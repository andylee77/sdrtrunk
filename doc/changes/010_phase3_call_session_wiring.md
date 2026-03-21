# 010 Phase 3: Call Session Manager — Direct Wiring

**Date:** 2026-03-21
**Branch:** plutosdr
**Status:** Complete

## Summary

Phase 3 completes the call session management architecture by replacing the passive observer
pattern (Phase 2) with direct, authoritative call routing through the P25CallSessionManager.
Control channel grants/updates from decoder states now flow directly to the call session
manager, and traffic channel updates are forwarded from the traffic manager's existing
methods.

## Changes Made

### P25ChannelGrantEvent.java
- Added `channelSourceType` field to the builder pattern
- Builder propagates `ChannelSourceType` to the built event via `setChannelSourceType()`

### P25TrafficChannelManager.java
- **Constructor wiring:** Call session manager now receives `setTrafficChannelManager()`,
  `setIgnoreDataCalls()`, `setIgnoreEncryptedCalls()`, `setIgnoreUnmonitoredCalls()` at
  construction time
- **setAliasList():** Forwards alias list to call session manager
- **addDecodeEventListener():** Wires decode event listener to call session manager so it
  can broadcast P25ChannelGrantEvents for the Events tab
- **broadcast():** Removed passive observer call to `mCallSessionManager.onDecodeEvent()`.
  The call session manager now receives events via dedicated methods.
- **Traffic-side forwarding (Step 9):** Added `mCallSessionManager.onTrafficChannelUpdate()`
  calls to: `processP2TrafficCurrentUser(Identifier)`, `processP2TrafficVoice()`
- **Traffic-side end forwarding:** Added `mCallSessionManager.onTrafficChannelEnd()` calls
  to: `processP2TrafficCallEnd()`, `processP2TrafficEndPushToTalk()`
- **convertPhase2ToPhase1Channel():** Changed from package-private to `public static` so
  call session manager (different package) can access it

### P25P1DecoderState.java
- **processControlTrafficGrant():** Changed from
  `mTrafficChannelManager.processP1ControlDirectedChannelGrant()` to
  `mTrafficChannelManager.getCallSessionManager().processChannelGrant()`
- **processControlAnnouncedTrafficUpdate():** Changed from
  `mTrafficChannelManager.processP1ControlAnnouncedTrafficUpdate()` to
  `mTrafficChannelManager.getCallSessionManager().processChannelUpdate()`

### P25P2DecoderState.java
- All 17 calls to `mTrafficChannelManager.processP2ChannelGrant()` and
  `mTrafficChannelManager.processP2ChannelUpdate()` redirected to go through
  `mTrafficChannelManager.getCallSessionManager().processP2ChannelGrant()` and
  `mTrafficChannelManager.getCallSessionManager().processP2ChannelUpdate()`

## Architecture After Phase 3

```
Control Channel Messages
    → DecoderState (P25P1/P25P2)
        → CallSessionManager.processChannelGrant/Update()  ← NEW: direct routing
            → Creates/updates CallSession
            → Delegates to TrafficChannelManager pool API for channel allocation
            → Broadcasts P25ChannelGrantEvent to Events tab
            → Notifies CallLogWriter for SQLite persistence

Traffic Channel Messages
    → DecoderState
        → TrafficChannelManager.processP*Traffic*()  ← existing methods
            → Manages tracker/event lifecycle
            → Forwards to CallSessionManager.onTrafficChannelUpdate/End()  ← NEW
            → Broadcasts events to Events tab
```

## Data Flow Summary

| Signal Source | Before Phase 3 | After Phase 3 |
|---|---|---|
| Control grants | DecoderState → TCM directly | DecoderState → CSM → TCM pool API |
| Control updates | DecoderState → TCM directly | DecoderState → CSM → TCM pool API |
| Traffic voice/user | DecoderState → TCM → passive observe | DecoderState → TCM → CSM.onTrafficChannelUpdate() |
| Traffic end | DecoderState → TCM → passive observe | DecoderState → TCM → CSM.onTrafficChannelEnd() |

## Notes

- The deprecated control-channel methods (`processP1ControlDirectedChannelGrant`,
  `processP1ControlAnnouncedTrafficUpdate`, `processP2ChannelGrant`, `processP2ChannelUpdate`)
  remain in P25TrafficChannelManager for now but are no longer called from decoder states.
  They can be removed in a future cleanup pass.
- ~~Phase 1 traffic-side methods do not yet forward to the call session manager~~ — **Fixed
  2026-03-21:** All P1 traffic methods now forward to CallSessionManager (see bug fixes below).

## Phase 3 Bug Fixes (2026-03-21)

**Bug 1: Duration incorrect on Calls tab for Phase 1 systems**
- **Root cause:** P1 traffic channel methods (`processP1TrafficCallStart`, `processP1TrafficCurrentUser`,
  `processP1TrafficLDU1`, `processP1TrafficCallEnd`) did not forward to CallSessionManager. Duration was
  only being extended by control channel grant update messages (every few seconds), not by traffic channel
  voice frames.
- **Fix:** Added `mCallSessionManager.onTrafficChannelUpdate()` calls to all 5 P1 traffic methods and
  `mCallSessionManager.onTrafficChannelEnd()` to `processP1TrafficCallEnd()` in `P25TrafficChannelManager`.

**Bug 2: History doesn't reload when switching channels and back**
- **Root cause:** `CallSessionModel.clear()` always wrapped in `EventQueue.invokeLater()`, causing the
  actual clear to be deferred even when already on the EDT. This could create timing issues with the
  subsequent `reloadHistory()` call.
- **Fix:** `CallSessionModel.clear()` now checks `EventQueue.isDispatchThread()` and executes immediately
  when already on the EDT via `clearImmediate()`.
