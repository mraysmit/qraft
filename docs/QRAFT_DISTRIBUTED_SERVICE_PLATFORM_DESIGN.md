# Qraft Distributed Service Platform Design

**Status:** Draft  
**Last updated:** 2026-09-25

## 1. Purpose

Qraft is a distributed service-discovery and coordination platform built around a
Raft-replicated state machine. It provides a single runtime that can operate as a
cluster server or as a client agent, together with APIs for service registration,
discovery, health, key/value data, sessions, and locks.

This document records the current architecture, the target design, the important
domain invariants, and the test-first path from the current implementation to the
target system.

### 1.1 Project module structure

Qraft is a Maven reactor with a root aggregate and seven build modules:

```text
qraft
|-- qraft-raft-engine
|-- qraft-distributed-state
|-- qraft-core
|-- qraft-agent
|-- qraft-tenant
|-- qraft-controller
`-- qraft-runtime
```

The root `qraft` project is not a deployable application. It owns the reactor,
shared dependency versions, Java 27 compiler settings, test conventions,
coverage configuration, and build-wide engineering rules.

| Module | Current purpose | Target responsibility |
|---|---|---|
| `qraft-raft-engine` | Defines reusable Raft command, state-machine, and engine contracts. | Own all implementation-neutral consensus contracts and reusable Raft primitives. It must not depend on service discovery, tenancy, HTTP, or a runtime mode. |
| `qraft-distributed-state` | Defines replicated key/value commands and codecs and currently contains the service-catalog model, including composite health-check identity, ordered observations, and replicated check state. | Own deterministic replicated-state commands and projections, including key/value behavior and catalog state. It must contain no network server, process lifecycle, or client-agent behavior. |
| `qraft-core` | Contains shared Java 27 discovery, health, node, and agent domain types, together with some inherited domain code awaiting removal. | Own small, transport-neutral value types shared between server and client. Service definitions and common identity types belong here; Raft implementation and HTTP DTOs do not. |
| `qraft-agent` | Implements client identity, node registration, the typed outbound catalog HTTP adapter, controller-seed failover, single-flight service reconciliation, heartbeat scheduling, policy-derived local liveness/readiness HTTP endpoints, bounded graceful shutdown, local execution of configured HTTP, TCP, and TTL checks, and sequenced publication of their observations and renewals. | Keep local health authoritative at the server without participating in Raft. |
| `qraft-tenant` | Implements the current namespace lifecycle abstraction and its in-memory implementation. | Own tenant and namespace policy, validation, and lifecycle contracts. Replicated persistence is performed through distributed-state commands rather than hidden local mutation. |
| `qraft-controller` | Contains the server application, Raft node implementation, transports, durable storage adapters, replicated state host, HTTP and gRPC APIs, snapshots, and graceful shutdown. | Operate one server member: participate in quorum, host authoritative replicated state, enforce request identity and policy, expose control-plane APIs and the built-in administrative interface, and own server lifecycle. |
| `qraft-runtime` | Packages controller and agent dependencies behind one executable entry point and one container image. | Remain a thin composition root that validates mode-specific configuration, constructs either server or client mode, installs process shutdown handling, and packages the built-in administrative assets into the same executable artifact without owning domain logic. |

The intended high-level dependency direction is:

```text
qraft-runtime
|-- qraft-agent
|   `-- qraft-core
`-- qraft-controller
    |-- qraft-core
    |-- qraft-tenant
    |-- qraft-distributed-state
    |   |-- qraft-core                 (target shared value types)
    |   `-- qraft-raft-engine
    `-- qraft-raft-engine
