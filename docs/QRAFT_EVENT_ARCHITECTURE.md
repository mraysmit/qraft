# Qraft Event Architecture

**Last updated:** 2026-11-21

## 1. Purpose

Qraft records what happens in a server as events: Raft activity, committed
changes to replicated state, and requests answered under a token. The events
serve four purposes:

1. expose meaningful node and cluster activity to operators;
2. tell readers when replicated state changed;
3. let a reader follow changes without polling;
4. give security and administrative activity an audit record.

These purposes have different consistency and durability requirements, so each
event carries a category that states which guarantee applies to it (section 4).

Qraft is not event-sourced. Current state is authoritative in the replicated
state machine and its snapshots. Events describe transitions in that state and
make them observable.

## 2. Design principles

- No event describes a replicated change before that change has committed and
  been applied to the state machine.
- Event publication never delays, fails, or re-enters a Raft transition.
- The Raft log is not a permanent event store, because snapshot compaction
  removes historical entries.
- Consumers can identify missed history and recover by reading authoritative
  state.
- Replicated events have stable identities derived from their committed log
  position.
- Local operational events remain distinguishable from replicated facts.
- Sensitive values are left out before an event enters the journal.
- Event history is bounded unless a durable sink owns its retention.
- Slow or failing consumers are isolated from the Raft state loop and from
  other consumers.

## 3. Package structure and ownership

Qraft is one Maven project. Events are owned by these packages:

| Package | Owns |
|---|---|
| `dev.mars.qraft.common.events` | The contracts and the journal: `QraftEvent`, `EventEnvelope`, `EventId`, `ReplicatedEventId`, `EventCursor`, `EventCategory`, `EventSeverity`, `EventFilter`, `EventPage`, `EventSource`, `EventSubscription`, `EventConsumer`, `SubscriptionEnd`, `DomainEvent`, `DomainEventSource`, the filter interfaces `KeyEvent`, `ServiceEvent` and `SessionEvent`, and `EventJournal`. It depends only on the JDK and SLF4J. |
| `dev.mars.qraft.raft` | `RaftEvents`, the system events, which `RaftNode` publishes; `RaftNode` also offers each applied entry's domain events to the journal (section 6). |
| `dev.mars.qraft.state` and its `kv`, `session` and `tenancy` packages | The domain events: `CatalogEvents`, `KvEvents`, `SessionEvents` and `TenancyEvents`. `QraftStateStore` produces them as it applies commands. |
| `dev.mars.qraft.server.http` | `AuditEvents`, published for each request answered under a token; `AuditFileSink`, the audit file; `EventRoutes`, the HTTP query and watch. |
| `dev.mars.qraft.server` | `QraftServerService` composes them: it creates one `EventJournal` for the server's boot, gives it to `RaftNode`, to the HTTP API server and to the audit file sink. |

Client mode keeps no journal and serves no events.

## 4. Event categories

`EventCategory` has four values. A query or watch can select one.

### 4.1 System events (`system`)

System events describe the operation of one server. They are node-local and
best-effort, and are ordered by the server's local sequence within one boot.
`RaftNode` publishes four:

- `raft.leadership_gained`, when the node becomes leader;
- `raft.leadership_lost`, when the leader leaves the leader's role;
- `raft.snapshot_published`, when a snapshot is published, taken locally or
  installed from the leader;
- `raft.fenced`, once, when the transition sequencer fences the node.

Routine heartbeats produce no events.

### 4.2 Domain events (`domain`)

Domain events describe successful changes to replicated state: nodes, service
instances and their health, keys and locks, sessions, tenants and namespaces
(section 7). They are deterministic consequences of committed commands, so
every replica that applies the same entry produces the same payloads. They are
not commands and are never appended to the Raft log.

### 4.3 Observation events (`observation`)

The category is reserved for facts measured outside replicated state, such as a
health probe or a client heartbeat. No code produces one (section 15). An
observation becomes a fact only when a command derived from it commits and
changes replicated state: a check result accepted by the servers appears as
`health.changed`, a domain event.

### 4.4 Audit events (`audit`)

