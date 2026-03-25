# 013 — Call Session Definition, Traffic Channel Cleanup, and Events Status Column

## Status: IMPLEMENTED (Issues 1 & 3) — Issue 2 deferred for investigation

## Problems Observed (2026-03-21)

### 1. Call Session Definition — "A call is a single user transmission"

**Current behavior:** CSM's `isMatch()` matches on (frequency, timeslot, talkgroup). When
dispatch keys up on TG 01087, and then a field unit responds on the same TG 01087 on the
same frequency, CSM treats them as the SAME session because the talkgroup matches. The
second transmission's duration gets inflated because it's extending the first session rather
than creating a new one.

**Expected behavior:** A "call" is a single user (single FROM radio) transmission. When
the FROM radio changes, that's a NEW call, even if it's the same talkgroup on the same
frequency. This is a back-and-forth conversation, but each PTT is a separate call event.

**Root cause:** CSM's `isMatch()` and `isSameCallCheckingToOnly()` logic inherited from TCM's
event tracker approach. TCM consolidates events by TO talkgroup only (which is correct for
the Events tab — you want one row per channel grant, not one row per PTT). But for the
Calls tab, we want per-transmission granularity.

**Key distinction:**
- **Events tab (TCM)**: One row per channel grant. Same TG on same freq = same event.
  FROM radio changes are just updates. This is the upstream behavior and is CORRECT.
- **Calls tab (CSM)**: One row per PTT transmission. Same TG but different FROM = new call.
  The call ends when TDU arrives (or control channel stops sending grants).

**What needs to change in CSM:**
- `isMatch()` should factor in FROM radio identity when available
- When a grant arrives with a DIFFERENT FROM radio than the current session, that should
  trigger a new session (transition old to ENDING, create new)
- The `reactivateFromEnding()` logic also needs to consider FROM radio — if the FROM changed,
  don't reactivate, start fresh
- Edge case: initial grants often don't include FROM radio. The FROM arrives later via
  traffic channel LDU/HDU messages. Need to handle the case where:
  1. Grant arrives with TO only (no FROM) → create session
  2. Traffic channel reports FROM=59207 → update session
  3. TDU → session goes to ENDING
  4. New grant arrives with same TO, no FROM yet → this could be same or different user
  5. Traffic channel reports FROM=59207 (same) → same call continues
  6. Traffic channel reports FROM=OTHER → should have been a new session

**Possible approaches:**
- **Option A: Split on FROM change from traffic channel.** When `onTrafficChannelUpdate()`
  detects a FROM that differs from the current session's FROM, end the current session and
  create a new one. This is the most accurate but requires traffic channel to provide FROM.
- **Option B: Split on TDU + new grant.** Every TDU finalizes the session. New grants always
  create new sessions. This is simpler but may over-split (PTT releases within the same
  user's transmission would create multiple sessions).
- **Option C: Hybrid.** TDU transitions to ENDING. If next grant arrives with SAME FROM
  within gap tolerance, reactivate. If DIFFERENT FROM or no FROM info, start new session.

**Recommendation:** Option C (hybrid). This matches the P25 protocol semantics:
- TDU = end of this user's transmission
- New grant with different FROM = different user's response
- New grant with same FROM within gap = same user continuing (mic bump, etc.)

### 2. Traffic Channels Not Clearing

**Observed:** Traffic channels (shown in the channel panel) remain allocated after calls end.
They process occasional data but aren't releasing back to the pool.

**Likely causes:**
- The `TrafficChannelTeardownMonitor` only reclaims channels when it receives
  `NOTIFICATION_PROCESSING_STOP` events. If the traffic channel's processing chain doesn't
  stop (because it's still receiving intermittent data), the channel stays allocated.
- The traffic channel's internal decoder may keep the processing chain alive as long as
  ANY frames are being decoded, even if the voice call has ended.
- The upstream `P25P1DecoderState` may have a fade timeout that keeps the channel alive
  for a period after the last message. If data packets arrive before the fade timeout
  expires, the timeout resets and the channel never releases.

**Investigation needed:**
- Check `P25P1DecoderState.fade()` and `P25P1DecoderState.reset()` — what triggers
  channel teardown on traffic channels?
- Check `ChannelProcessingManager` — what causes a traffic channel to stop processing?
- Check whether data activity on a voice traffic channel resets the fade timer
- This may be upstream behavior (not our bug) but worth understanding

### 3. Events Tab — Status Column with Color-Coded Indicators

**Feature request:** Add a "Status" column to the Events tab (leftmost column) that shows
a color-coded indicator for each event, similar to how the channel panel shows channel state.

**Purpose:** Since not all calls get traffic channels allocated (encrypted, unmonitored, max
channels exceeded), the user wants visual feedback about what the system is actually doing
with each event:

| Color | Meaning |
|-------|---------|
| Green | Active — traffic channel allocated and processing |
| Yellow | Active — control channel tracking only (no traffic channel) |
| Red | Ended — call complete |
| Gray | Ignored — encrypted/unmonitored/data filtered |

**Implementation approach:**
- Add a `status` field to `P25ChannelGrantEvent` or the tracker
- TCM can set the status based on whether allocation succeeded
- The `DecodeEventModel` (table model for Events tab) needs a new column at index 0
- Custom cell renderer to paint the color dot/icon
- Status updates when: channel allocated (→ green), channel rejected (→ yellow),
  call completes (→ red), call ignored (→ gray)

**Challenges:**
- The Events tab uses `IDecodeEvent` interface — need to add status without breaking
  the interface for all decoder types
- Status needs to update dynamically as the event progresses — the table model needs
  to fire cell update notifications for the status column
- Events from non-P25 decoders won't have this info — show as blank or default

## Files Likely Affected

### Issue 1 (Call Session Definition)
- `P25CallSessionManager.java` — `isMatch()`, `processPhase1Grant()`,
  `processP2ChannelGrantInternal()`, `onTrafficChannelUpdate()`, `reactivateFromEnding()`
- `CallSession.java` — may need `getCurrentFromRadio()` field

### Issue 2 (Traffic Channel Clearing)
- `P25P1DecoderState.java` — fade/reset logic for traffic channels
- `P25TrafficChannelManager.java` — `TrafficChannelTeardownMonitor`
- `ChannelProcessingManager.java` — channel lifecycle

### Issue 3 (Events Status Column)
- `DecodeEventModel.java` — add Status column
- `DecodeEventPanel.java` — column renderer
- `P25ChannelGrantEvent.java` — add status field
- `P25TrafficChannelEventTracker.java` — track allocation status
- `P25TrafficChannelManager.java` — set status on allocation/rejection

## Priority

1. **Issue 1** (Call Session Definition) — High. Causes incorrect call log entries.
2. **Issue 3** (Status Column) — Medium. UI visibility improvement.
3. **Issue 2** (Traffic Channel Clearing) — Medium. May be upstream behavior requiring
   deeper investigation.
