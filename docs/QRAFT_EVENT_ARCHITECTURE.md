# QRaft Event Architecture

## 1. Purpose

QRaft needs a common event architecture for both its current Raft controller
and planned platform features such as key/value storage, service discovery,
agents, health checks, sessions, tenancy, access control, and dynamic
configuration.

The event architecture has four purposes:

1. expose meaningful internal node and cluster activity to operators;
2. notify clients when replicated application state changes;
3. support blocking queries and live watches without polling;
4. provide a foundation for security and administrative audit records.

These purposes have different consistency and durability requirements. They
must not be combined into one stream with ambiguous guarantees.

This design does not convert QRaft into an event-sourced application. Current
state remains authoritative in the replicated state machine and its snapshots.
Events describe transitions in that state and make those transitions
observable.

## 2. Design principles

The implementation must obey the following rules:

- No event may describe a replicated change before that change has committed
  and been applied to the state machine.
- Event publication must never delay, fail, or re-enter a Raft transition.
- The Raft log is not a permanent event store because snapshot compaction may
  remove historical entries.
- Consumers must be able to identify missed history and recover by reading
  authoritative state.
- Replicated events must have stable identities derived from their committed
  log position.
- Local operational events must remain distinguishable from replicated facts.
- Sensitive values must be redacted before they enter general operational or
  audit streams.
- Event history must be bounded unless an explicitly configured durable sink
  owns its retention.
- Slow or failing consumers must be isolated from the Raft state loop and from
  other consumers.

## 3. Module structure and ownership

### `qraft-events`

A proposed new dependency-light module containing the shared event contracts.
It is not yet part of the Maven reactor; it is added when Phase 1 begins.

The module contains:

- `QraftEvent`;
- `EventEnvelope`;
- `EventCursor`;
- `EventCategory` and `EventSeverity`;
- `EventFilter`;
- `EventSource`;
- `EventSubscription`;
- `EventConsumer`;
- `EventSink`;
- explicit history-gap and overflow representations.

This module must not depend on the controller, transport, HTTP server, storage
implementation, or individual feature modules.

### `qraft-distributed-state`

Owns commands and domain events for replicated key/value and other generic
distributed-state operations. Planned event types include key creation, update,
deletion, compare-and-set outcomes, and lock ownership changes.

### `qraft-core`

Owns domain events for the service catalog, agents, nodes, health state, and
other core platform concepts.

### `qraft-tenant`

Owns tenant and namespace lifecycle events, policy-assignment events, and quota
state changes.

### `qraft-controller`

Owns concrete event production and local delivery:

- Raft system events;
- conversion of applied state-machine outcomes into event envelopes;
- the bounded in-memory event journal;
- subscriber isolation and backpressure;
- recent-event queries and streaming HTTP endpoints;
- event-related readiness and health information.

The controller must publish events only at sequenced application points.

### `qraft-runtime`

Owns optional adapters that export events to external systems. Export failure
must not alter consensus state or command results.

## 4. Event categories

### 4.1 System events

System events describe the operation of one QRaft node. Examples include:

- node started, recovered, ready, draining, or stopped;
- election started;
- role or term changed;
- leader elected or leadership lost;
- peer replication stalled or recovered;
- snapshot creation started, published, compacted, or failed;
- snapshot installation started, completed, rejected, or failed;
- storage opened, fenced, corrupted, or recovered;
- transition queue saturated or fenced.

System events are normally node-local and best-effort. A node-local sequence
orders them within one process lifetime.

Routine successful heartbeats must not produce individual events. The event
source should report meaningful state changes, such as a peer becoming
unreachable or returning to service, to avoid unbounded event volume.

### 4.2 Domain events

Domain events describe successful changes to replicated application state.
Examples include:

- service registered, updated, or deregistered;
- agent registered, changed, or removed;
- key created, updated, or deleted;
- session created, renewed, expired, or destroyed;
- lock acquired, released, or invalidated;
- tenant or namespace created, disabled, or removed;
- replicated configuration changed.

Domain events are deterministic consequences of committed commands. They are
not separate commands and must not be appended back into the Raft log.

### 4.3 Observation events

Observation events represent facts measured outside replicated state, such as:

- a health-check result;
- an agent heartbeat observation;
- a reconciliation attempt;
- a network or endpoint probe;
- a local resource warning.

An observation becomes a domain event only if a command derived from it is
committed and changes authoritative replicated state. This distinction prevents
uncommitted local observations from being mistaken for cluster facts.

### 4.4 Audit events

Audit events describe security-sensitive or administrative activity. Where
available, they include:

- authenticated principal;
- tenant and namespace;
- request and correlation identifiers;
- operation and target identity;
- authorization decision;
- result and failure classification;
- committed log index for successful replicated changes.

The initial event source may expose audit events, but it must not claim durable
or complete audit delivery until a durable audit sink and explicit retention
policy are implemented.

## 5. Event envelope and identity

A common envelope provides routing and ordering metadata without forcing every
event type to repeat it:

