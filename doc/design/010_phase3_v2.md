# 010 Phase 3 — Call Manager as Control Channel Authority

## Date
2026-03-20

## Status
Design — Ready for Implementation

## Scope
- P25CallSessionManager becomes the **sole recipient** of all control channel traffic
- P25TrafficChannelManager is **demoted** to a channel pool manager + traffic-side event handler
- Control channel events and traffic channel events are cleanly separated into two flows
- Events track their **source channel type** (control vs traffic) for debugging and display
- Cross-frequency patch call consolidation built into grant processing

---

## Architecture

### Two Distinct Event Flows

The system has two fundamentally different types of events that are currently tangled
together in P25TrafficChannelManager. Phase 3 cleanly separates them.

```
FLOW 1: CONTROL CHANNEL EVENTS
═══════════════════════════════
  P25P1DecoderState (control channel)
      │
      │  TSBK/AMBTC grants, grant updates, terminations
      │
      ▼
  P25CallSessionManager (AUTHORITY)
      │
      ├── Creates/manages CallSession objects
      ├── Applies encrypted/unmonitored filters
      ├── Detects patch duplicates across frequencies
      ├── Decides whether to open traffic channels
      │       │
      │       ▼
      │   P25TrafficChannelManager.allocateTrafficChannel()
      │       └── Takes channel from pool, starts via event bus
      │
      ├── Notifies → CallSessionModel (Calls tab)
      ├── Notifies → CallLogWriter (SQLite)
      └── Broadcasts → DecodeEvent to Events tab (tagged: SOURCE=CONTROL)


FLOW 2: TRAFFIC CHANNEL EVENTS
═══════════════════════════════
  P25P1DecoderState (traffic channel)
      │
      │  HDU, LDU1, LDU2, TDU, TDULC, ESP
      │
      ▼
  P25TrafficChannelManager (TRAFFIC-SIDE HANDLER)
      │
      ├── Manages P25TrafficChannelEventTracker for traffic-side messages
      ├── Updates tracker with identifiers, encryption, duration
      ├── Broadcasts → DecodeEvent to Events tab (tagged: SOURCE=TRAFFIC)
      │
      └── Forwards to → P25CallSessionManager
              ├── .onTrafficChannelUpdate(freq, ts, ic, timestamp)
              └── .onTrafficChannelEnd(freq, ts, timestamp)
```

