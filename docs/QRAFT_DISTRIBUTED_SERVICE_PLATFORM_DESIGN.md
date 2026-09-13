# Qraft Distributed Service Platform Design

**Status:** Draft  
**Last updated:** 2026-09-13

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
shared dependency versions, Java 25 compiler settings, test conventions,
coverage configuration, and build-wide engineering rules.

| Module | Current purpose | Target responsibility |
|---|---|---|
| `qraft-raft-engine` | Defines reusable Raft command, state-machine, and engine contracts. | Own all implementation-neutral consensus contracts and reusable Raft primitives. It must not depend on service discovery, tenancy, HTTP, or a runtime mode. |
| `qraft-distributed-state` | Defines replicated key/value commands and codecs and currently contains the service-catalog model. | Own deterministic replicated-state commands and projections, including key/value behavior and catalog state. It must contain no network server, process lifecycle, or client-agent behavior. |
| `qraft-core` | Contains shared Java 25 discovery, health, node, and agent domain types, together with some inherited domain code awaiting removal. | Own small, transport-neutral value types shared between server and client. Service definitions and common identity types belong here; Raft implementation and HTTP DTOs do not. |
| `qraft-agent` | Implements client identity, outbound registration, heartbeat scheduling, and local liveness/readiness HTTP endpoints. | Implement client-mode reconciliation, controller-seed failover, local health checks, TTL renewal, and explicit ownership of client resources. It never participates in Raft. |
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

Some current code does not yet fully match these boundaries. Most notably, the
service instance and catalog classes currently live together in
`qraft-distributed-state`, while the client needs a shared service-definition
contract from `qraft-core`. The migration should move or introduce only the
shared value types; the mutable replicated catalog remains server-side.

## 2. Goals

- Run one executable and one container image in either `server` or `client` mode.
- Replicate every cluster mutation through Raft.
- Keep client agents outside the Raft quorum.
- Discover services by tenant, namespace, service name, and health state.
- Preserve deterministic state-machine behavior across every server.
- Survive leader changes, network partitions, process restarts, and rolling upgrades.
- Expose explicit read consistency and write-failure behavior.
- Provide a built-in administrative interface for inspecting and operating the
  platform without requiring a separate management product.
- Provide testable lifecycle ownership with bounded startup and shutdown.
- Use Java 25 platform APIs without a framework-managed event loop.

## 3. Non-goals

- File transfer or protocol transfer engines.
- Workflow execution, job assignment, or job queues.
- Application deployment or workload scheduling.
- Service-mesh data-plane proxying.
- Client participation in Raft elections or log replication.

## 4. Design principles

1. **Replicate intent, derive views.** Commands are written to Raft; catalogs and
   indexes are deterministic projections of committed commands.
2. **Separate wire contracts from stored state.** HTTP request objects are not
   persisted domain objects.
3. **Make ownership explicit.** Every executor, timer, socket, and storage handle
   has one lifecycle owner.
4. **Treat retries as part of the protocol.** Operations are classified as
   idempotent or non-idempotent before retry behavior is defined.
5. **Expose uncertainty.** A lost response after submission is not reported as a
   definite rejection when the commit outcome is unknown.
6. **Preserve compatibility deliberately.** Protobuf fields are never renumbered,
   snapshots have defaults for new fields, and upgrade tests use old fixtures.
7. **Keep tests behavioral.** Tests use real implementations, protocol fixtures,
   purpose-built fakes, and integration clusters rather than mocking frameworks.

## 5. Current architecture

### 5.1 Runtime

The `qraft-runtime` module owns the executable entry point and resolves `server`
or `client` from the command line or `QRAFT_MODE`. The container image packages
the runtime and selects the mode at startup.

Current limitation: mode launch delegates directly to static application entry
points. This makes configuration injection, lifecycle assertions, and in-process
integration testing unnecessarily difficult.

### 5.2 Server

Server mode currently owns:

- Raft membership, elections, replication, and snapshots.
- Durable Raft storage.
- The replicated key/value and service-catalog state machine.
- Internal Raft gRPC transport.
- External distributed-state gRPC service.
- JDK HTTP health, status, and catalog endpoints.
- Coordinated shutdown of Raft, servers, storage, and executors.

Catalog registration and deregistration are encoded as protobuf commands and
applied to `QraftStateStore`. Catalog contents are included in snapshots, and the
command codec can read legacy JSON log entries during migration.

### 5.3 Client agent

Client mode currently owns:

- A local identity and network address.
- A JDK HTTP liveness and readiness server.
- An outbound HTTP registration client.
- A scheduled heartbeat publisher.

