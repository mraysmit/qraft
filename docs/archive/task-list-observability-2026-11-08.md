# Task List: Observability

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phases 1 to 4 are done, test first, and the final local run of the three suites passed on them: default 1,851 with every coverage gate met, end-to-end 22 and Docker 51, with no failures, errors or skips; audit over the 212 files written since the previous attempt: 669 error lines, all flagged, 382 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 as item 7 of the feature validation's order, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (Phase 4 recorded, and the list complete and archived. Earlier the same day: Phases 1 to 3 done and recorded; decision 4 and Phase 4's first item narrowed, as their records say; Phase 4's tests and documents written; started the same day)
**Follows:** [`task-list-tenancy-2026-11-08.md`](task-list-tenancy-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 18
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the operations rows and item 7
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

The signals design section 18 requires: catalog, health, key/value, session and lock counts; request counts and
latency by route; client reconciliation and readiness; a leader-side view of each server's replication; and the Raft
storage's snapshot and log size, all with bounded labels.

## 2. Where Qraft stands (2026-11-08)

- `RaftMetrics` publishes Raft RPC and thread-pool counts through OpenTelemetry's global meter, which a server exports
  on `server.telemetry.prometheusPort`.
- `/raft/status` reports the node's role, term and indexes; `/v1/operator/raft/configuration` lists the servers.
- Nothing counts catalog, health, key/value, session or request activity, and client mode publishes no metrics.

## 3. Reference: Consul

From Consul's telemetry and operator documentation:

- `consul.catalog.register`, `consul.catalog.deregister`, `consul.kvs.apply`, `consul.session_ttl.active`,
  `consul.session_ttl.invalidate`, and `consul.http.<method>.<path>` timings, with no per-key or per-service labels.
- `GET /v1/agent/metrics` answers an agent's metrics as JSON, or in Prometheus' text format with `?format=prometheus`.
- `GET /v1/operator/autopilot/health` answers, from the leader, each server's `LastContact`, `LastTerm`,
  `LastIndex`, `Healthy`, `Voter` and `Leader`, and the cluster's `Healthy` and `FailureTolerance`.

## 4. Decisions

Taken on 2026-11-08 as the recommended options, under the owner's instruction; each can be reversed.

1. **Servers use the OpenTelemetry meter they already export.** Every new instrument is created from an injected
   `Meter`, the global one in production and an in-memory reader's in tests, and is named `qraft.<area>.<name>`.
2. **State metrics are counted where commands are applied,** so every replica counts the same changes. Labels are
   bounded: a result, an operation, a health state, a reason; never a service, key, session or node name.
3. **Requests are counted by route, not path:** the route's registered prefix, the method and the status code, with a
   latency histogram by route and method.
4. **A client publishes at `GET /v1/client/metrics`,** Consul's `/v1/agent/metrics` under Qraft's name, as JSON or with
   `?format=prometheus` as text: reconciliation passes by result, readiness, and the age of its last successful contact
   with a server. It opens no telemetry port of its own. The server it last reached was left out, as the client keeps
   no such record and a server's address would make an unbounded label.
5. **The leader's view of replication is `GET /v1/operator/autopilot/health`,** in Consul's form, answered by the
   leader alone; another server answers 503 `leader_unavailable`. It needs `operator:read`.
6. **Storage status joins `/raft/status`** and the meter: the snapshot's index and age, and the log's size on disk.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. State metrics

- [x] An injectable meter for the state store, and the in-memory reader for tests.
- [x] Catalog registrations and deregistrations, health transitions by state, key/value applies by operation and
  result, active sessions, session invalidations by reason, and lock acquisitions by result.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 1 record, 2026-11-08.** `StateMetrics` holds the store's instruments: `qraft.catalog.registrations` and
`qraft.catalog.deregistrations` by `result`, `qraft.health.transitions` by `from` and `to`, `qraft.kv.applies` by
`operation` and `result`, the `qraft.session.active` gauge, `qraft.session.invalidations` by `reason` (`destroyed` or
`node`), and `qraft.lock.acquisitions` by `result` (`acquired` or `contended`). `QraftStateStore(Meter)` takes a meter;
the store without one resolves OpenTelemetry's global meter when it first applies a command, after the telemetry is
configured. `opentelemetry-sdk-testing` joins the test scope for its in-memory reader. `QraftStateStoreMetricsTest`
(2) was seen red 2 of 2, nothing counted, then green with the store's other tests, 37.

### Phase 2. Request and client metrics

- [x] Server requests by route, method and status, and their latency.
- [x] `GET /v1/client/metrics` on the client's local API, as JSON and as Prometheus text.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 2 record, 2026-11-08.** `RequestMetrics` counts `qraft.http.requests` by `route`, `method` and `status`, and
times `qraft.http.request.duration` in milliseconds by `route` and `method`. Every server route is wrapped. The route
is the prefix it was registered under, and a method outside HTTP's own is `OTHER`. `HttpApiServer.useMeter` gives a
test its own meter. `ClientMetrics` serves `/v1/client/metrics` under `client:read`: `qraft.client.ready`,
`qraft.client.last_contact_age_seconds` (-1 before any server answered), and `qraft.client.reconciliations` by
`result` (`clean`, `rejected`, `failed`), told of each pass by `ServiceReconciler.onPass`. The route answers as JSON in
Consul's form, or with `?format=prometheus` as text. `HttpApiServerRequestMetricsTest`, `ClientMetricsTest` and
`ClientMetricsRouteTest` were seen red 3 of 3, then green with their neighbours, 101 tests.

### Phase 3. Replication and storage status

- [x] `GET /v1/operator/autopilot/health` on the leader, from what it knows of each follower's last contact and index.
- [x] The snapshot's index and age and the log's size in `/raft/status` and as gauges.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Phase 3 record, 2026-11-08.** `RaftNode.serverHealth()` answers, on the leader only, a `ClusterHealth` of
`ServerHealth`s. The node records when each peer last answered in this leadership. A follower is healthy when it has
answered within an election timeout and holds the leader's log to within 250 entries, Consul's `MaxTrailingLogs`. The
failure tolerance is the healthy voters beyond a quorum. `GET /v1/operator/autopilot/health` answers it in Consul's
form under `operator:read`, and another server answers 503 `leader_unavailable`. `StorageStatus` reads the storage
directory: the bytes its files hold, and the age of `snapshot.dat`, or -1. The server reports both in `/raft/status`
as `storageBytes` and `snapshotAgeSeconds`, and as the gauges `qraft.raft.storage.bytes` and `qraft.raft.snapshot.age`.
The snapshot's index was already the gauge `qraft.raft.snapshot.last_index`. `RaftNodeServerHealthTest` (2),
`StorageStatusTest` and `HttpApiServerAutopilotHealthTest` (3) were seen red 6 of 6. The first green run failed one
test for an undeclared error: its own partition made the leader log h3 unreachable. The test now declares
`RAFT_PEER_UNREACHABLE`, as the read-barrier test does for its partition.

### Phase 4. Verification and documentation

- [x] A test that reads the state metrics through the Prometheus reader a server exports, and an end-to-end test of
  autopilot health, the storage status and a client's metrics on three servers. The servers built in one JVM by the
  end-to-end tests configure no telemetry, so the scrape is tested on its own.
- [x] A Docker test of autopilot health after a follower stops.
- [x] Design section 18, the feature validation's operations rows, the Consul plan, and the operations guide.

**Exit:** the three suites locally, audited, and the list complete and archived.

**Phase 4 record, 2026-11-08.** `PrometheusExportTest` scrapes the store's metrics through the Prometheus reader a
server exports, and finds them under Prometheus' names with their bounded labels and no service or key.
`ObservabilityEndToEndTest` (e2e) checks three servers: the leader reports all three healthy with a failure tolerance
of one, the followers answer 503, every server reports its storage's size, and a client counts a clean pass and
reports itself ready. `DockerAutopilotHealthTest`: on three containers, a stopped follower is reported unhealthy, with
the cluster unhealthy and no tolerance left. Its first run failed on the leader logging the stopped follower
unreachable, which the test causes; it now declares `RAFT_PEER_UNREACHABLE` and `RAFT_PEER_NAME_UNRESOLVED`, as the
other tests that stop containers do. The first final run failed one default test, `HttpApiServerRequestMetricsTest`,
which had passed alone. A first reading took it for the test reading counts before they were recorded. With a bounded
wait it still counted four of five requests. Run after the six classes the suite had run before it, it showed a
defect: the server keeps a request's route on its connection, and the next request on a kept-alive connection takes
it over as soon as the answer is sent. So a route read after the answer named the next request's route. The route and
method are now read before the request is handled. The design (section 18), the feature validation's metrics row, the
Consul plan and the operations guide are in line. The final run (logs `logs/obs-final3/`): default 1,851 with every coverage gate met, end-to-end 22 and Docker 51, with no failures, errors or skips; audit over the 212 files written since the previous attempt: 669 error lines, all flagged, 382 stack traces, none unflagged; no `too_many_pings`; no JVM crash file.

## 7. Out of scope

- Grafana dashboard panels for the new metrics; tracing changes; per-tenant metric labels; audit events.
