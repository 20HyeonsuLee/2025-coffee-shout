#!/bin/bash
set -e

TUNNEL_PID_FILE="/tmp/redis-tunnel.pid"

# docker compose 종료
echo "[1/2] Stopping monitoring stack..."
cd "$(dirname "$0")"
docker compose down

# SSH 터널 종료
echo "[2/2] Closing SSH tunnel..."
if [ -f "$TUNNEL_PID_FILE" ]; then
  PID=$(cat "$TUNNEL_PID_FILE")
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    echo "    SSH tunnel (PID: $PID) closed."
  else
    echo "    SSH tunnel already closed."
  fi
  rm -f "$TUNNEL_PID_FILE"
else
  echo "    No tunnel PID file found."
fi

echo ""
echo "Done."