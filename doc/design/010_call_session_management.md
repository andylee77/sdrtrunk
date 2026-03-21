# 010 — P25 Call Session Management

## Date
2026-03-20

## Status
Design — Not Yet Implemented

## Summary

Introduce a **Call Session** as the single authority for managing P25 calls, replacing
the current architecture where events, audio, recording, and streaming each independently
process messages and make their own decisions about call boundaries.

The Call Session model follows the **P25 channel grant lifecycle** (protocol-driven) rather
than the SDRTrunk audio segment/event model (implementation-driven), with a single manager
that coordinates all downstream consumers.

---

## Problem Statement

### Current Architecture: Multiple Independent Authorities

The current system has no unified concept of a "call." Instead, several independent subsystems
each receive messages and independently decide what constitutes a call:

```
P25 Messages
    ├── P25P1DecoderState ──→ P25TrafficChannelManager ──→ DecodeEvent (Events table)
    ├── P25P1AudioModule  ──→ AudioSegment ──→ DuplicateCallDetector ──→ Playback
    ├── P25P1AudioModule  ──→ AudioSegment ──→ AudioStreamingManager ──→ Streaming
    └── P25P1AudioModule  ──→ AudioSegment ──→ RecordingModule ──→ Recording
```

Each subsystem independently:
- Decides when a call starts and ends
- Manages its own staleness thresholds
- Creates its own data objects (DecodeEvent, AudioSegment)
- Has its own identity matching logic

### Resulting Problems

| Problem | Root Cause |
|---------|-----------|
| **Fragmented events** — Same call appears as multiple rows in Events table | `P25TrafficChannelEventTracker` has a 2-second staleness threshold. Any gap in control channel updates causes a new event row for the same logical call. |
| **Duplicate audio** — Patch group calls play multiple times | `DuplicateCallDetector` operates on AudioSegments independently of event tracking. Patch group member matching was added but operates at a different layer than event tracking. |
| **Missing patch group members** — Events show "P:00149" but no member list | `PatchGroupManager` enrichment has a 30-second staleness threshold and depends on timing of Add commands. Traffic channel messages never carry member info. Each subsystem queries the PatchGroupManager at different times. |
| **Recording fragmentation** — One call produces multiple recording files | AudioSegment boundaries don't align with logical call boundaries. |
| **Inconsistent state** — Events table shows different info than audio panel | Events and audio are tracked by different objects with different lifecycle logic. |
| **No call continuity across gaps** — Brief packet loss = new call | Each subsystem independently decides a gap means a new call. |

### Why This Happens: No Single Authority

The fundamental issue is there is **no single object that represents a call**. The P25
protocol defines calls clearly through channel grants, but SDRTrunk's implementation
distributes call management across multiple independent systems that each see part of
the picture and make local decisions.

---

## Design: Protocol-Driven Call Sessions

### Core Principle

**Follow the P25 channel grant model.** In P25, a call is defined by:
1. A **channel grant** message assigns a traffic channel to a talkgroup/patch group
2. The traffic channel carries **voice/data** for that call
3. The call ends when the traffic channel signals **call termination** (TLC/TDULC)
4. The same channel may be **re-granted** to the same or different talkgroup

A Call Session directly maps to this protocol model. It is the single source of truth
for everything about a call.

### Architecture

```
P25 Messages
    │
    ▼
P25P1DecoderState ──→ P25CallSessionManager (SINGLE AUTHORITY)
                           │
                           ├── owns ──→ CallSession
                           │               ├── identity (freq, timeslot, talkgroup, patchgroup)
                           │               ├── accumulated identifiers (FROM radios, encryption, etc.)
                           │               ├── audio buffers (all audio for this call)
                           │               ├── timing (start, end, last activity)
                           │               └── state (PENDING → ACTIVE → ENDING → COMPLETE)
                           │
                           ├── notifies ──→ DecodeEventModel (one row per call)
                           ├── notifies ──→ AudioPlaybackManager (one stream per call)
                           ├── notifies ──→ RecordingModule (one file per call)
                           ├── notifies ──→ AudioStreamingManager (one stream per call)
                           └── notifies ──→ DuplicateCallDetector (call-level, not segment-level)
```

