# 016 — P25 Data Capture: Analysis & Next Steps

**Date:** 2026-03-24  
**Based on:** First live capture session (`logs/p25_data_capture.jsonl`, ~1.9 MB, 5,763 entries)  
**System:** NAC:2209/x8A1 (primary), NAC:954/x3BA (secondary), Motorola P25 Phase II TDMA

---

## Current Capture Summary

| Data Type | Count | Status |
|-----------|-------|--------|
| LRRP Location Requests (outbound) | 128 | ✅ Captured, parsed by SDRTrunk |
| ARS Registration Responses | 29 | ✅ Captured, parsed |
| PDU Responses (ACK/NACK) | 421 | ✅ Captured |
| SNDCP Context Accepts | 25 | ✅ Captured |
| Patch Group Regroup Add/Delete | 86+15 | ✅ Captured |
| LSD (Low Speed Data) | 42 | ✅ Captured as raw hex |
| Raw IP Packets | 167 | ✅ Captured |
| LRRP Location Reports (inbound GPS) | 0 | ❌ Not yet captured — see below |
| TMS Text Messages | 0 | ❌ Not yet captured |
| Status Updates | 0 | ❌ Filter exists, none seen yet |

---

## What We Learned From the Data

### 1. LRRP / GPS Tracking Infrastructure

The system controller (10.51.1.116) is actively sending **LRRP Triggered Location Start Requests** to 18 radios, instructing them to report GPS positions periodically.

**Tracking parameters observed:**
- `TRIGGER ON GPIO` — location triggered by external input (MDT/vehicle events)
- `TRIGGER EVERY:130-132 SECS` — periodic GPS reporting every ~2 minutes
- `TRIGGER DISTANCE:2 / 80` — geofence-style distance triggers

**Most tracked radios (by LRRP packet count):**

| Radio LLID | IP Address | LRRP Packets |
|---|---|---|
| 3412445 | 10.71.195.38 | 21 |
| 3409905 | 10.71.201.251 | 18 |
| 3412449 | 10.71.198.90 | 15 |
| 3412561 | 10.71.210.53 | 10 |
| 3409902 | 10.71.201.234 | 9 |
| 3599070 | 10.71.201.231 | 8 |
| 3406040 | 10.71.192.7 | 7 |
| 3436026 | 10.71.200.201 | 6 |

**Key finding:** We're only seeing **outbound requests** (system→radio "start reporting"). The actual GPS coordinates come back as **inbound LRRP Location Reports** from the radios — these travel as data channel traffic and we're not capturing them yet.

### 2. Patch Group Activity (NAC:954/x3BA)

Single patch group **P:149** being actively managed:
- 86 ADD commands, 15 DELETE commands during capture
- Created and torn down multiple times — likely incident/shift-based

**Raw TSBK hex:**
- ADD: `00 90 00 95 00 95 00 95 00 95 B3 9E`
- DELETE: `01 90 00 95 00 95 00 95 00 95 DC DB`

Supergroup 0x0090 (144) patching member talkgroup(s) 0x0095 (149). The repeated `00 95` entries may represent multiple member groups or padding.

### 3. LSD (Low Speed Data) Analysis

42 LSD frames captured from voice calls, 36 unique values:

**LSD Format Types (bits 15-14 of byte 0):**
- `01xx` (0x40xx) — **Encryption LFSR sync** — 5 occurrences of `4000`
- `00xx` (0x00xx) — **Null / SACCH** — `0001`, `0002`, `0014`, `0800`
- `10xx` (0x80xx) — **Slow associated control** — `8000`
- `11xx` (0xC0xx) — **Reserved/vendor** — `C000`, `CAFA`

**By call context:**
- `1022→600`: 10 frames, all unique random-looking values → **encrypted call** (LSD = crypto sync LFSR)
- `→300`: 3 frames, all unique → likely encrypted
- `59201→1089`: 1 frame `0001` → unencrypted, basic LSD
- `59202→1087`: 1 frame `0800` → unencrypted, possible GPS compact or status
- 25 frames with no from/to → control channel LDU frames

### 4. ARS Registration Census

27 unique data-capable radios registered during the capture window. All assigned IPs in the 10.71.x.x/16 range. Most refreshing every 240 minutes (4 hours), one at 300 minutes.

### 5. SNDCP Data Sessions

~14 unique radios with active IP data sessions. All using:
- NSAPI:1, IPv4 dynamic addressing
- Ready Timer: 15s, Standby Timer: 43200s (12 hours)
- Authorized Groups: [16]
- Radio 3419002 had 11 repeated context accepts — likely roaming or reconnection

### 6. Unidentified IP Packets

10 packets that aren't LRRP or ARS:
- Several to radio 3406040 with UDP payload starting `01 74` — possible vendor-specific data
- One incomplete 4-block PDU to 3599068 (CRC fail — RF error)
- One invalid IPv4 header packet to 3402067

---

## Next Steps — Enhancements to Build

