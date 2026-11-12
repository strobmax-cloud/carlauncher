# Generates the built-in wallpapers (1280x720 JPEG). Pure numpy/Pillow, no external assets.
import numpy as np
from PIL import Image, ImageFilter
W, H = 1280, 720
rng = np.random.default_rng(7)
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)

def lerp(a, b, t): return a + (b - a) * t
def col(*c): return np.array(c, np.float32) / 255.0
def vgrad(stops):
    t = yy / (H - 1); img = np.zeros((H, W, 3), np.float32)
    for (p0, c0), (p1, c1) in zip(stops[:-1], stops[1:]):
        m = (t >= p0) & (t <= p1); k = ((t - p0) / max(p1 - p0, 1e-6))[..., None]
        img[m] = (lerp(col(*c0), col(*c1), k))[m]
    return img
def blob(cx, cy, r, c, a=1.0):
    d = ((xx - cx) ** 2 + (yy - cy) ** 2) / (r * r)
    return np.exp(-d)[..., None] * col(*c) * a
def ridge_line(base, amp, rough, seed):
    r = np.random.default_rng(seed); n = 2 ** 9 + 1; h = np.zeros(n); step = n - 1; scale = 1.0
    while step > 1:
        half = step // 2
        for i in range(half, n, step): h[i] = (h[i - half] + h[i + half]) / 2 + r.normal() * scale
        step = half; scale *= rough
    h = (h - h.min()) / (np.ptp(h) + 1e-6)
    k = np.ones(9) / 9; h = np.convolve(np.pad(h, 4, mode='edge'), k, mode='valid')
    xs = np.linspace(0, n - 1, W); return base - np.interp(xs, np.arange(n), h) * amp
def layer(img, line, c, haze=None, hz=0):
    mask = (yy >= line[None, :]).astype(np.float32)
    mask = np.array(Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8))) / 255.0
    fill = np.broadcast_to(col(*c), img.shape).copy()
    if haze is not None:  # atmospheric haze fading downward from ridge
        d = np.clip((yy - line[None, :]) / hz, 0, 1)[..., None]
        fill = lerp(col(*haze), col(*c), d)
    return img * (1 - mask[..., None]) + fill * mask[..., None]
def stars(img, n, ymax, bright=1.0):
    for _ in range(n):
        x, y = rng.integers(0, W), rng.integers(0, int(H * ymax)); b = rng.uniform(0.3, 1.0) * bright
        img[y, x] = np.clip(img[y, x] + b, 0, 1)
        if rng.random() < 0.15 and 0 < x < W - 1 and 0 < y < H - 1:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)): img[y + dy, x + dx] = np.clip(img[y + dy, x + dx] + b * 0.35, 0, 1)
    return img
def grain(img, s=0.012): return np.clip(img + rng.normal(0, s, img.shape).astype(np.float32), 0, 1)
def save(img, name, q=88):
    Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).save(name, quality=q, optimize=True, progressive=True)

out = "app/src/main/res/drawable-nodpi/"
# 1. Dusk mountains
img = vgrad([(0, (18, 16, 52)), (0.35, (70, 40, 110)), (0.55, (196, 92, 110)), (0.68, (250, 170, 110)), (1, (250, 190, 130))])
img += blob(820, 470, 180, (255, 200, 140), 0.55) + blob(820, 470, 60, (255, 235, 200), 0.8)
img = stars(img, 160, 0.3, 0.6)
for base, amp, rough, seed, c, hz in [(500, 170, 0.5, 1, (112, 62, 112), (200, 120, 130)), (560, 150, 0.48, 2, (66, 36, 82), (140, 80, 118)),
                                      (630, 130, 0.47, 3, (36, 20, 52), (86, 48, 92)), (700, 100, 0.46, 4, (14, 8, 24), (44, 24, 56))]:
    img = layer(img, ridge_line(base, amp, rough, seed), c, hz and hz, 140)
save(grain(img), out + "wp_dusk.jpg")

# 2. Aurora over a lake
img = vgrad([(0, (4, 8, 22)), (0.6, (8, 24, 44)), (1, (6, 14, 26))])
img = stars(img, 380, 0.6, 0.9)
for k, (c, amp, y0, w, ph) in enumerate([((60, 255, 170), 55, 300, 90, 0.0), ((80, 220, 255), 45, 240, 60, 1.7), ((170, 110, 255), 50, 190, 55, 3.1)]):
    yc = y0 + amp * np.sin(xx / 210 + ph) + 25 * np.sin(xx / 77 + ph * 2)
    band = np.exp(-((yy - yc) / w) ** 2) * np.where(yy < yc, 1.0, np.exp(-((yy - yc) / (w * 0.5)) ** 2))
    curtain = 0.75 + 0.25 * np.sin(xx / 17 + np.sin(xx / 60 + k) * 3) ** 2
    img += (band * curtain)[..., None] * col(*c) * 0.75