```

Arrows represent compile-time use from a higher-level composition or adapter
toward a lower-level contract or domain module. Cycles are prohibited. In
particular:

- `qraft-runtime` selects and assembles a mode but does not implement it.
- `qraft-agent` depends on shared domain contracts, not controller internals or
  replicated-state implementations.
- `qraft-controller` may compose all server-side modules, but those lower-level
  modules do not call back into the controller.
- `qraft-distributed-state` depends only on consensus contracts and shared value
  types needed by replicated commands.
- Public HTTP and gRPC request/response DTOs stay at adapter boundaries and are
  mapped explicitly to domain commands.

[`QRAFT_EVENT_ARCHITECTURE.md`](QRAFT_EVENT_ARCHITECTURE.md) proposes an
eighth module, `qraft-events`, for dependency-light event contracts. It is not
yet part of the reactor and will be added to this table when its first tranche
is implemented.

Some current code does not yet fully match these boundaries. Most notably, the
service instance and catalog classes currently live together in
`qraft-distributed-state`, while the client needs a shared service-definition
contract from `qraft-core`. The migration should move or introduce only the
shared value types; the mutable replicated catalog remains server-side.

## 2. Goals

These goals describe the operational outcomes the platform is intended to
provide. Later sections turn them into domain, protocol, persistence, lifecycle,
and acceptance requirements.

- **One deployable artifact.** Run the same executable and container image in
  either `server` or `client` mode. Role selection happens at startup so both
  modes share versioning, configuration conventions, packaging, diagnostics,
  and process-management behavior without becoming separate distributions.
- **Raft-authoritative cluster state.** Replicate every authoritative cluster
  mutation through Raft and acknowledge it only after it is committed and
  applied. No HTTP handler, background task, or administrative operation may
  create a competing mutation path around consensus.
- **Independent client agents.** Keep client agents outside the Raft quorum so
  workload nodes can scale, restart, and lose connectivity without changing
  consensus membership. Agents contribute registrations and observations;
  servers decide and retain authoritative cluster state.
- **Scoped and useful discovery.** Discover services by tenant, namespace,
  service name, and health state, with stable instance identity and deterministic
  ordering. Callers must be able to distinguish healthy candidates, degraded
  candidates, and absent services without depending on controller-local state.
- **Deterministic replicated behavior.** Applying the same committed command
  sequence and snapshot must produce the same state and indexes on every server.
  State-machine application therefore cannot depend on a local clock, network
  call, filesystem observation, or unordered iteration.
- **Operational resilience.** Preserve committed state and restore useful service
  after leader changes, network partitions, process restarts, snapshot recovery,
  and supported rolling upgrades. Temporary loss of quorum may reduce
  availability, but must not create divergent authoritative state.
- **Explicit consistency and failure semantics.** Make each read mode's freshness
  guarantee visible and distinguish rejected, retryable, and outcome-unknown
  writes. Clients should be able to make safe retry decisions without parsing
  log messages or assuming that a lost response means a failed commit.
- **Integrated administration.** Provide a built-in interface for inspecting and
  operating the platform without requiring a separate management product. The
  interface uses the same authenticated APIs, authorization rules, Raft command
  path, and consistency guarantees as every other client.
- **Bounded, testable lifecycle management.** Give every resource an explicit
  owner and make startup, readiness, drain, shutdown, and partial-startup cleanup
  observable in tests. Lifecycle operations must be idempotent and complete or
  fail within documented deadlines.
- **Direct use of Java 27.** Build concurrency, networking, and lifecycle control
  on Java 27 platform APIs rather than a framework-managed event loop. Shared
  abstractions are introduced only where they express a Qraft contract or make
  ownership and testing clearer.

## 3. Design principles

1. **Replicate intent, derive views.** Raft records validated commands that state
   what should change; catalogs, indexes, health summaries, and query structures
   are deterministic projections of the committed sequence. Derived views must
   be rebuildable from a snapshot and subsequent commands rather than acquiring
   an independent source of truth.
2. **Separate wire contracts from stored state.** HTTP and gRPC request objects
   belong to adapter boundaries and are validated and mapped into domain
   commands. Persisted models evolve under explicit compatibility rules and are
   never accidental serializations of whichever public DTO a handler accepts.
3. **Make ownership explicit.** Every executor, timer, socket, HTTP client,
   server, subscription, and storage handle has exactly one lifecycle owner.
   Owners close resources in a defined order, and callers receive one idempotent
   completion rather than relying on shutdown hooks or garbage collection.
4. **Treat retries as part of the protocol.** Classify an operation as idempotent
   or non-idempotent before defining timeouts, failover, and retry behavior.
   Request identity, duplicate handling, retry limits, and terminal rejection
   must be specified at the same boundary as the operation itself.
5. **Expose uncertainty.** A transport failure proves only that the caller did
   not receive a result; it does not prove that the command was not committed.
   APIs and clients distinguish definite rejection, safe retry, and unknown
   outcome so recovery can reconcile against authoritative state.
6. **Preserve compatibility deliberately.** Protobuf fields are never renumbered,
   snapshots and commands define defaults for newly introduced fields, and
   readers retain old formats for a documented migration window. Fixture-based
   upgrade tests prove compatibility before a format change is accepted.
7. **Keep tests behavioral.** Tests exercise observable results through real
   implementations, protocol-level fixtures, purpose-built fakes, and integration
   clusters rather than mocking frameworks. Tests at persistence, transport, and
   packaging boundaries verify serialization, failure behavior, recovery, and
   cleanup as well as the successful path.
8. **Keep consensus membership server-only.** Client agents never participate in
   Raft elections, quorum membership, or log replication. They communicate only
   through public control-plane protocols, allowing the client population to
   change independently of the small, deliberately configured server quorum.

## 4. Current architecture

### 4.1 Runtime

The `qraft-runtime` module owns the executable entry point and resolves `server`
or `client` from the required command-line subcommand. The container image
packages the runtime and selects the mode through its command, never through an
environment variable.

The runtime resolves the selected versioned configuration file before dispatch,
then launches the controller or agent through an injected mode boundary. Both
modes return the same runtime-owned lifecycle, which exposes completion and one
idempotent asynchronous close operation. The process entry point and shutdown
hook use that lifecycle directly; neither mode is started by invoking another
application's static `main` method. A runtime-boundary acceptance test exercises
real server and client startup, registration, discovery, readiness loss,
controller restart and reconciliation, deregistration, and ordered shutdown.

### 4.2 Server

Server mode currently owns:

- Raft membership, elections, replication, and snapshots.
- Durable Raft storage.
- The replicated key/value, service-catalog, and health-check state machine.
- Internal Raft gRPC transport.
- External distributed-state gRPC service.
- JDK HTTP health, status, catalog, health-observation, and health-discovery
  endpoints.
- Coordinated shutdown of Raft, servers, storage, and executors.

Catalog registration, deregistration, health observation, and health expiry are
encoded as protobuf commands and applied to `QraftStateStore`. The state machine
accepts only a strictly newer sequence for each check. It derives aggregate
service health deterministically, and an expiry changes state only when it matches
the exact sequence and deadline. Catalog and health-check contents are included in
snapshots, and the command codec can read legacy JSON log entries during
migration.

`PUT /v1/agent/check/observe` stamps each observation with the receiving server's
time before proposing it. `GET /v1/health/service/{serviceName}` returns each
instance with its replicated checks and can be filtered to passing instances
(section 12.1.1). No server evaluates deadlines yet, so an unrenewed check stays
in its last accepted state until leader-owned expiry is implemented.

### 4.3 Client agent

Client mode currently owns:

- A local identity and network address.
- A JDK HTTP liveness and readiness server.
- An agent-membership facade over the shared outbound controller client.
- A validated list of controller seeds and local service definitions.
- A `CatalogClient` port and `HttpCatalogClient` adapter for service registration,
  deregistration, and scoped presence reads across controller seeds.
- A single-flight reconciler for enabled local service definitions.
- A scheduled heartbeat publisher.
- `LocalHealthChecks`, which runs configured HTTP, TCP, and TTL checks
  independently through an injected scheduler and clock and accepts
  process-local TTL status through `LocalStatusReporter`.
- `HealthPublisher`, which publishes check results as sequenced observations
  through the shared controller client.

The client registers its node identity through the controller's
`/api/v1/agents/register`, `/api/v1/agents/heartbeat`, and
`DELETE /api/v1/agents/{agentId}` endpoints. A failed initial registration
keeps the local health server live and unready and retries in the background;
readiness is derived continuously from lifecycle state, accepted node
registration, convergence of every enabled local service definition, required
checks, and the freshness of the last successful controller response.

Checks of enabled services start once node registration is accepted. The
required-check readiness policy is: the agent is unready while any required check
has produced no result yet, or its latest local result is critical. Warning and
maintenance results keep the agent ready, optional checks never affect readiness,
and liveness is unaffected by check results. Readiness therefore follows the
health of the workloads the agent represents, while controller outages continue
to withdraw readiness through contact freshness.

`HealthPublisher` keeps at most one publication in flight per check and
coalesces results that arrive meanwhile to the latest. A changed status or output
is published immediately. An unchanged result is republished as a renewal once
half the check's TTL has passed since the last acceptance. Sequence numbers are
strictly increasing per check and start from the wall clock in milliseconds, so a
restarted agent normally exceeds the sequence its predecessor left behind. A
`stale_observation` answer raises the floor to the server's current sequence and
republishes. Retryable failures and `service_not_found` answers resend the same
observation after the capped registration backoff, which the server treats as an
idempotent replay; other rejections are logged and dropped.

`HttpCatalogClient` sends the stable service schema and node, tenant, and
namespace identity headers, assigns a fresh request ID to each attempt, enforces
the configured request timeout, and returns sealed success, retryable, or rejected
outcomes. Catalog operations, node registration, heartbeat, and node deregistration
share this classified transport. It owns and idempotently closes its JDK
`HttpClient`.

After node registration, `QraftAgent` immediately reconciles enabled service
definitions and schedules subsequent passes on its lifecycle-owned scheduler.
Successful registrations are retained across partial failures. Unchanged
definitions are verified by catalog read rather than rewritten; missing instances
are repaired, changed definitions are resubmitted, and unchanged rejected
definitions remain reported without being retried every cycle.

The production definition source is the `catalog.services` array loaded once from
the client JSON document at startup. Runtime file watching and configuration reload
are not implemented. The reconciler's removal path is therefore exercised by its
mutable source contract and tests today and becomes externally reachable when a
future reload mechanism supplies a changed definition set.

Node, heartbeat, and catalog operations update one controller-contact timestamp.
If that timestamp becomes older than `catalog.contactFreshnessMs`, readiness
returns 503 while liveness and background reconciliation continue. A later
successful response restores readiness once node membership and service
convergence are also satisfied. An agent with no service definitions requires
only accepted node registration and fresh controller contact.

Rejected node and service registrations are logged with their machine-readable
code and message while the agent remains live and unready. On shutdown, the client
withdraws readiness before stopping scheduled work. It waits for an active
reconciliation pass, deregisters every service known to have committed, prevents
new node registrations, waits for an in-flight node registration, and then sends
an idempotent node deregistration before stopping local health and HTTP resources.
All callers share one completion bounded by `agent.shutdownTimeoutMs`; when the
deadline expires, outstanding HTTP work is cancelled and incomplete cleanup is
reported once.

### 4.4 Known model gaps

- Client operations rotate across ordered controller seeds on retryable outcomes,
  stop on rejection, and remember the last successful endpoint.
- Reconciliation runs at the configured heartbeat cadence and has no independent
  exponential-backoff schedule. Repeated node-registration cycles use the
  configured capped exponential backoff with jitter.
- The most recently successful endpoint is preferred even when it is a follower;
  leader-aware write preference remains future work.
- Runtime configuration reload is not implemented, so changing or removing a
  service in the JSON file requires a client restart.
- TCP checks have no warning outcome: a connection either succeeds or fails.
- Automatic server-side expiry remains necessary because an agent can terminate
  without completing graceful deregistration.

Catalog identity and the registration boundary were corrected in Tranches 1 and
2. Instances are keyed by `(tenantId, namespace, nodeId, serviceId)`, registration
uses a dedicated request DTO, and authoritative health is initialized by the
server. Tenant, namespace, datacenter, region, and enabled state are durable
instance fields.

## 5. Target system context

```text
Applications and operators
          |
          | HTTP, gRPC, optional DNS
          v
