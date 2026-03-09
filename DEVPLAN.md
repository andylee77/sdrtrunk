# SDRTrunk Fork — Migration Plan

Migration of changes from `C:\Users\Andy\Downloads\sdrtrunk-master\sdrtrunk-master` (working copy)
into this clean fork at `C:\Users\Andy\Projects\SDRTrunk\sdrtrunk` (branch: `plutosdr`).

**Source:** Modified upstream clone with PlutoSDR integration, UI improvements, P25 fixes, DDC channelizer, and various other changes.

**Goal:** Selectively bring in desired changes with review and approval of each one.

**Reference baseline:** Both trees are based on upstream `DSheirer/sdrtrunk` master at the same commit.

---

## Decision Summary

| Category | Decision | Notes |
|----------|----------|-------|
| PlutoSDR / Python Stream | ✅ WANT | Core feature — TCP-based IQ stream interface |
| Waterfall / Spectrum UI | ✅ WANT | Enhanced display features |
| DDC Channelizer | ❌ SKIP | Not bringing in |
| P25 Decoder Fixes | ❓ REVIEW | Decide later |
| Audio Playback Changes | ❓ REVIEW | Decide later |
| DMR Changes | ❓ REVIEW | Decide later |
| DSP Demodulator Changes | ❓ REVIEW | Decide later |
| Build Config (GC tuning) | ❓ REVIEW | Machine-specific, may not want |
| Other (recording, filters, etc.) | ❓ REVIEW | Decide per-file |

---

## Phase 1: PlutoSDR / Python Stream Integration

The PlutoSDR integration is a TCP-based IQ stream interface. A Python companion server (`pluto_server.py`) runs alongside the hardware and streams signed 16-bit IQ samples over TCP. The Java side connects, sends a JSON config command, receives a JSON status response, then reads raw IQ data continuously.

### 1.1 New Files (7 PlutoSDR core files)

These are entirely new files that don't exist in upstream. Each needs review for quality and correctness.

| # | File | Size | Description | Status |
|---|------|------|-------------|--------|
| 1.1.1 | `source/tuner/plutosdr/PlutoSdrTunerController.java` | ~900 lines | Core controller: TCP protocol, connect/reconnect, debounced retune, DC offset correction, frequency lock, auto-bandwidth via IBandwidthAdjustable | ☑ Done |
| 1.1.2 | `source/tuner/plutosdr/PlutoSdrTuner.java` | new | Tuner wrapper extending base Tuner class | ☑ Done |
| 1.1.3 | `source/tuner/plutosdr/PlutoSdrTunerConfiguration.java` | new | Configuration bean: host, port, sample rate, gain, AGC, RF bandwidth; JSON-serializable | ☑ Done |
| 1.1.4 | `source/tuner/plutosdr/PlutoSdrTunerEditor.java` | new | JavaFX editor panel for PlutoSDR settings in the tuner tab | ☑ Done |
| 1.1.5 | `source/tuner/plutosdr/DiscoveredPlutoSdrTuner.java` | new | Discovery/lifecycle management for PlutoSDR tuners | ☑ Done |
| 1.1.6 | `source/tuner/plutosdr/AddPlutoSdrTunerDialog.java` | new | Dialog for manually adding a PlutoSDR tuner (host:port input) | ☑ Done |
| 1.1.7 | `source/tuner/plutosdr/PlutoSdrDeviceInfo.java` | new | Device info container: temperature, RSSI, model, serial, firmware version | ☑ Done |

### 1.2 New Dependency Files

Files required by the PlutoSDR implementation that don't exist in upstream.

| # | File | Description | Status |
|---|------|-------------|--------|
| 1.2.1 | `buffer/SignedShortNativeBuffer.java` | INativeBuffer implementation for signed 16-bit IQ samples (PlutoSDR uses int16 format vs unsigned byte for RTL-SDR) | ☑ Done |
| 1.2.2 | `source/tuner/manager/IBandwidthAdjustableTunerController.java` | Interface for tuner controllers that can auto-adjust sample rate to fit required bandwidth | ☑ Done |

### 1.3 Modified Framework Files (Tuner Integration Points)

