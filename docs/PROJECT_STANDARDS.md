# Qraft Project Standards

## 1. Purpose

This document defines the engineering, architecture, testing, logging, and build standards for Qraft. These standards apply to every module in the Maven reactor unless a module documents a stricter requirement.

Qraft is a Consul-style service discovery and distributed coordination platform. Features and implementation choices must support that product direction. Components inherited from the original Quorus project must not remain unless they have a clear role in that feature set.

## 2. Architecture

### 2.1 Java platform

- Use Java 27 or newer.
- Prefer standard Java APIs and Java 27 concurrency facilities.
- Vert.x is prohibited.
- Do not introduce compatibility wrappers that reproduce Vert.x APIs without a clear project-level abstraction.
- Blocking operations should use bounded executors or virtual threads as appropriate to the workload.
- Shared mutable state must have an explicit ownership and serialization model.

### 2.2 Network protocols

- Retain gRPC for controller-to-controller Raft communication and supported service APIs.
- Use the JDK HTTP stack for HTTP endpoints and clients unless an approved requirement demonstrates that it is insufficient.
- Transport implementations must provide explicit startup, shutdown, timeout, and failure behavior.
- Transport callbacks must propagate failures to their returned future or completion stage.

### 2.3 Raft execution

- Raft state transitions must execute through the dedicated state loop.
- Storage mutations must preserve their required ordering.
- Configured worker-pool sizes and queue bounds must be honored.
- Asynchronous operations must complete successfully or exceptionally on every code path.
- Resource shutdown must wait for active work or fail with a bounded timeout.
- Tests must cover election, replication, storage recovery, snapshot installation, network failure, and shutdown behavior.

### 2.4 Feature scope

- New features must contribute directly to service discovery, health checking, distributed state, namespaces, cluster membership, Raft consensus, or operational observability.
- Legacy functionality must not be retained merely because it existed in the source project.
- Before deleting inherited code, confirm that no Consul-style feature or supported public contract depends on it.

## 3. Module boundaries

- `qraft-raft-engine` defines Raft engine contracts and primitives.
- `qraft-distributed-state` defines replicated-state commands and codecs.
- `qraft-core` contains shared domain models and framework primitives.
- `qraft-agent` implements the Java 27 service-discovery agent.
- `qraft-tenant` implements namespace and tenant management.
- `qraft-controller` implements distributed control, Raft coordination, HTTP APIs, and gRPC services.
- `qraft-runtime` is the thin executable composition root that selects `server` or `client` mode and owns no domain logic.
- Modules must not depend on implementation details from a higher-level module.
- Shared abstractions belong in the lowest module that can own them without creating a circular dependency.

## 4. Testing

### 4.1 Test-driven development

- Use test-driven development for new behavior, defect correction, and migration work.
- Add or update a failing behavioral test before changing production behavior.
- Implement the smallest production change needed to satisfy the test.
- Refactor only after the behavioral test passes.
- Every concurrency or lifecycle defect must receive a regression test.
- Tests must have bounded waits and must fail rather than hang indefinitely.

### 4.2 Test doubles

- Mockito is prohibited.
- Do not add Mockito dependencies, imports, extensions, agents, configuration, examples, or generated test code.
- Do not substitute another mocking framework to evade this rule.
- Test observable behavior with real implementations, protocol-level fixtures, or lightweight purpose-built fakes.
- External adapters should exercise serialization, transport, response parsing, failure behavior, and cleanup.

### 4.3 Test isolation

- A test must release executors, servers, channels, temporary storage, and other resources that it creates.
- Asynchronous setup and teardown must be awaited with explicit timeouts.
- Tests must not depend on execution order.
- Timing-sensitive tests must use bounded polling rather than arbitrary long sleeps wherever practical.
- Expected fault-injection errors must be clearly identified in test output.

## 5. Configuration

- Qraft runtime configuration is file-based. Production processes must not read
  environment variables for application configuration.
- The runtime receives one versioned JSON configuration file. Its location is
  resolved, in order, from `--config <path>`, the `qraft.config` JVM system
  property, `config/<role>.json`, or `/etc/qraft/<role>.json`, where `<role>` is
  `server` or `client`. The configuration path must not come from an environment
  variable.
