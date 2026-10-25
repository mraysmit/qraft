# Qraft Feature Validation

**Date:** 2026-09-27
**Last updated:** 2026-10-21 (membership Step 5 completed with runtime policy wiring and automatic failed-voter cleanup; five 332-test affected runs, 1,051 default tests with coverage and 42 runtime tests passed; retained-log audits clean; Steps 6 to 8 remain open; dated links refreshed. Earlier, 2026-10-14: single-POM plan Phase 7: native futures, executor ownership, test-helper removal, fenced-node diagnostics and the closed membership Step 4 gate; existing feature gaps remain open)
**Scope:** every server and client feature in
[`CONSUL_FEATURE_IMPLEMENTATION_PLAN.md`](CONSUL_FEATURE_IMPLEMENTATION_PLAN.md) and
[`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md),
checked against the code on the original date above, with dated updates below
**Purpose:** establish which platform features exist before the administrative
interface ([`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md))
goes further than its first increment

## 1. Summary

The runtime, Raft, the service catalog, health propagation, and the client
are implemented and verified, including multi-node container acceptance. Five
feature areas the plans require are missing or only partly present:

- the key/value store;
- sessions and locks;
- read consistency modes and blocking queries;
- tenancy isolation;
- security.

Operational status and metrics are incomplete. On the validation date, remnants
of the inherited job system remained in `qraft-core`, and the Consul plan's own
checklist was out of date in both directions; both were resolved on 2026-09-27
(section 4).

Rows changed since the validation date carry the date of the change.

Status key:

- **Done:** implemented and covered by tests.
- **Partial:** present, but short of the specification in the named respect.
- **Missing:** not implemented.

## 2. Server features

