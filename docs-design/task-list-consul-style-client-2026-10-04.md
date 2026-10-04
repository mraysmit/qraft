# Task List: Consul-Style Client Agent

**Date:** 2026-10-04
**Status:** Proposed. Starts after [`task-list-single-pom-and-quorus-removal-2026-10-04.md`](task-list-single-pom-and-quorus-removal-2026-10-04.md), which provides the `client` package and the new node model and routes.
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), sections 4.3, 5, 6.2, 7.3, 8, 12.1 and 16
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

## 1. Goal

`qraft client` becomes a full client agent in Consul's sense, running on every
VM or bare-metal host:

- Applications talk only to their local agent on `localhost`. Through it they
  register their services and checks, report TTL status, and discover other
  services.
- The agent keeps those registrations in sync with the servers and forwards
  discovery reads to them.

Today the client only registers services from its configuration file and
publishes check results. Its local listener serves nothing but liveness and
readiness.

## 2. Decisions

All made 2026-10-04. Decisions 5 to 8 follow the Consul pattern.

1. **Deployment target:** VMs and bare metal, with one client per host.
2. **Client-to-server transport:** HTTP. The client keeps its classified
   transport, seed rotation, and error envelope.
3. **Local API scope:** Consul's `/v1/agent/*` endpoints, plus catalog and
   health reads forwarded to the servers. Key/value and sessions are forwarded
   as the servers gain them.
4. **DNS:** a separate, later task list.
5. **`/v1/agent/*` belongs to the client.** The server's current
   `/v1/agent/service/register`, `/v1/agent/service/deregister`, and
   `/v1/agent/check/observe` endpoints, together with the node routes, become
   the server-side protocol for the client:
   - `PUT /v1/catalog/register` and `PUT /v1/catalog/deregister`, Consul's
     catalog write paths, for nodes and services;
   - `PUT /v1/catalog/check/observe` and `PUT /v1/catalog/node/heartbeat`.

   The old paths are removed in the same change; nothing is deployed.
6. **The local API binds to `127.0.0.1` by default,** like Consul's
   `client_addr`. It has no authentication until the security list; liveness
   and readiness share the same listener.
7. **Registrations follow Consul's rules.**
   - Services and checks registered through the API are kept in
     `agent.dataDirectory` and restored at startup.
   - At run time, the latest registration or deregistration of an ID wins,
     including for a service defined in the configuration file.
   - At startup, the configuration file's services are registered again and
     take precedence over a kept API registration with the same ID.
8. **Node identity follows Consul.**
   - `agent.dataDirectory` is required.
   - A node ID (UUID) is generated at the first start and kept in
     `node-id` in that directory. An unreadable file stops startup and is never
     replaced.
   - `agent.id` becomes `agent.nodeName`, which defaults to the host name.
   - The catalog stays keyed by node name, so the service identity
     `(tenant, namespace, node, serviceId)` does not change. The node ID is
     stored with the node and used to detect conflicts.
   - A registration under a name held by another node ID is refused while that
     node is healthy, with 409 `node_name_reserved`. Once the old node is
     unreachable or reaped, the new ID takes the name, as Consul lets a new ID
     replace a dead node.
   - The same node ID under a new name renames the node.

## 3. Rules

- Red before green; mutation evidence for the safety guards; no Mockito;
  deterministic tests with injected clocks.
- Client HTTP behaviour is tested against real JDK HTTP servers on both sides.
- Each phase ends with `mvn install`. Phases that change the runtime also run
  the end-to-end suite and the Docker suite on a fresh image.
- The design document changes in the same phase as the behaviour.
  The user runs the builds and commits.

## 4. Tasks

### Phase 1. Client-to-server protocol

- [ ] Move the server's write endpoints off `/v1/agent/` (decision 5). Keep the
  identity headers, idempotence, error envelope, and `X-Qraft-Index`.
- [ ] Point `HttpCatalogClient`, the health publisher, and node registration
  at the new paths. Extend `AgentControllerContractTest` to cover every client
  call against a real server.
- [ ] Make sure the server exposes every read the client forwards: catalog
  services, a service, nodes, and service health.

**Exit:** The contract test passes, along with the full default, end-to-end,
and Docker suites.

### Phase 2. Data directory and node identity

- [ ] Add a required `agent.dataDirectory` setting, validated before any
  resource opens. Allow one agent per directory, enforced with a lock.
- [ ] Generate and keep the node ID (decision 8). Share the code with the
  server's `ServerIdentity` through `common`.
