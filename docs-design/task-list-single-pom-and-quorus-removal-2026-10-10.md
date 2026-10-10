# Task List: Single POM and Removal of the Quorus Leftovers

**Date:** 2026-10-04
**Last updated:** 2026-10-10 (Phase 5 done and its exit met: the version source, then the configuration without a default document, as typed records, with one shared parsing helper; each verified on Jenkins; three third-party keywords that Phase 3A's sweep had renamed are restored; the file renamed to the date of its last change. On 2026-10-09: the plans and the other documents brought up to date with Phase 4, in Phase 8; Phase 4 done, with its mutation evidence; its four open points reviewed with the user and its three remaining decisions confirmed; Phase 3A added and done; the audit of child JVMs under Phase 2A; the file renamed to the date of its last change)
**Status:** In progress. This is the current task list. It started on 2026-10-04 at the user's request, before the membership list's Step 4 close-out gate.
**Active work:** Phase 3A, which replaces Qraft's two retired words with `client` and `server`, was applied and verified on 2026-10-09, and committed as `5a00975`. Phase 3 is implemented and verified complete on 2026-10-08. Phase 4 was done and its exit met on 2026-10-09: the legacy node fixtures, the Consul-shaped node, the removal of the capabilities update, the three node statuses, the node routes at `/v1/catalog/*`, and the removal of `/api/v1/info`, `/status`, and bare `/health`; it was committed as `e4bf00d` to `a6e7930`. The user reviewed its four open points the same day: three were done at once and one became a task of Phase 7. The user then confirmed the phase's three remaining decisions as built. Nothing of Phase 4 is open. Phase 5 was done and its exit met on 2026-10-10: Jenkins build 4 verified its version source and build 5 the rest. The next coding task is Phase 6. Phase 2A was accepted on 2026-10-08; its last open task, the audit of child JVMs that cannot audit themselves, was done on 2026-10-09. Phases 0 to 2 were done 2026-10-04.
**Last reviewed:** 2026-10-08, against the code at commit `9a4adf0`, with the other four task lists. The status table in section 4 and every item marked "review of 2026-10-08" come from that review. No build was run for it. The logs that the Phase 2A and Phase 3 records cite were written in another working tree and were not available, so their counts were checked for arithmetic only. The earlier review of 2026-10-05 was against `24beac3`; its items keep their date.
**Order of work:**
- The membership list's Step 4 close-out gate is the first task of Phase 7 here.
- After this list, the membership list resumes at its Step 5, on the new layout, and runs to its end.
- [`task-list-consul-style-client-2026-10-10.md`](task-list-consul-style-client-2026-10-10.md) follows the membership list. Decided 2026-10-05.

**Related:** [`task-list-raft-membership-changes-2026-10-10.md`](task-list-raft-membership-changes-2026-10-10.md) (interrupted by this list), [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md)
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md), section 2.4: inherited components must not remain without a clear role

## 1. Goal

Ship Qraft as what it now is: one Maven project, one jar, two runtime modes,
with nothing left of Quorus's file-transfer fleet model, its Vert.x-shaped
runtime layer, or its unused tooling.

## 2. Decisions

1. **Client mode stays and becomes a full Consul-style client.** Decided
   2026-10-04, for VMs and bare metal. That work is
   [`task-list-consul-style-client-2026-10-10.md`](task-list-consul-style-client-2026-10-10.md).
   This list removes only the client's Quorus-era node model.
2. **One POM, one artifact.** The root `pom.xml` is the only POM and builds
   `target/qraft.jar`. Module boundaries become package boundaries, enforced by
   a dependency test.
3. **Packages follow the modes:** `server` (was `controller`) and `client` (was
   `agent`). The client configuration's object kept its old name until
   2026-10-09, when Phase 3A renamed it to `client`. The server's
   `/v1/client/*` paths move in the client list, not here.
4. **Removed formats stay readable.** Removed protobuf fields and messages are
   `reserved`; snapshot readers ignore removed fields; fixtures prove old WAL
   entries and snapshots still load.
5. **Phase 7 belongs here.** It may move to its own list if it grows, because
   it touches `RaftNode`.

## 3. Rules

- Phases 1 to 3 change no behaviour, with one exception: Phase 3 renames the
  log files and the default telemetry service name. Evidence: the same tests,
  by name and count less deleted tests, pass before and after.
- Phase 3 broke this rule where its verification found defects (recorded by
  the review of 2026-10-08). Each change was made test first, and each has a
  regression test:
  - `RaftNode.becomeLeader` appends the leadership no-op before it publishes
    leadership, and declines leadership once a stop has been requested;
  - a failed telemetry start releases the trace and metrics resources it had
    opened;
  - the logging directory and mode are chosen before the first log event.

  The first of these changes the code that the membership list's Step 4 gate
  covers; that gate now names it.
- Phase 2A changes the test harness. It changes production code only to fix
  what its inventory finds: an ERROR no test intends, or a failure logged
  without its exception. Each such change is made test first.
- Phases 4 to 7 change behaviour: red before green, with recorded mutations for
  safety guards.
- Each phase ends with `mvn install`; phases touching the runtime, Docker, or
  async code also run the end-to-end suite and the Docker suite on a fresh image.
- Until Phase 2A is complete, a log review is a comparison with the previous
  phase's logs by kind of ERROR/WARN line and exception. It is heuristic: it
  cannot tell an intended error from a regression of the same kind, and it must
  be reported as such. After Phase 2A, the build decides.
- The user runs builds in the VS Code terminal and commits; one commit per phase.

## 4. Tasks

### Status (2026-10-08)

Brought up to date by the review of 2026-10-08, from the code at `9a4adf0` and
the records below. Phase 2A was accepted on 2026-10-08 after 845 default and 31
tagged tests passed, coverage passed, and all 80 retained log files were audited
without unflagged errors or exceptions. See `docs/TESTING.md` for the flagging
rules and extracts from that run.

| Phase | State | Tasks done | Commit |
|---|---|---|---|
| 0. Baseline | Done; the evidence was copied into the repository on 2026-10-08 | 3 of 3 | none before 2026-10-08: its output was in `logs/` only |
| 1. Delete dead code and files | Done; verified against the code | 6 of 6 | `bbf5046` |
| 2. Single POM | Done; verified against the code | 7 of 7 | `04dddeb` |
| 2A. Intentional errors labelled | Done: accepted 2026-10-08; the audit of child JVMs added 2026-10-09 | 9 of 9 | `24beac3` to `26a3570` |
| 3. Package layout | Done; verified 2026-10-08. One task added by the review: the comparison of test names with the Phase 0 baseline | 10 of 11 | `9a4adf0` |
| 3A. One word for each mode | Added and done 2026-10-09; verified the same day | 5 of 5 | `5a00975` |
| 4. Node model and API | Done 2026-10-09, exit met; its four open points settled and its decisions confirmed by the user the same day | 7 of 7 | `e4bf00d` to `a6e7930` |
| 5. Configuration and version | Done 2026-10-10, exit met | 6 of 6 | `693449e`, `c123ee9`, `12d365c`, `d4e1af8` |
| 6. Docker and observability | One task added and done on 2026-10-10; the rest not started | 1 of 11 | `ce5890d` |
| 7. Async layer | Not started | 0 of 6 | |
| 8. Documentation and close-out | Started early for Phases 1 and 2; Phase 3's names done with Phase 3; the documents of Phases 4 and 5 done as each ended | 2 of 6 | |

### Phase 0. Baseline

- [x] Record a baseline run: `mvn install`, end-to-end, and Docker suites
  (812 / 7 / 23 on 2026-10-02), with their logs kept for comparison.
- [x] Record the runtime jar's class list as the packaging baseline.

**Record (2026-10-04).** `logs/refactor-baseline-2026-10-04/` holds the jar's
14,357 entries and the 842 test cases by module, class, and name. The default
suite is `logs/qraft-tests-2026-10-03_23-58-53-674.log`: BUILD SUCCESS with 812
tests, run on sources identical to HEAD `532e562` apart from documentation. The
23 Docker and 7 end-to-end cases come from the 2026-10-02 reports.

- [x] Keep the evidence in the repository (added 2026-10-05). `logs/` is
  ignored by git, so the baseline lists, `phase2/phase2_restructure.py`, and
  `tools/log_kinds.py` exist only in the working tree that produced them. Move
  the two baseline lists and the two tools to a tracked directory before
  Phase 3, whose exit compares test names with the baseline.

  Done late, on 2026-10-08, after Phase 3.
  `docs/archive/refactor-baseline-2026-10-04/` holds byte-identical copies of
  `testcases.txt`, `runtime-jar-entries.txt`, `README.txt`,
  `phase2/phase2_restructure.py`, and `tools/log_kinds.py`. The originals stay
  in `logs/`. Phase 3 ran in a working tree that did not have them, so its
  exit did not use them (Phase 3, "Exit as met").

### Phase 1. Delete dead code and files

- [x] Delete `qraft-tenant`: no module imports it. Namespaces return as
  replicated state with the tenancy list.
- [x] Delete `GenericStateStore`; only `ControllerStateStoreTest` uses it.
- [x] Delete the per-mode `main` methods in `QraftAgent` and
  `QraftControllerApplication`; the runtime calls their `launch` methods.
- [x] Delete the Quorus ASCII-art banner, which
  `QraftControllerApplication` printed with `System.out.println`, against
  `PROJECT_STANDARDS.md` section 6.1.
- [x] Remove the qraft-tenant lines from the README and the
  `PROJECT_STANDARDS.md` module list. The design documents change in Phase 8.
- [x] Remove the untracked strays: root `dev/`, `test-logs/`, and
  `.git/index.lock.stale-from-claude`. The `ftp-docker` directory was already gone.

`qraft-controller.json` moved to Phase 5. It is not unused: it holds the
classpath defaults for `AppConfig`'s static instance.

**Exit:** `mvn install` passes; only the deleted tests are missing: the 10
qraft-tenant cases and `genericStoreAppliesCommandsAndRestoresSnapshots`. The
default suite expectation is 801 tests.

**Record (2026-10-04).** `mvn clean install` on JDK 27
(`logs/qraft-tests-2026-10-04_17-29-37-924.log`): BUILD SUCCESS, every coverage
gate met, 801 tests (9 + 21 + 46 + 125 + 564 + 36).
- **Tests.** Against the baseline, the default suite lost exactly the 11 deleted
  cases and gained none.
- **Jar.** The runtime jar lost exactly the 12 entries of `qraft-tenant` and
  `GenericStateStore`, and gained none.
- **Warnings.** Maven's warnings are unchanged apart from the jar list in the
  shade overlap warning.

The end-to-end and Docker suites were not run for this phase; Phase 2 runs them
on a fresh image.

**Verified against the code (review of 2026-10-05).** Every task holds.
- No `qraft-tenant` directory, and no reference to it, to `GenericStateStore`,
  or to `NamespaceService` outside the documents.
- One `main` method, in `QraftRuntimeApplication`, which calls the two
  `launch` methods.
- No `System.out` or `System.err` in production code, and no "quorus" in any
  file outside the documents.
- The strays are gone.

### Phase 2. Single POM

- [x] Make the root `pom.xml` the only POM. Merge dependencies, protobuf
  generation, compiler, enforcer, surefire, JaCoCo, and shade configuration.
- [x] Move all sources, protos, and resources into one `src/` tree. No
  class names collide; the three identical `junit-platform.properties` become one.
- [x] Keep one `logback.xml` for both modes: the controller's, which is the one
  the executable jar already shipped.
- [x] Build `target/qraft.jar`. Update the Dockerfile location, entrypoint,
  `build-runtime.*`, compose `dockerfile:` paths, `.dockerignore`,
  `SharedDockerCluster`, and `DockerDeploymentContractTest`.
- [x] Define the test groups once: the default build excludes `e2e`, `docker`,
  and `slow`.
- [x] Simplify the Jenkinsfile: no per-module loops or `-pl`.
- [x] Add a package-dependency test: client never imports server, Raft, or
  state; Raft and state never import the HTTP layer; nothing imports the
  entry point. Decided: a hand-written test on the JDK class-file API, not ArchUnit.

**Exit:** Same tests as Phase 1; all coverage gates; end-to-end and Docker
suites on a fresh image; jar contents differ from the baseline only as expected.

**Record (2026-10-04).** The change was made by
`logs/refactor-baseline-2026-10-04/phase2/phase2_restructure.py`, first in a
copy of the repository and then in the working tree, with identical results.
- **Layout.** All 138 production and 148 test sources moved unchanged. The
  module POMs and directories are gone.
- **Image and files.**
  - The image's Dockerfile and entrypoint are now in `docker/`.
  - Deleted as unused: the second, agent-only image (`qraft-agent/Dockerfile`
    and its entrypoint) and the Quorus-era `qraft-controller/.env`.
  - The logs that tests had written inside module directories are now under
    `logs/legacy-module-logs/`.
- **Tests removed:**
  - the agent's `LoggingConfigurationTest`, whose `logback.xml` never reached
    the jar;
  - `everyTestJarAModuleDependsOnIsPublishedByItsModule`, since there are no
    module test-jars.
- **Tests renamed.** `agentContainerUsesTheConventionalMountedConfiguration`
  is now `runtimeImagePassesTheModeAndConfigurationThroughItsEntrypoint`.
