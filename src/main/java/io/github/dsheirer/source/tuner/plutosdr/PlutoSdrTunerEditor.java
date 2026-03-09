/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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
package io.github.dsheirer.source.tuner.plutosdr;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.ui.TunerEditor;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ChangeListener;
import java.awt.Color;
import java.awt.Font;

/**
 * Swing editor panel for the PlutoSDR tuner.
 *
 * <p>Allows the user to configure the companion server host/port, sample rate, RF bandwidth,
 * RF gain and AGC.  Also shows a live device-info panel with temperature, RSSI, hardware
 * model, serial number and firmware version received from the Python server.</p>
 */
public class PlutoSdrTunerEditor extends TunerEditor<PlutoSdrTuner, PlutoSdrTunerConfiguration>
{
    private static final long serialVersionUID = 1L;
    private static final Logger mLog = LoggerFactory.getLogger(PlutoSdrTunerEditor.class);

    /** Available sample rates for the AD9361 (in samples per second). */
    private static final Integer[] SAMPLE_RATES = {
            521_000,
            1_000_000,
            1_500_000,
            2_000_000,
            2_500_000,
            3_000_000,
            4_000_000,
            5_000_000,
            6_000_000,
            8_000_000,
            10_000_000,
            12_000_000,
            15_000_000,
            20_000_000,
            30_000_000,
            40_000_000,
            56_000_000,
            61_440_000
    };

    /**
     * RF bandwidth options for the AD9361 (Hz).
     * The first entry (0) means "Auto" – let the driver choose (~0.75 × sample_rate).
     * Range: 200 kHz – 56 MHz.
     */
    private static final Integer[] RF_BANDWIDTHS = {
            0,           // Auto
            200_000,
            400_000,
            600_000,
            800_000,
            1_000_000,
            1_500_000,
            2_000_000,
            2_500_000,
            3_000_000,
            4_000_000,
            5_000_000,
            6_000_000,
            8_000_000,
            10_000_000,
            12_000_000,
            15_000_000,
            18_000_000,
            20_000_000,
            25_000_000,
            28_000_000,
            36_000_000,
            40_000_000,
            56_000_000
    };

    // UI controls – connection
    private JTextField mHostTextField;
    private JSpinner   mPortSpinner;

    // UI controls – radio settings
    private JComboBox<Integer> mSampleRateCombo;
    private JComboBox<Integer> mRfBandwidthCombo;
    private JSpinner   mGainSpinner;
    private JCheckBox  mAgcCheckBox;
    private JCheckBox  mFrequencyLockCheckBox;
    private JButton    mApplyButton;

    // UI controls – device info panel (read-only, updated from server)
    private JLabel mDeviceModelLabel;
    private JLabel mDeviceSerialLabel;
    private JLabel mDeviceFwLabel;
    private JLabel mDeviceTempLabel;
    private JLabel mDeviceRssiLabel;
    private JLabel mDeviceActualBwLabel;
    private JLabel mDeviceTunedFreqLabel;
    private JLabel mDeviceSampleRateLabel;

    /** Timer that refreshes the device-info labels from the controller's cached info. */
    private Timer mDeviceInfoRefreshTimer;

    /**
     * The ChangeListener that was registered on the base-class PPM spinner.
     * We remove it and replace it with our own deferred-apply listener so that
     * arrow-button clicks do NOT immediately trigger a hardware reconnect.
     */
    private ChangeListener mOriginalPpmChangeListener;

    /**
     * True once we have detached the base-class PPM spinner listener and replaced
     * it with our own deferred-apply behaviour.
     */
    private boolean mPpmListenerReplaced = false;

    /**
     * Constructs an instance.
     *
     * @param userPreferences for wide-band recordings
     * @param tunerManager    to save configuration
     * @param discoveredTuner to control
     */
    public PlutoSdrTunerEditor(UserPreferences userPreferences, TunerManager tunerManager,
                                DiscoveredTuner discoveredTuner)
    {
        super(userPreferences, tunerManager, discoveredTuner);
        init();
        tunerStatusUpdated();
    }

