# Qraft

**Last updated:** 2026-11-21

Qraft is a service discovery and coordination platform in the style of Consul, written in Java 27 and built
on Raft consensus. A cluster of servers replicates every change through a Raft log and applies it in the
same order on each server. Clients run beside applications: they register services, run health checks,
and give the applications a local API for discovery, key/value data, sessions and locks. Qraft builds into
one executable jar, `target/qraft.jar`, which runs in server mode or client mode and also carries the
command-line tools and an embedded administrative interface.

## Features

- **Catalog and health.** Nodes, services and health checks, with health queries that can return only
  passing instances.
- **Client mode.** A local API on the application's host for registering services and checks and for
  discovery reads, which the client forwards to the servers. Anti-entropy keeps the servers' catalog in line
  with what the client has registered.
- **ACLs and tokens.** Tokens, policies and roles, a default policy, and ACL enforcement on the client's
  local API as well as on the servers.
- **Auth methods.** `jwt` and `kubernetes` auth methods with binding rules, and login and logout that
  exchange an external credential for a Qraft token.
- **Audit file.** An optional audit log written to a rotated file.
- **TLS.** HTTPS, TLS for the gRPC API, and mutual TLS between servers on the Raft port, with certificate
  reloading.
- **Key/value store.** Reads, recursive reads, key listings, writes with check-and-set, and deletes.
- **Consistency modes and blocking queries.** Default, `consistent` and `stale` reads, and blocking queries
  that wait on an index.
- **Sessions and locks.** Sessions with a TTL, and locks on keys held by a session.
- **Tenants and namespaces.** Logical isolation of state and policy.
- **Events.** A server's events, with a paged query and a watch.
- **DNS.** Service discovery over DNS, from servers and from clients.
- **Write forwarding.** A server that is not the leader forwards a write to the leader.
- **Observability.** OpenTelemetry traces and metrics over OTLP, a Prometheus endpoint, and structured
  logs.
- **Administrative interface.** A React interface served by each server at `/ui/`.
- **Operator and recovery tools.** Raft peer listing and removal, offline inspection of a data directory, and
  recovery of a cluster that lost its quorum.

## Structure

Qraft is one Maven project. Its code is divided into layers of packages, and `PackageDependencyTest` keeps
the layers apart:

| Layer | Packages | What it holds |
|---|---|---|
| Raft | `dev.mars.qraft.raft` | Consensus, the gRPC Raft transport, storage on the RaftLog write-ahead log, membership, and Raft metrics. |
| Replicated state | `dev.mars.qraft.state` | Deterministic commands and the state they build: catalog, ACLs, key/value, sessions, tenants and namespaces. |
| Common | `dev.mars.qraft.common` | Shared domain types, configuration loading, the ACL rule model, DNS, TLS material, events, and executors. |
| Client | `dev.mars.qraft.client` | Client mode: registration, anti-entropy, local checks, the local API, client-side ACLs and DNS. It also holds the Java client library, `dev.mars.qraft.client.api`, with `QraftApi`, `KeyValue`, `Sessions` and `LeaderElection`. |
| Server | `dev.mars.qraft.server` | Server mode: the HTTP and gRPC APIs, ACL resolution and auth methods, health processing, telemetry, the administrative interface, and offline recovery. |
| Runtime | `dev.mars.qraft.runtime` | The entry point, `QraftRuntimeApplication`. It starts server or client mode, or runs the `operator`, `kv` and `acl` commands. |

Section 1.1 of [the design](docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md) lists the packages
and the dependencies each layer may have.

## Prerequisites

- **JDK 27 or newer.** The build refuses an older JDK.
- **Maven 3.9 or newer.**
- **Network access to nodejs.org and the npm registry.** The build downloads Node and the interface's npm
  packages; no local Node installation is needed.
- **Docker**, only for the Docker test suite and the Docker clusters.

## Build

```sh
mvn clean install
```

This builds the administrative interface, compiles, runs the default test suite and the coverage checks, and
packages `target/qraft.jar`. [docs/TESTING.md](docs/TESTING.md) describes the three test suites and how to run
one test class.