- **Expected default suite:** 803 tests (801 − 2 + 4).
- **`PackageDependencyTest`.** It reads every production class's constant
  pool with the JDK class-file API and encodes the former module edges. Its
  types are layers: ENGINE, STATE, CORE, CLIENT, SERVER, and RUNTIME. The two
  packages split across modules are classified type by type until Phase 3.
- **Mutation evidence.** Run against the 2026-10-04 classes on JDK 27, the
  test passed. After adding a client class with a field of a server type, and
  a state class calling a shared agent type, it reported exactly those two
  dependencies.
- **POM fix.** The first build failed to compile: `logback-classic` must be in
  compile scope, because `TelemetryConfig` installs the OpenTelemetry appender,
  a Logback appender type. The old controller POM had it in compile scope.
- **Test race exposed.** The second build ran 803 tests and one failed:
  `RaftNodeTransportGenerationTest.delayedAppendSuccessFromPreviousLeadershipCannotAdvancePeerIndexes`.
  - **Cause.** The test read `getLastLogIndex()` and `getLastLogTerm()`
    separately from the test thread while the new leader's no-op was landing
    on the state loop. It got index 1 with term 1, and the node correctly
    rejected the append as inconsistent.
  - **Why now.** The timing of the merged single-JVM run exposed it.
  - **Production.** Production code has no off-loop callers of these getters;
    it reads `status()` on the loop.
  - **Fix.** The new `RaftAwait.logEnd(runtime, node)` reads both on the state
    loop in one step. It is used here and in
    `RaftNodeOutboundSnapshotGenerationTest.stepDownAndReelect`, which reads a
    fresh leader's log the same way.
  - **Left as they are.** The same getter pair in `RaftNodeTest` and
    `RaftNodeTimerSequencingTest` is read after an awaited, committed command,
    when the log is settled.
- **Results.**
  - Ten consecutive runs of the two changed test classes: 9 of 9 each time
    (`logs/qraft-tests-repeat-2026-10-04_18-06-38-962.log`).
  - `mvn clean install` on JDK 27: 803 tests, every coverage gate met
    (`logs/qraft-tests-2026-10-04_18-08-12-135.log`).
  - End-to-end and Docker suites on the fresh image: 30 of 30
    (`logs/qraft-tests-2026-10-04_18-16-10-026.log`).
- **Test names.** Against the 842 baseline cases, exactly the deleted, removed,
  and renamed cases are missing, and only the four `PackageDependencyTest`
  cases and the renamed case are new: 833 in total.
- **Jar contents.**
  - Qraft classes differ only by Phase 1's deletions.
  - The per-module Maven metadata became one `META-INF/maven/dev.mars/qraft/`.
  - Of the 73 bundled third-party artifacts, one version changed:
    `org.jetbrains:annotations` went from 13.0 to 17.0.0.
  - **Cause.** In one build, test dependencies take part in version mediation,
    and Testcontainers' newer copy won.
  - **Fix.** `pom.xml` now pins 13.0 in `dependencyManagement`, so the jar
    bundles what it did before.
  - **Verified with the pin.** `mvn clean install`
    (`logs/qraft-tests-2026-10-04_18-36-39-619.log`) ran 803 tests with every
    coverage gate met.
    - All 73 bundled third-party artifacts and their versions match the
      baseline.
    - The jar differs from the baseline only by Phase 1's deletions and the
      Maven metadata.
    - Maven's warnings are unchanged, except that `logback.xml` is no longer
      an overlapping resource.
- **Log review.** The logs were compared with the baseline by kind of
  ERROR/WARN line and exception (`logs/refactor-baseline-2026-10-04/tools/log_kinds.py`).
  - **Clean.** No Maven `[ERROR]` lines and no ANSI characters.
  - **No new kinds.** Every ERROR line in the default suite belongs to a kind
    the baseline also logged: fault injection, fencing, WAL corruption,
    unreachable peers, and shutdown-hook failures. Counts differ only where
    timing or chaos decides them.
  - **One kind gone:** the deleted `GenericStateStore` test's warning.
  - **One DEBUG exception not in this baseline:** the in-memory transport's
    "Interrupted awaiting the target node". A delivery still in flight is
    interrupted when a test stops its nodes. It is a test fixture's teardown
    path, already logged on 2026-09-28 and 2026-10-02.
  - **End-to-end and Docker run.** Its ERROR lines are the peers that a killed
    leader leaves unreachable, as in the 2026-10-03 run. Its WARN lines are the
    unauthenticated administrative-interface startup warning.
- **Exit gate met.** Phase 2 was committed as `04dddeb`.
- **Found for Phase 3.** The jar has always shipped the controller's
  `logback.xml`, so client mode writes `qraft-controller-server.log`. Phase 3
  names the log files by mode.

**Verified against the code (review of 2026-10-05).** Every task holds.
- One `pom.xml`, one `src/` tree, one `junit-platform.properties`, and one
  `logback.xml`. The final name is `qraft`.
- `docker/Dockerfile` copies `target/qraft.jar`. Every compose file, both
  `build-runtime` scripts, and `.dockerignore` point at them.
- The default build excludes `docker`, `slow`, and `e2e`. The Jenkinsfile has
  no `-pl`.
- `PackageDependencyTest` has four cases and the six layers. Logback is in
  compile scope, and `org.jetbrains:annotations` is pinned at 13.0.
- `RaftAwait.logEnd` is used in the two named classes. `RaftNode` reads its
  last log index and term only on the state loop.
- The source holds 138 production files and 158 test files: the 148 of this
  phase and the ten of Phase 2A. It holds 854 test annotations: the 833 of
  this phase and the 21 that Phase 2A added.

**Correction (review of 2026-10-05).** The dependency test enforces less than
the task above says.
- It enforces "Raft and state never import the HTTP layer" only for
  `raft.api`, `distributedstate`, and `catalog`.
- The Raft implementation (`controller.raft`), the state host
  (`controller.state`), and the HTTP layer (`controller.http`) are all one
  layer, SERVER, so the test would pass if `RaftNode` imported the HTTP
  server. It does not import it today: only `http`, `health`, and `api`
  import `raft` and `state`, never the reverse.
- Phase 3 separates the layers and makes the rule real.

### Phase 2A. Every intentional error is labelled as intentional in the log

**Problem (found 2026-10-04).** A reader of a test log cannot tell an
intentional ERROR from a real one.
- Production code logs the ERROR, and nothing on the line says that a test
  caused it.
- Injected faults are generic JDK exceptions created in 43 test files. (The
  review of 2026-10-05 found 39 files with a search for six common exception
  types, so the figure is about right; the inventory run gives the real list.)
- Docker containers' logs are never checked for errors. One class,
  `DockerDurableRestartTest`, reads them, for particular assertions only.

**Rules.**
- **One package.** Every intentional error is an entry of the enum
  `IntentionalError` in the test package `dev.mars.qraft.testing.fault`. This
  is a hard requirement. There are two kinds.
  - An **injected failure** is thrown by a test as an `InjectedFault` naming its
    entry. `InjectedFault` is the only exception type tests use to inject a
    failure. Where production code reacts to a specific type, such as
    `IOException`, the test keeps that type and attaches an `InjectedFault` as
    its cause. An event is that failure when the `InjectedFault` is anywhere in
    its exception's cause or suppressed chain. Production code must therefore
    log the exception itself, not only its message (PROJECT_STANDARDS 6.3).
  - An **intentional error** is an ERROR that production code logs because a
    test arranged the situation, with no injected exception: a refused
    bootstrap, a corrupted WAL, a partitioned peer. Its entry names the exact
    logger, level, and a pattern for the whole message. It labels an event only
    during a test that declares it with `IntentionalErrors.expect(entry)` or
    `expect(entry, times)`.
  - Declarations are per test, not an MDC scope, because MDC does not follow
    work onto the Raft state loop, transport threads, or virtual threads.
    Tests run one at a time, so an event belongs to the running test whatever
    thread logs it.
- **Nothing is filtered, moved, or suppressed.** Every event stays in the one
  log, in order, at its original level. `logback-test.xml` only adds a label
  directly after the level. Production `logback.xml` is not changed. Both
  configurations are checked for any setting that could drop an ERROR: a
  filter or turbo filter, an include, `neverBlock`, level `OFF`,
  `additivity="false"`, or a root without an appender.
- **The label says what happened and who did it.**
  - An injected failure:
    `ERROR *** INJECTED FAILURE: WAL_SYNC_FAILURE, injected by RaftNodeLogSequencingTest#... *** dev.mars.qraft.controller.raft.RaftNode - Failed to persist command to WAL: ...`
  - An intentional error:
    `ERROR *** INTENTIONAL ERROR: PEER_PARTITIONED, caused by RaftFailureTest#... *** dev.mars.qraft.controller.raft.RaftNode - Raft peer c became unreachable ...`
- **Labels are exact and checked.** Each test runs in a window that opens
  before its `@BeforeEach` methods and closes after its `@AfterEach` methods.
  The test fails on:
  - an ERROR, or an event carrying an exception at any level, that is neither
    an injected failure nor a declared intentional error;
  - a declared entry that occurs too few or too many times.

  Events logged between test classes fail the next class to close. Every test
  also fails if the check is not attached to the root logger. In a green build,
  every ERROR line in the log carries one of the two labels.
- **A late event lands on the next test** (noted 2026-10-05). A thread that
  outlives its test logs into whichever window is open then. The later test
  fails for an error it did not cause, or the event counts towards an entry it
  declared. The failure message names the logging thread. Such a failure is a
  teardown defect in the earlier test (`PROJECT_STANDARDS.md` section 4.3), and
  is fixed there, not by declaring the entry in the later test.
- **Libraries count too** (review of 2026-10-05). The root logger is at INFO,
  and `io.grpc`, `io.netty`, and `org.apache` at WARN. An event with an
  exception from gRPC, Netty, or Testcontainers therefore fails a test like
  any other. Where a test causes one on purpose, its entry names the
  library's logger.

**State of `main` (noted 2026-10-05, from reading the code; no build was
run).** Commit `24beac3` holds the first two tasks below. From that commit the
check runs for every test, while `IntentionalError` has only its two self-test
entries and no test declares or injects through it. The default suite on
`main` is therefore expected to fail, as the inventory task says, until the
conversion task is done. This phase is committed in parts, unlike the others.

**Tasks.**

- [x] Create `dev.mars.qraft.testing.fault` (2026-10-04):
  - `IntentionalError` and `InjectedFault`;
  - `IntentionalErrors`: the windows, `expect`, the label, and the check;
  - `IntentionalErrorLabel`, the `%intentional` Logback converter;
  - `IntentionalErrorCheck`, an appender that writes nothing and shows each
    event to the check;
  - `IntentionalErrorExtension`, registered for every test through
    `META-INF/services` and `junit.jupiter.extensions.autodetection.enabled`;
  - `LogbackConfigurationAudit`.

  `src/test/resources/logback-test.xml` writes `%intentional` right after `%-5level` in
  both appenders and attaches the check to the root logger first.
- [x] Tests of the mechanism (2026-10-04):
  - `IntentionalErrorsTest`: labels and checks;
  - `LogbackConfigurationAuditTest`;
  - `IntentionalErrorConfigurationTest`: the test configuration passes the
    audit, every writing appender has the label, the check is attached, each
    test has its window, and both labels are read back from the log file;
  - `LoggingConfigurationTest#productionConfigurationCannotDropAnError`.

  Evidence so far: on JDK 27 with Logback 1.5.32 from `target/qraft.jar` and a
  stub JUnit runner, outside Maven, 22 tests pass and 19 mutants are killed.
  The mutants removed or broke: the cause chain, the suppressed chain, the
  whole-message, logger, and level matches, the check of exceptions below
  ERROR, the declared counts, the label itself, the label in the file pattern,
  the check appender, the window name, the attachment guard (two), and the
  audit's filter, `OFF`, include, `neverBlock`, additivity, and single-root
  findings. Pending: the same tests under Maven with real JUnit.

  Verified against the code (review of 2026-10-05): the seven classes exist,
  the extension is registered through `META-INF/services` and
  `junit-platform.properties`, the check is the root logger's first appender,
  and the four test classes hold 22 tests (11, 4, 5, and 2).
- [x] Make tests runnable from an IDE (2026-10-06). The test configuration is
  `src/test/resources/logback-test.xml`, so Logback discovers it from the test
  runtime classpath in Maven and an IDE. Surefire no longer passes
  `logback.configurationFile`, and there is no second copy of the configuration
  to drift. TDD evidence: the new classpath-resource test first failed alone
  (6 tests, 1 failure), then the same class passed (6 tests) after the move.
