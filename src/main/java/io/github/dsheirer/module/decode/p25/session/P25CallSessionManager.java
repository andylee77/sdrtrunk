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
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.ScrambleParameters;
import io.github.dsheirer.module.decode.p25.reference.ServiceOptions;
import io.github.dsheirer.module.decode.session.CallSession;
import io.github.dsheirer.module.decode.session.CallSessionEvent;
import io.github.dsheirer.module.decode.session.CallSessionListener;
import io.github.dsheirer.module.decode.session.CallState;
import io.github.dsheirer.module.decode.session.ChannelSourceType;
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
 * Central authority for P25 call session management.
 *
 * Manages CallSession lifecycle for the Calls tab and call log database. This class is
 * the SESSION TRACKING layer only — it does NOT broadcast events to the Events tab or
 * allocate traffic channels. Those responsibilities belong to P25TrafficChannelManager (TCM).
 *
 * Architecture (established by fix 012):
 *   DecoderState → TCM (events + channels) → CSM (session tracking only)
 *                    ↕ traffic channels feed back updates → CSM
 *
 * Two distinct event flows:
 * - Control channel events arrive via processChannelGrant() / processChannelUpdate()
 *   (forwarded by TCM after it handles event broadcasting and channel allocation)
 * - Traffic channel events arrive via onTrafficChannelUpdate() / onTrafficChannelEnd()
 *   (forwarded by TCM from traffic channel decoder state)
 *
 * Key responsibilities:
 * - Creates and manages call session lifecycle (PENDING → ACTIVE → ENDING → COMPLETE)
 * - Determines DecodeEventType from Opcode
 * - Tags sessions with encrypted/unmonitored/data filtering info for Calls tab display
 * - Creates per-talker CallSessionEvent objects
 * - Notifies listeners on session creation, event addition/update, and completion
 * - Periodic cleanup of ENDING sessions past gap tolerance
 */
public class P25CallSessionManager
{
    private static final Logger mLog = LoggerFactory.getLogger(P25CallSessionManager.class);

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

    /** Back-reference to traffic channel manager for frequency allocation checks */
    private P25TrafficChannelManager mTrafficChannelManager;

    /** Alias list for isUnmonitored() checks */
    private AliasList mAliasList;

    /** Filtering flags */
    private boolean mIgnoreDataCalls;
    private boolean mIgnoreEncryptedCalls;
    private boolean mIgnoreUnmonitoredCalls;

    /**
     * Constructs an instance.
     */
    public P25CallSessionManager()
    {
    }

    // ========================================================================
    // Configuration setters
    // ========================================================================

