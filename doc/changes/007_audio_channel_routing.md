# 007 — Audio Channel Routing Filter

**Date:** 2026-03-20
**Status:** Implemented
**Design:** `doc/design/007_audio_channel_routing.md`

## Summary

Adds per-audio-channel routing filters and per-talkgroup mute controls to the audio
playback system. Users can now route each audio channel to hear only specific systems
(alias lists), groups, or all audio. Individual talkgroups can be muted via right-click
context menu. Also fixes the mute button state synchronization bug when audio devices change.

## Changes

### New Files
| File | Purpose |
|------|---------|
| `AudioChannelFilterMode.java` | Enum: OFF, ALL, SYSTEM, GROUP |
| `AudioChannelFilter.java` | Routing filter with per-TG mute set |
| `AudioChannelFilterItem.java` | Combo box model item wrapping mode+value+label |

### Modified Files
| File | Change |
|------|--------|
| `AudioChannel.java` | Added `mFilter` field, `getFilter()` accessor, per-TG mute check in `getAudio()` |
| `AudioPlaybackManager.java` | Added `mAliasModel` field, `setAliasModel()`, filter-aware segment routing in `processAudioSegments()` |
| `AudioChannelPanel.java` | Added routing combo box, right-click per-TG mute context menu, accepts `Supplier<Set<String>>` for active alias list names |
| `AudioChannelsPanel.java` | Passes `Supplier<Set<String>>` through to `AudioChannelPanel` |
| `AudioPanel.java` | Fixed MuteButton, accepts and passes `Supplier<Set<String>>` for active alias list names |
| `ControllerPanel.java` | Passes `() -> channelProcessingManager.getActiveAliasListNames()` supplier to `AudioPanel` |
| `ChannelProcessingManager.java` | Added `getActiveAliasListNames()` returning alias list names from processing channels |
| `SDRTrunk.java` | Wires `aliasModel` into `AudioPlaybackManager` |

## Architecture

### Routing (Phase A — AudioPlaybackManager)
- Each `AudioChannel` has an `AudioChannelFilter` with mode (OFF/ALL/SYSTEM/GROUP) and optional value
- `AudioPlaybackManager.processAudioSegments()` checks `filter.accepts(segment, aliasModel)` before assigning segments
- Linked segments also respect the filter
- Segments rejected by all channels are disposed normally

### Per-Talkgroup Mute (Phase A — AudioChannel)
- `AudioChannelFilter` maintains a `Set<Integer>` of muted talkgroup IDs
- `AudioChannel.getAudio()` checks `mFilter.isTalkgroupMutedForSegment()` at both mute check points
- Returns silence buffer instead of audio data — segment is NOT discarded
- Global mute button continues to work independently

### UI — Routing Combo (Phase B — AudioChannelPanel)
- JComboBox added to each AudioChannelPanel
- Populated with: Off, All, --- Systems --- (alias list names), --- Groups --- (group names)
- **Only shows alias lists from actively processing channels** — uses `Supplier<Set<String>>` backed by `ChannelProcessingManager.getActiveAliasListNames()`
- Re-populates dynamically via `ListChangeListener` on alias model changes
- Separator items rendered as JSeparator, non-selectable
- Selection updates the AudioChannelFilter via `setFilter(mode, value)`

### UI — Per-TG Mute Menu (Phase C — AudioChannelPanel)
- Right-click on AudioChannelPanel shows popup menu
- Lists talkgroups scoped to the current filter (system/group/all)
- JCheckBoxMenuItem per talkgroup with Mute All / Unmute All at top
- Talkgroup IDs extracted from alias model

### Per-Channel Mute Button (Phase D — AudioChannelPanel)
- Replaced the "M" text label with a clickable mute/unmute icon button on each audio channel
- Each channel can now be independently muted via its own button
- Button toggles `AudioChannel.setMuted()` and syncs icon on AUDIO_MUTED/AUDIO_UNMUTED events
- Removed the old global MuteButton from AudioPanel

### Volume Slider (Phase E — AudioPanel)
- Added a compact vertical volume slider on the right side of the audio panel
- Controls the audio output gain via `FloatControl` (same as the old right-click popup slider)
- Disabled gracefully when no gain control is available
- Double-click resets to center (0 dB)
- Syncs with new AudioOutput on `AUDIO_CONFIGURATION_CHANGE_COMPLETE`
- Right-click popup volume slider still available as alternative

## Testing Notes
- Default behavior unchanged: all channels default to ALL mode
- Setting a channel to OFF should prevent any audio from being routed to it
- SYSTEM filter matches on `AliasListConfigurationIdentifier` value
- GROUP filter matches on `Alias.getGroup()` for TO identifiers
- Per-TG mute state is cleared when the routing filter changes