- [x] Inventory run: `mvn test` (2026-10-06). It ran all 833 default tests and
  failed as intended: 65 test methods reported 125 unlabelled throwable events
  (123 at ERROR, one at WARN, and one at DEBUG). Eight additional class-container
  failures were secondary: `RemediationTestExtension` logged those test failures
  at ERROR. The primary events were emitted by `RaftNode` (112),
  `ShutdownCoordinator` (7), `FileRaftStorage` (4), `ClusterBootstrap` (1), and
  `InMemoryTransportSimulator` (1).

  | Test class | Failing methods | Events |
  |---|---:|---:|
  | `LeaderHealthExpiryClusterTest` | 1 | 4 |
  | `HttpApiServerOperatorTest` | 1 | 3 |
  | `HttpApiServerReadinessTest` | 1 | 2 |
  | `HttpApiServerTest` | 2 | 5 |
  | `ShutdownCoordinatorTest.HookExecutionTests` | 1 | 1 |
  | `ShutdownCoordinatorTest.TimeoutHandlingTests` | 4 | 6 |
  | `ClusterBootstrapTest` | 1 | 1 |
  | `EnhancedInMemoryTransportTest` | 3 | 15 |
  | `GrpcRaftIntegrationTest` | 3 | 4 |
  | `InMemoryTransportSimulatorTest` | 1 | 1 |
  | `InstallSnapshotTest` | 3 | 6 |
  | `MembershipServiceTest` | 3 | 4 |
  | `RaftFailureTest` | 3 | 4 |
  | `RaftNodeApplyFailureTest` | 2 | 4 |
  | `RaftNodeConfigurationChangeTest` | 3 | 5 |
  | `RaftNodeConfigurationTest` | 2 | 2 |
  | `RaftNodeInstalledSnapshotRealRecoveryTest` | 1 | 2 |
  | `RaftNodeInstalledSnapshotSequencingTest` | 4 | 7 |
  | `RaftNodeLogSequencingTest` | 7 | 11 |
  | `RaftNodeMembershipTest` | 5 | 9 |
  | `RaftNodeModelTest` | 1 | 8 |
  | `RaftNodeRealStorageRecoveryTest` | 1 | 3 |
  | `RaftNodeServerIdentityTest` | 1 | 2 |
  | `RaftNodeShutdownSequencingTest` | 3 | 3 |
  | `RaftNodeSnapshotSequencingTest` | 3 | 5 |
  | `RaftNodeTest` | 3 | 6 |
  | `RaftLogStorageIntegrationTest` | 2 | 2 |

  One separate failure was not from the check:
  `RaftNodeOutboundSnapshotGenerationTest#staleSnapshotFailureCannotCancelCurrentLeadershipTransfer`
  expected next index 3 but observed 4. It passed immediately when run alone. That
  is an intermittent defect under `PROJECT_STANDARDS.md` section 4.4 and must be
  made deterministic during the conversion, not hidden as an intentional error.
- [x] Convert every injected fault to `InjectedFault`, and declare every
  intentional error with `expect`. Fix the cause of any ERROR that is neither,
  and any production code that logs a failure without its exception. Replace
  `logExpectedFailure` and `@RemediationTest`.

  Completed (2026-10-06): all 125 events from the inventory's 65 test methods
  are converted. Injected transport, shutdown, persistence, snapshot,
  state-machine, and RPC failures carry `InjectedFault`; tests declare the
  expected bootstrap, WAL, shutdown, configuration, peer, committed-entry,
  fencing, queue-saturation, recovery, and startup-draining errors. Required
  outer exception types are preserved with `InjectedFault` attached as a
  cause. The legacy `RemediationTest`, `RemediationTestExtension`,
  `logExpectedFailure`, and `[EXPECTED-TEST-FAILURE]` mechanism is removed.

  TDD checkpoints found and corrected over-declarations and timing variants,
  including startup draining and asynchronous fencing. The inventory's
  intermittent
  `RaftNodeOutboundSnapshotGenerationTest#staleSnapshotFailureCannotCancelCurrentLeadershipTransfer`
  assertion was made deterministic: acknowledgement must advance `nextIndex`
  at least past the snapshot boundary, while subsequent replication may move
  it farther. The focused regression test passed, followed by the complete
  default suite: 825 tests, no failures or errors. Its log
  (`qraft-maven-tests-2026-10-06_13-12-03.log`) contains 126 labelled events
  (the timing-dependent peer count can exceed the inventory run), 124 ERROR
  lines, and zero unlabelled ERROR lines.
- [x] Docker suite:
  - collect each container's log under `logs/docker/<class>/`;
  - every ERROR in it must match an `IntentionalError` the class declares, and
    the test log reprints each one with its label;
  - any other ERROR fails the class.

  Completed (2026-10-06): the auto-registered Docker extension captures each
  compose service and detached helper before removal, archives each class's
  incremental output, rejects unparseable and undeclared ERROR lines, and
  reprints recognised events through the intentional-error labeller. The first
  complete Docker run was RED for snapshot-transfer interruption, controller
  recovery fencing, and cross-process WAL locking; focused contracts were added
  before their narrow catalogue entries and class declarations. The three
  formerly failing classes then passed 16 tests. After restarting Docker Desktop
  once when its daemon returned HTTP 503 and stopped, the complete Docker suite
  passed 24 tests with no failures or errors. The final default regression suite
  passed 830 tests with no failures or errors.
- [x] Child JVMs, by the same rule as containers (review of 2026-10-05). Five
  test classes start a Java process whose output goes to a file in a
  temporary directory, where no window sees it and the test log does not
  hold it:
  - `RaftNodeRealStorageRecoveryTest`, `RaftNodeRealSnapshotRecoveryTest`,
    and `RaftNodeInstalledSnapshotRealRecoveryTest`, which run crash writers;
  - `RaftStorageProcessLockTest`;
  - `CrashedAgentExpiryEndToEndTest`, which runs a whole client.

  Read each child's output when it ends. Every ERROR in it must match an entry
  the test declares, and is reprinted in the test log with its label.

  State (review of 2026-10-08, from the code at `9a4adf0`). The phase was
  accepted with this task open, and the code does something different from
  the text above.
  - A child that logs an error on purpose declares it in its own window, with
    `IntentionalErrorsHelper.inSubprocess`. `RaftStorageProcessLockTest` does
    so through `DirectoryLockProcessFixture`.
  - The children of the other four classes declare nothing. They run with
    the test Logback configuration, so what they log reaches a retained
    `logs/qraft-maven-tests-<timestamp>.log` without a flag, where the review
    of retained logs finds it. Their console output is not checked: an ERROR
    in one fails no test.
  - `inSubprocess` cannot close that gap. A crash writer halts at its
    checkpoint, and the client of `CrashedAgentExpiryEndToEndTest` is killed,
    so neither returns through the audit.

  Done 2026-10-09. `SubprocessOutputAuditHelper.requireNoErrors`
  gives the parent test the check. It fails the calling test for an ERROR
  line, a Logback status error, or an uncaught exception in a finished
  child's console output, and quotes each error with its stack trace. A child
  that has to log an error on purpose gets declared entries when the first
  one exists; none does today.
  - RED, 2026-10-09. `SubprocessOutputAuditHelperTest` against an empty
    method ran 7 tests with 6 failures, each "Expected
    java.lang.AssertionError to be thrown, but nothing was thrown"
    (`logs/qraft-subprocess-audit-red-2026-10-09_10-55-15-256.log`). The
    seventh, that ordinary output passes, guards against an audit that is
    too strict. The run's application log is empty.
  - Written after RED:
    - the audit;
    - an eighth test, that an error keeps its stack trace. It has not been
      seen to fail;
    - the calls from the three crash-writer tests, when their writer exits,
      and from `CrashedAgentExpiryEndToEndTest`, in its teardown;
    - `docs/TESTING.md`, "Helper subprocesses".
  - GREEN, 2026-10-09. The five classes ran 31 tests with no failures
    (`logs/qraft-subprocess-audit-green-2026-10-09_11-07-33-634.log`). The
    Surefire reports agree: 8, 7, 7, 7, and 2. The run wrote ten logs, and
    all ten were read:
    - the Maven capture and the application log
      `qraft-maven-tests-2026-10-09_11-07-42.log` each hold five flagged
      ERROR headers with seven exception headers under them, and no
      unflagged error or exception;
    - the six crash writers' own logs hold INFO lines only;
    - the two killed clients' own logs are empty. A healthy client logs
      nothing: its six logging statements are warnings on failure paths.
  - `mvn install`, 2026-10-09: 884 tests with 1 failure, in a test this
    change does not touch (`logs/qraft-tests-2026-10-09_11-19-14-080.log`).
    The run's 19 retained files hold no unflagged error apart from that
    test's report.
    - **Failure.**
      `GrpcRaftIntegrationTest#aNewElectionAfterTheLeaderStopsAdvancesTheTerm`:
      `RAFT_PEER_UNREACHABLE` "was declared at least 1 time(s) but occurred
      0 time(s)".
    - **Cause.** The test declared the error and did not wait for it. The
      requests to the stopped node1 fail asynchronously. The test ran first
      in its class, in a cold JVM. Its assertions held within 7 ms of node2
      becoming leader, and teardown stopped node2 3 ms later, before either
      failure had been reported. Stopping node2 discarded them.
    - **Production.** No race there: a stopped node drops the replies to its
      outstanding requests by design.
    - **Fix, in the test.** It declares node1 alone, and waits for "Failed to
      retrieve vote from node1" before it ends. The vote request's callback
      logs that error whatever node2 has become, so a test that ended before
      it could also have had it logged during the next test.
    - **Same pattern.** `aMajorityKeepsCommittingAfterAFollowerStops` also
      declared its error without waiting. It passed because the failure
      arrived 1 ms after node3 stopped. It now declares node3 alone and
      waits. The join test, which Phase 3 made wait, now uses the same
      `RaftNodeLogFixture` and `awaitError`; its condition is unchanged.
    - **Not reviewed.** The other declarations of this error run on the
      in-memory transport with manual timers. They passed in this run, and
      were not read one by one for the same pattern.
  - Verified, 2026-10-09:
    - `GrpcRaftIntegrationTest` passed five consecutive runs, each in a new
      JVM, 10 of 10 every time
      (`logs/qraft-grpc-repeat-2026-10-09_11-34-47-259.log`);
    - `mvn install` then passed: 884 tests, no failures, every coverage gate
      met (`logs/qraft-tests-2026-10-09_11-36-38-210.log`). The 884 are
      Phase 3's 876 and the 8 of `SubprocessOutputAuditHelperTest`;
    - the two runs wrote 26 files. All were read: 295 ERROR headers, every
      one flagged, and no unflagged error or uncaught exception.

    The Docker, end-to-end, and slow suites were not run: the change is in
    test code only. `CrashedAgentExpiryEndToEndTest`, the one tagged class
    it touches, ran in the GREEN run above.
  - Mutations, 2026-10-09, in an isolated copy of the committed code
    (`5a00975`) with five faults: each of the three crash writers logs an
    ERROR, the client logs an ERROR at launch, and the audit drops stack
    traces. The five classes ran 31 tests with 17 failures, the 17 predicted
    (`logs/qraft-subprocess-audit-mutants-2026-10-09_16-36-45-389.log`):
    - 3, 7, and 4 in the three recovery classes, which is every test that
      launches a crash writer;
    - both tests of the class that kills its client;
    - the stack-trace test of `SubprocessOutputAuditHelperTest`.

    The first sixteen failed with the audit's own message, naming the helper
    JVM and quoting the planted line. The 14 tests that launch no helper JVM
    passed. So the audit is called wherever a helper JVM is launched, and
    the eighth test fails when stack traces are dropped.
- [x] After each suite, check its log file: every ERROR line carries a label.
  This also covers anything logged after the last test class closed, which no
  window sees.

  Done as a required review, not as a build step (recorded by the review of
  2026-10-08). `AGENTS.md` and `docs/TESTING.md`, "What makes verification
  fail", require every retained Maven, application, subprocess, and Docker
  log of a run to be read for unflagged errors before the run is accepted.
  The acceptance run of 2026-10-08 did so for 80 files. Nothing in the build
  performs this check.
- [x] Document the rules and how to read a test log in `PROJECT_STANDARDS.md`
  section 4.3 and `docs/TESTING.md`.

  Done: `docs/TESTING.md`, "Intentional error flags and log auditing", with
  this phase, and `PROJECT_STANDARDS.md` section 4.3 on 2026-10-08.

**Exit:** Default, end-to-end, and Docker suites green. Every ERROR line in
their logs carries an `INJECTED FAILURE` or `INTENTIONAL ERROR` label naming
its entry and test. That includes the lines reprinted from containers and
child JVMs. A test class runs from an IDE as it does from Maven.

### Phase 3. Package layout follows the modes

**Whole-phase TDD task, authorized 2026-10-08.** Complete the following slices continuously, fixing
defects found during implementation or verification. For each slice, add a meaningful failing
architecture or behavioral regression, observe RED in the visible terminal, implement the change,
observe GREEN, and then refactor. Keep the existing legacy WAL/snapshot fixtures immutable.

1. [x] Support ownership: reject Raft dependencies on server-owned async/observability code; move the
   six shared async types and their tests to `common.async`, and move Raft metrics into Raft.
2. [x] Package boundaries: enforce the final `common`, `client`, `server`, `raft`, `state`, and runtime
   layers with compiled mutation fixtures, including Raft and state host dependencies on HTTP;
   migrate all production/test packages and callers and remove mixed agent/catalog ownership.
3. [x] Generated protocol packages: prove the generated classes have their new Java packages while
   the gRPC service names and existing command/snapshot bytes remain compatible; change only
   `java_package` options, preserving `qraft.raft`, `qraft.api`, field numbers, and reserved fields.
