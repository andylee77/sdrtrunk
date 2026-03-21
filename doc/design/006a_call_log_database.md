# 006a — Call Log SQLite Database

## Date
2026-03-20

## Status
Design — Not Yet Implemented

## Prerequisites
- **010 — Call Session Management** (CallSession + P25CallSessionManager)

## Summary

Persist completed call sessions and their per-talker events to a SQLite database,
replacing the current in-memory-only event history. The database is the single
persistent store for all call data, recordings, transcripts, and incident linkage.

This design is built on the **CallSession architecture** from design doc 010. The data
source is the `P25CallSessionManager`, not raw `DecodeEvent` objects. Writing to the DB
is triggered by the CallSession lifecycle (COMPLETE state), eliminating the need for the
debounce logic that the original 006 design required.

---

## Design Decisions

- **SQLite via JDBC** — Java's built-in `java.sql` package, no new dependencies
- **Database per system** — `{SDRTrunk_root}/call_logs/{system_name}_calls.db`
- **Write on session complete** — `CallLogWriter` implements `CallSessionListener` and
  writes when a CallSession reaches COMPLETE. No debounce timers needed.
- **Two-table schema** — `call_sessions` (one row per call) + `call_events` (one row per
  talker within a call), mirroring the 010 architecture exactly

---

## Database Schema

### `call_sessions` Table

One row per `CallSession` — represents a complete P25 call from channel grant to termination.

```sql
CREATE TABLE IF NOT EXISTS call_sessions (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,

    -- Timing
    time_start              INTEGER NOT NULL,       -- epoch milliseconds
    time_end                INTEGER NOT NULL,       -- epoch milliseconds
    duration_ms             INTEGER NOT NULL,       -- time_end - time_start

    -- Identity
    talkgroup_id            TEXT NOT NULL,           -- talkgroup number
    talkgroup_alias         TEXT,                    -- resolved alias at time of write
    patch_group_id          TEXT,                    -- supergroup ID (null if not patch call)
    patch_group_members     TEXT,                    -- comma-separated member TG IDs
    event_type              TEXT NOT NULL,           -- DecodeEventType label (GROUP_CALL, PATCH_GROUP_CALL, etc.)

    -- Channel
    frequency               REAL,                   -- frequency in Hz
    timeslot                INTEGER,                -- timeslot number (-1 if N/A)
    channel_descriptor      TEXT,                   -- P25 channel number/descriptor

    -- Session metadata
    talker_count            INTEGER DEFAULT 0,      -- number of distinct FROM radios
    encrypted               INTEGER DEFAULT 0,      -- 1 if encrypted call
    duplicate               INTEGER DEFAULT 0,      -- 1 if marked as duplicate by session manager

    -- System context
    system                  TEXT,                   -- system name from channel config
    site                    TEXT,                   -- site name
    channel_name            TEXT,                   -- channel name

    -- Protocol details
    details                 TEXT,                   -- event details string
    service_options         TEXT,                   -- P25 service options (JSON or text)

    -- Linked data (populated by later phases)
    transcript              TEXT,                   -- combined/primary transcript (006d)
    transcript_source       TEXT,                   -- 'whisper-large-v3', 'llm-corrected' (006d)
    transcript_quality      INTEGER,                -- quality rating 0-100 (006f)
    llm_summary             TEXT,                   -- LLM-generated summary (006e)
    incident_id             INTEGER,                -- linked incident FK (006e)

    -- Housekeeping
    created_at              INTEGER DEFAULT (strftime('%s','now') * 1000)
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_sessions_time ON call_sessions(time_start);
CREATE INDEX IF NOT EXISTS idx_sessions_talkgroup ON call_sessions(talkgroup_id);
CREATE INDEX IF NOT EXISTS idx_sessions_system ON call_sessions(system);
CREATE INDEX IF NOT EXISTS idx_sessions_incident ON call_sessions(incident_id);
CREATE INDEX IF NOT EXISTS idx_sessions_no_transcript
    ON call_sessions(transcript) WHERE transcript IS NULL;
```

### `call_events` Table

One row per talker (radio ID change) within a session. Linked to the parent session.
Each event has its own recording and transcript.

```sql
CREATE TABLE IF NOT EXISTS call_events (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id              INTEGER NOT NULL REFERENCES call_sessions(id),

    -- Timing
    time_start              INTEGER NOT NULL,       -- epoch milliseconds
    time_end                INTEGER NOT NULL,       -- epoch milliseconds
    duration_ms             INTEGER NOT NULL,

    -- Identity
    event_type              TEXT NOT NULL,           -- DecodeEventType label
    from_id                 TEXT,                    -- source radio ID
    from_alias              TEXT,                    -- resolved alias for FROM radio
    to_id                   TEXT,                    -- talkgroup (same as session)
    to_alias                TEXT,                    -- talkgroup alias

    -- Details
    details                 TEXT,

    -- Recording (006c)
    recording_path          TEXT,                   -- path to per-talker recording
    recording_organized_path TEXT,                  -- path after organizing

    -- Transcription (006d)
    transcript              TEXT,                   -- per-talker transcript
    transcript_source       TEXT,
    transcript_quality      INTEGER,

    -- Housekeeping
    created_at              INTEGER DEFAULT (strftime('%s','now') * 1000)
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_events_session ON call_events(session_id);
CREATE INDEX IF NOT EXISTS idx_events_time ON call_events(time_start);
CREATE INDEX IF NOT EXISTS idx_events_from ON call_events(from_id);
CREATE INDEX IF NOT EXISTS idx_events_no_transcript
    ON call_events(recording_path, transcript)
    WHERE recording_path IS NOT NULL AND transcript IS NULL;
```

### Relationship

