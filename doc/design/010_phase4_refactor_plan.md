# Phase 4: Unified Event Architecture — Refactoring Plan

**Date:** 2026-03-21
**Status:** PLANNING
**Problem:** Events and Calls tabs are getting corrupted because two systems handle event state in parallel

## Problem Statement

After Phase 3 (call session wiring), we have TWO parallel event handling systems:

1. **P25TrafficChannelManager** (upstream code + our additions) — has P25TrafficChannelEventTrackers,
   creates P25ChannelGrantEvents for the Events tab, manages event lifecycle, does filtering
   (encrypted/unmonitored/data), and allocates traffic channels.

2. **P25CallSessionManager** (our code) — ALSO creates events (CallSessionEvents for Calls tab),
   ALSO does filtering, ALSO creates P25ChannelGrantEvents for Events tab, ALSO manages lifecycle.

Both receive the same control channel messages (via P25P1DecoderState), both try to track state,
and they get out of sync. The result:
- Events tab shows "Group Call" when it should show "Encrypted Group Call"
- Events tab shows duplicate rows or missing rows
- Calls tab shows IGNORED on some items but not others
- Encryption detection from traffic channel doesn't propagate correctly to both systems

## Root Cause

The Phase 3 design tried to make P25CallSessionManager the "authority" for grants while keeping
P25TrafficChannelManager's event tracking alive. This created a dual-authority problem:

```
P25P1DecoderState
  ├── calls TCM.processP1ControlDirectedChannelGrant()     ← TCM creates tracker + event
  │     └── TCM internally calls CSM.processChannelGrant()  ← CSM ALSO creates session + event
  │
  ├── calls TCM.processP1ControlAnnouncedTrafficUpdate()    ← TCM updates tracker
  │     └── TCM internally calls CSM.processChannelUpdate()  ← CSM ALSO updates session
  │
  └── Traffic channel messages forwarded by TCM to CSM       ← CSM updates, TCM also updates
```

Both systems maintain independent state (trackers vs sessions), create independent events,
and apply independent filtering. When one upgrades to encrypted and the other doesn't,
the tabs show conflicting information.

## Solution: Clean Separation of Concerns

### Architecture After Refactoring

```
P25P1DecoderState (control channel)
  ├── calls CSM.processChannelGrant()      ← CSM is SOLE authority for events
  ├── calls CSM.processChannelUpdate()
  └── patch group updates → CSM.onPatchGroupUpdate()

P25TrafficChannelManager (JUST a channel pool)
  ├── allocatePhase1TrafficChannel()        ← Pool API only
  ├── allocatePhase2TrafficChannel()
  ├── releaseTrafficChannel()
  ├── isTrafficChannelAllocated()
  ├── processFrequencyBand()                ← Frequency band management
  ├── processControlFrequencyUpdate()       ← Control channel tracking
  └── TrafficChannelTeardownMonitor         ← Channel lifecycle (start/stop/reject)

P25CallSessionManager (SOLE event authority)
  ├── processChannelGrant()                 ← Creates session + P25ChannelGrantEvent
  ├── processChannelUpdate()                ← Updates session + event
  ├── onTrafficChannelUpdate()              ← Receives traffic-side updates
  ├── onTrafficChannelEnd()                 ← Receives traffic-side end
  ├── Filtering (encrypted/unmonitored/data)
  ├── Encryption upgrade from traffic channel
  ├── Patch group enrichment + consolidation
  ├── Events tab broadcasting (P25ChannelGrantEvent)
  └── Calls tab broadcasting (CallSessionEvent)
```

### Key Principle: ONE event object per call

The upstream TCM's tracker pattern works well: one P25ChannelGrantEvent object is reused
across the entire call lifetime. ClearableHistoryModel.add() uses object identity (contains())
to decide update vs new row. The CSM already implements this with mActiveControlEvents.

## Implementation Steps

### Step 1: Reset P25TrafficChannelManager to Upstream + Pool API

1. **Get clean upstream copy:**
   ```
   git show upstream/master:src/main/java/.../P25TrafficChannelManager.java > TCM_upstream.java
   ```

2. **Strip ALL event tracking / grant processing from TCM:**
   - Remove: `processP1ControlDirectedChannelGrant()` (event creation part)
   - Remove: `processP1ControlAnnouncedTrafficUpdate()` (event tracking part)
   - Remove: `processP2ChannelGrant()` (event creation part)
   - Remove: `processP2ChannelUpdate()` (event tracking part)
   - Remove: `processPhase1ControlChannelGrant()` (internal)
   - Remove: `processPhase2ChannelGrant()` (internal)
   - Remove: `getEventType()` methods
   - Remove: `isUnmonitored()` (move to CSM)
   - Remove: mIgnoreDataCalls, mIgnoreEncryptedCalls, mIgnoreUnmonitoredCalls filtering
   - Remove: `broadcast(DecodeEvent)`, `broadcast(P25TrafficChannelEventTracker)`

3. **Keep in TCM (channel pool management):**
   - mAvailablePhase1TrafficChannelQueue, mAvailablePhase2TrafficChannelQueue
   - mAllocatedTrafficChannelMap
   - mManagedPhase1TrafficChannels, mManagedPhase2TrafficChannels
   - createPhase1TrafficChannels(), createPhase2TrafficChannels()
   - allocatePhase1TrafficChannel(), allocatePhase2TrafficChannel()
   - releaseTrafficChannel()
   - isTrafficChannelAllocated()
   - requestTrafficChannelStart()
   - processControlFrequencyUpdate()
   - processFrequencyBand(), mFrequencyBandMap
   - TrafficChannelTeardownMonitor (channel lifecycle)
   - mPhase2ScrambleParameters, getPhase2ScrambleParameters()
   - mCallSessionManager reference (for forwarding traffic-side messages)
   - mCallLogWriter (persistence)
   - start(), stop(), reset()
   - IChannelEventListener, IChannelEventProvider implementations

