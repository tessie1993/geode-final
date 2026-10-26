#!/usr/bin/env python3
"""Geode's starter set of MilkDrop textures (#307), made from nothing but this file.

MilkDrop presets ask for textures by name (`sampler_worms`, `sampler_clouds`, ...). Geode ships no
one else's images, so this script draws its own under the names presets ask for most often: noise,
cells, gradients and a few simple pictures, every one computed here from integer hashes, so they are
ours by construction and come out byte-identical on every run. Most tile seamlessly, as MilkDrop
samples with wrapping by default.

    python3 tools/milk_textures/generate.py [output folder]

The output folder defaults to app/src/main/assets/milk/textures, from where MilkStarterPack installs
the textures beside the presets. Pure Python (zlib only); a run takes a minute or two.
"""

import math
import os
import struct
import sys
import zlib

SIZE = 256
TAU = 2.0 * math.pi


# --- Hashes and noise ------------------------------------------------------------------------------


def hash2(x, y, seed):
    """A float in [0, 1) for the lattice point (x, y), from 32-bit integer mixing."""
    h = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    h ^= h >> 16
    return h / 4294967296.0


def smooth(t):
    return t * t * (3.0 - 2.0 * t)


def value_noise(x, y, period, seed):
    """Value noise in [0, 1) that repeats every `period` lattice cells in x and y."""
    x0 = math.floor(x)
    y0 = math.floor(y)
    fx = smooth(x - x0)
    fy = smooth(y - y0)
    xa = x0 % period
    ya = y0 % period
    xb = (x0 + 1) % period
    yb = (y0 + 1) % period
    a = hash2(xa, ya, seed)
    b = hash2(xb, ya, seed)
    c = hash2(xa, yb, seed)
    d = hash2(xb, yb, seed)
    return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy


def fbm(u, v, octaves, base, seed, gain=0.5):
    """Fractal noise over the unit square, tiling at its edges: `base` cells across the first octave."""
    total = 0.0
    amplitude = 1.0
    norm = 0.0
    period = base
    for octave in range(octaves):
        total += amplitude * value_noise(u * period, v * period, period, seed + octave * 101)
        norm += amplitude
        amplitude *= gain
        period *= 2
    return total / norm


def worley(u, v, cells, seed):
    """Distances to the nearest and second-nearest feature points (tiling), and the nearest cell's id."""
    x = u * cells
    y = v * cells
    cx = math.floor(x)
    cy = math.floor(y)
    first = second = 9.0
    nearest = (0, 0)
    for oy in (-1, 0, 1):
        for ox in (-1, 0, 1):
            gx = cx + ox
            gy = cy + oy
            wx = gx % cells
            wy = gy % cells
            px = gx + hash2(wx, wy, seed)
            py = gy + hash2(wx, wy, seed + 17)
            d = math.hypot(px - x, py - y)
            if d < first:
                second = first
                first = d
                nearest = (wx, wy)
            elif d < second:
                second = d
    return first, second, nearest


# --- Colour --------------------------------------------------------------------------------------


def mix(a, b, t):
    return a + (b - a) * t


def clamp(x, lo=0.0, hi=1.0):
    return lo if x < lo else hi if x > hi else x


def ramp(stops, t):
    """The colour at `t` along `stops`, a list of (position, (r, g, b)) in increasing position."""
    t = clamp(t)
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        if t <= p1:
            k = 0.0 if p1 == p0 else (t - p0) / (p1 - p0)
            return tuple(mix(c0[i], c1[i], k) for i in range(3))
    return stops[-1][1]


def hsv(h, s, v):
    h = (h % 1.0) * 6.0
    i = int(h)
    f = h - i
    p = v * (1.0 - s)
    q = v * (1.0 - s * f)
    t = v * (1.0 - s * (1.0 - f))
    return [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)][i % 6]


# --- PNG -----------------------------------------------------------------------------------------


