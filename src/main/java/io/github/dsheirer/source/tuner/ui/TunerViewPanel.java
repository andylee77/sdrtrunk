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

package io.github.dsheirer.source.tuner.ui;

import com.jidesoft.swing.JideSplitPane;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.swing.JTableColumnWidthMonitor;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredRecordingTuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.manager.TunerStatus;
import io.github.dsheirer.preference.source.ChannelizerType;
import io.github.dsheirer.source.tuner.plutosdr.AddPlutoSdrTunerDialog;
import io.github.dsheirer.source.tuner.plutosdr.DiscoveredPlutoSdrTuner;
import io.github.dsheirer.source.tuner.recording.AddRecordingTunerDialog;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.List;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingWorker;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;

/**
 * Panel containing a discovered tuners table and a tuner editor for a selected tuner.
 */
public class TunerViewPanel extends JPanel
{
    private static final long serialVersionUID = 1L;
    private final static Logger mLog = LoggerFactory.getLogger(TunerViewPanel.class);
    private static final String TABLE_PREFERENCE_KEY = "tuner.view.panel";

    private UserPreferences mUserPreferences;
    private TunerManager mTunerManager;
    private PlaylistManager mPlaylistManager;
    private DiscoveredTunerModel mDiscoveredTunerModel;
    private DiscoveredTunerEditor mDiscoveredTunerEditor;
    private TunerConfigurationManager mTunerConfigurationManager;
    private JTable mTunerTable;
    private JTableColumnWidthMonitor mColumnWidthMonitor;
    private TableRowSorter<DiscoveredTunerModel> mRowSorter;
    private JideSplitPane mSplitPane;
    private JButton mAddRecordingButton;
    private JButton mRemoveRecordingButton;
    private JButton mAddPlutoSdrButton;
    private JButton mRemovePlutoSdrButton;
    private JButton mStopAllChannelsButton;
    private JButton mStartAutostartChannelsButton;

    /**
     * Constructs an instance
     * @param tunerManager for tuners
     * @param userPreferences for making recordings in the tuner editor
     * @param playlistManager for channel start/stop operations
     */
    public TunerViewPanel(TunerManager tunerManager, UserPreferences userPreferences, PlaylistManager playlistManager)
    {
        mTunerManager = tunerManager;
        mDiscoveredTunerModel = tunerManager.getDiscoveredTunerModel();
        mDiscoveredTunerEditor = new DiscoveredTunerEditor(userPreferences, tunerManager);
        mTunerConfigurationManager = tunerManager.getTunerConfigurationManager();
        mUserPreferences = userPreferences;
        mPlaylistManager = playlistManager;
        init();
    }

    /**
     * Constructs an instance (without playlist manager - channel control buttons will be hidden)
     * @param tunerManager for tuners
     * @param userPreferences for making recordings in the tuner editor
     */
    public TunerViewPanel(TunerManager tunerManager, UserPreferences userPreferences)
    {
        this(tunerManager, userPreferences, null);
    }

    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0", "[fill,grow]", "[fill,grow]"));

        mRowSorter = new TableRowSorter<>(mDiscoveredTunerModel);
        List<RowSorter.SortKey> sortKeys = new ArrayList<>();
        sortKeys.add(new RowSorter.SortKey(DiscoveredTunerModel.COLUMN_TUNER_TYPE, SortOrder.ASCENDING));
        mRowSorter.setSortKeys(sortKeys);

        mTunerTable = new JTable(mDiscoveredTunerModel);
        mTunerTable.setRowSorter(mRowSorter);
        mTunerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mTunerTable.getSelectionModel().addListSelectionListener(event ->
        {
            getRemoveRecordingButton().setEnabled(false);
            getRemovePlutoSdrButton().setEnabled(false);

            if(!event.getValueIsAdjusting())
            {
                int row = mTunerTable.getSelectedRow();

                if(row >= 0)
                {
                    int modelRow = mTunerTable.convertRowIndexToModel(row);

                    DiscoveredTuner selected = mDiscoveredTunerModel.getDiscoveredTuner(modelRow);
                    mDiscoveredTunerEditor.setItem(selected);
                    getRemoveRecordingButton().setEnabled(selected instanceof DiscoveredRecordingTuner);
                    getRemovePlutoSdrButton().setEnabled(selected instanceof DiscoveredPlutoSdrTuner);
                }
            }
        });

