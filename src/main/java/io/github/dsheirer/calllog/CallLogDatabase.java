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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite database manager for persisting completed call sessions and their per-talker events.
 *
 * Uses java.sql (JDBC) with the org.xerial:sqlite-jdbc driver.
 * Schema follows design doc 006a — two tables: call_sessions + call_events.
 *
 * Thread safety: this class is designed to be used from a single thread (the CallLogWriter).
 * SQLite is single-writer; connection opened in open(), closed in close().
 */
public class CallLogDatabase
{
    private static final Logger mLog = LoggerFactory.getLogger(CallLogDatabase.class);

    private final Path mDatabasePath;
    private Connection mConnection;

    // ========================================================================
    // SQL Statements
    // ========================================================================

    private static final String CREATE_SESSIONS_TABLE = """
        CREATE TABLE IF NOT EXISTS call_sessions (
            id                      INTEGER PRIMARY KEY AUTOINCREMENT,
            time_start              INTEGER NOT NULL,
            time_end                INTEGER NOT NULL,
            duration_ms             INTEGER NOT NULL,
            talkgroup_id            TEXT NOT NULL,
            talkgroup_alias         TEXT,
            patch_group_id          TEXT,
            patch_group_members     TEXT,
            event_type              TEXT NOT NULL,
            frequency               REAL,
            timeslot                INTEGER,
            channel_descriptor      TEXT,
            talker_count            INTEGER DEFAULT 0,
            encrypted               INTEGER DEFAULT 0,
            duplicate               INTEGER DEFAULT 0,
            system                  TEXT,
            site                    TEXT,
            channel_name            TEXT,
            from_id                 TEXT,
            details                 TEXT,
            service_options         TEXT,
            transcript              TEXT,
            transcript_source       TEXT,
            transcript_quality      INTEGER,
            llm_summary             TEXT,
            incident_id             INTEGER,
            created_at              INTEGER DEFAULT (strftime('%s','now') * 1000)
        )
        """;

    private static final String CREATE_EVENTS_TABLE = """
        CREATE TABLE IF NOT EXISTS call_events (
            id                      INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id              INTEGER NOT NULL REFERENCES call_sessions(id),
            time_start              INTEGER NOT NULL,
            time_end                INTEGER NOT NULL,
            duration_ms             INTEGER NOT NULL,
            event_type              TEXT NOT NULL,
            from_id                 TEXT,
            from_alias              TEXT,
            to_id                   TEXT,
            to_alias                TEXT,
            details                 TEXT,
            recording_path          TEXT,
            recording_organized_path TEXT,
            transcript              TEXT,
            transcript_source       TEXT,
            transcript_quality      INTEGER,
            created_at              INTEGER DEFAULT (strftime('%s','now') * 1000)
        )
        """;

    private static final String CREATE_INDEX_SESSIONS_TIME =
        "CREATE INDEX IF NOT EXISTS idx_sessions_time ON call_sessions(time_start)";

    private static final String CREATE_INDEX_SESSIONS_TALKGROUP =
        "CREATE INDEX IF NOT EXISTS idx_sessions_talkgroup ON call_sessions(talkgroup_id)";

    private static final String CREATE_INDEX_SESSIONS_SYSTEM =
        "CREATE INDEX IF NOT EXISTS idx_sessions_system ON call_sessions(system)";

    private static final String CREATE_INDEX_SESSIONS_INCIDENT =
        "CREATE INDEX IF NOT EXISTS idx_sessions_incident ON call_sessions(incident_id)";

    private static final String CREATE_INDEX_SESSIONS_NO_TRANSCRIPT =
        "CREATE INDEX IF NOT EXISTS idx_sessions_no_transcript ON call_sessions(transcript) WHERE transcript IS NULL";

    private static final String CREATE_INDEX_EVENTS_SESSION =
        "CREATE INDEX IF NOT EXISTS idx_events_session ON call_events(session_id)";

    private static final String CREATE_INDEX_EVENTS_TIME =
        "CREATE INDEX IF NOT EXISTS idx_events_time ON call_events(time_start)";

    private static final String CREATE_INDEX_EVENTS_FROM =
        "CREATE INDEX IF NOT EXISTS idx_events_from ON call_events(from_id)";

    private static final String CREATE_INDEX_EVENTS_NO_TRANSCRIPT =
        "CREATE INDEX IF NOT EXISTS idx_events_no_transcript ON call_events(recording_path, transcript) " +
        "WHERE recording_path IS NOT NULL AND transcript IS NULL";

