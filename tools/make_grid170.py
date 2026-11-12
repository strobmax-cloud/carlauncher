# features-v170.png (grid of the 1.7.0 features from the real Robolectric renders) + closeup-v170-*.png crops
import os
from PIL import Image, ImageDraw, ImageFont
P = "preview/"
def font(n):
    try: return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", n)
    except Exception: return ImageFont.load_default()
F, FS = font(22), font(16)
ITEMS = [("rail-left", "Меню слева · громкость с обеих сторон"), ("rail-right", "Меню справа"),
         ("dock-bottom", "Меню снизу — док, громкость на обоих концах"), ("phone-bt", "Музыка с телефона по Bluetooth · «Телефон»"), ("radio-card", "Радио: назад/вперёд = станции"),
         ("layout-minimal", "Карточка «Куда едем?» + плитка «Камера»"), ("layout-map", "Один пункт «Навигатор»"),
         ("low-storage-badge", "Значок «Мало места» (< 10 %)"), ("dvr-storage", "Записи: место на накопителях"),
         ("premium-statusbar-680", "Премиум при 680 px: ничего не вылезает"), ("accent-lime", "Цвет акцента: Лайм"),
         ("accent-orange", "Цвет акцента: Оранжевый"), ("accent-purple", "Цвет акцента: Фиолетовый")]
ITEMS = [x for x in ITEMS if os.path.exists(P + f"robo-v170-{x[0]}.png")]
W, H, C, PAD, LAB = 512, 288, 3, 12, 34
rows = (len(ITEMS) + C - 1) // C
g = Image.new("RGB", (C * W + (C + 1) * PAD, 60 + rows * (H + LAB) + (rows + 1) * PAD), (14, 15, 18))
d = ImageDraw.Draw(g); d.text((PAD + 4, 18), "Car Launcher 1.7.0 — что нового (настоящие кадры приложения, 1280×720)", font=F, fill=(235, 236, 240))
for n, (i, name) in enumerate(ITEMS):
    im = Image.open(P + f"robo-v170-{i}.png").convert("RGB"); im = im.resize((W, int(im.height * W / im.width)), Image.LANCZOS).crop((0, 0, W, H))
    x = PAD + (n % C) * (W + PAD); y = 60 + PAD + (n // C) * (H + LAB + PAD)
    d.text((x + 4, y + 6), name, font=FS, fill=(235, 236, 240)); g.paste(im, (x, y + LAB))
g.save("features-v170.png"); print("features-v170.png", g.size)

def crop(src, box, out, scale=1.0):
    f = P + f"robo-v170-{src}.png"
    if not os.path.exists(f): return
    im = Image.open(f).convert("RGB"); sx = im.width / 1280
    im = im.crop(tuple(int(v * sx) for v in box))
    if scale != 1.0: im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
    im.save(f"closeup-v170-{out}.png"); print(out, im.size)
for s in ("rail-left", "phone-bt", "radio-card", "dock-bottom", "rail-right", "layout-minimal", "layout-map", "low-storage-badge", "dvr-storage", "media", "premium-statusbar-680", "settings", "apps"):
    crop(s, (0, 0, 1280, 720), s)
crop("rail-left", (0, 0, 220, 720), "rail-left-strip")
crop("rail-left", (1150, 480, 1280, 720), "volume-passenger", 1.5)
crop("rail-right", (1060, 0, 1280, 720), "rail-right-strip")
crop("dock-bottom", (0, 620, 1280, 720), "dock-strip")
crop("layout-minimal", (880, 0, 1200, 220), "camera-tile", 1.4)
crop("phone-bt", (870, 15, 1190, 380), "phone-card", 1.2)
crop("radio-card", (870, 15, 1190, 380), "radio-card-closeup", 1.2)
crop("low-storage-badge", (340, 0, 940, 70), "badge", 1.6)
# accents strip
acc = [a for a in ("lime", "cyan", "yellow", "orange", "red", "purple") if os.path.exists(P + f"robo-v170-accent-{a}.png")]
if acc:
    ims = [Image.open(P + f"robo-v170-accent-{a}.png").convert("RGB").resize((426, 240), Image.LANCZOS) for a in acc]
    s = Image.new("RGB", (3 * 426 + 4 * 8, 2 * 240 + 3 * 8), (14, 15, 18))
    for k, im in enumerate(ims): s.paste(im, (8 + (k % 3) * 434, 8 + (k // 3) * 248))
    s.save("closeup-v170-accents.png"); print("accents", s.size)
if os.path.exists(P + "robo-v170-storage-settings.png"): Image.open(P + "robo-v170-storage-settings.png").save("closeup-v170-storage-settings.png")
