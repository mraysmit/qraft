# Task List: Test Suite Remediation

**Date:** 2026-09-27
**Active work:** None. Steps 1 to 8 are complete. Archived 2026-10-02 with the user's agreement.
**Source review:** [`QRAFT_TEST_SUITE_REVIEW_2026-09-27.md`](../../docs-design/QRAFT_TEST_SUITE_REVIEW_2026-09-27.md), sections 3 to 7
**Predecessor, run alongside:** [`task-list-platform-hygiene-and-readiness-2026-09-27.md`](task-list-platform-hygiene-and-readiness-2026-09-27.md), archived 2026-09-28
**Successor:** [`task-list-raft-membership-changes-2026-09-29.md`](../../docs-design/task-list-raft-membership-changes-2026-09-29.md)
**Paused:** [`task-list-embedded-admin-interface-2026-09-27.md`](../../docs-design/task-list-embedded-admin-interface-2026-09-27.md), after its Step 1
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

This was the current task list for the project until 2026-09-29. When the
active work is complete, add a completion summary, move this file to
`docs/archive/` with the user's agreement, and start a new dated task list for
the next backlog item.

**Completion summary (2026-09-28).**

- Every test can now fail, and fails for the reason its name gives. The tests
  that verified nothing were rewritten or deleted, and the placeholder container
  tests were replaced by real partition and topology tests.
- The older Raft, gRPC, runtime, and agent tests run on manual timers, bind
  port 0, prove absence exactly, and release what they create.
- The coverage gaps of the review's section 6 are closed, and the hygiene items
  of its section 7 are done.
- Nine production defects were found and fixed test first, beyond the five in
  the review's section 2:
  - a commit without a majority in even-sized clusters;
  - a replica that skipped a committed entry it could not apply;
  - the service fingerprint, the health publisher's clock regression, and the
    namespace update race;
  - a blank candidate winning a vote, a send after the gRPC transport stopped,
    and a member set without the node itself;
  - a sub-millisecond Raft timeout acting as zero.
- Defects in the test fakes were fixed too, among them the transport
  simulator's dropped requests and thread leak.
- Last recorded verification: `mvn install` with 705 tests and every coverage
  gate met, and the Docker suite 22 of 22.

**Carried forward.**

- The packaged-artifact test (design section 19.6) waits for the frontend build
  in the paused administrative-interface list.
- Membership changes (section 6, question 1) have their own list, the
  successor above.
- The maximum-term question (section 6, question 2) is still open. It has no
  list yet.

## 1. Goal

Make a clean run of the suite mean what it claims. When this list is complete:

- every test can fail, and fails for the reason its name gives;
- Raft safety rules are checked against the rule the design specifies, including
  the cases a regression would break;
- container tests exercise the failures their names describe;
- no test depends on machine speed, execution order, or state another test left.

## 2. Current state

Updated 2026-09-28.

The five production defects in the review's section 2 are fixed. Steps 1 and
2 fixed two more: the commit majority in even-sized clusters, and replicas that
skipped a committed entry they could not apply.

Steps 1 to 8 are done:

- the tests that verified nothing;
- the placeholder container tests;
- isolation and cleanup;
- determinism;
- the coverage gaps, except the packaged-artifact test;
- hygiene;
- the weak assertions that Step 7's renaming exposed. Strengthening them
  found three more production defects.

Open: the packaged-artifact test, which waits for the admin interface, and the
two questions in section 6.

## 3. Rules

- Red before green. A test for existing behaviour that passes at once is shown
  to fail against a mutation that removes the behaviour, and the mutation is
  recorded.
- Deterministic tests only (`PROJECT_STANDARDS.md`, section 4.4). Mockito is
  prohibited.
- A test that cannot be made meaningful is deleted, not kept for its count.
- A new or changed concurrency test is run repeatedly before its step is done.
- A step is verified with `mvn install`, not `mvn test`. The coverage gates run
  in the `verify` phase, so `mvn test` skips them.
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

**Status: Done 2026-09-27.** The mutation gate is recorded below.

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
- **Mutation gate, against rebuilt runtime images.**
  - **Check-quorum.** Making `majorityRecentlyContacted` always true left the
    partitioned leader leading. The partitioned-leader test failed after its
    30 s bound.
  - **Graceful stop.** Making the reconciler deregister nothing on shutdown
    left the service to expire. The graceful-stop test failed with "a graceful
    stop deregisters the service; it must not be left to expire".
  - Both sources were restored and the runtime image rebuilt clean. The clean
    Docker suite passes 19 of 19.

