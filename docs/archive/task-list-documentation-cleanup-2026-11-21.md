# Task List: Documentation Cleanup

**Date:** 2026-11-21
**Status:** Complete and archived on 2026-11-21. Written at the owner's word, "we need to have a major cleanup",
after a review of every current document against the code. Every phase is done; the three suites passed locally with
clean audits (section 4). Uncommitted.
**Last updated:** 2026-11-21 (written; Phases 0 to 9 done; archived)
**Active work:** complete on 2026-11-21
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

Every current document says what Qraft is and does now, checked against the code, once, in the place a reader looks
for it. History leaves the living documents: the archive and git hold it. Finished plans and point-in-time reviews
move to the archive.

## 2. Decisions

Taken by the owner on 2026-11-21:

1. **The transcript is deleted.** `docs-design/logging-discussion-verbatim.md`, a 2026-09-10 session with another
   vendor's assistant, holds no rule that `PROJECT_STANDARDS.md` section 6 does not already state; git keeps it.
2. **The design is split.** `docs/HTTP_API.md` lists every server and client route, and `docs/CONFIGURATION.md` every
   setting with its default. The design keeps what and why, and links to them.
3. **`docs/JENKINS.md` is split** into a short living reference and the build and local-run history, archived in
   date order with a table of one row per build.
4. **The two code defects are fixed first, test first:** `qraft operator raft list-peers` takes no token although its
   route needs `operator:read` under ACLs; the server's startup warning says the interface is unauthenticated and
   read-only.

Recommended, taken under the owner's standing instruction not to stop for go-ahead, each reversible:

5. **Living documents carry no dated history.** A "Last updated" line holds the date only. "Since 2026-11-17",
   "Built on 2026-11-08 by …" and retold decisions go; a link to the archived list that built a feature may stay as a
   pointer, not as narrative. Upgrade rules name the behaviour, not a date.
6. **One home per fact.** The suite commands live in `TESTING.md` only, exactly as the Jenkinsfile runs them; rules in
   `PROJECT_STANDARDS.md`; the assistant's working rules in `AGENTS.md`; operator procedures in the runbook. Others
   link.
7. **Archived:** `QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`, `CONSUL_FEATURE_IMPLEMENTATION_PLAN.md`,
   `QRAFT_FEATURE_VALIDATION_2026-10-21.md`, `QRAFT_TEST_SUITE_REVIEW_2026-10-21.md` and `docs/TEST-RESULTS.md`, each
   with every link to it updated. The two questions still open in the interface plan (Metrics, what clients report)
   move to the interface design's "Not built" section.
8. **The runbook is retitled** `docs/OPERATIONS.md`, "Qraft Operations Runbook", and reorganised for an operator; the
   anchor `#detecting-corruption-and-fencing` is kept.
9. **Archived documents are not rewritten.** Their old module and class names are historical identifiers.

## 3. Phases

| Phase | Work |
|---|---|
| 0 | The two code defects, test first |
| 1 | Delete the transcript; archive the five finished documents; update every link |
| 2 | `docs/HTTP_API.md` and `docs/CONFIGURATION.md`, from the code |
| 3 | The design restructured |
| 4 | The runbook retitled and reorganised |
| 5 | `TESTING.md`, `PROJECT_STANDARDS.md`, `AGENTS.md` |
| 6 | `JENKINS.md` split |
| 7 | The event architecture and the interface design |
| 8 | `README.md`, `docker/README.md`, `OPEN_SOURCE_USAGE.md` |
| 9 | The handover; a final pass for links, retired words and dates; `mvn install` |

## 4. Record

- **Phase 0, the code defects** (2026-11-21), test first.
  - `qraft operator raft list-peers` takes `--token` or `--token-file`, and sends it as `X-Qraft-Token`; without one
    it sends no token header. Red first: `Unknown option: --token`, and an unreadable token file exited 2. The token's
    reading is shared with `remove-peer` (`RaftOperatorCommand.token`).
  - The interface's startup line: with ACLs disabled, a WARN that anyone who can reach the server can read and change
    the cluster through it; with ACLs enabled, an INFO that its requests carry the signed-in caller's token. Red first:
    both tests met the old "unauthenticated: it is read-only" WARN. With ACLs on the line is INFO, not WARN: a level
    change, taken as part of the correction the owner chose, and reversible.
  - `RaftOperatorCommandTest` 9 and `HttpApiServerAdminUiTest` 27 passed.
  - **A race in two tests**, found by the first `mvn clean install` of the phase: `QraftServerAclStartupTest` failed
    with a `ConcurrentModificationException`, streaming a `ListAppender`'s list while the server's threads logged into
    it. Logback appends under the appender's own lock (`AppenderBase.doAppend` is synchronized), so
    `dev.mars.qraft.testing.LogCaptures.snapshot` copies the list under that lock; red first (the unlocked read
    finished while an append held the lock). The two tests that read a capture while the code under test still runs,
    `QraftServerAclStartupTest` and `ClientTlsReloadTest`, use it; every other reader reads after the work has
    finished or the appender is detached. Production code reads no such list. The end-to-end failures of that run
    followed from it: the build stopped before packaging, so the packaged-jar tests found no `target/qraft.jar`.
