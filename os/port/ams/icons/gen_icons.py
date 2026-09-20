#!/usr/bin/env python3
"""gen_icons.py - draw the S100 home/menu icons as 40x40 RGBA PNGs.

No PIL needed: shapes are rasterised with 4x supersampling into a float
canvas and written with a minimal PNG encoder. The PNGs next to this
script are the build input (midp's image2raw turns them into appdb/*.raw,
loaded by Icons.java); rerun `python gen_icons.py` after editing.
"""
import math
import os
import struct
import sys
import zlib

SIZE = 40          # output icon size
S = 4              # supersampling factor
N = SIZE * S


class Canvas:
    """Drawings are always in 0..40 icon units; `out` is the pixel size of
    the PNG (40 for menu icons, 20 for the list-row icons of the file
    manager)."""

    def __init__(self, out=SIZE):
        self.out = out
        n = out * S
        self.n = n
        self.unit = SIZE / out
        self.px = [[[0.0, 0.0, 0.0, 0.0] for _ in range(n)] for _ in range(n)]

    def paint(self, pred, color, alpha=1.0):
        """Composite `color` (0-255 rgb) wherever pred(x, y) holds (x, y in icon units)."""
        r, g, b = [c / 255.0 for c in color]
        n = self.n
        u = self.unit
        for j in range(n):
            y = (j + 0.5) / S * u
            row = self.px[j]
            for i in range(n):
                x = (i + 0.5) / S * u
                if pred(x, y):
                    d = row[i]
                    a = alpha
                    d[0] = r * a + d[0] * (1 - a)
                    d[1] = g * a + d[1] * (1 - a)
                    d[2] = b * a + d[2] * (1 - a)
                    d[3] = a + d[3] * (1 - a)

    def downsample(self):
        out = []
        for j in range(self.out):
            row = []
            for i in range(self.out):
                r = g = b = a = 0.0
                for dj in range(S):
                    for di in range(S):
                        p = self.px[j * S + dj][i * S + di]
                        r += p[0] * p[3]
                        g += p[1] * p[3]
                        b += p[2] * p[3]
                        a += p[3]
                k = S * S
                if a > 0:
                    row.append((int(r / a * 255 + 0.5), int(g / a * 255 + 0.5),
                                int(b / a * 255 + 0.5), int(a / k * 255 + 0.5)))
                else:
                    row.append((0, 0, 0, 0))
            out.append(row)
        return out


# ---- shape predicates (icon units, 0..40) ---------------------------------

def circle(cx, cy, r):
    return lambda x, y: (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def ring(cx, cy, r1, r2):
    return lambda x, y: r1 * r1 <= (x - cx) ** 2 + (y - cy) ** 2 <= r2 * r2


def rect(x0, y0, w, h):
    return lambda x, y: x0 <= x <= x0 + w and y0 <= y <= y0 + h


def rrect(x0, y0, w, h, r):
    def f(x, y):
        if not (x0 <= x <= x0 + w and y0 <= y <= y0 + h):
            return False
        cx = min(max(x, x0 + r), x0 + w - r)
        cy = min(max(y, y0 + r), y0 + h - r)
        return (x - cx) ** 2 + (y - cy) ** 2 <= r * r
    return f


def poly(pts):
    def f(x, y):
        inside = False
        n = len(pts)
        for i in range(n):
            x1, y1 = pts[i]
            x2, y2 = pts[(i + 1) % n]
            if (y1 > y) != (y2 > y):
                xi = x1 + (y - y1) * (x2 - x1) / (y2 - y1)
                if x < xi:
                    inside = not inside
        return inside
    return f


def line(x1, y1, x2, y2, w):
    """Thick line with round caps."""
    def f(x, y):
        dx, dy = x2 - x1, y2 - y1
        l2 = dx * dx + dy * dy
        t = 0 if l2 == 0 else max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / l2))
        px, py = x1 + t * dx, y1 + t * dy
        return (x - px) ** 2 + (y - py) ** 2 <= (w / 2) ** 2
    return f


