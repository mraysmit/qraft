# Task List: Sessions and Locks

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phases 1 to 4 are done, test first, and the final local run of the three suites passed on them: default 1,819 with every coverage gate met, end-to-end 20 and Docker 49, with no failures, errors or skips; audit over the 262 files the run wrote: 997 error lines, all flagged, 643 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 as item 5 of the feature validation's order, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4, and those recorded in the Phase 3 record, were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (Phase 4 recorded, and the list complete and archived. Earlier the same day: Phases 1 to 3 done and recorded, and Phase 4's tests and documents written; started the same day)
**Follows:** [`task-list-consistency-2026-11-08.md`](task-list-consistency-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 11
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the sessions rows and item 5
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

Sessions and locks as design section 11 has them, on Consul's: a session binds a node to an optional TTL and a
behaviour; creating, destroying and invalidating one are replicated; a key is locked by acquiring it with a live
session, and released only by that session; an invalidated session releases or deletes its keys, and its keys cannot
be acquired again for its lock delay.

## 2. Where Qraft stands (2026-11-08)

- Key/value entries have `LockIndex` 0 and no `Session`; `?acquire` and `?release` are refused (key/value list,
  decision 6).
- ACL rules have no `session` resource; the parser says the sessions list adds it.
- Leader-only expiry runs through `ExpiryScheduler`, as `LeaderHealthExpiry` and `AclTokenExpiry` do.

## 3. Reference: Consul

From Consul's session and KV documentation and `agent/consul/session_ttl.go`, `state/session.go` and
`state/kvs.go`:

- `PUT /v1/session/create` takes `Name`, `Node`, `LockDelay` (15 s by default), `Behavior` (`release` by default, or
  `delete`) and `TTL` (10 s to 24 h, or none) and answers `{"ID": ...}`. `PUT /v1/session/destroy/{id}`,
  `PUT /v1/session/renew/{id}`, `GET /v1/session/info/{id}`, `/v1/session/node/{node}` and `/v1/session/list`.
- TTL timers are kept by the leader in memory; a renewal resets the timer and is not replicated; on a new leader every
  timer restarts at twice its TTL. An expired session is destroyed through Raft.
- A session is invalidated when it is destroyed, when its TTL lapses, or when its node fails or is deregistered.
- `?acquire=<session>` sets the key and its session if the key is free, or already the session's, and the session is
  valid and the key's lock delay has passed; a new holder increments `LockIndex`. `?release=<session>` frees a key the
  session holds. Invalidation releases or deletes the session's keys by its behaviour, and starts each key's lock delay.
- ACLs: `session` and `session_prefix` rules name a node; creating a session needs `session:write` on its node,
  reading one `session:read`; acquiring needs `key:write`.

## 4. Decisions

Taken on 2026-11-08 as the recommended options, under the owner's instruction; each can be reversed.

1. **Sessions are global,** named by ID, and bound to a node by name; the keys they lock may be in any tenant and
   namespace. Consul scopes them by partition, which Qraft does not have.
2. **TTL timers on the leader alone, as in Consul:** a renewal resets the leader's timer and is not replicated, a
   follower answers a renewal 503 `leader_unavailable` so the client tries its next server, and a new leader restarts
   every timer at twice the TTL.
3. **Times come from the proposer.** A creation, an invalidation and an acquisition carry the leader's time, so the
   lock delay a replica keeps is the same on every replica. Consul sets it from each server's own clock.
4. **A node's failure invalidates its sessions:** the node's removal, or its becoming unreachable, which is Qraft's
   form of Consul's `serfHealth` going critical. Sessions bound to service checks are left for later.
5. **The server takes `Node` from the body; the client's local API takes its own node** when the body names none, as
   Consul's agent does.
6. **No leader-election helper in code.** Consul's `consul lock` runs a child process under a lock; Qraft documents the
   recipe on the local API (create a session, acquire, renew, release) in the operations guide, and leaves a command
   for later.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. Replicated sessions and locks

- [x] `state.session`: sessions by ID with node, name, behaviour, TTL, lock delay and creation index; create and
  destroy commands; the snapshot carries them.
- [x] Key/value acquire and release with `LockIndex` and `Session`; invalidation releases or deletes by behaviour and
  starts the lock delay; the delay refuses an acquisition stamped before it ends.
- [x] A node's removal or its becoming unreachable invalidates its sessions.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 1 record, 2026-11-08.** `state.session` holds `Session`, `SessionBehavior` (`release` or `delete`),
`SessionState`, `SessionCommand` (`Create`, and `Destroy` with the proposer's time) and `SessionCodec`; the Raft entry
carries `session_command = 7`, and a key/value command `lock = 6` and `unlock = 7`. `KvState.lock` follows Consul's
`kvsLockTxn`: refused while another session holds the key or its lock delay runs past the acquisition's time, and
`LockIndex` grows only for a new holder; a plain set keeps the lock. `QraftStateStore` refuses a session on a node that
is not registered (`node_not_found`) and an acquisition by a session that does not exist (`invalid_session`), and
invalidates a node's sessions when it becomes unreachable, is reaped or deregistered, or is replaced by a registration
under its name. The snapshot carries the sessions and the lock delays, and one written before sessions restores none.
`QraftStateStoreSessionTest`, 8 tests, was seen red 7 of 8 against stubs, then green, 101 tests with the key/value and
codec classes. The exit's `mvn install` is the Phase 3 run's default suite.

### Phase 2. TTL expiry on the leader

- [x] `SessionTtlExpiry`: a timer per session with a TTL on the leader, reset by a renewal, restarted at twice the TTL
  on a new leader; a lapsed session is destroyed through Raft.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 2 record, 2026-11-08.** `server.session.SessionTtlExpiry`, on the pattern of `AclTokenExpiry`: while this
server leads, each session with a TTL lapses at twice its TTL after the later of its creation and the start of the
leadership, or after its last renewal, as Consul's `session_ttl.go` has it; once a second the lapsed ones are destroyed
through Raft, stamped with the leader's time, and a proposal that fails is tried again at the next check. A renewal
changes only the leader's memory and a follower refuses it. `QraftServerService` attaches it beside the token expiry.
`SessionTtlExpiryTest`, 3 tests on the manual clock and scheduler, was seen red (2 failures, 1 error) against a stub,
then green with `QraftStateStoreSessionTest`, `AclTokenExpiryTest` and `LeaderHealthExpiryTest`, 23 tests. A first
green run named its classes with `+`, which Surefire does not read, and ran none; the rerun named them with commas.

### Phase 3. The HTTP API and ACLs

- [x] `/v1/session/create`, `destroy`, `renew`, `info`, `node` and `list` on the server; `?acquire` and `?release` on
  `/v1/kv`.
- [x] `session` and `session_prefix` ACL rules; the checks Consul makes.
- [x] Through the client's local API, with its own node by default.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Phase 3 record, 2026-11-08.** `SessionApiRoutes` serves Consul's session API. `create` takes `Node`, which the server
requires, `Name`, `TTL` (10 s to 24 h, or none), `LockDelay` (15 s by default, capped at 60 s, a number below 1000
being seconds and any other nanoseconds, as Consul reads it) and `Behavior`, and refuses sessions bound to checks. A
session is answered in Consul's form, `LockDelay` in nanoseconds and `TTL` as Go writes a duration. `info` of an
unknown session answers an empty list. `renew` is answered by the leader, and a follower answers 503
`leader_unavailable`, so a client tries its next server. Reads take the consistency list's options, and with ACLs a
read leaves out what the token cannot read. `/v1/kv` takes `acquire` and `release`. Given together, or with `cas`,
they are refused, where Consul takes the last of them. An unknown session is refused 400 `invalid_session`, and
answers carry `LockIndex`, and `Session` while one holds the key. The ACL resource `session`, named by node, is in the
WAL as 7 and in both built-in policies. Creating, renewing and destroying need `session:write` on the session's node,
and acquiring and releasing need `key:write`. The client's local API forwards `/v1/session`, and a session created
without a node is bound to the client's own. The red run, against stubs that only compiled, failed 13 of 37 for the
missing routes, rules and built-ins. Green: `AclSessionRulesTest`, `HttpApiServerSessionTest` (6) and
`ClientSessionForwardingTest`, with three pinned tests updated: the named resources, the built-in policies and their
written form, and `?acquire`, now refused only for an unknown session. The first run of the three suites failed its
default suite on a fourth pinned test, `authorize` refusing `session` as an unknown resource, which now refuses a
resource that does not exist. Its end-to-end suite then failed only because the failed install packaged no jar, and
the run was stopped before its Docker suite. The suites ran again with Phase 4's tests, as the final run.

### Phase 4. Verification and documentation

- [x] An end-to-end test: two clients contend for a lock; the holder's session lapses and the other acquires it after
  the lock delay.
- [x] A Docker test of a lock that survives a leader change.
- [x] Design section 11, the feature validation's session rows, the Consul plan, and the lock recipe in the operations
  guide.

**Exit:** the three suites locally, audited, and the list complete and archived.

**Phase 4 record, 2026-11-08.** `SessionEndToEndTest` (e2e): three servers and two clients. Each client's session is
bound to its own node, the holder's lock refuses the other, and a renewal reaches the leader through either client.
When the holder stops renewing, the leader destroys its session at twice its TTL, the key is released with its value
kept, and the other client acquires it once the lock delay has passed, with `LockIndex` 2; the lapsed session is then
refused as `invalid_session`. Its first run expected `false` there, and the test was corrected to the refusal the
server makes. `DockerSessionTest`: on three containers, a session and its lock survive the leader's container being
stopped; the new leader holds both, renews the session, refuses another session the lock, and frees it on release.
Neither was seen red, since what they check was built, red first, in Phases 1 to 3. The design (section 11), the
feature validation's session and lock rows, the Consul plan and the operations guide's lock recipe are in line. The
final run (logs `logs/sess4/`): default 1,819 with every coverage gate met, end-to-end 20 and Docker 49, with no failures, errors or skips; audit over the 262 files the run wrote: 997 error lines, all flagged, 643 stack traces, none unflagged; no `too_many_pings`; no JVM crash file.

## 7. Out of scope

- Sessions bound to service checks; `consul lock` and `consul exec`; semaphores; prepared queries.
