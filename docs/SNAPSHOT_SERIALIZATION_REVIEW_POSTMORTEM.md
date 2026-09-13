# Snapshot Serialization Review Postmortem

**Date:** 2026-09-13  
**Status:** Open correctness issue  
**Scope:** Raft persistence sequencing, snapshot publication, WAL compaction, and
in-memory state mutation

## 1. Executive summary

The storage externalisation work was incorrectly declared complete even though
Qraft does not provide a single serialization boundary across its Raft state,
WAL operations, snapshot operations, and shutdown lifecycle.

This was a serious review failure. The issue was missed during the initial
architecture review, during implementation, during test review, and when direct
questions were asked about how the snapshot and WAL were synchronized. An answer
was given that described the intended ordering inside one snapshot workflow as if
it were a system-wide concurrency guarantee. The implementation does not provide
that guarantee.

The current code separately provides:

- serialization inside the external WAL implementation;
- serialization inside `FileSnapshotStore`;
- a future chain ordering snapshot publication before WAL prefix truncation for
  one invocation.

It does not provide:

- mutual exclusion between a snapshot and a concurrent append;
- mutual exclusion between two snapshot attempts;
- serialization between snapshot installation and normal replication;
- serialization between persistence and shutdown;
- state-loop affinity for asynchronous persistence completions;
- protection against preparing a new Raft transition from state that an earlier
  persistence operation has not yet applied.

Consequently, the architecture currently has independently serialized components
but no serialized Raft persistence protocol spanning those components.

## 2. What was claimed incorrectly

The review previously described snapshot correctness in terms of this sequence:

```text
publish snapshot
-> truncate WAL prefix
-> trim the in-memory log
```

That sequence exists as a chain within one call to `takeSnapshot()`. It was
incorrectly presented as sufficient synchronization.

It is only a local ordering relationship. It says that the prefix truncation
continuation for that invocation is registered after snapshot publication. It
does not stop another workflow from executing between those stages, and it does
not guarantee that the final in-memory mutation runs on the Raft state loop.

The earlier completion statement is therefore withdrawn. Deleting duplicate WAL
implementations and passing the existing tests established storage delegation,
but did not establish safe serialization of the composed persistence protocol.

## 3. The flaw

### 3.1 Multiple independent execution domains

The node currently involves at least three execution domains:

1. `qraft-state-loop`, where public Raft operations initially dispatch;
2. the external WAL executor, where WAL futures complete;
3. `qraft-snapshot-store`, where snapshot futures complete.

Qraft's custom `Future` wraps a `CompletableFuture`. Its `compose`, `onSuccess`,
and `onFailure` callbacks execute according to normal `CompletableFuture`
completion rules. They are not automatically dispatched back to
`qraft-state-loop`.

As a result, entering an operation on the state loop does not mean its later
persistence continuation or in-memory mutation runs there.

### 3.2 No node-level asynchronous sequencer

There is no per-node queue that owns the complete lifecycle:

```text
prepare from current Raft state
-> persist all required durable effects
-> cross durability barriers
-> apply the corresponding in-memory transition
-> complete the operation
```

The state loop can start another operation as soon as the first operation has
launched asynchronous I/O. The first operation has not necessarily persisted or
applied when the second operation prepares its index, term, append plan, snapshot
boundary, or response.

### 3.3 No snapshot-in-progress protection

The periodic snapshot scheduler calls `takeSnapshot()` and ignores the returned
future. There is no in-progress token, generation, or queued-operation ownership.
A later timer firing or direct call can begin another snapshot before the first
one finishes.

### 3.4 Shutdown is not sequenced behind persistence

Shutdown can close the snapshot store and WAL without first joining a single
queue of accepted persistence transitions. Storage may therefore be closed while
a snapshot, append, metadata update, or compaction workflow remains in flight.

## 4. Unsafe interleavings

The following schedules are not adequately prevented by the current design.

### 4.1 Snapshot and append

```text
state loop: capture snapshot boundary N and snapshot bytes
snapshot executor: begin publishing snapshot N
state loop: prepare and submit append N+1
WAL executor: append and sync N+1
snapshot executor: finish publishing snapshot N
WAL executor: truncate prefix through N
completion thread: mutate the in-memory snapshot boundary
```

Some variants of this schedule can remain recoverable, but correctness cannot be
based on favorable executor ordering. The composed transition lacks one owner,
and its in-memory updates race with other Raft activity.

