# Task List: Administrative Interface, Scoped Discovery (UI-2)

**Date:** 2026-11-14
**Status:** Complete and archived on 2026-11-14. Steps 1 to 5 are done, test first, and Jenkins build 74, on `f550299`, passed the three suites with them: default 2,013 with every coverage gate met, end-to-end 31 and Docker and `slow` 52, with Vitest's 73 in each Maven run and no failures, errors or skips; audit over 898 files: 1,253 error lines, all flagged, 877 stack traces, none unflagged; no JVM crash file. Builds 72 and 73 failed first, on a Vitest timeout and on an undeclared cleanup stop; both causes are fixed and recorded in section 9.
**Last updated:** 2026-11-14 (Step 5 done, the list complete and archived, its links rewritten from its new place. Earlier the same day: Steps 3 and 4 built, test first, and checked in a browser under the policy in both scopes, with their records. Earlier the same day: Steps 1 and 2 built, test first, with their records, decision 7 on the legacy `FAILING` state, and a fallback for unrouted paths found on the way. Earlier the same day: written, at the owner's word to proceed with the interface's next increment)
**Active work:** complete on 2026-11-14
**Implementation plan:** [`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](../../docs-design/QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md), increment UI-2
**Source plan:** [`QRAFT_ADMIN_UI_UX_DESIGN.md`](../../docs-design/QRAFT_ADMIN_UI_UX_DESIGN.md), sections 3.2, 4.1, 5.1 and 5.3; [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 12.1
**Predecessor:** [`task-list-embedded-admin-interface-2026-11-14.md`](task-list-embedded-admin-interface-2026-11-14.md) (UI-0 and UI-1)
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

The list was completed on 2026-11-14, with the completion summary below, and
moved to `docs/archive/`.

**Completion summary.** Increment UI-2 is built. The server answers
`GET /v1/internal/ui/services`, one summary per service in the read's scope,
and `GET /v1/health/state/{state}`, the checks across services, both
ACL-filtered with the read options of every catalog read, and both naming the
five documented health states; a path no route serves is answered 404 in the
envelope after its body is read. The interface has a tenant and namespace
selector whose choice is in the page's address and every scoped view's text,
the Services list on one summary read, and a Health Checks page with status and
text filters. Found and fixed on the way: the legacy `FAILING` state in a
public read, a connection reset on unrouted paths, Vitest role queries slow
enough to time out on Jenkins, and four test cleanups stopping servers they had
not declared. Left open: the client's local API may answer unrouted paths with
the same reset; the bundle is 1,014 kB, 317 kB gzipped.

## 1. Goal

The interface reads any tenant and namespace, not only the default, and stops
reading one service at a time:

- a scope selector in the top bar, whose choice every view reads in;
- the Services list on one summary read;
- a Health Checks page across services.

## 2. Current state

Section 4 of the implementation plan was reviewed against the code on
2026-11-14, as its note of 2026-11-13 asked before UI-2 started.

- **Scoped reads exist.** Catalog and health reads take `X-Qraft-Tenant` and
  `X-Qraft-Namespace` (`HttpApiServer.readScope`); a blank header is
  `400 invalid_scope`; no header reads the default scope.
- **The scopes are replicated.** The tenancy list built tenants and namespaces
  with an API: `GET /v1/tenants`, and `GET /v1/namespaces` for the tenant the
  header names, each entry `Name`, `Description`, `CreateIndex` and
  `ModifyIndex` (`TenancyApiRoutes`). A registration needs its tenant and
  namespace to exist, so these lists hold every scope the catalog can.
- **Reads take consistency and blocking options**: `stale`, `consistent`,
  `index` and `wait` (`HttpApiServer.readyToRead`), and answer
  `X-Qraft-Index`, `X-Qraft-Known-Leader` and `X-Qraft-Last-Contact`. UI-4 adds
  the interface's use of them.
- **Missing:** a services summary, and a health-check listing across services.
  The interface's Services list reads `/v1/catalog/services` and then
  `/v1/health/service/{name}` once per service.
- Also built since the plan's tables, outside UI-2: the leader's view of each
  server (`/v1/operator/autopilot/health`), the storage size and snapshot age
  in `/raft/status`, the event journal (`/v1/operator/events`), key/value,
  sessions, ACL tokens, policies, roles, auth methods and login. Still missing:
  redacted configuration, the transition queue's depth, and a header naming the
  answering server.

## 3. Rules

The rules of the predecessor list hold: the interface is a client of the public
HTTP API only; bootstrap data holds no secret; configuration comes only from
the versioned JSON file; red before green for every behavioural change, with no
Mockito; every new Java file carries the license header and attributed
Javadoc; the design documents and this list are updated before the next step;
every log of every run is audited (`AGENTS.md`).

## 4. Decisions

Taken 2026-11-14 as the recommended options; each is reversible, and the owner
can change any of them.

1. **Open question 2 of the plan is settled by the code.** Replicated tenants
   exist, so the scope selector lists them (`/v1/tenants`, `/v1/namespaces`),
   and UI-2 does not wait.
2. **The summary is Consul's `GET /v1/internal/ui/services`**, in the read's
   scope: one entry per service the token may read, with its tags, the count
   of instances the token may read, their count in each health state, the
   datacenters and regions they run in, and the highest `modifyIndex` among
   them as the service's last change. Field names are camelCase, as the
   catalog's reads are.
3. **The listing is Consul's `GET /v1/health/state/{state}`**, in the read's
   scope, `state` one of `any`, `unknown`, `passing`, `warning`, `critical` and
   `maintenance`: each check of an instance the token may read, with its
   instance, service, node and node status. Filters by service, node and text
   are the page's, on the answer.
4. **Both reads behave as the other catalog reads**: ACL filtering as
   `/v1/health/service` (an instance needs `node:read` and `service:read`),
   `X-Qraft-Results-Filtered-By-ACLs` when something was left out, the read options and
   headers above, and `400 invalid_query` for any other parameter.
5. **The selected scope is in the page's address** (`?tenant=` and
   `?namespace=`), kept across the interface's links, so a view in a scope can
   be reloaded and shared (UI/UX design section 4.1). Without them the views
   read the default scope, as before.
6. **The Nodes view keeps its reads.** It follows the selected scope for owned
   services and failing checks; a node has no scope, and says so.
7. **Five health states.** `ServiceHealth` also holds `FAILING`, kept so an
   older snapshot loads; no observation can carry it (`HealthObservation`
   refuses it), and the aggregate counts it as critical. The two reads name the
   five documented states only: `failing` is not a state of the listing, the
   summary has no `failing` count, and an instance held as `FAILING` is counted
   as critical. Found by the listing's refusal test, which `failing` passed.

## 5. Step 1: Services summary endpoint

1. Test first, over real HTTP: `GET /v1/internal/ui/services` answers each
   service in the default scope and in a scope the headers name, never one of
   another scope; counts instances and their health; names the datacenters and
   regions; gives the highest `modifyIndex`; and carries `X-Qraft-Index`.
2. With ACLs: a service the token may not read is left out, and an instance
   on a node it may not read is not counted, with
   `X-Qraft-Results-Filtered-By-ACLs`.
3. An unknown parameter is `400 invalid_query`; `index` and `wait` block as on
   the other reads.
4. Design section 12.1 documents it.

**Exit gate.** The new tests red first, then green; `mvn install` passes.

**Status, 2026-11-14: done**, test first, uncommitted, from the check copy.
`mvn clean install` with Steps 1 and 2: default 2,012 and Vitest 49, every gate;
audit clean (`logs/ui2-steps12-install-20261114-141747.log`).
`HttpApiServerServicesSummaryTest` (5): name order in the default scope, tags,
counts by health, datacenters and regions, the highest `modifyIndex`, the scope
the headers name, the read options and refusals, and a blocking read answered
when a registration passes its index; red first on 404. In
`HttpApiServerAclEnforcementTest`, the summary counts only what the token may
read, with the filtered header, and an unknown token is refused on it; red
first. Design section 12.1 documents it. `mvn install` closes Step 2.

## 6. Step 2: Health-check listing endpoint

1. Test first: `GET /v1/health/state/{state}` answers the checks in the scope
   in the given state, or all for `any`, ordered by service, instance and
   check; each with its instance, service, node and node status; an unknown
   state is `400 invalid_state`.
2. ACL filtering, read options and refusals as in Step 1.
3. Design section 12.1 documents it.

**Exit gate.** As Step 1.

**Status, 2026-11-14: done**, test first, uncommitted.

- `HttpApiServerHealthStateTest` (6): every check in the scope by service,
  instance and check, with its instance, node and node status; each state;
  the scope the headers name; unknown states and parameters refused; and
  decision 7, an instance held as `FAILING` counted as critical and the five
  counts only. In `HttpApiServerAclEnforcementTest`, a check is listed only when
  its instance could be read. All red first.
- A test injecting a `FAILING` observation was written and dropped:
  `HealthObservation` refuses that status, so no check can hold it.
- Found on the way: `HttpApiServerTest.theRemovedNodeRoutesAreGone` failed once,
  `HTTP/1.1 header parser received no bytes`, and passed five runs after. The
  JDK server answers a path no context serves and closes the connection with
  the body unread, which is a reset that can discard its answer. A fallback
  route for `/` now reads the body and answers 404 `not_found` in the envelope
  (`aPathNoRouteServesIsAnsweredNotFoundInTheEnvelopeEvenWithABodyLeftUnread`,
  red first on the JDK's HTML; the race itself could not be forced, a 2 MB body
  being answered on Windows). The client's local API is served by the same
  JDK server and may have the same reset; left open, outside this list.
- Every `HttpApiServer*Test`, `AdminUiDataContractTest` and
  `ClientServerContractTest` pass with both reads and the fallback.
- Design section 12.1 documents the listing, the five states and the fallback.

## 7. Step 3: Scope in the client and the selector

1. The typed client sends the selected scope's headers on every catalog and
   health read, and reads tenants and namespaces; zod schemas for both, and
   for the two new reads.
2. The top bar's scope selector lists the tenants and the selected tenant's
   namespaces; choosing one sets the address's query and re-reads every view.
   The views' text names the scope they read.
3. Vitest tests: the headers are sent; the selection is read from and written
   to the address; a malformed tenancy answer is a failure.

**Exit gate.** Vitest green; the page runs under the policy in a browser.

**Status, 2026-11-14: done**, test first, uncommitted, with Step 4.

- `api/client.ts`: a `Scope`, the default `default`/`default`; every catalog
  and health read sends `X-Qraft-Tenant` and `X-Qraft-Namespace`, the default
  when none is given; `tenants()`, `namespaces(tenant)`, `servicesSummary()`
  and `checks(state)`, with zod schemas for each. `client.test.ts` (16), the
  scoped reads red first.
- `routing.tsx`: the scope is the address's query, the default left out;
  `selectScope` re-reads the current view in another scope, and every link
  keeps the scope; a `checks` route. `routing.test.ts` (6), red first.
- `views/ScopeSelector.tsx` in the top bar: the tenants and the selected
  tenant's namespaces; a new tenant opens in its `default` namespace. When the
  scopes cannot be listed the selected one is still read, and it says why.
- Every scoped view starts with the scope it read in words: "Read in the
  default tenant and namespace." or "Read in tenant acme, namespace prod."
- `views/scope.test.tsx` (6), red first.

## 8. Step 4: Services on the summary, and the Health Checks page

1. Services reads the summary once, with health counts in words, the last
   change's index, and attention first; the per-service fan-out is gone.
2. The Health Checks page (`/ui/checks`, in the navigation): every check in
   the scope, filterable by status, service, node and text; a check's
   observation shown apart from its accepted result; an expired check said to
   be expired rather than failing; `UNKNOWN` shown as neither.
3. Vitest tests for both, and the server-mode contract test extended to the
   two new reads and the tenancy reads.

**Exit gate.** Vitest and the Java suite green; headless Chrome loads every
view with no policy violation.

**Status, 2026-11-14: done**, test first, uncommitted.

- Services reads the summary once, gives the instances of each health in
  words ("1 critical, 1 passing") and the last change ("Changed at index
  31"), attention first; the fan-out is gone (`views.test.tsx`, a case that
  counts the reads, red first). The Services failure cases now fail the
  summary read, the one the view makes.
- `views/ChecksView.tsx` at `/ui/checks`, in the navigation: every check in the
  scope, attention first, with its service (a link), instance, node and node
  status, the observation apart from the acceptance, an expired check said to
  be expired, and its output; a count by health; filters by status and by text
  over service, instance, node, check and output; empty states for no check
  in the scope and for filters that leave none. `views/checks.test.tsx` (6),
  red first.
- Service detail and Nodes and Clients read in the selected scope (decision 6).
- `Root.test.tsx` records the styles inserted on the Health Checks page and
  while the scope selector's list is open: every one carries the nonce.
- `runtime.AdminUiDataContractTest` gained the scoped reads against a real
  server in `acme`/`prod`: the tenancy reads, the summary and the listing in
  the fields and types the schemas read.
- Headless Chrome, against a server and client from the jar with a failing
  check in `acme`/`prod`: Services, Health Checks, Nodes and a service in the
  default scope, and Services, Health Checks and `pay.v2` in `acme`/`prod`,
  each with no console message; the pages showed the scope, the index and the
  counts.
- Exit, from the check copy with no `node` or `npm` on the `PATH`:
  `mvn clean install` passed the default suite, 2,013, every coverage gate,
  and Vitest, 70, in 4 min 31 s. Audit of the reports and the Maven log, 551
  files: 558 error lines and 494 stack traces, all flagged; no JVM crash file
  (`logs/ui2-steps34-install-20261114-143049.log`). The probe's logs: no error
  line. The bundle is 1,014 kB, 317 kB gzipped.

## 9. Step 5: Verification and close-out

1. Jenkins runs the three suites on the committed work; every log audited.
2. The implementation plan's view map and section 4 brought up to date; this
   list completed and archived.

**Status, 2026-11-14: done.**

- The implementation plan's sections 4 and 5 were rewritten from the review of
  section 2: each capability's state on 2026-11-14, the new reads in section
  4.1, a Done status in the view map, and open question 2 marked settled.
- Jenkins build 72, on `3b6a706`, failed in the default stage: the Java suite
  passed, 2,013, and one Vitest test timed out at 5 s, the Health Checks filter
  test, which took 3.7 s on the workstation. Its role queries over the whole
  page took nearly all of it; with a selector for the rows and label queries
  for the controls it takes 0.4 s. `Root.test.tsx`'s per-view nonce test, 4.8 s
  on Jenkins, is split into one test per view, and three view tests over 2 s
  use text and label queries. Vitest, 73, passes, the slowest 2.5 s while the
  files run together (`docs/JENKINS.md`, build 72). Committed by the owner in
  `3bf28b1`.
- Jenkins build 73, on `3bf28b1`, failed in the default stage on a test outside
  this list: `QraftServerWriteForwardingTest`'s cleanup stopped server2, which
  it had not declared, while server1 asked for its vote. Its cleanup, and those
  of `TenancyEndToEndTest`, `TlsEndToEndTest` and `WriteForwardingEndToEndTest`,
  which stopped three servers the same way, now declare each server they stop;
  the four classes pass, audit clean. Committed by the owner in `f550299`.
- Jenkins build 74, on `f550299`: default 2,013 with every coverage gate met,
  end-to-end 31 and Docker and `slow` 52, 2,096 in all, with Vitest's 73 in
  each Maven run; `HttpApiServerServicesSummaryTest`,
  `HttpApiServerHealthStateTest`, `AdminUiDataContractTest`,
  `PackagedAdminUiEndToEndTest`, `DockerAdminUiTest` and the four classes with
  the new cleanup declarations passed in their stages. Audit over 898 files:
  1,253 error lines and 877 stack traces, all flagged (`docs/JENKINS.md`).
- The list completed and moved to `docs/archive/`, the links to it updated.

## 10. Out of scope

- The consistency selector and live updates (UI-4).
- Membership, storage and events views (UI-3, UI-4).
- Mutations and sign-in (UI-5).