horizon = 500
img = layer(img, ridge_line(horizon, 90, 0.5, 9), (6, 12, 18))
refl = img[:horizon][::-1][:H - horizon - 1]
lake = np.array(Image.fromarray((np.clip(refl, 0, 1) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(3))) / 255.0
ripple = 0.55 + 0.1 * np.sin(yy[horizon + 1:horizon + 1 + lake.shape[0]] * 0.9)
img[horizon + 1:horizon + 1 + lake.shape[0]] = lake * ripple[..., None] * 0.95 + col(2, 6, 12) * 0.5
img[horizon - 1:horizon + 2] *= 0.5
save(grain(img), out + "wp_aurora.jpg")

# 3. Deep ocean mesh gradient
img = np.zeros((H, W, 3), np.float32) + col(4, 10, 30)
for cx, cy, r, c, a in [(200, 100, 480, (8, 50, 110), 0.9), (1100, 140, 420, (16, 110, 140), 0.7), (700, 700, 560, (4, 18, 60), 1.0),
                        (980, 520, 260, (30, 170, 170), 0.4), (380, 480, 260, (60, 50, 170), 0.45)]:
    img += blob(cx, cy, r, c, a)
warp = np.sin(xx / 130 + np.sin(yy / 90) * 2) * np.cos(yy / 160)
img *= (0.9 + 0.12 * warp)[..., None] * (0.75 - 0.25 * (yy / H))[..., None]
save(grain(img, 0.01), out + "wp_ocean.jpg")

# 4. City lights bokeh
img = vgrad([(0, (8, 8, 18)), (1, (20, 14, 30))])
bok = np.zeros((H, W, 3), np.float32)
pal = [(255, 170, 70), (255, 120, 60), (90, 160, 255), (255, 220, 150), (200, 90, 200), (120, 220, 255)]
for _ in range(85):
    r = rng.uniform(10, 70); cx = rng.uniform(-50, W + 50); cy = rng.uniform(H * 0.25, H + 40) if rng.random() < 0.8 else rng.uniform(0, H)
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2); disc = np.clip((r - d) / 2.5, 0, 1) * (0.75 + 0.25 * np.clip(d / r, 0, 1))
    bok += disc[..., None] * col(*pal[rng.integers(len(pal))]) * rng.uniform(0.08, 0.32)
bok = np.array(Image.fromarray((np.clip(bok, 0, 1) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(4))) / 255.0
img = 1 - (1 - img) * (1 - bok)
save(grain(img), out + "wp_city.jpg")

# 5. Abstract fluid (domain-warped)
u, v = xx / W, yy / H
q1 = np.sin(u * 5 + np.sin(v * 4) * 1.8); q2 = np.cos(v * 5 + np.sin(u * 3 + 1) * 1.6)
f = np.sin((u + 0.35 * q1) * 6 + (v + 0.35 * q2) * 4)
g = np.cos((v + 0.4 * q1) * 5 - u * 3)
c1, c2, c3, c4 = col(30, 10, 70), col(120, 40, 180), col(240, 80, 150), col(40, 120, 240)
t1 = (f * 0.5 + 0.5)[..., None]; t2 = (g * 0.5 + 0.5)[..., None]
img = lerp(lerp(c1, c2, t1), lerp(c4, c3, t1), t2 ** 2)
img *= (0.55 + 0.45 * (1 - v))[..., None] * 0.95
save(grain(img, 0.012), out + "wp_fluid.jpg")
print("done")

# 6. Synthwave (tasteful): dusk gradient, striped sun, perspective grid
img = vgrad([(0, (14, 6, 34)), (0.45, (70, 18, 92)), (0.62, (190, 50, 120)), (0.64, (40, 8, 52)), (1, (10, 4, 22))])
img = stars(img, 120, 0.4, 0.7)
hz = 460; cx, cy, R = 640, 400, 190
d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
t = np.clip((yy - (cy - R)) / (2 * R), 0, 1)[..., None]
sun = lerp(col(255, 214, 90), col(255, 60, 140), t)
cut = ((yy > cy - 10) & (np.sin((yy - cy) / (4 + (yy - cy) * 0.06) * 3.1416) > 0.55 - (yy - cy) / R * 0.4))
sm = ((d < R) & (yy < hz) & ~cut).astype(np.float32)[..., None]
img = img * (1 - sm) + sun * sm
img += blob(cx, cy, 330, (255, 60, 150), 0.18) * (yy < hz)[..., None]
img = layer(img, ridge_line(hz, 70, 0.5, 21), (30, 8, 46))
img[hz:] = lerp(col(24, 6, 40), col(8, 2, 18), ((yy[hz:] - hz) / (H - hz))[..., None])
gy = yy[hz:] - hz; gx = xx[hz:] - cx
depth = 60.0 / (gy + 6)
hl = np.abs(np.sin(depth * 12)) ; hline = np.clip(1 - hl * (6 + gy * 0.15), 0, 1)
vl = np.abs(np.sin(gx / (gy + 6) * 3.1416 * 2.2)); vline = np.clip(1 - vl * (20 / (1 + gy * 0.02)), 0, 1)
g = np.maximum(hline, vline) * np.clip(gy / 40, 0, 1) * 0.85
img[hz:] += g[..., None] * col(255, 70, 170)
img[hz - 1:hz + 2] = col(255, 120, 200)
save(grain(img), out + "wp_synth.jpg")
print("synth")
