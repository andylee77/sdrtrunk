# SDRTrunk — Changelog (andylee77 fork)

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

## Pending / Future

- [ ] Finalize PlutoSDR tuner integration with Maia IQ streaming
- [ ] DDC channelizer performance optimization
- [ ] P25 back-to-back transmission handling fix
- [ ] Merge upstream changes from DSheirer/sdrtrunk
- [ ] Test with Fishball Z7020 hardware
