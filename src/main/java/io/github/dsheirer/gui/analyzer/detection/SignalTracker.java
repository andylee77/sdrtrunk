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

import io.github.dsheirer.gui.analyzer.AnalysisStatus;
import io.github.dsheirer.gui.analyzer.SignalAnalyzerConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks signals across multiple detection cycles, managing signal persistence,
 * appearance/disappearance, and merging new detections with previously known signals.
 *
 * Signals must be detected for at least {@code persistenceThreshold} consecutive
 * cycles to be confirmed as persistent. Signals that haven't been seen for
 * {@code staleTimeout} are removed.
 */
public class SignalTracker
{
    private static final Logger mLog = LoggerFactory.getLogger(SignalTracker.class);

    /**
     * Tolerance for frequency matching when correlating new detections with existing signals.
     * A new detection within this tolerance of an existing signal is considered the same signal.
     */
    private static final double FREQUENCY_MATCH_TOLERANCE_HZ = 2000.0;

    /**
     * Maximum age before a signal is considered stale and removed.
     */
    private static final Duration STALE_TIMEOUT = Duration.ofSeconds(30);

    private final SignalAnalyzerConfig mConfig;
    private final CopyOnWriteArrayList<DetectedSignal> mTrackedSignals = new CopyOnWriteArrayList<>();

    public SignalTracker(SignalAnalyzerConfig config)
    {
        mConfig = config;
    }

    /**
     * Get all currently tracked signals.
     */
    public List<DetectedSignal> getTrackedSignals()
    {
        return new ArrayList<>(mTrackedSignals);
    }

    /**
     * Get the count of tracked signals.
     */
    public int getSignalCount()
    {
        return mTrackedSignals.size();
    }

    /**
     * Process a new batch of detected signals from the SignalDetector.
     * Merges with existing tracked signals, updating matches and adding new ones.
     *
     * @param newDetections the signals detected in the latest cycle
     * @return the full list of currently tracked signals (including newly added)
     */
    public List<DetectedSignal> processDetections(List<DetectedSignal> newDetections)
    {
        Instant now = Instant.now();

        // Track which existing signals were matched
        boolean[] matched = new boolean[mTrackedSignals.size()];

        for(DetectedSignal newSignal : newDetections)
        {
            DetectedSignal bestMatch = null;
            int bestMatchIndex = -1;
            double bestMatchDistance = Double.MAX_VALUE;

            // Find closest matching existing signal
            for(int i = 0; i < mTrackedSignals.size(); i++)
            {
                DetectedSignal existing = mTrackedSignals.get(i);

                // Skip ignored signals
                if(existing.getStatus() == AnalysisStatus.IGNORED)
                {
                    continue;
                }

                double distance = Math.abs(existing.getFrequencyHz() - newSignal.getFrequencyHz());

                if(distance < FREQUENCY_MATCH_TOLERANCE_HZ && distance < bestMatchDistance)
                {
                    bestMatch = existing;
                    bestMatchIndex = i;
                    bestMatchDistance = distance;
                }
            }

            if(bestMatch != null)
            {
                // Update existing signal with new detection data
                bestMatch.incrementDetectionCount();
                // Smooth the power reading with exponential moving average
                double alpha = 0.3;
                bestMatch.setPowerDb(bestMatch.getPowerDb() * (1.0 - alpha) + newSignal.getPowerDb() * alpha);
                // Smooth the bandwidth too
                bestMatch.setBandwidthHz(bestMatch.getBandwidthHz() * (1.0 - alpha) + newSignal.getBandwidthHz() * alpha);

                // Check persistence threshold
                if(!bestMatch.isPersistent() && bestMatch.getDetectionCount() >= mConfig.getPersistenceThreshold())
                {
                    bestMatch.setPersistent(true);
                    bestMatch.addFlag(SignalFlag.CONTINUOUS);
                }

                matched[bestMatchIndex] = true;
            }
            else
            {
                // New signal — add to tracking
                mTrackedSignals.add(newSignal);
            }
        }

        // Remove stale signals that haven't been seen recently
        removeStaleSignals(now);

        return new ArrayList<>(mTrackedSignals);
    }

    /**
     * Remove signals that haven't been detected within the stale timeout period.
     * Signals that are IDENTIFIED or IGNORED are retained regardless of staleness.
     */
    private void removeStaleSignals(Instant now)
    {
        Iterator<DetectedSignal> iterator = mTrackedSignals.iterator();
        while(iterator.hasNext())
        {
            DetectedSignal signal = iterator.next();

            // Don't remove identified or ignored signals
            if(signal.getStatus() == AnalysisStatus.IDENTIFIED ||
               signal.getStatus() == AnalysisStatus.IGNORED)
            {
                continue;
            }

            Duration age = Duration.between(signal.getLastSeen(), now);
            if(age.compareTo(STALE_TIMEOUT) > 0)
            {
                mTrackedSignals.remove(signal);
            }
        }
    }

    /**
     * Mark a signal as ignored (won't be re-analyzed).
     */
    public void ignoreSignal(DetectedSignal signal)
    {
        signal.setStatus(AnalysisStatus.IGNORED);
    }

    /**
     * Remove a specific signal from tracking.
     */
    public void removeSignal(DetectedSignal signal)
    {
        mTrackedSignals.remove(signal);
    }

    /**
     * Clear all tracked signals.
     */
    public void clearAll()
    {
        mTrackedSignals.clear();
    }

    /**
     * Remove all signals that are flagged as artifacts.
     */
    public void removeArtifacts()
    {
        mTrackedSignals.removeIf(DetectedSignal::isArtifact);
    }
}