4. [x] Runtime names: prove server/client file logging and server telemetry defaults; rename server
   application/service classes, client endpoint helper classes, server thread names, and default
   configuration resources. Preserve the client's JSON `controllers` object and the `agent` object.
5. [x] Deployment names: update Compose services/config references, launchers, dashboards, and
   collector/scrape references together, with deployment contract tests before the changes.
6. [x] Whole-phase verification: pass the clean default build and coverage gates, repeat changed
   concurrency/lifecycle tests, run fresh-image Docker/e2e/slow suites, inspect artifact ownership and
   protocol descriptors, and audit every retained log for unflagged errors or exceptions. Record
   evidence and update current documentation. Generated logs remain ignored build artifacts.

**Records, in the order the work was done.** The review of 2026-10-08 put them in this order; their
text is unchanged. The totals in the first two records, 885 and 894 tests, are those of their own
runs. The final total is the 908 of "Whole Phase 3".

**First slice, completed 2026-10-08: break the Raft/state cycle.** The user approved proceeding with the
direction below: state may depend on Raft; Raft must not depend on state.

- RED preparation: `PackageDependencyTest` now checks the compiled Raft implementation against state,
  catalog, and distributed-state dependencies, independently of the old module classification. Bytecode
  fixtures exercise rejection of the reverse edge and acceptance of the intended direction in both layouts.
- RED confirmed before production changes: eight architecture tests ran, with exactly one failure listing
  seven compiled Raft-to-state references (including `RaftNode$Builder`). Retained output:
  `logs/qraft-phase3-red-2026-10-08_15-22-19-848.log`.
- GREEN: moved `RaftCommand`, `RaftCommandResult`, and `ConfigurationCommand` into
  `dev.mars.qraft.controller.raft` and updated production callers, implementations, codecs, and tests.
  The focused clean run passed all 53 tests, including all eight architecture tests, configuration replication,
  command codecs, immutable legacy command/snapshot fixtures, and replica determinism. Retained output:
  `logs/qraft-phase3-green-2026-10-08_15-25-14-313.log`; its seven ERROR headers were explicitly intentional.
- Refactored imports after GREEN. Verification exposed the following failures, investigated and remediated
  under the mandatory logging policy:
  - Health propagation teardown closed another follower while the new leader was still running. The two
    unflagged peer failures are retained in `logs/qraft-phase3-tagged-2026-10-08_15-29-15-403.log` and its
    application log `qraft-maven-tests-2026-10-08_15-29-33.log`. Teardown now declares only each peer it is about
    to close. Four test-first logging regressions prove these optional cleanup declarations cannot satisfy
    a required leader-loss count, accept other peers/signatures or earlier errors, or leak into another test.
    The focused cleanup run passed 23 tests (`qraft-cleanup-green-2026-10-08_15-43-42-822.log`).
  - The late-replication shutdown test raced initial leadership. Its setup now waits for the durable no-op,
    and its transport gate waits for the actual command at index 3. It acknowledges that command after stop.
  - The forwarded-join test sometimes ended before its deliberately unreachable learner failed an RPC,
    leaving its required error count unmet. It now declares only `node4`, fires a heartbeat, and waits for
    that exact error through a thread-safe log observer. The required occurrence is preserved.
  - A second no-op/draining failure exposed the production cause: `becomeLeader` published `LEADER` and
    notified listeners before queuing its initial transition. A deterministic test that requests stop from
    the leader notification reproduced the undeclared error before the production fix
    (`qraft-leadership-red-2026-10-08_16-00-14-790.log`). Raft now queues the no-op and initializes replication
    state before publishing leadership. All 44 focused architecture, metadata, shutdown, gRPC, and configuration
    tests passed (`qraft-leadership-green-2026-10-08_16-01-32-039.log`). Genuine failures keep their ERROR logging.
- Final verification: `mvn clean install` passed 854 default tests and every coverage gate; the fresh-jar
  `docker,e2e,slow` run passed all 31 tagged tests. All 885 tests passed with no failures, errors, or skips.
  The same 126 default test classes remain; the count increased only by four architecture regressions,
  four cleanup-declaration regressions, and the deterministic leader-notification/shutdown regression.
  Retained successful output:
  - `logs/qraft-phase3-final-clean-2026-10-08_16-03-01-464.log`;
  - `logs/qraft-phase3-final-tagged-2026-10-08_16-04-37-245.log`.
  Read all 80 logs written by this final verification window, including application and child-JVM logs and
  Docker archives: 581 flagged ERROR headers, 357 exception headers under flagged events, one flagged
  uncaught rethrow, and zero unflagged errors or exceptions. Counts include duplicate captures. Earlier
  failed and deliberately RED runs remain as diagnosis evidence; the defects above are remediated, and
  those failed runs are not counted as successful verification. The jar contains the seven command/result
  class files only under Raft. Legacy command/snapshot fixtures and replica determinism remain green.
  Async-layer placement, Raft metrics ownership, and the final package names follow as subsequent slices.

**Review defect follow-up, verified 2026-10-08.** The original final-run results above remain
historical evidence, rather than verification of these later changes. The focused RED run
(`logs/qraft-review-red-2026-10-08_16-32-47-340.log` and application mirror
`logs/qraft-maven-tests-2026-10-08_16-33-16.log`) compiled successfully and ran 51 tests with eight failures:
one foreign-thread stop/election race, three cleanup-count isolation defects, and four Docker rethrow
audit defects. The application mirror contains five ERROR events: four explicitly intentional and one
unflagged leadership no-op/draining failure, reproducing the production defect fixed below.

The implementation now serializes leadership admission with the stop-request lock, declining leadership
when stop has already won; listeners remain outside that lock and genuine failures retain ERROR logging.
Cleanup peer failures do not satisfy or increase any functional expectation. Docker audits discover ERROR
events before matching rethrows, retain recognised context only for the same source, require the current
declaration, and detect prefixed uncaught headers without changing message or stack text. The remaining
inline shutdown-test wait now requires the durable no-op and checks that the blocked sync belongs to
the active command. Forwarded-join polling fires further manual heartbeats. Partial bind-retry cleanup
shares the final-teardown close path, declares only a peer being closed while another live peer remains,
and removes closed runtimes from both registries.

GREEN verification ran in a visible PowerShell terminal at the user's explicit request, with the
documented Maven commands and timestamped `Tee-Object` captures; no script file was created:

- Focused suite: all 75 tests passed, including the eight regressions that failed in RED and the
  existing health propagation tests (`qraft-review-green-2026-10-08_17-32-59-491.log`).
- Concurrency tests: both additional runs passed all 29 metadata, shutdown, and gRPC tests, so each
  ran successfully three times (`qraft-review-repeat1-2026-10-08_17-34-01-103.log` and
  `qraft-review-repeat2-2026-10-08_17-35-15-193.log`).
- `mvn clean install`: all 863 default tests passed and all coverage checks were met
  (`qraft-review-default-2026-10-08_17-35-33-237.log`).
- Fresh-jar `docker,e2e,slow` suite: all 31 tagged tests passed
  (`qraft-review-tagged-2026-10-08_17-37-10-226.log`).

The final Surefire reports independently total 894 tests in 136 classes, with zero failures, errors,
or skips. Audited all 86 logs written during this successful verification window, including application
and subprocess mirrors and Docker archives: 567 flagged ERROR headers, 377 exception headers belonging
to flagged events, one flagged uncaught rethrow, and zero unflagged errors or exceptions. Counts include
duplicate captures and are evidence of this run, not fixed expectations. The earlier RED logs remain
diagnostic evidence and are excluded from the successful verification window.

**Support and package slices, verified 2026-10-08.** RED rejected Raft's server-owned async/metrics
dependencies; the focused GREEN run passed 40 tests and its two retained logs contained eight flagged
ERROR headers and eight attributed exception headers, with no unflagged errors. Six async types now
live in `common.async`; metrics live in `raft.metrics`.

The final package/protocol RED run executed 12 tests with three expected failures: the old production
packages and the two old generated Java packages. GREEN passed 54 architecture, async, codec,
determinism, and immutable legacy fixture tests. The compiled mutation fixtures reject both Raft and
state dependencies on server HTTP, Raft dependencies on state, client dependencies on server/Raft/state,
common dependencies on application code, and application dependencies on the runtime entry point.
Only protobuf Java packages changed; `qraft.raft` and `qraft.api` service names and fixture hashes
passed unchanged. The two GREEN logs contained no ERROR or exception headers.
Captures: `qraft-phase3-support-green-2026-10-08_18-44-08-646.log`,
`qraft-phase3-packages-red-2026-10-08_18-48-33-998.log`, and
`qraft-phase3-packages-green-2026-10-08_18-50-38-615.log`.

**Runtime/deployment slices, verified 2026-10-08.** Fresh-JVM RED reproduced the old telemetry name
and missing configured mode files. Startup now selects `qraft.log.mode` / `qraft.log.dir` before
logger initialization, and text/JSON files use `qraft-server` or `qraft-client` with compressed
archives under `archive/`. Server application/service and client endpoint helper classes, thread
names, default resource, Compose services/DNS/config files, dashboards, scrapes, and existing
launchers now use server names. The client's `agent` / `controllers` JSON keys and public paths
remain unchanged.

Verification caught and corrected a client-key rename mistake, dynamic fixture service strings,
a PowerShell dispatcher/help mismatch, and acceptance-test configuration/property leakage into
later tests. The 83-test focused verification passed after those corrections and its seven retained
logs contained no ERROR or exception headers.

The clean default run then passed 873 tests but exposed the retained 60% coverage gate for the
new server-observability package: moving Raft metrics revealed previously untested telemetry.
Real enabled/disabled SDK lifecycle tests now cover it. RED also reproduced a trace-exporter worker
leak after Prometheus bind failure. Failed initialization now releases already-opened trace/metrics
resources and preserves the primary failure; a regression also checks failed global SDK registration.
Compose validation reproduced an inherited undeclared Fluentd volume; its declaration is restored.
Final focused GREEN passed 34 tests, including real process logging, archive paths, every Compose
model, launcher dispatch, and telemetry resource cleanup. Its seven retained logs contain no ERROR
or exception headers. Captures: `qraft-phase3-telemetry-red-2026-10-08_19-09-01-429.log` and
`qraft-phase3-final-fixes-green-2026-10-08_19-10-39-914.log`.

**Docker logger migration follow-up, 2026-10-08.** The first complete acceptance run exercised all
32 intended cases, but Docker auditing failed the durable-restart class on three undeclared headers.
Its corruption and lock-contention cases were already explicitly declared; the two source signatures
still used the old abbreviated controller logger. Updated audit fixtures to the actual emitted
`dev.mars.qraft.server.QraftServerService` logger and observed eight failures among 20 tests in RED
(`qraft-phase3-docker-logger-red-2026-10-08_19-26-01-636.log`). The two definitions now derive the
exact source from `QraftServerService.class.getName()`, retaining their complete message patterns,
ERROR severity, and uncaught-rethrow matching. No expectation was broadened. GREEN passed all 44
Docker audit, attribution, and extension integration tests
(`qraft-phase3-docker-logger-green-2026-10-08_19-27-20-667.log`).
The failed acceptance capture `qraft-phase3-complete-tagged-2026-10-08_19-16-09-623.log` and its
Docker/application logs remain diagnostic evidence; they do not count as successful verification.
After this correction, the complete default suite and fresh-image acceptance suite both passed.

**Whole Phase 3, verified complete 2026-10-08.** All six TDD slices and the original tasks below are
complete. Final evidence is retained under `logs/`:

- Clean `mvn install`: 876 default tests and all coverage gates passed
  (`qraft-phase3-complete-default-2026-10-08_19-12-46-697.log`).
- Lifecycle/concurrency repeat: 47 tests passed
  (`qraft-phase3-complete-repeat-2026-10-08_19-15-23-119.log`).
- After the Docker logger correction, `mvn install` again passed all 876 default tests and coverage gates
  (`qraft-phase3-verified-default-2026-10-08_19-28-47-852.log`).
- The fresh-image `docker,e2e,slow` suite passed all 32 tests, including all nine durable-restart cases
  (`qraft-phase3-verified-tagged-2026-10-08_19-30-33-972.log`).

Copied actual Surefire XML into `logs/phase3-final-evidence/default/` and `tagged/` before later suites
could overwrite shared report names. These 140 reports independently total 908 tests in 139 distinct
classes, with zero failures, errors, or skips. Comparing per-class counts with the immediately preceding
successful 894-test run shows 14 additional cases; the former Raft-metrics telemetry case moved to
`TelemetryConfigTest`, and all other functional class counts are retained after class renames. This
comparison uses the retained 2026-10-08 captures, not the unavailable original Phase 0 baseline files.

The successful final window contains 87 retained Maven, application, subprocess, and Docker log files:
449 flagged ERROR headers, 301 exception headers attributed to flagged events, one flagged uncaught
rethrow, and zero unflagged errors or exceptions. Counts include duplicate captures and are evidence of
this run, not fixed expectations. Separately parsed both fresh-JVM JSON logs: 54 events, no ERROR or
stack-trace events. Earlier failed/RED captures remain diagnostic evidence outside this successful window.

