# Task List: Health Propagation and Automatic Deregistration

**Date:** 2026-09-25
**Completed:** 2026-09-26
**Source plan:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), Tranche 6
**Predecessor:** [`task-list-unified-runtime-flow-2026-09-24.md`](task-list-unified-runtime-flow-2026-09-24.md)
**Successor:** [`task-list-multi-node-container-acceptance-2026-09-26.md`](task-list-multi-node-container-acceptance-2026-09-26.md)
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

This task list is complete and retained as the implementation record for health
propagation and automatic deregistration.

**Completion summary (2026-09-26).** Service health is authoritative, replicated,
and self-cleaning:

- Agents run configured TTL, HTTP, and TCP checks near their workloads and publish
  ordered observations and renewals.
- The state machine rejects stale observations and derives service health
  deterministically.
- Only the leader proposes expiry. A check that is not renewed expires, and when
  it declares a deregistration delay, its service is then deregistered.
- A node that stops heartbeating is marked unreachable. When
  `server.health.nodeReapAfterMs` is set, the node and its services are reaped.
- A replica-determinism audit and a concurrency audit closed the defects they
  found, each with a regression test.
- End-to-end and Docker-tagged verification passed.

The limits known at the end of Step 6 are closed in its follow-up section.

## 1. Goal

Make service health authoritative, replicated, and self-cleaning. Agents execute
configured TTL, HTTP, and TCP checks near workloads and publish ordered
observations. The Raft state machine rejects stale observations, derives service
health deterministically, and removes registrations only through explicit
leader-proposed expiry commands.

## 2. Current state

Updated 2026-09-26, after Step 6.

- Registration stores a server-owned health value, but registration requests
  cannot set authoritative health.
- The replicated state machine accepts ordered per-check observations, derives
  aggregate service health, and applies explicit expiry commands (Step 1).
- Agents and other clients can submit observations through
  `PUT /v1/agent/check/observe`. `GET /v1/health/service/{serviceName}` returns
  instances with their checks and supports a `passing` filter (Step 2).
- The agent parses `catalog.services[].checks`, runs HTTP, TCP, and TTL checks
  locally (Step 3), publishes their observations and renewals, and withdraws
  readiness while a required check is critical or has not yet reported (Step 4).
- The leader expires unrenewed checks and, when a check declares a
  deregistration delay, deregisters the services of crashed or disconnected
  agents (Step 5). Registrations declare their checks, so a check removed from an
  agent's configuration is pruned rather than left to expire.
- End-to-end tests drive all of this through servers and agents started from
  configuration files and the public HTTP API. They include a killed client and a
  replaced leader, and the Docker-tagged suite passes (Step 6).

## 3. Rules

- Qraft does not use environment variables for configuration or configuration
  discovery. Health-check definitions and timing policy belong in the versioned
  client or server JSON file.
- Red before green for every behavioural change.
- Mockito and substitute mocking frameworks are prohibited. Use real protocol
  fixtures, real state machines, and small purpose-built fakes at time or
  transport boundaries.
- Inject time and scheduling. Expiry and ordering tests must not depend on wall
  clock sleeps.
- Only committed Raft commands mutate authoritative health or remove catalog
  registrations. Followers never expire state from local timers.
- Preserve composite identity: tenant, namespace, node, service, and check ID.

## 4. Step 1: Replicated health model and commands

**Status: Done 2026-09-25.** Added composite `ServiceCheckId`, ordered
`HealthObservation`, and replicated `HealthCheckState` with millisecond-precision
observed/accepted times and server-derived deadlines. Catalog observe and expire
commands use protobuf enum values 3 and 4 without renumbering existing variants.
The state machine accepts only a strictly newer per-check sequence, treats stale
or duplicate observations idempotently, and deterministically aggregates
`UNKNOWN`, `PASSING`, `WARNING`, `CRITICAL`, and `MAINTENANCE`. Expiry compares
the exact check identity, sequence, and deadline, so an old command cannot expire
or deregister a newer renewal. Health state is included in snapshots in stable
order; legacy binary command fixtures and snapshots remain readable with an
empty health-check set. The 15 final focused tests passed, and the complete
controller dependency reactor passed 572 tests with no failures, errors, or
skips.