def union(*ps):
    return lambda x, y: any(p(x, y) for p in ps)


def minus(p, q):
    return lambda x, y: p(x, y) and not q(x, y)


def rotate(p, cx, cy, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)

    def f(x, y):
        dx, dy = x - cx, y - cy
        return p(cx + dx * c + dy * s, cy - dx * s + dy * c)
    return f


# ---- palette ---------------------------------------------------------------
WHITE = (255, 255, 255)
BLACK = (20, 20, 20)
SHADOW = (0, 0, 0)
BLUE = (36, 96, 200)
BLUE_D = (22, 60, 140)
BLUE_L = (120, 170, 240)
GREEN = (60, 170, 70)
GREEN_D = (30, 110, 45)
YELLOW = (250, 210, 70)
ORANGE = (240, 140, 40)
RED = (220, 60, 50)
GREY = (150, 155, 165)
GREY_D = (90, 95, 105)
GREY_L = (215, 218, 225)
SKIN = (245, 205, 160)


def shadow(c, pred):
    c.paint(lambda x, y: pred(x - 1.2, y - 1.5), SHADOW, 0.25)


# ---- the icons -------------------------------------------------------------

def icon_messaging(c):
    body = rrect(4, 9, 32, 23, 2.5)
    shadow(c, body)
    c.paint(body, YELLOW)
    c.paint(minus(body, rrect(5.2, 10.2, 29.6, 20.6, 2)), ORANGE)
    flap = poly([(4.5, 10), (20, 22), (35.5, 10)])
    c.paint(flap, (255, 235, 150))
    c.paint(union(line(5, 10.5, 20, 22, 1.4), line(20, 22, 35, 10.5, 1.4)), ORANGE)
    c.paint(union(line(5, 31, 15.5, 21, 1.2), line(35, 31, 24.5, 21, 1.2)), ORANGE)


def icon_contacts(c):
    card = rrect(5, 6, 30, 29, 3)
    shadow(c, card)
    c.paint(card, GREY_L)
    c.paint(minus(card, rrect(6.2, 7.2, 27.6, 26.6, 2.5)), GREY)
    c.paint(rect(5, 6, 30, 5.5), BLUE)
    c.paint(rrect(9, 15, 7, 13, 1.5), BLUE_L)          # photo frame
    c.paint(circle(12.5, 19.5, 2.2), SKIN)
    c.paint(minus(circle(12.5, 26, 3.6), rect(8, 26, 10, 10)), BLUE_D)
    for i, w in enumerate((12, 9, 11)):
        c.paint(rrect(19, 16 + i * 4.2, w, 1.8, 0.9), GREY_D)


def icon_log(c):
    # handset
    hs = union(rrect(6, 6, 9, 12, 3.5), rrect(6, 22, 9, 12, 3.5), rrect(6, 10, 4, 20, 2),
               rrect(10, 16, 13, 8, 4), rrect(21, 6, 9, 12, 3.5), rrect(21, 22, 9, 12, 3.5),
               rrect(26, 10, 4, 20, 2))
    hs = rotate(union(rrect(8, 4, 8, 12, 3.5), rrect(8, 24, 8, 12, 3.5), rrect(9, 12, 5, 16, 2.5)),
                12, 20, 0)
    handset = rotate(hs, 14, 20, -40)
    shadow(c, handset)
    c.paint(handset, GREEN)
    c.paint(minus(handset, rotate(union(rrect(9, 5, 6, 10, 3), rrect(9, 25, 6, 10, 3),
                                        rrect(10, 13, 3, 14, 1.5)), 14, 20, -40)), GREEN_D)
    # clock
    c.paint(circle(29, 29, 8.5), GREY_D)
    c.paint(circle(29, 29, 7), WHITE)
    c.paint(union(line(29, 29, 29, 24, 1.4), line(29, 29, 32.5, 31, 1.4)), BLACK)
    c.paint(circle(29, 29, 1), BLACK)


