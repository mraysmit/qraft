#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

service=${1:-help}
case "$service" in
  cluster)
    "$SCRIPT_DIR/build-runtime.sh"
    echo "Starting Qraft single-server development environment..."
    docker compose -f compose/docker-compose-single-server.yml up -d
    echo "Server with embedded HTTP API available at http://localhost:8080"
    ;;
  multinode)
    "$SCRIPT_DIR/build-runtime.sh"
    echo "Starting Qraft multi-node cluster..."
    docker compose -f compose/docker-compose-cluster.yml up -d
    echo "Servers available at http://localhost:8081, :8082, and :8083"
    ;;
  stop)
    for file in docker-compose-single-server.yml docker-compose-cluster.yml docker-compose-5node.yml docker-compose-network-test.yml; do
      docker compose -f "compose/$file" down >/dev/null 2>&1 || true
    done
    echo "Services stopped."
    ;;
  status)
    docker ps --filter name=qraft- --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
    ;;
  *)
    echo "Usage: ./start.sh {cluster|multinode|stop|status}"
    echo "Five servers and the network-partition cluster: ./start-quick.sh cluster 5node"
    echo "The observability stack: ./start-observability.sh"
    ;;
esac
