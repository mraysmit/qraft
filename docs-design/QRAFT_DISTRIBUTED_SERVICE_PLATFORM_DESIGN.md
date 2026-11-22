# Qraft Distributed Service Platform Design

**Status:** Draft  
**Last updated:** 2026-11-22

This document is Qraft's design: what Qraft is and why, the contracts its parts keep,
and the rules that decide its behaviour. Reference detail lives elsewhere:

- [`docs/HTTP_API.md`](../docs/HTTP_API.md): every route, its parameters, bodies,
  answers and error codes.
- [`docs/CONFIGURATION.md`](../docs/CONFIGURATION.md): every setting and its default.
- [`docs/OPERATIONS.md`](../docs/OPERATIONS.md): the operator runbook.
- [`docs/TESTING.md`](../docs/TESTING.md): how the suites run and how logs are audited.
- [`docs/QRAFT_EVENT_ARCHITECTURE.md`](../docs/QRAFT_EVENT_ARCHITECTURE.md): the event
  model.
- [`QRAFT_ADMIN_UI_UX_DESIGN.md`](QRAFT_ADMIN_UI_UX_DESIGN.md): the administrative
  interface's design.

The task lists and plans that built each part are kept for history in
[`docs/archive/`](../docs/archive).

## 1. Purpose and scope

Qraft is a distributed service-discovery and coordination platform built around a
Raft-replicated state machine, on the model of HashiCorp Consul. One executable runs
either as a cluster server or as a client, and together they provide service
registration, discovery, health checking, key/value data, sessions and locks, tenancy,
access control, events, DNS and an embedded administrative interface.

This document covers the platform's architecture, domain model, replication,
consistency, persistence, security, lifecycle and test strategy. It names Consul's
behaviour where Qraft follows it and says why where Qraft departs from it. It does not
list routes or settings; the reference documents above do.

## 2. Goals

- **One deployable artifact.** Run the same executable and container image in either
  `server` or `client` mode. The mode is chosen at startup, so both modes share
  versioning, configuration conventions, packaging, diagnostics and process
  management without becoming separate distributions.
- **Raft-authoritative cluster state.** Replicate every authoritative cluster mutation
  through Raft and acknowledge it only after it is committed and applied. No HTTP
  handler, background task or administrative operation creates a mutation path
  around consensus.
- **Independent clients.** Keep clients outside the Raft quorum, so workload nodes can
  scale, restart and lose connectivity without changing consensus membership. Clients
  contribute registrations and observations; servers decide and keep authoritative
  state.
- **Scoped and useful discovery.** Discover services by tenant, namespace, service
  name and health, with stable instance identity and deterministic ordering. Callers
  can tell healthy candidates, degraded candidates and absent services apart without
  depending on one server's local state.
- **Deterministic replicated behaviour.** Applying the same committed command sequence
  and snapshot produces the same state and indexes on every server. State-machine
  application therefore never depends on a local clock, a network call, a filesystem
  observation or unordered iteration.
- **Operational resilience.** Preserve committed state and restore service after
  leader changes, partitions, process restarts, snapshot recovery and supported
  rolling upgrades. Loss of quorum may reduce availability; it never creates
  divergent authoritative state.
- **Explicit consistency and failure semantics.** Make each read mode's freshness
  visible, and tell rejected, retryable and outcome-unknown writes apart, so callers
  can retry safely without parsing log messages or assuming that a lost response means
  a failed commit.
- **Integrated administration.** Provide a built-in interface for inspecting and
  operating the platform without a separate management product. The interface uses
  the same APIs, authorization rules, Raft command path and consistency guarantees as
  every other caller.
- **Bounded, testable lifecycle management.** Give every resource an explicit owner,
  and make startup, readiness, drain, shutdown and partial-startup cleanup observable
  in tests. Lifecycle operations are idempotent and complete or fail within known
  deadlines.
- **Direct use of Java 27.** Build concurrency, networking and lifecycle control on
  Java 27 platform APIs rather than a framework-managed event loop. A shared
  abstraction exists only where it expresses a Qraft contract or makes ownership and
  testing clearer.

## 3. Principles

1. **Replicate intent, derive views.** Raft records validated commands that state what
   should change. Catalogs, indexes, health summaries and query structures are
   deterministic projections of the committed sequence, rebuildable from a snapshot and
   the commands after it, never an independent source of truth.
2. **Separate wire contracts from stored state.** HTTP and gRPC request objects belong
   to adapter boundaries and are validated and mapped into domain commands. Persisted
   models evolve under explicit compatibility rules and are never accidental
   serializations of a public DTO.
3. **Make ownership explicit.** Every executor, timer, socket, HTTP client, server,
   subscription and storage handle has exactly one lifecycle owner. Owners close
   resources in a defined order, and callers receive one idempotent completion rather
   than relying on shutdown hooks or garbage collection.
4. **Treat retries as part of the protocol.** Classify an operation as idempotent or
   not before defining its timeouts, failover and retries. Request identity, duplicate
   handling, retry limits and terminal rejection are specified at the same boundary as
   the operation.
5. **Expose uncertainty.** A transport failure proves only that the caller received no
   result, not that the command was not committed. APIs and clients distinguish
   definite rejection, safe retry and unknown outcome, so recovery reconciles against
   authoritative state.
6. **Preserve compatibility deliberately.** Protobuf fields are never renumbered,
   snapshots and commands define defaults for new fields, and readers keep old formats
   for a migration window. Fixture-based upgrade tests prove compatibility before a
   format change is accepted. A decision that affects durable identity or wire
   compatibility needs an explicit architecture decision record and those fixtures
   before it is implemented.
7. **Keep tests behavioural.** Tests exercise observable results through real
   implementations, protocol-level fixtures, purpose-built fakes and integration
   clusters, not mocking frameworks. Tests at persistence, transport and packaging
   boundaries verify serialization, failure behaviour, recovery and cleanup as well as
   the successful path.
8. **Keep consensus membership server-only.** Clients never take part in elections,
   quorum membership or log replication. They use only the public control-plane
   protocols, so the client population changes independently of the small, deliberately
   configured server quorum.
9. **Configure from one file, never the environment.** Each process reads one versioned
   JSON document. No production code reads an environment variable for configuration,
   no document interpolates one, and no environment variable locates the document.
   Secrets are mounted files that the document names.
10. **Follow Consul unless there is a reason not to.** Routes, field names, rules and
    defaults follow Consul's where they fit, so its documentation and experience carry
    over. Each departure is deliberate and stated where the rule is (section 16 lists
    them).

## 4. Architecture

### 4.1 Layers and packages

Qraft is one Maven project. The root `pom.xml` builds one executable jar,
`target/qraft.jar`, for both modes, and owns dependency versions, Java 27 compilation,
test conventions and coverage gates. The production code is divided into six layers,
each its own package tree under `dev.mars.qraft`:

| Layer | Package | Responsibility |
|---|---|---|
| Raft | `raft` | Consensus: `RaftNode`, elections, replication, membership (`RaftMembership`, `RaftMembershipPolicy`, `MembershipService`, `ClusterBootstrap`), the transition sequencer, persistence (`RaftPersistence`), the gRPC transport and its TLS, write forwarding (`ForwardCall`), snapshots' configuration envelope, Raft status and events. |
| | `raft.api` | The contracts replicated state implements: `ReplicatedStateMachine`, `ReplicatedCommand`, `CommandCodec`, `SnapshotStore`. |
| | `raft.storage`, `raft.storage.snapshot` | `RaftStorageFactory`, which pairs RaftLog's WAL with Qraft's snapshot store; the durable server ID (`ServerIdentity`); `FileSnapshotStore`. |
| | `raft.metrics` | Raft metrics. |
| | `raft.grpc` (generated) | The Raft protocol and the replicated command messages. |
| Replicated state | `state` | `QraftStateStore`, the deterministic state host; the command types and their protobuf codecs; state metrics. |
| | `state.catalog` | Service instances, their identity, health checks and observations, and the read-only catalog view. |
| | `state.kv` | The key/value store. |
| | `state.session` | Sessions and their commands. |
| | `state.tenancy` | Tenants, namespaces and `ScopedWrite`, the wrapper that checks a write's scope at its place in the log. |
| | `state.acl` | Tokens, policies, roles, auth methods and binding rules. |
| | `state.distributed` | The older distributed metadata map and its legacy codec. |
| Common | `common` | `Node`, `NodeStatus`, `ServiceDefinition`, `QraftVersion`. |
| | `common.acl` | The ACL rule model, identities and the shared authorizer, `AclAuthorizer`. |
| | `common.async` | `RaftStateLoop`, `BlockingTaskExecutor`, `CallerContext`. |
| | `common.concurrent` | `Deadlines`, `Completions`. |
| | `common.config` | Configuration-file discovery, placeholder refusal and JSON setting helpers. |
| | `common.dns` | The DNS codec, resolver and server both modes use. |
| | `common.events` | Event contracts and the bounded `EventJournal`. |
| | `common.http` | `JsonApi`, the request handling both modes' HTTP servers share. |
| | `common.storage` | Durable files and identity files (`server-id`, `node-id`). |
| | `common.tls` | TLS material, its reloading and listener settings. |
| Client | `client` | `QraftClient`, the client data directory and quiescence. |
| | `client.service` | The local API (`LocalApiServer` and its routes), local definitions, the registration store, readiness, heartbeats and client metrics. |
| | `client.catalog` | `CatalogClient`, `HttpCatalogClient`, server endpoints and retry policy, contact tracking, forwarded reads and the reconciler. |
| | `client.health` | Local HTTP, TCP, TTL and maintenance checks and `HealthPublisher`. |
| | `client.config` | Client configuration and service-definition parsing. |
| | `client.acl` | The client's tokens, its ACL resolver and cache, and `ClientAuthorizer`, which decides the local API. |
| | `client.api` | The Java client library: `QraftApi`, `KeyValue`, `Sessions`, `LeaderElection`. |
| Server | `server` | `QraftServerApplication` and `QraftServerService`: composition and lifecycle. |
| | `server.http` | `HttpApiServer` and its route classes, request DTOs, the request context, audit and request metrics. |
| | `server.acl` | Token resolution, token expiry, management-token installation, JWT and Kubernetes login. |
| | `server.health` | Leader-owned check and node expiry. |
| | `server.session` | `SessionTtlExpiry`, the leader's session timers. |
| | `server.recovery` | Offline inspection and lost-quorum recovery. |
| | `server.config` | `AppConfig`, the effective configuration and the security profile. |
| | `server.lifecycle` | `ShutdownCoordinator`. |
| | `server.observability` | Telemetry configuration. |
| | `server.ui` | The embedded administrative interface's assets and handler. |
| Runtime | `runtime` | The entry point, mode selection, the runtime lifecycle, and the `operator raft`, `acl` and `kv` command lines. |

Allowed dependencies, enforced by
`src/test/java/dev/mars/qraft/architecture/PackageDependencyTest.java`:

```text
Runtime -> every layer; nothing else uses Runtime
Server  -> Common, Raft, State
Client  -> Common
State   -> Common, Raft
Raft    -> Common
Common  -> no other Qraft layer
```

- Cycles are prohibited. Raft never uses State, Server, Client or Runtime; replicated
  state never uses Server or Client.
- Every Qraft type belongs to one of the six layers; the test refuses an unclassified
  package. It reads compiled references with the JDK class-file API, and compiled
  mutation fixtures prove that forbidden references are reported.
- Public HTTP and gRPC DTOs stay at adapter boundaries and map explicitly to domain
  commands.
- Generated protobuf classes follow ownership (`raft.grpc`), while the wire
  namespace `qraft.raft` and the service name `qraft.raft.RaftService` stay fixed
  (`ProtocolPackageTest`). The external gRPC `DistributedStateService`, with its
  namespace `qraft.api`, is retired: no client used it, it did not forward to the
  leader, and its writes were not audited.

### 4.2 Runtime and modes

`dev.mars.qraft.runtime` owns the entry point. It takes the mode from the required
subcommand, `server` or `client`; the container image selects the mode through its
command, never through an environment variable. The runtime resolves the configuration
file before dispatch, in this order, and launches the mode through an injected mode
boundary:

1. `--config <path>`;
2. the `qraft.config` JVM system property;
3. `config/server.json` or `config/client.json` in the working directory;
4. `/etc/qraft/server.json` or `/etc/qraft/client.json`.

Both modes return the same runtime-owned lifecycle: its completion, one idempotent
asynchronous close, and the addresses its listeners bound, by name (`http`, `raft`
and `dns` for a server; `http` for a client). The process entry point and its
shutdown hook use that lifecycle directly; no mode starts another application's static
`main`.

Configuration is read and validated before any resource opens. Unknown settings,
duplicate JSON keys, missing files and environment-style placeholders (any `${` in a
string value or field name) are refused, naming the JSON path. Parsing accepts an
injected document for tests; only the executable boundary opens a file. The document is
read once, at startup; only the TLS files it names are read again when they change
(section 11.3). Every setting and its default are in [`docs/CONFIGURATION.md`](../docs/CONFIGURATION.md).

No setting names a version of Qraft. Both modes report their build's project version,
which the build writes into the jar's manifest, log it at startup, and a client carries
it in its node's metadata as `qraft.version`. A process not run from the jar reports
`development`.

