# Task List: Key/Value Completion

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phases 1 to 6 are done, test first, and the final local run of the three suites passed on them: default 1,782 with every coverage gate met, end-to-end 18 and Docker 47, with no failures, errors or skips; audit over 1,293 files: 3,479 error lines, all flagged, 2,430 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 as item 3 of the feature validation's order, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (Phase 6 recorded, and the list complete and archived. Earlier the same day: Phases 1 to 5 built, test first, with their records in section 6. Earlier the same day: started)
**Follows:** [`task-list-transport-security-2026-11-08.md`](task-list-transport-security-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 10
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the key/value rows and item 3
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

Key/value entries as design section 10 has them, on Consul's KV store: scoped by tenant and namespace, with an opaque
value, flags, and creation and modification indexes; set, delete and delete-tree as Raft commands, each with
compare-and-set; deterministic prefix listing; Consul's HTTP `/v1/kv/{key}` on the server and through the client's
local API; `key` and `key_prefix` ACL rules; and a `qraft kv` command line.

## 2. Where Qraft stands (2026-11-08)

- Key/value is a flat map of strings in `QraftStateStore` (`metadata`), set and deleted by `DistributedStateCommand`
  and reached only through the gRPC `DistributedStateService`. Keys are global, with no index, flags or
  compare-and-set. Many tests use it as a replicated map.
- The ACL list left `key` rules to this list (`AclResource`).
- Snapshots of the state store are JSON records, so a new field is absent from older snapshots and restores empty.
- Raft commands are protobuf (`commands.proto`); a server refuses a command it does not know.

## 3. Reference: Consul's KV store

From Consul's KV HTTP API documentation and its `kvs_endpoint.go`:

- `GET /v1/kv/{key}` answers a JSON array of entries, each with `CreateIndex`, `ModifyIndex`, `LockIndex`, `Key`,
  `Flags`, `Value` (base64) and `Session`, or 404. `?recurse` answers every entry under the prefix, `?keys` only the
  keys, with `?separator` stopping each at the first separator after the prefix, and `?raw` the value alone.
- `PUT /v1/kv/{key}` takes the raw value as its body, `?flags` (an unsigned 64-bit number), and `?cas=<index>`: 0
  writes only if the key does not exist, another index only if it is the entry's `ModifyIndex`. It answers `true` or
  `false`. `?acquire` and `?release` take a session.
- `DELETE /v1/kv/{key}` answers `true`; `?recurse` deletes the prefix, and `?cas` deletes only at that index.
- Every answer carries `X-Consul-Index`. `CreateIndex` and `ModifyIndex` are Raft indexes.
- ACLs: `key` and `key_prefix` rules, `read`, `write`, `list` or `deny`; a read needs `key:read`, a write `key:write`, a
  recursive read filters what the token cannot read, and a recursive delete needs write on the whole prefix.
- A value is at most 512 KiB.

## 4. Decisions

Taken on 2026-11-08 as the recommended options, under the owner's instruction; each can be reversed.

1. **A new key/value state beside `metadata`.** `metadata` and the gRPC `DistributedStateService` stay as they are, a
   replicated map that tests and the gRPC API use; the KV store is new state (`state.kv`), with its own command and a
   `kv` field in the snapshot. Folding the two together would change the gRPC API's meaning, and is left for later.
2. **Indexes are Raft indexes,** as in Consul. The state machine applies entries in order and is told each applied
   index after it applies the entry (`RaftNode`), so the entry being applied is at the last applied index plus one.
   A test pins that, since the ACL list counted bootstraps instead.
3. **Scope by header.** A key is scoped by `X-Qraft-Tenant` and `X-Qraft-Namespace`, `default` when absent, as the
   catalog's routes are (design section 12). Consul's enterprise `?ns=` is not taken.
4. **Answers use Consul's field names** (`Key`, `Value`, `Flags`, `CreateIndex`, `ModifyIndex`), so that Consul's KV
   clients read them; the index header is `X-Qraft-Index`, the KV state's last index. A difference from Qraft's own
   camel-case schema, chosen for compatibility, which design section 12 allows for clients.
5. **No `list` access.** `key_prefix` rules take `read`, `write` or `deny`; reading a prefix filters out what the token
   cannot read. Consul's `list` access, which lets a token list keys it cannot read, is left out. A difference from
   Consul.
6. **Sessions later.** `LockIndex`, `Session`, `?acquire` and `?release` belong to the sessions and locks list; until
   then `LockIndex` is 0 and `Session` absent, and `?acquire` and `?release` are refused as unknown.
7. **Unknown query parameters are refused** with 400 `invalid_query`, as the discovery reads do (design section 12).
   `?stale` and `?consistent` are refused until the consistency list.
8. **Writes go to the leader.** A follower answers a write as a catalog write does, so a client tries its next
   server.
9. **The client retries only a write that cannot have been applied:** one that could not connect, or that a server
   answered as busy, without a leader, or draining. Any other answer, an unknown outcome among them, is passed on, so
   that no write is applied twice. A read moves past any failed server, as the forwarded discovery reads do.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. Replicated key/value state

- [x] `state.kv.KvState`: entries by tenant, namespace and key; set, compare-and-set, delete, compare-and-delete and
  delete-tree; creation and modification indexes; the state's last index; deterministic listing by prefix, and keys
  up to a separator.
- [x] `KvCommand` in `commands.proto` and the codec, round-tripped.
- [x] `QraftStateStore` applies it at the entry's Raft index, and its snapshot carries the entries; a snapshot from
  before restores with none.
- [x] A three-server test: what one server writes, every server reads, at the same indexes.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 1 record, 2026-11-08.** `KvStateTest` (9) and `QraftStateStoreKvTest` (5), seen red against stubs, then green;
`KvReplicationTest` (2), which checks decision 2 on a real `RaftNode`, three servers and a durable server recovering
from its snapshot and log, passed at its first run, since what it checks was built by then. Three points were made
red on their own: a compare-and-delete of an absent key succeeds whatever its index, as Consul's `kvsDeleteCASTxn`
answers; `reset()` empties the key/value state; and the WAL's field numbers are pinned. The state is under a
read-write lock, so a tree delete is seen whole.

### Phase 2. `key` ACL rules

- [x] `key` and `key_prefix` in `AclResource` and `AclRulesJson`, scoped by tenant and namespace as `service` is.
- [x] The built-in management and read-only policies cover keys.
- [x] Tests on the authorizer's precedence for keys.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 2 record, 2026-11-08.** `AclKeyRulesTest` and a policy with key rules in `AclCommandCodecTest`, seen red, then
green with every ACL test, 99. `KEY` is a resource named and scoped as `service` is, number 6 in the WAL. Four tests that
pinned the old resource set, the built-in policies, and `key` as refused or unknown were brought in line; the authorize
route now answers for `key`, and `session` is what stays unknown.

### Phase 3. `/v1/kv` on the server

- [x] `GET` with `?recurse`, `?keys`, `?separator` and `?raw`; `PUT` with `?flags` and `?cas`; `DELETE` with
  `?recurse` and `?cas`; `X-Qraft-Index`; the scope headers; unknown parameters refused.
- [x] ACLs: read, write, a filtered recursive read, and write on the whole prefix for a recursive delete.
- [x] A follower's answer to a write.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Phase 3 record, 2026-11-08.** `HttpApiServerKvTest` (7), seen red with no route, then green with every HTTP API
test, 179. A recursive delete is decided by `AclAuthorizer.decideWritePrefix`, on Consul's `KeyWritePrefix`: refused
when any rule under the prefix grants less than write, otherwise decided as a write on the prefix itself; it rests on
the rules, so a key created under the prefix later is covered. Its test was seen red against a stub that decided a
plain write. The three suites ran locally after this phase (logs `logs/local-run-2026-11-08-kv-phase3/`).

### Phase 4. Through the client

- [x] The client's local API forwards `/v1/kv` to its servers under the caller's token, as its discovery reads are.
- [x] Tests against a real server.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Phase 4 record, 2026-11-08.** `ClientKvForwardingTest` (2), seen red with no local route, then green with the client
tests, 85. `HttpCatalogClient.forward` keeps the answer's bytes and the index, leader and filter headers. Decision 9's
retry rule was written with the forwarding code, and its test, `HttpCatalogClientForwardTest` (2), after it, so that
test was not seen red.

### Phase 5. `qraft kv`

- [x] `qraft kv get`, `put` and `delete`, with `--http-addr`, the token and TLS options, `--tenant` and
  `--namespace`, on Consul's `consul kv`; a listing is `get --recurse` or `get --keys`, as in Consul.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 5 record, 2026-11-08.** `KvCommandLineTest` (6), seen red against a stub, then green with the other command
tests, 24. Consul's messages, its `@file` values, and exit codes 0, 1 and 2.

### Phase 6. Verification and documentation

- [x] An end-to-end test: a value written through one client is read through another, with compare-and-set.
- [x] A Docker test on a three-server cluster.
- [x] Design section 10, the feature validation's key/value rows, the Consul plan and the operations guide.

**Exit:** the three suites locally, audited, and the list complete and archived.

**Phase 6 record, 2026-11-08.** `KvEndToEndTest` (e2e): three servers in one JVM and two clients; what one writes
through its local API the other reads, of two compare-and-set writes from one index exactly one wins, and a recursive
delete through one client removes the prefix for both. `DockerKvTest`: on three containers, a compare-and-set write
through the leader is read through every server at the same indexes, and cas 0 on an existing key is refused; 14.7 s
against Docker Desktop. A restart was left out of it, since a restarted container may come back on another host
port; `KvReplicationTest` covers recovery from a server's own data. Neither was seen red, since what they check was
built, red first, in Phases 1 to 4. The design (section 10), the feature validation's key/value rows, the Consul plan
and the operations guide are in line. The final local run (logs `logs/local-run-2026-11-08-kv-final/`): default 1,782 with every coverage gate met, end-to-end 18 and Docker 47, with no failures, errors or skips; audit over 1,293 files: 3,479 error lines, all flagged, 2,430 stack traces, none unflagged; no `too_many_pings`; no JVM crash file.
The Phase 3 run passed default 1,772, end-to-end and Docker 46, with a clean audit over 1,080 files.

## 7. Out of scope

- Sessions, locks, `?acquire` and `?release` (the sessions list); blocking queries and consistency modes (the
  consistency list); transactions (`/v1/txn`); watches.
