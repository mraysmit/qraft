# Task List: ACLs and Tokens

**Date:** 2026-10-05
**Status:** Proposed. Nothing in section 4 is decided: each item is a recommendation to confirm or change. No code exists.
**Proposed start:** after [`task-list-consul-style-client-2026-10-04.md`](task-list-consul-style-client-2026-10-04.md) (proposal 15).
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), sections 7.3, 12, 15, 16 and 22
**Related:** the client list's decisions 6 and 8; decision 6 of [`task-list-raft-membership-changes-2026-09-29.md`](task-list-raft-membership-changes-2026-09-29.md), the operator token; item 9 of [`QRAFT_FEATURE_VALIDATION_2026-09-27.md`](QRAFT_FEATURE_VALIDATION_2026-09-27.md); phase 7 of [`CONSUL_FEATURE_IMPLEMENTATION_PLAN.md`](CONSUL_FEATURE_IMPLEMENTATION_PLAN.md)
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

## 1. Goal

Stop trusting what a caller says about itself. Design section 15 already says
the long-term design "does not trust a caller-supplied node or tenant
identity". Today the server does exactly that.

When this list is complete:

- every request to a server is made under a token, or as the anonymous token;
- a client can register, renew, and publish for its own node only, and for the
  services its token allows;
- the node identity rules of the client list's decision 8 are enforced, not
  only detected: a takeover or a rename needs a token that may write that name;
- operator actions use the same tokens, in place of the one shared operator
  token;
- tokens are created, limited, expired, and revoked while the cluster runs,
  through the replicated log.

This list gives authentication and authorization. It does not give secrecy on
the wire: that is transport security, proposal 14.

## 2. Current state

Checked in the code on 2026-10-05.

- **Identity is whatever the headers say.** `HeaderRequestContext` reads the
  tenant, namespace, and node from `X-Qraft-Tenant`, `X-Qraft-Namespace`, and
  `X-Qraft-Node`. Nothing authenticates them. The class sits behind the
  `RequestContext` interface, which design section 15 put there so that token
  authentication could replace it without changing the catalog commands.
- **One shared secret exists.** `server.operator.token` guards
  `DELETE /v1/operator/raft/peer`. It is sent as `X-Qraft-Token` or
  `Authorization: Bearer`, compared in constant time in `MembershipService`,
  and checked again by the leader when a removal is forwarded.
- **Everything else is open:** node registration and heartbeats, service
  writes, check observations, every catalog and health read, `/raft/status`,
  the gRPC `DistributedStateService`, and the administrative interface.
- **The client has no credential.** `AgentConfiguration` has no token setting.
- **Nodes have no scope.** Services carry a tenant and a namespace; a node
  does not.
- **The Raft port is trusted,** including a claim to an existing server ID
  (membership list, review remediation of 2026-10-02).
- **No listener uses TLS.**

## 3. Reference: how Consul handles this

Read on 2026-10-05 from HashiCorp's documentation. The sources are listed at
the end of this section.

- **Token.** A token has an accessor ID, which is public and names the token
  in the API and logs, and a secret ID, which the caller presents. It also
  has a description, a creation time, an optional expiry, and links to
  policies, roles, service identities, and node identities.
- **Presenting it.** The secret is sent in the `X-Consul-Token` header.
- **Anonymous token.** A request without a token acts as a built-in anonymous
  token. It cannot be deleted, and an operator may attach policies to it.
- **Rules.** A rule names a resource type, a name or a name prefix, and an
  access level.
  - Resource types include `node`, `service`, `agent`, `key`, `session`,
    `operator`, and `acl`, each with a `_prefix` form where names apply.
  - Access levels are `read`, `write`, and `deny`, plus `list` for keys.
  - An exact name takes precedence over a prefix.
- **What the resources control.**
  - `node`: catalog registration of the node, its health checks, and which
    nodes discovery results show.
  - `service`: service registration, and which services discovery shows.
  - `agent`: the agent's own utility operations. Catalog operations come
    under `node` and `service` instead.
  - `operator`: the operator API, which includes the Raft configuration.
  - `acl`: reading and writing ACLs. `acl:write` also reveals any token's
    secret.
- **Policies and roles.** A policy is a named set of rules. A role is a named
  set of policies and identities. Tokens link to either.
- **Identities** are policy templates:
  - a node identity gives `write` on that node name and `read` on every
    service;
  - a service identity gives `write` on that service name and on its sidecar
    proxy, and `read` on every service and every node.
