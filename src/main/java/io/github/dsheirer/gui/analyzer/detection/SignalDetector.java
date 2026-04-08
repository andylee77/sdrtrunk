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

import io.github.dsheirer.gui.analyzer.SignalAnalyzerConfig;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.spectrum.DFTResultsListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens to DFT results (float[] dB values) and detects signals above the noise floor.
 * Implements DFTResultsListener to tap into the existing FFT pipeline.
 *
 * The detector accumulates multiple DFT frames into a rolling average, estimates the noise
 * floor using median calculation, and finds peaks above a configurable threshold.
 * Adjacent bins above threshold are clustered into signal groups.
 */
public class SignalDetector implements DFTResultsListener
{
    private static final Logger mLog = LoggerFactory.getLogger(SignalDetector.class);

    private final SignalAnalyzerConfig mConfig;

    // Tuner state
    private long mCenterFrequencyHz;
    private int mBandwidthHz;
    private int mDftSize;

    // Frame averaging
    private float[][] mFrameBuffer;
    private int mFrameIndex;
    private int mFrameCount;
    private float[] mAveragedSpectrum;

    // Output listener
    private Listener<List<DetectedSignal>> mSignalListener;

    // Last detection results (for classifier access)
    private volatile float[] mLastAveragedSpectrum;

    // Running state
    private volatile boolean mRunning;

    /**
     * Represents a cluster of adjacent FFT bins above threshold, forming a single signal.
     */
    public static class BinCluster
    {
        public int startBin;
        public int endBin;
        public int peakBin;
        public float peakPower;

        public BinCluster(int startBin, int endBin, int peakBin, float peakPower)
        {
            this.startBin = startBin;
            this.endBin = endBin;
            this.peakBin = peakBin;
            this.peakPower = peakPower;
        }

        public int getWidth()
        {
            return endBin - startBin + 1;
        }

        public int getCenterBin()
        {
            return (startBin + endBin) / 2;
        }
    }

    public SignalDetector(SignalAnalyzerConfig config)
    {
        mConfig = config;
        mRunning = false;
        mDftSize = 4096; // default, updated from DFT results
    }

    /**
     * Set the center frequency of the current tuner.
     */
    public void setCenterFrequency(long centerFrequencyHz)
    {
        mCenterFrequencyHz = centerFrequencyHz;
    }

    public long getCenterFrequency()
    {
        return mCenterFrequencyHz;
    }

    /**
     * Set the bandwidth of the current tuner.
     */
    public void setBandwidth(int bandwidthHz)
    {
        mBandwidthHz = bandwidthHz;
    }

    public int getBandwidth()
    {
        return mBandwidthHz;
    }

    /**
     * Register a listener for detected signals.
     */
    public void setSignalListener(Listener<List<DetectedSignal>> listener)
    {
        mSignalListener = listener;
    }

    /**
     * Start signal detection.
     */
    public void start()
    {
        mRunning = true;
        mFrameBuffer = null;
        mFrameIndex = 0;
        mFrameCount = 0;
        mAveragedSpectrum = null;
    }

    /**
     * Stop signal detection.
     */
    public void stop()
    {
        mRunning = false;
        mFrameBuffer = null;
        mAveragedSpectrum = null;
    }

    public boolean isRunning()
    {
        return mRunning;
    }

    /**
     * DFTResultsListener callback — receives float[] of dB values from the FFT pipeline.
     * This runs on the DFT callback thread, so it must be fast.
     */
    @Override
    public void receive(float[] dftResults)
    {
        if(!mRunning || mCenterFrequencyHz == 0 || mBandwidthHz == 0)
        {
            return;
        }

        int size = dftResults.length;

        // Initialize or reinitialize frame buffer if DFT size changed
        if(mFrameBuffer == null || mDftSize != size)
        {
            mDftSize = size;
            int avgFrames = mConfig.getAveragingFrames();
            mFrameBuffer = new float[avgFrames][size];
            mFrameIndex = 0;
            mFrameCount = 0;
            mAveragedSpectrum = new float[size];
        }

        // Store frame in circular buffer
        System.arraycopy(dftResults, 0, mFrameBuffer[mFrameIndex], 0, size);
        mFrameIndex = (mFrameIndex + 1) % mFrameBuffer.length;
        if(mFrameCount < mFrameBuffer.length)
        {
            mFrameCount++;
        }

        // Only process after we have enough frames for averaging
        if(mFrameCount < mConfig.getAveragingFrames())
        {
            return;
        }

        // Compute averaged spectrum
        computeAveragedSpectrum();

        // Store for classifier access
        mLastAveragedSpectrum = mAveragedSpectrum.clone();

        // Estimate noise floor
        double noiseFloor = estimateNoiseFloor(mAveragedSpectrum);

        // Find peaks above threshold
        double threshold = noiseFloor + mConfig.getThresholdDb();
        List<BinCluster> clusters = findPeaks(mAveragedSpectrum, (float)threshold);

        // Convert clusters to DetectedSignal objects
        List<DetectedSignal> signals = new ArrayList<>();
        for(BinCluster cluster : clusters)
        {
            long freq = binToFrequency(cluster.getCenterBin());
            double bw = cluster.getWidth() * ((double)mBandwidthHz / mDftSize);

            // Skip signals narrower than minimum bandwidth
            if(bw < mConfig.getMinBandwidthHz())
            {
                // Still report very narrow signals (potential carriers) if they're strong enough
                if(cluster.peakPower < threshold + 10)
                {
                    continue;
                }
            }

            DetectedSignal signal = new DetectedSignal(freq, cluster.peakPower, bw);
            signals.add(signal);
        }

        // Notify listener
        if(mSignalListener != null && !signals.isEmpty())
        {
            mSignalListener.receive(signals);
        }
    }

