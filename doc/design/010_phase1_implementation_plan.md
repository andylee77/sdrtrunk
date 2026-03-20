# 010 Phase 1 + 006a — Implementation Plan

## Date
2026-03-20

## Status
Approved — Implementation In Progress

## Scope
- **010 Phase 1:** Call Session Foundation + Calls Tab
- **006a:** Call Log SQLite Database

These are tightly coupled (006a depends on 010 CallSession objects) and implemented together.

---

## Design Decisions (Updated from Review)

### 1. Flat Per-Talker Rows (Not Expandable Sessions)
Each radio ID change creates a **new flat row** in the Calls tab. There is no expand/collapse —
every talker segment is its own visible row. Rows carry a `sessionId` linking them to their
parent call session for grouping, but the display is a simple flat table.

**Why:** Simpler UI, each row = one talker's transmission with its own recording/transcript.
Matches the per-talker event model from the design doc.

### 2. Patch Group Identification Without Explicit Patch Data
When the same radio ID is seen transmitting to different talkgroups around the same time,
AND those events are on the same frequency/timeslot (or close in time), the session manager
should recognize these as the **same call** if any of these conditions are true:

- The event type is `CALL_PATCH_GROUP` or `CALL_PATCH_GROUP_ENCRYPTED`
- The talkgroup is a `PatchGroupIdentifier` (supergroup)
- The same FROM radio appears on different TO talkgroups within the gap tolerance window
  on the same frequency — this implies a patch group even without explicit Add commands

The session manager maintains a **radio-to-session affinity map**: when a radio ID is actively
transmitting in a session, subsequent events from that same radio on different talkgroups
(same frequency/timeslot) are linked to the same session rather than creating a new one.

### 3. Session Structure
A `P25CallSession` is the logical grouping unit (channel grant to termination). Within it,
`P25CallSessionEvent` objects represent each per-talker segment. The Calls tab shows one
row per `P25CallSessionEvent` (flat), with a session ID column for grouping context.

---

## Architecture

### Current Flow (Unchanged)
```
P25 Messages → P25P1DecoderState → P25TrafficChannelManager.broadcast(DecodeEvent)
                                          │
                                          └──→ DecodeEventModel → Events tab
```

### New Flow (Additive Intercept)
```
P25 Messages → P25P1DecoderState → P25TrafficChannelManager.broadcast(DecodeEvent)
                                          │
                                          ├──→ DecodeEventModel → Events tab (UNCHANGED)
                                          │
                                          └──→ P25CallSessionManager.onDecodeEvent(DecodeEvent)
                                                     │
                                                     ├──→ CallSessionModel → Calls tab (NEW)
                                                     │
                                                     └──→ CallLogWriter → SQLite DB (NEW)
```

---

## New Files (12)

### Core Session Classes (`module.decode.p25.session`)

#### 1. `CallState.java`
**Path:** `src/main/java/io/github/dsheirer/module/decode/p25/session/CallState.java`
**Type:** Enum
**Values:** `PENDING`, `ACTIVE`, `ENDING`, `COMPLETE`

#### 2. `P25CallSession.java`
**Path:** `src/main/java/io/github/dsheirer/module/decode/p25/session/P25CallSession.java`
**Type:** Class — represents a complete P25 call (channel grant to termination)

**Fields:**
```java
private final long mSessionId;                    // unique auto-incrementing ID
private long mFrequency;                          // downlink frequency
private int mTimeslot;                            // timeslot number
private CallState mState = CallState.PENDING;     // lifecycle state
private Identifier mTalkgroup;                    // primary TG (may be PatchGroupIdentifier)
private PatchGroupIdentifier mPatchGroup;         // enriched patch group data
private final MutableIdentifierCollection mIdentifiers; // all identifiers seen
private final List<P25CallSessionEvent> mEvents = new ArrayList<>();  // per-talker events
private EncryptionKeyIdentifier mEncryption;
private IChannelDescriptor mChannelDescriptor;
private ServiceOptions mServiceOptions;
private DecodeEventType mEventType;
private String mDetails;
private long mCallStart;                          // first event timestamp
private long mCallEnd;                            // last activity timestamp
private long mLastActivityTimestamp;              // for gap detection
private boolean mDuplicate;                       // marked as duplicate
private final Set<Integer> mSeenTalkgroups = new HashSet<>();  // all TGs seen (for patch detection)
private final Map<String, Long> mRadioAffinityMap = new HashMap<>(); // radio→last seen time
```

