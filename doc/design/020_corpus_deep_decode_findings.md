# 020: P25 Data Capture — Deep Decode Findings

**Date:** 2026-03-25  
**Corpus:** 24,474 records, 7.6MB, ~1.7 hours from 2 systems (Jacksonville + Clay County)

## Critical Finding: One-Way Traffic Only

**ALL 222 IP-layer packets are OUTBOUND (infrastructure → radio). Zero inbound.**

This means:
- We capture what the system **sends TO** radios (requests, queries, acknowledgments)
- We do NOT capture what radios **send BACK** (responses, GPS locations, status reports)
- This is because P25 data channels on trunked systems are **half-duplex per PDU session** — the outbound (OSP/OSP) path goes on the control channel or a data channel we're monitoring, but the inbound (ISP) responses from radios go on different timeslots/channels we may not be tuned to

## Protocol Decode Results

### 1. LRRP (Location Request/Response Protocol) — 17 packets

All 17 LRRP packets are **Triggered Location Stop Requests** (`msg_type=0x09`) sent FROM the infrastructure server (10.51.1.116:4001) TO 7 specific radios:

| Radio ID | Assigned IP | Packets |
|----------|------------|---------|
| 3416284 | 10.71.202.89 | 2 |
| 3412557 | 10.71.202.112 | 2 |
| 3412092 | 10.71.192.86 | 2 |
| 3404030 | 10.71.204.184 | 4 |
| 3412449 | 10.71.198.90 | 3 |
| 3436046 | 10.71.199.156 | 2 |
| 3432002 | 10.71.204.197 | 2 |

**All share identical LRRP payload:** `09 0E 22 01 50 52 44 64 42 8E 08 62 57 34 4A 50`

The "PRDdB" and "bW4JP" strings detected by our string scanner were actually bytes **inside the LRRP stop request payload** (0x50=P, 0x52=R, 0x44=D, 0x64=d, 0x42=B and 0x62=b, 0x57=W, 0x34=4, 0x4A=J, 0x50=P).

**No GPS coordinates captured** — we only see the infrastructure telling radios to STOP reporting their locations. The actual GPS location reports (responses) travel on the inbound path we're not capturing.

### 2. ARS (Automatic Registration Service) — 50 packets

All from infrastructure (10.51.1.116:49516) to 42 different radios on port 4005.

Common payload pattern: `00 07 BF 08 04 69 C3 [timestamp bytes]`
- `00 07` = likely message length (7 bytes)
- `BF` = flags/type byte (possible server acknowledgment/refresh)
- `08 04 69 C3` = appears to be a constant magic number or session token
- Last bytes increment over time → timestamps

These are **ARS server keepalive/refresh messages** — the infrastructure periodically pinging registered radios to confirm they're still online.

### 3. XCMP (eXtended Command & Management Protocol) — 91 packets

All from a management console (192.168.23.240:52482) to 58 different radios on port 64414.

Single opcode pattern: version=0, opcode=`0x0080`

Payload structure: `00 00 80 00 [12 zero bytes] 26 00 08 00 30 [3 unique bytes] 98 96 7F 00`

- The 3 unique bytes vary per radio (likely the radio's internal ID or serial hash)
- `0x0080` is an XCMP **Device Status Query** or **Radio Check** command
- `98 96 7F 00` = constant footer (possibly protocol version or capabilities mask)

This is a **fleet management console** actively polling 58 radios — likely checking online/offline status.

### 4. SNDCP (Sub-Network Data Control Protocol) — 2,846 packets

**190 unique radios** received IP address assignments in the `10.71.x.x/16` subnet.

Payload decode for Activate TDS Context Accept:
```
01 47 B1 0A [IP octets] 00 00 00 00 00 01 00 3C 40 00 00 03 00 00 00
```
- PDU type 0 + version 1 = standard SNDCP activate accept
- NSAPI = 4, PCOMP = 7
- IP addresses assigned from 10.71.128.0/17 range

Session lifecycle observed:
- 2,638 Activate Context Accepts (data session established)
- 91 Deactivate Context Requests (session teardown)
- 24 Activate Context Rejects (radio denied data service)

### 5. Motorola TDMA Data Channel TSBKs — 1,077 packets

Only **6 unique patterns** repeating, all with structure:
```
7C 87 90 03 8B 90 [service_opts] [ch1] [ch2] [ch3] [ch4] [padding] [CRC]
```

The three TDMA data channel configurations observed:
- **Channel 0x0405 (1029)** — appears in 4 patterns, most common data channel
- **Channel 0x03E1 (993)** — second data channel
- **Channel 0x0365 (869)** — third data channel (rare, only 3 occurrences)

Each pattern announces the same channel 4 times with alternating service option flags (`0FFF` vs `0F7F`), suggesting **active vs standby** data channel slots.

### 6. Motorola Unknown Opcode 135 (0x8D) — 4 unique occurrences

Pattern: `7C 87 90 03 8D 90 05 80 A1 [zeros] CF 80`

This appears to be a **Motorola Data Channel Status/Announcement** — a companion to 0x8B that indicates the data channel is idle or in a specific operating mode. The `0580 A1` likely encodes channel status flags.

### 7. Low Speed Data (LSD) — 428 frames

Embedded in LDU1/LDU2 voice frames:
| Type | Count | Purpose |
|------|-------|---------|
| SACCH | 106 | Slow Associated Control Channel (call metadata) |
| VENDOR | 94 | Motorola proprietary (subscriber features) |
| (empty) | 83 | No LSD content in frame |
| CRYPTO | 75 | Encryption sync/parameters |
| SLOW-CTRL | 70 | Slow control signaling |

The CRYPTO LSD values (`4000` = CRYPTO-SYNC:0) indicate calls using **DES/AES encryption** with key ID 0.

### 8. ResponseMessage (PDU Responses) — 12,334 records

These have **empty hex payloads** (0 bytes). They represent the SDRTrunk-decoded confirmation that a PDU was transmitted, but the actual response content was stripped or not available at the capture layer. This is the bulk of our data — acknowledgment records without payload.

### 9. PDUSequenceMessage (Multi-Block PDU) — 30 records

All 30 have **empty from/to/hex fields**. These are placeholder records where SDRTrunk detected a multi-block PDU sequence header but didn't fully reassemble the data blocks.

## Network Topology Discovered

```
Infrastructure Servers:
  10.51.1.116  — Main data server (ARS port 49516, LRRP port 4001)
  192.168.23.240 — Fleet management console (XCMP port 52482)

Radio Subnet:
  10.71.128.0/17 — 190 radios with dynamic IP assignments
  
Services Running:
  Port 4001 (UDP) — LRRP location service
  Port 4005 (UDP) — ARS registration service  
  Port 64414 (UDP) — XCMP device management
```

## Recommendations

1. **Capture inbound traffic**: The radio→infrastructure path contains GPS responses, ARS registrations, and XCMP status replies. This requires monitoring both timeslots on TDMA data channels.

2. **Decode the 12,334 empty ResponseMessages**: These are the largest class but have no hex data — investigate if P25DataCaptureModule is stripping the payload before logging.

3. **Track SNDCP sessions as radio activity indicators**: Each activate/deactivate cycle maps to a radio establishing a data session — useful for tracking which radios are actively using data services.

4. **The Opcode 135 / 0x8B duplication**: The same hex pattern is being classified as both `MOTOROLA_87_UNKNOWN_OPCODE_135` and `MOTOROLA_8B_TDMA_DATA_CHANNEL` — this is a classification bug in our capture module that should be fixed.