def write_png(path, width, height, pixel):
    """Writes an RGB PNG whose pixel (x, y), y down, is `pixel(x, y)` as three floats in 0..1."""
    rows = []
    previous = bytes(width * 3)
    for y in range(height):
        raw = bytearray()
        for x in range(width):
            r, g, b = pixel(x, y)
            raw += bytes((int(clamp(r) * 255.0 + 0.5), int(clamp(g) * 255.0 + 0.5), int(clamp(b) * 255.0 + 0.5)))
        rows.append(best_filter(bytes(raw), previous))
        previous = bytes(raw)
    data = zlib.compress(b"".join(rows), 9)

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    with open(path, "wb") as out:
        out.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", data) + chunk(b"IEND", b""))


def best_filter(row, previous):
    """The row with the PNG filter (none, sub, up or average) that leaves the smallest residuals."""
    bpp = 3
    candidates = [b"\x00" + row]
    sub = bytearray(row)
    up = bytearray(row)
    avg = bytearray(row)
    for i in range(len(row)):
        left = row[i - bpp] if i >= bpp else 0
        sub[i] = (row[i] - left) & 0xFF
        up[i] = (row[i] - previous[i]) & 0xFF
        avg[i] = (row[i] - ((left + previous[i]) >> 1)) & 0xFF
    candidates += [b"\x01" + bytes(sub), b"\x02" + bytes(up), b"\x03" + bytes(avg)]

    def cost(candidate):
        return sum(b if b < 128 else 256 - b for b in candidate[1:])

    return min(candidates, key=cost)


# --- The textures --------------------------------------------------------------------------------


def worms(u, v):
    # Ridges of warped noise: long, thin, curling bodies.
    wu = u + 0.15 * (fbm(u, v, 3, 3, 11) - 0.5)
    wv = v + 0.15 * (fbm(u, v, 3, 3, 12) - 0.5)
    n = fbm(wu, wv, 4, 5, 13)
    ridge = 1.0 - abs(2.0 * n - 1.0)
    body = clamp((ridge - 0.72) / 0.28) ** 1.5
    return ramp([(0.0, (0.05, 0.02, 0.04)), (0.5, (0.75, 0.32, 0.30)), (1.0, (1.0, 0.82, 0.70))], body)


def clouds(u, v):
    n = fbm(u, v, 6, 4, 21)
    t = clamp((n - 0.35) * 2.2)
    return ramp([(0.0, (0.22, 0.42, 0.78)), (0.55, (0.70, 0.80, 0.94)), (1.0, (1.0, 1.0, 1.0))], t)


def clouds2(u, v):
    # Wisps: noise stretched along x, sharpened.
    n = fbm(u, v * 0.5 + 0.3 * fbm(u, v, 2, 2, 31), 5, 4, 32)
    t = clamp((n - 0.45) * 3.0) ** 1.4
    return ramp([(0.0, (0.03, 0.05, 0.12)), (1.0, (0.92, 0.94, 1.0))], t)


def pic(x, y):
    # A soft picture: sky, a sun, two ranges of hills and a lake.
    u = x / SIZE
    v = y / SIZE
    sky = ramp([(0.0, (0.18, 0.24, 0.55)), (0.55, (0.95, 0.62, 0.45)), (1.0, (1.0, 0.85, 0.6))], v / 0.6)
    sun = math.exp(-((u - 0.68) ** 2 + (v - 0.38) ** 2) / 0.004)
    col = tuple(mix(sky[i], 1.0, sun) for i in range(3))
    far = 0.50 + 0.06 * math.sin(u * 7.0 + 1.0) + 0.04 * (fbm(u, 0.1, 4, 4, 41) - 0.5)
    near = 0.62 + 0.05 * math.sin(u * 4.0 + 2.5) + 0.06 * (fbm(u, 0.7, 4, 4, 42) - 0.5)
    if v > far:
        col = (0.36, 0.30, 0.48)
    if v > near:
        shade = 0.6 + 0.4 * fbm(u, v, 4, 8, 43)
        col = (0.18 * shade, 0.36 * shade, 0.20 * shade)
    if v > 0.82:
        ripple = 0.5 + 0.5 * math.sin(v * 140.0 + 3.0 * fbm(u, v, 2, 4, 44))
        col = (mix(0.20, 0.55, ripple * 0.4), mix(0.30, 0.55, ripple * 0.4), mix(0.50, 0.70, ripple * 0.4))
    return col