- **Configuration.**
  - `acl.enabled` is off by default.
  - `acl.default_policy` is `allow` by default; the documentation says that
    default will change. With `deny`, anything not allowed is refused.
  - Agents cache tokens and policies for 30 seconds, and `acl.down_policy`
    (default `extend-cache`) says what an agent does when it cannot reach a
    server to resolve one.
- **The agent's own tokens.**
  - `acl.tokens.agent` is used for the agent's internal operations. It needs
    at least `write` on the node name the agent registers as.
  - `acl.tokens.config_file_service_registration` registers the services and
    checks defined in configuration files.
  - `acl.tokens.default` is used for requests that carry no token.
  - `acl.tokens.agent_recovery` works on the agent's own endpoints when no
    server can be reached.
- **Bootstrap.**
  - `PUT /v1/acl/bootstrap` creates the first management token, with the
    built-in `global-management` policy. It succeeds once; afterwards it
    answers 403. The caller may supply the secret.
  - Alternatively, `acl.tokens.initial_management` in the server
    configuration names a secret that a server installs when it gains
    leadership.
- **The token API.** Creating, updating, and deleting need `acl:write`.
  Reading and listing need `acl:read`, which sees secrets redacted.
  `/acl/token/self` needs no privilege. An expiry set through the API must be
  between one minute and 24 hours ahead.

Recalled, and not found in the pages read. Verify each before relying on it:

- Consul keeps token secrets unhashed in its replicated state.
- A lost bootstrap is reset through a file in the leader's data directory.
- A service registered through an agent keeps the token it was registered
  with, and the agent syncs that service under it.
- A registration under a name held by another node ID is refused unless the
  holder's gossip health is failing. This is the rule the client list's
  decision 8 follows.