The packaged jar contains 438 Qraft classes and no old package trees or test-only logging/fixture
entries. Only protobuf `java_package` options changed; wire packages, service names, field numbers,
and immutable catalog fixture bytes remain unchanged. Documentation now describes the final ownership,
mode-specific startup logging, telemetry cleanup, and deployment names.

- [x] `dev.mars.qraft.agent` (client code) becomes `dev.mars.qraft.client`.
- [x] `dev.mars.qraft.controller` becomes `dev.mars.qraft.server`. Rename
  "controller" in class names, log file names, and the default telemetry
  service name. Name the log files by mode: client mode currently writes
  `qraft-controller-server.log`.
- [x] The Raft engine (`raft.api` and `controller.raft`) moves to `dev.mars.qraft.raft`.
- [x] Replicated state (`distributedstate`, `catalog`, and `controller.state`)
  moves to `dev.mars.qraft.state`.
- [x] Shared types (`Deadlines`, configuration resolver, `ServiceDefinition`,
  node types) move to `dev.mars.qraft.common`. The `catalog` and `agent`
  packages that today span two modules are gone.

**Added by the review of 2026-10-05.** This phase is not only a rename: as the
code stands, `raft` and `state` cannot be separate layers. The tasks below
come first.

- [x] Break the cycle between Raft and state, and fix the direction between
  them.
  - Before this slice, `controller.raft` imported three types from `controller.state`:
    `RaftCommand`, `RaftCommandResult`, and `ConfigurationCommand` (six
    imports).
  - `controller.state` imports from `controller.raft`: `RaftConfiguration`,
    `RaftConfigurationCodec`, `RaftLogApplicator`, and every generated command
    message in `controller.raft.grpc`.
  - Implemented: state uses Raft, and Raft never uses state. The command base type, its result, and
    Raft's configuration entry now live in the final `dev.mars.qraft.raft` package.
- [x] Give the async layer a home until Phase 7 removes it.
  `controller.runtime` is imported by nine files of `controller.raft`, by
  `ShutdownCoordinator`, and by the two classes of `controller`. As
  `server.runtime` it would make `raft` depend on `server`. It moves with
  `raft` or to `common`.
- [x] Remove Raft's one dependency on server observability: `controller.raft`
  imports `RaftMetrics`. Move it into `raft`, or pass it in behind an
  interface.
- [x] Change the generated code's packages without changing the wire. The
  protos' `java_package` options are `dev.mars.qraft.controller.raft.grpc`
  and `dev.mars.qraft.controller.api.grpc`, and follow the rename. Their
  `package` lines, `qraft.raft` and `qraft.api`, are part of the gRPC service
  names on the wire and stay as they are.
- [x] Decide which other "controller" names this phase renames. The second
  task above lists class names, log file names, and the telemetry service
  name. Not listed, and found in the code:
  - the client configuration's `controllers` object, which is a configuration
    contract, so renaming it changes behaviour. It was kept here. The client
    list's decision 10 renames it to `servers`, in its Phase 2 (decided
    2026-10-09);
  - thread names: `qraft-controller-raft` and `qraft-controller-release`;
  - client classes: `ControllerEndpoints`, `ControllerRetryPolicy`, and
    `ControllerContactTracker`;
  - Docker names: the compose files `docker-compose-single-controller.yml`
    and `docker-compose-controller-first.yml`, the services `controller1` to
    `controller5`, and the Grafana dashboard `qraft-controller.json` with its
    job `qraft-controllers-compose`.

No stored format holds a Java class or package name: production code has no
Java serialization and no polymorphic JSON type names, so the rename cannot
break a WAL or a snapshot. The existing catalog fixtures prove it for catalog
commands when they pass after the rename.

**Exit:** Same tests as Phase 2A, changed only where they name a package, a
log file, or the telemetry service. The dependency test uses the new packages
and has `raft`, `state`, and `server` as separate layers, so that "Raft and
state never import the HTTP layer" is enforced for the Raft implementation
and the state host too, with a mutation to show it.

**Exit as met (review of 2026-10-08).** The second sentence holds: the
compiled mutation fixtures of the package slice show it. The first holds only
in part.
- The suite grew by the regression tests of the defects that verification
  found, and those defects changed production behaviour (section 3).
- The tests were compared by per-class counts with the run before, not by
  name with the Phase 0 baseline, which that working tree did not have.

- [ ] Compare the test names of Phase 3's final run with the Phase 0 baseline
  (added by the review of 2026-10-08). The Surefire reports are in
  `logs/phase3-final-evidence/`, in the working tree that ran Phase 3. The
  baseline is now `docs/archive/refactor-baseline-2026-10-04/testcases.txt`.
  Account for each name that is missing, added, or renamed since Phase 0, as
  the Phase 1 and Phase 2 records do.

### Phase 3A. One word for each mode

**Added 2026-10-09, at the user's direction.** Qraft has two modes, server
mode and client mode. Two older words for them survived Phase 3: `agent` for
client mode and `controller` for server mode. This phase replaces both with
`client` and `server`, everywhere.

**Decisions (2026-10-09).**
- Both words go, in every letter case: class, method, field, and variable
  names; protobuf names; configuration keys; HTTP routes, JSON fields, and
  error codes; thread names and log keys; Docker files and service names;
  and the documents.
- The client's configuration objects are `client` and `servers`. This
  replaces decision 3's sentence that the object keeps its name, and carries
  out the client list's decision 10 here.
- The names planned after Consul's change too: the client's local API is
  `/v1/client/*`, and the ACL list's resource for it is `client`.
- Snapshots written before the rename stay readable (decision 4). The reader
  accepts the two old key names and writes the new ones. The legacy fixtures
  keep their bytes.
- It is a rename. It is verified by a word search and the existing suites; no
  test was added for it.

**Tasks.**

- [x] Replace both words in `src/`, `docker/`, `pom.xml`, and the
  `Jenkinsfile`: 3,115 occurrences in 125 files, and 33 files and folders
  renamed. `AgentRegistrationClient` became `RegistrationClient`, which
  would otherwise have repeated the word.
- [x] Keep old snapshots readable. `QraftStateStore` reads the old keys
  `agents` and `agentId` as `clients` and `clientId`. Unknown properties are
  ignored on restore, so without this an old snapshot would have restored
  with its registered clients silently missing. `LegacyCatalogFixtureTest`
  covers it.
- [x] Give distinct names to the variables that the replacement made
  collide, in four test classes: `RegistrationClientTest`,
  `CrashedClientExpiryEndToEndTest`, `HealthPropagationEndToEndTest`, and
  `UnifiedRuntimeEndToEndTest`. Production code had none.
- [x] Update the documents that describe Qraft as it is: the design, the
  README files, the standards, `TESTING.md` outside its log extracts, the
  administrative interface's design and plan, the Consul plan, the feature
  validation, and the forward-looking parts of the five task lists.
- [x] Verify: `mvn clean install`; then the Docker, end-to-end, and slow
  suites on a fresh image, because the configuration, the routes, and the
  compose files changed; then the review of the logs those runs retain.
  Done 2026-10-09: see "Verified" below.

**First build (2026-10-09).** `mvn clean install` compiled without an error
and ran 884 tests with 4 failures
(`logs/qraft-tests-2026-10-09_12-24-59-246.log`). The run's 21 retained files
hold no unflagged error.
- **Cause.** Three existing tests assert that an old name is absent: an old
  package is not a layer (`PackageDependencyTest`), deployment files and
  Docker fixtures do not name the old service (`DockerDeploymentContractTest`,
  two tests), and no log file carries the old name (`RuntimeLoggingTest`).
  The replacement turned the name they forbid into `server`, which is
  present everywhere.
- **Fix.** Those assertions have their committed text again: 15 lines in the
  three classes. They are the one place where the old words belong, because
  they are what the tests forbid.

**Second build (2026-10-09).** `mvn install` passed: 884 tests, no failures,
every coverage gate met (`logs/qraft-tests-2026-10-09_12-40-59-738.log`). The
run wrote 21 files, and all were read: 255 ERROR headers, every one flagged,
no unflagged error or uncaught exception, and neither old word in anything
the running code logged.

**Tagged suites, first attempt (2026-10-09).** 31 tests ran with 22 errors
(`logs/qraft-tests-2026-10-09_13-06-42-215.log`). It does not count as
verification.
- **Cause.** The Docker daemon was not running. Every one of the 22 is a
  Docker test, and each failed building the image: "failed to connect to
  the docker API at npipe:////./pipe/dockerDesktopLinuxEngine". None reached
  Qraft's code.
- **What did run.** The 9 tests that need no Docker passed, among them the
  end-to-end classes that start real server and client processes with the
  renamed configuration. The application logs of the run hold 11 ERROR
  events, all flagged.
- **Found in its output.** Five test methods began `anClient`, an article
  the replacement left wrong. They now begin `aClient`.

**Verified (2026-10-09),** with Docker running and after the five method
names changed.
- The Docker, end-to-end, and slow suites passed all 32 tests, three times
  (`logs/qraft-tests-2026-10-09_13-24-04-147.log`,
  `…_13-35-10-295.log`, and `…_14-34-39-151.log`). The containers ran the
  image built from the renamed jar, with the renamed configuration and
  compose files.
- `mvn install` passed 884 tests with every coverage gate met, twice
  (`…_13-51-11-615.log` and `…_14-52-07-087.log`).
- The five runs wrote 236 files: Maven captures, application and helper-JVM
  logs, fresh-JVM runtime logs, and Docker archives. All were read. They
  hold 894 ERROR headers, every one flagged; one uncaught rethrow, flagged;
  and no unflagged error or exception. Counts include duplicate captures.
- Neither old word appears in anything the running code or the containers
  logged.

**Word search (2026-10-09).** In `src/`, `docker/`, `pom.xml`, and the
`Jenkinsfile`, 21 lines still hold either word, each on purpose:
- the JaCoCo goal `prepare-agent`, twice, in `pom.xml`;
- the `User-Agent` HTTP header in `docker/test-data/nginx.conf`;
- the two old snapshot key names in `QraftStateStore`;
- the node name `agent-legacy`, which is data inside the immutable snapshot
  fixture, in `LegacyCatalogFixtureTest`;
- the 15 lines above, in tests that assert an old name is absent.

**Corrected 2026-10-10: the sweep had also renamed three words that belong
to other tools.** The search above looked for what the sweep had missed, not
for what it should have left alone.
- The `agent { label 'linux' }` directive of the `Jenkinsfile` had become
  `client { ... }`. Jenkins cannot read a pipeline without that section, so
  the job could not have run. No build was started between the sweep and
  2026-10-10, which is why nothing showed it.
- nginx's `$http_user_agent` had become `$http_user_client` in
  `docker/compose/nginx/nginx.conf` and `docker/test-data/nginx.conf`. nginx
  still starts with it, and logs "-" where the user agent belongs.
- All three were restored (`ce5890d`). The lines the sweep changed in every
  file that is not Java or Markdown were then read again: these are the only
  three of their kind.

The fixtures under `src/test/resources/fixtures/catalog/` were not touched.
In the documents the words remain only in dated records, `docs/archive/`,
the log extracts of `TESTING.md`, former module and class names given as
history, and descriptions of Consul's own software.

**What changes for someone using Qraft.**
- The client's configuration: `client` and `servers`, where the file had the
  two old words.
- The routes: `/api/v1/clients*`, `/v1/client/service/*`, and
  `/v1/client/check/observe`. They are interim: Phase 4 and the client list
  move them to `/v1/catalog/*`.
- JSON fields and error codes, for example `clientId` and
  `client_not_found`.
- Protobuf message, field, and enum value names. The field numbers are
  unchanged, so existing WAL entries decode as before.
- A snapshot written after the rename uses the new key names. A server from
  before the rename cannot restore it.

### Phase 4. Node model and API

- [x] First, write the legacy fixtures from the code as it stands (review of
  2026-10-05). Only four catalog fixtures and their manifest exist, under
  `src/test/resources/fixtures/catalog/`. There is none for a node command, an
  `UpdateCapabilities` command, a job-system status, or a snapshot holding
  nodes. Once the old model is removed, nothing can write the old format, so
  the fixtures of decision 4 are generated before any other task here, and
  committed with a manifest like the catalog's.

  Done 2026-10-09. Eleven fixtures, under
  `src/test/resources/fixtures/node/`:
  - eight node commands, from the production codec: a registration with
    every field set, a minimal one, a deregistration, a status update, a
    capabilities update, a heartbeat with a status, a sequence, and a
    registration identifier, a plain heartbeat, and an expiry;
  - two status updates holding the job system's four statuses. The codec
    never writes those, so they are built from the generated protocol
    classes;
  - one snapshot from `QraftStateStore.takeSnapshot`, holding a fully
    described node that has had a heartbeat, and a minimal node.

  `LegacyNodeFixtureTest`, seven tests, decodes and restores them with exact
  fields and checks their digests. `MANIFEST.md` beside them records the
  digests and how they were made. They were written once, by
  `LegacyNodeFixtureWriterFixture`, which refused to replace a file and was
  deleted afterwards. `.gitattributes` now marks the fixtures' `.bin` files
  as binary, so that no checkout can convert a line-feed byte inside one.

  Two limits, known and not papered over:
  - the snapshot uses today's key names. The key names from before
    Phase 3A are covered by the catalog snapshot fixture, whose node has no
    capabilities;
  - in a snapshot, only the job system's `active` status has a fixture, the
    catalog's. Today's code cannot write the other three into a snapshot,
    and none was made up.

  Verified 2026-10-09.
  - The writer ran once and wrote the eleven files
    (`logs/qraft-node-fixtures-write-2026-10-09_16-32-26-930.log`).
  - `LegacyNodeFixtureTest` and `LegacyCatalogFixtureTest` passed, 7 and 3
    (`logs/qraft-node-fixtures-test-2026-10-09_16-34-18-759.log`).
  - `mvn install` passed: 891 tests, every coverage gate met
    (`logs/qraft-tests-2026-10-09_16-34-48-739.log`). The 891 are the 884 of
    Phase 3A and these 7. Its 20 retained files hold 257 ERROR headers, every
    one flagged, and no unflagged error or exception.
  - The seven tests passed at once, as tests of existing behaviour do. Their
    mutation evidence belongs to this phase's exit.
