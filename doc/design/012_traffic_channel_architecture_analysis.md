# 012: Traffic Channel Architecture Analysis & Restoration Plan

## Date: 2026-03-21
## Status: Analysis Complete — Ready for Implementation

---

## 1. Executive Summary

Task 10 (Call Session Management, Phases 1–4) replaced the upstream `P25TrafficChannelManager` (TCM) event tracking system with a new `P25CallSessionManager` (CSM) as the sole authority for call tracking, event creation, and duration management. This created a **dual-bookkeeping architecture** where the Events tab and Calls tab are two separate tracking paths that drift apart, resulting in:

- **Events tab** showing 0.0 or minimal duration for many calls
- **Calls tab** accumulating correct duration
- **1:1 mismatch** between Events and Calls

The recommended fix is to **restore the upstream TCM** (bring back `P25TrafficChannelEventTracker` and all event tracking) and convert CSM to a **pure observer layer** that watches TCM events and creates call sessions from them.

---

## 2. Upstream Architecture (How It Was Designed)

### 2.1 The Single Authority: P25TrafficChannelManager

In upstream (`DSheirer/sdrtrunk`), the TCM was the **single authority** for everything:

```
┌─────────────────────────────────────────────────────────────────┐
│                    P25TrafficChannelManager                       │
│                                                                   │
│  ┌──────────────────────┐  ┌─────────────────────────────────┐  │
│  │ Channel Pool          │  │ Event Tracking                   │  │
│  │ • Phase1 queue        │  │ • mTS1ChannelGrantEventMap       │  │
│  │ • Phase2 queue        │  │ • mTS2ChannelGrantEventMap       │  │
│  │ • Allocated map       │  │ • P25TrafficChannelEventTracker  │  │
│  │ • Allocate/release    │  │ • Same-call matching             │  │
│  └──────────────────────┘  │ • Duration tracking (ctrl+traf)  │  │
│                             │ • Staleness detection            │  │
│                             │ • DecodeEvent creation           │  │
│                             └─────────────────────────────────┘  │
│                                                                   │
│  Event Broadcasting ──→ DecodeEventListener ──→ Events Tab        │
│  Channel Events ──→ ChannelProcessingManager                      │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 P25TrafficChannelEventTracker

The key class that was **deleted in our fork**. Each tracker:
- Held ONE `P25ChannelGrantEvent` (which IS the Events tab row)
- Tracked call state: not-started → started → complete
- Accepted **control-sourced** timestamps via `updateDurationControl(timestamp)`
- Accepted **traffic-sourced** timestamps via `updateDurationTraffic(timestamp)` — takes priority once traffic channel starts
- Detected staleness (>1 second gap from timestamp to tracker time)
- Matched same-call continuation via `isSameCallCheckingToOnly()` and `isSameCallCheckingToAndFrom()`
- **One tracker = one event = one row in Events tab. Always.**

### 2.3 Upstream Call Flow: Control Channel

```
P25P1DecoderState (control channel)
│
├── processControlTrafficGrant(channel, serviceOptions, identifiers, opcode, timestamp)
│   └── TCM.processP1ControlDirectedChannelGrant(channel, serviceOptions, ic, opcode, timestamp)
│       ├── Determines DecodeEventType from opcode
│       ├── Routes TDMA → processPhase2ChannelGrant()
│       ├── Routes FDMA → processPhase1ControlChannelGrant()
│       │   ├── Gets/creates tracker for frequency:timeslot
│       │   ├── Same call? → update duration, add FROM if missing
│       │   ├── Different call? → remove old tracker, create new
│       │   ├── Ignored data call? → create event but don't allocate
│       │   ├── Allocate traffic channel from pool
│       │   └── broadcast(tracker) → Events tab
│       └── Returns
│
├── processControlAnnouncedTrafficUpdate(channel, serviceOptions, ic, opcode, timestamp)
│   └── TCM.processP1ControlAnnouncedTrafficUpdate(channel, serviceOptions, ic, opcode, timestamp)
│       ├── Gets tracker (remove if stale)
│       ├── Same call (TO only)? → update control duration, broadcast
│       ├── Different call? → remove tracker, delegate to processP1ControlDirectedChannelGrant()
│       └── Returns
│
├── processFrequencyBand(IFrequencyBand)
│   └── TCM.processFrequencyBand(frequencyBand) — stores for preload data
│
└── receive(IMessage) — NetworkStatusBroadcast
    └── TCM.getMessageListener().receive(message) — captures scramble parameters
