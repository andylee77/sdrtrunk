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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Analyzes detected signals for harmonics, images, DC spurs, and other artifacts.
 * Sets appropriate SignalFlag values on each DetectedSignal and updates status
 * to FLAGGED for identified artifacts.
 */
public class HarmonicAnalyzer
{
    private static final Logger mLog = LoggerFactory.getLogger(HarmonicAnalyzer.class);

    private final SignalAnalyzerConfig mConfig;
    private long mCenterFrequencyHz;

    public HarmonicAnalyzer(SignalAnalyzerConfig config)
    {
        mConfig = config;
    }

    /**
     * Set the current tuner center frequency for DC spike detection.
     */
    public void setCenterFrequency(long centerFrequencyHz)
    {
        mCenterFrequencyHz = centerFrequencyHz;
    }

    /**
     * Analyze a list of detected signals and set appropriate flags.
     * Runs all validation checks: DC spikes, images, harmonics.
     */
    public void analyze(List<DetectedSignal> signals)
    {
        flagDcSpikes(signals);
        flagImages(signals);
        flagHarmonics(signals);
    }

    /**
     * Signals within ±dcSpikeMargin of the center frequency are flagged as DC_SPIKE.
     * These are typically caused by LO leakage in the SDR hardware.
     */
    private void flagDcSpikes(List<DetectedSignal> signals)
    {
        double margin = mConfig.getDcSpikeMarginHz();

        for(DetectedSignal signal : signals)
        {
            if(signal.getStatus() == AnalysisStatus.IGNORED)
            {
                continue;
            }

            double distanceFromCenter = Math.abs(signal.getFrequencyHz() - mCenterFrequencyHz);

            if(distanceFromCenter <= margin)
            {
                signal.addFlag(SignalFlag.DC_SPIKE);
                if(signal.getStatus() == AnalysisStatus.DETECTED)
                {
                    signal.setStatus(AnalysisStatus.FLAGGED);
                }
            }
        }
    }

    /**
     * Check for image signals — mirrors of real signals around the center frequency.
     * For each signal, check if there's a corresponding signal at (2 * center - freq).
     * If both exist with similar power (within 6 dB), the weaker one is flagged as an image.
     */
    private void flagImages(List<DetectedSignal> signals)
    {
        double marginHz = mConfig.getImageMarginHz();
        double powerToleranceDb = 6.0;

        for(int i = 0; i < signals.size(); i++)
        {
            DetectedSignal signalA = signals.get(i);

            if(signalA.getStatus() == AnalysisStatus.IGNORED || signalA.hasFlag(SignalFlag.DC_SPIKE))
            {
                continue;
            }

            // Calculate mirror frequency
            long mirrorFreq = 2 * mCenterFrequencyHz - signalA.getFrequencyHz();

            for(int j = i + 1; j < signals.size(); j++)
            {
                DetectedSignal signalB = signals.get(j);

                if(signalB.getStatus() == AnalysisStatus.IGNORED || signalB.hasFlag(SignalFlag.DC_SPIKE))
                {
                    continue;
                }

                // Check if signalB is at the mirror frequency of signalA
                if(Math.abs(signalB.getFrequencyHz() - mirrorFreq) <= marginHz)
                {
                    // Similar power suggests one is an image of the other
                    double powerDiff = Math.abs(signalA.getPowerDb() - signalB.getPowerDb());

                    if(powerDiff <= powerToleranceDb)
                    {
                        // Flag the weaker signal as image
                        DetectedSignal image = signalA.getPowerDb() < signalB.getPowerDb() ? signalA : signalB;
                        image.addFlag(SignalFlag.IMAGE);
                        if(image.getStatus() == AnalysisStatus.DETECTED)
                        {
                            image.setStatus(AnalysisStatus.FLAGGED);
                        }
                    }
                }
            }
        }
    }

    /**
     * Check for integer frequency relationships (f, 2f, 3f).
     * The lower-frequency signal is assumed to be the fundamental;
     * higher-frequency matches are flagged as harmonics.
     *
     * Note: This check operates on absolute frequencies, so it can detect harmonics
     * that fall within the visible bandwidth even if the fundamental is at a much
     * lower frequency.
     */
    private void flagHarmonics(List<DetectedSignal> signals)
    {
        double tolerance = mConfig.getHarmonicTolerance();

        for(int i = 0; i < signals.size(); i++)
        {
            DetectedSignal signalA = signals.get(i);

            if(signalA.getStatus() == AnalysisStatus.IGNORED ||
               signalA.hasFlag(SignalFlag.DC_SPIKE) ||
               signalA.hasFlag(SignalFlag.IMAGE))
            {
                continue;
            }

            for(int j = i + 1; j < signals.size(); j++)
            {
                DetectedSignal signalB = signals.get(j);

                if(signalB.getStatus() == AnalysisStatus.IGNORED ||
                   signalB.hasFlag(SignalFlag.DC_SPIKE) ||
                   signalB.hasFlag(SignalFlag.IMAGE))
                {
                    continue;
                }

                // Check if one is a harmonic of the other (2f, 3f, 4f)
                long freqLow = Math.min(signalA.getFrequencyHz(), signalB.getFrequencyHz());
                long freqHigh = Math.max(signalA.getFrequencyHz(), signalB.getFrequencyHz());

                if(freqLow <= 0)
                {
                    continue;
                }

                double ratio = (double)freqHigh / (double)freqLow;

                // Check for integer ratios 2, 3, 4
                for(int harmonic = 2; harmonic <= 4; harmonic++)
                {
                    if(Math.abs(ratio - harmonic) <= tolerance * harmonic)
                    {
                        // Flag the higher frequency signal as a harmonic
                        DetectedSignal harmonicSignal = signalA.getFrequencyHz() > signalB.getFrequencyHz()
                            ? signalA : signalB;
                        harmonicSignal.addFlag(SignalFlag.HARMONIC);
                        if(harmonicSignal.getStatus() == AnalysisStatus.DETECTED)
                        {
                            harmonicSignal.setStatus(AnalysisStatus.FLAGGED);
                        }
                        break;
                    }
                }
            }
        }
    }
}
