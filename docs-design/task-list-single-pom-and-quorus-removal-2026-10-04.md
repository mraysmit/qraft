# Task List: Single POM and Removal of the Quorus Leftovers

**Date:** 2026-10-04
**Status:** In progress. This is the current task list. It started on 2026-10-04 at the user's request, before the membership list's Step 4 close-out gate.
**Active work:** Phase 3 is implemented and verified complete on 2026-10-08. The next coding task is Phase 4's first item: create immutable legacy node-command, capability-command, job-status, and node-snapshot fixtures before removing the old model. Phase 2A is accepted complete; Phases 0 to 2 were done 2026-10-04.
**Last reviewed:** 2026-10-05, against the code at commit `24beac3`. The status table in section 4 and every item marked "review of 2026-10-05" come from that review. No build was run for it, and `logs/` was not in the working tree, so recorded test counts and jar comparisons were not re-checked.
**Order of work:**
- The membership list's Step 4 close-out gate is the first task of Phase 7 here.
- After this list, the membership list resumes at its Step 5, on the new layout, and runs to its end.
- [`task-list-consul-style-client-2026-10-04.md`](task-list-consul-style-client-2026-10-04.md) follows the membership list. Decided 2026-10-05.

**Related:** [`task-list-raft-membership-changes-2026-09-29.md`](task-list-raft-membership-changes-2026-09-29.md) (interrupted by this list), [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md)
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md), section 2.4: inherited components must not remain without a clear role

## 1. Goal

Ship Qraft as what it now is: one Maven project, one jar, two runtime modes,
with nothing left of Quorus's file-transfer fleet model, its Vert.x-shaped
runtime layer, or its unused tooling.

## 2. Decisions

1. **Client mode stays and becomes a full Consul-style client agent.** Decided
   2026-10-04, for VMs and bare metal. That work is
   [`task-list-consul-style-client-2026-10-04.md`](task-list-consul-style-client-2026-10-04.md).
   This list removes only the client's Quorus-era node model.
2. **One POM, one artifact.** The root `pom.xml` is the only POM and builds
   `target/qraft.jar`. Module boundaries become package boundaries, enforced by
   a dependency test.
3. **Packages follow the modes:** `server` (was `controller`) and `client` (was
   `agent`). The client configuration's `agent` object keeps its name. The
   server's `/v1/agent/*` paths move in the client list, not here.
4. **Removed formats stay readable.** Removed protobuf fields and messages are
   `reserved`; snapshot readers ignore removed fields; fixtures prove old WAL
   entries and snapshots still load.
5. **Phase 7 belongs here.** It may move to its own list if it grows, because
   it touches `RaftNode`.

## 3. Rules

- Phases 1 to 3 change no behaviour, with one exception: Phase 3 renames the
  log files and the default telemetry service name. Evidence: the same tests,
  by name and count less deleted tests, pass before and after.
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

### Status (2026-10-05)

This table is the historical review snapshot. Current work is identified above; Phase 2A was accepted
complete on 2026-10-08 after 845 default and 31 tagged tests passed, coverage passed, and all 80 retained
log files were audited without unflagged errors or exceptions. See `docs/TESTING.md` for the flagging rules
and extracts from that run.

| Phase | State | Tasks done | Commit |
|---|---|---|---|
| 0. Baseline | Done, with one task added since | 2 of 3 | none: its output is in `logs/` |
| 1. Delete dead code and files | Done; verified against the code | 6 of 6 | `bbf5046` |
| 2. Single POM | Done; verified against the code | 7 of 7 | `04dddeb` |
| 2A. Intentional errors labelled | In progress: default and Docker-suite conversion complete | 6 of 9 | `24beac3`, in part |
| 3. Package layout | Not started; five tasks added by the review | 0 of 10 | |
| 4. Node model and API | Not started; one task added | 0 of 7 | |
| 5. Configuration and version | Not started; two tasks added | 0 of 6 | |
| 6. Docker and observability | Not started; two tasks added | 0 of 10 | |
| 7. Async layer | Not started; one task added | 0 of 6 | |
| 8. Documentation and close-out | Started early for Phases 1 and 2 | 0 of 6 | |

