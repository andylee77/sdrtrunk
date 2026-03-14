# Change 003: Ignore Encrypted Calls Option

**Date:** 2026-03-14
**Status:** Complete
**Source:** Migration from modified upstream (`sdrtrunk-master`)

## Summary

Added a configuration option to ignore encrypted calls in P25 channel decoding. When enabled,
encrypted channel grants on both Phase 1 and Phase 2 will still appear in the events list
(marked as "IGNORED: ENCRYPTED CALL") but will **not** consume a traffic channel from the pool.
This frees up traffic channels for unencrypted calls you can actually listen to.

## Files Modified

| File | Change |
|------|--------|
| `module/decode/p25/phase1/DecodeConfigP25.java` | Added `mIgnoreEncryptedCalls` boolean field with Jackson XML serialization (`ignore_encrypted_calls` attribute), getter, and setter |
| `gui/playlist/channel/P25P1ConfigurationEditor.java` | Added "Ignore Encrypted Calls" ToggleSwitch to the P25 Phase 1 decoder configuration panel, with load/save support |
| `module/decode/p25/P25TrafficChannelManager.java` | Added `mIgnoreEncryptedCalls` field, reads setting from config in constructor, filters encrypted channel grants in both `processPhase1ControlChannelGrant()` and `processPhase2ChannelGrant()` |

## Behavior

- **Off (default):** All channel grants are processed normally — encrypted calls consume traffic channels.
- **On:** When the control channel announces an encrypted channel grant, it is logged as an event with
  "IGNORED: ENCRYPTED CALL" in the details but no traffic channel is allocated. This preserves traffic
  channel pool slots for unencrypted calls.
- The setting is persisted in the playlist XML as `ignore_encrypted_calls="true/false"` on the decode
  configuration element.
- Works for both P25 Phase 1 and Phase 2 channel grants.
