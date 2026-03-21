/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.audio.playback;

/**
 * Audio channel routing filter modes.
 *
 * OFF - Channel is disabled, no audio segments are offered
 * ALL - Accepts all audio segments (default behavior)
 * SYSTEM - Only accepts segments from channels configured for a specific system (alias list)
 * GROUP - Only accepts segments where the TO talkgroup has an alias in a specific group
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