    /**
     * Sets the traffic channel manager back-reference for frequency allocation checks.
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

    // ========================================================================
    // Control Channel Grant Processing (forwarded from TCM)
    // ========================================================================

    /**
     * Process a Phase 1 control channel grant. Called by TCM after it has handled
     * event broadcasting and traffic channel allocation.
     *
     * Logic:
     * 1. Determine DecodeEventType from opcode
     * 2. Route TDMA channels to Phase 2 processing
     * 3. Check for existing session on this (freq, timeslot)
     * 4. If same call → update session, extend duration
     * 5. If different call → end old session, start new
     * 6. Tag with encrypted/unmonitored/data filters for Calls tab
     * 7. Create CallSession and notify listeners
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
            DecodeEventType decodeEventType = P25DecodeEventTypeResolver.resolve(opcode, serviceOptions, null);
            boolean isDataGrant = opcode != null && opcode.isDataChannelGrant();

            if(apco25Channel.isTDMAChannel())
            {
                // TDMA channel from P1 control → Phase 2 session tracking
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
     * Process a Phase 1 control channel grant update. Called by TCM after it has
     * handled event broadcasting and traffic channel allocation.
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
     * Internal Phase 1 grant processing — session tracking only.
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
        // session and skip creating a duplicate session
        if(existing == null)
        {
            CallSession crossFreqSession = findCrossFrequencySession(toTalkgroup, fromRadio, timestamp);
            if(crossFreqSession != null)
            {
                crossFreqSession.updateActivity(timestamp);
                crossFreqSession.addSeenTalkgroup(toTalkgroup);
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

            // Use session's (possibly upgraded) event type
            DecodeEventType broadcastType = existing.getEventType() != null ? existing.getEventType() : decodeEventType;

            // Check BOTH ServiceOptions AND session's encryption state (may have been upgraded
            // by traffic channel detection via onTrafficChannelUpdate).
            boolean sessionEncrypted = (serviceOptions != null && serviceOptions.isEncrypted())
                    || P25DecodeEventTypeResolver.isEncryptedEventType(broadcastType);

            // Tag ignored calls so Calls tab can filter
            if(mIgnoreEncryptedCalls && sessionEncrypted)
            {
                tagSessionIgnored(existing, "IGNORED: ENCRYPTED CALL");
            }
            else if(mIgnoreUnmonitoredCalls && isUnmonitored(ic))
            {
                tagSessionIgnored(existing, "IGNORED: UNMONITORED CALL");
            }

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
                return;
            }
        }

        // Different TG on the same freq:ts — end old session, start a new one
        if(existing != null)
        {
            transitionToEnding(existing);
        }

        // Determine ignored reason — but ALWAYS create a session so Calls tab
        // gets entries and event type state is preserved across grants.
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

        // Create new session
        EncryptionKeyIdentifier encryption = extractEncryption(ic);
        CallSession newSession = createSession(frequency, timeslot, toTalkgroup, decodeEventType,
                serviceOptions, apco25Channel, encryption, timestamp);
        mActiveSessions.put(key, newSession);

        // Track the FROM radio on the session for future isMatch() veto checks
        if(fromRadio != null)
        {
            newSession.setCurrentFromRadio(fromRadio);
        }

        // Create per-talker event (use timeslot 0 for display — Phase 1 has no meaningful timeslot)
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
    }

    /**
     * Internal Phase 2 grant processing — session tracking only.
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
                    || P25DecodeEventTypeResolver.isEncryptedEventType(broadcastType);

            // Tag ignored calls so Calls tab can filter
            if(mIgnoreEncryptedCalls && sessionEncrypted)
            {
                tagSessionIgnored(existing, "IGNORED: ENCRYPTED CALL");
            }
            else if(mIgnoreUnmonitoredCalls && isUnmonitored(ic))
            {
                tagSessionIgnored(existing, "IGNORED: UNMONITORED CALL");
            }

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
                return;
            }
        }

        // Different TG on the same freq:ts — end old session, start a new one
        if(existing != null)
        {
            transitionToEnding(existing);
        }

        // Determine ignored reason — but ALWAYS create a session so Calls tab gets entries
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

        // Create new session
        EncryptionKeyIdentifier encryption = extractEncryption(ic);
        CallSession newSession = createSession(frequency, timeslot, toTalkgroup, decodeEventType,
                serviceOptions, apco25Channel, encryption, timestamp);
        mActiveSessions.put(key, newSession);

        // Track the FROM radio on the session for future isMatch() veto checks
        if(fromRadio != null)
        {
            newSession.setCurrentFromRadio(fromRadio);
        }

        String sessionDetails = ignoredReason != null ? ignoredReason : context;
        CallSessionEvent sessionEvent = createSessionEvent(newSession, fromRadio, toTalkgroup,
                decodeEventType, ic, apco25Channel, serviceOptions,
                sessionDetails, frequency, timeslot, timestamp);
        sessionEvent.setChannelSourceType(ChannelSourceType.CONTROL);
        newSession.addEvent(sessionEvent);
        newSession.setState(CallState.ACTIVE);

        notifySessionCreated(newSession);
        notifyEventAdded(newSession, sessionEvent);
    }

    // ========================================================================
    // Traffic-Side Forwarding (from TCM)
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

                // Fallback FROM-radio splitting for systems where control channel grants
                // lack FROM radio info. If the session was created without a FROM (null)
                // and the traffic channel now provides one, set it. If the session already
                // has a FROM and it differs from what traffic channel reports, this is a
                // different talker — end this session so the next grant creates a new one.
                Identifier sessionFrom = session.getCurrentFromRadio();
                if(sessionFrom == null)
                {
                    // First identification of FROM radio — set it on the session
                    session.setCurrentFromRadio(fromRadio);
                }
                else if(!sessionFrom.equals(fromRadio))
                {
                    // Different FROM radio detected from traffic channel — this shouldn't
                    // normally happen because control channel grants with different FROM
                    // are now vetoed by isMatch(). But as a safety net for systems where
                    // FROM is only available from traffic channel, end this session.
                    mLog.info("Session {} FROM radio changed via traffic channel: {} → {} — ending session",
                            session.getSessionId(), sessionFrom, fromRadio);
                    transitionToEnding(session);
                    return;
                }
            }

            // Check for encryption from traffic channel (HDU/LDU messages carry EncryptionKeyIdentifier).
            // This is the primary encryption detection path — control channel TSBK grants often do NOT
            // include encryption info; it's only available once the traffic channel starts decoding.
            EncryptionKeyIdentifier eki = extractEncryption(ic);
            if(eki != null && eki.isEncrypted())
            {
                session.setEncryption(eki);

                // Upgrade the session and event type to encrypted variant
                if(!P25DecodeEventTypeResolver.isEncryptedEventType(session.getEventType()))
                {
                    DecodeEventType upgradedType = P25DecodeEventTypeResolver.upgradeToEncrypted(session.getEventType());
                    session.setEventType(upgradedType);

                    CallSessionEvent currentEvent = session.getCurrentEvent();
                    if(currentEvent != null)
                    {
                        currentEvent.setEventType(upgradedType);
                    }

                    // Tag session as ignored if we're filtering encrypted calls
                    if(mIgnoreEncryptedCalls)
                    {
                        tagSessionIgnored(session, "IGNORED: ENCRYPTED CALL");
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
                transitionToEnding(session);
            }
        }
        catch(Exception e)
        {
            mLog.error("Error processing traffic channel end", e);
        }
    }

    // ========================================================================
    // Shared Logic
    // ========================================================================

    /**
     * Checks if the identifier collection represents an unmonitored call based on alias configuration.
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

    /**
     * Tags a session and its current event as ignored with the given reason.
     * Used so the Calls tab can filter/display ignored calls.
     */
    private void tagSessionIgnored(CallSession session, String reason)
    {
        CallSessionEvent currentEvt = session.getCurrentEvent();
        if(currentEvt != null)
        {
            String evtDetails = currentEvt.getDetails();
            if(evtDetails == null || !evtDetails.contains("IGNORED"))
            {
                currentEvt.setDetails(reason);
            }
        }
        session.setDetails(reason);
    }

