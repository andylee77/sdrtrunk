# 017 — Data Capture Improvements (Next Steps)

**Date:** 2026-03-24  
**Based on:** Analysis of live capture data from Change 016 (per-system JSONL logs)  
**Systems analyzed:** Clay County (NAC:2209/x8A1, Phase 2) and Jacksonville City - First Coast Radio (NAC:954/x3BA, Phase 1)

---

## Priority 1: Filter SACCH Idle Noise (High Impact)

**Problem:** The Clay County (Phase 2) log is ~90% repetitive Motorola SACCH idle fill messages — `MotorolaUnknownOpcode135` (opcode 0x87) and `MotorolaTDMADataChannel` (opcode 0x8B) repeating every ~350ms across TS1+TS2. These drown out meaningful data.

**Evidence:**
- Identical hex payload `7C 87 90 03 8B 90 0F FF 04 05 FF 04 05 FF 04 05 FF 04 05 00 00 B3 A0` repeated hundreds of times
- Details: "TS1/TS2 SACCH-S IDLE MOTOROLA TDMA DATA CHANNEL ACTIVE CHAN1:0-1029"

**Action:**
- In `P25DataCaptureModule`, add filtering to suppress:
  - `MotorolaUnknownOpcode135` messages entirely (or at most log once per session)
  - `MotorolaTDMADataChannel` IDLE messages (deduplicate — only log on change)
- Consider a configurable "verbose SACCH logging" flag, default off

**Files:** `P25DataCaptureModule.java`, possibly `CapturedPayload.java`

---

## Priority 2: Populate freq/channel Fields in Aggregated Data

**Problem:** All entries have `freq: 0` and `channel: ""`. Traffic channel data forwarded to the control channel module loses its source frequency/channel context.

**Evidence:** Every single JSONL entry shows `"freq":0,"channel":""` even for traffic channel LRRP and SNDCP messages.

**Action:**
- When `P25TrafficChannelManager` forwards `CapturedPayload` to the control channel's `P25DataCaptureModule`, include the traffic channel's frequency and channel descriptor
- Add `freq` and `channel` fields to `CapturedPayload` if not already present, populate them at traffic channel capture time

**Files:** `P25TrafficChannelManager.java`, `CapturedPayload.java`, `P25DataCaptureModule.java`

---

## Priority 3: Set Protocol Field for Known Packet Types

**Problem:** LRRP and ARS packets are partially decoded in the `details` field but have `proto: UNKNOWN` in the JSON output.

**Evidence:**
- ARS packets: `"details":"...ARS REGISTRATION SUCCESS REFRESH IN:240mins"` but `"proto":"UNKNOWN"`
- LRRP packets: `"details":"...LRRP TRIGGERED LOCATION START..."` but `"proto":"UNKNOWN"`

**Action:**
- In `PayloadStringScanner` or `P25DataCaptureModule`, detect and set proto field:
  - Port 4001 UDP → `proto: "LRRP"`
  - Port 4005 UDP → `proto: "ARS"`
  - Port 64414 UDP → `proto: "XCMP"` or `"TMS"` (Motorola device management, needs confirmation)
- Parse the `details` string for "LRRP" / "ARS" keywords as a fallback

**Files:** `P25DataCaptureModule.java`, `PayloadStringScanner.java`

---

## Priority 4: LRRP GPS Coordinate Extraction (Future Enhancement)

**Problem:** LRRP packets contain encoded GPS coordinates but they're not extracted as structured data.

**Evidence:**
- LRRP messages contain tokens like `REQUEST-64, REQUEST-62, HORIZONTAL DIRECTION, TIMESTAMP`
- The recurring strings `RDdB` and `bW4` in the binary payload suggest IEEE 754 float-encoded lat/lon values
- Trigger parameters are decoded (distance, time interval) but position data is listed as "UNRECOGNIZED" tokens: `(08, 10, 12, 2C, 44, 58, 9C)`

**Action (future):**
- Research LRRP token structure per TIA-102.BAHA / Motorola LRRP spec
- Extract lat/lon from REQUEST-64 (likely latitude) and REQUEST-62 (likely longitude) tokens
- Add GPS coordinate fields to the JSONL output for LRRP response messages
- Consider feeding these into a map display

**Files:** Would require new LRRP parsing code, possibly a new `LrrpDecoder` class

---

## Priority 5: Identify Jacksonville Port 64414 Traffic

**Problem:** Several IP packets from `192.168.23.240:52482` → port `64414` appear on the Jacksonville system with unknown payload structure.

**Evidence:**
- Multiple LLIDs receive these: 3201525, 3203312, 3203545, 3205825, 3203319
- Source `192.168.23.240` is likely system infrastructure (private IP)
- One packet also from `40.232.23.240` (Azure IP?) using RSVP protocol
- Payload pattern: `0000 8000 0000...2600 0800 30xx...`

**Action:**
- Research Motorola XCMP/XNL protocol on port 64414
- May be device management/provisioning traffic
- Low priority but interesting for system intelligence

---

## Observations (No Code Change Needed)

### SNDCP Context Management Working Well
- Both systems show healthy SNDCP ACTIVATE/DEACTIVATE cycles
- IPv4 address assignments visible: 10.71.x.x subnet
- Context reject for LLID 3133032 ("MRC NOT PROVISIONED FOR TDS") captured correctly — useful for system analysis

### Patch Group Management Captured
- Jacksonville system: `MotorolaGroupRegroupAddCommand` and `DeleteCommand` for Patch Group P:149
- This is real-time patch group creation/deletion — valuable operational data
- Already well-captured by the existing code

### LSD (Low Speed Data) From Traffic Channels
- SACCH, SLOW-CTRL, VENDOR, CRYPTO-SYNC LSD types captured from Jacksonville
- These provide traffic channel timing and encryption status context

### Data Volume Estimates
- Clay County (Phase 2): ~500 entries in 2.5 min → ~12,000/hour (mostly noise, ~1,200/hour after filtering)
- Jacksonville (Phase 1): ~500 entries in 3.3 min → ~9,000/hour (mostly useful data)
- Log rotation / size management may be needed for long sessions