    @Override
    public long getMinimumTunableFrequency()
    {
        return PlutoSdrTunerConfiguration.MINIMUM_FREQUENCY_HZ;
    }

    @Override
    public long getMaximumTunableFrequency()
    {
        return PlutoSdrTunerConfiguration.MAXIMUM_FREQUENCY_HZ;
    }

    // =========================================================================
    // Layout
    // =========================================================================

    private void init()
    {
        setLayout(new MigLayout("fill,wrap 3", "[right][grow,fill][fill]",
                "[][][][][][][][][][][][][][][][grow]"));

        add(new JLabel("Tuner:"));
        add(getTunerIdLabel(), "span 2");

        add(new JLabel("Status:"));
        add(getTunerStatusLabel(), "span 2");

        add(getButtonPanel(), "span,align left");

        add(new JSeparator(), "span,growx,push");

        add(new JLabel("Frequency (MHz):"));
        add(getFrequencyPanel(), "span 2");

        add(new JSeparator(), "span,growx,push");

        add(new JLabel("Server Host:"));
        add(getHostTextField(), "span 2");

        add(new JLabel("Server Port:"));
        add(getPortSpinner(), "span 2");

        add(new JSeparator(), "span,growx,push");

        add(new JLabel("Sample Rate:"));
        add(getSampleRateCombo(), "span 2");

        add(new JLabel("RF Bandwidth:"));
        add(getRfBandwidthCombo(), "span 2");

        add(new JLabel("RF Gain (dB):"));
        add(getGainSpinner());
        add(getAgcCheckBox());

        add(new JLabel(""));
        add(getFrequencyLockCheckBox(), "span 2");

        add(new JSeparator(), "span,growx,push");

        // Device info panel
        add(buildDeviceInfoPanel(), "span 3, growx");

        add(new JSeparator(), "span,growx,push");

        add(getApplyButton(), "span,align center");
    }

    /**
     * Builds the read-only device-info sub-panel that shows live data from the server.
     */
    private javax.swing.JPanel buildDeviceInfoPanel()
    {
        javax.swing.JPanel panel = new javax.swing.JPanel(
                new MigLayout("fill,wrap 2", "[right][grow,fill]", "[][][][][][]"));
        panel.setBorder(BorderFactory.createTitledBorder("Device Info  (from server)"));

        Font labelFont = new Font(Font.MONOSPACED, Font.PLAIN, 11);

        mDeviceModelLabel    = makeInfoLabel(labelFont);
        mDeviceSerialLabel   = makeInfoLabel(labelFont);
        mDeviceFwLabel       = makeInfoLabel(labelFont);
        mDeviceTempLabel     = makeInfoLabel(labelFont);
        mDeviceRssiLabel     = makeInfoLabel(labelFont);
        mDeviceActualBwLabel = makeInfoLabel(labelFont);
        mDeviceTunedFreqLabel  = makeInfoLabel(labelFont);
        mDeviceSampleRateLabel = makeInfoLabel(labelFont);

        panel.add(new JLabel("Tuned Freq:"));
        panel.add(mDeviceTunedFreqLabel);

        panel.add(new JLabel("Sample Rate:"));
        panel.add(mDeviceSampleRateLabel);

        panel.add(new JLabel("Model:"));
        panel.add(mDeviceModelLabel);

        panel.add(new JLabel("Serial:"));
        panel.add(mDeviceSerialLabel);

        panel.add(new JLabel("Firmware:"));
        panel.add(mDeviceFwLabel);

        panel.add(new JLabel("Die Temp:"));
        panel.add(mDeviceTempLabel);

        panel.add(new JLabel("RSSI:"));
        panel.add(mDeviceRssiLabel);

        panel.add(new JLabel("Actual RF BW:"));
        panel.add(mDeviceActualBwLabel);

        return panel;
    }

    private static JLabel makeInfoLabel(Font font)
    {
        JLabel label = new JLabel("\u2014");   // em-dash = "not yet known"
        label.setFont(font);
        label.setForeground(Color.DARK_GRAY);
        return label;
    }