### Steps 4 and 5 record (2026-09-27 to 2026-09-28)

**Step 4, isolation and cleanup: done.**

- **Global state.**
  - `AppConfig.install(AppConfig)` lets a caller reinstall the configuration it
    replaced. `QraftControllerLifecycleTest` restores both the global
    configuration and `qraft.log.dir`.
  - `QraftAgentApplicationTest` restores `qraft.log.dir`.
- **Teardown.**
  - A shared `Cleanup` in `qraft-runtime` runs every teardown step and rethrows
    the first failure. It is used in `AgentEndToEndTest`,
    `AgentHealthPublicationTest`, `AgentControllerContractTest`, and
    `HealthPropagationEndToEndTest`.
  - `ManualRaftCluster` tracks every node a test builds and stops it after the
    test, which also releases its storage. It covers `RaftNodeTest`,
    `RaftFailureTest`, `InstallSnapshotTest`, and the other tests moved to
    manual timers.
  - `GrpcRaftTransportTest` tracks its transports and pools. Its teardown runs
    every step, and its second target is started inside the `try` that stops
    it.
  - `InstallSnapshotTest` no longer names shared `/tmp` paths for its
    in-memory storage.
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
- **Ports.** Every in-process test binds port 0, and each component reports
  the port it bound:
  - agent `HealthService.port()`;
  - `GrpcRaftServer.port()`;
  - `GrpcServiceServer.port()`;
  - `HttpApiServer.port()`;
  - `RuntimeLifecycle.boundPorts()`.

  Configuration accepts 0 for `agent.httpPort` and for the server's HTTP,
  Raft, and API gRPC ports, as the design now documents. Raft port 0 is
  allowed only on a sole member. A three-member runtime reserves its Raft
  ports together, releases them just before launch, and retries the whole
  launch, at most three times, only on a bind failure.

  Fixed ports remain in two places. The restart tests rebind the port the
  agent already knows. The refused-endpoint helpers use a port they just
  closed.
- **Ordering.** `EnhancedInMemoryTransportTest` no longer imposes a method
  order.
- **`WorkerExecutorTest`.** Its tasks now finish one at a time on a semaphore.
  The measured maximum concurrency is exactly the limit, with no sleeps, no
  self-releasing 2 s latches, and no 1 s waits.

**Step 5, determinism: done.**

- **Manual timers.**
  - `ManualRaftTimers` fires a node's election timeout or heartbeat only when a
    test asks.
  - `ManualRaftCluster` builds nodes on those timers and provides `elect`,
    `heartbeatUntil`, and `heartbeatUntilSteppedDown`.
  - These tests moved onto it: `RaftNodeTest`, `RaftFailureTest`,
    `InstallSnapshotTest`, `LeaderHealthExpiryClusterTest`,
    `HttpApiServerReadinessTest`, `RaftNodeAppliesWhatItLogsTest`,
    `EnhancedInMemoryTransportTest`, and `AgentEndToEndTest`'s controllers.
    `GrpcRaftIntegrationTest` and `RaftNodeTransportGenerationTest` moved
    earlier.
  - The rewritten classes run in about a second each instead of up to 12 s.
- **Check-quorum window.** The manual clusters use a 10 s election timeout with
  200 ms heartbeats. That is a 50-round check-quorum window, and heartbeats
  are paced at one per 200 ms of polling. A leader therefore steps down only
  when a majority is silent for as long as a test waits.
- **Liveness waits.**
  - 147 waits in 28 files were raised to 10 s.
  - An outer wait around a 10 s `awaitState` is now 15 s, so the node's own
    timeout reports first.
  - The runtime tests' local HTTP timeouts were raised to 5 s: 300 ms connect,
    500 ms requests, and a 1 s connect. A slow reply from a live server had
    read as "down".
  - `AgentHealthPublicationTest`'s HTTP check uses a 1 s interval and timeout
    and a 5 s TTL, where it used 100 ms and 600 ms. It asserts PASSING straight
    after waiting for it.

  Timeouts under test stay short: `FutureTest` and the 50 ms client in
  `HttpCatalogClientTest`.
