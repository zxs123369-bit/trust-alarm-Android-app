"""把网页版（web/）同步进 Android App 的 assets，供 WebView 加载。

## 为什么需要这个脚本

手机端要让「界面与网页版一致」，最省事且永不漂移的做法是**直接复用网页版**，
而不是用 Kotlin 重画一遍。但网页版是给「网站」写的，有两处不兼容 WebView：

1. **绝对路径**：`index.html` 里写的是 `/app/css/base.css`、`/app/js/main.js`。
   网页里 `/app/` 由后端的 StaticFiles 挂载提供；WebView 是 `file://`
   或虚拟域名，`/app/` 会 404。→ 本脚本改写为相对路径 `./`。

2. **Service Worker 缓存**：PWA 靠 SW 离线缓存。WebView 里若注册 SW，
   会拿到旧缓存导致「改了网页但 App 没变」，且 `file://` 下 SW 本就不可用。
   → 本脚本排除 `sw.js`，并剥离 `main.js` 里的注册代码。

## 用法

    python android/sync_web.py            # 同步
    python android/sync_web.py --check    # 只校验，不写

跑完会打印复制了多少文件、改写了哪些地方。
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SRC = PROJECT_ROOT / "web"
DST = PROJECT_ROOT / "android" / "app" / "src" / "main" / "assets" / "web"

# WebView 下不需要的文件（SW 与 manifest 都是「装成 PWA」用的）
EXCLUDE_FILES = {"sw.js", "manifest.webmanifest"}

# index.html 里 /app/ 前缀 → 相对路径
ABS_PREFIX = re.compile(r'(["\'(])/app/')


def rewrite_index(html: str) -> tuple[str, int]:
    """把 /app/xxx 改成 ./xxx，返回 (新内容, 改写处数)。"""
    new, n = ABS_PREFIX.subn(r"\1./", html)
    return new, n


def strip_sw_registration(js: str) -> tuple[str, bool]:
    """剥离 main.js 里的 serviceWorker 注册块（WebView 不需要且有害）。

    定位方式：找到 `if ('serviceWorker' in navigator) {` 起始，
    然后做括号配对找到该块的结尾，整块用空实现替换。
    """
    marker = "if ('serviceWorker' in navigator)"
    idx = js.find(marker)
    if idx == -1:
        return js, False

    # 从 marker 后的第一个 '{' 开始做括号配对
    brace_start = js.find("{", idx)
    if brace_start == -1:
        return js, False

    depth = 0
    i = brace_start
    while i < len(js):
        c = js[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                break
        i += 1
    if depth != 0:
        return js, False

    block = js[idx : i + 1]
    replacement = (
        "/* [Hermes] WebView 环境下不注册 Service Worker：\n"
        "   file:// 协议下 SW 不可用，且缓存会导致「网页改了 App 没变」。\n"
        "   同步脚本 android/sync_web.py 会自动剥离本块。 */"
    )
    return js[:idx] + replacement + js[i + 1 :], True


def main() -> int:
    ap = argparse.ArgumentParser(description="同步网页版到 Android assets")
    ap.add_argument("--check", action="store_true", help="只校验不写入")
    args = ap.parse_args()

    if not SRC.is_dir():
        print(f"[FAIL] 源目录不存在: {SRC}")
        return 1

    print("=" * 60)
    print("  同步网页版 → Android assets")
    print("=" * 60)
    print(f"  源  {SRC}")
    print(f"  目标 {DST}")

    if args.check:
        # 只检查是否已同步且无残留绝对路径
        if not DST.is_dir():
            print("[FAIL] assets 尚未同步，请去掉 --check 先跑一次")
            return 1
        bad = []
        for p in DST.rglob("*"):
            if p.is_file() and p.suffix in {".html", ".js", ".json", ".webmanifest"}:
                txt = p.read_text(encoding="utf-8", errors="ignore")
                if re.search(r'(["\'(])/app/', txt):
                    bad.append(p.relative_to(DST))
        if bad:
            print(f"[FAIL] 仍有绝对路径: {bad}")
            return 1
        print("[OK] 已同步，且无 /app/ 绝对路径残留")
        return 0

    # 清空目标（避免删掉的网页文件残留在 assets 里）
    if DST.exists():
        shutil.rmtree(DST)
    DST.mkdir(parents=True)

    copied = 0
    skipped: list[str] = []
    rewrites: list[str] = []

    for src_file in SRC.rglob("*"):
        if not src_file.is_file():
            continue
        rel = src_file.relative_to(SRC)
        if src_file.name in EXCLUDE_FILES:
            skipped.append(str(rel))
            continue

        dst_file = DST / rel
        dst_file.parent.mkdir(parents=True, exist_ok=True)

        if src_file.suffix == ".html":
            html, n = rewrite_index(src_file.read_text(encoding="utf-8"))
            dst_file.write_text(html, encoding="utf-8")
            if n:
                rewrites.append(f"{rel}: {n} 处 /app/ → ./")
        elif src_file.name == "main.js":
            js, ok = strip_sw_registration(src_file.read_text(encoding="utf-8"))
            dst_file.write_text(js, encoding="utf-8")
            if ok:
                rewrites.append(f"{rel}: 已剥离 Service Worker 注册")
        else:
            shutil.copy2(src_file, dst_file)

        copied += 1

    print(f"\n  已复制 {copied} 个文件")
    if skipped:
        print(f"  已排除 {len(skipped)} 个（WebView 不需要）: {', '.join(skipped)}")
    if rewrites:
        print("  已改写:")
        for r in rewrites:
            print(f"    - {r}")

    # 自检：确认没有 /app/ 残留
    leftovers = []
    for p in DST.rglob("*"):
        if p.is_file() and p.suffix in {".html", ".js", ".json"}:
            txt = p.read_text(encoding="utf-8", errors="ignore")
            if re.search(r'(["\'(])/app/', txt):
                leftovers.append(str(p.relative_to(DST)))
    if leftovers:
        print(f"\n  [WARN] 仍有绝对路径残留: {leftovers}")
        return 1

    print("\n  [OK] 同步完成，无 /app/ 绝对路径残留")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(main())