    // =========================================================================
    // TunerEditor overrides
    // =========================================================================

    // =========================================================================
    // PPM spinner – deferred apply (only fires on Apply button, not on each click)
    // =========================================================================

    /**
     * Replaces the base-class PPM spinner's immediate-apply ChangeListener with a no-op so
     * that arrow-button clicks only update the displayed value without triggering a hardware
     * reconnect.  The actual PPM value is applied when the user clicks "Apply & Reconnect".
     *
     * <p>This must be called after the base-class {@link #init()} has created the spinner,
     * because the spinner is lazily initialised by {@link #getFrequencyCorrectionSpinner()}.</p>
     */
    private void replacePpmSpinnerListener()
    {
        if(mPpmListenerReplaced)
        {
            return;
        }

        JSpinner spinner = getFrequencyCorrectionSpinner();

        // The base class registers exactly one ChangeListener (mFrequencyAndCorrectionChangeListener).
        // Remove all existing listeners and replace with a no-op so arrow clicks don't reconnect.
        javax.swing.event.ChangeListener[] listeners = spinner.getChangeListeners();
        for(javax.swing.event.ChangeListener l : listeners)
        {
            spinner.removeChangeListener(l);
        }

        // Add a lightweight listener that just saves the config value without applying it to hardware.
        // The hardware reconnect happens only when the user clicks "Apply & Reconnect".
        spinner.addChangeListener(e ->
        {
            if(!isLoading() && hasConfiguration())
            {
                // Persist the new PPM value to the configuration so it survives a restart,
                // but do NOT call setFrequencyCorrection() on the controller yet.
                double ppm = ((SpinnerNumberModel) spinner.getModel()).getNumber().doubleValue();
                getConfiguration().setFrequencyCorrection(ppm);
                saveConfiguration();
            }
        });

        mPpmListenerReplaced = true;
    }

    @Override
    protected void tunerStatusUpdated()
    {
        setLoading(true);

        if(hasTuner())
        {
            getTunerIdLabel().setText(getTuner().getPreferredName());
        }
        else
        {
            getTunerIdLabel().setText(getDiscoveredTuner().getId());
        }

        String status = getDiscoveredTuner().getTunerStatus().toString();
        if(getDiscoveredTuner().hasErrorMessage())
        {
            status += " - " + getDiscoveredTuner().getErrorMessage();
        }
        getTunerStatusLabel().setText(status);

        getButtonPanel().updateControls();
        getFrequencyPanel().updateControls();

        boolean hasTuner = hasTuner();
        getHostTextField().setEnabled(!hasTuner);   // host/port can only be changed when stopped
        getPortSpinner().setEnabled(!hasTuner);
        getSampleRateCombo().setEnabled(hasTuner && !getTuner().getTunerController().isLockedSampleRate());
        getRfBandwidthCombo().setEnabled(hasTuner);
        getGainSpinner().setEnabled(hasTuner);
        getAgcCheckBox().setEnabled(hasTuner);
        getFrequencyLockCheckBox().setEnabled(hasTuner);
        getApplyButton().setEnabled(hasTuner);

        if(hasConfiguration())
        {
            getHostTextField().setText(getConfiguration().getHost());
            getPortSpinner().setValue(getConfiguration().getPort());
            getSampleRateCombo().setSelectedItem(getConfiguration().getSampleRate());
            getRfBandwidthCombo().setSelectedItem(getConfiguration().getRfBandwidth());
            getGainSpinner().setValue(getConfiguration().getRfGain());
            getAgcCheckBox().setSelected(getConfiguration().isAgcEnabled());
        }

        // Sync frequency lock checkbox with controller state
        if(hasTuner)
        {
            getFrequencyLockCheckBox().setSelected(getTuner().getController().isFrequencyLocked());
        }

        // Replace the base-class PPM spinner listener with our deferred-apply version.
        // Must be done after the spinner has been created (lazy init in getFrequencyCorrectionSpinner()).
        replacePpmSpinnerListener();

        // Start or stop the device-info refresh timer based on whether we have a running tuner
        if(hasTuner && getTuner().getController().isRunning())
        {
            startDeviceInfoTimer();
        }
        else
        {
            stopDeviceInfoTimer();
            resetDeviceInfoLabels();
        }

        setLoading(false);
    }

