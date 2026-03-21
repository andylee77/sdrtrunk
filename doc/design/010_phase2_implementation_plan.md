# 010 Phase 2 — Implementation Plan (Phase 1b UI + 006a SQLite)

## Date
2026-03-20

## Status
Approved — Implementation Starting

## Scope
- **Phase 1b:** CallSessionModel (Swing TableModel) + CallSessionPanel + NowPlayingPanel "Calls" tab
- **006a:** SQLite Call Log Database — build.gradle dependency, CallLogDatabase, CallLogRecord, CallEventRecord, CallLogWriter

These are tightly coupled (006a writes the same CallSession objects that the UI displays) and implemented together.

---

## Prerequisites
- **Phase 1a COMPLETE:** P25CallSessionManager + protocol-agnostic foundation (6 files, ~2100 lines)
- **Refactored to protocol-agnostic:** CallState, CallSession, CallSessionEvent, CallSessionListener now in `module.decode.session` (not P25-specific)
- **P25CallSessionManager** remains in `module.decode.p25.session`, uses generic types
- **P25TrafficChannelManager** already creates session manager, feeds events, manages lifecycle

---

## Implementation Steps

### Step 1: CallSessionModel.java (NEW ~180 lines)
**Path:** `src/main/java/io/github/dsheirer/module/decode/session/ui/CallSessionModel.java`

Swing `AbstractTableModel` implementing `CallSessionListener`. Maintains a `LinkedList<CallSessionEvent>` (flat per-talker rows, newest first, max 500).

**Columns (12):**
| # | Header | Type | Source |
|---|--------|------|--------|
| 0 | Time | Long | event.getTimeStart() |
| 1 | Duration | Long | event.getDuration() |
| 2 | Event | String | event.getEventType().getLabel() |
| 3 | From | IdentifierCollection | event.getIdentifierCollection() — FROM role |
| 4 | Alias | IdentifierCollection | event.getIdentifierCollection() — FROM alias |
| 5 | To | IdentifierCollection | event.getIdentifierCollection() — TO role |
| 6 | Alias | IdentifierCollection | event.getIdentifierCollection() — TO alias |
| 7 | Patch Group | IdentifierCollection | event.getIdentifierCollection() — PatchGroup |
| 8 | Channel | String | channelDescriptor + timeslot |
| 9 | Frequency | IChannelDescriptor | channelDescriptor |
| 10 | Session | Long | event.getSessionId() |
| 11 | Details | String | event.getDetails() |

**Key behavior:**
- `onSessionCreated()` → no-op (no events yet at creation)
- `onSessionEventAdded()` → insert at top, `fireTableRowsInserted(0,0)`; trim if >500
- `onSessionEventUpdated()` → find row by identity, `fireTableRowsUpdated(row,row)`
- `onSessionComplete()` → no visible change (events already displayed)
- All mutations wrapped in `EventQueue.invokeLater()`

### Step 2: CallSessionPanel.java (NEW ~250 lines)
**Path:** `src/main/java/io/github/dsheirer/module/decode/session/ui/CallSessionPanel.java`

JPanel with MigLayout containing JTable in JScrollPane. Implements `Listener<ProcessingChain>`.

ProcessingChain wiring: walks modules to find `P25TrafficChannelManager`, gets session manager, registers model as listener. Cell renderers duplicated from DecodeEventPanel inner classes (small, keeps change set self-contained).

### Step 3: NowPlayingPanel.java (MODIFY ~15 lines)
Add `CallSessionPanel` field, create in constructor, add "Calls" tab between "Details" and "Events", register as processing chain selection listener.

**Tab order:** Details | **Calls** | Events | Messages | Channel

### Step 4: Build verification checkpoint
`gradlew compileJava` — verify UI compiles cleanly before moving to SQLite.

### Step 5: build.gradle (MODIFY +1 line)
```groovy
implementation 'org.xerial:sqlite-jdbc:3.47.2.0'
```

### Step 6: CallLogRecord.java (NEW ~180 lines)
**Path:** `src/main/java/io/github/dsheirer/calllog/CallLogRecord.java`

