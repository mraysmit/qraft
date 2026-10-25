# Task List: Raft Pre-Vote

**Date:** 2026-10-24
**Last updated:** 2026-10-25 (complete: Jenkins builds 43 to 45 green on `9c946ac`; moved to `docs/archive/`. Created 2026-10-24)
**Status:** Complete, 2026-10-25. Follows the completed membership list,
[`task-list-raft-membership-changes-2026-10-24.md`](task-list-raft-membership-changes-2026-10-24.md).
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 14.9
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

A voter that returns after a long stop, or a partition, cannot raise the
cluster's term or depose a healthy leader. When this list is complete:

- a server whose election timer fires first asks the other voters whether they
  would vote for it, and starts a real election, with a higher term, only if a
  majority would;
- a voter that has heard from a live leader within an election timeout, and a
  leader itself, answer no, so a returning voter's term stays where it was and
  the first heartbeat it hears returns it to follower in the same term;
- an election that is justified, such as the loss of the leader, still happens
  as quickly as before, plus one round trip;
- a cluster of old and new servers during a rolling upgrade still elects a
  leader.

## 2. Why

Jenkins build 35 (2026-10-24), `DockerPeriodicCleanupTest`: a follower stopped
for 40 seconds and restarted. The leader's channel to it was still backing off,
so it heard no heartbeat, ran four elections (terms 2 to 5), and the leader
stepped down on the higher term in its next AppendEntries answer. Two more
elections followed (terms 6 and 7). Leader stickiness (ignoring vote requests
while a leader is live) did not stop it, because the higher term arrived in an
AppendEntries response, not in a vote request.

The Raft thesis, section 9.6, describes exactly this ("either through the
server's RequestVote requests or through its AppendEntries response") and the
Pre-Vote phase that prevents it. etcd (`PreVote` with `CheckQuorum`),
HashiCorp raft (merged June 2024, pull request 530) and Kafka's KRaft use it.
The owner decided on 2026-10-24 to adopt it as the standard solution. Section
4.2.3 of the thesis shows Pre-Vote alone does not stop a removed server from
disrupting a cluster; stickiness does, and stays.

## 3. Rules

- Strict test-first: each behaviour has a failing test that was run and seen to
  fail for the right reason before any production code for it exists.
- Removing or changing a state transition starts with its old effects. Read the old
  code and list each observable effect (term, role, known leader, timers,
  readiness); mark each removed on purpose or kept; write a failing test for every
  kept effect before touching the code. (Build 42 found a cut-off follower still
  reporting ready: the candidate transition used to clear the known leader, and
  removing the term raise removed that too. Characterization before change is the
  usual practice for this.)
- Existing log levels and messages are not changed. A new message logs a
  condition the way its nearest existing sibling does.
- No local build or suite beyond a small check: the verification runs on Jenkins,
  started once the work is pushed, and every log it writes is audited.
- No change to the WAL, snapshot or metadata formats. The wire gains one RPC and
  two messages; no existing field changes.

## 4. Decisions

1. **A separate `PreVote` RPC**, with its own request and response messages,
   not a flag on `RequestVote`. HashiCorp tried the flag first and dropped it
   because an old server would treat a pre-vote as a real vote and change its
   state.
2. **The receiver's answer changes nothing.** It records no vote, adopts no
   term, and does not count as contact with a leader.
3. **A pre-vote is granted if and only if** the receiver is running, the
   candidate is a voter in the receiver's configuration (by server ID; a server
   with no configuration yet cannot check, and does not refuse for that: a first
   server that bootstrapped alone would otherwise never be elected, found by the
   first container run), the
   receiver is not a leader and has not heard from a leader within an election
   timeout (the stickiness test of `prepareAndPersistVote`, which becomes one
   method both use), the proposed term is strictly ahead of the receiver's own
   (etcd's rule: a pre-vote is always for the sender's term plus one, so one
   that is not ahead comes from a sender that is behind or confused, and the
   vote already cast in the receiver's term does not matter), and the
   candidate's log is at least as up to date (the same comparison the real vote
   uses).
4. **The candidate asks at `currentTerm + 1` without taking it.** It re-arms its
   election timer when it starts the round. The result is used only if that
   timer's generation is still current, so a heartbeat that arrives meanwhile
   (which resets the timer) cancels the round. A pre-vote quorum starts the
   existing election transition unchanged.
5. **A denial, or a timeout, ends the round.** The next election timeout starts
   another. A higher term in a pre-vote answer is not adopted: the leader's next
   heartbeat carries it (thesis section 9.6).
6. **A sole voter skips the round**, as it skips voting.
7. **Rolling upgrades.** A transport that cannot send a pre-vote fails as
   unsupported, like `describe`, `join` and `removeServer`, and a peer that
   answers UNIMPLEMENTED is counted as a grant, so a mixed cluster still elects.
   Any other failure is not a grant.
8. **A failed pre-vote request is logged as a failed vote request is** (same
   level, a message that names the pre-vote), and the existing
   unreachable-peer fault definition is extended to match it.
