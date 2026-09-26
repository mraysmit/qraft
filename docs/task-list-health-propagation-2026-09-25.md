# Task List: Health Propagation and Automatic Deregistration

**Date:** 2026-09-25
**Active work:** Step 5, leader-owned expiry
**Source plan:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), Tranche 6
**Predecessor:** [`archive/task-list-unified-runtime-flow-2026-09-24.md`](archive/task-list-unified-runtime-flow-2026-09-24.md)
**Standards:** [`PROJECT_STANDARDS.md`](PROJECT_STANDARDS.md)

This is the current task list for the project. When the active work is complete,
add a completion summary, move this file to `archive/`, and start a new dated task
list for the next backlog item.

## 1. Goal

Make service health authoritative, replicated, and self-cleaning. Agents execute
configured TTL, HTTP, and TCP checks near workloads and publish ordered
observations. The Raft state machine rejects stale observations, derives service
health deterministically, and removes registrations only through explicit
leader-proposed expiry commands.

## 2. Current state

Updated 2026-09-26, after Step 4.

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
- Graceful shutdown deregisters services, but registrations left by crashed or
  disconnected agents do not expire automatically. No leader evaluates
  deadlines yet (Step 5).

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

## 9. Step 6: End-to-end and container verification

1. Start a real runtime server and client with TTL, HTTP, and TCP checks.
2. Observe health transitions through the public API and discovery filtering.
3. Kill the client without graceful shutdown and observe leader-proposed expiry
   and automatic deregistration.
4. Replace the leader around a renewal/expiry boundary and prove one converged
   result.
5. Run the full default reactor, Docker-tagged acceptance suite, Mockito scan,
   environment-configuration scan, and `git diff --check`.

## 10. Out of scope

- General configuration hot reload.
- Server-side write forwarding and leader-aware client redirection.
- Key/value sessions and locks.
- ACLs and certificate-derived identity.
- Persisting automatically generated node identity.
