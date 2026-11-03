#!/usr/bin/env python3
"""Phormi app icon - single source of truth for every launcher asset.

Draws one mark (a hand-cut monogram "P" in warm bone, with an ember sun held
inside its counter) and emits:

  * a master SVG + PNG (store listing / docs)
  * Android adaptive-icon vector drawables (background + foreground + monochrome)

Pure standard library. The PNG rasters are produced separately with resvg;
if resvg is unavailable only the vector sources are written.
"""
import math
import os
import re
import subprocess
import sys

# ----------------------------------------------------------------- palette
PINE_A = "#2C5248"      # muted pine green, top-left of the field
PINE_B = "#0A1715"      # near-black green, bottom-right
BONE_HI = "#FBF3E3"     # warm paper highlight
BONE_MID = "#F1E5CE"
BONE_LO = "#DFC9A3"     # warm shadow side of the letter
SUN_HI = "#FFD79E"
SUN_MID = "#E9955A"
SUN_LO = "#B24A28"
CONTOUR = "#4E796D"
RIM = "#8E3A22"
GRAIN_OPACITY = 0.05

# ------------------------------------------------- letterform (512 design units)
STEM_X = 186.0
STEM_TOP = 176.0
STEM_BOT = 372.0
BOWL_TOP = 176.0
BOWL_BOT = 316.0
BOWL_R = 356.0
STROKE = 62.0
HALF = STROKE / 2.0
MID_Y = (BOWL_TOP + BOWL_BOT) / 2.0
HOLE_L, HOLE_R = STEM_X + HALF, BOWL_R - HALF
SUN_CX, SUN_CY, SUN_R = (HOLE_L + HOLE_R) / 2.0, MID_Y, 31.0

BOWL_D = (f"M{STEM_X:g} {STEM_TOP:g} L248 {BOWL_TOP:g} "
          f"C326 {BOWL_TOP:g} {BOWL_R:g} 208 {BOWL_R:g} {MID_Y:g} "
          f"C{BOWL_R:g} 300 326 {BOWL_BOT:g} 248 {BOWL_BOT:g} L{STEM_X:g} {BOWL_BOT:g}")
STEM_D = f"M{STEM_X:g} {STEM_BOT:g} L{STEM_X:g} {STEM_TOP:g}"

X0, X1 = STEM_X - HALF, BOWL_R + HALF
Y0, Y1 = BOWL_TOP - HALF, STEM_BOT + HALF
BCX, BCY = (X0 + X1) / 2.0, (Y0 + Y1) / 2.0

K = 0.5522847498


def circle_path(cx, cy, r):
    o = K * r
    return (f"M{cx - r:.3f} {cy:.3f}"
            f"C{cx - r:.3f} {cy - o:.3f} {cx - o:.3f} {cy - r:.3f} {cx:.3f} {cy - r:.3f}"
            f"C{cx + o:.3f} {cy - r:.3f} {cx + r:.3f} {cy - o:.3f} {cx + r:.3f} {cy:.3f}"
            f"C{cx + r:.3f} {cy + o:.3f} {cx + o:.3f} {cy + r:.3f} {cx:.3f} {cy + r:.3f}"
            f"C{cx - o:.3f} {cy + r:.3f} {cx - r:.3f} {cy + o:.3f} {cx - r:.3f} {cy:.3f}Z")


def scaled(d, s, tx, ty):
    """Scale+translate an absolute M/L/C/Z path."""
    def repl(m):
        return f"{(float(m.group(1)) - BCX) * s + tx:.2f} {(float(m.group(2)) - BCY) * s + ty:.2f}"
    return re.sub(r"(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)", repl, d)


