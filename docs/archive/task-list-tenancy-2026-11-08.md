# Task List: Tenancy End to End

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phases 1 to 4 are done, test first, and the final local run of the three suites passed on them: default 1,839 with every coverage gate met, end-to-end 21 and Docker 50, with no failures, errors or skips; audit over the 264 files the run wrote: 926 error lines, all flagged, 612 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 as item 6 of the feature validation's order, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (Phase 4 recorded, and the list complete and archived. Earlier the same day: Phases 1 to 3 done and recorded, decision 3 narrowed to the writes that create data, and Phase 4's tests and documents written; started the same day)
**Follows:** [`task-list-sessions-and-locks-2026-11-08.md`](task-list-sessions-and-locks-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), sections 7.1, 7.2 and 15
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the tenancy rows and item 6
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

Tenants and namespaces that exist: created, listed and deleted through Raft, with names that are checked, so that a
write lands only in a tenant and namespace that have been created, deleting one removes what it held, and a client's
local API acts in the client's own tenant and namespace unless the caller names another.

## 2. Where Qraft stands (2026-11-08)

- Tenant and namespace are strings carried by every catalog identity and key/value scope, taken from the
  `X-Qraft-Tenant` and `X-Qraft-Namespace` headers, `default` when absent; any non-blank value is accepted, and nothing
  records which exist.
- Catalog, health and key/value reads are scoped by those headers; ACL `service` and `key` rules are scoped by tenant
  and namespace.
- A client registers in its configured tenant and namespace, but its local API forwards key/value and session requests
  with only the caller's headers.

## 3. Reference: Consul

From Consul Enterprise's admin partitions and namespaces (`/v1/partition`, `/v1/namespace`):

- `PUT /v1/partition` with `Name` and `Description` creates one; `GET /v1/partition/{name}`, `PUT` to update its
  description, `DELETE` to delete it, and `GET /v1/partitions` to list them. `default` cannot be deleted.
- `PUT /v1/namespace` with `Name`, `Description` and `Partition`; the same read, update, delete and list. Every
  partition has a `default` namespace, which cannot be deleted.
- Deleting a partition or namespace removes what it holds. A request in one that does not exist is refused.
- Names are DNS labels. Managing partitions and namespaces needs `operator:write`, and reading them `operator:read`.

## 4. Decisions

Taken on 2026-11-08 as the recommended options, under the owner's instruction; each can be reversed.

1. **A tenant is Consul's admin partition; a namespace lives in a tenant.** The `default` tenant and every tenant's
   `default` namespace always exist and cannot be deleted; creating a tenant creates its `default` namespace.
2. **Names are lower-case DNS labels:** letters, digits and hyphens, 1 to 63 characters, starting and ending with a
   letter or digit. A scope header that is not one is refused 400 `invalid_scope`.
3. **A write needs its tenant and namespace to exist.** A service registration, a key set and an acquisition into a
   missing one are refused 404 `tenant_not_found` or `namespace_not_found`. A health observation is not wrapped: one
   for an instance in a deleted namespace finds no instance, and is refused as not found already. The proposer checks, and
   the state machine checks again, so a write racing a deletion cannot land in a deleted namespace. Only entries that
   new servers propose carry the check, as a wrapper around the command; entries written before tenancy apply as they
   always did, so a server replays its log to the same state after its upgrade. Deletes, deregistrations and reads in
   a missing scope are answered as for an absent item.
4. **Upgrading keeps what exists.** Data already in a tenant or namespace that was never created stays readable and
   deletable; new writes there are refused until it is created. The operations guide says to create them first.
5. **Deleting a namespace removes its services, checks and keys; deleting a tenant removes its namespaces.** Sessions,
   which are global, lose the keys they held there.
6. **The client's local API acts in the client's tenant and namespace** when the caller names none, for key/value,
   sessions' keys and discovery reads, as Consul's agent defaults to its own partition.
7. **No command line in this list.** `qraft tenant` and `qraft namespace` are left for later; the HTTP API is enough
   for an operator.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. The replicated registry

- [x] `state.tenancy`: tenants and namespaces with a description and their indexes; create, update and delete
  commands; the implicit defaults; the snapshot carries them, and one written before tenancy restores only the
  defaults.
- [x] Deleting a namespace removes its services, checks and keys; deleting a tenant removes its namespaces.
- [x] Name validation, shared by the commands and the scope headers.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 1 record, 2026-11-08.** `state.tenancy` holds `Tenant`, `Namespace`, `TenancyNames` (lower-case DNS labels),
`TenancyState`, `TenancyCommand` (create, update and delete for each) and `TenancyCodec`; the Raft entry carries
`tenancy_command = 8`. The `default` tenant and its `default` namespace are always present at index 0, and every tenant
is created with its own. The state machine refuses a name that is not one (`invalid_name`), a second creation
(`tenant_exists`, `namespace_exists`), a missing tenant or namespace (`tenant_not_found`, `namespace_not_found`), and the
deletion of the `default` tenant or of any tenant's `default` namespace (`tenant_reserved`, `namespace_reserved`).
Deleting a namespace first removes its services, their checks and its keys, and deleting a tenant does so for each of
its namespaces. The snapshot carries the registry, and one written before tenancy restores the defaults alone.
`QraftStateStoreTenancyTest`, 6 tests, was seen red 6 of 6 against stubs, then green, 32 tests with the store and codec
classes. The exit's `mvn install` is the final run's default suite.

