# Running the Tests

## Prerequisites

- **JDK 27.** The build refuses older JDKs. Point `JAVA_HOME`, and your IDE's test runtime, at it.
- **Maven 3.9 or newer.**
- **Docker**, for the Docker suite only.
- Optional: the **Maven daemon**, `mvnd`. It takes the same arguments as `mvn`, and keeps Maven warm between
  runs, which saves most of the start-up cost of each small run.

In PowerShell, quote every `-D` argument, for example `"-Dtest=RaftNodeTest"`. Otherwise PowerShell splits
the argument at the dot.

## Run builds in a visible terminal, through `Tee-Object`

Run every build and test command in a terminal you can watch, and pipe its output through `Tee-Object`.
You see the progress live, and a log stays behind in `test-logs/`, which git ignores:

```powershell
mvn install 2>&1 | Tee-Object -FilePath test-logs/install.log
```

An assistant or script that starts a build for you must do the same: open a visible PowerShell window
that runs the command through `Tee-Object`, then read the results from the log. It must never run a build
hidden in the background:

```powershell
Start-Process pwsh -WorkingDirectory . -ArgumentList '-NoExit', '-Command',
    'mvn install 2>&1 | Tee-Object -FilePath test-logs/install.log'
```

## The three suites

| Suite | What it covers | Where | Time in tests* |
|---|---|---|---|
| **Default** | Unit and in-process integration tests: Raft on manual timers, gRPC on local ports, HTTP, storage, agent, and a runtime start-up test | every module | about 40 s (estimate) |
| **End-to-end** | Real server and agent processes that wait out real TTLs and a real leader change | `qraft-runtime`, tagged `@Tag("e2e")` | about 50 s |
| **Docker** | Real three-server clusters in containers: restarts, partitions, agents | `qraft-controller`, tagged `@Tag("docker")` | about 5.5 min |

\* Time spent running tests, measured 2026-09-29 before the end-to-end tests were separated. Compiling
and packaging come on top.

The default build excludes the end-to-end and Docker suites, through `test.excludedGroups` in
`qraft-runtime/pom.xml` and `qraft-controller/pom.xml`.

## While you work: run one test class

```bash
mvn test -pl qraft-controller -Dtest=RaftNodeMembershipTest
mvn test -pl qraft-controller "-Dtest=RaftNodeMembershipTest,MembershipServiceTest" -Dsurefire.failIfNoSpecifiedTests=false
```

`-pl` builds only that module, so the other modules must already be in your local repository: run
`mvn install` once first. Add `-Dsurefire.failIfNoSpecifiedTests=false` whenever `-Dtest` names classes
that some of the modules built do not have.

## Before you call a change done: `mvn install`

```bash
mvn install
```

This runs the default suite in every module, then the coverage gates: every package must keep at least 60%
line coverage (JaCoCo). The gates run in the `verify` phase, so **`mvn test` does not check coverage**, and
a change can pass `mvn test` but break `mvn install`. `mvn install` also packages
`qraft-runtime/target/qraft-runtime.jar`, which the Docker suite needs.

## When the change touches the runtime: the end-to-end and Docker suites

Run them when a change touches server or agent start-up, health checks and their TTLs, ports,
configuration files, the poms, or the Dockerfiles. They aren't needed for every change. Run them straight
after `mvn install`, together:

```bash
mvn test -pl qraft-controller,qraft-runtime "-Dgroups=docker,e2e" "-Dtest.excludedGroups="
```

Or only the end-to-end suite, which needs no Docker:

```bash
mvn test -pl qraft-runtime -Dgroups=e2e "-Dtest.excludedGroups="
```

- Docker must be running. The suite builds the `qraft-runtime:test` image once, from
  `qraft-runtime/target/qraft-runtime.jar`, and refuses a jar older than the sources. So run `mvn install`
  first. `docker/build-runtime.ps1` (or `.sh`) only packages that jar, so you don't need it after
  `mvn install`.
