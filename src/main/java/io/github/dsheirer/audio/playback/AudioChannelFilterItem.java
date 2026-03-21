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
 * Combo box model item for audio channel routing filter selection.
 *
 * Wraps a filter mode (OFF, ALL, SYSTEM, GROUP) with an optional value (system name or group name)
 * and a display label for the combo box.
 *
 * Special items with a null mode are used as visual separators in the dropdown.
 */
public class AudioChannelFilterItem
{
    private final AudioChannelFilterMode mMode;
    private final String mValue;
    private final String mLabel;

    /**
     * Constructs an instance
     *
     * @param mode the filter mode (null for separator items)
     * @param value the filter value (system name or group name, null for OFF/ALL)
     * @param label the display text for the combo box
     */
    public AudioChannelFilterItem(AudioChannelFilterMode mode, String value, String label)
    {
        mMode = mode;
        mValue = value;
        mLabel = label;
    }

    /**
     * Filter mode for this item
     */
    public AudioChannelFilterMode getMode()
    {
        return mMode;
    }

    /**
     * Filter value (system name or group name), null for OFF/ALL modes
     */
    public String getValue()
    {
        return mValue;
    }

    /**
     * Indicates if this item is a separator (non-selectable visual divider)
     */
    public boolean isSeparator()
    {
        return mMode == null;
    }

    @Override
    public String toString()
    {
        return mLabel;
    }
}
