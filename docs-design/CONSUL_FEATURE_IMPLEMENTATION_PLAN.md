# Qraft Consul-Like Feature Implementation Plan

## 1. Purpose

Qraft will evolve into a Consul-like distributed service with Raft-backed state, service discovery, health monitoring, agent lifecycle management, sessions, and distributed locks.

**Delivery status.** This plan defines the target feature set. What is built is
recorded in
[`QRAFT_FEATURE_VALIDATION_2026-09-27.md`](QRAFT_FEATURE_VALIDATION_2026-09-27.md)
and in the dated task lists in this directory and in `docs/archive/`. The checklist
at the end of this plan was last reconciled with the code on 2026-09-27; its
membership items were updated on 2026-10-02. Sections 3 and 4 were brought up
to date on 2026-10-05 with the move to one Maven project and the removal of
`qraft-tenant`.

## 2. Target Feature Set

- Raft-backed distributed key/value state
- Agent registration and health heartbeats
- Service catalog and service discovery
- Health checks and automatic deregistration
- Sessions, locks, and leader election helpers
- HTTP and optional DNS discovery APIs
- ACLs and namespaces
- Prometheus metrics and operational status

## 3. Runtime Direction: Pure Java 27

Qraft will not use Vert.x. The runtime model will use Java 27 platform APIs and standard-library concurrency primitives.

The active Maven build is Java-native. The controller uses Java 27 concurrency primitives and native grpc-java transport with no Vert.x runtime dependency.

### Replacement principles

- Use `com.sun.net.httpserver.HttpServer` or a small Java 27 HTTP abstraction for server endpoints.
- Use `java.net.http.HttpClient` for outbound agent and health-check requests.
- Use virtual threads for blocking request and background service work.
- Use `StructuredTaskScope` where structured concurrency improves lifecycle management.
- Use `CompletableFuture`, `ExecutorService`, and `ScheduledExecutorService` only where they fit the operation model.
- Use `java.util.concurrent.Flow` or direct queues for internal event delivery.
- Keep Raft and WAL integration behind Java-native interfaces that use standard `CompletableFuture` or `CompletionStage`, never framework-specific futures.
- Avoid framework-managed event loops, reactive wrappers, and framework-specific futures.
- Make shutdown explicit and ownership-based for every thread, executor, socket, and file resource.

### Migration order

1. Remove Vert.x types from public interfaces and domain models.
2. Introduce Java-native HTTP server and client abstractions.
3. Replace Vert.x timers with scheduled executors or virtual-thread loops.
4. Replace Vert.x `Future` and `Promise` usage with `CompletableFuture` or direct results.
5. Migrate health checks and agent heartbeats.
6. Migrate controller endpoints and middleware.
7. Remove Vert.x dependencies and configuration from the Maven build.
8. Remove obsolete reactive integration tests and examples.

### Single-binary runtime and startup modes

Qraft will be distributed as one executable runtime and one container image. The
runtime will select its role at startup rather than requiring separate controller
and agent distributions:

```text
qraft server
qraft client
```

- `server` starts the controller runtime, participates in the Raft quorum, owns replicated state, and exposes the control-plane APIs.
- `client` starts the agent runtime, represents a managed node or service, registers with a controller, reports health, and sends heartbeats.
- Client mode never participates in the Raft quorum.
- Runtime mode is selected only by the `server` or `client` command-line
  subcommand. Environment-variable mode selection is not supported.
- Mode-specific configuration must be validated before background services start.
- Both modes load one versioned JSON configuration document. Its location is
  resolved from `--config <path>`, `-Dqraft.config=<path>`, or the conventional
  `config/<role>.json` and `/etc/qraft/<role>.json` paths. Qraft does not use
  environment variables for runtime configuration or file discovery.
- Both modes share configuration conventions, logging, metrics, signal handling, and graceful shutdown.

Qraft is one Maven project that builds one executable jar. Until 2026-10-04 it
was a reactor of modules; their dependency and ownership boundaries are now
package boundaries, enforced by a dependency test. A thin entry-point package
owns mode selection and lifecycle orchestration; the server and client code are
not separate production deployment artifacts.

The container image must support both modes without rebuilding the application:

```text
qraft-image server   # controller/Raft server
qraft-image client   # node/service agent
```

Client mode must expose real local liveness and readiness endpoints. Maintaining
only an in-memory health flag is not sufficient for container health checks or
orchestration readiness.

## 4. Architectural Boundaries

### Core layers

These were Maven modules until 2026-10-04 and are now layers of packages in one
build. The former module name is given in brackets.

- Shared types (`qraft-core`): shared domain models, configuration, health, and discovery primitives
- Raft contracts (`qraft-raft-engine`): Raft consensus contracts and replicated command execution
- Replicated state (`qraft-distributed-state`): replicated key/value and service-catalog state
- Server (`qraft-controller`): cluster coordination, state ownership, and HTTP and gRPC APIs
- Client (`qraft-agent`): node identity, service registration, heartbeats, and local checks
- Entry point (`qraft-runtime`): single executable launcher, `server`/`client` mode selection, shared lifecycle, configuration, logging, metrics, and health wiring

