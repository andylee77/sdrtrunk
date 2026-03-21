/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.module.decode.p25.session;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.identifier.scramble.ScrambleParameterIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.log.LoggingSuppressor;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.ScrambleParameters;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacOpcode;
import io.github.dsheirer.module.decode.p25.reference.ServiceOptions;
import io.github.dsheirer.module.decode.session.CallSession;
import io.github.dsheirer.module.decode.session.CallSessionEvent;
import io.github.dsheirer.module.decode.session.CallSessionListener;
import io.github.dsheirer.module.decode.session.CallState;
import io.github.dsheirer.module.decode.session.ChannelSourceType;
import io.github.dsheirer.sample.Listener;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central authority for P25 call session management (Phase 3).
 *
 * This is the SOLE recipient of all control channel traffic (grants, updates, terminations).
 * It manages CallSession lifecycle, applies filtering (encrypted/unmonitored/data), creates
 * P25ChannelGrantEvents for the Events tab, and delegates traffic channel allocation to the
 * P25TrafficChannelManager via its pool API.
 *
 * Two distinct event flows:
 * - Control channel events arrive via processChannelGrant() / processChannelUpdate()
 * - Traffic channel events arrive via onTrafficChannelUpdate() / onTrafficChannelEnd()
 *
 * Key responsibilities:
 * - Creates and manages call session lifecycle (PENDING → ACTIVE → ENDING → COMPLETE)
 * - Determines DecodeEventType from Opcode and MacOpcode
 * - Applies encrypted/unmonitored/data call filtering
 * - Creates P25ChannelGrantEvents for Events tab backward compatibility
 * - Requests traffic channel allocation via pool API
 * - Creates per-talker CallSessionEvent objects
 * - Notifies listeners on session creation, event addition/update, and completion
 * - Periodic cleanup of ENDING sessions past gap tolerance
 */
public class P25CallSessionManager
{
    private static final Logger mLog = LoggerFactory.getLogger(P25CallSessionManager.class);
    private static final LoggingSuppressor LOGGING_SUPPRESSOR = new LoggingSuppressor(mLog);

    /** Active sessions keyed by "frequency:timeslot" */
    private final Map<String, CallSession> mActiveSessions = new ConcurrentHashMap<>();

    /** Sessions in ENDING state waiting for gap tolerance timeout */
    private final Map<String, CallSession> mEndingSessions = new ConcurrentHashMap<>();

    /** Listeners for session lifecycle events */
    private final List<CallSessionListener> mListeners = new CopyOnWriteArrayList<>();

    /** Auto-incrementing session ID counter */
    private final AtomicLong mSessionIdCounter = new AtomicLong(1);

    /** Gap tolerance in milliseconds - sessions in ENDING state are finalized after this period */
    private long mGapToleranceMs = 3000;

    /** Timer for periodic cleanup of ENDING sessions */
    private ScheduledExecutorService mTimerService;

    /** Optional PatchGroupManager for enrichment */
    private PatchGroupManager mPatchGroupManager;

    /** Flag to track if the manager is running */
    private volatile boolean mRunning = false;

    // ========================================================================
    // Phase 3: New fields for grant processing authority
    // ========================================================================

    /** Back-reference to traffic channel manager for pool API and channel allocation */
    private P25TrafficChannelManager mTrafficChannelManager;

    /** Alias list for isUnmonitored() checks */
    private AliasList mAliasList;

    /** Filtering flags */
    private boolean mIgnoreDataCalls;
    private boolean mIgnoreEncryptedCalls;
    private boolean mIgnoreUnmonitoredCalls;

    /** Listener for broadcasting P25ChannelGrantEvents to the Events tab */
    private Listener<IDecodeEvent> mDecodeEventListener;

    /**
     * Constructs an instance.
     */
    public P25CallSessionManager()
    {
    }

    // ========================================================================
    // Phase 3: Configuration setters
    // ========================================================================

    /**
     * Sets the traffic channel manager back-reference for pool API calls.
     */
    public void setTrafficChannelManager(P25TrafficChannelManager trafficChannelManager)
    {
        mTrafficChannelManager = trafficChannelManager;
    }

    /**
     * Sets the alias list for isUnmonitored() filtering.
     */
    public void setAliasList(AliasList aliasList)
    {
        mAliasList = aliasList;
    }

    public void setIgnoreDataCalls(boolean ignoreDataCalls)
    {
        mIgnoreDataCalls = ignoreDataCalls;
    }

    public void setIgnoreEncryptedCalls(boolean ignoreEncryptedCalls)
    {
        mIgnoreEncryptedCalls = ignoreEncryptedCalls;
    }

    public void setIgnoreUnmonitoredCalls(boolean ignoreUnmonitoredCalls)
    {
        mIgnoreUnmonitoredCalls = ignoreUnmonitoredCalls;
    }

