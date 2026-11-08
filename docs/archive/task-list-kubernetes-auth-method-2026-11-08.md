# Task List: The Kubernetes Auth Method

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phases 1 to 4 are done, test first, and the final local run of the three suites passed on them: default 1,918 with every coverage gate met, end-to-end 28 and Docker 51, with no failures, errors or skips; audit over the 269 files the run wrote: 911 error lines, all flagged, 624 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 for what the feature validation's security row still missed, other auth method types, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (the final run recorded, and the list complete and archived. Earlier the same day: phases 1 to 4 built)
**Follows:** [`task-list-certificate-reloading-2026-11-08.md`](task-list-certificate-reloading-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 15.3
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the security row
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

A workload in Kubernetes logs in with its service account token, as Consul's `kubernetes` auth method lets it: the
server asks the cluster's API whether the token is valid, and binding rules decide what the login gets from the
service account's namespace and name.

## 2. Where Qraft stands (2026-11-08)

- Auth methods are replicated (`AuthMethod`, `AuthCommand`, Raft field 10). The only type is `jwt`, checked against
  PEM public keys; its configuration is the method's only configuration (`JwtConfigProto`, field 5 of
  `AuthMethodProto`).
- A login is validated by the server that receives it, and only the token it mints is replicated.

## 3. Reference

Consul's `kubernetes` auth method: its configuration is `Host`, `CACert` and `ServiceAccountJWT`. A login's bearer
token is sent to `POST {Host}/apis/authentication.k8s.io/v1/tokenreviews` under the reviewer's `ServiceAccountJWT`,
and an authenticated review's `system:serviceaccount:<namespace>:<name>` user and UID become the selectors
`serviceaccount.namespace`, `serviceaccount.name` and `serviceaccount.uid`.

## 4. Decisions

Taken on 2026-11-08 as the recommended options, under the owner's instruction; each can be reversed.

1. **One new type, `kubernetes`.** `oidc` needs a browser and `aws-iam` an AWS account, so neither is built here.
2. **Its configuration is `host`, `caCert` and `serviceAccountJwt`,** in the ACL API's lower camel case, replicated in
   a new field of `AuthMethodProto` beside the `jwt` configuration, so a method written before it still reads.
   `serviceAccountJwt` is a secret: the API never answers it, as it never answers a token's secret.
3. **The review is made by the server that receives the login,** over HTTPS with `caCert` as the only trusted CA, and
   only the minted token is replicated, as for `jwt`. A review that says the token is not authenticated is refused 403
   `permission_denied`; a cluster API that cannot be reached is refused 503 `auth_method_unavailable`, which a
   workload may retry.
4. **The selectors are Consul's:** `serviceaccount.namespace`, `serviceaccount.name` and `serviceaccount.uid`.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. The replicated method

- [x] `KubernetesConfig` beside `JwtConfig`, the method's configuration as one of them, its codec field, the snapshot,
  and the state accepting the type.

**Exit:** the new tests seen red first; `mvn install`.

**Record (2026-11-08):** `AuthMethodConfig` is sealed over `JwtConfig` and `KubernetesConfig`, and Jackson deduces
which from the fields, so a `jwt` method's snapshot form is unchanged. `KubernetesConfigProto` is field 6 of
`AuthMethodProto`, and `KubernetesConfig.toString()` never writes the reviewer token. `AuthState` accepts a method
whose type and configuration agree and are complete. `QraftStateStoreKubernetesAuthTest` was seen red against the
types alone, the method refused, then green with `QraftStateStoreAuthTest`, `JwtValidatorTest` and the API and login
tests, 21. The `mvn install` exit was taken with Phase 3's run.

### Phase 2. The API

- [x] `PUT` and `GET /v1/acl/auth-method` for a `kubernetes` method, with its checks, and `serviceAccountJwt` never
  answered.

**Exit:** the new tests seen red first; `mvn install`.

**Record (2026-11-08):** `AuthApiRoutes` reads a `kubernetes` method's `host` (an `https` address), `caCert` (at least
one PEM certificate) and `serviceAccountJwt`, all required, and answers `host` and `caCert` alone. The new test in
`HttpApiServerAuthMethodApiTest` was seen red with 400 (`Only the jwt auth method type is supported`), then green.

### Phase 3. Login

- [x] The token review against a stub Kubernetes API over HTTPS: an authenticated service account logs in with the
  selectors of decision 4, an unauthenticated token is refused 403, and an unreachable API 503.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Record (2026-11-08):** `KubernetesTokenReviewer` posts the review under the reviewer token with a context trusting
`caCert` alone, and reads an authenticated `system:serviceaccount:<namespace>:<name>`. `Selector` and the bind name
interpolation read `serviceaccount.namespace`, `serviceaccount.name` and `serviceaccount.uid` as well as `value.<name>`.
The login answers 503 `auth_method_unavailable`, retryable, when the review cannot be made, logging the reason at WARN
without a trace. `HttpApiServerKubernetesLoginTest` was seen red 2 of 2 at the selector, then green: a login binds
the service `web`, a bad token is refused 403, and both an API whose certificate another CA signed (which receives no
review) and a stopped one answer 503. The three suites ran with Phase 4.

### Phase 4. Verification and documentation

- [x] An end-to-end login through a three-server cluster against the stub API, and the design, the feature validation
  and the operations guide.

**Record (2026-11-08):** `KubernetesAuthEndToEndTest` starts three servers with ACLs under `deny` and a stub
Kubernetes API over HTTPS. A pod's token is exchanged on the leader for a token bound to a policy by the namespace's
rule, which writes under `billing/`, is refused under `payroll/`, and is enforced by every server; no answer or log
holds the reviewer token. Design section 15.3, the feature validation's security row (now done, with `oidc` and
`aws-iam` recorded as not built) and the operations guide's login section describe the method.

**Exit:** the three suites locally, audited, and the list complete and archived.

**Final run, 2026-11-08.** The three suites from `C:\qx7` (logs `logs/five-final/`), which also took the earlier phases' `mvn install` exits and suites, and closed five lists together: default 1,918 with every coverage gate met, end-to-end 28 and Docker 51, with no failures, errors or skips; audit over the 269 files the run wrote: 911 error lines, all flagged, 624 stack traces, none unflagged; no `too_many_pings`; no JVM crash file.

## 7. Out of scope

- The `oidc` and `aws-iam` types, namespace mapping, and Kubernetes' own audiences on the review.