### CallSession Object

```java
/**
 * Represents a single P25 call — the authoritative object for all call-related
 * data and decisions. All downstream consumers (events, audio, recording,
 * streaming) reference this object rather than maintaining their own state.
 *
 * A CallSession follows the P25 channel grant lifecycle:
 * - Created when a channel grant is received
 * - Updated as voice/data messages arrive on the traffic channel
 * - Enriched over time with patch group members, encryption info, talker IDs
 * - Ended when call termination is signaled OR gap threshold is exceeded
 */
public class CallSession
{
    // === Identity (what defines this call) ===
    private long mFrequency;
    private int mTimeslot;
    private Identifier mTalkgroup;              // TalkgroupIdentifier or PatchGroupIdentifier
    private PatchGroupIdentifier mPatchGroup;   // enriched over time as Add commands arrive

    // === Accumulated state (grows over call lifetime) ===
    private MutableIdentifierCollection mIdentifiers;  // all identifiers seen during call
    private List<RadioIdentifier> mTalkers;            // all FROM radios (in order)
    private EncryptionKeyIdentifier mEncryption;
    private IChannelDescriptor mChannelDescriptor;
    private ServiceOptions mServiceOptions;

    // === Timing ===
    private long mCallStart;                // timestamp of first message
    private long mCallEnd;                  // timestamp of last message (updated continuously)
    private long mLastActivityTimestamp;     // last message of any kind (for gap detection)

    // === State machine ===
    private CallState mState;               // PENDING, ACTIVE, ENDING, COMPLETE

    // === Audio ===
    private List<float[]> mAudioBuffers;    // all decoded audio for this call
    private boolean mHasAudio;

    // === Protocol event details ===
    private DecodeEventType mEventType;
    private String mDetails;

    // === Display event (single row in Events table) ===
    private P25ChannelGrantEvent mDisplayEvent;  // the one event shown in UI

    // === Consumers tracking ===
    private boolean mDuplicate;
    private boolean mRecording;
    private boolean mStreaming;
}
```

### CallState Lifecycle

```
     Channel Grant received
            │
            ▼
    ┌──────────────┐
    │   PENDING    │  Waiting for voice/data on traffic channel
    │              │  (brief delay to collect initial burst of info)
    └──────┬───────┘
           │  First voice frame / LDU1 received
           ▼
    ┌──────────────┐
    │   ACTIVE     │  Call in progress — audio playing, recording, etc.
    │              │  Identifiers enriched as new info arrives
    │              │  Duration continuously updated
    └──────┬───────┘
           │  TLC/TDULC received OR gap threshold exceeded
           ▼
    ┌──────────────┐
    │   ENDING     │  Grace period — same TG on same channel within
    │              │  gap threshold re-activates (back to ACTIVE)
    │              │  Prevents fragmentation from brief gaps
    └──────┬───────┘
           │  Gap threshold exceeded with no new activity
           ▼
    ┌──────────────┐
    │  COMPLETE    │  Call finalized — recording closed, event finalized
    │              │  Session removed from active tracking
    └──────────────┘
```

### P25CallSessionManager

The single authority. All P25 call-related decisions flow through this manager.

