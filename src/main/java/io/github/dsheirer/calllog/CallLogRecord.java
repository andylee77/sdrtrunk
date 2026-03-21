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
package io.github.dsheirer.calllog;

import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;

/**
 * POJO representing one row in the call_sessions SQLite table.
 *
 * Maps directly to the schema defined in design doc 006a. Created from a completed
 * CallSession by the CallLogWriter and written to the database by CallLogDatabase.
 */
public class CallLogRecord
{
    // Database ID (set after insert)
    private long mId;

    // Timing
    private long mTimeStart;
    private long mTimeEnd;
    private long mDurationMs;

    // Identity
    private String mTalkgroupId;
    private String mTalkgroupAlias;
    private String mPatchGroupId;
    private String mPatchGroupMembers;
    private String mEventType;

    // Channel
    private double mFrequency;
    private int mTimeslot;
    private String mChannelDescriptor;

    // Session metadata
    private int mTalkerCount;
    private boolean mEncrypted;
    private boolean mDuplicate;

    // System context
    private String mSystem;
    private String mSite;
    private String mChannelName;

    // From (first talker)
    private String mFromId;
    private String mFromAlias;

    // Protocol details
    private String mDetails;
    private String mServiceOptions;

    // Cached synthetic IdentifierCollection for alias resolution at render time
    private IdentifierCollection mIdentifierCollection;

    public CallLogRecord()
    {
    }

    public long getId()
    {
        return mId;
    }

    public void setId(long id)
    {
        mId = id;
    }

    public long getTimeStart()
    {
        return mTimeStart;
    }

    public void setTimeStart(long timeStart)
    {
        mTimeStart = timeStart;
    }

    public long getTimeEnd()
    {
        return mTimeEnd;
    }

    public void setTimeEnd(long timeEnd)
    {
        mTimeEnd = timeEnd;
    }

    public long getDurationMs()
    {
        return mDurationMs;
    }

    public void setDurationMs(long durationMs)
    {
        mDurationMs = durationMs;
    }

    public String getTalkgroupId()
    {
        return mTalkgroupId;
    }

    public void setTalkgroupId(String talkgroupId)
    {
        mTalkgroupId = talkgroupId;
    }

    public String getTalkgroupAlias()
    {
        return mTalkgroupAlias;
    }

    public void setTalkgroupAlias(String talkgroupAlias)
    {
        mTalkgroupAlias = talkgroupAlias;
    }

    public String getPatchGroupId()
    {
        return mPatchGroupId;
    }

    public void setPatchGroupId(String patchGroupId)
    {
        mPatchGroupId = patchGroupId;
    }

    public String getPatchGroupMembers()
    {
        return mPatchGroupMembers;
    }

    public void setPatchGroupMembers(String patchGroupMembers)
    {
        mPatchGroupMembers = patchGroupMembers;
    }

    public String getEventType()
    {
        return mEventType;
    }

    public void setEventType(String eventType)
    {
        mEventType = eventType;
    }

    public double getFrequency()
    {
        return mFrequency;
    }

    public void setFrequency(double frequency)
    {
        mFrequency = frequency;
    }

    public int getTimeslot()
    {
        return mTimeslot;
    }

    public void setTimeslot(int timeslot)
    {
        mTimeslot = timeslot;
    }

    public String getChannelDescriptor()
    {
        return mChannelDescriptor;
    }

    public void setChannelDescriptor(String channelDescriptor)
    {
        mChannelDescriptor = channelDescriptor;
    }

    public int getTalkerCount()
    {
        return mTalkerCount;
    }

    public void setTalkerCount(int talkerCount)
    {
        mTalkerCount = talkerCount;
    }

    public boolean isEncrypted()
    {
        return mEncrypted;
    }

    public void setEncrypted(boolean encrypted)
    {
        mEncrypted = encrypted;
    }

    public boolean isDuplicate()
    {
        return mDuplicate;
    }

    public void setDuplicate(boolean duplicate)
    {
        mDuplicate = duplicate;
    }

    public String getSystem()
    {
        return mSystem;
    }

    public void setSystem(String system)
    {
        mSystem = system;
    }

    public String getSite()
    {
        return mSite;
    }

    public void setSite(String site)
    {
        mSite = site;
    }

    public String getChannelName()
    {
        return mChannelName;
    }

    public void setChannelName(String channelName)
    {
        mChannelName = channelName;
    }

    public String getDetails()
    {
        return mDetails;
    }

    public void setDetails(String details)
    {
        mDetails = details;
    }

    public String getServiceOptions()
    {
        return mServiceOptions;
    }

    public void setServiceOptions(String serviceOptions)
    {
        mServiceOptions = serviceOptions;
    }

    public String getFromId()
    {
        return mFromId;
    }

    public void setFromId(String fromId)
    {
        mFromId = fromId;
    }

    public String getFromAlias()
    {
        return mFromAlias;
    }

    public void setFromAlias(String fromAlias)
    {
        mFromAlias = fromAlias;
    }

    /**
     * Returns a synthetic IdentifierCollection built from the stored numeric IDs and the given
     * alias list name. This allows the same alias-resolving cell renderers used for live events
     * to work with historical database records. The collection is cached after first build.
     *
     * @param aliasListName the alias list name from the channel config (may be null)
     * @return IdentifierCollection with talkgroup/radio identifiers and alias list config
     */
    public IdentifierCollection getIdentifierCollection(String aliasListName)
    {
        if(mIdentifierCollection == null)
        {
            MutableIdentifierCollection mic = new MutableIdentifierCollection();

            // Add talkgroup (TO role)
            if(mTalkgroupId != null)
            {
                try
                {
                    int tgId = Integer.parseInt(mTalkgroupId);
                    mic.update(APCO25Talkgroup.create(tgId));
                }
                catch(NumberFormatException e)
                {
                    // Non-numeric talkgroup, skip
                }
            }

            // Add radio (FROM role)
            if(mFromId != null)
            {
                try
                {
                    int radioId = Integer.parseInt(mFromId);
                    mic.update(APCO25RadioIdentifier.createFrom(radioId));
                }
                catch(NumberFormatException e)
                {
                    // Non-numeric radio ID, skip
                }
            }

            // Add alias list configuration so the renderer can find the correct AliasList
            if(aliasListName != null && !aliasListName.isBlank())
            {
                mic.update(AliasListConfigurationIdentifier.create(aliasListName));
            }

            mIdentifierCollection = mic;
        }

        return mIdentifierCollection;
    }

    @Override
    public String toString()
    {
        return "CallLogRecord[tg=" + mTalkgroupId + " start=" + mTimeStart + " dur=" + mDurationMs + "ms]";
    }
}
