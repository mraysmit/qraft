# Open source usage

**Last updated:** 2026-11-22

## Qraft's licence

Qraft is licensed under the Apache License, Version 2.0, in [LICENSE](LICENSE). Its copyright notice, in
[NOTICE](NOTICE) and in the source file headers, is:

```text
Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
```

Section 4 of the licence sets what a redistribution must include: a copy of the licence, the `NOTICE` file,
the copyright notices, and a notice in each file you change. The header every source file carries is
defined in [docs/PROJECT_STANDARDS.md](docs/PROJECT_STANDARDS.md#101-source-file-headers).

## Java libraries in the jar

`pom.xml` declares these production dependencies. The shaded jar, `target/qraft.jar`, contains them and
their transitive dependencies. The licences are those that each artifact's POM, or its parent POM, declares.

| Maven coordinates | Version | Declared licence |
|---|---|---|
| `io.github.mraysmit:raftlog-core` | 1.5.0 | Apache License 2.0 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.19.4 | Apache License 2.0 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.19.4 | Apache License 2.0 |
| `io.grpc:grpc-protobuf`, `grpc-stub`, `grpc-netty` | 1.68.1 | Apache License 2.0 |
| `com.google.protobuf:protobuf-java`, `protobuf-java-util` | 3.25.5 | BSD 3-Clause |
| `com.google.guava:guava` | 33.5.0-jre | Apache License 2.0 |
| `io.opentelemetry:opentelemetry-sdk`, `opentelemetry-exporter-otlp` | 1.59.0 | Apache License 2.0 |
| `io.opentelemetry:opentelemetry-exporter-prometheus` | 1.59.0-alpha | Apache License 2.0 |
| `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0` | 2.14.0-alpha | Apache License 2.0 |
| `org.slf4j:slf4j-api`, `jul-to-slf4j` | 2.0.17 | MIT |
| `ch.qos.logback:logback-classic` | 1.5.32 | EPL 2.0 or LGPL 2.1 |
| `net.logstash.logback:logstash-logback-encoder` (runtime scope) | 8.1 | Apache License 2.0 and MIT |
| `javax.annotation:javax.annotation-api` | 1.3.2 | CDDL + GPLv2 with Classpath Exception |

The OpenTelemetry BOM 1.59.0 and instrumentation BOM 2.14.0-alpha are imported; the instrumentation BOM sets
the Logback appender's version, and `pom.xml` pins the Prometheus exporter and `org.jetbrains:annotations`
13.0, a transitive dependency the jar bundles.

## npm libraries in the administrative interface

The build bundles these libraries, from [src/main/ui/package.json](src/main/ui/package.json), into the
interface under `META-INF/qraft/ui/` in the jar:

| Package | Version | Licence |
|---|---|---|
| `antd` | 5.29.3 | MIT |
| `react` | 18.3.1 | MIT |
| `react-dom` | 18.3.1 | MIT |
| `zod` | 4.6.7 | MIT |

Their transitive packages are the entries of [src/main/ui/package-lock.json](src/main/ui/package-lock.json)
that are not marked `"dev": true`. The build installs exactly that lockfile with `npm ci`.

## Test and build tools

These are used to build and test Qraft. None of them is in the jar.

- **Java tests:** JUnit Jupiter 5.14.3, Awaitility 4.3.0, Testcontainers 2.0.3 and
  `opentelemetry-sdk-testing` 1.59.0.
- **Interface build and tests:** Vite 6.4.6, Vitest 3.2.7, TypeScript 5.9.3, `@vitejs/plugin-react`,
  jsdom, the Testing Library packages and the React type definitions, all `devDependencies` in
  `package.json`.
- **Node and npm:** `frontend-maven-plugin` 1.15.4 downloads Node v24.22.3, with the npm it bundles, into
  `target/node`.
- **Code generation and packaging:** `protobuf-maven-plugin` 4.1.2 runs `protoc` 3.25.5 and
  `protoc-gen-grpc-java` 1.68.1; `maven-shade-plugin` 3.6.0 builds the jar; JaCoCo 0.8.15 measures coverage.
- **Docker images, used and not distributed:** [docker/Dockerfile](docker/Dockerfile) builds on
  `sapmachine:27-jre-alpine` and adds the Alpine packages `curl`, `tzdata` and `bash`. The observability
  stack runs `otel/opentelemetry-collector-contrib:0.96.0`, `grafana/tempo:2.3.1`, `prom/prometheus:v2.49.1`,
  `grafana/loki:2.9.3` and `grafana/grafana:10.3.1`, and the network-test cluster `nicolaka/netshoot:latest`.

## Before distribution

The tables above list what Qraft declares directly. A distribution must account for everything it actually
contains.

1. **Qraft's `LICENSE` and `NOTICE` ship with the build.** `target/qraft.jar` holds them as `META-INF/LICENSE`
   and `META-INF/NOTICE`, and an image built from `docker/Dockerfile` also in `/usr/share/doc/qraft/`.
2. **Inventory the shaded Java tree.** Resolve the production dependencies with
   `mvn dependency:tree -Dscope=runtime` and compare the list with the contents of `target/qraft.jar`.
   Collect the licence and `NOTICE` files of every artifact in it, transitive ones included.
3. **Check `META-INF` in the jar.** The shade step merges the `META-INF` directories of all those jars.
   `META-INF/NOTICE` is Qraft's notice followed by the `META-INF/NOTICE` of each jar that has one, as written.
   Of any other file that several jars contain, licence files included, it keeps one copy: `META-INF/LICENSE` is
   Qraft's, so the jar does not carry every artifact's licence text. Supply the missing texts separately.
4. **Choose and meet the terms of the dual-licensed libraries.** Logback is offered under EPL 2.0 or
   LGPL 2.1, and the annotations API under CDDL or GPLv2 with Classpath Exception. Record the licence you
   take and meet its conditions.
5. **Inventory the npm bundle.** List the non-development packages in `package-lock.json` and include each
   one's copyright and licence text. The minified bundle in the jar is not a reliable place to find them.
6. **Docker images.** If you distribute an image built from `docker/Dockerfile`, it also contains SapMachine
   and the Alpine packages; their licences apply to it.
7. **Source headers.** Check that every source file carries the header of
   [docs/PROJECT_STANDARDS.md](docs/PROJECT_STANDARDS.md#101-source-file-headers).

This page is guidance, not legal advice.
