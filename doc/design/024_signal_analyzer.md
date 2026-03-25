# Design 024: Signal Analyzer Tab

**Date:** 2025-03-25
**Branch:** plutosdr
**Status:** Design — Not Yet Implemented

---

## 1. Problem Statement

When monitoring the RF spectrum with SDRTrunk, there are many signals visible in the waterfall
and spectrum displays that remain unidentified. In the 400 MHz, 700–900 MHz, and other bands,
there are numerous PSK, FSK, AM, FM, and digital signals whose protocol and purpose are unknown.

Currently, identifying a signal requires:
1. Visually spotting it in the spectrum display
2. Manually estimating its center frequency and bandwidth
3. Creating a channel in the Playlist Editor with a guessed decoder type
4. Starting the channel and observing whether valid messages are decoded
5. Repeating with different decoder types until a match is found (or giving up)

This is tedious, error-prone, and doesn't scale. There's no way to distinguish real signals
from harmonics, images, or DC spurs without manual retune tests.

## 2. Proposed Solution

Add a new **"Signal Analyzer"** tab to the main application window (after Playlist Editor)
that provides an automated, multi-layer signal analysis pipeline:

| Layer | Name | Function |
|-------|------|----------|
| 1 | **Signal Detection** | Find signals above noise floor from FFT data |
| 2 | **Signal Validation** | Filter out harmonics, images, DC spurs, noise |
| 3 | **Signal Characterization** | Determine modulation type, symbol rate, bandwidth |
| 4 | **Protocol Identification** | Try SDRTrunk decoders to identify protocol |

The analyzer operates in two modes:
- **Manual mode**: User clicks on signals or controls scanning step-by-step
- **Auto mode**: Continuous scanning with automated detection → characterization → identification

A detail panel (inspired by the existing "Channel" tab in Now Playing) provides per-signal
spectrum/squelch visualization when running decoder trials.

## 3. What Exists Today

### 3.1 Main Tab Architecture

The main window (`SDRTrunk.java`) creates a `ControllerPanel` which holds a `JideTabbedPane`
with four tabs:

```
┌──────────────┬──────┬────────┬─────────────────┐
│ Now Playing  │ Map  │ Tuners │ Playlist Editor  │
└──────────────┴──────┴────────┴─────────────────┘
```

Tabs are Swing `JPanel` instances added in `ControllerPanel.init()`. The "Playlist Editor"
tab is special — it fires `ViewPlaylistRequest` via EventBus to open a separate JavaFX window.

### 3.2 FFT/DFT Data Pipeline

```
Tuner (I/Q samples)
  │
  ▼
SpectralDisplayPanel (implements Listener<INativeBuffer>)
  │
  ▼
ComplexDftProcessor
  │  - Accumulates samples in NativeBufferManager
  │  - Runs at ~20 fps on ScheduledExecutorService
  │  - Applies Blackman-Harris 7 window
  │  - JTransforms FloatFFT_1D.complexForward()
  │  - Default DFT size: 4096 bins
  │
  ▼
ComplexDecibelConverter (extends DFTResultsConverter)
  │  - Converts complex FFT → float[] of dB values
  │  - Formula: 20 * log10(sqrt(I² + Q²) / (DFTSize/2))
  │  - Swaps upper/lower halves for correct frequency ordering
  │  - Length = DFT size (4096 by default)
  │
  ├──▶ SpectrumPanel (DFTResultsListener) — draws line graph
  └──▶ WaterfallPanel (DFTResultsListener) — draws scrolling waterfall
```

**Bin-to-frequency mapping:**
```
frequency[bin] = centerFrequency - (bandwidth/2) + bin * (bandwidth / DFTSize)
```

### 3.3 Available Decoders (DecoderType enum)

**Primary Decoders** (operate on I/Q sample streams):

| DecoderType | Modulation | Symbol Rate | Protocol |
|-------------|-----------|-------------|----------|
| `P25_PHASE1` | C4FM (4FSK) | 9600 baud | APCO25 |
| `P25_PHASE2` | H-DQPSK | 12000 baud | APCO25 Phase 2 |
| `DMR` | 4FSK | 9600 baud | DMR |
| `LTR` | FSK | 300 baud | LTR |
| `LTR_NET` | FSK | 300 baud | LTR-Net |
| `MPT1327` | FFSK | 1200 baud | MPT1327 |
| `PASSPORT` | FSK | — | Passport |
| `NBFM` | FM | — | Analog FM |
| `AM` | AM | — | Analog AM |

**Auxiliary Decoders** (operate on demodulated audio, in-band signaling):
- DCS, Fleetsync II, LJ1200/LoJack, MDC1200, Tait 1200

### 3.4 Channel Processing Pipeline

```
Channel (configuration)
  │
  ▼
ChannelProcessingManager.start(channel)
  │
  ├── TunerManager.getSource(sourceConfig, channelSpec) → Source (TunerChannelSource)
  │
  ├── DecoderFactory.getModules(channel) → List<Module>
  │     ├── Decoder (e.g. P25P1Decoder) — sync detection, message framing
  │     ├── DecoderState (e.g. P25P1DecoderState) — protocol state machine
  │     ├── MessageHistory — recent message buffer
  │     ├── EventLogger — event logging
  │     └── ... (audio, recording, etc.)
  │
  └── ProcessingChain
        ├── Source → Decoder → DecoderState → Messages
        ├── DecoderStateEvent broadcasts (DECODE, START, END, etc.)
        └── State machine: IDLE → ACTIVE → CALL/DATA/CONTROL → FADE → TEARDOWN → IDLE
```

**Key events for sync/message detection:**
- `DecoderStateEvent(Event.DECODE, State.CONTROL)` — valid control message decoded
- `DecoderStateEvent(Event.START, State.CALL)` — call detected
- `DecoderStateEvent(Event.CONTINUATION, State.*)` — ongoing activity
- `MessageHistory.receive(IMessage)` — every decoded message buffered
- `SyncDetectListener` — framer-level sync pattern detected (available in P25, DMR framers)

### 3.5 Channel Spectrum Panel (Now Playing → "Channel" tab)

The `ChannelSpectrumPanel` provides per-channel visualization:
- **Channel FFT spectrum** via `SpectrumPanel` + `FrequencyOverlayPanel`
- **Carrier offset display** (measured frequency error)
- **Noise floor spinner** (adjustable 8–36)
- **Squelch controls** (for NBFM/analog channels)
- Connects to processing chain via `ComplexSamplesToNativeBufferModule`

