# Task List: Test Suite Remediation

**Date:** 2026-09-27
**Active work:** Step 4, isolation and cleanup (Steps 1 to 3 are done)
**Source review:** [`QRAFT_TEST_SUITE_REVIEW_2026-09-27.md`](QRAFT_TEST_SUITE_REVIEW_2026-09-27.md), sections 3 to 7
**Runs alongside:** [`task-list-platform-hygiene-and-readiness-2026-09-27.md`](task-list-platform-hygiene-and-readiness-2026-09-27.md), complete and awaiting archive confirmation
**Paused:** [`task-list-embedded-admin-interface-2026-09-27.md`](task-list-embedded-admin-interface-2026-09-27.md), after its Step 1
**Standards:** [`PROJECT_STANDARDS.md`](PROJECT_STANDARDS.md)

This is the current task list for the project. When the active work is complete,
add a completion summary, move this file to `archive/` with the user's
agreement, and start a new dated task list for the next backlog item.

## 1. Goal

Make a clean run of the suite mean what it claims. When this list is complete:

- every test can fail, and fails for the reason its name gives;
- Raft safety rules are checked against the rule the design specifies, including
  the cases a regression would break;
- container tests exercise the failures their names describe;
- no test depends on machine speed, execution order, or state another test left.

## 2. Current state

Updated 2026-09-27.

The five production defects in the review's section 2 are fixed. Steps 1 and
2 fixed two more: the commit majority in even-sized clusters, and replicas that
skipped a committed entry they could not apply. The rest of the review is
open:

- **Tests that verify nothing.** About a dozen unit tests cannot fail, catch
  the failure they exist to detect, or pass for the wrong reason.
- **Placeholder container tests.** About half the Docker-tagged suite is
  placeholders: leader failover, partitions, and network stress that kill or
  partition nothing.
- **Timing and isolation.** The older Raft, gRPC, runtime, and agent tests use
  real elections, sleeps, sub-second margins, absence proven by waiting, and
  free-port races. Global configuration also leaks between tests.

## 3. Rules

- Red before green. A test for existing behaviour that passes at once is shown
  to fail against a mutation that removes the behaviour, and the mutation is
  recorded.
- Deterministic tests only (`PROJECT_STANDARDS.md`, section 4.4). Mockito is
  prohibited.
- A test that cannot be made meaningful is deleted, not kept for its count.
- A new or changed concurrency test is run repeatedly before its step is done.
- A production defect found on the way is fixed test first and recorded here and
  in the review.
- Every new Java file carries the license header and attributed type Javadoc.
- No commits; the user commits.

## 4. Steps

### Step 1. Raft safety oracle and coverage

Review sections 3 and 6.

1. Correct `RaftNodeModelTest`'s reference model to the design's rule:
   - the follower match index is the verified index,
     `max(prevLogIndex + entries, snapshotLastIndex)`;
   - commit is `min(leaderCommit, verifiedIndex)`.

   Make its generator produce a stale follower tail beyond the verified index,
   so a regression to the old rule fails.
2. Figure 8: a leader never counts replicas to commit an entry from an earlier
   term. The entry commits only once an entry from the leader's own term does.
3. A candidate that receives AppendEntries from a leader of its own term steps
   down and accepts it.
4. WAL integrity:
   - a torn final record is repaired, and recovery keeps every complete record;
   - a complete record that is corrupt fails recovery and fences the node, and
     does not start it with the record dropped.
5. Snapshot installation in several chunks, including a chunk out of order, a
   missing first chunk, and a restart of the transfer.
6. A leader whose append fails leaves its in-memory log unchanged.

**Exit gate.** Each new test fails against a mutation of the rule it checks.

**Status: Done 2026-09-27.** One production defect was found and fixed.

- **Model oracle.** `RaftNodeModelTest`'s generator now produces leaders
  behind the follower's tail: a lagging heartbeat and a lagging resend.
  - Under the old oracle this failed at once against the correct node:
    expected commit 2, the node committed 0.
  - The oracle now follows design section 14.3.
  - Reverting the node to the old rule (`verifiedIndex = lastLogIndex()`)
    fails both model tests.
- **Leader commit, with the defect.** `RaftNodeLeaderCommitTest` uses a
  transport that holds every AppendEntries until the test answers it.
  - **Figure 8.** An earlier-term entry held by a majority stays uncommitted
    until the leader's own no-op commits. Removing the current-term check
    fails the test.
  - **Defect: even-sized clusters committed without a majority.** The leader
    took the median of the sorted match indexes (`indices.get(size / 2)`). A
    two-member leader therefore committed alone, and a four-member leader
    committed on two copies. Both tests failed first.
  - The fix selects position `size - (size / 2 + 1)`.
  - Design section 14.3 now states the commit rule.
