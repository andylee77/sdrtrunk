/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

package io.github.dsheirer.module.decode.event;

import io.github.dsheirer.filter.FilterSet;
import java.awt.EventQueue;
import java.util.LinkedList;
import java.util.List;

import javax.swing.table.AbstractTableModel;

/**
 * AbstractTableModel implementation supporting clearable method options, pre-filtering, and save-to-file callbacks.
 */
public abstract class ClearableHistoryModel<T> extends AbstractTableModel
{
    public static final int DEFAULT_HISTORY_SIZE = 200;
    private LinkedList<T> mItems = new LinkedList<>();
    private int mHistorySize = DEFAULT_HISTORY_SIZE;
    private FilterSet<T> mFilterSet;
    private boolean mPreFilterEnabled = false;
    private ISaveEventListener<T> mSaveEventListener;
    private boolean mSaveEnabled = false;

    /**
     * Callback interface for saving events that pass the filter to an external writer.
     */
    public interface ISaveEventListener<T>
    {
        /**
         * Called when an event passes the filter and should be written to the save file.
         * @param item the event to save
         */
        void onEventSave(T item);
    }

    /**
     * Access an item/row by the model index value.
     * @param index to retrieve
     * @return item or null
     */
    public T getItem(int index)
    {
        if(index < mItems.size())
        {
            return mItems.get(index);
        }

        return null;
    }

    /**
     * Adds the item to the top of the item list and removes any tail items while the item list size exceeds the
     * maximum history size for this model.
     *
     * If pre-filter is enabled, items that don't pass the filter are dropped and never stored.
     * If save is enabled, items that pass the filter are forwarded to the save listener.
     *
     * @param item to add
     */
    public void add(T item)
    {
        boolean passesFilter = true;

        if(mFilterSet != null)
        {
            passesFilter = mFilterSet.canProcess(item) && mFilterSet.passes(item);
        }

        // Save filtered events to file if save is enabled
        if(mSaveEnabled && mSaveEventListener != null && passesFilter)
        {
            mSaveEventListener.onEventSave(item);
        }

        // Pre-filter: drop items that don't pass the filter before they consume buffer space
        if(mPreFilterEnabled && !passesFilter)
        {
            return;
        }

        if(mItems.contains(item))
        {
            int itemRow = mItems.indexOf(item);
            fireTableRowsUpdated(itemRow, itemRow);
        }
        else
        {
            mItems.addFirst(item);
            fireTableRowsInserted(0, 0);

            while(mItems.size() > mHistorySize)
            {
                mItems.removeLast();
                fireTableRowsDeleted(mItems.size() - 1, mItems.size() - 1);
            }
        }
    }

    /**
     * Clears all messages from history
     */
    public void clear()
    {
        EventQueue.invokeLater(() -> {
            mItems.clear();
            fireTableDataChanged();
        });
    }

    /**
     * Clears the current messages and loads the messages argument
     */
    public void clearAndSet(List<T> items)
    {
        EventQueue.invokeLater(() -> {
            mItems.clear();
            fireTableDataChanged();
            for(T item: items)
            {
                add(item);
            }
        });
    }

    /**
     * Current history size
     * @return history size
     */
    public int getHistorySize()
    {
        return mHistorySize;
    }

    /**
     * Sets the history size
     * @param historySize
     */
    public void setHistorySize(int historySize)
    {
        mHistorySize = historySize;
    }

    /**
     * Sets the filter set used for pre-filtering and save-filtering.
     * @param filterSet to use
     */
    public void setFilterSet(FilterSet<T> filterSet)
    {
        mFilterSet = filterSet;
    }

    /**
     * Enables or disables pre-filtering. When enabled, events that don't pass the filter
     * are dropped entirely and don't consume buffer space.
     * @param enabled true to enable pre-filtering
     */
    public void setPreFilterEnabled(boolean enabled)
    {
        mPreFilterEnabled = enabled;
    }

    /**
     * Indicates if pre-filtering is enabled.
     * @return true if pre-filtering is enabled
     */
    public boolean isPreFilterEnabled()
    {
        return mPreFilterEnabled;
    }

    /**
     * Enables or disables saving filtered events to file.
     * @param enabled true to enable saving
     */
    public void setSaveEnabled(boolean enabled)
    {
        mSaveEnabled = enabled;
    }

    /**
     * Indicates if saving is enabled.
     * @return true if saving is enabled
     */
    public boolean isSaveEnabled()
    {
        return mSaveEnabled;
    }

    /**
     * Sets the save event listener that receives events to write to file.
     * @param listener to receive save events, or null to clear
     */
    public void setSaveEventListener(ISaveEventListener<T> listener)
    {
        mSaveEventListener = listener;
    }

    @Override
    public int getRowCount()
    {
        return mItems.size();
    }
}
