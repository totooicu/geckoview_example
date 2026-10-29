#!/usr/bin/env bash
# 桌面自测 NetHttp.java：起假坚果云 → 编译 → 跑 25 项断言。
#
#   ./run.sh            只打本地假服务端
#   ./run.sh --live     再向真实 dav.jianguoyun.com 发一次无凭据 PROPFIND（要外网）
#
# 依赖：JDK（javac/java 8 语法以上即可，本沙箱用 21）、Node 18+。
# 每次运行都重启假服务端：分片存在它的内存里，留着旧状态会让「MKCOL 新建 → 201」假失败。
set -euo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-8078}"
BASE="http://127.0.0.1:${PORT}"
OUT="$(mktemp -d)"

# 端口被上一轮的假服务端占着就先收掉——只认这个脚本起的那一条命令
if (exec 3<>"/dev/tcp/127.0.0.1/${PORT}") 2>/dev/null; then
  exec 3<&- 3>&- || true
  echo "端口 ${PORT} 上有旧的假坚果云，先关掉再起新的"
  pkill -f "node .*net-http-test/server.mjs ${PORT}" 2>/dev/null || true
  sleep 0.5
fi

node server.mjs "${PORT}" >/tmp/net-http-fake.log 2>&1 &
SRV=$!
trap 'kill ${SRV} 2>/dev/null || true; rm -rf "${OUT}"' EXIT

for _ in 1 2 3 4 5 6 7 8 9 10; do
  if (exec 3<>"/dev/tcp/127.0.0.1/${PORT}") 2>/dev/null; then exec 3<&- 3>&- || true; break; fi
  sleep 0.3
done

javac -encoding UTF-8 -d "${OUT}" \
  ../../app/src/main/java/org/mozilla/geckoview_example/jsbridge/NetHttp.java \
  NetHttpSelfTest.java

java -cp "${OUT}" org.mozilla.geckoview_example.jsbridge.NetHttpSelfTest --base="${BASE}" "$@"
