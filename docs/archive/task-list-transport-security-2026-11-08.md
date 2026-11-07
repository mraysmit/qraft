# Task List: Transport Security

**Date:** 2026-11-07
**Status:** Complete and archived on 2026-11-08. Phases 1 to 6 are done, each test first, and the final local run of the three suites passed on them: default 1,743 with every coverage gate met, end-to-end 17 and Docker 46, with no failures, errors or skips; audit over 862 files: 2,414 error lines, all flagged, 1,678 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-07 as the ACL list's proposal 14, under the owner's instruction of that day to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (Phase 6 recorded, the list complete and archived, and renamed to the date of its last change. Earlier, 2026-11-07: Phases 1 to 5 built, test first, with their records in section 6, and decision 9 added. Earlier the same day: started)
**Follows:** [`task-list-acl-and-tokens-2026-11-07.md`](task-list-acl-and-tokens-2026-11-07.md), proposal 14, and its decision of 2026-10-09 that the server's HTTP listener requires a client certificate
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), sections 15 and 22
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

Every network connection that carries a token, a registration or Raft traffic is encrypted and authenticated:

- the server's HTTP listener serves HTTPS and, by default, requires a client certificate signed by the cluster's CA;
- the server's API gRPC port serves TLS, with the same certificate rule;
- the Raft port uses mutual TLS, and a server checks that a peer's certificate names the address it dialled;
- a client calls the servers over HTTPS and presents its certificate;
- the `qraft acl` and `qraft operator raft` command lines can call an HTTPS server with a CA and a client
  certificate.

Without TLS configured everything works as it does now, and a server with ACLs enabled keeps its startup warning that
tokens cross the network unencrypted.

## 2. Where Qraft stands (2026-11-07)

- No listener uses TLS. The server listens on HTTP (`server.http`), API gRPC (`server.apiGrpcPort`) and Raft gRPC
  (`server.raft.port`); the client's local API listens on HTTP, on loopback by default.
- The client already accepts `https` URLs in `servers.urls`, and sends every server call through one `HttpClient`.
- The Raft port is trusted: any process that reaches it can speak Raft (ACL list, section 2).
- `grpc-netty` is on the classpath, and the JDK reads PEM certificates, so no new dependency is needed.

## 3. Reference: how Consul handles this

From Consul's documentation of the `tls` stanza and its agent:

- `tls.defaults` holds `ca_file`, `cert_file`, `key_file`, `tls_min_version` (TLS 1.2 by default), `verify_incoming`
  and `verify_outgoing`; `tls.https`, `tls.grpc` and `tls.internal_rpc` override them per interface.
- `verify_incoming` requires and checks a client certificate; `verify_outgoing` checks the server's certificate on
  outgoing connections; `internal_rpc.verify_server_hostname` also checks that a server's certificate names a server.
- HTTPS has its own port (`ports.https`); HTTP can be turned off with `ports.http = -1`.
- Server-to-server RPC uses mutual TLS when `verify_incoming` and `verify_outgoing` are on.
- The command line takes `-ca-file`, `-client-cert`, `-client-key`, or `CONSUL_CACERT`, `CONSUL_CLIENT_CERT`,
  `CONSUL_CLIENT_KEY`.
- Keys and certificates are PEM files.

## 4. Decisions

Taken on 2026-11-07 as the recommended options, under the owner's instruction; each can be reversed.

1. **Settings follow Consul's `tls` stanza, in Qraft's JSON style.** `server.tls` holds `caFile`, `certFile`, `keyFile`
   and `minVersion` (`TLSv1.2` by default, or `TLSv1.3`), with `https` (`enabled`, `verifyIncoming`), `grpc`
   (`enabled`, `verifyIncoming`) and `raft` (`enabled`, `verifyServerHostname`). `client.tls` holds `caFile`, `certFile`,
   `keyFile` and `minVersion`. Files are PEM; the key is PKCS #8, unencrypted, RSA or EC.
2. **One HTTP listener, either HTTP or HTTPS.** With `server.tls.https.enabled` the listener on `server.http.port`
   serves HTTPS only. Consul can serve both on two ports; serving both would let a token cross in the clear on the
   other port, which is what this list removes. A difference from Consul.
3. **`verifyIncoming` defaults to `true` for HTTPS and API gRPC,** carrying out the ACL list's decision of 2026-10-09;
   Consul defaults it to `false`. A difference from Consul.
