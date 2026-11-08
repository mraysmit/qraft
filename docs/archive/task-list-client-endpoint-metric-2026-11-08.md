# Task List: The Client's Server Endpoint Metric

**Date:** 2026-11-08
**Status:** Complete and archived on 2026-11-08. Phase 1 is done, test first, and the final local run of the three suites passed on them: default 1,918 with every coverage gate met, end-to-end 28 and Docker 51, with no failures, errors or skips; audit over the 269 files the run wrote: 911 error lines, all flagged, 624 stack traces, none unflagged; no `too_many_pings`; no JVM crash file (section 6). Started on 2026-11-08 for what the feature validation's client metrics row still missed, under the owner's instruction of 2026-11-07 to keep working without stopping for questions; the decisions in section 4 were taken as the recommended options under that instruction, and the owner can reverse any of them.
**Last updated:** 2026-11-08 (the final run recorded, and the list complete and archived. Earlier the same day: Phase 1 built)
**Follows:** [`task-list-client-library-2026-11-08.md`](task-list-client-library-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](../../docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 18
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](../../docs-design/QRAFT_FEATURE_VALIDATION_2026-10-21.md), the client metrics row
**Standards:** [`PROJECT_STANDARDS.md`](../PROJECT_STANDARDS.md)

## 1. Goal

The client's metrics name the server endpoint it uses, the last part of design section 18's client metrics that is not
built.

## 2. Where Qraft stands (2026-11-08)

- `GET /v1/client/metrics` answers readiness, the age of the last server contact and reconciliation passes by result
  (the observability list).
- The client prefers the server that last answered it (`ServerEndpoints`), and nothing reports which that is.

## 3. Reference

Consul reports a client's servers through `consul members` and `/v1/agent/self`, not as a metric. A Prometheus
"info" gauge, value 1 with the fact as a label, is the usual way to report a fact that is not a number.

## 4. Decisions

Taken on 2026-11-08 as the recommended option, under the owner's instruction; it can be reversed.

1. **The gauge `qraft.client.server`, 1, labelled `endpoint`** with the server the client tries first: the one that
   last answered, or its first seed before any has. In Prometheus' text it is `qraft_client_server_info`.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, before the list closes.
- Every unflagged error line or stack trace is remediated (AGENTS.md).

## 6. Phases

### Phase 1. The endpoint gauge

- [x] `ServerEndpoints.preferred()`, and the gauge in the JSON and Prometheus forms, through a real client.

**Exit:** the new tests seen red first; the three suites locally, audited, and the list complete and archived.

**Final run, 2026-11-08.** The three suites from `C:\qx7` (logs `logs/five-final/`), which also took the earlier phases' `mvn install` exits and suites, and closed five lists together: default 1,918 with every coverage gate met, end-to-end 28 and Docker 51, with no failures, errors or skips; audit over the 269 files the run wrote: 911 error lines, all flagged, 624 stack traces, none unflagged; no `too_many_pings`; no JVM crash file. An earlier run, `logs/trio-final/`, passed the default suite (1,905) and the end-to-end suite (26) on the client library, endpoint metric and certificate reloading work, and its Docker suite failed one class on its log audit: `DockerDurableRestartTest` kills the leader, and the new leader's snapshot to it failed five times with `UNAVAILABLE: Keepalive failed. The connection is likely gone`, a form the `RAFT_SNAPSHOT_TRANSFER_INTERRUPTED` declaration did not cover. The killed container's network went without resetting the connection, so the channel learned of it by its keepalive. The declaration now covers that form, with `DockerLogCaptureTest` seen red at it first.

**Record (2026-11-08):** `ServerEndpoints.preferred()` answers the seed a cycle starts with; `HttpCatalogClient`
passes it on as `preferredServer()`, and `ClientMetrics` takes it as a fourth argument and reports the gauge, its label
escaped as Prometheus' text format requires. `ServerEndpointsTest`, `ClientMetricsTest` and `ClientMetricsRouteTest`
were seen red 3 of 3 against stubs (a `null` preference and no gauge), then green with `HttpCatalogClientTest`,
`QraftClientTest` and `PackageDependencyTest`, 60 tests.

## 7. Out of scope

- A list of every server the client knows, and their health.
