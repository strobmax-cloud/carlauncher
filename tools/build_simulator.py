# Builds CarLauncher-preview.html: a single offline file (fonts, wallpapers, covers, icons inlined).
# Theme values mirror app/src/main/java/com/goga/carlauncher/Theme.kt.
import base64, json, re, glob, os
R = "app/src/main/res/"
def b64(path, mime): return f"data:{mime};base64," + base64.b64encode(open(path, "rb").read()).decode()
icons = json.load(open("tools/icons.json"))
line = {}
for f in glob.glob(R + "drawable/ic_*.xml"):
    s = open(f).read(); n = os.path.basename(f)[:-4]
    line[n] = {"d": re.search(r'pathData="([^"]+)"', s).group(1), "stroke": "strokeColor" in s}
WALL = [("dusk", "Сумерки"), ("aurora", "Сияние"), ("fluid", "Флюид"), ("synth", "Синтвейв"), ("city", "Город"), ("ocean", "Океан")]
walls = {k: b64(R + f"drawable-nodpi/wp_{k}.jpg", "image/jpeg") for k, _ in WALL}
covers = [b64(f"tools/assets/cover{i}.jpg", "image/jpeg") for i in (1, 2, 3)]
fonts = {"roboto": b64("tools/assets/roboto.woff", "font/woff"), "cond": b64("tools/assets/roboto-cond.woff", "font/woff")}
def rgba(argb):
    a = (argb >> 24) & 255; r = (argb >> 16) & 255; g = (argb >> 8) & 255; b = argb & 255
    return f"rgba({r},{g},{b},{round(a/255, 3)})"
# id, name, wallpaper, accent, glass, tint, border, radius, clockFont, clockSize, clockFg, clockBg, dateFg, speedBg, speedFg, tileBlocks, bento, wallDim
T = [
 ("glass", "Стекло", "dusk", 0xFF0A84FF, True, 0x6B141418, 0x1AFFFFFF, 24, "light", 120, 0xFFFFFFFF, 0, 0xFFD1D1D6, 0, 0xFFFFFFFF, False, False, 0x14000000),
 ("aurora", "Сияние", "aurora", 0xFF30D5C8, True, 0x70061418, 0x2478FFDC, 24, "thin", 124, 0xFFFFFFFF, 0, 0xFF9FF5E8, 0, 0xFFFFFFFF, False, False, 0x00000000),
 ("bento", "Бенто", "fluid", 0xFFFF9F0A, False, 0xF0141416, 0, 28, "condensed", 116, 0xFF000000, 0xFFFF9F0A, 0xCC000000, 0xFFD4F84A, 0xFF000000, True, True, 0x1F000000),
 ("synth", "Синтвейв", "synth", 0xFFFF3EA5, True, 0x7A1E0830, 0x59FF40AA, 14, "condensed", 116, 0xFFFFFFFF, 0, 0xFFFF8AD0, 0, 0xFFFFFFFF, False, False, 0x1A000000),
 ("city", "Ночной город", "city", 0xFFFFB340, True, 0x8C000000, 0, 22, "light", 120, 0xFFFFFFFF, 0, 0xFFFFD08A, 0, 0xFFFFFFFF, False, False, 0x26000000),
]
themes = []
for t in T:
    themes.append(dict(id=t[0], name=t[1], wall=t[2], accent=rgba(t[3]), glass=t[4], tint=rgba(t[5]), border=rgba(t[6]) if t[6] else "transparent",
        radius=t[7], font=t[8], clockSize=t[9], clockFg=rgba(t[10]), clockBg=rgba(t[11]) if t[11] else "", dateFg=rgba(t[12]),
        speedBg=rgba(t[13]) if t[13] else "", speedFg=rgba(t[14]), blocks=t[15], bento=t[16], dim=rgba(t[17])))
kt = open("app/src/main/java/com/goga/carlauncher/BrandWidgets.kt").read()
shapes = dict(re.findall(r'const val (\w+) = "([^"]+)"', kt))   # car / ambient paths shared with BrandWidgets.kt
data = dict(map=b64("tools/assets/map_preview.jpg", "image/jpeg"), mapDay=b64("tools/assets/map_preview_day.jpg", "image/jpeg"),
            shapes=shapes, carbon=b64("app/src/main/res/drawable-nodpi/carbon.png", "image/png"), themes=themes, walls=walls, wallNames=WALL, covers=covers, icons=icons, line=line)
html = open("tools/simulator_template.html").read()
html = html.replace("/*FONT_ROBOTO*/", fonts["roboto"]).replace("/*FONT_COND*/", fonts["cond"])
html = html.replace("/*DATA*/", "const DATA = " + json.dumps(data, ensure_ascii=False) + ";")
open("CarLauncher-preview.html", "w").write(html)
print("size", os.path.getsize("CarLauncher-preview.html"))
