# 010 Phase 3 — Call Manager as Control Channel Authority

## Date
2026-03-20

## Status
Design — Ready for Implementation

## Scope
- **010 Phase 3:** P25CallSessionManager becomes the **sole recipient** of all control channel
  traffic, sitting between the control channel decoder and the traffic channel manager
- **Call Manager is the decision point** — it decides when to open traffic channels, not the
  traffic channel manager
- **Traffic Manager demoted** to a channel pool manager — it opens/closes channels on demand
- **Cross-frequency patch call consolidation** built into the call manager's grant processing

This is a fundamental architectural change: the call manager is no longer an observer that
receives events after the fact. It is the authority that processes control channel messages
first and decides what happens next.

---

## Problem Statement

### Current Flow (Phase 1/2): Call Manager is a Passive Observer

```
P25P1DecoderState (control channel decoder)
    │
    │  processControlTrafficGrant()
    ▼
P25TrafficChannelManager                          ← MAKES ALL DECISIONS
    │
    ├── checks mAllocatedTrafficChannelMap
    ├── checks staleness, encrypted, unmonitored
    ├── allocates traffic channel from pool
    ├── creates P25TrafficChannelEventTracker
    ├── requests channel start via event bus
    ├── broadcasts DecodeEvent
    │       │
    │       ▼
    │   P25CallSessionManager.onDecodeEvent()     ← PASSIVE OBSERVER (too late)
    │       └── creates/updates CallSession
    │       └── feeds Calls tab + SQLite
    │
    └── DecodeEventModel (Events tab)
```

**Problems with this flow:**

1. **Call manager sees events AFTER traffic channels are already allocated.** By the time
   `onDecodeEvent()` fires, the traffic channel is open, audio is flowing, recording has
   started. The call manager can't prevent duplicate channels or make informed decisions.

2. **Traffic manager doesn't understand calls.** It processes each channel grant independently.
   When a patch group call produces 4 grants for the same call on different frequencies,
   it opens 4 traffic channels because it has no concept of "these are all the same call."

3. **Control channel info is fragmented.** The traffic manager gets one grant at a time.
   It can't accumulate TO/FROM/encryption/patch-group info across multiple messages before
   deciding what to do. Each grant triggers immediate allocation.

4. **Filtering logic is scattered.** Encrypted-call filtering, unmonitored-call filtering,
   and patch-duplicate detection are all bolted onto the traffic manager's grant processing
   methods. They should be centralized in the call manager where the full call context exists.

### What We Want: Call Manager as the Authority

The call manager should receive ALL control channel traffic first. It accumulates information,
makes decisions about calls, and ONLY tells the traffic manager to open a channel when it
decides a channel is needed. The traffic manager becomes a simple channel pool — it opens
and closes channels on command, nothing more.

---

## Architecture Change

### New Flow (Phase 3): Call Manager is the Authority

```
P25P1DecoderState (control channel decoder)
    │
    │  ALL control channel grants/updates/terminations
    ▼
P25CallSessionManager                             ← AUTHORITY — ALL DECISIONS HERE
    │
    ├── receives channel grants, updates, terminations
    ├── creates/updates CallSession objects
    ├── accumulates identifiers (TO, FROM, encryption, patch group)
    ├── detects patch duplicates across frequencies
    ├── checks encrypted/unmonitored filters
    ├── manages call lifecycle (PENDING → ACTIVE → ENDING → COMPLETE)
    │
    ├── DECISION: "This call needs a traffic channel"
    │       │
    │       ▼
    │   P25TrafficChannelManager.allocateTrafficChannel(freq, timeslot, ic)
    │       └── takes channel from pool
    │       └── requests channel start via event bus
    │       └── returns success/failure
    │
    ├── DECISION: "This is a patch duplicate — no traffic channel needed"
    │       └── enriches primary session, done
    │
    ├── DECISION: "This call is encrypted/unmonitored — skip"
    │       └── updates session tracking only, no traffic channel
    │
    ├── notifies → CallSessionModel (Calls tab)
    ├── notifies → CallLogWriter (SQLite)
    ├── notifies → DecodeEventModel (Events tab, for compatibility)
    │
    └── DECISION: "Traffic channel should be released"
            │
            ▼
        P25TrafficChannelManager.releaseTrafficChannel(freq, timeslot)
```