- **Absence** is checked immediately once no path can still produce the
  result:
  - `RecordingListener`;
  - `pollSnapshot` after `settle()`;
  - `GrpcRaftIntegrationTest`;
  - `JavaRuntimeTest`, which uses the scheduler's queue;
  - multi-member recovery in `RaftNodeTest`.
- **Sleeps.**
  - `InMemoryTransportSimulator` holds a request delayed by latency,
    throttling, or reordering in a queue. It hands the request to its pool
    when due, and no longer sleeps a thread.
  - `MockRaftTransport` is deleted.
  - The gRPC tests' sleeps are gone.
- **Production timeout.** `HttpApiServer` takes its Raft timeout as a
  constructor argument (default 5 s). Its test uses 200 ms.
- **Randomness.** The simulator is seeded from `qraft.transport.seed` or the
  node ID, and the seed is logged.
- **Latch-held deadlines.** `AgentRegistrationClientTest` and `JavaRuntimeTest`
  are fixed. Their latches are bounded at 30 s and their results are checked.

**Defects found in the test fakes, each fixed test first.**

- **`ManualRaftTimers`.** A new leader announces its leadership before it arms
  its heartbeat. A test woken by the announcement could fire before the
  heartbeat existed, which is how two `RaftNodeTest` cases failed. The same
  race was latent in `GrpcRaftIntegrationTest`.

  A fire now runs on the state loop behind every task already queued, and
  returns once it has run. `ManualRaftTimersTest` holds the loop with a latch
  to prove the fire waits.
- **`InMemoryTransportSimulator.stop()`.** Stopping dropped requests the pool
  had not yet started, and their callers waited forever. Of 50 requests in
  transit, 40 never completed. Stopping now fails every request it holds or
  has not started.
- **`InstallSnapshotTest`'s stale-term test** passed for the wrong reason. Its
  node was the leader, and the snapshot data was invalid, so other guards
  refused it. It now uses a real follower in term 2 and a valid snapshot. The
  same request in term 2 is then installed, which proves the term alone was
  refused.
- **`EnhancedInMemoryTransportTest`'s chaos test** could not detect a double
  vote. Its candidacies never overlapped. Every other round now fires two
  candidates at once.

**Mutations.** Each was run in an isolated copy of the tree.

| Mutation | Result |
|---|---|
| Multi-member recovery treats the recovered tail as committed | Caught by 2 `RaftNodeTest` cases |
| A step-down leaves pending commands waiting | Caught by `RaftNodeTest`, majority value wins |
| The leader never sends InstallSnapshot to a lagging follower | Caught by 3 `InstallSnapshotTest` cases |
| Check-quorum requires every peer | Caught by `RaftFailureTest`, a leader keeps leading with a majority |
| The stale-term InstallSnapshot guard is removed | Survived at first (see above); caught after the fix |
| A new leader's grace period starts at the epoch | Caught by `LeaderHealthExpiryClusterTest` |
| Every member's expiry acts as leader | Caught by 2 `LeaderHealthExpiryClusterTest` cases |
| A node grants a second vote in a term | Caught by `RaftFailureTest`; missed by the chaos test at first, caught after the fix |

Earlier in Step 5, two generation-guard mutations survived because a
redundant guard caught them. Mutating both guards was caught, in
`RaftNodeOutboundSnapshotGenerationTest` and `RaftNodeTransportGenerationTest`.

**Code review of Steps 4 and 5 (2026-09-28).** Each defect below was fixed,
test first where it had behaviour to test.

- **Simulator delivery thread (introduced in this step).** Starting the thread
  lazily let a send still in flight during `stop()` restart it. The stop then
  waited 10 s for the new thread, and the thread leaked. `stop()` now lets
  in-flight sends finish before the delivery thread stops, and a stopped
  transport never restarts it. Found from the test's 10 s duration; the test
  now also asserts that no transport thread survives `stop()`.
- **Simulator thread leak (existing).** The constructor started a non-daemon
  delivery thread even for a transport that is never started. It now starts
  with the transport or its first held delivery, and all simulator threads are
  named daemons.
- **`ManualRaftTimers`.** An `Error` thrown by a fired action, such as a failed
  assertion in a listener, left the caller waiting 10 s for a misleading
  timeout. It now reaches the caller unchanged.
