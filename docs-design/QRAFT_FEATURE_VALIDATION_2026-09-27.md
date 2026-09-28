# Qraft Feature Validation

**Date:** 2026-09-27
**Scope:** every server and client feature in
[`CONSUL_FEATURE_IMPLEMENTATION_PLAN.md`](CONSUL_FEATURE_IMPLEMENTATION_PLAN.md) and
[`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md),
checked against the code on the date above
**Purpose:** establish which platform features exist before the administrative
interface ([`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md))
goes further than its first increment

## 1. Summary

The runtime, Raft, the service catalog, health propagation, and the client agent
are implemented and verified, including multi-node container acceptance. Five
feature areas the plans require are missing or only partly present:

- the key/value store;
- sessions and locks;
- read consistency modes and blocking queries;
- tenancy isolation;
- security.

Operational status and metrics are incomplete, and remnants of the inherited job
system remain in `qraft-core`. The Consul plan's own checklist is out of date in
both directions (section 4).

Status key:

- **Done:** implemented and covered by tests.
- **Partial:** present, but short of the specification in the named respect.
- **Missing:** not implemented.

## 2. Server features

| Area | Feature | Status | Evidence or gap |
|---|---|---|---|
| Runtime | `qraft server` from one image; file configuration validated before resources open | Done | `QraftRuntimeApplication`, `AppConfig`, `QraftControllerLifecycleTest` |
| Runtime | Drain before shutdown; bounded shutdown | Done | `ShutdownCoordinator`, `RaftNodeShutdownSequencingTest` |
| Runtime | Readiness that reflects recovery, fencing, draining, and leadership (design 6.1) | Done | `HttpApiServerReadinessTest`; done 2026-09-27 |
| Raft | Election, replication, check-quorum, fencing | Done | `RaftNode*Test`, `RaftNodeCheckQuorumTest` |
| Raft | Durable WAL, snapshots in every role, installation, recovery | Done | `RaftNodeFollowerLogTest`, `DockerDurableRestartTest`, `DockerAgentRecoveryTest` |
| Raft | Replica determinism; follower match index limited to verified entries | Done | `ReplicaDeterminismTest`, `RaftNodeFollowerLogTest` |
| Raft | Adding or removing servers (membership change) | Missing | Membership is static, from `server.raft.nodes` |
| Catalog | Register and deregister through Raft; composite identity; idempotence | Done | `HttpApiServerTest`, `AgentEndToEndTest` |
| Catalog | Catalog and health queries with deterministic ordering | Done | `ServiceCatalog` sorts names and instances |
| Catalog | Registration and modification indexes on instances (design 7.5) | Missing | `ServiceInstance` has no index fields |
| Catalog | Reads scoped by tenant and namespace | Done | Catalog and health reads honour the scope headers; `HttpApiServerTest`, `ServiceCatalogTest`; done 2026-09-27 |
| Health | Ordered observations, derived service health, all five states | Done | `HealthCommandStateStoreTest`, `HealthPropagationEndToEndTest` |
| Health | Leader-owned expiry and automatic deregistration | Done | `LeaderHealthExpiry*Test`, `CrashedAgentExpiryEndToEndTest` |
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
| Consistency | Monotonic applied index on reads | Partial | `X-Qraft-Index` on catalog and health reads; not on agent or Raft reads |
| Consistency | Leader forwarding for writes | Partial | Leader hint (`X-Qraft-Leader-Id`) and seed rotation; forwarding is out of scope by decision |
| API | Structured error envelope with request ID | Done | `code`, `message`, `retryable`, `requestId`; the deprecated `error` field is due for removal after 2026-12-31 |
| Tenancy | Tenant and namespace identity in catalog commands and keys | Done | Composite `ServiceInstanceId` |
| Tenancy | Namespace lifecycle, validation, and isolation end to end | Missing | `qraft-tenant` has an in-memory `NamespaceService` that no other module uses |
| Security | Authentication, ACL tokens, policies, token middleware (Consul plan phase 7) | Missing | Trusted identity headers behind `HeaderRequestContext` only |
| Security | Audit events | Missing | — |
| Operations | Prometheus metrics | Partial | Raft metrics only. The catalog, health-transition, request, and session metrics of design 18 are absent |
| Operations | Node and cluster status | Partial | `/raft/status` describes only the answering node: no peers, lag, or quorum |
| Operations | Health aggregation | Done | Derived service health; `?passing` filter |
| Operations | Backup, corrupt-replica recovery, upgrade procedures | Done | [`RAFT_STORAGE_OPERATIONS.md`](../docs/RAFT_STORAGE_OPERATIONS.md) |
| Operations | Event journal (design [`QRAFT_EVENT_ARCHITECTURE.md`](../docs/QRAFT_EVENT_ARCHITECTURE.md)) | Missing | No `qraft-events` module |
| Operations | DNS discovery (optional) | Missing | — |

