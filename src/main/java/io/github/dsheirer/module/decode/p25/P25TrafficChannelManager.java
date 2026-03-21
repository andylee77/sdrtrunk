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
package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.ChannelEvent.Event;
import io.github.dsheirer.controller.channel.IChannelEventListener;
import io.github.dsheirer.controller.channel.IChannelEventProvider;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.TalkerAliasManager;
import io.github.dsheirer.identifier.patch.PatchGroupPreLoadDataContent;
import io.github.dsheirer.log.LoggingSuppressor;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.IDecodeEventProvider;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25ExplicitChannel;
import io.github.dsheirer.module.decode.p25.identifier.channel.P25Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.P25P2Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.P25P2ExplicitChannel;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCNetworkStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.NetworkStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.ScrambleParameters;
import io.github.dsheirer.calllog.CallLogWriter;
import io.github.dsheirer.module.decode.p25.session.P25CallSessionManager;
import io.github.dsheirer.module.decode.traffic.TrafficChannelManager;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Traffic Channel Pool Manager for P25.
 *
 * Phase 4 Architecture: This class is ONLY a channel pool allocator. All call tracking,
 * event creation, filtering, and duration management is handled by P25CallSessionManager (CSM).
 *
 * Responsibilities:
 * - Create and manage Phase 1 and Phase 2 traffic channel pools
 * - Allocate/release channels from pools (used by CSM)
 * - Send channel start/stop requests via event bus
 * - Monitor channel teardown/rejection and reclaim channels
 * - Manage frequency band data for channel preloading
 * - Capture Phase 2 scramble parameters from network status broadcasts
 * - Wire CSM to decode event listener and call log writer
 */