    // ========================================================================
    // Session Management
    // ========================================================================

    /**
     * Updates an existing session's event from a control channel grant.
     *
     * This method consolidates all activity for a call into ONE event per session,
     * matching how the P25TrafficChannelManager consolidates events using
     * isSameCallCheckingToOnly(). The FROM radio is updated when a new one is
     * identified, but a change in FROM does NOT create a new row — it just updates
     * the existing event.
     */
    private void updateSessionFromGrant(CallSession session, IdentifierCollection ic,
                                        DecodeEventType eventType, ServiceOptions serviceOptions,
                                        IChannelDescriptor channelDescriptor, long timestamp)
    {
        Identifier fromRadio = ic != null ? ic.getFromIdentifier() : null;

        if(fromRadio != null)
        {
            session.updateRadioAffinity(fromRadio.toString(), timestamp);
            // Keep the session's current FROM radio in sync with the latest grant
            session.setCurrentFromRadio(fromRadio);
        }

        // Update event type if it upgrades
        if(eventType != null)
        {
            boolean upgrade = false;
            if(P25DecodeEventTypeResolver.isPatchEventType(eventType) && !P25DecodeEventTypeResolver.isPatchEventType(session.getEventType()))
            {
                upgrade = true;
            }
            if(P25DecodeEventTypeResolver.isEncryptedEventType(eventType) && !P25DecodeEventTypeResolver.isEncryptedEventType(session.getEventType()))
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

    // ========================================================================
    // Session lifecycle transitions
    // ========================================================================

    /**
     * Transitions a session to ENDING state, moving it from active to ending map.
     *
     * @param session the session to transition
     */
    private void transitionToEnding(CallSession session)
    {
        String key = sessionKey(session.getFrequency(), session.getTimeslot());
        mActiveSessions.remove(key);
        session.setState(CallState.ENDING);
        mEndingSessions.put(key + ":" + session.getSessionId(), session);
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

        mLog.debug("Session complete: {} (events={}, duration={}ms)",
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

        // Check ACTIVE sessions for staleness
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

        // Check ENDING sessions for finalization
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
            // find sessions that have the supergroup or other members.
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

        return null;
    }

    /**
     * Searches all active sessions for one that shares the same patch group as the given identifier.
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
            if(session.hasSeenTalkgroup(supergroupId))
            {
                return session;
            }

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
            for(int tgId : memberTgIds)
            {
                session.addSeenTalkgroupById(tgId);
            }
        }

        // Consolidate: if multiple sessions matched, merge them into the oldest one
        if(matchingSessions.size() > 1)
        {
            matchingSessions.sort((a, b) -> Long.compare(a.getCallStart(), b.getCallStart()));
            CallSession primary = matchingSessions.get(0);

            for(int i = 1; i < matchingSessions.size(); i++)
            {
                CallSession duplicate = matchingSessions.get(i);
                mLog.info("Consolidating duplicate patch session {} (freq={}) into primary session {} (freq={})",
                        duplicate.getSessionId(), duplicate.getFrequency(),
                        primary.getSessionId(), primary.getFrequency());

                for(int tgId : duplicate.getSeenTalkgroupIds())
                {
                    primary.addSeenTalkgroupById(tgId);
                }

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
     * to backfill the model when switching between channels.
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
