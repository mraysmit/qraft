#!/usr/bin/env sh
set -eu

echo "Qraft Log Extraction Pipeline"
echo "=============================="
echo "Docker log path: $(docker inspect qraft-controller1 --format='{{.LogPath}}' 2>/dev/null || echo unavailable)"

timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
payload=$(printf '{"agentId":"demo-agent","timestamp":"%s","sequenceNumber":999,"status":"healthy"}' "$timestamp")
if curl --fail-with-body -sS -X POST -H 'Content-Type: application/json' \
    --data "$payload" http://localhost:8081/api/v1/agents/heartbeat >/dev/null; then
  echo "Heartbeat sent; log activity generated."
else
  echo "Heartbeat failed; register demo-agent first." >&2
fi

docker logs qraft-controller1 --tail 3 2>/dev/null || echo "No recent logs available."
echo "Pipeline: Java app -> Docker JSON -> Promtail -> Loki -> Grafana"
echo "Grafana: http://localhost:3000"