**Red tests.**

1. A health observation identifies tenant, namespace, node, service, and check;
   carries status, sequence, observed time, TTL, and optional output.
2. Applying a newer sequence updates only the addressed check; an equal or older
   sequence is an idempotent no-op.
3. Aggregate service health is derived deterministically from required checks and
   supports `UNKNOWN`, `PASSING`, `WARNING`, `CRITICAL`, and `MAINTENANCE`.
4. An explicit expiry command changes or removes exactly the identities and
   deadlines encoded in that command; replay produces the same result.
5. Command and snapshot codecs round-trip the new state and load legacy fixtures
   with safe defaults.

**Implementation.** Add immutable health observation/check state and explicit
observe/expire command variants to the distributed-state protocol. Extend the
materialized store, protobuf codec, and snapshots without renumbering existing
fields.

**Exit gate.** Health ordering, aggregation, expiry, replay, and legacy loading
are deterministic under injected timestamps.

## 5. Step 2: Controller health API

**Status: Done 2026-09-26.** Added `PUT /v1/agent/check/observe`, which takes
the composite identity from the registration headers and validates status,
sequence, observed time, TTL, and output length (at most 4096 characters). The
receiving server stamps its injected-clock receipt time into the observe command,
so the deadline never depends on the agent clock. Success is returned only after
commit and apply, with `X-Qraft-Index`. An exact replay returns the original 200
body and deadline; an older sequence, or the same sequence with different
content, returns 409 `stale_observation` with `currentSequenceNumber`; an
unregistered composite instance returns 404 `service_not_found`.
`GET /v1/health/service/{serviceName}` now returns each instance with its
replicated checks, and `?passing` narrows the result to `PASSING` instances; any
other query parameter or value returns `invalid_query`. Catalog discovery is
unchanged and neither read moves the applied index. The contract is documented in
the design document, section 12.1.1. The six new real-HTTP tests passed, and the
full default reactor passed 664 tests on JDK 27 with no failures, errors, or skips.

1. Add request validation and error-envelope tests for health observations.
2. Expose an agent-facing observation/renewal endpoint with the existing
   request-context identity rules and commit-index response contract.
3. Make `GET /v1/health/service/{serviceName}` return only the states defined by
   its public contract while ordinary catalog discovery remains non-mutating.
4. Reject stale sequences without turning a successful idempotent replay into a
   transport failure.

**Exit gate.** Real HTTP tests prove serialization, validation, commit/apply,
stale-order handling, and health-filtered discovery.

## 6. Step 3: File configuration and local check execution

**Status: Done 2026-09-26, with one exit-gate exception noted below.** Each
`catalog.services` entry accepts an optional `checks` array of `http`, `tcp`, and
`ttl` definitions. Parsing validates identity, type-specific fields, and timing
(timeout at most the interval, TTL greater than the interval), rejects unknown
settings, and applies documented defaults. `AgentConfiguration` exposes the result
as `getHealthChecks()`; `ServiceDefinition` and its registration fingerprint are
unchanged. The new `dev.mars.qraft.agent.health` package runs each check
independently through an injected `CheckScheduler` and `Clock`:

- HTTP uses the JDK `HttpClient`: 2xx is passing, 429 warning, and anything else
  critical.
- TCP connects through a `TcpConnector` transport boundary backed by a
  virtual-thread socket.
- A scheduled timeout cancels the in-flight probe, and the next attempt is
  scheduled only after completion, so a check never overlaps itself.
- Stopping is terminal: it cancels scheduled and in-flight work, and no late
  result is delivered.
- Output is limited to 4096 characters.

Process-local status is the `LocalStatusReporter` input for TTL checks. A report
produces a local result only, and a missed renewal produces one local critical
result until the next report. Real HTTP servers and sockets cover the protocol
paths; a manual clock and scheduler drive every timing assertion. The 21 new
agent tests passed three consecutive runs, and the full default reactor passed
685 tests on JDK 27 with no failures, errors, or skips.

