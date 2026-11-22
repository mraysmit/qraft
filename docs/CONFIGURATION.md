# Qraft Configuration Reference

**Last updated:** 2026-11-22

This is the complete reference for the configuration file of a Qraft process in
server mode and in client mode. Every key, default and rule here comes from the
code that reads the file: `AppConfig` for server mode, `ClientConfiguration`
and `DefinitionJson` for client mode, and the shared readers in
`dev.mars.qraft.common.config`, `common.tls` and `common.dns`.

## 1. How configuration works

### 1.1 One file per process

A Qraft process runs in one mode, chosen by its first argument:
`qraft server [--config <path>]` or `qraft client [--config <path>]`. It reads
one JSON file and nothing else.

The file is found in this order. The first rule that applies is used.

| Order | Source | Notes |
|---|---|---|
| 1 | `--config <path>` | Must be the only argument after the mode. Any other argument is refused with a usage message. |
| 2 | JVM system property `-Dqraft.config=<path>` | Used only when no argument follows the mode. A blank value is refused. |
| 3 | `config/<mode>.json` | Relative to the working directory: `config/server.json` or `config/client.json`. |
| 4 | `/etc/qraft/<mode>.json` | `/etc/qraft/server.json` or `/etc/qraft/client.json`. |

If none applies, the process stops with `No Qraft <mode> configuration file
found`, which lists the paths it tried. A path given by rule 1 or 2 is not
checked for existence first. If the file cannot be read, the process stops with
`Could not read server configuration <path>` (or `client`).

The Docker examples in `docker/config/` are mounted at `/etc/qraft/` and passed
with `--config`.

### 1.2 Document rules

These rules apply to both modes.

| Rule | Detail |
|---|---|
| JSON object | The document must be valid JSON and a JSON object. |
| No duplicate keys | A key repeated in one object is refused. |
| `version` | Required. An integer, and `1` is the only value accepted: `Unsupported configuration version: <n>`. |
| Unknown keys | Every object refuses a key it does not know, at every level: `Unknown server.raft setting: <name>`. |
| No placeholders | Any string value or field name that contains `${` is refused, with its JSON path. A lone `$` is ordinary text. |
| No environment variables | Qraft reads no setting from environment variables. The only input besides the file is the `qraft.config` JVM property, which only locates the file. |
| Defaults | Leave a key out to take its default. Do not write `null`: it is treated as absent for text settings and for most objects, but refused for numbers and booleans, for `server.raft.membership`, `server.raft.membership.cleanup` and `server.ui.devAssetsDirectory`, and for the text settings under `server.dns` and `client.dns`. |
| Text | Text settings are trimmed. A blank text setting is refused unless the table says blank is treated as absent. Tags and metadata values are kept as written. |
| No reload | No setting changes while the process runs. A change needs a restart. TLS files are the exception: see `server.tls`. |

Most rules are checked while the file is read. A server then checks its
cross-setting rules (ports, ACL and profile, I/O sizes, Raft timing, snapshot
and storage) before it opens its storage or any listener. A few rules are
checked later, as the process starts. The tables mark those "at start".

### 1.3 Seeing the effective settings

