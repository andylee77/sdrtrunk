# 019 — P25 Data Capture Analysis: What We Have, What We're Missing

**Date:** 2026-03-25  
**Status:** ANALYSIS  

## Executive Summary

After analyzing all captured data logs (5 JSONL files, ~5.2 MB, covering Jacksonville First Coast Radio and Clay County systems), the findings reveal that **we are capturing less than 5% of the actual data traffic on the P25 systems**. The vast majority of what we're logging is control-channel signaling overhead (ACKs, SNDCP context grants, LRRP request commands), while the actual data payloads (GPS location reports, text messages, bulk data transfers) happen on **data traffic channels** that we're not effectively capturing even though SDRTrunk IS following them.

## Live System State (from screenshot & Activity Summary)

The Now Playing panel shows the following active channels for Jacksonville:

| Status | Decoder | Channel | Frequency | Channel Name | Notes |
|--------|---------|---------|-----------|-------------|-------|
| **CONTROL** (green) | P25-1 | 0-1593 | 860.96250 | LCN 11 | Control channel (Clay County?) |
| **CONTROL** (green) | P25-1 | 0-717 | 855.48750 | LCN 2 | Control channel (Jacksonville) |
| **ACTIVE** (blue) | P25-1 | 0-1193 | 858.46250 | T-LCN 11 | Active traffic channel |
| **ACTIVE** (blue) | P25-1 | 0-957 | 856.98750 | T-LCN 2 | Active traffic channel |
| **DATA** (yellow) | P25-1 | 0-913 | 856.71250 | T-LCN 2 | **FDMA Data channel — ACTIVELY MONITORED** |
| **ACTIVE** (blue) | P25-1 | 0-1513 | 860.46250 | T-LCN 2 | Active traffic channel |
| **CALL** (cyan) | P25-1 | 0-1429 | 859.93750 | T-LCN 2 | Voice call: 03212224→01085 JFRD A1 EMS EAST |

### Key Observation
**SDRTrunk IS following the data channel (0-913 at 856.71250 MHz)**. This is the `CURRENT FDMA DATA CHANNEL` announced in the Activity Summary. A traffic channel slot is allocated and the tuner is decoding it. But our P25DataCaptureModule is not logging meaningful data from it.

### Activity Summary (from System tab)
The Activity Summary comes from `P25P1NetworkConfigurationMonitor`, which tracks decoded TSBK broadcast messages:
```
Network:  WACN:BEE00[781824]  SYSTEM:3BD[957]  NAC:3BA[954]  LRA:00[0]
Site:     RFSS:01[1]  SITE:02[2]  STATUS: ACTIVE RFSS NETWORK CONNECTION
PRI CTRL: 0-717  DOWN:855487500  UP:810487500
SEC CTRL: 0-673, 0-793, 0-797
DATA CH:  0-913  DOWN:856712500
Bands:    0=FDMA 851MHz/12.5kHz, 1=FDMA 762MHz/12.5kHz
          2=TDMA 851MHz/12.5kHz/2TS, 3=TDMA 762MHz/12.5kHz/2TS
```

### Source of Activity Summary Data
The info comes from these TSBK message types processed by `P25P1NetworkConfigurationMonitor`:
- **`OSP_NETWORK_STATUS_BROADCAST`** → WACN, System, NAC, LRA
- **`OSP_RFSS_STATUS_BROADCAST`** → RFSS, Site, Primary Control Channel
- **`OSP_SECONDARY_CONTROL_CHANNEL_BROADCAST`** → Secondary control channels
- **`OSP_SNDCP_DATA_CHANNEL_ANNOUNCEMENT_EXPLICIT`** → **The FDMA data channel (0-913)**
- **`OSP_IDENTIFIER_UPDATE`** → Frequency band definitions
- **`MOTOROLA_OSP_BASE_STATION_ID`** → Station ID/license
- **`MOTOROLA_OSP_TDMA_DATA_CHANNEL`** → TDMA data channel announcements

⚠️ **None of these TSBK broadcast messages are captured by P25DataCaptureModule** — it only captures vendor-specific/Motorola TSBKs, not the standard network configuration broadcasts.

---

## Data Log Inventory

| Log File | System | Date | Entries | Size |
|----------|--------|------|---------|------|
| `p25_data_Jacksonville_..._20260324.jsonl` | Jacksonville | 3/24 | 2,890 | 884 KB |
| `p25_data_Jacksonville_..._20260325.jsonl` | Jacksonville | 3/25 | 3,781 | 904 KB |
| `p25_data_Clay_County_20260324.jsonl` | Clay County | 3/24 | ~2,300 | 699 KB |
| `p25_data_Clay_County_20260325.jsonl` | Clay County | 3/25 | 1,392 | 413 KB |
| `p25_data_capture.jsonl` | Combined (legacy) | 3/24 | ~7,000 | 2.3 MB |

