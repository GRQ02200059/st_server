#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/rebuild_remote.sh"

test -x "$SCRIPT"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

PROJECT_DIR="$TMP_DIR/st_server"
mkdir -p \
  "$PROJECT_DIR/scripts" \
  "$PROJECT_DIR/build/stale" \
  "$PROJECT_DIR/.gradle/stale" \
  "$PROJECT_DIR/data/accounts" \
  "$TMP_DIR/fake-java/bin"

cp "$SCRIPT" "$PROJECT_DIR/scripts/rebuild_remote.sh"
chmod +x "$PROJECT_DIR/scripts/rebuild_remote.sh"
touch "$PROJECT_DIR/build.gradle.kts"
printf 'stale build output\n' >"$PROJECT_DIR/build/stale/marker"
printf 'stale Gradle state\n' >"$PROJECT_DIR/.gradle/stale/marker"
printf 'persistent player data\n' >"$PROJECT_DIR/data/accounts/sentinel"

cat >"$TMP_DIR/fake-java/bin/java" <<'EOF'
#!/usr/bin/env bash
echo 'openjdk version "17.0.0"'
EOF
chmod +x "$TMP_DIR/fake-java/bin/java"

cat >"$PROJECT_DIR/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\0' "$@" >>"$GRADLE_CALLS"

if [ "${1:-}" = "--stop" ]; then
  exit 0
fi

test ! -e build
test ! -e .gradle
test -f data/accounts/sentinel
test "$(cat data/accounts/sentinel)" = "persistent player data"
EOF
chmod +x "$PROJECT_DIR/gradlew"

GRADLE_CALLS="$TMP_DIR/gradle-calls" \
JAVA_HOME="$TMP_DIR/fake-java" \
bash "$PROJECT_DIR/scripts/rebuild_remote.sh"

EXPECTED_ARGS=$'--stop\n-Pkotlin.compiler.execution.strategy=in-process\nclean\ninstallDist\n--no-daemon\n--rerun-tasks'
ACTUAL_ARGS="$(tr '\0' '\n' <"$TMP_DIR/gradle-calls")"

test "$ACTUAL_ARGS" = "$EXPECTED_ARGS"
printf 'PASS: rebuild_remote.sh preserves data and performs a cold installDist build\n'