- [x] Replace `ClientInfo`, `ClientCapabilities`, `ClientSystemInfo`, and
  `ClientNetworkInfo` with a Consul-shaped node: name, address, datacenter,
  region, metadata, status, and server-stamped times. The client list
  (decision 8) adds the generated node ID. The Quorus fleet fields
  are removed: CPU, memory, disk, bandwidth, packet loss, NAT, connection
  type, and supported services.
- [x] Remove the `UpdateCapabilities` command. Reserve the removed protobuf
  numbers and messages, and add fixtures for old WAL entries and snapshots
  (decision 4).
- [x] Reduce node status to the states Qraft sets, and drop the job-system
  status mapping if the fixtures allow.

  The three items above were done together on 2026-10-09, because they are
  one model.

  **Decided 2026-10-09, by the user: a node has no host name and no port,
  and the client's version travels in the node's metadata.** A Consul node
  has a name and an address; a port belongs to a service. The version is the
  metadata entry `qraft.version`, next to `qraft.registrationId`.

  What changed:
  - `Node` is an immutable record of eight fields: `name`, `address`,
    `datacenter`, `region`, `metadata`, `status`, `registrationTime`, and
    `lastHeartbeat`. Its metadata is never null, is kept in key order, and
    cannot be changed. It replaces `ClientInfo`. `ClientCapabilities`,
    `ClientSystemInfo`, `ClientNetworkInfo`, and their three test classes
    are deleted.
  - `NodeStatus` has the three statuses Qraft sets: `registering`,
    `healthy`, and `unreachable`. It replaces `ClientStatus`. API input is
    parsed strictly: a removed status is refused.
  - `NodeCommand` and `NodeCodec` replace `ClientCommand` and `ClientCodec`.
    The capabilities update is gone from both.
  - `commands.proto`: in the node message, numbers 2, 4, 5, and 9 and the
    names `client_id`, `hostname`, `port`, `capabilities`, and `version` are
    reserved; in the node command, number 5 and the names `client_id`,
    `client_info`, and `new_capabilities`. The messages for capabilities,
    system information, and network information are deleted. The envelope
    keeps field number 1 for a node command.
  - `QraftStateStore` holds `nodes` and writes that key in a snapshot.

  How the old formats still read (decision 4):
  - **The job-system status mapping stays, for reading only.** The fixtures
    did not allow dropping it: they hold `active`, `idle`, `degraded`,
    `overloaded`, and `draining`. The six statuses that meant the node was in
    contact read as `healthy`; `failed` and `deregistered` read as
    `unreachable`. Their protobuf values stay in the enum, marked deprecated,
    and nothing writes them.
  - A WAL entry that holds the capabilities update decodes to nothing, and
    the state store applies nothing for it. Its command type stays in the
    enum, marked deprecated.
  - A snapshot's nodes are read under `nodes`, `clients`, or the key from
    before Phase 3A. A node's name is read under `name`, `clientId`, or the
    key from before Phase 3A. The removed fields are ignored.

  Left for later, on purpose:
  - **The HTTP paths and bodies are unchanged here.** The node routes are
    still `/api/v1/clients*`, the registration answers with `clientId`, and
    the heartbeat body is keyed by `clientId`. The next item moves them. Two
    things did change on the wire, because the node did: a registration is
    a node's eight fields, and an entry of the node list has `name` where it
    had `clientId`.
  - **The `client.hostname` setting is still read and validated, and no
    longer reaches the node.** It is a leftover. The client list's decision 8
    renames `client.id` to `client.nodeName`, which defaults to the host
    name; the setting goes then, in that list's Phase 2. **Removed on
    2026-10-09 instead, on the user's decision:** see the review at the end
    of this phase.
  - A heartbeat could still carry a status, and the store sets whichever of
    the three it carries. The next item decided it: the new heartbeat body
    has no status.

  **Red before green.** The fixture tests were first rewritten to expect the
  new behaviour against the old code: three failed, as predicted, on the
  removed status, the job system's statuses, and the capabilities update
  (`logs/qraft-phase4-model-red-2026-10-09_16-55-11-358.log`).

  **A test race found and fixed on the way.**
  `HttpApiServerTest.fencedNodeFailsReadinessWhileLivenessRemainsAvailable`
  failed once on an unflagged error, "Failed to persist command to WAL: Raft
  transition sequencer is fenced"
  (`logs/qraft-phase4-model-green-2026-10-09_17-09-55-160.log`).
  - Cause: the test's storage fails every append after the bootstrap
    configuration. The node's first such append is the leadership no-op of
    its first term, and that failure fences the node. The test then
    submitted a command of its own and took that command to be the fencing
    event. When the command was queued before the fence, it failed with the
    injected fault as its cause and was flagged. When it arrived after the
    fence, 3 ms later in the failing run, it was refused with no cause, and
    nothing flagged it.
  - Production is consistent: in both orders the node is fenced and the
    command fails. Only the test depended on the order.
  - Fix: the test submits no command. It waits for the fence that the
    no-op's failure causes. The run logs one flagged error and no other.
  - Noted, not changed: a command refused by an already fenced node is
    logged without the reason the node was fenced, because
    `RaftTransitionSequencer` does not keep that reason. It is a question for
    Phase 7, which touches `RaftNode`. **Decided 2026-10-09:** it is a task
    of Phase 7 now.

  Verified 2026-10-09.
  - `mvn install` passed: 880 tests, every coverage gate met
    (`logs/qraft-tests-2026-10-09_17-12-39-374.log`). The 880 are the 891
    before, less the 25 tests of the five deleted model classes and the 5
    of `ClientCodecTest`, plus `NodeTest` 8, `NodeStatusTest` 4, and
    `NodeCodecTest` 7. No other class changed its count. Its Maven output
    holds 128 flagged ERROR headers, and its 142 retained files hold no
    unflagged error or exception.
  - The Docker, end-to-end, and slow suites passed on a fresh image: 32
    tests (`logs/qraft-tests-2026-10-09_17-14-59-263.log`). Its 79 retained
    files, 64 of them Docker archives, hold 129 flagged ERROR headers and no
    unflagged error or exception.
  - The mutation evidence for the codec and the fixture guards belongs to
    this phase's exit.
- [x] Move node registration, heartbeat, deregistration, and listing from
  `/api/v1/clients*` to Consul-aligned `/v1/` routes with the standard error
  envelope and identity headers (for example `GET /v1/catalog/nodes`). Use the
  paths of the client list's decision 5, so the node routes move only once.
  The service and check write paths move later, in the client list's Phase 1.
  Update `HttpCatalogClient`.

  Before moving them, fix the request bodies of `PUT /v1/catalog/register`
  and `PUT /v1/catalog/deregister` for nodes and services together (review of
  2026-10-08). The client list's decision 5 puts both on those two paths, and
  its Phase 1 adds the services. A body designed here for a node alone would
  change there: the node routes would keep their paths and still change
  twice. Record the bodies in the design's section 12.1.

  Done 2026-10-09. The four routes are `PUT /v1/catalog/register`,
  `PUT /v1/catalog/deregister`, `PUT /v1/catalog/node/heartbeat`, and
  `GET /v1/catalog/nodes`. The old four are removed in the same change. The
  design's new section 12.1.2 records the bodies, the answers, and the error
  codes.

  **Decisions made here, on the standing instruction to follow Consul.
  Confirmed by the user on 2026-10-09, as built.**
  - **The identity header names the node.** `X-Qraft-Node` is required on
    the three writes, as it is for a service. A body never names the node.
  - **One registration body for nodes and services:** the node's
    description under the key `node`. The client list's Phase 1 adds a
    service beside it under the key `service`, so a node sends the same body
    then. Until then `node` is required and `service` is refused.
  - **Deregistration follows Consul's rule:** a body that names no service
    removes the node itself, so a node sends `{}`. A service will be removed
    with `{"serviceId": "..."}`; until then any field is refused. When that
    field arrives it must hold a non-blank string, so that a missing value
    cannot remove the node instead.
  - **A heartbeat carries no status and no time.** Its body is an optional
    `sequenceNumber` and an optional `registrationId`. It makes the node
    healthy, and the server's clock gives the time. With three statuses, a
    client had nothing else to report.
  - **Unknown fields are refused** on all three writes, as on the service
    routes. A registration cannot carry a name, a status, or a time.
  - **Every answer is JSON.** A write answers 200 with the node's name and
    `registered`, `accepted`, or `deregistered`. Deregistration is
    idempotent: an absent node answers 200 with `deregistered: false`, where
    the old route answered 404. The key is `node`, not `nodeId`, because the
    client list gives a node a generated ID.
  - **The client accepts a success only when the answer names the node and
    carries the outcome.** Before, any 2xx status registered the node, so an
    answer from some other HTTP service would have. A malformed success is
    now the retryable `invalid_response`, as it is for a service.
  - **The node list writes each time as an ISO-8601 instant**, like the
    other answers. The old list wrote the mapper's default, a number of
    seconds. The list has its own answer type, `NodeEntry`.
  - **A node route takes no path parameter.** A longer path answers 404
    `not_found`.
  - The error codes are `invalid_registration`, `invalid_deregistration`,
    `invalid_heartbeat`, `node_not_found`, and `stale_heartbeat`. They
    replace `invalid_client`, `client_id_required`, and `client_not_found`.

  Not changed, and noted for the client list's Phase 1:
  - **Deregistering a node removes the node's entry only.** Consul removes
    the node's services and checks with it. A Qraft client deregisters its
    services first, and expiry reaps what a crashed client leaves. Whether
    the command should remove them is to decide when services share the
    path. **Changed on 2026-10-09, on the user's decision:** see the review
    at the end of this phase.
  - The service and check writes are still at `/v1/client/*`.

  What changed in the code:
  - `HttpApiServer` serves the four routes. `CatalogRegistrationRequest`,
    `NodeHeartbeatRequest`, and `NodeEntry` are new.
  - `HttpCatalogClient` has `registerNode`, `heartbeatNode`, and
    `deregisterNode`, and refuses to register a node of another name than
    the one it speaks for. `RegistrationClient` and `HeartbeatService` lost
    the parameters that only repeated the node's name.
  - `NodeAnswerHelper` answers the node routes for the tests that stand a
    plain HTTP server in for a Qraft server.

  **Red before green.**
  - The server tests were rewritten first. Ten of `HttpApiServerTest`'s 41
    failed, as predicted: nine on the absent routes and one on the old ones
    still being there
    (`logs/qraft-phase4-routes-red-2026-10-09_17-28-17-601.log`).
  - The time format had its own red run: one failure, a number where an
    instant was expected
    (`logs/qraft-phase4-node-times-red-2026-10-09_17-48-20-704.log`).
  - One expectation of mine was wrong and was corrected, not the code. A
    path that only begins with a route's name, such as
    `/v1/catalog/registers`, reaches no route: the JDK's HTTP server matches
    a context by whole path segments and answers that 404 itself
    (`logs/qraft-tests-2026-10-09_17-33-28-726.log`).

  **A second test race found and fixed on the way.**
  `DockerRunningPartitionTest.aPartitionedFollowerCampaignsButNeverLeadsWhileTheMajorityServes`
  timed out once, after 60 s (`logs/qraft-tests-2026-10-09_17-37-40-723.log`).
  - Cause, from the three containers' archived logs: the test cut the
    follower off less than a second after the first election. The follower
    was ready, because it knew its leader, and did not yet hold the
    cluster's configuration. A server without a configuration does not
    campaign, so its term never rose. It logged "Cluster bootstrap: WAITING"
    for the whole partition and established its configuration only after
    the network was restored.
  - Production is consistent. A server that does not know the membership
    cannot campaign, and an entry that no follower holds was never
    committed. No data and no committed state is at risk.
  - Fix: before it cuts a server off, the test waits until every server
    lists all three in `GET /v1/operator/raft/configuration`. The class ran
    in 21 s afterwards.
  - Noted, not changed: `/health/ready` answers 200 for a server that knows
    a leader and holds no configuration yet. Whether such a server is ready
    is a question for the membership list. **This note understated the
    problem, which was fixed on 2026-10-09:** see the review at the end of
    this phase.

  Verified 2026-10-09.
  - `mvn install` passed: 884 tests, every coverage gate met
    (`logs/qraft-tests-2026-10-09_17-48-58-794.log`). The 884 are the 880
    before, plus two in `HttpApiServerTest` and two in
    `RegistrationClientTest`. Its Maven output holds 130 flagged ERROR
    headers, and its 150 retained files hold no unflagged error or
    exception.
  - The Docker, end-to-end, and slow suites passed on a fresh image: 32
    tests (`logs/qraft-tests-2026-10-09_17-50-49-051.log`). Its 79 retained
    files, 64 of them Docker archives, hold 84 flagged ERROR headers and no
    unflagged error or exception.
  - A word search of `src/` finds the old paths only in
    `HttpApiServerTest.theRemovedNodeRoutesAreGone`, which asserts that they
    answer 404.

  Still naming the old node routes, each under its own later item:
  - `docker/test-data`, `docker/scripts`, `start-quick.*`, and a Grafana
    panel: Phase 6.
  - the administrative interface's plan and task list: Phase 8.
