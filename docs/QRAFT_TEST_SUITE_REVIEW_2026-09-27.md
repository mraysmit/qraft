# Qraft Test Suite Review

**Date:** 2026-09-27
**Scope:** every test source and test resource in the reactor. The default suite
has 738 tests; the Docker-tagged suite has 30.
**Method:** four independent read-only reviews, one per area, against
[`PROJECT_STANDARDS.md`](PROJECT_STANDARDS.md) section 4 and the design
documents. Every finding that implies a production defect or a production
deletion was verified against the code before inclusion.

## 1. Summary

| Module | Test files | Test methods | Lines |
|---|---|---|---|
| qraft-controller | 78 | 441 | 22,723 |
| qraft-agent | 21 | 107 | 3,862 |
| qraft-core | 14 | 96 | 1,822 |
| qraft-runtime | 8 | 36 | 2,438 |
| qraft-distributed-state | 5 | 20 | 430 |
| qraft-tenant | 1 | 5 | 81 |
| qraft-raft-engine | 0 | 0 | 0 |

The tests written since the health-propagation work are strong. They drive time
with injected clocks and manual timers, hold races open with latches and gated
storage, prove absence exactly, and each was shown to fail without the behaviour
it checks. This includes:

- the sequencing, recovery, check-quorum, and follower-log tests;
- the health evaluator and state-store tests;
- the agent check runners, publisher, and reconciler tests;
- `DeadlinesTest` and `QuiescenceTest`;
- the newest Docker agent tests.

Two problems undermine the suite as evidence:

1. **Some tests verify nothing.** About half of the Docker-tagged suite has
   placeholder tests. Their names claim leader failover, partitions, and network
   stress, but their bodies only wait for one leader and log. Across the suite,
   about a dozen more tests cannot fail, swallow the failure they exist to
   catch, or pass for the wrong reason.
2. **The older layer breaks the determinism standard systematically.** This
   covers the older Raft multi-node and gRPC tests, the runtime end-to-end tests,
   the agent lifecycle tests, and the worker and lifecycle tests. They use real
   elections with 2 to 5 second waits, sleeps used as synchronization, absence
   proven by waiting, sub-second margins, and free-port races. These are the
   likeliest sources of future flaky failures.

The review also found production defects, listed next.

## 2. Production defects found by the review

All five were verified against the code. All five were fixed on 2026-09-27, each
with a test that failed first.

1. **Recovery does not check the snapshot overlap term.** Design section 14.5,
   step 5, requires recovery to reject WAL entries that overlap the snapshot
   with a conflicting term. `RaftNode` recovery skips every entry at or below
   `snapshotLastIndex` without comparing terms.

   A follower publishes an installed snapshot before it truncates the
   conflicting WAL suffix. After a crash between the two, recovery loaded the
   divergent suffix into the log above the snapshot, contrary to section 14.5.

   On a multi-node follower the entries were not applied: commit is limited to
   leader-verified entries, the leader's consistency check replaces them, and
   the election restriction keeps the node from leading. The fix removes the
   reliance on those safeguards.

   **Fixed.** When the WAL entry at the snapshot boundary carries another term,
   recovery discards that entry and every later one. It completes the
   interrupted suffix and prefix truncation in an owned transition before the
   node starts. Design section 14.5 records the behaviour.

   `RaftNodeInstalledSnapshotRealRecoveryTest` covers the edge cases. Each of
   the first two fails without the fix.
   - **A real crash.** A child process installs a conflicting snapshot and
     halts once it is durable, through the new crash-writer checkpoint
     `AFTER_DIVERGENT_SNAPSHOT_PUBLICATION`. The test confirms the window is
     real: a term-99 snapshot sits beside an untrimmed WAL `[1, 2, 3, 4]`. The
     follower restarts in its real two-member cluster. Without the fix its log
     holds the stale entry (last index 4); with it the log ends at the
     boundary.
     - A leader then appends index 4 after the boundary, and it is applied.
     - The WAL finishes as `[4]`.
   - **An interrupted compaction.** Recovery's own compaction is interrupted
     after the suffix truncation, leaving `[1, 2, 3]`. The next recovery
     completes it.
   - **A failed compaction.** When the WAL sync fails during the compaction,
     startup fails rather than serving a half-compacted log. The next start
     completes the compaction.

   The earlier single-node test was replaced: a single-node cluster never
   receives an installed snapshot.