FISH = [
    (hash2(i, 0, 51), hash2(i, 1, 51), hash2(i, 2, 51) * TAU, 0.025 + 0.025 * hash2(i, 3, 51), hash2(i, 4, 51)) for i in range(70)
]


def manyfish(u, v):
    t = clamp(v)
    col = ramp([(0.0, (0.05, 0.30, 0.45)), (1.0, (0.01, 0.08, 0.18))], t)
    for fx, fy, angle, size, hue in FISH:
        dx = (u - fx + 0.5) % 1.0 - 0.5
        dy = (v - fy + 0.5) % 1.0 - 0.5
        ca = math.cos(angle)
        sa = math.sin(angle)
        px = (dx * ca + dy * sa) / size
        py = (-dx * sa + dy * ca) / size
        body = px * px / 1.0 + py * py / 0.30
        tail = -1.6 < px < -0.8 and abs(py) < (-0.8 - px) * 0.8
        if body < 1.0 or tail:
            r, g, b = hsv(0.05 + 0.15 * hue, 0.75, 0.95)
            shade = 0.7 + 0.3 * clamp(0.5 - py)
            col = (r * shade, g * shade, b * shade)
            if body < 1.0 and (px - 0.55) ** 2 + (py + 0.05) ** 2 < 0.012:
                col = (0.02, 0.02, 0.02)
    return col


def sunrise(x, y):
    u = x / SIZE
    v = y / SIZE
    horizon = 0.62
    if v < horizon:
        col = ramp([(0.0, (0.06, 0.07, 0.25)), (0.6, (0.75, 0.30, 0.35)), (1.0, (1.0, 0.70, 0.30))], v / horizon)
        d = math.hypot(u - 0.5, (v - horizon) * 1.2)
        glow = math.exp(-d * d / 0.02)
        disc = 1.0 if d < 0.09 else 0.0
        col = tuple(mix(col[i], (1.0, 0.95, 0.75)[i], max(glow * 0.6, disc)) for i in range(3))
    else:
        depth = (v - horizon) / (1.0 - horizon)
        col = ramp([(0.0, (0.80, 0.45, 0.30)), (1.0, (0.05, 0.08, 0.22))], depth)
        streak = math.exp(-((u - 0.5) ** 2) / (0.002 + 0.02 * depth)) * (0.5 + 0.5 * math.sin(v * 180.0))
        col = tuple(mix(col[i], (1.0, 0.85, 0.5)[i], streak * (1.0 - depth) * 0.7) for i in range(3))
    return col


def cells(u, v):
    f1, f2, cell = worley(u, v, 8, 61)
    edge = clamp((f2 - f1) * 6.0)
    r, g, b = hsv(hash2(cell[0], cell[1], 62), 0.55, 0.55 + 0.35 * hash2(cell[0], cell[1], 63))
    shade = 0.55 + 0.45 * (1.0 - f1)
    return (r * shade * edge, g * shade * edge, b * shade * edge)


