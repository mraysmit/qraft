# Code Review: Snapshot Serialization Remediation

**Date:** 2026-09-15
**Reviewed range:** commits `d1319a8` through `91c712a` (Phases 1 to 6 of the
remediation described in `SNAPSHOT_SERIALIZATION_REVIEW_POSTMORTEM.md`)
**Scope:** `RaftNode`, `RaftTransitionSequencer`, `FileSnapshotStore`,
`ShutdownCoordinator`, controller shutdown wiring, and the remediation test
tranche in `qraft-controller`
**Verdict:** Not complete. Two release-blocking defects, four high-severity
issues, and a test-fixture gap that is the direct cause of the headline defect.

## 1. Summary

The per-node transition sequencer is a sound design and most of the wiring is
correct. The sequencer's ordering, fencing, and drain logic hold up. Every
append and truncate is followed by a sync. Snapshot bytes, index, and term are
captured together on the state loop. Shutdown drains the sequencer and the
owned asynchronous operations before closing resources, and transport closes
before storage.

However, one reproduced defect fences any follower on real storage the first
time it learns of a new term from its leader. It is exactly the failure class
the postmortem describes: a continuation running off the state loop. The
remediation test fixtures could not observe it because every gated storage
returns already-completed futures, so the continuation ran inline on the state
loop during testing and on the WAL executor thread in production.

A second defect leaves a discarded log suffix in the WAL after snapshot
installation, which corrupts the index mapping on the next restart.

The postmortem's status line "Remediation complete" should be reverted to
"in progress" until the items in Section 5 are addressed.

## 2. Confirmed defects

Findings are ordered by severity. Line references are to the reviewed commit.

### 2.1 Critical: higher-term AppendEntries or InstallSnapshot fences the node on the real WAL

`RaftNode.prepareAndPersistFollowerAppend` (lines 1451-1452) and
`RaftNode.prepareAndPersistInstalledSnapshot` (line 2357) chain the metadata
write with `Future.compose`. The continuation begins by calling
`persistAppendEntries` (line 1564) or `persistInstalledSnapshot` (line 2435),
each of which starts with `transitionSequencer.assertActiveTransition()`.

`Future.compose` is a plain `thenCompose`; the continuation runs on whichever
thread completes the source future. `FileRaftStorage` completes every future on
its single `wal-executor` thread. The assertion therefore throws
`IllegalStateException("Raft transition accessed outside its owning state loop")`,
the transition fails under `FailurePolicy.FENCE`, and the sequencer is fenced
permanently.

**Reproduction.** A throwaway test (deleted after use) built a durable node from
`RaftStorageFactory.createDurable` with a real file WAL seeded at term 1, then
sent an AppendEntries at term 2 with one entry:

```text
ERROR RaftNode - AppendEntries failed during durable transition:
      Raft transition accessed outside its owning state loop
r1 success=false term=1 nodeTerm=1 logSize=1
ERROR RaftNode - AppendEntries failed during durable transition:
      Raft transition sequencer is fenced
r2 (same-term heartbeat) success=false
```

The metadata write had already completed before the continuation ran, so the
WAL holds term 2 while memory holds term 1. The heartbeat-only variant follows
the same path because the ownership assertion runs before the empty-entries
check in `persistAppendEntries`.

**Why existing tests pass.** Same-term traffic uses an already-completed term
future, so the continuation runs inline. Multi-node tests run in volatile mode,
where `persistMetadata` returns a completed future. Every sequencing fixture
delegates `updateMetadata` to a synchronous in-memory storage. No fixture
completes a metadata future from a foreign thread.

**When it happens in production.** Any follower that learns of a new term from
AppendEntries or InstallSnapshot rather than from RequestVote: a node restarted
after an election, a node partitioned during an election, or a node whose vote
request was lost.

**Fix.** Route both continuations through the state loop, as recovery already
does with `composeOnStateLoop`, or perform the ownership assertion and plan
capture before chaining and only issue the storage calls in the continuation.
Add a fixture variant that completes the metadata future from a named foreign
thread for every sequencing test that involves a higher term.

### 2.2 Critical: InstallSnapshot discards the in-memory suffix but leaves it in the WAL

`applyInstalledSnapshot` (lines 2498-2509) retains the in-memory suffix only
when the local log has an entry at `lastIncludedIndex` with the matching term.
Otherwise it resets the in-memory log to the sentinel. The only durable
mutation in that transition is `truncatePrefix(lastIncludedIndex)` (lines
2452-2455), which keeps every WAL record with an index above the boundary.