### 4.2 Two concurrent leader submissions

```text
submission A: read lastLogIndex = N; prepare entry N+1
submission B: read lastLogIndex = N; prepare entry N+1
submission A: persist entry N+1
submission B: persist a different entry N+1
```

The state-loop entry points are serialized, but neither adds its entry to memory
until asynchronous persistence completes. The second submission can therefore
prepare from the same old in-memory log.

### 4.3 Follower append plans prepared from stale state

```text
AppendEntries A: calculate suffix replacement plan from log L
AppendEntries B: calculate another plan from the same log L
A: persist and later apply L1
B: persist and later apply a plan that assumed L, not L1
```

Serialization inside the WAL preserves a physical call order. It does not repair
a plan that was calculated from stale consensus state.

### 4.4 Concurrent snapshots

```text
snapshot A: capture boundary N
snapshot B: capture boundary N or M
A: publish snapshot
B: replace snapshot
A: compact WAL and update memory
B: compact WAL and update memory
```

Independent snapshot-store serialization prevents simultaneous file writes, but
does not make the two compound Raft transitions coherent.

### 4.5 Snapshot installation and ordinary replication

A follower may be receiving and publishing an installed snapshot while an
AppendEntries workflow prepares or persists against its previous log boundary.
Both operations change the relationship between the snapshot sentinel, absolute
indices, the WAL tail, `commitIndex`, and `lastApplied`.

### 4.6 Persistence and shutdown

```text
operation: submit asynchronous persistence
shutdown: close snapshot store and WAL
operation completion: attempt in-memory mutation or another storage stage
```

Without a draining state and a queue barrier, shutdown has no definitive point at
which all previously accepted durable transitions are complete.

## 5. Why the flaw was repeatedly missed

### 5.1 Component serialization was confused with protocol serialization

The review observed that the WAL uses a single executor and that the snapshot
store uses a single executor. It incorrectly inferred that the composed protocol
was serialized. Two independently serialized queues do not create a total order
across both resources.

### 5.2 Local future ordering was confused with exclusion

The `saveAtomically().compose(truncatePrefix())` chain proves ordering between two
stages of one invocation. It does not prevent unrelated operations from running
between them. The review checked the arrows within the method but did not examine
all other entry points capable of overlapping those arrows.

### 5.3 State-loop entry was confused with state-loop completion

Public operations call `runOnContext`, which made the code appear actor-like.
The review did not trace the executor on which every asynchronous continuation
would run. `Future.fromCompletionStage` does not restore state-loop affinity.

### 5.4 The review followed storage calls instead of state ownership

The externalisation audit focused on whether WAL methods delegated correctly:
append, sync, replay, truncation, metadata, and close. That was necessary but
insufficient. The more important concurrency question was whether the complete
Raft transition remained under one serialized owner from preparation through
durable completion and memory application.

### 5.5 Existing tests were overwhelmingly sequential

The tests generally invoked an operation, awaited completion, and only then
started the next operation. Those tests proved call results and restart behavior,
but they could not reveal overlapping preparation, executor affinity, or shutdown
races.

No test deliberately held one storage stage open while starting a conflicting
Raft operation. No test asserted that every mutation of Raft state occurred on
`qraft-state-loop`.

### 5.6 Green tests were treated as evidence beyond their scope

A clean reactor result was reported as a completion gate. It showed that the
tested behaviors passed. It did not show that untested concurrent histories were
safe. The absence of adversarial scheduling tests should have prevented a claim
of serialization correctness.

### 5.7 The intended invariant was documented but not traced to enforcement

The design stated prepare, persist, then apply. The review verified that order
textually inside individual methods but did not identify a concrete mechanism
that prevented a second prepare from starting during the first persist. An
invariant without an enforcement point is only an intention.

### 5.8 Confirmation bias after removing visible duplication

Deleting thousands of lines of duplicate storage code produced a strong signal
of architectural simplification. Subsequent checks concentrated on remaining WAL
symbols and file-format code. That structural success distracted from the new
cross-component concurrency boundary created by direct asynchronous integration.

### 5.9 Direct user questions were answered from the intended design

When asked how the files were synchronized, the answer described how a correct
implementation should behave and assumed the chained operations were protected.
The implementation should have been inspected at that point. The later explicit
statement that the operations must be strictly serialized finally triggered the
necessary executor and call-path review.

This was not a lack of available evidence. It was a failure to validate the
assumption being questioned.