- [ ] Rename `agent.id` to `agent.nodeName`, defaulting to the host name.
  Update the configuration examples and the Docker configurations.
- [ ] Server side: store the node ID with the node, using a new optional
  protobuf field; older entries load without one.
  - Refuse a held name with `node_name_reserved`.
  - Allow the takeover once the holder is unreachable or reaped.
  - Rename a node when its ID returns under a new name.
- [ ] Add fixtures: node entries and snapshots written without a node ID
  still load.

**Exit:** Red before green, with mutations of the conflict and takeover
guards. A wiped-directory restart scenario passes end to end.

### Phase 3. Local API listener

- [ ] Grow `HealthService` into the client's local API server: one JDK
  `HttpServer` serving `/health/live`, `/health/ready`, and the API routes.
- [ ] Add a bind-address setting (decision 6). The startup log and the
  lifecycle report the bound address and port.
- [ ] Share request handling with the server through `common`: error envelope,
  `X-Request-Id`, body limits, and method checks.
- [ ] Refuse API writes once shutdown begins; keep reads until the listener stops.

### Phase 4. Services and checks through the API

- [ ] Services: `PUT /v1/agent/service/register` (optionally with checks),
  `PUT /v1/agent/service/deregister/{serviceId}`, `GET /v1/agent/services`, and
  `GET /v1/agent/service/{serviceId}`.
- [ ] Checks: `PUT /v1/agent/check/register`,
  `PUT /v1/agent/check/deregister/{checkId}`, and `GET /v1/agent/checks`.
- [ ] Feed the reconciler one definition source, file plus API definitions,
  through its existing `Supplier` contract, with the rules of decision 7.
- [ ] Keep API registrations atomically in the data directory, restore them at
  startup, and fail startup on an unreadable file.
- [ ] Start and stop local checks with registration and deregistration. The
  published declared-check set follows the change.

### Phase 5. TTL status and maintenance

- [ ] `PUT /v1/agent/check/pass/{checkId}`, `warn`, and `fail` (each with an
  optional note), and `PUT /v1/agent/check/update/{checkId}`, all through
  `LocalStatusReporter`.
- [ ] Node maintenance with `PUT /v1/agent/maintenance`, and service
  maintenance with `PUT /v1/agent/service/maintenance/{serviceId}`. Both
  publish `maintenance` observations and persist across a restart.

### Phase 6. Agent information

- [ ] `GET /v1/agent/self`: node name and ID, version, configuration with
  secrets redacted, controller contact, and readiness conditions.
- [ ] `GET /v1/agent/members`: nodes and their status from the servers' catalog.
  Qraft has no gossip pool, so this is a forwarded read.
- [ ] `PUT /v1/agent/leave`: the existing bounded graceful deregistration,
  followed by process exit.

### Phase 7. Forwarded reads

- [ ] Forward `GET /v1/catalog/services`, `/v1/catalog/service/{name}`,
  `/v1/catalog/nodes`, and `/v1/health/service/{name}` (including `?passing`)
  through the seed-rotating transport.
- [ ] Pass through unchanged the scope headers, `X-Qraft-Index`, the leader
  hint, request IDs, status codes, and the error envelope.
- [ ] With no reachable server, return 503 with a retryable envelope. Never
  serve stale local data as current; there is no local cache in this list.

### Phase 8. Verification and documentation

- [ ] End-to-end scenario 1: an application registers a service and a TTL
  check through its own agent, reports passing, and is discovered through
  another host's agent.
- [ ] End-to-end scenario 2: the application deregisters cleanly.
- [ ] End-to-end scenario 3: API registrations survive an agent restart, and
  the local API keeps working across a server leader change.
- [ ] End-to-end scenario 4: a host rebuilt under the same name re-registers
  only after its old node becomes unreachable.
- [ ] Docker: two client containers whose applications use only `localhost`.
- [ ] Update the design, sections 4.3, 5, 6.2, 7.3, 8, 12.1 and 16:
  applications talk to their local agent, node identity, and the path split.
- [ ] Update the feature validation (persisted generated node identity is now
  delivered), the Consul plan checklist, and the Docker client example.

**Exit:** Full suites on a fresh image; changed concurrency tests pass five
consecutive runs; audits as in the other lists.

## 5. Out of scope

- DNS discovery (decision 4).
- Authentication and ACLs on the local API (security list).
- Forwarding key/value, sessions, consistency modes, and blocking queries. Each
  is added when the corresponding server feature lands.
- Response caching in the agent, gossip, and configuration-file reload.