2. **A failed registration does not use the error envelope.** When a service
   registration command does not succeed, `HttpApiServer.registerService`
   answers 409 with the success-shaped `ServiceRegistrationResponse` (`registered: false`)
   instead of the error envelope (`code`, `message`, `retryable`, `requestId`)
   that design section 12.2 requires for errors.

   **Fixed.** It now answers 409 `registration_rejected` in the error envelope.
   The agent already classifies any error envelope as a rejection.
   `HttpApiServerTest
   .registrationTheStateMachineDoesNotAcceptAnswersWithTheErrorEnvelope` drives
   the path through a state machine that declines registrations.
   `HttpCatalogClientTest.aRegistrationTheCatalogRejectsIsNotRetried` shows the
   agent classifies the answer as a non-retryable rejection. The path is
   unreachable with the current state machine, which accepts every
   registration.
3. **Discovery ignores tenant and namespace.** `ServiceCatalog.instances(name)`
   filters by service name alone, although design section 7.5 groups discovery
   by tenant, namespace, and service name. This is feature validation item 6; no
   test pins the intended behaviour.

   **Fixed.**
   - `ServiceCatalog` lookups take the existing `ServiceKey`, the discovery
     key, and `services` takes a tenant and namespace. No lookup by service
     name without a scope remains. `instances()`, which lists every scope for
     replication and expiry, is still public.
   - This changes the HTTP contract: a client that registers outside the
     default scope and reads without scope headers now receives an empty
     result. The agent always sends its scope.
   - The three discovery reads honour the optional scope headers and reject a
     blank one with 400 `invalid_scope`.
   - Readers of non-default scopes were updated: `HealthCommandStateStoreTest`,
     two `HttpApiServerTest` assertions, and `DockerDurableRestartTest`, which
     was updated before it ran.
   - Tests: `ServiceCatalogTest.lookupsNeverCrossTenantOrNamespace`, and
     `HttpApiServerTest.catalogAndHealthReadsAreScopedByTenantAndNamespace` and
     `readsRejectBlankScopeHeaders`.
   - `HttpCatalogClientTest.lookupMatchesTheCompleteScopedIdentityAndClassifiesAbsence`
     now asserts that the reconciler's catalog lookup sends the agent's scope.
     Without it, an agent outside the default scope would find its own
     services absent on every pass and register them again. The assertion
     fails when the tenant header is removed.
   - A blank service name is rejected by the path parser before a
     `ServiceKey` is built, so it still answers `service_name_required`.
   - The contract is in design section 12.
4. **The agent's time is not injectable.** `QraftAgent` creates its schedulers
   and HTTP clients itself, so its lifecycle tests cannot follow the standard's
   injected-scheduler rule and fall back to real time.

   **Fixed.**
   - A package-private constructor takes both schedulers.
   - `QraftAgentTest` drives retries and heartbeats with a
     `ManualScheduledExecutor`. It proves "never retried" by an empty schedule
     instead of a 100 ms wait. That assertion fails when the agent is made to
     retry a rejection.
   - The shutdown-ordering test no longer races a 2 s shutdown deadline.
   - `registrationBackoffGrowsWhileTheControllerIsDownAndRestartsAfterSuccess`
     covers the coverage gap in section 6. It pins the retry delays at 5, 10,
     and 20 ms, then shows that after a successful registration a lost node
     starts again at 5 ms. It fails when the reset is removed.
   - `shutdownCancelsAPendingRegistrationRetry` shows that no registration is
     attempted after shutdown.
   - Not covered: a heartbeat rejected while a retry is already pending must
     not schedule a second retry. The guard is a compare-and-set, but proving
     the absence exactly needs a completion hook on the heartbeat that the
     agent does not expose.
   - The HTTP clients stay real: the tests run them against a local controller
     stub, the protocol-level fixture section 4.2 prefers.
5. **Dead code from the file-transfer project remains in `qraft-core`.** Nothing
   in production uses any of the following; only tests keep them alive:
   - `AgentStatus.canTransitionTo`, and `InvalidTransitionException`;
   - `HealthStatus` and `HealthDetail`, whose UP, DOWN, and DEGRADED states
     conflict with design section 7.6;
   - the capacity and bandwidth scoring in `AgentSystemInfo` and
     `AgentNetworkInfo`;
   - `AgentCapabilities.supportsService` and `isAvailableInRegion`.

   **Fixed.**
   - These are deleted, with `QraftException`, whose only subclass was
     `InvalidTransitionException`, and the `monitoring` package.
   - Their tests are deleted with them, including `AgentStatusTransitionTest`.
     This removes about 1,200 lines and 97 test cases.
   - The derived scoring getters had been serialized as computed JSON fields,
     so agent JSON responses no longer carry them. Nothing in the repository
     reads them: replicated state uses protobuf, and every Jackson reader
     ignores unknown fields. An external client of those responses would see
     the fields disappear.
   - `AgentSystemInfoTest` and `AgentNetworkInfoTest` now pin the exact
     serialized field set, so a computed getter cannot silently rejoin the wire
     format.
   - The pin exposed an older defect. `AgentNetworkInfo` serialized the NAT
     flag twice, as `isNatTraversal` and `natTraversal`. It now writes only
     `isNatTraversal`, its declared name, and still reads `natTraversal` from
     older agents.

