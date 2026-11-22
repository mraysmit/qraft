# Qraft Project Standards

**Last updated:** 2026-11-22

## 1. Purpose

This document holds the rules for Qraft's code and tests: architecture, testing, configuration, logging, the
administrative interface, dependencies and code quality. They apply to all of Qraft's code unless a package documents
a stricter rule. How to run and audit the tests is in [TESTING.md](TESTING.md); the rules for an assistant working in
this repository are in [AGENTS.md](../AGENTS.md).

Qraft is a Consul-style service discovery and distributed coordination platform. Features and implementation choices
support that product direction, and code with no role in it is removed.

## 2. Architecture

### 2.1 Java platform

- Use Java 27 or newer.
- Prefer standard Java APIs and Java 27 concurrency facilities.
- Vert.x is prohibited.
- Do not introduce compatibility wrappers that reproduce Vert.x APIs without a clear project-level abstraction.
- Blocking operations should use bounded executors or virtual threads as appropriate to the workload.
- Shared mutable state must have an explicit ownership and serialization model.
- Asynchronous contracts use standard `CompletableFuture` or `CompletionStage` APIs.
- Non-async future callbacks can run on a completing thread or a thread registering or helping complete a callback.
  Blocking work must not run inline on a thread the code does not own; hand it to an executor owned for that work.
- Bound asynchronous waits with `dev.mars.qraft.common.concurrent.Deadlines`, never `CompletableFuture.orTimeout` or
  `completeOnTimeout`, which complete on one JVM-wide delay thread.

### 2.2 Network protocols

- Retain gRPC for server-to-server Raft communication.
- Use the JDK HTTP stack for HTTP endpoints and clients unless an approved requirement demonstrates that it is
  insufficient.
- Transport implementations must provide explicit startup, shutdown, timeout, and failure behavior.
- Transport callbacks must propagate failures to their returned future or completion stage.

### 2.3 Raft execution

- Raft state transitions must execute through the dedicated state loop.
- Storage mutations must preserve their required ordering.
- Configured worker-pool sizes and queue bounds must be honored.
- Asynchronous operations must complete successfully or exceptionally on every code path.
- Resource shutdown must wait for active work or fail with a bounded timeout.
- The server composition root owns `RaftStateLoop` and `BlockingTaskExecutor`. Stop the service, then drain blocking
  work while the state loop can still deliver its completions, then stop the loop. The blocking executor bounds both
  active work and queued work and rejects overload through failed futures.
- State-loop work, timers, blocking work and delivered completions preserve the submitting `CallerContext` MDC and
  OpenTelemetry context, then restore the executing thread's previous context.
- Tests must cover election, replication, storage recovery, snapshot installation, network failure, and shutdown
  behavior.

### 2.4 Feature scope

- New features must contribute directly to service discovery, health checking, distributed state, namespaces, cluster
  membership, Raft consensus, or operational observability.
- Code is not kept merely because it exists. Before deleting code, confirm that no feature or supported public contract
  depends on it.

## 3. Package boundaries

Qraft is one Maven project that builds one executable jar. Its code is divided into layers of packages:

- Raft (`dev.mars.qraft.raft`) owns consensus implementation, contracts, transport, storage adapters, and metrics; it
  may use only Common.
- State (`dev.mars.qraft.state`) owns the deterministic state host, commands, codecs, and catalog projections; it may
  use Raft and Common.
- Common (`dev.mars.qraft.common`) owns shared models, configuration helpers, deadlines, caller-context propagation,
  and the state-loop and blocking executors; it uses no other Qraft layer.
- Client (`dev.mars.qraft.client`) implements the Java 27 discovery client and may use only Common.
- Server (`dev.mars.qraft.server`) composes Raft and State, the HTTP API, telemetry, and lifecycle; it may use
  Common, Raft, and State.
- Runtime (`dev.mars.qraft.runtime`) holds the executable entry point, `QraftRuntimeApplication`, which selects server
  mode or client mode, and the command-line tools. It may use every layer and owns no domain logic.
- A layer must not depend on implementation details from a higher-level layer. `PackageDependencyTest` enforces the
  allowed direction.
- Shared abstractions belong in the lowest layer that can own them without creating a circular dependency.

## 4. Testing

### 4.1 Test-driven development

- Every change to behavior, every defect correction, and every migration is test-first.
- Write the failing behavioral test first, run it, and see it fail for the reason the change addresses, before any
  production code changes.
