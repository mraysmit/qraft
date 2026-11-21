# Qraft HTTP API Reference

**Last updated:** 2026-11-21

This is the complete reference for Qraft's two HTTP APIs: the server API that every server in server mode serves, and
the local API that a client in client mode serves to the applications on its host. It is derived from the route code
in `src/main/java/dev/mars/qraft/server/http/` and `src/main/java/dev/mars/qraft/client/service/`.

## 1. Overview

### 1.1 The two APIs

| | Server API | Client local API |
|---|---|---|
| Served by | Every server, on its HTTP listener | A client, on its local listener |
| Address | Every interface, port `server.http.port` (default `8080`) | `client.bindAddress` (default the loopback address), port `client.httpPort` (default `8080`) |
| Transport | HTTP, or HTTPS when `server.tls.https.enabled` is set | HTTP only |
| Holds state | Yes: answers from its replicated state | No: keeps its own service and check definitions, and forwards every catalog, key/value, session and tenancy request to the servers |

The server's admin interface, when enabled, is served on the same listener as the server API (section 4.12).

A server also has listeners that are not part of this API: the Raft port (`server.raft.port`, default `9080`), which
carries write forwarding between servers (section 3), and, with telemetry enabled, a Prometheus listener
(`server.telemetry.prometheusPort`, default `9464`).

### 1.2 Requests and answers

- Bodies are JSON unless a route says otherwise. JSON answers carry `Content-Type: application/json`.
- A request body may be at most 512 KiB (524,288 bytes). A longer body is refused with 413 `request_too_large` before
  the route runs, whether or not the request declares its length. This applies to both APIs.
- Each route accepts only the methods its table lists. Any other method is answered 405 `method_not_allowed`.
- A path that no route serves is answered 404 `not_found` in the error envelope.
- Most server routes refuse a query parameter they do not take with 400 `invalid_query`, rather than ignore it.
  These ignore the query: the health routes, the catalog writes (`/v1/catalog/register`, `/v1/catalog/deregister`,
  `/v1/catalog/node/heartbeat`, `/v1/catalog/check/observe`), `/v1/acl/bootstrap`, and the auth method, binding rule,
  login and logout routes, apart from `GET /v1/acl/binding-rules`, which takes `authmethod` and refuses any other
  parameter. `/raft/status`, `/v1/operator/raft/configuration`, the autopilot, members, storage and configuration
  reads (sections 4.9 and 4.10) and `/v1/internal/ui/audit` accept the read options of section 2.1 without applying
  them. The client's own routes
  (sections 5.2 and 5.3) ignore parameters they do not use, apart from `/v1/client/metrics`.

### 1.3 Request IDs

Every request has an ID: the value of its `X-Request-Id` header, or a new UUID when it sends none. The answer carries
it back in `X-Request-Id`, the error envelope repeats it as `requestId`, and it is the `requestId` of the logging
context while the request is handled. A write forwarded to the leader, and a request the client forwards to a
server, keeps the same ID.

### 1.4 The error envelope

Every error that a route answers is one JSON object:

```json
{
  "code": "leader_unavailable",
  "error": "leader_unavailable",
  "message": "A consistent read is answered by the leader, which this server is not, or could not confirm",
  "retryable": true,
  "requestId": "2f0c6c1e-4d4e-4c2a-9a59-0d7c8b9c2a10",
  "leaderId": "server-2"
}
```

| Field | Meaning |
|---|---|
| `code` | A stable, machine-readable code |
| `error` | The same code, repeated |
| `message` | A human-readable description |
| `retryable` | Whether the same request may succeed if sent again later |
| `requestId` | The request's ID |
| further fields | Details some errors add, such as `leaderId`, `conditions`, `resetIndex`, `serviceId`, `checkId` or `node` |

Common codes:

| Status | Code | Meaning |
|---|---|---|
| 400 | `invalid_query` | A query parameter the route does not take, or a malformed one |
| 400 | `invalid_scope` | A blank or invalid `X-Qraft-Tenant` or `X-Qraft-Namespace` |
| 401 | `acl_disabled` | An ACL management route was called while ACLs are disabled |
| 403 | `token_not_found` | The presented token names no token |
| 403 | `permission_denied` | The token lacks the permission the route needs |
| 404 | `not_found` | No route serves the path |
| 405 | `method_not_allowed` | The route does not take the method |
| 413 | `request_too_large` | The body is longer than 512 KiB |
| 503 | `draining` | The server is draining and takes no new work |
| 503 | `catalog_unavailable` | The server has no Raft state to answer from |
| 503 | `leader_unavailable` | No leader could take the request; it was not applied (section 3) |
| 503 | `outcome_unknown` | A write was sent but not answered in time; it may have been applied (section 3) |

Routes add their own codes, listed in their sections.

The admin interface's files (section 4.12) are the exception: a missing file is a plain 404 and a wrong method a
plain 405, without the envelope.

### 1.5 Authentication and ACLs

A request presents a token in `X-Qraft-Token`, or else as `Authorization: Bearer <secret>`, the scheme in any case,
on the server API and the client local API alike. `X-Qraft-Token` wins when both are sent.
Qraft never reads a token from the query string.

With ACLs disabled (`server.acl.enabled` is `false`, the default), every request is unrestricted, whatever token it
presents.

With ACLs enabled:

- A request without a token acts as the anonymous token.
- A token whose secret matches no token is refused with 403 `token_not_found`.
- A route that needs a permission it lacks answers 403 `permission_denied`.
- A list read leaves out what the token may not read and sets `X-Qraft-Results-Filtered-By-ACLs: true` when it left
  anything out.

Permissions are written `resource:access`. The resources are `node`, `service`, `client`, `operator`, `acl`, `key`
and `session`; `service` and `key` are scoped to a tenant and namespace. The tables below give each route's
permission. "Filtered" means the route answers 200 and leaves out what the token may not read.

The health routes, the admin interface's files, `PUT /v1/acl/bootstrap` and `POST /v1/acl/login` take no token.

