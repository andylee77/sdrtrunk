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
package io.github.dsheirer.source.tuner.plutosdr;

import io.github.dsheirer.preference.source.ChannelizerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.ui.DiscoveredTunerModel;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.Dimension;

/**
 * Dialog for manually adding a PlutoSDR tuner by specifying the companion server host and port.
 *
 * <p>PlutoSDR devices connect over Ethernet and are not auto-discovered via USB enumeration.
 * This dialog allows the user to enter the host/port of the companion Python IQ-stream server
 * (pluto_server.py) and add the tuner to the discovered tuner list.</p>
 */
public class AddPlutoSdrTunerDialog extends JFrame
{
    private static final long serialVersionUID = 1L;
    private static final Logger mLog = LoggerFactory.getLogger(AddPlutoSdrTunerDialog.class);

    private final TunerManager mTunerManager;
    private final DiscoveredTunerModel mDiscoveredTunerModel;
    private final TunerConfigurationManager mTunerConfigurationManager;
    private final ChannelizerType mChannelizerType;

    private JTextField mHostTextField;
    private JSpinner   mPortSpinner;
    private JButton    mAddButton;
    private JButton    mCancelButton;

    /**
     * Constructs an instance.
     *
     * @param tunerManager               the tuner manager used to properly start and configure the new tuner
     * @param channelizerType            channelizer type preference
     */
    public AddPlutoSdrTunerDialog(TunerManager tunerManager, ChannelizerType channelizerType)
    {
        mTunerManager              = tunerManager;
        mDiscoveredTunerModel      = tunerManager.getDiscoveredTunerModel();
        mTunerConfigurationManager = tunerManager.getTunerConfigurationManager();
        mChannelizerType           = channelizerType;

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setTitle("Add PlutoSDR Tuner");
        setSize(new Dimension(420, 200));
        setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new MigLayout("insets 10", "[right][grow,fill]", "[][][][]"));

        // ---- Host ----
        content.add(new JLabel("Server Host:"));
        mHostTextField = new JTextField("localhost");
        mHostTextField.setToolTipText("<html>Hostname or IP address of the PlutoSDR companion server.<br>" +
                "For a server on this PC use <b>localhost</b>.<br>" +
                "For a remote server enter its IP (e.g. 192.168.120.106).</html>");
        content.add(mHostTextField, "wrap");

        // ---- Port ----
        content.add(new JLabel("Server Port:"));
        mPortSpinner = new JSpinner(new SpinnerNumberModel(1234, 1, 65535, 1));
        mPortSpinner.setToolTipText("TCP port of the PlutoSDR companion server (default: 1234)");
        content.add(mPortSpinner, "wrap");

        // ---- Info label ----
        content.add(new JLabel(""), "");
        JLabel infoLabel = new JLabel("<html><i>Start pluto_server.py before clicking Add.</i></html>");
        content.add(infoLabel, "wrap");

        // ---- Buttons ----
        content.add(new JLabel(""));
        JPanel buttonPanel = new JPanel(new MigLayout("insets 0", "[][]", ""));

        mAddButton = new JButton("Add");
        mAddButton.setToolTipText("Add the PlutoSDR tuner with the specified host and port");
        mAddButton.addActionListener(e -> onAdd());
        buttonPanel.add(mAddButton);

        mCancelButton = new JButton("Cancel");
        mCancelButton.addActionListener(e -> setVisible(false));
        buttonPanel.add(mCancelButton);

        content.add(buttonPanel, "wrap");

        setContentPane(content);
    }

    /**
     * Handles the Add button click: validates input, creates the configuration and discovered tuner,
     * persists the configuration, and adds the tuner to the model.
     */
    private void onAdd()
    {
        String host = mHostTextField.getText().trim();

        if(host.isEmpty())
        {
            JOptionPane.showMessageDialog(this,
                    "Please enter a server hostname or IP address.",
                    "Host Required",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        int port = (Integer) mPortSpinner.getValue();

        // Build a unique ID the same way DiscoveredPlutoSdrTuner does
        String uniqueId = "PlutoSDR:" + host + ":" + port;

        // Check for duplicate
        if(mDiscoveredTunerModel.getDiscoveredTuner(uniqueId) != null)
        {
            JOptionPane.showMessageDialog(this,
                    "A PlutoSDR tuner for " + host + ":" + port + " is already in the list.",
                    "Duplicate Tuner",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        mLog.info("Adding PlutoSDR tuner: host={} port={}", host, port);

        try
        {
            // Create and persist the configuration
            PlutoSdrTunerConfiguration config = new PlutoSdrTunerConfiguration(uniqueId);
            config.setHost(host);
            config.setPort(port);

            mTunerConfigurationManager.addTunerConfiguration(config);

            // Create the discovered tuner and route it through TunerManager.startAndConfigureTuner()
            // so that the status listener is registered, the tuner is started, and the configuration
            // is applied — exactly the same path used for tuners discovered at startup.
            // startAndConfigureTuner() internally calls addDiscoveredTuner() on the model.
            DiscoveredPlutoSdrTuner discoveredTuner = new DiscoveredPlutoSdrTuner(config, mChannelizerType);
            mTunerManager.startAndConfigureTuner(discoveredTuner);

            mLog.info("PlutoSDR tuner added: {}", uniqueId);
        }
        catch(Exception ex)
        {
            mLog.error("Error adding PlutoSDR tuner", ex);
            JOptionPane.showMessageDialog(this,
                    "Error adding PlutoSDR tuner: " + ex.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        setVisible(false);
        dispose();
    }
}
