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
package io.github.dsheirer.module.decode.session;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.reference.ServiceOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents a single call session — from channel grant to call termination.
 *
 * Protocol-agnostic — usable by P25, DMR, NXDN, or any trunked protocol.
 *
 * A session is the logical grouping unit that tracks all activity on a particular
 * frequency/timeslot for a single call. Within it, CallSessionEvent objects represent
 * each per-talker segment. The Calls tab shows one flat row per event, with the
 * sessionId linking related events together.
 *
 * The session also handles patch group detection: when the same radio ID is seen
 * transmitting to different talkgroups on the same channel within the gap tolerance,
 * all those TGs are considered part of the same call (implied patch group).
 */
public class CallSession
{
    private final long mSessionId;
    private long mFrequency;
    private int mTimeslot;
    private CallState mState = CallState.PENDING;
    private Identifier mTalkgroup;
    private PatchGroupIdentifier mPatchGroup;
    private final MutableIdentifierCollection mIdentifiers;
    private final List<CallSessionEvent> mEvents = new ArrayList<>();
    private EncryptionKeyIdentifier mEncryption;
    private IChannelDescriptor mChannelDescriptor;
    private ServiceOptions mServiceOptions;
    private DecodeEventType mEventType;
    private String mDetails;
    private long mCallStart;
    private long mCallEnd;
    private long mLastActivityTimestamp;
    private boolean mDuplicate;

    // Patch group detection: all TG IDs seen during this session
    private final Set<Integer> mSeenTalkgroups = new HashSet<>();

    // Radio affinity: tracks which radio IDs are associated with this session and when last seen
    private final Map<String, Long> mRadioAffinityMap = new HashMap<>();

    /**
     * Constructs an instance.
     *
     * @param sessionId unique session identifier
     * @param frequency downlink frequency in Hz
     * @param timeslot timeslot number
     * @param talkgroup TO talkgroup identifier (may be PatchGroupIdentifier)
     * @param eventType initial decode event type
     * @param serviceOptions service options (may be null)
     * @param channelDescriptor channel descriptor (may be null)
     * @param timestamp creation timestamp (epoch ms)
     */
    public CallSession(long sessionId, long frequency, int timeslot, Identifier talkgroup,
                       DecodeEventType eventType, ServiceOptions serviceOptions,
                       IChannelDescriptor channelDescriptor, long timestamp)
    {
        mSessionId = sessionId;
        mFrequency = frequency;
        mTimeslot = timeslot;
        mTalkgroup = talkgroup;
        mEventType = eventType;
        mServiceOptions = serviceOptions;
        mChannelDescriptor = channelDescriptor;
        mCallStart = timestamp;
        mCallEnd = timestamp;
        mLastActivityTimestamp = timestamp;
        mIdentifiers = new MutableIdentifierCollection();

        // Track initial talkgroup
        addSeenTalkgroup(talkgroup);

        // If the talkgroup is already a patch group, store it
        if(talkgroup instanceof PatchGroupIdentifier pgi)
        {
            mPatchGroup = pgi;
        }
    }

    public long getSessionId()
    {
        return mSessionId;
    }

    public long getFrequency()
    {
        return mFrequency;
    }

    public int getTimeslot()
    {
        return mTimeslot;
    }

    public CallState getState()
    {
        return mState;
    }

    public void setState(CallState state)
    {
        mState = state;
    }

    public Identifier getTalkgroup()
    {
        return mTalkgroup;
    }

    public PatchGroupIdentifier getPatchGroup()
    {
        return mPatchGroup;
    }

    public void enrichPatchGroup(PatchGroupIdentifier patchGroup)
    {
        mPatchGroup = patchGroup;
        // Also add member TGs to seenTalkgroups
        if(patchGroup != null)
        {
            PatchGroup pg = patchGroup.getValue();
            for(TalkgroupIdentifier member : pg.getPatchedTalkgroupIdentifiers())
            {
                mSeenTalkgroups.add(member.getValue());
            }
        }
    }

    public MutableIdentifierCollection getIdentifiers()
    {
        return mIdentifiers;
    }

    public EncryptionKeyIdentifier getEncryption()
    {
        return mEncryption;
    }

    public void setEncryption(EncryptionKeyIdentifier encryption)
    {
        mEncryption = encryption;
    }

    public boolean isEncrypted()
    {
        return mEncryption != null && mEncryption.isEncrypted();
    }

    public IChannelDescriptor getChannelDescriptor()
    {
        return mChannelDescriptor;
    }

    public void setChannelDescriptor(IChannelDescriptor channelDescriptor)
    {
        if(mChannelDescriptor == null)
        {
            mChannelDescriptor = channelDescriptor;
        }
    }

    public ServiceOptions getServiceOptions()
    {
        return mServiceOptions;
    }

    public void setServiceOptions(ServiceOptions serviceOptions)
    {
        mServiceOptions = serviceOptions;
    }

