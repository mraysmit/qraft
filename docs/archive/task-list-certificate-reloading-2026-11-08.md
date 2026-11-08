# Task List: Certificate Reloading

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phases 1 to 3 are done, test first, and the final local run of the three suites passed on them: default 1,918 with every coverage gate met, end-to-end 28 and Docker 51, with no failures, errors or skips; audit over the 269 files the run wrote: 911 error lines, all flagged, 624 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 for one of the two items the feature validation's security row still missed, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (the final run recorded, and the list complete and archived. Earlier the same day: phases 1 to 3 built)
**Follows:** [`task-list-client-endpoint-metric-2026-11-08.md`](task-list-client-endpoint-metric-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 15.2
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the security row
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

A server or a client takes up a renewed certificate, key or CA without a restart, as Consul does on a reload, so that
short-lived certificates can be rotated under a running cluster.

## 2. Where Qraft stands (2026-11-08)

- `TlsMaterial.load` reads `server.tls` or `client.tls` once, at start: the CA, and the certificate and key, checked
  to match. The HTTPS listener, the API gRPC port, the Raft port's mutual TLS and the client's calls to its servers
  build their contexts from it.
- Changing the files has no effect until the process restarts. Design section 15.2 lists reloading as not built.

## 3. Reference

Consul reloads its TLS files on `consul reload` or `SIGHUP`. New connections use the new material, and connections
already open keep theirs. A reload that fails leaves the old material in use.

## 4. Decisions

Taken on 2026-11-08 as the recommended options, under the owner's instruction; each can be reversed.

1. **Reloading managers.** Each context `TlsMaterial` makes uses a key manager and a trust manager that delegate to
   the material's current managers. `TlsMaterial.reload()` reads the files again, checks them as `load` does, and
   swaps both together. A reload that fails any check keeps what is in use and says why. Every listener and caller
   built from the material takes the new managers on its next handshake.
2. **Reloaded when the files change.** A server and a client check the modification times of their CA, certificate
   and key files every 30 seconds, and reload when one has changed. There is no new setting and no reload command.
3. **Logged.** A reload is logged at INFO with the certificate's subject and expiry. A failed reload is logged at WARN
   with the reason, once for each change of the files.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. Reloading material

- [x] `TlsMaterial.reload()`: a handshake after a reload sees the new certificate, a new CA is trusted and the old is
  not, and a reload of files that do not match keeps the old material.

**Exit:** the new tests seen red first; `mvn install`.

**Record (2026-11-08):** `ReloadingTrustManager` delegates each check to the current CA bundle, and
`ReloadingKeyManager` tags each alias with the generation of material that chose it and answers the chain and key from
that generation, so a reload during a handshake never pairs one generation's choice with another's key. `TlsMaterial`
builds both contexts on them, and hands the managers rather than factories to gRPC, whose `GrpcTls` now passes them to
Netty. `TlsMaterialReloadTest` keeps one listener open throughout, with a new client context for each handshake so
that no session is resumed. It was seen red 2 of 2 against a stub, then green with the 66 other TLS tests, among them
`GrpcRaftTlsTest`, `HttpApiServerTlsTest` and `ClientServerTlsContractTest`. The `mvn install` exit was taken with
Phase 3's run.

### Phase 2. Watching the files

- [x] A watcher that reloads on a change of the files' modification times, started by the server for `server.tls` and
  by the client for `client.tls`, and stopped with them; its logging.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Record (2026-11-08):** `TlsReloader` checks the files' modification times and sizes on a daemon thread,
`qraft-tls-reloader`, and logs as decision 3 says, a refused reload once until the files change again.
`QraftServerService` starts it with the material and closes it when it stops; `QraftClient` keeps its material, starts
it with the client and closes it at shutdown. Both take `useTlsReloadInterval` before they start, for tests.
`TlsReloaderTest`, `QraftServerTlsReloadTest` (HTTPS and the Raft port both present the new certificate) and
`ClientTlsReloadTest` were each seen red against stubs, then green, and the four reload tests passed five runs in a
row. The three suites ran with Phase 3.

### Phase 3. Verification and documentation

- [x] An end-to-end test that replaces a running server's certificate and sees a new connection use it, and the design,
  the feature validation and the operations guide.

**Record (2026-11-08):** `CertificateRotationEndToEndTest` starts three servers with HTTPS and mutual TLS on the Raft
port, replaces every server's certificate and key with the EC pair while they run, sees each present it to a new
connection, and commits a write afterwards; each server logged one reload. Design section 15.2 describes reloading,
the feature validation's security row records it, and the operations guide says how to renew a certificate and change
the CA.

**Exit:** the three suites locally, audited, and the list complete and archived.

**Final run, 2026-11-08.** The three suites from `C:\qx7` (logs `logs/five-final/`), which also took the earlier phases' `mvn install` exits and suites, and closed five lists together: default 1,918 with every coverage gate met, end-to-end 28 and Docker 51, with no failures, errors or skips; audit over the 269 files the run wrote: 911 error lines, all flagged, 624 stack traces, none unflagged; no `too_many_pings`; no JVM crash file. An earlier run, `logs/trio-final/`, passed the default suite (1,905) and the end-to-end suite (26) on the client library, endpoint metric and certificate reloading work, and its Docker suite failed one class on its log audit: `DockerDurableRestartTest` kills the leader, and the new leader's snapshot to it failed five times with `UNAVAILABLE: Keepalive failed. The connection is likely gone`, a form the `RAFT_SNAPSHOT_TRANSFER_INTERRUPTED` declaration did not cover. The killed container's network went without resetting the connection, so the channel learned of it by its keepalive. The declaration now covers that form, with `DockerLogCaptureTest` seen red at it first.

## 7. Out of scope

- A reload command or signal, reloading other settings, automatic issuance and revocation lists.