The client still targets legacy agent endpoints that the current controller HTTP
server does not expose. It also treats failed initial registration as a reason to
stop its local health server. Both behaviors must be replaced by the registration
and reconciliation design below.

### 5.4 Known model gaps

- Catalog entries are keyed only by `serviceId`, which can collide across nodes.
- Registration HTTP payloads deserialize directly into stored service instances.
- Health is accepted as registration input instead of being controller-owned state.
- Datacenter, region, namespace, tenant, and enabled state are not all represented
  explicitly in the stored service identity.
- Client configuration accepts one controller URL and cannot fail over among seeds.
- Obsolete transfer and job settings remain in client configuration resources.

## 6. Target system context

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

## 7. Runtime modes

### 7.1 Server mode

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

### 7.2 Client mode

`qraft client`:

- Validates client identity, controller seeds, and service definitions.
- Starts local liveness before contacting the cluster.
- Reconciles configured services with the replicated catalog.
- Runs local health checks and publishes changes or TTL renewals.
- Remains live but unready during a controller outage.
- Never opens Raft transport or durable Raft storage.

## 8. Domain model

### 8.1 Tenant

A tenant is the top-level ownership and isolation boundary. It contains
namespaces, services, key/value entries, sessions, locks, policies, quotas, and
audit records.

The initial deployment may expose only the `default` tenant, but tenant identity
must be present in stored keys before multiple tenants are enabled.

### 8.2 Namespace

A namespace is an isolation boundary within a tenant, commonly representing an
environment, team, or application group. Every namespaced resource defaults to
the `default` namespace when no explicit value is supplied.

### 8.3 Agent and node

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

### 8.4 Service definition

A service definition is client-owned configuration. It contains:

- Local service ID.
- Service name.
- Advertised address and port.
- Tags and metadata.
- Enabled state.
- Optional health-check definitions.

It does not contain authoritative health state or Raft indexes.

### 8.5 Service instance

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

### 8.6 Health

Health is server-owned state based on observations reported by agents or produced
by server-side checks. The target states are:

- `UNKNOWN`: no valid observation has been accepted.
- `PASSING`: all required checks pass.
- `WARNING`: service is degraded but discoverable when policy permits.
- `CRITICAL`: one or more required checks fail or expire.
- `MAINTENANCE`: explicitly disabled for discovery.

Health changes are separate Raft commands. Registration cannot claim an
authoritative health result.

## 9. Service registration and reconciliation

### 9.1 Registration flow

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

### 9.2 Reconciliation

The agent periodically reconciles desired service definitions rather than relying
on one startup request. Reconciliation repairs state after configuration changes,
administrative deletion, or interrupted communication.

Only one reconciliation may run at a time. A stable content fingerprint prevents
unnecessary writes when the desired definition has not changed.

### 9.3 Controller selection and retry

Client configuration supplies an ordered, deduplicated list of controller seed
URIs. For an idempotent operation, the client may try each seed once in a cycle.

Retryable outcomes include:

- Connection refusal or reset.
- Request timeout.
- Server drain or temporary unavailability.
- A response indicating that the contacted server cannot accept the write.
- HTTP 429, 502, 503, or 504.

Validation, authorization, and semantic conflict responses are not retried
against every server. Reconciliation cycles use capped exponential backoff with
jitter. The last successful endpoint is preferred for the next operation.

### 9.4 Deregistration

Deregistration is node-scoped and idempotent. Removing an absent instance is a
successful no-op. During graceful shutdown, the agent:

1. Marks itself unready.
2. Stops new reconciliation and health publications.
3. Attempts bounded deregistration for its known registered services.
4. Stops the local HTTP server.
5. Closes its scheduler and HTTP resources.

Automatic expiry remains necessary because graceful shutdown cannot be guaranteed.

## 10. Health checks and failure detection

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

## 11. Distributed key/value state

Key/value entries are scoped by tenant and namespace and contain:

- Key and opaque value.
- Creation and modification indexes.
- Optional flags.
- Optional owning session.

Writes, deletes, compare-and-set operations, acquire, and release are Raft
commands. Prefix listing is deterministic. Blocking queries wait for an index
strictly greater than the caller's observed index and terminate on timeout,
shutdown, or leadership/consistency failure.

## 12. Sessions and locks

A session binds an owner identity to a TTL and optional behavior on expiry.
Session creation, renewal, destruction, and expiration are replicated.

Lock acquisition is a conditional KV mutation tied to a live session. Release is
accepted only from the owning session. Expiration is represented by an explicit
Raft command so every server observes the same ordering between renewals and
expiry.

Leader-election helpers are library behavior built on sessions and locks rather
than a separate consensus mechanism.

## 13. API design

### 13.1 Service endpoints

