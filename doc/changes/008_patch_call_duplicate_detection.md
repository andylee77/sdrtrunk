# 008 — Patch Call Duplicate Detection & Events Column

## Date
2026-03-20

## Summary
Enhanced the duplicate call detector to recognize patch group member talkgroups as
duplicates of the patch group call, and added a "Patch Group" column to the Events
table so patch group relationships are visible in the UI and CSV exports.

## Problem
When P25 patch groups are active (e.g. P:00149 patches talkgroups 01085, 01087, 01089),
the system grants separate traffic channels for each patched talkgroup. The duplicate
call detector only compared plain talkgroup IDs against each other — it did not understand
that a call to talkgroup 01085 is a duplicate of the patch group P:00149[01085,01087,01089].

This resulted in:
- Multiple simultaneous audio playbacks for the same transmission
- Duplicate entries in the Events table for what is logically one call
- No visibility into which calls are part of a patch group

## Solution

### 1. DuplicateCallDetector — Patch Group Member Matching
**File:** `src/main/java/io/github/dsheirer/audio/DuplicateCallDetector.java`

The `isDuplicate(List<Identifier>, List<Identifier>)` method was rewritten to handle
all combinations of TalkgroupIdentifier and PatchGroupIdentifier comparisons:

| Segment 1 TO | Segment 2 TO | Match Logic |
|---|---|---|
| TalkgroupIdentifier | TalkgroupIdentifier | Direct value comparison (unchanged) |
| TalkgroupIdentifier | PatchGroupIdentifier | Match if TG equals supergroup ID **or** any member TG |
| PatchGroupIdentifier | TalkgroupIdentifier | Match if any member TG equals TG, or supergroup matches |
| PatchGroupIdentifier | PatchGroupIdentifier | Match supergroup IDs or overlapping member TGs |
| RadioIdentifier | RadioIdentifier | Direct value comparison (unchanged) |

This matching runs during the existing duplicate detection flow — patch member calls
are flagged as duplicates and suppressed before audio playback.

### 2. Events Table — Patch Group Column
**Files:**
- `src/main/java/io/github/dsheirer/module/decode/event/DecodeEventModel.java`
- `src/main/java/io/github/dsheirer/module/decode/event/DecodeEventPanel.java`

Added a new "Patch Group" column (index 7) between "To Alias" and "Channel":
- Shows `P:<supergroup> [<member1>, <member2>, ...]` when the TO identifier is a PatchGroupIdentifier
- Empty for non-patch-group calls
- Included in CSV save exports
- Uses TalkgroupFormatPreference for consistent formatting

## Files Changed
| File | Change |
|------|--------|
| `src/main/java/io/github/dsheirer/audio/DuplicateCallDetector.java` | Patch group member matching in isDuplicate() |
| `src/main/java/io/github/dsheirer/module/decode/event/DecodeEventModel.java` | Added COLUMN_PATCH_GROUP constant and column definition |
| `src/main/java/io/github/dsheirer/module/decode/event/DecodeEventPanel.java` | PatchGroupCellRenderer, CSV save patch group column |

## Testing
1. Configure a P25 Phase 1 system with patch group activity
2. Enable duplicate call detection by talkgroup in User Preferences
3. Verify that when a patch call like P:00149[01085,01087,01089] is active:
   - Individual calls to 01085, 01087, 01089 are flagged as duplicates
   - Only one audio stream plays (the first/main call)
   - The Patch Group column shows the supergroup and member talkgroups
4. Verify non-patch calls show empty Patch Group column
5. Verify CSV save includes the Patch Group column
