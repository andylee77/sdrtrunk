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
 * Configuration settings for the signal analyzer.
 */
public class SignalAnalyzerConfig
{
    // Detection
    private double mThresholdDb = 10.0;
    private double mMinBandwidthHz = 6250;
    private int mAveragingFrames = 10;
    private int mPersistenceThreshold = 3;

    // Characterization (Phase 2 - future)
    private int mCharacterizationTimeMs = 2000;
    private double mEnvelopeThreshold = 0.1;

    // Identification (Phase 3 - future)
    private int mDwellTimeMs = 5000;
    private boolean mAutoIdentify = true;
    private int mMaxConcurrentTrials = 1;

    // Validation
    private double mDcSpikeMarginHz = 1000;
    private double mImageMarginHz = 500;
    private double mHarmonicTolerance = 0.005;

    // UI
    private boolean mShowFlagged = true;
    private boolean mAutoScroll = true;

    public SignalAnalyzerConfig()
    {
    }

    // Detection getters/setters

    public double getThresholdDb()
    {
        return mThresholdDb;
    }

    public void setThresholdDb(double thresholdDb)
    {
        mThresholdDb = thresholdDb;
    }

    public double getMinBandwidthHz()
    {
        return mMinBandwidthHz;
    }

    public void setMinBandwidthHz(double minBandwidthHz)
    {
        mMinBandwidthHz = minBandwidthHz;
    }

    public int getAveragingFrames()
    {
        return mAveragingFrames;
    }

    public void setAveragingFrames(int averagingFrames)
    {
        mAveragingFrames = averagingFrames;
    }

    public int getPersistenceThreshold()
    {
        return mPersistenceThreshold;
    }

    public void setPersistenceThreshold(int persistenceThreshold)
    {
        mPersistenceThreshold = persistenceThreshold;
    }

    // Characterization getters/setters

    public int getCharacterizationTimeMs()
    {
        return mCharacterizationTimeMs;
    }

    public void setCharacterizationTimeMs(int characterizationTimeMs)
    {
        mCharacterizationTimeMs = characterizationTimeMs;
    }

    public double getEnvelopeThreshold()
    {
        return mEnvelopeThreshold;
    }

    public void setEnvelopeThreshold(double envelopeThreshold)
    {
        mEnvelopeThreshold = envelopeThreshold;
    }

    // Identification getters/setters

    public int getDwellTimeMs()
    {
        return mDwellTimeMs;
    }

    public void setDwellTimeMs(int dwellTimeMs)
    {
        mDwellTimeMs = dwellTimeMs;
    }

    public boolean isAutoIdentify()
    {
        return mAutoIdentify;
    }

    public void setAutoIdentify(boolean autoIdentify)
    {
        mAutoIdentify = autoIdentify;
    }

    public int getMaxConcurrentTrials()
    {
        return mMaxConcurrentTrials;
    }

    public void setMaxConcurrentTrials(int maxConcurrentTrials)
    {
        mMaxConcurrentTrials = maxConcurrentTrials;
    }

    // Validation getters/setters

    public double getDcSpikeMarginHz()
    {
        return mDcSpikeMarginHz;
    }

    public void setDcSpikeMarginHz(double dcSpikeMarginHz)
    {
        mDcSpikeMarginHz = dcSpikeMarginHz;
    }

    public double getImageMarginHz()
    {
        return mImageMarginHz;
    }

    public void setImageMarginHz(double imageMarginHz)
    {
        mImageMarginHz = imageMarginHz;
    }

    public double getHarmonicTolerance()
    {
        return mHarmonicTolerance;
    }

    public void setHarmonicTolerance(double harmonicTolerance)
    {
        mHarmonicTolerance = harmonicTolerance;
    }

    // UI getters/setters

    public boolean isShowFlagged()
    {
        return mShowFlagged;
    }

    public void setShowFlagged(boolean showFlagged)
    {
        mShowFlagged = showFlagged;
    }

    public boolean isAutoScroll()
    {
        return mAutoScroll;
    }

    public void setAutoScroll(boolean autoScroll)
    {
        mAutoScroll = autoScroll;
    }
}