9. **CheckQuorum stays.** It lets a leader that cannot reach a quorum step down,
   which Pre-Vote needs so that a cut-off leader does not refuse elections for
   ever.

## 5. Steps

Each step is red, then green, then a small local check of the classes it touches.

1. **Wire and transport.** `PreVoteRequest` and `PreVoteResponse` and the `PreVote`
   RPC in `raft.proto`; `RaftTransport.sendPreVote` as a default that fails as
   unsupported; `GrpcRaftTransport.sendPreVote`; `GrpcRaftServer.preVote`.
   Tests: a gRPC round trip between two real transports; the default fails as
   unsupported; the request and response keep their field numbers.
2. **Receiver.** `RaftNode.handlePreVoteRequest`, sharing its decision with the
   real vote handler. Tests (held transport, manual timers): granted for an
   up-to-date voter when no leader has been heard; refused while a leader is
   live, by a leader, for a stale term, for a log that is behind, for a
   non-voter or unknown candidate, and when not running; and in every case the
   receiver's term, vote, state and leader contact are unchanged.
3. **Candidate.** The election timeout starts a pre-vote round; a quorum starts
   the election; a denial, a timeout, or a heartbeat in the meantime does not.
   Tests: no term change and no vote request without a pre-vote quorum; a
   quorum then starts the election at `currentTerm + 1`; a heartbeat during the
   round cancels it; a sole voter elects at once; an unreachable peer is not a
   grant; an UNIMPLEMENTED peer is.
4. **The incident, reproduced deterministically.** A voter that has been cut off
   for many election timeouts, then reconnected, leaves the leader and its term
   alone and returns to follower on the first heartbeat.
5. **Existing behaviour.** The elections that must still happen (loss of the
   leader, a fresh cluster, recovery, a seed, failover with one voter down) still
   happen; the manual-timer and in-memory-cluster suites pass unchanged except
   where they count messages.
6. **Container scenario.** A follower stopped long enough for the leader's
   channel to back off (about a minute) and restarted: for 30 seconds the leader
   and its term do not change. This is the reverse of the finding recorded in the
   periodic cleanup scenario.
7. **Documents.** The runbook's "What to expect" section loses the returning-voter
   limitation and gains the Pre-Vote behaviour; design 14.9 and the feature
   validation record are updated.
8. **Exit gate (done, 2026-10-25).** Three consecutive green Jenkins runs of all three stages on the
   final code; every log audited (no unflagged error, no unflagged trace); the
   audits of the membership list's step 8 repeated; this list completed and
   archived.

## 6. Progress, 2026-10-25

Each step below was written test first and its red run seen before the code.

1. **Wire and transport.** `PreVote` RPC and messages in `raft.proto`;
   `RaftTransport.sendPreVote` default (fails as unsupported);
   `GrpcRaftTransport.sendPreVote` and `GrpcRaftServer.preVote`, with the same
   telemetry as the vote RPC. Red: the gRPC round trip failed with "GrpcRaftTransport
   cannot ask node2 for a pre-vote". Green: 61 tests.
2. **Receiver.** `RaftNode.handlePreVoteRequest` and `wouldGrantPreVote`, with the
   stickiness test now one method (`liveLeaderKnown`) that the real vote uses too.
   `RaftNodePreVoteReceiverTest`, 10 tests, 7 failing first. Writing the tests
   corrected decision 3: a pre-vote is granted only for a term strictly ahead of the
   receiver's own (etcd's rule), which also makes the receiver's earlier vote
   irrelevant.
3. **Candidate.** `onElectionTimer` starts a round (`startPreVote`) for a voter with
   another voter; answers are counted on the state loop; a quorum calls the existing
   `startElection`. `RaftNodePreVoteCandidateTest`, 10 tests, 7 failing first. The
   unreachable-peer fault definition and the stopped-peer matcher were extended
   for the "Failed to retrieve pre-vote from ..." message, each with a failing test
   first (`DockerLogCaptureTest`, `IntentionalErrorsTest`).
4. **Compatibility.** An old server's `UNIMPLEMENTED` is turned into the unsupported
   failure by `GrpcRaftTransport`; red: `GrpcRaftTransportTest` saw a
   `StatusRuntimeException`. Counting such a peer as willing is covered in step 3.
5. **The incident, deterministic.** A voter cut off through ten election timeouts
   keeps term 0 and answers the leader's first heartbeat in the leader's term 7.
   Mutation check: with `asksForPreVotes()` forced to false the test fails with
   `expected: <0> but was: <10>`, the incident's term inflation; the line was
   restored.