The default suite on `main` is expected to fail from `24beac3` until Phase 2A's
conversion task is done (Phase 2A, "State of `main`").

### Phase 0. Baseline

- [x] Record a baseline run: `mvn install`, end-to-end, and Docker suites
  (812 / 7 / 23 on 2026-10-02), with their logs kept for comparison.
- [x] Record the runtime jar's class list as the packaging baseline.

**Record (2026-10-04).** `logs/refactor-baseline-2026-10-04/` holds the jar's
14,357 entries and the 842 test cases by module, class, and name. The default
suite is `logs/qraft-tests-2026-10-03_23-58-53-674.log`: BUILD SUCCESS with 812
tests, run on sources identical to HEAD `532e562` apart from documentation. The
23 Docker and 7 end-to-end cases come from the 2026-10-02 reports.

- [ ] Keep the evidence in the repository (added 2026-10-05). `logs/` is
  ignored by git, so the baseline lists, `phase2/phase2_restructure.py`, and
  `tools/log_kinds.py` exist only in the working tree that produced them. Move
  the two baseline lists and the two tools to a tracked directory before
  Phase 3, whose exit compares test names with the baseline.

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
- [ ] Child JVMs, by the same rule as containers (review of 2026-10-05). Five
  test classes start a Java process whose output goes to a file in a
  temporary directory, where no window sees it and the test log does not
  hold it:
  - `RaftNodeRealStorageRecoveryTest`, `RaftNodeRealSnapshotRecoveryTest`,
    and `RaftNodeInstalledSnapshotRealRecoveryTest`, which run crash writers;
  - `RaftStorageProcessLockTest`;
  - `CrashedAgentExpiryEndToEndTest`, which runs a whole client.

  Read each child's output when it ends. Every ERROR in it must match an entry
  the test declares, and is reprinted in the test log with its label.
- [ ] After each suite, check its log file: every ERROR line carries a label.
  This also covers anything logged after the last test class closed, which no
  window sees.
- [ ] Document the rules and how to read a test log in `PROJECT_STANDARDS.md`
  section 4.3 and `docs/TESTING.md`.

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
    contract, so renaming it changes behaviour;
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

### Phase 4. Node model and API

- [ ] First, write the legacy fixtures from the code as it stands (review of
  2026-10-05). Only five catalog fixtures exist, under
  `src/test/resources/fixtures/catalog/`. There is none for a node command, an
  `UpdateCapabilities` command, a job-system status, or a snapshot holding
  nodes. Once the old model is removed, nothing can write the old format, so
  the fixtures of decision 4 are generated before any other task here, and
  committed with a manifest like the catalog's.
- [ ] Replace `AgentInfo`, `AgentCapabilities`, `AgentSystemInfo`, and
  `AgentNetworkInfo` with a Consul-shaped node: name, address, datacenter,
  region, metadata, status, and server-stamped times. The client list
  (decision 8) adds the generated node ID. The Quorus fleet fields
  are removed: CPU, memory, disk, bandwidth, packet loss, NAT, connection
  type, and supported services.
- [ ] Remove the `UpdateCapabilities` command. Reserve the removed protobuf
  numbers and messages, and add fixtures for old WAL entries and snapshots
  (decision 4).
- [ ] Reduce node status to the states Qraft sets, and drop the job-system
  status mapping if the fixtures allow.
- [ ] Move node registration, heartbeat, deregistration, and listing from
  `/api/v1/agents*` to Consul-aligned `/v1/` routes with the standard error
  envelope and identity headers (for example `GET /v1/catalog/nodes`). Use the
  paths of the client list's decision 5, so the node routes move only once.
  The service and check write paths move later, in the client list's Phase 1.
  Update `HttpCatalogClient`.
- [ ] Remove `/api/v1/info`, `/status`, and bare `/health`. Compose
  healthchecks and the documentation use `/health/live` or `/health/ready`.
- [ ] Delete the old agent DTO tests. Add node codec, replica-determinism, and
  legacy-fixture tests.

**Exit:** Red before green with mutations for the codec and fixture guards;
full suites.

### Phase 5. Configuration and version

- [ ] Remove `AppConfig`'s static default instance and its classpath defaults
  file `qraft-server.json` (moved from Phase 1).
