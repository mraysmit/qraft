#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"
action=${1:-help}
cluster_type=${2:-3node}

# Takes down what Qraft's own compose files define, and with -v their data volumes too.
down_services() {
  for file in docker-compose-single-server.yml docker-compose-cluster.yml docker-compose-5node.yml docker-compose-network-test.yml; do
    docker compose -f "compose/$file" down "$@" >/dev/null 2>&1 || true
  done
}

case "$action" in
  cluster)
    sh "$SCRIPT_DIR/build-runtime.sh"
    case "$cluster_type" in
      3node) file=compose/docker-compose-cluster.yml ;;
      5node) file=compose/docker-compose-5node.yml ;;
      network-test) file=compose/docker-compose-network-test.yml ;;
      *) echo "Unknown cluster type: $cluster_type" >&2; exit 2 ;;
    esac
    docker compose -f "$file" up -d
    ;;
  stop) down_services ;;
  clean) down_services -v ;;
  status)
    docker ps --filter name=qraft- --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
    docker network ls --filter name=qraft --format 'table {{.Name}}\t{{.Driver}}\t{{.Scope}}'
    docker volume ls --filter name=qraft --format 'table {{.Name}}\t{{.Driver}}'
    ;;
  *)
    echo "Usage: ./start-quick.sh {cluster [3node|5node|network-test]|stop|clean|status}"
    echo "The cluster types share their data volumes: a cluster starts with the data of the one before. clean removes it."
    ;;
esac