6. **Container scenario.** `DockerReturningVoterTest`: a follower stopped for 70
   seconds and restarted; for 40 seconds the leader and the term are unchanged on
   every server. **Its first run found a defect no unit test had:** the cluster
   never elected a leader at start-up. The first server bootstrapped alone, the
   others tried to join it, it needed their votes, and they refused every
   pre-vote because they had no configuration yet to check the sender against.
   Fixed test first (a server with no configuration answers by the other rules);
   red: `aServerThatHasNoConfigurationYetStillAnswersByLeaderTermAndLog`. The
   second run was invalid (the machine was suspended; the test reported nine hours
   elapsed); in its logs the restarted follower asked for pre-votes about every
   2.5 seconds and stayed at term 1. The third run passed in 143.6 s with no
   unflagged error: the returning follower asked for pre-votes six times, started
   no election, and took only the leader's term from a heartbeat.
   **Jenkins build 42 (2dcb2ec), the first run with Pre-Vote, failed on the Docker
   stage** (default 1,189, end-to-end 10 passed; Docker 41 tests, 3 errors;
   `DockerReturningVoterTest` passed there in 124 s). Two of the three were tests
   that proved "it is campaigning" by a rising term, which Pre-Vote prevents:
   `DockerRunningPartitionTest`'s partitioned follower and my own
   `DockerRemovedServerCampaignTest`. Both now count "Asking for pre-votes for node
   X" in the server's log and assert the term never moved and the server never
   became a candidate. The third, the partitioned-leader test, failed before any
   fault because the previous test had left a follower cut off for 60 seconds; it
   passed once that test was fixed. The two unflagged traces in the audit were the
   failing tests' own `ConditionTimeoutException` output.

   **Fixing the partitioned-follower test found a second real defect:** a follower
   cut off from its leader reported itself ready (HTTP 200) indefinitely, because it
   used to forget its silent leader by becoming a candidate and now stays a
   follower. Test first: `aLeaderThatHasBeenSilentForAWholeElectionTimeout...`
   failed with `expected: <null> but was: <peer-2>`. The pre-vote round now calls
   `forgetSilentLeader()` (its log message no longer says "does not campaign").
   Local checks after both fixes: 451 tests in 45 classes, and the three affected
   container classes (partition 18 s with both tests, removed-server 62 s,
   returning voter 120 s) pass with no unflagged error.
   **Effects audit of the removed candidate transition (the new Rules item applied
   after the fact).** Reading `applyElection`, the old election timeout did: raise
   the term (removed on purpose); record the vote for itself (removed, nothing is
   voted); become CANDIDATE (removed); clear the known leader (kept: found missing by
   build 42, now `forgetSilentLeader()` with a unit and a container test); notify
   state-change listeners of CANDIDATE (removed; the only production listener,
   `LeaderHealthExpiry`, reacts to LEADER alone, and `awaitState` is test-only);
   re-arm the election timer (kept: `startPreVote` re-arms first, tested by the
   heartbeat-cancels-the-round test); send vote requests (kept, after a quorum of
   pre-votes). `leaderContactActive = false` is set before either path. No further
   gap found, so no further code change.
7. **Documents.** The runbook's "What to expect" section, design section 14.10 (and
   14.9's limitation), and the feature validation record are updated.

Local checks, 2026-10-25: after the receiver fix, `RaftNode*Test`, the pre-vote
tests, `GrpcRaft*Test`, `ClusterBootstrapTest` and `QraftServer*Test`: 372 tests in
38 classes pass; before it, 484 tests in 49 classes (those and the sequencer,
transport, bootstrap, membership and fault-audit suites) passed. Not yet run: the
full default, end-to-end and Docker suites, which run on Jenkins.

Exit gate, 2026-10-25: Jenkins builds 43, 44 and 45, all on `9c946ac`, passed every
stage (default 1,190, end-to-end 10, Docker 41; coverage gates met). In each, every
ERROR line in the console and the archived logs is flagged, the only stack trace the
checker could not tie to a flag is the flagged `lock-contender` trace in
`DockerDurableRestartTest` (its flag is on the "Exception in thread" header), and no
error mentions a draining sequencer. The membership list's step 8 audits were
repeated over the changes since `ece88aa~1`: no pom change, every new Java file has
its header and `@author`, no assistant attribution in code, docs or commit
messages, no `Thread.sleep`, environment or system-property configuration added,
and `git diff --check` is clean.

Follow-up, 2026-10-25: a returning follower lagging until the leader's gRPC
channel reconnects. Measured locally with `DockerReturningVoterTest`: ready 0.2 s
after it first answered following a 70 s stop, but 54.6 s following a 200 s stop
(gRPC backs off up to 120 s). Other Raft transports lower the backoff ceiling
(brunoga/raft: 50 ms to 2 s); the fix here resets the backoff on proof of life.
Test first: `RaftNodePreVoteReceiverTest` (four tests: a pre-vote or vote from a
configured peer, granted or refused, calls `RaftTransport.peerHeardFrom`; an
unknown server does not) and `GrpcRaftTransportTest` (a channel backing off from
a restarted peer connects within 600 ms of `peerHeardFrom`; red with a timeout
before the change). `DockerReturningVoterTest` now stops the follower for 200 s and
requires it ready within 15 s of answering; it passed locally in 261 s, where the
same scenario took 54.6 s to be ready before. The Jenkins exit gate must be
repeated on the new code: three consecutive green runs, logs audited.

## 7. Out of scope

- Leadership transfer, and any change to how a leader steps down.
- Adopting a term from a pre-vote answer.
- Changing the election timeout, its jitter, or the stickiness window.
