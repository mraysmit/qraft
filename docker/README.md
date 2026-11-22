# Qraft Docker clusters

**Last updated:** 2026-11-21

This directory holds the Docker Compose clusters and the observability stack that you start by hand, to try
Qraft or watch it run. The Docker test suite starts its own clusters from `src/test/resources`; see
[docs/TESTING.md](../docs/TESTING.md) for it.

Run the commands on this page from this directory, `docker/`, unless a section says otherwise. `start.ps1`
and `start-quick.ps1` name their compose files relative to the current directory and fail from anywhere
else. The `.sh` scripts change to this directory themselves.

## How the image is built

No compose file in this directory uses a prebuilt Qraft image. Each Qraft service builds from [`Dockerfile`](Dockerfile), with the
repository root as its build context (`context: ../..` in a file under `compose/`). The image is
`sapmachine:27-jre-alpine` with `target/qraft.jar` copied in; it does not run Maven. Its entrypoint runs
`java -jar /app/qraft.jar` with the container's command, so a service's command is the mode and its
arguments, such as `["server", "--config", "/etc/qraft/server.json"]`.

Every start command of `start.ps1`, `start.sh`, `start-quick.ps1` and `start-quick.sh` first runs
`build-runtime.ps1` or `build-runtime.sh`. That runs `mvn package -DskipTests` when `pom.xml` or a file
under `src/main` is newer than `target/qraft.jar`, and does nothing when the jar is current.
`src/main/ui/node_modules` is not counted: every `mvn test` reinstalls it, and it is not a source. The start
command then runs `docker compose ... up -d --build`, so the image is rebuilt from that jar. To build the
jar without starting anything:

```powershell
.\build-runtime.ps1
```

```sh
sh ./build-runtime.sh
```

The image `qraft-runtime:test` belongs to the Docker test suite only: `SharedDockerClusterFixture` builds it
with `src/test/resources/docker-compose-build-image.yml`.

## Topologies

