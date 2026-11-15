# Task List: Administrative Interface, Key/Value (UI-6, part 1)

**Date:** 2026-11-15
**Status:** In progress. Phase 0 is next.
**Last updated:** 2026-11-15 (started)
**Active work:** Phase 0
**Implementation plan:** [`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md), increment UI-6
**Source plan:** [`QRAFT_ADMIN_UI_UX_DESIGN.md`](QRAFT_ADMIN_UI_UX_DESIGN.md), sections 4.3, 7.1, 11 and 12; [`task-list-key-value-2026-11-08.md`](../docs/archive/task-list-key-value-2026-11-08.md)
**Predecessor:** [`task-list-admin-ui-access-mutations-2026-11-15.md`](../docs/archive/task-list-admin-ui-access-mutations-2026-11-15.md) (UI-5)
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

The owner asked on 2026-11-15 to start the next increment after Jenkins build
82, which passed UI-5. The plan names UI-6, coordination and governance, and
says it is large enough to be split into several task lists; this is the first
(decision 1). The decisions in section 4 were taken as the recommended options;
each is reversible, and the owner can change any of them. Each phase ends with
`mvn install` in the check copy at `C:\qw` and an audit of its logs; Jenkins
runs the three suites once the owner has committed and pushed the work.

## 1. Goal

Deliver the Key/Value pages of UI/UX design section 7.1: a directory-style
browser with breadcrumbs, each entry's value and metadata with its recent
events, and create, update, delete and compare-and-set through the mutation
workflow, with what the server must add for them first.

## 2. Current state

- **The key/value API is complete for a browser** (key/value list, sessions
  list, consistency list). `GET /v1/kv/{key}` answers Consul's entries, `Key`,
  `Value` in base64, `Flags`, `CreateIndex`, `ModifyIndex`, `LockIndex` and
  `Session` while one holds it, or 404 `key_not_found`; `?recurse` answers the
  prefix's entries, `?keys` only their keys, `?separator` stops each key at the
  first separator after the prefix, `?raw` the value alone. A read takes
  `stale`, `consistent`, `index` and `wait`, and answers `X-Qraft-Index`, the
  key/value state's last index. `PUT` takes the raw value with `flags` and
  `cas`, 0 to create only, or `acquire` and `release`; `DELETE` takes `cas`
  or `recurse`. A write answers `true` or `false`, a stale compare-and-set
  `false`, with status 200 and no index. Keys are scoped by `X-Qraft-Tenant`
  and `X-Qraft-Namespace`.
- **ACLs:** `key` and `key_prefix` rules, `read`, `write` or `deny`. A read of
  a prefix leaves out what the token cannot read and says so with
  `X-Qraft-Results-Filtered-By-ACLs`; a recursive delete needs write on every
  rule under the prefix. The authorize call answers `key` checks by name and
  scope. Every request under a token is audited.
- **Events:** the key/value state publishes `key.set`, `key.deleted`,
  `key.tree_deleted`, `lock.acquired` and `lock.released` as domain events,
  each with its key or prefix and never the value. The event query filters by
  category, type, scope and service, not by key.
- **No limit on a value.** The server reads the whole request body and submits
  the value to Raft whatever its size. Consul refuses a value over 512 KiB.
- **Flags** are answered as an unsigned 64-bit number; a browser's JSON reading
  rounds one above 2^53 - 1.
- **The interface** has no Key/Value page. Its client sends a body only as
  JSON, and its reads do not report the filtered header.

## 3. Rules

As UI-5's: the interface is a client of the public HTTP API only; red before
green for every behavioural change, with no Mockito; license headers and
attributed Javadoc on new Java files; the design documents and this list are
updated as each phase ends; every log of every run audited (`AGENTS.md`).
Frontend tests use no role query (builds 72 and 75), and drive time with a
scheduler they control, never a wall-clock wait. A value is never logged, never
put in an address, and never written into an event.

## 4. Decisions

Taken 2026-11-15 as the recommended options; each reversible.

1. **UI-6 is three lists:** this one, Key/Value; then Sessions, Locks and
   Watches, with acquire and release, which need a session to hold; then
   Tenants, Namespaces and Audit. Each is a vertical slice that can be released
   alone, and Key/Value is the base the other two build on.
2. **Key/Value is always offered in navigation,** as the catalog views are: the
   server filters what a token may read, and the browser says so when the
   answer carries `X-Qraft-Results-Filtered-By-ACLs`. There is no `list`
   access to ask about (key/value list, decision 5).
3. **The browser lists one level at a time** with `?keys&separator=/`, as
   Consul's interface does: a key ending in `/` is a folder, and an empty
   folder is the 404 of its prefix. Its address is `/ui/kv/<path>`, a folder
   when the path is empty or ends in `/`, a key otherwise, each segment
   encoded. The interface creates no folder key of its own; a key named with
   `/` makes its folders appear.
4. **A value is shown only on its entry's page,** never in the list, an
   address or a log: as text when it is valid UTF-8, laid out when it is JSON,
   and otherwise as binary, its size and base64 shown, and not edited here. The
   platform has no redaction policy for values beyond the ACL rules, so a value
   the token may read is shown. The size is shown; the hash is left out,
   because the browser's Web Crypto works only over HTTPS or on the local host.
5. **Every update and deletion is a compare-and-set** at the `ModifyIndex` the
   page read, and a creation is `cas=0`. A stale `false` is a conflict: the
   outcome gives the index expected and the one now current, and a line
   difference of the value now held against the one sent. Such a write is safe
   to send again after an unknown outcome, since one already applied makes the
   second stale; the workflow then offers it again. An unconditional write is
   not offered.
6. **Flags are edited within the browser's safe integers,** 0 to 2^53 - 1. An
   entry whose flags are above that is shown as such, and its value and flags
   are not edited here: the interface cannot know its flags exactly, and a
   write would change them.
7. **The server limits a value to 512 KiB,** Consul's limit, and answers a
   larger one 413 `value_too_large` before it reaches Raft. The editor says the
   limit and refuses a larger value before the review. The interface reads a
   413 as a validation refusal.
8. **The entry's recent events** come from a new `key` filter on the event
   query: the events that name the key, and the tree deletions whose prefix
   covers it. The page shows them with `operator:read`, which the events read
   needs, labelled as the answering server's ephemeral journal.
9. **Write controls follow the authorize call** (UI-5, decision 3): an entry's
   page asks for `key:write` on its key in the selected scope when it opens;
   a folder's Create asks for `key:write` on its prefix, and its recursive
   deletion is offered with it too, the server refusing it when a rule under
   the prefix grants less, an outcome the workflow shows as a missing
   permission.
10. **The pages follow the server** with blocking queries on `X-Qraft-Index`
    (UI-4). A live update never replaces the editor's draft: the page says that
    the entry changed after it was opened, which a save then meets as a
    conflict.
11. **Acquire and release wait for the next list,** which delivers sessions.
    Until then an entry shows its lock index and the session that holds it.

## 5. Phase 0: The server's answers

1. Test first: the event query and watch take `key`, answering the events that
   name it and the tree deletions whose prefix covers it; a blank key refused.
2. Test first: a value over 512 KiB is answered 413 `value_too_large` and not
   submitted; one of exactly 512 KiB is written and read back.
3. The data contract test writes the key/value answers the interface reads as
   fixtures: a folder's keys, an entry, a held entry, a filtered listing, a
   write's `true`, a stale compare-and-set's `false`, and a key's events.
4. Platform design section 12 documents the filter and the limit.

**Exit gate.** The new tests red first, then green; `mvn install` passes and
its logs are clean.

## 6. Phase 1: Browsing and reading

1. The client: one level of keys, an entry, and the keys under a prefix, each
   in the selected scope and consistency and as a blocking query; reads report
   the filtered header; the value decoded as text, JSON or binary; flags above
   the safe integers recognised.
2. Routing and navigation: `/ui/kv/<path>` (decision 3); Key/Value in
   navigation (decision 2).
3. The browser: breadcrumbs, the folders and keys of one level, an empty
   folder, the filtered notice, and the failure states of UI/UX design section
   13; it follows the server live.
4. The entry's page: key, scope, value, size, flags, create, modify and lock
   indexes, the session that holds it, and its recent events with
   `operator:read`.

**Exit gate.** Each view red first; Vitest and `mvn install` pass, the logs
clean.

## 7. Phase 2: Changes

1. The client sends a value as its raw body, with `flags` and `cas`, and reads
   a write's `true` or `false`.
2. The mutation workflow reads an answer as an outcome: a stale `false` is a
   conflict, shown with its indexes and difference (decision 5).
3. Create in a folder; update and delete an entry; delete a folder's keys after
   a review that counts them; each behind its permission (decision 9), with the
   editor's limits (decisions 6 and 7) and the live notice (decision 10).

**Exit gate.** Each change red first; Vitest and `mvn install` pass, the logs
clean.

## 8. Phase 3: Close

1. The packaged server's end-to-end test with ACLs on: a token with a
   `key_prefix` rule browses, creates, updates, meets a stale write and deletes,
   and its listing is filtered; the output has no error and no value.
2. Headless Chrome against the packaged server, without and with ACLs.
3. The implementation plan, the UI/UX design section 7.1, the platform design
   and the handover record the list; the list is archived.

**Exit gate.** `mvn install` and the end-to-end tests pass with clean logs; the
owner's commit, then Jenkins.

## 9. Out of scope

- Acquire and release, Sessions, Locks and Watches (the next list).
- Tenants, Namespaces and Audit (the third list).
- Transactions (`/v1/txn`), which the server does not have.
