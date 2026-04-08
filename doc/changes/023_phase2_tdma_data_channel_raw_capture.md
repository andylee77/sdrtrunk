# Change 023: Phase 2 TDMA Data Channel (DATCH) Raw Capture

**Date:** 2026-03-25
**Branch:** plutosdr
**Status:** Implemented (Phase 1 of design 023)

## Summary

Added raw capture of Motorola TDMA Data Channel (DATCH) timeslot payloads. Previously,
Phase 2 TDMA data sessions (14-15+ second sessions visible as "TDMA PHASE 2 DATA CHANNEL
ACTIVE" in the Events tab) produced zero data output despite being fully detected and
allocated. The `DatchTimeslot` class descrambled the 320-bit payload but did nothing with
it — just dumped raw hex in `toString()`.

This change captures every DATCH timeslot's raw descrambled payload (40 bytes each) to
the JSONL corpus log and Data tab, enabling offline analysis to reverse-engineer the FEC
encoding, framing, and reassembly protocol.

## Problem

- TDMA Phase 2 data channels are correctly detected, allocated, and displayed
- `DatchTimeslot` descrambles the payload but discards it (no FEC, no PDU assembly)
- ~16,000 bytes per 15-second session was being thrown away
- Data tab showed nothing from these channels
- No way to analyze the raw data for pattern discovery

## Changes

### Modified Files

| File | Change |
|------|--------|
| `DatchTimeslot.java` | Added `getDescrambledPayload()` (returns 40-byte array) and `getDescrambledPayloadHex()` methods; added `PAYLOAD_BITS`/`PAYLOAD_BYTES` constants |
| `P25DataCaptureModule.java` | Added `DatchTimeslot` import; added `processDatchTimeslot()` handler; added dispatch in `processMessage()` for `DatchTimeslot` instances |
| `CapturedPayload.java` | Added `DATCH_RAW("DATCH")` to `PayloadType` enum |

### New Files

| File | Purpose |
|------|---------|
| `tools/datch_analysis.py` | Offline analysis tool for DATCH corpus data |
| `doc/changes/023_phase2_tdma_data_channel_raw_capture.md` | This document |

## Implementation Details

### DatchTimeslot Enhancement

```java
public byte[] getDescrambledPayload() {
    return getMessage().getBytes(); // 320 bits = 40 bytes, already descrambled
}
```

The superclass `Timeslot` constructor already descrambles via `getMessage().xor(scramblingSequence)`,
so `getMessage()` returns the descrambled content. The new method just exposes it as a byte array.

### P25DataCaptureModule Handler

```java
private void processDatchTimeslot(DatchTimeslot datch) {
    byte[] payload = datch.getDescrambledPayload();
    // ... builds CapturedPayload with type DATCH_RAW, includes:
    //   - Full 40-byte hex dump
    //   - Timeslot number in SAP field (DATCH:TS1 or DATCH:TS2)
    //   - Channel frequency and descriptor
    //   - Protocol detection (scans for IPv4 header, etc.)
    //   - ASCII string scanning
}
```

DatchTimeslot messages already flow through the message listener chain (they reach
`P25P2DecoderState.receive()`), so `P25DataCaptureModule` receives them automatically.
The only change needed was adding the dispatch case and handler method.

### Message Flow

```
Dibits → P25P2MessageFramer → P25P2SuperFrameDetector
    → SuperFrameFragment → TimeslotFactory → DatchTimeslot
        → Message listener chain
            → P25P2DecoderState.receive() → broadcasts State.DATA (unchanged)
            → P25DataCaptureModule.processMessage() → processDatchTimeslot() [NEW]
                → CapturedPayload(DATCH_RAW) → emit() → JSONL log + Data tab
```

### JSONL Output Format

Each DATCH timeslot produces a JSONL record:
```json
{
  "ts": 1711404900123,
  "type": "DATCH_RAW",
  "class": "DatchTimeslot",
  "sap": "DATCH:TS1",
  "from": "",
  "to": "",
  "channel": "0-1029",
  "freq": 851012500,
  "len": 40,
  "hex": "A3 B7 4F 22 ...",
  "proto": "UNKNOWN",
  "mode": "TDMA",
  "details": "TS1 DATCH-SC TDMA DATA TIMESLOT MSG:A3B74F22..."
}
```

### Analysis Tool

`tools/datch_analysis.py` reads JSONL corpus files and provides:
- **Summary**: Total timeslots, bytes, frequency/channel distribution, time range
- **Sessions**: Groups timeslots into sessions by time proximity (2s gap threshold)
- **Headers**: First byte/nibble/2-bit pattern analysis, sequence detection
- **FEC**: IPv4 header scanning, entropy analysis, duplicate pattern detection
- **Dump**: Raw hex dump of first N records for manual inspection

Usage:
```bash
python tools/datch_analysis.py logs/p25_data_system_20260325.jsonl --all
```

## Expected Data Volume

- 15-second data session ≈ ~400 DATCH timeslots ≈ 16,000 bytes raw data
- Each JSONL record ≈ ~200 bytes (hex + metadata)
- Typical session produces ~80KB in JSONL log
- Multiple sessions per hour on active systems

## Risk Assessment

- **Zero risk to existing functionality**: No changes to `P25P2DecoderState`, voice
  processing, MAC message handling, or any other decoder path
- **Minimal performance impact**: DatchTimeslot messages were already being created and
  processed; we just extract the byte array and write a log record
- **JSONL growth**: Active systems may produce significant DATCH log volume; existing
  date-rotation handles this

## Next Steps (Design 023 Phase 2+)

1. Collect corpus of DATCH captures from live system
2. Run `datch_analysis.py --all` to identify header patterns and FEC encoding
3. Try standard P25 FEC methods (trellis, Viterbi) on raw data
4. Look for IPv4/SNDCP signatures after FEC decoding
5. Build PDU reassembly pipeline once framing is understood
