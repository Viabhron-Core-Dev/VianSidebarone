#!/usr/bin/env python3
"""
Deterministic Build-Time Speed Icon Asset Generator
Generates:
1. 1000 pre-rendered 96x96 status-bar icons for kB/s (0..999) -> app/src/main/res/drawable-xhdpi/ic_stat_speed_<val>_k.png
2. 421 pre-rendered 96x96 status-bar icons for MB/s (1.0..43.0 in 0.1 increments) -> app/src/main/res/drawable-xhdpi/ic_stat_speed_<d>_<f>_m.png
3. Generates com.example.core.SpeedIconProvider.kt for O(1) resource ID and name lookup.

Target Device: Redmi A5, 320 dpi (xhdpi), density 2.0.
Artwork specs:
- 96x96 px
- Speed baseline: X=48, Y=52
- Unit baseline: X=48, Y=95
- White glyphs on transparent background
- Visibly bolder/thicker stroke
- Alpha cleanup threshold 80
"""

import os
import sys
import time
import struct
import zlib
import subprocess
from concurrent.futures import ProcessPoolExecutor

FONT_SPEED = "tools/fonts/RobotoCondensed-Bold.ttf"
FONT_UNIT = "tools/fonts/Roboto-Bold.ttf"
OUT_DIR = "app/src/main/res/drawable-xhdpi"
PROVIDER_FILE = "app/src/main/java/com/example/core/SpeedIconProvider.kt"

def get_glyph_params(val_str, unit_str):
    if unit_str == "kB/s":
        # Integer values 0..999
        val_int = int(val_str)
        if val_int < 10:
            return 68.0, 1.0
        elif val_int < 100:
            return 68.0, 1.0
        else:
            return 58.67, 0.86
    else:
        # MB/s values 1.0..43.0
        if len(val_str) <= 3: # e.g. 1.0 .. 9.9
            return 68.0, 1.0
        else: # e.g. 10.0 .. 43.0
            return 49.87, 0.73

def render_icon(item):
    val_str, unit_str, filename = item
    pointsize, strokewidth = get_glyph_params(val_str, unit_str)
    out_path = os.path.join(OUT_DIR, f"{filename}.png")

    cmd = [
        "convert", "-size", "96x96", "xc:none",
        "-fill", "white",
        "-font", FONT_SPEED, "-pointsize", str(pointsize),
        "-stroke", "white", "-strokewidth", str(strokewidth),
        "-draw", f'text-anchor middle text 48,52 "{val_str}"',
        "-font", FONT_UNIT, "-pointsize", "36",
        "-stroke", "white", "-strokewidth", "0.8",
        "-draw", f'text-anchor middle text 48,95 "{unit_str}"',
        "-depth", "8", "rgba:-"
    ]

    raw = bytearray(subprocess.check_output(cmd))

    # Apply alpha cleanup threshold 80
    for i in range(0, len(raw), 4):
        if raw[i + 3] < 80:
            raw[i] = 0
            raw[i + 1] = 0
            raw[i + 2] = 0
            raw[i + 3] = 0

    raw_rows = bytearray()
    for y in range(96):
        raw_rows.append(0)
        raw_rows.extend(raw[y * 96 * 4 : (y + 1) * 96 * 4])

    compressed = zlib.compress(bytes(raw_rows), level=6)

    def chunk(tag, data):
        tag_b = tag.encode("ascii")
        crc = zlib.crc32(tag_b + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag_b + data + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", 96, 96, 8, 6, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n" + chunk("IHDR", ihdr) + chunk("IDAT", compressed) + chunk("IEND", b"")

    with open(out_path, "wb") as f:
        f.write(png)

    return filename

def generate_provider_kt(kb_items, mb_items):
    lines = [
        "package com.example.core",
        "",
        "import com.example.R",
        "import kotlin.math.roundToInt",
        "",
        "/**",
        " * Lightweight O(1) lookup table for pre-rendered 96x96 status-bar speed icons.",
        " * Zero runtime Canvas/Paint/Bitmap overhead.",
        " */",
        "object SpeedIconProvider {",
        "",
        "    data class IconInfo(val resId: Int, val resName: String)",
        "",
        "    private val kbIcons = intArrayOf("
    ]

    for val_str, _, filename in kb_items:
        lines.append(f"        R.drawable.{filename}, // {val_str} kB/s")

    lines.append("    )")
    lines.append("")
    lines.append("    private val mbIcons = intArrayOf(")

    for val_str, _, filename in mb_items:
        lines.append(f"        R.drawable.{filename}, // {val_str} MB/s")

    lines.append("    )")
    lines.append("")
    lines.append("""    /**
     * Resolves the pre-rendered icon resource ID and name for a given speed value and unit.
     */
    fun resolve(speedValue: String, speedUnit: String): IconInfo {
        val isMb = speedUnit.contains("MB", ignoreCase = true) || speedUnit.contains("M", ignoreCase = true)
        return if (isMb) {
            val dbl = speedValue.toDoubleOrNull() ?: 1.0
            val tenths = (dbl * 10.0).roundToInt().coerceIn(10, 430)
            val index = tenths - 10
            val resId = mbIcons[index]
            val d = tenths / 10
            val f = tenths % 10
            IconInfo(resId, "ic_stat_speed_${d}_${f}_m")
        } else {
            val kbVal = (speedValue.toIntOrNull() ?: 0).coerceIn(0, 999)
            val resId = kbIcons[kbVal]
            IconInfo(resId, "ic_stat_speed_${kbVal}_k")
        }
    }
}
""")

    os.makedirs(os.path.dirname(PROVIDER_FILE), exist_ok=True)
    with open(PROVIDER_FILE, "w") as f:
        f.write("\n".join(lines))
    print(f"Generated {PROVIDER_FILE}")

def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    kb_items = []
    for i in range(1000): # 0..999
        kb_items.append((str(i), "kB/s", f"ic_stat_speed_{i}_k"))

    mb_items = []
    for tenths in range(10, 431): # 10..430 => 1.0..43.0
        d = tenths // 10
        f = tenths % 10
        val_str = f"{d}.{f}"
        mb_items.append((val_str, "MB/s", f"ic_stat_speed_{d}_{f}_m"))

    all_items = kb_items + mb_items
    total = len(all_items)
    print(f"Generating {total} pre-rendered icons ({len(kb_items)} kB/s + {len(mb_items)} MB/s)...")

    t0 = time.time()
    with ProcessPoolExecutor() as executor:
        for idx, _ in enumerate(executor.map(render_icon, all_items), 1):
            if idx % 200 == 0 or idx == total:
                print(f"  Progress: {idx}/{total} icons generated ({time.time() - t0:.1f}s)")
    t1 = time.time()
    print(f"Generated {total} icons in {t1 - t0:.2f}s.")

    generate_provider_kt(kb_items, mb_items)
    print("Asset generation complete.")

if __name__ == "__main__":
    main()
