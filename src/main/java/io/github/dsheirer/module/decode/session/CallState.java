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
 * Lifecycle state for a call session.
 *
 * Protocol-agnostic — usable by P25, DMR, NXDN, or any trunked protocol.
 *
 * PENDING  - Channel grant received, waiting for first voice/data
 * ACTIVE   - Voice/data in progress, audio playing, recording, etc.
 * ENDING   - Call termination signaled or gap started; grace period before finalization.
 *            Same call resuming within gap threshold re-activates (back to ACTIVE).
 * COMPLETE - Call finalized, recording closed, event row settled.
 */
public enum CallState
{
    PENDING("Pending"),
    ACTIVE("Active"),
    ENDING("Ending"),
    COMPLETE("Complete");

    private final String mLabel;

    CallState(String label)
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
