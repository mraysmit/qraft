# Task List: Catalog Identity and Registration Protocol

**Date:** 2026-09-22
**Archive status:** Closed and archived on 2026-09-24.
**Status:** Complete (verified 2026-09-24)
**Source plan:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), Tranches 1 and 2
**Predecessor:** [`task-list-22-sep-2026.md`](task-list-22-sep-2026.md) (Tranche 8, complete)
**Successor:** [`task-list-agent-catalog-client-2026-09-24.md`](../task-list-agent-catalog-client-2026-09-24.md)
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## Why this is next

Every remaining feature in the Consul plan and the platform design keys on the
durable identity of a service instance: the agent catalog client, reconciliation,
health propagation through Raft, sessions, and tenancy. The platform design
requires legacy fixtures and an upgrade test before any durable identity change,
and that cost rises with every registration format that ships. The items deferred
from Tranche 8 (composite identity, structured error envelope, applied-index
header) all land here.

## Current state (verified 2026-09-22)

- `ServiceInstance` in `qraft-distributed-state` has `serviceId`, `serviceName`,
  `nodeId`, `address`, `port`, `tags`, `metadata`, `health`. No tenant, namespace,
  datacenter, region, or enabled state.
- `ServiceCatalog` keys instances by `serviceId` alone. Two nodes registering the
  same local service ID overwrite each other. `deregister(serviceId)` is not
  node-scoped.
- `ServiceInstanceProto` in `commands.proto` uses fields 1 through 8. Field 8 is
  `health` as a string, and the register command carries it, so a caller can set
  authoritative health.
- `HttpApiServer.registerService` deserializes the request body directly into
  `ServiceInstance`. There is no request DTO, and `deregisterService` takes only a
  service ID path parameter.
- Error responses are ad hoc JSON maps. `outcome_unknown` carries `retryable`
  (added in Tranche 8); `leader_unavailable`, `invalid_registration`,
  `catalog_unavailable`, and `method_not_allowed` do not. No `requestId`.
- Reads do not expose an applied-index header.
- `QraftStateStore.takeSnapshot` serializes the catalog as JSON through Jackson,
  including a list of `ServiceInstance` records. Restore calls
  `serviceCatalog.replaceAll`.
- No immutable command or snapshot fixtures exist under test resources.
- Existing tests: `ServiceCatalogTest`, `ServiceCatalogValidationTest`,
  `CatalogCommandCodecTest`, `ControllerStateStoreTest`, `HttpApiServerTest`.

## Rules for this work

- Red before green. Every behavioural change starts with a failing test.
- No Mockito. Real codec, real state store, real HTTP server on a bound port.
- Protobuf field numbers 1 through 8 in `ServiceInstanceProto` are never reused or
  renumbered. New fields are appended.
- Legacy fixtures are captured from the current code before any change and are
  never regenerated.
- Each step leaves the reactor green and is independently revertible.

## Step 1: Freeze legacy fixtures

**Purpose.** Prove, not assume, that existing on-disk data survives the identity
change.

**Tasks.**

1. Add `qraft-controller/src/test/resources/fixtures/catalog/` with a short
   `MANIFEST.md` listing each file, the commit it was produced at, and a SHA-256.
2. Using the current `ProtobufCommandCodec`, encode and store:
   - a register command with tags, metadata, and health `PASSING`;
   - a register command with empty tags and metadata and health `UNKNOWN`;
   - a deregister command.
3. Using the current `QraftStateStore.takeSnapshot`, store one snapshot containing
   two instances of the same service on different nodes, one agent, and a
   non-zero last-applied index.
4. Write a test that decodes every fixture with the current code and asserts the
   exact field values. This test must pass before Step 2 and keep passing after.

**Exit gate.** Fixtures committed with manifest and hashes; fixture test green.

## Step 2: Composite instance identity

**Purpose.** Two nodes can register the same local service ID without collision.

**Red tests, in order.**

1. `ServiceCatalogTest`: register `web` from `node-a` and `web` from `node-b`;
   `instances("web")` returns both, ordered deterministically by
   (tenant, namespace, nodeId, serviceId).
2. `ServiceCatalogTest`: re-registering the same composite identity replaces the
   instance and does not add a duplicate.