```

### 2.4 Upstream Call Flow: Traffic Channel

```
P25P1DecoderState (traffic channel — no control channel TCM)
│
├── processHDUCallStart(talkgroup, radio, eki, serviceOptions, channelDescriptor, timestamp)
│   └── TCM.processP1TrafficCallStart(frequency, talkgroup, radio, eki, serviceOptions, channelDescriptor, timestamp)
│       ├── Gets tracker for frequency:TS1
│       ├── If already started → remove (new call), create new tracker
│       ├── If not started → add talkgroup to existing tracker
│       ├── broadcast(tracker) → Events tab
│       └── Returns
│
├── processLCChannelUser(ic, timestamp) — from LDU voice frames
│   └── TCM.processP1TrafficCurrentUser(frequency, channelDescriptor, decodeEventType, serviceOptions, ic, timestamp, details)
│       ├── Gets tracker (remove if complete)
│       ├── Same call? → add identifiers, update traffic duration, broadcast
│       ├── Different call? → create new tracker, broadcast
│       └── Returns
│
├── processLDU1Identifiers(identifiers, timestamp)
│   └── TCM.processP1TrafficLDU1(frequency, identifiers, timestamp)
│       ├── Gets tracker (remove if complete)
│       ├── If exists → add identifiers (encryption, etc.), update duration
│       ├── If not exists → create new tracker from LDU1 data
│       └── broadcast(tracker) → Events tab
│
├── processCurrentUser(identifier, timestamp) — encryption, GPS, etc.
│   └── TCM.processP1TrafficCurrentUser(frequency, identifier, timestamp)
│       ├── Gets tracker (remove if complete)
│       ├── If exists → add identifier, update traffic duration, broadcast
│       └── Returns
│
└── processTDU/processTDULC — call termination
    └── TCM.processP1TrafficCallEnd(frequency, timestamp)
        ├── Gets tracker for frequency:TS1
        ├── If started → mark complete, broadcast
        └── Returns true/false (was a call ended?)
```

### 2.5 Upstream Call Flow: Phase 2

Similar pattern but with timeslot-awareness:

```
P25P2DecoderState (traffic channel — timeslot 1 or 2)
│
├── receive(AbstractVoiceTimeslot) — voice frames
│   └── TCM.processP2TrafficVoice(frequency, timeslot, timestamp)
│
├── processChannelGrant(MacMessage) — P2 control grants
│   └── TCM.processP2ChannelGrant(channel, serviceOptions, ic, macOpcode, timestamp)
│
├── processChannelUpdate(MacMessage) — P2 control updates
│   └── TCM.processP2ChannelUpdate(channel, serviceOptions, ic, macOpcode, timestamp)
│
├── processCurrentUser(MacMessage) — P2 traffic current user
│   └── TCM.processP2TrafficCurrentUser(frequency, timeslot, channelDescriptor, serviceOptions, macOpcode, ic, timestamp, details)
│
├── processP2TrafficCurrentUser(identifier) — single identifier update
│   └── TCM.processP2TrafficCurrentUser(frequency, timeslot, identifier, timestamp)
│
├── processCallEnd / processEndPTT — call termination
│   └── TCM.processP2TrafficCallEnd(frequency, timeslot, timestamp)
│   └── TCM.processP2TrafficEndPushToTalk(frequency, timeslot, timestamp)
│
└── resetState()
    └── TCM.processP2TrafficCallEnd(frequency, timeslot, timestamp)