### Key Relationships

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        CONTROL CHANNEL DECODER                              │
│                     (P25P1DecoderState on control channel)                   │
│                                                                             │
│  Decodes TSBK/AMBTC messages from the P25 control channel.                 │
│  Produces: channel grants, channel updates, channel grant updates,          │
│            group regroup (patch) commands, call terminations                 │
│                                                                             │
│  Currently calls: processControlTrafficGrant() → P25TrafficChannelManager   │
│  Phase 3 change: calls → P25CallSessionManager instead                      │
└────────────────────────────────┬────────────────────────────────────────────┘
                                 │
                    ALL control channel messages
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        CALL SESSION MANAGER                                 │
│                     (P25CallSessionManager)                                 │
│                                                                             │
│  THE SINGLE AUTHORITY for all call-related decisions.                       │
│                                                                             │
│  Responsibilities:                                                          │
│  1. Receive ALL control channel grants/updates/terminations                 │
│  2. Create and manage CallSession objects (one per logical call)            │
│  3. Accumulate identifiers across multiple messages for same call           │
│  4. Detect patch group duplicates BEFORE any traffic channel is opened      │
│  5. Apply filters (encrypted, unmonitored) BEFORE traffic channel opens     │
│  6. Decide WHEN to request a traffic channel from the pool                  │
│  7. Decide WHEN to release a traffic channel back to the pool               │
│  8. Track call lifecycle (PENDING → ACTIVE → ENDING → COMPLETE)             │
│  9. Notify downstream consumers (Calls tab, Events tab, SQLite, audio)      │
│                                                                             │
│  Owns:                                                                      │
│  - Map of active CallSessions indexed by (frequency, timeslot)              │
│  - Map of ending CallSessions (grace period before finalization)            │
│  - Radio affinity map (radio → session mapping for patch detection)         │
│  - Reference to PatchGroupManager for enrichment                            │
│                                                                             │
│  Does NOT own:                                                              │
│  - Traffic channel pool (that stays in P25TrafficChannelManager)            │
│  - Audio modules, recording modules (those attach to traffic channels)      │
│  - Channel start/stop via event bus (traffic manager handles that)          │
└──────────┬──────────────────────────────────────┬───────────────────────────┘
           │                                      │
           │ "allocate channel at freq X"         │ "release channel at freq X"
           │ (only when call manager decides)     │ (when call ends)
           ▼                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        TRAFFIC CHANNEL MANAGER                              │
│                     (P25TrafficChannelManager)                               │
│                                                                             │
│  DEMOTED to a channel pool manager. No longer makes call decisions.         │
│                                                                             │
│  Responsibilities (Phase 3):                                                │
│  1. Maintain pool of available Phase 1 and Phase 2 traffic channels         │
│  2. Allocate a channel from pool when call manager requests it              │
│  3. Request channel start via ChannelProcessingManager event bus            │
│  4. Release channel back to pool when call manager says call is done        │
│  5. Handle traffic channel teardown                                         │
│  6. Continue to handle traffic-channel-side messages (HDU, LDU, TLC, etc.)  │
│     that arrive FROM an already-open traffic channel                        │
│                                                                             │
│  NO LONGER does:                                                            │
│  - Process control channel grants (that's the call manager's job)           │
│  - Create P25TrafficChannelEventTracker from grants (call manager tracks)   │
│  - Make decisions about encrypted/unmonitored calls                         │
│  - Make decisions about duplicate channels                                  │
│  - Broadcast DecodeEvents from control channel grants                       │
│                                                                             │
│  Still does (unchanged):                                                    │
│  - processP1TrafficCurrentUser() — traffic channel reports current call     │
│  - processP1TrafficCallEnd() — traffic channel signals call termination     │
│  - Channel pool management (available queues, allocated map)                │
│  - Phase 2 scramble parameters                                              │
│  - Frequency band map                                                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

### How a Call is Tracked: End-to-End Flow

Here is the complete lifecycle of a P25 call from control channel grant to completion:

```
STEP 1: CHANNEL GRANT RECEIVED ON CONTROL CHANNEL
══════════════════════════════════════════════════
  P25P1DecoderState.processTSBK()
    → sees GROUP_VOICE_CHANNEL_GRANT for TG 01085 on freq 856.4625
    → calls P25CallSessionManager.processChannelGrant(channel, serviceOptions, ic, opcode, timestamp)

STEP 2: CALL MANAGER EVALUATES THE GRANT
═════════════════════════════════════════
  P25CallSessionManager.processChannelGrant():
    a) Extract identifiers: TO=TG01085, FROM=Radio1234567, encryption=none
    b) Check: is there an active session on this (freq, timeslot)?
       - YES + same TG → update existing session (duration, talker change)
       - YES + different TG → end old session, create new one
       - NO → proceed to create new session
    c) Check: is this a patch duplicate of a session on a DIFFERENT frequency?
       - Scan all active sessions for radio affinity or patch TG membership match
       - If YES → enrich primary session, DO NOT open traffic channel, DONE
    d) Check: is this encrypted and we're ignoring encrypted?
       - If YES → track in session (for logging) but DO NOT open traffic channel
    e) Check: is this unmonitored and we're ignoring unmonitored?
       - If YES → track in session (for logging) but DO NOT open traffic channel
    f) Create CallSession in PENDING state
       - Session accumulates all identifiers from the grant

STEP 3: CALL MANAGER DECIDES TO OPEN A TRAFFIC CHANNEL
═══════════════════════════════════════════════════════
  P25CallSessionManager (continuing from step 2f):
    g) Call passes all filters → request traffic channel
    h) Call P25TrafficChannelManager.allocateTrafficChannel(freq, timeslot, ic, timestamp)
       - Traffic manager takes channel from pool
       - Traffic manager calls requestTrafficChannelStart() via event bus
       - Returns the allocated Channel (or null if pool exhausted)
    i) If allocation succeeded:
       - Session moves to ACTIVE state
       - Session stores reference to allocated frequency
       - Notify listeners: session created → Calls tab, SQLite, Events tab
    j) If allocation failed (no channels available):
       - Session stays PENDING or moves to COMPLETE (pool exhausted)
       - Log warning

STEP 4: TRAFFIC CHANNEL COMES ALIVE
════════════════════════════════════
  ChannelProcessingManager starts the traffic channel's processing chain:
    - P25P1DecoderState (traffic) begins decoding
    - AudioModule begins producing audio
    - Traffic channel's P25P1DecoderState.receive() gets HDU, LDU1, LDU2, etc.

STEP 5: TRAFFIC CHANNEL REPORTS BACK
═════════════════════════════════════
  Traffic channel's P25P1DecoderState:
    - processHDU() → extracts encryption key, talkgroup confirmation
    - processLDU() → voice frames, talker alias, encryption
    - These update the traffic channel's local decoder state

  P25TrafficChannelManager still handles traffic-channel-side callbacks:
    - processP1TrafficCurrentUser() — traffic channel confirms current call identity
    - processP1TrafficCallEnd() — traffic channel signals TLC/TDULC
    → These are FORWARDED to P25CallSessionManager:
      - callManager.onTrafficChannelUpdate(freq, timeslot, ic, timestamp)
      - callManager.onTrafficChannelEnd(freq, timeslot, timestamp)

STEP 6: CONTROL CHANNEL SENDS UPDATES
══════════════════════════════════════
  Control channel continues sending CHANNEL_GRANT_UPDATE messages:
    → P25CallSessionManager.processChannelUpdate(channel, serviceOptions, ic, opcode, timestamp)
    → Call manager updates the session's duration, may add new identifiers
    → If the update reveals a talker change (different FROM radio):
      - Current CallSessionEvent is completed
      - New CallSessionEvent created for the new talker
      - Session continues (same logical call)

STEP 7: PATCH GROUP ENRICHMENT (ONGOING)
═════════════════════════════════════════
  Control channel decoder sees MOTOROLA_OSP_GROUP_REGROUP_ADD:
    → PatchGroupManager.addPatchGroups() stores member data
    → PatchGroupManager notifies P25CallSessionManager via listener
    → Call manager checks active sessions for matching supergroup ID
    → Matching sessions get enriched with patch member TGs
    → Calls tab updates in-place

  Control channel sends additional grants for patch member TGs:
    → Call manager receives grant for TG 01089 on freq 859.7125
    → Step 2c detects this as patch duplicate of TG 01085 session
    → Primary session enriched, NO traffic channel opened

STEP 8: CALL TERMINATION
═════════════════════════
  Either:
  a) Traffic channel signals TLC/TDULC:
     → P25TrafficChannelManager.processP1TrafficCallEnd()
     → Forwards to callManager.onTrafficChannelEnd(freq, timeslot, timestamp)
     → Session moves to ENDING state (grace period)
  
  b) Control channel grants same frequency to a different TG:
     → callManager.processChannelGrant() detects different TG on same freq
     → Old session moves to COMPLETE
     → New session created for new TG
  
  c) No activity for gap tolerance period (2-3 seconds):
     → Call manager's cleanup timer moves ENDING → COMPLETE

STEP 9: SESSION FINALIZED
══════════════════════════
  Session moves to COMPLETE:
    → Call manager tells traffic manager to release channel:
      P25TrafficChannelManager.releaseTrafficChannel(freq, timeslot)
    → Traffic manager returns channel to pool, tears down processing chain
    → Call manager notifies listeners: session completed
    → CallLogWriter writes final record to SQLite
    → Calls tab shows final duration
    → Session removed from active tracking
```

