# 010 — Phase 2: Calls Tab UI + Call Log SQLite Database

## Summary

Implements phase 2 of the call session management system (design doc 010):

1. **Calls Tab UI** — New "Calls" tab in the NowPlaying panel showing real-time per-talker
   call session events in a JTable. Displays time, duration, event type, from/to identifiers,
   aliases, patch groups, channel, frequency, encrypted indicator, and details.

2. **Call Log SQLite Database** — Persists completed call sessions and their per-talker events
   to a SQLite database at `~/SDRTrunk/call_logs/{system}_calls.db`. Schema follows design
   doc 006a with two tables: `call_sessions` and `call_events` with full indexing.

3. **Historical Data Loading** — On channel selection, queries the database for recent sessions
   (configurable 1h–72h, default 4h) and populates them below live events. Historical records
   show session-level data (talkgroup, timing, channel, encryption status).

4. **Display Filters** — Toolbar with three filter checkboxes:
   - **Hide Encrypted** — hides calls with encrypted event types
   - **Hide Data** — hides data channel calls
   - **Hide Ignored** — hides calls marked IGNORED (unmonitored, encrypted, etc.)
   
   Filters use JTable RowFilter for dynamic toggling without removing data from the model.

## New Files

| File | Purpose |
|------|---------|
| `calllog/CallLogRecord.java` | POJO for `call_sessions` table row |
| `calllog/CallEventRecord.java` | POJO for `call_events` table row |
| `calllog/CallLogDatabase.java` | SQLite JDBC database manager (schema, insert, query, WAL mode) |
| `calllog/CallLogWriter.java` | CallSessionListener → converts sessions to records, writes to DB |
| `session/ui/CallSessionModel.java` | Swing AbstractTableModel for dual-source display (live + historical) |
| `session/ui/CallSessionPanel.java` | JPanel with JTable, filter toolbar, history loading, cell renderers |

## Modified Files

| File | Change |
|------|--------|
| `build.gradle` | Added `org.xerial:sqlite-jdbc:3.47.2.0` dependency |
| `NowPlayingPanel.java` | Added "Calls" tab between Details and Events |
| `P25TrafficChannelManager.java` | Creates CallLogWriter, registers as listener, start/stop lifecycle, exposes getCallLogWriter() |

## Database Schema

Two tables with foreign key relationship:
- `call_sessions` — one row per channel grant session (talkgroup, timing, channel, metadata)
- `call_events` — one row per talker segment within a session (from/to, timing, details)

Query method: `queryRecentSessions(sinceTimestamp, maxRows)` — returns sessions newer than
the timestamp, ordered newest first, with configurable limit.

Includes future-ready columns for transcription, LLM summary, incident tracking, and
recording organization that will be populated in later phases.

## Data Flow

```
DecodeEvent → P25CallSessionManager → CallSessionListener callbacks
                                           ↓                    ↓
                                    CallSessionModel     CallLogWriter
                                    (UI table model)     (SQLite persistence)
                                           ↓                    ↓
                                    Calls tab JTable    ~/SDRTrunk/call_logs/*.db
                                           ↑
                                    History load on channel select
                                    (queryRecentSessions → loadHistory)
```

## Filter Architecture

```
CallSessionModel (raw data, both live + historical)
    ↓
TableRowSorter with RowFilter
    ↓
JTable (filtered view)

Filter checks model methods: isEncrypted(), isDataCall(), isUnmonitored()
```

## Alias Resolution Pattern

Historical records from the database use the same alias resolution pattern as the Events tab:

1. `CallLogRecord.getIdentifierCollection(aliasListName)` builds a **synthetic IdentifierCollection**
   containing `APCO25Talkgroup` (TO), `APCO25RadioIdentifier` (FROM), and
   `AliasListConfigurationIdentifier` for alias list lookup.
2. `CallSessionModel` returns this IC for all identity columns (FROM_ID, FROM_ALIAS, TO_ID, TO_ALIAS).
3. The renderer calls `mAliasModel.getAliasList(ic)` at paint time — exactly like the Events tab.
4. Alias changes in the config are reflected immediately in historical records (no cache staleness).

The alias list name is obtained from `P25TrafficChannelManager.getAliasListName()` (delegates to
the parent channel config) and passed to the model via `CallSessionModel.setAliasListName()` on
channel selection.

## Testing

- `gradlew compileJava` — BUILD SUCCESSFUL
- `gradlew run` — Calls tab populates, SQLite DB created, history loads on channel selection
- No regressions to existing Events tab or audio routing

## Known Issue: Cross-Frequency Patch Calls

Patch group calls that span multiple traffic channels (each member TG gets its own frequency)
create separate sessions in the Calls tab. The session manager currently observes events AFTER
P25TrafficChannelManager has already allocated channels, so it cannot prevent duplicate channel
allocation.

**Example:** Radio 00059201 on patch P:00149 with members 01085, 01087, 01089 → three ACTIVE
traffic channels in NowPlaying, three separate session rows in the Calls tab.

**Fix:** Phase 3 — Session manager becomes the authority between control channel grants and
traffic channel allocation. See `doc/design/010_phase3_implementation_plan.md`.

## Bug Fix: isSameTalker null→non-null FROM

Fixed `CallSession.isSameTalker()` to handle the common P25 pattern where the initial channel
grant has no FROM radio (null) and a subsequent update identifies the radio. Previously this
created a duplicate per-talker event row. Now: null→non-null is treated as same talker, and
the existing event's FROM radio is updated via `setFromRadio()`. Only when BOTH FROM radios
are non-null AND different does a new per-talker event get created.
