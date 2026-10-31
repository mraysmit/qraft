# Task List: Consul-Style Client

**Date:** 2026-10-04
**Status:** Active from 2026-10-26, with Phase 1. The `client` package and new node model and routes were delivered by [`task-list-single-pom-and-quorus-removal-2026-10-21.md`](../docs/archive/task-list-single-pom-and-quorus-removal-2026-10-21.md), completed and archived on 2026-10-14. The membership list ([`task-list-raft-membership-changes-2026-10-24.md`](../docs/archive/task-list-raft-membership-changes-2026-10-24.md)) was completed and archived on 2026-10-24, and Pre-Vote followed on 2026-10-25 and 2026-10-26, so nothing precedes this list. The sequencing decision of 2026-10-05 stands: this list, then the ACL list. The owner confirmed it on 2026-10-26 when asked whether key/value completion should go first, and said the embedded administrative interface waits until the other outstanding work is done.
**Last updated:** 2026-10-31 (Phase 4's five tasks built, test first, checked against Consul's agent; its exit waits for the full suites. Earlier, 2026-10-30: Phase 4 started: `c33a2f2` recorded, with client anti-entropy, a check ID unique across a client, and the groundwork for each task, none of them complete, verified by Jenkins build 56; decision 8's reason for the server's removal corrected to match; the rule on who runs builds corrected. Earlier the same day: Phase 3 closed: the local API on `client.bindAddress`, `127.0.0.1` by default as Consul's `client_addr`, request handling shared with the server through `common.http.JsonApi` with a 512 KiB body limit, and writes refused once shutdown begins. Earlier the same day: Phase 2 closed: the server side built test first, the node-identity fixtures, the determinism test and the design record, with writes from a node of another ID refused as Consul refuses them; its exit met on a local run. Earlier, 2026-10-29: decision 8 checked against Consul's catalog code and four points decided by the owner: a rename follows Consul, removing the node under its old name with its services and checks; a node without an ID can be claimed by a registration that carries one; names conflict regardless of case; a `REGISTERING` node holds its name. Phase 2's server task and exit follow. Earlier, 2026-10-28: Phase 2's three client tasks recorded as done: the data directory and its lock, the node ID kept through a shared identity file, and `client.nodeName`; the server side remains. Earlier, 2026-10-27: Phase 1 closed on a local run of the three suites, no Jenkins being reachable; Phase 2 started. Earlier the same day: renamed with the ACL list, which changed. Earlier, 2026-10-26: Phase 1 built, with local checks only; the full suites run on Jenkins before it is closed. Earlier the same day: started, membership and Pre-Vote complete. Earlier, 2026-10-21: membership Step 5 completed; Steps 6 to 8 remain the preceding work; this client list is still proposed; renamed with linked documents. Earlier, 2026-10-14: cleanup prerequisites completed and archived; membership Steps 5 to 8 remain the preceding work. On 2026-10-11: the rule that guards need mutation evidence removed at the owner's decision, from the rules and from one exit; otherwise renamed with the task lists it links to; nothing else changed that day. On 2026-10-10: renamed with the task lists it links to; nothing else changed that day. On 2026-10-09: decisions 5 and 8: what a node's deregistration removes, and the `client.hostname` setting; the file renamed to the date of its last change; the single-POM list's Phase 3A replaced Qraft's two retired words here and everywhere; decision 10 records the configuration names, and decision 3 the path of the local API. On 2026-10-08, the review with the other task lists: the request bodies of decision 5 and the log audit in the rules)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), sections 4.3, 5, 6.2, 7.3, 8, 12.1 and 16
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

## 1. Goal

`qraft client` becomes a full client in Consul's sense, running on every
VM or bare-metal host:

- Applications talk only to their local client on `localhost`. Through it they
  register their services and checks, report TTL status, and discover other
  services.
- The client keeps those registrations in sync with the servers and forwards
  discovery reads to them.

Today the client only registers services from its configuration file and
publishes check results. Its local listener serves nothing but liveness and
readiness.