```java
public record EventEnvelope<E extends QraftEvent>(
        EventId id,
        String nodeId,
        UUID bootId,
        long localSequence,
        Instant observedAt,
        EventCategory category,
        EventSeverity severity,
        long term,
        RaftRole role,
        OptionalLong committedIndex,
        Optional<String> tenant,
        Optional<String> namespace,
        Optional<String> correlationId,
        E event) {
}
```

The exact Java types may change during implementation, but the semantics must
remain explicit.

Local events are identified and ordered by:

```text
node ID + boot ID + local sequence
```

The boot ID distinguishes two process lifetimes in which the local sequence
starts again.

Replicated domain events additionally have a stable identity derived from:

```text
cluster identity + committed log index + event ordinal/type
```

This permits consumers to deduplicate the same replicated fact when it is
observed through different nodes. Wall-clock time must never be used as an
ordering or deduplication key.

## 6. State-machine integration

The application of a replicated command should return both its client-visible
result and any domain events produced by the same mutation:

```java
public record QraftApplyOutcome<R>(
        R result,
        List<? extends QraftEvent> events) {
}
```

The required order is:

```text
command reaches the committed index
    -> state machine applies the command
    -> state mutation and domain events are produced together
    -> state-machine last-applied index advances
    -> events receive the term and committed index
    -> events are offered to the local event source
    -> the submitting client's command result completes
```

Events must not be emitted during command preparation, WAL append, replication,
or commit calculation. At those points the command may still fail or leadership
may change.

If state-machine application fails, no success domain event may be emitted. A
separate system failure event may be produced after failure becomes observable,
provided it cannot interfere with node fencing or error propagation.

Followers produce the same deterministic domain event payloads when applying
the same committed command. Envelope fields that are necessarily local, such as
node ID, boot ID, local sequence, and observation time, may differ.

## 7. Key/value event model

The key/value store should initially define these event families:

- `KeyCreated`;
- `KeyUpdated`;
- `KeyDeleted`;
- `CompareAndSetRejected` where rejected attempts are intentionally exposed;
- `LockAcquired`;
- `LockReleased`;
- `LockInvalidated` when session loss removes ownership.

A successful key mutation event should contain:

- tenant and namespace;
- key;
- operation;
- creation index;
- modification index;
- committed Raft term and log index through the envelope;
- flags;
- session identity where applicable;
- value size and optionally a value hash.

Raw values are excluded from the general operational and audit streams by
default. An explicitly authorized key watch API may return values from the
authoritative key/value state rather than leaking them through general system
events.

Creation and modification indexes belong to the key/value data model, not only
to the event model. They allow a client to compare returned state with its watch
cursor even when intermediate event history has expired.

## 8. Event source and bounded journal

The first concrete source should provide a bounded in-memory journal and live
subscriptions:

```java
public interface EventSource {
    EventPage recent(EventCursor after, EventFilter filter, int limit);

    EventSubscription subscribe(
            EventCursor after,
            EventFilter filter,
            EventConsumer consumer);
}
```

The journal must have fixed count and/or byte limits. Its query result must
distinguish these cases:

- all events after the cursor were returned;
- more retained events remain;
- the cursor predates retained history;
- the cursor belongs to a different node boot;
- the requested stream or cursor is invalid.

When retained history is overwritten, affected consumers must receive an
explicit history-gap or overflow result. Silent loss is not acceptable.

## 9. Watches and blocking queries

Key/value watches and blocking queries must use an applied or modification index
as their correctness cursor. They must not rely exclusively on the transient
event journal.

A client interaction is:

1. read authoritative state and receive its applied or modification index;
2. request changes after that index;
3. receive retained matching events or wait for a later matching application;
4. if the server reports a history gap, read authoritative state again;
5. resume from the new index.

This recovery rule makes watches correct across disconnects, event-buffer
overflow, leadership changes, process restarts, and snapshot compaction.

An event notification is therefore a signal that state may have changed. The
state store and its indexes remain the source of truth.

## 10. External interfaces

The first external capabilities should be:

```text
GET /v1/system/events?after=<cursor>&limit=<count>
GET /v1/system/events/stream?after=<cursor>
```

These use the `/v1/` prefix shared by the catalog and other target APIs. The
controller's older `/api/v1/` agent and info routes are not a pattern for new
endpoints.

The bounded query should be implemented before live streaming. Both interfaces
should support filters for category, severity, node, tenant, namespace, term,
and minimum committed index where those filters are applicable.

Server-sent events are sufficient for the initial one-way live stream. The
protocol must include event IDs, keepalive behavior, disconnect handling,
history-gap responses, and maximum per-subscriber buffering.

Feature-specific watch endpoints may use the same underlying source while
returning feature-appropriate representations and enforcing feature-specific
authorization. A general system event endpoint must not become a way to bypass
key/value, tenant, or audit permissions.

## 11. Backpressure and failure isolation

Publishing an event means offering an immutable envelope to a non-blocking
source. It must not wait for network clients or external sinks.