### 1.6 Scope headers

| Header | Meaning |
|---|---|
| `X-Qraft-Tenant` | The tenant a request acts in; `default` when absent |
| `X-Qraft-Namespace` | The namespace a request acts in; `default` when absent |
| `X-Qraft-Node` | The node a catalog write is for; required by the catalog writes |
| `X-Qraft-Node-ID` | The node's UUID, in canonical lower-case form; optional |

A scope header that is present must not be blank and must be a valid tenant or namespace name, or the request is
refused with 400 `invalid_scope`. The headers say where a request acts; they authenticate nothing.

## 2. Common read options

### 2.1 Query parameters

The catalog, health, key/value, session and tenancy reads, and the admin interface's services, locks, sessions and
scopes reads, take these parameters:

| Parameter | Meaning |
|---|---|
| `stale` | Answer from this server's applied state |
| `consistent` | Answer only from the leader, after it confirms it still leads |
| `index` | Block until the read's index is greater than this value (a whole number, 0 or more) |
| `wait` | The longest a blocking read waits: a number and one unit, `ms`, `s`, `m` or `h`, such as `30s` or `5m`. Default `5m`, at most `10m` |

### 2.2 Consistency modes

| Mode | Behaviour |
|---|---|
| default (neither parameter) | This server answers from its applied state |
| `stale` | The same as the default |
| `consistent` | Only the leader answers: it passes a read barrier, which confirms it still leads and that it has applied what was committed when the read began, within the Raft timeout (5 seconds). Any other server, or a leader that cannot confirm, answers 503 `leader_unavailable` with `leaderId` and `X-Qraft-Leader-Id` when it knows the leader |

Giving both `stale` and `consistent` is refused with 400 `invalid_query`. Reads are not forwarded (section 3), so a
`consistent` read must be sent to the leader.

### 2.3 Blocking queries

With `index` above 0, the read waits until the index it answers with is greater than `index`, or until `wait` passes,
plus a random extra of up to one sixteenth of `wait`. Either way it then answers 200 with the current state and its
index; reaching the wait is not an error. With `index` absent or 0 the read answers at once. If the server stops
while a read waits, the read answers 503 `draining`.

The index a read waits on is the one it answers in `X-Qraft-Index`:

- the key/value state's last index for `/v1/kv` and `/v1/internal/ui/locks`, which only a key/value change moves;
- the server's last applied Raft index for every other read.

### 2.4 Response headers

| Header | Set on | Meaning |
|---|---|---|
| `X-Request-Id` | Every answer | The request's ID (section 1.3) |
| `X-Qraft-Answered-By` | Every answer of the server API | The node ID of the server that answered; on a forwarded write, the leader |
| `X-Qraft-Index` | Reads, and writes that report it | The index described in section 2.3 |
| `X-Qraft-Consistency` | Reads that take the read options | The mode applied: `default`, `stale` or `consistent` |
| `X-Qraft-Known-Leader` | Reads that take the read options | `true` when the server knows a leader, else `false` |
| `X-Qraft-Last-Contact` | Reads that take the read options | Milliseconds since this server last heard from the leader, when it knows |
| `X-Qraft-Leader-Id` | `leader_unavailable` and `outcome_unknown` answers | The leader this server knows, when it knows one |
| `X-Qraft-Results-Filtered-By-ACLs` | Filtered reads | `true` when the answer leaves out something the token may not read |
| `X-Qraft-Forwarded-To` | Forwarded writes | The leader the write was forwarded to |

Request header `X-Qraft-Forwarded-By` is described in section 3.

## 3. Write forwarding

Any server takes a write. A request with method `PUT`, `POST`, `DELETE` or `PATCH`, on any route, that reaches a
follower is forwarded to the leader over the Raft port when the follower knows the leader. `GET` requests are never
forwarded: a follower answers them itself. Nor are the three `POST` routes that only read,
`/v1/internal/acl/resolve`, `/v1/internal/acl/authorize` and `/v1/acl/explain`: the server asked answers them from
its own applied state.

The follower:

1. sends the method, path, query, body and headers, apart from hop-by-hop headers, to the leader, adding
   `X-Qraft-Forwarded-By` with its own node ID; the token goes with the request, and the leader authorizes it;
2. waits up to twice the Raft timeout (10 seconds by default) for the leader's answer, and gives up sooner when it
   stops following that leader;
3. after a successful answer that carries `X-Qraft-Index`, waits up to the Raft timeout to apply that index itself, so
   that a read sent to it next sees the write;
4. answers with the leader's status, headers and body, adding `X-Qraft-Forwarded-To`.

A server that drains does not forward: the route answers 503 `draining`. A follower that knows no leader handles the
write itself; it cannot commit it, and answers with one of the codes below. A write that already carries
`X-Qraft-Forwarded-By` and reaches a server that does not lead is refused with 503 `leader_unavailable`, so a write is
never forwarded twice.

On the leader itself, a write waits up to the Raft timeout to commit.

| Code | Status | When | Applied? |
|---|---|---|---|
| `leader_unavailable` | 503 | No leader is known; the leader could not be reached, so the write was never sent; the write was withdrawn before it was sent; or a forwarded write reached a server that does not lead | No: the request may be sent again, to any server |
| `outcome_unknown` | 503 | The write was sent, or proposed, but no answer came in time, or the follower stopped following the leader while it waited | Perhaps: check before sending it again |

`leader_unavailable` is `retryable: true`. `outcome_unknown` is `retryable: false`, since the write may have been
applied: a caller reads the state before sending it again, or replays it only when it is idempotent. Both carry
`leaderId` and `X-Qraft-Leader-Id` when a leader is known.

## 4. Server routes

In the tables, "read options" means the parameters of section 2.1. A `{name}` in a path is a path parameter.

### 4.1 Health and status