`audit.http_request` records a request that presented a token (`X-Qraft-Token`
or `Authorization: Bearer`): the token's accessor ID, the method, the route
prefix the request was served under, the response status, and the tenant and
namespace the request named in `X-Qraft-Tenant` and `X-Qraft-Namespace`,
`default` where it named none. The request ID (`X-Request-Id`) is the
envelope's correlation ID. A secret that no token matches is recorded as
accessor `unknown`. Neither the secret nor a request or response body is ever
part of the event.

The journal's audit events are bounded and ephemeral. The audit file
(section 12) keeps them on disk, best effort.

## 5. Event envelope and identity

Every event travels in an envelope:

```java
public record EventEnvelope(
        EventId id,
        Instant observedAt,
        EventCategory category,
        EventSeverity severity,
        long term,
        OptionalLong committedIndex,
        Optional<ReplicatedEventId> replicatedId,
        Optional<String> tenant,
        Optional<String> namespace,
        Optional<String> correlationId,
        QraftEvent event) {
}
```

- `id` is `EventId(nodeId, bootId, sequence)`: the server, a random UUID for the
  process lifetime, and a sequence that starts at 1 in each boot. IDs are
  ordered within one boot. Comparing IDs of different boots is refused, never
  settled by time.
- `observedAt` is for people. Wall-clock time is never used to order or
  deduplicate events.
- `severity` is `INFO`, `WARNING` or `ERROR`.
- `term` is the Raft term at publication; for a domain event, the term of the
  entry that produced it.
- A domain event carries `committedIndex` and `replicatedId`; no other category
  may carry either, and the constructor refuses an envelope that breaks this.
- `ReplicatedEventId` is `(index, ordinal, type)`: the committed index of the
  entry, the event's position among that entry's events (from 0), and its type.
  Its string form is `index:ordinal:type`, such as `42:0:key.set`. Every server
  that applies the entry gives the event the same identity, so a consumer can
  recognise one replicated fact seen through two servers.
- `tenant` and `namespace` are the event's scope where it has one (section 7).
- `correlationId` is the request ID of an audit event.
- `event` is the payload. `QraftEvent` has one method, `type()`, a stable
  lower-case name with a dotted area prefix.

A cursor, `EventCursor(bootId, sequence)`, marks a position in one boot's
events. Its string form is `<boot>:<sequence>`; `<boot>:0` is the start of a
boot.

## 6. State-machine integration

`QraftStateStore` implements `DomainEventSource`. When a server keeps a
journal, `RaftNode` calls `recordDomainEvents(true)` as it is built. While
recording, each command the store applies adds its `DomainEvent`s (scope and
payload) to a pending list.

For each committed entry, `RaftNode.applyLog` does this, in order:

```text
the state machine applies the entry's command
    -> the state machine's last-applied index advances
    -> publishDomainEvents(term, index): drainDomainEvents(), and each event
       is published with the entry's term, its index and its ordinal
    -> the submitting client's result completes, on the leader
```

Events are never published during command preparation, WAL append,
replication, or commit calculation, where the command may still fail or
leadership may change.

If the state machine fails to apply an entry, the pending events are drained
and discarded, and the node fences itself; the only event that follows is
`raft.fenced`.

A failure to record an event is logged at WARN and never fails the entry.

## 7. Event types

Each payload is a Java record; its JSON form has one field per record
component. "None" in the scope column means the event belongs to no tenant or
namespace.