- [x] Remove `/api/v1/info`, `/status`, and bare `/health`. Compose
  healthchecks and the documentation use `/health/live` or `/health/ready`.

  Done 2026-10-09.
  - The server no longer serves the three routes. `/health/live` and
    `/health/ready` each have their own handler; the two helpers that
    registered routes by a status and a body are gone with their last users.
  - **Bare `/health` is removed in client mode too.** The client's health
    listener served it as a second name for readiness. One jar has one set of
    names. Decided here, and confirmed by the user on 2026-10-09.
  - **A server's health check is now liveness, and the client's is
    readiness.** A server's bare path always answered 200, so the 29 server
    health checks of the nine compose files use `/health/live`, as do the
    nine Testcontainers wait strategies. The client's bare path answered its
    readiness, so the one client health check uses `/health/ready`.
    A server's check must stay liveness: a server is unready until its
    cluster has a leader, so a readiness check would mark every server of a
    starting cluster unhealthy and hold back whatever waits on
    `service_healthy`.
  - `DockerRaftClusterTest.everyServerReportsPassingHealth` is now
    `everyServerReportsThatItIsAlive`.
  - `AdminUiConfig` still reserves the segments `api` and `status`. Nothing
    is served under them now. They stay reserved so that the interface cannot
    be mounted where an API used to answer; the interface's plan and list are
    updated in Phase 8.

  **Red before green.** The two tests were changed first and failed as
  predicted, one in each mode, on bare `/health` answering 200 and 503
  (`logs/qraft-phase4-remove-routes-red-2026-10-09_17-59-09-874.log`).

  Verified 2026-10-09.
  - `mvn install` passed: 884 tests, every coverage gate met
    (`logs/qraft-tests-2026-10-09_18-00-16-690.log`). Its Maven output holds
    129 flagged ERROR headers, and its 149 retained files hold no unflagged
    error or exception.
  - The Docker, end-to-end, and slow suites passed on a fresh image, started
    by the new health checks: 32 tests
    (`logs/qraft-tests-2026-10-09_18-02-02-077.log`). Its 79 retained files,
    64 of them Docker archives, hold 105 flagged ERROR headers and no
    unflagged error or exception.
- [x] Delete the old client DTO tests. Add node codec, replica-determinism, and
  legacy-fixture tests.

  Done 2026-10-09, with the model.
  - Deleted: `ClientCapabilitiesTest`, `ClientSystemInfoTest`,
    `ClientNetworkInfoTest`, `ClientInfoTest`, `ClientStatusTest`, and
    `ClientCodecTest`, 30 tests.
  - Node codec: `NodeCodecTest`, 7 tests, with `NodeTest`, 8, and
    `NodeStatusTest`, 4, for the JSON side.
  - Replica determinism: `ReplicaDeterminismTest`, 9 tests, rewritten for an
    immutable node.
  - Legacy fixtures: `LegacyNodeFixtureTest`, 7 tests, and
    `LegacyCatalogFixtureTest`, 3.

**Exit:** Red before green with mutations for the codec and fixture guards;
full suites.

**Exit met 2026-10-09.**
- Red before green: four red runs, each failing exactly as predicted. They
  are cited under the items above: the model (3 failures), the routes (10),
  the time format (1), and the removed routes (2).
- Mutations: 15 mutants of the node codec, the node's JSON reading, the
  snapshot reading, the stored status, and one fixture's bytes. **All 15
  were killed, each by the tests named beforehand**
  (`logs/qraft-phase4-mutants-2026-10-09_18-08-47-532.log`).
  - They ran one at a time in an isolated copy, never in the working tree.
    The copy passed before the first mutant and after the last: 42 tests.
  - Eight are in the codec: a removed status decoded the wrong way, in each
    direction; the capabilities update refused instead of skipped; an absent
    address and an absent time decoded as values; the metadata, a
    heartbeat's registration identifier, and an expiry's reap flag dropped.
  - Six are in the reading of stored JSON: each of the two older names of a
    node's name and of a snapshot's nodes no longer read; an unknown field
    refused; a removed status refused.
  - One changes a single byte of a fixture, which the digest guard caught.
  - The fixtures alone kill 11 of the 15. Two need `NodeCodecTest`, because
    no fixture holds `failed` or `deregistered`, the two statuses Qraft never
    wrote; one needs `NodeTest`; one needs the round-trip tests.
- Full suites: the last runs of the phase are `mvn install`, 884 tests with
  every coverage gate met, and the Docker, end-to-end, and slow suites, 32
  tests on a fresh image. Both are cited under the route removals above, with
  their log audits: no unflagged error or exception in either.

**The phase's three remaining decisions, confirmed by the user on
2026-10-09.** Each was put with its alternatives, and each stays as built.
1. **The bodies and answers of the node routes** stay as the design's
   section 12.1.2 records them: the identity header names the node, and a
   deregistration that names no service removes the node.
   - Not chosen: a deregistration that must say what it removes, and a body
     that names the node as Consul's does.
   - Accepted with it: since a node's deregistration also removes its
     services, an empty body sent by mistake removes more than it did.
     Consul's rule has the same cost.
2. **A heartbeat carries no status and no time.** Not chosen: an optional
   status field.
3. **Bare `/health` stays removed in client mode too.** Not chosen: restoring
   it there as a second name for readiness.

**The phase's four open points, reviewed with the user on 2026-10-09.** I
re-read the code behind each before the review. Two were worse than the
notes above said, and the red run below showed both.

1. **A node's deregistration removes its services and checks. Decided: yes,
   now.**
   - Before, it removed the node's entry only. The notes above said that the
     client covers this by deregistering its services first. It did not
     fully: at shutdown the client deregisters the node even when a service
     deregistration failed. The node was then gone, its services stayed, and
     nothing reaped them, because expiry works from node entries.
   - Now `NodeCommand.Deregister` removes every service registered on the
     node, in every tenant and namespace, with its checks, in the same
     replicated step. It uses the removal that reaping already did.
   - It does so when the node has no entry too, so that services left under
     a node's name can be removed. The result still says whether a node
     entry existed.
   - An old log that holds a deregistration replays with the new meaning.
     Nothing is deployed, so no log exists that this could change.
2. **A server that does not campaign forgets a silent leader. Decided: yes,
   now.**
   - Before, only a campaign made a server forget its leader. A server that
     holds no configuration yet, or that has joined and is not yet a voter,
     does not campaign. Cut off from its leader, it kept reporting that
     leader, so `/health/ready` kept answering 200. The design's section 6.1
     says such a server is unready.
   - Now, when the election timer fires and the server does not campaign, it
     forgets the leader and reports `no_leader`. The next message from a
     leader makes the leader known again. It logs the loss once, at INFO.
   - Not added: a readiness condition for a server without a configuration.
     Such a server in contact with its leader is ready, as a follower that
     is behind is.
   - This change to `RaftNode` comes before the membership list's Step 4
     gate. That gate's mutation evidence is taken against the source that
     includes it.
3. **A fenced server says once why it is fenced. Decided: yes, in Phase 7.**
   It is a task there now. The refusals stay as they are.
4. **The `client.hostname` setting is removed. Decided: now.**
   - Nothing used its value since the node lost its host name. The planned
     `client.nodeName` defaults to the machine's host name, not to a setting.
   - A configuration file that still sets it is refused: "Unknown client
     setting: hostname". The three Docker configuration files and the
     generated ones of two tests no longer set it.

**Red before green.** Five new assertions failed before any production
change, as predicted, in four classes: two on the services a deregistered
node left, in the state store and over HTTP; one more on services under a
name without a node entry; one on the leader still being known; one on the
setting being accepted
(`logs/qraft-decisions-red-2026-10-09_18-28-25-003.log`).

Verified 2026-10-09.
- `mvn install` passed: 888 tests, every coverage gate met
  (`logs/qraft-tests-2026-10-09_18-30-09-463.log`). The 888 are the 884
  before, plus two in `NodeMembershipExpiryStateStoreTest`, one in
  `HttpApiServerTest`, and one in `RaftNodeServerIdCountingTest`. Its Maven
  output holds 128 flagged ERROR headers, and its 149 retained files hold no
  unflagged error or exception.
- The Docker, end-to-end, and slow suites passed on a fresh image, with the
  changed client configuration files: 32 tests
  (`logs/qraft-tests-2026-10-09_18-32-00-701.log`). Its 79 retained files,
  64 of them Docker archives, hold 105 flagged ERROR headers and no unflagged
  error or exception.
- A word search of `src/` and `docker/config` finds `hostname` as a setting
  only in the test that asserts it is refused. The legacy fixtures and the
  test of JSON written by earlier versions keep the old field, as they must.
- No mutation run was made for these three changes. The red run is the
  evidence that each test detects its change being absent.

### Phase 5. Configuration and version

- [x] Remove `AppConfig`'s static default instance and its classpath defaults
  file `qraft-server.json` (moved from Phase 1).
- [x] Replace `AppConfig`'s flattened string map with typed records parsed
  directly from the JSON document. The map holds `qraft.*` keys, with cluster
  nodes re-encoded as `name=host:port,...`. Keep identical validation errors
  and JSON paths.
