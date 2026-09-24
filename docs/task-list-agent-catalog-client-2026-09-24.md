# Task List: Agent Catalog Client and Reconciliation

**Date:** 2026-09-24
**Active work:** Client catalog adapter and reconciliation (platform design Tranches 3 and 4)
**Source plan:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md)
**Predecessor:** [`archive/task-list-catalog-identity-2026-09-22.md`](archive/task-list-catalog-identity-2026-09-22.md) (Tranches 1 and 2, complete)
**Standards:** [`PROJECT_STANDARDS.md`](PROJECT_STANDARDS.md)

This is the current task list for the project. When the active work is complete,
add a completion summary, move this file to `archive/`, and start a new dated
task list for the next backlog item.

## 1. Project status

| Track | Item | Status |
|---|---|---|
| Storage | Tranche 0: WAL and snapshot contracts (RaftLog externalisation) | Done 2026-09-22 |
| Storage | Tranche 8 of the externalisation plan: system and failure verification | Done 2026-09-22 |
| Catalog | Tranche 1: composite catalog identity | Done 2026-09-24 |
| Catalog | Tranche 2: registration protocol, error envelope, `X-Qraft-Index` | Done 2026-09-24 |
| Agent | Tranche 3: client catalog adapter | **Active** (section 3) |
| Agent | Tranche 4: reconciliation and readiness | **Active** (section 3) |
| Runtime | Tranche 5: unified runtime flow | Backlog |
| Health | Tranche 6: health propagation and automatic deregistration | Backlog |
| Acceptance | Tranche 7: multi-node container acceptance | Backlog |
| Features | Consul plan Phase 2: key/value HTTP API | Backlog |
| Features | Consul plan Phase 5: sessions and locks | Backlog |
| Features | Consul plan Phase 6: read consistency modes and write forwarding | Backlog |
| Features | Consul plan Phase 7: ACLs, policies, audit | Backlog |
| Features | Consul plan Phase 8: metrics, DNS, deployment examples | Backlog |
| Events | Event architecture Phases 1 and 2: contracts and bounded local source | Backlog |
| UI | Administrative UI Stage 1: operational discovery | Backlog |

## 2. Current state of the agent (verified 2026-09-24)

- `QraftAgent` registers **only its own node identity** with
  `POST /api/v1/agents/register`, sends heartbeats to `/api/v1/agents/heartbeat`,
  and deregisters with `DELETE /api/v1/agents/{agentId}`. It never calls the
  `/v1/agent/service/*` catalog API, and it has no concept of service definitions.
- A failed initial registration keeps the local health server live and unready and
  retries at the fixed heartbeat interval. There is no backoff or jitter.
- `AgentConfiguration` accepts one `controllerUrl`. There are no seeds, no
  failover, and no tenant or namespace.
- The current executable and Dockerfiles still read legacy environment variables
  (`AGENT_ID`, `CONTROLLER_URL`, `QRAFT_MODE`, and others). Section 17 now
  prohibits all environment-variable configuration. Step 1 removes these paths
  rather than renaming them.
- An invalid numeric setting silently falls back to its default instead of
  failing startup.
- `AgentRegistrationClient.send` turns every transport failure into `null`, so
  callers cannot tell a refused connection from a timeout or a `503`. The client
  ignores the error envelope's `code` and `retryable` fields.
- `HeartbeatService` creates its own `HttpClient` that nobody closes.
- `shutdown()` stops the scheduler and health server **before** deregistering.
  `close()` joins that deregistration with no deadline.
- `agentId` defaults to `agent-<hostname>`. The design (section 8.3) requires a
  stable node ID that is persisted locally when it is generated.
- The controller's `leader_unavailable` and `outcome_unknown` responses carry the
  leader's **node ID**, not a URL. The client therefore cannot redirect to the
  leader and must rotate through its seeds.

Existing tests: `QraftAgentTest`, `AgentRegistrationClientTest`,
`HeartbeatServiceTest`, `HealthServiceTest`, `AgentConfigurationTest`, and
`qraft-runtime`'s `AgentControllerContractTest` (real agent against a real
single-node controller).

## 3. Active work: client catalog adapter and reconciliation

### Rules