class Mark:
    """The P + sun, drawn into any square viewport so the mark's bounding circle
    lands on a chosen radius. This is what keeps every output icon identical."""

    def __init__(self, viewport, anchor_ratio, radius_ratio=None, scale=None):
        self.vp = viewport
        self.cx = self.cy = viewport * anchor_ratio
        if scale is None:
            radius = (X1 - X0) / 2.0  # unused; width is the driver
            target_w = viewport * radius_ratio * 2.0
            scale = target_w / (X1 - X0)
        self.s = scale
        self.bowl = scaled(BOWL_D, scale, self.cx, self.cy)
        self.stem = scaled(STEM_D, scale, self.cx, self.cy)
        self.sw = STROKE * scale
        self.sun_cx = (SUN_CX - BCX) * scale + self.cx
        self.sun_cy = (SUN_CY - BCY) * scale + self.cy
        self.sun_r = SUN_R * scale

    @classmethod
    def for_diagonal(cls, viewport, diagonal_ratio, cx_ratio=0.5, cy_ratio=0.5):
        """Size the mark so its bounding-box diagonal is `diagonal_ratio` of the
        viewport. Using the diagonal (not the width) keeps the mark inside
        circular / squircle / teardrop launcher masks without clipping."""
        w, h = X1 - X0, Y1 - Y0
        diag = math.hypot(w, h)
        scale = (viewport * diagonal_ratio) / diag
        m = cls(viewport, cx_ratio, scale=scale)
        m.cx = viewport * cx_ratio
        m.cy = viewport * cy_ratio
        m.bowl = scaled(BOWL_D, scale, m.cx, m.cy)
        m.stem = scaled(STEM_D, scale, m.cx, m.cy)
        m.sun_cx = (SUN_CX - BCX) * scale + m.cx
        m.sun_cy = (SUN_CY - BCY) * scale + m.cy
        m.sun_r = SUN_R * scale
        return m

    # -- shared gradient block -------------------------------------------
    def defs(self, uid, grain=False):
        return f'''    <linearGradient id="field{uid}" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="{self.vp*0.62:.2f}" y2="{self.vp:.2f}">
      <stop offset="0" stop-color="{PINE_A}"/><stop offset="1" stop-color="{PINE_B}"/>
    </linearGradient>
    <radialGradient id="warmth{uid}" gradientUnits="userSpaceOnUse" cx="{self.vp*0.27:.2f}" cy="{self.vp*0.07:.2f}" r="{self.vp*1.02:.2f}">
      <stop offset="0" stop-color="#E8A659" stop-opacity="0.18"/>
      <stop offset="0.55" stop-color="#C67537" stop-opacity="0.05"/>
      <stop offset="1" stop-color="#C67537" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="depth{uid}" gradientUnits="userSpaceOnUse" cx="{self.vp*0.88:.2f}" cy="{self.vp*0.95:.2f}" r="{self.vp*0.78:.2f}">
      <stop offset="0" stop-color="#05100D" stop-opacity="0.6"/>
      <stop offset="1" stop-color="#05100D" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="bone{uid}" gradientUnits="userSpaceOnUse"
        x1="{self.cx - self.sw*0.95:.2f}" y1="{self.cy - self.sw*1.75:.2f}"
        x2="{self.cx + self.sw*0.75:.2f}" y2="{self.cy + self.sw*1.9:.2f}">
      <stop offset="0" stop-color="{BONE_HI}"/>
      <stop offset="0.42" stop-color="{BONE_MID}"/>
      <stop offset="1" stop-color="{BONE_LO}"/>
    </linearGradient>
    <radialGradient id="ember{uid}" gradientUnits="userSpaceOnUse" cx="{self.sun_cx:.2f}" cy="{self.sun_cy:.2f}" r="{self.sun_r:.2f}">
      <stop offset="0" stop-color="{SUN_HI}"/>
      <stop offset="0.38" stop-color="{SUN_MID}"/>
      <stop offset="0.82" stop-color="#C4532F"/>
      <stop offset="1" stop-color="{SUN_LO}"/>
    </radialGradient>
    <radialGradient id="bloom{uid}" gradientUnits="userSpaceOnUse" cx="{self.sun_cx:.2f}" cy="{self.sun_cy:.2f}" r="{self.sun_r*3.1:.2f}">
      <stop offset="0" stop-color="#F0A05E" stop-opacity="0.34"/>
      <stop offset="0.45" stop-color="#E08A4C" stop-opacity="0.11"/>
      <stop offset="1" stop-color="#E08A4C" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="spill{uid}" gradientUnits="userSpaceOnUse" cx="{self.sun_cx:.2f}" cy="{self.sun_cy:.2f}" r="{self.sun_r*2.0:.2f}">
      <stop offset="0" stop-color="#FFCE8F" stop-opacity="0.46"/>
      <stop offset="0.30" stop-color="#F5A868" stop-opacity="0.18"/>
      <stop offset="1" stop-color="#F5A868" stop-opacity="0"/>
    </radialGradient>''' + (f'''
    <filter id="grain{uid}" x="0" y="0" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency="{0.85/self.vp*512:.4f}" numOctaves="3" seed="11" result="n"/>
      <feColorMatrix in="n" type="saturate" values="0"/>
    </filter>''' if grain else "")

    def mark(self, uid, shadow=True):
        s = self.s
        out = f'    <circle cx="{self.sun_cx:.2f}" cy="{self.sun_cy:.2f}" r="{self.sun_r*3.1:.2f}" fill="url(#bloom{uid})"/>\n'
        if shadow:
            dx, dy = 2.6 * s, 4.0 * s
            out += (f'    <g transform="translate({dx:.2f},{dy:.2f})" opacity="0.46">\n'
                    f'      <path d="{self.bowl}" fill="none" stroke="#04100C" stroke-width="{self.sw:.2f}" stroke-linecap="round" stroke-linejoin="round"/>\n'
                    f'      <path d="{self.stem}" fill="none" stroke="#04100C" stroke-width="{self.sw:.2f}" stroke-linecap="round"/>\n'
                    f'    </g>\n')
        out += (f'    <circle cx="{self.sun_cx:.2f}" cy="{self.sun_cy:.2f}" r="{self.sun_r:.2f}" fill="url(#ember{uid})"/>\n'
                f'    <circle cx="{self.sun_cx:.2f}" cy="{self.sun_cy:.2f}" r="{self.sun_r:.2f}" fill="none" stroke="{RIM}" stroke-width="{max(0.6, 1.9*s):.2f}" opacity="0.4"/>\n'
                f'    <path d="{self.bowl}" fill="none" stroke="url(#bone{uid})" stroke-width="{self.sw:.2f}" stroke-linecap="round" stroke-linejoin="round"/>\n'
                f'    <path d="{self.stem}" fill="none" stroke="url(#bone{uid})" stroke-width="{self.sw:.2f}" stroke-linecap="round"/>\n'
                # warm light spilling from the sun onto the letter, so the two feel lit as one object
                f'    <path d="{self.bowl}" fill="none" stroke="url(#spill{uid})" stroke-width="{self.sw*0.92:.2f}" stroke-linecap="round" stroke-linejoin="round"/>\n'
                f'    <path d="{self.stem}" fill="none" stroke="url(#spill{uid})" stroke-width="{self.sw*0.92:.2f}" stroke-linecap="round"/>\n')
        return out

    def field(self, uid, rings=True, grain=True):
        """background layers only (used by the adaptive background layer)."""
        out = (f'    <rect width="{self.vp}" height="{self.vp}" fill="url(#field{uid})"/>\n'
               f'    <rect width="{self.vp}" height="{self.vp}" fill="url(#warmth{uid})"/>\n')
        if rings:
            cx, cy = self.vp * 0.914, self.vp * 0.101
            out += '    <g fill="none">\n'
            for i in range(9):
                r = self.vp * (0.152 + 0.1015 * i)
                out += (f'      <circle cx="{cx:.2f}" cy="{cy:.2f}" r="{r:.2f}" stroke="{CONTOUR}" '
                        f'stroke-width="{max(0.3, self.vp*0.0029):.2f}" opacity="0.30"/>\n')
            out += '    </g>\n'
        out += f'    <rect width="{self.vp}" height="{self.vp}" fill="url(#depth{uid})"/>\n'
        return out