## 2. Decisions

Decisions 1 to 8 were made 2026-10-04, decision 9 on 2026-10-05, and
decision 10 on 2026-10-09. Decisions 5 to 8 follow the Consul pattern.

1. **Deployment target:** VMs and bare metal, with one client per host.
2. **Client-to-server transport:** HTTP. The client keeps its classified
   transport, seed rotation, and error envelope.
3. **Local API scope:** the endpoints of Consul's agent API, served at
   `/v1/client/*` (the path was decided 2026-10-09), plus catalog and
   health reads forwarded to the servers. Key/value and sessions are forwarded
   as the servers gain them.
4. **DNS:** a separate, later task list.
5. **`/v1/client/*` belongs to the client.** The server's current
   `/v1/client/service/register`, `/v1/client/service/deregister`, and
   `/v1/client/check/observe` endpoints, together with the node routes, become
   the server-side protocol for the client:
   - `PUT /v1/catalog/register` and `PUT /v1/catalog/deregister`, Consul's
     catalog write paths, for nodes and services;
   - `PUT /v1/catalog/check/observe` and `PUT /v1/catalog/node/heartbeat`.

   The old paths are removed in the same change; nothing is deployed.

   The node routes move earlier, in the single-POM list's Phase 4. That phase
   fixes the request bodies of the two catalog write paths for nodes and
   services together, so that Phase 1 here adds services without changing
   what a node sends (noted 2026-10-08).

   Done there on 2026-10-09, with two things that Phase 1 here builds on:
   - The bodies of the two catalog write paths are in the design's section
     12.1.2. A registration describes the node under `node`; a service is
     added beside it under `service`. A deregistration that names no service
     removes the node.
   - **A node's deregistration removes its services and checks** in the same
     replicated step, as in Consul (decided by the user on 2026-10-09).
6. **The local API binds to `127.0.0.1` by default,** like Consul's
   `client_addr`. It has no authentication until
   [`task-list-acl-and-tokens-2026-10-31.md`](task-list-acl-and-tokens-2026-10-31.md),
   which is proposed to follow this list; liveness and readiness share the
   same listener.
7. **Registrations follow Consul's rules.**
   - Services and checks registered through the API are kept in
     `client.dataDirectory` and restored at startup.
   - At run time, the latest registration or deregistration of an ID wins,
     including for a service defined in the configuration file.
   - At startup, the configuration file's services are registered again and
     take precedence over a kept API registration with the same ID.
   - Checked against Consul's `agent/agent.go` on 2026-10-31, and built in
     Phase 4. The kept copy of an ID the file defines is purged (`loadServices`:
     "service exists, not restoring from file"), and a kept check that cannot
     be restored is purged (`loadChecks`: "Failed to restore check"). One
     departure: Consul logs a file it cannot decode and goes on, and a client
     stops, as Phase 4 asks.
