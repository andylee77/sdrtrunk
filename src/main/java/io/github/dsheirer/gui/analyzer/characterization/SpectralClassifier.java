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
package io.github.dsheirer.gui.analyzer.characterization;

import io.github.dsheirer.gui.analyzer.AnalysisStatus;
import io.github.dsheirer.gui.analyzer.ModulationType;
import io.github.dsheirer.gui.analyzer.detection.DetectedSignal;
import io.github.dsheirer.gui.analyzer.detection.SignalFlag;
import io.github.dsheirer.module.decode.DecoderType;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies detected signals based on spectral shape analysis of the FFT data.
 * This is a "Phase 2-lite" approach that works entirely from the existing FFT pipeline
 * without needing dedicated I/Q channel allocation.
 *
 * Classification approach:
 * 1. Spectral flatness — flat-topped signals are digital, peaked signals are analog FM
 * 2. Bandwidth measurement — standard channel widths map to known protocols
 * 3. Frequency band heuristics — 700-900 MHz public safety bands = likely P25/trunked
 * 4. Signal persistence — continuous signals are control channels, intermittent are traffic
 *
 * The classifier updates each DetectedSignal's modulationType, symbolRate, and
 * identifiedDecoder fields based on the analysis.
 */
public class SpectralClassifier
{
    private static final Logger mLog = LoggerFactory.getLogger(SpectralClassifier.class);

    // Spectral shape thresholds
    private static final double FLATNESS_DIGITAL_THRESHOLD = 0.65;   // ratio > this = flat-topped (digital)
    private static final double FLATNESS_CARRIER_THRESHOLD = 0.25;   // ratio < this = narrow carrier/CW

    // Bandwidth classification boundaries (Hz)
    private static final double BW_CARRIER_MAX = 2000;        // < 2 kHz = carrier/CW
    private static final double BW_NARROW_MAX = 8000;         // < 8 kHz = narrowband (NXDN 6.25 kHz)
    private static final double BW_STANDARD_MIN = 8000;       // 8-16 kHz = standard narrowband
    private static final double BW_STANDARD_MAX = 20000;      // Strong 12.5 kHz signals can spread to ~18-20 kHz
    private static final double BW_WIDE_MAX = 50000;          // 20-50 kHz = 25 kHz channels or wideband LMR

    // Frequency band boundaries (Hz)
    private static final long BAND_VHF_MIN = 136_000_000L;
    private static final long BAND_VHF_MAX = 174_000_000L;
    private static final long BAND_UHF_MIN = 400_000_000L;
    private static final long BAND_UHF_MAX = 520_000_000L;
    private static final long BAND_700_MIN = 764_000_000L;
    private static final long BAND_700_MAX = 776_000_000L;
    private static final long BAND_800_MIN = 806_000_000L;
    private static final long BAND_800_MAX = 870_000_000L;
    private static final long BAND_900_MIN = 896_000_000L;
    private static final long BAND_900_MAX = 960_000_000L;

    // P25 control channel persistence threshold (frames before considered continuous)
    private static final int CONTINUOUS_DETECTION_THRESHOLD = 10;

    /**
     * Classify all detected signals using the current FFT spectrum data.
     *
     * @param signals the list of detected signals to classify
     * @param spectrum the averaged FFT spectrum (dB values)
     * @param centerFrequencyHz tuner center frequency
     * @param bandwidthHz tuner bandwidth
     * @param dftSize FFT size (number of bins)
     */
    public void classify(List<DetectedSignal> signals, float[] spectrum,
                          long centerFrequencyHz, int bandwidthHz, int dftSize)
    {
        if(spectrum == null || signals == null || signals.isEmpty())
        {
            return;
        }

        double binWidthHz = (double)bandwidthHz / dftSize;

        for(DetectedSignal signal : signals)
        {
            // Skip signals that have already progressed past characterization
            // (don't re-classify them back to CHARACTERIZING)
            if(signal.getStatus() == AnalysisStatus.IDENTIFIED ||
               signal.getStatus() == AnalysisStatus.IDENTIFYING ||
               signal.getStatus() == AnalysisStatus.NO_MATCH)
            {
                continue;
            }

            // Skip artifacts
            if(signal.isArtifact())
            {
                continue;
            }

            classifySignal(signal, spectrum, centerFrequencyHz, bandwidthHz, dftSize, binWidthHz);
        }
    }

