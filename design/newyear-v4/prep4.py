import numpy as np
from PIL import Image, ImageFilter
import colorsys, sys
S='/workspace/ny-assets/'
def load(n): return np.asarray(Image.open(S+n).convert('RGBA')).astype(np.float32)/255.
def to_img(a): return Image.fromarray((np.clip(a,0,1)*255+.5).astype(np.uint8),'RGBA')
def gblur(ch, r):
    im=Image.fromarray((np.clip(ch,0,1)*65535).astype(np.uint16) if False else (np.clip(ch,0,1)*255).astype(np.uint8),'L')
    return np.asarray(im.filter(ImageFilter.GaussianBlur(r))).astype(np.float32)/255.
def clean(a, lo=.22, erode=1):
    al=a[...,3]
    al=np.clip((al-lo)/(1-lo),0,1)
    # erode fringe
    im=Image.fromarray((al*255).astype(np.uint8),'L')
    for _ in range(erode): im=im.filter(ImageFilter.MinFilter(3))
    al2=np.asarray(im).astype(np.float32)/255.
    al2=np.minimum(al2, al)
    # colour decontamination: colours of edge pixels from the solid interior
    solid=(a[...,3]>.92).astype(np.float32)
    rgb=a[...,:3]
    num=np.stack([gblur(rgb[...,i]*solid,3) for i in range(3)],-1); den=gblur(solid,3)[...,None]
    inner=np.where(den>.02, num/np.maximum(den,1e-3), rgb)
    w=np.clip((al2-.0)/.9,0,1)[...,None]**.5
    rgb2=inner*(1-w)+rgb*w
    rgb2=np.where((a[...,3]>.92)[...,None], rgb, rgb2)
    return np.dstack([rgb2, al2])
def grade(a, sat=.55, val=.78, hue=.03, lift=(0.04,0.07,0.08), frost=.28):
    rgb=a[...,:3]
    mx=rgb.max(-1); mn=rgb.min(-1)
    l=(rgb*np.array([.299,.587,.114])).sum(-1)[...,None]
    rgb=l+(rgb-l)*sat                                  # desaturate
    rgb=rgb*val
    # cool / teal shift: pull red down a bit, blue up a bit
    rgb=rgb*np.array([1-hue*2, 1, 1+hue*3])
    rgb=rgb+np.array(lift)*(1-rgb)*.6                  # lifted, cool shadows
    # frost / top light on upward facing edges
    al=a[...,3]
    up=np.clip(al - np.roll(al,3,axis=0),0,1)          # alpha rises downward → top edge
    upb=gblur(up,1.2)
    rgb=rgb+(np.array([.86,.92,1.0])-rgb)*np.clip(upb*frost*2.2,0,frost)[...,None]
    return np.dstack([np.clip(rgb,0,1), al])
def rotscale(a, scale, rot, flip=False):
    im=to_img(a)
    if flip: im=im.transpose(Image.FLIP_LEFT_RIGHT)
    w,h=im.size; im=im.resize((max(1,int(w*scale)),max(1,int(h*scale))),Image.LANCZOS)
    im=im.rotate(rot, resample=Image.BICUBIC, expand=True)
    return np.asarray(im).astype(np.float32)/255.
def over(dst, src, x, y, shadow=.45, sdx=5, sdy=9, sblur=7, darken=1.0):
    h,w=src.shape[:2]; H,W=dst.shape[:2]
    def paste(layer, x, y):
        x0=max(0,x); y0=max(0,y); x1=min(W,x+w); y1=min(H,y+h)
        if x1<=x0 or y1<=y0: return
        s=layer[y0-y:y1-y, x0-x:x1-x]; d=dst[y0:y1,x0:x1]
        sa=s[...,3:4]; da=d[...,3:4]
        oa=sa+da*(1-sa)
        orgb=(s[...,:3]*sa+d[...,:3]*da*(1-sa))/np.maximum(oa,1e-4)
        dst[y0:y1,x0:x1]=np.dstack([orgb,oa])
    if shadow>0:
        sh=np.zeros_like(src); sh[...,:3]=np.array([.01,.02,.04]); sh[...,3]=gblur(src[...,3],sblur)*shadow
        paste(sh, x+sdx, y+sdy)
    s=src.copy(); s[...,:3]*=darken
    paste(s, x, y)
