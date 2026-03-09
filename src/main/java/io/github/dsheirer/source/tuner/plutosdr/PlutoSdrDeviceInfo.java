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
package io.github.dsheirer.source.tuner.plutosdr;

/**
 * Snapshot of live device information received from the PlutoSDR companion server.
 *
 * <p>Fields are populated from the JSON response sent by the server on initial connection
 * and from periodic {@code {"command":"status"}} poll responses.  Fields that have not yet
 * been received from the server default to {@code "unknown"} / {@code Double.NaN} / {@code 0}.</p>
 *
 * <p>Instances are immutable-by-convention: the controller creates a new instance (or a copy)
 * each time it updates the info, and stores it in an {@code AtomicReference}.</p>
 */
public class PlutoSdrDeviceInfo
{
    private String mHwModel            = "unknown";
    private String mHwSerial           = "unknown";
    private String mFwVersion          = "unknown";
    private double mTemperatureCelsius = Double.NaN;
    private String mRssi               = "unknown";
    private int    mActualRfBandwidth  = 0;   // Hz; 0 = not yet known
    private long   mTunedFrequency     = 0;   // Hz; the PPM-corrected frequency the hardware is tuned to
    private int    mSampleRate         = 0;   // Hz; current sample rate

    /** Default constructor – all fields at their "not yet known" defaults. */
    public PlutoSdrDeviceInfo()
    {
    }

    /** Copy constructor – creates a mutable copy of {@code other}. */
    public PlutoSdrDeviceInfo(PlutoSdrDeviceInfo other)
    {
        if(other != null)
        {
            mHwModel           = other.mHwModel;
            mHwSerial          = other.mHwSerial;
            mFwVersion         = other.mFwVersion;
            mTemperatureCelsius = other.mTemperatureCelsius;
            mRssi              = other.mRssi;
            mActualRfBandwidth = other.mActualRfBandwidth;
            mTunedFrequency    = other.mTunedFrequency;
            mSampleRate        = other.mSampleRate;
        }
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    /** Hardware model string (e.g. "ADALM-PLUTO"). */
    public String getHwModel()
    {
        return mHwModel;
    }

    public void setHwModel(String hwModel)
    {
        mHwModel = (hwModel != null && !hwModel.isBlank()) ? hwModel : "unknown";
    }

    /** Hardware serial number. */
    public String getHwSerial()
    {
        return mHwSerial;
    }

    public void setHwSerial(String hwSerial)
    {
        mHwSerial = (hwSerial != null && !hwSerial.isBlank()) ? hwSerial : "unknown";
    }

    /** Firmware version string. */
    public String getFwVersion()
    {
        return mFwVersion;
    }

    public void setFwVersion(String fwVersion)
    {
        mFwVersion = (fwVersion != null && !fwVersion.isBlank()) ? fwVersion : "unknown";
    }

    /**
     * AD9361 die temperature in degrees Celsius.
     * Returns {@link Double#NaN} if not yet received from the server.
     */
    public double getTemperatureCelsius()
    {
        return mTemperatureCelsius;
    }

    public void setTemperatureCelsius(double temperatureCelsius)
    {
        mTemperatureCelsius = temperatureCelsius;
    }

    /**
     * Returns a formatted temperature string, e.g. {@code "42.5 °C"}, or {@code "—"} if unknown.
     */
    public String getTemperatureString()
    {
        if(Double.isNaN(mTemperatureCelsius))
        {
            return "\u2014";   // em-dash
        }
        return String.format("%.1f \u00B0C", mTemperatureCelsius);
    }

    /** RX RSSI string as reported by the AD9361 (e.g. {@code "93.75 dB"}). */
    public String getRssi()
    {
        return mRssi;
    }

    public void setRssi(String rssi)
    {
        mRssi = (rssi != null && !rssi.isBlank()) ? rssi : "unknown";
    }

    /**
     * Actual RF bandwidth set by the AD9361 driver in Hz.
     * Returns {@code 0} if not yet received from the server.
     */
    public int getActualRfBandwidth()
    {
        return mActualRfBandwidth;
    }

    public void setActualRfBandwidth(int actualRfBandwidth)
    {
        mActualRfBandwidth = actualRfBandwidth;
    }

    /**
     * Returns a formatted RF bandwidth string, e.g. {@code "1.875 MHz"}, or {@code "—"} if unknown.
     */
    public String getActualRfBandwidthString()
    {
        if(mActualRfBandwidth <= 0)
        {
            return "\u2014";   // em-dash
        }
        if(mActualRfBandwidth >= 1_000_000)
        {
            return String.format("%.3f MHz", mActualRfBandwidth / 1_000_000.0);
        }
        return String.format("%d kHz", mActualRfBandwidth / 1_000);
    }

    /**
     * The PPM-corrected frequency the hardware is actually tuned to, in Hz.
     * Returns {@code 0} if not yet known.
     */
    public long getTunedFrequency()
    {
        return mTunedFrequency;
    }

    public void setTunedFrequency(long tunedFrequency)
    {
        mTunedFrequency = tunedFrequency;
    }

    /**
     * Returns a formatted tuned frequency string, e.g. {@code "858.212156 MHz"}, or {@code "—"} if unknown.
     */
    public String getTunedFrequencyString()
    {
        if(mTunedFrequency <= 0)
        {
            return "\u2014";   // em-dash
        }
        return String.format("%.6f MHz", mTunedFrequency / 1_000_000.0);
    }

    /**
     * Current sample rate in Hz.
     * Returns {@code 0} if not yet known.
     */
    public int getSampleRate()
    {
        return mSampleRate;
    }

    public void setSampleRate(int sampleRate)
    {
        mSampleRate = sampleRate;
    }

    /**
     * Returns a formatted sample rate string, e.g. {@code "5.000 MHz"}, or {@code "—"} if unknown.
     */
    public String getSampleRateString()
    {
        if(mSampleRate <= 0)
        {
            return "\u2014";   // em-dash
        }
        if(mSampleRate >= 1_000_000)
        {
            return String.format("%.3f MHz", mSampleRate / 1_000_000.0);
        }
        return String.format("%d kHz", mSampleRate / 1_000);
    }

    /** Returns true if any device info has been received (i.e. hw_model is not "unknown"). */
    public boolean hasInfo()
    {
        return !"unknown".equals(mHwModel);
    }

    @Override
    public String toString()
    {
        return "PlutoSdrDeviceInfo{model=" + mHwModel +
                ", serial=" + mHwSerial +
                ", fw=" + mFwVersion +
                ", temp=" + getTemperatureString() +
                ", rssi=" + mRssi +
                ", rfBw=" + getActualRfBandwidthString() +
                ", tunedFreq=" + getTunedFrequencyString() +
                ", sampleRate=" + getSampleRateString() + "}";
    }
}
