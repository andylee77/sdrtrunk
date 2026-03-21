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

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.session.CallSession;
import io.github.dsheirer.module.decode.session.CallSessionEvent;
import io.github.dsheirer.module.decode.session.CallSessionListener;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements CallSessionListener to persist completed call sessions to a SQLite database.
 *
 * Writing only occurs on session COMPLETE — one write per session, no debounce needed.
 * Each session and its per-talker events are written in a single atomic transaction.
 *
 * The database path is auto-generated from the system name:
 * {user.home}/SDRTrunk/call_logs/{system}_calls.db
 */
public class CallLogWriter implements CallSessionListener
{
    private static final Logger mLog = LoggerFactory.getLogger(CallLogWriter.class);

    private CallLogDatabase mDatabase;
    private final String mSystem;
    private final String mSite;
    private final String mChannelName;
    private boolean mStarted = false;

    /**
     * Constructs an instance.
     *
     * @param system system name from channel config (used for DB path and stored in records)
     * @param site site name from channel config
     * @param channelName channel name from channel config
     */
    public CallLogWriter(String system, String site, String channelName)
    {
        mSystem = system != null ? system : "default";
        mSite = site;
        mChannelName = channelName;
    }

    /**
     * Opens the database connection. Called when the traffic channel manager starts.
     */
    public void start()
    {
        if(mStarted)
        {
            return;
        }

        try
        {
            Path dbPath = getDefaultDatabasePath(mSystem);
            mDatabase = new CallLogDatabase(dbPath);
            mDatabase.open();
            mStarted = true;
            mLog.info("CallLogWriter started for system '{}' → {}", mSystem, dbPath);
        }
        catch(SQLException | IOException e)
        {
            mLog.error("Failed to start CallLogWriter for system '{}'", mSystem, e);
            mDatabase = null;
        }
    }

    /**
     * Returns the database for read access (history queries). May be null if not started.
     */
    public CallLogDatabase getDatabase()
    {
        return mDatabase;
    }

    /**
     * Closes the database connection. Called when the traffic channel manager stops.
     */
    public void stop()
    {
        mStarted = false;

        if(mDatabase != null)
        {
            mDatabase.close();
            mDatabase = null;
        }

        mLog.info("CallLogWriter stopped for system '{}'", mSystem);
    }

    // ========================================================================
    // CallSessionListener implementation
    // ========================================================================

    @Override
    public void onSessionCreated(CallSession session)
    {
        // No-op — we only write on COMPLETE
    }

    @Override
    public void onSessionEventAdded(CallSession session, CallSessionEvent event)
    {
        // No-op — we only write on COMPLETE
    }

    @Override
    public void onSessionEventUpdated(CallSession session, CallSessionEvent event)
    {
        // No-op — we only write on COMPLETE
    }

    @Override
    public void onSessionComplete(CallSession session)
    {
        if(!mStarted || mDatabase == null || !mDatabase.isOpen())
        {
            return;
        }

        try
        {
            CallLogRecord record = toRecord(session);
            List<CallEventRecord> events = toEventRecords(session);
            mDatabase.insertSessionWithEvents(record, events);

            mLog.trace("Written session {} to call log: tg={} events={} dur={}ms",
                    session.getSessionId(),
                    record.getTalkgroupId(),
                    events.size(),
                    record.getDurationMs());
        }
        catch(SQLException e)
        {
            mLog.error("Error writing session {} to call log database", session.getSessionId(), e);
        }
    }

    // ========================================================================
    // Conversion: CallSession → CallLogRecord
    // ========================================================================