## 6. Why this is serious

Raft safety depends on more than writing valid records. The node must preserve a
coherent order between:

- the state from which an index or append plan is calculated;
- the durable effects written for that transition;
- the in-memory state exposed to the next transition;
- the response that tells a peer or client the transition succeeded.

If those phases overlap without a single serialized owner, a valid WAL
implementation cannot make the node correct. It will faithfully persist calls
whose plans may already be stale or contradictory.

Snapshot correctness is especially sensitive because compaction intentionally
deletes historical commands. The system must prove that the published snapshot,
the retained WAL suffix, and the in-memory boundary all describe one ordered
history.

## 7. Required corrective architecture

Qraft needs one per-node asynchronous transition sequencer. It must own the entire
prepare, persist, and apply lifecycle, not merely submit storage calls in order.

Conceptually:

```text
Raft message or local command
-> enqueue transition
-> prepare on qraft-state-loop
-> execute required WAL and snapshot stages
-> return to qraft-state-loop
-> apply memory changes and complete response
-> begin next queued transition
```

The sequencer must cover at least:

- term and vote persistence;
- leader command append and sync;
- follower conflict replacement and sync;
- local snapshot publication and prefix compaction;
- installed snapshot publication and prefix compaction;
- no-op AppendEntries and other protocol events that read or mutate state while a
  durable transition is active;
- election, heartbeat, and snapshot timer transitions;
- transport completions that update leader replication state;
- recovery;
- transition to a fenced or failed state;
- shutdown draining and resource closure.

The queue must not calculate plans before their turn. Otherwise it serializes I/O
while still allowing stale preparation.

Storage I/O should remain asynchronous. The state-loop thread must not block on
disk. Completion must explicitly dispatch back to the state loop before changing
Raft memory or completing a protocol response.

Shutdown must first reject new transitions, then drain or fail the accepted queue,
then close the snapshot store and WAL.

The sequencer must establish a valid total order, but that does not require every
event to use unconditional FIFO processing. A higher-term event may cancel a
snapshot that has not crossed an irreversible publication point, or it may wait
for the active snapshot transition to finish. Either policy is valid only when it
is explicit, tested, and produces no overlap. A stale completion must never apply
state or emit success after its operation has been superseded.

Every queued transition needs an identity and the term or generation under which
it was prepared. Its completion must validate that identity before applying
memory changes. The queue must also be bounded and define which failures allow
continued processing and which failures fence the node.

## 8. Mandatory TDD tranche

No serialization fix should begin with implementation. The following tests must
first fail for the intended concurrency reason. They must use real components or
small latching fakes, never a mocking framework.

### 8.0 Implementation phases and status

Implementation proceeds in reviewable phases. A phase is complete only when its
focused tests and the existing non-heavy controller suite pass.

| Phase | Scope | Status |
|---|---|---|
| 1 | Per-node transition sequencer: state-loop execution, whole-transition ordering, bounded admission, failure policy, fencing, and drain semantics. | Implemented and connected to `RaftNode`; focused tests pass. |
| 2 | Term and vote transitions, including concurrent same-term votes and higher-term persistence. | Implemented. Election self-votes, incoming votes, and higher-term step-downs share the sequencer; focused and default non-heavy tests pass. |
| 3 | Leader append and follower suffix-replacement transitions. | Not started. |
| 4 | Local snapshot capture, publication, WAL compaction, and boundary application. | Not started. |
| 5 | Installed snapshots, timer events, and transport completions with term or leadership-generation fencing. | Not started. |
| 6 | Shutdown integration, real-storage recovery matrix, structural bypass checks, and model-based histories. | Not started. |

Each integration phase starts with a deterministic failing test that holds the
current durable operation at a named gate. Production wiring follows only after
the test proves the unsafe interleaving in the previous implementation.

Phase 2 establishes these additional rules:

- election state and a self-vote are applied only after their single metadata
  write succeeds;
- vote decisions are prepared from the state left by the previous completed
  metadata transition, so concurrent candidates cannot both observe an empty
  vote;
- higher terms learned through any RPC path use the same serialized step-down
  transition and cannot overtake an in-flight vote;
- a metadata-write failure has an uncertain durability outcome, so the node is
  fenced and does not attempt a compensating metadata write;
- a rejection after failed higher-term persistence reports the last durable
  local term, never the unpersisted observed term.

### 8.1 Deterministic adversarial test harness