8. **Node identity follows Consul.**
   - `client.dataDirectory` is required.
   - A node ID (UUID) is generated at the first start and kept in
     `node-id` in that directory. An unreadable file stops startup and is never
     replaced.
   - `client.id` becomes `client.nodeName`, which defaults to the host name.
     That is the machine's host name. The `client.hostname` setting was
     removed on 2026-10-09, in the single-POM list.
   - The catalog stays keyed by node name, so the service identity
     `(tenant, namespace, node, serviceId)` does not change. The node ID is
     stored with the node and used to detect conflicts.
   - A registration under a name held by another node ID is refused while that
     node holds it, with 409 `node_name_reserved`. A `REGISTERING` or
     `HEALTHY` node holds its name; once it is `UNREACHABLE` or reaped, the new
     ID takes the name, as Consul lets a new ID replace a node whose Serf check
     is critical (decided 2026-10-29; a node that has not yet sent its first
     heartbeat keeps its name, which is stricter than Consul).
   - Names conflict regardless of case, as in Consul (`strings.EqualFold` in
     `ensureNoNodeWithSimilarNameTxn`): `Web-1` and `web-1` are one name. A
     name is stored as it was given (decided 2026-10-29).
   - A node written before node IDs existed has none. A registration that
     carries an ID claims such a node's name even while it holds it, and keeps
     its services and checks: it is that machine after its upgrade. This is
     Consul's rule for a node without an ID (decided 2026-10-29).
   - **A takeover removes what the old node registered** (decided 2026-10-05).
     The catalog is keyed by node name, so the old node's services and checks
     would otherwise pass to the new ID. The command that gives the name to
     the new ID deregisters them in the same replicated step. The new node
     starts with nothing and registers its own. Consul instead leaves them for
     the new agent's anti-entropy, which deletes what it did not register.
     Qraft's reconciler has done the same since 2026-10-30 (Phase 4), but only
     in its own tenant and namespace and not before its first pass, so the
     server still removes them.
   - An old node that comes back after a takeover is another node ID asking
     for a held name. The rule above applies to it: it is refused with
     `node_name_reserved` while the new holder is healthy.
   - The same node ID under a new name renames the node, as in Consul
     (decided 2026-10-29, replacing the earlier plan to move the services): the
     node is removed under its old name with its services and checks, and
     inserted under the new one, in the same replicated step. The client's
     reconciler looks up each of its services on every pass and registers again
     those that are absent, so they return under the new name. A rename and a
     takeover are then the same step: remove the name's old record and
     everything on it, insert the node. A rename to a name held by another node
     ID follows the rule above: refused while that holder holds it, a takeover
     otherwise; a rename cannot claim a name held by a node without an ID.
9. **The Docker scenario keeps the `127.0.0.1` bind and shares the client's
   network namespace.** Decided 2026-10-05.
   - Decision 1 targets VMs and bare metal, so a client container in the test
     stands for a host. The test must exercise the bind address Qraft ships
     with (decision 6), not a widened one.
   - The application's requests are made from inside the client's network
     namespace: `docker compose exec <client> curl http://127.0.0.1:<port>/...`,
     using the `curl` the runtime image already has. An application that
     needs its own container joins the namespace with
     `network_mode: "service:<client>"`.
   - The test does not bind the local API to `0.0.0.0` or publish its port.
     That would no longer show applications using only `localhost`.
   - A container that does not share the client's namespace cannot reach the
     local API, and the test proves it.
   - A real container deployment that does not share a namespace sets the
     bind address of Phase 3 instead, as Consul's `client_addr` is set for
     containers. That is documented, not tested here.

10. **The client's configuration names its two objects `client` and
    `servers`.** Decided 2026-10-09, and done the same day by the single-POM
    list's Phase 3A, which replaced Qraft's two retired words everywhere.
    - This list no longer renames either object. Phase 2 changes only what
      is inside `client`: `client.nodeName` and a required
      `client.dataDirectory`.
    - The old object names are refused as unknown fields, like any other
      unknown field. They have no message of their own.
    - Decision 3's local API is served at `/v1/client/*`, and the ACL list's
      resource for a client's own endpoints is `client`. Both follow from
      the same decision.

## 3. Rules

- Red before green; no Mockito; deterministic tests with injected clocks.
  (The rule that safety guards need recorded mutations was removed by the owner on 2026-10-11, from every task list.)
- Client HTTP behaviour is tested against real JDK HTTP servers on both sides.
- Each phase ends with `mvn install`. Phases that change the runtime also run
  the end-to-end suite and the Docker suite on a fresh image.
- The design document changes in the same phase as the behaviour. Phase 8
  only checks that it did.
- A phase without its own exit line exits on these rules, with each of its
  endpoints tested over real HTTP.
- A run is accepted only after its retained Maven, application, subprocess,
  and Docker logs have been read and hold no unflagged error (`AGENTS.md`, and
  `docs/TESTING.md`, "Intentional error flags and log auditing"). A test that
  causes an error on purpose declares it. Added 2026-10-08: this list predates
  the logging policy.