4. **The Raft port is always mutual when enabled,** and `verifyServerHostname` defaults to `true`: a server checks that
   a peer's certificate names the host of the address in `server.raft.nodes` it dialled, by standard hostname
   verification. Consul checks for `server.<datacenter>.<domain>` instead; Qraft has no such domain.
5. **The client's local API stays HTTP on loopback,** as Consul's client agent does by default. HTTPS on it is left for
   later.
6. **A server's health endpoints are on the same listener,** so with a required client certificate a probe needs one.
   Docker health checks in the TLS compose files present the test certificate.
7. **No certificate reloading.** Consul reloads on SIGHUP; Qraft reads its files at start. Left for later.
8. **Test certificates are checked in,** generated by a script under `src/test/resources/tls/` with OpenSSL, valid for
   100 years, so no test depends on the date. They are for tests only and say so.
9. **The command lines read no environment variable.** Consul's also read `CONSUL_CACERT`, `CONSUL_CLIENT_CERT` and
   `CONSUL_CLIENT_KEY`; Qraft reads nothing from the environment anywhere (the `qraft operator raft` command's own
   documentation), so its command lines take the three options alone. A difference from Consul, found while Phase 4
   was built, which first read the variables.
10. **`server.operator.token` stays.** TLS authenticates the machine that calls, and does not authorize it: with
    ACLs disabled, the operator token is still the only thing that decides who may remove a server, and with ACLs
    enabled it is refused and `operator:write` decides. Taken as the recommended option under the owner's
    instruction; the owner can reverse it.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).
- No file or log ever holds a private key's content.

## 6. Phases

### Phase 1. TLS material and settings

- [x] `common.tls.TlsSettings`: parse a `tls` object (`caFile`, `certFile`, `keyFile`, `minVersion`), refuse an
  unknown key, a missing file, a key without a certificate, and an unknown version.
- [x] `common.tls.TlsMaterial`: read a PEM CA bundle, a certificate chain and a PKCS #8 RSA or EC key; build an
  `SSLContext` for a server, with or without a required client certificate, and for a client.
- [x] Refusals name the file and the reason, never the key's content.
- [x] Test certificates and the script that makes them.

**Exit:** `mvn install` passes with the new tests seen red first.

**Phase 1 record, 2026-11-07.** `common.tls.TlsSettings` and `TlsMaterial`, test first: `TlsSettingsTest` (8) and
`TlsMaterialTest` (13), seen red against stubs, then green. The material is read with the JDK alone: the PEM CA bundle
and certificate chain by `CertificateFactory`, the PKCS #8 key by `KeyFactory`, RSA or EC; a key in another format is
refused with its format named, a key that is not its certificate's is refused, and no refusal holds a key's content.
The material tests are real handshakes on loopback. The certificates and `generate.sh` are in
`src/test/resources/tls/`; the script sets `MSYS_NO_PATHCONV` and a relative scratch directory so that it runs in Git
Bash on Windows as well.

### Phase 2. HTTPS on the server's listener and TLS on its API gRPC port

- [x] `server.tls` in `AppConfig`, refused when incomplete.
- [x] `HttpApiServer` serves HTTPS with `server.tls.https.enabled`, and requires a client certificate unless
  `verifyIncoming` is `false`.
- [x] `GrpcServiceServer` serves TLS with `server.tls.grpc.enabled`, under the same rule.
- [x] The warning that tokens cross the network unencrypted is logged only for a listener without TLS.
- [x] Tests: a client with the CA and a certificate is answered; one with no certificate, a certificate from another
  CA, or plain HTTP is refused.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Phase 2 record, 2026-11-07.** `AppConfigTlsTest` (7), `HttpApiServerTlsTest` (5), `GrpcServiceServerTlsTest` (5)
and `QraftServerTlsStartupTest`, each seen red first. `HttpApiServer` serves `HttpsServer` with the material's context
and parameters; `GrpcServiceServer` a Netty server with `raft.GrpcTls.serverContext`, on the JDK's TLS. The server reads
the material once at start. The warning names the listeners without TLS, or is not logged when both have it. The
three suites ran locally on Phase 2 (logs `logs/local-run-2026-11-07-tls-phase2/`).

### Phase 3. The client calls the servers over HTTPS

