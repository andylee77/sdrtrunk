# 006b — Call Log UI, Playback & Events Panel Cleanup

## Date
2026-03-20

## Status
Design — Not Yet Implemented

## Prerequisites
- **010 — Call Session Management** (Calls tab, CallSession model)
- **006a — Call Log SQLite Database** (persistent storage)

## Summary

This document covers the user-facing call log experience: the **Calls tab** UI (defined
in 010), the **Events tab cleanup**, inline audio playback, transcript display, and
historical call loading from the database. It merges the original 006 Phase 2
(Events Panel UI Cleanup) and Phase 6 (Call List Playback UI & Metadata Display),
updated to work with the CallSession architecture.

---

## 1. Calls Tab (Primary Call View)

The Calls tab is the primary user interface for viewing calls. It is defined in
design doc 010 and shows **one row per CallSession** with expandable per-talker detail.

### Columns

| Column | Width | Description |
|--------|-------|-------------|
| ▶ | 24px | Play button — active if session has recordings |
| Time | 80px | Session start time (HH:mm:ss) |
| Duration | 60px | Session duration (M:SS) |
| Talkgroup | 80px | TG ID or alias |
| Patch Group | 100px | Patch member TGs (fills in as enriched) |
| From | 80px | Talker count badge, or single radio if 1 talker |
| Channel | 80px | Frequency or channel descriptor |
| Type | 100px | Event type (Group Call, Patch Group Call, etc.) |
| 📝 | 24px | Transcript indicator — 📝 if transcript exists |
| Details | flex | Event details text |

### Expand/Collapse (Per-Talker Detail)

Clicking or pressing → on a row expands to show per-talker events:

```
▼ 14:03:01 │ 0:12 │ TG 01085 │ P:149,233 │ 3 talkers │ 856.4625 │ Group Call
    ▶ 14:03:01–14:03:04 │ FROM 1234567 (Unit 12) │ recording_001.wav │ 📝
    ▶ 14:03:05–14:03:08 │ FROM 9876543 (Dispatch) │ recording_002.wav │ 📝
    ▶ 14:03:09–14:03:12 │ FROM 1234567 (Unit 12) │ recording_003.wav │ 📝
```

Each child row has its own play button, recording reference, and transcript.

### Live Updates

During an active call, the session row updates in-place:
- Duration increments
- Talker count increases
- Patch group column fills in when enrichment arrives
- Event type may upgrade (GROUP_CALL → PATCH_GROUP_CALL)
- Row is visually distinguished (bold, or highlighted background) while ACTIVE/PENDING

When the session reaches COMPLETE, the row settles to its final state.

### Table Model

`CallSessionModel` extends `AbstractTableModel`:
- Backed by a list of `CallSession` objects (live) + `CallLogRecord` objects (historical)
- Live sessions come from `P25CallSessionManager` via `CallSessionListener`
- Historical sessions loaded from `CallLogDatabase` on demand
- Maximum live rows: configurable (default 500)
- Historical rows loaded via "Load History" feature (see section 4)

---

## 2. Events Tab Cleanup

The existing **Events tab is preserved** showing raw protocol events exactly as upstream
produces them. However, the management controls are simplified since the Calls tab + DB
are now the primary call tracking mechanism.

### Changes to Events Tab Controls

