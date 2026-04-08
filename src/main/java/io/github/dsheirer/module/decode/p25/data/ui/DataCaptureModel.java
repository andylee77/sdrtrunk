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
package io.github.dsheirer.module.decode.p25.data.ui;

import io.github.dsheirer.module.decode.p25.data.CapturedPayload;
import io.github.dsheirer.sample.Listener;
import java.awt.EventQueue;
import java.util.LinkedList;
import javax.swing.table.AbstractTableModel;

/**
 * Table model for displaying captured P25 payload data. Implements Listener<CapturedPayload>
 * to receive real-time data from the P25DataCaptureModule.
 *
 * Maintains a bounded history (newest at top) and fires table model events on the EDT.
 */
public class DataCaptureModel extends AbstractTableModel implements Listener<CapturedPayload>
{
    private static final long serialVersionUID = 1L;

    public static final int COLUMN_TIME = 0;
    public static final int COLUMN_TYPE = 1;
    public static final int COLUMN_MODE = 2;
    public static final int COLUMN_CHANNEL = 3;
    public static final int COLUMN_FREQ = 4;
    public static final int COLUMN_SAP_OPCODE = 5;
    public static final int COLUMN_FROM = 6;
    public static final int COLUMN_TO = 7;
    public static final int COLUMN_LENGTH = 8;
    public static final int COLUMN_PROTOCOL = 9;
    public static final int COLUMN_GPS = 10;
    public static final int COLUMN_HEX = 11;
    public static final int COLUMN_STRINGS = 12;
    public static final int COLUMN_DETAILS = 13;
    public static final int COLUMN_COUNT = 14;

    private static final String[] COLUMN_NAMES = {
        "Time", "Type", "Mode", "Channel", "Freq (MHz)", "SAP/Opcode", "From", "To", "Len", "Protocol", "GPS", "Hex", "Strings", "Details"
    };

    private static final int MAX_HISTORY = 500;

    private final LinkedList<CapturedPayload> mPayloads = new LinkedList<>();

    public DataCaptureModel()
    {
    }

    /**
     * Receives a new captured payload from the P25DataCaptureModule.
     * Adds to the front of the list and trims if exceeding max history.
     *
     * Change 021: Safety filter — skip zero-payload records at the UI level.
     * The primary filter is in P25DataCaptureModule.emit(), but this provides
     * belt-and-suspenders protection so the Data tab never shows empty records.
     */
    @Override
    public void receive(CapturedPayload payload)
    {
        // Safety: skip zero-payload records (primary filter is in P25DataCaptureModule.emit)
        if(payload.getPayloadLength() == 0)
        {
            return;
        }

        EventQueue.invokeLater(() -> {
            mPayloads.addFirst(payload);

            if(mPayloads.size() > MAX_HISTORY)
            {
                mPayloads.removeLast();
            }

            fireTableDataChanged();
        });
    }

    /**
     * Clears all captured payloads.
     */
    public void clear()
    {
        EventQueue.invokeLater(() -> {
            mPayloads.clear();
            fireTableDataChanged();
        });
    }

    @Override
    public int getRowCount()
    {
        return mPayloads.size();
    }

    @Override
    public int getColumnCount()
    {
        return COLUMN_COUNT;
    }

    @Override
    public String getColumnName(int column)
    {
        if(column >= 0 && column < COLUMN_NAMES.length)
        {
            return COLUMN_NAMES[column];
        }
        return "";
    }

    @Override
    public Class<?> getColumnClass(int column)
    {
        switch(column)
        {
            case COLUMN_TIME:
                return Long.class;
            case COLUMN_LENGTH:
                return Integer.class;
            default:
                return String.class;
        }
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex)
    {
        if(rowIndex < 0 || rowIndex >= mPayloads.size())
        {
            return null;
        }

        CapturedPayload cp = mPayloads.get(rowIndex);

        switch(columnIndex)
        {
            case COLUMN_TIME:
                return cp.getTimestamp();
            case COLUMN_TYPE:
                return cp.getType().getShortLabel();
            case COLUMN_MODE:
                return cp.getMode();
            case COLUMN_CHANNEL:
                return cp.getChannel();
            case COLUMN_FREQ:
                return cp.getFrequencyDisplay();
            case COLUMN_SAP_OPCODE:
                return cp.getSapOrOpcode();
            case COLUMN_FROM:
                return cp.getFromId();
            case COLUMN_TO:
                return cp.getToId();
            case COLUMN_LENGTH:
                return cp.getPayloadLength();
            case COLUMN_PROTOCOL:
                return cp.getDetectedProtocol();
            case COLUMN_GPS:
                return cp.getGpsDisplay();
            case COLUMN_HEX:
                return cp.getHexDump();
            case COLUMN_STRINGS:
                return cp.getDetectedStringsDisplay();
            case COLUMN_DETAILS:
                return cp.getDetails();
            default:
                return null;
        }
    }

    /**
     * Returns the CapturedPayload at the given model row index, or null if out of bounds.
     */
    public CapturedPayload getPayloadAt(int rowIndex)
    {
        if(rowIndex >= 0 && rowIndex < mPayloads.size())
        {
            return mPayloads.get(rowIndex);
        }
        return null;
    }
}
