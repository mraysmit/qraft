# Task List: Multi-Node Container Acceptance

**Date:** 2026-09-26
**Completed:** 2026-09-27
**Source plan:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), Tranche 7
**Predecessor:** [`task-list-health-propagation-2026-09-25.md`](task-list-health-propagation-2026-09-25.md)
**Successor:** [`task-list-embedded-admin-interface-2026-09-27.md`](../task-list-embedded-admin-interface-2026-09-27.md)
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

This task list is complete and retained as the implementation record for
multi-node container acceptance.

**Completion summary (2026-09-27).** Tranche 7 is complete:

- In containers built from one image, a three-server cluster and a client-mode
  agent survive, and converge after, each of the following:
  - a whole-cluster crash that outlasts the check TTL;
  - a killed follower that must install the leader's snapshot;
  - a crashed agent;
  - a gracefully restarted agent;
  - an agent partitioned from every server.
- Each Docker test was shown to fail without the behaviour it verifies, and
  passed three consecutive runs.
- The work found and fixed three Raft defects: followers never compacted their
  logs; a follower refused appends that overlap its snapshot; and a follower
  reported and committed a stale uncommitted tail, which is a safety violation.

The embedded administrative interface remains the one open acceptance
criterion.

## 1. Goal

Prove in containers built from one image that a three-server cluster and a
client-mode agent recover durably and converge after restarts, crashes, and
partitions:

- Committed catalog and health state survive server crashes through the WAL and
  snapshots.
- A restarted cluster gives its agents time to renew before it expires anything.
- An agent that restarts, crashes, or is cut off rejoins with no duplicate node
  or service and no lost health state.

Mapping to the design: Steps 1 and 2 close Tranche 7 item 4, restarting servers
and verifying durable recovery. Steps 3 and 4 extend item 3, client recovery, to
crashes and partitions of the agent.

## 2. Current state

Updated 2026-09-27.

- Tranche 7 items 1 to 3 are done. `DockerAgentHealthTest` starts three server
  containers and one client-mode agent container from `qraft-runtime:test`. The
  agent's configuration, `docker/config/agent-acceptance/agent.json`, declares
  HTTP and TCP checks against itself.
  - Every server discovers the service as passing.
  - Publication survives the loss of the leader container.
  - `docker stop` of the agent deregisters its service.
  - `docker kill` of the agent leads to expiry and then deregistration.
- `DockerDurableRestartTest` covers durable server recovery without an agent:
  - graceful whole-cluster restart;
  - a snapshot plus the WAL suffix after it;
  - a killed follower and a killed leader;
  - a corrupt follower;
  - volume ownership.
- The acceptance server configurations snapshot every 5 entries. Agent heartbeats
  and health renewals are Raft commands, so a running agent quickly drives
  snapshot publication and log compaction. Since Step 1, every server compacts its
  own log. A follower installs the leader's snapshot only when it has fallen
  behind the leader's compaction point.
- The acceptance server configurations use the health defaults: expiry evaluation
  every second, a node TTL of 90 seconds, and node reaping after 72 hours.
- The agent acceptance configuration is the expiry profile:
  - Both checks have a 5-second TTL.
  - Only the HTTP check sets `deregisterAfterMs`: it removes the service 5
    seconds after that check expires.
  - Controller contact freshness is 30 seconds.
- Observation sequence numbers are seeded from the wall clock:
  `max(previous + 1, clock millis)`. A restarted agent is therefore already
  ahead of the sequences the servers hold. When a clock has stepped back, a
  stale answer raises the floor to the server's sequence. `HealthPublisherTest`
  covers this; it cannot be forced in a container.
- `SharedDockerCluster.startIsolatedThreeNodeClusterWithAgent()` starts a
  disposable cluster with the agent. `isolateContainerNetwork` disconnects a
  container and then restarts it, so it cannot model a partition of a running
  agent.
- The repository has no CI configuration. The Docker-tagged suite runs locally
  with `-Dtest.excludedGroups= -Dgroups=docker` against a runtime JAR built on
  the host.
- Cost: each scenario starts its own four-container cluster, which takes about
  40 seconds. Steps 1 to 4 add about five, taking the Docker-tagged suite from
  about 5 to about 9 minutes.

## 3. Rules

- Configuration comes only from mounted, versioned JSON files. No Qraft setting
  comes from an environment variable.
- Red before green. A step that changes behaviour starts with a failing test. A
  step that verifies existing behaviour shows its test can fail, by disabling
  the mechanism under test, before the test counts.