| Area | Feature | Status | Evidence or gap |
|---|---|---|---|
| Runtime | `qraft server` from one image; file configuration validated before resources open | Done | `QraftRuntimeApplication`, `AppConfig`, `QraftServerLifecycleTest` |
| Runtime | Native Java asynchronous contracts | Done | Updated 2026-10-14. `Future`, `Promise` and `AsyncResult` wrappers removed on 2026-10-12 (`9851987`, Jenkins build 26); production and tests use `CompletableFuture` / `CompletionStage`. `CompletionsTest`, `RaftStorageFactoryTest`, `ClusterBootstrapTest` |
| Runtime | Explicitly owned Raft state loop and bounded blocking executor | Done | Done 2026-10-13 (`7a9108f`; recorded 2026-10-14). `QraftServerApplication` owns `RaftStateLoop` and `BlockingTaskExecutor`; configured work/queue bounds, overload rejection, MDC/OpenTelemetry propagation and restoration. `BlockingTaskExecutorTest`, `RaftStateLoopTest`, `QraftServerLifecycleTest` |
| Runtime | Drain before shutdown; bounded shutdown | Done | `ShutdownCoordinator`, `RaftNodeShutdownSequencingTest`. Updated 2026-10-14: service stop, blocking-work drain, state-loop termination, then telemetry cleanup, including failed cleanup and interrupts (`QraftServerLifecycleTest`) |
| Runtime | Readiness that reflects recovery, fencing, draining, and leadership (design 6.1) | Done | `HttpApiServerReadinessTest`; done 2026-09-27. Since 2026-10-09 a server that does not campaign loses a silent leader too, and is unready (`RaftNodeServerIdCountingTest`) |
| Raft | Election, replication, check-quorum, fencing | Done | `RaftNode*Test`, `RaftNodeCheckQuorumTest` |
| Raft | Durable WAL, snapshots in every role, installation, recovery | Done | `RaftNodeFollowerLogTest`, `DockerDurableRestartTest`, `DockerClientRecoveryTest` |
| Raft | Replica determinism; follower match index limited to verified entries | Done | `ReplicaDeterminismTest`, `RaftNodeFollowerLogTest` |
| Raft | Adding or removing servers (membership change) | Partial | Updated 2026-10-24. Lost-quorum recovery (Step 7) is built and verified: the offline `inspect` and `recover` commands, their crash boundaries and one-time claim, and `DockerLostQuorumRecoveryTest` from current directories and from a backup, five consecutive runs and Jenkins build 33 (1,138 default, 10 end-to-end, 34 Docker tests). Its runbook is in `RAFT_STORAGE_OPERATIONS.md`. Earlier, 2026-10-21. Durable server IDs, replicated configuration, bootstrapping, non-voter promotion, joining, operator list/remove and leader stickiness are built. Membership parsing and all six runtime settings are wired and logged. `RaftNodeCleanupTest` covers automatic failed-voter cleanup, grace/interval boundaries, contact and leadership resets, quorum/minimum/budget limits, promotion triggers, cancellation, injected WAL failure and real-WAL reopening. Step 5 passed five consecutive 332-test affected runs, 1,051 default tests with coverage, 42 runtime tests and retained-log audits. Step 8's container scenarios are built and verified (replacement, grow and shrink, leader removal, cleanup, removed-server campaigns): three consecutive green Jenkins runs, builds 39 to 41. The one limitation recorded in design section 14.9, a long-stopped voter that returns deposing the leader, is closed by Pre-Vote (section 14.10, 2026-10-25). See [`task-list-raft-membership-changes-2026-10-24.md`](../docs/archive/task-list-raft-membership-changes-2026-10-24.md) |
| Catalog | Register and deregister through Raft; composite identity; idempotence | Done | `HttpApiServerTest`, `ClientEndToEndTest` |
| Catalog | Catalog and health queries with deterministic ordering | Done | `ServiceCatalog` sorts names and instances |
| Catalog | Registration and modification indexes on instances (design 7.5) | Missing | `ServiceInstance` has no index fields |
| Catalog | Reads scoped by tenant and namespace | Done | Catalog and health reads honour the scope headers; `HttpApiServerTest`, `ServiceCatalogTest`; done 2026-09-27 |
| Catalog | Node registry: a Consul-shaped node, written at `PUT /v1/catalog/register`, `/deregister`, and `/node/heartbeat`, and listed at `GET /v1/catalog/nodes` | Done | Done 2026-10-09 (single-POM list, Phase 4). The inherited capabilities, host name, port, and job-system statuses are removed, and what earlier versions wrote still reads. A node's deregistration removes its services. `HttpApiServerTest`, `NodeCodecTest`, `LegacyNodeFixtureTest` |
| Health | Ordered observations, derived service health, all five states | Done | `HealthCommandStateStoreTest`, `HealthPropagationEndToEndTest` |
| Health | Leader-owned expiry and automatic deregistration | Done | `LeaderHealthExpiry*Test`, `CrashedClientExpiryEndToEndTest` |
| Health | Node membership expiry: unreachable, then reaped | Done | `NodeExpiryEvaluatorTest`, `NodeMembershipExpiryStateStoreTest` |
| Key/value | Replicated put, get, delete, and list | Partial | gRPC `DistributedStateService` only, with string values |
| Key/value | HTTP `/v1/kv/{key}` (Consul plan phase 2) | Missing | No route |
| Key/value | Tenant and namespace scoping of keys (design 7.1, 10) | Missing | Keys are global |
| Key/value | Creation and modification indexes, flags, versioning | Missing | `DistributedStateCommand` has only `Put(key, value)` and `Delete(key)` |
| Key/value | Compare-and-set on modify index | Missing | — |
| Key/value | Deterministic prefix listing | Partial | gRPC `List` exists; ordering and scoping are unverified by tests |
| Sessions | Create, renew, destroy, replicated expiry (design 11) | Missing | No session model or command |
| Locks | Acquire and release on keys, owner enforcement, leader-election helpers | Missing | — |
| Consistency | `default`, `stale`, and `consistent` reads (design 13) | Missing | Every read is the answering node's applied state |
| Consistency | Blocking queries on the index (design 10, 12.3) | Missing | — |
| Consistency | Monotonic applied index on reads | Partial | `X-Qraft-Index` on catalog and health reads; not on client or Raft reads |
| Consistency | Leader forwarding for writes | Partial | Leader hint (`X-Qraft-Leader-Id`) and seed rotation; forwarding is out of scope by decision |
| API | Structured error envelope with request ID | Done | `code`, `message`, `retryable`, `requestId`; the deprecated `error` field is due for removal after 2026-12-31 |
| Tenancy | Tenant and namespace identity in catalog commands and keys | Done | Composite `ServiceInstanceId` |
| Tenancy | Namespace lifecycle, validation, and isolation end to end | Missing | No namespace model. `qraft-tenant`, whose in-memory `NamespaceService` nothing used, was removed on 2026-10-04 (updated 2026-10-05) |
| Security | Authentication, ACL tokens, policies, token middleware (Consul plan phase 7) | Missing | Trusted identity headers behind `HeaderRequestContext` only. One shared operator token (`server.operator.token`) guards Raft server removal, standing in for ACLs (updated 2026-10-02) |
| Security | Audit events | Missing | — |
| Operations | Prometheus metrics | Partial | Raft metrics only. The catalog, health-transition, request, and session metrics of design 18 are absent. Client mode publishes no metrics; the dashboard's panels for a `qraft_clients` metric that never existed were removed on 2026-10-10 (updated 2026-10-11) |
| Operations | Deployment files: Compose clusters, start scripts, and one observability stack | Done | One server with a client, three and five servers, a network-test cluster, and OpenTelemetry Collector, Tempo, Prometheus, Loki, and Grafana, alone or with three servers. `DockerStartCommandsTest` runs every start command and both stacks. Removed on 2026-10-10: the ELK, Fluentd, and Promtail stacks, the nginx load balancer, the logging demo scripts, and the hand-test data (added 2026-10-11) |
| Operations | Node and cluster status | Partial | `/raft/status` describes only the answering node. `GET /v1/operator/raft/configuration` lists the configured servers, with voter and leader flags (updated 2026-10-02). Still no reachability, lag, or quorum |
| Operations | Fenced-node reason and one-time transition failure diagnostic | Done | Done 2026-10-13 (`f737d6b`; recorded 2026-10-14). `/raft/status` exposes nullable `fenceReason`; the sequencer retains the first cause and logs one ERROR announcement with its original stack and caller context. Startup failures remain observable. `RaftTransitionSequencerTest`, `RaftNodeApplyFailureTest`, `RaftNodeConfigurationTest`, `RaftNodeMetadataSequencingTest`, `HttpApiServerTest`, `DockerDurableRestartTest`. Cluster-wide reachability, lag and quorum remain outside this completed diagnostic |
| Operations | Health aggregation | Done | Derived service health; `?passing` filter |
| Operations | Offline backup, corrupt-replica replacement, compatible-upgrade guidance | Done | [`RAFT_STORAGE_OPERATIONS.md`](../docs/RAFT_STORAGE_OPERATIONS.md); clarified 2026-10-03: lower-level legacy byte compatibility does not establish whole-node startup compatibility; an older backup cannot rejoin as the same voter |
| Operations | Lost-quorum recovery and whole-cluster backup restore | Missing | No supported procedure yet. Membership Step 7 must establish and test it; clarified 2026-10-03 |
| Operations | Event journal (design [`QRAFT_EVENT_ARCHITECTURE.md`](../docs/QRAFT_EVENT_ARCHITECTURE.md)) | Missing | No event code. `qraft-events` is to be a package of the one build, not a module (updated 2026-10-05) |
| Operations | DNS discovery (optional) | Missing | — |