| Type | Category, severity | Payload | Scope | Produced when |
|---|---|---|---|---|
| `raft.leadership_gained` | system, info | `term` | None | The node becomes leader of `term`. |
| `raft.leadership_lost` | system, warning | `term`, `nowState` | None | The leader of `term` leaves the role; `nowState` is the Raft state it moved to, such as `FOLLOWER`. |
| `raft.snapshot_published` | system, info | `lastIndex`, `lastTerm`, `installed` | None | A snapshot is published and the log prefix compacted; `installed` is true for one installed from the leader. |
| `raft.fenced` | system, error | `reason` | None | The transition sequencer fences the node; `reason` is the first failure's type and message. |
| `node.registered` | domain, info | `name` | None | A node registers under a free name. |
| `node.updated` | domain, info | `name` | None | A registered node registers again. |
| `node.unreachable` | domain, info | `name` | None | The leader's expiry marks a node unreachable. |
| `node.removed` | domain, info | `name`, `reason` | None | A node leaves the catalog: `deregistered`, `reaped` after being unreachable, or `replaced`. |
| `service.registered` | domain, info | `serviceId`, `serviceName`, `node` | The instance's | A registration of an instance applies, a re-registration included. |
| `service.deregistered` | domain, info | `serviceId`, `serviceName`, `node` | The instance's | An instance leaves the catalog. |
| `health.changed` | domain, info | `serviceId`, `serviceName`, `from`, `to` | The instance's | An instance's aggregate health changes; `from` and `to` are health state names. |
| `key.set` | domain, info | `key`, `created` | The key's | A write applies; `created` is true when the key did not exist. |
| `key.deleted` | domain, info | `key` | The key's | A deletion removes an existing key. |
| `key.tree_deleted` | domain, info | `prefix` | The keys' | A recursive deletion removes at least one key under `prefix`; a namespace's deletion, on its own or with its tenant, removes at least one of its keys, with the empty prefix. |
| `lock.acquired` | domain, info | `key`, `session` | The key's | A session acquires a key. |
| `lock.released` | domain, info | `key`, `session` | The key's | A session releases a key it holds. |
| `session.created` | domain, info | `id`, `node` | None | A session is created, bound to `node`. |
| `session.destroyed` | domain, info | `id`, `reason` | None | A session ends: `destroyed` through the API, `ttl` when the leader's timer found it lapsed, or `node` when its node failed or left. |
| `tenant.created`, `tenant.updated`, `tenant.deleted` | domain, info | `name` | The tenant and its `default` namespace | A tenant is created, its description changed, or it is deleted. |
| `namespace.created`, `namespace.updated`, `namespace.deleted` | domain, info | `tenant`, `name` | The namespace | A namespace is created, its description changed, or it is deleted, on its own or with its tenant. |
| `audit.http_request` | audit, info | `accessorId`, `method`, `route`, `status`, `tenant`, `namespace` | The request's | A request that presented a token is answered (section 4.4). |

Rules that hold across the domain events:

- A refused change produces no event: a compare-and-set that does not match, an
  acquisition of a held key, a registration on a node another holds.
- A deletion or release that removes nothing produces no event.
- A key/value or lock event carries the key, never the value.
- Each key a session's end frees has its own event, in key order, before
  `session.destroyed`: `lock.released` when the session's behaviour is
  `release`, `key.deleted` when it is `delete`.
- A tenancy event carries no description. A deletion's event comes after the
  events of what the deletion removed.
- `session.destroyed` carries its reason in the replicated destroy command, so
  every replica records the same reason. An entry written by a server that does
  not carry the cause reads as `destroyed`.

`KeyEvent`, `ServiceEvent` and `SessionEvent` let a filter select one key's,
one service's or one session's events:

- `key.set`, `key.deleted`, `lock.acquired` and `lock.released` concern the key
  they name, and `key.tree_deleted` concerns every key under its prefix;
- `service.registered`, `service.deregistered` and `health.changed` name their
  service;
- `session.created`, `session.destroyed`, `lock.acquired` and `lock.released`
  name their session.

Creation and modification indexes belong to the key/value data model, not to
the events. A reader compares the state it reads with its watch cursor through
those indexes even when the intermediate events have expired (section 9).

## 8. Event source and bounded journal

`EventSource` is the read side:

```java
public interface EventSource {
    EventPage recent(EventCursor after, EventFilter filter, int limit);

    EventSubscription subscribe(
            EventCursor after,
            EventFilter filter,
            EventConsumer consumer);
}
```

`EventJournal` is the one implementation, one per server boot:

- It numbers events in strict order and keeps the newest
  `DEFAULT_CAPACITY` (4,096), dropping the oldest first. The bound is a count
  of events.
