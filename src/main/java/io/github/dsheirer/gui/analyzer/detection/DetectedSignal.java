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
import io.github.dsheirer.gui.analyzer.ModulationType;
import io.github.dsheirer.module.decode.DecoderType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Data model representing a single detected signal from FFT analysis.
 * Tracks the signal through the full analysis pipeline: detection → validation →
 * characterization → identification.
 */
public class DetectedSignal
{
    private static final AtomicLong ID_COUNTER = new AtomicLong(0);

    // Identity
    private final long mId;
    private long mFrequencyHz;
    private double mPowerDb;
    private double mBandwidthHz;

    // Detection metadata
    private Instant mFirstSeen;
    private Instant mLastSeen;
    private int mDetectionCount;
    private boolean mPersistent;

    // Validation flags
    private final EnumSet<SignalFlag> mFlags = EnumSet.noneOf(SignalFlag.class);

    // Characterization results (filled by Phase 2 - future)
    private ModulationType mModulationType = ModulationType.UNKNOWN;
    private int mSymbolRate;
    private double mBandwidth3dB;
    private double mBandwidth6dB;
    private double mFmDeviation;

    // Identification results (filled by Phase 3)
    private DecoderType mIdentifiedDecoder;
    private String mIdentificationConfidence;
    private String mNac;
    private String mSystemInfo;
    private String mSuggestedProtocol;  // For protocols without decoders (EDACS, NXDN, etc.)
    private int mSyncHits;
    private int mValidMessages;
    private List<String> mSampleMessages = new ArrayList<>();
    private List<String> mTrialDiagnostics = new ArrayList<>();  // Accumulated results from all decoder trials

    // Status
    private AnalysisStatus mStatus = AnalysisStatus.DETECTED;

    public DetectedSignal(long frequencyHz, double powerDb, double bandwidthHz)
    {
        mId = ID_COUNTER.incrementAndGet();
        mFrequencyHz = frequencyHz;
        mPowerDb = powerDb;
        mBandwidthHz = bandwidthHz;
        mFirstSeen = Instant.now();
        mLastSeen = mFirstSeen;
        mDetectionCount = 1;
        mPersistent = false;
    }

    public long getId()
    {
        return mId;
    }

    public long getFrequencyHz()
    {
        return mFrequencyHz;
    }

    public void setFrequencyHz(long frequencyHz)
    {
        mFrequencyHz = frequencyHz;
    }

    /**
     * Get the frequency in MHz for display purposes.
     */
    public double getFrequencyMHz()
    {
        return mFrequencyHz / 1_000_000.0;
    }

    public double getPowerDb()
    {
        return mPowerDb;
    }

    public void setPowerDb(double powerDb)
    {
        mPowerDb = powerDb;
    }

    public double getBandwidthHz()
    {
        return mBandwidthHz;
    }

    /**
     * Get the bandwidth in kHz for display purposes.
     */
    public double getBandwidthKHz()
    {
        return mBandwidthHz / 1000.0;
    }

    public void setBandwidthHz(double bandwidthHz)
    {
        mBandwidthHz = bandwidthHz;
    }

    public Instant getFirstSeen()
    {
        return mFirstSeen;
    }

    public Instant getLastSeen()
    {
        return mLastSeen;
    }

    public void setLastSeen(Instant lastSeen)
    {
        mLastSeen = lastSeen;
    }

    public int getDetectionCount()
    {
        return mDetectionCount;
    }

    public void incrementDetectionCount()
    {
        mDetectionCount++;
        mLastSeen = Instant.now();
    }

    public boolean isPersistent()
    {
        return mPersistent;
    }

    public void setPersistent(boolean persistent)
    {
        mPersistent = persistent;
    }

    public EnumSet<SignalFlag> getFlags()
    {
        return mFlags;
    }

    public void addFlag(SignalFlag flag)
    {
        mFlags.add(flag);
    }

    public void removeFlag(SignalFlag flag)
    {
        mFlags.remove(flag);
    }

    public boolean hasFlag(SignalFlag flag)
    {
        return mFlags.contains(flag);
    }

