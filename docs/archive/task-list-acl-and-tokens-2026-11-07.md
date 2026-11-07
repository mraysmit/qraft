# Task List: ACLs and Tokens

**Date:** 2026-10-05
**Status:** Complete and archived on 2026-11-07. Phases 1 to 9 are done: Phase 4's exit was met on Jenkins build 65, and Phases 5 to 9 were committed as `f833761`, passed by Jenkins build 66, and, with the fixes its audit and the work after it called for, passed by the final local run of the three suites with a clean audit. `server.operator.token` is kept until the transport security list (proposal 14), the recommended choice, taken on 2026-11-07 under the owner's instruction to finish the list without stopping for questions; the owner can reverse it. Started on 2026-11-04 at the owner's word; aligned with Consul's source on 2026-11-01 at the owner's request, except that secrets follow ZooKeeper, at the owner's decision of the same day (proposal 3). TLS and audit events are left to the transport security and events lists.
**Proposed start:** after [`task-list-consul-style-client-2026-11-01.md`](task-list-consul-style-client-2026-11-01.md) (proposal 15), complete on 2026-11-01.
**Last updated:** 2026-11-07 (the list complete and archived, with `server.operator.token` kept; the final local run of the three suites recorded, and the fixes for a takeover under another case and for Tempo's audit timing. Earlier the same day: Phases 5 to 9 built, test first, with their records in section 6. Earlier the same day: Phase 4's exit line: the owner kept the Pre-Vote fix; Phase 4's first point decided and built: a management file keeps the bootstrap endpoint closed until its token is installed; its second decided: Grafana's SQLite in WAL mode, and after build 64 its retries declared instead; build 64's result in Phase 4's record, and Phase 4's exit met on build 65; the list renamed with the handover. Earlier, 2026-11-06: Phase 4 built, with its record and two points for the owner in section 6; Phase 3's exit recorded, with what its log audit found; a row added to the differences from Consul; the list renamed. Earlier, 2026-11-05: Phase 3 built, with its record in section 6, starting with the owner's decision on Phase 1's open point. Earlier the same day: Phase 2 built, test first, with its record in section 6; the owner's decisions on how nested rules rank, in proposal 7, and on how far a service identity reads, in proposal 9, with their rows in the differences from Consul; the list renamed. Earlier, 2026-11-04: Phase 1 built, test first, with its record and the points it decided in section 6; the built-in read-only policy's name corrected to Consul's `builtin/global-read-only` in proposal 8; the anonymous token built into the initial state, in proposal 5 and Phase 3; the rows Phase 1 adds to the differences from Consul; the list renamed. Earlier, 2026-11-01: the owner's decisions on proposals 16 to 19 recorded: 16, 17 and 19 stay, and 18 now follows Consul, in proposal 18, the differences from Consul, and Phases 1 and 5. Earlier the same day: `defaultPolicy` has no default, and `deny` with ACLs disabled logs a critical warning, at the owner's decision: proposals 12, 13 and 16, the differences from Consul, and Phases 3 and 6. Earlier the same day: secrets stored only as a hash, following ZooKeeper rather than Consul, at the owner's decision: proposal 3, the rules, the differences from Consul, and Phases 1, 5, 6 and 9. Earlier the same day: aligned with Consul's source at the owner's request: section 3 rewritten from the source, with the items that were only recalled now read; the departures reversed, and those that stay listed at the end of section 4; roles, the bootstrap endpoint, the default policy, the client's cache, down policy and four tokens added; every route the client list built added to proposal 11, with the client deciding for itself so that it keeps working with no server; proposal 11's token for anti-entropy's removals corrected; section 2 checked again; the default profile changed to `development`. Earlier the same day: the client list complete and archived, and its link updated. Earlier the same day: renamed with the client list. Earlier, 2026-10-31: renamed with the client list; the token for the client's anti-entropy removals read in Consul's source and recommended to follow it, in proposal 11 and Phase 6; Consul's anti-entropy moved from the recalled items to what has been read. Earlier, 2026-10-30: proposal 11's planted instance corrected: the client's reconciler has had anti-entropy since `c33a2f2`, which opens the point of the token its removals use, added to Phase 6; the rule on who runs builds corrected. Earlier the same day: renamed with the client list, which changed; nothing else changed. Earlier, 2026-10-29: renamed with the client list, which changed; nothing else changed. Earlier, 2026-10-28: renamed with the client list, which changed; nothing else changed. Earlier, 2026-10-27: the review of the administrative interface against this list: four points decided by the owner: a security profile, proposal 16; a lock-out guard on the server, proposal 17; a policy that tokens link to cannot be deleted, proposal 18, which settles Phase 1's open item; and a server-side explanation of a decision, proposal 19. Proposal 12 gains the marker for a token installed from the file, and its revocation order follows from proposal 17. Phases 1, 2, 3, 5 and 9 gain the tasks. Renamed to the date of its last change, with the client list. Earlier, 2026-10-26: renamed with the client list, which became active; nothing else changed. Earlier, 2026-10-21: renamed with linked documents; this list remains proposed after the client list. Earlier, 2026-10-11: the rule that guards need recorded mutations removed at the owner's decision, from the rules and from four exits; otherwise renamed with the task lists it links to; nothing else changed that day. On 2026-10-10: renamed with the task lists it links to; nothing else changed that day. On 2026-10-09: proposal 11 brought in line with the node routes and with what a node's deregistration removes; the file renamed to the date of its last change; four points decided: the authorize call; revoking the management token; Consul's rule for registering a service, which needs `service:write` alone on a registered node; and a client certificate on the server's HTTP listener, for the transport security list)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), sections 7.3, 12, 15, 16 and 22
**Related:** the client list's decisions 6 and 8; decision 6 of [`task-list-raft-membership-changes-2026-10-24.md`](task-list-raft-membership-changes-2026-10-24.md), the operator token; item 9 of [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md); phase 7 of [`CONSUL_FEATURE_IMPLEMENTATION_PLAN.md`](../../docs-design/CONSUL_FEATURE_IMPLEMENTATION_PLAN.md); section 10.2 of [`QRAFT_ADMIN_UI_UX_DESIGN.md`](../../docs-design/QRAFT_ADMIN_UI_UX_DESIGN.md), the administrative interface's access control
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

Stop trusting what a caller says about itself. Design section 15 already says
the long-term design "does not trust a caller-supplied node or tenant
identity". Today the server does exactly that.

When this list is complete, with ACLs enabled:

- every request to a server or a client's local API is made under a token, or
  as the anonymous token;
- a client's own token registers and renews its node, and removes what should
  not be on it;
- an application's token decides which services it can register, report on, and
  read;
- the node identity rules of the client list's decision 8 are enforced, not
  only detected: a takeover or a rename needs a token that may write that name;
- operator actions use the same tokens, in place of the one shared operator
  token;
- tokens are created, limited, expired, and revoked while the cluster runs,
  through the replicated log.

This list gives authentication and authorization. It does not give secrecy on
the wire: that is transport security, proposal 14.

The rule for every choice: **do what Consul does.** Section 4 ends with each
place this list still differs, and why.

## 2. Current state

Checked in the code on 2026-11-01, after the client list finished.

- **Identity is whatever the headers say.** `HeaderRequestContext` reads the
  tenant, namespace, node, and node ID from `X-Qraft-Tenant`,
  `X-Qraft-Namespace`, `X-Qraft-Node`, and `X-Qraft-Node-ID`. Nothing
  authenticates them. The class sits behind the `RequestContext` interface,
  which design section 15 put there so that token authentication could replace
  it without changing the catalog commands.
- **One shared secret exists.** `server.operator.token` guards
  `DELETE /v1/operator/raft/peer`. It is sent as `X-Qraft-Token` or
  `Authorization: Bearer`, compared in constant time in `MembershipService`,
  and checked again by the leader when a removal is forwarded.
- **Everything else on a server is open:** the routes
  `/v1/catalog/register`, `deregister`, `node/heartbeat`, `check/observe`,
  `nodes`, `node-services/{name}`, `services`, and `service/{name}`,
  `/v1/health/service/{name}`, `/v1/operator/raft/configuration`,
  `/raft/status`, the gRPC `DistributedStateService`, and the administrative
  interface.
- **Everything on a client's local API is open** to whoever reaches it, which by
  default means the host, because it binds `127.0.0.1` (`client.bindAddress`):
  `/v1/client/self`, `members`, `leave`, `service/register`,
  `service/deregister/{id}`, `services`, `service/{id}`, `check/register`,
  `check/deregister/{id}`, `checks`, `check/pass|warn|fail|update/{id}`,
  `maintenance`, and `service/maintenance/{id}`, and the forwarded reads
  `/v1/catalog/nodes`, `services`, `service/{name}`, and
  `/v1/health/service/{name}`.
- **The client has no credential.** `ClientConfiguration` has no token setting,
  and `HttpCatalogClient` sends none.
- **Registrations are kept without a token.** `registrations.json` in the data
  directory holds what the local API registered, through `RegistrationStore`.
- **Nodes have no scope.** Services carry a tenant and a namespace; a node
  does not.
- **The Raft port is trusted,** including a claim to an existing server ID
  (membership list, review remediation of 2026-10-02).
- **No listener uses TLS.**

## 3. Reference: how Consul handles this

Read on 2026-11-01 from Consul's source, on its `main` branch, and on 2026-10-05
from its documentation. The files are named at each point, and the sources are
listed at the end of this section.

**Tokens** (`agent/structs/acl.go`, `agent/consul/auth/token_writer.go`)

- A token has an accessor ID, which is public and names the token in the API and
  logs, and a secret ID, which the caller presents. Both are UUIDs, generated
  unless the caller supplies them.
- It has a description, a creation time, an optional expiry, and links to
  policies, roles, service identities, and node identities.
- The secret and the expiry cannot change once the token exists: "Changing a
  token's SecretID is not permitted", "Cannot change expiration time".
- An expiry must be between one minute and 24 hours ahead.
- **Secrets are stored unhashed.** The state store looks a token up by its secret
  (`ACLTokenGetBySecret`, in `agent/consul/state/acl.go`). A read with `acl:read`
  shows the secret as `<hidden>`; `acl:write` reveals it.
- A request cannot delete the token it was made with ("Deletion of the request's
  authorization token is not permitted"), and nobody can delete the anonymous
  token (`ACL.TokenDelete`).

**Presenting a token and being refused** (`agent/http.go`)

- The secret comes from `?token=`, then `X-Consul-Token`, then
  `Authorization: Bearer`. A request with none uses the agent's default token, if
  it has one (`parseTokenWithDefault`), and otherwise acts as the anonymous token.
- **403 for an unknown or expired token and for a missing permission alike:**
  `acl.IsErrPermissionDenied(err) || acl.IsErrNotFound(err)`. The messages differ:
  "ACL not found" and "Permission denied".
- With ACLs disabled, the ACL endpoints answer 401 "ACL support disabled".

**Rules** (`acl/policy.go`, `acl/policy_authorizer.go`, and the rule documentation)

- A rule names a resource, a name or a name prefix, and an access level: `read`,
  `write`, or `deny`, and `list` for keys. Rules are written in HCL or its JSON
  form: `{"service_prefix": {"billing-": {"policy": "write"}}, "acl": "read"}`.
- An exact name wins, and otherwise the longest prefix. Where two rules of a
  token's policies are equally specific, the merge prefers `deny`, then `write`,
  then `read` (`takesPrecedenceOver`).
- Consul Enterprise nests rules under `partition` and `namespace`, each with a
  `_prefix` form. Qraft's tenant is the partition's counterpart.
- `node` controls catalog registration of a node, its checks, and which nodes
  discovery shows. `service` controls service registration, and which services
  discovery shows. `agent` controls the agent's own utility operations.
  `operator` covers the Raft configuration. `acl` covers ACLs.
- The built-in policies are `global-management` and `global-read-only`.
  Neither's rules can be changed, and neither can be deleted (`ACL.PolicySet`,
  `ACL.PolicyDelete`).
- **A policy that tokens link to can be deleted.** The links are dropped when a
  token is next read (`fixupTokenPolicyLinks`).
- A role is a named set of policies and identities, and tokens link to it.
- A node identity gives `write` on that node name and `read` on every service.
  A service identity gives `write` on that service name and its sidecar proxy,
  and `read` on every service and every node.

**What each operation needs on a server** (`agent/consul/catalog_endpoint.go`,
`health_endpoint.go`, `agent/structs/aclfilter/filter.go`)

- **Registering** (`vetRegisterWithACL`): `node:write` only when the registration
  creates the node or changes what is recorded for it; `service:write` on the
  service name, and on the name of any instance with the same ID that it replaces;
  for a check, `service:write` on its service, or `node:write` without one.
- **Deregistering** (`vetDeregisterWithACL`): **`node:write` on the node is enough
  for anything on it.** "This accounts for cases where the agent no longer has a
  token with write permission on the service to deregister it." Without it, a
  service needs `service:write` on its name, and a check the same on its service.
- **Reads are filtered, including a read of one named service.** The answer is
  what the token may see, which may be nothing, with
  `X-Consul-Results-Filtered-By-ACLs: true` when anything was removed
  (`setResultsFilteredByACLs`). An instance needs both `node:read` and
  `service:read` (`CheckServiceNode.CanRead`). The only 403 on these reads is for
  an unknown token.
- **Operator:** `operator:read` for the Raft configuration, `operator:write` to
  remove a peer.

**What each operation needs on an agent** (`agent/agent_endpoint.go`, `agent/acl.go`)

| Consul endpoint | Needs |
|---|---|
| `AgentSelf` | `agent:read` on the node name |
| `AgentMembers` | filtered by `node:read` (`filterMembers`) |
| `AgentLeave` | `agent:write` on the node name |
| `AgentToken` | `agent:write` on the node name |
| `AgentRegisterService` | `service:write` on the name, and on the name of the instance it replaces (`vetServiceRegister`) |
| `AgentDeregisterService`, `AgentServiceMaintenance` | `service:write` on the existing service; 404 if there is none (`vetServiceUpdate`) |
| `AgentServices`, `AgentChecks` | filtered by `service:read` and `node:read` |
| `AgentService` | `service:read`, and 403 without it |
| `AgentRegisterCheck` | `service:write` on its service, or `node:write` without one, for the new check and the one it replaces (`vetCheckRegister`) |
| `AgentDeregisterCheck`, `agentCheckUpdate` (pass, warn, fail, update) | the same, on the existing check; 404 if there is none (`vetCheckUpdate`) |
| `AgentNodeMaintenance` | `node:write` on the node name |

**How an agent decides** (`agent/consul/acl.go`, `acl_client.go`)

- The agent decides itself, from a token and policies it resolved from a server
  and caches: `ACL.TokenRead` and `ACL.PolicyResolve`, which answers for the
  policies and roles the presenting token links to.
- `acl.token_ttl`, `policy_ttl`, and `role_ttl` are 30 s by default
  (`agent/config/default.go`).
- `acl.down_policy` says what happens when no server answers:
  `extend-cache`, the default, keeps using what is cached past its expiry;
  `async-cache` does too and refreshes in the background; `allow` and `deny` allow
  or deny everything. With `extend-cache` and nothing cached, the code answers
  with the default policy (`down = acl.RootAuthorizer(ACLDefaultPolicy)`), where a
  comment says "this acts like deny".
- `acl.default_policy` is `allow` by default. With `deny`, anything not allowed is
  refused. Under either, reading or writing ACLs needs a rule for it: the static
  allow-all authorizer denies `acl` (`acl/static_authorizer.go`).
- `acl.enabled` is off by default.

**An agent's own tokens** (`agent/token/store.go`, `agent/local/state.go`,
`agent/agent.go`)

