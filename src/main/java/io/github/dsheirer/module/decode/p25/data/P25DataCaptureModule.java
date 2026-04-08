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

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.ip.IPacket;
import io.github.dsheirer.module.decode.ip.ipv4.IPV4Packet;
import io.github.dsheirer.module.decode.ip.mototrbo.lrrp.LRRPPacket;
import io.github.dsheirer.module.decode.ip.mototrbo.lrrp.LRRPPacketType;
import io.github.dsheirer.module.decode.ip.mototrbo.lrrp.token.Heading;
import io.github.dsheirer.module.decode.ip.mototrbo.lrrp.token.Point2d;
import io.github.dsheirer.module.decode.ip.mototrbo.lrrp.token.Speed;
import io.github.dsheirer.module.decode.ip.mototrbo.lrrp.token.Token;
import io.github.dsheirer.module.decode.ip.mototrbo.xcmp.XCMPPacket;
import io.github.dsheirer.module.decode.ip.udp.UDPPacket;
import io.github.dsheirer.module.decode.p25.data.CapturedPayload.PayloadType;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequenceMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.packet.PacketMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.packet.sndcp.SNDCPPacketMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacOpcode;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructure;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.UnknownMacStructure;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.UnknownVendorMessage;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.DatchTimeslot;
import io.github.dsheirer.sample.Listener;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P25 Data Capture Module — intercepts all decoded P25 messages from the processing chain
 * and extracts raw payload data for corpus building, analysis, and display.
 *
 * Captures:
 * - PDU reassembled payloads (PacketMessage.getPayloadMessage())
 * - SNDCP packet data
 * - Low Speed Data (LSD) from LDU1/LDU2 voice frames
 * - Vendor/unknown TSBK raw bits
 * - Known Motorola extended function data
 * - Phase 2 MAC vendor/unknown structures
 * - IP/LRRP packets already parsed by SDRTrunk
 *
 * Supports:
 * - Parent module forwarding: traffic channel modules forward payloads to the control
 *   channel's module so all data is aggregated in one place for the Data tab.
 * - Per-system log files: logs go to logs/p25_data_<systemName>_<date>.jsonl
 *
 * This module implements IMessageListener so the processing chain automatically
 * wires it to receive all decoded messages.
 */
public class P25DataCaptureModule extends Module implements IMessageListener
{
    private static final Logger mLog = LoggerFactory.getLogger(P25DataCaptureModule.class);

    /** Maximum hex dump bytes to include in display (full dump goes to corpus log) */
    private static final int MAX_DISPLAY_HEX_BYTES = 64;

    /** Listeners for captured payload events (UI model, etc.) */
    private final List<Listener<CapturedPayload>> mPayloadListeners = new CopyOnWriteArrayList<>();

    /** Message listener that receives from the processing chain */
    private final Listener<IMessage> mMessageListener = this::processMessage;

    /**
     * Parent module reference — when set, this module forwards all captured payloads
     * to the parent (control channel) module for aggregation. This allows traffic channel
     * data to appear in the control channel's Data tab.
     */
    private P25DataCaptureModule mParentModule;

    /** System name for per-system log file naming */
    private String mSystemName = "default";

    /** Per-system log writer */
    private PrintWriter mCorpusWriter;
    private String mCurrentLogDate;

    /** Traffic channel frequency (Hz) — set by DecoderFactory for traffic channels, 0 for control */
    private long mChannelFrequency;

    /** Traffic channel descriptor string — set by DecoderFactory for traffic channels */
    private String mChannelDescriptor = "";

    /** Channel mode — "FDMA" for Phase 1, "TDMA" for Phase 2, set by DecoderFactory */
    private String mChannelMode = "";

    /**
     * Tracks the last TDMA data channel details string per timeslot for deduplication.
     * Key: "TS1" or "TS2", Value: last details string emitted.
     * Used to suppress repetitive MotorolaTDMADataChannel IDLE messages (Priority 1 filter).
     */
    private String mLastTdmaDataDetailsTS1 = "";
    private String mLastTdmaDataDetailsTS2 = "";

    private boolean mRunning = false;

    public P25DataCaptureModule()
    {
    }

