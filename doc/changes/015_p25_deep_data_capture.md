# Change 015: P25 Deep Data Capture

## Date
2026-03-24

## Summary
Added a P25 Deep Data Capture subsystem that intercepts all decoded P25 Phase 1 messages and extracts raw payload data for corpus building, protocol analysis, and real-time display. This captures PDU reassembled payloads, SNDCP packets, Low Speed Data (LSD) from voice frames, vendor/Motorola TSBKs, and status/extended function commands — data that was previously decoded but not exposed for inspection.

## Motivation
The Events tab shows call grants, registrations, and channel activity — but the actual **data payloads** flowing through the P25 system (PDU data packets, LRRP location reports, SNDCP sessions, vendor-specific TSBKs, Low Speed Data embedded in voice frames) are invisible. To understand what data services a system uses, build protocol decoders, and detect LRRP/GPS location data, we need to capture and display the raw bytes.

## Architecture

### New Files
| File | Purpose |
|------|---------|
| `module/decode/p25/data/CapturedPayload.java` | Immutable value object — timestamp, type, hex dump, detected strings, protocol ID, from/to, etc. Builder pattern. `toJsonLine()` for corpus logging. |
| `module/decode/p25/data/PayloadStringScanner.java` | Static utility — scans byte arrays for printable ASCII runs (≥4 chars), detects protocol by magic bytes (IPv4, LRRP, NMEA-GPS, JSON, ARP, HTTP). Hex dump formatter. |
| `module/decode/p25/data/P25DataCaptureModule.java` | Core module — extends `Module`, implements `IMessageListener`. Receives all messages from the processing chain. Dispatches by type: `PacketMessage`, `SNDCPPacketMessage`, `PDUMessage`, `PDUSequenceMessage`, `LDU1Message`/`LDU2Message` (LSD), `TSBKMessage`. Emits `CapturedPayload` to listeners + JSON-lines corpus log. |
| `module/decode/p25/data/ui/DataCaptureModel.java` | `AbstractTableModel` + `Listener<CapturedPayload>` — bounded 500-row history, newest-first, EDT-safe. 10 columns: Time, Type, SAP/Opcode, From, To, Len, Protocol, Hex, Strings, Details. |
| `module/decode/p25/data/ui/DataCapturePanel.java` | Swing panel — JTable with color-coded type/protocol renderers, timestamp formatting. Implements `Listener<ProcessingChain>` to discover the `P25DataCaptureModule` when channel selection changes. |

### Modified Files
| File | Change |
|------|--------|
| `DecoderFactory.java` | Added `modules.add(new P25DataCaptureModule())` in `processP25Phase1()` |
| `NowPlayingPanel.java` | Added "Data" tab with `DataCapturePanel`, registered as processing chain selection listener |
| `logback.xml` | Added `P25_DATA_FILE` rolling file appender → `logs/p25_data_capture.jsonl` (50MB/file, 30 days, 500MB cap) with `P25_DATA_CAPTURE` logger |

### Data Flow
```
P25 Decoder → IMessage → ProcessingChain message bus
                            ↓
                   P25DataCaptureModule.processMessage()
                            ↓
                   extract raw bytes, scan strings, detect protocol
                            ↓
                   CapturedPayload (immutable)
                     ↙            ↘
           CORPUS_LOG.info()    mPayloadListeners
           (JSON-lines file)      ↓
                            DataCaptureModel.receive()
                                  ↓
                            JTable update (EDT)
```

### Captured Message Types
| Type | PayloadType | What's Captured |
|------|-------------|-----------------|
| PacketMessage | PDU_PACKET | Reassembled PDU payload bytes — IP packets, LRRP, etc. |
| SNDCPPacketMessage | SNDCP | SNDCP header info + underlying packet payload |
| PDUMessage | PDU_PACKET | PDU message details (non-packet) |
| PDUSequenceMessage | PDU_PACKET | Completed PDU sequences |
| LDU1Message/LDU2Message | LSD | Low Speed Data (2 bytes/frame, non-zero only) |
| TSBKMessage (vendor/unknown) | TSBK_VENDOR | All unknown/vendor TSBK opcodes |
| TSBKMessage (Motorola) | TSBK_MOTOROLA | Extended function, emergency, regroup, deny, etc. |
| TSBKMessage (standard) | TSBK_STANDARD | Extended function, status update/query, emergency alarm |

### Corpus Log Format
JSON-lines format (`logs/p25_data_capture.jsonl`):
```json
{"ts":1711324800000,"type":"PDU_PACKET","class":"PacketMessage","sap":"","from":"03402059","to":"","channel":"","freq":0,"len":42,"hex":"45 00 00 2A ...","proto":"IPv4","details":"...","strings":["GET /"]}
```

## Testing
- Build compiles successfully (`gradlew compileJava` — BUILD SUCCESSFUL)
- The "Data" tab appears in the NowPlaying detail tabs between "Messages" and "Channel"
- When a P25 Phase 1 channel is selected, the DataCapturePanel discovers the P25DataCaptureModule and wires the model
- Corpus log rolls daily at `logs/p25_data_capture.jsonl`

## Future Work
- Phase 2: LRRP location parser (extract lat/lon from LRRP payloads, display on map)
- Phase 2: IP packet parser (extract IP headers, identify UDP/TCP protocols)
- Phase 2: Hex dump detail panel (click row → full hex+ASCII dump view)
- Phase 2: Export to PCAP format for Wireshark analysis
- Phase 2: Add P25 Phase 2 support in processP25Phase2()
