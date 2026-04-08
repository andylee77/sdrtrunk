# Change 019: Data Capture — Mode Field and UI Column Enrichment

**Date:** 2026-03-25
**Branch:** plutosdr
**Status:** Complete

## Summary

Added channel mode tracking (FDMA/TDMA) to the P25 data capture system, and surfaced
Channel, Frequency (MHz), and Mode as new columns in the Data tab UI. These fields are
also included in the JSONL corpus log output for offline analysis.

## Problem

The Data tab showed captured P25 payloads but lacked context about *where* the data
originated — specifically whether it came from an FDMA (Phase 1) or TDMA (Phase 2)
channel, what frequency it was captured on, and which logical channel descriptor it
belonged to. This made it difficult to correlate data captures with specific traffic
channels during analysis.

## Changes

### CapturedPayload.java
- Added `mMode` field (String: "FDMA", "TDMA", or empty)
- Added `getMode()` getter and `Builder.mode()` builder method
- Added `getFrequencyDisplay()` helper — formats Hz as MHz (e.g., `856712500` → `856.7125`)
- Added `"mode"` field to `toJsonLine()` JSONL output

### P25DataCaptureModule.java
- Added `mChannelMode` field with `setChannelMode()` / `getChannelMode()`
- All 7 `CapturedPayload.builder()` calls now include `.mode(mChannelMode)`
  - processPacketMessage (PDU/LRRP/XCMP)
  - processSNDCPPacket
  - processPDUMessage
  - processPDUSequenceMessage
  - processLDU (Low Speed Data)
  - processMacMessage (Phase 2 MAC)
  - processTSBK

### DecoderFactory.java
- Phase 1 creation: `dataCaptureP1.setChannelMode("FDMA")`
- Phase 2 creation: `dataCaptureP2.setChannelMode("TDMA")`

### DataCaptureModel.java (UI table model)
- Added 3 new columns between Type and SAP/Opcode:
  - **Mode** — FDMA or TDMA
  - **Channel** — logical channel descriptor (e.g., "0-913")
  - **Freq (MHz)** — frequency in MHz format (e.g., "856.7125")
- Column count increased from 11 to 14
- All column index constants renumbered accordingly

### DataCapturePanel.java (UI panel)
- Added column widths for Mode (45px), Channel (60px), Freq (75px)
- Added `ModeCellRenderer` — color-codes FDMA (blue) vs TDMA (orange)
- Added centered cell renderers for Channel and Freq columns

## JSONL Output Change

The `"mode"` field is now included in every JSONL line:
```json
{"ts":1711...,"type":"LRRP","class":"PacketMessage","sap":"LRRP:IMMEDIATE_LOCATION_REPORT","from":"1234","to":"","channel":"0-913","freq":856712500,"len":45,"hex":"45...","proto":"LRRP","mode":"FDMA","details":"..."}
```

## Files Modified
- `src/main/java/io/github/dsheirer/module/decode/p25/data/CapturedPayload.java`
- `src/main/java/io/github/dsheirer/module/decode/p25/data/P25DataCaptureModule.java`
- `src/main/java/io/github/dsheirer/module/decode/DecoderFactory.java`
- `src/main/java/io/github/dsheirer/module/decode/p25/data/ui/DataCaptureModel.java`
- `src/main/java/io/github/dsheirer/module/decode/p25/data/ui/DataCapturePanel.java`

## Testing
- Build compiles successfully with `gradlew compileJava`
- No new warnings introduced
