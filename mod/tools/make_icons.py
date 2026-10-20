"""Рисует иконки плеера: assets/syrradio/textures/gui/icons.png.

Полоса 16x16-иконок слева направо: перемешать, назад, играть, пауза, вперёд,
повтор, повтор трека, удалить, включить (маленький «играть» для строки списка).
Белые, на прозрачном фоне — цвет задаётся в игре.

    python3 tools/make_icons.py
"""

from pathlib import Path

from PIL import Image

N = 16
NAMES = ["shuffle", "prev", "play", "pause", "next", "repeat", "repeat_one", "remove", "play_small"]
img = Image.new("RGBA", (N * len(NAMES), N), (0, 0, 0, 0))
WHITE = (255, 255, 255, 255)


def icon(index):
    ox = index * N

    def px(x, y):
        if 0 <= x < N and 0 <= y < N:
            img.putpixel((ox + x, y), WHITE)

    def rect(x0, y0, x1, y1):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                px(x, y)

    def tri(a, b, c):
        def side(p, q, r):
            return (p[0] - r[0]) * (q[1] - r[1]) - (q[0] - r[0]) * (p[1] - r[1])

        for y in range(N):
            for x in range(N):
                p = (x + 0.5, y + 0.5)
                d1, d2, d3 = side(p, a, b), side(p, b, c), side(p, c, a)
                neg = d1 < 0 or d2 < 0 or d3 < 0
                pos = d1 > 0 or d2 > 0 or d3 > 0
                if not (neg and pos):
                    px(x, y)

    def line(x0, y0, x1, y1):
        steps = max(abs(x1 - x0), abs(y1 - y0))
        for i in range(steps + 1):
            px(round(x0 + (x1 - x0) * i / steps), round(y0 + (y1 - y0) * i / steps))

    return px, rect, tri, line


# 0 — перемешать: две скрещённые стрелки
px, rect, tri, line = icon(0)
line(2, 4, 4, 4); line(4, 4, 9, 11); line(9, 11, 12, 11)
line(2, 11, 4, 11); line(4, 11, 9, 4); line(9, 4, 12, 4)
for cy in (4, 11):
    line(11, cy - 2, 13, cy); line(13, cy, 11, cy + 2)
    px(12, cy - 1); px(12, cy + 1)

# 1 — назад: черта и треугольник влево
px, rect, tri, line = icon(1)
rect(3, 3, 4, 12)
tri((12.5, 2.5), (12.5, 13.5), (5, 8))

# 2 — играть
px, rect, tri, line = icon(2)
tri((4, 2), (4, 14), (13, 8))

# 3 — пауза
px, rect, tri, line = icon(3)
rect(4, 3, 6, 12)
rect(9, 3, 11, 12)

# 4 — вперёд
px, rect, tri, line = icon(4)
rect(11, 3, 12, 12)
tri((3.5, 2.5), (3.5, 13.5), (11, 8))


def repeat_loop(index):
    px, rect, tri, line = icon(index)
    line(4, 4, 11, 4)        # верх, стрелка вправо
    line(10, 2, 12, 4); line(12, 4, 10, 6); px(11, 3); px(11, 5)
    line(3, 5, 3, 8)         # левый бок вниз от угла
    line(4, 11, 11, 11)      # низ, стрелка влево
    line(5, 9, 3, 11); line(3, 11, 5, 13); px(4, 10); px(4, 12)
    line(12, 7, 12, 10)      # правый бок
    return px, rect, tri, line


# 5 — повтор очереди
repeat_loop(5)

# 6 — повтор трека: петля и «1» внутри
px, rect, tri, line = repeat_loop(6)
rect(8, 6, 8, 9)
px(7, 7)
rect(7, 9, 9, 9)

# 7 — удалить: крестик
px, rect, tri, line = icon(7)
for d in range(0, 8):
    px(4 + d, 4 + d); px(5 + d, 4 + d)
    px(11 - d, 4 + d); px(12 - d, 4 + d)

# 8 — включить трек из списка: треугольник поменьше
px, rect, tri, line = icon(8)
tri((5, 3), (5, 13), (12, 8))

out = Path(__file__).resolve().parent.parent / "src/main/resources/assets/syrradio/textures/gui/icons.png"
img.save(out)
print(out, img.size)