| Method | Path | Parameters | ACL | Purpose |
|---|---|---|---|---|
| GET | `/health/live` | none | none | Answers 200 `{"status":"alive"}` whenever the process runs, draining included |
| GET | `/health/ready` | none | none | Answers 200 `{"status":"ready"}`, or 503 `not_ready` naming the unmet conditions |
| GET | `/raft/status` | none | `operator:read` | This server's Raft state: `nodeId`, `serverId`, `state`, `term`, `leaderId`, `commitIndex`, `lastApplied`, `lastLogIndex`, `snapshotLastIndex`, `fenced`, `fenceReason`, `removed`, and `storageBytes` and `snapshotAgeSeconds` when known |

`/health/ready` answers 503 `not_ready` with a `conditions` list holding each unmet condition, in this order:

| Condition | Meaning |
|---|---|
| `draining` | The server is draining |
| `unavailable` | Its Raft state could not be read |
| `fenced` | Its Raft node is fenced |
| `recovering` | It has not finished recovery (reported only when not fenced) |
| `removed` | Its removal from the configuration has committed |
| `no_leader` | It is neither the leader nor a follower of a known leader |

`/raft/status` answers 503 `raft_unavailable` when the Raft state cannot be read in time.

### 4.2 Catalog and health

Catalog reads are confined to the scope of `X-Qraft-Tenant` and `X-Qraft-Namespace`. An instance is readable only
with both `node:read` on its node and `service:read` on its service.

| Method | Path | Parameters | ACL | Purpose |
|---|---|---|---|---|
| PUT | `/v1/catalog/register` | none; headers `X-Qraft-Node` (required), `X-Qraft-Node-ID`, scope | `node:write` on the node when the registration creates or changes it, and on any node whose name or ID it takes over; `service:write` on the service, and on the service of the instance with the same ID that it replaces | Registers the node (`node`), a service (`service`), or both |
| PUT | `/v1/catalog/deregister` | none; header `X-Qraft-Node` (required) | `node:write` on the node allows removing anything on it; without it, a service needs `service:write` on its name | `{"serviceId": "..."}` removes that service; `{}` removes the node with everything on it |
| PUT | `/v1/catalog/check/observe` | none; header `X-Qraft-Node` (required) | `service:write` on the check's service | Records a check's observation |
| GET | `/v1/catalog/services` | read options | filtered by `service:read` | The services in scope, each with its instances' tags |
| GET | `/v1/catalog/service/{name}` | read options | filtered | The service's instances, each with `createIndex` and `modifyIndex` |
| GET | `/v1/health/service/{name}` | `passing` (`true`, `false`, or no value for `true`), read options | filtered | The service's instances with their checks; `passing` keeps only instances whose checks pass on a node that is not unreachable |
| GET | `/v1/health/state/{state}` | read options | filtered | Every check in scope in `state`, which is `any`, `unknown`, `passing`, `warning`, `critical` or `maintenance`, ordered by service, instance and check |

Register body: `node` (`address`, `datacenter`, `region`, `metadata`) and `service` (`serviceId`, `serviceName`,
`address`, `port`, `tags`, `metadata`, `datacenter`, `region`, `enabled`, `health`, `checks`, where `checks` lists the
declared check IDs). Unknown fields are refused. The whole body is checked before anything is replicated. The answer
is `{"node": ..., "registered": true, "service": {...}}`, with the halves that were registered.

Observe body: `serviceId`, `checkId`, `status`, `sequenceNumber`, `observedAt`, `ttlMillis`, `required`, `output` and
`deregisterAfterMillis`.

Codes of these routes:

| Status | Code | Route | Meaning |
|---|---|---|---|
| 400 | `invalid_registration`, `invalid_deregistration`, `invalid_observation` | the writes | The body or headers are invalid |
| 400 | `service_name_required` | `/v1/catalog/service`, `/v1/health/service` | No name after the path |
| 400 | `invalid_state` | `/v1/health/state` | Not one of the states listed |
| 404 | `service_not_found` | deregister, observe | Unknown service ID |
| 404 | `check_not_declared` | observe | The registration does not declare this check |
| 404 | `tenant_not_found`, `namespace_not_found` | register | The scope does not exist |
| 409 | `node_name_reserved` | the writes | A node of another ID holds the name; details `node` and `holder` |
| 409 | `registration_rejected` | register | The replicated catalog did not accept the registration |
| 409 | `stale_observation` | observe | Older than the accepted observation; detail `currentSequenceNumber` |

### 4.3 Nodes

| Method | Path | Parameters | ACL | Purpose |
|---|---|---|---|---|
| PUT | `/v1/catalog/node/heartbeat` | none; header `X-Qraft-Node` (required); body `sequenceNumber`, `registrationId` | `node:write` on the node | Records a heartbeat, which makes the node healthy |
| GET | `/v1/catalog/nodes` | read options | filtered by `node:read` | The registered nodes, in name order |
| GET | `/v1/catalog/node-services/{node}` | read options | `node:read` on the node, then filtered by `service:read` | The node's service instances in scope; empty without `node:read` |

The node routes take no longer path: `/v1/catalog/nodes/x` is 404 `not_found`. A heartbeat answers 404
`node_not_found` for an unregistered node, 409 `registration_superseded` when a later registration of the node
replaced the one it names (the client should register again), and 409 `stale_heartbeat` when it is out of order.
`/v1/catalog/node-services` without a node answers 400 `node_name_required`.

### 4.4 Key/value

The scope is the request's `X-Qraft-Tenant` and `X-Qraft-Namespace`. Entries use Consul's field names.

| Method | Path | Parameters | ACL | Purpose |
|---|---|---|---|---|
| GET | `/v1/kv/{key}` | `recurse`, `keys`, `separator`, `raw`, read options | `key:read` on the key; with `recurse` or `keys`, filtered | One entry; with `recurse`, every entry under the prefix; with `keys`, their names, cut after `separator`; with `raw`, the value alone |
| PUT | `/v1/kv/{key}` | `flags`, `cas`, `acquire`, `release` | `key:write` on the key | Writes the body as the value; answers `true` or `false` |
| DELETE | `/v1/kv/{key}` | `recurse`, `cas` | `key:write` on the key; with `recurse`, write on every key under the prefix | Deletes the key, or with `recurse` the tree; answers `true` or `false` |