    private static final String INSERT_SESSION = """
        INSERT INTO call_sessions (
            time_start, time_end, duration_ms, talkgroup_id, talkgroup_alias,
            patch_group_id, patch_group_members, event_type, frequency, timeslot,
            channel_descriptor, talker_count, encrypted, duplicate, system, site,
            channel_name, from_id, details, service_options
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String INSERT_EVENT = """
        INSERT INTO call_events (
            session_id, time_start, time_end, duration_ms, event_type,
            from_id, from_alias, to_id, to_alias, details, recording_path, transcript
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    /**
     * Constructs an instance.
     *
     * @param databasePath full path to the SQLite database file
     */
    public CallLogDatabase(Path databasePath)
    {
        mDatabasePath = databasePath;
    }

    /**
     * Returns the database file path.
     */
    public Path getDatabasePath()
    {
        return mDatabasePath;
    }

    /**
     * Opens the database connection and creates tables/indexes if needed.
     *
     * @throws SQLException if connection cannot be established
     * @throws IOException if the parent directory cannot be created
     */
    public void open() throws SQLException, IOException
    {
        if(mConnection != null && !mConnection.isClosed())
        {
            return;
        }

        // Ensure parent directory exists
        Path parentDir = mDatabasePath.getParent();
        if(parentDir != null && !Files.exists(parentDir))
        {
            Files.createDirectories(parentDir);
        }

        String url = "jdbc:sqlite:" + mDatabasePath.toAbsolutePath();
        mConnection = DriverManager.getConnection(url);

        // Enable WAL mode for better concurrent read performance
        try(Statement stmt = mConnection.createStatement())
        {
            stmt.execute("PRAGMA journal_mode=WAL");
        }

        createTablesAndIndexes();
        mLog.info("Call log database opened: {}", mDatabasePath);
    }

    /**
     * Closes the database connection.
     */
    public void close()
    {
        if(mConnection != null)
        {
            try
            {
                mConnection.close();
                mLog.info("Call log database closed: {}", mDatabasePath);
            }
            catch(SQLException e)
            {
                mLog.error("Error closing call log database", e);
            }
            mConnection = null;
        }
    }

    /**
     * Returns true if the database connection is open.
     */
    public boolean isOpen()
    {
        try
        {
            return mConnection != null && !mConnection.isClosed();
        }
        catch(SQLException e)
        {
            return false;
        }
    }

    /**
     * Creates tables and indexes if they don't already exist.
     */
    private void createTablesAndIndexes() throws SQLException
    {
        try(Statement stmt = mConnection.createStatement())
        {
            stmt.execute(CREATE_SESSIONS_TABLE);
            stmt.execute(CREATE_EVENTS_TABLE);
            stmt.execute(CREATE_INDEX_SESSIONS_TIME);
            stmt.execute(CREATE_INDEX_SESSIONS_TALKGROUP);
            stmt.execute(CREATE_INDEX_SESSIONS_SYSTEM);
            stmt.execute(CREATE_INDEX_SESSIONS_INCIDENT);
            stmt.execute(CREATE_INDEX_SESSIONS_NO_TRANSCRIPT);
            stmt.execute(CREATE_INDEX_EVENTS_SESSION);
            stmt.execute(CREATE_INDEX_EVENTS_TIME);
            stmt.execute(CREATE_INDEX_EVENTS_FROM);
            stmt.execute(CREATE_INDEX_EVENTS_NO_TRANSCRIPT);
        }
    }

    /**
     * Inserts a completed session and all its events in a single atomic transaction.
     *
     * @param session the session record to insert
     * @param events the event records to insert (linked to the session)
     * @throws SQLException if the insert fails
     */
    public void insertSessionWithEvents(CallLogRecord session, List<CallEventRecord> events) throws SQLException
    {
        if(!isOpen())
        {
            throw new SQLException("Database is not open");
        }

        boolean previousAutoCommit = mConnection.getAutoCommit();
        mConnection.setAutoCommit(false);

        try
        {
            long sessionId = insertSession(session);

            for(CallEventRecord event : events)
            {
                event.setSessionId(sessionId);
                insertEvent(event);
            }

            mConnection.commit();
        }
        catch(SQLException e)
        {
            try
            {
                mConnection.rollback();
            }
            catch(SQLException rollbackEx)
            {
                mLog.error("Error rolling back transaction", rollbackEx);
            }
            throw e;
        }
        finally
        {
            mConnection.setAutoCommit(previousAutoCommit);
        }
    }

    /**
     * Inserts a session record and returns the auto-generated ID.
     */
    private long insertSession(CallLogRecord record) throws SQLException
    {
        try(PreparedStatement ps = mConnection.prepareStatement(INSERT_SESSION, Statement.RETURN_GENERATED_KEYS))
        {
            int i = 1;
            ps.setLong(i++, record.getTimeStart());
            ps.setLong(i++, record.getTimeEnd());
            ps.setLong(i++, record.getDurationMs());
            ps.setString(i++, record.getTalkgroupId());
            setStringOrNull(ps, i++, record.getTalkgroupAlias());
            setStringOrNull(ps, i++, record.getPatchGroupId());
            setStringOrNull(ps, i++, record.getPatchGroupMembers());
            ps.setString(i++, record.getEventType());
            ps.setDouble(i++, record.getFrequency());
            ps.setInt(i++, record.getTimeslot());
            setStringOrNull(ps, i++, record.getChannelDescriptor());
            ps.setInt(i++, record.getTalkerCount());
            ps.setInt(i++, record.isEncrypted() ? 1 : 0);
            ps.setInt(i++, record.isDuplicate() ? 1 : 0);
            setStringOrNull(ps, i++, record.getSystem());
            setStringOrNull(ps, i++, record.getSite());
            setStringOrNull(ps, i++, record.getChannelName());
            setStringOrNull(ps, i++, record.getFromId());
            setStringOrNull(ps, i++, record.getDetails());
            setStringOrNull(ps, i++, record.getServiceOptions());

            ps.executeUpdate();

            try(ResultSet rs = ps.getGeneratedKeys())
            {
                if(rs.next())
                {
                    long id = rs.getLong(1);
                    record.setId(id);
                    return id;
                }
            }

            throw new SQLException("Failed to retrieve generated session ID");
        }
    }

    /**
     * Inserts an event record.
     */
    private void insertEvent(CallEventRecord record) throws SQLException
    {
        try(PreparedStatement ps = mConnection.prepareStatement(INSERT_EVENT))
        {
            int i = 1;
            ps.setLong(i++, record.getSessionId());
            ps.setLong(i++, record.getTimeStart());
            ps.setLong(i++, record.getTimeEnd());
            ps.setLong(i++, record.getDurationMs());
            ps.setString(i++, record.getEventType());
            setStringOrNull(ps, i++, record.getFromId());
            setStringOrNull(ps, i++, record.getFromAlias());
            setStringOrNull(ps, i++, record.getToId());
            setStringOrNull(ps, i++, record.getToAlias());
            setStringOrNull(ps, i++, record.getDetails());
            setStringOrNull(ps, i++, record.getRecordingPath());
            setStringOrNull(ps, i++, record.getTranscript());

            ps.executeUpdate();
        }
    }

    private static final String QUERY_RECENT_SESSIONS = """
        SELECT id, time_start, time_end, duration_ms, talkgroup_id, talkgroup_alias,
               patch_group_id, patch_group_members, event_type, frequency, timeslot,
               channel_descriptor, talker_count, encrypted, duplicate, system, site,
               channel_name, from_id, details, service_options
        FROM call_sessions
        WHERE time_start >= ?
        ORDER BY time_start DESC
        LIMIT ?
        """;

    /**
     * Queries recent call sessions from the database.
     *
     * @param sinceTimestamp epoch millis — only return sessions started at or after this time
     * @param maxRows maximum number of rows to return
     * @return list of CallLogRecord, newest first
     * @throws SQLException if the query fails
     */
    public List<CallLogRecord> queryRecentSessions(long sinceTimestamp, int maxRows) throws SQLException
    {
        if(!isOpen())
        {
            throw new SQLException("Database is not open");
        }

        List<CallLogRecord> results = new ArrayList<>();

        try(PreparedStatement ps = mConnection.prepareStatement(QUERY_RECENT_SESSIONS))
        {
            ps.setLong(1, sinceTimestamp);
            ps.setInt(2, maxRows);

            try(ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                {
                    CallLogRecord record = new CallLogRecord();
                    record.setId(rs.getLong("id"));
                    record.setTimeStart(rs.getLong("time_start"));
                    record.setTimeEnd(rs.getLong("time_end"));
                    record.setDurationMs(rs.getLong("duration_ms"));
                    record.setTalkgroupId(rs.getString("talkgroup_id"));
                    record.setTalkgroupAlias(rs.getString("talkgroup_alias"));
                    record.setPatchGroupId(rs.getString("patch_group_id"));
                    record.setPatchGroupMembers(rs.getString("patch_group_members"));
                    record.setEventType(rs.getString("event_type"));
                    record.setFrequency(rs.getDouble("frequency"));
                    record.setTimeslot(rs.getInt("timeslot"));
                    record.setChannelDescriptor(rs.getString("channel_descriptor"));
                    record.setTalkerCount(rs.getInt("talker_count"));
                    record.setEncrypted(rs.getInt("encrypted") != 0);
                    record.setDuplicate(rs.getInt("duplicate") != 0);
                    record.setSystem(rs.getString("system"));
                    record.setSite(rs.getString("site"));
                    record.setChannelName(rs.getString("channel_name"));
                    record.setFromId(rs.getString("from_id"));
                    record.setDetails(rs.getString("details"));
                    record.setServiceOptions(rs.getString("service_options"));
                    results.add(record);
                }
            }
        }

        return results;
    }

    /**
     * Sets a string parameter or NULL if the value is null.
     */
    private static void setStringOrNull(PreparedStatement ps, int index, String value) throws SQLException
    {
        if(value != null)
        {
            ps.setString(index, value);
        }
        else
        {
            ps.setNull(index, Types.VARCHAR);
        }
    }
}
