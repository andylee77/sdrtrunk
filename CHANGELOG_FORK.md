# SDRTrunk — Changelog (andylee77 fork)

## [2026-03-25] Change 023: DATCH Motorola Protocol Deep Analysis

- Built `tools/datch_motorola_protocol.py` — comprehensive 9-analysis protocol tool
- **Confirmed byte[0] header structure**: bits[7:6]=frame counter (cycles 0->2->3), bits[5:0]=opcode
- **Identified 15+ distinct opcodes** mapping to system beacons, data channels, encrypted data
- **Discovered three idle families**: EC (primary), B6 (channel-specific), 20 (tertiary)
- **Mapped "xx05/xx35" family group** — shared opcode 0x17, structured data records
- **EC-variant XOR extraction** reveals user data encoded as bit diffs from idle canonical
- **Cross-family relationships found** — families sharing payload body with different headers
- **Identified encrypted/OTAR families** — opcodes 0x25-0x34 with high-entropy payloads
- **Single-byte protocol signature matches are false positives** — +36 offset spacing confirms
  per-slot byte values, not reassembled PDU content
- Detailed findings: `doc/design/023_datch_motorola_protocol_analysis.md`

## [2026-03-25] Change 023: DATCH Corpus Deep Analysis

**First DATCH corpus successfully captured and analyzed — 2,329 timeslots, 93 KB, 100-second session (Clay County 857.2125 MHz)**

Key findings from deep bit-level analysis:
- **Two dominant idle/keepalive families** account for 94.2% of traffic (EC family: 57.7%, B6 family: 36.5%)
- **NOT encrypted** at transport layer (53.7% 1-bit ratio, massive payload duplication)
- **Frame structure decoded:** byte 0 bits[7:6] = frame counter, byte 0 bits[5:0] = family ID, byte 9 = slot counter (4-value cycle), byte 30 = sub-counter, byte 39 = CRC/checksum
- **7-slot superframe** repeating cycle identified, alternating TS1/TS2 in fixed pattern
- **112 data burst timeslots (4.8%)** found in 4 distinct burst events:
  - Burst 1: Single control/setup frame
  - Burst 2: 28-timeslot structured data burst (1.4s) with EC-variant and new control families
  - Burst 3: 7-timeslot data burst (250ms) with structured low-entropy content
  - Burst 4: 4-timeslot HIGH ENTROPY burst — possible encrypted application data (OTAR/APX-NEXT MDT)
  - Plus 48 recurring status/beacon frames scattered throughout session
- **XOR analysis** confirms only 4 byte positions vary within idle families (bytes 0, 9, 30, 39)

New tools and docs:
- `tools/datch_deep_analysis.py` — Bit-level DATCH analysis (families, XOR diffs, superframe detection, data transition mapping)
- `doc/design/023_datch_corpus_analysis_findings.md` — Full analysis findings with protocol architecture

## [2026-03-25] Change 023: Phase 2 TDMA Data Channel (DATCH) Raw Capture

**Phase 2 TDMA data sessions now captured to JSONL corpus and Data tab**
- Previously, DATCH timeslots (Motorola TDMA data channel) were detected and allocated but their 320-bit payloads were discarded — zero data captured from 14-15 second data sessions
- Added `getDescrambledPayload()` and `getDescrambledPayloadHex()` to `DatchTimeslot.java`
- Added `DATCH_RAW` payload type to `CapturedPayload.PayloadType` enum
- Added `processDatchTimeslot()` handler in `P25DataCaptureModule.java` — captures every DATCH timeslot's raw 40-byte descrambled payload with timestamp, timeslot number, channel, and frequency metadata
- Each 15-second data session now produces ~400 timeslots × 40 bytes = ~16,000 bytes of raw data for analysis

**Offline Analysis Tool**
- New `tools/datch_analysis.py` for corpus analysis: session grouping, header pattern analysis, FEC detection (entropy, IPv4 signature scanning, duplicate detection), raw hex dump
- Usage: `python tools/datch_analysis.py logs/p25_data_system_20260325.jsonl --all`

**Zero risk to existing functionality** — no changes to P25P2DecoderState, voice processing, or any other decoder path. DatchTimeslot messages already flowed through the message listener chain; we just added a handler to capture their payloads.

Files: DatchTimeslot.java, P25DataCaptureModule.java, CapturedPayload.java, tools/datch_analysis.py
Doc: `doc/changes/023_phase2_tdma_data_channel_raw_capture.md`, `doc/design/023_phase2_tdma_data_channel_decoding.md`

## [2026-03-25] Change 022: Extended PDU Block Assembly & Data Channel Timeout