---

## What We're Successfully Identifying

### 1. SNDCP Context Accepts (IP Address Assignments) ✅
The system assigns dynamic IPv4 addresses to radios when they activate data sessions.

- **Jacksonville:** 817+ entries today, many unique LLIDs
- **Clay County:** 25 entries, 16 unique LLIDs
- **Example:** `LLID:3200295 ACTIVATE TDS CONTEXT ACCEPT NSAPI:1 ADDRESS:10.71.153.144 TYPE:IPV4 DYNAMIC`
- **Value:** We can map radio IDs (LLIDs) to their IP addresses — useful for correlating later traffic

### 2. LRRP Triggered Location START Requests ✅ (Commands Only)
Infrastructure sends GPS tracking commands to radios on the control channel.

- **Clay County:** 46 entries (all TRIGGERED LOCATION START)
- **Jacksonville:** 0 entries (different system configuration — may use different LRRP approach)
- **Example:** `LRRP TRIGGERED LOCATION START REQUEST ID:196607 TRIGGER ON GPIO TRIGGER DISTANCE`
- **Radios targeted:** LLID 3422021, LLID 3599072 (Clay County)
- **⚠️ GPS field is empty** — these are just the commands, not the responses with coordinates

### 3. ARS Registration Successes ✅ (Confirmations Only)
Infrastructure acknowledges radio registrations.

- **Clay County:** 7 entries on UDP port 49516→4005
- **Example:** `ARS REGISTRATION SUCCESS REFRESH IN:240mins` to LLID 3402125, 3416337, etc.
- **Jacksonville:** 0 entries (possibly different port or not using ARS)

### 4. Motorola XCMP Device Management ✅
Infrastructure polls radios for status/management on UDP port 52482→64414.

- **Jacksonville:** 15 XCMP-tagged + 26 untagged but same pattern = **~41 total**
- **Payload:** Consistent 53-byte IPv4/UDP packets from 192.168.23.240:52482 to radios:64414
- **UDP payload pattern:** `00 00 80 00 00 00 00 00 00 00 00 00 00 00 26 00 08 00 30 XX XX 98 96 7F 00 AA AA AA AA AA [4 varying bytes]`
- **This is:** Motorola XCMP radio status polling/keep-alive or time sync messages
- **Note:** The `26 00 08 00` appears to be an XCMP opcode, the `30` is a sub-function, and the `98 96 7F 00` may be a timestamp

### 5. PDU ACK Responses ✅ (Overhead Only)
The control channel sends ACKs confirming data block reception.

- **Jacksonville:** 2,898 ACKs = **98% of all PDU_PACKET entries**
- **Clay County:** 506 ACKs = **85% of all PDU_PACKET entries**
- **200 unique radio targets** in Clay County ACKs alone
- **Value:** These confirm data was exchanged, but the actual data payload is NOT in the ACK

### 6. Motorola Patch Group Management ✅
- **Jacksonville:** 30 entries (Group Regroup Add/Delete for Patch Group P:149)
- **Clay County:** 350 entries (includes idle noise from SACCH)

### 7. Low Speed Data (LSD) ✅
Embedded in voice frames (LDU1/LDU2).

- **Jacksonville:** 151 entries
- **Clay County:** 5 entries
- **Types seen:** CRYPTO-SYNC, SACCH, SLOW-CTRL, VENDOR
- **Value:** Low — mostly zeros and sync data

### 8. SNDCP Data Channel Grants ✅ (Signaling Only)
Control channel directs radios to specific traffic channels for data.

- **Clay County:** 10 grants observed (channels 0-1193, 0-1117)
- **Example:** `SNDCP DATA CHANNEL GRANT TO:3599050 CHAN:0-1193 NSAPI:1 HALF DUPLEX CIRCUIT MODE`
- **⚠️ This is the smoking gun** — we SEE the grants but the actual data happens on those traffic channels

---

## What We're NOT Capturing (The Missing Data)

### 1. 🔴 LRRP Location REPORTS (GPS Coordinates) — CRITICAL MISS
When infrastructure sends a TRIGGERED LOCATION START request, the radio responds with its GPS coordinates on a **data traffic channel**. We see the request but NOT the response.

- **Evidence:** 46 LRRP location requests sent, 0 location reports received
- **Expected data:** Latitude, longitude, heading, speed for each responding radio
- **Impact:** We're completely blind to vehicle/unit location tracking

### 2. 🔴 ARS Registration REQUESTS — MODERATE MISS  
Radios initiate registration on traffic channels. We only see the infrastructure's ACK response.