```text
Operators, administrative interface
          |
          | HTTP, gRPC, DNS
          v
+---------------- Qraft server cluster ----------------+
|  Server A       Server B       Server C              |
|  HTTP/gRPC      HTTP/gRPC      HTTP/gRPC             |
|       \             |             /                  |
|        +--------- Raft quorum --------+              |
|                    |                                 |
|          replicated state machine                    |
+------------------------------------------------------+
          ^
          | registration, observations, heartbeats, forwarded reads and writes
          |
+---------+-----------+       +-------------------------+
| Client              |       | Client                  |
| node-a              |       | node-b                  |
| local API, DNS      |       | local API, DNS          |
| local checks        |       | local checks            |
| service definitions |       | service definitions     |
+---------+-----------+       +------------+------------+
          ^                                ^
          | localhost                      | localhost
     Applications                     Applications
```

Applications talk only to the client on their own host. Through it they register
services and checks, report TTL status, read and write keys, hold sessions and locks,
and discover other services. Servers are authoritative for committed cluster state;
clients are authoritative only for their local observations, which they submit to the
servers as commands.

### 4.3 Server

`qraft server` validates its configuration, opens durable storage, starts the Raft
transport and node, and starts its public listeners: HTTP (with the administrative
interface) and, when configured, DNS. It takes part in the quorum and applies
committed commands.

Server mode owns:

- Raft consensus, membership, snapshots and recovery (section 8, section 10);
- the replicated state machine: catalog, health, key/value, sessions, tenancy and ACLs
  (section 5);
- leader-only background work: check and node expiry (section 7), session TTLs
  (section 5.6), ACL token expiry and management-token installation (section 11);
- the HTTP API, DNS, metrics and the embedded administrative interface.

A server is live while its process and HTTP listener run; `/health/live` is unaffected
by every other condition. It is ready when it can serve its advertised behaviour:
`/health/ready` answers 200 only when it is not draining, its Raft state can be read,
it is not fenced, recovery has finished, it has not been removed from the
configuration, and it knows a current leader (it leads, or follows one). Otherwise it
answers 503 `not_ready` with a `conditions` list naming every failed condition:
`draining`, `unavailable`, `fenced` or else `recovering`, `removed` and `no_leader`. The
conditions are read as one consistent view on the Raft state loop. A candidate, or a
server cut off from the majority, is unready until it follows a leader again. That holds
for a server that does not campaign, because it holds no configuration yet or is not a
voter: when its election timer fires it forgets the silent leader (section 8.4).
Readiness does not depend on the read consistency a caller asks for (section 9.2).

### 4.4 Client

`qraft client` validates its identity, server URLs and service definitions, then starts
its local API, with liveness and readiness, before it contacts the cluster. It never
opens Raft transport or Raft storage.

Client mode owns:

- a data directory (`client.dataDirectory`), locked by `client.lock` before any thread,
  HTTP client or listener opens and held until shutdown completes, so one directory
  belongs to one client at a time. It holds the node ID (`node-id`), what the local API
  registered (`registrations.json`) and, when token persistence is on, the tokens set
  through the API (`acl-tokens.json`);
- the local API: one JDK HTTP server (`LocalApiServer`) serving liveness, readiness,
  registrations, TTL status, maintenance, membership, leave, metrics, token updates,
  and forwarded discovery, key/value, session and tenancy routes (section 4.5);
- node registration and heartbeats, and the reconciler for its service definitions
  (section 6);
- local HTTP, TCP, TTL and maintenance checks and their publication (section 7);
- DNS on its own port when configured (section 5.3);
- its tokens and its own ACL decisions for the local API (section 11).

The local API listens on `client.bindAddress`, `127.0.0.1` by default as Consul's
`client_addr` is, so it is reached only from the client's host. The setting is an IP
address; a host name is refused rather than looked up. A container deployment whose
applications do not share the client's network namespace sets it, as Consul's
`client_addr` is set for containers.

**Readiness** is derived continuously from five conditions, which
`GET /v1/client/self` reports one by one: the client is running; its node registration
is accepted; every enabled local definition has converged; every required check is
satisfied; and its last successful server response is fresher than
`catalog.contactFreshnessMs`. A required check is unsatisfied while it has produced no
result or its latest result is critical; warning and maintenance keep the client ready,
optional checks never affect readiness, and liveness never depends on checks. So
readiness follows the health of the workloads the client represents, while a server
outage withdraws readiness through contact freshness. A client with no definitions
needs only an accepted node registration and fresh contact. A failed initial
registration keeps the local API live and unready and retries in the background.

**Server selection and retry.** The client holds an ordered, deduplicated list of
server URLs (HTTP or HTTPS origins only). One classified transport,
`HttpCatalogClient`, carries node registration, heartbeats, catalog operations,
observations and forwarded reads. Each attempt has a fresh request ID and the configured
request timeout, and ends in a sealed outcome:

- *success*: a parsed 2xx answer that names what was written and carries its outcome
  field. Anything else in a 2xx answer is the retryable `invalid_response`, so that an
  answer some other HTTP service could give never registers anything;
- *retryable*: a transport failure, a timeout, HTTP 429, 502, 503 or 504, a retryable
  error envelope, or a malformed 5xx body;
- *rejected*: a non-retryable envelope or a malformed 4xx body.

`outcome_unknown` is not retryable on the wire, but the client's own registrations,
heartbeats and observations are idempotent, so the client replays them on it, by the
code (section 9.1).

Retryable outcomes move to the next URL; a rejection stops. The last successful URL is
tried first next time, whether it leads or follows, because a follower forwards writes
to the leader (section 8.5). A returned `leaderId` is kept as a hint. Failed
node-registration cycles back off exponentially with jitter between
`catalog.registrationRetryMinMs` and `catalog.registrationRetryMaxMs`; service
reconciliation stays on the heartbeat cadence and rotates through the list within each
pass. Validation, authorization and conflict refusals are never retried against every
server.

A rejected node or service registration is logged with its code and message while the
client stays live and unready.

### 4.5 API conventions

Both modes' HTTP servers share `common.http.JsonApi`, and every route follows these
rules. The routes themselves are in [`docs/HTTP_API.md`](../docs/HTTP_API.md).

- **Request IDs.** Every request has an ID: its `X-Request-Id`, or a new one. The answer
  carries it back, and it is the envelope's `requestId` and the ID in every log line
  written while the request is handled.
- **Body limit.** A body longer than 512 KiB is refused with 413 `request_too_large`
  before the route runs, whether or not it declares its length, as Consul limits its
  request bodies.
- **Error envelope.** Every refusal uses one envelope: `code`, `message`, `retryable`
  and `requestId`, with route-specific fields beside them. Callers branch on `code` and
  `retryable`, never on the message. A duplicate `error` property holding the code stays
  for one compatibility release and is removed in the first release after 2026-12-31.
  A success-shaped body is never returned for a failure.
- **Unrouted paths.** A path no route serves answers 404 `not_found` in the envelope,
  after its body is read. The JDK server would otherwise answer it as HTML and close
  the connection with the body unread, a reset that can discard the answer.
- **Unknown query parameters are refused.** A route answers 400 `invalid_query`, naming
  the parameter, for a parameter it does not take. Consul's handlers ignore parameters
  they do not know; here an ignored parameter would answer another question than the
  one asked (`?tag=blue` would list every instance). Every catalog, health and
  key/value read takes the read options `stale`, `consistent`, `index` and `wait`
  (section 9.2); the health read also takes `passing`.
- **Names in paths.** In a read route such as `/v1/catalog/service/{name}`, the name is
  everything after the route's prefix, decoded once, as in Consul; it may contain a
  slash.
- **Identity and scope headers.** A request names where it acts with four headers:
  `X-Qraft-Node` (the node, required on node and service writes), `X-Qraft-Node-ID` (the
  node ID, a canonical lower-case UUID, optional), `X-Qraft-Tenant` and
  `X-Qraft-Namespace` (each `default` when absent). A blank scope header is 400
  `invalid_scope`. A token travels in `X-Qraft-Token` or `Authorization: Bearer`.
  The headers state the caller's choice; with ACLs enabled the token decides whether
  it may act there (section 11).
- **Request DTOs.** Registration, observation and heartbeat bodies map explicitly to
  commands. Unknown fields are refused. A body cannot set what the server owns:
  authoritative health, Raft indexes, node status or server-stamped times.
- **Index metadata.** Every read carries the index it answers at in `X-Qraft-Index`: the
  applied index for catalog reads and the key/value state's last index for `/v1/kv`.
  Indexes are monotonic for a committed history and are never wall-clock times.
- **Answering server.** Every answer of a server, a refusal and an unrouted path
  included, names the server that gave it, by node ID, in `X-Qraft-Answered-By`.

## 5. Domain model

### 5.1 Tenants and namespaces

A tenant is the top-level ownership and isolation boundary, Consul's admin partition
under Qraft's name. A namespace is an isolation boundary within a tenant, commonly an
environment, team or application group. Services, checks, keys and the audit records of
requests carry a tenant and namespace; nodes, sessions, ACL tokens and policies have
none.

- **State** (`state.tenancy`): tenants and their namespaces, each with a description
  and the indexes that created and last changed it. The `default` tenant and every
  tenant's `default` namespace always exist and cannot be deleted on their own. Names
  are lower-case DNS labels, and so are the scope headers.
- **Enforcement.** A service registration, a key write or a lock acquisition into a
  tenant or namespace that does not exist is refused 404 `tenant_not_found` or
  `namespace_not_found`. The server checks before it proposes, and the state machine
  checks again at the entry's place in the log through `ScopedWrite`. Only servers that
  know tenancy propose the wrapper, so a log written before tenancy replays to the
  state it had.
- **Deletion.** Deleting a namespace removes its services, their checks and its keys in
  the same replicated step. Deleting a tenant removes its namespaces and everything they
  held.
- **Events.** Each tenancy change produces a domain event in the scope it names,
  identical on every replica.
- **Discovery never crosses scope.** Catalog and health reads answer only the scope
  their headers name. Reads across scopes exist only for administration:
  `/v1/internal/ui/scopes` (what each namespace holds), `/v1/internal/ui/locks` and
  `/v1/internal/ui/audit`, each filtered by the caller's permissions.
- A client's local API acts in the client's configured tenant and namespace when the
  caller names none.

### 5.2 Nodes and node identity

A client is the client-mode process; a node is the stable logical identity it
represents. One node hosts many service instances. A node in the replicated registry
has the shape of a Consul node, with the status and times the servers record:

- `name`, its key in the registry;
- `id`, the node ID: a UUID the client generates at its first start and keeps in
  `node-id` in its data directory, written atomically and forced to disk. A restart
  keeps it; a wiped directory gives a new node; a `node-id` that cannot be read stops
  the start and is never replaced. A node registered by an older client, log entry or
  snapshot may have none;
- `address`, `datacenter`, `region`;
- `metadata`, free-form text, including `qraft.version` and `qraft.registrationId`, the
  identifier of the node's current registration;
- `status`, `registering`, `healthy` or `unreachable`, set only by servers;
- `registrationTime` and `lastHeartbeat`, stamped by the receiving server. The node's
  last contact is its last heartbeat, or its registration time before the first one.

A node has no host name and no port: a port belongs to a service instance. Its name is
`client.nodeName`, the machine's host name by default; a host name alone is not an
identity, which is why the node ID exists.

**Who may hold a name**, by Consul's catalog rules (`ensureNodeTxn` and
`ensureNoNodeWithSimilarNameTxn` in its state store). Each rule is decided when the
registration is applied, from replicated state alone, so every replica decides alike:

- Names conflict regardless of case: `Web-1` and `web-1` are one name. A name is stored
  as given.
- A node holds its name while it is `registering` or `healthy`. A registration under a
  name held by a node of another ID is refused 409 `node_name_reserved`, and so is one
  without an ID under the name of a node with one. A node that has not yet sent its
  first heartbeat keeps its name, which is stricter than Consul, where a node without a
  Serf check can be replaced.
- Once the holder is `unreachable`, or reaped, a new ID takes the name. A takeover
  removes the old node's services and checks in the same replicated step. Consul leaves
  them to the new agent's anti-entropy; Qraft's reconciler also removes what it does
  not want (section 6.2), but only in its own tenant and namespace and not before its
  first pass, so the server removes them.
- An old node that returns after a takeover is another ID asking for a held name, and
  is refused while the new holder holds it.
- A registration with an ID that no node has claims a node without an ID, even one
  holding its name, and keeps its services and checks: it is that machine after an
  upgrade. This is Consul's rule.
- The same ID under a new name, or another spelling of its name, is a rename, as in
  Consul: the node is removed under its old name with its services and checks and
  inserted under the new one in the same step, and the reconciler registers its
  services again on its next pass. A rename into a name another node holds is refused;
  into an unreachable holder's name, it takes the name over.
- A service registration, service deregistration, check observation or node
  deregistration that carries an ID is refused when the node it names is registered
  with another ID, so a node that lost its name cannot change what the new holder
  registered. A heartbeat naming an earlier registration is refused as
  `registration_superseded`.

A client refused with `node_name_reserved` logs it and tries again after its
registration backoff, so a host rebuilt with an empty directory takes its name over
once its old node is unreachable. A client whose registration was superseded learns it
from its next heartbeat and registers again the same way, as Consul's agent keeps
syncing. While its node is not registered a client sends no service write, as Consul's
agent syncs its node before its services.

