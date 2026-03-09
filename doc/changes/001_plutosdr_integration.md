# Change 001: PlutoSDR Tuner Integration

**Date:** 2026-03-08
**Branch:** plutosdr
**Status:** Complete

## Summary

Adds full PlutoSDR hardware support to SDRTrunk. PlutoSDR devices connect over
Ethernet/TCP (not USB), using a companion Python server (`pluto_server.py`) that
runs on or near the PlutoSDR hardware and streams signed 16-bit IQ samples to
SDRTrunk over a TCP socket.

## New Files (9)

| File | Description |
|------|-------------|
| `buffer/SignedShortNativeBuffer.java` | Signed 16-bit IQ native buffer for PlutoSDR data format |
| `source/tuner/manager/IBandwidthAdjustableTunerController.java` | Interface for tuners that auto-adjust RF bandwidth |
| `source/tuner/plutosdr/PlutoSdrTunerController.java` | ~900-line TCP protocol handler: connect/reconnect, debounced retune, DC offset correction |
| `source/tuner/plutosdr/PlutoSdrTuner.java` | Tuner wrapper extending base Tuner class |
| `source/tuner/plutosdr/PlutoSdrTunerConfiguration.java` | Configuration bean (host, port, sample rate, gain, AGC, RF bandwidth) |
| `source/tuner/plutosdr/PlutoSdrTunerEditor.java` | JavaFX editor panel for PlutoSDR settings |
| `source/tuner/plutosdr/DiscoveredPlutoSdrTuner.java` | Discovery/lifecycle management |
| `source/tuner/plutosdr/AddPlutoSdrTunerDialog.java` | Dialog for manually adding a PlutoSDR at runtime |
| `source/tuner/plutosdr/PlutoSdrDeviceInfo.java` | Device info container |

## Modified Files (10)

| File | Change |
|------|--------|
| `source/tuner/TunerType.java` | Added `PLUTO_SDR("PlutoSDR")` enum value |
| `source/tuner/TunerClass.java` | Added `PLUTO_SDR("PlutoSDR")` enum value |
| `source/tuner/configuration/TunerConfiguration.java` | Added `@JsonSubTypes.Type` for `PlutoSdrTunerConfiguration` |
| `source/tuner/TunerFactory.java` | Added PlutoSDR cases in `getTunerConfiguration()` and `getEditor()` |
| `source/tuner/TunerController.java` | Added `isFrequencyLocked()` base method (returns false) |
| `source/tuner/manager/FrequencyErrorCorrectionManager.java` | Added post-correction cooldown (30s), `resetCooldown()`, improved logging |
| `source/tuner/manager/TunerManager.java` | Added `discoverPlutoSdrTuners()`, made `startAndConfigureTuner()` public |
| `source/tuner/ui/TunerViewPanel.java` | Added PlutoSDR add/remove buttons, channel control buttons, PlaylistManager support |
| `controller/ControllerPanel.java` | Pass `playlistManager` to TunerViewPanel constructor |
| `source/tuner/manager/PolyphaseChannelSourceManager.java` | Added frequency-lock check before retuning |

## Architecture

```
PlutoSDR Hardware
    ↓ (libiio / Maia SDR)
pluto_server.py (Python companion)
    ↓ (TCP socket, signed 16-bit IQ)
PlutoSdrTunerController
    ↓ (SignedShortNativeBuffer)
Polyphase Channelizer
    ↓
P25/DMR/etc Decoders
```

## Key Design Decisions

1. **No DDC contamination**: Skipped Tuner.java DDC channelizer addition and
   ControllerPanel DDC tab — PlutoSDR uses the Polyphase channelizer
2. **Frequency lock**: PlutoSdrTunerController can lock its center frequency,
   preventing the PolyphaseChannelSourceManager from auto-retuning
3. **Post-correction cooldown**: FrequencyErrorCorrectionManager ignores PPM
   measurements for 30s after each auto-correction to prevent feedback loops
   during TCP reconnects
4. **Debounced retune**: PlutoSdrTunerController debounces frequency change
   commands to avoid rapid-fire retune requests to the companion server

## Testing

- Build verification: `gradlew compileJava` — BUILD SUCCESSFUL
- Runtime testing requires PlutoSDR hardware + companion server
