# Qraft Operations Runbook

**Last updated:** 2026-11-21

This runbook is for the people who deploy and run Qraft. It covers what runs where, how to start a cluster, how to
watch it, and what to do when something goes wrong. Two references hold the full lists this runbook does not repeat:

- [CONFIGURATION.md](CONFIGURATION.md): every setting, with its default.
- [HTTP_API.md](HTTP_API.md): every route, with its request, answer and permission.

Apply every procedure to one server at a time unless it says otherwise. A healthy quorum must stay available while a
server is stopped, rebuilt or replaced.

## Contents

1. [Overview](#overview)
2. [Configuration essentials](#configuration-essentials)
3. [Deploy and bootstrap](#deploy-and-bootstrap)
4. [Health and monitoring](#health-and-monitoring)
5. [Routine operations](#routine-operations)
6. [Membership](#membership)
7. [Failure recovery](#failure-recovery)
8. [Feature operations](#feature-operations)
9. [Known log messages and symptom index](#known-log-messages-and-symptom-index)
10. [Appendix: format compatibility](#appendix-format-compatibility)

## Overview

### Modes

Qraft is one executable jar with two modes:

- **Server mode** runs a member of the Raft cluster. The servers hold the replicated state: the catalog of nodes,
  services and checks, the key/value store, sessions and locks, tenants and namespaces, and ACLs.
- **Client mode** runs beside applications on each host. It registers the host as a node, registers the services in
  its configuration file, runs their health checks, heartbeats to the servers, and serves a local API on the host.

In this runbook `qraft` stands for `java -jar qraft.jar`. The container image runs `java -jar /app/qraft.jar` with
the arguments it is given. The commands are:

| Command | What it does |
|---|---|
| `qraft server [--config <path>]` | Runs server mode. |
| `qraft client [--config <path>]` | Runs client mode. |
| `qraft operator raft list-peers` / `remove-peer` | Lists or removes Raft members through a server's HTTP API. |
| `qraft operator raft inspect` / `recover` | Act offline on stopped data directories, for lost-quorum recovery. |
| `qraft acl ...` | Bootstraps ACLs and manages tokens, policies and roles. |
| `qraft kv get` / `put` / `delete` | Reads and writes the key/value store. |

Every command that calls a server takes `--http-addr <host:port>` (default `127.0.0.1:8080`; give `https://...` for
a server that serves HTTPS), the TLS options `--ca-file <path> [--client-cert <path> --client-key <path>]`, and a
token as `--token <token>` or `--token-file <path>`. Prefer `--token-file`: it keeps the secret out of the shell
history and the process list. Exit codes are 0 for done, 1 for refused or unreachable, and 2 for misuse.

### Ports

| Setting | Default | Listener |
|---|---|---|
| `server.http.port` | 8080 | HTTP API, health endpoints and the admin interface. |
| `server.raft.port` | 9080 | Raft traffic between servers (gRPC), joins, removals and forwarded writes. |
| `server.apiGrpcPort` | 10080 | The API over gRPC. |
| `server.telemetry.prometheusPort` | 9464 | Prometheus metrics, while `server.telemetry.enabled` is true (the default). |
| `server.dns.port` | none | DNS over UDP and TCP, only when `server.dns` is given. |
| `client.httpPort` | 8080 | The client's local API, on `client.bindAddress`. |
| `client.dns.port` | none | DNS on a client, only when `client.dns` is given. |

No two listeners may share a port: HTTP, Raft, API gRPC, Prometheus while telemetry is on, and DNS when it is
given. The server's HTTP listener binds `server.http.host` (`0.0.0.0`, every interface, by default); its Raft and
API gRPC listeners bind every interface. The client's local API listens on the loopback address unless
`client.bindAddress` names another IP address. A client and a server on the same host both default to port 8080, so
change one of them.

### Files

| Path | Holds |
|---|---|
| The configuration file | One versioned JSON document per process. See [Configuration essentials](#configuration-essentials). |
| `server.raft.storage.path` | The server's Raft data directory. Default `./data/raft/<server.id>`, relative to the working directory. |
| `client.dataDirectory` | The client's data directory. Required. |
| `logging.directory` | Application logs. Default `./logs`. |
| `server.audit.path` | The audit file, when set. |

**Server data directory.** The directory is one consistency unit. Treat every file in it as owned by Qraft and never
edit, copy or delete individual files.

- `server-id`: the server's durable Raft identity, a UUID generated at its first start. The cluster records each
  server by this ID, so a directory without the file is a different server, even under the same name and address.
- `meta.dat`: the current term and vote.
- `raft.log`: the Raft write-ahead log (WAL).
- `snapshot.dat`, when present: the latest published snapshot and its Raft boundary.
- `snapshot.dat.tmp`: a snapshot being written. One beside an existing `snapshot.dat` is stale and is removed at
  startup. One with no `snapshot.dat` is what a crash leaves while a server writes its first snapshot; startup moves
  it to `snapshot.dat.interrupted` and starts from the WAL.
- `snapshot.dat.interrupted`, when present: such an unpublished first snapshot, kept as evidence. Nothing reads it.
- `acl-bootstrap-reset`, when an operator writes it: see [Lost management secret](#lost-management-secret).
- Lock state of the storage library. The server holds an exclusive lock on the directory while it runs.

Never share a data directory between servers, and never mount one writable directory into two processes.

**Client data directory.** `client.dataDirectory` holds:

- `node-id`: the client's durable node ID. Keep the directory across restarts so the client keeps its identity.
- `client.lock`: a lock that lets one client at a time use the directory.
- `registrations.json`: what applications registered through the local API, with each registration's token.
- `acl-tokens.json`: tokens set through `PUT /v1/client/token/{kind}`, only when
  `client.acl.enableTokenPersistence` is true.

**Logs.** Each process writes to the console and to `<logging.directory>/qraft-server.log` and
`qraft-server.json` (`qraft-client.log` and `qraft-client.json` in client mode). The files roll daily and at 100 MB
into `<logging.directory>/archive/` as gzip files, kept for 30 days and at most 1 GB for each of the two files. The offline `qraft
operator raft inspect` and `recover` commands log to `./logs/qraft-server.*` under their working directory and to the
console.

## Configuration essentials

[CONFIGURATION.md](CONFIGURATION.md) lists every setting with its default. The rules an operator meets first:

- **One file per process.** A process reads its configuration from `--config <path>`; else from the JVM property
  `-Dqraft.config=<path>` (given before `-jar`); else from `config/<mode>.json` under the working directory; else from
  `/etc/qraft/<mode>.json`. Qraft reads no environment variables.
- **Version 1.** The document starts with `"version": 1`. Any other version is refused.
- **Unknown keys are refused.** A misspelt setting stops startup with a message that names it.
- **No setting changes while a process runs.** Change the file and restart the process.
- **A server's effective settings** can be read from `GET /v1/operator/configuration` (needs `operator:read` with
  ACLs enabled). It lists each setting with its value, whether it came from the file or a default, and withholds
  secrets: the operator token is never shown, and the telemetry endpoint is shown without credentials.

Settings refused at startup, with the message the server gives:

| Setting | Refused when |
|---|---|
| `server.raft.storage.fsync` | `false`: "Raft WAL fsync must be enabled for durability". |
| `server.raft.storage.type` | Anything but `raftlog`: "Raft storage type must be 'raftlog'". |
| `server.id` | Missing while `server.raft.nodes` lists more than one server: "server.id is required for a multi-node cluster". |
| `server.operator.token` | Shorter than 16 characters, or set while `server.acl.enabled` is true. |
| `server.acl.defaultPolicy` | Missing while ACLs are enabled. It is `allow` or `deny`. |
| `server.acl.initialManagementTokenFile` | Set while ACLs are disabled, unreadable, or not holding a UUID outside the reserved range. |
| `server.security.profile` | `production` without ACLs enabled and `defaultPolicy` `deny`. |
| `server.raft.port` | 0 on a server that has peers. |
| `server.raft.nodes` | Given without `server.id` as one of its keys: "server.id '...' is not a key of server.raft.nodes, which lists: ...". |
| `server.raft.heartbeatIntervalMs` | Not less than `server.raft.electionTimeoutMs`. |
| `server.raft.logHardLimit` | Less than 1. |
| `server.shutdown.drainTimeoutMs`, `server.shutdown.timeoutMs` | Negative. |
| Any two listener ports | The same nonzero port: "server.http.port and server.raft.port are both 8080: each listener needs its own port". |
| `server.ui.path` | Not a prefix such as `/ui/` of lower-case letters, digits and hyphens, or starting with a reserved segment: `v1`, `api`, `health`, `raft`, `status`, `metrics` or `debug`. |

A server with no `server.id` and a single listed node uses the host name and logs a warning: "Using hostname '...' as
node ID. Set server.id explicitly for production."

## Deploy and bootstrap

### Start a new cluster

Use an odd number of servers: three tolerate one failure, five tolerate two.

1. For each server, write a configuration file. Give every server its own `server.id` and the same
   `server.raft.nodes`, which maps each server's name to its Raft address. Each server's own entry is the address its
   peers dial. For example, `server1.json`:

   ```json
   {
     "version": 1,
     "server": {
       "id": "server1",
       "raft": {
         "nodes": {"server1": "server1:9080", "server2": "server2:9080", "server3": "server3:9080"},
         "storage": {"path": "/var/lib/qraft/server1"}
       }
     },
     "logging": {"directory": "/var/log/qraft"}
   }
   ```

   In containers, the storage path is normally `/app/data` on a separate named volume for each server, and the log
   directory `/app/logs`.
2. Make each storage path an empty, persistent directory owned by the user that runs the server.
3. Start every server: `qraft server --config server1.json`, and so on.
4. Watch the log. A server with no Raft state asks every listed server to describe itself, once a second. It
   bootstraps only when every listed server answers, none holds Raft state, and all list exactly the same servers.
   Until then it logs `Cluster bootstrap: WAITING`. Once one attempt succeeds, each server logs
   `Cluster configuration established: ...`.
5. Confirm the cluster:
   - `GET /health/ready` answers 200 on every server;
   - `qraft operator raft list-peers --http-addr server1:8080` lists every server as a voter, one as leader.

A server whose list differs from a peer's logs `Bootstrap refused: <peer> answers as ... and lists ..., but this
server lists ...` at ERROR, once a second, and no cluster forms. Correct the lists so they are identical; the order of
entries does not matter.

A single server may omit `server.raft.nodes`. It then advertises `localhost:<raft port>`, which other servers cannot
reach, so list it with a reachable address if the cluster may grow later.

### Start clients

1. Write each client's configuration file. `client.dataDirectory` and `servers.urls` are required:

   ```json
   {
     "version": 1,
     "client": {"nodeName": "web-1", "dataDirectory": "/var/lib/qraft-client"},
     "servers": {"urls": ["http://server1:8080", "http://server2:8080", "http://server3:8080"]},
     "catalog": {"services": [{"id": "web-1", "name": "web", "address": "10.0.0.21", "port": 8000}]}
   }
   ```

   `client.nodeName` defaults to the host name. List every server in `servers.urls`; the client moves to the next
   one when a server does not answer.
2. Start the client: `qraft client --config client.json`.
3. Confirm `GET /health/ready` on the client's local API answers 200, and that the node and its services appear in
   `GET /v1/catalog/nodes` and `GET /v1/catalog/services` on a server.

### Enable ACLs on a new cluster

ACLs are off by default, and every call is then allowed. With ACLs disabled a server logs a startup warning that
there is no access control.

1. On every server set:

   ```json
   "acl": {"enabled": true, "defaultPolicy": "deny"}
   ```

   inside `server`, and remove `server.operator.token` if it is set: with ACLs enabled, removals need a token with
   `operator:write` instead. For a production deployment also set `"security": {"profile": "production"}`, which
   refuses to start without ACLs enabled and `deny`.
2. Create the first management token, in one of two ways:
   - **From a file.** Put a UUID in a file readable only by the server's user, on every server, and set
     `server.acl.initialManagementTokenFile` to it. Whichever server leads installs the token. A bootstrap through
     the API is then refused with `management_token_pending` until it is installed.
   - **Through the API.** Start the servers, then run `qraft acl bootstrap --http-addr server1:8080` once. It prints
     the management token's `AccessorID` and `SecretID`. The secret is shown only this once; store it safely. A
     second bootstrap is refused with 403 and the index to reset it with.
3. Write the management secret to a file, for example `mgmt.token`, readable only by you.
4. Create policies. Rules are JSON:

   ```text
   qraft acl policy create --name operators --rules '{"operator": "write", "acl": "read", "node_prefix": {"": {"policy": "read"}}}' --token-file mgmt.token
   ```

   The resources are `node`, `service`, `client`, `key` and `session` (each also as `_prefix`, mapping a name to
   `{"policy": "read" | "write" | "deny"}`), and `operator` and `acl` (taking the access itself). Service and key
   rules can be nested under `tenant` and `namespace`. Use `--rules-file <path>` for longer rules.
5. Create roles and tokens:

   ```text
   qraft acl role create --name ops --policy-name operators --token-file mgmt.token
   qraft acl token create --description "operator" --role-name ops --token-file mgmt.token
   qraft acl token create --description "client web-1" --node-identity web-1 --service-identity web --token-file mgmt.token
   ```

   A node identity gives `node:write` on that node and `service:read` on every service. A service identity gives
   `service:write` on that service, in `default` or the tenant and namespace given as `service:tenant:namespace`,
   and read on every service and node. `--expiration-ttl` gives a token an expiry.
6. Check what a token may do before handing it out:

   ```text
   qraft acl explain --accessor-id <id> --resource operator --access read --token-file mgmt.token
   ```

   It prints whether access is allowed and whether a rule or the default policy decided it.
7. Configure each client, with the same default policy as the servers:

   ```json
   "acl": {
     "enabled": true,
     "defaultPolicy": "deny",
     "downPolicy": "extend-cache",
     "tokens": {"defaultFile": "/etc/qraft/tokens/default", "clientFile": "/etc/qraft/tokens/client"}
   }
   ```

   inside `client`. The token files are `clientFile` (the client's own calls for its node), `configFileRegistrationFile`
   (the services and checks in its file), `defaultFile` (requests that carry no token) and `recoveryFile` (the
   client's own endpoints when no server answers). `client` and `configFileRegistration` fall back to `default`. A
   service in `catalog.services` may name its own `tokenFile`. The client caches what it resolves for 30 seconds
   (`tokenTtlMs`, `policyTtlMs`, `roleTtlMs`). When no server answers, `downPolicy` decides: `extend-cache` (the
   default) uses the cache however old, and the default policy where nothing is cached; `async-cache` does the same
   while it asks again in the background; `allow` and `deny` allow or deny everything not freshly cached.
8. From now on, pass a token to every command and request: `--token-file <path>` on the command line, or the
   `X-Qraft-Token` header (or `Authorization: Bearer <token>`) on HTTP. Qraft never reads a token from a query string.

To enable ACLs on a cluster that is already running, enable them server by server with a rolling restart, give each
client its tokens and `client.acl`, and change the security profile to `production` last. Every server must run a
version that knows the ACL commands before ACLs are enabled: see
[Appendix: format compatibility](#appendix-format-compatibility).

### Files that hold secrets

- A token's secret is hashed before it is replicated, so the WAL, snapshots and backups hold hashes, never a working
  secret. They still hold everything else the cluster knows, including the catalog and the policies' rules: keep
  them on an encrypted medium with strict access.
- On a server, the file named by `server.acl.initialManagementTokenFile` holds a secret.
- On a client, each file under `client.acl.tokens`, each service's `tokenFile`, `registrations.json` and
  `acl-tokens.json` hold secrets.
- Only the user that runs the process should read them. Qraft writes its own files `rw-------` on a file system with
  POSIX permissions. On Windows, restrict the data directory. Do the same for files you mount.

### Enable TLS

`server.tls` and `client.tls` encrypt and authenticate the connections that carry tokens, registrations and Raft
traffic. [CONFIGURATION.md](CONFIGURATION.md) lists every key. In summary:

- `server.tls.caFile`, `certFile`, `keyFile` and `minVersion` (`TLSv1.2`, the default, or `TLSv1.3`) give the
  material. On their own they turn on nothing.
- `server.tls.https.enabled` makes the HTTP port serve HTTPS only; `server.tls.grpc.enabled` does the same for the
  API gRPC port; `server.tls.raft.enabled` turns on mutual TLS on the Raft port. `https.verifyIncoming` and
  `grpc.verifyIncoming` (default `true`) require a client certificate; `raft.verifyServerHostname` (default `true`)
  requires a peer's certificate to name the host dialled.
- `client.tls` gives a client the CA and its own certificate and key for calling HTTPS servers.

With ACLs enabled, a server logs at startup a warning naming each listener without TLS, HTTP, API gRPC or Raft,
since tokens sent to it cross the network unencrypted. The Raft listener counts: a follower forwards a write to the
leader over it, with the caller's token.

**Certificates.** Use one CA for the cluster. Each server's certificate names every host its peers and clients dial
it by (the hosts in `server.raft.nodes` and in the clients' `servers.urls`), with both server and client use, since a
server is a client of its peers. Each client's certificate has client use. Keys are PEM, PKCS #8
(`BEGIN PRIVATE KEY`), RSA or EC; convert an older RSA key with `openssl pkcs8 -topk8 -nocrypt`. Keep key files
readable by the process's user alone.

**Turning it on.**

1. Give every server the material, and set `https`, `grpc` and `raft` enabled together.
2. Restart the servers one at a time. A server with mutual TLS on its Raft port and one without cannot reach each
   other, so a half-changed cluster keeps a quorum only while the changed or the unchanged servers are a majority.
3. Change each client's `servers.urls` to `https://` and give it `client.tls`; restart it.
4. Add the TLS options to every command: `--http-addr https://server1:8080 --ca-file ca.pem --client-cert
   client.pem --client-key client.key`.
5. With `verifyIncoming`, health probes need a certificate too:

   ```text
   curl --cacert ca.pem --cert client.pem --key client.key https://server1:8080/health/ready
   ```

**What a refusal looks like.** A client logs once per server `TLS with server ... failed, so the client cannot use
that server until it is fixed: ...` and is not ready. A server that cannot reach a peer over mutual TLS logs it as an
unreachable peer, as for a partitioned one; compare `/raft/status` on each server to see whether they agree on a
leader.

Qraft does not check certificate revocation, and the client's local API serves plain HTTP only.

## Health and monitoring

### Liveness and readiness

Both endpoints are on every server and every client, need no token, and answer `GET` only.

- `GET /health/live` answers 200 `{"status":"alive"}` whenever the process can answer, even when fenced.
- `GET /health/ready` on a server answers 200 `{"status":"ready"}`, or 503 in the error envelope with code
  `not_ready` and the unmet conditions:

  ```json
  {"code":"not_ready","error":"not_ready","message":"Server is not ready: fenced, no_leader",
   "retryable":true,"requestId":"...","conditions":["fenced","no_leader"]}
  ```

  | Condition | Meaning |
  |---|---|
  | `draining` | The server is draining and takes no new work. |
  | `unavailable` | The server's Raft state could not be read in time. |
  | `fenced` | Storage failed or a durability transition was uncertain; see [Detecting corruption and fencing](#detecting-corruption-and-fencing). |
  | `recovering` | The server has not finished recovering its state from storage. |
  | `removed` | The server's removal from the configuration has committed. |
  | `no_leader` | The server is neither the leader nor a follower of a known leader. |

- `GET /health/ready` on a client answers 200 `{"status":"ready"}` or 503 `{"status":"not_ready"}`.

Route load balancers and orchestrators by readiness, and restart only on failed liveness. Do not restart a fenced
server in a loop: see [Detecting corruption and fencing](#detecting-corruption-and-fencing).

### Raft status

`GET /raft/status` (needs `operator:read` with ACLs enabled) answers this server's view: `nodeId`, `serverId`,
`state` (`FOLLOWER`, `CANDIDATE` or `LEADER`), `term`, `leaderId`, `commitIndex`, `lastApplied`, `lastLogIndex`,
`snapshotLastIndex`, `fenced`, `fenceReason`, `removed`, `storageBytes` and `snapshotAgeSeconds` (-1 with no
snapshot). Compare it across servers: they should agree on `leaderId` and `term`, and their `lastApplied` should move
together.

A snapshot age that keeps growing while the log grows means snapshots are not being taken; see
[Snapshots and log size](#snapshots-and-log-size).

### Membership, storage and autopilot reads

Each needs `operator:read` with ACLs enabled. [HTTP_API.md](HTTP_API.md) has the full answers.

- `GET /v1/operator/raft/configuration`: the Raft configuration this server holds, each server's `serverId`, `name`,
  `address`, `voter` and `leader`. `qraft operator raft list-peers` prints it as a table:

  ```text
  qraft operator raft list-peers --http-addr server1:8080 --token-file ops.token
  ```

- `GET /v1/operator/raft/members`: on the leader, each server's progress (`matchIndex`, `nextIndex`, `lag`,
  `lastContactMillis`, `reachable`, `healthy`) and the quorum (`voters`, `quorumSize`, `reachableVoters`,
  `failureTolerance`, `healthy`). Another server answers the configuration it holds, labelled `"view":"follower"`,
  with the leader it knows and no progress.
- `GET /v1/operator/raft/storage`: this server's log boundaries (`firstLogIndex`, `lastLogIndex`), `commitIndex`,
  `lastApplied`, `snapshotLastIndex`, `snapshotLastTerm`, `storageBytes`, `snapshotAgeSeconds`, `fenced`,
  `fenceReason`, `transitionQueueDepth` and `lastSnapshotFailure`.
- `GET /v1/operator/autopilot/health`: answered by the leader only. Each server's `LastContact`, `LastIndex` and
  `Healthy`: it has answered within an election timeout and is no more than 250 entries behind. Before removing or
  replacing a server, check `FailureTolerance` is at least 1. Another server answers 503 `leader_unavailable` and
  names the leader in `X-Qraft-Leader-Id`.

### Metrics

With `server.telemetry.enabled` (the default), a server exports metrics for Prometheus at
`http://<server>:<server.telemetry.prometheusPort>/metrics`, port 9464 by default. Prometheus sees each name with
underscores for dots and the unit and `_total` suffixes the exporter adds, such as
`qraft_catalog_registrations_total`. No label names a service, key, session or node. Useful series:

| Metric | Shows |
|---|---|
| `qraft.cluster.is_leader`, `qraft.cluster.term`, `qraft.cluster.commit_index`, `qraft.cluster.last_applied` | Leadership and progress. |
| `qraft.raft.storage.bytes`, `qraft.raft.snapshot.age` | Storage size, and seconds since the published snapshot. |
| `qraft.raft.snapshot.total`, `qraft.raft.snapshot.duration`, `qraft.raft.log.compacted.entries` | Snapshot activity. |
| `qraft.http.requests`, `qraft.http.request.duration` | Requests, by route prefix, method and status. |
| `qraft.catalog.registrations`, `qraft.catalog.deregistrations`, `qraft.health.transitions` | Catalog activity. |
| `qraft.kv.applies`, `qraft.session.active`, `qraft.session.invalidations`, `qraft.lock.acquisitions` | Key/value, sessions and locks. |

A client publishes `qraft.client.ready`, `qraft.client.last_contact_age_seconds`, `qraft.client.server` and
`qraft.client.reconciliations` at `GET /v1/client/metrics` on its local API, as JSON or with `?format=prometheus`
for a scrape (needs `client:read` with ACLs enabled). `qraft.client.server` is 1, with the server the client tries
first as its `endpoint` label.

### Events

Each server keeps its newest 4,096 events in memory for its current boot: Raft leadership changes, snapshots and
fencing; the catalog's, key/value store's, sessions' and locks' changes for each committed entry; and the audit of
requests. A key's events name the key, never its value. Nothing in the journal is replicated or kept across a
restart.

With `operator:read`:

- `GET /v1/operator/events?after=<boot>:<sequence>&limit=100` answers a page of events, the cursor to ask from next
  (`next`), and a `status`: `more` while there are more, `history_gap` when the cursor is older than the server still
  holds, `other_boot` when it names an earlier boot. `category`, `type`, `tenant`, `namespace`, `service`, `key` and
  `session` narrow it.
- `GET /v1/operator/events/watch`, with the same parameters except `limit`, is a server-sent event stream. It ends
  with an `end` event giving the reason (`overflow` when the reader was too slow, `history_gap`, `other_boot`,
  `failed` or `server_stopping`) and the cursor to resume after.

Each server answers only its own events; watch each server to follow a cluster. After a gap or a restart, read the
state itself again, then watch from the new cursor.

### Admin interface

Each server serves the admin interface on its HTTP port at `/ui/` by default, and redirects `GET /` there.

- `server.ui.enabled` (default `true`) turns it off. `server.ui.path` moves it. `server.ui.devAssetsDirectory` is for
  developing the interface only; leave it unset.
- **Without ACLs** the server logs at startup, at WARN, that anyone who can reach it can read and change the cluster
  through the interface. Do not expose such a server beyond a trusted network.
- **With ACLs** the interface asks for a token at sign-in, every request it sends carries that token, and the ACLs
  decide what it may read and change. Each such request, its live reads included, writes an audit line.

### Audit file

With `server.audit.path` set, each server writes one JSON line for every request it answers under a token: the
time, the server, the token's accessor ID, the method, the route, the status, the request ID, and the tenant and
namespace. It never writes a secret. Writing is best effort: when the file cannot be written, the server warns and
goes on, and lines are lost.

`GET /v1/internal/ui/audit` (needs `operator:read`) reads the file of the server asked, newest first. It filters by
`accessor`, `requestId`, `method`, `tenant`, `namespace`, `route` (a prefix) and `result` (`allowed`, `denied` for
403, `failed` for 5xx), and pages with `limit` (100 by default, 1,000 at most), `before` and `from`. Each server
holds only the requests it answered, so read each server to see a cluster's requests. Rotation is under
[Audit file rotation](#audit-file-rotation).

## Routine operations

### Backup

A valid backup is a copy of the complete data directory made while that server is stopped. Copying only `raft.log`,
`meta.dat` or `snapshot.dat` combines different durability boundaries and is not a backup. Do not copy a running
server's directory: a copy can see the metadata, the WAL, a snapshot publication and log compaction at different
moments.

1. Confirm the other servers are ready and hold a quorum without this one.
2. Stop the server and confirm its process has exited.
3. Copy the entire storage directory, keeping file names, permissions and bytes. For a container volume, use an
   offline helper container or a storage-platform snapshot, only after the server using the volume has stopped.
4. Record the server's name, server ID (the `server-id` file), Qraft version, time, source path, and whether the
   server is restarted after the copy.
5. Start the server and wait for `GET /health/ready` to answer 200 before you stop another one.

The key/value store, sessions, tenants and ACLs are part of the replicated state and of every snapshot, so they need
no separate backup. Before restoring a backup, read
[Restoring storage without rolling back a voter](#restoring-storage-without-rolling-back-a-voter).

### Graceful stop

**A server** stops on SIGTERM, which `docker stop` sends. It first drains: its readiness answers 503 `draining` and
new writes are refused with 503 `draining`, retryable. Then it stops its expiry timers, closes its gRPC, DNS and HTTP
listeners, then stops its Raft node, which closes its storage. Each step is bounded by `server.shutdown.timeoutMs`
(30 seconds by default). It logs `Initiating graceful shutdown (drain=...ms, timeout=...ms)` and, at the end,
`Graceful shutdown completed`.

- The drain does not wait for a load balancer to notice it: the server goes straight on to close its listeners. Take
  it out of any load balancer before stopping it. `server.shutdown.drainTimeoutMs` bounds the drain step.
- A stopped server remains a voter. Stopping more than a minority of voters stops the cluster from committing.
- Stopping the leader is safe: the other servers elect a new one after an election timeout
  (`server.raft.electionTimeoutMs`, 5 seconds by default).

**A client** stops on SIGTERM, or when told through its local API with `PUT /v1/client/leave` (needs
`client:write`). It stops accepting writes, stops its checks, deregisters its services and its node, then exits. It
waits at most `client.shutdownTimeoutMs` (30 seconds by default). The leave answers `{"deregistered": true}` when
all of that completed. Otherwise the client logs `Client shutdown incomplete ...; automatic expiry may be
required`, and the servers expire the node as described in
[Node expiry and reaping](#node-expiry-and-reaping).

### Rolling upgrade

Upgrade only to a release whose wire, command, WAL, metadata and snapshot formats are compatible with the deployed
one. Read [Appendix: format compatibility](#appendix-format-compatibility) first.

1. Confirm the other servers retain quorum without the one you upgrade.
2. Stop one server and take a complete offline backup of its directory.
3. Keep its directory and server ID, and start the new release with the same configuration file.
4. Verify readiness, that `list-peers` still shows it as a voter, and that the catalog holds what you expect, before
   moving to the next server.
5. Upgrade clients after all servers.
6. Use a new feature only once every server runs a release that has it. An older server refuses a log entry that
   holds a command it does not know: as a follower it refuses the append, and on restart it stops its recovery at
   that entry, until it is upgraded.

On failure, stop the server and preserve its directory. Rolling back the software is safe only when the old release
can read everything the new release wrote. Do not restore the pre-upgrade backup after the server has reopened its
storage; follow [Restoring storage without rolling back a voter](#restoring-storage-without-rolling-back-a-voter) or
rebuild the server from healthy peers.

### Certificate renewal

A server and a client check their TLS files every 30 seconds and take up new material without a restart.

1. Write the new key before the new certificate, or both at once. A certificate without its matching key is refused
   until the key follows.
2. Watch the log. A reload is logged at INFO: `server.tls reloaded: certificate CN=..., valid until ...`. A refused
   reload is logged at WARN, `... was not reloaded, and the material in use stays: ...`, and the old material stays
   in use.
3. New connections use the new certificate; connections already open keep theirs until they close.

To change the CA: add the new CA to every `caFile` first, then renew every certificate, then remove the old CA.

### Token rotation

**A client's token.**

1. Create a new token with the same policies, roles and identities: `qraft acl token create ...`.
2. Put the new secret in the client's token file, then either restart the client or set it while the client runs
   with `PUT /v1/client/token/{kind}` and the body `{"token": "<secret>"}` (needs `client:write`). The kinds are
   `default`, `client`, `configFileRegistration` and `recovery`. A token set this way is kept across a restart only
   with `client.acl.enableTokenPersistence`; otherwise the file's token is used again at the next start.
3. Delete the old token: `qraft acl token delete --id <old accessor> --token-file mgmt.token`.

**Revoking the management token.** The lock-out guard refuses to delete the last token that holds `acl:write` without
an expiry, so work in this order:

1. Replace the secret in every server's management token file.
2. Restart the leader, so the new token is installed by the next leader.
3. Check the new token works: `qraft acl token list --token-file <new secret file>`.
4. Delete the old one: `qraft acl token delete --id <old accessor> --token-file <new secret file>`.

A token installed from a file returns at the next leadership change while any server's file still holds its secret,
which is why the files are changed first.

#### Lost management secret

Either:

- **Reset the bootstrap.** Run `qraft acl bootstrap --http-addr <leader>`. Its refusal names the reset index. Write
  that number to a file named `acl-bootstrap-reset` in the leader's data directory (`server.raft.storage.path`), and
  bootstrap again. The answer holds the new management secret, once; the file is removed when used. While a
  management token file is configured, bootstrap answers `management_token_pending` until the file's token is
  installed.
- **Install a new one from the file.** Put a new secret in every server's management token file and restart the
  leader.

### Audit file rotation

Before writing a line that would take the audit file past `server.audit.rotateBytes` (24 MiB by default), the server
moves the file aside under its name and the time in UTC, such as `qraft-audit-20261120T101530.000Z.json` for
`qraft-audit.json`, and starts a new file. It keeps the newest `server.audit.maxFiles` moved files (15 by default; 0
keeps none) and removes older ones. A file that cannot be moved, as Windows refuses one that a reader holds open,
goes on taking lines and is moved at a later one; the server warns once. The audit read covers the current file
only. A server that serves the admin interface fills its file faster, since each of the interface's requests is
audited.

### Snapshots and log size

A server takes a snapshot when `server.raft.snapshot.threshold` entries (10,000 by default) have been applied since
the last one. It checks every `server.raft.snapshot.checkIntervalMs` (60 seconds by default, at least 1 second).
After publishing a snapshot it compacts the log up to it. `server.raft.snapshot.enabled` (default `true`) turns this
off; leave it on.

`server.raft.logHardLimit` (100,000 entries by default) caps the log. When the log reaches it, the leader refuses
new writes and logs `Raft log at capacity (N/limit), rejecting command` until a snapshot compacts the log. A failed
snapshot is logged as `Scheduled snapshot failed: ...` and recorded in `lastSnapshotFailure` of
`GET /v1/operator/raft/storage`. Watch `snapshotAgeSeconds` and `storageBytes`.

## Membership

The cluster's membership is recorded in the Raft log, not read from the configuration files after the first start.
Every change adds, removes or alters one server, and commits before the next.

### Add a server

1. Choose a name and Raft address that no current member uses.
2. Write its configuration file. Its `server.raft.nodes` must list itself, with the address its peers will dial, and
   at least one current member.
3. Start it with an empty storage directory.
4. It finds a listed server that already holds Raft state, and asks the members in turn to add it. It logs
   `Bootstrap: asked <member> to join its cluster: JOINED Added <name> as a non-voter`.
5. The leader promotes it to a voter once it is within `server.raft.membership.promotionMaxTrailingEntries` (250 by
   default) of the leader and has stayed healthy for `promotionStabilizationMs` (10 seconds by default). When the
   leader's log has been compacted past what the new server needs, the leader sends it a snapshot first.
6. Confirm with `list-peers` that it is a voter, and that `GET /health/ready` answers 200.
7. Optionally add it to `server.raft.nodes` in the other servers' files. The servers take each other's addresses from
   the Raft configuration, not from the files, so this only keeps the files describing the cluster.

### Remove a server

With ACLs disabled, a removal needs `server.operator.token`, the same on every server; with none configured every
removal is refused and the server logs `No server.operator.token is configured: Raft operator removals are refused`
at startup. With ACLs enabled, it needs a token with `operator:write`.

1. Check `FailureTolerance` from `GET /v1/operator/autopilot/health` on the leader is at least 1, unless you are
   shrinking a cluster on purpose.
2. List the configuration and note the server's ID:

   ```text
   qraft operator raft list-peers --http-addr <host:port> --token-file <path>
   ```

3. Remove it, by ID or by name. Any server forwards the request to the leader:

   ```text
   qraft operator raft remove-peer --name <name> --http-addr <host:port> --token-file <path>
   ```

   - Exit code 0 means removed; the command prints `Removed <name> (<server ID>)`.
   - `Error 403: permission_denied` means the token is wrong or lacks `operator:write`.
   - `Error 404: not_found` means no configured server has that ID or name.
   - `Error 409: refused: Removing voter ... would leave N voters, of which this leader has recently heard from M:
     not a quorum` means too few of the remaining voters have answered the leader recently. Restore them first; do
     not retry blindly.
   - `Error 503: leader_unavailable`, or a leader that has not yet committed an entry in its term, is worth retrying
     after a moment.
4. Run `list-peers` again and confirm it is gone.
5. Stop the removed server and wipe its directory; do not leave it running. A removed follower never learns of its
   removal: it keeps its old configuration and keeps asking for pre-votes, which the voters refuse. A leader that
   removes itself steps down and reports `removed`.

An operator can remove a member below the automatic cleanup minimum when the quorum checks permit it.

### Replace a server that lost its storage

A server whose storage directory is empty starts with a new server ID. The cluster still records the old server ID
under its name and address, and that entry still counts as a voter. The leader refuses a join from the new ID while
the old entry holds the name or address; a join never evicts a member. Until an operator removes the old entry, the
new server asks once a second, logs the refusal (`Name or address belongs to server <ID>; an operator must remove
the existing member before replacement`), and takes nothing from the leader.

Use this procedure for a wiped or rebuilt directory, and as the last steps of
[recovering a corrupt replica](#recover-a-corrupt-replica-from-peers). The other servers must be healthy and hold a
quorum without the server being replaced.

1. Stop the server being replaced, if it is running, and confirm its process has exited.
2. List the configuration from a healthy server and note the old entry's server ID:

   ```text
   qraft operator raft list-peers --http-addr <host:port> --token-file <path>
   ```

3. Remove the old entry, as in [Remove a server](#remove-a-server).
4. Run `list-peers` again and confirm the old entry is gone.
5. Make sure the storage path is an empty directory with the correct owner and permissions, then start the server
   with its usual name, address and `server.raft.nodes`.
6. It joins as a non-voter and is promoted as in [Add a server](#add-a-server).
7. Before returning it to service, require:
   - `list-peers` shows it under its new server ID with `Voter` `true`;
   - `/health/ready` answers 200, and `/raft/status` shows `"fenced":false`;
   - the catalog holds what you expect.

Notes:

- **Remove first, start second.** Starting the empty server before the removal is safe, but it only waits and logs a
  refused join every second.
- **A replacement that reports `removed`** holds a configuration that leaves it out and has stopped asking to join.
  Stop it, empty its directory again, and repeat from step 4.
- **Never copy `server-id`** from the old directory or another server to skip the removal. The old ID vouched for
  entries the empty directory no longer holds; reusing it can lose committed data.
- **A server that moved with its storage** is a different case. It keeps its server ID, asks to rejoin from its new
  address at startup, has its recorded address updated, and keeps its vote. No removal is needed.
- **Two servers lost at once in a cluster of three** leaves no quorum, so the removal cannot commit. Follow
  [Recover a cluster that lost its quorum](#recover-a-cluster-that-lost-its-quorum).

### Automatic failed-voter cleanup

The leader removes voters that have failed for long enough, through ordinary committed membership changes. The
`server.raft.membership` settings and defaults are:

```json
{
  "promotionStabilizationMs": 10000,
  "promotionMaxTrailingEntries": 250,
  "cleanup": {"enabled": true, "failureTimeoutMs": 60000, "intervalMs": 10000, "minVoters": 3}
}
```

- An omitted `failureTimeoutMs` or `intervalMs` rises to the election timeout or heartbeat interval when those are
  larger. An explicit failure timeout must be at least the election timeout, and an interval at least the heartbeat
  interval.
- A voter becomes eligible after `failureTimeoutMs` with no valid reply. The absence is counted in the current
  leader's memory, so every new leadership starts the grace again.
- Each pass removes at most `floor((N - 1) / 2)` of its `N` voters, one at a time, and never takes the cluster below
  `minVoters`. With three voters and the default minimum of 3, a failed voter is found eligible and kept; it is
  removed when a replacement's promotion commits, which requests an early pass.
- Cleanup never removes the leader or a non-voter, and does not recover a cluster that has lost its quorum.
- Disabling cleanup does not disable operator removal.

For automatic replacement, start a server with a new name and address, let it catch up and be promoted, and let
cleanup remove the failed voter. To reuse a name or address, follow
[Replace a server that lost its storage](#replace-a-server-that-lost-its-storage).

Startup logs `Raft membership policy: ...` with the effective settings. The leader logs eligible IDs, proposed
removals and committed removals at INFO, and why it defers a pass at DEBUG.

### Node expiry and reaping

Nodes are the hosts clients register, not Raft members. A client heartbeats every `client.heartbeatIntervalMs`
(30 seconds by default).

- The leader marks a node unreachable after `server.health.nodeTtlMs` (90 seconds by default) without a heartbeat.
  Sessions bound to that node end, and their locks are released.
- An unreachable node is reaped, with its services, after `server.health.nodeReapAfterMs` (72 hours by default). 0
  never reaps.
- A client that stops cleanly deregisters its services and node itself; expiry is for clients that do not.

### What to expect when servers return, leave or stop

These are normal, not faults:

- **A voter that returns after a long stop does not replace the leader.** It asks the other voters for a pre-vote
  before raising its term, and a voter that hears a live leader refuses. You may see `Asking for pre-votes for node X
  at term N+1` every few seconds in its log, with no election and no change of term, until the leader's first
  heartbeat makes it a follower. A vote or pre-vote request from a configured peer makes the transport reconnect to
  that peer at once, so the heartbeat is not delayed by connection backoff.
- **A server that missed a term catches up from a refusal.** A voter in a later term refuses a pre-vote and answers
  with that term, and the asking server follows it.
- **A server cut off from its leader reports `no_leader`.** After an election timeout of silence it forgets its
  leader, although it takes no new term. The next heartbeat makes the leader known again.
- **A leader that loses contact with a majority steps down,** logging `Node X stepping down in term N: a majority has
  not responded for R heartbeat rounds`.
- **A snapshot to an unreachable lagging peer is retried less often.** The leader waits 250 ms after the first
  failure, doubling up to 5 seconds, and starts again after a delivered snapshot or a new leadership. Each failed
  attempt is still logged as an error.

## Failure recovery

### Detecting corruption and fencing

A storage failure, or an uncertain durability transition, fences the server. A fenced server stays observable but
stops taking part:

- `GET /health/live` answers 200 `{"status":"alive"}`.
- `GET /health/ready` answers 503 `not_ready` with `fenced` among its `conditions`.
- `GET /raft/status` reports `"fenced":true` and `fenceReason`, the original failure's exception type and message. A
  healthy server reports `"fenceReason":null`.
- The first transition that fences the server logs `Raft transition sequencer fenced: <exception type>: <message>` at
  ERROR, with the original stack trace. Later fence attempts keep the first reason and are not announced again. The
  failing operation's own error stays in the log.
- A server whose recovery fails at startup logs `Raft recovery failed; node remains live but unready and will not
  participate. Preserve the node directory for diagnosis, then replace it from a healthy peer and restart: ...` and
  reports the failure in `fenceReason`.
- A committed entry that cannot be applied fences the server with `Fencing node X: committed entry N could not be
  applied; this replica must not continue without it: ...`.
- Logs name the path and the cause. WAL corruption names `raft.log` and a byte position. Snapshot corruption is
  reported as `Corrupt snapshot.dat: ...`.
- A server whose WAL is compacted further than its published snapshot reaches is fenced at startup: `Node X cannot
  recover: its log is compacted through index N but its published snapshot reaches only index M; ...`. A published
  snapshot is missing or has been replaced by an older one.

What to do:

1. Do not restart a fenced server in a loop. It does not unfence in place.
2. Do not modify or truncate the WAL or any other file.
3. Preserve the directory and the logs.
4. Rebuild the server from healthy peers: [Recover a corrupt replica from peers](#recover-a-corrupt-replica-from-peers).
   An older backup must not be restarted as the same voter.

### Recover a corrupt replica from peers

Use this only when the remaining servers are healthy, hold the state you need, and retain quorum.

1. Take the fenced server out of traffic and stop its process.
2. Move or snapshot its complete storage directory to a read-only evidence location, keeping the original bytes and
   logs.
3. Replace the server: [Replace a server that lost its storage](#replace-a-server-that-lost-its-storage). An empty
   directory gives the server a new server ID, so the cluster does not take it back until its old entry is removed.

Recover one replica at a time. If no healthy quorum remains, preserve all storage and use
[Recover a cluster that lost its quorum](#recover-a-cluster-that-lost-its-quorum) instead; wiping another server can
destroy the remaining evidence.

A server fenced because its WAL is compacted beyond its published snapshot is handled the same way. It may have
acknowledged the entries it lost. Do not copy a `snapshot.dat` into the directory from a backup or another server.

**An interrupted first snapshot does not stop a server.** A server killed while writing the first snapshot of its
life finds `snapshot.dat.tmp` and no `snapshot.dat` at the next start. Its WAL is still complete. It logs `Found
unpublished first snapshot ... with no published snapshot: kept as ...snapshot.dat.interrupted, and starting without
a snapshot` and starts from the WAL. No action is needed. Do not rename either file to `snapshot.dat`.

### Restoring storage without rolling back a voter

An offline copy is consistent when it is taken; that does not make it safe to restore later under the same server
ID. If the server has opened its storage since the copy, it may have recorded a later term, vote or acknowledged log
entry. Restoring the older `meta.dat` and WAL erases those promises while the cluster still counts the same voter.
Leader catch-up does not make this safe: Raft requires term, vote and log to survive restarts
([Raft, Figure 2 and section 5.2](https://raft.github.io/raft.pdf)).

Restore a complete directory under the same server ID only when it is the server's last durable state: the copy was
made after a clean stop and the server has not opened its storage since. Then:

1. Keep the process stopped.
2. Preserve the damaged directory.
3. Restore the complete copy to the configured path and fix its ownership.
4. Start the server. Never merge files from different directories.

If the backup predates later participation, or you do not know:

- **With a healthy quorum**, keep the backup as evidence and
  [replace the server](#replace-a-server-that-lost-its-storage) from empty storage under a new server ID. Do not
  copy backup files or its `server-id` into the replacement.
- **Without a quorum**, preserve every surviving directory and backup, keep failed servers stopped, and use
  [Recover a cluster that lost its quorum](#recover-a-cluster-that-lost-its-quorum), which rebuilds one seed under a
  new server ID from a surviving directory or a backup. Never restore an older directory under its old server ID.

### Recover a cluster that lost its quorum

Use this only when too few voters remain to elect a leader and none can be brought back with its own directory (in a
cluster of three, two are lost for good). It rebuilds **one seed** from a stopped directory, under a **new server
ID**, with a configuration in which the seed is the only voter. Every other server then joins the seed empty. The
same procedure rebuilds a whole lost cluster from a backup.

What it gives you:

- The seed holds every entry in the source directory. From a surviving directory, that is everything the survivor
  had. From a backup, it is exactly what preceded the copy; anything committed later is lost, including writes
  clients were told succeeded. Clients must register again what they wrote after the backup.
- The old server IDs never return. The seed and the servers that join it refuse appends, snapshots and campaigns
  meant for the old IDs, and the old configuration counts no reply from the new ones. A server that receives an
  answer from an unexpected server ID logs `Ignoring ... from X with server ID Y, which is not the configured
  server`.
- Nothing is edited inside a data directory by hand. The command copies the source, appends one configuration entry
  and publishes the copy.

It is not an upgrade or migration tool, and not a way to change membership while a quorum remains.

#### Rules before you start

1. **Keep every excluded server stopped:** the seed's source, the other survivors and the lost servers. Server IDs
   keep the two clusters apart, but an excluded directory that holds an old majority can still start and serve its
   own clients as the old cluster. Each stays stopped until its directory is wiped.
2. **Preserve everything.** Do not wipe, move or repair an excluded directory until the recovered cluster is healthy.
3. **Pick the source.** With surviving directories, use the one with the highest last log index and term (`inspect`
   shows both). With none, use the newest complete backup taken from a stopped server.
4. **A source on read-only media** is first copied to writable storage, as a complete offline copy.
5. **The source's parent directory must be persistent and writable.** The command writes a one-time claim file
   beside the source, `<source>.qraft-recovery.json`, and stages and publishes the seed in the output directory. In
   containers, mount the source inside a persistent recovery volume, for example at `/recovery/source`, and keep the
   claim, the staging copy and the output (`/recovery/seed`) on that volume. A source mounted at a mount root such
   as `/app/data` would put the claim in the container's throwaway layer.
6. **The commands are offline.** A running server holds the source's lock, and both commands then refuse.

#### Procedure

1. **Inspect the source.** The command prints the directory's state and whether ordinary startup would accept it,
   and changes nothing:

   ```text
   qraft operator raft inspect --dir /recovery/source
   ```

   For example:

   ```text
   Server ID           03648846-07ab-4d0b-9b94-2ea1d62547fe
   Term                1
   Voted for           server1
   Last log index      9
   Last log term       1
   Snapshot index      8
   Snapshot term       1
   Compacted through   8
   Configuration from  SNAPSHOT
   Ordinary startup    accepted

   Name     ID                                    Address       Voter
   server1  03648846-07ab-4d0b-9b94-2ea1d62547fe  server1:9080  true
   server2  5c1f1a2e-6b7d-4f0e-9a43-2a8e5d9b1c70  server2:9080  true
   server3  9e2b7c44-1d3a-4c8f-b6e5-7f0a3d2c8e19  server3:9080  true
   ```

   `Configuration from` is `WAL`, `SNAPSHOT` or `NONE`. Record the server ID: the recovery input repeats it. If
   ordinary startup is refused, the report says `refused: <reason>`; recover only from a directory ordinary startup
   accepts.

2. **Write the recovery input.** It is a version 1 JSON file, and unknown keys are refused. `source.serverId` must
   equal the ID `inspect` printed. `serverConfig` is the server configuration file the seed will start with. That
   file gives the output directory (`server.raft.storage.path`, a new directory on the persistent volume), the seed's
   name (`server.id`), and its Raft address, which must be listed under its name in `server.raft.nodes`.

   ```json
   {"version": 1,
    "source": {"path": "/recovery/source", "serverId": "03648846-07ab-4d0b-9b94-2ea1d62547fe"},
    "serverConfig": "/etc/qraft/server.json"}
   ```

3. **Recover:**

   ```text
   qraft operator raft recover --input /etc/qraft/recovery.json
   ```

   Exit codes: 0 done, 1 refused, 2 invalid input. For example:

   ```text
   Outcome             recovered
   Output              /recovery/seed
   Server ID           287be963-4fe3-4304-92c6-1eda38229cba
   Recovery index      10
   Recovery term       2

   Start server1 with its server configuration: it is the sole voter. Keep the source /recovery/source and every excluded server stopped; they rejoin only empty, through a join.
   ```

   The command holds the source's lock, copies it byte for byte and compares checksums, opens the copy with the
   ordinary checks, appends one entry that lists the seed as the only voter in a term above the source's, and
   publishes the copy with the new server ID. The source is never changed.

   **One-time use.** A second `recover` from the same source with a different output is refused, so one source
   cannot seed two clusters. If the command was interrupted, run the same input again: before publication it starts
   again from the unchanged source with a new ID; after publication it records the result, prints `Outcome
   publication recorded`, and exits 0. Once a recovery is complete, the same input exits 1 and changes nothing. A
   staging directory is never accepted as a data directory.

4. **Start the seed** with the server configuration used above, keeping the name and address the old server had.
   Check that:
   - `/raft/status` shows it as leader, with its new server ID;
   - `list-peers` lists only the seed, as a voter;
   - the registrations you expect are present, and a test write commits.

5. **Rejoin the others empty.** For each remaining server, wipe its data directory (the old one is evidence you may
   now discard), then start it with its usual configuration. It gets a new server ID, joins the seed and is promoted.
   Do not list a still-running excluded server among a wiped server's `server.raft.nodes`. Watch `list-peers` until
   every server is a voter under a new ID and none of the old IDs remains.

The recovered cluster starts below `cleanup.minVoters` and grows by joins; cleanup never takes it below that minimum.

### Directory-lock failures

The storage holds an exclusive lock on its directory while the server runs. A second process pointed at the same
directory fails at startup with `Failed to initialize Raft storage: ...` naming the directory; the first one keeps
serving. A client's data directory is locked the same way: a second client fails with `<directory> is in use by
another client process; a data directory belongs to one client at a time`.

1. Find the process or container that owns the directory.
2. If it is the intended one, leave it running and correct the other's storage path or volume mapping.
3. If the owner is stale, stop it cleanly and confirm it has exited before starting the intended process.
4. Never bypass, delete or replace lock state while an owner process is alive.

## Feature operations

### Reads, writes and consistency

**Writes** may be sent to any server. A follower forwards a write to the leader over the Raft port and answers with
the leader's answer, which carries `X-Qraft-Forwarded-To` with the leader's name. The leader checks the token,
writes the audit line and applies limits as for a write sent to it directly. The follower answers once it has
applied the write itself, so a caller that reads from it next sees its own write. A write that has already been
forwarded once is not forwarded again.

When a forward fails, the answer says whether the write may have been applied:

| Answer | Meaning | What the caller should do |
|---|---|---|
| 503 `leader_unavailable` | The write was never sent: the follower knows no leader, could not connect to it within 2 seconds, or stopped following it before sending. It was not applied. | Send it to another server. |
| 503 `outcome_unknown`, `retryable: false` | The write was sent and not answered, or the follower stopped following the leader while waiting. It may have been applied. | Read the state, then retry if it is safe to. |

A commit that times out on the leader itself is also answered `outcome_unknown`. Answers name the leader in
`X-Qraft-Leader-Id` when it is known.

**Reads** take a consistency mode:

| Mode | Answered from | Notes |
|---|---|---|
| default | The server asked, from its applied state. | |
| `?stale` | The same. | Answers as the default does. |
| `?consistent` | The leader, after it confirms it still leads and has applied everything committed when the read began. | Any other server refuses it with 503 `leader_unavailable`, naming the leader. |

Giving both `stale` and `consistent` is refused. Each read answers `X-Qraft-Consistency` (the mode applied),
`X-Qraft-Known-Leader`, `X-Qraft-Last-Contact` (milliseconds since this server heard from the leader) and
`X-Qraft-Index`. A blocking read adds `?index=<X-Qraft-Index>&wait=<duration>`; `wait` defaults to 5 minutes and is at
most 10 minutes. Every answer names the server that gave it in `X-Qraft-Answered-By`.

### Key/value

The key/value store is part of the replicated state and of every snapshot, so it needs no step of its own in a
backup, recovery or upgrade. `qraft kv` reads and writes it:

```text
qraft kv put app/config @config.json --token-file app.token
qraft kv get app/ --recurse --token-file app.token
qraft kv delete app/old --token-file app.token
```

It takes `--tenant` and `--namespace` for a scope, `--flags` and `--cas` on `put`, `--recurse` or `--keys
[--separator]` on `get`, the TLS options, and a token. With ACLs enabled the token needs `key` rules.

### Sessions and locks

Sessions are part of the replicated state and of every snapshot, with the lock delays of the keys they held. Their
TTL timers are kept by the leader in memory. A new leader gives every session twice its TTL from its election, so a
leader change never ends a session that was being renewed.

A process elects itself leader of something through its client's local API:

1. Create a session, which the client binds to its own node: `PUT /v1/session/create` with
   `{"Name": "billing-leader", "TTL": "15s", "LockDelay": "15s"}`. It answers `{"ID": "<session>"}`.
2. Try to take the lock: `PUT /v1/kv/service/billing/leader?acquire=<session>` with the leader's address as the body.
   `true` means this process leads. `false` means another session holds the key, or the lock delay after the last
   holder's session ended has not passed.
3. While leading, renew the session within its TTL: `PUT /v1/session/renew/<session>`. A 404 means the session has
   ended, and the process must stop acting as leader at once.
4. To step down, `PUT /v1/kv/service/billing/leader?release=<session>`, then `PUT /v1/session/destroy/<session>`.
5. Others watch the key with a blocking read, `GET /v1/kv/service/billing/leader?index=<X-Qraft-Index>&wait=5m`.
   When its `Session` is gone, they go back to step 2.

A Java application can use the client library's `LeaderElection`, which follows the same recipe.

A session ends when it is destroyed, when twice its TTL passes without a renewal, or when its node becomes
unreachable or leaves the catalog. Its keys are then released, or deleted with `"Behavior": "delete"`. Durations are
written as Go writes them, such as `15s`, or in milliseconds, such as `1500ms`. With ACLs enabled, the token needs
`session:write` on the node and `key:write` on the key.

For operators:

- `GET /v1/internal/ui/sessions` lists the sessions with each one's `RenewedAt` and `ExpiresAt` from the leader's
  timers. Only the leader keeps the timers; another server answers both as null with `"TtlKnown": false`.
- `GET /v1/internal/ui/locks` lists every key a session holds, in every tenant and namespace, with its session and
  indexes but not its value, and the lock delays still running. `?session=<id>` narrows it to one session. With ACLs
  enabled, a key appears only where the token may read it. The server cannot see who is waiting for a lock: a
  contender waits with a blocking read of its own.
- The `session.destroyed` event and `qraft.session.invalidations` give the reason a session ended: `destroyed`,
  `ttl` or `node`.

### Tenants and namespaces

Tenants and namespaces are part of the replicated state and of every snapshot. Only the `default` tenant and its
`default` namespace exist from the start. A registration, key write or lock acquisition into a tenant or namespace
that does not exist is refused 404 `tenant_not_found` or `namespace_not_found`.

Create each tenant and namespace before clients use it, with a token that has `operator:write`:

```text
PUT /v1/tenant     {"Name": "acme", "Description": "Acme Ltd"}
PUT /v1/namespace  {"Name": "prod", "Tenant": "acme"}
```

A client whose tenant or namespace is missing keeps retrying its registrations, logging each refusal at WARN, and
becomes ready once the scope exists. Deleting a namespace (`DELETE /v1/namespace/prod` with `X-Qraft-Tenant: acme`)
removes its services, checks and keys; deleting a tenant removes all its namespaces. A session that held a key in a
deleted namespace goes on, holding nothing there. `GET /v1/internal/ui/scopes` (needs `operator:read`) lists every
tenant and namespace with what it holds.

### DNS

A server or a client serves DNS when its configuration gives `dns.port`:

```json
"dns": {"host": "0.0.0.0", "port": 8600, "domain": "qraft", "ttlSeconds": 0, "onlyPassing": false}
```

`host` defaults to `server.http.host` on a server and `client.bindAddress` on a client. The listener takes UDP and
TCP on the same port, and the process does not start if it cannot bind both. Point a resolver's stub zone for the
domain at it, or ask directly:

```text
dig @127.0.0.1 -p 8600 web.service.qraft SRV
dig @127.0.0.1 -p 8600 primary.web.service.prod.ns.acme.ap.qraft A
```

Names are `[tag.]<service>.service[.<namespace>.ns][.<tenant>.ap].<domain>`, `_<service>._<tag>.service...`,
`<node>.node.<domain>` and `<hex address>.addr.<domain>`. Names outside the domain are refused, so send only the
domain here.

With ACLs enabled, a server reads under the token in `server.dns.tokenFile`, which needs `service:read` on what it
answers and `node:read` for node names; without one it reads as the anonymous token. A client reads under its default
token. A lookup the token may not make is answered REFUSED, and one whose read fails or finds no server SERVFAIL;
neither is logged above DEBUG. Each lookup counts in the HTTP request metrics, and one made under a token writes an
audit line.

### Auth methods and login

A workload that holds a JWT can exchange it for a Qraft token. An operator with `acl:write` creates an auth method
(`PUT /v1/acl/auth-method`) and binding rules that say what a login gets (`PUT /v1/acl/binding-rule`). The workload
calls `POST /v1/acl/login` and gets a token that expires at the method's `maxTokenTtl`, and `POST /v1/acl/logout` when
done. [HTTP_API.md](HTTP_API.md) has the bodies.

- A `jwt` method validates tokens against the provider's PEM public keys (`jwtValidationPubKeys`). To rotate the
  provider's key, update the method's keys.
- A `kubernetes` method sends a pod's service account token to the cluster's TokenReview API, using the API's address
  (`host`), its CA (`caCert`) and a reviewer token (`serviceAccountJwt`) allowed to create TokenReviews (the
  `system:auth-delegator` cluster role). To rotate the reviewer token, write the method again with the new one.
- A bearer token that is not accepted, or that no binding rule matches, is refused 403 `permission_denied`. If the
  Kubernetes API cannot be reached, or its certificate is not signed by `caCert`, the login is refused 503
  `auth_method_unavailable` and the server logs why at WARN.
- Deleting a method revokes every token its logins created.

## Known log messages and symptom index

### Symptom index

| Symptom or log line | Where to look |
|---|---|
| `/health/ready` 503 with `fenced`; `Raft transition sequencer fenced: ...` | [Detecting corruption and fencing](#detecting-corruption-and-fencing) |
| `Raft recovery failed; node remains live but unready ...` | [Detecting corruption and fencing](#detecting-corruption-and-fencing) |
| `... cannot recover: its log is compacted through index N but its published snapshot reaches only index M` | [Recover a corrupt replica from peers](#recover-a-corrupt-replica-from-peers) |
| `Found unpublished first snapshot ...` | [Recover a corrupt replica from peers](#recover-a-corrupt-replica-from-peers), last paragraph: no action |
| `Failed to initialize Raft storage: ...`; `... is in use by another client process ...` | [Directory-lock failures](#directory-lock-failures) |
| `/health/ready` 503 with `no_leader` on every server | Quorum lost: [Recover a cluster that lost its quorum](#recover-a-cluster-that-lost-its-quorum), after checking the network and peers |
| `/health/ready` 503 with `no_leader` on one server | [What to expect when servers return, leave or stop](#what-to-expect-when-servers-return-leave-or-stop) |
| `/health/ready` 503 with `removed` | [Remove a server](#remove-a-server), step 5 |
| `Cluster bootstrap: WAITING` repeating | [Start a new cluster](#start-a-new-cluster): a listed server does not answer |
| `Bootstrap refused: ... lists ..., but this server lists ...` | [Start a new cluster](#start-a-new-cluster): the lists differ |
| `Name or address belongs to server ...; an operator must remove the existing member before replacement` | [Replace a server that lost its storage](#replace-a-server-that-lost-its-storage) |
| `remove-peer` refused 403 `permission_denied` | [Remove a server](#remove-a-server) |
| `Raft log at capacity (N/limit), rejecting command` | [Snapshots and log size](#snapshots-and-log-size) |
| `Scheduled snapshot failed: ...` | [Snapshots and log size](#snapshots-and-log-size) |
| Writes answered 503 `leader_unavailable` or `outcome_unknown` | [Reads, writes and consistency](#reads-writes-and-consistency) |
| `?consistent` reads answered 503 `leader_unavailable` | [Reads, writes and consistency](#reads-writes-and-consistency): send them to the leader |
| `Log entry holds a command this server does not know ...` | [Rolling upgrade](#rolling-upgrade), step 6 |
| `TLS with server ... failed, so the client cannot use that server ...` | [Enable TLS](#enable-tls) |
| `... was not reloaded, and the material in use stays: ...` | [Certificate renewal](#certificate-renewal) |
| `management_token_pending` | [Enable ACLs on a new cluster](#enable-acls-on-a-new-cluster), step 2 |
| Client not ready; registrations refused `tenant_not_found` or `namespace_not_found` | [Tenants and namespaces](#tenants-and-namespaces) |
| `Client shutdown incomplete ...; automatic expiry may be required` | [Graceful stop](#graceful-stop), [Node expiry and reaping](#node-expiry-and-reaping) |
| `The audit file ... cannot be written` or `... could not be moved aside ...` | [Audit file rotation](#audit-file-rotation) |
| Login refused 503 `auth_method_unavailable` | [Auth methods and login](#auth-methods-and-login) |

### Expected while a peer is down

While a server is stopped, unreachable or being replaced, its peers log these. They are expected for that peer and
for that time only. The same lines for a peer that should be up, or that persist after it returns, need
investigation.

- `Raft peer X became unreachable during AppendEntries` (ERROR, once per outage, with the cause).
- `Failed to retrieve pre-vote from X` and `Failed to retrieve vote from X` (ERROR).
- `Failed to send InstallSnapshot chunk ...` (ERROR), retried with backoff.
- `Asking for pre-votes for node X at term N` (INFO) on a server that has lost its leader.

### Startup warnings

- `Security profile development with ACLs disabled: every call is allowed, and there is no access control. ...`
  (WARN): ACLs are off. Expected only where that is intended.
- `CRITICAL ACL_DENY_NOT_ENFORCED: server.acl.defaultPolicy is deny, but ACLs are disabled, ...` (WARN), or the same
  for `client.acl.defaultPolicy`: the configuration asks for `deny` without enabling ACLs. Fix the configuration.
- `ACLs are enabled, but no listener uses TLS: tokens cross the network unencrypted. ...`, and `ACLs are enabled, but
  the <listeners> do not use TLS: ...` naming the HTTP, API gRPC or Raft listeners that lack it (WARN): see
  [Enable TLS](#enable-tls).
- `Administrative interface enabled at /ui/ while ACLs are disabled: anyone who can reach this server can read and
  change the cluster through it` (WARN): see [Admin interface](#admin-interface).
- `Using hostname '...' as node ID. Set server.id explicitly for production.` (WARN).
- `No server.operator.token is configured: Raft operator removals are refused` (INFO).

### Routine information

- `ACL refused <method> <path>: ...` (INFO): a request was refused for its token. A refusal is not an error; it names
  the accessor ID, never the secret.
- `A write was not forwarded to the leader X: ...`, `A write for the leader X was withdrawn before it was sent ...`
  and `A write forwarded to the leader X was not answered ...` (INFO): see
  [Reads, writes and consistency](#reads-writes-and-consistency).
- A vote, snapshot or no-op transition that reaches a stopping server is turned away before anything is written, and
  logged at DEBUG. Any other failure of such a transition is an error.

### A warning in the log of a server that is stopping

A server that is stopping can log this warning once, with a stack trace:

```text
WARN  io.netty.channel.ChannelInitializer - Failed to initialize a channel. Closing: [id: 0x81d5c86d, L:/10.0.0.5:9080 - R:/10.0.0.6:43752]
io.netty.util.IllegalReferenceCountException: refCnt: 0, increment: 1
	at io.grpc.netty.NettyServer$1.initChannel(NettyServer.java:278)
```

- **What happened.** A peer connected to the server's Raft port in the instant the server stopped. gRPC's server had
  released its shared resources but had not yet marked itself terminated, and the connection was prepared between
  those two steps.
- **What it costs.** One refused connection to a server that was stopping anyway. The peer sees a closed connection
  and tries again by itself. No state is touched: the server's storage is closed by its own shutdown, which this does
  not interrupt.
- **What to do.** Nothing, when the warning is among the last lines of a server that was told to stop. Investigate
  when it appears on a server that was not told to stop.
- **Why Qraft does not prevent it.** The window is inside the gRPC library.

## Appendix: format compatibility

**Storage backend.** Qraft supports only the `raftlog` storage. The former `file` configuration value and the RocksDB
backend are not accepted. There is no in-place or online migration from RocksDB: preserve such a directory and stay
on the version that created it, unless a separately supplied offline exporter is available. An exporter must write a
new directory, validate it, and never change its source.

**Legacy file-backend bytes.** The current storage reads the legacy `meta.dat` and `raft.log`, and can append,
replace a suffix, sync, close and reopen them. The legacy `snapshot.dat` is read; the next successful snapshot writes
the current format.

**Older catalog data.** Catalog snapshots and WAL commands written before services had a composite identity decode
directly: a missing tenant or namespace becomes `default`, a missing datacenter or region an empty string, and a
missing enabled state `true`. Node commands and snapshot nodes that still carry fields nodes no longer have (host
name, port, capabilities) decode with those fields ignored; a removed node status reads as `healthy` or
`unreachable`, by whether the node was in contact, and an entry holding the removed capabilities update applies
nothing. No operator action or migration is needed for these.

**Directories that do not start.** A directory that holds Raft state but no cluster configuration refuses to start,
and so does an application snapshot without the Raft configuration envelope. Being able to read the WAL or decode
the catalog does not bypass these checks. There is no in-place upgrade from such a directory: preserve its bytes and
keep using the version that created it while its data must be kept. A new cluster on empty directories starts empty;
it does not migrate old data. Replacing one server from healthy peers works only when those peers already form a
compatible, configured Qraft cluster.

**New commands during an upgrade.** A server refuses a log entry that holds a command it does not know, rather than
skipping it: as a follower it refuses the append, and a recovering server stops at that entry, until it is upgraded.
Unknown fields inside a known command are read past. So upgrade every server before using a feature that adds
commands, such as ACLs, key/value, sessions, or tenants and namespaces.

**Pre-votes during an upgrade.** A server that does not support pre-votes answers a pre-vote request with gRPC
`UNIMPLEMENTED`, and the asking server counts it as willing, so a cluster upgraded one server at a time still elects.