    /**
     * Sets the parent module for traffic→control forwarding.
     * When a traffic channel module has a parent, it forwards all payloads
     * to the parent's listeners and log, so the control channel's Data tab
     * shows aggregated data from all channels.
     *
     * @param parent the control channel's P25DataCaptureModule
     */
    public void setParentModule(P25DataCaptureModule parent)
    {
        mParentModule = parent;
    }

    /**
     * Returns the parent module, or null if this is the control channel module.
     */
    public P25DataCaptureModule getParentModule()
    {
        return mParentModule;
    }

    /**
     * Sets the system name used for per-system log file naming.
     * @param systemName the channel/system name (sanitized for filenames)
     */
    public void setSystemName(String systemName)
    {
        if(systemName != null && !systemName.isEmpty())
        {
            // Sanitize for use in filenames
            mSystemName = systemName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        }
    }

    /**
     * Returns the system name.
     */
    public String getSystemName()
    {
        return mSystemName;
    }

    /**
     * Sets the traffic channel frequency for this module.
     * Called by DecoderFactory when creating traffic channel modules so that
     * captured payloads include the frequency they were captured on.
     *
     * @param frequency in Hertz, or 0 for control channel
     */
    public void setChannelFrequency(long frequency)
    {
        mChannelFrequency = frequency;
    }

    /**
     * Returns the channel frequency (Hz), or 0 if not set.
     */
    public long getChannelFrequency()
    {
        return mChannelFrequency;
    }

    /**
     * Sets the traffic channel descriptor string for this module.
     * Called by DecoderFactory when creating traffic channel modules.
     *
     * @param descriptor channel descriptor string (e.g., "0-1029")
     */
    public void setChannelDescriptor(String descriptor)
    {
        mChannelDescriptor = descriptor != null ? descriptor : "";
    }

    /**
     * Returns the channel descriptor string, or empty string if not set.
     */
    public String getChannelDescriptor()
    {
        return mChannelDescriptor;
    }

    /**
     * Sets the channel mode for this module (FDMA or TDMA).
     * Called by DecoderFactory when creating the module.
     *
     * @param mode "FDMA" for Phase 1 or "TDMA" for Phase 2
     */
    public void setChannelMode(String mode)
    {
        mChannelMode = mode != null ? mode : "";
    }

    /**
     * Returns the channel mode ("FDMA", "TDMA"), or empty string if not set.
     */
    public String getChannelMode()
    {
        return mChannelMode;
    }

    @Override
    public Listener<IMessage> getMessageListener()
    {
        return mMessageListener;
    }

    /**
     * Adds a listener for captured payload events.
     */
    public void addPayloadListener(Listener<CapturedPayload> listener)
    {
        if(!mPayloadListeners.contains(listener))
        {
            mPayloadListeners.add(listener);
        }
    }

    /**
     * Removes a listener for captured payload events.
     */
    public void removePayloadListener(Listener<CapturedPayload> listener)
    {
        mPayloadListeners.remove(listener);
    }

    /**
     * Returns the current list of payload listeners (for backfill on UI reconnect).
     */
    public List<Listener<CapturedPayload>> getPayloadListeners()
    {
        return mPayloadListeners;
    }

    @Override
    public void reset()
    {
    }

    @Override
    public void start()
    {
        mRunning = true;
        mLog.info("P25DataCaptureModule started [system={}]", mSystemName);
    }

    @Override
    public void stop()
    {
        mRunning = false;
        closeCorpusWriter();
        mLog.info("P25DataCaptureModule stopped [system={}]", mSystemName);
    }

    /**
     * Primary message processing — dispatches by message type to extract payload data.
     */
    private void processMessage(IMessage message)
    {
        if(!mRunning || message == null || !message.isValid())
        {
            return;
        }

        try
        {
            if(message instanceof SNDCPPacketMessage sndcp)
            {
                processSNDCPPacket(sndcp);
            }
            else if(message instanceof PacketMessage packet)
            {
                processPacketMessage(packet);
            }
            else if(message instanceof PDUMessage pdu)
            {
                processPDUMessage(pdu);
            }
            else if(message instanceof PDUSequenceMessage pduSeq)
            {
                processPDUSequenceMessage(pduSeq);
            }
            else if(message instanceof LDU1Message ldu1)
            {
                processLDU(ldu1);
            }
            else if(message instanceof LDU2Message ldu2)
            {
                processLDU(ldu2);
            }
            else if(message instanceof TSBKMessage tsbk)
            {
                processTSBK(tsbk);
            }
            else if(message instanceof DatchTimeslot datch)
            {
                processDatchTimeslot(datch);
            }
            else if(message instanceof MacMessage mac)
            {
                processMacMessage(mac);
            }
        }
        catch(Exception e)
        {
            mLog.error("Error processing message for data capture: {}", message.getClass().getSimpleName(), e);
        }
    }

