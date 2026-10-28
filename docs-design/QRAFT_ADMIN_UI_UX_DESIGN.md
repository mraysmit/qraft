# Qraft Administrative UI and UX Design

**Status:** Draft  
**Last updated:** 2026-10-27 (later the same day, after a review of the interface's logic against the ACL list and the platform design: access control rewritten as section 10.2's subsections, with the owner's decisions on the security profile, the lock-out guard, policy deletion and the explanation endpoint; principle 3.7; access control moved to Stage 2; section 11's integrity refusals and operations that are not idempotent; nodes, health states, unscoped views, and operations without an API in sections 3.2, 5 and 9. Earlier that day, after a review of the reference implementation against this document: workspace tabs, section 4.4; access control on the token model of the ACL list, sections 4.1, 10.2, 11 and 15, with Roles and Auth Methods reserved, section 2; the reference implementation follows section 4.3 and labels reserved views. Earlier, 2026-10-09: a client row shows the version from the node's metadata; a node has no capabilities any more. Before: 2026-09-25)

## 1. Purpose

Qraft provides a built-in administrative interface for discovering, configuring,
securing, and operating the platform. The interface follows the useful catalog
and resource-browsing patterns established by HashiCorp Consul while extending
them with first-class Raft, storage, recovery, event, tenancy, federation, and
operational workflows.

The administrative interface is not a separate control plane. It uses the same
authenticated and authorized HTTP APIs as every other client. All replicated
mutations follow the normal Raft command path and expose the same consistency,
leader-forwarding, retry, and unknown-outcome semantics.

This document defines the intended information architecture, primary screens,
interaction patterns, safety requirements, and staged delivery plan. Build and
runtime packaging are defined in
[`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md).

An interactive, self-contained reference implementation is available at
[`console-prototype/qraft-admin-mockup.html`](../console-prototype/qraft-admin-mockup.html).
It applies the GD Workspace runtime prototype's visual language and prototyping
approach to this Qraft information architecture; it is a design reference, not
an assertion that the two products share domain concepts or user journeys.
It follows this document's navigation (section 4.3), shows every reserved view
of section 2 with a "Reserved" label so the design can be reviewed in full, and
uses invented data. Where the two differ, this document governs.

## 2. Product scope and roadmap alignment

The administrative experience is designed for the intended platform roadmap,
not only the features currently implemented. It includes:

- service discovery and health;
- key/value state, sessions, locks, and watches;
- service topology, intentions, routing, and gateways;
- datacenters, regions, peering, and federation;
- tenants, namespaces, ACLs, quotas, and audit records;
- cluster membership, Raft consensus, storage, snapshots, and recovery;
- configuration, metrics, events, and diagnostics;
- authenticated and auditable administrative mutations.

The topology, intentions, routing, gateways, peering, and federation portions of
this document are forward-looking UX reservations rather than commitments in the
current platform design or delivery plan. They remain disabled and produce no
navigation entries until corresponding platform contracts and an approved
delivery plan exist.

Roles and Auth Methods, in Govern, are reserved in the same way. The ACL list
([`task-list-acl-and-tokens-2026-10-28.md`](task-list-acl-and-tokens-2026-10-28.md))
delivers tokens and policies and leaves roles, authentication methods and login
out (its section 7).

## 3. Design principles

### 3.1 Services are the primary working surface

The Services page is the default landing page. It provides the fastest path to
the resource most operators and application teams use daily. Cluster safety is
kept visible through a persistent status strip rather than requiring the user to
visit a separate dashboard first.

### 3.2 Scope is always explicit

Tenant, namespace, datacenter, and region context remain visible in the top bar.
Every list, detail page, form, confirmation, event, and audit record shows the
scope that applies to it. A mutation must never rely on scope that is visible
only on a previous screen.

A resource that has no scope says so, rather than showing the top bar's scope
as if it applied. Nodes, Raft and storage state, configuration, tokens and
policies have no tenant or namespace (platform design section 7.3; ACL list,
proposal 7).

Until multi-cluster exists (section 2), Qraft runs one datacenter. Datacenter
and region are attributes of nodes and services, and the top bar's
datacenter and region selectors filter by them. They do not switch clusters.

### 3.3 State and observations are distinguished

The UI distinguishes authoritative replicated state from node-local observations.
A health probe, reconciliation attempt, or network measurement is not presented
as a committed fact until the corresponding state change has committed and been
applied.

### 3.4 Operational risk is visible

Quorum loss, replication lag, fenced storage, stalled transitions, snapshot
failure, incomplete recovery, history gaps, and unknown mutation outcomes use
prominent, persistent messages. They are not reduced to transient notifications.

### 3.5 Permissions shape the experience

Authorization determines which resources, values, actions, and event fields are
available. The UI does not display unusable administrative controls merely to
return an authorization failure later. A disabled control may be shown when its
explanation helps the user understand which permission is required.

### 3.6 Colour is never the only signal

Health, severity, role, and lifecycle state are communicated with text, icons,
shape, and colour. The interface supports keyboard navigation, meaningful focus
order, accessible names, reduced motion, and sufficient contrast.

### 3.7 Security state is always visible

The server's security profile, whether access control is on, and whether the
token in use is the management token are always on screen. Two states carry
a persistent banner on every page that cannot be dismissed: a development
server running without access control, and a session using the management
token (section 10.2.1). Security is never inferred from the absence of an
error.

## 4. Application shell

### 4.1 Top bar

The persistent top bar contains:

- product and cluster identity;
- tenant and namespace selector;
- datacenter and region selector;
- read-consistency selector: `Default`, `Consistent`, or `Stale`;
- current cluster state and leader;
- global search;
- notifications, and the menu of the token in use (section 10.2).

The selected scope and consistency mode are encoded in the URL where practical
so diagnostic views can be bookmarked and shared with appropriately authorized
users.

### 4.2 Cluster status strip

The default working pages show a compact status strip:

```text
Cluster: Healthy | Leader: server-2 | Quorum: 3/3
Critical services: 2 | Replication lag: 0 | Scope: production / payments
```

The strip expands when the cluster is degraded. It links directly to the member,
service, storage, or event view that explains the condition. It also shows the
security profile and whether access control is on, and links to the security
status page (section 10.2.1).

### 4.3 Primary navigation

```text
Discover
  Services
  Nodes
  Health Checks

Connect
  Topology
  Intentions
  Routing
  Gateways

Coordinate
  Key/Value
  Sessions
  Locks
  Watches

Multi-cluster
  Datacenters
  Regions
  Peers
  Federation

Operate
  Cluster Overview
  Raft Members
  Storage & Snapshots
  Events
  Metrics
  Configuration
  Diagnostics

Govern
  Tenants
  Namespaces
  Quotas
  Audit

Access
  Status
  Tokens
  Policies
  Explain
  Roles
  Auth Methods
```

Access is a group of its own, beside Govern, because access control holds the
system's integrity together (section 10.2). Roles and Auth Methods are
reserved (section 2).

Navigation entries are controlled by server-provided feature flags and the
permissions of the token in use. Features that are not enabled do not
produce dead navigation entries.

### 4.4 Workspace tabs

Opening a view from the navigation opens it in a workspace tab, beside the
views already open. An operator investigating an incident can keep a service,
the Raft members and the events open together and move between them without
losing their place.

- A tab holds one view with its own filters, selection, detail tab and scroll
  position. Opening a view that already has a tab selects that tab rather than
  opening a second one.
- The selected tab's view, scope and consistency mode are in the URL (section
  4.1). Reloading, bookmarking, sharing a link, and the browser's back and
  forward buttons all work on that one view. The set of open tabs belongs to
  the browser session only: it is not in the URL and is not shared.
- Scope and consistency mode are chosen once, in the top bar, and apply to
  every open tab. Changing either refreshes each tab's view, so no tab shows
  data from a scope the top bar no longer names (section 3.2).
- Only the selected tab keeps live updates running (section 12). A tab that
  is not selected re-reads its view when it is selected again, and says so if
  the result has changed. Pausing live updates applies to the tab where it was
  paused.
- Closing a tab selects its neighbour. Closing the last tab opens Services,
  the landing page (section 3.1). A tab whose mutation form holds input that
  has not been submitted asks before it closes.
- The tab strip is keyboard operable: the arrow keys move between tabs, and
  each tab and its close control have accessible names.
- The cluster status strip (section 4.2) is shown whichever tab is selected.

## 5. Discover

### 5.1 Services

The Services page is the default landing page. Each service row shows:

- service name and type;
- aggregate health and instance count;
- tenant and namespace;
- datacenter and region;
- tags and selected metadata;
- registration source;
- mesh participation and gateway type where applicable;
- upstream and downstream relationship counts;
- last authoritative change.

Users can search across service name, instance ID, node, address, tag, and
metadata. Filters cover health, type, tenant, namespace, datacenter, region,
gateway, mesh participation, source, and enabled state. The default sort places
critical and warning services before passing services.

The service detail page contains:

- **Overview:** identity, scope, aggregate health, configuration, and endpoints;
- **Instances:** address, port, node, owner client, health, and renewal state;
- **Health Checks:** definitions, latest authoritative result, observation time,
  output subject to redaction, and failure reason;
- **Topology:** upstreams, downstreams, gateways, routes, and traffic status;
- **Intentions:** applicable allow, deny, and application-aware policies;
- **Routing:** routing rules, weighted targets, failover, and configuration
  status;
- **Metadata:** tags, metadata, source, and indexes;
- **Events:** scoped system, domain, observation, and audit events.

Authorized users can register, update, enable, disable, or deregister a service.
These operations use the standard replicated command path.

A service that a client registered belongs to that client (platform design
section 7.4). The client registers it again at its next reconciliation (section
8.2), so a deregistration or update made in the interface does not last, and
the interface must not present it as lasting. A service registered on a
client's node by someone other than that client stays until it is deregistered
or its node is removed, because a client removes only what it registered
itself (ACL list, proposal 11). Which services the interface may register, and
on which nodes, is open: implementation plan, open question 5.

Maintenance is set through a client's local API, which listens on the client's
own host only (client list, decision 6). The interface shows maintenance and
cannot set it.

### 5.2 Nodes and clients

Qraft distinguishes server members from clients.

Server rows show:

- node identity and advertised address;
- leader, follower, candidate, or non-voter role;
- reachability and readiness;
- current term, commit index, and applied index;
- replication lag;
- storage and snapshot state;
- lifecycle state, including draining or fenced.

Client rows show:

- node name and node ID, and any name conflict: a `node_name_reserved`
  refusal, a takeover, or a rename (client list, decision 8);
- node status, `registering`, `healthy`, or `unreachable`, which only the
  servers set. A node's status is not service health and does not use health
  states or colours;
- last contact; for a healthy node, when it becomes unreachable without a
  heartbeat; for an unreachable node, when it will be reaped (platform design
  section 9.2);
- owned services and failing checks;
- datacenter and region. A node has no tenant or namespace;
- version, from the node's metadata;
- for an unreachable node, whether a valid token holds its node identity
  (section 10.2.7).

A client's liveness, readiness, current server endpoint, and last successful
reconciliation are known only to the client. They appear only if clients
report them to the servers: implementation plan, open question 6.

Authorized users can deregister a node. The deregistration removes the node's
services and checks in the same replicated step, and the review step lists
them. A client that is still running registers its node again.

Node and client detail pages provide Summary, Services, Health Checks,
Reconciliation, Metadata, Sessions, Raft, Storage, and Events tabs when those
tabs apply to the selected type.

### 5.3 Health checks

The cross-platform health view supports investigation by service, node, check
type, status, scope, and age. It separates:

- the latest external observation;
- the authoritative replicated health state;
- registration and renewal state;
- expiry or automatic-deregistration deadlines.

The UI makes stale observations and missing renewals distinguishable from an
actively failing check.

Health has five states: `UNKNOWN`, `PASSING`, `WARNING`, `CRITICAL`, and
`MAINTENANCE` (platform design section 7.6). Each has its own label and icon.
`UNKNOWN`, no accepted observation yet, is not shown as passing or failing.

## 6. Connect

### 6.1 Service topology

The topology view visualizes:

- services and service instances;
- sidecars and gateways;
- upstream and downstream dependencies;
- allowed, denied, and unmatched intentions;
- routing paths and weighted destinations;
- health and configuration status;
- cross-datacenter, peered, and federated edges.

Topology is an overview and diagnostic surface, not a replacement for a complete
metrics system. Large graphs initially collapse to service-level nodes and allow
the user to expand only the area under investigation.

Selecting a node opens the corresponding service or gateway details. Selecting
an edge opens a panel containing the source, destination, intention, route,
scope, enforcement state, and recent related events.

### 6.2 Intentions

The Intentions page supports create, read, update, and delete workflows. Each
rule shows source, destination, action, scope, precedence, description, and
application-aware match conditions.

The editor includes a preview describing the effective decision and the rules
that would match selected source and destination identities before submission.

### 6.3 Routing

Routing screens manage match conditions, destinations, weights, failover, and
gateway traversal. Validation detects unreachable targets, invalid weight totals,
cycles, missing services, and incompatible scopes before submission where the
API can determine them safely.

### 6.4 Gateways

Gateway views cover ingress, terminating, mesh, and future gateway types. They
show listeners, bound services, addresses, health, routing relationships,
certificates without exposing private material, and configuration errors.

## 7. Coordinate

### 7.1 Key/value browser

The key/value interface uses a directory-style prefix browser with breadcrumbs.
Each entry exposes:

- key and value subject to permission and redaction policy;
- creation and modification indexes;
- flags;
- owning session;
- value size and optional hash;
- tenant and namespace;
- recent related events.

The editor supports plain text and JSON presentation, create, update, delete,
compare-and-set, acquire, and release operations. Compare-and-set workflows show
the expected index, current index, and a difference view when a stale write is
rejected.

### 7.2 Sessions and locks

Session views show owner, scope, TTL, remaining lifetime, renewal status, expiry
behaviour, held keys, and recent events. Authorized users can create, renew, and
destroy sessions.

Lock views show the key, owning session, acquisition index, waiters or contention
summary where available, and invalidation reason. The UI never bypasses session
ownership rules.

### 7.3 Watches

Watch views use the authoritative applied or modification index as the correctness
cursor. A history-gap response causes the UI to re-read current state and resume
from the new index. The interface reports this resynchronization instead of
silently implying uninterrupted delivery.

## 8. Multi-cluster

### 8.1 Datacenters and regions

Datacenter and region pages show cluster identity, reachability, service counts,
gateway state, replication health, and current operational incidents. Switching
scope preserves the user's resource type and filters where those filters remain
valid.

### 8.2 Peers and federation

Peer and federation screens show:

- local and remote identity;
- connection and authentication state;
- last successful synchronization;
- replication lag and errors;
- imported and exported services;
- applicable gateways and routes;
- recent configuration and connectivity events.

Creating, rotating, disabling, or removing a peer relationship is permission
controlled and auditable. Secret material is displayed only at the explicitly
defined one-time boundary, if the protocol requires it.

## 9. Operate

### 9.1 Cluster overview

The cluster overview answers whether the cluster is safe to operate. It shows:

- overall state: healthy, degraded, no quorum, recovering, draining, or fenced;
- each server's readiness conditions, as `/health/ready` names them:
  `draining`, `unavailable`, `fenced`, `recovering`, `removed`, and
  `no_leader` (platform design section 6.1), each with its explanation. A
  `removed` server, whose removal from the configuration has committed,
  never campaigns again and is shown as such, not as failed;
- current leader, role distribution, and term;
- available voting members versus quorum requirement;
- commit and applied indexes;
- maximum and per-member replication lag;
- peer reachability;
- service and client health totals;
- transition queue depth and saturation;
- WAL state and size;
- snapshot boundary, age, and last result;
- recent elections, failures, and recoveries.

### 9.2 Raft members

The member view provides a table and optional reachability matrix. It exposes
leader and voter badges, match and next indexes, last contact, replication lag,
leadership generation, readiness, and lifecycle state.

Membership-changing actions must explain their quorum impact before submission.
Unsafe operations are not offered merely because an API exists.

Removing a server (`DELETE /v1/operator/raft/peer`, platform design section
12.5) is the one membership change with an API. The interface offers it from
increment UI-5 of the implementation plan, with the quorum before and after,
and reports the server's refusals (`409 refused`, with its reason) as they
come. Servers join, and non-voters are promoted, through server configuration
and the leader's own policy; the interface shows these changes and does not
offer them.

### 9.3 Storage and snapshots

The storage view exposes:

- WAL implementation, health, size, and durability mode;
- directory-lock and fenced state;
- last successful sync and recovery;
- snapshot index, term, format version, checksum state, and age;
- snapshot publication, installation, and compaction progress;
- retained log boundary;
- corruption, migration, or compatibility failures.

A storage-fenced node receives a persistent critical banner. It must not look
like an ordinary unhealthy service because a fenced node cannot safely continue
participating in consensus or accepting mutations.

A server takes a snapshot when its log reaches the configured threshold. No
API takes one on demand, so the interface does not offer it. Offline recovery
(`qraft operator raft inspect` and `recover`) runs only from the command line,
against a stopped server's directories. The interface shows recovery and
fencing state and links the procedures in
[`RAFT_STORAGE_OPERATIONS.md`](../docs/RAFT_STORAGE_OPERATIONS.md); it does
not perform them.

### 9.4 Events

The event experience follows
[`QRAFT_EVENT_ARCHITECTURE.md`](../docs/QRAFT_EVENT_ARCHITECTURE.md):

- **System:** node lifecycle, elections, leadership, replication, snapshots,
  storage, queue saturation, and fencing;
- **Domain:** services, clients, keys, sessions, locks, tenants, namespaces, and
  replicated configuration;
- **Observation:** health checks, heartbeats, reconciliation, probes, and local
  resource warnings;
- **Audit:** principal, authorization decision, operation, target, result, and
  committed index.

The page supports filters for category, severity, node, tenant, namespace, term,
correlation ID, and minimum committed index. It provides bounded history, live
streaming, pause and resume, cursor visibility, export subject to authorization,
and explicit history-gap or subscriber-overflow states.

The initial bounded journal is labelled as ephemeral. The UI does not claim
durable or complete audit history until a durable audit sink and retention policy
exist.

### 9.5 Metrics and external observability

The built-in metrics page presents bounded operational summaries rather than
attempting to replace Prometheus and Grafana. It should support configured links
to an external cluster, service, node, or gateway dashboard while preserving the
current scope in the link parameters.

### 9.6 Configuration and diagnostics

The configuration page shows effective values with secrets redacted. It
distinguishes:

- static startup configuration;
- dynamically replicated configuration;
- defaults and explicitly configured values;
- source and last-change metadata;
- settings that require restart.

Diagnostics correlate request IDs, node IDs, role, term, tenant, namespace,
events, and metrics. General UI views never expose credentials, tokens, raw
command payloads, protected key/value data, or sensitive health output.

## 10. Govern

### 10.1 Tenants and namespaces

Tenant and namespace pages support inspection and administration of identity,
enabled state, metadata, policy assignments, quotas, resource totals, and recent
events. Disabling or removing a scope shows the affected services, sessions,
locks, routes, and peer exports before confirmation.

### 10.2 Access control

Access control is part of the system's integrity, not a list of resources to
browse. It follows Consul's token model, as the ACL list
([`task-list-acl-and-tokens-2026-10-28.md`](task-list-acl-and-tokens-2026-10-28.md))
sets it out, including the decisions of 2026-10-27 in its proposals 16 to 19.
The section contains Status, Tokens, Policies, and an explanation of
decisions, and presents tokens and policies through their relationships
rather than as disconnected lists. Roles and Auth Methods are reserved
(section 2).

#### 10.2.1 Security status

The Status page answers whether the cluster's access control can be trusted.
It shows:

- the security profile, `production` or `development`, and whether ACLs are
  enabled, from the bootstrap data (ACL list, proposal 16);
- transport security. Until the transport security list exists, tokens cross
  the network unencrypted, and the page repeats the server's startup warning;
- the management token: whether it exists, its accessor ID, and that it was
  installed from the management file;
- the anonymous token's policies, with a warning when any of them grants
  `write`, because every caller without a token gets it;
- how many tokens hold `acl:write` without an expiry, with a warning when only
  one does, because the next change to it would be a lock-out (section
  10.2.6);
- tokens that expire soon, and what they hold: node identities, service
  identities, `acl:write`.

Two states carry a persistent banner on every page (principle 3.7):

- **A development server without access control.** "No access control:
  anyone who can reach this API can change the cluster." The interface offers
  every function, and every review step repeats the warning. A production
  server cannot be in this state: it refuses to start without ACLs.
- **The management token in use.** "Signed in with the management token:
  unrestricted access. Use a scoped token for routine work."

#### 10.2.2 Signing in

- With ACLs enabled, the interface asks for a token secret before it makes any
  API call. The first operator signs in with the management secret from the
  server's file (ACL list, proposal 12). There is no bootstrap endpoint.
- The secret is typed or pasted, sent as `X-Qraft-Token`, never placed in a
  URL, and never shown again after it is entered. Signing out discards it.
- `401 token_invalid` (the token is unknown, expired, or was revoked during the
  session) ends the session. The interface discards the secret and asks for
  another; it never retries with the old one. `403 permission_denied` is an
  outcome of one operation and does not end the session.
- With ACLs disabled, on a development server, there is no sign-in, and the
  token menu says that there is no access control.
- The token menu shows the token in use: accessor ID, description, policies,
  identities, and expiry, from `GET /v1/acl/token/self`, with a warning when it
  expires soon. It lets the operator change token.
- How the browser holds the secret between page loads is decided with
  increment UI-5 of the implementation plan.

#### 10.2.3 Tokens

- **List.** Accessor ID, description, policies, identities, expiry, and
  creation time. The management token, the anonymous token, and tokens
  installed from the file are marked. Filters cover policy, identity, and
  expiring soon. Lists, events and audit records name a token by its accessor
  ID only.
- **Detail.** Links to each policy, to the node of a node identity, and to the
  service of a service identity, and an explanation of what the token may do
  (section 10.2.5).
- **Create.** The server stores only a hash of the secret and returns the
  secret once, in the response to the committed command. The interface shows
  it at that point only, with a copy control and a warning that it cannot be
  shown again, and clears it when the dialog closes. No later view and no
  permission reveals a secret.
- **Creation is not idempotent.** Each attempt makes a new secret, so the
  interface never retries it automatically. When the outcome is unknown, the
  token may exist with nobody holding its secret. The interface says so,
  searches for the token by its description, and offers to delete what it
  finds before creating again.
- **Update.** Description, policies, identities, and expiry. The review step
  shows the permissions added and removed.
- **Delete, which is revocation.** The review step shows what depends on the
  token where the interface can tell: the client of a node identity becomes
  not ready, and its node becomes unreachable after the node TTL. The result
  says the revocation takes effect on the leader at once and on each follower
  when it applies the deletion's index; until then a follower may still
  accept the token for reads (ACL list, proposal 10).
- **No rotation.** A secret cannot be read back or reissued. The interface
  offers replacement as a guided sequence: create a new token with the same
  policies and identities, move its users to it, then delete the old one.
- **The management token.** Marked as installed from the file. Before a
  deletion, the review step says the token returns at the next leadership
  change while any server's file holds its secret, and gives the revocation
  order: replace the secret in every server's file, restart the leader so the
  new token is installed, check it exists, then delete the old one (ACL list,
  proposal 12).
- **The anonymous token** cannot be deleted. Its policies can be changed, with
  the warning of section 10.2.1.

#### 10.2.4 Policies

- **List.** Name, a summary of its rules, and how many tokens link to it.
- **Editor.** The policy's JSON, with a structured view of its rules:
  resource, exact name or prefix, access (`read`, `write`, or `deny`), and for
  `service` rules the tenant and namespace, where `*` matches any. The editor
  states the precedence: an exact name beats a prefix, a longer prefix beats a
  shorter one, and `deny` beats an allow of equal specificity. It shows the
  server's refusals, such as an unknown field or resource, at the rule they
  concern. Local checks never imply that the server will accept the policy
  (section 11).
- **Detail.** Rules, the tokens that link to it (`GET
  /v1/acl/tokens?policy={name}`), and explanations for those tokens.
- **Change.** The review step lists the linked tokens the change affects.
- **Delete.** A policy that any token links to cannot be deleted; the server
  answers `409 policy_in_use` (ACL list, proposal 18). The interface lists the
  linked tokens and offers deletion only when there are none. The built-in
  management policy cannot be deleted.

#### 10.2.5 Explaining decisions

An explanation answers "may this token do this, and why". The operator
chooses a token, a resource, a name, an access level and, for a service, the
scope. The server answers through `POST /v1/acl/explain` (ACL list, proposal
19): allow or deny, and the policy and rule, the identity, or the absence of
any match that decided it. It needs `acl:read`. The explanation is available
from the token, policy, service and node pages.

The interface never evaluates rules itself, because an explanation computed
in the browser could differ from what the server enforces. The reverse
question, which tokens may do something, has no API and is not offered.

#### 10.2.6 Lock-out and recovery

The server refuses any change after which no token would hold `acl:write`
without an expiry, with `409 acl_lockout` (ACL list, proposal 17). The review
step predicts the refusal: it shows how many other tokens hold `acl:write`
without an expiry, and warns when a change removes `acl:write` from the token
in use, which the server allows while another token holds it.

When every management secret is lost, recovery is a new secret in the
management file and a leader restart. The interface describes this procedure
and links the runbook; it cannot perform it.

#### 10.2.7 Limits until later lists

- **Transport.** The transport security list will make the server's HTTP
  listener require a client certificate (ACL list, proposal 14). Interface
  users will then need one as well; that list says how they get one.
- **Audit.** The ACL list writes ACL changes and denials to the log only, with
  the accessor ID. Structured audit records wait for the event journal
  (section 10.3).
- **A revoked client token looks like a crash.** A client refused with `401`
  becomes not ready and stops heartbeating, and its node becomes unreachable.
  The servers record no reason. Until denials are recorded as events, the node
  page shows, for an unreachable node, whether any valid token holds its node
  identity, so that an operator can tell revocation from a crash.
- **If ACLs are enabled before increment UI-5,** the read-only interface's
  calls act as the anonymous token, and it shows only what the anonymous token
  may read.

### 10.3 Audit

The audit page supports filters for principal, tenant, namespace, request ID,
operation, target, authorization decision, result, and committed index. It clearly
states the configured retention and durability guarantee.

Audit records need the event journal (event architecture, sections 4.4 and
12). Until it exists the Audit page says that audit records are not
available, and where ACL changes and denials are logged instead. It does not
present an empty list as a record.

## 11. Mutation workflow and safety

Every mutation form follows this sequence:

1. Display the exact resource identity and scope.
2. Validate locally where possible without implying server acceptance.
3. Display a review step for consequential or destructive operations.
4. Submit through the normal authenticated API.
5. Report the structured outcome.
6. Link successful or rejected operations to their event and audit context.

The UI distinguishes:

- committed and applied successfully;
- rejected by validation;
- rejected by compare-and-set or ownership rules;
- refused because the token is unknown or expired (`401 token_invalid`),
  which asks the operator for another token rather than offering a retry;
- refused for a missing permission (`403 permission_denied`), or hidden by
  policy;
- refused to protect integrity, with the names that explain it:
  `409 acl_lockout`, `409 policy_in_use`, `409 node_name_reserved`;
- submitted to a follower with a leader hint;
- retryable failure;
- outcome unknown after submission;
- node draining, overloaded, fenced, or unavailable.

An unknown outcome is never displayed as a definite failure. The UI offers a
safe state refresh or idempotent retry only when the API classifies that action
as safe. An operation that is not idempotent, such as creating a token, is
never retried automatically; its unknown outcome is settled by reading state
first (section 10.2.3).

On a development server without access control, every review step carries the
warning of section 10.2.1.

Destructive confirmations include the resource identity, scope, and observable
impact. High-impact actions such as tenant disablement, namespace deletion,
membership changes, gateway removal, and peer removal also show dependent
resources.

## 12. Live updates, consistency, and indexes

Blocking queries or feature-specific watches keep lists and detail pages current.
Users can pause live updates when investigating a stable snapshot or operating a
large cluster.

Every administrative read exposes:

- the selected consistency mode;
- the server that answered;
- the applied state index;
- stale or resynchronized state where applicable.

Changing consistency mode refreshes the view and preserves filters. A stale read
is visibly labelled, particularly on mutation review pages.

## 13. Empty, loading, and failure states

Empty states explain whether the result is caused by:

- no resources existing;
- filters excluding resources;
- insufficient permission;
- a selected scope with no matching resources;
- the feature being disabled;
- the server being unable to provide an authoritative result.

Loading states preserve the previous successful content when safe and show that
it may be stale. Errors include the request or correlation ID, retryability,
leader hint, and a direct route to relevant diagnostics when available.

## 14. Packaging and security constraints

The UI is compiled into static assets, embedded in the executable JAR, and served
by server mode below the configured administrative path. Client mode does not
serve the interface.

The UI must:

- use classpath resources in production;
- share the API listener, TLS, authentication boundary, limits, and lifecycle;
- keep API, health, metrics, and debug routes outside client-side route fallback;
- use hashed immutable assets and a revalidated bootstrap document;
- include no bearer credentials or secrets in bootstrap data, which carries
  the security profile and the authentication mode, `none` or `token`
  (section 10.2.1);
- apply explicit content types, `nosniff`, a restrictive content security policy,
  and configured security headers;
- support an explicit development-only filesystem asset override;
- be disableable without producing a different runtime artifact.

## 15. Delivery sequence

The shell and navigation should be designed for the complete roadmap while
feature flags expose only backed capabilities.

### Stage 1: Operational discovery

- application shell, scope selection, consistency selection, and cluster strip;
- Services, service instances, Nodes and Clients, and Health Checks;
- Cluster Overview and Raft Members;
- Storage and Snapshots;
- read-only Events and Configuration.

### Stage 2: Coordination and mutations

- service registration and deregistration;
- Key/Value CRUD and compare-and-set;
- Sessions, Locks, and Watches;
- mutation result, leader hint, retry, and unknown-outcome handling;
- access control (section 10.2): security status and banners, sign-in, the
  token menu, Tokens, Policies, and explanations, which every mutation
  depends on;
- server removal, with its quorum review (section 9.2);
- initial administrative audit display.

### Stage 3: Connectivity

- Topology;
- Intentions;
- Routing;
- Gateways;
- service-level metrics links.

### Stage 4: Multi-cluster and governance

- Datacenters, Regions, Peers, and Federation;
- imported and exported services;
- Tenants, Namespaces, quotas, and policy assignments;
- Roles and Auth Methods once a list delivers them (section 2);
- durable audit integration when its storage contract exists.

## 16. Acceptance criteria

The administrative experience is complete when:

- the application shell can accommodate every planned feature without a major
  navigation redesign;
- Services provides an efficient default landing page while cluster risk remains
  visible;
- users can trace service health from service to instance, node, check,
  observation, committed state change, and audit record;
- topology, intentions, routes, gateways, peering, and federation share one
  consistent identity and scope model;
- all mutations use authenticated, authorized, replicated APIs;
- the security profile, access control's state, and use of the management
  token are always visible, and the interface upholds the ACL list's
  integrity rules: a secret shown once, no automatic retry of token
  creation, lock-out refusals predicted and reported, linked policies
  protected, and the management token's revocation order given;
- mutation outcomes accurately represent commit uncertainty and retry safety;
- cluster, Raft, transition, storage, snapshot, recovery, and fencing states are
  observable;
- live views recover explicitly from event-history gaps;
- secrets and protected values are redacted consistently;
- permissions govern navigation, resource visibility, field visibility, and
  available actions;
- the packaged runtime serves the interface without external frontend files or a
  separate deployment.

## 17. Related documents

- [`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md): API readiness, view map, and delivery increments
- [`CONSUL_FEATURE_IMPLEMENTATION_PLAN.md`](CONSUL_FEATURE_IMPLEMENTATION_PLAN.md)
- [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md)
- [`QRAFT_EVENT_ARCHITECTURE.md`](../docs/QRAFT_EVENT_ARCHITECTURE.md)
- [`RAFTLOG_EXTERNALISATION_TDD_PLAN.md`](../docs/archive/RAFTLOG_EXTERNALISATION_TDD_PLAN.md)
- [`SNAPSHOT_SERIALIZATION_REVIEW_POSTMORTEM.md`](../docs/archive/SNAPSHOT_SERIALIZATION_REVIEW_POSTMORTEM.md)
- [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)