- `tokens.agent`, which falls back to `tokens.default`, covers the node: its
  registration and anti-entropy's read. **Every deregistration during a sync uses
  it:** "Always use the agent token to delete without trying the service token."
- A service or check registered through the API **keeps the token it was
  registered with**, written with it to the data directory (`persistedService`),
  and syncs under it. Without one it uses `config_file_service_registration` for
  a definition from a file, and then `default`.
- `tokens.agent_recovery` is recognised by the agent itself, with no server. It
  gives `agent:write` on its own node name and `read` on every node.
- A refused sync is not fatal: "Service registration blocked by ACLs" is logged as
  a warning, the service is marked in sync, and it is tried again at the next full
  sync.
- `PUT /v1/agent/token/{kind}` changes a token on a running agent. It is kept
  across a restart only with `acl.enable_token_persistence`.

**Bootstrap** (`agent/consul/acl_endpoint.go`, `leader.go`)

- `PUT /v1/acl/bootstrap` needs no token and creates the first management token.
  It works once and then answers 403, naming a reset index. The caller may supply
  the secret.
- **A lost bootstrap is reset** by writing that index to `acl-bootstrap-reset` in
  the leader's data directory. The file is removed when used.
- `acl.tokens.initial_management` names a secret that a server installs when it
  gains leadership, if no token has it. It uses the same one-time command, so it
  also closes the endpoint.
- The anonymous token is created at leadership too.
- The leader deletes expired tokens in batches (`acl_token_exp.go`).

**The ACL API** (`agent/http_register.go`, `agent/acl_endpoint.go`)

- Tokens: `PUT /v1/acl/token`, `GET`, `PUT`, and `DELETE /v1/acl/token/{id}`,
  `GET /v1/acl/token/self`, and `PUT /v1/acl/token/{id}/clone`.
  `GET /v1/acl/tokens` filters on `policy`, `role`, and `servicename`.
- Policies: `PUT /v1/acl/policy`, `GET`, `PUT`, and `DELETE /v1/acl/policy/{id}`,
  `GET /v1/acl/policy/name/{name}`, and `GET /v1/acl/policies`. Roles have the same
  shape.
- `POST /v1/internal/acl/authorize` answers up to 64 checks for the token that
  presents it, and needs no grant.

The client list's decision 8 follows Consul's rule for a name held by another node
ID (`agent/consul/state/catalog.go`: "Node name %s is reserved by node %s"), checked
on 2026-10-29.

