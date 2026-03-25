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

import java.util.Collections;
import java.util.List;

/**
 * Captured raw payload data from a P25 message. Represents a single extraction event
 * from PDU data, Low Speed Data, vendor TSBK, SNDCP packet, or IP packet.
 *
 * Immutable value object — created by P25DataCaptureModule and consumed by the
 * DataCaptureModel (UI table) and JSON-lines logger.
 */
public class CapturedPayload
{
    /**
     * Classification of the payload source within the P25 message stream.
     */
    public enum PayloadType
    {
        PDU_PACKET("PDU"),
        SNDCP("SNDCP"),
        IP_PACKET("IP"),
        LRRP("LRRP"),
        LSD("LSD"),
        TSBK_VENDOR("TSBK-V"),
        TSBK_MOTOROLA("TSBK-M"),
        TSBK_STANDARD("TSBK"),
        LC_DATA("LC"),
        UNKNOWN("UNK");

        private final String mShortLabel;

        PayloadType(String shortLabel)
        {
            mShortLabel = shortLabel;
        }

        public String getShortLabel()
        {
            return mShortLabel;
        }
    }

    private final long mTimestamp;
    private final PayloadType mType;
    private final String mMessageClass;
    private final String mDetails;
    private final byte[] mRawBytes;
    private final String mHexDump;
    private final List<String> mDetectedStrings;
    private final String mFromId;
    private final String mToId;
    private final String mChannel;
    private final long mFrequency;
    private final String mDetectedProtocol;
    private final String mSapOrOpcode;
    private final int mPayloadLength;

    private CapturedPayload(Builder builder)
    {
        mTimestamp = builder.mTimestamp;
        mType = builder.mType;
        mMessageClass = builder.mMessageClass;
        mDetails = builder.mDetails;
        mRawBytes = builder.mRawBytes;
        mHexDump = builder.mHexDump;
        mDetectedStrings = builder.mDetectedStrings != null ? builder.mDetectedStrings : Collections.emptyList();
        mFromId = builder.mFromId;
        mToId = builder.mToId;
        mChannel = builder.mChannel;
        mFrequency = builder.mFrequency;
        mDetectedProtocol = builder.mDetectedProtocol;
        mSapOrOpcode = builder.mSapOrOpcode;
        mPayloadLength = builder.mPayloadLength;
    }

    public long getTimestamp() { return mTimestamp; }
    public PayloadType getType() { return mType; }
    public String getMessageClass() { return mMessageClass; }
    public String getDetails() { return mDetails; }
    public byte[] getRawBytes() { return mRawBytes; }
    public String getHexDump() { return mHexDump; }
    public List<String> getDetectedStrings() { return mDetectedStrings; }
    public String getFromId() { return mFromId; }
    public String getToId() { return mToId; }
    public String getChannel() { return mChannel; }
    public long getFrequency() { return mFrequency; }
    public String getDetectedProtocol() { return mDetectedProtocol; }
    public String getSapOrOpcode() { return mSapOrOpcode; }
    public int getPayloadLength() { return mPayloadLength; }

    /**
     * Returns detected strings as a comma-separated string for display.
     */
    public String getDetectedStringsDisplay()
    {
        if(mDetectedStrings.isEmpty())
        {
            return "";
        }
        return String.join(", ", mDetectedStrings);
    }

    /**
     * Returns a JSON-lines formatted string for corpus logging.
     */
    public String toJsonLine()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendJsonField(sb, "ts", mTimestamp, true);
        appendJsonStringField(sb, "type", mType.name(), false);
        appendJsonStringField(sb, "class", mMessageClass, false);
        appendJsonStringField(sb, "sap", mSapOrOpcode, false);
        appendJsonStringField(sb, "from", mFromId, false);
        appendJsonStringField(sb, "to", mToId, false);
        appendJsonStringField(sb, "channel", mChannel, false);
        appendJsonField(sb, "freq", mFrequency, false);
        appendJsonField(sb, "len", mPayloadLength, false);
        appendJsonStringField(sb, "hex", mHexDump, false);
        appendJsonStringField(sb, "proto", mDetectedProtocol, false);
        appendJsonStringField(sb, "details", escapeJson(mDetails), false);

        // Strings array
        sb.append(",\"strings\":[");
        for(int i = 0; i < mDetectedStrings.size(); i++)
        {
            if(i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(mDetectedStrings.get(i))).append("\"");
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    private void appendJsonField(StringBuilder sb, String key, long value, boolean first)
    {
        if(!first) sb.append(",");
        sb.append("\"").append(key).append("\":").append(value);
    }

    private void appendJsonStringField(StringBuilder sb, String key, String value, boolean first)
    {
        if(!first) sb.append(",");
        sb.append("\"").append(key).append("\":\"").append(value != null ? value : "").append("\"");
    }

    private String escapeJson(String s)
    {
        if(s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Builder for CapturedPayload.
     */
    public static Builder builder(PayloadType type, long timestamp)
    {
        return new Builder(type, timestamp);
    }

    public static class Builder
    {
        private final long mTimestamp;
        private final PayloadType mType;
        private String mMessageClass = "";
        private String mDetails = "";
        private byte[] mRawBytes;
        private String mHexDump = "";
        private List<String> mDetectedStrings;
        private String mFromId = "";
        private String mToId = "";
        private String mChannel = "";
        private long mFrequency;
        private String mDetectedProtocol = "";
        private String mSapOrOpcode = "";
        private int mPayloadLength;

        private Builder(PayloadType type, long timestamp)
        {
            mType = type;
            mTimestamp = timestamp;
        }

        public Builder messageClass(String messageClass) { mMessageClass = messageClass; return this; }
        public Builder details(String details) { mDetails = details; return this; }
        public Builder rawBytes(byte[] rawBytes) { mRawBytes = rawBytes; return this; }
        public Builder hexDump(String hexDump) { mHexDump = hexDump; return this; }
        public Builder detectedStrings(List<String> strings) { mDetectedStrings = strings; return this; }
        public Builder fromId(String fromId) { mFromId = fromId; return this; }
        public Builder toId(String toId) { mToId = toId; return this; }
        public Builder channel(String channel) { mChannel = channel; return this; }
        public Builder frequency(long frequency) { mFrequency = frequency; return this; }
        public Builder detectedProtocol(String protocol) { mDetectedProtocol = protocol; return this; }
        public Builder sapOrOpcode(String sapOrOpcode) { mSapOrOpcode = sapOrOpcode; return this; }
        public Builder payloadLength(int length) { mPayloadLength = length; return this; }

        public CapturedPayload build()
        {
            return new CapturedPayload(this);
        }
    }
}