- An entry is `Key`, `Value` (Base64), `Flags`, `CreateIndex`, `ModifyIndex`, `LockIndex`, and `Session` while a
  session holds it. A one-key read answers a list of one entry.
- `raw` answers the value with `Content-Type: application/octet-stream`; it cannot be given with `recurse` or `keys`.
  `separator` is given only with `keys`.
- `flags` is an unsigned 64-bit number. `cas` is an index, 0 or more: the write or delete succeeds only if the key's
  `ModifyIndex` matches, and with 0 only if the key does not exist.
- `acquire={session}` locks the key for the session, and `release={session}` unlocks it. `acquire`, `release` and
  `cas` are each given alone.
- `/v1/kv` with `recurse` or `keys` and no key reads from the root.

Codes: 400 `key_required` (no key where one is needed), 400 `invalid_session` (the named session does not exist),
404 `key_not_found` (no such key, or nothing readable under the prefix), 404 `tenant_not_found` or
`namespace_not_found` (a write to a scope that does not exist), 409 `kv_rejected` (the replicated state refused the
write).

### 4.5 Sessions

| Method | Path | Parameters | ACL | Purpose |
|---|---|---|---|---|
| PUT | `/v1/session/create` | none | `session:write` on the session's node | Creates a session; answers `{"ID": "..."}` |
| PUT | `/v1/session/destroy/{id}` | none | `session:write` on the session's node | Destroys the session; answers `true` |
| PUT | `/v1/session/renew/{id}` | none | `session:write` on the session's node | Renews the session's TTL on the leader; answers a list of the session, or 404 `session_not_found` |
| GET | `/v1/session/info/{id}` | read options | filtered by `session:read` on the session's node | The session, as a list of none or one |
| GET | `/v1/session/node/{node}` | read options | filtered | The node's sessions |
| GET | `/v1/session/list` | read options | filtered | Every session |

Create body:

| Field | Meaning |
|---|---|
| `Node` | The node the session is bound to; required on the server (400 `node_required` without it) |
| `Name` | A name |
| `TTL` | A duration from `10s` to `24h`, or empty for none |
| `LockDelay` | A duration such as `15s`, or a number: below 1000 it is seconds, otherwise nanoseconds. Default `15s`; anything above `60s` is taken as `60s` |
| `Behavior` | `release` (default) or `delete` |

A session is answered with `ID`, `Name`, `Node`, `LockDelay` (nanoseconds), `Behavior`, `TTL` (as Go writes a
duration, or empty), `CreateIndex` and `ModifyIndex`. The writes take no query parameter. Other codes: 400
`invalid_json`, 400 `invalid_session`, 400 `node_not_found` (create for an unknown node), 409 `session_rejected`.

Only the leader keeps session timers, so a renewal is always answered by the leader: a follower forwards it like any
other write.

### 4.6 Tenancy

| Method | Path | Parameters | ACL | Purpose |
|---|---|---|---|---|
| PUT | `/v1/tenant` | none; body `Name`, `Description` | `operator:write` | Creates a tenant, with its `default` namespace |
| GET | `/v1/tenants` | read options | `operator:read` | Lists the tenants |
| GET | `/v1/tenant/{name}` | read options | `operator:read` | Reads a tenant |
| PUT | `/v1/tenant/{name}` | none; body `Description` | `operator:write` | Changes the tenant's description |
| DELETE | `/v1/tenant/{name}` | none | `operator:write` | Deletes the tenant and its namespaces; answers `true` |
| PUT | `/v1/namespace` | none; body `Name`, `Description`, `Tenant` | `operator:write` | Creates a namespace in `Tenant`, else in the `X-Qraft-Tenant` tenant, else `default` |
| GET | `/v1/namespaces` | read options | `operator:read` | Lists the namespaces of the `X-Qraft-Tenant` tenant |
| GET | `/v1/namespace/{name}` | read options | `operator:read` | Reads a namespace of the `X-Qraft-Tenant` tenant |
| PUT | `/v1/namespace/{name}` | none; body `Description` | `operator:write` | Changes the namespace's description |
| DELETE | `/v1/namespace/{name}` | none | `operator:write` | Deletes the namespace with its services, checks and keys; answers `true` |

A tenant is answered as `Name`, `Description`, `CreateIndex`, `ModifyIndex`; a namespace adds `Tenant`. Codes: 404
`tenant_not_found` and `namespace_not_found`, 409 `tenant_exists` and `namespace_exists`, 400 `invalid_json` and
`invalid_request`, and the state machine's other refusals as 400 with their own code.

### 4.7 ACL tokens, policies and roles

With ACLs disabled, every route in this section answers 401 `acl_disabled`. Apart from the parameters listed, these
routes take no query parameter, the read options included. A token's secret is answered only by the bootstrap,
create or clone that made it; every later answer shows `<hidden>`.