Sources:
- [Consul's source](https://github.com/hashicorp/consul/tree/main), at the paths above
- [ACL tokens](https://developer.hashicorp.com/consul/docs/secure/acl/token)
- [ACL rules](https://developer.hashicorp.com/consul/docs/secure/acl/rule)
- [ACL roles, with the identity templates](https://developer.hashicorp.com/consul/docs/secure/acl/role)
- [Bootstrap the ACL system](https://developer.hashicorp.com/consul/docs/secure/acl/bootstrap)
- [ACL configuration reference](https://developer.hashicorp.com/consul/docs/reference/agent/configuration-file/acl)
- [ACL HTTP API](https://developer.hashicorp.com/consul/api-docs/acl) and
  [token endpoints](https://developer.hashicorp.com/consul/api-docs/acl/tokens)
- [Consul security model](https://developer.hashicorp.com/consul/docs/security/security-models/core)
  and [general configuration](https://developer.hashicorp.com/consul/docs/reference/agent/configuration-file/general),
  read on 2026-10-09 for how Consul limits the use of a stolen token: its
  HTTP API binds to `127.0.0.1` by default (`client_addr`), `verify_incoming`
  makes it require a client certificate, and `allow_write_http_from` limits
  write calls to listed networks
- ZooKeeper, read on 2026-11-01 for how it keeps secrets (proposal 3): the
  [administrator's guide](https://zookeeper.apache.org/doc/current/zookeeperAdmin.html),
  which says its snapshots and logs are not encrypted and leaves their protection to
  external measures; the
  [programmer's guide](https://zookeeper.apache.org/doc/current/zookeeperProgrammers.html)
  and [`DigestAuthenticationProvider`](https://github.com/apache/zookeeper/blob/master/zookeeper-server/src/main/java/org/apache/zookeeper/server/auth/DigestAuthenticationProvider.java),
  for its digest scheme; and
  [its SASL guide](https://cwiki.apache.org/confluence/display/ZOOKEEPER/Client-Server+mutual+authentication),
  for files readable only by the server's user

## 4. Proposed decisions

Each follows Consul unless it says otherwise. The owner asked on 2026-11-01 for the
list to be aligned with Consul, which reversed the departures this list used to
propose: no bootstrap endpoint, no `allow` mode, 401 for a bad token, no cache or
down policy, no default or recovery token, 403 on a named read, and roles left out.
On how secrets are stored, the owner chose ZooKeeper's approach over Consul's the
same day (proposal 3). The numbering is kept, because other documents cite it.

### The model

1. **Consul's model:** tokens, policies, roles, and node and service identities,
   held in replicated state.
2. **A token** has:
   - an accessor ID and a secret, each a UUID from `SecureRandom`, generated unless
     the caller supplies them;
   - a description, and links to policies, roles, node identities, and service
     identities;
   - a creation time and an optional expiry, between one minute and 24 hours ahead,
     both stamped by the leader into the command so that every replica stores the
     same values.

   The secret and the expiry cannot change once the token exists. To replace a
   secret, clone the token (`PUT /v1/acl/token/{id}/clone`), move its users, and
   delete the old one.
3. **Decided 2026-11-01: secrets are stored only as a hash, following ZooKeeper
   rather than Consul.** Consul stores a secret as it is, so a copy of a snapshot holds
   working tokens. ZooKeeper's digest scheme stores a hash and hashes what a caller
   presents, so a copied hash is not a login.
   - The server hashes the secret with SHA-256 before it proposes the command, so
     replicas, the WAL, snapshots, and backups hold only the hash. A token is looked up
     by the hash of what a caller presents.
   - **SHA-256, not ZooKeeper's default of SHA-1, and no slow password hash.** ZooKeeper's
     passwords are chosen by people. A generated secret has about 122 random bits, too
     many to guess back from a fast hash. A secret a caller supplies must be a UUID, but
     has that strength only if it is random, so the documents say to let the server
     generate it.
   - **A secret is returned once,** by the create, clone, or bootstrap that made it, or
     not at all when the caller supplied it. Every later read shows `<hidden>`, whatever
     the permission, and never shows the hash.
   - **The cost: no reveal.** A lost secret cannot be read back. Its token is cloned, its
     users moved to the clone, and the old token deleted (proposal 2).
   - **What this does not cover.** A file that must hold a secret stays readable only by
     the user that runs the process, as ZooKeeper says of its JAAS files and keytabs: the
     management file, the client's token files, `registrations.json`, and persisted tokens.
     Qraft writes its own files that way, and a deployment does the same for what it
     mounts. The client's cache stays in memory. Backups still hold everything else, so
     Phase 9's runbook carries Consul's advice on them.
4. **Presenting a token.** `X-Qraft-Token` or `Authorization: Bearer`, as the
   operator token is sent today. Never logged (`PROJECT_STANDARDS.md` section 6.3);
   a log line names the accessor ID.
5. **Anonymous, invalid, and disabled.**
   - A request without a token uses the client's default token, if any, and
     otherwise acts as a built-in anonymous token, which can have policies attached
     and cannot be deleted. It is part of every server's initial state, with Consul's
     accessor ID and secret (Phase 1).
   - An unknown or expired secret is refused with 403 `token_not_found`. A valid
     token without the permission is refused with 403 `permission_denied`. The
     status is Consul's; the two codes carry what its two messages do.
   - With ACLs disabled, the ACL API answers 401 `acl_disabled`.

### Rules and scope

6. **Resources in this list:** `node`, `service`, `client`, `operator`, and `acl`.
   `client` is Consul's `agent`, under Qraft's name. Access is `read`, `write`, or
   `deny`. `node`, `service`, and `client` take an exact name or, with `_prefix`, a
   prefix; `operator` and `acl` are single values, as in Consul. An exact name beats
   a prefix, and a longer prefix beats a shorter one. Where a token's policies give
   two equally specific rules, `deny` wins, then `write`, then `read`. `key` and
   `session` are reserved names, added by the key/value and sessions lists. A policy
   naming an unknown resource is refused when it is stored.
7. **Scope.** Rules are nested under `tenant` and `namespace`, each with a `_prefix`
   form, as Consul Enterprise nests them under `partition` and `namespace`, and a
   rule outside them applies to `default`. The empty prefix matches any. A rule for
   one tenant grants nothing in another. The scope headers stay, as the caller's
   choice of scope; authorization checks that choice against the token. `node`,
   `client`, `operator`, and `acl` rules have no scope, because nodes have none
   today (section 2). If the tenancy list scopes nodes, their rules move inside.

   **Decided 2026-11-05: the name ranks first, then the scope.** Consul's documentation
   and public source do not say how Enterprise ranks nested rules. Among the rules that
   match a request, the most specific name decides, as in Consul; for an equally
   specific name, the most specific scope decides: an exact tenant before a prefix, a
   longer prefix before a shorter, then the namespace the same way. Only rules equal in
   both merge, `deny` before `write` before `read`. So `tenant "acme" { namespace
   "prod" { service "web" write } }` allows writing `web` in `acme`/`prod` against a
   `tenant_prefix ""` deny for `web`, as Consul's exact `service "web"` beats a
   `service_prefix ""` deny.
8. **Policies use the JSON form of Consul's rules,** validated when stored, with
   unknown fields refused:

   ```json
   {
     "name": "billing-services",
     "description": "Billing's services in acme/prod, and read access across acme",
     "rules": {
       "tenant": {
         "acme": {
           "namespace": {
             "prod": { "service_prefix": { "billing-": { "policy": "write" } } }
           },
           "namespace_prefix": {
             "": { "service_prefix": { "": { "policy": "read" } } }
           }
         }
       },
       "node_prefix": { "": { "policy": "read" } }
     }
   }
   ```

   The built-in `global-management` and `builtin/global-read-only` policies, Consul's
   names on its `main` branch, cannot be deleted, and their rules cannot change; as in
   Consul, they can be renamed and described. A role is a name, a description, and links to policies and
   identities; a token that links to it holds what it holds.
9. **Identities, as in Consul:**
   - a node identity gives `write` on that node name and `read` on every service, in
     every tenant and namespace, because a node has no scope;
   - a service identity gives `write` on that service name and `read` on every
     service, both in its own tenant and namespace, and `read` on every node. There is
     no sidecar rule, because Qraft has no mesh.

   **Decided 2026-11-05: a service identity reads only its own scope.** Consul CE has no
   scopes, and Consul Enterprise puts an identity's generated policy in the token's
   partition and namespace. A Qraft token has no scope, so a service identity carries
   its own tenant and namespace (Phase 1), and its reads stay there, so that one
   tenant's service token cannot read another tenant's catalog.

### Enforcement

10. **Two places decide, as in Consul: a server's HTTP adapter, and a client's local
    API.**
    - **On a server,** a successor to `HeaderRequestContext` resolves the token
      against the answering server's applied state, authorizes, and only then
      proposes or reads.
    - **On a client,** the local API decides for itself, with the same authorizer
      (Phase 2), from a token and policies it resolves from a server and caches. A
      forwarded read is decided by the server, under the caller's token.
    - **Resolving.** The client calls `GET /v1/acl/token/self` under the caller's
      token, and a new `POST /v1/internal/acl/resolve` for the policies and roles
      that token links to: Consul's `ACL.TokenRead`, `ACL.PolicyResolve`, and
      `ACL.RoleResolve`, over HTTP because a Qraft client talks to servers that way
      (client list, decision 2). The route answers only for what the presenting
      token links to, and needs no grant.
    - **The cache.** `client.acl.tokenTtlMs`, `policyTtlMs`, and `roleTtlMs`, each
      30 s by default.
    - **The down policy.** `client.acl.downPolicy`: `extend-cache` by default, or
      `async-cache`, `allow`, `deny`, with Consul's meanings, including the default
      policy where nothing is cached. So an application can go on reporting its TTL
      check while no server answers, and a client can leave.
    - **What this costs, as in Consul:** a revoked token keeps working on a client
      until its cache entry expires, and for as long as no server answers under
      `extend-cache`.
    - **The state machine does not check again when it applies a command.** A write
      is authorized by the leader, which holds the latest ACL state. A follower that
      is behind may honour a revoked token for a read until it catches up. Proposal
      17's invariant is not an authorization check, and is the exception.
    - **Reads are filtered, including a read of one named service or node.** The
      answer is what the token may see, with `X-Qraft-Results-Filtered-By-ACLs: true`
      when anything was removed. An instance needs both `node:read` and
      `service:read`. `GET /v1/client/service/{id}` alone answers 403, as Consul's
      `AgentService` does.
11. **What each operation needs.**

    On a server:

    | Operation | Needs |
    |---|---|
    | `/v1/catalog/register`, a node | `node:write` on the name, only when the request creates the node or changes what is recorded for it |
    | `/v1/catalog/register`, a service | `service:write` on its name in its scope, and on the name of any instance with the same ID that it replaces |
    | `/v1/catalog/register`, a takeover (client decision 8) | `node:write` on that name |
    | `/v1/catalog/register`, a rename | `node:write` on the old name and on the new |
    | `/v1/catalog/node/heartbeat` | `node:write` on the name. Qraft's own: Consul's agents have gossip |
    | `/v1/catalog/check/observe` | `service:write` on its service |
    | `/v1/catalog/deregister` | `node:write` on the node for anything on it; otherwise `service:write` on the service's name |
    | `/v1/catalog/nodes` | filtered by `node:read` |
    | `/v1/catalog/node-services/{name}` | `node:read`, filtered by `service:read` |
    | `/v1/catalog/services` | filtered by `service:read` |
    | `/v1/catalog/service/{name}`, `/v1/health/service/{name}` | filtered by `node:read` and `service:read` |
    | `GET /v1/operator/raft/configuration`, `/raft/status` | `operator:read` |
    | `DELETE /v1/operator/raft/peer` | `operator:write`, checked again by the leader |
    | `PUT /v1/acl/bootstrap` | no token, and it works once |
    | `GET /v1/acl/token/self`, `POST /v1/internal/acl/authorize`, `POST /v1/internal/acl/resolve` | any valid token, and each answers only for that token |
    | The rest of the ACL API | `acl:read` or `acl:write`; no permission reads a secret back (proposal 3) |
    | `POST /v1/acl/explain` (proposal 19) | `acl:read` |
    | gRPC `DistributedStateService` | `operator:write`, sent as `x-qraft-token` metadata, until the key/value list adds `key` rules |
    | `/health/live`, `/health/ready` | no token, ever: orchestrators call them |
    | The administrative interface's static files | no token; its API calls carry one (UI-5) |

    On a client's local API:

    | Operation | Needs |
    |---|---|
    | `GET /v1/client/self` | `client:read` on the node name |
    | `GET /v1/client/members` | forwarded; the server filters by `node:read` |
    | `PUT /v1/client/leave` | `client:write` on the node name |
    | `PUT /v1/client/token/{kind}` | `client:write` on the node name |
    | `PUT /v1/client/service/register` | `service:write` on the name, and on the name of the instance it replaces |
    | `PUT /v1/client/service/deregister/{id}` | `service:write` on the existing service; 404 if there is none |
    | `GET /v1/client/services`, `GET /v1/client/checks` | filtered by `service:read` |
    | `GET /v1/client/service/{id}` | `service:read`, and 403 without it |
    | `PUT /v1/client/check/register` | `service:write` on its service, for the new check and the one it replaces |
    | `PUT /v1/client/check/deregister/{id}`, `check/pass`, `warn`, `fail`, `update` | `service:write` on the existing check's service; 404 if there is none |
    | `PUT /v1/client/maintenance` | `node:write` on the node name |
    | `PUT /v1/client/service/maintenance/{id}` | `service:write` on the existing service; 404 if there is none |
    | `/v1/catalog/nodes`, `services`, `service/{name}`, `/v1/health/service/{name}` | forwarded under the caller's token; the server filters |
    | `/health/live`, `/health/ready` | no token, ever |

    Not covered: the Raft port, which stays trusted until transport security
    (proposal 14), and the Prometheus port.

    **Decided 2026-10-09, and moved on 2026-11-01 to Consul's path:
    `POST /v1/internal/acl/authorize` answers only for the token that presents it,**
    as `GET /v1/acl/token/self` does, and needs no grant. It takes up to 64 checks,
    and cannot ask about another token. The client no longer uses it, because the
    client decides for itself (proposal 10). The administrative interface can.

    **Decided 2026-10-09: Consul's rule for registering a service.**
    `node:write` is needed only to create or change a node. A service on a node that
    is already registered, with the node's details unchanged, needs `service:write`
    alone (section 3).
    - **Not a requirement.** No decision required a service registration to be tied
      to a node. The design's section 15 asks that the server not trust a caller's
      claim to be a node, and creating or changing a node still needs `node:write`.
    - **What it allows.** A holder of `service:write` on a name can add an instance
      of that service on any registered node.
    - **A stolen service token** works from anywhere that reaches a server. Consul
      limits that outside its ACL rules: its HTTP API binds to `127.0.0.1` by
      default, and when opened to a network it can require a client certificate.
      Qraft's servers must listen on a network, because clients reach them over the
      HTTP API (client list, decision 2). So Qraft takes the second: proposal 14.
    - **A planted instance** goes at the client's next pass in the client's tenant
      and namespace, since `ServiceReconciler` removes what the catalog holds for its
      node and its source does not want (client list, Phase 4). In another scope it
      stays until someone deregisters it. A planted instance also goes when its node
      is deregistered or reaped.
    - **The token for those removals: the client's own, and its `node:write` is
      enough.** Corrected on 2026-11-01: this list used to say that removal needs
      `service:write`, so that a planted instance could stay, but Consul's
      `vetDeregisterWithACL` allows it for this very reason. A client's node identity
      therefore removes anything on its node. A refusal is logged as a warning, and
      tried again on the next pass.
    - **Rejected: two credentials on a synced service write,** the client's own token
      for the node and the registration's for the service. It would have tied a
      registration to a node, and departed from Consul.

### Configuration and bootstrap

12. **`server.acl` in the server configuration.**
    - `enabled`, defaulting to `false`, so that existing configurations and tests are
      unchanged until they are given tokens.
    - `defaultPolicy`: `allow`, or `deny`, under which anything not allowed is refused.
      Under either, reading or writing ACLs needs a rule for it, so `allow` does not leave
      the ACL API open.
    - **Decided 2026-11-01: `defaultPolicy` has no default.** Consul's is `allow`.
      - With ACLs enabled and no `defaultPolicy`, the server refuses to start, before any
        resource opens. An operator chooses it knowingly.
      - With ACLs disabled it may be left out, so existing configurations do not change.
      - With ACLs disabled and `defaultPolicy` `deny`, the server starts and logs one critical
        warning, `ACL_DENY_NOT_ENFORCED`: the configuration asks for `deny`, but ACLs are
        disabled, so nothing is denied and every call is allowed. It is logged at WARN and
        marked `CRITICAL` in its message, without an exception, because the server goes on
        running as configured.
    - `PUT /v1/acl/bootstrap` creates the first management token, with the
      `global-management` policy. It takes an optional secret, returns the token, and
      afterwards answers 403 `permission_denied`, naming a reset index.
    - `initialManagementTokenFile`: a file holding the first management secret, a
      UUID. A server that gains leadership installs it if no token has that secret,
      through the same one-time command, so it closes the endpoint. Consul also takes
      the secret inline; Qraft's rule is that secrets are mounted files.
    - **Recovery when every management secret is lost,** either of Consul's two ways:
      write the reset index to `acl-bootstrap-reset` in the leader's data directory
      and bootstrap again, or put a new secret in the file and change leadership.
    - **A risk of following Consul here.** Consul's API binds to `127.0.0.1` by
      default, and Qraft's server listens on the network, so an open cluster's
      bootstrap goes to whoever calls first. A deployment closes that by using the
      file: the bootstrap is taken before a server serves requests that matter.
      Since 2026-11-07, at the owner's decision, the endpoint stays closed while a
      file is configured and its token is not yet installed, which closes the few
      milliseconds at a first leadership that Consul leaves open (Phase 4's first
      point).
    - **Decided 2026-10-09: revoking that token.** Every server mounts the same file.
      A leader installs the file's secret whenever no token has it, so a token deleted
      first returns at the next leadership change, while any server's file still holds
      its secret. Servers with different files each install their own. This is how
      Consul's `initial_management` token behaves, and it adds no replicated state.
    - **Decided 2026-10-27, with proposal 17: the order.** While the old management
      token is the only token that holds `acl:write` without an expiry, the lock-out
      guard refuses its deletion. So: replace the secret in every server's file; change
      leadership, by restarting the leader, so that the new secret is installed; check
      that the new management token exists; then delete the old one. Phase 3 tests the
      rule, and Phase 9 puts the order in the runbook.
    - **Decided 2026-10-27: a token installed from the file is marked so.** A token
      read shows `installedFromFile: true`. The API, the command line, and the
      administrative interface use it to warn, before a deletion, that the token returns
      at the next leadership change while any server's file still holds its secret.
    - With ACLs enabled, `server.operator.token` is refused as a conflicting setting,
      and `operator:write` replaces it. With ACLs disabled it guards removal as it does
      today.
13. **`client.acl` in the client configuration.**
    - `enabled`, `defaultPolicy`, `downPolicy`, and the cache settings of proposal 10,
      as Consul's agents have them. The default policy must match the servers'. A client
      applies proposal 12's rules for it: refused with ACLs enabled and no `defaultPolicy`,
      and the critical warning with ACLs disabled and `deny`.
    - Its tokens, each a mounted file:

      | Setting, under `client.acl.tokens` | Consul's | Used for | Falls back to |
      |---|---|---|---|
      | `clientFile` | `agent` | Registering and renewing the node, anti-entropy's read, and every removal during a pass | `defaultFile` |
      | `configFileRegistrationFile` | `config_file_service_registration` | Services and checks from the configuration file | `defaultFile` |
      | `defaultFile` | `default` | A request with no token, and a sync with no other token | the anonymous token |
      | `recoveryFile` | `agent_recovery` | The client's own endpoints with no server: `client:write` on its node name and `read` on every node | none |

      A node identity for the client's node name is the intended grant for `clientFile`,
      and service identities for its configured services for
      `configFileRegistrationFile`. A service in the file may name its own `tokenFile`.
    - A request to the local API is decided and forwarded under the caller's own token.
      Without one it uses the default token, and without that it is anonymous. A
      deployment that gives a default token gives its rights to every process on the
      host; with none, a process gets no rights merely by being on the host.
    - A service or check registered through the local API keeps the token it was
      registered with. The client stores it with the registration in `registrations.json`,
      in a file only its own user can read, and syncs that service and its checks under it.
    - `PUT /v1/client/token/{default|client|configFileRegistration|recovery}` changes a
      token while the client runs, kept across a restart only with
      `client.acl.enableTokenPersistence`. The client has no configuration reload, so this
      is how a client's token is replaced without a restart.
    - **A refused sync is a warning.** A 403 on a service, check, or removal is logged as a
      warning, marked as synced, and tried again at the next full pass. Readiness follows
      from the conditions it already has: a client whose node is refused is not registered,
      and so not ready.
14. **Tokens cross the network unencrypted until TLS exists.** Consul allows the same and
    recommends TLS. Propose a transport security list to follow this one directly: HTTPS on
    the server listener and the client's calls, and mutual TLS on the Raft port, which is
    how Consul protects server traffic. Until then, a server with ACLs enabled on a listener
    without TLS logs one startup warning, as the administrative interface does for being
    unauthenticated.

    **Decided 2026-10-09: that list makes the server's HTTP listener require a client
    certificate.** HTTPS alone hides a token on the wire. A required certificate also stops
    a stolen token being used from a machine that has none, which is the protection proposal
    11 leaves to this list. In Consul it is optional, through `verify_incoming`; that list
    says how clients, operators' tools, and the interface's users get a certificate.

### Order

15. **This list follows the client list,** which finished on 2026-11-01.
    - The local API, whose requests it must decide and forward, did not exist before it.
    - The feature validation's recommended order puts security last, after key/value,
      sessions, and tenancy. This list takes only the token and ACL core out of that item.
      The `key` and `session` rules, replicated tenants, and audit events stay with their
      own lists.

### Integrity and the administrative interface

Proposals 16 to 19 came from reviewing the administrative interface's design against this
list on 2026-10-27, and the owner decided each of them that day. None of them was in Consul.
On 2026-11-01, against the alignment, the owner kept proposals 16, 17 and 19, which remain
differences from Consul, and changed proposal 18 to Consul's behaviour.

16. **A security profile: `server.security.profile`, `production` or `development`.**
    - **Production refuses to run open.** A configuration with `production`, and with
      `server.acl.enabled` `false` or `defaultPolicy` `allow`, is refused before any resource
      opens.
    - **Development may run without ACLs.** With ACLs disabled, every call is allowed, as
      today, and the server logs one startup warning naming the profile and saying that there
      is no access control. With `defaultPolicy` `deny`, proposal 12's critical warning takes
      its place. With ACLs enabled, development behaves exactly as production, apart
      from allowing `defaultPolicy` `allow`.
    - **The management token is the superuser in both profiles.**
    - **The profile is public.** The administrative interface's bootstrap data carries the
      profile and the authentication mode, `none` or `token` (design section 12.4.1).
    - **Client mode has no profile.** A client follows what the servers enforce.
    - **The default is `development`,** changed on 2026-11-01 from a recommendation of
      `production`: Consul's ACLs are off by default, and no existing configuration then has
      to change. A production deployment says `production`.
17. **The server refuses a lock-out.**
    - A command after which no token would hold `acl:write` without an expiry is refused with
      409 `acl_lockout`. That covers deleting a token, changing its policies or roles, changing
      a policy's or role's rules, and deleting a policy or role.
    - A token with an expiry does not count, because it will lapse. Removing `acl:write` from
      the caller's own token is allowed while another token still holds it; the administrative
      interface warns about it.
    - **The state machine applies the check,** when it applies the command, not only the leader
      when it proposes. Every replica reaches the same outcome, and two commands in flight
      cannot together remove the last holder.
    - The leader's deletion of expired tokens (Phase 8) is unaffected, because an expiring token
      never counts.
    - Consul has no such guard, and recovers through the bootstrap reset instead. Recovery here
      stays as in proposal 12.
18. **A policy or role that tokens link to can be deleted, as in Consul.** Decided
    2026-11-01, replacing the decision of 2026-10-27 that such a deletion is refused.
    - The deletion succeeds, and the links are dropped when each token or role is next read
      (`fixupTokenPolicyLinks`, `fixupTokenRoleLinks`, `fixupRolePolicyLinks`).
    - **The cost:** tokens lose what it granted with no change of their own: on a server at
      once, and on a client when its cache entry expires (proposal 10).
    - **Proposal 17 still applies.** A deletion that would leave no token holding `acl:write`
      without an expiry is refused with 409 `acl_lockout`.
    - `GET /v1/acl/tokens?policy={id}` lists the tokens that link to a policy, as in Consul, so
      that an operator can see who is affected first, and for the interface's policy page.
19. **The server explains a decision: `POST /v1/acl/explain`,** needing `acl:read`.
    - It takes an accessor ID, a resource, a name, an access level, and for `service` a tenant
      and namespace. It answers allow or deny and what decided it: the policy and the rule, the
      identity, or that nothing matched and the default policy decided.
    - Phase 2's function returns the deciding rule with the decision, and both enforcement and
      this endpoint use it, so an explanation cannot differ from what the server enforces. The
      administrative interface uses this endpoint and never evaluates rules itself.
    - It reads the answering server's applied state, like every other read.
    - It is separate from `POST /v1/internal/acl/authorize`, which answers only for the presenting
      token and needs no grant.
    - The reverse question, which tokens may do something, is out of scope (section 7).

### Where this list still differs from Consul

| Point | Consul | This list | Why |
|---|---|---|---|
| Default policy (proposal 12) | `allow` unless set | No default: ACLs enabled without one are refused at startup, and `deny` with ACLs disabled logs a critical warning | The owner's decision of 2026-11-01 |
| Token secrets (proposal 3) | Stored as they are; `acl:write` reads them | A SHA-256 hash; no permission reads them | The owner's decision of 2026-11-01, following ZooKeeper |
| Proposals 16, 17 and 19 | None of them | Kept | The owner's decisions of 2026-10-27, confirmed on 2026-11-01 |
| Client certificate on the server's listener (proposal 14) | Optional | Required, in the following list | The owner's decision of 2026-10-09 |
| `server.operator.token` with ACLs disabled | No such token | Stays until Phase 9's decision | With ACLs off, Consul has no guard on removing a server; this one is the membership list's decision 6 |
| Token in the query string | `?token=` also read | Headers only | A query string reaches logs, and every Qraft route refuses a parameter it does not know |
| Rule syntax | HCL or JSON | JSON, in Consul's form | HCL needs a parser Qraft does not have, and JSON is one of Consul's two forms |
| Secrets in configuration | Inline or in a file | A mounted file | Qraft's rule for secrets |
| Name of the resource and tokens | `agent` | `client` | Qraft has only server mode and client mode |
| `/raft/status` | Consul's `/v1/status/*` have no check | `operator:read` | It shows more than a leader and peers |
| `node/heartbeat`, gRPC, `/health/*` | No counterpart | As in proposal 11 | Qraft's own routes |
| The anonymous token (Phase 1) | Created by the leader when it gains leadership | In every server's initial state | The same on every replica with no command |
| Bootstrap reset index (Phase 1) | The bootstrap's Raft index | A count of bootstraps applied | The state machine is not given an entry's index |
| Stored rules (Phase 1) | The rule text, parsed when used | Typed rules, sorted, with no rule twice | Validated once, when stored, and encoded the same on every replica |
| Identity names (Phase 1) | A lower-case DNS label | Any node or service name, 256 characters at most | Qraft's node names default to the host name |
| A service identity's scope (Phase 1) | The token's partition and namespace | Its own tenant and namespace, for its write and its reads of services (decided 2026-11-05) | A Qraft token has no scope |
| Nested rules' rank (Phase 2) | Not documented, and Enterprise's source is not public | The name first, then the scope (decided 2026-11-05) | The most specific rule decides, as Consul does for names |
| Secret and expiry unchanged on update (Phase 1) | Checked by the leader's token writer | Checked by the state machine | Every replica refuses the same |
| An observation of an instance that is not registered (Phase 4) | A check is registered through the catalog, which needs `node:write` or `service:write` | 404 `service_not_found` before any permission is asked | `/v1/catalog/check/observe` is Qraft's own route, and an instance that does not exist has no service name to ask about |

## 5. Rules

- Red before green. (The rule that safety guards need recorded mutations was removed by the
  owner on 2026-10-11, from every task list. It stood here for every authorization guard.)
- Deterministic tests, with injected clocks for expiry and for the client's cache. No Mockito.
- HTTP behaviour is tested against real JDK HTTP servers, on both sides for the client.
- Every ACL command has a replica-determinism test. Fixtures prove that a WAL and a snapshot
  written before ACLs still load.
- A test searches the captured log of each ACL scenario for the secrets it used, and fails if
  it finds one. A secret passes through the server on its way to being hashed, so nothing may log
  a request's body.
- A denial is not an error. It is logged at a level below ERROR and without an exception, so it
  needs no entry in `IntentionalErrorFixture`.
- A run is accepted only after its retained Maven, application, subprocess, and Docker logs have
  been read and hold no unflagged error (`AGENTS.md`, and `docs/TESTING.md`, "Intentional error
  flags and log auditing"). Added 2026-10-08.
- The design document changes in the same phase as the behaviour.
- Each phase ends with `mvn install`. Phases that change the runtime also run the end-to-end
  suite and the Docker suite on a fresh image.
- A phase without its own exit line exits on these rules, with each of its endpoints and commands
  tested over real HTTP. Added 2026-10-08, for Phases 5 and 8.
- The owner commits. The main verification runs on Jenkins
  ([`docs/JENKINS.md`](../JENKINS.md)).

## 6. Tasks

### Phase 1. Replicated ACL state

- [x] Add the token, policy, and role values, with the validation of proposals 2 and 6 to 9.
- [x] Add the commands: create, update, and delete a token, a policy, and a role; bootstrap, used
  by the endpoint and the management file alike; and delete expired tokens. Add their protobuf
  messages with new field numbers, and their place in the snapshot.
- [x] Hold a secret only as its SHA-256 hash, taken before the command is proposed, and look a token up by
  that hash (proposal 3). A test on real storage searches the WAL and a snapshot for the secret, and fails if
  it finds it.
- [x] Refuse a change to a token's secret or expiry.
- [x] Build in the `global-management` and `builtin/global-read-only` policies and the anonymous token.
  None of them can be deleted, and the policies' rules cannot change.
- [x] Allow the deletion of a policy or role that a token links to, and drop the links when the token or role is
  next read (proposal 18). A test proves that the deleted policy no longer grants anything, and that a deletion
  the lock-out guard refuses leaves the links in place.
- [x] Apply the lock-out guard in the state machine (proposal 17). Include a determinism test of two
  commands in flight that would together remove the last token holding `acl:write` without an expiry.
- [x] Record `installedFromFile` on the token the management file installs (proposal 12).

**Exit:** Codec, determinism, and legacy-fixture tests.

**Record, 2026-11-04.** Built test first, checked against Consul's source on its `main` branch
(`agent/structs/acl.go`, `acl/acl.go`, `acl/validation.go`, `agent/consul/state/acl.go` and `acl_schema.go`,
`agent/consul/acl_endpoint.go`, `agent/consul/auth/token_writer.go`, `agent/consul/leader.go`). No route, setting or
command line uses it yet; with ACLs not yet configurable, a server's only change is that its snapshots carry an `acl`
section.

- **Where it lives.** The rule model and the identities are in `common.acl` (`AclResource`, `AclAccess`, `AclScope`,
  `AclRule`, `AclRules`, `NodeIdentity`, `ServiceIdentity`), because Phase 2's authorizer is shared with the client,
  which may use only Common. The stored values and the state are in `state.acl` (`AclToken`, `AclPolicy`, `AclRole`,
  `AclSecrets`, `AclBuiltins`, `AclRefusal`, `AclState`, and the read-only `AclView` that
  `QraftStateStore.getAcl()` answers). The commands are `state.AclCommand`, encoded by `state.AclCodec` as
  `AclCommandProto`, field 5 of `RaftCommandMessage`.
- **Rules are held typed, not as text.** A policy holds its rules as `AclRules`, sorted and with no rule twice, and the
  WAL and snapshot hold them in that form. Phase 2 parses Consul's JSON form into it and the authorizer evaluates it;
  Phase 5 writes it back as JSON. Consul stores the rule text and parses it when used.
- **A refusal is a result.** `RaftCommandResult` gained `Refused`, with a code from `AclRefusal`: `accessor_in_use`,
  `secret_in_use`, `reserved_id`, `id_in_use`, `name_in_use`, `policy_not_found`, `role_not_found`,
  `secret_immutable`, `expiry_immutable`, `builtin_immutable`, `acl_lockout`, `acl_bootstrapped` and
  `invalid_reset_index`. A missing token, policy or role to update or delete is `NotFound`.
- **Built in, not created at leadership.** The two policies and the anonymous token are part of every server's initial
  state, so they need no command and a snapshot from before ACLs restores them. Consul's leader creates the anonymous
  token when it gains leadership. Phase 3 therefore no longer creates it. The anonymous token's creation time is the
  epoch, the same on every server.
- **Consul's IDs and names.** `global-management` is `00000000-0000-0000-0000-000000000001`, `builtin/global-read-only`
  `...0002`, and the anonymous token's accessor `...0002` with the secret `anonymous`. IDs beginning
  `00000000-0000-0000-0000-0000000000` are reserved, so a created token, policy or role cannot take one. This list
  had named the read-only policy `global-read-only`; Consul's `main` renamed it and reserves the `builtin/` prefix.
- **IDs and secrets.** An accessor, policy or role ID is a canonical UUID, held in lower case, because Consul indexes
  it as a UUID's bytes. A secret is hashed as written, because Consul compares secrets case-sensitively
  (`indexFromStringCaseSensitive`).
- **Names.** Policy names follow `ValidatePolicyName` (128 characters, at most one `/`, no `builtin/`), role names
  `validRoleName` (256), and both are unique regardless of case, as Consul's name indexes lower-case them.
- **Identities, departing from Consul.** A node identity takes any name a node can register under, because Qraft's
  node names default to the host name and may hold a dot, a capital or a slash; Consul allows only a lower-case DNS
  label. A service identity carries its service's tenant and namespace, `default` unless given, because a Qraft token
  has no scope; Consul Enterprise takes them from the token's partition and namespace.
- **Expiry.** An expiry lies between one minute and 24 hours after the token's creation time, both stamped by the
  leader, so the value checks Phase 8's bounds without a clock.
- **Updates keep what the server owns:** a token's creation time and its `installedFromFile` mark. The secret and
  the expiry must be the ones it has; Consul's token writer checks this on the leader, and here the state machine does.
- **The bootstrap index is a count.** The state machine is not given an entry's Raft index, so the reset index is the
  number of bootstraps applied, kept in the replicated state. Consul uses the bootstrap's Raft index. Either way an
  operator copies it from the refusal into `acl-bootstrap-reset`.
- **The lock-out guard** counts a token that has no expiry and whose policies, directly and through its roles, merge
  to `write` for `acl`; links to deleted policies and roles grant nothing. Before any token holds `acl:write`, nothing
  is a lock-out, so a cluster can be set up before its bootstrap.
- **Tests,** each seen red on a stub before the code existed, except two written after the code they cover, which
  passed at their first run: `AclFixtureTest` and `AclSecretStorageTest`. `AclRulesTest` 7, `AclIdentityTest` 3,
  `AclValuesTest` 7, `AclStateStoreTest` 15, `AclCommandCodecTest` 4 (its field-number test pins the schema and
  passed on the stub), `ReplicaDeterminismTest` 3 new, `AclFixtureTest` 3 and `AclSecretStorageTest` 1. The fixture
  `fixtures/acl/snapshot-before-acl.json` was written by the code at `1eb1955`, before any of this.
- **Exit met on 2026-11-04,** on a local run of the three suites (Jenkins does not answer from the `markr`
  workstation): default 1,502 with every coverage gate met, end-to-end 13, Docker and `slow` 44, no failures, errors
  or skips; 236 retained logs with 683 ERROR lines, all flagged, and 585 stack traces, none under an unflagged
  header.
- **Decided by the owner on 2026-11-05, and fixed at the start of Phase 3: a server from before ACLs that meets an
  ACL entry.** As it stood: It reads field 5 of `RaftCommandMessage`
  as an unknown field, decodes the entry as a no-op, and applies nothing, where it should stop as it does for a command
  it cannot apply. That divergence needs a rolling upgrade that writes an ACL command before every server runs this
  code; nothing writes one until Phase 3. Consul marks such entries with `IgnoreUnknownTypeFlag` to choose. The fix
  belongs in the codec, before Phase 3, and is the owner's to order.

### Phase 2. The authorizer

- [x] Parse Consul's JSON rule form, with the `tenant` and `namespace` nesting of proposal 7, refusing
  an unknown resource or field.
- [x] One pure function from a token's rules, a resource, a name, a scope, and an access level to allow
  or deny, together with the rule or identity that decided it (proposal 19). The server and the client
  both use it.
- [x] The default policy, and that neither `allow` nor `deny` grants `acl` without a rule for it.
- [x] Table-driven tests of exact against prefix, longer against shorter prefix, the merge of equally
  specific rules, the scope match and the empty prefix, both identities, and a role.

**Exit:** A test, red before green, for each precedence rule and for the scope check.

**Record, 2026-11-05.** Built test first, checked against Consul's source on its `main` branch
(`acl/policy.go`, `policy_authorizer.go`, `policy_merger.go`, `static_authorizer.go`, `chained_authorizer.go`,
and `agent/structs/acl_ce.go`), with the owner's two decisions of the same day in proposals 7 and 9. Every test
of the phase was seen red on stubs, on its assertions, before the code existed. Still nothing enforces ACLs.

- **The parser,** `common.acl.AclRulesJson.parse`, reads Consul's JSON form into Phase 1's `AclRules`; there is
  no second model. `node`, `service` and `client` with their `_prefix` forms map names to `{"policy": ...}`;
  `operator` and `acl` take the access itself, `read`, `write` or `deny`, in lower case. `tenant` is given only at
  the top, `namespace` at the top or in a tenant, and only service rules nest. It refuses an unknown resource or
  field, `key` and `session` (named as left to their lists), Consul's `intentions`, `list`, a key written twice,
  and a rule given twice, also through `default`. A refusal names its place, as
  `ACL rules, at tenant.acme.namespace.prod.servcie: ...`. Phase 5 writes rules back in the same form.
- **The authorizer,** `common.acl.AclAuthorizer.decide(grants, defaultPolicy, request)`, is one pure function in
  Common, which the client may use. It ranks the matching rules by name and then by scope (proposal 7), merges
  equals across the token's grants, and decides by the winning access as Consul's `enforce` does: `write` allows
  reading and writing, `read` allows reading and refuses writing, `deny` refuses both. A matched rule never falls
  back to the default policy, so a `read` rule under `allow` still refuses a write, as in Consul. Where no rule
  matches, the default policy decides, and `acl` is refused under either.
- **What decided** comes back with the answer (`AclDecision.ByRule` with the rule and its `AclOrigin`, or
  `ByDefault` with the policy), for proposal 19's explanation. Among merged equals, the first grant in the order
  given with the winning access is named.
- **Grants.** `AclGrant` holds a policy's rules, or the rules an identity stands for (proposal 9, as decided), each
  with its origin and the role it comes through. `AclView.grants(token)` lists a token's grants: its own policies,
  node identities and service identities, then those of each role, leaving out what was deleted.
- **The lock-out guard now uses the authorizer** (`AclState.holdsAclWrite`), so the two cannot drift.
  `AclRules.mergedAccess`, which Phase 1 had added for the guard, is removed, and its test moved to
  `AclAccess.merge`. Phase 1's lock-out tests pass unchanged.
- **Names are compared as written,** as Consul's radix tree compares them, so a rule for `web-1` does not match
  `WEB-1`. Qraft's catalog compares node names regardless of case (client list, decision 8); Phase 7, which
  enforces node identity, must decide whether a takeover or rename under another case needs a rule for that case.
  Decided in Phase 7: it needs `node:write` on the spelling the catalog holds as well (Phase 7 record).
- **Tests:** `AclRulesJsonTest` 5, `AclAuthorizerTest` 10 (table-driven rows for each precedence rule, the scope
  checks, both identities and a role), and one new `AclStateStoreTest` case for a token's grants. One test row was
  wrong at first, asking for a service without a scope, and was corrected; the request check refused it, as it
  should.
- **Exit met on 2026-11-05** by `mvn install` on a copy of the tree: default 1,518 with every coverage gate met, no
  failures, errors or skips; 45 retained logs with 479 ERROR lines, all flagged, and 456 stack traces, none under an
  unflagged header; no JVM crash file. The end-to-end and Docker suites were not run: no runtime path calls the
  authorizer yet, and the lock-out guard decides the same as before.

### Phase 3. Configuration and the first token

- [x] First, decided by the owner on 2026-11-05: refuse an entry holding a command this server does not know,
  rather than read it as a no-op (Phase 1's open point).
- [x] Add `server.acl.enabled`, `defaultPolicy`, and `initialManagementTokenFile`, validated before any
  resource opens. Refuse an unreadable file, a secret that is not a UUID, enabled ACLs with no
  `defaultPolicy`, and `server.operator.token` together with enabled ACLs.
- [x] `PUT /v1/acl/bootstrap`: once only, then 403 with the reset index. The reset through
  `acl-bootstrap-reset` in the leader's data directory, removing the file when used.
- [x] The leader installs the management token when it gains leadership and none has that secret, through
  the bootstrap command, or, once the bootstrap is used, a token creation, as Consul's `initializeACLs` does.
  Doing it twice changes nothing. The anonymous token needs nothing: it is built in (Phase 1).
- [x] Log the effective settings without the secret.
- [x] Add `server.security.profile` (proposal 16), `development` by default. Refuse `production` with ACLs
  disabled or `defaultPolicy` `allow`, before any resource opens. Log the one startup warning for
  `development` with ACLs disabled, or in its place, with `defaultPolicy` `deny`, the critical warning
  `ACL_DENY_NOT_ENFORCED` (proposal 12). A test asserts which one is logged for each combination.
- [x] Test how the management token is revoked (proposal 12): a deleted token is installed again while a
  server's file still holds its secret, and is not once every file holds a new one. While the old token is
  the only holder of `acl:write` without an expiry, its deletion is refused with `acl_lockout`.

**Exit:** Lifecycle tests on real storage: a restarted cluster keeps its tokens, a new secret in the file is
installed at the next leadership, and a bootstrap reset works once.

**Record, 2026-11-05.** Built test first, checked against Consul's `agent/consul/acl_endpoint.go` (`Bootstrap`,
`fileBootstrapResetIndex`), `agent/consul/state/acl.go` (`ACLBootstrap`) and `agent/consul/leader.go`
(`initializeACLs`). Still nothing enforces ACLs on a request; that is Phase 4.

- **An entry from a newer server is refused** (`ProtobufCommandCodec`): an entry with no command this server knows
  but a field it does not know is refused with "Log entry holds a command this server does not know, in field N of
  RaftCommandMessage", where it was read as a no-op. A follower then refuses the append and writes nothing, and a
  server whose WAL holds one stops its recovery, leaving the WAL, as `UnknownCommandEntryTest` shows; an empty entry
  is still a no-op, and unknown fields inside a known command are still read past (`UnknownCommandCodecTest`).
  Servers already deployed still read such an entry as a no-op, so the rule for a rolling upgrade is that every
  server runs this version before ACLs are enabled. Design section 14.8. Field 5, which ACL commands took in Phase 1,
  belonged to a job queue command only in the layout replaced on 2026-09-09, which no supported WAL holds.
- **The settings** (`AppConfig`, `server.config.SecurityProfile`): `server.acl.enabled` (off), `defaultPolicy`
  (`allow` or `deny`, no default), `initialManagementTokenFile`, and `server.security.profile` (`development` by
  default). `validate()` refuses, before any resource opens: enabled ACLs without a default policy; the operator
  token with enabled ACLs; a management file without enabled ACLs, unreadable, or not holding a UUID outside the
  reserved range, without ever quoting the file; and `production` without enabled ACLs or with `allow`. Consul
  ignores its management token with ACLs disabled; a file with nothing to do is refused here.
- **Startup logging:** an `ACL configuration:` line with the file's path and not its secret, and exactly one warning:
  `Security profile development with ACLs disabled: every call is allowed...`, or in its place
  `CRITICAL ACL_DENY_NOT_ENFORCED: ...` when `defaultPolicy` is `deny`, or, with ACLs enabled, proposal 14's warning
  that tokens cross the network unencrypted until TLS exists. `AppConfigAclTest` asserts which for each combination.
- **The bootstrap endpoint**, `PUT /v1/acl/bootstrap` (`HttpApiServer.bootstrapAcl`, with `AclHttpSettings`): 401
  `acl_disabled` with ACLs disabled; a token with `global-management`, Consul's description "Bootstrap Token (Global
  Management)", and a generated secret or one supplied as `bootstrapSecret`, a UUID outside the reserved range;
  the secret in the answer only (`AclTokenAnswer`); 403 `permission_denied` naming the reset index (`resetIndex`)
  once bootstrapped; `acl-bootstrap-reset` in the data directory (`server.raft.storage.path`) read as Consul reads
  it, removed when used, and kept when its index is wrong; 400 for a body or a reset file that cannot be read, with
  Jackson's original message, which never quotes the body; and on a follower 503 `leader_unavailable` with the
  leader's ID, as every other write. Consul forwards to the leader; Qraft's writes do not forward.
- **The management file** (`server.acl.ManagementTokenInstaller`, attached in `QraftServerService`): whenever the
  server leads and no token has the file's secret, it installs the token, through the bootstrap while none has been
  applied and through a creation afterwards, including when the endpoint's bootstrap is applied first, with
  Consul's description "Initial Management Token" and `installedFromFile`. A proposal that fails is tried again every
  second while the server leads, as Consul's leader loop retries.
- **Tests,** seen red on stubs: `UnknownCommandCodecTest` (one of three; two guard what must not change),
  `UnknownCommandEntryTest` 2, `AppConfigAclTest` 7 of 8 (one pins the defaults), `ManagementTokenInstallerTest` 6
  of 7 (one guards a closed installer), `HttpApiServerAclBootstrapTest` 7. Written after the code they cover and
  passing at their first run: `ManagementTokenLifecycleTest`, the exit's test on real storage across a three-server
  cluster and two restarts, and `QraftServerAclStartupTest`, a real server that installs its file's token. The
  bootstrap reset is tested over HTTP with a real reset file and in-memory Raft storage. `ManualExpiryTimeHelper` is
  now public, so that the installer's tests share it. The recovery refusal is declared as
  `RAFT_RECOVERY_UNKNOWN_COMMAND`, logged by both recovery log sites, as a corrupt WAL's is.
- **Exit met on 2026-11-05,** on a local run of the three suites from a copy of the tree at `05524be`: default 1,547
  with every coverage gate met, end-to-end 13, Docker and `slow` 44, no failures, errors or skips, and no standby
  during the run. Its log audit, read on 2026-11-06 with a checker that matches error text in any case, found 905 of
  912 error lines flagged and no unflagged stack trace, and three causes for the seven others, each also in the
  accepted runs of Phases 1 and 2, where only upper-case `ERROR` was counted. Two were fixed on 2026-11-06:
  `QraftClientTest`'s fake server had no route for the observation a TTL report publishes, so the client logged the
  JDK server's HTML 404 as "a malformed error response", and now it has one; and `DockerServerReplacementTest`'s
  deliberate removal with a wrong operator token printed `Error 403: ...`, which the Docker audit did not read as an
  error, and now does, through the declared `RAFT_OPERATOR_TOKEN_REFUSED` (`docs/TESTING.md`). The third is open
  for the owner: Grafana's own `level=info` retries of "database is locked" on its SQLite store at startup in
  `DockerStartCommandsTest`. The logs are in `logs/local-run-2026-11-05-acl-phase3/`.

### Phase 4. Enforcement on the server

- [x] Replace `HeaderRequestContext` with a context that resolves the token and carries the principal. Keep
  the scope headers as the caller's choice.
- [x] Authorize every route as proposal 11's server table sets out. Filter reads, and send
  `X-Qraft-Results-Filtered-By-ACLs`.
- [x] Answer 403 `token_not_found` and 403 `permission_denied` in the standard error envelope, with the
  request ID and never the secret.
- [x] Move the operator endpoints from the operator token to `operator` rules. A forwarded removal carries the
  caller's token, and the leader authorizes it again.
- [x] Require a token on the gRPC `DistributedStateService`.
- [x] Leave `/health/live` and `/health/ready` open, and prove it.

**Exit:** One test per row of the server table, each red before green. With ACLs disabled, every existing test
passes unchanged.

**Record, 2026-11-06.** Built test first, after Consul's `vetRegisterWithACL` and `vetDeregisterWithACL`
(`agent/consul/catalog_endpoint.go`), its read filters (`agent/structs/aclfilter/filter.go`,
`CheckServiceNode.CanRead`), its token order (`agent/http.go`) and its `PermissionDeniedError` (`acl/errors.go`), as
section 3 read them on 2026-11-01; the source was not read again for this phase. The rows of proposal 11's server
table for the ACL API itself, `token/self`, `authorize`, `resolve` and `explain`, come with their routes in Phase 5.

- **Resolving.** `server.acl.AclResolver` resolves the token from `X-Qraft-Token`, and otherwise
  `Authorization: Bearer`, against the answering server's applied state to an `AclPrincipal`, which decides through
  Phase 2's authorizer. No token is the anonymous token. With ACLs disabled every principal is unrestricted and a
  token is ignored, as in Consul. `server.http.TokenRequestContext` replaces `HeaderRequestContext` and carries the
  principal; `AclHttpSettings` gained the default policy.
- **Refusals.** 403 `token_not_found`, "ACL not found", and 403 `permission_denied` in the words of Consul's
  `PermissionDeniedError`: `Permission denied: token with AccessorID '<id>' lacks permission 'node:write' on
  "web-1"`, with `in tenant "<t>" namespace "<n>"` for a service. Both are in the standard envelope with the request
  ID. A refusal is logged at INFO with the accessor ID and never the secret: proposal 5's rule that a denial is not
  an error.
- **Writes are authorized before anything is proposed,** the whole body of a registration first. A registration
  needs `node:write` when it creates the node or changes a field a client describes (name, ID, address, datacenter,
  region, metadata), Consul's `ChangesNode`. Every registration of a Qraft client carries a new
  `qraft.registrationId`, so a client's node registration always needs it. A node ID registered under another name
  is a rename, which needs `node:write` on the old name as well. A service needs `service:write` in its scope, and on
  the name of the instance it replaces. A claim to a name another node holds is authorized first and then judged by
  the store as before, so a token without `node:write` gets 403 rather than 409. For deregistration, `node:write` on
  the node removes anything on it; without it, an unknown service ID is 404 `service_not_found`, Consul's
  "Unknown service ID", and a known one needs `service:write` on its name.
- **Reads are filtered,** as proposal 10 decided, and `X-Qraft-Results-Filtered-By-ACLs: true` is sent only when
  something was left out.
- **Operator.** `operator:read` for the Raft configuration and `/raft/status`. A removal needs `operator:write` on
  the receiving server, and again in `MembershipService`, on that server and on the leader, through
  `raft.RemovalAuthorizer`. It is the operator token with ACLs disabled, as before, and `AclRemovalAuthorizer` with
  them enabled, so the Raft port's removal is checked too. `MembershipService.authorizedBy` takes it.
- **gRPC.** `server.api.AclGrpcGuard` intercepts `DistributedStateService` and needs `operator:write` from
  `x-qraft-token` metadata, answering `PERMISSION_DENIED` with `token_not_found: ...` or `permission_denied: ...`.
  With ACLs disabled the service is not wrapped.
- **Open:** `/health/live`, `/health/ready`, the bootstrap, which ignores a token as Consul's does, and the
  administrative interface's files.
- **What a deployment cannot do yet.** A client sends no token until Phase 6, so a cluster with ACLs enabled under
  `deny` refuses its clients' registrations, and under `allow` lets them through as the anonymous token.
- **Tests.** Seen red on stubs, on their assertions: `AclResolverTest` 5 of 6 (one pins ACLs disabled),
  `HttpApiServerAclEnforcementTest` 21 of 24 (three pin what must not change: a token ignored with ACLs disabled,
  health, and the bootstrap), `AclGrpcGuardTest` 3 of 4 (one pins ACLs disabled), and the new case of
  `QraftServerAclStartupTest`, a real server whose removal and gRPC API were not yet wired. Two of the enforcement
  tests passed on the stubs at first because they asserted only successes; each gained a refusal and was seen red.
  Written after their code and passing at their first run: `MembershipServiceTest`'s case for the leader's own
  decision, after `RemovalAuthorizer` was drawn out of the existing operator-token check, which the existing
  membership and operator tests guard; and `HttpApiServerAdminUiTest`'s case for the interface's files.
- **A race in Phase 3's test, found here.** `QraftServerAclStartupTest` asked for a bootstrap until it was refused,
  but a bootstrap that reaches the new leader before its management installer proposes is applied. The second test
  of the class met it at its first run. The test now waits for the installer's log line before it asks. The same
  window exists in production; see the first point below.
- **Exit: not yet met.** The run of 2026-11-06 passed the default suite (1,587, every coverage gate met) and the
  end-to-end suite (13), and failed in the Docker stage on two causes outside this phase: a deliberate removal
  refusal in `DockerMembershipResizeTest` that the new command-line audit caught, now declared; and a Raft Pre-Vote
  deadlock in `DockerClientTopologyTest`, whose fix reverses the Pre-Vote list's decision 5 and was kept by the
  owner on 2026-11-07. Both are in the handover of 2026-11-07, section 3. Jenkins build 64, on `b33d594`, passed
  every suite (default 1,598, end-to-end 13, Docker 44) but not its log audit: an interrupt at server shutdown
  cost the end-to-end log its lines, and Grafana's retries remained with WAL mode. Both are fixed (`bb1101a`).
- **Exit: met on 2026-11-07,** on Jenkins build 65 (`bb1101a`): default 1,603 with every coverage gate met,
  end-to-end 13, Docker 44, no failures, errors or skips; 1,403 error lines, all flagged but the console's echo of
  the commit message; 1,048 stack traces, none unflagged; no JVM crash file (`docs/JENKINS.md`, "Build 65").

**Two points for the owner, raised in Phase 4.**

1. **A bootstrap can beat the management file.** `ManagementTokenInstaller` proposes when its leadership listener
   fires, and nothing stops `PUT /v1/acl/bootstrap` from being applied first by a newly elected leader. The caller
   then holds a management token, and the file's token is created afterwards. Proposal 12 says the file closes the
   endpoint, and the risk it names, an open cluster's bootstrap going to whoever calls first, therefore remains for
   a few milliseconds at each first leadership. Consul's `initializeACLs` runs the same way, from the leader's loop.
   Options: keep Consul's behaviour and say so in Phase 9's runbook; or, while a management file is configured,
   refuse the endpoint until the file's token is installed. The second keeps the reset-file recovery working only
   after the installation.
   **Decided 2026-11-07 by the owner: the second, and built** (uncommitted), test first. `AclHttpSettings` carries
   the hash of the file's secret, never the secret, and `HttpApiServer.bootstrapAcl` answers 503
   `management_token_pending`, retryable, without proposing, while no token in the applied state has it. Once the
   token is in, a bootstrap has been applied, so the endpoint answers 403 with the reset index and the reset file
   allows one more, as before. `HttpApiServerAclBootstrapTest`: the refusal was seen red (`expected: <503> but was:
   <200>`, the window itself), and the two cases for afterwards passed before and after. Checked locally with the
   ACL classes it touches, 49 tests, no error line or stack trace in their logs. Design section 16.
2. **Grafana's "database is locked" retries,** above in Phase 3's exit: Grafana's own INFO lines, carrying error
   text, from contention on its SQLite store at startup. Grafana's `[database] wal = true`
   (`GF_DATABASE_WAL=true`) puts SQLite in write-ahead mode, which lets readers and a writer proceed together but
   still allows one writer, so it may reduce the retries without ending them. Which to try, or whether to declare
   them, is the owner's.
   **Decided 2026-11-07 by the owner: WAL first** (uncommitted), test first. Both observability compose files set
   `GF_DATABASE_WAL=true`, pinned by `DockerDeploymentContractTest`, seen red and then green. Only a Docker run of
   `DockerStartCommandsTest` shows whether the retries are gone; if any remain, they are declared there, with that
   run as the evidence.
   **Then declared, 2026-11-07, at the owner's decision:** build 64 confirmed WAL in effect and still logged the four
   retries, since SQLite allows one writer either way, so WAL mode and its contract test are removed. The Docker
   audit now fails a container line below error level that carries an `error=` or `err=` field unless it is
   declared, and `DockerStartCommandsTest` declares `GRAFANA_SQLITE_LOCK_RETRY`, which matches only Grafana's retry
   line. `docs/TESTING.md`.

### Phase 5. The ACL API and commands

- [x] `PUT /v1/acl/token`, `GET /v1/acl/tokens` with `policy`, `role`, and `servicename`, `GET`, `PUT`, and
  `DELETE /v1/acl/token/{accessorId}`, `PUT /v1/acl/token/{accessorId}/clone`, and `GET /v1/acl/token/self`.
  A request cannot delete the token it was made with.
- [x] `PUT /v1/acl/policy`, `GET /v1/acl/policies`, `GET`, `PUT`, and `DELETE /v1/acl/policy/{id}`, and
  `GET /v1/acl/policy/name/{name}`. The same for roles.
- [x] Return a secret only in the response that made it: create, clone, or bootstrap. Every later read shows `<hidden>`,
  under any permission, and never the hash (proposal 3).
- [x] `POST /v1/internal/acl/authorize`, and `POST /v1/internal/acl/resolve` for the client.
- [x] `POST /v1/acl/explain` (proposal 19), and `qraft acl explain`.
- [x] Answer 401 `acl_disabled` and 409 `acl_lockout` in the standard error envelope, with the names that explain
  them.
- [x] `qraft acl bootstrap`, and `qraft acl token|policy|role create|list|read|delete`, taking the caller's token
  from `--token-file` as `RaftOperatorCommand` does.

**Phase 5 record, 2026-11-07** (uncommitted), test first, after Consul's `agent/acl_endpoint.go` in camel case.

- **The routes** are in `server.http.AclApiRoutes`, one class beside `HttpApiServer`, which shares its helpers. Every
  route but `token/self`, `internal/acl/authorize` and `internal/acl/resolve` needs `acl:read` to read and `acl:write`
  to change; those three answer only for the presenting token. With ACLs disabled each answers 401 `acl_disabled`. A
  read is answered from the answering server's applied state; a change goes to the leader, and a follower answers 503
  `leader_unavailable`, as every other write.
- **Tokens.** A create takes an accessor and secret or generates them (each a UUID outside the reserved range), links
  to policies and roles by ID or by name, node and service identities, and `expirationTime` or `expirationTtl`, as
  Consul's `30m` or `1h30m` or in ISO-8601; the leader stamps the creation time. An update replaces the description,
  links and identities, keeps the secret and expiry, and refuses a change to either with `secret_immutable` or
  `expiry_immutable`. A clone gets a new accessor and secret and the same links. A request cannot delete its own
  token (400 `self_deletion`, Consul's words). The list takes `policy`, `role` and `servicename`.
- **The secret is answered once,** by the create or clone that made it; every later answer shows `<hidden>` and no
  answer shows the hash (proposal 3). A supplied secret is answered by its create too, as the bootstrap already did
  and as Consul does; proposal 3's "not at all when the caller supplied it" is not followed, because the caller holds
  it anyway and the bootstrap had set the precedent.
- **Policies and roles,** by ID and by name, with `GET /v1/acl/roles?policy=`. A policy's rules are given as a JSON
  object, or a string holding one as Consul's `Rules` is, and answered as an object through the new
  `AclRulesJson.write`, the inverse of `parse`. A built-in policy may be renamed and described, not given other rules.
- **Refusals** keep the state machine's code: 409 for a conflict (`accessor_in_use`, `secret_in_use`, `id_in_use`,
  `name_in_use`, `acl_lockout`), 400 for anything else; 404 `acl_not_found` for something that does not exist; 400
  `invalid_token`, `invalid_policy`, `invalid_role`, `invalid_authorization` or `invalid_explanation` for a body that
  cannot be read, with Jackson's original message, which never quotes the body.
- **`authorize`** takes up to 64 checks of `resource`, `segment`, `access`, and for a service `tenant` and
  `namespace` (`default` when left out), and answers each with `allow`. **`resolve`** answers the presenting token,
  hidden, with every policy it links to directly or through its roles, rules included, and those roles: what Phase 6's
  client caches. **`explain`** takes an accessor ID and a request, and answers `allowed`, `decidedBy` (`rule` or
  `default`), the default policy, and for a rule the policy, node or service identity and role it came through and
  the rule itself, from the same `AclAuthorizer.decide` that enforcement uses.
- **`qraft acl`** (`runtime.AclCommandLine`): `bootstrap`; `token create|list|read|delete`, with `read --self`;
  `policy create|list|read|delete`, with `--rules` or `--rules-file`; `role create|list|read|delete`; and `explain`.
  The caller's token comes from `--token` or `--token-file`, as `qraft operator raft` takes it, and a refusal is
  printed as `Error <status>: <code>: <message>`, which the Docker audit reads as a command-line error.
- **Tests:** `AclRulesJsonTest` (two cases), `HttpApiServerAclApiTest` (18) and `AclCommandLineTest` (8), each seen
  red on a stub first and then green, with the bootstrap, enforcement and operator command tests unchanged.

### Phase 6. The client

- [x] Add `client.acl`, with its settings and tokens (proposal 13), validated at startup: refuse enabled ACLs with no
  `defaultPolicy`, and log `ACL_DENY_NOT_ENFORCED` with ACLs disabled and `deny`.
- [x] Send the client's own token, with its fallback, on every call it makes for its node, including anti-entropy's
  read and every removal during a pass.
- [x] Resolve and cache tokens, policies, and roles, with the TTLs and the four down policies (proposal 10), on an
  injected clock.
- [x] Authorize every route of proposal 11's client table on the client, with the 404 for what does not exist after
  the authorization, as Consul's order is.
- [x] Forward discovery reads and `members` under the caller's token, then the default token. A token holding a
  control character is refused, as a scope header is (client list, Phase 7).
- [x] Keep each API registration's token with it in `registrations.json`, readable only by the client's user, and
  sync that service and its checks under it, falling back as proposal 13 sets out.
- [x] Treat a refused sync as a warning, marked synced until the next full pass.
- [x] Recognise the recovery token on the client, with no server.
- [x] `PUT /v1/client/token/{kind}` and `client.acl.enableTokenPersistence`, with persisted tokens readable only by the
  client's user (proposal 3).

**Exit:** One test per row of the client table, each red before green. `ClientServerContractTest` covers every call
from the client to a server with a sufficient token, an insufficient one, and none. A test of each down policy with
no server reachable: a TTL check's report under a cached token, a leave under the recovery token.

**Phase 6 record, 2026-11-07** (uncommitted), test first, after Consul's agent (`agent/acl.go`, `agent/token`,
`agent/local/state.go`). Client code may depend only on the client and common packages, so the client builds grants
itself from the servers' answer, with the shared `common.acl` types, and the permission-denied message moved to
`common.acl.AclDenial`, which the server's `AclPrincipal` now uses too.

- **Settings** (`client.acl.ClientAclSettings`, parsed by `ClientConfiguration` under `client.acl`): `enabled`,
  `defaultPolicy`, `downPolicy`, `tokenTtlMs`, `policyTtlMs`, `roleTtlMs`, `enableTokenPersistence` and `tokens`, with
  unknown fields refused; enabled ACLs need a default policy, and `deny` with ACLs disabled is the critical warning
  `ACL_DENY_NOT_ENFORCED`. A start logs `Client ACL configuration:` without a token. A configured service may name its
  own `tokenFile`.
- **Tokens** (`client.acl.ClientTokens`): each from its mounted file, refused at startup when it cannot be read, is
  blank, or holds a character a header cannot carry, and never quoted; the table's fallbacks; the recovery token,
  compared in constant time. `PUT /v1/client/token/{kind}` replaces one with `client:write` on the node; with
  persistence it is kept in `acl-tokens.json`, written owner-only through the new `DurableFile.writePrivate`, and beats
  its file at the next start.
- **The calls to the servers** (`client.catalog.RequestTokens`, `client.service.ServiceTokens`): the client's token for
  its node's registration, renewal and removal, anti-entropy's read, a lookup and every service removal; a service's
  own token for its registration and its checks' observations (an API registration's, else the default; a file
  service's `tokenFile`, else `configFileRegistration`, else the default); a forwarded read under the caller's token,
  else the default, and never the client's. `HttpCatalogClient.resolveAcl` asks
  `POST /v1/internal/acl/resolve` across the seeds.
- **Deciding the local API** (`client.acl.ClientAclResolver`, `ClientPrincipal`, `ClientAuthorizer`): a request's token
  from `X-Qraft-Token` or `Authorization: Bearer`, else the default token, else the anonymous token; resolved through
  the servers into grants in the servers' order and decided by `AclAuthorizer`; cached for the shortest time to live of
  what it holds, on an injected clock; with no server, the down policy: `extend-cache` the stale entry, else the
  default policy; `async-cache` the stale entry at once while it asks again; `allow` and `deny` everything not freshly
  cached. The recovery token writes the client and reads every node with no server. Every row of proposal 11's
  client table is enforced: with ACLs enabled, a missing service or check answers 404 before its authorization, as
  Consul's `vetServiceUpdate`; with ACLs disabled the earlier answers stay. Lists set
  `X-Qraft-Results-Filtered-By-ACLs` when they leave anything out.
- **A registration keeps its token** in `registrations.json` (`tokens`, by service ID), which is now written
  owner-only. A registration replaces its token; a removal forgets it.
- **A sync refused for its token** (`permission_denied`, `token_not_found`) is a warning, counted as synced so it holds
  no readiness back, and sent again at the next pass (`ServiceReconciler`); a refused removal is logged so too.
- **Found while testing:** `DurableFile.writePrivate` first deleted a leftover temporary file, which removed the
  directory three tests put in its place to make a write fail, so the failure never came; it now opens the file as
  `write` does, with the owner-only permission set at creation and again on an existing file.
- **Tests:** `ClientAclSettingsTest` (6), `ClientTokensTest` (3), `ClientAclResolverTest` (9),
  `HttpCatalogClientTokenTest` (5), `LocalApiAclTest` (16, one per row of the client table and the down policies with
  no server), `RegistrationTokenTest` (2) and `ServiceReconcilerAclRefusalTest` (2), each seen red on a stub first, then
  green with the 352 tests of the client packages. `ClientServerAclContractTest` covers every call from the client to a
  real server with a sufficient token, an insufficient one and none; it was written after the code, so it was not seen
  red, and its first runs corrected two of its own expectations, both the servers' documented behaviour: an unchanged
  node needs no `node:write`, and `node-services` without `node:read` is filtered, not refused.
  `ClientTokens.readServiceTokens` and the POSIX permission of `writePrivate` were not seen red: the first is covered
  through the client's start, the second can be red only on a POSIX file system, so on Jenkins.
- **Exit:** met locally on these tests; the full suites run on Jenkins once committed.

### Phase 7. Node identity is enforced

- [x] A token for node A cannot register, renew, take over, or rename node B.
- [x] A token with `service:write` and no `node:write` cannot create a node or change one, and can register its
  service on a node that is already registered (proposal 11).
- [x] A client's node identity removes a planted instance on its node, and a token with neither `node:write` nor
  `service:write` on it cannot.
- [x] A takeover and a rename succeed with the right token, under the rules of the client list's decision 8.

**Exit:** A test, red before green, for each of these guards. This phase closes the "can the server trust the claim"
half of design section 22.

**Phase 7 record, 2026-11-07** (uncommitted). `HttpApiServerNodeIdentityAclTest` (4) states each guard with the token a
client holds, a node identity for its own node: a token for node A cannot register, renew, take over or rename node
B; `service:write` alone cannot create or change a node and registers its service on one already registered; a
client's node identity removes an instance planted on its node, which a token that only reads cannot; and a takeover
and a rename succeed with the right tokens, under the client list's decision 8. The guards were built in Phase 4, so
these tests passed at their first run and were not seen red; the exit's "red before green" is not met in that sense,
and making them red would have meant breaking a guard on purpose, which is the mutation testing the owner shelved.

**A takeover under another case** (fixed 2026-11-07, uncommitted), test first. The catalog holds a name regardless of
case, so registering `Host-A` with a new ID takes over an unreachable `host-a` and removes its services, while the
server checked `node:write` only on `Host-A`. A token for `Host-A` alone could do it. The new case,
`aNameThatDiffersOnlyInCaseNeedsNodeWriteOnTheNameItTakesOver`, was seen red (200 where 403 was expected), then green:
`HttpApiServer.refusedNodeRegistration` now also needs `node:write` on every recorded node whose name differs from the
proposed one only in case, as it did on a node that carries the proposed ID. With both spellings granted, the takeover
succeeds. The class and the catalog and state store tests passed, 23.

### Phase 8. Expiry and revocation

- [x] A server refuses an expired token, by its own clock, as `token_not_found`.
- [x] Refuse an expiry less than one minute or more than 24 hours ahead.
- [x] The leader deletes expired tokens in batches with a replicated command, on the pattern of `LeaderHealthExpiry`.
- [x] A revoked token stops working on a client when its cache entry expires, and not before; a test records the bound.
- [x] A client whose own token is deleted logs why, and becomes not ready when it is refused.

**Phase 8 record, 2026-11-07** (uncommitted), test first, after Consul's token expiry and `reapExpiredACLTokens`.

- **A server refuses an expired token** from its expiry on, by its own clock, as `token_not_found`:
  `AclResolver.enabled(defaultPolicy, view, clock)`, given the HTTP server's clock (`AclResolverTest`, seen red).
- **The bounds of an expiry,** one minute to 24 hours from the leader's creation time, were already enforced by
  `AclToken` and are refused by the API with `invalid_token` (Phase 5's tests).
- **The leader deletes expired tokens** (`server.acl.AclTokenExpiry`, on the pattern of `LeaderHealthExpiry` and
  `ManagementTokenInstaller`): every 30 s while it leads, through `DeleteExpiredTokens` stamped with its time, in
  batches of at most 256; a failed proposal is left to the next interval. Wired in `QraftServerService` with ACLs
  enabled, on the expiry thread, closed with the rest of expiry (`AclTokenExpiryTest`, 3, seen red).
- **A revoked token keeps working on a client** until its cache entry expires, and not after: the bound is the shortest
  time to live of what the entry holds, 30 s by default (`ClientAclResolverTest`; it records the cache's behaviour and
  passed at once).
- **A client whose own token is refused** for its heartbeat logs why at WARN and is no longer registered, so not
  ready; a registration refused for its token is tried again, since the token can be replaced
  (`RegistrationClientTest`, seen red). `DockerAclTest` shows it in containers.
- **Exit:** met locally on these tests, with 266 tests around them green.

### Phase 9. Verification and documentation

- [x] End-to-end: an application registers through its client under a service identity, is discovered by a reader with
  `service:read`, and is invisible to one without it.
- [x] End-to-end: a second host with another node's name and its own token is refused.
- [x] End-to-end: with every server stopped, an application goes on reporting its TTL check under a cached token.
- [x] Docker: three servers with ACLs enabled, and clients holding node identity tokens. Revoke one client's token and
  observe it.
- [x] Update the design's sections 7.3, 12, 15, 16 and 22, the feature validation, and the Consul plan's phase 7
  checklist.
- [x] Add to `RAFT_STORAGE_OPERATIONS.md`: WAL files, snapshots, and backups hold token hashes and not secrets, and
  still need an encrypted medium with strict access controls, as Consul advises, for the rest of what they hold;
  which files do hold a secret, and that only the process's user may read them (proposal 3); both procedures for a
  lost management secret; and the order for revoking the management token (proposal 12).
- [x] Decide whether `server.operator.token` is removed: kept until the transport security list (proposal 14), the recommended choice, taken on 2026-11-07 under the owner's instruction to finish the list without stopping for questions; the owner can reverse it (Phase 9's record).

**Exit:** Full suites on a fresh image; changed concurrency tests pass five consecutive runs; audits as in the other lists.

**Phase 9 record, 2026-11-07** (uncommitted).

- **End-to-end** (`AclEndToEndTest`, tagged `e2e`, 3): an application registers through its client under a service
  identity and is discovered through another host's client by a reader, and not by a token without `service:read`
  nor an anonymous caller; a second host taking another node's name with its own node's token is refused, and the
  name keeps its node; and with the server stopped, the application reports its TTL check under its cached token,
  while an uncached one is refused by the default policy. All three passed at their first run.
- **Docker** (`DockerAclTest`, 1): three servers with ACLs enabled under `deny` (`docker/config/three-node-acl/`, with a
  test-only management token file, and `docker-compose-3node-acl-prebuilt.yml`), and a client container holding a
  token with a node identity and a service identity, mounted as its token file (`startDetachedClient` now takes
  mounted files). The client registers and is ready; the token is revoked; the client logs why and becomes not
  ready. Run locally against Docker Desktop in 29 s; its logs and archives hold no error line and no stack trace.
- **Documents:** design sections 15.1, 16 and 22; the feature validation's security row and item 9; the Consul plan's
  phase 7 and its item; `RAFT_STORAGE_OPERATIONS.md`, "ACLs, tokens and the files that hold secrets", with what backups
  hold, the files that hold a secret, both procedures for a lost management secret, the order for revoking the
  management token, removals with ACLs, and enabling ACLs on a running cluster.
- **`server.operator.token`: kept,** kept until the transport security list (proposal 14), the recommended choice, taken on 2026-11-07 under the owner's instruction to finish the list without stopping for questions; the owner can reverse it. It is refused with ACLs enabled, and with them
  disabled it is the only guard on removing a server (the membership list's decision 6). Recommended: keep it until
  the transport security list, then decide with TLS in place.
- **Exit:** Jenkins build 66 (`f833761`) passed the full suites, default 1,684 with every coverage gate met,
  end-to-end 16 and Docker 45; the changed concurrency tests passed five runs in a row. Its audit found one unflagged
  line outside this list, the OpenTelemetry collector dialling Tempo before Tempo listened, fixed since. The final
  local run of the three suites, on that fix, the case fix of Phase 7 and a fix to the timing of Tempo's audit, passed
  default 1,686 with every coverage gate met, end-to-end 16 and Docker 45, and its audit found no unflagged error line
  or stack trace: the exit is met (handover of 2026-11-07, section 3).

## 7. Out of scope

- TLS and mutual TLS, and so the Raft port and the client certificate on the server's HTTP listener (proposal 14's
  following list).
- Authentication methods, binding rules, login, and templated policies beyond the two identities. Consul has them; they
  connect ACLs to outside identity systems, and can be added without changing anything stored.
- Token replication and local tokens, because there is one datacentre.
- `key` and `session` rules, which the key/value and sessions lists add. `mesh`, `intention`, `query`, `event`, and
  `peering`, because Qraft has none of those.
- Replicated tenants and namespaces as objects. This list only matches a scope; the tenancy list creates and validates
  them.
- Structured audit events and a durable audit sink, which need the event journal. This list logs ACL changes and denials
  with the accessor ID.
- The reverse of proposal 19: which tokens may do a given thing.
