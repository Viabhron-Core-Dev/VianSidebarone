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
- Visibly bolder/thicker typography
- Alpha cleanup threshold 80

Uses pure Python standard library with libfreetype or Java headless fallback.
Does NOT invoke or require external ImageMagick 'convert' executable.
"""

import os
import sys
import time
import struct
import zlib
import ctypes
import ctypes.util
import subprocess

FONT_SPEED = "tools/fonts/RobotoCondensed-Bold.ttf"
FONT_UNIT = "tools/fonts/Roboto-Bold.ttf"
OUT_DIR = "app/src/main/res/drawable-xhdpi"
PROVIDER_FILE = "app/src/main/java/com/example/core/SpeedIconProvider.kt"

# PNG chunk helper
IHDR = struct.pack(">IIBBBBB", 96, 96, 8, 6, 0, 0, 0)

def make_chunk(tag, data):
    tag_b = tag.encode("ascii")
    crc = zlib.crc32(tag_b + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + tag_b + data + struct.pack(">I", crc)

def encode_png(raw_rows):
    compressed = zlib.compress(bytes(raw_rows), level=6)
    return b"\x89PNG\r\n\x1a\n" + make_chunk("IHDR", IHDR) + make_chunk("IDAT", compressed) + make_chunk("IEND", b"")

# --- FreeType C-Types Bindings ---
class FT_Generic(ctypes.Structure):
    _fields_ = [('data', ctypes.c_void_p), ('finalizer', ctypes.c_void_p)]

class FT_BBox(ctypes.Structure):
    _fields_ = [('xMin', ctypes.c_long), ('yMin', ctypes.c_long), ('xMax', ctypes.c_long), ('yMax', ctypes.c_long)]

class FT_Vector(ctypes.Structure):
    _fields_ = [('x', ctypes.c_long), ('y', ctypes.c_long)]

class FT_Bitmap(ctypes.Structure):
    _fields_ = [
        ('rows', ctypes.c_uint),
        ('width', ctypes.c_uint),
        ('pitch', ctypes.c_int),
        ('buffer', ctypes.POINTER(ctypes.c_ubyte)),
        ('num_grays', ctypes.c_ushort),
        ('pixel_mode', ctypes.c_ubyte),
        ('palette_mode', ctypes.c_ubyte),
        ('palette', ctypes.c_void_p),
    ]

class FT_Glyph_Metrics(ctypes.Structure):
    _fields_ = [
        ('width', ctypes.c_long),
        ('height', ctypes.c_long),
        ('horiBearingX', ctypes.c_long),
        ('horiBearingY', ctypes.c_long),
        ('horiAdvance', ctypes.c_long),
        ('vertBearingX', ctypes.c_long),
        ('vertBearingY', ctypes.c_long),
        ('vertAdvance', ctypes.c_long),
    ]

class FT_GlyphSlotRec(ctypes.Structure):
    _fields_ = [
        ('library', ctypes.c_void_p),
        ('face', ctypes.c_void_p),
        ('next', ctypes.c_void_p),
        ('glyph_index', ctypes.c_uint),
        ('generic', FT_Generic),
        ('metrics', FT_Glyph_Metrics),
        ('linearHoriAdvance', ctypes.c_long),
        ('linearVertAdvance', ctypes.c_long),
        ('advance', FT_Vector),
        ('format', ctypes.c_uint),
        ('bitmap', FT_Bitmap),
        ('bitmap_left', ctypes.c_int),
        ('bitmap_top', ctypes.c_int),
    ]

class FT_FaceRec(ctypes.Structure):
    pass

FT_FaceRec._fields_ = [
    ('num_faces', ctypes.c_long),
    ('face_index', ctypes.c_long),
    ('face_flags', ctypes.c_long),
    ('style_flags', ctypes.c_long),
    ('num_glyphs', ctypes.c_long),
    ('family_name', ctypes.c_char_p),
    ('style_name', ctypes.c_char_p),
    ('num_fixed_sizes', ctypes.c_int),
    ('available_sizes', ctypes.c_void_p),
    ('num_charmaps', ctypes.c_int),
    ('charmaps', ctypes.c_void_p),
    ('generic', FT_Generic),
    ('bbox', FT_BBox),
    ('units_per_EM', ctypes.c_ushort),
    ('ascender', ctypes.c_short),
    ('descender', ctypes.c_short),
    ('height', ctypes.c_short),
    ('max_advance_width', ctypes.c_short),
    ('max_advance_height', ctypes.c_short),
    ('underline_position', ctypes.c_short),
    ('underline_thickness', ctypes.c_short),
    ('glyph', ctypes.POINTER(FT_GlyphSlotRec)),
]

def load_freetype_lib():
    candidates = [
        ctypes.util.find_library("freetype"),
        "libfreetype.so.6",
        "libfreetype.so",
        "/usr/lib/x86_64-linux-gnu/libfreetype.so.6",
        "/usr/lib/aarch64-linux-gnu/libfreetype.so.6",
        "/usr/lib/libfreetype.so.6",
    ]
    for cand in candidates:
        if cand:
            try:
                return ctypes.CDLL(cand)
            except Exception:
                pass
    return None

class FreeTypeIconRenderer:
    def __init__(self):
        self.ft = load_freetype_lib()
        if not self.ft:
            raise RuntimeError("FreeType library not found on system.")

        self.lib = ctypes.c_void_p()
        err = self.ft.FT_Init_FreeType(ctypes.byref(self.lib))
        if err != 0:
            raise RuntimeError(f"FT_Init_FreeType failed: {err}")

        self.face_speed = ctypes.POINTER(FT_FaceRec)()
        self.face_unit = ctypes.POINTER(FT_FaceRec)()

        err1 = self.ft.FT_New_Face(self.lib, FONT_SPEED.encode("utf-8"), 0, ctypes.byref(self.face_speed))
        err2 = self.ft.FT_New_Face(self.lib, FONT_UNIT.encode("utf-8"), 0, ctypes.byref(self.face_unit))
        if err1 != 0 or err2 != 0:
            raise RuntimeError(f"FT_New_Face failed: err1={err1}, err2={err2}")

        self.glyph_cache = {}
        self._init_glyph_cache()
        self.unit_kb_layer = self._pre_render_unit("kB/s")
        self.unit_mb_layer = self._pre_render_unit("MB/s")

    def _get_emboldened_glyph(self, face, ch, sz, stroke_radius=0.5):
        self.ft.FT_Set_Pixel_Sizes(face, 0, sz)
        self.ft.FT_Load_Char(face, ord(ch), 4) # FT_LOAD_RENDER
        slot = face.contents.glyph.contents
        adv = slot.advance.x >> 6
        w = slot.bitmap.width
        h = slot.bitmap.rows
        left = slot.bitmap_left
        top = slot.bitmap_top
        pitch = slot.bitmap.pitch
        buf = bytes([slot.bitmap.buffer[i] for i in range(h * pitch)])

        ew = w + 2
        eh = h + 2
        eleft = left - 1
        etop = top + 1
        ebuf = bytearray(ew * eh)

        offsets = [(1, 1, 1.0)]
        if stroke_radius >= 0.5:
            offsets += [(0, 1, 0.7), (2, 1, 0.7), (1, 0, 0.7), (1, 2, 0.7)]
        if stroke_radius >= 0.8:
            offsets += [(0, 0, 0.5), (2, 0, 0.5), (0, 2, 0.5), (2, 2, 0.5)]

        for ox, oy, weight in offsets:
            for r in range(h):
                row_ebuf = (r + oy) * ew
                row_buf = r * pitch
                for c in range(w):
                    val = int(buf[row_buf + c] * weight)
                    pos = row_ebuf + (c + ox)
                    if val > ebuf[pos]:
                        ebuf[pos] = val

        return adv, ew, eh, eleft, etop, bytes(ebuf)

    def _init_glyph_cache(self):
        for sz in [68, 59, 50]:
            for ch in "0123456789.":
                radius = 0.8 if sz >= 59 else 0.6
                self.glyph_cache[(sz, ch)] = self._get_emboldened_glyph(self.face_speed, ch, sz, radius)

    def _pre_render_unit(self, text):
        self.ft.FT_Set_Pixel_Sizes(self.face_unit, 0, 36)
        glyphs = []
        total_adv = 0
        for char in text:
            adv, ew, eh, eleft, etop, ebuf = self._get_emboldened_glyph(self.face_unit, char, 36, 0.5)
            glyphs.append((adv, ew, eh, eleft, etop, ebuf))
            total_adv += adv

        pen_x = 48.0 - total_adv / 2.0
        canvas = bytearray(96 * 96)
        for adv, ew, eh, eleft, etop, ebuf in glyphs:
            gx = int(round(pen_x)) + eleft
            gy = 95 - etop
            for r in range(eh):
                y = gy + r
                if 0 <= y < 96:
                    row_y = y * 96
                    row_ebuf = r * ew
                    for c in range(ew):
                        x = gx + c
                        if 0 <= x < 96:
                            val = ebuf[row_ebuf + c]
                            if val > canvas[row_y + x]:
                                canvas[row_y + x] = val
            pen_x += adv
        return bytes(canvas)

    def render(self, val_str, unit_str):
        is_kb = (unit_str == "kB/s")
        canvas = bytearray(self.unit_kb_layer if is_kb else self.unit_mb_layer)

        if is_kb:
            sz = 68 if int(val_str) < 100 else 59
        else:
            sz = 68 if len(val_str) <= 3 else 50

        total_adv = sum(self.glyph_cache[(sz, ch)][0] for ch in val_str)
        pen_x = 48.0 - total_adv / 2.0

        for ch in val_str:
            adv, ew, eh, eleft, etop, ebuf = self.glyph_cache[(sz, ch)]
            gx = int(round(pen_x)) + eleft
            gy = 52 - etop
            for r in range(eh):
                y = gy + r
                if 0 <= y < 96:
                    row_y = y * 96
                    row_ebuf = r * ew
                    for c in range(ew):
                        x = gx + c
                        if 0 <= x < 96:
                            val = ebuf[row_ebuf + c]
                            if val > canvas[row_y + x]:
                                canvas[row_y + x] = val
            pen_x += adv

        raw_rows = bytearray(96 * (96 * 4 + 1))
        for y in range(96):
            dest_idx = y * (96 * 4 + 1) + 1
            src_idx = y * 96
            for x in range(96):
                a = canvas[src_idx + x]
                if a >= 80:
                    raw_rows[dest_idx : dest_idx + 4] = b"\xff\xff\xff" + bytes([a])
                dest_idx += 4

        return encode_png(raw_rows)

def render_items_java(items, out_dir):
    """Headless Java AWT fallback renderer if libfreetype is unavailable."""
    java_file = "/tmp/SpeedIconAwtRenderer.java"
    java_code = """