### 2.1 Found during remediation

6. **Even-sized clusters committed without a majority.** `updateCommitIndex`
   took `indices.get(size / 2)` of the ascending match indexes. That is held by
   only half of an even-sized cluster, so a two-member leader committed alone
   and a four-member leader on two copies.

   Fixed on 2026-09-27 with `RaftNodeLeaderCommitTest`; see Step 1 of
   [`task-list-test-suite-remediation-2026-09-27.md`](task-list-test-suite-remediation-2026-09-27.md).
   Every existing multi-node test used three or five members, the odd sizes
   where the median is a majority.
7. **A replica skipped a committed entry it could not apply.** When the state
   machine threw, `applyLog` failed the client and advanced `lastApplied` past
   the entry. A replica that failed for a local reason, or an old server in a
   rolling upgrade, diverged silently.

   Fixed on 2026-09-27 by fencing, the user's decision; see Step 2 of the task
   list. The only test that claimed to cover state-machine failures,
   `RaftFailureTest.testStateMachineFailures`, called its own anonymous class
   and never reached `RaftNode`.

## 3. Tests that verify nothing or pass for the wrong reason

| Test | Problem |
|---|---|
| `DockerRaftClusterTest` leader failure and partition tests | Kill or partition nothing; one stops at a TODO. Deleted 2026-09-27; replaced by `DockerRunningPartitionTest`, with failover already covered by `DockerDurableRestartTest` |
| `ConfigurableRaftClusterTest` (8 tests) | The cluster configuration never reaches the containers, and `applyNetworkStress` only logs; every test reduces to "one leader". Deleted 2026-09-27 |
| `NetworkPartitionTest` split-brain and recovery | Log statements only. The minority check treats an unreachable node as success. Deleted 2026-09-27; replaced by `DockerRunningPartitionTest` |
| `RaftNodeTest:247` | `assertThrows(Exception.class, …get(5s))` also accepts a timeout. Fixed 2026-09-27 |
| `EnhancedInMemoryTransportTest:317, 442` | `leaderCount <= 1` holds before any election. Fixed 2026-09-27: the Byzantine test is deleted and the chaos test checks one leader per term |
| `RaftFailureTest:264-315` | Exercises a local anonymous class, not `RaftNode`. Deleted 2026-09-27; the real behaviour is an open decision (task list Step 2) |
| `GrpcRaftServerTest` large-entries and short-deadline tests; `GrpcRaftTransportTest:440, 480` | Catch the only failure path. Fixed 2026-09-27 |
| `RaftNodeModelTest:206, 253` | The reference model encodes the match-index and commit rule the design rejects. Its generator never produces the case that would catch a regression. Fixed 2026-09-27 |
| `DockerAgentHealthTest` graceful stop | Expiry deregisters within the wait anyway, so skipped deregistration still passes. Fixed 2026-09-27 |
| `CrashedAgentExpiryEndToEndTest` | 800 ms windows sampled by polls with 500 ms timeouts, so it can miss the window under load. Fixed 2026-09-27: 5 s windows |
| `HttpApiServerTest:422-432, 546` | The 400 comes from the missing `X-Qraft-Node` header, not the invalid body. Fixed 2026-09-27 |
| `HttpApiServerTest:497-524` | Never shows the second request in flight, so it can pass with requests handled one after the other. Renamed 2026-09-27 to what it proves |
| `ReplicaDeterminismTest:123-126` | Keys are inserted already in sorted order, so a `LinkedHashMap` passes. Fixed 2026-09-27 |
| `ControllerRetryPolicyTest:48-56`, `HeartbeatServiceTest:82-88`, `RaftMetricsTest:48`, `ShutdownCoordinatorTest:346` | Assertions true for any input. Fixed 2026-09-27; the fluent-chaining test is deleted |
| `HttpCatalogClientTest:210-237` and similar | Assume a refused connection is immediate. On Windows it times out first, and the test passes on the wrong outcome. Fixed 2026-09-27 |
| `AgentStatusTransitionTest` | Restates the production transition table, which nothing enforces. Deleted with the table on 2026-09-27 |

