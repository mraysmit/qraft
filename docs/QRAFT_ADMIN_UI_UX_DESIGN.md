# Qraft Administrative UI and UX Design

**Status:** Draft  
**Last updated:** 2026-09-14

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

The current platform design identifies service-mesh data-plane proxying as a
non-goal, and the current Consul-style implementation plan does not yet describe
the mesh, routing, gateway, peering, or federation roadmap. Those documents must
be updated when the feature contracts are defined. This UX design reserves the
navigation and interaction model for those capabilities so they can be added
without reorganizing the product.

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

## 4. Application shell

### 4.1 Top bar

The persistent top bar contains:

- product and cluster identity;
- tenant and namespace selector;
- datacenter and region selector;
- read-consistency selector: `Default`, `Consistent`, or `Stale`;
- current cluster state and leader;
- global search;
- notifications and active-user or token menu.

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
service, storage, or event view that explains the condition.

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
  Tokens
  Policies
  Roles
  Auth Methods
  Quotas
  Audit
```

Navigation entries are controlled by server-provided feature flags and the
authenticated principal's permissions. Features that are not enabled do not
produce dead navigation entries.

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
- **Instances:** address, port, node, owner agent, health, and renewal state;
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

### 5.2 Nodes and agents

Qraft distinguishes server members from client agents.

Server rows show:

- node identity and advertised address;
- leader, follower, candidate, or non-voter role;
- reachability and readiness;
- current term, commit index, and applied index;
- replication lag;
- storage and snapshot state;
- lifecycle state, including draining or fenced.

Agent rows show:

- agent and node identity;
- liveness and readiness;
- current controller endpoint;
- last heartbeat and successful reconciliation;
- owned services and failing checks;
- tenant, namespace, datacenter, and region;
- version and relevant capabilities.

Node and agent detail pages provide Summary, Services, Health Checks,
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
- current leader, role distribution, and term;
- available voting members versus quorum requirement;
- commit and applied indexes;
- maximum and per-member replication lag;
- peer reachability;
- service and agent health totals;
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

### 9.4 Events

The event experience follows
[`QRAFT_EVENT_ARCHITECTURE.md`](QRAFT_EVENT_ARCHITECTURE.md):

- **System:** node lifecycle, elections, leadership, replication, snapshots,
  storage, queue saturation, and fencing;
- **Domain:** services, agents, keys, sessions, locks, tenants, namespaces, and
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

The access-control section contains Tokens, Policies, Roles, and Auth Methods.
It supports the relationships between these resources rather than presenting
four disconnected lists.

Policy and role detail pages show effective permissions and affected identities.
Token lists show public accessor information and never repeatedly expose secret
values. Token creation or rotation follows the explicitly defined one-time secret
display and secure-handling contract.

### 10.3 Audit

The audit page supports filters for principal, tenant, namespace, request ID,
operation, target, authorization decision, result, and committed index. It clearly
states the configured retention and durability guarantee.

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
- unauthorized or hidden by policy;
- submitted to a follower with a leader hint;
- retryable failure;
- outcome unknown after submission;
- node draining, overloaded, fenced, or unavailable.

An unknown outcome is never displayed as a definite failure. The UI offers a
safe state refresh or idempotent retry only when the API classifies that action
as safe.

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
- include no bearer credentials or secrets in bootstrap data;
- apply explicit content types, `nosniff`, a restrictive content security policy,
  and configured security headers;
- support an explicit development-only filesystem asset override;
- be disableable without producing a different runtime artifact.

## 15. Delivery sequence

The shell and navigation should be designed for the complete roadmap while
feature flags expose only backed capabilities.

### Stage 1: Operational discovery

- application shell, scope selection, consistency selection, and cluster strip;
- Services, service instances, Nodes and Agents, and Health Checks;
- Cluster Overview and Raft Members;
- Storage and Snapshots;
- read-only Events and Configuration.

### Stage 2: Coordination and mutations

- service registration and deregistration;
- Key/Value CRUD and compare-and-set;
- Sessions, Locks, and Watches;
- mutation result, leader hint, retry, and unknown-outcome handling;
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
- Tokens, Policies, Roles, and Auth Methods;
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

- [`CONSUL_FEATURE_IMPLEMENTATION_PLAN.md`](CONSUL_FEATURE_IMPLEMENTATION_PLAN.md)
- [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md)
- [`QRAFT_EVENT_ARCHITECTURE.md`](QRAFT_EVENT_ARCHITECTURE.md)
- [`RAFTLOG_EXTERNALISATION_TDD_PLAN.md`](RAFTLOG_EXTERNALISATION_TDD_PLAN.md)
- [`SNAPSHOT_SERIALIZATION_REVIEW_POSTMORTEM.md`](archive/SNAPSHOT_SERIALIZATION_REVIEW_POSTMORTEM.md)
- [`PROJECT_STANDARDS.md`](PROJECT_STANDARDS.md)