+---------------- Qraft server cluster ----------------+
|  Server A       Server B       Server C              |
|  HTTP/gRPC      HTTP/gRPC      HTTP/gRPC             |
|       \             |             /                  |
|        +--------- Raft quorum --------+              |
|                    |                                  |
|          replicated state machine                    |
+------------------------------------------------------+
          ^
          | registration, health observations, renewal
          |
+---------+----------+       +-------------------------+
| Client agent       |       | Client agent            |
| node-a              |       | node-b                  |
| local checks        |       | local checks            |
| service definitions |       | service definitions     |
+--------------------+       +-------------------------+
```

Servers are authoritative for committed cluster state. Client agents are
authoritative only for local observations and submit those observations as
commands to the server cluster.

## 6. Runtime modes

### 6.1 Server mode

`qraft server`:

- Validates server configuration before opening resources.
- Opens durable storage.
- Starts internal Raft transport and the Raft node.
- Starts public APIs only with accurate readiness semantics.
- Participates in quorum and applies committed commands.
- Enters drain mode before shutdown.

A server is live when its process and local health server operate. It is ready
when it can safely serve the advertised API behavior. Readiness may depend on
Raft recovery, quorum, and leadership according to endpoint consistency rules.

### 6.2 Client mode

`qraft client`:

- Validates client identity, controller seeds, and service definitions.
- Starts local liveness before contacting the cluster.
- Reconciles configured services with the replicated catalog.
- Runs local health checks and publishes changes or TTL renewals.
- Remains live but unready during a controller outage.
- Never opens Raft transport or durable Raft storage.

## 7. Domain model

### 7.1 Tenant

A tenant is the top-level ownership and isolation boundary. It contains
namespaces, services, key/value entries, sessions, locks, policies, quotas, and
audit records.

The initial deployment may expose only the `default` tenant, but tenant identity
must be present in stored keys before multiple tenants are enabled.

### 7.2 Namespace

A namespace is an isolation boundary within a tenant, commonly representing an
environment, team, or application group. Every namespaced resource defaults to
the `default` namespace when no explicit value is supplied.

### 7.3 Agent and node

An agent is the client-mode process. A node is the stable logical identity that
the agent represents. One node can host multiple service instances.

`nodeId` must remain stable across ordinary agent restarts. An automatically
generated ID must be persisted locally; a hostname alone is not a sufficient
identity in environments where names can be reused.

Agent state includes:

- Tenant and namespace context.
- Node ID, hostname, address, datacenter, and region.
- Agent version and metadata.
- Last accepted contact and lifecycle status.
- Locally configured service definitions and health checks.

### 7.4 Service definition

A service definition is client-owned configuration. It contains:

- Local service ID.
- Service name.
- Advertised address and port.
- Tags and metadata.
- Enabled state.
- Optional health-check definitions.

It does not contain authoritative health state or Raft indexes.

### 7.5 Service instance

A service instance is server-owned replicated state derived from a registration
command. Its durable identity is:

```text
(tenantId, namespace, nodeId, serviceId)
```

Its discovery grouping key is:

```text
(tenantId, namespace, serviceName)
```

This permits two nodes to use the same local service ID without overwriting each
other. Re-registering the same durable identity is an idempotent update.

Stored instance data includes the definition fields plus current health,
registration index, modification index, and optional deregistration deadline.

### 7.6 Health

Health is server-owned state based on observations reported by agents or produced
by server-side checks. The target states are:

- `UNKNOWN`: no valid observation has been accepted.
- `PASSING`: all required checks pass.
- `WARNING`: service is degraded but discoverable when policy permits.
- `CRITICAL`: one or more required checks fail or expire.
- `MAINTENANCE`: explicitly disabled for discovery.

Health changes are separate Raft commands. Registration cannot claim an
authoritative health result.

## 8. Service registration and reconciliation

### 8.1 Registration flow

1. The agent loads and validates all service definitions.
2. The local health server becomes live and reports not ready.
3. The agent selects a configured controller seed.
4. Each enabled definition is submitted as an idempotent registration request.
5. The receiving server validates and proposes a Raft command.
6. Success is returned only after the command is committed and applied.
7. The agent becomes ready after every required definition is registered and
   controller contact is sufficiently recent.

Partial registration is retained. The agent retries only missing or changed
definitions and does not roll back successfully committed registrations.

### 8.2 Reconciliation

The agent periodically reconciles its startup service-definition snapshot rather
than relying on one startup request. Reconciliation repairs state after
administrative deletion or interrupted communication. Its source abstraction can
also reconcile changed or removed definitions, but the production JSON source is
immutable for the process lifetime because runtime configuration reload is not yet
implemented.

Only one reconciliation may run at a time. A stable content fingerprint prevents
unnecessary writes when the desired definition has not changed.

### 8.3 Controller selection and retry

Client configuration supplies an ordered, deduplicated list of controller seed
URIs. For an idempotent operation, the client may try each seed once in a cycle.

Retryable outcomes include:

- Connection refusal or reset.
- Request timeout.
- Server drain or temporary unavailability.
- A response indicating that the contacted server cannot accept the write.
- HTTP 429, 502, 503, or 504.

Validation, authorization, and semantic conflict responses are not retried
against every server. A failed node-registration cycle is retried with capped
exponential backoff and jitter. Periodic service reconciliation stays on the
configured heartbeat cadence; retryable operations still rotate through the seed
list within each pass. The last successful endpoint is preferred for the next
operation, irrespective of whether it is a leader or follower.

### 8.4 Deregistration

Deregistration is node-scoped and idempotent. Removing an absent instance is a
successful no-op. During graceful shutdown, the agent:

1. Marks itself unready.
2. Stops local checks and health publications, then waits for in-flight
   publications to finish, and stops new reconciliation.
3. Attempts bounded deregistration for its known registered services.
4. Deregisters its node after the service attempts complete.
5. Stops the local HTTP server.
6. Closes its scheduler and HTTP resources.

Repeated and concurrent shutdown calls share one completion. The entire sequence
is bounded by the positive `agent.shutdownTimeoutMs` file setting, which defaults
to 30 seconds. Deadline expiry force-cancels outstanding HTTP work and logs one
incomplete-cleanup warning; it does not delay process termination indefinitely.

Automatic expiry remains necessary because graceful shutdown cannot be guaranteed.

## 9. Health checks and failure detection

Agents execute checks close to the workload and report observations. Initial
check types are:

- TTL renewal.
- HTTP request.
- TCP connection.
- Process-local status supplied through an agent API.

Each observation contains tenant, namespace, node, service, check ID, status,
sequence number, observed time, and optional diagnostic output. Servers reject
stale sequence numbers. Server time determines expiry deadlines so agent clock
skew cannot indefinitely preserve a registration.

TTL expiry and automatic deregistration must be deterministic. A leader evaluates
deadlines and proposes explicit replicated expiration commands; followers do not
mutate catalog state from local timers.

Discovery returns all states by default only where the API contract says so. A
healthy-service query filters to eligible states without mutating the catalog.

## 10. Distributed key/value state

Key/value entries are scoped by tenant and namespace and contain:

- Key and opaque value.
- Creation and modification indexes.
- Optional flags.
- Optional owning session.

Writes, deletes, compare-and-set operations, acquire, and release are Raft
commands. Prefix listing is deterministic. Blocking queries wait for an index
strictly greater than the caller's observed index and terminate on timeout,
shutdown, or leadership/consistency failure.

## 11. Sessions and locks

A session binds an owner identity to a TTL and optional behavior on expiry.
Session creation, renewal, destruction, and expiration are replicated.

Lock acquisition is a conditional KV mutation tied to a live session. Release is
accepted only from the owning session. Expiration is represented by an explicit
Raft command so every server observes the same ordering between renewals and
expiry.

Leader-election helpers are library behavior built on sessions and locks rather
than a separate consensus mechanism.

## 12. API design

### 12.1 Service endpoints

Initial HTTP endpoints are:

```text
PUT /v1/agent/service/register
PUT /v1/agent/service/deregister/{serviceId}
PUT /v1/agent/check/observe
GET /v1/catalog/services
GET /v1/catalog/service/{serviceName}
GET /v1/health/service/{serviceName}
```

HTTP request DTOs map explicitly to commands. They may accept documented field
aliases for client compatibility, but responses use one stable Qraft schema.
Internal fields such as Raft indexes and authoritative health cannot be set by a
registration request.

Registration identity is supplied through interim request-context headers:

```text
X-Qraft-Node: required
X-Qraft-Tenant: optional; defaults to default
X-Qraft-Namespace: optional; defaults to default
```

The registration body is:

```json
{
  "serviceId": "web",
  "serviceName": "frontend",
  "address": "127.0.0.1",
  "port": 8080,
  "tags": ["blue"],
  "metadata": {"team": "platform"},
  "datacenter": "dc-1",
  "region": "eu-west",
  "enabled": true
}
```

`tags` and `metadata` default to empty collections, while `datacenter` and
`region` default to empty strings. Unknown fields are rejected with
`invalid_registration`. For one compatibility window only, a `health` field is
recognized but ignored; the stored health is always initialized to `UNKNOWN`.
No aliases are currently accepted.

A successful registration returns only the stable protocol fields:

```json
{
  "serviceId": "web",
  "serviceName": "frontend",
  "nodeId": "node-a",
  "tenantId": "default",
  "namespace": "default",
  "registered": true
}
```

Deregistration uses the same three identity headers. It is idempotent and returns
HTTP 200 with `{"serviceId":"web","deregistered":false}` when the composite
instance is already absent.

#### 12.1.1 Health observations and health discovery

Agents report check results and TTL renewals with
`PUT /v1/agent/check/observe`, using the same identity headers as registration.
A renewal is simply a newer observation of the same check. The body is:

```json
{
  "serviceId": "web",
  "checkId": "http",
  "status": "passing",
  "sequenceNumber": 7,
  "observedAt": "2026-09-26T09:59:59Z",
  "ttlMillis": 30000,
  "required": true,
  "output": "200 OK"
}
```

`status` is `passing`, `warning`, `critical`, or `maintenance`
(case-insensitive); `UNKNOWN` is server-derived and cannot be reported.
`sequenceNumber` and `ttlMillis` must be positive, `observedAt` is an ISO-8601
UTC instant, `required` defaults to `true`, and `output` is optional and limited
to 4096 characters. Unknown fields and any other validation failure return
`invalid_observation`.

The receiving server stamps the command with its own receipt time, and the
deadline is that receipt time plus `ttlMillis`; the agent clock never determines
expiry. Success is returned only after the observation is committed and applied:

```json
{
  "serviceId": "web",
  "checkId": "http",
  "nodeId": "node-a",
  "tenantId": "default",
  "namespace": "default",
  "sequenceNumber": 7,
  "status": "PASSING",
  "deadline": "2026-09-26T10:00:30Z",
  "accepted": true
}
```

The response carries `X-Qraft-Index`, which is at least the index that applied the
observation. Other outcomes are:

- An exact replay of the accepted observation returns the same 200 body with the
  original deadline, so a retry after an unknown outcome is safe.
- An older sequence, or the same sequence with different content, returns HTTP
  409 `stale_observation` with `currentSequenceNumber`, and state is unchanged.
- An observation for a composite instance that is not registered returns HTTP 404
  `service_not_found`.

`GET /v1/health/service/{serviceName}` returns every instance of the service in
deterministic identity order, whatever its health. Each entry pairs the stored
registration with its replicated checks ordered by check ID:

```json
[
  {
    "service": {"serviceId": "web", "health": "PASSING", "...": "..."},
    "checks": [
      {
        "checkId": "http",
        "status": "PASSING",
        "required": true,
        "sequenceNumber": 7,
        "observedAt": "2026-09-26T09:59:59Z",
        "acceptedAt": "2026-09-26T10:00:00Z",
        "deadline": "2026-09-26T10:00:30Z",
        "expired": false,
        "output": "200 OK"
      }
    ]
  }
]
```

`?passing` or `?passing=true` restricts the result to instances whose aggregate
health is `PASSING`; `passing=false` is the unfiltered default. Any other query
parameter or value returns `invalid_query`. Health reads never mutate the catalog.
`GET /v1/catalog/service/{serviceName}` continues to return bare registrations.

### 12.2 Error envelope

Errors use a stable machine-readable envelope:

```json
{
  "code": "leader_unavailable",
  "message": "No server can currently accept the write",
  "retryable": true,
  "requestId": "..."
}
```

Clients branch on `code` and `retryable`, never on human-readable messages.
`X-Request-Id` is echoed when supplied and generated otherwise; the same value is
used in request logging. The deprecated duplicate `error` property remains for
one compatibility release and is scheduled for removal in the first release
after 2026-12-31.

The current `HttpCatalogClient` applies this contract for registration and
deregistration. It treats parsed 2xx responses as success; transport failures,
timeouts, HTTP 429/502/503/504, retryable envelopes, and malformed 5xx bodies as
retryable; and non-retryable envelopes or malformed 4xx bodies as rejected. A
returned `leaderId` is exposed as an outcome hint. The shared controller transport
rotates through ordered seeds on retryable outcomes and remembers the last
successful endpoint. Failed node-registration cycles use capped exponential
backoff with jitter; catalog operations are retried by the periodic reconciler on
its normal heartbeat cadence.

### 12.3 Index metadata

Reads expose the applied state index in a response header. Blocking-query clients
send their last observed index and a bounded wait duration. Indexes are monotonic
for a given committed history and are not wall-clock timestamps.

The current catalog read endpoints return the index as `X-Qraft-Index`.

### 12.4 Built-in administrative capabilities

The server provides a built-in administrative interface backed exclusively by
the same authenticated, authorized APIs available to other clients. It must not
read or mutate controller implementation objects directly, and it must not
introduce an alternate consistency or persistence path.

The interface is part of the main executable artifact. Its static assets are
embedded at build time and served by the server-mode process; it is not a
separate module, service, container, installation, or deployment. Client mode
does not start the administrative listener. Packaging the interface must not add
domain logic to `qraft-runtime`: the runtime carries the assets, while
`qraft-controller` owns the HTTP routes, authentication, authorization, cache
policy, and lifecycle of the serving endpoint.

#### 12.4.1 Build and executable packaging

The administrative frontend is compiled before the Java packaging phase into a
static distribution containing `index.html`, hashed JavaScript and CSS bundles,
fonts, images, and an asset manifest. The frontend source remains part of the
controller source tree rather than becoming an independently released Maven
module. Tool versions and frontend dependencies are locked so a clean build is
reproducible.

Maven stages the completed distribution as generated controller resources under
`META-INF/qraft/ui/`. The existing `qraft-runtime` shade build then copies those
resources, together with the controller classes, into the executable runtime
JAR. A packaged server therefore contains everything required to serve the
interface. The container image continues to contain the same runtime artifact
and does not copy a separate web distribution into the image.

The production server reads assets through `ClassLoader` resource streams. It
must not convert resource URLs to `Path` or use `Files`, because resources inside
the shaded JAR are ZIP entries rather than ordinary filesystem files. Assets are
served by the existing JDK HTTP server and share its listener, TLS configuration,
authentication boundary, request limits, and lifecycle:

```text
qraft-runtime executable JAR
|-- Java classes
`-- META-INF/qraft/ui/
    |-- index.html
    |-- asset-manifest.json
    `-- assets/<content-hashed files>