**Key Methods:**
```java
public long getSessionId()
public CallState getState()
public void setState(CallState state)
public long getDuration()
public int getTalkerCount()                       // distinct FROM radios
public int getEventCount()
public List<P25CallSessionEvent> getEvents()
public P25CallSessionEvent getCurrentEvent()      // most recent per-talker event
public void addEvent(P25CallSessionEvent event)
public void updateActivity(long timestamp)
public void enrichPatchGroup(PatchGroupIdentifier pg)
public boolean isRadioAffiliated(String radioId, long timestamp, long toleranceMs)
public void addSeenTalkgroup(int talkgroupId)
public Set<Integer> getSeenTalkgroups()
public boolean isMatch(long frequency, int timeslot, Identifier talkgroup, Identifier fromRadio, long timestamp)
```

The `isMatch()` method implements the matching logic:
1. Same frequency + timeslot + same talkgroup → match
2. Same frequency + timeslot + different talkgroup BUT same FROM radio within tolerance → patch match
3. Same frequency + timeslot + talkgroup is in seenTalkgroups set → patch match
4. PatchGroupIdentifier supergroup or member match → match

#### 3. `P25CallSessionEvent.java`
**Path:** `src/main/java/io/github/dsheirer/module/decode/p25/session/P25CallSessionEvent.java`
**Type:** Class — one per-talker segment (one row in Calls tab)

**Fields:**
```java
private final long mSessionId;       // parent session ID
private long mTimeStart;
private long mTimeEnd;
private DecodeEventType mEventType;
private Identifier mFromRadio;       // FROM radio for this segment
private Identifier mToTalkgroup;     // TO talkgroup
private String mFromAlias;           // resolved alias at creation time
private String mToAlias;             // resolved alias at creation time
private IdentifierCollection mIdentifierCollection;
private IChannelDescriptor mChannelDescriptor;
private ServiceOptions mServiceOptions;
private String mDetails;
private long mFrequency;
private int mTimeslot;
// Future fields (populated by later phases):
private String mRecordingPath;       // 006c
private String mTranscript;          // 006d
```

**Key Methods:**
```java
public long getSessionId()
public long getDuration()
public void updateEnd(long timestamp)
// Standard getters/setters
```

#### 4. `P25CallSessionListener.java`
**Path:** `src/main/java/io/github/dsheirer/module/decode/p25/session/P25CallSessionListener.java`
**Type:** Interface

```java
void onSessionCreated(P25CallSession session);
void onSessionEventAdded(P25CallSession session, P25CallSessionEvent event);
void onSessionEventUpdated(P25CallSession session, P25CallSessionEvent event);
void onSessionComplete(P25CallSession session);
```

#### 5. `P25CallSessionManager.java`
**Path:** `src/main/java/io/github/dsheirer/module/decode/p25/session/P25CallSessionManager.java`
**Type:** Class — central authority for call management

**Fields:**
```java
private final Map<String, P25CallSession> mActiveSessions = new ConcurrentHashMap<>();  // key: "freq:ts"
private final Map<String, P25CallSession> mEndingSessions = new ConcurrentHashMap<>();
private final List<P25CallSessionListener> mListeners = new CopyOnWriteArrayList<>();
private final AtomicLong mSessionIdCounter = new AtomicLong(1);
private long mGapToleranceMs = 3000;
private ScheduledExecutorService mTimerService;
private PatchGroupManager mPatchGroupManager;
private static final Logger mLog = LoggerFactory.getLogger(P25CallSessionManager.class);
```