- Mockito and substitute mocking frameworks are prohibited.
- No flaky tests (`PROJECT_STANDARDS.md`, section 4.4):
  - Waits are on observable state, with generous bounds.
  - Wall-clock sleeps are never used for synchronization.
  - An intermittent failure is a defect to root-cause in both the test and
    production code.
  - Every new or changed Docker test passes at least three consecutive runs
    before its step is done.
  - Timing margins are several seconds, never about one. A scenario whose
    margin depends on container or JVM start time uses a profile that makes the
    margin generous.
- Only committed Raft commands mutate catalog or health state. Followers never
  expire state from local timers.
- Every new Java file carries the license header and attributed type Javadoc
  (`PROJECT_STANDARDS.md`, section 10.1).
- Update the design document and this list before moving to the next step.

## 4. Step 1: Whole-cluster crash and restart with a running agent

1. Add a restart profile, `docker/config/agent-acceptance/agent-restart.json`, and
   a compose file that mounts it. The profile has:
   - a 15-second check TTL;
   - a 30-second deregistration delay on the HTTP check;
   - 5-second controller contact freshness.

   Restart scenarios need margins of several seconds for container and JVM
   start; the expiry profile's 5-second TTL leaves about one. The expiry profile
   stays as it is for expiry scenarios.
2. Before the crash, every server has published a snapshot
   (`snapshotLastIndex > 0` in `/raft/status`). Recovery therefore exercises the
   snapshot as well as log replay.
3. Kill all three server containers (`docker kill`) while the agent keeps
   running. Keep them down for longer than the check TTL, then start them.
4. While every server is down, the agent stays live and becomes unready:
   `/health/live` returns 200 and `/health/ready` returns 503.
5. After the restart, every server recovers the node, the service, its declared
   checks, and its health-check state, with no duplicate instance.
6. The restarted cluster's leader grants a full TTL before expiring anything.
   Checks whose stored deadlines passed during the outage are renewed, not
   expired: no server reports them expired after the restart.
7. The agent becomes ready again, and renewals resume with higher sequence
   numbers.

**Exit gate.** The Docker test passes three consecutive runs and fails when the
new leader's grace is removed.

**Status: Done 2026-09-27.**
`DockerAgentRecoveryTest.aWholeClusterCrashOutlastingTheCheckTtlRecoversFromDiskAndRenewsInsteadOfExpiring`
uses the restart profile (`agent-restart.json` and
`docker-compose-3node-agent-restart-prebuilt.yml`):

- It kills all three server containers once every server has published a
  snapshot.
- While every server is down, the agent stays live and becomes unready.
- The agent is then frozen with `docker pause`, a new `SharedDockerCluster`
  lifecycle action, so everything the restarted servers hold can only come from
  their own WAL and snapshots.
- Every server recovers the node with its original registration time, one
  instance, and both checks at or above their pre-crash sequence numbers.
- No server expires the checks, although their deadlines passed during the
  17-second outage.
- After the agent is unfrozen, it becomes ready and renewals resume.

The test failed with the new leader's grace removed from `HealthExpiryEvaluator`,
then passed three consecutive runs. `DockerHealthApi` holds the public-API reads
the Docker agent tests share.

The test's precondition exposed production defects, fixed test first in
`RaftNode` (`RaftNodeFollowerLogTest`):

- **Only the leader ever took snapshots.** Followers never compacted, so their
  in-memory log and WAL grew without bound. Agent heartbeats alone add entries
  every second, so a follower would eventually run out of memory. A follower
  that was elected with an oversized log refused every write at the log hard
  limit until its first snapshot, and a restarted follower replayed its whole
  WAL. Every server now compacts its own log on the snapshot schedule, in any
  role. Step-downs cancel only the role timers.
- **A follower rejected an append whose previous entry lay inside its snapshot.**
  After it compacted, a retransmission would be refused indefinitely. A
  previous entry inside the snapshot is committed and identical on the leader, so
  it is now accepted. RaftLog's `AppendPlan` skips the entries the snapshot
  covers.
- **A follower reported and committed its whole log, including a stale tail.**
  A successful response carried `lastLogIndex()` as the match index, and the
  follower committed up to `min(leaderCommit, lastLogIndex)`. An uncommitted tail
  from an earlier term, not yet truncated by a conflict, therefore let a new
  leader count the follower for entries it did not hold. That leader could
  commit an entry without a real majority, a Raft safety violation. The follower
  now reports and commits only through the last entry the request verified, or
  through its snapshot boundary.