server process
|-- /v1/...   authenticated control-plane APIs
`-- /ui/...   embedded administrative assets
```

The `/ui/` handler strips only the configured administrative path prefix before
performing a classpath lookup. A request for `/` may redirect to `/ui/` when the
interface is enabled. An unknown extensionless `GET` below `/ui/` falls back to
`index.html` for client-side routing. Missing named assets return `404`; API,
metrics, health, and debugging paths must never fall back to the frontend.

At request time, the server renders a small non-cacheable `index.html` template
with a safely JSON-encoded bootstrap object containing public runtime settings,
such as the API base path, administrative content path, enabled feature flags,
and authentication mode. Secrets and bearer credentials are never included.
Content-hashed assets are immutable and may receive long-lived cache headers;
`index.html` and the asset manifest require revalidation so upgrades do not leave
the browser pointing at removed bundles.

Responses set an explicit content type, `X-Content-Type-Options: nosniff`, a
restrictive content security policy, and the same configured security headers as
the API server. Compressed variants may be packaged and selected from
`Accept-Encoding`, but the uncompressed resource remains the canonical fallback.

Production uses embedded resources exclusively. A filesystem resource directory
may be supported as an explicit development-only override for rapid frontend
iteration. Startup validation rejects a missing directory, an absent
`index.html`, ambiguous embedded-plus-external configuration, or a content path
that overlaps `/v1/`, health, metrics, or debugging endpoints.