- **Evidence:** 7 registration successes but 0 initial requests
- **Impact:** We miss the initial "hello" from radios

### 3. 🔴 TMS (Text Messaging Service) — UNKNOWN MISS
Text messages between radios travel over data traffic channels.

- **Evidence:** No TMS messages found in any log
- **Impact:** If the system uses text messaging, we're missing all of it

### 4. 🔴 Radio→Infrastructure Data Responses — CRITICAL MISS
When radios respond to XCMP commands, LRRP requests, or send any data, it goes on traffic channels.

- **Evidence:** The ratio tells the story:
  - 200 unique radio targets in ACKs vs only ~50 IP packets captured
  - 817 SNDCP context accepts (radios getting IP addresses) vs 41 actual IP packets
  - The infrastructure is ACKing data from radios that we never see

### 5. 🟡 Bulk Data Transfers — MODERATE MISS
SNDCP DATA CHANNEL GRANTS direct radios to traffic channels for larger data sessions.

- **Evidence:** 10 data channel grants seen in Clay County, but we don't capture the data sessions

### 6. 🟡 Unclassified UDP Payloads — CLASSIFICATION GAP
Even the IP packets we DO capture lack protocol classification.

- **Evidence:** 2,796 out of 2,811 PDU_PACKET entries have empty protocol/msgClass/sap fields
- **Impact:** We're logging hex data but not identifying what protocol it contains

---

## Why We're Missing Data: Root Cause Analysis

### The Control Channel vs Traffic Channel Problem

```
CONTROL CHANNEL (what we primarily monitor)          DATA TRAFFIC CHANNELS (where the data lives)
┌──────────────────────────────────────┐             ┌──────────────────────────────────────┐
│ Infrastructure → Radio:              │             │ Radio → Infrastructure:              │
│  • SNDCP DATA CHANNEL GRANT  ──────────────────►  │  • LRRP Location REPORTS (GPS) 🔴    │
│  • LRRP Location START REQUEST       │             │  • ARS Registration REQUEST  🔴      │
│  • ARS Registration SUCCESS          │             │  • TMS Text Messages  🔴             │
│  • XCMP/Status Polling               │             │  • SNDCP Data Payloads  🔴           │
│  • PDU ACK (ALL BLOCKS RECEIVED)     │             │  • Any radio-initiated data  🔴      │
│                                      │             │                                      │
│ What we see: commands OUT, ACKs BACK │             │ What we miss: actual data content     │
└──────────────────────────────────────┘             └──────────────────────────────────────┘
```

### The Real Problem: We Can Only See the Downlink

**This is the fundamental limitation.** Looking at the screenshot, the DATA channel at 856.71250 MHz IS being monitored. The `P25DataCaptureModule` IS instantiated on traffic channels (including data channels) with parent→child forwarding to the control channel's module. The architecture is working correctly.

But we can only receive the **downlink** frequency (856 MHz band). The **uplink** (radio→infrastructure) is on a completely different frequency (810 MHz band, per the frequency band definition: `TRANSMIT OFFSET:-45000000`).

```
DOWNLINK (856 MHz band) — what we receive        UPLINK (811 MHz band) — what we CAN'T receive
┌─────────────────────────────────────┐          ┌─────────────────────────────────────┐
│ Infrastructure → Radio:             │          │ Radio → Infrastructure:             │
│  • SNDCP context accepts            │          │  • LRRP Location REPORTS (GPS!) 🔴  │
│  • LRRP Location START requests     │          │  • ARS Registration REQUESTS  🔴    │
│  • XCMP polls / time syncs          │          │  • TMS Text Messages  🔴            │
│  • PDU ACKs (ALL BLOCKS RECEIVED)   │          │  • Radio-initiated data  🔴         │
│  • ARS Registration SUCCESS         │          │  • XCMP command responses  🔴       │
│                                     │          │                                     │
│ ✅ This is what we're capturing     │          │ 🔴 45 MHz away — can't receive      │
└─────────────────────────────────────┘          └─────────────────────────────────────┘
```

### Contributing Factors

1. **Downlink-only limitation** — We monitor 856.71250 MHz (downlink) but radio responses go to ~811.71250 MHz (uplink). This is a hardware/RF limitation, not a software bug. ALL SDR-based scanners have this limitation.

2. **What we DO get from the data channel is correct** — The LRRP requests, XCMP polls, SNDCP context accepts, and PDU ACKs are all legitimate downlink traffic on the data channel. We're capturing what's actually there.

3. **The PDU ACKs prove data IS flowing** — The ~2,900 "ALL BLOCKS RECEIVED" ACKs confirm the infrastructure received data FROM radios on the uplink. We see the acknowledgment but not the original data.

