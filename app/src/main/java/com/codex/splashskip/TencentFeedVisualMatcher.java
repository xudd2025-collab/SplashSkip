package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** An ad's own caption/X and its direct-close modal. Recommendation Xs are excluded. */
final class TencentFeedVisualMatcher {
    static final String FEED="tencent-feed-ad-close",DIRECT="tencent-feed-direct-close";
    private final Template download,ad,title,direct;
    private Template[] adGlyphs=new Template[0];
    TencentFeedVisualMatcher withAdGlyphs(Template[] words) {adGlyphs=words;return this;}
    private static final VisualComponents.Mask CAPTION=(f,x,y)->grayAt(f,x,y)>=238 && grayAt(f,x,y)<=250;
    private static final VisualComponents.Mask DARK=(f,x,y)->grayAt(f,x,y)<95;
    TencentFeedVisualMatcher(Template download,Template ad,Template title,Template direct) {
        this.download=download;this.ad=ad;this.title=title;this.direct=direct;
    }
    Hit find(Frame f) {
        Hit modal=modal(f);if(modal!=null)return modal;
        for(int[] panel:VisualComponents.boxes(f,1,1,f.width-1,f.height-1,CAPTION,1500)) {
            int w=panel[2]-panel[0],h=panel[3]-panel[1];
            if(w<f.width*.65f || h<f.width*.07f || h>f.width*.60f || panel[4]<w*h*.45f)continue;
            float[] action=word(f,download,panel[0],panel[1],panel[2],panel[3],.75f);
            if(action==null)continue;
            // The ad marker belongs to this caption and its adjoining video,
            // not to a separate recommendation above or below it.
            float[] mark=word(f,ad,panel[0],Math.max(0,panel[1]-w*.85f),panel[2],panel[3],.70f);
            if(mark==null)mark=AdLabelVerifier.region(f,new float[]{panel[0]/(float)f.width,Math.max(.01f,(panel[1]-w*.85f)/f.height),panel[2]/(float)f.width,panel[3]/(float)f.height},adGlyphs,()->false);
            if(mark==null)continue;
            for(int[] cross:VisualComponents.boxes(f,panel[0]+2,(int)(action[1]-30),panel[2]-2,(int)(action[1]+30),DARK,12)) {
                float score=VisualComponents.cross(f,cross,false);if(score<.80f)continue;
                float x=(cross[0]+cross[2]-1)/2f,y=(cross[1]+cross[3]-1)/2f;
                if(x<action[0]+download.width*.25f || x-action[0]>w*.35f)continue;
                float scale=1.4f*(cross[2]-cross[0])/11f;
                return f.hit(FEED,x,y,Math.min(score,Math.min(action[2],mark[2]))).withCropScale(scale);
            }
        }
        return null;
    }
    private Hit modal(Frame f) {
        for(int[] panel:VisualComponents.boxes(f,1,1,f.width-1,f.height-1,VisualComponents.WHITE,5000)) {
            int w=panel[2]-panel[0],h=panel[3]-panel[1];
            if(w<f.width*.65f || h<f.width*.35f || h>f.width*1.15f || panel[4]<w*h*.45f)continue;
            float[] heading=word(f,title,panel[0],panel[1],panel[2],panel[1]+h*.35f,.78f);
            if(heading==null)continue;
            float[] close=word(f,direct,panel[0],heading[1]+title.height*.5f,panel[2],panel[3],.80f);
            if(close==null || grayAt(f,panel[0]-8,(panel[1]+panel[3])/2f)>190 || grayAt(f,panel[2]+8,(panel[1]+panel[3])/2f)>190)continue;
            return f.hit(DIRECT,close[0],close[1],Math.min(heading[2],close[2]));
        }
        return null;
    }
    private float[] word(Frame f,Template source,float l,float t,float r,float b,float threshold) {
        for(float scale:new float[]{1f,.85f,.95f,1.1f,.8f,.9f,.7f,1.2f,1.05f,1.15f}) {
            Template text=source.scaled(scale);
            float[] hit=UiFeatureSearch.first(f,text,l,t,r,b,threshold,c -> UiFeatureSearch.part(f,text,c[0],c[1],0,0,text.columns/2,text.rows)>=.60f && UiFeatureSearch.part(f,text,c[0],c[1],text.columns/2,0,text.columns,text.rows)>=.60f);
            if(hit!=null)return hit;
            // Text may be dark on a light panel; correlation needs inversion.
            Template inverse=text.inverted();
            hit=UiFeatureSearch.first(f,inverse,l,t,r,b,threshold,c -> UiFeatureSearch.part(f,inverse,c[0],c[1],0,0,inverse.columns/2,inverse.rows)>=.60f && UiFeatureSearch.part(f,inverse,c[0],c[1],inverse.columns/2,0,inverse.columns,inverse.rows)>=.60f);
            if(hit!=null)return hit;
        }
        return null;
    }
}