    @Override
    public void setTunerLockState(boolean locked)
    {
        getFrequencyPanel().updateControls();
        getSampleRateCombo().setEnabled(!locked);
        getRfBandwidthCombo().setEnabled(!locked);
    }

    // =========================================================================
    // Device info timer
    // =========================================================================

    private void startDeviceInfoTimer()
    {
        if(mDeviceInfoRefreshTimer == null)
        {
            // Refresh the device-info labels every 5 seconds on the EDT
            mDeviceInfoRefreshTimer = new Timer(5_000, e -> refreshDeviceInfoLabels());
            mDeviceInfoRefreshTimer.setInitialDelay(500);  // first update quickly after connect
            mDeviceInfoRefreshTimer.start();
        }
        else if(!mDeviceInfoRefreshTimer.isRunning())
        {
            mDeviceInfoRefreshTimer.restart();
        }
    }

    private void stopDeviceInfoTimer()
    {
        if(mDeviceInfoRefreshTimer != null)
        {
            mDeviceInfoRefreshTimer.stop();
        }
    }

    /**
     * Reads the latest {@link PlutoSdrDeviceInfo} from the controller and updates the labels.
     * Must be called on the EDT (the Swing Timer guarantees this).
     */
    private void refreshDeviceInfoLabels()
    {
        if(!hasTuner())
        {
            return;
        }

        PlutoSdrDeviceInfo info = getTuner().getController().getDeviceInfo();

        mDeviceModelLabel.setText(info.getHwModel());
        mDeviceSerialLabel.setText(info.getHwSerial());
        mDeviceFwLabel.setText(info.getFwVersion());
        mDeviceTempLabel.setText(info.getTemperatureString());
        mDeviceRssiLabel.setText(info.getRssi());
        mDeviceActualBwLabel.setText(info.getActualRfBandwidthString());
        mDeviceTunedFreqLabel.setText(info.getTunedFrequencyString());
        mDeviceSampleRateLabel.setText(info.getSampleRateString());
    }

    private void resetDeviceInfoLabels()
    {
        String dash = "\u2014";
        if(mDeviceModelLabel != null)
        {
            mDeviceModelLabel.setText(dash);
            mDeviceSerialLabel.setText(dash);
            mDeviceFwLabel.setText(dash);
            mDeviceTempLabel.setText(dash);
            mDeviceRssiLabel.setText(dash);
            mDeviceActualBwLabel.setText(dash);
            mDeviceTunedFreqLabel.setText(dash);
            mDeviceSampleRateLabel.setText(dash);
        }
    }

    // =========================================================================
    // Control accessors / lazy initialisation
    // =========================================================================

    private JTextField getHostTextField()
    {
        if(mHostTextField == null)
        {
            mHostTextField = new JTextField("localhost");
            mHostTextField.setToolTipText("<html>Hostname or IP address of the PlutoSDR companion server.<br>" +
                    "For a server running on this PC use <b>localhost</b>.<br>" +
                    "For a remote server enter its IP address (e.g. 192.168.1.100).</html>");
        }
        return mHostTextField;
    }

    private JSpinner getPortSpinner()
    {
        if(mPortSpinner == null)
        {
            mPortSpinner = new JSpinner(new SpinnerNumberModel(1234, 1, 65535, 1));
            mPortSpinner.setToolTipText("TCP port number of the PlutoSDR companion server (default: 1234)");
        }
        return mPortSpinner;
    }