### Control Channel Message Types and Who Handles Them

| Message Type | Current Handler | Phase 3 Handler | Notes |
|---|---|---|---|
| `GROUP_VOICE_CHANNEL_GRANT` | P25TrafficChannelManager | **P25CallSessionManager** | Core grant — creates session |
| `GROUP_VOICE_CHANNEL_GRANT_UPDATE` | P25TrafficChannelManager | **P25CallSessionManager** | Update — extends duration |
| `GROUP_VOICE_CHANNEL_GRANT_UPDATE_EXPLICIT` | P25TrafficChannelManager | **P25CallSessionManager** | Explicit update |
| `UNIT_TO_UNIT_VOICE_CHANNEL_GRANT` | P25TrafficChannelManager | **P25CallSessionManager** | Private call grant |
| `UNIT_TO_UNIT_VOICE_CHANNEL_GRANT_UPDATE` | P25TrafficChannelManager | **P25CallSessionManager** | Private call update |
| `TELEPHONE_INTERCONNECT_VOICE_CHANNEL_GRANT` | P25TrafficChannelManager | **P25CallSessionManager** | Phone interconnect |
| `TELEPHONE_INTERCONNECT_VOICE_CHANNEL_GRANT_UPDATE` | P25TrafficChannelManager | **P25CallSessionManager** | Phone update |
| `GROUP_DATA_CHANNEL_GRANT` | P25TrafficChannelManager | **P25CallSessionManager** | Data channel grant |
| `SNDCP_DATA_CHANNEL_GRANT` | P25TrafficChannelManager | **P25CallSessionManager** | SNDCP data grant |
| `MOTOROLA_GROUP_REGROUP_CHANNEL_GRANT` | P25TrafficChannelManager | **P25CallSessionManager** | Patch group grant |
| `MOTOROLA_OSP_GROUP_REGROUP_ADD` | PatchGroupManager (existing) | PatchGroupManager → **notifies CallSessionManager** | Patch enrichment |
| Traffic channel HDU/LDU/TLC/TDULC | P25TrafficChannelManager | P25TrafficChannelManager → **forwards to CallSessionManager** | Traffic-side stays |

---

## Key Design Decisions

### 1. Call Manager Receives Grants FIRST, Traffic Manager Receives Commands

The fundamental change: `P25P1DecoderState.processControlTrafficGrant()` calls the call
manager instead of the traffic manager. The call manager decides whether to open a traffic
channel and tells the traffic manager to do it. This inverts the current relationship.

### 2. Traffic Manager Becomes a Channel Pool

P25TrafficChannelManager retains:
- The pool of available Phase 1 and Phase 2 traffic channels
- The `mAllocatedTrafficChannelMap` (frequency → channel mapping)
- The `requestTrafficChannelStart()` method (sends to event bus)
- Traffic-channel-side message handling (HDU, LDU, TLC callbacks)

P25TrafficChannelManager loses:
- `processPhase1ControlChannelGrant()` — call manager handles this
- `processPhase2ChannelGrant()` — call manager handles this
- `processP1ControlAnnouncedTrafficUpdate()` — call manager handles this
- `processP1ControlDirectedChannelGrant()` — call manager handles this
- `P25TrafficChannelEventTracker` creation from control channel grants
- Encrypted/unmonitored call filtering (moved to call manager)
- DecodeEvent broadcasting from control channel grants

New methods added to P25TrafficChannelManager:
- `allocateTrafficChannel(APCO25Channel, IdentifierCollection, long timestamp)` → returns Channel or null
- `releaseTrafficChannel(long frequency, int timeslot)` → returns channel to pool

### 3. Accumulate Before Allocating

The call manager can hold a session in PENDING state to accumulate information from
multiple control channel messages before deciding to open a traffic channel:

```
Grant 1:  TG 01085 → freq 856.4625    → Session created (PENDING)
                                          - we know TO, freq, maybe FROM
Grant 2:  same freq, same TG, update   → Session enriched
                                          - now we have serviceOptions (encrypted?)
Decision: all checks pass              → allocateTrafficChannel()
                                          - Session moves to ACTIVE
```

In practice, the PENDING state may be very brief (same message processing cycle) for
simple calls. But for calls where we need to verify encryption status or patch membership
before committing a traffic channel, the accumulation window is valuable.

### 4. Patch Duplicate Detection is Built-In

Because the call manager processes ALL grants across ALL frequencies, it naturally
knows about every active call. When a second grant arrives for a patch member TG:

```
Grant for TG 01085 on freq A → Session created, traffic channel opened
Grant for TG 01089 on freq B → Call manager scans active sessions
                                → Finds TG 01089 is a patch member of TG 01085's session
                                → Enriches primary session, DOES NOT open traffic channel
```

No special `isPatchDuplicate()` method bolted onto the traffic manager. It's just
the normal grant processing logic — the call manager knows about all calls, so it
naturally detects duplicates.

### 5. Traffic Channel Callbacks Flow Back to Call Manager

When the traffic channel's decoder reports events (current user, call end), the traffic
manager forwards them to the call manager:

```
Traffic channel decoder → P25TrafficChannelManager.processP1TrafficCurrentUser()
                              → existing logic (tracker update)
                              → NEW: mCallSessionManager.onTrafficChannelUpdate(freq, ts, ic, timestamp)

Traffic channel decoder → P25TrafficChannelManager.processP1TrafficCallEnd()
                              → existing logic (tracker complete)
                              → NEW: mCallSessionManager.onTrafficChannelEnd(freq, ts, timestamp)
```

This gives the call manager both control-channel and traffic-channel information,
making it the single source of truth for call state.

### 6. Incremental Migration Path

We can migrate control channel message types one at a time:
1. Start with `GROUP_VOICE_CHANNEL_GRANT` (most common)
2. Add `GROUP_VOICE_CHANNEL_GRANT_UPDATE` 
3. Add `MOTOROLA_GROUP_REGROUP_CHANNEL_GRANT`
4. Add remaining grant types

Each migration step is independently testable. Until a message type is migrated,
it continues flowing through the traffic manager as before.

---

## Implementation Steps

### Step 1: Add Grant Processing Methods to P25CallSessionManager

New methods that replace the traffic manager's grant processing:

```java
/**
 * Process a control channel grant. This is the primary entry point for ALL
 * control channel grant messages. The call manager decides whether to create
 * a new session, update an existing session, or suppress a duplicate.
 *
 * If a traffic channel is needed, the call manager requests one from the
 * traffic channel manager's pool.
 */
public void processChannelGrant(APCO25Channel channel, ServiceOptions serviceOptions,
                                 IdentifierCollection ic, Opcode opcode,
                                 long timestamp, String context)

/**
 * Process a control channel grant update. Updates duration and identifiers
 * for an existing session. Does NOT allocate new traffic channels.
 */
public void processChannelUpdate(APCO25Channel channel, ServiceOptions serviceOptions,
                                  IdentifierCollection ic, Opcode opcode,
                                  long timestamp, String context)

/**
 * Called by traffic manager when traffic channel reports current user info.
 * Enriches the active session with traffic-channel-side identifiers.
 */
public void onTrafficChannelUpdate(long frequency, int timeslot,
                                    IdentifierCollection ic, long timestamp)

/**
 * Called by traffic manager when traffic channel signals call termination.
 * Moves session to ENDING state (grace period before finalization).
 */
public void onTrafficChannelEnd(long frequency, int timeslot, long timestamp)
```

`processChannelGrant()` logic:
1. Extract TO, FROM, encryption from IdentifierCollection
2. Look up active session by (frequency, timeslot)
3. If existing session with same TG → update (new talker? new event row)
4. If existing session with different TG → complete old, start new
5. If no existing session → scan ALL active sessions for patch duplicate
6. If patch duplicate → enrich primary, return (no traffic channel)
7. If encrypted + ignoring encrypted → log, return (no traffic channel)
8. If unmonitored + ignoring unmonitored → log, return (no traffic channel)
9. Create new CallSession(PENDING)
10. Request traffic channel: `mTrafficChannelManager.allocateTrafficChannel(...)`
11. If allocated → session moves to ACTIVE, notify listeners
12. If not allocated → session stays PENDING or COMPLETE (pool exhausted)

