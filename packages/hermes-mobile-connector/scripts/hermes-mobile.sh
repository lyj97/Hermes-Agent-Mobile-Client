#!/usr/bin/env bash
set -euo pipefail

PORT="${HERMES_DASHBOARD_PORT:-9119}"
HOST="${HERMES_DASHBOARD_HOST:-0.0.0.0}"
OS_NAME="$(uname -s)"
SERVICE_NAME="hermes-mobile-dashboard.service"
LAUNCHD_LABEL="dev.hermes.mobile.dashboard"
LAUNCHD_PLIST="${HOME}/Library/LaunchAgents/${LAUNCHD_LABEL}.plist"

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing command: $1" >&2
    exit 1
  }
}

detect_hermes_bin() {
  if [[ -n "${HERMES_BIN:-}" && -x "${HERMES_BIN}" ]]; then
    echo "${HERMES_BIN}"
    return
  fi
  if command -v hermes >/dev/null 2>&1; then
    command -v hermes
    return
  fi
  for candidate in \
    "${HOME}/.local/bin/hermes" \
    "${HOME}/.npm-global/bin/hermes" \
    "/opt/hermes-agent/.venv/bin/hermes" \
    "/usr/local/bin/hermes" \
    "/opt/homebrew/bin/hermes"; do
    [[ -x "${candidate}" ]] && {
      echo "${candidate}"
      return
    }
  done
  return 1
}

run_user() {
  echo "${HERMES_RUN_USER:-${USER:-$(id -un)}}"
}

run_home() {
  local user
  user="$(run_user)"
  eval echo "~${user}"
}

workdir() {
  if [[ -n "${HERMES_WORKDIR:-}" ]]; then
    echo "${HERMES_WORKDIR}"
  else
    run_home
  fi
}

tailscale_ip() {
  command -v tailscale >/dev/null 2>&1 || return 0
  tailscale ip -4 2>/dev/null | head -n 1 || true
}

tailscale_dns() {
  command -v tailscale >/dev/null 2>&1 || return 0
  command -v python3 >/dev/null 2>&1 || return 0
  tailscale status --json 2>/dev/null | python3 -c 'import json,sys
try:
    data=json.load(sys.stdin)
    name=(data.get("Self") or {}).get("DNSName") or ""
    print(name.rstrip("."))
except Exception:
    pass' || true
}

lan_ip() {
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true
  else
    hostname -I 2>/dev/null | awk '{print $1}' || true
  fi
}

public_ip() {
  curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || true
}

mobile_urls() {
  local ts_dns ts_ip lan public
  ts_dns="$(tailscale_dns)"
  ts_ip="$(tailscale_ip)"
  lan="$(lan_ip)"
  public="$(public_ip)"

  [[ -n "${ts_dns}" ]] && echo "http://${ts_dns}:${PORT}"
  [[ -n "${ts_ip}" ]] && echo "http://${ts_ip}:${PORT}"
  [[ -n "${lan}" ]] && echo "http://${lan}:${PORT}"
  [[ -n "${public}" ]] && echo "http://${public}:${PORT}"
}

health() {
  curl -fsS --max-time 5 "http://127.0.0.1:${PORT}/api/status" >/dev/null 2>&1
}

install_linux() {
  need_cmd sudo
  need_cmd systemctl
  need_cmd curl
  local hermes_bin user home cwd
  hermes_bin="$(detect_hermes_bin)" || {
    echo "Hermes CLI not found. Install Hermes Agent first, then rerun this command." >&2
    exit 1
  }
  user="$(run_user)"
  home="$(run_home)"
  cwd="$(workdir)"

  sudo tee "/etc/systemd/system/${SERVICE_NAME}" >/dev/null <<EOF
[Unit]
Description=Hermes Mobile Dashboard Connector
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${user}
WorkingDirectory=${cwd}
Environment=HOME=${home}
Environment=HERMES_DASHBOARD_TUI=1
ExecStart=${hermes_bin} dashboard --host ${HOST} --port ${PORT} --no-open --insecure --tui
Restart=always
RestartSec=3
KillSignal=SIGINT
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target
EOF

  sudo systemctl daemon-reload
  sudo systemctl enable "${SERVICE_NAME}" >/dev/null
  sudo systemctl restart "${SERVICE_NAME}"
}

