# DATCH Motorola Protocol Analysis — Deep Findings

## Date: 2026-03-25
## Source: Clay County P25 Phase 2 TDMA data channel captures
## Tool: `tools/datch_motorola_protocol.py`

---

## 1. Capture Overview

4 sessions captured on Clay County P25 Phase 2 data channels:

| Session | Frequency   | Slots | Duration |
|---------|-------------|-------|----------|
| 1       | 857.2125 MHz | 2789  | 100.4s   |
| 2       | 856.4375 MHz | 2773  | 100.2s   |
| 3       | 857.4375 MHz | 2767  | 100.3s   |
| 4       | 857.2125 MHz | 2378  | 85.5s    |

Each timeslot = 40 raw bytes captured after TDMA deinterleaving.

---

## 2. Three Idle Families Identified

The DATCH channel carries three distinct idle patterns simultaneously:

| Family     | Sig prefix | Count | Description |
|------------|-----------|-------|-------------|
| **EC idle** | `ec2777...` | 1343  | Primary system idle — Phase 2 control |
| **B6 idle** | `b68436...` | 852   | Secondary idle — channel-specific |
| **20 idle** | `201678...` | 188   | Tertiary idle — less frequent |

### EC Idle Canonical (40 bytes):
```
0e ec 27 77 8c db e1 2d f8 c6 ff 0d 6f c3 5f f0 4d fc 63 fe 09 bf 06 ff 90 37 cc 37 e7 02 f8 f0 5f 78 cf bc 35 ee 8b f5
```

**Key insight**: The three idle families interleave on the channel. The EC idle
is the dominant pattern and matches the P25 Phase 2 TDMA Data Channel (DATCH)
specification for idle fill. B6 and 20 families appear to be Motorola-proprietary
extensions — possibly system status broadcasts or channel maintenance.

---

## 3. Byte[0] Header Structure — CONFIRMED

The first byte of every 40-byte timeslot has a consistent structure across ALL
families, both idle and data:

```
Byte[0] = [FT:2][LOWER:6]
           bits[7:6] = Frame counter / type (cycles: 0 -> 2 -> 3, skips 1)
           bits[5:0] = Message opcode / content type
```

### Evidence:
- **Same family always has same lower 6 bits**, regardless of bits[7:6]
- Example: Family `2017` uses byte[0] values `0x1C` (ft=0), `0x9C` (ft=2), `0xDC` (ft=3) — all with lower6=0x1C
- Example: Family `1037` uses byte[0] values `0x1D` (ft=0), `0x9D` (ft=2), `0xDD` (ft=3) — all with lower6=0x1D
- **Frame type 1 (01) is almost never seen** — only 2 singleton slots

### Opcode Catalog (lower 6 bits):

| Opcode | Hex  | Families | Slots | Likely Function |
|--------|------|----------|-------|-----------------|
| 0x00   | 00   | b694, 4940 | 88 | Idle variant / status broadcast |
| 0x02   | 02   | b684, b680 | 11 | Channel status / heartbeat |
| 0x08   | 08   | 0035     | 18  | System parameter broadcast |
| 0x0E   | 0E   | ec27, ec17, ec37, ec87, ... | 34 | EC-family idle/data |
| 0x13   | 13   | 8014     | 4   | Data channel type A |
| 0x15   | 15   | b005, a025, c015 | 14 | Data channel type B |
| 0x17   | 17   | 8035, 9035, f005, 8005, 9005, e015, 47d0 | 47 | Data channel type C (most common) |
| 0x1C   | 1C   | 2017, ec15, 2002 | 62 | Data channel type D |
| 0x1D   | 1D   | 1037, 1036 | 98 | Data channel type E (most common) |
| 0x1E   | 1E   | dfd2, 6017 | 2  | Rare data type |
| 0x25   | 25   | a851     | 4   | Encrypted/scrambled data |
| 0x28   | 28   | 1a34     | 4   | Encrypted/scrambled data |
| 0x2A   | 2A   | b724     | 2   | Encrypted/scrambled data |
| 0x34   | 34   | 15bf, d5bf, 21b6 | 8 | Encrypted/scrambled data |