4. **P25DataCaptureModule IS on traffic channels** — `DecoderFactory` creates a P25DataCaptureModule for every traffic channel (including data channels). Traffic channel instances forward captures to the control channel's module via `setParentModule()`. The architecture is correct.

5. **Some infrastructure-relayed data MAY appear on downlink** — When the infrastructure forwards data to another radio (e.g., text message relay, LRRP report forwarded to dispatch), it appears on the downlink. This explains the few IP packets we DO capture (LRRP requests, XCMP, ARS confirmations) — these are all infrastructure-originated.

---

## Identified Protocol Patterns in Captured Data

### Port-Based Protocol Map

| Source Port | Dest Port | Protocol | Direction | Count | Notes |
|------------|-----------|----------|-----------|-------|-------|
| 52482 | 64414 | XCMP (Motorola) | Infra→Radio | ~41 | Device mgmt/status poll |
| 4001 | 4001 | LRRP | Infra→Radio | 66 | Location tracking requests |
| 49516 | 4005 | ARS | Infra→Radio | 7 | Registration confirmations |

### IP Address Map

| IP Address | Role | System |
|-----------|------|--------|
| 192.168.23.240 | Infrastructure (Jax) | Jacksonville |
| 10.51.1.116 | Infrastructure (Clay) | Clay County |
| 10.71.xxx.xxx | Radio units (Jax) | Jacksonville |
| 10.71.192-217.xxx | Radio units (Clay) | Clay County |

### XCMP Payload Structure (Decoded)
```
Offset 0-1:  51 00              — SNDCP/PPP framing header
Offset 2-21: 45 00 00 35 ...   — IPv4 header (20 bytes, total len 53)
Offset 22-29: CD 02 FB 9E ...  — UDP header (src:52482, dst:64414, len:33)
Offset 30+:  UDP payload:
  00 00 80 00 00 00 00 00 00 00 00 00 00 00  — 14 bytes header/padding
  26 00 08 00                                 — XCMP opcode (0x2600) + length (8)
  30 XX XX 98 96 7F 00                        — XCMP sub-function + timestamp?
  AA AA AA AA AA                              — Padding
  XX XX XX XX                                 — Variable (checksum or radio-specific)
```

---

## Data Capture Efficiency Summary

| Metric | Jacksonville | Clay County |
|--------|-------------|-------------|
| Total entries logged | 3,781 | 1,392 |
| Actual data packets (non-ACK, non-overhead) | 41 (1.1%) | 88 (6.3%) |
| Unique radios with data activity (from ACKs) | ~100+ | 200+ |
| LRRP requests seen | 0 | 46 |
| LRRP responses with GPS | 0 | 0 |
| ARS registrations | 0 | 7 |
| XCMP device polls | ~41 | 0 |
| Protocol-classified packets | 15 (0.5%) | 73 (5.2%) |
| **Estimated data we're missing** | **~95%+** | **~90%+** |

---

## Recommendations

### Short-Term (Improve What We Have)

1. **Better XCMP classification** — The ~26 "UNKNOWN PACKET" entries in Jacksonville are the same XCMP pattern but aren't being classified (first byte `51` instead of `45` may be confusing the IPv4 detection). Fix `PayloadStringScanner` to handle the SNDCP framing prefix.

2. **Populate protocol/msgClass/sap fields** — 99.5% of PDU_PACKET entries have empty classification fields. The `details` string contains the info but structured fields are blank.

3. **Filter ACK noise** — 85-98% of logged data is just ACK responses. Consider:
   - Counting ACKs instead of logging each one
   - Only logging ACKs that correspond to data we actually captured
   - A separate "ack_summary" counter per radio per minute

4. **Flag LRRP requests as "AWAITING RESPONSE"** — So it's clear the GPS data is pending

### Medium-Term (Capture More Data)

5. **Ensure data traffic channels capture data** — Verify P25DataCaptureModule is wired up on traffic channel decoders, not just control channel. The parent→child forwarding needs to work bidirectionally.

6. **Prioritize data channel following** — When an SNDCP DATA CHANNEL GRANT is seen, ensure a traffic channel slot is allocated for it even if it means briefly deprioritizing a voice channel.

7. **Track data channel grant→response correlation** — When we see a GRANT, start a timer and log whether we successfully captured data from that traffic channel.

### Long-Term (Full Data Capture)

8. **Dedicated data channel monitoring** — If the system uses a small number of dedicated data channels (Clay County appears to use channels 0-1193 and 0-1117), consider permanently monitoring those frequencies alongside the control channel.

9. **LRRP response parsing** — Implement full LRRP response message parsing to extract GPS coordinates when we do capture location reports.

10. **TMS/SDS parsing** — Implement Short Data Service and Text Messaging Service decoders for any text messages that appear on data channels.
