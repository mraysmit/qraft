#!/usr/bin/env sh
set -eu

curl --fail-with-body -sS http://localhost:8080/api/v1/agents
echo
