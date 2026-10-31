#!/usr/bin/env bash
# 桌面自测 net.*，两步：
#   0) 用 ../file-write-test/stubs 的桩把 NetBridge.java 编一遍（Android 那一半的语法与签名检查）；
#   1) 起假坚果云 → 编译纯 java 的 NetHttp.java → 跑 24 项断言。
#
#   ./run.sh            只打本地假服务端
#   ./run.sh --live     再向真实 dav.jianguoyun.com 发一次无凭据 PROPFIND（要外网）
#
# 依赖：JDK（javac/java 8 语法以上即可，本沙箱用 21）、Node 18+。
# 每次运行都重启假服务端：分片存在它的内存里，留着旧状态会让「MKCOL 新建 → 201」假失败。
# 第 0 步为什么要有：NetBridge 是碰 android.util.Base64 / Log / JsBridge 的那一层，
# 沙箱没有 Android SDK，gradle 门禁退化成 javac；不编这一遍，桥接层的拼写错误
# 要等真机打包才暴露。桩与真实代码飘了会在这里报错（不是静默跳过）。
# 注意第 0 步只**编**不**跑**：net.capabilities 的 methods 表是用 JSON 字符串构造的
# （new JSONObject("{...}")），而 stubs/ 里的 JSONObject 明确不解析文本、直接抛异常。
# 想在桌面上真的驱动 NetBridge，得先把那张表改成逐条 put，再照 file-write-test 的路子加断言。
set -euo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-8078}"
BASE="http://127.0.0.1:${PORT}"
OUT="$(mktemp -d)"
SRC=../../app/src/main/java/org/mozilla/geckoview_example/jsbridge

# ---------- 0. 桥接层（Android 那一半）先用桩编一遍 ----------
javac -encoding UTF-8 -Xlint:none -d "${OUT}/bridge" \
  $(find ../file-write-test/stubs -name '*.java') \
  "${SRC}/NetBridge.java" "${SRC}/NetHttp.java"
echo "javac：NetBridge.java（对着 file-write-test 的桩）编译通过 ✓"

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
