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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;

/**
 * Configuration for a PlutoSDR tuner accessed via a network IQ stream server
 * (e.g. a Python-based SigMF/PySDR TCP server running on the host that has the PlutoSDR attached).
 *
 * The server sends raw interleaved signed 16-bit little-endian IQ samples over a plain TCP socket.
 * The client (this class) connects, optionally sends a JSON command to configure the radio, then
 * reads the sample stream.
 *
 * Compatible server implementations:
 *   - The companion PlutoSDR Python server (pluto_server.py) included in this project's docs/plutosdr/
 *   - Any server that streams raw interleaved int16 IQ samples over TCP
 */
public class PlutoSdrTunerConfiguration extends TunerConfiguration
{
    /** Minimum tunable frequency for the AD9361 chip used in the PlutoSDR */
    public static final long MINIMUM_FREQUENCY_HZ = 70_000_000L;   // 70 MHz
    /** Maximum tunable frequency for the AD9361 chip used in the PlutoSDR */
    public static final long MAXIMUM_FREQUENCY_HZ = 6_000_000_000L; // 6 GHz

    /** Default sample rates available on the PlutoSDR / AD9361 */
    public static final int DEFAULT_SAMPLE_RATE = 2_500_000; // 2.5 MSPS

    /**
     * Sentinel value meaning "let the AD9361 driver choose the RF bandwidth automatically"
     * (typically ~0.75 × sample_rate).  Stored as 0 so that the server knows not to
     * override the driver default.
     */
    public static final int RF_BANDWIDTH_AUTO = 0;

    /** Minimum RF bandwidth supported by the AD9361 (Hz). */
    public static final int MIN_RF_BANDWIDTH_HZ = 200_000;   // 200 kHz
    /** Maximum RF bandwidth supported by the AD9361 (Hz). */
    public static final int MAX_RF_BANDWIDTH_HZ = 56_000_000; // 56 MHz

    private String mHost = "localhost";
    private int mPort = 1234;
    private int mSampleRate = DEFAULT_SAMPLE_RATE;
    private int mRfGain = 30;          // dB, 0-73 for AD9361
    private boolean mAgcEnabled = false;
    private int mRfBandwidth = RF_BANDWIDTH_AUTO;  // 0 = auto

    /**
     * Default constructor for Jackson/JAXB deserialization
     */
    public PlutoSdrTunerConfiguration()
    {
        super(MINIMUM_FREQUENCY_HZ, MAXIMUM_FREQUENCY_HZ);
    }

    /**
     * Constructs an instance with the given unique ID (used to persist/restore configuration)
     */
    public PlutoSdrTunerConfiguration(String uniqueID)
    {
        super(uniqueID);
        setMinimumFrequency(MINIMUM_FREQUENCY_HZ);
        setMaximumFrequency(MAXIMUM_FREQUENCY_HZ);
    }

    @JsonIgnore
    @Override
    public TunerType getTunerType()
    {
        return TunerType.PLUTO_SDR;
    }

    // -------------------------------------------------------------------------
    // Host / Port
    // -------------------------------------------------------------------------

    @JacksonXmlProperty(isAttribute = true, localName = "host")
    public String getHost()
    {
        return mHost;
    }

    public void setHost(String host)
    {
        mHost = host;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "port")
    public int getPort()
    {
        return mPort;
    }

    public void setPort(int port)
    {
        mPort = port;
    }

    // -------------------------------------------------------------------------
    // Sample Rate
    // -------------------------------------------------------------------------

    @JacksonXmlProperty(isAttribute = true, localName = "sample_rate")
    public int getSampleRate()
    {
        return mSampleRate;
    }

    public void setSampleRate(int sampleRate)
    {
        mSampleRate = sampleRate;
    }

    // -------------------------------------------------------------------------
    // Gain
    // -------------------------------------------------------------------------

    @JacksonXmlProperty(isAttribute = true, localName = "rf_gain")
    public int getRfGain()
    {
        return mRfGain;
    }

    public void setRfGain(int rfGain)
    {
        mRfGain = rfGain;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "agc_enabled")
    public boolean isAgcEnabled()
    {
        return mAgcEnabled;
    }

    public void setAgcEnabled(boolean agcEnabled)
    {
        mAgcEnabled = agcEnabled;
    }

    // -------------------------------------------------------------------------
    // RF Bandwidth
    // -------------------------------------------------------------------------

    /**
     * Returns the configured RF bandwidth in Hz, or {@link #RF_BANDWIDTH_AUTO} (0) to let the
     * AD9361 driver choose automatically (typically ~0.75 × sample_rate).
     */
    @JacksonXmlProperty(isAttribute = true, localName = "rf_bandwidth")
    public int getRfBandwidth()
    {
        return mRfBandwidth;
    }

    /**
     * Sets the RF bandwidth in Hz.  Pass {@link #RF_BANDWIDTH_AUTO} (0) to use the driver default.
     */
    public void setRfBandwidth(int rfBandwidth)
    {
        mRfBandwidth = rfBandwidth;
    }
}