Disabling the interface prevents route registration but does not produce a
different executable. This preserves one build artifact while allowing operators
to reduce the exposed HTTP surface.

The supported capabilities are:

- inspect cluster membership, peer reachability, current leader, server role,
  current term, commit index, applied index, and replication lag;
- inspect storage and recovery state, including WAL size, snapshot boundary and
  age, last successful snapshot, and reported persistence or recovery failures;
- browse services by tenant, namespace, service name, node, tags, metadata, and
  authoritative health state;
- inspect service instances, their owning agents, configured checks, latest
  observations, failure reasons, and registration or renewal status;
- register, update, and deregister services when the authenticated principal has
  permission, using the normal replicated command path;
- inspect agents and nodes, including identity, metadata, advertised addresses,
  owned services, last successful reconciliation, and liveness status;
- browse key/value entries by tenant, namespace, and prefix, including creation
  and modification indexes, flags, and session ownership;
- create, compare-and-set, update, and delete key/value entries through normal
  Raft-backed APIs, subject to authorization and value-redaction policy;
- inspect, create, renew, and destroy sessions, and inspect lock ownership and
  contention without bypassing session ownership rules;
- inspect and administer tenants and namespaces, including enabled state,
  policy assignments, and quotas where those features are supported;
- inspect active configuration with secrets redacted and distinguish static
  configuration from dynamically replicated state;
- inspect health, metrics summaries, recent operational events, and audit
  records using bounded queries that cannot create unbounded in-memory history;
- expose explicit read-consistency selection and applied-index metadata for
  administrative reads;
- return structured leader hints, retryability, authorization failures, and
  unknown-outcome errors for administrative mutations exactly as the public API
  does.

Administrative mutations are auditable and carry the authenticated principal,
tenant, namespace, request ID, operation, target identity, result, and committed
index where applicable. Secrets, raw credentials, sensitive health output, and
protected key/value contents are never exposed without an explicit permission.

This section defines capabilities only. Presentation layout, navigation,
interaction patterns, and visual design are intentionally outside this document.

## 13. Consistency model

Every write is acknowledged after commit and state-machine application. Read modes
are:

- `default`: server-selected safe default.
- `stale`: local applied state without leader coordination.
- `consistent`: leader-confirmed read after establishing current-term authority.

A follower may serve stale reads. Consistent reads require leader confirmation or
forwarding. Writes may be forwarded or rejected with a structured leader hint;
until forwarding exists, clients rotate through configured seeds.

Deterministic ordering is mandatory for service lists, instances, tags, prefix
results, and snapshot serialization.

## 14. Persistence and upgrades

### 14.1 Storage boundaries

Qraft uses RaftLog as a write-ahead log, not as an application database. The WAL
stores only:

- Current term and voted-for identity.
- Indexed, termed log entries with opaque command payloads.
- Suffix-truncation records used to replace a conflicting log suffix.

RaftLog does not interpret commands, answer key/value or catalog queries, run the
state machine, or own application snapshots. Qraft owns command serialization,
materialized state, snapshot contents, snapshot transfer, and the relationship
between a snapshot boundary and the Raft log.

The detailed test-first migration and removal gates are maintained in
[`RAFTLOG_EXTERNALISATION_TDD_PLAN.md`](archive/RAFTLOG_EXTERNALISATION_TDD_PLAN.md).

The storage design therefore has two distinct ports:

```java
interface RaftWal extends AutoCloseable {
    CompletableFuture<Void> open(Path directory);
    CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor);
    CompletableFuture<PersistentMeta> loadMetadata();
    CompletableFuture<Void> appendEntries(List<LogEntryData> entries);
    CompletableFuture<Void> truncateSuffix(long fromIndex);
    CompletableFuture<Void> sync();
    CompletableFuture<List<LogEntryData>> replayLog();
    CompletableFuture<Void> truncatePrefix(long throughIndex);
}

interface SnapshotStore extends AutoCloseable {
    CompletableFuture<Void> saveAtomically(SnapshotData snapshot);
    CompletableFuture<Optional<SnapshotData>> loadLatest();
}
```