def seaweed(u, v):
    col = ramp([(0.0, (0.02, 0.18, 0.20)), (1.0, (0.01, 0.06, 0.08))], 1.0 - v)
    for i in range(14):
        base = (i + hash2(i, 0, 71)) / 14.0
        sway = 0.03 * math.sin(v * TAU * 2.0 + i * 1.7) + 0.02 * math.sin(v * TAU * 5.0 + i)
        width = 0.012 + 0.012 * hash2(i, 1, 71)
        dx = (u - base - sway + 0.5) % 1.0 - 0.5
        if abs(dx) < width:
            k = 1.0 - abs(dx) / width
            green = 0.35 + 0.45 * hash2(i, 2, 71)
            col = (0.10 * k + col[0] * (1 - k), green * k + col[1] * (1 - k), 0.18 * k + col[2] * (1 - k))
    return col


def lichen(u, v):
    n = fbm(u, v, 5, 6, 81)
    f1, _, _ = worley(u, v, 24, 82)
    crust = clamp((n - 0.48) * 6.0)
    speck = clamp(1.0 - f1 * 2.5)
    stone = 0.30 + 0.25 * fbm(u, v, 4, 16, 83)
    lichen_colour = ramp([(0.0, (0.55, 0.62, 0.30)), (1.0, (0.85, 0.80, 0.45))], speck)
    return tuple(mix(stone, lichen_colour[i], crust) for i in range(3))


def rose(x, y):
    u = (x + 0.5) / SIZE - 0.5
    v = (y + 0.5) / SIZE - 0.5
    r = math.hypot(u, v) * 2.4
    a = math.atan2(v, u)
    petals = abs(math.cos(2.5 * a + 0.4 * r))
    inner = abs(math.cos(4.0 * a + 1.0))
    shape = max(petals * (1.0 - r * 0.9), inner * (0.5 - r) * 1.6)
    t = clamp(shape * 1.8)
    col = ramp([(0.0, (0.04, 0.01, 0.03)), (0.4, (0.45, 0.02, 0.10)), (0.8, (0.90, 0.15, 0.25)), (1.0, (1.0, 0.65, 0.70))], t)
    return col


def grad3(x, y):
    return ramp([(0.0, (0.10, 0.20, 0.90)), (0.5, (0.85, 0.15, 0.70)), (1.0, (1.0, 0.70, 0.15))], x / (SIZE - 1))


def grad16(x, y):
    band = int(x * 16 / SIZE)
    return hsv(band / 16.0, 0.8, 0.95)


def heart(x, y):
    u = ((x + 0.5) / SIZE - 0.5) * 2.6
    v = (0.5 - (y + 0.5) / SIZE) * 2.6 + 0.25
    f = (u * u + v * v - 1.0) ** 3 - u * u * v ** 3
    inside = f < 0.0
    glow = math.exp(-max(f, 0.0) * 8.0)
    base = (0.10, 0.02, 0.06)
    if inside:
        shade = clamp(0.75 + 0.35 * v)
        return (0.95 * shade, 0.12 * shade, 0.25 * shade)
    return tuple(base[i] + (0.6, 0.05, 0.15)[i] * glow * 0.6 for i in range(3))


def test(x, y):
    # A test card: colour bars, a grey ramp, a grid and a circle.
    u = x / SIZE
    v = y / SIZE
    bars = [(0.75, 0.75, 0.75), (0.75, 0.75, 0.0), (0.0, 0.75, 0.75), (0.0, 0.75, 0.0), (0.75, 0.0, 0.75), (0.75, 0.0, 0.0), (0.0, 0.0, 0.75)]
    if v < 0.6:
        col = bars[min(int(u * 7), 6)]
    elif v < 0.75:
        col = (u, u, u)
    else:
        col = (0.08, 0.08, 0.10)
    if x % 32 == 0 or y % 32 == 0:
        col = (1.0, 1.0, 1.0)
    if abs(math.hypot(u - 0.5, v - 0.5) - 0.35) < 0.006:
        col = (1.0, 1.0, 1.0)
    return col


def moss1(u, v):
    n = fbm(u, v, 6, 16, 91, gain=0.6)
    f1, _, _ = worley(u, v, 32, 92)
    t = clamp(n * 1.2 - 0.1) * (0.7 + 0.3 * clamp(1.0 - f1))
    return ramp([(0.0, (0.05, 0.10, 0.03)), (0.5, (0.22, 0.42, 0.10)), (1.0, (0.62, 0.78, 0.28))], t)


