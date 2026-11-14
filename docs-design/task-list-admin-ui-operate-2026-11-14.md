# Task List: Administrative Interface, Operate (UI-3), and UI-2's Open Items

**Date:** 2026-11-14
**Last updated:** 2026-11-14 (written, at the owner's word to close the open items and complete UI-3 without pausing)
**Active work:** Phase 0
**Implementation plan:** [`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md), increment UI-3
**Source plan:** [`QRAFT_ADMIN_UI_UX_DESIGN.md`](QRAFT_ADMIN_UI_UX_DESIGN.md), sections 4.2, 5.2, 9.1 to 9.3; [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), sections 12.4.1 and 12.5
**Predecessor:** [`task-list-admin-ui-scoped-discovery-2026-11-14.md`](../docs/archive/task-list-admin-ui-scoped-discovery-2026-11-14.md) (UI-2)
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

The owner asked on 2026-11-14 to close the open items and complete UI-3 without
pausing until the task is complete and tested without errors, test first. Under
that instruction the decisions in section 4 were taken as the recommended
options; each is reversible, and the owner can change any of them. The suites
run locally from the check copy at `C:\qw`, with no commit or Jenkins run as a
gate. When this list's work is complete, add a completion summary and move this
file to `docs/archive/`.

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
7. **Reachability is the leader's last answer within an election timeout**, the
   test the autopilot read already makes; lag is the leader's last log index
   less the server's match index.

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

## 6. Phase 1: Membership and storage reads

1. Test first, over real HTTP against a three-server cluster: the members read
   on the leader gives each server's match and next index, lag, last contact,
   reachability and health, and the quorum; on a follower, the labelled partial
   view with the leader it knows; with ACLs, `operator:read` is needed.
2. Test first: the storage read gives the fields of decision 6, the snapshot's
   index and term after a snapshot, and a snapshot failure once one happens.
3. Design section 12.5 documents both.

**Exit gate.** As Phase 0.

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

## 8. Phase 3: Verification and close-out

1. The three suites run locally from the check copy (default, end-to-end,
   Docker), every log audited, with no unflagged error.
2. The plan's view map and increments, the design, and the handover brought up
   to date; this list completed and archived.

## 9. Out of scope

- Membership changes from the interface (UI-5).
- Events, configuration and live reads (UI-4).