    /**
     * Classify a single signal based on spectral shape, bandwidth, and frequency band.
     */
    private void classifySignal(DetectedSignal signal, float[] spectrum,
                                 long centerFrequencyHz, int bandwidthHz, int dftSize,
                                 double binWidthHz)
    {
        long freqHz = signal.getFrequencyHz();
        double bwHz = signal.getBandwidthHz();

        // 1. Compute spectral flatness around the signal
        double flatness = computeSpectralFlatness(signal, spectrum, centerFrequencyHz,
            bandwidthHz, dftSize, binWidthHz);

        // 2. Classify by spectral shape + bandwidth
        ModulationType modType;
        int symbolRate = 0;
        DecoderType decoderType = null;

        if(bwHz < BW_CARRIER_MAX)
        {
            // Very narrow — carrier wave or CW
            modType = ModulationType.CARRIER_ONLY;
        }
        else if(bwHz < BW_NARROW_MAX)
        {
            // Narrowband (< 8 kHz) — could be DMR, NXDN, or narrowband analog
            // DMR signals often appear 3-7 kHz wide in the FFT even though they
            // occupy a 12.5 kHz channel, because the 4FSK energy is concentrated
            if(flatness >= FLATNESS_DIGITAL_THRESHOLD)
            {
                modType = ModulationType.FSK4;
                symbolRate = 4800;
                if(isInUhfBand(freqHz))
                {
                    // UHF 4FSK at 4800 baud = DMR
                    decoderType = DecoderType.DMR;
                }
            }
            else
            {
                modType = ModulationType.FM_NARROW;
            }
        }
        else if(bwHz >= BW_STANDARD_MIN && bwHz <= BW_STANDARD_MAX)
        {
            // Standard 12.5 kHz channel — this is where most interesting signals live
            if(flatness >= FLATNESS_DIGITAL_THRESHOLD)
            {
                // Flat-topped = digital signal
                classifyDigitalStandard(signal, freqHz, flatness);
                return; // classifyDigitalStandard sets all fields
            }
            else if(flatness <= FLATNESS_CARRIER_THRESHOLD)
            {
                // Narrow peak within wider bandwidth — analog FM with carrier
                modType = ModulationType.FM_NARROW;
            }
            else
            {
                // Bell-shaped — analog FM
                modType = ModulationType.FM_NARROW;
                // Check if it could be a digital signal with lower confidence
                if(flatness > 0.45 && isPublicSafetyBand(freqHz))
                {
                    // Borderline — might be digital, mark as unknown digital
                    modType = ModulationType.UNKNOWN_DIGITAL;
                    symbolRate = 9600;
                }
            }
        }
        else if(bwHz <= BW_WIDE_MAX)
        {
            // Wideband (16-30 kHz)
            if(flatness >= FLATNESS_DIGITAL_THRESHOLD)
            {
                modType = ModulationType.UNKNOWN_DIGITAL;
            }
            else
            {
                modType = ModulationType.FM_WIDE;
            }
            signal.addFlag(SignalFlag.WIDEBAND);
        }
        else
        {
            // Very wide — broadcast or wideband data
            modType = ModulationType.FM_WIDE;
            signal.addFlag(SignalFlag.WIDEBAND);
        }

        // 3. Apply band-based protocol heuristics for analog FM
        if(modType == ModulationType.FM_NARROW && isPublicSafetyBand(freqHz))
        {
            decoderType = DecoderType.NBFM;
        }

        // 4. Update signal (always set all fields to avoid stale values from previous frames)
        signal.setModulationType(modType);
        signal.setSymbolRate(symbolRate);
        signal.setIdentifiedDecoder(decoderType);

        // Update status
        if(signal.getStatus() == AnalysisStatus.DETECTED)
        {
            signal.setStatus(AnalysisStatus.CHARACTERIZING);
        }

        // Update persistence flag
        if(signal.getDetectionCount() >= CONTINUOUS_DETECTION_THRESHOLD)
        {
            signal.setPersistent(true);
            signal.addFlag(SignalFlag.CONTINUOUS);
        }
    }

    /**
     * Classify a standard-bandwidth (12.5 kHz) digital signal based on frequency band.
     * In the 700-900 MHz public safety bands, flat-topped 12.5 kHz signals are almost
     * certainly P25 Phase 1 (C4FM, 9600 baud). In the UHF band, they could be P25 or DMR.
     */
    private void classifyDigitalStandard(DetectedSignal signal, long freqHz, double flatness)
    {
        ModulationType modType;
        int symbolRate;
        DecoderType decoderType;

        if(isIn700Or800Band(freqHz))
        {
            // 700/800 MHz public safety — overwhelmingly P25 Phase 1 in the US
            modType = ModulationType.C4FM;
            symbolRate = 9600;
            decoderType = DecoderType.P25_PHASE1;

            // Continuous signals in these bands are P25 control channels
            if(signal.getDetectionCount() >= CONTINUOUS_DETECTION_THRESHOLD)
            {
                signal.setPersistent(true);
                signal.addFlag(SignalFlag.CONTINUOUS);
            }
        }
        else if(isIn900Band(freqHz))
        {
            // 900 MHz — also commonly P25 in US public safety
            modType = ModulationType.C4FM;
            symbolRate = 9600;
            decoderType = DecoderType.P25_PHASE1;
        }
        else if(isInUhfBand(freqHz))
        {
            // UHF (400-520 MHz) — DMR is the dominant digital protocol
            // DMR uses 4FSK at 4800 baud in 12.5 kHz channels
            modType = ModulationType.FSK4;
            symbolRate = 4800;
            decoderType = DecoderType.DMR;
        }
        else if(isInVhfBand(freqHz))
        {
            // VHF — P25 common, DMR also possible
            modType = ModulationType.FSK4;
            symbolRate = 9600;
            decoderType = null;
        }
        else
        {
            // Unknown band
            modType = ModulationType.UNKNOWN_DIGITAL;
            symbolRate = 9600;
            decoderType = null;
        }

        signal.setModulationType(modType);
        signal.setSymbolRate(symbolRate);
        if(decoderType != null)
        {
            signal.setIdentifiedDecoder(decoderType);
        }
        // Spectral classification is heuristic only — status stays at CHARACTERIZING
        // until actual decoder trials confirm the identification (Phase 3)
        signal.setStatus(AnalysisStatus.CHARACTERIZING);
    }