        //Add support for right-click context menu to the tuner table
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem logStateMenuItem = new JMenuItem("Log Tuner State");
        logStateMenuItem.addActionListener(e -> {
            int viewRow = mTunerTable.getSelectedRow();

            DiscoveredTuner selected = null;

            if(viewRow >= 0)
            {
                int modelRow = mTunerTable.convertRowIndexToModel(viewRow);
                selected = mDiscoveredTunerModel.getDiscoveredTuner(modelRow);
            }

            if(selected != null)
            {
                selected.logState();
            }
            else
            {
                mLog.error("Can't log state - tuner not selected");
            }
        });
        popupMenu.add(logStateMenuItem);
        mTunerTable.setComponentPopupMenu(popupMenu);

        //Monitor for tuner removal events so we can update the editor when our selected tuner is removed
        mDiscoveredTunerModel.addTableModelListener(e ->
        {
            //Detect when status is for the currently selected tuner
            if(e.getType() == TableModelEvent.DELETE &&
                mDiscoveredTunerEditor.hasItem() &&
                !mDiscoveredTunerModel.hasTuner(mDiscoveredTunerEditor.getItem()))
            {
                mDiscoveredTunerEditor.setItem(null);
            }
        });

        mDiscoveredTunerModel.addListener(tunerEvent ->
        {
            switch(tunerEvent.getEvent())
            {
                case UPDATE_LOCK_STATE:
                    if(tunerEvent.getTuner() != null)
                    {
                        int row = mTunerTable.getSelectedRow();

                        if(row >= 0)
                        {
                            int modelRow = mTunerTable.convertRowIndexToModel(row);
                            DiscoveredTuner selectedTuner = mDiscoveredTunerModel.getDiscoveredTuner(modelRow);

                            if(selectedTuner != null && selectedTuner.hasTuner() && tunerEvent.getTuner() == selectedTuner.getTuner())
                            {
                                mDiscoveredTunerEditor.setTunerLockState(selectedTuner.getTuner().getTunerController().isLockedSampleRate());
                            }
                        }
                    }
                    break;
            }
        });

        TableCellRenderer errorCellRenderer = new TunerStatusCellRenderer();
        mTunerTable.getColumnModel().getColumn(DiscoveredTunerModel.COLUMN_TUNER_STATUS).setCellRenderer(errorCellRenderer);

        mColumnWidthMonitor = new JTableColumnWidthMonitor(mUserPreferences, mTunerTable, TABLE_PREFERENCE_KEY);
        JScrollPane tunerTableScroller = new JScrollPane(mTunerTable);

