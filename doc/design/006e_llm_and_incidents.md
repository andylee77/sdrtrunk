# 006e — LLM Integration & Incident Management

## Date
2026-03-20

## Status
Design — Not Yet Implemented

## Prerequisites
- **010 — Call Session Management** (CallSession model)
- **006a — Call Log SQLite Database** (persistent storage)
- **006d — Whisper Transcription** (transcripts to enhance/analyze)

## Summary

Integrate with LMStudio, Ollama, or any OpenAI-compatible API for transcript enhancement,
call analysis, summarization, location extraction, and incident auto-detection. Also
includes the incident management system for grouping related calls into incidents with
timeline views, cross-talkgroup linking, and export capabilities.

This combines the original 006 Phase 7 (LLM Integration) and Phase 8 (Incident Manager),
updated to work with the CallSession architecture and the two-table database schema (006a).

---

## Part 1: LLM Integration

### LLM Endpoint Configuration

```java
public class LLMConfiguration
{
    private String endpointUrl;       // "http://localhost:1234/v1"
    private String apiKey;            // optional, for OpenAI/cloud
    private String modelName;         // "local-model" or "gpt-4"
    private String provider;          // "lmstudio", "ollama", "openai", "custom"
    private boolean enabled;
    private int maxTokens;
    private float temperature;
    private int timeoutSeconds;
}
```

Configuration stored in `{SDRTrunk_root}/configuration/llm_config.json`.

### Features

#### 1a: Transcript Enhancement

Post-process Whisper output through LLM for correction and cleanup:
- Fix common Whisper errors (unit numbers, street names, radio codes)
- Correct domain-specific terminology based on system context
- Preserve original Whisper transcript in `transcript_source = 'whisper-...'`
- Store LLM-corrected version with `transcript_source = 'llm-corrected'`
- Configurable: auto-enhance all, or only flagged/low-quality transcripts

#### 1b: Call Summarization

Generate one-line summaries from transcripts:
- Stored in `call_sessions.llm_summary`
- Useful for incident timeline view and quick scanning
- Example: *"Engine 17 responding to structure fire at 1234 Oak St, reports smoke visible"*

#### 1c: Address/Location Extraction

Parse addresses, cross streets, and landmarks from transcripts:
- Store structured location data (address, lat/lon if geocodable)
- Replaces the Python `normalize_address.py` pipeline
- Could feed into SDRTrunk's existing map display feature

#### 1d: Incident Auto-Detection

Analyze sequences of calls to detect related incidents:
- Group calls by time proximity, shared talkgroups, mentioned addresses
- Suggest incident groupings to the user
- Auto-create incident entries if confidence is high enough
- See Part 2 below for incident data model

### LLM Processing Flow

```
Transcription Complete (from 006d)
       │
       ▼
  Is LLM enabled?
       │ no → done
       │ yes ↓
  Add to LLM processing queue
       │
       ▼
  LLMWorker sends to endpoint:
    System: "You are a radio dispatch transcript analyst for {system_name}..."
    User: "Analyze this radio transcript: {raw_transcript}
           Talkgroup: {tg_id} ({tg_alias})
           Radio: {from_id} ({from_alias})
           Duration: {duration}s
           
           Respond with JSON:
           {
             enhanced_transcript: '...',
             summary: '...',
             quality_score: 0-100,
             locations: [{address: '...', type: '...'}],
             incident_keywords: ['...']
           }"
       │
       ▼
  Parse JSON response
       │
       ▼
  Update DB:
    → call_events.transcript (LLM-corrected, if enhancement enabled)
    → call_events.transcript_source = 'llm-corrected'
    → call_sessions.llm_summary
    → call_events.transcript_quality (LLM-rated)
    → Incident linkage suggestion → user approval or auto-link
```

### LLM Queue

Similar to the transcription queue (006d):
- Priority: HIGH (live calls), LOW (batch)
- Single worker thread (LLM inference is sequential)
- Configurable timeout per request
- Retry on failure with backoff

---

## Part 2: Incident Management

### Incident Database Schema

```sql
CREATE TABLE IF NOT EXISTS incidents (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    title               TEXT NOT NULL,              -- "Structure Fire - 1234 Oak St"
    incident_type       TEXT,                       -- 'fire', 'ems', 'law', 'hazmat', 'other'
    status              TEXT DEFAULT 'active',      -- 'active', 'closed', 'archived'
    location            TEXT,                       -- primary address/location
    latitude            REAL,                       -- GPS coordinates (if available)
    longitude           REAL,
    units_assigned      TEXT,                       -- JSON array of unit names
    start_time          INTEGER,                    -- epoch ms of first related call
    end_time            INTEGER,                    -- epoch ms of last related call
    notes               TEXT,                       -- user notes
    llm_summary         TEXT,                       -- LLM-generated incident summary
    tags                TEXT,                       -- JSON array of tags
    created_at          INTEGER DEFAULT (strftime('%s','now') * 1000),
    updated_at          INTEGER DEFAULT (strftime('%s','now') * 1000)
);

CREATE INDEX IF NOT EXISTS idx_incidents_time ON incidents(start_time);
CREATE INDEX IF NOT EXISTS idx_incidents_type ON incidents(incident_type);
CREATE INDEX IF NOT EXISTS idx_incidents_status ON incidents(status);
```

The `call_sessions.incident_id` column (from 006a schema) links sessions to incidents.
Multiple sessions can belong to the same incident (many-to-one).

### Incident Linking