def icon_settings(c):
    teeth = [rotate(rect(17.5, 3, 5, 34), 20, 20, a) for a in (0, 30, 60, 90, 120, 150)]
    gear = minus(union(circle(20, 20, 13), *teeth), circle(20, 20, 5))
    shadow(c, gear)
    c.paint(gear, GREY)
    c.paint(minus(gear, union(circle(20, 20, 11.5), *[rotate(rect(18.5, 4.2, 3, 32), 20, 20, a)
                                                        for a in (0, 30, 60, 90, 120, 150)])),
            GREY_D)
    c.paint(ring(20, 20, 5, 6.5), GREY_D)


def icon_organiser(c):
    page = rrect(6, 7, 28, 28, 3)
    shadow(c, page)
    c.paint(page, WHITE)
    c.paint(minus(page, rrect(7.2, 8.2, 25.6, 25.6, 2.5)), GREY)
    c.paint(minus(rrect(6, 7, 28, 9, 3), rect(0, 12, 40, 10)), RED)
    c.paint(rect(6, 12, 28, 4), RED)
    c.paint(union(rrect(12, 4, 3, 6, 1.5), rrect(25, 4, 3, 6, 1.5)), GREY_D)
    for r in range(3):
        for k in range(4):
            col = BLUE if (r, k) == (1, 2) else GREY_L
            c.paint(rrect(9.5 + k * 6, 19 + r * 5, 4, 3.5, 0.8), col)


def icon_applications(c):
    tiles = [((6, 6), BLUE), ((21, 6), GREEN), ((6, 21), ORANGE), ((21, 21), RED)]
    for (x, y), col in tiles:
        t = rrect(x, y, 13, 13, 3)
        shadow(c, t)
        c.paint(t, col)
        c.paint(minus(rrect(x + 1.5, y + 1.5, 10, 5, 2), rect(x, y + 5.5, 20, 10)), WHITE, 0.35)


def icon_phone(c):
    body = rrect(11, 3, 18, 34, 4)
    shadow(c, body)
    c.paint(body, GREY_D)
    c.paint(rrect(13, 6, 14, 15, 1.5), BLUE_L)
    c.paint(rrect(14, 7, 12, 6, 1), (170, 205, 250))
    for r in range(3):
        for k in range(3):
            c.paint(rrect(13.5 + k * 4.5, 23 + r * 4, 3.2, 2.6, 0.8), GREY_L)


def icon_running(c):
    for i, col in enumerate((BLUE_L, BLUE, BLUE_D)):
        w = rrect(6 + i * 4, 6 + i * 4, 22, 20, 2)
        shadow(c, w)
        c.paint(w, WHITE)
        c.paint(minus(w, rrect(7 + i * 4, 7 + i * 4, 20, 18, 1.5)), col)
        c.paint(rect(6 + i * 4, 6 + i * 4, 22, 4), col)


def icon_install(c):
    box = union(rrect(6, 20, 28, 15, 2), rect(6, 20, 28, 4))
    shadow(c, box)
    c.paint(box, ORANGE)
    c.paint(rect(6, 20, 28, 4), (200, 110, 30))
    arrow = union(rect(17.5, 4, 5, 12), poly([(12, 14), (28, 14), (20, 22)]))
    c.paint(arrow, GREEN)


def icon_calculator(c):
    body = rrect(8, 4, 24, 32, 3)
    shadow(c, body)
    c.paint(body, GREY_D)
    c.paint(rrect(10.5, 6.5, 19, 7, 1.5), (200, 230, 200))
    for r in range(3):
        for k in range(3):
            col = ORANGE if (r == 2 and k == 2) else GREY_L
            c.paint(rrect(10.5 + k * 6.5, 16 + r * 6, 5, 4.5, 1), col)