- **Candidate.** `RaftNodeCandidateTest` covers same-term, later-term, and
  earlier-term leaders. Removing the same-term step-down fails the same-term
  test.
- **WAL integrity.** In `RaftNodeRealStorageRecoveryTest`:
  - **Torn final record.** It is repaired and the two complete records
    recover. This pins the storage library's repair for upgrades (design
    section 14.7); no Qraft mutation applies to it.
  - **Corrupt complete record.** A flipped interior byte fails startup with
    `CorruptLogException`, and the log stays byte-for-byte unchanged. Making
    recovery swallow the replay failure fails the test.
- **Chunked snapshots.** `RaftNodeInstalledSnapshotSequencingTest` adds five
  tests:
  - three chunks, published only at the last;
  - a skipped chunk, answered with the missing cursor and then completed;
  - no first chunk, answered `ASSEMBLER_STATE_LOST`;
  - a repeated chunk, not appended twice;
  - a leader restarting with a newer snapshot, which replaces the partial
    transfer.

  Accepting any chunk order fails the skipped and repeated tests. Keeping a
  stale partial transfer fails the restart test.
- **Leader append failure.** A rejection the storage guarantees happened
  before any byte was written leaves the log unchanged and does not fence: the
  next write commits. My first version of this test wrongly expected a fence.
  Fencing on every failure fails it. The existing
  `uncertainLeaderSyncFailureFencesLaterAppend` already covers an uncertain
  failure.

### Step 2. Tests that verify nothing

Review section 3, in-process tests.

- `RaftNodeTest:247` accepts a timeout as the expected exception.
- `EnhancedInMemoryTransportTest:317, 442` assert `leaderCount <= 1`, which
  holds before any election.
- `RaftFailureTest:264-315` exercises a local anonymous class.
- The gRPC tests catch the only failure path:
  - `GrpcRaftServerTest`, the large-entries and short-deadline tests;
  - `GrpcRaftTransportTest:440, 480`.
- `HttpApiServerTest:422-432, 546` get their 400 from a missing header.
  `HttpApiServerTest:497-524` never shows two requests in flight.
- `ReplicaDeterminismTest:123-126`: the key-order check passes with insertion
  order.
- Assertions true for any input:
  - `ControllerRetryPolicyTest:48-56`;
  - `HeartbeatServiceTest:82-88`;
  - `RaftMetricsTest:48`;
  - `ShutdownCoordinatorTest:346`.
- `HttpCatalogClientTest:210-237` and similar assume a refused connection is
  immediate. On Windows the timeout fires first.

**Exit gate.** Each rewritten test fails against a mutation; each deleted test
is listed with the reason.

**Status: Done 2026-09-27.** One open decision is recorded below.

- **Fallout from the Step 1 defect fix.** Three `RaftNodeOutboundSnapshotGenerationTest`
  tests failed after the commit-majority fix. They ran a two-member cluster and
  hard-coded peer indexes that were only correct while the leader committed
  alone.
  - With the fix, entries the peer rejected stay uncommitted, so re-election
    appends a leadership no-op.
  - The helper now waits for that no-op to commit and returns the fresh
    leadership's next index. The lowering loop runs until the snapshot
    boundary instead of exactly three times.
- **Rewritten:**
  - **`RaftNodeTest` partition test.** The isolated leader's write must fail
    with `CommandOutcomeUnknownException`, not any exception; a timeout was
    also accepted before.
  - **`EnhancedInMemoryTransportTest` combined chaos.** A state listener
    records every leadership with its term. The test asserts one leader per
    term over the whole run, and that a leader emerges once the chaos clears.
    - This is a smoke check: granting every vote is not caught, because two
      simultaneous candidacies are rare.
    - `RaftNodeModelTest` does catch that deterministically. Dropping the
      vote-once rule fails it at operation 4.
  - **`GrpcRaftTransportTest` and `GrpcRaftServerTest` large and multi-entry
    appends.** They now send real encoded commands (100 KB, 1 MB, and 100
    entries) at a term the target cannot reach alone, and assert success and
    the match index instead of accepting any exception.
  - **The expired-deadline test.** It uses a deadline already past and asserts
    `DEADLINE_EXCEEDED` rather than passing either way.
  - **`HttpApiServerTest` invalid registration and deregistration.** They send
    `X-Qraft-Node` and assert which field the message names.
    - A missing header is now its own assertion.
    - An unknown deregistration answers 200 with `deregistered: false`.
    - The in-flight claim is removed from the sequencer test's name, which now
      states what it proves.
  - **`ReplicaDeterminismTest`.** Twenty agents and twenty metadata keys, in
    reverse order. An unordered writer fails; disabling key ordering failed it.
  - **`ControllerRetryPolicyTest`.** Cancelling a backoff must leave no
    scheduled wake-up. Removing the cancel propagation failed it.
  - **`HeartbeatServiceTest`.** An unregistered agent sends nothing to a
    controller that would accept it. The agent has two guards; removing both
    failed the test.
  - **`RaftMetricsTest`.** The active thread count is 1 while a task runs,
    replacing `>= 0`.
  - **`HttpCatalogClientTest` refusal.** Observed with a bound that cannot
    expire first, and asserted as `transport_error`.
