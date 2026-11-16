"""Mockup line icons (24x24 SVG fragments) -> Android VectorDrawables res/drawable/ck_*.xml (white, tinted in code)."""
import json, re, sys, os
from xml.etree import ElementTree as ET
src = json.load(open(sys.argv[1])); out = sys.argv[2]
def f(v): return float(v)
def el2path(e):
    t = e.tag.split('}')[-1]; a = e.attrib
    if t == 'path': return a['d']
    if t == 'line': return f"M{a['x1']},{a['y1']}L{a['x2']},{a['y2']}"
    if t in ('polyline', 'polygon'):
        p = re.split(r'[\s,]+', a['points'].strip()); pts = [(p[i], p[i+1]) for i in range(0, len(p) - 1, 2)]
        return 'M' + 'L'.join(f'{x},{y}' for x, y in pts) + ('Z' if t == 'polygon' else '')
    if t == 'circle':
        cx, cy, r = f(a['cx']), f(a['cy']), f(a['r'])
        return f"M{cx-r},{cy}a{r},{r} 0 1,0 {2*r},0a{r},{r} 0 1,0 {-2*r},0Z"
    if t == 'ellipse':
        cx, cy, rx, ry = f(a['cx']), f(a['cy']), f(a['rx']), f(a['ry'])
        return f"M{cx-rx},{cy}a{rx},{ry} 0 1,0 {2*rx},0a{rx},{ry} 0 1,0 {-2*rx},0Z"
    if t == 'rect':
        x, y, w, h = f(a.get('x', 0)), f(a.get('y', 0)), f(a['width']), f(a['height']); r = f(a.get('rx', a.get('ry', 0)))
        r = min(r, w / 2, h / 2)
        if r <= 0: return f"M{x},{y}h{w}v{h}h{-w}Z"
        return (f"M{x+r},{y}h{w-2*r}a{r},{r} 0 0 1 {r},{r}v{h-2*r}a{r},{r} 0 0 1 {-r},{r}h{-(w-2*r)}"
                f"a{r},{r} 0 0 1 {-r},{-r}v{-(h-2*r)}a{r},{r} 0 0 1 {r},{-r}Z")
    return None
def vd(frag, name, filled=False, sw=1.8):
    frag = re.sub(r'<svg[^>]*>', '', frag).replace('</svg>', '')
    root = ET.fromstring(f'<g xmlns="http://www.w3.org/2000/svg">{frag}</g>')
    paths = []
    for e in root.iter():
        if e is root or e.tag.split('}')[-1] == 'g': continue
        d = el2path(e)
        if not d: continue
        fill = e.attrib.get('fill'); efill = filled or (fill not in (None, 'none'))
        if e.attrib.get('fill') == 'none': efill = False
        paths.append((d, efill))
    s = ['<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">']
    for d, fl in paths:
        if fl: s.append(f'<path android:fillColor="#FFFFFFFF" android:pathData="{d}"/>')
        else: s.append(f'<path android:strokeColor="#FFFFFFFF" android:strokeWidth="{sw}" android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000" android:pathData="{d}"/>')
    s.append('</vector>')
    open(os.path.join(out, f'ck_{name}.xml'), 'w').write('\n'.join(s))
n = 0
for k, v in src['ID'].items(): vd(v, k); n += 1
for k, v in src['ICC'].items():
    vd(v, 'c_' + k); n += 1
for k, v in src['PB'].items(): vd(v, 'pb_' + k, filled=True); n += 1
print(n)