def svg_doc(vp, defs, body, w=None, h=None):
    w = w or vp
    h = h or w
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
            f'viewBox="0 0 {vp} {vp}">\n  <defs>\n{defs}\n  </defs>\n{body}</svg>\n')


# ----------------------------------------------------------- SVG builders
def master_svg():
    m = Mark.for_diagonal(512, 0.86, 0.5, 0.485)
    body = (m.field("m") + m.mark("m") +
            f'    <rect width="512" height="512" filter="url(#grainm)" opacity="{GRAIN_OPACITY}"/>\n')
    return svg_doc(512, m.defs("m", grain=True), body), m


def adaptive_foreground():
    vp = 108.0
    m = Mark.for_diagonal(vp, 0.545, 0.5, 0.495)
    return svg_doc(vp, m.defs("f"), m.mark("f", shadow=False))


def adaptive_background():
    vp = 108.0
    m = Mark.for_diagonal(vp, 0.545)
    return svg_doc(vp, m.defs("b"), m.field("b"))


# ------------------------------------------------------- Android vectors
def vector_header(comment, aapt=True):
    ns = ('    xmlns:aapt="http://schemas.android.com/aapt"\n' if aapt else "")
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- {comment} -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'{ns}'
            '    android:width="108dp"\n'
            '    android:height="108dp"\n'
            '    android:viewportWidth="108"\n'
            '    android:viewportHeight="108">\n')


