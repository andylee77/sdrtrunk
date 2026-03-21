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

## Future Work

- **P2 channel grant forwarding**: The P2 control methods (`processP2ChannelGrant`,
  `processP2ChannelUpdate`) don't yet forward to CSM. Add when testing Phase 2 systems.
- **Patch call consolidation**: Detect same radio ID granted to multiple talk groups
  (Motorola LSM implicit patches), consolidate to single audio channel.
- **CSM cleanup**: Remove now-unused `broadcastControlGrantEvent()` and
  `allocatePhase1TrafficChannel()` from CSM since TCM handles those.