install_macos() {
  need_cmd curl
  local hermes_bin cwd
  hermes_bin="$(detect_hermes_bin)" || {
    echo "Hermes CLI not found. Install Hermes Agent first, then rerun this command." >&2
    exit 1
  }
  cwd="$(workdir)"
  mkdir -p "${HOME}/Library/LaunchAgents" "${HOME}/Library/Logs"
  cat > "${LAUNCHD_PLIST}" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>${LAUNCHD_LABEL}</string>
<key>ProgramArguments</key><array>
<string>${hermes_bin}</string><string>dashboard</string><string>--host</string><string>${HOST}</string><string>--port</string><string>${PORT}</string><string>--no-open</string><string>--insecure</string><string>--tui</string>
</array>
<key>EnvironmentVariables</key><dict><key>HERMES_DASHBOARD_TUI</key><string>1</string></dict>
<key>WorkingDirectory</key><string>${cwd}</string>
<key>RunAtLoad</key><true/>
<key>KeepAlive</key><true/>
<key>StandardOutPath</key><string>${HOME}/Library/Logs/hermes-mobile-dashboard.log</string>
<key>StandardErrorPath</key><string>${HOME}/Library/Logs/hermes-mobile-dashboard.err</string>
</dict></plist>
EOF
  launchctl bootout "gui/$(id -u)" "${LAUNCHD_PLIST}" >/dev/null 2>&1 || true
  launchctl bootstrap "gui/$(id -u)" "${LAUNCHD_PLIST}"
  launchctl kickstart -k "gui/$(id -u)/${LAUNCHD_LABEL}" >/dev/null 2>&1 || true
}

install_connector() {
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    install_macos
  else
    install_linux
  fi

  for _ in $(seq 1 25); do
    health && break
    sleep 1
  done

  if ! health; then
    echo "Hermes dashboard did not become healthy at http://127.0.0.1:${PORT}/api/status" >&2
    echo "Run: npx github:your-org-or-user/Hermes-Agent-Mobile-Client logs" >&2
    exit 1
  fi

  echo "Hermes Mobile Connector is ready."
  echo
  print_urls
}

start_connector() {
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    launchctl bootstrap "gui/$(id -u)" "${LAUNCHD_PLIST}" 2>/dev/null || true
    launchctl kickstart -k "gui/$(id -u)/${LAUNCHD_LABEL}" >/dev/null 2>&1 || true
  else
    sudo systemctl start "${SERVICE_NAME}"
  fi
}

stop_connector() {
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    launchctl bootout "gui/$(id -u)" "${LAUNCHD_PLIST}" >/dev/null 2>&1 || true
  else
    sudo systemctl stop "${SERVICE_NAME}" || true
  fi
}

status_connector() {
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    launchctl print "gui/$(id -u)/${LAUNCHD_LABEL}" >/dev/null 2>&1 && echo "Service: active" || echo "Service: inactive"
  else
    systemctl is-active "${SERVICE_NAME}" >/dev/null 2>&1 && echo "Service: active" || echo "Service: inactive"
  fi
  health && echo "Health: ok" || echo "Health: failed"
  echo
  print_urls
}

logs_connector() {
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    tail -n "${1:-120}" "${HOME}/Library/Logs/hermes-mobile-dashboard.log" "${HOME}/Library/Logs/hermes-mobile-dashboard.err" 2>/dev/null || true
  else
    sudo journalctl -u "${SERVICE_NAME}" -n "${1:-120}" --no-pager
  fi
}

uninstall_connector() {
  stop_connector || true
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    rm -f "${LAUNCHD_PLIST}"
  else
    sudo systemctl disable "${SERVICE_NAME}" >/dev/null 2>&1 || true
    sudo rm -f "/etc/systemd/system/${SERVICE_NAME}"
    sudo systemctl daemon-reload
  fi
  echo "Hermes Mobile Connector removed. Hermes Agent data was not touched."
}

print_urls() {
  local first=1
  echo "Paste one of these into Hermes Agent Mobile:"
  while IFS= read -r candidate; do
    [[ -z "${candidate}" ]] && continue
    if [[ "${first}" == "1" ]]; then
      echo "Recommended: ${candidate}"
      first=0
    else
      echo "Fallback:    ${candidate}"
    fi
  done < <(mobile_urls)

  if [[ "${first}" == "1" ]]; then
    echo "Recommended: http://<this-machine-ip>:${PORT}"
  fi

  if command -v tailscale >/dev/null 2>&1; then
    echo
    echo "Tailscale detected. Prefer the Tailscale URL for private cross-network access."
  else
    echo
    echo "Tip: Install Tailscale on this machine and your Android phone to avoid public firewall/router setup."
  fi
}

case "${1:-status}" in
  install) install_connector ;;
  start) start_connector ;;
  stop) stop_connector ;;
  restart|repair) stop_connector || true; start_connector; status_connector ;;
  status) status_connector ;;
  url|urls) print_urls ;;
  logs) shift || true; logs_connector "${1:-120}" ;;
  uninstall) uninstall_connector ;;
  *) echo "Usage: hermes-mobile [install|start|stop|restart|status|url|logs|uninstall]" >&2; exit 2 ;;
esac