---

## 4. Data Family Structure

### 4.1 The "xx35" and "xx05" Families — Motorola Data Channel

Families sharing opcode **0x17** (8035, 9035, b005, 9005, 8005, f005, e015)
all have **strikingly similar payload structures**:

```
Example 8035: 80 35 14 00 1a 02 49 00 c4 08 4d 03 4d 00 54 10 30 6c 09 46 00 65 00 1c 80 45 58 1c 00 d7 ...
Example 9035: 90 35 14 00 1a 01 49 c0 c4 00 4e 02 4d 00 94 60 00 78 09 40 03 65 80 9c a0 45 58 18 80 17 ...
Example f005: f0 05 18 08 1d 00 48 80 c4 08 4d 03 4d 00 14 10 30 6c 01 44 03 64 00 5c c0 45 50 18 80 17 ...
Example 8005: 80 05 10 00 1a 00 49 00 c8 0c 4e 03 4d 80 94 00 20 60 09 44 03 65 00 dc a0 15 58 0c 81 17 ...
```

**Common features:**
- Fixed positions 0,1 = family ID (byte[1] always xx05 or xx35)
- Position 6 always 0x41-0x49 range
- Position 12 always 0x4D
- Position 25 always 0x45 or 0x15 range
- These are **structured data records** — likely Motorola system status/registration data

### 4.2 The "2017" Family — Most Common Data Payload

Family `2017` (59 slots, opcode 0x1C) has a nearly-constant payload:
```
20 17 68 0c 07 01 57 40 40 09 49 01 7b c0 cb 60 24 e8 0d c4 02 57 80 59 80 20 80 01 80 8e 80 34 28 15 fa 07
```
Only positions [13, 31, 32] change. This is a **periodic beacon/status message**.

### 4.3 The "1037" Family — Second Most Common

Family `1037` (63 slots, opcode 0x1D) also has a mostly-constant payload:
```
10 37 6c 04 15 01 57 80 80 0d 7a 03 77 80 cb 40 04 28 01 e4 02 53 00 1a 80 00 88 01 80 0e a0 24 38 19 dc 06
```
Changes at positions [2,3,10,11,12,23,24,25,32,33,34]. More dynamic than 2017.

### 4.4 The "b694" Family — Steady-State Idle

Family `b694` (86 slots, opcode 0x00) is almost perfectly static:
```
b6 94 b2 14 fb 55 56 d6 d0 48 0a 81 1e b0 8d 0a b2 10 ad 76 1a 65 5e 0c 15 d1 03 90 f6 c6 ad 41 9c bf 0e db
```
Only positions [8,10,11,12,13,17,18,19,27,33] vary. This is a **system heartbeat**.

### 4.5 Encrypted/Scrambled Families

Families with opcodes 0x25, 0x28, 0x2A, 0x34 show high-entropy payloads:
- `a851` (0x25): scrambled, appears in bursts of 4 slots
- `1a34` (0x28): fully static, repeating — encrypted status beacon
- `15bf` (0x34): nearly static, only 1 byte varies — encrypted beacon
- `b724` (0x2A): fully static, high entropy — encrypted data

These appear periodically (every ~100 slots) and are likely Motorola **OTAR
(Over-The-Air Rekeying)** key management or encrypted status messages.

---

## 5. EC-Variant XOR Analysis — Data Encoding

### How data is encoded in the EC family

Data frames that are "close to" EC idle (byte[1] in 0xEC-0xEF range) carry
user data as **bit differences from the EC idle canonical**. XORing reveals
the encoded information.

### Burst 2 (14 EC-variant slots) — Largest data transfer observed:

Signature progression shows systematic byte[1-2] changes:
```
ec15 -> ec17 -> ec57 -> ecb7 -> ec87 -> ec37 -> ec87 -> ec37 -> ec17 -> ed27 -> ec27 -> ed37 -> ee07 -> ee27
```

**Key observation**: Byte[2] of the signature encodes a **running counter/accumulator**.
The XOR of byte[2] against idle (0x27) produces values that accumulate:
`0x32, 0x30, 0x70, 0x90, 0xa0, 0x10, 0xa0, 0x10, 0x30, 0x00, 0x00, 0x10, 0x20, 0x00`

This is consistent with a **CRC or running checksum** in the signature region.

### EC-variant data bit distribution:

The XOR data concentrates on even-numbered byte positions in the stripped payload:
positions [1,3,4,6,8,9,10,12,14,16,18,19,20,21,23,25,27,29,31,32,33,35]

This pattern suggests **nibble-aligned protocol data** with specific bit positions
allocated to different fields.

### Single-slot EC variants (low bit-diff)

Bursts 3-19 are single EC-variant slots with only 1-18 bits different from idle.
These are likely **bit errors from the channel** rather than real data — the DATCH
channel has enough noise to flip a few bits in the idle pattern occasionally.

---

## 6. Burst Reassembly — Protocol Signature Detection

### Key burst types identified:

| Burst Type | Families | Duration | Protocol Signatures Found |
|------------|----------|----------|--------------------------|
| **Beacon burst** | 2017 (repeat) | 1-6s | SNDCP, IPv6, OTAR_rekey at consistent offsets |
| **Data transfer** | EC variants + b005/a025 | 1-2s | SNDCP, IPv4, IPv6, OTAR, ARS, TMS |
| **Channel assignment** | xx35/xx05 families | 0.1-0.5s | SNDCP_activate, IPv4, IPv6 |
| **Encrypted** | a851/1a34/15bf/b724 | 0-0.1s | ARS_register, ARS_deregister |
| **Status/heartbeat** | b694, 0035 | 10s+ | SNDCP, IPv4_tos0, IPv6, OTAR, ARS, TMS |

### SNDCP Signatures at Regular Offsets

The `sndcp_activate` (0xC0) pattern appears at offset 13 within the `2017` family
payload every time, and at regular +36 intervals in multi-slot bursts. This means:

**Byte 13 of the 36-byte stripped payload = 0xC0 (SNDCP activate)**

This is position 14 in the raw 40-byte timeslot (after adjusting for byte[0] header
and byte[9] framing). The `otar_rekey` (0x0C) at offset 3 is similarly consistent.

### Interpretation

The regular spacing of protocol signatures (+36 per slot) confirms that:
1. Each 40-byte timeslot carries exactly **36 bytes of protocol payload** (bytes 1-8
   as header/signature, bytes 10-29 and 31-38 as payload, bytes 0/9/30/39 as framing)
2. The protocol searches are finding the **same byte values** repeated in each slot's
   payload region, not a PDU reassembled across slots
3. The "SNDCP" and "OTAR" detections are **false positives from single-byte matching** —
   these are just common byte values (0xC0, 0x0C, etc.) appearing at fixed positions

---

## 7. Revised Understanding — What DATCH Data Really Carries

Based on this deep analysis, the DATCH timeslot structure is:

```
Byte  0:    Header [FT:2][Opcode:6]
Bytes 1-2:  Family signature (channel/content identifier)
Bytes 3-8:  Extended signature (content hash / CRC / parameters)
Byte  9:    Framing marker
Bytes 10-29: Payload block 1 (20 bytes)
Byte  30:   Framing marker
Bytes 31-38: Payload block 2 (8 bytes)
Byte  39:   Framing marker
```

Total payload = 28 bytes per timeslot (after removing framing and headers).

### Data types observed:

1. **System beacons** (opcodes 0x00, 0x02, 0x08, 0x0E): Periodic broadcasts of
   system parameters, channel status, and idle fill. High repetition, low variation.

2. **Registration/status data** (opcodes 0x15, 0x17, 0x1C, 0x1D): Motorola-proprietary
   data payloads carrying subscriber registration, location updates, and system status.
   These have similar structure with family-specific parameter fields.

3. **Encrypted data** (opcodes 0x25, 0x28, 0x2A, 0x34): High-entropy payloads that
   are likely OTAR key management or encrypted subscriber data.

4. **EC-variant data** (opcode 0x0E with non-idle signatures): Actual user data
   encoded as XOR differences from EC idle. Rare (33 out of 2789 slots = 1.2%).

---

## 8. Cross-Family Relationships

### Payload sharing between families

Several families with **different byte[0-1] but identical bytes[2-35]** exist:

- `2017`, `6017`, `2002` all share the same payload body (opcode 0x1C/0x1E)
- `b684`, `b680`, `b704`, `1204` share the same B6-family payload body
- `e427`, `e527`, `5ae7`, `e827`, `fc27` are EC idle with different byte[0-1]

This confirms **byte[0] carries the frame counter** and **byte[1] high nibble**
may carry priority/source information, while the rest is the actual data.

### The "B6" family sub-types

The B6 idle family has several variants:
- `b684` (10 slots) — appears as single-slot heartbeats scattered through session
- `b694` (86 slots) — bulk idle at end of session (steady-state)
- `b680`, `b704`, `1204` — rare variants (1 slot each)

The varying byte[1] values (b6**84**, b6**94**, b6**80**, b7**04**, 12**04**)
while sharing the same payload body suggests **byte[1] encodes a sequence
number or channel state** within the B6 family.

---

## 9. Motorola Protocol Architecture Summary

```
+------------------------------------------------------------------+
|                    DATCH 40-Byte Timeslot                        |
+------------------------------------------------------------------+
| Byte 0 |  Header: [Frame Counter : 2] [Opcode : 6]              |
| Byte 1 |  Family High: [Priority?:4] [Content Type High:4]      |
| Byte 2 |  Family Low:  [Content Type Low:4] [Sub-type:4]        |
| Byte 3-8| Extended params / content hash / CRC                   |
| Byte 9  | Framing (always same as canonical[9])                  |
| Byte 10-29| Payload Block 1 (20 bytes)                           |
| Byte 30 | Framing (always same as canonical[30])                 |
| Byte 31-38| Payload Block 2 (8 bytes)                            |
| Byte 39 | Framing (always same as canonical[39])                 |
+------------------------------------------------------------------+
```

### Opcode categories:

```
0x00-0x0F: System/idle (beacons, heartbeats, EC idle)
0x10-0x1F: Data channel messages (registration, status, location)
0x20-0x3F: Special/encrypted data (OTAR, encrypted beacons)
```

---

## 10. Next Steps

### For decoding:
1. **Parse bytes[1-2] as content type** — build a lookup table mapping each
   2-byte family signature to its message type and content structure
2. **Decode the "xx05/xx35" data families** — these are the most information-rich,
   with structured parameter fields at consistent positions
3. **Track byte[0] frame counter** — use bits[7:6] to detect retransmissions
   and frame boundaries
4. **Correlate with control channel** — compare DATCH data families with
   corresponding SACCH/FACCH control channel messages to determine what
   subscriber actions trigger each data family

### For SDRTrunk integration:
1. **DatchTimeslot parser** should extract: opcode (byte[0] lower6), family
   signature (bytes[1-2]), and payload (bytes 3-8, 10-29, 31-38)
2. **Idle detection** can use the three known idle signatures (EC, B6, 20)
3. **Message factory** should route based on opcode to family-specific decoders
4. **EC-variant data extraction** via XOR with EC idle canonical gives the
   actual user data for opcode 0x0E frames
