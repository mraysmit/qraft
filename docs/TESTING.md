# Running the Tests

## Prerequisites

- **JDK 27.** The build refuses older JDKs. Point `JAVA_HOME`, and your IDE's test runtime, at it.
- **Maven 3.9 or newer.**
- **Docker**, for the Docker suite only.
- **PowerShell 7**, for the UTF-8 log-capture commands below.
- Optional: the **Maven daemon**, `mvnd`. It takes the same arguments as `mvn`, and keeps Maven warm between
  runs, which saves most of the start-up cost of each small run.

In PowerShell, quote every `-D` argument, for example `"-Dtest=RaftNodeTest"`. Otherwise PowerShell splits
the argument at the dot.

## Jenkins access

- **URL:** <http://192.168.137.32:8080/>
- **Username:** `mraysmit`
- **API token:** stored locally in `.env/jenkins-api-token.txt`, relative to the repository root.

For Jenkins API requests, use HTTP Basic authentication with the username and API token as the password.
Read the token from the local file; keep its value out of documentation and logs.
Authentication was verified on 2026-10-03: `GET /whoAmI/api/json` returned HTTP 200 with
`authenticated: true`, `name: mraysmit`, and `anonymous: false`.

### Run every suite on Jenkins

Open the [Qraft job](http://192.168.137.32:8080/job/Qraft/) and select **Build Now**.
The job loads the root [`Jenkinsfile`](../Jenkinsfile) and checks out its configured branch from
`https://github.com/mraysmit/qraft.git` on the `linux` node. The Linux fixture permission fix is included
in the published CI branch:

1. Verify Git, Maven, Docker, and Docker Compose. Download a checksum-verified SapMachine JDK 27
   into the job's `@tools` directory on its first run.
2. Run `mvn -B --fail-at-end -Dstyle.color=never clean install`: the default tests,
   coverage gates, and a freshly packaged runtime jar.
3. Run the tests tagged `e2e`, with `test.excludedGroups` cleared.
4. Run the tests tagged `docker` or `slow`, with `test.excludedGroups` cleared.
   Together with step 3, this covers every test the default build excludes; the Docker fixtures
   build their own image.

The suites run sequentially, and concurrent Qraft builds are disabled. An end-to-end failure still
allows the Docker suite to run, while a failed default build stops the dependent suites. Maven's exit
code is preserved through `tee`, and the build has a one-hour timeout.

Jenkins publishes the JUnit results and archives `logs/`, separate `reports/default`, `reports/e2e`,
and `reports/docker` directories, and the default suite's JaCoCo HTML/XML reports under `coverage/`.
It keeps 20 builds and artifacts from the last 10. Dependencies are cached in the job's `@repository`
directory, outside the source workspace; each build starts with a fresh checkout.

The job uses **Pipeline script from SCM**, **Git**, branch `*/main`, and script path `Jenkinsfile`.
Builds are started manually; no recurring trigger is configured. See [JENKINS.md](JENKINS.md) for the
job configuration, Linux fixture requirements, and run results. The memory corruption found in the
Jenkins VM on 2026-10-03 is resolved: the job was recreated on the current server on
2026-10-04, and JENKINS.md keeps the investigation as a historical record. One Docker test is still
red on the current server (JENKINS.md, "Current server setup").

## Run builds in a visible terminal, through `Tee-Object`

Run every build and test command in a terminal you can watch, and pipe its output through `Tee-Object`.
You see the progress live, and each run leaves a timestamped UTF-8 log in the
central `logs/` directory, which git ignores. This is the same command convention
as [PROJECT_STANDARDS.md, section 7](PROJECT_STANDARDS.md#7-standard-maven-verification-command):

```powershell
New-Item -ItemType Directory -Force .\logs | Out-Null
mvn -B "-Dstyle.color=never" install 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
$qraftBuildExitCode = $LASTEXITCODE
if ($qraftBuildExitCode -ne 0) { throw "Maven failed with exit code $qraftBuildExitCode" }
```

For every command below, create `logs/` first and check `$LASTEXITCODE` immediately
after the pipeline using the final two lines above. A successful `Tee-Object`
does not prove Maven passed. The shared Logback test configuration also writes
`logs/qraft-maven-tests-<timestamp>.log`; that application log complements the
captured Maven output.

The terminal is the VS Code integrated terminal. An assistant must not open separate PowerShell windows,
and must never run a build hidden in the background. It gives you the command to run in the VS Code
terminal, through `Tee-Object` as above, and then reads the results from the log.

## The three suites

| Suite | What it covers | Where | Time in tests* |
|---|---|---|---|
| **Default** | Unit and in-process integration tests: Raft on manual timers, gRPC on local ports, HTTP, storage, agent, and a runtime start-up test | `src/test` | about 40 s (estimate) |
| **End-to-end** | Real server and agent processes that wait out real TTLs and a real leader change | tagged `@Tag("e2e")` | about 50 s |
| **Docker** | Real three-server clusters in containers: restarts, partitions, agents | tagged `@Tag("docker")` | about 5.5 min |

\* Time spent running tests, measured 2026-09-29 before the end-to-end tests were separated. Compiling
and packaging come on top.

The default build excludes the end-to-end and Docker suites, through `test.excludedGroups` in
`pom.xml`.

## While you work: run one test class

```powershell
mvn -B "-Dstyle.color=never" test "-Dtest=RaftNodeMembershipTest" 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
```

For more than one class:

```powershell
mvn -B "-Dstyle.color=never" test "-Dtest=RaftNodeMembershipTest,MembershipServiceTest" 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
```

Qraft is one Maven module, so there is no `-pl` and nothing to install first.

## Before you call a change done: `mvn install`

```powershell
mvn -B "-Dstyle.color=never" install 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
```

This runs the default suite, then the coverage gates: every package must keep at least 60%
line coverage (JaCoCo). The gates run in the `verify` phase, so **`mvn test` does not check coverage**, and
a change can pass `mvn test` but break `mvn install`. `mvn install` also packages
`target/qraft.jar`, the executable jar the Docker suite needs.

**One executable jar.** Until 2026-10-03 `qraft-controller` shaded its
dependencies into its own jar as well. Without `clean`, that step started from the previous shaded jar and
kept an old dependency's classes, and `qraft-runtime.jar` inherited them. The unit tests ran against the
new version, so nothing failed: only the packaged jar, and so the Docker suite and any deployment, ran the
old one. It was found when RaftLog went from 1.4.0 to 1.4.1. A plain `mvn install` now packages the
versions the poms name; `clean` is not needed after a dependency change. To check what a jar holds:

```bash
unzip -p target/qraft.jar META-INF/maven/io.github.mraysmit/raftlog-core/pom.properties
```

## When the change touches the runtime: the end-to-end and Docker suites

Run them when a change touches server or agent start-up, health checks and their TTLs, ports,
configuration files, the poms, or the Dockerfiles. They aren't needed for every change. Run them straight
after `mvn install`, together:

```powershell
mvn -B "-Dstyle.color=never" test "-Dgroups=docker,e2e" "-Dtest.excludedGroups=" 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
```

Or only the end-to-end suite, which needs no Docker:

```powershell
mvn -B "-Dstyle.color=never" test "-Dgroups=e2e" "-Dtest.excludedGroups=" 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
```

- Docker must be running. The suite builds the `qraft-runtime:test` image once, from
  `target/qraft.jar`, and refuses a jar older than the sources. So run `mvn install`
  first. `docker/build-runtime.ps1` (or `.sh`) only packages that jar, so you don't need it after
  `mvn install`.
- Both options are needed: `-Dgroups` selects the suites, and the empty `-Dtest.excludedGroups=` lifts
  the default exclusion.
- Don't run another Maven build in the same working tree while the Docker suite runs. It overwrites the
  classes and jar under test.

## Deploying to Docker

There are two ways onto Docker, and they build differently.

**1. The Docker tests deploy themselves.** You never deploy by hand before running them. After `mvn install`:

1. `SharedDockerCluster` checks that `target/qraft.jar` exists and is newer than the
   sources.
2. It builds the `qraft-runtime:test` image once per run, with
   `docker compose -f src/test/resources/docker-compose-build-image.yml build`.
3. Each test class starts its clusters from the compose files in `src/test/resources`,
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
jar again with `package -DskipTests`, then starts the containers with
`docker compose ... up -d` and returns. Follow a cluster's logs with
`docker compose -f compose/<file>.yml logs -f`. `start.sh` and `start-quick.sh` are the shell
equivalents.

**The whole process for a runtime change:**

1. `mvn install`, which runs the default suite and the coverage gates, and packages the runtime jar.
2. `mvn test "-Dgroups=docker,e2e" "-Dtest.excludedGroups="`, the
   end-to-end and Docker suites, which build their own image from that jar.
3. Optional: `docker\start.ps1 controllers`, to watch a hand-started cluster. It repackages the jar first,
   which is redundant straight after step 1 but harmless.

## Making runs faster

- Name only the classes you need (`-Dtest`) while you work, and keep `mvn install` for
  the end.
- Use `mvnd` in place of `mvn` for repeated small runs.
- Surefire runs the test classes one after another in one JVM. `-DforkCount=2` runs them in two
  JVMs. It is not the default, because it has not been tried on this suite yet: the in-memory Raft
  simulator keeps static state, which is safe across JVMs but not across threads.

## Results

- Test results: `target/surefire-reports/`. One XML and one text file per class, with the time
  each class took.
- Coverage: `target/site/jacoco/index.html`, written by `mvn install`.

## Rules for tests

- **No Mockito.** Use real objects, or small hand-written fakes such as `HeldRaftTransport`.
- **No flaky tests.** A test that fails only sometimes is a defect. Find the race, check whether production
  code has it too, and make the test deterministic.
- **Raft timing uses manual timers** (`ManualRaftCluster`, `ManualRaftTimers`). A test fires an election or
  heartbeat itself, and doesn't wait for one on the clock.
- **Real-time waits are rare and bounded.** They belong only to the runtime end-to-end tests and the
  Docker suite, which wait out real TTLs and elections. Everywhere else, a wait's bound exists only to
  report a hang.