- `recent` returns up to `limit` matching events after the cursor, the cursor
  to ask from next, and an `EventPage.Status`:

  | Status | Meaning |
  |---|---|
  | `COMPLETE` | Every retained event after the cursor was examined. |
  | `MORE` | More retained events remain after `next`. |
  | `HISTORY_GAP` | The cursor is older than the retained history; `next` is the cursor before the oldest retained event. |
  | `OTHER_BOOT` | The cursor belongs to another boot. |
  | `INVALID` | The cursor names a sequence this boot has not reached, or the request is otherwise invalid. |

  The cursor to ask from next is the last event examined, matching or not, so
  a filtered reader never examines the same events twice.
- `EventFilter` selects by category, type, tenant, namespace, service, key and
  session; an empty set or an absent value takes every event. An event without
  a scope matches no tenant or namespace filter.
- A subscription replays what is retained after its cursor and then follows new
  events (section 11).

## 9. Watches and blocking queries

Key/value watches and blocking queries use an applied or modification index as
their correctness cursor. They do not rely on the transient event journal.

A reader:

1. reads authoritative state and receives its applied or modification index;
2. asks for changes after that index;
3. receives the change or waits for a later one;
4. on a history gap, or when the index goes back, reads authoritative state
   again;
5. resumes from the new index.

This recovery rule keeps watches correct across disconnects, journal
overflow, leadership changes, process restarts, and snapshot compaction. A
snapshot installed from the leader replaces state without producing domain
events, so a reader of events that sees `raft.snapshot_published` with
`installed` true reads state again.

An event is a signal that state may have changed. The state store and its
indexes remain the source of truth.

## 10. HTTP interface

Each server answers its own journal. The routes are on the HTTP API listener and
need `operator:read`.

### 10.1 Query

`GET /v1/operator/events` takes these parameters:

| Parameter | Meaning |
|---|---|
| `after` | The cursor, `<boot>:<sequence>`; the start of this boot by default. |
| `limit` | At most this many events, 1 to 1,000; 100 by default. |
| `category` | `system`, `domain`, `observation` or `audit`. |
| `type` | One event type, such as `key.set`; an empty value is refused. |
| `tenant`, `namespace` | Events in this tenant or namespace; trimmed, and a blank value is refused. |
| `service` | The events that name this service. Trimmed; a blank value is refused. |
| `key` | The events about this key, read as given, not trimmed; an empty value is refused. |
| `session` | The events this session took part in. Trimmed; a blank value is refused. |

Any other parameter is refused. When a parameter is repeated, the first value
is used.

The answer:

```json
{
  "events": [
    {
      "cursor": "6f1c2d9e-...:42",
      "node": "server-1",
      "observedAt": "2026-11-21T10:15:30.123Z",
      "category": "domain",
      "severity": "info",
      "term": 3,
      "committedIndex": 118,
      "replicatedId": "118:0:key.set",
      "tenant": "default",
      "namespace": "default",
      "type": "key.set",
      "event": { "key": "app/config", "created": true }
    }
  ],
  "next": "6f1c2d9e-...:42",
  "status": "complete",
  "capacity": 4096
}
```

`committedIndex`, `replicatedId`, `tenant`, `namespace` and `correlationId` are
present only when the envelope has them. `status` is the page status in lower
case: `complete`, `more`, `history_gap`, `other_boot` or `invalid`. An `invalid`
page is answered with 200 and no events.

### 10.2 Watch

`GET /v1/operator/events/watch` takes the same parameters but `limit`, which
only pages a query: a watch given `limit` is refused with 400 `invalid_query`.
It answers `200` with `Content-Type: text/event-stream` and
`Cache-Control: no-cache`:

- an opening comment, `: watching`, sent at once;
- each retained event after the cursor, then each new event, as
  `id: <cursor>`, `event: <type>` and `data: <the event's JSON, as in the query>`;
- a comment line, `:`, every 15 seconds as a heartbeat;
- when the subscription ends, a last `end` event whose data is
  `{"reason": ..., "resumeAfter": "<cursor>"}`.

