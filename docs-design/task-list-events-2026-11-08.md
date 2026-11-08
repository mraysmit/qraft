# Task List: Events

**Date:** 2026-11-08
**Status:** Active. Started on 2026-11-08 as item 8 of the feature validation's order, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (started)
**Follows:** [`task-list-observability-2026-11-08.md`](../docs/archive/task-list-observability-2026-11-08.md)
**Design:** [`QRAFT_EVENT_ARCHITECTURE.md`](../docs/QRAFT_EVENT_ARCHITECTURE.md), phases 1 to 4 of section 15
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](QRAFT_FEATURE_VALIDATION_2026-10-21.md), the event journal row and item 8
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

## 1. Goal

The event architecture's first four phases: the event contracts, a bounded local journal with subscriptions, a small
set of Raft system events at their sequenced points, and domain events that the state machine produces with its
results for the catalog and node commands.

## 2. Where Qraft stands (2026-11-08)

- There is no event code. The architecture document fixes the envelope, identities, journal, backpressure and the
  order in which an applied command's events are offered.
- `RaftNode` applies committed entries in order on its state loop, and `RaftTransitionSequencer` orders its
  transitions; `QraftStateStore.apply` returns a `RaftCommandResult`.

## 3. Reference

The architecture document, sections 4 to 8, 11 and 15. Consul's closest equivalent is its event stream
(`agent/consul/stream`), a bounded in-memory topic buffer with snapshot-then-follow subscriptions and an explicit
"reload" when a subscriber falls behind.

## 4. Decisions

Taken on 2026-11-08 as the recommended options, under the owner's instruction; each can be reversed.

1. **One package, `dev.mars.qraft.events`,** for the contracts and the journal, depending on nothing else in Qraft.
2. **The journal is bounded by count,** 4,096 events by default, with its cursor the node's boot ID and local
   sequence; a byte bound is left for later, as no event carries an unbounded payload yet.
3. **A subscriber has its own bounded queue,** 1,024 events by default, drained by its own virtual thread; one that
   falls behind is ended with an overflow carrying the cursor to resume from, as Consul's stream ends a slow subscriber
   with a reload. Publication never blocks.
4. **System events in this list:** leadership gained and lost with the term, a snapshot published, and fencing.
5. **Domain events in this list:** a node registered, changed and removed; a service registered and deregistered; and
   a service's health changed. Each carries its committed index, and its replicated identity is the index and its
   ordinal in that entry.
6. **No network interface:** the HTTP query and the live watch are the design's phases 6 and 7, left for later.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. Contracts

- [ ] The envelope, the event ID and replicated identity, the cursor and its comparison, the filter, and the source,
  subscription and consumer interfaces.

**Exit:** the new tests seen red first; `mvn install`.

### Phase 2. The bounded local journal

- [ ] Ordering, the count bound, paginated replay, history-gap and other-boot answers, independent subscriber queues,
  overflow, consumer-exception isolation, cancellation, and publication that never waits.

**Exit:** the new tests seen red first; `mvn install`.

### Phase 3. Raft system events

- [ ] Leadership gained and lost, a snapshot published, and fencing, offered at their sequenced points and never for a
  stale completion or a failed publication.

**Exit:** the new tests seen red first; `mvn install`.

### Phase 4. State-machine outcomes

- [ ] The state machine's outcome carries the domain events of the change it made; the node gives them their term
  and committed index, and offers them after the entry is applied and before the submitter's result completes.
- [ ] Node, service and health events, the same on every replica, and none for a refused, missing or no-op command.

**Exit:** the new tests seen red first; the three suites locally, audited, and the list complete and archived.

## 7. Out of scope

- The design's phases 5 to 8: key/value events, the HTTP query, the live watch, durable sinks and audit.
