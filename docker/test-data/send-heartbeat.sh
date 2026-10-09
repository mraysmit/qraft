#!/usr/bin/env sh
set -eu

sequence=${1:-3}
client_id=${2:-test-client-002}
base_url=${3:-http://localhost:8080}
timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
payload=$(printf '{"clientId":"%s","timestamp":"%s","sequenceNumber":%s,"status":"healthy"}' \
  "$client_id" "$timestamp" "$sequence")

echo "Sending heartbeat with timestamp: $timestamp, sequence: $sequence"
curl --fail-with-body -sS -X POST -H 'Content-Type: application/json' \
  --data "$payload" "$base_url/api/v1/clients/heartbeat"
echo
