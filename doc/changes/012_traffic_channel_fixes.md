# 012 — Traffic Channel Architecture Fix

## Problem

The Phase 4 event consolidation (change 010/011) rerouted P25P1DecoderState and
P25P2DecoderState to send control-channel grants directly to the P25CallSessionManager
(CSM), bypassing the P25TrafficChannelManager (TCM). This broke the upstream traffic
channel allocation pipeline:

1. **No traffic channels allocated** — TCM was no longer receiving control-channel
   grant messages, so it never allocated traffic channel pool entries.
2. **Event corruption** — CSM attempted to broadcast its own decode events AND
   allocate traffic channels, duplicating TCM's responsibilities and causing
   identifier collection corruption (missing TO fields, wrong set IDs).
3. **BOM encoding issue** — P25P2DecoderState.java had a UTF-8 BOM injected by
   PowerShell, preventing compilation.

## Root Cause Analysis

The upstream architecture is:
```
DecoderState → TCM (events + channel allocation + traffic tracking)
                 ↕ traffic channels feed back updates
```

The Phase 4 changes attempted:
```
DecoderState → CSM (session tracking + events + channel allocation)  ← BROKEN
```

The CSM was never designed to fully replace TCM's event/channel management. TCM has
~1800 lines of carefully orchestrated locking, tracker maps, and channel pool management
that CSM was trying to replicate with simpler code, causing data integrity issues.

## Fix Applied

**Reverted to upstream routing + added CSM forwarding from TCM:**

```
DecoderState → TCM (events + channels) → CSM (session tracking only)
                 ↕ traffic channels feed back updates → CSM
```

### Changes

1. **P25P1DecoderState.java** — Reverted to upstream: all `processP1ControlDirected
   ChannelGrant()` and `processP1ControlAnnouncedTrafficUpdate()` calls route to TCM
   (removed CSM routing added in Phase 4).

2. **P25P2DecoderState.java** — Reverted to exact upstream content. Stripped BOM that
   was causing compilation failure.

3. **P25TrafficChannelManager.java** — Two surgical changes:
   - **Disabled CSM event listener wiring**: Commented out
     `mCallSessionManager.setDecodeEventListener(listener)` in `addDecodeEventListener()`
     so CSM no longer broadcasts to the Events tab (TCM owns that exclusively).
   - **Added CSM forwarding**: After each control-channel grant/update method's
     `finally { mLock.unlock(); }`, added forwarding calls to CSM for session tracking:
     - `processP1ControlDirectedChannelGrant()` → `mCallSessionManager.processChannelGrant()`
     - `processP1ControlAnnouncedTrafficUpdate()` → `mCallSessionManager.processChannelUpdate()`
   - Traffic-side methods already had CSM forwarding from Phase 3 (unchanged).

### Architecture After Fix

| Component | Responsibility |
|-----------|---------------|
| **TCM** | Event broadcasting, traffic channel pool, tracker maps, locking |
| **CSM** | Session tracking (Calls tab), call log DB writes |
| **DecoderState** | Decodes messages, routes to TCM (upstream pattern) |

The CSM receives notifications from TCM in two ways:
- **Control side**: TCM calls `CSM.processChannelGrant/Update()` after processing
- **Traffic side**: TCM calls `CSM.onTrafficChannelUpdate/End()` (existing Phase 3 wiring)

## Files Modified

- `src/main/java/io/github/dsheirer/module/decode/p25/phase1/P25P1DecoderState.java`
- `src/main/java/io/github/dsheirer/module/decode/p25/phase2/P25P2DecoderState.java`
- `src/main/java/io/github/dsheirer/module/decode/p25/P25TrafficChannelManager.java`

## CSM Cleanup (completed)

Removed dead code from P25CallSessionManager that was left over from the Phase 3/4
architecture where CSM tried to own Events tab broadcasting and traffic channel allocation.
After fix 012 reverted those responsibilities to TCM, this code became unreachable since
`mDecodeEventListener` was never set (commented out in TCM's `addDecodeEventListener()`).

### Removed from CSM (~400 lines)

**Dead fields:**
- `mDecodeEventListener` — never set (TCM commented out the wiring)
- `mActiveControlEvents` map — only used by dead broadcast methods

**Dead methods:**
- `broadcastControlGrantEvent()` — Events tab broadcasting (TCM owns this)
- `broadcastTrafficUpdate()` — Events tab traffic updates (TCM owns this)
- `processP2DataChannel()` — never called externally (P25P1DecoderState calls TCM's version)
- `processP2ChannelGrant()` — never called externally (P2 grants go through TCM)
- `processP2ChannelUpdate()` — never called externally (P2 updates go through TCM)
- `setDecodeEventListener()` — never called (commented out in TCM)
- `onDecodeEvent()` — deprecated no-op

**Dead code within remaining methods:**
- All `mTrafficChannelManager.allocatePhase1TrafficChannel()` calls (TCM already allocates)
- All `mTrafficChannelManager.allocatePhase2TrafficChannel()` calls (TCM already allocates)
- All `broadcastControlGrantEvent()` calls within grant processing
- All `mActiveControlEvents` cache lookups/updates in `processChannelUpdate()` and
  `onTrafficChannelUpdate()`
- `mActiveControlEvents` cleanup in `transitionToEnding()`, `finalizeSession()`, `stop()`

**Refactored:**
- `transitionToEnding()` simplified — removed `clearCachedEvent` parameter (was for
  `mActiveControlEvents` management)
- Added `tagSessionIgnored()` helper to consolidate duplicate "IGNORED" tagging logic
- Updated class javadoc to clearly describe CSM as session tracking only

### Cleaned up in TCM

- Removed commented-out `mCallSessionManager.setDecodeEventListener(listener)` code block
  from `addDecodeEventListener()`

### Result

CSM reduced from ~1000 lines to ~600 lines. Clean separation of concerns:
- **TCM**: Events tab, traffic channel pool, tracker maps, event broadcasting
- **CSM**: Session lifecycle, Calls tab, call log DB

## Future Work

- **P2 channel grant forwarding**: The P2 control methods in TCM don't yet forward to
  CSM. Add when testing Phase 2 systems.
- **Patch call consolidation**: Detect same radio ID granted to multiple talk groups
  (Motorola LSM implicit patches), consolidate to single audio channel.