def fire_base(u, v):
    n = fbm(u, v * 0.7, 5, 4, 101)
    heat = clamp(n * 1.3 - 0.15) * (0.35 + 0.65 * v)
    return ramp([(0.0, (0.0, 0.0, 0.0)), (0.35, (0.55, 0.05, 0.0)), (0.65, (1.0, 0.45, 0.05)), (1.0, (1.0, 0.95, 0.6))], heat)


def pano_starsmap(x, y):
    u = x / SIZE
    v = y / SIZE
    band = math.exp(-((v - 0.5 - 0.12 * math.sin(u * TAU)) ** 2) / 0.01) * fbm(u, v, 5, 8, 111)
    col = (0.02 + 0.10 * band, 0.02 + 0.08 * band, 0.05 + 0.14 * band)
    h = hash2(x, y, 112)
    if h > 0.992:
        b = (h - 0.992) / 0.008
        tint = hash2(x, y, 113)
        col = (mix(0.7, 1.0, b), mix(0.7, 0.95, b * tint), mix(0.8, 1.0, b * (1.0 - tint)))
    return col


def pano_earth(u, v):
    n = fbm(u, v, 6, 3, 121)
    lat = abs(v - 0.5) * 2.0
    if lat > 0.86 - 0.08 * fbm(u, v, 3, 6, 122):
        return (0.92, 0.95, 0.98)
    if n < 0.52:
        depth = clamp((0.52 - n) * 5.0)
        return ramp([(0.0, (0.10, 0.35, 0.60)), (1.0, (0.02, 0.10, 0.30))], depth)
    height = clamp((n - 0.52) * 4.0)
    dry = clamp(1.0 - lat * 1.6) * fbm(u, v, 3, 5, 123)
    green = ramp([(0.0, (0.20, 0.45, 0.15)), (1.0, (0.45, 0.40, 0.25))], height)
    return tuple(mix(green[i], (0.75, 0.62, 0.38)[i], clamp(dry * 1.4 - 0.3)) for i in range(3))


def pano_earth_clouds(u, v):
    n = fbm(u + 0.2 * fbm(u, v, 2, 2, 131), v, 6, 4, 132)
    t = clamp((n - 0.5) * 3.5)
    return (t, t, t)