| End reason | Meaning |
|---|---|
| `overflow` | The watch fell a whole subscriber queue behind. `resumeAfter` is the last event it delivered, so a new watch from there misses nothing. |
| `history_gap` | The cursor was older than the retained history. |
| `other_boot` | The cursor belongs to another boot. |
| `failed` | Delivering an event to this watch failed on the server. |
| `server_stopping` | The server is stopping, or the journal closed. Stopping waits up to 5 seconds for open watches to send their end. |

A client that leaves has its subscription ended at the next write, event or
heartbeat; it gets no `end` event.

### 10.3 Error responses

Errors use the server's error envelope:
`{"code", "error", "message", "retryable", "requestId"}`.

| Status | Code | When |
|---|---|---|
| 400 | `invalid_query` | An unknown parameter, a malformed cursor, a `limit` out of range or not a number, an unknown category, or a blank or empty filter value. A watch whose cursor names a sequence this boot has not reached. |
| 403 | `token_not_found` | The token is not known. |
| 403 | `permission_denied` | The token lacks `operator:read`. |
| 404 | `not_found` | A path below `/v1/operator/events` other than `/watch`. |
| 405 | `method_not_allowed` | A method other than `GET`. |
| 503 | `draining` | The server is draining (retryable). |
| 503 | `catalog_unavailable` | The server has no Raft node (retryable). |
| 503 | `events_unavailable` | The server keeps no journal (retryable). |

### 10.4 The administrative interface's feeds

The interface reads events through these routes (`src/main/ui/src/eventFeed.ts`).
A feed reads the journal from its start in pages of 1,000, then watches from the
last cursor. On `history_gap` or `other_boot` it reads the journal again from
the start; on `overflow` it reads from `resumeAfter` and watches again; on any
other end or a failed request it tries again from its cursor after 5 seconds.
It keeps at most the journal's capacity of events, and says when it read the
journal again or resumed a watch.

A feed and every live read are released when the page is hidden into the
browser's back/forward cache or the tab is hidden, and read again when the page
is shown (`src/main/ui/src/live.tsx`). Pausing live updates closes the watch;
resuming reads from the cursor.

## 11. Backpressure and failure isolation

Publishing gives the envelope its ID, keeps it, and offers it to each
subscriber's queue. It never waits for a subscriber or a network client.

Each subscription has its own bounded queue of `DEFAULT_SUBSCRIBER_QUEUE`
(1,024) events, drained by its own virtual thread. A subscriber whose queue is
full is ended with `OVERFLOW` once it has delivered what it had queued, with the
cursor after the last event it received. It never blocks the producer or
discards another subscriber's events.

`SubscriptionEnd` carries a reason and the cursor to resume after:

| Reason | Meaning |
|---|---|
| `CANCELLED` | The subscriber closed its subscription. |
| `OVERFLOW` | It fell a whole queue behind. |
| `FAILED` | Its consumer threw; only that subscription ends, and the failure is logged at WARN. |
| `CLOSED` | The journal closed, or the subscriber's thread was interrupted. |
| `HISTORY_GAP` | Its cursor was older than the retained history. |
| `OTHER_BOOT` | Its cursor belonged to another boot. |

A consumer is told once why its subscription ended. A consumer exception
never fails a committed command, fences the transition sequencer, rolls back
state-machine application, runs inside `RaftNode`, or calls back into it.

Delivery is best effort. At-least-once or exactly-once delivery needs a durable
outbox or journal, which Qraft does not have (section 15).

## 12. Retention and durability

The Raft WAL cannot provide general event replay because:

- snapshot compaction removes old entries;
- the log holds commands, not complete, presentation-safe events;
- system and audit events are not replicated commands;
- replaying the WAL for arbitrary readers would couple consensus storage to
  unbounded query workloads.

The journal is ephemeral: it holds one boot's newest 4,096 events and loses
them when the server stops. Each server keeps its own.

The audit file is the one durable sink. With `server.audit.path` set,
`AuditFileSink` subscribes to the journal's audit events from the moment the
server starts and appends each as one JSON line:

```json
{"type":"audit.http_request","timestamp":"...","node":"server-1","accessorId":"...","method":"PUT","route":"/v1/kv","status":200,"tenant":"default","namespace":"default","requestId":"..."}
```

- Before a line that would take the file past `server.audit.rotateBytes`
  (25,165,824 bytes, 24 MiB, by default), the file is moved aside as
  `<name>-<yyyyMMdd'T'HHmmss.SSS'Z'><extension>`, numbered when two moves share
  a millisecond, and the oldest moved files beyond `server.audit.maxFiles`
  (15 by default) are removed. A file that cannot be moved, as Windows refuses
  one a reader holds open, takes the line and is moved at a later one.
- A line that cannot be written is counted; the first such failure is logged at
  WARN, and requests go on.
- A sink that overflows resumes after the last line it wrote. One that finds
  the journal no longer holds what followed writes
  `{"type":"audit.events_lost"}` and resumes from the oldest event the journal
  holds.

The file is best effort: each server writes the requests it answered, kept
within the rotation's bounds. The administrative interface's Audit page reads
the current file through `GET /v1/internal/ui/audit`.

## 13. Security and redaction

Each event type carries only what its contract names (section 7). Sensitive
data never enters the journal:

- key/value values;
- token secrets, private keys and certificates;
- health-check output;
- command and request payloads;
- tenant and namespace descriptions.

Filtering is not authorization. The query and the watch need `operator:read`
before any event is read. Feature-specific reads, such as a key/value blocking
query, apply their own permissions and return values from authoritative state,
not from the journal.

## 14. Integration with the transition sequencer

The transition sequencer determines when Raft state becomes authoritative.
System events about sequenced state changes are built at the point the change
has been applied:

- `raft.leadership_gained` and `raft.leadership_lost` when the node's state
  change is notified, after the new role is installed;
- `raft.snapshot_published` after the snapshot is published, the log prefix
  compacted, and, for an installed snapshot, the state machine restored;
- `raft.fenced` when the sequencer enters the fenced state, once.

The sequencer never invokes subscribers. It offers an envelope to the journal
after applying the authoritative state, and each subscriber's thread delivers
it outside the state loop's call stack.

## 15. Not built

- **A byte bound on the journal.** The journal is bounded by count only.
- **Other system events:** node started, ready, draining or stopped; election
  started; term changed; peer replication stalled or recovered; snapshot
  creation or installation started, rejected or failed; storage opened,
  corrupted or recovered; transition queue saturated.
- **Observation events.** The `observation` category has no producer.
- **Other domain events:** ACL tokens, policies, roles, auth methods and
  binding rules, and Raft configuration changes produce no events.
- **Durable sinks beyond the audit file:** an export to an external system, or
  a durable journal or outbox with defined delivery, acknowledgement, ordering,
  retention and duplicate handling.
- **Query filters** for severity, node, term and minimum committed index; the
  administrative interface applies those to the events it has read.
- **A cluster-wide view.** Each server answers its own journal only.
- **Event names proposed and never built:** `KeyCreated`, `KeyUpdated` and
  `KeyDeleted` (the built types are `key.set`, with `created`, `key.deleted` and
  `key.tree_deleted`); `CompareAndSetRejected` (a refused write produces no
  event); `LockAcquired` and `LockReleased` as named (the built types are
  `lock.acquired` and `lock.released`); `LockInvalidated` (a session's
  destruction is `session.destroyed`, with its reason).
- **Value size or hash** in key/value events.

## 16. Related documents

- [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md)
- [`QRAFT_ADMIN_UI_UX_DESIGN.md`](../docs-design/QRAFT_ADMIN_UI_UX_DESIGN.md), section 9.4
- [`OPERATIONS.md`](OPERATIONS.md)
- The archived task lists that built the events:
  [`task-list-events-2026-11-08.md`](archive/task-list-events-2026-11-08.md),
  [`task-list-event-query-and-watch-2026-11-08.md`](archive/task-list-event-query-and-watch-2026-11-08.md),
  [`task-list-authentication-and-audit-2026-11-08.md`](archive/task-list-authentication-and-audit-2026-11-08.md)