- Qraft does not use environment variables for configuration. Runtime settings
  come from one versioned JSON file named by the mandatory `--config <path>`
  argument; the path itself is never sourced from the environment.
- Red before green. Every behavioural change starts with a failing test.
- No Mockito. Outbound HTTP is tested against a real JDK `HttpServer` fixture.
  Agent-to-controller behaviour is tested in `qraft-runtime` against a real
  `HttpApiServer` and Raft node.
- Time and randomness (backoff, jitter, contact freshness) are injected, so tests
  are deterministic and never sleep for a backoff interval.
- `qraft-agent` must not depend on `qraft-controller`. Wire types the agent needs
  are defined on the client side or in `qraft-core`.
- Each step leaves the reactor green and can be reverted on its own.

### Step 1: Client configuration

**Purpose.** Give the agent the inputs that reconciliation needs, and fail fast
on invalid configuration.

**Red tests.**

1. Configuration is parsed from an injected JSON document; production code never
   reads `System.getenv`.
2. `controllers.urls` parses into an ordered, deduplicated list of URIs.
   An empty list, a malformed URI, or a non-HTTP scheme is rejected.
3. `catalog.tenant` and `catalog.namespace` default to `default`; a blank value that
   is explicitly supplied is rejected.
4. A non-numeric or out-of-range timeout, interval, or port fails `build()`
   instead of falling back.
5. `catalog.registrationRetryMinMs`, `catalog.registrationRetryMaxMs`, and
   `controllers.requestTimeoutMs` are validated, and min must not exceed max.
6. Service definitions load from the main document's `catalog.services` array. They
   cover duplicate service IDs, missing required fields, an invalid port, and an
   empty or absent array (which is valid: an agent with no services).

**Implementation.**

1. Remove environment-variable configuration from the runtime, agent, controller,
   Dockerfiles, entrypoints, and maintained Compose manifests. Add the mandatory
   `--config <path>` option and mount one versioned JSON document per process.
   Do not add a configuration-path environment variable or environment
   substitution syntax.
2. Add the `ServiceDefinition` value type to `qraft-core` (design sections 1.1
   and 8.4). It holds the local service ID, name, address, port, tags, metadata,
   and enabled state. It carries no health and no Raft index.
3. Use the main JSON configuration document as the initial service-definition
   source (see decision D1 in section 5).

**Exit gate.** Invalid configuration fails before any thread or socket is
created. No production source, Dockerfile, entrypoint, maintained Compose file,
or current documentation uses environment variables for Qraft configuration.

### Step 2: `CatalogClient` and `HttpCatalogClient`

**Purpose.** One outbound adapter for the catalog API, with outcomes callers can
act on.

**Red tests (real JDK `HttpServer` fixture).**

1. `register` sends `PUT /v1/agent/service/register` with the `X-Qraft-Node`,
   `X-Qraft-Tenant`, and `X-Qraft-Namespace` headers and exactly the body schema in
   design section 13.1. It never sends a `health` field.
2. `deregister` sends `PUT /v1/agent/service/deregister/{serviceId}` with the same
   identity headers. A `200` with `"deregistered": false` counts as success.
3. Every request sends an `X-Request-Id`, and each attempt gets a new one.
4. Outcome classification:
   - `Success`: 2xx with a parsed response;
   - `Retryable`: connection refused or reset, request timeout, HTTP 429, 502,
     503, or 504, or an error envelope with `retryable: true` (`draining`,
     `leader_unavailable`, `outcome_unknown`, `catalog_unavailable`, `fenced`);
   - `Rejected`: an error envelope with `retryable: false` (for example
     `invalid_registration`), carrying `code` and `message`;
   - a malformed or non-envelope error body is `Retryable` for 5xx and
     `Rejected` for 4xx.
5. A `leaderId` in an error response is exposed as a hint on the outcome.
6. The request timeout is honoured and a timed-out request fails within it.
7. The client closes its `HttpClient` exactly once, and a call after `close()`
   fails immediately.

**Implementation.** Add `CatalogClient`, `HttpCatalogClient`, and a sealed
`CatalogOutcome` in `qraft-agent`. Map responses by `code` and `retryable`, never
by message text.