def pattern_002(x, y):
    # Diamonds with a ring in each, two colours.
    cu = (x % 32) / 32.0 - 0.5
    cv = (y % 32) / 32.0 - 0.5
    diamond = abs(cu) + abs(cv) < 0.5
    odd = ((x // 32) + (y // 32)) % 2 == 1
    base = (0.10, 0.28, 0.50) if diamond != odd else (0.95, 0.75, 0.25)
    if abs(math.hypot(cu, cv) - 0.25) < 0.035:
        base = (0.92, 0.92, 0.88)
    return base


def smalltiled_lizard_scales(x, y):
    # Overlapping round scales in offset rows, shaded towards their lower edge.
    s = 16.0
    row = math.floor(y / (s * 0.75))
    best = None
    for r in (row - 1, row, row + 1):
        oy = r * s * 0.75
        ox = 0.5 * s if r % 2 else 0.0
        cx = math.floor((x - ox) / s) * s + ox + s * 0.5
        for c in (cx - s, cx, cx + s):
            d = math.hypot(x - c, y - oy - s * 0.5) / (s * 0.62)
            if d < 1.0 and (best is None or oy > best[1]):
                best = (d, oy, c, r)
    if best is None:
        return (0.05, 0.08, 0.04)
    d, _, c, r = best
    tone = hash2(int(c), r, 141)
    shade = (1.0 - d * d) * 0.8 + 0.2
    base = ramp([(0.0, (0.20, 0.38, 0.12)), (1.0, (0.55, 0.62, 0.22))], tone)
    return (base[0] * shade, base[1] * shade, base[2] * shade)


def smalltiled_electric_nebula(u, v):
    n = fbm(u, v, 5, 4, 151)
    ridge = (1.0 - abs(2.0 * fbm(u + 0.3 * n, v, 5, 6, 152) - 1.0)) ** 6
    col = ramp([(0.0, (0.02, 0.0, 0.06)), (0.5, (0.25, 0.05, 0.45)), (1.0, (0.45, 0.30, 0.95))], n)
    return tuple(col[i] + (0.6, 0.85, 1.0)[i] * ridge for i in range(3))


def ruin(x, y):
    # Weathered stone blocks with dark mortar.
    bw, bh = 48, 24
    row = y // bh
    ox = (bw // 2) if row % 2 else 0
    bx = (x + ox) // bw
    lx = (x + ox) % bw
    ly = y % bh
    u = x / SIZE
    v = y / SIZE
    mortar = lx < 2 or ly < 2
    tone = 0.45 + 0.20 * hash2(bx % (SIZE // bw + 1), row, 161)
    grit = fbm(u, v, 5, 16, 162)
    moss = clamp((fbm(u, v, 4, 4, 163) - 0.58) * 4.0)
    if mortar:
        return (0.12, 0.11, 0.10)
    stone = tone * (0.75 + 0.5 * (grit - 0.5))
    return (mix(stone, 0.25, moss), mix(stone * 0.96, 0.38, moss), mix(stone * 0.9, 0.15, moss))


def facade01(x, y):
    # A grid of windows in a concrete wall; some lit, some dark.
    cw, ch = 32, 32
    cx, cy = x // cw, y // ch
    lx, ly = x % cw, y % ch
    wall = 0.55 + 0.1 * (fbm(x / SIZE, y / SIZE, 4, 16, 171) - 0.5)
    if 6 <= lx < 26 and 5 <= ly < 25:
        lit = hash2(cx, cy, 172) > 0.55
        if lx in (15, 16) or ly in (14, 15):
            return (0.2, 0.2, 0.22)
        return (0.95, 0.82, 0.50) if lit else (0.10, 0.14, 0.20)
    return (wall, wall * 0.98, wall * 0.95)


def by_uv(f):
    """A texture drawn in unit coordinates, sampled at pixel centres."""
    return lambda x, y: f((x + 0.5) / SIZE, (y + 0.5) / SIZE)


TEXTURES = {
    "worms": by_uv(worms),
    "clouds": by_uv(clouds),
    "pic": pic,
    "manyfish": by_uv(manyfish),
    "sunrise": sunrise,
    "cells": by_uv(cells),
    "seaweed": by_uv(seaweed),
    "lichen": by_uv(lichen),
    "rose": rose,
    "grad3": grad3,
    "grad16": grad16,
    "heart": heart,
    "test": test,
    "moss1": by_uv(moss1),
    "clouds2": by_uv(clouds2),
    "fire_base": by_uv(fire_base),
    "pano_starsmap": pano_starsmap,
    "pano_earth": by_uv(pano_earth),
    "pano_earth_clouds": by_uv(pano_earth_clouds),
    "pattern_002": pattern_002,
    "smalltiled_lizard_scales": smalltiled_lizard_scales,
    "smalltiled_electric_nebula": by_uv(smalltiled_electric_nebula),
    "ruin": ruin,
    "facade01": facade01,
}


def main():
    root = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "app", "src", "main", "assets", "milk", "textures")
    os.makedirs(out, exist_ok=True)
    for name, pixel in TEXTURES.items():
        path = os.path.join(out, name + ".png")
        write_png(path, SIZE, SIZE, pixel)
        print(f"{path}: {os.path.getsize(path)} bytes")


if __name__ == "__main__":
    main()