```

### 2.6 Key Design Principles in Upstream

1. **TCM is the single source of truth** — one tracker = one event = one Events tab row
2. **Control channel creates/updates trackers** — grants create new trackers, updates extend duration
3. **Traffic channel also creates/updates trackers** — for the SAME event object, using traffic-side timestamps
4. **Duration has two sources** — `updateDurationControl()` (from control grants) and `updateDurationTraffic()` (from traffic messages). Traffic takes priority once started.
5. **Same-call detection** — two modes: `isSameCallCheckingToOnly()` for control updates, `isSameCallCheckingToAndFrom()` for traffic current user
6. **Staleness** — trackers auto-expire after 1 second gap (configurable)
7. **Event reuse** — `broadcast(tracker)` sends the SAME `P25ChannelGrantEvent` object to the Events tab via `DecodeEventListener`. The `ClearableHistoryModel.add()` uses `contains()` (object identity) to decide update vs. add.

---

## 3. Current Fork Architecture (Task 10 Changes)

### 3.1 What Was Changed

**P25TrafficChannelManager** was stripped to a "pool-only" manager:
- **REMOVED**: `mTS1ChannelGrantEventMap`, `mTS2ChannelGrantEventMap` (tracker maps)
- **REMOVED**: `P25TrafficChannelEventTracker` class entirely (deleted file)
- **REMOVED**: All `processP1*`, `processP2*` methods (control grants, traffic updates, call start/end)
- **REMOVED**: `DecodeEventDuplicateDetector`, `LoggingSuppressor`
- **REMOVED**: `getEventType(Opcode/MacOpcode)` methods
- **REMOVED**: `mIgnoreDataCalls` flag (moved to CSM)
- **KEPT**: Channel pool creation, allocation, release, teardown monitor
- **ADDED**: `P25CallSessionManager` instance, `CallLogWriter` instance
- **ADDED**: `allocatePhase1TrafficChannel()`, `allocatePhase2TrafficChannel()`, `releaseTrafficChannel()`, `isTrafficChannelAllocated()` — pool API for CSM

**P25P1DecoderState** was rewired:
- Control channel methods now call `TCM.getCallSessionManager().processChannelGrant()` instead of `TCM.processP1ControlDirectedChannelGrant()`
- Traffic channel methods now call `TCM.getCallSessionManager().onTrafficChannelUpdate()` instead of `TCM.processP1TrafficCurrentUser()`
- Call end methods now call `TCM.getCallSessionManager().onTrafficChannelEnd()` instead of `TCM.processP1TrafficCallEnd()`

**P25P2DecoderState** was similarly rewired to call CSM.

### 3.2 The Dual-Bookkeeping Problem

CSM now maintains **two parallel tracking systems**:

```
┌────────────────────────────────────────────────────────────┐
│                   P25CallSessionManager                     │
│                                                              │
│  ┌─────────────────────┐  ┌──────────────────────────────┐ │
│  │ CallSession Map      │  │ Cached Control Events Map    │ │
│  │ (for Calls tab)      │  │ (for Events tab)             │ │
│  │                       │  │                              │ │
│  │ • mActiveSessions    │  │ • mActiveControlEvents       │ │
│  │ • mEndingSessions    │  │ • P25ChannelGrantEvent objs  │ │
│  │ • CallSessionEvent   │  │ • broadcastControlGrantEvent │ │
│  │ • Session lifecycle  │  │ • broadcastTrafficUpdate     │ │
│  │   PENDING→ACTIVE→    │  │                              │ │
│  │   ENDING→COMPLETE    │  │                              │ │
│  └─────────────────────┘  └──────────────────────────────┘ │
│                                                              │
│  These two systems CAN and DO drift apart!                   │
└────────────────────────────────────────────────────────────┘
```

### 3.3 Specific Bugs Causing the Duration Mismatch

**Bug 1: Events tab cached events don't get traffic-side updates for many calls**

The `broadcastTrafficUpdate()` method tries to find the cached event by `frequency:timeslot`, with a fallback from timeslot 1→0. But:
- The initial grant creates the event with timeslot 0 (Phase 1)
- Traffic updates come with timeslot 1
- If `onTrafficChannelUpdate()` encounters any error or the session doesn't exist, no update occurs
- For IGNORED calls (no traffic channel), there's NO traffic-side update path at all

**Bug 2: Control channel updates are unreliable for duration**

In upstream, `processP1ControlAnnouncedTrafficUpdate()` would directly update the tracker's control duration. In our fork, `processControlAnnouncedTrafficUpdate()` in DecoderState calls `CSM.processChannelUpdate()`, which tries to find an active session and update it. If the session was already transitioned to ENDING (from a TDU), the update falls through to creating a new grant — creating a NEW cached event with a new start time.

**Bug 3: Lifecycle mismatch between sessions and cached events**

`transitionToEnding()` has a `clearCachedEvent` parameter. When `true`, the cached Events tab event is removed. When `false` (TDU within same call), it's kept. But the cleanup timer can finalize the session and remove the event later. Meanwhile, new control grants may create a new cached event that starts at a different timestamp. The Calls tab session continues accumulating duration from the original session start, but the Events tab event has a newer start time.

**Bug 4: Missing FROM identifiers and details on Events tab**

In upstream, `processP1TrafficCurrentUser()` added identifiers directly to the tracker's event. In our fork, `onTrafficChannelUpdate()` updates the CallSession and its current event, then calls `broadcastTrafficUpdate()` to update the cached event. But `broadcastTrafficUpdate()` only sets `identifierCollection` and `duration` — it doesn't update `FROM`, `details`, or `eventType` from the traffic-side data.

---

## 4. Impact on Other Systems

### 4.1 DMR Traffic Channel Manager — NOT AFFECTED

`DMRTrafficChannelManager` (902 lines) was **not modified** by Task 10. It follows the same upstream pattern:
- Has its own call event maps (`mCallEventsTS1`/`mCallEventsTS2`)
- Creates/tracks `DMRCallEvent` objects
- DecoderState calls TCM directly
- No CSM integration

### 4.2 MPT1327 Traffic Channel Manager — NOT AFFECTED

`MPT1327TrafficChannelManager` was **not modified**. Simpler protocol, simpler tracking.

### 4.3 Base TrafficChannelManager — NOT AFFECTED

The abstract base class was not modified. It only has `setCurrentControlFrequency()` and the abstract `processControlFrequencyUpdate()`.

---

## 5. Recommended Architecture: CSM as Observer Layer

### 5.1 Design Principle

**Restore the upstream TCM as the single source of truth for event tracking. CSM becomes a pure observer that listens to TCM events and creates call sessions from them.**

```
┌──────────────────────────────────────────────────────────────────┐
│  P25P1DecoderState / P25P2DecoderState                            │
│                                                                    │
│  Control Channel:                                                  │
│    processControlTrafficGrant() ──→ TCM.processP1ControlDirected…│
│    processControlAnnouncedUpdate() ──→ TCM.processP1ControlAnnou…│
│                                                                    │
│  Traffic Channel:                                                  │
│    processHDUCallStart() ──→ TCM.processP1TrafficCallStart()      │
│    processLCChannelUser() ──→ TCM.processP1TrafficCurrentUser()   │
│    processLDU1Identifiers() ──→ TCM.processP1TrafficLDU1()        │
│    processTDU() ──→ TCM.processP1TrafficCallEnd()                 │
└───────────────────────┬──────────────────────────────────────────┘
                        │
                        ▼
