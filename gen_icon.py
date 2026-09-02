# -*- coding: utf-8 -*-
"""Генератор иконки Shigu's Dream (128x128 PNG, без внешних зависимостей)."""
import struct, zlib, math, os

SIZE = 128

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

TOP = (46, 26, 84)      # тёмно-фиолетовый
BOTTOM = (12, 10, 34)   # почти чёрный
MOON = (255, 215, 130)  # золотая луна
STAR = (255, 245, 220)

def pixel(x, y):
    t = y / (SIZE - 1)
    r, g, b = lerp(TOP, BOTTOM, t)
    # Луна: круг (86, 62) r=30 минус круг (70, 52) r=27 -> полумесяц
    d1 = math.hypot(x - 86, y - 62)
    d2 = math.hypot(x - 70, y - 52)
    if d1 <= 30 and d2 > 27:
        edge = 30 - d1
        shade = 1.0 if edge > 2 else 0.82
        return int(MOON[0]*shade), int(MOON[1]*shade), int(MOON[2]*shade)
    # Звёзды: маленькие ромбы
    stars = [(30, 28, 5), (38, 84, 4), (98, 22, 4), (58, 104, 3)]
    for sx, sy, s in stars:
        if abs(x - sx) + abs(y - sy) <= s:
            return STAR
    return r, g, b

rows = []
for y in range(SIZE):
    row = bytearray([0])  # filter type 0
    for x in range(SIZE):
        r, g, b = pixel(x, y)
        row += bytes([r, g, b, 255])
    rows.append(bytes(row))

raw = b"".join(rows)

def chunk(tag, data):
    c = struct.pack(">I", len(data)) + tag + data
    c += struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return c

png = b"\x89PNG\r\n\x1a\n"
png += chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(raw, 9))
png += chunk(b"IEND", b"")

out = os.path.join(os.path.dirname(__file__), "mod", "src", "main", "resources", "assets", "shigusdream", "icon.png")
with open(out, "wb") as f:
    f.write(png)
print("OK:", out, len(png), "bytes")