    /**
     * Process a fully reassembled PacketMessage — extract the payload bytes.
     *
     * Change 018: Walks the parsed packet hierarchy to detect LRRP and XCMP packets:
     * - LRRP packets: extracts GPS coordinates (lat/lon/heading/speed) from Point2d/Point3d tokens
     * - XCMP packets: extracts message type and sets protocol to XCMP
     * - Port 64414 UDP: identified as XCMP device management traffic
     */
    private void processPacketMessage(PacketMessage packet)
    {
        BinaryMessage payload = packet.getPayloadMessage();

        if(payload != null)
        {
            byte[] bytes = payload.getBytes();

            if(bytes != null && bytes.length > 0)
            {
                List<String> strings = PayloadStringScanner.scanStrings(bytes);
                String protocol = PayloadStringScanner.detectProtocol(bytes);

                // Enrich protocol from details string if byte-level detection returned UNKNOWN
                String details = packet.toString();
                if("UNKNOWN".equals(protocol) || "IPv4".equals(protocol))
                {
                    String enriched = PayloadStringScanner.detectProtocolFromDetails(details);
                    if(enriched != null)
                    {
                        protocol = enriched;
                    }
                }

                // Walk the parsed packet hierarchy to extract structured data
                PayloadType payloadType = PayloadType.PDU_PACKET;
                double latitude = Double.NaN;
                double longitude = Double.NaN;
                double heading = Double.NaN;
                double speed = Double.NaN;
                String sapOrOpcode = "";

                try
                {
                    IPacket parsedPacket = packet.getPacket();

                    if(parsedPacket instanceof IPV4Packet ipv4)
                    {
                        IPacket ipPayload = ipv4.getPayload();

                        if(ipPayload instanceof UDPPacket udp)
                        {
                            IPacket udpPayload = udp.getPayload();
                            int dstPort = udp.getHeader().getDestinationPort().getValue();
                            int srcPort = udp.getHeader().getSourcePort().getValue();
                            sapOrOpcode = "UDP:" + srcPort + "→" + dstPort;

                            if(udpPayload instanceof LRRPPacket lrrp)
                            {
                                // --- Priority 4: LRRP GPS Coordinate Extraction ---
                                payloadType = PayloadType.LRRP;
                                protocol = "LRRP";

                                LRRPPacketType lrrpType = lrrp.getHeader().getLRRPPacketType();
                                sapOrOpcode = "LRRP:" + lrrpType;

                                // Extract GPS coordinates from LRRP response tokens
                                for(Token token : lrrp.getTokens())
                                {
                                    if(token instanceof Point2d point)
                                    {
                                        latitude = point.getLatitude();
                                        longitude = point.getLongitude();
                                    }
                                    else if(token instanceof Heading hdg)
                                    {
                                        heading = hdg.getHeading();
                                    }
                                    else if(token instanceof Speed spd)
                                    {
                                        speed = spd.getSpeed();
                                    }
                                }

                                if(!Double.isNaN(latitude) && !Double.isNaN(longitude))
                                {
                                    mLog.info("LRRP GPS extracted: lat={}, lon={}, hdg={}, spd={}, from={}",
                                        String.format("%.6f", latitude),
                                        String.format("%.6f", longitude),
                                        Double.isNaN(heading) ? "N/A" : String.format("%.0f", heading),
                                        Double.isNaN(speed) ? "N/A" : String.format("%.1f", speed),
                                        extractId(packet, Role.FROM));
                                }
                            }
                            else if(udpPayload instanceof XCMPPacket xcmp)
                            {
                                // --- Priority 5: XCMP Packet Identification ---
                                protocol = "XCMP";
                                String xcmpType = xcmp.getHeader().getMessageType().toString();
                                sapOrOpcode = "XCMP:" + xcmpType + " port:" + dstPort;

                                mLog.debug("XCMP packet detected: type={}, port={}, from={}",
                                    xcmpType, dstPort, extractId(packet, Role.FROM));
                            }
                            else if(dstPort == 64414 || srcPort == 64414)
                            {
                                // --- Priority 5: Port 64414 traffic (likely XCMP/XNL) ---
                                protocol = "XCMP";
                                sapOrOpcode = "UDP:" + srcPort + "→" + dstPort + " (XCMP?)";

                                mLog.debug("Port 64414 traffic detected: {}→{}, from={}",
                                    srcPort, dstPort, extractId(packet, Role.FROM));
                            }
                        }
                    }
                }
                catch(Exception e)
                {
                    // Packet walking failed — continue with basic extraction
                    mLog.debug("Error walking packet hierarchy: {}", e.getMessage());
                }

                CapturedPayload cp = CapturedPayload.builder(payloadType, packet.getTimestamp())
                        .messageClass(packet.getClass().getSimpleName())
                        .details(details)
                        .rawBytes(bytes)
                        .hexDump(PayloadStringScanner.toHexDump(bytes, MAX_DISPLAY_HEX_BYTES))
                        .detectedStrings(strings)
                        .fromId(extractId(packet, Role.FROM))
                        .toId(extractId(packet, Role.TO))
                        .detectedProtocol(protocol)
                        .sapOrOpcode(sapOrOpcode)
                        .frequency(mChannelFrequency)
                        .channel(mChannelDescriptor)
                        .mode(mChannelMode)
                        .payloadLength(bytes.length)
                        .latitude(latitude)
                        .longitude(longitude)
                        .heading(heading)
                        .speed(speed)
                        .build();

                emit(cp);
            }
        }
    }

