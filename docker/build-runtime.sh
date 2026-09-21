#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

echo "Building the Qraft runtime JAR locally with Maven..."
mvn -f "$REPOSITORY_ROOT/pom.xml" package -pl qraft-runtime -am -DskipTests

ARTIFACT="$REPOSITORY_ROOT/qraft-runtime/target/qraft-runtime.jar"
if [ ! -f "$ARTIFACT" ]; then
  echo "Expected host-built runtime JAR was not created: $ARTIFACT" >&2
  exit 1
fi

echo "Host-built artifact: $ARTIFACT"
