# Task List: Unified Runtime Flow

**Date:** 2026-09-24
**Active work:** Step 3, runtime-boundary end-to-end proof
**Source plan:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), Tranche 5
**Predecessor:** [`archive/task-list-agent-catalog-client-2026-09-24.md`](archive/task-list-agent-catalog-client-2026-09-24.md)
**Standards:** [`PROJECT_STANDARDS.md`](PROJECT_STANDARDS.md)

This is the current task list for the project. When the active work is complete,
add a completion summary, move this file to `archive/`, and start a new dated task
list for the next backlog item.

## 1. Goal

Make the unified `qraft-runtime` boundary own server and client startup rather
than delegating to two static `main` methods. Prove through that boundary that a
server and client can start from versioned configuration files, converge catalog
state, report readiness accurately, and shut down cleanly.

## 2. Rules

- Qraft does not use environment variables for configuration or configuration-file
  discovery. Runtime settings come from one versioned JSON file selected by
  `--config`, `qraft.config`, or a conventional role-specific path.
- Red before green for every behavioural change.
- Mockito and substitute mocking frameworks are prohibited. Use real runtime
  components, bound-loopback protocol fixtures, or small purpose-built fakes.
- Configuration must be validated before runtime-owned threads, sockets, or
  storage are opened.
- Preserve the `server` and `client` command-line contract and one-image Docker
  entrypoint.

## 3. Step 1: Injectable mode launchers

**Status: Done 2026-09-24.** Added a runtime-owned `ModeLauncher` seam and a
testable `run` composition boundary. The production `main` installs server and
client adapters, while tests inject purpose-built recording and failing launchers.
Mode validation and file resolution occur before dispatch; exactly one launcher
receives the resolved path, and launcher failures propagate unchanged. The seven
focused launcher tests and the complete 23-test runtime suite pass. The reactor
run required for those runtime tests passed 642 tests with no failures, errors,
or skips.

**Red tests.**

1. Parsed `server` startup invokes only the supplied server launcher with the
   resolved configuration path.
2. Parsed `client` startup invokes only the supplied client launcher with the
   resolved configuration path.
3. An unsupported or missing mode fails before either launcher is invoked.
4. Launcher startup failure is propagated and does not start the other mode.

**Implementation.** Introduce the smallest runtime-owned launcher/lifecycle
abstraction needed to inject server and client startup. Keep `main` as a thin
adapter that installs the production launchers. Do not make the runtime tests call
or replace static application entry points.

**Exit gate.** Mode selection and delegation are covered without static state,
process exit, framework mocks, or opened production resources.

## 4. Step 2: Managed production lifecycle

**Status: Done 2026-09-24.** Added a runtime-owned `RuntimeLifecycle` with one
completion future and one idempotent asynchronous close operation. The unified
entry point installs its shutdown hook against that lifecycle and production
launchers now call `QraftControllerApplication.launch(Path)` and
`QraftAgent.launch(Path)` directly; neither invokes another application's
`main` method. Controller shutdown owns the service, Java runtime, and telemetry
providers, while both module launch methods clean up acquired resources when
startup fails. Configuration parsing and validation precede the injected
resource factories in both modes. The 13 focused lifecycle tests passed, and
the complete runtime dependency reactor passed 671 tests with no failures,
errors, or skips.

**Red tests.**

1. A launched mode exposes one completion/close boundary owned by the runtime.
2. Repeated or concurrent close is idempotent.
3. Server and client startup failures close every resource already acquired.
4. Configuration validation occurs before lifecycle resources open.

**Implementation.** Adapt `QraftControllerApplication` and `QraftAgent` behind
the launcher boundary so the runtime, command-line entry point, and shutdown hook
share one managed lifecycle.

**Exit gate.** Neither production launcher depends on invoking another class's
`main` method, and shutdown ownership is explicit.

## 5. Step 3: Runtime-boundary end-to-end proof

**Red tests.**

1. Start a real single-node server through the runtime launcher from a temporary
   server JSON file.
2. Start a real client through the runtime launcher from a temporary client JSON
   file containing two services.
3. Observe client readiness and both services through public HTTP catalog APIs.
4. Close the client and observe service and node deregistration, then close the
   server; all runtime-owned resources terminate.
5. A controller restart followed by reconciliation restores both services without
   bypassing the runtime boundary.

**Exit gate.** One test exercises configuration loading, both launchers,
registration, discovery, readiness, reconciliation, and ordered shutdown through
the unified runtime API.

## 6. Step 4: Deployment and documentation verification

1. Keep both explicit and conventional config-file startup paths covered by the
   Docker deployment contract.
2. Update the platform design Tranche 5 status and runtime ownership description.
3. Run the full default reactor, Docker-tagged acceptance suite, Mockito scan,
   environment-configuration scan, and `git diff --check`.

## 7. Out of scope

- Health observation commands, TTL expiry, and automatic deregistration
  (Tranche 6).
- Multi-node container acceptance and leader replacement (Tranche 7).
- Configuration hot reload.
- Server-side write forwarding and leader-aware client routing.