def icon_stopwatch(c):
    face = circle(20, 22, 13)
    shadow(c, face)
    c.paint(face, GREY_D)
    c.paint(circle(20, 22, 11), WHITE)
    c.paint(rrect(17, 4, 6, 4, 1), GREY_D)
    c.paint(rotate(rrect(29, 8, 5, 3, 1), 31.5, 9.5, -45), GREY_D)
    for a in range(0, 360, 30):
        c.paint(rotate(rect(19.5, 12, 1, 2.2), 20, 22, a), BLACK)
    c.paint(line(20, 22, 20, 14, 1.4), RED)
    c.paint(circle(20, 22, 1.2), BLACK)


def icon_notes(c):
    page = poly([(8, 5), (32, 5), (32, 27), (24, 35), (8, 35)])
    shadow(c, page)
    c.paint(page, (255, 245, 170))
    c.paint(poly([(24, 27), (32, 27), (24, 35)]), (230, 210, 120))
    for i in range(4):
        c.paint(rrect(12, 11 + i * 5, 16 - (i == 3) * 6, 1.6, 0.8), GREY_D)


def icon_certificate(c):
    page = rrect(7, 5, 26, 30, 2.5)
    shadow(c, page)
    c.paint(page, WHITE)
    c.paint(minus(page, rrect(8.2, 6.2, 23.6, 27.6, 2)), GREY)
    for i in range(3):
        c.paint(rrect(11, 10 + i * 4.5, 18 - (i == 2) * 7, 1.6, 0.8), GREY_D)
    c.paint(circle(26, 28, 6), RED)
    c.paint(circle(26, 28, 3.5), (250, 150, 140))
    c.paint(poly([(23, 32), (29, 32), (28, 38), (26, 36.5), (24, 38)]), RED)


def icon_profile(c):
    bell = union(minus(circle(20, 19, 11), rect(0, 19, 40, 30)), rect(9, 19, 22, 9),
                 poly([(7, 28), (33, 28), (34, 31), (6, 31)]))
    shadow(c, bell)
    c.paint(bell, YELLOW)
    c.paint(circle(20, 34, 3), ORANGE)
    c.paint(rrect(18.5, 5, 3, 4, 1.5), ORANGE)


def icon_display(c):
    sun = union(circle(20, 20, 8), *[rotate(rrect(19, 3, 2, 6, 1), 20, 20, a) for a in range(0, 360, 45)])
    shadow(c, sun)
    c.paint(sun, YELLOW)
    c.paint(circle(20, 20, 6), ORANGE)


def icon_clock(c):
    face = circle(20, 20, 15)
    shadow(c, face)
    c.paint(face, BLUE)
    c.paint(circle(20, 20, 13), WHITE)
    for a in range(0, 360, 90):
        c.paint(rotate(rect(19.4, 8.5, 1.2, 2.5), 20, 20, a), BLACK)
    c.paint(union(line(20, 20, 20, 11, 1.6), line(20, 20, 26.5, 20, 1.6)), BLACK)
    c.paint(circle(20, 20, 1.4), RED)


def icon_shortcuts(c):
    c.paint(circle(20, 20, 15), BLUE_D)
    c.paint(circle(20, 20, 13.5), BLUE_L)
    for a in range(0, 360, 90):
        c.paint(rotate(poly([(20, 7.5), (15.5, 12.5), (24.5, 12.5)]), 20, 20, a), WHITE)
    c.paint(circle(20, 20, 4), WHITE)


def icon_info(c):
    c.paint(circle(20, 20, 15), BLUE_D)
    c.paint(circle(20, 20, 13.5), BLUE)
    c.paint(circle(20, 12.5, 2.2), WHITE)
    c.paint(rrect(18, 17, 4, 12, 1.5), WHITE)


def icon_exit(c):
    door = rrect(8, 5, 16, 30, 2)
    shadow(c, door)
    c.paint(door, (170, 110, 60))
    c.paint(minus(door, rrect(9.5, 6.5, 13, 27, 1.5)), (120, 75, 40))
    c.paint(circle(21, 21, 1.4), YELLOW)
    c.paint(union(rect(24, 18.5, 8, 3), poly([(31, 14), (37, 20), (31, 26)])), GREEN)


