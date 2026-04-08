# Design 023: Phase 2 TDMA Data Channel Decoding

**Date:** 2026-03-25
**Branch:** plutosdr
**Status:** Design — Not Yet Implemented

## Problem Statement

We observe long TDMA Phase 2 data sessions (14-15+ seconds) on traffic channels, visible in the Events tab as "TDMA PHASE 2 DATA CHANNEL ACTIVE" with durations up to 15.3 seconds. Despite being active for extended periods, **zero data is captured from these sessions**. The Now Playing panel shows them correctly as DATA state on TS:1/TS:2, but the Data tab shows nothing from these channels.

The root cause: SDRTrunk can detect, allocate, and display Phase 2 TDMA data channels, but the `DatchTimeslot` class that processes these timeslots does **nothing** with the payload — it just dumps raw hex. No FEC decoding, no PDU assembly, no IP packet extraction occurs.

Meanwhile, FDMA data channels (channel `0-1117` at 857.98750 MHz) work great — LRRP, SNDCP, ARS packets are fully decoded and captured every 2-5 seconds.

## What Exists Today

### Detection & Allocation (Working)
1. **Control channel TSBK detection**: `MotorolaExplicitTDMADataChannelAnnouncement` (opcode 0x8B) announces active TDMA data channels
2. **Traffic channel allocation**: `P25TrafficChannelManager.processP2DataChannel()` allocates a Phase 2 traffic channel
3. **DUID recognition**: `DataUnitID.SCRAMBLED_DATCH` (value 10, parity 0xA3) is identified as "Motorola APX-Next TDMA Data Channel"
4. **Timeslot creation**: `TimeslotFactory` creates `DatchTimeslot` when SCRAMBLED_DATCH is detected
5. **State tracking**: `P25P2DecoderState` sets `State.DATA` when DatchTimeslot arrives

### DatchTimeslot (Stub — No Payload Processing)
```
DatchTimeslot extends Timeslot
├── Descrambles 320-bit message via XOR with scrambling sequence
├── toString() → raw hex dump: "DATCH-SC TDMA DATA TIMESLOT MSG:..."
├── getIdentifiers() → empty list (no radio IDs, talkgroups, encryption info extracted)
└── No FEC decoding, no payload parsing, no MAC message extraction
```

### Message Pipeline Flow
```
Dibits → P25P2MessageFramer → P25P2SuperFrameDetector
    → SuperFrameFragment → TimeslotFactory → DatchTimeslot
        → P25P2MessageProcessor.receive()
            → (just forwards raw timeslot to mMessageListener)
                → P25P2DecoderState.receive()
                    → broadcasts State.DATA (and nothing else)
                        → P25DataCaptureModule receives nothing useful
```

### Key Difference: Voice Timeslots vs DATCH

**Voice (FACCH/SACCH/LCCH)** timeslots extend `AbstractSignalingTimeslot` which:
- Applies FEC (Viterbi + trellis decoding)
- Extracts 180-bit MAC PDUs from the 320-bit timeslot
- Parses MAC messages (opcodes, identifiers, payloads)
- Provides identifiers (FROM/TO radio, talkgroup, encryption key)

**DATCH** timeslots extend `Timeslot` directly:
- Only descrambles the 320-bit payload
- Does not apply any FEC
- Does not extract any structured data
- Returns empty identifiers

## Architecture of the Phase 2 Super-Frame

Understanding the super-frame is critical for DATCH decoding:

```
Super-Frame = 12 timeslots across 3 fragments (720 dibits each)
├── Fragment 1: I-ISCH + TS_A(ch1) + I-ISCH + TS_B(ch2) + S-ISCH + TS_C(ch1) + S-ISCH + TS_D(ch2)
├── Fragment 2: I-ISCH + TS_A(ch1) + I-ISCH + TS_B(ch2) + S-ISCH + TS_C(ch1) + S-ISCH + TS_D(ch2)
└── Fragment 3: I-ISCH + TS_A(ch1) + I-ISCH + TS_B(ch2) + S-ISCH + TS_C(ch2) + S-ISCH + TS_D(ch1)

Each timeslot = 320 bits (160 dibits)
4 ultra-frames = 4 super-frames = 48 timeslots per ultra-frame
```