3. `ServiceCatalogTest`: `deregister` requires node identity; deregistering
   `web` on `node-a` leaves `web` on `node-b` untouched.
4. `ServiceCatalogValidationTest`: tenant and namespace default to `default` when
   omitted; blank tenant, namespace, or datacenter when supplied is rejected;
   `enabled` defaults to true.
5. `CatalogCommandCodecTest`: a register command with all new fields round-trips
   through protobuf.
6. Fixture test from Step 1: the legacy register fixtures decode with tenant and
   namespace `default`, datacenter and region empty, `enabled` true. The legacy
   snapshot restores both instances and re-serializes deterministically.

**Implementation.**

1. Add `tenantId`, `namespace`, `datacenter`, `region`, `enabled` to
   `ServiceInstance` with a compact constructor that applies defaults.
2. Add a `ServiceInstanceId` value type for (tenantId, namespace, nodeId,
   serviceId) and a `ServiceKey` for (tenantId, namespace, serviceName).
3. Re-key `ServiceCatalog` on `ServiceInstanceId`. Change `deregister` and
   `setHealth` to take the composite identity. Update `INSTANCE_ORDER`.
4. Append `tenant_id = 9`, `namespace = 10`, `datacenter = 11`, `region = 12`,
   `enabled = 13` to `ServiceInstanceProto`. Add `node_id = 4` and
   `tenant_id = 5`, `namespace = 6` to `CatalogCommandProto` for deregistration.
5. Update `ProtobufCommandCodec` and `QraftStateStore.applyCatalogCommand`.
6. Confirm Jackson tolerates the legacy snapshot's missing fields through the
   record defaults; add an explicit `@JsonCreator` or mixin only if the fixture
   test proves it necessary.

**Exit gate.** All six red tests green; fixture test still green; full reactor
green.

## Step 3: Registration request DTO and server-owned fields

**Purpose.** Separate the wire contract from stored state. The caller cannot set
health or Raft indexes.

**Red tests (real HTTP against a single-node Raft state machine).**

1. A register body containing `"health": "PASSING"` is accepted, but the stored
   instance has health `UNKNOWN`.
2. A register body omitting tenant and namespace stores `default` for both.
3. A register body with an unknown field is rejected with `400` and code
   `invalid_registration`, or accepted and ignored; pick one and test it. The
   platform design leaves aliases open, so document the choice in the response
   test.
4. Response body uses one stable schema: `serviceId`, `serviceName`, `nodeId`,
   `tenantId`, `namespace`, and `registered`. No stored-object leakage.
5. `PUT /v1/agent/service/deregister/{serviceId}` requires node identity from a
   header or query parameter; missing identity is `400`. Deregistering an absent
   instance returns `200` with `"deregistered": false`, not `404`, because the
   design makes it an idempotent no-op.

**Implementation.**

1. Add `ServiceRegistrationRequest` and `ServiceRegistrationResponse` DTOs in the
   controller HTTP package. Map explicitly to `ServiceInstance` with health forced
   to `UNKNOWN`.
2. Add a `DeregistrationRequest` mapping for the node-scoped path.
3. Decide the identity source for tenant, namespace, and node in the interim:
   explicit headers (`X-Qraft-Tenant`, `X-Qraft-Namespace`, `X-Qraft-Node`) behind
   a `RequestContext` interface, as the platform design section 15 prescribes, so
   token authentication can replace it later without touching commands.

**Exit gate.** HTTP tests green; `HttpApiServerTest` still green.

## Step 4: Structured error envelope and retry classification

**Purpose.** Clients branch on `code` and `retryable`, never on message text.

**Red tests.**

1. Every error response from `HttpApiServer` contains `code`, `message`,
   `retryable`, and `requestId`. Cover `invalid_registration` (not retryable),
   `leader_unavailable` (retryable, with `leaderId` and `X-Qraft-Leader-Id` when
   known), `outcome_unknown` (retryable), `catalog_unavailable` (retryable),
   `method_not_allowed` (not retryable), and `draining` (retryable).
2. `requestId` echoes an inbound `X-Request-Id` header when present and is
   generated otherwise. The same value appears in the MDC for the request's log
   lines.
3. A validation failure inside command application (for example, health update
   for an unknown instance) maps to `409` with a not-retryable code rather than
   `503`.

**Implementation.**