public class P25TrafficChannelManager extends TrafficChannelManager implements IDecodeEventProvider,
    IChannelEventListener, IChannelEventProvider, IMessageListener
{
    private static final Logger mLog = LoggerFactory.getLogger(P25TrafficChannelManager.class);
    public static final String CHANNEL_START_REJECTED = "CHANNEL START REJECTED";
    public static final String MAX_TRAFFIC_CHANNELS_EXCEEDED = "MAX TRAFFIC CHANNELS EXCEEDED";

    private Queue<Channel> mAvailablePhase1TrafficChannelQueue = new LinkedTransferQueue<>();
    private Queue<Channel> mAvailablePhase2TrafficChannelQueue = new LinkedTransferQueue<>();
    private List<Channel> mManagedPhase1TrafficChannels;
    private List<Channel> mManagedPhase2TrafficChannels;
    private Map<Long,Channel> mAllocatedTrafficChannelMap = new HashMap<>();
    private ReentrantLock mLock = new ReentrantLock();
    private Map<Integer, IFrequencyBand> mFrequencyBandMap = new ConcurrentHashMap<>();
    private Listener<ChannelEvent> mChannelEventListener;
    private Listener<IDecodeEvent> mDecodeEventListener;
    private TrafficChannelTeardownMonitor mTrafficChannelTeardownMonitor = new TrafficChannelTeardownMonitor();
    private Channel mParentChannel;
    private ScrambleParameters mPhase2ScrambleParameters;
    private Listener<IMessage> mMessageListener;
    private TalkerAliasManager mTalkerAliasManager = new TalkerAliasManager();
    private P25CallSessionManager mCallSessionManager;
    private CallLogWriter mCallLogWriter;

    /**
     * Constructs an instance.
     * @param parentChannel (ie control channel) that owns this traffic channel manager
     */
    public P25TrafficChannelManager(Channel parentChannel)
    {
        mParentChannel = parentChannel;

        boolean ignoreDataCalls = false;
        boolean ignoreEncryptedCalls = false;
        boolean ignoreUnmonitoredCalls = false;

        if(parentChannel.getDecodeConfiguration() instanceof DecodeConfigP25Phase1 phase1)
        {
            ignoreDataCalls = phase1.getIgnoreDataCalls();
            ignoreEncryptedCalls = phase1.getIgnoreEncryptedCalls();
            ignoreUnmonitoredCalls = phase1.getIgnoreUnmonitoredCalls();
            createPhase1TrafficChannels(phase1.getTrafficChannelPoolSize(), phase1);
            createPhase2TrafficChannels(phase1.getTrafficChannelPoolSize(), new DecodeConfigP25Phase2());
        }
        else if(parentChannel.getDecodeConfiguration() instanceof DecodeConfigP25Phase2 phase2)
        {
            ignoreDataCalls = phase2.getIgnoreDataCalls();
            ignoreEncryptedCalls = phase2.getIgnoreEncryptedCalls();
            ignoreUnmonitoredCalls = phase2.getIgnoreUnmonitoredCalls();
            createPhase1TrafficChannels(phase2.getTrafficChannelPoolSize(), new DecodeConfigP25Phase1());
            createPhase2TrafficChannels(phase2.getTrafficChannelPoolSize(), phase2);
        }

        mCallSessionManager = new P25CallSessionManager();
        mCallSessionManager.setTrafficChannelManager(this);
        mCallSessionManager.setIgnoreDataCalls(ignoreDataCalls);
        mCallSessionManager.setIgnoreEncryptedCalls(ignoreEncryptedCalls);
        mCallSessionManager.setIgnoreUnmonitoredCalls(ignoreUnmonitoredCalls);

        mCallLogWriter = new CallLogWriter(
            parentChannel.getSystem(),
            parentChannel.getSite(),
            parentChannel.getName()
        );
        mCallSessionManager.addListener(mCallLogWriter);
    }

    public P25CallSessionManager getCallSessionManager() { return mCallSessionManager; }
    public CallLogWriter getCallLogWriter() { return mCallLogWriter; }
    public TalkerAliasManager getTalkerAliasManager() { return mTalkerAliasManager; }
    public ScrambleParameters getPhase2ScrambleParameters() { return mPhase2ScrambleParameters; }

    public String getAliasListName()
    {
        return mParentChannel != null ? mParentChannel.getAliasListName() : null;
    }

    public void setAliasList(AliasList aliasList)
    {
        if(mCallSessionManager != null)
        {
            mCallSessionManager.setAliasList(aliasList);
        }
    }

    /**
     * Stores a frequency band for preload data when starting traffic channels.
     */
    public void processFrequencyBand(IFrequencyBand frequencyBand)
    {
        mFrequencyBandMap.put(frequencyBand.getIdentifier(), frequencyBand);
    }

    /**
     * Broadcasts a decode event to the Events tab. Used by P25P2DecoderState for
     * data decode events that don't go through the CSM call session flow.
     */
    public void broadcast(DecodeEvent decodeEvent)
    {
        if(mDecodeEventListener != null)
        {
            mDecodeEventListener.receive(decodeEvent);
        }
    }

    // ========================================================================
    // Control Frequency Management
    // ========================================================================

    @Override
    protected void processControlFrequencyUpdate(long previous, long current, Channel parentChannel)
    {
        if(previous == current) return;

        mLock.lock();
        try
        {
            List<Channel> toDisable = new ArrayList<>(mAllocatedTrafficChannelMap.values());
            for(Channel ch : toDisable)
            {
                if(!parentChannel.equals(ch))
                {
                    broadcast(new ChannelEvent(ch, Event.REQUEST_DISABLE));
                }
            }
            mAllocatedTrafficChannelMap.remove(previous);
            mAllocatedTrafficChannelMap.put(current, parentChannel);
        }
        finally
        {
            mLock.unlock();
        }
    }

    // ========================================================================
    // Channel Pool Creation
    // ========================================================================

    private void createPhase1TrafficChannels(int poolSize, DecodeConfigP25Phase1 decodeConfig)
    {
        if(mManagedPhase1TrafficChannels == null)
        {
            List<Channel> list = new ArrayList<>();
            for(int x = 0; x < poolSize; x++)
            {
                Channel ch = new Channel("T-" + mParentChannel.getName(), ChannelType.TRAFFIC);
                ch.setAliasListName(mParentChannel.getAliasListName());
                ch.setSystem(mParentChannel.getSystem());
                ch.setSite(mParentChannel.getSite());
                ch.setDecodeConfiguration(decodeConfig);
                ch.setEventLogConfiguration(mParentChannel.getEventLogConfiguration());
                ch.setRecordConfiguration(mParentChannel.getRecordConfiguration());
                list.add(ch);
            }
            mAvailablePhase1TrafficChannelQueue.addAll(list);
            mManagedPhase1TrafficChannels = Collections.unmodifiableList(list);
        }
    }

    private void createPhase2TrafficChannels(int poolSize, DecodeConfiguration decodeConfig)
    {
        if(mManagedPhase2TrafficChannels == null)
        {
            List<Channel> list = new ArrayList<>();
            for(int x = 0; x < poolSize; x++)
            {
                Channel ch = new Channel("T-" + mParentChannel.getName(), ChannelType.TRAFFIC);
                ch.setAliasListName(mParentChannel.getAliasListName());
                ch.setSystem(mParentChannel.getSystem());
                ch.setSite(mParentChannel.getSite());
                ch.setDecodeConfiguration(decodeConfig);
                ch.setEventLogConfiguration(mParentChannel.getEventLogConfiguration());
                ch.setRecordConfiguration(mParentChannel.getRecordConfiguration());
                list.add(ch);
            }
            mAvailablePhase2TrafficChannelQueue.addAll(list);
            mManagedPhase2TrafficChannels = Collections.unmodifiableList(list);
        }
    }

    // ========================================================================
    // Pool API — used by P25CallSessionManager
    // ========================================================================

    public Channel allocatePhase1TrafficChannel(APCO25Channel apco25Channel, IdentifierCollection ic, long timestamp)
    {
        long frequency = apco25Channel.getDownlinkFrequency();
        mLock.lock();
        try
        {
            if(mAllocatedTrafficChannelMap.containsKey(frequency))
            {
                return mAllocatedTrafficChannelMap.get(frequency);
            }
            Channel ch = mAvailablePhase1TrafficChannelQueue.poll();
            if(ch != null)
            {
                requestTrafficChannelStart(ch, apco25Channel, ic, timestamp);
                return ch;
            }
            return null;
        }
        finally
        {
            mLock.unlock();
        }
    }

    public Channel allocatePhase2TrafficChannel(APCO25Channel apco25Channel, IdentifierCollection ic, long timestamp)
    {
        long frequency = apco25Channel.getDownlinkFrequency();
        mLock.lock();
        try
        {
            if(mAllocatedTrafficChannelMap.containsKey(frequency) || frequency == getCurrentControlFrequency())
            {
                return mAllocatedTrafficChannelMap.get(frequency);
            }
            Channel ch = mAvailablePhase2TrafficChannelQueue.poll();
            if(ch != null)
            {
                requestTrafficChannelStart(ch, apco25Channel, ic, timestamp);
                return ch;
            }
            return null;
        }
        finally
        {
            mLock.unlock();
        }
    }

    public void releaseTrafficChannel(long frequency)
    {
        mLock.lock();
        try
        {
            Channel ch = mAllocatedTrafficChannelMap.remove(frequency);
            if(ch != null)
            {
                mLog.info("Releasing traffic channel for frequency {} (patch consolidation)", frequency);
                broadcast(new ChannelEvent(ch, Event.REQUEST_DISABLE));
            }
        }
        finally
        {
            mLock.unlock();
        }
    }

    public boolean isTrafficChannelAllocated(long frequency)
    {
        mLock.lock();
        try
        {
            return mAllocatedTrafficChannelMap.containsKey(frequency);
        }
        finally
        {
            mLock.unlock();
        }
    }

    public static APCO25Channel convertPhase2ToPhase1Channel(APCO25Channel channel)
    {
        return convertPhase2ToPhase1(channel);
    }

    private void requestTrafficChannelStart(Channel trafficChannel, APCO25Channel apco25Channel,
                                            IdentifierCollection ic, long timestamp)
    {
        if(apco25Channel != null && apco25Channel.getDownlinkFrequency() > 0 && getInterModuleEventBus() != null)
        {
            SourceConfigTuner sourceConfig = new SourceConfigTuner();
            sourceConfig.setFrequency(apco25Channel.getDownlinkFrequency());
            if(mParentChannel.getSourceConfiguration() instanceof SourceConfigTuner parentConfig)
            {
                sourceConfig.setPreferredTuner(parentConfig.getPreferredTuner());
            }
            trafficChannel.setSourceConfiguration(sourceConfig);

            if(mPhase2ScrambleParameters != null &&
                trafficChannel.getDecodeConfiguration() instanceof DecodeConfigP25Phase2 p2)
            {
                p2.setScrambleParameters(mPhase2ScrambleParameters.copy());
            }

            mAllocatedTrafficChannelMap.put(apco25Channel.getDownlinkFrequency(), trafficChannel);

            ChannelStartProcessingRequest request = new ChannelStartProcessingRequest(trafficChannel,
                    apco25Channel, ic, this);
            request.addPreloadDataContent(new PatchGroupPreLoadDataContent(ic, timestamp));
            request.addPreloadDataContent(new P25FrequencyBandPreloadDataContent(mFrequencyBandMap.values()));
            getInterModuleEventBus().post(request);
        }
        else
        {
            if(mManagedPhase1TrafficChannels.contains(trafficChannel))
            {
                mAvailablePhase1TrafficChannelQueue.add(trafficChannel);
            }
            else if(mManagedPhase2TrafficChannels.contains(trafficChannel))
            {
                mAvailablePhase2TrafficChannelQueue.add(trafficChannel);
            }
        }
    }

    // ========================================================================
    // Channel Event Handling
    // ========================================================================

    @Override
    public Listener<ChannelEvent> getChannelEventListener() { return mTrafficChannelTeardownMonitor; }

    private void broadcast(ChannelEvent channelEvent)
    {
        if(mChannelEventListener != null) { mChannelEventListener.receive(channelEvent); }
    }

    @Override
    public void setChannelEventListener(Listener<ChannelEvent> listener) { mChannelEventListener = listener; }

    @Override
    public void removeChannelEventListener() { mChannelEventListener = null; }

    @Override
    public void addDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListener = listener;
        if(mCallSessionManager != null) { mCallSessionManager.setDecodeEventListener(listener); }
    }

    @Override
    public void removeDecodeEventListener(Listener<IDecodeEvent> listener) { mDecodeEventListener = null; }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Override
    public void reset() {}

    @Override
    public void start()
    {
        if(mCallLogWriter != null) { mCallLogWriter.start(); }
        if(mCallSessionManager != null) { mCallSessionManager.start(); }
    }

    @Override
    public void stop()
    {
        if(mCallSessionManager != null) { mCallSessionManager.stop(); }
        if(mCallLogWriter != null) { mCallLogWriter.stop(); }

        for(Channel ch : new ArrayList<>(mAllocatedTrafficChannelMap.values()))
        {
            broadcast(new ChannelEvent(ch, Event.REQUEST_DISABLE));
        }
        mAvailablePhase1TrafficChannelQueue.clear();
        mAvailablePhase2TrafficChannelQueue.clear();
    }

    @Override
    public Listener<IMessage> getMessageListener()
    {
        if(mMessageListener == null)
        {
            mMessageListener = message -> {
                if(mPhase2ScrambleParameters == null && message.isValid())
                {
                    if(message instanceof NetworkStatusBroadcast nsb)
                    {
                        mPhase2ScrambleParameters = nsb.getScrambleParameters();
                    }
                    else if(message instanceof AMBTCNetworkStatusBroadcast nsb)
                    {
                        mPhase2ScrambleParameters = nsb.getScrambleParameters();
                    }
                }
            };
        }
        return mMessageListener;
    }

    private static APCO25Channel convertPhase2ToPhase1(APCO25Channel channel)
    {
        P25Channel toConvert = channel.getValue();
        if(toConvert instanceof P25P2ExplicitChannel phase2)
        {
            return APCO25ExplicitChannel.create(phase2.getDownlinkBandIdentifier(),
                phase2.getDownlinkChannelNumber(), phase2.getUplinkBandIdentifier(),
                phase2.getUplinkChannelNumber());
        }
        else if(toConvert instanceof P25P2Channel phase2)
        {
            return APCO25Channel.create(phase2.getDownlinkBandIdentifier(), phase2.getDownlinkChannelNumber());
        }
        return channel;
    }

    // ========================================================================
    // Traffic Channel Teardown Monitor
    // ========================================================================

    public class TrafficChannelTeardownMonitor implements Listener<ChannelEvent>
    {
        @Override
        public void receive(ChannelEvent channelEvent)
        {
            Channel channel = channelEvent.getChannel();

            if(mManagedPhase1TrafficChannels.contains(channel))
            {
                mLock.lock();
                try
                {
                    switch(channelEvent.getEvent())
                    {
                        case NOTIFICATION_PROCESSING_STOP:
                            mAllocatedTrafficChannelMap.entrySet().stream()
                                .filter(e -> e.getValue() == channel)
                                .map(Map.Entry::getKey).findFirst()
                                .ifPresent(freq -> {
                                    mAllocatedTrafficChannelMap.remove(freq);
                                    mAvailablePhase1TrafficChannelQueue.add(channel);
                                });
                            break;
                        case NOTIFICATION_PROCESSING_START_REJECTED:
                            mAllocatedTrafficChannelMap.entrySet().stream()
                                .filter(e -> e.getValue() == channel)
                                .map(Map.Entry::getKey).findFirst()
                                .ifPresent(freq -> {
                                    mAllocatedTrafficChannelMap.remove(freq);
                                    mAvailablePhase1TrafficChannelQueue.add(channel);
                                    mLog.warn("Phase 1 traffic channel start rejected for frequency {}: {}",
                                        freq, channelEvent.getDescription());
                                });
                            break;
                    }
                }
                finally { mLock.unlock(); }
            }
            else if(mManagedPhase2TrafficChannels.contains(channel))
            {
                mLock.lock();
                try
                {
                    switch(channelEvent.getEvent())
                    {
                        case NOTIFICATION_PROCESSING_STOP:
                            mAllocatedTrafficChannelMap.entrySet().stream()
                                .filter(e -> e.getValue() == channel)
                                .map(Map.Entry::getKey).findFirst()
                                .ifPresent(freq -> {
                                    mAllocatedTrafficChannelMap.remove(freq);
                                    mAvailablePhase2TrafficChannelQueue.add(channel);
                                });
                            break;
                        case NOTIFICATION_PROCESSING_START_REJECTED:
                            mAllocatedTrafficChannelMap.entrySet().stream()
                                .filter(e -> e.getValue() == channel)
                                .map(Map.Entry::getKey).findFirst()
                                .ifPresent(freq -> {
                                    mAllocatedTrafficChannelMap.remove(freq);
                                    mAvailablePhase2TrafficChannelQueue.add(channel);
                                    mLog.warn("Phase 2 traffic channel start rejected for frequency {}: {}",
                                        freq, channelEvent.getDescription());
                                });
                            break;
                    }
                }
                finally { mLock.unlock(); }
            }
        }
    }
}