    /**
     * Compute the rolling average of all frames in the buffer.
     */
    private void computeAveragedSpectrum()
    {
        Arrays.fill(mAveragedSpectrum, 0.0f);

        for(int f = 0; f < mFrameCount; f++)
        {
            float[] frame = mFrameBuffer[f];
            for(int b = 0; b < mDftSize; b++)
            {
                mAveragedSpectrum[b] += frame[b];
            }
        }

        float divisor = mFrameCount;
        for(int b = 0; b < mDftSize; b++)
        {
            mAveragedSpectrum[b] /= divisor;
        }
    }

    /**
     * Estimate noise floor using median of all bins.
     * More robust than mean (not skewed by strong signals).
     */
    public static double estimateNoiseFloor(float[] spectrum)
    {
        float[] sorted = new float[spectrum.length];
        System.arraycopy(spectrum, 0, sorted, 0, spectrum.length);
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    /**
     * Find contiguous runs of bins above threshold, clustered into signal groups.
     * Adjacent bins above threshold are merged. A gap of more than gapBins between
     * above-threshold bins starts a new cluster.
     */
    public List<BinCluster> findPeaks(float[] spectrum, float threshold)
    {
        List<BinCluster> clusters = new ArrayList<>();
        int gapBins = 3; // Allow small gaps within a signal

        int startBin = -1;
        int peakBin = -1;
        float peakPower = Float.NEGATIVE_INFINITY;
        int gapCount = 0;

        for(int b = 0; b < spectrum.length; b++)
        {
            if(spectrum[b] >= threshold)
            {
                if(startBin < 0)
                {
                    startBin = b;
                    peakBin = b;
                    peakPower = spectrum[b];
                }
                else
                {
                    if(spectrum[b] > peakPower)
                    {
                        peakPower = spectrum[b];
                        peakBin = b;
                    }
                }
                gapCount = 0;
            }
            else if(startBin >= 0)
            {
                gapCount++;
                if(gapCount > gapBins)
                {
                    // End of cluster
                    int endBin = b - gapCount;
                    clusters.add(new BinCluster(startBin, endBin, peakBin, peakPower));
                    startBin = -1;
                    peakBin = -1;
                    peakPower = Float.NEGATIVE_INFINITY;
                    gapCount = 0;
                }
            }
        }

        // Handle cluster that extends to the end
        if(startBin >= 0)
        {
            int endBin = spectrum.length - 1 - gapCount;
            if(endBin >= startBin)
            {
                clusters.add(new BinCluster(startBin, endBin, peakBin, peakPower));
            }
        }

        return clusters;
    }

    /**
     * Standard channel step sizes in Hz, ordered by priority for snapping.
     * Most LMR systems use 12.5 kHz steps. Some NXDN uses 6.25 kHz.
     */
    private static final long CHANNEL_STEP_HZ = 12_500;

    /**
     * Convert bin index to frequency, snapped to nearest standard channel step.
     * Raw: frequency[bin] = centerFrequency - (bandwidth/2) + bin * (bandwidth / DFTSize)
     * Then snap to nearest 12.5 kHz step to align with standard channel plans.
     */
    public long binToFrequency(int bin)
    {
        long rawFreq = mCenterFrequencyHz - (mBandwidthHz / 2) + (long)(bin * ((double)mBandwidthHz / mDftSize));
        return snapToChannelStep(rawFreq);
    }

    /**
     * Snap a frequency to the nearest standard channel step (12.5 kHz).
     * This converts raw FFT bin frequencies like 856.6243 MHz to the correct 856.6250 MHz.
     */
    public static long snapToChannelStep(long frequencyHz)
    {
        return Math.round((double)frequencyHz / CHANNEL_STEP_HZ) * CHANNEL_STEP_HZ;
    }

    /**
     * Convert frequency to bin index.
     */
    public int frequencyToBin(long frequencyHz)
    {
        double binWidth = (double)mBandwidthHz / mDftSize;
        return (int)((frequencyHz - mCenterFrequencyHz + (mBandwidthHz / 2)) / binWidth);
    }

    /**
     * Get the last computed averaged spectrum (for spectral classification).
     * May return null if no frames have been processed yet.
     */
    public float[] getLastAveragedSpectrum()
    {
        return mLastAveragedSpectrum;
    }

    /**
     * Get the current DFT size.
     */
    public int getDftSize()
    {
        return mDftSize;
    }
}
