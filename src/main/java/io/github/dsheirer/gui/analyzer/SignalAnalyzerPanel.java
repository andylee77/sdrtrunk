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
package io.github.dsheirer.gui.analyzer;

import com.jidesoft.swing.JideSplitPane;
import io.github.dsheirer.gui.analyzer.detection.DetectedSignal;
import io.github.dsheirer.gui.analyzer.detection.HarmonicAnalyzer;
import io.github.dsheirer.gui.analyzer.detection.SignalDetector;
import io.github.dsheirer.gui.analyzer.detection.SignalTracker;
import io.github.dsheirer.gui.analyzer.ui.AnalysisLogPanel;
import io.github.dsheirer.gui.analyzer.ui.AnalyzerControlPanel;
import io.github.dsheirer.gui.analyzer.ui.SignalAnalysisTableModel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main Swing JPanel for the Signal Analyzer tab.
 * Layout: controls (top) → signal table (middle) → analysis log (bottom)
 *
 * This is the Phase 1 implementation which provides:
 * - Signal detection from FFT data
 * - Harmonic/image/DC spike validation
 * - Signal tracking across detection cycles
 * - Real-time signal table display
 * - Analysis log with timestamped messages
 */
public class SignalAnalyzerPanel extends JPanel
{
    private static final Logger mLog = LoggerFactory.getLogger(SignalAnalyzerPanel.class);

    // Configuration
    private final SignalAnalyzerConfig mConfig;

    // Engine
    private final SignalDetector mDetector;
    private final SignalTracker mTracker;
    private final HarmonicAnalyzer mHarmonicAnalyzer;
    private final SignalAnalyzerController mController;

    // UI components
    private final AnalyzerControlPanel mControlPanel;
    private final SignalAnalysisTableModel mTableModel;
    private final AnalysisLogPanel mLogPanel;
    private JTable mSignalTable;

    public SignalAnalyzerPanel()
    {
        mConfig = new SignalAnalyzerConfig();
        mDetector = new SignalDetector(mConfig);
        mTracker = new SignalTracker(mConfig);
        mHarmonicAnalyzer = new HarmonicAnalyzer(mConfig);

        mTableModel = new SignalAnalysisTableModel();
        mControlPanel = new AnalyzerControlPanel(mConfig);
        mLogPanel = new AnalysisLogPanel();

        mController = new SignalAnalyzerController(mConfig, mDetector, mTracker, mHarmonicAnalyzer,
            mTableModel, mControlPanel, mLogPanel);

        init();
        wireListeners();

        mLogPanel.logInfo("Signal Analyzer initialized — Phase 1 (Detection + Validation)");
        mLogPanel.logInfo("Click 'Start' to begin scanning the current tuner bandwidth");
    }

    /**
     * Get the signal detector, which implements DFTResultsListener.
     * Used to wire into the FFT pipeline from SpectralDisplayPanel.
     */
    public SignalDetector getDetector()
    {
        return mDetector;
    }

    /**
     * Get the controller for external wiring.
     */
    public SignalAnalyzerController getController()
    {
        return mController;
    }

    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0", "[grow,fill]", "[][grow,fill]"));

        // Top: controls
        add(mControlPanel, "wrap");

        // Create signal table
        mSignalTable = new JTable(mTableModel);
        mSignalTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mSignalTable.setAutoCreateRowSorter(true);
        mSignalTable.setFillsViewportHeight(true);
        mSignalTable.setRowHeight(20);

        // Set column widths
        mSignalTable.getColumnModel().getColumn(SignalAnalysisTableModel.COL_FREQUENCY).setPreferredWidth(110);
        mSignalTable.getColumnModel().getColumn(SignalAnalysisTableModel.COL_POWER).setPreferredWidth(70);
        mSignalTable.getColumnModel().getColumn(SignalAnalysisTableModel.COL_BANDWIDTH).setPreferredWidth(70);
        mSignalTable.getColumnModel().getColumn(SignalAnalysisTableModel.COL_MODULATION).setPreferredWidth(80);
        mSignalTable.getColumnModel().getColumn(SignalAnalysisTableModel.COL_SYMBOL_RATE).setPreferredWidth(70);
        mSignalTable.getColumnModel().getColumn(SignalAnalysisTableModel.COL_PROTOCOL).setPreferredWidth(80);
        mSignalTable.getColumnModel().getColumn(SignalAnalysisTableModel.COL_FLAGS).setPreferredWidth(120);
        mSignalTable.getColumnModel().getColumn(SignalAnalysisTableModel.COL_STATUS).setPreferredWidth(80);

        // Custom cell renderers for numeric formatting
        DefaultTableCellRenderer freqRenderer = new DefaultTableCellRenderer()
        {
            @Override
            protected void setValue(Object value)
            {
                if(value instanceof Double)
                {
                    setText(String.format("%.4f", (Double)value));
                }
                else
                {
                    super.setValue(value);
                }
                setHorizontalAlignment(RIGHT);
            }
        };
        mSignalTable.getColumnModel().getColumn(SignalAnalysisTableModel.COL_FREQUENCY).setCellRenderer(freqRenderer);

        DefaultTableCellRenderer powerRenderer = new DefaultTableCellRenderer()
        {
            @Override
            protected void setValue(Object value)
            {
                if(value instanceof Double)
                {
                    setText(String.format("%.1f", (Double)value));
                }
                else
                {
                    super.setValue(value);
                }
                setHorizontalAlignment(RIGHT);
            }
        };
        mSignalTable.getColumnModel().getColumn(SignalAnalysisTableModel.COL_POWER).setCellRenderer(powerRenderer);

