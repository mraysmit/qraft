# Task List: Embedded Administrative Interface

**Date:** 2026-09-27
**Last updated:** 2026-11-13 (resumed at the owner's word to continue the implementation work, the backend it waited for being built; Steps 2 and 3 built, test first, with their records, the choices on Step 2's two optional items, and the `<base>` element Step 3 needed; the current state brought up to date; renamed to the date of its last change. Earlier, 2026-10-27, later the same day: Step 2's bootstrap object carries the security profile, from the ACL list's proposal 16. Earlier that day: decision 2 and Step 3: the asset manifest is written as `asset-manifest.json`, the name design section 12.4.1 gives; renamed to the date of its last change. Earlier, 2026-10-21: current-task pointer updated to membership Step 6 after Step 5 completion; this list remains paused after Step 1; renamed with linked documents. Earlier, 2026-10-14: current-task pointer updated to membership Step 5 after cleanup archival. On 2026-10-11: renamed with the task lists it links to; nothing else changed that day. On 2026-10-10: renamed with the task lists it links to; nothing else changed that day. On 2026-10-09: the read APIs, the development proxy, and the reserved segments, after the single-POM list's Phase 4. Qraft's two retired words replaced, and the file renamed to the date of its last change. On 2026-10-08: the log audit added to Step 6)
**Active work:** Resumed 2026-11-13, after a pause from 2026-09-27 until the backend features in [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](QRAFT_FEATURE_VALIDATION_2026-10-21.md) were delivered; Steps 2 and 3 are done and Step 4 is next
**Implementation plan:** [`QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md`](QRAFT_ADMIN_UI_IMPLEMENTATION_PLAN.md), increments UI-0 and UI-1
**Source plan:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), sections 12.4, 12.4.1, 19.6, and 21; [`QRAFT_ADMIN_UI_UX_DESIGN.md`](QRAFT_ADMIN_UI_UX_DESIGN.md), sections 14 and 15
**Predecessor:** [`archive/task-list-multi-node-container-acceptance-2026-10-21.md`](../docs/archive/task-list-multi-node-container-acceptance-2026-10-21.md)
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

This list is active again. The membership list it waited for,
[`task-list-raft-membership-changes-2026-10-24.md`](../docs/archive/task-list-raft-membership-changes-2026-10-24.md),
was completed and archived on 2026-10-24, and the Pre-Vote list that followed it,
[`task-list-raft-pre-vote-2026-10-25.md`](../docs/archive/task-list-raft-pre-vote-2026-10-25.md),
on 2026-10-25. The owner then kept it paused, on 2026-10-26, until the other backend
work was done; by 2026-11-09 every row of the feature validation was, and Jenkins
build 67 ran it all on 2026-11-13.
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

Updated 2026-11-13. The list as it stood on 2026-09-27 is kept below the first
three points, since its scope was agreed on it.

- The frontend source is under `src/main/ui/`, and every Maven build builds it
  (Step 3). `console-prototype/qraft-admin-mockup.html` stays the design
  reference: about 64 KB of plain HTML with inline JavaScript and embedded JSON
  data, with no framework or build step.
- Since the pause the server gained what this list called out of scope:
  - ACLs (the ACL list): with them enabled, every API route but health and
    the bootstrap needs a token, while the interface's files need none, so the
    interface's own calls must carry one (increment UI-5);
  - HTTPS on the same listener, requiring a client certificate by default,
    when `server.tls` is configured (the transport security list);
  - `server.security.profile`, `production` or `development` (ACL list,
    proposal 16).