- The owner commits. The main verification runs on Jenkins
  ([`docs/JENKINS.md`](../docs/JENKINS.md)).

## 4. Tasks

### Phase 1. Client-to-server protocol

- [x] Move the server's write endpoints off `/v1/client/` (decision 5). Keep the
  identity headers, idempotence, error envelope, and `X-Qraft-Index`.
  Done 2026-10-26, test first (`HttpApiServerCatalogServiceRoutesTest`, 12 tests,
  six red before the change):
  - `PUT /v1/catalog/register` takes `service` beside or instead of `node`, at
    least one of the two. The whole body is checked before anything is
    replicated, the node is committed first, then the service. A service the
    catalog rejects after the node was registered answers 409
    `registration_rejected`; the node stays and the client's retry repeats both
    idempotently.
  - `PUT /v1/catalog/deregister` with `{"serviceId": ...}` removes that service and
    keeps the node; `{}` still removes the node and everything on it. The
    service ID must be a non-blank string.
  - `PUT /v1/catalog/check/observe` replaces `/v1/client/check/observe`, with the
    same body and answers.
  - The three `/v1/client/*` routes and their handlers are gone; a request to them
    is a 404 from the HTTP server, tested.
  - Design sections 12.1, 12.1.1 and 12.1.2, the Consul plan and the admin
    interface plan are updated.
- [x] Point `HttpCatalogClient` and the health publisher at the new paths.
  Node registration already uses its new paths, from the single-POM list's
  Phase 4. Extend `ClientServerContractTest` to cover every client call
  against a real server.
  Done 2026-10-26: `HttpCatalogClient` sends `{"service": ...}` and
  `{"serviceId": ...}` and reads the service answer under `service`
  (`HttpCatalogClientTest`, five red first). The contract test gained a lookup
  and an observation against a real server, beside the register, deregister
  and node lifecycle tests it had. Every client test stub that stood in for the
  two shared routes now dispatches on the half the body carries
  (`NodeAnswerHelper.dispatching`), and the Docker, end-to-end and recording-proxy
  tests use the new paths and bodies.
- [x] Make sure the server exposes every read the client forwards: catalog
  services, a service, nodes, and service health. They were already served and
  tested in `HttpApiServerTest` (`/v1/catalog/services`, `/v1/catalog/service/{name}`,
  `/v1/catalog/nodes`, `/v1/health/service/{name}` with `?passing`).

**Exit:** The contract test passes, along with the full default, end-to-end,
and Docker suites. Met 2026-10-27, on `7a1ed98` with the image-build retry removed,
locally against Docker Desktop because no Jenkins was reachable (see
[`docs/JENKINS.md`](../docs/JENKINS.md), "Local run in place of Jenkins, 2026-10-27"):
default 1,205, end-to-end 10 and Docker 41 passed, coverage gates met, and the 223
retained logs hold 823 ERROR lines, all flagged, and no unflagged stack trace.

### Phase 2. Data directory and node identity

- [x] Add a required `client.dataDirectory` setting, validated before any
  resource opens. Allow one client per directory, enforced with a lock.
  Done 2026-10-27, test first, in `6680a67`. `ClientDataDirectory.open` creates
  the directory and takes a `FileChannel.tryLock` on `client.lock` before the
  node ID is read; a second client in the same process or in another process is
  refused with a message naming the directory, and the lock is released if the
  node ID cannot be read (`ClientDataDirectoryTest`, 5 tests;
  `ClientDataDirectoryProcessLockTest`, 1 test, with an owner subprocess).
  `QraftClient` opens it first, before either HTTP client, and releases it when
  its shutdown completes (`QraftClientTest.aClientHoldsItsDataDirectoryFromConstructionUntilItsShutdownCompletes`).
  A missing or invalid path is refused when the configuration is read
  (`ClientConfigurationTest.aDataDirectoryIsRequiredAndMustBeAPath`).
