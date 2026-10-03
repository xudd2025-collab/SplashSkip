package com.codex.splashskip;

import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Full ad scenes are required; feed recommendation dismissal crosses are not targets. */
final class TencentVisualMatcher {
    static final String SPLASH="tencent-splash-skip", BANNER="tencent-reservation-close", VIDEO="tencent-video-ad-close";
    private final Template skip, interactiveAd, adLabel, flip, bannerCross, reserve, videoCross, videoAd, replay;
    private final Template skipBold, interactiveRight, flipBold;
    private Template[] preloadedLabels=new Template[0];
    private Template[] glyphs=new Template[0];
    private Template[] adGlyphs=new Template[0];
    private Template[] features=new Template[0];
    private UniversalSplashMatcher adaptiveMatcher;
    TencentVisualMatcher withFeatures(Template[] words) {features=words;adaptiveMatcher=null;return this;}
    private ControlTextMatcher.Model labelModel;
    TencentVisualMatcher withLabelModel(ControlTextMatcher.Model model){labelModel=model;return this;}
    TencentVisualMatcher withGlyphs(Template[] words) { glyphs=words;return this; }
    TencentVisualMatcher withAdGlyphs(Template[] words) { adGlyphs=words;return this; }
    TencentVisualMatcher(Template skip,Template interactiveAd,Template adLabel,Template flip,
                         Template bannerCross,Template reserve,Template videoCross,Template videoAd,Template replay,
                         Template skipBold,Template interactiveRight,Template flipBold) {
        this.skip=skip;this.interactiveAd=interactiveAd.scaled(2);this.adLabel=adLabel.scaled(2);this.flip=flip;
        this.bannerCross=bannerCross;this.reserve=reserve;this.videoCross=videoCross;this.videoAd=videoAd;this.replay=replay;
        this.skipBold=skipBold;this.interactiveRight=interactiveRight.scaled(2);this.flipBold=flipBold;
    }
    TencentVisualMatcher withPreloadedLabel(Template label) {
        preloadedLabels=new Template[]{label.scaled(2),label.scaled(2.2f),label.scaled(1.8f)};
        return this;
    }
    Hit find(Frame f,boolean splashes,boolean banners,boolean videos) {
        return find(f,splashes,banners,videos,null);
    }
    Hit find(Frame f,boolean splashes,boolean banners,boolean videos,AdButtonClassifier classifier) {
        if(f.height<f.width*1.65f || f.height>f.width*2.65f)return null;
        Hit hit=splashes?splash(f):null;
        if(hit==null && splashes && classifier!=null)hit=splashAi(f,classifier);
        if(hit==null && videos)hit=video(f);
        return hit!=null?hit:banners?banner(f):null;
    }
    Hit opening(Frame f,AdButtonClassifier classifier) {
        return opening(f,classifier,false);
    }
    private UniversalSplashMatcher adaptive() {
        if(adaptiveMatcher==null) {
            java.util.List<Template> labels=new java.util.ArrayList<>();
            labels.add(adLabel.scaled(.5f));labels.add(interactiveAd.scaled(.5f));labels.add(interactiveRight.scaled(.5f));
            Template[] textLabels=new Template[preloadedLabels.length];
            for(int i=0;i<textLabels.length;i++)textLabels[i]=preloadedLabels[i].scaled(.5f);
            adaptiveMatcher=new UniversalSplashMatcher(new Template[]{skip,skipBold},labels.toArray(new Template[0]))
                    .withGlyphs(glyphs).withAdGlyphs(adGlyphs).withPrompts(new Template[]{flip,flipBold}).withFeatures(features).withTextMarks(textLabels);
        }
        return adaptiveMatcher.withLabelModel(labelModel);
    }
    Hit opening(Frame f,AdButtonClassifier classifier,boolean launchContext) {
        return opening(f,classifier,launchContext,()->false);
    }
    Hit opening(Frame f,AdButtonClassifier classifier,boolean launchContext,java.util.function.BooleanSupplier cancelled) {
        return launchContext?adaptive().findLaunching(f,classifier,SPLASH,cancelled):adaptive().find(f,classifier,SPLASH,cancelled);
    }
    private Hit splash(Frame f) {
        // The blank brand loading page has no control here. Avoid a full scene search on that frame.
        Hit hit=splashStyle(f,skip);
        return hit!=null?hit:splashStyle(f,skipBold);
    }
    private Hit splashStyle(Frame f,Template skipText) {
        float[] button=UiFeatureSearch.find(f,skipText,0,0,f.width,f.height,.81f);
        if(button==null)return null;
        Frame fine=new Frame(f.pixels,f.originalWidth,f.originalHeight,1216);
        // Thin low-contrast text loses detail on smaller displays; both other anchors remain required.
        float[] mark=findMark(fine,button[1]*2);
        if(mark==null)return null;
        float[] prompt=findPrompt(f);
        if(prompt==null)return null;
        return f.hit(SPLASH,button[0],button[1],Math.min(button[2],Math.min(mark[2],prompt[2])));
    }
    private float[] findMark(Frame fine,float y) {
        float[] generic=AdLabelVerifier.header(fine,y,adGlyphs,()->false,labelModel);
        if(generic!=null)return generic;
        java.util.List<Template> labels=new java.util.ArrayList<>();
        labels.add(adLabel);labels.add(interactiveAd);labels.add(interactiveRight);
        java.util.Collections.addAll(labels,preloadedLabels);
        for(Template word:labels)for(float scale:new float[]{1f,.9f,1.1f}) {
            float[] hit=UiFeatureSearch.find(fine,word.scaled(scale),0,0,fine.width,fine.height,.80f);
            if(hit!=null)return hit;
        }
        return null;
    }
    private float[] findPrompt(Frame f) {
        return SplashPromptVerifier.find(f,new Template[]{flip,flipBold},()->false);
    }
    private Hit splashAi(Frame f,AdButtonClassifier classifier) {
        return splashAi(f,classifier,true);
    }
    private Hit splashAi(Frame f,AdButtonClassifier classifier,boolean requirePrompt) {
        if(requirePrompt && findPrompt(f)==null)return null;
        return opening(f,classifier,!requirePrompt);
    }
    private Hit banner(Frame f) {
        for(float[] close:UiFeatureSearch.all(f,bannerCross,.86f)) {
            float[] label=UiFeatureSearch.find(f,reserve,0,close[1]+bannerCross.height,f.width,Math.min(f.height,close[1]+f.height*.40f),.85f);
            if(label==null || label[0]>close[0] || Math.abs(label[0]-close[0])>f.width*.60f ||
                    grayAt(f,label[0]-reserve.width,label[1])>120 || grayAt(f,label[0]+reserve.width,label[1])>120 ||
                    grayAt(f,close[0]-f.width*.23f,close[1]+40)<200)continue;
            return f.hit(BANNER,close[0],close[1],Math.min(close[2],label[2]));
        }
        return null;
    }
    private Hit video(Frame f) {
        for(float[] close:UiFeatureSearch.all(f,videoCross,.83f)) {
            float[] control=UiFeatureSearch.find(f,replay,0,close[1]+videoCross.height,close[0],Math.min(f.height,close[1]+f.height*.38f),.77f);
            if(control==null)continue;
            float[] mark=UiFeatureSearch.find(f,videoAd,0,Math.max(0,close[1]-videoAd.height),f.width,control[1]+replay.height,.81f);
            if(mark==null)continue;
            float sheetY=control[1]+65;
            if(grayAt(f,f.width*.12f,sheetY)<230 || grayAt(f,f.width*.50f,sheetY)<230 || grayAt(f,f.width*.87f,sheetY)<230)continue;
            return f.hit(VIDEO,close[0],close[1],Math.min(close[2],Math.min(mark[2],control[2])));
        }
        return null;
    }

}