This pattern can be reused in the Signal Analyzer for per-signal detail views.

### 3.6 Tuner Access

```java
TunerManager.getAvailableTuners() → List<DiscoveredTuner>
  │
  ▼
DiscoveredTuner.getTuner() → Tuner
  │
  ├── .getTunerController().getFrequency()     → center freq (long Hz)
  ├── .getTunerController().getBandwidth()      → bandwidth (int Hz)
  ├── .getTunerController().getSampleRate()     → sample rate (double)
  ├── .getTunerController().getUsableBandwidth() → usable BW after rolloff
  ├── .getPreferredName()                       → display name
  └── .getChannelSourceManager()                → manages channel allocations
```

---

## 4. Architecture Design

### 4.1 High-Level Component Diagram

```
                    ┌─────────────────────────────────────────────┐
                    │           Signal Analyzer Tab                │
                    │                                              │
                    │  ┌──────────────────────────────────────┐   │
                    │  │      SignalAnalyzerPanel (UI)         │   │
                    │  │  ┌────────────┐ ┌──────────────────┐ │   │
                    │  │  │  Controls   │ │  Tuner Selector  │ │   │
                    │  │  │  Start/Stop │ │  Threshold       │ │   │
                    │  │  │  Mode       │ │  Dwell Time      │ │   │
                    │  │  └────────────┘ └──────────────────┘ │   │
                    │  │  ┌──────────────────────────────────┐ │   │
                    │  │  │    Signal Table                   │ │   │
                    │  │  │  Freq|Power|BW|Mod|Sym|Proto|Conf │ │   │
                    │  │  │  ──────────────────────────────── │ │   │
                    │  │  │  ...rows...                       │ │   │
                    │  │  └──────────────────────────────────┘ │   │
                    │  │  ┌────────────────┐ ┌───────────────┐ │   │
                    │  │  │ Signal Detail   │ │ Analysis Log  │ │   │
                    │  │  │ (spectrum view) │ │ (scrolling)   │ │   │
                    │  │  └────────────────┘ └───────────────┘ │   │
                    │  └──────────────────────────────────────┘   │
                    │                                              │
                    │  ┌──────────────────────────────────────┐   │
                    │  │    SignalAnalyzerController            │   │
                    │  │    (orchestration + state machine)     │   │
                    │  └───────┬───────────┬───────────┬──────┘   │
                    │          │           │           │           │
                    │    ┌─────▼──┐  ┌─────▼─────┐ ┌──▼────────┐ │
                    │    │Detector│  │Characteriz.│ │Identifier │ │
                    │    │(FFT)   │  │(I/Q)       │ │(Decoders) │ │
                    │    └────────┘  └───────────┘ └───────────┘ │
                    └─────────────────────────────────────────────┘
                          ▲               ▲              ▲
                          │               │              │
              ┌───────────┘     ┌─────────┘    ┌─────────┘
              │                 │               │
    ┌─────────┴────┐  ┌────────┴───┐  ┌────────┴──────────┐
    │SpectralDisplay│  │TunerChannel│  │ChannelProcessing  │
    │Panel (DFT)   │  │Source (I/Q)│  │Manager (decoders) │
    └──────────────┘  └────────────┘  └───────────────────┘
```

### 4.2 Package Structure

```
src/main/java/io/github/dsheirer/gui/analyzer/
├── SignalAnalyzerPanel.java              ← Main Swing JPanel (the tab)
├── SignalAnalyzerController.java         ← Orchestration state machine
├── SignalAnalyzerConfig.java             ← User-configurable settings
│
├── detection/
│   ├── SignalDetector.java               ← DFTResultsListener, FFT peak finding
│   ├── DetectedSignal.java              ← Data model for a detected signal
│   ├── SignalTracker.java                ← Track signals over time (persistence, appearance)
│   └── HarmonicAnalyzer.java            ← Harmonic/image/spur/DC detection
│
├── characterization/
│   ├── SignalCharacterizer.java          ← I/Q sample analysis engine
│   ├── ModulationType.java              ← Enum: FM_NARROW, C4FM, FSK2, FSK4, QPSK, etc.
│   ├── CharacterizationResult.java      ← Result: mod type, symbol rate, BW, confidence
│   ├── EnvelopeAnalyzer.java            ← Constant envelope detection (FM/PSK vs AM)
│   ├── FrequencyAnalyzer.java           ← Instantaneous freq analysis, FM deviation
│   └── SymbolRateEstimator.java         ← Symbol rate from zero-crossing/autocorrelation
│
├── identification/
│   ├── SignalIdentifier.java            ← Decoder trial engine
│   ├── IdentificationResult.java        ← Result: protocol, confidence, messages
│   ├── DecoderTrialConfig.java          ← Config for a single decoder trial
│   └── DecoderTrialListener.java        ← Interface for trial result callbacks
│
├── ui/
│   ├── SignalAnalysisTableModel.java    ← AbstractTableModel for signal table
│   ├── SignalDetailPanel.java           ← Per-signal spectrum/detail view
│   ├── AnalysisLogPanel.java            ← Scrolling log text area
│   └── AnalyzerControlPanel.java        ← Start/Stop, tuner select, threshold controls
│
└── logging/
    └── SignalAnalysisLog.java            ← CSV file + in-memory log writing
```

### 4.3 Data Flow