| Start command | Compose file | Configuration | Host ports |
|---|---|---|---|
| `start.ps1 cluster` | `compose/docker-compose-single-server.yml` | `config/single/server.json`, `config/client.json` | 8080 server HTTP; 8600 UDP and TCP server DNS. The client publishes no port. |
| `start.ps1 multinode`, `start-quick.ps1 cluster` or `cluster 3node` | `compose/docker-compose-cluster.yml` | `config/three-node/` | 8081 to 8083 |
| `start-quick.ps1 cluster 5node` | `compose/docker-compose-5node.yml` | `config/five-node/` | 8081 to 8085 |
| `start-quick.ps1 cluster network-test` | `compose/docker-compose-network-test.yml` | `config/five-node/` | 8081 to 8085 |
| `start-observability.ps1` | `compose/docker-compose-observability.yml` | `compose/*.yaml`, `compose/prometheus-observability.yml` | see [Observability](#observability) |
| none: run Compose yourself | `compose/docker-compose-observability-cluster.yml` | `config/three-node-observability/` | 8081 to 8083 HTTP; 9464 to 9466 Prometheus; the observability ports |
| none: run Compose yourself | `../src/test/resources/docker-compose-3node-acl-prebuilt.yml` | `config/three-node-acl/` | a free port for each server's 8080 |
| none: run Compose yourself | `../src/test/resources/docker-compose-3node-tls-prebuilt.yml` | `config/three-node-tls/`, `../src/test/resources/tls/` | a free port for each server's 8080 |

The `.sh` scripts take the same arguments: `sh ./start.sh multinode`, `sh ./start-quick.sh cluster 5node`,
`sh ./start-observability.sh`.

The folders `config/three-node-acceptance/`, `config/five-node-acceptance/` and `config/client-acceptance/`
belong to the Docker test suite. Its compose files in `src/test/resources` mount the three-node and client
folders; `DockerDeploymentContractTest` checks all three. Do not change them for a hand-run cluster.

Every server in these files listens inside its container on 8080 for HTTP, 9080 for Raft and 10080 for the
gRPC API. Only the HTTP port, and the DNS port of the single server, is published to the host; Raft and gRPC
traffic stays on the Docker network.

The cluster files use the same container names, `qraft-server1` and so on, so only one of them can run at a
time: stop one before starting another. The Docker test suite's `DockerStartCommandsTest` also fails while a
hand-run cluster is running.

## Starting and stopping

```powershell
.\start.ps1 cluster                    # one server with a client
.\start.ps1 multinode                  # three servers
.\start-quick.ps1 cluster 5node        # five servers
.\start-quick.ps1 cluster network-test # five servers for network experiments
.\start.ps1 status                     # the running qraft- containers
.\start-quick.ps1 status               # the same, with Qraft's networks and volumes
.\start.ps1 stop                       # stop every cluster above, keeping its data
.\start-quick.ps1 stop                 # the same
.\start-quick.ps1 clean                # stop every cluster above and DELETE its data volumes
```

```sh
sh ./start.sh cluster
sh ./start.sh multinode
sh ./start-quick.sh cluster 5node
sh ./start-quick.sh cluster network-test
sh ./start.sh status
sh ./start-quick.sh status
sh ./start.sh stop
sh ./start-quick.sh clean
```

`stop` runs `docker compose down` on the single-server, three-server, five-server and network-test files.
It does not stop the observability stack or the observability cluster; see [Observability](#observability).

**`start-quick ... clean` deletes data.** It runs `docker compose down -v` on the same four files, which
removes their named volumes and with them every server's Raft log and snapshots. Nothing asks for
confirmation, and the data cannot be recovered afterwards.

Follow a cluster's logs with `docker compose -f compose/<file>.yml logs -f`.

### Data volumes are shared between cluster types

All compose files in `compose/` belong to one Compose project, named after that directory, and the
three-server, five-server and network-test files name their volumes alike: `server1-data` to
`server5-data`. A cluster type therefore starts with the Raft data the previous one left, including its
membership. Run `start-quick ... clean` between cluster types when you want a clean start, knowing that it
deletes that data.

The single-server cluster and the observability cluster mount no data volume. Their servers keep their data
in the container, and `docker compose down` removes it with the container.

## Endpoints

Check a server:

```powershell
Invoke-RestMethod http://localhost:8081/health/ready
```

```sh
curl -fsS http://localhost:8081/health/ready
```

The administrative interface is at `/ui/` on each server's HTTP port: http://localhost:8080/ui/ for the
single server, and http://localhost:8081/ui/ to http://localhost:8085/ui/ for the others.

The jar's command-line tools run inside a server container, against that server:

```sh
docker exec qraft-server1 java -jar /app/qraft.jar operator raft list-peers
```

Every compose file publishes each port as `${PUBLISHED_PORT:-<port>}`, and the single-server file its DNS
port as `${PUBLISHED_DNS_PORT:-8600}`. Leave them unset for the ports on this page. Set `PUBLISHED_PORT=0`
and Docker picks a free host port for each one; `docker port <container>` shows which. Compose reads these
variables; Qraft does not.

## The single server and its client

`start.ps1 cluster` starts one server, `qraft-server-dev`, and one client, `qraft-client-dev`. The client's
file, [`config/client.json`](config/client.json), is a complete client example: it names the server by its
origin, `http://server:8080`, and registers a service called `web`.

The client's local API listens on `127.0.0.1` inside its container and publishes no port. Reach it from
inside the container, as an application on the same host would:

```sh
docker exec qraft-client-dev curl -fsS http://127.0.0.1:8080/health/ready
docker exec qraft-client-dev curl -fsS -X PUT http://127.0.0.1:8080/v1/client/service/register \
  -d '{"id":"orders-1","name":"orders","port":9000,"checks":[{"id":"app","type":"ttl","ttlMs":60000}]}'
docker exec qraft-client-dev curl -fsS -X PUT http://127.0.0.1:8080/v1/client/check/pass/app
docker exec qraft-client-dev curl -fsS 'http://127.0.0.1:8080/v1/health/service/orders?passing'
```

The client forwards discovery reads to the servers. [docs/HTTP_API.md](../docs/HTTP_API.md) describes every
route.

The server also answers DNS on port 8600, published over UDP and TCP:

```sh
dig @127.0.0.1 -p 8600 web.service.qraft SRV
```

### Giving other containers the client's local API

An application in its own container can share the client's network namespace with
`network_mode: "service:client"`. A container outside that namespace is refused, even on the same Docker
network.

Setting `client.bindAddress` to `0.0.0.0` instead opens the local API to anything that can reach the port.
With ACLs off, the local API has no authentication: a caller can register and remove the node's services and
checks, set their status and maintenance, read the client's settings, discover services in any tenant and
namespace, and make the client leave and exit. With client ACLs on, each request needs a token that grants
it, but the local API serves plain HTTP even when the client calls its servers over HTTPS, so those tokens
cross the network in the clear. Either way, keep the port on a network that only its applications reach,
and do not publish it. [docs/OPERATIONS.md](../docs/OPERATIONS.md#enable-acls-on-a-new-cluster)
covers ACLs and tokens, and [docs/CONFIGURATION.md](../docs/CONFIGURATION.md) the client settings.

## The network-test cluster

`compose/docker-compose-network-test.yml` runs the five servers of `config/five-node/`, and defines two extra
networks, `raft-partition-a` and `raft-partition-b`. It also defines a
network monitor, `qraft-network-monitor`, from the image `nicolaka/netshoot:latest`, which joins all three
networks. The monitor is in the Compose profile `monitoring`, so `start-quick ... network-test` does not
start it. Start it after the cluster:

```sh
docker compose -f compose/docker-compose-network-test.yml --profile monitoring up -d network-monitor
docker exec -it qraft-network-monitor bash
```

The monitor mounts the host's Docker socket, `/var/run/docker.sock`, which gives anything run in it control
of the host's Docker engine.

The servers hold no capability to change their own network, and their image has no `tc` or `iptables`. To
delay or drop a server's traffic, run the tools in a container that joins the server's network namespace and
holds `NET_ADMIN` itself; the server's container needs nothing:

```sh
docker run --rm --network container:qraft-server1 --cap-add NET_ADMIN nicolaka/netshoot \
    tc qdisc add dev eth0 root netem delay 200ms
docker run --rm --network container:qraft-server1 --cap-add NET_ADMIN nicolaka/netshoot \
    tc qdisc del dev eth0 root
```

To cut a server off, disconnect it from the cluster's network and connect it again:
`docker network disconnect compose_raft-cluster qraft-server1`, then `docker network connect compose_raft-cluster
qraft-server1`.

## ACL and TLS clusters

No start script runs a cluster with ACLs or TLS. The Docker test suite's compose files do, and you can run
them by hand. They use the `qraft-runtime:test` image, so build it first. From the repository root:

```sh
sh docker/build-runtime.sh    # or, in PowerShell: .\docker\build-runtime.ps1
docker compose -f src/test/resources/docker-compose-build-image.yml build
docker compose -p qraft-acl -f src/test/resources/docker-compose-3node-acl-prebuilt.yml up -d
docker compose -p qraft-acl -f src/test/resources/docker-compose-3node-acl-prebuilt.yml port server1 8080
```

The `port` command prints the host port of server1's HTTP API. Give each cluster a project name of its own
with `-p`: both files name their volumes alike, so without it the two clusters share Raft data. Stop a
cluster with the same `-p` and `-f` and `down`; `down -v` also deletes its data.

**ACLs.** `docker-compose-3node-acl-prebuilt.yml` runs three servers with ACLs enabled and the default policy
`deny`. Each installs the management token in `docker/config/three-node-acl/management-token`, a fixed value
committed for tests. Use it as the token of the first requests:

```sh
java -jar target/qraft.jar acl token list --http-addr 127.0.0.1:<port> \
  --token-file docker/config/three-node-acl/management-token
```

**TLS.** `docker-compose-3node-tls-prebuilt.yml` runs three servers that serve only TLS: HTTPS and gRPC
requiring a client certificate, and mutual TLS on the Raft port. It mounts the test certificates from
`src/test/resources/tls`, which are public and for tests only. The server certificate names `localhost`, so
from the host:

```sh
curl -fsS --cacert src/test/resources/tls/ca.pem --cert src/test/resources/tls/client.pem \
  --key src/test/resources/tls/client.key https://localhost:<port>/health/ready
```

[docs/OPERATIONS.md](../docs/OPERATIONS.md) covers ACLs, tokens and transport security for real clusters.

## Observability

The observability stack is an OpenTelemetry Collector, Tempo, Prometheus, Loki and Grafana.

```powershell
.\start-observability.ps1          # start the stack and wait for Grafana
.\start-observability.ps1 -Status  # its containers
.\start-observability.ps1 -Logs    # follow the logs of every container in the stack
.\start-observability.ps1 -Down    # stop it and keep its data
.\start-observability.ps1 -Clean   # stop it and DELETE its data volumes
```

```sh
sh ./start-observability.sh
sh ./start-observability.sh status
sh ./start-observability.sh logs
sh ./start-observability.sh down
sh ./start-observability.sh clean
```

| Service | Endpoint |
|---|---|
| Grafana | http://localhost:3000, user `admin`, password `admin` |
| Prometheus | http://localhost:9090 |
| Tempo | http://localhost:3200; gRPC on 9095 |
| Loki | http://localhost:3100 |
| OTLP gRPC | localhost:4317 |
| OTLP HTTP | localhost:4318 |
| Collector metrics and health | localhost:8888 and localhost:13133 |

The stack alone serves Qraft servers that run on this machine, outside Docker. In each server's file set
`server.telemetry.enabled` to `true` and `server.telemetry.otlpEndpoint` to `http://localhost:4317`.
Prometheus scrapes `server.telemetry.prometheusPort` on this machine at 9464 for one server, and at 9471,
9472 and 9473 for three.

### The stack with three servers

`compose/docker-compose-observability-cluster.yml` runs the same stack with three servers that report to it,
using `config/three-node-observability/`. No script starts it:

```powershell
.\build-runtime.ps1
docker compose -f compose/docker-compose-observability-cluster.yml up -d --build
```

The servers' HTTP APIs are on 8081 to 8083 and their Prometheus endpoints on 9464 to 9466. `--build`
rebuilds the servers' image from the jar just built; without it, `up` keeps an existing image and runs the
older build.

Neither `start.ps1 stop` nor `start-quick ... stop` takes this cluster down. Stop it with:

```powershell
docker compose -f compose/docker-compose-observability-cluster.yml down
```

It uses the same container names and ports as the stack alone and as the three-server cluster, so stop those
before starting it. Its Grafana, Prometheus, Tempo and Loki volumes are the same volumes as the stack
alone's: `-v` on either deletes the data of both.

## Logs and fenced servers

A server writes its log to its console and to files under `/app/logs` inside its container. Read the
console with `docker logs <container>`.

`/raft/status` reports `fenced` and `fenceReason`. A fenced server stays live but answers `/health/ready`
with HTTP 503. Keep its logs and storage, and follow
[the recovery runbook](../docs/OPERATIONS.md#detecting-corruption-and-fencing).

## Writing your own compose file

For a file in `docker/compose/`, build from the repository root and mount configuration from `docker/config/`:

```yaml
services:
  client:
    build:
      context: ../..
      dockerfile: docker/Dockerfile
    command: ["client", "--config", "/etc/qraft/example-client.json"]
    volumes:
      - ../config/client.json:/etc/qraft/example-client.json:ro
```

A file mounted at `/etc/qraft/client.json` or `/etc/qraft/server.json` needs no `--config`: Qraft looks
there last. [The README](../README.md#run) gives the full lookup order. Qraft reads no environment
variables for its configuration.
