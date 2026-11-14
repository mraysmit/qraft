# Qraft Administrative UI Implementation Plan

**Status:** Agreed 2026-09-27; UI-0 to UI-3 delivered 2026-11-14; open questions 4 to 6 are decided when their increments start
**Last updated:** 2026-11-14 (UI-3 delivered, open question 3 settled, and sections 4 and 5 brought up to date with it. Earlier the same day: UI-2 delivered and its task list archived. Earlier the same day: sections 4 and 5 reviewed against the code as UI-2 started, and the view map given a Done status. Earlier the same day: UI-0 and UI-1 delivered, and their task list archived, its links updated. Earlier, 2026-11-13: UI-0 resumed: section 4 notes what the backend lists built since it was last updated, to be reviewed when UI-2 starts; the embedded-interface task-list link refreshed. Earlier, 2026-11-01: `409 policy_in_use` removed, as the owner decided for the ACL list's proposal 18. Earlier the same day: the ACL list aligned with Consul; its proposal numbers stand. Earlier the same day: dated task-list link refreshed. Earlier, 2026-10-31: section 4: a health entry carries its node's status, and `?passing` leaves out an unreachable node's instances. Earlier, 2026-10-30: open question 5's facts corrected: a client now removes other services on its node, in its own tenant and namespace. Earlier, 2026-10-27, later the same day, after the review of the interface against the ACL list: access control moves from UI-6 to UI-5 with the security profile, sign-in, status, tokens, policies and explanations; server removal joins UI-5; section 4.2 gains the profile and the explanation endpoint; the client rows' data; open questions 5 and 6. Earlier that day: section 3: the asset manifest's file name in the Vite build; sections 4.2, 5 and 6: tokens and policies come from the ACL list, and roles and authentication methods are reserved; the embedded-interface task-list link refreshed. Earlier, 2026-10-26: section 4.1, the service writes are on `PUT /v1/catalog/register` and `/deregister` after the client list's Phase 1. Earlier, 2026-10-21: dated task-list links refreshed; the administrative interface remains paused after Step 1 while backend delivery continues. Earlier, 2026-10-09: section 4.1: the node list is at `/v1/catalog/nodes`, the node writes are listed, and bare `/health` is gone, after the single-POM list's Phase 4. On 2026-10-05: the build location and the tenancy owner, after the move to one Maven project; section 4 was brought up to date with scoped reads and the Raft operator endpoints on 2026-10-02)
**Design:** [`QRAFT_ADMIN_UI_UX_DESIGN.md`](QRAFT_ADMIN_UI_UX_DESIGN.md) (what the interface is) and
[`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), sections
12.4, 12.4.1, and 19.6 (how it is packaged and served)
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

## 1. Purpose

The UI/UX design defines the complete administrative experience and a four-stage
delivery sequence. It does not say which server APIs each view needs, which of
them exist, or in what order the backend and frontend work must land. This plan
supplies that.

- Section 4 inventories what the HTTP API provides today.
- Section 5 maps every planned view to the data it needs and the API that
  supplies it.
- Section 6 orders the work into increments. Each increment names the backend
  prerequisites that must land first.

Each increment is delivered through its own dated task list in `docs-design/`, as the
rest of the platform is. This plan changes only when scope or ordering changes.

## 2. Delivery principles

- **The interface is an API client.** Every view reads, and every mutation
  writes, through the same public HTTP API any other client uses. A view whose
  data has no API waits for that API; the UI never reaches into server
  objects to fill a gap.
- **Backend first, per increment.** An increment's server APIs are delivered and
  tested before its views, each on the normal red-green path. The views then
  consume a stable contract.
- **Only backed capabilities appear.** Navigation entries come from
  server-provided feature flags (UI/UX design section 4.3). A capability whose
  API does not exist produces no entry, not a placeholder page.
- **State and observation stay distinct.** The UI labels node-local observations
  and does not present them as committed state (UI/UX design section 3.3).
- **The contract is tested from both sides.** Java tests prove each endpoint's
  behaviour. The frontend validates every response with a zod schema. Contract
  tests connect the two (section 7).

## 3. Technology baseline

The stack was agreed in
[`task-list-embedded-admin-interface-2026-11-14.md`](../docs/archive/task-list-embedded-admin-interface-2026-11-14.md),
section 4.1, after reviewing the `peegeeq-management-ui` and
`peegeeq-utilities-ui` modules:

- **Frontend.** React 18, TypeScript in strict mode, Vite 6, and Ant Design 5.
  Data comes through a typed `fetch` client with zod validation, and there is no
  global state library until a view needs one.
- **Build.** `frontend-maven-plugin` in the root `pom.xml`, with pinned Node and
  npm versions:
  - `npm ci` against a committed lock file;
  - the build runs in `generate-resources`;
  - output is staged under `META-INF/qraft/ui/` and never committed;
  - Vite writes its asset manifest as `asset-manifest.json`
    (`build.manifest: 'asset-manifest.json'`; with `true`, Vite names it
    `.vite/manifest.json`);
  - no source maps are published.
- **Serving.** Classpath resources on the existing JDK HTTP server, with a
  per-request `index.html` that carries bootstrap settings and a content
  security policy nonce. Ant Design receives the nonce, so the policy never
  allows unsafe inline styles.
- **Tests.**
  - Vitest with jsdom and Testing Library for the frontend.
  - Java tests over real HTTP for serving and APIs.
  - Packaged-JAR and Docker acceptance tests for delivery.
  - Browser end-to-end automation is deferred (section 7).

## 4. API readiness

**Reviewed against the code on 2026-11-14**, when UI-2 started
([`task-list-admin-ui-scoped-discovery-2026-11-14.md`](../docs/archive/task-list-admin-ui-scoped-discovery-2026-11-14.md),
section 2). The tables had last been brought up to date on 2026-11-01, before
the backend lists of 2026-11-04 to 2026-11-09 (handover, section 1); each row
now states what the code provides on that date.

### 4.1 What exists

| Endpoint | Provides | Gaps for the UI |
|---|---|---|
| `GET /raft/status` | node, server ID, role, term, leader, commit, applied, and last log index, snapshot index, fenced and removed flags, the storage's size and the snapshot's age, read as one consistent view | Describes only the answering node |
| `GET /v1/operator/raft/configuration` | each configured server: server ID, name, address, voter, leader, as the answering server holds them | No reachability or lag |
| `GET /v1/operator/autopilot/health` | the leader's view of each server: healthy, last contact, last index and term, as Consul's | Superseded for the interface by the members read |
| `GET /v1/operator/raft/members` | on the leader each server's match and next index, lag, last contact, reachability and health, and the quorum; on a follower its labelled partial view (since 2026-11-14) | None for UI-3 |
| `GET /v1/operator/raft/storage` | size, snapshot index, term and age, first log index held, log indexes, fencing, transition queue depth, last snapshot failure (since 2026-11-14) | No WAL health figure or lock state |
| `DELETE /v1/operator/raft/peer` | removal of a server, guarded by the operator token and forwarded to the leader | A mutation: offered from UI-5 |
| `GET /health/live`, `/health/ready` | liveness, and readiness with the conditions the server does not meet | None for the status strip |
| `GET /v1/tenants`, `GET /v1/namespaces` | the replicated tenants, and the namespaces of the tenant `X-Qraft-Tenant` names | None for the scope selector |
| `GET /v1/catalog/nodes` | nodes in name order: name, node ID, address, datacenter, region, metadata (the client's version is `qraft.version`), status, and the registration and heartbeat times; carries `X-Qraft-Index` | No owned-service or failing-check counts. A node has no tenant or namespace |
| `GET /v1/catalog/services` | service names with their tags, in the scope of the `X-Qraft-Tenant` and `X-Qraft-Namespace` headers | Superseded for the Services list by the summary |
| `GET /v1/internal/ui/services` | one entry per service in the read's scope: tags, instance count, instances by health, datacenters and regions, and the highest `modifyIndex` (since 2026-11-14) | None for the Services list |
| `GET /v1/catalog/service/{name}` | instances in the requested scope: identity, address, port, tags, metadata, health, tenant, namespace, datacenter, region, enabled, and the creation and modification indexes | No registration source |
| `GET /v1/health/service/{name}` (`?passing`) | instances in the requested scope with their node's status and their checks: status, sequence, observed, accepted, deadline, expired, output, deregistration delay | One service at a time |
| `GET /v1/health/state/{state}` | the accepted checks across the services of the read's scope, in one health state or all, with instance, service, node and node status (since 2026-11-14) | A declared check without a result is not listed |
| `GET /v1/operator/events` | the bounded event journal: query and watch, by category (system, domain, observation, audit) | Bounded; no durable history |
| `/v1/kv`, `/v1/session` | key/value with indexes and compare-and-set; sessions and locks | Not yet in the interface (Stage 2) |
| `/v1/acl/...`, `/v1/acl/login` | tokens, policies, roles, auth methods, binding rules, `token/self`, `explain`, tokens by policy, `409 acl_lockout`, login | Not yet in the interface (UI-5) |
| `PUT /v1/catalog/register`, `/deregister`, `/node/heartbeat`, `/check/observe` | replicated node and service writes, scoped by `X-Qraft-Tenant`, `X-Qraft-Namespace` and `X-Qraft-Node` | Mutations: offered from UI-5 |
| Read options and metadata | `stale`, `consistent`, `index` and `wait` on catalog and key/value reads; `X-Qraft-Index`, `X-Qraft-Known-Leader`, `X-Qraft-Last-Contact`; `X-Qraft-Leader-Id` and the error envelope (`code`, `message`, `retryable`, `requestId`) on errors | No header naming the answering server |
| OpenTelemetry Prometheus exporter | metrics on a separate port (`prometheusPort`, default 9464) | Not on the API listener; the UI only links to it |

### 4.2 What is missing, and what was built

| Capability | Needed by | State on 2026-11-14 |
|---|---|---|
| Tenant- and namespace-scoped reads, and a listing of the scopes | Scope selector; every Discover view | Built: the headers since 2026-09-27, the tenancy API since the tenancy list; used by UI-2 |
| Services summary | Services landing page | Built 2026-11-14 (UI-2) |
| Cross-service health-check listing | Health Checks page | Built 2026-11-14 (UI-2) |
| Cluster membership detail: match and next index, lag, reachability, quorum | Status strip, Cluster Overview, Raft Members, server rows in Nodes | Built 2026-11-14 (UI-3): `/v1/operator/raft/members` |
| Storage and snapshot state: WAL size and health, fenced and lock state, snapshot index, term, age and last result, retained boundary | Storage & Snapshots, Cluster Overview | Built 2026-11-14 (UI-3), `/v1/operator/raft/storage`, except a WAL health figure and the lock state; the last result is the last failure |
| Transition-queue depth and saturation | Cluster Overview | Built 2026-11-14 (UI-3): the depth; no saturation figure |
| Bounded event journal and query | Events page and tabs | Built (events list): query and watch |
| Redacted effective configuration | Configuration page | Missing |
| Read consistency modes and an answering-node header | Consistency selector, read metadata | Partial: the modes are built; no answering-node header |
| Blocking queries or watches | Live updates, Watches | Built: `index` and `wait` on catalog and key/value reads; the event watch |
| HTTP key/value API with indexes and compare-and-set | Key/Value browser | Built |
| Sessions and locks | Sessions, Locks | Built |
| Replicated tenants and namespaces with an API | Tenants, Namespaces, scope selector | Built (tenancy list) |
| Authentication, authorization, tokens and policies | Mutations; Tokens, Policies; the token menu | Built (ACL list) |
| The security profile and authentication mode in the bootstrap data; `installedFromFile` | Security status, banners, sign-in | Built (UI-0's bootstrap object; the token answers) |
| `POST /v1/acl/explain`; tokens by policy | Explanations; policy pages | Built |
| Lock-out refusal, `409 acl_lockout` | Token and policy review steps | Built |
| Node ID and name conflicts | Client rows | Built (client list, Phase 2): node IDs and `node_name_reserved` |
| A client's readiness, server endpoint, and last reconciliation | Client rows | Missing: only the client knows them (open question 6) |
| Roles, authentication methods and login | Roles, Auth Methods | Built (authentication and audit list) |
| Durable audit sink and query | Audit page | Partial: an audit file, and audit events in the bounded journal; no durable query |
| Topology, intentions, routing, gateways, peering, federation | Connect and Multi-cluster | Reserved: no platform contract |

## 5. View map

Status key:

- **Done:** the interface has the view.
- **Ready:** existing APIs supply the view's core data.
- **Partial:** existing APIs supply part of it; the rest waits for the named
  capability.
- **Blocked:** the view needs a missing capability before it can exist.
- **Reserved:** no platform contract exists; not planned.

| Stage | View (UI/UX section) | Status | Notes (section 4.2) |
|---|---|---|---|
| 1 | Shell and status strip (4.1, 4.2) | Done | UI-1 to UI-3: role, leader, term, readiness, security, the scope selector, quorum, failure tolerance, lag, and unreachable servers. The consistency selector is UI-4's. |
| 1 | Services list (5.1) | Done | UI-2: on the summary. Registration source is not recorded. |
| 1 | Service detail: Overview, Instances, Health Checks, Metadata (5.1) | Done | UI-1, in the selected scope since UI-2. |
| 1 | Service detail: Events tab | Ready | The event journal |
| 1 | Nodes and Clients: client rows (5.2) | Done, in part | UI-1. Readiness, server endpoint and last reconciliation need clients to report them (open question 6). |
| 1 | Nodes and Clients: server rows (5.2) | Done | UI-3: role, vote, reachability, match index, lag, last contact |
| 1 | Health Checks, cross-service (5.3) | Done | UI-2: on the check listing. |
| 1 | Cluster Overview (9.1) | Done | UI-3: servers, quorum, services, client nodes, storage and queue. Recent events are UI-4's. |
| 1 | Raft Members (9.2) | Done | UI-3, read-only; a follower shows its partial view (open question 3) |
| 1 | Storage & Snapshots (9.3) | Done | UI-3, with the persistent fenced banner |
| 1 | Events, read-only (9.4) | Ready | The event journal |
| 1 | Configuration, read-only (9.6) | Blocked | Redacted configuration |
| 1 | Metrics (9.5) | Partial | A configured link to the external dashboard is ready. |
| 2 | Service register, update, deregister (5.1, 11) | Ready | Authentication and audit exist; open question 5 is undecided. |
| 2 | Key/Value CRUD and compare-and-set (7.1) | Ready | |
| 2 | Sessions and Locks (7.2) | Ready | |
| 2 | Watches (7.3) | Partial | Blocking reads and the event watch |
| 2 | Mutation outcomes and audit display (11) | Partial | Audit events in the journal; no durable query |
| 2 | Access: Status, sign-in, token menu, banners (10.2.1, 10.2.2) | Ready | |
| 2 | Access: Tokens, Policies, Explain (10.2.3 to 10.2.6) | Ready | |
| 2 | Server removal (9.2) | Ready | Behind sign-in, UI-5 |
| 3 | Topology, Intentions, Routing, Gateways (6) | Reserved | Platform contracts |
| 4 | Datacenters, Regions, Peers, Federation (8) | Reserved | Platform contracts |
| 4 | Tenants and Namespaces (10.1) | Ready | |
| 4 | Roles, Auth Methods (10.2) | Ready | |
| 4 | Audit (10.3) | Partial | No durable query |
| All | Live updates and pause (12) | Ready | Blocking reads; UI-4 |

## 6. Increments

Each increment is one task list. Backend prerequisites land first within it.

### UI-0 Foundation: packaging and serving

Server configuration and route contract, embedded asset serving, the
reproducible frontend build, and the packaged-JAR and container acceptance tests
(platform design sections 12.4.1 and 19.6). No backend prerequisites. This
closes the embedded-interface acceptance criterion in platform design section
21.

### UI-1 Discovery on existing APIs

Views:

- the application shell;
- the status strip, limited to what `/raft/status` and the health endpoints
  provide;
- the Services list, fetching once per service;
- service detail: Overview, Instances, Health Checks, and Metadata;
- client rows in Nodes and Clients.

The shared frontend pieces also land here:

- the typed API client;
- zod schemas;
- error-envelope and leader-hint handling;
- display of the `X-Qraft-Index` read metadata;
- feature flags from the bootstrap data;
- the empty, loading, and failure states of UI/UX design section 13.

No backend prerequisites.

UI-0 and UI-1 together were the scope of
[`task-list-embedded-admin-interface-2026-11-14.md`](../docs/archive/task-list-embedded-admin-interface-2026-11-14.md),
completed and archived on 2026-11-14. UI-2 is the next increment; it waits for
its backend work and the review of section 4.

### UI-2 Scoped discovery

Backend first:

- tenant- and namespace-scoped client reads (catalog and health reads are
  already scoped);
- the services summary endpoint;
- the cross-service health-check listing.

Views: the scope selector, driven by the scopes present in the catalog until
replicated tenants exist (UI-6); the Services list on the summary endpoint; and
the Health Checks page.

Delivered 2026-11-14 in
[`task-list-admin-ui-scoped-discovery-2026-11-14.md`](../docs/archive/task-list-admin-ui-scoped-discovery-2026-11-14.md):
replicated tenants already existed, so the scope selector lists them (open
question 2). UI-3 is the next increment.

### UI-3 Operate: cluster, members, and storage

Backend first:

- a leader-side membership view: peers, match and next index, last contact, lag,
  reachability, and quorum;
- storage and snapshot state;
- transition-queue depth.

Views:

- the full status strip;
- server rows in Nodes and Clients;
- Cluster Overview;
- Raft Members, read-only (membership changes are not offered);
- Storage & Snapshots, including the persistent fenced banner.

Delivered 2026-11-14 in [`task-list-admin-ui-operate-2026-11-14.md`](../docs/archive/task-list-admin-ui-operate-2026-11-14.md), with UI-2's open items: the client's
fallback for unrouted paths, the redirect from `/`, gzip for text assets, and
the bundle split. UI-4 is the next increment.

### UI-4 Events, configuration, and live reads

Backend first:

- `qraft-events` phases 1 to 4 (event architecture section 15): contracts, a
  bounded local source, Raft system events, and state-machine outcomes;
- a bounded event query;
- the redacted effective configuration;
- consistency modes and an answering-node header;
- blocking queries.

Views:

- Events, with the journal labelled ephemeral;
- the Events tabs;
- Configuration;
- the consistency selector;
- live updates with pause and resume, and history-gap resynchronization.

### UI-5 Authentication and service mutations

Backend first:

- authentication and authorization for the HTTP API, from the ACL list:
  tokens, policies, the anonymous token, and `GET /v1/acl/token/self`;
- the ACL list's integrity and explanation features: the security profile in
  the bootstrap data, the lock-out and linked-policy refusals, the management
  token's file marker, and `POST /v1/acl/explain`;
- audit events for every mutation.

Views:

- permission-shaped navigation;
- access control, UI/UX design section 10.2: the security status page and
  banners, sign-in and the token menu, and how the browser holds the token
  secret; Tokens, including the one-time secret, revocation, replacement, and
  the management token's revocation order; Policies, with linked tokens and
  a deletion review that lists them; and explanations;
- the mutation workflow of UI/UX design section 11: review step, structured
  outcomes, and unknown-outcome handling;
- service register, update, and deregister, within open question 5's answer;
- server removal, with its quorum review (UI/UX design section 9.2).

From this increment the interface signs in with a token, or, on a development
server running without ACLs, works under the no-access-control banner. A
production server always has ACLs on (ACL list, proposal 16). Until this
increment the interface is read-only. If ACLs are enabled before it, the
read-only interface's calls act as the anonymous token.

Access control comes here, not with governance in UI-6, because every
mutation depends on it and an operator needs to manage tokens from the first
mutation onwards.

### UI-6 Coordination and governance

Backend first:

- the HTTP key/value API with compare-and-set;
- sessions and locks;
- replicated tenants and namespaces;
- the `key` and `session` rules, which the key/value and sessions lists add
  to the ACL model.

Views:

- Key/Value;
- Sessions;
- Locks;
- Watches;
- Tenants;
- Namespaces;
- Audit, once a durable sink exists.

This increment is large enough to be split into several task lists when it is
reached.

### Not planned

Connect (topology, intentions, routing, gateways) and Multi-cluster remain
reserved until the platform defines their contracts (UI/UX design section 2).
So do Roles and Auth Methods, until a task list delivers them. They produce no
navigation entries.

### Dependencies

```text
UI-0 -> UI-1 -> UI-2 -> UI-3 -> UI-4 -> UI-5 -> UI-6
                         |               ^
                         +---------------+  membership and storage state feed the events of UI-4
```

UI-2 and UI-3 are independent of each other and may be swapped. UI-4's
consistency modes and blocking queries also serve UI-6's Watches. UI-5 must
precede every mutation.

## 7. Test strategy

| Layer | Tool | Proves |
|---|---|---|
| Serving and packaging | Java over real HTTP; packaged-JAR and Docker acceptance | Routes, fallback rules, cache and security headers, content security policy and nonce, disabled and client-mode behaviour, JAR contents |
| Server APIs | Java over real HTTP, the existing pattern | Each endpoint's behaviour, scoping, errors, and read metadata |
| API contract | Java tests write the JSON responses they receive to fixture files under `target/`; Vitest validates each fixture with the frontend's zod schema | The frontend schema and the server response cannot drift apart unnoticed |
| Frontend logic and views | Vitest, jsdom, Testing Library | Rendering, formatting, state handling, error and empty states, and accessible names |
| Browser end to end | Playwright | Deferred. Introduce it with UI-5, when mutation workflows make full journeys worth automating |

The platform's rules apply to frontend tests as they do to Java:

- deterministic time;
- no wall-clock sleeps;
- every new test red before green.

## 8. Acceptance

The implementation meets UI/UX design section 16 when:

- UI-0 has delivered the packaged, embedded interface, closing platform design
  section 21;
- UI-1 to UI-4 cover every Stage 1 view with scope, consistency, live updates,
  and observable cluster, Raft, storage, snapshot, recovery, and fencing states;
- UI-5 delivers authenticated, authorized, audited mutations with accurate
  outcome reporting;
- UI-6 delivers coordination and governance, with consistent redaction and
  permission-shaped navigation.

Criteria that depend on Connect and Multi-cluster remain open until those
contracts exist.

## 9. Open questions

1. **Default state before authentication.** Decided 2026-09-27. The read-only
   interface is enabled by default before UI-5, with a startup warning that it
   is unauthenticated
   ([`task-list-embedded-admin-interface-2026-11-14.md`](../docs/archive/task-list-embedded-admin-interface-2026-11-14.md),
   decision 4).
2. **Source of scope options.** Settled 2026-11-14 by the code: replicated
   tenants and namespaces exist with an API (tenancy list), and a registration
   needs its scope to exist, so the scope selector lists them and UI-2 did not
   wait ([`task-list-admin-ui-scoped-discovery-2026-11-14.md`](../docs/archive/task-list-admin-ui-scoped-discovery-2026-11-14.md),
   decision 1). The question was: until replicated tenants exist (UI-6), should
   the scope selector list only the scopes present in the catalog, or should
   UI-2 wait for replicated tenants?
3. **Membership reads.** Settled 2026-11-14 ([`task-list-admin-ui-operate-2026-11-14.md`](../docs/archive/task-list-admin-ui-operate-2026-11-14.md), decision 5, taken
   under the owner's instruction not to pause, reversible): a follower answers
   its own partial view, labelled, with the leader it knows and null progress.
   The question was: only the leader knows its peers' match indexes and contact
   times; should a follower answer membership reads with a redirect hint to the
   leader, or with its own partial view clearly labelled?
4. **Metrics.** Should the Metrics page stay a configured link to the external
   dashboard, or read bounded summaries from the API listener?
5. **Services registered from the interface.** Raised 2026-10-27. A client
   re-registers the services it owns and, since 2026-10-30, removes at its next
   reconciliation any other service on its node in its own tenant and namespace
   (UI/UX design section 5.1). Should the interface register only services on
   nodes that no client runs, or also on a client's node, with a warning? Such a
   service lasts only in a tenant or namespace other than the client's. A node registered without a client stops heartbeating and is
   reaped (platform design section 9.2), so the first answer also needs a way
   to keep such a node, which the platform does not have.
6. **What clients report.** Raised 2026-10-27. A client's readiness, current
   server endpoint, and last successful reconciliation are known only to the
   client. Should clients report them to the servers, for example as node
   metadata on each heartbeat, or should the client rows leave them out? The
   first is a change to the client list.
