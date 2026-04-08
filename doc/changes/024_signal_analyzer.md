# Change 024: Signal Analyzer

**Date:** 2026-03-25
**Branch:** plutosdr

## Summary

Added a Signal Analyzer feature that detects and catalogs signals visible in the
spectrum display. The analyzer taps into the existing FFT pipeline to find spectral
peaks above the noise floor, tracks them over time, and identifies artifacts like
DC offset spikes, harmonics, and image frequencies.

## Architecture

### New Package: `io.github.dsheirer.gui.analyzer`

**Enums:**
- `AnalysisStatus` — Signal lifecycle states (DETECTED, CONFIRMED, LOST, IGNORED)
- `ModulationType` — Placeholder for future Phase 2 modulation classification
- `detection/SignalFlag` — Artifact classification flags (DC_OFFSET, HARMONIC, IMAGE, WEAK, WIDEBAND)

**Data Model:**
- `SignalAnalyzerConfig` — Tunable detection parameters (threshold dB, min bandwidth, averaging depth, etc.)
- `detection/DetectedSignal` — Represents a detected signal with frequency, power, bandwidth, flags, and status

**Detection Engine:**
- `detection/SignalDetector` — Core FFT peak finder using noise floor estimation and configurable thresholds. Implements `DFTResultsListener` to receive FFT data from the existing spectral display pipeline. Runs frame averaging to smooth transients before detecting peaks.
- `detection/SignalTracker` — Merges new detections with previously tracked signals using frequency proximity matching. Maintains detection counts for signal persistence and handles aging/removal of stale signals.
- `detection/HarmonicAnalyzer` — Post-detection filter that flags DC offset spikes (center frequency), harmonics of fundamental signals, and image frequencies. Helps the user distinguish real signals from hardware artifacts.

**Controller:**
- `SignalAnalyzerController` — Main orchestrator implementing both `DFTResultsListener` and `ISourceEventProcessor`. Manages the analysis state machine (IDLE → SCANNING → STOPPED), coordinates the detection pipeline, and dispatches UI updates to the EDT.

**UI:**
- `ui/SignalAnalysisTableModel` — Swing table model displaying detected signals with columns for frequency, power, bandwidth, status, flags, and detection count.
- `ui/AnalyzerControlPanel` — Control panel with Start/Stop/Clear buttons, Auto/Manual mode toggle, threshold slider, and signal count display.
- `ui/AnalysisLogPanel` — Scrolling log panel with timestamped detection events and status messages. Color-coded entries for info, warnings, detections, and errors.
- `SignalAnalyzerPanel` — Main panel assembling all UI components with a split pane layout.

### Modified Files

**`SpectralDisplayPanel.java`:**
- Added `addDftResultsListener()` / `removeDftResultsListener()` — allows external components to tap into the FFT output
- Added `addSourceEventProcessor()` / `removeSourceEventProcessor()` — allows external components to receive tuner frequency/bandwidth change events
- Modified `process(SourceEvent)` to forward events to additional registered processors

**`ControllerPanel.java`:**
- Added `SignalAnalyzerPanel` as a new tab with FontAwesome SIGNAL icon
- Added `getSignalAnalyzerPanel()` getter for wiring

**`SDRTrunk.java`:**
- Added wiring code to connect the `SignalAnalyzerController` as a `DFTResultsListener` on the spectral display pipeline
- Added wiring code to connect it as an `ISourceEventProcessor` for frequency/bandwidth updates
- Guarded with null check for headless mode

## Detection Algorithm

1. **Noise floor estimation:** Sorts FFT bins by power, takes the median of the lower half as the noise floor estimate
2. **Peak detection:** Scans FFT bins for values exceeding (noise floor + threshold dB), merging adjacent above-threshold bins into contiguous signal regions
3. **Frame averaging:** Maintains a running average over N frames (configurable) to smooth transient spikes before detection
4. **Tracking:** Matches new detections to existing tracked signals by frequency proximity (within configurable tolerance)
5. **Artifact flagging:** Identifies DC offset spikes, harmonics (integer multiples of fundamental frequencies), and unusually wide or weak signals

## User Interface

The Signal Analyzer tab appears in the main controller panel between "Tuners" and "Playlist Editor":

- **Top:** Control panel with Start/Stop, Clear, Auto/Manual mode, threshold adjustment
- **Center:** Signal table showing all detected signals sorted by power
- **Bottom:** Scrolling analysis log with timestamped events

## Phase 2a: Spectral Classification (FFT-Based)

Added `SpectralClassifier` — classifies detected signals using FFT spectral shape analysis
without requiring dedicated I/Q channel allocation. Runs every detection cycle on all tracked signals.

### Classification Approach

1. **Spectral flatness** — Measures the ratio of mean-to-peak power within each signal's bandwidth.
   Flat-topped signals (flatness > 0.65) are digital (P25, DMR). Bell-shaped signals are analog FM.
   Very narrow peaks (flatness < 0.25) are carrier/CW.