Two existing tests encoded the old behaviour, and both are restated:

- `RaftNodeTimerSequencingTest` expected a node that stepped down never to
  publish a snapshot. It now checks that a snapshot queued behind the step-down
  waits for it and then covers exactly the applied entries.
- `RaftNodeInstalledSnapshotSequencingTest` expected a heartbeat inside the
  installed snapshot to be rejected. It now checks that the heartbeat sees the
  installed boundary as its match index.

The full default reactor passed 807 tests. The design document, sections 14.3
and 14.4, records the append and compaction contracts.

## 5. Step 2: Killed follower catches up on health state

1. Add `lastLogIndex` to `/raft/status`. Record a follower's last log index,
   then kill it while the agent keeps publishing.
2. Before restarting it, wait until the leader's `snapshotLastIndex` is above the
   killed follower's recorded last log index. The follower recovers its own
   snapshot and WAL, but it cannot catch up from the leader's log alone and must
   install the leader's snapshot.
3. Restart the follower. It installs the leader's snapshot, replays the suffix,
   and then holds health-check state identical to the leader's: the same
   sequence numbers, deadlines, expiry flags, and declared checks.
4. Its `/v1/health/service/web` and `?passing` answers agree with the leader's.

**Exit gate.** The Docker test passes three consecutive runs and fails when
snapshot installation omits health state.

**Status: Done 2026-09-27.**
`DockerAgentRecoveryTest.aKilledFollowerInstallsTheLeadersSnapshotAndThenHoldsTheLeadersHealthState`
proves the follower's health state can only have come from the installed
snapshot:

- It records a follower's last log index, kills the follower, and freezes the
  agent, so no later observation can rebuild the checks through the log.
- It writes filler registrations in a separate tenant until the leader's snapshot
  covers every health entry and passes the follower's last log index.
- After its restart, the follower's snapshot index passes its old last log
  index, which it can reach only by installing the leader's snapshot.
- The follower's `/v1/health/service/web` and `?passing` answers then equal the
  leader's.
- The unfrozen agent then passes again on every server.

With `QraftStateStore` restoring snapshots without health checks, the test fails
on that convergence condition. It then passed three consecutive runs, together
with Step 1's test and `DockerAgentHealthTest`.

Supporting changes:

- `/raft/status` now reports `lastLogIndex` and `lastApplied`. It reads one
  consistent `RaftStatus` on the node's state loop through `RaftNode.status()`.
  Fields read one at a time from the HTTP thread could mix two moments, for
  example during snapshot trimming (`HttpApiServerTest`).
- The Docker agent test classes declare a 10-minute method budget. The module's
  90-second default timeout would otherwise interrupt a test that starts its own
  cluster before its bounded waits can report which condition failed.

The full default reactor passed 807 tests.

## 6. Step 3: Agent container crash and restart

These scenarios use the restart profile from Step 1.

1. Kill the agent container and start it again. The new process registers the
   same node and service identities again. No server ever shows the instance
   removed or duplicated, and every server ends with exactly one node and one
   passing instance.
2. Sequence numbers continue above the pre-crash values, because they are seeded
   from the wall clock.
3. A graceful `docker restart` deregisters the service, registers it again, and
   ends with it passing on every server.

**Exit gate.** The Docker tests pass three consecutive runs, and each is shown to
fail without the behaviour it verifies:

- Item 1 fails when the agent registers under an identity unique to each
  process.
- Item 2 fails only when both clock seeding and stale-floor recovery are
  disabled, because either one alone keeps sequences increasing.
- Item 3 fails when shutdown skips deregistration.

**Status: Done 2026-09-27.** Two tests in `DockerAgentRecoveryTest` cover this
step:

- `aCrashedAgentRestartsUnderTheSameIdentityWithoutItsInstanceEverBeingRemovedOrDuplicated`
  kills and restarts the agent container. It records the committed `http` check
  only after the kill, once every server agrees, so no observation from the
  killed process can still be in flight. No server ever shows the instance
  removed or duplicated. Every server ends with one node and one passing
  instance, with a sequence number above the recorded one. The test pins no node
  ID, so a restart under a new identity shows up as a duplicate rather than as a
  failure to start.
- `aGracefullyStoppedAgentDeregistersItsServiceAndRegistersItAgainWhenItStarts`
  finds the service gone from every server once `docker stop` returns, and
  passing again after `docker start`.

Exit-gate results:

- **Identity.** With an agent ID unique to each process, the crash test fails
  after the restart.
