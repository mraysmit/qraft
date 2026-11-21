# Task List: The Handover's Open Items

**Date:** 2026-11-20
**Status:** Complete and archived on 2026-11-20. Written from the handover's section 6 at the owner's word, "finish
open items". Every item is closed: four with work done test first (Phases 1 to 4), the rest by a recorded decision,
each reversible. The three suites passed locally with clean audits (section 11); the work is uncommitted.
**Last updated:** 2026-11-20 (written; Phases 1 to 6 done; decisions 2, 5 and 6 amended; archived)
**Active work:** complete on 2026-11-20
**Source:** `docs-design/HANDOVER_2026-11-20.md`, section 6, as committed in `cc3b171`; the handover was replaced on 2026-11-21
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

Close every open item of the handover's section 6. An item that needs work gets it, test first; an item already
decided elsewhere, or that records a rule rather than a task, is closed here with the reason, and moved out of the
open items. The release question ("What complete means") is not one of them: the handover's section 4 offers it as
the other choice beside these items, and the owner chose these.

## 2. Rules

- Strict test-first: a failing test, seen to fail for the right reason, before the code.
- `mvn install` at the end of each phase; the end-to-end and Docker stages for a phase that changes the runtime, run
  from a copy of the tree outside the workspace (handover, section 5), every log audited.
- Decisions are taken as the recommended option under the owner's standing instruction not to stop for go-ahead
  (handover, section 2), and each can be reversed.

## 3. The items and their decisions

| # | Item (handover, section 6) | Decision |
|---|---|---|
| 1 | The interface's tabs share six connections | Work: Phase 1 |
| 2 | Retried requests leave no trace | Work: Phase 2 |
| 3 | A renewal's margin in the acceptance client | Work: Phase 3 |
| 4 | The interface's own reads fill the audit file | Work: Phase 4 |
| 5 | The Docker stage's length | Closed: kept as it is (decision 5) |
| 6 | An unexplained leader step-down | Closed after investigation (decision 6) |
| 7 | The start scripts' name-lookup warnings | Closed: kept as they are (decision 7) |
| 8 | The Jenkins host | Closed: a rule for Java upgrades (decision 8) |
| 9 | Services registered from the administrative interface | Closed: decided on 2026-11-15 (decision 9) |
| 10 | Upgrading a cluster to ACLs | Closed: a rule for upgrades, kept in the runbook |
| 11 | The copies of the tree for local runs | Closed: a note for runners, moved to section 5 |
| 12 | Windows reserves ports | Closed: a note for runners, moved to section 5 |
| 13 | Builds need the network for the administrative interface | Closed: kept (decision 13) |
| 14 | Other builds on the `markr` workstation | Closed: a note for runners, moved to section 5 |
| 15 | Audits count error text in any case | Closed: a rule for audits, moved to section 5 |
| 16 | Mutation testing (PIT) is shelved | Closed: a standing rule, in section 2 |
| 17 | The `Partial` and `Missing` rows | Closed: done since 2026-11-09 |

## 4. Decisions