### Component Responsibilities

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     P25CallSessionManager                               │
│                                                                         │
│  OWNS:                                                                  │
│  - All control channel grant processing logic                           │
│  - CallSession lifecycle (PENDING → ACTIVE → ENDING → COMPLETE)         │
│  - Event type determination (getEventType for Opcode and MacOpcode)     │
│  - Encrypted/unmonitored call filtering                                 │
│  - AliasList reference for isUnmonitored() checks                       │
│  - Patch duplicate detection across frequencies                         │
│  - P25ChannelGrantEvent creation for Events tab compatibility           │
│  - Session cleanup timer                                                │
│  - DecodeEvent listener reference (for Events tab broadcasting)         │
│                                                                         │
│  CALLS:                                                                 │
│  - trafficManager.allocateTrafficChannel() when channel needed          │
│  - trafficManager.releaseTrafficChannel() when call ends                │
│  - trafficManager.isTrafficChannelAllocated() for status checks         │
│                                                                         │
│  RECEIVES FROM:                                                         │
│  - P25P1DecoderState: processChannelGrant(), processChannelUpdate()     │
│  - P25TrafficChannelManager: onTrafficChannelUpdate(), onTrafficEnd()   │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                     P25TrafficChannelManager                             │
│                                                                         │
│  OWNS:                                                                  │
│  - Phase 1 and Phase 2 traffic channel pools (queues)                   │
│  - Allocated traffic channel map (frequency → Channel)                  │
│  - requestTrafficChannelStart() — sends to event bus                    │
│  - TrafficChannelTeardownMonitor — handles stop/rejection               │
│  - Traffic-channel-side tracker management (P25TrafficChannelEventTracker│
│  - Frequency band map                                                   │
│  - Scramble parameters (Phase 2)                                        │
│  - TalkerAliasManager                                                   │
│  - Message listener for network status broadcast                        │
│                                                                         │
│  EXPOSES (new pool API):                                                │
│  - allocateTrafficChannel(apco25Channel, ic, timestamp) → Channel       │
│  - releaseTrafficChannel(frequency, timeslot)                           │
│  - isTrafficChannelAllocated(frequency) → boolean                       │
│  - getCurrentControlFrequency() (already exists)                        │
│                                                                         │
│  HANDLES (traffic-side, unchanged):                                     │
│  - processP1TrafficCallStart() — HDU                                    │
│  - processP1TrafficCurrentUser() — identifier updates                   │
│  - processP1TrafficLDU1() — voice frames                                │
│  - processP1TrafficCallEnd() — TLC/TDULC                                │
│  - processP2TrafficCurrentUser(), processP2TrafficCallEnd(), etc.       │
│  - processP2DataChannel()                                               │
│                                                                         │
│  NO LONGER HANDLES:                                                     │
│  - processP1ControlDirectedChannelGrant() — removed                     │
│  - processP1ControlAnnouncedTrafficUpdate() — removed                   │
│  - processPhase1ControlChannelGrant() — removed                         │
│  - processPhase2ChannelGrant() (from control) — removed                 │
│  - processP2ChannelGrant() (from control) — removed                     │
│  - processP2ChannelUpdate() (from control) — removed                    │
│  - Encrypted/unmonitored filtering — moved to call manager              │
│  - getEventType() methods — moved to call manager                       │
│  - isUnmonitored() — moved to call manager                              │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Event Source Tracking

Every DecodeEvent and CallSessionEvent will carry a **source channel type** tag indicating
whether it originated from the control channel or a traffic channel. This is useful for
debugging, log analysis, and potentially for UI display.

### New Enum: ChannelSourceType

```java
package io.github.dsheirer.module.decode.session;

public enum ChannelSourceType
{
    CONTROL("Control"),
    TRAFFIC("Traffic"),
    UNKNOWN("Unknown");

    private final String mLabel;

    ChannelSourceType(String label) { mLabel = label; }

    public String getLabel() { return mLabel; }
    @Override public String toString() { return mLabel; }
}
```

### Where It's Set

- **Call manager** sets `CONTROL` on all P25ChannelGrantEvents it creates from control
  channel grants/updates
- **Traffic manager** sets `TRAFFIC` on all P25ChannelGrantEvents it creates from
  traffic channel HDU/LDU/TDU messages
- **CallSessionEvent** gets a new `channelSourceType` field set by the call manager when
  creating events from grants, and by the traffic forwarding when updating from traffic

### Where It's Used

- **Events tab** — can optionally show a "Source" column (CONTROL/TRAFFIC)
- **Calls tab** — CallSessionEvent carries it for diagnostic purposes
- **Log output** — included in trace/debug logging for troubleshooting

---

## Implementation Steps

### Step 1: Create ChannelSourceType Enum

New file: `src/main/java/io/github/dsheirer/module/decode/session/ChannelSourceType.java`

Simple enum with CONTROL, TRAFFIC, UNKNOWN values and a label.

### Step 2: Add channelSourceType to CallSessionEvent

Add a `ChannelSourceType mChannelSourceType` field to `CallSessionEvent` with getter/setter.
Default to `UNKNOWN`.

### Step 3: Add channelSourceType to P25ChannelGrantEvent

Add a `ChannelSourceType` field to the existing P25ChannelGrantEvent builder so events in
the Events tab can show whether they came from the control or traffic channel.

### Step 4: Move Shared Logic to P25CallSessionManager

Move from P25TrafficChannelManager to P25CallSessionManager:

**a) `getEventType(Opcode, ServiceOptions, DecodeEventType)` method**
- Determines DecodeEventType from TSBK Opcode
- Used by control channel grant processing
- Copy to call manager (traffic manager can keep its copy for traffic-side if needed,
  or we extract to a shared utility)

**b) `getEventType(MacOpcode, ServiceOptions, DecodeEventType)` method**
- Determines DecodeEventType from Phase 2 MAC opcode
- Same treatment as above

**c) `isUnmonitored(IdentifierCollection)` method**
- Requires AliasList reference
- Move to call manager, add `setAliasList(AliasList)` to call manager

**d) Filtering flags: `mIgnoreDataCalls`, `mIgnoreEncryptedCalls`, `mIgnoreUnmonitoredCalls`**
- These drive the call manager's filtering decisions
- Pass to call manager at construction time or via setters

### Step 5: Add Grant Processing Methods to P25CallSessionManager