```java
/**
 * Central authority for P25 call management. Replaces the distributed decision-making
 * currently split across P25TrafficChannelManager, AudioModule, DuplicateCallDetector,
 * and RecordingModule.
 *
 * Responsibilities:
 * - Create and track CallSessions based on channel grants
 * - Merge messages into the correct CallSession
 * - Manage call lifecycle (PENDING → ACTIVE → ENDING → COMPLETE)
 * - Coordinate with PatchGroupManager for enrichment
 * - Notify downstream consumers (events, audio, recording, streaming)
 * - Handle duplicate call detection at the call level
 * - Manage traffic channel allocation (absorbs P25TrafficChannelManager's role)
 */
public class P25CallSessionManager
{
    // Active calls indexed by (frequency, timeslot)
    private Map<Long, Map<Integer, CallSession>> mActiveSessions;

    // Recently completed calls for gap-tolerance merging
    private Map<Long, Map<Integer, CallSession>> mEndingSessions;

    // Downstream consumers notified of call lifecycle events
    private List<CallSessionListener> mListeners;

    // Configuration
    private long mGapToleranceMs = 3000;     // how long to wait before finalizing (2-3 sec)
    private long mPendingTimeoutMs = 500;    // how long to collect initial info

    // PatchGroupManager reference for enrichment
    private PatchGroupManager mPatchGroupManager;
}
```

### Key Behaviors

#### 1. Call Identity Matching

When a new message arrives, the session manager determines if it belongs to an existing call:

```
Message arrives with (frequency, timeslot, talkgroup, from_radio)
    │
    ├── Active session exists for (frequency, timeslot)?
    │       │
    │       ├── YES: Same talkgroup (or patch group overlap)?
    │       │       │
    │       │       ├── YES + same FROM radio (or no FROM yet):
    │       │       │       → Same call → merge into existing session
    │       │       │
    │       │       ├── YES + different FROM radio:
    │       │       │       → New event entry (new talker on same TG)
    │       │       │       → Session continues but starts new event row
    │       │       │
    │       │       └── NO: Different call → end current session, start new one
    │       │
    │       └── NO: Check ENDING sessions for (frequency, timeslot)
    │               │
    │               ├── Same talkgroup within gap threshold?
    │               │       │
    │               │       ├── YES: Reactivate → back to ACTIVE
    │               │       │
    │               │       └── NO: Finalize old, start new session
    │               │
    │               └── No ending session → start new session
    │
    └── Patch group matching:
            If talkgroup matches supergroup ID OR any patched member
            → same call (handles all the cases currently in DuplicateCallDetector)
```

#### 2. Patch Group Enrichment (Event-Driven)

Instead of a one-shot enrichment at message arrival time, the session manager
uses an **event-driven** approach — zero overhead, instant enrichment:

```
1. Channel grant arrives with PatchGroupIdentifier (supergroup only, no members)
   → CallSession created with bare PatchGroupIdentifier
   → PatchGroupManager.update() attempted — may or may not have members yet

2. Add command arrives on control channel (at any time):
   → PatchGroupManager.addPatchGroups() stores new member data
   → PatchGroupManager notifies session manager via listener
   → Session manager checks active sessions for matching supergroup ID
   → Matching sessions get enriched immediately
   → Display event updated in-place (Patch Group column fills in)

3. No Add commands = no overhead. No polling timer needed.
```

This solves the timing problem — even if the Add command arrives several seconds
after the channel grant, the session is enriched the moment the data arrives.
The hook point is the existing `PatchGroupManager.addPatchGroups()` call in
`P25P1DecoderState.processTSBK()`.

#### 3. Gap Tolerance

The key difference from the current system. Instead of a 2-second staleness timeout
that kills the event tracker:

```
Current (fragile):
  Message gap > 2 seconds → tracker removed → next message = NEW event

Proposed (resilient):
  Message gap → state moves to ENDING
  ENDING + same TG+radio within gap threshold (2-3 sec) → back to ACTIVE (same call!)
  ENDING + gap threshold exceeded → COMPLETE (call finalized)
  ENDING + different TG → COMPLETE old call, start new call
```

#### 4. Two-Tab UI: Calls Tab + Events Tab (Unchanged)

The existing **Events tab is not modified**. It continues to show raw protocol
events exactly as upstream produces them. This preserves compatibility and gives
a detailed low-level view.

