#!/usr/bin/env bash
set -euo pipefail

# Legacy wrapper. Prefer:
#   npx github:areu01or00/Hermes-Agent-Mobile-Client install
#
# Kept for users who already copied the old VPS script path.

SERVICE_NAME="hermes-dashboard.service"
SERVICE_PATH="/etc/systemd/system/${SERVICE_NAME}"
DASHBOARD_PORT="${HERMES_DASHBOARD_PORT:-9119}"
DASHBOARD_HOST="${HERMES_DASHBOARD_HOST:-0.0.0.0}"
RUN_USER="${HERMES_RUN_USER:-$USER}"
RUN_HOME="$(eval echo "~${RUN_USER}")"
WORKDIR="${HERMES_WORKDIR:-$RUN_HOME}"

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing command: $1" >&2
    exit 1
  fi
}

need_cmd systemctl
need_cmd curl

if ! command -v hermes >/dev/null 2>&1; then
  echo "hermes CLI not found in PATH for user '$USER'." >&2
  echo "Install/configure Hermes first, then re-run this script." >&2
  exit 1
fi

echo "[1/6] Writing systemd service: ${SERVICE_PATH}"
sudo tee "${SERVICE_PATH}" >/dev/null <<EOF
[Unit]
Description=Hermes Dashboard
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${RUN_USER}
WorkingDirectory=${WORKDIR}
Environment=HOME=${RUN_HOME}
Environment=HERMES_DASHBOARD_TUI=1
ExecStart=$(command -v hermes) dashboard --host ${DASHBOARD_HOST} --port ${DASHBOARD_PORT} --no-open --insecure --tui
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

echo "[2/6] Reloading systemd + enabling service"
sudo systemctl daemon-reload
sudo systemctl enable "${SERVICE_NAME}" >/dev/null

echo "[3/6] Restarting service"
sudo systemctl restart "${SERVICE_NAME}"

echo "[4/6] Waiting for local dashboard health"
for i in $(seq 1 20); do
  if curl -fsS "http://127.0.0.1:${DASHBOARD_PORT}/api/status" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -fsS "http://127.0.0.1:${DASHBOARD_PORT}/api/status" >/dev/null 2>&1; then
  echo "Dashboard did not become healthy on localhost:${DASHBOARD_PORT}" >&2
  echo "Check: sudo journalctl -u ${SERVICE_NAME} -n 100 --no-pager" >&2
  exit 1
fi

echo "[5/6] Skipping firewall mutation"
echo "Tailscale is recommended. If using public VPS IP, open TCP ${DASHBOARD_PORT} yourself."

echo "[6/6] Detecting public endpoint"
PUBLIC_IP="$(curl -fsS https://api.ipify.org || true)"
if [[ -n "${PUBLIC_IP}" ]]; then
  MOBILE_URL="http://${PUBLIC_IP}:${DASHBOARD_PORT}"
else
  MOBILE_URL="http://<your-vps-ip>:${DASHBOARD_PORT}"
fi

echo
echo "Dashboard is running."
echo "Service: ${SERVICE_NAME}"
echo "Local check:  http://127.0.0.1:${DASHBOARD_PORT}/api/status"
echo "Mobile URL:   ${MOBILE_URL}"
echo
echo "If mobile cannot connect over public IP, open cloud security-group inbound TCP ${DASHBOARD_PORT}."
