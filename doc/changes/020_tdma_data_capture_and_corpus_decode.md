# Change 020: TDMA Data Capture + Corpus Deep Decode

**Date:** 2026-03-25  
**Status:** Complete  
**Design doc:** `doc/design/020_corpus_deep_decode_findings.md`

## Problem

Two issues identified:

1. **Phase 2 TDMA data channels not captured**: The Data tab showed only FDMA entries even though P25-2 DATA channels on TS:1/TS:2 were active. The `processMacMessage()` filter in P25DataCaptureModule was too restrictive — it only captured vendor/unknown/Motorola MAC structures, dropping all standard MAC opcodes. On TDMA data channels, these standard MAC messages carry the actual data content (SNDCP, PDU, IP data).

2. **Corpus analysis revealed one-way traffic**: Deep decoding of 24,474 captured records showed ALL 222 IP-layer packets were outbound (infrastructure→radio). No inbound radio→infrastructure responses were captured, meaning no GPS coordinates from LRRP responses.

## Changes

### P25DataCaptureModule.java
- Modified `processMacMessage()` to capture ALL standard MAC opcodes when `mChannelMode == "TDMA"`
- This ensures Phase 2 TDMA data channels (TS:1, TS:2) capture everything flowing on those timeslots
- Non-TDMA channels retain the original selective capture filter
- The SACCH idle noise filter (MotorolaUnknownOpcode135) and TDMA data channel dedup still apply

### Analysis Tools (tools/)
- `corpus_analysis.py` — Record type distribution analysis
- `deep_decode.py` — First-pass hex payload decoder
- `deep_decode2.py` — Full protocol decoder (LRRP, SNDCP, ARS, XCMP, Motorola TSBK)
- `direction_analysis.py` — Traffic direction and IP layer parsing

### Design Doc
- `doc/design/020_corpus_deep_decode_findings.md` — Complete analysis findings including network topology, protocol breakdowns, and recommendations

## Key Findings from Corpus Analysis

| Protocol | Records | Direction | Content |
|----------|---------|-----------|---------|
| LRRP | 17 | Outbound | Triggered Location Stop Requests to 7 radios |
| ARS | 50 | Outbound | Server keepalive/refresh to 42 radios |
| XCMP | 91 | Outbound | Device status queries to 58 radios |
| SNDCP | 2,846 | Outbound | IP address assignments (190 radios mapped) |
| Motorola TSBK 0x8B | 1,077 | Control | 3 TDMA data channel announcements |
| ResponseMessage | 12,334 | N/A | Empty acknowledgment records |

Network topology discovered:
- **10.51.1.116** = Infrastructure server (LRRP + ARS)
- **192.168.23.240** = Fleet management console (XCMP)
- **10.71.128.0/17** = Radio IP subnet (190 radios mapped)

## Build

```
BUILD SUCCESSFUL
```

## Testing

Run SDRTrunk, observe the Data tab when P25-2 DATA channels are active on TS:1/TS:2. TDMA MAC messages should now appear in the Data tab and be logged to the corpus file.