1. **Hidden tabs release their long reads.** A tab the browser hides, by switching tabs or minimising the window,
   releases its live reads and event feeds through the `Hold` of 2026-11-18, and reads again when it is shown. A
   hidden tab no longer holds connections, so the six are shared only among the tabs in view. HTTP/2 on the server
   was the other choice; the JDK's HTTP server has no HTTP/2. Consul's UI answers the same limit differently: its
   blocking queries are off unless a user turns them on in its settings
   ([HashiCorp, "Consul UI improvements"](https://hashicorp.com/it/blog/consul-ui-improvements)).
2. **A retried request is logged.** The client logs at INFO each request it sends again to the next server, naming
   the request, the server passed over, what it answered and the server tried next, and each request no server took,
   with how many were tried and the last one's answer. The owner asked for the open items to be finished, and this
   item asks for these lines; no existing line changes its level. Amended in Phase 2:
   - A follower already logs at INFO, under the request's ID, each forward it withdraws, gives up or cannot send
     (since 2026-11-13), and it never sends a forward twice, so nothing was added on the server.
   - The client's lines carry no request ID: the client gives each attempt its own `X-Request-Id`, so one ID would
     not tie the attempts together, and the request's name does.
   - An answer is a code (`leader_unavailable`, `request_timeout`, `HTTP 503`), never a server's message or an
     exception's, and a forwarded request is named by its path without the query, which can carry `?token=`.
3. **A renewal is bounded by the time its check has left.** While the last accepted observation still keeps the
   check, the publisher tells the client when the next must land: when that observation was sent, on the client's
   clock, and its TTL. The server counts its deadline from when it received the observation, so its deadline is no
   earlier, and no clock is compared with another's. The client gives each server it tries at most its share of the
   time left, never more than the request timeout, so a server that cannot answer in time is given up while the next
   can still be tried. With the acceptance client's 5 s TTL and 3 s request timeout, a renewal sent with 2.5 s left now
   gives each of three servers about 0.8 s. A server's own answer in a healthy cluster takes milliseconds. Once the
   time has run out, nothing is saved by giving up early, and an attempt gets the request timeout again.
4. **The audit file rotates by size**, as Consul's audit file sink does with `rotate_bytes` and `rotate_max_files`
   ([Consul, audit logging](https://developer.hashicorp.com/consul/docs/monitor/log/audit)); reads stay audited.
   The interface's reads are requests under a token like any other. `server.audit.rotateBytes`, 24 MiB by default,
   and `server.audit.maxFiles`, 15 by default, are the figures of Consul's own example (its docs disagree between
   releases on `rotate_max_files`' default). Before a line that would take the file past the size, the file is moved
   aside under its name and the time, and the oldest moved files past the number are removed. A file Windows will not
   move while a reader holds it goes on taking lines and is moved at a later one, so no line is lost to rotation. The
   audit page reads the current file only, and already says how much of it was read.
5. **The Docker stage is kept as it is.** Amended in Phase 5; it was "shortened by configuration of its tests
   only".
   - The largest saving configuration alone could give is a shorter cleanup grace for the tests,
     `server.raft.membership.cleanup.failureTimeoutMs`, 60 s by default and set by no Docker configuration, which the
     handover put at 3 to 4 minutes of a 26 to 34 minute stage.
   - The five classes that wait for the cluster's timers hold their waits against that 60 s in code: two absences of
     40 s that must never add up to one grace, holds of 25 to 40 s, and limits sized around it. Each would have to be
     derived again, on a real cluster, from a shorter grace, and a margin set wrong is an intermittent failure, which
     the project treats as a defect.
   - The stage runs unattended on Jenkins. The other options (a lower reconnect backoff cap, the start-script tests in
     a stage of their own, classes in parallel on separate clusters) change the product, the pipeline, or the load on
     a node that the PeeGeeQ jobs share.
6. **The step-down is closed after investigation.** Amended in Phase 6; it was "investigated first and fixed only on
   evidence".
   - The step-down has not happened again. In every Jenkins build saved since 64, builds 64 to 86, a leader steps
     down by check-quorum in a Docker class only in `DockerClientTopologyTest`, `DockerDurableRestartTest` and
     `DockerRunningPartitionTest`, each of which cuts a leader off from the majority on purpose, at the time it does
     so.
   - The run that met it, `DockerServerReplacementTest` on 2026-10-27, came before deadlines on every Raft call and
     keepalive on every connection (2026-10-28). Its record says that "no Raft RPC has a deadline, so a stalled call
     logs nothing". A call stalled on a connection that died without a reset is the likeliest cause, and one that
     would now end at its deadline and be logged. Its logs are not kept, so the cause cannot be established.
   - A busy leader cannot step down falsely: since 2026-11-15 it counts check-quorum in heartbeat rounds, and its
     heartbeat timer goes through the transition sequencer with the answers it counts, so a leader whose sequencer
     waits counts no rounds either.
   - A busy follower can still answer late. `RaftTransitionSequencer` runs one transition at a time, its result
     applied before the next begins, and a follower's AppendEntries, a heartbeat included, is one, so a heartbeat
     waits behind an append's sync or a snapshot's installation. The leader steps down for it only when the other
     voters are silent too, for an election timeout. Until the follower finishes, the leader could not commit with it
     either way.
   - `hashicorp/raft` answers heartbeats on a fast path "to avoid head-of-line blocking". That keeps such a leader
     through the delay, but it is a change to the protocol's ordering on both sides, and it belongs in a list of its
     own if a step-down with that cause is seen.
7. **The start scripts' name-lookup warnings are kept.** They are gRPC's own WARN while a peer's container is not
   started yet, and Docker gives a container its name only when it starts. Making the scripts wait for each server
   would not remove them: whichever server starts first still looks up peers that have not started. Consul's agent
   warns the same way, `[WARN] agent: (LAN) couldn't join`, for a `retry_join` name that does not resolve yet
   ([HashiCorp forum](https://discuss.hashicorp.com/t/meaning-of-failed-to-resolve-consul-consul-server-0-consul-consul-server-consul-svc/19024)).
8. **The Jenkins host:** a Java upgrade on the host is followed by a Jenkins restart between builds (build 85). The
   fresh clone each build stays: it is what makes a build depend only on what is pushed.
9. **Services from the interface** were decided on 2026-11-15 with UI-5 (its task list, decision 7; the interface
   plan's open question 5). The handover item was left open by mistake.
13. **The npm registry:** builds keep reaching it. Jenkins reaches it (build 68), and a mirror is infrastructure the
   project does not otherwise need.

## 5. Phase 1: Hidden tabs

- Red: a live read and an event feed stop their requests when `document.visibilityState` becomes `hidden`, and read
  again when it becomes `visible`; a paused view stays paused when shown.
- Green: `LiveProvider` releases and resumes its holds on `visibilitychange`.
- `mvn install`.

## 6. Phase 2: Retried requests

- Survey the client's and the forwarding server's retries; red tests on the lines each must write.

## 7. Phase 3: A renewal's margin

- Survey the renewal's request and its timeout; red test that a renewal waiting on a server past the check's time
  left is given up and sent to the next server.

## 8. Phase 4: Audit rotation

- Survey the audit sink and its query; red tests on rotation by size, the files kept, and the query over the
  current file.

## 9. Phase 5: The Docker stage

- Surveyed the five timer-bound classes and their configuration: decision 5, amended. No code changes.

## 10. Phase 6: The leader step-down

- Investigated: decision 6, amended. No code changes.
- Found on the way, and left to the owner as a new open item in the handover: a follower builds every held entry's
  payload for each AppendEntries it takes, a heartbeat included (`RaftNode.prepareAndPersistFollowerAppend`, the
  `currentEntryData` passed to `raftlog`'s `AppendPlan.from`), so the work grows with the log up to its snapshot.
  Narrowing it depends on what `AppendPlan` reads, which is the `raftlog` library's contract.

## 11. Record

Local runs are from `C:\qx11`, a clone of the workspace refreshed to its `HEAD` with the list's files copied over it;
the workspace's `src/main/ui/node_modules` is held open, so `npm ci` cannot run there (`EPERM`). The Docker stage runs
once over the phases that change the runtime, at the end of Phase 5, and again after Phase 6.

- **Phase 1, hidden tabs** (2026-11-20). `LiveProvider` releases its holds on `visibilitychange` to `hidden` and
  resumes them on `visible` (`live.tsx`); `views/testing.ts` has `tabVisibility`, and `setupTests.ts` gives each test a
  visible document. Red first: three tests failed for the right reason (the read not aborted, the poll still pending,
  the watch still open); two more pass on the old code and guard against regressions (a paused view stays paused; a
  page both hidden and put into the cache reads once when shown). `mvn clean install`: default 2,086 and Vitest 379,
  every coverage gate; audit of 614 files, no unflagged line.
- **Phase 2, retried requests** (2026-11-20). `HttpCatalogClient` logs, in each of its seven loops across the servers,
  a request passed over to the next server and one no server took (decision 2). Red first: the new test failed with no
  line logged; a second, that a request the first server takes logs nothing, guards against a regression. The client
  tests, 41 classes, passed.
- **Phase 3, a renewal's margin** (2026-11-20). `ObservationClient.observe(observation, landBy)`, a default that
  ignores `landBy`; `HealthPublisher` passes the time its last accepted observation keeps the check while that is
  ahead; `HttpCatalogClient` bounds each attempt by its share of the time left (decision 3);
  `ServerContactTracker.clock()` gives the client's clock. Red first: the publisher sent every observation without
  `landBy`, and the client's observation to a silent server and then an accepting one waited out the test's 5 s; a
  third test, an observation with no time left, guards the request timeout. The client tests passed.
- **Phase 2 closed** (2026-11-20): `mvn clean install` 2,088 and Vitest 379, every coverage gate; end-to-end 36; audit
  of 642 files, no unflagged line. The end-to-end log holds 779 of the new lines, 408 of them from one test whose
  server answers `held_by_test` while the publisher retries, the rest heartbeats and lookups around the tests'
  stopped servers and lost leaders: each is a request really sent again.
- **Phase 4, audit rotation** (2026-11-20). `server.audit.rotateBytes` and `server.audit.maxFiles` (`AppConfig`, in its
  effective settings), and `AuditFileSink.Rotation` (decision 4); the runbook's audit section and the design's
  section 15.3 say so. Red first: the settings were refused as unknown and missing from the effective settings; the
  sink moved nothing aside and kept nothing. The audit, configuration and audit-route tests passed.
- **Phases 3 and 4 closed** (2026-11-20): `mvn clean install` 2,094 and Vitest 379, every coverage gate; end-to-end 36;
  audit of 642 files, no unflagged line.
- **Phases 5 and 6** closed without code (decisions 5 and 6).
- **The Docker and `slow` stage** over Phases 2 to 4 (2026-11-20): 52, no failures, errors or skips, in 26 min 23 s.
  Its audit, of 893 files with its 25 Docker archive folders, found 625 error lines and 430 stack traces, all flagged,
  no `too_many_pings` and no JVM crash file (`docs/JENKINS.md`, "Local runs for the open items, 2026-11-20").