- **`ManualRaftCluster.heartbeatUntil`** now names a leader that stopped
  leading. It previously reported "no periodic timer is armed".
- **`HttpApiServer`.** The new Raft timeout was converted with `toMillis()`, so
  a positive sub-millisecond timeout acted as zero. It is now applied in
  nanoseconds.
- **`ManagedRuntimeLifecycle`.** A field had lost its indentation.
- **`HttpCheckRunnerTest`.** A latch result was ignored; it is now asserted.
- **`SharedDockerCluster`.** An image-build failure hid the build's output, so
  the one failure in the first Docker run (a build that exited 1, then
  succeeded on the next test) could not be diagnosed. The failure now carries
  the build's last 40 lines. The rerun passed 19 of 19.
- **Verification tooling.** The isolated copy was refreshed with `robocopy`,
  which restores a file's older timestamp. After a mutation run, Maven kept the
  mutated class, and `HttpCheckRunnerTest` failed there although the code was
  fine. Every copy run now deletes the compiled classes first, and the copy
  results were rerun clean.

Left as they are:

- `RaftNodeShutdownSequencingTest` elects a single node on a real 25 ms timer
  over a fake transport. It is the only candidate, so the outcome is fixed.
- `AgentHealthPublicationTest` runs real checks every second. It is an
  end-to-end test by design.

**Verification.**

- The default suite passes, 783 tests with no failures: `mvn test -pl qraft-runtime -am`.
- The Docker suite passes 19 of 19 with a freshly built runtime JAR.
- The changed concurrency classes: 14 classes (113 tests) passed 10 consecutive runs, compiled clean in the isolated copy.

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

### Step 6 progress (2026-09-28)

Done: items 1 to 5. Every new test of existing behaviour was shown to fail against
a mutation that removes the behaviour; all 53 mutations were caught. Four
production changes were made test first:

- **Environment-style placeholders** (design section 16) are now refused in
  server and client documents. `ConfigurationPlaceholders` in `qraft-core`
  refuses any `${` in a string value or field name, naming its JSON path.
- **Defect: the service fingerprint confused tags, metadata, and checks.** A
  service tagged `env`, `prod` had the same fingerprint as one with metadata
  `env=prod`. Moving a value between them was therefore never re-registered,
  and the catalog kept the stale definition. Each list's size is now part of
  the fingerprint.
- **Defect: a health publisher whose clock stepped back stopped renewing.** It
  sent no renewal until the clock passed the last acceptance again plus half
  the TTL, so the controller could expire a healthy check. A clock behind the
  last acceptance now renews at once.
- **Defect: `InMemoryNamespaceService.update` checked, then wrote.** A delete
  between the two was undone. The update is now one atomic `replace`, proven
  by a map that runs the delete inside the update. `find(null)` and
  `delete(null)` are refused like `create(null)`, instead of failing with a
  bare `NullPointerException`.

The items:

1. **API envelopes.**
   - Every agent-route rejection returns the structured error envelope.
   - `service_name_required`.
   - Blank scope headers on writes.
   - `raft_unavailable`, and readiness reporting `unavailable`, when the
     node's state loop is held.
   - The `leaderId` detail on `outcome_unknown`.
   - The readiness envelope with several conditions, in order.
2. **Configuration.**
   - Every `validate()` bound, at and just past it.
   - Unreadable files, named in the error.
   - The agent's defaults, versions, invalid JSON, and non-object roots.
   - Uppercase UI paths.
   - Placeholders.
3. **Agent.**
   - Each retryable seed tried once per cycle, for registration, lookup,
     and observation.
   - The retry policy's cap and input checks.
   - Fingerprint invariants.
   - Publisher client failures (throwing, a failed future, `null`), and
     clock regression.
   - Disabled services' checks. Backoff growth and reset were already
     covered.
4. **`closePartiallyOpened`.** It is now package-private and tested: the
   runtime is released before telemetry, every step runs, and cleanup failures
   are suppressed on the startup failure. Its runtime failure is now
   unwrapped, as `releaseAfter` does.