    /**
     * Process an SNDCP packet message.
     */
    private void processSNDCPPacket(SNDCPPacketMessage sndcp)
    {
        String sndcpType = sndcp.getSNDCPMessage() != null
                ? sndcp.getSNDCPMessage().getPDUType().name()
                : "UNKNOWN";

        BinaryMessage payload = null;
        try
        {
            payload = sndcp.getPayloadMessage();
        }
        catch(Exception e)
        {
            // Some SNDCP types may not have payload
        }

        byte[] bytes = (payload != null) ? payload.getBytes() : null;
        List<String> strings = (bytes != null) ? PayloadStringScanner.scanStrings(bytes) : List.of();
        String protocol = (bytes != null) ? PayloadStringScanner.detectProtocol(bytes) : "SNDCP";

        // Enrich protocol from details string
        String details = sndcp.toString();
        if("UNKNOWN".equals(protocol) || "SNDCP".equals(protocol) || "IPv4".equals(protocol))
        {
            String enriched = PayloadStringScanner.detectProtocolFromDetails(details);
            if(enriched != null)
            {
                protocol = enriched;
            }
        }

        CapturedPayload cp = CapturedPayload.builder(PayloadType.SNDCP, sndcp.getTimestamp())
                .messageClass(sndcp.getClass().getSimpleName())
                .details(details)
                .rawBytes(bytes)
                .hexDump(bytes != null ? PayloadStringScanner.toHexDump(bytes, MAX_DISPLAY_HEX_BYTES) : "")
                .detectedStrings(strings)
                .fromId(extractId(sndcp, Role.FROM))
                .toId(extractId(sndcp, Role.TO))
                .sapOrOpcode("SNDCP:" + sndcpType)
                .detectedProtocol(protocol)
                .frequency(mChannelFrequency)
                .channel(mChannelDescriptor)
                .mode(mChannelMode)
                .payloadLength(bytes != null ? bytes.length : 0)
                .build();

        emit(cp);
    }

    /**
     * Process a PDU message (non-packet, non-SNDCP).
     */
    private void processPDUMessage(PDUMessage pdu)
    {
        String details = pdu.toString();
        String protocol = PayloadStringScanner.detectProtocolFromDetails(details);

        CapturedPayload cp = CapturedPayload.builder(PayloadType.PDU_PACKET, pdu.getTimestamp())
                .messageClass(pdu.getClass().getSimpleName())
                .details(details)
                .fromId(extractId(pdu, Role.FROM))
                .toId(extractId(pdu, Role.TO))
                .detectedProtocol(protocol != null ? protocol : "")
                .frequency(mChannelFrequency)
                .channel(mChannelDescriptor)
                .mode(mChannelMode)
                .build();

        emit(cp);
    }