- **Sequencing.** Three safeguards overlap here: clock seeding, the stale floor,
  and a resend at the next sequence on each stale answer. Disabling clock seeding
  and the floor together is not enough, because the resend catches up with the
  small sequences an unseeded process reaches. The gate instead seeds sequences
  from process uptime, which models a clock that steps back across a restart:
  - With the stale floor intact, the crash test still passes. This shows in a
    container that the floor rescues a restarted agent whose sequences start
    below the servers'.
  - With stale answers dropped, it fails: the checks expire and the service is
    removed before the agent recovers.
- **Deregistration.** With service deregistration skipped on shutdown, the
  graceful test fails.

The recovery class then passed three consecutive runs. The first version of the
crash test was rejected by its own gate. It pinned the node ID, so the identity
mutation failed before the crash. It also read the pre-crash sequence before the
kill, so a renewal still in flight from the killed process could stand in for
one from the new process.

## 7. Step 4: Agent partitioned from every server

1. Add a Docker helper that disconnects a running container from its networks
   without restarting it, and reconnects it with the same aliases.
2. Lower the expiry profile's controller contact freshness to 5 seconds, so the
   partition need not outlast 30 seconds before the agent becomes unready.
3. Partition the agent. It stays live and becomes unready. The leader expires its
   checks after their TTL and deregisters its service after the HTTP check's
   `deregisterAfterMs`.
4. Heal the partition. The agent's periodic reconciliation registers the service
   again, and publication resumes. Every server shows exactly one passing
   instance.

**Exit gate.** The Docker test passes three consecutive runs and fails when
periodic reconciliation is disabled.

**Status: Done 2026-09-27.**
`DockerAgentHealthTest.anAgentCutOffFromEveryServerIsExpiredAndDeregisteredThenRejoinsWhenThePartitionHeals`
uses `SharedDockerCluster.partitionContainer` to disconnect the running agent
from its network without restarting it. `isolateContainerNetwork` now uses the
same helper and then restarts the container.

- A partitioned container also loses its published ports, so the test reads the
  agent's own `/health/live` and `/health/ready` inside the container through
  `execInService`.
- While partitioned, the agent stays live and becomes unready. Every server shows
  its checks expired and its service critical, and then deregisters the service.
- After `restoreContainerNetwork`, the agent's periodic reconciliation registers
  the service again. Every server then shows exactly one passing instance, and
  the agent is ready.

The expiry profile's controller contact freshness is now 5 seconds. With periodic
reconciliation disabled in `QraftAgent`, the test fails waiting for the service
to return after the partition heals. `DockerAgentHealthTest` then passed three
consecutive runs.

## 8. Step 5: Verification and close-out

1. Run the full default reactor and the full Docker-tagged suite, each new
   Docker test passing at least three consecutive runs.
2. Run the Mockito, environment-variable, and `orTimeout` scans, the header
   audit, and `git diff --check`.
3. Mark Tranche 7 item 4 complete in the design document, and record Steps 3 and
   4 under item 3. Record evidence against the initial acceptance criteria in
   section 21. The embedded administrative interface criterion stays open,
   because that work is out of scope here.

**Status: Done 2026-09-27.**

- **Default reactor.** 807 tests passed.
- **Docker-tagged suite.** 30 tests passed against a freshly built runtime JAR:
  - `ConfigurableRaftClusterTest`, 8 tests;
  - `DockerAgentHealthTest`, 3 tests;
  - `DockerAgentRecoveryTest`, 4 tests;
  - `DockerDurableRestartTest`, 8 tests;
  - `DockerRaftClusterTest`, 4 tests;
  - `NetworkPartitionTest`, 3 tests.
- **Scans.** The header audit, the Mockito scan, the production
  environment-variable, `orTimeout`, and `completeOnTimeout` scans, the
  whitespace check, and a check for leftover mutation markers were all clean.
  No containers were left running.
- **Design document.** Tranche 7 is marked complete, and section 21 records the
  evidence against each initial acceptance criterion.

## 9. Out of scope

- Packaging the embedded administrative interface and its packaged-artifact
  acceptance test (design sections 12.4 and 19.6). This is separate work that has
  not started.
- CI pipeline configuration.
- Five-node container acceptance with agents.
- Server-side write forwarding and leader-aware client redirection.
- ACLs, certificate-derived identity, and TLS between containers.

The next active work is the embedded administrative interface, tracked in
[`task-list-embedded-admin-interface-2026-09-27.md`](../task-list-embedded-admin-interface-2026-09-27.md).
