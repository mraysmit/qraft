# Qraft Administrative UI and UX Design

**Last updated:** 2026-11-21

## 1. Purpose

Qraft has a built-in administrative interface for discovering, configuring,
securing, and operating the platform. It follows the catalog and
resource-browsing patterns of HashiCorp Consul and adds Raft, storage, event,
tenancy, and access-control workflows.

The interface is not a separate control plane. It uses the same authenticated
and authorized HTTP APIs as every other caller, on the server's own HTTP
listener, plus a few read-only routes of its own under `/v1/internal/ui/`
(`services`, `scopes`, `sessions`, `locks`, `audit` and `acl/status`). Every
replicated change it makes follows the normal Raft command path, with the same
consistency, leader-forwarding, retry, and unknown-outcome semantics.

This document describes the interface's information architecture, screens,
interaction patterns, and safety rules. The code is under `src/main/ui`. Build
and runtime packaging are described in
[`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md).

A self-contained mockup,
[`console-prototype/qraft-admin-mockup.html`](../console-prototype/qraft-admin-mockup.html),
uses invented data and shows some views that are not built, labelled
"Reserved". It is a design reference; where it differs from this document, this
document governs.

## 2. Product scope

The interface covers:

- service discovery and health;
- key/value state, sessions, locks, and watches;
- cluster membership, Raft consensus, storage, and snapshots;
- events and configuration;
- tenants, namespaces, and audit records;
- access control: security status, tokens, policies, and explanations;
- authenticated, reviewed, and auditable changes.

Connect (topology, intentions, routing, gateways), Multi-cluster (datacenters,
regions, peering, federation), quotas, metrics, diagnostics, and the Roles and
Auth Methods pages are not built (section 15). They produce no navigation
entries.

## 3. Design principles

### 3.1 Services are the primary working surface

The Services page is the landing page. It is the fastest path to the resource
most operators and application teams use daily. Cluster safety stays visible
through a persistent status strip (section 4.2) rather than a separate
dashboard the user must visit first.

### 3.2 Scope is always explicit

The tenant and namespace in use are visible in the top bar and in the address.
Every scoped list, detail page, form, review, event, and audit record shows the
scope that applies to it. A change never relies on scope that was visible only
on a previous screen.

A resource that has no scope says so, rather than showing the top bar's scope
as if it applied. Nodes, sessions, Raft and storage state, configuration,
tokens and policies have no tenant or namespace.

Qraft runs one datacenter. Datacenter and region are attributes of nodes and
service instances, shown in their lists.

### 3.3 State and observations are distinguished

The interface distinguishes authoritative replicated state from node-local
observations. A health check's observation, made by a client, is shown apart
from the time the servers accepted it. A node's status is not service health.

### 3.4 Operational risk is visible

Fenced storage, a missing leader, unreachable servers, an unready server, a
removed server, history gaps, and unknown outcomes use persistent messages on
the page, not transient notifications. The fenced-storage banner cannot be
dismissed.

### 3.5 Permissions shape the experience

What the token in use may do determines which views, values and actions are
offered. After sign-in the interface asks the server once whether the token
holds `operator:read`, `operator:write`, `acl:read` and `acl:write`, and each
page that offers a change asks for the permission it needs on its resource. A
view the token may not read says which permission it needs, rather than
failing later.

### 3.6 Colour is never the only signal

Health, severity, role, and lifecycle state are given in text, with marks and
colour. Each health state and each event severity has its own label.

### 3.7 Security state is always visible

The security profile and whether access control is on are always on screen, in
the status strip. Two states carry a banner on every page that cannot be
dismissed: a development server without access control, and a session using a
management token (section 10.2.1). Security is never inferred from the absence
of an error.

## 4. Application shell

### 4.1 Top bar

The top bar holds, from left to right:

- the product name, Qraft;
- the primary navigation (section 4.3);
- the tenant and namespace selector, `ScopeSelector`: the replicated tenants,
  and the selected tenant's namespaces. Choosing one reads the current view
  again in it. When the scopes cannot be listed, the selected scope is still
  read, and the selector says why it lists nothing;
- the read-consistency selector, `ConsistencySelector`: `Default`,
  `Consistent` or `Stale`. Choosing one reads the current view again, keeping
  its filters;
- the live-updates control: "Live updates on" or "Live updates paused", with a
  button to pause or resume (section 12);
- the token menu (section 10.2.2), or "No access control".

### 4.2 Cluster status strip

Below the banners, every page shows a status strip, `StatusStrip`. With
`operator:read` it shows:

```text
Answering node: server-2 | Role: Leader | Leader: server-2 | Term: 7 | Readiness: Ready
Quorum: 3 of 3 voters reachable | Failure tolerance: 1 | Replication lag: 0
Security profile: production | Access control: on
```

The quorum, failure tolerance and lag come from the membership read, which only
the leader answers in full; on a follower the strip says the quorum is known to
the leader, and names it. The strip adds a persistent message when servers are
unreachable, the server is not ready (with the conditions it does not meet),
no leader is known, the answering node's storage is fenced, or the node has
been removed from the cluster's configuration.

Without `operator:read` the strip says that the cluster's status needs it, and
shows the security profile and whether access control is on.

### 4.3 Primary navigation

The navigation is fixed in `App.tsx`. The groups are in one bar, each group's
name a label before its links:

```text
Discover     Services · Nodes and Clients · Health Checks
Coordinate   Key/Value · Sessions · Locks · Watches
Operate      Cluster Overview · Raft Members · Storage & Snapshots · Events · Configuration
Govern       Tenants · Namespaces · Audit
Access       Access
```

Operate and Govern need `operator:read`; Access needs `acl:read`. A group with
no link the token may use is not shown. Access is a group of its own because
access control holds the system's integrity together (section 10.2); its one
link opens the Status page, whose own navigation leads to Tokens, Policies and
Explain. Opening a view whose permission the token lacks, by its address,
shows the view's title and the permission it needs.

### 4.4 Addresses

Each view has its own address below the interface's path, `/ui/` by default, so
reloading, bookmarking, sharing a link, and the browser's back and forward
buttons work. The server answers every such address with `index.html`, and a
`GET` of the origin's root redirects to the interface.

| Address | View |
|---|---|
| `/ui/`, `/ui/services` | Services |
| `/ui/services/<name>` | A service |
| `/ui/nodes` | Nodes and Clients |
| `/ui/checks` | Health Checks |
| `/ui/kv?path=<folder or key>` | Key/Value |
| `/ui/sessions`, `/ui/sessions/<id>` | Sessions, a session |
| `/ui/locks` | Locks |
| `/ui/watches?target=<key, prefix, service, sessions or events>&name=<name>` | Watches |
| `/ui/cluster`, `/ui/members`, `/ui/storage` | Cluster Overview, Raft Members, Storage & Snapshots |
| `/ui/events?category=<category>&correlation=<request ID>` | Events |
| `/ui/configuration` | Configuration |
| `/ui/tenants`, `/ui/tenants/<name>` | Tenants, a tenant |
| `/ui/namespaces`, `/ui/namespaces/<tenant>/<name>` | Namespaces, a namespace |
| `/ui/audit?<filters>` | Audit (section 10.3) |
| `/ui/access` | Access control status |
| `/ui/access/tokens`, `/ui/access/tokens/<accessor ID>` | Tokens, a token |
| `/ui/access/policies`, `/ui/access/policies/<ID>` | Policies, a policy |
| `/ui/access/explain` | Explain |

The selected scope is the query's `tenant` and `namespace`, and the selected
consistency its `consistency`; the defaults are left out. The interface's links
keep them. A key or folder is in the query because a key may end in `/` or hold
a dot, which the server's address rules do not take in a path. An address that
names no view says so and links Services.

## 5. Discover

### 5.1 Services

The Services page lists every service in the selected scope from one summary
read, `GET /v1/internal/ui/services`, followed by a blocking query. Services
needing attention come first: critical, warning, maintenance, unknown, passing,
then services with no instances, each group by name. Each row shows:

- the service name, linked to its page;
- its worst health, and its instances counted in all and by health;
- its tags;
- its tenant and namespace;
- the datacenters and regions its instances run in;
- its last change, as the highest modification index among its instances.

A service's page has these tabs:

- **Overview:** scope, aggregate health, instances and how many are enabled,
  datacenters and regions;
- **Instances:** instance ID, address and port, node, the node's status,
  health, and whether it is enabled;
- **Health Checks:** each check of each instance, its status, whether it is
  required, when a client observed it and when the servers accepted it, its
  renewal deadline or that it expired, and its output;
- **Metadata:** each instance's tags, metadata and indexes;
- **Events:** the service's registrations, deregistrations and health changes
  in the selected scope, from the answering server's journal, followed live;
- **Changes**, to a token with `service:write` on the service in the scope:
  register, update, enable, disable and deregister instances;
- **Access**, with access control on and `acl:read`: an explanation of what a
  token may do with the service (section 10.2.5).

The interface registers instances only on nodes the catalog already has, and
creates no node, since a node without a client is reaped. A service a client
registered belongs to that client: the client registers it again at its next
reconciliation, so a deregistration or update made in the interface does not
last. A service registered on a client's node by anyone else does not last
either if it is in the client's tenant and namespace, as the client removes it;
in another tenant or namespace it stays until it is deregistered or its node is
removed. The catalog does not record which client registered an instance, so
each review says what a client on the node does to the change.

Maintenance is set through a client's local API, which listens on the client's
own host only. The interface shows maintenance and cannot set it.

### 5.2 Nodes and clients

The Nodes and Clients page has two tables.

**Servers**, with `operator:read`, from the membership read: server name,
address, role (leader or follower), voter or non-voter, reachability, match
index, lag, and last contact. Only the leader knows the progress fields; a
follower says so.

**Clients**, the catalog's nodes, followed by a blocking query:

- node name and node ID;
- node status: `registering`, `healthy` or `unreachable`, which only the
  servers set. A node's status is not service health and takes no health
  label or colour;
- address, datacenter and region. A node has no tenant or namespace;
- version, from the node's `qraft.version` metadata;
- registration time and last heartbeat;
- the services it owns and its failing checks, counted in the selected scope.

With access control on and `acl:read`, the page offers an explanation for the
`node` resource (section 10.2.5).

### 5.3 Health checks

The Health Checks page lists every accepted check in the selected scope,
across services, needing attention first. A status filter (All, Critical,
Warning, Maintenance, Unknown, Passing) and a text filter over service,
instance, node, check and output narrow the one listing read. Each row shows
the service, instance, node, check, status, when a client observed it and when
the servers accepted it, its renewal deadline or that it expired, and its
output.

Health has five states: `UNKNOWN`, `PASSING`, `WARNING`, `CRITICAL`, and
`MAINTENANCE` (platform design section 5.4). Each has its own label. `UNKNOWN`,
no accepted observation yet, is shown as neither passing nor failing. An
expired check is said to be expired.

## 6. Connect

Not built (section 15).

## 7. Coordinate

### 7.1 Key/value browser

The Key/Value pages browse the selected scope's keys by folder, with
breadcrumbs, at `/ui/kv?path=<folder or key>`. Both a folder and an entry
follow the server live, in the selected scope and consistency.

- A folder lists one level: folders first, then keys, each by name. When the
  server left out keys the token may not read, the page says so.
- An entry's page shows its value, its scope, size, flags, creation,
  modification and lock indexes, and the session that holds it. A value
  appears only on its entry's page: laid out when it is a JSON object or list,
  and in base64 when it is not UTF-8 text. Flags above the largest integer the
  browser holds exactly are said to be so.
- An entry's recent events, with `operator:read`, are its `key.set`,
  `key.deleted`, `key.tree_deleted`, `lock.acquired` and `lock.released` from
  the answering server's journal.

Changes follow `key:write` on the key, or on a folder's prefix, in the selected
scope:

- every update and deletion is a compare-and-set at the index the page read,
  and a creation is one at 0, so it succeeds only if no key of that name
  exists;
- the editor refuses a value over the server's 512 KiB limit, flags that are not
  a whole number the browser holds exactly, and a key name that is empty or
  ends in `/`;
- a live update never replaces a draft: the editor says the entry changed
  under it, and keeps the draft. A refused compare-and-set shows the index the
  write expected, the index now held, and the line difference from the value
  now held to the one sent;
- a folder below the top offers deleting every key under it, after a review
  that counts them; the whole store is not offered for deletion.

On an entry, acquiring needs `key:write` and a live session chosen from those
the token may read. Releasing is offered only with the session that holds the
key, and needs `session:write` on that session's node as well. A refused
acquisition shows the session that holds the key, or the lock delay that runs.

### 7.2 Sessions and locks

Sessions belong to no tenant or namespace, so the scope selector does not apply
to them. The Sessions page lists the sessions the token may read, each with its
ID, name, node, behaviour, TTL, lock delay, time left, keys held, and creation
index. A session's page shows the same, with its last renewal and when it
lapses, the keys it holds, each linked to its entry in its own scope, and its
recent events. Both follow the server live.

- The remaining lifetime and the last renewal come from the leader, which
  alone keeps the timers. A follower's page says it cannot tell them. The time
  left counts down by the browser's clock.
- A session is created on a node the catalog has, with its TTL and lock delay
  checked against the server's limits before the review. A session with a TTL
  can be renewed; the review says when it lapses after the leader takes the
  renewal. Destroying one is reviewed with the keys it holds and what its
  behaviour does to them. Each needs `session:write` on the session's node.

The Locks page lists, across every scope the token may read:

- the keys sessions hold, each with its scope, session and indexes, never its
  value;
- the lock delays still running;
- recent invalidations from the answering server's journal: each
  `session.destroyed` with its reason, `destroyed`, `ttl` or `node`.

Waiters are not shown: a contender waits with a blocking read of its own,
which the server cannot count.

### 7.3 Watches

The Watches page runs one watch, in the tab alone, with no server-side
registration, on a key, the keys under a prefix, a service's health, the
sessions, or, with `operator:read`, the event journal. A watch of state is a
blocking query on its index; a watch of the journal is the event feed (section
9.4).

Its log, of at most 500 rows, lists each delivery with its index and what
changed, never a value, and each resynchronization as a row of its own: an
index that went back, a history gap, another boot, or an overflow. After a
resynchronization the state is described afresh rather than as a change. The
live-updates control pauses the watch, and the page says so. Choosing another
target, scope or consistency starts a new watch.

## 8. Multi-cluster

Not built (section 15).

## 9. Operate

### 9.1 Cluster overview

Cluster Overview shows, each from its own read so that one that fails leaves
the rest:

- the servers, how many are reachable, and the leader; on a follower, that
  their progress is known to the leader;
- the quorum size, the voters, and the failure tolerance;
- the services and instances in the selected scope;
- the client nodes;
- the answering server's storage: its size, its snapshot index, and the
  transitions queued;
- the newest 10 system events of the answering server's journal: elections,
  leadership, snapshots and fencing.

A fenced storage banner leads the page when the storage is fenced. The page
links Raft Members, Storage & Snapshots and Events.

### 9.2 Raft members

Raft Members shows each server's progress and the quorum as the leader sees
them: server, address, role, voter or non-voter, reachability, match index,
next index, lag, last contact, and health. On a follower it shows the
configuration the follower holds, which leader it follows, and that only the
leader knows each server's match index, lag and contact.

Removing a server (`DELETE /v1/operator/raft/peer`, platform design section
8.3) is the one membership change the interface offers, to a token with
`operator:write`, and never for the last voter. The review shows the server's
identity, the voters and the quorum before and after, the failure tolerance
after, the server's reachability, a warning when it is the leader, and that a
removed server still running must be stopped and not restarted with its old
data. Without access control it asks for the operator token for that one
request and keeps it for no other. The server's refusals, such as
`409 refused` with its reason, are shown as they come.

Servers join, and non-voters are promoted, through server configuration and the
leader's own policy; the interface shows these changes and does not offer them.

### 9.3 Storage and snapshots

Storage & Snapshots shows the answering server's storage:

- its size, or that the server has no storage directory;
- its snapshot;
- the first log index it still holds;
- its last, committed and applied log indexes;
- the transitions queued;
- whether it is fenced, and why;
- the last snapshot failure since the server started.

A fenced server gets a persistent critical banner that cannot be dismissed. It
does not look like an ordinary unhealthy service, because a fenced node cannot
safely go on taking part in consensus or accepting changes.

A server takes a snapshot when its log reaches the configured threshold. No API
takes one on demand, so the interface does not offer it. Offline recovery
(`qraft operator raft inspect` and `recover`) runs only from the command line,
against a stopped server's directories. The interface shows fencing state and
does not perform recovery; [`OPERATIONS.md`](../docs/OPERATIONS.md) describes
the procedures.

### 9.4 Events

The Events page follows
[`QRAFT_EVENT_ARCHITECTURE.md`](../docs/QRAFT_EVENT_ARCHITECTURE.md). It reads
the answering server's whole journal in the category chosen, newest first, and
then follows it live:

- the category, `system`, `domain`, `observation` or `audit`, is asked of the
  server; the address may name it, and a request's correlation ID, as a
  change's link to its audit events does;
- the other filters, least severity, event tenant, event namespace, term,
  correlation ID and least committed index, narrow the events read, so none is
  missed;
- each event shows when it was observed, its severity, category, type, the
  server, the term, the committed index, its scope, and its payload fields;
- the page says how many of the events read are shown, and exports the events
  shown as a JSON file named for the answering server and the time. Nothing is
  sent to the server.

The page carries a label that the journal is ephemeral: the server keeps only
its last events, up to its capacity, since it started, and loses them when it
stops; it is not a durable or complete history, and every other server keeps
its own. The feed's state is shown: reading, following or paused at its cursor,
or stopped and trying again. When the feed read the journal again after a
history gap or another boot, or resumed a watch after an overflow, a persistent
message says so.

The same feed shows recent events on Cluster Overview, a service's page, a
key's entry, a session's page, the Locks page, a tenant's and a namespace's
pages, the Watches page, and the Audit page when no audit file is configured.

### 9.5 Metrics and external observability

Not built (section 15).

### 9.6 Configuration

The Configuration page shows the answering server's effective settings: each
setting's key, its value, whether it is configured or a default, and whether a
change applies at once or needs a restart. A secret is withheld and marked
"Redacted". A source filter (All, Configured, Default) and a text filter over
the keys narrow the list. Every setting is static, read at startup; the
replicated configuration is the Raft server configuration, which Raft Members
shows.

## 10. Govern

### 10.1 Tenants and namespaces

The Tenants and Namespaces pages, with `operator:read`, list every tenant and
namespace with what it holds, from one read of every scope,
`GET /v1/internal/ui/scopes`, followed live:

- a tenant or namespace has a name, a description and its indexes;
- the totals are its services, instances, keys, and held keys with their
  sessions;
- a tenant's page lists its namespaces; a namespace's page links its services
  in its scope and its held keys;
- recent events are the answering server's journal in the scope, including the
  scope's own `tenant.*` and `namespace.*` events.

Creating a tenant or namespace, changing its description, and deleting it need
`operator:write`. A tenant is created with its default namespace. A creation is
sent once: an unknown outcome is settled by reading the tenants, never by
sending it again. A deletion's review counts and lists what it removes, and
says that a session holding a key there goes on, holding nothing there. The
default tenant is not offered for deletion, and a default namespace is deleted
only with its tenant.

### 10.2 Access control

Access control is part of the system's integrity, not a list of resources to
browse. It follows Consul's token model, as the ACL list
([`task-list-acl-and-tokens-2026-11-07.md`](../docs/archive/task-list-acl-and-tokens-2026-11-07.md))
sets it out. The section holds Status, Tokens, Policies, and Explain, and
presents tokens and policies through their relationships. Without access
control, only Status is offered, since the server has no tokens or policies.

#### 10.2.1 Security status

The Status page, with `acl:read`, answers whether the cluster's access control
can be trusted. With access control on, it reads `GET /v1/internal/ui/acl/status`,
which the server decides with the authorizer it enforces with, and shows:

- the security profile, whether ACLs are enabled, and the default policy;
- the transport: HTTPS, with or without a required client certificate; on plain
  HTTP, a warning that tokens cross the network unencrypted;
- the management tokens, each marked when it was installed from the management
  file, with its expiry;
- the anonymous token's policies, with a warning when they grant any write,
  because every caller without a token gets it;
- the tokens that hold `acl:write` without an expiry, with a warning when only
  one does, because the next change to it would be a lock-out (section 10.2.6);
- the tokens that expire within the server's window, and what they hold: node
  identities, service identities, `acl:write`;
- how recovery works when every management secret is lost (section 10.2.6).

Without access control the page shows the security profile and the warning "No
access control: anyone who can reach this API can change the cluster."

Two states carry a persistent banner on every page (principle 3.7):

- **A development server without access control:** "This development server
  has no access control: anyone who can reach this server can read and change
  it." A production server cannot be in this state: it refuses to start without
  ACLs and a `deny` default policy. Every review step repeats the warning.
- **A management token in use:** "Signed in with the management token:
  unrestricted access. Use a scoped token for routine work."

#### 10.2.2 Signing in

- With ACLs enabled, the interface asks for a token secret before it makes any
  API call. The first operator signs in with the management secret from the
  server's file, or the one `PUT /v1/acl/bootstrap` returned. The interface
  never calls that endpoint itself.
- The secret is typed or pasted, checked with `GET /v1/acl/token/self`, and sent
  as `X-Qraft-Token` on every request. It is never placed in an address and
  never shown again after it is entered.
- The browser holds the secret in the tab's `sessionStorage`: it lasts across
  reloads of that tab, is not shared with other tabs, and goes when the tab
  closes. A browser that refuses the storage holds it in memory until the page
  is reloaded. It is never put in `localStorage`, a cookie or an address.
- `403 token_not_found` to a request that presented the secret (the token is
  unknown, expired, or was revoked) ends the session: the interface discards
  the secret and asks for another, and never retries with the old one.
  `403 permission_denied` is an outcome of one operation and does not end the
  session.
- With ACLs disabled there is no sign-in, and the token menu says there is no
  access control.
- The token menu shows the token in use: accessor ID, description, policies,
  roles, identities, and expiry, with a warning when it expires within the
  hour. It offers to change token and to sign out, both of which discard the
  secret.

#### 10.2.3 Tokens

- **List.** Accessor ID, description, policies, identities, expiry, creation
  time, and marks: management token, installed from the management file,
  anonymous token. Filters cover policy, identity, and expiring soon. Lists,
  events and audit records name a token by its accessor ID only.
- **Detail.** The same fields, with links to each policy, to Nodes and Clients
  for a node identity, and to the service of a service identity in its scope,
  and an explanation of what the token may do (section 10.2.5).
- **Create.** The server stores only a hash of the secret and returns the
  secret once, in the answer to the committed command. The interface shows it
  then only, with a copy control and a warning that it cannot be shown again,
  and clears it when the panel closes. No later view reveals a secret: every
  read shows `<hidden>`.
- **Creation is not idempotent.** Each attempt makes a new secret, so the
  interface never sends it again by itself. When the outcome is unknown, the
  token may exist with nobody holding its secret. The interface lists the
  tokens with the same description and offers to delete them before creating
  again.
- **Update.** Description, policies, and identities; an expiry cannot change.
  The review lists the policies and identities added and removed, and warns
  when the change may remove `acl:write` from the token in use.
- **Delete, which is revocation.** The review says what depends on the token:
  the client of a node identity becomes not ready, and its node unreachable
  after the node TTL; what registers a service identity can no longer do so. It
  says the revocation takes effect on the leader at once and on each follower
  when it applies the deletion's index, until when a follower may still accept
  the token for reads, and that a client may go on accepting it until its cache
  entry expires, or for as long as it reaches no server. The token in use is
  not offered for deletion.
- **No rotation.** A secret cannot change once the token exists. Replacement is
  a guided sequence: clone the token, move its users to the clone's secret,
  then delete the old one.
- **A management token installed from the file.** Before a deletion, the review
  says the token returns at the next leadership change while any server's file
  holds its secret, and gives the revocation order: replace the secret in every
  server's file, restart the leader so the new token is installed, check it
  exists, then delete the old one.
- **The anonymous token** cannot be deleted. Its policies can be changed: every
  caller without a token gets them.

#### 10.2.4 Policies

- **List.** Name, a summary of its rules, and how many tokens link to it.
- **Editor.** Name, description and rules in Consul's JSON form, checked
  locally only to be JSON; the server decides the rest, and its refusal is
  shown. The rules are shown structured, with the precedence the server
  applies: an exact name beats a prefix, a longer prefix beats a shorter one,
  and where two rules are equally specific `deny` wins, then `write`, then
  `read`.
- **Detail.** Rules, the tokens that link to it
  (`GET /v1/acl/tokens?policy={id}`), and an explanation for a token chosen.
- **Change.** The review lists the linked tokens the change affects.
- **Delete.** A policy that tokens link to can be deleted, and those tokens lose
  what it granted. The review lists the linked tokens, says so, and says that a
  client may go on honouring the policy until its cache entry expires, and that
  the server refuses a deletion after which no token would hold `acl:write`
  without an expiry (`409 acl_lockout`). The built-in `global-management` and
  `builtin/global-read-only` policies cannot be deleted.

#### 10.2.5 Explaining decisions

An explanation answers "may this token do this, and why". The operator chooses
a token, a resource, a name, an access level and, for a service, the scope. The
server answers through `POST /v1/acl/explain`, which needs `acl:read`: allow or
deny, and the policy and rule, the identity, or the default policy that
decided. It is offered on the Explain page, and on the token, policy, service
and node pages.

The interface never evaluates rules itself, because an explanation computed in
the browser could differ from what the server enforces. The reverse question,
which tokens may do something, has no API and is not offered.

#### 10.2.6 Lock-out and recovery

The server refuses any change after which no token would hold `acl:write`
without an expiry, with `409 acl_lockout`. The review predicts the refusal: a
token deletion says how many other tokens hold `acl:write` without an expiry,
and warns when the token is the only one, and an update of the token in use
warns that removing its `acl:write` is allowed only while another token holds
it.

When every management secret is lost, recovery is either a new secret in the
management file and a restart of the leader, or a bootstrap reset through a
file in the leader's data directory. The Status page describes both, and the
operations runbook gives the steps; the interface cannot perform them.

### 10.3 Audit

The Audit page, with `operator:read`, reads the answering server's audit file,
`server.audit.path`, through `GET /v1/internal/ui/audit`, newest first.

- Its filters are principal (accessor ID), request ID, method, route prefix,
  tenant, namespace, result (allowed, denied for 403, failed for a 5xx) and a
  time before which records were written, all in the address. The scope's are
  `inTenant` and `inNamespace` there and are labelled "In tenant" and "In
  namespace", since the scope selector holds `tenant` and `namespace`.
- Each record shows its time, principal, scope, request ID, operation, status
  and decision. A record without a scope shows its scope as not recorded.
- A lost stretch and a line that could not be read each take a row of their
  own, where they are in the file.
- The page states that the records are kept best effort, on each server for
  the requests it answered, until an operator removes or rotates the file, and
  that a record carries no committed index, so that filter is not offered, nor
  a target.
- It says how much of the file a read covered. Older records go on from the
  byte offset the last read stopped at, which the server answers.
- Without a file, the page says so and how to configure one, and lists the
  server's bounded journal of audit events instead, labelled as not a complete
  record.

A change's outcome links both its audit events, on the Events page, and its
audit records, on the Audit page, by its request ID.

## 11. Mutation workflow and safety

Every change follows this sequence:

1. Display the exact resource identity and scope.
2. Check the form locally, without implying that the server will accept it.
3. Display a review of the change's impact.
4. Submit through the normal authenticated API.
5. Report the structured outcome.
6. Link the request's audit events and audit records.

The outcome is one of:

- committed and applied, with the index where the server answers one;
- rejected by validation (400 or 413);
- rejected because it conflicts with the current state (409), such as a stale
  compare-and-set;
- refused to protect integrity, with the code that explains it:
  `acl_lockout`, `node_name_reserved`, `refused`, `self_deletion`;
- refused because the token lacks the permission (`403 permission_denied`);
- refused because the server no longer knows the token
  (`403 token_not_found`), which asks for sign-in rather than offering a
  retry;
- not found (404);
- not applied, and retryable, when the server marks the refusal retryable;
- unknown: the request was sent and no answer came, or the answer was
  `outcome_unknown` (whatever its `retryable` flag), an internal error, a
  gateway's 5xx without the server's error envelope, any other 5xx the server
  did not mark retryable, or not in the server's form;
- any other refusal the server did not mark retryable is shown as a rejection,
  not offered for retry.

An unknown outcome is never shown as a definite failure. The interface offers
to read the current state, and then to submit again only a change that is
idempotent. An operation that is not idempotent, such as creating a token or a
tenant, is never sent again automatically; its unknown outcome is settled by
reading state (section 10.2.3).

On a development server without access control, every review repeats the
warning of section 10.2.1. Destructive actions are marked as such, and their
reviews include the resource identity and its observable impact.

## 12. Live updates, consistency, and indexes

Live reads keep lists and detail pages current (`src/main/ui/src/live.tsx`):

- a read with an index is a blocking query, sent again as soon as it answers,
  waiting up to 30 s at the server;
- a read without an index is read again every 5 seconds, and so is a blocking
  query that answered at once with the same index;
- a failed read keeps the last answer and is tried again after 5 seconds;
- an index lower than the last, as when another server answers or state was
  restored, is a fresh start, and the view says it was resynchronized;
- event feeds read and then follow the journal (section 9.4).

One control in the top bar pauses every live read and feed, for investigating
a stable view or a large cluster; resuming reads again at once.

A page hidden into the browser's back/forward cache, or a tab hidden for
another, releases its live reads and watches at once, and reads again when it
is shown. The server answers over HTTP/1.1, where a browser opens six
connections to it; a blocking query holds one for up to 30 s and a watch for
good. Released when hidden, they leave the six connections to the tabs in view.

Every read shows its metadata: the server that answered, the consistency it
applied, with no known leader where that is so, and the applied index; or, for
a server's own state, such as membership or storage, that the consistency
chosen does not apply. It also says when the view was resynchronized, is being
read again and may be out of date, or is paused. Changing the consistency reads
the view again and keeps its filters.

## 13. Empty, loading, and failure states

An empty list says why it is empty: no resource exists in the scope, the
filters exclude every resource, or the server left out what the token may not
read. A view the token may not read names the permission it needs.

While a view is read again, it keeps the last answer and says it may be out of
date. A failed read shows what failed, the server's message and code, the
request ID, the leader the server named, whether retrying may help, and a
button to read again. An answer not in the server's form says the interface
and the server may be different versions.

## 14. Packaging and security constraints

The interface is compiled into static assets, embedded in the executable JAR,
and served by server mode below `server.ui.path`, `/ui/` by default. Client
mode does not serve it.

- It shares the API listener, TLS, authentication, limits, and lifecycle.
- API, health, and Raft routes stay outside its route fallback: only an
  address below the interface's path whose last segment has no extension is
  answered with `index.html`.
- Content-hashed files below `assets/` are cached as immutable and never fall
  back; `index.html` is never stored, and other files are revalidated.
- The bootstrap object is placed in `index.html` and carries no credentials or
  secrets: the API base path, the interface's path, an empty `features` map,
  the authentication mode, `none` or `token`, and the security profile.
- Every response is marked `nosniff`, sends no referrer, and carries a content
  security policy that allows scripts, styles, images, fonts and requests only
  from the server's origin, and inline styles only with the request's nonce.
- `server.ui.devAssetsDirectory` serves the assets from a directory, for
  development only.
- `server.ui.enabled: false` disables the interface without a different
  runtime artifact.

## 15. Not built

- **Workspace tabs:** several views open side by side in tabs, each with its
  own filters and scroll position, the selected tab alone kept live.
- **A datacenter and region selector** in the top bar.
- **Global search.**
- **Notifications** in the top bar.
- **Search and filters on Services:** across name, instance, node, address,
  tag and metadata, and by health, type, scope, datacenter, region, source and
  enabled state. A service's type, registration source, and mesh and gateway
  participation are not shown.
- **A service's Topology, Intentions and Routing tabs.**
- **Node detail pages and node deregistration** from the interface, with a
  review of the services and checks the deregistration removes. Client rows do
  not show a name conflict, nor when a node becomes unreachable or will be
  reaped.
- **The identity marker:** for an unreachable node, whether a valid token holds
  its node identity, to tell a revoked client from a crashed one.
- **What clients report about themselves.** A client's readiness, current
  server endpoint, and last successful reconciliation are known only to the
  client. Should clients report them to the servers, for example as node
  metadata on each heartbeat, or should the client rows leave them out? The
  first is a change to the client.
- **Roles and Auth Methods pages.** The server has roles, auth methods,
  binding rules and login; the interface shows a token's roles in the token
  menu and on its page, and offers no page for them.
- **Metrics.** Should the Metrics page be a configured link to an external
  dashboard, keeping the current scope in the link, or read bounded summaries
  from the API listener?
- **Diagnostics:** a page correlating request IDs, nodes, terms, scopes, events
  and metrics.
- **Quotas, and a tenant's enabled state, metadata and policy assignments.**
  The platform has none of them.
- **More of Cluster Overview and Raft Members:** each server's readiness
  conditions, the transition queue's saturation, WAL state, snapshot age and
  last result, recent elections and failures, a reachability matrix, and
  leadership generation.
- **More of Storage & Snapshots:** the WAL implementation and durability mode,
  the directory lock, the last sync and recovery, the snapshot's format version
  and checksum state, and publication, installation and compaction progress.
- **Event filters for node and severity at the server,** and events from more
  than the answering server.
- **The Configuration page's dynamic configuration:** every setting is static.
- **Connect:** topology, intentions, routing and gateways.
- **Multi-cluster:** datacenters, regions, peers and federation, with imported
  and exported services.

## 16. Related documents

- [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md)
- [`QRAFT_EVENT_ARCHITECTURE.md`](../docs/QRAFT_EVENT_ARCHITECTURE.md)
- [`OPERATIONS.md`](../docs/OPERATIONS.md)
- [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)
- History: the archived implementation plan,
  [`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](../docs/archive/QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md),
  and the task lists it links.
