# Change 005: Ignore Unmonitored Calls

## Summary
Added a new "Ignore Unmonitored Calls" option to the P25 decoder configuration (both Phase 1 and Phase 2).
When enabled, the traffic channel manager will skip allocating tuner resources for talkgroups that are
not being actively monitored — i.e., talkgroups with no alias, "Do Not Monitor" priority, or no
recording/streaming configured.

## Problem
Previously, the tuner would allocate traffic channels for all active calls (except encrypted ones when
that option was enabled). On busy systems this could mean 10+ active traffic channels consuming tuner
bandwidth and CPU resources for talkgroups that the user isn't listening to, recording, or streaming.

## Solution
Added a third ignore option alongside the existing "Ignore Data Calls" and "Ignore Encrypted Calls":

- **Ignore Unmonitored Calls**: When checked, calls to talkgroups that are considered "unmonitored"
  will not have traffic channels allocated. The call event is still logged in the events table with
  an "IGNORED: UNMONITORED CALL" detail tag.

### What qualifies as "unmonitored"?
A call is unmonitored if its TO talkgroup matches any of:
1. No alias is defined for the talkgroup in the alias list
2. The alias has "Do Not Monitor" priority set
3. The alias exists but has neither recording nor streaming configured

If any alias for the talkgroup IS recordable or streamable, the call is considered monitored and will
proceed normally.

## Files Changed

### `DecodeConfigP25.java` (base config class for Phase 1 & Phase 2)
- Added `mIgnoreUnmonitoredCalls` boolean field with Jackson XML serialization
- Added `getIgnoreUnmonitoredCalls()` / `setIgnoreUnmonitoredCalls()` methods

### `P25P1ConfigurationEditor.java`
- Added "Ignore Unmonitored Calls" checkbox to the P25 Phase 1 channel configuration UI
- Wired save/load for the new option

### `P25P2ConfigurationEditor.java`
- Added "Ignore Unmonitored Calls" checkbox to the P25 Phase 2 channel configuration UI
- Wired save/load for the new option

### `P25TrafficChannelManager.java`
- Added `mIgnoreUnmonitoredCalls` field, read from config in constructor
- Added `mAliasList` field with `setAliasList(AliasList)` setter
- Added `isUnmonitored(IdentifierCollection)` helper method that checks alias properties
- Added unmonitored filtering in `processPhase1ControlChannelGrant()`:
  - Same-call path: early return with duration update only (no channel allocation)
  - New-call path: create IGNORED event tracker and return
- Added unmonitored filtering in `processPhase2ChannelGrant()`:
  - Same-call path: early return with duration update only (no channel allocation)
  - New-call path: create IGNORED event tracker and return

### `DecoderFactory.java`
- Wired `AliasList` to `P25TrafficChannelManager` via `setAliasList()` at all creation points:
  - `getPrimaryModules()` switch cases for P25_PHASE1 and P25_PHASE2
  - `processP25Phase1()` when creating primary TCM for standard channels
  - `processP25Phase2()` when creating TCM for standard channels and fallback case

## UI Behavior
The checkbox appears in the P25 decoder configuration panel alongside the existing
"Ignore Data Calls" and "Ignore Encrypted Calls" checkboxes. Default is unchecked (disabled).

## Event Display
Ignored unmonitored calls still appear in the decode events table with details:
- `"IGNORED: UNMONITORED CALL"` — for visibility and debugging

## Testing Notes
- Enable the option in the channel config and verify that only aliased talkgroups with
  recording or streaming configured get traffic channels allocated
- Verify that "Do Not Monitor" priority talkgroups are also filtered
- Verify that talkgroups with no alias are filtered
- Verify events still show up in the events table with IGNORED detail
- Verify that disabling the option returns to normal behavior (all calls get channels)