```
Phase 1: DETECTION
═══════════════════

SpectralDisplayPanel ──DFT float[]──▶ SignalDetector
                                        │
                    ┌───────────────────┘
                    │
                    ▼
            SignalDetector.processDftResults(float[] dBValues)
                │
                ├── 1. Accumulate frames (rolling average, ~10 frames = 0.5s)
                ├── 2. Estimate noise floor (median of all bins)
                ├── 3. Find peaks above threshold (noiseFloor + userThreshold)
                ├── 4. Cluster adjacent bins into signal groups
                ├── 5. Estimate center freq + bandwidth for each cluster
                └── 6. Pass to SignalTracker
                         │
                         ├── Track persistence (≥3 cycles to confirm)
                         ├── Track signal history (first seen, last seen)
                         └── Emit List<DetectedSignal> to controller


Phase 2: VALIDATION
═══════════════════

List<DetectedSignal> ──▶ HarmonicAnalyzer
                            │
                            ├── Check for DC spike (within ±1 kHz of center freq)
                            ├── Check for image signals (mirror around center freq)
                            ├── Check for harmonic relationships (f, 2f, 3f)
                            ├── Flag likely spurs (constant power, exactly at known spur freqs)
                            └── Set flags on DetectedSignal: VALID, DC_SPIKE, IMAGE,
                                HARMONIC, SPUR, INTERMITTENT, CONTINUOUS


Phase 3: CHARACTERIZATION
═════════════════════════

DetectedSignal (VALID) ──▶ SignalCharacterizer
                              │
                              ├── Request I/Q samples from TunerChannelSource
                              │   at detected frequency (12.5 kHz or 25 kHz BW)
                              │
                              ├── EnvelopeAnalyzer
                              │   ├── Compute amplitude envelope: |I + jQ|
                              │   ├── Calculate variance/std deviation
                              │   ├── Low variance → constant envelope (FM, FSK, PSK)
                              │   └── High variance → AM, SSB, or data bursts
                              │
                              ├── FrequencyAnalyzer
                              │   ├── Compute instantaneous frequency: d(phase)/dt
                              │   ├── Measure FM deviation (±kHz)
                              │   ├── Detect discrete frequency levels → FSK
                              │   │   ├── 2 levels → 2FSK
                              │   │   └── 4 levels → 4FSK / C4FM
                              │   └── No discrete levels + constant envelope → PSK
                              │
                              ├── SymbolRateEstimator
                              │   ├── Autocorrelation of inst. freq. derivative
                              │   ├── Peak detection → symbol period
                              │   ├── Common rates: 1200, 2400, 4800, 9600, 12000 baud
                              │   └── No detectable rate → analog or continuous carrier
                              │
                              └── Emit CharacterizationResult:
                                    ├── modulationType: FM_NARROW, FM_WIDE, AM, C4FM,
                                    │                   FSK2, FSK4, QPSK, DQPSK, UNKNOWN_DIGITAL,
                                    │                   UNKNOWN_ANALOG, CARRIER_ONLY
                                    ├── symbolRate: int (0 if analog)
                                    ├── bandwidth3dB: double (Hz)
                                    ├── bandwidth6dB: double (Hz)
                                    ├── fmDeviation: double (Hz, if FM)
                                    └── confidence: HIGH, MEDIUM, LOW


Phase 4: IDENTIFICATION
═══════════════════════

CharacterizationResult ──▶ SignalIdentifier
                              │
                              ├── Select candidate decoders based on modulation + symbol rate:
                              │   ┌─────────────────────────────────────────────┐
                              │   │ Modulation  │ SymRate │ Candidates          │
                              │   ├─────────────┼─────────┼─────────────────────│
                              │   │ C4FM/4FSK   │ 9600    │ P25_PHASE1, DMR     │
                              │   │ QPSK/DQPSK  │ 12000   │ P25_PHASE2          │
                              │   │ 4FSK        │ 4800    │ NXDN (future)       │
                              │   │ 2FSK        │ 300     │ LTR, LTR_NET        │
                              │   │ FFSK        │ 1200    │ MPT1327             │
                              │   │ 2FSK        │ any     │ PASSPORT            │
                              │   │ FM_NARROW   │ —       │ NBFM + aux decoders │
                              │   │ AM          │ —       │ AM                  │
                              │   │ UNKNOWN     │ any     │ try all primary     │
                              │   └─────────────┴─────────┴─────────────────────┘
                              │
                              ├── For each candidate decoder (in priority order):
                              │   │
                              │   ├── Create temporary Channel:
                              │   │   ├── SourceConfigTuner at detected frequency
                              │   │   ├── DecodeConfiguration for candidate decoder
                              │   │   └── Type = STANDARD (temporary)
                              │   │
                              │   ├── Start via ChannelProcessingManager.start(channel)
                              │   │
                              │   ├── Attach listeners:
                              │   │   ├── DecoderStateEvent listener → watch for DECODE events
                              │   │   ├── IMessage listener → count valid messages
                              │   │   └── Sync detect callback → framer-level sync hits
                              │   │
                              │   ├── Dwell for timeout (configurable, default 5 seconds)
                              │   │
                              │   ├── Evaluate:
                              │   │   ├── syncHits > 0 AND validMessages ≥ 3 → HIGH confidence
                              │   │   ├── syncHits > 0 AND validMessages 1-2 → MEDIUM
                              │   │   ├── syncHits > 0 AND validMessages == 0 → LOW
                              │   │   └── syncHits == 0 → NO_MATCH, try next decoder
                              │   │
                              │   └── Stop channel, clean up
                              │
                              └── Emit IdentificationResult:
                                    ├── decoderType: DecoderType (or null if no match)
                                    ├── confidence: HIGH, MEDIUM, LOW, NONE
                                    ├── syncHits: int
                                    ├── validMessages: int
                                    ├── sampleMessages: List<String> (first few decoded messages)
                                    └── trialDuration: long (ms)
```

---

## 5. UI Design

### 5.1 Tab Placement

```
┌──────────────┬──────┬────────┬─────────────────┬──────────────────┐
│ Now Playing  │ Map  │ Tuners │ Playlist Editor  │ Signal Analyzer  │
└──────────────┴──────┴────────┴─────────────────┴──────────────────┘
```

Added as the 5th tab in `ControllerPanel.init()`. Unlike Playlist Editor (which opens a
separate window), Signal Analyzer is an embedded panel.

