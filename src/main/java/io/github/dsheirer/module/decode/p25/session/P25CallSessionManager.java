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

    /** Cached P25ChannelGrantEvent objects for the Events tab, keyed by "frequency:timeslot".
     *  Reusing the SAME object reference is critical — ClearableHistoryModel.add() uses
     *  contains() to detect whether to update an existing row or add a new one. */
    private final Map<String, P25ChannelGrantEvent> mActiveControlEvents = new ConcurrentHashMap<>();

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

            // No active session — try to reactivate from ENDING (PTT release within same call)
            if(existing == null)
            {
                CallSession reactivated = reactivateFromEnding(frequency, timeslot, toTalkgroup, fromRadio, timestamp);
                if(reactivated != null)
                {
                    CallSessionEvent currentEvent = reactivated.getCurrentEvent();
                    if(currentEvent != null)
                    {
                        currentEvent.updateEnd(timestamp);
                        notifyEventUpdated(reactivated, currentEvent);
                    }
                    return;
                }
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

        // Upgrade event type if PatchGroupManager resolved the TO identifier to a PatchGroupIdentifier
        if(toTalkgroup instanceof PatchGroupIdentifier)
        {
            boolean encrypted = serviceOptions != null && serviceOptions.isEncrypted();
            decodeEventType = encrypted ? DecodeEventType.CALL_PATCH_GROUP_ENCRYPTED : DecodeEventType.CALL_PATCH_GROUP;
        }

        // Cross-frequency patch group matching: if no session exists on this frequency but we
        // have an active session for the same patch group on another frequency, update that
        // session and skip allocating a duplicate traffic channel
        if(existing == null)
        {
            CallSession crossFreqSession = findCrossFrequencySession(toTalkgroup, fromRadio, timestamp);
            if(crossFreqSession != null)
            {
                crossFreqSession.updateActivity(timestamp);
                crossFreqSession.addSeenTalkgroup(toTalkgroup);

                // Broadcast event for Events tab but don't allocate another traffic channel
                broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                        "PATCH MEMBER - PHASE 1 CHANNEL GRANT " + (serviceOptions != null ? serviceOptions : ""),
                        timestamp);
                return;
            }
        }

        // Check for same call continuation (active session)
        if(existing != null && existing.isMatch(frequency, timeslot, toTalkgroup, fromRadio, timestamp))
        {
            // Always update session state so event type (including encryption) is preserved
            existing.updateActivity(timestamp);
            if(serviceOptions != null)
            {
                existing.setServiceOptions(serviceOptions);
            }
            existing.addSeenTalkgroup(toTalkgroup);
            updateSessionFromGrant(existing, ic, decodeEventType, serviceOptions, apco25Channel, timestamp);

            // Use session's (possibly upgraded) event type for broadcasting
            DecodeEventType broadcastType = existing.getEventType() != null ? existing.getEventType() : decodeEventType;

            // Check BOTH ServiceOptions AND session's encryption state (may have been upgraded
            // by traffic channel detection via onTrafficChannelUpdate).
            boolean sessionEncrypted = (serviceOptions != null && serviceOptions.isEncrypted())
                    || isEncryptedEventType(broadcastType);

            // Determine if this is an ignored call — skip traffic channel but still update session
            boolean ignored = false;
            String ignoredPrefix = "";
            if(mIgnoreEncryptedCalls && sessionEncrypted)
            {
                ignored = true;
                ignoredPrefix = "IGNORED: ENCRYPTED CALL ";
            }
            else if(mIgnoreUnmonitoredCalls && isUnmonitored(ic))
            {
                ignored = true;
                ignoredPrefix = "IGNORED: UNMONITORED CALL ";
            }

            if(!ignored)
            {
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
            }
            else
            {
                // Update session event details so Calls tab can filter by "IGNORED"
                CallSessionEvent currentEvt = existing.getCurrentEvent();
                if(currentEvt != null)
                {
                    String evtDetails = currentEvt.getDetails();
                    if(evtDetails == null || !evtDetails.contains("IGNORED"))
                    {
                        currentEvt.setDetails(ignoredPrefix.trim());
                    }
                }
                existing.setDetails(ignoredPrefix.trim());
            }

            String details = ignored ? ignoredPrefix : "PHASE 1 CHANNEL GRANT ";
            details += (serviceOptions != null ? serviceOptions : "");
            broadcastControlGrantEvent(broadcastType, serviceOptions, apco25Channel, ic, 0, details, timestamp);
            return;
        }

        // No active session — try to reactivate from ENDING (PTT release within same call)
        if(existing == null)
        {
            CallSession reactivated = reactivateFromEnding(frequency, timeslot, toTalkgroup, fromRadio, timestamp);
            if(reactivated != null)
            {
                reactivated.addSeenTalkgroup(toTalkgroup);
                updateSessionFromGrant(reactivated, ic, decodeEventType, serviceOptions, apco25Channel, timestamp);

                // Ensure traffic channel is allocated
                if(mTrafficChannelManager != null && !mTrafficChannelManager.isTrafficChannelAllocated(frequency)
                        && !(mIgnoreDataCalls && isDataGrant))
                {
                    mTrafficChannelManager.allocatePhase1TrafficChannel(apco25Channel, ic, timestamp);
                }

                broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, 0,
                        "PHASE 1 CHANNEL GRANT " + (serviceOptions != null ? serviceOptions : ""), timestamp);
                return;
            }
        }

        // Different TG on the same freq:ts — end old session, start a new one
        if(existing != null)
        {
            transitionToEnding(existing, true);
        }

        // Determine ignored reason (if any) — but ALWAYS create a session so Calls tab
        // gets entries and event type state is preserved across grants (e.g., encryption).
        // Traffic channels are NOT allocated for ignored calls — they are control-channel-only.
        String ignoredReason = null;
        if(mIgnoreDataCalls && isDataGrant)
        {
            ignoredReason = "IGNORED: PHASE 1 DATA CALL";
        }
        else if(mIgnoreEncryptedCalls && serviceOptions != null && serviceOptions.isEncrypted())
        {
            ignoredReason = "IGNORED: ENCRYPTED CALL";
        }
        else if(mIgnoreUnmonitoredCalls && isUnmonitored(ic))
        {
            ignoredReason = "IGNORED: UNMONITORED CALL";
        }

        // Create new session for ALL calls (even ignored ones)
        EncryptionKeyIdentifier encryption = extractEncryption(ic);
        CallSession newSession = createSession(frequency, timeslot, toTalkgroup, decodeEventType,
                serviceOptions, apco25Channel, encryption, timestamp);
        mActiveSessions.put(key, newSession);

        // Create per-talker event (use timeslot 0 for display — Phase 1 has no meaningful timeslot)
        // Use ignoredReason as details if set, so CallSessionModel can detect "IGNORED" for filtering
        String sessionDetails = ignoredReason != null ? ignoredReason : context;
        CallSessionEvent sessionEvent = createSessionEvent(newSession, fromRadio, toTalkgroup,
                decodeEventType, ic, apco25Channel, serviceOptions,
                sessionDetails, frequency, 0, timestamp);
        sessionEvent.setChannelSourceType(ChannelSourceType.CONTROL);
        newSession.addEvent(sessionEvent);
        newSession.setState(CallState.ACTIVE);
        newSession.setDetails(sessionDetails);

        notifySessionCreated(newSession);
        notifyEventAdded(newSession, sessionEvent);

        // Build details string and allocate traffic channel only for non-ignored calls
        String details;
        if(ignoredReason != null)
        {
            // Ignored call — control-channel-only, no traffic channel allocation
            details = ignoredReason + " " + (serviceOptions != null ? serviceOptions : "");
        }
        else
        {
            // Normal call — allocate traffic channel
            details = isDataGrant ? "PHASE 1 DATA CHANNEL GRANT " : "PHASE 1 CHANNEL GRANT ";
            details += (serviceOptions != null ? serviceOptions : "");

            if(mTrafficChannelManager != null)
            {
                Channel trafficChannel = mTrafficChannelManager.allocatePhase1TrafficChannel(apco25Channel, ic, timestamp);
                if(trafficChannel == null)
                {
                    details = P25TrafficChannelManager.MAX_TRAFFIC_CHANNELS_EXCEEDED + " - " + details;
                }
            }
        }

        broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, 0, details, timestamp);
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

        // Upgrade event type if PatchGroupManager resolved the TO identifier to a PatchGroupIdentifier
        if(toTalkgroup instanceof PatchGroupIdentifier)
        {
            boolean encrypted = serviceOptions != null && serviceOptions.isEncrypted();
            decodeEventType = encrypted ? DecodeEventType.CALL_PATCH_GROUP_ENCRYPTED : DecodeEventType.CALL_PATCH_GROUP;
        }

        // Cross-frequency patch group matching for P2 channels
        if(existing == null)
        {
            CallSession crossFreqSession = findCrossFrequencySession(toTalkgroup, fromRadio, timestamp);
            if(crossFreqSession != null)
            {
                crossFreqSession.updateActivity(timestamp);
                crossFreqSession.addSeenTalkgroup(toTalkgroup);

                broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                        "PATCH MEMBER - PHASE 2 CHANNEL GRANT " + (serviceOptions != null ? serviceOptions : ""),
                        timestamp);
                return;
            }
        }

        // Check for same call continuation
        if(existing != null && existing.isMatch(frequency, timeslot, toTalkgroup, fromRadio, timestamp))
        {
            // Always update session state so event type (including encryption) is preserved
            existing.updateActivity(timestamp);
            if(serviceOptions != null)
            {
                existing.setServiceOptions(serviceOptions);
            }
            existing.addSeenTalkgroup(toTalkgroup);
            updateSessionFromGrant(existing, ic, decodeEventType, serviceOptions, apco25Channel, timestamp);

            DecodeEventType broadcastType = existing.getEventType() != null ? existing.getEventType() : decodeEventType;
            boolean sessionEncrypted = (serviceOptions != null && serviceOptions.isEncrypted())
                    || isEncryptedEventType(broadcastType);

            boolean ignored = false;
            String ignoredPrefix = "";
            if(mIgnoreEncryptedCalls && sessionEncrypted)
            {
                ignored = true;
                ignoredPrefix = "IGNORED: ENCRYPTED CALL ";
            }
            else if(mIgnoreUnmonitoredCalls && isUnmonitored(ic))
            {
                ignored = true;
                ignoredPrefix = "IGNORED: UNMONITORED CALL ";
            }

            if(!ignored)
            {
                if(mTrafficChannelManager != null && !mTrafficChannelManager.isTrafficChannelAllocated(frequency)
                        && !(mIgnoreDataCalls && isDataGrant))
                {
                    Channel trafficChannel = mTrafficChannelManager.allocatePhase2TrafficChannel(apco25Channel, ic, timestamp);
                    if(trafficChannel == null)
                    {
                        mLog.debug("Max traffic channels exceeded for P2 frequency {}", frequency);
                    }
                }
            }
            else
            {
                // Update session event details so Calls tab can filter by "IGNORED"
                CallSessionEvent currentEvt = existing.getCurrentEvent();
                if(currentEvt != null)
                {
                    String evtDetails = currentEvt.getDetails();
                    if(evtDetails == null || !evtDetails.contains("IGNORED"))
                    {
                        currentEvt.setDetails(ignoredPrefix.trim());
                    }
                }
                existing.setDetails(ignoredPrefix.trim());
            }

            String details = ignored ? ignoredPrefix : "PHASE 2 CHANNEL GRANT ";
            details += (serviceOptions != null ? serviceOptions : "");
            broadcastControlGrantEvent(broadcastType, serviceOptions, apco25Channel, ic, timeslot, details, timestamp);
            return;
        }

        // No active session — try to reactivate from ENDING (PTT release within same call)
        if(existing == null)
        {
            CallSession reactivated = reactivateFromEnding(frequency, timeslot, toTalkgroup, fromRadio, timestamp);
            if(reactivated != null)
            {
                reactivated.addSeenTalkgroup(toTalkgroup);
                updateSessionFromGrant(reactivated, ic, decodeEventType, serviceOptions, apco25Channel, timestamp);

                // Ensure traffic channel is allocated
                if(mTrafficChannelManager != null && !mTrafficChannelManager.isTrafficChannelAllocated(frequency)
                        && !(mIgnoreDataCalls && isDataGrant))
                {
                    mTrafficChannelManager.allocatePhase2TrafficChannel(apco25Channel, ic, timestamp);
                }

                broadcastControlGrantEvent(decodeEventType, serviceOptions, apco25Channel, ic, timeslot,
                        "PHASE 2 CHANNEL GRANT " + (serviceOptions != null ? serviceOptions : ""), timestamp);
                return;
            }
        }

        // Different TG on the same freq:ts — end old session, start a new one
        if(existing != null)
        {
            transitionToEnding(existing, true);
        }

        // Determine ignored reason — but ALWAYS create a session so Calls tab gets entries
        // and event type state is preserved across grants. No traffic channel for ignored calls.
        String ignoredReason = null;
        if(mIgnoreDataCalls && isDataGrant)
        {
            ignoredReason = "IGNORED: PHASE 2 DATA CALL";
        }
        else if(mIgnoreEncryptedCalls && serviceOptions != null && serviceOptions.isEncrypted())
        {
            ignoredReason = "IGNORED: ENCRYPTED CALL";
        }
        else if(mIgnoreUnmonitoredCalls && isUnmonitored(ic))
        {
            ignoredReason = "IGNORED: UNMONITORED CALL";
        }

        // Create new session for ALL calls (even ignored ones)
        EncryptionKeyIdentifier encryption = extractEncryption(ic);
        CallSession newSession = createSession(frequency, timeslot, toTalkgroup, decodeEventType,
                serviceOptions, apco25Channel, encryption, timestamp);
        mActiveSessions.put(key, newSession);

        // Use ignoredReason as details if set, so CallSessionModel can detect "IGNORED" for filtering
        String sessionDetails = ignoredReason != null ? ignoredReason : context;
        CallSessionEvent sessionEvent = createSessionEvent(newSession, fromRadio, toTalkgroup,
                decodeEventType, ic, apco25Channel, serviceOptions,
                sessionDetails, frequency, timeslot, timestamp);
        sessionEvent.setChannelSourceType(ChannelSourceType.CONTROL);
        newSession.addEvent(sessionEvent);
        newSession.setState(CallState.ACTIVE);

        notifySessionCreated(newSession);
        notifyEventAdded(newSession, sessionEvent);

        // Build details and allocate traffic channel only for non-ignored calls
        String details;
        if(ignoredReason != null)
        {
            details = ignoredReason + " " + (serviceOptions != null ? serviceOptions : "");
        }
        else
        {
            details = "PHASE 2 CHANNEL GRANT " + (serviceOptions != null ? serviceOptions : "");

            if(mTrafficChannelManager != null)
            {
                Channel trafficChannel = mTrafficChannelManager.allocatePhase2TrafficChannel(apco25Channel, ic, timestamp);
                if(trafficChannel == null)
                {
                    details = P25TrafficChannelManager.MAX_TRAFFIC_CHANNELS_EXCEEDED + " - " + details;
                }
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

            // Check for encryption from traffic channel (HDU/LDU messages carry EncryptionKeyIdentifier).
            // This is the primary encryption detection path — control channel TSBK grants often do NOT
            // include encryption info; it's only available once the traffic channel starts decoding.
            EncryptionKeyIdentifier eki = extractEncryption(ic);
            if(eki != null && eki.isEncrypted())
            {
                session.setEncryption(eki);

                // Upgrade the session and event type to encrypted variant
                if(!isEncryptedEventType(session.getEventType()))
                {
                    DecodeEventType upgradedType = upgradeToEncrypted(session.getEventType());
                    session.setEventType(upgradedType);

                    CallSessionEvent currentEvent = session.getCurrentEvent();
                    if(currentEvent != null)
                    {
                        currentEvent.setEventType(upgradedType);
                    }

                    // Also upgrade the cached control event in the Events tab so it shows
                    // "Encrypted Group Call" instead of "Group Call". Without this, the
                    // control-sourced event row never gets its type upgraded because the
                    // control channel grant messages don't carry encryption info.
                    // Note: Phase 1 control events are cached with timeslot 0, but traffic
                    // channels report timeslot 1. Try both keys to find the cached event.
                    String eventKey = frequency + ":" + timeslot;
                    P25ChannelGrantEvent cachedEvent = mActiveControlEvents.get(eventKey);
                    if(cachedEvent == null && timeslot != 0)
                    {
                        eventKey = frequency + ":0";
                        cachedEvent = mActiveControlEvents.get(eventKey);
                    }
                    if(cachedEvent != null)
                    {
                        cachedEvent.setDecodeEventType(upgradedType);
                        // Update details to reflect encryption
                        String existingDetails = cachedEvent.getDetails();
                        if(existingDetails != null && !existingDetails.contains("ENCRYPTED"))
                        {
                            if(mIgnoreEncryptedCalls)
                            {
                                cachedEvent.setDetails("IGNORED: ENCRYPTED CALL " + existingDetails);
                            }
                        }
                        if(mDecodeEventListener != null)
                        {
                            mDecodeEventListener.receive(cachedEvent);
                        }
                    }

                    // Also update session details for Calls tab filtering
                    if(mIgnoreEncryptedCalls)
                    {
                        if(currentEvent != null)
                        {
                            String details = currentEvent.getDetails();
                            if(details == null || !details.contains("IGNORED"))
                            {
                                currentEvent.setDetails("IGNORED: ENCRYPTED CALL");
                            }
                        }
                        session.setDetails("IGNORED: ENCRYPTED CALL");
                    }

                    mLog.debug("Session {} upgraded to encrypted via traffic channel: {}",
                            session.getSessionId(), upgradedType);
                }
            }

            CallSessionEvent currentEvent = session.getCurrentEvent();
            if(currentEvent != null)
            {
                currentEvent.updateEnd(timestamp);
                // Mark as TRAFFIC source since this update came from a traffic channel
                currentEvent.setChannelSourceType(ChannelSourceType.TRAFFIC);

                // Update FROM radio if the traffic channel provides one
                if(fromRadio != null)
                {
                    currentEvent.setFromRadio(fromRadio);
                }

                // Only update the identifier collection if it has richer info
                // (has a FROM radio) to avoid overwriting an identified FROM with null
                if(ic != null)
                {
                    if(fromRadio != null || currentEvent.getFromRadio() == null)
                    {
                        currentEvent.setIdentifierCollection(ic);
                    }
                }
                notifyEventUpdated(session, currentEvent);
            }

            // Phase 4: Always re-broadcast the cached control event to the Events tab.
            // Since TCM no longer broadcasts traffic-side events, CSM must update the
            // Events tab with duration, identifiers, and any other traffic-channel info.
            broadcastTrafficUpdate(frequency, timeslot, ic, timestamp);
        }
        catch(Exception e)
        {
            mLog.error("Error processing traffic channel update", e);
        }
    }

    /**
     * Called from P25TrafficChannelManager when traffic-side call end messages
     * (TDU, TDULC, call end) arrive.
     *
     * Transitions the session to ENDING state, removing it from mActiveSessions. This ensures
     * that when a NEW call starts on the same frequency/talkgroup, it creates a new session
     * instead of extending the old one's duration.
     *
     * For PTT releases WITHIN a group call (where the control channel continues sending grant
     * updates), processChannelGrant/Update will find the session in mEndingSessions and
     * reactivate it via reactivateFromEnding(). This correctly handles the P25 pattern of:
     * TDU → brief gap → next grant update → same call continues.
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
                // TDU within the same call — keep cached Events tab event so the row
                // survives reactivation with correct duration
                transitionToEnding(session, false);
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
     * Creates or updates a cached P25ChannelGrantEvent for the Events tab and broadcasts it.
     * Tagged with ChannelSourceType.CONTROL.
     *
     * CRITICAL: ClearableHistoryModel.add() uses contains() (object identity) to decide
     * whether to update an existing row or add a new one. We MUST reuse the same event object
     * for the same call to get in-place updates with duration, encryption upgrades, etc.
     * Creating a new object each time would create a new row per control channel message.
     */
    private void broadcastControlGrantEvent(DecodeEventType eventType, ServiceOptions serviceOptions,
                                            APCO25Channel channel, IdentifierCollection ic,
                                            int timeslot, String details, long timestamp)
    {
        if(mDecodeEventListener == null)
        {
            return;
        }

        String eventKey = channel.getDownlinkFrequency() + ":" + timeslot;
        P25ChannelGrantEvent existing = mActiveControlEvents.get(eventKey);

        if(existing != null)
        {
            // Update the existing event object in-place — same reference so model updates the row
            existing.setDecodeEventType(eventType);
            existing.setDetails(details);
            existing.setIdentifierCollection(ic);
            existing.setDuration(timestamp - existing.getTimeStart());
            if(serviceOptions != null)
            {
                existing.setServiceOptions(serviceOptions);
            }
            existing.setChannelDescriptor(channel);
            mDecodeEventListener.receive(existing);
        }
        else
        {
            // Create new event for this frequency/timeslot
            P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(eventType, timestamp, serviceOptions)
                    .channelDescriptor(channel)
                    .details(details)
                    .identifiers(ic)
                    .timeslot(timeslot)
                    .build();
            event.setChannelSourceType(ChannelSourceType.CONTROL);
            mActiveControlEvents.put(eventKey, event);
            mDecodeEventListener.receive(event);
        }
    }

    // ========================================================================
    // Phase 3: Shared Logic (moved from P25TrafficChannelManager)
    // ========================================================================

    /**
     * Phase 4: Re-broadcasts the cached control event to the Events tab with updated info
     * from traffic channel messages (duration, identifiers, encryption).
     *
     * Since TCM no longer broadcasts traffic-side events directly, this method ensures the
     * Events tab stays current with traffic channel activity. The cached control event
     * (created by broadcastControlGrantEvent during the initial grant) is updated in-place
     * and re-broadcast, preserving ClearableHistoryModel object identity for in-row updates.
     */
    private void broadcastTrafficUpdate(long frequency, int timeslot, IdentifierCollection ic, long timestamp)
    {
        if(mDecodeEventListener == null)
        {
            return;
        }

        // Try the exact key first, then fallback for Phase 1 (timeslot mismatch: control=0, traffic=1)
        String eventKey = frequency + ":" + timeslot;
        P25ChannelGrantEvent existing = mActiveControlEvents.get(eventKey);
        if(existing == null && timeslot != 0)
        {
            eventKey = frequency + ":0";
            existing = mActiveControlEvents.get(eventKey);
        }

        if(existing != null)
        {
            existing.setDuration(timestamp - existing.getTimeStart());
            if(ic != null)
            {
                existing.setIdentifierCollection(ic);
            }
            // Mark as TRAFFIC source since this update came from a traffic channel
            existing.setChannelSourceType(ChannelSourceType.TRAFFIC);
            mDecodeEventListener.receive(existing);
        }
    }


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
     * Updates an existing session's event from a control channel grant.
     *
     * This method consolidates all activity for a call into ONE event per session,
     * matching how the P25TrafficChannelManager consolidates events using
     * isSameCallCheckingToOnly(). The FROM radio is updated when a new one is
     * identified, but a change in FROM does NOT create a new row — it just updates
     * the existing event. This prevents the Calls tab from splitting one continuous
     * call into many short rows every time a different radio keys up or when grant
     * updates arrive without a FROM field.
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

        // Always update the existing event — do NOT create new per-talker events.
        // This matches the TrafficChannelManager's behavior of one event per call.
        CallSessionEvent currentEvent = session.getCurrentEvent();
        if(currentEvent != null)
        {
            currentEvent.updateEnd(timestamp);

            // Update FROM radio: keep existing if incoming is null, update if incoming is new
            if(fromRadio != null)
            {
                currentEvent.setFromRadio(fromRadio);
            }

            // Update identifier collection only if it has richer info (has a FROM radio)
            if(ic != null)
            {
                if(fromRadio != null || currentEvent.getFromRadio() == null)
                {
                    currentEvent.setIdentifierCollection(ic);
                }
            }

            notifyEventUpdated(session, currentEvent);
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

    /**
     * Upgrades a non-encrypted DecodeEventType to its encrypted counterpart.
     * Returns the original type if already encrypted or no mapping exists.
     */
    private DecodeEventType upgradeToEncrypted(DecodeEventType type)
    {
        if(type == null)
        {
            return DecodeEventType.CALL_ENCRYPTED;
        }

        return switch(type)
        {
            case CALL_GROUP -> DecodeEventType.CALL_GROUP_ENCRYPTED;
            case CALL_PATCH_GROUP -> DecodeEventType.CALL_PATCH_GROUP_ENCRYPTED;
            case CALL_UNIT_TO_UNIT -> DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED;
            case CALL_INTERCONNECT -> DecodeEventType.CALL_INTERCONNECT_ENCRYPTED;
            case CALL -> DecodeEventType.CALL_ENCRYPTED;
            case DATA_CALL -> DecodeEventType.DATA_CALL_ENCRYPTED;
            default -> type; // Already encrypted or unknown — return as-is
        };
    }

    // ========================================================================
    // Session lifecycle transitions
    // ========================================================================

    /**
     * Transitions a session to ENDING state, moving it from active to ending map.
     *
     * @param session the session to transition
     * @param clearCachedEvent true to remove the cached Events tab event (used when a DIFFERENT
     *        call starts on the same frequency); false to keep it (used for TDU within the same
     *        call, so the Events tab row survives reactivation with correct duration)
     */
    private void transitionToEnding(CallSession session, boolean clearCachedEvent)
    {
        String key = sessionKey(session.getFrequency(), session.getTimeslot());
        mActiveSessions.remove(key);
        session.setState(CallState.ENDING);
        mEndingSessions.put(key + ":" + session.getSessionId(), session);

        if(clearCachedEvent)
        {
            // Remove the cached Events tab event so a new call on this frequency creates a fresh row.
            // Phase 1 control events are cached with timeslot 0, so try both keys.
            mActiveControlEvents.remove(key);
            if(session.getTimeslot() != 0)
            {
                mActiveControlEvents.remove(session.getFrequency() + ":0");
            }
        }
    }

    /**
     * Checks mEndingSessions for a session matching the given frequency/timeslot/talkgroup
     * and reactivates it if found (within the gap tolerance window). This handles the P25
     * pattern where TDU transitions the session to ENDING, but the control channel continues
     * sending grant updates for the same call (PTT release within a group call).
     *
     * @return the reactivated session, or null if no matching ENDING session was found
     */
    private CallSession reactivateFromEnding(long frequency, int timeslot, Identifier toTalkgroup,
                                              Identifier fromRadio, long timestamp)
    {
        String keyPrefix = sessionKey(frequency, timeslot) + ":";

        for(Iterator<Map.Entry<String, CallSession>> it = mEndingSessions.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<String, CallSession> entry = it.next();
            if(entry.getKey().startsWith(keyPrefix))
            {
                CallSession session = entry.getValue();
                if(session.isMatch(frequency, timeslot, toTalkgroup, fromRadio, timestamp))
                {
                    // Reactivate: move back to active sessions
                    it.remove();
                    session.setState(CallState.ACTIVE);
                    session.updateActivity(timestamp);
                    String activeKey = sessionKey(frequency, timeslot);
                    mActiveSessions.put(activeKey, session);
                    return session;
                }
            }
        }

        return null;
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

        // Clean up the cached Events tab event now that the session is truly complete.
        // This handles the case where transitionToEnding kept the cached event (clearCachedEvent=false)
        // for potential reactivation, but the session expired without being reactivated.
        String key = sessionKey(session.getFrequency(), session.getTimeslot());
        mActiveControlEvents.remove(key);
        if(session.getTimeslot() != 0)
        {
            mActiveControlEvents.remove(session.getFrequency() + ":0");
        }

        mLog.trace("Session complete: {} (events={}, duration={}ms)",
                session.getSessionId(), session.getEventCount(), session.getDuration());

        notifySessionComplete(session);
    }

    /**
     * Periodic cleanup of stale sessions. Runs every 500ms on a background thread.
     *
     * Checks both ACTIVE and ENDING sessions for staleness:
     * - ACTIVE sessions with no activity beyond gap tolerance are finalized directly.
     *   This is the primary mechanism for ending calls that naturally stop (the control
     *   channel simply stops sending grant updates and the traffic channel sends TDU).
     * - ENDING sessions (from explicit different-call transitions) are finalized after
     *   the gap tolerance period.
     */
    private void cleanupEndingSessions()
    {
        long now = System.currentTimeMillis();

        // Check ACTIVE sessions for staleness — finalize if no activity beyond gap tolerance.
        // This handles the normal call lifecycle: when neither the control channel nor the
        // traffic channel sends further updates, the session is considered over.
        Iterator<Map.Entry<String, CallSession>> activeIterator = mActiveSessions.entrySet().iterator();

        while(activeIterator.hasNext())
        {
            Map.Entry<String, CallSession> entry = activeIterator.next();
            CallSession session = entry.getValue();
            long gap = now - session.getLastActivityTimestamp();

            if(gap > mGapToleranceMs)
            {
                activeIterator.remove();
                finalizeSession(session);
            }
        }

        // Check ENDING sessions for finalization (from explicit different-call transitions
        // via transitionToEnding(), which is called when a different talkgroup starts on
        // the same frequency/timeslot).
        Iterator<Map.Entry<String, CallSession>> endingIterator = mEndingSessions.entrySet().iterator();

        while(endingIterator.hasNext())
        {
            Map.Entry<String, CallSession> entry = endingIterator.next();
            CallSession session = entry.getValue();
            long gap = now - session.getLastActivityTimestamp();

            if(gap > mGapToleranceMs)
            {
                endingIterator.remove();
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

    /**
     * Unified cross-frequency session matching. Checks all active sessions on OTHER frequencies
     * to find one that belongs to the same call. This handles:
     *
     * 1. PatchGroupIdentifier: supergroup or member TG overlap with seen talkgroups
     * 2. Regular TalkgroupIdentifier: TG value already in any session's seenTalkgroups
     * 3. Radio affinity: same FROM radio active in another session within 5 seconds
     *
     * This is the key fix for patch group calls opening multiple traffic channels: when
     * grants arrive for patch member TGs BEFORE the PatchGroupManager has resolved them
     * to PatchGroupIdentifiers, the radio affinity check catches them as the same call.
     *
     * @param toTalkgroup the TO identifier (may be TalkgroupIdentifier or PatchGroupIdentifier)
     * @param fromRadio the FROM identifier (may be null)
     * @param timestamp current timestamp for affinity checking
     * @return the matching CallSession on another frequency, or null if none found
     */
    private CallSession findCrossFrequencySession(Identifier toTalkgroup, Identifier fromRadio, long timestamp)
    {
        // Check 1: PatchGroupIdentifier — supergroup or member overlap
        if(toTalkgroup instanceof PatchGroupIdentifier pgi)
        {
            CallSession session = findSessionByPatchGroup(pgi);
            if(session != null)
            {
                return session;
            }
        }

        // Check 2: Regular TalkgroupIdentifier — check if the TG is already seen in any active session
        if(toTalkgroup instanceof TalkgroupIdentifier tgi)
        {
            int tgId = tgi.getValue();
            for(CallSession session : mActiveSessions.values())
            {
                if(session.hasSeenTalkgroup(tgId))
                {
                    return session;
                }
            }

            // Check 2b: Consult PatchGroupManager — if this TG is a member of a known patch group,
            // find sessions that have the supergroup or other members. This catches the case where
            // member TG grants arrive for different TGs that are part of the same patch group,
            // but the PatchGroupIdentifier hasn't been resolved in the IC yet.
            if(mPatchGroupManager != null)
            {
                int supergroupId = mPatchGroupManager.getSupergroupId(tgId);
                if(supergroupId > 0)
                {
                    for(CallSession session : mActiveSessions.values())
                    {
                        if(session.hasSeenTalkgroup(supergroupId))
                        {
                            return session;
                        }
                    }
                }
            }
        }

        // Check 3: Radio affinity — same FROM radio on another frequency within tolerance
        if(fromRadio != null)
        {
            String radioStr = fromRadio.toString();
            for(CallSession session : mActiveSessions.values())
            {
                if(session.isRadioAffiliated(radioStr, timestamp, 5000))
                {
                    return session;
                }
            }
        }

        return null;
    }

    /**
     * Searches all active sessions for one that shares the same patch group as the given identifier.
     * This enables cross-frequency session matching: when a member TG grant arrives on a different
     * frequency, we can find the existing session for the supergroup on the original frequency.
     *
     * @param patchGroupIdentifier the patch group to match against
     * @return the matching CallSession, or null if none found
     */
    private CallSession findSessionByPatchGroup(PatchGroupIdentifier patchGroupIdentifier)
    {
        if(patchGroupIdentifier == null)
        {
            return null;
        }

        int supergroupId = patchGroupIdentifier.getValue().getPatchGroup().getValue();

        for(CallSession session : mActiveSessions.values())
        {
            // Check if the session's primary TG matches the supergroup
            if(session.hasSeenTalkgroup(supergroupId))
            {
                return session;
            }

            // Check if any member TGs overlap
            for(TalkgroupIdentifier member : patchGroupIdentifier.getValue().getPatchedTalkgroupIdentifiers())
            {
                if(session.hasSeenTalkgroup(member.getValue()))
                {
                    return session;
                }
            }
        }

        return null;
    }

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

        int supergroupId = patchGroup.getValue().getPatchGroup().getValue();
        java.util.List<Integer> memberTgIds = new java.util.ArrayList<>();
        memberTgIds.add(supergroupId);
        for(TalkgroupIdentifier member : patchGroup.getValue().getPatchedTalkgroupIdentifiers())
        {
            memberTgIds.add(member.getValue());
        }

        // Find all active sessions that have any member TG of this patch group
        java.util.List<CallSession> matchingSessions = new java.util.ArrayList<>();
        for(CallSession session : mActiveSessions.values())
        {
            for(int tgId : memberTgIds)
            {
                if(session.hasSeenTalkgroup(tgId))
                {
                    matchingSessions.add(session);
                    break;
                }
            }
        }

        // Enrich all matching sessions with patch group info
        for(CallSession session : matchingSessions)
        {
            session.enrichPatchGroup(patchGroup);
            // Add ALL member TGs to the session's seenTalkgroups so future grants match
            for(int tgId : memberTgIds)
            {
                session.addSeenTalkgroupById(tgId);
            }
        }

        // Consolidate: if multiple sessions matched, merge them into the oldest one
        // and release traffic channels for the duplicates
        if(matchingSessions.size() > 1)
        {
            // Sort by session start time — keep the oldest
            matchingSessions.sort((a, b) -> Long.compare(a.getCallStart(), b.getCallStart()));
            CallSession primary = matchingSessions.get(0);

            for(int i = 1; i < matchingSessions.size(); i++)
            {
                CallSession duplicate = matchingSessions.get(i);
                mLog.info("Consolidating duplicate patch session {} (freq={}) into primary session {} (freq={})",
                        duplicate.getSessionId(), duplicate.getFrequency(),
                        primary.getSessionId(), primary.getFrequency());

                // Merge seen talkgroups into primary
                for(int tgId : duplicate.getSeenTalkgroupIds())
                {
                    primary.addSeenTalkgroupById(tgId);
                }

                // Release the traffic channel for the duplicate's frequency
                if(mTrafficChannelManager != null)
                {
                    mTrafficChannelManager.releaseTrafficChannel(duplicate.getFrequency());
                }

                // Remove from active sessions and finalize
                String dupKey = sessionKey(duplicate.getFrequency(), duplicate.getTimeslot());
                mActiveSessions.remove(dupKey);
                finalizeSession(duplicate);
            }
        }

        // Also enrich any ENDING sessions
        for(CallSession session : mEndingSessions.values())
        {
            for(int tgId : memberTgIds)
            {
                if(session.hasSeenTalkgroup(tgId))
                {
                    session.enrichPatchGroup(patchGroup);
                    break;
                }
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
        mActiveControlEvents.clear();

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

    /**
     * Returns all CallSessionEvents from active and ending sessions. Used by CallSessionPanel
     * to backfill the model when switching between channels (Fix 4: calls disappearing on click-away).
     *
     * @return list of all current session events (active + ending)
     */
    public List<CallSessionEvent> getActiveSessionEvents()
    {
        java.util.ArrayList<CallSessionEvent> events = new java.util.ArrayList<>();

        for(CallSession session : mActiveSessions.values())
        {
            events.addAll(session.getEvents());
        }

        for(CallSession session : mEndingSessions.values())
        {
            events.addAll(session.getEvents());
        }

        return events;
    }

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
