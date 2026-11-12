# v1.7.0 (copy of build_simulator16.py): + «Новое в 1.7.0» gallery, v1.7.0 screens. Builds CarLauncher-preview.html: an offline, clickable simulator made of the REAL app renders
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
                   ):
        v.append((n, lab))
    v.append((f"layout-{i}-idle", "Тишина (ничего не играет)"))
    v.insert(0, (f"v170-layout-{i}", "1.7.0 · меню слева, громкость с двух сторон"))
    v = [x for x in v if os.path.exists(P + f"robo-{x[0]}.png")]
    variants[i] = v
    for n, _ in v: imgs[n] = enc(n)
for n in ["apps", "dvr", "media-root", "media-folder", "media-music", "upload", "miniplayer"]:
    e = enc({"apps": "v170-apps", "dvr": "v170-dvr", "media-root": "v170-media"}.get(n, n)) or enc(n)
    if e: imgs[n] = e
imgs["settings-full"] = enc("v170-settings-full", 72, 1168) or enc("settings-full", 72, 1168)
NEWS = [("v170-rail-left", "Меню слева + громкость с 2 сторон", "По умолчанию: меню слева (листается), внизу меню — Громче/Тише для водителя, справа — ещё пара для пассажира. Кнопки закреплены и никогда не уезжают за экран."),
        ("v170-rail-right", "Меню справа", "Настройки → «Боковое меню и громкость» → Где меню: Справа."),
        ("v170-dock-bottom", "Меню снизу (док)", "Где меню: Снизу — док как на Mac, листается вбок, громкость закреплена на обоих концах."),
        ("v170-phone-bt", "Музыка с телефона (BT)", "Телефон играет по Bluetooth — карточка «Телефон», кнопки ⏮ ⏯ ⏭ управляют телефоном (если магнитола передаёт данные AVRCP)."),
        ("v170-radio-card", "Радио магнитолы — источник", "Запустили радио из лаунчера — карточка «РАДИО», ⏮ ⏭ переключают станции (сервис радио FYT), ⏯ открывает радио. Я.Музыка на паузе больше не перехватывает кнопки."),
        ("v170-layout-minimal", "Карточка навигатора + плитка «Камера»", "Вместо фальшивой карты — чистая карточка «Куда едем?». Плитка «Камера» открывает Cam REC / камеру заднего вида."),
        ("v170-layout-map", "Навигатор (Карта)", "Один пункт «Навигатор» — ваш навигатор по умолчанию, музыка продолжается сама."),
        ("v170-premium-statusbar-680", "Премиум при 680 px", "Если штатная строка состояния съедает высоту — экран сам масштабируется, список справа листается."),
        ("v170-low-storage-badge", "«Мало места»", "Красный значок на главном, если где-то свободно меньше 10 %. Нажатие — экран «Записи»."),
        ("v170-dvr", "Записи + место на флешке", "Полоски занятого места, «Открыть регистратор», «Папка…» для своей папки."),
        ("v170-media", "Флешка + место", "Свободно / всего для памяти и каждой флешки/SD."),
        ("v170-settings-full", "Настройки (вся страница)", "Новые разделы: «Боковое меню и громкость», «Цвет акцента», «Камера и регистратор», хранилище, GitHub."),
       ] + [(f"v170-accent-{a}", "Акцент: " + l, "Настройки → «Цвет акцента».") for a, l in (("lime", "Лайм"), ("cyan", "Бирюза"), ("orange", "Оранжевый"), ("red", "Красный"), ("purple", "Фиолетовый"), ("yellow", "Жёлтый"))]
NEWS = [list(x) for x in NEWS if os.path.exists(P + f"robo-{x[0]}.png")]
for n, _, _ in NEWS: imgs[n] = enc(n, 72, 1168) if n.endswith("settings-full") else enc(n)
def raw(path, maxw=320):
    im = Image.open(path).convert("RGB"); im.thumbnail((maxw, maxw)); b = io.BytesIO(); im.save(b, "WEBP", quality=82)
    return "data:image/webp;base64," + base64.b64encode(b.getvalue()).decode()
imgs["democover"] = raw(P + "sample-cover.png")
if os.path.exists(P + "robo-v163-plate-flat.png"): imgs["plate"] = raw(P + "robo-v163-plate-flat.png", 520)
data = dict(news=NEWS, layouts=layouts, variants=variants, imgs=imgs, dash=sorted(DASH | {"grand"}))
html = open("tools/simulator170_template.html").read().replace("/*DATA*/", "const DATA=" + json.dumps(data, ensure_ascii=False) + ";")
open("CarLauncher-preview.html", "w").write(html)
print("size", os.path.getsize("CarLauncher-preview.html"), "images", len(imgs))