## 3. Client features

| Feature | Status | Evidence or gap |
|---|---|---|
| `qraft client` from one image; file configuration validated first | Done | `ClientConfiguration`, `QraftRuntimeApplicationTest` |
| Local liveness and readiness from membership, convergence, required checks, and server contact | Done | `QraftClientTest`, `DockerClientRecoveryTest` |
| Node registration, heartbeats, capped backoff, seed rotation, preferred endpoint | Done | `RegistrationClientTest`, `HttpCatalogClientTest`. On the catalog's write paths since 2026-10-09 |
| Single-flight service reconciliation with fingerprints | Done | `ServiceReconcilerTest` |
| HTTP, TCP (with a warning threshold), and TTL checks; sequenced publication and renewal | Done | `*CheckRunnerTest`, `HealthPublisherTest` |
| Bounded graceful shutdown: checks, services, then node | Done | `QraftClientTest`, `DockerClientHealthTest` |
| Recovery from crash, partition, and whole-cluster outage | Done | `DockerClientRecoveryTest`, `DockerClientHealthTest` |
| A generated node identity persisted locally (design 7.3) | Missing | Deferred by the health-propagation list; `client.id` is required in configuration |
| Client metrics: reconciliation attempts, failures, endpoint, readiness (design 18) | Missing | The client has no metrics |
| Client libraries for key/value, sessions, and leader election | Missing | Depends on the server features |

## 4. Consistency of the plans with the code

- **Legacy concepts remained.** Resolved 2026-09-27 by
  [`task-list-platform-hygiene-and-readiness-2026-10-21.md`](../docs/archive/task-list-platform-hygiene-and-readiness-2026-10-21.md).
  The Consul plan's completion criteria require that no job or workflow concepts
  remain. `ClientStatus` still has
  `getJobAssignmentPriority()`, which nothing calls, and the work-scheduling
  states `IDLE`, `ACTIVE`, and `OVERLOADED`. The `ClientSystemInfo` Javadoc
  describes "capacity planning and job assignment". Those classes were removed
  on 2026-10-09, with the rest of the inherited node model (single-POM list,
  Phase 4).
- **The Consul plan's checklist was stale.** Reconciled 2026-09-27. Several
  unchecked items were done:
  - client membership and failure detection;
  - health checks and health-state propagation through Raft;
  - multi-node integration and failure-injection coverage.

  Snapshot, restore, upgrade, and recovery workflows are partly done. Server
  configuration and bootstrap are done; the membership work is built and verified through
  Step 8, and the list stays open for the owner to close. Disruptive-server
  validation was completed on 2026-10-21, lost-quorum recovery on 2026-10-24 and
  the container scenarios on 2026-10-24, and Pre-Vote (design section 14.10) on
  2026-10-25, which closes the one limitation they recorded. Failed-server cleanup was
  implemented on 2026-10-21; its verification record is in the membership list.