    /**
     * Converts a completed CallSession into a CallLogRecord for database storage.
     */
    private CallLogRecord toRecord(CallSession session)
    {
        CallLogRecord record = new CallLogRecord();

        // Timing
        record.setTimeStart(session.getCallStart());
        record.setTimeEnd(session.getCallEnd());
        record.setDurationMs(session.getDuration());

        // Identity
        record.setTalkgroupId(extractTalkgroupIdString(session.getTalkgroup()));
        record.setEventType(session.getEventType() != null ? session.getEventType().getLabel() : "UNKNOWN");

        // Patch group
        PatchGroupIdentifier patchGroup = session.getPatchGroup();
        if(patchGroup != null)
        {
            PatchGroup pg = patchGroup.getValue();
            record.setPatchGroupId(String.valueOf(pg.getPatchGroup().getValue()));

            List<TalkgroupIdentifier> members = pg.getPatchedTalkgroupIdentifiers();
            if(members != null && !members.isEmpty())
            {
                StringBuilder sb = new StringBuilder();
                for(int i = 0; i < members.size(); i++)
                {
                    if(i > 0)
                    {
                        sb.append(",");
                    }
                    sb.append(members.get(i).getValue());
                }
                record.setPatchGroupMembers(sb.toString());
            }
        }

        // Channel
        record.setFrequency(session.getFrequency());
        record.setTimeslot(session.getTimeslot());
        if(session.getChannelDescriptor() != null)
        {
            record.setChannelDescriptor(session.getChannelDescriptor().toString());
        }

        // Session metadata
        record.setTalkerCount(session.getTalkerCount());
        record.setEncrypted(session.isEncrypted());
        record.setDuplicate(session.isDuplicate());

        // System context
        record.setSystem(mSystem);
        record.setSite(mSite);
        record.setChannelName(mChannelName);

        // From (first talker's radio ID)
        List<CallSessionEvent> events = session.getEvents();
        if(!events.isEmpty())
        {
            CallSessionEvent firstEvent = events.get(0);
            if(firstEvent.getFromRadio() != null)
            {
                record.setFromId(extractRadioIdString(firstEvent.getFromRadio()));
            }
        }

        // Protocol details
        record.setDetails(session.getDetails());
        if(session.getServiceOptions() != null)
        {
            record.setServiceOptions(session.getServiceOptions().toString());
        }

        return record;
    }

    // ========================================================================
    // Conversion: CallSessionEvent → CallEventRecord
    // ========================================================================

    /**
     * Converts all per-talker events from a CallSession into CallEventRecord list.
     */
    private List<CallEventRecord> toEventRecords(CallSession session)
    {
        List<CallSessionEvent> sessionEvents = session.getEvents();
        List<CallEventRecord> records = new ArrayList<>(sessionEvents.size());

        for(CallSessionEvent event : sessionEvents)
        {
            CallEventRecord record = new CallEventRecord();

            // Timing
            record.setTimeStart(event.getTimeStart());
            record.setTimeEnd(event.getTimeEnd());
            record.setDurationMs(event.getDuration());

            // Identity
            record.setEventType(event.getEventType() != null ? event.getEventType().getLabel() : "UNKNOWN");
            record.setFromId(event.getFromRadio() != null ? event.getFromRadio().toString() : null);
            record.setToId(event.getToTalkgroup() != null ? event.getToTalkgroup().toString() : null);

            // Details
            record.setDetails(event.getDetails());

            // Recording (populated by later phases)
            record.setRecordingPath(event.getRecordingPath());

            // Transcript (populated by later phases)
            record.setTranscript(event.getTranscript());

            records.add(record);
        }

        return records;
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    /**
     * Extracts a string representation of the talkgroup ID from an Identifier.
     */
    private String extractTalkgroupIdString(Identifier identifier)
    {
        if(identifier == null)
        {
            return "unknown";
        }

        if(identifier instanceof TalkgroupIdentifier tgi)
        {
            return String.valueOf(tgi.getValue());
        }
        else if(identifier instanceof PatchGroupIdentifier pgi)
        {
            return String.valueOf(pgi.getValue().getPatchGroup().getValue());
        }

        return identifier.toString();
    }

    /**
     * Extracts a string representation of the radio ID from an Identifier.
     */
    private String extractRadioIdString(Identifier identifier)
    {
        if(identifier == null)
        {
            return null;
        }
        // For IntegerIdentifier subclasses (RadioIdentifier), toString() returns the numeric value
        return identifier.toString();
    }

    /**
     * Returns the default database path for a given system name.
     * Path: {user.home}/SDRTrunk/call_logs/{system}_calls.db
     */
    static Path getDefaultDatabasePath(String systemName)
    {
        String sanitized = sanitizeFileName(systemName);
        return Paths.get(System.getProperty("user.home"), "SDRTrunk", "call_logs", sanitized + "_calls.db");
    }

    /**
     * Sanitizes a string for use as a file name component.
     * Replaces non-alphanumeric characters (except hyphens and underscores) with underscores.
     */
    private static String sanitizeFileName(String name)
    {
        if(name == null || name.isBlank())
        {
            return "default";
        }
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
    }
}
