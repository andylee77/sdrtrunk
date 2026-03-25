# Change 013: Call Session and Event Fixes

## Date: 2026-03-21

## Problem

Two issues observed during live testing:

1. **Duplicate Events in Events tab**: When a call's FROM radio changes (talker changeover), the
   Events tab showed 4 events instead of the expected 2. The root cause was a race condition between
   the control channel and traffic channel — both independently created new P25ChannelGrantEvent
   objects for the same call, resulting in duplicate rows in the Events table.

2. **Double CSM call in processP1ControlAnnouncedTrafficUpdate**: When the traffic update detected
   a different call (new TG on same frequency), it called `processP1ControlDirectedChannelGrant`
   (which internally calls CSM.processChannelGrant) AND then also called CSM.processChannelUpdate.
   This resulted in the call session manager receiving two notifications for the same event.

## Root Cause Analysis

### Duplicate Events
- `processP1TrafficCurrentUser(long, IChannelDescriptor, ...)` would create a brand new
  `P25ChannelGrantEvent` whenever `isSameCallCheckingToAndFrom()` returned false — even when an
  existing tracker was present but had a different FROM identifier.
- Since `ClearableHistoryModel.add()` uses object identity via `contains()`, each new event object
  became a separate row in the Events table rather than updating the existing one.
- The control channel already handles talker changeover via `isDifferentTalker` in
  `processPhase1ControlChannelGrant`. Having the traffic channel ALSO create new events for the
  same scenario caused duplicates.

### Double CSM Call
- In `processP1ControlAnnouncedTrafficUpdate`, when a different call was detected (else branch),
  the method delegated to `processP1ControlDirectedChannelGrant` which calls
  `mCallSessionManager.processChannelGrant()`. Then back in the calling method, it unconditionally
  called `mCallSessionManager.processChannelUpdate()` — a double notification.

## Fix

### processP1TrafficCurrentUser (IChannelDescriptor overload)
- When a tracker exists but `isSameCallCheckingToAndFrom` returns false, instead of creating a new
  event, the method now updates the existing tracker's identifiers and duration. This defers new
  event creation to the control channel's `isDifferentTalker` logic.
- New event creation is now only a fallback for when NO tracker exists at all (control channel
  grant was missed).

### processP1ControlAnnouncedTrafficUpdate
- Added a `sameCall` boolean flag. The CSM.processChannelUpdate call is now conditional — only
  invoked when the update is for the same call (same TG). When a different call is detected,
  `processP1ControlDirectedChannelGrant` handles the CSM notification via processChannelGrant,
  and the redundant processChannelUpdate call is skipped.

### processP1TrafficLDU1 — removed fallback event creation
- When the tracker was complete and removed, the method previously created a brand new "PHASE 1
  CALL" event object, causing duplicate rows alongside the control channel's "PHASE 1 CHANNEL
  GRANT" events. Now the method simply returns without creating an event — the control channel
  is the sole authority for event creation.

### Ignored events not tracking properly
- In `processPhase1ControlChannelGrant`, the three ignored-call checks (data/encrypted/unmonitored)
  had `if(tracker == null)` guards that prevented new ignored events from being created when the
  old tracker was for a *different* call. Now the ignored checks always create/replace the tracker
  regardless of whether one exists, since by this point we know any existing tracker is for a
  different call.

### Stale event threshold reduced
- `STALE_EVENT_THRESHOLD_MS` in `P25TrafficChannelEventTracker` reduced from 2000ms to 500ms.
  This makes the control channel consider a tracked event stale after 500ms with no updates.

### Stale check skip for active trackers
- In `getTrackerRemoveIfStale`, when a tracker has been started by the traffic channel
  (`isStarted() && !isComplete()`), the stale check is now skipped entirely. The control
  channel's timestamps are in a different clock domain than the traffic channel's — once the
  traffic channel takes over the event lifecycle, only `completeTraffic()` (TDU) or the teardown
  monitor should end the event. This prevents false stale detection on active calls.

### Traffic channel timeout reduced
- `P25P1DecoderState` and `P25P2DecoderState` traffic channel timeout reduced from 1000ms to
  250ms. This makes the traffic channel more responsive to call end detection — when the
  traffic channel stops receiving voice frames, the decoder state fires the call end after
  250ms instead of waiting a full second.

