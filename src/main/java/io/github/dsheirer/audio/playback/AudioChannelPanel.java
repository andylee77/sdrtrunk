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

import com.google.common.base.Joiner;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.AudioEvent;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.identifier.TalkgroupFormatPreference;
import io.github.dsheirer.properties.SystemProperties;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.settings.ColorSetting;
import io.github.dsheirer.settings.Setting;
import io.github.dsheirer.settings.SettingChangeListener;
import io.github.dsheirer.settings.SettingsManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/**
 * UI to wrap an audio channel and provide display of metadata and playback state information.
 */
public class AudioChannelPanel extends JPanel implements Listener<AudioEvent>, SettingChangeListener
{
    private static final long serialVersionUID = 1L;
    private static final Logger mLog = LoggerFactory.getLogger(AudioChannelPanel.class);

    public static final String PROPERTY_PREFIX = "audio.channel.panel.color.";
    public static final String PROPERTY_COLOR_BACKGROUND = PROPERTY_PREFIX + "background";
    public static final String PROPERTY_COLOR_LABEL = PROPERTY_PREFIX + "label";
    public static final String PROPERTY_COLOR_MUTED = PROPERTY_PREFIX + "muted";
    public static final String PROPERTY_COLOR_VALUE = PROPERTY_PREFIX + "value";

    private final AudioChannel mAudioChannel;
    private final AliasModel mAliasModel;
    private final IconModel mIconModel;
    private final SettingsManager mSettingsManager;
    private final UserPreferences mUserPreferences;
    private final TalkgroupFormatPreference mTalkgroupFormatPreference;
    private Identifier mIdentifier;
    private List<Alias> mAliases = Collections.EMPTY_LIST;
    private final Lock mLock = new ReentrantLock();

    private final Font mFont = new Font(Font.MONOSPACED, Font.PLAIN, 16);
    private final Color mBackgroundColor;
    private final Color mLabelColor;
    private final Color mMutedColor;
    private final Color mValueColor;
    private static final ImageIcon MUTED_ICON = IconModel.getScaledIcon("images/audio_muted.png", 18);
    private static final ImageIcon UNMUTED_ICON = IconModel.getScaledIcon("images/audio_unmuted.png", 18);
    private JButton mMuteButton;
    private JLabel mChannelName = new JLabel(" ");
    private final JLabel mIconLabel = new JLabel(" ");
    private final JLabel mIdentifierLabel = new JLabel("-----");
    private JComboBox<AudioChannelFilterItem> mRoutingCombo;
    private boolean mUpdatingCombo = false;
    private final Supplier<Set<String>> mActiveAliasListNamesSupplier;

    /**
     * Constructs an instance
     * @param audioChannel to wrap by this panel
     * @param aliasModel for alias lookup
     * @param iconModel for icon lookup
     * @param settingsManager for monitoring changes to tone insertion
     * @param userPreferences for lookup of tone and other preferences
     * @param activeAliasListNamesSupplier supplies the set of alias list names from active channels
     */
    public AudioChannelPanel(AudioChannel audioChannel, AliasModel aliasModel, IconModel iconModel,
                             SettingsManager settingsManager, UserPreferences userPreferences,
                             Supplier<Set<String>> activeAliasListNamesSupplier)
    {
        mIconModel = iconModel;
        mActiveAliasListNamesSupplier = activeAliasListNamesSupplier;
        mSettingsManager = settingsManager;
        mSettingsManager.addListener(this);
        mAliasModel = aliasModel;
        mUserPreferences = userPreferences;
        mTalkgroupFormatPreference = mUserPreferences.getTalkgroupFormatPreference();
        mAudioChannel = audioChannel;

        if(mAudioChannel != null)
        {
            mAudioChannel.addAudioEventListener(this);
            mAudioChannel.setIdentifierCollectionListener(new AudioMetadataProcessor());
        }

        mBackgroundColor = SystemProperties.getInstance().get(PROPERTY_COLOR_BACKGROUND, Color.BLACK);
        mLabelColor = SystemProperties.getInstance().get(PROPERTY_COLOR_LABEL, Color.LIGHT_GRAY);
        mMutedColor = SystemProperties.getInstance().get(PROPERTY_COLOR_MUTED, Color.RED);
        mValueColor = SystemProperties.getInstance().get(PROPERTY_COLOR_VALUE, Color.GREEN);

        init();
    }

