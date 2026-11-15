# Task List: Administrative Interface, Events, Configuration and Live Reads (UI-4)

**Date:** 2026-11-15
**Status:** Complete and archived on 2026-11-15. Phases 0 to 3 are done, test first, uncommitted; `mvn install` and the end-to-end tests UI-4 touches passed in the check copy with every log audited, none unflagged, and headless Chrome showed every view with no console message (section 8).
**Last updated:** 2026-11-15 (Phase 3 done, the list complete and archived, its links rewritten from its new place. Earlier the same day: Phases 0 to 2 built, test first, with their records; decisions 3, 5 and 6 corrected to
what the code does. Earlier the same day: written, at the owner's word to proceed with UI-4)
**Active work:** complete on 2026-11-15
**Implementation plan:** [`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](../../docs-design/QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md), increment UI-4
**Source plan:** [`QRAFT_ADMIN_UI_UX_DESIGN.md`](../../docs-design/QRAFT_ADMIN_UI_UX_DESIGN.md), sections 4.1, 5.1, 9.1, 9.4, 9.6, 12 and 13; [`QRAFT_EVENT_ARCHITECTURE.md`](../QRAFT_EVENT_ARCHITECTURE.md); [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 12
**Predecessor:** [`task-list-admin-ui-operate-2026-11-14.md`](task-list-admin-ui-operate-2026-11-14.md) (UI-3)
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

The owner asked on 2026-11-15 to proceed with UI-4. The decisions in section 4
were taken as the recommended options; each is reversible, and the owner can
change any of them. Each phase ends with `mvn install` in the check copy at
`C:\qw` and an audit of its logs; Jenkins runs the three suites once the owner
has committed and pushed the work. The list was completed on 2026-11-15, with the
completion summary below, and moved to `docs/archive/`.

**Completion summary.** UI-4 is delivered. On the server: every answer names the
server that gave it and every read the consistency it applied; the event query
and watch take a tenant, a namespace and a service, and the query gives the
journal's capacity; and `GET /v1/operator/configuration` answers the effective
configuration, each setting with its source, secrets withheld. In the interface:
the consistency selector, in the address; a read line on every view; live
updates, blocking queries for the catalog views and a read every 5 s for the
operator views, with one pause control and resynchronization; the Events page,
labelled ephemeral, followed with the watch; a service's Events tab and Cluster
Overview's recent events; and the Configuration page. Decisions 1 to 7 were taken
as the recommended options; each can be reversed. Left for later: exporting
events, which waits for UI-5's authorization, and a replicated configuration
other than the Raft server configuration, which the platform does not have.

## 1. Goal

Deliver UI-4: the Events page with its journal labelled ephemeral, the Events
tabs, the Configuration page, the consistency selector, and live updates with
pause and resume and history-gap resynchronization, with what the server must
add for them first.

## 2. Current state

- The event journal and its HTTP query and watch exist (events list, event
  query and watch list): `GET /v1/operator/events` pages one server's bounded
  journal (4,096 events) after a cursor, by category and type, with the page's
  status (`complete`, `more`, `history_gap`, `other_boot`); `/watch` follows it
  as server-sent events and ends with a reason (`overflow`, `history_gap`,
  `other_boot`, `failed`, `server_stopping`). Neither takes a tenant, a
  namespace or a service. Raft system events, catalog, key/value and session
  domain events, and audit events are published.
- Catalog, health, summary, check-listing, tenancy, key/value and session reads
  take `stale`, `consistent`, `index` and `wait`, and answer `X-Qraft-Index`,
  `X-Qraft-Known-Leader` and `X-Qraft-Last-Contact`. No answer names the server
  that gave it, or the consistency it applied.
- The server's configuration is read once from its file (`AppConfig`); only a
  few settings are logged at start. No read answers it.
- The interface reads each view once when it opens; nothing updates it, and it
  sends no consistency option.

## 3. Rules

As UI-3's: the interface is a client of the public HTTP API only; red before
green for every behavioural change, with no Mockito; license headers and
attributed Javadoc on new Java files; the design documents and this list are
updated as each phase ends; every log of every run audited (`AGENTS.md`).
Frontend tests use no role query (builds 72 and 75), and drive time with a
scheduler they control, never a wall-clock wait.

## 4. Decisions

Taken 2026-11-15 as the recommended options; each reversible.

1. **The answering server is named on every answer** of the server's HTTP API,
   errors included, as `X-Qraft-Answered-By`, the server's node ID. Every read
   that takes the read options also answers `X-Qraft-Consistency`: `default`,
   `consistent` or `stale`, the mode it applied.
2. **The event query and watch take `tenant`, `namespace` and `service`.** A
   service's events are those that name it: `service.registered`,
   `service.deregistered` and `health.changed`. The query's answer also gives
   the journal's `capacity`, so the interface can say how much it keeps.
3. **The configuration read is `GET /v1/operator/configuration`,** needing
   `operator:read`. It answers the server, the file the configuration came from
   and when it was read, and each setting by its key in the document
   (`server.raft.electionTimeoutMs`) with its value and its source,
   `configured` or `default`. Secrets are never answered: the operator token is
   answered with no value, and the telemetry endpoint without its user
   information or query, both marked `redacted`. File paths are given; what the
   files hold is not read for it. No setting changes while the server runs, so
   each answers `restartRequired: true`. The replicated configuration is the Raft server configuration, which
   Raft Members shows; the page says so. (Written first as "the operator token is
   given as `redacted`"; corrected to a null value marked `redacted`, so no text
   can be taken for the token.)
4. **The consistency selector** offers Default, Consistent and Stale in the top
   bar. It is in the address as `consistency=`, left out for Default, and kept
   across the interface's links as the scope is. It applies to the reads that
   take it; the operator reads always answer the answering server's own state,
   and their views say so.
5. **Live updates are on by default, with one Pause control for the whole
   interface.** Catalog, health, summary and check-listing reads are blocking
   queries (`index` and `wait=30s`); an index lower than the last one read
   starts the query again from 0 and the view says it was resynchronized
   (Consul's rule). A blocking query answered with the index it waited on, its
   wait having passed or the server not having waited, is sent again after 5 s,
   so a server that answers at once is not asked without pause; an index of 0 is
   read every 5 s. The operator reads, readiness and the Raft status are read
   again every 5 s, and events follow the watch. Paused, nothing is read, the
   last answers stay, and the interface says it is paused. A failed read is
   tried again after 5 s, the last answer kept and marked out of date.
6. **The Events page reads this server's whole journal** in the category chosen,
   in pages of 1,000, then follows the watch from the last cursor. Severity,
   tenant, namespace, term, correlation ID and least committed index narrow the
   loaded events in the browser, which holds the whole journal, so none is
   missed. The newest come first. A history gap or another boot is reported in
   an alert and the journal is read again from its start; an overflowed watch is
   resumed from the last event it delivered (its `resumeAfter`), which misses
   nothing while the journal holds them, and the alert says so. (Written first
   as reading the journal again on an overflow too; the watch's `resumeAfter`
   makes that unnecessary.) The
   watch is read with `fetch`, not `EventSource`, so the token header UI-5 adds
   can be sent. Export waits for UI-5, being subject to authorization.
7. **The Events tabs** are the service detail's Events tab, the service's
   events in the read scope, and Cluster Overview's recent events, the last 10
   system events.

## 5. Phase 0: The server's answers

1. Test first: every answer, a refusal and an unrouted path included, names
   the answering server; reads that take the read options name the consistency
   applied.
2. Test first: the event query and watch filter by tenant, namespace and
   service; an unknown value is no match; the query's answer has the capacity.
3. Test first: the configuration read answers every setting with its source,
   the operator token redacted and the endpoint's user information removed;
   `operator:read` is needed; a server not given its configuration answers 503.
4. The data contract test writes the new answers as fixtures.
5. Design section 12 documents the headers, the filters and the read.

**Exit gate.** The new tests red first, then green; `mvn install` passes and
its logs are clean.

**Status, 2026-11-15: done**, test first.

- `HttpApiServerConsistencyTest` (2 new), red first on the missing headers:
  every answer, a 405, a 404 and a 400 included, carries `X-Qraft-Answered-By`;
  catalog, key/value, summary, check-listing and tenancy reads carry
  `X-Qraft-Consistency` (`default`, `stale`, `consistent`); an operator read and
  a refused read carry none. `HttpApiServer.requestAware` sets the first,
  `readyToRead` the second.
- `EventContractsTest.aServiceFilterMatchesOnlyTheEventsThatNameThatService`,
  `HttpApiServerEventQueryTest` (1 new) and `HttpApiServerEventWatchTest` (1 new),
  red first on compilation: `EventFilter` takes a service, matched by the new
  `ServiceEvent` interface, which `service.registered`, `service.deregistered`
  and `health.changed` implement; the query and watch take `tenant`, `namespace`
  and `service`, refuse a blank one, and the query answers `capacity`
  (`EventJournal.capacity()`).
- `AppConfigEffectiveTest` (3) and `HttpApiServerConfigurationTest` (3), red first
  on compilation, and `QraftServerConfigurationReadTest`, red first on the 503:
  `AppConfig.effective()` gives every setting in the document's order with its
  value and whether the document gave it, the token withheld and the endpoint's
  user information and query removed; `GET /v1/operator/configuration` answers
  it with the server, the file and when the server was given it, and
  `QraftServerService` gives it. One expectation of the unit test was corrected:
  a file's path is answered in the platform's form.
- `AdminUiDataContractTest` (1 new): the event page, a domain and a system event,
  the configuration and the two headers, in the fields the interface reads.
- Platform design section 12.5 and event architecture section 17 document both.
- `mvn clean install`: default 2,041 and Vitest 95, every gate; the audit, 1,412
  files, found 4,848 error lines and 4,170 stack traces, all flagged
  (`logs/ui4-p0-install-20261115-102550.log`).

## 6. Phase 1: Read metadata, consistency and live updates

1. The client sends the consistency option, `index` and `wait`, and reads the
   answering server, the consistency and the index from each answer; the zod
   schemas for the configuration and event answers; the watch parser; all test
   first.
2. The consistency selector and its address; the read line on every view
   naming the consistency, the answering server and the index, with stale and
   resynchronized states.
3. Live updates with pause and resume, under a scheduler the tests drive.

**Exit gate.** Vitest and the Java suite green.

**Status, 2026-11-15: done**, test first.

- `api/reads.test.ts` (11), red first: the client sends `consistent` or `stale`
  on the reads that take them, and `index` and `wait` with the signal that ends
  a blocking query; reads `X-Qraft-Answered-By`, `X-Qraft-Consistency`,
  `X-Qraft-Known-Leader` and `X-Qraft-Last-Contact`; reports an aborted read as
  an abort; reads the configuration and event pages with zod schemas; and reads
  the watch with `fetch`, whatever pieces its stream arrives in, telling each
  event and its end, a stream closed without its end or a refusal as an error,
  and nothing once closed.
- `routing.test.ts` (3 new), red first: `consistency=` in the address after the
  scope, the default left out; the `events` and `configuration` routes.
- `live.test.tsx` (6), red first: `useLiveRead` sends a blocking query again at
  once with the last index and a 30 s wait; starts again and says so when the
  index goes back; waits 5 s after an unchanged answer; reads a read without an
  index every 5 s; reads nothing while paused, the follow-up in flight aborted
  and the last answer kept, and reads at once on resuming; keeps the last answer
  of a failed read and tries again after 5 s. Time is a scheduler the test fires.
- `views/consistency.test.tsx` (5), red first: the consistency selector, the
  address and the links; the read line, `Answered by … · <consistency> ·
  Applied index N`, a stale read with its leader contact, an operator view as
  the answering server's own state; the top bar's pause control.
- Every view reads through `useLiveRead`: Services, a service, Health Checks
  and the clients of Nodes and Clients are blocking queries; the status strip,
  the server rows, Cluster Overview's operator reads, Raft Members and Storage
  & Snapshots are read every 5 s. The test API's catalog reads wait, given an
  index, as a server with no change does.

## 7. Phase 2: Events, the Events tabs and Configuration

1. The Events page (`/ui/events`) with its filters, ephemeral label, cursor,
   live following, and history-gap, overflow and other-boot resynchronization.
2. The service detail's Events tab and Cluster Overview's recent events.
3. The Configuration page (`/ui/configuration`).
4. Vitest tests for each; the contract test extended; headless Chrome on every
   view under the policy with no console message.

**Exit gate.** As Phase 1, and Chrome with no console message.

**Status, 2026-11-15: done**, test first, Chrome below in Phase 3's record.

- `views/events.test.tsx` (9), red first: the Events page reads the journal in
  pages and lists it newest first under the ephemeral label with the server and
  the capacity, then follows the watch from the last cursor; narrows by
  severity, scope, term, correlation ID and least committed index; reads the
  journal again from its start on a history gap, saying events were missed;
  resumes an overflowed watch from its `resumeAfter`; closes the watch while
  paused and on resuming reads from its cursor first; tries a failed watch again
  after 5 s; reads and follows the category chosen. A service's Events tab reads
  its events in the read scope; Cluster Overview shows the last ten system
  events. `useEventFeed` (`eventFeed.ts`) does the reading and following.
- `views/configuration.test.tsx` (3), red first: each setting with its value,
  source and restart, a redacted one as Redacted, a setting with no value as
  Not set, the file and the time, the static-configuration note linking Raft
  Members; the key and source filters; a refusal.
- Two expectations of the tests were corrected: an empty page answers the cursor
  it was asked from, and the Raft Members link is looked for in its note.
- **A slow test found and its cause fixed.** The whole suite passed, but the
  Root test that walks a service's tabs took 3.2 s with every file running
  (Jenkins build 76: 0.9 s), and Nodes and Clients 2.4 s. Timing each part
  against `origin/main` in the check copy found the cause: Ant Design's button
  in the pause control made every table mounted after it three times slower to
  render in jsdom. The pause control is a plain button; the slowest test is now
  1.7 s with every file running, as before UI-4. Vitest 132, all passing.

## 8. Phase 3: Close

1. The packaged-artifact test reads the configuration from a packaged server
   with a real configuration file and finds its secret redacted. It cannot fail
   first: it refuses a jar older than the sources, and the behaviour it accepts
   was built test first in Phase 0 (`QraftServerConfigurationReadTest`).
2. The implementation plan, the UI/UX design and the handover brought up to
   date; this list's completion summary; the list archived.

**Exit gate.** `mvn install` and the packaged test pass with clean logs; the
owner's commit, then Jenkins.

**Status, 2026-11-15: done.**

- `PackagedAdminUiEndToEndTest.thePackagedServerAnswersTheConfigurationItWasStartedFromWithItsSecretWithheld`:
  the shaded jar, started from a directory holding its configuration file with
  an operator token, answers `GET /v1/operator/configuration` naming the file,
  the token's setting listed with no value and marked `redacted`, the secret in
  no part of the answer, and `X-Qraft-Answered-By`; its output has no error.
- Headless Chrome against the packaged server, over the DevTools protocol: a
  virtual-time budget never ends while a blocking query or the watch is open,
  so each view is read after a wall-clock wait. Services, a service, Nodes and
  Clients, Health Checks, Cluster Overview, Raft Members, Storage & Snapshots,
  Events, Configuration, and Services read stale and consistent each showed
  their data, every style element carried the nonce, Chrome logged no console
  message, and the operator token appeared nowhere. A service registered while
  Services, and another while Events, was open appeared within 3 s without a
  reload: the blocking query and the watch in a real browser. The server's
  output and logs held no error.
- `mvn clean install` on the final tree: default 2,041 and Vitest 132, every
  coverage gate met. Then `PackagedAdminUiEndToEndTest` (3),
  `EventWatchEndToEndTest` and `ConsistencyEndToEndTest`, 5, all passing. The
  audit of the install, the rebuild of the jar, the end-to-end run, its reports
  and the application logs, 939 files, found 5,126 error lines and 4,413 stack
  traces, all flagged (`logs/ui4-final-install-20261115-110247.log`,
  `logs/ui4-final-e2e-20261115-111012.log`).
- **Two refused runs, their cause fixed.** The first two runs of those
  end-to-end tests stopped at once: `PackagedAdminUiEndToEndTest` refused the jar
  as older than its sources (`logs/ui4-final-e2e-20261115-110656.log` and
  `-nosync-20261115-110828.log`). The check copy's sync script had copied the
  unchanged files again after the install, renewing their times. It now copies
  only a file whose content differs; the jar was rebuilt from the same sources
  with `mvn package -DskipTests` and the tests passed on it.
- The implementation plan (status, sections 4 and 5, UI-4 delivered), platform
  design section 20 and the handover record UI-4.