After installation the WAL still contains stale entries `N+1..M`. The next
AppendEntries at `prevLogIndex = N` sees no in-memory conflict, so `AppendPlan`
produces no suffix truncation, and the new entry `N+1` is appended after the
stale ones. `replayLog` then returns duplicate indices. Recovery (lines
521-526) appends everything above `snapshotLastIndex` with no contiguity check,
so `toArrayIndex` no longer maps indices to positions.

This is the lagging-follower-with-divergent-suffix scenario that triggers
InstallSnapshot in the first place. Only the matching-suffix case is tested
(`RaftNodeInstalledSnapshotSequencingTest.matchingUncommittedSuffixIsRetainedAcrossInstallation`).

**Fix.** When the suffix is discarded, issue `truncateSuffix(lastIncludedIndex + 1)`
followed by `sync()` within the same transition, before or alongside prefix
compaction. Reject non-contiguous replay during recovery instead of silently
appending.

### 2.3 High: a fenced node is a silent zombie

No main-code path reads `RaftTransitionSequencer.isFenced()`. After a fence the
node stays `running`, keeps answering RPCs with failures, and the election timer
logs an error on every timeout. Health and readiness endpoints never change.
Operators discover the state only from log volume.

**Fix.** Either stop the node when the sequencer fences or expose the fenced
state through `RaftNode` and the controller's health endpoints.

### 2.4 High: FENCE is applied to storage rejections that carry no ambiguity

`FileRaftStorage.appendEntries` rejects oversized payloads and runs a disk-space
preflight before writing any record. Both return a failed future with nothing
written and no self-fence. `RaftNode` submits `leader-append` (lines 634-638)
and `append-entries` (lines 1365-1369) under FENCE, so one transient low-disk
condition or one oversized command fences the leader permanently.

The mid-write `IOException` case genuinely requires fencing because the WAL does
not fence itself there. The storage API gives no typed signal to distinguish
the two cases.

**Fix.** Either add a typed pre-write rejection to the storage contract or
whitelist the pre-write exception types and treat them as CONTINUE.

### 2.5 High: election liveness is lost after an admission rejection

`onElectionTimer` (lines 969-973) clears `electionTimerId`, then
`startElection` submits the transition. If admission fails, for example with
`QueueFullException`, the `onFailure` handler only logs. Nothing re-arms the
election timer. The node remains a follower until a peer message resets the
timer.

**Fix.** Re-arm the election timer on any admission rejection that is not a
draining or fenced rejection.

### 2.6 High: InstallSnapshot has a retry storm with no backoff

A follower that fails publication or is fenced answers `success=false` with
`nextChunkIndex=0` (lines 2332-2336 and 2485-2488). The leader resends
immediately (lines 2215-2228). A follower with a full disk produces a tight loop
of full snapshot transfers with no delay between attempts.

**Fix.** Distinguish a chunk-order rejection (retry immediately) from a
persistence failure (abandon the transfer and let the next heartbeat cycle
retry), and add a minimum delay before re-sending from chunk 0.

### 2.7 Medium: store close is asynchronous, so stop completes before handles and the lock are released

`FileRaftStorage.close()` enqueues the channel and lock release on its executor
and returns. `FileSnapshotStore.close()` only calls `executor.shutdown()`.
`closeNodeResources` (lines 2648-2679) therefore completes the stop future
before either store has released anything, and it cannot observe a WAL close
failure. This contradicts the postmortem's statement that "every close is
attempted, with the first failure reported". Reopening the same directory in
one JVM can hit the "lock already held in this JVM" error visible in the test
log.

### 2.8 Medium: draining rejections are logged at ERROR

`handleAppendEntriesRequest` (line 1373) and `handleInstallSnapshot` (line
2330) log every failed transition at ERROR, including `DrainingException`. The
postmortem states that draining rejections are debug lifecycle information.
`submitCommand` and the append-response path handle this correctly.

### 2.9 Medium: dead code with a trap

The two `stepDown` overloads (lines 1179-1195) have no callers. The
`persistMetadataRequired=false` variant applies a higher term through
`applyDurableHigherTerm` without any durable write. Delete both.

### 2.10 Medium: one bounded queue with no admission policy

The sequencer capacity is hard-coded to 1024 (line 313). Client commands, peer
RPCs, vote responses, transport completions, and timer callbacks share the
queue with no priority. Sustained client load can reject higher-term peer
traffic. Section 8.15 of the postmortem asked for this policy to be explicit and
tested; it is neither.

### 2.11 Low