def gradient_attr(kind, attrs, stops):
    a = "".join(f'\n                    android:{k}="{v}"' for k, v in attrs)
    s = "".join(f'\n                    android:{n}="{v}"' for n, v in stops)
    return f'''            <aapt:attr name="android:{kind}">
                <gradient
                    android:type="{attrs.pop("_type")}"{a}{s} />
            </aapt:attr>'''


def android_foreground():
    vp = 108.0
    m = Mark.for_diagonal(vp, 0.545, 0.5, 0.495)
    f = lambda v: f"{v:.2f}"

    def linear_fill(x1, y1, x2, y2, stops):
        s = "".join(f'\n                    android:{n}="{v}"' for n, v in stops)
        return (f'            <aapt:attr name="android:strokeColor">\n'
                f'                <gradient\n'
                f'                    android:type="linear"\n'
                f'                    android:startX="{f(x1)}"\n'
                f'                    android:startY="{f(y1)}"\n'
                f'                    android:endX="{f(x2)}"\n'
                f'                    android:endY="{f(y2)}"{s} />\n'
                f'            </aapt:attr>\n')

    bone_stops = [("startColor", BONE_HI), ("centerColor", BONE_MID), ("endColor", BONE_LO)]
    bx1, by1 = m.cx - m.sw * 0.95, m.cy - m.sw * 1.75
    bx2, by2 = m.cx + m.sw * 0.75, m.cy + m.sw * 1.9

    out = vector_header("Phormi launcher mark - hand-cut bone monogram P with an ember sun in its counter.\n"
                        "     Generated by tools/app_icon/generate_icon.py - edit that, not this file.")
    # sun glow
    out += f'''    <path android:pathData="{circle_path(m.sun_cx, m.sun_cy, m.sun_r*3.1)}">
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="radial"
                    android:centerX="{f(m.sun_cx)}"
                    android:centerY="{f(m.sun_cy)}"
                    android:gradientRadius="{f(m.sun_r*3.1)}"
                    android:startColor="#57F0A05E"
                    android:centerColor="#1CE08A4C"
                    android:endColor="#00E08A4C" />
            </aapt:attr>
    </path>
'''
    # sun
    out += f'''    <path android:pathData="{circle_path(m.sun_cx, m.sun_cy, m.sun_r)}">
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="radial"
                    android:centerX="{f(m.sun_cx - m.sun_r*0.22)}"
                    android:centerY="{f(m.sun_cy - m.sun_r*0.3)}"
                    android:gradientRadius="{f(m.sun_r*1.35)}"
                    android:startColor="{SUN_HI}"
                    android:centerColor="{SUN_MID}"
                    android:endColor="{SUN_LO}" />
            </aapt:attr>
    </path>
'''
    # letter
    out += f'''    <path
        android:pathData="{m.bowl}"
        android:strokeWidth="{f(m.sw)}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round">
{linear_fill(bx1, by1, bx2, by2, bone_stops)}    </path>
    <path
        android:pathData="{m.stem}"
        android:strokeWidth="{f(m.sw)}"
        android:strokeLineCap="round">
{linear_fill(bx1, by1, bx2, by2, bone_stops)}    </path>
</vector>
'''
    return out


def android_background():
    vp = 108.0
    m = Mark.for_diagonal(vp, 0.545)
    rings = ""
    cx, cy = vp * 0.914, vp * 0.101
    for i in range(9):
        r = vp * (0.152 + 0.1015 * i)
        rings += (f'        <path android:pathData="{circle_path(cx, cy, r)}" '
                  f'android:strokeColor="#4D{CONTOUR.lstrip("#")}" android:strokeWidth="0.31" />\n')

    out = vector_header("Phormi launcher background - pine field, contour rings, low warm glow.\n"
                        "     Generated by tools/app_icon/generate_icon.py - edit that, not this file.")
    out += f'''    <group>
        <clip-path android:pathData="M0,0h108v108h-108z" />
        <path android:pathData="M0,0h108v108h-108z">
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="linear"
                    android:startX="0" android:startY="0"
                    android:endX="67" android:endY="108"
                    android:startColor="{PINE_A}"
                    android:endColor="{PINE_B}" />
            </aapt:attr>
        </path>
        <path android:pathData="M0,0h108v108h-108z">
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="radial"
                    android:centerX="29" android:centerY="7.5"
                    android:gradientRadius="110"
                    android:startColor="#2EE8A659"
                    android:centerColor="#0DC67537"
                    android:endColor="#00C67537" />
            </aapt:attr>
        </path>
{rings}        <path android:pathData="M0,0h108v108h-108z">
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="radial"
                    android:centerX="95" android:centerY="102"
                    android:gradientRadius="84"
                    android:startColor="#9905100D"
                    android:endColor="#0005100D" />
            </aapt:attr>
        </path>
    </group>
</vector>
'''
    return out


