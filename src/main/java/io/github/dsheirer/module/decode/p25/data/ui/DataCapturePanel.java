
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

import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.p25.data.CapturedPayload;
import io.github.dsheirer.module.decode.p25.data.P25DataCaptureModule;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

/**
 * Swing panel for displaying captured P25 deep data (PDU payloads, LSD, vendor TSBKs).
 *
 * Added as a tab in the NowPlaying detail tabs alongside Events, Calls, Messages.
 * Implements Listener<ProcessingChain> to receive channel selection notifications
 * and discover the P25DataCaptureModule in the selected channel's processing chain.
 *
 * Features:
 * - Type filter dropdown (All, PDU, SNDCP, LSD, TSBK-V, TSBK-M, TSBK)
 * - Clear button
 * - Row count label
 * - Right-click context menu with Copy Hex / Copy Details
 * - Color-coded type and protocol columns
 */
public class DataCapturePanel extends JPanel implements Listener<ProcessingChain>
{
    private static final long serialVersionUID = 1L;
    private static final Logger mLog = LoggerFactory.getLogger(DataCapturePanel.class);

    /** Filter options for the type dropdown */
    private static final String FILTER_ALL = "All";
    private static final String[] FILTER_OPTIONS = {
        FILTER_ALL, "PDU", "SNDCP", "LSD", "TSBK-V", "TSBK-M", "TSBK", "LRRP"
    };

    private JTable mTable;
    private DataCaptureModel mModel = new DataCaptureModel();
    private TableRowSorter<DataCaptureModel> mRowSorter;
    private JScrollPane mScrollPane;
    private UserPreferences mUserPreferences;
    private TimestampCellRenderer mTimestampCellRenderer;
    private JComboBox<String> mFilterCombo;
    private JLabel mRowCountLabel;

    /** Currently wired data capture module — used to unregister when switching channels */
    private P25DataCaptureModule mCurrentModule;

    /** Per-module model cache — preserves data when switching between channels */
    private final Map<P25DataCaptureModule, DataCaptureModel> mModelCache = new HashMap<>();

    /**
     * Constructs an instance.
     *
     * @param userPreferences for timestamp formatting
     */
    public DataCapturePanel(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
        mTimestampCellRenderer = new TimestampCellRenderer();

        setLayout(new MigLayout("insets 0 0 0 0", "[grow,fill]", "[][grow,fill]"));

        // Toolbar with filter, clear, and row count
        JPanel toolbar = new JPanel(new MigLayout("insets 2 4 2 4", "[][][][grow][]", ""));

        toolbar.add(new JLabel("Filter:"));

        mFilterCombo = new JComboBox<>(FILTER_OPTIONS);
        mFilterCombo.setSelectedItem(FILTER_ALL);
        mFilterCombo.addActionListener(this::onFilterChanged);
        toolbar.add(mFilterCombo);

        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> {
            mModel.clear();
            updateRowCount();
        });
        toolbar.add(clearButton);

        // Spacer
        toolbar.add(new JLabel(""), "growx");

        mRowCountLabel = new JLabel("0 rows");
        toolbar.add(mRowCountLabel);

        add(toolbar, "wrap");

        // Table
        mTable = new JTable(mModel);
        mRowSorter = new TableRowSorter<>(mModel);
        mTable.setRowSorter(mRowSorter);
        mTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        updateCellRenderers();
        setColumnWidths();

        // Listen for model changes to update row count
        mModel.addTableModelListener(e -> updateRowCount());

