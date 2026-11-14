#!/usr/bin/env python3
"""Generate the synthetic table images used by Demo mode and the tests.

The images are deliberately built to match what the detectors expect:
- felt HSV inside the green (or blue) window in CvConfig
- 6 black pockets at the table corners + side midpoints
- balls radius 16 px at 1280x720 (about 9 px at the 720-wide working res,
  inside HoughCircles minRadius 8 / maxRadius 18)
- a white guideline from the cue ball toward the target ball, along the
  true aim direction (cue -> ghost point), so the pipeline should solve
  the same shot the image depicts
- a cue stick behind the cue ball
"""
import math
import os
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.expanduser(
    "~/workspace/production/8ball-pool-rea/guideline-tool/app/src/main/assets/demo"
)
ICON_DIR = os.path.expanduser(
    "~/workspace/production/8ball-pool-rea/guideline-tool/app/src/main/res/mipmap-xxxhdpi"
)
os.makedirs(OUT, exist_ok=True)
os.makedirs(ICON_DIR, exist_ok=True)

W, H = 1280, 720
BALL_R = 16

BALL_COLORS = {
    "yellow": (255, 215, 0),
    "blue": (30, 80, 220),
    "red": (220, 30, 30),
    "purple": (130, 40, 160),
    "orange": (255, 140, 0),
    "green": (20, 140, 60),
    "maroon": (140, 20, 40),
    "black": (15, 15, 15),
    "white": (245, 245, 245),
}


def draw_ball(draw, cx, cy, color_name, stripe=False, number=None):
    color = BALL_COLORS[color_name]
    draw.ellipse([cx - BALL_R, cy - BALL_R, cx + BALL_R, cy + BALL_R],
                 fill=color, outline=(0, 0, 0), width=1)
    if stripe:
        # white number band through the middle; centre pixel is white
        band = BALL_R // 2
        draw.rectangle([cx - BALL_R, cy - band, cx + BALL_R, cy + band],
                       fill=(245, 245, 245))
        draw.ellipse([cx - BALL_R, cy - BALL_R, cx + BALL_R, cy + BALL_R],
                     outline=(0, 0, 0), width=1)
    if number is not None:
        try:
            font = ImageFont.load_default(size=14)
        except TypeError:
            font = ImageFont.load_default()
        txt = str(number)
        bbox = draw.textbbox((0, 0), txt, font=font)
        tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
        draw.text((cx - tw / 2, cy - th / 2), txt, fill=(0, 0, 0), font=font)


def make_table(path, felt, cue, target, pocket, extra_balls):
    """felt: RGB. cue/target/pocket: (x, y). extra_balls: list of
    (x, y, color_name, stripe, number)."""
    img = Image.new("RGB", (W, H), (30, 30, 30))
    draw = ImageDraw.Draw(img)
    # wooden surround + rails
    draw.rounded_rectangle([40, 60, 1240, 660], radius=24, fill=(101, 67, 50))
    # felt
    draw.rounded_rectangle([80, 90, 1200, 630], radius=14, fill=felt)
    # pockets: 4 corners + side midpoints of the felt rect
    pockets = [(80, 90), (1200, 90), (1200, 630), (80, 630), (640, 90), (640, 630)]
    for px, py in pockets:
        draw.ellipse([px - 22, py - 22, px + 22, py + 22], fill=(0, 0, 0))

    # ghost-ball geometry (same math as ShotSolver)
    tx, ty = target
    px, py = pocket
    dx, dy = px - tx, py - ty
    d = math.hypot(dx, dy)
    ux, uy = dx / d, dy / d
    gx, gy = tx - 2 * BALL_R * ux, ty - 2 * BALL_R * uy
    # aim direction cue -> ghost
    ax, ay = gx - cue[0], gy - cue[1]
    ad = math.hypot(ax, ay)
    ax, ay = ax / ad, ay / ad

    # cue stick behind the cue ball
    sx, sy = cue[0] - ax * 20, cue[1] - ay * 20
    ex, ey = cue[0] - ax * 170, cue[1] - ay * 170
    draw.line([sx, sy, ex, ey], fill=(150, 100, 50), width=7)

    # the game's own white guideline: a segment of the TRUE aim ray
    # (cue -> ghost point), from the cue ball's edge to the foot of
    # the perpendicular from the target centre onto the ray, pulled
    # back along the ray so it stays 2 px clear of the target ball.
    # (v1.0.28: the old chord ran cue-edge -> target-edge and sat
    # 3.01 deg (green) / 2.05 deg (blue) off the true aim ray, so the
    # detector was measured against a line that was not the aim.)
    gx0, gy0 = cue[0] + ax * (BALL_R + 2), cue[1] + ay * (BALL_R + 2)
    foot_dist = (tx - cue[0]) * ax + (ty - cue[1]) * ay
    fx, fy = cue[0] + ax * foot_dist, cue[1] + ay * foot_dist
    foot_perp = math.hypot(tx - fx, ty - fy)
    end_dist = foot_dist
    if foot_perp < BALL_R + 2:
        end_dist = foot_dist - math.sqrt((BALL_R + 2) ** 2 - foot_perp ** 2)
    gx1, gy1 = cue[0] + ax * end_dist, cue[1] + ay * end_dist
    draw.line([gx0, gy0, gx1, gy1], fill=(255, 255, 255), width=3)
    # ghost-ball marker (small white circle at G)
    draw.ellipse([gx - 5, gy - 5, gx + 5, gy + 5], outline=(255, 255, 255), width=2)

    for (bx, by, color, stripe, number) in extra_balls:
        draw_ball(draw, bx, by, color, stripe, number)
    draw_ball(draw, target[0], target[1], "red", stripe=False, number=3)
    draw_ball(draw, cue[0], cue[1], "white", stripe=False, number=None)
    img.save(path)
    print("wrote", path)