**Exit gate.** A contract test in `qraft-runtime` registers and deregisters a
service against a real `HttpApiServer` and checks the catalog through
`GET /v1/catalog/service/{name}`.

### Step 3: Controller-seed rotation and backoff

**Purpose.** Registration succeeds when the first configured server is a follower
or offline (design section 22 acceptance criterion).

**Red tests.**

1. For an idempotent operation, a `Retryable` outcome moves to the next seed.
   Each seed is tried at most once per cycle.
2. A `Rejected` outcome is returned immediately and never tried against another
   seed.
3. The last seed that succeeded is tried first next time.
4. Retry delay between cycles grows exponentially from the configured minimum to
   the maximum, with jitter, under an injected clock and random source.
5. `outcome_unknown` on registration is retried, because registration is an
   idempotent update of the composite identity.
6. Cancellation during a backoff wait stops the retry promptly.

**Implementation.** Add a `ControllerEndpoints` selector and a retry policy used
by `HttpCatalogClient`. Route the existing node registration and heartbeat calls
through the same selector and classification, and delete their private
`send`-to-`null` handling and the extra `HttpClient`.

**Exit gate.** In `qraft-runtime`: with three configured seeds where the first
refuses connections and the second returns `leader_unavailable`, registration
commits through the third.

### Step 4: Single-flight reconciliation

**Purpose.** Replace one-shot startup registration with periodic convergence
(design section 9.2).

**Red tests (fake `CatalogClient`, injected scheduler trigger and clock).**

1. The first pass registers every enabled definition. Disabled definitions are
   not registered.
2. A partial failure keeps the successful registrations. The next pass retries
   only the missing ones and never rolls back committed ones.
3. An unchanged definition is not re-sent; a stable content fingerprint detects
   changes.
4. A changed definition is re-registered, and only that one.
5. A service that the controller reports absent (for example, deleted by an
   administrator) is registered again on the next pass.
6. Two overlapping triggers never run two passes concurrently.
7. A `Rejected` definition is reported and not retried every cycle until its
   definition changes.

**Implementation.** Add `ServiceReconciler`, owned by `QraftAgent`, scheduled on
the agent's scheduler. Detecting absence (test 5) needs a read. Use
`GET /v1/catalog/service/{name}` filtered by node, or record decision D2 if a
node-scoped read endpoint is required instead.

### Step 5: Readiness policy

**Purpose.** Readiness reflects whether the agent is actually serving its purpose
(design section 7.2).

**Red tests.**

1. The agent is live from start to shutdown, including through a complete
   controller outage.
2. The agent is ready only when its node registration is accepted, every
   **required** enabled definition is committed, and the last successful
   controller contact is within a configured freshness window.
3. Losing contact beyond the window makes the agent unready without stopping
   reconciliation. Recovering makes it ready again.
4. An agent with no service definitions becomes ready on node registration alone.

**Implementation.** Replace the direct `HealthService.setReady` calls with a
readiness function evaluated from reconciler and contact state.

### Step 6: Bounded graceful shutdown

**Purpose.** Implement the order in design section 9.4 with a total deadline.

**Red tests.**

1. Shutdown marks the agent unready before anything else.
2. No reconciliation pass or heartbeat starts after shutdown begins.
3. Every registered service is deregistered, then the node, within the deadline.
4. An unreachable controller cannot extend shutdown beyond the deadline, and the
   incomplete deregistration is logged once.
5. The local HTTP server stops after deregistration. The scheduler and HTTP client
   are closed and their threads have terminated.
6. Repeated or concurrent `shutdown()` calls are idempotent.

**Implementation.** Reorder `QraftAgent.shutdown` and make `close()` bounded.

### Step 7: End-to-end verification

1. `qraft-runtime`: a real agent with two service definitions registers both
   against a single-node controller, becomes ready, survives a controller restart
   by reconciling, and deregisters both on shutdown.
2. `qraft-runtime`: two agents register the same local service ID `web`, and both
   instances are discoverable.
3. `qraft-runtime`: an in-process three-node cluster, with the first seed a
   follower, accepts the agent's registration. This may need the controller's
   in-memory transport fixture exposed through a test-jar; do that rather than
   duplicating it.
