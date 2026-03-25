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
package io.github.dsheirer.module.decode.p25.data;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scans raw byte arrays for printable ASCII strings and attempts to identify
 * the payload protocol based on magic byte patterns.
 *
 * Used by P25DataCaptureModule to extract human-readable content from PDU payloads,
 * vendor TSBK data, and other binary P25 data.
 */
public class PayloadStringScanner
{
    /** Minimum run of printable characters to report as a string hit */
    private static final int MIN_STRING_LENGTH = 4;

    /** Range of printable ASCII bytes (tab through tilde) */
    private static final int LOW_PRINTABLE = 0x09;
    private static final int HIGH_PRINTABLE = 0x7E;

    /**
     * Scans the byte array for runs of printable ASCII characters.
     * Returns a list of strings found (minimum MIN_STRING_LENGTH characters).
     *
     * @param data byte array to scan
     * @return list of detected ASCII strings, may be empty
     */
    public static List<String> scanStrings(byte[] data)
    {
        if(data == null || data.length < MIN_STRING_LENGTH)
        {
            return Collections.emptyList();
        }

        List<String> hits = new ArrayList<>();
        int start = -1;
        int length = 0;

        for(int i = 0; i < data.length; i++)
        {
            int b = data[i] & 0xFF;
            boolean printable = (b >= LOW_PRINTABLE && b <= HIGH_PRINTABLE);

            if(printable)
            {
                if(start < 0)
                {
                    start = i;
                }
                length++;
            }
            else
            {
                if(length >= MIN_STRING_LENGTH)
                {
                    hits.add(new String(data, start, length, StandardCharsets.US_ASCII));
                }
                start = -1;
                length = 0;
            }
        }

        // Check final run
        if(length >= MIN_STRING_LENGTH)
        {
            hits.add(new String(data, start, length, StandardCharsets.US_ASCII));
        }

        return hits;
    }

    /**
     * Attempts to identify the protocol/format of the payload based on magic bytes
     * at the start of the data.
     *
     * @param data raw payload bytes
     * @return detected protocol name, or "UNKNOWN" if not recognized
     */
    public static String detectProtocol(byte[] data)
    {
        if(data == null || data.length < 2)
        {
            return "UNKNOWN";
        }

        int b0 = data[0] & 0xFF;
        int b1 = data.length > 1 ? data[1] & 0xFF : 0;

        // IPv4 packet — version 4, typical IHL=5 gives 0x45
        if((b0 & 0xF0) == 0x40)
        {
            return "IPv4";
        }

        // LRRP Immediate Location Response (type 0x22)
        if(b0 == 0x22)
        {
            return "LRRP";
        }

        // LRRP Immediate Location Request (type 0x10)
        if(b0 == 0x10)
        {
            return "LRRP-REQ";
        }

        // LRRP Triggered Location Response (type 0x23)
        if(b0 == 0x23)
        {
            return "LRRP-TRIG";
        }

        // NMEA GPS — $GP
        if(b0 == 0x24 && b1 == 0x47)
        {
            return "NMEA-GPS";
        }

        // JSON — {"
        if(b0 == 0x7B && b1 == 0x22)
        {
            return "JSON";
        }

        // HTTP GET
        if(data.length >= 4 && b0 == 0x47 && b1 == 0x45 && (data[2] & 0xFF) == 0x54 && (data[3] & 0xFF) == 0x20)
        {
            return "HTTP-GET";
        }

        // HTTP POST
        if(data.length >= 5 && b0 == 0x50 && b1 == 0x4F && (data[2] & 0xFF) == 0x53 && (data[3] & 0xFF) == 0x54)
        {
            return "HTTP-POST";
        }

        // UTF-16 BOM
        if((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF))
        {
            return "UTF-16";
        }

        // ARP (0x0001 or 0x0002 hardware type)
        if(data.length >= 8 && b0 == 0x00 && (b1 == 0x01 || b1 == 0x02))
        {
            // Could be ARP — check for typical IPv4 ARP structure
            if(data.length >= 8 && (data[2] & 0xFF) == 0x08 && (data[3] & 0xFF) == 0x00)
            {
                return "ARP";
            }
        }

        return "UNKNOWN";
    }

    /**
     * Converts a byte array to a hex dump string with space-separated bytes.
     * Limits output to maxBytes to prevent excessive string length.
     *
     * @param data byte array
     * @param maxBytes maximum bytes to include
     * @return hex string
     */
    public static String toHexDump(byte[] data, int maxBytes)
    {
        if(data == null || data.length == 0)
        {
            return "";
        }

        int limit = Math.min(data.length, maxBytes);
        StringBuilder sb = new StringBuilder(limit * 3);

        for(int i = 0; i < limit; i++)
        {
            if(i > 0)
            {
                sb.append(' ');
            }
            sb.append(String.format("%02X", data[i] & 0xFF));
        }

        if(data.length > maxBytes)
        {
            sb.append("...");
        }

        return sb.toString();
    }

    /**
     * Converts a byte array to a full hex dump string with space-separated bytes.
     */
    public static String toHexDump(byte[] data)
    {
        return toHexDump(data, Integer.MAX_VALUE);
    }

    /**
     * Decodes a 2-byte (4 hex char) Low Speed Data value into a human-readable format description.
     * LSD format is defined by bits 15-14 of the 16-bit value (the two MSBs of byte 0).
     *
     * Reference: TIA-102.BAAA Section 7.2 (Low Speed Data)
     *
     * @param lsdHex 4-character hex string (e.g., "4000", "0001", "C0FA")
     * @return human-readable format description
     */
    public static String decodeLsdFormat(String lsdHex)
    {
        if(lsdHex == null || lsdHex.length() < 4)
        {
            return "UNKNOWN";
        }

        try
        {
            int value = Integer.parseInt(lsdHex, 16);
            int formatBits = (value >> 14) & 0x03;
            int payload = value & 0x3FFF;

            switch(formatBits)
            {
                case 0: // 00 — Null / SACCH
                    if(value == 0x0000)
                    {
                        return "NULL";
                    }
                    return "SACCH:" + String.format("%04X", payload);

                case 1: // 01 — Encryption LFSR sync
                    if(value == 0x4000)
                    {
                        return "CRYPTO-SYNC:0";
                    }
                    return "CRYPTO-SYNC:" + String.format("%04X", payload);

                case 2: // 10 — Slow associated control channel
                    if(value == 0x8000)
                    {
                        return "SLOW-CTRL:0";
                    }
                    return "SLOW-CTRL:" + String.format("%04X", payload);

                case 3: // 11 — Reserved / vendor-specific
                    return "VENDOR:" + String.format("%04X", payload);

                default:
                    return "UNKNOWN";
            }
        }
        catch(NumberFormatException e)
        {
            return "PARSE-ERR";
        }
    }

    /**
     * Returns a short label for the LSD format type based on the 2 MSBs.
     *
     * @param lsdHex 4-character hex string
     * @return short format label (NULL/SACCH, CRYPTO, SLOW-CTRL, VENDOR)
     */
    public static String getLsdFormatLabel(String lsdHex)
    {
        if(lsdHex == null || lsdHex.length() < 4)
        {
            return "";
        }

        try
        {
            int value = Integer.parseInt(lsdHex, 16);
            int formatBits = (value >> 14) & 0x03;

            switch(formatBits)
            {
                case 0: return "SACCH";
                case 1: return "CRYPTO";
                case 2: return "SLOW-CTRL";
                case 3: return "VENDOR";
                default: return "";
            }
        }
        catch(NumberFormatException e)
        {
            return "";
        }
    }
}
