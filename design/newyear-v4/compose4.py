exec(open('/workspace/ny-assets/prep4.py').read())
A=grade(clean(load('fir-2740412.png')))
B=grade(clean(load('branch-2993101.png')))
D=grade(clean(load('baubles-1007754.png')), sat=.7, val=.85)
def canvas(w,h): return np.zeros((h,w,4),np.float32)
def place(dst, src, cx, cy, **kw):
    h,w=src.shape[:2]; over(dst, src, int(cx-w/2), int(cy-h/2), **kw)


OX=140; OY=140
tl=canvas(560+OX,260+OY)
def P(img,x,y,**kw): place(tl, img, x+OX, y+OY, **kw)
P(rotscale(B,.32,-58), 70,40, shadow=.35, darken=.55)
P(rotscale(B,.26,-8), 210,0, shadow=.35, darken=.62)
P(rotscale(B,.34,-96), 10,70, shadow=.45, darken=.78)
P(rotscale(B,.33,-30), 130,22, shadow=.5, darken=.92)
to_img(tl[OY:, OX:]).save('v4/ny4_tl.png')
# bottom spray lying in the drift, centred on x≈1010 (between the tabs and the volume buttons)
BX=820; BY=520
br=canvas(400,260)
def Q(img, x, y, **kw): place(br, img, x-BX, y-BY, **kw)
Q(rotscale(B,.21,150,flip=False), 960,690, shadow=.35, darken=.6)
Q(rotscale(B,.21,-200,flip=True), 1060,690, shadow=.35, darken=.62)
Q(rotscale(B,.23,170,flip=True), 1036,700, shadow=.45, darken=.9)
br_min=br.copy()
Q(rotscale(D,.15,2), 1006,700, shadow=.45)
to_img(br).save('v4/ny4_br.png'); to_img(br_min).save('v4/ny4_br_min.png')
from PIL import Image
sheet=Image.new('RGB',(1280,720),(20,22,34))
for f,xy in [('v4/ny4_tl.png',(0,0)),('v4/ny4_br.png',(BX,BY))]:
    im=Image.open(f); sheet.paste(im,xy,im)
sheet.save('/tmp/ny/clusters5.png')