A new **Calls tab** is added alongside Events. The Calls tab shows one row per
CallSession, with the linked events accessible:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Calls  │  Events  │  Messages  │                                      │
├─────────┴──────────┴────────────┴──────────────────────────────────────┤
│ Time     │ TG     │ Patch     │ Duration │ Talkers │ Channel    │ ... │
│ 14:03:01 │ 01085  │ 149,233   │ 0:12     │ 3       │ 856.4625   │     │
│ 14:02:45 │ 00233  │           │ 0:08     │ 1       │ 856.7125   │     │
│ 14:02:30 │ 01085  │ 149,233   │ 0:25     │ 2       │ 856.4625   │     │
└──────────┴────────┴───────────┴──────────┴─────────┴────────────┴─────┘
```

Expanding or double-clicking a call row shows its linked events (per-talker entries):

```
▼ 14:03:01 │ TG 01085 │ P:149,233 │ 0:12 │ 3 talkers │ 856.4625
    Event 1: FROM 1234567 → TG 01085  [14:03:01 - 14:03:04]  (recording_001.wav)
    Event 2: FROM 9876543 → TG 01085  [14:03:05 - 14:03:08]  (recording_002.wav)
    Event 3: FROM 1234567 → TG 01085  [14:03:09 - 14:03:12]  (recording_003.wav)
