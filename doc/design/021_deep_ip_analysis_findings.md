# 021 — Deep IP Layer Analysis Findings

## Corpus Summary

**Duration:** 12.8 hours (04:00 - 16:48 UTC, March 25, 2026)  
**Systems:** Jacksonville City (First Coast Radio) + Clay County  
**Total records:** 260,192  
**Non-zero payload:** 60,624 (23.3%)  
**Zero-payload filtered:** 199,568 (76.7%)  

## Critical Finding: Zero-Payload Noise

**76.7% of all captured records had zero payload length.** These were PDU ResponseMessage
acknowledgments and other signaling records that contained no actual data content. They
were drowning out real data in both the UI Data tab and JSONL corpus logs.

Change 021 adds a filter in `P25DataCaptureModule.emit()` to suppress these at the source,
plus a safety filter in `DataCaptureModel.receive()` at the UI level.

## Network Topology Confirmed

```
Infrastructure:
  10.51.1.116     — Main LRRP/ARS server (all LRRP requests originate here)
  192.168.23.240  — Fleet management console (XCMP queries)
  
Radio IP Space:
  10.71.0.0/16    — Primary radio subnet (2,318 unique IPs observed)
    10.71.128.0 - 10.71.204.x — Most active range (20+ /24 subnets)
    
  Other subnets observed in SNDCP assignments (likely CRC/bit errors):
    10.68.x.x, 10.69.x.x, 10.70.x.x — Secondary ranges
    10.150.x.x, 10.175.x.x, 10.90.x.x — Outlier assignments (possible errors)
```

## SNDCP IP Address Lifecycle

| Event | Count | Description |
|-------|-------|-------------|
| ACTIVATE_ACCEPT | 33,926 | IP address assigned to radio |
| DEACTIVATE | 1,209 | IP address released |
| SNDCP_DATA_CHANNEL_GRANT | 1,181 | Data channel allocated |
| REJECT | 277 | IP assignment rejected |
| OUTBOUND_UNKNOWN | 48 | Unknown SNDCP types |
| DATA (RF_UNCONFIRMED) | 8 | Actual data transfer |
| INBOUND_ACTIVATE_REQUEST | 5 | Radio-initiated requests (rare!) |

**Key observations:**
- **2,097 unique radios** received IP assignments
- **117 radios** had multiple IP addresses (dynamic assignment, up to 12 IPs per radio)
- Longest session: Radio 3409004 held IP 10.71.193.58 for **8.1 hours**
- Most sessions are very short (4 seconds to 30 minutes)
- Only **5 inbound SNDCP requests** captured (radio → infrastructure) — confirms
  we're predominantly seeing the outbound (infrastructure → radio) data path

## IP Layer Data Assessment

### What We Can Reconstruct
1. **SNDCP session lifecycle** — complete activate/deactivate tracking with IP assignments
2. **Radio-to-IP mapping** — which radio had which IP at what time
3. **Application protocol identification** — LRRP, ARS, XCMP identified from port numbers
4. **Network topology** — infrastructure servers, subnets, and traffic patterns

### What We Cannot (Yet) Reconstruct
1. **Full IP packet flows** — The hex payload in our captures contains the raw message bytes
   (SNDCP wrapper, TSBK structure, etc.), NOT the extracted IP packet payload. SDRTrunk
   parses the IP layer in its `PacketMessage` hierarchy but our capture records the outer
   wrapper bytes.
2. **Inbound (radio→infra) data** — We see almost exclusively outbound traffic. Inbound
   data would require capturing on the data channel frequencies, which are TDMA.
3. **LRRP GPS responses** — All 579 LRRP events are "Triggered Location START Requests"
   (server asking radios to report location). Zero GPS coordinate responses captured.
   The responses travel on data channels, not the control channel.

### Why IP Flow Reconstruction is Limited

The `PacketMessage` hierarchy in SDRTrunk already parses IPv4/UDP/LRRP/XCMP internally.
Our capture module records:
- **hex field**: raw message bytes (SNDCP PDU, TSBK structure, MAC frame)
- **details field**: SDRTrunk's parsed toString() output, which includes IP addresses

The actual IP packet bytes are deep inside the parsed hierarchy. To capture them,
we would need to extract `packet.getPacket()` → `IPV4Packet` → raw bytes, which we
partially do for LRRP/XCMP but not for all IP packets.

## Protocol Breakdown (Non-Zero Records)

| Protocol | Count | Notes |
|----------|-------|-------|
| SNDCP | 34,910 | IP address management |
| (empty) | 12,292 | TSBK/MAC without protocol tag |
| XCMP | 2,988 | Device management (fleet console) |
| SACCH | 2,962 | LSD format type in voice frames |
| SLOW-CTRL | 1,639 | LSD slow control channel |
| CRYPTO | 1,576 | LSD crypto sync |
| VENDOR | 1,461 | LSD vendor-specific |
| ARS | 1,313 | Automatic Registration Service |
| UNKNOWN | 896 | Unidentified protocols |
| LRRP | 579 | Location requests |
| IPv4 | 8 | Raw IP packets (SNDCP RF_UNCONFIRMED) |

## Payload Size Distribution

Key clusters:
- **2 bytes**: 7,638 (LSD — Low Speed Data from voice frames)
- **23 bytes**: 8,914 (SNDCP context messages)
- **32 bytes**: 33,837 (SNDCP activate/accept — most common)
- **12 bytes**: 3,378 (TSBK structures)
- **48 bytes**: 1,357 (Extended TSBK/MAC)
- **64 bytes**: 3,651 (IP-wrapped packets — LRRP, ARS, XCMP)

Packets >1000 bytes exist (up to 2016 bytes) — these are multi-block PDU sequences
containing larger data transfers.

## Radio Activity

- **2,867 unique radio IDs** observed in non-zero payload records
- **1,146 radios** have IP address assignments
- Most active radio: 3200891 with 1,423 events over 12.7 hours
- Special IDs (300, 402, 850) are likely talkgroup or system IDs, not individual radios

## Timeline Patterns

- Activity ramps up from 04:00 (night) to 15:00 (afternoon), typical for a city system
- Busiest period: 15:00 hour (6,045 records)
- TSBK_VENDOR spike at startup (04:00) — 320 vendor TSBK records in first minute
- LSD peaks correlate with voice traffic (voice calls carry LSD in every LDU frame)

## Recommendations for Next Steps

1. **Extract IP packet bytes from PacketMessage hierarchy** — modify `processPacketMessage()`
   to also extract the raw IPv4 packet bytes (not just the outer wrapper) for full IP
   flow reconstruction

2. **Capture data channel traffic** — LRRP responses and other inbound data travel on
   TDMA data channels. Need to ensure data channel grants are followed and captured.

3. **Parse the 8 SNDCP RF_UNCONFIRMED records** — these are the only actual IP data
   transfers observed. They contain real user data payloads.

4. **Correlate SNDCP sessions with IP traffic** — link radio→IP assignments with
   LRRP/XCMP/ARS events to build complete session profiles per radio

5. **Investigate non-10.71 IPs in SNDCP** — the radios with IPs outside 10.71.0.0/16
   may indicate bit errors in SNDCP decoding or multi-site roaming
