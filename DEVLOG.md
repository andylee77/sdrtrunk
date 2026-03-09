# SDRTrunk — Developer Log

Reference for repo structure, build system, and infrastructure in the `andylee77/sdrtrunk` fork.

---

## Repository Layout

```
sdrtrunk/
├── CHANGELOG_FORK.md     ← Fork tracking log (our changes, builds)
├── CHANGELOG             ← Upstream changelog (do not modify)
├── DEVLOG.md             ← This file (developer reference)
├── README.md             ← Upstream README
├── build.gradle          ← Gradle build config (Java 25, dependencies, runtime packaging)
├── gradle.properties     ← Version: 0.6.2-beta-1
├── gradlew / gradlew.bat ← Gradle wrapper scripts
├── settings.gradle       ← Gradle project settings
│
├── src/
│   ├── main/             ← Java source code
│   │   └── java/io/github/dsheirer/
│   │       ├── gui/                    ← GUI (JavaFX + Swing)
│   │       ├── source/tuner/           ← SDR tuner implementations
│   │       │   ├── plutosdr/           ← PlutoSDR support (OUR ADDITION)
│   │       │   ├── rtl/                ← RTL-SDR
│   │       │   ├── airspy/             ← Airspy
│   │       │   ├── hackrf/             ← HackRF
│   │       │   └── sdrplay/            ← SDRPlay
│   │       ├── dsp/filter/channelizer/ ← Channelizer (DDC changes)
│   │       ├── module/decode/
│   │       │   ├── p25/                ← P25 decoder (our improvements)
│   │       │   ├── dmr/                ← DMR decoder
│   │       │   └── ...
│   │       └── ...
│   └── test/             ← JUnit test source
│
├── gradle/               ← Gradle wrapper files
├── artifacts/            ← Build artifacts config
├── .github/              ← GitHub Actions workflows
│
└── doc/
    └── changes/          ← Detailed change documentation
```

---

## Build System

### Gradle (Java 25)

SDRTrunk is a Gradle-based Java project using Bellsoft Liberica JDK 25 with JavaFX.

**Prerequisites:**
- Bellsoft Liberica JDK 25+ (full, with JavaFX modules)
- Gradle 8.10+ (or use the included wrapper)

**Build commands:**
```bash
# Run from source (development)
gradlew run

# Create release package for current OS
gradlew runtimeZipCurrent

# Create releases for Windows
gradlew runtimeZipWindows

# Create releases for Linux & macOS
gradlew runtimeZipOthers

# Run tests
gradlew test

# Clean build
gradlew clean
```

**Key JVM flags** (configured in `build.gradle`):
- `--add-modules=jdk.incubator.vector` — Project Panama Vector API
- `--enable-preview` — Preview language features
- `--enable-native-access=ALL-UNNAMED` — Foreign memory access
- `-Djava.library.path=...` — SDRPlay API library path (Windows)

**Dependencies** (notable):
- JavaFX (via Liberica JDK)
- Jackson (XML/JSON serialization)
- usb4java (USB SDR access)
- JTransforms (FFT)
- Logback (logging)
- Apache Commons (compression, CSV, math)

---

## Git Remotes

| Remote | URL | Purpose |
|--------|-----|---------|
| `origin` | `https://github.com/andylee77/sdrtrunk.git` | Your fork |
| `upstream` | `https://github.com/DSheirer/sdrtrunk.git` | Original project |

### Branches

| Branch | Purpose |
|--------|---------|
| `master` | Tracks upstream, clean for syncing |
| `plutosdr` | Active development (PlutoSDR + DDC + P25 changes) |

### Upstream Sync

```bash
git fetch upstream
git checkout master
git merge upstream/master
git push origin master

git checkout plutosdr
git merge master
# Resolve conflicts if any
git push
```

---

## Related Repositories

| Repo | Branch | Purpose |
|------|--------|---------|
| `andylee77/sdrtrunk` | `plutosdr` | This repo — SDRTrunk desktop app |
| `andylee77/tezuka_fw` | `fishball-dev` | Firmware for Fishball Z7020 |
| `andylee77/maia-sdr` | `fishball-dev` | Maia SDR (FPGA + IQ streaming for PlutoSDR) |

---

## Work Documents (Outside Repo)

Located at `C:\Users\Andy\Projects\SDRTrunk\work_docs\`:

### PlutoSDR Integration (`plutosdr/`)

## Session Log

| Date | Session | What was done |
|------|---------|---------------|
| 2026-03-08 | Project setup | Forked repo, created plutosdr branch, set up workspace docs |
| 2026-03-08 | Migration analysis | Diffed modified source against fresh clone, identified 81 changed files across 13 categories. Created `DEVPLAN.md` with full migration work plan. Phase 1 (PlutoSDR, 22 items) and Phase 2 (Waterfall/Spectrum, 5 items) prioritized. DDC channelizer explicitly skipped. Updated `.clinerules` to clarify `doc/changes/` is for completed changes only. |
