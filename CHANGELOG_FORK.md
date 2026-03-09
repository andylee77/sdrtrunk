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

## Pending / Future

- [ ] Finalize PlutoSDR tuner integration with Maia IQ streaming
- [ ] DDC channelizer performance optimization
- [ ] P25 back-to-back transmission handling fix
- [ ] Merge upstream changes from DSheirer/sdrtrunk
- [ ] Test with Fishball Z7020 hardware