def icon_inbox(c):
    tray = union(poly([(6, 22), (34, 22), (32, 34), (8, 34)]), rect(6, 22, 28, 3))
    shadow(c, tray)
    c.paint(tray, GREY_D)
    c.paint(rect(14, 25, 12, 3), GREY_L)
    c.paint(union(rect(17.5, 5, 5, 11), poly([(12, 14), (28, 14), (20, 22)])), BLUE)


def icon_compose(c):
    page = rrect(7, 6, 22, 28, 2)
    shadow(c, page)
    c.paint(page, WHITE)
    c.paint(minus(page, rrect(8.2, 7.2, 19.6, 25.6, 1.5)), GREY)
    for i in range(3):
        c.paint(rrect(11, 12 + i * 5, 12, 1.6, 0.8), GREY_D)
    pen = rotate(union(rrect(28, 12, 6, 18, 1), poly([(28, 30), (34, 30), (31, 36)])), 31, 24, 40)
    c.paint(pen, ORANGE)
    c.paint(rotate(rect(28, 12, 6, 4), 31, 24, 40), RED)


# ---- camera, file manager, connectivity ------------------------------------

def icon_camera(c):
    body = rrect(4, 11, 32, 23, 3)
    shadow(c, body)
    c.paint(body, GREY_D)
    c.paint(rrect(13, 7, 12, 6, 1.5), GREY_D)           # viewfinder hump
    c.paint(rect(4, 15, 32, 3), GREY)
    c.paint(circle(22, 23, 8), GREY_L)
    c.paint(circle(22, 23, 6.2), BLUE_D)
    c.paint(circle(22, 23, 3.8), BLUE)
    c.paint(circle(20, 21, 1.4), WHITE)
    c.paint(rrect(7, 18, 5, 3, 1), YELLOW)              # flash window


def icon_files(c):
    back = poly([(5, 9), (16, 9), (19, 12), (35, 12), (35, 33), (5, 33)])
    shadow(c, back)
    c.paint(back, ORANGE)
    c.paint(poly([(5, 15), (35, 15), (35, 33), (5, 33)]), YELLOW)
    c.paint(rrect(18, 20, 12, 3, 1), (230, 200, 90))


def icon_album(c):
    frame = rrect(5, 7, 30, 26, 2)
    shadow(c, frame)
    c.paint(frame, WHITE)
    c.paint(minus(frame, rrect(6.5, 8.5, 27, 23, 1.5)), GREY)
    c.paint(rect(7, 9, 26, 22), (150, 200, 250))
    c.paint(poly([(7, 31), (16, 19), (22, 26), (26, 22), (33, 31)]), GREEN)
    c.paint(circle(27, 14, 3), YELLOW)


def icon_video(c):
    body = rrect(4, 12, 24, 17, 3)
    shadow(c, body)
    c.paint(body, GREY_D)
    c.paint(poly([(28, 17), (36, 12), (36, 29), (28, 24)]), GREY_D)
    c.paint(circle(13, 20.5, 5), GREY_L)
    c.paint(circle(13, 20.5, 3), BLUE)
    c.paint(circle(22, 16, 1.6), RED)


def half_ring_up(cx, cy, r, w):
    """Upper half of a ring (an arc opening downwards)."""
    return minus(ring(cx, cy, r - w, r), rect(0, cy, 40, 40))


def icon_connectivity(c):
    c.paint(circle(20, 20, 15), BLUE_D)
    c.paint(circle(20, 20, 13.5), BLUE)
    c.paint(half_ring_up(20, 26, 11, 2), WHITE)
    c.paint(half_ring_up(20, 26, 7, 2), WHITE)
    c.paint(circle(20, 26, 2.6), WHITE)


def icon_wifi(c):
    sides = union(poly([(0, 0), (20, 31), (0, 40)]), poly([(40, 0), (20, 31), (40, 40)]))
    for r in (16, 11, 6):
        wedge = minus(half_ring_up(20, 31, r, 3), sides)
        shadow(c, wedge)
        c.paint(wedge, BLUE)
    c.paint(circle(20, 31, 2.8), BLUE_D)