    private JComboBox<Integer> getSampleRateCombo()
    {
        if(mSampleRateCombo == null)
        {
            mSampleRateCombo = new JComboBox<>(SAMPLE_RATES);
            mSampleRateCombo.setSelectedItem(PlutoSdrTunerConfiguration.DEFAULT_SAMPLE_RATE);
            mSampleRateCombo.setToolTipText("<html>AD9361 sample rate in samples per second.<br>" +
                    "Higher rates require more CPU and network bandwidth.</html>");
            mSampleRateCombo.setEnabled(false);
            mSampleRateCombo.addActionListener(e ->
            {
                if(!isLoading() && hasTuner())
                {
                    Integer rate = (Integer) getSampleRateCombo().getSelectedItem();
                    if(rate != null)
                    {
                        try
                        {
                            getTuner().getController().setSampleRate(rate);
                            adjustForSampleRate(rate);
                            save();
                        }
                        catch(SourceException ex)
                        {
                            JOptionPane.showMessageDialog(PlutoSdrTunerEditor.this,
                                    "PlutoSDR - couldn't apply sample rate [" + rate + "]: " + ex.getMessage(),
                                    "Sample Rate Error", JOptionPane.ERROR_MESSAGE);
                            mLog.error("PlutoSDR - couldn't apply sample rate [{}]", rate, ex);
                        }
                    }
                }
            });
        }
        return mSampleRateCombo;
    }

    private JComboBox<Integer> getRfBandwidthCombo()
    {
        if(mRfBandwidthCombo == null)
        {
            mRfBandwidthCombo = new JComboBox<>(RF_BANDWIDTHS);
            mRfBandwidthCombo.setSelectedItem(PlutoSdrTunerConfiguration.RF_BANDWIDTH_AUTO);
            mRfBandwidthCombo.setRenderer(new javax.swing.DefaultListCellRenderer()
            {
                @Override
                public java.awt.Component getListCellRendererComponent(
                        javax.swing.JList<?> list, Object value, int index,
                        boolean isSelected, boolean cellHasFocus)
                {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if(value instanceof Integer bw)
                    {
                        if(bw == 0)
                        {
                            setText("Auto (= sample rate, full bandwidth)");
                        }
                        else if(bw >= 1_000_000)
                        {
                            setText(String.format("%.3f MHz", bw / 1_000_000.0));
                        }
                        else
                        {
                            setText(String.format("%d kHz", bw / 1_000));
                        }
                    }
                    return this;
                }
            });
            mRfBandwidthCombo.setToolTipText("<html>AD9361 RX RF bandwidth (200 kHz – 56 MHz).<br>" +
                    "<b>Auto</b> sets the filter equal to the sample rate (full IQ bandwidth).<br>" +
                    "Set a narrower bandwidth to improve adjacent-channel rejection.<br>" +
                    "The actual bandwidth applied is shown in the Device Info panel below.</html>");
            mRfBandwidthCombo.setEnabled(false);
            mRfBandwidthCombo.addActionListener(e ->
            {
                if(!isLoading() && hasTuner())
                {
                    Integer bw = (Integer) getRfBandwidthCombo().getSelectedItem();
                    if(bw != null)
                    {
                        try
                        {
                            getTuner().getController().setRfBandwidth(bw);
                            save();
                        }
                        catch(SourceException ex)
                        {
                            JOptionPane.showMessageDialog(PlutoSdrTunerEditor.this,
                                    "PlutoSDR - couldn't apply RF bandwidth [" + bw + "]: " + ex.getMessage(),
                                    "RF Bandwidth Error", JOptionPane.ERROR_MESSAGE);
                            mLog.error("PlutoSDR - couldn't apply RF bandwidth [{}]", bw, ex);
                        }
                    }
                }
            });
        }
        return mRfBandwidthCombo;
    }

