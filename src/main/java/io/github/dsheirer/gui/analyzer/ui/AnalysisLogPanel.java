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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;
import net.miginfocom.swing.MigLayout;

/**
 * Scrolling text area that displays analysis log messages with timestamps.
 */
public class AnalysisLogPanel extends JPanel
{
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_LOG_LINES = 1000;

    private JTextArea mLogTextArea;
    private JScrollPane mScrollPane;
    private JButton mClearButton;
    private JButton mExportButton;
    private boolean mAutoScroll = true;
    private int mLineCount = 0;

    public AnalysisLogPanel()
    {
        init();
    }

    private void init()
    {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Analysis Log"));

        mLogTextArea = new JTextArea();
        mLogTextArea.setEditable(false);
        mLogTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        mLogTextArea.setBackground(new Color(20, 20, 30));
        mLogTextArea.setForeground(new Color(200, 200, 200));
        mLogTextArea.setCaretColor(new Color(200, 200, 200));

        // Enable auto-scroll
        DefaultCaret caret = (DefaultCaret)mLogTextArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        mScrollPane = new JScrollPane(mLogTextArea);
        add(mScrollPane, BorderLayout.CENTER);

        // Button panel
        JPanel buttonPanel = new JPanel(new MigLayout("insets 2", "[][grow,fill][]"));
        mClearButton = new JButton("Clear Log");
        mClearButton.addActionListener(e -> clearLog());
        buttonPanel.add(mClearButton);

        buttonPanel.add(new JPanel()); // spacer

        mExportButton = new JButton("Export CSV");
        mExportButton.setToolTipText("Export detected signals to CSV file");
        buttonPanel.add(mExportButton);

        add(buttonPanel, BorderLayout.SOUTH);
    }

    /**
     * Append a log message with timestamp.
     * Thread-safe — dispatches to EDT if needed.
     */
    public void log(String message)
    {
        String timestamped = "[" + LocalTime.now().format(TIME_FORMAT) + "] " + message + "\n";

        if(SwingUtilities.isEventDispatchThread())
        {
            appendText(timestamped);
        }
        else
        {
            SwingUtilities.invokeLater(() -> appendText(timestamped));
        }
    }

    /**
     * Log a message with a specific color tag prefix.
     */
    public void logInfo(String message)
    {
        log(message);
    }

    public void logDetection(String message)
    {
        log("\u2022 " + message); // bullet point
    }

    public void logWarning(String message)
    {
        log("\u26A0 " + message); // warning triangle
    }

    public void logSuccess(String message)
    {
        log("\u2713 " + message); // checkmark
    }

    public void logError(String message)
    {
        log("\u2717 " + message); // X mark
    }

    /**
     * Append text to the log, trimming old lines if exceeding maximum.
     */
    private void appendText(String text)
    {
        mLogTextArea.append(text);
        mLineCount++;

        // Trim old lines if exceeding maximum
        if(mLineCount > MAX_LOG_LINES)
        {
            String content = mLogTextArea.getText();
            int firstNewline = content.indexOf('\n');
            if(firstNewline >= 0)
            {
                mLogTextArea.replaceRange("", 0, firstNewline + 1);
                mLineCount--;
            }
        }
    }

    /**
     * Clear all log messages.
     */
    public void clearLog()
    {
        mLogTextArea.setText("");
        mLineCount = 0;
    }

    /**
     * Get the full log text content.
     */
    public String getLogText()
    {
        return mLogTextArea.getText();
    }

    /**
     * Get the Export CSV button for external wiring.
     */
    public JButton getExportButton()
    {
        return mExportButton;
    }

    /**
     * Set auto-scroll behavior.
     */
    public void setAutoScroll(boolean autoScroll)
    {
        mAutoScroll = autoScroll;
        DefaultCaret caret = (DefaultCaret)mLogTextArea.getCaret();
        caret.setUpdatePolicy(autoScroll ? DefaultCaret.ALWAYS_UPDATE : DefaultCaret.NEVER_UPDATE);
    }
}
