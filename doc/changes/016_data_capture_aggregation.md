# Change 016: Data Capture Aggregation & Per-System Logging

## Date
2026-03-24

## Summary
Enhanced the P25 Data Capture system with two major features:
1. **Traffic→Control Aggregation**: All data captured on traffic channels is now forwarded
   to the control channel's Data tab, so you see everything in one place without clicking
   on individual traffic channels.
2. **Per-System Log Files**: Corpus logs now include the system name and date in the filename
   (e.g., `logs/p25_data_MySystem_20260324.jsonl`), keeping data separate between systems.

Also fixed compile errors in Phase 2 MAC message handling that were using wrong API calls.

## Files Changed

### `src/main/java/io/github/dsheirer/module/decode/p25/data/P25DataCaptureModule.java`
- **Fixed**: Phase 2 MAC message processing — use `MacStructure.getOpcode()` instead of
  non-existent `MacPduType.getMacOpcode()`
- **Fixed**: Use `UnknownMacStructure`/`UnknownVendorMessage` instead of non-existent `UnknownMacMessage`
- **Added**: `setParentModule()` / `getParentModule()` — traffic channel modules forward all
  captured payloads to the parent (control channel) module
- **Added**: `receiveFromChild()` — entry point for receiving forwarded payloads from traffic channels
- **Added**: `setSystemName()` / `getSystemName()` — per-system log file naming
- **Changed**: Logging from SLF4J static logger to per-instance `PrintWriter` with date rotation
  - File pattern: `logs/p25_data_<systemName>_<YYYYMMDD>.jsonl`
  - Auto-rotates at midnight
  - Auto-creates `logs/` directory

### `src/main/java/io/github/dsheirer/module/decode/p25/P25TrafficChannelManager.java`
- **Added**: `mDataCaptureModule` field to hold control channel's P25DataCaptureModule reference
- **Added**: `setDataCaptureModule()` / `getDataCaptureModule()` accessor methods

### `src/main/java/io/github/dsheirer/module/decode/DecoderFactory.java`
- **Phase 1**: Control channel stores its data capture module on the TCM; traffic channels
  look it up from TCM and set as parent module
- **Phase 2**: Same pattern — control stores, traffic looks up parent
- **Both**: System name set from `channel.getSystem()` (or channel name as fallback)

## Architecture

```
Control Channel                    Traffic Channel 1        Traffic Channel N
┌─────────────────┐               ┌──────────────────┐    ┌──────────────────┐
│ P25DataCapture   │◄──────────── │ P25DataCapture    │    │ P25DataCapture    │
│ Module (parent)  │  forward     │ Module (child)    │    │ Module (child)    │
│                  │              │ parentModule=ctrl  │    │ parentModule=ctrl  │
│ - logToCorpus()  │              └──────────────────┘    └──────────────────┘
│ - UI listeners   │
│ - Data tab       │
└─────────────────┘
```

- Traffic channel modules call `emit()` → detects `mParentModule != null` → calls
  `parentModule.receiveFromChild(payload)` instead of local distribution
- Control channel module receives from all children, logs to corpus, and distributes to UI
- Only one log file per system (not per traffic channel)

## Log File Naming
- **Before**: Single `logs/p25_data_capture.jsonl` for everything
- **After**: `logs/p25_data_<SystemName>_<YYYYMMDD>.jsonl`
  - System name sanitized (non-alphanumeric → underscore)
  - Date rotates at midnight
  - Example: `logs/p25_data_County_Fire_20260324.jsonl`