## 4. Determinism (standards section 4.4)

| Pattern | Where |
|---|---|
| Real elections with 2–5 s waits | `RaftNodeTest`, `RaftFailureTest`, `InstallSnapshotTest`, `LeaderHealthExpiryClusterTest` (250 ms election timeout, exact proposal counts), `AgentEndToEndTest` |
| Liveness waits below 10 s in process, or below 5 s on local HTTP | About 213 occurrences in 39 files: most `*SequencingTest` `await` helpers (5 s); runtime tests (300–500 ms HTTP, 2 s catalog); agent tests (1 s HTTP timeouts, `RecordingListener` 5 s); `WorkerExecutorTest` (1 s) |
| Outer 5 s timeouts wrapping 10 s awaits | `RaftNodeCheckQuorumTest:154, 186`, `RaftNodeTimerSequencingTest:269`, `RaftNodeOutboundSnapshotGenerationTest:150` |
| Absence proven by waiting | `RecordingListener.assertNoResult` (100 ms) in 4 check-runner tests; `pollSnapshot(100–200 ms)`; `during(2s)` in `GrpcRaftIntegrationTest`; `JavaRuntimeTest:114` (80 ms sleep); `WorkerExecutorTest` (50 ms sleeps); `QraftAgentTest:158` (fixed 2026-09-27) |
| Sleeps and disguised sleeps | `GrpcRaftIntegrationTest:334`; `GrpcRaftServerTest:806, 819`; `InMemoryTransportSimulator` (4); `MockRaftTransport` (3) |
| Real production timeout in a test | `HttpApiServerTest:480` waits the hard-coded 5 s proposal timeout, with a 2 s upper margin |
| Real deadlines held by latches | `QraftAgentTest:391-449` (2 s shutdown deadline; fixed 2026-09-27); `AgentRegistrationClientTest:154-168`; `JavaRuntimeTest:71` (2 s latch whose result is ignored) |
| Unseeded randomness | `InMemoryTransportSimulator:67`, so chaos runs cannot be reproduced |
| Real-timer race | `RaftNodeTransportGenerationTest:129-143` |

## 5. Isolation and cleanup

- **Global state leaks.**
  - `QraftControllerLifecycleTest` installs a global `AppConfig` and never
    restores it, so later tests see node-a's configuration.
  - `QraftAgentApplicationTest` sets the `qraft.log.dir` system property and
    never restores it.
- **Free-port-then-bind races.** In every runtime test (worst:
  `HealthPropagationEndToEndTest`, with 9 ports), the gRPC fixtures, and every
  `QraftAgentTest`.
- **Unguarded teardown.** `@AfterEach` methods in `AgentEndToEndTest`,
  `AgentHealthPublicationTest`, `AgentControllerContractTest`, and
  `HealthPropagationEndToEndTest` skip later cleanup when an earlier step
  throws. Nodes and durable storage are created outside try/finally in
  `RaftNodeTest`, `InstallSnapshotTest`, and `GrpcRaftTransportTest`, and
  durable storage holds Windows file locks.
- **Unawaited shutdown.** `JavaRuntimeExtension` and `WorkerExecutorTest` do not
  wait for the runtime to close. `RaftNodeIntegrationTest`'s class-level runtime
  is never closed.
- **Unbounded or undersized blocking.**
  - `join()` without a timeout (54 in `RaftNodeTest`), bounded only by the
    90-second JUnit default.
  - `qraft-runtime` has no default JUnit timeout.
  - `DockerDurableRestartTest` has no `@Timeout` although its waits add up to
    about 270 s.
  - The `SharedDockerCluster` lock contender uses an unbounded `waitFor()`.
    Its `docker run` container carries no Testcontainers label, so it could
    leak.
- **Order dependence.** `EnhancedInMemoryTransportTest` uses
  `@TestMethodOrder(OrderAnnotation)`.
- **Fake defects.**
  - `InMemoryTransportSimulator` swallows exceptions in delayed delivery, so
    the promise never completes.
  - It drops queued messages on `stop()`.
  - It joins without a timeout.
  - A "crashed" node still receives RPCs.

## 6. Coverage gaps

- **Raft.**
  - The recovery overlap term check (section 2, item 1).
  - The non-contiguous replay failure paths.
  - Repair of a torn final record, and that `RaftNode` fences on
    `CorruptLogException`.
  - The leader's current-term-only commit rule (Figure 8 of the Raft paper).
  - Leader append failure leaving memory unchanged.
  - The WAL prefix truncated after follower compaction.
  - Multi-chunk `InstallSnapshot`, including out-of-order chunks.
  - A candidate stepping down on a same-term AppendEntries.
  - Command replication over real gRPC.
