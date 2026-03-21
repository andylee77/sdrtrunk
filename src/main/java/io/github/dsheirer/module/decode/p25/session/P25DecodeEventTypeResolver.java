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
package io.github.dsheirer.module.decode.p25.session;

import io.github.dsheirer.log.LoggingSuppressor;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacOpcode;
import io.github.dsheirer.module.decode.p25.reference.ServiceOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure utility class for resolving P25 opcodes and service options into DecodeEventType values.
 *
 * Extracted from P25CallSessionManager to reduce its size and improve testability.
 * All methods are static — no instance state.
 *
 * Responsibilities:
 * - Map Phase 1 Opcode → DecodeEventType
 * - Map Phase 2 MacOpcode → DecodeEventType
 * - Determine if event type is encrypted or patch-group
 * - Upgrade non-encrypted event types to encrypted variants
 */
public final class P25DecodeEventTypeResolver
{
    private static final Logger mLog = LoggerFactory.getLogger(P25DecodeEventTypeResolver.class);
    private static final LoggingSuppressor LOGGING_SUPPRESSOR = new LoggingSuppressor(mLog);

    private P25DecodeEventTypeResolver() {} // Utility class — no instantiation

    /**
     * Resolves a Phase 1 Opcode and ServiceOptions into a DecodeEventType.
     *
     * @param opcode Phase 1 TSBK opcode (may be null)
     * @param serviceOptions service options from the grant (may be null)
     * @param current the current event type to fall back to if opcode is unrecognized
     * @return resolved DecodeEventType, never null
     */
    public static DecodeEventType resolve(Opcode opcode, ServiceOptions serviceOptions, DecodeEventType current)
    {
        boolean encrypted = serviceOptions != null && serviceOptions.isEncrypted();

        DecodeEventType type = null;

        if(opcode != null)
        {
            type = switch(opcode)
            {
                case OSP_GROUP_VOICE_CHANNEL_GRANT, OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE,
                     OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE_EXPLICIT ->
                        encrypted ? DecodeEventType.CALL_GROUP_ENCRYPTED : DecodeEventType.CALL_GROUP;
                case OSP_UNIT_TO_UNIT_VOICE_CHANNEL_GRANT, OSP_UNIT_TO_UNIT_VOICE_CHANNEL_GRANT_UPDATE ->
                        encrypted ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED : DecodeEventType.CALL_UNIT_TO_UNIT;
                case OSP_TELEPHONE_INTERCONNECT_VOICE_CHANNEL_GRANT,
                     OSP_TELEPHONE_INTERCONNECT_VOICE_CHANNEL_GRANT_UPDATE ->
                        encrypted ? DecodeEventType.CALL_INTERCONNECT_ENCRYPTED : DecodeEventType.CALL_INTERCONNECT;
                case OSP_SNDCP_DATA_CHANNEL_GRANT, OSP_GROUP_DATA_CHANNEL_GRANT, OSP_INDIVIDUAL_DATA_CHANNEL_GRANT ->
                        encrypted ? DecodeEventType.DATA_CALL_ENCRYPTED : DecodeEventType.DATA_CALL;
                case MOTOROLA_OSP_GROUP_REGROUP_CHANNEL_GRANT, MOTOROLA_OSP_GROUP_REGROUP_CHANNEL_UPDATE ->
                        encrypted ? DecodeEventType.CALL_PATCH_GROUP_ENCRYPTED : DecodeEventType.CALL_PATCH_GROUP;
                default -> type;
            };
        }

        if(type == null)
        {
            type = current;
            if(opcode != null)
            {
                LOGGING_SUPPRESSOR.error(opcode.name(), 2, "Unrecognized opcode for determining decode " +
                        "event type: " + opcode.name());
            }
        }

        if(type == null)
        {
            type = encrypted ? DecodeEventType.CALL_ENCRYPTED : DecodeEventType.CALL;
        }

        return type;
    }