- [x] Generate and keep the node ID (decision 8). Share the code with the
  server's `ServerIdentity` through `common`.
  Done 2026-10-27, test first, in `6680a67`. `common.storage.IdentityFile`
  creates an ID once, writes it atomically through a temporary file and forces
  the directory, and refuses to replace one it cannot read (`IdentityFileTest`,
  5 tests). `ServerIdentity` keeps `server-id` through it; the client keeps
  `node-id` in its data directory.
- [x] Rename `client.id` to `client.nodeName`, defaulting to the host name.
  Update the configuration examples and the Docker configurations.
  Done 2026-10-27, test first, in `6680a67`
  (`ClientConfigurationTest.readsTheNodeNameAndTheDataDirectory`,
  `theNodeNameDefaultsToTheHostName`, and
  `theRetiredIdSettingIsRefusedLikeAnyUnknownSetting`). `docker/config/client.json`,
  the acceptance client configurations and the design's section 16 example
  carry `nodeName` and `dataDirectory`. The full suites passed on `14ded7d`, with
  the snapshot-transfer fix later committed as `f991254`: default 1,225,
  end-to-end 10 and Docker 41, and every ERROR line in the retained logs flagged.
- [x] Server side: store the node ID with the node, using a new optional
  protobuf field; older entries load without one.
  - Refuse a held name with `node_name_reserved`, comparing names regardless
    of case. A `REGISTERING` or `HEALTHY` node holds its name.
  - Allow the takeover once the holder is unreachable or reaped. The takeover
    command deregisters the old node's services and checks in the same step.
  - Refuse the old node ID when it returns after a takeover, while the new
    holder holds the name.
  - Let a registration with an ID claim the name of a node without one,
    keeping its services and checks.
  - Rename a node when its ID returns under a new name: remove it under the
    old name with its services and checks, and insert it under the new one, in
    the same step. Refuse a rename to a name another node holds, or that a
    node without an ID holds.

  Done 2026-10-30, test first, red seen for each before the change:
  - `Node.id` and `optional string id = 13` on `NodeProto`
    (`NodeCodecTest.aRegistrationCarriesItsNodeId`); the snapshot keeps it.
  - The rules are applied with the registration, from the replicated state
    alone: `NodeIdentityStateStoreTest`, 19 tests (refusal while registering or
    healthy, regardless of case; takeover of an unreachable holder with its
    services and checks removed; the old ID refused after a takeover; a claim
    of a node without an ID that keeps them; a rename that leaves nothing under
    the old name, refused into a held name or an ID-less holder's, taking over
    an unreachable one; a case-only change as a rename).
  - Beyond the list, as Consul does: a service registration, a service
    deregistration, a check observation and a node deregistration carry the
    sender's node ID (`optional string node_uuid` on `CatalogCommandProto` and
    `NodeCommandProto`) and are refused when the node they name has another
    ID, so a node that lost its name, for instance after a partition, cannot
    change or remove what the new holder registered.
  - HTTP: the ID comes from `X-Qraft-Node-ID` (a canonical UUID, else 400),
    and a refusal answers 409 `node_name_reserved` in Consul's words, naming
    the holder, with nothing in the body registered
    (`HttpApiServerNodeIdentityTest`, 8 tests). `GET /v1/catalog/nodes` lists
    each node's `id`.
  - Client: every request carries the node ID from the data directory
    (`HttpCatalogClientTest.everyRequestOfAClientWithANodeIdCarriesItAndAClientWithoutOneSendsNone`),
    and a `node_name_reserved` refusal is logged and tried again after the
    registration backoff, as Consul's agent keeps syncing
    (`RegistrationClientTest.aNameAnotherNodeHoldsIsLoggedAndTriedAgainLater`).
  - A client that lost its name, as after a partition and a takeover, registers
    again: a heartbeat of a registration a later one replaced answers 409
    `registration_superseded`, where an out-of-order one still answers
    `stale_heartbeat`, and the client goes back to registering
    (`RegistrationClientTest`, `QraftClientTest`); while its node is not
    registered it sends no service write, and a service refused with
    `node_name_reserved` is sent again on the next pass, not suppressed
    (`ServiceReconcilerTest`). Found while closing the phase and built the same
    day.