- **HTTP API.**
  - The readiness 503 envelope, the `unavailable` condition, and several
    conditions failing at once.
  - Error envelopes on the agent routes.
  - `service_name_required`, `service_id_required`, `raft_unavailable`, blank
    scope headers, and the `leaderId` detail on `outcome_unknown`.
- **Configuration.**
  - The server `validate()` branches: ports, I/O bounds, timing, snapshot, and
    Prometheus port.
  - A missing or unreadable file.
  - The agent defaults.
  - Unsupported versions and invalid JSON on the agent side.
  - Uppercase UI paths.
  - Environment-style placeholders, which design section 16 forbids: neither
    rejected in production nor tested.
- **Agent.**
  - Backoff growth and reset across registration cycles.
  - Every retryable seed tried exactly once per cycle.
  - Fingerprint invariants.
  - `HealthPublisher` client failures and clock regression.
  - Disabled services' checks skipped.
- **Lifecycle.** `closePartiallyOpened`; `QraftControllerService` wiring.
- **Other modules.**
  - `qraft-raft-engine`: `SnapshotData` has real validation and defensive
    copies, and no tests.
  - `qraft-tenant`: update, null, and concurrency paths; no tenant scope.
- **Containers.**
  - Two agents with the same local service ID.
  - A first seed offline or a follower.
  - A running old leader partitioned while an agent publishes. The only
    helper restarts the container.
  - A rolling server restart.
  - The packaged-artifact test of design section 19.6, which is known to be
    open.

## 7. Hygiene

- **Names.** About 186 methods named `test…`, concentrated in the older Raft,
  gRPC, and `qraft-core` model tests. Their type Javadoc often does not state
  the behaviour verified. Some names mislead: `RaftFailureTest.testNetworkPartition`
  stops a node, and the Docker placeholders claim coverage they lack.
- **Headers.** Nine Raft test files have a truncated Apache header, missing the
  "Unless required…" paragraph. `ShutdownCoordinatorTest` and
  `InstallSnapshotTest` lack `@version`. `MockRaftTransport` carries a
  conflicting proprietary header.
- **Dead fixtures.** `MockRaftTransport`, `NetworkTestUtils` (550 lines),
  `TestClusterConfiguration`, which sets a `JAVA_OPTS` environment variable,
  three unused compose files, `ExpectsError` and `ExpectsErrorExtension`,
  `otel-collector-config.yaml` twice, and a legacy
  `qraft-controller.properties`.
- **Duplication.**
  - `ManualTimers` ×3, `NoOpTransport` ×4, `SingleNodeTransport` ×3.
  - `freePort` ×6.
  - About 12 copies of `await` and `awaitStateLoop`.
  - Single-node election is tested four times.
- **Debug output.**
  - `System.out` in `RaftNodeTest`, `RaftFailureTest`, and
    `ShutdownCoordinatorTest`.
  - `printStackTrace` in `GrpcRaftIntegrationTest`.
- **Legacy values used as the normal case.** `ServiceHealth.FAILING` is used as
  the unhealthy state in two catalog tests, and the configuration tests assert
  legacy timeout aliases.
- **Style.**
  - Wildcard imports in 7 controller test files.
  - `assertThrows(RuntimeException.class)` that is too broad.
  - `assertTrue(x.equals(y))`.
  - The obsolete `version: '3.8'` key in every compose file.

## 8. Recommended remediation

In order:

1. **Fix the production defects in section 2**, test first. Done 2026-09-27,
   including catalog scoping.
2. **Remove or rebuild the tests that verify nothing** (section 3). Delete the
   Docker placeholders, and replace them with real container tests of leader
   failover and a running-leader partition. Correct `RaftNodeModelTest`'s
   reference rule.
3. **Bring the older layer onto the determinism standard** (sections 4 and 5):
   - injected timers for the multi-node Raft tests;
   - an injected scheduler for `QraftAgent`;
   - at least 10 s liveness bounds;
   - absence proven exactly;
   - port 0 in place of free-port races;
   - guarded teardown, and restored global state;
   - a default timeout for `qraft-runtime`, and `@Timeout` on
     `DockerDurableRestartTest`.
4. **Close the coverage gaps** (section 6), starting with the Raft and API
   items.
5. **Hygiene** (section 7): delete the dead fixtures, extract shared fakes, and
   rename the `test…` methods to state their behaviour.

Items 1 to 3 should come before new feature work. Until they are done, a clean
run of the suite overstates the evidence, and its older half remains the
likeliest source of intermittent failures.