### Priority 1: LRRP Location Report Decoding (GPS coordinates)

**Goal:** Capture inbound LRRP Location Reports containing actual lat/lon/speed/heading.

**What's needed:**
- SDRTrunk already has LRRP parsing code (it decoded the outbound requests)
- The inbound reports come as IP packets FROM the radio's IP TO the controller
- These arrive on data channel traffic grants (the system grants a data channel to the radio)
- We may need to capture from traffic channels, not just the control channel
- Check if `PacketMessage` already receives these — the `P25DataCaptureModule` should capture any `PacketMessage` that arrives

**Where to look in code:**
- `io.github.dsheirer.module.decode.p25.phase1.message.pdu.packet` — packet message parsing
- LRRP decoder classes (search for `LRRP` or `lrrp` in the source)
- Check if traffic channel data grants are followed for data-only sessions

**Expected data from LRRP reports:**
- Latitude, Longitude (decimal degrees)
- Speed (km/h or mph)
- Heading/bearing (degrees)
- Altitude (optional)
- Timestamp
- Radio ID (LLID)

### Priority 2: LSD Format Decoding

**Goal:** Decode the 2-byte LSD values into meaningful fields instead of raw hex.

**What to implement:**
- Parse bits 15-14 as format type:
  - `00` = Null/SACCH
  - `01` = Encryption sync (LFSR)
  - `10` = Slow associated control
  - `11` = Reserved/vendor-specific
- For format `01` (encryption), flag as "Encrypted Voice — Crypto Sync"
- For format `00`, check if bits carry GPS compact data (Motorola extension)
- Display format type in the Data tab `SAP/Opcode` column

**Reference:** TIA-102.BAAA Section 7.2 (Low Speed Data)

### Priority 3: Patch Group Membership Extraction

**Goal:** Parse the raw Motorola Group Regroup TSBK bytes to extract actual member talkgroup IDs.

**What to implement:**
- Decode the TSBK payload bytes to extract:
  - Supergroup ID
  - Member talkgroup list (up to 4 per TSBK)
  - Regroup type (add/delete)
- Show patch group membership in the Data tab
- Correlate with `PatchGroupManager` for real-time patch state tracking
- Log patch group create/dissolve events with timestamps and member lists

**Reference:** Motorola proprietary TSBK format for GROUP_REGROUP_ADD (opcode 0x30)

### Priority 4: TMS Text Message Capture

**Goal:** Capture and display text messages sent between radios.

**What to look for:**
- TMS uses UDP port 4007 in the IP payload
- Messages are sent as PDU data packets
- SDRTrunk may already parse some TMS — check for existing decoders
- If not parsed, the raw payload will contain ASCII text that `PayloadStringScanner` would detect

### Priority 5: Radio Inventory / IP Mapping Table

**Goal:** Build a persistent table mapping Radio LLID → IP address → last seen time.

**Data sources already captured:**
- ARS Registration: LLID → IP, with refresh interval
- SNDCP Context Accept: LLID → IP, with session timers  
- LRRP requests: LLID → IP

**What to implement:**
- Aggregate ARS + SNDCP + LRRP data into a radio inventory
- Show in a new UI panel or as a column enhancement in the Data tab
- Persist across sessions for fleet tracking

### Priority 6: Data Tab UI Improvements

**Goal:** Make the Data tab more useful now that we know what data flows through it.

**Ideas:**
- Add a filter dropdown to show only specific types (LRRP, SNDCP, Regroup, LSD, etc.)
- Color-code rows by type (GPS=green, encryption=red, regroup=orange, etc.)
- Add a "Copy Hex" context menu option
- Show decoded LSD format type in the SAP/Opcode column
- For LRRP packets, show a "📍" icon and extract coordinates when available

---

## Data Flow Architecture Note

Currently, `P25DataCaptureModule` receives messages from the processing chain's message bus. It captures:
- Control channel TSBKs (regroup, extended function, etc.)
- PDU packets (IP data — LRRP, ARS, TMS)
- SNDCP session setup
- LSD from voice frames on traffic channels

For **inbound LRRP Location Reports**, the radio sends its GPS data back over a data channel. If SDRTrunk is following traffic channel grants (which it does for voice), it should also follow data channel grants — meaning the `P25DataCaptureModule` on the traffic channel would receive the inbound LRRP packets. **Verify this is working by monitoring the data channel traffic in the Events tab for "SNDCP DATA PAGE REQUEST" events.**

---

## Analysis Tools Created

| Tool | Purpose |
|------|---------|
| `tools/analyze_capture.py` | High-level message type breakdown |
| `tools/analyze_capture2.py` | PDU target analysis, retry rates |
| `tools/deep_analysis.py` | Full deep dive: LRRP, LSD, patch groups, SNDCP, ARS |
| `tools/capture_analysis.txt` | Output from analyze_capture.py |
| `tools/capture_analysis2.txt` | Output from analyze_capture2.py |
| `tools/deep_analysis_results.txt` | Output from deep_analysis.py |
