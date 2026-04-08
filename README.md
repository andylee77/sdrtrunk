# SDRTrunk (andylee77 fork)

A cross-platform Java application for decoding, monitoring, recording, and streaming trunked mobile radio protocols using Software Defined Radios (SDR).

**Forked from [DSheirer/sdrtrunk](https://github.com/DSheirer/sdrtrunk)** — this fork adds PlutoSDR hardware support, P25 data capture and analysis tools, UI improvements, and signal analysis capabilities.

## Fork Highlights

### PlutoSDR Tuner Support

- Full PlutoSDR integration via TCP companion server (libiio-based)
- Add/remove PlutoSDR tuners at runtime with configuration persistence
- Companion server and utilities in `tools/plutosdr/`

### P25 Decoder Enhancements

- **Ignore Encrypted Calls** — skip encrypted channel grants to save traffic channel slots
- **Ignore Unmonitored Calls** — skip calls to talkgroups with no alias or "Do Not Monitor" priority
- **Patch Call Duplicate Detection** — detect and suppress duplicate audio when member talkgroups of an active patch group receive individual grants
- **Call Session Management** — per-talker call session tracking with FROM-radio splitting, Calls tab UI, and SQLite call log persistence
- **Event Broadcasting Consolidation** — single-authority event model eliminates duplicate rows in Events tab
- **Extended PDU Assembly** — supports up to 32 data blocks per PDU (up from 5), enabling capture of larger IP packets and LRRP responses

### P25 Data Capture & Analysis

- **Data Capture Module** — captures IP payloads, LRRP GPS coordinates, XCMP/XNL fleet management, ARS registrations, and SNDCP session data
- **Phase 2 TDMA Data Channel (DATCH) Raw Capture** — captures raw 40-byte descrambled payloads from Motorola TDMA data sessions
- **Data Tab** — filterable UI with protocol coloring, GPS column, hex payload display, and per-system JSONL logging
- **Corpus Analysis Tools** — Python scripts for deep protocol analysis (`tools/`)

### Signal Analyzer (Phase 1)

- New Signal Analyzer tab with FFT-based signal detection
- Noise floor estimation, peak detection, and frame averaging
- Signal tracking with frequency proximity matching
- Artifact flagging: DC offset, harmonics, image frequencies

### UI Improvements

- **Spectrum/Waterfall** — reference level control and corrected dB calculation
- **Audio Channel Routing** — per-channel routing filters (Off/All/System/Group), per-channel mute, per-talkgroup mute, visible volume slider
- **Events Tab** — color-coded status column (active/ended/ignored), pre-filter mode, filtered save-to-CSV
- **Patch Group Column** — shows patch group membership in Events tab

## Building from Source

### Requirements

- **JDK:** Bellsoft Liberica JDK 25 (with JavaFX modules)
- **Gradle:** 8.10+ (via included wrapper)

### Commands

```bash
# Run from source
./gradlew run

# Build release zip
./gradlew runtimeZipCurrent

# Run tests
./gradlew test

# Clean
./gradlew clean
```

## System Requirements

- **OS:** Windows 64-bit, Linux 64-bit, or macOS 64-bit (12.x+)
- **CPU:** 4-core
- **RAM:** 8GB recommended (4GB minimum depending on usage)

## Documentation

- [CHANGELOG_FORK.md](CHANGELOG_FORK.md) — Detailed changelog for all fork changes
- [doc/changes/](doc/changes/) — Per-change documentation
- [doc/design/](doc/design/) — Design documents and analysis findings
- [Upstream Wiki](https://github.com/DSheirer/sdrtrunk/wiki) — Getting started, user manual, and support

## Upstream

This fork tracks [DSheirer/sdrtrunk](https://github.com/DSheirer/sdrtrunk). The `master` branch is kept clean for upstream syncing; all fork work is on feature branches.
