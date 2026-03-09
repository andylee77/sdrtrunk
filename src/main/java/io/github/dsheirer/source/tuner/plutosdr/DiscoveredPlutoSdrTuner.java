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
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A discovered PlutoSDR tuner that is accessed via a companion TCP IQ-stream server.
 *
 * <p>Unlike USB tuners, PlutoSDR devices are not discovered automatically via USB enumeration.
 * Instead, they are added manually by the user through the tuner configuration UI, which
 * creates a {@link PlutoSdrTunerConfiguration} that is persisted and reloaded on startup.</p>
 *
 * <p>The unique ID is derived from the server host and port so that the enabled/disabled state
 * and configuration are correctly restored across application restarts.</p>
 */
public class DiscoveredPlutoSdrTuner extends DiscoveredTuner
{
    private static final Logger mLog = LoggerFactory.getLogger(DiscoveredPlutoSdrTuner.class);

    private final PlutoSdrTunerConfiguration mConfiguration;
    private final ChannelizerType mChannelizerType;

    /**
     * Constructs an instance.
     *
     * @param configuration   the persisted configuration for this PlutoSDR
     * @param channelizerType the channelizer type to use
     */
    public DiscoveredPlutoSdrTuner(PlutoSdrTunerConfiguration configuration, ChannelizerType channelizerType)
    {
        mConfiguration    = configuration;
        mChannelizerType  = channelizerType;
        // Pre-load the configuration so it is available before start() is called
        mTunerConfiguration = configuration;
    }

    // =========================================================================
    // DiscoveredTuner abstract implementations
    // =========================================================================

    @Override
    public TunerClass getTunerClass()
    {
        return TunerClass.PLUTO_SDR;
    }

    /**
     * Unique identifier for this discovered tuner.
     * Format: {@code PlutoSDR:<host>:<port>}
     */
    @Override
    public String getId()
    {
        return "PlutoSDR:" + mConfiguration.getHost() + ":" + mConfiguration.getPort();
    }

    /**
     * Starts the PlutoSDR tuner by creating a controller and connecting to the companion server.
     *
     * <p>The configuration (frequency, sample rate, gain, etc.) must be applied to the controller
     * <em>before</em> {@link PlutoSdrTunerController#start()} is called, because {@code start()}
     * opens the TCP connection and sends the initial JSON command using those values.  Applying
     * the configuration afterwards would send zeroes/defaults to the server.</p>
     */
    @Override
    public void start()
    {
        if(isAvailable() && !hasTuner())
        {
            try
            {
                PlutoSdrTunerController controller = new PlutoSdrTunerController(
                        mConfiguration.getHost(),
                        mConfiguration.getPort(),
                        this);

                // Apply configuration BEFORE start() so the initial connect() uses the correct
                // frequency, sample rate, gain, etc.
                controller.applyConfiguration(mConfiguration);

                mTuner = new PlutoSdrTuner(controller, this, mChannelizerType);
                mTuner.start();

                mLog.info("PlutoSDR tuner started: {}", getId());
            }
            catch(SourceException se)
            {
                setErrorMessage(se.getMessage());
                mLog.error("Unable to start PlutoSDR tuner [{}] - error: {}", getId(), getErrorMessage());
            }
        }
    }

    @Override
    public String toString()
    {
        return "PlutoSDR Tuner - " + getId();
    }
}
