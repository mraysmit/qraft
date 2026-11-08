# Task List: Catalog Indexes

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phases 1 and 2 are done, test first, and the final local run of the three suites passed on them: default 1,883 with every coverage gate met, end-to-end 23 and Docker 51, with no failures, errors or skips; audit over the 275 files the run wrote: 949 error lines, all flagged, 636 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 for two rows of the feature validation that remained open, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (Phase 2 recorded, and the list complete and archived. Earlier the same day: Phase 1 done and recorded, and Phase 2's check and documents written; started the same day)
**Follows:** [`task-list-authentication-and-audit-2026-11-08.md`](task-list-authentication-and-audit-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 7.5
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the catalog's index row and the consistency index row
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

Design section 7.5's registration and modification indexes on every service instance, as Consul's `CreateIndex` and
`ModifyIndex`, and the applied index on the one read that still lacks it.

## 2. Where Qraft stands (2026-11-08)

- `ServiceInstance` holds no index; catalog and health reads answer `X-Qraft-Index`, the applied index, and the client
  passes it on with its forwarded reads.
- `/raft/status` answers no index.

## 3. Reference: Consul

A catalog service carries `CreateIndex`, the index that first registered it, and `ModifyIndex`, the index that last
changed it. Registering a service again without a change leaves `ModifyIndex` as it was; a health change is the check's
own index, not the service's.

## 4. Decisions

Taken on 2026-11-08 as the recommended options, under the owner's instruction; each can be reversed.

1. **The indexes live beside the instances,** in the catalog, so `ServiceInstance` and the commands that carry it keep
   their form; the snapshot carries them, and one written before them restores 0 for both.
2. **`createIndex` and `modifyIndex` are added to each instance a read answers,** in the instance's own lower-camel
   form, in catalog and health reads alike.
3. **A registration that changes nothing but the health it carries leaves `modifyIndex` alone,** as Consul's does.
4. **`/raft/status` answers `X-Qraft-Index`,** the answering server's applied index, as every other read does.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. Indexes

- [x] The catalog keeps each instance's indexes; re-registration, deregistration and the snapshot treat them as
  decision 1 and 3 say; reads answer them; `/raft/status` answers its index.

**Exit:** the new tests seen red first; `mvn install`.

**Phase 1 record, 2026-11-08.** `ServiceCatalog` keeps each instance's `ServiceCatalogView.Indexes` beside it. A new
instance takes the registering entry's index as both. A registration that changes more than the health it carries
moves `modifyIndex`, and deregistration forgets them. The store passes the entry's index to the catalog and snapshots
the indexes as `serviceIndexes`; one written before them restores 0. `HttpApiServer.instanceView` adds `createIndex`
and `modifyIndex` to each instance in catalog, node-services and health reads; a health entry keeps its instance for
its own logic and answers the view. `/raft/status` answers `X-Qraft-Index`. `ServiceCatalogIndexTest` and
`HttpApiServerCatalogIndexTest` were seen red 2 of 2, then green with the catalog, store and client contract tests,
115.

### Phase 2. Verification and documentation

- [x] An end-to-end check through a client's local API, and the design and the feature validation. The check is
  `ClientServerContractTest`'s discovery read against a real server, now asserting the indexes, as the client passes
  the server's answer on as it came; the Consul plan has no item for indexes.

**Exit:** the three suites locally, audited, and the list complete and archived.

**Phase 2 record, 2026-11-08.** The final run of the three suites from `C:\qx7` (logs `logs/idx-final/`): default 1,883 with every coverage gate met, end-to-end 23 and Docker 51, with no failures, errors or skips; audit over the 275 files the run wrote: 949 error lines, all flagged, 636 stack traces, none unflagged; no `too_many_pings`; no JVM crash file.
Two older qx7 logs that the run copied into `run-logs` with their own times, from 11:29 and 14:38, held one unflagged
error each: a heartbeat refused by a stopped state loop and logged with its trace, and an undeclared partition in
`RaftNodeServerHealthTest`. Both were remediated after those runs and before this one: the sequencer refuses work
after its loop stops as draining, logged at debug (598f3a2), and the test declares `RAFT_PEER_UNREACHABLE` (14:39).

## 7. Out of scope

- Indexes on nodes and checks.
