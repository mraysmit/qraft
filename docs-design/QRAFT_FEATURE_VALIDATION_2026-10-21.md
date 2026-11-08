# Qraft Feature Validation

**Date:** 2026-09-27
**Last updated:** 2026-11-07 (ACL list Phases 5 to 9 in the security row and item 9. Earlier, 2026-11-06: ACL list Phases 3 and 4, the settings and the server's enforcement, in the security row, now partial, and item 9; the ACL list's link renamed. Earlier, 2026-11-05: ACL list Phase 2, the authorizer, in the security row and item 9. Earlier, 2026-11-04: the ACL list started, and its replicated state built, in the security row and item 9. Earlier, 2026-11-01: applications that use only their local client verified by Jenkins build 62. Earlier the same day: discovery reads through the local API verified by Jenkins build 61. Earlier the same day: health aggregation verified by Jenkins build 60. Earlier, 2026-10-31: health aggregation built out: `?passing` excludes an unreachable node's instances. Earlier the same day: the client's own information verified by Jenkins build 59. Earlier the same day: health aggregation moved to Partial: `?passing` does not yet consider a node's status. Earlier the same day: services, checks, TTL status, maintenance and the client's own information through the local API, the client list's Phases 4 to 6. Earlier, 2026-10-30: the client section brought up to the client list's Phase 3 and `c33a2f2`: persisted node identity, the local API listener and client anti-entropy; item 10 of the order corrected. Earlier, 2026-10-26: Pre-Vote and its backoff-reset follow-up complete, noted under Raft. Earlier, 2026-10-21: membership Step 5 completed with runtime policy wiring and automatic failed-voter cleanup; five 332-test affected runs, 1,051 default tests with coverage and 42 runtime tests passed; retained-log audits clean; Steps 6 to 8 remain open; dated links refreshed. Earlier, 2026-10-14: single-POM plan Phase 7: native futures, executor ownership, test-helper removal, fenced-node diagnostics and the closed membership Step 4 gate; existing feature gaps remain open)
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
| Raft | Adding or removing servers (membership change) | Done | Updated 2026-10-24, when the membership list was archived complete with Steps 1 to 8 (this row was corrected from Partial on 2026-11-08). Lost-quorum recovery (Step 7) is built and verified: the offline `inspect` and `recover` commands, their crash boundaries and one-time claim, and `DockerLostQuorumRecoveryTest` from current directories and from a backup, five consecutive runs and Jenkins build 33 (1,138 default, 10 end-to-end, 34 Docker tests). Its runbook is in `RAFT_STORAGE_OPERATIONS.md`. Earlier, 2026-10-21. Durable server IDs, replicated configuration, bootstrapping, non-voter promotion, joining, operator list/remove and leader stickiness are built. Membership parsing and all six runtime settings are wired and logged. `RaftNodeCleanupTest` covers automatic failed-voter cleanup, grace/interval boundaries, contact and leadership resets, quorum/minimum/budget limits, promotion triggers, cancellation, injected WAL failure and real-WAL reopening. Step 5 passed five consecutive 332-test affected runs, 1,051 default tests with coverage, 42 runtime tests and retained-log audits. Step 8's container scenarios are built and verified (replacement, grow and shrink, leader removal, cleanup, removed-server campaigns): three consecutive green Jenkins runs, builds 39 to 41. The one limitation recorded in design section 14.9, a long-stopped voter that returns deposing the leader, is closed by Pre-Vote (section 14.10, 2026-10-25). See [`task-list-raft-membership-changes-2026-10-24.md`](../docs/archive/task-list-raft-membership-changes-2026-10-24.md) |
| Catalog | Register and deregister through Raft; composite identity; idempotence | Done | `HttpApiServerTest`, `ClientEndToEndTest` |
| Catalog | Catalog and health queries with deterministic ordering | Done | `ServiceCatalog` sorts names and instances |
| Catalog | Registration and modification indexes on instances (design 7.5) | Done | Since 2026-11-08 ([`task-list-catalog-indexes-2026-11-08.md`](../docs/archive/task-list-catalog-indexes-2026-11-08.md)): `createIndex` and `modifyIndex` kept by the catalog, snapshotted, and answered with each instance; `ServiceCatalogIndexTest`, `HttpApiServerCatalogIndexTest`, `ClientServerContractTest` |
| Catalog | Reads scoped by tenant and namespace | Done | Catalog and health reads honour the scope headers; `HttpApiServerTest`, `ServiceCatalogTest`; done 2026-09-27 |
| Catalog | Node registry: a Consul-shaped node, written at `PUT /v1/catalog/register`, `/deregister`, and `/node/heartbeat`, and listed at `GET /v1/catalog/nodes` | Done | Done 2026-10-09 (single-POM list, Phase 4). The inherited capabilities, host name, port, and job-system statuses are removed, and what earlier versions wrote still reads. A node's deregistration removes its services. `HttpApiServerTest`, `NodeCodecTest`, `LegacyNodeFixtureTest` |
| Health | Ordered observations, derived service health, all five states | Done | `HealthCommandStateStoreTest`, `HealthPropagationEndToEndTest` |
| Health | Leader-owned expiry and automatic deregistration | Done | `LeaderHealthExpiry*Test`, `CrashedClientExpiryEndToEndTest` |
| Health | Node membership expiry: unreachable, then reaped | Done | `NodeExpiryEvaluatorTest`, `NodeMembershipExpiryStateStoreTest` |
| Key/value | Replicated put, get, delete, and list | Done | `state.kv`, since 2026-11-08 ([`task-list-key-value-2026-11-08.md`](../docs/archive/task-list-key-value-2026-11-08.md)); `KvStateTest`, `KvReplicationTest`; the older gRPC `DistributedStateService` map is unchanged |
| Key/value | HTTP `/v1/kv/{key}` (Consul plan phase 2) | Done | On the server and through the client, since 2026-11-08; `HttpApiServerKvTest`, `ClientKvForwardingTest`, `KvEndToEndTest`, `DockerKvTest` |
| Key/value | Tenant and namespace scoping of keys (design 7.1, 10) | Done | By the scope headers; `KvStateTest`, `HttpApiServerKvTest` |
| Key/value | Creation and modification indexes, flags, versioning | Done | Raft indexes; `QraftStateStoreKvTest`, `KvReplicationTest` |
| Key/value | Compare-and-set on modify index | Done | Set and delete; `KvStateTest`, `HttpApiServerKvTest`, `KvEndToEndTest` |
| Key/value | Deterministic prefix listing | Done | Key order, and keys cut at a separator; `KvStateTest` |
| Sessions | Create, renew, destroy, replicated expiry (design 11) | Done | Since 2026-11-08 ([`task-list-sessions-and-locks-2026-11-08.md`](../docs/archive/task-list-sessions-and-locks-2026-11-08.md)); renewals reset the leader's timers and are not replicated, as in Consul, and a lapse is a replicated destroy; `QraftStateStoreSessionTest`, `SessionTtlExpiryTest`, `HttpApiServerSessionTest`, `ClientSessionForwardingTest` |
| Locks | Acquire and release on keys, owner enforcement, leader-election helpers | Done | Acquire, release, `LockIndex` and the lock delay since 2026-11-08; `HttpApiServerSessionTest`, `SessionEndToEndTest`, `DockerSessionTest`. The leader-election helper is the client library's `LeaderElection` since 2026-11-08 ([`task-list-client-library-2026-11-08.md`](../docs/archive/task-list-client-library-2026-11-08.md)); `LeaderElectionTest`, `LeaderElectionEndToEndTest`. There is no `qraft lock` command (the sessions list's decision 6) |
| Consistency | `default`, `stale`, and `consistent` reads (design 13) | Done | Since 2026-11-08 ([`task-list-consistency-2026-11-08.md`](../docs/archive/task-list-consistency-2026-11-08.md)); `consistent` passes a ReadIndex barrier on the leader, the default is the answering server's state; `RaftReadBarrierTest`, `HttpApiServerConsistencyTest`, `ConsistencyEndToEndTest`, `DockerConsistencyTest` |
| Consistency | Blocking queries on the index (design 10, 12.3) | Done | `index` and `wait` on catalog and key/value reads, since 2026-11-08; `QraftStateStoreIndexWaitTest`, `HttpApiServerConsistencyTest` |
| Consistency | Monotonic applied index on reads | Done | `X-Qraft-Index` on catalog, health, key/value, session, tenancy and Raft status reads, and passed on by the client's local API with its forwarded reads; the status read since 2026-11-08 ([`task-list-catalog-indexes-2026-11-08.md`](../docs/archive/task-list-catalog-indexes-2026-11-08.md)) |
| Consistency | Leader forwarding for writes | Done | Since 2026-11-08 a follower forwards a write to the leader over the Raft port and answers with the leader's answer ([`task-list-write-forwarding-2026-11-08.md`](../docs/archive/task-list-write-forwarding-2026-11-08.md)), one hop only; with no reachable leader it answers 503 with the leader hint (`X-Qraft-Leader-Id`), and a client still rotates its seeds; `GrpcRaftForwardTest`, `QraftServerWriteForwardingTest`, `WriteForwardingEndToEndTest`. The consistency list had left forwarding out of scope; reads are still not forwarded |
| API | Structured error envelope with request ID | Done | `code`, `message`, `retryable`, `requestId`; the deprecated `error` field is due for removal after 2026-12-31 |
| Tenancy | Tenant and namespace identity in catalog commands and keys | Done | Composite `ServiceInstanceId` |
| Tenancy | Namespace lifecycle, validation, and isolation end to end | Done | Since 2026-11-08 ([`task-list-tenancy-2026-11-08.md`](../docs/archive/task-list-tenancy-2026-11-08.md)): replicated tenants and namespaces, checked names, writes refused outside one that exists, deletion with what it holds, and the client's own scope by default; `QraftStateStoreTenancyTest`, `ScopedWriteTest`, `HttpApiServerTenancyApiTest`, `TenancyEndToEndTest`, `DockerTenancyTest`. The older `qraft-tenant` module was removed on 2026-10-04 |
| Security | Authentication, ACL tokens, policies, token middleware (Consul plan phase 7) | Done | Since 2026-11-04 the replicated ACL state exists (ACL list Phase 1, design section 15.1), since 2026-11-05 the authorizer (Phase 2) and the settings, bootstrap and management file (Phase 3), since 2026-11-06 a server with ACLs enabled enforces them on its HTTP API, its removals and its gRPC API (Phase 4), and since 2026-11-07 the ACL API and `qraft acl` (Phase 5), the client's tokens, its own decisions with a cache and down policies (Phase 6), node identity enforced (Phase 7), and expiry and revocation (Phase 8); and since 2026-11-07 transport security: HTTPS and API gRPC TLS requiring a client certificate, mutual TLS on the Raft port, the client over HTTPS, and the command lines' TLS options ([`task-list-transport-security-2026-11-08.md`](../docs/archive/task-list-transport-security-2026-11-08.md), design section 15.2). Since 2026-11-08 also the `jwt` auth method, binding rules, login and logout, and audit events with a file sink ([`task-list-authentication-and-audit-2026-11-08.md`](../docs/archive/task-list-authentication-and-audit-2026-11-08.md), design section 15.3). Since 2026-11-08 also certificate reloading: a renewed certificate, key or CA is taken up without a restart ([`task-list-certificate-reloading-2026-11-08.md`](../docs/archive/task-list-certificate-reloading-2026-11-08.md)); `TlsMaterialReloadTest`, `TlsReloaderTest`, `QraftServerTlsReloadTest`, `ClientTlsReloadTest`, `CertificateRotationEndToEndTest`. Since 2026-11-08 also the `kubernetes` auth method, logins reviewed by the cluster's TokenReview API ([`task-list-kubernetes-auth-method-2026-11-08.md`](../docs/archive/task-list-kubernetes-auth-method-2026-11-08.md)); `QraftStateStoreKubernetesAuthTest`, `HttpApiServerKubernetesLoginTest`, `KubernetesAuthEndToEndTest`. Not built: the `oidc` and `aws-iam` types, which need a browser and an AWS account. With ACLs disabled, the identity headers are still trusted and the shared operator token (`server.operator.token`) still guards Raft server removal (updated 2026-10-02) |
| Security | Audit events | Done | Since 2026-11-08: an `AUDIT` event per request answered under a token, with no secret, and `server.audit.path` for a JSON-lines file; `HttpApiServerAuditTest`, `AuditFileSinkTest`, `AuthEndToEndTest` |
| Operations | Prometheus metrics | Done | Since 2026-11-08 ([`task-list-observability-2026-11-08.md`](../docs/archive/task-list-observability-2026-11-08.md)): catalog, health-transition, key/value, session, lock and request metrics on every server with bounded labels; client metrics at `/v1/client/metrics`; autopilot health on the leader; storage size and snapshot age. `QraftStateStoreMetricsTest`, `PrometheusExportTest`, `HttpApiServerRequestMetricsTest`, `ClientMetricsTest`, `RaftNodeServerHealthTest`, `ObservabilityEndToEndTest`, `DockerAutopilotHealthTest`. The dashboard has no panels for them yet |
| Operations | Deployment files: Compose clusters, start scripts, and one observability stack | Done | One server with a client, three and five servers, a network-test cluster, and OpenTelemetry Collector, Tempo, Prometheus, Loki, and Grafana, alone or with three servers. `DockerStartCommandsTest` runs every start command and both stacks. Removed on 2026-10-10: the ELK, Fluentd, and Promtail stacks, the nginx load balancer, the logging demo scripts, and the hand-test data (added 2026-10-11) |
| Operations | Node and cluster status | Done | `/raft/status` describes the answering node, with its storage's size and snapshot age, and `GET /v1/operator/raft/configuration` lists the configured servers. Since 2026-11-08 the leader answers `GET /v1/operator/autopilot/health` with each server's last contact, index and health, and the cluster's failure tolerance ([`task-list-observability-2026-11-08.md`](../docs/archive/task-list-observability-2026-11-08.md)) |
| Operations | Fenced-node reason and one-time transition failure diagnostic | Done | Done 2026-10-13 (`f737d6b`; recorded 2026-10-14). `/raft/status` exposes nullable `fenceReason`; the sequencer retains the first cause and logs one ERROR announcement with its original stack and caller context. Startup failures remain observable. `RaftTransitionSequencerTest`, `RaftNodeApplyFailureTest`, `RaftNodeConfigurationTest`, `RaftNodeMetadataSequencingTest`, `HttpApiServerTest`, `DockerDurableRestartTest`. Cluster-wide reachability, lag and quorum remain outside this completed diagnostic |
| Operations | Health aggregation | Done | Derived service health; a `?passing` filter that also excludes an instance on an unreachable node, whatever its checks say, as Consul's does; each entry carries its node's status (design 9.2, client list Phase 6A). `HttpApiServerTest`, `CrashedClientExpiryEndToEndTest`. Since 2026-10-31, verified by Jenkins build 60 |
| Operations | Offline backup, corrupt-replica replacement, compatible-upgrade guidance | Done | [`RAFT_STORAGE_OPERATIONS.md`](../docs/RAFT_STORAGE_OPERATIONS.md); clarified 2026-10-03: lower-level legacy byte compatibility does not establish whole-node startup compatibility; an older backup cannot rejoin as the same voter |
| Operations | Lost-quorum recovery and whole-cluster backup restore | Done | Updated 2026-10-26. Built and verified in membership Step 7 on 2026-10-24: the offline `inspect` and `recover` commands, with `DockerLostQuorumRecoveryTest` recovering from current directories and from a backup (Jenkins build 33). The runbook is in `RAFT_STORAGE_OPERATIONS.md`, which also states what a restore cannot recover: a registration a client was told succeeded may be absent after a restore from a backup |
| Operations | Event journal (design [`QRAFT_EVENT_ARCHITECTURE.md`](../docs/QRAFT_EVENT_ARCHITECTURE.md)) | Done | The design's phases 1 to 4 since 2026-11-08 ([`task-list-events-2026-11-08.md`](../docs/archive/task-list-events-2026-11-08.md)): the contracts and the bounded journal in `dev.mars.qraft.common.events`, Raft system events, and the catalog's domain events, the same on every replica; `EventContractsTest`, `EventJournalTest`, `RaftNodeSystemEventsTest`, `RaftNodeDomainEventsTest`. Phases 5 to 7 since 2026-11-08 ([`task-list-event-query-and-watch-2026-11-08.md`](../docs/archive/task-list-event-query-and-watch-2026-11-08.md)): key/value, lock and session events without values, `GET /v1/operator/events` and the server-sent watch `GET /v1/operator/events/watch`; `QraftStateStoreKvEventsTest`, `HttpApiServerEventQueryTest`, `HttpApiServerEventWatchTest`, `EventWatchEndToEndTest`. The audit file of phase 8 came with the authentication list; durable sinks other than it are not built |
| Operations | DNS discovery (optional) | Missing | — |

