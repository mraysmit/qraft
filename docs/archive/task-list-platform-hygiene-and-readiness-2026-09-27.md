# Task List: Platform Hygiene and Server Readiness

**Date:** 2026-09-27
**Active work:** None. All steps are complete. Archived 2026-09-28 with the user's agreement.
**Source plan:** [`QRAFT_FEATURE_VALIDATION_2026-09-27.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-09-27.md), section 5, items 1 and 2
**Predecessor:** [`archive/task-list-multi-node-container-acceptance-2026-09-26.md`](task-list-multi-node-container-acceptance-2026-09-26.md)
**Paused alongside:** [`task-list-embedded-admin-interface-2026-09-27.md`](../../docs-design/task-list-embedded-admin-interface-2026-09-27.md), paused after its Step 1
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

This is the current task list for the project. When the active work is complete,
add a completion summary, move this file to `docs/archive/`, and start a new dated task
list for the next backlog item.

**Completion summary (2026-09-27).**

- The job system's agent statuses and work-assignment concepts are gone from the
  active build. Replicated history that holds them still decodes to their
  current meaning on every replica.
- The Consul plan's checklist matches the code.
- A server reports ready only when it has recovered, is neither fenced nor
  draining, and knows a current leader. The response names every failed
  condition.
- A partitioned minority server is unready until the partition heals.
- One intermittent test failure was root-caused along the way; see Step 4.

## 1. Goal

Start closing the gaps the feature validation found, with the two items that
every later list builds on:

- no concept inherited from the job system remains in the active build, and the
  Consul plan's checklist matches the code;
- a server reports ready only when it can safely serve its advertised API.

## 2. Current state

Updated 2026-09-27.

- `AgentStatus` in `qraft-core` still has `getJobAssignmentPriority()`, which
  nothing calls, and three states documented as legacy aliases: `ACTIVE` and
  `IDLE` (healthy) and `OVERLOADED` (degraded). They reach several places:
  - the protobuf `AgentStatusProto`, as values 3, 4, and 6, so existing WAL
    entries and snapshots may contain them;
  - `AgentCodec`, which maps them in both directions;
  - `HttpApiServer.heartbeatStatus`, which maps them to `HEALTHY` and
    `DEGRADED`;
  - `AgentInfo`, which treats `ACTIVE` and `IDLE` as available.

  The `AgentSystemInfo` Javadoc describes "capacity planning and job
  assignment".
- Several unchecked items in the Consul plan's checklist are done, and one
  checked area is incomplete (feature validation, section 4).
- `/health/ready` on a server is 503 only when the Raft node is fenced. It
  reports ready during recovery, while draining, and while the node knows of no
  leader, for example as a partitioned candidate.

## 3. Rules

- Red before green, with deterministic tests (`PROJECT_STANDARDS.md`, section
  4.4). Mockito is prohibited.
- Replicated history must stay readable. A WAL entry or snapshot written by an
  earlier version decodes to the same state on every replica after this change.
- Every new Java file carries the license header and attributed type Javadoc.
- Update the design documents and this list before moving to the next step.

## 4. Decisions

### 4.1 Agreed 2026-09-27

1. **Legacy status strings in heartbeats.** After the states are removed, a
   heartbeat whose status is `active`, `idle`, or `overloaded` is rejected as
   invalid, like any unknown status. The Qraft agent never sends
   them, and there is no released client to keep compatible.

2. **Readiness conditions.** A server is ready when all of the following hold:
   - it has finished recovery and is running;
   - it is not fenced;
   - it is not draining;
   - it knows a current leader, meaning it is the leader or a follower of one.
     A candidate, or a follower that has lost its leader, is unready.

   Readiness says nothing about read consistency until consistency modes exist
   (feature validation item 4). The response body names every condition that
   failed.

## 5. Step 1: Remove the job-system remnants

1. Remove `getJobAssignmentPriority()` and the `ACTIVE`, `IDLE`, and
   `OVERLOADED` states from `AgentStatus`. Update `AgentInfo` availability and
   `HttpApiServer.heartbeatStatus`, following decision 1.
2. Keep protobuf values 3, 4, and 6 decodable. They decode to `HEALTHY`,
   `HEALTHY`, and `DEGRADED` on every replica, and they are never encoded
   again. Mark them deprecated in `commands.proto`.
3. Remove the job and capacity-planning wording from `AgentSystemInfo` and any
   other active source.

**Exit gate.** Tests, each shown failing first, prove:

- a WAL entry and a snapshot holding each legacy value decode to the mapped
  state;
- no legacy value is ever encoded;
- heartbeats behave as decision 1 settles.

A scan of `qraft-*/src/main` finds no job, transfer, workflow, or assignment
concept.

**Status: Done 2026-09-27.** `DRAINING` was removed too: its own documentation
called it a legacy alias, and heartbeats mapped it to `MAINTENANCE`.

- `AgentStatus`, now version 2.0, holds the node lifecycle only:
  `REGISTERING`, `HEALTHY`, `DEGRADED`, `MAINTENANCE`, `UNREACHABLE`, `FAILED`,
  and `DEREGISTERED`. The following are gone:
  - `getJobAssignmentPriority()`;
  - the `availableForWork` flag;
  - `AgentInfo.isAvailable()`, so `/api/v1/agents` no longer returns
    `"available"`.
- Replicated history stays readable:
  - `AgentCodec` decodes protobuf values 3, 4, 6, and 8 as `HEALTHY`,
    `HEALTHY`, `DEGRADED`, and `MAINTENANCE`, and never encodes them.
    `commands.proto` marks them deprecated.
  - Snapshot JSON is read through `AgentStatus.fromStoredValue`, which maps
    `active`, `idle`, `overloaded`, and `draining` the same way.
  - The existing legacy snapshot fixture, which stores `"status":"active"` and
    `"available":true`, now restores as `HEALTHY`.
- API input stays strict. `AgentStatus.fromValue` rejects the legacy words, so a
  heartbeat carrying one is a 400 and changes nothing.
- `AgentSystemInfo` no longer describes job assignment. The only remaining
  "transfer" wording is Raft's own chunked snapshot transfer.

Five tests failed on the behaviour first:

- `AgentCodecTest`: legacy values decode to their current meaning, and no
  current status encodes as a legacy value;
- `LegacyCatalogFixtureTest`;
- `AgentStatusTest`;
- `HttpApiServerTest`.

`AgentStatusTest` and `AgentStatusTransitionTest` were rewritten for the seven
statuses. The full default reactor passed 732 tests. The count fell from 819
because the transition test's cases per pair of statuses shrank from 121 to 49.

## 6. Step 2: Bring the Consul plan up to date

1. Check each item of the checklist in `CONSUL_FEATURE_IMPLEMENTATION_PLAN.md`
   against the feature validation, and mark it done, partial, or open.
2. Point the plan at the feature validation and the dated task lists as the
   current record of delivery.

**Exit gate.** Every checklist item agrees with the feature validation.

**Status: Done 2026-09-27.**

- The Consul plan names the feature validation and the dated task lists as the
  record of delivery.
- Its checklist now marks as done: agent membership and failure detection;
  health propagation; snapshot, recovery, and upgrade workflows; multi-node and
  failure-injection coverage; and removal of the job-system concepts.
- Server configuration is marked done, with membership change noted as not
  implemented.
- Every remaining gap is listed as an open item that references its feature
  validation item.

## 7. Step 3: Server readiness

1. `/health/ready` is 200 only when every condition in decision 2 holds.
   Otherwise it is 503 with a structured body that lists the failed conditions:
   `recovering`, `fenced`, `draining`, or `no_leader`.
2. `/health/live` is unchanged: the process and its HTTP listener are up.
3. Update the Docker healthchecks and any test that relies on the old readiness,
   so that liveness and readiness stay distinct.

**Exit gate.** Tests, each shown failing first, cover each condition:

- a recovering node;
- a fenced node;
- a draining node;
- a candidate without a leader;
- a leader;
- a follower of a known leader.

A cluster test shows a partitioned minority server becoming unready and ready
again when the partition heals.

**Status: Done 2026-09-27.**

- `/health/ready` reads one consistent `RaftStatus` on the state loop. The
  record gains `running` and `knowsLeader()`.
- It returns 503 `not_ready`, with a `conditions` list, when the server is
  `draining`, `unavailable`, `fenced` (or else `recovering`), or has
  `no_leader`. It returns 200 otherwise.
- A server without a Raft node is judged on draining alone.
- `/health/live` is unchanged. The Docker healthchecks already probe `/health`,
  not readiness, so they needed no change.

`HttpApiServerReadinessTest` covers:

- a leader;
- a follower of a known leader;
- a node that knows no leader;
- a node that has not recovered;
- a draining server;
- a follower that loses its leader behind an in-memory partition and is ready
  again when the partition heals.

Four of its tests failed first, it passed three consecutive runs, and the
existing fenced tests still pass. The design document, section 6.1, records
the contract.

## 8. Step 4: Verification and close-out

1. Run the full default reactor and the Docker-tagged suite, the Mockito,
   environment-variable, and `orTimeout` scans, the header audit, and
   `git diff --check`.
2. Update the feature validation to record items 1 and 2 as done.

**Status: Done 2026-09-27.**

- **Default reactor.** Two consecutive runs passed 738 tests.
- **Docker-tagged suite.** 30 tests passed against a runtime JAR built with the
  readiness change.
- **Scans.** The header audit, the Mockito, environment-variable, and
  `orTimeout` scans, and the whitespace check were clean.
- **Intermittent failure.** The first full run failed once, in
  `JavaRuntimeTest.executeBlockingUsesVirtualThreadAndCompletesOnRuntimeContext`.
  - A probe of 2,000 repetitions reproduced it twice: the callback ran on the
    test's main thread.
  - The cause: a thread blocked in `get()` on a `CompletableFuture` helps
    complete it, and so it can run the future's pending callbacks. The test
    blocked on the operation before its callback had run.
  - The test now waits on the callback's result. With that change the probe
    showed 0 failures in 6,000 runs.
  - Production code is not exposed. Every continuation that must run on a
    state loop checks where it runs and dispatches there, and the server's
    blocking waits are on private copies from `Deadlines.bound`.
  - The rule is now in the `Future` and `JavaRuntime.executeBlocking` Javadoc,
    and in `PROJECT_STANDARDS.md`, section 4.4.

## 9. Out of scope

- Every other item in the feature validation, section 5. Each has its own later
  list.
- Readiness that depends on read consistency modes.