Qraft uses RaftLog's `RaftStorage` interface directly. A private future-conversion
helper adapts `CompletableFuture` to the controller runtime without recreating a
storage interface. No semantic storage adapter exists, so prefix compaction and
every other WAL operation reach the external implementation.

The snapshot store may share a node data directory with the WAL, but it is a
separate ownership and durability contract. Snapshot data, last included index,
last included term, format version, and checksum are published atomically as one
recoverable unit.

The consensus layer should use standard `CompletableFuture` or `CompletionStage`
at this boundary. Runtime-specific future wrappers add no storage semantics and
should not leak into the reusable storage port.

### 14.2 Opaque command payloads and materialized state

The KV example in the RaftLog demo establishes the intended separation:

```text
domain mutation
    -> deterministic command encoding
    -> opaque WAL payload at (index, term)
    -> durable replay in index order
    -> command decoding
    -> materialized application state
```

Qraft follows the same pattern for key/value, catalog, health, tenant, session,
and lock commands. RaftLog never sees a service ID or key. It sees only command
bytes. Reads are served from `QraftStateStore` or another materialized projection,
never by searching the WAL.

Unlike the demo's simple last-write-wins map, Qraft command application also
enforces compare-and-set indexes, session ownership, node-scoped service identity,
and health sequence ordering. Those rules belong entirely to the deterministic
state machine and must produce the same result on every server.

Command codecs must reject truncated payloads, impossible lengths, trailing data
where the format forbids it, invalid UTF-8 where text is required, and command
variants missing required fields. The WAL checksum protects stored bytes; it does
not replace domain-level payload validation.

### 14.3 Append and conflict-replacement contract

Leader submission uses this order:

1. Allocate the next log index and encode the command without mutating memory.
2. Call `appendEntries` with the indexed and termed payload.
3. Call `sync`, the durability barrier.
4. Only after successful sync, add the entry to the in-memory log.
5. Replicate it and acknowledge the client only after Raft commit and application.

Follower `AppendEntries` handling uses prepare, persist, then apply:

1. Validate the preceding index and term.
2. Calculate a side-effect-free append plan from the request and in-memory log.
3. If required, call `truncateSuffix(conflictIndex)`.
4. Append only the new entries from the plan.
5. Call `sync` once for the complete truncate-and-append batch.
6. Apply the same plan to memory.
7. Only then send a successful RPC response.

An append or sync failure leaves the in-memory log unchanged and produces a
failed response. A durability failure that fences the WAL also fences the Raft
node: later operations must not be attempted on the same storage instance.

Qraft uses RaftLog's pure `AppendPlan` as the authoritative conflict calculator.
Because Qraft retains a sentinel at the snapshot boundary, it supplies the
post-snapshot entries as a one-based relative view and translates the planned
truncation index back to the absolute Raft index. Tests cover a compacted log in
which a conflict is followed by a matching-term old suffix; the whole suffix must
still be re-appended after truncation.

Metadata is different from log append: `updateMetadata(term, votedFor)` includes
its own atomic durability barrier. A server updates its effective persistent term
and vote before granting a vote or responding based on the new term. A separate
`sync` call is neither required nor a substitute for this guarantee.

### 14.4 Snapshot and prefix-compaction contract

Prefix compaction is safe only after a covering application snapshot is durable.
The required order is:

1. Choose `lastApplied` as the snapshot boundary and resolve its exact term.
2. Capture a deterministic state-machine snapshot for that boundary.
3. Atomically and durably publish the snapshot and boundary metadata through
   `SnapshotStore`.
4. Call `RaftWal.truncatePrefix(lastIncludedIndex)`.
5. After successful durable compaction, trim the corresponding in-memory prefix
   and retain a sentinel containing the included index and term.

`truncatePrefix` is itself a durability barrier; an extra `sync` is not required.
It removes entries at indexes less than or equal to the supplied boundary while
retaining original indexes, terms, payloads, and replay order for later entries.

Failure behavior is deliberate:

- Snapshot publication failure leaves the WAL and memory unchanged.
- A crash after snapshot publication but before compaction is safe; recovery may
  ignore replayed entries at or below the snapshot boundary.
- Compaction failure leaves memory untrimmed and may fence the WAL.
- A successful compaction must never be paired with an absent or volatile
  snapshot.
- Installing a snapshot from a leader uses the same publish-before-compact order
  before changing the follower's in-memory state.

The implementation satisfies this contract. `FileSnapshotStore` publishes
snapshots durably and atomically, and prefix compaction calls RaftLog's real
`truncatePrefix`. The former in-memory snapshot adapter and its no-op prefix
truncation have been removed.

### 14.5 Recovery contract

Recovery proceeds by:

1. Loading term and vote metadata.
2. Loading the latest valid application snapshot.
3. Restoring the state machine and its included index and term.
4. Replaying the WAL in its original index order.
5. Rejecting entries that overlap the snapshot with a conflicting term.
6. Applying eligible committed entries strictly after the snapshot boundary.

RaftLog may repair only a structurally incomplete final record caused by a torn
write. Complete records with bad checksums, malformed headers, or ambiguous
interior corruption are recovery failures and fence the instance; Qraft must not
silently replace them with empty state.

The WAL holds the physical log, while Qraft owns the logical snapshot boundary.
After prefix compaction, the first replayed entry may have an index greater than
one. Recovery validates continuity relative to the snapshot boundary rather than
assuming that every WAL begins at index one.

### 14.6 Concurrency and ownership

Each node has one open WAL instance and one exclusive data-directory lock. RaftLog
owns and serializes its blocking file operations. Qraft must not add a second
executor that permits WAL operations to reorder, and it must not open two storage
instances over the same directory.

The storage future completing means the operation reached the guarantee documented
by that method; it does not mean a command is committed or applied. Raft owns the
separate persisted, committed, and applied indexes.

### 14.7 Version alignment

Qraft must pin a RaftLog version whose published interface and behavior match
Qraft's use of it. The root POM pins `raftlog.version` (currently 1.4.0), and
`RaftLogStorageIntegrationTest` exercises the real library.

Every upgrade requires contract tests against the real `FileRaftStorage` for:

- Metadata durability across close and reopen.
- Append, sync, close, reopen, and replay.
- Atomic suffix replacement followed by replay.
- Durable snapshot publication followed by prefix compaction and recovery.
- Compaction failure without premature in-memory trimming.
- Corrupt interior record failure and torn-tail recovery.
- Exclusive directory locking and post-fence operation rejection.

### 14.8 Application-format compatibility

Compatibility rules:

- Existing protobuf field numbers are never reused.
- New scalar fields have safe defaults.
- Legacy catalog entries that omit scoped identity load with tenant and namespace
  `default`, empty datacenter and region, and `enabled=true`; no operator action
  or offline rewrite is required.
- Snapshot readers accept older documents that omit newer sections.
- Mixed legacy and current WAL entries remain readable during the supported
  migration window.
- Corrupt or incomplete payloads fail recovery clearly rather than being silently
  interpreted as empty commands.

Before changing catalog identity, tests must capture legacy snapshots and command
bytes as immutable fixtures.