Initial HTTP endpoints are:

```text
PUT /v1/agent/service/register
PUT /v1/agent/service/deregister/{serviceId}
GET /v1/catalog/services
GET /v1/catalog/service/{serviceName}
GET /v1/health/service/{serviceName}
```

HTTP request DTOs map explicitly to commands. They may accept documented field
aliases for client compatibility, but responses use one stable Qraft schema.
Internal fields such as Raft indexes and authoritative health cannot be set by a
registration request.

### 13.2 Error envelope

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

### 13.3 Index metadata

Reads expose the applied state index in a response header. Blocking-query clients
send their last observed index and a bounded wait duration. Indexes are monotonic
for a given committed history and are not wall-clock timestamps.

### 13.4 Built-in administrative capabilities

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

#### 13.4.1 Build and executable packaging

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

## 14. Consistency model

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

## 15. Persistence and upgrades

### 15.1 Storage boundaries

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
[`RAFTLOG_EXTERNALISATION_TDD_PLAN.md`](RAFTLOG_EXTERNALISATION_TDD_PLAN.md).

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

### 15.2 Opaque command payloads and materialized state

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

### 15.3 Append and conflict-replacement contract

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

### 15.4 Snapshot and prefix-compaction contract

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

The current RaftLog adapter does not yet satisfy this contract: it keeps snapshots
only in memory and treats prefix truncation as a successful no-op. That behavior
is acceptable only while no WAL prefix is removed and cannot be considered a
durable snapshot implementation. The adapter must be replaced or corrected before
snapshot-based compaction is enabled with the production WAL.

### 15.5 Recovery contract

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

### 15.6 Concurrency and ownership

Each node has one open WAL instance and one exclusive data-directory lock. RaftLog
owns and serializes its blocking file operations. Qraft must not add a second
executor that permits WAL operations to reorder, and it must not open two storage
instances over the same directory.

The storage future completing means the operation reached the guarantee documented
by that method; it does not mean a command is committed or applied. Raft owns the
separate persisted, committed, and applied indexes.

### 15.7 Version alignment

Qraft must pin a RaftLog version whose published interface and behavior match the
adapter. The current Qraft dependency is older than the checked sister project and
does not expose the sister project's complete prefix-compaction behavior through
the adapter.

An upgrade requires contract tests against the real `FileRaftStorage` for:

- Metadata durability across close and reopen.
- Append, sync, close, reopen, and replay.
- Atomic suffix replacement followed by replay.
- Durable snapshot publication followed by prefix compaction and recovery.
- Compaction failure without premature in-memory trimming.
- Corrupt interior record failure and torn-tail recovery.
- Exclusive directory locking and post-fence operation rejection.

### 15.8 Application-format compatibility

Compatibility rules:

- Existing protobuf field numbers are never reused.
- New scalar fields have safe defaults.
- New identity fields require an explicit migration from legacy catalog keys.
- Snapshot readers accept older documents that omit newer sections.
- Mixed legacy and current WAL entries remain readable during the supported
  migration window.
- Corrupt or incomplete payloads fail recovery clearly rather than being silently
  interpreted as empty commands.

Before changing catalog identity, tests must capture legacy snapshots and command
bytes as immutable fixtures.

## 16. Security and tenancy

Tenant and namespace identity must be carried through authentication context,
commands, stored keys, queries, snapshots, metrics labels, and audit records.

The long-term design does not trust a caller-supplied node or tenant identity.
Authentication establishes the principal, and authorization maps that principal
to allowed tenants, namespaces, nodes, services, and operations.

Initial development may use explicit identity headers in a trusted environment,
but the boundary must be isolated behind a request-context interface so token or
certificate authentication can replace it without changing catalog commands.

## 17. Configuration

All production environment variables use the `QRAFT_` prefix. Proposed client
settings include:

```text
QRAFT_MODE=client
QRAFT_AGENT_ID=node-a
QRAFT_AGENT_HTTP_PORT=8080
QRAFT_CONTROLLER_URLS=http://server-a:8080,http://server-b:8080,http://server-c:8080
QRAFT_TENANT=default
QRAFT_NAMESPACE=default
QRAFT_DATACENTER=dc1
QRAFT_REGION=eu-west
QRAFT_REGISTRATION_RETRY_MIN_MS=250
QRAFT_REGISTRATION_RETRY_MAX_MS=30000
QRAFT_CONTROLLER_REQUEST_TIMEOUT_MS=3000
```

Service definitions should be loaded from a dedicated JSON, YAML, or properties
source rather than encoded into many environment variables. Configuration parsing
accepts an injected key/value source for deterministic tests; direct environment
access is limited to the executable boundary.