- [x] Have server and client configuration share one JSON parsing and
  validation helper where they duplicate it.

  The three tasks above were done together on 2026-10-10.

  What changed:
  - **No default document and no process-wide configuration.**
    `AppConfig.get()`, both `install` methods, and the classpath file
    `qraft-server.json` are gone. `QraftServerApplication` reads the
    document, validates it, and hands the configuration to
    `QraftServerService` and to `TelemetryConfig`. Two tests no longer
    restore a process-wide configuration, because there is none.
  - **Typed settings.** `AppConfig` holds records that follow the
    document's own structure. The getters and every validation message are
    as they were. The untyped getters by `qraft.*` key are gone; their one
    caller, the shutdown timeouts, has two typed getters.
  - **The cluster's members are a map.** `getClusterMembers()` gives each
    member's name with its Raft address, in the document's order, and
    `QraftServerService` uses it in place of parsing a `name=host:port,...`
    string. `getClusterNodes()` stays as the form for display.
  - **`JsonSettings`, in `common.config`,** holds what both modes
    duplicated: reading the document, the format version, and the checks
    of objects, numbers, booleans, and unknown settings.
    `ClientConfiguration` lost its copies. Reading a text setting stays in
    each class, because a server allows a blank value where the setting may
    be empty and a client never does.

  One difference in behaviour: a member's name in `server.raft.nodes` is
  trimmed when it is read. Before, it was trimmed where it was used.

  **No red run.** Tasks 2 and 3 are refactors and task 1 is a removal, so
  the existing configuration tests are the check, as this phase's exit
  says. Three of them had to change, because they tested what was removed:
  `AppConfigCoverageTest` lost its two tests of how the classpath defaults
  were packaged and loaded, and its test of the default document now reads a
  document with no settings. New tests cover what is new: the member map,
  the shutdown settings, and `JsonSettingsTest`, with the exact message of
  each refusal.

  Verified 2026-10-10, on Jenkins:
  [build 5](http://192.168.137.32:8080/job/Qraft/5/), of `d4e1af8`.
  - Default build: 898 tests, every coverage gate met. The 898 are the 892
    before, less the 2 removed, plus 1 in `AppConfigCoverageTest` and 7 in
    `JsonSettingsTest`.
  - End-to-end: 10 tests. Docker and slow: 25. Jenkins published 933
    results, none failed or skipped.
  - Its 101 archived log files, 64 of them Docker archives, hold 496 flagged
    ERROR headers and no unflagged error or exception.
  - No local run was made.

  Commits: `12d365c` and `d4e1af8`.
- [x] Report the version from the build manifest instead of the `2.0-ext`
  default and the `applicationVersion` setting.
- [x] Put a version in the manifest, and decide what it is (review of
  2026-10-05). The executable jar's manifest has none today: the shade step
  sets only the main class. The POM's version is `1.0-SNAPSHOT`, while the
  server reports `2.0-ext`. Add the implementation entries to the manifest,
  and choose the POM version that Qraft reports.
- [x] Do the same for the client (review of 2026-10-05). `client.version` in
  the client configuration is the same kind of setting as
  `applicationVersion`, and the Docker example sets it to `1.0.0`. The client
  reports the manifest's version too, and the setting is removed.

  The three tasks above were done together on 2026-10-09 and verified on
  2026-10-10.

  **Decided 2026-10-09, by the user: the POM's version is the only source,
  and Qraft reports it as it stands.** That is `1.0-SNAPSHOT` today. A
  release changes one number.

  What changed:
  - The build writes `Implementation-Title` and `Implementation-Version`
    into the manifest of the executable jar. `QraftVersion` reads the
    version back. A process that does not run from the jar, as a test run
    does not, reports `development`.
  - `server.applicationVersion` and `client.version` are refused as unknown
    settings. The Docker example and the tests no longer set them, and
    `2.0-ext` is gone.
  - Each mode logs one line when it starts: "Qraft <version> starting in
    <mode> mode". That is where a server reports its version. A client also
    puts it in its node's metadata, as `qraft.version`.

  **Found and fixed on the way: a server put its configured version into
  the replicated state.** `QraftServerService` seeded the state machine with
  the key `version`, taken from `applicationVersion`. Two servers with
  different settings therefore started from different state, and with the
  version coming from the build, two builds in one cluster would have too.
  - Nothing read the entry. The seeding is removed, with the constructor of
    `QraftStateStore` that allowed it: replicated state now starts the same
    on every server.
  - The store's own initial entry, `version` = `3.0`, stays. On a new
    cluster the key/value service now answers `3.0` for that key, where it
    answered the configured version.

  **Red before green.** Eleven assertions failed before any production
  change, as predicted: the stub of `QraftVersion` (3), each setting still
  accepted (2), the client still registering `1.0.0` (1), neither mode
  naming its version at start-up, from the class path and from the jar
  (4), and the jar's manifest without a version (1). The user ran it
  (`logs/qraft-phase5-version-red-2026-10-09_21-55-10-814.log`).

  Verified 2026-10-10, on Jenkins, at the user's request:
  [build 4](http://192.168.137.32:8080/job/Qraft/4/), of `ce5890d`.
  - Default build: 892 tests, every coverage gate met. The 892 are the 888
    before, plus 3 in `QraftVersionTest` and 1 in `AppConfigValidationTest`.
  - End-to-end: 10 tests. Docker and slow: 25. The 35 are the 32 before,
    plus three in `RuntimeLoggingTest` that take Qraft's classes from the
    packaged jar: its manifest carries the POM's version, and each mode
    reports it.
  - Jenkins published 927 results, none failed or skipped.
  - Its 101 archived log files, 64 of them Docker archives, hold 550 flagged
    ERROR headers and no unflagged error or exception.
  - In the containers both modes logged `1.0-SNAPSHOT`; from the class path
    both logged `development`.
  - No local run was made for the green state. The class files on the
    development machine are whatever the user's last build left.

  Commits: the red state as `693449e`, the change as `c123ee9`.

**Exit:** The existing configuration tests pass unchanged, apart from those
of the two removed version settings, plus tests for the version source in
both modes.

**Exit met 2026-10-10,** with one qualification. The configuration tests
pass, and the version source has tests in both modes, from the class path and
from the packaged jar. Three tests beyond those of the version settings
changed, and they are named above: each tested the default document or the
process-wide instance that task 1 removes.

### Phase 6. Docker and observability

- [ ] Keep one observability stack: OpenTelemetry Collector, Tempo,
  Prometheus, Loki, and Grafana.
- [ ] Delete the unreferenced ELK and Fluentd compose files.
- [ ] Delete the Promtail stack: `docker/logging/`, `docker-compose-loki.yml`,
  and the Promtail labels in the cluster compose files.
- [ ] Remove the start scripts' use of `docker-compose-loki.yml` in the same
  change (review of 2026-10-05). `start.ps1`, `start.sh`, `start-quick.ps1`,
  and `start-quick.sh` all start or stop it, so deleting the file alone
  breaks them, whatever the nginx decision below.
- [ ] Delete the four logging demo scripts (`.ps1` and `.sh`): demo-logging,
  log-extraction-demo, setup-logging, and simple-log-demo.
- [ ] Remove the Grafana panels built on `qraft_clients`, a metric the server
  never emits.
- [ ] Rework `docker/test-data` to use the Phase 4 node routes, or delete it in
  favour of the end-to-end suite. Delete the unreferenced `test-heartbeat.json`.
  `start-quick.*`'s `test` action and `docker/README.md` use the other files
  there.

  Since Phase 4 (2026-10-09) these call routes that no longer exist, so each
  fails until this task is done. `docker/README.md` says so.
  - `docker/test-data`: `check-clients.*`, `send-heartbeat.*`, and the body
    in `test-registration.json`, which still has the fields of the old
    node model;
  - `start-quick.*`: the `test` action;
  - the logging demo scripts that the task above deletes;
  - one Grafana panel, which reads `/api/v1/clients` directly.
- [ ] Delete `docker/test-data/nginx.conf` (review of 2026-10-05). Nothing
  references it; the load-balancer topology mounts `compose/nginx/nginx.conf`.
- [ ] Decide whether the nginx load-balancer topology stays, since clients
  rotate through their seeds themselves. Reduce `start.*` and `start-quick.*`
  to the remaining topologies.
- [x] Keep the local credentials folder out of Docker build contexts (added
  and done 2026-10-10, at the user's direction). `.env/` holds the Jenkins
  API token. Git ignored it and it was never committed, but `.dockerignore`
  did not name it, and most compose files build from the repository root.
  The Dockerfile copies only the jar and the entrypoint, so the token could
  not reach an image; with Docker's legacy builder it would still have been
  sent to the daemon with the build context. `.dockerignore` now excludes it
  (`ce5890d`).
- [ ] Find the cause of the one Docker failure on Jenkins (added 2026-10-05):
  `DockerClientRecoveryTest.aKilledFollowerInstallsTheLeadersSnapshotAndThenHoldsTheLeadersHealthState`
  timed out after 90 seconds in build 2 of 2026-10-04 (`docs/JENKINS.md`),
  while the Phase 2 run on the development machine passed 30 of 30. An
  intermittent or machine-dependent failure is a defect
  (`PROJECT_STANDARDS.md` section 4.4).

  Noted 2026-10-10: the test passed in Jenkins build 4, the first complete
  pass of the pipeline on that server. One pass does not give the cause of
  the timeout in build 2, so this task stays open.

**Exit:** `DockerDeploymentContractTest`, the Docker suite, and each remaining
start command work.

### Phase 7. Replace the Vert.x-shaped async layer

- [ ] Before any change in this phase, close the membership list's Step 4
  gate: record its mutation evidence against the source as Phase 6 left it,
  under the rules of that list's "Step 4 close-out gate". The tests it
  validates are then the regression net for the changes to `RaftNode` below.
- [ ] Replace `common.async.Future`, `Promise`, and `AsyncResult` with
  `CompletableFuture` and `CompletionStage` (11 production files import
  them, plus the `common.async` package itself; counted again on 2026-10-08).
- [ ] Replace `JavaRuntime` (an emulated event loop) and `WorkerExecutor` with
  explicitly owned executors: the Raft state loop and a bounded
  virtual-thread pool. Keep the `CallerContext` MDC and OpenTelemetry propagation.
- [ ] Replace the `JavaTestContextHelper` and `JavaRuntimeExtensionHelper` test helpers
  with plain futures and bounded waits.
- [ ] Size the test side before starting (review of 2026-10-05). 56 test
  files import the async types, against 3 that use the two helpers above.
  They include every hand-written transport and Raft fixture. Decide then
  whether this phase moves to its own list, as decision 5 allows.
- [ ] Run the changed concurrency tests five times consecutively.
- [ ] Have a fenced server say once why it is fenced (decided 2026-10-09, in
  the review of Phase 4's open points).
  - Today nothing announces a fence. The failing operation logs its own
    error, `RaftTransitionSequencer` logs nothing and does not keep the
    cause, later refusals say only "is fenced", and `/raft/status` shows
    `fenced: true` with no reason.
  - Log one error that names the cause when the node becomes fenced, and add
    the reason to `/raft/status`.
  - Leave the refusals as they are. Attaching the cause to each one would
    repeat the stack trace on every refused write and heartbeat, and would
    change how six test classes' declared errors are labelled.

**Exit:** Full suites on a fresh image; no wrapper future types remain;
`PROJECT_STANDARDS.md` section 2.1 audit passes.

### Phase 8. Documentation and close-out

**Done early, 2026-10-05,** at the user's direction, for Phases 1 and 2 only:
the documents below now describe one Maven project, its layers of packages, and
the removal of `qraft-tenant`. They use the package names of that day.
- The design's section 1.1 is a package map, and its other module wording is
  gone.
- The README, `PROJECT_STANDARDS.md` sections 1, 3, 6.2, 7, and 9, the Consul
  plan's sections 3 and 4, the event architecture's section 3, and the version
  sources in `OPEN_SOURCE_USAGE.md`.
- The feature validation's `qraft-tenant` and `qraft-events` rows, and the
  build location, frontend source path, and tenancy owner in the administrative
  interface's plan and task list.
- The module wording in `TESTING.md` and `JENKINS.md`, and the jar name in
  `docker/README.md`.

**Done early, 2026-10-09,** at the user's direction, for Phase 4 and for the
decisions that followed it:
- The design: the node model in section 7.3, the node routes in the new
  section 12.1.2, the readiness of a server that does not campaign in section
  6.1, and what a node's deregistration removes in sections 8.4 and 9.2.
- The administrative interface's plan, section 4.1, its task list, and one line
  of the interface design: the task below.
- The Consul plan: a checklist entry for the node model and the removed
  routes. Its section 3 now names the packages, with the responsibilities the
  design's section 1.1 gives them. It still gave the modules of before
  2026-10-04, two of them under Qraft's retired words.
- The feature validation: a row for the node registry, and notes on the rows
  for readiness and for the client's node registration.
- The ACL list's proposal 11, and the membership list's Step 4 gate.
- `RAFT_STORAGE_OPERATIONS.md`: node commands and snapshot nodes from before
  2026-10-09 still decode.
- `docker/README.md`: its `test` action calls routes that are gone.
- `TESTING.md` and `PROJECT_STANDARDS.md` section 7: an assistant must not run
  builds or open a console unless the user specifically requests it. Both
  state that rule now, in the user's words of 2026-10-09, with what applies
  when the user does request a run: one visible console, reused, never
  hidden. A request covers that request; it is not a standing permission.

What remains for this phase:

- [ ] Bring the documents above up to date with Phases 3 to 7:
  - the package and class names of Phase 3, in the design's section 1.1 table
    first, then the README, `PROJECT_STANDARDS.md` section 3, `TESTING.md`,
    `JENKINS.md`, and `docker/README.md`. Done with Phase 3 (`9a4adf0`). The
    review of 2026-10-08 searched these documents for the old package, class,
    module, and service names. It found one, the telemetry `serviceName` in
    the design's configuration example, and corrected it. The other matches
    are dated records, former module names given as such, and log extracts
    that `TESTING.md` marks as historical;
  - the log file names of `PROJECT_STANDARDS.md` section 6.2. Done with
    Phase 3: the section names the files by mode, and its note that both
    modes write one log file is gone;
  - the names and removals of Phases 4 to 7, as each phase ends. Phase 4's
    are in, as of 2026-10-09: see "Done early" above;
  - the version source, in `OPEN_SOURCE_USAGE.md` if Phase 5 changes it.
- [ ] Record the removals of Phases 4 to 7 in the feature validation and the
  Consul plan checklist. Phase 4's are recorded in both, as of 2026-10-09.
- [x] Update the administrative interface's plan and task list for Phase 4:
  the plan's section 4.1, the list's read APIs, its reserved path segments,
  and the development proxy lose the routes Phase 4 removes: `/api/v1/clients`,
  `/status`, and bare `/health`.

  Done 2026-10-09, with one difference from the task as written.
  - The plan's section 4.1 lists `GET /v1/catalog/nodes` in place of
    `/api/v1/clients`, the two health routes without the bare one, and the
    node writes.
  - The list's read APIs name the same routes, and its development proxy no
    longer forwards `/api`.
  - **The reserved segments were not reduced.** `AdminUiConfig` still refuses
    a path under `api` or `status`, and the list now says so. Nothing answers
    there any more, so the task as written would free both names. They were
    kept so that the interface cannot be mounted where an API used to answer.
    This waits for the user's word: freeing them is a two-word change in
    `AdminUiConfig` and one test.
- [x] Update the membership list for its resumption: the class names in its
  Step 5 contract, after Phases 3 and 5. The Phase 3 name,
  `QraftServerService`, was put there on 2026-10-08; `AppConfig` follows
  Phase 5.

  Done 2026-10-10. `AppConfig` kept its name. The membership list's Step 5
  now says what it became: records that follow the document, and a
  configuration that `QraftServerService` is given.
- [ ] Run the final audits:
  - prohibited frameworks;
  - environment-variable configuration;
  - the timeout API;
  - source headers;
  - `git diff --check`;
  - a search for `quorus`, `job`, `transfer`, `fleet`, and `/api/v1`.
- [ ] Archive this list and resume the membership list at its Step 5. The
  client list follows the membership list.

## 5. Out of scope

- New features: key/value completion, consistency modes, sessions, tenancy, security.
- The gRPC `DistributedStateService`: the key/value completion list decides
  whether `/v1/kv` replaces it.
- The client's local API, the move of the server's `/v1/client/*` paths, and
  DNS. The first two are in the client list; DNS gets a later list.