- [x] Add fixtures: node entries and snapshots written without a node ID
  still load.
  Done 2026-10-30: `fixtures/node-identity/`, a registration and a snapshot
  with nodes, a service and its check, written on `4c31c22` by the production
  codec and state store before the node ID existed, pinned by digest in its
  manifest (`NodeIdentityFixtureTest`).
- [x] Add replica-determinism tests for the takeover and rename commands:
  every replica ends with the same nodes, services, and checks.
  Done 2026-10-30:
  `ReplicaDeterminismTest.takeoverRenameAndClaimDecideTheSameOnEveryReplicaAndAfterASnapshot`,
  over a log of refusals, a takeover, the old ID refused, a claim, a rename
  and two spellings of a name, against a follower that installs a snapshot
  halfway. It covers code already written, so it was not red first.
- [x] Record decision 8 in the design: section 7.3, which today says a host
  name alone is not a sufficient identity, and section 22, which lists client
  identity as open and requires a decision record and fixture-based upgrade
  tests for a change to durable identity.
  Done 2026-10-30: sections 7.3, 12.1, 12.1.2 and 22.

**Exit:** Red before green, with tests of the conflict, takeover, and
rename guards, including a takeover that must not leave the old node's
services in place, a rename that must not leave them under the old name, and a
claim of a node without an ID that must keep them. A wiped-directory
restart scenario passes end to end. The scenario is
`WipedClientDirectoryEndToEndTest`: a second client with the same name and an
empty directory is refused while the first holds the name, and once the first
is killed and unreachable, takes it over with only its own services. Met
2026-10-30, on `23278d7` with the rest of the server side in the working tree,
locally against Docker Desktop: default 1,274 with every coverage gate met,
end-to-end 11 and Docker 43 passed with no failures, errors or skips, and the
229 retained logs hold 935 ERROR lines, all flagged, and no unflagged stack
trace. This is the run after the lost-name fix recorded with the server task above; the one before it, without
the fix, passed default 1,269 with the same clean audit.

### Phase 3. Local API listener

- [x] Grow `HealthService` into the client's local API server: one JDK
  `HttpServer` serving `/health/live`, `/health/ready`, and the API routes.
  Done 2026-10-30: `LocalApiServer` (renamed), with `route(path, handler)` for
  the API routes of the later phases.