When a TDMA data channel is active:
- One or both timeslots carry `SCRAMBLED_DATCH` DUIDs instead of voice DUIDs
- Multiple consecutive DATCH timeslots form a data transfer session
- A 15-second session ≈ 15s × (4800 dibits/s / 720 dibits/fragment × 4 timeslots/fragment) ≈ ~400 DATCH timeslots
- At 320 bits per timeslot, that's ~16,000 bytes of raw data that we're currently **throwing away**

## What We Know About DATCH Payload Structure

From the existing code comments in `DatchTimeslot.java`:
- "Doesn't appear to be encrypted"
- "Doesn't seem to be RS encoded"
- Repeating patterns in first 4 bits observed
- After descrambling, the 320-bit payload is available but unstructured

### Suspected Structure (Based on P25 Phase 2 Standards)
The DATCH timeslot likely carries:
1. **Header bits** (4-8 bits): Timeslot type indicator, sequence number
2. **PDU Fragment** (~288-304 bits): A fragment of a larger PDU sequence
3. **CRC/FEC** (~12-28 bits): Error detection/correction

The PDU fragments would reassemble into:
- **SNDCP PDUs** carrying IP packets (same as Phase 1 PDU but different framing)
- **MAC signaling** for data session management

### What We Need to Discover
1. Whether DATCH uses the same trellis/Viterbi FEC as FACCH timeslots
2. The exact header format (sequence number, fragment indicator, last-fragment flag)
3. How multiple DATCH timeslots reassemble into a complete PDU
4. Whether there's a separate control/signaling interleave within the data stream

## Implementation Plan

### Phase 1: Raw DATCH Capture & Analysis (Low Risk)

**Goal:** Capture raw descrambled DATCH timeslot data to files for offline analysis.

**Changes:**
1. **`DatchTimeslot.java`** — Add method to return raw descrambled bits as byte array
2. **`P25P2DecoderState.java`** — Forward DatchTimeslot to P25DataCaptureModule with raw hex
3. **`P25DataCaptureModule.java`** — Add handler for DatchTimeslot that emits raw payload to JSONL

**Expected output:** JSONL records with type "DATCH_RAW", 40 bytes of hex per timeslot, with timestamps. This lets us analyze patterns offline.

**Analysis script:** New `tools/datch_analysis.py` to:
- Correlate DATCH timeslots by channel/timeslot/timestamp
- Look for header patterns, sequence numbers, fragment boundaries
- Attempt FEC detection (try Viterbi, trellis, Reed-Solomon decodings)
- Look for IP packet signatures (0x45 for IPv4 header)

### Phase 2: FEC & Fragment Detection (Research)

**Goal:** Determine the correct FEC and framing for DATCH timeslots.

**Approach:**
1. Collect a corpus of raw DATCH data from known data sessions
2. Try standard P25 FEC methods:
   - **1/2-rate trellis coding** (same as FACCH) on the 320-bit payload
   - **3/4-rate trellis coding** (used for Phase 2 voice)
   - **No FEC** (just CRC at the end)
3. Look for well-known byte patterns after each decoding attempt:
   - IPv4 header (0x45)
   - SNDCP headers
   - Sequence numbers incrementing across timeslots
4. Check if the Motorola-proprietary DATCH format is documented in any ASTRO 25 technical references

### Phase 3: PDU Assembly Pipeline (Implementation)

**Goal:** Build a complete pipeline from DATCH timeslots to IP packets.

**New classes needed:**

```
DatchTimeslot (existing, enhanced)
├── Apply discovered FEC decoding
├── Extract header: sequence number, fragment flag, last-fragment flag
└── Return decoded payload fragment

DatchPDUAssembler (new)
├── Collects fragments by sequence
├── Detects fragment boundaries (first/continuation/last)
├── Assembles complete PDU from fragments
├── Handles out-of-order and missing fragments
└── Emits complete PDUs to listener

DatchPDUMessage (new)
├── Wraps a complete reassembled PDU
├── Determines PDU type (SNDCP, IP packet, control)
├── Provides identifiers (LLID, NSAPI from SNDCP header)
└── Feeds into existing PacketMessageFactory for IP parsing
```