    /**
     * Sets the decode event listener for broadcasting P25ChannelGrantEvents to the Events tab.
     */
    public void setDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListener = listener;
    }

    // ========================================================================
    // Phase 3: Control Channel Grant Processing (AUTHORITY)
    // ========================================================================

    /**
     * Process a Phase 1 control channel grant. Primary entry point for ALL Phase 1
     * control channel grant messages. Replaces processP1ControlDirectedChannelGrant().
     *
     * Logic:
     * 1. Determine DecodeEventType from opcode
     * 2. Route TDMA channels to Phase 2 processing
     * 3. Check for existing session on this (freq, timeslot)
     * 4. If same call → update session, extend duration
     * 5. If different call → end old session, start new
     * 6. Apply encrypted/unmonitored/data filters
     * 7. Create CallSession, request traffic channel if needed
     * 8. Create P25ChannelGrantEvent for Events tab (SOURCE=CONTROL)
     * 9. Notify listeners
     */
    public void processChannelGrant(APCO25Channel apco25Channel, ServiceOptions serviceOptions,
                                    IdentifierCollection ic, Opcode opcode,
                                    long timestamp, String context)
    {
        if(!mRunning || apco25Channel.getDownlinkFrequency() <= 0)
        {
            return;
        }

        try
        {
            DecodeEventType decodeEventType = getEventType(opcode, serviceOptions, null);
            boolean isDataGrant = opcode != null && opcode.isDataChannelGrant();

            if(apco25Channel.isTDMAChannel())
            {
                // TDMA channel from P1 control → Phase 2 processing
                processP2ChannelGrantInternal(apco25Channel, serviceOptions, ic, decodeEventType,
                        isDataGrant, timestamp, context);
            }
            else
            {
                processPhase1Grant(apco25Channel, serviceOptions, ic, decodeEventType,
                        isDataGrant, timestamp, context);
            }
        }
        catch(Exception e)
        {
            mLog.error("Error processing channel grant", e);
        }
    }

    /**
     * Process a Phase 1 control channel grant update. Replaces
     * processP1ControlAnnouncedTrafficUpdate().
     *
     * Logic:
     * 1. Find existing session on (freq, timeslot)
     * 2. If same call → update duration
     * 3. If no session or different call → delegate to processChannelGrant()
     */
    public void processChannelUpdate(APCO25Channel apco25Channel, ServiceOptions serviceOptions,
                                     IdentifierCollection ic, Opcode opcode,
                                     long timestamp, String context)
    {
        if(!mRunning)
        {
            return;
        }

        try
        {
            long frequency = apco25Channel.getDownlinkFrequency();
            int timeslot = apco25Channel.isTDMAChannel() ? apco25Channel.getTimeslot() : P25P1Message.TIMESLOT_1;
            String key = sessionKey(frequency, timeslot);
            CallSession existing = mActiveSessions.get(key);

            Identifier toTalkgroup = extractToIdentifier(ic);
            Identifier fromRadio = ic != null ? ic.getFromIdentifier() : null;

            if(existing != null && existing.isMatch(frequency, timeslot, toTalkgroup, fromRadio, timestamp))
            {
                // Same call — update duration
                existing.updateActivity(timestamp);

                CallSessionEvent currentEvent = existing.getCurrentEvent();
                if(currentEvent != null)
                {
                    currentEvent.updateEnd(timestamp);
                    notifyEventUpdated(existing, currentEvent);
                }
                return;
            }

            // No matching session or different call — treat as a new grant
            processChannelGrant(apco25Channel, serviceOptions, ic, opcode, timestamp, context);
        }
        catch(Exception e)
        {
            mLog.error("Error processing channel update", e);
        }
    }

    /**
     * Process a Phase 2 control channel grant. Entry point for P2 MAC grants
     * from the control channel. Replaces processP2ChannelGrant().
     */
    public void processP2ChannelGrant(APCO25Channel apco25Channel, ServiceOptions serviceOptions,
                                      IdentifierCollection ic, MacOpcode macOpcode,
                                      long timestamp, String context)
    {
        if(!mRunning || apco25Channel.getDownlinkFrequency() <= 0)
        {
            return;
        }

        try
        {
            DecodeEventType decodeEventType = getEventType(macOpcode, serviceOptions, null);
            boolean isDataGrant = macOpcode.isDataChannelGrant();

            if(apco25Channel.isTDMAChannel())
            {
                if(apco25Channel.getTimeslotCount() == 2)
                {
                    if(macOpcode.isDataChannelGrant())
                    {
                        APCO25Channel phase1Channel = P25TrafficChannelManager.convertPhase2ToPhase1Channel(apco25Channel);
                        processPhase1Grant(phase1Channel, serviceOptions, ic, decodeEventType, isDataGrant, timestamp, context);
                    }
                    else
                    {
                        processP2ChannelGrantInternal(apco25Channel, serviceOptions, ic, decodeEventType,
                                isDataGrant, timestamp, context);
                    }
                }
                else
                {
                    mLog.warn("Cannot process TDMA channel grant - unrecognized timeslot count: " +
                            apco25Channel.getTimeslotCount());
                }
            }
            else
            {
                processPhase1Grant(apco25Channel, serviceOptions, ic, decodeEventType, isDataGrant, timestamp, context);
            }
        }
        catch(Exception e)
        {
            mLog.error("Error processing P2 channel grant", e);
        }
    }

    /**
     * Process a Phase 2 control channel update. Replaces processP2ChannelUpdate().
     */
    public void processP2ChannelUpdate(APCO25Channel apco25Channel, ServiceOptions serviceOptions,
                                       IdentifierCollection ic, MacOpcode macOpcode,
                                       long timestamp, String context)
    {
        if(!mRunning || apco25Channel.getDownlinkFrequency() <= 0)
        {
            return;
        }

        try
        {
            long frequency = apco25Channel.getDownlinkFrequency();

            // If not already processing on this frequency, treat as a new grant
            if(mTrafficChannelManager != null && !mTrafficChannelManager.isTrafficChannelAllocated(frequency))
            {
                processP2ChannelGrant(apco25Channel, serviceOptions, ic, macOpcode, timestamp, context);
            }
            else
            {
                // Already allocated — just update session duration
                int timeslot = apco25Channel.isTDMAChannel() ? apco25Channel.getTimeslot() : P25P1Message.TIMESLOT_1;
                String key = sessionKey(frequency, timeslot);
                CallSession existing = mActiveSessions.get(key);

                if(existing != null)
                {
                    existing.updateActivity(timestamp);
                    CallSessionEvent currentEvent = existing.getCurrentEvent();
                    if(currentEvent != null)
                    {
                        currentEvent.updateEnd(timestamp);
                        notifyEventUpdated(existing, currentEvent);
                    }
                }
            }
        }
        catch(Exception e)
        {
            mLog.error("Error processing P2 channel update", e);
        }
    }

    /**
     * Internal Phase 1 grant processing.
     */
    private void processPhase1Grant(APCO25Channel apco25Channel, ServiceOptions serviceOptions,
                                    IdentifierCollection ic, DecodeEventType decodeEventType,
                                    boolean isDataGrant, long timestamp, String context)
    {
        long frequency = apco25Channel.getDownlinkFrequency();
        int timeslot = P25P1Message.TIMESLOT_1;
        String key = sessionKey(frequency, timeslot);
        CallSession existing = mActiveSessions.get(key);

        Identifier toTalkgroup = extractToIdentifier(ic);
        Identifier fromRadio = ic != null ? ic.getFromIdentifier() : null;

        // Check for same call continuation
        if(existing != null && existing.isMatch(frequency, timeslot, toTalkgroup, fromRadio, timestamp))
        {
            // Same call — check if we need to filter now
            if(mIgnoreEncryptedCalls && serviceOptions != null && serviceOptions.isEncrypted())
            {
                existing.updateActivity(timestamp);
                broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                        "PHASE 1 CHANNEL GRANT " + (serviceOptions != null ? serviceOptions : ""), timestamp);
                return;
            }

            if(mIgnoreUnmonitoredCalls && isUnmonitored(ic))
            {
                existing.updateActivity(timestamp);
                broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                        "PHASE 1 CHANNEL GRANT " + (serviceOptions != null ? serviceOptions : ""), timestamp);
                return;
            }

            // Update session
            existing.updateActivity(timestamp);
            if(serviceOptions != null)
            {
                existing.setServiceOptions(serviceOptions);
            }
            existing.addSeenTalkgroup(toTalkgroup);

            // Update per-talker event
            updateSessionFromGrant(existing, ic, decodeEventType, serviceOptions, apco25Channel, timestamp);

            // Ensure traffic channel is allocated (might have been rejected initially)
            if(mTrafficChannelManager != null && !mTrafficChannelManager.isTrafficChannelAllocated(frequency)
                    && !(mIgnoreDataCalls && isDataGrant))
            {
                Channel trafficChannel = mTrafficChannelManager.allocatePhase1TrafficChannel(apco25Channel, ic, timestamp);
                if(trafficChannel == null)
                {
                    mLog.debug("Max traffic channels exceeded for frequency {}", frequency);
                }
            }

            // Broadcast control event for Events tab
            broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                    "PHASE 1 CHANNEL GRANT " + (serviceOptions != null ? serviceOptions : ""), timestamp);
            return;
        }

        // Different call or no existing session — end existing if different
        if(existing != null)
        {
            transitionToEnding(existing);
        }

        // Apply filters for new grants
        if(mIgnoreDataCalls && isDataGrant)
        {
            broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                    "IGNORED: PHASE 1 DATA CALL " + (serviceOptions != null ? serviceOptions : ""), timestamp);
            return;
        }

        if(mIgnoreEncryptedCalls && serviceOptions != null && serviceOptions.isEncrypted())
        {
            broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                    "IGNORED: ENCRYPTED CALL " + (serviceOptions != null ? serviceOptions : ""), timestamp);
            return;
        }

        if(mIgnoreUnmonitoredCalls && isUnmonitored(ic))
        {
            broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                    "IGNORED: UNMONITORED CALL " + (serviceOptions != null ? serviceOptions : ""), timestamp);
            return;
        }

        // Create new session
        EncryptionKeyIdentifier encryption = extractEncryption(ic);
        CallSession newSession = createSession(frequency, timeslot, toTalkgroup, decodeEventType,
                serviceOptions, apco25Channel, encryption, timestamp);
        mActiveSessions.put(key, newSession);

        // Create per-talker event
        CallSessionEvent sessionEvent = createSessionEvent(newSession, fromRadio, toTalkgroup,
                decodeEventType, ic, apco25Channel, serviceOptions,
                context, frequency, timeslot, timestamp);
        sessionEvent.setChannelSourceType(ChannelSourceType.CONTROL);
        newSession.addEvent(sessionEvent);
        newSession.setState(CallState.ACTIVE);
        newSession.setDetails(context);

        notifySessionCreated(newSession);
        notifyEventAdded(newSession, sessionEvent);

        // Allocate traffic channel
        String details = isDataGrant ? "PHASE 1 DATA CHANNEL GRANT " : "PHASE 1 CHANNEL GRANT ";
        details += (serviceOptions != null ? serviceOptions : "");

        if(mTrafficChannelManager != null)
        {
            Channel trafficChannel = mTrafficChannelManager.allocatePhase1TrafficChannel(apco25Channel, ic, timestamp);
            if(trafficChannel == null)
            {
                details = P25TrafficChannelManager.MAX_TRAFFIC_CHANNELS_EXCEEDED + " - " + details;
            }
        }

        broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot, details, timestamp);
    }

    /**
     * Internal Phase 2 grant processing.
     */
    private void processP2ChannelGrantInternal(APCO25Channel apco25Channel, ServiceOptions serviceOptions,
                                               IdentifierCollection ic, DecodeEventType decodeEventType,
                                               boolean isDataGrant, long timestamp, String context)
    {
        // Apply scramble parameters if available
        if(mTrafficChannelManager != null)
        {
            ScrambleParameters scramble = mTrafficChannelManager.getPhase2ScrambleParameters();
            if(scramble != null && ic instanceof MutableIdentifierCollection mic)
            {
                mic.silentUpdate(ScrambleParameterIdentifier.create(scramble));
            }
        }

        int timeslot = apco25Channel.getTimeslot();
        long frequency = apco25Channel.getDownlinkFrequency();

        if(ic != null)
        {
            ic.setTimeslot(timeslot);
        }

        String key = sessionKey(frequency, timeslot);
        CallSession existing = mActiveSessions.get(key);

        Identifier toTalkgroup = extractToIdentifier(ic);
        Identifier fromRadio = ic != null ? ic.getFromIdentifier() : null;

        // Check for same call continuation
        if(existing != null && existing.isMatch(frequency, timeslot, toTalkgroup, fromRadio, timestamp))
        {
            if(mIgnoreEncryptedCalls && serviceOptions != null && serviceOptions.isEncrypted())
            {
                existing.updateActivity(timestamp);
                broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                        "PHASE 2 CHANNEL GRANT " + (serviceOptions != null ? serviceOptions : ""), timestamp);
                return;
            }

            if(mIgnoreUnmonitoredCalls && isUnmonitored(ic))
            {
                existing.updateActivity(timestamp);
                broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                        "PHASE 2 CHANNEL GRANT " + (serviceOptions != null ? serviceOptions : ""), timestamp);
                return;
            }

            existing.updateActivity(timestamp);
            if(serviceOptions != null)
            {
                existing.setServiceOptions(serviceOptions);
            }
            existing.addSeenTalkgroup(toTalkgroup);

            updateSessionFromGrant(existing, ic, decodeEventType, serviceOptions, apco25Channel, timestamp);

            // Ensure traffic channel is allocated
            if(mTrafficChannelManager != null && !mTrafficChannelManager.isTrafficChannelAllocated(frequency)
                    && !(mIgnoreDataCalls && isDataGrant))
            {
                Channel trafficChannel = mTrafficChannelManager.allocatePhase2TrafficChannel(apco25Channel, ic, timestamp);
                if(trafficChannel == null)
                {
                    mLog.debug("Max traffic channels exceeded for P2 frequency {}", frequency);
                }
            }

            broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                    "PHASE 2 CHANNEL GRANT " + (serviceOptions != null ? serviceOptions : ""), timestamp);
            return;
        }

        // Different call or no existing session
        if(existing != null)
        {
            transitionToEnding(existing);
        }

        // Apply filters
        if(mIgnoreDataCalls && isDataGrant)
        {
            broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                    "PHASE 2 DATA CALL IGNORED: " + (serviceOptions != null ? serviceOptions : ""), timestamp);
            return;
        }

        if(mIgnoreEncryptedCalls && serviceOptions != null && serviceOptions.isEncrypted())
        {
            broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                    "IGNORED: ENCRYPTED CALL " + (serviceOptions != null ? serviceOptions : ""), timestamp);
            return;
        }

        if(mIgnoreUnmonitoredCalls && isUnmonitored(ic))
        {
            broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                    "IGNORED: UNMONITORED CALL " + (serviceOptions != null ? serviceOptions : ""), timestamp);
            return;
        }

        // Create new session
        EncryptionKeyIdentifier encryption = extractEncryption(ic);
        CallSession newSession = createSession(frequency, timeslot, toTalkgroup, decodeEventType,
                serviceOptions, apco25Channel, encryption, timestamp);
        mActiveSessions.put(key, newSession);

        CallSessionEvent sessionEvent = createSessionEvent(newSession, fromRadio, toTalkgroup,
                decodeEventType, ic, apco25Channel, serviceOptions,
                context, frequency, timeslot, timestamp);
        sessionEvent.setChannelSourceType(ChannelSourceType.CONTROL);
        newSession.addEvent(sessionEvent);
        newSession.setState(CallState.ACTIVE);

        notifySessionCreated(newSession);
        notifyEventAdded(newSession, sessionEvent);

        // Allocate traffic channel
        String details = "PHASE 2 CHANNEL GRANT " + (serviceOptions != null ? serviceOptions : "");

        if(mTrafficChannelManager != null)
        {
            Channel trafficChannel = mTrafficChannelManager.allocatePhase2TrafficChannel(apco25Channel, ic, timestamp);
            if(trafficChannel == null)
            {
                details = P25TrafficChannelManager.MAX_TRAFFIC_CHANNELS_EXCEEDED + " - " + details;
            }
        }

        broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot, details, timestamp);
    }

    // ========================================================================
    // Phase 3: Traffic-Side Forwarding
    // ========================================================================

    /**
     * Called from P25TrafficChannelManager when traffic-side messages (HDU, LDU, current user)
     * arrive. Updates the active call session with new identifiers and activity timestamp.
     *
     * @param frequency traffic channel downlink frequency
     * @param timeslot timeslot number
     * @param ic identifier collection from the traffic message
     * @param timestamp of the traffic message
     */
    public void onTrafficChannelUpdate(long frequency, int timeslot, IdentifierCollection ic, long timestamp)
    {
        if(!mRunning)
        {
            return;
        }

        try
        {
            String key = sessionKey(frequency, timeslot);
            CallSession session = mActiveSessions.get(key);

            if(session == null)
            {
                return;
            }

            session.updateActivity(timestamp);

            Identifier fromRadio = ic != null ? ic.getFromIdentifier() : null;
            if(fromRadio != null)
            {
                session.updateRadioAffinity(fromRadio.toString(), timestamp);
            }

            CallSessionEvent currentEvent = session.getCurrentEvent();
            if(currentEvent != null)
            {
                currentEvent.updateEnd(timestamp);
                if(ic != null)
                {
                    currentEvent.setIdentifierCollection(ic);
                }
                notifyEventUpdated(session, currentEvent);
            }
        }
        catch(Exception e)
        {
            mLog.error("Error processing traffic channel update", e);
        }
    }

    /**
     * Called from P25TrafficChannelManager when traffic-side call end messages
     * (TDU, TDULC, call end) arrive. Transitions the session to ENDING state.
     *
     * @param frequency traffic channel downlink frequency
     * @param timeslot timeslot number
     * @param timestamp of the end message
     */
    public void onTrafficChannelEnd(long frequency, int timeslot, long timestamp)
    {
        if(!mRunning)
        {
            return;
        }

        try
        {
            String key = sessionKey(frequency, timeslot);
            CallSession session = mActiveSessions.get(key);

            if(session != null)
            {
                session.updateActivity(timestamp);
                transitionToEnding(session);
            }
        }
        catch(Exception e)
        {
            mLog.error("Error processing traffic channel end", e);
        }
    }

    // ========================================================================
    // Phase 3: DecodeEvent Broadcasting (Events tab)
    // ========================================================================

    /**
     * Creates and broadcasts a P25ChannelGrantEvent for the Events tab.
     * Tagged with ChannelSourceType.CONTROL.
     */
    private void broadcastControlGrantEvent(DecodeEventType eventType, ServiceOptions serviceOptions,
                                            APCO25Channel channel, IdentifierCollection ic,
                                            int timeslot, String details, long timestamp)
    {
        if(mDecodeEventListener == null)
        {
            return;
        }

        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(eventType, timestamp, serviceOptions)
                .channelDescriptor(channel)
                .details(details)
                .identifiers(ic)
                .timeslot(timeslot)
                .build();
        event.setChannelSourceType(ChannelSourceType.CONTROL);

        mDecodeEventListener.receive(event);
    }

    // ========================================================================
    // Phase 3: Shared Logic (moved from P25TrafficChannelManager)
    // ========================================================================

    /**
     * Creates a call event type description for the specified opcode and service options.
     * Moved from P25TrafficChannelManager.
     */
    DecodeEventType getEventType(Opcode opcode, ServiceOptions serviceOptions, DecodeEventType current)
    {
        boolean encrypted = serviceOptions != null && serviceOptions.isEncrypted();

        DecodeEventType type = null;

        if(opcode != null)
        {
            type = switch(opcode)
            {
                case OSP_GROUP_VOICE_CHANNEL_GRANT, OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE,
                     OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE_EXPLICIT ->
                        encrypted ? DecodeEventType.CALL_GROUP_ENCRYPTED : DecodeEventType.CALL_GROUP;
                case OSP_UNIT_TO_UNIT_VOICE_CHANNEL_GRANT, OSP_UNIT_TO_UNIT_VOICE_CHANNEL_GRANT_UPDATE ->
                        encrypted ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED : DecodeEventType.CALL_UNIT_TO_UNIT;
                case OSP_TELEPHONE_INTERCONNECT_VOICE_CHANNEL_GRANT,
                     OSP_TELEPHONE_INTERCONNECT_VOICE_CHANNEL_GRANT_UPDATE ->
                        encrypted ? DecodeEventType.CALL_INTERCONNECT_ENCRYPTED : DecodeEventType.CALL_INTERCONNECT;
                case OSP_SNDCP_DATA_CHANNEL_GRANT, OSP_GROUP_DATA_CHANNEL_GRANT, OSP_INDIVIDUAL_DATA_CHANNEL_GRANT ->
                        encrypted ? DecodeEventType.DATA_CALL_ENCRYPTED : DecodeEventType.DATA_CALL;
                case MOTOROLA_OSP_GROUP_REGROUP_CHANNEL_GRANT, MOTOROLA_OSP_GROUP_REGROUP_CHANNEL_UPDATE ->
                        encrypted ? DecodeEventType.CALL_PATCH_GROUP_ENCRYPTED : DecodeEventType.CALL_PATCH_GROUP;
                default -> type;
            };
        }

        if(type == null)
        {
            type = current;
            if(opcode != null)
            {
                LOGGING_SUPPRESSOR.error(opcode.name(), 2, "Unrecognized opcode for determining decode " +
                        "event type: " + opcode.name());
            }
        }

        if(type == null)
        {
            type = encrypted ? DecodeEventType.CALL_ENCRYPTED : DecodeEventType.CALL;
        }

        return type;
    }

    /**
     * Creates a Phase 2 call event type description for the specified opcode and service options.
     * Moved from P25TrafficChannelManager.
     */
    DecodeEventType getEventType(MacOpcode macOpcode, ServiceOptions serviceOptions, DecodeEventType current)
    {
        boolean encrypted = serviceOptions != null && serviceOptions.isEncrypted();

        DecodeEventType type = null;

        switch(macOpcode)
        {
            case PUSH_TO_TALK:
                type = (current != null) ? current :
                        (encrypted ? DecodeEventType.CALL_GROUP_ENCRYPTED : DecodeEventType.CALL_GROUP);
                break;
            case TDMA_01_GROUP_VOICE_CHANNEL_USER_ABBREVIATED:
            case TDMA_05_GROUP_VOICE_CHANNEL_GRANT_UPDATE_MULTIPLE_IMPLICIT:
            case TDMA_21_GROUP_VOICE_CHANNEL_USER_EXTENDED:
            case TDMA_25_GROUP_VOICE_CHANNEL_GRANT_UPDATE_MULTIPLE_EXPLICIT:
            case PHASE1_40_GROUP_VOICE_CHANNEL_GRANT_IMPLICIT:
            case PHASE1_42_GROUP_VOICE_CHANNEL_GRANT_UPDATE_IMPLICIT:
            case PHASE1_C0_GROUP_VOICE_CHANNEL_GRANT_EXPLICIT:
            case PHASE1_C3_GROUP_VOICE_CHANNEL_GRANT_UPDATE_EXPLICIT:
                type = encrypted ? DecodeEventType.CALL_GROUP_ENCRYPTED : DecodeEventType.CALL_GROUP;
                break;
            case MOTOROLA_80_GROUP_REGROUP_VOICE_CHANNEL_USER_ABBREVIATED:
            case MOTOROLA_83_GROUP_REGROUP_VOICE_CHANNEL_UPDATE:
            case PHASE1_90_GROUP_REGROUP_VOICE_CHANNEL_USER_ABBREVIATED:
            case MOTOROLA_A0_GROUP_REGROUP_VOICE_CHANNEL_USER_EXTENDED:
            case MOTOROLA_A3_GROUP_REGROUP_CHANNEL_GRANT_IMPLICIT:
            case MOTOROLA_A4_GROUP_REGROUP_CHANNEL_GRANT_EXPLICIT:
            case MOTOROLA_A5_GROUP_REGROUP_CHANNEL_GRANT_UPDATE:
            case L3HARRIS_B0_GROUP_REGROUP_EXPLICIT_ENCRYPTION_COMMAND:
                type = encrypted ? DecodeEventType.CALL_PATCH_GROUP_ENCRYPTED : DecodeEventType.CALL_PATCH_GROUP;
                break;
            case TDMA_02_UNIT_TO_UNIT_VOICE_CHANNEL_USER_ABBREVIATED:
            case TDMA_22_UNIT_TO_UNIT_VOICE_CHANNEL_USER_EXTENDED:
            case PHASE1_44_UNIT_TO_UNIT_VOICE_SERVICE_CHANNEL_GRANT_ABBREVIATED:
            case PHASE1_46_UNIT_TO_UNIT_VOICE_CHANNEL_GRANT_UPDATE_ABBREVIATED:
            case PHASE1_48_TELEPHONE_INTERCONNECT_VOICE_CHANNEL_GRANT_IMPLICIT:
            case PHASE1_C4_UNIT_TO_UNIT_VOICE_SERVICE_CHANNEL_GRANT_EXTENDED_VCH:
            case PHASE1_C6_UNIT_TO_UNIT_VOICE_CHANNEL_GRANT_UPDATE_EXTENDED_VCH:
            case PHASE1_CF_UNIT_TO_UNIT_VOICE_SERVICE_CHANNEL_GRANT_EXTENDED_LCCH:
                type = encrypted ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED : DecodeEventType.CALL_UNIT_TO_UNIT;
                break;
            case TDMA_03_TELEPHONE_INTERCONNECT_VOICE_CHANNEL_USER:
            case PHASE1_C8_TELEPHONE_INTERCONNECT_VOICE_CHANNEL_GRANT_EXPLICIT:
                type = encrypted ? DecodeEventType.CALL_INTERCONNECT_ENCRYPTED : DecodeEventType.CALL_INTERCONNECT;
                break;
            case PHASE1_54_SNDCP_DATA_CHANNEL_GRANT:
            case L3HARRIS_A0_PRIVATE_DATA_CHANNEL_GRANT:
            case L3HARRIS_AC_UNIT_TO_UNIT_DATA_CHANNEL_GRANT:
                type = encrypted ? DecodeEventType.DATA_CALL_ENCRYPTED : DecodeEventType.DATA_CALL;
                break;
        }

        if(type == null)
        {
            LOGGING_SUPPRESSOR.error(macOpcode.name(), 2, "Unrecognized MAC opcode for determining " +
                    "decode event type: " + macOpcode.name());
            type = current;
        }

        if(type == null)
        {
            type = DecodeEventType.CALL;
        }

        return type;
    }

    /**
     * Checks if the identifier collection represents an unmonitored call based on alias configuration.
     * Moved from P25TrafficChannelManager.
     */
    boolean isUnmonitored(IdentifierCollection ic)
    {
        if(mAliasList == null || ic == null)
        {
            return false;
        }

        Identifier toIdentifier = ic.getToIdentifier();

        if(toIdentifier == null)
        {
            return true;
        }

        List<Alias> aliases = mAliasList.getAliases(toIdentifier);

        if(aliases.isEmpty())
        {
            return true;
        }

        for(Alias alias : aliases)
        {
            if(alias.getPlaybackPriority() != Priority.DO_NOT_MONITOR)
            {
                return false;
            }

            if(alias.isRecordable() || alias.isStreamable())
            {
                return false;
            }
        }

        return true;
    }

    // ========================================================================
    // Passive Observer (deprecated — kept for backward compatibility during transition)
    // ========================================================================

    /**
     * @deprecated Phase 3 replaces passive observation with direct grant processing.
     * This method is kept temporarily for backward compatibility but the call from
     * P25TrafficChannelManager.broadcast() should be removed.
     */
    @Deprecated
    public void onDecodeEvent(DecodeEvent decodeEvent, long timestamp)
    {
        // No-op in Phase 3 — call manager now receives events directly
    }

    // ========================================================================
    // Session Management (preserved from Phase 2)
    // ========================================================================

    /**
     * Updates an existing session's per-talker event from a control channel grant.
     */
    private void updateSessionFromGrant(CallSession session, IdentifierCollection ic,
                                        DecodeEventType eventType, ServiceOptions serviceOptions,
                                        IChannelDescriptor channelDescriptor, long timestamp)
    {
        Identifier fromRadio = ic != null ? ic.getFromIdentifier() : null;

        if(fromRadio != null)
        {
            session.updateRadioAffinity(fromRadio.toString(), timestamp);
        }

        // Update event type if it upgrades
        if(eventType != null)
        {
            boolean upgrade = false;
            if(isPatchEventType(eventType) && !isPatchEventType(session.getEventType()))
            {
                upgrade = true;
            }
            if(isEncryptedEventType(eventType) && !isEncryptedEventType(session.getEventType()))
            {
                upgrade = true;
            }
            if(upgrade)
            {
                session.setEventType(eventType);
                CallSessionEvent currentEvent = session.getCurrentEvent();
                if(currentEvent != null)
                {
                    currentEvent.setEventType(eventType);
                }
            }
        }

        // Check if same talker or different
        if(session.isSameTalker(fromRadio))
        {
            CallSessionEvent currentEvent = session.getCurrentEvent();
            if(currentEvent != null)
            {
                currentEvent.updateEnd(timestamp);
                if(ic != null)
                {
                    currentEvent.setIdentifierCollection(ic);
                }
                notifyEventUpdated(session, currentEvent);
            }
        }
        else
        {
            // Different talker — create new per-talker event
            CallSessionEvent previousEvent = session.getCurrentEvent();
            if(previousEvent != null)
            {
                previousEvent.updateEnd(timestamp);
            }

            Identifier toTalkgroup = extractToIdentifier(ic);
            CallSessionEvent newEvent = createSessionEvent(session, fromRadio, toTalkgroup,
                    eventType, ic, channelDescriptor, serviceOptions,
                    null, session.getFrequency(), session.getTimeslot(), timestamp);
            newEvent.setChannelSourceType(ChannelSourceType.CONTROL);
            session.addEvent(newEvent);
            notifyEventAdded(session, newEvent);
        }
    }

    /**
     * Creates a new CallSession.
     */
    private CallSession createSession(long frequency, int timeslot, Identifier talkgroup,
                                      DecodeEventType eventType, ServiceOptions serviceOptions,
                                      IChannelDescriptor channelDescriptor,
                                      EncryptionKeyIdentifier encryption, long timestamp)
    {
        long sessionId = mSessionIdCounter.getAndIncrement();
        CallSession session = new CallSession(sessionId, frequency, timeslot, talkgroup,
                eventType, serviceOptions, channelDescriptor, timestamp);

        if(encryption != null)
        {
            session.setEncryption(encryption);
        }

        if(mPatchGroupManager != null && talkgroup instanceof PatchGroupIdentifier pgi)
        {
            session.enrichPatchGroup(pgi);
        }

        return session;
    }

    /**
     * Creates a new CallSessionEvent (per-talker row).
     */
    private CallSessionEvent createSessionEvent(CallSession session, Identifier fromRadio,
                                                Identifier toTalkgroup, DecodeEventType eventType,
                                                IdentifierCollection identifiers,
                                                IChannelDescriptor channelDescriptor,
                                                ServiceOptions serviceOptions, String details,
                                                long frequency, int timeslot, long timestamp)
    {
        return new CallSessionEvent(session.getSessionId(), timestamp, eventType,
                fromRadio, toTalkgroup, identifiers, channelDescriptor, serviceOptions,
                details, frequency, timeslot);
    }

    /**
     * Extracts the TO identifier from an IdentifierCollection.
     */
    private Identifier extractToIdentifier(IdentifierCollection identifiers)
    {
        if(identifiers == null)
        {
            return null;
        }

        Identifier to = identifiers.getIdentifier(IdentifierClass.USER, Form.PATCH_GROUP, Role.TO);
        if(to != null)
        {
            return to;
        }

        to = identifiers.getIdentifier(IdentifierClass.USER, Form.TALKGROUP, Role.TO);
        if(to != null)
        {
            return to;
        }

        return identifiers.getIdentifier(IdentifierClass.USER, Form.RADIO, Role.TO);
    }

    /**
     * Extracts the encryption key identifier from an IdentifierCollection.
     */
    private EncryptionKeyIdentifier extractEncryption(IdentifierCollection identifiers)
    {
        if(identifiers == null)
        {
            return null;
        }

        Identifier id = identifiers.getIdentifier(IdentifierClass.USER, Form.ENCRYPTION_KEY, Role.ANY);
        return (id instanceof EncryptionKeyIdentifier eki) ? eki : null;
    }

    private boolean isPatchEventType(DecodeEventType eventType)
    {
        if(eventType == null)
        {
            return false;
        }
        return eventType.name().contains("PATCH_GROUP");
    }

    private boolean isEncryptedEventType(DecodeEventType eventType)
    {
        if(eventType == null)
        {
            return false;
        }
        String label = eventType.getLabel();
        return label != null && label.contains("Encrypted");
    }

    // ========================================================================
    // Session lifecycle transitions
    // ========================================================================

    private void transitionToEnding(CallSession session)
    {
        String key = sessionKey(session.getFrequency(), session.getTimeslot());
        mActiveSessions.remove(key);
        session.setState(CallState.ENDING);
        mEndingSessions.put(key + ":" + session.getSessionId(), session);
    }

    private void finalizeSession(CallSession session)
    {
        if(session.getState() == CallState.COMPLETE)
        {
            return;
        }

        session.setState(CallState.COMPLETE);

        CallSessionEvent lastEvent = session.getCurrentEvent();
        if(lastEvent != null)
        {
            lastEvent.updateEnd(session.getCallEnd());
        }

        mLog.trace("Session complete: {} (events={}, duration={}ms)",
                session.getSessionId(), session.getEventCount(), session.getDuration());

        notifySessionComplete(session);
    }

    private void cleanupEndingSessions()
    {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, CallSession>> iterator = mEndingSessions.entrySet().iterator();

        while(iterator.hasNext())
        {
            Map.Entry<String, CallSession> entry = iterator.next();
            CallSession session = entry.getValue();
            long gap = now - session.getLastActivityTimestamp();

            if(gap > mGapToleranceMs)
            {
                iterator.remove();
                finalizeSession(session);
            }
        }
    }

    // ========================================================================
    // Session key generation
    // ========================================================================

    private String sessionKey(long frequency, int timeslot)
    {
        return frequency + ":" + timeslot;
    }

    // ========================================================================
    // Patch group support
    // ========================================================================

    public void setPatchGroupManager(PatchGroupManager patchGroupManager)
    {
        mPatchGroupManager = patchGroupManager;
    }

    public void onPatchGroupUpdate(PatchGroupIdentifier patchGroup)
    {
        if(patchGroup == null)
        {
            return;
        }

        for(CallSession session : mActiveSessions.values())
        {
            if(session.hasSeenTalkgroup(patchGroup.getValue().getPatchGroup().getValue()))
            {
                session.enrichPatchGroup(patchGroup);
            }
        }

        for(CallSession session : mEndingSessions.values())
        {
            if(session.hasSeenTalkgroup(patchGroup.getValue().getPatchGroup().getValue()))
            {
                session.enrichPatchGroup(patchGroup);
            }
        }
    }

    // ========================================================================
    // Start / stop
    // ========================================================================

    public void start()
    {
        if(mRunning)
        {
            return;
        }

        mRunning = true;
        mTimerService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "P25CallSessionManager-Cleanup");
            t.setDaemon(true);
            return t;
        });
        mTimerService.scheduleAtFixedRate(this::cleanupEndingSessions, 500, 500, TimeUnit.MILLISECONDS);
        mLog.info("P25CallSessionManager started (gap tolerance: {}ms)", mGapToleranceMs);
    }

    public void stop()
    {
        mRunning = false;

        if(mTimerService != null)
        {
            mTimerService.shutdown();
            try
            {
                mTimerService.awaitTermination(1, TimeUnit.SECONDS);
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            mTimerService = null;
        }

        for(CallSession session : mActiveSessions.values())
        {
            finalizeSession(session);
        }
        mActiveSessions.clear();

        for(CallSession session : mEndingSessions.values())
        {
            finalizeSession(session);
        }
        mEndingSessions.clear();

        mLog.info("P25CallSessionManager stopped");
    }

    // ========================================================================
    // Gap tolerance configuration
    // ========================================================================

    public long getGapToleranceMs()
    {
        return mGapToleranceMs;
    }

    public void setGapToleranceMs(long gapToleranceMs)
    {
        mGapToleranceMs = gapToleranceMs;
    }

    // ========================================================================
    // Listener management
    // ========================================================================

    public void addListener(CallSessionListener listener)
    {
        if(listener != null && !mListeners.contains(listener))
        {
            mListeners.add(listener);
        }
    }

    public void removeListener(CallSessionListener listener)
    {
        mListeners.remove(listener);
    }

    private void notifySessionCreated(CallSession session)
    {
        for(CallSessionListener listener : mListeners)
        {
            try
            {
                listener.onSessionCreated(session);
            }
            catch(Exception e)
            {
                mLog.error("Error notifying listener of session created", e);
            }
        }
    }

    private void notifyEventAdded(CallSession session, CallSessionEvent event)
    {
        for(CallSessionListener listener : mListeners)
        {
            try
            {
                listener.onSessionEventAdded(session, event);
            }
            catch(Exception e)
            {
                mLog.error("Error notifying listener of event added", e);
            }
        }
    }

    private void notifyEventUpdated(CallSession session, CallSessionEvent event)
    {
        for(CallSessionListener listener : mListeners)
        {
            try
            {
                listener.onSessionEventUpdated(session, event);
            }
            catch(Exception e)
            {
                mLog.error("Error notifying listener of event updated", e);
            }
        }
    }

    private void notifySessionComplete(CallSession session)
    {
        for(CallSessionListener listener : mListeners)
        {
            try
            {
                listener.onSessionComplete(session);
            }
            catch(Exception e)
            {
                mLog.error("Error notifying listener of session complete", e);
            }
        }
    }
}
