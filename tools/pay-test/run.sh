#!/usr/bin/env bash
# 桌面自测 PayMock：真起一个 HTTP 服务端（含 /__pay/* 路由），由 client.mjs 走一遍协议。
#
#   ./run.sh
#
# 依赖：JDK 8+（沙箱用 21）、Node 18+。
# 端口用 417xx 段高位随机端口（避开 4190 WHATWG 禁用端口）。
set -euo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-41788}"
TOKEN="${TOKEN:-paytest01}"
OUT="$(mktemp -d)"

# 上一轮没退干净：只收本脚本起的那条命令
pkill -f "PayMockSelfTestServer ${PORT}" 2>/dev/null || true
sleep 0.2

javac -encoding UTF-8 -d "${OUT}" \
  ../../app/src/main/java/org/mozilla/geckoview_example/jsbridge/PayMock.java \
  ../../app/src/main/java/org/mozilla/geckoview_example/jsbridge/DataShareServer.java \
  PayMockSelfTestServer.java

java -cp "${OUT}" org.mozilla.geckoview_example.jsbridge.PayMockSelfTestServer "${PORT}" "${TOKEN}" \
  >/tmp/paymock-selftest.log 2>&1 &
SRV=$!
trap 'kill ${SRV} 2>/dev/null || true; rm -rf "${OUT}"' EXIT

for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  if grep -q '^READY' /tmp/paymock-selftest.log 2>/dev/null; then break; fi
  if ! kill -0 ${SRV} 2>/dev/null; then
    echo "服务端没起来："
    cat /tmp/paymock-selftest.log
    exit 1
  fi
  sleep 0.2
done

node client.mjs "http://127.0.0.1:${PORT}"