New methods that replace the traffic manager's control-channel grant processing:

```java
/**
 * Process a control channel grant. Primary entry point for ALL control channel
 * grant messages. Replaces processP1ControlDirectedChannelGrant() and
 * processP2ChannelGrant().
 *
 * Logic:
 * 1. Determine DecodeEventType from opcode
 * 2. Check for existing session on this (freq, timeslot)
 * 3. If same call → update session, extend duration
 * 4. If different call → end old session, start new
 * 5. Check patch duplicate across all active sessions
 * 6. Apply encrypted/unmonitored/data filters
 * 7. Create CallSession, request traffic channel if needed
 * 8. Create P25ChannelGrantEvent for Events tab (SOURCE=CONTROL)
 * 9. Notify listeners
 */
public void processChannelGrant(APCO25Channel channel, ServiceOptions serviceOptions,
                                 IdentifierCollection ic, Opcode opcode,
                                 long timestamp, String context)

/**
 * Process a control channel grant update. Replaces
 * processP1ControlAnnouncedTrafficUpdate() and processP2ChannelUpdate().
 *
 * Logic:
 * 1. Find existing session on (freq, timeslot)
 * 2. If same call → update duration
 * 3. If no session or different call → delegate to processChannelGrant()
 */
public void processChannelUpdate(APCO25Channel channel, ServiceOptions serviceOptions,
                                  IdentifierCollection ic, Opcode opcode,
                                  long timestamp, String context)

/**
 * Process a Phase 2 control channel grant. Entry point for P2 MAC grants
 * from the control channel.
 */
public void processP2ChannelGrant(APCO25Channel channel, ServiceOptions serviceOptions,
                                   IdentifierCollection ic, MacOpcode macOpcode,
                                   long timestamp, String context)

/**
 * Process a Phase 2 control channel update.
 */
public void processP2ChannelUpdate(APCO25Channel channel, ServiceOptions serviceOptions,
                                    IdentifierCollection ic, MacOpcode macOpcode,
                                    long timestamp, String context)
```

### Step 6: Add Pool API to P25TrafficChannelManager

New simplified methods for the call manager to use:

```java
/**
 * Allocate a Phase 1 traffic channel from the pool for the given frequency.
 * Sets up the channel and requests start via event bus.
 * Returns the allocated Channel, or null if pool is exhausted.
 */
public Channel allocatePhase1TrafficChannel(APCO25Channel apco25Channel,
                                             IdentifierCollection ic,
                                             long timestamp)

/**
 * Allocate a Phase 2 traffic channel from the pool.
 */
public Channel allocatePhase2TrafficChannel(APCO25Channel apco25Channel,
                                             IdentifierCollection ic,
                                             long timestamp)

/**
 * Release a traffic channel back to the pool.
 * Tears down the processing chain and returns the channel to the available queue.
 */
public void releaseTrafficChannel(long frequency, int timeslot)

/**
 * Check if a traffic channel is already allocated at the given frequency.
 */
public boolean isTrafficChannelAllocated(long frequency)
```

These methods extract the pool allocation logic from the existing
`processPhase1ControlChannelGrant()` and `processPhase2ChannelGrant()` methods.
They use the existing `requestTrafficChannelStart()` internally.

The call manager acquires the traffic manager's lock when calling these methods,
OR the methods themselves acquire the lock (simpler — they already have mLock).

### Step 7: Add DecodeEvent Broadcasting to P25CallSessionManager

The call manager needs to broadcast P25ChannelGrantEvents to the Events tab for backward
compatibility. Add a `Listener<IDecodeEvent>` reference:

```java
private Listener<IDecodeEvent> mDecodeEventListener;

public void setDecodeEventListener(Listener<IDecodeEvent> listener)
public void broadcastDecodeEvent(DecodeEvent event)
```

When the call manager creates a session from a control channel grant, it also creates
a P25ChannelGrantEvent and broadcasts it through this listener. The event carries
`ChannelSourceType.CONTROL`.

### Step 8: Redirect P25P1DecoderState

Change the two core private methods:

```java
// processControlTrafficGrant() — BEFORE:
mTrafficChannelManager.processP1ControlDirectedChannelGrant(channel, serviceOptions, mic, opcode, timestamp, context);

// processControlTrafficGrant() — AFTER:
P25CallSessionManager callManager = mTrafficChannelManager.getCallSessionManager();
callManager.processChannelGrant(channel, serviceOptions, mic, opcode, timestamp, context);

// processControlAnnouncedTrafficUpdate() — BEFORE:
mTrafficChannelManager.processP1ControlAnnouncedTrafficUpdate(channel, serviceOptions, mic, opcode, timestamp, context);

// processControlAnnouncedTrafficUpdate() — AFTER:
P25CallSessionManager callManager = mTrafficChannelManager.getCallSessionManager();
callManager.processChannelUpdate(channel, serviceOptions, mic, opcode, timestamp, context);
```

Also redirect Phase 2 control channel grants (where P25P2DecoderState calls
`mTrafficChannelManager.processP2ChannelGrant()` and `processP2ChannelUpdate()`).

### Step 9: Add Traffic-Side Forwarding to Call Manager

In P25TrafficChannelManager, modify existing traffic-side methods to forward session
updates after their existing tracker logic:

```java
// In processP1TrafficCallEnd():
// ... existing tracker complete logic ...
if(mCallSessionManager != null)
{
    mCallSessionManager.onTrafficChannelEnd(frequency, P25P1Message.TIMESLOT_1, timestamp);
}

// In processP1TrafficCurrentUser():
// ... existing tracker update logic ...
if(mCallSessionManager != null)
{
    mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1, ic, timestamp);
}

// In processP1TrafficCallStart():
// ... existing tracker logic ...
if(mCallSessionManager != null)
{
    mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1, mic, timestamp);
}
```

Tag all traffic-originated DecodeEvents with `ChannelSourceType.TRAFFIC`.

### Step 10: Remove Passive Observer Pattern

Remove from P25TrafficChannelManager.broadcast():
```java
// REMOVE this line — call manager no longer observes DecodeEvents passively
if(mCallSessionManager != null)
{
    mCallSessionManager.onDecodeEvent(decodeEvent, System.currentTimeMillis());
}
```

The call manager now receives events directly:
- Control events via `processChannelGrant()` / `processChannelUpdate()` 
- Traffic events via `onTrafficChannelUpdate()` / `onTrafficChannelEnd()`

### Step 11: Wire Up References

In P25TrafficChannelManager constructor:
```java
mCallSessionManager = new P25CallSessionManager();
mCallSessionManager.setTrafficChannelManager(this);  // NEW: bidirectional
mCallSessionManager.setAliasList(mAliasList);         // NEW: for filtering
mCallSessionManager.setIgnoreDataCalls(mIgnoreDataCalls);
mCallSessionManager.setIgnoreEncryptedCalls(mIgnoreEncryptedCalls);
mCallSessionManager.setIgnoreUnmonitoredCalls(mIgnoreUnmonitoredCalls);
```

In `P25TrafficChannelManager.setAliasList()` — also forward to call manager.

The DecodeEvent listener wiring happens in P25TrafficChannelManager or during channel
setup — the call manager needs the same `Listener<IDecodeEvent>` that the traffic
manager currently has.

### Step 12: Remove Deprecated Control-Channel Methods from Traffic Manager

After migration is complete, remove:
- `processP1ControlDirectedChannelGrant()`
- `processP1ControlAnnouncedTrafficUpdate()`
- `processPhase1ControlChannelGrant()` (private, ~150 lines)
- `processPhase2ChannelGrant()` (private, ~120 lines — control path only)
- `processP2ChannelGrant()` (public entry for control channel)
- `processP2ChannelUpdate()` (public entry for control channel)

This removes ~400 lines from the traffic manager.

### Step 13: Build Verification + Runtime Test

1. `gradlew compileJava` — verify compilation
2. `gradlew run` — start P25 channel, observe:
   - Normal call: control grant → call manager → allocates traffic channel → audio plays
   - Events tab shows events tagged CONTROL and TRAFFIC
   - Calls tab shows sessions populated from call manager
   - Call termination: traffic channel released, session finalized

### Step 14: Documentation + Changelog

- Write `doc/changes/010_phase3_call_manager_authority.md`
- Update `CHANGELOG_FORK.md`

---

## Method Migration Map

### Control Channel Methods: Traffic Manager → Call Manager