### 5.2 Panel Layout

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ CONTROLS (AnalyzerControlPanel)                                             │
│ ┌─────────┐ ┌─────────┐  Tuner: [▼ RSP1A - 855MHz ]  Threshold: [═●═ +10] │
│ │▶ Start  │ │⏹ Stop   │  Mode: (●)Auto ( )Manual      Dwell: [5s ▼]       │
│ └─────────┘ └─────────┘  Min BW: [6.25kHz ▼]  [⟳ Spur Test] [Clear All]  │
├─────────────────────────────────────────────────────────────────────────────┤
│ SIGNAL TABLE (SignalAnalysisTableModel)                                [top]│
│ ┌────────────┬──────┬───────┬──────────┬───────┬─────────┬──────┬─────────┐│
│ │ Frequency  │Power │  BW   │Modulation│SymRate│Protocol │Confid│ Flags   ││
│ │    (MHz)   │ (dB) │ (kHz) │          │(baud) │         │      │         ││
│ ├────────────┼──────┼───────┼──────────┼───────┼─────────┼──────┼─────────┤│
│ │ 851.0125   │-38.2 │ 12.5  │ C4FM     │ 9600  │ P25-1   │ HIGH │         ││
│ │ 853.4625   │-42.1 │ 12.5  │ 4FSK     │ 9600  │ DMR     │ HIGH │         ││
│ │ 855.4875   │-45.3 │ 12.5  │ 4FSK     │ 9600  │ (test)  │  —   │         ││
│ │ 857.5000   │-51.7 │ 12.5  │ FM-Narrow│  —    │ NBFM    │ LOW  │CTCSS131 ││
│ │ 460.5000   │-55.2 │  6.25 │ 2FSK     │ 1200  │ Unknown │  —   │         ││
│ │ 858.0000   │-48.0 │  ~0   │ Carrier  │  —    │  —      │  —   │⚠DC Spur││
│ │ 861.0250   │-62.1 │ 12.5  │ Unknown  │  —    │  —      │  —   │⚠Harmnic││
│ └────────────┴──────┴───────┴──────────┴───────┴─────────┴──────┴─────────┘│
│ Right-click: [Identify Now] [Create Channel] [Ignore] [Delete] [Retune Test]│
├─────────────────────────────────────────────────────────────────────────────┤
│ BOTTOM SPLIT                                                        [bottom]│
│ ┌─────────────────────────────┐ ┌─────────────────────────────────────────┐ │
│ │ SIGNAL DETAIL               │ │ ANALYSIS LOG                           │ │
│ │ (SignalDetailPanel)         │ │ (AnalysisLogPanel)                     │ │
│ │                             │ │                                        │ │
│ │ ┌─────────────────────────┐ │ │ [19:08:15] 851.0125 MHz: Detected     │ │
│ │ │  Channel Spectrum FFT   │ │ │   power -38.2 dB, BW ~12.5 kHz       │ │
│ │ │  (like Now Playing →    │ │ │ [19:08:16] 851.0125 MHz: Modulation:  │ │
│ │ │   Channel tab)          │ │ │   C4FM (const envelope, 9600 sym/s)   │ │
│ │ │                         │ │ │ [19:08:18] 851.0125 MHz: P25 Phase 1  │ │
│ │ │ ~~~~~~/\~~~~/\~~~~      │ │ │   — NID sync, 5 valid frames in 2s   │ │
│ │ │                         │ │ │ [19:08:18] 851.0125 MHz: ✓ IDENTIFIED│ │
│ │ └─────────────────────────┘ │ │   P25 Phase 1 (HIGH confidence)       │ │
│ │                             │ │ [19:08:20] 858.0000 MHz: ⚠ DC offset │ │
│ │ Freq: 851.0125 MHz         │ │   (within 1kHz of center)             │ │
│ │ Offset: +125 Hz            │ │ [19:08:25] 861.0250 MHz: ⚠ Possible  │ │
│ │ Noise Floor: -72.3 dB      │ │   harmonic of 430.5125 MHz            │ │
│ │ State: CONTROL              │ │                                        │ │
│ │                             │ │ [Export CSV] [Clear Log]               │ │
│ └─────────────────────────────┘ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.3 Right-Click Context Menu

