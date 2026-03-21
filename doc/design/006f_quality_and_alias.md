# 006f — Transcription Quality Management & Inline Alias Management

## Date
2026-03-20

## Status
Design — Not Yet Implemented

## Prerequisites
- **010 — Call Session Management** (Calls tab for context menus)
- **006a — Call Log SQLite Database** (quality scores stored in DB)
- **006b — Call Log UI** (Calls tab with right-click context)
- **006d — Whisper Transcription** (transcripts to rate and manage)

## Summary

Two related quality-of-life features:

1. **Transcription Quality Management** — Rate, flag, re-run, and improve transcriptions
   over time using manual ratings, corrections dictionaries, and side-by-side comparison.

2. **Inline Alias Management** — Create and edit talkgroup/radio aliases directly from
   the Calls tab via right-click, eliminating the need to switch to the Playlist Editor.

These combine the original 006 Phase 9 (Transcription Quality) and Phase 10 (Inline Alias
Management), updated for the CallSession-based Calls tab UI.

---

## Part 1: Transcription Quality Management

### 1a: Manual Rating

- Rate transcripts directly from the Calls tab (006b)
- Click the quality column or right-click → "Rate Transcript"
- Options: 👍/👎 (simple) or 1-5 stars (detailed)
- Rating stored in `call_events.transcript_quality` (0-100 scale)
- Aggregated to `call_sessions.transcript_quality` (average of event ratings)

### 1b: Re-Run Flagging

- Flag transcripts for re-transcription with different settings
- Right-click → "Re-transcribe" or "Re-transcribe with..."
- Options when re-running:
  - Different Whisper model (e.g., large-v3 → large-v3-turbo)
  - Different prompt (try a different transcription profile)
  - Different parameters (beam size, temperature)
  - LLM enhancement only (keep Whisper output, re-run LLM)
- Re-run items enter the transcription queue (006d) at FLAGGED priority
- Original transcript preserved; new version replaces if accepted

### 1c: Side-by-Side Comparison

When a transcript has been re-run or LLM-enhanced:
- Show original Whisper transcript vs. LLM-corrected version
- Diff highlighting showing changes (insertions in green, deletions in red)
- Accept/reject each correction or accept all
- Accept updates `transcript` and `transcript_source` in DB

```
┌─ Transcript Comparison ──────────────────────────────────────┐
│ Original (Whisper large-v3)     │ Enhanced (LLM-corrected)   │
│                                  │                            │
│ "engine 7 team responding to    │ "Engine 17 responding to   │
│  twelve thirty four oak street   │  1234 Oak Street with      │
│  with smoke showing"             │  smoke showing"            │
│                                  │                            │
│ Changes: 2 corrections           │                            │
│ Quality: 62 → 89                 │                            │
│                                  │                            │
│              [Accept Enhanced] [Keep Original] [Cancel]       │
└──────────────────────────────────────────────────────────────┘
```

### 1d: Corrections Dictionary

Maintain a learned corrections dictionary for common Whisper errors:

```json
// {SDRTrunk_root}/configuration/transcribe_corrections.json
{
  "corrections": [
    { "from": "engine 7 team",   "to": "Engine 17",     "count": 12, "auto": true },
    { "from": "rescue 8 team",   "to": "Rescue 18",     "count": 8,  "auto": true },
    { "from": "letter 19",       "to": "Ladder 19",     "count": 5,  "auto": true },
    { "from": "clay fire",       "to": "Clay Fire",     "count": 23, "auto": false },
    { "from": "highway 17",      "to": "Hwy 17",        "count": 3,  "auto": false }
  ],
  "blockPhrases": [
    "thank you for watching",
    "please subscribe",
    "like and subscribe"
  ]
}
```

- **Auto-corrections** (`"auto": true`): Applied automatically as post-processing step
  after Whisper transcription, before LLM enhancement
- **Manual corrections** (`"auto": false`): Suggested but require user approval
- **Learning**: When user manually corrects a transcript, offer to add the correction
  to the dictionary
- **Count tracking**: Track how many times each correction has been applied
- **Feed into prompts**: Common corrections can be included in Whisper prompts
  to reduce the error in the first place

### 1e: Quality Analytics (Nice-to-Have)

Dashboard-style view for monitoring transcription quality:

| Metric | Description |
|--------|-------------|
| Average quality score | Per system, per talkgroup, per radio |
| Error rate | % of transcripts rated below threshold |
| Common corrections | Top N corrections applied |
| Per-radio quality | Identify radios with consistently poor transcription |
| Model comparison | If multiple models used, compare quality scores |

---

## Part 2: Inline Alias Management

### Problem

When a talkgroup or radio ID appears in the Calls tab with no alias, the user currently
has to switch to the Playlist Editor → Aliases tab, create a new alias, add an identifier,
type the number, configure settings, and save. This is tedious during active monitoring.

### Solution: Right-Click Context Menu + Quick Alias Dialog

#### Right-Click on Calls Tab Row

