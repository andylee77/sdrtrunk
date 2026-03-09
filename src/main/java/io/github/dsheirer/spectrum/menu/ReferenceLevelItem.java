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
package io.github.dsheirer.spectrum.menu;

import io.github.dsheirer.spectrum.SpectrumPanel;
import io.github.dsheirer.spectrum.WaterfallPanel;
import java.util.Hashtable;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/**
 * Reference Level slider menu item — allows the user to shift the spectrum and waterfall
 * displays up or down in dB so the noise floor sits at a comfortable position regardless
 * of hardware gain settings.
 *
 * <p>Range: -60 dB to +60 dB in 1 dB steps, default 0 dB.</p>
 * <p>Positive values shift the spectrum upward (raise the apparent signal level on screen).</p>
 * <p>Negative values shift the spectrum downward.</p>
 *
 * <p>Both the {@link SpectrumPanel} and {@link WaterfallPanel} are updated together so that
 * the spectrum trace and the waterfall color mapping stay in sync.</p>
 */
public class ReferenceLevelItem extends JSlider
{
    private static final long serialVersionUID = 1L;

    private static final int MIN_DB = -60;
    private static final int MAX_DB = 60;

    /**
     * Constructs an instance.
     *
     * @param spectrumPanel  the spectrum panel to adjust
     * @param waterfallPanel the waterfall panel to adjust in sync with the spectrum panel
     */
    public ReferenceLevelItem(SpectrumPanel spectrumPanel, WaterfallPanel waterfallPanel)
    {
        super(MIN_DB, MAX_DB, (int)spectrumPanel.getReferenceLevelOffset());

        Hashtable<Integer, JLabel> labels = new Hashtable<>();
        labels.put(MIN_DB, new JLabel(MIN_DB + " dB"));
        labels.put(-30, new JLabel("-30 dB"));
        labels.put(0, new JLabel("0 dB"));
        labels.put(30, new JLabel("+30 dB"));
        labels.put(MAX_DB, new JLabel("+" + MAX_DB + " dB"));

        setLabelTable(labels);
        setMajorTickSpacing(10);
        setMinorTickSpacing(5);
        setPaintTicks(true);
        setPaintLabels(true);
        setPreferredSize(new java.awt.Dimension(300, 60));

        addChangeListener(new ChangeListener()
        {
            @Override
            public void stateChanged(ChangeEvent e)
            {
                float offsetDb = (float)getValue();
                spectrumPanel.setReferenceLevelOffset(offsetDb);
                waterfallPanel.setReferenceLevelOffset(offsetDb);
            }
        });
    }
}