┌──────────────────────────────────────────────────────────────────┐
│  P25TrafficChannelManager (RESTORED to upstream)                  │
│                                                                    │
│  ┌──────────────────┐  ┌────────────────────────────────────┐    │
│  │ Channel Pool      │  │ Event Tracking (RESTORED)           │    │
│  │ • Allocate/release│  │ • mTS1ChannelGrantEventMap          │    │
│  │ • Teardown monitor│  │ • mTS2ChannelGrantEventMap          │    │
│  └──────────────────┘  │ • P25TrafficChannelEventTracker     │    │
│                         │ • Same-call matching                │    │
│                         │ • Duration tracking (ctrl + traffic)│    │
│                         │ • Staleness detection               │    │
│                         └──────────────────┬─────────────────┘    │
│                                             │                      │
│  broadcast(DecodeEvent) ────────────────────┤                      │
│                                             │                      │
│  ┌──────────────────────────────────────────┤                      │
│  │                                          │                      │
│  ▼                                          ▼                      │
│  Events Tab                    CSM Observer Hook                   │
│  (DecodeEventListener)         (NEW: CSM receives same events)     │
└──────────────────────────────────────────────────────────────────┘
                                              │
                                              ▼
┌──────────────────────────────────────────────────────────────────┐
│  P25CallSessionManager (OBSERVER — additive layer)                │
│                                                                    │
│  Receives: DecodeEvent from TCM broadcast()                       │
│                                                                    │
│  Creates: CallSession for each unique call                        │
│  Updates: Duration, identifiers, encryption from each broadcast   │
│  Filters: Encrypted/unmonitored/data (marks as IGNORED)           │
│  Enriches: Patch group consolidation                              │
│  Outputs:                                                          │
│    • Calls tab (CallSessionEvent via CallSessionListener)         │
│    • Call log database (CallLogWriter)                            │
│    • Patch group cross-frequency matching                         │
└──────────────────────────────────────────────────────────────────┘
```

### 5.2 CSM Observer Interface

CSM observes TCM events via the existing `Listener<IDecodeEvent>` mechanism:

```java
// In TCM — existing broadcast() method (upstream code, unchanged)
public void broadcast(DecodeEvent decodeEvent) {
    if (mDecodeEventListener != null) {
        // ... existing duplicate detection for data calls ...
        mDecodeEventListener.receive(decodeEvent);
    }
}

