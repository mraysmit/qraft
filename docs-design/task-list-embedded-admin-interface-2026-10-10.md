# Task List: Embedded Administrative Interface

**Date:** 2026-09-27
**Last updated:** 2026-10-10 (renamed with the task lists it links to; nothing else changed that day. On 2026-10-09: the read APIs, the development proxy, and the reserved segments, after the single-POM list's Phase 4. Qraft's two retired words replaced, and the file renamed to the date of its last change. On 2026-10-08: the log audit added to Step 6)
**Active work:** Paused after Step 1 on 2026-09-27, until the backend features in [`QRAFT_FEATURE_VALIDATION_2026-09-27.md`](QRAFT_FEATURE_VALIDATION_2026-09-27.md) are delivered; Step 2 is next when it resumes
**Implementation plan:** [`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md), increments UI-0 and UI-1
**Source plan:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), sections 12.4, 12.4.1, 19.6, and 21; [`QRAFT_ADMIN_UI_UX_DESIGN.md`](QRAFT_ADMIN_UI_UX_DESIGN.md), sections 14 and 15
**Predecessor:** [`archive/task-list-multi-node-container-acceptance-2026-09-26.md`](../docs/archive/task-list-multi-node-container-acceptance-2026-09-26.md)
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

This list is paused; the current task list is
[`task-list-single-pom-and-quorus-removal-2026-10-10.md`](task-list-single-pom-and-quorus-removal-2026-10-10.md).
When this list's work is complete, add a completion summary and move this file
to `docs/archive/`.

## 1. Goal

Close the last open initial acceptance criterion (design section 21): the shaded
runtime artifact serves the embedded administrative interface in server mode,
without external asset files or an additional process.

This list delivers:

- the packaging and serving contract of design sections 12.4.1 and 19.6;
- a first read-only slice of Stage 1, operational discovery (admin UI design
  section 15), built only on HTTP APIs that already exist.

## 2. Current state

Updated 2026-09-27.

- No frontend source exists. `console-prototype/qraft-admin-mockup.html` is a
  self-contained design reference of about 64 KB. It is plain HTML with inline
  JavaScript and embedded JSON data, and uses no framework or build step.
- The Maven build has no frontend step. Node 24 is installed on the development
  machine, but the build does not use it.
- The server HTTP API (`HttpApiServer`, JDK `HttpServer`) has no
  administrative routes, no static-asset serving, and no UI configuration. It
  has no authentication, TLS, or authorization, and those are out of scope here.
- Read APIs that exist today:
  - `/raft/status`: node, role, term, leader, commit, applied, and last log
    index, snapshot index, and the fenced flag, read as one consistent view;
  - `/health/live` and `/health/ready`;
  - `/v1/catalog/nodes`: nodes with status, heartbeat, and metadata. Until
    2026-10-09 this was `/api/v1/clients`;
  - `/v1/catalog/services`, `/v1/catalog/service/{name}`;
  - `/v1/health/service/{name}` with checks and the `passing` filter.
- Stage 1 views that need APIs which do not exist yet:
  - cluster membership, peer reachability, and replication lag;
  - storage, WAL size, snapshot age, and recovery state;
  - events;
  - configuration with secrets redacted.
- Client mode must not start any administrative listener or route.

## 3. Rules

- The interface is a client of the public HTTP API. It must not read or mutate
  server implementation objects, or introduce another consistency or
  persistence path.
- Production serves assets from classpath resources under `META-INF/qraft/ui/`
  only. Server code must not convert resource URLs to `Path` or use `Files`.
- Bootstrap data never contains secrets or credentials. `index.html` and the
  manifest are revalidated, and content-hashed assets are immutable.
- The interface can be disabled by configuration without producing a different
  artifact. Disabling it removes its routes.
- Configuration comes only from the versioned JSON file. There are no
  environment-variable settings.
- Red before green for every behavioural change, with deterministic tests
  (`PROJECT_STANDARDS.md`, section 4.4). Mockito and substitute mocking
  frameworks are prohibited.
- Every new Java file carries the license header and attributed type Javadoc
  (`PROJECT_STANDARDS.md`, section 10.1).
- Update the design documents and this list before moving to the next step.

## 4. Decisions

### 4.1 Agreed 2026-09-27

These follow a review of the `peegeeq-management-ui` and `peegeeq-utilities-ui`
modules. The stack matches those modules for consistency across the products,
and corrects the build and security weaknesses the review found.

1. **Frontend stack.** React 18, TypeScript in strict mode, Vite 6, and Ant
   Design 5, the same major versions as `peegeeq-management-ui`.
   - The first slice uses no global state library: no Redux Toolkit and no
     Zustand. Data comes through a small typed `fetch` client that validates
     responses with zod. The peegeeq modules carry two overlapping stores,
     used in three files between them, alongside axios.
   - Vite's development server proxies `/v1`, `/raft`, and `/health` to a
     local server, as peegeeq does for its backend. `/api` is not proxied:
     no route has been served under it since 2026-10-09.
2. **Build integration.** `frontend-maven-plugin` in the root `pom.xml`, with
   pinned Node and npm versions, as in peegeeq, but correcting what it does
   differently:
   - `npm ci`, not `npm install`, so the committed `package-lock.json` is never
     rewritten and builds are reproducible.
   - The Vite build runs in `generate-resources` of every `mvn package`. In
     peegeeq the build execution is skipped, and the committed output can
     drift from its source.
   - Output goes to a directory under `target/` and is staged as generated
     resources under `META-INF/qraft/ui/`. It is never committed and never
     written into another module's source tree. peegeeq's configuration
     empties a directory in `peegeeq-rest`.
   - `build.manifest: true` produces the asset manifest that design section
     12.4.1 requires.
   - Source maps are not published: they are off, or generated without a
     reference from the bundles. peegeeq commits and serves them.
3. **Frontend tests.** Vitest with jsdom and Testing Library, run by
   `mvn test` through the same plugin, as in peegeeq. Serving behaviour is
   covered by Java tests over real HTTP. Playwright end-to-end tests are out of
   scope for this list.

### 4.2 Agreed 2026-09-27, after review of the implementation plan

4. **Default state without authentication.** The interface is enabled by
   default. It is read-only in this list and exposes only what the public API
   already exposes. The server logs one startup warning that the interface is
   unauthenticated. Authentication arrives with increment UI-5 of the
   implementation plan.
5. **Scope of the Stage 1 slice.** Views on existing APIs only:
   - the application shell with a cluster status strip from `/raft/status` and
     the health endpoints;
   - Services, fetching once per service until UI-2 adds a summary endpoint;
   - service detail: Overview, Instances, Health Checks, and Metadata;
   - client rows in Nodes and Clients.

   Views that need new APIs follow the implementation plan's later increments.
6. **Design tranche.** Tranche 8, the embedded administrative interface, is
   added to design section 20 and points at the implementation plan.

## 5. Step 1: Configuration and route contract

1. Add `server.ui` to the server configuration:
   - `enabled`;
   - `path`, defaulting to `/ui/`;
   - an optional development-only `devAssetsDirectory`.
2. Validate these settings at startup, before any listener opens. Reject:
   - a path that overlaps `/v1/`, `/api/`, `/health`, `/raft/`, metrics, or
     debugging routes. `/api/` and `/status` are still refused, although the
     server has served nothing under them since 2026-10-09: the interface
     cannot be mounted where an API used to answer;
   - a malformed path;
   - a development directory that is missing or has no `index.html`;
   - an ambiguous combination of embedded and external assets.
3. When the interface is disabled, and always in client mode, no administrative
   route is registered.

**Exit gate.** Configuration and route-registration tests, each shown failing
first.

**Status: Done 2026-09-27.**

- `AdminUiConfig` validates `server.ui` while the file is parsed, before
  anything opens:
  - `enabled` defaults to `true`, and `path` to `/ui/`.
  - The path must be a lowercase, slash-terminated prefix whose first segment is
    not one of the server's routes: `v1`, `api`, `health`, `raft`, `status`,
    `metrics`, or `debug`.
  - `devAssetsDirectory` must exist, hold `index.html`, and accompany an enabled
    interface. With a single optional override, an ambiguous combination of
    embedded and external assets cannot be expressed.
- `HttpApiServer` registers `AdminUiHandler` at the path only when the
  interface is enabled. It logs one startup warning that the interface is
  unauthenticated.
- Assets come from `UiAssets.forConfig`: `ClasspathUiAssets` (resource streams
  under `META-INF/qraft/ui/`) or `DirectoryUiAssets` (the development override).
- The handler:
  - answers only `GET` and `HEAD`;
  - serves the path prefix as `index.html`;
  - serves only named files, with explicit content types;
  - refuses any relative path with an empty, `.`, or `..` segment, a
    backslash, or a control character, so no request can leave the asset root.
- Client mode never runs `HttpApiServer`. Step 5 proves client mode has no
  interface route as part of packaged acceptance.

`AdminUiConfigTest` has 5 tests and `HttpApiServerAdminUiTest` has 7. Both
failed on the behaviour first, and the full default reactor passed 819 tests.
Until Step 3 packages assets, an enabled server answers the UI path with 404.

## 6. Step 2: Serving embedded assets

1. Serve `META-INF/qraft/ui/` through classpath resource streams on the existing
   JDK HTTP server, sharing its listener, limits, and lifecycle. Strip only the
   configured prefix before the lookup.
2. Render `index.html` per request with a safely JSON-encoded bootstrap object:
   - the API base path;
   - the UI path;
   - enabled feature flags;
   - the authentication mode;
   - a fresh content security policy nonce.

   It contains no secrets.
3. Set cache headers: immutable, long-lived caching for content-hashed assets,
   and revalidation for `index.html` and the manifest.
4. Set response headers: an explicit content type, `X-Content-Type-Options:
   nosniff`, a restrictive content security policy, and the configured security
   headers.
   - The policy allows the application's scripts and styles only from the
     server's origin, plus inline styles carrying the per-request nonce. Ant
     Design 5 injects its CSS at runtime in `<style>` elements. The frontend
     passes the nonce to `ConfigProvider` (`csp={{ nonce }}`), so the policy
     never needs `'unsafe-inline'`.
5. Route requests:
   - An unknown extensionless `GET` below the UI path falls back to `index.html`.
   - A missing named asset returns 404.
   - API, health, metrics, and debugging paths never fall back.
   - `/` may redirect to the UI path.
6. Serve precompressed variants chosen by `Accept-Encoding`, with the
   uncompressed resource as the fallback. This item is optional; decide it at
   review.

**Exit gate.** Java tests against fixture resources on the test classpath, over
real HTTP, each shown failing first.

## 7. Step 3: Frontend source and reproducible build

1. Create the frontend source under `src/main/ui/`, using the
   stack in decision 1, with a committed `package-lock.json` and every Node,
   npm, and plugin version pinned.
2. Build it in `generate-resources` with `npm ci` and `vite build`, as decision 2
   sets out. Stage the output as generated resources under
   `META-INF/qraft/ui/`. The output contains:
   - `index.html`;
   - Vite's asset manifest;
   - `assets/<content-hashed files>`;
   - no source maps.

   None of it is committed.
3. The build's shade step carries those resources into `target/qraft.jar`,
   adding no domain logic to the entry-point package.

**Exit gate.** A clean `mvn package` on a machine without Node installed
produces a runtime JAR that contains the manifest and every asset it lists, and
no source maps. A server test reads the packaged resources through the
classloader. Vitest runs in `mvn test`.

## 8. Step 4: First Stage 1 read-only views

This step builds the scope agreed in decision 5:

- the application shell and cluster status strip;
- Services;
- service instances with checks;
- Nodes and Clients.

The views follow the admin UI design principles: explicit scope, observations
distinguished from state, and colour never the only signal.

**Exit gate.**

- Vitest and Testing Library tests cover the views and the typed API client,
  including zod rejecting a malformed response.
- A server-mode test fetches the views' data through the same public APIs the
  interface uses.
- The rendered page runs under the enforced content security policy with no
  inline style or script lacking the nonce.

## 9. Step 5: Packaged-artifact and container acceptance

1. Start the shaded runtime JAR from a directory with no frontend files on disk.
   Fetch `index.html` and an asset listed in the manifest (design section 19.6).
2. Client mode, and server mode with the interface disabled, expose no
   administrative routes.
3. The `qraft-runtime:test` image serves the interface from a server container.

**Exit gate.** These tests pass three consecutive runs and are shown able to
fail.

## 10. Step 6: Verification and close-out

1. Run the full default suite, the Docker-tagged suite, the Mockito,
   environment-variable, and `orTimeout` scans, the header audit, and
   `git diff --check`. Read every retained log of those runs for unflagged
   errors, as `AGENTS.md` and `docs/TESTING.md` require (added 2026-10-08:
   this list predates the logging policy, which applies to every step's exit
   gate).
2. Mark the embedded administrative interface criterion in design section 21,
   and record the tranche status.

## 11. Out of scope

- Authentication, authorization, TLS, and permission-shaped navigation (admin UI
  design sections 3.5 and 10.2).
- Administrative mutations, and mutation outcome and audit handling (Stage 2).
- Stage 1 views that need new server APIs: membership and replication lag,
  storage and snapshots, events, and redacted configuration.
- Live updates, event streaming, and consistency selection (admin UI design
  section 12).
- Connectivity, multi-cluster, and governance stages.
- Browser end-to-end test automation.