Surgical edits to existing files that wire PlutoSDR into the tuner framework. These are small, well-defined changes.

| # | File | Change Size | What Changed | Why | Status |
|---|------|-------------|--------------|-----|--------|
| 1.3.1 | `source/tuner/TunerType.java` | +2 lines | Added `PLUTO_SDR("PlutoSDR")` enum value | Required: every tuner type needs an enum entry | ☑ Done |
| 1.3.2 | `source/tuner/TunerClass.java` | +1 line | Added `PLUTO_SDR("PlutoSDR")` enum value | Required: tuner class categorization | ☑ Done |
| 1.3.3 | `source/tuner/TunerFactory.java` | +6 lines | Added `case PLUTO_SDR:` in config factory and editor factory switch statements, plus imports | Required: wires PlutoSDR config and editor into the factory pattern | ☑ Done |
| 1.3.4 | `source/tuner/configuration/TunerConfiguration.java` | +2 lines | Added `@JsonSubTypes.Type` for `PlutoSdrTunerConfiguration` + import | Required: enables JSON serialization/deserialization of PlutoSDR config | ☑ Done |
| 1.3.5 | `source/tuner/TunerController.java` | +14 lines | Added `isFrequencyLocked()` method (returns false by default, overridden by PlutoSDR) | PlutoSDR uses frequency lock to prevent PolyphaseChannelManager from retuning the center frequency when channels activate | ☑ Done |
| 1.3.6 | `source/tuner/Tuner.java` | +5 lines | Added DDC channelizer type handling in constructor | **❌ SKIPPED: DDC-related, not PlutoSDR. PlutoSDR uses Polyphase channelizer.** | ☑ Decided: Skip |
| 1.3.7 | `source/tuner/manager/TunerManager.java` | +42/-1 lines | Added `discoverPlutoSdrTuners()`, made `startAndConfigureTuner()` public | Required: allows discovery and manual addition of PlutoSDR tuners | ☑ Done |
| 1.3.8 | `source/tuner/ui/TunerViewPanel.java` | +223/-1 lines | Added "Add PlutoSDR" button, PlutoSDR editor wiring, channel control buttons | Required: provides the UI entry point for adding/managing PlutoSDR tuners | ☑ Done |
| 1.3.9 | `source/tuner/manager/FrequencyErrorCorrectionManager.java` | modified | Added 30s cooldown, `resetCooldown()`, improved logging | PlutoSDR needs to reset the auto-PPM observation cooldown after a manual correction | ☑ Done |
| 1.3.10 | `source/tuner/manager/PolyphaseChannelSourceManager.java` | modified | Added frequency-lock check before retuning | **Clean: only PlutoSDR frequency-lock check, no DDC code.** | ☑ Done |

### 1.4 Documentation / Companion Tools

| # | File | Description | Status |
|---|------|-------------|--------|
| 1.4.1 | `tools/plutosdr/pluto_server.py` | Python companion TCP server (required to run PlutoSDR with SDRTrunk) | ☑ Done |
| 1.4.2 | `tools/plutosdr/README.md` | PlutoSDR setup guide, protocol reference, troubleshooting | ☑ Done |
| 1.4.3 | `tools/plutosdr/poll_device.py` | Device discovery/validation utility | ☑ Done |

---

## Phase 2: Waterfall / Spectrum UI Enhancements

Changes to the spectrum display, waterfall, and related UI components.

| # | File | Change Size | Description | Status |
|---|------|-------------|-------------|--------|
| 2.1 | `spectrum/WaterfallPanel.java` | +36/-1 | Reference level offset field, getter/setter (dB→color-index scaling), applied in receive() loop | ☑ Done |
| 2.2 | `spectrum/SpectrumPanel.java` | +31/-1 | Reference level offset field, getter/setter, applied as pixel offset in drawSpectrum() | ☑ Done |
| 2.3 | `spectrum/SpectralDisplayPanel.java` | +9 | Import ReferenceLevelItem, added "Reference Level" submenu in right-click context menu | ☑ Done |
| 2.4 | `spectrum/converter/ComplexDecibelConverter.java` | +5/-2 | Fixed dB calculation: changed from 10*log10(power) to 20*log10(amplitude) for correct amplitude-domain scaling | ☑ Done |
| 2.5 | `spectrum/menu/ReferenceLevelItem.java` | new file | JSlider menu item (-60 to +60 dB) that adjusts both spectrum and waterfall reference level in sync | ☑ Done |