def icon_bluetooth(c):
    c.paint(circle(20, 20, 15), BLUE_D)
    c.paint(circle(20, 20, 13.5), BLUE)
    rune = union(line(20, 8, 20, 32, 2.2), line(20, 8, 27, 14, 2.2), line(27, 14, 13, 26, 2.2),
                 line(20, 32, 27, 26, 2.2), line(27, 26, 13, 14, 2.2))
    c.paint(rune, WHITE)


def icon_hotspot(c):
    # two arcs on each side of a small mast
    for r in (9, 14):
        rg = ring(20, 22, r - 2.2, r)
        left = minus(rg, poly([(20, 22), (30, 0), (40, 0), (40, 40), (30, 44)]))
        left = minus(left, poly([(20, 22), (10, 0), (0, 0), (0, 44), (10, 44)]))
        c.paint(minus(rg, union(poly([(20, 22), (8, 0), (32, 0)]), poly([(20, 22), (8, 44), (32, 44)]))), BLUE)
    c.paint(circle(20, 22, 3.5), BLUE_D)
    c.paint(rect(18.6, 24, 2.8, 12), GREY_D)


def icon_usb(c):
    plug = rrect(13, 4, 14, 12, 1.5)
    shadow(c, plug)
    c.paint(plug, GREY_L)
    c.paint(minus(plug, rrect(14.5, 5.5, 11, 9, 1)), GREY_D)
    c.paint(union(rrect(16, 7.5, 3, 2, 0.5), rrect(21, 7.5, 3, 2, 0.5)), GREY_D)
    body = rrect(11, 16, 18, 20, 2.5)
    shadow(c, body)
    c.paint(body, GREY_D)
    c.paint(union(line(20, 18, 20, 33, 2), circle(20, 33, 2.4), line(20, 26, 14, 22, 1.8),
                  line(20, 29, 26, 25, 1.8), circle(14, 22, 1.8), rect(24.5, 22.5, 3, 3)), WHITE)


def icon_installfile(c):
    page = rrect(9, 4, 22, 28, 2)
    shadow(c, page)
    c.paint(page, WHITE)
    c.paint(minus(page, rrect(10.2, 5.2, 19.6, 25.6, 1.5)), GREY)
    c.paint(union(rrect(13, 9, 14, 1.6, 0.8), rrect(13, 13, 10, 1.6, 0.8)), GREY_D)
    c.paint(circle(27, 28, 8.5), GREEN_D)
    c.paint(circle(27, 28, 7), GREEN)
    c.paint(union(rect(25.5, 22.5, 3, 7), poly([(22, 28), (32, 28), (27, 33.5)])), WHITE)


# 20 px row icons for the file manager (drawn in the same 40-unit space)

def icon_folder(c):
    back = poly([(3, 8), (16, 8), (19, 12), (37, 12), (37, 34), (3, 34)])
    c.paint(back, ORANGE)
    c.paint(poly([(3, 16), (37, 16), (37, 34), (3, 34)]), YELLOW)


def icon_file(c):
    page = poly([(8, 3), (26, 3), (33, 10), (33, 37), (8, 37)])
    c.paint(page, WHITE)
    c.paint(minus(page, poly([(10, 5), (25, 5), (31, 11), (31, 35), (10, 35)])), GREY_D)
    c.paint(poly([(26, 3), (33, 10), (26, 10)]), GREY_L)


def icon_file_image(c):
    icon_file(c)
    c.paint(rect(12, 16, 17, 15), (150, 200, 250))
    c.paint(poly([(12, 31), (18, 22), (22, 27), (25, 24), (29, 31)]), GREEN)
    c.paint(circle(24, 19, 2), YELLOW)


