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
package io.github.dsheirer.audio.playback;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.AudioEvent;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.preference.PreferenceEditorType;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.settings.SettingsManager;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Set;
import java.util.function.Supplier;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.sound.sampled.FloatControl;
import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;

/**
 * Audio playback panel
 */
public class AudioPanel extends JPanel implements Listener<AudioEvent>
{
    private final AliasModel mAliasModel;
    private final AudioPlaybackManager mAudioPlaybackManager;
    private final IconModel mIconModel;
    private final SettingsManager mSettingsManager;
    private final UserPreferences mUserPreferences;
    private final Supplier<Set<String>> mActiveAliasListNamesSupplier;
    private AudioChannelsPanel mAudioChannelsPanel;
    private JSlider mVolumeSlider;

    /**
     * Constructs an instance
     * @param iconModel for icon lookup
     * @param userPreferences for preference lookup
     * @param settingsManager to monitor for changes
     * @param audioPlaybackManager for accessing the audio output
     * @param aliasModel for alias lookup
     * @param activeAliasListNamesSupplier supplies the set of alias list names from active channels
     */
    public AudioPanel(IconModel iconModel, UserPreferences userPreferences, SettingsManager settingsManager,
                      AudioPlaybackManager audioPlaybackManager, AliasModel aliasModel,
                      Supplier<Set<String>> activeAliasListNamesSupplier)
    {
        mIconModel = iconModel;
        mSettingsManager = settingsManager;
        mAudioPlaybackManager = audioPlaybackManager;
        mAliasModel = aliasModel;
        mUserPreferences = userPreferences;
        mActiveAliasListNamesSupplier = activeAliasListNamesSupplier;
        mAudioPlaybackManager.addAudioEventListener(this);
        init();
    }

    /**
     * Initialize the display
     */
    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0", "[grow,fill]0[180lp!]", "[fill]"));
        setBackground(Color.BLACK);

        mAudioChannelsPanel = new AudioChannelsPanel(mIconModel, mUserPreferences, mSettingsManager, mAudioPlaybackManager, mAliasModel, mActiveAliasListNamesSupplier);
        add(mAudioChannelsPanel);

        //Volume slider on the right side
        mVolumeSlider = createVolumeSlider();
        JPanel volumePanel = new JPanel(new MigLayout("insets 2 4 2 4, fill", "[fill]", "[center]"));
        volumePanel.setBackground(Color.BLACK);
        volumePanel.add(mVolumeSlider, "growx");
        add(volumePanel);