Sources:
- [ACL tokens](https://developer.hashicorp.com/consul/docs/secure/acl/token)
- [ACL rules](https://developer.hashicorp.com/consul/docs/secure/acl/rule)
- [ACL roles, with the identity templates](https://developer.hashicorp.com/consul/docs/secure/acl/role)
- [Bootstrap the ACL system](https://developer.hashicorp.com/consul/docs/secure/acl/bootstrap)
- [ACL configuration reference](https://developer.hashicorp.com/consul/docs/reference/agent/configuration-file/acl)
- [ACL HTTP API](https://developer.hashicorp.com/consul/api-docs/acl) and
  [token endpoints](https://developer.hashicorp.com/consul/api-docs/acl/tokens)

## 4. Proposed decisions

Each is a recommendation. Where Qraft would depart from Consul, the item says
so and why.

### The model

1. **Follow Consul's model:** tokens, policies, and node and service
   identities, held in replicated state. Roles are left out of this list;
   they are a convenience over policies and can be added without changing
   anything stored.
2. **A token** has:
   - an accessor ID, a UUID, which is public and is what logs and the API use;
   - a secret of 256 random bits from `SecureRandom`;
   - a description, linked policies, node identities, and service identities;
   - a creation time and an optional expiry, both stamped by the leader into
     the command so that every replica stores the same values.
3. **Secrets are stored only as a SHA-256 hash.** This departs from Consul.
   - The leader generates the secret, proposes the hash, and returns the
     secret once in the HTTP response when the command commits.
   - The WAL, snapshots, and offline backups therefore hold nothing a reader
     can present.
   - A fast unsalted hash is enough because the secret is random, not chosen.
   - The cost: a secret cannot be read back. A lost secret means a new token.
     No permission reveals another token's secret.
4. **Presenting a token.** `X-Qraft-Token` or `Authorization: Bearer`, as the
   operator token is sent today. Never in a query string. Never logged
   (`PROJECT_STANDARDS.md` section 6.3); a log line names the accessor ID.
5. **Anonymous and invalid.**
   - A request without a token acts as a built-in anonymous token. It has no
     policies until an operator attaches some, and it cannot be deleted.
   - An unknown or expired secret is refused with 401 `token_invalid`. A valid
     token without the permission is refused with 403 `permission_denied`.
     Consul answers 403 for both; the split tells a client whether to stop
     and report a bad credential or a missing grant.

### Rules and scope

6. **Resources in this list:** `node`, `service`, `agent`, `operator`, and
   `acl`. Access is `read`, `write`, or `deny`. A rule matches an exact name
   or a prefix; an exact name beats a prefix, a longer prefix beats a shorter
   one, and `deny` beats an allow of equal specificity. `key` and `session`
   are reserved names, added by the key/value and sessions lists. A policy
   naming an unknown resource is refused when it is stored.
7. **Scope.** A `service` rule carries a tenant and a namespace, each
   defaulting to `default`, and `*` matches any. A rule for one tenant grants
   nothing in another. The scope headers stay, as the caller's choice of
   scope; authorization checks that choice against the token. `node`,
   `agent`, `operator`, and `acl` rules have no scope, because nodes have
   none today (section 2). If the tenancy list scopes nodes, their rules gain
   the same two fields.
8. **Policies are JSON documents,** like every other Qraft configuration,
   validated when stored, with unknown fields refused:

   ```json
   {
     "name": "billing-services",
     "rules": [
       { "resource": "service", "prefix": "billing-", "access": "write",
         "tenant": "acme", "namespace": "prod" },
       { "resource": "service", "prefix": "", "access": "read",
         "tenant": "acme", "namespace": "*" },
       { "resource": "node", "prefix": "", "access": "read" }
     ]
   }
   ```
9. **Identities, as in Consul:**
   - a node identity gives `write` on that node name and `read` on every
     service in the token's scope;
   - a service identity gives `write` on that service name, and `read` on
     every service in its scope and on every node. There is no sidecar rule,
     because Qraft has no mesh.

### Enforcement

10. **One enforcement point: the server's HTTP adapter.** A successor to
    `HeaderRequestContext` resolves the token against the answering server's
    applied state, authorizes, and only then proposes or reads.
    - **No agent-side cache and no down policy.** This departs from Consul. A
      client that cannot reach a server already answers 503 (client list,
      Phase 7), so there is nothing for a cached decision to protect.
    - **The state machine does not check again when it applies a command,** as
      in Consul. A write is authorized by the leader, which holds the latest
      ACL state. A follower that is behind may honour a revoked token for a
      read until it catches up. Record this as a known limit, and revisit it
      with the consistency modes.
    - **Lists are filtered; named resources are refused.** A catalog or
      health list returns only what the token may read, as Consul filters. A
      request for one named service or node the token may not read gets 403.
11. **What each operation needs.** The paths are those the client list's
    decision 5 leaves in place.

    | Operation | Needs |
    |---|---|
    | Register, renew, or deregister a node | `node:write` on the node name |
    | Take over a node name (client decision 8) | `node:write` on that name |
    | Rename a node | `node:write` on the old name and on the new |
    | Register or deregister a service | `service:write` on the service name in its scope, and `node:write` on its node |
    | Publish a check observation | `service:write` for a service's check; `node:write` for a node's |
    | Catalog and health reads | `service:read` or `node:read`; lists are filtered |
    | `GET /v1/operator/raft/configuration`, `/raft/status` | `operator:read` |
    | `DELETE /v1/operator/raft/peer` | `operator:write`, checked again by the leader |
    | The ACL API | `acl:read` or `acl:write` |
    | The client's own endpoints (`/v1/agent/self`, `leave`, maintenance) | `agent:read` or `agent:write` on the node name |
    | `/health/live`, `/health/ready` | no token, ever: orchestrators call them |
    | The administrative interface's static files | no token; its API calls carry one (UI-5) |
    | gRPC `DistributedStateService` | `operator:write`, sent as `x-qraft-token` metadata, until the key/value list adds `key` rules |

    Not covered: the Raft port, which stays trusted until transport security
    (proposal 14), and the Prometheus port.

### Configuration and bootstrap

12. **`server.acl` in the server configuration.**
    - `enabled`, defaulting to `false` in this list, so that existing
      configurations and tests are unchanged until they are given tokens. The
      close-out decides whether the default becomes `true`.
    - **When enabled, anything not allowed is refused.** There is no
      `allow` mode. This departs from Consul, whose `allow` default exists to
      migrate running clusters; Qraft has none to migrate.
    - `initialManagementTokenFile`: a file holding the first management
      secret. A server that gains leadership installs it, as a token with the
      built-in management policy, if no token with that hash exists.
    - **There is no bootstrap endpoint.** This departs from Consul. Qraft's
      HTTP API listens on every interface, so an unauthenticated bootstrap
      call would go to whoever made it first. The file fits Qraft's rule that
      secrets are mounted files, and it is also the recovery path: when every
      management secret is lost, put a new one in the file and restart the
      leader.
    - With ACLs enabled, `server.operator.token` is refused as a conflicting
      setting, and `operator:write` replaces it. With ACLs disabled it guards
      removal as it does today.
13. **The client.**
    - `agent.acl.tokenFile` holds the agent's own token. The agent uses it to
      register and renew its node, and to register the services and checks of
      its configuration file. Consul splits these into two tokens; one is
      enough here. A node identity for the agent's node name, plus service
      identities for its configured services, is the intended grant.
    - A request to the local API is forwarded under the caller's own token,
      unchanged. Without one it is forwarded as anonymous. There is no
      default token: this departs from Consul, and means a process on the
      host gets no rights merely by being on the host.
    - A service registered through the local API keeps the token it was
      registered with, as in Consul. The agent stores it with the
      registration in `agent.dataDirectory`, in a file only its own user can
      read, and syncs that service and its checks under it.
    - The agent's own endpoints are authorized by asking a server, through a
      new `POST /v1/acl/authorize`. With no server reachable they answer 503.
      There is no recovery token.
    - A 401 or 403 from a server is a rejected outcome, not a retryable one:
      the agent reports not ready and logs it once.
14. **Tokens cross the network unencrypted until TLS exists.** Consul allows
    the same and recommends TLS. Propose a transport security list to follow
    this one directly: HTTPS on the server listener and the client's calls,
    and mutual TLS on the Raft port, which is how Consul protects server
    traffic. Until then, a server with ACLs enabled on a listener without TLS
    logs one startup warning, as the administrative interface does for being
    unauthenticated.

### Order

15. **This list follows the client list.**
    - The routes it protects become final only with the single-POM list's
      Phase 4 and the client list's Phase 1. Built earlier, enforcement would
      be written twice.
    - The local API, whose requests it must forward under the caller's token,
      does not exist before the client list.
    - The alternative is to build Phases 1 to 3 below, which touch no route,
      before the client list. That brings the replicated model forward but
      delivers no protection sooner.
    - The feature validation's recommended order puts security last, after
      key/value, sessions, and tenancy. This list takes only the token and ACL
      core out of that item. The `key` and `session` rules, replicated
      tenants, and audit events stay with their own lists.

## 5. Rules

- Red before green. Every authorization guard has a recorded mutation: with
  the guard removed, a named test fails.
- Deterministic tests, with injected clocks for expiry. No Mockito.
- HTTP behaviour is tested against real JDK HTTP servers, on both sides for
  the client.
- Every ACL command has a replica-determinism test. Fixtures prove that a WAL
  and a snapshot written before ACLs still load.
- A test searches the captured log of each ACL scenario for the secrets it
  used, and fails if it finds one.
- A denial is not an error. It is logged at a level below ERROR, so it needs
  no entry in `IntentionalError`.
- The design document changes in the same phase as the behaviour.
- Each phase ends with `mvn install`. Phases that change the runtime also run
  the end-to-end suite and the Docker suite on a fresh image.
- The user runs the builds and commits.

## 6. Tasks

### Phase 1. Replicated ACL state

- [ ] Add the token and policy values, with the validation of proposals 6
  to 9.
- [ ] Add the commands: create, update, and delete a token; create, update,
  and delete a policy; install the management token. Add their protobuf
  messages with new field numbers, and their place in the snapshot.
- [ ] Store the secret's hash only (proposal 3). No command, WAL entry, or
  snapshot carries a secret.
- [ ] Build in the management policy and the anonymous token. Neither can be
  deleted.
- [ ] Refuse the deletion of a policy that a token still links to, or define
  that the link is dropped. Decide which before coding.

**Exit:** Codec, determinism, and legacy-fixture tests. A mutation that
stores the secret instead of its hash fails a test.

### Phase 2. The authorizer

- [ ] One pure function from a token's rules, a resource, a name, a scope,
  and an access level to allow or deny.
- [ ] Table-driven tests of exact against prefix, longer against shorter
  prefix, `deny` against allow, the scope match and its wildcard, and both
  identity templates.

**Exit:** A recorded mutation for each precedence rule and for the scope
check.

### Phase 3. Configuration and the management token

- [ ] Add `server.acl.enabled` and `server.acl.initialManagementTokenFile`,
  validated before any resource opens. Refuse an unreadable or too-short
  file, and `server.operator.token` together with enabled ACLs.
- [ ] The leader installs the management token when it gains leadership and
  none with that hash exists. Installing twice changes nothing.
- [ ] Log the effective settings without the secret.

**Exit:** Lifecycle tests on real storage: a restarted cluster keeps its
tokens, and a new secret in the file is installed at the next leadership.

### Phase 4. Enforcement on the server

- [ ] Replace `HeaderRequestContext` with a context that resolves the token
  and carries the principal. Keep the scope headers as the caller's choice.
- [ ] Authorize every endpoint as proposal 11 sets out. Filter lists.
- [ ] Answer 401 `token_invalid` and 403 `permission_denied` in the standard
  error envelope, with the request ID and never the secret.
- [ ] Move the operator endpoints from the operator token to `operator`
  rules. A forwarded removal carries the caller's token, and the leader
  authorizes it again.
- [ ] Require a token on the gRPC `DistributedStateService`.
- [ ] Leave `/health/live` and `/health/ready` open, and prove it.

**Exit:** One test per row of proposal 11's table, each with its mutation.
With ACLs disabled, every existing test passes unchanged.

### Phase 5. The ACL API and commands

- [ ] `PUT /v1/acl/token`, `GET /v1/acl/tokens`, `GET`, `PUT`, and
  `DELETE /v1/acl/token/{accessorId}`, and `GET /v1/acl/token/self`.
- [ ] `PUT /v1/acl/policy`, `GET /v1/acl/policies`, and `GET`, `PUT`, and
  `DELETE /v1/acl/policy/{name}`.
- [ ] `POST /v1/acl/authorize`, for the client's own endpoints.
- [ ] `qraft acl token create|list|read|delete` and
  `qraft acl policy create|list|read|delete`, taking the caller's token from
  `--token-file` as `RaftOperatorCommand` does.
- [ ] A created token's secret appears once, in the creating response.

### Phase 6. The client

- [ ] Add `agent.acl.tokenFile`, validated at startup. Send the token on
  every call the agent makes for itself.
- [ ] Treat 401 and 403 as rejected outcomes: not ready, logged once, no
  retry storm.
- [ ] Forward local API requests under the caller's token.
- [ ] Keep each API registration's token with it in the data directory, and
  sync that service under it.
- [ ] Authorize the agent's own endpoints through the server.

**Exit:** `AgentControllerContractTest` covers every client call with a
sufficient token, an insufficient one, and none.

### Phase 7. Node identity is enforced

- [ ] A token for node A cannot register, renew, take over, or rename node B,
  and cannot register a service on node B.
- [ ] A takeover and a rename succeed with the right token, under the rules of
  the client list's decision 8.

**Exit:** Recorded mutations for each of these guards. This phase closes the
"can the server trust the claim" half of design section 22.

### Phase 8. Expiry and revocation

- [ ] A server refuses an expired token, by its own clock.
- [ ] The leader deletes expired tokens with a replicated command, on the
  pattern of `LeaderHealthExpiry`.
- [ ] A client whose token is deleted becomes not ready and says why.

### Phase 9. Verification and documentation

- [ ] End-to-end: an application registers through its agent under a service
  identity, is discovered by a reader with `service:read`, and is invisible
  to one without it.
- [ ] End-to-end: a second host with another node's name and its own token
  is refused.
- [ ] Docker: three servers with ACLs enabled, and clients holding node
  identity tokens. Revoke one client's token and observe it.
- [ ] Update the design's sections 7.3, 12, 15, 16 and 22, the feature
  validation, and the Consul plan's phase 7 checklist.
- [ ] Add to `RAFT_STORAGE_OPERATIONS.md`: backups hold token hashes, not
  secrets, and the procedure for a lost management secret.
- [ ] Decide whether `server.acl.enabled` defaults to `true`, and whether
  `server.operator.token` is removed.

**Exit:** Full suites on a fresh image; changed concurrency tests pass five
consecutive runs; audits as in the other lists.

## 7. Out of scope

- TLS and mutual TLS, and so the Raft port (proposal 14's following list).
- Roles, authentication methods and login, and any templated policy beyond
  the two identities.
- `key` and `session` rules, which the key/value and sessions lists add.
- Replicated tenants and namespaces as objects. This list only matches a
  scope; the tenancy list creates and validates them.
- Structured audit events and a durable audit sink, which need the event
  journal. This list logs ACL changes and denials with the accessor ID.
- Agent-side caching of decisions, a down policy, and a recovery token.
- More than one datacentre.