4. Full reactor and the Docker-tagged suite pass.

### Step 8: Documentation

1. Platform design section 5.3: describe the catalog client, seeds, and
   reconciliation as current behaviour, and mark Tranches 3 and 4 complete.
2. Section 17: document the final JSON schema and the prohibition on environment
   variables.
3. Consul plan checklist: tick "Complete client-mode configuration and controller
   discovery behavior".
4. `docker/README.md`: show a mounted client configuration file and an explicit
   `client --config /etc/qraft/client.json` command.
5. Record decisions D1 to D3 in the platform design's open-decisions list.

### Running order and gates

| Step | Depends on | Gate |
|---|---|---|
| 1 Configuration | None | Invalid config fails before resources open; no old variable names |
| 2 Catalog client | Step 1 (`ServiceDefinition`) | Real-controller register/deregister contract test green |
| 3 Seed rotation | Step 2 | Registration commits through the third of three seeds |
| 4 Reconciliation | Steps 2, 3 | Partial registration retained; single-flight proven |
| 5 Readiness | Step 4 | Live through outage; ready only under policy |
| 6 Shutdown | Steps 4, 5 | Ordered, bounded, idempotent |
| 7 End to end | Steps 1 to 6 | Runtime and Docker suites green |
| 8 Documentation | Step 7 | Docs match shipped behaviour |

### Out of scope for this work

- Health checks, TTL renewal, and server-side expiry (Tranche 6). The agent keeps
  reporting node-level heartbeats only.
- Server-side write forwarding. Seed rotation is the client behaviour for now.
- Persisting a generated node ID (design section 8.3). It is tracked in the
  backlog and does not block this work, because `agent.id` is set explicitly in
  every supported configuration file.
- Moving node registration from `/api/v1/agents/*` to a `/v1/` route.

## 4. Backlog

In intended order. Each item gets a detailed step list when it becomes active.

1. **Tranche 5: unified runtime flow.** Replace `QraftRuntimeApplication`'s
   static `main` delegation with injectable mode launchers. Start one server and
   one client through the runtime boundary under test.
2. **Tranche 6: health propagation.** Health observation and expiry commands,
   agent-side TTL, HTTP, and TCP checks, leader-owned expiry proposals, and
   automatic deregistration.
3. **Tranche 7: multi-node container acceptance.** Three servers and one client
   from one image; leader replacement and restart recovery.
4. **Stable node identity.** Persist a generated node ID locally (design
   section 8.3).
5. **Key/value HTTP API** (Consul Phase 2). Reuse the tenant and namespace
   scoping, error envelope, and `X-Qraft-Index`.
6. **Event contracts and bounded local source** (event architecture Phases 1
   and 2). Independent of the agent work; can run in parallel.
7. **Sessions and locks** (Consul Phase 5). Depends on key/value.
8. **Read consistency modes and write forwarding** (Consul Phase 6).
9. **Administrative UI Stage 1.** Depends on the read APIs and event query.
10. **Security and tenancy** (Consul Phase 7). Replaces `HeaderRequestContext`
    with authenticated identity.
11. **Operations** (Consul Phase 8). Prometheus metrics, configuration
    reference, deployment examples, optional DNS.

### Scheduled clean-ups

| Item | Due |
|---|---|
| Remove the deprecated duplicate `error` key from the error envelope | First release after 2026-12-31 |
| Stop accepting the ignored `health` field on registration | With the `error` key removal |
| Move legacy `/api/v1/info` and `/api/v1/agents/*` routes under `/v1/` | With Tranche 6 node-health work |
| Decide how long legacy JSON WAL entries and pre-identity snapshots stay readable | Open decision, platform design section 23 |

## 5. Open decisions for the active work

| ID | Decision | Proposed default |
|---|---|---|
| D1 | Initial service-definition source | The `catalog.services` array in the versioned JSON runtime configuration |
| D2 | How reconciliation detects an administratively deleted instance | Read `GET /v1/catalog/service/{name}` and filter by node; add a node-scoped endpoint only if that proves insufficient |
| D3 | Whether a service removed from the definitions file is deregistered on the next reconcile | Yes for services this agent process registered. Instances left behind by an earlier process wait for Tranche 6 expiry |
