# Change 022: Extended PDU Block Assembly & Data Channel Timeout

**Date:** 2026-03-25
**Branch:** plutosdr
**Status:** Complete

## Problem

We were missing data packets from long P25 data calls. Analysis of the capture pipeline identified two key gaps:

### Gap 1: Hard Limit of 5 PDU Data Blocks
The `P25P1MessageFramer` only handled up to 5 data blocks per PDU sequence, despite the P25 protocol supporting up to 127 (7-bit `BLOCKS_TO_FOLLOW` field). This meant:
- **Unconfirmed mode**: Max 60 bytes payload (5 × 12 bytes)
- **Confirmed mode**: Max 80 bytes payload (5 × 16 bytes)

Any IP packet larger than this was silently truncated. In our 260K-record corpus, only **8 records** had parseable IPv4 data — the rest were likely truncated before IP parsing could succeed.

### Gap 2: Aggressive Traffic Channel Timeout
The traffic channel timeout was 1 second (changed from upstream's 45-second default). Data channels can have legitimate multi-second gaps between PDU bursts. A 1-second timeout could prematurely tear down a data channel mid-session.

## Changes

### 1. Extended PDU Block Assembly (P25P1MessageFramer)

**Files modified:**
- `P25P1DataUnitID.java` — Added `PACKET_DATA_UNIT_BLOCK_EXTENDED` enum value
- `P25P1MessageAssembler.java` — Added `reconfigure(duid, messageLength)` overload for dynamic sizing
- `P25P1MessageFramer.java` — Extended `dispatchPDU()` to handle blocks beyond 5

**How it works:**
- Blocks 1-5: Existing hardcoded `PACKET_DATA_UNIT_BLOCK_1..5` handling is **unchanged** (zero regression risk)
- Block 5: Now checks `isComplete()` instead of unconditionally dispatching. If more blocks are needed, transitions to extended assembly
- Blocks 6+: New `dispatchExtendedPDUBlock()` method uses dynamic offset computation based on `PDUSequence.getDataBlocks().size()`
- Safety cap at **32 blocks** (`MAX_PDU_DATA_BLOCKS`) to prevent runaway assembly from corrupted headers
- Message lengths computed dynamically: each block beyond 5 adds 210 bits (196 data + 14 null padding)
- Elapsed dibit tracking computed dynamically: 684 base + 108 per additional block

**New capacity:**
- **Unconfirmed mode**: Up to 384 bytes (32 × 12 bytes) — 6.4× previous
- **Confirmed mode**: Up to 512 bytes (32 × 16 bytes) — 6.4× previous

### 2. Data Channel Timeout Extension (P25P1DecoderState)

**File modified:** `P25P1DecoderState.java`

Changed traffic channel fade timeout from **1 second** to **3 seconds**. This gives data channels enough time to complete multi-burst PDU sessions without premature teardown, while still releasing idle channels promptly.

### 3. Diagnostic Logging

Added structured logging at key points:
- **INFO** when PDU header announces > 5 blocks (shows format, confirmed flag, LLID)
- **INFO** when extended block assembly begins
- **INFO** when extended sequence completes (shows block count and payload bytes)
- **WARN** when a header requests more than 32 blocks (safety cap triggered)

## Technical Details

### PDU Block Assembly Pattern
```
PDU Header (196 bits) → Block 1 (196 bits) → Block 2 → ... → Block N
                         ↑                                      ↑
                    getSubMessage(196, 392)              getSubMessage(196*N, 196*(N+1))
```

### Message Length Computation (blocks > 5)
```
assemblerMessageLength = 1218 + (blockNumber - 5) × 210
elapsedDibits = 684 + (blockCount - 5) × 108
```

The 210-bit increment per block (196 data + 14 null padding) was derived from the consistent pattern observed in blocks 2-5.

### Remaining Gaps (Future Work)
1. **SNDCP Segmentation/Reassembly** — Large IP packets can span multiple PDU transmissions via SNDCP. No reassembly state machine exists yet.
2. **IP Fragmentation Reassembly** — The `IPV4Packet` parser doesn't reassemble IP fragments.
3. **Phase 2 TDMA Data** — No PDU/packet infrastructure for P25 Phase 2 data channels.

## Files Changed

| File | Change |
|------|--------|
| `P25P1DataUnitID.java` | Added `PACKET_DATA_UNIT_BLOCK_EXTENDED` enum |
| `P25P1MessageAssembler.java` | Added `reconfigure(duid, messageLength)` overload |
| `P25P1MessageFramer.java` | Extended `dispatchPDU()`, added `dispatchExtendedPDUBlock()`, `getExtendedBlockMessageLength()`, constants, logging |
| `P25P1DecoderState.java` | Changed traffic channel timeout from 1000ms to 3000ms |

## Testing

- Build: `gradlew compileJava` — BUILD SUCCESSFUL
- No changes to existing block 1-5 handling paths (backward compatible)
- Extended paths only activate when PDU header's `blocksToFollowCount > 5`