# --- Green table: cue (350,360) -> red 3-ball (700,360) -> top-right pocket
green_balls = [
    (900, 250, "yellow", False, 1),
    (950, 420, "blue", False, 2),
    (500, 200, "orange", False, 5),
    (600, 500, "purple", False, 4),
    (850, 520, "black", False, 8),
    (420, 480, "green", False, 6),
    (1050, 320, "yellow", True, 9),
    (760, 180, "blue", True, 10),
    (300, 250, "red", True, 11),
    (980, 520, "orange", True, 13),
]
make_table(os.path.join(OUT, "demo_table_green.png"),
           (46, 139, 87), (350, 360), (700, 360), (1200, 90), green_balls)

# --- Green re-rank table (v1.0.21): same layout as the green table,
# but the orange 13-ball is moved off the bottom-right target line
# (from (980,520) to (1150,450)), so the best pocket (top-right,
# cut ~31 deg) stays blocked by the yellow 1-ball while the
# bottom-right pocket (cut ~31 deg) is clear. The pipeline must
# show the bottom-right shot. The guideline still points at the
# red 3-ball toward the top-right ghost, as on the green table.
# (1150,450) is 134 px clear of the nearest path and Hough-detects
# at the app's param2=15; the first tried spot (1100,480) missed
# Hough by a hair (downscale phase), and (1050,500) missed too —
# see cv-harness probe21c_ball_spot.py.
rerank_balls = [
    (900, 250, "yellow", False, 1),
    (950, 420, "blue", False, 2),
    (500, 200, "orange", False, 5),
    (600, 500, "purple", False, 4),
    (850, 520, "black", False, 8),
    (420, 480, "green", False, 6),
    (1050, 320, "yellow", True, 9),
    (760, 180, "blue", True, 10),
    (300, 250, "red", True, 11),
    (1150, 450, "orange", True, 13),
]
make_table(os.path.join(OUT, "demo_table_green_rerank.png"),
           (46, 139, 87), (350, 360), (700, 360), (1200, 90), rerank_balls)

# --- Green bank re-rank table (v1.0.22): only three balls. The cue
# (115,174) sits below the red 3-ball (115,107), which is frozen
# near the top rail, so no direct shot solves and the one-rail
# banks are the only option. The best bank (cut ~6 deg, into the
# top-left pocket off the top rail) is blocked by the yellow
# 1-ball at (105,225), which sits on its second leg; the next
# clear bank cuts ~67 deg into the bottom-middle pocket. The
# pipeline must show the clear bank, unflagged. The guideline
# aims at the shown bank's ghost, computed through the MIRRORED
# pocket (640,-450): the same mirror math ShotSolver uses, so the
# drawn line points exactly where the solver aims.
make_table(os.path.join(OUT, "demo_table_green_bank_rerank.png"),
           (46, 139, 87), (115, 174), (115, 107), (640, -450),
           [(105, 225, "yellow", False, 1)])

# --- Blue table: cue (300,500) -> red 3-ball (800,200). The aim line is
# drawn toward the top-left pocket's ghost point, but that pocket is
# geometrically unreachable (cut ~141 deg, the solver correctly rejects
# it); the shot solves via the top-right pocket instead (cut ~17 deg,
# the smallest cut). The test only asserts that a shot solves.
# The green 6-ball sits on the SOLVED shot's aim line (12.6 px off it
# at working res, inside the one-diameter blocked threshold), so the
# depicted shot is flagged blocked. (v1.0.28: it moved from (500,350)
# to (635,266), 160 px along the solved aim line: once the guideline
# became the true aim ray, the old spot scored better than the red
# ball in the target pick (green 24.7 vs red 26.1) and would have
# flipped the target. The move keeps it exactly as far off the
# solved aim line, so it still blocks the depicted shot, while the
# red ball now wins the pick by ~9 points.)
blue_balls = [
    (950, 300, "yellow", False, 1),
    (600, 420, "blue", False, 2),
    (450, 180, "orange", False, 5),
    (1000, 500, "purple", False, 4),
    (700, 540, "black", False, 8),
    (635, 266, "green", False, 6),
    (1100, 200, "yellow", True, 9),
    (350, 300, "blue", True, 10),
    (900, 450, "red", True, 11),
]
make_table(os.path.join(OUT, "demo_table_blue.png"),
           (40, 90, 160), (300, 500), (800, 200), (80, 90), blue_balls)

# --- Pure black frame (FLAG_SECURE / dead-capture test) ---
Image.new("RGB", (640, 360), (0, 0, 0)).save(os.path.join(OUT, "demo_black.png"))
print("wrote demo_black.png")

# --- Launcher icon (192x192, xxxhdpi) ---
icon = Image.new("RGB", (192, 192), (27, 94, 32))
d = ImageDraw.Draw(icon)
d.ellipse([46, 46, 146, 146], fill=(17, 17, 17))
d.ellipse([76, 76, 116, 116], fill=(255, 255, 255))
try:
    font = ImageFont.load_default(size=28)
except TypeError:
    font = ImageFont.load_default()
d.text((88, 82), "8", fill=(17, 17, 17), font=font)
icon.save(os.path.join(ICON_DIR, "ic_launcher.png"))
print("wrote ic_launcher.png")
