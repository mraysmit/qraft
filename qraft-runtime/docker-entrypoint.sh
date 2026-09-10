#!/bin/sh
set -eu

mode="${QRAFT_MODE:-${1:-}}"
if [ -z "$mode" ]; then
  echo "QRAFT_MODE or a startup mode argument (server|client) is required" >&2
  exit 2
fi

exec java $JAVA_OPTS -jar /app/qraft.jar "$mode"