- Implement the smallest production change needed to satisfy the test.
- Refactor only after the behavioral test passes.
- Every concurrency or lifecycle defect must receive a regression test.
- Tests must have bounded waits and must fail rather than hang indefinitely.

### 4.2 Test doubles

- Name test helper classes with a `Helper` suffix and test fixture classes with a `Fixture` suffix.
- Apply these names to nested test helpers and doubles as well as standalone source files. Test support annotations
  and fault definitions must also identify their helper or fixture role.
- Their class Javadoc must identify their test support role and explain how tests use them. Subprocess fixtures must
  name the tests that launch them and state where the assertions live.
- Mockito is prohibited.
- Do not add Mockito dependencies, imports, extensions, clients, configuration, examples, or generated test code.
- Do not substitute another mocking framework to evade this rule.
- Test observable behavior with real implementations, protocol-level fixtures, or lightweight purpose-built fakes such
  as `HeldRaftTransportFixture`.
- External adapters should exercise serialization, transport, response parsing, failure behavior, and cleanup.

### 4.3 Test isolation

- A test must release executors, servers, channels, temporary storage, and other resources that it creates.
- Asynchronous setup and teardown must be awaited with explicit timeouts.
- Tests must not depend on execution order.
- A test that writes a port into a configuration before the server that binds it starts takes the port from
  `dev.mars.qraft.testing.TestPortsHelper.freePort()`, which never hands out the same port twice in one JVM. Ports are
  unique only within a JVM, so tests run in one forked JVM.
- A test reads a Logback `ListAppender`'s events through `dev.mars.qraft.testing.LogCapturesHelper.snapshot`, which
  copies the list under the appender's lock, never by streaming `appender.list` itself: a thread still logging into it
  makes the stream fail with a `ConcurrentModificationException`. A capture that replaces its list with a
  `CopyOnWriteArrayList` is safe to read directly.
- A test that stops a gRPC server leaves no connection half made. A call that fails before it is sent, such as one with
  a deadline already passed, still makes its channel start connecting, and stopping the server while it accepts that
  connection logs Netty's `Failed to initialize a channel`. Wait until the channel is ready, as
  `GrpcRaftServerTest.startServerAndConnect` does, or complete a call, before the test can end.
- A Docker test that kills a server and starts it again first calls
  `DockerHealthApiHelper.awaitFirstSnapshotOnEveryServer`. A server killed while it writes its first snapshot restarts
  from its log, and one killed later restarts from its snapshot; the wait keeps every run on the second path, so
  timing does not choose. The first path has its own tests, which place the kill exactly.

### 4.4 Deterministic tests

Flaky tests are not tolerated. An intermittent failure is a defect: find the race, check whether the same race exists
in production code, and fix both. Rerunning is not a fix.

- Time-dependent behavior is driven by injected clocks and schedulers, never by wall-clock sleeps. Raft timing uses
  manual timers (`ManualRaftClusterFixture`, `ManualRaftTimersHelper`): a test fires an election or a heartbeat
  itself.
- Real-time waits belong only to the end-to-end and Docker suites, which wait out real TTLs and elections. Everywhere
  else, a wait's bound exists only to report a hang.
- A test must not assert a wall-clock upper bound, such as "completes within 1 s". To prove that an operation does not
  wait for something, hold that thing with a latch that is released only after the operation has returned.
- Absence is proven exactly where possible, for example by showing that nothing was proposed after a synchronous step,
  rather than by waiting and observing nothing.
- Liveness waits, including future `get` timeouts, polling deadlines, and request timeouts on paths expected to
  succeed, are generous, at least 10 seconds for in-process work and 5 seconds for local HTTP. They only bound failure
  diagnosis and must never be the thing that fails under machine load.
- Callbacks are registered before the operation they observe can complete, or the test must not depend on which thread
  runs them.
- A test that asserts which thread ran a callback never blocks in `join` or `get` on the same future before the
  callback has run: a blocked thread helps complete the future and may run the callback itself.
- A new or changed concurrency test is run repeatedly before the change is complete.

### 4.5 Errors in test logs

- Every error a test causes on purpose is declared and flagged in the log. A test injects a failure with
  `InjectedFaultFixture`, or declares an entry of `IntentionalErrorFixture` with `IntentionalErrorsHelper.expect`. The
  log line then carries `*** INJECTED FAILURE: <entry>, injected by <test> ***` or
  `*** INTENTIONAL ERROR: <entry>, caused by <test> ***` after its level.
