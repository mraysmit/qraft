# Task List: Client Library for Key/Value, Sessions and Leader Election

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phases 1 to 4 are done, test first, and the final local run of the three suites passed on them: default 1,918 with every coverage gate met, end-to-end 28 and Docker 51, with no failures, errors or skips; audit over the 269 files the run wrote: 911 error lines, all flagged, 624 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 for two rows of the feature validation that remained open, the client libraries and the locks row's leader-election helper, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (the final run recorded, and the list complete and archived. Earlier the same day: phases 1 to 4 built)
**Follows:** [`task-list-event-query-and-watch-2026-11-08.md`](task-list-event-query-and-watch-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), sections 10 and 11
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the locks row and
the client libraries row
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

A Java library an application uses to read and write keys, hold sessions and elect a leader through its local client
or a server, so that it need not speak the HTTP API itself. Section 11 of the design says leader election is library
behaviour built on sessions and locks.

## 2. Where Qraft stands (2026-11-08)

- The servers and each client's local API serve Consul's `/v1/kv` and `/v1/session`, with blocking reads.
- `qraft kv` reads and writes keys from the command line. There is no Java API for applications.
- Leader election is a recipe in the operations guide, with no helper (sessions list, decision 6).

## 3. Reference

Consul's Go `api` package: `KV()` with `Get`, `List`, `Keys`, `Put`, `CAS`, `Acquire`, `Release`, `Delete` and
`DeleteTree`, each with query options carrying `WaitIndex` and `WaitTime`; `Session()` with `Create`, `Renew`,
`RenewPeriodic` and `Destroy`; and `LockKey`/`Lock`, which create a session, acquire a key, renew the session and
report a lost lock on a channel.

## 4. Decisions

Taken on 2026-11-08 as the recommended options, under the owner's instruction; each can be reversed.

1. **The library is `dev.mars.qraft.client.api`,** in the client layer, built on the JDK's `HttpClient` and Jackson.
   `QraftApi` is built with an address (the local API by default), and optionally a token, sent as `X-Qraft-Token`,
   and a tenant and namespace, sent as Qraft's scope headers.
2. **The calls are synchronous,** for virtual threads, and a refusal throws `QraftApiException` with the status, the
   error code and the message the server gave.
3. **Key/value** (`KeyValue`): `get`, `list`, `keys`, `put` with flags, `cas`, `acquire`, `release`, `delete` and
   `deleteTree`; values are bytes. A read takes `QueryOptions` (the index to wait past, the wait, and the
   consistency) and answers its entries with the `X-Qraft-Index` it came with.
4. **Sessions** (`Sessions`): `create` with a name, a TTL, a lock delay, a behaviour and, for a server, a node;
   `renew`, which answers whether the session still exists; `destroy`; and `info`.
5. **Leader election** (`LeaderElection`): one contender for one key. It creates a session with the TTL, tries to
   acquire the key with its value, and while it does not hold it waits on blocking reads of the key until it is free.
   While it leads it renews the session every half TTL; a renewal that finds the session gone, or a read that finds
   another session holding the key, ends its leadership, and it campaigns again with a new session. A listener is
   told when leadership is gained and lost, one call at a time. `close()` releases the key and destroys the session.
6. **No `qraft lock` command** (sessions list, decision 6, stands): the library is the helper.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. The API and key/value

- [x] `QraftApi` and `KeyValue` against a server: each call, the flags, compare-and-set, a blocking read woken by a
  write, the scope headers and the token, and a refusal as `QraftApiException`.

**Exit:** the new tests seen red first; `mvn install`.

**Record (2026-11-08):** `QraftApi` sends each call with its token and scope headers, and a blocking read with its
index and wait, allowing for the wait and a sixteenth in its timeout; `KeyValue` encodes each segment of a key's path,
reads 404 `key_not_found` as nothing, and reads a refusal's code and message from the server's error. The default
address is the local API's default port, 8080, not Consul's 8500. `QraftApiKeyValueTest` (four tests, with a stub
server for the token) was seen red 4 of 4 against stubs, then green with `PackageDependencyTest`. The `mvn install`
exit was taken with Phase 3's run of the three suites.

