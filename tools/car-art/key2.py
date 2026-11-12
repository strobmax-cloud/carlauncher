import numpy as np, sys
from PIL import Image, ImageFilter
from scipy import ndimage
f,out=sys.argv[1],sys.argv[2]
im=np.asarray(Image.open(f).convert('RGB')).astype(float)
h,w,_=im.shape
border=np.concatenate([im[:6].reshape(-1,3),im[:,:6].reshape(-1,3),im[:,-6:].reshape(-1,3)])
bg=np.median(border,0); bn=np.linalg.norm(bg)
nrm=np.linalg.norm(im,axis=2)+1e-3
dot=(im*bg).sum(2)
cos=dot/(nrm*bn); k=dot/bn**2
like=(cos>0.955)
lab,n=ndimage.label(like)
el=set(np.unique(np.concatenate([lab[0],lab[-1],lab[:,0],lab[:,-1]])))-{0}
reg=np.isin(lab,list(el))
reg=ndimage.binary_closing(reg,iterations=1)
# shadow alpha in background region
sh=np.clip(1-k,0,1)
sh=np.clip((sh-0.06)/0.94,0,1)**1.1*0.92
alpha=np.ones((h,w)); col=im.copy()
alpha[reg]=sh[reg]; col[reg]=0
# transition ring: unmix against bg*k_local
ring=ndimage.binary_dilation(reg,iterations=2)&~reg
d=np.sqrt(((im-bg)**2).sum(2))
a2=np.clip((d-30)/60,0,1)
alpha[ring]=np.maximum(a2[ring],sh[ring]) if False else a2[ring]
A=np.clip(alpha,1e-3,1)[...,None]
un=np.clip((im-(1-A)*bg)/A,0,255)
col[ring]=un[ring]
pink=np.minimum(col[...,0],col[...,2])-col[...,1]
m=ring&(pink>0)
col[...,0]=np.where(m,col[...,0]-pink*0.7,col[...,0]); col[...,2]=np.where(m,col[...,2]-pink*0.9,col[...,2])
al=Image.fromarray((alpha*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.7))
o=Image.fromarray(np.dstack([col.clip(0,255).astype(np.uint8),np.asarray(al)]),'RGBA')
bb=Image.fromarray(((alpha>0.08)*255).astype(np.uint8)).getbbox()
o=o.crop(bb); o.save(out); print(out,o.size,bg)
for nm,c in [('d',(20,24,32,255)),('l',(235,238,242,255)),('r',(70,74,82,255))]:
    b=Image.new('RGBA',o.size,c);b.alpha_composite(o);b.convert('RGB').save('/tmp/'+out[:-4]+nm+'.png')
