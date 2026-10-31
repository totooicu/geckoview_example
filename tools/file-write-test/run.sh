#!/usr/bin/env bash
# 桌面验 file.* 公共目录的收口，三步：
#   0) 核桩签名——stubs/ 里的 JsBridge 必须和真实 JsBridge.java 一行不差，否则下面是假通过；
#   1) 用桩把真实的 FileBridge.java + PublicWriteScope.java 编译一遍（沙箱没有 Android SDK）；
#   2) 跑 PublicWriteScopeSelfTest（判定逻辑本身，31 项）；
#   3) 跑 FileBridgePublicBranchTest（拿真 FileBridge 走 API 26-28 分支，落盘都是真文件，24 项）。
#
#   ./run.sh
#
# 依赖：JDK（javac / java，本沙箱用 21）。不需要 Android SDK、不需要连手机。
# 为什么要有第 1、3 步：壳工程 CLAUDE.md「环境备注」写明沙箱跑不了 gradle，
# Java 改动的门禁退化成「javac 语法检查 + 通读 diff」。光靠人读 diff 迟早漏，
# 所以补一套最小桩，把 FileBridge 真的编一遍、再把 26-28 那条分支真的跑一遍。
# 桩只覆盖 java.io 表达得了的部分：MediaStore 那半边（29+ 建 content uri、"wa" 续写、
# 权限框）桌面验不到，留给真机——app/src/main/assets/test.html 里的
# 「分片续写（应读到 AAABBB）」与「越界位置续写（应被拒）」两个按钮。
#
# 反证（这套断言不是摆设）：把 writePublic 里那句 mPublicScope.resolveWrite 换成
# 「uri 非空就照它来」，H4a/H4b/H4c/H4e/H5a/H5b/H6a/H6b/H8d 九条当场变红，
# H4c 的详情里能看见定位日志被追加成了「ORIGINAL-LOCATION-LOG\nPWNED」。
set -euo pipefail
cd "$(dirname "$0")"

SRC=../../app/src/main/java/org/mozilla/geckoview_example/jsbridge
REAL_BRIDGE="$SRC/JsBridge.java"
OUT="$(mktemp -d)"
trap 'rm -rf "${OUT}"' EXIT

# ---------- 0. 桩必须与真实 JsBridge 的签名一致，否则第 1 步是假通过 ----------
if [ ! -f "$REAL_BRIDGE" ]; then
  echo "找不到 $REAL_BRIDGE，签名核对做不了" >&2
  exit 1
fi
for sig in \
  'public static final int PERM_FILE = 1002;' \
  'public void respond(String id, boolean ok, @Nullable JSONObject result, @Nullable String error) {' \
  'public interface PermissionCallback {' \
  'void onGranted();' \
  'default void onDenied() {}' \
  'Activity activity, int code, String[] permissions, PermissionCallback cb) {' \
  'public Activity activity() {'
do
  if ! grep -qF "$sig" "$REAL_BRIDGE"; then
    echo "桩与真实 JsBridge 已经飘了，缺这一行：$sig" >&2
    echo "先照 $REAL_BRIDGE 更新 stubs/org/mozilla/geckoview_example/jsbridge/JsBridge.java" >&2
    exit 1
  fi
done
echo "桩签名核对：与 $REAL_BRIDGE 一致 ✓"

# ---------- 1. 用桩类型检查 + 跑真实的 FileBridge（26-28 分支）----------
javac -encoding UTF-8 -Xlint:none -d "$OUT" \
  $(find stubs -name '*.java') \
  "$SRC/FileBridge.java" \
  "$SRC/PublicWriteScope.java" \
  PublicWriteScopeSelfTest.java \
  FileBridgePublicBranchTest.java
echo "javac：FileBridge.java + PublicWriteScope.java 编译通过 ✓"

# ---------- 2. 跑断言 ----------
java -cp "$OUT" org.mozilla.geckoview_example.jsbridge.PublicWriteScopeSelfTest
echo
java -cp "$OUT" org.mozilla.geckoview_example.jsbridge.FileBridgePublicBranchTest
