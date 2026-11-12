"""Stitches the cached OSM tiles around the demo position and applies the same night ColorMatrix as MapTileView.
Output: assets/map_preview.jpg (1136x688) for the HTML simulator."""
import numpy as np
from PIL import Image
import os
d=os.path.join(os.path.dirname(__file__),'assets','tiles')
Z,x0,x1,y0,y1,left,top=open(os.path.join(d,'meta.txt')).read().split()
x0,x1,y0,y1=map(int,(x0,x1,y0,y1)); left=float(left); top=float(top)
W,H=1136,688
def night(im):
    """Same per-pixel recolouring as MapTileView.night() (Kotlin)."""
    r,g,b=im[...,0]/255,im[...,1]/255,im[...,2]/255
    mx=np.maximum(np.maximum(r,g),b); mn=np.minimum(np.minimum(r,g),b); sat=mx-mn
    L=0.299*r+0.587*g+0.114*b
    # base: background / buildings
    v=0.075+0.3*np.clip(0.95-L,0,0.2)
    out=np.stack([v*0.92,v*0.96,v*1.12],-1)
    # labels & dark lines -> light
    m=L<0.55; t=0.9*(1-L)
    out[m]=np.stack([t,t,t],-1)[m]
    # green areas
    m=(g>r+0.04)&(g>b)&(L>=0.55); out[m]=[0.07,0.13,0.10]
    # water
    m=(b>r+0.08)&(b>=g)&(L>=0.55); out[m]=[0.05,0.09,0.16]
    # major roads (orange/yellow/pink)
    m=(r>0.85)&(sat>0.12)&(b<g)&(L>=0.55)&~(g>r+0.04)
    out[m&(g>0.62)]=[0.52,0.41,0.27]
    out[m&(g<=0.62)]=[0.20,0.22,0.26]
    # minor roads: near-white
    m=(sat<0.05)&(L>0.975); out[m]=[0.36,0.39,0.45]
    return (np.clip(out,0,1)*255).astype(np.uint8)

big=Image.new('RGB',((x1-x0+1)*256,(y1-y0+1)*256))
for x in range(x0,x1+1):
  for y in range(y0,y1+1):
    big.paste(Image.open(os.path.join(d,f'16-{x}-{y}.png')).convert('RGB'),((x-x0)*256,(y-y0)*256))
ox=int(round(left-x0*256)); oy=int(round(top-y0*256))
im=np.asarray(big.crop((ox,oy,ox+W,oy+H))).astype(np.float32)
out=night(im)
Image.fromarray(out).save(os.path.join(os.path.dirname(__file__),'assets','map_preview.jpg'),quality=82)
def day(im):
    """Same as MapTileView.day() (Kotlin, integer maths)."""
    im=im.astype(np.int32); r,g,b=im[...,0],im[...,1],im[...,2]
    l=(299*r+587*g+114*b)//1000
    out=np.stack([(r+l)//2*86//100+30,(g+l)//2*86//100+31,(b+l)//2*86//100+34],-1)
    return np.clip(out,0,255).astype(np.uint8)
Image.fromarray(day(im)).save(os.path.join(os.path.dirname(__file__),'assets','map_preview_day.jpg'),quality=82)
print('ok')
