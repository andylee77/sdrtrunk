# 014: Fix TDU Immediate Channel Disable

## Problem

When a TDU (Terminator Data Unit) was received on a traffic channel, our fork immediately disabled the traffic channel via `REQUEST_DISABLE`. This caused a cascade of problems:

1. **TrafficChannelTeardownMonitor** received the stop notification and removed the tracker from the map
2. The control channel continued sending `GROUP_VOICE_CHANNEL_GRANT_UPDATE` messages (which only carry TO, no FROM)
3. With no tracker in the map, the update created a **new tracker with FROM=null**
4. A **new traffic channel** was allocated from the pool for a call that was probably already over
5. The new channel often couldn't start in time → got torn down → creating orphan events
6. This cycle repeated, creating multiple split events per call with missing FROM identifiers

## Root Cause

We added immediate channel disable logic in `processP1TrafficCallEnd` and `processP2TrafficCallEnd` that does not exist in upstream:

```java
// WE ADDED (now removed):
channelToDisable = mAllocatedTrafficChannelMap.get(frequency);
...
broadcast(new ChannelEvent(channelToDisable, Event.REQUEST_DISABLE));
```

**Upstream behavior**: TDU marks the event complete (`completeTraffic`) and broadcasts it, but the traffic channel stays allocated and running. The control channel's stale timeout (`STALE_EVENT_THRESHOLD_MS = 2s`) handles cleanup when no more grants arrive.

## Fix

Reverted both `processP1TrafficCallEnd` and `processP2TrafficCallEnd` to match upstream behavior:
- Mark the tracker as complete via `completeTraffic(timestamp)`
- Broadcast the updated event
- **Do NOT** disable the traffic channel
- Let the control channel stale timeout handle cleanup naturally

## How It Works Now

1. **TDU received** → tracker marked `complete=true`, event status = ENDED, broadcast
2. **Control channel GRANT_UPDATE arrives** → finds the tracker still in the map → `isSameCallCheckingToOnly` returns true (same TO) → enters SAME_CALL path → `updateDurationControl` is a no-op (traffic already started) → no new tracker, no new channel allocation
3. **If same radio keys up again** → new HDU/LDU arrives on the still-running traffic channel → the tracker is enriched with new identifiers → seamless continuation
4. **If no more grants arrive** → after 2s, `getTrackerRemoveIfStale` removes the tracker; traffic channel stops naturally when it has no more data to decode

## Files Changed

- `P25TrafficChannelManager.java` — removed immediate `REQUEST_DISABLE` from both `processP1TrafficCallEnd` and `processP2TrafficCallEnd`

## Impact

- Eliminates FROM=null tracker creation from GRANT_UPDATE messages
- Eliminates orphan ACTIVE_CONTROL events that get immediately torn down
- Eliminates event splitting (one event per call instead of ENDED + FROM=null + removal)
- Eliminates unnecessary traffic channel pool churn
- Traffic channels stay allocated longer (until stale timeout) but this matches upstream behavior