    /**
     * Receives preference update notifications via the event bus
     * @param preferenceType that was updated
     */
    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.TALKGROUP_FORMAT)
        {
            updateLabels();
        }
    }

    public void dispose()
    {
        //Deregister from receiving preference update notifications
        MyEventBus.getGlobalEventBus().unregister(this);

        if(mAudioChannel != null)
        {
            mAudioChannel.removeAudioEventListener(this);
            mAudioChannel.removeAudioMetadataListener();
        }
    }

    private void init()
    {
        //Register to receive preference updates
        MyEventBus.getGlobalEventBus().register(this);

        setLayout(new MigLayout("align center center, insets 0 0 0 0",
            "[][][align right]0[grow,fill][]", ""));
        setBackground(mBackgroundColor);

        //Per-channel mute button
        mMuteButton = new JButton(UNMUTED_ICON);
        mMuteButton.setBorderPainted(false);
        mMuteButton.setContentAreaFilled(false);
        mMuteButton.setBackground(mBackgroundColor);
        mMuteButton.setPreferredSize(new Dimension(22, 22));
        mMuteButton.setToolTipText("Mute/Unmute this channel");
        if(mAudioChannel != null)
        {
            mMuteButton.addActionListener(e -> {
                boolean newMuted = !mAudioChannel.isMuted();
                mAudioChannel.setMuted(newMuted);
                mMuteButton.setIcon(newMuted ? MUTED_ICON : UNMUTED_ICON);
            });
        }
        else
        {
            mMuteButton.setEnabled(false);
        }
        add(mMuteButton);

        mChannelName = new JLabel(mAudioChannel != null ? mAudioChannel.getChannelName() : " ");
        mChannelName.setFont(mFont);
        mChannelName.setForeground(mLabelColor);
        add(mChannelName);

        mIconLabel.setFont(mFont);
        mIconLabel.setForeground(mValueColor);
        add(mIconLabel);

        mIdentifierLabel.setFont(mFont);
        mIdentifierLabel.setForeground(mValueColor);
        add(mIdentifierLabel, "wmin 10lp");

        //Routing filter combo box — starts with just Off/All, refreshes from active channels on open
        mRoutingCombo = createRoutingCombo();
        add(mRoutingCombo, "wmin 80lp, wmax 120lp");

        //Right-click context menu for per-talkgroup mute
        addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                if(e.isPopupTrigger())
                {
                    showTalkgroupMuteMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e)
            {
                if(e.isPopupTrigger())
                {
                    showTalkgroupMuteMenu(e);
                }
            }
        });
    }

    /**
     * Creates and configures the routing filter combo box.
     * Populates with Off, All, then system names and group names from the alias model.
     */
    private JComboBox<AudioChannelFilterItem> createRoutingCombo()
    {
        JComboBox<AudioChannelFilterItem> combo = new JComboBox<>();
        combo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        combo.setMaximumSize(new Dimension(120, 24));

        //Custom renderer to handle separator items
        combo.setRenderer(new DefaultListCellRenderer()
        {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus)
            {
                if(value instanceof AudioChannelFilterItem item && item.isSeparator())
                {
                    return new JSeparator(JSeparator.HORIZONTAL);
                }
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        });

        populateRoutingCombo(combo);

        //Refresh the combo contents each time the dropdown is opened, so it reflects
        //whatever channels are currently active (started via autostart, tuner tab, etc.)
        combo.addPopupMenuListener(new PopupMenuListener()
        {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e)
            {
                populateRoutingCombo(combo);
            }
            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        combo.addActionListener(e -> {
            if(mUpdatingCombo || mAudioChannel == null)
            {
                return;
            }

            Object selected = combo.getSelectedItem();

            if(selected instanceof AudioChannelFilterItem item)
            {
                if(item.isSeparator())
                {
                    //Don't allow separator selection - revert
                    return;
                }
                mAudioChannel.getFilter().setFilter(item.getMode(), item.getValue());
            }
        });

        return combo;
    }

    /**
     * Populates the routing combo box with Off, All, then system names and group names
     * from alias lists that belong to currently active (processing) channels.
     * Preserves the current selection if it still exists after repopulation.
     */
    private void populateRoutingCombo(JComboBox<AudioChannelFilterItem> combo)
    {
        mUpdatingCombo = true;

        try
        {
            //Remember the current filter state so we can restore it
            AudioChannelFilterMode currentMode = null;
            String currentValue = null;

            if(mAudioChannel != null)
            {
                currentMode = mAudioChannel.getFilter().getMode();
                currentValue = mAudioChannel.getFilter().getFilterValue();
            }

            combo.removeAllItems();

            //Fixed items
            AudioChannelFilterItem offItem = new AudioChannelFilterItem(AudioChannelFilterMode.OFF, null, "Off");
            AudioChannelFilterItem allItem = new AudioChannelFilterItem(AudioChannelFilterMode.ALL, null, "All");
            combo.addItem(offItem);
            combo.addItem(allItem);

            //Get the set of alias list names from active (processing) channels
            Set<String> activeAliasListNames = mActiveAliasListNamesSupplier != null
                ? mActiveAliasListNamesSupplier.get() : Collections.emptySet();

            //Collect systems and groups from aliases belonging to active channels only
            Set<String> systemNames = new HashSet<>();
            Set<String> groupNames = new HashSet<>();

            for(Alias alias : mAliasModel.getAliases())
            {
                String listName = alias.getAliasListName();
                if(listName == null || listName.isEmpty() || !activeAliasListNames.contains(listName))
                {
                    continue;
                }

                systemNames.add(listName);

                String group = alias.getGroup();
                if(group != null && !group.isEmpty())
                {
                    groupNames.add(group);
                }
            }

            //Add systems
            if(!systemNames.isEmpty())
            {
                combo.addItem(new AudioChannelFilterItem(null, null, "--- Systems ---"));
                List<String> sortedSystems = new ArrayList<>(systemNames);
                Collections.sort(sortedSystems);
                for(String system : sortedSystems)
                {
                    combo.addItem(new AudioChannelFilterItem(AudioChannelFilterMode.SYSTEM, system, system));
                }
            }

            //Add groups
            if(!groupNames.isEmpty())
            {
                combo.addItem(new AudioChannelFilterItem(null, null, "--- Groups ---"));
                List<String> sortedGroups = new ArrayList<>(groupNames);
                Collections.sort(sortedGroups);
                for(String group : sortedGroups)
                {
                    combo.addItem(new AudioChannelFilterItem(AudioChannelFilterMode.GROUP, group, group));
                }
            }

            //Restore previous selection if it still exists, otherwise default to All
            AudioChannelFilterItem toSelect = allItem;

            if(currentMode != null)
            {
                for(int i = 0; i < combo.getItemCount(); i++)
                {
                    AudioChannelFilterItem item = combo.getItemAt(i);
                    if(item.getMode() == currentMode &&
                       java.util.Objects.equals(item.getValue(), currentValue))
                    {
                        toSelect = item;
                        break;
                    }
                }
            }

            combo.setSelectedItem(toSelect);
        }
        finally
        {
            mUpdatingCombo = false;
        }
    }

    /**
     * Shows a right-click context menu with per-talkgroup mute checkboxes.
     * Collects talkgroup IDs from all aliases that match the current filter scope.
     */
    private void showTalkgroupMuteMenu(MouseEvent e)
    {
        if(mAudioChannel == null)
        {
            return;
        }

        AudioChannelFilter filter = mAudioChannel.getFilter();

        if(filter.getMode() == AudioChannelFilterMode.OFF)
        {
            return;
        }

        //Collect talkgroup IDs in scope
        List<TalkgroupEntry> talkgroups = new ArrayList<>();

        for(Alias alias : mAliasModel.getAliases())
        {
            //Filter by system or group if applicable
            if(filter.getMode() == AudioChannelFilterMode.SYSTEM)
            {
                if(!alias.getAliasListName().equals(filter.getFilterValue()))
                {
                    continue;
                }
            }
            else if(filter.getMode() == AudioChannelFilterMode.GROUP)
            {
                if(alias.getGroup() == null || !alias.getGroup().equals(filter.getFilterValue()))
                {
                    continue;
                }
            }

            for(AliasID id : alias.getAliasIdentifiers())
            {
                if(id.getType() == AliasIDType.TALKGROUP && id instanceof Talkgroup tg)
                {
                    talkgroups.add(new TalkgroupEntry(tg.getValue(), alias.getName()));
                }
            }
        }

        if(talkgroups.isEmpty())
        {
            return;
        }

        //Sort by talkgroup ID
        talkgroups.sort(Comparator.comparingInt(TalkgroupEntry::id));

        //Build popup menu
        JPopupMenu popup = new JPopupMenu("Mute Talkgroups");

        //Mute All / Unmute All
        JMenuItem muteAll = new JMenuItem("Mute All");
        muteAll.addActionListener(ae -> {
            Set<Integer> allIds = new HashSet<>();
            for(TalkgroupEntry entry : talkgroups)
            {
                allIds.add(entry.id());
            }
            filter.muteAllTalkgroups(allIds);
        });
        popup.add(muteAll);

        JMenuItem unmuteAll = new JMenuItem("Unmute All");
        unmuteAll.addActionListener(ae -> filter.unmuteAllTalkgroups());
        popup.add(unmuteAll);

        popup.add(new JPopupMenu.Separator());

        //Per-talkgroup checkboxes
        for(TalkgroupEntry entry : talkgroups)
        {
            String label = entry.id() + (entry.name() != null ? " - " + entry.name() : "");
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(label, filter.isTalkgroupMuted(entry.id()));
            item.addActionListener(ae -> filter.setTalkgroupMuted(entry.id(), item.isSelected()));
            popup.add(item);
        }

        popup.show(this, e.getX(), e.getY());
    }

    /**
     * Simple record for talkgroup ID + alias name pairs used in the per-TG mute menu
     */
    private record TalkgroupEntry(int id, String name) {}

    @Override
    public void receive(final AudioEvent audioEvent)
    {
        switch(audioEvent.getType())
        {
            case AUDIO_STOPPED:
                EventQueue.invokeLater(this::resetLabels);
                break;
            case AUDIO_MUTED:
            case AUDIO_UNMUTED:
                EventQueue.invokeLater(() -> {
                    boolean muted = mAudioChannel.isMuted();
                    mMuteButton.setIcon(muted ? MUTED_ICON : UNMUTED_ICON);
                });
                break;
            default:
                break;
        }
    }

    /**
     * Resets the from and to labels.
     */
    private void resetLabels()
    {
        //Protect access to mIdentifier and mAliases
        mLock.lock();

        try
        {
            boolean updated = mIdentifier != null;
            mIdentifier = null;
            mAliases = Collections.EMPTY_LIST;

            //Hold the lock through the label update
            if(updated)
            {
                updateLabels();
            }
        }
        finally
        {
            mLock.unlock();
        }
    }

    private void updateIdentifiers(IdentifierCollection identifierCollection)
    {
        if(identifierCollection == null || identifierCollection.isEmpty())
        {
            resetLabels();
            return;
        }

        List<Identifier> toIds = identifierCollection.getIdentifiers(IdentifierClass.USER, Role.TO);

        if(toIds.isEmpty())
        {
            resetLabels();
            return;
        }

        boolean updated = false;

        //Protect access to mIdentifier and mAliases
        mLock.lock();

        try
        {
            if(toIds.size() == 1)
            {
                Identifier currentIdentifier = mIdentifier;

                if(currentIdentifier == null || currentIdentifier != toIds.get(0))
                {
                    mIdentifier = toIds.get(0);
                    AliasList aliasList = mAliasModel.getAliasList(identifierCollection);

                    if(aliasList != null)
                    {
                        mAliases = aliasList.getAliases(mIdentifier);
                    }
                    updated = true;
                }
            }
            else
            {
                mIdentifier = toIds.get(0);
                AliasList aliasList = mAliasModel.getAliasList(identifierCollection);

                if(aliasList != null)
                {
                    mAliases = aliasList.getAliases(mIdentifier);
                }
                updated = true;
            }

            //Hold the lock through the label update
            if(updated)
            {
                updateLabels();
            }
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Updates the alias label with text and icon from the alias.
     */
    private void updateLabels()
    {
        String identifier = null;
        String iconName = null;

        //Protect access to mIdentifier and mAliases
        mLock.lock();

        try
        {
            if(mAliases.size() == 1)
            {
                identifier = mAliases.get(0).getName();
                iconName = mAliases.get(0).getIconName();
            }
            else if(mAliases.size() > 1)
            {
                identifier = Joiner.on(", ").skipNulls().join(mAliases);
            }

            if(identifier == null && mIdentifier != null)
            {
                identifier = mTalkgroupFormatPreference.format(mIdentifier);
            }

            if(identifier == null)
            {
                identifier = "-----";
            }

            final ImageIcon icon = iconName != null ? mIconModel.getIcon(iconName, 18) : null;
            final String identifierText = identifier;

            EventQueue.invokeLater(() -> {
                mIdentifierLabel.setText(identifierText);
                mIconLabel.setIcon(icon);
            });
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Processes audio metadata to update this panel's display values
     */
    public class AudioMetadataProcessor implements Listener<IdentifierCollection>
    {
        @Override
        public void receive(final IdentifierCollection identifierCollection)
        {
            updateIdentifiers(identifierCollection);
        }
    }

    @Override
    public void settingChanged(Setting setting)
    {
        if(setting instanceof ColorSetting)
        {
            ColorSetting colorSetting = (ColorSetting)setting;

            switch(colorSetting.getColorSettingName())
            {
                case CHANNEL_STATE_LABEL_DECODER:
                    EventQueue.invokeLater(() -> {
                        if(mIdentifierLabel != null)
                        {
                            mIdentifierLabel.setForeground(mLabelColor);
                        }
                        if(mIconLabel != null)
                        {
                            mIconLabel.setForeground(mLabelColor);
                        }
                    });
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public void settingDeleted(Setting setting)
    {
    }
}
