#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"
action=${1:-help}
cluster_type=${2:-3node}

stop_services() {
  for file in docker-compose.yml docker-compose-5node.yml docker-compose-network-test.yml docker-compose-loki.yml; do
    docker compose -f "compose/$file" down >/dev/null 2>&1 || true
  done
}

case "$action" in
  cluster)
    "$SCRIPT_DIR/build-runtime.sh"
    case "$cluster_type" in
      3node) file=compose/docker-compose.yml ;;
      5node) file=compose/docker-compose-5node.yml ;;
      network-test) file=compose/docker-compose-network-test.yml ;;
      *) echo "Unknown cluster type: $cluster_type" >&2; exit 2 ;;
    esac
    docker compose -f "$file" up -d
    ;;
  logging) docker compose -f compose/docker-compose-loki.yml up -d ;;
  test)
    curl --fail-with-body -sS -H 'Content-Type: application/json' \
      --data-binary @test-data/test-registration.json http://localhost:8080/api/v1/agents/register
    "$SCRIPT_DIR/test-data/send-heartbeat.sh" 1
    "$SCRIPT_DIR/test-data/check-agents.sh"
    ;;
  stop) stop_services ;;
  clean)
    stop_services
    docker volume prune -f
    docker network prune -f
    ;;
  status)
    docker ps --filter name=qraft- --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
    docker network ls --filter name=qraft --format 'table {{.Name}}\t{{.Driver}}\t{{.Scope}}'
    docker volume ls --filter name=qraft --format 'table {{.Name}}\t{{.Driver}}'
    ;;
  *)
    echo "Usage: ./start-quick.sh {cluster [3node|5node|network-test]|logging|test|stop|clean|status}"
    ;;
esac
