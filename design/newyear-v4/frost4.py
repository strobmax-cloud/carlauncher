"""Procedural frosted-glass edge (ice ferns), 1280x720 RGBA. Generated here — no third-party texture."""
import numpy as np, math, random
from PIL import Image, ImageDraw, ImageFilter
W,H=1280,720; S=2                     # supersample
rng=random.Random(19); nrng=np.random.default_rng(19)
yy,xx=np.mgrid[0:H*S,0:W*S].astype(np.float32)/S
d=np.minimum.reduce([xx,yy,W-1-xx,H-1-yy])
cd=np.minimum.reduce([np.hypot(xx,yy),np.hypot(W-xx,yy),np.hypot(xx,H-yy),np.hypot(W-xx,H-yy)])
corner=np.exp(-cd/210.)
def reach_at(x,y):
    c=min(math.hypot(x,y),math.hypot(W-x,y),math.hypot(x,H-y),math.hypot(W-x,H-y))
    return 46+150*math.exp(-c/210.)
reach=46+150*corner
fall=np.clip(1-d/reach,0,1)**1.6

def fbm(shape,octs=5):
    out=np.zeros(shape,np.float32); a=1.
    for o in range(octs):
        f=2**o; sm=nrng.random((shape[0]//(64//f if 64//f>0 else 1)+2, shape[1]//(64//f if 64//f>0 else 1)+2)).astype(np.float32)
        im=Image.fromarray((sm*255).astype(np.uint8)).resize((shape[1],shape[0]),Image.BICUBIC)
        out+=a*np.asarray(im,np.float32)/255; a*=.5
    return out/out.max()
n=fbm((H*S,W*S))
haze=np.clip(fall*(.2+1.0*n**2)-.1,0,1)*.32          # milky frost film

lines=Image.new('L',(W*S,H*S),0); dr=ImageDraw.Draw(lines)
def seg(x0,y0,x1,y1,a,w):
    dr.line([(x0*S,y0*S),(x1*S,y1*S)],fill=int(255*a),width=max(1,int(w*S)))
def fern(x,y,ang,L,a,w,depth,fork=True):
    """ice fern: a gently curving stem with close, forward-pointing barbs on both sides (barbs carry finer barbs)"""
    st=2.0; steps=max(2,int(L/st)); curl=rng.uniform(-.02,.02); px,py=x,y
    for i in range(steps):
        t=i/steps; curl+=rng.uniform(-.006,.006); ang+=curl
        nx,ny=px+st*math.cos(ang),py+st*math.sin(ang)
        aa=a*(1-.6*t); seg(px,py,nx,ny,aa,w)
        if depth>0 and i%2==1:
            rem=L*(1-t)
            for side in (-1,1):
                bl=min(rem*.38,18 if depth>1 else 7)*rng.uniform(.55,1.)
                if bl>2.5: fern(nx,ny,ang+side*rng.uniform(.7,.95),bl,aa*.8,max(.5,w*.6),depth-1,False)
        if fork and depth>1 and rng.random()<.025 and L*(1-t)>30:
            fern(nx,ny,ang+rng.choice((-1,1))*rng.uniform(.35,.6),L*(1-t)*.75,aa,w*.85,depth,True)
        px,py=nx,ny
def edge_seeds():
    out=[]
    for edge in range(4):
        length=W if edge in (0,2) else H; p=0.
        while p<length:
            if edge==0: x,y,inw=p,0,math.pi/2
            elif edge==2: x,y,inw=p,H,-math.pi/2
            elif edge==1: x,y,inw=W,p,math.pi
            else: x,y,inw=0,p,0.
            r=reach_at(x,y); out.append((x,y,inw,r))
            p+=rng.uniform(.35,.6)*r*.5+8
    return out
for (x,y,inw,r) in edge_seeds():
    for k in range(2 if r>90 else 1):
        ang=inw+rng.uniform(-.75,.75)
        fern(x+rng.uniform(-6,6),y+rng.uniform(-6,6),ang,r*rng.uniform(.5,1.0),rng.uniform(.5,.85),rng.uniform(.8,1.1),2)
# corner stars: radial ferns from the very corner
for (cx,cy,a0) in [(0,0,0),(W,0,math.pi/2),(W,H,math.pi),(0,H,-math.pi/2)]:
    for j in range(6):
        fern(cx,cy,a0+math.pi/2*(j+.5)/6+rng.uniform(-.1,.1),rng.uniform(130,210),.85,1.2,2)
L=np.asarray(lines.filter(ImageFilter.GaussianBlur(.35*S)),np.float32)/255
glowL=np.asarray(lines.filter(ImageFilter.GaussianBlur(3*S)),np.float32)/255
cryst=np.clip(L*1.1,0,1)*np.clip(fall*1.8,0,1)*.85 + glowL*.25*fall

alpha=np.clip(haze+cryst-haze*cryst,0,1)
# sparkle: sparse bright points on the crystals
sp=np.zeros_like(alpha); pts=nrng.random(alpha.shape)<.00045*np.clip(L*3,0,1)*fall
sp[pts]=1; spi=Image.fromarray((sp*255).astype(np.uint8))
sp=np.asarray(spi.filter(ImageFilter.GaussianBlur(1.1*S)),np.float32)/255*10
alpha=np.clip(alpha+np.clip(sp,0,1)*.6,0,1)*.78
t=np.clip(cryst*1.4,0,1)
R=205+45*t; G=222+30*t; B=245+10*t        # cold blue film → near-white crystals
rgba=np.dstack([R,G,B,alpha*255]).clip(0,255).astype(np.uint8)
im=Image.fromarray(rgba,'RGBA').resize((W,H),Image.LANCZOS)
im.save('/workspace/ny-assets/v4/ny4_frost.png'); im.save('/workspace/ny-assets/v4/ny4_frost.webp',quality=88)
bg=Image.new('RGBA',(W,H),(24,22,46,255)); bg.alpha_composite(im); bg.convert('RGB').save('/tmp/ny/frost-b.png')
