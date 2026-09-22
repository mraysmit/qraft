# Tranche 8 Task List: System and Failure Verification

**Date:** 2026-09-22
**Archive status:** Closed and archived on 2026-09-22.
**Status:** Complete. All six steps implemented and verified 2026-09-22.
**Source plan:** [`RAFTLOG_EXTERNALISATION_TDD_PLAN.md`](RAFTLOG_EXTERNALISATION_TDD_PLAN.md), Tranche 8
**Runbook:** [`RAFT_STORAGE_OPERATIONS.md`](../RAFT_STORAGE_OPERATIONS.md)

## Completion summary

| Step | Status | Covering tests |
|---|---|---|
| 1 Container restart | Done | `DockerDurableRestartTest`: whole-cluster restart, snapshot plus WAL suffix restart, killed follower, killed leader |
| 2 Corruption and fencing | Done | `HttpApiServerTest.corruptWalFencesStartupWithoutMutatingTheEvidence`; `DockerDurableRestartTest.corruptFollowerStaysLiveButUnreadyWhileHealthyQuorumServes`; `FileSnapshotStoreTest.unpublishedFirstSnapshotTemporaryFileFencesOpenAndIsPreserved`; `RaftNodeRealSnapshotRecoveryTest.restartWithUnpublishedFirstSnapshotFencesAndPreservesTemporary` |
| 3 Directory lock | Done | `RaftStorageProcessLockTest.secondJvmCannotOpenDirectoryWhileOwnerRemainsHealthy`; `DockerDurableRestartTest.secondContainerCannotOwnAnActiveNodeVolume` |
| 4 Partition and snapshot catch-up | Done | `DockerDurableRestartTest.partitionedFollowerCatchesUpBySnapshotAndSurvivesRestart`; `NetworkPartitionTest.testMajorityPartition` re-enabled with real Docker network isolation |
| 5 Leader crash and retry | Done | `HttpApiServerTest.reportsUnknownOutcomeWhenHttpWriteTimesOut`; `DockerDurableRestartTest.retryAfterLeaderCrashConvergesToOneCatalogInstance` |
| 6 Operational documentation | Done | `RAFT_STORAGE_OPERATIONS.md`, with a verification-coverage section naming the backing tests |

Verification on 2026-09-22: full reactor green (controller 307 including 8 Docker
acceptance tests; core 231; distributed-state 14; agent 13; runtime 13; tenant 5),
zero failures, errors, or skips. `NetworkPartitionTest` run separately under the
`docker` tag: 3 of 3 passed. No Mockito introduced. `git diff --check` clean.

Production changes: `CommandOutcomeUnknownException` and the HTTP
`outcome_unknown` / `retryable` mapping; leadership no-op after recovery with an
uncommitted suffix; fenced recovery keeps the node live but unready; snapshot
store refuses to open over an unpublished first-snapshot temporary; readiness
endpoint alone reports fenced state (general `/health` no longer does).

Deferred, tracked under the platform design's Tranche 2 rather than here:
composite catalog identity, applied-index response header, HTTP request-ID
propagation, full structured error envelope.

The original plan follows for reference.

---

This plan is ordered so each step builds the fixtures the next one needs, and each step follows the project's red-green rule: write the failing system test first, change production code only when the test exposes a real defect.

## Step 1: Container restart preserves durable state (scenarios 8 and 1)

**Why first.** The compose cluster already mounts a named volume per controller at `/app/data`, and the shared Docker fixture already builds the image once and hands out a three-node cluster. The missing piece is a test that stops and restarts containers and checks state, so this step is mostly test code.

**Preparation checks.** Two facts need confirming before writing the test, because the volume is useless if either is wrong:

- The container's Raft storage path must resolve inside `/app/data`. The property is `qraft.raft.storage.path` in the controller configuration. Confirm the compose environment or packaged properties point it there rather than a working-directory default.
- Snapshot settings in the container must allow a snapshot to be forced within the test. The threshold property exists, but the compose files do not set it, so the test will need to set a low threshold through the environment.

**Fixture work.** Extend the shared Docker cluster helper with container-level operations it does not have today: stop one container, kill one container without graceful shutdown, start it again, and stop and start the whole cluster while keeping volumes. Testcontainers' compose support exposes the container IDs, so these become `docker stop`, `docker kill`, and `docker start` calls alongside the existing network manipulation helpers.

