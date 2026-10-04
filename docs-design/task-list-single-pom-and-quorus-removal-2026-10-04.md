# Task List: Single POM and Removal of the Quorus Leftovers

**Date:** 2026-10-04
**Status:** Proposed. Starts after the membership list's Step 4 close-out gate; membership Step 5 resumes on the new layout.
**Followed by:** [`task-list-consul-style-client-2026-10-04.md`](task-list-consul-style-client-2026-10-04.md)
**Active work:** Phase 2. Phase 1 was done 2026-10-04. The list started at the user's request before the membership Step 4 close-out, whose mutation evidence will be recorded against the refactored source.
**Related:** [`task-list-raft-membership-changes-2026-09-29.md`](task-list-raft-membership-changes-2026-09-29.md) (active), [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md)
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md), section 2.4: inherited components must not remain without a clear role

## 1. Goal

Ship Qraft as what it now is: one Maven project, one jar, two runtime modes,
with nothing left of Quorus's file-transfer fleet model, its Vert.x-shaped
runtime layer, or its unused tooling.

## 2. Decisions (confirm before Phase 1)

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

- Phases 1 to 3 change no behaviour. Evidence: the same tests, by name and
  count less deleted tests, pass before and after.
- Phases 4 to 7 change behaviour: red before green, with recorded mutations for
  safety guards.
- Each phase ends with `mvn install`; phases touching the runtime, Docker, or
  async code also run the end-to-end suite and the Docker suite on a fresh image.
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
  - **Not yet verified.** A `mvn clean install` with the pin is still needed to
    confirm that the jar's third-party list matches the baseline exactly.
- **Found for Phase 3.** The jar has always shipped the controller's
  `logback.xml`, so client mode writes `qraft-controller-server.log`. Phase 3
  names the log files by mode.

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

**Exit:** Same tests as Phase 2; the dependency test uses the new packages.

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
  paths of the client list's decision 5, so the client-to-server protocol
  changes only once. Update `HttpCatalogClient`.
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

**Exit:** `DockerDeploymentContractTest`, the Docker suite, and each remaining
start command work.

### Phase 7. Replace the Vert.x-shaped async layer

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

- [ ] Rewrite the design's section 1.1 as a package map, and update:
  - the README;
  - `PROJECT_STANDARDS.md` section 3;
  - `TESTING.md` and `JENKINS.md`;
  - `docker/README.md`;
  - the version sources in `OPEN_SOURCE_USAGE.md`.
- [ ] Make the event architecture's `qraft-events` a package, not a module.
- [ ] Record the removals in the feature validation and the Consul plan checklist.
- [ ] Run the final audits:
  - prohibited frameworks;
  - environment-variable configuration;
  - the timeout API;
  - source headers;
  - `git diff --check`;
  - a search for `quorus`, `job`, `transfer`, `fleet`, and `/api/v1`.
- [ ] Archive this list and resume membership Step 5.

## 5. Out of scope

- New features: key/value completion, consistency modes, sessions, tenancy, security.
- The gRPC `DistributedStateService`: the key/value completion list decides
  whether `/v1/kv` replaces it.
- The client's local API, the move of the server's `/v1/agent/*` paths, and
  DNS. The first two are in the client list; DNS gets a later list.
