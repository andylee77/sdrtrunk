# Change 021 — Zero-Payload Filter & Deep IP Analysis

**Date:** 2026-03-25  
**Branch:** plutosdr  
**Status:** Complete

## Problem

After capturing 12.8 hours of data across Jacksonville and Clay County systems (260K records),
analysis revealed that **76.7% of all records had zero payload length**. These were PDU
ResponseMessage acknowledgments and other signaling records containing no actual data content.
They cluttered both the Data tab UI and the JSONL corpus logs, making analysis difficult.

## Changes

### Java: Zero-Payload Filter

**`P25DataCaptureModule.java`** — Added filter in `emit()` method:
- Records with `payloadLength == 0` are now suppressed before reaching listeners or JSONL log
- This is the primary filter point — catches all zero-payload records regardless of type
- Reduces JSONL log volume by ~77%, dramatically improving corpus quality

**`DataCaptureModel.java`** — Added safety filter in `receive()` method:
- Belt-and-suspenders: also checks `payloadLength == 0` at the UI level
- Ensures the Data tab never displays empty records even if the primary filter is bypassed

### Analysis Tools

**`tools/ip_reconstruct.py`** — New comprehensive IP layer analysis tool:
- Reconstructs SNDCP session lifecycle (activate/deactivate/reject with IP tracking)
- Parses IPv4/UDP headers from hex payloads
- Identifies application protocols (LRRP, ARS, XCMP, TMS) from port numbers
- Tracks IP flows with direction analysis (infra→radio vs radio→infra)
- Builds radio database with IP assignments and activity spans
- Performs payload pattern analysis (size distribution, first-byte analysis)
- Timeline analysis with per-minute and per-hour activity bucketing
- Outputs 10-section comprehensive report

**`tools/quick_stats.py`** — Quick statistical overview of capture files

## Key Findings from 12.8-Hour Corpus

| Metric | Value |
|--------|-------|
| Total records | 260,192 |
| Non-zero payload | 60,624 (23.3%) |
| Unique radio IDs | 2,867 |
| Radios with IPs | 2,097 |
| Unique IPs assigned | 2,318 |
| IP subnets (active) | 20+ /24s in 10.71.0.0/16 |
| LRRP events | 579 (all outbound requests) |
| XCMP events | 2,988 (fleet management) |
| ARS events | 1,313 (registration keepalives) |
| Longest SNDCP session | 8.1 hours (Radio 3409004) |

### Network Topology
- **10.51.1.116** — Main LRRP/ARS server
- **192.168.23.240** — Fleet management console (XCMP)
- **10.71.0.0/16** — Radio IP space (2,318 unique addresses)

### IP Reconstruction Limitation
Full IP flow reconstruction requires extracting IP packet bytes from SDRTrunk's
`PacketMessage` hierarchy (currently we capture wrapper bytes). The details string
contains parsed IP info but the hex field has the outer PDU/SNDCP structure bytes.

## Files Modified

| File | Change |
|------|--------|
| `P25DataCaptureModule.java` | Zero-payload filter in `emit()` |
| `DataCaptureModel.java` | Safety zero-payload filter in `receive()` |
| `tools/ip_reconstruct.py` | New: comprehensive IP analysis tool |
| `tools/quick_stats.py` | New: quick stats overview |
| `doc/design/021_deep_ip_analysis_findings.md` | Full analysis findings |

## Impact

- **JSONL logs:** ~77% smaller going forward (only non-zero payload records logged)
- **Data tab:** Only shows records with actual data content
- **Analysis:** New tools enable IP layer reconstruction from corpus data
