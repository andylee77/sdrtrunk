/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
package io.github.dsheirer.gui.analyzer.detection;

/**
 * Flags for classifying detected signals as real or artifacts.
 */
public enum SignalFlag
{
    DC_SPIKE("DC Spike", "Near center frequency, likely LO leakage"),
    IMAGE("Image", "Mirror of another signal around center frequency"),
    HARMONIC("Harmonic", "Appears to be harmonic of lower-frequency signal"),
    SPUR("Spur", "Moves with retune, not a real signal"),
    INTERMITTENT("Intermittent", "Bursty, not always present"),
    CONTINUOUS("Continuous", "Always present (carrier or control channel)"),
    WIDEBAND("Wideband", "Wider than typical narrowband channel"),
    NARROWBAND("Narrowband", "Typical narrowband channel");

    private final String mLabel;
    private final String mDescription;

    SignalFlag(String label, String description)
    {
        mLabel = label;
        mDescription = description;
    }

    public String getLabel()
    {
        return mLabel;
    }

    public String getDescription()
    {
        return mDescription;
    }

    @Override
    public String toString()
    {
        return mLabel;
    }
}
