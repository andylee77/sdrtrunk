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
package io.github.dsheirer.gui.analyzer;

/**
 * Modulation types that can be detected by signal characterization.
 */
public enum ModulationType
{
    FM_NARROW("NFM", "Narrowband FM", false),
    FM_WIDE("WFM", "Wideband FM", false),
    AM("AM", "Amplitude Modulation", false),
    C4FM("C4FM", "Continuous 4-level FM", true),
    FSK2("2FSK", "2-level FSK", true),
    FSK4("4FSK", "4-level FSK", true),
    BPSK("BPSK", "Binary Phase Shift Keying", true),
    QPSK("QPSK", "Quadrature PSK", true),
    DQPSK("DQPSK", "Differential QPSK", true),
    PI4_DQPSK("π/4-DQPSK", "π/4 Differential QPSK", true),
    CARRIER_ONLY("CW", "Carrier/CW", false),
    UNKNOWN_DIGITAL("UNK-D", "Unknown Digital", true),
    UNKNOWN_ANALOG("UNK-A", "Unknown Analog", false),
    UNKNOWN("UNK", "Unknown", false);

    private final String mShortName;
    private final String mDisplayName;
    private final boolean mDigital;

    ModulationType(String shortName, String displayName, boolean digital)
    {
        mShortName = shortName;
        mDisplayName = displayName;
        mDigital = digital;
    }

    public String getShortName()
    {
        return mShortName;
    }

    public String getDisplayName()
    {
        return mDisplayName;
    }

    public boolean isDigital()
    {
        return mDigital;
    }

    @Override
    public String toString()
    {
        return mShortName;
    }
}
