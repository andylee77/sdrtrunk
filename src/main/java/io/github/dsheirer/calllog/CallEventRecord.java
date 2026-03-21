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

/**
 * POJO representing one row in the call_events SQLite table.
 *
 * Maps to a single per-talker segment within a call session. Created from
 * CallSessionEvent by the CallLogWriter. Linked to the parent session row
 * via sessionId.
 */
public class CallEventRecord
{
    // Database ID (set after insert)
    private long mId;

    // Parent session — set to the DB-assigned session ID after session insert
    private long mSessionId;

    // Timing
    private long mTimeStart;
    private long mTimeEnd;
    private long mDurationMs;

    // Identity
    private String mEventType;
    private String mFromId;
    private String mFromAlias;
    private String mToId;
    private String mToAlias;

    // Details
    private String mDetails;

    // Recording (populated by later phases)
    private String mRecordingPath;

    // Transcription (populated by later phases)
    private String mTranscript;

    public CallEventRecord()
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

    public long getSessionId()
    {
        return mSessionId;
    }

    public void setSessionId(long sessionId)
    {
        mSessionId = sessionId;
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

    public String getEventType()
    {
        return mEventType;
    }

    public void setEventType(String eventType)
    {
        mEventType = eventType;
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

    public String getToId()
    {
        return mToId;
    }

    public void setToId(String toId)
    {
        mToId = toId;
    }

    public String getToAlias()
    {
        return mToAlias;
    }

    public void setToAlias(String toAlias)
    {
        mToAlias = toAlias;
    }

    public String getDetails()
    {
        return mDetails;
    }

    public void setDetails(String details)
    {
        mDetails = details;
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

    @Override
    public String toString()
    {
        return "CallEventRecord[session=" + mSessionId + " from=" + mFromId + " to=" + mToId +
               " dur=" + mDurationMs + "ms]";
    }
}