    private JSpinner getGainSpinner()
    {
        if(mGainSpinner == null)
        {
            // AD9361 manual gain range: 0 – 73 dB
            mGainSpinner = new JSpinner(new SpinnerNumberModel(30, 0, 73, 1));
            mGainSpinner.setToolTipText("<html>RF gain in dB (0 – 73 dB).<br>" +
                    "Disabled when AGC is enabled.</html>");
            mGainSpinner.setEnabled(false);
            mGainSpinner.addChangeListener(e ->
            {
                if(!isLoading() && hasTuner() && !getAgcCheckBox().isSelected())
                {
                    int gain = (Integer) getGainSpinner().getValue();
                    try
                    {
                        getTuner().getController().setRfGain(gain);
                        save();
                    }
                    catch(SourceException ex)
                    {
                        JOptionPane.showMessageDialog(PlutoSdrTunerEditor.this,
                                "PlutoSDR - couldn't apply gain [" + gain + "]: " + ex.getMessage(),
                                "Gain Error", JOptionPane.ERROR_MESSAGE);
                        mLog.error("PlutoSDR - couldn't apply gain [{}]", gain, ex);
                    }
                }
            });
        }
        return mGainSpinner;
    }

    private JCheckBox getAgcCheckBox()
    {
        if(mAgcCheckBox == null)
        {
            mAgcCheckBox = new JCheckBox("AGC");
            mAgcCheckBox.setToolTipText("Enable Automatic Gain Control (disables manual gain slider)");
            mAgcCheckBox.setEnabled(false);
            mAgcCheckBox.addActionListener(e ->
            {
                if(!isLoading() && hasTuner())
                {
                    boolean agc = getAgcCheckBox().isSelected();
                    getGainSpinner().setEnabled(!agc);
                    try
                    {
                        getTuner().getController().setAgcEnabled(agc);
                        save();
                    }
                    catch(SourceException ex)
                    {
                        JOptionPane.showMessageDialog(PlutoSdrTunerEditor.this,
                                "PlutoSDR - couldn't apply AGC setting: " + ex.getMessage(),
                                "AGC Error", JOptionPane.ERROR_MESSAGE);
                        mLog.error("PlutoSDR - couldn't apply AGC setting", ex);
                    }
                }
            });
        }
        return mAgcCheckBox;
    }

    private JCheckBox getFrequencyLockCheckBox()
    {
        if(mFrequencyLockCheckBox == null)
        {
            mFrequencyLockCheckBox = new JCheckBox("Lock Centre Frequency");
            mFrequencyLockCheckBox.setToolTipText("<html>When checked, the tuner will NOT retune when channels are activated.<br>" +
                    "Use this to keep the waterfall overlay aligned with signals at 6 MHz / 8 MHz.<br>" +
                    "Uncheck to allow the channelizer to automatically centre on the active channels.</html>");
            mFrequencyLockCheckBox.setEnabled(false);
            mFrequencyLockCheckBox.addActionListener(e ->
            {
                if(!isLoading() && hasTuner())
                {
                    getTuner().getController().setFrequencyLocked(mFrequencyLockCheckBox.isSelected());
                }
            });
        }
        return mFrequencyLockCheckBox;
    }

    /**
     * Apply button – saves all settings (including the current PPM spinner value) and
     * reconnects to the server.  This is the only action that triggers a hardware reconnect
     * for PPM changes, gain changes, and host/port changes.
     */
    private JButton getApplyButton()
    {
        if(mApplyButton == null)
        {
            mApplyButton = new JButton("Apply & Reconnect");
            mApplyButton.setToolTipText("<html>Save the current settings and reconnect to the server.<br>" +
                    "Use this after changing PPM, host, port, sample rate, or gain.</html>");
            mApplyButton.setEnabled(false);
            mApplyButton.addActionListener(e ->
            {
                if(hasConfiguration())
                {
                    save();

                    if(hasTuner())
                    {
                        // Apply the PPM correction from the spinner to the controller.
                        // setFrequencyCorrection() updates the FrequencyController AND triggers
                        // a hardware reconnect via setTunedFrequency() → reconnect().
                        // We do NOT call apply() afterwards because apply() calls
                        // super.apply() which calls setFrequency() AND setFrequencyCorrection()
                        // again — causing a SECOND redundant TCP reconnect.
                        // Instead, we just apply the PPM + do a single reconnect with the
                        // current PlutoSDR-specific settings (sample rate, gain, etc.) which
                        // were already applied by their individual change listeners.
                        double ppm = ((SpinnerNumberModel) getFrequencyCorrectionSpinner().getModel())
                                .getNumber().doubleValue();
                        try
                        {
                            getTuner().getController().setFrequencyCorrection(ppm);
                        }
                        catch(SourceException ex)
                        {
                            mLog.warn("PlutoSDR - could not apply PPM correction {}: {}", ppm, ex.getMessage());
                        }

                        // NOTE: We intentionally do NOT call apply(getConfiguration()) here.
                        // setFrequencyCorrection() already triggered a hardware reconnect with
                        // the PPM-corrected frequency.  Calling apply() would trigger a second
                        // reconnect because TunerController.apply() calls setFrequency() and
                        // setFrequencyCorrection() again internally.
                    }
                }
            });
        }
        return mApplyButton;
    }