```
call_sessions.incident_id → incidents.id

Incident #42: "Structure Fire - 1234 Oak St"
    ├── Session: TG 300 (Dispatch), 15:07:26  "E17, R18, L19 respond to..."
    ├── Session: TG 300 (Dispatch), 15:08:14  "E17 responding, en route"
    ├── Session: TG 300 (Dispatch), 15:12:03  "E17 on scene, smoke visible"
    ├── Session: TG 301 (TAC),     15:12:45  "E17 to Command: working fire"
    ├── Session: TG 300 (Dispatch), 15:15:30  "B1 responding"
    └── Session: TG 301 (TAC),     15:22:18  "Command: fire under control"
```

### Manual Grouping

From the Calls tab (006b), right-click context menu:

```
┌─────────────────────────────────┐
│ Create Incident from Selection..│  ← select multiple rows
│ Add to Incident ►               │  ← submenu of recent incidents
│   │ #42 Structure Fire - 1234.. │
│   │ #41 MVA - Hwy 17 & CR 218  │
│   │ [Other...]                  │
└─────────────────────────────────┘
```

### Auto-Grouping (LLM-Assisted)

When LLM processing is enabled:
1. LLM analyzes each call's transcript for incident keywords
2. System checks for temporal proximity (calls within N minutes)
3. System checks for shared talkgroups or related talkgroup groups
4. If confidence is high: auto-link to existing incident or create new one
5. If confidence is medium: suggest to user via notification
6. If confidence is low: no action

### Incident Timeline UI

```
┌─ Incident #42: Structure Fire — 1234 Oak St ──────────┐
│ Status: Active   Type: Fire   Duration: 45 min         │
│ Units: E17, R18, L19, B1                                │
│                                                         │
│ Timeline:                                               │
│ ▶ 15:07:26  TG 300  Dispatch → E17, R18, L19 to 1234..│
│ ▶ 15:08:14  TG 300  E17: Responding, en route          │
│ ▶ 15:12:03  TG 300  E17: On scene, smoke visible...    │
│ ▶ 15:12:45  TG 301  E17 to Command: Working fire...    │
│ ▶ 15:15:30  TG 300  Dispatch: B1 responding...         │
│ ▶ 15:22:18  TG 301  Command: Fire under control        │
│                                                         │
│ Notes:                                                  │
│ [User-editable notes area                             ] │
│                                                         │
│ [Add Call] [Edit] [Close Incident] [Export PDF]         │
└─────────────────────────────────────────────────────────┘
```

Each timeline row has a ▶ play button to play the recording for that call.
Transcripts are shown inline or on hover.

### Incident Features

| Feature | Description |
|---------|-------------|
| **Cross-TG linking** | Link calls from dispatch (TG 300) + TAC (TG 301) into same incident |
| **Unit tracking** | Extract unit names from transcripts, track which units are assigned |
| **Status management** | Active → Closed → Archived lifecycle |
| **Notes** | Free-text notes field for user annotations |
| **Tags** | User-defined tags for categorization |
| **LLM summary** | Auto-generated incident summary from all linked transcripts |
| **Export** | PDF or HTML incident report with timeline, transcripts, and optionally map |

---

## LLM Configuration UI

```
┌─ LLM Configuration ───────────────────────────────────────────┐
│ [x] Enable LLM Post-Processing                                │
│                                                                │
│ Provider: [LMStudio ▾]                                         │
│ URL: [http://localhost:1234/v1                    ]             │
│ Model: [local-model                               ]            │
│ API Key: [                                        ] (optional) │
│                                                                │
│ [x] Auto-enhance transcripts                                  │
│ [x] Auto-rate quality                                          │
│ [x] Auto-generate summaries                                   │
│ [ ] Auto-detect incidents (experimental)                       │
│ [ ] Extract locations                                          │
│                                                                │
│ [Test Connection]  Status: ● Connected                         │
└────────────────────────────────────────────────────────────────┘
```

---

## New Classes

### LLM Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `LLMClient` | `io.github.dsheirer.transcribe.llm` | HTTP client for OpenAI-compatible API |
| `LLMProcessor` | `io.github.dsheirer.transcribe.llm` | Queue + worker for LLM processing |
| `LLMResult` | `io.github.dsheirer.transcribe.llm` | Result POJO: enhanced text, summary, quality, locations |
| `LLMConfiguration` | `io.github.dsheirer.transcribe.llm` | Endpoint config (URL, model, API key, features) |

### Incident Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `Incident` | `io.github.dsheirer.incident` | POJO representing an incident |
| `IncidentManager` | `io.github.dsheirer.incident` | CRUD operations, auto-grouping logic |
| `IncidentTimelinePanel` | `io.github.dsheirer.gui.incident` | Timeline UI with playback and transcripts |
| `IncidentListPanel` | `io.github.dsheirer.gui.incident` | List of incidents with search/filter |
| `IncidentContextMenu` | `io.github.dsheirer.gui.incident` | Right-click menu items for incident operations |

---

## File Layout

```
src/main/java/io/github/dsheirer/
├── transcribe/
│   └── llm/
│       ├── LLMClient.java
│       ├── LLMProcessor.java
│       ├── LLMResult.java
│       └── LLMConfiguration.java
└── incident/
    ├── Incident.java
    ├── IncidentManager.java
    └── gui/
        ├── IncidentTimelinePanel.java
        ├── IncidentListPanel.java
        └── IncidentContextMenu.java
```

---

## Configuration Files

| File | Location | Format | Purpose |
|------|----------|--------|---------|
| `llm_config.json` | `{config}/` | JSON | LLM endpoint settings |

---

## Integration with Other Design Docs

| Doc | Integration |
|-----|-------------|
| **010** | Incidents link to CallSessions; incident auto-detection uses session data |
| **006a** | `incidents` table in same DB; `call_sessions.incident_id` FK |
| **006b** | Right-click context menu in Calls tab for incident operations |
| **006d** | LLM processing triggered after transcription completes |
| **006f** | LLM quality rating stored in `transcript_quality` column |
