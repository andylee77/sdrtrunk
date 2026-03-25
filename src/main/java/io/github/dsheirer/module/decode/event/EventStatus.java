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
package io.github.dsheirer.module.decode.event;

import java.awt.Color;

/**
 * Status indicator for decode events displayed in the Events tab.
 * Provides color-coded visual feedback about the system's handling of each event.
 */
public enum EventStatus
{
    /** Traffic channel allocated and actively processing voice/data */
    ACTIVE_TRAFFIC("Active (Traffic)", new Color(0, 180, 0)),

    /** Control channel tracking only — no traffic channel allocated */
    ACTIVE_CONTROL("Active (Control)", new Color(220, 180, 0)),

    /** Call has completed normally */
    ENDED("Ended", new Color(180, 0, 0)),

    /** Call was ignored (encrypted, unmonitored, data filtered) */
    IGNORED("Ignored", new Color(140, 140, 140)),

    /** Default/unknown status (non-P25 events or before status is set) */
    UNKNOWN("", null);

    private final String mLabel;
    private final Color mColor;

    EventStatus(String label, Color color)
    {
        mLabel = label;
        mColor = color;
    }

    public String getLabel()
    {
        return mLabel;
    }

    public Color getColor()
    {
        return mColor;
    }
}
