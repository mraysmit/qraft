# Task List: Consistency Modes and Blocking Queries

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phases 1 to 5 are done, test first, and the final local run of the three suites passed on them: default 1,797 with every coverage gate met, end-to-end 19 and Docker 48, with no failures, errors or skips; audit over the 257 files the run wrote: 1,021 error lines, all flagged, 653 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 as item 4 of the feature validation's order, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (Phase 5 recorded, and the list complete and archived. Earlier the same day: Phases 1 to 4 built, test first, with their records in section 6. Earlier the same day: started)
**Follows:** [`task-list-key-value-2026-11-08.md`](task-list-key-value-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), sections 10 and 13
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the consistency rows and item 4
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

Reads of key/value and of the catalog choose their consistency, as design section 13 has them, and may block until
the state changes, as Consul's blocking queries do:

- `?consistent`: the leader confirms that it still leads, by a round of heartbeats a quorum answers, and answers once
  it has applied everything committed when the read began (Raft's ReadIndex, Consul's `VerifyLeader`);
- `?stale`: any server answers from what it has applied, and says how long since it heard from a leader;
- the default: as `?stale`, without the requirement to name it (decision 1);
- `?index=N&wait=D`: the read waits until the index it answers with is greater than `N`, or `D` passes.

## 2. Where Qraft stands (2026-11-08)

- Every read is the answering server's applied state; catalog and key/value reads carry `X-Qraft-Index`.
- `RaftNode` has check-quorum: a leader counts the peers that answered within an election timeout's heartbeat rounds,
  and steps down without a quorum. A new leader commits a no-op in its term.
- The discovery reads refuse any query parameter but the health route's `passing`; `/v1/kv` refuses `stale` and
  `consistent` until this list.
- The client forwards discovery and key/value reads with its request timeout, 5 s by default.

## 3. Reference: Consul

From Consul's documentation of consistency modes and blocking queries, and `agent/http.go` and `agent/consul/rpc.go`:

- Default reads go to the leader, which answers without confirming its leadership; `?consistent` confirms it with a
  quorum (`VerifyLeader`); `?stale` lets any server answer. Each answer carries `X-Consul-KnownLeader` and
  `X-Consul-LastContact`, the milliseconds since the answering server heard from the leader.
- A blocking query gives `?index=` the last `X-Consul-Index` it saw and `?wait=` up to 10 minutes, 5 by default. The
  server answers once the index passes it, or at the wait, and adds up to a sixteenth of the wait at random, so that
  clients do not wake together. An index of 0 answers at once.

## 4. Decisions

Taken on 2026-11-08 as the recommended options, under the owner's instruction; each can be reversed.

1. **The default is the answering server's applied state,** as today, with the leader-contact headers. Consul's default
   reads are forwarded to the leader by the servers; Qraft's servers do not forward, and its clients and tests read
   from any server, so making the default leader-only would refuse every read a follower answers now. A difference
   from Consul, recorded in design section 13.
2. **`?consistent` on a follower answers 503 `leader_unavailable`** with the leader hint, as a write does, so a client
   tries its next server.
3. **A ReadIndex barrier in `RaftNode`.** `readBarrier()` records the commit index, or the leader's no-op of its term
   if that is later, sends a heartbeat round, and completes once a quorum has answered a request sent after the read
   began and that index is applied. Each AppendEntries request carries the sequence it was sent at, so a late answer to
   an older request does not count. It fails when the node stops leading.
4. **Headers:** `X-Qraft-Known-Leader` (`true` or `false`) and `X-Qraft-Last-Contact` (milliseconds; 0 on the leader),
   on every catalog and key/value read.
5. **Blocking queries on the index each read answers with:** the key/value state's last index for `/v1/kv`, the
   applied index for catalog reads. `wait` is a duration (`10s`, `5m`), 5 minutes by default and at most 10, with up
   to a sixteenth added at random; an index of 0, or one the state already passed, answers at once.
6. **The client forwards a blocking read with its wait added to its request timeout,** so the forwarded read is not cut
   short.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. The ReadIndex barrier