### Logging improvements
- `CallLogWriter` and `P25CallSessionManager` session completion logging changed from TRACE to
  DEBUG level to reduce log noise at TRACE level.
- Added DEBUG logging to `P25TrafficChannelEventTracker.updateDurationTraffic()` to log when a
  tracker first starts receiving traffic channel updates (one-time per tracker lifecycle).
- Added DEBUG logging to `P25TrafficChannelManager.getTrackerRemoveIfStale()` when the stale
  check is skipped for an active tracker.

### Dead Pool API code removed from P25TrafficChannelManager
- Removed 5 dead methods that were added during Phase 3 call session wiring but never called:
  `allocatePhase1TrafficChannel()`, `allocatePhase2TrafficChannel()`, `isTrafficChannelAllocated()`,
  `releaseTrafficChannel()` (stub), and `convertPhase2ToPhase1Channel()` (public wrapper).
- These were originally intended for the CSM to perform channel allocation, but the architecture
  was revised (fix 012) to keep all channel allocation in the TCM. The CSM is purely a session
  tracking layer and never called these methods.
- The CSM's only use of the TCM back-reference is `getPhase2ScrambleParameters()` for P2 scramble
  parameter enrichment — that method is retained.
- Updated `broadcast(DecodeEvent)` javadoc to remove stale "Phase 3" reference.

### Immediate traffic channel release on call end
- `processP1TrafficCallEnd` and `processP2TrafficCallEnd` now immediately send a
  `REQUEST_DISABLE` channel event when the call is marked complete (status goes ENDED/red).
  Previously the traffic channel would remain allocated for up to 1 second waiting for the
  channel timeout to fire. Now the channel is returned to the pool immediately, freeing it
  for the next call.

### HDU tracker replacement fix (processP1TrafficCallStart)
- When the HDU arrives on the traffic channel, `processP1TrafficCallStart` previously removed
  ANY started tracker and created a new event from scratch — losing the FROM radio identifier
  that the control channel grant had provided. This caused events to show no FROM and fragment
  into short-lived repeated events (the first with FROM, then immediately ended and replaced
  with one without FROM).
- The fix checks if the existing started tracker is for the **same talkgroup** before deciding
  what to do. If same call: enrich the existing tracker with identifiers from the HDU (radio,
  encryption key) without replacing it. If different call: remove and recreate as before.
- This was an existing upstream behavior but became visible due to our EventStatus tracking
  and the `updateDurationTraffic()` call setting `mStarted=true` before the HDU arrives (via
  LCW messages on the traffic channel).

## Files Changed

| File | Change |
|------|--------|
| `P25TrafficChannelManager.java` | Fixed `processP1TrafficCurrentUser` to update existing tracker instead of creating duplicate events; fixed `processP1ControlAnnouncedTrafficUpdate` to avoid double CSM call; removed fallback event creation from `processP1TrafficLDU1`; fixed ignored event tracking; added immediate traffic channel release on call end; added stale check skip for active trackers with debug logging |
| `P25TrafficChannelEventTracker.java` | Reduced `STALE_EVENT_THRESHOLD_MS` from 2000ms to 500ms; added debug logging on tracker start |
| `P25P1DecoderState.java` | Reduced traffic channel timeout from 1000ms to 250ms |
| `P25P2DecoderState.java` | Reduced traffic cha

- **Event creation authority**: The control channel (via `processPhase1ControlChannelGrant` and
  `isDifferentTalker`) is the primary authority for creating new events when talkers change.
  Traffic channel methods should only create fallback events when no tracker exists.
- **CSM call flow**: `processP1ControlDirectedChannelGrant` → CSM.processChannelGrant (for new
  calls). `processP1ControlAnnouncedTrafficUpdate` → CSM.processChannelUpdate (for same-call
  updates only).
- **Channel release flow**: Traffic channel sends TDU → `processP1TrafficCallEnd` marks event
  ENDED → immediately sends `REQUEST_DISABLE` → `TrafficChannelTeardownMonitor` receives
  `NOTIFICATION_PROCESSING_STOP` → channel returned to pool.