With ACLs enabled, these rules apply on top of what the caller's token may write
(section 11.2). With ACLs disabled, any caller can claim any node name and ID
(section 17).

### 5.3 Services

A **service definition** is client-owned. It comes from the configuration file's
`catalog.services` array or from the local API, and holds a local service ID, a service
name, an address and port, tags, metadata, an enabled flag and optional checks. It
holds no authoritative health and no Raft index.

A **service instance** is server-owned replicated state derived from a registration
command. Its durable identity is `(tenantId, namespace, nodeId, serviceId)`, so two
nodes can use the same local service ID without overwriting each other; its discovery
grouping key is `(tenantId, namespace, serviceName)`. Re-registering the same identity
is an idempotent update. An instance stores the definition's fields, its datacenter and
region, its current health, and two indexes, as Consul's `CreateIndex` and
`ModifyIndex`: `createIndex`, the entry that registered it, and `modifyIndex`, the entry
that last changed it. A registration that changes nothing but the health it carries
moves neither.

**Declared checks.** A registration may declare the check IDs its client will publish.
Declaring prunes any replicated check not in the list and makes the server refuse later
observations for undeclared checks; an empty list declares none. Omitting the list, as
older clients do, keeps existing checks and accepts any check ID. The client always
declares the checks configured for a service.

**Registration bodies.** One `PUT /v1/catalog/register` carries a node, a service or
both, after Consul's catalog. The whole body is checked before anything is replicated;
when it holds both, the node is committed first, then the service. A service the
catalog then refuses answers 409 `registration_rejected`, the node stays registered,
and the client's retry repeats both idempotently. Deregistration follows Consul's rule:
a body that names no service removes the node itself; one that names a service removes
only that service. The service ID must be a non-blank string, so that a missing value
cannot remove the node instead.

**DNS.** A server and a client each serve DNS when configured, over UDP and TCP, for the
domain `qraft` unless another is named, with the names of Consul's DNS interface:
`[tag.]<service>.service[.<namespace>.ns][.<tenant>.ap].<domain>`, RFC 2782 SRV names,
`<node>.node.<domain>` and `<hex address>.addr.<domain>`. A lookup asks the same reads
as HTTP: a server runs its own health and node reads in memory, under the token in its
DNS token file; a client forwards them to the servers under its default token. So ACLs,
tenancy and readiness mean in DNS what they mean in HTTP. Without `ns` or `ap` labels a
server reads the `default` scope and a client its own. An instance is answered when its
health is passing, warning or unknown and its node is not unreachable; `onlyPassing`
leaves out warnings. Answers are shuffled. A name in the domain that names nothing is
NXDOMAIN; a name outside it is REFUSED, because there are no recursors; a read the token
may not make is REFUSED; a failed read is SERVFAIL. A UDP answer fits 512 bytes, or the
query's EDNS size up to 4,096, and is marked truncated when records are left out.
Prepared queries, recursors, a DNS token kind and other datacenters are not offered.

### 5.4 Health

Health is server-owned state derived from observations that clients report. The states
are:

- `UNKNOWN`: no observation has been accepted;
- `PASSING`: every required check passes;
- `WARNING`: degraded but discoverable;
- `CRITICAL`: a required check fails or has expired;
- `MAINTENANCE`: explicitly taken out of passing discovery.

An instance's health is the deterministic aggregate of its checks. Health changes are
separate Raft commands; a registration cannot claim a health result, and a stored
registration always starts `UNKNOWN`. `FAILING` remains only so an older snapshot loads,
and is counted as `CRITICAL`; no observation can carry it. Section 7 describes how
health is observed and expired.

### 5.5 Key/value

Key/value entries follow Consul's KV store:

- **State** (`state.kv.KvState`): entries by tenant, namespace and key, each with an
  opaque value, 64-bit flags, a creation and a modification index (the Raft indexes of
  the commands that wrote it), and for a lock its session and `LockIndex`. The store
  takes the entry being applied at the last applied index plus one, since `RaftNode`
  applies entries in order.
- **Writes** are Raft commands. A set or delete with a compare-and-set index applies
  only at the entry's modification index, or for 0 only to an absent key; deleting an
  absent key succeeds. Acquire and release are conditional writes (section 5.6).
- **Reads** list prefixes deterministically, take `recurse`, `keys`, `separator` and
  `raw`, and answer in Consul's field names. A blocking read waits for the key/value
  state's index to pass the caller's (section 9.2).
- **ACLs**: `key` and `key_prefix` rules, scoped as `service` rules are. A read needs
  `key:read` and a recursive read leaves out what the token cannot read; a write needs
  `key:write`; a recursive delete needs write on every key under the prefix, decided as
  Consul's `KeyWritePrefix` decides it. There is no `list` access.
- `qraft kv get`, `put` and `delete` drive it from the command line, with Consul's
  messages.
- The older replicated metadata map is separate from `/v1/kv`. The gRPC
  `DistributedStateService` that served it is retired, so nothing outside the state
  machine reads or writes it; stored log entries and snapshots that hold it still
  decode.

### 5.6 Sessions and locks

Sessions follow Consul's:

- **State** (`state.session`): sessions by ID, each bound to a registered node, with a
  name, a behaviour (`release` or `delete`), an optional TTL, a lock delay and the index
  that created it.
- **What is replicated.** Creating and destroying a session are Raft commands, and so
  is a lapse, which is a destroy stamped with the leader's time and the cause `ttl`.
  Every replica therefore orders a lapse against every other write alike.
- **Renewal is not replicated**, as in Consul's `session_ttl.go`. The leader keeps the
  TTL timers in memory (`server.session.SessionTtlExpiry`): a session lapses twice its
  TTL after the later of its creation, its last renewal and the start of this
  leadership. A follower that receives a renewal forwards it to the leader like any
  write (section 8.5). A new leader gives every session twice its TTL again, so a
  failover never ends a session early.
- **Invalidation.** A session ends when it is destroyed, when it lapses, or when its node
  becomes unreachable, is reaped, deregistered or replaced. Its keys are then released
  or deleted by its behaviour, and none can be acquired again until its lock delay,
  counted from the invalidation's time, has passed. The times come from the proposer,
  so every replica keeps the same delay. The destroy carries its cause (`destroyed`,
  `ttl` or `node`) so that events and metrics record why.
- **Locks.** `acquire={session}` sets a key and its session when no other session holds
  it and its lock delay has passed; a new holder increments `LockIndex`.
  `release={session}` frees a key only for the session that holds it.
- **ACLs**: `session` and `session_prefix` rules name a node. Creating, renewing or
  destroying needs `session:write` on the node, reading needs `session:read`, and
  acquiring or releasing needs `key:write`.
- The server requires a session's node; a client's local API binds a session without
  one to the client's node.

