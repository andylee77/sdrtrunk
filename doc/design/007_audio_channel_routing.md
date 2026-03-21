# Design: Audio Channel Routing & Per-Talkgroup Mute Control

**Document:** `doc/design/007_audio_channel_routing.md`
**Created:** 2026-03-20
**Status:** Planning
**Branch:** `plutosdr`
**Related DEVPLAN items:** 3.3.1–3.3.5 (Audio Playback Changes)

---

## Overview

Replace the simple per-channel talkgroup filter from the prior modified source with a
**hierarchical audio channel routing system** that provides dropdown selectors on each audio
channel (LEFT/RIGHT for stereo, MONO for mono) with options to route audio by Off, All, System,
or Alias Group — plus per-talkgroup mute/unmute control within the selected scope.

This design also fixes the **mute button regression** present in the prior implementation.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Prior Implementation Analysis](#2-prior-implementation-analysis)
3. [Root Cause of Mute Bug](#3-root-cause-of-mute-bug)
4. [New Design](#4-new-design)
5. [UI Layout](#5-ui-layout)
6. [Data Model](#6-data-model)
7. [Audio Segment Routing Logic](#7-audio-segment-routing-logic)
8. [Per-Talkgroup Mute](#8-per-talkgroup-mute)
9. [Mute Button Fix](#9-mute-button-fix)
10. [Files to Modify](#10-files-to-modify)
11. [New Classes](#11-new-classes)
12. [Implementation Plan](#12-implementation-plan)
13. [Testing](#13-testing)

---

## 1. Problem Statement

### Current State (upstream)

The audio playback system has LEFT and RIGHT channels (for stereo output) that each display
the currently playing talkgroup/alias. Audio segments are assigned to whichever channel is empty
first. There is a single global mute button. Users have **no control** over which audio plays
on which channel — it's purely first-come, first-served.

### Prior Modified Source

Added a `JComboBox` per channel listing "All TGs" plus every individual talkgroup from the alias
model. When a talkgroup was selected, only audio segments matching that talkgroup ID would play
on that channel. **However, the mute button stopped working** because the filtering implementation
discarded non-matching segments permanently instead of just silencing them.

### Desired State

- **Dropdown selector per channel** with hierarchical options: Off, All, {System}, {Group}
- **Per-talkgroup mute/unmute** via right-click context menu within the selected scope
- **Mute button works correctly** — mute silences audio without discarding segments
- **Dynamic population** — systems and groups populate from active channel configs and alias model

---

## 2. Prior Implementation Analysis

### Changes Made in Modified Source (5 files)

#### `AudioChannel.java` (+76 lines)
- Added `mTalkgroupFilter` (Integer) field
- Added `setTalkgroupFilter()`, `getTalkgroupFilter()`, `hasTalkgroupFilter()`
- Added `matchesTalkgroupFilter()` — checks `IdentifierCollection` TO identifiers for
  `TalkgroupIdentifier` match
- **Filter enforcement in `play()`** — rejects non-matching segments with `decrementConsumerCount()`
- Changed stall threshold from 6 to 20 intervals (DDC-related, not needed for our fork)
- Logger rename: `LOGGER` → `mLog`

#### `AudioChannelPanel.java` (+201 lines)
- Added `JComboBox<TalkgroupFilterItem>` to each channel panel
- `createTalkgroupFilterCombo()` — populates from `AliasModel.getAliases()`, extracting all
  `Talkgroup` alias IDs
- `refreshTalkgroupCombo()` — re-populates when alias list changes via `ListChangeListener`
- `TalkgroupFilterItem` inner class — wraps Integer talkgroup ID + display label
- Listens to `AliasModel.aliasList()` changes to auto-refresh the combo

#### `AudioPlaybackManager.java` (+20/-7 lines)
- Updated linked segment handling to check `matchesTalkgroupFilter()` before playing
- Changed empty-channel assignment from simple "first empty channel" to iterating segments and
  finding a channel that matches the segment's talkgroup filter

#### `AudioPlaybackDeviceManager.java` (-41 lines)
- Simplified device discovery: collapsed separate mono/stereo/multi-channel branches into a
  single branch with `channels <= 2` guard
- Removed commented-out multi-channel code

#### `AudioPlaybackDeviceDescriptor.java` (-16 lines)
- Simplified `getCleanDescription()`: removed ALSA fallback handling

### What Worked Well
- Filter concept is sound — per-channel routing is useful
- `matchesTalkgroupFilter()` correctly inspects `IdentifierCollection` TO identifiers
- Dynamic combo population from `AliasModel` is the right approach

### What Needs Improvement
- Flat talkgroup list doesn't scale — needs hierarchical grouping
- "Off" option missing — can't disable a channel
- Filter discards segments in `play()` — causes mute bug
- No per-talkgroup mute within a filter scope
- DDC stall threshold change (6→20) is not applicable to our polyphase channelizer

---

## 3. Root Cause of Mute Bug

### The Bug

After selecting a talkgroup filter on a channel, the global mute button no longer works.
Clicking mute/unmute has no audible effect.

### Root Cause

The prior implementation has **two independent gating mechanisms** that conflict:

1. **Mute** (in `AudioChannel.getAudio()`): When `mMuted` is true, `getAudio()` returns
   silence (`new float[SAMPLES_PER_INTERVAL]`) instead of the actual audio. The audio segment
   is still consumed and played through — it just outputs silence. This is correct.

2. **Talkgroup filter** (in `AudioChannel.play()`): When the filter doesn't match, the segment
   is **permanently discarded** via `decrementConsumerCount()`. The segment is never queued.

The conflict arises because:

```
Audio Segment arrives at AudioPlaybackManager.processAudioSegments()
    │
    ▼
AudioPlaybackManager checks if channel is empty → yes
    │
    ▼
AudioChannel.play(audioSegment) is called
    │
    ▼
Filter check: matchesTalkgroupFilter(audioSegment)?
    │ NO → audioSegment.decrementConsumerCount()  ← PERMANENTLY DISCARDED
    │ YES → mAudioSegmentQueue.add(audioSegment)
    │
    ▼
Later, getAudio() is called by the audio output processor
    │
    ▼
if(isMuted()) return silence  ← NEVER REACHED for filtered segments
```

When a talkgroup filter is active, most segments are discarded before they ever reach the
mute check. The segment's consumer count drops to zero, triggering `dispose()`, and the
segment can never be recovered.

Additionally, the `MuteButton` inner class maintains its own `mMuted` boolean that can
get out of sync with the actual `AudioChannel.isMuted()` state, especially across audio
device configuration changes (which recreate the audio output and channels).

### The Fix

**Separation of concerns:**
- **Routing/filtering** must happen in `AudioPlaybackManager.processAudioSegments()`, before
  segments are offered to channels. Segments that don't match ANY channel's filter are held in
  the queue (not discarded) in case filters change, or eventually aged out.
- **Mute** continues to operate in `AudioChannel.getAudio()` as silence substitution.
- The two mechanisms never interfere with each other.

---

## 4. New Design

### Channel Routing Model

Each audio channel has a **routing filter** that determines which audio segments it accepts:

| Mode | Value | Behavior |
|------|-------|----------|
| `OFF` | — | Channel is disabled. No audio segments are offered. UI shows "Off". |
| `ALL` | — | Accepts all audio segments (current default behavior). |
| `SYSTEM` | system name | Only accepts segments from channels configured for this system. |
| `GROUP` | alias group name | Only accepts segments where the TO talkgroup has an alias in this group. |

### Dropdown Options (populated dynamically)

```
┌──────────────────────────┐
│ Off                      │  ← AudioChannelFilterMode.OFF
│ All                      │  ← AudioChannelFilterMode.ALL
│ ── Systems ───────────── │  ← separator
│ Clay County              │  ← AudioChannelFilterMode.SYSTEM, value="Clay County"
│ Duval County             │  ← AudioChannelFilterMode.SYSTEM, value="Duval County"
│ ── Groups ────────────── │  ← separator
│ Fire Dispatch            │  ← AudioChannelFilterMode.GROUP, value="Fire Dispatch"
│ Fire TAC                 │  ← AudioChannelFilterMode.GROUP, value="Fire TAC"
│ Law Enforcement          │  ← AudioChannelFilterMode.GROUP, value="Law Enforcement"
│ EMS                      │  ← AudioChannelFilterMode.GROUP, value="EMS"
└──────────────────────────┘
```

### Per-Talkgroup Mute (within scope)

Right-click on the channel panel to get a context menu showing all talkgroups within the
current routing scope, each with a mute/unmute toggle:

```
┌────────────────────────────────────────┐
│ ✓ Clay Fire Dispatch (300)             │  ← unmuted (playing)
│ ✗ Clay Fire TAC 1 (301)               │  ← muted
│ ✓ Clay Fire TAC 2 (302)               │  ← unmuted (playing)
│ ✓ Clay EMS Dispatch (400)             │  ← unmuted (playing)
│ ────────────────────────────────────── │
│ Unmute All                             │
│ Mute All                               │
└────────────────────────────────────────┘
```

Muted talkgroups within a scope work like the global mute — the segment is consumed but
silence is output. This prevents the segment discard bug.

---

## 5. UI Layout

### Current Layout (upstream)

```
┌─────────────────────────────────────────────────────┐
│ [🔊] │ LEFT  [icon] Alias Name │ RIGHT [icon] Alias │
└─────────────────────────────────────────────────────┘
```

### New Layout

```
┌──────────────────────────────────────────────────────────────┐
│ [🔊] │ LEFT [All          ▾] [icon] Alias │ RIGHT [All          ▾] [icon] Alias │
│       │  M                                 │   M                                  │
└──────────────────────────────────────────────────────────────┘
```

Where:
- `[🔊]` = Global mute button (existing)
- `[All          ▾]` = Routing filter dropdown per channel
- `[icon] Alias` = Currently playing talkgroup icon and alias name (existing)
- `M` = Per-channel muted indicator (existing, shown in red when muted)

### AudioChannelPanel Layout Changes

Current MigLayout: `"[][][align right]0[grow,fill]"`

New MigLayout: `"[][][align right]0[grow,fill][]"`

The routing combo is added as the last column in the channel panel.

### Sizing

- Combo box: `preferredSize(140, 22)`, `maxSize(180, 22)`, font `Sans-Serif Plain 10`
- Tooltip: "Route {LEFT|RIGHT} audio channel: select audio source filter"

---

## 6. Data Model

### `AudioChannelFilterMode` (new enum)

```java
public enum AudioChannelFilterMode
{
    OFF("Off"),           // Channel disabled
    ALL("All"),           // Accept all audio
    SYSTEM("System"),     // Filter by system name
    GROUP("Group");       // Filter by alias group name

    private final String mLabel;

    AudioChannelFilterMode(String label) { mLabel = label; }

    public String getLabel() { return mLabel; }
}
```

### `AudioChannelFilter` (new class)

```java
public class AudioChannelFilter
{
    private AudioChannelFilterMode mMode = AudioChannelFilterMode.ALL;
    private String mFilterValue = null;  // system name or group name
    private final Set<Integer> mMutedTalkgroups = new ConcurrentHashSet<>();

    // Mode and value
    public AudioChannelFilterMode getMode() { return mMode; }
    public void setMode(AudioChannelFilterMode mode) { mMode = mode; }
    public String getFilterValue() { return mFilterValue; }
    public void setFilter(AudioChannelFilterMode mode, String value) { ... }

    // Per-talkgroup mute
    public boolean isTalkgroupMuted(int talkgroupId) { ... }
    public void setTalkgroupMuted(int talkgroupId, boolean muted) { ... }
    public void muteAllTalkgroups() { ... }
    public void unmuteAllTalkgroups() { ... }
    public Set<Integer> getMutedTalkgroups() { ... }

    // Matching
    public boolean isOff() { return mMode == AudioChannelFilterMode.OFF; }
    public boolean accepts(AudioSegment segment, AliasModel aliasModel) { ... }
    public boolean isTalkgroupMutedForSegment(AudioSegment segment) { ... }
}
```

### `AudioChannelFilterItem` (new class — combo box model)

```java
public class AudioChannelFilterItem
{
    private final AudioChannelFilterMode mMode;
    private final String mValue;   // null for OFF/ALL, system/group name for SYSTEM/GROUP
    private final String mLabel;   // display text

    // Constructor, getters, toString()
}
```

### Filter Matching Logic in `AudioChannelFilter.accepts()`

```java
public boolean accepts(AudioSegment segment, AliasModel aliasModel)
{
    if(mMode == AudioChannelFilterMode.OFF)
    {
        return false;
    }

    if(mMode == AudioChannelFilterMode.ALL)
    {
        return true;
    }

    IdentifierCollection identifiers = segment.getIdentifierCollection();
    if(identifiers == null)
    {
        return false;
    }

    if(mMode == AudioChannelFilterMode.SYSTEM)
    {
        // Check the CONFIGURATION class, ALIAS_LIST form identifier
        // The alias list name typically matches the system name
        AliasListConfigurationIdentifier aliasConfig = identifiers.getAliasListConfiguration();
        if(aliasConfig != null)
        {
            // Match against alias lists associated with this system
            return mFilterValue.equals(aliasConfig.getValue());
        }
        return false;
    }

    if(mMode == AudioChannelFilterMode.GROUP)
    {
        // Check if the TO talkgroup's alias belongs to this group
        List<Identifier> toIds = identifiers.getIdentifiers(IdentifierClass.USER, Role.TO);
        for(Identifier id : toIds)
        {
            AliasList aliasList = aliasModel.getAliasList(identifiers);
            if(aliasList != null)
            {
                List<Alias> aliases = aliasList.getAliases(id);
                for(Alias alias : aliases)
                {
                    if(mFilterValue.equals(alias.getGroup()))
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    return false;
}
```

### Talkgroup Mute Check in `AudioChannelFilter.isTalkgroupMutedForSegment()`

```java
public boolean isTalkgroupMutedForSegment(AudioSegment segment)
{
    if(mMutedTalkgroups.isEmpty())
    {
        return false;
    }

    IdentifierCollection identifiers = segment.getIdentifierCollection();
    if(identifiers == null)
    {
        return false;
    }

    List<Identifier> toIds = identifiers.getIdentifiers(IdentifierClass.USER, Role.TO);
    for(Identifier id : toIds)
    {
        if(id instanceof TalkgroupIdentifier tgId)
        {
            if(mMutedTalkgroups.contains(tgId.getValue()))
            {
                return true;
            }
        }
    }

    return false;
}
```

---

## 7. Audio Segment Routing Logic

### Current Flow (upstream)

```
AudioSegment arrives at AudioPlaybackManager
    │
    ▼
processAudioSegments():
    1. Check: duplicate? do-not-monitor? → discard
    2. Check: linked to current channel segment? → assign to that channel
    3. Sort by priority
    4. Assign to first empty channel
    5. If no empty channel and segment is complete → discard
```

### New Flow

```
AudioSegment arrives at AudioPlaybackManager
    │
    ▼
processAudioSegments():
    1. Check: duplicate? do-not-monitor? → discard
    2. Check: linked to current channel segment?
       └─ AND channel filter accepts it? → assign to that channel
       └─ Channel filter rejects? → discard (linked segments can't float)
    3. Sort by priority
    4. For each segment, find first empty channel WHERE:
       └─ channel.getFilter().isOff() == false  (skip OFF channels)
       └─ channel.getFilter().accepts(segment, aliasModel) == true
       └─ If found → assign
    5. If no matching empty channel and segment is complete → discard
```

### Key Difference: Filter at Assignment, Not at Play

The critical architectural change is that filtering happens in `AudioPlaybackManager` when
deciding which channel to offer a segment to — **not** in `AudioChannel.play()`.

`AudioChannel.play()` always accepts the segment (unless the channel is disabled at the
hardware level). The filtering decision is made by the manager.

This means:
- **Mute always works** — it operates independently in `getAudio()`
- **Per-talkgroup mute always works** — it operates independently in `getAudio()`
- **Segments are not discarded by the channel** — only by the manager when no channel accepts them

### Per-Talkgroup Mute Integration in `AudioChannel.getAudio()`

```java
// In AudioChannel.getAudio(), after retrieving audio from the segment:

if(isMuted())
{
    return new float[SAMPLES_PER_INTERVAL];  // Global channel mute
}

if(mFilter.isTalkgroupMutedForSegment(mCurrentAudioSegment))
{
    return new float[SAMPLES_PER_INTERVAL];  // Per-talkgroup mute
}

return audio;
```

---

## 8. Per-Talkgroup Mute

### How It Works

1. User right-clicks on an `AudioChannelPanel`
2. Context menu shows all talkgroups within the current routing scope
3. Each talkgroup has a checkmark (✓ = unmuted, ✗ = muted)
4. Clicking a talkgroup toggles its mute state
5. "Mute All" and "Unmute All" options at the bottom

### Building the Talkgroup List

The talkgroup list depends on the current routing filter mode:

| Mode | Talkgroups Shown |
|------|------------------|
| OFF | (no context menu) |
| ALL | All talkgroups from all alias lists |
| SYSTEM | Talkgroups from aliases in the system's alias list |
| GROUP | Talkgroups from aliases in the selected group |

```java
private List<TalkgroupMenuItem> buildTalkgroupList()
{
    List<TalkgroupMenuItem> items = new ArrayList<>();
    AudioChannelFilter filter = mAudioChannel.getFilter();

    switch(filter.getMode())
    {
        case ALL:
            // All talkgroups from all aliases
            for(Alias alias : mAliasModel.getAliases())
            {
                addTalkgroupsFromAlias(alias, items);
            }
            break;

        case SYSTEM:
            // Talkgroups from the matching alias list
            AliasList aliasList = mAliasModel.getAliasList(filter.getFilterValue());
            if(aliasList != null)
            {
                for(Alias alias : aliasList.aliases())
                {
                    addTalkgroupsFromAlias(alias, items);
                }
            }
            break;

        case GROUP:
            // Talkgroups from aliases in the selected group
            for(Alias alias : mAliasModel.getAliases())
            {
                if(filter.getFilterValue().equals(alias.getGroup()))
                {
                    addTalkgroupsFromAlias(alias, items);
                }
            }
            break;

        default:
            break;
    }

    items.sort(Comparator.comparingInt(TalkgroupMenuItem::getTalkgroupId));
    return items;
}
```

### Context Menu Appearance

```java
private JPopupMenu createTalkgroupMuteMenu()
{
    JPopupMenu menu = new JPopupMenu();
    List<TalkgroupMenuItem> talkgroups = buildTalkgroupList();

    for(TalkgroupMenuItem tg : talkgroups)
    {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(tg.getLabel());
        item.setSelected(!mAudioChannel.getFilter().isTalkgroupMuted(tg.getTalkgroupId()));
        item.addActionListener(e -> {
            boolean currentlyMuted = mAudioChannel.getFilter().isTalkgroupMuted(tg.getTalkgroupId());
            mAudioChannel.getFilter().setTalkgroupMuted(tg.getTalkgroupId(), !currentlyMuted);
        });
        menu.add(item);
    }

    menu.addSeparator();
    JMenuItem unmuteAll = new JMenuItem("Unmute All");
    unmuteAll.addActionListener(e -> mAudioChannel.getFilter().unmuteAllTalkgroups());
    menu.add(unmuteAll);

    JMenuItem muteAll = new JMenuItem("Mute All");
    muteAll.addActionListener(e -> {
        for(TalkgroupMenuItem tg : talkgroups)
        {
            mAudioChannel.getFilter().setTalkgroupMuted(tg.getTalkgroupId(), true);
        }
    });
    menu.add(muteAll);

    return menu;
}
```

---

## 9. Mute Button Fix

### Problem

The `MuteButton` in `AudioPanel` maintains its own `mMuted` boolean:

```java
public class MuteButton extends JButton
{
    private boolean mMuted = false;  // ← local state, not synced

    public MuteButton()
    {
        addActionListener(e -> {
            mMuted = !mMuted;
            mAudioPlaybackManager.getAudioOutput().setMuted(mMuted);
            // Update icon...
        });
    }
}
```

When the audio output is recreated (device change), the button's local `mMuted` doesn't reset.

### Fix

1. **Remove local `mMuted` state** — always read from `AudioOutput.isMuted()`
2. **Handle null audio output** gracefully
3. **Sync on audio config change** — when `AUDIO_CONFIGURATION_CHANGE_COMPLETE` fires,
   reset the button icon to match the new output's mute state

```java
public class MuteButton extends JButton
{
    public MuteButton()
    {
        setIcon(UNMUTED_ICON);
        addActionListener(e -> {
            AudioOutput output = mAudioPlaybackManager.getAudioOutput();
            if(output != null)
            {
                boolean newMuted = !output.isMuted();
                output.setMuted(newMuted);
                updateIcon(newMuted);
            }
        });
    }

    public void syncState()
    {
        AudioOutput output = mAudioPlaybackManager.getAudioOutput();
        boolean muted = (output != null && output.isMuted());
        updateIcon(muted);
    }

    private void updateIcon(boolean muted)
    {
        EventQueue.invokeLater(() -> {
            setIcon(muted ? MUTED_ICON : UNMUTED_ICON);
            getAccessibleContext().setAccessibleName(muted ? "Unmute" : "Mute");
        });
    }
}
```

And in `AudioPanel.receive()`:

```java
case AUDIO_CONFIGURATION_CHANGE_COMPLETE:
    EventQueue.invokeLater(() -> {
        // ... existing panel rebuild code ...
        mMuteButton.syncState();  // ← add this
    });
    break;
```

### Interaction Between Global Mute and Per-Talkgroup Mute

| Global Mute | TG Mute | Audio Output |
|-------------|---------|--------------|
| Unmuted | Unmuted | Audio plays |
| Unmuted | Muted | Silence (per-TG mute) |
| Muted | Unmuted | Silence (global mute) |
| Muted | Muted | Silence (both) |

Global mute takes precedence — when globally muted, all audio is silenced regardless of
per-talkgroup state. The per-talkgroup mute state is preserved so that when global mute is
toggled off, the per-talkgroup mutes resume their effect.

---

## 10. Files to Modify

### Core Audio Changes

| # | File | Change Summary | Lines ±Est |
|---|------|----------------|------------|
| 1 | `audio/playback/AudioChannel.java` | Add `AudioChannelFilter` field, integrate TG mute in `getAudio()`, remove stall threshold change from prior | +30/-2 |
| 2 | `audio/playback/AudioChannelPanel.java` | Add routing combo box, right-click TG mute menu, refresh on alias changes | +250 |
| 3 | `audio/playback/AudioPlaybackManager.java` | Filter-aware segment assignment, pass `AliasModel` reference | +40/-15 |
| 4 | `audio/playback/AudioPanel.java` | Fix `MuteButton` sync, add `syncState()` call on config change | +15/-10 |

### Optional Cleanup (from prior, independent)

| # | File | Change Summary | Lines ±Est |
|---|------|----------------|------------|
| 5 | `audio/playback/AudioPlaybackDeviceManager.java` | Simplify device discovery (optional, from prior) | -30 |
| 6 | `audio/playback/AudioPlaybackDeviceDescriptor.java` | Simplify `getCleanDescription()` (optional, from prior) | -10 |

---

## 11. New Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `AudioChannelFilterMode` | `audio.playback` | Enum: OFF, ALL, SYSTEM, GROUP |
| `AudioChannelFilter` | `audio.playback` | Encapsulates routing mode + filter value + per-TG mute set |
| `AudioChannelFilterItem` | `audio.playback` | Combo box model item (mode + label + value) |

### Class: `AudioChannelFilterMode`

```java
package io.github.dsheirer.audio.playback;

/**
 * Audio channel routing filter modes.
 */
public enum AudioChannelFilterMode
{
    OFF("Off"),
    ALL("All"),
    SYSTEM("System"),
    GROUP("Group");

    private final String mLabel;

    AudioChannelFilterMode(String label)
    {
        mLabel = label;
    }

    public String getLabel()
    {
        return mLabel;
    }
}
```

### Class: `AudioChannelFilter`

```java
package io.github.dsheirer.audio.playback;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Audio channel routing filter with per-talkgroup mute control.
 */
public class AudioChannelFilter
{
    private AudioChannelFilterMode mMode = AudioChannelFilterMode.ALL;
    private String mFilterValue = null;
    private final Set<Integer> mMutedTalkgroups = ConcurrentHashMap.newKeySet();

    /**
     * Current filter mode
     */
    public AudioChannelFilterMode getMode()
    {
        return mMode;
    }

    /**
     * Filter value (system name or group name), null for OFF/ALL modes
     */
    public String getFilterValue()
    {
        return mFilterValue;
    }

    /**
     * Sets the routing filter mode and value.
     * Clears per-talkgroup mute state when the filter changes.
     */
    public void setFilter(AudioChannelFilterMode mode, String value)
    {
        mMode = mode;
        mFilterValue = value;
        mMutedTalkgroups.clear();
    }

    /**
     * Indicates if this channel is off (disabled)
     */
    public boolean isOff()
    {
        return mMode == AudioChannelFilterMode.OFF;
    }

    /**
     * Determines if the audio segment is accepted by this filter.
     * Does NOT consider per-talkgroup mute — that is handled separately.
     */
    public boolean accepts(AudioSegment segment, AliasModel aliasModel)
    {
        if(mMode == AudioChannelFilterMode.OFF)
        {
            return false;
        }

        if(mMode == AudioChannelFilterMode.ALL)
        {
            return true;
        }

        IdentifierCollection identifiers = segment.getIdentifierCollection();

        if(identifiers == null)
        {
            return false;
        }

        if(mMode == AudioChannelFilterMode.SYSTEM)
        {
            AliasListConfigurationIdentifier aliasConfig = identifiers.getAliasListConfiguration();
            return aliasConfig != null && mFilterValue != null
                && mFilterValue.equals(aliasConfig.getValue());
        }

        if(mMode == AudioChannelFilterMode.GROUP)
        {
            List<Identifier> toIds = identifiers.getIdentifiers(IdentifierClass.USER, Role.TO);

            for(Identifier id : toIds)
            {
                AliasList aliasList = aliasModel.getAliasList(identifiers);

                if(aliasList != null)
                {
                    List<Alias> aliases = aliasList.getAliases(id);

                    for(Alias alias : aliases)
                    {
                        if(mFilterValue != null && mFilterValue.equals(alias.getGroup()))
                        {
                            return true;
                        }
                    }
                }
            }

            return false;
        }

        return false;
    }

    /**
     * Checks if the current audio segment's talkgroup is in the per-TG mute set
     */
    public boolean isTalkgroupMutedForSegment(AudioSegment segment)
    {
        if(mMutedTalkgroups.isEmpty() || segment == null)
        {
            return false;
        }

        IdentifierCollection identifiers = segment.getIdentifierCollection();

        if(identifiers == null)
        {
            return false;
        }

        List<Identifier> toIds = identifiers.getIdentifiers(IdentifierClass.USER, Role.TO);

        for(Identifier id : toIds)
        {
            if(id instanceof TalkgroupIdentifier tgId)
            {
                if(mMutedTalkgroups.contains(tgId.getValue()))
                {
                    return true;
                }
            }
        }

        return false;
    }

    // Per-talkgroup mute management
    public boolean isTalkgroupMuted(int talkgroupId) { return mMutedTalkgroups.contains(talkgroupId); }
    public void setTalkgroupMuted(int talkgroupId, boolean muted)
    {
        if(muted) { mMutedTalkgroups.add(talkgroupId); }
        else { mMutedTalkgroups.remove(talkgroupId); }
    }
    public void muteAllTalkgroups(Set<Integer> talkgroups) { mMutedTalkgroups.addAll(talkgroups); }
    public void unmuteAllTalkgroups() { mMutedTalkgroups.clear(); }
    public Set<Integer> getMutedTalkgroups() { return Set.copyOf(mMutedTalkgroups); }
}
```

### Class: `AudioChannelFilterItem`

```java
package io.github.dsheirer.audio.playback;

/**
 * Combo box model item for audio channel routing filter selection.
 */
public class AudioChannelFilterItem
{
    private final AudioChannelFilterMode mMode;
    private final String mValue;
    private final String mLabel;

    public AudioChannelFilterItem(AudioChannelFilterMode mode, String value, String label)
    {
        mMode = mode;
        mValue = value;
        mLabel = label;
    }

    public AudioChannelFilterMode getMode() { return mMode; }
    public String getValue() { return mValue; }

    @Override
    public String toString() { return mLabel; }
}
```

---

## 12. Implementation Plan

### Phase A: Core Filter Infrastructure (no UI)

1. Create `AudioChannelFilterMode` enum
2. Create `AudioChannelFilter` class with `accepts()` and TG mute methods
3. Create `AudioChannelFilterItem` class
4. Add `AudioChannelFilter` field to `AudioChannel`
5. Integrate per-TG mute check in `AudioChannel.getAudio()`
6. Update `AudioPlaybackManager.processAudioSegments()` to use filter matching
7. Pass `AliasModel` reference to `AudioPlaybackManager` (constructor change)

### Phase B: UI — Routing Combo Box

1. Add `JComboBox<AudioChannelFilterItem>` to `AudioChannelPanel`
2. Populate with Off, All, systems (from `AliasModel.getListNames()`), groups (from `AliasModel.getGroupNames()`)
3. Listen for combo selection → update `AudioChannel.getFilter()`
4. Register `ListChangeListener` on `AliasModel.aliasList()` for dynamic refresh
5. Handle separator rendering in combo (custom `ListCellRenderer`)

### Phase C: UI — Per-Talkgroup Mute Context Menu

1. Add `MouseListener` to `AudioChannelPanel` for right-click
2. Build talkgroup list from current filter scope
3. Show `JPopupMenu` with `JCheckBoxMenuItem` per talkgroup
4. Add "Mute All" / "Unmute All" options

### Phase D: Mute Button Fix

1. Remove local `mMuted` from `MuteButton`
2. Add `syncState()` method to `MuteButton`
3. Call `syncState()` on `AUDIO_CONFIGURATION_CHANGE_COMPLETE`
4. Verify mute works in all combinations (global mute + per-TG mute + routing filter)

### Phase E: Optional Cleanup

1. Simplify `AudioPlaybackDeviceManager` (optional, from prior)
2. Simplify `AudioPlaybackDeviceDescriptor` (optional, from prior)

---

## 13. Testing

### Manual Test Matrix

| Test | Steps | Expected |
|------|-------|----------|
| Default state | Start app, stereo output | Both channels show "All", audio plays on both |
| Set LEFT to Off | Select "Off" on LEFT dropdown | LEFT goes silent, RIGHT still plays all audio |
| Set LEFT to System | Select a system name | LEFT only plays audio from that system |
| Set LEFT to Group | Select a group name | LEFT only plays audio from talkgroups in that group |
| Global mute | Click mute button | Both channels go silent, M indicators show |
| Global unmute | Click mute button again | Both channels resume, M indicators hide |
| Per-TG mute | Right-click LEFT → uncheck a talkgroup | That talkgroup's audio goes silent on LEFT |
| Per-TG unmute | Right-click LEFT → check a talkgroup | That talkgroup's audio resumes on LEFT |
| Mute + filter | Set filter to Group, then global mute | Both silent. Unmute → only group audio plays |
| Per-TG mute + global mute | Per-TG mute TG 300, then global mute/unmute | After unmute, TG 300 still silent, others play |
| Device change | Change audio device in preferences | Channels recreated, mute state synced, filters reset to ALL |
| Alias refresh | Load a new playlist | Combo box repopulates with new systems/groups |
| Mono output | Select mono audio device | Single channel shows combo, filter works |

### Edge Cases

| Case | Expected Behavior |
|------|-------------------|
| Both channels set to same system | Both play same system audio, segments round-robin |
| Both channels OFF | No audio plays, segments accumulate then age out |
| LEFT=System A, RIGHT=System B | Audio routes to appropriate channel by system |
| No aliases loaded yet | Combo shows only "Off" and "All" (no systems/groups) |
| Filter set to group, then group renamed | Combo refreshes, filter value may become stale → revert to ALL |
| Audio segment with no identifiers | Only accepted by ALL mode channels |

---

## Appendix: Architecture Diagram

```
                    ┌─────────────────────────┐
                    │    Audio Modules         │
                    │  (P25, DMR, etc.)        │
                    └──────────┬──────────────┘
                               │ AudioSegment
                               ▼
                    ┌─────────────────────────┐
                    │ AudioPlaybackManager    │
                    │                         │
                    │  processAudioSegments() │
                    │    ┌─────────────┐      │
                    │    │ For each    │      │
                    │    │ segment:    │      │
                    │    │             │      │
                    │    │ 1. Dup?     │      │
                    │    │ 2. DNM?     │      │
                    │    │ 3. Linked?  │      │
                    │    │ 4. Filter   │◄─────┼─── AliasModel (systems, groups)
                    │    │    match?   │      │
                    │    └──────┬──────┘      │
                    └───────────┼──────────────┘
                       ┌────────┴────────┐
                       ▼                 ▼
              ┌──────────────┐  ┌──────────────┐
              │ AudioChannel │  │ AudioChannel │
              │   (LEFT)     │  │   (RIGHT)    │
              │              │  │              │
              │ Filter:      │  │ Filter:      │
              │  [GROUP:     │  │  [ALL]       │
              │   Fire Disp] │  │              │
              │              │  │              │
              │ Muted TGs:   │  │ Muted TGs:  │
              │  {301}       │  │  {}          │
              │              │  │              │
              │ getAudio():  │  │ getAudio():  │
              │  1. Get buf  │  │  1. Get buf  │
              │  2. Muted?   │  │  2. Muted?   │
              │     →silence │  │     →silence │
              │  3. TG mute? │  │  3. TG mute? │
              │     →silence │  │     →silence │
              │  4. Return   │  │  4. Return   │
              └──────┬───────┘  └──────┬───────┘
                     │                  │
                     ▼                  ▼
              ┌───────────────────────────────┐
              │        AudioOutput            │
              │   (SourceDataLine / Mixer)    │
              │                               │
              │   AudioProviderStereo:        │
              │   Interleave LEFT + RIGHT     │
              │   → Write to DataLine         │
              └───────────────────────────────┘
```

---

## Appendix: What NOT to Bring from Prior Implementation

| Prior Change | Decision | Reason |
|-------------|----------|--------|
| DDC stall threshold (6→20 intervals) | ❌ Skip | We use polyphase channelizer, not DDC |
| Logger rename (`LOGGER` → `mLog`) | ✅ Accept | Cosmetic, consistent with codebase style |
| `matchesTalkgroupFilter()` in `AudioChannel.play()` | ❌ Replace | Root cause of mute bug — filtering must happen in manager |
| `TalkgroupFilterItem` / flat TG combo | ❌ Replace | Replaced with hierarchical `AudioChannelFilterItem` |
| `AudioPlaybackDeviceManager` simplification | ✅ Accept | Clean independent refactor |
| `AudioPlaybackDeviceDescriptor` simplification | ⚠️ Partial | Accept the simplification but keep ALSA handling for Linux |