Exit-gate exception: TCP has no warning outcome. A connection either succeeds or
fails, so no TCP warning test exists. Adding one would need a new policy, such as
a slow-connect threshold. The checks are not yet started by `QraftAgent`; Step 4
wires them to publication and lifecycle.

1. Extend `catalog.services` with validated optional health-check definitions;
   no separate file or environment-variable source is introduced.
2. Implement TTL, HTTP, and TCP check runners with injected clocks, schedulers,
   and protocol clients.
3. Bound timeouts and diagnostic output; a slow or failed check must not overlap
   itself or block unrelated services.
4. Define process-local status as an internal input boundary without allowing it
   to mutate server state directly.

**Exit gate.** Deterministic tests cover passing, warning, failure, timeout,
recovery, cancellation, and non-overlap for each check type.

## 7. Step 4: Agent publication and renewal

**Status: Done 2026-09-26.** `HttpCatalogClient` implements the new
`ObservationClient` port. It sends `PUT /v1/agent/check/observe` with the
existing identity headers and seed rotation, and classifies the answer as
accepted, stale (with the server's current sequence), retryable, or rejected.
Accepted and stale answers count as controller contact.

`HealthPublisher` handles sequencing and delivery:

- It keeps one publication in flight per check and coalesces later results.
- It publishes changes immediately and unchanged results as renewals at half
  the TTL.
- Sequences are strictly increasing, clock-seeded, and raised past a stale
  answer before republishing.
- Retries resend the identical observation after the capped registration
  backoff, including while registration is still converging
  (`service_not_found`).

`QraftAgent` starts the checks of enabled services after node registration and
exposes `statusReporter(serviceId, checkId)` for process-local TTL status. On
shutdown it stops the checks and the publisher and waits for in-flight
publications before service deregistration begins. Readiness follows the
user-selected required-check policy: unready while any required check is
critical or has no result; warning and maintenance stay ready. The policy is
documented in the design document, section 4.3.

The exit-gate contract test (`AgentHealthPublicationTest`) runs a real agent
against a real controller. It covers:

- publication through a refused first seed;
- renewal with an unchanged status;
- a required HTTP check failing and recovering, with readiness following it;
- process-local TTL status;
- recovery after a controller restart;
- a recording proxy showing that every observation precedes deregistration and
  no request follows shutdown.

The 15 new agent tests and 2 contract tests passed three consecutive runs, and
the full default reactor passed 702 tests on JDK 27 with no failures, errors, or
skips.

1. Publish changed observations and TTL renewals through the shared classified
   controller client and seed rotation.
2. Maintain a monotonic per-check sequence across retry attempts in one process;
   retries of the same observation retain idempotent identity.
3. Keep liveness active and withdraw readiness according to the documented
   required-check policy during sustained failures or controller outages.
4. Stop new checks and publications before graceful deregistration begins.

**Exit gate.** A real-agent/controller contract proves observation, renewal,
seed failover, recovery, and ordered shutdown.

## 8. Step 5: Leader-owned expiry

**Status: Done 2026-09-26.** Two policies were decided with the user:

- Automatic deregistration is declared per check by the agent
  (`deregisterAfterMs`; absent or zero means never).
- Registrations declare their check IDs, so re-registration prunes checks
  removed from the agent's configuration.

Each slice was driven red to green:

1. **Model:** `deregisterAfterMillis` on the replicated observation. It is carried
   by protobuf field 13 and snapshots, and entries without it decode as never.
2. **Transport:** the field travels through the agent configuration,
   observation, client, and HTTP API, and is exposed by health discovery.
3. **Declared checks:** `Register` carries sorted, distinct check IDs with a
   `checks_declared` flag, and older entries keep existing checks. The state
   machine prunes undeclared checks and recomputes health. A late observation
   for an undeclared check is rejected with `check_not_declared` (retried by the
   agent) and cannot resurrect it. Declared sets survive snapshots and are
   forgotten on deregistration. The agent derives each service's declared checks
   from its configured checks, and the reconciler fingerprint includes them.
4. **Deadline evaluation:** `HealthExpiryEvaluator` expires checks at their exact
   deadline. It grants a full TTL from leadership acquisition, deregisters only
   after the delay, orders commands deterministically, and saturates time
   arithmetic (a real overflow found by the test).
5. **Leader-only execution:** `LeaderHealthExpiry` evaluates only while leader.
   It suppresses duplicates in flight, retries failed or throwing proposals,
   cancels on step-down, restarts the grace period on re-election, ignores
   repeated notifications, and wakes early for sooner deadlines.
6. **Two-phase rule:** the state machine now refuses to deregister a check that
   was never expired. One Step 1 test that deregistered a live check in one step
   was updated to the two-phase contract.
7. **Wiring:** `QraftControllerService` wires the component with
   `server.health.expiryIntervalMs` and a five-second proposal timeout, and stops
   it before the servers during shutdown.

Exit gate: `LeaderHealthExpiryClusterTest` uses real Raft nodes and replicated
state machines, with expiry time driven manually. It shows:

- only the leader proposes, and every replica converges;
- a partitioned former leader's expiry cannot commit;
- a replacement leader whose clock runs an hour ahead still grants a full TTL,
  and all replicas converge after healing;
- deregistration after the delay is replicated;
- a renewal racing an expiry converges to the renewal in either commit order.

`CrashedAgentExpiryEndToEndTest` kills a client runtime in a separate JVM and
observes the real server's leader expire its check and then deregister its
service.

The cluster and component tests passed three consecutive runs, and the full
default reactor passed 740 tests on JDK 27 with no failures, errors, or skips.

1. Build a side-effect-free deadline evaluator using server receipt time rather
   than trusting the agent clock.
2. Run evaluation only while leader and propose explicit expiry commands through
   Raft; leadership loss cancels outstanding local scheduling work.
3. Prove renewal-versus-expiry ordering at the same deadline and across leader
   changes.
4. Automatically deregister crashed-agent services according to the configured
   policy without deleting a newer registration or renewal.

**Exit gate.** Multi-node deterministic tests prove that followers never expire
locally and that a replacement leader converges on the same committed result.

### Step 5 follow-up: closing the recorded gaps

**Status: Done 2026-09-26.** The two gaps recorded after Step 5 were closed test
first.

- **Check-quorum.** A leader that has not heard from a majority, itself
  included, for one election timeout's worth of heartbeat rounds steps down in
  its current term and fails pending writes with `CommandOutcomeUnknownException`.
  A responsive majority, rejected appends, and a single-node cluster keep it
  leader, and it can be elected again when peers return.
  `RaftNodeCheckQuorumTest` drives this with manual timers. The cluster test now
  shows the partitioned former leader stepping down without proposing.
- **Node membership expiry.** This follows the user-selected policy:
  server-wide `server.health.nodeTtlMs`, and reaping of the node and its services
  after `server.health.nodeReapAfterMs`.
  - Membership times are stamped with the server clock, and all agent command
    times are stored in milliseconds. Without that, a leader holding nanosecond
    times would match expiries that its followers treat as no-ops.
  - `AgentCommand.Expire` (enum value 6) marks a node `UNREACHABLE`, then reaps it
    with every service it registered in every tenant, together with their checks.
    Both phases match the exact last contact, and reaping requires the
    unreachable phase; a heartbeat restores `HEALTHY`.
  - `NodeExpiryEvaluator` applies the failover grace. `LeaderHealthExpiry`
    proposes node commands and deduplicates them regardless of proposal time.
  - `CrashedAgentExpiryEndToEndTest` kills a check-free client JVM and observes
    it become unreachable and then be reaped with its service.

The full default reactor passed 762 tests on JDK 27 with no failures, errors, or
skips.

### Replica-determinism audit

**Status: Done 2026-09-26.** A codebase-wide audit covered divergence between
replicas and trust in client or local clocks. It found the following, each
reproduced by a failing test before it was fixed:

- **The leader applied a different object than it logged.** The leader applied
  the original command object, while followers and WAL replay applied the
  decoded one. Any lossy codec field could therefore diverge the leader from its
  followers, and from itself after a restart.
- **A retransmitted entry could fence a healthy follower.** Followers compared a
  retransmitted entry with a re-encoding of their stored command. Protobuf maps
  encode in `Map.copyOf` order, which is randomized per JVM, so a service
  registration with two or more metadata entries looked like a divergent log
  (production codec; reproduced in `RaftNodeAppliesWhatItLogsTest`).
- **Decoding read the local clock.** `AgentCodec` substituted `Instant.now()`
  for an entry without a time. The `AgentInfo` constructor also stamped the
  local clock during decode.
- **Custom capability values lost their types.** They were stringified, so `5`
  became `"5"`, `true` became `"true"`, a list became `"[a, b]"`, and null
  became `""`.
- **Null and empty strings swapped.** Agent and system/network info strings
  exchanged null and `""` across the codec.
- **A blank heartbeat registration ID diverged.** It was rejected on the leader
  and accepted on followers.
- **A registration could claim server-owned state.** It could claim a lifecycle
  status such as `UNREACHABLE`.
- **Replicated state leaked through readers.** `findAgent` returned the live
  mutable `AgentInfo`, `getServiceCatalog` returned the live mutable catalog,
  and command objects were aliased into state.
- **Snapshot bytes depended on the JVM.** Map order in snapshots was randomized
  per JVM.

Fixes: log entries carry their replicated bytes, and the leader applies the
command decoded from them (`LogEntry` also loses its unused `Instant.now()`
timestamp). The other fixes are:

- deterministic decoding;
- protobuf `optional` strings;
- a JSON-valued custom capabilities field with a legacy fallback;
- blank registration IDs normalized to none;
- server-owned registration status;
- deep copies in and out of the store;
- a read-only `ServiceCatalogView`;
- key-ordered snapshot maps.

Tests: `ReplicaDeterminismTest` and `RaftNodeAppliesWhatItLogsTest`. The
invariants are documented in the design document, section 13.1.

The audit's full build also exposed a pre-existing race in
`JavaRuntimeTest.executeBlockingUsesVirtualThreadAndCompletesOnRuntimeContext`.
It failed four runs in five, because a completion that won the race ran the
late-registered callback on the test thread. The test now registers its callback
before the task can complete. The full default reactor passed 773 tests on JDK 27
with no failures, errors, or skips.

### Concurrency and flakiness audit

**Status: Done 2026-09-26.** An intermittent test failure is a defect, so every
timing-dependent test was reviewed and each race was checked for in production code
too. The rule is now in `PROJECT_STANDARDS.md`, section 4.4. Production races
found and fixed, each with a regression test:

- **Double vote during recovery (high).** Vote, append, and snapshot requests
  that arrived before recovery finished could write durable state that recovery
  then overwrote, so a node could vote twice in one term. They are now rejected
  without side effects until the node is running
  (`RaftNodeRecoverySequencingTest`).
- **Writes left pending after shutdown.** A write that could no longer commit
  when the shutdown drain ended stayed pending forever. It now fails
  (`RaftNodeShutdownSequencingTest`).
- **Election timer re-armed after stop.** A vote applied while the node was
  stopping re-armed the election timer (`RaftNodeTimerSequencingTest`).
- **Log read off the state loop.** Snapshot installation read the log's last
  index off the state loop. It now reads it on the loop before publication.
- **Missed wake-up in `awaitState`.** `awaitState` could miss a state change
  between its check and its listener registration. It also consumed the node's
  own Raft timers for its timeout (`RaftNodeCheckQuorumTest`).
- **Stale timer registration.** A runtime timer that fired before its
  registration was recorded left a stale entry. This is fixed by construction;
  the interleaving could not be forced, so its test never failed first.
- **Older registration results winning.** A slow, earlier registration result or
  heartbeat rejection could overwrite a newer registration. Only the current
  attempt now records an outcome (`AgentRegistrationClientTest`).
- **Agent shutdown races.** An agent shutdown that raced startup could throw
  `RejectedExecutionException`, and shutdown could report completion before
  agent threads and clients had terminated. It now awaits quiescence
  (`QuiescenceTest`, `QraftAgentTest`).
- **Work chained after a timeout.** `CompletableFuture.orTimeout` completes on
  the JVM-wide `ForkJoinPool.commonPool-delayScheduler` thread, so work chained
  after a timeout ran there. When agent shutdown hit its deadline, it closed the
  health executor on that thread and stalled every other timeout in the process.
  Bounded waits now use `Deadlines` in `qraft-core`, which completes timeouts on
  a new virtual thread and releases the pending expiry as soon as the result
  arrives. Production code no longer uses `orTimeout` (`DeadlinesTest`,
  `FutureTest`).
- **Controller teardown on a foreign thread.** Controller teardown released the
  runtime and telemetry on whichever thread finished stopping the controller.
  That can be a thread the runtime shutdown waits for, so the thread would wait
  for itself. Release now runs on its own virtual thread
  (`QraftControllerLifecycleTest`).
- **Transitions logged in the wrong context.** A Raft transition's apply step
  logged and traced in the storage thread's context, and the next transition
  started in the previous transition's context. Each transition now captures its
  submitter's MDC and OpenTelemetry context and runs every step under it
  (`RaftTransitionSequencerTest`).

Test-only races fixed: `JavaRuntimeTest` registered a callback after the
operation could complete. `RaftTransitionSequencerTest.alreadyCompletedTransitionsDoNotDrainRecursively`
submitted 10,000 transitions against a normal admission limit of 8,750, so
whether it hit the designed back-pressure depended on whether the submitter
outran the state loop. It now sizes the sequencer for the burst and queues the
whole burst from one state-loop task, the deepest chain deterministically.

Five consecutive full default reactor runs on the final production code passed
with no failures, errors, or skips. The first two ran 794 tests; the last three
ran 796, including the two Step 6 end-to-end tests.

## 9. Step 6: End-to-end and container verification

1. Start a real runtime server and client with TTL, HTTP, and TCP checks.
2. Observe health transitions through the public API and discovery filtering.
3. Kill the client without graceful shutdown and observe leader-proposed expiry
   and automatic deregistration.
4. Replace the leader around a renewal/expiry boundary and prove one converged
   result.
5. Run the full default reactor, Docker-tagged acceptance suite, Mockito scan,
   environment-configuration scan, and `git diff --check`.

**Status: Done 2026-09-26.**

- **Items 1 and 2.** In
  `HealthPropagationEndToEndTest.configuredChecksDriveHealthTransitionsAndPassingDiscoveryThroughThePublicApi`,
  a server starts from its configuration file. An agent starts from its own file
  through `QraftAgent.launch`, the entry point that client mode uses, so the test
  can reach the process-local TTL input. It registers two `web` instances:
  `web-a` has an HTTP check, and `web-b` has a TCP check and a TTL check. Results
  are read only through `/v1/health/service/web` and its `?passing` filter:
  - Every check passes.
  - A TTL warning hides `web-b` from `?passing`.
  - An HTTP 503 hides `web-a`, and it returns when the endpoint recovers.
  - Closing the TCP listener hides `web-b`, while the unfiltered query still
    lists both instances.
  - When TTL reports stop, that check lapses to critical in the agent.
  - Graceful shutdown deregisters both instances.
- **Item 3.** `CrashedAgentExpiryEndToEndTest` (Step 5) kills a client runtime
  JVM without graceful shutdown. It then observes leader-proposed expiry
  followed by automatic deregistration, and a check-free node being marked
  unreachable and then reaped.
- **Item 4.** In `HealthPropagationEndToEndTest`, three servers start from their
  configuration files and run Raft over gRPC. A client-mode runtime reaches each
  server through a proxy that can hold its observations, and its HTTP
  observations live for 8 seconds. Holding them forces the renewal/expiry
  boundary rather than relying on timing:
  - `aRenewalHeldPastItsDeadlineAcrossALeaderChangeLandsInsideTheNewLeadersGraceSoTheCheckNeverExpires`
    holds the renewal and shuts the leader down 2.5 seconds before the held
    observation's deadline. It releases the renewal a second after the deadline.
    No survivor ever shows the check expired, and both converge on the renewal.
    With the new leader's grace removed from `HealthExpiryEvaluator`, this test
    fails.
  - `aRenewalHeldBeyondTheNewLeadersGraceExpiresTheCheckOnEverySurvivorAndTheRenewalThenRestoresIt`
    holds the renewal across the leader change until every survivor shows the
    same expired check and a critical service that is still registered. The
    released renewal then restores it on both survivors.

  `LeaderHealthExpiryClusterTest` also covers the boundary with manual time: a
  new leader whose clock is skewed, and a renewal racing an expiry.
- **Item 5.** Five consecutive full default reactor runs passed; see the
  concurrency audit above. The Docker-tagged suite ran against a freshly built
  runtime JAR, with `-Dtest.excludedGroups= -Dgroups=docker`, and passed 23
  tests with no failures, errors, or skips:
  - `ConfigurableRaftClusterTest`, 8 tests;
  - `DockerDurableRestartTest`, 8 tests;
  - `DockerRaftClusterTest`, 4 tests;
  - `NetworkPartitionTest`, 3 tests.

  The header audit covered all 250 Java files. The Mockito scan, the
  production environment-variable scan, a scan for `orTimeout` and
  `completeOnTimeout` in production code, and `git diff --check` were all clean.

### Step 6 follow-up: closing the carried-forward limits

**Status: Done 2026-09-26.** Step 6 ended with four known limits. Each is now
closed test first:

- **TCP had no warning outcome.** A `tcp` check accepts an optional
  `warnAfterMs`. A connection that takes at least that long is a warning, and a
  faster one passes again. The threshold must be shorter than `timeoutMs`, and
  zero or absent never warns. The probe measures connection time with the
  injected clock. `TcpCheckRunnerTest` covers the boundary at 299 and 300 ms, and
  `AgentConfigurationTest` covers parsing and rejection.
- **Node reaping was off by default.** `server.health.nodeReapAfterMs` now
  defaults to 72 hours, Consul's reconnect window, so a crashed agent's node and
  services no longer stay forever. `0` still turns reaping off explicitly
  (`AppConfigCoverageTest`).
- **The stale-timer fix had no failing test.** `JavaRuntime` accepts its
  scheduler through a package-private constructor. The test scheduler runs each
  timer to completion before `schedule` returns, which forces the race every
  time.
  `JavaRuntimeTest.aTimerThatFiresBeforeItsRegistrationIsRecordedLeavesNoRegistrationBehind`
  fails with a stale entry when the fix is removed. It replaces the old test,
  which fired 2,000 timers and could not guarantee the race.
- **The leader-replacement boundary relied on timing.** It is now forced with
  holding proxies; see item 4.
- **No agent with health checks ran in a container.** `DockerAgentHealthTest`
  starts three server containers and one client-mode agent container from the
  same image. The agent's mounted `docker/config/agent-acceptance/agent.json`
  declares HTTP and TCP checks against itself.
  - Every server lists the service as passing.
  - After the leader container is killed, both survivors keep receiving newer
    renewals.
  - `docker stop` of the agent deregisters its service.
  - `docker kill` of the agent lets the leader expire both checks on every
    server, and then deregister the service.

  `DockerDeploymentContractTest` validates the new compose file and the agent
  configuration in the default reactor.

Verification after these changes:

- The forced leader-replacement tests passed four consecutive runs.
- Three consecutive full default reactor runs passed 802 tests each, with no
  failures, errors, or skips.
- Against a freshly built runtime JAR, the Docker-tagged suite passed 25 tests:
  - `ConfigurableRaftClusterTest`, 8 tests;
  - `DockerAgentHealthTest`, 2 tests;
  - `DockerDurableRestartTest`, 8 tests;
  - `DockerRaftClusterTest`, 4 tests;
  - `NetworkPartitionTest`, 3 tests.
- The header audit, the Mockito scan, the production environment-variable scan,
  the `orTimeout` and `completeOnTimeout` scan, and `git diff --check` were
  clean.

## 10. Out of scope

- General configuration hot reload.
- Server-side write forwarding and leader-aware client redirection.
- Key/value sessions and locks.
- ACLs and certificate-derived identity.
- Persisting automatically generated node identity.

The next active work is the rest of Tranche 7, multi-node container acceptance,
tracked in [`task-list-multi-node-container-acceptance-2026-09-26.md`](task-list-multi-node-container-acceptance-2026-09-26.md).
