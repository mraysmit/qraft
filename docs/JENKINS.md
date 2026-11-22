# Qraft on Jenkins

**Last updated:** 2026-11-21

The [Qraft job](http://192.168.137.32:8080/job/Qraft/) runs every test suite on Linux, against the pushed `main`
branch. This page describes the job and how to use it. The record of builds 1 to 87 and of the local runs that stood in
for builds is archived in
[`JENKINS_BUILD_HISTORY_2026-10-03_to_2026-11-20.md`](archive/JENKINS_BUILD_HISTORY_2026-10-03_to_2026-11-20.md).

## Job configuration

- **Type:** Pipeline, script from SCM: Git, `https://github.com/mraysmit/qraft.git`, branch `*/main`, script path
  `Jenkinsfile`, lightweight checkout.
- **Source:** the pushed `main` only. Local changes and unpushed commits are not in a build. The Checkout stage
  deletes the workspace, checks out the revision that supplied the `Jenkinsfile` and prints it (`git rev-parse HEAD`).
- **Node:** `agent { label 'linux' }`. The Jenkins controller's built-in node carries `linux` and its existing
  `peegeeq-linux` label. It needs Bash, Git, curl, Maven 3.9 or newer, and a running Docker engine with Docker Compose.
- **Trigger:** manual only, with no recurring trigger. Concurrent builds are disabled.
- **Retention:** 20 builds, and the artifacts of the latest 10.
- **Caches:** the workspace is `/var/lib/jenkins/workspace/Qraft`. The JDK is cached in
  `<workspace>@tools/sapmachine-jdk-<version>` and the Maven repository in `<workspace>@repository`; both survive the
  fresh checkout. The pipeline sets its own `JAVA_HOME` and `PATH` and changes no other job's Java.
- **The JDK is the pipeline's own.** No package on the server provides it, so updating the server's packages or
  Jenkins does not update it. A new JDK takes a change to the `Jenkinsfile`: the version in `JAVA_HOME`, `PATH`, the
  download URL and the version check, and the release's SHA-256. The version in the directory name makes the new JDK
  install beside the old one; the old directory can then be removed by hand.
- **When it runs:** after the owner has committed and pushed; a build sees only what is on `main`. It is started and
  read through the API below. How an AI assistant takes part is in [`AGENTS.md`](../AGENTS.md).

## Parameters

| Parameter | Values | Default | Meaning |
|---|---|---|---|
| `MUTATION_TESTING` | `off`, `dry-run`, `run` | `off` | `off` runs the three suites. `dry-run` and `run` run PIT on one batch instead of them. |
| `MUTATION_BATCH` | `01` to `05` | `01` | The batch of at most ten mutants: the POM profile `pit-batch-<number>`. |

PIT is on ice at the owner's decision ([TESTING.md](TESTING.md#mutation-testing-with-pit)): leave both parameters at
their defaults.

## Stages

1. **Checkout**, as above.
2. **Toolchain and Docker.** On the first build after a JDK change, downloads SapMachine JDK 27.0.1.1 from its GitHub
   release and checks its pinned SHA-256 checksum. Fails unless the JDK's `release` file names 27.0.1.1. Then prints the
   versions of `java`, `javac`, `mvn`, `docker` and `docker compose`.
3. **PIT mutation testing**, only when `MUTATION_TESTING` is not `off`. The suites below are skipped. Its report is
   copied to `reports/pit/`.
4. **Default tests and coverage gates**, which also runs the frontend's Vitest tests and packages `target/qraft.jar`,
   the jar the Docker tests build their image from. Its Surefire reports go to `reports/default/` and its JaCoCo
   reports to `coverage/`.
5. **End-to-end tests**, reports to `reports/e2e/`.
6. **Docker cluster tests**, `docker` and `slow` together (`GrpcRaftKeepaliveTest` is tagged `slow`), reports to
   `reports/docker/`.

The commands as the `Jenkinsfile` runs them: default, end-to-end, Docker, then PIT, which passes
`-Dpit.dryRun=true` for `dry-run` only.

```bash
mvn -B --fail-at-end -Dstyle.color=never -Dmaven.repo.local="$WORKSPACE@repository" clean install
mvn -B -Dstyle.color=never -Dmaven.repo.local="$WORKSPACE@repository" test \
    -Dgroups=e2e -Dtest.excludedGroups= -DskipVitest=true
mvn -B -Dstyle.color=never -Dmaven.repo.local="$WORKSPACE@repository" test \
    '-Dgroups=docker|slow' -Dtest.excludedGroups= -DskipVitest=true
mvn -B -Dstyle.color=never -Dmaven.repo.local="$WORKSPACE@repository" "-Pmutation-testing,pit-batch-$batch" \
    clean test-compile org.pitest:pitest-maven:mutationCoverage
```

Each command's output goes through `tee` to the console and to `logs/qraft-<stage>-<UTC time>-<build>.log`, and the
step exits with Maven's status. The end-to-end and Docker stages remove `target/surefire-reports` before they run, so
each stage's reports are its own. A failed default stage stops the build; the end-to-end and Docker stages run inside
`catchError`, so an end-to-end failure still lets the Docker stage run.

The Docker stage creates and removes Docker networks, which changes the host's network interfaces. A browser that runs
directly on this node can lose a page load when that happens; the PeeGeeQ job runs its browser tests in a container
for that reason.

## Time limits

- The whole build: 60 minutes.
- The PIT stage: 20 minutes. A batch that runs longer has gone wrong and is stopped.
- The JDK download: 600 seconds, with three retries.
- The helper script below waits 90 minutes by default before it stops following a build.

## Results and artifacts

- **JUnit:** `reports/**/TEST-*.xml`, published under **Test Result**, for every stage that copied its reports.
- **Archived:** `logs/**`, `reports/**`, `coverage/**`, and any JVM crash files, `**/hs_err_pid*.log` and
  `**/replay_pid*.log`. The Docker tests' container archives are under `logs/docker/`.

## Linux container test fixtures

Qraft's image runs as the non-root user `qraft`, UID 1001. Java creates temporary files on Linux readable by their
owner only, so a file the Jenkins user writes cannot be read by a container that bind-mounts it. On POSIX file
systems, `SharedDockerClusterFixture.makeContainerConfigReadable` makes each file it bind-mounts world-readable (owner
read and write, group and others read): the storage-lock contender's configuration, a detached server's or client's
configuration, and a detached client's extra files, such as a test token file. The mounts stay read-only, and the image
keeps its normal user.

## Starting a build and reading its results

Authentication is HTTP Basic with the Jenkins user name and an API token ([TESTING.md](TESTING.md#jenkins-access)).
The token is in `.env/jenkins-api-token.txt`. The `.env/` folder is git-ignored. Never print the token or write it
anywhere.

| Step | Request |
|---|---|
| Start | `POST /job/Qraft/buildWithParameters?MUTATION_TESTING=off`. A plain `POST /job/Qraft/build` answers 400, because the job has parameters. |
| Find the build | The answer's `Location` header names the queue item; `GET <item>/api/json` until it has `executable.number`. |
| Last build | `GET /job/Qraft/lastBuild/api/json` |
| Follow | `GET /job/Qraft/<n>/logText/progressiveText?start=<offset>`, using `X-Text-Size` and `X-More-Data` |
| Result | `GET /job/Qraft/<n>/api/json?tree=result,duration` |
| Test totals | `GET /job/Qraft/<n>/testReport/api/json` |
| Console | `GET /job/Qraft/<n>/consoleText` |
| Artifacts | `GET /job/Qraft/<n>/artifact/*zip*/archive.zip` |

The helper `.env/qraft-jenkins-build.ps1` makes every request above except the last-build and test-totals ones. Run
it in a visible terminal ([TESTING.md](TESTING.md#run-builds-in-a-visible-terminal-through-tee-object)):

```powershell
.\.env\qraft-jenkins-build.ps1    # -MutationTesting off (default), -PollSeconds 15, -TimeoutMinutes 90
```

It starts the build, follows it, and saves it under `logs/jenkins/Qraft-<n>/`: `console.log` while it runs, then the
whole console, `archive.zip` and its contents in `archive/`. It writes its progress to `logs/jenkins/current-build.json`.
If it stops early, the build keeps running on Jenkins; nothing is cancelled. It reads the token through
`.env/jenkins-tools.ps1`, which also provides `Invoke-QraftJenkins` for single API calls and `Invoke-QraftJenkinsShell`,
which runs a shell command on the Jenkins host through the script console; use that one for read-only checks.

## Auditing a build

A green result and its test totals do not accept a build. Audit the console, every archived log, the container
archives and the test reports under [TESTING.md, "Intentional error flags and log
auditing"](TESTING.md#intentional-error-flags-and-log-auditing) before accepting it.

## Troubleshooting

- **A start that times out may still have queued a build.** Ask for the last build and the queue before starting
  another.
- **A build that fails before any test ran is infrastructure:** the checkout or the `Jenkinsfile` fetch. So is a
  Docker stage whose image build cannot reach Docker Hub. Find the cause on the host; a rerun says nothing about the
  code.
- **Network stalls to GitHub or Docker Hub** have shown as hung `git fetch` calls, slow downloads and failed DNS
  lookups. Check the gateway's DNS (192.168.137.1) and its route to GitHub before suspecting the code.
- **A Java upgrade on the Jenkins host** under the running Jenkins makes its steps fail with `Failed to exec spawn
  helper`. Jenkins must be restarted on the host between builds; that is the owner's to do.
- **Docker losing an image snapshot** ("parent snapshot ... does not exist") is a fault of the Docker daemon: check its
  version, storage driver and image store, and investigate. The tests do not retry a failed image build.
- **Native crashes (`SIGSEGV`) in unrelated JVMs or the Docker CLI** point at the host, not at Qraft.
  Once they were the host's faulty memory, which `memtester` confirmed.
- **When Jenkins cannot be reached**, run the three suites locally with the commands above. On Windows, `mvn.cmd` runs
  through `cmd.exe`, which reads `|` as a pipe, so pass `-Dgroups=docker,slow`; keep `-DskipVitest=true` on the
  end-to-end and Docker suites.
