"""Prints a line of text as an SVG <path>, for feature-graphic.svg (#368).

The feature graphic's words are letter outlines, not <text>, so the PNG looks the same wherever
it's rendered and no font file has to sit in this public repo. The fonts are free: Noto Serif
(OFL) and Roboto (Apache 2.0), both in every Android system image's /system/fonts, e.g.
    adb pull /system/fonts/NotoSerif-Bold.ttf
    adb pull /system/fonts/Roboto-Regular.ttf        (a variable font: pass --wght)

    python docs/play-store/outline_text.py FONT.ttf "Text" SIZE X BASELINE_Y [--wght 300]

Needs fontTools (pip install fonttools).
"""
import argparse

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer


def outline(font_path, text, size, x, y, wght=None):
    font = TTFont(font_path)
    if wght is not None and "fvar" in font:
        font = instancer.instantiateVariableFont(font, {"wght": wght})
    scale = size / font["head"].unitsPerEm
    cmap, glyphs, hmtx = font.getBestCmap(), font.getGlyphSet(), font["hmtx"]
    pen = SVGPathPen(glyphs, ntos=lambda n: f"{n:.1f}".rstrip("0").rstrip("."))
    for ch in text:
        name = cmap[ord(ch)]
        glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x, y)))
        x += hmtx[name][0] * scale
    return pen.getCommands()


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("font")
    p.add_argument("text")
    p.add_argument("size", type=float)
    p.add_argument("x", type=float)
    p.add_argument("y", type=float)
    p.add_argument("--wght", type=float)
    args = p.parse_args()
    print(f'<path d="{outline(args.font, args.text, args.size, args.x, args.y, args.wght)}"/>')