import java.io.*;
import java.awt.*;
import java.awt.image.*;
import javax.imageio.ImageIO;

public class SpeedIconAwtRenderer {
    public static void main(String[] args) throws Exception {
        File outDir = new File(args[0]);
        Font fontSpeed = Font.createFont(Font.TRUETYPE_FONT, new File("tools/fonts/RobotoCondensed-Bold.ttf"));
        Font fontUnit = Font.createFont(Font.TRUETYPE_FONT, new File("tools/fonts/Roboto-Bold.ttf"));
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 3) continue;
            String val = parts[0];
            String unit = parts[1];
            String filename = parts[2];

            BufferedImage img = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            boolean isKb = unit.equals("kB/s");
            float szSpeed;
            if (isKb) {
                szSpeed = Integer.parseInt(val) < 100 ? 68f : 59f;
            } else {
                szSpeed = val.length() <= 3 ? 68f : 50f;
            }

            g.setFont(fontSpeed.deriveFont(Font.BOLD, szSpeed));
            FontMetrics fmSpeed = g.getFontMetrics();
            int sw = fmSpeed.stringWidth(val);
            int sx = 48 - sw / 2;
            int sy = 52;

            g.setColor(Color.WHITE);
            // Thicker typography offsets
            g.drawString(val, sx - 1, sy);
            g.drawString(val, sx + 1, sy);
            g.drawString(val, sx, sy - 1);
            g.drawString(val, sx, sy + 1);
            g.drawString(val, sx, sy);

