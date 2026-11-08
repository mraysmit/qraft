# Task List: Event Query and Watch

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phases 1 to 4 are done, test first, and the final local run of the three suites passed on them: default 1,890 with every coverage gate met, end-to-end 24 and Docker 51, with no failures, errors or skips; audit over the 272 files the run wrote: 818 error lines, all flagged, 590 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 for the event journal row of the feature validation, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (the final run recorded, and the list complete and archived. Earlier the same day: phases 1 to 4 built)
**Follows:** [`task-list-catalog-indexes-2026-11-08.md`](task-list-catalog-indexes-2026-11-08.md)
**Design:** [`QRAFT_EVENT_ARCHITECTURE.md`](../QRAFT_EVENT_ARCHITECTURE.md), phases 5 to 7 of section 15
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the event journal row
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

The event architecture's phases 5 to 7: domain events for key/value, sessions and locks; the bounded event query over
HTTP; and a live watch that replays and then follows.

## 2. Where Qraft stands (2026-11-08)

- Every server keeps a bounded journal of its boot's system, domain and audit events (the events list), with paged
  replay and subscriptions, and no network interface.
- Key/value, session and lock changes produce no event.

## 3. Reference

The architecture document, sections 7 to 10 and 13. Consul's event stream (`/v1/agent/...` streaming in its gRPC
subscribe API) sends a snapshot and then follows, and tells a subscriber to reload when it falls behind.

## 4. Decisions

Taken on 2026-11-08 as the recommended options, under the owner's instruction; each can be reversed.

1. **Key/value events carry the key, never the value** (architecture section 13): a key set, a key deleted, a tree
   deleted, a lock acquired and released; and a session created and destroyed, with the reason a session ended.
2. **The query is `GET /v1/operator/events`,** with `after` (`<boot>:<sequence>`), `limit` (100 by default, at most
   1,000), `category` and `type`; it answers the page's events, the cursor to ask from next and the page's status, and
   needs `operator:read`.
3. **The watch is `GET /v1/operator/events/watch`,** server-sent events over HTTP with the same filter: each event an
   SSE `event` whose `id` is its cursor, a comment as a heartbeat, and a final `end` event with the reason and the
   cursor to resume from, after which the server closes the stream.
4. **One server's events:** the query and the watch answer the server asked; a client that wants every server's asks
   each, as the identities let it tell the same replicated fact apart.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. Key/value, session and lock events

- [x] The events of decision 1, the same on every replica, and none for a write that changed nothing.

**Exit:** the new tests seen red first; `mvn install`.

**Record (2026-11-08):** `KvEvents` (`key.set` with whether the key was created, `key.deleted`, `key.tree_deleted`,
`lock.acquired`, `lock.released`) and `SessionEvents` (`session.created`, `session.destroyed` with its reason). The
store decides before applying whether the key, the tree or the held lock existed, and records an event only for a
write that changed something. `QraftStateStoreKvEventsTest` was seen red against the records alone, then green. The
`mvn install` exit was taken with Phase 4's run of the three suites.

### Phase 2. The event query

- [x] `GET /v1/operator/events` with its cursor, limit and filter, the gap and other-boot answers, and `operator:read`.

**Exit:** the new tests seen red first; `mvn install`.

**Record (2026-11-08):** `EventRoutes` answers the page, `next` and the status, and refuses an unknown parameter, a
cursor that is not one, a limit outside 1 to 1,000 and an unknown category with 400. `HttpApiServerEventQueryTest` was
seen red with 404, then green.

### Phase 3. The live watch

- [ ] `GET /v1/operator/events/watch`: replay then follow, heartbeats, and the end on overflow, a gap, the server
  stopping or the client leaving.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Record (2026-11-08):** the watch subscribes to the journal before it answers, so a cursor past the last event is
still refused with 400; it then sends an opening comment, the events with their cursors as `id`, a heartbeat comment
after each interval without one, and an `end` event with the reason and `resumeAfter`. The request's thread writes the
heartbeats and the end, the subscription's thread the events, one at a time. `HttpApiServer.stop()` and `close()` end
every watch as `server_stopping`, waiting at most five seconds, before the listener stops. A client that leaves is
noticed at the next write and its subscription cancelled. `HttpApiServerEventWatchTest` was seen red with 404, then
green, and passed five runs in a row. The end-to-end test then showed a quiet watch answered only at its first
heartbeat, because the HTTP server sends the headers with the first body it flushes; a test of a quiet watch was seen
red with a timeout, and the opening comment made it green. Overflow is covered by `EventJournalTest`, as a network
client cannot be made slow deterministically. The three suites ran with Phase 4.

### Phase 4. Verification and documentation

- [x] An end-to-end test that watches one server while key/value writes are made through another, and the architecture
  document, the feature validation and the operations guide.

**Record (2026-11-08):** `EventWatchEndToEndTest` starts three servers, watches a follower for `key.set` and writes
three keys through another server; the watch sees each, in order, as the watched server's own event, with the same
`replicatedId` as the writing server's. The architecture document's section 17 records phases 5 to 7 and how the
key/value events differ from section 7's first model; the feature validation's event journal row is done; the
operations guide has an Events section.

**Exit:** the three suites locally, audited, and the list complete and archived.

**Final run, 2026-11-08.** The three suites from `C:\qx7` (logs `logs/evq-final/`), which also took Phase 1's and
Phase 2's `mvn install` exits and Phase 3's suites: default 1,890 with every coverage gate met, end-to-end 24 and Docker 51, with no failures, errors or skips; audit over the 272 files the run wrote: 818 error lines, all flagged, 590 stack traces, none unflagged; no `too_many_pings`; no JVM crash file. The run's script ended with exit code 1, which is
`robocopy`'s code for files copied when it saves the run's logs, after all three suites had passed.

## 7. Out of scope

- Durable sinks other than the audit file, and feature-specific blocking queries over events.
