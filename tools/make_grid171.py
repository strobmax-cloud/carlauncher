# features-v171.png (grid of the 1.7.1 changes from the real Robolectric renders) + closeup-v171-*.png
import os
from PIL import Image, ImageDraw, ImageFont
P = "preview/"
def font(n):
    try: return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", n)
    except Exception: return ImageFont.load_default()
F, FS = font(22), font(16)
ITEMS = [("floating-volume", "Громкость поверх экрана, без пустой полосы"), ("voltest", "Тест громкости: 8 способов, большие кнопки"),
         ("rail-large", "Меню «Крупный» (+25 %), Назад / Недавние"), ("dock-large", "Док снизу, крупный"),
         ("car-showcase", "SX4 крупнее и чётче, номер читается"), ("car-minimal", "Минимал: большая машина"),
         ("car-cluster", "Приборка: машина крупнее"), ("car-showcase-day", "Дорога днём"),
         ("sharp-cover", "Фон: чёткая обложка"), ("cover-accent", "Цвет интерфейса из обложки"),
         ("cleanup", "Очистка памяти: что можно убрать"), ("rail-xlarge", "Меню «Очень крупный»")]
ITEMS = [x for x in ITEMS if os.path.exists(P + f"robo-v171-{x[0]}.png")]
W, H, C, PAD, LAB = 512, 288, 3, 12, 34
rows = (len(ITEMS) + C - 1) // C
g = Image.new("RGB", (C * W + (C + 1) * PAD, 60 + rows * (H + LAB) + (rows + 1) * PAD), (14, 15, 18))
d = ImageDraw.Draw(g); d.text((PAD + 4, 18), "Car Launcher 1.7.1 — что нового (настоящие кадры приложения, 1280×720)", font=F, fill=(235, 236, 240))
for n, (i, name) in enumerate(ITEMS):
    im = Image.open(P + f"robo-v171-{i}.png").convert("RGB"); im = im.resize((W, int(im.height * W / im.width)), Image.LANCZOS).crop((0, 0, W, H))
    x = PAD + (n % C) * (W + PAD); y = 60 + PAD + (n // C) * (H + LAB + PAD)
    d.text((x + 4, y + 6), name, font=FS, fill=(235, 236, 240)); g.paste(im, (x, y + LAB))
g.save("features-v171.png"); print("features-v171.png", g.size)

def crop(src, box, out, scale=1.0):
    f = P + f"robo-v171-{src}.png"
    if not os.path.exists(f): return
    im = Image.open(f).convert("RGB"); sx = im.width / 1280
    im = im.crop(tuple(int(v * sx) for v in box))
    if scale != 1.0: im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
    im.save(f"closeup-v171-{out}.png"); print(out, im.size)
for s in ("floating-volume", "voltest", "rail-large", "rail-xlarge", "dock-large", "car-showcase", "car-minimal", "car-cluster", "car-smart", "car-showcase-day", "sharp-cover", "blur-cover", "cover-accent", "cleanup"):
    crop(s, (0, 0, 1280, 720), s)
crop("rail-large", (0, 0, 260, 720), "rail-large-strip")
crop("car-showcase", (300, 150, 900, 580), "car-zoom", 1.5)
crop("car-showcase", (470, 400, 720, 470), "plate-zoom", 3.0)
crop("floating-volume", (0, 200, 420, 520), "volume-left", 1.4)
for s in ("quick-panel", "car-closeup", "plate", "cleanup-full", "settings-full"):
    if os.path.exists(P + f"robo-v171-{s}.png"): Image.open(P + f"robo-v171-{s}.png").save(f"closeup-v171-{s}.png"); print(s)