## 15. Security and tenancy

Tenant and namespace identity must be carried through authentication context,
commands, stored keys, queries, snapshots, metrics labels, and audit records.

The long-term design does not trust a caller-supplied node or tenant identity.
Authentication establishes the principal, and authorization maps that principal
to allowed tenants, namespaces, nodes, services, and operations.

Initial development may use explicit identity headers in a trusted environment,
but the boundary must be isolated behind a request-context interface so token or
certificate authentication can replace it without changing catalog commands.

## 16. Configuration

Qraft does not use environment variables for runtime configuration. This rule
applies to server and client modes, containers, service-manager deployments,
logging, storage, observability, and secrets. Production code must not call
`System.getenv` for configuration, configuration files must not interpolate
environment variables, and there is no environment variable for locating the
configuration file.

The runtime is started with an explicit mode. Configuration-file discovery has
the following descending precedence:

1. `--config <path>`;
2. the `qraft.config` JVM system property;
3. `config/server.json` or `config/client.json` relative to the working directory;
4. `/etc/qraft/server.json` or `/etc/qraft/client.json`.

For example:

```text
qraft server --config /etc/qraft/server.json
qraft client --config /etc/qraft/client.json
```

The path names one versioned JSON document. A client document has this shape:

```json
{
  "version": 1,
  "agent": {
    "id": "node-a",
    "httpPort": 8080,
    "heartbeatIntervalMs": 5000,
    "shutdownTimeoutMs": 30000,
    "datacenter": "dc1",
    "region": "eu-west"
  },
  "controllers": {
    "urls": [
      "http://server-a:8080",
      "http://server-b:8080",
      "http://server-c:8080"
    ],
    "requestTimeoutMs": 3000
  },
  "catalog": {
    "tenant": "default",
    "namespace": "default",
    "registrationRetryMinMs": 250,
    "registrationRetryMaxMs": 30000,
    "contactFreshnessMs": 90000,
    "services": []
  },
  "logging": {
    "directory": "/var/log/qraft"
  }
}
```

Every `controllers.urls` entry is an HTTP or HTTPS origin. Paths, queries,
fragments, and user information are rejected. A root trailing slash is removed,
scheme and host case are normalized, and equivalent origins are deduplicated.

A server document uses the same envelope and keeps all server settings beneath
`server`:

```json
{
  "version": 1,
  "server": {
    "id": "server-a",
    "applicationVersion": "2.0-ext",
    "http": { "host": "0.0.0.0", "port": 8080 },
    "apiGrpcPort": 10080,
    "raft": {
      "port": 9080,
      "nodes": {
        "server-a": "server-a:9080",
        "server-b": "server-b:9080",
        "server-c": "server-c:9080"
      },
      "electionTimeoutMs": 3000,
      "heartbeatIntervalMs": 500,
      "storage": { "type": "raftlog", "path": "/var/lib/qraft", "fsync": true },
      "snapshot": { "enabled": true, "threshold": 10000, "checkIntervalMs": 60000 },
      "logHardLimit": 100000,
      "io": { "poolSize": 10, "queueSize": 1000 }
    },
    "telemetry": {
      "enabled": true,
      "otlpEndpoint": "http://otel-collector:4317",
      "prometheusPort": 9464,
      "serviceName": "qraft-controller"
    },
    "shutdown": { "drainTimeoutMs": 5000, "timeoutMs": 30000 }
  },
  "logging": { "directory": "/var/log/qraft" }
}
```

Service definitions live in the `catalog.services` array; they are not encoded in
environment variables or discovered through a separate environment-selected
file. Sensitive material is mounted as a file and referenced by a configuration
file path when security support lands.

A service entry may carry an optional `checks` array. Check IDs are unique within
their service, and the same ID may be reused by different services:

```json
{
  "id": "web", "name": "web", "address": "10.0.0.4", "port": 9000,
  "checks": [
    {"id": "http", "type": "http", "url": "http://127.0.0.1:9000/health",
     "intervalMs": 10000, "timeoutMs": 2000, "ttlMs": 30000},
    {"id": "tcp", "type": "tcp", "intervalMs": 5000, "required": false},
    {"id": "app", "type": "ttl", "ttlMs": 15000}
  ]
}
```

- `http` issues a GET to an absolute HTTP or HTTPS `url` without user
  information. A 2xx response is passing, 429 is warning, and any other status or
  transport failure is critical. The response body is discarded.
- `tcp` opens one connection to `address` and `port`, which default to the
  service's own address and port. A connection is passing; anything else is
  critical.
- `ttl` has no probe. The local process reports its status (`passing`,
  `warning`, `critical`, or `maintenance`) through an internal agent input. If no
  report arrives within `ttlMs`, the agent records a critical result locally.
  `ttlMs` is required and it is the only timing setting.

For `http` and `tcp`, `intervalMs` defaults to 10000, `timeoutMs` to the lesser
of 2000 and the interval, and `ttlMs` to three intervals. The timeout must not
exceed the interval, and `ttlMs` must exceed it. `ttlMs` is the server-side time
to live of each published observation. `required` defaults to `true`. Unknown
check settings are rejected.

Each check runs independently on the agent. The next attempt starts only after
the previous one completes or times out, so a check never overlaps itself. A
timeout cancels the in-flight request or connection and records a critical
result. Diagnostic output is limited to 4096 characters, matching the controller
limit. Local results never modify server state directly; the agent publishes them
as health observations (section 12.1.1).

Configuration parsing accepts an injected parsed document for deterministic
tests. Only the executable boundary discovers and opens the configuration file.

Invalid configuration fails before background work starts. Unknown settings,
missing files, duplicate JSON keys, and environment-style placeholders are
invalid.

## 17. Lifecycle and resource ownership

Startup and shutdown are explicit state machines. Suggested runtime states are:

```text
NEW -> STARTING -> RUNNING -> DRAINING -> STOPPING -> TERMINATED
                    |
                    +-----------------------------> FAILED
```

Each transition is atomic and idempotent. Startup failure closes resources already
created in reverse order. Shutdown has a total deadline and reports incomplete
cleanup without waiting indefinitely.

The runtime owns mode selection. The selected mode owns its application service.
Each service owns its servers, clients, schedulers, storage, and background tasks.
No component creates an executor that another component is expected to discover
and close implicitly.

## 18. Observability

Required signals include:

- Raft role, term, commit index, applied index, and leader identity.
- Replication lag and peer reachability.
- Catalog registration, deregistration, and health-transition counters.
- Agent reconciliation attempts, failures, current endpoint, and readiness.
- Snapshot age, WAL size, recovery duration, and recovery failures.
- Request latency and errors by stable route and error code.
- Session count, expiry count, and lock contention.

Logs carry request ID, node ID, Raft role and term where applicable, tenant, and
namespace. Sensitive tokens and health-output secrets are never logged.

Metrics avoid unbounded labels such as raw service IDs, keys, request IDs, or
error messages.

## 19. Test strategy

Development follows red-green-refactor in small behavioral increments.

### 19.1 Domain tests

- Composite service identity prevents cross-node and cross-tenant collisions.
- Re-registration is deterministic and idempotent.
- Validation rejects invalid identity, address, port, tags, and metadata.
- Health transitions and stale observation rejection are deterministic.
- Session expiry and lock ownership rules cover ordering boundaries.

### 19.2 Codec and compatibility tests