## 3. Client features

| Feature | Status | Evidence or gap |
|---|---|---|
| `qraft client` from one image; file configuration validated first | Done | `ClientConfiguration`, `QraftRuntimeApplicationTest` |
| Local liveness and readiness from membership, convergence, required checks, and server contact | Done | `QraftClientTest`, `DockerClientRecoveryTest` |
| Node registration, heartbeats, capped backoff, seed rotation, preferred endpoint | Done | `RegistrationClientTest`, `HttpCatalogClientTest`. On the catalog's write paths since 2026-10-09 |
| Single-flight service reconciliation with fingerprints | Done | `ServiceReconcilerTest` |
| Anti-entropy: reconciliation removes what the catalog holds for the node and the client does not want (design 8.2) | Done | `ServiceReconcilerTest`, `ClientServerContractTest`. Since 2026-10-30, verified by Jenkins builds 56 and 57 |
| Local API listener: `client.bindAddress`, shared request handling, writes refused once shutdown begins | Done | `LocalApiServerTest`, `JsonApiTest`. Since 2026-10-30 |
| Services and checks through the local API, kept in the data directory (design 12.1.3) | Done | `LocalRegistrationRoutesTest`, `LocalDefinitionsTest`, `RegistrationStoreTest`, `QraftClientTest`. Since 2026-10-31, verified by Jenkins build 57 |
| TTL status and maintenance through the local API, maintenance kept across a restart (design 12.1.3) | Done | `LocalRegistrationRoutesTest`, `LocalDefinitionsTest`, `QraftClientTest`, `ClientServerContractTest`. Since 2026-10-31, verified by Jenkins build 58 |
| The client's own information through the local API: `self`, `members` as a forwarded read, and `leave` with process exit (design 12.1.3) | Done | `LocalClientRoutesTest`, `HttpCatalogClientTest`, `ClientLeaveEndToEndTest`. Since 2026-10-31, verified by Jenkins build 59 |
| Discovery reads through the local API, forwarded with their path, query and scope; the servers refuse a query parameter they do not know (design 12.1, 12.1.3) | Done | `LocalDiscoveryRoutesTest`, `LocalClientRoutesTest`, `HttpCatalogClientTest`, `ClientServerContractTest`, `HttpApiServerTest`. Since 2026-11-01, verified by Jenkins build 61 |
| Applications that use only their local client, across hosts, a restart, a leader change and a rebuilt host; containers outside a client's network namespace refused (design 5, 12.1.3) | Done | `ClientEndToEndTest`, `WipedClientDirectoryEndToEndTest`, `DockerClientLocalApiTest`. Since 2026-11-01, verified by Jenkins build 62 |
| HTTP, TCP (with a warning threshold), and TTL checks; sequenced publication and renewal | Done | `*CheckRunnerTest`, `HealthPublisherTest` |
| Bounded graceful shutdown: checks, services, then node | Done | `QraftClientTest`, `DockerClientHealthTest` |
| Recovery from crash, partition, and whole-cluster outage | Done | `DockerClientRecoveryTest`, `DockerClientHealthTest` |
| A generated node identity persisted locally (design 7.3) | Done | `ClientDataDirectoryTest`, `IdentityFileTest`, `NodeIdentityStateStoreTest`, `WipedClientDirectoryEndToEndTest`. Since 2026-10-30; `client.nodeName` replaced `client.id` |
| Client metrics: reconciliation attempts, failures, endpoint, readiness (design 18) | Done | Since 2026-11-08 `GET /v1/client/metrics`, as JSON or Prometheus text: readiness, the age of the last server contact, and reconciliation passes by result ([`task-list-observability-2026-11-08.md`](../docs/archive/task-list-observability-2026-11-08.md)); `ClientMetricsTest`, `ClientMetricsRouteTest`. Since 2026-11-08 also the server endpoint in use, `qraft.client.server` with its `endpoint` label ([`task-list-client-endpoint-metric-2026-11-08.md`](../docs/archive/task-list-client-endpoint-metric-2026-11-08.md)); `ServerEndpointsTest` (this row was corrected from Missing on 2026-11-08) |
| Client libraries for key/value, sessions, and leader election | Done | The Java library `dev.mars.qraft.client.api` since 2026-11-08 ([`task-list-client-library-2026-11-08.md`](../docs/archive/task-list-client-library-2026-11-08.md)): `QraftApi`, `KeyValue` with blocking reads, `Sessions` and `LeaderElection`, through a client's local API or a server; `QraftApiKeyValueTest`, `QraftApiSessionsTest`, `LeaderElectionTest`, `LeaderElectionEndToEndTest`. Other languages are not built |

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
  2026-10-25, which closes the one limitation they recorded; its backoff-reset
  follow-up, so a returning follower is not left waiting for the leader's gRPC
  reconnect, was verified on 2026-10-26 (Jenkins builds 46, 49 and 55). Failed-server cleanup was
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
   The token and ACL core comes earlier, straight after the client, as proposed on
   2026-10-05; it started on 2026-11-04, and its Phases 1 to 9 were built by
   2026-11-07, so servers and clients both enforce ACLs:
   [`task-list-acl-and-tokens-2026-11-07.md`](../docs/archive/task-list-acl-and-tokens-2026-11-07.md).
   Transport security followed the same day, so tokens cross the network only over TLS: [`task-list-transport-security-2026-11-08.md`](../docs/archive/task-list-transport-security-2026-11-08.md).
10. **Deferred until decided.** DNS discovery. Persisted generated node identity
    was decided and built in the client list's Phase 2, closed on 2026-10-30.
    Server membership change was decided on 2026-09-29 and completed in
    [`task-list-raft-membership-changes-2026-10-24.md`](../docs/archive/task-list-raft-membership-changes-2026-10-24.md).

Items 3 to 5 complete the Consul plan's target feature set. Items 6 to 9 are
the prerequisites of the interface's increments UI-2 to UI-6.
