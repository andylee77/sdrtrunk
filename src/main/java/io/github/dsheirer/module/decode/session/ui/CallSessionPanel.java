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

import com.google.common.base.Joiner;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.calllog.CallLogDatabase;
import io.github.dsheirer.calllog.CallLogRecord;
import io.github.dsheirer.calllog.CallLogWriter;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.session.P25CallSessionManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.swing.JTableColumnWidthMonitor;
import io.github.dsheirer.sample.Listener;
import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

/**
 * Swing panel for displaying call session events and historical call log records in a JTable.
 *
 * Features:
 * - Real-time live events from P25CallSessionManager (per-talker segments)
 * - Historical session records loaded from SQLite database on channel selection
 * - Filter toolbar: time range selector, hide encrypted, hide data calls, hide unmonitored
 * - JTable RowFilter for dynamic filtering without removing data from the model
 *
 * Implements Listener<ProcessingChain> to receive notifications when the user selects
 * a different channel in the NowPlaying table.
 */
public class CallSessionPanel extends JPanel implements Listener<ProcessingChain>
{
    private static final long serialVersionUID = 1L;
    private static final Logger mLog = LoggerFactory.getLogger(CallSessionPanel.class);
    private static final String TABLE_PREFERENCE_KEY = "call.session.panel";

    /** History time range options in hours */
    private static final int[] HISTORY_HOURS = {1, 4, 8, 12, 24, 48, 72};
    private static final int DEFAULT_HISTORY_HOURS = 4;
    private static final int MAX_HISTORY_ROWS = 1500;

    private JTable mTable;
    private JTableColumnWidthMonitor mTableColumnWidthMonitor;
    private CallSessionModel mModel = new CallSessionModel();
    private TableRowSorter<CallSessionModel> mRowSorter;
    private JScrollPane mScrollPane;
    private IconModel mIconModel;
    private AliasModel mAliasModel;
    private UserPreferences mUserPreferences;
    private TimestampCellRenderer mTimestampCellRenderer;

    // Filter controls
    private JComboBox<String> mHistoryCombo;
    private JCheckBox mHideEncryptedCheckBox;
    private JCheckBox mHideDataCheckBox;
    private JCheckBox mHideUnmonitoredCheckBox;

    /** Currently wired session manager — used to unregister when switching channels */
    private P25CallSessionManager mCurrentSessionManager;

    /** Currently wired call log writer — used for database history queries */
    private CallLogWriter mCurrentCallLogWriter;


    /**
     * Constructs an instance.
     *
     * @param iconModel for alias icon display
     * @param userPreferences for timestamp and talkgroup formatting
     * @param aliasModel for resolving aliases
     */
    public CallSessionPanel(IconModel iconModel, UserPreferences userPreferences, AliasModel aliasModel)
    {
        mIconModel = iconModel;
        mAliasModel = aliasModel;
        mUserPreferences = userPreferences;
        mTimestampCellRenderer = new TimestampCellRenderer();

        setLayout(new MigLayout("insets 0 0 0 0", "[grow,fill]", "[][grow,fill]"));

        // Filter toolbar
        add(createFilterToolbar(), "wrap");

        // Table
        mTable = new JTable(mModel);
        mRowSorter = new TableRowSorter<>(mModel);
        mTable.setRowSorter(mRowSorter);
        mTableColumnWidthMonitor = new JTableColumnWidthMonitor(mUserPreferences, mTable, TABLE_PREFERENCE_KEY);
        updateCellRenderers();

        mScrollPane = new JScrollPane(mTable);
        add(mScrollPane);
    }

