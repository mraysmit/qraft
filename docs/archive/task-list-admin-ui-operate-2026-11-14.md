# Task List: Administrative Interface, Operate (UI-3), and UI-2's Open Items

**Date:** 2026-11-14
**Status:** Complete and archived on 2026-11-14. Phases 0 to 3 are done, test first; the code was committed by the owner in `de1fa54` and `4a3f019`, and the three suites ran locally on `4a3f019` from the check copy: default 2,026 with every coverage gate met and Vitest 94, end-to-end 31, and Docker and `slow` 52, with no failures, errors or skips; every log audited, none unflagged (section 8).
**Last updated:** 2026-11-14 (Phase 3 done, the list complete and archived, its links rewritten from its new place. Earlier the same day: Phases 0 to 2 built, test first, with their records; decision 7 corrected to the test the code makes. Earlier the same day: written, at the owner's word to close the open items and complete UI-3 without pausing)
**Active work:** complete on 2026-11-14
**Implementation plan:** [`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](../../docs-design/QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md), increment UI-3
**Source plan:** [`QRAFT_ADMIN_UI_UX_DESIGN.md`](../../docs-design/QRAFT_ADMIN_UI_UX_DESIGN.md), sections 4.2, 5.2, 9.1 to 9.3; [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), sections 12.4.1 and 12.5
**Predecessor:** [`task-list-admin-ui-scoped-discovery-2026-11-14.md`](task-list-admin-ui-scoped-discovery-2026-11-14.md) (UI-2)
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

The owner asked on 2026-11-14 to close the open items and complete UI-3 without
pausing until the task is complete and tested without errors, test first. Under
that instruction the decisions in section 4 were taken as the recommended
options; each is reversible, and the owner can change any of them. The suites
run locally from the check copy at `C:\qw`, with no commit or Jenkins run as a
gate. The list was completed on 2026-11-14, with the completion summary below, and
moved to `docs/archive/`.

**Completion summary.** UI-2's open items are closed: the client's local API
answers an unrouted path 404 in the envelope after reading its body; `/`
redirects to the interface when it is enabled; text assets are sent gzip, kept
in memory; and the bundle is split so no script passes 500 kB. UI-3 is
delivered: `GET /v1/operator/raft/members`, the leader's view of each server's
progress and the quorum or a follower's labelled partial view, and
`GET /v1/operator/raft/storage`, with the last snapshot failure now recorded;
and in the interface the full status strip, server rows in Nodes and Clients,
Cluster Overview, Raft Members and Storage & Snapshots. Decisions 1 to 7 were
taken under the owner's instruction not to pause; each can be reversed.
Left for later: a WAL health figure and the storage lock state, a saturation
figure for the transition queue, and membership changes from the interface
(UI-5).

## 1. Goal

- Close the items UI-0 to UI-2 left open: the client's local API answering an
  unrouted path with a reset; the optional redirect from `/` to the interface;
  and the bundle's size, served uncompressed in one chunk above 1 MB.
- Deliver UI-3: the leader-side membership view and the storage and snapshot
  state on the server, and in the interface the full status strip, server rows
  in Nodes and Clients, Cluster Overview, Raft Members (read-only), and Storage
  & Snapshots with its persistent fenced banner.

## 2. Current state

- `GET /v1/operator/autopilot/health`, Consul's, answers on the leader alone
  each server's health, last contact, last index and term, and the failure
  tolerance; a follower answers 503. It has no next index, no lag figure and no
  reachability apart from health.
- `RaftNode` holds per peer `matchIndex`, `nextIndex` and the time of the last
  answer, `snapshotLastIndex` and `snapshotLastTerm`, and its transition
  sequencer's queue; `StorageStatus` gives the storage's size and the snapshot's
  age. `/raft/status` gives size, snapshot index and age, and fencing.
- A failed snapshot is only logged (`RaftNode.takeSnapshot`).
- The client's local API (`LocalApiServer`) is served by the JDK server, which
  answers a path no context serves itself and closes with the body unread.
- `AdminUiHandler` serves every asset uncompressed; Vite emits one bundle of
  1,014 kB (317 kB gzipped) and warns above 500 kB.

## 3. Rules

As UI-2's: the interface is a client of the public HTTP API only; red before
green for every behavioural change, with no Mockito; license headers and
attributed Javadoc on new Java files; the design documents and this list are
updated as each phase ends; every log of every run audited (`AGENTS.md`).
Frontend tests avoid whole-page role queries, which timed out on Jenkins in
build 72.

## 4. Decisions

Taken 2026-11-14 under the owner's instruction not to pause; each reversible.

1. **Unrouted paths on the client** are answered as on the server: a fallback
   route reads the body and answers 404 `not_found` in the envelope.
2. **`GET /` redirects to the interface** with 302 when it is enabled (design
   section 12.4.1 allows it); any other unrouted path stays 404, and `/` with
   the interface disabled stays 404.
3. **Compression on the fly.** The handler answers a request that accepts gzip
   with a gzip body for text assets (JavaScript, CSS, JSON, SVG, HTML other than
   the rendered index), `Vary: Accept-Encoding`, kept in memory once made; the
   uncompressed file stays the fallback. No build plugin and no new dependency.
4. **The bundle is split** into the application and its libraries (React,
   Ant Design and its parts, zod) with Vite's `manualChunks`, so no chunk passes
   Vite's 500 kB warning where a split can avoid it.
5. **Open question 3 of the plan: a follower answers membership with its own
   partial view, labelled.** `GET /v1/operator/raft/members` answers on every
   server: on the leader with each server's progress and the quorum; on a
   follower with the configuration it holds, `view: "follower"`, the leader it
   knows, and no progress, which only the leader has. The interface says so and
   names the leader.
6. **A storage read of its own**, `GET /v1/operator/raft/storage`: size,
   snapshot index, term and age, the first log index still held (the retained
   boundary), the last log, commit and applied indexes, fencing, the transition
   queue's depth, and the last snapshot failure since the server started.
   `/raft/status` is unchanged. Both new reads need `operator:read`, as the
   autopilot read does.
7. **Reachability is the leader's check-quorum test**: the server answered
   within an election timeout's worth of heartbeat rounds
   (`quorumContactRounds`), counted in rounds rather than wall time, so a test
   drives it with the cluster fixture's timers. Lag is the leader's last log
   index less the server's match index; a server is healthy when reachable and
   within 250 entries, the autopilot read's limit. (Written first as "an answer
   within an election timeout", the autopilot read's wall-clock test; corrected
   when the round count proved the one a test can drive.)

## 5. Phase 0: UI-2's open items

1. Test first: the client's local API answers an unrouted path, with a body,
   404 `not_found` in the envelope.
2. Test first: `GET /` redirects to the interface's path when it is enabled;
   not when disabled; no other path redirects.
3. Test first: a text asset requested with `Accept-Encoding: gzip` is answered
   gzip, decoding to the file, with `Vary`; without it, the plain file; the
   rendered index is never compressed with a nonce cached.
4. The bundle split; the build has no chunk warning, or the reason is recorded.

**Exit gate.** The new tests red first, then green; `mvn install` passes.

**Status, 2026-11-14: done**, test first, committed by the owner in `de1fa54`.

- `LocalApiServerTest.aPathNoRouteServesIsAnsweredNotFoundInTheEnvelopeAfterItsBodyIsRead`,
  red first on the JDK's `text/html`: `LocalApiServer` registers a fallback
  route for `/` that reads the body and answers 404 `not_found`.
- `HttpApiServerAdminUiTest`: the root redirects, 302, to the interface's path
  when it is enabled, a write to it is 405, and no other path redirects (red
  first, 404); with the interface disabled `/` is not found.
- `HttpApiServerAdminUiTest`: a text asset is sent gzip to a request that takes
  it, decoding to the file, the same bytes each time, with `Vary` on both
  answers (red first); refused with `q=0` or `identity`, for the rendered index,
  and for an image, it is plain. `AdminUiHandler` compresses once per path,
  size and CRC, so an edited development file is never served stale.
- `EmbeddedUiResourcesTest.noScriptPassesTheSizeAtWhichViteWarns`, red first on
  the 1,014 kB bundle: Vite's `manualChunks` splits the application (34 kB) from
  React (143 kB), Ant Design (357 kB), its parts (365 kB), zod (95 kB) and the
  rest (27 kB); the build gives no chunk warning. Served gzip, the largest is
  125 kB.
- `mvn clean install`: default 2,019 and Vitest 73, every gate; audit clean
  (`logs/ui3-p0-install-20261114-171205.log`). Design section 12.4.1 records the
  redirect, the compression and the split; section 12.1 the client's fallback.

## 6. Phase 1: Membership and storage reads

1. Test first, over real HTTP against a three-server cluster: the members read
   on the leader gives each server's match and next index, lag, last contact,
   reachability and health, and the quorum; on a follower, the labelled partial
   view with the leader it knows; with ACLs, `operator:read` is needed.
2. Test first: the storage read gives the fields of decision 6, the snapshot's
   index and term after a snapshot, and a snapshot failure once one happens.
3. Design section 12.5 documents both.

**Exit gate.** As Phase 0.

**Status, 2026-11-14: done**, test first, committed by the owner in `de1fa54`.

- `RaftNode.membership()` and `RaftNode.storageState()`, captured on the state
  loop, with the records `RaftMembership` and `RaftStorageState`;
  `RaftTransitionSequencer.depth()`; a snapshot that fails records its message
  and time. `HttpApiServer` serves `GET /v1/operator/raft/members` and
  `GET /v1/operator/raft/storage`, `operator:read`, no parameter.
- `HttpApiServerOperatorReadsTest` (6), red first on 404: the leader's view of a
  three-server cluster with progress and quorum; a server stopped is found
  unreachable once its rounds pass, with lag and the quorum's tolerance
  falling; a follower's labelled partial view; `operator:read` and parameter
  refusals; the storage read; and on a durable server a snapshot moving the
  boundary and a refused one recorded, under the specific
  `RAFT_SNAPSHOT_REFUSED_BY_TEST` declaration. The first run of the unreachable
  case fired the heartbeat timer 51 times without settling; the node drops a
  fire while a round is pending, so it advanced one round. The test drives one
  settled round at a time.
- Design section 12.5 documents both reads.

## 7. Phase 2: The Operate views

1. The client and schemas for both reads, test first.
2. The status strip: quorum and failure tolerance, and the largest lag, from
   the members read; on a follower, that only the leader knows them.
3. Nodes and Clients: server rows above the client rows.
4. Cluster Overview (`/ui/cluster`), Raft Members (`/ui/members`), and Storage
   & Snapshots (`/ui/storage`), in the navigation; the fenced banner on the
   storage page as well as the strip.
5. Vitest tests for each; the contract test extended to both reads; headless
   Chrome on every view under the policy.

**Exit gate.** Vitest and the Java suite green; Chrome with no console message.

**Status, 2026-11-14: done**, test first, committed by the owner in `de1fa54`
and `4a3f019`.

- The client's `members()` and `storage()` with zod schemas; routes `cluster`,
  `members` and `storage` (red first).
- `views/operate.test.tsx` (12), red first: the strip's quorum, failure
  tolerance and largest lag; an unreachable server in a persistent warning; a
  follower's "known to the leader"; the strip still showing the node when the
  membership is refused; server rows above the clients in Nodes and Clients;
  Cluster Overview; Raft Members on the leader and on a follower; Storage &
  Snapshots with its retained boundary, a fenced banner on the page and the
  last snapshot failure, and what a server without a storage directory or
  snapshot cannot say.
- `Root.test.tsx`: every style inserted on the three new pages carries the
  nonce. A scope test that rendered three views (2.2 s) is three tests.
- `AdminUiDataContractTest` checks both reads against a real server.
- Headless Chrome against a server and client from the jar: `/` (redirected),
  Services, Nodes, Health Checks, Cluster Overview, Raft Members, Storage &
  Snapshots and a service, each with no console message; the Ant Design chunk
  came gzip, 97 kB for 357 kB; the client answered an unrouted `PUT` 404.

## 8. Phase 3: Verification and close-out

1. The three suites run locally from the check copy (default, end-to-end,
   Docker), every log audited, with no unflagged error.
2. The plan's view map and increments, the design, and the handover brought up
   to date; this list completed and archived.

**Status, 2026-11-14: done.**

- The three suites on `4a3f019` from the check copy, no Node on the `PATH`:
  `mvn clean install`, default 2,026 with every coverage gate met and Vitest 94
  (`logs/ui3-default-20261114-173829.log`; audit of the reports and log, 551
  files: 562 error lines and 496 stack traces, all flagged); end-to-end, 31
  (`logs/ui3-e2e-20261114-174327.log`; audit with the subprocess logs, 598
  files: 3,459 error lines and 3,057 stack traces, all flagged); Docker and
  `slow`, 52, in 27 min (`logs/ui3-docker-20261114-174652.log`; audit
  of the reports, the log and the Docker archives, 281 files: 275 error
  lines and 133 stack traces, all flagged). No failure, error or skip; no
  JVM crash file.
- The implementation plan: UI-3 delivered, open question 3 settled, section 4's
  rows for membership, storage and the queue built, and the view map's Operate
  rows Done. Design section 20 names UI-2's and UI-3's archived lists.
- The list completed and moved to `docs/archive/`.

## 9. Out of scope

- Membership changes from the interface (UI-5).
- Events, configuration and live reads (UI-4).
