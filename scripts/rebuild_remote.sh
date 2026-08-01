#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="$PROJECT_ROOT/gradlew"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

test -x "$GRADLEW" || fail "Gradle wrapper is missing or not executable: $GRADLEW"
test -f "$PROJECT_ROOT/build.gradle.kts" || fail "Not an STZB server checkout: $PROJECT_ROOT"

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$(command -v java || true)"
  test -n "$JAVA_BIN" || fail "Java was not found. Install OpenJDK 17 or set JAVA_HOME."
  JAVA_BIN="$(readlink -f "$JAVA_BIN")"
  JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
fi

test -x "$JAVA_HOME/bin/java" || fail "JAVA_HOME does not contain an executable java: $JAVA_HOME"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

cd "$PROJECT_ROOT"

echo "[1/3] Stopping Gradle daemons..."
"$GRADLEW" --stop || true

echo "[2/3] Removing project build caches (data/ is preserved)..."
rm -rf -- build .gradle

echo "[3/3] Rebuilding install distribution..."
exec "$GRADLEW" \
  -Pkotlin.compiler.execution.strategy=in-process \
  clean \
  installDist \
  --no-daemon \
  --rerun-tasks