    /**
     * Process a PDU sequence message.
     */
    private void processPDUSequenceMessage(PDUSequenceMessage pduSeq)
    {
        if(pduSeq.getPDUSequence() != null && pduSeq.getPDUSequence().isComplete())
        {
            String seqDetails = pduSeq.toString();
            String seqProtocol = PayloadStringScanner.detectProtocolFromDetails(seqDetails);

            CapturedPayload cp = CapturedPayload.builder(PayloadType.PDU_PACKET, pduSeq.getTimestamp())
                    .messageClass(pduSeq.getClass().getSimpleName())
                    .details(seqDetails)
                    .fromId(extractId(pduSeq, Role.FROM))
                    .toId(extractId(pduSeq, Role.TO))
                    .detectedProtocol(seqProtocol != null ? seqProtocol : "")
                    .frequency(mChannelFrequency)
                    .channel(mChannelDescriptor)
                    .mode(mChannelMode)
                    .build();

            emit(cp);
        }
    }

    /**
     * Process Low Speed Data from an LDU voice frame.
     * LSD is 2 bytes per LDU frame — we capture every non-zero LSD value.
     * Decodes the format type (bits 15-14): SACCH, CRYPTO, SLOW-CTRL, VENDOR.
     */
    private void processLDU(LDUMessage ldu)
    {
        String lsd = ldu.getLowSpeedData();

        // Skip all-zero LSD (no data) and null/empty
        if(lsd == null || lsd.isEmpty() || lsd.equals("0000"))
        {
            return;
        }

        String lduType = (ldu instanceof LDU1Message) ? "LDU1" : "LDU2";
        String lsdFormat = PayloadStringScanner.decodeLsdFormat(lsd);
        String lsdLabel = PayloadStringScanner.getLsdFormatLabel(lsd);

        CapturedPayload cp = CapturedPayload.builder(PayloadType.LSD, ldu.getTimestamp())
                .messageClass(lduType)
                .details(lduType + " LSD:" + lsd + " [" + lsdFormat + "]")
                .hexDump(lsd)
                .sapOrOpcode(lsdLabel)
                .detectedProtocol(lsdLabel)
                .fromId(extractId(ldu, Role.FROM))
                .toId(extractId(ldu, Role.TO))
                .frequency(mChannelFrequency)
                .channel(mChannelDescriptor)
                .mode(mChannelMode)
                .payloadLength(2)
                .build();

        emit(cp);
    }

    /**
     * Process a Motorola TDMA Data Channel (DATCH) timeslot — captures the raw descrambled
     * 320-bit (40-byte) payload for offline analysis and corpus building.
     *
     * Change 023: Phase 2 TDMA data channels carry DATCH timeslots that contain data payloads
     * (likely SNDCP/IP) but SDRTrunk currently doesn't decode them beyond descrambling.
     * This method captures every DATCH timeslot's raw descrambled payload to the JSONL corpus
     * and Data tab, enabling offline analysis to determine the FEC encoding, framing, and
     * reassembly protocol.
     *
     * Each DATCH timeslot = 320 bits = 40 bytes. A typical 15-second data session produces
     * ~400 timeslots = ~16,000 bytes of raw data that was previously discarded.
     *
     * @param datch the descrambled DATCH timeslot message
     */
    private void processDatchTimeslot(DatchTimeslot datch)
    {
        byte[] payload = datch.getDescrambledPayload();

        if(payload == null || payload.length == 0)
        {
            return;
        }

        String hexDump = PayloadStringScanner.toHexDump(payload, MAX_DISPLAY_HEX_BYTES);
        List<String> strings = PayloadStringScanner.scanStrings(payload);
        String protocol = PayloadStringScanner.detectProtocol(payload);

        // Include timeslot number in the SAP/opcode field for correlation
        String sapOrOpcode = "DATCH:TS" + datch.getTimeslot();

        CapturedPayload cp = CapturedPayload.builder(PayloadType.DATCH_RAW, datch.getTimestamp())
                .messageClass("DatchTimeslot")
                .details(datch.toString())
                .rawBytes(payload)
                .hexDump(hexDump)
                .detectedStrings(strings)
                .detectedProtocol(protocol)
                .sapOrOpcode(sapOrOpcode)
                .frequency(mChannelFrequency)
                .channel(mChannelDescriptor)
                .mode("TDMA")
                .payloadLength(payload.length)
                .build();

        emit(cp);
    }

