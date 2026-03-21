# 009 — Fix Audio Volume Slider

## Summary

Fixed the audio volume slider in the AudioPanel that was too small to interact with
and was not functioning correctly due to use of `FloatControl.shift()`.

## Problems

1. **Slider too small**: The volume slider was a vertical `JSlider` with
   `setPreferredSize(new Dimension(30, 40))` — only 40 pixels tall. In the AudioPanel's
   thin horizontal toolbar layout (~30-40px tall), the slider thumb was larger than the
   usable track, making it impossible to drag.

2. **Layout too narrow**: The column for the slider was only `50lp` wide, further
   constraining the already tiny vertical slider.

3. **`gain.shift()` unreliable**: Both the in-panel slider and the right-click popup
   `VolumeSlider` used `FloatControl.shift(from, to, microseconds)` which starts a
   threaded transition. When called rapidly during slider dragging, each call spawned
   a competing transition, making volume changes erratic or non-functional.

## Changes

### `AudioPanel.java`

- **Changed slider orientation** from `JSlider.VERTICAL` to `JSlider.HORIZONTAL` — a
  horizontal slider fits naturally in the toolbar's height and provides a usable track
  length.

- **Widened layout column** from `[50lp!]` to `[180lp!]` to give the horizontal slider
  enough room for comfortable interaction.

- **Removed fixed preferred size** — the old `setPreferredSize(new Dimension(30, 40))`
  was artificially constraining the slider. Now it sizes naturally within its layout cell.

- **Changed `gain.shift()` to `gain.setValue()`** — both the in-panel slider's
  `addChangeListener` and the right-click popup `VolumeSlider`'s `addChangeListener`
  now call `FloatControl.setValue(target)` directly for immediate, reliable volume changes.

- **Cleaned up unused `Dimension` import**.

## Files Modified

- `src/main/java/io/github/dsheirer/audio/playback/AudioPanel.java`
