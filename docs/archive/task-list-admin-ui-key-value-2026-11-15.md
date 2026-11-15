# Task List: Administrative Interface, Key/Value (UI-6, part 1)

**Date:** 2026-11-15
**Status:** Complete and archived on 2026-11-15. Phases 0 to 3 are done, test first; Phase 0's key filter is in `3a82654` and `f45bf2a`, which Jenkins build 84 passed with a clean audit, and the rest is uncommitted. `mvn install`, the end-to-end stage and headless Chrome passed in the check copy with every log audited, none unflagged.
**Last updated:** 2026-11-15 (Phases 2 and 3 done, the list complete and archived, its links rewritten from its new place. Earlier the same day: Phase 1 done, its install clean, and Phase 2's changes built test first. Earlier the same day: Phase 0 done, its install clean; Phase 1 steps 1 and 2 done, and decision 3's address moved to the query. Earlier the same day: Phase 0 steps 3 and 4 done, the contract and the documents. Earlier the same day: step 2 done: the value limit already existed, and decision 7 is corrected. Earlier the same day: step 1 done, the key filter; started)
**Active work:** complete on 2026-11-15
**Implementation plan:** [`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](../../docs-design/QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md), increment UI-6
**Source plan:** [`QRAFT_ADMIN_UI_UX_DESIGN.md`](../../docs-design/QRAFT_ADMIN_UI_UX_DESIGN.md), sections 4.3, 7.1, 11 and 12; [`task-list-key-value-2026-11-08.md`](task-list-key-value-2026-11-08.md)
**Predecessor:** [`task-list-admin-ui-access-mutations-2026-11-15.md`](task-list-admin-ui-access-mutations-2026-11-15.md) (UI-5)
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

The owner asked on 2026-11-15 to start the next increment after Jenkins build
82, which passed UI-5. The plan names UI-6, coordination and governance, and
says it is large enough to be split into several task lists; this is the first
(decision 1). The decisions in section 4 were taken as the recommended options;
each is reversible, and the owner can change any of them. Each phase ends with
`mvn install` in the check copy at `C:\qw` and an audit of its logs; Jenkins
runs the three suites once the owner has committed and pushed the work. The
list was completed on 2026-11-15, with the completion summary below, and moved
to `docs/archive/`.

**Completion summary.** UI-6's Key/Value pages are delivered. On the server: the
event query's `key` filter, and a test that a value is held to the request
body's 512 KiB limit, which was already there. In the interface: Key/Value in
navigation; a folder's one level with breadcrumbs, the filtered notice and its
empty states; an entry's value as text, laid-out JSON or base64, with its size,
flags, indexes, session and recent events; every update and deletion a
compare-and-set at the index read and a creation at 0, a stale write shown with
its indexes and line difference; a folder's keys counted and deleted; the
editor's limits and its notice when the entry changes under it; and each change
behind `key:write` in the selected scope. Decisions 1 to 11 were taken as the
recommended options, each reversible; decisions 3 and 7 were corrected on the
way, the address moved to the query and the limit found to exist. One fault was
found and fixed, test first: the mutation workflow read a 413 as retryable. Left
for later: acquire and release, with Sessions, Locks and Watches (UI-6's second
list), and Tenants, Namespaces and Audit (its third).

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
- **A value is at most 512 KiB.** Every request body over 512 KiB, Consul's
  limit, is refused 413 `request_too_large` before its route runs
  (`JsonApi`). (Written first as no limit: the survey missed it, and Phase 0
  found it.)
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
   folder is the 404 of its prefix. Its address is `/ui/kv?path=<path>`, a
   folder when the path is empty or ends in `/`, a key otherwise. (Written
   first as `/ui/kv/<path>`: the server answers an interface address with an
   empty segment 404, and takes one whose last segment has a dot for a file,
   and a key may have both, as `app/` and `config.json` do.) The interface
   creates no folder key of its own; a key named with `/` makes its folders
   appear.
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
7. **A value is at most 512 KiB,** the server's limit on every request body,
   which answers a larger one 413 `request_too_large` before the route runs.
   The editor says the limit and refuses a larger value before the review. The
   interface reads a 413 as a validation refusal. (Written first as a new
   server limit with its own code; the limit was already there.)
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

1. Test first: the event query takes `key`, answering the events that name it
   and the tree deletions whose prefix covers it; an empty key refused. The
   watch reads the same filters.
2. A value over 512 KiB is answered 413 `request_too_large` and not
   submitted; one of exactly 512 KiB is written and read back.
3. The data contract test checks the key/value answers in the fields and types
   the interface will read: a folder's keys, an entry, a held entry, a filtered
   listing, a write's `true`, a stale compare-and-set's `false`, and a key's
   events.
4. The event architecture documents the filter, where the event query is
   documented; the platform design already documents the limit (section 12).

**Exit gate.** The new tests red first, then green; `mvn install` passes and
its logs are clean.

**Status, 2026-11-15: step 1 done**, test first.

- `EventContractsTest` (1 new), red first as it did not compile: a key filter
  takes the `key.set`, `key.deleted`, `lock.acquired` and `lock.released`
  events of the key, and the `key.tree_deleted` events whose prefix covers it,
  the whole store's included, and not a key that only begins with it.
  `HttpApiServerEventQueryTest` (1 new), red first on the 400 of the unknown
  parameter: a key's events through a write, an acquisition, a release, a
  deletion and a tree deletion, with the type and scope filters, and an empty
  key refused. The key/value events are `common.events.KeyEvent`s, and the key
  is read as given, not trimmed, since a key may begin or end with a space
  (`logs/kv-p0-key-red-route-*.log`, `logs/kv-p0-key-green-*.log`).

**Status, 2026-11-15: step 2 done.** `HttpApiServerKvTest` (1 new): a value of
exactly 512 KiB is written and read back whole; one byte more, a creation or
not, is refused 413, nothing reaches the log and no key appears. Written first
to expect a new `value_too_large`, it failed on the `request_too_large` that
`JsonApi` already answers for every request body over 512 KiB; the test now
holds that limit for values, and passed at once. No server change.

**Status, 2026-11-15: steps 3 and 4 done.** `AdminUiDataContractTest` (1 new),
on a server with ACLs on: a write's `true` and a stale compare-and-set's
`false`, both 200; a folder's keys with a sub-folder ending in `/`; an entry's
fields, `Session` absent while none holds it and present when one does; a
listing under a token that may read only `app/`, its other keys left out and
`X-Qraft-Results-Filtered-By-ACLs` set; and a key's events, without its value.
It passed at once: every answer existed but the key filter, built red first in
step 1. The event architecture, the operations guide and the implementation
plan document the filter.

**Status, 2026-11-15: done.** `mvn clean install` in the check copy: default
2,057 and Vitest 225, every gate; the audit of the files the run wrote, 606,
found 853 error lines and 753 stack traces, every one flagged
(`logs/kv-p0-install-20261115-222950.log`).

## 6. Phase 1: Browsing and reading

1. The client: one level of keys, an entry, and the keys under a prefix, each
   in the selected scope and consistency and as a blocking query; reads report
   the filtered header; the value decoded as text, JSON or binary; flags above
   the safe integers recognised.
2. Routing and navigation: `/ui/kv?path=<path>` (decision 3); Key/Value in
   navigation (decision 2).
3. The browser: breadcrumbs, the folders and keys of one level, an empty
   folder, the filtered notice, and the failure states of UI/UX design section
   13; it follows the server live.
4. The entry's page: key, scope, value, size, flags, create, modify and lock
   indexes, the session that holds it, and its recent events with
   `operator:read`.

**Exit gate.** Each view red first; Vitest and `mvn install` pass, the logs
clean.

**Status, 2026-11-15: done**, each step red first.

- `api/kv.test.ts` (13): one level of a folder in the scope, the consistency
  and the blocking query; an empty folder's 404 as one with nothing in it; each
  segment of a key encoded, its `/` kept; an entry, and a missing one as none;
  every key under a prefix; the filtered header on every read, `Read.filtered`;
  another refusal a failure; a value as text, laid out when a JSON object or
  list, its size in bytes, or binary with its base64; flags exact only within
  the safe integers; and a level's rows, folders first.
- `routing.test.ts` (1 new): `/ui/kv`, with the folder or key in `path`
  (decision 3, corrected).
- `views/kv.test.tsx` (11) and `security.test.tsx` (2 updated): Key/Value in
  navigation after Health Checks; a folder's rows and breadcrumbs, never a
  value; the scope and consistency read, with the read metadata; an empty
  folder, the filtered notice, the empty top; an entry's laid-out JSON, text,
  binary value, size, flags and those above the safe integers, indexes and
  session; a key that is not there; its recent events with `operator:read`
  through the new `key` filter, and none read without it.
- `mvn clean install` in the check copy: default 2,057 and Vitest 250, every
  gate; the audit of the files the run wrote, 603, found 841 error lines and
  741 stack traces, every one flagged (`logs/kv-p1-install-20261115-223936.log`).

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

**Status, 2026-11-15: done**, each step red first.

- `api/kv.test.ts` (5 new): a value sent as the raw body with its flags and
  `cas` in the scope; a stale `false` read as the answer it is; a deletion at
  its index and a folder's recursive deletion; a write never answered an
  unknown outcome; and the line difference.
- `mutation.test.tsx` (2 new): an answer that refuses the change, as a stale
  `false`, shown as the conflict it stands for with the page's detail
  (`judge` and `detail`); and a 413, which the workflow had read as
  retryable, read as a validation refusal.
- `views/kvChanges.test.tsx` (12): changes offered only with `key:write` on the
  key or folder in the scope; an edit reviewed by its size and flags and saved
  at the index read; a stale write's indexes and line difference; a value over
  512 KiB and flags outside the safe integers refused before the review; the
  notice when the entry changes under the editor, the draft kept; a deletion at
  the index read; a binary value not edited, still deleted; a key created only
  if none of its name exists, a bad name refused, and a link to it; a folder's
  keys counted and deleted; no deletion of the whole store; nothing offered
  without the permission.
- Vitest: 269 passing, nothing on stderr.
- `mvn clean install` in the check copy: default 2,057 and Vitest 269, every
  gate; the audit of the files the run wrote, 603, found 844 error lines and
  744 stack traces, every one flagged (`logs/kv-p2-install-20261115-224733.log`).

## 8. Phase 3: Close

1. The packaged server's end-to-end test with ACLs on: a token with a
   `key_prefix` rule browses, creates, updates, meets a stale write and deletes,
   and its listing is filtered; the output has no error and no value.
2. Headless Chrome against the packaged server, without and with ACLs.
3. The implementation plan, the UI/UX design section 7.1, the platform design
   and the handover record the list; the list is archived.

**Exit gate.** `mvn install` and the end-to-end tests pass with clean logs; the
owner's commit, then Jenkins.

**Status, 2026-11-15: done.**

- `PackagedAdminUiEndToEndTest.aPackagedServerWithAclsOnAnswersTheKeyValuePagesCallsUnderAKeyPrefixToken`:
  the shaded jar with ACLs on, under a token that may write `app/`: a creation,
  refused once the key exists; an update at the index read, refused once stale;
  a level filtered to `app/` with the filtered header; the authorize answers;
  the key's two events, without its value; a deletion at the index read and a
  folder's; the whole store's deletion refused; and the server's output holds no
  value, no secret and no error. It passed at once: it accepts what Phases 0 to
  2 built test first. The end-to-end stage as Jenkins runs it: 34 tests, all
  passing, its audit of 60 files clean (`logs/kv-p3-e2e-*.log`).
- Headless Chrome against the packaged server, over the DevTools protocol,
  without ACLs (the top, a folder, a nested folder, an entry, and a key with a
  dot in its name) and with them, after sign-in (the top, a folder, an entry, a
  dotted key, a token): each showed its data and controls, every style carried
  the nonce, Chrome logged no console message, no value appeared in a listing,
  no secret on any page or in the server's output, and the server logged no
  error.
- The implementation plan, the UI/UX design section 7.1, the event
  architecture, the operations guide and the handover record the list.

## 9. Out of scope

- Acquire and release, Sessions, Locks and Watches (the next list).
- Tenants, Namespaces and Audit (the third list).
- Transactions (`/v1/txn`), which the server does not have.