Create purpose-built WAL and snapshot-store fixtures with named, independently
controllable gates. Each operation must expose events such as:

```text
operationEntered
allowOperationToComplete
operationCompleted
stateApplied
responseCompleted
```

Tests must wait until the intended interleaving has definitely been reached
before releasing a gate. Timing sleeps, probabilistic repetition, and merely
starting calls from different threads are not acceptable evidence of ordering.

The harness must record:

- transition identity and captured term;
- preparation, storage-call, durability, memory-application, and response events;
- calling thread and `JavaRuntime.currentContext()` for every event;
- WAL and snapshot-store call order;
- queue admission, rejection, cancellation, drain, and fencing events.

Run core sequencing tests once with already-completed futures and once with
manually completed asynchronous futures. This detects reentrancy and completion
thread assumptions.

### 8.2 Define and test linearization points

Before implementing the sequencer, define the point at which each transition
becomes authoritative:

| Transition | Required linearization point |
|---|---|
| Term or vote | Metadata is durable and the matching term/vote is applied on the state loop. |
| Leader append | Append and sync succeed, then the entry is installed in memory on the state loop. |
| Follower suffix replacement | Truncate, append, and sync succeed, then exactly that plan is applied in memory. |
| Local snapshot | Snapshot is published and the covered WAL prefix is compacted, then the new boundary is installed in memory. |
| Installed snapshot | Snapshot is published and WAL compaction succeeds, then state-machine restoration and boundary replacement occur. |
| Shutdown | New work is rejected, accepted work reaches its defined terminal state, and only then are resources closed. |

Every adversarial test must assert a history compatible with these points. It is
not sufficient to assert only the final return value.

### 8.3 Concurrent leader submissions

Hold the first append before completion and submit a second command. Prove that:

- the second command cannot calculate or persist an index until the first
  transition has durably applied;
- the final WAL contains distinct consecutive indexes;
- the in-memory log has the same order;
- each client promise is associated with its own index;
- failure of the first operation follows the defined queue/fencing policy and
  cannot cause the second command to reuse partially durable state.

Repeat with the first operation held at append and at sync.

### 8.4 Concurrent votes and metadata transitions

Hold persistence of a vote for candidate A, then submit a vote request from
candidate B in the same term. Prove that B cannot evaluate `votedFor` from the
old state and that exactly one candidate receives a granted vote. Reopen the real
WAL and prove the durable vote matches the granted response.

Also test:

- two concurrent higher-term vote requests;
- a vote request while self-vote persistence is pending;
- a rejected higher-term candidate while another metadata transition is active;
- metadata failure followed by queued vote requests;
- no response based on a new term before that term is durable.

### 8.5 Higher-term events during in-flight work

For each leader append, follower replacement, local snapshot, and self-vote
transition, inject a higher-term RequestVote, AppendEntries, or InstallSnapshot
event while persistence is held open.

Prove that:

- the two transitions never overlap their state-application phases;
- the node stops issuing leader actions as soon as the chosen term-observation
  policy requires;
- completion of the old operation cannot restore an old role, term, or vote;
- an old append completion cannot start replication after leadership is lost;
- an old vote completion cannot overwrite a vote or term from the newer event;
- stale success is not returned to a client or peer;
- cancellation before an irreversible point, if supported, is explicit and
  leaves a valid durable history.

### 8.6 Timer and transport-completion interactions

Exercise election, heartbeat, and snapshot timer callbacks while each durable
transition type is blocked. Prove that timer work is admitted to the same state
transition discipline and cannot prepare from intermediate state.

Required cases include:

- election timeout while self-vote metadata is pending;
- repeated election timeout while an election transition is active;
- heartbeat timer while a leader append or step-down is pending;
- repeated snapshot timer firing while a snapshot is active;
- vote and replication transport completions arriving after term or role change;
- delayed replication success updating `nextIndex` or `matchIndex` only when its
  originating leadership generation is still current.

### 8.7 Snapshot payload and boundary consistency

Hold command application while requesting a snapshot and prove that the serialized
state, `lastApplied`, snapshot index, and snapshot term describe exactly the same
committed prefix.

Test both sides of the boundary:

- state at or below the declared index is present;
- state above the declared index is absent;
- an index cannot be captured before its command is applied;
- a command cannot be applied between capturing snapshot bytes and capturing the
  associated boundary;
- inability to resolve the exact term at `lastApplied` fails snapshot creation
  instead of substituting `currentTerm`;
