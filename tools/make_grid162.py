# layouts-grid-v162(.png | -light.png): every layout, dark and day look, from the real Robolectric renders
import re
from PIL import Image, ImageDraw, ImageFont
lk = open("app/src/main/java/com/goga/carlauncher/Layouts.kt").read()
L = re.findall(r'L\("(\w+)", "([^"]+)"', lk)
DASH = {"cluster", "chrono", "radial", "showcase"}
try: font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 22)
except Exception: font = ImageFont.load_default()
W, H, C, PAD, LAB = 512, 288, 3, 12, 34
for mode in ("dark", "light"):
    rows = (len(L) + C - 1) // C
    g = Image.new("RGB", (C * W + (C + 1) * PAD, rows * (H + LAB) + (rows + 1) * PAD), (14, 15, 18) if mode == "dark" else (226, 229, 234))
    d = ImageDraw.Draw(g)
    for n, (i, name) in enumerate(L):
        f = f"preview/robo-layout-{i}" + (("-night" if i in DASH else "") if mode == "dark" else ("-day" if i in DASH else "-light")) + ".png"
        im = Image.open(f).convert("RGB").resize((W, H), Image.LANCZOS)
        x = PAD + (n % C) * (W + PAD); y = PAD + (n // C) * (H + LAB + PAD)
        d.text((x + 4, y + 4), name, font=font, fill=(235, 236, 240) if mode == "dark" else (20, 22, 26))
        g.paste(im, (x, y + LAB))
    out = "layouts-grid-v162.png" if mode == "dark" else "layouts-grid-v162-light.png"
    g.save(out); print(out, g.size)
