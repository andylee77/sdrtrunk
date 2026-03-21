# 006c — Recording Organization

## Date
2026-03-20

## Status
Design — Not Yet Implemented

## Prerequisites
- **010 — Call Session Management** (CallSession owns recording paths)
- **006a — Call Log SQLite Database** (recording paths stored in DB)

## Summary

Port the existing Python `organize_recordings.py` into Java as a built-in SDRTrunk feature.
Recordings are automatically organized into a directory structure based on system, talkgroup,
and date. The organizer operates on completed recordings, either automatically on completion
or manually via a batch operation.

With the CallSession architecture, recording paths are owned by the session and its events.
The organizer updates both the file system and the database when it moves files.

---

## Current Python Logic (Reference)

The existing `organize_recordings.py` script:
- Parses SDRTrunk recording filenames to extract: date, time, system, site, channel, talkgroup, source radio
- Creates directory structure: `organized/{System}/TG_{talkgroup}/{YYYY-MM-DD}/{original_filename}`
- Detects duplicates via MD5 hash comparison
- Moves files from flat recordings directory to organized structure

---

## Design

### Recording Flow with CallSession

```
CallSession reaches COMPLETE
    │
    ├── CallLogWriter writes to DB (006a)
    │       → call_events rows include recording_path
    │
    └── RecordingOrganizer (if auto-organize enabled)
            │
            ├── Parse recording metadata from CallSession (not filename)
            │       → system, talkgroup, radio, timestamp all available directly
            │
            ├── Build target path: {base}/{System}/TG_{talkgroup}/{YYYY-MM-DD}/{filename}
            │
            ├── Move file to target path
            │
            └── Update DB: call_events.recording_organized_path = new path
```

**Key improvement over Python script**: With CallSession, we don't need to parse the filename
to extract metadata — the session already has system, talkgroup, radio ID, timestamp, etc.
The filename parser is still useful for organizing historical recordings that predate the DB.

### Auto-Organize on Recording Completion

When a recording is completed:
1. `CallSessionListener.onSessionComplete()` fires
2. `RecordingOrganizer` (also a listener, or called by `CallLogWriter`) checks if auto-organize is enabled
3. For each `call_event` recording in the session:
   - Compute target directory from session metadata
   - Create directories if needed
   - Move file
   - Update `recording_organized_path` in DB

### Manual Batch Organize

A "Organize Existing Recordings" button processes files in the flat recordings directory:
1. Glob-search for recording files (`*.wav`, `*.mp3`)
2. Parse filenames using `RecordingFilenameParser` to extract metadata
3. Build target paths and move files
4. If a matching DB row exists, update `recording_organized_path`
5. Report results: moved, skipped (already organized), duplicates found

### Duplicate Detection

- MD5 hash comparison for files with the same target path
- Duplicates moved to `{base}/duplicates/{filename}`
- Log duplicate detection for user review

---

## Directory Structure

Default pattern:
```
{organized_base}/
├── Clay-County/
│   ├── TG_00300/
│   │   ├── 2026-03-15/
│   │   │   ├── 20260315_150726_Clay-County_Site1_CC1__TO_300_FROM_3406041.mp3
│   │   │   └── 20260315_151203_Clay-County_Site1_CC1__TO_300_FROM_1013.mp3
│   │   └── 2026-03-16/
│   │       └── ...
│   └── TG_00301/
│       └── ...
├── duplicates/
│   └── ...
└── unmatched/         ← files that couldn't be parsed
    └── ...
```

### Customizable Pattern

Users can configure the folder pattern. Variables available:

| Variable | Example | Source |
|----------|---------|-------|
| `{system}` | `Clay-County` | Session system name or filename parse |
| `{site}` | `Site1` | Session site name or filename parse |
| `{talkgroup}` | `00300` | Session talkgroup or filename parse |
| `{talkgroup_alias}` | `Clay_Fire_Dispatch` | Alias lookup (spaces → underscores) |
| `{date}` | `2026-03-15` | Session start date |
| `{year}` | `2026` | Session start year |
| `{month}` | `03` | Session start month |
| `{radio}` | `3406041` | FROM radio ID |

Default pattern: `{system}/TG_{talkgroup}/{date}/`

---

## New Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `RecordingOrganizer` | `io.github.dsheirer.record.organize` | Core organize logic — move files, update DB |
| `RecordingFilenameParser` | `io.github.dsheirer.record.organize` | Parse SDRTrunk recording filenames for metadata |
| `OrganizeConfiguration` | `io.github.dsheirer.record.organize` | Settings: target dir, pattern, auto-organize flag, duplicates dir |

---

## Configuration

Add "Recording Organization" section in Recording preferences or channel config:

```
[x] Auto-organize after recording
Target directory: [C:\Users\Andy\SDRTrunk\organized    ] [Browse]
Folder pattern:   [{system}/TG_{talkgroup}/{date}/     ]
[Organize Existing Recordings]  (button → batch process)
```

### OrganizeConfiguration Fields

```java
public class OrganizeConfiguration
{
    private boolean autoOrganize = false;
    private String targetDirectory;          // base directory for organized files
    private String folderPattern = "{system}/TG_{talkgroup}/{date}/";
    private String duplicatesDirectory;      // defaults to {target}/duplicates/
    private boolean moveOriginal = true;     // true=move, false=copy
}
```

---

## Filename Parser

For historical recordings that predate the CallSession DB, parse the filename:

```java
public class RecordingFilenameParser
{
    // Pattern: YYYYMMDD_HHMMSS_{System}_{Site}_{Channel}__TO_{TG}[_FROM_{Radio}].ext
    private static final Pattern PATTERN = Pattern.compile(
        "(\\d{8})_(\\d{6})_([^_]+)_([^_]+)_([^_]+)__TO_(\\d+)(?:_FROM_(\\d+))?\\.\\w+");

    public RecordingMetadata parse(String filename) { ... }
}
```

Returns a `RecordingMetadata` record with: date, time, system, site, channel, talkgroup,
radio (optional), and original filename.

---

## File Layout

```
src/main/java/io/github/dsheirer/
└── record/
    └── organize/
        ├── RecordingOrganizer.java
        ├── RecordingFilenameParser.java
        ├── RecordingMetadata.java
        └── OrganizeConfiguration.java
```

---

## Integration Notes

- Auto-organize hooks into `CallSessionListener.onSessionComplete()` — same event that
  triggers the DB write (006a). Order: DB write first, then organize, then update DB path.
- The organizer is independent of transcription (006d) — organizing happens whether or
  not transcription is enabled.
- Batch organize can be run at any time, even on recordings from before the DB existed.
- The Python `organize_recordings.py` script remains functional as a standalone tool.
