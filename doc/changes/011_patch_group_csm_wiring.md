# 011: Patch Group CSM Wiring Fix

**Date:** 2026-03-21
**Status:** Complete
**Files Modified:**
- `src/main/java/io/github/dsheirer/module/decode/p25/phase1/P25P1DecoderState.java`

## Problem

Three related issues observed on JFRD (Motorola P25 Phase 1) patch group calls:

1. **Duplicate traffic channels:** When a patch group call activates (e.g., P-149 patching
   TGs 01085, 01087, 01089, 00149), separate traffic channels were allocated for each member
   TG grant instead of consolidating into a single session. This caused multiple ACTIVE
   channels in Now Playing for what should be one call.

2. **Call/event duration mismatch:** Because duplicate sessions existed, each had its own
   independent duration tracking, creating confusing mismatches between Calls and Events tabs.

3. **Patch group not displayed:** The Patch Group column in Events/Calls showed only a single
   TG ID instead of the full patch group with all members.

## Root Cause

**The P25CallSessionManager (CSM) never received a reference to the PatchGroupManager.**

The `P25P1DecoderState` creates its own `PatchGroupManager` instance and uses it to resolve
member TGs to `PatchGroupIdentifier` objects in the `IdentifierCollection`. However, the CSM
has its own `mPatchGroupManager` field with a `setPatchGroupManager()` method that was
**never called from anywhere in the codebase**.

This meant:
- **Check 2b in `findCrossFrequencySession()`** (consulting PatchGroupManager to resolve
  member TG → supergroup) **never executed** — the manager was always null.
- **`onPatchGroupUpdate()`** was never called, so the consolidation logic that merges
  duplicate sessions and releases extra traffic channels **never ran**.
- Sessions created for member TG grants could not look up the supergroup, so each member
  TG created its own independent traffic channel.

## Fix

### 1. Wire PatchGroupManager to CSM (constructor)

In `P25P1DecoderState`'s constructor, after setting the traffic channel manager, share the
PatchGroupManager with the CSM:

```java
trafficChannelManager.getCallSessionManager().setPatchGroupManager(mPatchGroupManager);
```

This enables Check 2b in `findCrossFrequencySession()` to resolve member TGs to their
supergroup and find existing sessions.

### 2. Notify CSM on MOTOROLA_OSP_GROUP_REGROUP_ADD

In the TSBK processing for `MOTOROLA_OSP_GROUP_REGROUP_ADD`, after adding patch groups to
the PatchGroupManager, iterate the identifiers and call `onPatchGroupUpdate()` on the CSM
for each `PatchGroupIdentifier`:

```java
for(Identifier id : tsbk.getIdentifiers())
{
    if(id instanceof PatchGroupIdentifier pgi)
    {
        mTrafficChannelManager.getCallSessionManager().onPatchGroupUpdate(pgi);
    }
}
```

This triggers the CSM's consolidation logic which:
- Finds all active sessions that have any member TG of the patch group
- Enriches sessions with patch group info (all member TGs)
- If multiple sessions matched, merges them into the oldest one
- Releases traffic channels for the duplicate sessions

## How It Works Now

The flow for a Motorola patch group call is:

1. **MOTOROLA_OSP_GROUP_REGROUP_ADD** arrives → PatchGroupManager registers the patch
   (supergroup + member TGs) → CSM.onPatchGroupUpdate() consolidates any existing sessions

2. **Individual member TG grants** arrive (GROUP_VOICE_CHANNEL_GRANT for each member TG):
   - `getMutableIdentifierCollection()` passes TG through PatchGroupManager.update() →
     resolves to PatchGroupIdentifier if the patch is known
   - CSM's `processPhase1Grant()` calls `findCrossFrequencySession()`:
     - Check 1: If PatchGroupIdentifier → finds session by supergroup/member overlap
     - Check 2b: If TalkgroupIdentifier → consults PatchGroupManager for supergroup lookup
     - Check 3: Radio affinity fallback
   - Matching session found → update existing session, skip duplicate traffic channel

3. **MOTOROLA_OSP_GROUP_REGROUP_CHANNEL_GRANT** arrives → PatchGroupIdentifier in IC →
   processed as a patch call grant, matching the existing session

## Impact

- Patch group calls now allocate ONE traffic channel instead of 3-4
- Event/call durations are consistent (single session tracking)
- Patch group display shows all member TGs via PatchGroupIdentifier
- Events tab shows "PATCH MEMBER - PHASE 1 CHANNEL GRANT" for member TG grants
  that matched an existing session (no traffic channel allocated)