4. **Keep TCM's traffic-side forwarding methods** (thin wrappers that just forward to CSM):
   - processP1TrafficCallStart() → forward to CSM.onTrafficChannelUpdate()
   - processP1TrafficCurrentUser() → forward to CSM.onTrafficChannelUpdate()
   - processP1TrafficLDU1() → forward to CSM.onTrafficChannelUpdate()
   - processP1TrafficCallEnd() → forward to CSM.onTrafficChannelEnd()
   - processP2TrafficCurrentUser() → forward to CSM.onTrafficChannelUpdate()
   - processP2TrafficVoice() → forward to CSM.onTrafficChannelUpdate()
   - processP2TrafficCallEnd() → forward to CSM.onTrafficChannelEnd()
   - processP2TrafficEndPushToTalk() → forward to CSM.onTrafficChannelEnd()

   These stay in TCM because the traffic channel DecoderStates call them directly.
   They become pure pass-throughs — no tracker management, no event creation.

5. **Remove P25TrafficChannelEventTracker entirely** from TCM (CSM replaces it).

### Step 2: Rewire P25P1DecoderState

Currently P25P1DecoderState calls TCM methods for grants/updates. Rewire to call CSM directly:

**Before:**
```java
mTrafficChannelManager.processP1ControlDirectedChannelGrant(channel, so, ic, opcode, ts);
mTrafficChannelManager.processP1ControlAnnouncedTrafficUpdate(channel, so, ic, opcode, ts);
```

**After:**
```java
mCallSessionManager.processChannelGrant(channel, so, ic, opcode, ts, context);
mCallSessionManager.processChannelUpdate(channel, so, ic, opcode, ts, context);
```

Same for P25P2DecoderState if applicable.

**Access pattern:** P25P1DecoderState already has access to TCM. Add a getter:
```java
mCallSessionManager = mTrafficChannelManager.getCallSessionManager();
```

### Step 3: Make CSM the SOLE IDecodeEventProvider

Currently TCM implements IDecodeEventProvider. After refactoring:
- TCM still implements IDecodeEventProvider but delegates to CSM
- `addDecodeEventListener(listener)` → `mCallSessionManager.setDecodeEventListener(listener)`
- CSM broadcasts P25ChannelGrantEvents directly

### Step 4: Update P25P2DecoderState Similarly

P25P2DecoderState also calls TCM grant/update methods. Same rewiring:
- `processP2ChannelGrant()` → CSM.processP2ChannelGrant()
- `processP2ChannelUpdate()` → CSM.processP2ChannelUpdate()

### Step 5: Verify Traffic-Side Flow

Traffic-side messages from P25P1DecoderState (on traffic channels) call:
- `processP1TrafficCallStart()` → TCM forwards to CSM
- `processP1TrafficCurrentUser()` → TCM forwards to CSM
- `processP1TrafficLDU1()` → TCM forwards to CSM
- `processP1TrafficCallEnd()` → TCM forwards to CSM

These stay in TCM because traffic channel DecoderStates are created dynamically
and receive TCM as their traffic channel manager. The forwarding is already in place
from Phase 3 — just need to remove the tracker management from these methods.

## What Stays the Same

- **CallSession, CallSessionEvent, CallState** — unchanged
- **CallSessionModel, CallSessionPanel** — unchanged (Calls tab)
- **DecodeEventModel, ClearableHistoryModel** — unchanged (Events tab)
- **P25ChannelGrantEvent** — unchanged (used by both tabs)
- **PatchGroupManager** — unchanged
- **CallLogWriter, CallLogDatabase** — unchanged
- **DuplicateCallDetector** — unchanged (audio dedup)

## Risk Mitigation

1. **Before starting:** Create a git branch `phase4-refactor` from current `plutosdr`
2. **Incremental approach:** Do one step at a time, compile and test between each
3. **Phase 2 channels:** These seem to work — be careful not to break them
4. **Fallback:** If refactoring goes wrong, `plutosdr` branch still has the Phase 3 code

## Files Modified

| File | Change |
|------|--------|
| P25TrafficChannelManager.java | Strip to pool-only + thin traffic forwarding |
| P25CallSessionManager.java | Already has most logic — just verify completeness |
| P25P1DecoderState.java | Rewire grant/update calls to CSM |
| P25P2DecoderState.java | Rewire grant/update calls to CSM (if needed) |
| P25TrafficChannelEventTracker.java | May be removed entirely or kept for compatibility |

## Success Criteria

- [ ] Events tab shows correct event types (Group Call, Encrypted Group Call, Patch Group Call)
- [ ] Events tab shows "IGNORED: ENCRYPTED CALL" for all encrypted calls when filtering enabled
- [ ] Events tab shows "IGNORED: UNMONITORED CALL" for all unmonitored calls
- [ ] Calls tab shows matching information (not contradicting Events tab)
- [ ] Encryption detected on traffic channel upgrades BOTH Events and Calls tabs
- [ ] Patch group calls don't open duplicate traffic channels
- [ ] Traffic channels are properly allocated and released
- [ ] No duplicate event rows in Events tab
- [ ] Duration updates work correctly for ongoing calls
- [ ] Phase 2 channels continue to work correctly

## Timeline Estimate

- Step 1 (TCM reset): ~2 hours — most complex step
- Step 2 (P25P1DecoderState rewire): ~30 min
- Step 3 (IDecodeEventProvider): ~15 min  
- Step 4 (P25P2DecoderState): ~30 min
- Step 5 (Verification): ~1 hour testing
- Total: ~4-5 hours
