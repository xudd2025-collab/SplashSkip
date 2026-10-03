package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Carousel promotion overlay: circle + actual X, dimmed page, modal surface and both navigation words. */
final class ChinaMobileVisualMatcher {
    static final String CLOSE="mobile-promotion-close";
    private final Template previous,next;
    ChinaMobileVisualMatcher(Template previous,Template next) {this.previous=previous;this.next=next;}
    Hit find(Frame f) {
        if(f.height<=f.width)return null;
        for(int[] ring:VisualComponents.boxes(f,1,1,f.width-1,f.height-1,VisualComponents.LIGHT,50)) {
            float w=ring[2]-ring[0],h=ring[3]-ring[1];
            if(w<f.width*.05f || w>f.width*.15f || w/h<.88f || w/h>1.14f || ring[4]>w*h*.45f)continue;
            float x=(ring[0]+ring[2]-1)/2f,y=(ring[1]+ring[3]-1)/2f,r=(w+h)*.24f;
            if(ringCoverage(f,x,y,r)<.85f)continue;
            float cross=0;
            for(int[] glyph:VisualComponents.boxes(f,(int)(x-r*.63f),(int)(y-r*.63f),(int)(x+r*.63f),(int)(y+r*.63f),VisualComponents.LIGHT,15))
                cross=Math.max(cross,VisualComponents.cross(f,glyph,true));
            if(cross<.78f)continue;
            for(int[] card:VisualComponents.boxes(f,1,(int)(y+r),f.width-1,f.height-1,VisualComponents.BRIGHT,5000)) {
                int cw=card[2]-card[0],ch=card[3]-card[1];
                if(cw<f.width*.50f || cw>f.width*.86f || ch<f.height*.22f || ch>f.height*.56f || card[4]<cw*ch*.55f)continue;
                if(card[1]-y>f.height*.18f || x<card[0]-r || x>card[2]+r)continue;
                if(grayAt(f,card[0]-12,(card[1]+card[3])/2f)>150 || grayAt(f,card[2]+12,(card[1]+card[3])/2f)>150)continue;
                float prev=word(f,previous,card[0],card[0]+cw*.5f,card[3],Math.min(f.height-20,card[3]+f.height*.18f));
                float after=word(f,next,card[0]+cw*.5f,card[2],card[3],Math.min(f.height-20,card[3]+f.height*.18f));
                if(prev<.75f || after<.75f)continue;
                return f.hit(CLOSE,x,y,Math.min(cross,Math.min(prev,after))).withCropScale(w/55f);
            }
        }
        return null;
    }
    private float ringCoverage(Frame f,float x,float y,float r) {
        int count=0;
        for(int i=0;i<40;i++) {
            double angle=i*Math.PI*2/40;boolean white=false;
            for(int dr=-1;dr<=1;dr++)if(grayAt(f,(float)(x+Math.cos(angle)*(r+dr)),(float)(y+Math.sin(angle)*(r+dr)))>=215)white=true;
            if(white)count++;
        }
        return count/40f;
    }
    private float word(Frame f,Template text,float left,float right,float top,float bottom) {
        for(float size:new float[]{1f,.9f,1.1f,.8f,1.2f}) {
            Template t=text.scaled(size);
            for(int y=(int)top;y<bottom;y+=3)for(int x=(int)left;x<right;x+=3) {
                if(correlate(f,t,x,y)<.45f)continue;
                float[] hit=refine(f,t,x,y,2);
                if(hit[2]>=.75f && SplashPromptVerifier.half(f,t,hit[0],hit[1],0)>=.55f && SplashPromptVerifier.half(f,t,hit[0],hit[1],1)>=.55f)return hit[2];
            }
        }
        return 0;
    }
}