    /**
     * Process a Phase 2 MAC message — capture vendor/unknown MAC structures and
     * all MAC messages on TDMA data channels.
     * The opcode lives on MacStructure, not on MacMessage directly.
     *
     * Filters (Change 017/020):
     * - MotorolaUnknownOpcode135 (opcode 0x87): SACCH idle fill, suppressed entirely
     * - MotorolaTDMADataChannel (opcode 0x8B) IDLE: deduplicated per timeslot, only emitted on change
     * - On TDMA data channels: capture ALL MAC messages (not just vendor/unknown)
     *   to ensure SNDCP, PDU, and IP data carried on Phase 2 timeslots is captured
     */
    private void processMacMessage(MacMessage macMessage)
    {
        MacStructure structure = macMessage.getMacStructure();

        if(structure == null)
        {
            return;
        }

        MacOpcode opcode = structure.getOpcode();

        if(opcode == null)
        {
            return;
        }

        PayloadType type;
        boolean capture = false;
        String opcodeName = opcode.name();
        String structureClassName = structure.getClass().getSimpleName();

        // --- Priority 1 SACCH idle noise filter ---

        // Filter: MotorolaUnknownOpcode135 — repetitive SACCH idle fill on Phase 2 systems.
        // These repeat every ~350ms on both timeslots and contain no useful data. Suppress entirely.
        if(structureClassName.contains("MotorolaUnknownOpcode135"))
        {
            return;
        }

        // Filter: MotorolaTDMADataChannel IDLE messages — deduplicate per timeslot.
        // Only emit when the details string changes (indicating a real state change).
        if(structureClassName.contains("MotorolaTDMADataChannel"))
        {
            String details = macMessage.toString();
            int timeslot = macMessage.getTimeslot();

            if(timeslot == 2)
            {
                if(details.equals(mLastTdmaDataDetailsTS2))
                {
                    return; // Duplicate — suppress
                }
                mLastTdmaDataDetailsTS2 = details;
            }
            else
            {
                if(details.equals(mLastTdmaDataDetailsTS1))
                {
                    return; // Duplicate — suppress
                }
                mLastTdmaDataDetailsTS1 = details;
            }
            // Details changed — fall through to capture
        }

        // --- End Priority 1 filter ---

        // Capture unknown MAC structures
        if(structure instanceof UnknownMacStructure || structure instanceof UnknownVendorMessage)
        {
            type = PayloadType.TSBK_VENDOR;
            capture = true;
        }
        // Capture vendor-named opcodes
        else if(opcodeName.contains("UNKNOWN") || opcodeName.contains("VENDOR"))
        {
            type = PayloadType.TSBK_VENDOR;
            capture = true;
        }
        // Capture Motorola vendor MAC messages
        else if(opcodeName.contains("MOTOROLA"))
        {
            type = PayloadType.TSBK_MOTOROLA;
            capture = true;
        }
        // Capture Harris/L3Harris vendor MAC messages
        else if(opcodeName.contains("HARRIS") || opcodeName.contains("L3HARRIS"))
        {
            type = PayloadType.TSBK_VENDOR;
            capture = true;
        }
        // Capture interesting standard Phase 2 MAC opcodes
        else
        {
            type = PayloadType.TSBK_STANDARD;

            // On TDMA channels, capture ALL MAC messages — Phase 2 data channels carry
            // SNDCP/PDU/IP data via MAC structures that would otherwise be filtered out.
            // This ensures we capture the inbound (radio→infra) data path on TS1/TS2.
            if("TDMA".equals(mChannelMode))
            {
                capture = true;
            }
            // On non-TDMA channels, only capture specific interesting opcodes
            else if(opcodeName.contains("EXTENDED_FUNCTION") ||
               opcodeName.contains("STATUS") ||
               opcodeName.contains("EMERGENCY") ||
               opcodeName.contains("REGROUP") ||
               opcodeName.contains("SNDCP"))
            {
                capture = true;
            }
            // Skip high-volume housekeeping (voice channel users, grants, etc.)
        }

        if(!capture)
        {
            return;
        }

        // Extract raw MAC message bits as hex
        String rawHex = "";
        byte[] rawBytes = null;
        try
        {
            CorrectedBinaryMessage msg = macMessage.getMessage();
            if(msg != null)
            {
                rawBytes = msg.getBytes();
                rawHex = PayloadStringScanner.toHexDump(rawBytes, MAX_DISPLAY_HEX_BYTES);
            }
        }
        catch(Exception e)
        {
            // Ignore
        }

        List<String> strings = (rawBytes != null) ? PayloadStringScanner.scanStrings(rawBytes) : List.of();

        CapturedPayload cp = CapturedPayload.builder(type, macMessage.getTimestamp())
                .messageClass(structureClassName)
                .details(macMessage.toString())
                .rawBytes(rawBytes)
                .hexDump(rawHex)
                .detectedStrings(strings)
                .fromId(extractId(macMessage, Role.FROM))
                .toId(extractId(macMessage, Role.TO))
                .sapOrOpcode("MAC:" + opcodeName)
                .frequency(mChannelFrequency)
                .channel(mChannelDescriptor)
                .mode(mChannelMode)
                .payloadLength(rawBytes != null ? rawBytes.length : 0)
                .build();

        emit(cp);
    }

