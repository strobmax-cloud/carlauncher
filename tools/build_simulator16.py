# Builds CarLauncher-preview.html for v1.6: an offline, clickable simulator made of the REAL app renders
# (Robolectric, 1280×720) — so what you see is exactly what the head unit draws. Run after `gradle testReleaseUnitTest`.
import base64, io, json, os, re
from PIL import Image
P = "preview/"
def enc(name, q=80, maxw=1280):
    f = P + f"robo-{name}.png"
    if not os.path.exists(f): return None
    im = Image.open(f).convert("RGB")
    if im.width > maxw: im = im.resize((maxw, int(im.height * maxw / im.width)), Image.LANCZOS)
    b = io.BytesIO(); im.save(b, "WEBP", quality=q, method=6)
    return "data:image/webp;base64," + base64.b64encode(b.getvalue()).decode()
lk = open("app/src/main/java/com/goga/carlauncher/Layouts.kt").read()
layouts = [dict(id=m[0], name=m[1], desc=m[2], brand=bool(m[3])) for m in re.findall(r'L\("(\w+)", "([^"]+)", "([^"]+)"(, true)?\)', lk)]
DASH = {"cluster", "chrono", "radial", "showcase"}
imgs = {}; variants = {}
for l in layouts:
    i = l["id"]; v = []
    if i in DASH:
        for m, lab in (("night", "Ночь"), ("day", "День")):
            if enc(f"layout-{i}-{m}"): v.append((f"layout-{i}-{m}", lab + " · играет"))
        if i == "radial":
            for acc, lab in (("blue", "Синий"), ("gold", "Золото")): v.append((f"layout-radial-{acc}", "Подсветка: " + lab))
        if i == "chrono": v.append(("layout-chrono-gold-day", "День · золото"))
    else:
        v.append((f"layout-{i}", "Стекло · играет"))
        for f in sorted(os.listdir(P)):
            m = re.match(rf"robo-theme-(\w+)-{i}\.png$", f)
            if m: v.append((f"theme-{m.group(1)}-{i}", "Тема «" + {"aurora": "Сияние", "bento": "Бенто", "synth": "Синтвейв", "city": "Ночной город"}.get(m.group(1), m.group(1)) + "»"))
        v.insert(1, (f"layout-{i}-light", "День · играет"))
    # v1.6.3: road time of day (dark UI + day road), YouTube card, spectrum bars
    for n, lab in ((f"v163-{i}-darkui-dayroad", "Тёмный интерфейс · дорога ДНЁМ"), (f"v163-{i}-darkui-nightroad", "Тёмный интерфейс · дорога НОЧЬЮ"),
                   (f"v163-{i}-lightui-dayroad", "Светлый интерфейс · дорога днём"), (f"v163-bars-{i}", "Столбики под музыку"),
                   (f"v163-yt-{i}", "Справа: YouTube · играет"), (f"v163-yt-{i}-idle", "Справа: YouTube · ничего не играет")):
        v.append((n, lab))
    v.append((f"layout-{i}-idle", "Тишина (ничего не играет)"))
    v = [x for x in v if os.path.exists(P + f"robo-{x[0]}.png")]
    variants[i] = v
    for n, _ in v: imgs[n] = enc(n)
for n in ["apps", "dvr", "media-root", "media-folder", "media-music", "upload", "miniplayer"]:
    e = enc(n)
    if e: imgs[n] = e
imgs["settings-full"] = enc("settings-full", 72, 1168)
def raw(path, maxw=320):
    im = Image.open(path).convert("RGB"); im.thumbnail((maxw, maxw)); b = io.BytesIO(); im.save(b, "WEBP", quality=82)
    return "data:image/webp;base64," + base64.b64encode(b.getvalue()).decode()
imgs["democover"] = raw(P + "sample-cover.png")
if os.path.exists(P + "robo-v163-plate-flat.png"): imgs["plate"] = raw(P + "robo-v163-plate-flat.png", 520)
data = dict(layouts=layouts, variants=variants, imgs=imgs, dash=sorted(DASH | {"grand"}))
html = open("tools/simulator16_template.html").read().replace("/*DATA*/", "const DATA=" + json.dumps(data, ensure_ascii=False) + ";")
open("CarLauncher-preview.html", "w").write(html)
print("size", os.path.getsize("CarLauncher-preview.html"), "images", len(imgs))