**Key Methods:**
```java
// Primary entry point — called from P25TrafficChannelManager.broadcast()
public void onDecodeEvent(DecodeEvent event, long timestamp)

// Session lifecycle
private P25CallSession findOrCreateSession(DecodeEvent event, long timestamp)
private P25CallSession matchSession(long frequency, int timeslot, Identifier talkgroup,
                                     Identifier fromRadio, long timestamp)
private String sessionKey(long frequency, int timeslot)
private void transitionToEnding(P25CallSession session)
private void finalizeSession(P25CallSession session)

// Patch group support
public void setPatchGroupManager(PatchGroupManager pgm)
public void onPatchGroupUpdate(PatchGroupIdentifier patchGroup)

// Timer
public void start()
public void stop()
private void cleanupEndingSessions()

// Listeners
public void addListener(P25CallSessionListener listener)
public void removeListener(P25CallSessionListener listener)
private void notifySessionCreated(P25CallSession session)
private void notifyEventAdded(P25CallSession session, P25CallSessionEvent event)
private void notifyEventUpdated(P25CallSession session, P25CallSessionEvent event)
private void notifySessionComplete(P25CallSession session)
```

**Event Processing Logic (`onDecodeEvent`):**
```
1. Extract: frequency, timeslot, TO identifier, FROM identifier, eventType, timestamp
2. Look up active session by sessionKey(frequency, timeslot)
3. If found:
   a. Check if same call (same TG or patch-related or same radio):
      - session.isMatch(freq, ts, talkgroup, fromRadio, timestamp) → true
      - If same FROM radio as current event → update current event duration
      - If different FROM radio → create new P25CallSessionEvent, add to session
   b. If not same call:
      - Transition current session to ENDING
      - Create new session
4. If not found in active:
   a. Check ENDING sessions for gap-tolerance resume:
      - Same TG within gap threshold → reactivate (ENDING → ACTIVE)
      - Same FROM radio within gap threshold on same freq → reactivate (patch detection)
   b. If no match → create new session + new event
5. Notify listeners
```

**Patch Group Detection (Without Explicit Patch Data):**
When a radio transmits to TG A, and then we see the same radio on the same channel
transmitting to TG B within the gap tolerance, the session manager treats this as
the same call session. The `mSeenTalkgroups` set on the session accumulates all TGs
seen, and the `mRadioAffinityMap` tracks which radios are affiliated with this session.

### UI Classes (`module.decode.p25.session.ui`)

#### 6. `CallSessionModel.java`
**Path:** `src/main/java/io/github/dsheirer/module/decode/p25/session/ui/CallSessionModel.java`
**Type:** Class extends `AbstractTableModel` implements `P25CallSessionListener`

**Columns:**
| Index | Name | Type | Source |
|-------|------|------|--------|
| 0 | Time | Long (epoch ms) | event.getTimeStart() |
| 1 | Duration | Long (ms) | event.getDuration() |
| 2 | Event | String | event.getEventType().getLabel() |
| 3 | From | IdentifierCollection | FROM radio ID |
| 4 | From Alias | IdentifierCollection | FROM radio alias |
| 5 | To | IdentifierCollection | TO talkgroup |
| 6 | To Alias | IdentifierCollection | TO talkgroup alias |
| 7 | Patch Group | IdentifierCollection | Patch members (if any) |
| 8 | Channel | String | Channel descriptor |
| 9 | Frequency | IChannelDescriptor | Formatted frequency |
| 10 | Session | Long | Session ID (for grouping) |
| 11 | Details | String | Event details |

**Behavior:**
- `onSessionCreated(session)` → no-op (session has no events yet at creation)
- `onSessionEventAdded(session, event)` → insert new row at top, `fireTableRowsInserted(0, 0)`
- `onSessionEventUpdated(session, event)` → find row, `fireTableRowsUpdated(row, row)`
- `onSessionComplete(session)` → no visible change (events already displayed)
- Maximum 500 events held in memory (oldest removed when exceeded)
- Thread-safe: all mutations via `EventQueue.invokeLater()`

