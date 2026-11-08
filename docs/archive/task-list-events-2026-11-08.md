# Task List: Events

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phases 1 to 4 are done, test first, and the final local run of the three suites passed on them: default 1,867 with every coverage gate met, end-to-end 22 and Docker 51, with no failures, errors or skips; audit over the 275 files written since the previous attempt: 973 error lines, all flagged, 645 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 as item 8 of the feature validation's order, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (the final run recorded, and the list complete and archived. Earlier the same day: Phases 1 to 4 done and recorded; started the same day)
**Follows:** [`task-list-observability-2026-11-08.md`](task-list-observability-2026-11-08.md)
**Design:** [`QRAFT_EVENT_ARCHITECTURE.md`](../QRAFT_EVENT_ARCHITECTURE.md), phases 1 to 4 of section 15
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the event journal row and item 8
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

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

1. **One package, `dev.mars.qraft.common.events`,** for the contracts and the journal, depending on nothing else in Qraft.
   It began as `dev.mars.qraft.events`; `PackageDependencyTest` admits only the final layers, and Common, which every
   layer may use and which uses no other, is the one whose rule an events package keeps, so it moved there.
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

- [x] The envelope, the event ID and replicated identity, the cursor and its comparison, the filter, and the source,
  subscription and consumer interfaces.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 1 record, 2026-11-08.** `dev.mars.qraft.common.events` holds `QraftEvent`, `EventCategory`, `EventSeverity`, `EventId`
(node, boot, sequence from 1, ordered within a boot and refused across boots), `ReplicatedEventId` (index, ordinal,
type), `EventEnvelope` (a domain event carries its committed index and its identity at that index; any other carries
neither), `EventCursor`, `EventFilter`, `EventPage`, `SubscriptionEnd`, `EventConsumer`, `EventSubscription` and
`EventSource`. `EventContractsTest` (5) was seen red 5 of 5 against shapes without their rules, then green.

### Phase 2. The bounded local journal

- [x] Ordering, the count bound, paginated replay, history-gap and other-boot answers, independent subscriber queues,
  overflow, consumer-exception isolation, cancellation, and publication that never waits.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 2 record, 2026-11-08.** `EventJournal` keeps the newest events of one boot, 4,096 by default. `recent` pages
through them and answers a cursor older than what is kept with the cursor to resume from. Each subscriber has its own
queue, 1,024 by default, and its own virtual thread. One that falls a queue behind takes what it had queued and is
ended by overflow, with the cursor after the last event it received. One whose consumer throws is ended alone, with a
warning that carries no stack trace. Cancelling and closing end subscriptions, and publication only offers to the
queues. `EventJournalTest` (5) was seen red 5 of 5. Its first green run failed the overflow case: a burst of 49 events
into queues of 4 overflowed the fast subscriber too. The test now publishes bursts no larger than a queue, and passed
five runs in five.

### Phase 3. Raft system events

- [x] Leadership gained and lost, a snapshot published, and fencing, offered at their sequenced points and never for a
  stale completion or a failed publication.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 3 record, 2026-11-08.** `RaftEvents` holds `LeadershipGained`, `LeadershipLost`, `SnapshotPublished` and
`Fenced`. `RaftNode.Builder.events(EventJournal)` gives a node its journal. Leadership is recorded where the node
already tells its state listeners of a change, on the state loop: gained on becoming leader, lost on leaving the role,
with the term it led. A snapshot is recorded once it has been applied, taken or installed, and fencing once, through a
listener the transition sequencer calls when it fences. An event that cannot be recorded is reported and never fails
the node. `RaftNodeSystemEventsTest` (3) was seen red 3 of 3, then green with the sequencer, apply-failure, snapshot
and read-barrier tests, 29.

### Phase 4. State-machine outcomes

- [x] The state machine's outcome carries the domain events of the change it made; the node gives them their term
  and committed index, and offers them after the entry is applied and before the submitter's result completes.
- [x] Node, service and health events, the same on every replica, and none for a refused, missing or no-op command.

**Phase 4 record, 2026-11-08.** `QraftStateStore` is a `DomainEventSource`, recording only once a node with a journal
asks it to. Its events, `CatalogEvents`:

- a node registered, updated, made unreachable, or removed (deregistered, reaped or replaced);
- a service registered or deregistered, in its tenant and namespace, wherever an instance leaves;
- a service's health changed.

After applying each entry, the node drains them and offers them with the entry's term, its index and their ordinal,
before completing the submitter's result. An entry that fails to apply produces none. `QraftServerService` gives its
node a journal for the boot. `QraftStateStoreDomainEventsTest` (2) and `RaftNodeDomainEventsTest` were seen red 3 of
3, then green with the store's other tests, 55. The architecture document, the feature validation's journal row and
the Consul plan are in line.

**Exit:** the new tests seen red first; the three suites locally, audited, and the list complete and archived.

**Final run, 2026-11-08.** The first run failed its default suite on `PackageDependencyTest`, which admits only the
final layers' packages: the events package moved under Common (decision 1), and that run was stopped before its later
suites. The rerun (logs `logs/ev-final2/`): default 1,867 with every coverage gate met, end-to-end 22 and Docker 51, with no failures, errors or skips; audit over the 275 files written since the previous attempt: 973 error lines, all flagged, 645 stack traces, none unflagged; no `too_many_pings`; no JVM crash file.

## 7. Out of scope

- The design's phases 5 to 8: key/value events, the HTTP query, the live watch, durable sinks and audit.