    public DecodeEventType getEventType()
    {
        return mEventType;
    }

    public void setEventType(DecodeEventType eventType)
    {
        mEventType = eventType;
    }

    public String getDetails()
    {
        return mDetails;
    }

    public void setDetails(String details)
    {
        mDetails = details;
    }

    public long getCallStart()
    {
        return mCallStart;
    }

    public long getCallEnd()
    {
        return mCallEnd;
    }

    public long getLastActivityTimestamp()
    {
        return mLastActivityTimestamp;
    }

    public long getDuration()
    {
        return mCallEnd > mCallStart ? mCallEnd - mCallStart : 0;
    }

    public boolean isDuplicate()
    {
        return mDuplicate;
    }

    public void setDuplicate(boolean duplicate)
    {
        mDuplicate = duplicate;
    }

    /**
     * Updates the last activity timestamp and call end time.
     *
     * @param timestamp current time
     */
    public void updateActivity(long timestamp)
    {
        if(timestamp > mLastActivityTimestamp)
        {
            mLastActivityTimestamp = timestamp;
        }
        if(timestamp > mCallEnd)
        {
            mCallEnd = timestamp;
        }
    }

    // ========================================================================
    // Per-talker events
    // ========================================================================

    /**
     * Returns an unmodifiable view of all per-talker events in this session.
     */
    public List<CallSessionEvent> getEvents()
    {
        return Collections.unmodifiableList(mEvents);
    }

    /**
     * Returns the most recent per-talker event, or null if none exist.
     */
    public CallSessionEvent getCurrentEvent()
    {
        if(mEvents.isEmpty())
        {
            return null;
        }
        return mEvents.get(mEvents.size() - 1);
    }

    /**
     * Adds a per-talker event to this session.
     */
    public void addEvent(CallSessionEvent event)
    {
        mEvents.add(event);

        // Track radio affinity
        if(event.getFromRadio() != null)
        {
            mRadioAffinityMap.put(event.getFromRadio().toString(), event.getTimeStart());
        }

        // Track talkgroup
        addSeenTalkgroup(event.getToTalkgroup());
    }

    public int getEventCount()
    {
        return mEvents.size();
    }

    /**
     * Returns the count of distinct FROM radio identifiers seen during this session.
     */
    public int getTalkerCount()
    {
        Set<String> radios = new HashSet<>();
        for(CallSessionEvent event : mEvents)
        {
            if(event.getFromRadio() != null)
            {
                radios.add(event.getFromRadio().toString());
            }
        }
        return radios.size();
    }

    // ========================================================================
    // Talkgroup tracking (for patch group detection)
    // ========================================================================

    /**
     * Adds a talkgroup ID to the set of TGs seen during this session.
     */
    public void addSeenTalkgroup(Identifier talkgroup)
    {
        if(talkgroup instanceof TalkgroupIdentifier tgi)
        {
            mSeenTalkgroups.add(tgi.getValue());
        }
        else if(talkgroup instanceof PatchGroupIdentifier pgi)
        {
            PatchGroup pg = pgi.getValue();
            mSeenTalkgroups.add(pg.getPatchGroup().getValue());
            for(TalkgroupIdentifier member : pg.getPatchedTalkgroupIdentifiers())
            {
                mSeenTalkgroups.add(member.getValue());
            }
        }
    }

    /**
     * Returns all talkgroup IDs seen during this session (supergroups + members + regular TGs).
     */
    public Set<Integer> getSeenTalkgroups()
    {
        return Collections.unmodifiableSet(mSeenTalkgroups);
    }

    /**
     * Checks if a talkgroup ID has been seen in this session.
     */
    public boolean hasSeenTalkgroup(int talkgroupId)
    {
        return mSeenTalkgroups.contains(talkgroupId);
    }

    // ========================================================================
    // Radio affinity tracking (for patch group detection without explicit data)
    // ========================================================================

    /**
     * Records that a radio ID is affiliated with this session at the given time.
     */
    public void updateRadioAffinity(String radioId, long timestamp)
    {
        mRadioAffinityMap.put(radioId, timestamp);
    }

    /**
     * Checks if a radio ID is affiliated with this session (seen within tolerance).
     *
     * @param radioId the radio identifier string
     * @param timestamp current time
     * @param toleranceMs how long after last seen the radio is still considered affiliated
     * @return true if the radio was seen within the tolerance window
     */
    public boolean isRadioAffiliated(String radioId, long timestamp, long toleranceMs)
    {
        Long lastSeen = mRadioAffinityMap.get(radioId);
        return lastSeen != null && (timestamp - lastSeen) <= toleranceMs;
    }

    // ========================================================================
    // Call identity matching
    // ========================================================================

