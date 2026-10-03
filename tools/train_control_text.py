"""Train only control words on generated fonts, never on advertising creative images.

The two user screenshots are excluded from training and used by ControlTextCheck.
Pass a directory of legally installed fonts; no fonts are bundled by this script.
"""
import argparse, hashlib, json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import train_skip_model as base

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--fonts',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--report',type=Path,required=True);p.add_argument('--weights',type=Path,required=True)
    args=p.parse_args();rng=np.random.default_rng(20261002);base.HIDDEN=32
    names=['msyh.ttc','msyhbd.ttc','simhei.ttf','Deng.ttf','Dengb.ttf','simsun.ttc']
    targets=['关闭广告','广告']
    negatives=['关闭网页','关闭通知','广告设置','广告推荐','继续播放','继续观看','放大画面','直接关闭','取消','跳过','电视剧','广告已关闭','不显示广告','关闭广告功能','关掉广告','了解详情','立即下载','×','返回']
    def render(word,font):
        face=ImageFont.truetype(str(args.fonts/font),int(rng.integers(24,56)))
        b=face.getbbox(word);w=b[2]-b[0];h=b[3]-b[1]
        mx=int(rng.integers(0,6));my=int(rng.integers(0,5));im=Image.new('L',(w+mx*2,h+my*2),0)
        ImageDraw.Draw(im).text((mx-b[0],my-b[1]),word,font=face,fill=255)
        im=im.resize((32,20),Image.Resampling.BILINEAR)
        shifted=Image.new('L',(32,20),0);shifted.paste(im,(int(rng.integers(-1,2)),int(rng.integers(-1,2))));im=shifted
        bg=int(rng.integers(0,170));fg=int(rng.integers(max(bg+45,170),256));a=np.asarray(im,dtype='float32')/255
        a=bg+(fg-bg)*a+rng.normal(0,3,a.shape)
        if rng.random()<.3:a=255-a
        im=Image.fromarray(np.clip(a,0,255).astype('uint8'))
        if rng.random()<.2:im=im.filter(ImageFilter.GaussianBlur(.3))
        return base.vector(im)
    x=[];y=[];val=[];vy=[]
    for name in names:
        for word in targets+negatives:
            label=np.array([word==t for t in targets],dtype='float32')
            for j in range(260):
                v=render(word,name)
                # Separate generated instances; actual screenshots remain entirely held out.
                (val if j>=208 else x).append(v);(vy if j>=208 else y).append(label)
    x=np.asarray(x,'float32');y=np.asarray(y,'float32');val=np.asarray(val,'float32');vy=np.asarray(vy,'float32')
    params=[rng.normal(0,.035,(640,32)).astype('float32'),np.zeros((1,32),'float32'),rng.normal(0,.035,(32,2)).astype('float32'),np.zeros((1,2),'float32')]
    ms=[np.zeros_like(v) for v in params];vs=[np.zeros_like(v) for v in params]
    groups=[np.flatnonzero(y.sum(1)==0),np.flatnonzero(y[:,0]>0),np.flatnonzero(y[:,1]>0)]
    for step in range(1,1601):
        indices=np.concatenate([rng.choice(g,64) for g in groups]);bx=x[indices];by=y[indices];w1,b1,w2,b2=params
        z=bx@w1+b1;hidden=np.maximum(z,0);prob=1/(1+np.exp(-np.clip(hidden@w2+b2,-30,30)))
        dz=(prob-by)/len(indices);dh=(dz@w2.T)*(z>0)
        grads=[bx.T@dh+.0003*w1,dh.sum(0,keepdims=True),hidden.T@dz+.0003*w2,dz.sum(0,keepdims=True)]
        for v,g,m,s in zip(params,grads,ms,vs):
            m[:]=.9*m+.1*g;s[:]=.999*s+.001*g*g;v-=.002*(m/(1-.9**step))/(np.sqrt(s/(1-.999**step))+1e-8)
        if step%250==0:print('step',step,flush=True)
    stats={}
    for name,xx,yy in [('train',x,y),('validation_generated',val,vy)]:
        pp=base.predict(xx,params);stats[name]={}
        for i,word in enumerate(targets):
            stats[name][word]={'positive_min':float(pp[yy[:,i]>0,i].min()),'negative_max':float(pp[yy[:,i]==0,i].max()),'false_positive_at_095':int(((yy[:,i]==0)&(pp[:,i]>=.95)).sum()),'false_negative_at_095':int(((yy[:,i]>0)&(pp[:,i]<.95)).sum())}
    args.output.parent.mkdir(parents=True,exist_ok=True);base.export(params,args.output,'Generated control words only; requires visible glyphs and button structure')
    args.weights.parent.mkdir(parents=True,exist_ok=True)
    args.weights.write_bytes(np.concatenate([params[0].T.flatten(),params[1].flatten(),params[2].T.flatten(),params[3].flatten()]).astype('<f4').tobytes())
    stats.update({'seed':20261002,'input':[1,640],'heads':targets,'hidden':32,'train_count':len(x),'heldout_count':len(val),'training_fonts':names,'validation_split':'independent generated instances; not an unknown-font evaluation','personal_screenshots_in_training':False,'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest()})
    args.report.write_text(json.dumps(stats,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps(stats,ensure_ascii=True),flush=True)
    # Font atlases contain only UI glyphs. Each tile is tightly cropped with one-pixel padding.
    for word,filename in [('关闭广告','control_close_glyphs'),('广告','control_ad_glyphs')]:
        atlas=Image.new('RGB',(192 if len(word)==4 else 96,62*len(names)),'black')
        for i,name in enumerate(names):
            face=ImageFont.truetype(str(args.fonts/name),48);b=face.getbbox(word);tile=Image.new('RGB',(b[2]-b[0]+4,b[3]-b[1]+4),'black');ImageDraw.Draw(tile).text((2-b[0],2-b[1]),word,font=face,fill='white')
            tile.thumbnail((atlas.width,60));atlas.paste(tile,((atlas.width-tile.width)//2,i*62+(62-tile.height)//2))
        atlas.save(args.output.parent.parent/'res/drawable-nodpi'/f'{filename}.png')
if __name__=='__main__':main()
