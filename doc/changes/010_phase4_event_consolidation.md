# Change 010 Phase 4: Event Broadcasting Consolidation

**Date:** 2026-03-21
**Branch:** phase4-refactor (from plutosdr)
**Status:** Complete

## Summary

Eliminated dual-broadcasting of call events to the Events tab. Previously, both the
P25TrafficChannelManager (TCM) and P25CallSessionManager (CSM) could broadcast events
for the same call, causing duplicate rows and confusing duration updates. Now CSM is
the sole authority for Events tab broadcasting.

## Problem

After Phase 3, two event paths existed:
1. **Control-side (TCM):** `processPhase1ControlChannelGrant()` etc. broadcast events for the Events tab
2. **Traffic-side (TCM):** `processP1TrafficCurrentUser()`, `processP2TrafficVoice()`, etc. also broadcast events
3. **CSM:** `broadcastControlGrantEvent()` broadcast a separate cached event for the Events tab

This caused duplicate rows in the Events tab and inconsistent duration updates.

## Solution

### TCM Changes (P25TrafficChannelManager.java)
- **Removed** `broadcast(tracker)` from all 13 traffic-side methods
- Traffic methods still maintain tracker state (for internal bookkeeping) and forward to CSM
- Control-side methods (processPhase1ControlChannelGrant, processPhase2ChannelGrant, etc.) keep broadcasting -- they handle channel allocation and initial event creation
- TrafficChannelTeardownMonitor keeps broadcasting for rejected channel notifications
- Updated broadcast(DecodeEvent) javadoc to document Phase 4 role

### CSM Changes (P25CallSessionManager.java)
- Added `broadcastTrafficUpdate()` method -- re-broadcasts the cached control event with updated duration and identifiers from traffic channel messages
- Called at the end of `onTrafficChannelUpdate()` so every traffic update keeps the Events tab current
- Handles Phase 1 timeslot mismatch (control=0, traffic=1) with fallback key lookup
- Added P25ChannelGrantEvent import

### Affected Traffic-Side Methods (broadcast removed)
- `processP2TrafficCallEnd()`
- `processP2TrafficEndPushToTalk()`
- `processP2TrafficCurrentUser()` (single identifier)
- `processP2TrafficVoice()`
- `processP2TrafficCurrentUser()` (IC overload) -- also added CSM forwarding
- `processP1TrafficCallStart()`
- `processP1TrafficCurrentUser()` (single identifier)
- `processP1TrafficLDU1()` (two broadcast calls)
- `processP1TrafficCurrentUser()` (IC overload, two broadcast calls)
- `processP1TrafficCallEnd()`

## Event Flow (After Phase 4)

```
Control Channel Grant
  -> TCM: creates tracker, broadcasts event (control-side, kept)
  -> TCM: forwards to CSM.processChannelGrant()
  -> CSM: creates CallSession + CallSessionEvent, caches P25ChannelGrantEvent

Traffic Channel Update
  -> TCM: updates tracker (no broadcast), forwards to CSM.onTrafficChannelUpdate()
  -> CSM: updates CallSession/CallSessionEvent, notifies Calls tab
  -> CSM: broadcastTrafficUpdate() re-broadcasts cached control event with new duration/IDs

Traffic Channel End
  -> TCM: marks tracker complete (no broadcast), forwards to CSM.onTrafficChannelEnd()
  -> CSM: ends CallSession, notifies Calls tab
```

## Files Changed

| File | Change |
|------|--------|
| `P25TrafficChannelManager.java` | Removed 13 traffic-side broadcast(tracker) calls |
| `P25CallSessionManager.java` | Added broadcastTrafficUpdate() method + call in onTrafficChannelUpdate() |
| `tools/phase4_patch.py` | Patch script for automated changes |
| `tools/fix_encoding.py` | Encoding fix utility for Windows cp1252 em-dash issue |

## Build Verification

- `gradlew clean compileJava` -- BUILD SUCCESSFUL, 0 errors
