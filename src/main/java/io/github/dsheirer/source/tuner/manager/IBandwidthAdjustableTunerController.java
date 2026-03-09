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
package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.SourceException;

/**
 * Optional interface for tuner controllers that can dynamically adjust their sample rate
 * (and therefore their usable bandwidth) to accommodate a wider set of channels.
 *
 * <p>The {@link PolyphaseChannelSourceManager} checks for this interface when a requested
 * channel set does not fit within the current usable bandwidth.  If the controller implements
 * this interface and the sample rate is not locked, the source manager will call
 * {@link #setSampleRateForBandwidth(long)} to request a sample-rate increase before
 * retrying channel allocation.</p>
 *
 * <p>Implementing classes should pick the smallest supported sample rate whose usable
 * bandwidth (typically sample_rate × usable_bandwidth_percentage) is at least as wide as
 * the requested bandwidth.</p>
 */
public interface IBandwidthAdjustableTunerController
{
    /**
     * Attempts to set the tuner's sample rate to the smallest value whose usable bandwidth
     * is at least {@code requiredBandwidthHz} wide.
     *
     * @param requiredBandwidthHz the minimum usable bandwidth needed in Hertz
     * @throws SourceException if the sample rate cannot be changed (e.g. no suitable rate exists,
     *                         or the hardware rejected the change)
     */
    void setSampleRateForBandwidth(long requiredBandwidthHz) throws SourceException;
}
