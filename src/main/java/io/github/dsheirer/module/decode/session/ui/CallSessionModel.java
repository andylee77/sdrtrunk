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
package io.github.dsheirer.module.decode.session.ui;

import io.github.dsheirer.calllog.CallLogRecord;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.session.CallSession;
import io.github.dsheirer.module.decode.session.CallSessionEvent;
import io.github.dsheirer.module.decode.session.CallSessionListener;
import java.awt.EventQueue;
import java.util.LinkedList;
import java.util.List;
import javax.swing.table.AbstractTableModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Swing TableModel for displaying call rows in the Calls tab.
 *
 * Supports two row types:
 * - CallSessionEvent (live per-talker events from real-time monitoring)
 * - CallLogRecord (historical session records loaded from SQLite database)
 *
 * Each row is stored as an Object in the list — getValueAt() dispatches based on type.
 * Historical records are appended after live events, newest first.
 *
 * Implements CallSessionListener to receive real-time updates from the P25CallSessionManager.
 */
public class CallSessionModel extends AbstractTableModel implements CallSessionListener
{
    private static final long serialVersionUID = 1L;
    private static final Logger mLog = LoggerFactory.getLogger(CallSessionModel.class);

    public static final int MAX_ROWS = 2000;

    public static final int COLUMN_TIME = 0;
    public static final int COLUMN_DURATION = 1;
    public static final int COLUMN_EVENT = 2;
    public static final int COLUMN_FROM_ID = 3;
    public static final int COLUMN_FROM_ALIAS = 4;
    public static final int COLUMN_TO_ID = 5;
    public static final int COLUMN_TO_ALIAS = 6;
    public static final int COLUMN_PATCH_GROUP = 7;
    public static final int COLUMN_CHANNEL = 8;
    public static final int COLUMN_FREQUENCY = 9;
    public static final int COLUMN_DETAILS = 10;

    private static final String[] HEADERS = {
        "Time", "Duration", "Event", "From", "Alias", "To", "Alias",
        "Patch Group", "Channel", "Frequency", "Details"
    };

    /** Holds both CallSessionEvent and CallLogRecord objects, newest first */
    private final LinkedList<Object> mRows = new LinkedList<>();

    /** Index where historical records start (live events are 0..mHistoryStartIndex-1) */
    private int mHistoryStartIndex = 0;

    /** Alias list name from the channel config — used to build synthetic ICs for historical records */
    private String mAliasListName;

    public CallSessionModel()
    {
    }

    /**
     * Sets the alias list name used to build synthetic IdentifierCollections for historical records.
     * Called when a channel is selected.
     */
    public void setAliasListName(String aliasListName)
    {
        mAliasListName = aliasListName;
    }

    /**
     * Clears all rows from the model. If already on the EDT, executes immediately.
     * Otherwise schedules on the EDT.
     */
    public void clear()
    {
        if(EventQueue.isDispatchThread())
        {
            clearImmediate();
        }
        else
        {
            EventQueue.invokeLater(this::clearImmediate);
        }
    }

    /**
     * Immediately clears all rows. Must be called on the EDT.
     */
    private void clearImmediate()
    {
        int size = mRows.size();
        if(size > 0)
        {
            mRows.clear();
            mHistoryStartIndex = 0;
            fireTableRowsDeleted(0, size - 1);
        }
    }

    /**
     * Loads historical records from the database. Replaces any existing history rows
     * but preserves live event rows.
     */
    public void loadHistory(List<CallLogRecord> records)
    {
        EventQueue.invokeLater(() -> {
            // Remove existing history rows
            int oldSize = mRows.size();
            if(oldSize > mHistoryStartIndex)
            {
                for(int i = oldSize - 1; i >= mHistoryStartIndex; i--)
                {
                    mRows.remove(i);
                }
                fireTableRowsDeleted(mHistoryStartIndex, oldSize - 1);
            }

            // Add new history rows
            if(records != null && !records.isEmpty())
            {
                int insertStart = mRows.size();
                mRows.addAll(records);

                // Trim total if over max
                while(mRows.size() > MAX_ROWS)
                {
                    mRows.removeLast();
                }

                int insertEnd = mRows.size() - 1;
                if(insertEnd >= insertStart)
                {
                    fireTableRowsInserted(insertStart, insertEnd);
                }
            }
        });
    }

    /**
     * Returns the raw row object at the specified index.
     */
    public Object getRow(int rowIndex)
    {
        if(rowIndex >= 0 && rowIndex < mRows.size())
        {
            return mRows.get(rowIndex);
        }
        return null;
    }

