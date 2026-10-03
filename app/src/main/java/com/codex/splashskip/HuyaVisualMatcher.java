package com.codex.splashskip;

import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Each landscape target requires its own neighbouring scene features. */
final class HuyaVisualMatcher {
    static final String AD = "huya-landscape-ad-close";
    static final String SPLASH = "huya-splash-skip";
    static final String GAME_AD = "huya-game-ad-close", FISH = "huya-fish-panel-close";
    static final String REWARD = "huya-reward-card-close";
    static final String PORTRAIT_AD = "huya-portrait-ad-close";
    private final Template cross, download, closeText, gameDownload, promoHeader, fishCross, fishHeader;
    private final Template gameInstall,rewardCross,rewardClaim,rewardTitle,playLabel;
    private Template[] actionWords=new Template[0];
    HuyaVisualMatcher withActionWords(Template[] words) {actionWords=words;return this;}
    HuyaVisualMatcher(Template cross, Template download, Template closeText, Template gameDownload,
                      Template promoHeader, Template fishCross, Template fishHeader,
                      Template gameInstall,Template rewardClaim,Template rewardTitle,Template playLabel) {
        this.cross = cross; this.download = download; this.closeText = closeText.scaled(2);
        this.gameDownload = gameDownload.scaled(2); this.promoHeader = promoHeader.scaled(2);
        this.fishCross = fishCross.scaled(2); this.fishHeader = fishHeader.scaled(2);
        this.gameInstall=gameInstall.scaled(2);this.rewardCross=cross.scaled(2);
        this.rewardClaim=rewardClaim.scaled(2);this.rewardTitle=rewardTitle.scaled(2);
        this.playLabel=playLabel.scaled(2);
    }
    Hit find(Frame f, boolean enabled) {
        return find(f, enabled, enabled);
    }
    Hit find(Frame f, boolean ads, boolean fish) {
        if(!ads && !fish || UiFeatureSearch.cancelled())return null;
        // Portrait and landscape cards share surfaces, action text and an actual cross.
        Hit adaptive=ads?downloadCard(new Frame(f.pixels,f.originalWidth,f.originalHeight,1216)):null;
        if(UiFeatureSearch.cancelled())return null;
        if(adaptive!=null)return adaptive;
        if(f.height>=f.width)return null;
        if (f.height < f.width*.35f || f.height > f.width*.90f) return null;
        Frame fine=new Frame(f.pixels,f.originalWidth,f.originalHeight,1216);
        Hit hit = ads ? gameAd(fine) : null;
        if(UiFeatureSearch.cancelled())return null;
        if (hit == null && ads) hit = whiteAd(f);
        if(UiFeatureSearch.cancelled())return null;
        if (hit == null && ads) hit = reward(fine);
        if(UiFeatureSearch.cancelled())return null;
        return hit != null ? hit : fish ? fishPanel(fine) : null;
    }
    private Hit downloadCard(Frame f) {
        boolean portrait=f.height>=f.width;
        for(int[] panel:VisualComponents.boxes(f,1,1,f.width-1,f.height-1,VisualComponents.WHITE,700)) {
            int w=panel[2]-panel[0],h=panel[3]-panel[1];
            if(w<f.width*.10f || w>f.width*.48f || h<45 || h<w*.85f || h>w*2.4f || panel[4]<w*h*.10f)continue;
            int edges=0;for(int i=1;i<10;i++) {
                if(grayAt(f,panel[0]+3,panel[1]+h*i/10f)>=236)edges++;
                if(grayAt(f,panel[2]-4,panel[1]+h*i/10f)>=236)edges++;
            }
            if(edges<14)continue;
            for(int[] action:VisualComponents.boxes(f,panel[0]+2,panel[1]+(int)(h*.62f),panel[2]-2,panel[3]-2,VisualComponents.BLUE,150)) {
                int aw=action[2]-action[0],ah=action[3]-action[1];
                if(aw<w*.60f || aw>w*.96f || ah<7 || aw<ah*2.3f || aw>ah*7.5f || action[4]<aw*ah*.65f)continue;
                float ax=(action[0]+action[2]-1)/2f,ay=(action[1]+action[3]-1)/2f;
                float label=actionText(f,download,ax,ay,aw);
                if(label<.80f)label=actionText(f,playLabel.scaled(.5f),ax,ay,aw);
                if(label<.80f)for(Template word:actionWords)label=Math.max(label,actionText(f,word,ax,ay,aw));
                if(label<.80f)continue;
                for(int[] glyph:VisualComponents.boxes(f,panel[0]+2,panel[1]+2,panel[2]-2,panel[1]+(int)(h*.25f),VisualComponents.GRAY,7)) {
                    float score=VisualComponents.cross(f,glyph,false);if(score<.78f)continue;
                    float x=(glyph[0]+glyph[2]-1)/2f,y=(glyph[1]+glyph[3]-1)/2f;
                    float scale=Math.max(.5f,Math.min(2f,1.4f*(glyph[2]-glyph[0])/(11f*f.width/608)));
                    return f.hit(portrait?PORTRAIT_AD:AD,x,y,Math.min(score,label)).withCropScale(scale);
                }
            }
        }
        return null;
    }
    private float actionText(Frame f,Template word,float x,float y,float buttonWidth) {
        float best=0,base=buttonWidth*.55f/word.width;
        for(float factor:new float[]{1f,.9f,1.1f,.8f,1.2f}) {
            float[] hit=refine(f,word.scaled(base*factor),x,y,3);best=Math.max(best,hit[2]);
        }
        return best;
    }
    private Hit whiteAd(Frame f) {
        // Each candidate must belong to the current white card and its own CTA.
        for(int[] panel:VisualComponents.boxes(f,1,1,f.width-1,f.height-1,VisualComponents.WHITE,250)) {
            int w=panel[2]-panel[0],h=panel[3]-panel[1];
            if(w<60 || h<80 || w>f.width*.60f || h>w*3)continue;
            for(float[] close:UiFeatureSearch.all(f,cross,panel[0],panel[1],panel[2],panel[1]+h*.30f,.82f,6)) {
                float[] label=UiFeatureSearch.find(f,download,panel[0],close[1]+cross.height,panel[2],panel[3],.74f);
                if(label==null) {
                    Frame fine=new Frame(f.pixels,f.originalWidth,f.originalHeight,1216);
                    label=UiFeatureSearch.find(fine,playLabel,panel[0]*2,close[1]*2+cross.height*2,panel[2]*2,panel[3]*2,.82f);
                    if(label==null || !blue(fine,label[0]-playLabel.width*.75f,label[1]) || !blue(fine,label[0]+playLabel.width*.75f,label[1]))continue;
                }
                return f.hit(AD,close[0],close[1],Math.min(close[2],label[2]));
            }
        }
        return null;
    }
    private boolean blue(Frame f,float x,float y) {
        int ix=Math.round(x*f.originalWidth/f.width),iy=Math.round(y*f.originalHeight/f.height);
        if(ix<0 || iy<0 || ix>=f.originalWidth || iy>=f.originalHeight)return false;
        int color=f.pixels[iy*f.originalWidth+ix],r=(color>>16)&255,g=(color>>8)&255,b=color&255;
        return b>r+40 && b>g+30;
    }
    private Hit gameAd(Frame f) {
        for(float[] header:UiFeatureSearch.all(f,promoHeader,.82f)) {
            float[] close=UiFeatureSearch.find(f,closeText,header[0]+promoHeader.width/2,header[1]-closeText.height,f.width,header[1]+closeText.height,.86f);
            if(close==null)continue;
            float[] label=UiFeatureSearch.find(f,gameDownload,header[0]-f.width*.10f,close[1]+closeText.height,close[0],Math.min(f.height,close[1]+f.height*.70f),.82f);
            if(label==null)label=UiFeatureSearch.find(f,gameInstall,header[0]-f.width*.10f,close[1]+closeText.height,close[0],Math.min(f.height,close[1]+f.height*.70f),.82f);
            if(label!=null)return f.hit(GAME_AD,close[0],close[1],Math.min(close[2],Math.min(header[2],label[2])));
        }
        return null;
    }
    private Hit reward(Frame f) {
        for(float[] close:UiFeatureSearch.all(f,rewardCross,.86f)) {
            if(grayAt(f,close[0]-130,close[1]+36)<230 || grayAt(f,close[0]+6,close[1]+120)<230)continue;
            float[] claim=UiFeatureSearch.find(f,rewardClaim,close[0]-f.width*.30f,close[1]+rewardCross.height,close[0],Math.min(f.height,close[1]+f.height*.65f),.85f);
            if(claim==null)continue;
            float[] title=UiFeatureSearch.find(f,rewardTitle,close[0]-f.width*.30f,close[1]+rewardCross.height,close[0],claim[1],.86f);
            if(title!=null)return f.hit(REWARD,close[0],close[1],Math.min(close[2],Math.min(claim[2],title[2])));
        }
        return null;
    }
    private Hit fishPanel(Frame f) {
        for(float[] header:UiFeatureSearch.all(f,fishHeader,.86f)) {
            if(horizontalEdges(f,fishHeader,header[0],header[1])<.70f)continue;
            for(float[] close:UiFeatureSearch.all(f,fishCross,header[0]+fishHeader.width/2+fishCross.width/2,header[1]-fishHeader.height,f.width,header[1]+fishHeader.height,.78f,12)) {
                // This tiny X can be partially covered. Verify its visible
                // strokes separately so a plain panel edge cannot stand in for it.
                if(UiFeatureSearch.part(f,fishCross,close[0],close[1],1,3,7,6)<.70f ||
                        grayAt(f,header[0]-80,header[1]+80)<100 || grayAt(f,header[0]+40,header[1]+80)<100)continue;
                return f.hit(FISH,close[0],close[1],Math.min(header[2],close[2]));
            }
        }
        return null;
    }
    // Reject matching a plain horizontal panel edge in place of the title's actual characters.
    private float horizontalEdges(Frame f,Template t,float cx,float cy) {
        float left=cx-t.width/2,top=cy-t.height/2;
        if(left<0 || top<0 || left+t.width>=f.width || top+t.height>=f.height)return -1;
        double a=0,b=0,aa=0,bb=0,ab=0;int count=0;
        for(int y=0;y<t.rows;y++)for(int x=1;x<t.columns-1;x++) {
            int py=(int)(top+(y+.5f)*t.height/t.rows);
            int lx=(int)(left+(x-.5f)*t.width/t.columns),rx=(int)(left+(x+1.5f)*t.width/t.columns);
            double actual=f.gray[py*f.width+rx]-f.gray[py*f.width+lx];
            double expected=t.normalized[y*t.columns+x+1]-t.normalized[y*t.columns+x-1];
            a+=actual;b+=expected;aa+=actual*actual;bb+=expected*expected;ab+=actual*expected;count++;
        }
        double va=aa-a*a/count,vb=bb-b*b/count;
        return va<1 || vb<1?-1:(float)((ab-a*b/count)/Math.sqrt(va*vb));
    }
}
