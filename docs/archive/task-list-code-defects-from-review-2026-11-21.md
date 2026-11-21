# Task List: Code Defects Found by the Documentation Review

**Date:** 2026-11-21
**Status:** Complete and archived on 2026-11-21. At the owner's word, "address the issues you've found in the code
following a strict TDD approach. Do not run any tests other than the tests that are being impacted. Then we focus on
cleaning up the documentation".
**Last updated:** 2026-11-21 (written; items 1 to 18 done; archived)
**Active work:** complete on 2026-11-21
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

## 1. Rules

- Strict test-first: each fix starts with a test seen to fail for the right reason. A mechanical rename has no red step
  and is proved by a search.
- Only the tests a change affects are run: the classes that cover the changed code, named with `-Dtest`. No suite
  run.
- The documents are corrected afterwards, in the documentation phase. Done on 2026-11-21: the API and configuration
  references, the runbook, the design, the event architecture, the interface design, the Docker README, the test
  standards and the handover describe the fixed code.

## 2. Decisions

1. **`outcome_unknown` is not retryable.** The design's three outcomes of a write (section 9.1): a definite refusal,
   a retryable refusal known not to have been applied, and `outcome_unknown`, which may have been applied. The server
   marked `outcome_unknown` `retryable: true`. It answers `retryable: false`; a caller that replays an idempotent
   request (the client's registrations, heartbeats and observations) decides by the code, as the design says.

## 3. Items

| # | Defect | Test first |
|---|---|---|
| 1 | The server marks `outcome_unknown` retryable; the client's own writes must still retry it | server route tests; `HttpCatalogClientTest` |
| 2 | A write the client forwards and sends, unanswered, is answered `no_server_reachable`, retryable | `LocalKvRoutes` tests |
| 3 | The interface offers "Submit again" for `outcome_unknown`, and treats any unclassified refusal as retryable | `Mutation` Vitest |
| 4 | The auth method, binding rule, login and logout routes answer with ACLs disabled | `AuthApiRoutes` tests |
| 5 | The unencrypted-token warning ignores the Raft listener, which carries forwarded tokens | `AppConfig` security warning test |
| 6 | `server.http.host` binds nothing | `HttpApiServer` bind test |
| 7 | A stopping server never drains | `QraftServerService` shutdown test |
| 8 | Read-only POST routes (`/v1/internal/acl/resolve`, `/authorize`, `/v1/acl/explain`) are forwarded to the leader | forwarding test |
| 9 | Deleting a tenant or namespace emits no events for what it removes | `QraftStateStore` event tests |
| 10 | The Docker start-script check rebuilds the jar during the Docker suite (`node_modules` counted as a source) | `DockerDeploymentContractTest` |
| 11 | `GET /v1/acl/binding-rules` reads `authmethod` only as the first parameter | `AuthApiRoutes` tests |
| 12 | The event watch accepts and ignores `limit`; an empty `type=` matches nothing | `EventRoutes` tests |
| 13 | `/raft/status` and `/v1/operator/raft/configuration` accept any query | `HttpApiServer` tests |
| 14 | The client's local API matches `Bearer` exactly, the server in any case | `ClientAuthorizer` tests |
| 15 | Configuration checks missing: `logHardLimit`, heartbeat below election timeout, Prometheus and DNS ports in the clash check, shutdown timeouts, `server.id` in `server.raft.nodes`; usage line `qraft-server` | `AppConfig` and `ConfigFileResolver` tests |
| 16 | Two tests stream a live log capture (`QraftClientTest`, `RegistrationClientTest`) | the two classes |
| 17 | Test helpers without the `Helper`/`Fixture` suffix (`TestPorts`, `LogCaptures`, `DockerImageBuild`) | rename, proved by search |
| 18 | Stale comments: citations of design section 12.4.1, `pom.xml`'s `-Dgroups` and PIT table notes, `RaftOperatorCommand` and `AclCommandLine` Javadoc, misplaced Javadoc in `HttpApiServer`, the orphaned drain Javadoc in `QraftServerService` | comments only, proved by search |

For the owner, not fixed here:

- `DistributedStateService` (gRPC) does not forward, lists in no fixed order and is not audited: whether it stays or is
  retired is a design question.
- Every file in `docker/compose/` shares the Compose project `compose`; naming the projects changes the volume names
  of hand-run clusters, whose data would then not be found.
- A management token file is read once, at startup; rotation needs every server restarted. A document fix unless the
  owner wants the file read again on leadership.

## 4. Record

Each item's red run and green run are kept in `logs/defects-<n>-red-2026-11-21.log` and `-green-`.

1. **`outcome_unknown` not retryable.** The server answers it `retryable: false` in its four places
   (`HttpApiServer`); the client's own writes still replay it, by the code (`HttpCatalogClient.classifyError`). Red:
   `HttpApiServerTest`, `QraftServerWriteForwardingTest` (twice), `HttpCatalogClientTest`. Green with the client
   catalog, health publisher and reconciler tests.
2. **A forwarded write sent and never answered** is answered 503 `outcome_unknown`, not retryable, in the server's
   envelope, and no other server is asked (`HttpCatalogClient.unknownOutcome`). Red: `HttpCatalogClientForwardTest`
   met no answer at all. Green with the client library's key/value and the local route tests.
3. **The interface** reads `outcome_unknown` as unknown whatever its flag, and an unclassified refusal as not
   retryable: unknown from the server's side (5xx), a refusal otherwise (`Mutation.tsx`). Red: two Vitest cases; green
   with the eight test files that assert a write's outcome, 105 tests.
4. **The auth method, binding rule, login and logout routes** answer 401 `acl_disabled` with ACLs disabled
   (`AuthApiRoutes`). Red: the route created a method. The three tests of `HttpApiServerAuthMethodApiTest` and both of
   `HttpApiServerKubernetesLoginTest` that ran with ACLs disabled now run with ACLs enabled and an `acl:write` token.
   Green with the login and ACL route tests and the two end-to-end auth tests.
5. **The unencrypted-token warning** names every listener without TLS, the Raft listener included
   (`AppConfig.logSecurityWarning`). Red: `AppConfigTlsTest`.
6. **`server.http.host`** is where the HTTP listener binds; `0.0.0.0` or none still binds every interface, IPv4 and
   IPv6 (`HttpApiServer`, `QraftServerService`). Red: `QraftServerHttpHostTest` met all interfaces.
7. **A stopping server drains:** a DRAIN hook puts the HTTP API into drain mode (`QraftServerService`), whose Javadoc
   now describes each phase. Red: `QraftServerDrainTest`.
8. **The read-only POST routes** (`/v1/internal/acl/resolve`, `/authorize`, `/v1/acl/explain`) are answered by the
   server asked, never forwarded (`HttpApiServer.READ_ONLY_POSTS`). Red: a follower forwarded all three.
9. **Removals emit their events:** a scope's keys (`key.tree_deleted`), a tenant's namespaces (`namespace.deleted`),
   and a session's freed keys (`key.deleted` or `lock.released`) (`QraftStateStore`, `KvState`). Red: two event tests.
   Found on the way, with its own red test (`KvStateTest`): `KvState.releaseSession` walked the `TreeMap`'s own
   entries while it removed keys, and a `TreeMap` moves the next entry into a node it removes, so a delete session's
   lock delay fell on the wrong key and a key was reported twice; it copies the entries first.
   `AdminUiDataContractTest`, missed in the first green run and found by item 15's, had pinned a destroyed session's
   events without the release: it now expects `lock.released` before `session.destroyed`. Green with every class
   that reads those events or takes the changed paths: 109 tests, 29 classes.
10. **The Docker start scripts' freshness check** leaves out `src/main/ui/node_modules`, which every `mvn test`
    reinstalls, as the Docker fixture's check does (`docker/build-runtime.sh` and `.ps1`), so a Docker stage no longer
    packages the jar again under the Maven run testing it. Red: `DockerDeploymentContractTest`.
11. **`GET /v1/acl/binding-rules`** takes `authmethod` anywhere in the query and refuses any other parameter, 400
    `invalid_query` (`AuthApiRoutes`, with `AclApiRoutes.query`). Red: `?stale&authmethod=x` listed every rule.
12. **The event watch** refuses `limit`, which only pages a query, and both routes refuse an empty `type=`
    (`EventRoutes.request`). Red: `HttpApiServerEventWatchTest`, `HttpApiServerEventQueryTest`, each answered 200.
13. **`/raft/status` and `/v1/operator/raft/configuration`** refuse a parameter they do not take, 400
    `invalid_query`, and still take the read options, as Consul's `?stale` (`HttpApiServer.refusedForItsQuery`). Red:
    `?verbose` answered 200 on both.
14. **The client's local API** reads the `Bearer` scheme in any case, as the server's API does (RFC 9110, section
    11.1) (`ClientAuthorizer.presented`). Red: `LocalApiAclTest`, `bearer` acted as the default token, 403.
15. **Configuration checks** (`AppConfig.validate`): the heartbeat interval below the election timeout, as
    hashicorp/raft's `ValidateConfig` demands of its timeouts; `logHardLimit` positive; the shutdown timeouts zero or
    more; the Prometheus port, while telemetry is on, and the DNS port, which serves TCP too, in the clash check, whose
    refusal now names both settings, as Consul's configuration builder does; and a server listed under its own name in
    a non-empty `server.raft.nodes`. The resolver's usage line reads `qraft server`. Red: six tests in four classes.
    `raftTimingIntervalsArePositive` took 1 ms for both timings; it takes an election timeout of 2. Every server
    configuration in the repository (26) meets the new checks. Green with every class that builds a server
    configuration: 137 configuration tests, 65 server tests, 12 end-to-end tests and `AdminUiDataContractTest`.
16. **Log captures are read through the snapshot** (`LogCapturesHelper.snapshot`), which copies the list under the
    appender's lock. The review named two classes; the same live read was in eighteen, all changed. The three captures
    that replace their list with a `CopyOnWriteArrayList` are safe and kept. Proved by search: no read of a live
    `ListAppender.list` remains. Green: the eighteen classes and the helper's own test, 328 tests.
17. **Test helpers take the `Helper` suffix:** `TestPortsHelper`, `LogCapturesHelper` and `DockerImageBuildHelper`,
    with their tests, and the current documents that name them. Proved by search: the old names remain only in this
    list and the dated archives. All test code compiles from clean; the three helper tests pass.
18. **Comments:** `pom.xml` names both selections (`-Dgroups=e2e`, `-Dgroups=docker,slow`) and says PIT is shelved;
    the interface's citations name design section 13.2 (`pom.xml`, `AdminUiHandler`, `bootstrap.ts`);
    `RaftOperatorCommand`'s `remove-peer` says which token each mode needs; `AclCommandLine` says any server will do;
    the membership and bootstrap Javadoc in `HttpApiServer` sit on `raftMembers` and `bootstrapAcl`. Lines this
    session's edits pushed past 120 characters are rewrapped. Proved by search and by a compile from clean.

Every run's log was read. Each stack trace and error line carries its flag; the warnings are those the tests cause
on purpose, such as `EventJournalTest`'s failing consumer.

Found on the way, for the owner: `docker/config/agent-acceptance/` is an empty directory on this machine, untracked,
left from before the client rename; nothing uses it.