### Step 2: Add Pool Management Methods to P25TrafficChannelManager

New simplified methods for the call manager to use:

```java
/**
 * Allocate a traffic channel from the pool for the given frequency.
 * Sets up the channel and requests start via event bus.
 * Returns the allocated Channel, or null if pool is exhausted.
 *
 * Called by P25CallSessionManager when it decides a traffic channel is needed.
 */
public Channel allocateTrafficChannel(APCO25Channel apco25Channel,
                                       IdentifierCollection ic,
                                       long timestamp)

/**
 * Release a traffic channel back to the pool.
 * Tears down the processing chain and returns the channel to the available queue.
 *
 * Called by P25CallSessionManager when a call session completes.
 */
public void releaseTrafficChannel(long frequency, int timeslot)

/**
 * Check if a traffic channel is already allocated at the given frequency.
 */
public boolean isTrafficChannelAllocated(long frequency, int timeslot)
```

`allocateTrafficChannel()` is extracted from the existing `processPhase1ControlChannelGrant()`
logic — it's the part that checks the pool, takes a channel, and calls
`requestTrafficChannelStart()`. But it no longer makes any call-related decisions.

### Step 3: Redirect P25P1DecoderState to Call Manager

Change `processControlTrafficGrant()` in `P25P1DecoderState.java`:

```java
// BEFORE (current):
private void processControlTrafficGrant(APCO25Channel channel, ServiceOptions serviceOptions,
                                        List<Identifier> identifiers, Opcode opcode,
                                        long timestamp, String context)
{
    if(mTrafficChannelManager != null)
    {
        mTrafficChannelManager.processP1ControlDirectedChannelGrant(channel, serviceOptions,
            new IdentifierCollection(identifiers), opcode, timestamp, context);
    }
}

// AFTER (Phase 3):
private void processControlTrafficGrant(APCO25Channel channel, ServiceOptions serviceOptions,
                                        List<Identifier> identifiers, Opcode opcode,
                                        long timestamp, String context)
{
    if(mTrafficChannelManager != null)
    {
        P25CallSessionManager callManager = mTrafficChannelManager.getCallSessionManager();
        if(callManager != null)
        {
            // Call manager is the authority — it decides whether to open a traffic channel
            callManager.processChannelGrant(channel, serviceOptions,
                new IdentifierCollection(identifiers), opcode, timestamp, context);
        }
    }
}
```

Similarly for `processControlTrafficUpdate()`:
```java
// AFTER:
if(callManager != null)
{
    callManager.processChannelUpdate(channel, serviceOptions,
        new IdentifierCollection(identifiers), opcode, timestamp, context);
}
```

### Step 4: Forward Traffic Channel Callbacks to Call Manager

In P25TrafficChannelManager, modify existing traffic-channel-side methods to forward
to the call manager:

```java
// In processP1TrafficCurrentUser():
// ... existing tracker logic ...
// NEW: forward to call manager
if(mCallSessionManager != null)
{
    mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1, ic, timestamp);
}

// In processP1TrafficCallEnd():
// ... existing tracker logic ...
// NEW: forward to call manager
if(mCallSessionManager != null)
{
    mCallSessionManager.onTrafficChannelEnd(frequency, P25P1Message.TIMESLOT_1, timestamp);
}
```

### Step 5: Move Encrypted/Unmonitored Filtering to Call Manager

Remove the `mIgnoreEncryptedCalls` and `mIgnoreUnmonitoredCalls` checks from
P25TrafficChannelManager's grant processing methods. Add them to the call manager's
`processChannelGrant()` instead. The call manager has the full session context and
can make better filtering decisions.

### Step 6: Wire Up the Call Manager ↔ Traffic Manager Reference

The call manager needs a reference to the traffic manager (to request channel
allocation). The traffic manager already has a reference to the call manager.
Ensure bidirectional wiring in the constructor or initialization:

```java
// In P25TrafficChannelManager constructor (already exists):
mCallSessionManager = new P25CallSessionManager();

// NEW: give call manager a reference to traffic manager
mCallSessionManager.setTrafficChannelManager(this);
```

### Step 7: Update P25TrafficChannelEventTracker Usage