**Tests to write, in this order.**

1. Register several services through the HTTP register endpoint on the leader, read them back from all three nodes, stop the whole cluster with volumes retained, start it, wait for readiness, and read the same catalog from each node. Also assert the leader's term after restart is at least the pre-restart term, which proves metadata persistence.
2. Repeat with enough registrations to cross the snapshot threshold, so recovery must load a snapshot plus a WAL suffix rather than a full WAL.
3. Kill a single follower rather than stopping it, restart it, and confirm it converges to the same catalog. This is the container form of scenario 2.
4. Kill the leader, wait for re-election, restart the old leader, and confirm it rejoins as a follower with the full catalog.

**Expected exit.** These tests run under the existing `docker` tag and pass. If any fail, the defect is real production behaviour and gets a regression test at the unit level as well.

## Step 2: Node-level corruption and fencing (scenario 6)

**Current state.** The RaftLog integration test proves a complete corrupt record fails replay at the storage layer. Readiness already returns 503 with a fenced status when the node is fenced. What is untested is the chain from a corrupt file on disk to a process that refuses readiness and logs an actionable message.

**Tests to write.**

1. In-process: build a durable node against a real WAL directory, write entries, close it, corrupt a complete interior record by flipping bytes, reopen through a new node. Assert startup fails or the node reports fenced, readiness returns 503, and the log contains the directory path and the record index or offset. Assert the corrupt file is not modified or truncated.
2. Container: with the cluster stopped, corrupt the WAL inside one controller's volume using a short-lived helper container that mounts the same volume, then start the cluster. Assert the two healthy nodes form a quorum and serve reads, the corrupted node's readiness endpoint returns 503 with the fenced status, and its logs name the failure.
3. The same for an unpublished first-snapshot temporary file, which the postmortem says fences startup and preserves the file.

**Likely production change.** The error message may need to be made actionable if it currently just surfaces the library's exception text. The standards require that failures describe operational impact.

## Step 3: Process-level directory lock (scenario 7)

**Current state.** Storage-level tests prove a second opener in the same JVM is refused and that close releases the lock. Nothing proves it across OS processes.

**Tests to write.**

1. Launch two separate JVMs pointed at the same storage path using the same separate-JVM harness the real-storage recovery tests already use. Assert the second exits with a non-zero status, its log names the directory and the fact that another process holds it, and the first process is unaffected.
2. Container: start a second controller container sharing the first controller's volume. Assert it exits, its readiness is never true, and the original continues to serve.

**Note.** Whether a file lock survives across containers depends on the volume driver. The local driver behaves like a normal filesystem, so this should hold, but the test documents it.

## Step 4: Compaction during partition, catch-up by snapshot (scenario 5)

**Current state.** Snapshot installation for a lagging follower is proven in-process, and the Docker suite has network partition helpers that disconnect and reconnect containers. The majority-partition test is disabled because the node isolation helper is a no-op. That helper needs to be fixed or the test rewritten to use the working `docker network disconnect` path.

**Tests to write.**

1. Fix or replace the isolation helper so a single node can be cut off and later restored, and re-enable the disabled test.
2. Partition one follower, submit enough registrations on the majority to cross the snapshot threshold so the leader compacts, restore the follower, and assert it converges. Assert through the leader's metrics or logs that an InstallSnapshot was actually sent, otherwise the test could pass via ordinary AppendEntries if compaction never happened.
3. Restart the caught-up follower and confirm it recovers from its installed snapshot plus suffix. This connects Step 1's restart fixture to snapshot installation.

## Step 5: Leader crash before response and idempotent retry (scenario 3)

**Current state (verified 2026-09-22).** This step was originally recorded as blocked on the platform design's catalog protocol work. Inspection of the code shows that most of the required behaviour already exists and the real gap is small:

