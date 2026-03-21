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

**Bug 3: Patch group members opening multiple traffic channels (2026-03-21 v2)**
- **Root cause:** The cross-frequency patch group matching in `processPhase1Grant()` and
  `processP2ChannelGrantInternal()` only worked when the TO identifier had been resolved to a
  `PatchGroupIdentifier` by the `PatchGroupManager`. When initial grants arrived for patch member TGs
  BEFORE the Motorola regroup TSBK defined the patch group (or after the 30s freshness expired), the
  TGs stayed as regular `TalkgroupIdentifier` instances. The `instanceof PatchGroupIdentifier` check
  failed, causing each member TG on each frequency to get its own session and traffic channel allocation.
- **Fix:** Replaced the `PatchGroupIdentifier`-only cross-frequency check with a unified
  `findCrossFrequencySession()` method that performs 3 checks:
  1. **PatchGroupIdentifier**: supergroup or member TG overlap with any session's seenTalkgroups
  2. **TalkgroupIdentifier**: TG value already tracked in any active session's seenTalkgroups
  3. **Radio affinity**: same FROM radio active in another session within 5 seconds
  This catches patch member grants even when the PatchGroupManager hasn't resolved them yet.
- **Files changed:** `P25CallSessionManager.java`

**Bug 8: Sessions never end — duration grows across separate calls on same TG/freq**
- **Root cause:** `onTrafficChannelEnd()` was changed (Bug 7 fix) to NOT transition to ENDING,
  to prevent splitting. But this meant sessions stayed in `mActiveSessions` indefinitely, kept
  alive by periodic control channel grant updates. When a NEW call started on the same
  talkgroup/frequency, `isMatch()` returned true (same TG, same freq) and the existing session's
  duration kept growing — merging distinct calls into one ever-growing row. Traffic channels also
  appeared stuck as ACTIVE in the Now Playing panel because the session never ended.
- **Fix:** Restored `transitionToEnding()` in `onTrafficChannelEnd()` so TDU properly moves the
  session from `mActiveSessions` to `mEndingSessions`. Added `reactivateFromEnding()` method that
  checks `mEndingSessions` when a grant arrives and no active session exists on that freq/timeslot.
  If a matching ENDING session is found (same TG within gap tolerance), it's reactivated back to
  ACTIVE — this handles PTT releases within a group call where the control channel continues
  sending grants. If no matching session is found or the gap tolerance expired, a new session is
  created — this properly separates distinct calls. Wired reactivation into all three entry points:
  `processChannelUpdate()`, `processPhase1Grant()`, and `processP2ChannelGrantInternal()`.
- **Files changed:** `P25CallSessionManager.java`

**Bug 4: Calls splitting into many short rows on Calls tab**
- **Root cause:** Direct consequence of Bug 3. Each failed cross-frequency match created a new session,
  and when the next grant arrived for a different TG on the same frequency, `transitionToEnding()` was
  called on the old session, creating many 0.0-2.8 second rows instead of one continuous call.
- **Fix:** Resolved by the same `findCrossFrequencySession()` fix in Bug 3. Member TG grants now update
  the existing session instead of creating new ones.

**Bug 5: "TS1" showing in Channel column for Phase 1 channels**
- **Root cause:** `processPhase1Grant()` used `P25P1Message.TIMESLOT_1` (= 1) as the timeslot when
  creating `CallSessionEvent` objects. `CallSessionEvent.hasTimeslot()` returned true for timeslot > 0,
  and `CallSessionModel.formatChannel()` displayed "TS" + timeslot when `hasTimeslot()` was true. For
  Phase 1 FDMA channels, the timeslot is meaningless (always 1).
- **Fix:** Changed `createSessionEvent()` call in `processPhase1Grant()` to pass timeslot `0` for the
  display event (the internal session key still uses `TIMESLOT_1` for map lookups). This causes
  `hasTimeslot()` to return false and the Channel column displays just the channel descriptor (e.g.,
  "0-949" instead of "0-949 TS1").
- **Files changed:** `P25CallSessionManager.java`

**Bug 6: Calls disappear when clicking away from channel and back**
- **Root cause:** `CallSessionPanel.receive()` cleared the model and only registered for future events
  via `addListener()`. There was no backfill of existing active session events, so all live rows were
  lost when switching channels and returning.
- **Fix:** Added `getActiveSessionEvents()` method to `P25CallSessionManager` that returns all
  `CallSessionEvent` objects from active and ending sessions. Added `backfillEvents()` method to
  `CallSessionModel` that bulk-inserts events (sorted newest-first, deduped by identity). Updated
  `CallSessionPanel.receive()` to call `mModel.backfillEvents(activeEvents)` immediately after
  registering as a listener.
- **Files changed:** `P25CallSessionManager.java`, `CallSessionModel.java`, `CallSessionPanel.java`

**Bug 7: Calls still splitting into many short rows — per-talker event creation**
- **Root cause:** `updateSessionFromGrant()` used `isSameTalker()` to decide whether to create a new
  `CallSessionEvent` row. Every time the FROM radio changed (e.g., 03599042 → null → 00001014),
  it treated the change as a different talker and created a new row. Since control channel TSBKs
  alternate between having a FROM radio ID and not having one, this split one continuous call into
  5-10+ short-duration rows. The upstream `P25TrafficChannelManager` avoids this by using
  `isSameCallCheckingToOnly()` which only checks the TO talkgroup and consolidates everything
  into one event.
- **Fix:** Changed `updateSessionFromGrant()` to always update the existing event — never create
  new per-talker events. The FROM radio is updated when a new one is identified but preserved
  when subsequent updates arrive without one (null FROM). Also fixed `onTrafficChannelUpdate()`
  to protect the FROM radio identity from being overwritten by null-FROM traffic updates. Both
  methods now only update the `IdentifierCollection` when the incoming IC has richer info (has
  a FROM radio) or when the current event has no FROM radio yet.
- **Files changed:** `P25CallSessionManager.java`