def icon_file_audio(c):
    icon_file(c)
    c.paint(union(rect(20, 14, 2.5, 14), rect(20, 14, 9, 2.5), circle(18, 28, 4),
                  rect(27, 14, 2.5, 8), circle(25, 22, 4)), BLUE)


def icon_file_video(c):
    icon_file(c)
    c.paint(rrect(11, 15, 13, 12, 1.5), BLUE)
    c.paint(poly([(24, 18), (30, 15), (30, 27), (24, 24)]), BLUE)
    c.paint(poly([(15, 18), (21, 21), (15, 24)]), WHITE)


def icon_file_java(c):
    icon_file(c)
    c.paint(circle(20, 22, 8.5), ORANGE)
    c.paint(circle(20, 22, 7), RED)
    c.paint(union(rect(18.5, 17, 3, 8), poly([(15, 24), (25, 24), (20, 29)])), WHITE)


def icon_file_text(c):
    icon_file(c)
    for i in range(4):
        c.paint(rect(12, 15 + i * 5, 17 - (i == 3) * 8, 2), GREY_D)


def icon_network(c):
    # a globe: circle with meridian and parallels
    c.paint(circle(20, 20, 15), BLUE_D)
    c.paint(circle(20, 20, 13.5), BLUE)
    c.paint(ring(20, 20, 12, 13.5), WHITE)
    c.paint(minus(ring(20, 20, 5.5, 7), union(rect(0, 0, 40, 7.5), rect(0, 32.5, 40, 8))), WHITE)
    c.paint(rect(19.3, 7, 1.4, 26), WHITE)
    c.paint(rect(7, 19.3, 26, 1.4), WHITE)
    c.paint(rect(9, 13, 22, 1.2), WHITE)
    c.paint(rect(9, 25.8, 22, 1.2), WHITE)


def icon_sim(c):
    body = minus(rrect(11, 6, 18, 28, 2.5), poly([(23, 6), (29, 6), (29, 12)]))
    shadow(c, body)
    c.paint(body, GREY_L)
    c.paint(rrect(14, 16, 12, 10, 1.5), YELLOW)
    c.paint(rect(14, 19.5, 12, 0.9), ORANGE)
    c.paint(rect(14, 22.5, 12, 0.9), ORANGE)
    c.paint(rect(18, 16, 0.9, 10), ORANGE)
    c.paint(rect(22, 16, 0.9, 10), ORANGE)


def icon_airplane(c):
    c.paint(circle(20, 20, 15), BLUE_D)
    c.paint(circle(20, 20, 13.5), BLUE)
    plane = union(poly([(20, 8), (22, 10), (22, 17), (32, 23), (32, 26), (22, 23), (22, 28),
                        (25, 30), (25, 32), (20, 31), (15, 32), (15, 30), (18, 28), (18, 23),
                        (8, 26), (8, 23), (18, 17), (18, 10)]))
    c.paint(plane, WHITE)


def icon_vpn(c):
    # a shield with a keyhole
    sh = union(rect(9, 7, 22, 14), poly([(9, 21), (31, 21), (20, 34)]))
    shadow(c, sh)
    c.paint(sh, GREEN_D)
    c.paint(union(rect(11, 9, 18, 12), poly([(11, 21), (29, 21), (20, 31)])), GREEN)
    c.paint(circle(20, 17, 3.2), WHITE)
    c.paint(poly([(18.5, 18), (21.5, 18), (22.5, 26), (17.5, 26)]), WHITE)


def icon_dns(c):
    # a server box with a small lock
    box = rrect(6, 9, 28, 22, 2)
    shadow(c, box)
    c.paint(box, GREY_D)
    for i in range(3):
        c.paint(rrect(8, 11 + i * 6.5, 24, 5, 1), GREY_L)
        c.paint(circle(29, 13.5 + i * 6.5, 1.3), GREEN)
    c.paint(rrect(22, 22, 12, 10, 2), BLUE)
    c.paint(minus(ring(28, 22, 2.8, 4.3), rect(0, 22, 40, 20)), BLUE)
    c.paint(circle(28, 26.5, 1.5), WHITE)


