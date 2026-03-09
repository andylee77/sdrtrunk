/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.buffer;

import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import java.util.Iterator;

/**
 * Native buffer wrapper for interleaved signed 16-bit (short) IQ samples, as produced by the
 * PlutoSDR / AD9361 and similar SDR hardware.
 *
 * <p>Samples are stored as interleaved I, Q pairs where each value is a signed 16-bit integer
 * in the range [-32768, 32767].  They are normalised to the range [-1.0, 1.0] on conversion.</p>
 *
 * <p>DC offset correction values (iAverageDc, qAverageDc) are subtracted from each sample
 * during conversion to remove the DC spike at the centre frequency.  These values are
 * calculated externally (e.g. in {@link PlutoSdrTunerController}) and passed to the constructor.</p>
 *
 * <p>The {@code samples} array passed to the constructor must have an even length that is also
 * an even multiple of {@code FRAGMENT_SIZE * 2} (i.e. each fragment contains
 * {@code FRAGMENT_SIZE} I values and {@code FRAGMENT_SIZE} Q values stored interleaved).</p>
 */
public class SignedShortNativeBuffer extends AbstractNativeBuffer
{
    /** Number of complex samples (I+Q pairs) per fragment delivered to downstream consumers. */
    private static final int FRAGMENT_SIZE = 2048;

    /** Normalisation divisor: AD9361 outputs 12-bit values sign-extended to 16 bits.
     *  The actual ADC range is ±2048 (12-bit signed), so we normalise against 2048 to
     *  produce values in the range [-1.0, 1.0].  Using the full 16-bit range (32768) would
     *  shift the entire spectrum down by 24 dB, pushing the noise floor far too high on the
     *  spectral display. */
    private static final float SCALE = 2048.0f;

    /** Interleaved I, Q short samples. Length = 2 × (number of complex samples). */
    private final short[] mSamples;

    /** DC offset correction for the I (in-phase) channel, in normalised [-1,1] units. */
    private final float mIAverageDc;

    /** DC offset correction for the Q (quadrature) channel, in normalised [-1,1] units. */
    private final float mQAverageDc;

    /**
     * Constructs an instance with DC offset correction.
     *
     * @param samples              interleaved signed 16-bit IQ samples (I0, Q0, I1, Q1, …)
     * @param timestamp            millisecond timestamp for the first sample in this buffer
     * @param samplesPerMillisecond used to calculate sub-buffer timestamps
     * @param iAverageDc           DC offset to subtract from I samples (normalised units)
     * @param qAverageDc           DC offset to subtract from Q samples (normalised units)
     */
    public SignedShortNativeBuffer(short[] samples, long timestamp, float samplesPerMillisecond,
                                   float iAverageDc, float qAverageDc)
    {
        super(timestamp, samplesPerMillisecond);

        if(samples.length % (FRAGMENT_SIZE * 2) != 0)
        {
            throw new IllegalArgumentException("Samples short[] length [" + samples.length +
                    "] must be an even multiple of " + (FRAGMENT_SIZE * 2));
        }

        mSamples = samples;
        mIAverageDc = iAverageDc;
        mQAverageDc = qAverageDc;
    }

    /**
     * Constructs an instance without DC offset correction (DC offsets default to 0).
     *
     * @param samples              interleaved signed 16-bit IQ samples (I0, Q0, I1, Q1, …)
     * @param timestamp            millisecond timestamp for the first sample in this buffer
     * @param samplesPerMillisecond used to calculate sub-buffer timestamps
     */
    public SignedShortNativeBuffer(short[] samples, long timestamp, float samplesPerMillisecond)
    {
        this(samples, timestamp, samplesPerMillisecond, 0.0f, 0.0f);
    }

    @Override
    public int sampleCount()
    {
        // Each complex sample is two shorts (I + Q)
        return mSamples.length / 2;
    }

    @Override
    public Iterator<ComplexSamples> iterator()
    {
        return new ComplexSamplesIterator();
    }

    @Override
    public Iterator<InterleavedComplexSamples> iteratorInterleaved()
    {
        return new InterleavedComplexSamplesIterator();
    }

    // =========================================================================
    // Iterators
    // =========================================================================

    /**
     * Iterates over the raw short array and produces {@link ComplexSamples} fragments of
     * {@link #FRAGMENT_SIZE} complex samples each, with DC offset correction applied.
     */
    private class ComplexSamplesIterator implements Iterator<ComplexSamples>
    {
        /** Current position in the interleaved short array (advances by 2 per complex sample). */
        private int mPointer = 0;

        @Override
        public boolean hasNext()
        {
            return mPointer < mSamples.length;
        }

        @Override
        public ComplexSamples next()
        {
            long timestamp = getFragmentTimestamp(mPointer);

            float[] i = new float[FRAGMENT_SIZE];
            float[] q = new float[FRAGMENT_SIZE];

            int offset = mPointer;

            for(int idx = 0; idx < FRAGMENT_SIZE; idx++)
            {
                i[idx] = (mSamples[offset++] / SCALE) - mIAverageDc;
                q[idx] = (mSamples[offset++] / SCALE) - mQAverageDc;
            }

            mPointer = offset;
            return new ComplexSamples(i, q, timestamp);
        }
    }

    /**
     * Iterates over the raw short array and produces {@link InterleavedComplexSamples} fragments
     * of {@link #FRAGMENT_SIZE} complex samples each (stored as interleaved I, Q floats),
     * with DC offset correction applied.
     */
    private class InterleavedComplexSamplesIterator implements Iterator<InterleavedComplexSamples>
    {
        private int mPointer = 0;

        @Override
        public boolean hasNext()
        {
            return mPointer < mSamples.length;
        }

        @Override
        public InterleavedComplexSamples next()
        {
            long timestamp = getFragmentTimestamp(mPointer);

            // FRAGMENT_SIZE complex samples → FRAGMENT_SIZE * 2 floats (interleaved I, Q)
            float[] converted = new float[FRAGMENT_SIZE * 2];

            int offset = mPointer;

            for(int idx = 0; idx < converted.length; idx += 2)
            {
                converted[idx]     = (mSamples[offset++] / SCALE) - mIAverageDc;
                converted[idx + 1] = (mSamples[offset++] / SCALE) - mQAverageDc;
            }

            mPointer = offset;
            return new InterleavedComplexSamples(converted, timestamp);
        }
    }
}
