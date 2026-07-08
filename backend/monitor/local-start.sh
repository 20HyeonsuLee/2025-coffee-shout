#!/bin/bash
set -e

# ============================================================
# ElastiCache SSH 터널 + 모니터링 스택 로컬 실행 스크립트
# ============================================================

# 설정 (필요시 수정)
EC2_HOST="${EC2_HOST:-43.200.188.232}"
EC2_USER="${EC2_USER:-ubuntu}"
SSH_KEY="${SSH_KEY:-$HOME/home/aws/bcsd/myServerKey.pem}"
ELASTICACHE_HOST="coffee-shout-test-f6yew9.serverless.apn2.cache.amazonaws.com"
ELASTICACHE_PORT=6379
LOCAL_PORT=6379
TUNNEL_PID_FILE="/tmp/redis-tunnel.pid"

# SSH 키 옵션
SSH_KEY_OPT=""
if [ -n "$SSH_KEY" ]; then
  SSH_KEY_OPT="-i $SSH_KEY"
fi

# 기존 터널 정리
if [ -f "$TUNNEL_PID_FILE" ]; then
  OLD_PID=$(cat "$TUNNEL_PID_FILE")
  if kill -0 "$OLD_PID" 2>/dev/null; then
    echo "[*] Killing existing SSH tunnel (PID: $OLD_PID)..."
    kill "$OLD_PID"
    sleep 1
  fi
  rm -f "$TUNNEL_PID_FILE"
fi

# 포트 사용 중인지 확인
if lsof -i :$LOCAL_PORT -t >/dev/null 2>&1; then
  echo "[!] Port $LOCAL_PORT is already in use. Please free it first."
  exit 1
fi

# SSH 터널 시작
echo "[1/2] Opening SSH tunnel: localhost:$LOCAL_PORT -> $ELASTICACHE_HOST:$ELASTICACHE_PORT (via $EC2_HOST)..."
ssh -f -N -p 22222 -L $LOCAL_PORT:$ELASTICACHE_HOST:$ELASTICACHE_PORT \
  $SSH_KEY_OPT $EC2_USER@$EC2_HOST

# 터널 PID 저장
SSH_PID=$(lsof -i :$LOCAL_PORT -t 2>/dev/null | head -1)
echo "$SSH_PID" > "$TUNNEL_PID_FILE"
echo "    SSH tunnel PID: $SSH_PID"

# AWS 크레덴셜 확인
if [ -z "$AWS_ACCESS_KEY_ID" ] || [ -z "$AWS_SECRET_ACCESS_KEY" ]; then
  echo "[!] AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY are required for CloudWatch."
  echo "    export AWS_ACCESS_KEY_ID=<your-key>"
  echo "    export AWS_SECRET_ACCESS_KEY=<your-secret>"
  exit 1
fi

# docker compose 실행
echo "[2/2] Starting monitoring stack..."
cd "$(dirname "$0")"
REDIS_ADDR="rediss://host.docker.internal:$LOCAL_PORT" \
REDIS_SKIP_TLS_VERIFY=true \
AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
docker compose up -d

echo ""
echo "============================================================"
echo "  Monitoring stack is running!"
echo "  Grafana:    http://localhost:3000  (admin/admin)"
echo "  Prometheus: http://localhost:9090"
echo "  Redis Exp:  http://localhost:9121/metrics"
echo ""
echo "  Stop: ./local-stop.sh"
echo "============================================================"