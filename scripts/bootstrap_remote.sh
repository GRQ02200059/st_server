#!/usr/bin/env bash
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/GRQ02200059/st_server.git}"
BRANCH="${BRANCH:-main}"
APP_DIR="${APP_DIR:-/opt/st_server}"
SERVICE_NAME="${SERVICE_NAME:-st_server}"
STZB_PORT="${STZB_PORT:-59979}"
STZB_PUBLIC_HOST="${STZB_PUBLIC_HOST:-152.136.236.184}"
JAVA_PACKAGE="${JAVA_PACKAGE:-openjdk-17-jdk}"
GRADLE_DISTRIBUTION_URL="${GRADLE_DISTRIBUTION_URL:-https\\://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip}"

if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
  RUN_USER="${RUN_USER:-stzb}"
else
  if ! command -v sudo >/dev/null 2>&1; then
    echo "This installer needs root privileges or sudo." >&2
    exit 1
  fi
  SUDO="sudo"
  RUN_USER="${RUN_USER:-$(id -un)}"
fi

run_as_user() {
  if [ "$(id -u)" -eq 0 ] && [ "$RUN_USER" != "root" ]; then
    sudo -H -u "$RUN_USER" "$@"
  else
    "$@"
  fi
}

if ! command -v apt-get >/dev/null 2>&1; then
  echo "Only Ubuntu/Debian apt-get based servers are supported by this script." >&2
  exit 1
fi

echo "[1/5] Installing system packages..."
$SUDO apt-get update
$SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y \
  ca-certificates \
  curl \
  git \
  unzip \
  "$JAVA_PACKAGE"

if [ "$(id -u)" -eq 0 ] && ! id "$RUN_USER" >/dev/null 2>&1; then
  echo "[2/5] Creating service user $RUN_USER..."
  useradd --system --create-home --shell /usr/sbin/nologin "$RUN_USER"
else
  echo "[2/5] Using service user $RUN_USER..."
fi

echo "[3/5] Syncing repository into $APP_DIR..."
$SUDO mkdir -p "$(dirname "$APP_DIR")"
if [ -d "$APP_DIR/.git" ]; then
  $SUDO chown -R "$RUN_USER":"$RUN_USER" "$APP_DIR"
  run_as_user git -C "$APP_DIR" fetch origin "$BRANCH"
  run_as_user git -C "$APP_DIR" reset --hard "origin/$BRANCH"
else
  if [ -e "$APP_DIR" ]; then
    echo "$APP_DIR exists but is not a git checkout; move it away first." >&2
    exit 1
  fi
  $SUDO git clone --branch "$BRANCH" "$REPO_URL" "$APP_DIR"
  $SUDO chown -R "$RUN_USER":"$RUN_USER" "$APP_DIR"
fi

echo "[4/5] Building server..."
WRAPPER_PROPERTIES="$APP_DIR/gradle/wrapper/gradle-wrapper.properties"
run_as_user sed -i \
  -e "s#^distributionUrl=.*#distributionUrl=$GRADLE_DISTRIBUTION_URL#" \
  -e "s#^networkTimeout=.*#networkTimeout=60000#" \
  -e "s#^retries=.*#retries=3#" \
  -e "s#^retryBackOffMs=.*#retryBackOffMs=2000#" \
  "$WRAPPER_PROPERTIES"
run_as_user bash -c 'cd "$1" && ./gradlew --no-daemon clean installDist' _ "$APP_DIR"

if ! command -v systemctl >/dev/null 2>&1; then
  echo "systemctl not found. Start manually with:"
  echo "  STZB_PORT=$STZB_PORT $APP_DIR/build/install/stzb-server/bin/stzb-server"
  exit 0
fi

echo "[5/5] Installing and starting systemd service $SERVICE_NAME..."
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
TMP_UNIT="$(mktemp)"
cat >"$TMP_UNIT" <<EOF
[Unit]
Description=STZB private server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$RUN_USER
WorkingDirectory=$APP_DIR
Environment=STZB_PORT=$STZB_PORT
Environment=STZB_PUBLIC_HOST=$STZB_PUBLIC_HOST
ExecStart=$APP_DIR/build/install/stzb-server/bin/stzb-server
Restart=on-failure
RestartSec=3
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

$SUDO install -m 0644 "$TMP_UNIT" "$UNIT_FILE"
rm -f "$TMP_UNIT"
$SUDO systemctl daemon-reload
$SUDO systemctl enable --now "$SERVICE_NAME"

echo
echo "Installed."
echo "Service: $SERVICE_NAME"
echo "Port: $STZB_PORT"
echo "Public host: $STZB_PUBLIC_HOST"
echo "Status: systemctl status $SERVICE_NAME --no-pager"
echo "Logs: journalctl -u $SERVICE_NAME -f"