- `assertActiveTransition` (`RaftTransitionSequencer` lines 130-136) checks only
  that some transition is active, not that the caller is that transition.
- `finish` clears `active` before running the apply step (lines 186-189), so the
  sequencer reports idle during apply. Harmless today, fragile.
- A failed directory fsync after the snapshot rename is labelled
  "failed before publication" (lines 1926 and 2449). Recovery handles the
  resulting state correctly, but the label is wrong and the store never fences.
- A failed first snapshot leaves `snapshot.dat.tmp`, and the next open refuses
  to start (`FileSnapshotStore` lines 59-62). The test suite shows this is
  intentional; it is an operationally harsh choice because the temp file holds
  nothing the intact WAL does not.
- `Future.onSuccess` uses `thenAccept`, so exceptions thrown by callbacks are
  silently swallowed. Pre-existing.

## 3. Test suite assessment

The 73 remediation tests run in the default build, use no mocking framework, and
several would genuinely have gone red for the ordering defects the postmortem
describes. The evidence is nonetheless weaker than the document states.

### 3.1 The Section 8.1 harness does not exist

No fixture records per-event thread and context. No fixture completes storage
futures from a foreign thread. The requirement to run each case once with
already-completed futures and once with asynchronously completed futures was
not implemented. This is the direct reason defect 2.1 shipped.

### 3.2 The timer tests are wall-clock based

`RaftNodeTimerSequencingTest` lines 351, 379, and 408 sleep for a fixed
interval and assume a production timer fired inside it. The election timeout is
jittered to `[T, 2T)`, leaving a narrow margin. Two tests pass vacuously if the
timer does not fire; the third can flake red if the periodic snapshot timer
fires before the metadata gate is armed.

### 3.3 The structural test is easily bypassed

`RaftPersistenceArchitectureTest` (lines 183-204) reads only `RaftNode.java` as
text, extracts five method bodies by string prefix, and checks for literal
substrings. A method reference, a helper class, a renamed storage method, or an
assertion placed in a dead branch all pass undetected.

### 3.4 The model test compares the sequencer against itself

`RaftTransitionSequencerModelTest` never constructs a `RaftNode`. Its reference
model and its system under test are the same `Operation.apply` function (lines
306 and 328). The model has no failure, fencing, rejection, or generation
concepts, so the four seeds exercise one property: strict FIFO.

### 3.5 Seven of twelve real-storage recovery rows never instantiate a node

`RealStorageCrashWriter` and `SnapshotStoreCrashWriter` drive the stores
directly. Rows worded "after sync, before peer or client response" and "after
WAL prefix compaction, before in-memory boundary application" overstate what was
verified. Only `InstalledSnapshotCrashWriter` runs a real `RaftNode`.

### 3.6 Fencing of already-queued work is tested only on the bare sequencer

Every `RaftNode` fencing test submits the follow-up after the failure has been
observed. `RaftTransitionSequencerTest` is the only place a queued transition
is shown to be fenced.

### 3.7 Uncovered items from Sections 8.4 to 8.16

- Reopening the real WAL to verify a granted vote (8.4).
- Higher-term events during leader append and during follower replacement
  (8.5).
- Repeated election timeout while an election is active; heartbeat while a
  step-down is pending (8.6).
- Snapshot publication versus follower AppendEntries (8.8).
- Follower append-fails and sync-fails cases (8.11).
- Queue-full behaviour and essential-traffic admission through the node (8.15).
- Peer work arriving during drain (8.16).

## 4. What checks out

- `RaftTransitionSequencer` ordering, fencing, drain, and reentrancy handling.
- Durability barriers: every `appendEntries` and `truncateSuffix` is followed
  by `sync()`; `truncatePrefix` and `updateMetadata` provide their own.
- Snapshot capture, index, and term are taken together on the state loop after
  the transition becomes active.
- Shutdown: the shared stop future is idempotent, the sequencer and owned
  operations drain before close, and transport closes before storage on a
  worker thread.
- Recovery continuations are marshalled to the state loop and preserve MDC and
  trace context.
- `Future.timeout` now derives from a copy and cannot complete the source.
- `ShutdownCoordinator` critical hooks correctly stop later phases on failure,
  and the JVM shutdown hook now waits for the bounded controller result.
- `RaftStorageFactory` closes both stores on open failure and there are no
  double-close paths.

## 5. Required actions before re-declaring completion

1. Fix 2.1 and add a foreign-thread completion variant to every gated fixture.
2. Fix 2.2 and add a non-matching-suffix installed-snapshot test that restarts
   the node from real storage.