- **Deleted:**
  - **`EnhancedInMemoryTransportTest.testByzantineFailureMode` and the
    simulator's `BYZANTINE` mode.** The mode forges votes and match indexes,
    which no Raft implementation tolerates; the test passed only because
    `leaderCount <= 1` always holds.
  - **`RaftFailureTest.testStateMachineFailures`.** It called its own
    anonymous class.
  - **`ShutdownCoordinatorTest`'s fluent-chaining test.** It asserted only
    non-null and `RUNNING`; phase order is covered by
    `shouldExecuteAllPhasesInOrder`.
- **Verification.** The full suite passes 665 tests: the controller has three
  fewer after the deletions. Five consecutive runs of the new and changed Raft
  tests passed.

**Decision: applying a committed entry that throws (resolved 2026-09-27, fence).**
When `stateMachine.apply` threw, `RaftNode` failed the client, logged the error,
and advanced `lastApplied` past the entry. A replica that failed for a local
reason then diverged silently. So did an old follower in a rolling upgrade that
received a command type it did not know. The user chose to fence.

- **Fix.** `applyLog` now fences through the new
  `RaftTransitionSequencer.fence`.
  - The applied index stays before the entry, and nothing after it is applied.
  - The waiting client receives the failure; other pending writes fail with an
    unknown outcome.
  - A fenced node applies nothing more.
- **Tests.** `RaftNodeApplyFailureTest` covers a leader and a follower. Both
  failed before the fix.
  - The leader keeps its applied index and refuses later writes with
    `FencedException`.
  - The follower applies the entry before the poisoned one, stops at it, and
    refuses later appends.
- **Documentation.** Design section 14.3 records the rule.

### Step 3. Container evidence

Review sections 3 and 6.

1. Delete the placeholders:
   - `DockerRaftClusterTest`'s failover and partition tests;
   - `ConfigurableRaftClusterTest`;
   - `NetworkPartitionTest`'s log-only tests.
2. Replace them with real container tests:
   - **Leader failover.** Kill the leader. The majority elects a new one,
     committed writes survive, and the old leader rejoins as a follower.
   - **Running-leader partition.** Partition the leader without restarting it.
     It steps down and rejects writes; the majority commits, and the cluster
     converges on heal.
3. Make `DockerAgentHealthTest`'s graceful-stop check able to fail: it must
   distinguish deregistration from expiry.
4. Give `CrashedAgentExpiryEndToEndTest` windows that a loaded machine cannot
   miss.

**Exit gate.** The Docker-tagged suite passes, and a mutation disabling each
mechanism makes its test fail.

**Status: Done 2026-09-27**, pending the mutation gate recorded below.

- **Deleted:**
  - `ConfigurableRaftClusterTest` and `TestClusterConfiguration`;
  - `NetworkPartitionTest`;
  - the failover and partition placeholders in `DockerRaftClusterTest`;
  - the now-unused five-node test cluster: `getFiveNodeCluster` and
    `docker-compose-5node-prebuilt.yml`.

  The five-node configurations stay, because the user-facing compose files
  under `docker/` use them.
- **Failover by killing the leader.** Already covered for real by
  `DockerDurableRestartTest.killedLeaderIsReplacedAndRejoinsWithCompleteCatalog`,
  so it is not duplicated.
- **New `DockerRunningPartitionTest`.** It uses its own three-server cluster
  and disconnects a server from every network without restarting it. The
  partitioned server is read from inside its container.
  - **A partitioned leader** stops being leader and answers a write with 503,
    and its readiness is 503. The majority elects a leader and commits. After
    healing there is one leader, every server has the majority's write, and
    none has the refused one.
  - **A partitioned follower** is observed through two elections of its own
    and is never leader in any observation, and it is unready. The majority
    commits, and the follower catches up and is ready after healing.