        JPanel tunerTablePanel = new JPanel();
        tunerTablePanel.setLayout(new MigLayout("insets 0", "[fill,grow,align center]", "[fill,grow][]"));
        tunerTablePanel.add(tunerTableScroller, "span");

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new MigLayout("insets 0 1 3 0", "", ""));
        buttonPanel.add(getAddRecordingButton());
        buttonPanel.add(getRemoveRecordingButton());
        buttonPanel.add(getAddPlutoSdrButton());
        buttonPanel.add(getRemovePlutoSdrButton());

        if(mPlaylistManager != null)
        {
            buttonPanel.add(getStopAllChannelsButton());
            buttonPanel.add(getStartAutostartChannelsButton());
        }

        tunerTablePanel.add(buttonPanel);

        tunerTablePanel.setPreferredSize(new Dimension(200,200));
        JScrollPane editorScroller = new JScrollPane(mDiscoveredTunerEditor);
        editorScroller.setPreferredSize(new Dimension(200, 200));

        mSplitPane = new JideSplitPane();
        mSplitPane.setOrientation(JideSplitPane.HORIZONTAL_SPLIT);
        mSplitPane.add(tunerTablePanel);
        mSplitPane.add(editorScroller);
        mSplitPane.setProportionalLayout(true);
        mSplitPane.setProportions(new double[]{0.5});

        add(mSplitPane);
    }

    private JButton getAddRecordingButton()
    {
        if(mAddRecordingButton == null)
        {
            mAddRecordingButton = new JButton("Add Recording Tuner");
            mAddRecordingButton.addActionListener(e ->
            {
                AddRecordingTunerDialog dialog = new AddRecordingTunerDialog(mUserPreferences, mDiscoveredTunerModel,
                        mTunerConfigurationManager);
                dialog.setLocationRelativeTo(TunerViewPanel.this);
                EventQueue.invokeLater(() -> dialog.setVisible(true));
            });
        }

        return mAddRecordingButton;
    }

    private JButton getRemoveRecordingButton()
    {
        if(mRemoveRecordingButton == null)
        {
            mRemoveRecordingButton = new JButton("Remove Recording Tuner");
            mRemoveRecordingButton.setEnabled(false);
            mRemoveRecordingButton.addActionListener(e -> {
                int[] indexes = mTunerTable.getSelectionModel().getSelectedIndices();

                //With single selection mode this should always be length one
                if(indexes.length == 1)
                {
                    int modelIndex = mTunerTable.convertRowIndexToModel(indexes[0]);
                    DiscoveredTuner selected = mDiscoveredTunerModel.getDiscoveredTuner(modelIndex);

                    if(selected instanceof DiscoveredRecordingTuner discoveredRecordingTuner)
                    {
                        mLog.info("Removing Tuner: " + discoveredRecordingTuner);
                        discoveredRecordingTuner.stop();
                        mTunerConfigurationManager.removeTunerConfiguration(discoveredRecordingTuner.getTunerConfiguration());
                        EventQueue.invokeLater(() -> mDiscoveredTunerModel.removeDiscoveredTuner(discoveredRecordingTuner));
                    }
                }
            });
        }

        return mRemoveRecordingButton;
    }

    private JButton getAddPlutoSdrButton()
    {
        if(mAddPlutoSdrButton == null)
        {
            mAddPlutoSdrButton = new JButton("Add PlutoSDR Tuner");
            mAddPlutoSdrButton.setToolTipText("<html>Manually add a PlutoSDR tuner by specifying the companion server host and port.<br>" +
                    "Ensure pluto_server.py is running before clicking this button.</html>");
            mAddPlutoSdrButton.addActionListener(e ->
            {
                ChannelizerType channelizerType = mUserPreferences.getTunerPreference().getChannelizerType();
                // Pass the TunerManager so the dialog uses startAndConfigureTuner(), which registers
                // the status listener and starts the tuner — making it available in channel config.
                AddPlutoSdrTunerDialog dialog = new AddPlutoSdrTunerDialog(mTunerManager, channelizerType);
                dialog.setLocationRelativeTo(TunerViewPanel.this);
                EventQueue.invokeLater(() -> dialog.setVisible(true));
            });
        }

        return mAddPlutoSdrButton;
    }

    private JButton getRemovePlutoSdrButton()
    {
        if(mRemovePlutoSdrButton == null)
        {
            mRemovePlutoSdrButton = new JButton("Remove PlutoSDR Tuner");
            mRemovePlutoSdrButton.setEnabled(false);
            mRemovePlutoSdrButton.addActionListener(e ->
            {
                int[] indexes = mTunerTable.getSelectionModel().getSelectedIndices();

                //With single selection mode this should always be length one
                if(indexes.length == 1)
                {
                    int modelIndex = mTunerTable.convertRowIndexToModel(indexes[0]);
                    DiscoveredTuner selected = mDiscoveredTunerModel.getDiscoveredTuner(modelIndex);

                    if(selected instanceof DiscoveredPlutoSdrTuner discoveredPlutoSdrTuner)
                    {
                        mLog.info("Removing PlutoSDR Tuner: " + discoveredPlutoSdrTuner);
                        discoveredPlutoSdrTuner.stop();
                        mTunerConfigurationManager.removeTunerConfiguration(discoveredPlutoSdrTuner.getTunerConfiguration());
                        EventQueue.invokeLater(() -> mDiscoveredTunerModel.removeDiscoveredTuner(discoveredPlutoSdrTuner));
                    }
                }
            });
        }

        return mRemovePlutoSdrButton;
    }

    /**
     * Button to stop all currently active/processing channels.
     */
    private JButton getStopAllChannelsButton()
    {
        if(mStopAllChannelsButton == null)
        {
            mStopAllChannelsButton = new JButton("Stop All Channels");
            mStopAllChannelsButton.setToolTipText("<html>Stop all currently active channels.<br>" +
                    "Use this before changing tuner bandwidth settings.</html>");
            mStopAllChannelsButton.addActionListener(e ->
            {
                if(mPlaylistManager == null)
                {
                    return;
                }

                int confirm = JOptionPane.showConfirmDialog(TunerViewPanel.this,
                        "Stop all currently active channels?",
                        "Stop All Channels",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);

                if(confirm != JOptionPane.YES_OPTION)
                {
                    return;
                }

                mStopAllChannelsButton.setEnabled(false);
                mStartAutostartChannelsButton.setEnabled(false);

                new SwingWorker<Void, Void>()
                {
                    @Override
                    protected Void doInBackground()
                    {
                        List<Channel> channels = new ArrayList<>(mPlaylistManager.getChannelModel().getChannels());
                        int stopped = 0;

                        for(Channel channel : channels)
                        {
                            if(channel.isProcessing())
                            {
                                try
                                {
                                    mPlaylistManager.getChannelProcessingManager().stop(channel);
                                    stopped++;
                                }
                                catch(ChannelException ce)
                                {
                                    mLog.error("Error stopping channel [" + channel.getName() + "] - " + ce.getMessage());
                                }
                            }
                        }

                        mLog.info("Stopped " + stopped + " active channel(s)");
                        return null;
                    }

                    @Override
                    protected void done()
                    {
                        mStopAllChannelsButton.setEnabled(true);
                        mStartAutostartChannelsButton.setEnabled(true);
                    }
                }.execute();
            });
        }

        return mStopAllChannelsButton;
    }

    /**
     * Button to start all channels that have the auto-start flag set.
     */
    private JButton getStartAutostartChannelsButton()
    {
        if(mStartAutostartChannelsButton == null)
        {
            mStartAutostartChannelsButton = new JButton("Start Autostart Channels");
            mStartAutostartChannelsButton.setToolTipText("<html>Start all channels that have the Auto-Start flag enabled.<br>" +
                    "Use this after changing tuner bandwidth settings to resume decoding.</html>");
            mStartAutostartChannelsButton.addActionListener(e ->
            {
                if(mPlaylistManager == null)
                {
                    return;
                }

                mStopAllChannelsButton.setEnabled(false);
                mStartAutostartChannelsButton.setEnabled(false);

                new SwingWorker<Void, Void>()
                {
                    @Override
                    protected Void doInBackground()
                    {
                        List<Channel> autoStartChannels = mPlaylistManager.getChannelModel().getAutoStartChannels();
                        int started = 0;

                        for(Channel channel : autoStartChannels)
                        {
                            if(!channel.isProcessing())
                            {
                                try
                                {
                                    mPlaylistManager.getChannelProcessingManager().start(channel);
                                    started++;
                                }
                                catch(ChannelException ce)
                                {
                                    mLog.error("Error starting channel [" + channel.getName() + "] - " + ce.getMessage());
                                }
                            }
                        }

                        mLog.info("Started " + started + " autostart channel(s)");
                        return null;
                    }

                    @Override
                    protected void done()
                    {
                        mStopAllChannelsButton.setEnabled(true);
                        mStartAutostartChannelsButton.setEnabled(true);
                    }
                }.execute();
            });
        }

        return mStartAutostartChannelsButton;
    }

    /**
     * Custom cell renderer for the TunerStatus enumeration column
     */
    public class TunerStatusCellRenderer extends DefaultTableCellRenderer
    {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column)
        {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof TunerStatus tunerStatus)
            {
                if(tunerStatus == TunerStatus.ERROR)
                {
                    component.setForeground(Color.RED);
                }
                else if(tunerStatus == TunerStatus.DISABLED)
                {
                    component.setForeground(Color.DARK_GRAY);
                }
                else
                {
                    component.setForeground(table.getForeground());
                }
            }
            else
            {
                component.setForeground(table.getForeground());
            }

            return component;
        }
    }
}