    /**
     * Determines if an incoming event matches this session.
     *
     * Match conditions (any one is sufficient):
     * 1. Same frequency + timeslot + same talkgroup
     * 2. Same frequency + timeslot + different talkgroup BUT same FROM radio within tolerance (patch detection)
     * 3. Same frequency + timeslot + talkgroup is in seenTalkgroups set (known patch member)
     * 4. PatchGroupIdentifier supergroup or member overlap
     *
     * @param frequency downlink frequency
     * @param timeslot timeslot number
     * @param talkgroup TO talkgroup identifier
     * @param fromRadio FROM radio identifier (may be null)
     * @param timestamp current timestamp
     * @return true if this event belongs to this session
     */
    public boolean isMatch(long frequency, int timeslot, Identifier talkgroup, Identifier fromRadio, long timestamp)
    {
        // Must be same frequency and timeslot
        if(mFrequency != frequency || mTimeslot != timeslot)
        {
            return false;
        }

        // Must not be complete
        if(mState == CallState.COMPLETE)
        {
            return false;
        }

        // Condition 1: Same talkgroup
        if(isSameTalkgroup(talkgroup))
        {
            return true;
        }

        // Condition 2: Same FROM radio within tolerance (implies patch group)
        if(fromRadio != null)
        {
            String radioStr = fromRadio.toString();
            if(isRadioAffiliated(radioStr, timestamp, 5000))
            {
                return true;
            }
        }

        // Condition 3: Talkgroup is in our seenTalkgroups set
        int tgId = extractTalkgroupId(talkgroup);
        if(tgId > 0 && hasSeenTalkgroup(tgId))
        {
            return true;
        }

        // Condition 4: PatchGroupIdentifier overlap
        if(talkgroup instanceof PatchGroupIdentifier incoming && mPatchGroup != null)
        {
            // Check if supergroups match
            int incomingSupergroup = incoming.getValue().getPatchGroup().getValue();
            int ourSupergroup = mPatchGroup.getValue().getPatchGroup().getValue();
            if(incomingSupergroup == ourSupergroup)
            {
                return true;
            }

            // Check if any member talkgroups overlap
            for(TalkgroupIdentifier member : incoming.getValue().getPatchedTalkgroupIdentifiers())
            {
                if(hasSeenTalkgroup(member.getValue()))
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Checks if the given talkgroup identifier matches this session's primary talkgroup.
     */
    private boolean isSameTalkgroup(Identifier talkgroup)
    {
        if(mTalkgroup == null || talkgroup == null)
        {
            return false;
        }

        // Direct equality
        if(mTalkgroup.equals(talkgroup))
        {
            return true;
        }

        // Compare by extracted ID value
        int ourId = extractTalkgroupId(mTalkgroup);
        int theirId = extractTalkgroupId(talkgroup);
        return ourId > 0 && ourId == theirId;
    }

    /**
     * Extracts a numeric talkgroup ID from an identifier.
     *
     * @param identifier to extract from
     * @return talkgroup ID or -1 if not extractable
     */
    private static int extractTalkgroupId(Identifier identifier)
    {
        if(identifier instanceof TalkgroupIdentifier tgi)
        {
            return tgi.getValue();
        }
        else if(identifier instanceof PatchGroupIdentifier pgi)
        {
            return pgi.getValue().getPatchGroup().getValue();
        }
        return -1;
    }

    /**
     * Checks if the FROM radio in the given event is the same as the current event's FROM radio.
     *
     * Rules:
     * - Both null = same talker (common for control channel grants with no radio ID)
     * - Current null, incoming non-null = same talker (radio was just identified, update existing event)
     * - Current non-null, incoming null = same talker (update without radio info, keep existing)
     * - Both non-null and equal = same talker
     * - Both non-null and different = DIFFERENT talker → creates new per-talker event
     *
     * When the FROM radio transitions from null to identified, this method also updates
     * the current event's FROM radio so the UI shows the correct radio ID.
     */
    public boolean isSameTalker(Identifier fromRadio)
    {
        CallSessionEvent current = getCurrentEvent();
        if(current == null)
        {
            return false;
        }

        Identifier currentFrom = current.getFromRadio();

        // Both null = same talker
        if(currentFrom == null && fromRadio == null)
        {
            return true;
        }

        // Current null, incoming identified = same talker, update FROM
        if(currentFrom == null && fromRadio != null)
        {
            current.setFromRadio(fromRadio);
            return true;
        }

        // Current identified, incoming null = same talker, keep existing FROM
        if(currentFrom != null && fromRadio == null)
        {
            return true;
        }

        // Both non-null: compare by value — only different if values differ
        return currentFrom.equals(fromRadio);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("CallSession[id=").append(mSessionId);
        sb.append(" state=").append(mState);
        sb.append(" freq=").append(mFrequency);
        if(mTimeslot > 0)
        {
            sb.append(" ts=").append(mTimeslot);
        }
        sb.append(" tg=").append(mTalkgroup);
        sb.append(" events=").append(mEvents.size());
        sb.append(" dur=").append(getDuration()).append("ms");
        sb.append("]");
        return sb.toString();
    }
}
