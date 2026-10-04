#!/usr/bin/env bash
# 跑 Android 纯 JVM 单元测试（test/ 目录，秒级，不需要模拟器）。
#
# ## 为什么要有这个包装脚本（★ 中文路径坑）
#
# 项目真实路径是 D:\信任闹钟（含中文）。AGP 8.5 的 testDebugUnitTest 任务
# 会把 project 路径原样放进 JVM classpath；在中文路径下，JVM 收到的类路径
# 编码错乱 → 报 `ClassNotFoundException: ...Test`（测试类其实已编译成功）。
# 现象：`initializationError / ClassNotFoundException`，且手动用 java -cp 跑
# 却完全正常 —— 极易误判成测试代码写错。
#
# 绕法：把源码（不含 build 缓存）镜像到一个纯 ASCII 临时目录再跑，跑完清理。
# 已验证：ASCII 路径下 BUILD SUCCESSFUL，12/12 通过。
#
# 用法：
#   bash scripts/run_unit_tests.sh          # 跑全部
#   bash scripts/run_unit_tests.sh --keep   # 保留镜像目录（排查用）
set -euo pipefail

SRC_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # ...\android
MIRROR="${TA_TEST_MIRROR:-/d/ta_ascii_test}"

export JAVA_HOME="${JAVA_HOME:-/d/tools/android/jdk-17.0.20.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-/d/tools/android/sdk}"
GRADLE="${GRADLE:-/d/tools/android/gradle/gradle-8.7/bin/gradle}"
export MSYS_NO_PATHCONV=1

keep=0
[[ "${1:-}" == "--keep" ]] && keep=1

echo "==> 镜像源码到 ASCII 路径: $MIRROR"
rm -rf "$MIRROR"
mkdir -p "$MIRROR"
( cd "$SRC_ROOT" && tar cf - --exclude=build --exclude=.gradle . ) | ( cd "$MIRROR" && tar xf - )

echo "==> 运行单元测试 (testDebugUnitTest)"
set +e
( cd "$MIRROR" && "$GRADLE" testDebugUnitTest --console=plain )
rc=$?
set -e

echo "==> 结果"
find "$MIRROR/app/build/test-results" -name "*.xml" 2>/dev/null | while read -r f; do
  wf="$(cygpath -w "$f" 2>/dev/null || echo "$f")"
  python3 - "$wf" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', t)
n = re.search(r'testsuite name="([^"]+)"', t)
if m and n:
    print(f"  {n.group(1):48s} tests={m.group(1)} skip={m.group(2)} fail={m.group(3)} err={m.group(4)}")
PY
done

# ★ 落盘证据：临时目录会被清理，把 junit xml 拷进项目 docs/ 留档
#   （默认就做——否则"跑过了"无凭据，事后无法复查）
EVIDENCE="$SRC_ROOT/../docs/evidence_unit_tests"
mkdir -p "$EVIDENCE" 2>/dev/null && \
  cp "$MIRROR"/app/build/test-results/testDebugUnitTest/*.xml "$EVIDENCE"/ 2>/dev/null && \
  echo "==> 证据已落盘: $EVIDENCE"

if [[ $keep -eq 0 ]]; then rm -rf "$MIRROR"; fi
exit $rc
