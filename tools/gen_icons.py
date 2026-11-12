# Generates illustrated tile icons (iOS-style rounded squares with gradients) as Android vector drawables
# and the same shapes as JSON for the HTML simulator, so both use identical artwork.
import json
G = {  # Material filled glyphs, 24x24
 "nav": "M12,2L4.5,20.29l0.71,0.71L12,18l6.79,3 0.71,-0.71z",
 "radio": "M3.24,6.15C2.51,6.43 2,7.17 2,8v12c0,1.1 0.89,2 2,2h16c1.11,0 2,-0.9 2,-2V8c0,-1.11 -0.89,-2 -2,-2H8.3l8.26,-3.34L15.88,1 3.24,6.15zM7,20c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM20,12h-2v-2h-2v2H4V8h16v4z",
 "phone": "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z",
 "camera": "M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11l-4,4z",
 "music": "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z",
 "video": "M21,3H3C1.9,3 1,3.9 1,5v14c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V5C23,3.9 22.1,3 21,3zM9,16V8l7,4L9,16z",
 "headphones": "M12,1c-4.97,0 -9,4.03 -9,9v7c0,1.66 1.34,3 3,3h3v-8H5v-2c0,-3.87 3.13,-7 7,-7s7,3.13 7,7v2h-4v8h3c1.66,0 3,-1.34 3,-3v-7c0,-4.97 -4.03,-9 -9,-9z",
 "folder": "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z",
}
ICONS = {  # name: (gradient top, gradient bottom, glyph, extra)
 "tile_nav": ("#3FD0FF", "#0A5CFF", "nav", None),
 "tile_radio": ("#FFC24A", "#FF6A00", "radio", None),
 "tile_phone": ("#63F08E", "#14A944", "phone", None),
 "tile_camera": ("#55555B", "#1A1A1D", "camera", "rec"),
 "tile_music": ("#FF7A93", "#FF1F4F", "music", None),
 "tile_video": ("#FF5A4E", "#C8001C", "video", None),
 "tile_headphones": ("#FB6BD0", "#9B1FE8", "headphones", None),
 "tile_files": ("#6FD8FF", "#0A6CFF", "folder", None),
}
SQ = "M12,0H36A12,12 0,0 1,48,12V36A12,12 0,0 1,36,48H12A12,12 0,0 1,0,36V12A12,12 0,0 1,12,0Z"
GLOSS = "M12,0H36A12,12 0,0 1,48,12V20C40,23 8,23 0,20V12A12,12 0,0 1,12,0Z"
out = {}
for name, (c1, c2, g, extra) in ICONS.items():
    rec = '<path android:fillColor="#FF3B30" android:pathData="M33,15.5m-2.6,0a2.6,2.6 0,1 1,5.2 0a2.6,2.6 0,1 1,-5.2 0"/>' if extra == "rec" else ""
    xml = f'''<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="48dp" android:height="48dp" android:viewportWidth="48" android:viewportHeight="48">
  <path android:pathData="{SQ}">
    <aapt:attr name="android:fillColor">
      <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="0" android:endY="48"
          android:startColor="{c1}" android:endColor="{c2}"/>
    </aapt:attr>
  </path>
  <path android:fillColor="#14FFFFFF" android:pathData="{GLOSS}"/>
  <group android:translateX="10.8" android:translateY="10.8" android:scaleX="1.1" android:scaleY="1.1">
    <path android:fillColor="#FFFFFFFF" android:pathData="{G[g]}"/>
  </group>
  {rec}
</vector>
'''
    open(f"app/src/main/res/drawable/{name}.xml", "w").write(xml)
    out[name] = {"c1": c1, "c2": c2, "glyph": G[g], "rec": extra == "rec"}
json.dump({"icons": out, "sq": SQ, "gloss": GLOSS}, open("tools/icons.json", "w"), ensure_ascii=False)
print(len(out))
