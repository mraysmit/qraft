# Task List: Multi-Node Container Acceptance

**Date:** 2026-09-26
**Active work:** Step 1, whole-cluster restart with a running agent
**Source plan:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), Tranche 7
**Predecessor:** [`archive/task-list-health-propagation-2026-09-25.md`](archive/task-list-health-propagation-2026-09-25.md)
**Standards:** [`PROJECT_STANDARDS.md`](PROJECT_STANDARDS.md)

This is the current task list for the project. When the active work is complete,
add a completion summary, move this file to `archive/`, and start a new dated task
list for the next backlog item.

## 1. Goal

Prove in containers built from one image that a three-server cluster and a
client-mode agent recover durably and converge after restarts, crashes, and
partitions:

- Committed catalog and health state survive server restarts through the WAL and
  snapshots.
- A restarted cluster gives its agents time to renew before it expires anything.
- An agent that restarts, crashes, or is cut off rejoins with no duplicate node
  or service and no lost health state.

## 2. Current state

Updated 2026-09-26.

- Tranche 7 items 1 to 3 are done. `DockerAgentHealthTest` starts three server
  containers and one client-mode agent container from `qraft-runtime:test`. The
  agent's configuration, `docker/config/agent-acceptance/agent.json`, declares
  HTTP and TCP checks against itself.
  - Every server discovers the service as passing.
  - Publication survives the loss of the leader container.
  - `docker stop` of the agent deregisters its service.
  - `docker kill` of the agent leads to expiry and then deregistration.
- `DockerDurableRestartTest` covers durable server recovery without an agent:
  - whole-cluster restart;
  - a snapshot plus the WAL suffix after it;
  - a killed follower and a killed leader;
  - a corrupt follower;
  - volume ownership.
- The acceptance server configurations snapshot every 5 entries. Agent
  heartbeats and health renewals are Raft commands, so an agent quickly drives
  snapshot publication and installation.
- The acceptance server configurations use the health defaults: expiry
  evaluation every second, a node TTL of 90 seconds, and node reaping after 72
  hours. The agent acceptance configuration uses a check TTL and deregistration
  delay of 5 seconds each, and a controller contact freshness of 30 seconds.
- `SharedDockerCluster.startIsolatedThreeNodeClusterWithAgent()` starts a
  disposable cluster with the agent. `isolateContainerNetwork` disconnects a
  container and then restarts it, so it cannot model a partition of a running
  agent.
- The repository has no CI configuration. The Docker-tagged suite runs locally
  with `-Dtest.excludedGroups= -Dgroups=docker` against a runtime JAR built on
  the host.

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
- Only committed Raft commands mutate catalog or health state. Followers never
  expire state from local timers.
- Every new Java file carries the license header and attributed type Javadoc
  (`PROJECT_STANDARDS.md`, section 10.1).
- Update the design document and this list before moving to the next step.

## 4. Step 1: Whole-cluster restart with a running agent

1. Keep the agent container running while all three server containers are
   stopped for longer than the check TTL.
2. While every server is down, the agent stays live and becomes unready:
   `/health/live` returns 200 and `/health/ready` returns 503. This needs a
   controller contact freshness shorter than the outage in the agent acceptance
   configuration.
3. After the restart, every server recovers the node, the service, its declared
   checks, and its health-check state from the WAL and snapshots, with no
   duplicate instance.
4. The restarted cluster's leader grants a full TTL before expiring anything.
   Checks whose stored deadlines passed during the outage are renewed, not
   expired: no server reports them expired after the restart.
5. The agent becomes ready again, and renewals resume with higher sequence
   numbers.

**Exit gate.** The Docker test passes three consecutive runs and fails when the
new leader's grace is removed.

## 5. Step 2: Killed follower catches up on health state

1. Kill a follower container while the agent keeps publishing, long enough for
   the leader to compact its log past the follower's position.
2. Restart the follower. It installs the leader's snapshot, replays the suffix,
   and then holds health-check state identical to the leader's: the same
   sequence numbers, deadlines, expiry flags, and declared checks.
3. Its `/v1/health/service/web` and `?passing` answers agree with the leader's.

**Exit gate.** The Docker test passes three consecutive runs and fails when
snapshot installation omits health state.

## 6. Step 3: Agent container crash and restart

1. Kill the agent container and start it again before its checks expire. The new
   process re-registers the same node and service identities, and every server
   keeps exactly one node and one instance.
2. The new process's first observations are reconciled with the sequence numbers
   the servers already hold, so the checks return to passing rather than being
   rejected as stale. Sequence numbers continue above the pre-crash values.
3. A graceful `docker restart` deregisters the service, registers it again, and
   ends with it passing on every server.

**Exit gate.** The Docker tests pass three consecutive runs. Each is shown to
fail without the mechanism it verifies.

## 7. Step 4: Agent partitioned from every server

1. Add a Docker helper that disconnects a running container from its networks
   without restarting it, and reconnects it with the same aliases.
2. Partition the agent. It stays live and becomes unready. The leader expires its
   checks after their TTL and deregisters its service after `deregisterAfterMs`.
3. Heal the partition. The agent reconciles, registers the service again, and
   republishes. Every server shows exactly one passing instance.

**Exit gate.** The Docker test passes three consecutive runs.

## 8. Step 5: Verification and close-out

1. Run the full default reactor and the full Docker-tagged suite, each new
   Docker test passing at least three consecutive runs.
2. Run the Mockito, environment-variable, and `orTimeout` scans, the header
   audit, and `git diff --check`.
3. Mark Tranche 7 item 4 complete in the design document. Record evidence
   against the initial acceptance criteria in section 21.

## 9. Out of scope

- Packaging the embedded administrative interface and its packaged-artifact
  acceptance test (design sections 12.4 and 19.6). This is separate work that has
  not started.
- CI pipeline configuration.
- Five-node container acceptance with agents.
- Server-side write forwarding and leader-aware client redirection.
- ACLs, certificate-derived identity, and TLS between containers.