### Phase 2. Enforcement

- [x] The scoped-write wrapper: the state machine refuses its command when the scope does not exist, and applies it
  otherwise; the codec carries it; an entry without it applies as before.
- [x] Registration, key set and acquisition through HTTP are proposed wrapped, and refused at the proposer first;
  scope headers that are not names are refused.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 2 record, 2026-11-08.** `ScopedWrite` holds a tenant, a namespace and the write. The state machine refuses it
when either does not exist and applies the write otherwise, at the entry's place in the log, so a write that races a
deletion cannot land in a deleted namespace. The Raft entry carries it as `scoped_write = 9`, with the write encoded
inside, and an entry without it, as a log from before tenancy holds, applies as it always did. The server proposes
registrations, key sets and acquisitions wrapped, and refuses them first itself, 404 `tenant_not_found` or
`namespace_not_found`. A scope header that is not a name is refused 400. `ScopedWriteTest` (3) and
`HttpApiServerTenancyEnforcementTest` (3) were seen red 5 of 6, the sixth being the unwrapped write that already
applied as before. A run of the whole default set then failed 10 older tests in 6 classes that wrote into tenants they
never created. Each now creates its tenant and namespace first, through the node, and a fixture that refused
registrations by their type now looks inside the wrapper. The default set then passed, 1,831 tests.

### Phase 3. The HTTP API, ACLs and the client

- [x] `/v1/tenant`, `/v1/tenants`, `/v1/namespace` and `/v1/namespaces` on the server, with `operator:write` and
  `operator:read`.
- [x] The client's local API defaults to its own tenant and namespace, and forwards the tenancy routes.
- [x] Every test that writes into a tenant or namespace other than the defaults creates it first.

**Exit:** the new tests seen red first; the three suites locally, audited.

**Phase 3 record, 2026-11-08.** `TenancyApiRoutes` serves Consul's partition and namespace APIs under Qraft's names. A
creation is `PUT` to the collection, and a read, an update of the description or a deletion goes to `/{name}`. Lists
are `/v1/tenants`, and `/v1/namespaces` in the `X-Qraft-Tenant` header's tenant. A namespace's tenant is the body's
`Tenant`, else that header, else `default`. Refusals: 409 for one that exists, 404 for one that is missing, and 400
for a name or a reserved default. Reads take the consistency options. With ACLs, a change needs `operator:write` and a
read `operator:read`. The client's local API forwards the four routes. It already sent the client's own tenant and
namespace whenever the caller named none, so decision 6 needed no change, only a test. `HttpApiServerTenancyApiTest`
(4) and `ClientTenancyForwardingTest` were seen red 5 of 5, on the missing routes. The Docker tests that register in
`platform` create it first, through `DockerClusterApiHelper.ensureScope` on whichever server leads.

### Phase 4. Verification and documentation

- [x] An end-to-end test: two clients in two tenants see only their own services and keys; a deleted namespace's
  keys are gone for both.
- [x] A Docker test of a tenant and namespace created on the leader and enforced by every server.
- [x] Design sections 7.1, 7.2 and 15, the feature validation's tenancy rows, the Consul plan, and the operations
  guide's upgrade step.

**Exit:** the three suites locally, audited, and the list complete and archived.

**Phase 4 record, 2026-11-08.** `TenancyEndToEndTest` (e2e): three servers, and two clients in `acme/prod` and `beta`.
The beta client cannot register until its tenant is created, then does. Each sees only its own service and key, and
another's only by naming its scope. A namespace deleted through a client loses its key for both, and refuses writes
afterwards. Its first run found a defect: the client's reconciler held a registration refused for a missing tenant as a
faulty definition, and never sent it again. It now sends it again on the next pass, as for a reserved node name;
`ServiceReconcilerTest` was seen red on it first. `DockerTenancyTest`: on three containers, a namespace created on the
leader is listed by every server, a write into one that does not exist is refused, and after its deletion every server
reports it and its key gone. The first final run passed its default and end-to-end suites, and failed two Docker
tests. `DockerClientRecoveryTest` registered its fillers in a `filler` scope that was never created, so the leader's
log never grew to a snapshot; `registerFiller` now creates the scope first. `DockerDurableRestartTest` met a defect:
a follower that had not yet applied a new namespace refused a registration into it with 404, from its own state,
instead of sending the client on to the leader. Only the leader, which applies a creation before acknowledging it,
now refuses at the proposer; `HttpApiServerTenancyEnforcementTest` was seen red on a follower first. The design
(sections 7.1 and 7.2), the feature validation's tenancy row, the Consul plan and the operations guide's upgrade
step are in line. The final run (logs `logs/ten-final2/`): default 1,839 with every coverage gate met, end-to-end 21 and Docker 50, with no failures, errors or skips; audit over the 264 files the run wrote: 926 error lines, all flagged, 612 stack traces, none unflagged; no `too_many_pings`; no JVM crash file.

## 7. Out of scope

- `qraft tenant` and `qraft namespace`; quotas; per-namespace ACL defaults; sessions scoped by tenant.