- Both options are needed: `-Dgroups` selects the suites, and the empty `-Dtest.excludedGroups=` lifts
  the default exclusion.
- Don't run another Maven build in the same working tree while the Docker suite runs. It overwrites the
  classes and jar under test.

## Deploying to Docker

There are two ways onto Docker, and they build differently.

**1. The Docker tests deploy themselves.** You never deploy by hand before running them. After `mvn install`:

1. `SharedDockerCluster` checks that `qraft-runtime/target/qraft-runtime.jar` exists and is newer than the
   sources.
2. It builds the `qraft-runtime:test` image once per run, with
   `docker compose -f qraft-controller/src/test/resources/docker-compose-build-image.yml build`.
3. Each test class starts its clusters from the compose files in `qraft-controller/src/test/resources`,
   using the `docker/config/*-acceptance` configs, and removes them when it finishes.

**2. A cluster you start by hand**, to try something out or watch it run. Run these from `docker/`:

```powershell
.\start.ps1 cluster                # one server:                   http://localhost:8080
.\start.ps1 controllers            # three servers, load balancer: http://localhost:8080 (8081-8083 direct)
.\start-quick.ps1 cluster 3node    # three servers: http://localhost:8081-8083 (compose/docker-compose-cluster.yml)
.\start-quick.ps1 cluster 5node    # five servers:  http://localhost:8081-8085 (compose/docker-compose-5node.yml)
.\start.ps1 status                 # the running Qraft containers
.\start.ps1 stop                   # stop every cluster either script started
```

Only one cluster runs at a time: they share container names, so stop one before starting another.
`start-quick.ps1 test` sends to `http://localhost:8080`, so it needs `start.ps1 cluster` or `controllers`.
Each `cluster` or `controllers` command first runs `docker/build-runtime.ps1`, which packages the runtime
jar again with `package -pl qraft-runtime -am -DskipTests`, then starts the containers with
`docker compose ... up -d` and returns. Follow a cluster's logs with
`docker compose -f compose/<file>.yml logs -f`. `start.sh` and `start-quick.sh` are the shell
equivalents.

**The whole process for a runtime change:**

1. `mvn install`, which runs the default suite and the coverage gates, and packages the runtime jar.
2. `mvn test -pl qraft-controller,qraft-runtime "-Dgroups=docker,e2e" "-Dtest.excludedGroups="`, the
   end-to-end and Docker suites, which build their own image from that jar.
3. Optional: `docker\start.ps1 controllers`, to watch a hand-started cluster. It repackages the jar first,
   which is redundant straight after step 1 but harmless.

## Making runs faster

- Name only the module and classes you need (`-pl`, `-Dtest`) while you work, and keep `mvn install` for
  the end.
- Use `mvnd` in place of `mvn` for repeated small runs.
- Surefire runs the classes of a module one after another in one JVM. `-DforkCount=2` runs them in two
  JVMs. It is not the default, because it has not been tried on this suite yet: the in-memory Raft
  simulator keeps static state, which is safe across JVMs but not across threads.

## Results

- Test results: `<module>/target/surefire-reports/`. One XML and one text file per class, with the time
  each class took.
- Coverage: `<module>/target/site/jacoco/index.html`, written by `mvn install`.

## Rules for tests

- **No Mockito.** Use real objects, or small hand-written fakes such as `HeldRaftTransport`.
- **No flaky tests.** A test that fails only sometimes is a defect. Find the race, check whether production
  code has it too, and make the test deterministic.
- **Raft timing uses manual timers** (`ManualRaftCluster`, `ManualRaftTimers`). A test fires an election or
  heartbeat itself, and doesn't wait for one on the clock.
- **Real-time waits are rare and bounded.** They belong only to the runtime end-to-end tests and the
  Docker suite, which wait out real TTLs and elections. Everywhere else, a wait's bound exists only to
  report a hang.