    /**
     * Resolves a Phase 2 MacOpcode and ServiceOptions into a DecodeEventType.
     *
     * @param macOpcode Phase 2 MAC opcode
     * @param serviceOptions service options from the grant (may be null)
     * @param current the current event type to fall back to if opcode is unrecognized
     * @return resolved DecodeEventType, never null
     */
    public static DecodeEventType resolve(MacOpcode macOpcode, ServiceOptions serviceOptions, DecodeEventType current)
    {
        boolean encrypted = serviceOptions != null && serviceOptions.isEncrypted();

        DecodeEventType type = null;

        switch(macOpcode)
        {
            case PUSH_TO_TALK:
                type = (current != null) ? current :
                        (encrypted ? DecodeEventType.CALL_GROUP_ENCRYPTED : DecodeEventType.CALL_GROUP);
                break;
            case TDMA_01_GROUP_VOICE_CHANNEL_USER_ABBREVIATED:
            case TDMA_05_GROUP_VOICE_CHANNEL_GRANT_UPDATE_MULTIPLE_IMPLICIT:
            case TDMA_21_GROUP_VOICE_CHANNEL_USER_EXTENDED:
            case TDMA_25_GROUP_VOICE_CHANNEL_GRANT_UPDATE_MULTIPLE_EXPLICIT:
            case PHASE1_40_GROUP_VOICE_CHANNEL_GRANT_IMPLICIT:
            case PHASE1_42_GROUP_VOICE_CHANNEL_GRANT_UPDATE_IMPLICIT:
            case PHASE1_C0_GROUP_VOICE_CHANNEL_GRANT_EXPLICIT:
            case PHASE1_C3_GROUP_VOICE_CHANNEL_GRANT_UPDATE_EXPLICIT:
                type = encrypted ? DecodeEventType.CALL_GROUP_ENCRYPTED : DecodeEventType.CALL_GROUP;
                break;
            case MOTOROLA_80_GROUP_REGROUP_VOICE_CHANNEL_USER_ABBREVIATED:
            case MOTOROLA_83_GROUP_REGROUP_VOICE_CHANNEL_UPDATE:
            case PHASE1_90_GROUP_REGROUP_VOICE_CHANNEL_USER_ABBREVIATED:
            case MOTOROLA_A0_GROUP_REGROUP_VOICE_CHANNEL_USER_EXTENDED:
            case MOTOROLA_A3_GROUP_REGROUP_CHANNEL_GRANT_IMPLICIT:
            case MOTOROLA_A4_GROUP_REGROUP_CHANNEL_GRANT_EXPLICIT:
            case MOTOROLA_A5_GROUP_REGROUP_CHANNEL_GRANT_UPDATE:
            case L3HARRIS_B0_GROUP_REGROUP_EXPLICIT_ENCRYPTION_COMMAND:
                type = encrypted ? DecodeEventType.CALL_PATCH_GROUP_ENCRYPTED : DecodeEventType.CALL_PATCH_GROUP;
                break;
            case TDMA_02_UNIT_TO_UNIT_VOICE_CHANNEL_USER_ABBREVIATED:
            case TDMA_22_UNIT_TO_UNIT_VOICE_CHANNEL_USER_EXTENDED:
            case PHASE1_44_UNIT_TO_UNIT_VOICE_SERVICE_CHANNEL_GRANT_ABBREVIATED:
            case PHASE1_46_UNIT_TO_UNIT_VOICE_CHANNEL_GRANT_UPDATE_ABBREVIATED:
            case PHASE1_48_TELEPHONE_INTERCONNECT_VOICE_CHANNEL_GRANT_IMPLICIT:
            case PHASE1_C4_UNIT_TO_UNIT_VOICE_SERVICE_CHANNEL_GRANT_EXTENDED_VCH:
            case PHASE1_C6_UNIT_TO_UNIT_VOICE_CHANNEL_GRANT_UPDATE_EXTENDED_VCH:
            case PHASE1_CF_UNIT_TO_UNIT_VOICE_SERVICE_CHANNEL_GRANT_EXTENDED_LCCH:
                type = encrypted ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED : DecodeEventType.CALL_UNIT_TO_UNIT;
                break;
            case TDMA_03_TELEPHONE_INTERCONNECT_VOICE_CHANNEL_USER:
            case PHASE1_C8_TELEPHONE_INTERCONNECT_VOICE_CHANNEL_GRANT_EXPLICIT:
                type = encrypted ? DecodeEventType.CALL_INTERCONNECT_ENCRYPTED : DecodeEventType.CALL_INTERCONNECT;
                break;
            case PHASE1_54_SNDCP_DATA_CHANNEL_GRANT:
            case L3HARRIS_A0_PRIVATE_DATA_CHANNEL_GRANT:
            case L3HARRIS_AC_UNIT_TO_UNIT_DATA_CHANNEL_GRANT:
                type = encrypted ? DecodeEventType.DATA_CALL_ENCRYPTED : DecodeEventType.DATA_CALL;
                break;
        }

        if(type == null)
        {
            LOGGING_SUPPRESSOR.error(macOpcode.name(), 2, "Unrecognized MAC opcode for determining " +
                    "decode event type: " + macOpcode.name());
            type = current;
        }

        if(type == null)
        {
            type = DecodeEventType.CALL;
        }

        return type;
    }

    /**
     * Checks if the given DecodeEventType represents a patch group call.
     */
    public static boolean isPatchEventType(DecodeEventType eventType)
    {
        if(eventType == null)
        {
            return false;
        }
        return eventType.name().contains("PATCH_GROUP");
    }

    /**
     * Checks if the given DecodeEventType represents an encrypted call.
     */
    public static boolean isEncryptedEventType(DecodeEventType eventType)
    {
        if(eventType == null)
        {
            return false;
        }
        String label = eventType.getLabel();
        return label != null && label.contains("Encrypted");
    }

    /**
     * Upgrades a non-encrypted DecodeEventType to its encrypted counterpart.
     * Returns the original type if already encrypted or no mapping exists.
     */
    public static DecodeEventType upgradeToEncrypted(DecodeEventType type)
    {
        if(type == null)
        {
            return DecodeEventType.CALL_ENCRYPTED;
        }

        return switch(type)
        {
            case CALL_GROUP -> DecodeEventType.CALL_GROUP_ENCRYPTED;
            case CALL_PATCH_GROUP -> DecodeEventType.CALL_PATCH_GROUP_ENCRYPTED;
            case CALL_UNIT_TO_UNIT -> DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED;
            case CALL_INTERCONNECT -> DecodeEventType.CALL_INTERCONNECT_ENCRYPTED;
            case CALL -> DecodeEventType.CALL_ENCRYPTED;
            case DATA_CALL -> DecodeEventType.DATA_CALL_ENCRYPTED;
            default -> type; // Already encrypted or unknown — return as-is
        };
    }
}