- [x] Add a bind-address setting (decision 6). The startup log and the
  lifecycle report the bound address and port.
  Done 2026-10-30, test first, checked against Consul's agent
  (`agent/config/default.go`: `client_addr = "127.0.0.1"`; `agent/apiserver.go`
  logs each HTTP server's protocol and address). `client.bindAddress`, an IP
  address and `127.0.0.1` by default; a host name is refused, not looked up
  (`ClientConfigurationTest`). The listener binds it and logs `Local API
  started: protocol=http, address=...` (`LocalApiServerTest`). The runtime
  lifecycle reports every listener's address in both modes, its ports read
  from them (`QraftRuntimeApplicationTest`, `UnifiedRuntimeEndToEndTest`). As
  decision 9 asks, the Docker tests reach the client from inside its container
  and no longer publish its port; every Docker client logged
  `127.0.0.1:8080`.
- [x] Share request handling with the server through `common`: error envelope,
  `X-Request-Id`, body limits, and method checks.
  Done 2026-10-30, test first: `common.http.JsonApi` (`JsonApiTest`, against a
  real JDK server), used by the server's API and the local API. The body limit
  is new on both: 512 KiB, Consul's, answered 413 `request_too_large`, counted
  as read when no length is declared. The client's health routes now answer a
  wrong method in the envelope and carry the request ID.
- [x] Refuse API writes once shutdown begins; keep reads until the listener stops.
  Done 2026-10-30, test first: after `beginShutdown()`, which the client's
  shutdown calls first, a write is refused with 503 `shutting_down`,
  retryable, and reads and health are served until the listener stops
  (`LocalApiServerTest`, `QraftClientTest`). Consul's agent does not refuse
  writes while it leaves; this is stricter, so nothing is registered behind
  the deregistration.

**Exit:** none of its own; the list's rules, with each endpoint tested over real
HTTP. Met 2026-10-30, on `23278d7` with Phases 2 and 3 in the working tree,
locally against Docker Desktop: default 1,286 with every coverage gate met,
end-to-end 11 and Docker 43 passed with no failures, errors or skips, and the
235 retained logs hold 925 ERROR lines, all flagged, and no unflagged stack
trace.

### Phase 4. Services and checks through the API

Groundwork came with `c33a2f2` on 2026-10-30, which Jenkins build 56 passed with
clean logs ([`docs/JENKINS.md`](../docs/JENKINS.md)); nothing records whether its
tests were seen red first. The five tasks were built on 2026-10-31, test first:
53 of the 104 tests in the affected classes were seen red on stubs, each on an
assertion or on what a stub left empty. Checked against Consul's
`agent/agent_endpoint.go`, `agent/agent.go` and `agent/local/state.go`.

- [x] Services: `PUT /v1/client/service/register` (optionally with checks),
  `PUT /v1/client/service/deregister/{serviceId}`, `GET /v1/client/services`, and
  `GET /v1/client/service/{serviceId}`.
  Done 2026-10-31 (`LocalRegistrationRoutes`, `LocalRegistrationRoutesTest`, over
  real HTTP). As in Consul: the ID defaults to the name and the address to the
  client's; a registration keeps the service's other checks unless
  `replace-existing-checks` is given with no value or `true`; an unknown service
  reads as 404 (`service_not_found`); removing one that is not registered is not an
  error, answering `deregistered: false`.
- [x] Checks: `PUT /v1/client/check/register`,
  `PUT /v1/client/check/deregister/{checkId}`, and `GET /v1/client/checks`.
  Done 2026-10-31. A check for a service that is not registered is a 404, as
  Consul's "ServiceID does not exist". A check always names a service: Consul's
  node-level checks are not in this list. The listing carries each check's latest
  status, output and time once it has reported.
- [x] Feed the reconciler one definition source, file plus API definitions,
  through its existing `Supplier` contract, with the rules of decision 7.
  Done 2026-10-31 (`LocalDefinitions`, `LocalDefinitionsTest`). Beyond the task: the
  reconciler is told of a change (`ServiceReconciler.definitionsChanged`), as
  Consul's agent triggers a sync. A pass that is running read the old definitions,
  so one more runs after it, and none once shutdown has begun
  (`ServiceReconcilerTest`). Reads of the source take no lock, because the reconciler
  reads it under its own lock and a change tells the reconciler under the source's.
- [x] Keep API registrations atomically in the data directory, restore them at
  startup, and fail startup on an unreadable file.
  Done 2026-10-31 (`RegistrationStore`, `RegistrationStoreTest`): one versioned file,
  `registrations.json`, written through `common.storage.DurableFile`, which
  `IdentityFile` now shares. A change is kept before it takes effect, so one that
  cannot be kept answers 500 `registration_not_kept`, retryable, with nothing
  changed. A file that cannot be read stops the client before it opens a listener
  and lets the directory go (`QraftClientTest`).
- [x] Start and stop local checks with registration and deregistration. The
  published declared-check set follows the change.
  Done 2026-10-31 (`QraftClientTest`): readiness counts a required check from when
  it is registered until it is removed, publication forgets a removed check, and a
  service is sent again declaring its new set of checks. A check that changed is
  removed and added, so it starts afresh; registering the same again disturbs
  nothing.
- [x] Beyond the list, as Consul's anti-entropy: the reconciler removes what the
  catalog holds for its node and its source does not want, whoever registered it.
  Done 2026-10-30, in `c33a2f2`. A pass reads
  `GET /v1/catalog/node-services/{nodeName}`, a new server route named after
  Consul's (`HttpApiServerCatalogServiceRoutesTest`, `ClientServerContractTest`),
  and keeps what is in the client's tenant and namespace (`HttpCatalogClientTest`).
  A disabled definition still held is removed, a service both remembered and listed
  is removed once, a pass goes on when no server can list, and nothing is read while
  the node is not registered (`ServiceReconcilerTest`). Design section 8.2. Read in
  Consul's `agent/local/state.go` on 2026-10-31: "Mark a remote service that does
  not exist locally as deleted so that it will be removed on the server later."
- [x] Beyond the list, as Consul's rule per agent: a check ID is unique across a
  client, not only within its service. Done 2026-10-30, in `c33a2f2`
  (`ClientConfigurationTest.aCheckIdIsUniqueAcrossTheClientsServicesAsConsulsIsPerAgent`).
  Design section 16.

**Exit:** none of its own; the list's rules, with each endpoint tested over real
HTTP. Not met yet: local checks only, the 352 tests of the client and common
packages with the contract tests, and the changed classes five times in a row. The
full suites run on Jenkins once the owner has pushed this.

### Phase 5. TTL status and maintenance

- [ ] `PUT /v1/client/check/pass/{checkId}`, `warn`, and `fail` (each with an
  optional note), and `PUT /v1/client/check/update/{checkId}`, all through
  `LocalStatusReporter`.
- [ ] Node maintenance with `PUT /v1/client/maintenance`, and service
  maintenance with `PUT /v1/client/service/maintenance/{serviceId}`. Both
  publish `maintenance` observations and persist across a restart.

### Phase 6. Client information

- [ ] `GET /v1/client/self`: node name and ID, version, configuration with
  secrets redacted, server contact, and readiness conditions.
- [ ] `GET /v1/client/members`: nodes and their status from the servers' catalog.
  Qraft has no gossip pool, so this is a forwarded read.
- [ ] `PUT /v1/client/leave`: the existing bounded graceful deregistration,
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
  check through the client on its own host, reports passing, and is discovered through
  another host's client.
- [ ] End-to-end scenario 2: the application deregisters cleanly.
- [ ] End-to-end scenario 3: API registrations survive a client restart, and
  the local API keeps working across a server leader change.
- [ ] End-to-end scenario 4: a host rebuilt under the same name re-registers
  only after its old node becomes unreachable. The old node's services are
  gone from discovery once it does, and the rebuilt host's own are present.
- [ ] Docker: two client containers whose applications use only `localhost`
  (decision 9).
  - Each application runs in its client's network namespace and reaches the
    local API at `127.0.0.1`, the default bind.
  - An application registers through one client and is discovered through
    the other.
  - From a container outside a client's namespace, on the same compose
    network, that client's local API port refuses the connection.
- [ ] Document in `docker/README.md` that a container outside the client's
  network namespace needs the bind-address setting, and what that exposes
  while the local API has no authentication.
- [ ] Check that the design's sections 4.3, 5, 6.2, 7.3, 8, 12.1 and 16 were
  updated in their phases: applications talk to their local client, node
  identity, and the path split.
- [ ] Update the feature validation and the Consul plan checklist for what this
  list delivers, and the Docker client example. Their node identity entries were
  updated on 2026-10-30.

**Exit:** Full suites on a fresh image; changed concurrency tests pass five
consecutive runs; audits as in the other lists.

## 5. Out of scope

- DNS discovery (decision 4).
- Authentication and ACLs, on the local API and on the server. Until they
  exist, decision 8 detects a conflicting node identity but cannot stop a
  caller from claiming one. Proposed in
  [`task-list-acl-and-tokens-2026-10-31.md`](task-list-acl-and-tokens-2026-10-31.md).
- Forwarding key/value, sessions, consistency modes, and blocking queries. Each
  is added when the corresponding server feature lands.
- Response caching in the client, gossip, and configuration-file reload.