### Phase 7 runtime and diagnostic close-out, recorded 2026-10-14

This is Phase 7 of
[the single-POM cleanup list](../docs/archive/task-list-single-pom-and-quorus-removal-2026-10-21.md#phase-7-replace-the-vertx-shaped-async-layer),
whose exit was met on 2026-10-13. It is separate from the Consul plan's
security and tenancy phase.

- Native futures replaced the emulated future types at `9851987`, verified by
  Jenkins build 26. `Completions` retains the original failure at completion
  boundaries; [CompletionsTest](../src/test/java/dev/mars/qraft/common/concurrent/CompletionsTest.java)
  and [RaftStorageFactoryTest](../src/test/java/dev/mars/qraft/raft/storage/RaftStorageFactoryTest.java)
  cover that contract.
- `JavaRuntime` and `WorkerExecutor` were removed in favour of explicitly owned
  executors (`9a6fbd0`, `7a9108f`).
  [The executor coverage table](../docs/TESTING.md#state-loop-and-blocking-executor-changes)
  maps bounds, rejection, caller context, cancellation, timers, completion
  dispatch and shutdown to their regression tests.
- `JavaTestContextHelper` and `JavaRuntimeExtensionHelper` were removed
  (`d876349`). [ShutdownCoordinatorTest](../src/test/java/dev/mars/qraft/server/lifecycle/ShutdownCoordinatorTest.java)
  owns its state loop directly and uses
  bounded future waits; protocol fixtures borrow both resources from
  `RaftExecutorsFixture` and release them in ownership order.
- Fencing diagnostics and the bootstrap pre-write rejection correction are
  covered by the tests in the Operations row above (`f737d6b`). The native
  observability-log audit follow-up preserves full error output and requires
  specific attribution and matching recovery for the previously approved Tempo
  startup case (`DockerLogCaptureTest`, `DockerStartCommandsTest`).

Final local verification on 2026-10-13 passed 960 default tests with every
coverage gate and 42 runtime tests against the freshly packaged jar and rebuilt
image. Five consecutive affected Raft/HTTP runs passed 293 tests each. The
recorded audit of 322 retained logs, including 163 Docker archives, found no
unflagged error or exception header in the accepted output; earlier failed
captures remain separately recorded with their causes and fixes.
[TESTING.md](../docs/TESTING.md#phase-7-verification-record-2026-10-13) names the
final captures and links the TDD and log-audit evidence. These are local results,
separate from Jenkins build 26's earlier wrapper removal. No tests were rerun
for this documentation update, and the remaining feature gaps stay open.

## 5. Recommended order

The administrative interface is paused after UI-0 Step 1, its configuration and
route contract. The missing backend features come first, each as its own dated
task list, ordered by dependency and by what the interface's later increments
need:

1. **Hygiene.** Done 2026-09-27: the job-system remnants are removed from
   `qraft-core`, and the Consul plan's checklist is up to date.
2. **Server readiness.** Done 2026-09-27: readiness reflects recovery, fencing,
   draining, and leadership. Consistency-dependent readiness follows item 4.
3. **Key/value completion.** Tenant- and namespace-scoped keys with creation
   and modification indexes, flags, compare-and-set, deterministic prefix
   listing, and the HTTP `/v1/kv` API.
4. **Consistency.** `default`, `stale`, and `consistent` reads, and blocking
   queries on the applied index, for key/value and the catalog.
5. **Sessions and locks.** Replicated sessions with TTL and expiry, key
   acquire and release with owner enforcement, and a leader-election helper.
6. **Tenancy end to end.** Replicated tenants and namespaces with validation
   and lifecycle, and scoped reads for clients and key/value. Catalog and health
   reads are scoped as of 2026-09-27.
7. **Observability.** Catalog, health, request, client, and session metrics; a
   leader-side membership and replication status; and storage and snapshot
   status.
8. **Events.** The `qraft-events` bounded journal, phases 1 to 4 of the event
   architecture.
9. **Security.** Authentication, ACL tokens and policies, and audit events.
   The token and ACL core is proposed, as of 2026-10-05, to come earlier,
   straight after the client:
   [`task-list-acl-and-tokens-2026-10-21.md`](task-list-acl-and-tokens-2026-10-21.md).
10. **Deferred until decided.** DNS discovery, and persisted generated node
    identity. Server membership change was decided on 2026-09-29 and is in
    progress in
    [`task-list-raft-membership-changes-2026-10-24.md`](../docs/archive/task-list-raft-membership-changes-2026-10-24.md).

Items 3 to 5 complete the Consul plan's target feature set. Items 6 to 9 are
the prerequisites of the interface's increments UI-2 to UI-6.