---

## Phase 3: Other Changes — Deferred Review

These changes exist in the modified source but are not the current priority. They should be reviewed individually when time permits to decide whether they add value.

### 3.1 DDC Channelizer (SKIP)

Explicitly not bringing these in.

| File | Description |
|------|-------------|
| `dsp/filter/channelizer/ddc/DDCChannel.java` | DDC channel implementation |
| `dsp/filter/channelizer/ddc/DDCChannelManager.java` | DDC channel manager |
| `dsp/filter/channelizer/ddc/DDCChannelSource.java` | DDC channel source |
| `source/tuner/manager/DDCChannelSourceManager.java` | DDC source manager |
| `gui/channelizer/DDCChannelMonitorPanel.java` | DDC monitor UI |
| `preference/source/ChannelizerType.java` | Added DDC enum value |
| `preference/source/TunerPreference.java` | DDC preference support |

### 3.2 P25 Decoder Changes (14 files)

**Investigation notes (2026-03-09):** The modified source contains +638/-33 lines of P25 changes across
14 files. User reports that decoding works noticeably better on the modified build (using heterodyne
channelizer), but still sees CRC errors. Key observations:

- **P25P1MessageFramer.java (+86 lines)** is the **#1 priority** for CRC error investigation. The framer
  handles sync detection and frame alignment — if frames aren't properly aligned before CRC checking,
  every frame will fail CRC. The +86 lines of changes likely contain improved sync recovery, better
  handling of frame boundary detection, and possibly tolerance for bit errors in sync words.

- **P25P1AudioModule.java (+372 lines)** has the largest change — likely improved voice frame handling
  and back-to-back transmission continuity. This wouldn't directly affect CRC errors but would improve
  overall audio quality once frames are decoded.

- **P25TrafficChannelManager.java (+100 lines)** — better traffic channel tracking/handoff, fewer missed
  transmissions during channel switches.

- **Heterodyne vs Polyphase:** User reports heterodyne channelizer decodes better than polyphase for P25.
  Likely because heterodyne gives a cleaner, more direct signal path for a single channel — the polyphase
  filter bank can introduce transition-band artifacts at channel edges that affect symbol timing.

**Recommended investigation order for CRC errors:**
1. Start with `P25P1MessageFramer.java` — diff the changes, understand sync detection improvements
2. Then `P25P1DecoderLSM.java` (+10 lines) — LSM is the heterodyne decoder path
3. Then `P25P1MessageAssembler.java` (+4 lines) — message assembly changes
4. Then `P25P1DecoderC4FM.java` (+8 lines) — C4FM decoder path changes (may cross-apply to LSM)

| # | File | Change Size | Description | Status |
|---|------|-------------|-------------|--------|
| 3.2.1 | `module/decode/p25/P25TrafficChannelEventTracker.java` | +4/-4 | Minor event tracking changes | ☐ Deferred |
| 3.2.2 | `module/decode/p25/P25TrafficChannelManager.java` | +100 | Traffic channel management improvements | ☐ Deferred |
| 3.2.3 | `module/decode/p25/audio/P25P1AudioModule.java` | +372 | Major audio module rewrite | ☐ Deferred |
| 3.2.4 | `module/decode/p25/phase1/DecodeConfigP25.java` | +12 | New configuration options | ☐ Deferred |
| 3.2.5 | `module/decode/p25/phase1/P25P1DecoderC4FM.java` | +8/-8 | C4FM decoder tweaks | ☐ Deferred |
| 3.2.6 | `module/decode/p25/phase1/P25P1DecoderLSM.java` | +10/-10 | LSM (heterodyne) decoder changes | ☐ Deferred |
| 3.2.7 | `module/decode/p25/phase1/P25P1DecoderState.java` | +6/-6 | Decoder state changes | ☐ Deferred |
| 3.2.8 | `module/decode/p25/phase1/P25P1DemodulatorLSM.java` | +3 | LSM demodulator addition | ☐ Deferred |
| 3.2.9 | `module/decode/p25/phase1/P25P1MessageAssembler.java` | +4/-4 | Message assembly improvements | ☐ Deferred |
| 3.2.10 | `module/decode/p25/phase1/P25P1MessageFramer.java` | +86 | **🔍 CRC INVESTIGATION START HERE** — sync detection / frame alignment | ☐ Deferred |
| 3.2.11 | `module/decode/p25/phase1/message/lc/LinkControlOpcode.java` | +2/-2 | Opcode enum change | ☐ Deferred |
| 3.2.12 | `module/decode/p25/phase1/message/ldu/LDU1Message.java` | +56 | Additional LDU1 voice frame parsing | ☐ Deferred |
| 3.2.13 | `module/decode/p25/phase2/P25P2DecoderState.java` | +4/-4 | Phase 2 decoder state changes | ☐ Deferred |
| 3.2.14 | `module/decode/p25/phase2/message/mac/MacOpcode.java` | +4/-4 | MAC opcode enum changes | ☐ Deferred |