    /**
     * Get a comma-separated display string of all flags.
     */
    public String getFlagsDisplayString()
    {
        if(mFlags.isEmpty())
        {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for(SignalFlag flag : mFlags)
        {
            if(sb.length() > 0)
            {
                sb.append(", ");
            }
            // Prefix warning flags with warning symbol
            if(flag == SignalFlag.DC_SPIKE || flag == SignalFlag.IMAGE ||
               flag == SignalFlag.HARMONIC || flag == SignalFlag.SPUR)
            {
                sb.append("\u26A0");
            }
            sb.append(flag.getLabel());
        }
        return sb.toString();
    }

    /**
     * Returns true if this signal has been flagged as an artifact (DC spike, image, harmonic, or spur).
     */
    public boolean isArtifact()
    {
        return mFlags.contains(SignalFlag.DC_SPIKE) || mFlags.contains(SignalFlag.IMAGE) ||
               mFlags.contains(SignalFlag.HARMONIC) || mFlags.contains(SignalFlag.SPUR);
    }

    public ModulationType getModulationType()
    {
        return mModulationType;
    }

    public void setModulationType(ModulationType modulationType)
    {
        mModulationType = modulationType;
    }

    public int getSymbolRate()
    {
        return mSymbolRate;
    }

    public void setSymbolRate(int symbolRate)
    {
        mSymbolRate = symbolRate;
    }

    public double getBandwidth3dB()
    {
        return mBandwidth3dB;
    }

    public void setBandwidth3dB(double bandwidth3dB)
    {
        mBandwidth3dB = bandwidth3dB;
    }

    public double getBandwidth6dB()
    {
        return mBandwidth6dB;
    }

    public void setBandwidth6dB(double bandwidth6dB)
    {
        mBandwidth6dB = bandwidth6dB;
    }

    public double getFmDeviation()
    {
        return mFmDeviation;
    }

    public void setFmDeviation(double fmDeviation)
    {
        mFmDeviation = fmDeviation;
    }

    public DecoderType getIdentifiedDecoder()
    {
        return mIdentifiedDecoder;
    }

    public void setIdentifiedDecoder(DecoderType identifiedDecoder)
    {
        mIdentifiedDecoder = identifiedDecoder;
    }

    public int getSyncHits()
    {
        return mSyncHits;
    }

    public void setSyncHits(int syncHits)
    {
        mSyncHits = syncHits;
    }

    public int getValidMessages()
    {
        return mValidMessages;
    }

    public void setValidMessages(int validMessages)
    {
        mValidMessages = validMessages;
    }

    public List<String> getSampleMessages()
    {
        return mSampleMessages;
    }

    public void setSampleMessages(List<String> sampleMessages)
    {
        mSampleMessages = sampleMessages;
    }

    public AnalysisStatus getStatus()
    {
        return mStatus;
    }

    public void setStatus(AnalysisStatus status)
    {
        mStatus = status;
    }

    public String getIdentificationConfidence()
    {
        return mIdentificationConfidence;
    }

    public void setIdentificationConfidence(String confidence)
    {
        mIdentificationConfidence = confidence;
    }

    /**
     * Get the detected NAC (Network Access Code) for P25 signals.
     */
    public String getNac()
    {
        return mNac;
    }

    public void setNac(String nac)
    {
        mNac = nac;
    }

    /**
     * Get system identification info (e.g. NAC for P25, Color Code for DMR, etc.)
     */
    public String getSystemInfo()
    {
        return mSystemInfo;
    }

    public void setSystemInfo(String systemInfo)
    {
        mSystemInfo = systemInfo;
    }

    /**
     * Get the suggested protocol for signals without a decoder (e.g. EDACS, NXDN).
     * Set by heuristic analysis when all decoder trials fail.
     */
    public String getSuggestedProtocol()
    {
        return mSuggestedProtocol;
    }

    public void setSuggestedProtocol(String suggestedProtocol)
    {
        mSuggestedProtocol = suggestedProtocol;
    }

    /**
     * Get accumulated trial diagnostics from all decoder trials.
     * Each entry is a summary line like "P25-1: 0 valid, 0 crcFail, 5 syncLoss"
     */
    public List<String> getTrialDiagnostics()
    {
        return mTrialDiagnostics;
    }

    public void addTrialDiagnostic(String diagnostic)
    {
        mTrialDiagnostics.add(diagnostic);
    }

    /**
     * Get a compact summary of trial diagnostics for table display.
     * Shows which decoders had the most activity.
     */
    public String getTrialDiagnosticsSummary()
    {
        if(mTrialDiagnostics.isEmpty())
        {
            return "";
        }
        // Show count of trials and any notable findings
        return String.format("%d decoders tried", mTrialDiagnostics.size());
    }

    /**
     * Get a summary string of the identification for display.
     * Shows NAC for P25, suggested protocol, or other relevant system info.
     */
    public String getSystemInfoDisplay()
    {
        if(mNac != null && !mNac.isEmpty())
        {
            return "NAC:" + mNac;
        }
        if(mSuggestedProtocol != null && !mSuggestedProtocol.isEmpty())
        {
            return mSuggestedProtocol;
        }
        if(mSystemInfo != null && !mSystemInfo.isEmpty())
        {
            return mSystemInfo;
        }
        return "";
    }

    /**
     * Checks if a given frequency falls within this signal's bandwidth.
     * Used for matching new detections to existing tracked signals.
     *
     * @param frequencyHz the frequency to test
     * @param toleranceHz additional tolerance beyond the signal bandwidth
     * @return true if the frequency is within range
     */
    public boolean containsFrequency(long frequencyHz, double toleranceHz)
    {
        double halfBw = (mBandwidthHz / 2.0) + toleranceHz;
        return Math.abs(frequencyHz - mFrequencyHz) <= halfBw;
    }

    @Override
    public String toString()
    {
        return String.format("Signal[id=%d, freq=%.4f MHz, power=%.1f dB, bw=%.1f kHz, status=%s]",
            mId, getFrequencyMHz(), mPowerDb, getBandwidthKHz(), mStatus);
    }
}
