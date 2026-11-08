# Task List: Authentication Methods and Audit

**Date:** 2026-11-08
**Status:** Active. Started on 2026-11-08 as what remained of item 9 of the feature validation's order, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (started)
**Follows:** [`task-list-events-2026-11-08.md`](../docs/archive/task-list-events-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 15; [`QRAFT_EVENT_ARCHITECTURE.md`](../docs/QRAFT_EVENT_ARCHITECTURE.md), section 4.4
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](QRAFT_FEATURE_VALIDATION_2026-10-21.md), the security rows and item 9
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

## 1. Goal

What item 9 still lacks: a way for a workload to exchange a credential it already holds for a Qraft token, as Consul's
auth methods do, and audit events of the requests made under tokens.

## 2. Where Qraft stands (2026-11-08)

- Tokens, policies, roles and node and service identities are replicated, enforced on servers and clients, expire and
  are revoked (the ACL list); every token is created by an operator or bootstrapped.
- The event journal takes events of any category, and no audit event is produced.

## 3. Reference: Consul

From Consul's auth method, binding rule, login and audit documentation:

- `PUT /v1/acl/auth-method` with `Name`, `Type` and `Config`; a `jwt` method's config takes `JWTValidationPubKeys`,
  `BoundIssuer`, `BoundAudiences`, `ClaimMappings`, `ExpirationLeeway`, `NotBeforeLeeway` and `ClockSkewLeeway`, and
  the method's `MaxTokenTTL` bounds what a login gets.
- `PUT /v1/acl/binding-rule` with `AuthMethod`, `Selector`, `BindType` (`service`, `node`, `role`, `policy`) and
  `BindName`, which may interpolate mapped claims such as `${value.name}`.
- `POST /v1/acl/login` with `AuthMethod` and `BearerToken` answers a new token bound by every rule whose selector
  matches; `POST /v1/acl/logout` deletes the login token it is called with.
- Consul Enterprise's audit log writes a JSON record of each authenticated request and its response to a file sink,
  with secrets left out.

## 4. Decisions

Taken on 2026-11-08 as the recommended options, under the owner's instruction; each can be reversed.

1. **One method type, `jwt`, with static public keys** (RSA and EC, PEM). JWKS, OIDC discovery, Kubernetes and AWS
   methods are left for later; a JWT signed with `none` or HMAC is refused.
2. **Selectors are a small subset of Consul's:** `value.<claim> == "<text>"` and `"<text>" in list.<claim>`, joined by
   `and`; any other selector is refused when the rule is written, never misread at login.
3. **A login token always expires,** at the method's `MaxTokenTTL`, 1 hour by default and at most 24 hours, and its
   description names the method; logout deletes only a token a login created.
4. **Audit events go to the event journal** as the `AUDIT` category, one per request a server answers under a token,
   with the accessor ID, the method, the route, the status and the request ID, and never a secret or a body.
5. **A file sink is optional,** `server.audit.path`, writing one JSON line per audit event, best-effort as Consul's
   default; a sink that cannot write reports it once and the requests go on.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. Auth methods and binding rules

- [ ] Replicated auth methods and binding rules, with their create, update and delete commands, the snapshot, and the
  ACL API under `acl:write` and `acl:read`.

**Exit:** the new tests seen red first; `mvn install`.

### Phase 2. Login and logout

- [ ] JWT validation: signature, issuer, audience, expiry and not-before with their leeways; claim mapping; selectors.
- [ ] `POST /v1/acl/login` and `POST /v1/acl/logout`.

**Exit:** the new tests seen red first; `mvn install`.

### Phase 3. Audit

- [ ] Audit events in the journal for requests under a token, and the optional file sink.

**Exit:** the new tests seen red first; the three suites locally, audited.

### Phase 4. Verification and documentation

- [ ] An end-to-end test: a workload logs in with a JWT, uses its token, logs out, and its requests are audited.
- [ ] Design section 15, the feature validation's security rows, the Consul plan, and the operations guide.

**Exit:** the three suites locally, audited, and the list complete and archived.

## 7. Out of scope

- JWKS, OIDC, Kubernetes and AWS methods; namespace rules on binding; audit of requests without a token; audit sinks
  other than a file.
