"""为 Android 生成各密度启动图标（纯标准库，不装 Pillow）。

复用 PWA 图标生成思路：手写 PNG（zlib + struct）+ 超采样抗锯齿。
画一个品牌蓝圆底的钟表。
"""
from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path

BASE = Path(r"D:\信任闹钟\android\app\src\main\res")
BRAND = (43, 108, 176, 255)     # 品牌蓝
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)


def render(size: int, ss: int = 3) -> bytes:
    """渲染 size×size 的 PNG 字节。ss 为超采样倍数。"""
    S = size * ss
    px = [[CLEAR] * S for _ in range(S)]
    cx = cy = S / 2.0
    r_out = S * 0.47

    # 圆底 + 白色钟面
    for y in range(S):
        for x in range(S):
            d = math.hypot(x - cx + 0.5, y - cy + 0.5)
            if d > r_out:
                continue
            px[y][x] = BRAND if d > r_out * 0.87 else WHITE

    # 指针：分针向上、时针向左
    ix, iy = int(cx), int(cy)
    for i in range(int(S * 0.21)):
        yy = iy - i
        if 0 <= yy < S:
            px[yy][ix] = BRAND
            if ix + 1 < S:
                px[yy][ix + 1] = BRAND
    for i in range(int(S * 0.15)):
        xx = ix - i
        if 0 <= xx < S:
            px[iy][xx] = BRAND
            if iy + 1 < S:
                px[iy + 1][xx] = BRAND

    # 中心圆点
    for dy in range(-int(S * 0.035), int(S * 0.035) + 1):
        for dx in range(-int(S * 0.035), int(S * 0.035) + 1):
            if dx * dx + dy * dy <= (S * 0.035) ** 2:
                yy, xx = iy + dy, ix + dx
                if 0 <= yy < S and 0 <= xx < S:
                    px[yy][xx] = BRAND

    # 降采样（盒式滤波）
    raw = bytearray()
    for y in range(size):
        raw.append(0)  # PNG 行过滤器：None
        for x in range(size):
            r = g = b = a = 0
            for j in range(ss):
                for i in range(ss):
                    p = px[y * ss + j][x * ss + i]
                    r += p[0]
                    g += p[1]
                    b += p[2]
                    a += p[3]
            k = ss * ss
            raw += bytes((r // k, g // k, b // k, a // k))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def main() -> int:
    targets = [
        ("mipmap-mdpi", 48),
        ("mipmap-hdpi", 72),
        ("mipmap-xhdpi", 96),
        ("mipmap-xxhdpi", 144),
        ("mipmap-xxxhdpi", 192),
    ]
    for folder, size in targets:
        out = BASE / folder / "ic_launcher.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(render(size))
        # 圆角图标同一份（Android 会自行裁切）
        (BASE / folder / "ic_launcher_round.png").write_bytes(render(size))
        print(f"  {folder}/ic_launcher.png  {size}px  {out.stat().st_size} 字节")
    print("完成")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