    /**
     * Returns the event type string for a row — used by filter.
     */
    public String getEventTypeString(int rowIndex)
    {
        Object row = getRow(rowIndex);
        if(row instanceof CallSessionEvent cse)
        {
            return cse.getEventType() != null ? cse.getEventType().getLabel() : null;
        }
        else if(row instanceof CallLogRecord clr)
        {
            return clr.getEventType();
        }
        return null;
    }

    /**
     * Returns true if the row is encrypted.
     * Checks both the event type label and the EncryptionKeyIdentifier in the IdentifierCollection.
     */
    public boolean isEncrypted(int rowIndex)
    {
        Object row = getRow(rowIndex);
        if(row instanceof CallSessionEvent cse)
        {
            // Check event type label
            String eventLabel = cse.getEventType() != null ? cse.getEventType().getLabel() : "";
            if(eventLabel.contains("Encrypted"))
            {
                return true;
            }

            // Check EncryptionKeyIdentifier in the IdentifierCollection
            IdentifierCollection ic = cse.getIdentifierCollection();
            if(ic != null)
            {
                Identifier encId = ic.getIdentifier(IdentifierClass.USER, Form.ENCRYPTION_KEY, Role.ANY);
                if(encId instanceof EncryptionKeyIdentifier eki && eki.isEncrypted())
                {
                    return true;
                }
            }

            // Fallback: check details string for ENCRYPTED
            String details = cse.getDetails();
            if(details != null && details.toUpperCase().contains("ENCRYPTED"))
            {
                return true;
            }

            return false;
        }
        else if(row instanceof CallLogRecord clr)
        {
            if(clr.isEncrypted())
            {
                return true;
            }
            // Also check event type string (e.g. "Encrypted Group Call")
            String clrEventType = clr.getEventType();
            if(clrEventType != null && clrEventType.contains("Encrypted"))
            {
                return true;
            }
            // Also check details string
            String clrDetails = clr.getDetails();
            if(clrDetails != null && clrDetails.toUpperCase().contains("ENCRYPTED"))
            {
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * Returns true if the row is a data call.
     */
    public boolean isDataCall(int rowIndex)
    {
        String eventType = getEventTypeString(rowIndex);
        return eventType != null && eventType.toLowerCase().contains("data");
    }

    /**
     * Returns true if the row details indicate it was unmonitored/ignored.
     */
    public boolean isUnmonitored(int rowIndex)
    {
        Object row = getRow(rowIndex);
        String details = null;
        if(row instanceof CallSessionEvent cse)
        {
            details = cse.getDetails();
        }
        else if(row instanceof CallLogRecord clr)
        {
            details = clr.getDetails();
        }
        return details != null && details.contains("IGNORED");
    }

    // ========================================================================
    // CallSessionListener implementation
    // ========================================================================

    /**
     * Backfills the model with existing active session events. Called when switching
     * back to a channel to restore live events that would otherwise be lost.
     * Events are added newest-first to match the normal insertion order.
     *
     * @param events list of CallSessionEvents from active/ending sessions
     */
    public void backfillEvents(List<CallSessionEvent> events)
    {
        if(events == null || events.isEmpty())
        {
            return;
        }

        EventQueue.invokeLater(() -> {
            // Sort events newest-first by start time so they appear in correct order
            List<CallSessionEvent> sorted = new java.util.ArrayList<>(events);
            sorted.sort((a, b) -> Long.compare(b.getTimeStart(), a.getTimeStart()));

            for(CallSessionEvent event : sorted)
            {
                // Only add if not already present (avoid duplicates)
                if(findEventIndex(event) < 0)
                {
                    mRows.addFirst(event);
                    mHistoryStartIndex++;
                }
            }

            if(!sorted.isEmpty())
            {
                fireTableDataChanged();
            }
        });
    }

    @Override
    public void onSessionCreated(CallSession session)
    {
        // No-op — no events yet at creation time
    }

    @Override
    public void onSessionEventAdded(CallSession session, CallSessionEvent event)
    {
        EventQueue.invokeLater(() -> {
            mRows.addFirst(event);
            mHistoryStartIndex++;
            fireTableRowsInserted(0, 0);

            // Trim if over max
            while(mRows.size() > MAX_ROWS)
            {
                int lastIndex = mRows.size() - 1;
                mRows.removeLast();
                fireTableRowsDeleted(lastIndex, lastIndex);
            }
        });
    }

    @Override
    public void onSessionEventUpdated(CallSession session, CallSessionEvent event)
    {
        EventQueue.invokeLater(() -> {
            int index = findEventIndex(event);
            if(index >= 0)
            {
                fireTableRowsUpdated(index, index);
            }
        });
    }

    @Override
    public void onSessionComplete(CallSession session)
    {
        // No visible change — events are already displayed
    }

    /**
     * Finds the row index of a specific event instance (by identity, not equality).
     */
    private int findEventIndex(CallSessionEvent event)
    {
        for(int i = 0; i < mRows.size(); i++)
        {
            if(mRows.get(i) == event)
            {
                return i;
            }
        }
        return -1;
    }

    // ========================================================================
    // AbstractTableModel implementation
    // ========================================================================

    @Override
    public int getRowCount()
    {
        return mRows.size();
    }

    @Override
    public int getColumnCount()
    {
        return HEADERS.length;
    }

    @Override
    public String getColumnName(int column)
    {
        return HEADERS[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex)
    {
        return switch(columnIndex)
        {
            case COLUMN_TIME, COLUMN_DURATION -> Long.class;
            case COLUMN_EVENT, COLUMN_DETAILS -> String.class;
            case COLUMN_FROM_ID, COLUMN_FROM_ALIAS, COLUMN_TO_ID, COLUMN_TO_ALIAS,
                 COLUMN_PATCH_GROUP -> Object.class;
            case COLUMN_CHANNEL -> String.class;
            case COLUMN_FREQUENCY -> Object.class;
            default -> super.getColumnClass(columnIndex);
        };
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex)
    {
        Object row = getRow(rowIndex);

        if(row == null)
        {
            return null;
        }

        if(row instanceof CallSessionEvent event)
        {
            return getValueFromEvent(event, columnIndex);
        }
        else if(row instanceof CallLogRecord record)
        {
            return getValueFromRecord(record, columnIndex);
        }

        return null;
    }

    private Object getValueFromEvent(CallSessionEvent event, int columnIndex)
    {
        return switch(columnIndex)
        {
            case COLUMN_TIME -> event.getTimeStart();
            case COLUMN_DURATION -> event.getDuration();
            case COLUMN_EVENT -> event.getEventType() != null ? event.getEventType().getLabel() : null;
            case COLUMN_FROM_ID -> event.getIdentifierCollection();
            case COLUMN_FROM_ALIAS -> event.getIdentifierCollection();
            case COLUMN_TO_ID -> event.getIdentifierCollection();
            case COLUMN_TO_ALIAS -> event.getIdentifierCollection();
            case COLUMN_PATCH_GROUP -> event.getIdentifierCollection();
            case COLUMN_CHANNEL -> formatChannel(event);
            case COLUMN_FREQUENCY -> event.getChannelDescriptor();
            case COLUMN_DETAILS -> event.getDetails();
            default -> null;
        };
    }

    private Object getValueFromRecord(CallLogRecord record, int columnIndex)
    {
        return switch(columnIndex)
        {
            case COLUMN_TIME -> record.getTimeStart();
            case COLUMN_DURATION -> record.getDurationMs();
            case COLUMN_EVENT -> record.getEventType();
            case COLUMN_FROM_ID -> record.getIdentifierCollection(mAliasListName);
            case COLUMN_FROM_ALIAS -> record.getIdentifierCollection(mAliasListName);
            case COLUMN_TO_ID -> record.getIdentifierCollection(mAliasListName);
            case COLUMN_TO_ALIAS -> record.getIdentifierCollection(mAliasListName);
            case COLUMN_PATCH_GROUP -> formatPatchGroupFromRecord(record);
            case COLUMN_CHANNEL -> record.getChannelDescriptor();
            case COLUMN_FREQUENCY -> record.getFrequency();
            case COLUMN_DETAILS -> record.getDetails();
            default -> null;
        };
    }

    /**
     * Formats the channel descriptor + timeslot for display (live events).
     */
    private String formatChannel(CallSessionEvent event)
    {
        IChannelDescriptor descriptor = event.getChannelDescriptor();

        if(descriptor != null)
        {
            if(event.hasTimeslot())
            {
                return descriptor + " TS" + event.getTimeslot();
            }
            return descriptor.toString();
        }
        else if(event.hasTimeslot())
        {
            return "TS" + event.getTimeslot();
        }

        return null;
    }

    /**
     * Formats patch group info from a historical record.
     */
    private String formatPatchGroupFromRecord(CallLogRecord record)
    {
        if(record.getPatchGroupId() != null)
        {
            StringBuilder sb = new StringBuilder("P:");
            sb.append(record.getPatchGroupId());
            if(record.getPatchGroupMembers() != null)
            {
                sb.append(" [").append(record.getPatchGroupMembers()).append("]");
            }
            return sb.toString();
        }
        return null;
    }
}