            g.setFont(fontUnit.deriveFont(Font.BOLD, 36f));
            FontMetrics fmUnit = g.getFontMetrics();
            int uw = fmUnit.stringWidth(unit);
            int ux = 48 - uw / 2;
            int uy = 95;
            g.drawString(unit, ux - 1, uy);
            g.drawString(unit, ux + 1, uy);
            g.drawString(unit, ux, uy);
            g.dispose();

            for (int y = 0; y < 96; y++) {
                for (int x = 0; x < 96; x++) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    if (a < 80) {
                        img.setRGB(x, y, 0);
                    } else {
                        img.setRGB(x, y, (a << 24) | 0x00FFFFFF);
                    }
                }
            }
            ImageIO.write(img, "png", new File(outDir, filename + ".png"));
        }
    }
}
"""
    with open(java_file, "w") as f:
        f.write(java_code)
    
    input_data = "\n".join(f"{item[0]},{item[1]},{item[2]}" for item in items) + "\n"
    proc = subprocess.Popen(["java", "-Djava.awt.headless=true", java_file, out_dir], stdin=subprocess.PIPE)
    proc.communicate(input=input_data.encode("utf-8"))
    if proc.returncode != 0:
        raise RuntimeError(f"Java icon renderer failed with return code {proc.returncode}")

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
    force = "--force" in sys.argv

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

    items_to_render = []
    for item in all_items:
        _, _, filename = item
        out_path = os.path.join(OUT_DIR, f"{filename}.png")
        if force or not os.path.exists(out_path) or os.path.getsize(out_path) < 100:
            items_to_render.append(item)

    if items_to_render:
        print(f"Generating {len(items_to_render)} missing/requested pre-rendered icons out of {total}...")
        t0 = time.time()
        try:
            renderer = FreeTypeIconRenderer()
            for idx, item in enumerate(items_to_render, 1):
                val_str, unit_str, filename = item
                png_bytes = renderer.render(val_str, unit_str)
                with open(os.path.join(OUT_DIR, f"{filename}.png"), "wb") as f:
                    f.write(png_bytes)
                if idx % 200 == 0 or idx == len(items_to_render):
                    print(f"  Progress: {idx}/{len(items_to_render)} icons generated ({time.time() - t0:.1f}s)")
        except Exception as e:
            print(f"FreeType direct rendering unavailable ({e}), falling back to headless Java renderer...")
            render_items_java(items_to_render, OUT_DIR)
        t1 = time.time()
        print(f"Rendered {len(items_to_render)} icons in {t1 - t0:.2f}s.")
    else:
        print(f"All {total} pre-rendered icons already exist in {OUT_DIR}.")

    if force or not os.path.exists(PROVIDER_FILE) or os.path.getsize(PROVIDER_FILE) < 1000:
        generate_provider_kt(kb_items, mb_items)
    else:
        print(f"{PROVIDER_FILE} is up to date.")

    # Validation check to guarantee every single resource exists on disk
    missing = [item[2] for item in all_items if not os.path.exists(os.path.join(OUT_DIR, f"{item[2]}.png"))]
    if missing:
        raise RuntimeError(f"FATAL: {len(missing)} icons missing after generation: {missing[:10]}")

    print(f"Speed icon verification successful: {total}/{total} icons present.")

if __name__ == "__main__":
    main()