- [x] `RaftNode.readBarrier()`: a single server completes at once with its applied index; three servers complete once a
  quorum answers; a follower fails; a leader that cannot reach a quorum does not complete, and fails when it steps down;
  the barrier waits for the read index to be applied.
- [x] AppendEntries answers counted by the sequence their request was sent at.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Phase 1 record, 2026-11-08.** `RaftReadBarrierTest` (4), seen red against a stub that always failed, three of them,
the follower's refusal passing against it too; then green with the Raft node tests, 328. The barrier lives on the
state loop; a barrier is checked after every same-leadership AppendEntries answer and after every applied entry, and
failed with the pending commands when leadership ends.

### Phase 2. Waiting on an index

- [x] `QraftStateStore` wakes waiters after each applied entry: `awaitAppliedIndex` and `awaitKvIndex`, each with a
  deadline.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 2 record, 2026-11-08.** `QraftStateStoreIndexWaitTest` (4), seen red against stubs that did not wait, then
green. A lock and condition wake the waiters on each applied index, a restore and a reset.

### Phase 3. Modes, headers and blocking queries on the server

- [x] `?stale`, `?consistent`, the default, and the leader-contact headers on the catalog's reads and `/v1/kv`.
- [x] `?index` and `?wait` on the same reads; refused when malformed or longer than 10 minutes.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Phase 3 record, 2026-11-08.** `HttpApiServerConsistencyTest` (5), seen red, four of them, the malformed-option case
passing against the old refusal of every option; then green with the HTTP API tests. Three older tests that refused
`?stale` now refuse another unknown name. `RaftNode` already kept the time of the last leader contact, on its timer
clock, for leader stickiness; the leader-contact header reads it, and a first attempt that added a second field did
not compile. A finding on the way: `ClientKvForwardingTest`'s teardown let a heartbeat in flight submit after the
state loop had stopped, which `RaftNode` logged at ERROR as a WAL failure. A transition the stopped loop refuses never
ran, as one refused while draining does not, so `RaftTransitionSequencer` now refuses it as draining, logged at
debug, with the loop's refusal as its cause; its test was seen red first.

### Phase 4. Through the client

- [x] A forwarded blocking read waits as long as it asks, plus the request timeout.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 4 record, 2026-11-08.** A case in `HttpCatalogClientForwardTest`, seen red with a 1 s request timeout against
a server that answers in 1.5 s, then green: both forwarding paths set their timeout to the request timeout plus the
read's wait and a sixteenth.

### Phase 5. Verification and documentation

- [x] An end-to-end test: a blocking key/value read through a client wakes on another client's write; a consistent read
  through a follower is refused and through the leader answered.
- [x] A Docker test of a consistent read and a blocking query on three servers.
- [x] Design section 13, the feature validation's consistency rows and the Consul plan.

**Exit:** the three suites locally, audited, and the list complete and archived.

**Phase 5 record, 2026-11-08.** `ConsistencyEndToEndTest` (e2e): of three servers exactly one, the leader, answers a
consistent read, a consistent read through a client finds it, and a blocking key/value read through one client wakes
when another writes. `DockerConsistencyTest`: on three containers the leader alone answers a consistent read, every
server answers a stale one naming a known leader, and a follower's blocking read wakes on a write. Neither was seen
red, since what they check was built, red first, in Phases 1 to 4. The design (section 13), the feature validation's
consistency rows and the Consul plan are in line. The first final run failed in its default suite on one more older
test that refused `?stale`, through the client's local discovery read, now refusing another unknown name; that run
was stopped, since its Docker suite would have shared the copy with the rerun. The final run (logs
`logs/local-run-2026-11-08-consistency-final2/`): default 1,797 with every coverage gate met, end-to-end 19 and Docker 48, with no failures, errors or skips; audit over the 257 files the run wrote: 1,021 error lines, all flagged, 653 stack traces, none unflagged; no `too_many_pings`; no JVM crash file. The audit of the whole folder also met one line
from a check run before the sequencer's fix, copied in from the copy's own logs: the teardown finding of Phase 3.

## 7. Out of scope

- Server-side forwarding of reads and writes to the leader (decision 1); `max_stale` and `?cached`; blocking on the
  catalog's own indexes per service, which Consul keeps per table.