- Every command round-trips through protobuf.
- Unknown fields are tolerated where safe.
- Incomplete commands are rejected.
- Old binary fixtures decode with documented defaults.
- Snapshot fixtures restore and reserialize deterministically.

### 19.3 Protocol adapter tests

Outbound HTTP adapters use a real JDK HTTP fixture to verify method, path, headers,
body, timeout, response parsing, failure classification, and resource cleanup.
Controller HTTP tests run against a real bound port and a real single-node Raft
state machine.

Administrative-resource tests run against the same real HTTP server and verify
classpath loading from the packaged JAR, content types, cache and security
headers, path-prefix stripping, client-route fallback, API-route isolation,
disabled-route behavior, bootstrap escaping, and missing-asset responses.

### 19.4 Lifecycle tests

Purpose-built fakes may represent the catalog-client boundary, scheduler trigger,
and clock. Tests cover partial startup, repeated calls, retry cancellation,
readiness transitions, bounded shutdown, and executor termination.

### 19.5 Cluster tests

In-memory and real-transport clusters cover:

- Replication to three servers.
- Writes sent to followers.
- Leader isolation and replacement.
- Conflicting uncommitted entries.
- Partition healing and follower catch-up.
- Snapshot installation and restart recovery.
- Client failover across controller seeds.

### 19.6 Container acceptance tests

Tagged tests build one image, start it in both modes, register a service, query it
through multiple servers, change leadership, and verify graceful and automatic
deregistration. These tests are separate from the fast default reactor but run in
continuous integration with Docker available.

The packaged-artifact acceptance test starts the shaded runtime JAR without a
frontend directory on disk, fetches `index.html` and a manifest-listed hashed
asset from the server, and verifies that client mode and disabled server mode do
not expose the administrative routes.

## 20. Test-first delivery sequence

Current progress, the active tranche's detailed steps, and the backlog are
tracked in the current dated task list in `docs/`
([`task-list-health-propagation-2026-09-25.md`](task-list-health-propagation-2026-09-25.md)).
Completed task lists are moved to `docs/archive/`.

### Tranche 0: Align the WAL and snapshot contracts

Status: complete (2026-09-22). See the archived
[`RAFTLOG_EXTERNALISATION_TDD_PLAN.md`](archive/RAFTLOG_EXTERNALISATION_TDD_PLAN.md).

1. Add real-storage contract tests for persist-before-memory ordering, suffix
   replacement, fencing, and reopen recovery.
2. Split application snapshots from the WAL interface and add an atomic durable
   `SnapshotStore` implementation.
3. Replace the successful prefix-compaction no-op with the real RaftLog operation
   or an explicit unsupported-operation failure.
4. Pin the validated RaftLog release and remove stale runtime-adapter assumptions.
5. Prove snapshot publication, prefix compaction, close, reopen, and recovery in
   one end-to-end storage test before enabling automatic compaction.

### Tranche 1: Correct catalog identity

Status: complete (verified 2026-09-24).

1. Add failing tests for duplicate local service IDs on different nodes.
2. Add tenant, namespace, datacenter, region, and enabled-state tests.
3. Add legacy command and snapshot fixtures.
4. Implement composite identity and persistence changes.

### Tranche 2: Stable registration protocol

Status: complete (verified 2026-09-24).

1. Add request-mapping and validation tests.
2. Separate the HTTP DTO from `ServiceInstance`.
3. Make registration and node-scoped deregistration idempotent.
4. Add structured error responses and retry classifications.

### Tranche 3: Client catalog adapter

Status: complete (verified 2026-09-24).

1. [x] Add real-HTTP contract tests for registration and deregistration.
2. [x] Implement `CatalogClient` and `HttpCatalogClient`.
3. [x] Replace the legacy agent HTTP path with the shared classified controller
   transport and seed selector.

### Tranche 4: Reconciliation and readiness

Status: complete (verified 2026-09-24).

1. [x] Add reconciliation tests for partial registration, recovery, changed and
   deleted definitions, rejection suppression, and overlapping triggers.
2. [x] Implement single-flight reconciliation and controller-seed rotation.
3. [x] Keep liveness active during cluster outages and derive readiness from policy.

### Tranche 5: Unified runtime flow

Status: complete (verified 2026-09-25).

1. [x] Introduce injectable mode launchers under tests.
2. [x] Give both production modes one runtime-owned managed lifecycle.
3. [x] Start one real server and one real client through the runtime boundary.
4. [x] Verify registration, discovery, readiness loss and recovery,
   reconciliation after controller restart, deregistration, and shutdown end to
   end.
5. [x] Exercise explicit and conventional configuration-file selection through
   the maintained one-image Docker deployment contract.

### Tranche 6: Health propagation

Status: in progress. The replicated model, the controller health API, local
check execution, and agent publication are complete; leader-owned expiry is next.

1. [x] Define health observation and expiry commands with deterministic tests.
2. [x] Implement local checks and TTL renewal.
3. [ ] Implement leader-owned expiry proposals and automatic deregistration.
4. [ ] Verify behavior across leadership changes and clock boundaries.

### Tranche 7: Multi-node container acceptance

1. Start three server containers and one client container from one image.
2. Verify replication and discovery through every server.
3. Replace the leader and verify client recovery.
4. Restart servers and verify durable recovery.

## 21. Initial acceptance criteria

The first complete service-discovery slice is accepted when:

- One image starts successfully in both modes.
- Two nodes can register the same local service ID safely.
- Client mode remains live and unready when all controllers are unavailable.
- Client mode becomes ready after all required services are committed.
- Registration succeeds when the first configured server is a follower or offline.
- Catalog data survives restart and snapshot recovery.
- Leadership can change without losing committed registrations.
- Graceful deregistration is bounded and automatic expiry handles crashes.
- The shaded runtime artifact serves the embedded administrative interface in
  server mode without external asset files or an additional process.
- The full default reactor and tagged container acceptance suite pass.

## 22. Open decisions

- Whether warning services are returned by default discovery queries.
- Whether write forwarding is implemented server-side or seed rotation remains the
  primary client behavior.
- How agent identity is established before ACL and certificate support lands.
- How long legacy command and snapshot readers remain supported.

Resolved on 2026-09-22: the public registration schema uses the Qraft field names
shown in section 12.1 and currently accepts no aliases. Unknown fields are
rejected. The legacy `health` input name is the sole temporary compatibility
exception; it is accepted but ignored because health is server-owned.

Resolved D1 on 2026-09-24: Qraft runtime configuration is a versioned JSON file.
The file is selected by explicit argument, JVM locator property, or conventional
role-specific path, in that order. Qraft does not use environment variables for
configuration or file discovery. Client service definitions are stored in the
main document's `catalog.services` array rather than a separately selected file.

Resolved D2 on 2026-09-24: reconciliation detects administrative deletion through
`GET /v1/catalog/service/{name}` and filters the response by the complete tenant,
namespace, node, and service identity. No additional node-scoped read endpoint is
required.

Resolved D3 on 2026-09-24: when the reconciler's definition source removes or
disables a definition registered by the current process, it is deregistered on
the next pass. The production JSON source is currently loaded once, so live file
removal is not observed until a future reload mechanism is implemented. Instances
left by an earlier process wait for automatic expiry from Tranche 6.

Decisions that affect durable identity or wire compatibility require an explicit
architecture decision record and fixture-based upgrade tests before implementation.