## Run

The jar takes the mode first, then an optional configuration file:

```sh
java -jar target/qraft.jar server --config server.json
java -jar target/qraft.jar client --config client.json
```

Qraft reads its configuration only from a JSON file, never from environment variables. It finds the file in
this order:

1. `--config <path>` after the mode;
2. the JVM property `-Dqraft.config=<path>`, given before `-jar`;
3. `config/server.json` or `config/client.json`, relative to the working directory;
4. `/etc/qraft/server.json` or `/etc/qraft/client.json`.

A single server for a first look needs only its id. This file turns telemetry off, since no collector is
running:

```json
{"version": 1, "server": {"id": "server1", "telemetry": {"enabled": false}}}
```

The server then listens on the default ports below and keeps its Raft data in `./data/raft/server1`.
[docker/config/client.json](docker/config/client.json) is a complete client file. A client and a server on
the same machine both default to HTTP port 8080, so give the client another `client.httpPort`.
[docs/CONFIGURATION.md](docs/CONFIGURATION.md) describes every setting.

The same jar runs the command-line tools. Each talks to a server's HTTP API at `127.0.0.1:8080` unless given
`--http-addr`, and prints its usage when given wrong arguments:

```sh
java -jar target/qraft.jar operator raft list-peers
java -jar target/qraft.jar operator raft inspect --dir <stopped data directory>
java -jar target/qraft.jar kv put app/config @config.json
java -jar target/qraft.jar kv get app/ --recurse
java -jar target/qraft.jar acl bootstrap
java -jar target/qraft.jar acl token list --token-file <file>
```

The `operator raft` commands are `list-peers`, `remove-peer`, `inspect` and `recover`; `inspect` and
`recover` work on stopped data directories and contact no server. [docs/OPERATIONS.md](docs/OPERATIONS.md)
explains when to use them.

To run clusters in Docker, see [docker/README.md](docker/README.md).

## Default ports

| Port | Use | Setting |
|---|---|---|
| 8080 | Server HTTP API, health and the administrative interface at `/ui/`; a client's local API | `server.http.port`; `client.httpPort` |
| 9080 | Raft traffic between servers | `server.raft.port` |
| 10080 | Server gRPC API | `server.apiGrpcPort` |
| 8600 | DNS, UDP and TCP, when a `dns` section is configured | `server.dns.port`; `client.dns.port` |
| 9464 | Prometheus metrics, when telemetry is enabled | `server.telemetry.prometheusPort` |

A client's local API listens on `127.0.0.1` unless `client.bindAddress` says otherwise.

## Documentation

| Document | Contents |
|---|---|
| [docs/HTTP_API.md](docs/HTTP_API.md) | Every HTTP route of servers and clients |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | Every setting of the server and client files |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Running a cluster: storage, backup, recovery, ACLs, TLS, upgrades |
| [docker/README.md](docker/README.md) | Docker clusters and the observability stack, started by hand |
| [docs/TESTING.md](docs/TESTING.md) | The test suites, and how logs are audited |
| [docs/PROJECT_STANDARDS.md](docs/PROJECT_STANDARDS.md) | Coding and testing standards |
| [docs/JENKINS.md](docs/JENKINS.md) | The Jenkins pipeline |
| [docs/QRAFT_EVENT_ARCHITECTURE.md](docs/QRAFT_EVENT_ARCHITECTURE.md) | The event architecture |
| [docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md](docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md) | The platform design |
| [docs-design/QRAFT_ADMIN_UI_UX_DESIGN.md](docs-design/QRAFT_ADMIN_UI_UX_DESIGN.md) | The administrative interface's design |

## Licence

Qraft is licensed under the Apache License, Version 2.0: see [LICENSE](LICENSE) and [NOTICE](NOTICE).
[OPEN_SOURCE_USAGE.md](OPEN_SOURCE_USAGE.md) lists the third-party components and what a distribution must
include.
