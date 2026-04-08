/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
package io.github.dsheirer.gui.analyzer.ui;

import io.github.dsheirer.gui.analyzer.detection.DetectedSignal;
import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;

/**
 * Table model for the signal analysis table.
 * Columns: Frequency, Power, BW, Modulation, SymRate, Protocol, Confidence, Flags, Status
 */
public class SignalAnalysisTableModel extends AbstractTableModel
{
    private static final String[] COLUMN_NAMES = {
        "Frequency (MHz)", "Power (dB)", "BW (kHz)", "Modulation",
        "Sym Rate", "Protocol", "Confidence", "System Info", "Flags", "Status"
    };

    private static final Class<?>[] COLUMN_CLASSES = {
        Double.class, Double.class, Double.class, String.class,
        Integer.class, String.class, String.class, String.class, String.class, String.class
    };

    public static final int COL_FREQUENCY = 0;
    public static final int COL_POWER = 1;
    public static final int COL_BANDWIDTH = 2;
    public static final int COL_MODULATION = 3;
    public static final int COL_SYMBOL_RATE = 4;
    public static final int COL_PROTOCOL = 5;
    public static final int COL_CONFIDENCE = 6;
    public static final int COL_SYSTEM_INFO = 7;
    public static final int COL_FLAGS = 8;
    public static final int COL_STATUS = 9;

    private final List<DetectedSignal> mSignals = new ArrayList<>();

    public SignalAnalysisTableModel()
    {
    }

    @Override
    public int getRowCount()
    {
        return mSignals.size();
    }

    @Override
    public int getColumnCount()
    {
        return COLUMN_NAMES.length;
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
        if(column >= 0 && column < COLUMN_CLASSES.length)
        {
            return COLUMN_CLASSES[column];
        }
        return String.class;
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex)
    {
        return false;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex)
    {
        if(rowIndex < 0 || rowIndex >= mSignals.size())
        {
            return null;
        }

        DetectedSignal signal = mSignals.get(rowIndex);

        switch(columnIndex)
        {
            case COL_FREQUENCY:
                return signal.getFrequencyMHz();
            case COL_POWER:
                return signal.getPowerDb();
            case COL_BANDWIDTH:
                return signal.getBandwidthKHz();
            case COL_MODULATION:
                return signal.getModulationType().getShortName();
            case COL_SYMBOL_RATE:
                int rate = signal.getSymbolRate();
                return rate > 0 ? rate : null;
            case COL_PROTOCOL:
                if(signal.getIdentifiedDecoder() != null)
                {
                    return signal.getIdentifiedDecoder().getShortDisplayString();
                }
                return "\u2014"; // em-dash
            case COL_CONFIDENCE:
                String conf = signal.getIdentificationConfidence();
                return conf != null ? conf : "";
            case COL_SYSTEM_INFO:
                return signal.getSystemInfoDisplay();
            case COL_FLAGS:
                return signal.getFlagsDisplayString();
            case COL_STATUS:
                return signal.getStatus().getLabel();
            default:
                return null;
        }
    }

    /**
     * Get the signal at the specified row index.
     */
    public DetectedSignal getSignalAt(int rowIndex)
    {
        if(rowIndex >= 0 && rowIndex < mSignals.size())
        {
            return mSignals.get(rowIndex);
        }
        return null;
    }

    /**
     * Update the signal list. Replaces all signals and fires a table data change event.
     * This method should be called on the EDT.
     */
    public void updateSignals(List<DetectedSignal> newSignals)
    {
        mSignals.clear();
        mSignals.addAll(newSignals);
        fireTableDataChanged();
    }

    /**
     * Remove a specific signal from the table.
     */
    public void removeSignal(DetectedSignal signal)
    {
        int index = mSignals.indexOf(signal);
        if(index >= 0)
        {
            mSignals.remove(index);
            fireTableRowsDeleted(index, index);
        }
    }

    /**
     * Clear all signals from the table.
     */
    public void clear()
    {
        int size = mSignals.size();
        if(size > 0)
        {
            mSignals.clear();
            fireTableRowsDeleted(0, size - 1);
        }
    }

    /**
     * Get the total number of signals.
     */
    public int getSignalCount()
    {
        return mSignals.size();
    }

    /**
     * Get signals that are not flagged as artifacts.
     */
    public List<DetectedSignal> getValidSignals()
    {
        List<DetectedSignal> valid = new ArrayList<>();
        for(DetectedSignal signal : mSignals)
        {
            if(!signal.isArtifact())
            {
                valid.add(signal);
            }
        }
        return valid;
    }
}