```
┌─────────────────────────────────┐
│ Create Alias for TG 300...      │  ← if TO has no alias
│ Create Alias for Radio 3406041..│  ← if FROM has no alias
│ Edit Alias "Clay Fire Dispatch" │  ← if TO has an alias
│ Edit Alias "Unit 1013"          │  ← if FROM has an alias
│ ─────────────────────────────── │
│ Copy Talkgroup ID               │
│ Copy Radio ID                   │
│ Copy Details                    │
│ ─────────────────────────────── │
│ Rate Transcript ►               │  ← 006f Part 1
│ Re-transcribe...                │  ← 006f Part 1
│ ─────────────────────────────── │
│ Create Incident...              │  ← 006e
│ Add to Incident ►               │  ← 006e
└─────────────────────────────────┘
```

#### Quick Alias Dialog

Auto-populated based on which row/column was right-clicked:

```
┌─ Create Alias ─────────────────────────────────────────┐
│                                                         │
│ Name: [                    ]                            │
│                                                         │
│ Identifier Type: [Talkgroup ▾]  ← auto-set from context│
│ ID Value: [300             ]  ← pre-filled from call    │
│                                                         │
│ Group: [Clay County Fire ▾]  ← dropdown of existing    │
│                                 alias groups            │
│                                                         │
│ ☑ Listen (audio playback)                               │
│ ☑ Record                                                │
│ ☐ Stream                                                │
│ Priority: [50 ▾]                                        │
│ Color: [■ Red ▾]                                        │
│                                                         │
│ ┌─ Transcription (if 006d enabled) ─────────────────┐  │
│ │ ☑ Enable Transcription                             │  │
│ │ Min Duration: [4s]  Profile: [Clay Fire Dispatch ▾]│  │
│ └────────────────────────────────────────────────────┘  │
│                                                         │
│         [Create & Apply]  [Cancel]                      │
└─────────────────────────────────────────────────────────┘
```

For editing existing aliases, the same dialog opens pre-populated with current settings.

### Features

| Feature | Description |
|---------|-------------|
| **Auto-detect type** | Right-clicking the TO/Talkgroup column → Talkgroup identifier; FROM column → Radio ID |
| **Pre-fill ID** | Talkgroup or radio ID number automatically filled from the call data |
| **Group dropdown** | Shows existing alias groups from the alias list, with "Create New..." option |
| **Quick toggles** | Listen, Record, Stream checkboxes immediately visible |
| **Priority** | Dropdown or spinner for priority level |
| **Color picker** | Optional alias color for display in tables |
| **Transcription** | Enable/disable transcription per-alias, select profile (if 006d is implemented) |
| **Immediate effect** | After creating/editing, alias takes effect on next event (no restart) |
| **Batch create** | Select multiple rows → "Create Aliases for Selected" to batch-create stubs |

### Auto-Suggest Group

When creating an alias from a call, suggest a group based on:
1. The system/site from the call session (e.g., "Clay-County" → suggest "Clay County" group)
2. Other aliases with similar talkgroup ranges
3. Most recently used group (for quick sequential alias creation)

### Implementation

#### New Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `QuickAliasDialog` | `io.github.dsheirer.gui.alias` | Quick create/edit dialog |
| `CallsContextMenu` | `io.github.dsheirer.gui.decode.call` | Right-click context menu for Calls tab |

#### Integration Points

- `CallSessionPanel` (006b): Add `MouseListener` for right-click → `CallsContextMenu`
- `CallsContextMenu`: Inspects clicked row and column to determine context actions
- `QuickAliasDialog`: Creates or modifies `Alias` objects via `AliasModel`
- `AliasModel.addAlias()` / `AliasModel.updateAlias()`: Existing API for persisting
- Changes saved to playlist file immediately

---

## File Layout

```
src/main/java/io/github/dsheirer/
├── gui/
│   ├── alias/
│   │   └── QuickAliasDialog.java
│   └── decode/
│       └── call/
│           └── CallsContextMenu.java       ← extends the context menu from 006b
└── transcribe/
    └── quality/
        ├── TranscriptComparisonDialog.java
        ├── CorrectionsManager.java
        └── QualityDashboard.java            ← nice-to-have
```

---

## Configuration Files

| File | Location | Format | Purpose |
|------|----------|--------|---------|
| `transcribe_corrections.json` | `{config}/` | JSON | Learned corrections dictionary |

---

## Implementation Priority Within This Document

| Priority | Feature | Effort | Value |
|----------|---------|--------|-------|
| 🔴 High | Inline alias creation (right-click → create) | Small | Very High |
| 🔴 High | Manual transcript rating (thumbs up/down) | Small | High |
| 🟡 Medium | Corrections dictionary (auto-apply) | Medium | High |
| 🟡 Medium | Re-run flagging | Small | Medium |
| 🟢 Low | Side-by-side comparison UI | Medium | Medium |
| 🟢 Low | Quality analytics dashboard | Large | Low |
| 🟢 Low | Batch alias creation | Small | Medium |

---

## Integration with Other Design Docs

| Doc | Integration |
|-----|-------------|
| **010** | Context menus appear on the Calls tab rows (CallSession data) |
| **006a** | Quality scores stored in `call_events.transcript_quality` / `call_sessions.transcript_quality` |
| **006b** | Context menu and dialogs attached to CallSessionPanel |
| **006d** | Re-run uses TranscriptionQueue; corrections applied as post-filter |
| **006e** | LLM quality rating feeds into `transcript_quality`; context menu includes incident items |