Leader election is library behaviour built on sessions and locks, not a second
consensus mechanism. The Java client library, `dev.mars.qraft.client.api`, is Consul's
Go `api` package for Java applications: `QraftApi` (an address, a token and a scope;
synchronous calls; a refusal throws `QraftApiException` with the status, code and
message), `KeyValue`, `Sessions`, and `LeaderElection`, which holds a contender's
session, acquires the key, blocks on it while another holds it, renews every half TTL
while it leads, confirms with consistent reads that it still holds the key, and tells a
listener when leadership is gained and lost. A lost renewal, a key no longer held, or a
failed call while leading ends leadership, and it campaigns again with a new session.
The recipe without the library is in
[`docs/OPERATIONS.md`](../docs/OPERATIONS.md#sessions-and-locks).

## 6. Registration, reconciliation and anti-entropy

### 6.1 Registration flow

1. The client loads and validates its service definitions.
2. The local API becomes live and reports not ready.
3. The client registers its node, then keeps it with heartbeats every
   `client.heartbeatIntervalMs`.
4. Once the node is accepted, the client starts the checks of enabled services and
   submits each enabled definition as an idempotent registration.
5. The receiving server validates the request and proposes a command, forwarding it to
   the leader when it follows.
6. Success is returned only after the command is committed and applied.
7. The client becomes ready once every enabled definition has converged, its required
   checks are satisfied and server contact is fresh.

A partial registration is kept. The client retries only missing or changed
definitions and never rolls back what committed.

### 6.2 Reconciliation and anti-entropy

The client reconciles continuously rather than relying on one startup request, so the
catalog is repaired after administrative deletion, interrupted communication or a crash.

- **One source.** `LocalDefinitions` combines the configuration file's definitions,
  read once at startup, with what the local API registers while the client runs. A
  change runs a pass at once, as Consul's agent triggers a sync; a pass already running
  read the old definitions, so one more runs after it.
- **Single flight.** One pass runs at a time, on the client's lifecycle-owned scheduler,
  at the heartbeat cadence.
- **Minimal writes.** A stable content fingerprint prevents a write when a definition has
  not changed. An unchanged definition is verified by a catalog read, filtered by its
  complete tenant, namespace, node and service identity; a missing instance is
  registered again; a changed one is resubmitted; an unchanged refused definition is
  reported without being retried every pass. A service refused with
  `node_name_reserved` is sent again on the next pass, not suppressed as refused.
- **Removal of what only the catalog knows**, as Consul's anti-entropy does. A pass reads
  the node's services (`GET /v1/catalog/node-services/{nodeName}`) and deregisters any
  in the client's tenant and namespace that its source does not want, whether or not
  this process registered it: a definition removed while the client was down, a
  deregistration accepted just before a crash, or a service something else registered
  on the node. A service in another scope is left alone. When no server can answer, the
  pass goes on with what the process remembers. Nothing is read while the node is not
  registered.
- A sync refused for its token is a warning, counted as synced, and tried again on the
  next pass.

### 6.3 Registrations through the local API

The local API registers services and checks after Consul's agent API
(`agent/agent_endpoint.go`). A write answers once the change is kept and has taken
effect locally: its checks run and readiness counts them. The servers learn of it from
the reconciler, as Consul's agent syncs after it answers. A check's first observation
can therefore reach a server before its service; the client retries it.

- **IDs.** A service ID defaults to its name and its address to the client's, as in
  Consul. A check ID is unique across the client, so registering it for another service
  moves it. At run time the latest registration or deregistration wins, including for a
  service the configuration file defines.
- **Persistence.** What the API registered is kept in `registrations.json`, replaced
  atomically and readable only by the client's user, and restored at startup. At
  startup the file's definitions take precedence: a kept registration of the same ID is
  purged, and so is a kept check whose service is gone, so a file-defined service that
  the API deregistered is back after a restart. A `registrations.json` that cannot be
  read stops the client and is never replaced, where Consul logs it and continues. A
  change that cannot be kept is logged as an ERROR with its cause, answers 500
  `registration_not_kept`, retryable, and changes nothing.
- **TTL status.** An application reports a TTL check's status through `pass`, `warn`,
  `fail` or `update`. A status is not kept across a restart, where Consul keeps it until
  it would have expired.
- **Maintenance** takes a service, or every service of the node, out of passing
  discovery while it stays registered. As in Consul it is a check of its own,
  `_service_maintenance:{serviceId}` or `_node_maintenance:{serviceId}`, kept across a
  restart; enabling it again keeps the first reason, and applications may not use those
  IDs. The service declares the check, which reports `maintenance` with the reason as
  its output every third of its 60-second TTL, because a service registered again comes
  back without its checks' state. Unlike Consul's critical maintenance check it is not
  required, so it never holds the client's readiness back. Disabling it ends the
  declaration and the server prunes the check; deregistering the service ends its
  maintenance.
- **Writes during shutdown.** Once shutdown begins, before anything is deregistered, a
  local API write is refused 503 `shutting_down`, retryable, so nothing is registered
  behind the deregistration. Reads, liveness and readiness are served until the listener
  stops, last. Consul's agent does not refuse writes while it leaves; this is stricter.

**Forwarded reads.** The local API forwards the catalog and health reads, key/value,
session and tenancy routes to the servers, as Consul's agent serves them, so an
application needs only its local client. Each reaches the servers with the caller's
path and query, so what a request means is decided in one place. Without scope headers,
a forwarded request uses the client's own tenant and namespace, where its API
registers; a request straight to a server uses `default`. A forwarded request goes
under the caller's token, else the client's default token. A busy or unreachable server
is passed over for the next; with no answer at all the client answers 503
`no_server_reachable`, never older data. A key/value or session write moves to the next
server only when it cannot have been applied; one sent and never answered is answered 503
`outcome_unknown`, not retryable, and no other server is asked. Applications register services through
`/v1/client/*`; the local API does not forward catalog writes, which Consul's agent
does.

**Members.** `GET /v1/client/members` is a forwarded read of the server's node list,
because Qraft has no gossip pool. Consul's `/v1/agent/members` lists its gossip pool,
and Consul itself points to `/v1/catalog/nodes` for a consistent list. So there is no
answer without a server, only clients are listed (servers have no node entry), and a
node that left is gone where Consul shows it as `left`. Consul's `wan` and `segment`
parameters are not carried over.

### 6.4 Deregistration and leave

Deregistration is node-scoped and idempotent: removing an absent instance is a
successful no-op. A node's deregistration, as in Consul, removes its entry and every
service and check registered on it in every tenant and namespace, in one replicated
step, including services registered under its name when it has no entry.

During graceful shutdown the client:

1. withdraws readiness;
2. stops local checks and publications, waits for publications in flight, and stops
   new reconciliation, waiting for a pass in progress;
3. deregisters every service known to have committed, within its bound;
4. prevents new node registrations, waits for one in flight, and deregisters its node,
   which removes anything a failed service attempt left behind;
5. stops the local API;
6. closes its scheduler and HTTP resources.

Repeated and concurrent shutdown calls share one completion, bounded by
`client.shutdownTimeoutMs`. When the deadline passes, outstanding HTTP work is cancelled
and incomplete cleanup is reported once; shutdown never delays process exit
indefinitely. Automatic expiry (section 7) still handles a client that cannot leave.

`PUT /v1/client/leave` runs the same sequence, answering between steps 4 and 5 with
whether the client left the catalog, then finishes shutting down; the process exits
with code 0. A Consul client does the same: its `Client.Leave` logs a failed leave and
returns no error. A client that could not deregister is treated as one that crashed: its
checks expire, its node becomes unreachable after `nodeTtlMs`, and the catalog keeps
both until `nodeReapAfterMs`. Consul treats a leave as a permanent intent and does not
rejoin without an explicit join or `rejoin_after_leave`; a Qraft client always has its
server URLs, so its next start registers it again.

## 7. Health and failure detection

### 7.1 Client checks

Clients run checks close to the workload. Each check runs independently on the client's
scheduler, and its next attempt starts only after the previous one completes or times
out, so a check never overlaps itself. A timeout cancels the request or connection and
records a critical result. Output is limited to 4,096 characters, matching the server.
Local results never change server state directly; they are published as observations.

- **`http`** sends a GET to an absolute HTTP or HTTPS URL without user information. A
  2xx answer is passing, 429 is warning, anything else or a transport failure is
  critical. The body is discarded.
- **`tcp`** opens one connection to the check's address and port, by default the
  service's. A connection is passing, a failure critical. With `warnAfterMs`, a
  connection that takes at least that long is a warning; the threshold must be shorter
  than the timeout.
- **`ttl`** has no probe. The application reports its status through the local API; if
  no report arrives within the TTL, the client records a critical result.
- **maintenance**: the client's own check (section 6.3).

For `http` and `tcp`, the timeout must not exceed the interval and the TTL must exceed
it. The TTL is the server-side time to live of each published observation. A check is
required unless it says otherwise. Any check may set `deregisterAfterMs`, how long it
may stay expired before its service is deregistered automatically; zero means never.
Defaults are in [`docs/CONFIGURATION.md`](../docs/CONFIGURATION.md).

### 7.2 Observations

`HealthPublisher` publishes each check's results as sequenced observations, at most one
in flight per check, coalescing results that arrive meanwhile to the latest. A changed
status or output is published immediately; an unchanged result is published again as a
renewal once half the check's TTL has passed since its last acceptance. A renewal is
simply a newer observation of the same check.

- **Sequences** are strictly increasing per check and start from the wall clock in
  milliseconds, so a restarted client normally exceeds what its predecessor left. The
  state machine accepts only a strictly newer sequence.
- **Server time decides expiry.** The receiving server stamps the command with its own
  receipt time, and the deadline is that time plus the TTL. The client's clock never
  determines expiry, so client clock skew cannot keep a registration alive. An
  observation's `observedAt` is kept for display.
- **Replay is safe.** An exact replay of the accepted observation answers as the
  original did, with its deadline, so a retry after an unknown outcome is safe. An older
  sequence, or the same sequence with different content, is 409 `stale_observation`
  with the current sequence, and the client raises its floor to it and publishes again.
- **Not yet registered.** An observation for an instance that is not registered is 404
  `service_not_found`, and for an undeclared check 404 `check_not_declared`. The client
  retries both after the registration backoff, because its own re-registration may not
  have committed yet; the server treats the retry as an idempotent replay. Other
  refusals are logged and dropped.

### 7.3 Leader-owned check expiry

Expiry is deterministic: the leader evaluates deadlines and proposes explicit expiry
commands, and followers change catalog state only by applying committed entries, never
from local timers.

`server.health.LeaderHealthExpiry` runs only while its server leads. It evaluates on
gaining leadership, then at the sooner of `server.health.expiryIntervalMs` and the next
due instant. Losing leadership cancels evaluation and forgets proposals in flight, and
completions from an earlier term are ignored. A due command is not proposed again while
it is in flight, and each proposal times out after five seconds so a lost proposal is
evaluated again. The side-effect-free `HealthExpiryEvaluator` applies these rules:

- **Grace after failover.** Clients cannot renew while there is no leader, and servers'
  clocks may differ. A check is due only at the later of its stored deadline and the
  current leader's acquisition time plus the check's TTL, so a failover never
  mass-expires checks, and a leader whose clock runs ahead still waits a full TTL.
- **Exact identity.** Every command names the stored sequence and deadline. A renewal
  committed first turns the expiry into a no-op; an expiry committed first is superseded
  by the renewal.
- **Two phases.** An unrenewed check is first marked expired, which makes a required
  check critical. If its latest observation carries a positive deregistration delay, the
  leader deregisters the instance that long after the effective deadline. The state
  machine accepts a deregistering command only for a check that is already expired.
- **Saturation.** A deadline beyond the representable time range never falls due.

Deregistration removes the instance, its checks and its declared check set. A client
that is still alive registers the instance again through reconciliation.

### 7.4 Node membership expiry

Qraft has no gossip; a node's liveness is its heartbeats. The same leader-only component
expires silent nodes under a server-wide policy, `server.health.nodeTtlMs` and
`server.health.nodeReapAfterMs` (Consul's 72-hour reconnect window by default; zero never
reaps). Registration and heartbeat times are stamped by the receiving server, and all
replicated times are stored in milliseconds, so leader and followers hold identical
values that expiry can match exactly.

- A node is due at the later of `lastContact + nodeTtlMs` and `leaderSince + nodeTtlMs`,
  the same failover grace as for checks.
- The first phase marks it `unreachable`; a later heartbeat makes it `healthy` again.
- With a positive reap delay, an unreachable node is reaped that long after its
  effective deadline. Reaping removes the node and every service instance on it, in
  every tenant and namespace, with their checks and declared check sets, so a crashed
  client's services go even when they have no checks.
- Both phases name the stored last contact, so a heartbeat or re-registration committed
  first makes a stale command a no-op. Reaping applies only to an unreachable node. A
  node command differing only in its proposal time is the same expiry in flight and is
  not proposed twice.
- A node becoming unreachable, or being reaped, invalidates its sessions (section 5.6).

### 7.5 Health in discovery

- The health read returns every instance of a service in deterministic identity order,
  whatever its health, each with its checks in check-ID order and its node's status.
- `?passing` keeps only instances whose aggregate health is `PASSING` and whose node is
  not `unreachable`. A check keeps its last result until its TTL lapses, and the TTL has
  no upper limit, so its check alone could keep a service passing long after its node
  stopped. Consul excludes such a service because the failed node's critical
  `serfHealth` check counts among its checks; Qraft decides the same at the read, from
  the replicated node status, so every server answers alike. Check results and stored
  health do not change, and a returning heartbeat restores the instance with no new
  observation. An instance whose node has no entry is decided by its checks.
- So a client cut off from the servers has its services leave `?passing` after
  `nodeTtlMs`, though they may still run, as Consul's failure detector does; the grace
  after failover stops a change of leader from dropping them.
- The catalog's service read returns bare registrations, so it lists an unreachable
  node's instance until the node is reaped.
- DNS answers passing, warning and unknown instances of reachable nodes, as Consul's DNS
  does, unless `onlyPassing` is set.
- Health reads never change the catalog.

## 8. Raft

Raft is implemented in `dev.mars.qraft.raft`. All state transitions run on one platform
thread, the `RaftStateLoop`, through the `RaftTransitionSequencer`.

### 8.1 Server identity

Each server has a durable **server ID**, a UUID generated at its first start and kept in
`server-id` in its Raft data directory, written atomically and made durable while the
WAL holds the directory's lock.

- A restart on the same storage keeps the ID; a wiped directory gives a new one; a data
  directory from before server IDs gets one at its next start.
- A file that exists but cannot be read stops the start and is never replaced.
- Every Raft request and response names its sender's server ID. An append or snapshot
  install also names its target's, and a server refuses one naming another server ID
  before its term, log or snapshot changes. A server that lost its storage answers at its
  old address under a new ID, so it cannot install a snapshot meant for the entry it
  replaced, which would leave it holding a configuration without itself.
- `/raft/status` and the startup log report it.

### 8.2 Bootstrap and joining

A server with no Raft state asks every server in `server.raft.nodes` to describe itself
over the Raft port, then decides:

- **Bootstrap** when every listed server answers, none holds Raft state, and all list
  exactly the same servers. It writes the configuration at index 1, in term 0, treated as
  committed. The configuration is built from each server's own answer, so every server
  that bootstraps writes the identical entry. A sole member bootstraps itself.
- **Join** when a listed server already holds state (below).
- **Retry** every second while some listed server has not answered, until it holds a
  configuration.
- **Never bootstrap** when the lists differ.

**Joining.** A server with no Raft state that finds a cluster asks the listed servers in
turn to add it, as Consul's `retry_join` tries every address. It stops at the first that
answers for a leader, passing over one that cannot be reached or knows no leader, and
asks again every second until the leader has replicated the configuration to it. Any
server forwards the request to the leader over the Raft port. The leader adds the server
as a non-voter and promotes it (section 8.3). There is no operator command to add a
server, as in Consul.

**Rejoining after lost storage.** A server that lost its storage returns under a new
server ID. While another server ID holds its name or address, admission is refused until
an operator removes that entry. A join alone never evicts another server ID.

**Moving with its storage.** A server that returns under its server ID and name at a new
address has its configured address updated and keeps its vote, as Consul's `AddServer`
does. A request under another name, or for an address another server holds, is refused.
Startup retries joining when the recovered configuration records an old self-address,
until replication confirms the advertised address.

**Addresses.** Once a server holds Raft state, its membership comes from the replicated
log; `server.raft.nodes` no longer changes it. The transport reaches each configured
server at the address in the configuration, which overrides the setting, because a
joined server is in no server's list and a rejoined one may have a new address.

The procedures for replacing a server and for recovery are in
[`docs/OPERATIONS.md`](../docs/OPERATIONS.md#replace-a-server-that-lost-its-storage).

### 8.3 Membership

Raft membership is recorded in the replicated log, as in Consul.

- **The configuration** lists each voter and non-voter with its server ID, name and
  address. Each server uses the latest configuration in its log, committed or not.
  Truncating an uncommitted configuration reverts to the one before. A snapshot records
  the configuration it covers in an envelope around the state machine's snapshot.
- **Log invariants.** Index 1 always holds the bootstrap configuration, in term 0. A
  follower refuses an append that would replace a committed entry. Every new leader
  appends a no-op in its term, so an idle cluster can commit an entry in that term
  before changing membership.
- **No upgrade from before configurations.** A data directory that holds Raft state but
  no configuration refuses to start.
- **Counting.** Elections, commits and check-quorum count only voters, by server ID. A
  reply counts, and is listened to at all (its term included), only when the server ID
  it names is the one the configuration records for that peer. A server that lost its
  storage therefore cannot vote or be counted for the server it replaced.
- **Campaigning.** A server that is not a voter in its configuration never campaigns.
- **Changes** are made one server at a time. A leader may start a change only after it
  has committed an entry in its current term, and only when no other change is
  uncommitted. Every configuration entry, however submitted, meets these rules. A server
  is added as a non-voter; a change that adds a voter directly is refused.
- **Promotion**, as Consul's autopilot does it. A non-voter is replicated to but not
  counted. At each heartbeat round the leader judges a non-voter healthy when it has
  answered this leader within the last two heartbeat rounds (only replies to the current
  leadership are recorded, so its term matches; Consul's `LastContactThreshold` is
  200 ms) and its log trails the leader's by at most
  `server.raft.membership.promotionMaxTrailingEntries` (Consul's `MaxTrailingLogs`).
  Once it has been healthy for `server.raft.membership.promotionStabilizationMs`
  (Consul's `ServerStabilizationTime`, counted in heartbeat rounds), the leader proposes
  it as a voter. A lapse in health restarts the period. One server is promoted at a
  time; a promotion refused, for example while another change is uncommitted, is tried
  at the next round. A new leader starts every period afresh.
- **Removal.** An operator removes a server by ID or name, through
  `DELETE /v1/operator/raft/peer` or `qraft operator raft remove-peer`. Any server takes
  the request and forwards it to the leader over the Raft port with its token, which
  the leader checks again in its own state. Removing a voter is refused unless the
  voters left, among those the leader has heard from within its check-quorum window,
  are a quorum; a non-voter can always be removed. A leader that removes itself steps
  down once the removal commits and never campaigns again; the rest elect a leader. It
  keeps running, reports `removed` in `/raft/status` and readiness, and refuses writes as
  a follower does, so it can be stopped. A follower removed by another leader is no
  longer replicated to and may never learn of its removal; it keeps asking for pre-votes,
  harmlessly (section 8.4), and should be stopped.
- **Automatic failed-voter cleanup**, as Consul's autopilot `CleanupDeadServers`. The
  leader tracks each voter's absence in applied heartbeat rounds, never wall-clock time,
  so a delayed state loop never invents missed rounds. A new leadership starts a fresh
  grace, and a valid reply resets absence by server ID. A cleanup pass freezes its
  committed voter count, its eligible server IDs in sorted order, and a budget of
  `floor((N - 1) / 2)` removals. Before each proposal it checks again the failure, the
  configured minimum number of voters, recent contact with both the current and the
  proposed quorum, the current-term commit and the single-change rule, and it waits for
  each removal to commit before the next. A candidate that recovers is skipped; an
  unrelated membership change, a failed proposal or a leadership change abandons the
  pass. Failure tracking and pass state are the leader's alone and never persisted, so a
  leadership change restarts the grace. A committed promotion can trigger cleanup after a
  replacement joins with a distinct name and address; a replacement reusing either needs
  the old entry removed first. Its settings are under `server.raft.membership.cleanup`,
  and its operation is in
  [`docs/OPERATIONS.md`](../docs/OPERATIONS.md#automatic-failed-voter-cleanup).
- **The Raft port** without TLS is trusted: anyone who can reach it can join a server
  that is later promoted, as they can already send Raft messages, so it belongs on a
  private network. With `server.tls.raft` only a peer with a certificate from the
  cluster's CA takes part (section 11.3).

### 8.4 Elections: Pre-Vote, stickiness and check-quorum

**Pre-Vote.** An election timeout does not raise a term. A voter with at least one other
voter first sends each a `PreVote` request for the next term and starts the real election
only when a quorum, itself included, would vote for it. The rules follow the Raft thesis
(section 9.6) and etcd:

- **A receiver changes nothing.** It records no vote, adopts no term and does not count
  the sender as a leader.
- **A receiver says yes only if** it is running; the sender is a voter in its
  configuration (a server with no configuration yet cannot check, and answers by the
  other rules, or a first server that bootstrapped alone could never be elected); it is
  not a leader and has heard from none within an election timeout (the same test the
  real vote uses); the proposed term is strictly ahead of its own; and the sender's log is
  at least as up to date as its own.
- **A round forgets a silent leader.** A follower whose election timeout fires has not
  heard its leader for a whole timeout, so it stops reporting that leader, and its
  readiness fails, as it would on becoming a candidate. A heartbeat restores it. Without
  this a cut-off follower would keep claiming readiness.
- **A round ends on a no, on silence, or on a heartbeat.** The election timer is armed
  again when the round starts and its generation is checked when an answer arrives, so
  a heartbeat meanwhile cancels the round.
- **A later term in an answer is adopted**, as for a vote: the asking server persists it,
  becomes a follower in it, and asks beyond it next time. Such an answer is always a
  refusal from a server already in a later term, and only a configured server's answer
  counts. `hashicorp/raft`'s `runCandidate` and etcd do the same. Without it, two servers
  can deadlock with no leader: one behind in term with the longer log, the other ahead in
  term with the shorter, each refusing the other's pre-vote. Unlike `hashicorp/raft`, a
  pre-vote is granted only for a term strictly ahead of the receiver's.
- **A sole voter skips the round**, as it skips voting.
- **Compatibility.** `RaftTransport.sendPreVote` is a default that fails as unsupported.
  `GrpcRaftTransport` turns a peer's `UNIMPLEMENTED` into that failure, and the asking
  server counts such a peer as willing, so a cluster of old and new servers still elects.
  Any other failure is not a yes.
- **A request from a peer ends its reconnect backoff.** gRPC waits between attempts to an
  unreachable peer, up to 120 seconds, so a follower that returned after a long stop could
  go unheard by the leader for most of that time. `RaftNode` reports every vote and
  pre-vote request from a configured server to `RaftTransport.peerHeardFrom`, and
  `GrpcRaftTransport` resets its channel's connect backoff to that peer. Resetting on
  proof of life keeps gRPC's backoff for peers that are really down.

**Leader stickiness.** A leader ignores campaigns while check-quorum allows it to lead. A
follower ignores vote requests, including higher terms, for the minimum election timeout
after leader contact, measured by a monotonic clock. Pre-Vote does not stop a removed
server from disrupting a cluster (thesis section 4.2.3); stickiness does.

**Check-quorum.** A leader steps down in its current term, failing uncommitted writes,
when a majority including itself has not acknowledged any request for an election
timeout's worth of heartbeat rounds. Counting applied rounds rather than wall-clock time
means a delayed state loop cannot cause a false step-down. A single-node cluster never
steps down.

**Changing a transition.** A change that removes or alters a state transition first lists
the old transition's observable effects (term, role, known leader, timers, readiness),
marks each removed or kept, and has a test for every kept effect before the code changes.
Pre-Vote's removal of the candidate transition from the election timeout also removed its
clearing of the known leader, which is why a round now forgets a silent leader.

**Other transport rules.** A refused transition at shutdown, from a draining sequencer,
is expected and logged at debug by `TransitionFailureLog`, which every handler of a
sequenced failure uses. Snapshot sends to an unreachable lagging peer back off from
250 ms to 5 seconds, doubling per failure; a delivered snapshot or a new leadership
starts again.

### 8.5 Write forwarding

As Consul's servers do, a follower that knows the leader forwards every write (`PUT`,
`POST`, `DELETE` and `PATCH`) to it with the Raft port's `Forward` call, which carries the
method, path, query, headers and body, under the port's mutual TLS when it is on.

- The leader answers on its own routes, with its ACLs, tenancy, audit and limits. The
  follower answers with that answer and `X-Qraft-Forwarded-To`, once it has applied the
  leader's index, so its own reads see the write.
- A forwarded write carries `X-Qraft-Forwarded-By` and is never forwarded again: one that
  reaches a server that does not lead is answered 503 `leader_unavailable`.
- A write is sent only on a ready connection, waited for at most two seconds. One never
  sent is 503 `leader_unavailable` with the leader hint, since it was not applied; one
  sent and not answered is 503 `outcome_unknown`.
- A follower that stops following that leader while it waits, by hearing nothing from it
  for an election timeout or by learning of another, answers at once instead of at the
  end of its wait (twice the Raft timeout). A leader cut off by the network can take the
  write on a connection that still looks ready and never answer, and a caller with a
  check to renew needs to try another server in time. A `raft.ForwardCall` is either sent
  or withdrawn, never both: a call still waiting for its connection is withdrawn and
  answered `leader_unavailable`; one already sent is answered `outcome_unknown`.
- A draining server forwards nothing. Reads are not forwarded (section 9.2), nor are the
  `POST` routes that only read: `/v1/internal/acl/resolve`, `/v1/internal/acl/authorize`
  and `/v1/acl/explain` are answered by the server asked.

## 9. Consistency and replica determinism

### 9.1 Writes

Every write is acknowledged after commit and state-machine application. A write's
failure is one of: a definite refusal (a 4xx envelope, `retryable: false`); a retryable
refusal known not to have been applied (`leader_unavailable`, `draining`); or
`outcome_unknown`, which may have been applied and so is answered `retryable: false`. A
caller recovers from an unknown outcome by reading authoritative state or by replaying an
idempotent request; observations and registrations are designed so that a replay is
safe.

### 9.2 Read modes

Every catalog, health and key/value read takes the same options:

- **Default and `?stale`** answer from the answering server's applied state. Consul's
  default reads go to the leader through server-side forwarding; Qraft does not forward
  reads, and making the default leader-only would refuse every read a follower answers.
  So the default is a stale read with the known-leader headers below, a difference from
  Consul.
- **`?consistent`** is answered by the leader once `RaftNode.readBarrier()` passes:
  Raft's ReadIndex. It records the commit index, or the first index of the leader's term
  if that is later, sends a heartbeat round, and completes once a quorum has answered a
  request sent after it began and that index is applied. Each AppendEntries request
  carries the sequence it was sent at, so a late answer to an earlier request confirms
  nothing. Another server answers 503 `leader_unavailable` with the leader hint, and a
  client tries its next server. `stale` and `consistent` together are refused.
- **Headers.** Every read carries `X-Qraft-Known-Leader`, `X-Qraft-Last-Contact` (the
  milliseconds since the answering server heard from the leader, 0 on the leader) and
  `X-Qraft-Consistency`, the mode applied. A refused read carries none of them.
- **Blocking queries.** `?index=N&wait=D` waits until the read's index passes `N`, or `D`
  passes with up to a sixteenth more at random, as in Consul. `wait` is five minutes by
  default and at most ten. The client forwards a blocking read with its wait added to its
  request timeout. A wait ends early when the server stops, answered 503 `draining`.

Operator reads of a server's own Raft view (`/raft/status`, the Raft configuration and
membership, storage) answer that server's state; the leader alone answers the autopilot
health read, as only it knows its followers' progress.

### 9.3 Ordering

Deterministic ordering is mandatory for service lists, instances, tags, checks, node
lists, prefix results and snapshot serialization.

### 9.4 Replica determinism

Replicated state depends only on the committed log:

- **Apply what is logged.** A leader encodes each command once and appends and applies
  the command decoded from those bytes, not the object it received, so any normalization
  in the codec is identical on the leader, its followers and after WAL replay.
- **Replicated bytes are authoritative.** Every log entry keeps the exact bytes that were
  replicated. Persistence, sending to followers and comparing a retransmitted entry with
  an existing one all use those bytes, never a re-encoding. Protobuf map encoding
  follows Java map iteration order, which differs between JVMs, so a re-encoding could
  make a duplicate entry look like a divergent log and fence a healthy follower.
- **No clock or randomness while decoding or applying.** Times in commands are chosen
  once, by the proposing server, and stored in milliseconds. An entry without a time
  decodes to the epoch, not to the replaying server's clock.
- **Clients do not own server state.** Registration status, registration time, heartbeat
  time and observation receipt time come from the receiving server. The node routes
  refuse a body that carries them, and the state machine sets them whatever a command
  holds.
- **Lossless encoding.** Nullable strings use protobuf `optional` fields, so null and the
  empty string stay distinct. Null and empty lists are the same.
- **Removed formats stay readable.** Fields a node no longer has are `reserved` in the
  protocol and ignored in a snapshot. A removed node status reads as `healthy` or
  `unreachable`, by whether the node was in contact. An entry holding the removed
  capabilities update applies nothing. Fixtures written by earlier code prove each case.
- **No shared mutable state.** A node is an immutable value, and the state machine copies
  what a command's collections hold. Readers receive those values and a read-only
  catalog view, so nothing outside a committed command can change replicated state.
- **Reproducible snapshots.** Snapshot JSON writes map entries in key order, so replicas
  and JVMs produce identical bytes for identical state.
- **Decisions in the state machine.** Name ownership (section 5.2), scope existence
  (section 5.1), ACL refusals (section 11.2), compare-and-set, session ownership,
  node-scoped identity and observation ordering are decided where the command is
  applied, from replicated state, so every replica decides alike. Metrics of applied
  state are counted there too.

## 10. Persistence and compatibility

### 10.1 Storage boundaries

Qraft uses RaftLog as a write-ahead log, not as an application database. The WAL stores
only the current term and voted-for identity, indexed and termed log entries with opaque
payloads, and suffix-truncation records that replace a conflicting suffix. RaftLog does
not interpret commands, answer queries, run the state machine or own snapshots; Qraft
owns command serialization, materialized state, snapshot contents and transfer, and the
relationship between a snapshot boundary and the log.

Production pairs RaftLog's `dev.mars.raftlog.storage.RaftStorage` with Qraft's
`dev.mars.qraft.raft.api.SnapshotStore` in `RaftStorageFactory.DurableStorage`:

```java
public record DurableStorage(dev.mars.raftlog.storage.RaftStorage wal,
                             SnapshotStore snapshots) { }
```

`RaftStorageFactory.createDurable` returns `CompletableFuture<DurableStorage>`. Both
storage contracts expose standard Java futures; `Completions` handles failures and
unwraps `CompletionException` without converting futures into another type.
`RaftPersistence` passes WAL operations, prefix compaction included, to RaftLog's storage
unchanged; there is no semantic WAL adapter.

The snapshot store may share a node's data directory with the WAL, under a separate
ownership and durability contract; so does `server-id`. Snapshot data is an envelope: the
cluster configuration at the snapshot's last included index, then the state machine's
snapshot. It is restored with the snapshot and sent with an install. Snapshot data, last
included index, last included term, format version and checksum are published atomically
as one recoverable unit.

### 10.2 Opaque command payloads and materialized state

```text
domain mutation
    -> deterministic command encoding
    -> opaque WAL payload at (index, term)
    -> durable replay in index order
    -> command decoding
    -> materialized application state
```

Key/value, catalog, health, node, tenancy, session, lock and ACL commands all follow this
path. RaftLog never sees a service ID or key, only command bytes. Reads are served from
`QraftStateStore`, never by searching the WAL. Command application enforces
compare-and-set indexes, session ownership, node-scoped identity and sequence ordering;
those rules belong entirely to the deterministic state machine.

Command codecs reject truncated payloads, impossible lengths, forbidden trailing data,
invalid UTF-8 where text is required, and variants missing required fields. The WAL
checksum protects stored bytes; it does not replace payload validation.

### 10.3 Append and conflict-replacement contract

Leader submission:

1. Allocate the next index and encode the command without changing memory.
2. Call `appendEntries` with the indexed, termed payload.
3. Call `sync`, the durability barrier.
4. Only after a successful sync, add the entry to the in-memory log.
5. Replicate it, and acknowledge the client only after commit and application.

Follower `AppendEntries` is prepare, persist, then apply:

1. Validate the preceding index and term. A preceding index inside the follower's
   snapshot is accepted without comparison: every entry a snapshot covers is committed,
   so it is identical on the leader, and refusing it would leave a follower that
   compacted after a retransmission unable to catch up.
2. Calculate a side-effect-free append plan from the request and the in-memory log.
3. If required, call `truncateSuffix(conflictIndex)`.
4. Append only the plan's new entries.
5. Call `sync` once for the whole truncate-and-append batch.
6. Apply the same plan to memory.
7. Only then answer success.

A request verifies the follower's log only through its last entry, or through the
snapshot boundary when it lies inside the snapshot. The follower reports that verified
index as its match index and advances its commit index to no more than
`min(leaderCommit, verifiedIndex)`. A longer tail may be an uncommitted entry from an
earlier term that no conflict has truncated yet: reporting it would let the leader count
the follower for entries it does not hold, and committing it would apply an entry the
cluster never committed.

The leader commits index N only when a majority of the voters, `floor(n / 2) + 1`
including itself, has a match index of at least N, and only when the entry at N is from
its current term; an earlier term's entry commits only as part of a later current-term
commit (Raft section 5.4.2). A cluster of two therefore needs both voters, and one of
four needs three. Every new leader appends a no-op in its term, which commits the entries
before it and gives the leader the current-term commit a configuration change needs.

Committed entries are applied strictly in index order. If the state machine fails to
apply one, from a local fault or because an older server in a rolling upgrade does not
know the command, the node **fences**: the applied index stays before the entry and
nothing after it is applied; the waiting client receives the failure; other pending
writes fail with an unknown outcome; the node stops its election, heartbeat and snapshot
timers and reports unready. Skipping the entry would leave that replica silently
different from every other. Recovery is an operator action: a fix or an upgrade, then a
restart.

An append or sync failure leaves the in-memory log unchanged and fails the response. A
durability failure that fences the WAL also fences the node; no later operation is
attempted on the same storage instance.

RaftLog's pure `AppendPlan` is the authoritative conflict calculator. Because Qraft keeps
a sentinel at the snapshot boundary, it supplies the post-snapshot entries as a one-based
relative view and translates the planned truncation index back to the absolute index.
Tests cover a compacted log in which a conflict is followed by a matching-term old
suffix; the whole suffix must still be appended again after truncation.

Metadata differs from append: `updateMetadata(term, votedFor)` has its own atomic
durability barrier. A server makes its new term and vote durable before granting a vote
or answering on the new term; a separate `sync` is neither required nor a substitute.

### 10.4 Snapshot and prefix-compaction contract

Every server compacts its own log independently, in any role, on the configured snapshot
interval and threshold. A follower that never compacted would hold every entry in memory
and in its WAL, replay all of them on restart, and once elected refuse writes at the log
hard limit until its first snapshot. A snapshot covers only applied, and so committed,
entries, so it is safe in any role; it runs through the transition sequencer like every
other state change.

Prefix compaction is safe only after a covering snapshot is durable:

1. Choose `lastApplied` as the boundary and resolve its exact term.
2. Capture a deterministic state-machine snapshot for that boundary.
3. Publish the snapshot and boundary metadata atomically and durably through
   `SnapshotStore`.
4. Call `RaftPersistence.truncatePrefix(lastIncludedIndex)`, which is RaftLog's
   `truncatePrefix`.
5. After durable compaction, trim the in-memory prefix and keep a sentinel holding the
   included index and term.

`truncatePrefix` is itself a durability barrier. It removes entries at or below the
boundary while keeping original indexes, terms, payloads and replay order for later
entries.

- A snapshot publication failure leaves the WAL and memory unchanged. A snapshot that
  fails before publication fences nothing; the storage read reports it.
- A crash after publication but before compaction is safe; recovery ignores replayed
  entries at or below the boundary.
- A compaction failure leaves memory untrimmed and may fence the WAL.
- A successful compaction is never paired with an absent or volatile snapshot; recovery
  checks for this pairing and refuses it (section 10.5).
- Installing a snapshot from a leader uses the same publish-before-compact order before
  changing the follower's in-memory state.

`FileSnapshotStore` publishes snapshots durably and atomically.

### 10.5 Recovery contract

Recovery:

1. loads term and vote metadata;
2. loads the latest valid snapshot;
3. restores the state machine and its included index and term;
4. replays the WAL in its original index order;
5. rejects entries that overlap the snapshot with a conflicting term. A follower
   publishes an installed snapshot before it removes the WAL suffix that conflicts with
   it, so a crash between the two leaves an entry at the boundary with another term.
   Recovery discards that entry and every later one, which belong to the history the
   snapshot replaced, and completes the interrupted compaction in an owned transition
   before the node starts. Such entries are never replayed or applied;
6. applies eligible committed entries strictly after the boundary.

RaftLog may repair only a structurally incomplete final record left by a torn write.
Complete records with bad checksums, malformed headers or ambiguous interior corruption
are recovery failures and fence the instance; Qraft never replaces them with empty
state. After prefix compaction the first replayed entry may have an index above one, so
recovery validates continuity relative to the snapshot boundary.

- **An interrupted first snapshot.** A temporary snapshot file with no published
  snapshot is what a kill leaves between creating that file and renaming it, during the
  first snapshot a server writes or installs; the WAL is complete at that moment, by the
  order of section 10.4. `FileSnapshotStore.open` moves the file to
  `snapshot.dat.interrupted`, keeping one such file as evidence, and the server starts
  from its WAL.
- **A missing snapshot.** After replay, recovery compares the index the WAL is compacted
  through, from `compactionBoundary()` of RaftLog's storage interface, with the index the
  published snapshot reaches. If the WAL is ahead, a published snapshot is missing or was
  replaced by an older one, and the entries between are gone: recovery fails, the node is
  fenced, and the server stays live and unready, as for a corrupt WAL. The interface's
  default answers 0, for a storage that never compacts, so an implementation that
  compacts must override it to be checked.
- **No rollback of a voter.** Restart recovery assumes the server's last durable state.
  An offline copy that predates later participation cannot replace it under the same
  server ID: it may erase persisted votes or acknowledged entries. The same-identity
  restore restriction and the healthy-quorum replacement procedure are in
  [`docs/OPERATIONS.md`](../docs/OPERATIONS.md#restoring-storage-without-rolling-back-a-voter).
- **Lost quorum.** Lost-quorum recovery and whole-cluster restore from backup are
  supported only through the offline `qraft operator raft inspect` and `recover`
  commands (`server.recovery`). `recover` copies a stopped source directory, appends one
  configuration entry listing a single voter under a newly generated server ID in a term
  above the source's, replaces `server-id` and publishes the copy by rename; the WAL,
  snapshot, metadata and wire formats do not change. A claim file beside the source
  makes recovery one-time, and an interrupted run can be repeated safely. Server-ID
  fencing keeps the recovered cluster apart from an old majority that is restarted: the
  seed and the servers that join it refuse appends and snapshots meant for old IDs, the
  old configuration counts no reply from the new IDs, and the recovered leader ignores
  old campaigns. Other servers rejoin empty under new IDs. A restore keeps what preceded
  the backup and nothing after it. The procedure is in
  [`docs/OPERATIONS.md`](../docs/OPERATIONS.md#recover-a-cluster-that-lost-its-quorum).

### 10.6 Concurrency and ownership

Each server has one open WAL instance and one exclusive data-directory lock. RaftLog owns
and serializes its blocking file operations; Qraft adds no executor that lets WAL
operations reorder, and never opens two storage instances over one directory. A storage
future completing means the operation reached the guarantee that method documents, not
that a command is committed or applied; Raft owns the persisted, committed and applied
indexes.

### 10.7 Version alignment

Qraft pins a RaftLog version whose interface and behaviour match its use: the root POM's
`raftlog.version`, 1.5.0, which provides `compactionBoundary()`.
`RaftLogStorageIntegrationTest` exercises the real library. Every upgrade needs contract
tests against the real `FileRaftStorage` for metadata durability across reopen; append,
sync, close, reopen and replay; atomic suffix replacement and replay; durable snapshot
publication followed by compaction and recovery; compaction failure without premature
in-memory trimming; corrupt-interior failure and torn-tail recovery; and exclusive
directory locking with rejection after fencing.

### 10.8 Application-format compatibility

- Existing protobuf field numbers are never reused, and new scalar fields have safe
  defaults.
- Catalog entries that omit scoped identity load with tenant and namespace `default`,
  empty datacenter and region, and enabled; no operator action or offline rewrite is
  needed.
- Snapshot readers accept older documents that omit newer sections: a snapshot without
  key/value, session, tenancy or ACL sections restores their initial state; a fixture
  written before ACLs proves it for the ACL section. Whole-node recovery still needs the Raft
  configuration envelope; a readable legacy payload does not imply startup compatibility.
- Older WAL entries stay readable during the migration window, including JSON
  distributed-state entries from before catalog replication, which the command codec
  still decodes.
- Corrupt or incomplete payloads fail recovery clearly rather than being read as empty
  commands.
- An entry holding a command this server does not know, one a newer server wrote, is
  refused rather than read as a no-op: a follower refuses the append and a recovering
  server stops until it is upgraded. So in a rolling upgrade every server runs the new
  version before the new commands are used. Unknown fields inside a known command are
  still read past.
- Upgrade fixtures capture command and snapshot bytes before an identity change, such as
  `fixtures/node-identity/`, which shows entries and snapshots from before node IDs
  loading without one.

The upgrade procedure is in
[`docs/OPERATIONS.md`](../docs/OPERATIONS.md#rolling-upgrade).

## 11. Security

Tenant and namespace identity is carried through the request context, commands, stored
keys, queries, snapshots, events and audit records. Authentication establishes a
principal; authorization decides what it may do where. The request context is an
interface (`server.http.RequestContext`, implemented by `TokenRequestContext`), so the
caller's choice of scope and node is separate from the token that authorizes it, and
catalog commands do not depend on how a caller was authenticated.

### 11.1 Security profile and startup checks

- `server.security.profile` is `development` (the default) or `production`, which refuses
  to start without enabled ACLs and default policy `deny`.
- At startup a server logs its ACL settings without secrets, and one warning: with ACLs
  disabled, that every call is allowed, or, when the default policy is `deny`, the
  critical warning `ACL_DENY_NOT_ENFORCED`; with ACLs enabled and a listener without TLS,
  that tokens sent to it cross the network unencrypted, naming the listener and setting.
  A client logs the same `ACL_DENY_NOT_ENFORCED` warning.
- A secret is never logged or quoted, at any level, by either mode.

### 11.2 Access control lists

ACLs follow Consul's model.

- **Replicated state.** Tokens, policies, roles, auth methods, binding rules and a
  bootstrap index, in `state.acl`, changed only by ACL commands and read through the
  read-only `QraftStateStore.getAcl()`. A token has an accessor ID, the SHA-256 hash of
  its secret, a description, links to policies and roles, node and service identities, a
  creation time, an optional expiry, and a mark when it was installed from the management
  file. A policy holds typed rules (`common.acl.AclRules`), so the client's authorizer
  shares the model.
- **No secret is replicated.** A secret is hashed before its command is proposed, so the
  WAL, snapshots and backups hold only hashes, following ZooKeeper's digest scheme rather
  than Consul. A token is found by the hash of what a caller presents. A secret is
  answered only by the create or clone that made it.
- **Built in.** The `global-management` and `builtin/global-read-only` policies and the
  anonymous token, with Consul's IDs, are part of every server's initial state. The
  built-ins cannot be deleted, and the built-in policies' rules cannot change.
- **Decided by the state machine.** Refused with a code, identically on every replica: a
  change to a secret or expiry by an update; an ID, name or secret already in use; a link
  to a policy or role that does not exist; a reserved ID; and a command after which no
  token would hold `acl:write` without an expiry (the lock-out guard). A linked policy or
  role can be deleted; its links are dropped when a token or role is read, as in Consul.
- **One authorizer.** `common.acl.AclAuthorizer` is one pure function used by server and
  client: a token's grants, the default policy and a request in; allow or deny out, with
  what decided. An exact name beats a prefix and a longer prefix a shorter, as in Consul;
  for an equally specific name the more specific tenant, then namespace, wins; equals
  merge `deny`, `write`, `read`. A matched `read` refuses a write, and no default policy
  grants `acl`. A node identity reads services everywhere; a service identity reads those
  of its own scope. Rules use Consul's JSON form (`common.acl.AclRulesJson`). The lock-out
  guard and `POST /v1/acl/explain` decide through the same function.
- **Enforcement on a server**, with ACLs enabled. A request's token is resolved
  (`server.acl.AclResolver`) against the answering server's applied state; a request
  without one acts as the anonymous token. Each route authorizes before it reads or
  proposes:
  - *Writes*, after Consul's `vetRegisterWithACL` and `vetDeregisterWithACL`: a
    registration needs `node:write` on the name when it creates the node or changes what
    is recorded for it, and on any other name its node ID holds, which it renames; and
    `service:write` on the service in its scope, and on the instance it replaces. Since
    names are held regardless of case, a takeover or replacement of another spelling
    needs `node:write` on that spelling too. A heartbeat needs `node:write`; an
    observation `service:write`. `node:write` on a node removes anything on it; without
    it a service removal needs `service:write`.
  - *Reads are filtered*, never refused for a permission: nodes by `node:read`, services
    by `service:read`, an instance by both, as Consul's `CheckServiceNode.CanRead`. An
    answer that left anything out carries `X-Qraft-Results-Filtered-By-ACLs: true`.
  - *Operator*: `operator:read` for the Raft status, configuration, membership, storage,
    autopilot, configuration and event reads; `operator:write` for tenancy changes and a
    server removal, checked on the receiving server and again by the leader
    (`AclRemovalAuthorizer`).
  - *ACL API*: `acl:read` or `acl:write`, except the token's own `self`, `authorize` and
    `resolve`, which answer only for the presenting token. A request cannot delete its
    own token. With ACLs disabled the ACL API, its auth method, binding rule, login and
    logout routes included, answers 401 `acl_disabled`.
  - *Refusals*: 403 `token_not_found` for a token that names none, and 403
    `permission_denied` in the words of Consul's `PermissionDeniedError`, naming the
    accessor ID, the permission and its target. A refusal is logged at INFO with the
    accessor ID, never the secret.
  - *Open*: `/health/live`, `/health/ready`, the bootstrap and the administrative
    interface's files. With ACLs disabled every request is allowed and a token is
    ignored, as in Consul.
  - *Not checked again when applied*: the leader authorizes a write from its applied
    state, and the state machine does not, so a follower that is behind may honour a
    revoked token for a read until it catches up.
- **Bootstrap.** `PUT /v1/acl/bootstrap` creates the first management token once, and
  again only with the current bootstrap index, which an operator writes to
  `acl-bootstrap-reset` in the leader's data directory. With
  `server.acl.initialManagementTokenFile`, whenever a server leads and no token has that
  secret, it installs the token through the bootstrap. While a file is configured and its
  token is not installed, the bootstrap answers 503 `management_token_pending` and
  proposes nothing, so no other bootstrap can be applied first; Consul's `initializeACLs`
  leaves that window open at each first leadership.
- **Operator token.** With ACLs disabled, a server removal needs `server.operator.token`,
  which every server should share, since the receiving server and the leader both check
  it; without it every removal is refused. It cannot be combined
  with enabled ACLs, where `operator:write` replaces it.
- **Expiry and revocation.** A server refuses a token from its expiry on, by its own
  clock, as `token_not_found`, and while it leads deletes expired tokens in batches
  through the replicated command (`server.acl.AclTokenExpiry`). An expiry is between one
  minute and 24 hours from creation.
- **The client.** A client has four tokens, each a mounted file (`client.acl.ClientTokens`):
  its own for its node's calls, anti-entropy's read and every removal (Consul's `agent`
  token); one for the configuration file's services and checks (a service may name its
  own); a default for a request with none; and a recovery token for its own endpoints
  when no server answers. Each call to the servers carries the token its purpose names.
  An API registration keeps the token it was made with in `registrations.json`. A token
  can be replaced while the client runs, with `client:write` on its node, and with token
  persistence is kept in `acl-tokens.json`, readable only by the client's user.
- **The client decides its local API** (`client.acl.ClientAuthorizer`), with the shared
  authorizer, from what `POST /v1/internal/acl/resolve` answers for the caller's token
  (`client.acl.ClientAclResolver`), cached for the shortest time to live of what it holds
  and, with no server, decided by the down policy (Consul's `extend-cache`,
  `async-cache`, `allow` or `deny`). A revoked token keeps working on a client until its
  cache entry expires. A client whose own token is refused logs why and is not ready until
  a valid token registers it again. A client's default policy must be the servers'.
- **Node identity.** A client's token is normally a node identity for its own node: under
  the write rules above it cannot register, renew, take over or rename another node, and
  it removes anything planted on its own.

The ACL operator procedures are in
[`docs/OPERATIONS.md`](../docs/OPERATIONS.md#enable-acls-on-a-new-cluster).

### 11.3 Transport security

Transport security follows Consul's `tls` stanza. Without `server.tls` and `client.tls`,
every listener is plain.

- **One material, read once.** A PEM CA bundle, a certificate chain and a PKCS #8 key,
  RSA or EC (`common.tls.TlsMaterial`). The CA decides whom a server trusts. Refusals name
  the setting and file, never a key's content. TLS 1.2 is the lowest version, or 1.3 when
  configured.
- **The HTTP listener serves HTTPS or HTTP, never both.** It requires a client
  certificate from the CA unless `verifyIncoming` is `false`. Consul defaults that to
  `false`; Qraft defaults it to `true`, so a stolen token is of no use from a machine
  without a certificate. Health probes on that listener present a certificate too.
- **The Raft port uses mutual TLS**: a server presents its certificate, requires one from
  each peer, and checks that a dialled peer's certificate names the host in
  `server.raft.nodes` unless `verifyServerHostname` is `false` (`raft.GrpcTls`). A peer
  without a certificate from the CA, or speaking without TLS, takes no part. Forwarded
  writes travel over this port.
- **A client calls its servers over HTTPS** with its CA and certificate and an `https`
  server URL. A failed handshake is logged once per server and reason, since retrying
  cannot mend it, and the client is not ready until a server accepts it. Its local API
  stays HTTP on loopback, as Consul's does by default.
- **Command lines** take `--ca-file`, `--client-cert` and `--client-key`, never an
  environment variable.
- **Reloading.** Every context and manager made from `TlsMaterial` delegates to its
  current material. A server and a client check their files' modification times every
  30 seconds and reload on a change, after the same checks as at startup. New handshakes
  use the new material; open connections keep theirs; a reload that fails a check is
  logged at WARN and leaves the material in use.
- Consul binds its HTTP API to `127.0.0.1` by default; a Qraft server's HTTP listener
  binds `server.http.host`, every interface by default, so TLS and ACLs protect it.
- With ACLs enabled, a server warns at startup of each listener without TLS, HTTP, API
  gRPC or Raft: a follower forwards a write's token over the Raft port.

### 11.4 Auth methods

Auth methods follow Consul's.

- A replicated `jwt` method holds PEM public keys, a bound issuer and audiences, claim
  mappings and leeways, and the longest life it gives a token (one hour by default, at
  most 24). A `kubernetes` method holds the cluster API's `https` host, its CA and a
  reviewer service-account token, which the API never answers; the server that receives
  a login asks the cluster's TokenReview API, trusting that CA alone, and an
  authenticated service account gives `serviceaccount.namespace`, `serviceaccount.name`
  and `serviceaccount.uid`. A snapshot tells the two types apart by their fields.
- Binding rules bind a login to a service or node identity, a role or a policy, under a
  selector of `value.<name> == "<text>"` and `"<text>" in list.<name>` clauses.
- `POST /v1/acl/login` exchanges a valid credential for a token bound by every matching
  rule; logout deletes it. Deleting a method deletes its rules and its logins' tokens. A
  credential the method does not accept is refused 403; a Kubernetes API that cannot be
  reached or trusted gives 503 `auth_method_unavailable`.

### 11.5 Audit

Every request a server answers under a token is an `AUDIT` event in its journal, with
the accessor ID, method, route, status, request ID, tenant and namespace; no secret or
body is recorded. `server.audit.path` also writes the events as JSON lines, rotated by
size and file count as Consul's audit file sink is. The administrative interface reads
the answering server's file from its end, filtered, and pages backwards by the byte offset
each read stopped at, so no page scans again what the last one read.

### 11.6 Not provided

Automatic certificate issuance, revocation lists, a reload command, HTTPS on the client's
local API, JWKS, OIDC and AWS auth methods, audit of anonymous requests, and audit sinks
other than a file.

## 12. Events and observability

### 12.1 Events

`dev.mars.qraft.common.events` defines the event contracts, and each server keeps one
boot's newest events in a bounded `EventJournal`, with per-subscriber queues that end by
overflow with a resume cursor and are isolated from each other's failures. Events are:

- **system events** from `RaftNode`: leadership gained and lost, a snapshot published,
  fencing;
- **domain events** from `QraftStateStore`, for nodes, services, health, keys, locks,
  sessions and tenancy. Each carries its entry's term and committed index, identical on
  every replica, and names its scope; a key's event carries the key, never the value, and
  a write that changed nothing produces none;
- **audit events** (section 11.5).

`server.http.EventRoutes` serves the query, `GET /v1/operator/events`, and the live watch,
`GET /v1/operator/events/watch`, filtered by category, type, scope, service, key or
session; both need `operator:read`. The model, envelope, identity and retention are in
[`docs/QRAFT_EVENT_ARCHITECTURE.md`](../docs/QRAFT_EVENT_ARCHITECTURE.md).

### 12.2 Status and metrics

`/raft/status` reports, as one view read on the node's state loop, the node ID, server ID,
role, term, leader, commit index, last applied index, last log index, snapshot index,
`fenced` and a nullable `fenceReason` (the first fencing failure's exception type and
message), `removed`, and where the server knows its directory, `storageBytes` and
`snapshotAgeSeconds`. The sequencer keeps the first fencing failure and logs one ERROR
with its stack trace when it fences; later refusals keep their own messages.

Metrics follow these rules:

- **State metrics are counted where commands are applied**, so every replica counts
  alike: registrations and deregistrations by result, health transitions by the states
  left and taken, key/value applies by operation and result, active sessions, session
  invalidations by reason, and lock acquisitions by result.
- **Request metrics** count requests by route, method and status, and their duration by
  route and method. The route is the prefix it was registered under, never the path.
- **No unbounded labels**: no service IDs, keys, request IDs or error messages.
- **The client** publishes readiness, the age of its last server contact, reconciliation
  passes by result and the endpoint it tries first, at `GET /v1/client/metrics`
  (Consul's `/v1/agent/metrics` under Qraft's name), as JSON or Prometheus text.
- **Raft**: vote-request and AppendEntries counters, the Raft I/O pool's gauges, and
  storage size and snapshot age as gauges (`qraft.raft.storage.bytes`,
  `qraft.raft.snapshot.age`). Role, term, indexes and leader are read from
  `/raft/status`.
- **The leader's view** of the servers, in Consul's autopilot form: each server's last
  contact, term, index and health, with the cluster's health and failure tolerance. A
  follower is healthy when it answered within an election timeout's worth of heartbeat
  rounds, as check-quorum counts it, and trails the leader by at most 250 entries.

A server publishes its metrics on its Prometheus port, and Prometheus scrapes that port.
The OpenTelemetry collector receives traces and logs and scrapes nothing, so each
server's metrics are stored once.

**Logs** carry the request ID, node ID, Raft role and term where they apply, tenant and
namespace. A Raft transition logs and traces in the MDC and OpenTelemetry context of the
caller that submitted it, for every step (preparation, persistence completion and
application); neither the storage thread that completed persistence nor the transition
before it supplies that context. A stopping server can log one warning that is not
Qraft's, from gRPC's Netty transport; why, and why it is accepted, is in
[`docs/OPERATIONS.md`](../docs/OPERATIONS.md#a-warning-in-the-log-of-a-server-that-is-stopping).

The metrics and health reads an operator uses are in
[`docs/OPERATIONS.md`](../docs/OPERATIONS.md#metrics).

## 13. Administrative interface

### 13.1 Capabilities

The server provides a built-in administrative interface backed only by the same
authenticated, authorized HTTP APIs available to any caller. It never reads or changes
server objects directly and introduces no other consistency or persistence path. Its
design is in [`QRAFT_ADMIN_UI_UX_DESIGN.md`](QRAFT_ADMIN_UI_UX_DESIGN.md). It provides:

- **Discover**: services by tenant and namespace with health summaries
  (`/v1/internal/ui/services`), a service's instances and checks, checks by state
  (`/v1/health/state/{state}`), and nodes; registering and deregistering services
  through the normal catalog writes.
- **Coordinate**: key/value entries by prefix, with indexes, flags and session
  ownership; set, compare-and-set and delete, recursively too; sessions (with the leader's
  renewal and expiry times, which a follower answers as unknown), their creation, renewal
  and destruction; held locks and running lock delays across scopes; and live watches.
- **Operate**: Raft membership with each server's progress, reachability and health and
  the quorum's failure tolerance; storage and recovery state, including size, snapshot
  boundary and age, fencing and the last failed snapshot; and server removal.
- **Govern**: tenants and namespaces (their descriptions, what each holds, creation and
  deletion); ACL tokens and policies, with explanation of a decision; the security status
  (ACLs, default policy, profile, listener TLS, management tokens, the anonymous token's
  writes, tokens that hold `acl:write` without expiry, and tokens expiring soon); and the
  audit file.
- **Observe**: the server's events, the effective configuration (each setting with its
  value, whether configured or defaulted, and secrets redacted).
- **Read metadata**: an explicit consistency selection and the index, consistency,
  leader contact and answering server of each read.

The interface acts with the caller's token. With ACLs disabled it has the access every
caller has. Every mutation goes through the replicated command path and is audited like
any other request, and the interface shows leader hints, retryability, authorization
failures and unknown outcomes exactly as the API answers them. Secrets, raw credentials
and protected key/value contents are never shown without permission.

### 13.2 Packaging and serving

- **One artifact.** The frontend is built before Java packaging into `index.html`,
  content-hashed scripts and styles, and `asset-manifest.json` (Vite's
  `build.manifest: 'asset-manifest.json'`). Maven stages it under `META-INF/qraft/ui/`
  and the shade step packs it into `target/qraft.jar`. The frontend source is part of the
  one source tree, with locked tool and dependency versions, so a clean build is
  reproducible. The container image holds the same jar and no separate web
  distribution.
- **Served from the classpath.** Assets are read through `ClassLoader` resource streams,
  never as a `Path` or through `Files`, because resources in the shaded jar are ZIP
  entries. They are served on the server's HTTP listener and share its TLS, request
  limits and lifecycle. Client mode serves no interface; disabling it removes its routes
  without producing a different executable.
- **Routing.** The handler strips only the configured path prefix (`/ui/` by default; it
  cannot begin with `v1`, `api`, `health`, `raft`, `status`, `metrics` or `debug`). A
  read of `/` redirects 302 to the interface when it is enabled. An unknown extensionless
  `GET` below the prefix falls back to `index.html` for client-side routing; a missing
  named asset is 404, and API, metrics, health and debugging paths never fall back.
  Whether a last segment has an extension is read from the path as sent, before
  decoding, and the interface writes a dot in a route's name as `%2E`, so a view of a
  service named `api.v2` is still a route.
- **The index** is rendered per request and never stored: a JSON bootstrap object with
  public settings (the API base path, the interface's path, feature flags, the
  authentication mode and the security profile), never a secret, and a `<base>` naming
  the interface's path, since assets are linked relative to the page. Content-hashed
  assets are cached as immutable.
- **Headers.** An explicit content type, `X-Content-Type-Options: nosniff`, and a
  restrictive content security policy. Inline styles are allowed only with the request's
  nonce, which Ant Design passes to the styles it adds; the interface gives the nonce to a
  style a library adds without it. Only scripts from the server's own origin run.
- **Compression.** Scripts, styles, JSON and SVG are sent gzip to a request that accepts
  it, compressed once and kept in memory, with `Vary: Accept-Encoding`; the rendered index
  is not compressed. The build splits the application from its libraries so no script
  passes Vite's 500 kB warning.
- **Development override.** `server.ui.devAssetsDirectory` replaces the embedded assets
  for frontend work. Startup refuses a missing directory, one without `index.html`, or an
  override with the interface disabled.

## 14. Lifecycle and resource ownership

### 14.1 Ownership

The runtime owns mode selection; the selected mode owns its application service; each
service owns its servers, clients, schedulers, storage and background tasks. No component
creates an executor that another is expected to find and close. Startup failure closes
what was already opened, in reverse order. Every close is idempotent, and every caller
of a shutdown shares one completion.

### 14.2 Server execution resources

`QraftServerApplication` owns two execution resources from `common.async`:

- `RaftStateLoop` serializes Raft state transitions and timers on one platform thread.
- `BlockingTaskExecutor` runs blocking resource operations on virtual threads, with
  parallelism and queue capacity from `server.raft.io.poolSize` and `queueSize`. These are
  independent of the transport's callback pool, which uses the same settings. Overload
  fails the submitted operation rather than running blocking work on the submitting
  thread. Cancelling a queued task skips it; active work is not interrupted.

Both preserve the submitting `CallerContext`, MDC and OpenTelemetry context, and restore
the executing thread's previous context afterwards. Blocking results normally complete on
the state loop; a refused completion dispatch or a forced shutdown can fail results on
the dispatching or shutdown thread.

### 14.3 Server shutdown

`server.lifecycle.ShutdownCoordinator` runs a server's shutdown once; a repeated call
returns the same completion. Its states are `RUNNING`, `DRAINING`, `SHUTTING_DOWN`,
`STOPPED` and `FAILED`, and it runs four phases in order, each hook bounded by its own
deadline:

| Phase | Deadline | What runs |
|---|---|---|
| `DRAIN` (state `DRAINING`) | `server.shutdown.drainTimeoutMs` | Hooks that stop accepting new work. |
| `AWAIT_COMPLETION` | `server.shutdown.timeoutMs` | Hooks that wait for active work; the server registers none, since no server-managed work remains after draining. |
| `STOP_SERVICES` (state `SHUTTING_DOWN`) | `server.shutdown.timeoutMs` | Leader-only work (token installation, token and session expiry, health expiry), the audit sink and TLS reloader; then the Raft gRPC server, DNS and the HTTP listener; then the Raft node, which closes storage. |
| `CLOSE_RESOURCES` | `server.shutdown.timeoutMs` | Hooks that close remaining resources; the server registers none, since stopping the Raft node closes storage. |

A hook that fails or times out is logged and the sequence goes on, except a critical one:
stopping the Raft node is critical, and if it fails, later hooks are skipped, the state
becomes `FAILED` and the completion fails, because closing resources under a node that
did not stop would break its storage contract. Otherwise the state becomes `STOPPED`.

After the service stops, `QraftServerApplication` drains blocking work, stops the state
loop and closes telemetry, on a virtual thread it owns, so cleanup never runs on an
execution resource whose termination it awaits. Each executor reports failure if it
cannot terminate within five seconds; a forced blocking shutdown fails outstanding
operations. Later steps still run after a failure; the first failure is reported with the
later ones suppressed.

A draining HTTP server reports `draining` on readiness, refuses proposals with 503
`draining`, retryable, and forwards nothing (`HttpApiServer.enterDrainMode`). The server's
`DRAIN` hook puts the HTTP API into drain mode before `STOP_SERVICES` stops the listener.
The drain does not wait: a load balancer is told to stop sending by the operator, before the
stop.

### 14.4 Client shutdown

The client's shutdown is the sequence of section 6.4, bounded as a whole by
`client.shutdownTimeoutMs`. The client awaits its executors' and HTTP client's
termination on a virtual thread before reporting shutdown complete, and `HttpCatalogClient`
owns and idempotently closes its JDK `HttpClient`.

### 14.5 Callbacks and deadlines

A non-async future callback can run on the completing thread or on a thread registering
or helping complete it, so blocking work is dispatched to its owned executor, never run
inline on an arbitrary callback thread. Bounded waits use
`dev.mars.qraft.common.concurrent.Deadlines`, not `CompletableFuture.orTimeout` or
`completeOnTimeout`: the JDK completes those on one JVM-wide delay thread, where one
blocked callback would stall every timeout in the process. `Deadlines` delivers each
timeout on a new virtual thread and releases the pending expiry once the result arrives.

## 15. Test strategy

Development is strict test-first: a failing test, then the code, in small behavioural
increments. [`docs/TESTING.md`](../docs/TESTING.md) describes the three suites, how to run
them, and the log audit that every run passes: any logged error without an explicit
intentional-error flag is a defect, whatever the test totals say.

- **Domain tests**: composite identity prevents cross-node and cross-tenant collisions;
  re-registration is deterministic and idempotent; validation refuses invalid identity,
  address, port, tags and metadata; health transitions, stale observations, session
  expiry and lock ownership are deterministic at their ordering boundaries.
- **Codec and compatibility tests**: every command round-trips through protobuf; unknown
  fields are tolerated where safe; incomplete and unknown commands are refused; old binary
  and snapshot fixtures decode with documented defaults and reserialize deterministically.
- **Protocol adapter tests**: outbound HTTP adapters run against a real JDK HTTP fixture
  for method, path, headers, body, timeout, parsing, failure classification and cleanup.
  Server HTTP tests run against a real bound port and a real single-node Raft state
  machine. Interface-serving tests verify classpath loading from the packaged jar,
  content types, cache and security headers, prefix stripping, client-route fallback,
  API-route isolation, disabled routes, bootstrap escaping and missing assets.
- **Lifecycle tests**: purpose-built fakes for the catalog-client boundary, scheduler
  trigger and clock; partial startup, repeated calls, retry cancellation, readiness
  transitions, bounded shutdown and executor termination.
- **Raft tests**: held transports and manual timers decide rounds deterministically,
  never wall-clock waits. Membership, Pre-Vote, cleanup and disruptive-server rules each
  have such tests, and real-WAL tests reopen storage.
- **Cluster tests**: in-memory and real-transport clusters cover replication to three
  servers, writes sent to followers, leader isolation and replacement, conflicting
  uncommitted entries, partition healing and catch-up, snapshot installation, restart
  recovery and client failover across servers.
- **End-to-end tests**: real servers and clients started through the runtime boundary
  from configuration files, covering registration, discovery, readiness loss and
  recovery, reconciliation after server restart, health transitions across leader
  changes, deregistration and ordered shutdown. The packaged-artifact test starts the
  shaded jar from a directory with no interface files, fetches the index and a
  manifest-listed asset, and verifies that client mode and a disabled server serve none
  of the interface's routes.
- **Container tests**: one image started in both modes; multi-server clusters with
  clients; leader replacement, durable restart, membership changes, partitions and lost
  quorum. The shared fixture builds the image once, before the first container test class
  and outside its time limits, and never retries a failed build: a failed build is a
  fault to investigate, and every later class is given the first failure.

An intermittent failure is a defect: its cause is found, production code is checked for
the race, and the test is made deterministic.

## 16. Differences from Consul

Qraft follows Consul's routes, field names and rules unless a difference below is
stated. Each is deliberate.

| Area | Consul | Qraft | Why |
|---|---|---|---|
| Membership and failure detection | Serf gossip; `serfHealth` check | Heartbeats and node TTL decided by the leader; an unreachable node's services leave `?passing` | No gossip layer; one replicated status makes every server answer alike (section 7.4). |
| Client members | `/v1/agent/members` lists the gossip pool, `left` nodes included | A forwarded catalog node read; clients only; no `left`; no `wan` or `segment` | No gossip pool (section 6.3). |
| Admin partitions | Enterprise admin partitions | Tenants, with namespaces inside | The same isolation boundary under Qraft's name (section 5.1). |
| Default reads | Forwarded to the leader | The answering server's applied state, with known-leader headers | Qraft forwards writes, not reads (section 9.2). |
| Unknown query parameters | Ignored | Refused 400 `invalid_query` | An ignored parameter answers another question (section 4.5). |
| A `registering` node's name | Replaceable without a Serf check | Held until the node is unreachable | A registering node is alive (section 5.2). |
| Name takeover | The new Consul agent's anti-entropy removes the old services | Removed in the same replicated step | The reconciler removes only its own scope, from its first pass (section 5.2). |
| ACL secrets | Stored | Only SHA-256 hashes replicated | WAL, snapshots and backups hold no secret (section 11.2). |
| Management token file | Bootstrap window open at first leadership | Bootstrap refused until the file's token is installed | No other bootstrap can win the race (section 11.2). |
| `verifyIncoming` | `false` by default | `true` by default | A stolen token is useless without a certificate (section 11.3). |
| Server HTTP bind | `127.0.0.1` by default | Every interface | Clients on other hosts call the servers; TLS and ACLs protect the listener (section 11.3). |
| Local API writes during leave | Accepted | Refused 503 `shutting_down` | Nothing is registered behind the deregistration (section 6.3). |
| Unreadable persisted registrations | Logged; Consul continues | The client stops; the file is kept | A silently lost registration would deregister services (section 6.3). |
| TTL status across restart | Kept until it would expire | Not kept | (section 6.3) |
| Maintenance check | Critical | Reports `maintenance`; not required | Maintenance never holds readiness back (section 6.3). |
| Leave | Permanent until an explicit join or `rejoin_after_leave` | The next start registers again | A client always has its server URLs (section 6.4). |
| Catalog writes through the local API | Forwarded | Registrations go through `/v1/client/*` | The client keeps and reconciles what applications register (section 6.3). |
| Pre-vote grant | `hashicorp/raft`'s rule | Granted only for a term strictly ahead of the receiver's | (section 8.4) |
| Configuration | Files, flags and some environment variables | One JSON file; no environment variables | Principle 9. |
| DNS | Recursors, prepared queries, other datacenters | None | Not offered (section 5.3). |
| KV ACLs | `list` access | No `list` access | (section 5.5) |
| Client metrics | `/v1/agent/metrics` | `/v1/client/metrics` | The routes are named after Qraft's client mode. |

## 17. Open decisions and known gaps

- **Trusting a node claim with ACLs disabled.** With ACLs enabled, a server trusts a
  caller's node claim only as far as its token's grants (section 11.2), and with HTTPS a
  client certificate binds the caller to a machine (section 11.3). With ACLs disabled,
  any caller can still claim any node name and node ID. Whether the development profile
  should keep that is open.
- **The migration window for legacy readers.** How long the readers of legacy command and
  snapshot formats (section 10.8), including the JSON distributed-state entries, stay
  supported.
- **Runtime configuration reload.** Configuration is read once; changing or removing a
  service in a client's file needs a restart, though the local API changes definitions at
  run time. Whether to add a reload is open.
- **Reconciliation backoff.** Service reconciliation stays on the heartbeat cadence and
  has no independent backoff; only node registration backs off.
