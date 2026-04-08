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

import io.github.dsheirer.gui.analyzer.SignalAnalyzerConfig;
import java.awt.Color;
import java.awt.event.ActionListener;
import java.util.Hashtable;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.event.ChangeListener;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

/**
 * Control panel for the signal analyzer.
 * Contains Start/Stop buttons, mode selection (Auto/Manual), threshold slider,
 * and other controls.
 */
public class AnalyzerControlPanel extends JPanel
{
    private JButton mStartButton;
    private JButton mStopButton;
    private JButton mClearButton;
    private JRadioButton mAutoModeButton;
    private JRadioButton mManualModeButton;
    private JSlider mThresholdSlider;
    private JLabel mThresholdValueLabel;
    private JLabel mStatusLabel;

    private final SignalAnalyzerConfig mConfig;

    public AnalyzerControlPanel(SignalAnalyzerConfig config)
    {
        mConfig = config;
        init();
    }

    private void init()
    {
        setLayout(new MigLayout("insets 5", "[][][][grow,fill][][][][]", "[]"));
        setBorder(BorderFactory.createTitledBorder("Signal Analyzer Controls"));

        // Start button
        mStartButton = new JButton("Start");
        mStartButton.setIcon(IconFontSwing.buildIcon(FontAwesome.PLAY, 14, new Color(0, 128, 0)));
        mStartButton.setToolTipText("Start signal detection scan");
        add(mStartButton);

        // Stop button
        mStopButton = new JButton("Stop");
        mStopButton.setIcon(IconFontSwing.buildIcon(FontAwesome.STOP, 14, Color.RED));
        mStopButton.setToolTipText("Stop signal detection scan");
        mStopButton.setEnabled(false);
        add(mStopButton);

        // Separator
        add(new JLabel("  |  "));

        // Mode selection
        mAutoModeButton = new JRadioButton("Auto", true);
        mAutoModeButton.setToolTipText("Automatically detect and track all signals");
        mManualModeButton = new JRadioButton("Manual");
        mManualModeButton.setToolTipText("Manually select signals for analysis");

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(mAutoModeButton);
        modeGroup.add(mManualModeButton);

        add(new JLabel("Mode:"));
        add(mAutoModeButton);
        add(mManualModeButton);

        // Threshold slider
        add(new JLabel("  Threshold:"));
        mThresholdSlider = new JSlider(3, 30, (int)mConfig.getThresholdDb());
        mThresholdSlider.setToolTipText("dB above noise floor to detect signals");

        Hashtable<Integer, JComponent> labels = new Hashtable<>();
        labels.put(3, new JLabel("3"));
        labels.put(10, new JLabel("10"));
        labels.put(20, new JLabel("20"));
        labels.put(30, new JLabel("30"));
        mThresholdSlider.setLabelTable(labels);
        mThresholdSlider.setMajorTickSpacing(10);
        mThresholdSlider.setMinorTickSpacing(1);
        mThresholdSlider.setPaintTicks(true);
        mThresholdSlider.setPaintLabels(true);
        mThresholdSlider.addChangeListener(e -> {
            int value = mThresholdSlider.getValue();
            mConfig.setThresholdDb(value);
            mThresholdValueLabel.setText(value + " dB");
        });
        add(mThresholdSlider, "width 150!");

        mThresholdValueLabel = new JLabel((int)mConfig.getThresholdDb() + " dB");
        add(mThresholdValueLabel);

        // Clear button
        add(new JLabel("  "));
        mClearButton = new JButton("Clear All");
        mClearButton.setIcon(IconFontSwing.buildIcon(FontAwesome.TRASH_O, 14, Color.DARK_GRAY));
        mClearButton.setToolTipText("Clear all detected signals");
        add(mClearButton);

        // Status label
        add(new JLabel("  "));
        mStatusLabel = new JLabel("Idle");
        mStatusLabel.setForeground(Color.GRAY);
        add(mStatusLabel);
    }

    /**
     * Register a listener for the Start button.
     */
    public void addStartListener(ActionListener listener)
    {
        mStartButton.addActionListener(listener);
    }

    /**
     * Register a listener for the Stop button.
     */
    public void addStopListener(ActionListener listener)
    {
        mStopButton.addActionListener(listener);
    }

    /**
     * Register a listener for the Clear button.
     */
    public void addClearListener(ActionListener listener)
    {
        mClearButton.addActionListener(listener);
    }

    /**
     * Register a listener for threshold changes.
     */
    public void addThresholdChangeListener(ChangeListener listener)
    {
        mThresholdSlider.addChangeListener(listener);
    }

    /**
     * Returns true if Auto mode is selected.
     */
    public boolean isAutoMode()
    {
        return mAutoModeButton.isSelected();
    }

    /**
     * Update the UI to reflect scanning state.
     */
    public void setScanning(boolean scanning)
    {
        mStartButton.setEnabled(!scanning);
        mStopButton.setEnabled(scanning);
        mAutoModeButton.setEnabled(!scanning);
        mManualModeButton.setEnabled(!scanning);

        if(scanning)
        {
            mStatusLabel.setText("Scanning...");
            mStatusLabel.setForeground(new Color(0, 128, 0));
        }
        else
        {
            mStatusLabel.setText("Idle");
            mStatusLabel.setForeground(Color.GRAY);
        }
    }

    /**
     * Update the status label text.
     */
    public void setStatusText(String text)
    {
        mStatusLabel.setText(text);
    }

    /**
     * Update the status label with signal count info.
     */
    public void updateSignalCount(int total, int valid)
    {
        if(mStopButton.isEnabled())
        {
            mStatusLabel.setText(String.format("Scanning... %d signals (%d valid)", total, valid));
        }
    }
}