Invalid configuration fails before background work starts. Unknown settings may
produce warnings during a migration period and errors after removal deadlines.

## 18. Lifecycle and resource ownership

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

## 19. Observability

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

## 20. Test strategy

Development follows red-green-refactor in small behavioral increments.

### 20.1 Domain tests

- Composite service identity prevents cross-node and cross-tenant collisions.
- Re-registration is deterministic and idempotent.
- Validation rejects invalid identity, address, port, tags, and metadata.
- Health transitions and stale observation rejection are deterministic.
- Session expiry and lock ownership rules cover ordering boundaries.

### 20.2 Codec and compatibility tests

- Every command round-trips through protobuf.
- Unknown fields are tolerated where safe.
- Incomplete commands are rejected.
- Old binary fixtures decode with documented defaults.
- Snapshot fixtures restore and reserialize deterministically.

### 20.3 Protocol adapter tests

Outbound HTTP adapters use a real JDK HTTP fixture to verify method, path, headers,
body, timeout, response parsing, failure classification, and resource cleanup.
Controller HTTP tests run against a real bound port and a real single-node Raft
state machine.

Administrative-resource tests run against the same real HTTP server and verify
classpath loading from the packaged JAR, content types, cache and security
headers, path-prefix stripping, client-route fallback, API-route isolation,
disabled-route behavior, bootstrap escaping, and missing-asset responses.

### 20.4 Lifecycle tests

Purpose-built fakes may represent the catalog-client boundary, scheduler trigger,
and clock. Tests cover partial startup, repeated calls, retry cancellation,
readiness transitions, bounded shutdown, and executor termination.

### 20.5 Cluster tests

In-memory and real-transport clusters cover:

- Replication to three servers.
- Writes sent to followers.
- Leader isolation and replacement.
- Conflicting uncommitted entries.
- Partition healing and follower catch-up.
- Snapshot installation and restart recovery.
- Client failover across controller seeds.

### 20.6 Container acceptance tests

Tagged tests build one image, start it in both modes, register a service, query it
through multiple servers, change leadership, and verify graceful and automatic
deregistration. These tests are separate from the fast default reactor but run in
continuous integration with Docker available.

The packaged-artifact acceptance test starts the shaded runtime JAR without a
frontend directory on disk, fetches `index.html` and a manifest-listed hashed
asset from the server, and verifies that client mode and disabled server mode do
not expose the administrative routes.

## 21. Test-first delivery sequence

### Tranche 0: Align the WAL and snapshot contracts

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

1. Add failing tests for duplicate local service IDs on different nodes.
2. Add tenant, namespace, datacenter, region, and enabled-state tests.
3. Add legacy command and snapshot fixtures.
4. Implement composite identity and persistence changes.

### Tranche 2: Stable registration protocol

1. Add request-mapping and validation tests.
2. Separate the HTTP DTO from `ServiceInstance`.
3. Make registration and node-scoped deregistration idempotent.
4. Add structured error responses and retry classifications.

### Tranche 3: Client catalog adapter

1. Add real-HTTP contract tests for registration and deregistration.
2. Implement `CatalogClient` and `HttpCatalogClient`.
3. Replace the legacy agent registration client.

### Tranche 4: Reconciliation and readiness

1. Add lifecycle tests for initial failure, partial registration, recovery, and
   shutdown.
2. Implement single-flight reconciliation and controller-seed rotation.
3. Keep liveness active during cluster outages and derive readiness from policy.

### Tranche 5: Unified runtime flow

1. Introduce injectable mode launchers under tests.
2. Start one server and one client through the runtime boundary.
3. Verify registration, discovery, readiness, and shutdown end to end.

### Tranche 6: Health propagation

1. Define health observation and expiry commands with deterministic tests.
2. Implement local checks and TTL renewal.
3. Implement leader-owned expiry proposals and automatic deregistration.
4. Verify behavior across leadership changes and clock boundaries.

### Tranche 7: Multi-node container acceptance

1. Start three server containers and one client container from one image.
2. Verify replication and discovery through every server.
3. Replace the leader and verify client recovery.
4. Restart servers and verify durable recovery.

## 22. Initial acceptance criteria

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

## 23. Open decisions

- Whether public HTTP field names must exactly match an external compatibility
  profile or only provide documented aliases.
- Whether warning services are returned by default discovery queries.
- Whether write forwarding is implemented server-side or seed rotation remains the
  primary client behavior.
- How agent identity is established before ACL and certificate support lands.
- Which service-definition file formats are supported initially.
- How long legacy command and snapshot readers remain supported.

Decisions that affect durable identity or wire compatibility require an explicit
architecture decision record and fixture-based upgrade tests before implementation.