```
call_sessions (1) ──→ (N) call_events

Session: TG 01085, Patch 149+233, 14:03:01–14:03:12, 3 talkers
    ├── Event 1: FROM 1234567, 14:03:01–14:03:04, recording_001.wav
    ├── Event 2: FROM 9876543, 14:03:05–14:03:08, recording_002.wav
    └── Event 3: FROM 1234567, 14:03:09–14:03:12, recording_003.wav
```

---

## New Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `CallLogDatabase` | `io.github.dsheirer.calllog` | Manages SQLite connection, table creation, CRUD operations |
| `CallLogRecord` | `io.github.dsheirer.calllog` | POJO for a session row (maps to `call_sessions`) |
| `CallEventRecord` | `io.github.dsheirer.calllog` | POJO for an event row (maps to `call_events`) |
| `CallLogWriter` | `io.github.dsheirer.calllog` | Implements `CallSessionListener`, writes sessions+events on COMPLETE |
| `CallLogConfiguration` | `io.github.dsheirer.calllog.config` | Persisted config (db path, enabled flag) |

---

## CallLogWriter — How It Works

### No Debounce Needed

The original 006 design required a complex debounce mechanism because `DecodeEvent` objects
are broadcast repeatedly during a call (duration updates, identifier updates, etc.). Each
update would trigger a write if not debounced.

With the CallSession architecture, this problem disappears:

```
Old approach (006 original):
  DecodeEvent published repeatedly → CallLogWriter must debounce → timer-based "completed" check

New approach (006a, based on 010):
  CallSession reaches COMPLETE → CallSessionListener.onSessionComplete(session) → write to DB
  One write. No timers. No debounce.
```

### Listener Interface

```java
public class CallLogWriter implements CallSessionListener
{
    private CallLogDatabase mDatabase;

    @Override
    public void onSessionComplete(CallSession session)
    {
        // Extract session data → CallLogRecord
        CallLogRecord record = toRecord(session);

        // Extract per-talker events → List<CallEventRecord>
        List<CallEventRecord> events = toEventRecords(session);

        // Write to DB (session + events in a single transaction)
        mDatabase.insertSessionWithEvents(record, events);
    }

    @Override
    public void onSessionUpdated(CallSession session)
    {
        // Optional: could update an in-progress row for crash recovery
        // For Phase 1, we only write on COMPLETE
    }
}
```

### Transaction Pattern

Session and events are written in a single SQLite transaction for atomicity:

```java
public void insertSessionWithEvents(CallLogRecord session, List<CallEventRecord> events)
{
    try (Connection conn = getConnection())
    {
        conn.setAutoCommit(false);
        try
        {
            long sessionId = insertSession(conn, session);
            for (CallEventRecord event : events)
            {
                event.setSessionId(sessionId);
                insertEvent(conn, event);
            }
            conn.commit();
        }
        catch (SQLException e)
        {
            conn.rollback();
            throw e;
        }
    }
}
```

---

## Channel Configuration

Add `CallLogConfiguration` to `Channel` class alongside existing config objects:

```java
@JacksonXmlProperty(isAttribute = false, localName = "call_log_configuration")
public CallLogConfiguration getCallLogConfiguration() { ... }
```

Fields:
- `enabled` (boolean, default: `true`)
- `databasePath` (String, auto-generated from system name if null)

---

## Query Patterns

Common queries the UI (006b) and other phases will use:

```sql
-- Recent calls for a system
SELECT * FROM call_sessions WHERE system = ? ORDER BY time_start DESC LIMIT 100;

-- Calls for a talkgroup in a date range
SELECT * FROM call_sessions
WHERE talkgroup_id = ? AND time_start BETWEEN ? AND ?
ORDER BY time_start DESC;

-- Events for a session (expand view)
SELECT * FROM call_events WHERE session_id = ? ORDER BY time_start ASC;

-- Full-text search across transcripts
SELECT s.* FROM call_sessions s
LEFT JOIN call_events e ON e.session_id = s.id
WHERE s.transcript LIKE ? OR e.transcript LIKE ? OR s.details LIKE ?
GROUP BY s.id ORDER BY s.time_start DESC;

-- Calls with recordings but no transcript (batch transcription, 006d)
SELECT e.* FROM call_events e
WHERE e.recording_path IS NOT NULL AND e.transcript IS NULL;

-- Session + all events joined (flat view for export)
SELECT s.*, e.from_id, e.from_alias, e.recording_path, e.transcript
FROM call_sessions s
JOIN call_events e ON e.session_id = s.id
ORDER BY s.time_start DESC, e.time_start ASC;
```

---

## File Layout

```
src/main/java/io/github/dsheirer/
└── calllog/
    ├── CallLogDatabase.java
    ├── CallLogRecord.java
    ├── CallEventRecord.java
    ├── CallLogWriter.java
    └── config/
        └── CallLogConfiguration.java
```

---

## Integration with 010 Phases

| 010 Phase | CallLogWriter Behavior |
|---|---|
| Phase 1 (CallSession + Calls tab) | CallLogWriter subscribes to P25CallSessionManager, writes on COMPLETE |
| Phase 2 (Audio integration) | No change — audio ownership moves to session, but DB write is the same |
| Phase 3 (Recording integration) | Recording paths populated on CallSession, automatically included in DB write |
| Phase 4 (Traffic channel consolidation) | No change — session lifecycle is the same |

---

## Migration Notes

- Existing CSV event logs (`event_logs/` directory) continue to work unchanged
- `DecodeEventLogger` is untouched — CSV logs are separate from the call log DB
- The SQLite DB is a new, additional persistence layer
- Historical CSV data is not migrated — the DB starts fresh when enabled
- Phase 006c (recording organizer) can backfill `recording_path` for historical recordings