2. **Bandwidth classification** — Maps measured bandwidth to channel types:
   - < 2 kHz = carrier/CW
   - 2-8 kHz = narrowband digital (NXDN 6.25 kHz channels)
   - 8-16 kHz = standard 12.5 kHz channels (P25, DMR, NBFM)
   - 16-30 kHz = wideband FM

3. **Frequency band heuristics** — Uses RF band to determine likely protocol:
   - 700/800 MHz + flat-topped 12.5 kHz → **P25 Phase 1** (C4FM, 9600 baud)
   - 900 MHz + flat-topped 12.5 kHz → **P25 Phase 1**
   - UHF (400-520 MHz) + flat-topped → **4FSK** (P25 or DMR, not committed)
   - VHF (136-174 MHz) + flat-topped → **4FSK**
   - Any band + analog FM shape → **NBFM**

4. **Signal persistence** — Signals detected ≥10 consecutive cycles are flagged as
   `CONTINUOUS` (likely control channels, always-on beacons).

### Results

The Modulation column now shows actual classifications (C4FM, NFM, 4FSK, CW, etc.)
instead of "UNK". The Protocol column shows identified decoders (P25 Phase 1, NBFM, etc.)
instead of "—". The Sym Rate column shows estimated symbol rates (9600, 4800, etc.).

## Phase 3 Fixes: False Confirmation Elimination and System Info Display

Major fixes to the decoder trial identification engine based on real-world testing:

### Problem: Massive False P25 Phase 2 Confirmations

The original tentative match logic treated **sync losses** (just finding sync patterns in data)
as evidence of a protocol match. P25 Phase 2, LTR, and other decoders produce sync pattern
matches on **random noise**, leading to nearly every detected frequency being falsely
"CONFIRMED" as P25 Phase 2 with "Low confidence, 0 events". In the test log, 30+ frequencies
were falsely confirmed this way.

**Root cause:** `hasTentativeMatch()` accepted sync losses alone:
```java
// OLD (broken): sync losses on noise → false confirmation
return bestTentativeDecoder != null &&
    (bestTentativeCrcFails >= TENTATIVE_MIN_CRC_FAILS ||
     bestTentativeSyncLoss >= TENTATIVE_MIN_SYNC_LOSS);
```

**Fix:** Now requires **actual CRC-failed frames** (real frame structure found, not just sync):
```java
// NEW: only real frame activity counts
return bestTentativeDecoder != null &&
    bestTentativeCrcFails >= TENTATIVE_MIN_CRC_FAILS;
```

### Problem: No System Info in Table

The table showed Protocol but no NAC, confidence level, or system identification details.
For P25 signals, the NAC (Network Access Code) is the key identifier but wasn't displayed.

**Fix:** Added three new data fields to `DetectedSignal`:
- `nac` — Network Access Code (P25), extracted from confirmed messages
- `identificationConfidence` — High/Medium/Low/Tentative
- `getSystemInfoDisplay()` — Formatted string showing NAC and other system details

Added two new columns to the table model:
- **Confidence** — Shows High/Medium/Low/Tentative
- **System Info** — Shows "NAC: 954/x3BA" or other protocol-specific identifiers

### Problem: Log Noise Makes Results Unreadable

With 40+ signals × 7 decoder candidates, the log showed 280+ "Trying X decoder..." and
280+ "✗ X — no match" lines, drowning out the actual results.

**Fix:** 
- Only log first decoder trial start per signal ("856.5500 MHz: Identifying...")
- Suppress intermediate decoder failure messages entirely from UI log
- Only log final result: confirmed, tentative, or no match
- All intermediate details still available in logback log at DEBUG level

### New Status: TENTATIVE

Added `AnalysisStatus.TENTATIVE` for signals where CRC-failed frames were found
(real protocol activity detected) but no valid messages could be decoded.
This is distinct from IDENTIFIED (valid messages) and NO_MATCH (no activity at all).
Tentative matches typically indicate encrypted traffic, weak signals, or intermittent channels.

### CSV Export Updated

Export now includes: Confidence, NAC, System_Info columns alongside existing fields.

## Future Phases

- **Phase 2b:** I/Q-based characterization (envelope analysis, instantaneous frequency, symbol rate estimation via dedicated channel allocation)

## Files Added

```
src/main/java/io/github/dsheirer/gui/analyzer/
├── AnalysisStatus.java
├── ModulationType.java
├── SignalAnalyzerConfig.java
├── SignalAnalyzerController.java
├── SignalAnalyzerPanel.java
├── characterization/
│   └── SpectralClassifier.java
├── detection/
│   ├── DetectedSignal.java
│   ├── HarmonicAnalyzer.java
│   ├── SignalDetector.java
│   ├── SignalFlag.java
│   └── SignalTracker.java
└── ui/
    ├── AnalysisLogPanel.java
    ├── AnalyzerControlPanel.java
    └── SignalAnalysisTableModel.java
```

## Files Modified

```
src/main/java/io/github/dsheirer/spectrum/SpectralDisplayPanel.java
src/main/java/io/github/dsheirer/controller/ControllerPanel.java
src/main/java/io/github/dsheirer/gui/SDRTrunk.java
```
