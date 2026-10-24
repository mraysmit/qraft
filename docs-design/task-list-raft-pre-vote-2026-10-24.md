# Task List: Raft Pre-Vote

**Date:** 2026-10-24
**Last updated:** 2026-10-24 (created; design decided, nothing built)
**Status:** Active. Follows the completed membership list,
[`task-list-raft-membership-changes-2026-10-24.md`](../docs/archive/task-list-raft-membership-changes-2026-10-24.md).
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 14.9
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

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
8. **Exit gate.** Three consecutive green Jenkins runs of all three stages on the
   final code; every log audited (no unflagged error, no unflagged trace); the
   audits of the membership list's step 8 repeated; this list completed and
   archived.

## 6. Out of scope

- Leadership transfer, and any change to how a leader steps down.
- Adopting a term from a pre-vote answer.
- Changing the election timeout, its jitter, or the stickiness window.