#### 7. `CallSessionPanel.java`
**Path:** `src/main/java/io/github/dsheirer/module/decode/p25/session/ui/CallSessionPanel.java`
**Type:** Class extends `JPanel` implements `Listener<ProcessingChain>`

**Layout:** MigLayout with JTable in JScrollPane.
**Cell Renderers:** Reuse renderer patterns from DecodeEventPanel (Timestamp, Duration, Frequency, Identifier, Alias, PatchGroup).

**ProcessingChain wiring:**
```java
public void receive(ProcessingChain processingChain) {
    // Unsubscribe from previous session manager
    if (mCurrentSessionManager != null) {
        mCurrentSessionManager.removeListener(mCallSessionModel);
    }
    // Find P25CallSessionManager in the new chain
    if (processingChain != null) {
        for (Module module : processingChain.getModules()) {
            if (module instanceof P25TrafficChannelManager tcm) {
                mCurrentSessionManager = tcm.getCallSessionManager();
                if (mCurrentSessionManager != null) {
                    mCurrentSessionManager.addListener(mCallSessionModel);
                }
            }
        }
    }
}
```

### Database Classes (`calllog`)

#### 8. `CallLogDatabase.java`
**Path:** `src/main/java/io/github/dsheirer/calllog/CallLogDatabase.java`

Manages SQLite connection. Creates tables on init. Provides transactional insert.

**Schema:** (from 006a design doc)
- `call_sessions` table — one row per P25CallSession
- `call_events` table — one row per P25CallSessionEvent (per-talker)
- Indexes on time, talkgroup, system, session_id

**Key Methods:**
```java
public CallLogDatabase(Path databasePath)
public void initialize()                    // CREATE TABLE IF NOT EXISTS
public long insertSession(Connection conn, CallLogRecord session)
public void insertEvent(Connection conn, CallEventRecord event)
public void insertSessionWithEvents(CallLogRecord session, List<CallEventRecord> events)
public long getSessionCount()
public void close()
```

#### 9. `CallLogRecord.java`
**Path:** `src/main/java/io/github/dsheirer/calllog/CallLogRecord.java`

POJO mapping to `call_sessions` table. Fields from 006a schema:
- timeStart, timeEnd, durationMs, talkgroupId, talkgroupAlias, patchGroupId,
  patchGroupMembers, eventType, frequency, timeslot, channelDescriptor,
  talkerCount, encrypted, duplicate, system, site, channelName, details,
  serviceOptions

#### 10. `CallEventRecord.java`
**Path:** `src/main/java/io/github/dsheirer/calllog/CallEventRecord.java`

POJO mapping to `call_events` table. Fields:
- sessionId, timeStart, timeEnd, durationMs, eventType, fromId, fromAlias,
  toId, toAlias, details, recordingPath, transcript

#### 11. `CallLogWriter.java`
**Path:** `src/main/java/io/github/dsheirer/calllog/CallLogWriter.java`

Implements `P25CallSessionListener`. On `onSessionComplete()`:
1. Convert `P25CallSession` → `CallLogRecord`
2. Convert each `P25CallSessionEvent` → `CallEventRecord`
3. Call `mDatabase.insertSessionWithEvents(record, events)` (single transaction)

#### 12. `CallLogConfiguration.java`
**Path:** `src/main/java/io/github/dsheirer/calllog/CallLogConfiguration.java`

Simple POJO: `enabled` (boolean), `databasePath` (String, auto-generated from system name if null).

---

## Modified Files (4)

### 1. `PatchGroupManager.java`
**Path:** `src/main/java/io/github/dsheirer/identifier/patch/PatchGroupManager.java`

**Changes:**
1. Add `PatchGroupUpdateListener` interface:
   ```java
   public interface PatchGroupUpdateListener {
       void onPatchGroupAdded(PatchGroupIdentifier patchGroup);
   }
   ```
2. Add listener list field and add/remove methods
3. In `addPatchGroup()`: after successful add, notify listeners
4. **Bug fix** in `update()` PATCH_GROUP case: remove the `mPatchGroupTrackerMap.remove(id)`
   calls that immediately follow `put()` calls (lines ~185-195)