| Method | Path | Parameters | ACL | Purpose |
|---|---|---|---|---|
| PUT | `/v1/acl/bootstrap` | none; optional body `{"bootstrapSecret": "<uuid>"}` | none | Creates the first management token, once |
| PUT | `/v1/acl/token` | none | `acl:write` | Creates a token; answers it with its secret |
| GET | `/v1/acl/tokens` | `policy`, `role`, `servicename` | `acl:read` | Lists tokens, narrowed by policy ID, role ID or service identity |
| GET | `/v1/acl/token/self` | none | none beyond a valid token | The presenting token |
| GET | `/v1/acl/token/{accessorId}` | none | `acl:read` | Reads a token |
| PUT | `/v1/acl/token/{accessorId}` | none | `acl:write` | Updates a token's description, links and identities |
| PUT | `/v1/acl/token/{accessorId}/clone` | none; optional body `description` | `acl:write` | Copies a token under a new accessor and secret |
| DELETE | `/v1/acl/token/{accessorId}` | none | `acl:write` | Deletes a token; a token cannot delete itself (400 `self_deletion`) |
| PUT | `/v1/acl/policy` | none | `acl:write` | Creates a policy |
| GET | `/v1/acl/policies` | none | `acl:read` | Lists policies |
| GET | `/v1/acl/policy/{id}` | none | `acl:read` | Reads a policy |
| GET | `/v1/acl/policy/name/{name}` | none | `acl:read` | Reads a policy by name |
| PUT | `/v1/acl/policy/{id}` | none | `acl:write` | Updates a policy |
| DELETE | `/v1/acl/policy/{id}` | none | `acl:write` | Deletes a policy |
| PUT | `/v1/acl/role` | none | `acl:write` | Creates a role |
| GET | `/v1/acl/roles` | `policy` | `acl:read` | Lists roles, narrowed by policy ID |
| GET | `/v1/acl/role/{id}` | none | `acl:read` | Reads a role |
| GET | `/v1/acl/role/name/{name}` | none | `acl:read` | Reads a role by name |
| PUT | `/v1/acl/role/{id}` | none | `acl:write` | Updates a role |
| DELETE | `/v1/acl/role/{id}` | none | `acl:write` | Deletes a role |
| POST | `/v1/acl/explain` | none | `acl:read` | Explains the decision for one token and one request |

Bodies use camel-case field names; unknown fields are refused.

| Body | Fields |
|---|---|
| Token | `accessorId`, `secretId` (UUIDs outside the reserved range; generated when absent), `description`, `policies` and `roles` (each link `{"id"}` or `{"name"}`), `nodeIdentities` (`nodeName`), `serviceIdentities` (`serviceName`, `tenant`, `namespace`), and `expirationTime` (ISO-8601) or `expirationTtl` (such as `30m` or `1h30m`), not both |
| Policy | `id`, `name`, `description`, `rules` (a JSON object, or a string holding one) |
| Role | `id`, `name`, `description`, `policies`, `nodeIdentities`, `serviceIdentities` |
| Explain | `accessorId` (required), `resource`, `name`, `access` (`read` or `write`), `tenant`, `namespace` |

An update cannot change a token's secret (400 `secret_immutable`) or its expiry (400 `expiry_immutable`). The
explain answer gives
`allowed`, `defaultPolicy`, and `decidedBy`, which is `rule`, with the deciding `rule` and the `policy`, `role`,
`nodeIdentity` or `serviceIdentity` it came from, or `default`.

Bootstrap: with ACLs disabled it answers 401 `acl_disabled`. While a management token configured by
`server.acl.initialManagementTokenFile` is not yet installed, it answers 503 `management_token_pending`. After the first
bootstrap it answers 403 `permission_denied` with `resetIndex`; writing that index to the file `acl-bootstrap-reset`
in the leader's data directory allows one more bootstrap.

Codes: 404 `acl_not_found`; 400 `invalid_token`, `invalid_policy`, `invalid_role` or `invalid_explanation` for an
invalid body; 400 with the state machine's code for a link to a policy or role that does not exist; 409 for a
conflict, such as an ID, name or secret in use, or `acl_lockout` when after the change no token would hold
`acl:write` without an expiry; other refusals 400 with their code.

### 4.8 Auth methods, binding rules, login and logout

With ACLs disabled, every route in this section, login and logout included, answers 401 `acl_disabled`.

| Method | Path | Parameters | ACL | Purpose |
|---|---|---|---|---|
| PUT | `/v1/acl/auth-method` | none | `acl:write` | Creates an auth method |
| GET | `/v1/acl/auth-methods` | none | `acl:read` | Lists auth methods |
| GET | `/v1/acl/auth-method/{name}` | none | `acl:read` | Reads an auth method |
| PUT | `/v1/acl/auth-method/{name}` | none | `acl:write` | Updates an auth method; the path's name wins over the body's |
| DELETE | `/v1/acl/auth-method/{name}` | none | `acl:write` | Deletes an auth method; answers `true` |
| PUT | `/v1/acl/binding-rule` | none | `acl:write` | Creates a binding rule with a new ID |
| GET | `/v1/acl/binding-rules` | `authmethod` | `acl:read` | Lists binding rules, of one auth method when given |
| GET | `/v1/acl/binding-rule/{id}` | none | `acl:read` | Reads a binding rule |
| PUT | `/v1/acl/binding-rule/{id}` | none | `acl:write` | Updates a binding rule |
| DELETE | `/v1/acl/binding-rule/{id}` | none | `acl:write` | Deletes a binding rule; answers `true` |
| POST | `/v1/acl/login` | none; body `authMethod`, `bearerToken` | none | Exchanges a bearer token for a Qraft token |
| POST | `/v1/acl/logout` | none | the login token itself | Deletes the token the request presents, which must have been made by a login; answers `true` |

Auth method body: `name`, `type` (`jwt` or `kubernetes`), `description`, `maxTokenTtl` (from `1m` to `24h`, default
`1h`) and `config`:

| Type | `config` fields |
|---|---|
| `jwt` | `jwtValidationPubKeys` (required), `boundIssuer`, `boundAudiences`, `claimMappings`, `listClaimMappings`, `expirationLeeway`, `notBeforeLeeway`, `clockSkewLeeway` |
| `kubernetes` | `host` (an `https` address), `caCert` (a PEM certificate) and `serviceAccountJwt`, all required. `serviceAccountJwt` is never answered |

Binding rule body: `description`, `authMethod`, `selector`, `bindType` (`service`, `node`, `role` or `policy`) and
`bindName`, which may interpolate `${value.<claim>}` or, for a kubernetes login, `${serviceaccount.namespace}`,
`${serviceaccount.name}` and `${serviceaccount.uid}`.

