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
package io.github.dsheirer.module.decode.session;

/**
 * Indicates whether a decode event or call session event originated from
 * the control channel or a traffic channel.
 *
 * Useful for debugging, log analysis, and UI display.
 */
public enum ChannelSourceType
{
    CONTROL("Control"),
    TRAFFIC("Traffic"),
    UNKNOWN("Unknown");

    private final String mLabel;

    ChannelSourceType(String label)
    {
        mLabel = label;
    }

    public String getLabel()
    {
        return mLabel;
    }

    @Override
    public String toString()
    {
        return mLabel;
    }
}
