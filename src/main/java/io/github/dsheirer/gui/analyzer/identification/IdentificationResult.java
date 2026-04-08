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
package io.github.dsheirer.gui.analyzer.identification;

import io.github.dsheirer.module.decode.DecoderType;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of a decoder trial on a detected signal.
 * Contains the decoder type tried, how many decode events were observed,
 * and a confidence assessment.
 */
public class IdentificationResult
{
    /**
     * Confidence levels for identification results.
     */
    public enum Confidence
    {
        HIGH("High", "Multiple valid messages decoded"),
        MEDIUM("Medium", "Some valid messages decoded"),
        LOW("Low", "Sync detected but few/no valid messages"),
        NONE("None", "No sync or messages detected");

        private final String mLabel;
        private final String mDescription;

        Confidence(String label, String description)
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

    private final DecoderType mDecoderType;
    private final Confidence mConfidence;
    private final int mDecodeEventCount;
    private final long mTrialDurationMs;
    private final List<String> mSampleMessages;
    private final String mNotes;
    private final boolean mSuccess;
    private final boolean mTentative;
    private final String mNac;
    private final String mSystemInfo;
    private final int mSyncLossCount;
    private final int mCrcFailCount;

    public IdentificationResult(DecoderType decoderType, Confidence confidence,
                                 int decodeEventCount, long trialDurationMs,
                                 List<String> sampleMessages, String notes,
                                 boolean success, boolean tentative, String nac,
                                 String systemInfo)
    {
        this(decoderType, confidence, decodeEventCount, trialDurationMs,
             sampleMessages, notes, success, tentative, nac, systemInfo, 0, 0);
    }

    public IdentificationResult(DecoderType decoderType, Confidence confidence,
                                 int decodeEventCount, long trialDurationMs,
                                 List<String> sampleMessages, String notes,
                                 boolean success, boolean tentative, String nac,
                                 String systemInfo, int syncLossCount, int crcFailCount)
    {
        mDecoderType = decoderType;
        mConfidence = confidence;
        mDecodeEventCount = decodeEventCount;
        mTrialDurationMs = trialDurationMs;
        mSampleMessages = sampleMessages != null ? sampleMessages : new ArrayList<>();
        mNotes = notes;
        mSuccess = success;
        mTentative = tentative;
        mNac = nac;
        mSystemInfo = systemInfo;
        mSyncLossCount = syncLossCount;
        mCrcFailCount = crcFailCount;
    }

    /**
     * Create a successful identification result.
     */
    public static IdentificationResult success(DecoderType decoderType, Confidence confidence,
                                                int decodeEventCount, long trialDurationMs,
                                                List<String> sampleMessages, String nac,
                                                String systemInfo)
    {
        return new IdentificationResult(decoderType, confidence, decodeEventCount,
            trialDurationMs, sampleMessages, null, true, false, nac, systemInfo);
    }

    /**
     * Create a no-match result for a decoder that was tried but didn't decode anything.
     */
    public static IdentificationResult noMatch(DecoderType decoderType, long trialDurationMs)
    {
        return new IdentificationResult(decoderType, Confidence.NONE, 0,
            trialDurationMs, null, "No decode events observed", false, false, null, null);
    }

    /**
     * Create a no-match result with diagnostic data (sync losses, CRC failures observed).
     * Even though no valid messages were decoded, the diagnostic data tells us about
     * the signal's digital characteristics and which decoders had partial matches.
     */
    public static IdentificationResult noMatchWithDiagnostics(DecoderType decoderType, long trialDurationMs,
                                                               int syncLossCount, int crcFailCount)
    {
        String notes = syncLossCount > 0 || crcFailCount > 0
            ? String.format("%d syncLoss, %d crcFail", syncLossCount, crcFailCount)
            : "No decode events observed";
        return new IdentificationResult(decoderType, Confidence.NONE, 0,
            trialDurationMs, null, notes, false, false, null, null, syncLossCount, crcFailCount);
    }

    /**
     * Create a tentative (unconfirmed) match — CRC-failed frames were detected but no CRC-valid messages.
     * This typically indicates encrypted traffic, weak signal, or intermittent channel.
     * NOTE: Requires actual CRC-failed frames, not just sync losses.
     */
    public static IdentificationResult tentative(DecoderType decoderType, long trialDurationMs,
                                                   int crcFails, int syncLoss,
                                                   List<String> sampleMessages)
    {
        String notes = String.format("Tentative — %d CRC-failed frames, %d sync losses (likely encrypted/intermittent)",
            crcFails, syncLoss);
        return new IdentificationResult(decoderType, Confidence.LOW, 0,
            trialDurationMs, sampleMessages, notes, true, true, null, null);
    }

    /**
     * Create a failure result when the trial couldn't be started (e.g., no tuner available).
     */
    public static IdentificationResult error(DecoderType decoderType, String errorMessage)
    {
        return new IdentificationResult(decoderType, Confidence.NONE, 0,
            0, null, errorMessage, false, false, null, null);
    }

    public DecoderType getDecoderType()
    {
        return mDecoderType;
    }

    public Confidence getConfidence()
    {
        return mConfidence;
    }

    public int getDecodeEventCount()
    {
        return mDecodeEventCount;
    }

    public long getTrialDurationMs()
    {
        return mTrialDurationMs;
    }

    public List<String> getSampleMessages()
    {
        return mSampleMessages;
    }

    public String getNotes()
    {
        return mNotes;
    }

    public boolean isSuccess()
    {
        return mSuccess;
    }

    /**
     * Returns true if this is a tentative match (CRC-failed frames found but no valid messages).
     */
    public boolean isTentative()
    {
        return mTentative;
    }

    /**
     * Get the detected NAC (Network Access Code) for P25 signals.
     * Returns null if no NAC was detected.
     */
    public String getNac()
    {
        return mNac;
    }

    /**
     * Get protocol-specific system identification info.
     * Examples: "NAC:954/x3BA" for P25, "CC:1" for DMR, "DCC:2 SITE:088" for Passport.
     * Returns null if no system info was detected.
     */
    public String getSystemInfo()
    {
        return mSystemInfo;
    }

    public int getSyncLossCount()
    {
        return mSyncLossCount;
    }

    public int getCrcFailCount()
    {
        return mCrcFailCount;
    }

    /**
     * Get a compact diagnostic summary for this trial result.
     * e.g., "P25-1: 0 valid, 2 crcFail, 5 syncLoss"
     */
    public String getDiagnosticSummary()
    {
        return String.format("%s: %d events, %d crcFail, %d syncLoss",
            mDecoderType.getShortDisplayString(),
            mDecodeEventCount, mCrcFailCount, mSyncLossCount);
    }

    @Override
    public String toString()
    {
        return String.format("IdentificationResult[decoder=%s, confidence=%s, events=%d, duration=%dms, success=%s]",
            mDecoderType, mConfidence, mDecodeEventCount, mTrialDurationMs, mSuccess);
    }
}