```

The **control channel** determines session boundaries (channel grant to termination).
Within that session, radio ID changes create linked event entries. The Calls tab
provides the unified view; the Events tab provides the raw detail:

- **Calls tab**: One row per call session, updated in-place during call
  - Duration, talker count, patch group info all update live
  - Patch Group column fills in when enrichment data arrives
  - Event Type may upgrade (e.g., GROUP_CALL → PATCH_GROUP_CALL)
- **Events tab**: Unchanged — raw events from upstream code, as many rows as the system produces
- **Linkage**: Each raw event in the Events tab carries a `sessionId` linking it to the Calls tab row

#### 5. Audio Management

The session manager **owns** the audio lifecycle for each call:

```
Current:
  AudioModule creates AudioSegments independently
  DuplicateCallDetector compares AudioSegments
  AudioPlaybackManager picks segments to play
  (None of these know about the Events table's view of the call)

Proposed:
  P25CallSessionManager creates CallSession
  AudioModule feeds decoded audio TO the CallSession
  CallSession buffers audio and notifies:
    - AudioPlaybackManager: "play this call's audio"
    - RecordingModule: "record this call's audio"
    - AudioStreamingManager: "stream this call's audio"
  Duplicate detection is at the CallSession level:
    - Before creating a new session, check if another session
      on a different channel has the same TG/patch group
    - If so, mark the new session as duplicate → no playback/recording
```

---

## Comparison: Current vs. Proposed

| Aspect | Current | Proposed |
|--------|---------|----------|
| **Authority** | Distributed — each subsystem decides independently | Single — P25CallSessionManager is the authority |
| **Call model** | SDRTrunk implementation-driven (AudioSegment, DecodeEvent) | P25 protocol-driven (channel grant → voice → termination) |
| **Call identity** | Per-event comparison with 2-sec staleness | Per-session with configurable gap tolerance |
| **Patch groups** | One-shot enrichment at message time | Continuous enrichment during call lifetime |
| **Audio** | AudioSegments created independently by AudioModule | Audio fed into CallSession, managed centrally |
| **Recording** | Per AudioSegment | Per CallSession (one file per logical call) |
| **Duplicate detection** | AudioSegment-level comparison | CallSession-level (knows about all active calls) |
| **UI** | Events tab only, multiple rows per call | New Calls tab (1 row/call, expandable) + Events tab unchanged |
| **Gap handling** | 2-sec gap = new event | Configurable gap tolerance with ENDING state |

---

## Implementation Phases

This is a significant architectural change. It should be phased to minimize risk and
allow testing at each stage.

### Phase 1: Call Session Foundation + Calls Tab

**Goal**: Introduce CallSession, P25CallSessionManager, and a new Calls tab alongside
existing code. Events tab is untouched — the new Calls tab shows the unified view.

**Changes**:
- New `CallSession` class
- New `P25CallSessionManager` class  
- New `CallSessionListener` interface
- New `CallSessionModel` (table model for Calls tab)
- New `CallSessionPanel` (UI panel for Calls tab, with expand/collapse per call)
- Intercept `P25TrafficChannelManager.broadcast(DecodeEvent)` to route through session manager
- Session manager creates CallSessions from channel grants, links raw events via `sessionId`
- PatchGroupManager enrichment via listener (event-driven)
- New Calls tab added to the channel tab pane (alongside Events and Messages)

**What stays the same**: Events tab unchanged. Audio, recording, streaming still use
existing AudioSegment flow. Traffic channel allocation stays in P25TrafficChannelManager.

**Testable outcome**: New Calls tab shows one row per call session with patch group
members that fill in over time. Expanding a call shows linked per-talker events.
Events tab continues showing raw events as before.

### Phase 2: Audio Integration

**Goal**: Route audio through CallSession instead of independent AudioSegments.

**Changes**:
- CallSession owns audio buffers
- AudioModule feeds decoded audio to CallSession (via session manager)
- Duplicate detection moves to CallSession level
- AudioPlaybackManager receives audio from CallSession
- Remove DuplicateCallDetector's independent comparison (now handled by session manager)

**Testable outcome**: One audio stream per logical call, no duplicates, audio
survives brief gaps.

### Phase 3: Recording & Streaming Integration

**Goal**: One recording file per call, one stream per call.

**Changes**:
- RecordingModule records from CallSession instead of AudioSegment
- AudioStreamingManager streams from CallSession
- Recording filename includes call identifiers (TG, timestamp)

**Testable outcome**: One .wav file per logical call. Streams follow call boundaries.

### Phase 4: Traffic Channel Manager Consolidation

**Goal**: Merge P25TrafficChannelManager's channel allocation into P25CallSessionManager.

**Changes**:
- Traffic channel pool management moves to session manager
- Channel grant processing flows directly to session creation
- Remove P25TrafficChannelEventTracker (replaced by CallSession)
- Remove P25ChannelGrantEvent creation from P25TrafficChannelManager

**Testable outcome**: Full unified call management with one authority.

---

## Key Design Decisions

### Q: Why not just fix the staleness threshold?

Changing the 2-second threshold to 5 seconds would reduce fragmentation but doesn't
solve the fundamental problem — there are still multiple independent systems making
call boundary decisions. A patch group member call on a different channel still won't
know it's a duplicate unless there's a central authority tracking all active calls.

### Q: Does this affect traffic channel allocation?

In Phases 1-3, no. Traffic channel allocation stays in P25TrafficChannelManager.
The session manager observes the events that come out of it. In Phase 4, the
session manager absorbs that role.

### Q: What about non-P25 protocols (DMR, NXDN)?

The CallSession concept is P25-specific in Phase 1. However, the architecture
could be generalized later with a protocol-agnostic `CallSession` base class
and protocol-specific managers.

### Q: How does this interact with our existing changes?

| Our Change | Impact |
|---|---|
| **Ignore encrypted calls** (#003) | Moves to session manager — check service options before creating session |
| **Ignore unmonitored calls** (#005) | Moves to session manager — check alias before creating session |
| **Patch call duplicate detection** (#008) | Absorbed into session manager's duplicate detection |
| **Audio channel routing** (#007) | AudioPlaybackManager receives from CallSession instead of AudioSegment |

### Q: What about the PatchGroupManager upstream bug?

The `PatchGroupManager.update()` PATCH_GROUP case has a bug where stale/version-mismatch
entries are `put()` then immediately `remove()`-ed (destroying the data). This should be
fixed as part of Phase 1 since it affects patch group enrichment. The fix is simply
removing the `remove()` calls after the `put()` calls in those two code paths.

---

## Files Affected (Estimated)

### Phase 1 (New + Modified)
| File | Change |
|------|--------|
| `module/decode/p25/P25CallSession.java` | **NEW** — CallSession object |
| `module/decode/p25/P25CallSessionManager.java` | **NEW** — Central call authority |
| `module/decode/p25/P25CallSessionListener.java` | **NEW** — Listener interface |
| `module/decode/p25/CallSessionModel.java` | **NEW** — Table model for Calls tab |
| `module/decode/p25/CallSessionPanel.java` | **NEW** — UI panel for Calls tab (expand/collapse) |
| `module/decode/p25/P25TrafficChannelManager.java` | Modified — route events through session manager |
| `module/decode/event/DecodeEventPanel.java` | Modified — add Calls tab alongside Events/Messages |
| `identifier/patch/PatchGroupManager.java` | Modified — fix put/remove bug, add listener support |
| `module/decode/p25/P25TrafficChannelEventTracker.java` | Eventually removed (Phase 4) |

### Phase 2 (Modified)
| File | Change |
|------|--------|
| `audio/AudioSegment.java` | Modified or wrapped |
| `audio/DuplicateCallDetector.java` | Simplified — session manager handles duplicates |
| `audio/playback/AudioPlaybackManager.java` | Modified — receive from CallSession |
| `module/decode/p25/audio/P25P1AudioModule.java` | Modified — feed audio to session |

### Phase 3 (Modified)
| File | Change |
|------|--------|
| `record/RecordingModule.java` | Modified — record from CallSession |
| `audio/broadcast/AudioStreamingManager.java` | Modified — stream from CallSession |

---

## Resolved Design Decisions

1. **Gap tolerance: 2-3 seconds.** Enough to bridge brief packet gaps without
   making calls feel sluggish. The current upstream uses 2 seconds but immediately
   kills the tracker — our ENDING state gives the same call a chance to resume
   within the same window, which is the key improvement.

2. **Split event rows by radio ID.** Different FROM radio = different event row
   in the Events table, different recording file. This gives clean per-talker
   entries for logging. The CallSession tracks the overall TG conversation
   (channel grant to termination), but creates separate event entries per talker.

3. **Control channel is the authority.** The control channel's channel grant
   and channel update messages define the call session boundaries. Traffic
   channel messages (LDU, HDU, TLC) provide audio and fine-grained timing,
   but the control channel determines when a call starts, what TG it's on,
   and when the channel is re-granted or terminated. This is the P25
   protocol model and is the right place to make call boundary decisions.

4. **Upstream compatibility.** Phases 1-3 sit alongside existing code (intercept
   pattern), minimizing merge conflicts with upstream. Phase 4 consolidation
   is optional and only done once the design is proven.

5. **Two-tab UI: Calls tab + Events tab unchanged.** The existing Events tab
   stays exactly as upstream produces it — raw protocol events, multiple rows
   per call, no modifications. A new Calls tab is added alongside it showing
   one row per CallSession with expandable per-talker detail. This preserves
   upstream compatibility, gives a low-level debug view (Events), and provides
   the clean unified view (Calls). Events carry a `sessionId` linking them
   to their parent call in the Calls tab.

6. **Patch group enrichment: event-driven, not polling.** Rather than a periodic
   timer polling the PatchGroupManager, we use an event-driven approach:
   whenever the control channel decoder processes a `MOTOROLA_OSP_GROUP_REGROUP_ADD`
   message, the PatchGroupManager stores the member data. We add a listener so
   that when new patch group data arrives, the session manager is notified and
   immediately enriches any active CallSessions that reference that supergroup ID.
   This has zero overhead when no Add commands arrive, and provides instant
   enrichment when they do. The existing `PatchGroupManager.addPatchGroups()`
   call in `P25P1DecoderState.processTSBK()` is the hook point.
