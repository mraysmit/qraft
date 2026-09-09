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

The active Maven build is Java-native. The legacy agent and controller modules still contain Vert.x implementations and must be migrated before they are reintroduced into the active build.

### Replacement principles

- Use `com.sun.net.httpserver.HttpServer` or a small Java 25 HTTP abstraction for server endpoints.
- Use `java.net.http.HttpClient` for outbound agent and health-check requests.
- Use virtual threads for blocking request and background service work.
- Use `StructuredTaskScope` where structured concurrency improves lifecycle management.
- Use `CompletableFuture`, `ExecutorService`, and `ScheduledExecutorService` only where they fit the operation model.
- Use `java.util.concurrent.Flow` or direct queues for internal event delivery.
- Keep Raft and WAL integration behind synchronous, Java-native interfaces.
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

## 4. Architectural Boundaries

### Core modules

- `qraft-core`: shared domain models, configuration, health, and discovery primitives
- `qraft-raft-engine`: Raft consensus and replicated command execution
- `qraft-distributed-state`: replicated key/value state
- `qraft-controller`: cluster coordination, state ownership, and HTTP APIs
- `qraft-agent`: node identity, service registration, heartbeats, and local checks
- `qraft-api`: public API contracts and client-facing representations

### Design principles

- All cluster mutations must be Raft-replicated.
- Reads must expose explicit consistency behavior.
- Agents own local health observations.
- Controllers own the replicated service catalog.
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
- `PUT /v1/agent/service/deregister`
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

The agent will not poll for jobs or execute transfers.

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
- Docker examples for a three-node cluster.
- Optional DNS discovery interface.

## 6. First Implementation Slice

The first delivery should establish the basic Consul-like behavior:

1. Remove remaining transfer/job references from active controller and agent code.
2. Introduce `ServiceRegistration` and `ServiceInstance`.
3. Implement service registration and deregistration through Raft.
4. Expose catalog and health endpoints.
5. Add one end-to-end multi-node registration flow.

This slice should be complete before implementing sessions, ACLs, or DNS.

## 7. Completion Criteria

The implementation will be considered aligned with the target design when:

- No transfer or workflow classes remain in the active build.
- Agents register services and report health.
- Controllers replicate catalog mutations through Raft.
- KV operations support versioning and CAS semantics.
- Service discovery works during controller leadership changes.
- Sessions and locks have deterministic expiration behavior.
- Operational state is exposed through health and metrics endpoints.
- No Vert.x dependencies, types, timers, event loops, or framework futures remain.