def icon_location(c):
    # a map pin
    pin = union(circle(20, 15, 10), poly([(11, 19), (29, 19), (20, 35)]))
    shadow(c, pin)
    c.paint(pin, RED)
    c.paint(circle(20, 15, 4.5), WHITE)


def icon_security(c):
    # a padlock
    c.paint(minus(ring(20, 15, 5.5, 9), rect(0, 15, 40, 25)), GREY_D)
    body = rrect(8, 15, 24, 18, 3)
    shadow(c, body)
    c.paint(body, YELLOW)
    c.paint(rrect(8, 15, 24, 3, 1.5), ORANGE)
    c.paint(circle(20, 22, 3), GREY_D)
    c.paint(poly([(18.6, 23), (21.4, 23), (22.2, 29), (17.8, 29)]), GREY_D)


def icon_music(c):
    # a beamed pair of notes
    c.paint(circle(20, 20, 15), BLUE_D)
    c.paint(circle(20, 20, 13.5), BLUE)
    notes = union(rect(15.5, 11, 2, 14), rect(24.5, 9, 2, 14),
                  poly([(15.5, 11), (26.5, 8.5), (26.5, 12.5), (15.5, 15)]),
                  circle(13.5, 25.5, 3.3), circle(22.5, 23.5, 3.3))
    c.paint(notes, WHITE)


SMALL_ICONS = {
    "s100_folder": 20, "s100_file": 20, "s100_file_image": 20, "s100_file_audio": 20,
    "s100_file_video": 20, "s100_file_java": 20, "s100_file_text": 20,
}

ICONS = {
    "s100_camera": icon_camera,
    "s100_files": icon_files,
    "s100_album": icon_album,
    "s100_video": icon_video,
    "s100_connectivity": icon_connectivity,
    "s100_wifi": icon_wifi,
    "s100_bluetooth": icon_bluetooth,
    "s100_hotspot": icon_hotspot,
    "s100_usb": icon_usb,
    "s100_installfile": icon_installfile,
    "s100_folder": icon_folder,
    "s100_file": icon_file,
    "s100_file_image": icon_file_image,
    "s100_file_audio": icon_file_audio,
    "s100_file_video": icon_file_video,
    "s100_file_java": icon_file_java,
    "s100_file_text": icon_file_text,
    "s100_messaging": icon_messaging,
    "s100_contacts": icon_contacts,
    "s100_log": icon_log,
    "s100_settings": icon_settings,
    "s100_organiser": icon_organiser,
    "s100_applications": icon_applications,
    "s100_phone": icon_phone,
    "s100_running": icon_running,
    "s100_install": icon_install,
    "s100_calculator": icon_calculator,
    "s100_stopwatch": icon_stopwatch,
    "s100_notes": icon_notes,
    "s100_certificate": icon_certificate,
    "s100_profile": icon_profile,
    "s100_display": icon_display,
    "s100_clock": icon_clock,
    "s100_shortcuts": icon_shortcuts,
    "s100_info": icon_info,
    "s100_exit": icon_exit,
    "s100_inbox": icon_inbox,
    "s100_compose": icon_compose,
    "s100_network": icon_network,
    "s100_sim": icon_sim,
    "s100_airplane": icon_airplane,
    "s100_vpn": icon_vpn,
    "s100_dns": icon_dns,
    "s100_location": icon_location,
    "s100_security": icon_security,
    "s100_music": icon_music,
}


def write_png(path, rows):
    size = len(rows)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
    raw = b"".join(b"\x00" + bytes(v for p in row for v in p) for row in rows)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    only = sys.argv[1:]          # optional icon names to (re)draw
    for name, fn in ICONS.items():
        if only and name not in only:
            continue
        c = Canvas(SMALL_ICONS.get(name, SIZE))
        fn(c)
        write_png(os.path.join(here, name + ".png"), c.downsample())
        print("wrote", name + ".png")


if __name__ == "__main__":
    main()