        addMouseListener(new MouseSelectionListener());
    }

    /**
     * Creates a horizontal volume slider that controls the audio output gain.
     * If the audio output has no gain control, the slider is disabled.
     */
    private JSlider createVolumeSlider()
    {
        JSlider slider = new JSlider(JSlider.HORIZONTAL, 0, 100, 50);
        slider.setBackground(Color.BLACK);
        slider.setForeground(Color.LIGHT_GRAY);
        slider.setToolTipText("Volume (double-click to reset)");
        slider.setMajorTickSpacing(50);
        slider.setMinorTickSpacing(10);
        slider.setPaintTicks(true);
        slider.setPaintLabels(false);
        slider.setFocusable(false);

        //Initialize from current gain control if available
        AudioOutput output = mAudioPlaybackManager.getAudioOutput();
        if(output != null && output.hasGainControl())
        {
            FloatControl gain = output.getGainControl();
            slider.setValue(gainToSlider(gain.getValue(), gain));
            slider.setEnabled(true);
        }
        else
        {
            slider.setEnabled(false);
        }

        slider.addChangeListener(e -> {
            AudioOutput out = mAudioPlaybackManager.getAudioOutput();
            if(out != null && out.hasGainControl())
            {
                FloatControl gain = out.getGainControl();
                float target = sliderToGain(slider.getValue(), gain);
                gain.setValue(target);
            }
        });

        //Double-click to reset to center (0 dB)
        slider.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                if(e.getClickCount() == 2)
                {
                    slider.setValue(50);
                }
            }
        });

        return slider;
    }

    /**
     * Converts a gain value (dB) to a slider position (0-100).
     * 50 = 0 dB (no gain), 0 = minimum, 100 = maximum.
     */
    private static int gainToSlider(float gainDb, FloatControl control)
    {
        if(gainDb == 0.0f) return 50;
        if(gainDb < 0.0f) return 50 - (int)(gainDb / control.getMinimum() * 50.0f);
        return 50 + (int)(gainDb / control.getMaximum() * 50.0f);
    }

    /**
     * Converts a slider position (0-100) to a gain value (dB).
     */
    private static float sliderToGain(int value, FloatControl control)
    {
        if(value == 50) return 0.0f;
        if(value < 50) return (float)(50 - value) / 50.0f * control.getMinimum();
        return (float)(value - 50) / 50.0f * control.getMaximum();
    }

    /**
     * Synchronizes the volume slider with the current AudioOutput's gain control after
     * audio device configuration changes.
     */
    private void syncVolumeSlider()
    {
        AudioOutput output = mAudioPlaybackManager.getAudioOutput();
        if(output != null && output.hasGainControl())
        {
            FloatControl gain = output.getGainControl();
            mVolumeSlider.setValue(gainToSlider(gain.getValue(), gain));
            mVolumeSlider.setEnabled(true);
        }
        else
        {
            mVolumeSlider.setValue(50);
            mVolumeSlider.setEnabled(false);
        }
    }

    /**
     * Receive audio event notifications from the audio playback controller
     */
    @Override
    public void receive(AudioEvent event)
    {
        switch(event.getType())
        {
            case AUDIO_CONFIGURATION_CHANGE_STARTED:
                break;
            case AUDIO_CONFIGURATION_CHANGE_COMPLETE:
                EventQueue.invokeLater(() -> {
                    remove(mAudioChannelsPanel);
                    mAudioChannelsPanel.dispose();
                    mAudioChannelsPanel = new AudioChannelsPanel(mIconModel, mUserPreferences, mSettingsManager, mAudioPlaybackManager, mAliasModel, mActiveAliasListNamesSupplier);
                    add(mAudioChannelsPanel);
                    mAudioChannelsPanel.repaint();
                    syncVolumeSlider();
                    revalidate();
                    repaint();
                });
                break;
            default:
                break;
        }
    }

    /**
     * Audio output mute control menu item.
     */
    public static class AudioOutputMuteItem extends JMenuItem
    {
        private final AudioOutput mAudioOutput;

        /**
         * Constructs an instance
         * @param audioOutput to mute/unmute
         */
        public AudioOutputMuteItem(AudioOutput audioOutput)
        {
            super(audioOutput.isMuted() ? "Unmute" : "Mute");
            mAudioOutput = audioOutput;
            addActionListener(e -> mAudioOutput.setMuted(!mAudioOutput.isMuted()));
        }
    }

    /**
     * Mouse listener
     */
    public class MouseSelectionListener implements MouseListener
    {
        @Override
        public void mouseClicked(MouseEvent event)
        {
            if(SwingUtilities.isRightMouseButton(event))
            {
                JPopupMenu popup = new JPopupMenu();
                JMenuItem outputMenu = new JMenuItem("Audio Playback Device ...");
                Icon icon = IconFontSwing.buildIcon(FontAwesome.COG, 14);
                outputMenu.setIcon(icon);
                outputMenu.addActionListener(e -> MyEventBus.getGlobalEventBus()
                        .post(new ViewUserPreferenceEditorRequest(PreferenceEditorType.AUDIO_OUTPUT)));
                popup.add(outputMenu);

                //Add optional gain controller for th eaudio output
                if(mAudioPlaybackManager.getAudioOutput() != null && mAudioPlaybackManager.getAudioOutput().hasGainControl())
                {
                    popup.add(new JPopupMenu.Separator());
                    JMenuItem volume = new JMenuItem("Audio Volume");
                    volume.setEnabled(false);
                    Icon volumeIcon = IconFontSwing.buildIcon(FontAwesome.VOLUME_UP, 14);
                    volume.setIcon(volumeIcon);
                    popup.add(volume);
                    popup.add(new VolumeSlider(mAudioPlaybackManager.getAudioOutput().getGainControl()));
                }

                popup.show(event.getComponent(), event.getX(), event.getY());
            }
        }

        @Override
        public void mousePressed(MouseEvent e)
        {
        }

        @Override
        public void mouseReleased(MouseEvent e)
        {
        }

        @Override
        public void mouseEntered(MouseEvent e)
        {
        }

        @Override
        public void mouseExited(MouseEvent e)
        {
        }
    }

    /**
     * Audio volume (gain) adjustment slider control
     */
    public static class VolumeSlider extends JSlider
    {
        private final FloatControl mFloatControl;

        /**
         * Constructs an instance
         * @param control to be controlled
         */
        public VolumeSlider(FloatControl control)
        {
            super(0, 100, 0);

            setMajorTickSpacing(25);
            setMinorTickSpacing(5);
            setPaintTicks(true);
            setPaintLabels(true);
            mFloatControl = control;
            setValue(getIntegerValue(mFloatControl.getValue()));
            addChangeListener(event -> mFloatControl.setValue(
                getFloatValue(VolumeSlider.this.getValue())));

            addMouseListener(new MouseListener()
            {
                @Override
                public void mouseClicked(MouseEvent event)
                {
                    if(event.getClickCount() == 2)
                    {
                        VolumeSlider.this.setValue(50);
                    }
                }
                public void mouseReleased(MouseEvent arg0) {}
                public void mousePressed(MouseEvent arg0) {}
                public void mouseExited(MouseEvent arg0) {}
                public void mouseEntered(MouseEvent arg0) {}
            });
        }

        /**
         * Converts the integer value to a floating point value to use in the
         * float control.  Assumes an integer value of 50 is the 0.0 dB mid
         * point (ie no gain ) value.
         */
        private int getIntegerValue(float value)
        {
            if(value == 0.0f)
            {
                return 50;
            }
            else if(value < 0.0f)
            {
                float ratio = value / mFloatControl.getMinimum();

                return 50 - (int) (ratio * 50.0f);
            }
            else
            {
                float ratio = value / mFloatControl.getMaximum();

                return 50 + (int) (ratio * 50.0f);
            }
        }

        private float getFloatValue(int value)
        {
            if(value == 50)
            {
                return 0.0f;
            }
            else if(value < 50)
            {
                return (float) (50 - value) / 50.0f * mFloatControl.getMinimum();
            }
            else
            {
                return (float) (value - 50) / 50.0f * mFloatControl.getMaximum();
            }
        }
    }

}