Tenant and namespace policy, validation, and lifecycle have no code today:
`qraft-tenant` was removed on 2026-10-04, and they return as replicated state
with the tenancy work (phase 7).

Public HTTP and gRPC request and response types live at the adapter boundary in
the server code; there is no separate API layer. The authoritative layer
responsibilities and their packages are defined in
`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md` section 1.1.

### Design principles

- All cluster mutations must be Raft-replicated.
- Reads must expose explicit consistency behavior.
- Agents own local health observations.
- Controllers own the replicated service catalog.
- Only server mode participates in Raft consensus; client mode is an outbound control-plane participant.
- One runtime image must be deployable in either mode without rebuilding the application.
- Service discovery must not depend on transfer or workflow concepts.
- Durable state must use the RaftLog/WAL implementation.

## 5. Implementation Phases

### Phase 1: Establish the core boundaries and Java 27 runtime

- Standardize naming around `Service`, `Instance`, `HealthCheck`, `Session`, and `KeyValue`.
- Remove remaining transfer, workflow, assignment, and job concepts from active APIs.
- Confirm the build's structure reflects the intended runtime layers.
- Define interfaces for replicated state, service catalog, health checks, and sessions.
- Remove Vert.x from the build's dependencies and public APIs.
- Establish shared Java 27 executors, virtual-thread policies, and shutdown conventions.
- Add the unified runtime launcher with explicit `server` and `client` startup modes.
- Define mode-specific configuration validation and common lifecycle ownership.
- Add client liveness and readiness HTTP endpoints and connect them to container health checks.

### Phase 2: Distributed key/value store

Implement the Consul-style KV feature:

- `PUT /v1/kv/:key`
- `GET /v1/kv/:key`
- `DELETE /v1/kv/:key`
- CAS updates using modify indexes.
- Key metadata and versioning.
- Prefix listing.
- Blocking queries based on state-index changes.
- Raft replication for all writes.

### Phase 3: Service catalog

Introduce service registration with:

- Service ID
- Service name
- Address and port
- Tags
- Metadata
- Datacenter and region
- Node identity
- Enable/disable state

Endpoints:

- `PUT /v1/agent/service/register`
- `PUT /v1/agent/service/deregister/:serviceId`
- `GET /v1/catalog/services`
- `GET /v1/catalog/service/:service`
- `GET /v1/health/service/:service`

### Phase 4: Agent lifecycle and health

The agent will be responsible for:

- Node registration.
- Service registration.
- Heartbeats.
- TTL health checks.
- HTTP health checks.
- Automatic deregistration after expiry.
- Qraft-prefixed configuration.
- Running as the `client` mode of the unified Qraft runtime.
- Serving local liveness and readiness endpoints for orchestration.

The agent will not poll for jobs or execute transfers.

Client mode is not a Raft node. It communicates with the controller cluster and
reports local observations; the controller cluster remains responsible for
replicating the resulting catalog and health state.

### Phase 5: Sessions and distributed locks

Implement:

- Session creation.
- TTL renewal.
- Session destruction.
- Automatic expiration.
- KV acquire/release semantics.
- Lock ownership enforcement.
- Leader-election helpers built on sessions and KV.

Endpoints:

- `POST /v1/session/create`
- `PUT /v1/session/renew`
- `PUT /v1/session/destroy`
- `PUT /v1/kv/:key?acquire=session-id`
- `PUT /v1/kv/:key?release=session-id`

### Phase 6: Consistency and discovery behavior

Add:

- Default, stale, and consistent reads.
- Blocking queries.
- Monotonic indexes.
- Leader forwarding for writes.
- Readiness behavior during Raft leadership changes.
- Deterministic service ordering.

### Phase 7: Security and tenancy

After the core behavior is stable, add:

- ACL tokens.
- Policies and capabilities.
- Namespaces.
- Datacenter isolation.
- Token-validation middleware.
- Audit events.

### Phase 8: Operations

Add:

- Prometheus metrics.
- Node and cluster status.
- Health aggregation.
- Structured error responses.
- Configuration reference.
- One container image and deployment examples for both `server` and `client` modes.
- Docker examples for a three-node cluster.
- Optional DNS discovery interface.

## 6. First Implementation Slice

The first delivery should establish the basic Consul-like behavior:

1. Add the unified executable runtime with `server` and `client` modes.
2. Remove remaining transfer/job references from active controller and agent code.
3. Introduce `ServiceRegistration` and `ServiceInstance`.
4. Implement service registration and deregistration through Raft.
5. Expose controller catalog/health APIs and client liveness/readiness APIs.
6. Add one end-to-end multi-node registration flow using one image in both modes.