Each live subscriber has an independent bounded queue. When a subscriber cannot
keep up, the implementation must apply a documented policy such as terminating
that subscription with an overflow cursor. It must never block the producer or
discard another subscriber's events.

Consumer exceptions are contained and reported as operational failures. They
must not:

- fail a committed command;
- fence the Raft transition sequencer;
- roll back state-machine application;
- execute synchronously inside `RaftNode`;
- call synchronously back into `RaftNode`.

External sinks operate asynchronously and independently. Delivery is initially
best-effort. At-least-once or exactly-once claims require a separately designed
durable outbox or journal.

## 12. Retention and durability

The Raft WAL cannot provide general event replay because:

- snapshot compaction removes old entries;
- the log contains commands rather than complete presentation-safe events;
- operational and observation events are not replicated commands;
- replaying the WAL for arbitrary clients would couple consensus storage to
  unbounded query workloads.

The initial bounded journal is intentionally ephemeral. A future durable event
adapter may write to an independent append-only store or external system. Its
contract must define:

- delivery semantics;
- acknowledgement and retry behavior;
- retention and compaction;
- ordering scope;
- duplicate handling;
- recovery after exporter downtime;
- whether loss affects readiness.

Durable audit requirements should be addressed explicitly rather than inferred
from the presence of the local event journal.

## 13. Security and redaction

Every event type must define which fields are public, privileged, redacted, or
forbidden. Redaction occurs before an event enters a generally accessible
journal or exporter.

The following data must not appear in general events by default:

- raw key/value contents;
- credentials, tokens, private keys, or certificates;
- sensitive health-check output;
- unredacted command payloads;
- storage paths containing secrets;
- personal or tenant-confidential metadata not required by the event contract.

Filtering is not authorization. Authorization must be applied before querying
history or creating a subscription, and again when necessary for each event's
tenant and namespace scope.

## 14. Integration with the transition sequencer

The transition sequencer determines when Raft state becomes authoritative.
System events about sequenced state changes must therefore be constructed at
the transition's application boundary.

Examples include:

- election events after durable election metadata is applied;
- follower events after a higher term is durable;
- leader events after leader state and generation are installed;
- snapshot-created events after publication and prefix compaction satisfy the
  defined success boundary;
- snapshot-installed events after publication, compaction, in-memory boundary
  replacement, and state-machine restoration complete;
- storage-fenced events when the sequencer actually enters fenced state.

The sequencer must not invoke arbitrary subscribers. It may offer an envelope to
the local source after applying the authoritative state. The source then
dispatches outside the state-loop call stack.

## 15. TDD implementation plan

### Phase 1: Event contracts

Define the envelope, identities, cursors, filters, source, subscription, and
consumer contracts. Test validation, equality, stable replicated identity, and
cursor comparison without introducing transport or storage dependencies.

### Phase 2: Bounded local source

Implement the in-memory journal using real queues and purpose-built consumers.
Tests must prove:

- strict local sequence ordering;
- count and byte bounds;
- paginated replay;
- explicit history-gap detection;
- independent subscriber queues;
- slow-consumer overflow behavior;
- consumer-exception isolation;
- cancellation and shutdown cleanup;
- publication never waits for a subscriber.

### Phase 3: Raft system events

Add events at sequenced state-application points. Use controllable real storage
fakes to prove:

- no election or term event precedes metadata durability;
- no command event precedes WAL durability, commit, and application;
- stale transport completions cannot emit current-generation events;
- failed snapshot publication cannot emit snapshot success;
- a fenced transition emits one terminal fencing event without recursion.

### Phase 4: State-machine outcomes

Introduce `QraftApplyOutcome` and adapt the existing catalog and agent commands.
Tests must prove state mutation, result, and events agree for successful,
not-found, no-op, and compare-and-set outcomes.

### Phase 5: Key/value integration

Add key/value indexes and domain events. Test creation, update, deletion,
compare-and-set, sessions, locks, redaction, deterministic follower event
payloads, and stable event identities.

### Phase 6: Bounded event query

Expose recent events over HTTP. Test serialization, filtering, authorization,
pagination, gap responses, limits, draining behavior, and secret redaction with
the real HTTP stack.

### Phase 7: Live watch

Add live server-sent events and feature-specific blocking queries. Test replay
followed by live delivery, disconnects, slow clients, overflow, restart cursors,
leadership changes, snapshot compaction, and authoritative-state recovery.

### Phase 8: Durable adapters and audit

Only after the local contract is stable, add external sinks or a durable
journal. Define and test their delivery and retention guarantees independently
from Raft correctness.

## 16. Initial implementation boundary

The first tranche should stop after the shared contracts and bounded local event
source are fully tested. It should not yet instrument every Raft path or expose
network streaming.

That boundary establishes the semantics needed by the current serialization
work and by future feature modules without prematurely coupling events to HTTP,
an external broker, or permanent storage.

The next tranche should add only a small set of high-value sequenced system
events and one existing replicated feature's domain events. This provides an
end-to-end validation of ordering before the key/value and watch APIs depend on
the architecture.
