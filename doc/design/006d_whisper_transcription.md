# 006d — Whisper Transcription

## Date
2026-03-20

## Status
Design — Not Yet Implemented

## Prerequisites
- **010 — Call Session Management** (CallSession lifecycle triggers transcription)
- **006a — Call Log SQLite Database** (transcript storage)
- **006c — Recording Organization** (recording paths available)

## Summary

Native Java Whisper transcription triggered automatically on completed call recordings.
Uses whisper.cpp via JNI binding for GPU-accelerated (CUDA) or CPU-based transcription.
Includes per-talkgroup/radio prompt profiles, hallucination filtering, batch processing,
and a transcription queue with priority management.

This design is updated from the original 006 to use the **CallSession architecture**:
transcription is triggered by `CallSessionListener.onSessionComplete()` and results
are written to the call log database (006a) per-event.

---

## Technology Choice

**whisper.cpp JNI via [whisper-jni](https://github.com/GiviMAD/whisper-jni)**
- Most mature Java binding for whisper.cpp
- Supports CUDA GPU acceleration and CPU fallback
- Uses GGML model format (e.g., `ggml-large-v3.bin` ~3GB)
- Apache 2.0 license

### Dependency

```groovy
implementation 'io.github.givimad:whisper-jni:...'
```

---

## Transcription Flow

```
CallSession reaches COMPLETE
    │
    ├── CallLogWriter writes session + events to DB (006a)
    │
    └── TranscriptionTrigger checks each call_event:
            │
            ├── Is transcription enabled for this TG/radio?
            │       │ no → skip
            │       │ yes ↓
            ├── Does this event have a recording?
            │       │ no → skip
            │       │ yes ↓
            ├── Is duration >= minDuration?
            │       │ no → skip
            │       │ yes ↓
            └── Add to TranscriptionQueue
                    │
                    ▼
            TranscriptionWorker picks up
                    │
                    ▼
            Load audio → High-pass filter → Normalize
                    │
                    ▼
            Audio quality check (silence, tones, active frames)
                    │ passes ↓  │ fails → mark as "no speech"
                    ▼
            Select prompt from TranscribeProfile (TG/radio match)
                    │
                    ▼
            Run Whisper transcription
                    │
                    ▼
            Apply hallucination filters
                    │
                    ▼
            Write results:
                → call_events.transcript, transcript_source (DB update)
                → call_sessions.transcript (aggregated, DB update)
                → MP3 ID3 tags: COMM, USLT, TIT3 (optional)
                → LLM post-processing queue (006e, if enabled)
```

### Key Change from Original 006

The trigger is `CallSessionListener.onSessionComplete()`, not AudioSegment completion.
Each `call_event` within the session (one per talker) is independently queued for
transcription, since each has its own recording file. The session-level transcript
is an aggregation of the per-event transcripts.

---

## Model Loading

### Startup Detection

1. On application start, check if any channel has transcription enabled
2. If yes, show loading popup (modal dialog):

```
┌─ Loading Whisper Model ────────────────────┐
│                                              │
│  Loading Whisper model: large-v3             │
│  Device: CUDA (NVIDIA RTX 4090)              │
│                                              │
│  ████████████████░░░░░░░░  65%               │
│                                              │
│  This may take a moment...                   │
│                                              │
│              [Cancel]                         │
└──────────────────────────────────────────────┘
```

3. If no channels need transcription at startup, load lazily when first needed
4. External model support: user can point to any GGML model file on disk

### Model Lifecycle

- `WhisperModelManager` is a singleton
- Model loaded once, shared across all transcription workers
- Unload via preferences UI or when all transcription-enabled channels stop
- Memory: ~3GB for large-v3 (GPU VRAM or system RAM)

---

## Transcription Queue

### Priority System

| Priority | Source | Description |
|----------|--------|-------------|
| HIGH | Live calls | Transcription from just-completed live calls |
| LOW | Batch | Batch transcription of historical recordings |
| FLAGGED | Re-run | Flagged for re-transcription (006f) |

### Queue Configuration

```java
public class TranscriptionQueue
{
    private PriorityBlockingQueue<TranscriptionRequest> mQueue;
    private int mMaxQueueSize = 100;          // configurable
    private TranscriptionWorker mWorker;       // single worker for GPU
    private volatile boolean mPaused = false;  // pause support for batch
}
```

### Concurrency

- **GPU mode**: Single worker thread (GPU can only process one at a time)
- **CPU mode**: Configurable 1-N workers (default 1, user can increase)

---

## Transcription Profiles

Per-talkgroup/radio-ID prompt and settings configuration.

### Configuration File

`{SDRTrunk_root}/configuration/transcribe_profiles.json`:

```json
{
  "globalSettings": {
    "enabled": true,
    "modelPath": "C:/models/ggml-large-v3.bin",
    "modelSize": "large-v3",
    "device": "cuda",
    "language": "en",
    "beamSize": 5,
    "noSpeechThreshold": 0.50,
    "defaultMinDurationSeconds": 4,
    "queueMaxSize": 100
  },
  "profiles": [
    {
      "name": "Clay Fire Dispatch",
      "matchRules": [
        { "type": "talkgroup", "value": "300" },
        { "type": "radio_id", "range": "1011-1014" }
      ],
      "minDurationSeconds": 2,
      "prompt": "Clay County Florida Fire and EMS radio dispatch. Units: Engine, Rescue, Ladder...",
      "enabled": true
    },
    {
      "name": "Clay Fire TAC",
      "matchRules": [
        { "type": "talkgroup", "value": "301" },
        { "type": "talkgroup", "value": "302" }
      ],
      "minDurationSeconds": 4,
      "prompt": "Clay County Florida Fire EMS tactical channel...",
      "enabled": true
    },
    {
      "name": "Generic Fire/EMS",
      "matchRules": [
        { "type": "wildcard", "value": "*" }
      ],
      "minDurationSeconds": 5,
      "prompt": "Fire and EMS radio communications...",
      "enabled": true
    }
  ]
}
```

### Profile Matching

When a call_event is ready for transcription:
1. Check profiles in order (first match wins)
2. Match by talkgroup ID, radio ID, or wildcard
3. Use matched profile's prompt, min duration, and settings
4. If no profile matches and global `enabled` is true, use default settings

---

## Hallucination Filtering

Ported from the existing Python `transcribe_audio.py` battle-tested filtering:

### Filters (Applied in Order)

| Filter | Description | Action |
|--------|-------------|--------|
| **Noise/beep runs** | Repeated character patterns (`AAAA`, `BEEEE`, `♪♪♪`) | Remove runs |
| **Full block phrases** | "thank you for watching", "please subscribe", "like and subscribe", etc. | Discard entire result |
| **Cutoff phrases** | Trailing hallucination phrases ("thanks for watching") | Trim text at phrase |
| **Repetitive words** | >50% same word in output | Collapse to single occurrence |
| **Filler-only** | "uh", "um", "hmm" as sole output | Discard |
| **No-speech probability** | Whisper's `no_speech_prob` > threshold | Discard |
| **Short result** | ≤2 characters after filtering | Discard |
| **Empty after filter** | All text removed by filters | Mark as "no speech detected" |

### HallucinationFilter Class

```java
public class HallucinationFilter
{
    private Set<String> mBlockPhrases;    // loaded from config or hardcoded
    private Set<String> mCutoffPhrases;
    private double mNoSpeechThreshold;

    public TranscriptionResult filter(TranscriptionResult raw) { ... }
}
```

---

## Audio Quality Checker

Pre-screens audio before sending to Whisper to avoid wasting GPU time:

| Check | Condition | Result |
|-------|-----------|--------|
| **Silence** | <10% of frames above noise floor | Skip — "silence" |
| **Tone-only** | Dominant single frequency (DTMF, alert tone) | Skip — "tone only" |
| **Too short** | Effective audio < 0.5s after trimming silence | Skip — "too short" |
| **Encryption noise** | Characteristic encrypted audio pattern | Skip — "encrypted" |

```java
public class AudioQualityChecker
{
    public AudioQualityResult check(float[] audioSamples, int sampleRate) { ... }
}
```

---

## Batch Transcription

Process existing recordings that don't have transcripts.

### Trigger

- "Transcribe Unprocessed" button in the Calls tab or Transcribe Config UI
- Queries DB: `SELECT * FROM call_events WHERE recording_path IS NOT NULL AND transcript IS NULL`
- Feeds all matching recordings into `TranscriptionQueue` at LOW priority

### Progress UI

```
┌─ Batch Transcription ─────────────────────────────────┐
│ Unprocessed events with recordings: 1,247              │
│                                                        │
│ Filter: Date [2026-03-01] to [2026-03-20]             │
│         Talkgroup: [300, 301    ]                      │
│         System: [Clay-County ▾]                        │
│                                                        │
│ [Start Batch]  [Pause]  [Cancel]                       │
│                                                        │
│ Progress: 45/1247  ████░░░░░░  3.6%                   │
│ Current: 20260315_150726_Clay-County_..._TO_300.mp3    │
│ ETA: ~2h 15m                                           │
└────────────────────────────────────────────────────────┘
```

### Features

- **Filters**: Constrain batch by date range, talkgroup, system
- **Pause/cancel**: User can pause or cancel at any time
- **Priority**: Live recordings always take priority over batch items
- **Resume**: Batch progress is implicit — only un-transcribed events are queued

---

## Transcribe Configuration UI

New tab in preferences or standalone config panel:

```
┌─ Transcribe Configuration ─────────────────────────────────────┐
│ [x] Enable Transcription                                        │
│                                                                  │
│ ┌─ Whisper Model ──────────────────────────────────────────────┐│
│ │ Model: [large-v3 ▾]                                          ││
│ │ Path:  [C:/models/ggml-large-v3.bin          ] [Browse]      ││
│ │ Device: [CUDA GPU ▾]   Status: ● Loaded (3.2 GB)            ││
│ │ [Load Model]  [Unload]                                       ││
│ └──────────────────────────────────────────────────────────────┘│
│                                                                  │
│ ┌─ Transcription Profiles ─────────────────────────────────────┐│
│ │ Profile           │ Match              │ Min Dur │ Enabled   ││
│ │ Clay Fire Dispatch│ TG:300, Radio:1011 │ 2s      │ ✓         ││
│ │ Clay Fire TAC     │ TG:301, TG:302     │ 4s      │ ✓         ││
│ │ Generic Fire/EMS  │ *                  │ 5s      │ ✓         ││
│ │                                                               ││
│ │ [Add Profile] [Edit] [Remove] [Import] [Export]               ││
│ └──────────────────────────────────────────────────────────────┘│
│                                                                  │
│ ┌─ Batch Processing ──────────────────────────────────────────┐│
│ │ Unprocessed events with recordings: 1,247                    ││
│ │ [Start Batch]  [Pause]  [Cancel]                             ││
│ │ Queue: 0 pending  │  Processed today: 145                    ││
│ └──────────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────┘
```

---

## New Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `WhisperModelManager` | `io.github.dsheirer.transcribe` | Singleton — lazy-loads model, manages lifecycle |
| `TranscriptionQueue` | `io.github.dsheirer.transcribe` | Thread-safe priority queue for pending transcriptions |
| `TranscriptionWorker` | `io.github.dsheirer.transcribe` | Background thread that processes the queue |
| `TranscriptionRequest` | `io.github.dsheirer.transcribe` | Queue item: recording path, event ID, priority, profile |
| `TranscriptionResult` | `io.github.dsheirer.transcribe` | Result POJO: text, confidence, segments, quality |
| `TranscriptionTrigger` | `io.github.dsheirer.transcribe` | Implements `CallSessionListener`, queues events for transcription |
| `TranscribeConfiguration` | `io.github.dsheirer.transcribe.config` | Global transcription settings |
| `TranscribeProfile` | `io.github.dsheirer.transcribe.config` | Per-talkgroup/radio-ID prompt + settings |
| `TranscribeProfileManager` | `io.github.dsheirer.transcribe.config` | Loads/saves `transcribe_profiles.json` |
| `HallucinationFilter` | `io.github.dsheirer.transcribe.filter` | Port of Python hallucination filtering logic |
| `AudioQualityChecker` | `io.github.dsheirer.transcribe.filter` | Pre-screen audio for quality/silence/tones |

---

## File Layout

```
src/main/java/io/github/dsheirer/
└── transcribe/
    ├── WhisperModelManager.java
    ├── TranscriptionQueue.java
    ├── TranscriptionWorker.java
    ├── TranscriptionRequest.java
    ├── TranscriptionResult.java
    ├── TranscriptionTrigger.java
    ├── config/
    │   ├── TranscribeConfiguration.java
    │   ├── TranscribeProfile.java
    │   └── TranscribeProfileManager.java
    └── filter/
        ├── HallucinationFilter.java
        └── AudioQualityChecker.java
```

---

## Configuration Files

| File | Location | Format | Purpose |
|------|----------|--------|---------|
| `transcribe_profiles.json` | `{config}/` | JSON | Per-TG/radio transcription prompts and settings |

---

## Integration with 010 Phases

| 010 Phase | Transcription Behavior |
|---|---|
| Phase 1 (CallSession + Calls tab) | TranscriptionTrigger subscribes to session manager, queues on COMPLETE |
| Phase 2 (Audio integration) | No change — transcription works from recording files, not live audio |
| Phase 3 (Recording integration) | Recording paths reliably available on session — transcription more reliable |
| Phase 4 (Consolidation) | No change |

---

## References

- Existing Python scripts: `C:\Users\Andy\SDRTrunk\recordings\transcribe_audio.py`
- Existing Python scripts: `C:\Users\Andy\SDRTrunk\recordings\transcribe_config.py`
- whisper-jni: https://github.com/GiviMAD/whisper-jni
- whisper.cpp: https://github.com/ggerganov/whisper.cpp