    /**
     * Creates the filter toolbar panel with time range and filter checkboxes.
     */
    private JPanel createFilterToolbar()
    {
        JPanel toolbar = new JPanel(new MigLayout("insets 2 4 2 4, gap 8", "", ""));

        // History time range
        toolbar.add(new JLabel("History:"));
        String[] historyOptions = new String[HISTORY_HOURS.length];
        int defaultIndex = 0;
        for(int i = 0; i < HISTORY_HOURS.length; i++)
        {
            historyOptions[i] = HISTORY_HOURS[i] + "h";
            if(HISTORY_HOURS[i] == DEFAULT_HISTORY_HOURS)
            {
                defaultIndex = i;
            }
        }
        mHistoryCombo = new JComboBox<>(historyOptions);
        mHistoryCombo.setSelectedIndex(defaultIndex);
        mHistoryCombo.setToolTipText("Load call history from database for the last N hours");
        mHistoryCombo.addActionListener(e -> reloadHistory());
        toolbar.add(mHistoryCombo);

        // Separator
        toolbar.add(new JLabel("  |  "));

        // Hide encrypted
        mHideEncryptedCheckBox = new JCheckBox("Hide Encrypted");
        mHideEncryptedCheckBox.setToolTipText("Hide encrypted calls from the display");
        mHideEncryptedCheckBox.addActionListener(e -> applyFilters());
        toolbar.add(mHideEncryptedCheckBox);

        // Hide data calls
        mHideDataCheckBox = new JCheckBox("Hide Data");
        mHideDataCheckBox.setToolTipText("Hide data channel calls from the display");
        mHideDataCheckBox.addActionListener(e -> applyFilters());
        toolbar.add(mHideDataCheckBox);

        // Hide unmonitored/ignored
        mHideUnmonitoredCheckBox = new JCheckBox("Hide Ignored");
        mHideUnmonitoredCheckBox.setToolTipText("Hide calls marked as IGNORED (unmonitored, encrypted, etc.)");
        mHideUnmonitoredCheckBox.addActionListener(e -> applyFilters());
        toolbar.add(mHideUnmonitoredCheckBox);

        return toolbar;
    }

    /**
     * Applies the current filter checkbox states as a RowFilter on the table.
     */
    private void applyFilters()
    {
        boolean hideEncrypted = mHideEncryptedCheckBox.isSelected();
        boolean hideData = mHideDataCheckBox.isSelected();
        boolean hideUnmonitored = mHideUnmonitoredCheckBox.isSelected();

        if(!hideEncrypted && !hideData && !hideUnmonitored)
        {
            mRowSorter.setRowFilter(null);
            return;
        }

        mRowSorter.setRowFilter(new RowFilter<CallSessionModel, Integer>()
        {
            @Override
            public boolean include(Entry<? extends CallSessionModel, ? extends Integer> entry)
            {
                int modelRow = entry.getIdentifier();
                CallSessionModel model = entry.getModel();

                if(hideEncrypted && model.isEncrypted(modelRow))
                {
                    return false;
                }
                if(hideData && model.isDataCall(modelRow))
                {
                    return false;
                }
                if(hideUnmonitored && model.isUnmonitored(modelRow))
                {
                    return false;
                }
                return true;
            }
        });
    }

    /**
     * Returns the selected history hours from the combo box.
     */
    private int getSelectedHistoryHours()
    {
        int index = mHistoryCombo.getSelectedIndex();
        if(index >= 0 && index < HISTORY_HOURS.length)
        {
            return HISTORY_HOURS[index];
        }
        return DEFAULT_HISTORY_HOURS;
    }

    /**
     * Reloads history from the database using the current time range selection.
     */
    private void reloadHistory()
    {
        if(mCurrentCallLogWriter == null)
        {
            return;
        }

        CallLogDatabase db = mCurrentCallLogWriter.getDatabase();
        if(db == null || !db.isOpen())
        {
            return;
        }

        int hours = getSelectedHistoryHours();
        long sinceTimestamp = System.currentTimeMillis() - (hours * 3600_000L);

        // Run query off EDT, then load results on EDT
        new Thread(() -> {
            try
            {
                List<CallLogRecord> records = db.queryRecentSessions(sinceTimestamp, MAX_HISTORY_ROWS);
                mLog.info("Loaded {} historical call records (last {}h)", records.size(), hours);
                mModel.loadHistory(records);
            }
            catch(SQLException e)
            {
                mLog.error("Error loading call history", e);
            }
        }, "CallLogHistoryLoader").start();
    }

    /**
     * Sets up cell renderers for all columns.
     */
    private void updateCellRenderers()
    {
        mTable.getColumnModel().getColumn(CallSessionModel.COLUMN_TIME).setCellRenderer(mTimestampCellRenderer);
        mTable.getColumnModel().getColumn(CallSessionModel.COLUMN_DURATION).setCellRenderer(new DurationCellRenderer());
        mTable.getColumnModel().getColumn(CallSessionModel.COLUMN_FROM_ID).setCellRenderer(new IdentifierCellRenderer(Role.FROM));
        mTable.getColumnModel().getColumn(CallSessionModel.COLUMN_FROM_ALIAS).setCellRenderer(new AliasedIdentifierCellRenderer(Role.FROM));
        mTable.getColumnModel().getColumn(CallSessionModel.COLUMN_TO_ID).setCellRenderer(new IdentifierCellRenderer(Role.TO));
        mTable.getColumnModel().getColumn(CallSessionModel.COLUMN_TO_ALIAS).setCellRenderer(new AliasedIdentifierCellRenderer(Role.TO));
        mTable.getColumnModel().getColumn(CallSessionModel.COLUMN_PATCH_GROUP).setCellRenderer(new PatchGroupCellRenderer());
        mTable.getColumnModel().getColumn(CallSessionModel.COLUMN_CHANNEL).setCellRenderer(new CenteredCellRenderer());
        mTable.getColumnModel().getColumn(CallSessionModel.COLUMN_FREQUENCY).setCellRenderer(new FrequencyCellRenderer());
    }

