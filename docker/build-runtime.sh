#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

ARTIFACT="$REPOSITORY_ROOT/target/qraft.jar"

# A JAR newer than the POM and every production source is current. Packaging again would rewrite target/
# under a Maven build that is running in this tree.
if [ -f "$ARTIFACT" ] && [ -z "$(find "$REPOSITORY_ROOT/pom.xml" "$REPOSITORY_ROOT/src/main" -type f -newer "$ARTIFACT" | head -n 1)" ]; then
  echo "Host-built artifact is current: $ARTIFACT"
  exit 0
fi

echo "Building the Qraft runtime JAR locally with Maven..."
mvn -f "$REPOSITORY_ROOT/pom.xml" package -DskipTests

if [ ! -f "$ARTIFACT" ]; then
  echo "Expected host-built runtime JAR was not created: $ARTIFACT" >&2
  exit 1
fi

echo "Host-built artifact: $ARTIFACT"
