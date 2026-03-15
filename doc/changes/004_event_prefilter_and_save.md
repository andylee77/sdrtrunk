# 004 — Event Tab Pre-Filter and Filtered Save-to-CSV

## Date
2026-03-14

## Summary
Added two new features to the Events tab: a **Pre-Filter** mode that prevents filtered-out events
from consuming buffer space, and a **Save** toggle that writes filtered events to a CSV file with
human-readable formatting (resolved aliases, formatted timestamps, etc.).

## Problem
The Events tab has a maximum buffer size (default 200, adjustable up to 2000). The existing filter
is a **post-filter** — it hides rows visually via JTable RowFilter, but filtered-out events still
occupy buffer slots. If 190 of 200 events are "Registration" type and the user filters those out,
they only see 10 useful events because the other 190 filled the buffer.

Additionally, the existing channel-level event logging (`DecodeEventLogger`) logs ALL events to CSV
without filtering and without resolved aliases. Users wanted to save just the filtered events with
the same human-readable format shown in the Events tab.

## Solution

### Pre-Filter Checkbox
- Added a "Pre-Filter" checkbox to the `HistoryManagementPanel` toolbar
- When enabled, events that don't pass the filter are **dropped entirely** in `ClearableHistoryModel.add()`
  before they consume a buffer slot
- Only affects newly arriving events (not retroactive)
- The existing post-filter (JTable RowFilter) remains active for visual consistency

### Save Checkbox
- Added a "Save" checkbox to the `HistoryManagementPanel` toolbar
- When toggled ON, creates a new CSV file in the event logs directory
  (e.g., `20260314_141200_filtered_events.csv`)
- Writes only events that pass the current filter, with human-readable formatting:
  - Timestamps use the user's configured format
  - Talkgroup/radio IDs use the user's configured format
  - Aliases are resolved from the alias model
  - Duration is in seconds (matching the UI)
  - Frequency is in MHz (matching the UI)
- When toggled OFF (or channel changes), the file is closed
- CSV header: `Time, Duration, Event, From, From Alias, To, To Alias, Channel, Frequency, Details`

### UI Layout (Events tab toolbar)
```
[Filters] [☐ Pre-Filter] [☐ Save] [Clear]  History: |----slider----| 200
```

## Files Modified

| File | Changes |
|------|---------|
| `ClearableHistoryModel.java` | Added `FilterSet` reference, `preFilterEnabled` flag, `saveEnabled` flag, `ISaveEventListener` callback interface. `add()` now checks filter before storing and forwards passing events to save listener. |
| `HistoryManagementPanel.java` | Added Pre-Filter checkbox and Save checkbox with tooltips. Added `setSaveToggleCallback()` and `isSaveSelected()` methods. Updated layout. |
| `DecodeEventPanel.java` | Added CSV save infrastructure: `openSaveFile()`, `closeSaveFile()`, `writeEventToSaveFile()` with human-readable formatting. Added `formatIdentifiers()` and `formatAliases()` helper methods. Wired save toggle callback and save event listener. Closes save file on channel switch. |

## Notes
- The Pre-Filter and Save controls also appear on the Messages tab (via shared `HistoryManagementPanel`).
  Pre-Filter works for messages too. Save is a no-op on Messages since no save listener is configured there.
- The existing post-filter behavior is completely unchanged when Pre-Filter is unchecked.
- Save files are written to the same event logs directory used by the channel-level logging
  (configurable in User Preferences → Directories).

## Bugfix: ClassCastException on Messages Tab

**Bug:** The initial implementation called `mModel.setFilterSet(filterSet)` from
`HistoryManagementPanel.updateFilterSet()`. This caused a generics type mismatch on the Messages
tab: `MessageActivityModel` is `ClearableHistoryModel<MessageItem>` but the filter set is
`FilterSet<IMessage>`. When `add(MessageItem)` called `filterSet.canProcess(messageItem)`, the
filter expected `IMessage` but got `MessageItem`, causing `ClassCastException`. This silently
killed all message display.

**Fix:** Removed `mModel.setFilterSet(filterSet)` from `HistoryManagementPanel.updateFilterSet()`.
Instead, `DecodeEventPanel` sets the filter directly on its model via `mEventModel.setFilterSet(mFilterSet)`
where the types match (`ClearableHistoryModel<IDecodeEvent>` with `FilterSet<IDecodeEvent>`).

## Build
Compiles cleanly. `gradlew compileJava` — BUILD SUCCESSFUL.