During migration, the existing tracker system continues to work for traffic-channel-side
messages. But trackers are no longer created from control channel grants — they're only
used when a traffic channel reports activity. The call manager's CallSession replaces
the tracker for control-channel-originated tracking.

Eventually (Phase 4), the tracker can be fully replaced by CallSession for both
control and traffic channel tracking.

### Step 8: Build Verification + Runtime Test

1. `gradlew compileJava` — verify compilation
2. `gradlew run` — start P25 channel, observe:
   - Normal call: control grant → call manager creates session → requests traffic channel → audio plays
   - Patch call: first grant opens traffic channel, subsequent patch member grants suppressed
   - Encrypted call (with ignore enabled): grant logged but no traffic channel opened
   - Channel updates: session duration extended correctly
   - Call termination: traffic channel released, session finalized

### Step 9: Documentation + Changelog

- Update `doc/changes/010_phase3_*.md` with completed work
- Update `CHANGELOG_FORK.md`

---

## Files Modified

| # | File | Change | Lines (est) |
|---|------|--------|-------------|
| 1 | `P25CallSessionManager.java` | Add `processChannelGrant()`, `processChannelUpdate()`, `onTrafficChannelUpdate()`, `onTrafficChannelEnd()`, `setTrafficChannelManager()` | ~200 |
| 2 | `P25TrafficChannelManager.java` | Add `allocateTrafficChannel()`, `releaseTrafficChannel()`, `isTrafficChannelAllocated()`. Forward traffic callbacks to call manager. | ~80 |
| 3 | `P25P1DecoderState.java` | Redirect `processControlTrafficGrant()` and `processControlTrafficUpdate()` to call manager | ~20 |
| 4 | `CallSession.java` | Add fields for traffic channel allocation tracking | ~20 |

**Total:** ~320 lines of new/modified code across 4 files.

---

## Comparison: Current Phase 3 Plan vs. This Plan

| Aspect | Old Phase 3 (Patch Helper) | New Phase 3 (Call Manager Authority) |
|--------|---------------------------|--------------------------------------|
| **Who receives grants** | Traffic manager (unchanged) | **Call manager** |
| **Who decides to open traffic channels** | Traffic manager | **Call manager** |
| **Patch detection** | `isPatchDuplicate()` helper added to traffic manager | Built into call manager's normal grant processing |
| **Encrypted/unmonitored filtering** | Stays in traffic manager | **Moves to call manager** |
| **Traffic manager role** | Same as before + patch check | **Demoted to channel pool** |
| **Architectural direction** | Incremental patch | **Correct architecture** — single authority |
| **Risk** | Low (minimal change) | Medium (re-plumbing the message flow) |
| **Future phases** | Still need to re-plumb later | **Done** — call manager is already in position |

---

## Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Breaking existing call flow during migration | Medium | Migrate one message type at a time. Keep traffic manager's existing methods until all grants are migrated. Fall back to traffic manager if call manager returns null. |
| Thread safety between call manager and traffic manager | Medium | Call manager acquires traffic manager's lock when calling allocateTrafficChannel(). Or use message passing. |
| Missing traffic channel callbacks | Low | Traffic manager already has the callback methods. We just add forwarding lines. |
| Performance (additional indirection) | None | One extra method call per grant. Negligible. |
| Regression for non-P25 protocols | None | Only P25 decoder state is modified. DMR/NXDN unaffected. |

---

## Related Design Docs

| Doc | Relevance |
|-----|-----------|
| `010_call_session_management.md` | Master design — this phase implements the core architecture |
| `010_phase1_implementation_plan.md` | Phase 1 foundation (CallSession, P25CallSessionManager) — COMPLETE |
| `010_phase2_implementation_plan.md` | Phase 2 (Calls tab, SQLite) — COMPLETE |
| `006a_call_log_database.md` | Call log records reflect consolidated sessions |

---

## What This Does NOT Cover (Future Phases)

- **Audio routing through CallSession** — Audio still uses AudioSegment/AudioModule. The
  call manager controls channel allocation but doesn't own audio buffers yet.
- **Recording integration** — Recording still per-AudioSegment.
- **P25TrafficChannelEventTracker full removal** — Tracker still used for traffic-channel-side
  messages. Full replacement by CallSession is a future phase.
- **Phase 2 (TDMA) grant processing** — Focus is on Phase 1 grants first. Phase 2 TDMA
  grants follow the same pattern but with timeslot handling.
- **PatchGroupManager bug fix** — The `update()` put/remove issue. Independent fix.
