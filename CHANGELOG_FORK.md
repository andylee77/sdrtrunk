# SDRTrunk — Changelog (andylee77 fork)

Tracking log for the `plutosdr` branch of the SDRTrunk fork.
Upstream: [DSheirer/sdrtrunk](https://github.com/DSheirer/sdrtrunk)

---

## [2026-03-08] Project Setup

### Fork & Repository
- Forked `DSheirer/sdrtrunk` → `andylee77/sdrtrunk`
- Created `plutosdr` branch for PlutoSDR development
- Set up upstream tracking: `upstream` → `DSheirer/sdrtrunk`, `origin` → `andylee77/sdrtrunk`
- Working copy: `C:\Users\Andy\Projects\SDRTrunk\sdrtrunk`
- Upstream version: `0.6.2-beta-1`

### Areas of Development
- **PlutoSDR tuner support** — Hardware integration via libiio

### Work Documents
Extensive work documents accumulated in `C:\Users\Andy\Projects\SDRTrunk\work_docs\`:
- `plutosdr/` — 30+ documents on PlutoSDR integration
- `ddc/` — 14 documents on DDC channelizer design
- `p25/` — 15 documents on P25 decoder investigation
- `pymbe/` — AMBE voice codec research

---

## [2026-03-08] Change 001: PlutoSDR Tuner Integration

### New Files (9)
- `buffer/SignedShortNativeBuffer.java` — Signed 16-bit IQ native buffer
- `source/tuner/manager/IBandwidthAdjustableTunerController.java` — Auto-bandwidth interface
- `source/tuner/plutosdr/PlutoSdrTunerController.java` — TCP protocol, connect/reconnect, debounced retune, DC offset correction
- `source/tuner/plutosdr/PlutoSdrTuner.java` — Tuner wrapper
- `source/tuner/plutosdr/PlutoSdrTunerConfiguration.java` — Configuration bean (host, port, sample rate, gain, AGC, RF bandwidth)
- `source/tuner/plutosdr/PlutoSdrTunerEditor.java` — JavaFX editor panel
- `source/tuner/plutosdr/DiscoveredPlutoSdrTuner.java` — Discovery/lifecycle management
- `source/tuner/plutosdr/AddPlutoSdrTunerDialog.java` — Runtime add dialog
- `source/tuner/plutosdr/PlutoSdrDeviceInfo.java` — Device info container

### Modified Files (10)
- `TunerType.java` — Added PLUTO_SDR enum
- `TunerClass.java` — Added PLUTO_SDR enum
- `TunerConfiguration.java` — Added JSON subtype for PlutoSDR config
- `TunerFactory.java` — Added PlutoSDR cases in config and editor factories
- `TunerController.java` — Added `isFrequencyLocked()` base method
- `FrequencyErrorCorrectionManager.java` — Added 30s post-correction cooldown, `resetCooldown()`, improved logging
- `TunerManager.java` — Added `discoverPlutoSdrTuners()`, made `startAndConfigureTuner()` public
- `TunerViewPanel.java` — Added PlutoSDR add/remove buttons, channel control buttons
- `ControllerPanel.java` — Pass playlistManager to TunerViewPanel
- `PolyphaseChannelSourceManager.java` — Added frequency-lock check before retuning

### Decisions
- ❌ Skipped DDC contamination in `Tuner.java` (DDC channelizer type)
- ❌ Skipped DDC tab in `ControllerPanel.java`
- ✅ Build verified: `gradlew compileJava` — BUILD SUCCESSFUL

### Documentation
- `doc/changes/001_plutosdr_integration.md` — Detailed change doc

---

## [2026-03-09] Change 002: Waterfall / Spectrum UI Enhancements

### New Files (1)
- `spectrum/menu/ReferenceLevelItem.java` — JSlider widget (-60 to +60 dB) for shifting spectrum and waterfall reference level

### Modified Files (4)
- `SpectrumPanel.java` — Added reference level offset field, getter/setter, applied as pixel offset in drawSpectrum()
- `WaterfallPanel.java` — Added reference level offset field, getter/setter (dB→color-index scaling), applied in receive() loop
- `SpectralDisplayPanel.java` — Import ReferenceLevelItem, added "Reference Level" submenu in right-click context menu
- `ComplexDecibelConverter.java` — Fixed dB calculation: 10*log10(power) → 20*log10(amplitude) for correct amplitude-domain scaling

### Documentation
- `doc/changes/002_waterfall_spectrum_ui.md` — Detailed change doc

---

## [2026-03-14] Change 003: Ignore Encrypted Calls Option

### Modified Files (3)
- `module/decode/p25/phase1/DecodeConfigP25.java` — Added `mIgnoreEncryptedCalls` boolean field with Jackson XML serialization, getter, and setter
- `gui/playlist/channel/P25P1ConfigurationEditor.java` — Added "Ignore Encrypted Calls" ToggleSwitch to the P25 Phase 1 decoder panel, with load/save support
- `module/decode/p25/P25TrafficChannelManager.java` — Added `mIgnoreEncryptedCalls` field, reads from config in constructor, filters encrypted grants in `processPhase1ControlChannelGrant()` and `processPhase2ChannelGrant()`

### Behavior
- When enabled, encrypted channel grants are logged as "IGNORED: ENCRYPTED CALL" but do not consume a traffic channel slot
- Setting persisted in playlist XML as `ignore_encrypted_calls` attribute on the decode configuration element
- Works for both P25 Phase 1 and Phase 2 channel grants

### Documentation
- `doc/changes/003_ignore_encrypted_calls.md` — Detailed change doc

### Bugfix: Same-Call Re-Allocation Bypass (ef329cd9)
- **Bug:** Repeated channel grant updates for the same encrypted call entered the "same call" code path, which bypassed the encrypted filter and allocated a traffic channel anyway
- **Fix:** Added early-return encrypted check at the top of both "same call" blocks in `processPhase1ControlChannelGrant()` and `processPhase2ChannelGrant()`
- See `doc/changes/003_ignore_encrypted_calls.md` for full details

---

## Pending / Future

- [ ] Finalize PlutoSDR tuner integration with Maia IQ streaming
- [ ] DDC channelizer performance optimization
- [ ] P25 back-to-back transmission handling fix
- [ ] Merge upstream changes from DSheirer/sdrtrunk
- [ ] Test with Fishball Z7020 hardware