### 2. `P25TrafficChannelManager.java`
**Path:** `src/main/java/io/github/dsheirer/module/decode/p25/P25TrafficChannelManager.java`

**Changes:**
1. Add field: `private P25CallSessionManager mCallSessionManager;`
2. In constructor (end): `mCallSessionManager = new P25CallSessionManager();`
3. Add getter: `public P25CallSessionManager getCallSessionManager()`
4. In `broadcast(DecodeEvent)` — append after existing logic:
   ```java
   if (mCallSessionManager != null) {
       mCallSessionManager.onDecodeEvent(decodeEvent, System.currentTimeMillis());
   }
   ```

### 3. `NowPlayingPanel.java`
**Path:** `src/main/java/io/github/dsheirer/channel/metadata/NowPlayingPanel.java`

**Changes:**
1. Add import for `CallSessionPanel`
2. Add field: `private final CallSessionPanel mCallSessionPanel;`
3. In constructor: create `mCallSessionPanel`
4. In `getTabbedPane()`: insert `"Calls"` tab between `"Details"` and `"Events"`
5. In `init()`: register as processing chain listener

### 4. `build.gradle`
**Path:** `build.gradle`

**Change:** Add SQLite JDBC dependency:
```groovy
implementation 'org.xerial:sqlite-jdbc:3.47.2.0'
```

---

## Implementation Order

| Step | File(s) | Description |
|------|---------|-------------|
| 1 | `CallState.java` | Trivial enum |
| 2 | `P25CallSessionEvent.java` | Per-talker POJO |
| 3 | `P25CallSession.java` | Core session object |
| 4 | `P25CallSessionListener.java` | Listener interface |
| 5 | `P25CallSessionManager.java` | Central logic (~400 lines) |
| 6 | `PatchGroupManager.java` | Add listener + bug fix |
| 7 | `P25TrafficChannelManager.java` | Add session manager + intercept |
| 8 | `CallSessionModel.java` | Table model for Calls tab |
| 9 | `CallSessionPanel.java` | UI panel for Calls tab |
| 10 | `NowPlayingPanel.java` | Add Calls tab |
| 11 | `build.gradle` | Add sqlite-jdbc |
| 12 | `CallLogRecord.java`, `CallEventRecord.java` | DB POJOs |
| 13 | `CallLogDatabase.java` | SQLite manager |
| 14 | `CallLogWriter.java` | Session→DB writer |
| 15 | `CallLogConfiguration.java` | Config POJO |
| 16 | Wire CallLogWriter into P25CallSessionManager | Integration |

Build verification after steps 7, 10, and 16.

---

## Directory Structure

```
src/main/java/io/github/dsheirer/
├── module/decode/p25/session/
│   ├── CallState.java
│   ├── P25CallSession.java
│   ├── P25CallSessionEvent.java
│   ├── P25CallSessionListener.java
│   ├── P25CallSessionManager.java
│   └── ui/
│       ├── CallSessionModel.java
│       └── CallSessionPanel.java
├── calllog/
│   ├── CallLogDatabase.java
│   ├── CallLogRecord.java
│   ├── CallEventRecord.java
│   ├── CallLogWriter.java
│   └── CallLogConfiguration.java
```

---

## Testing Strategy

1. **Compile test:** `gradlew compileJava` after each major step group
2. **Runtime test:** `gradlew run` → start P25 channel → verify:
   - Calls tab appears between Details and Events
   - Events still populate in Events tab (unchanged)
   - Calls tab populates with per-talker rows
   - Same radio on different TGs within tolerance = same session ID
   - Patch group calls show patch members in Patch Group column
   - Gap tolerance: brief silence doesn't create new session
   - SQLite DB file created in `call_logs/` directory
   - DB contains session + event rows after calls complete
3. **Edge cases:**
   - No P25 channel selected → Calls tab is empty (no crash)
   - Switch between channels → Calls tab updates
   - Multiple simultaneous calls on different frequencies