**Extended PDU Block Assembly (was limited to 5 blocks, now supports up to 32)**
- P25 protocol allows up to 127 data blocks per PDU header, but framer was hardcoded to BLOCK_1..BLOCK_5
- Previous max payload: 60-80 bytes — insufficient for larger IP packets, LRRP responses, etc.
- Added `PACKET_DATA_UNIT_BLOCK_EXTENDED` DUID and dynamic assembly loop in `P25P1MessageFramer`
- New capacity: up to 384 bytes (unconfirmed) / 512 bytes (confirmed) per PDU sequence — 6.4× improvement
- Blocks 1-5 handling completely unchanged (zero regression risk); extension only activates when blocksToFollow > 5
- Safety cap at 32 blocks prevents runaway assembly from corrupted headers

**Data Channel Timeout Extension**
- Traffic channel fade timeout increased from 1 second to 3 seconds
- Prevents premature teardown of data channels during multi-burst PDU sessions
- Still well below upstream's 45-second default; good balance of capture vs resource usage

**Diagnostic Logging**
- Logs when PDU header announces > 5 blocks (format, confirmed flag, LLID)
- Logs extended sequence completion with block count and payload bytes
- Warns when header requests > 32 blocks (safety cap)

Files: P25P1DataUnitID.java, P25P1MessageAssembler.java, P25P1MessageFramer.java, P25P1DecoderState.java

## [2026-03-25] Change 021: Zero-Payload Filter & Deep IP Analysis

**Zero-Payload Filter**
- Added filter in `P25DataCaptureModule.emit()` to suppress records with `payloadLength == 0`
- Added safety filter in `DataCaptureModel.receive()` at the UI level
- Eliminates 76.7% of corpus noise (PDU ResponseMessage ACKs with no data content)
- JSONL logs and Data tab now show only records with actual payload data

**Deep IP Analysis (12.8-hour corpus: 260K records, Jacksonville + Clay County)**
- New `tools/ip_reconstruct.py`: 10-section comprehensive IP layer analysis
- SNDCP session lifecycle reconstruction: 2,318 unique IPs assigned to 2,097 radios
- Network topology mapped: 10.51.1.116 (LRRP/ARS server), 192.168.23.240 (fleet mgmt), 10.71.0.0/16 (radio space)
- 579 LRRP events (all outbound location requests), 2,988 XCMP fleet mgmt, 1,313 ARS registrations
- Longest SNDCP session: 8.1 hours; 117 radios with multiple IP assignments
- IP flow reconstruction limited: hex captures contain wrapper bytes, not IP packet bytes
- Recommendations: extract IP payload from PacketMessage hierarchy, capture data channel traffic

Files: P25DataCaptureModule.java, DataCaptureModel.java, tools/ip_reconstruct.py, tools/quick_stats.py
Doc: `doc/changes/021_zero_payload_filter_and_deep_analysis.md`, `doc/design/021_deep_ip_analysis_findings.md`

---

## [2026-03-25] Change 018: LRRP GPS Coordinate Extraction & XCMP/XNL Detection

**Priority 4: LRRP GPS Extraction**
- Walks parsed packet hierarchy (IPV4→UDP→LRRPPacket) to extract GPS coordinates
- Extracts lat/lon from Point2d/Point3d tokens, heading from Heading, speed from Speed
- New GPS column in Data tab (green text, between Protocol and Hex)
- GPS coordinates included in JSON corpus log (`lat`, `lon`, `heading`, `speed` fields)
- "Copy GPS" right-click context menu for LRRP rows; "LRRP" filter option in type dropdown

**Priority 5: XCMP/XNL Port 64414 Identification**
- Added port 64414 routing to XCMP parser in PacketMessageFactory
- P25DataCaptureModule detects XCMP packets and extracts message type
- Port 64414 traffic labeled as "XCMP" protocol with purple color
- Added ARS (teal), SNDCP (dark cyan) protocol colors

Files: CapturedPayload.java, P25DataCaptureModule.java, DataCaptureModel.java,
DataCapturePanel.java, PacketMessageFactory.java
Doc: `doc/changes/018_lrrp_gps_and_xcmp_detection.md`

---