def android_monochrome():
    vp = 108.0
    m = Mark.for_diagonal(vp, 0.545, 0.5, 0.495)
    f = lambda v: f"{v:.2f}"
    out = vector_header("Themed-icon silhouette (Android 13+). Solid shape only; the system tints it.",
                        aapt=False)
    out += f'''    <path
        android:pathData="{m.bowl}"
        android:strokeColor="#FF000000"
        android:strokeWidth="{f(m.sw)}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="{m.stem}"
        android:strokeColor="#FF000000"
        android:strokeWidth="{f(m.sw)}"
        android:strokeLineCap="round" />
    <path
        android:pathData="{circle_path(m.sun_cx, m.sun_cy, m.sun_r*0.60)}"
        android:fillColor="#FF000000" />
</vector>
'''
    return out


def adaptive_icon_xml(monochrome=True):
    mono = '\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />' if monochrome else ""
    return f'''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />{mono}
</adaptive-icon>
'''


def legacy_square():
    """Full-bleed square art for pre-Android-8 launchers (mipmap-*dpi/ic_launcher.png)."""
    m = Mark.for_diagonal(512, 0.80, 0.5, 0.49)
    body = m.field("l") + m.mark("l") + \
        f'    <rect width="512" height="512" filter="url(#grainl)" opacity="{GRAIN_OPACITY}"/>\n'
    return svg_doc(512, m.defs("l", grain=True), body)


def legacy_round():
    """Circular crop for pre-Android-8 round icons (ic_launcher_round.png)."""
    m = Mark.for_diagonal(512, 0.66, 0.5, 0.49)
    body = (f'    <clipPath id="roundl"><path d="{circle_path(256, 256, 254)}"/></clipPath>\n'
            f'    <g clip-path="url(#roundl)">\n'
            + m.field("r") + m.mark("r") +
            f'    <rect width="512" height="512" filter="url(#grainr)" opacity="{GRAIN_OPACITY}"/>\n'
            '    </g>\n')
    return svg_doc(512, m.defs("r", grain=True), body)


def app_mark_svg(viewport=512, bg=False):
    """Transparent (or full) Phormi mark used for the in-app logo + store art."""
    m = Mark.for_diagonal(viewport, 0.86, 0.5, 0.5)
    body = (m.field("a") if bg else "") + m.mark("a", shadow=False)
    return svg_doc(viewport, m.defs("a"), body)


# ------------------------------------------------------------------ main
def main(repo, work):
    res = os.path.join(repo, "app/src/main/res")
    brand = os.path.join(repo, "docs/branding")
    work = os.path.abspath(work)
    os.makedirs(brand, exist_ok=True)
    os.makedirs(work, exist_ok=True)

    files = {
        os.path.join(res, "drawable/ic_launcher_background.xml"): android_background(),
        os.path.join(res, "drawable/ic_launcher_foreground.xml"): android_foreground(),
        os.path.join(res, "drawable/ic_launcher_monochrome.xml"): android_monochrome(),
        os.path.join(res, "mipmap-anydpi-v26/ic_launcher.xml"): adaptive_icon_xml(),
        os.path.join(res, "mipmap-anydpi-v26/ic_launcher_round.xml"): adaptive_icon_xml(),
    }
    for p, c in files.items():
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as fh:
            fh.write(c)
        print("wrote", os.path.relpath(p, repo))

    ms, m = master_svg()
    with open(os.path.join(work, "phormi_icon.svg"), "w") as fh:
        fh.write(ms)
    with open(os.path.join(work, "adaptive_foreground.svg"), "w") as fh:
        fh.write(adaptive_foreground())
    with open(os.path.join(work, "adaptive_background.svg"), "w") as fh:
        fh.write(adaptive_background())
    with open(os.path.join(work, "legacy_square.svg"), "w") as fh:
        fh.write(legacy_square())
    with open(os.path.join(work, "legacy_round.svg"), "w") as fh:
        fh.write(legacy_round())
    with open(os.path.join(work, "phormi_mark.svg"), "w") as fh:
        fh.write(app_mark_svg())
    print("wrote svg sources to", work)


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.abspath(os.path.join(here, "../..")) if len(sys.argv) < 2 else sys.argv[1]
    work = os.path.join(repo, "docs/branding")
    main(repo, work)
