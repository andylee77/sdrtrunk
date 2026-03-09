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

import io.github.dsheirer.preference.source.ChannelizerType;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerType;

/**
 * PlutoSDR Tuner – wraps a {@link PlutoSdrTunerController} and exposes it to the SDRTrunk
 * tuner management framework.
 *
 * <p>The PlutoSDR is accessed via a companion TCP IQ-stream server (see
 * {@code docs/plutosdr/pluto_server.py}).  The server connects to the PlutoSDR over its
 * Ethernet interface (default {@code 192.168.2.1}) using the libiio / pyadi-iio Python
 * bindings and streams raw interleaved signed 16-bit IQ samples to this client.</p>
 */
public class PlutoSdrTuner extends Tuner
{
    /**
     * Constructs an instance.
     *
     * @param controller         the PlutoSDR tuner controller
     * @param tunerErrorListener listener to receive fatal errors from this tuner
     * @param channelizerType    channelizer type to use for this tuner
     */
    public PlutoSdrTuner(PlutoSdrTunerController controller, ITunerErrorListener tunerErrorListener,
                         ChannelizerType channelizerType)
    {
        super(controller, tunerErrorListener, channelizerType);
    }

    /**
     * Returns the preferred display name for this tuner, incorporating the server host and port.
     */
    @Override
    public String getPreferredName()
    {
        return "PlutoSDR @ " + getController().getHost() + ":" + getController().getPort();
    }

    /**
     * Typed access to the underlying controller.
     */
    public PlutoSdrTunerController getController()
    {
        return (PlutoSdrTunerController) getTunerController();
    }

    @Override
    public TunerClass getTunerClass()
    {
        return TunerClass.PLUTO_SDR;
    }

    @Override
    public TunerType getTunerType()
    {
        return TunerType.PLUTO_SDR;
    }

    /**
     * Unique identifier used to persist and restore the tuner configuration.
     * Uses the server host:port as the unique key.
     */
    @Override
    public String getUniqueID()
    {
        return "PlutoSDR:" + getController().getHost() + ":" + getController().getPort();
    }

    /**
     * Effective sample size in bits.  The AD9361 produces 12-bit samples sign-extended to 16 bits.
     */
    @Override
    public double getSampleSize()
    {
        return 12.0;
    }

    /**
     * Maximum theoretical USB/network bits per second.
     * At 61.44 MSPS (max AD9361 rate) × 16 bits × 2 (I+Q) = ~1.97 Gbps.
     * In practice the Ethernet link limits this to ~100 Mbps for the standard PlutoSDR.
     * We return a conservative value that reflects the typical 2.5 MSPS default.
     */
    @Override
    public int getMaximumUSBBitsPerSecond()
    {
        // 16 bits/sample × 2 (I+Q) × 61.44 MSPS theoretical max
        return 1_966_080_000;
    }
}
