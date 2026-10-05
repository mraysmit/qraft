# Task List: Single POM and Removal of the Quorus Leftovers

**Date:** 2026-10-04
**Status:** In progress. This is the current task list. It started on 2026-10-04 at the user's request, before the membership list's Step 4 close-out gate.
**Active work:** Phase 2A, intentional-error labelling, added 2026-10-04 at the user's direction before Phase 3. Phases 0 to 2 were done 2026-10-04.
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

### Phase 2A. Every intentional error is labelled as intentional in the log

**Problem (found 2026-10-04).** A reader of a test log cannot tell an
intentional ERROR from a real one.
- Production code logs the ERROR, and nothing on the line says that a test
  caused it.
- Injected faults are generic JDK exceptions created in 43 test files.
- Docker containers' logs are never checked.

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

  `config/logback-test.xml` writes `%intentional` right after `%-5level` in
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
- [ ] Inventory run: `mvn test`. Expected to fail widely; each failure lists
  the test's unlabelled errors. Record the list here.
- [ ] Convert every injected fault to `InjectedFault`, and declare every
  intentional error with `expect`. Fix the cause of any ERROR that is neither,
  and any production code that logs a failure without its exception. Replace
  `logExpectedFailure` and `@RemediationTest`.
- [ ] Docker suite:
  - collect each container's log under `logs/docker/<class>/`;
  - every ERROR in it must match an `IntentionalError` the class declares, and
    the test log reprints each one with its label;
  - any other ERROR fails the class.
- [ ] After each suite, check its log file: every ERROR line carries a label.
  This also covers anything logged after the last test class closed, which no
  window sees.
- [ ] Document the rules and how to read a test log in `PROJECT_STANDARDS.md`
  section 4.3 and `docs/TESTING.md`.

**Exit:** Default, end-to-end, and Docker suites green. Every ERROR line in
their logs carries an `INJECTED FAILURE` or `INTENTIONAL ERROR` label naming
its entry and test.

### Phase 3. Package layout follows the modes

- [ ] `dev.mars.qraft.agent` (client code) becomes `dev.mars.qraft.client`.
- [ ] `dev.mars.qraft.controller` becomes `dev.mars.qraft.server`. Rename
  "controller" in class names, log file names, and the default telemetry
  service name. Name the log files by mode: client mode currently writes
  `qraft-controller-server.log`.
- [ ] The Raft engine (`raft.api` and `controller.raft`) moves to `dev.mars.qraft.raft`.
- [ ] Replicated state (`distributedstate`, `catalog`, and `controller.state`)
  moves to `dev.mars.qraft.state`.
- [ ] Shared types (`Deadlines`, configuration resolver, `ServiceDefinition`,
  node types) move to `dev.mars.qraft.common`. The `catalog` and `agent`
  packages that today span two modules are gone.

**Exit:** Same tests as Phase 2A, changed only where they name a package, a
log file, or the telemetry service; the dependency test uses the new packages.

### Phase 4. Node model and API

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
  file `qraft-controller.json` (moved from Phase 1).
- [ ] Replace `AppConfig`'s flattened string map with typed records parsed
  directly from the JSON document. The map holds `qraft.*` keys, with cluster
  nodes re-encoded as `name=host:port,...`. Keep identical validation errors
  and JSON paths.
- [ ] Have server and client configuration share one JSON parsing and
  validation helper where they duplicate it.
- [ ] Report the version from the build manifest instead of the `2.0-ext`
  default and the `applicationVersion` setting.

**Exit:** The existing configuration tests pass unchanged, plus tests for the
version source.

### Phase 6. Docker and observability

- [ ] Keep one observability stack: OpenTelemetry Collector, Tempo,
  Prometheus, Loki, and Grafana.
- [ ] Delete the unreferenced ELK and Fluentd compose files.
- [ ] Delete the Promtail stack: `docker/logging/`, `docker-compose-loki.yml`,
  and the Promtail labels in the cluster compose files.
- [ ] Delete the four logging demo scripts (`.ps1` and `.sh`): demo-logging,
  log-extraction-demo, setup-logging, and simple-log-demo.
- [ ] Remove the Grafana panels built on `qraft_agents`, a metric the server
  never emits.
- [ ] Rework `docker/test-data` to use the Phase 4 node routes, or delete it in
  favour of the end-to-end suite. Delete the unreferenced `test-heartbeat.json`.
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
- [ ] Replace `controller.runtime.Future`, `Promise`, and `AsyncResult` with
  `CompletableFuture` and `CompletionStage` (11 production files import
  them, plus the runtime package itself).
- [ ] Replace `JavaRuntime` (an emulated event loop) and `WorkerExecutor` with
  explicitly owned executors: the Raft state loop and a bounded
  virtual-thread pool. Keep the `CallerContext` MDC and OpenTelemetry propagation.
- [ ] Replace the `JavaTestContext` and `JavaRuntimeExtension` test helpers
  with plain futures and bounded waits.
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