## 3. Client features

| Feature | Status | Evidence or gap |
|---|---|---|
| `qraft client` from one image; file configuration validated first | Done | `AgentConfiguration`, `QraftRuntimeApplicationTest` |
| Local liveness and readiness from membership, convergence, required checks, and controller contact | Done | `QraftAgentTest`, `DockerAgentRecoveryTest` |
| Node registration, heartbeats, capped backoff, seed rotation, preferred endpoint | Done | `AgentRegistrationClientTest`, `HttpCatalogClientTest` |
| Single-flight service reconciliation with fingerprints | Done | `ServiceReconcilerTest` |
| HTTP, TCP (with a warning threshold), and TTL checks; sequenced publication and renewal | Done | `*CheckRunnerTest`, `HealthPublisherTest` |
| Bounded graceful shutdown: checks, services, then node | Done | `QraftAgentTest`, `DockerAgentHealthTest` |
| Recovery from crash, partition, and whole-cluster outage | Done | `DockerAgentRecoveryTest`, `DockerAgentHealthTest` |
| A generated node identity persisted locally (design 7.3) | Missing | Deferred by the health-propagation list; `agent.id` is required in configuration |
| Agent metrics: reconciliation attempts, failures, endpoint, readiness (design 18) | Missing | The agent has no metrics |
| Client libraries for key/value, sessions, and leader election | Missing | Depends on the server features |

## 4. Consistency of the plans with the code

- **Legacy concepts remained.** Resolved 2026-09-27 by
  [`task-list-platform-hygiene-and-readiness-2026-09-27.md`](task-list-platform-hygiene-and-readiness-2026-09-27.md).
  The Consul plan's completion criteria require that no job or workflow concepts
  remain. `AgentStatus` still has
  `getJobAssignmentPriority()`, which nothing calls, and the work-scheduling
  states `IDLE`, `ACTIVE`, and `OVERLOADED`. The `AgentSystemInfo` Javadoc
  describes "capacity planning and job assignment".
- **The Consul plan's checklist was stale.** Reconciled 2026-09-27. Several
  unchecked items were done:
  - agent membership and failure detection;
  - health checks and health-state propagation through Raft;
  - multi-node integration and failure-injection coverage.

  Snapshot, restore, upgrade, and recovery workflows are partly done. Server
  configuration and bootstrap is done apart from membership change.

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
   and lifecycle, and scoped reads for agents and key/value. Catalog and health
   reads are scoped as of 2026-09-27.
7. **Observability.** Catalog, health, request, agent, and session metrics; a
   leader-side membership and replication status; and storage and snapshot
   status.
8. **Events.** The `qraft-events` bounded journal, phases 1 to 4 of the event
   architecture.
9. **Security.** Authentication, ACL tokens and policies, and audit events.
10. **Deferred until decided.** Server membership change, DNS discovery, and
    persisted generated node identity.

Items 3 to 5 complete the Consul plan's target feature set. Items 6 to 9 are
the prerequisites of the interface's increments UI-2 to UI-6.