    /**
     * Process TSBK messages — capture vendor/unknown opcodes and interesting standard opcodes.
     */
    private void processTSBK(TSBKMessage tsbk)
    {
        Opcode opcode = tsbk.getOpcode();

        if(opcode == null)
        {
            return;
        }

        PayloadType type;
        boolean capture = false;
        String opcodeName = opcode.name();

        // Capture all unknown/vendor TSBKs
        if(opcodeName.contains("UNKNOWN") || opcodeName.contains("VENDOR"))
        {
            type = PayloadType.TSBK_VENDOR;
            capture = true;
        }
        // Capture Motorola-specific opcodes
        else if(opcodeName.startsWith("MOTOROLA_"))
        {
            type = PayloadType.TSBK_MOTOROLA;

            switch(opcode)
            {
                case MOTOROLA_OSP_EXTENDED_FUNCTION_COMMAND:
                case MOTOROLA_OSP_EMERGENCY_ALARM_ACTIVATION:
                case MOTOROLA_OSP_GROUP_REGROUP_ADD:
                case MOTOROLA_OSP_GROUP_REGROUP_DELETE:
                case MOTOROLA_OSP_DENY_RESPONSE:
                case MOTOROLA_OSP_QUEUED_RESPONSE:
                case MOTOROLA_OSP_ACKNOWLEDGE_RESPONSE:
                    capture = true;
                    break;
                case MOTOROLA_OSP_SYSTEM_LOADING:
                case MOTOROLA_OSP_TDMA_DATA_CHANNEL:
                    break;
                default:
                    break;
            }
        }
        // Capture Harris-specific opcodes
        else if(opcodeName.startsWith("HARRIS_"))
        {
            type = PayloadType.TSBK_VENDOR;
            capture = true;
        }
        // Capture interesting standard opcodes with raw data
        else
        {
            type = PayloadType.TSBK_STANDARD;

            switch(opcode)
            {
                case OSP_EXTENDED_FUNCTION_COMMAND:
                case OSP_STATUS_UPDATE:
                case OSP_STATUS_QUERY:
                case ISP_STATUS_UPDATE_REQUEST:
                case ISP_STATUS_QUERY_RESPONSE:
                case ISP_EMERGENCY_ALARM_REQUEST:
                    capture = true;
                    break;
                default:
                    break;
            }
        }

        if(!capture)
        {
            return;
        }

        String rawHex = "";
        byte[] rawBytes = null;
        try
        {
            BinaryMessage msg = tsbk.getMessage();
            if(msg != null)
            {
                rawBytes = msg.getBytes();
                rawHex = PayloadStringScanner.toHexDump(rawBytes, MAX_DISPLAY_HEX_BYTES);
            }
        }
        catch(Exception e)
        {
            // Ignore
        }

        List<String> strings = (rawBytes != null) ? PayloadStringScanner.scanStrings(rawBytes) : List.of();

        CapturedPayload cp = CapturedPayload.builder(type, tsbk.getTimestamp())
                .messageClass(tsbk.getClass().getSimpleName())
                .details(tsbk.toString())
                .rawBytes(rawBytes)
                .hexDump(rawHex)
                .detectedStrings(strings)
                .fromId(extractId(tsbk, Role.FROM))
                .toId(extractId(tsbk, Role.TO))
                .sapOrOpcode(opcodeName)
                .frequency(mChannelFrequency)
                .channel(mChannelDescriptor)
                .mode(mChannelMode)
                .payloadLength(rawBytes != null ? rawBytes.length : 0)
                .build();

        emit(cp);
    }

