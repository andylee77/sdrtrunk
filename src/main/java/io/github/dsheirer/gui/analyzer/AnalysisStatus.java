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
 * Status of a signal through the analysis pipeline.
 */
public enum AnalysisStatus
{
    DETECTED("Detected", "Just found in FFT"),
    VALIDATING("Validating", "Checking for harmonics/spurs"),
    CHARACTERIZING("Characterizing", "Running modulation analysis"),
    IDENTIFYING("Identifying", "Running decoder trials"),
    IDENTIFIED("Identified", "Protocol successfully identified"),
    TENTATIVE("Tentative", "Sync found but no valid frames (likely encrypted/intermittent)"),
    HEURISTIC("Heuristic", "Protocol suggested by signal analysis (no decoder available)"),
    NO_MATCH("No Match", "All decoders tried, none matched"),
    FLAGGED("Flagged", "Flagged as spur/harmonic/image"),
    IGNORED("Ignored", "User chose to ignore");

    private final String mLabel;
    private final String mDescription;

    AnalysisStatus(String label, String description)
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
