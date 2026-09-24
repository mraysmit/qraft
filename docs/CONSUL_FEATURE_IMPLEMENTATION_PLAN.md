# Qraft Consul-Like Feature Implementation Plan

## 1. Purpose

Qraft will evolve into a Consul-like distributed service with Raft-backed state, service discovery, health monitoring, agent lifecycle management, sessions, and distributed locks.

File transfer, workflow execution, transfer jobs, job queues, and transfer-specific orchestration are out of scope.

## 2. Target Feature Set

- Raft-backed distributed key/value state
- Agent registration and health heartbeats
- Service catalog and service discovery
- Health checks and automatic deregistration
- Sessions, locks, and leader election helpers
- HTTP and optional DNS discovery APIs
- ACLs and namespaces
- Prometheus metrics and operational status

## 3. Runtime Direction: Pure Java 25

Qraft will not use Vert.x. The runtime model will use Java 25 platform APIs and standard-library concurrency primitives.

The active Maven build is Java-native. The controller uses Java 25 concurrency primitives and native grpc-java transport with no Vert.x runtime dependency.

### Replacement principles

- Use `com.sun.net.httpserver.HttpServer` or a small Java 25 HTTP abstraction for server endpoints.
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
7. Remove Vert.x dependencies and configuration from all Maven modules.
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
- Both modes load one versioned JSON configuration document named by the required
  `--config <path>` argument. Qraft does not use environment variables for runtime
  configuration or for locating that file.
- Both modes share configuration conventions, logging, metrics, signal handling, and graceful shutdown.

The Maven modules remain separated for dependency and ownership boundaries, but
they become libraries behind a thin executable runtime module. The runtime module
owns mode selection and lifecycle orchestration; `qraft-controller` and
`qraft-agent` are not separate production deployment artifacts.

The container image must support both modes without rebuilding the application:

```text
qraft-image server   # controller/Raft server
qraft-image client   # node/service agent
```

Client mode must expose real local liveness and readiness endpoints. Maintaining
only an in-memory health flag is not sufficient for container health checks or
orchestration readiness.

## 4. Architectural Boundaries

### Core modules

- `qraft-core`: shared domain models, configuration, health, and discovery primitives
- `qraft-raft-engine`: Raft consensus contracts and replicated command execution
- `qraft-distributed-state`: replicated key/value and service-catalog state
- `qraft-tenant`: tenant and namespace policy, validation, and lifecycle
- `qraft-controller`: cluster coordination, state ownership, and HTTP and gRPC APIs
- `qraft-agent`: node identity, service registration, heartbeats, and local checks
- `qraft-runtime`: single executable launcher, `server`/`client` mode selection, shared lifecycle, configuration, logging, metrics, and health wiring

Public HTTP and gRPC request and response types live at the adapter boundary in
`qraft-controller`; there is no separate API module. The authoritative module
responsibilities are defined in `QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`
section 1.1.

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

### Phase 1: Establish the core boundaries and Java 25 runtime

- Standardize naming around `Service`, `Instance`, `HealthCheck`, `Session`, and `KeyValue`.
- Remove remaining transfer, workflow, assignment, and job concepts from active APIs.
- Confirm the Maven module structure reflects the intended runtime modules.
- Define interfaces for replicated state, service catalog, health checks, and sessions.
- Remove Vert.x from module dependencies and public APIs.
- Establish shared Java 25 executors, virtual-thread policies, and shutdown conventions.
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

### Remaining implementation work

- [ ] Complete server-mode configuration and bootstrap behavior.
- [ ] Complete client-mode configuration and controller discovery behavior.
- [ ] Implement agent membership and failure detection semantics.
- [x] Implement service registration, catalog replication, and query behavior.
- [x] Add composite `(tenant, namespace, node, serviceId)` catalog identity with
  node-scoped idempotent deregistration and legacy-data defaults.
- [ ] Implement health checks and health-state propagation through Raft.
- [ ] Implement namespaces and tenancy isolation end to end.
- [ ] Add snapshot, restore, upgrade, and operational recovery workflows.
- [ ] Add multi-node integration and failure-injection coverage.
- [x] Run the complete reactor test suite and review the resulting log.