5. **`SnapshotData`** (with the module's first test dependency), and
   **`qraft-tenant`**. The review's "no tenant scope" is a missing feature,
   left with tenancy lifecycle in section 5.

6. **Containers.** `DockerAgentTopologyTest` adds three scenarios:
   - **Two agents, one service ID.** A second agent container starts once the
     leader is known. It has the same local service ID, and its seeds are an
     offline host, then a follower, then the leader. Each agent's instance is
     its own, and stopping the second removes only its instance.
   - **A running leader cut off.** The leader is disconnected but keeps
     running while the agent publishes. The majority keeps receiving
     publications, and no check expires.
   - **A rolling restart.** Every server restarts in turn while the agent
     publishes. The service is never expired or removed.

   Mutations ran in an isolated copy, with the runtime JAR and image built
   from the mutated code:
   - Removing registration failover was caught by the two-agent test.
   - Removing failover for observations alone was not caught: the agent's
     registration and heartbeat traffic moves the shared seed preference to
     the new leader.
   - Removing failover from both paths was caught by the rolling restart. It
     was also caught by the partition test, in the partition phase, whenever
     the cut-off leader was the agent's first seed.

   A diagnostic run found that a Docker network disconnect can leave
   established connections working, which let the partition test pass once
   without a real partition. That test now proceeds only once the old leader,
   read inside its own container, has stepped down.

   **Open.** The packaged-artifact test (design section 19.6) needs the
   frontend build that the paused admin-interface list adds. Until then an
   enabled server serves no assets. The test's negative half (client mode and
   a disabled server do not expose the UI) cannot fail, because every mode
   answers 404, so it is not written yet.

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

### Step 7 record (2026-09-28)

Done. Each item of review section 7:

1. **Names.**
   - 113 `test…` methods in 11 files now state what they check. The
     remaining three `test…` methods are JUnit `TestWatcher` callbacks.
   - Each new name claims only what the test's assertions check. For example,
     "is answered" means the call returned without error, not that the
     response was right.
   - Two misleading display names and six class Javadocs were corrected.
     `GrpcRaftTransportTest` now says it does not test retries or timeouts.
     `RaftNodeIntegrationTest` now says it does not check timer cleanup.
2. **Headers.**
   - The Apache header is complete in six test files and three main files:
     `RaftPersistence`, `RaftTimerScheduler`, `RaftStorageFactory`.
   - `ShutdownCoordinatorTest` has `@version`.
3. **Dead fixtures.** Deleted:
   - `NetworkTestUtils`;
   - `ExpectsError` and `ExpectsErrorExtension`;
   - both test copies of `otel-collector-config.yaml`;
   - the legacy `qraft-controller.properties`;
   - the unused `docker-compose-test.yml`, `docker-compose-5node-test.yml`,
     and test-resource `docker-compose-network-test.yml`.

   `MockRaftTransport` and `TestClusterConfiguration` were already gone. With
   `ExpectsError` gone, nothing used `qraft-core`'s test-jar, so it and the
   controller's dependency on it were removed. The contract test that required
   it now checks that every test-jar a module depends on is published. It was
   shown to fail when the controller stops publishing its test-jar.
4. **Duplication.**
   - `ManualTimers` (4 copies) is replaced by `ManualRaftTimers`, which gained
     a test for timers armed behind a queued task.
   - `NoOpTransport` (4) and `SingleNodeTransport` (3) are replaced by
     `PeerlessTransport`, whose sends fail and name their target.
   - The `await` (16) and `awaitStateLoop` (8) copies are replaced by
     `RaftAwait`.
   - The `freePort` copies went in Step 5.
   - **Single-node election** had three copies of the same check. The copy in
     `RaftFailureTest` is deleted. Its one unique check, that no leader is
     known before an election, moved to `RaftNodeTest`, where a mutation
     making the node name itself leader fails it. The remaining tests each
     cover something different:
     - the election itself;
     - the durable term and vote;
     - the order of metadata writes;
     - election over real gRPC.
   - Also deleted: a duplicate follower-refusal test, and two tests that only
     read back protobuf builder fields (generated code).
5. **Debug output.** None is left. `ShutdownCoordinatorTest` logs through
   SLF4J. `DirectoryLockProcess` still writes to stdout, because that is how
   it talks to its parent test.
6. **Legacy values.**
   - The catalog tests use `CRITICAL`, not `FAILING`.
     `LegacyCatalogFixtureTest` keeps `FAILING` on purpose.
   - The legacy timeout aliases are removed from `AgentConfiguration`
     (`getHttpConnectionTimeout`, `getHttpIdleTimeout`,
     `Builder.httpConnectionTimeout`). Their tests use `requestTimeoutMs`.
