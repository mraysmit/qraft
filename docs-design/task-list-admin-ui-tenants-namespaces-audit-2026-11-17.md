# Task List: Administrative Interface, Tenants, Namespaces and Audit (UI-6, part 3)

**Date:** 2026-11-17
**Status:** In progress. Phase 0 next.
**Last updated:** 2026-11-17 (written, from a survey of the code with UI-6's second list in the working tree)
**Active work:** Phase 0, the server's answers
**Implementation plan:** [`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md), increment UI-6
**Source plan:** [`QRAFT_ADMIN_UI_UX_DESIGN.md`](QRAFT_ADMIN_UI_UX_DESIGN.md), sections 4.3, 10.1, 10.3, 11, 12 and 13; [`task-list-tenancy-2026-11-08.md`](../docs/archive/task-list-tenancy-2026-11-08.md); [`task-list-authentication-and-audit-2026-11-08.md`](../docs/archive/task-list-authentication-and-audit-2026-11-08.md)
**Predecessor:** [`task-list-admin-ui-sessions-locks-watches-2026-11-17.md`](../docs/archive/task-list-admin-ui-sessions-locks-watches-2026-11-17.md) (UI-6, part 2)
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

The owner said on 2026-11-17 to proceed after UI-6's second list. This is UI-6's
third and last list (the Key/Value list's decision 1). The decisions in
section 4 were taken as the recommended options. Each is reversible, and the
owner can change any of them.

Each phase ends with `mvn clean install` in the check copy at `C:\qx8` and an
audit of its logs. The list ends with the three suites. Jenkins is not
available on this workstation (the owner, 2026-11-17), so the three suites run
locally against Docker Desktop.

## 1. Goal

Deliver the Govern pages of UI/UX design section 10, with what the server must
add for them first:

- Tenants and Namespaces (section 10.1): each with its identity, what it holds
  and its recent events;
- creation, a change of description, and a deletion reviewed with what it
  removes;
- the Audit page (section 10.3): the records the server keeps, filtered, with
  their retention and durability stated;
- the navigation in the groups of section 4.3, as the second list's decision 11
  left for this one.

## 2. Current state

Surveyed on 2026-11-17.

- **Tenancy** (tenancy list, `TenancyApiRoutes`):
  - `PUT /v1/tenant` creates a tenant with its `default` namespace;
    `GET`, `PUT` (its description) and `DELETE /v1/tenant/{name}`; and
    `GET /v1/tenants`.
  - `PUT /v1/namespace`, and `GET`, `PUT` and `DELETE /v1/namespace/{name}` in
    the `X-Qraft-Tenant` header's tenant; `GET /v1/namespaces`.
  - Reads need `operator:read`, and changes `operator:write`.
  - A tenant or namespace has a name, a description and its indexes, and
    nothing else: no enabled state, metadata, policy assignments or quotas.
  - Names are lowercase letters, digits and dashes.
  - The `default` tenant and every `default` namespace are refused for deletion
    (`tenant_reserved`, `namespace_reserved`).
  - Deleting a namespace removes its services, their checks and its keys
    (`QraftStateStore.removeScope`), and deleting a tenant does so for each of
    its namespaces. A session that held a key there keeps on, holding nothing
    there.
  - No tenancy change produces an event.
  - No read counts what a scope holds.
- **Audit** (authentication and audit list):
  - Every request under a token publishes `audit.request` to the answering
    server's bounded journal. It carries the accessor ID, method, route and
    status, with the request ID as its correlation ID.
  - Nothing records the request's tenant or namespace, or a committed index.
  - With `server.audit.path`, `AuditFileSink` writes each record to that file
    as a JSON line. It is best effort, as Consul's default sink is: a line
    that cannot be written is counted, and a gap is marked
    `audit.events_lost`. Each server writes only what it answered, and the
    file is never rotated.
  - No read answers the file.
- **The interface:** the Events page shows the journal's audit category, and a
  mutation's outcome links its request's audit events (UI-5). There is no
  Tenants, Namespaces or Audit page, and navigation is one flat bar of 14
  links.

## 3. Rules

These are the second list's rules:

- The interface is a client of the public HTTP API only, apart from the
  `/v1/internal/ui/` reads it is given.
- Red before green for every behavioural change, with no Mockito.
- New Java files carry the license header and attributed Javadoc.
- The design documents and this list are updated as each phase ends.
- Every log of every run is audited (`AGENTS.md`).
- Frontend tests use no role query. They drive time with a scheduler they
  control, and each starts with no style element.
- A key's value is never logged, never put in an address, and never written
  into an event. A secret is never shown again after its creation.

## 4. Decisions

Taken 2026-11-17 as the recommended options; each reversible.

1. **Tenancy changes produce domain events:** `tenant.created`,
   `tenant.updated`, `tenant.deleted`, `namespace.created`,
   `namespace.updated` and `namespace.deleted`. Each is in the scope it names,
   a tenant's in its tenant and its `default` namespace, so a scope's recent
   events include its own history. They are the same on every replica, as the
   catalog's are.
2. **One read counts what each scope holds:** `GET /v1/internal/ui/scopes`
   answers every tenant with its namespaces. Each namespace carries:
   - its services, its instances and its keys;
   - the keys held in it, and the sessions that hold them.

   It needs `operator:read`, as the tenancy reads do. The counts include what
   the token could not read through the other reads; `operator:read` is the
   broad operator permission already. It takes the read options, and answers
   the applied index.
3. **A deletion is reviewed with what it removes:**
   - the services and their instances;
   - the number of keys;
   - the held keys, with their sessions, which keep on holding nothing there;
   - for a tenant, the sum over its namespaces.

   Routes and peer exports, which section 10.1 names, do not exist. The review
   names the default scopes as not deletable, and the interface does not offer
   their deletion. A deletion is idempotent only in effect: a second one
   answers 404, which the review says is a deletion already made.
4. **An audit record carries the request's tenant and namespace:** from
   `X-Qraft-Tenant` and `X-Qraft-Namespace`, the default where none is given. A
   record written before this change has neither, and is shown so.
5. **The audit file is the durable record, read on its own server:**
   `GET /v1/internal/ui/audit` reads the answering server's audit file from
   its end, newest first, and answers up to `limit` records that match the
   filters:
   - `limit` is 100 by default and 1,000 at most;
   - `accessor`, `requestId`, `method`, `tenant` and `namespace` match exactly;
   - `route` matches as a prefix;
   - `result` is one of `allowed`, `denied` (403) or `failed` (5xx);
   - `before` gives the time a page ends at, for the next page.

   The read stops after 64 MiB of the file, and says how much it read and
   whether it reached the start. It answers the file's path, its size, and the
   durability below. It needs `operator:read`, as the journal's audit events
   do. Without `server.audit.path` it answers that no file is configured.
6. **The Audit page states what the record is:**
   - it is best effort, as Consul's default sink is;
   - it is kept on each server for the requests that server answered;
   - it is kept until an operator removes or rotates the file;
   - a lost stretch is marked by `audit.events_lost`.

   With no file configured, the page says so and how to configure one, and
   lists the answering server's bounded journal instead, labelled as such. It
   never presents an empty list as a complete record.
7. **Committed indexes are not in an audit record.** A request's audit record is
   written as it is answered, and the record does not know the Raft index its
   change took. The filter section 10.3 names is left out, and the page says
   so.
8. **Navigation is grouped** as section 4.3 has it, in one bar:
   - Discover;
   - Coordinate;
   - Operate;
   - Govern: Tenants, Namespaces and Audit;
   - Access.

   Each group's name is a label before its links. A group with no link the
   token may use is not shown. The links keep their order, so a link list read
   from the bar is the same as before with Govern's links added. Govern needs
   `operator:read`, as the tenancy and audit reads do.
9. **Addresses:** `/ui/tenants`, `/ui/tenants/<tenant>`, `/ui/namespaces`,
   `/ui/namespaces/<tenant>/<namespace>` and `/ui/audit` with its filters in
   the query. Names cannot hold a `/` or a dot.
10. **Changes follow the authorize call** for `operator:write`, as server
    removal does (UI-5), and the mutation workflow. A creation's unknown
    outcome is settled by reading the tenancy, never by sending it again; a
    creation of a scope that already exists answers 409, which is shown as the
    conflict it is.

## 5. Phase 0: The server's answers

1. Test first: the tenancy domain events (decision 1), in their scope, the same
   on every replica, none for a refused change.
2. Test first: `GET /v1/internal/ui/scopes` (decision 2):
   - counts of services, instances, keys and held keys, with the sessions
     holding them;
   - `operator:read` needed;
   - the read options and its index;
   - an unknown query parameter refused.
3. Test first: an audit record's tenant and namespace (decision 4), in the
   journal and in the file.
4. Test first: `GET /v1/internal/ui/audit` (decision 5):
   - the newest records first;
   - each filter, and a page that ends before a time;
   - the scan's limit, and what it says about it;
   - no file configured;
   - a line that is not JSON, and a lost stretch, each shown as what it is;
   - `operator:read` needed.
5. The data contract test checks these answers in the fields and types the
   interface will read.
6. Documents:
   - the event architecture, for the events;
   - the operations guide, for the reads and the audit file;
   - the platform design's tenancy and audit sections;
   - the implementation plan's section 4.

**Exit gate.** The new tests red first, then green; `mvn install` passes and
its logs are clean.

## 6. Phase 1: Tenants and Namespaces, read, and grouped navigation

1. The client and schemas: the scopes read, and the tenancy reads it does not
   have.
2. Routing (decision 9), and the grouped navigation (decision 8).
3. The Tenants page: each tenant with its description, its namespaces and its
   totals.
4. A tenant's page: its namespaces with their totals, and its recent events with
   `operator:read`.
5. The Namespaces page: every namespace of every tenant with its totals.
6. A namespace's page: its totals, its services linked in its scope, its held
   keys, and its recent events.

All of them follow the server live, and show the empty and failure states.

**Exit gate.** Each view red first; Vitest and `mvn install` pass, the logs
clean.

## 7. Phase 2: Tenancy changes

1. Create a tenant, and a namespace in a tenant. The name is checked as the
   server checks it, before the review; a conflict and an unknown outcome are
   shown as decision 10 has them.
2. Change a description.
3. Delete a namespace or a tenant after the review of decision 3. The default
   scopes are not offered.
4. Each change needs `operator:write` through the authorize call.

**Exit gate.** Each change red first; Vitest and `mvn install` pass, the logs
clean.

## 8. Phase 3: Audit

1. The client and schema of the audit read.
2. The Audit page (decisions 5 to 7):
   - the filters, in the address;
   - the records, newest first, with their principal, scope, request,
     operation, status and decision;
   - a link from a request ID to its events;
   - the next page;
   - the durability and retention, and how much of the file was read;
   - no file configured, with the journal's bounded record instead;
   - a lost stretch shown as one.
3. A mutation's outcome links the Audit page at its request ID, beside its
   events.

**Exit gate.** Each view red first; Vitest and `mvn install` pass, the logs
clean.

## 9. Phase 4: Close

1. The packaged server's end-to-end test, with ACLs on and an audit file:
   - a tenant and a namespace are created, counted, described and deleted;
   - the deletion removes what the review counted;
   - the audit read finds those requests by accessor, scope and result;
   - the output has no error and no secret.
2. Headless Chrome against the packaged server, without and with ACLs, on every
   new page.
3. The three suites locally against Docker Desktop, every log audited.
4. Records of the list:
   - the implementation plan;
   - UI/UX design sections 4.3, 10.1 and 10.3;
   - the platform design;
   - the operations guide;
   - `docs/JENKINS.md`'s local runs;
   - the handover.

   Then the list is archived.

**Exit gate.** The three suites pass with clean logs; the owner's commit.

## 10. Out of scope

- Enabled state, metadata, policy assignments and quotas for a scope, which the
  platform does not have.
- Routes and peer exports (Connect and Multi-cluster are reserved).
- An audit store shared by the servers, and the file's rotation.
- Roles and Auth Methods pages.