When right-clicking a signal row:
- **Identify Now** — Force immediate decoder trial on this signal (manual mode)
- **Create Channel** — Open dialog to create a playlist channel from this signal with pre-filled frequency, decoder type, etc.
- **Re-characterize** — Re-run modulation analysis
- **Spur Test** — Briefly retune center freq ±100 kHz to check if signal moves
- **Ignore** — Mark signal as ignored (won't re-scan)
- **Delete** — Remove from table

### 5.4 Signal Detail Panel

Inspired by the existing `ChannelSpectrumPanel` in the Now Playing "Channel" tab:
- Shows channel-level FFT spectrum for the selected signal
- Displays carrier offset, noise floor
- When a decoder trial is running, shows decoder state (IDLE → SYNC → DECODE → etc.)
- Updates in real-time during identification

---

## 6. Detailed Class Design

### 6.1 Data Models

#### DetectedSignal.java
```java
public class DetectedSignal {
    // Identity
    private long id;                          // unique ID
    private long frequencyHz;                 // center frequency in Hz
    private double powerDb;                   // peak power in dB
    private double bandwidthHz;               // estimated bandwidth in Hz

    // Detection metadata
    private Instant firstSeen;
    private Instant lastSeen;
    private int detectionCount;               // frames detected
    private boolean persistent;               // detected ≥3 consecutive cycles

    // Validation flags
    private EnumSet<SignalFlag> flags;         // DC_SPIKE, IMAGE, HARMONIC, SPUR, etc.

    // Characterization (filled by Layer 3)
    private ModulationType modulationType;
    private int symbolRate;                   // 0 if unknown/analog
    private double bandwidth3dB;
    private double bandwidth6dB;
    private double fmDeviation;
    private CharacterizationConfidence charConfidence;

    // Identification (filled by Layer 4)
    private DecoderType identifiedDecoder;
    private IdentificationConfidence idConfidence;
    private int syncHits;
    private int validMessages;
    private List<String> sampleMessages;

    // Status
    private AnalysisStatus status;            // DETECTED, VALIDATING, CHARACTERIZING,
                                              // IDENTIFYING, IDENTIFIED, NO_MATCH, FLAGGED, IGNORED
}
```

#### SignalFlag.java (Enum)
```java
public enum SignalFlag {
    DC_SPIKE,          // Near center frequency, likely LO leakage
    IMAGE,             // Mirror of another signal around center freq
    HARMONIC,          // Appears to be harmonic of lower-frequency signal
    SPUR,              // Moves with retune, not a real signal
    INTERMITTENT,      // Bursty, not always present
    CONTINUOUS,        // Always present (carrier or control channel)
    WIDEBAND,          // Wider than typical narrowband channel
    NARROWBAND         // Typical narrowband channel
}
```

#### ModulationType.java (Enum)
```java
public enum ModulationType {
    FM_NARROW("NFM", "Narrowband FM", false),
    FM_WIDE("WFM", "Wideband FM", false),
    AM("AM", "Amplitude Modulation", false),
    C4FM("C4FM", "Continuous 4-level FM", true),
    FSK2("2FSK", "2-level FSK", true),
    FSK4("4FSK", "4-level FSK", true),
    BPSK("BPSK", "Binary Phase Shift Keying", true),
    QPSK("QPSK", "Quadrature PSK", true),
    DQPSK("DQPSK", "Differential QPSK", true),
    PI4_DQPSK("π/4-DQPSK", "π/4 Differential QPSK", true),
    CARRIER_ONLY("CW", "Carrier/CW", false),
    UNKNOWN_DIGITAL("UNK-D", "Unknown Digital", true),
    UNKNOWN_ANALOG("UNK-A", "Unknown Analog", false),
    UNKNOWN("UNK", "Unknown", false);

    private String shortName;
    private String displayName;
    private boolean digital;
}
```

#### AnalysisStatus.java (Enum)
```java
public enum AnalysisStatus {
    DETECTED,          // Just found in FFT
    VALIDATING,        // Checking for harmonics/spurs
    CHARACTERIZING,    // Running modulation analysis
    IDENTIFYING,       // Running decoder trials
    IDENTIFIED,        // Protocol successfully identified
    NO_MATCH,          // All decoders tried, none matched
    FLAGGED,           // Flagged as spur/harmonic/image
    IGNORED            // User chose to ignore
}
```

#### CharacterizationResult.java
```java
public class CharacterizationResult {
    private ModulationType modulationType;
    private int estimatedSymbolRate;           // baud, 0 if analog/unknown
    private double bandwidth3dB;               // Hz
    private double bandwidth6dB;               // Hz
    private double fmDeviation;                // Hz (if FM-family)
    private double envelopeVariance;           // normalized 0.0–1.0
    private int fskLevels;                     // 0, 2, or 4
    private Confidence confidence;             // HIGH, MEDIUM, LOW
    private String analysisNotes;              // human-readable description
}
```

#### IdentificationResult.java
```java
public class IdentificationResult {
    private DecoderType decoderType;           // null if no match
    private Confidence confidence;             // HIGH, MEDIUM, LOW, NONE
    private int syncHits;
    private int validMessages;
    private int invalidMessages;
    private long trialDurationMs;
    private List<String> sampleMessages;       // first few decoded messages as strings
    private String notes;
}
```

### 6.2 Core Engine Classes

#### SignalDetector.java
```java
/**
 * Listens to DFT results (float[] dB values) and detects signals above noise floor.
 * Implements DFTResultsListener to tap into the existing FFT pipeline.
 */
public class SignalDetector implements DFTResultsListener {

    // Configuration
    private double thresholdDb = 10.0;        // dB above noise floor
    private double minBandwidthHz = 6250;     // minimum signal bandwidth to report
    private int averagingFrames = 10;         // frames to average (0.5s at 20fps)

    // State
    private long centerFrequencyHz;
    private int bandwidthHz;
    private int dftSize;
    private float[][] frameBuffer;            // circular buffer of DFT frames
    private float[] averagedSpectrum;         // averaged power spectrum

    // Output
    private Listener<List<DetectedSignal>> signalListener;

    @Override
    public void receive(float[] dftResults) {
        // 1. Add to frame buffer
        // 2. Compute rolling average
        // 3. Estimate noise floor (median)
        // 4. Find peaks above threshold
        // 5. Cluster adjacent bins
        // 6. Convert bin clusters to DetectedSignal objects
        // 7. Notify listener
    }

    /**
     * Estimate noise floor using median of all bins.
     * More robust than mean (not skewed by strong signals).
     */
    private double estimateNoiseFloor(float[] spectrum) { ... }

    /**
     * Find contiguous runs of bins above threshold.
     * Returns list of (startBin, endBin, peakBin, peakPower) tuples.
     */
    private List<BinCluster> findPeaks(float[] spectrum, double threshold) { ... }

    /**
     * Convert bin index to frequency.
     */
    private long binToFrequency(int bin) {
        return centerFrequencyHz - (bandwidthHz / 2) + (long)(bin * ((double)bandwidthHz / dftSize));
    }
}
```

#### HarmonicAnalyzer.java
```java
/**
 * Analyzes detected signals for harmonics, images, DC spurs, and other artifacts.
 */
public class HarmonicAnalyzer {

    private long centerFrequencyHz;

    /**
     * Analyze a list of detected signals and set appropriate flags.
     */
    public void analyze(List<DetectedSignal> signals) {
        flagDcSpikes(signals);
        flagImages(signals);
        flagHarmonics(signals);
    }

    /**
     * Signals within ±1 kHz of center frequency → DC_SPIKE flag.
     */
    private void flagDcSpikes(List<DetectedSignal> signals) { ... }

    /**
     * For each signal, check if there's a mirror at (2 * center - freq).
     * If both exist with similar power, one is likely an image.
     */
    private void flagImages(List<DetectedSignal> signals) { ... }

    /**
     * Check for integer frequency relationships (f, 2f, 3f).
     * Lower frequency = potential fundamental, higher = harmonic.
     */
    private void flagHarmonics(List<DetectedSignal> signals) { ... }
}
```

#### SignalCharacterizer.java
```java
/**
 * Characterizes a detected signal by analyzing raw I/Q samples.
 * Creates a temporary TunerChannelSource to extract narrowband I/Q at the signal frequency.
 */
public class SignalCharacterizer {

    private TunerManager tunerManager;

    /**
     * Characterize a single signal. Blocks for analysisTimeMs while collecting samples.
     * Returns characterization result with modulation type, symbol rate, etc.
     */
    public CompletableFuture<CharacterizationResult> characterize(
            DetectedSignal signal, int analysisTimeMs) {

        // 1. Request TunerChannelSource at signal frequency
        //    with bandwidth = max(signal.bandwidth * 2, 25000)
        // 2. Collect complex samples for analysisTimeMs
        // 3. Run EnvelopeAnalyzer → constant vs varying envelope
        // 4. Run FrequencyAnalyzer → FM deviation, FSK level count
        // 5. Run SymbolRateEstimator → baud rate
        // 6. Classify modulation type from combined results
        // 7. Release TunerChannelSource
        // 8. Return CharacterizationResult
    }
}
```

#### SignalIdentifier.java
```java
/**
 * Tries SDRTrunk decoders on a detected signal to identify its protocol.
 * Creates temporary channels, monitors for sync/message events, and scores results.
 */
public class SignalIdentifier {

    private ChannelProcessingManager channelProcessingManager;
    private ChannelModel channelModel;
    private TunerManager tunerManager;

    /**
     * Get ordered list of candidate decoders for a given modulation type and symbol rate.
     */
    public List<DecoderType> getCandidateDecoders(CharacterizationResult charResult) {
        // Map modulation + symbol rate to likely decoder types
        // See table in Section 4.3
    }

    /**
     * Try a single decoder on a signal. Returns identification result after dwellTimeMs.
     */
    public CompletableFuture<IdentificationResult> tryDecoder(
            DetectedSignal signal, DecoderType decoder, int dwellTimeMs) {

        // 1. Create Channel with SourceConfigTuner at signal freq
        // 2. Set DecodeConfiguration for decoder type
        // 3. Start channel
        // 4. Attach DecoderStateEvent listener (count DECODE events)
        // 5. Attach IMessage listener (count valid messages)
        // 6. Wait dwellTimeMs
        // 7. Stop channel, collect stats
        // 8. Score confidence
        // 9. Return IdentificationResult
    }

    /**
     * Try all candidate decoders sequentially. Stop on first HIGH confidence match.
     */
    public CompletableFuture<IdentificationResult> identifySignal(
            DetectedSignal signal, CharacterizationResult charResult, int dwellTimeMs) {

        List<DecoderType> candidates = getCandidateDecoders(charResult);
        // Try each candidate in order
        // Return first HIGH match, or best MEDIUM/LOW match, or NONE
    }
}
```

#### SignalAnalyzerController.java
```java
/**
 * Main orchestrator. Manages the analysis state machine:
 * IDLE → SCANNING → DETECTING → VALIDATING → CHARACTERIZING → IDENTIFYING → COMPLETE
 *
 * Supports two modes:
 * - AUTO: Continuously scans, detects, characterizes, and identifies
 * - MANUAL: User triggers each step or selects individual signals to analyze
 */
public class SignalAnalyzerController {

    // Dependencies
    private SignalDetector detector;
    private HarmonicAnalyzer harmonicAnalyzer;
    private SignalCharacterizer characterizer;
    private SignalIdentifier identifier;
    private SignalAnalysisLog log;

    // State
    private AnalyzerMode mode;                // AUTO or MANUAL
    private AnalyzerState state;              // IDLE, SCANNING, etc.
    private List<DetectedSignal> currentSignals;
    private int currentSignalIndex;           // which signal is being analyzed

    // Configuration
    private SignalAnalyzerConfig config;

    // UI callbacks
    private Listener<List<DetectedSignal>> tableUpdateListener;
    private Listener<String> logListener;

    /**
     * Start scanning. In AUTO mode, begins the full pipeline.
     * In MANUAL mode, starts detection only.
     */
    public void startScan() { ... }

    /**
     * Stop scanning and any ongoing analysis.
     */
    public void stopScan() { ... }

    /**
     * Manual mode: user selects a signal and triggers identification.
     */
    public void identifySignal(DetectedSignal signal) { ... }

    /**
     * Called by SignalDetector when new signals are detected.
     */
    private void onSignalsDetected(List<DetectedSignal> signals) {
        // 1. Merge with existing signal list (update existing, add new)
        // 2. Run HarmonicAnalyzer
        // 3. Update table
        // 4. In AUTO mode, proceed to characterization of new signals
    }

    /**
     * Auto mode: process the next uncharacterized signal.
     */
    private void processNextSignal() {
        // 1. Find next signal with status DETECTED or VALIDATED
        // 2. Set status to CHARACTERIZING
        // 3. Run SignalCharacterizer
        // 4. On completion, set status to IDENTIFYING
        // 5. Run SignalIdentifier
        // 6. On completion, set status to IDENTIFIED or NO_MATCH
        // 7. Log result
        // 8. Process next signal
    }
}
```

### 6.3 UI Classes

#### SignalAnalyzerPanel.java
```java
/**
 * Main Swing panel for the Signal Analyzer tab.
 * Layout: controls (top) → signal table (middle) → detail + log split (bottom)
 */
public class SignalAnalyzerPanel extends JPanel {

    private AnalyzerControlPanel controlPanel;
    private JTable signalTable;
    private SignalAnalysisTableModel tableModel;
    private SignalDetailPanel detailPanel;
    private AnalysisLogPanel logPanel;

    // Wired to controller
    private SignalAnalyzerController controller;

    public SignalAnalyzerPanel(PlaylistManager playlistManager,
                                TunerManager tunerManager,
                                UserPreferences userPreferences) {
        // Build UI with MigLayout
        // Wire controls to controller
        // Set up table selection → detail panel updates
        // Set up right-click context menu
    }
}
```

#### SignalDetailPanel.java
```java
/**
 * Per-signal detail view, inspired by ChannelSpectrumPanel.
 * Shows channel-level FFT spectrum for the selected signal.
 * When a decoder trial is active, shows decoder state info.
 */
public class SignalDetailPanel extends JPanel {

    private SpectrumPanel spectrumPanel;      // Reuse existing spectrum renderer
    private ComplexDftProcessor dftProcessor;
    private JLabel frequencyLabel;
    private JLabel carrierOffsetLabel;
    private JLabel noiseFloorLabel;
    private JLabel decoderStateLabel;
    private JLabel statusLabel;

    /**
     * Show detail for the given signal. If a decoder trial is active,
     * connect to its processing chain for live spectrum display.
     */
    public void showSignal(DetectedSignal signal) { ... }
}
```

#### SignalAnalysisTableModel.java
```java
/**
 * Table model for the signal table.
 * Columns: Frequency, Power, BW, Modulation, SymRate, Protocol, Confidence, Flags, Status
 */
public class SignalAnalysisTableModel extends AbstractTableModel {

    private static final String[] COLUMN_NAMES = {
        "Frequency (MHz)", "Power (dB)", "BW (kHz)", "Modulation",
        "Sym Rate", "Protocol", "Confidence", "Flags", "Status"
    };

    private List<DetectedSignal> signals = new ArrayList<>();

    // Standard table model methods + signal add/update/remove
    public void updateSignals(List<DetectedSignal> newSignals) { ... }
}
```

---

## 7. Integration Points

### 7.1 ControllerPanel.java — Add New Tab

```java
// In ControllerPanel constructor, add:
mSignalAnalyzerPanel = new SignalAnalyzerPanel(playlistManager, tunerManager, userPreferences);

// In ControllerPanel.init(), before the Playlist Editor tab:
mTabbedPane.addTab("Signal Analyzer", mSignalAnalyzerPanel);
// Adjust mSettingsTabIndex since Playlist Editor tab index shifts by 1
```

### 7.2 SpectralDisplayPanel.java — Expose DFT Data Feed

Add a method to register an additional `DFTResultsListener`:

```java
// In SpectralDisplayPanel:
public void addDftResultsListener(DFTResultsListener listener) {
    mComplexDecibelConverter.addListener(listener);
}

public void removeDftResultsListener(DFTResultsListener listener) {
    mComplexDecibelConverter.removeListener(listener);
}
```

The `ComplexDecibelConverter` (which extends `DFTResultsConverter`) already has listener
management — we just need to expose it from `SpectralDisplayPanel`.

### 7.3 SDRTrunk.java — Wire Dependencies

The main `SDRTrunk` class constructs `ControllerPanel` and `SpectralDisplayPanel`.
It needs to:
1. Pass `TunerManager` and `PlaylistManager` to `SignalAnalyzerPanel`
2. After construction, wire the DFT feed: `spectralDisplayPanel.addDftResultsListener(signalAnalyzerPanel.getDetector())`

### 7.4 Source Event Handling

The `SignalDetector` needs to know the current center frequency and bandwidth to map
FFT bins to frequencies. It should implement `ISourceEventProcessor` to receive
`SourceEvent` notifications for frequency/bandwidth changes, same pattern as
`SpectralDisplayPanel`.

---

## 8. Signal Characterization Algorithms — Detail

### 8.1 Envelope Analysis (Constant vs. Varying Amplitude)

```
Input: Complex I/Q samples at signal frequency (e.g., 12.5 kHz BW, ~1s of data)

1. Compute amplitude envelope: a[n] = sqrt(I[n]² + Q[n]²)
2. Normalize: a_norm[n] = a[n] / mean(a)
3. Compute coefficient of variation: CV = std(a_norm) / mean(a_norm)

Decision:
  CV < 0.1  → Constant envelope (FM, FSK, PSK, CW)
  CV 0.1–0.3 → Mild variation (possibly filtered digital, or FM with deviation)
  CV > 0.3  → Amplitude modulated (AM, SSB, QAM)
```

### 8.2 Instantaneous Frequency Analysis

```
Input: Complex I/Q samples

1. Compute instantaneous phase: φ[n] = atan2(Q[n], I[n])
2. Unwrap phase: φ_u[n] (handle ±π wraps)
3. Compute instantaneous frequency: f[n] = (φ_u[n] - φ_u[n-1]) * Fs / (2π)
4. Analyze f[n]:

   a. Histogram of f[n] values:
      - 1 cluster at center → CW or PSK (phase changes, not frequency)
      - 2 clusters → 2FSK
      - 4 clusters → 4FSK / C4FM
      - Continuous spread → FM (analog)

   b. FM deviation = max(|f[n]|) or std(f[n])
      - ±2.5 kHz → narrowband FM
      - ±5 kHz → standard FM
      - ±75 kHz → broadcast FM (unlikely in these bands)

   c. For FSK: measure frequency offset between clusters
      - ±600 Hz shift, 2 levels → typical 1200 baud 2FSK
      - ±1800 Hz shift, 4 levels → typical 9600 baud 4FSK (DMR/P25)
```

### 8.3 Symbol Rate Estimation

```
Input: Instantaneous frequency f[n] (from 8.2)

Method 1: Autocorrelation
1. Compute autocorrelation of |d(f[n])/dt| (absolute frequency derivative)
2. Find first significant peak after zero lag → symbol period T
3. Symbol rate = Fs / T (samples per second / samples per symbol)

Method 2: Spectral Analysis of Envelope
1. Compute FFT of |d(f[n])/dt|²
2. Find strongest spectral peak → symbol clock frequency
3. Symbol rate = clock frequency

Common results to match:
  300 baud    → LTR, LTR-Net
  1200 baud   → MPT1327, POCSAG, Fleetsync
  2400 baud   → NXDN (4800 4FSK)
  4800 baud   → NXDN (9600 4FSK), DMR/P25 (after mapping)
  9600 baud   → P25 Phase 1 (C4FM), DMR (4FSK)
  12000 baud  → P25 Phase 2 (H-DQPSK)
```

### 8.4 Modulation Classification Decision Tree

```
                        Signal Detected
                             │
                    ┌────────▼────────┐
                    │ Envelope Const? │
                    └────┬───────┬────┘
                    Yes  │       │ No
                    ┌────▼──┐  ┌─▼─────────┐
                    │Const. │  │ AM-family  │
                    │Envlpe │  │ CV > 0.3   │
                    └───┬───┘  └─────┬──────┘
                        │            │
                ┌───────▼──────┐     ▼
                │ Inst. Freq   │   AM or SSB
                │ Analysis     │   (check BW
                └──┬───┬───┬──┘    symmetry)
                   │   │   │
            ┌──────┘   │   └──────┐
            ▼          ▼          ▼
        Continuous  2 clusters  4 clusters
        freq dist   in hist     in hist
            │          │          │
            ▼          ▼          ▼
         FM/PSK      2FSK       4FSK
            │          │          │
    ┌───────▼──┐       │    ┌────▼────┐
    │Phase     │       │    │Sym rate │
    │changes?  │       │    │~9600?   │
    └──┬────┬──┘       │    └─┬────┬──┘
    Yes│    │No        │   Yes│    │No
       ▼    ▼          ▼      ▼    ▼
     PSK  FM_NARROW  2FSK   C4FM  4FSK
     (QPSK, (check        (P25)
     DQPSK) deviation)
```

---

## 9. Implementation Phases

### Phase 1: Tab UI + Signal Detection (Minimum Viable Feature)

**Goal:** See all signals in the current tuner bandwidth, with power and bandwidth estimates,
harmonic/spur flagging.

**New files:**
- `gui/analyzer/SignalAnalyzerPanel.java`
- `gui/analyzer/SignalAnalyzerController.java`
- `gui/analyzer/SignalAnalyzerConfig.java`
- `gui/analyzer/detection/SignalDetector.java`
- `gui/analyzer/detection/DetectedSignal.java`
- `gui/analyzer/detection/SignalTracker.java`
- `gui/analyzer/detection/HarmonicAnalyzer.java`
- `gui/analyzer/ui/SignalAnalysisTableModel.java`
- `gui/analyzer/ui/AnalyzerControlPanel.java`
- `gui/analyzer/ui/AnalysisLogPanel.java`
- All enums: `SignalFlag`, `AnalysisStatus`, `ModulationType` (partial)

**Modified files:**
- `controller/ControllerPanel.java` — add tab
- `spectrum/SpectralDisplayPanel.java` — expose DFT listener registration
- `gui/SDRTrunk.java` — wire dependencies

**Estimated effort:** Medium — mostly UI + FFT analysis math

### Phase 2: Signal Characterization (Modulation Analysis)

**Goal:** For each detected signal, determine modulation type (FM, FSK, PSK, AM) and
estimate symbol rate.

**New files:**
- `gui/analyzer/characterization/SignalCharacterizer.java`
- `gui/analyzer/characterization/CharacterizationResult.java`
- `gui/analyzer/characterization/EnvelopeAnalyzer.java`
- `gui/analyzer/characterization/FrequencyAnalyzer.java`
- `gui/analyzer/characterization/SymbolRateEstimator.java`

**Key challenge:** Need to tap raw I/Q samples for a detected signal. This requires
allocating a `TunerChannelSource` at the detected frequency — same mechanism SDRTrunk
uses for channels, but we're using it for analysis rather than decoding.

**Estimated effort:** High — DSP algorithms for modulation classification

### Phase 3: Protocol Identification + Logging

**Goal:** Try SDRTrunk decoders on characterized signals, identify protocol, log results.

**New files:**
- `gui/analyzer/identification/SignalIdentifier.java`
- `gui/analyzer/identification/IdentificationResult.java`
- `gui/analyzer/identification/DecoderTrialConfig.java`
- `gui/analyzer/identification/DecoderTrialListener.java`
- `gui/analyzer/ui/SignalDetailPanel.java`
- `gui/analyzer/logging/SignalAnalysisLog.java`

**Key challenge:** Creating and managing temporary channels for decoder trials.
Need to handle resource contention (limited tuner channel count) and cleanup.

**Estimated effort:** High — integration with channel processing pipeline

---

## 10. Configuration (SignalAnalyzerConfig)

```java
public class SignalAnalyzerConfig {
    // Detection
    private double thresholdDb = 10.0;          // dB above noise floor
    private double minBandwidthHz = 6250;       // 6.25 kHz minimum
    private int averagingFrames = 10;           // ~0.5s at 20 fps
    private int persistenceThreshold = 3;       // cycles before confirming

    // Characterization
    private int characterizationTimeMs = 2000;  // 2s of I/Q samples
    private double envelopeThreshold = 0.1;     // CV threshold for constant envelope

    // Identification
    private int dwellTimeMs = 5000;             // 5s per decoder trial
    private boolean autoIdentify = true;        // auto-run identification
    private int maxConcurrentTrials = 1;        // one at a time (to limit tuner resources)

    // Validation
    private double dcSpikeMarginHz = 1000;      // ±1 kHz from center = DC spike
    private double imageMarginHz = 500;         // tolerance for image detection
    private double harmonicTolerance = 0.005;   // 0.5% frequency tolerance for harmonics

    // UI
    private boolean showFlagged = true;         // show DC/spur/harmonic signals
    private boolean autoScroll = true;          // auto-scroll log

    // Logging
    private boolean csvLogging = true;
    private String logDirectory = "logs";       // relative to app directory
}
```

---

## 11. Error Handling and Resource Management

### 11.1 Tuner Resource Contention

The tuner has a limited number of DDC channels (depends on hardware and bandwidth).
The signal analyzer must:
- Check available channel capacity before starting characterization/identification
- Queue analysis requests if channels are full
- Release channels promptly after analysis
- Yield to user-created channels (lower priority than playlist channels)

### 11.2 Cleanup

- All temporary channels MUST be stopped and removed when:
  - Analysis completes (success or failure)
  - User stops scan
  - Tab is closed / application exits
- Use try-finally blocks and shutdown hooks

### 11.3 Thread Safety

- Signal detection runs on the DFT callback thread (same as spectrum display) — must be fast
- Characterization and identification run on background threads (executor service)
- UI updates must be dispatched to EDT via `SwingUtilities.invokeLater()`
- Use `CompletableFuture` for async analysis pipeline

---

## 12. Future Enhancements (Post-MVP)

- **Band scanning**: Automatically retune the tuner across a frequency range and detect signals
- **Signal database**: Persist identified signals to a database for historical tracking
- **Known signal library**: Match detected signals against FCC/NTIA frequency allocations
- **Automatic channel creation**: One-click to create playlist channel from identified signal
- **Signal recording**: Record baseband I/Q of unidentified signals for offline analysis
- **CTCSS/DCS detection**: For analog FM signals, detect squelch tones
- **Multi-tuner support**: Use multiple tuners for wider bandwidth coverage
- **Spectrogram analysis**: Frequency-hopping pattern detection
- **Machine learning**: Train classifier on known signal types for faster identification

---

## 13. Open Questions

1. **Performance**: The DFT callback runs at ~20 fps. Signal detection must complete within
   ~50ms to avoid blocking the spectrum display. If too slow, we may need to sample every
   Nth frame rather than process every frame.

2. **Characterization I/Q source**: Should we create a full `TunerChannelSource` (uses a DDC
   channel slot) or is there a lighter-weight way to extract narrowband I/Q from the tuner's
   wideband stream? Creating a TunerChannelSource is cleanest but costs a channel slot.

3. **P25 Phase 2**: Requires TDMA timing, which may not be achievable on a cold start without
   a control channel. May need to skip or handle specially.

4. **Intermittent signals**: Trunked voice channels are only active during calls. The analyzer
   may detect them briefly and then they disappear. Should we track "ghost" signals that were
   seen recently but aren't currently active?

---

## 14. References

- `ControllerPanel.java` — tab registration pattern
- `SpectralDisplayPanel.java` — DFT data pipeline
- `ComplexDftProcessor.java` — FFT processing
- `ComplexDecibelConverter.java` — dB conversion
- `ChannelSpectrumPanel.java` — per-channel spectrum view (model for SignalDetailPanel)
- `ChannelProcessingManager.java` — channel start/stop
- `DecoderFactory.java` — decoder instantiation
- `DecoderType.java` — available decoders
- `TunerManager.java` — tuner access
- `SourceConfigTuner.java` — tuner source configuration