Tracking log for the `plutosdr` branch of the SDRTrunk fork.
Upstream: [DSheirer/sdrtrunk](https://github.com/DSheirer/sdrtrunk)

---

## [2026-03-08] Project Setup

### Fork & Repository
- Forked `DSheirer/sdrtrunk` → `andylee77/sdrtrunk`
- Created `plutosdr` branch for PlutoSDR development
- Set up upstream tracking: `upstream` → `DSheirer/sdrtrunk`, `origin` → `andylee77/sdrtrunk`
- Working copy: `C:\Users\Andy\Projects\SDRTrunk\sdrtrunk`
- Upstream version: `0.6.2-beta-1`

### Areas of Development
- **PlutoSDR tuner support** — Hardware integration via libiio

### Work Documents
Extensive work documents accumulated in `C:\Users\Andy\Projects\SDRTrunk\work_docs\`:
- `plutosdr/` — 30+ documents on PlutoSDR integration
- `ddc/` — 14 documents on DDC channelizer design
- `p25/` — 15 documents on P25 decoder investigation
- `pymbe/` — AMBE voice codec research

---

## [2026-03-08] Change 001: PlutoSDR Tuner Integration

### New Files (9)
- `buffer/SignedShortNativeBuffer.java` — Signed 16-bit IQ native buffer
- `source/tuner/manager/IBandwidthAdjustableTunerController.java` — Auto-bandwidth interface
- `source/tuner/plutosdr/PlutoSdrTunerController.java` — TCP protocol, connect/reconnect, debounced retune, DC offset correction
- `source/tuner/plutosdr/PlutoSdrTuner.java` — Tuner wrapper
- `source/tuner/plutosdr/PlutoSdrTunerConfiguration.java` — Configuration bean (host, port, sample rate, gain, AGC, RF bandwidth)
- `source/tuner/plutosdr/PlutoSdrTunerEditor.java` — JavaFX editor panel
- `source/tuner/plutosdr/DiscoveredPlutoSdrTuner.java` — Discovery/lifecycle management
- `source/tuner/plutosdr/AddPlutoSdrTunerDialog.java` — Runtime add dialog
- `source/tuner/plutosdr/PlutoSdrDeviceInfo.java` — Device info container

### Modified Files (10)
- `TunerType.java` — Added PLUTO_SDR enum
- `TunerClass.java` — Added PLUTO_SDR enum
- `TunerConfiguration.java` — Added JSON subtype for PlutoSDR config
- `TunerFactory.java` — Added PlutoSDR cases in config and editor factories
- `TunerController.java` — Added `isFrequencyLocked()` base method
- `FrequencyErrorCorrectionManager.java` — Added 30s post-correction cooldown, `resetCooldown()`, improved logging
- `TunerManager.java` — Added `discoverPlutoSdrTuners()`, made `startAndConfigureTuner()` public
- `TunerViewPanel.java` — Added PlutoSDR add/remove buttons, channel control buttons
- `ControllerPanel.java` — Pass playlistManager to TunerViewPanel
- `PolyphaseChannelSourceManager.java` — Added frequency-lock check before retuning

### Decisions
- ❌ Skipped DDC contamination in `Tuner.java` (DDC channelizer type)
- ❌ Skipped DDC tab in `ControllerPanel.java`
- ✅ Build verified: `gradlew compileJava` — BUILD SUCCESSFUL

### Documentation
- `doc/changes/001_plutosdr_integration.md` — Detailed change doc

---

## [2026-03-09] Change 002: Waterfall / Spectrum UI Enhancements

### New Files (1)
- `spectrum/menu/ReferenceLevelItem.java` — JSlider widget (-60 to +60 dB) for shifting spectrum and waterfall reference level

### Modified Files (4)
- `SpectrumPanel.java` — Added reference level offset field, getter/setter, applied as pixel offset in drawSpectrum()
- `WaterfallPanel.java` — Added reference level offset field, getter/setter (dB→color-index scaling), applied in receive() loop
- `SpectralDisplayPanel.java` — Import ReferenceLevelItem, added "Reference Level" submenu in right-click context menu
- `ComplexDecibelConverter.java` — Fixed dB calculation: 10*log10(power) → 20*log10(amplitude) for correct amplitude-domain scaling

### Documentation
- `doc/changes/002_waterfall_spectrum_ui.md` — Detailed change doc

---

## [2026-03-14] Change 003: Ignore Encrypted Calls Option

### Modified Files (3)
- `module/decode/p25/phase1/DecodeConfigP25.java` — Added `mIgnoreEncryptedCalls` boolean field with Jackson XML serialization, getter, and setter
- `gui/playlist/channel/P25P1ConfigurationEditor.java` — Added "Ignore Encrypted Calls" ToggleSwitch to the P25 Phase 1 decoder panel, with load/save support
- `module/decode/p25/P25TrafficChannelManager.java` — Added `mIgnoreEncryptedCalls` field, reads from config in constructor, filters encrypted grants in `processPhase1ControlChannelGrant()` and `processPhase2ChannelGrant()`

### Behavior
- When enabled, encrypted channel grants are logged as "IGNORED: ENCRYPTED CALL" but do not consume a traffic channel slot
- Setting persisted in playlist XML as `ignore_encrypted_calls` attribute on the decode configuration element
- Works for both P25 Phase 1 and Phase 2 channel grants

### Documentation
- `doc/changes/003_ignore_encrypted_calls.md` — Detailed change doc

### Bugfix: Same-Call Re-Allocation Bypass (ef329cd9)
- **Bug:** Repeated channel grant updates for the same encrypted call entered the "same call" code path, which bypassed the encrypted filter and allocated a traffic channel anyway
- **Fix:** Added early-return encrypted check at the top of both "same call" blocks in `processPhase1ControlChannelGrant()` and `processPhase2ChannelGrant()`
- See `doc/changes/003_ignore_encrypted_calls.md` for full details

---

## [2026-03-14] Change 004: Event Tab Pre-Filter and Filtered Save-to-CSV

### Modified Files (3)
- `module/decode/event/ClearableHistoryModel.java` — Added `FilterSet` reference, `preFilterEnabled` flag, `saveEnabled` flag, and `ISaveEventListener` callback interface. The `add()` method now checks the filter before storing events (pre-filter) and forwards passing events to the save listener.
- `module/decode/event/HistoryManagementPanel.java` — Added "Pre-Filter" checkbox and "Save" checkbox to the toolbar, with tooltips. Added `setSaveToggleCallback()` and `isSaveSelected()` methods.
- `module/decode/event/DecodeEventPanel.java` — Added CSV save infrastructure: `openSaveFile()`, `closeSaveFile()`, `writeEventToSaveFile()` with human-readable formatting (resolved aliases, formatted timestamps, duration in seconds, frequency in MHz). Wired save toggle callback and save event listener. Closes save file on channel switch.

### Behavior
- **Pre-Filter:** When the "Pre-Filter" checkbox is enabled, events that don't pass the filter are dropped entirely and don't consume buffer space. Only affects new incoming events.
- **Save:** When the "Save" checkbox is enabled, filtered events are written to a CSV file (`<timestamp>_filtered_events.csv`) in the event logs directory with the same human-readable columns shown in the UI: Time, Duration, Event, From, From Alias, To, To Alias, Channel, Frequency, Details.
- The existing post-filter (JTable RowFilter) behavior is completely unchanged when Pre-Filter is unchecked.
- Both controls also appear on the Messages tab. Pre-Filter works for messages. Save is a no-op on Messages (no listener configured).

### Bugfix: ClassCastException on Messages Tab
- **Bug:** `HistoryManagementPanel.updateFilterSet()` called `mModel.setFilterSet(filterSet)`, setting a `FilterSet<IMessage>` on `ClearableHistoryModel<MessageItem>`, causing ClassCastException when `add()` checked the filter
- **Fix:** Removed `mModel.setFilterSet()` from `HistoryManagementPanel`, set filter directly on model in `DecodeEventPanel` where generics types match

### Documentation
- `doc/changes/004_event_prefilter_and_save.md` — Detailed change doc

---

## [2026-03-20] Change 005: Ignore Unmonitored Calls Option

### Modified Files (5)
- `module/decode/p25/phase1/DecodeConfigP25.java` — Added `mIgnoreUnmonitoredCalls` boolean field with Jackson XML serialization, getter, and setter
- `gui/playlist/channel/P25P1ConfigurationEditor.java` — Added "Ignore Unmonitored Calls" ToggleSwitch to the P25 Phase 1 decoder panel, with load/save support
- `gui/playlist/channel/P25P2ConfigurationEditor.java` — Added "Ignore Unmonitored Calls" ToggleSwitch to the P25 Phase 2 decoder panel, with load/save support
- `module/decode/p25/P25TrafficChannelManager.java` — Added `mIgnoreUnmonitoredCalls` field, `AliasList` field with setter, `isUnmonitored()` helper method; filters unmonitored grants in both Phase 1 and Phase 2 grant methods (same-call and new-call paths)
- `module/decode/DecoderFactory.java` — Wired `AliasList` to `P25TrafficChannelManager` via `setAliasList()` at all P25 creation points

### Behavior
- When enabled, calls to talkgroups that are "unmonitored" (no alias, Do Not Monitor priority, or no recording/streaming configured) are logged as "IGNORED: UNMONITORED CALL" but do not consume a traffic channel slot
- Setting persisted in playlist XML as `ignore_unmonitored_calls` attribute on the decode configuration element
- Works for both P25 Phase 1 and Phase 2 channel grants, including same-call continuation paths
- Significantly reduces wasted tuner resources on busy systems where only specific talkgroups are of interest

### Documentation
- `doc/changes/005_ignore_unmonitored_calls.md` — Detailed change doc

---

## [2026-03-20] Change 007: Audio Channel Routing Filter

### New Files (3)
- `audio/playback/AudioChannelFilterMode.java` — Enum for filter modes: OFF, ALL, SYSTEM, GROUP
- `audio/playback/AudioChannelFilter.java` — Routing filter with per-talkgroup mute set; `accepts()` method checks system/group matching, `isTalkgroupMutedForSegment()` checks per-TG mute
- `audio/playback/AudioChannelFilterItem.java` — Combo box model item wrapping mode + value + display label

### Modified Files (8)
- `audio/playback/AudioChannel.java` — Added `AudioChannelFilter` field, `getFilter()` accessor, per-TG mute check in both `getAudio()` mute code paths
- `audio/playback/AudioPlaybackManager.java` — Added `AliasModel` reference, filter-aware segment routing: linked segments respect filter, empty channel assignment checks `filter.accepts()` and `filter.isOff()`
- `audio/playback/AudioChannelPanel.java` — Added routing combo box (Off/All/Systems/Groups), per-channel mute button (replaced "M" label), right-click per-TG mute context menu, active-channel scoped alias list filtering
- `audio/playback/AudioChannelsPanel.java` — Passes `Supplier<Set<String>>` active alias list names through to `AudioChannelPanel`
- `audio/playback/AudioPanel.java` — Replaced global MuteButton with visible compact vertical volume slider (gain control), added `syncVolumeSlider()` on config change
- `controller/ControllerPanel.java` — Passes active alias list names supplier from `ChannelProcessingManager` to `AudioPanel`
- `controller/channel/ChannelProcessingManager.java` — Added `getActiveAliasListNames()` returning alias list names from actively processing channels
- `gui/SDRTrunk.java` — Wired `aliasModel` to `AudioPlaybackManager` via `setAliasModel()`

### Behavior
- Each audio channel has a routing filter combo box: Off (no audio), All (default, all segments), System (filter by alias list), Group (filter by alias group)
- Routing combo only shows alias lists from actively processing channels (not all configured aliases)
- Per-channel mute button: each audio channel has its own mute/unmute icon button (independent of other channels)
- Per-talkgroup mute via right-click context menu — outputs silence instead of discarding segments
- Visible volume slider on the right side of the audio panel (replaces old global mute button)
- Volume slider controls gain via FloatControl, double-click resets to 0 dB, syncs on audio device change
- Default behavior unchanged: all channels default to ALL mode

### Documentation
- `doc/changes/007_audio_channel_routing.md` — Detailed change doc

---

## [2026-03-20] Change 008: Patch Call Duplicate Detection & Events Column

### Modified Files (3)
- `audio/DuplicateCallDetector.java` — Rewrote `isDuplicate(List<Identifier>, List<Identifier>)` to handle all combinations of TalkgroupIdentifier and PatchGroupIdentifier comparisons: TG↔TG (unchanged), TG↔PatchGroup (match supergroup ID or any member TG), PatchGroup↔TG (reverse), PatchGroup↔PatchGroup (supergroup match or overlapping members), Radio↔Radio (unchanged)
- `module/decode/event/DecodeEventModel.java` — Added `COLUMN_PATCH_GROUP` (index 7) between To Alias and Channel columns, updated all subsequent column indices
- `module/decode/event/DecodeEventPanel.java` — Added `PatchGroupCellRenderer` displaying `P:<supergroup> [<member1>, <member2>, ...]` for patch group calls; added `formatPatchGroupForSave()` for CSV export; updated CSV header and save writer

### Behavior
- When duplicate call detection by talkgroup is enabled, individual calls to member talkgroups of an active patch group are now detected as duplicates and suppressed (audio flagged as duplicate, consumer count decremented)
- Example: Patch group P:00149[01085,01087,01089] is active → separate traffic channel grants to 01085, 01087, 01089 are flagged as duplicates of the patch call
- New "Patch Group" column in Events table shows patch group details for patch calls, empty for regular calls
- CSV save exports include the Patch Group column

### Documentation
- `doc/changes/008_patch_call_duplicate_detection.md` — Detailed change doc

---

## [2026-03-20] Change 010: Phase 2 — Calls Tab UI + Call Log SQLite Database

### New Files (6)
- `calllog/CallLogRecord.java` — POJO for call_sessions table row
- `calllog/CallEventRecord.java` — POJO for call_events table row
- `calllog/CallLogDatabase.java` — SQLite JDBC database manager (schema, insert, WAL mode)
- `calllog/CallLogWriter.java` — CallSessionListener → converts sessions to records, writes to DB
- `module/decode/session/ui/CallSessionModel.java` — Swing AbstractTableModel for call session events
- `module/decode/session/ui/CallSessionPanel.java` — JPanel with JTable, cell renderers, processing chain listener

### Modified Files (3)
- `build.gradle` — Added `org.xerial:sqlite-jdbc:3.47.2.0` dependency
- `channel/metadata/NowPlayingPanel.java` — Added "Calls" tab between Details and Events
- `module/decode/p25/P25TrafficChannelManager.java` — Creates CallLogWriter, registers as listener, start/stop lifecycle

### Behavior
- New "Calls" tab in NowPlaying panel shows real-time per-talker call session events
- Completed call sessions persisted to SQLite at `~/SDRTrunk/call_logs/{system}_calls.db`
- Schema: `call_sessions` and `call_events` tables with full indexing and WAL mode
- CallLogWriter auto-starts/stops with P25TrafficChannelManager lifecycle

### Documentation
- `doc/changes/010_phase2_calls_tab_and_calllog_db.md` — Detailed change doc

---

## [2026-03-21] Change 010 Phase 3: Call Session Manager — Direct Wiring

### Modified Files (4)
- `module/decode/p25/P25ChannelGrantEvent.java` — Added `channelSourceType` field to builder pattern with `channelSourceType()` setter and propagation in `build()`
- `module/decode/p25/P25TrafficChannelManager.java` — Wired call session manager with filter settings, alias list, and decode event listener at construction; removed passive observer from `broadcast()`; added traffic-side forwarding (`onTrafficChannelUpdate/End`) to P2 traffic methods; made `convertPhase2ToPhase1Channel()` public
- `module/decode/p25/phase1/P25P1DecoderState.java` — Redirected `processControlTrafficGrant()` and `processControlAnnouncedTrafficUpdate()` to route through `getCallSessionManager().processChannelGrant/Update()` instead of direct traffic manager calls
- `module/decode/p25/phase2/P25P2DecoderState.java` — Redirected all 17 `processP2ChannelGrant()` and `processP2ChannelUpdate()` calls to route through `getCallSessionManager()`

### Behavior
- Control channel grants/updates now flow: DecoderState → CallSessionManager → TrafficChannelManager pool API
- Traffic channel updates now forwarded: TrafficChannelManager → CallSessionManager.onTrafficChannelUpdate/End()
- Removed passive observer pattern (onDecodeEvent) — call session manager is now the authoritative routing layer
- Old deprecated control-channel methods retained in TrafficChannelManager but no longer called (future cleanup)

### Documentation
- `doc/changes/010_phase3_call_session_wiring.md` — Detailed change doc

---

## [2026-03-21] Change 010 Phase 4: Event Broadcasting Consolidation

### Modified Files (2)
- `module/decode/p25/P25TrafficChannelManager.java` -- Removed `broadcast(tracker)` from 13 traffic-side methods; traffic methods still update tracker state and forward to CSM but no longer broadcast to Events tab
- `module/decode/p25/session/P25CallSessionManager.java` -- Added `broadcastTrafficUpdate()` method that re-broadcasts cached control event with updated duration/identifiers; called at end of `onTrafficChannelUpdate()`

### Behavior
- CSM is now sole authority for Events tab broadcasting (eliminates duplicate rows)
- Control-side broadcasting in TCM preserved (channel allocation, initial event creation)
- Traffic updates flow: TCM updates tracker (no broadcast) -> CSM.onTrafficChannelUpdate() -> CSM.broadcastTrafficUpdate() re-broadcasts cached control event with current duration/IDs
- Handles P1 timeslot mismatch (control=0, traffic=1) with fallback key lookup

### Documentation
- `doc/changes/010_phase4_event_consolidation.md` -- Detailed change doc

---

## [2026-03-21] Change 012: Traffic Channel Fixes (In Progress)

### Modified Files (1)
- `module/decode/p25/P25TrafficChannelManager.java` -- Added `releaseTrafficChannel(long frequency)` stub method for CSM patch-call consolidation

### Context
- P25CallSessionManager's patch-call duplicate detection calls `releaseTrafficChannel()` to deallocate duplicate traffic channels when same radio ID is granted to different talk groups (implicit patch detection for Motorola LSM systems)
- Method is currently a stub (logs only); full channel deallocation is TODO
- Future work: implement actual channel pool release, audio routing consolidation for patched calls, and handling of traffic events that corrupt control channel identifier collections

### Documentation
- `doc/changes/012_traffic_channel_fixes.md` -- Detailed change doc
- `doc/design/012_traffic_channel_architecture_analysis.md` -- Architecture analysis

---

## [2026-03-21] Change 013: Call Session FROM-Radio Splitting, Events Status Column & Duplicate Event Fix

### New Files (1)
- `module/decode/event/EventStatus.java` — Enum with four states: ACTIVE_CONTROL (yellow), ACTIVE_TRAFFIC (green), ENDED (red), IGNORED (gray)

### Modified Files (7)
- `module/decode/session/CallSession.java` — Added `mCurrentFromRadio` field for tracking which radio is transmitting; `isMatch()` now vetoes same-call when FROM radio differs
- `module/decode/p25/session/P25CallSessionManager.java` — FROM radio extraction in grant/update processing; session splitting when FROM changes; traffic channel fallback splitting
- `module/decode/p25/P25ChannelGrantEvent.java` — Added `mEventStatus` field (default: ACTIVE_CONTROL) with getter/setter
- `module/decode/p25/P25TrafficChannelEventTracker.java` — Status transitions: `updateDurationTraffic()` → ACTIVE_TRAFFIC, `completeTraffic()` → ENDED, `setDetails()` → auto-detects IGNORED keywords
- `module/decode/p25/P25TrafficChannelManager.java` — Added IGNORED auto-detection in `broadcast(DecodeEvent)` for builder-created events; fixed `processP1TrafficCurrentUser` to update existing tracker instead of creating duplicate events; fixed `processP1ControlAnnouncedTrafficUpdate` to avoid double CSM call
- `module/decode/event/DecodeEventModel.java` — Added Status column at index 0 (all others shifted +1)
- `module/decode/event/DecodeEventPanel.java` — Added `StatusCellRenderer` rendering 10×10 colored circles; Status column 24px wide

### Behavior — Issue 1: FROM Radio Session Splitting
- Call sessions now represent single-user transmissions (per PTT)
- When FROM radio changes on same talkgroup/frequency, a new session is created
- Uses "Option C" hybrid approach: TDU → ENDING, different FROM → new session, same FROM within gap → reactivate
- Fixes inflated call durations where dispatch + field units on same TG were merged

### Behavior — Issue 2: Duplicate Event Fix
- Fixed race condition where both control channel and traffic channel created separate P25ChannelGrantEvent objects for the same call, causing 4 events instead of 2 in the Events tab
- `processP1TrafficCurrentUser` now updates existing tracker when FROM differs instead of creating a new event — defers new event creation to control channel's `isDifferentTalker` logic
- `processP1ControlAnnouncedTrafficUpdate` no longer double-calls CSM when a different call is detected — `processP1ControlDirectedChannelGrant` handles the CSM notification

### Behavior — Issue 3: Events Status Column
- New leftmost column in Events tab with color-coded status dots
- Green = active traffic channel processing, Yellow = control channel tracking only
- Red = call ended, Gray = ignored (encrypted/unmonitored/max channels)
- Non-P25 events show blank; status updates dynamically as events progress

### Documentation
- `doc/changes/013_call_session_and_event_fixes.md` — Detailed change doc
- `doc/design/013_call_session_and_event_fixes.md` — Design analysis (3 issues)

---

## [2026-03-25] Change 017: Data Capture Improvements

### Modified Files (3)
- `module/decode/p25/data/P25DataCaptureModule.java` — Added SACCH idle noise filters (MotorolaUnknownOpcode135 suppressed entirely, MotorolaTDMADataChannel deduplicated per timeslot); added `mChannelFrequency` and `mChannelDescriptor` fields with setters; all CapturedPayload builders now include `.frequency()` and `.channel()`; enhanced protocol detection via details-string fallback on packet/SNDCP/PDU methods
- `module/decode/p25/data/PayloadStringScanner.java` — Added `detectProtocolFromDetails(String)` method recognizing Motorola UDP ports (4001→LRRP, 4005→ARS, 64414→XCMP) and keywords (LRRP, ARS+REGISTRATION, SNDCP+ACTIVATE/DEACTIVATE)
- `module/decode/DecoderFactory.java` — Wired `channelDescriptor.getDownlinkFrequency()` and `channelDescriptor.toString()` to P25DataCaptureModule for traffic channels (both P25 Phase 1 and Phase 2)

### Behavior
- **Priority 1 — SACCH idle noise filter:** MotorolaUnknownOpcode135 messages (opcode 0x87, SACCH idle fill repeating ~350ms) are completely suppressed; MotorolaTDMADataChannel (opcode 0x8B) IDLE messages are deduplicated per timeslot — only emitted when details change. Reduces ~85% of MAC noise on Phase 2 systems.
- **Priority 2 — Frequency/channel metadata:** Traffic channel payloads now include the source frequency (Hz) and channel descriptor string, enabling correlation of captured data with specific traffic channels and call events.
- **Priority 3 — Enhanced protocol detection:** Packets previously showing "UNKNOWN" or generic "IPv4" are now identified as LRRP, ARS, XCMP, or SNDCP based on well-known port numbers and keywords in the decoded message details string.

### Documentation
- `doc/changes/017_data_capture_improvements.md` — Detailed change doc
- `doc/design/017_data_capture_improvements.md` — Design document

---

## [2026-03-25] Change 024: Signal Analyzer Tab — Phase 1: Detection UI

**Implemented the Signal Analyzer feature — FFT-based signal detection, tracking, and artifact flagging.**

### New Files (13)
- `gui/analyzer/AnalysisStatus.java` — Signal lifecycle states (DETECTED, CONFIRMED, LOST, IGNORED)
- `gui/analyzer/ModulationType.java` — Placeholder enum for Phase 2 modulation classification
- `gui/analyzer/SignalAnalyzerConfig.java` — Tunable detection parameters (threshold, min BW, averaging, etc.)
- `gui/analyzer/SignalAnalyzerController.java` — Main orchestrator implementing DFTResultsListener + ISourceEventProcessor
- `gui/analyzer/SignalAnalyzerPanel.java` — Main panel assembling control, table, and log components
- `gui/analyzer/detection/SignalFlag.java` — Artifact classification flags (DC_OFFSET, HARMONIC, IMAGE, WEAK, WIDEBAND)
- `gui/analyzer/detection/DetectedSignal.java` — Data model for detected signals
- `gui/analyzer/detection/SignalDetector.java` — Core FFT peak finder with noise floor estimation and frame averaging
- `gui/analyzer/detection/SignalTracker.java` — Merges detections with tracked signals using frequency proximity
- `gui/analyzer/detection/HarmonicAnalyzer.java` — DC spike, harmonic, and image frequency flagging
- `gui/analyzer/ui/SignalAnalysisTableModel.java` — Table model for signal display
- `gui/analyzer/ui/AnalyzerControlPanel.java` — Start/Stop/Clear controls, threshold slider, mode toggle
- `gui/analyzer/ui/AnalysisLogPanel.java` — Color-coded scrolling analysis log

### Modified Files (3)
- `spectrum/SpectralDisplayPanel.java` — Added addDftResultsListener/addSourceEventProcessor for external tap
- `controller/ControllerPanel.java` — Added Signal Analyzer tab with FontAwesome SIGNAL icon
- `gui/SDRTrunk.java` — Wired controller as DFTResultsListener + ISourceEventProcessor

### Detection Algorithm
- Noise floor estimation from median of lower FFT bins
- Peak detection above configurable threshold (default 10 dB above noise floor)
- Frame averaging (8 frames) to smooth transients
- Signal tracking with frequency proximity matching
- Artifact flagging: DC offset, harmonics, image frequencies, weak/wideband signals

### Documentation
- `doc/changes/024_signal_analyzer.md` — Detailed change doc

---

## [2026-03-25] Design 024: Signal Analyzer Tab (Design Document)

**Comprehensive design document for automated signal detection, characterization, and protocol identification.**

A new "Signal Analyzer" tab in the main application window providing a 4-layer analysis pipeline:
1. **Signal Detection** — FFT peak finding from existing DFT data pipeline (4096-bin spectrum)
2. **Signal Validation** — Harmonic/image/DC spur/noise filtering with automated retune tests
3. **Signal Characterization** — I/Q sample analysis for modulation classification (FM, 2FSK, 4FSK/C4FM, PSK, AM), symbol rate estimation (300–12000 baud), bandwidth measurement
4. **Protocol Identification** — Automated decoder trials using SDRTrunk's existing decoders (P25, DMR, LTR, MPT1327, NBFM, AM, etc.) with sync/message scoring

Features:
- **Auto + Manual modes** — continuous scanning or user-directed per-signal analysis
- **Signal detail panel** — per-signal channel spectrum view (inspired by Now Playing → Channel tab)
- **Harmonic/spur detection** — DC spike, image mirror, harmonic relationship flagging
- **Modulation classification** — envelope analysis, instantaneous frequency, symbol rate estimation
- **Decoder trial engine** — temporary channel creation, dwell-based sync/message detection
- **CSV logging** — timestamped signal analysis results
- **Right-click context menu** — Identify Now, Create Channel, Spur Test, Ignore

Target bands: 400 MHz, 700–900 MHz (and any tuner-visible bandwidth)

Design document: `doc/design/024_signal_analyzer.md`
Implementation: 3 phases (Phase 1: Detection UI, Phase 2: Characterization, Phase 3: Identification + Logging)

---

## Pending / Future

- [x] Signal Analyzer Tab — Phase 1: Tab UI + Signal Detection ✅ (2026-03-25)
- [ ] Signal Analyzer Tab — Phase 2: Signal Characterization (modulation analysis)
- [ ] Signal Analyzer Tab — Phase 3: Protocol Identification + Logging
- [ ] Finalize PlutoSDR tuner integration with Maia IQ streaming
- [ ] DDC channelizer performance optimization
- [ ] P25 back-to-back transmission handling fix
- [ ] Merge upstream changes from DSheirer/sdrtrunk
- [ ] Test with Fishball Z7020 hardware