POJO mapping to `call_sessions` table. Fields from 006a schema.

### Step 7: CallEventRecord.java (NEW ~120 lines)
**Path:** `src/main/java/io/github/dsheirer/calllog/CallEventRecord.java`

POJO mapping to `call_events` table.

### Step 8: CallLogDatabase.java (NEW ~250 lines)
**Path:** `src/main/java/io/github/dsheirer/calllog/CallLogDatabase.java`

SQLite database manager using `java.sql.*` (JDBC) with `org.xerial:sqlite-jdbc` driver.
- CREATE TABLE IF NOT EXISTS for both tables + indexes
- `insertSessionWithEvents()` — single transaction (atomicity)
- Lazy connection management

### Step 9: CallLogWriter.java (NEW ~200 lines)
**Path:** `src/main/java/io/github/dsheirer/calllog/CallLogWriter.java`

Implements `CallSessionListener`. Converts CallSession → CallLogRecord + CallEventRecord list, writes on COMPLETE.

### Step 10: P25TrafficChannelManager.java (MODIFY ~20 lines)
Wire CallLogWriter: create in constructor, register as listener, start/stop with lifecycle. DB path from `{user.home}/SDRTrunk/call_logs/{system}_calls.db`.

### Step 11: Final build verification + runtime test.

### Step 12: Documentation (change doc, changelog, commit).

---

## File Summary

| # | File | Type | Lines (est) |
|---|------|------|-------------|
| 1 | `session/ui/CallSessionModel.java` | NEW | ~180 |
| 2 | `session/ui/CallSessionPanel.java` | NEW | ~250 |
| 3 | `channel/metadata/NowPlayingPanel.java` | MODIFY | ~15 |
| 4 | `build.gradle` | MODIFY | +1 |
| 5 | `calllog/CallLogRecord.java` | NEW | ~180 |
| 6 | `calllog/CallEventRecord.java` | NEW | ~120 |
| 7 | `calllog/CallLogDatabase.java` | NEW | ~250 |
| 8 | `calllog/CallLogWriter.java` | NEW | ~200 |
| 9 | `P25TrafficChannelManager.java` | MODIFY | ~20 |

**Total:** ~7 new files, ~2 modified files, ~1200 lines of code

---

## Design Decisions

1. **Cell renderers duplicated, not shared** — DecodeEventPanel renderers are inner classes. Creating equivalent inner classes in CallSessionPanel keeps change set self-contained without touching upstream code.

2. **DB path auto-generated from system name** — `{user.home}/SDRTrunk/call_logs/{system}_calls.db`. Directory auto-created. No config UI needed yet. Falls back to `"default"` if system name is null.

3. **CallLogWriter lives in P25TrafficChannelManager** — Needs access to `mParentChannel` for system/site/channel name metadata.

4. **Single SQLite connection** — SQLite is single-writer. Connection opened in start(), closed in stop().

5. **Write only on COMPLETE** — No debounce needed. One write per session. Atomic transaction for session + all events.

---

## Directory Structure

```
src/main/java/io/github/dsheirer/
├── module/decode/session/              ← Protocol-agnostic foundation
│   ├── CallState.java                  (Phase 1a - done, refactored)
│   ├── CallSession.java               (Phase 1a - done, refactored from P25CallSession)
│   ├── CallSessionEvent.java          (Phase 1a - done, refactored from P25CallSessionEvent)
│   ├── CallSessionListener.java       (Phase 1a - done, refactored from P25CallSessionListener)
│   └── ui/
│       ├── CallSessionModel.java      ← NEW (Phase 2)
│       └── CallSessionPanel.java      ← NEW (Phase 2)
├── module/decode/p25/session/          ← P25-specific session manager
│   └── P25CallSessionManager.java     (Phase 1a - done, imports from decode.session)
├── calllog/
│   ├── CallLogDatabase.java           ← NEW (Phase 2)
│   ├── CallLogRecord.java             ← NEW (Phase 2)
│   ├── CallEventRecord.java           ← NEW (Phase 2)
│   └── CallLogWriter.java             ← NEW (Phase 2)
```