    /**
     * Compute spectral flatness for a signal: ratio of mean power to peak power
     * within the signal's bandwidth. Values closer to 1.0 mean flat-topped (digital),
     * values closer to 0.0 mean peaked (analog/carrier).
     *
     * Examines the FFT bins spanning the signal's detected bandwidth, plus a small
     * margin on each side.
     */
    private double computeSpectralFlatness(DetectedSignal signal, float[] spectrum,
                                            long centerFrequencyHz, int bandwidthHz,
                                            int dftSize, double binWidthHz)
    {
        long signalFreq = signal.getFrequencyHz();
        double signalBw = signal.getBandwidthHz();

        // Convert signal frequency range to bin indices
        long lowFreq = (long)(signalFreq - signalBw / 2.0);
        long highFreq = (long)(signalFreq + signalBw / 2.0);

        int lowBin = frequencyToBin(lowFreq, centerFrequencyHz, bandwidthHz, dftSize);
        int highBin = frequencyToBin(highFreq, centerFrequencyHz, bandwidthHz, dftSize);

        // Clamp to valid range
        lowBin = Math.max(0, lowBin);
        highBin = Math.min(dftSize - 1, highBin);

        if(highBin <= lowBin || highBin - lowBin < 2)
        {
            // Too few bins to analyze — not enough resolution
            return 0.5; // neutral/uncertain
        }

        // Find peak and compute mean across the signal's bins
        float peak = Float.NEGATIVE_INFINITY;
        double sum = 0;
        int count = 0;

        for(int b = lowBin; b <= highBin; b++)
        {
            float val = spectrum[b];
            sum += val;
            count++;
            if(val > peak)
            {
                peak = val;
            }
        }

        if(count == 0 || peak == Float.NEGATIVE_INFINITY)
        {
            return 0.5;
        }

        double mean = sum / count;

        // Flatness = how close the mean is to the peak (in linear scale relative to noise)
        // We work in dB, so convert: if peak is -40 dB and mean is -42 dB, that's quite flat
        // Normalize: flatness = 1.0 - (peak - mean) / referenceRange
        double peakMinusMean = peak - mean;

        // A reference range of ~10 dB difference between peak and mean
        double flatness = 1.0 - (peakMinusMean / 10.0);
        flatness = Math.max(0.0, Math.min(1.0, flatness));

        return flatness;
    }

    /**
     * Convert frequency to FFT bin index.
     */
    private int frequencyToBin(long frequencyHz, long centerFrequencyHz, int bandwidthHz, int dftSize)
    {
        double binWidth = (double)bandwidthHz / dftSize;
        return (int)((frequencyHz - centerFrequencyHz + (bandwidthHz / 2)) / binWidth);
    }

    // ---- Frequency band helpers ----

    private boolean isPublicSafetyBand(long freqHz)
    {
        return isInVhfBand(freqHz) || isInUhfBand(freqHz) ||
               isIn700Or800Band(freqHz) || isIn900Band(freqHz);
    }

    private boolean isInVhfBand(long freqHz)
    {
        return freqHz >= BAND_VHF_MIN && freqHz <= BAND_VHF_MAX;
    }

    private boolean isInUhfBand(long freqHz)
    {
        return freqHz >= BAND_UHF_MIN && freqHz <= BAND_UHF_MAX;
    }

    private boolean isIn700Or800Band(long freqHz)
    {
        return (freqHz >= BAND_700_MIN && freqHz <= BAND_700_MAX) ||
               (freqHz >= BAND_800_MIN && freqHz <= BAND_800_MAX);
    }

    private boolean isIn900Band(long freqHz)
    {
        return freqHz >= BAND_900_MIN && freqHz <= BAND_900_MAX;
    }
}