1. Add an `ErrorResponse` record and one `respondError` helper. Replace every ad
   hoc error map, including the `outcome_unknown` block added in Tranche 8.
2. Rename the JSON key from `error` to `code`. Keep `error` as a duplicate key for
   one release if any script or test depends on it; note the removal date.
3. Propagate `X-Request-Id` into MDC on the HTTP handler thread, matching the gRPC
   server's existing behaviour.

**Exit gate.** No error path in `HttpApiServer` returns a body without the four
envelope fields.

## Step 5: Applied-index metadata on reads

**Purpose.** A client that retries after `outcome_unknown` can confirm the retry
landed, and later blocking queries have a cursor.

**Red tests.**

1. `GET /v1/catalog/services`, `/v1/catalog/service/{name}`, and
   `/v1/health/service/{name}` return `X-Qraft-Index` equal to the state
   machine's last-applied index.
2. After a committed registration, the header value on the next read is strictly
   greater than the value before it.
3. Service and instance lists are deterministically ordered by the composite key.

**Implementation.** Expose `lastAppliedIndex` from `QraftStateStore` through the
existing state-machine interface and set the header in the three read handlers.

## Step 6: Retry convergence with the new identity

**Purpose.** Re-verify the Tranche 8 retry scenario under composite identity.

**Red tests.**

1. In-process: register `web` on `node-a` twice with a gated append between them;
   the catalog holds exactly one instance for that composite identity.
2. In-process: register `web` on `node-a` and `web` on `node-b` concurrently
   through the sequencer; both exist.
3. `DockerDurableRestartTest.retryAfterLeaderCrashConvergesToOneCatalogInstance`
   is updated to send tenant, namespace, and node identity and to assert on the
   composite key. It must still pass.

## Step 7: Documentation

1. Update the platform design section 4.4 to remove the resolved model gaps and
   section 12.1 to show the final request and response schemas and headers.
2. Update the Consul plan checklist: tick "service registration, catalog
   replication, and query behavior" only if it was not already ticked for the
   pre-identity version; otherwise add a line for composite identity.
3. Add an entry to `RAFT_STORAGE_OPERATIONS.md` under migration stating that
   snapshots and WAL entries written before this change load with `default`
   tenant and namespace, and that no operator action is required.
4. Record the alias and unknown-field decision from Step 3 as an architecture
   decision in the platform design's open decisions list.

## Running order and gates

| Step | Depends on | Gate |
|---|---|---|
| 1 Fixtures | None | Fixtures committed with manifest; fixture test green on current code |
| 2 Composite identity | Step 1 | Two nodes hold the same serviceId; legacy fixtures decode with defaults |
| 3 Request DTO | Step 2 | Caller cannot set health; node-scoped idempotent deregistration |
| 4 Error envelope | None (can run in parallel with 2 and 3) | Every error carries `code`, `message`, `retryable`, `requestId` |
| 5 Applied index | Step 2 | `X-Qraft-Index` monotonic across a committed write |
| 6 Retry convergence | Steps 2, 3, 4 | In-process and Docker convergence tests green |
| 7 Documentation | Steps 1 to 6 | Design documents match shipped schema |

Steps 1 and 4 can start immediately and in parallel. Step 4 touches only the HTTP
layer and has no dependency on identity.

## Out of scope for this list

- Agent catalog client, controller seed rotation, and reconciliation (platform
  design Tranches 3 and 4). They consume the protocol defined here.
- Health observation commands and TTL expiry (Tranche 6).
- Key/value HTTP API (Consul plan Phase 2). It will reuse the tenant and namespace
  scoping and the error envelope from this list.
- Token or certificate authentication. The `RequestContext` seam from Step 3 is
  the boundary it will replace.
- Event architecture Phase 1. Independent; may run in parallel if capacity allows.

## Acceptance

This list is complete when:

- two nodes can register the same local service ID and both are discoverable;
- legacy command and snapshot fixtures decode and restore with documented defaults;
- a registration request cannot set authoritative health;
- deregistration is node-scoped and idempotent;
- every HTTP error carries `code`, `message`, `retryable`, and `requestId`;
- reads expose the applied index;
- the Tranche 8 retry convergence test passes under composite identity;
- the full reactor and the Docker-tagged suite pass with no Mockito.
