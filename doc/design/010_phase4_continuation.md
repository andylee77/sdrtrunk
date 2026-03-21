# Phase 4 Continuation: TCM Grant Processing Removal

## Status: IN PROGRESS — Needs Fresh Session

## Problem Statement

The Calls tab and Events tab show **completely different data** because two parallel systems
are processing grants independently:

1. **P25TrafficChannelManager (TCM)** — OLD system, still fully active
   - P25P1DecoderState calls `TCM.processP1ControlDirectedChannelGrant()` etc.
   - TCM creates `P25ChannelGrantEvent` objects via `broadcast()` → Events tab
   - TCM manages traffic channel allocation AND event creation
   - 36+ `broadcast()` calls creating Events tab entries

2. **P25CallSessionManager (CSM)** — NEW system, running in parallel
   - TCM forwards to CSM via `onTrafficChannelUpdate()` / `onTrafficChannelEnd()`
   - CSM ALSO processes grants via `processChannelGrant()` etc. (called from TCM)
   - CSM creates `CallSessionEvent` objects → Calls tab
   - CSM also creates its own `P25ChannelGrantEvent` objects for Events tab

**Result:** Events tab gets events from TCM's old code path. Calls tab gets events from CSM's
new code path. They don't match because:
- Different call matching logic (TCM uses `isSameCallCheckingToOnly()`, CSM uses `isMatch()`)
- Different duration tracking (TCM updates from both control and traffic, CSM may miss some)
- Different timeslot handling
- Different event type determination
- TCM's events show things CSM doesn't create sessions for

## Observed Mismatches (from screenshots)

1. **Duration mismatch** — Same call shows different durations in Calls vs Events
2. **Timeslot mismatch** — Calls shows "0-949 TS0" but Events shows "0-949 TS0" with different
   timeslot values for the same call
3. **Missing calls** — Some Events tab entries have no corresponding Calls tab entry
4. **Extra calls** — Some Calls tab entries don't match any Events tab entry
5. **Different event types** — Same call may show as "Group Call" in one and "Encrypted Group Call"
   in the other

## Architecture Goal

```
P25P1DecoderState ──→ CSM (sole authority) ──→ Events tab (P25ChannelGrantEvent)
                                            ──→ Calls tab (CallSessionEvent)
                                            ──→ TCM (pool only: allocate/release)
```

NOT what we have now:
```
P25P1DecoderState ──→ TCM (old grant processing) ──→ Events tab (P25ChannelGrantEvent)
                      │                           ──→ TCM.broadcast()
                      ├──→ CSM (parallel processing) ──→ Calls tab (CallSessionEvent)  
                      │                               ──→ Events tab (ANOTHER P25ChannelGrantEvent)
                      └──→ TCM traffic channel pool
```

## What Needs to Be Done

### Step 1: Identify ALL callers of TCM grant methods

Find every call to these TCM methods and redirect them to CSM:

- `TCM.processP1ControlDirectedChannelGrant()` → `CSM.processChannelGrant()`
- `TCM.processP1ControlAnnouncedTrafficUpdate()` → `CSM.processChannelUpdate()`
- `TCM.processP2ChannelGrant()` → `CSM.processP2ChannelGrant()`
- `TCM.processP2ChannelUpdate()` → `CSM.processP2ChannelUpdate()`

**Callers are in:**
- `P25P1DecoderState.java` — primary caller for Phase 1 grants
- `P25P2DecoderState.java` — primary caller for Phase 2 grants (if exists)
- Possibly other decoder state classes

### Step 2: Gut TCM grant processing methods

Remove the grant processing logic from these TCM methods. They should become
thin pass-through or be removed entirely:

- `processP1ControlDirectedChannelGrant()` — REMOVE (callers now go to CSM)
- `processP1ControlAnnouncedTrafficUpdate()` — REMOVE
- `processP2ChannelGrant()` — REMOVE  
- `processP2ChannelUpdate()` — REMOVE
- `broadcast(DecodeEvent)` — REMOVE (CSM handles all event broadcasting)
- `broadcast(P25TrafficChannelEventTracker)` — REMOVE
- `getCallEventType()` — REMOVE (CSM has `getEventType()`)
- `isUnmonitored()` — REMOVE (CSM has its own)

### Step 3: Keep TCM pool management methods

TCM should ONLY keep these methods that CSM calls:

- `allocatePhase1TrafficChannel(channel, ic, timestamp)` → returns Channel
- `allocatePhase2TrafficChannel(channel, ic, timestamp)` → returns Channel
- `releaseTrafficChannel(frequency)` → releases channel back to pool
- `isTrafficChannelAllocated(frequency)` → boolean check
- `convertPhase2ToPhase1Channel()` — static utility
- `getPhase2ScrambleParameters()` — configuration
- Traffic channel event listener methods (for forwarding traffic-side messages to CSM)
- Channel start/stop/disable management

### Step 4: Remove TCM's traffic-side event broadcasting

Currently TCM's traffic channel listener (`processTrafficChannelMessage`) broadcasts
events to the Events tab when traffic messages arrive. This should be removed — CSM's
`onTrafficChannelUpdate()` already handles updating both Events and Calls tabs via
`broadcastTrafficUpdate()`.

### Step 5: Verify P25TrafficChannelEventTracker can be removed

`P25TrafficChannelEventTracker` is TCM's event tracking class. If all event tracking
is now in CSM (via `CallSession` + `CallSessionEvent` + `mActiveControlEvents`), then
this class can be removed entirely. Check for any remaining references.

### Step 6: Test thoroughly

After removal:
- Events tab and Calls tab must show same calls with same durations
- Traffic channel allocation still works
- Encryption detection still works (via traffic channel → CSM.onTrafficChannelUpdate)
- Patch group detection still works
- Ignored calls (encrypted/unmonitored/data) still filtered correctly
- Channel enable/disable lifecycle still works

## Files That Need Changes

| File | Change |
|------|--------|
| `P25TrafficChannelManager.java` | Remove grant methods, broadcast, event tracking; keep pool API |
| `P25P1DecoderState.java` | Change calls from TCM to CSM |
| `P25P2DecoderState.java` | Change calls from TCM to CSM (if applicable) |
| `P25TrafficChannelEventTracker.java` | Potentially delete entire class |
| `P25CallSessionManager.java` | May need minor adjustments |
| `ChannelProcessingManager.java` | May need CSM wiring adjustments |

## Risk Assessment

- **HIGH RISK**: This is the core call processing pipeline. Wrong changes break ALL decoding.
- **Mitigation**: The `phase4-refactor` branch isolates this work. `plutosdr` branch is safe.
- **Rollback**: `git checkout plutosdr` restores the working (pre-Phase 4) state.

## Branch State

- Branch: `phase4-refactor` (off `plutosdr`)
- Latest commit: `92ab32dd` — hasTimeslot() fix
- All Phase 4 cosmetic fixes committed
- Core architectural work (this plan) NOT yet started