This slice should be complete before implementing sessions, ACLs, or DNS.

## 7. Completion Criteria

The implementation will be considered aligned with the target design when:

- No transfer or workflow classes remain in the active build.
- One binary and container image start successfully in both `server` and `client` modes.
- Client mode exposes working liveness and readiness endpoints.
- Agents register services and report health.
- Controllers replicate catalog mutations through Raft.
- KV operations support versioning and CAS semantics.
- Service discovery works during controller leadership changes.
- Sessions and locks have deterministic expiration behavior.
- Operational state is exposed through health and metrics endpoints.
- No Vert.x dependencies, types, timers, event loops, or framework futures remain.
## Implementation Checklist

Items ticked before 2026-10-04 keep the wording of the module build they were
done in; `qraft-runtime` and "reactor" below refer to it. The modules were
merged into one Maven project on 2026-10-04.

### Runtime and deployment foundation

- [x] Add the `qraft-runtime` module to the Maven reactor.
- [x] Provide one executable runtime entry point.
- [x] Support `server` and `client` startup modes.
- [x] Resolve the mode from a command-line argument.
- [x] Package one Docker image with a mode-aware entrypoint.
- [x] Compile and test the runtime module with its controller and agent dependencies.

### Health and lifecycle foundation

- [x] Expose client liveness and readiness endpoints.
- [x] Mark the client ready only after successful controller registration.
- [x] Start and stop the controller HTTP health server with the controller lifecycle.
- [x] Add real HTTP tests for client health transitions.
- [x] Add runtime mode-resolution tests.
- [x] Bound client shutdown, deregister services before the node, and terminate
  owned scheduler and HTTP resources.

### Remaining implementation work

- [x] Complete server-mode configuration and bootstrap behavior. A cluster
  forms from `server.raft.nodes`; from then on, membership lives in the
  replicated log.
- [x] Complete versioned client-mode configuration and the one-controller catalog
  HTTP adapter with typed retryable and rejected outcomes.
- [x] Complete controller discovery behavior: seed rotation, preferred-endpoint
  memory, capped backoff for repeated node-registration cycles, and shared
  node/catalog transport ownership. Periodic reconciliation remains on the
  configured heartbeat cadence.
- [x] Implement single-flight service reconciliation with stable fingerprints,
  partial-success retention, catalog absence repair, and rejection suppression.
- [x] Derive agent readiness from node membership, required service convergence,
  and controller-contact freshness.
- [x] Implement agent membership and failure detection semantics: unreachable
  after the node TTL, reaped with its services after the reap delay.
- [x] Implement service registration, catalog replication, and query behavior.
- [x] Add composite `(tenant, namespace, node, serviceId)` catalog identity with
  node-scoped idempotent deregistration and legacy-data defaults.
- [x] Implement health checks and health-state propagation through Raft: HTTP,
  TCP, and TTL checks, leader-owned expiry, and automatic deregistration.
- [ ] Implement namespaces and tenancy isolation end to end: replicated tenants
  and namespaces, and scoped reads (feature validation item 6).
- [x] Add snapshots in every role, installation, restart recovery, offline
  backups, replica replacement, and guidance for explicitly compatible upgrades
  in `RAFT_STORAGE_OPERATIONS.md`. Byte-compatible legacy payloads do not imply
  whole-node upgrade compatibility.
- [ ] Add lost-quorum recovery and a tested whole-cluster restore contract.
  Rejoining an existing cluster from an older backup under the same voter ID
  is unsupported (membership Step 7).
- [x] Add multi-node integration and failure-injection coverage, including
  container crash, partition, and whole-cluster restart tests.
- [x] Run the complete reactor test suite and review the resulting log.
- [x] Remove the job-system concepts from the active build.
- [x] Make server readiness reflect recovery, fencing, draining, and leadership
  (feature validation item 2).
- [ ] Complete the key/value store: tenant and namespace scoping, indexes,
  flags, compare-and-set, and the HTTP `/v1/kv` API (phase 2; item 3).
- [ ] Add `default`, `stale`, and `consistent` reads, and blocking queries
  (phase 6; item 4).
- [ ] Implement sessions, locks, and leader-election helpers (phase 5; item 5).
- [ ] Add catalog, health, request, agent, and session metrics, and cluster
  membership and storage status (phase 8; item 7).
- [ ] Implement the bounded event journal (item 8).
- [ ] Implement ACL tokens, policies, token validation, and audit events
  (phase 7; item 9).
- [ ] Implement server membership change. Decided 2026-09-29, and in progress
  in [`task-list-raft-membership-changes-2026-09-29.md`](task-list-raft-membership-changes-2026-09-29.md):
  server IDs, the configuration in the log, and non-voter promotion are done.
- [ ] Decide on and implement DNS discovery and persisted generated node
  identity (item 10).