    /**
     * Emits a captured payload to all listeners and the corpus log.
     *
     * If this module has a parent (i.e. it's on a traffic channel), the payload
     * is forwarded to the parent module so it appears in the control channel's
     * Data tab and log file.
     *
     * Change 021: Zero-payload records (payloadLength == 0) are suppressed entirely.
     * These are typically PDU ResponseMessage acknowledgments and other signaling
     * records that contain no actual data payload. In the Jacksonville corpus,
     * 78% of all records were zero-payload, drowning out actual data in both
     * the UI and JSONL logs.
     */
    private void emit(CapturedPayload payload)
    {
        // --- Zero-payload filter (Change 021) ---
        // Skip records with no payload data. These are ACK/response records that
        // clutter the Data tab and JSONL logs without contributing useful data.
        if(payload.getPayloadLength() == 0)
        {
            return;
        }

        // If we have a parent module, forward to it for aggregation
        if(mParentModule != null)
        {
            mParentModule.receiveFromChild(payload);
            return;
        }

        // We are the control channel module — log and distribute
        logToCorpus(payload);

        // Forward to UI listeners
        for(Listener<CapturedPayload> listener : mPayloadListeners)
        {
            try
            {
                listener.receive(payload);
            }
            catch(Exception e)
            {
                mLog.error("Error forwarding payload to listener", e);
            }
        }
    }

    /**
     * Receives a payload forwarded from a child (traffic channel) module.
     * Logs it and distributes to UI listeners.
     */
    public void receiveFromChild(CapturedPayload payload)
    {
        logToCorpus(payload);

        for(Listener<CapturedPayload> listener : mPayloadListeners)
        {
            try
            {
                listener.receive(payload);
            }
            catch(Exception e)
            {
                mLog.error("Error forwarding child payload to listener", e);
            }
        }
    }

    /**
     * Logs a payload to the per-system corpus file.
     * File: logs/p25_data_<systemName>_<date>.jsonl
     */
    private void logToCorpus(CapturedPayload payload)
    {
        try
        {
            String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

            // Rotate log file if date changed
            if(mCorpusWriter == null || !today.equals(mCurrentLogDate))
            {
                closeCorpusWriter();

                File logsDir = new File("logs");
                if(!logsDir.exists())
                {
                    logsDir.mkdirs();
                }

                String filename = "p25_data_" + mSystemName + "_" + today + ".jsonl";
                File logFile = new File(logsDir, filename);
                mCorpusWriter = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
                mCurrentLogDate = today;
                mLog.info("Opened corpus log: {}", logFile.getAbsolutePath());
            }

            mCorpusWriter.println(payload.toJsonLine());
        }
        catch(IOException e)
        {
            mLog.error("Error writing to corpus log", e);
        }
    }

    /**
     * Closes the corpus writer if open.
     */
    private void closeCorpusWriter()
    {
        if(mCorpusWriter != null)
        {
            try
            {
                mCorpusWriter.close();
            }
            catch(Exception e)
            {
                // Ignore
            }
            mCorpusWriter = null;
            mCurrentLogDate = null;
        }
    }

    /**
     * Extracts an identifier string for the given role from a message.
     */
    private String extractId(IMessage message, Role role)
    {
        if(message.getIdentifiers() == null)
        {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for(Identifier id : message.getIdentifiers())
        {
            if(id.getRole() == role)
            {
                if(sb.length() > 0)
                {
                    sb.append(",");
                }
                sb.append(id.getValue());
            }
        }
        return sb.toString();
    }
}
