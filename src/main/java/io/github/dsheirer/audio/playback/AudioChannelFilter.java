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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Audio channel routing filter with per-talkgroup mute control.
 *
 * Each audio channel has one of these filters to determine which audio segments it accepts.
 * The filter operates at the routing level (in AudioPlaybackManager) to decide which segments
 * are offered to a channel. Per-talkgroup mute operates at the playback level (in AudioChannel)
 * to output silence for muted talkgroups without discarding the segment.
 *
 * This separation of concerns ensures the global mute button always works correctly.
 */
public class AudioChannelFilter
{
    private AudioChannelFilterMode mMode = AudioChannelFilterMode.ALL;
    private String mFilterValue = null;
    private final Set<Integer> mMutedTalkgroups = ConcurrentHashMap.newKeySet();

    /**
     * Current filter mode
     */
    public AudioChannelFilterMode getMode()
    {
        return mMode;
    }

    /**
     * Filter value (system name or group name), null for OFF/ALL modes
     */
    public String getFilterValue()
    {
        return mFilterValue;
    }

    /**
     * Sets the routing filter mode and value.
     * Clears per-talkgroup mute state when the filter changes.
     */
    public void setFilter(AudioChannelFilterMode mode, String value)
    {
        mMode = mode;
        mFilterValue = value;
        mMutedTalkgroups.clear();
    }

    /**
     * Indicates if this channel is off (disabled)
     */
    public boolean isOff()
    {
        return mMode == AudioChannelFilterMode.OFF;
    }

    /**
     * Determines if the audio segment is accepted by this filter.
     * Does NOT consider per-talkgroup mute — that is handled separately in AudioChannel.getAudio().
     *
     * @param segment the audio segment to evaluate
     * @param aliasModel for alias/group lookup
     * @return true if the segment should be routed to this channel
     */
    public boolean accepts(AudioSegment segment, AliasModel aliasModel)
    {
        if(mMode == AudioChannelFilterMode.OFF)
        {
            return false;
        }

        if(mMode == AudioChannelFilterMode.ALL)
        {
            return true;
        }

        IdentifierCollection identifiers = segment.getIdentifierCollection();

        if(identifiers == null)
        {
            return false;
        }

        if(mMode == AudioChannelFilterMode.SYSTEM)
        {
            AliasListConfigurationIdentifier aliasConfig = identifiers.getAliasListConfiguration();
            return aliasConfig != null && mFilterValue != null
                && mFilterValue.equals(aliasConfig.getValue());
        }

        if(mMode == AudioChannelFilterMode.GROUP)
        {
            List<Identifier> toIds = identifiers.getIdentifiers(IdentifierClass.USER, Role.TO);

            for(Identifier id : toIds)
            {
                AliasList aliasList = aliasModel.getAliasList(identifiers);

                if(aliasList != null)
                {
                    List<Alias> aliases = aliasList.getAliases(id);

                    for(Alias alias : aliases)
                    {
                        if(mFilterValue != null && mFilterValue.equals(alias.getGroup()))
                        {
                            return true;
                        }
                    }
                }
            }

            return false;
        }

        return false;
    }

    /**
     * Checks if the current audio segment's talkgroup is in the per-TG mute set.
     *
     * @param segment the audio segment to check
     * @return true if the segment's talkgroup is muted
     */
    public boolean isTalkgroupMutedForSegment(AudioSegment segment)
    {
        if(mMutedTalkgroups.isEmpty() || segment == null)
        {
            return false;
        }

        IdentifierCollection identifiers = segment.getIdentifierCollection();

        if(identifiers == null)
        {
            return false;
        }

        List<Identifier> toIds = identifiers.getIdentifiers(IdentifierClass.USER, Role.TO);

        for(Identifier id : toIds)
        {
            if(id instanceof TalkgroupIdentifier tgId)
            {
                if(mMutedTalkgroups.contains(tgId.getValue()))
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Indicates if a specific talkgroup ID is muted
     */
    public boolean isTalkgroupMuted(int talkgroupId)
    {
        return mMutedTalkgroups.contains(talkgroupId);
    }

    /**
     * Sets the mute state for a specific talkgroup ID
     */
    public void setTalkgroupMuted(int talkgroupId, boolean muted)
    {
        if(muted)
        {
            mMutedTalkgroups.add(talkgroupId);
        }
        else
        {
            mMutedTalkgroups.remove(talkgroupId);
        }
    }

    /**
     * Mutes all talkgroups in the provided set
     */
    public void muteAllTalkgroups(Set<Integer> talkgroups)
    {
        mMutedTalkgroups.addAll(talkgroups);
    }

    /**
     * Unmutes all talkgroups
     */
    public void unmuteAllTalkgroups()
    {
        mMutedTalkgroups.clear();
    }

    /**
     * Returns a copy of the muted talkgroup set
     */
    public Set<Integer> getMutedTalkgroups()
    {
        return Set.copyOf(mMutedTalkgroups);
    }
}