3. Decide and implement fenced-node behaviour (2.3).
4. Separate pre-write storage rejections from ambiguous failures (2.4).
5. Re-arm the election timer on admission rejection (2.5).
6. Add backoff or transfer abandonment to InstallSnapshot retries (2.6).
7. Replace the wall-clock timer tests with gate-driven equivalents (3.2).
8. Revert the postmortem status to "Remediation in progress" and record this
   review in its history.

Items 2.7 through 2.11 and Sections 3.3 to 3.7 should be scheduled but need not
block the status change.

## 6. Re-review of corrective changes (2026-09-15, later the same day)

Uncommitted working-tree changes on top of `91c712a` were reviewed against
Sections 2 and 3. The full controller suite passes (260 tests, no failures,
errors, or skips).

### 6.1 Status by finding

| Finding | Status |
|---|---|
| 2.1 Off-loop continuation fences the node | Fixed and proven |
| 2.2 Discarded suffix left in WAL | Fixed and proven |
| 2.3 Fenced node invisible | Fixed for HTTP readiness |
| 2.4 FENCE on unambiguous pre-write rejections | Fixed, with caveats (6.3) |
| 2.5 Election timer lost after queue-full | Fixed |
| 2.6 InstallSnapshot retry storm | Fixed |
| 2.7 Asynchronous store close | Open |
| 2.8 Draining logged at ERROR | Fixed |
| 2.9 Dead `stepDown` code | Fixed |
| 2.10 Hard-coded capacity, no admission policy | Partial: capacity configurable, no policy |
| 2.11 Low items | `active` now cleared after apply; rest open |
| 3.2 Wall-clock timer tests | Fixed via `RaftTimerScheduler` seam |
| 3.1, 3.3 to 3.7 | Open |

### 6.2 What was verified

- **2.1.** Both higher-term continuations now use `composeOnStateLoop`. The
  new `higherTermAppendCompletesThroughTheRealWalExecutor` test reproduces the
  original defect against the real WAL and passes. `RaftNodeLogSequencingTest`
  and `RaftNodeInstalledSnapshotSequencingTest` gained a foreign-thread
  metadata completion mode.
- **2.2.** The retain decision is captured in the plan at prepare time. The
  non-retaining path issues `truncateSuffix(boundary + 1)` and `sync()` before
  prefix compaction. A sequencing test checks the WAL is empty after install,
  and the `AFTER_DIVERGENT_SUFFIX_INSTALL` crash-writer variant proves it on
  real storage across a halt. Recovery now rejects non-contiguous replay. A
  crash between publication and suffix truncation replays the stale suffix,
  but the leader's next append conflicts on term and truncates it, so the
  window is safe.
- **2.5.** The re-arm is guarded by the election timer generation. The
  capacity-1 test is deterministic.
- **3.2.** The manual timer scheduler removes all three sleeps; the tests now
  provide real ordering evidence.

### 6.3 Concerns with the new code

- **Message-prefix coupling to raftlog.** `isKnownPrewriteRejection` matches
  exception message prefixes from the external WAL. A reworded message reverts
  silently to fencing. Safe direction, but a typed exception in raftlog is the
  correct long-term fix.
- **Follower recovery block is too wide.** The `recover` in
  `prepareAndPersistFollowerAppend` wraps the higher-term metadata write as
  well as the append. If the metadata write ever failed with a whitelisted
  message, the node would apply an unpersisted term. Verified unreachable
  today because `updateMetadata` never calls the disk-space check, but the
  recovery should be scoped to the append stage.
- **Misleading log on queue-full.** `startElection` logs "metadata durability
  is uncertain" at ERROR for a `QueueFullException` before re-arming.
- **Backoff conflates chunk-zero restarts with persistence rejection.** A
  follower that lost its assembler also answers with chunk 0, so the leader
  waits one heartbeat before restarting. A delay, not a loss.
- **Fenced election timer keeps logging.** A fenced node fires the election
  timer every timeout and logs an ERROR each time.
- **Codec failures still fence the leader.** Nothing is written, so this is
  not ambiguous, but the whitelist only recognises storage exceptions.

### 6.4 Remaining before completion can be re-declared

1. Scope the follower `recover` to the append stage only.
2. Apply the foreign-thread completion mode to `RaftNodeMetadataSequencingTest`,
   `RaftNodeSnapshotSequencingTest`, and `RaftNodeTransportGenerationTest`.
3. Items 2.7, 2.10 (policy), 3.3, 3.4, 3.5, and 3.6 remain as scheduled work.

The two release blockers are resolved with adequate regression evidence.