| Component | Current | After |
|-----------|---------|-------|
| Pre-Filter checkbox | Shown | **Removed** (pre-filtering was our #004 change, no longer needed with DB) |
| Save checkbox | Shown | **Removed** (DB replaces CSV save for call data) |
| Clear button | Shown | **Removed** (events scroll off naturally) |
| History size slider | Shown | **Removed** (increase default to 2000, DB is source of truth) |
| Filter button | Shown | **Kept** — still useful for filtering the live event stream |
| DB status indicator | N/A | **New** — small label: "DB: ● Connected (12,345 calls)" |

### Implementation Approach

- Create `EventHistoryManagementPanel` as a simplified subclass of `HistoryManagementPanel`
  that only shows the Filter button + DB status
- The existing `HistoryManagementPanel` continues unchanged for the Messages tab
- Increase default event history size from 200 to 2000

### Messages Tab — Unchanged

The Messages tab keeps all existing controls (clear, history size, filter, save).
No changes.

---

## 3. Inline Audio Playback

### Player Panel

A slide-out player panel appears below the Calls table when a recording is selected:

```
┌──────────────────────────────────────────────────────────────────────┐
│  Calls  │  Events  │  Messages  │                                    │
├─────────┴──────────┴────────────┴────────────────────────────────────┤
│ ▶ │ Time     │ Dur  │ TG     │ Patch   │ From    │ Channel   │ ... │
│   │ 14:03:01 │ 0:12 │ 01085  │ 149,233 │ 3       │ 856.4625  │     │
│ ▶ │ 14:02:45 │ 0:08 │ 00233  │         │ 1       │ 856.7125  │     │
│   │ 14:02:30 │ 0:25 │ 01085  │ 149,233 │ 2       │ 856.4625  │     │
├──────────────────────────────────────────────────────────────────────┤
│ ▶ ⏸ ⏹  ───●────────────────────  0:04 / 0:12    🔊━━━━━━━━━━━━━   │
│                                                                      │
│ Transcript: "Engine 17 responding to 1234 Oak Street, smoke visible" │
└──────────────────────────────────────────────────────────────────────┘
```

### Controls

| Control | Function |
|---------|----------|
| ▶ Play | Start/resume playback |
| ⏸ Pause | Pause playback |
| ⏹ Stop | Stop and reset position |
| Seek slider | Drag to seek within recording |
| Time display | Current position / total duration |
| Volume slider | Adjust playback volume |
| Transcript area | Shows transcript text (scrolls if long) |

### Audio Backend

- **WAV files**: `javax.sound.sampled.Clip` or `AudioInputStream` → `SourceDataLine`
- **MP3 files**: JavaFX `MediaPlayer` (already available via Liberica JDK)
- **Streaming**: Feed audio data progressively for large files
- Recording path comes from `CallSession.getRecordingPath()` or `call_events.recording_path` in DB

### Playback from Calls Tab

- Click ▶ on a session row → plays all recordings for that session sequentially
- Click ▶ on a child (per-talker) row → plays just that talker's recording
- Double-click a row → expands + plays

### Transcript Display

- **Below player**: Shows full transcript text for the currently playing recording
- **Synchronized subtitles** (nice-to-have): If Whisper segment timestamps are stored,
  highlight the current phrase during playback
- **Tooltip on 📝 column**: Shows first ~100 chars of transcript on hover
- **Click 📝**: Opens full transcript in a scrollable text area

---

## 4. Historical Call Loading

### "Load History" Feature

A button or menu item opens a query dialog to load past calls from the DB:

```
┌─ Load Call History ────────────────────────────────────┐
│                                                         │
│ Date Range: [2026-03-01] to [2026-03-20]               │
│ Talkgroup:  [300, 301, 01085          ] (comma-sep)    │
│ System:     [Clay-County ▾] [All ▾]                    │
│ Search:     [structure fire             ] (transcripts) │
│                                                         │
│ Results: 1,247 calls found                              │
│                                                         │
│          [Load into Table]  [Export CSV]  [Cancel]      │
└─────────────────────────────────────────────────────────┘
```

### Loaded History in the Table

- Historical calls appear in the Calls table below live calls
- Visual separator line between live and historical sections
- Historical rows have a subtle background tint (light gray)
- Historical rows are fully interactive: expand, play, view transcript
- "Clear History" button removes loaded historical rows from the table

### Export

- **Export CSV**: Export query results to CSV file
- **Export JSON**: Export query results as JSON (for external tools)
- Fields: all `call_sessions` columns + joined `call_events` data

---

## 5. DB Status Indicator

A small status label in the Events tab toolbar (or Calls tab toolbar):

| State | Display |
|-------|---------|
| Connected | `DB: ● Connected (12,345 sessions)` — green dot |
| Writing | `DB: ● Writing...` — yellow dot (brief flash during write) |
| Disconnected | `DB: ○ Disconnected` — gray dot |
| Error | `DB: ✖ Error: [message]` — red dot |

Click the indicator to see DB file path, size on disk, session count, event count.

---

## New Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `CallSessionPanel` | `io.github.dsheirer.gui.decode.call` | Calls tab panel with table + player |
| `CallSessionModel` | `io.github.dsheirer.gui.decode.call` | Table model for Calls tab (live + historical) |
| `CallSessionTableCellRenderer` | `io.github.dsheirer.gui.decode.call` | Custom cell rendering (expand/collapse, live highlighting) |
| `AudioPlayerPanel` | `io.github.dsheirer.gui.decode.call` | Inline audio player (play/pause/seek/volume) |
| `TranscriptPanel` | `io.github.dsheirer.gui.decode.call` | Transcript display below player |
| `CallHistoryQueryDialog` | `io.github.dsheirer.gui.decode.call` | Dialog for loading historical calls from DB |
| `EventHistoryManagementPanel` | `io.github.dsheirer.module.decode.event` | Simplified management panel for Events tab |
| `DatabaseStatusIndicator` | `io.github.dsheirer.gui.decode.call` | DB connection status label |

---

## Modified Files

| File | Change |
|------|--------|
| `DecodeEventPanel.java` | Add Calls tab to the tab pane (alongside Events, Messages) |
| `HistoryManagementPanel.java` | Extract interface or make configurable for Events vs Messages |
| `ClearableHistoryModel.java` | Remove pre-filter/save logic for events usage; keep for messages |

---

## File Layout

```
src/main/java/io/github/dsheirer/
└── gui/
    └── decode/
        └── call/
            ├── CallSessionPanel.java
            ├── CallSessionModel.java
            ├── CallSessionTableCellRenderer.java
            ├── AudioPlayerPanel.java
            ├── TranscriptPanel.java
            ├── CallHistoryQueryDialog.java
            └── DatabaseStatusIndicator.java
```

---

## Implementation Notes

- The Calls tab is added in **010 Phase 1**. This document (006b) adds the playback,
  transcript display, and history loading features on top of the basic table.
- The Events tab cleanup can be done independently of the Calls tab.
- Audio playback is self-contained — it just reads recording files from disk.
  It doesn't depend on the live audio pipeline.
- Historical loading depends on 006a (database) being implemented first.