- **Phase 1, delete and archive** (2026-11-21).
  - `docs-design/logging-discussion-verbatim.md` deleted (decision 1).
  - Archived, each opened with an archive note and its own links rebased: `QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`,
    `CONSUL_FEATURE_IMPLEMENTATION_PLAN.md`, `QRAFT_FEATURE_VALIDATION_2026-10-21.md`,
    `QRAFT_TEST_SUITE_REVIEW_2026-10-21.md`, and `docs/TEST-RESULTS.md` as `TEST-RESULTS_2026-10-10.md`, whose line
    attributing a recommendation to an assistant was rewritten (`AGENTS.md`, "No assistant attribution").
  - The runbook renamed `docs/OPERATIONS.md` ahead of its rewrite, so the other rewrites link to its final name.
  - 39 documents' links updated; 425 relative links checked, none broken.
- **Phases 2 to 8** are written in parallel, one writer per document or group, each with the review's findings for it
  and only its own files to change.
  - Phase 2: `docs/HTTP_API.md`, 662 lines, every server and client route from the code; `docs/CONFIGURATION.md`, 576
    lines, every setting with its default and rules, from the code.
  - Phase 5: `docs/TESTING.md` 987 to 359 lines, the suite commands exactly as the Jenkinsfile runs them;
    `docs/PROJECT_STANDARDS.md` with standards for the interface's TypeScript and Vitest tests, the `TestPorts` rule
    and the coverage gate; `AGENTS.md` holding every working rule for an assistant, in the present tense.
  - Phase 6: `docs/JENKINS.md` 1,357 to 147 lines; the history archived verbatim in date order, with a table of 99
    rows, as `docs/archive/JENKINS_BUILD_HISTORY_2026-10-03_to_2026-11-20.md`.
  - Phase 7: `docs/QRAFT_EVENT_ARCHITECTURE.md` describes the event system as built, with a table of its 28 event
    types; `docs-design/QRAFT_ADMIN_UI_UX_DESIGN.md` describes the interface as built, with its section numbers kept
    (the code cites them) and a "Not built" section that holds the archived plan's open questions 4 and 6.
  - Phase 8: `README.md` rewritten as the front page; `docker/README.md` the one home for hand-run clusters;
    `OPEN_SOURCE_USAGE.md` 240 to 99 lines, with the interface's npm libraries.
  - Five archived links pointed at `TESTING.md` sections removed as history; each now names the section as committed in
    `cc3b171`.
  - Phase 3: the design 3,365 to 1,938 lines, in seventeen sections: what Qraft is and why, its contracts and rules;
    routes and settings left for the two references; procedures for the runbook; every dated record removed.
  - Phase 4: `docs/OPERATIONS.md`, "Qraft Operations Runbook", reorganised for an operator in ten sections with a
    table of contents and a symptom index, its wrong claims corrected, the procedures that read the servers given a
    token, and its test evidence removed; the anchors other documents link to are kept.
- **Phase 9, the final pass** (2026-11-21).
  - The handover replaced by `HANDOVER_2026-11-21.md`, about a quarter of the old length; the archived open-items list
    names the old one as committed in `cc3b171`.
  - Twenty code comments cited the platform design by its old section numbers; each now names the section that holds
    that content, or the reference that took it. Two citations in the interface design, likewise. A comment in
    `schemas.ts` cited the event architecture's sections 3 and 5 for categories and severity; it is 4 and 5.
  - Four links into the runbook named headings the rewrite renamed; each points at the heading that holds its subject.
  - The checks over every living document: 577 relative links and anchors, none broken; CRLF throughout; no retired
    word for a Qraft component (Consul's agent and Jenkins's own words aside); no dated history but the live contract
    that a field goes after 2026-12-31; no assistant attribution.
  - The writers' reports named code defects beyond this list's scope; they are recorded in the handover's section 5
    for the owner, none fixed.
  - The three suites, locally from `C:\qx11` with every change of the list: `mvn clean install` 2,099 and Vitest 379,
    every coverage gate; end-to-end 36; Docker and `slow` 52, in 27 minutes. Docker Desktop was not running at first,
    so the first Docker stage failed at the image build in every class, which the build fixture passes on as the cause
    without trying again; it was started and the stage run again. The audits, 1,084 files with 25 Docker archive
    folders, found every error line and stack trace flagged and no `too_many_pings`. The four `.dumpstream` files hold
    only the JDK's class-data sharing warning (above).
- **The JDK on the `mraysmit` workstation** (found by the audit of the Phase 0 rerun): `.jdks\openjdk-27-1` was updated
  in place on 2026-11-20 to 27.0.1.1, except `lib\modules`, still the 27+35 file, so every JVM prints a class-data
  sharing warning that Surefire records in a `.dumpstream` file. `.jdks\openjdk-27.0.1.1_windows-x64_bin` holds only
  the new `lib\modules`, left by the interrupted update. The owner's to repair, outside the repository.