**Modified classes:**

| Class | Change |
|-------|--------|
| `P25P2MessageProcessor` | Route DatchTimeslot to DatchPDUAssembler instead of raw forwarding |
| `P25P2DecoderState` | Process reassembled DatchPDUMessages like Phase 1 PDU/PacketMessages |
| `P25DataCaptureModule` | Capture DatchPDU decoded payloads |

### Phase 4: Integration with Existing IP Stack

Once DATCH PDUs are reassembled, they should contain the same SNDCP/IP payloads as Phase 1 PDUs. The existing IP parsing infrastructure handles the rest:

```
DatchPDUMessage
  → PacketMessageFactory.create()
    → SNDCPPacketMessage / PacketMessage
      → IPV4Packet → UDPPacket → LRRPPacket / ARSPacket / XCMPPacket
```

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| DATCH format is fully proprietary/undocumented | Medium | High | Phase 1 raw capture lets us reverse-engineer; patterns should be detectable |
| FEC method is non-standard | Low-Medium | Medium | Try all known P25 FEC methods; brute-force approach |
| DATCH uses encryption we can't detect | Low | High | Code comments say "doesn't appear to be encrypted"; verify with raw captures |
| Multi-timeslot reassembly is complex | Medium | Medium | Start simple: try treating each timeslot as independent PDU, then add reassembly |
| Motorola changes DATCH format across firmware | Low | Low | Our captures are from a specific system; build for that first |

## Priority & Effort Estimate

| Phase | Effort | Value | Priority |
|-------|--------|-------|----------|
| Phase 1: Raw Capture | 2-3 hours | Medium — enables all analysis | **Do First** |
| Phase 2: FEC Research | 4-8 hours | High — determines feasibility | **Do Second** |
| Phase 3: PDU Assembly | 8-16 hours | Very High — unlocks all TDMA data | Do after Phase 2 proves feasible |
| Phase 4: IP Integration | 2-4 hours | High — leverages existing code | Do after Phase 3 |

## Quick Win: Phase 1 Implementation Sketch

### DatchTimeslot Enhancement
```java
// In DatchTimeslot.java
public byte[] getDescrambledPayload() {
    return getMessage().toByteArray(); // 320 bits = 40 bytes
}
```

### P25P2DecoderState Enhancement
```java
// In receive(IMessage) when message instanceof DatchTimeslot
case SCRAMBLED_DATCH:
    processDatchTimeslot((DatchTimeslot) message);
    break;

private void processDatchTimeslot(DatchTimeslot datch) {
    broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.DATA));
    // Forward to data capture module via standard message pipeline
    // The existing P25DataCaptureModule will pick it up
}
```

### P25DataCaptureModule Enhancement
```java
// Add handler for DatchTimeslot
if (message instanceof DatchTimeslot datch) {
    CapturedPayload payload = CapturedPayload.builder()
        .type("DATCH_RAW")
        .messageClass("DatchTimeslot")
        .channel(mChannelDescriptor)
        .frequency(mChannelFrequency)
        .mode("TDMA")
        .payloadHex(datch.getMessage().toHexString())
        .payloadLength(40) // 320 bits = 40 bytes
        .details(datch.toString())
        .build();
    emit(payload);
}
```

## Relationship to Other Work

- **Change 022** (Extended PDU Blocks): Fixes Phase 1 PDU assembly for large packets. Orthogonal to this work.
- **Change 021** (IP Reconstruct): The analysis tooling will benefit from DATCH captures once they're decoded.
- **Change 017** (Data Capture Improvements): The capture infrastructure is already in place; we just need to feed it DATCH data.

## References

- P25 TIA-102.BBAC: FDMA Data Overview
- P25 TIA-102.BBAD: Phase 2 TDMA Overview
- Motorola ASTRO 25 Technical Reference (proprietary — may document DATCH format)
- Existing SDRTrunk code: `DatchTimeslot.java`, `TimeslotFactory.java`, `P25P2SuperFrameDetector.java`