// In TCM — ADDITION: also notify CSM
public void broadcast(P25TrafficChannelEventTracker tracker) {
    broadcast(tracker.getEvent());   // existing: sends to Events tab
    if (mCallSessionManager != null) {
        mCallSessionManager.onDecodeEvent(tracker.getEvent(), 
            tracker.isStarted(), tracker.isComplete());
    }
}
```

CSM's `onDecodeEvent()` would:
1. Extract frequency, timeslot, identifiers, eventType, duration from the DecodeEvent
2. Find or create a CallSession for this (frequency, timeslot)
3. Update session state, identifiers, duration
4. If call is complete → finalize session
5. Apply filtering (mark as IGNORED in Calls tab — don't prevent tracking)
6. Notify CallSessionListeners (Calls tab UI, call log DB)

### 5.3 What CSM Keeps (Our Additions)

- `CallSession` lifecycle management (PENDING → ACTIVE → ENDING → COMPLETE)
- `CallSessionEvent` per-call tracking for Calls tab
- Patch group consolidation (`findCrossFrequencySession`, `onPatchGroupUpdate`)
- Encrypted/unmonitored/data call filtering (observation only — never prevents traffic channel allocation)
- Call log database integration (`CallLogWriter`)
- Session gap tolerance and cleanup timer
- `PatchGroupManager` integration

### 5.4 What CSM Loses (Returns to TCM)

- `processChannelGrant()` / `processChannelUpdate()` — returns to TCM
- `processP2ChannelGrant()` / `processP2ChannelUpdate()` — returns to TCM
- `broadcastControlGrantEvent()` / `broadcastTrafficUpdate()` — removed (TCM handles Events tab)
- `mActiveControlEvents` cache — removed entirely
- Traffic channel allocation decisions — returns to TCM
- `onTrafficChannelUpdate()` / `onTrafficChannelEnd()` — replaced by observing TCM events
- `isUnmonitored()` — stays in CSM but only for Calls tab filtering, not allocation decisions

### 5.5 Filtering Strategy

**Critical change**: In the current fork, filtering (encrypted/unmonitored/data) **prevents traffic channel allocation**. This is wrong for two reasons:
1. It means the Events tab shows calls as "IGNORED" with no traffic data
2. Upstream intended for traffic channels to still be allocated (audio just isn't played)

In the restored architecture:
- **TCM allocates traffic channels for ALL calls** (except data calls if `ignoreDataCalls` is set — upstream behavior)
- **CSM marks calls as IGNORED** in the Calls tab for display filtering
- **Audio routing** handles the actual muting/filtering (already implemented in our audio channel routing changes #007)

### 5.6 What Stays Different from Upstream TCM

The only additions to the restored TCM:
1. `P25CallSessionManager` instance (observer)
2. `CallLogWriter` instance
3. Hook in `broadcast(tracker)` to notify CSM
4. `TalkerAliasManager` (already in both upstream and fork)
5. `ignoreEncryptedCalls` / `ignoreUnmonitoredCalls` passed to CSM (not to TCM itself)

---

## 6. Implementation Plan

### Phase 1: Restore TCM (Estimated: 2-3 hours)

1. **Restore `P25TrafficChannelEventTracker.java`** from upstream
   - Copy from `git show upstream/master:src/main/java/io/github/dsheirer/module/decode/p25/P25TrafficChannelEventTracker.java`

2. **Restore `P25TrafficChannelManager.java`** to upstream + observer hook
   - Start from upstream version
   - Add CSM instance and observer hook
   - Add CallLogWriter instance  
   - Add `ignoreEncryptedCalls`/`ignoreUnmonitoredCalls` pass-through to CSM
   - Keep `TalkerAliasManager`

3. **Restore `P25P1DecoderState.java`** call sites
   - `processControlTrafficGrant()` → call `TCM.processP1ControlDirectedChannelGrant()` (like upstream)
   - `processControlAnnouncedTrafficUpdate()` → call `TCM.processP1ControlAnnouncedTrafficUpdate()` (like upstream)
   - `processHDUCallStart()` → call `TCM.processP1TrafficCallStart()` (like upstream)
   - `processLCChannelUser()` → call `TCM.processP1TrafficCurrentUser()` (like upstream)
   - `processLDU1()` → call `TCM.processP1TrafficLDU1()` (like upstream)
   - `processTDU/TDULC()` → call `TCM.processP1TrafficCallEnd()` (like upstream)

4. **Restore `P25P2DecoderState.java`** call sites
   - Similar pattern: restore calls to TCM methods instead of CSM

### Phase 2: Convert CSM to Observer (Estimated: 1-2 hours)

1. **Remove CSM grant processing methods**:
   - `processChannelGrant()`, `processChannelUpdate()`
   - `processP2ChannelGrant()`, `processP2ChannelUpdate()`
   - `processPhase1Grant()`, `processP2ChannelGrantInternal()`
   - `processP2DataChannel()`

2. **Remove CSM traffic forwarding methods**:
   - `onTrafficChannelUpdate()`, `onTrafficChannelEnd()`

3. **Remove Events tab caching**:
   - `mActiveControlEvents` map
   - `broadcastControlGrantEvent()`
   - `broadcastTrafficUpdate()`

4. **Add CSM observer method**:
   ```java
   public void onDecodeEvent(P25ChannelGrantEvent event, boolean isStarted, boolean isComplete) {
       // Extract info from event
       // Find or create CallSession
       // Update session state
       // Apply filtering (IGNORED marking only)
       // Notify listeners
   }
   ```

5. **Keep CSM session management, patch group logic, call log, listeners**

### Phase 3: Testing (Estimated: 1 hour)

1. Run with 2 P25 Phase 1 LSM systems
2. Verify Events tab shows correct durations (should match upstream behavior)
3. Verify Calls tab shows correct durations (1:1 with Events)
4. Verify patch group consolidation still works
5. Verify call log database still records properly
6. Verify audio routing still works with filtering

---

## 7. Files Changed Summary

| File | Action |
|------|--------|
| `P25TrafficChannelEventTracker.java` | **RESTORE** from upstream |
| `P25TrafficChannelManager.java` | **RESTORE** from upstream + add CSM observer hook |
| `P25P1DecoderState.java` | **RESTORE** call sites to use TCM directly |
| `P25P2DecoderState.java` | **RESTORE** call sites to use TCM directly |
| `P25CallSessionManager.java` | **REWRITE** as observer (remove grant processing, add event observation) |
| `P25DecodeEventTypeResolver.java` | **KEEP** — still used by CSM for event type analysis |
| `CallSession.java` | **KEEP** — unchanged |
| `CallSessionEvent.java` | **KEEP** — unchanged |
| `CallLogWriter.java` | **KEEP** — unchanged |
| `CallSessionPanel.java` | **KEEP** — unchanged |
| `CallSessionModel.java` | **KEEP** — unchanged |

---

## 8. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Merge conflicts with future upstream | LOW | LOW | TCM stays close to upstream |
| Patch group consolidation regression | MEDIUM | MEDIUM | CSM keeps patch logic as observer |
| Call log DB data format change | LOW | LOW | CSM still creates CallSessions |
| Audio routing affected | LOW | LOW | Audio routing is independent (#007) |
| Other protocol TCMs affected | NONE | NONE | DMR/MPT1327 not touched |

---

## 9. Alternative Considered: Fix the Dual-Bookkeeping

Instead of restoring upstream, we could fix the existing CSM to keep Events tab in sync. This would require:
- Fixing every duration update path in CSM
- Fixing timeslot key mismatches
- Fixing lifecycle desync between sessions and cached events
- Duplicating all the same-call matching logic that upstream already has

**Rejected because**: This is essentially reimplementing `P25TrafficChannelEventTracker` inside CSM, which is what we're trying to avoid. The upstream tracker is well-tested and handles edge cases we'd have to rediscover.
