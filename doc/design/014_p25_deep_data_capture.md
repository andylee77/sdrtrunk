# 014 — P25 Deep Data Capture

## Status: DESIGN — Not yet implemented

## Extending SDRTrunk Beyond the Message Tab
### Technical Reference for Fork Development
### Unencrypted P25 Phase 1 & Phase 2 Systems

---

**Table of Contents**

1. [Overview and Scope](#1-overview-and-scope)
2. [What the Message Tab Does Not Show](#2-what-the-message-tab-does-not-show)
3. [Low Speed Data (LSD)](#3-low-speed-data-lsd)
4. [PDU Application Layer Payloads](#4-pdu-application-layer-payloads)
5. [LRRP — Location Protocol](#5-lrrp--location-registration-and-response-protocol)
6. [Short Data Bursts (SDB)](#6-short-data-bursts-sdb)
7. [Manufacturer/Vendor TSBK Opcodes](#7-manufacturervendor-tsbk-opcodes)
8. [Voice Frame Embedded Link Control (LC)](#8-voice-frame-embedded-link-control-lc)
9. [Phase 2 MAC Layer Messages](#9-phase-2-mac-layer-messages)
10. [String Scanning and Protocol Identification](#10-string-scanning-and-protocol-identification-pipeline)
11. [Logging and Corpus Building Strategy](#11-logging-and-corpus-building-strategy)
12. [Recommended Display Panels](#12-recommended-display-panel-additions-for-your-fork)
13. [Implementation Priority Order](#13-implementation-priority-order)
14. [Codebase Reality Check](#14-codebase-reality-check)
15. [Unit Identity Resolution](#15-unit-identity-resolution--building-the-id-to-name-map)
16. [Motorola-Specific Transmissions](#16-motorola-specific-transmissions-on-p25-systems)
17. [Whisper Integration Architecture](#17-whisper-integration-architecture)
18. [Legal and Operational Note](#18-legal-and-operational-note)
A. [P25 Data Unit ID Quick Reference](#appendix-a--p25-data-unit-id-quick-reference)
B. [Key SDRTrunk Source Directories](#appendix-b--key-sdrtrunk-source-directories)

---

## 1. Overview and Scope

SDRTrunk's message tab surfaces high-level decoded summaries — call grants, registrations, affiliations, and talker metadata. However, the decoder pipeline captures significantly more data than it renders. This document catalogs all additional data layers available in an unencrypted P25 system that are currently decoded internally but not displayed, partially displayed, or silently discarded.

This reference is organized by capture priority: high-value targets that are easiest to add, through deep frame-level data requiring more significant pipeline work. Each section includes the relevant SDRTrunk source locations, data formats, and suggested hook points for our fork.

> **Note:** Section 14 contains a "Codebase Reality Check" based on actual source inspection of our fork (March 2026). Several claims in the original reference have been corrected there — read section 14 alongside any section that interests you.

---

## 2. What the Message Tab Does Not Show

The message tab renders the `toString()` output of decoded message objects. Every message class decodes more fields internally than its string representation exposes. The gap between decoded and displayed falls into four categories:

| Category | Examples | Current State |
|----------|----------|---------------|
| PDU Application Payloads | IP packets, CAD data, MDT traffic | Decoded structurally, payload bytes available but not interpreted |
| Low Speed Data (LSD) | 2 bytes per voice frame — telemetry, status | Extracted as hex string, not accumulated or parsed |
| Unknown/Vendor TSBKs | Opcodes 0x30–0x3F manufacturer range | Caught as unknown, raw bits not emitted to log |
| Voice Frame Embedded LC | Talker alias (full), encryption params | Partially surfaced — vendor assemblers exist |
| Phase 2 MAC Payloads | Multi-fragment MAC PDU content | Structurally parsed, payload not rendered |
| LRRP Responses | GPS lat/lon, location confidence | Not extracted from PDU payload |
| SDB Payload Content | Short Data Burst application bytes | Frame logged, content not displayed |
| MBT Extended Blocks | Multi-block TSBK extended payloads | Header shown, extended data dropped |

---

## 3. Low Speed Data (LSD)

Low Speed Data is a frequently overlooked channel in SDRTrunk. Every P25 Phase 1 voice frame (LDU1 and LDU2) contains 2 LSD bytes embedded in the frame structure. Over a typical 5-second voice transmission, this accumulates to approximately 40 bytes per call — enough to carry meaningful application data.

### 3.1 Why LSD Matters

- Every voice call carries LSD regardless of whether the agency uses it for data
- On systems that populate LSD, it carries: call priority, radio status codes, encryption sync material, and manufacturer-specific telemetry
- Aggregated per-call, LSD bytes form a complete secondary data stream alongside voice
- SDRTrunk extracts LSD as a hex display string but does NOT accumulate per-call or parse structurally

### 3.2 SDRTrunk Source Location

```
src/main/java/io/github/dsheirer/module/decode/p25/phase1/message/ldu/
  LDUMessage.java    ← Parent class — LOW_SPEED_DATA bit positions defined here
  LDU1Message.java   ← Inherits LSD from LDUMessage
  LDU2Message.java   ← Inherits LSD from LDUMessage
```

**Actual bit positions** (from `LDUMessage.java`):
```
LOW_SPEED_DATA = {1392, 1393, 1394, 1395, 1396, 1397, 1398, 1399,
                  1408, 1409, 1410, 1411, 1412, 1413, 1414, 1415}
```
This is 16 bits (2 bytes). The existing `getLowSpeedData()` method returns `getMessage().getHex(LOW_SPEED_DATA, 4)` — a hex string appended to the message stub display as `" VOICE LSD:<hex>"`.

### 3.3 What's Missing

- **No per-call accumulation** — each LDU shows its own 2-byte LSD but they're never collected across a call
- **No CRC checking** — LSD has a cyclic error detection code that's not validated
- **No structured parsing** — the 2 bytes per frame are not interpreted as status/priority/telemetry
- **No listener emission** — LSD bytes are not forwarded to any data listener

### 3.4 Hook Point

```java
// Add LSD accumulator per call — collect from each LDU, emit on call teardown
public class LSDAccumulator implements Listener<IMessage> {
    private final Map<Integer, ByteArrayOutputStream> perCallLSD = new ConcurrentHashMap<>();

    @Override
    public void receive(IMessage message) {
        if (message instanceof LDUMessage ldu) {
            String lsdHex = ldu.getLowSpeedData();
            // Accumulate per call ID, emit complete buffer on call end
        }
    }
}
```

### 3.5 Display Recommendation

Add a per-call LSD accumulator that collects all LSD bytes for the duration of the call and displays them as a hex string with ASCII interpretation in a dedicated panel or secondary message row. Flag calls where LSD bytes are non-zero as potentially carrying application data.

---

## 4. PDU Application Layer Payloads

P25 Packet Data Units (PDUs) are the primary vehicle for data calls. SDRTrunk correctly parses PDU headers — service access point, block count, source/destination IDs — and has block reassembly infrastructure. The gap is in application-layer payload interpretation.

### 4.1 PDU Structure

```
[ PDU Header — decoded by SDRTrunk ]
  ├── Format (UDT/CDT/Response/Unconfirmed/Confirmed)
  ├── Service Access Point (SAP) — tells you payload type
  ├── Source/Destination LLID
  ├── Block count
  └── Data Header CRC

[ Data Blocks — reassembled in PacketMessage ]
  ├── Block 0..N application payload bytes
  └── Final block CRC
```

### 4.2 Service Access Point (SAP) Values

The SAP field identifies payload type — already decoded in SDRTrunk but not used to route payload to protocol-specific parsers:

| SAP Value | Payload Type | What to Expect |
|-----------|-------------|----------------|
| 0x00 | Unprotected data | Raw IP or vendor-framed MDT data |
| 0x01 | TCP/IP Header Comp | Compressed IP — decompress then parse |
| 0x02 | UDP/IP Header Comp | Compressed UDP — common for GPS/CAD |
| 0x03 | IP | Raw IP packet — parse as standard IPv4 |
| 0x04 | ARP | ARP — reveals radio IP assignments |
| 0x05 | SNDCP | Sub-Network Dependent Convergence Protocol |
| 0x09 | LRRP | Location Registration/Response — GPS payloads |
| 0x0A | LRRP Response | Location response with lat/lon data |
| 0x3D | Motorola proprietary | Motorola-specific data, vendor framed |
| 0x3E | Kenwood proprietary | Kenwood-specific MDT data |
| 0x3F | Harris/other vendor | Vendor-specific, requires fingerprinting |

### 4.3 SDRTrunk Source Location

```
src/main/java/io/github/dsheirer/module/decode/p25/phase1/message/pdu/
  PDUSequence.java                   ← Holds PDUHeader + List<DataBlock>, isComplete()
  PDUSequenceMessage.java            ← Base for PDU message types
  packet/PacketMessage.java          ← getPayloadMessage() reassembles all blocks
  ambtc/                             ← Abbreviated MBT C messages
  block/                             ← Individual data blocks
    UnconfirmedDataBlock.java        ← getData() has raw payload bytes
    ConfirmedDataBlock.java          ← same — getData() returns BinaryMessage
```

**Key:** `PacketMessage.getPayloadMessage()` already fully reassembles data blocks into a single contiguous `BinaryMessage`. The reassembly infrastructure exists — what's missing is application-layer interpretation of the reassembled bytes.

### 4.4 Payload Extraction Hook

```java
public class PDUPayloadExtractor implements Listener<IMessage> {
    @Override
    public void receive(IMessage message) {
        if (message instanceof PacketMessage pkt) {
            BinaryMessage payload = pkt.getPayloadMessage();
            if (payload != null) {
                byte[] bytes = payload.getBytes();
                int sap = pkt.getHeader().getServiceAccessPoint();
                emitPayloadEvent(pkt, sap, bytes);
                scanStrings(bytes);
                if (sap == 0x09 || sap == 0x0A) parseLRRP(bytes);
                if (sap == 0x03) parseIPPacket(bytes);
            }
        }
    }
}
```

---

## 5. LRRP — Location Registration and Response Protocol

LRRP is the P25 standard for GPS/location data. It rides inside PDUs with SAP 0x09/0x0A and contains structured binary location data. On agencies using LRRP, this gives you real-time GPS coordinates for every radio transmitting on the system.

### 5.1 LRRP Payload Structure

LRRP Immediate Location Response (most common):

```
Byte  0     : Message Type (0x22 = Immediate Location Response)
Byte  1-4   : Source LLID (radio unit ID)
Byte  5     : Reporting interval / velocity present flags
Bytes 6-9   : Latitude  (32-bit signed int, degrees * 2^23)
Bytes 10-13 : Longitude (32-bit signed int, degrees * 2^23)
Byte  14    : Position error / confidence
Bytes 15-16 : Velocity (optional, if flag set)
Bytes 17-18 : Heading  (optional, if flag set)
```

Lat/Lon conversion:
```java
double lat = (int32_value) / Math.pow(2, 23);
double lon = (int32_value) / Math.pow(2, 23);
```

### 5.2 LRRP Message Types

| Type Byte | Message | Content |
|-----------|---------|---------|
| 0x10 | Immediate Location Request | Dispatcher requesting radio location |
| 0x20 | Triggered Location Request | Periodic reporting setup |
| 0x22 | Immediate Location Response | Radio reporting GPS fix — main target |
| 0x23 | Triggered Location Response | Periodic GPS report |
| 0x32 | Location Cancel Request | Stop reporting |
| 0x38 | Location Protocol Error | Error response |

### 5.3 Display Recommendation

- Show lat/lon with 6 decimal places alongside unit ID and talkgroup
- Log to a separate LRRP event stream sortable by unit ID
- Optionally emit to a local socket for mapping integration (e.g., feeding ATAK, Google Maps overlay)
- Calculate distance/bearing between sequential LRRP fixes per unit to derive speed

---

## 6. Short Data Bursts (SDB)

Short Data Bursts are small, low-latency data transmissions carried on the traffic channel alongside or between voice frames. They are commonly used for status messages, emergency alerts, radio check responses, and small telemetry payloads.

### 6.1 SDB Types

| SDB Format | Payload Size | Common Use |
|------------|-------------|------------|
| Individual | Up to 32 bytes | Unit-to-unit status, radio check, telemetry |
| Group | Up to 32 bytes | Broadcast status to talkgroup |
| Confirmed | Up to 32 bytes | Acknowledged status — dispatcher confirmation required |
| Unconfirmed | Up to 32 bytes | Fire-and-forget status update |

### 6.2 What's in the Payload

- On Motorola systems: often a 1-2 byte status code from a predefined list (e.g., 'En Route', 'On Scene', 'Available')
- On Harris/L3Harris: may carry structured TLV data with GPS embedded
- On generic P25: may be raw ASCII status strings
- Emergency alert SDBs carry the initiating radio's ID and reason code

### 6.3 Source Location

```
src/main/java/io/github/dsheirer/module/decode/p25/phase1/message/
  sdu/SDUMessage.java           ← Single Data Unit (related)
  pdu/                          ← SDB rides in PDU wrapper
```

Look for `DataUnitID.SHORT_DATA_UNIT` handling in `P25P1MessageDecoder.java` — this is where SDB frames enter the pipeline. The payload object is created but content bytes are not forwarded to any display listener.

---

## 7. Manufacturer/Vendor TSBK Opcodes

The TSBK opcode space reserves 0x30–0x3F for manufacturer-specific use. SDRTrunk catches these as `UnknownVendorOSPMessage`/`UnknownVendorISPMessage` or manufacturer-specific subclasses but does not render their payload content. These opcodes often carry proprietary but very useful data.

### 7.1 Known Vendor Opcode Ranges

| Opcode Range | Vendor | Typical Payload |
|-------------|--------|-----------------|
| 0x34 (MFID 0x90) | Motorola | Group regroup, failsoft, SmartZone data |
| 0x38 (MFID 0x90) | Motorola | Queued response with vendor detail |
| 0x3x (MFID 0xA4) | Harris/L3Harris | Unit status, location data extensions |
| 0x3x (MFID 0x00) | DTMF/Standard | Sometimes used for inter-system data |
| Various (MFID 0xB4) | Kenwood | Kenwood-specific trunking extensions |
| Various (MFID 0x60) | EF Johnson | VIDA system extensions |

### 7.2 Extraction Strategy

The MFID (Manufacturer ID) field in the TSBK header identifies the vendor. Use this to route to vendor-specific parsers:

```java
// In your message listener, catch vendor TSBKs and extract MFID + payload:
if (message instanceof UnknownVendorOSPMessage tsbk) {
    int mfid   = tsbk.getMessage().getInt(8, 15);   // MFID field
    int opcode = tsbk.getMessage().getInt(2, 7);     // Opcode
    byte[] raw = tsbk.getMessage().getBytes(16, 72); // Payload bits

    // Log all: mfid + opcode + raw hex — build corpus first
    // then write targeted parsers per MFID once patterns emerge
    vendorDispatch(mfid, opcode, raw, tsbk);
}
```

### 7.3 Corpus-First Approach

> **NOTE:** Before writing vendor parsers, log all unknown TSBK opcodes with full raw hex and metadata (system, timestamp, opcode, MFID) for at least 24-48 hours of traffic. Repeat occurrences with consistent structure will fingerprint the payload format far more reliably than spec guessing.

---

## 8. Voice Frame Embedded Link Control (LC)

Every P25 voice call carries Link Control words embedded in LDU1 frames. These are separate from the call setup TSBK and can carry data that changes mid-call. SDRTrunk has extensive LC handling including vendor-specific talker alias assemblers, but not all fields are fully surfaced.

### 8.1 LC Word Types in Voice Frames

| LC Opcode | Name | What May Not Be Shown |
|-----------|------|----------------------|
| 0x00 | Group Voice Ch User | Emergency bit, priority level, full encryption params |
| 0x03 | Unit-to-Unit Voice | Source/dest full address, call timer |
| 0x06 | Encrypted Sound Ch | Algorithm ID, key ID — useful for encryption fingerprinting |
| 0x0F | Call Termination | Termination reason code |
| 0x17 | Talker Alias Header | Format + alias length — alias content in subsequent frames |
| 0x18 | Talker Alias Block 1-3 | Alias string continuation blocks |

### 8.2 Talker Alias — Existing Infrastructure

SDRTrunk already has significant talker alias support:

- **Motorola Phase 1:** `MotorolaTalkerAliasHeader` + `MotorolaTalkerAliasDataBlock` → `MotorolaTalkerAliasAssembler` → `MotorolaTalkerAliasComplete`
- **L3Harris Phase 1:** `L3HarrisTalkerAliasBlock1-4` → `L3HarrisTalkerAliasAssembler` → `L3HarrisTalkerAliasComplete`
- **Phase 2 MAC equivalents** for both vendors
- **`TalkerAliasManager`** in `P25TrafficChannelManager` enriches identifier collections
- **`P25TalkerAliasIdentifier`** is the identifier type

The remaining gap (if any) is likely in edge cases: truncated multi-block sequences, encoding format handling (7-bit ASCII vs UTF-8), and ensuring all blocks are collected before call teardown. Live testing on target systems would confirm.

### 8.3 Encryption Fingerprinting via LC

Even on systems where voice is encrypted, the LC words in LDU1 frames reveal the algorithm ID and key ID in cleartext. This allows fingerprinting: which radios use which keys, when key changes occur, and which talkgroups use which algorithms — all without decrypting any audio.

```
LC Opcode 0x06 — Encrypted Sound Channel User:
  Bits 8-15  : Algorithm ID (e.g., 0x84 = AES-256, 0x81 = DES-OFB)
  Bits 16-31 : Key ID
  Bits 32-59 : Source address
```

Log these per call for key usage analysis.

---

## 9. Phase 2 MAC Layer Messages

P25 Phase 2 uses a MAC (Medium Access Control) layer that carries more complex multiplexed data than Phase 1. SDRTrunk's Phase 2 decoder handles the structural parsing but several MAC PDU types do not fully expose their payload.

### 9.1 MAC Message Types with Hidden Payload

| MAC Type | Class | Missing Data |
|----------|-------|-------------|
| MAC_SIGNAL | P25P2MacSignal | Secondary payload bytes after signal word |
| MAC_END_PUSH_TO_TALK | EndPushToTalk | Final call statistics embedded in end frame |
| MAC_IDLE | MacIdle | Idle payload — sometimes carries system data |
| Unknown MAC types | MacStructureVariableLength | Full raw payload not emitted |
| Multi-fragment PDU | Various | Fragment reassembly incomplete for large payloads |

### 9.2 Phase 2 Source Location

```
src/main/java/io/github/dsheirer/module/decode/p25/phase2/message/mac/
  MacMessageFactory.java              ← Factory — unknown types fall through here
  MacStructureVariableLength.java     ← Catch-all for unknown types
  UnknownMacMessage.java              ← Wraps UnknownMacStructure for unrecognized PDU types
  UnknownVendorMessage.java           ← Unrecognized vendor opcodes
  structure/
    *.java                            ← Individual MAC structure classes
```

**Note:** The factory class is `MacMessageFactory.java` (not `MacStructureFactory` as some references state). Unknown MAC PDU types fall to `UnknownMacMessage` wrapping an `UnknownMacStructure`. Unknown vendor opcodes create `UnknownVendorMessage`. L3Harris GPS (vendor V170, opcode 0x80) has special detection logic.

Hook at `MacMessageFactory.create()` to intercept all MAC messages before type dispatch, log raw bits for unknowns.

---

## 10. String Scanning and Protocol Identification Pipeline

Once you are capturing raw payload bytes from PDUs, LSD accumulations, and SDBs, the string scanning and protocol identification pipeline turns raw bytes into actionable intelligence.

### 10.1 String Scanner Implementation

```java
public class PayloadStringScanner {
    private static final int MIN_RUN = 4;
    private static final int LOW  = 0x09; // Include tab
    private static final int HIGH = 0x7E;

    public List<StringHit> scan(byte[] data, int offset) {
        List<StringHit> hits = new ArrayList<>();
        int start = -1, len = 0;
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            boolean printable = (b >= LOW && b <= HIGH);
            if (printable) {
                if (start < 0) start = i;
                len++;
            } else {
                if (len >= MIN_RUN)
                    hits.add(new StringHit(offset + start,
                        new String(data, start, len, StandardCharsets.US_ASCII)));
                start = -1; len = 0;
            }
        }
        if (len >= MIN_RUN)
            hits.add(new StringHit(offset + start,
                new String(data, start, len, StandardCharsets.US_ASCII)));
        return hits;
    }
}
```

### 10.2 Protocol Fingerprinting by Magic Bytes

| First Bytes (Hex) | ASCII Hint | Protocol / Format |
|-------------------|-----------|-------------------|
| 45 xx xx xx | E... | IPv4 packet (ver=4, IHL=5 typical) |
| 24 47 50 47 47 41 | $GPGGA | NMEA GPS — GGA sentence (fix data) |
| 24 47 50 52 4D 43 | $GPRMC | NMEA GPS — RMC sentence (position+speed) |
| 22 xx xx xx xx | ".... | LRRP Immediate Location Response |
| 7B 22 | {" | JSON — modern MDT or CAD API |
| 47 45 54 20 2F | GET / | HTTP GET — MDT tunneling HTTP over P25 |
| 50 4F 53 54 20 2F | POST / | HTTP POST — MDT data submission |
| FF FE or FE FF | (BOM) | UTF-16 text — some newer MDT systems |
| xx 00 xx 00 | Alternating NUL | UTF-16LE string — vendor proprietary |

### 10.3 CAD Vendor Format Detection

| Keyword Pattern | Likely Vendor / System | Follow-up Action |
|----------------|----------------------|------------------|
| CAD:, CADID:, INCIDENT: | Generic CAD plaintext | Parse as key:value pairs |
| `<CAD>`, `<DISPATCH>`, XML tags | XML-framed CAD (CentralSquare) | Parse as XML |
| TT=, PR=, AD=, UN= | TriTech / CentralSquare field codes | Map fields per TriTech MDT spec |
| "type":"incident" | REST/JSON CAD (Motorola PremierOne) | Parse as JSON |
| UNIT, DISPO, ETA keywords | Dispatch narrative text | Free-text extract + keyword alert |
| @, followed by talkgroup | Kenwood MDT format | Parse @ as message delimiter |

### 10.4 Recommended Scanning Pipeline Architecture

```
SDRTrunk decoded message stream
         │
         ▼
  ┌─────────────────────────────────────────┐
  │     RawPayloadInterceptor               │
  │  (hooks: PDU, LSD, SDB, vendor TSBK)   │
  └───────────────────┬─────────────────────┘
                      │  byte[] + metadata
                      ▼
  ┌───────────────────────────────────────────────────────┐
  │                PayloadRouter                          │
  │   SAP=0x09/0x0A → LRRPParser                         │
  │   SAP=0x03      → IPv4Parser → TCP/UDP payload        │
  │   SAP=0x05      → SNDCPDecoder → inner packet         │
  │   Unknown SAP   → StringScanner + MagicByteDetector   │
  └───────────┬──────────────┬───────────────┬───────────┘
              │              │               │
              ▼              ▼               ▼
        GPSDisplay     CADDisplay      RawHexDisplay
        (lat/lon/unit) (vendor parsed) (hex+strings)
```

---

## 11. Logging and Corpus Building Strategy

Before writing protocol-specific parsers, building a raw corpus is the most efficient approach. Once you have 24-48 hours of logged raw payloads from a target system, pattern analysis will reveal the vendor format and field structure far more reliably than spec-hunting.

### 11.1 Recommended Log Format

```json
{
  "ts":       "2024-11-15T14:22:31.441Z",
  "system":   "EDACS-Metro",
  "site":     42,
  "tg":       "TG-3421",
  "unit":     "1234567",
  "source":   "PDU",
  "sap":      "0x09",
  "len":      24,
  "hex":      "22 00 12 D6 87 00 33 A1 2F 44 ...",
  "strings":  ["$GPRMC", "CAD:FIRE"],
  "proto":    "LRRP"
}
```

### 11.2 Analysis Workflow

- Group by SAP value — each SAP cluster should show consistent structure
- Sort unknown SAP payloads by first byte — magic byte consistency reveals framing
- Extract all strings across a session and sort by frequency — high-frequency strings are field labels
- Look for repeated byte sequences at fixed offsets — likely fixed header fields
- Compare payloads from same unit across multiple transmissions to identify variable vs fixed fields

### 11.3 Tools for Offline Analysis

| Tool | Use Case |
|------|----------|
| Python + binascii | Quick hex dump and string extraction from JSON log |
| Wireshark (with P25 plugin) | Load IP-bearing PDU payloads as PCAP for protocol decode |
| CyberChef | Visual byte manipulation, magic byte detection, encoding analysis |
| strings (GNU) | Bulk ASCII extraction from binary log files |
| xxd + grep | Fast pattern search across hex dumps |
| Scapy (Python) | Parse extracted IP packets from PDU payloads |

---

## 12. Recommended Display Panel Additions for Your Fork

### 12.1 Raw Payload Panel

- Parallel tab to the existing message tab
- Shows: timestamp, source (PDU/LSD/SDB/TSBK), unit ID, talkgroup, SAP, raw hex, ASCII string hits
- Filter controls: by SAP, by unit, by talkgroup, by string match
- Color-code rows by detected protocol (LRRP = green, IP = blue, unknown = gray)

### 12.2 GPS / Location Panel

- Dedicated panel for LRRP events
- Table: unit ID, timestamp, latitude, longitude, confidence, velocity (if present)
- Option to export as CSV or emit to localhost UDP for mapping integration
- Show last-known location per unit, highlight units not seen in >10 minutes

### 12.3 LSD Accumulator Panel

- Per-call view: call metadata + accumulated LSD bytes displayed as hex + ASCII
- Flag calls where LSD bytes are not all-zero
- Group by talkgroup to spot which channels carry LSD data

### 12.4 String Alert Panel

- User-configurable keyword list (e.g., 'FIRE', 'PRIORITY', 'CODE 3', 'ACTIVE SHOOTER')
- Any string hit matching a keyword triggers an alert row with full context
- Persistent log survives session — searchable

---

## 13. Implementation Priority Order

Based on implementation complexity and expected data yield:

| Priority | Feature | Effort | Expected Yield |
|----------|---------|--------|----------------|
| 1 | Raw PDU payload logging (hex + strings) | Low | Immediately shows what data types are present |
| 2 | LRRP parser from PDU payload (SAP 0x09/0x0A) | Low-Medium | GPS coordinates per radio if system uses LRRP |
| 3 | Talker alias full reassembly (LC 0x17/0x18) | Medium | Complete alias strings — feeds identity database |
| 4 | LSD byte extraction and per-call accumulation | Medium | Secondary data channel on all voice calls |
| 5 | Unknown TSBK raw bit logging with MFID | Low | Corpus for vendor opcode fingerprinting |
| 6 | Motorola Extended Function capture (0x3C) | Low-Medium | Radio inhibit, monitor, regroup events |
| 7 | Whisper audio segment hook + queue | Medium | Identity correlation — feeds alias database |
| 8 | Alias database (SQLite) + AliasResolver | Medium | Unified ID-to-name across all sources |
| 9 | SDB payload content display | Medium | Status message content and telemetry |
| 10 | IP packet parsing from SAP 0x03 PDUs | Medium-High | Full TCP/UDP payload decode |
| 11 | CAD payload correlation to unit IDs | Medium-High | Operational designators — richest name source |
| 12 | Vendor TSBK parsers (MFID-specific) | High | Proprietary data decode — system specific |
| 13 | CAD vendor format parsers | High | Structured CAD message display — system specific |

---

## 14. Codebase Reality Check

> **Added March 2026** — Based on actual source inspection of our fork at commit `5ba924e`. This section corrects claims from the original reference where the codebase is more capable than described.

### 14.1 LSD — Extracted but Not Accumulated (Section 3)

**Original claim:** "LSD bytes are silently discarded."

**Actual state:** `LDUMessage.java` defines `LOW_SPEED_DATA` bit positions and has `getLowSpeedData()` returning hex. The message stub display includes `" VOICE LSD:<hex>"`. LSD is **not** silently discarded — it's extracted and shown per-frame.

**Remaining gap:** No per-call accumulation, no CRC validation, no structured interpretation, no listener emission. The infrastructure to *read* LSD exists; the infrastructure to *use* it does not.

**Bit position correction:** The actual bit positions are `{1392-1399, 1408-1415}` in `LDUMessage.java`, not `320-335` as the original reference stated.

### 14.2 PDU Payloads — Reassembly Exists (Section 4)

**Original claim:** "Payload bytes dropped after structural parsing."

**Actual state:** `PacketMessage.getPayloadMessage()` fully reassembles all data blocks into a contiguous `BinaryMessage`. The method handles both confirmed (16 octets/block via 3/4-rate trellis) and unconfirmed (12 octets/block via 1/2-rate trellis) data blocks. SNDCP offset handling also exists.

**Remaining gap:** No application-layer interpretation. The reassembled bytes are available in code but no LRRP parser, no IP packet parser, no string scanner, and no CAD payload extractor acts on them. The plumbing works; the faucets are missing.

### 14.3 Talker Alias — Extensive Support (Section 8)

**Original claim:** "SDRTrunk partially handles talker alias, commonly truncates."

**Actual state:** SDRTrunk has comprehensive talker alias support:
- Motorola-specific assembler: `MotorolaTalkerAliasHeader` + `MotorolaTalkerAliasDataBlock` → `MotorolaTalkerAliasAssembler` → `MotorolaTalkerAliasComplete`
- L3Harris-specific assembler: `L3HarrisTalkerAliasBlock1-4` → `L3HarrisTalkerAliasAssembler` → `L3HarrisTalkerAliasComplete`
- Phase 2 MAC equivalents for both vendors
- `TalkerAliasManager` in `P25TrafficChannelManager` enriches identifier collections
- `P25TalkerAliasIdentifier` identifier type

**Remaining gap:** Possibly edge cases with truncated multi-block sequences or format handling. Needs live system testing to confirm whether the existing infrastructure covers all real-world scenarios. Priority 3 in implementation may be lower effort than expected.

### 14.4 MAC Factory — Class Name Correction (Section 9)

**Original claim:** References `MacStructureFactory.java`.

**Actual state:** The factory is `MacMessageFactory.java`. Three levels of unknown handling:
1. Unknown MAC PDU type → `UnknownMacMessage` (wraps `UnknownMacStructure`)
2. Unknown vendor opcode → `UnknownVendorMessage`
3. Unmatched opcode → `UnknownMacStructure` (fall-through)

Special logic: L3Harris GPS detection (vendor V170, opcode 0x80) checks for Harris vendor ID and redirects to `L3HARRIS_AA_GPS_LOCATION`.

### 14.5 Vendor TSBKs — Infrastructure Exists (Section 7)

**Original claim:** "Caught as unknown, raw bits not emitted."

**Actual state:** `UnknownVendorOSPMessage` and `UnknownVendorISPMessage` exist in `tsbk/unknown/` package. Motorola-specific TSBK handlers exist for many opcodes. The vendor opcode resolution infrastructure is in place.

**Remaining gap:** Raw bits are not logged to file for corpus building. The message objects exist but their payload content is only available via `toString()` display, not as structured data for offline analysis. Adding a JSON logger for these messages is low effort.

### 14.6 Summary — What Actually Needs Building

Based on source inspection, the **true gaps** for our fork are:

| Area | What Exists | What's Missing |
|------|------------|----------------|
| LSD | Per-frame hex extraction | Per-call accumulation, parsing, listener |
| PDU Payload | Full block reassembly | Application-layer parsers (LRRP, IP, string scan) |
| Talker Alias | Full vendor assemblers | Possibly edge cases — needs live testing |
| Vendor TSBK | Message classes, opcode routing | Raw corpus logging, payload display |
| Phase 2 MAC | Factory, unknown handlers | Raw payload logging for unknowns |
| LRRP | Nothing | Entire parser needed (hooks into PDU payload) |
| String Scanning | Nothing | Entire pipeline needed |
| Corpus Logging | Nothing | JSON logger for all payload types |

The lowest-hanging fruit is a **universal payload logger** that hooks into the existing message stream and writes JSON lines for PDU payloads, vendor TSBKs, and LSD bytes. This requires no new parsing — just serialization of data already available in the message objects.

---

## 15. Unit Identity Resolution — Building the ID-to-Name Map

Radio names and callsigns are not part of the P25 RF protocol in any systematic way. However, multiple over-the-air channels leak identity information that can be correlated over time into a reliable local alias table.

### 15.1 All Identity Sources — Ranked by Reliability

| Source | Data Available | Reliability | Effort |
|--------|---------------|-------------|--------|
| Talker Alias (LC 0x17/0x18) | Alias string from codeplug | High — when present | Low — needs full reassembly |
| CAD Payload Strings | Unit designators, officer names | Very High — operational names | Medium — needs payload capture |
| Voice Transcription (Whisper) | Self-identification, callsign spoken | High — catch-all method | Medium — compute cost |
| TSBK Affiliation (opcode 0x28) | Unit ID to talkgroup mapping | Medium — IDs only, no names | Low — already in SDRTrunk |
| SDB Status Payloads | Unit designator in status string | Medium — system dependent | Medium |
| LRRP Source LLID | Confirms unit ID is GPS-capable | Low — ID confirmation only | Low — from LRRP parser |

### 15.2 Talker Alias — Full Reassembly

```
LC Opcode 0x17 — Talker Alias Header:
  Bits 8-9   : Format  (00=7-bit ASCII, 01=UTF-8, 10=UTF-16)
  Bits 10-14 : Alias length in characters
  Bits 16-55 : First 7 characters (7-bit) or bytes (UTF-8)

LC Opcode 0x18 — Talker Alias Block (up to 3 per call):
  Bits 8-55  : Next 8 characters / bytes of alias
```

> **Note:** SDRTrunk already has Motorola and L3Harris-specific assemblers (see Section 8.2 / 14.3). The standard P25 0x17/0x18 format may differ from vendor implementations.

### 15.3 CAD Payload Correlation

CAD messages transmitted to MDTs are the richest source of operational identities. Correlation logic:

```
Event: PDU data call received
  destination LLID = 1234567  (the radio unit ID)
  payload string contains: 'UNIT: E7' or 'ASSIGNED: MED-3'
  Map: unit_id 1234567 → designator 'E7' / 'MED-3'

Watch for field keywords: TT=, UN=, UNIT:, ASSIGNED:, RESP:
```

### 15.4 Persistent Alias Database Schema

```sql
-- SQLite — simple, file-based, queryable from fork
CREATE TABLE unit_aliases (
    unit_id        INTEGER NOT NULL,
    system_id      TEXT NOT NULL,
    alias          TEXT,
    designator     TEXT,
    whisper_name   TEXT,
    talker_alias   TEXT,
    confidence     TEXT,   -- confirmed | probable | candidate
    source         TEXT,   -- talker_alias | cad | whisper | manual
    first_seen     INTEGER,
    last_seen      INTEGER,
    observe_count  INTEGER DEFAULT 1,
    PRIMARY KEY (unit_id, system_id)
);

CREATE TABLE alias_observations (
    unit_id    INTEGER,
    system_id  TEXT,
    ts         INTEGER,
    source     TEXT,
    raw_value  TEXT,
    call_id    TEXT
);
```

### 15.5 Confidence Scoring

- Weight: explicit self-ID > dispatch addressing > talker alias > phonetic match > badge number
- Require 2+ independent observations before promoting to confirmed
- Flag conflicts — if two transcripts produce different names for same unit ID, hold as unresolved
- Decay old mappings — unit ID not seen in 30+ days may have been reassigned

---

## 16. Motorola-Specific Transmissions on P25 Systems

Motorola SmartZone and ASTRO 25 systems transmit significant proprietary data on top of the standard P25 layer, mostly using MFID 0x90 in the TSBK header.

### 16.1 Motorola MFID 0x90 TSBK Opcodes

| Opcode | Name | Content and Value |
|--------|------|-------------------|
| 0x00 | Group Regroup | SmartZone patch/regroup — links talkgroups across sites |
| 0x01 | Group Regroup Delete | Removes a patch |
| 0x02 | Unit Regroup | Individual radio patched to alternate talkgroup |
| 0x04 | Queued Response | Call queued — reason code and retry timer |
| 0x08 | System Load | Site capacity metrics — active calls and available repeaters |
| 0x0B | Failsoft | System entering failsoft — site going standalone |
| 0x0D | Radio Check | Radio check command from console — targeted unit ID in payload |
| 0x17 | Deny Response | Detailed deny with Motorola-specific reason codes |
| 0x20 | Acknowledge | Extended ACK with Motorola service options |
| 0x28 | Announcement Multi-Site | SmartZone multi-site system parameters |
| 0x34 | Group Affiliation Extended | Extended affiliation with home site info |
| 0x38 | ID Update | Radio ID change notification |
| 0x3C | Extended Function | Wraps sub-functions — see 16.2 |

### 16.2 Extended Function (Opcode 0x3C)

Container for Motorola sub-commands sent from console to specific radios. All transmitted in clear on unencrypted control channels:

| Function Code | Action | Notes |
|--------------|--------|-------|
| 0x0000 | Radio Check | Console verifying radio is active |
| 0x0001 | Radio Inhibit | Radio remotely disabled — lost/stolen response |
| 0x0002 | Radio Uninhibit | Re-enabling previously inhibited radio |
| 0x0003 | Radio Monitor | Console silently monitoring radio audio |
| 0x0006 | Radio Detach | Forcing radio to deregister |
| 0x000B | Dynamic Regroup | Assigning to temporary special ops talkgroup |
| 0x000C | Cancel Dynamic Regroup | Releasing from temporary talkgroup |
| 0x0015 | Emergency Alarm Ack | Dispatcher acknowledging emergency |

> **NOTE:** Radio Inhibit (0x0001) and Radio Monitor (0x0003) are particularly significant operationally. Both the command and target unit ID are transmitted in the clear.

### 16.3 Motorola Voice Channel Service Options

```
Motorola Extended Service Options (in HDU/TDULC):
  Bit 0   : Emergency flag
  Bit 1   : Encrypted flag (may be set even on clear calls)
  Bit 2   : Duplex mode
  Bit 3   : Packet data capable
  Bits 4-5: Priority level (0=lowest, 3=highest/preemptive)
  Bit 6   : Reserved
  Bit 7   : Vocoder override flag
```

### 16.4 Motorola Data Services

| Service | Protocol | What It Carries |
|---------|----------|----------------|
| PremierOne MDT | Proprietary JSON over IP/P25 | Full CAD incidents, unit status, AVL, messaging |
| ASTRO 25 Messaging | Motorola MTP over P25 data | Console-to-radio text messages — often plaintext |
| AVL | LRRP or Motorola variant | GPS updates from vehicle-mounted radios |
| Job Ticketing | PremierOne sub-protocol | Assignment dispatch, status updates |

### 16.5 Console-to-Radio Text Messages

Plaintext messages sent from dispatch console to MDT-equipped radios via P25 data calls:

```
Bytes 0-1   : Message type (0x00 0x01 = standard text)
Bytes 2-3   : Sequence number
Bytes 4-7   : Source address (dispatcher console ID)
Bytes 8-11  : Destination address (radio unit ID)
Bytes 12-13 : Message length
Bytes 14+   : UTF-8 or ASCII message text (plaintext)
```

### 16.6 Combined Motorola Identity Signals

| Signal | Identity Information Revealed |
|--------|------------------------------|
| Talker alias (LC 0x17/0x18) | Codeplug-programmed name or badge number |
| Extended Function target unit ID | Confirms radio is active and console-recognized |
| Radio Inhibit target | Radio reported lost or stolen |
| Dynamic Regroup target | Unit assigned to special operation |
| Home site from registration | Geographic assignment — narrows to precinct |
| PremierOne MDT destination LLID | Confirms unit has MDT |
| Emergency alarm unit ID | High-confidence active officer identification |
| Console text message destination | Confirms unit is dispatch-assigned at that moment |

---

## 17. Whisper Integration Architecture

Voice transcription via Whisper is the most comprehensive fallback for identity resolution — catching self-identification, callsigns, and dispatch addressing.

### 17.1 Audio Routing from SDRTrunk

SDRTrunk's audio output can be intercepted at the module level for per-call segmentation:

```java
// Hook point: P25AudioModule.java
// Each call produces a discrete audio segment.
public class WhisperAudioCapture implements AudioSegmentListener {
    @Override
    public void audioSegmentComplete(AudioSegment segment) {
        CallMetadata meta = new CallMetadata(
            segment.getIdentifierCollection(),
            segment.getTimestamp(),
            segment.getDurationMs()
        );
        // Skip squelch tails — below 800ms has near-zero identity value
        if (segment.getDurationMs() > 800) {
            whisperQueue.offer(new WhisperJob(segment.getAudioBytes(), meta));
        }
    }
}
```

### 17.2 Model Selection

| Whisper Model | Best For | Notes |
|--------------|---------|-------|
| base.en | Radio checks, clear audio, short calls | Fast — adequate for identity extraction |
| small.en | General dispatch, moderate noise | Good balance of speed and accuracy |
| medium.en | Noisy channels, P25 compression artifacts | Worthwhile for high-value talkgroups |
| large-v3 | Maximum accuracy for ambiguous IDs | Reserve for manual review |

### 17.3 Queue Priority Strategy

1. **Priority 1:** First-ever transmission from a new unit ID
2. **Priority 2:** Calls on radio check talkgroups
3. **Priority 3:** Calls from unit IDs with only candidate-level aliases
4. **Priority 4:** All other calls FIFO
5. **Drop/defer:** Calls under 800ms, calls from already-confirmed unit IDs during high load

### 17.4 Name Extraction from Transcripts

```python
import re

PATTERNS = [
    r'(?:this is|i.?m|(?:unit|car|engine|medic)\s*)[A-Z0-9\-]{2,10}',
    r'badge\s*(\d{3,6})',
    r'(?:alpha|bravo|charlie|delta|echo|foxtrot|golf)\s*\d+',
    r'(?:dispatch(?:ing)?\s+to|calling)\s+([A-Z][A-Z0-9\s\-]{2,12})',
]

def extract_candidates(transcript: str, unit_id: int) -> list:
    hits = []
    for pat in PATTERNS:
        for m in re.finditer(pat, transcript, re.IGNORECASE):
            hits.append({
                'unit_id': unit_id,
                'candidate': m.group().strip(),
                'confidence': 'high' if 'this is' in m.group().lower() else 'medium'
            })
    return hits
```

### 17.5 High-Value Call Types for Identity

| Call Type | Why Valuable | What to Listen For |
|-----------|-------------|-------------------|
| Radio checks | Almost always includes unit ID or name | 'Unit 412 radio check' |
| Shift change traffic | Officers IDing themselves | Badge numbers, names |
| Dispatch assignments | Dispatcher addresses unit by callsign | 'Engine 7 respond to...' |
| Emergency activations | Officer states identity under stress | Name and badge |
| Mutual aid introductions | Visiting units always identify agency | Agency + designator |

### 17.6 Fork Integration Components

1. **AudioSegmentListener** — Captures per-call audio with metadata
2. **WhisperJobQueue** — Bounded, priority-weighted, prevents backlog
3. **TranscriptProcessor** — Regex extraction, scores candidates, writes to alias DB
4. **AliasResolver** — Shared service consulted by all display components (priority: confirmed > probable > talker_alias > whisper)
5. **AliasEnrichmentListener** — Subscribes to alias DB updates, retroactively updates unit IDs in message tab

---

## 18. Legal and Operational Note

> **NOTE:** Monitoring unencrypted P25 radio communications is generally lawful in the United States under the Electronic Communications Privacy Act for one-way passive monitoring of non-scrambled public safety communications. However, regulations vary by jurisdiction and use case. This document is a technical reference for software development purposes. Confirm applicable regulations for your specific use before deploying any capture system.

---

## Appendix A — P25 Data Unit ID Quick Reference

| DUID | Name | LSD? | Payload? |
|------|------|------|----------|
| 0x0 | Header Data Unit (HDU) | No | Encryption params for encrypted calls |
| 0x3 | Terminator w/ Link Control (TDULC) | No | Final LC word |
| 0x5 | Logical Data Unit 1 (LDU1) | Yes | Voice + LC word + 2 LSD bytes |
| 0x7 | Trunking Signaling Block (TSBK) | No | Control/signaling opcodes |
| 0x9 | Logical Data Unit 2 (LDU2) | Yes | Voice + Encryption Sync + 2 LSD bytes |
| 0xA | Packet Data Unit (PDU) | No | Data calls — primary payload vehicle |
| 0xB | Multi-Block TSBK (MBT) | No | Extended TSBK with additional payload |
| 0xC | TSBK (alternate) | No | Same as 0x7 |
| 0xF | Terminator w/o LC (TDU) | No | Simple call terminator |

---

## Appendix B — Key SDRTrunk Source Directories

```
io.github.dsheirer.module.decode.p25/
  phase1/
    P25P1MessageDecoder.java          ← Main Phase 1 decode pipeline
    message/
      ldu/LDU1Message.java            ← LSD bytes here (via LDUMessage parent)
      ldu/LDU2Message.java            ← LSD bytes here (via LDUMessage parent)
      ldu/LDUMessage.java             ← getLowSpeedData(), LOW_SPEED_DATA positions
      pdu/PDUSequence.java            ← PDU header + block collection
      pdu/packet/PacketMessage.java   ← getPayloadMessage() — full reassembly
      pdu/block/                      ← Data block payload bytes
      tsbk/                           ← All TSBK message classes
      tsbk/unknown/                   ← UnknownVendorOSPMessage, UnknownVendorISPMessage
      lc/                             ← Link control word classes
      lc/motorola/                    ← Motorola talker alias assembler
      lc/l3harris/                    ← L3Harris talker alias assembler
  phase2/
    P25P2MessageDecoder.java          ← Phase 2 decode pipeline
    message/mac/                      ← MAC layer messages
    message/mac/MacMessageFactory.java ← Hook here for all MAC types
  audio/
    P25AudioModule.java               ← Voice processing — audio segment output
  P25TrafficChannelManager.java       ← TalkerAliasManager lives here
```
