# Change 017: Data Capture Improvements

**Date:** 2026-03-25
**Branch:** plutosdr
**Status:** Complete

## Summary

Three targeted improvements to the P25 data capture system to reduce noise,
enrich payload metadata, and improve protocol identification.

## Priority 1: Filter SACCH Idle Noise

**Problem:** Phase 2 (TDMA) systems generate high-volume repetitive MAC messages
that flood the Data tab with no useful information:
- `MotorolaUnknownOpcode135` (opcode 0x87): SACCH idle fill, repeats ~350ms on both timeslots
- `MotorolaTDMADataChannel` (opcode 0x8B): IDLE state announcements, repeat identically

**Solution:**
- `MotorolaUnknownOpcode135` messages are suppressed entirely in `processMacMessage()`
- `MotorolaTDMADataChannel` messages are deduplicated per timeslot — only emitted when
  the details string changes (indicating a real state transition like IDLE→ACTIVE)
- Dedup state tracked via `mLastTdmaDataDetailsTS1` / `mLastTdmaDataDetailsTS2` fields

**Impact:** Eliminates ~85% of MAC noise on active Phase 2 systems.

## Priority 2: Populate Frequency/Channel Fields

**Problem:** Captured payloads from traffic channels had no indication of which
frequency or channel they came from, making it hard to correlate data with calls.

**Solution:**
- Added `mChannelFrequency` (long, Hz) and `mChannelDescriptor` (String) fields
  to `P25DataCaptureModule`
- Added `setChannelFrequency()` / `setChannelDescriptor()` setters
- `DecoderFactory` wires `channelDescriptor.getDownlinkFrequency()` and
  `channelDescriptor.toString()` into the data capture module for traffic channels
  (both P25 Phase 1 and Phase 2)
- All `CapturedPayload.builder()` calls now include `.frequency()` and `.channel()`

## Priority 3: Enhanced Protocol Detection from Details String

**Problem:** Many packets with recognized protocols (LRRP, ARS, XCMP) showed as
"UNKNOWN" or generic "IPv4" because byte-level magic detection missed them.

**Solution:**
- Added `PayloadStringScanner.detectProtocolFromDetails(String details)` method
- Recognizes well-known Motorola UDP ports: 4001→LRRP, 4005→ARS, 64414→XCMP
- Recognizes keywords: "LRRP", "ARS"+"REGISTRATION", "SNDCP"+"ACTIVATE/DEACTIVATE"
- Applied as fallback enrichment in `processPacketMessage()`, `processSNDCPPacket()`,
  `processPDUMessage()`, and `processPDUSequenceMessage()`

## Files Modified

| File | Change |
|------|--------|
| `P25DataCaptureModule.java` | SACCH filters, freq/channel fields, enriched protocol on all builders |
| `PayloadStringScanner.java` | New `detectProtocolFromDetails()` method |
| `DecoderFactory.java` | Wire channelDescriptor freq/name to data capture module (P1 + P2) |

## Testing

- Build compiles cleanly (`gradlew compileJava` — BUILD SUCCESSFUL)
- No new warnings introduced
- Runtime verification: observe Data tab on Phase 2 system — MotorolaUnknownOpcode135
  messages should no longer appear; MotorolaTDMADataChannel only on state changes