- Configuration files must not contain environment-variable substitutions.
- Containers and service managers mount configuration and secret files. They may
  use an explicit command argument, the JVM locator property, or a conventional
  path; they must not translate environment variables into configuration.
- Tests inject parsed configuration values or temporary configuration files; they
  must not mutate or depend on the process environment.
- Invalid, missing, unknown, or duplicate settings fail before threads, sockets,
  storage, or other runtime resources are opened.

## 6. Logging

### 6.1 Logging API

- Application code must use SLF4J.
- Logback is the runtime logging implementation.
- JUL-based library logs must be bridged into SLF4J where the application owns the process.
- Do not write directly to `System.out` or `System.err` for application diagnostics.

### 6.2 Log locations

- Runtime and test logs use the repository or deployment's central `logs` directory.
- Production runtime location is controlled by `logging.directory` in the
  runtime configuration file.
- The default runtime location is `${user.dir}/logs`.
- Test logs must use unique, timestamped filenames so separate runs can be compared.
- Test logs must not be packaged into production artifacts.
- Generated logs must not be committed to source control.
- CI systems should retain test logs as build artifacts under an explicit retention policy.

Expected layout:

```text
logs/
|-- qraft-agent-<instance>.log
|-- qraft-agent-<instance>.json
|-- qraft-controller-<instance>.log
|-- qraft-controller-<instance>.json
|-- qraft-tests-<timestamp>.log
`-- archive/
```

### 6.3 Log format and quality

- Text logs must use UTF-8.
- Console output must not contain ANSI control sequences when it is being captured to a file.
- Runtime logs must include timestamp, thread, level, logger, and message.
- Raft logs should include MDC fields for node ID, role, and term.
- RPC logs should include request ID and RPC type where available.
- Agent logs should include agent ID and request ID where available.
- Exceptions must include the stack trace at the point where the failure is handled.
- Routine retries should not repeatedly emit full stack traces after the first actionable warning.
- Secrets, credentials, tokens, and sensitive payloads must never be logged.

### 6.4 Rotation and retention

- Runtime text and JSON logs must rotate by date and size.
- Archived runtime logs should be compressed.
- Runtime retention must have both age and total-size limits.
- Test-log retention must preserve enough runs for regression comparison without growing indefinitely.
- Failed CI run logs should be retained longer than routine successful-run logs where supported.

## 7. Standard Maven test command

Run the Maven reactor tests from the repository root with PowerShell:

```powershell
mvn -B "-Dstyle.color=never" test 2>&1 | Tee-Object ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss').log"
```

This command:

- Runs the Maven reactor test lifecycle.
- Disables Maven color output so the captured file does not contain ANSI color sequences.
- Displays output in the console.
- Captures standard output and standard error in the same test log.
- Creates a timestamped log that can be compared with earlier runs.

The `logs` directory must exist before running the command. If it does not exist, create it once with:

```powershell
New-Item -ItemType Directory -Force .\logs
```

## 8. Test-log comparison

Compare two retained Maven test logs with:

```powershell
git diff --no-index `
  .\logs\qraft-tests-2026-09-10_09-15-32.log `
  .\logs\qraft-tests-2026-09-10_10-42-08.log
```

Timestamps, UUIDs, temporary paths, random ports, and election timing are nondeterministic. Where exact comparison is required, normalize those fields or compare structured test results in addition to raw logs.

## 9. Dependency management

- Dependency versions shared by multiple modules must be managed by the parent POM.
- A module must explicitly declare the dependencies required by its production behavior.
- Runtime configuration must not reference appenders, encoders, transports, or providers that are absent from the runtime classpath.
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

Every Java source file, including tests, test fakes, and fixtures, must begin with the Apache 2.0
license header, followed by the package declaration:

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

`@since` is the date the file was created. `@version` starts at `1.0` and is raised only for a
deliberate incompatible revision of the type. A test class's Javadoc states the behavior it
verifies.

## 11. Change completion

A change is complete when:

- Required behavioral tests have been added or updated.
- Every new Java file has the license header and attributed type Javadoc described in section 10.1.
- Relevant tests pass without hanging.
- Logs contain no unexpected errors or invalid terminal characters.
- Required runtime dependencies are present.
- Production artifacts do not contain test configuration or generated test logs.
- Documentation reflects any changed public behavior or operational procedure.