    /**
     * Called when the user selects a different channel in the NowPlaying table.
     * Walks the processing chain to find the P25TrafficChannelManager, wires the model
     * as a call session listener, and loads history from the database.
     */
    @Override
    public void receive(final ProcessingChain processingChain)
    {
        EventQueue.invokeLater(() -> {
            // Unregister from previous session manager
            if(mCurrentSessionManager != null)
            {
                mCurrentSessionManager.removeListener(mModel);
                mCurrentSessionManager = null;
            }

            mCurrentCallLogWriter = null;

            // Clear the model
            mModel.clear();

            if(processingChain != null)
            {
                // Walk modules to find the P25TrafficChannelManager
                for(Module module : processingChain.getModules())
                {
                    if(module instanceof P25TrafficChannelManager tcm)
                    {
                        mCurrentSessionManager = tcm.getCallSessionManager();
                        mCurrentCallLogWriter = tcm.getCallLogWriter();

                        // Pass alias list name to model so historical records can build synthetic ICs
                        mModel.setAliasListName(tcm.getAliasListName());

                        if(mCurrentSessionManager != null)
                        {
                            mCurrentSessionManager.addListener(mModel);
                        }

                        // Load history from database
                        reloadHistory();
                        break;
                    }
                }
            }
        });
    }

    // ========================================================================
    // Cell Renderers
    // ========================================================================

    /**
     * Centered cell renderer base class.
     */
    public class CenteredCellRenderer extends DefaultTableCellRenderer
    {
        public CenteredCellRenderer()
        {
            setHorizontalAlignment(JLabel.CENTER);
        }
    }

    /**
     * Timestamp cell renderer.
     */
    public class TimestampCellRenderer extends DefaultTableCellRenderer
    {
        private SimpleDateFormat mTimestampFormatter;

        public TimestampCellRenderer()
        {
            setHorizontalAlignment(JLabel.CENTER);
            updatePreferences();
        }

        public void updatePreferences()
        {
            mTimestampFormatter = mUserPreferences.getDecodeEventPreference().getTimestampFormat().getFormatter();
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof Long)
            {
                label.setText(mTimestampFormatter.format(new Date((long)value)));
            }
            else
            {
                label.setText(null);
            }