7. **Style.**
   - Wildcard imports are expanded in 23 test files and 4 main files.
   - `assertTrue(x.equals(y))` is `assertEquals` everywhere.
   - Broad `assertThrows` calls now pin the message and cause. That covers
     the codec tests and both state stores' corrupt-snapshot handling, where
     a failed restore now also leaves the state unchanged. Six mutations
     were caught.
   - The compose `version:` key is gone.

**Found on the way.**
- **`mvn install` failed after Step 6.** `SnapshotDataTest`, the first test in
  `qraft-raft-engine`, gave its coverage gate data. The gate then found 50%
  line coverage against a 60% minimum. Step 6 had been verified with
  `mvn test`, which stops before the gates (see the new rule in section 3).
  `SnapshotStoreContractTest` now covers the interface's own contract:
  - the default `closeAsync` closes once, and reports a failed close
    (including an `Error`) through the future rather than by throwing;
  - `SnapshotPublicationException` requires and reports its outcome.

  Five mutations were caught.
- **An assertion tightened earlier in this step named the wrong store's
  exception.** The full build caught it before the step closed. It is
  corrected above.

**Verification.**
- `mvn install`: 732 tests, every coverage gate met.
- The changed Raft, sequencing, and gRPC tests: 5 runs of 236, all green.
- The Docker suite, on an image built from the new JAR: 22 of 22.

### Step 7. Hygiene

Review section 7:

- behaviour-stating names instead of `test…`;
- the truncated headers;
- dead fixtures and compose files;
- shared fakes;
- `System.out` in tests;
- wildcard imports.

### Step 8 record (2026-09-28)

Done. Every test listed below was strengthened or deleted. Every strengthened
or new test was shown to fail against a mutation of the behaviour it now
checks: 26 mutations in process and 1 against the container cluster, all
caught.

**Production defects, each fixed test first.**
- **A vote request naming no candidate won the vote.** A fresh node granted
  term 1 to an empty candidate ID and recorded `""` as its vote. That refused
  every real candidate for the rest of the term, and a request with every
  field unset did the same in term 0. `RaftNode.handleVoteRequest` now
  rejects a blank candidate with `IllegalArgumentException`, before any state
  changes. The gRPC server reports it as `INVALID_ARGUMENT`.
- **A send after `GrpcRaftTransport.stop()` never completed.** It opened a
  new channel that was never closed. The reply's callback went to the
  shut-down executor, whose `CallerRunsPolicy` silently discards tasks after
  shutdown. Sends after stop now fail at once with `IllegalStateException`.
- **A node accepted a member set that did not include itself.** With an empty
  set, its own vote exceeded half of zero, so it would lead a cluster of
  nobody. `RaftNode.Builder.build()` now refuses such a set.

**Tests.**
- **Agent model** (`AgentInfoTest`, `AgentNetworkInfoTest`,
  `AgentSystemInfoTest`).
  - The getter echoes and non-null checks are deleted. Round trips of every
    field replace them, compared against literal values, so a setter that
    drops its value fails.
  - New tests cover `AgentInfo.copyOf`, which the state store relies on,
    `getEndpoint`, equality by agent ID alone, and null metadata.
  - `toString` is checked as `field=value` pairs, which also removes the
    `8`-in-`x86_64` false pass. The hash-code inequality assertion is gone.
- **`GrpcRaftServerTest`.**
  - Its node runs on manual timers. The real 5 s election timeout could
    elect the node during a test and change every vote result.
  - Each vote and heartbeat test now asserts the decision and the term. That
    covers:
    - one vote per term;
    - adopting a higher term;
    - refusing a log that ends before index 0;
    - following a new leader;
    - rejecting a blank or empty request.
  - The two-server test now shows each server serves its own node's state.
  - Four tests are deleted: the unasserted start, the rapid start and stop,
    the generous-deadline test, and the cleared-entries test, which repeated
    the heartbeat test.
- **`GrpcRaftTransportTest`.**
  - Each request is shown to reach the named peer, and the peer's decision
    and state to come back intact.
  - The pool test is replaced. It never exceeded the pool, and could not
    through the public API. The callback pool is now built by a
    package-private factory, tested directly: a full pool and queue run the
    callback on the delivering thread, and pool threads are daemons.