### 3.3 Audio Playback Changes (5 files)

| # | File | Status |
|---|------|--------|
| 3.3.1 | `audio/playback/AudioChannel.java` | ☐ Deferred |
| 3.3.2 | `audio/playback/AudioChannelPanel.java` | ☐ Deferred |
| 3.3.3 | `audio/playback/AudioPlaybackDeviceDescriptor.java` | ☐ Deferred |
| 3.3.4 | `audio/playback/AudioPlaybackDeviceManager.java` | ☐ Deferred |
| 3.3.5 | `audio/playback/AudioPlaybackManager.java` | ☐ Deferred |

### 3.4 DMR Changes (3 files)

| # | File | Status |
|---|------|--------|
| 3.4.1 | `module/decode/dmr/DMRDecoder.java` | ☐ Deferred |
| 3.4.2 | `module/decode/dmr/message/data/lc/full/TalkerAliasComplete.java` | ☐ Deferred |
| 3.4.3 | `module/decode/dmr/message/data/packet/UDTShortMessageService.java` | ☐ Deferred |

### 3.5 DSP Demodulator Changes (4 files)

| # | File | Status |
|---|------|--------|
| 3.5.1 | `dsp/psk/demod/DifferentialDemodulatorFloatScalar.java` | ☐ Deferred |
| 3.5.2 | `dsp/psk/demod/DifferentialDemodulatorFloatVector128.java` | ☐ Deferred |
| 3.5.3 | `dsp/psk/demod/DifferentialDemodulatorFloatVector512.java` | ☐ Deferred |
| 3.5.4 | `dsp/psk/demod/DifferentialDemodulatorFloatVector64.java` | ☐ Deferred |

### 3.6 Recording / Activity Logging (4 files)

| # | File | Status |
|---|------|--------|
| 3.6.1 | `record/JsonActivityRecorder.java` | new file — ☐ Deferred |
| 3.6.2 | `record/RecorderFactory.java` | ☐ Deferred |
| 3.6.3 | `record/RecorderType.java` | ☐ Deferred |
| 3.6.4 | `record/AudioRecordingManager.java` | ☐ Deferred |

### 3.7 Other Miscellaneous Changes