- uncommitted log entries are never included in the application snapshot.

### 8.8 Snapshot versus other persistence transitions

Hold snapshot publication open, then submit a leader append, follower append,
vote/term update, and direct second snapshot request in separate tests.

For each pair, prove there is one valid total order and no overlap. A test may
accept either of these explicitly selected policies:

- finish the active snapshot transition before beginning the later transition;
- cancel the snapshot before its irreversible publication point and run the
  higher-priority transition first.

The test must not hard-code FIFO if the architecture selects safe preemption.
Once the snapshot has been published, its required WAL compaction and state-loop
boundary handling must reach the defined terminal policy before conflicting work
can apply.

### 8.9 At most one local snapshot transition

Trigger the periodic scheduler and a direct snapshot concurrently. Prove that the
second request either joins the active result or queues and reevaluates
eligibility after the first completes.

Cover:

- two direct calls;
- two timer firings;
- timer plus direct call;
- first snapshot success;
- first snapshot failure before publication;
- first snapshot failure after publication;
- no duplicate publication or compaction from the same stale boundary.

### 8.10 Follower AppendEntries ordering

Hold the first suffix replacement at truncate, append, and sync in separate tests.
Submit a second AppendEntries request and prove its consistency check and
`AppendPlan` calculation do not occur until the first transition reaches its
memory-application or failure policy.

Include:

- matching retries;
- overlapping new suffixes;
- conflicts followed by matching-term old entries;
- heartbeat/no-op AppendEntries while replacement is pending;
- a higher-term AppendEntries request while replacement is pending;
- distinct responses whose match indexes correspond to the actual serialized
  history.

### 8.11 Partial suffix-replacement failures

Follower replacement is a compound operation:

```text
truncate suffix
-> append replacement
-> sync
-> apply plan in memory
```

Inject failure after each durable call boundary:

- truncation fails;
- truncation succeeds and append fails;
- truncation and append succeed and sync fails;
- all storage calls succeed but dispatch back to the state loop is rejected
  during shutdown.

For every case, prove that no success response or in-memory plan application
occurs, queued work follows the fencing policy, and restart produces only a
documented valid recovery outcome.

### 8.12 Snapshot publication and compaction failures

Test each compound boundary independently:

- snapshot temporary write fails;
- snapshot force fails;
- snapshot publication fails;
- snapshot succeeds but WAL prefix truncation fails;
- snapshot and compaction succeed but state-loop application is delayed by
  shutdown;
- an unpublished temporary snapshot exists during recovery;
- a published snapshot coexists with an untrimmed WAL prefix during recovery.

Prove that WAL compaction never starts before durable snapshot publication. If
publication succeeds but compaction fails, memory must not pretend compaction
completed, and the node must enter the explicitly selected retry or fenced state.

### 8.13 Snapshot installation ordering and validation

Hold final snapshot installation publication open while submitting ordinary
AppendEntries. Prove that AppendEntries cannot prepare against the old boundary
and later apply against the new one.

Also test:

- installed index below `snapshotLastIndex`;
- installed index below `lastApplied` or the committed boundary;
- duplicate completed installation;
- term change during multi-chunk assembly;
- AppendEntries between chunks and final publication;
- simultaneous transfers from different leaders;
- assembler cleanup after rejection, timeout, term change, and persistence
  failure;
- state-machine restoration failure after snapshot publication and WAL
  compaction;
- response term and next-chunk index after every failure.

A stale snapshot must never roll the state machine or Raft indexes backwards.

### 8.14 State-loop affinity

Record the thread and `JavaRuntime.currentContext()` for every mutation following
an asynchronous persistence or transport completion.

All changes to the following must occur on the owning state loop:

- log and snapshot sentinel;
- current term and voted-for candidate;
- role and current leader;
- snapshot boundary;
- commit and last-applied indexes;
- `nextIndex` and `matchIndex`;
- pending client commands;
- snapshot-install assemblers and in-progress markers;
- timers and running/draining/fenced lifecycle state.

Verify both success and failure continuations, including futures completed on a
WAL thread, snapshot thread, transport thread, and synchronously on the caller.

### 8.15 Queue admission, backpressure, cancellation, and fencing

Define a bounded capacity and test:

- rejection of new client commands when the queue is full;
- no index allocation or state mutation for rejected work;
- the admission policy for essential peer and higher-term traffic;
- FIFO behavior among equal-priority operations if FIFO is selected;
- safe preemption rules if priority is selected;
- caller timeout or cancellation does not cancel, reorder, or forget durable
  work already accepted;
