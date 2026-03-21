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

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;
import io.github.dsheirer.module.decode.p25.reference.ServiceOptions;
import io.github.dsheirer.module.decode.session.CallSession;
import io.github.dsheirer.module.decode.session.CallSessionEvent;
import io.github.dsheirer.module.decode.session.CallSessionListener;
import io.github.dsheirer.module.decode.session.CallState;
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
 * Observes DecodeEvent broadcasts from P25TrafficChannelManager and builds/manages
 * CallSession objects that group related events into unified call sessions.
 *
 * Key responsibilities:
 * - Creates and manages call session lifecycle (PENDING → ACTIVE → ENDING → COMPLETE)
 * - Matches incoming events to existing sessions (same channel, TG, or radio affinity)
 * - Detects implied patch groups when same radio transmits to different TGs
 * - Creates per-talker CallSessionEvent objects (one per radio ID change)
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

    /**
     * Constructs an instance.
     */
    public P25CallSessionManager()
    {
    }

    /**
     * Primary entry point — called from P25TrafficChannelManager.broadcast().
     *
     * Processes a DecodeEvent and either creates a new session, adds to an existing session,
     * or updates an existing per-talker event.
     *
     * @param decodeEvent the decode event to process
     * @param timestamp current time in epoch milliseconds
     */
    public void onDecodeEvent(DecodeEvent decodeEvent, long timestamp)
    {
        if(!mRunning)
        {
            return;
        }

        try
        {
            processEvent(decodeEvent, timestamp);
        }
        catch(Exception e)
        {
            mLog.error("Error processing decode event for call session management", e);
        }
    }

    /**
     * Internal processing of a decode event.
     */
    private void processEvent(DecodeEvent decodeEvent, long timestamp)
    {
        // Extract identifiers from the event
        IdentifierCollection identifiers = decodeEvent.getIdentifierCollection();
        if(identifiers == null)
        {
            return;
        }

        // Extract key fields
        Identifier toTalkgroup = extractToIdentifier(identifiers);
        Identifier fromRadio = identifiers.getIdentifier(IdentifierClass.USER, Form.RADIO, Role.FROM);
        DecodeEventType eventType = decodeEvent.getEventType();
        IChannelDescriptor channelDescriptor = decodeEvent.getChannelDescriptor();
        int timeslot = decodeEvent.getTimeslot();

        // Get frequency from channel descriptor
        long frequency = 0;
        if(channelDescriptor != null)
        {
            frequency = channelDescriptor.getDownlinkFrequency();
        }

        // Skip events without a talkgroup
        if(toTalkgroup == null)
        {
            return;
        }

        // Get service options if available (P25ChannelGrantEvent)
        ServiceOptions serviceOptions = null;
        if(decodeEvent instanceof P25ChannelGrantEvent grantEvent)
        {
            serviceOptions = grantEvent.getServiceOptions();
        }

        // Get encryption info
        Identifier encIdentifier = identifiers.getIdentifier(IdentifierClass.USER, Form.ENCRYPTION_KEY, Role.ANY);
        EncryptionKeyIdentifier encryption = (encIdentifier instanceof EncryptionKeyIdentifier eki) ? eki : null;

        String key = sessionKey(frequency, timeslot);

        // Try to match against active sessions
        CallSession activeSession = mActiveSessions.get(key);
        if(activeSession != null && activeSession.isMatch(frequency, timeslot, toTalkgroup, fromRadio, timestamp))
        {
            // Matched active session — update it
            updateSession(activeSession, decodeEvent, fromRadio, toTalkgroup, eventType,
                    identifiers, channelDescriptor, serviceOptions, encryption, timestamp);
            return;
        }

        // If active session exists but doesn't match (different TG, no radio affinity), end it
        if(activeSession != null)
        {
            transitionToEnding(activeSession);
        }

        // Try to match against ENDING sessions (gap tolerance resume)
        CallSession endingSession = mEndingSessions.get(key);
        if(endingSession != null && endingSession.isMatch(frequency, timeslot, toTalkgroup, fromRadio, timestamp))
        {
            // Resume the ending session
            long gap = timestamp - endingSession.getLastActivityTimestamp();
            if(gap <= mGapToleranceMs)
            {
                mEndingSessions.remove(key);
                endingSession.setState(CallState.ACTIVE);
                mActiveSessions.put(key, endingSession);
                updateSession(endingSession, decodeEvent, fromRadio, toTalkgroup, eventType,
                        identifiers, channelDescriptor, serviceOptions, encryption, timestamp);
                return;
            }
        }

        // Also check all ending sessions for radio affinity match (cross-channel patch detection)
        for(Map.Entry<String, CallSession> entry : mEndingSessions.entrySet())
        {
            CallSession candidate = entry.getValue();
            if(candidate.isMatch(frequency, timeslot, toTalkgroup, fromRadio, timestamp))
            {
                long gap = timestamp - candidate.getLastActivityTimestamp();
                if(gap <= mGapToleranceMs)
                {
                    mEndingSessions.remove(entry.getKey());
                    candidate.setState(CallState.ACTIVE);
                    mActiveSessions.put(key, candidate);
                    updateSession(candidate, decodeEvent, fromRadio, toTalkgroup, eventType,
                            identifiers, channelDescriptor, serviceOptions, encryption, timestamp);
                    return;
                }
            }
        }

        // Finalize old ending session on this key if it exists
        if(endingSession != null)
        {
            finalizeSession(endingSession);
            mEndingSessions.remove(key);
        }

        // Create a new session
        CallSession newSession = createSession(frequency, timeslot, toTalkgroup, eventType,
                serviceOptions, channelDescriptor, encryption, timestamp);
        mActiveSessions.put(key, newSession);

        // Create the first per-talker event
        CallSessionEvent sessionEvent = createSessionEvent(newSession, fromRadio, toTalkgroup,
                eventType, identifiers, channelDescriptor, serviceOptions,
                decodeEvent.getDetails(), frequency, timeslot, timestamp);
        newSession.addEvent(sessionEvent);
        newSession.setState(CallState.ACTIVE);
        newSession.setDetails(decodeEvent.getDetails());

        // Notify listeners
        notifySessionCreated(newSession);
        notifyEventAdded(newSession, sessionEvent);
    }

    /**
     * Updates an existing session with a new decode event.
     */
    private void updateSession(CallSession session, DecodeEvent decodeEvent,
                               Identifier fromRadio, Identifier toTalkgroup,
                               DecodeEventType eventType, IdentifierCollection identifiers,
                               IChannelDescriptor channelDescriptor, ServiceOptions serviceOptions,
                               EncryptionKeyIdentifier encryption, long timestamp)
    {
        session.updateActivity(timestamp);
        session.setChannelDescriptor(channelDescriptor);
        session.addSeenTalkgroup(toTalkgroup);

        if(serviceOptions != null)
        {
            session.setServiceOptions(serviceOptions);
        }
        if(encryption != null)
        {
            session.setEncryption(encryption);
        }

        // Track radio affinity
        if(fromRadio != null)
        {
            session.updateRadioAffinity(fromRadio.toString(), timestamp);
        }

        // Update event type if it upgrades (e.g. CALL → CALL_PATCH_GROUP)
        if(eventType != null && isPatchEventType(eventType) && !isPatchEventType(session.getEventType()))
        {
            session.setEventType(eventType);
        }

        // Update details
        if(decodeEvent.getDetails() != null)
        {
            session.setDetails(decodeEvent.getDetails());
        }

        // Check if this is the same talker or a new talker
        if(session.isSameTalker(fromRadio))
        {
            // Same talker — update duration of current event
            CallSessionEvent currentEvent = session.getCurrentEvent();
            if(currentEvent != null)
            {
                currentEvent.updateEnd(timestamp);
                currentEvent.setIdentifierCollection(identifiers);
                if(decodeEvent.getDetails() != null)
                {
                    currentEvent.setDetails(decodeEvent.getDetails());
                }
                notifyEventUpdated(session, currentEvent);
            }
        }
        else
        {
            // Different talker (or first talker after resuming) — create new per-talker event
            // Close out previous event
            CallSessionEvent previousEvent = session.getCurrentEvent();
            if(previousEvent != null)
            {
                previousEvent.updateEnd(timestamp);
            }

            CallSessionEvent newEvent = createSessionEvent(session, fromRadio, toTalkgroup,
                    eventType, identifiers, channelDescriptor, serviceOptions,
                    decodeEvent.getDetails(), session.getFrequency(), session.getTimeslot(), timestamp);
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

        // Try to enrich with patch group data
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
     * Checks for patch group first, then talkgroup, then radio.
     */
    private Identifier extractToIdentifier(IdentifierCollection identifiers)
    {
        // Prefer patch group
        Identifier to = identifiers.getIdentifier(IdentifierClass.USER, Form.PATCH_GROUP, Role.TO);
        if(to != null)
        {
            return to;
        }

        // Then talkgroup
        to = identifiers.getIdentifier(IdentifierClass.USER, Form.TALKGROUP, Role.TO);
        if(to != null)
        {
            return to;
        }

        // Then radio (for unit-to-unit calls)
        return identifiers.getIdentifier(IdentifierClass.USER, Form.RADIO, Role.TO);
    }

    /**
     * Checks if a DecodeEventType is a patch group event type.
     */
    private boolean isPatchEventType(DecodeEventType eventType)
    {
        if(eventType == null)
        {
            return false;
        }
        return eventType.name().contains("PATCH_GROUP");
    }

    // ========================================================================
    // Session lifecycle transitions
    // ========================================================================

    /**
     * Moves an active session to ENDING state.
     */
    private void transitionToEnding(CallSession session)
    {
        String key = sessionKey(session.getFrequency(), session.getTimeslot());
        mActiveSessions.remove(key);
        session.setState(CallState.ENDING);
        mEndingSessions.put(key + ":" + session.getSessionId(), session);
    }

    /**
     * Finalizes a session — moves to COMPLETE and notifies listeners.
     */
    private void finalizeSession(CallSession session)
    {
        if(session.getState() == CallState.COMPLETE)
        {
            return;
        }

        session.setState(CallState.COMPLETE);

        // Close out the last event
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
     * Periodic cleanup — finalizes ENDING sessions past gap tolerance.
     */
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

    /**
     * Generates a session key from frequency and timeslot.
     */
    private String sessionKey(long frequency, int timeslot)
    {
        return frequency + ":" + timeslot;
    }

    // ========================================================================
    // Patch group support
    // ========================================================================

    /**
     * Sets the PatchGroupManager for enrichment.
     */
    public void setPatchGroupManager(PatchGroupManager patchGroupManager)
    {
        mPatchGroupManager = patchGroupManager;
    }

    /**
     * Called when a patch group is updated. Enriches any active sessions using this patch group.
     */
    public void onPatchGroupUpdate(PatchGroupIdentifier patchGroup)
    {
        if(patchGroup == null)
        {
            return;
        }

        for(CallSession session : mActiveSessions.values())
        {
            // Check if this session's talkgroup is related to this patch group
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

    /**
     * Starts the session manager timer for periodic ENDING session cleanup.
     */
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

    /**
     * Stops the session manager, finalizing all active and ending sessions.
     */
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

        // Finalize all remaining sessions
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