A login validates the bearer token against the method (a `kubernetes` method asks the cluster's token review), binds
the token by every rule whose selector matches, and answers `accessorId`, `secretId`, `description`, `policies`,
`roles`, `serviceIdentities`, `nodeIdentities`, `authMethod`, `createTime` and `expirationTime`. The token expires
after the method's `maxTokenTtl`. A login that binds nothing, or whose bearer token is invalid, is refused with 403
`permission_denied`.

Codes: 404 `auth_method_not_found` and `binding_rule_not_found`, 409 `auth_method_exists` and
`binding_rule_exists`, 400 `invalid_auth_method`, `invalid_binding_rule` and `invalid_json`, 503
`auth_method_unavailable` when a kubernetes token review cannot be made, 409 `login_rejected`.

### 4.9 Operator: Raft configuration, peers, members, storage and autopilot

| Method | Path | Parameters | ACL | Purpose |
|---|---|---|---|---|
| GET | `/v1/operator/raft/configuration` | none | `operator:read` | The Raft configuration this server holds: each server's `serverId`, `name`, `address`, `voter`, and `leader` |
| DELETE | `/v1/operator/raft/peer` | `id` or `name`, exactly one | `operator:write`; with ACLs disabled, the operator token | Removes a server from the Raft configuration |
| GET | `/v1/operator/raft/members` | none | `operator:read` | The membership as this server sees it |
| GET | `/v1/operator/raft/storage` | none | `operator:read` | This server's log and snapshot state |
| GET | `/v1/operator/autopilot/health` | none | `operator:read` | Each server's health, in Consul's field names; answered by the leader only |

- `/v1/operator/raft/configuration` answers 503 `not_configured` before the server has a configuration.
- `/v1/operator/raft/peer`: with ACLs disabled, the request must present `server.operator.token` as its token; with no
  operator token configured, every removal is refused. Any server passes the removal to the leader. It answers 200
  `{"status": "REMOVED", "message": ...}`, 403 `permission_denied`, 404 `not_found`, 503 `leader_unavailable`, 409
  `refused`, 400 `invalid_request` for a bad query, or 503 `membership_unavailable` when the server does not serve
  membership changes.
- `/v1/operator/raft/members` answers `view` (`leader` or `follower`), `answeredBy`, `leaderId`, `term`,
  `commitIndex`, `lastLogIndex`, `quorum` (`voters`, `quorumSize`, `reachableVoters`, `failureTolerance`, `healthy`; the
  leader's view only) and `servers`, each with `serverId`, `name`, `address`, `voter`, `leader`, `self`, `matchIndex`,
  `nextIndex`, `lag`, `lastContactMillis`, `reachable` and `healthy`. On a follower the progress fields are null.
- `/v1/operator/raft/storage` answers `answeredBy`, `storageBytes`, `snapshotAgeSeconds`, `snapshotLastIndex`,
  `snapshotLastTerm`, `firstLogIndex`, `lastLogIndex`, `commitIndex`, `lastApplied`, `fenced`, `fenceReason`,
  `transitionQueueDepth` and `lastSnapshotFailure` (`message`, `at`, or null).
- `/v1/operator/autopilot/health` answers `Healthy`, `FailureTolerance` and `Servers`, each with `ID`, `Name`,
  `Address`, `Leader`, `Voter`, `Healthy`, `LastContact`, `LastTerm` and `LastIndex`. Any other server answers 503
  `leader_unavailable`; since reads are not forwarded, send it to the leader.

### 4.10 Operator: configuration, events and watch

| Method | Path | Parameters | ACL | Purpose |
|---|---|---|---|---|
| GET | `/v1/operator/configuration` | none | `operator:read` | This server's effective configuration, secrets withheld |
| GET | `/v1/operator/events` | `after`, `limit`, `category`, `type`, `tenant`, `namespace`, `service`, `key`, `session` | `operator:read` | A page of this server's event journal |
| GET | `/v1/operator/events/watch` | the same, without `limit`, which is refused | `operator:read` | The journal's events as server-sent events, replayed then followed |

`/v1/operator/configuration` answers `server`, `file`, `readAt` and `settings`, each with `key`, `value`, `source`
(`configured` or `default`), `redacted` and `restartRequired` (always `true`: no setting changes while the server
runs). It answers 503 `configuration_unavailable` when the server was not given its configuration.

Event parameters:

| Parameter | Meaning |
|---|---|
| `after` | The cursor to read after, `<boot>:<sequence>`; the start of this server's current boot by default |
| `limit` | At most this many events, 1 to 1,000; default 100 |
| `category` | `system`, `domain`, `observation` or `audit` |
| `type` | An event type; an empty value is refused |
| `tenant`, `namespace`, `service` | Events of that scope or service |
| `key` | Events naming the key, and tree deletions whose prefix covers it; taken as given, not trimmed |
| `session` | The session's own events and the locks it took or gave up |

The page answers `events`, `next` (the cursor to ask from next), `status` (`complete`, `more`, `history_gap`,
`other_boot` or `invalid`) and `capacity`. Each event has `cursor`, `node`, `observedAt`, `category`, `severity`,
`term`, `type`, `event`, and when present `committedIndex`, `replicatedId`, `tenant`, `namespace` and
`correlationId`. Without a journal the routes answer 503 `events_unavailable`.

The watch answers `Content-Type: text/event-stream`. It opens with the comment `: watching`, then sends each event
with its cursor as `id`, its type as `event` and its view as `data`, and a comment line as a heartbeat after each 15
seconds without one. Unless the client left, it ends with an `end` event whose data gives `reason` (`overflow`,
`history_gap`, `other_boot`, `failed` or `server_stopping`) and `resumeAfter`, the cursor to resume from. A server
that stops ends its open watches as `server_stopping` first.

### 4.11 Internal routes

The `/v1/internal/ui/` routes serve the admin interface's views. The `/v1/internal/acl/` routes answer for the
presenting token alone. All follow the rules of sections 1 and 2.

| Method | Path | Parameters | ACL | Purpose |
|---|---|---|---|---|
| GET | `/v1/internal/ui/services` | read options | filtered: services by `service:read`, instances by `node:read` and `service:read` | Each service in scope with `name`, `tenantId`, `namespace`, `tags`, `instanceCount`, `health` (counts by state), `datacenters`, `regions` and `modifyIndex` |
| GET | `/v1/internal/ui/locks` | `session`, read options | filtered by `key:read` | `Held`: every key a session holds, in every scope, without its value; `Delays`: lock delays still running, with `Until`. `session` narrows `Held` |
| GET | `/v1/internal/ui/sessions` | read options | filtered by `session:read` | `Sessions`, each with `RenewedAt` and `ExpiresAt` from the leader's timers, and `TtlKnown`, false on a server that does not lead |
| GET | `/v1/internal/ui/scopes` | read options | `operator:read` | Every tenant with its namespaces, each with counts of its `Services`, `Instances`, `Keys` and `HeldKeys`, and the `Sessions` holding keys |
| GET | `/v1/internal/ui/audit` | `limit`, `accessor`, `requestId`, `method`, `route`, `tenant`, `namespace`, `result`, `before`, `from` | `operator:read` | Reads this server's audit file backwards, newest first |
| GET | `/v1/internal/ui/acl/status` | none | `acl:read`; 401 `acl_disabled` with ACLs disabled | The security status: settings, transport, management tokens, what the anonymous token may write, tokens holding `acl:write` without an expiry, and tokens expiring within the hour |
| POST | `/v1/internal/acl/authorize` | none; body a JSON array of up to 64 checks (`resource`, `segment`, `access`, `tenant`, `namespace`) | the presenting token only | Answers `allow` for each check, for the presenting token; 401 `acl_disabled` with ACLs disabled |
| POST | `/v1/internal/acl/resolve` | none | the presenting token only | The presenting token with its policies and roles; clients in client mode resolve tokens through it; 401 `acl_disabled` with ACLs disabled |

Audit parameters: `limit` from 1 to 1,000 (default 100); `accessor`, `requestId`, `method`, `tenant` and `namespace`
match exactly; `route` matches a prefix; `result` is `allowed`, `denied` (status 403) or `failed` (status 500 or
above); `before` is an instant; `from` is the offset that a previous answer's `Next` gave. The answer gives
`Configured`, `File`, `Size`, `Scanned`, `Complete`, `Next` and `Records`. One read scans at most 64 MiB of the file.

### 4.12 The admin interface

| Method | Path | ACL | Purpose |
|---|---|---|---|
| GET, HEAD | `/` | none | Redirects (302) to the interface's path when the interface is enabled; other methods answer 405. With the interface disabled, 404 |
| GET, HEAD | `/ui/` and below | none | The interface's files |

The interface is enabled by default (`server.ui.enabled`) at `/ui/` (`server.ui.path`). The path itself, and any path
whose last segment has no extension, is answered with the interface's page, so the interface can route in the browser.
Files below `assets/` are cached as immutable; the page is never stored. Every answer carries a content security
policy, `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`. The files need no token; with ACLs
enabled, each API request the interface makes carries the signed-in user's token and is authorized as any other.

## 5. Client local API routes

The local API shares the request ID, body limit, method and error envelope rules of section 1. Once the client begins
to shut down, every request other than `GET` and `HEAD` through its routes is refused with 503 `shutting_down`; the
health routes keep answering.

With ACLs enabled on the client (`client.acl.enabled`), the client resolves a presented token through the servers'
`POST /v1/internal/acl/resolve` and caches the result. When no server answers, `client.acl.downPolicy` decides:
`extend-cache` (default), `async-cache`, `allow` or `deny`. A token that cannot be sent on is refused with 400
`invalid_token`. Permissions on services are checked in the client's own tenant and namespace.

### 5.1 Health

| Method | Path | ACL | Purpose |
|---|---|---|---|
| GET | `/health/live` | none | 200 `{"status":"alive"}` |
| GET | `/health/ready` | none | 200 `{"status":"ready"}`, or 503 `{"status":"not_ready"}` (not the error envelope) |

The client is ready when it is running, its node is registered, its services have converged with the servers, its
required checks are satisfied, and it has had a successful server contact within `catalog.contactFreshnessMs` of
its configuration (90 seconds by default). `GET /v1/client/self` reports each condition.

### 5.2 The client itself

| Method | Path | Parameters | ACL | Purpose |
|---|---|---|---|---|
| GET | `/v1/client/self` | none | `client:read` on the client's node | `node`, `version`, `config` (named settings only), `serverContact` and `readiness` |
| GET | `/v1/client/members` | read options | decided by the servers | Forwarded to the servers' `GET /v1/catalog/nodes` |
| PUT | `/v1/client/leave` | none | `client:write` | Leaves the catalog; answers `{"deregistered": ...}`, `true` when it left the catalog completely, then the client shuts down |
| PUT | `/v1/client/token/{kind}` | none; body `{"token": "..."}` | `client:write` | Replaces one of the client's tokens while it runs: `default`, `client`, `configFileRegistration` or `recovery` |
| GET | `/v1/client/metrics` | `format=prometheus` only | `client:read` | The client's metrics as JSON, or in Prometheus text format |

A replaced token is kept across a restart only with `client.acl.enableTokenPersistence`, and is never logged or
answered. When it cannot be kept, the route answers 500 `token_not_kept` and nothing changes.

The metrics are `qraft.client.ready`, `qraft.client.last_contact_age_seconds` (-1 before any server has answered),
`qraft.client.server` (1, labelled with the `endpoint` the client tries first) and `qraft.client.reconciliations` by
`result` (`clean`, `rejected` or `failed`). The JSON answer has `Timestamp`, `Gauges` and `Counters`. In Prometheus
format they are `qraft_client_ready`, `qraft_client_last_contact_age_seconds`, `qraft_client_server_info` and
`qraft_client_reconciliations_total`.

### 5.3 Services, checks and maintenance

These routes change the client's own definitions. A write answers once the change is kept in the data directory and
has taken effect locally; the client then reconciles with the servers. A change that cannot be kept answers 500
`registration_not_kept` and nothing changes. Service and check bodies use the same JSON form as the client's
configuration file.

| Method | Path | Parameters | ACL | Purpose |
|---|---|---|---|---|
| PUT | `/v1/client/service/register` | `replace-existing-checks` (no value or `true`) | `service:write` on the service, and on the service of the same ID it replaces | Registers a service; answers `{"serviceId"}` |
| PUT | `/v1/client/service/deregister/{serviceId}` | none | `service:write` on the service | Removes a service; answers `serviceId` and `deregistered` |
| GET | `/v1/client/services` | none | filtered by `service:read` | The client's enabled services, by ID |
| GET | `/v1/client/service/{serviceId}` | none | `service:read`; refused, not filtered | One service |
| PUT | `/v1/client/check/register` | none | `service:write` on the check's service, and on the service of the check it replaces | Registers a check for a registered service; answers `checkId` and `serviceId` |
| PUT | `/v1/client/check/deregister/{checkId}` | none | `service:write` on the check's service | Removes a check; answers `checkId` and `deregistered` |
| GET | `/v1/client/checks` | none | filtered by `service:read` | The client's checks, by ID, each with its latest `status`, `output` and `observedAt` |
| PUT | `/v1/client/check/pass/{checkId}` | `note` | `service:write` on the check's service | Sets a TTL check passing |
| PUT | `/v1/client/check/warn/{checkId}` | `note` | `service:write` | Sets a TTL check warning |
| PUT | `/v1/client/check/fail/{checkId}` | `note` | `service:write` | Sets a TTL check critical |
| PUT | `/v1/client/check/update/{checkId}` | none; body `status` (`passing`, `warning` or `critical`), `output` | `service:write` | Sets a TTL check's status and output |
| PUT | `/v1/client/maintenance` | `enable` (required), `reason` | `node:write` on the client's node | Puts the node into or out of maintenance |
| PUT | `/v1/client/service/maintenance/{serviceId}` | `enable` (required), `reason` | `service:write` on the service | Puts a service into or out of maintenance |

- `enable` is read as Go's `strconv.ParseBool` reads it: `1`, `t`, `T`, `TRUE`, `true`, `True`, or the same forms of
  false.
- Removing what is not registered is not an error with ACLs disabled; with ACLs enabled, an unknown service or check
  answers 404, since there is no name to authorize.
- Codes: 400 `invalid_registration` and `invalid_request`, 404 `service_not_found` and `check_not_found`, 400
  `check_not_ttl` for a status sent to a check that is not a TTL check, 503 `check_not_running` before the check runs.

### 5.4 Forwarded routes

The client keeps no copy of the catalog or the key/value state. These routes forward the request to the servers, with
its path and query as written, its `X-Qraft-Tenant` and `X-Qraft-Namespace` headers (else the client's own tenant and
namespace, `catalog.tenant` and `catalog.namespace`), its token (else the client's `default` token) and its request ID. The servers decide and filter. A scope
header or request ID that holds a character that cannot be sent on is refused with 400 `invalid_scope` or
`invalid_request_id`.

| Methods | Paths | Server routes |
|---|---|---|
| GET | `/v1/catalog/services`, `/v1/catalog/service/{name}`, `/v1/catalog/nodes`, `/v1/health/service/{name}` | The server routes of the same path (section 4.2, 4.3) |
| GET, PUT, DELETE | `/v1/kv/...` | Section 4.4 |
| GET, PUT, DELETE | `/v1/session/...` | Section 4.5. A `create` without a `Node`, or with an empty one, is bound to the client's own node |
| GET, PUT, DELETE | `/v1/tenant`, `/v1/tenants`, `/v1/namespace`, `/v1/namespaces` and below | Section 4.6 |

The client tries its configured servers (`servers.urls`) in turn:

- a read moves to the next server after a connection failure, a 429 or any 5xx answer;
- a write moves on only when it cannot have been applied: a connection that could not be made, a 429, or a 503
  `leader_unavailable` or `draining`. Any other answer, `outcome_unknown` included, is passed on, so that no write is
  applied twice. A write that was sent to a server and not answered is answered 503 `outcome_unknown`, not
  retryable, in the server's error envelope, and no other server is asked.

When no server can be asked, the route answers 503 `no_server_reachable`. Otherwise the server's status and body are
passed on. A blocking read's timeout is extended by its `wait`. The forwarded discovery reads and
`/v1/client/members` pass on `X-Qraft-Index`, `X-Qraft-Leader-Id` and `X-Qraft-Results-Filtered-By-ACLs`; the
key/value, session and tenancy routes pass on those and `Content-Type`.

## 6. Not provided

Compared with Consul's HTTP API:

- No routes are registered for transactions (`/v1/txn`), user events (`/v1/event/*`), prepared queries
  (`/v1/query`), snapshots (`/v1/snapshot`), network coordinates (`/v1/coordinate`), status (`/v1/status/*`),
  configuration entries (`/v1/config`), service mesh (`/v1/connect/*`), `/v1/catalog/datacenters`,
  `/v1/catalog/node/{node}`, `/v1/health/node/{node}`, `/v1/health/checks/{service}` or
  `/v1/operator/keyring`. Each answers 404 `not_found`.
- The client local API has no routes under Consul's agent prefix, `/v1/agent/`; its routes are under `/v1/client/`.
- Catalog and health reads take only the read options (and `passing` on `/v1/health/service`). Consul parameters
  such as `dc`, `tag`, `near`, `filter` and `token` are refused with 400 `invalid_query`.
- A token is never read from the query string; use `X-Qraft-Token` or `Authorization: Bearer`.
- Sessions bound to health checks are refused: a session ends when it is destroyed, its TTL lapses, or its node fails
  or leaves.
- Auth methods are of type `jwt` or `kubernetes` only.
- Reads are not forwarded to the leader; only writes are (section 3).
- The client local API serves HTTP only.
