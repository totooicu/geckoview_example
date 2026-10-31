#!/usr/bin/env bash
# 桌面自测 DataShareServer.java：真起一个 HTTP + WebSocket 服务端，按网页协议走一遍。
#
#   ./run.sh
#
# 依赖：JDK 8+（沙箱用 21）、Node 18+（WebSocket 是 Node 22 内置）。
set -euo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-41799}"
TOKEN="${TOKEN:-selftest01}"
OUT="$(mktemp -d)"

# 上一轮没退干净：只收本脚本起的那条命令
pkill -f "DataShareSelfTestServer ${PORT}" 2>/dev/null || true
sleep 0.2

javac -encoding UTF-8 -d "${OUT}" \
  ../../app/src/main/java/org/mozilla/geckoview_example/jsbridge/DataShareServer.java \
  DataShareSelfTestServer.java

java -cp "${OUT}" org.mozilla.geckoview_example.jsbridge.DataShareSelfTestServer "${PORT}" "${TOKEN}" \
  >/tmp/datashare-selftest.log 2>&1 &
SRV=$!
trap 'kill ${SRV} 2>/dev/null || true; rm -rf "${OUT}"' EXIT

for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  if grep -q '^READY' /tmp/datashare-selftest.log 2>/dev/null; then break; fi
  if ! kill -0 ${SRV} 2>/dev/null; then
    echo "服务端没起来："; cat /tmp/datashare-selftest.log; exit 1
  fi
  sleep 0.2
done

node client.mjs "http://127.0.0.1:${PORT}" "ws://127.0.0.1:${PORT}/remote?token=${TOKEN}" "${TOKEN}"
