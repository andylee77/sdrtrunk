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
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.reference.ServiceOptions;
import io.github.dsheirer.module.decode.session.ChannelSourceType;

/**
 * Represents a single per-talker segment within a call session.
 * Each radio ID change creates a new event. This is one row in the Calls tab.
 *
 * Protocol-agnostic — usable by P25, DMR, NXDN, or any trunked protocol.
 *
 * The event carries its own timing, identifiers, and (future) recording/transcript references.
 * It links to its parent session via sessionId.
 */
public class CallSessionEvent
{
    private final long mSessionId;
    private long mTimeStart;
    private long mTimeEnd;
    private DecodeEventType mEventType;
    private Identifier mFromRadio;
    private Identifier mToTalkgroup;
    private IdentifierCollection mIdentifierCollection;
    private IChannelDescriptor mChannelDescriptor;
    private ServiceOptions mServiceOptions;
    private String mDetails;
    private long mFrequency;
    private int mTimeslot;

    // Channel source type — indicates if this event originated from control or traffic channel
    private ChannelSourceType mChannelSourceType = ChannelSourceType.UNKNOWN;

    // Future fields — populated by later phases
    private String mRecordingPath;
    private String mTranscript;

    /**
     * Constructs an instance.
     *
     * @param sessionId parent session ID
     * @param timeStart start timestamp (epoch ms)
     * @param eventType decode event type
     * @param fromRadio FROM radio identifier (may be null)
     * @param toTalkgroup TO talkgroup identifier
     * @param identifierCollection full identifier collection from the decode event
     * @param channelDescriptor channel descriptor
     * @param serviceOptions service options (may be null, P25-specific but stored generically)
     * @param details event details string
     * @param frequency downlink frequency in Hz
     * @param timeslot timeslot number
     */
    public CallSessionEvent(long sessionId, long timeStart, DecodeEventType eventType,
                            Identifier fromRadio, Identifier toTalkgroup,
                            IdentifierCollection identifierCollection,
                            IChannelDescriptor channelDescriptor, ServiceOptions serviceOptions,
                            String details, long frequency, int timeslot)
    {
        mSessionId = sessionId;
        mTimeStart = timeStart;
        mTimeEnd = timeStart;
        mEventType = eventType;
        mFromRadio = fromRadio;
        mToTalkgroup = toTalkgroup;
        mIdentifierCollection = identifierCollection;
        mChannelDescriptor = channelDescriptor;
        mServiceOptions = serviceOptions;
        mDetails = details;
        mFrequency = frequency;
        mTimeslot = timeslot;
    }

    public long getSessionId()
    {
        return mSessionId;
    }

    public long getTimeStart()
    {
        return mTimeStart;
    }

    public long getTimeEnd()
    {
        return mTimeEnd;
    }

    public long getDuration()
    {
        return mTimeEnd > mTimeStart ? mTimeEnd - mTimeStart : 0;
    }

    public void updateEnd(long timestamp)
    {
        if(timestamp > mTimeEnd)
        {
            mTimeEnd = timestamp;
        }
    }

    public DecodeEventType getEventType()
    {
        return mEventType;
    }

    public void setEventType(DecodeEventType eventType)
    {
        mEventType = eventType;
    }

    public Identifier getFromRadio()
    {
        return mFromRadio;
    }

    public void setFromRadio(Identifier fromRadio)
    {
        mFromRadio = fromRadio;
    }

    public Identifier getToTalkgroup()
    {
        return mToTalkgroup;
    }

    public void setToTalkgroup(Identifier toTalkgroup)
    {
        mToTalkgroup = toTalkgroup;
    }

    public IdentifierCollection getIdentifierCollection()
    {
        return mIdentifierCollection;
    }

    public void setIdentifierCollection(IdentifierCollection identifierCollection)
    {
        mIdentifierCollection = identifierCollection;
    }

    public IChannelDescriptor getChannelDescriptor()
    {
        return mChannelDescriptor;
    }

    public void setChannelDescriptor(IChannelDescriptor channelDescriptor)
    {
        mChannelDescriptor = channelDescriptor;
    }

    public ServiceOptions getServiceOptions()
    {
        return mServiceOptions;
    }

    public String getDetails()
    {
        return mDetails;
    }

    public void setDetails(String details)
    {
        mDetails = details;
    }

    public long getFrequency()
    {
        return mFrequency;
    }

    public int getTimeslot()
    {
        return mTimeslot;
    }

    /**
     * Returns true if this event has a valid timeslot.
     * Uses >= 0 to match DecodeEvent convention where timeslot 0 is valid
     * (used for Phase 1 FDMA non-timeslot signalling) and -1 means "no timeslot".
     */
    public boolean hasTimeslot()
    {
        return mTimeslot >= 0;
    }

    public String getRecordingPath()
    {
        return mRecordingPath;
    }

    public void setRecordingPath(String recordingPath)
    {
        mRecordingPath = recordingPath;
    }

    public String getTranscript()
    {
        return mTranscript;
    }

    public void setTranscript(String transcript)
    {
        mTranscript = transcript;
    }

    public ChannelSourceType getChannelSourceType()
    {
        return mChannelSourceType;
    }

    public void setChannelSourceType(ChannelSourceType channelSourceType)
    {
        mChannelSourceType = channelSourceType;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("SessionEvent[session=").append(mSessionId);
        sb.append(" type=").append(mEventType);
        if(mFromRadio != null)
        {
            sb.append(" from=").append(mFromRadio);
        }
        if(mToTalkgroup != null)
        {
            sb.append(" to=").append(mToTalkgroup);
        }
        sb.append(" dur=").append(getDuration()).append("ms");
        sb.append("]");
        return sb.toString();
    }
}