- [x] `client.tls` in `ClientConfiguration`; an `https` server URL uses it.
- [x] The client presents its certificate and checks the server's against the CA.
- [x] A client whose certificate is refused logs one warning naming the server and the reason, and is not ready.
- [x] Tests against a server with a required client certificate.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Phase 3 record, 2026-11-07.** `ClientTlsConfigurationTest` (3) and `ClientServerTlsContractTest` (3), seen red,
then green with the client and catalog tests, 85. A failed handshake is noticed where every call's transport failure
is classified (`HttpCatalogClient.transportCode`), and logged once per server and reason, since retrying cannot mend
it; the tests declare it as `CLIENT_TLS_REFUSED`, exactly once.

### Phase 4. The command lines

- [x] `qraft acl` and `qraft operator raft` take `--ca-file`, `--client-cert` and `--client-key` (decision 9: no
  environment variables).
- [x] Tests against an HTTPS server.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 4 record, 2026-11-07.** `CommandLineTlsTest` (5), seen red, then green with the commands' own tests, 20. The
options build the command's `HttpClient` (`runtime.CommandLineTls`); a certificate without its key, or without a CA,
is refused as misuse, exit 2. The environment variables first built were removed, test and code, under decision 9.

### Phase 5. Mutual TLS on the Raft port

- [x] `GrpcRaftServer` requires a client certificate signed by the CA with `server.tls.raft.enabled`.
- [x] `GrpcRaftTransport` presents the server's certificate and checks the peer's, with hostname verification unless
  `verifyServerHostname` is `false`.
- [x] Tests: a three-server cluster elects a leader and replicates over mutual TLS; a peer without a certificate, or
  with one from another CA, cannot take part.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Phase 5 record, 2026-11-07.** `GrpcRaftTlsTest` (5): three servers elect and replicate over mutual TLS; a server
with another CA's certificate takes no part; one without TLS cannot reach servers that require it; one whose
certificate names another host is refused while hosts are checked, and takes part when the check is off. The three
refusals were seen red. gRPC itself checks the host dialled; with the check off, a trust manager that validates the
chain alone answers for the engine (`GrpcTls.peerContext`). `QraftServerTlsStartupTest` gained the Raft port, seen
red with the server's wiring taken out, then green. Two findings: the refusals log the unreachable-peer errors a
partition does, now declared for node3; and a TLS dial to a plaintext gRPC port makes that port print a stack trace
through `java.util.logging`, which the tests do not bridge, so the plaintext case now has the plaintext server
campaign, and no TLS server dials it.

### Phase 6. Verification and documentation

- [x] An end-to-end test: a TLS cluster with ACLs, a client registering and discovering over HTTPS.
- [x] A Docker test on a three-server TLS compose file.
- [x] The design's sections 15 and 22, the operations guide and the feature validation.
- [x] `server.operator.token`, kept until this list: decided again with TLS in place, and kept (decision 10).

**Exit:** the three suites locally, audited, and the list complete and archived.

**Phase 6 record, 2026-11-08.** `TlsEndToEndTest` (e2e): three servers in one JVM serving only TLS, with ACLs under
`deny`, elect over mutual TLS and install the management token; one client registers a service over HTTPS and
another discovers it; plain HTTP reaches no server. Its first run failed for the test's own fault: the second client
listed one server, a follower, and a client finds the leader by trying its servers in turn, so each now lists all
three. `DockerTlsTest`: three containers serving only TLS, with health checks that present the test client
certificate, agree on one leader, which only heartbeats over mutual TLS can bring about; each answers a caller with a
certificate and refuses one without and plain HTTP; 17.7 s against Docker Desktop. Neither was seen red, since every
behaviour they check was built, red first, in Phases 2 to 5. The design (sections 15.2, 16 and 22), the operations
guide, the feature validation, the Consul plan and the Docker README are in line. The final local run of the three
suites (logs `logs/local-run-2026-11-07-tls-final/`): default 1,743 with every coverage gate met, end-to-end 17 and Docker 46, with no failures, errors or skips; audit over 862 files: 2,414 error lines, all flagged, 1,678 stack traces, none unflagged; no `too_many_pings`; no JVM crash file. The Phase 2 run before it passed default
1,726, end-to-end 16 and Docker 45, with a clean audit over 658 files.

## 7. Out of scope

- HTTPS on the client's local API (decision 5), certificate reloading (decision 7), automatic certificate issuance
  (Consul's `auto_encrypt` and Connect CA), and certificate revocation lists.
- The Prometheus port, which serves metrics and no tokens.