- **Raft node tests.**
  - `RaftFailureTest`'s empty-membership test now checks the refusal.
  - `RaftNodeIntegrationTest` is deleted. In its place, `RaftNodeTest` shows
    that stopping a follower cancels its election timer, and stopping a
    leader cancels its heartbeat.
  - `RaftNodeTest`'s `LogEntry` test moved to a new `LogEntryTest`. Its
    state-store test repeated `ControllerStateStoreTest` and is deleted.
  - `DockerRaftClusterTest` requires every server to name the same leader in
    the leader's term. It timed out, as it should, against an image in which
    followers never record the leader.

**Verification.**
- `mvn install`: 705 tests, every coverage gate met.
- The Docker suite, on an image built from the new JAR: 22 of 22.

### Step 8. Weak assertions

Renaming the `test…` methods to say what they check showed that about 45 of
them check almost nothing:
- `assertNotNull` on a response;
- no exception thrown;
- a getter returning what its setter was given;
- `getTerm() >= 0`, which always holds.

Each is either strengthened, with a mutation that fails it, or deleted under
the section 3 rule that a test which cannot be made meaningful is deleted.
They are:

- **`GrpcRaftServerTest`** (14).
  - The RequestVote tests at term 0, the maximum term, an empty or 10,000-character
    candidate ID, a negative log index, and every field unset never check
    the vote decision or the returned term.
  - The heartbeat test never checks `getSuccess()`.
  - The "explicitly cleared entries" test repeats the heartbeat test.
  - The generous-deadline test adds nothing.
  - The start and stop tests never show a port bound or released.
  - The two-server test never shows the servers are independent.
- **`GrpcRaftTransportTest`** (10).
  - The responses are only checked non-null. Their term, vote, success, and
    match index go unchecked.
  - The two-peer test never shows which peer answered.
  - The restart test never shows the stopped transport refusing sends.
  - The pool test never exceeds the pool size or the 500-request queue it
    was written for.
- **`AgentInfoTest`, `AgentNetworkInfoTest`, `AgentSystemInfoTest`** (about
  25). Most are getter and setter echoes, and the default-constructor tests
  check only non-null. Specific defects:
  - `toString` "contains `8`" is already satisfied by `x86_64`.
  - The hash codes of two different agents are required to differ, which the
    `hashCode` contract does not promise.
  - Equality is never shown to depend on the agent ID alone.
  - The network JSON round trip uses the default `false` for NAT traversal
    and compares list sizes, not contents.
  - The agent JSON round trip never checks `status`.
- **Raft node tests.**
  - `RaftFailureTest.aNodeBuiltWithNoMembersIsAFollower` checks an unstarted
    node's fields. Decide whether an empty membership is refused, and test
    that.
  - `RaftNodeIntegrationTest`'s two tests check only `isRunning`, overlap
    `RaftNodeTest`, and do not check timer cleanup.
  - `DockerRaftClusterTest.theClusterElectsExactlyOneLeader` does not check
    that the followers agree on the leader.
  - `RaftNodeTest`'s state-store and `LogEntry` tests belong with those types.

## 5. Out of scope

- New platform features: key/value, consistency modes, sessions, and tenancy
  lifecycle. They follow in their own lists.
- The administrative interface, which stays paused.

## 6. Open questions

Found in Step 8. Both change Raft behaviour, so they need a decision first.

1. **Membership changes, and servers that disrupt elections.**
   - Membership is fixed at startup. `clusterNodes` never changes, and there
     is no configuration-change log entry. Adding a server therefore means
     editing every server's configuration and restarting. During that
     rollout, old and new configurations can each form a majority.
   - Refusing votes to candidates outside `clusterNodes` is not the fix. Raft
     (thesis section 4.1) has servers process requests without consulting
     their configuration, so that a joining server, or one whose log lags,
     is not locked out.
   - Protection from a removed or misconfigured server belongs to the
     leader-stickiness rule or pre-vote (thesis section 4.2.3).
   - Membership changes now have their own list:
     [`task-list-raft-membership-changes-2026-09-29.md`](../../docs-design/task-list-raft-membership-changes-2026-09-29.md).
2. **The maximum term.** One vote request at `Long.MAX_VALUE` moves a node to
   that term. The node's next election would overflow the term to a negative
   number. A node could instead refuse terms that leave no room for another
   election.
