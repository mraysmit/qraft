# Task List: Administrative Interface, Access Control and Mutations (UI-5)

**Date:** 2026-11-15
**Status:** In progress. Phase 0 is next.
**Last updated:** 2026-11-15 (written, at the owner's word to proceed after Jenkins build 78)
**Active work:** Phase 0, the security status read
**Implementation plan:** [`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md), increment UI-5
**Source plan:** [`QRAFT_ADMIN_UI_UX_DESIGN.md`](QRAFT_ADMIN_UI_UX_DESIGN.md), sections 3.5, 3.7, 5.1, 9.2, 9.4, 10.2 and 11; [`task-list-acl-and-tokens-2026-11-07.md`](../docs/archive/task-list-acl-and-tokens-2026-11-07.md), proposals 3 and 10 to 19
**Predecessor:** [`task-list-admin-ui-events-configuration-live-2026-11-15.md`](../docs/archive/task-list-admin-ui-events-configuration-live-2026-11-15.md) (UI-4)
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

The owner asked on 2026-11-15 to proceed after Jenkins build 78, which passed
UI-4; the plan names UI-5 as the next increment, at the owner's word. The
decisions in section 4 were taken as the recommended options; each is
reversible, and the owner can change any of them. Decision 7 answers the plan's
open question 5. Each phase ends with `mvn install` in the check copy at
`C:\qw` and an audit of its logs; Jenkins runs the three suites once the owner
has committed and pushed the work.

## 1. Goal

Deliver UI-5: sign-in and the token menu, the security status page and its
banners, permission-shaped navigation, Tokens, Policies and explanations, the
mutation workflow with its review step and structured outcomes, service
registration, update and deregistration, server removal with its quorum review,
and the export of events, with what the server must add for them first.

## 2. Current state

- **Authentication and authorization exist** (ACL list, authentication and
  audit list). A request presents `X-Qraft-Token` or a bearer `Authorization`
  header; without one it acts as the anonymous token. An unknown, expired or
  revoked token is answered 403 `token_not_found`, a missing grant 403
  `permission_denied`. With ACLs disabled every request is unrestricted, and
  every `/v1/acl/...` route answers 401 `acl_disabled`.
- **The ACL API** (`AclApiRoutes`): tokens (create, list by `policy`, `role`
  or `servicename`, read, update, delete, clone, `self`), policies (create,
  list, read by ID or name, update, delete), roles, `POST /v1/acl/explain`
  (`acl:read`) and `POST /v1/internal/acl/authorize`, which answers up to 64
  checks for the presenting token and needs no grant. A secret is answered once,
  by the create or clone that made it, and `<hidden>` after. A token answers
  `installedFromFile`; a refusal of the state machine keeps its code: 409 for a
  conflict, `acl_lockout` included, 400 for anything else. The built-ins are the
  `global-management` and `builtin/global-read-only` policies and the
  anonymous token.
- **Audit:** every request answered under a token is published to the event
  journal as an audit event with the accessor ID, method, route and status,
  its correlation ID the request's `X-Request-Id`, and written to the audit
  file when one is configured.
- **Catalog writes:** `PUT /v1/catalog/register` and `/deregister` act on the
  node `X-Qraft-Node` names, in the scope of `X-Qraft-Tenant` and
  `X-Qraft-Namespace`; both are idempotent and authorized as Consul's.
- **Server removal:** `DELETE /v1/operator/raft/peer?id=|name=` needs
  `operator:write` with ACLs, and the shared operator token without them;
  any server forwards it to the leader; refusals are 409 `refused` with the
  reason.
- **The interface** reads the bootstrap's `authMode` (`none` or `token`) and
  `securityProfile`, and shows the no-access-control banner on a development
  server without ACLs. It sends no token, so with ACLs enabled it acts as the
  anonymous token. It makes no write.
- **Missing for the security status page:** how many tokens hold `acl:write`
  without an expiry, whether the anonymous token's policies grant `write`,
  which tokens expire soon and what they hold, and whether the listener serves
  HTTPS and asks for client certificates. The interface cannot work these out
  without evaluating rules, which it must never do (UI/UX design section
  10.2.5).

## 3. Rules

As UI-4's: the interface is a client of the public HTTP API only; red before
green for every behavioural change, with no Mockito; license headers and
attributed Javadoc on new Java files; the design documents and this list are
updated as each phase ends; every log of every run audited (`AGENTS.md`).
Frontend tests use no role query (builds 72 and 75), and drive time with a
scheduler they control, never a wall-clock wait. A secret is never logged,
never put in an address, and never written into a fixture.

## 4. Decisions

Taken 2026-11-15 as the recommended options; each reversible.

1. **The browser holds the secret in `sessionStorage`,** for the tab: it lasts
   across reloads of that tab, is not shared with other tabs, and goes when the
   tab closes. Signing out, and any 403 `token_not_found`, remove it at once.
   It is never put in `localStorage`, a cookie or an address. (The
   alternative, memory only, signs the operator out on every reload; the
   content security policy, with no inline script and a nonce on every style,
   keeps the usual way to read `sessionStorage`, injected script, closed.)
2. **With `authMode` `token`, the interface asks for a secret before any API
   call** but the health checks the sign-in page needs, and accepts it when
   `GET /v1/acl/token/self` does. Every request after, the event watch
   included, sends it as `X-Qraft-Token`. A 403 `token_not_found` on any
   request ends the session and returns to sign-in with the reason; a 403
   `permission_denied` is the outcome of that one request. There is no
   anonymous mode in the interface.
3. **Navigation is shaped by one authorize call** after sign-in:
   `operator:read`, `operator:write`, `acl:read` and `acl:write`, through
   `POST /v1/internal/acl/authorize`. The operator views need `operator:read`,
   Access needs `acl:read`, the Tokens and Policies changes `acl:write`, server
   removal `operator:write`. A service's changes ask for `service:write` on its
   name in the selected scope when its page opens. The catalog views are always
   offered, the server filtering what they show. Without ACLs every entry is
   offered, under the banner. A control not offered is left out, not disabled
   (UI/UX design section 3.5).
4. **The security status read is `GET /v1/internal/ui/acl/status`,** needing
   `acl:read`, beside the services summary. It answers: whether ACLs are
   enabled and the default policy; the security profile; whether the listener
   serves HTTPS and whether it asks for client certificates; the management
   tokens, those linked to `global-management`, with their accessor IDs and
   which was installed from the file; the anonymous token's policies and
   whether any of them grants `write`; the tokens that hold `acl:write` without
   an expiry, decided by the authorizer enforcement uses; and the tokens that
   expire within 24 hours, with their node and service identities and whether
   they hold `acl:write`. Without ACLs it answers 401 `acl_disabled`, as the
   other ACL routes; the interface then shows the security profile and the
   no-access-control state from the bootstrap.
5. **A management token** is one linked to the built-in `global-management`
   policy; the one installed from the file is marked as such. Signed in with
   one, the interface shows the management-token banner on every page, not
   dismissible (UI/UX design section 10.2.1).
6. **Every mutation goes through one workflow** (UI/UX design section 11): the
   form names the resource and its scope; local checks never claim the server
   will accept; a review step lists the identity, the scope and the impact, and
   on a server without ACLs repeats the no-access-control warning; the outcome
   is one of: committed (with the applied index), rejected by validation,
   refused for ownership or compare-and-set, refused to protect integrity
   (`acl_lockout`, `node_name_reserved`, `refused`), permission denied, token
   not found (ends the session), retryable (the server's `retryable` flag),
   server draining, fenced or without a leader, and outcome unknown, when no
   answer came after the request was sent. An unknown outcome is never shown as
   a failure: the interface offers to read the current state, and offers to
   submit again only for an idempotent change, after that read. Every outcome
   links to its audit events, the Events page in the audit category filtered by
   the request's ID, which the Events page takes from its address.
7. **Services from the interface (open question 5):** the interface registers,
   updates, enables, disables and deregisters service instances on nodes the
   catalog already has, in the selected scope, and never creates a node: a node
   without a client is reaped, and the platform has no way to keep one. The
   review step says that a client registers its own services again at its next
   reconciliation, so a change to one of them does not last, and that a client
   removes, at its next reconciliation, a service on its node in its own tenant
   and namespace; the catalog does not record which client registered an
   instance, so the review says so for every node that has a client. (The
   other answer, only nodes no client runs, needs a way to keep such a node.)
8. **Server removal** is offered from Raft Members to a token with
   `operator:write`, never for the last voter. The review step shows the voters
   and the quorum before and after, the failure tolerance after, whether the
   server is reachable, and that a removed server still running must be stopped
   and not restarted with its old data. On a server without ACLs the review
   asks for the operator token for that one request; it is not kept. Refusals
   are reported with their reason.
9. **Tokens, Policies and explanations** as UI/UX design sections 10.2.3 to
   10.2.6. The new secret is shown once, with a copy control and its warning,
   and cleared when the dialog closes. A create whose outcome is unknown is
   settled by listing the tokens with its description and offering to delete
   what is found. Replacement is the guided clone, move, delete sequence. The
   review of a token change lists the policies and identities added and
   removed; the review of a token or policy deletion lists what depends on it,
   and how many other tokens hold `acl:write` without an expiry, from the
   status read, warning when the change concerns the token in use. Roles and
   auth methods stay out of navigation (reserved); a token's roles are listed
   by name.
10. **Export of events** downloads the events the Events page holds, as
    filtered, as a JSON file, to a token that may read them; nothing is sent
    to the server.
11. **Browser end-to-end automation stays deferred.** The plan names Playwright
    for UI-5; adding it means a browser download in every build, Jenkins
    included. The journeys are proved by Vitest over the client, by Java
    end-to-end tests of the packaged server with ACLs enabled over the routes
    the interface calls, and by the headless Chrome probe locally.

## 5. Phase 0: The server's answers

1. Test first: `GET /v1/internal/ui/acl/status` answers as decision 4, the
   writers decided by the authorizer, a token with an expiry not counted, the
   anonymous token's write found, the expiring tokens within 24 hours of the
   server's clock; `acl:read` needed; 401 without ACLs.
2. The data contract test writes the ACL answers the interface reads as
   fixtures: a token list, `self`, a created token's answer (its secret
   replaced in the fixture), a policy, the explanation, the authorize answer,
   the status, and a registration's answer; never a real secret.
3. Platform design section 12 documents the status read.

**Exit gate.** The new tests red first, then green; `mvn install` passes and
its logs are clean.

## 6. Phase 1: Sign-in, the token menu, banners, navigation and status

1. The client sends the token on every request and the watch, and reports a
   `token_not_found` so that the session ends; writes (`PUT`, `POST`,
   `DELETE`) with their outcomes; the ACL schemas; all test first.
2. Sign-in, the session in `sessionStorage`, sign-out, and the end of a session
   on `token_not_found`.
3. The token menu from `self`, with expiry warnings; the management-token
   banner; the no-access-control banner kept.
4. Permission-shaped navigation from the authorize answer.
5. The Access section's Status page from the status read.

**Exit gate.** Vitest and the Java suite green.

## 7. Phase 2: The mutation workflow, Tokens, Policies and explanations

1. The mutation workflow: review step, outcomes, unknown outcome, audit link;
   the Events page's filters in its address.
2. Tokens: list with marks and filters, detail, create with the one-time
   secret, update, delete, replacement, the management token's deletion
   order, the anonymous token.
3. Policies: list with linked-token counts, detail with linked tokens, editor
   with the structured rules and precedence, change and delete reviews.
4. Explanations from the token, policy, service and node pages.

**Exit gate.** As Phase 1.

## 8. Phase 3: Service changes, server removal and export

1. Service register, update, enable and disable, and deregister, as decision 7.
2. Server removal from Raft Members, as decision 8.
3. Export of events, as decision 10.

**Exit gate.** As Phase 1.

## 9. Phase 4: Close

1. Java end-to-end tests of the packaged server with ACLs enabled: sign-in by
   `self`, a token's create, update and delete, a policy's, a service's
   registration and deregistration, as the interface calls them.
2. Headless Chrome on every view, signed in and without ACLs, under the policy
   with no console message.
3. The plan, the UI/UX design, the platform design and the handover brought up
   to date; the list completed and archived.

**Exit gate.** `mvn install` and the end-to-end tests pass with clean logs; the
owner's commit, then Jenkins.