- already-completed futures do not cause recursive queue draining or stack
  overflow;
- one transition completes exactly once despite duplicate completion signals;
- fatal durability failure fences the node and fails all affected queued promises;
- no operation reaches a fenced storage instance;
- explicitly nonfatal failure, if any exists, releases the queue according to a
  tested policy.

### 8.16 Shutdown drain

Test shutdown with:

- an empty queue;
- an active operation and no queued work;
- an active operation plus queued work;
- an active operation that succeeds;
- an active operation that fails;
- repeated concurrent shutdown calls;
- new client and peer work arriving during drain;
- shutdown requested between snapshot publication and WAL compaction;
- shutdown requested after WAL durability but before memory application.

Prove that new work is rejected after draining begins, accepted work reaches its
documented terminal state, snapshot storage and WAL close only after that state,
and no completion callback mutates node state after shutdown completes.

### 8.17 Real-storage recovery matrix

After the deterministic sequencer tests are green, verify the composed behavior
with the real WAL and snapshot store. Exercise restart at these observable
boundaries:

- before snapshot temporary-file creation;
- after temporary-file write and before force;
- after force and before atomic publication;
- after snapshot publication and before WAL prefix compaction;
- after WAL prefix compaction and before in-memory boundary application;
- after suffix truncation and before replacement append;
- after replacement append and before sync;
- after sync and before peer or client response;
- during snapshot installation publication;
- while shutdown is draining accepted persistence work.

For every recovered directory, assert the exact snapshot boundary, WAL suffix,
term, vote, reconstructed application state, and readiness/fencing result. The
test matrix must state which interruption points require injected storage faults
and which can be exercised with real process restart.

### 8.18 Structural and model-based verification

Add a structural test or review rule proving that no persistent Raft entry point
bypasses the sequencer. Search-based checks alone are insufficient, but they can
guard against direct calls to WAL and snapshot mutation methods outside the
coordinator.

Add a small model-based history test that generates bounded combinations of
commands, votes, term changes, follower replacements, snapshot triggers, and
shutdown. Compare the observed event history with a simple serialized reference
model. Persist the random seed for every failure so the history is reproducible.

## 9. Review controls going forward

Future reviews of asynchronous safety must include these questions:

1. What single mechanism establishes the total order?
2. Does that order cover preparation, persistence, memory application, and
   response, or only I/O submission?
3. On which executor does every continuation run?
4. Can another entry point prepare from state while persistence is outstanding?
5. Can periodic work overlap itself?
6. What happens when shutdown begins with work in flight?
7. What happens after a durability failure fences storage?
8. Which tests deliberately force each possible overlap?
9. What is the linearization point of each transition?
10. Can a same-term vote or higher-term event overtake an in-flight callback?
11. Can timers or transport completions bypass the transition order?
12. Is the queue bounded, drainable, and safe under synchronous completion?
13. Are snapshot bytes, index, and term captured from one committed state?
14. Can a stale installed snapshot move any state backwards?

A code review must trace one complete transition across thread changes and then
enumerate every competing entry point. Reading each method independently is not
sufficient.

No completion claim should be made from a green sequential suite when the stated
requirement includes serialization, concurrency protection, or atomic lifecycle
coordination.

## 10. Completion criteria

The snapshot and WAL integration must not be considered complete until:

- one per-node sequencer is the exclusive entry point for persistent Raft
  transitions;
- plans and indexes are calculated only when their transition reaches the head of
  that sequencer;
- asynchronous completions return to the state loop before memory mutation;
- snapshot publication, prefix truncation, and boundary application execute as
  one protected transition;
- snapshot installation is mutually ordered with AppendEntries;
- shutdown drains the transition queue before closing storage;
- durability failure produces a defined fenced-node behavior;
- same-term votes, higher-term events, timers, and transport completions obey the
  same transition order;
- snapshot bytes, index, and term are captured from one committed state;
- stale installed snapshots cannot move state backwards;
- queue capacity, admission, cancellation, failure propagation, and preemption
  policies are explicit and tested;
- adversarial concurrency tests and real-storage recovery tests pass;
- the observed histories satisfy the defined linearization points;
- a structural review confirms that no persistence path bypasses the sequencer.

Until those conditions are met, storage delegation may be structurally complete,
but snapshot correctness and node-level persistence serialization are not.