    // =========================================================================
    // Sample-rate / bandwidth helpers
    // =========================================================================

    /**
     * Called after the user changes the sample rate.
     *
     * <p>If the RF bandwidth combo is set to a fixed value that is now <em>wider</em> than the
     * new sample rate (which would be meaningless — the analog filter can't be wider than the
     * digital sample rate), we automatically switch it back to <b>Auto</b> so the server will
     * set the filter equal to the new sample rate.  If the user has already chosen Auto, or a
     * fixed value that is ≤ the new sample rate, we leave it alone.</p>
     *
     * @param newSampleRate the newly selected sample rate in Hz
     */
    @Override
    protected void adjustForSampleRate(int newSampleRate)
    {
        if(mRfBandwidthCombo == null)
        {
            return;
        }

        Integer currentBw = (Integer) getRfBandwidthCombo().getSelectedItem();
        if(currentBw == null || currentBw == 0)
        {
            // Already Auto — nothing to do
            return;
        }

        if(currentBw > newSampleRate)
        {
            // Fixed BW is wider than the new sample rate — reset to Auto
            mLog.info("PlutoSDR editor: RF bandwidth {} Hz > new sample rate {} Hz — resetting to Auto",
                    currentBw, newSampleRate);
            setLoading(true);   // suppress the combo's action listener while we change it
            getRfBandwidthCombo().setSelectedItem(0);
            setLoading(false);

            // Apply the Auto bandwidth to the controller immediately
            if(hasTuner())
            {
                try
                {
                    getTuner().getController().setRfBandwidth(0);
                }
                catch(SourceException ex)
                {
                    mLog.warn("PlutoSDR editor: could not reset RF bandwidth to Auto after sample rate change: {}",
                            ex.getMessage());
                }
            }
        }
        // else: fixed BW ≤ new sample rate — leave it as-is
    }

    // =========================================================================
    // Save
    // =========================================================================

    @Override
    public void save()
    {
        if(hasConfiguration() && !isLoading())
        {
            getConfiguration().setFrequency(getFrequencyControl().getFrequency());
            getConfiguration().setMinimumFrequency(getMinimumFrequencyTextField().getFrequency());
            getConfiguration().setMaximumFrequency(getMaximumFrequencyTextField().getFrequency());
            double ppm = ((SpinnerNumberModel) getFrequencyCorrectionSpinner().getModel()).getNumber().doubleValue();
            getConfiguration().setFrequencyCorrection(ppm);
            getConfiguration().setAutoPPMCorrectionEnabled(getAutoPPMCheckBox().isSelected());

            getConfiguration().setHost(getHostTextField().getText().trim());
            getConfiguration().setPort((Integer) getPortSpinner().getValue());
            getConfiguration().setSampleRate((Integer) getSampleRateCombo().getSelectedItem());
            getConfiguration().setRfBandwidth((Integer) getRfBandwidthCombo().getSelectedItem());
            getConfiguration().setRfGain((Integer) getGainSpinner().getValue());
            getConfiguration().setAgcEnabled(getAgcCheckBox().isSelected());

            saveConfiguration();
        }
    }
}