| Current Method (P25TrafficChannelManager) | New Method (P25CallSessionManager) |
|---|---|
| `processP1ControlDirectedChannelGrant(APCO25Channel, ServiceOptions, IC, Opcode, timestamp, context)` | `processChannelGrant(APCO25Channel, ServiceOptions, IC, Opcode, timestamp, context)` |
| `processP1ControlAnnouncedTrafficUpdate(APCO25Channel, ServiceOptions, IC, Opcode, timestamp, context)` | `processChannelUpdate(APCO25Channel, ServiceOptions, IC, Opcode, timestamp, context)` |
| `processPhase1ControlChannelGrant()` (private) | Logic absorbed into `processChannelGrant()` |
| `processPhase2ChannelGrant()` (private, control path) | Logic absorbed into `processP2ChannelGrant()` |
| `processP2ChannelGrant()` | `processP2ChannelGrant()` |
| `processP2ChannelUpdate()` | `processP2ChannelUpdate()` |
| `getEventType(Opcode, ...)` | `getEventType(Opcode, ...)` |
| `getEventType(MacOpcode, ...)` | `getEventType(MacOpcode, ...)` |
| `isUnmonitored(IC)` | `isUnmonitored(IC)` |

### Traffic-Side Methods: Stay in Traffic Manager + Forward

| Method (stays in P25TrafficChannelManager) | Addition |
|---|---|
| `processP1TrafficCallStart()` | + forward `onTrafficChannelUpdate()` |
| `processP1TrafficCurrentUser()` (both) | + forward `onTrafficChannelUpdate()` |
| `processP1TrafficLDU1()` | + forward `onTrafficChannelUpdate()` |
| `processP1TrafficCallEnd()` | + forward `onTrafficChannelEnd()` |
| `processP2TrafficCurrentUser()` (both) | + forward `onTrafficChannelUpdate()` |
| `processP2TrafficCallEnd()` | + forward `onTrafficChannelEnd()` |
| `processP2TrafficEndPushToTalk()` | + forward `onTrafficChannelEnd()` |
| `processP2TrafficVoice()` | + forward `onTrafficChannelUpdate()` |
| `processP2DataChannel()` | stays as-is |

### New Pool API (P25TrafficChannelManager)

| New Method | Description |
|---|---|
| `allocatePhase1TrafficChannel(APCO25Channel, IC, timestamp)` | Extract from processPhase1ControlChannelGrant |
| `allocatePhase2TrafficChannel(APCO25Channel, IC, timestamp)` | Extract from processPhase2ChannelGrant |
| `releaseTrafficChannel(frequency, timeslot)` | New: returns channel to pool |
| `isTrafficChannelAllocated(frequency)` | Wraps mAllocatedTrafficChannelMap.containsKey |

---

## Files Modified

| # | File | Change | Lines (est) |
|---|------|--------|-------------|
| 1 | **NEW** `ChannelSourceType.java` | New enum: CONTROL, TRAFFIC, UNKNOWN | ~15 |
| 2 | `CallSessionEvent.java` | Add channelSourceType field | ~10 |
| 3 | `P25ChannelGrantEvent.java` | Add channelSourceType to builder | ~15 |
| 4 | `P25CallSessionManager.java` | Add grant processing, filtering, event type, DecodeEvent broadcasting | ~300 |
| 5 | `P25TrafficChannelManager.java` | Add pool API, add traffic forwarding, remove control-channel methods | ~-200 net |
| 6 | `P25P1DecoderState.java` | Redirect 2 methods to call manager | ~10 |
| 7 | `CallSession.java` | Add traffic channel allocation tracking | ~15 |

**Net change:** ~+165 new lines (300 added to call manager, 200 removed from traffic manager, ~65 misc)

---

## Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| Breaking call flow during migration | Migrate one grant type at a time. Keep traffic manager's old methods until verified. |
| Thread safety between managers | Pool API methods acquire mLock internally. Call manager doesn't need its own lock for pool calls. |
| Missing Events tab entries | Call manager broadcasts P25ChannelGrantEvents through same listener. |
| Traffic-side regressions | Traffic-side methods unchanged except for adding forwarding lines. |
| DecodeEvent backward compatibility | P25ChannelGrantEvent creation logic preserved, just moved to call manager. |

---

## What This Does NOT Cover (Future Phases)

- Audio routing through CallSession
- Recording integration with CallSession
- Full removal of P25TrafficChannelEventTracker (still used for traffic-side)
- Phase 2 TDMA timeslot-aware grant processing refinements
- PatchGroupManager bug fix (independent)