### Phase 2. Sessions

- [x] `Sessions`: create with its options, renew, destroy and info, and a renewal of a session that is gone.

**Exit:** the new tests seen red first; `mvn install`.

**Record (2026-11-08):** `Sessions` with `SessionOptions` and `SessionInfo`, which reads the TTL as Go writes a
duration and the lock delay in nanoseconds. `QraftApiSessionsTest` was seen red 2 of 2 against stubs. It then failed on
the server: a `TTL` of `10000ms` was refused, because `AclApiRoutes`'s duration pattern tried `m` before `ms` and read
`1500ms` as minutes and a stray `s`, so no millisecond duration was ever accepted for a session's `TTL` or
`LockDelay` or a token's `expirationTtl`. `AclApiRoutesDurationTest` was seen red at `1500ms`; trying `ms` first made
it, the sessions test, `HttpApiServerSessionTest` and `HttpApiServerAclApiTest` green, 31 tests.

### Phase 3. Leader election

- [x] `LeaderElection`: one of two contenders leads; the other takes over when the leader closes, and when its
  session is destroyed; the listener's calls; close.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Record (2026-11-08):** `LeaderElection` campaigns on a virtual thread of its own as decision 5 says. A contender
whose session ends while it campaigns, which the server answers 400 `invalid_session`, drops it and creates another.
A failed call is logged at WARN with its message and retried after `retryWait`. `LeaderElectionTest` was seen red 2 of
2 against a stub, the listener hearing nothing, then green, and the three library tests passed five runs in a row.
The three suites ran with Phase 4.

### Phase 4. Verification and documentation

- [x] An end-to-end test of two applications electing a leader through their own clients' local APIs, and the
  design, the feature validation and the operations guide.

**Record (2026-11-08):** `LeaderElectionEndToEndTest` starts three servers and two clients. Beside each client an
application elects through that client's local API with no node given, so the client binds each session to its own
node; the first leads, the other reads the key through its own client, and takes over when the first closes. The
design's section 11 describes the library; the feature validation's locks and client libraries rows are done; the
operations guide shows the library beside the recipe and notes millisecond durations.

Repeating the end-to-end test found a defect: once in five runs the first application gave up its leadership straight
after acquiring the key. Its first check of the key was a default read, which its client answered from a server that
had not yet applied the acquisition, so the key looked free. `LeaderElectionStaleReadTest` stands a stub API whose
default reads are behind its consistent ones, and was seen red with `lost`. A leader now checks its hold with
consistent reads past the index, and the end-to-end test then passed eight runs in a row. It also reads consistently
itself, as its own checks through the two clients raced the same way.

**Exit:** the three suites locally, audited, and the list complete and archived.

**Final run, 2026-11-08.** The three suites from `C:\qx7` (logs `logs/five-final/`), which also took the earlier phases' `mvn install` exits and suites, and closed five lists together: default 1,918 with every coverage gate met, end-to-end 28 and Docker 51, with no failures, errors or skips; audit over the 269 files the run wrote: 911 error lines, all flagged, 624 stack traces, none unflagged; no `too_many_pings`; no JVM crash file. An earlier run, `logs/trio-final/`, passed the default suite (1,905) and the end-to-end suite (26) on the client library, endpoint metric and certificate reloading work, and its Docker suite failed one class on its log audit: `DockerDurableRestartTest` kills the leader, and the new leader's snapshot to it failed five times with `UNAVAILABLE: Keepalive failed. The connection is likely gone`, a form the `RAFT_SNAPSHOT_TRANSFER_INTERRUPTED` declaration did not cover. The killed container's network went without resetting the connection, so the channel learned of it by its keepalive. The declaration now covers that form, with `DockerLogCaptureTest` seen red at it first.

## 7. Out of scope

- A `qraft lock` command, libraries in other languages, and a catalog or health API in the library.