        // Right-click context menu
        mTable.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                if(e.isPopupTrigger())
                {
                    showContextMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e)
            {
                if(e.isPopupTrigger())
                {
                    showContextMenu(e);
                }
            }
        });

        mScrollPane = new JScrollPane(mTable);
        add(mScrollPane);
    }

    /**
     * Updates the row count label.
     */
    private void updateRowCount()
    {
        int displayed = mTable.getRowCount();
        int total = mModel.getRowCount();

        if(displayed == total)
        {
            mRowCountLabel.setText(total + " rows");
        }
        else
        {
            mRowCountLabel.setText(displayed + " / " + total + " rows");
        }
    }

    /**
     * Handles filter dropdown changes.
     */
    private void onFilterChanged(ActionEvent e)
    {
        String selected = (String)mFilterCombo.getSelectedItem();

        if(selected == null || FILTER_ALL.equals(selected))
        {
            mRowSorter.setRowFilter(null);
        }
        else
        {
            final String filterValue = selected;
            mRowSorter.setRowFilter(new RowFilter<DataCaptureModel, Integer>()
            {
                @Override
                public boolean include(Entry<? extends DataCaptureModel, ? extends Integer> entry)
                {
                    String typeValue = (String)entry.getValue(DataCaptureModel.COLUMN_TYPE);
                    return filterValue.equals(typeValue);
                }
            });
        }

        updateRowCount();
    }

    /**
     * Shows the right-click context menu.
     */
    private void showContextMenu(MouseEvent e)
    {
        int viewRow = mTable.rowAtPoint(e.getPoint());
        if(viewRow < 0)
        {
            return;
        }

        mTable.setRowSelectionInterval(viewRow, viewRow);
        int modelRow = mTable.convertRowIndexToModel(viewRow);
        CapturedPayload payload = mModel.getPayloadAt(modelRow);

        if(payload == null)
        {
            return;
        }

        JPopupMenu menu = new JPopupMenu();

        // Copy Hex
        JMenuItem copyHex = new JMenuItem("Copy Hex");
        copyHex.addActionListener(a -> {
            String hex = payload.getHexDump();
            if(hex != null && !hex.isEmpty())
            {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(hex), null);
            }
        });
        menu.add(copyHex);

        // Copy Details
        JMenuItem copyDetails = new JMenuItem("Copy Details");
        copyDetails.addActionListener(a -> {
            String details = payload.getDetails();
            if(details != null && !details.isEmpty())
            {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(details), null);
            }
        });
        menu.add(copyDetails);

        // Copy JSON Line
        JMenuItem copyJson = new JMenuItem("Copy JSON Line");
        copyJson.addActionListener(a -> {
            String json = payload.toJsonLine();
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(json), null);
        });
        menu.add(copyJson);

        // Copy GPS (only shown if GPS data present)
        if(payload.hasGpsCoordinates())
        {
            JMenuItem copyGps = new JMenuItem("Copy GPS (" + payload.getGpsDisplay() + ")");
            copyGps.addActionListener(a -> {
                String gps = payload.getLatitude() + ", " + payload.getLongitude();
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(gps), null);
            });
            menu.add(copyGps);
        }

        menu.show(mTable, e.getX(), e.getY());
    }

    /**
     * Sets initial column widths.
     */
    private void setColumnWidths()
    {
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_TIME).setPreferredWidth(100);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_TYPE).setPreferredWidth(50);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_MODE).setPreferredWidth(45);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_CHANNEL).setPreferredWidth(60);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_FREQ).setPreferredWidth(75);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_SAP_OPCODE).setPreferredWidth(120);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_FROM).setPreferredWidth(80);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_TO).setPreferredWidth(80);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_LENGTH).setPreferredWidth(40);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_PROTOCOL).setPreferredWidth(60);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_GPS).setPreferredWidth(200);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_HEX).setPreferredWidth(250);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_STRINGS).setPreferredWidth(150);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_DETAILS).setPreferredWidth(300);
    }

    /**
     * Sets up cell renderers.
     */
    private void updateCellRenderers()
    {
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_TIME).setCellRenderer(mTimestampCellRenderer);
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_TYPE).setCellRenderer(new TypeCellRenderer());
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_MODE).setCellRenderer(new ModeCellRenderer());
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_CHANNEL).setCellRenderer(new CenteredCellRenderer());
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_FREQ).setCellRenderer(new CenteredCellRenderer());
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_LENGTH).setCellRenderer(new CenteredCellRenderer());
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_PROTOCOL).setCellRenderer(new ProtocolCellRenderer());
        mTable.getColumnModel().getColumn(DataCaptureModel.COLUMN_GPS).setCellRenderer(new GpsCellRenderer());
    }

    /**
     * Swaps the active model to a new one, re-wiring the table, sorter, and row-count listener.
     */
    private void swapModel(DataCaptureModel newModel)
    {
        mModel = newModel;
        mTable.setModel(mModel);
        mRowSorter = new TableRowSorter<>(mModel);
        mTable.setRowSorter(mRowSorter);

        // Re-apply renderers and column widths after model swap
        updateCellRenderers();
        setColumnWidths();

        // Re-apply current filter
        onFilterChanged(null);

        // Re-wire row count listener
        mModel.addTableModelListener(e -> updateRowCount());
    }

    /**
     * Called when the user selects a different channel in the NowPlaying table.
     * Discovers the P25DataCaptureModule and wires the model as a payload listener.
     * Uses per-module model cache so data persists when switching between channels.
     */
    @Override
    public void receive(final ProcessingChain processingChain)
    {
        EventQueue.invokeLater(() -> {
            // Unregister from previous module (but keep its model in cache)
            if(mCurrentModule != null)
            {
                mCurrentModule.removePayloadListener(mModel);
                mCurrentModule = null;
            }

            P25DataCaptureModule newModule = null;

            if(processingChain != null)
            {
                // Walk modules to find P25DataCaptureModule
                for(Module module : processingChain.getModules())
                {
                    if(module instanceof P25DataCaptureModule dcm)
                    {
                        newModule = dcm;
                        break;
                    }
                }
            }

            if(newModule != null)
            {
                mCurrentModule = newModule;

                // Get or create cached model for this module
                DataCaptureModel cachedModel = mModelCache.computeIfAbsent(newModule, k -> new DataCaptureModel());

                // Swap model if different from current
                if(cachedModel != mModel)
                {
                    swapModel(cachedModel);
                }

                // Wire listener (idempotent — addPayloadListener should handle duplicates)
                newModule.addPayloadListener(mModel);
            }
            else
            {
                // No P25 data module — show empty model
                swapModel(new DataCaptureModel());
            }

            updateRowCount();
        });
    }

    // ========================================================================
    // Cell Renderers
    // ========================================================================

    /**
     * Centered cell renderer.
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
        private SimpleDateFormat mFormatter;

        public TimestampCellRenderer()
        {
            setHorizontalAlignment(JLabel.CENTER);
            mFormatter = mUserPreferences.getDecodeEventPreference().getTimestampFormat().getFormatter();
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof Long ts)
            {
                label.setText(mFormatter.format(new Date(ts)));
            }
            else
            {
                label.setText(null);
            }

            return label;
        }
    }

    /**
     * Type cell renderer — color-codes by payload type.
     */
    public class TypeCellRenderer extends DefaultTableCellRenderer
    {
        private static final Color COLOR_PDU = new Color(0, 100, 180);
        private static final Color COLOR_SNDCP = new Color(0, 140, 120);
        private static final Color COLOR_LSD = new Color(128, 128, 128);
        private static final Color COLOR_LSD_CRYPTO = new Color(200, 50, 50);
        private static final Color COLOR_TSBK_V = new Color(200, 120, 0);
        private static final Color COLOR_TSBK_M = new Color(180, 80, 0);
        private static final Color COLOR_LRRP = new Color(0, 150, 0);

        public TypeCellRenderer()
        {
            setHorizontalAlignment(JLabel.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(!isSelected && value instanceof String type)
            {
                switch(type)
                {
                    case "PDU":
                    case "IP":
                        label.setForeground(COLOR_PDU);
                        break;
                    case "SNDCP":
                        label.setForeground(COLOR_SNDCP);
                        break;
                    case "LSD":
                        // Check protocol column for CRYPTO to color red
                        int modelRow = table.convertRowIndexToModel(row);
                        CapturedPayload cp = mModel.getPayloadAt(modelRow);
                        if(cp != null && "CRYPTO".equals(cp.getDetectedProtocol()))
                        {
                            label.setForeground(COLOR_LSD_CRYPTO);
                        }
                        else
                        {
                            label.setForeground(COLOR_LSD);
                        }
                        break;
                    case "TSBK-V":
                        label.setForeground(COLOR_TSBK_V);
                        break;
                    case "TSBK-M":
                        label.setForeground(COLOR_TSBK_M);
                        break;
                    case "LRRP":
                        label.setForeground(COLOR_LRRP);
                        break;
                    default:
                        label.setForeground(table.getForeground());
                        break;
                }
            }

            return label;
        }
    }

    /**
     * Protocol cell renderer — color-codes known protocols.
     */
    public class ProtocolCellRenderer extends DefaultTableCellRenderer
    {
        public ProtocolCellRenderer()
        {
            setHorizontalAlignment(JLabel.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(!isSelected && value instanceof String proto)
            {
                switch(proto)
                {
                    case "LRRP":
                    case "LRRP-REQ":
                    case "LRRP-TRIG":
                    case "NMEA-GPS":
                        label.setForeground(new Color(0, 150, 0));
                        break;
                    case "IPv4":
                        label.setForeground(new Color(0, 100, 180));
                        break;
                    case "CRYPTO":
                        label.setForeground(new Color(200, 50, 50));
                        break;
                    case "JSON":
                    case "HTTP-GET":
                    case "HTTP-POST":
                        label.setForeground(new Color(150, 0, 150));
                        break;
                    case "SACCH":
                    case "SLOW-CTRL":
                        label.setForeground(new Color(128, 128, 128));
                        break;
                    case "VENDOR":
                        label.setForeground(new Color(200, 120, 0));
                        break;
                    case "XCMP":
                        label.setForeground(new Color(180, 0, 180));
                        break;
                    case "ARS":
                        label.setForeground(new Color(0, 120, 160));
                        break;
                    case "SNDCP":
                        label.setForeground(new Color(0, 140, 120));
                        break;
                    default:
                        label.setForeground(table.getForeground());
                        break;
                }
            }

            return label;
        }
    }

    /**
     * Mode cell renderer — color-codes FDMA vs TDMA.
     */
    public class ModeCellRenderer extends DefaultTableCellRenderer
    {
        private static final Color COLOR_FDMA = new Color(0, 100, 180);
        private static final Color COLOR_TDMA = new Color(180, 80, 0);

        public ModeCellRenderer()
        {
            setHorizontalAlignment(JLabel.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(!isSelected && value instanceof String mode)
            {
                switch(mode)
                {
                    case "FDMA":
                        label.setForeground(COLOR_FDMA);
                        break;
                    case "TDMA":
                        label.setForeground(COLOR_TDMA);
                        break;
                    default:
                        label.setForeground(table.getForeground());
                        break;
                }
            }

            return label;
        }
    }

    /**
     * GPS cell renderer — highlights cells that contain GPS coordinates in green.
     */
    public class GpsCellRenderer extends DefaultTableCellRenderer
    {
        private static final Color COLOR_GPS = new Color(0, 140, 0);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(!isSelected && value instanceof String gps && !gps.isEmpty())
            {
                label.setForeground(COLOR_GPS);
            }
            else if(!isSelected)
            {
                label.setForeground(table.getForeground());
            }

            return label;
        }
    }
}