A server answers `GET /v1/operator/configuration` with every setting in force.
With ACLs enabled the request needs `operator:read`. The answer holds `server`
(the server's name), `file` (the path read), `readAt`, and `settings`, a list of
entries with these fields:

| Field | Meaning |
|---|---|
| `key` | The setting's key, such as `server.raft.electionTimeoutMs`. `server.raft.nodes` is one entry whose value is the whole map. |
| `value` | The value in force, or `null` when there is none. |
| `source` | `configured` when the file gave it, `default` otherwise. |
| `redacted` | `true` when the value is withheld or shortened. |
| `restartRequired` | Always `true`. |

Secrets are withheld. `server.operator.token` is answered as `null` with
`redacted: true` when one is set. `server.telemetry.otlpEndpoint` is answered
without user information or query, and marked redacted when that changes it.
For a file setting, such as `server.acl.initialManagementTokenFile`, the path
is answered, never what the file holds. When `server.dns` is absent the list
has one entry, `server.dns`, with value `null`.

A client answers `GET /v1/client/self`. Its `config` object shows `nodeName`,
`dataDirectory`, `address`, `clientPort`, `bindAddress`, `datacenter`,
`region`, `tenant`, `namespace`, `servers`, `heartbeatIntervalMs`,
`shutdownTimeoutMs`, `requestTimeoutMs`, `registrationRetryMinMs`,
`registrationRetryMaxMs`, `contactFreshnessMs` and `loggingDirectory`. It does
not show the ACL, TLS or DNS settings.

## 2. Server settings

A server document has three keys at its root:

```json
{
  "version": 1,
  "server": { },
  "logging": { }
}
```

`server` is required and must be an object. `logging` is optional.

Durations are integers in milliseconds unless the key says otherwise
(`ttlSeconds`). Ports are integers.

### 2.1 `server`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `id` | string | the host name, or `node-<pid>` if the host name cannot be read | Required when `server.raft.nodes` lists more than one server: `server.id is required for a multi-node cluster`. When `server.raft.nodes` is given, it must be one of its keys, checked with the rest of the configuration, before storage opens: `server.id '<id>' is not a key of server.raft.nodes, which lists: <names>`. Blank is treated as absent. | The server's name in the cluster. A server that takes the host name logs a warning. |
| `http` | object | | | See 2.2. |
| `raft` | object | | | See 2.3 to 2.8. |
| `health` | object | | | See 2.9. |
| `shutdown` | object | | | See 2.10. |
| `telemetry` | object | | | See 2.11. |
| `ui` | object | | | See 2.12. |
| `operator` | object | | | See 2.13. |
| `acl` | object | | | See 2.14. |
| `security` | object | | | See 2.15. |
| `tls` | object | absent: no TLS | | See 2.16. |
| `audit` | object | | | See 2.17. |
| `dns` | object | absent: no DNS | | See 2.18. |

The HTTP listener binds `server.http.host`. The Raft listener listens on every
interface; no setting chooses its address.

No two listeners may share a port other than 0: HTTP, Raft, the Prometheus
port while telemetry is enabled, and the DNS port, which serves TCP as well as
UDP, when DNS is on. The refusal names both settings, for example
`server.http.port and server.raft.port are both 8080: each listener needs its own port`.

There is no API gRPC listener. `server.apiGrpcPort` and `server.tls.grpc` belonged
to the gRPC `DistributedStateService`, which is retired, and a document that
still sets one is refused like any unknown setting:
`Unknown server setting: apiGrpcPort`, `Unknown server.tls setting: grpc`.

### 2.2 `server.http`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `host` | string | `"0.0.0.0"` | Non-blank. | The address the HTTP listener binds; `0.0.0.0` binds every interface, IPv4 and IPv6. Also the default for `server.dns.host`. |
| `port` | integer | `8080` | 0 to 65535; 0 takes any free port. Must differ from the other listeners' ports (2.1). | Port of the HTTP API. It also serves the admin interface (`server.ui`) and the health routes, and serves HTTPS only when `server.tls.https.enabled` is true. |

### 2.3 `server.raft`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `port` | integer | `9080` | 0 to 65535. 0 only when this server is the cluster's sole member: `server.raft.port may be 0 only on a cluster's sole member: peers dial the configured Raft address`. Must differ from the other listeners' ports (2.1). | Port of the Raft listener, which peers dial. |
| `nodes` | object of name to `"host:port"` | this server alone: `{ "<server.id>": "localhost:<server.raft.port>" }` | An object. Each name and address is a non-blank string: `server.raft.nodes must map non-blank IDs to addresses`. Names and addresses are trimmed. Must include `server.id`. | The servers this server contacts to form or join a cluster, in the document's order. See 5.1. |
| `electionTimeoutMs` | integer | `5000` | At least 1: `Raft timing intervals must be positive`. | The shortest election timeout. Each wait is chosen at random from `electionTimeoutMs` up to twice that. |
| `heartbeatIntervalMs` | integer | `1000` | At least 1, and less than `electionTimeoutMs`: `server.raft.heartbeatIntervalMs (<n>) must be less than server.raft.electionTimeoutMs (<n>)`. | How often the leader sends heartbeats. Membership timings are counted in heartbeat rounds. |
| `logHardLimit` | integer | `100000` | At least 1: `server.raft.logHardLimit must be positive, got: <n>`. | When the leader's log holds this many entries it refuses new commands with `Raft log at capacity (<n>/<limit>). Wait for snapshot.` A snapshot compacts the log. |
| `storage` | object | | | See 2.4. |
| `snapshot` | object | | | See 2.5. |
| `io` | object | | | See 2.6. |
| `membership` | object | | Present means an object; `null` is refused. | See 2.7 and 2.8. |

### 2.4 `server.raft.storage`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `type` | string | `"raftlog"` | Must be `raftlog`, in any case: `Raft storage type must be 'raftlog'`. | The storage engine. |
| `path` | string | `"./data/raft/<server.id>"` | Blank is treated as absent. | The server's storage directory: its Raft log, snapshots and durable server identity. A relative path resolves against the working directory. |
| `fsync` | boolean | `true` | Must be `true`: `Raft WAL fsync must be enabled for durability`. | Whether log writes are synced to disk. |

### 2.5 `server.raft.snapshot`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `enabled` | boolean | `true` | | Whether the server takes snapshots on its own schedule. |
| `threshold` | integer | `10000` | At least 1, even when snapshots are disabled. | Entries applied since the last snapshot that trigger a new one. |
| `checkIntervalMs` | integer | `60000` | At least 1000, even when snapshots are disabled. | How often the threshold is checked. |

Both rules share one message: `Snapshot threshold must be positive and check
interval at least 1000ms`.

### 2.6 `server.raft.io`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `poolSize` | integer | `10` | 1 to 100: `Raft I/O pool size must be between 1 and 100`. | Threads of the server's blocking-work pool (`qraft-worker`) and of the Raft transport. |
| `queueSize` | integer | `1000` | 10 to 100000: `Raft I/O queue size must be between 10 and 100000`. | Queue capacity of the same pools. |

### 2.7 `server.raft.membership`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `promotionStabilizationMs` | integer | `10000` | Zero or more. | How long a non-voter must stay healthy before it is promoted to voter. |
| `promotionMaxTrailingEntries` | integer | `250` | Zero or more. | The most log entries a non-voter may trail the leader by and still be promoted. |
| `cleanup` | object | | Present means an object; `null` is refused. | See 2.8. |

### 2.8 `server.raft.membership.cleanup`

Automatic removal of failed voters.

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `enabled` | boolean | `true` | | Whether failed voters are removed automatically. |
| `failureTimeoutMs` | integer | `max(60000, server.raft.electionTimeoutMs)` | At least 1, and at least `server.raft.electionTimeoutMs`, even when cleanup is disabled. | How long a voter must be absent before it may be removed. |
| `intervalMs` | integer | `max(10000, server.raft.heartbeatIntervalMs)` | At least 1, and at least `server.raft.heartbeatIntervalMs`, even when cleanup is disabled. | How often cleanup runs. |
| `minVoters` | integer | `3` | At least 1. | Cleanup never takes the cluster below this many voters. |

### 2.9 `server.health`

The leader applies these settings. Followers apply the commands it commits.

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `expiryIntervalMs` | integer | `1000` | At least 1: `server.health.expiryIntervalMs must be positive`. | How often the leader evaluates health-check deadlines, unless a check falls due sooner. |
| `nodeTtlMs` | integer | `90000` | At least 1. | How long a node may go without a heartbeat before the leader marks it unreachable. Keep client `heartbeatIntervalMs` well below it. |
| `nodeReapAfterMs` | integer | `259200000` (72 hours) | Zero or more. 0 never reaps. | How long an unreachable node is kept before it and its services are removed. |

### 2.10 `server.shutdown`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `drainTimeoutMs` | integer | `5000` | 0 or more: `server.shutdown.drainTimeoutMs must not be negative, got: <n>`. | Time limit for each step of the drain phase, in which the HTTP API stops taking new work: readiness reports `draining` and new writes are refused with 503 `draining`. |
| `timeoutMs` | integer | `30000` | 0 or more: `server.shutdown.timeoutMs must not be negative, got: <n>`. | Time limit for each step of the later phases: waiting for work in progress, stopping services, closing resources. |

### 2.11 `server.telemetry`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `enabled` | boolean | `true` | | Whether OpenTelemetry tracing and the Prometheus endpoint run. |
| `otlpEndpoint` | string | `"http://localhost:4317"` | Non-blank. Not otherwise checked. | The OTLP gRPC endpoint traces are exported to. |
| `prometheusPort` | integer | `9464` | 1 to 65535, even when telemetry is disabled. Port 0 is refused. While telemetry is enabled, must differ from the other listeners' ports (2.1). | Port of the Prometheus metrics endpoint. |
| `serviceName` | string | `"qraft-server"` | Non-blank. | The `service.name` resource attribute. |

### 2.12 `server.ui`

The embedded admin interface, served by the HTTP listener.

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `enabled` | boolean | `true` | | Whether the interface's route is registered. |
| `path` | string | `"/ui/"` | At least 3 characters, beginning and ending with `/`. Each segment is lowercase letters, digits and hyphens, starting with a letter or digit. The first segment must not be `v1`, `api`, `health`, `raft`, `status`, `metrics` or `debug`. | The route prefix. |
| `devAssetsDirectory` | string | none: the embedded assets | Non-blank. Needs `enabled` true. The directory must exist and hold `index.html`. | For development only: serves the interface's files from this directory in place of the embedded ones. |

### 2.13 `server.operator`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `token` | string | none | Blank is treated as absent. At least 16 characters: `server.operator.token must be at least 16 characters`. Not allowed with `server.acl.enabled`: `operator:write replaces it`. | The shared token a Raft operator's removal of a server must carry while ACLs are disabled. Without it, every removal is refused. Never logged. |

### 2.14 `server.acl`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `enabled` | boolean | `false` | | Whether ACLs are enforced on the HTTP API and removals. |
| `defaultPolicy` | string | none | `allow` or `deny`. Required when `enabled` is true: `server.acl.defaultPolicy must be set when ACLs are enabled: allow or deny`. | The decision for a request that no rule covers. |
| `initialManagementTokenFile` | string | none | Blank is treated as absent. Needs `enabled` true. The file is read when the configuration is checked, and its trimmed content must be a UUID outside the range kept for built-ins (`00000000-0000-0000-0000-0000000000xx`). | Whenever this server becomes leader and no token has this secret, it installs a management token with the `global-management` policy. The secret is never logged. |

Startup warnings: with ACLs disabled, `deny` logs `CRITICAL
ACL_DENY_NOT_ENFORCED`, and otherwise the development profile logs that every
call is allowed. With ACLs enabled, a warning names each listener that does not
use TLS.

### 2.15 `server.security`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `profile` | string | `"development"` | `production` or `development`. `production` needs `server.acl.enabled` true and `server.acl.defaultPolicy` `deny`. | `production` refuses to start without that access control. `development` may run without ACLs, and also allows the `allow` default policy. |

### 2.16 `server.tls`

When `server.tls` is absent, no listener uses TLS. The material enables no
listener by itself: each listener has its own switch. The files are read at
start, checked every 30 seconds, and reloaded when one changes.

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `caFile` | string | | Required when `server.tls` is present: `server.tls.caFile is required`. Non-blank. | PEM bundle of the CA certificates that peers' certificates must chain to. |
| `certFile` | string | none | Set together with `keyFile`. Required when any listener below is enabled: `server.tls.certFile and server.tls.keyFile are required for server.tls.<listener>`. | PEM certificate chain this server presents. |
| `keyFile` | string | none | Set together with `certFile`. | PKCS #8 PEM private key of that certificate. |
| `minVersion` | string | `"TLSv1.2"` | `TLSv1.2` or `TLSv1.3`. | The lowest TLS version accepted. `TLSv1.2` allows 1.3 and 1.2. |
| `https.enabled` | boolean | `false` | | The HTTP listener serves HTTPS only. |
| `https.verifyIncoming` | boolean | `true` | | HTTPS requires a client certificate. |
| `raft.enabled` | boolean | `false` | | The Raft port uses mutual TLS. |
| `raft.verifyServerHostname` | boolean | `true` | | A peer's certificate must name the host this server dialled. |

`https` and `raft` are objects. They accept only the keys shown.

### 2.17 `server.audit`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `path` | string | none: no audit file | Blank is treated as absent. | File the audit events are written to, one JSON line each. The admin interface's audit view reads it. |
| `rotateBytes` | integer | `25165824` (24 MiB) | Greater than 0: `server.audit.rotateBytes must be positive`. | Before a line would take the file past this size, the file is moved aside, named for the time. |
| `maxFiles` | integer | `15` | Zero or more: `server.audit.maxFiles must be zero or more`. | How many moved files are kept. The oldest are removed first. |

### 2.18 `server.dns`

DNS is off when `server.dns` is absent. When it is present, `port` is required.

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `host` | string | `server.http.host` | Non-blank. | Address the DNS interface listens on. |
| `port` | integer | | Required: `server.dns.port is required to serve DNS`. 0 to 65535; 0 takes any free port. Must differ from the other listeners' ports (2.1). | Port for both UDP and TCP. |
| `domain` | string | `"qraft"` | One or more DNS labels (letters, digits, hyphens; up to 63 characters each). Lowercased; a final dot is removed. | The domain answered. |
| `ttlSeconds` | integer | `0` | 0 to 2147483647. | Time to live of every record and of a negative answer. |
| `onlyPassing` | boolean | `false` | | Whether an instance with a warning is left out of answers. |
| `tokenFile` | string | none: reads carry no token | Non-blank. At start the file is read and must not be empty. | File of the token the server's DNS reads run under. |

### 2.19 `logging`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `directory` | string | `"./logs"` | Non-blank. | Directory of `qraft-server.log` and `qraft-server.json`. Rolled files go to `archive/` in it. A relative path resolves against the working directory. |

## 3. Client settings

A client document has these keys at its root:

```json
{
  "version": 1,
  "client": { },
  "servers": { },
  "catalog": { },
  "logging": { }
}
```

`client` and `servers` are required objects. `catalog` and `logging` are
optional.

### 3.1 `client`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `nodeName` | string | the host name | Non-blank. If absent, the host name must be readable. | The node's name in the catalog. |
| `dataDirectory` | string | | Required. A non-blank path. | The client's own directory. It is created if missing and locked (`client.lock`) so only one client uses it. It holds the node ID (`node-id`), the registrations the client keeps, and `acl-tokens.json` when token persistence is on. |
| `address` | string | the local host's IP address, or `127.0.0.1` if it cannot be read | Non-blank. | The address the node is registered with. It is also the address of a service registered through the local API without one. |
| `bindAddress` | string | the loopback address | An IP address. A host name is refused: `client.bindAddress must be an IP address, such as 127.0.0.1 or 0.0.0.0`. | Address the local API listens on, as Consul's `client_addr`. |
| `httpPort` | integer | `8080` | 0 to 65535; 0 takes any free port, and the client registers the port it bound. | Port of the local API. |
| `heartbeatIntervalMs` | integer | `30000` | At least 1. | How often the client sends its node's heartbeat and reconciles its services with the catalog. Keep it well below the servers' `server.health.nodeTtlMs`. |
| `shutdownTimeoutMs` | integer | `30000` | At least 1. | Time limit for stopping the client's work. |
| `datacenter` | string | `"default"` | Non-blank. | The node's datacenter. |
| `region` | string | `"default"` | Non-blank. | The node's region. |
| `acl` | object | absent: ACLs disabled | | See 3.5. |
| `tls` | object | absent | | See 3.6. |
| `dns` | object | absent: no DNS | | See 3.7. |

### 3.2 `servers`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `urls` | array of strings | | Required, non-empty. Each entry is a non-blank `http` or `https` URL with a host, and nothing after it but an optional `/`: no path, query, fragment or user information. Scheme and host are lowercased, and duplicates are dropped. | The servers' HTTP API addresses. |
| `requestTimeoutMs` | integer | `5000` | At least 1. | Time limit for each request to a server. |

### 3.3 `catalog`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `tenant` | string | `"default"` | Non-blank. | Tenant of the node's services. |
| `namespace` | string | `"default"` | Non-blank. | Namespace of the node's services. |
| `registrationRetryMinMs` | integer | `250` | At least 1, and not more than `registrationRetryMaxMs`. | Shortest delay before a failed call to the servers is retried. |
| `registrationRetryMaxMs` | integer | `30000` | At least 1. | Longest retry delay. Delays grow exponentially between the two, with jitter. |
| `contactFreshnessMs` | integer | `90000` | At least 1. | The client is ready only if it reached a server within this time. |
| `services` | array of objects | `[]` | | See 3.4. |

### 3.4 `catalog.services[]` and their `checks[]`

Each service:

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `id` | string | | Required, non-blank, unique among the file's services. | The service's ID. |
| `name` | string | | Required, non-blank. | The service's name. |
| `address` | string | | Required, non-blank. | The service's address. |
| `port` | integer | | Required, 1 to 65535. | The service's port. |
| `tags` | array of strings | `[]` | Each a non-blank string. | The service's tags. |
| `metadata` | object of strings | `{}` | Non-blank keys, string values. | The service's metadata. |
| `enabled` | boolean | `true` | | `false` leaves the service unregistered and removes it from the catalog if it is there. |
| `tokenFile` | string | none | Non-blank. At start the file is read and must hold a token of printable ASCII. | Token for this service's calls to the servers, in place of the `configFileRegistration` token. |
| `checks` | array of objects | `[]` | | The service's health checks. |

Each check has `id` and `type`, and these common keys:

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `id` | string | | Required, non-blank. Unique within the service and across the client. Must not begin with `_service_maintenance:` or `_node_maintenance:`. | The check's ID. |
| `type` | string | | Required: `http`, `tcp` or `ttl`. | The kind of check. |
| `required` | boolean | `true` | | Whether the check counts toward the client's readiness. A required check must have reported, and its latest result must not be critical. |
| `deregisterAfterMs` | integer | `0` | Zero or more. 0 never deregisters. | How long the check may stay expired at the servers before its service is deregistered. |

`http` checks also take:

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `url` | string | | Required. An absolute `http` or `https` URL with a host and no user information. | The URL probed. |
| `intervalMs` | integer | `10000` | At least 1. | Time between probes. |
| `timeoutMs` | integer | `min(2000, intervalMs)` | Greater than 0, and not more than `intervalMs`. | Time limit for one probe. |
| `ttlMs` | integer | `3 × intervalMs` | Greater than `intervalMs`. | How long the servers hold each published result. |

`tcp` checks take `intervalMs`, `timeoutMs` and `ttlMs` as above, and also:

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `address` | string | the service's `address` | Non-blank. | Host connected to. |
| `port` | integer | the service's `port` | 1 to 65535. | Port connected to. |
| `warnAfterMs` | integer | `0` | Zero or more, and less than `timeoutMs`. | A connection that takes at least this long is a warning. 0 never warns. |

`ttl` checks take only the common keys and:

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `ttlMs` | integer | | Required, greater than 0: `ttlMs is required for a ttl check`. | The application must report the check's status within this time, or the check turns critical. |

A key that does not belong to the check's type is refused, for example
`warnAfterMs` on an `http` check.

### 3.5 `client.acl`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `enabled` | boolean | `false` | | Whether the client enforces ACLs on its local API. |
| `defaultPolicy` | string | none | `allow` or `deny`. Required when `enabled` is true. | Policy for what no rule decides. Set it to the servers' `server.acl.defaultPolicy`. |
| `downPolicy` | string | `"extend-cache"` | `extend-cache`, `async-cache`, `allow` or `deny`. | What the client decides when no server answers a token lookup, with Consul's meanings: `extend-cache` uses what is cached however old, and the default policy where nothing is; `async-cache` does the same while it asks again in the background; `allow` and `deny` apply to everything not freshly cached. |
| `tokenTtlMs` | integer | `30000` | At least 1. | How long a resolved token is cached. |
| `policyTtlMs` | integer | `30000` | At least 1. | Cache lifetime when the token has policies, if shorter than `tokenTtlMs`. |
| `roleTtlMs` | integer | `30000` | At least 1. | Cache lifetime when the token has roles, if shorter. |
| `enableTokenPersistence` | boolean | `false` | | Tokens set through `PUT /v1/client/token/{kind}` are kept in `acl-tokens.json` in the data directory, and take their file's place at the next start. |
| `tokens` | object | `{}` | Keys: `clientFile`, `configFileRegistrationFile`, `defaultFile`, `recoveryFile`. Each value is a non-blank path. At start each file is read and must hold a token. | Files of the client's four tokens. See below. |

The tokens:

| Token | Used for | Falls back to |
|---|---|---|
| `default` | A request with no token, and DNS reads. | none (anonymous) |
| `client` | The client's own calls for its node. | `default` |
| `configFileRegistration` | The services and checks in this file, unless a service has its own `tokenFile`. | `default` |
| `recovery` | The client's own endpoints when no server answers. | none |

With ACLs disabled, `deny` logs `CRITICAL ACL_DENY_NOT_ENFORCED` at start.

### 3.6 `client.tls`

Used to call `https` server URLs. Without it, an `https` URL is verified
against the JVM's default trust store and the client presents no certificate.
The files are read at start, checked every 30 seconds, and reloaded when one
changes.

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `caFile` | string | | Required when `client.tls` is present. Non-blank. | PEM bundle of the CA certificates the servers' certificates must chain to. |
| `certFile` | string | none | Set together with `keyFile`. | PEM certificate chain the client presents. Needed when the servers keep `verifyIncoming` true, the default. |
| `keyFile` | string | none | Set together with `certFile`. | PKCS #8 PEM private key of that certificate. |
| `minVersion` | string | `"TLSv1.2"` | `TLSv1.2` or `TLSv1.3`. | The lowest TLS version used. |

### 3.7 `client.dns`

The same as `server.dns`, with two differences: `host` defaults to
`client.bindAddress`, and there is no `tokenFile` (it is refused as unknown).
The client sends its DNS reads to the servers under its `default` token.

| Key | Type | Default | Rules |
|---|---|---|---|
| `host` | string | `client.bindAddress` | Non-blank. |
| `port` | integer | | Required. 0 to 65535. |
| `domain` | string | `"qraft"` | One or more DNS labels. |
| `ttlSeconds` | integer | `0` | 0 to 2147483647. |
| `onlyPassing` | boolean | `false` | |

### 3.8 `logging`

| Key | Type | Default | Rules | Meaning |
|---|---|---|---|---|
| `directory` | string | `"./logs"` | Non-blank. | Directory of `qraft-client.log` and `qraft-client.json`. Rolled files go to `archive/` in it. |

## 4. Examples

### 4.1 A single server

This is `docker/config/single/server.json`, laid out for reading. It runs one
server with DNS on port 8600 and telemetry off.

```json
{
  "version": 1,
  "server": {
    "id": "server-dev",
    "http": { "host": "0.0.0.0", "port": 8080 },
    "raft": {
      "port": 9080,
      "nodes": { "server-dev": "server:9080" },
      "electionTimeoutMs": 3000,
      "heartbeatIntervalMs": 500,
      "storage": { "type": "raftlog", "path": "/app/data", "fsync": true },
      "snapshot": { "enabled": true, "threshold": 10000, "checkIntervalMs": 1000 },
      "logHardLimit": 100000,
      "io": { "poolSize": 10, "queueSize": 1000 }
    },
    "dns": { "port": 8600 },
    "telemetry": { "enabled": false }
  },
  "logging": { "directory": "/app/logs" }
}
```

Every setting it leaves out takes its default. For a three-node cluster, see
`docker/config/three-node/`. `three-node-acl/` adds ACLs and
`three-node-tls/` adds TLS on all three listeners.

### 4.2 A client with one service and one check

The client and service come from `docker/config/client.json`. The check is the
HTTP check from `docker/config/client-acceptance/client.json`, which probes the
client's own `/health/live` route.

```json
{
  "version": 1,
  "client": {
    "nodeName": "client-example",
    "dataDirectory": "/app/data",
    "address": "client-example",
    "httpPort": 8080,
    "datacenter": "dc1",
    "region": "eu-west"
  },
  "servers": {
    "urls": ["http://server:8080"]
  },
  "catalog": {
    "services": [
      {
        "id": "web",
        "name": "web",
        "address": "client-example",
        "port": 8080,
        "tags": ["http"],
        "metadata": { "owner": "platform" },
        "checks": [
          {
            "id": "http",
            "type": "http",
            "url": "http://127.0.0.1:8080/health/live",
            "intervalMs": 1000,
            "timeoutMs": 1000,
            "ttlMs": 5000,
            "deregisterAfterMs": 5000
          }
        ]
      }
    ]
  },
  "logging": { "directory": "/app/logs" }
}
```

## 5. Settings across servers

### 5.1 Must be the same on every server

| Setting | Why |
|---|---|
| `server.raft.nodes` | A new cluster forms only when every listed server answers, none holds Raft state, each answers under the name it is listed by, and every one lists exactly the same servers with the same addresses. If a name or list disagrees, the server logs `Bootstrap refused` and tries again later; if a listed server does not answer, it waits. A server that finds a listed server already in a cluster asks to join it. Once a server holds Raft state, the replicated configuration decides membership. Its own entry still gives the Raft address it advertises. |
| `server.operator.token` | A removal is checked on the server that receives it and again on the leader. |

### 5.2 Should be the same on every server

The code does not compare these between servers, but each one is applied by
whichever server receives a request or leads.

| Setting | Why |
|---|---|
| `server.acl.enabled`, `server.acl.defaultPolicy`, `server.security.profile` | Each server enforces its own ACL settings on the requests it receives. |
| `server.acl.initialManagementTokenFile` (the file's content) | Whichever server leads installs the secret in its own file if no token has it. Different secrets would give several management tokens. |
| `server.tls.raft.enabled`, `server.tls.caFile` | Raft peers connect to each other's Raft ports. A peer must use the same TLS setting and present a certificate the other trusts. |
| `server.raft.membership.*`, `server.health.*`, `server.raft.logHardLimit` | The leader applies them: promotion, cleanup, health expiry and node reaping, and the log limit. They change with leadership if they differ. |
| `client.acl.defaultPolicy` (on each client) | Set it to the servers' default policy. |

### 5.3 Different on each server

| Setting | Why |
|---|---|
| `server.id` | Each server's own name. It must be its own key in `server.raft.nodes`. |
| `server.raft.storage.path` | Each server needs a directory of its own, so servers on one host need different paths. The open log holds a lock on the directory. |
| `server.tls.certFile`, `server.tls.keyFile` | Each server presents its own certificate. With `server.tls.raft.verifyServerHostname` it must name the host peers dial. |

Ports, `server.http.host`, `server.dns`, `server.audit`, `server.ui`,
`server.telemetry` and `logging` are local to each server and may differ. A
server's entry in `server.raft.nodes` must reach its Raft port, because peers
dial that address.