- [ ] Replace `AppConfig`'s flattened string map with typed records parsed
  directly from the JSON document. The map holds `qraft.*` keys, with cluster
  nodes re-encoded as `name=host:port,...`. Keep identical validation errors
  and JSON paths.
- [ ] Have server and client configuration share one JSON parsing and
  validation helper where they duplicate it.
- [ ] Report the version from the build manifest instead of the `2.0-ext`
  default and the `applicationVersion` setting.
- [ ] Put a version in the manifest, and decide what it is (review of
  2026-10-05). The executable jar's manifest has none today: the shade step
  sets only the main class. The POM's version is `1.0-SNAPSHOT`, while the
  server reports `2.0-ext`. Add the implementation entries to the manifest,
  and choose the POM version that Qraft reports.
- [ ] Do the same for the client (review of 2026-10-05). `agent.version` in
  the client configuration is the same kind of setting as
  `applicationVersion`, and the Docker example sets it to `1.0.0`. The client
  reports the manifest's version too, and the setting is removed.

**Exit:** The existing configuration tests pass unchanged, apart from those
of the two removed version settings, plus tests for the version source in
both modes.

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
- [ ] Remove the Grafana panels built on `qraft_agents`, a metric the server
  never emits.
- [ ] Rework `docker/test-data` to use the Phase 4 node routes, or delete it in
  favour of the end-to-end suite. Delete the unreferenced `test-heartbeat.json`.
  `start-quick.*`'s `test` action and `docker/README.md` use the other files
  there.
- [ ] Delete `docker/test-data/nginx.conf` (review of 2026-10-05). Nothing
  references it; the load-balancer topology mounts `compose/nginx/nginx.conf`.
- [ ] Decide whether the nginx load-balancer topology stays, since clients
  rotate through their seeds themselves. Reduce `start.*` and `start-quick.*`
  to the remaining topologies.
- [ ] Find the cause of the one Docker failure on Jenkins (added 2026-10-05):
  `DockerAgentRecoveryTest.aKilledFollowerInstallsTheLeadersSnapshotAndThenHoldsTheLeadersHealthState`
  timed out after 90 seconds in build 2 of 2026-10-04 (`docs/JENKINS.md`),
  while the Phase 2 run on the development machine passed 30 of 30. An
  intermittent or machine-dependent failure is a defect
  (`PROJECT_STANDARDS.md` section 4.4).

**Exit:** `DockerDeploymentContractTest`, the Docker suite, and each remaining
start command work.

### Phase 7. Replace the Vert.x-shaped async layer

- [ ] Before any change in this phase, close the membership list's Step 4
  gate: record its mutation evidence against the source as Phase 6 left it,
  under the rules of that list's "Step 4 close-out gate". The tests it
  validates are then the regression net for the changes to `RaftNode` below.
- [ ] Replace `common.async.Future`, `Promise`, and `AsyncResult` with
  `CompletableFuture` and `CompletionStage` (11 production files import
  them, plus the runtime package itself).
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

What remains for this phase:

- [ ] Bring the documents above up to date with Phases 3 to 7:
  - the package and class names of Phase 3, in the design's section 1.1 table
    first, then the README, `PROJECT_STANDARDS.md` section 3, `TESTING.md`,
    `JENKINS.md`, and `docker/README.md`;
  - the log file names of `PROJECT_STANDARDS.md` section 6.2, and its note
    that both modes write the controller's log;
  - the version source, in `OPEN_SOURCE_USAGE.md` if Phase 5 changes it.
- [ ] Record the removals of Phases 4 to 7 in the feature validation and the
  Consul plan checklist.
- [ ] Update the administrative interface's plan and task list for Phase 4:
  the plan's section 4.1, the list's read APIs, its reserved path segments,
  and the development proxy lose the routes Phase 4 removes: `/api/v1/agents`,
  `/status`, and bare `/health`.
- [ ] Update the membership list for its resumption: the class names in its
  Step 5 contract, after Phases 3 and 5.
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
- The client's local API, the move of the server's `/v1/agent/*` paths, and
  DNS. The first two are in the client list; DNS gets a later list.