| # | File | Change Size | Status |
|---|------|-------------|--------|
| 3.7.1 | `bits/BinaryMessage.java` | modified | ☐ Deferred |
| 3.7.2 | `controller/ControllerPanel.java` | modified | ☐ Deferred |
| 3.7.3 | `controller/channel/ChannelProcessingManager.java` | modified | ☐ Deferred |
| 3.7.4 | `edac/Checksum_5_DMR.java` | modified | ☐ Deferred |
| 3.7.5 | `edac/bch/BCH_63_16_23_P25.java` | modified | ☐ Deferred |
| 3.7.6 | `filter/Filter.java` | modified | ☐ Deferred |
| 3.7.7 | `filter/FilterSet.java` | modified | ☐ Deferred |
| 3.7.8 | `gui/channel/ChannelSpectrumPanel.java` | modified | ☐ Deferred |
| 3.7.9 | `gui/control/FrequencyTextField.java` | modified | ☐ Deferred |
| 3.7.10 | `gui/playlist/channel/P25P1ConfigurationEditor.java` | modified | ☐ Deferred |
| 3.7.11 | `gui/squelch/NoiseSquelchView.java` | modified | ☐ Deferred |
| 3.7.12 | `gui/viewer/symbol/SymbolViewerFX.java` | modified | ☐ Deferred |
| 3.7.13 | `gui/viewer/sync/SyncResultsViewer.java` | modified | ☐ Deferred |
| 3.7.14 | `monitor/DiagnosticMonitor.java` | modified | ☐ Deferred |
| 3.7.15 | `monitor/ResourceMonitor.java` | modified | ☐ Deferred |
| 3.7.16 | `source/tuner/sdrplay/api/device/DeviceStruct_v3_08.java` | modified | ☐ Deferred |

### 3.8 Build Configuration

| # | File | Change | Status |
|---|------|--------|--------|
| 3.8.1 | `build.gradle` | GC tuning params (G1GC, 4GB min, 6GB max, 50ms pause target, string dedup, gc logging) added to both Windows and Linux JVM args | ☐ Deferred — these are aggressive/machine-specific |

---

## Watch Items / Flags

Things identified during analysis that need attention:

1. **DDC contamination in PlutoSDR files:** `Tuner.java` change (item 1.3.6) adds DDC channelizer support in the constructor — this is DDC-related, not PlutoSDR. The PlutoSDR uses the existing Polyphase channelizer. We may need to skip this change or only bring it in if we later want DDC.

2. **PolyphaseChannelSourceManager mixed changes:** Item 1.3.10 may contain both PlutoSDR-related IBandwidthAdjustable support AND DDC-related changes. Needs careful line-by-line review to separate.

3. **ChannelizerType / TunerPreference:** Listed under DDC skip (3.1), but if PlutoSDR references ChannelizerType anywhere, we may need those changes. Verify during Phase 1 review.

4. **`src_original/` in modified source:** The modified tree contains a `src_original/` directory which appears to be a backup of the original source. This can be used as a secondary reference to verify what the original code looked like.

---

## Review Process

For each item:
1. View the diff between upstream (this repo) and modified version
2. Understand the purpose of the change
3. Decide: Accept as-is / Modify / Reject
4. If accepted, apply the change and mark ☑
5. After each phase, build and verify compilation

**Diff command template:**
```bash
git diff --no-index "c:\Users\Andy\Projects\SDRTrunk\sdrtrunk\src\main\java\io\github\dsheirer\<path>" "C:\Users\Andy\Downloads\sdrtrunk-master\sdrtrunk-master\src\main\java\io\github\dsheirer\<path>"
```

---

## Change Counts

| Category | New Files | Modified Files | Total | Decision |
|----------|-----------|---------------|-------|----------|
| PlutoSDR Core | 7 | 0 | 7 | ✅ Review |
| PlutoSDR Dependencies | 2 | 0 | 2 | ✅ Review |
| PlutoSDR Framework Hooks | 0 | 10 | 10 | ✅ Review |
| PlutoSDR Docs | 3 | 0 | 3 | ✅ Review |
| Waterfall/Spectrum UI | 1 | 4 | 5 | ✅ Review |
| DDC Channelizer | 5 | 2 | 7 | ❌ Skip |
| P25 Decoder | 0 | 14 | 14 | ❓ Deferred |
| Audio Playback | 0 | 5 | 5 | ❓ Deferred |
| DMR | 0 | 3 | 3 | ❓ Deferred |
| DSP Demodulators | 0 | 4 | 4 | ❓ Deferred |
| Recording | 1 | 3 | 4 | ❓ Deferred |
| Other | 0 | 16 | 16 | ❓ Deferred |
| Build Config | 0 | 1 | 1 | ❓ Deferred |
| **Total** | **19** | **62** | **81** | |

**Phase 1+2 scope:** 27 items to review (22 PlutoSDR + 5 Waterfall/Spectrum)