        DefaultTableCellRenderer bwRenderer = new DefaultTableCellRenderer()
        {
            @Override
            protected void setValue(Object value)
            {
                if(value instanceof Double)
                {
                    setText(String.format("%.1f", (Double)value));
                }
                else
                {
                    super.setValue(value);
                }
                setHorizontalAlignment(RIGHT);
            }
        };
        mSignalTable.getColumnModel().getColumn(SignalAnalysisTableModel.COL_BANDWIDTH).setCellRenderer(bwRenderer);

        // Status column renderer with color
        DefaultTableCellRenderer statusRenderer = new DefaultTableCellRenderer()
        {
            @Override
            protected void setValue(Object value)
            {
                super.setValue(value);
                if(value != null)
                {
                    String status = value.toString();
                    switch(status)
                    {
                        case "Detected":
                            setForeground(new Color(0, 100, 200));
                            break;
                        case "Flagged":
                            setForeground(new Color(200, 150, 0));
                            break;
                        case "Identifying":
                            setForeground(new Color(180, 100, 0));
                            break;
                        case "Identified":
                            setForeground(new Color(0, 150, 0));
                            break;
                        case "Characterizing":
                            setForeground(new Color(100, 100, 200));
                            break;
                        case "No Match":
                            setForeground(Color.GRAY);
                            break;
                        case "Ignored":
                            setForeground(Color.LIGHT_GRAY);
                            break;
                        default:
                            setForeground(Color.BLACK);
                    }
                }
            }
        };
        mSignalTable.getColumnModel().getColumn(SignalAnalysisTableModel.COL_STATUS).setCellRenderer(statusRenderer);

        // Table in a scroll pane
        JScrollPane tableScrollPane = new JScrollPane(mSignalTable);
        tableScrollPane.setPreferredSize(new Dimension(800, 250));

        // Bottom: log panel
        mLogPanel.setPreferredSize(new Dimension(800, 200));

        // Split pane between table and log
        JideSplitPane splitPane = new JideSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setDividerSize(5);
        splitPane.add(tableScrollPane);
        splitPane.add(mLogPanel);

        add(splitPane, "grow");
    }

    private void wireListeners()
    {
        // Start/Stop/Clear buttons
        mControlPanel.addStartListener(e -> mController.startScan());
        mControlPanel.addStopListener(e -> mController.stopScan());
        mControlPanel.addClearListener(e -> mController.clearAll());

        // Right-click context menu on table
        mSignalTable.setComponentPopupMenu(createContextMenu());

        // Table selection listener — log selected signal info
        mSignalTable.getSelectionModel().addListSelectionListener(e -> {
            if(!e.getValueIsAdjusting())
            {
                int selectedRow = mSignalTable.getSelectedRow();
                if(selectedRow >= 0)
                {
                    int modelRow = mSignalTable.convertRowIndexToModel(selectedRow);
                    DetectedSignal signal = mTableModel.getSignalAt(modelRow);
                    if(signal != null)
                    {
                        mLogPanel.logInfo(String.format("Selected: %.4f MHz — power %.1f dB, BW %.1f kHz, " +
                            "detected %d times, status: %s",
                            signal.getFrequencyMHz(), signal.getPowerDb(), signal.getBandwidthKHz(),
                            signal.getDetectionCount(), signal.getStatus()));
                    }
                }
            }
        });
    }

    /**
     * Create the right-click context menu for the signal table.
     */
    private JPopupMenu createContextMenu()
    {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem ignoreItem = new JMenuItem("Ignore Signal");
        ignoreItem.addActionListener(e -> {
            DetectedSignal signal = getSelectedSignal();
            if(signal != null)
            {
                mController.ignoreSignal(signal);
            }
        });
        menu.add(ignoreItem);

        JMenuItem deleteItem = new JMenuItem("Delete Signal");
        deleteItem.addActionListener(e -> {
            DetectedSignal signal = getSelectedSignal();
            if(signal != null)
            {
                mController.deleteSignal(signal);
            }
        });
        menu.add(deleteItem);

        menu.add(new JSeparator());

        // Phase 3: Decoder trial identification
        JMenuItem identifyItem = new JMenuItem("Identify Now");
        identifyItem.setToolTipText("Run decoder trials to confirm the signal's protocol");
        identifyItem.addActionListener(e -> {
            DetectedSignal signal = getSelectedSignal();
            if(signal != null)
            {
                mController.identifySignal(signal);
            }
        });
        menu.add(identifyItem);

        JMenuItem cancelTrialItem = new JMenuItem("Cancel Identification");
        cancelTrialItem.setToolTipText("Cancel the current decoder trial");
        cancelTrialItem.addActionListener(e -> {
            mController.cancelIdentification();
        });
        menu.add(cancelTrialItem);

        menu.add(new JSeparator());

        JMenuItem createChannelItem = new JMenuItem("Create Channel (Future)");
        createChannelItem.setEnabled(false);
        createChannelItem.setToolTipText("Create a playlist channel from this identified signal");
        menu.add(createChannelItem);

        return menu;
    }

    /**
     * Get the currently selected signal from the table.
     */
    private DetectedSignal getSelectedSignal()
    {
        int selectedRow = mSignalTable.getSelectedRow();
        if(selectedRow >= 0)
        {
            int modelRow = mSignalTable.convertRowIndexToModel(selectedRow);
            return mTableModel.getSignalAt(modelRow);
        }
        return null;
    }
}
