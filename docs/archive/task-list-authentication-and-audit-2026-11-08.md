# Task List: Authentication Methods and Audit

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phases 1 to 4 are done, test first, and the final local run of the three suites passed on them: default 1,881 with every coverage gate met, end-to-end 23 and Docker 51, with no failures, errors or skips; audit over the 407 files written since the events list closed: 1,288 error lines, all flagged, 753 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 as what remained of item 9 of the feature validation's order, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (Phase 4 recorded, and the list complete and archived. Earlier the same day: Phases 1 to 3 done and recorded, decision 6 added, and Phase 4's test and documents written; started the same day)
**Follows:** [`task-list-events-2026-11-08.md`](task-list-events-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 15; [`QRAFT_EVENT_ARCHITECTURE.md`](../QRAFT_EVENT_ARCHITECTURE.md), section 4.4
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the security rows and item 9
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

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
6. **Field names follow Qraft's ACL API,** in lower camel case (`name`, `authMethod`, `bearerToken`, `accessorId`), as
   its token, policy and role routes already are, rather than Consul's `Name` and `AuthMethod`. Taken during Phase 4,
   when the end-to-end test met the difference.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. Auth methods and binding rules

- [x] Replicated auth methods and binding rules, with their create, update and delete commands, the snapshot, and the
  ACL API under `acl:write` and `acl:read`.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 1 record, 2026-11-08.** `state.acl` holds `AuthMethod`, `JwtConfig`, `BindingRule`, `BindType`, `Selector` (the
two clause forms joined by `and`, refused otherwise), `AuthState` and `AuthCommand`; the Raft entry carries
`auth_command = 10`, through `state.AuthCodec`, which writes a login's token with the ACL commands' own form. The state
machine refuses a type other than `jwt`, a method without keys, a token life outside 1 minute to 24 hours, a rule
without its method or with a selector it cannot read, and a second creation. Deleting a method deletes its rules and
the tokens its logins created. The snapshot carries methods, rules and logins, and one written before them restores
none. `AuthApiRoutes` serves `/v1/acl/auth-method(s)` and `/v1/acl/binding-rule(s)`, parsing a method's PEM keys
(`server.acl.JwtKeys`) before proposing it. `QraftStateStoreAuthTest` (4) was seen red 4 of 4, then green with the
store's other tests, 37. Its first runs also corrected the test's accessor and link IDs to UUIDs, as a token requires.
`HttpApiServerAuthMethodApiTest` (3) was seen red 3 of 3, then green with the ACL API tests, 25.

### Phase 2. Login and logout

- [x] JWT validation: signature, issuer, audience, expiry and not-before with their leeways; claim mapping; selectors.
- [x] `POST /v1/acl/login` and `POST /v1/acl/logout`.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 2 record, 2026-11-08.** `server.acl.JwtValidator` accepts RS256 to RS512 and ES256 to ES512, the EC forms
through the JDK's P1363 signatures. It refuses `none`, HMAC, other keys, an expiry or not-before beyond its leeways and
the clock skew, another issuer, and another audience. It then maps the claims. Login needs no token of its own. It
binds every rule whose selector matches, interpolating `${value.<name>}`, and leaves out a role or policy that does
not exist. A login that binds nothing is refused 403 `permission_denied`, as is an invalid JWT. The token expires at
the method's `maxTokenTtl`, is described as created via login, and is stored through the replicated login command.
Logout deletes only a token a login created. `JwtValidatorTest` (2) and `HttpApiServerLoginTest` were seen red 3 of
3, then green with the auth and ACL tests, 28.

### Phase 3. Audit

- [x] Audit events in the journal for requests under a token, and the optional file sink.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Phase 3 record, 2026-11-08.** `AuditEvents.Request` (accessor ID, method, route, status) goes into the journal as an
`AUDIT` event, with the request ID as its correlation ID. It is recorded for every request a server answers under a
token. The accessor is resolved before the request is handled, so a logout is audited under the token it ended. The
secret and the body are never recorded. `AuditFileSink` follows the journal's audit events into
`server.audit.path`, one JSON line each. A write that fails is counted and reported once at WARN. A subscription that
falls behind is resumed, and a gap is written as a line. `QraftServerService` gives its HTTP server the journal and
starts the sink when the path is set. `AppConfigAuditTest`, `HttpApiServerAuditTest` and `AuditFileSinkTest` (2) were
seen red 4 of 4, then green with the config and HTTP tests, 155.

### Phase 4. Verification and documentation

- [x] An end-to-end test: a workload logs in with a JWT, uses its token, logs out, and its requests are audited.
- [x] Design section 15, the feature validation's security rows, the Consul plan, and the operations guide.

**Exit:** the three suites locally, audited, and the list complete and archived.

**Phase 4 record, 2026-11-08.** `AuthEndToEndTest` (e2e) runs three servers with ACLs enabled and an audit file each.
The operator creates a policy, a `jwt` method and a rule on the leader. A workload logs in with its JWT, writes a key,
and every server enforces its token from the replicated state. The leader's audit file holds the write under the
token's accessor ID and no secret, and after logout every server refuses the token. Its first run found the auth
routes using Consul's field names where Qraft's ACL API uses lower camel case (decision 6); routes and tests now use
the ACL API's. The final run's default and end-to-end suites passed. Its Docker suite was ended from outside at
16:57:52, while `DockerReturningVoterTest` waited: Maven's shutdown hook told the fork to exit, and the runner's window
was gone. No test had failed. The Docker suite alone was run again (logs `logs/auth-final/` and
`logs/auth-final-docker/`): default 1,881 with every coverage gate met, end-to-end 23 and Docker 51, with no failures, errors or skips; audit over the 407 files written since the events list closed: 1,288 error lines, all flagged, 753 stack traces, none unflagged; no `too_many_pings`; no JVM crash file.

## 7. Out of scope

- JWKS, OIDC, Kubernetes and AWS methods; namespace rules on binding; audit of requests without a token; audit sinks
  other than a file.