            return label;
        }
    }

    /**
     * Duration cell renderer — displays duration in seconds.
     */
    public class DurationCellRenderer extends DefaultTableCellRenderer
    {
        private DecimalFormat mDecimalFormat = new DecimalFormat("0.0");

        public DurationCellRenderer()
        {
            setHorizontalAlignment(JLabel.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            String formatted = null;

            if(value instanceof Long)
            {
                long duration = (long)value;
                if(duration > 0)
                {
                    formatted = mDecimalFormat.format((double)duration / 1e3d);
                }
            }

            label.setText(formatted);
            return label;
        }
    }

    /**
     * Identifier cell renderer — displays identifiers for a specific role.
     * Handles both IdentifierCollection (live events) and plain String (historical records).
     */
    public class IdentifierCellRenderer extends DefaultTableCellRenderer
    {
        protected Role mRole;

        public IdentifierCellRenderer(Role role)
        {
            mRole = role;
            setHorizontalAlignment(JLabel.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof IdentifierCollection ic)
            {
                List<Identifier> identifiers = ic.getIdentifiers(mRole);
                label.setText(format(identifiers));
            }
            else if(value instanceof String s)
            {
                label.setText(s);
            }
            else
            {
                label.setText(null);
            }

            return label;
        }

        protected String format(List<Identifier> identifiers)
        {
            if(identifiers == null || identifiers.isEmpty())
            {
                return null;
            }

            StringBuilder sb = new StringBuilder();

            for(Identifier identifier : identifiers)
            {
                if(sb.length() > 0)
                {
                    sb.append(",");
                }

                if(identifier.getForm() == Form.TALKGROUP || identifier.getForm() == Form.RADIO ||
                   identifier.getForm() == Form.PATCH_GROUP)
                {
                    sb.append(mUserPreferences.getTalkgroupFormatPreference().format(identifier));
                }
                else
                {
                    sb.append(identifier);
                }
            }

            return sb.toString();
        }
    }

    /**
     * Aliased identifier cell renderer — resolves and displays aliases.
     * Handles both IdentifierCollection (live events) and plain String (historical alias text).
     */
    public class AliasedIdentifierCellRenderer extends DefaultTableCellRenderer
    {
        private Role mRole;

        public AliasedIdentifierCellRenderer(Role role)
        {
            mRole = role;
            setHorizontalAlignment(JLabel.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            Color color = mTable.getForeground();
            ImageIcon icon = null;
            String text = null;

            if(value instanceof IdentifierCollection ic)
            {
                List<Identifier> identifiers = ic.getIdentifiers(mRole);

                if(identifiers != null && !identifiers.isEmpty())
                {
                    AliasList aliasList = mAliasModel.getAliasList(ic);

                    if(aliasList != null)
                    {
                        StringBuilder sb = new StringBuilder();

                        for(Identifier identifier : identifiers)
                        {
                            List<Alias> aliases = aliasList.getAliases(identifier);

                            if(!aliases.isEmpty())
                            {
                                if(sb.length() > 0)
                                {
                                    sb.append(",");
                                }
                                sb.append(Joiner.on(", ").skipNulls().join(aliases));
                                color = aliases.get(0).getDisplayColor();
                                icon = mIconModel.getIcon(aliases.get(0).getIconName(), IconModel.DEFAULT_ICON_SIZE);
                            }
                        }

                        text = sb.toString();
                    }
                }
            }
            // Note: historical records now return synthetic IdentifierCollections (handled above)

            label.setText(text);
            label.setForeground(color);
            label.setIcon(icon);

            return label;
        }
    }

    /**
     * Frequency cell renderer — displays frequency in MHz.
     * Handles both IChannelDescriptor (live events) and Double (historical records).
     */
    public class FrequencyCellRenderer extends DefaultTableCellRenderer
    {
        private DecimalFormat mFrequencyFormatter = new DecimalFormat("0.00000");

        public FrequencyCellRenderer()
        {
            setHorizontalAlignment(JLabel.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            String formatted = null;

            if(value instanceof IChannelDescriptor channelDescriptor)
            {
                long frequency = channelDescriptor.getDownlinkFrequency();
                if(frequency > 0)
                {
                    formatted = mFrequencyFormatter.format(frequency / 1e6d);
                }
            }
            else if(value instanceof Double freq)
            {
                if(freq > 0)
                {
                    // Historical records store frequency in Hz as double
                    formatted = mFrequencyFormatter.format(freq / 1e6d);
                }
            }

            label.setText(formatted);
            return label;
        }
    }

    /**
     * Patch group cell renderer — displays patch group supergroup and member talkgroups.
     * Handles both IdentifierCollection (live events) and plain String (historical records).
     */
    public class PatchGroupCellRenderer extends DefaultTableCellRenderer
    {
        public PatchGroupCellRenderer()
        {
            setHorizontalAlignment(JLabel.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            String text = null;

            if(value instanceof IdentifierCollection ic)
            {
                text = formatPatchGroup(ic);
            }
            else if(value instanceof String s)
            {
                text = s;
            }

            label.setText(text);
            return label;
        }

        private String formatPatchGroup(IdentifierCollection ic)
        {
            List<Identifier> toIdentifiers = ic.getIdentifiers(Role.TO);

            if(toIdentifiers == null || toIdentifiers.isEmpty())
            {
                return null;
            }

            for(Identifier identifier : toIdentifiers)
            {
                if(identifier instanceof PatchGroupIdentifier pgId)
                {
                    PatchGroup patchGroup = pgId.getValue();
                    StringBuilder sb = new StringBuilder();

                    sb.append("P:");
                    sb.append(mUserPreferences.getTalkgroupFormatPreference().format(patchGroup.getPatchGroup()));

                    List<TalkgroupIdentifier> members = patchGroup.getPatchedTalkgroupIdentifiers();
                    if(!members.isEmpty())
                    {
                        sb.append(" [");
                        for(int i = 0; i < members.size(); i++)
                        {
                            if(i > 0)
                            {
                                sb.append(", ");
                            }
                            sb.append(mUserPreferences.getTalkgroupFormatPreference().format(members.get(i)));
                        }
                        sb.append("]");
                    }

                    return sb.toString();
                }
            }

            return null;
        }
    }
}
