# Task List: Forwarding Writes to the Leader

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phases 1 to 3 are done, test first, and the final local run of the three suites passed on them: default 1,918 with every coverage gate met, end-to-end 28 and Docker 51, with no failures, errors or skips; audit over the 269 files the run wrote: 911 error lines, all flagged, 624 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 for the feature validation's last open consistency row, leader forwarding for writes, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (the final run recorded, and the list complete and archived. Earlier the same day: phases 1 to 3 built)
**Follows:** [`task-list-kubernetes-auth-method-2026-11-08.md`](task-list-kubernetes-auth-method-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 13
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the leader
forwarding row
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

Any server accepts a write, as a Consul server does: a follower sends it to the leader and answers with the leader's
answer, so a caller that reaches any server need not find the leader itself.

## 2. Where Qraft stands (2026-11-08)

- A follower answers a write 503 `leader_unavailable` with the leader hint (`X-Qraft-Leader-Id`), and a client tries
  its next server (consistency list).
- A server removal is already sent to the leader over the Raft port's gRPC by the membership service.

## 3. Reference

Consul's servers forward an RPC that needs the leader over their server port; the caller sees the leader's answer.

## 4. Decisions

Taken on 2026-11-08 as the recommended options, under the owner's instruction; each can be reversed.

1. **Writes are forwarded over the Raft port.** A follower that knows the leader sends a `PUT`, `POST` or `DELETE` on
   its HTTP API to the leader with a new `Forward` RPC beside `RemoveServer`, carrying the method, the path and query,
   the token and scope headers and the body, and answers with the leader's status, headers and body. The Raft port's
   mutual TLS, when it is on, protects it.
2. **The leader answers a forwarded write on its own routes,** through the same handler, filters and wrapper as a
   request to its listener, so its ACLs, tenancy, audit, metrics and body limits apply as to any other request.
   Changed on 2026-11-08 from "on its own HTTP listener, over loopback": over HTTPS the leader's certificate need not
   name the loopback address, and the JDK's HTTP client always checks the name.
3. **One hop.** A forwarded write carries `X-Qraft-Forwarded-By` with the follower's ID; a server that receives one and
   does not lead answers 503 `leader_unavailable`, as does a follower that knows no leader or cannot reach it. The
   answer to a forwarded write carries `X-Qraft-Forwarded-To` with the leader's ID.
4. **Reads are not forwarded:** the consistency list's decision 1 stands, and `?consistent` on a follower still answers
   503 with the hint.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. The Forward RPC

- [x] `Forward` on the Raft port: the request and answer messages, the server's handler handing a request to a forward
  target, and the transport's call to a peer by ID.

**Exit:** the new tests seen red first; `mvn install`.

**Record (2026-11-08):** `ForwardRequest` and `ForwardResponse` in `raft.proto` and `rpc Forward` beside
`RemoveServer`; `GrpcRaftServer.useForwardTarget`, which answers 503 `leader_unavailable` until it is given;
`RaftTransport.forward`, failing by default, and `GrpcRaftTransport.forward` with a 30-second deadline.
`GrpcRaftForwardTest` was seen red 3 of 3 against stubs, then green with `GrpcRaftTransportTest` and `GrpcRaftTlsTest`,
35. The `mvn install` exit was taken with Phase 2's run.

### Phase 2. Forwarding on the HTTP API

- [x] A follower forwards a write and relays the answer; the leader answers it on its own listener, over HTTPS when it
  serves HTTPS; a forwarded write on a server that does not lead, and a write with no leader, answer 503.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Record (2026-11-08):** `HttpApiServer` remembers every route's context. A follower's write is forwarded inside the
request wrapper, after the request ID is set and the body read and limited, with the request's headers bar the
hop-by-hop ones. The leader answers it with `ForwardedExchange`, an in-memory exchange run through the route's own
filters and handler, as decision 2 now says. `QraftServerService` wires the HTTP API's target into the Raft server
and the transport into the HTTP API. `QraftServerWriteForwardingTest` (three servers in one JVM) was seen red with the
follower's 503, then green: a write with its query, a registration with its identity header, and a session renewal
(which only the leader makes) through a follower; a second hop refused; and a follower whose leader is gone answering
503. The two servers it stops are declared with `expectPeerShutdownDuringCleanup`.

Two defects were found and fixed, each test first. A draining follower forwarded a write where it should take no new
work; the test, which drains the second follower, was seen red with 200, and a draining server now lets its route answer
503 `draining`. Repeated runs then failed once in three: the first write through a follower was refused 503 with
`UNAVAILABLE: io exception`, because a follower seldom calls its leader and its channel was still in gRPC's reconnect
backoff from before the leader listened. A `GrpcRaftForwardTest` case that leaves a channel in its backoff and then starts
the peer was seen red with that error.

The first run of the three suites then found what that wait cost, and two more defects. `HealthPropagationEndToEndTest`
timed out closing its clients: a deregistration through a follower whose leader had stopped was held for 10 seconds,
past the client's shutdown budget. `ObservabilityEndToEndTest` saw its client unready for a moment: a client now keeps
to the follower that answered its write, and the follower's own reads trailed the write it forwarded, so the client's
check of its registration found nothing yet. And a forward that failed after it was sent was answered
`leader_unavailable`, which tells a caller the write was not applied. Each was made a failing test first, in
`QraftServerWriteForwardingTest` and `GrpcRaftForwardTest`, and fixed:

- `forward` cuts the backoff short and waits at most 2 seconds for a ready connection, and sends only on one; without it
  the forward fails as `ForwardNotSentException`, answered 503 `leader_unavailable` at once.
- A forward that fails or times out after it was sent is answered 503 `outcome_unknown`, as a commit that times out on
  the leader is.
- The leader answers a forwarded write with its applied index, and the follower answers once it has applied that index
  itself, at most the Raft timeout later, so a caller that reads from it next sees its own write.

The four end-to-end tests the changes touch then passed five runs in a row, and every end-to-end test passed on qx8.
The three suites ran with Phase 3.

### Phase 3. Verification and documentation

- [x] An end-to-end test of writes through each follower of a three-server cluster with ACLs, and the design, the
  feature validation and the operations guide.

**Record (2026-11-08):** `WriteForwardingEndToEndTest` starts three servers serving only HTTPS, with mutual TLS on the
Raft port and ACLs under `deny`. A write through each follower is applied under the management token with the leader
named, and a token with no rules is refused 403 by the leader through each follower. Design section 13 (and the
key/value bullet of section 10), the feature validation's forwarding row and the operations guide describe it.

**Exit:** the three suites locally, audited, and the list complete and archived.

**Final run, 2026-11-08.** The three suites from `C:\qx7` (logs `logs/five-final/`), which also took the earlier phases' `mvn install` exits and suites, and closed five lists together: default 1,918 with every coverage gate met, end-to-end 28 and Docker 51, with no failures, errors or skips; audit over the 269 files the run wrote: 911 error lines, all flagged, 624 stack traces, none unflagged; no `too_many_pings`; no JVM crash file.

## 7. Out of scope

- Forwarding reads, and forwarding over more than one hop.