- Read APIs added since, which views this list leaves out can use (the
  implementation plan's later increments):
  - `/raft/status` also reports the storage's size and the snapshot's age
    (the observability list);
  - `/v1/operator/raft/configuration`, the servers and their voting state, and
    `/v1/operator/autopilot/health`, each server's health on the leader;
  - `/v1/operator/events` and its watch (the event query and watch list);
  - `/v1/kv`, `/v1/session/...`, `/v1/tenants` and `/v1/namespaces`.

  Configuration with secrets redacted still has none.

As of 2026-09-27:

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
   - `build.manifest: 'asset-manifest.json'` writes the asset manifest that
     design section 12.4.1 requires, at the root of the output and under the
     name that section gives. With `true`, Vite would write it as
     `.vite/manifest.json`.
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
   - the authentication mode, `none` until the ACL list's enforcement exists,
     then `none` or `token`;
   - the security profile, `production` or `development`, once the ACL list
     adds it (its proposal 16);
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

**Status: Done 2026-11-13.**

- `UiBootstrap` holds the public settings:
  - `apiBasePath`, `/`: the interface calls `/v1`, `/raft` and `/health` on
    the same listener;
  - `uiPath`;
  - `features`, empty until a view needs a flag;
  - `authMode`, `token` with ACLs enabled and `none` otherwise;
  - `securityProfile`, `production` or `development`.

  `toJson` adds the request's `cspNonce` and escapes `<`, `>`, `&`, U+2028 and
  U+2029, so the JSON cannot end the element that carries it. It holds nothing
  secret: the management token's hash, which the server is given, is not in it.
- `HttpApiServer` takes the security profile in one more constructor, which
  `QraftServerService` calls with `server.security.profile`. The existing
  constructors pass `development`, the configuration's default.
- `AdminUiHandler` renders `index.html` for each request. It does so for the
  prefix, for `index.html` itself, and for any path whose last segment has no
  extension, outside `assets/`. It adds
  `<script id="qraft-bootstrap" type="application/json" nonce="...">` before
  `</head>`, with a 128-bit nonce from `SecureRandom`. It also adds
  `<base href="...">` with the configured path, first in the head (added with
  Step 3): the build links its assets relative to the page, since the path is
  configured, and from a client-side route such as `services/web` they must
  still resolve below the path.
- Cache headers:
  - `no-store` for the rendered index. It carries a per-request nonce, and a
    stored copy would replay it, so this is stricter than the revalidation
    item 3 asks for;
  - `public, max-age=31536000, immutable` below `assets/`;
  - `no-cache` for the manifest and every other named file.
- Every response, 404 and 405 included, carries `X-Content-Type-Options:
  nosniff`, `Referrer-Policy: no-referrer`, and this policy:
  `default-src 'none'; script-src 'self'; style-src 'self' 'nonce-...';
  img-src 'self' data:; font-src 'self'; connect-src 'self';
  manifest-src 'self'; base-uri 'self'; form-action 'none';
  frame-ancestors 'none'`. `base-uri` was `'none'` until the `<base>` element
  needed `'self'`. The API server sets no security headers of its own, so there
  were none to share.
- Routing: nothing below `assets/` falls back, and a missing named file is not
  found. Paths outside the prefix never reach the handler.
- Not built, the recommended choice on each optional item, taken under the
  owner's instruction to continue and open to reversal:
  - the redirect from `/` (item 5): the JDK server would send every path that
    has no context of its own to a `/` context, which would change how an
    unknown API path is answered;
  - precompressed variants (item 6): their worth depends on the bundles'
    sizes, which Step 3 will show. Step 3's one bundle is 516 kB, 165 kB
    gzipped, most of it Ant Design, so a gzip variant would save about two
    thirds of the transfer; it is left for Step 4, whose views settle the size.
- Tests: `UiBootstrapTest` has 3, and `HttpApiServerAdminUiTest` 5 new cases,
  13 in all, and a 14th for the base, with the policy's `base-uri` changed,
  both seen red (`base-uri 'self' in ... base-uri 'none'`, and no base
  element) before the handler changed. Each failed first on a stub, for example with
  `expected: <[apiBasePath, ...]> but was: <[]>`, `no bootstrap element`,
  `expected: <nosniff> but was: <>`,
  `expected: <public, max-age=31536000, immutable> but was: <>`, and
  `expected: <200> but was: <404>`. They passed with `AdminUiConfigTest`, 22
  tests, and then with the server's HTTP and UI packages and the architecture
  rules, 231. Logs `logs/ui-step2-red-*`, `logs/ui-step2-green-*` and
  `logs/ui-step2-http-pkg-*`; every error line and stack trace in them is
  flagged.
- `mvn install` from the check copy: the first run failed one unrelated test,
  `RaftNodeSystemEventsTest`, on a race in the test, fixed (handover,
  section 3); the second passed the default suite, 1,980, with every coverage
  gate met. Its audit, over the Maven output, the test reports and the
  application logs, 576 files: 838 error lines and 738 stack traces, all flagged; no JVM crash
  file (`logs/ui-step2-install-*` and `logs/ui-step2-install2-*`).

## 7. Step 3: Frontend source and reproducible build

1. Create the frontend source under `src/main/ui/`, using the
   stack in decision 1, with a committed `package-lock.json` and every Node,
   npm, and plugin version pinned.
2. Build it in `generate-resources` with `npm ci` and `vite build`, as decision 2
   sets out. Stage the output as generated resources under
   `META-INF/qraft/ui/`. The output contains:
   - `index.html`;
   - Vite's asset manifest, `asset-manifest.json`;
   - `assets/<content-hashed files>`;
   - no source maps.

   None of it is committed.
3. The build's shade step carries those resources into `target/qraft.jar`,
   adding no domain logic to the entry-point package.

**Exit gate.** A clean `mvn package` on a machine without Node installed
produces a runtime JAR that contains the manifest and every asset it lists, and
no source maps. A server test reads the packaged resources through the
classloader. Vitest runs in `mvn test`.

**Status: Done 2026-11-13.**

- `src/main/ui/`: `package.json` with exact versions and the committed
  `package-lock.json`; React 18.3.1, Ant Design 5.29.3 and zod 4.6.7; Vite
  6.4.6 with `@vitejs/plugin-react` 4.7.0, TypeScript 5.9.3 in strict mode,
  Vitest 3.2.7 with jsdom 26.1.0 and Testing Library (`@testing-library/react`
  16.3.3, `dom` 10.4.2, `jest-dom` 6.9.1, since npm marks 6.10.0 a broken
  release). `.npmrc` saves exact versions and runs no dependency's install
  script: the toolchain needs none, since esbuild and Rollup ship their
  binaries as optional packages for each platform.
- `vite.config.ts` builds with `base: './'`, so the assets are linked relative
  to the page, which the server's `<base>` resolves below the configured path
  (Step 2's record). It writes to `target/generated-resources/ui/META-INF/qraft/ui/`,
  with `asset-manifest.json` and no source maps. The development server
  proxies `/v1`, `/raft` and `/health` to `127.0.0.1:8080`.
- `src/bootstrap.ts` reads and checks the bootstrap object with zod, and
  refuses a page the server did not render; `src/main.tsx` hands its nonce to
  Ant Design's `ConfigProvider`. Under `npm run dev`, which serves the page
  itself, it uses fixed development settings. `src/App.tsx` is the shell that
  Step 4 fills: it names the product and, in words, the security profile and
  sign-in mode.
- `pom.xml`: `frontend-maven-plugin` 1.15.4, the latest of the 1.x line that
  peegeeq uses (1.15.1), rather than 2.0 untried. It installs Node v24.22.3,
  the current LTS, with the npm it bundles (11.19.1), into `target/node`, and
  runs `npm ci` and `npm run build` (`tsc --noEmit && vite build`) in
  `generate-resources`, and Vitest in `test`, skipped with `-DskipTests`. The
  output directory is a resource directory, so the shade step carries it into
  `target/qraft.jar`.
- Tests, each seen red first:
  - `EmbeddedUiResourcesTest` (3), in the default suite: through the
    classloader, the index and the manifest are embedded, every file the
    manifest lists is below `assets/` with no source map, and the index links
    its entry as `./assets/...` with no inline script. Red before the build
    existed (`index.html is not embedded below META-INF/qraft/ui/`).
  - Vitest: `bootstrap.test.ts` (5), including zod refusing an unknown
    authentication mode or profile and a missing nonce, red on a stub; and
    `App.test.tsx` (2), red on an empty shell.
- Two tests broke on what this step added, and were fixed test first:
  - The Docker tests' check that `target/qraft.jar` is current walked all of
    `src/main`, and `npm ci` writes `node_modules/.package-lock.json` at the
    time it runs, so the end-to-end and Docker suites would have found the jar
    stale. It now skips `node_modules`; the lockfile stays an input.
    `SharedDockerClusterFreshnessTest` has a case for each, the first seen red
    with that exact message.
  - `DockerDeploymentContractTest` read every file below `src/main` as UTF-8,
    and `node_modules` holds binaries (`MalformedInputException`). Its two
    walks now skip `node_modules`, the interface's own source still scanned.
- Also found by the first full run, not caused by this step:
  `DnsServerTest.aPortHeldForUdpIsLeftForTheNextCandidate`'s helper took a TCP
  `bind(0)` port and bound UDP on it, which Windows refused for a port it
  holds for UDP alone (handover, section 6). The helper now picks random ports
  from the dynamic range, as the listener does. On 2026-11-14 the helper was
  removed: the test now offers the two held ports and then random ones, records
  every port tried, and asserts the held two came first and the server listens
  on the last, so it no longer relies on a port staying free between a check
  and the server's bind.
- Exit, from the check copy with no `node` or `npm` on the `PATH`:
  `mvn clean install` installed its own Node and passed the default suite,
  1,986, every coverage gate, and Vitest, 7, in 4 min 6 s. `target/qraft.jar`
  holds `META-INF/qraft/ui/index.html`, `asset-manifest.json` and
  `assets/index-DjytTdhu.js`, the same content hash as a build with Node
  24.11.1, and no `.map` file, `node_modules` or source. Audit over the 581
  files the run wrote: 832 error lines and 732 stack traces, all flagged; no
  JVM crash file (`logs/ui-step3-install2-no-node-*`).
- New build warnings, none an error: npm reports `whatwg-encoding` deprecated,
  inside `jsdom`, a test dependency; Rollup drops an annotation it cannot place
  inside zod; and Vite notes the bundle is above 500 kB (516 kB, 165 kB
  gzipped).
- Every build now needs the npm registry, and the first on a machine
  nodejs.org, since the Node download is then cached in the local Maven
  repository.
- Test review, 2026-11-14, of what Steps 2 and 3 left untested:
  - One defect found, test first: a template without a head had the base and
    the bootstrap put before its doctype, which puts a browser into quirks
    mode. They now go after a leading doctype
    (`aTemplateWithoutAHeadKeepsItsDoctypeFirst`, red first). The packaged
    template has a head, so only a development directory could meet it.
  - `HttpApiServerAdminUiTest` gained cases that passed when written, on
    behaviour already present: a template with neither head nor doctype, where
    a `<header>` element is not taken for a head; `index.html` requested by
    name is rendered; a refused method names `GET, HEAD` and keeps the security
    headers; `HEAD` gives `GET`'s length and headers without a body; a file of
    an unknown type is served as `application/octet-stream`; and assets without
    an index answer not found rather than fall back.
  - Vitest: `Root.test.tsx` checks that every style element Ant Design adds
    carries the request's nonce, which the policy needs or a browser renders
    the page unstyled. `main.tsx`'s tree moved into `Root.tsx` so a test can
    render it (red first on the missing module). The case was seen to fail
    with the `csp` setting removed, and passes with it.
  - Exit, from the check copy with no `node` or `npm` on the `PATH`:
    `mvn clean install` passed the default suite, 1,993, every coverage gate,
    and Vitest, 9, in 4 min 12 s; the jar holds the interface and no `.map`
    file. Audit of the surefire reports and the Maven log, 543 files: 556
    error lines and 490 stack traces, all flagged; no JVM crash file
    (`logs/better-tests-install-20261114-100433.log`).

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