- A declaration names one specific failure: its logger, level and message, and where needed its exception. A broader
  declaration that would also cover an unrelated failure is not allowed.
- An ERROR without a flag, or an exception logged at any level without one, fails the test that was running and is a
  defect to investigate. It is never filtered, suppressed, lowered in severity, or excused.
- A helper JVM and a container follow the same rule.
- A run is accepted only after its retained Maven output, application logs, helper-JVM logs and Docker archives have
  been read and hold no unflagged error line or stack trace. Test totals and exit codes are not enough.
  [TESTING.md](TESTING.md#intentional-error-flags-and-log-auditing) has the machinery and the audit rules.

### 4.6 Coverage

- Every package keeps at least 60% line coverage: JaCoCo's check, `PACKAGE`, `LINE`, `COVEREDRATIO` 0.60, bound to
  `verify`.
- The check leaves out generated gRPC code (`dev/mars/qraft/raft/grpc/**`),
  `dev/mars/qraft/server/QraftServerApplication*`, `dev/mars/qraft/server/QraftServerService*`, and JDK classes. The
  list is in `pom.xml`.

## 5. Configuration

- Qraft runtime configuration is file-based. Production processes must not read environment variables for application
  configuration.
- The runtime receives one versioned JSON configuration file. Its location is resolved, in order, from
  `--config <path>`, the `qraft.config` JVM system property, `config/<role>.json`, or `/etc/qraft/<role>.json`, where
  `<role>` is `server` or `client`. The configuration path must not come from an environment variable.
- Configuration files must not contain environment-variable substitutions.
- Containers and service managers mount configuration and secret files. They may use an explicit command argument, the
  JVM locator property, or a conventional path; they must not translate environment variables into configuration.
- Tests inject parsed configuration values or temporary configuration files; they must not mutate or depend on the
  process environment.
- Invalid, missing, unknown, or duplicate settings fail before threads, sockets, storage, or other runtime resources
  are opened.

## 6. Logging

### 6.1 Logging API

- Application code must use SLF4J.
- Logback is the runtime logging implementation.
- JUL-based library logs must be bridged into SLF4J where the application owns the process.
- Do not write directly to `System.out` or `System.err` for application diagnostics.

### 6.2 Log locations

- The runtime writes to the directory that `logging.directory` names in its configuration file, by default
  `${user.dir}/logs`.
- Both modes use the shipped `logback.xml`. Startup selects the mode and the configured directory before initializing
  any logger.
- Tests write to the repository's `logs/` directory, through `src/test/resources/logback-test.xml`, with timestamped
  filenames so separate runs can be compared.
- Test logs and test configuration are never packaged into production artifacts, and no generated log is committed:
  `logs/` is git-ignored and docker-ignored.

Runtime layout:

```text
logs/
|-- qraft-server.log
|-- qraft-server.json
|-- qraft-client.log
|-- qraft-client.json
`-- archive/
```

Test layout, in the repository:

```text
logs/
|-- qraft-maven-tests-<timestamp>.log         one per test JVM
|-- qraft-<suite>-<timestamp>[-<build>].log   captured Maven output, local or Jenkins
|-- docker/<TestClass>/                       audited container and helper archives
`-- phase3-runtime-process-<mode>-<id>/       fresh runtime JVMs of RuntimeLoggingTest
```

[TESTING.md](TESTING.md#results-and-logs) says what each holds.

### 6.3 Log format and quality

- Text logs must use UTF-8.
- Console output must not contain ANSI control sequences when it is being captured to a file.
- Runtime logs must include timestamp, thread, level, logger, and message.
- Raft logs should include MDC fields for node ID, role, and term.
- RPC logs should include request ID and RPC type where available.
- Client logs should include client ID and request ID where available.
- Exceptions must include the stack trace at the point where the failure is handled.
- Routine retries should not repeatedly emit full stack traces after the first actionable warning.
- Secrets, credentials, tokens, and sensitive payloads must never be logged.

### 6.4 Rotation and retention

- Runtime text and JSON logs rotate daily and at 100 MB into `archive/`, compressed, and keep 30 days up to 1 GB
  (`logback.xml`).
- Test logs are not rotated. Jenkins keeps the artifacts, logs included, of its last 10 builds; nothing trims `logs/`
  on a workstation.

## 7. Verification and completion

- Focused runs (`mvn test -Dtest=...`) are for iteration. `mvn test` alone is not completion evidence: the coverage
  gates run in `verify`.
- A change is verified when `clean install` and all three suites (default, end-to-end, Docker and slow) pass on
  Jenkins against the pushed commit, and the retained logs of that build have been audited with no unflagged error
  line or stack trace. When Jenkins cannot be reached, the same suites run locally.
- The exact commands, the local capture through `Tee-Object`, and the audit rules are in [TESTING.md](TESTING.md):
  [Run every suite on Jenkins](TESTING.md#run-every-suite-on-jenkins),
  [Run builds in a visible terminal](TESTING.md#run-builds-in-a-visible-terminal-through-tee-object) and
  [Auditing a run](TESTING.md#auditing-a-run).

## 8. The administrative interface

The embedded interface is TypeScript and React, in `src/main/ui`, built into the jar by Maven
([TESTING.md](TESTING.md#prerequisites)).

### 8.1 Code

- TypeScript runs with the settings of `src/main/ui/tsconfig.json`: `strict`, `noUncheckedIndexedAccess`,
  `exactOptionalPropertyTypes`, and no unused locals or parameters. `npm run build` type-checks before it bundles, so a
  type error fails the Maven build.
- Dependencies are pinned to exact versions in `package.json` and installed with `npm ci` from the committed lockfile.
  The build writes below `target/`; nothing it writes is committed.
- A response from the API is validated against its schema in `src/api/schemas.ts` before use.
- Timers go through the `Scheduler` of `src/live.tsx`, supplied by `LiveProvider`, so that a test can drive them.
- Every TypeScript source file, tests included, starts with the Apache 2.0 header of section 10.1.

### 8.2 Vitest tests

- Time is driven by a scheduler the test fires by hand, `manualScheduler()` in `src/views/testing.ts`. No test sleeps,
  or depends on the wall clock or on a real timer firing.
- No whole-page `getByRole` or `findByRole` query: computing every element's role and name over a page of Ant Design
  components is too slow in jsdom. Find elements by selector (`tbody tr.ant-table-row`), by text with a selector
  (`getByText(x, { selector: 'h2' })`), or by label (`getByLabelText`).
- An Ant Design component present on every page, or beside every table, slows every test in jsdom. Such a control is a
  plain element, and a simple new list uses `PlainTable` from `src/views/common.tsx`.
- Each test starts from a clean page: `src/setupTests.ts` unmounts what the test rendered and removes the styles it
  added.
- A slow test is made lighter rather than given a longer limit. The limits, 60 s per test and 15 s per wait, exist
  only to report a hang.

## 9. Dependency management

- The root POM is the only POM. A version shared by several artifacts is declared once, as a property.
- The POM must explicitly declare the dependencies that production behavior requires.
- Runtime configuration must not reference appenders, encoders, transports, or providers that are absent from the
  runtime classpath.
- Test-only dependencies must use test scope.
- Optional production integrations must fail clearly or remain disabled when their dependencies are unavailable.

## 10. Code quality

- Prefer clear domain-specific names over legacy compatibility terminology.
- Remove dead code rather than preserving unused migration paths.
- Validate configuration at startup and fail fast on invalid production settings.
- Public asynchronous APIs must document completion, failure, cancellation, and threading behavior.
- Preserve interrupt status when handling `InterruptedException`.
- Avoid unbounded executors and unbounded queues.
- Close all `AutoCloseable` resources deterministically.
- Logging must describe observable state and operational impact rather than implementation noise.

### 10.1 Source file headers

Every Java source file, including tests, test fakes, and fixtures, must begin with the Apache 2.0 license header,
followed by the package declaration:

```java
/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
```

The copyright line matches `NOTICE` and does not change with the file's creation year.

The top-level type must have a Javadoc comment that states its purpose and carries attribution:

```java
/**
 * Runs one probe check at a fixed delay without overlapping itself.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
```

`@since` is the date the file was created. `@version` starts at `1.0` and is raised only for a deliberate
incompatible revision of the type. A test class's Javadoc states the behavior it verifies.

## 11. Change completion

A change is complete when:

- Its behavioral tests were written first and seen to fail, and now pass.
- Every new Java file has the license header and attributed type Javadoc of section 10.1.
- The build and the three suites pass on Jenkins, as section 7 requires, without hanging.
- Every error line and stack trace in the retained logs carries its intentional-error or injected-failure flag
  (section 4.5), and no captured log holds an ANSI control sequence.
- Required runtime dependencies are present.
- Production artifacts do not contain test configuration or generated test logs.
- Documentation reflects any changed public behavior or operational procedure.