- **`DockerAgentHealthTest` graceful stop.** It now records whether any
  server ever showed a check expired before the instance disappeared, and
  asserts none did. Expiry holds a check critical for 5 s, so polls at 100 ms
  cannot miss it.
- **`CrashedAgentExpiryEndToEndTest`.** The observation windows went from
  800 ms to 5 s (`deregisterAfterMs`, `nodeReapAfterMs`). Request timeouts went
  from 500 ms to 5 s, the connect timeout from 300 ms to 5 s, and the waits
  from 15 s to 30 s.
- **Result.** The Docker suite passes 19 of 19, down from 30 after removing 13
  placeholders and adding 2 tests.

### Step 4 progress (2026-09-27)

Done:

- **Global state.**
  - `AppConfig.install(AppConfig)` lets a caller reinstall the configuration it
    replaced. `QraftControllerLifecycleTest` restores both the global
    configuration and `qraft.log.dir`.
  - `QraftAgentApplicationTest` restores `qraft.log.dir`.
- **Teardown.** A shared `Cleanup` in `qraft-runtime` runs every teardown step
  and rethrows the first failure, in `AgentEndToEndTest`,
  `AgentHealthPublicationTest`, `AgentControllerContractTest`, and
  `HealthPropagationEndToEndTest`.
- **Shutdown.** `JavaRuntimeExtension` now waits for each runtime to stop. It
  also closes a runtime injected into a class-level method, which covers
  `RaftNodeIntegrationTest`.
- **Timeouts.**
  - `qraft-runtime` and `qraft-agent` get the controller's default method
    timeouts.
  - `DockerDurableRestartTest` has a 10-minute class timeout.
- **Storage-lock contender.** Its wait is bounded at 60 s, and it runs as a
  named container that is force-removed if it keeps running. Its result
  records whether it exited, and the test asserts it did, so a contender
  wrongly serving the volume fails the test instead of hanging.
- **Ordering.** `EnhancedInMemoryTransportTest` no longer imposes a method
  order.
- **`WorkerExecutorTest`.** Its tasks now finish one at a time on a semaphore.
  The measured maximum concurrency is exactly the limit, with no sleeps, no
  self-releasing 2 s latches, and no 1 s waits.

Open:

- free-port-then-bind races in the runtime, gRPC, and agent tests;
- node and durable-storage creation outside try/finally in `RaftNodeTest`,
  `InstallSnapshotTest`, and `GrpcRaftTransportTest`.

### Step 4. Isolation and cleanup

Review section 5.

- **Global state:** restore the global `AppConfig` and the `qraft.log.dir`
  property.
- **Teardown:** guard it in the runtime tests. Create nodes and storage inside
  try/finally.
- **Shutdown:** await runtime shutdown in `JavaRuntimeExtension` and
  `WorkerExecutorTest`, and close `RaftNodeIntegrationTest`'s runtime.
- **Timeouts and blocking:**
  - add a default timeout to `qraft-runtime`;
  - add `@Timeout` to `DockerDurableRestartTest`;
  - bound the lock contender's wait and label its container.
- **Ports:** bind port 0 instead of picking a free port.
- **Ordering:** remove the order dependence in `EnhancedInMemoryTransportTest`.

### Step 5. Determinism

Review section 4.

- **Timers:** move the multi-node Raft tests onto injected timers.
- **Liveness waits:** raise them to at least 10 s in process and 5 s on local
  HTTP. Remove outer timeouts shorter than the waits they wrap.
- **Absence:** prove it exactly, not by waiting:
  - `RecordingListener.assertNoResult`;
  - `pollSnapshot`;
  - `during(2s)`;
  - the short sleeps.
- **Sleeps:** remove them from the gRPC tests and the fakes.
- **Production timeout:** `HttpApiServerTest:480` must not wait the real 5 s
  proposal timeout.
- **Randomness:** seed `InMemoryTransportSimulator`.

### Step 6. Remaining coverage

Review section 6:

- the API envelopes;
- configuration `validate()` branches, and rejection of environment
  placeholders (not implemented);
- the agent items;
- `closePartiallyOpened`;
- `SnapshotData`;
- `qraft-tenant`;
- the remaining container scenarios.

### Step 7. Hygiene

Review section 7:

- behaviour-stating names instead of `test…`;
- the truncated headers;
- dead fixtures and compose files;
- shared fakes;
- `System.out` in tests;
- wildcard imports.

## 5. Out of scope

- New platform features: key/value, consistency modes, sessions, and tenancy
  lifecycle. They follow in their own lists.
- The administrative interface, which stays paused.