- **Idempotency is not a blocker.** `QraftStateStore` applies `CatalogCommand.Register` as a put keyed by `serviceId` and always returns `Success`. A client that re-sends the identical registration after a lost response converges to one instance. The composite identity (tenant, namespace, node, serviceId) from the platform design is a correctness gap for cross-node collisions, but this scenario does not depend on it.
- **Unknown-outcome is already produced at the node level.** `RaftNode.failPendingCommands` fails pending client promises with explicit messages on stop ("Node stopped before command commitment; outcome may be unknown"), on leadership loss through a higher term, and on snapshot-driven step-down. That is the hard part and it is done.
- **The gap is HTTP error classification.** `HttpApiServer.respondUnavailable` maps every `CompletionException` from a submitted command to HTTP 503 with code `leader_unavailable`. That includes the node's unknown-outcome failures and the five-second `orTimeout` in the `submit` helper. A timed-out request is exactly the unknown-outcome case and is currently reported as a leader problem. The message text carries the distinction, but the standards require clients to branch on `code`, not message.
- **Request IDs** exist only on the gRPC Raft transport, not on the HTTP layer. They are useful for correlation but not required for this scenario.

**Prerequisite work (small, HTTP layer only).**

1. In `respondUnavailable`, classify the cause: a `TimeoutException`, or an `IllegalStateException` originating from `failPendingCommands`, returns a distinct code such as `outcome_unknown` with `retryable: true`. Only genuine leader absence returns `leader_unavailable`. Consider a typed exception from `RaftNode` for the pre-commit failure rather than matching message text.
2. Add a `retryable` boolean to the existing JSON error map. The full structured error envelope from the platform design section 13.2 can follow later; one field is enough for this test.
3. Cover the mapping with a real-HTTP test against a single-node Raft state machine, per the standards' prohibition on mocking.

Deferred and not required for this step: composite catalog identity, applied-index response header, HTTP request-ID propagation. These remain platform design Tranche 2 work.

**Tests to write.**

1. In-process: use the existing gated storage fixtures to hold a leader's append at the sync gate, stop the node while the client request is pending, and assert the HTTP client receives `outcome_unknown` with `retryable: true` rather than `leader_unavailable` or a definite rejection.
2. In-process: hold the gate past the five-second submit timeout and assert the same `outcome_unknown` code, proving the timeout path is classified correctly.
3. Container: submit a registration and kill the leader using a helper that issues the request and the kill concurrently. Retry the identical registration against the new leader and assert exactly one instance exists in the catalog on all nodes.

**Honest caveat.** The container test cannot deterministically hit the window between persistence and response. It proves convergence under retry rather than the exact interleaving. The in-process gated tests are the ones that prove the window and the error code.

**Revised ordering.** Because the blocker is a small HTTP change rather than a protocol redesign, this step can run immediately after Step 1 instead of last. It needs the Step 1 kill-and-restart fixture for the container test and nothing else.

## Step 6: Operational documentation

This is the second exit gate for Tranche 8 and has nothing written today. Write one operator-facing document covering:

- Backup: what files make up a node directory, how to copy them safely with the node stopped, and why copying a running node's WAL is not a valid backup.
- Corruption: what a fenced node looks like at the readiness endpoint and in logs, and the recovery procedure, which is wiping the node directory and letting it rejoin via snapshot installation.
- Fencing: the conditions that fence a node, that it stays live but unready, and that restart is the only recovery.
- Directory lock: the error an operator sees when two processes share a path.
- Migration: the legacy WAL and snapshot compatibility path already proven by fixture in Tranche 6.

Each procedure should be exercised by one of the Docker tests above, so the documentation describes verified behaviour rather than intended behaviour.

## Running order and gates

| Step | Depends on | Gate |
|---|---|---|
| 1 Restart | None | Docker suite green with volumes retained |
| 2 Corruption | Step 1 fixture | Fenced node visible at readiness and in logs |
| 3 Lock | Separate-JVM harness | Second process exits, first unaffected |
| 4 Partition and snapshot | Steps 1 and 4.1 | InstallSnapshot observed, follower converges and survives restart |
| 5 Leader crash and retry | Step 1 fixture; HTTP error classification change | `outcome_unknown` surfaced over HTTP, retry converges |
| 6 Documentation | Steps 1 to 5 | Every procedure backed by a passing test |

Steps 1 through 4 can be done now without touching the public API. Step 5 requires one small, additive HTTP change: a distinct `outcome_unknown` error code with a `retryable` field. It does not depend on the catalog protocol redesign in the platform design's Tranche 2, so all eight Tranche 8 scenarios can be closed within this task list. The recommended execution order is 1, 5, 2, 3, 4, 6.