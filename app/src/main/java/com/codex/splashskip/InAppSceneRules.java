package com.codex.splashskip;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

final class InAppSceneRules implements AutoCloseable {
    static final String HUYA = "com.duowan.kiwi";
    static final String TENCENT = "com.tencent.qqlive";
    static final String MOBILE = "com.greenpoint.android.mc10086.activity";
    private final NetdiskRules netdisk;
    private final HuyaVisualMatcher huya;
    private final TencentVisualMatcher tencent;
    private final TencentFeedVisualMatcher tencentFeed;
    private final UniversalSplashMatcher universal;
    private final ChinaMobileVisualMatcher mobile;
    private final ControlTextMatcher controlText;
    private final UiTextRecognizer uiText;
    private SkipTextModel controlModel;
    private static final java.util.Map<String,Boolean> launchable=new java.util.concurrent.ConcurrentHashMap<>();
    private AdActionModel actionModel;
    private final RecentButtonMemory memory;
    private final BilibiliVisualMatcher.Template[] skipWords;
    private long lastModelTrace;
    InAppSceneRules(Context context) {
        memory=RecentButtonMemory.get(context);
        uiText=new UiTextRecognizer(context);
        controlText=new ControlTextMatcher(controlGlyphs(context));
        try {controlModel=new SkipTextModel(context,"control_text.tflite");}
        catch(Exception | LinkageError error) {Diagnostics.append(context,"control text model unavailable: "+error.getClass().getSimpleName());}
        tencentFeed=new TencentFeedVisualMatcher(template(context,R.drawable.feed_download_text,64,20),template(context,R.drawable.feed_ad_text,32,20),template(context,R.drawable.feed_reason_title,112,20),template(context,R.drawable.feed_direct_close,64,20));
        mobile=new ChinaMobileVisualMatcher(template(context,R.drawable.mobile_prev_text,48,20),template(context,R.drawable.mobile_next_text,48,20));
        netdisk=new NetdiskRules(context);
        BilibiliVisualMatcher.Template[] glyphs=glyphs(context);
        skipWords=glyphs;
        BilibiliVisualMatcher.Template[] features=glyphs(context,R.drawable.skip_feature_glyphs);
        BilibiliVisualMatcher.Template[] adGlyphs=glyphs(context,R.drawable.generic_ad_glyphs);
        tencentFeed.withAdGlyphs(adGlyphs);
        universal=new UniversalSplashMatcher(new BilibiliVisualMatcher.Template[]{
                template(context,R.drawable.universal_skip_text,32,20),template(context,R.drawable.tencent_skip_text,32,20),
                template(context,R.drawable.tencent_skip_bold,32,20)},new BilibiliVisualMatcher.Template[]{
                template(context,R.drawable.universal_ad_label,32,16),template(context,R.drawable.tencent_ad_label,32,16),
                template(context,R.drawable.tencent_interactive_ad,48,16),template(context,R.drawable.tencent_interactive_right,48,16)}).withGlyphs(glyphs).withAdGlyphs(adGlyphs)
                .withPrompts(new BilibiliVisualMatcher.Template[]{template(context,R.drawable.tencent_flip_label,80,24),template(context,R.drawable.tencent_flip_bold,80,24)}).withFeatures(features);
        huya=new HuyaVisualMatcher(template(context,R.drawable.huya_ad_cross,16,16),
                template(context,R.drawable.huya_download_label,48,16),template(context,R.drawable.huya_close_text,32,16),
                template(context,R.drawable.huya_download_game,48,16),template(context,R.drawable.huya_promo_header,48,16),
                template(context,R.drawable.huya_fish_cross,8,8),template(context,R.drawable.huya_fish_header,64,12),
                template(context,R.drawable.huya_game_install,48,16),template(context,R.drawable.huya_reward_claim,48,16),
                template(context,R.drawable.huya_reward_title,64,16),template(context,R.drawable.huya_play_label,48,16))
                .withActionWords(new BilibiliVisualMatcher.Template[]{template(context,R.drawable.download_bold_text,64,20),template(context,R.drawable.play_bold_text,64,20)});
        tencent=new TencentVisualMatcher(template(context,R.drawable.tencent_skip_text,48,24),
                template(context,R.drawable.tencent_interactive_ad,64,16),template(context,R.drawable.tencent_ad_label,32,20),
                template(context,R.drawable.tencent_flip_label,80,24),template(context,R.drawable.tencent_banner_cross,32,32),
                template(context,R.drawable.tencent_reserve_label,64,24),template(context,R.drawable.tencent_video_cross,12,14),
                template(context,R.drawable.tencent_video_ad,40,20),template(context,R.drawable.tencent_video_replay,32,32),
                template(context,R.drawable.tencent_skip_bold,48,24),template(context,R.drawable.tencent_interactive_right,64,24),
                template(context,R.drawable.tencent_flip_bold,80,24))
                .withPreloadedLabel(template(context,R.drawable.tencent_preloaded_label,96,20)).withGlyphs(glyphs).withAdGlyphs(adGlyphs).withFeatures(features);
        try { actionModel=new AdActionModel(context); }
        catch(Exception | LinkageError error) {
            Diagnostics.append(context,"action model unavailable; verified scene rules remain active: "+error.getClass().getSimpleName());
        }
    }
    static boolean mobilePackage(String pkg) {return pkg!=null && (MOBILE.equals(pkg) || pkg.equals("com.greenpoint.android.mc10086"));}
    static boolean supports(String pkg) { return NetdiskRules.PACKAGE.equals(pkg) || HUYA.equals(pkg) || TENCENT.equals(pkg) || mobilePackage(pkg); }
    static boolean watches(Context c,String pkg) {
        if(supports(pkg))return true;
        if(pkg==null || pkg.isEmpty() || pkg.equals(c.getPackageName()) || pkg.equals("com.android.systemui") ||
                pkg.contains("launcher") || pkg.contains(".settings"))return false;
        Boolean allowed=launchable.get(pkg);
        if(allowed==null) {
            allowed=c.getPackageManager().getLaunchIntentForPackage(pkg)!=null;launchable.put(pkg,allowed);
        }
        return allowed;
    }
    static boolean genericPages(Context c) {return option(c,"enabled") && AppProfiles.enabled(c) && option(c,"generic_ad_close");}
    static boolean enabled(Context c,String pkg) {
        if(genericPages(c) && watches(c,pkg))return true;
        if (NetdiskRules.PACKAGE.equals(pkg)) return NetdiskRules.promo(c) || NetdiskRules.customer(c);
        if(!c.getSharedPreferences("settings",0).getBoolean("enabled",true) || !AppProfiles.enabled(c))return false;
        if(HUYA.equals(pkg))return option(c,"huya_ads") || option(c,"huya_fish");
        if(TENCENT.equals(pkg))return option(c,"tencent_splash") || option(c,"tencent_banner") || option(c,"tencent_video") || option(c,"tencent_feed");
        if(mobilePackage(pkg))return option(c,"mobile_promo");
        return watches(c,pkg);
    }
    private static boolean option(Context c,String key) { return c.getSharedPreferences("settings",0).getBoolean(key,true); }
    boolean accepts(Context c,String rule) {
        if(UiControlPolicy.isControl(rule))return option(c,"enabled") && AppProfiles.enabled(c) && (!UiControlPolicy.CLOSE_AD.equals(rule) || genericPages(c));
        if(ControlTextMatcher.CLOSE.equals(rule))return genericPages(c);
        if (NetdiskVisualMatcher.PROMO.equals(rule)) return NetdiskRules.promo(c);
        if (NetdiskVisualMatcher.CUSTOMER.equals(rule)) return NetdiskRules.customer(c);
        if(HuyaVisualMatcher.FISH.equals(rule))return enabled(c,HUYA) && option(c,"huya_fish");
        if(HuyaVisualMatcher.AD.equals(rule) || HuyaVisualMatcher.PORTRAIT_AD.equals(rule) || HuyaVisualMatcher.GAME_AD.equals(rule) || HuyaVisualMatcher.REWARD.equals(rule) || HuyaVisualMatcher.SPLASH.equals(rule))return enabled(c,HUYA) && option(c,"huya_ads");
        if(ChinaMobileVisualMatcher.CLOSE.equals(rule))return option(c,"enabled") && AppProfiles.enabled(c) && option(c,"mobile_promo");
        if(UniversalSplashMatcher.SKIP.equals(rule))return AppProfiles.enabled(c) && option(c,"enabled");
        if(TencentVisualMatcher.SPLASH.equals(rule))return enabled(c,TENCENT) && option(c,"tencent_splash");
        if(TencentVisualMatcher.BANNER.equals(rule))return enabled(c,TENCENT) && option(c,"tencent_banner");
        if(TencentFeedVisualMatcher.FEED.equals(rule) || TencentFeedVisualMatcher.DIRECT.equals(rule))return enabled(c,TENCENT) && option(c,"tencent_feed");
        return TencentVisualMatcher.VIDEO.equals(rule) && enabled(c,TENCENT) && option(c,"tencent_video");
    }
    void prepareText(){uiText.prepare();}
    void beginTextScene(){uiText.newScene();}
    boolean verifyText(Bitmap screen,BilibiliVisualMatcher.Hit hit,java.util.function.BooleanSupplier cancelled,long budget){return uiText.verifyControl(screen,hit,cancelled,budget);}
    boolean textReady(){return uiText.ready();}
    String textStatus(){return uiText.status();}
    String textSummary(){return uiText.summary();}
    void clearTextFrame(){uiText.clearFrame();}
    BilibiliVisualMatcher.Hit image(Context c,String pkg,Bitmap image,java.util.function.BooleanSupplier cancelled,boolean splashPriority,boolean openingWindow,long deadline) {
        return image(c,pkg,image,cancelled,splashPriority,openingWindow,deadline,null);
    }
    BilibiliVisualMatcher.Hit image(Context c,String pkg,Bitmap image,java.util.function.BooleanSupplier cancelled,boolean splashPriority,boolean openingWindow,long deadline,ControlTree.Snapshot tree) {

        int w=image.getWidth(),h=image.getHeight(); int[] pixels=new int[w*h];
        image.getPixels(pixels,0,w,0,0,w,h);
        if(!enabled(c,pkg))return null;
        NativeControlPolicy.Candidate nativeControl=NativeControlPolicy.find(tree,openingWindow);
        boolean nativeEligible=nativeControl!=null && tree.current(pkg,tree.epoch,android.os.SystemClock.uptimeMillis(),w,h)
                && textAllowed(c,pkg,nativeControl.label.action);
        if(tree!=null && !cancelled.getAsBoolean())JointLearningStore.get(c).observeCandidate(tree,pixels,w,h);
        // Diagnostic serialization cannot invalidate the selection and force OCR;
        // execution still re-reads a fresh live tree before ACTION_CLICK.
        if(nativeEligible && !cancelled.getAsBoolean()) {
            uiText.clearFrame();
            return nativeControl.hit(tree);
        }
        BilibiliVisualMatcher.Hit text=uiText.findTree(pixels,w,h,openingWindow,tree,cancelled,deadline-android.os.SystemClock.uptimeMillis());
        if(text==null && uiText.prefersFreshFrame())return null;
        if(text==null)text=uiText.find(pixels,w,h,openingWindow,cancelled,Math.min(700,deadline-android.os.SystemClock.uptimeMillis()));
        if(text!=null && textAllowed(c,pkg,text.rule)) {
            ControlTree.Hint hint=tree==null?null:tree.match(text);
            text.structure=hint==null?"":hint.structure;
            text.jointFeatures=JointControlModel.features(pixels,w,h,text.textBounds,hint);
            return text;
        }
        if(text==null && uiText.deferFallback(openingWindow))return null;
        java.util.function.BooleanSupplier bounded=()->cancelled.getAsBoolean() || android.os.SystemClock.uptimeMillis()>deadline;
        if(bounded.getAsBoolean())return null;
        BilibiliVisualMatcher.Frame frame=new BilibiliVisualMatcher.Frame(pixels,w,h);
        // Opening skip controls get the first frame; reuse its pixels for page-close fallback.
        UiFeatureSearch.setCancellation(bounded);
        try {
            BilibiliVisualMatcher.Hit hit=sceneImage(c,pkg,image,frame,bounded,splashPriority,openingWindow);
            if(bounded.getAsBoolean())return null;
            if(hit!=null)return hit;
            return genericPages(c) && controlModel!=null?controlFrame(c,image,frame,bounded):null;
        } finally {UiFeatureSearch.setCancellation(null);}
    }
    private boolean textAllowed(Context c,String pkg,String rule) {
        if(!accepts(c,rule))return false;
        if(UiControlPolicy.CLOSE_AD.equals(rule))return genericPages(c);
        if(HUYA.equals(pkg))return option(c,"huya_ads");
        if(TENCENT.equals(pkg))return option(c,"tencent_splash");
        return true;
    }
    private BilibiliVisualMatcher.Hit sceneImage(Context c,String pkg,Bitmap image,BilibiliVisualMatcher.Frame frame,java.util.function.BooleanSupplier cancelled,boolean splashPriority,boolean openingWindow) {
        if (NetdiskRules.PACKAGE.equals(pkg)) return netdisk.image(image,NetdiskRules.promo(c),NetdiskRules.customer(c));
        if(BilibiliRules.PACKAGE.equals(pkg))return null;
        if(!supports(pkg) && !openingWindow)return null;
        long genericDeadline=android.os.SystemClock.uptimeMillis()+500;
        java.util.function.BooleanSupplier genericCancelled=()->cancelled.getAsBoolean() || android.os.SystemClock.uptimeMillis()>genericDeadline;
        ControlTextMatcher.Model labelClassifier=controlModel==null?null:(f,box,head) -> controlProbability(image,box,head);
        universal.withLabelModel(labelClassifier);tencent.withLabelModel(labelClassifier);
        AdButtonClassifier classifier=actionModel==null?null:new AdButtonClassifier() {
        @Override public java.util.List<BilibiliVisualMatcher.Hit> prioritize(BilibiliVisualMatcher.Frame f,java.util.List<BilibiliVisualMatcher.Hit> candidates) {
            return memory.bank.prioritize(pkg,f,candidates,System.currentTimeMillis());
        }
        @Override public float strokeProbability(BilibiliVisualMatcher.Frame f,BilibiliVisualMatcher.Hit candidate) { return cancelled.getAsBoolean()?0:actionModel.strokeProbability(f,candidate); }
        @Override public float probability(BilibiliVisualMatcher.Frame f,BilibiliVisualMatcher.Hit candidate) {
            if(cancelled.getAsBoolean())return 0;
            float probability=actionModel.probability(image,candidate);
            if(probability>=.98f && android.os.SystemClock.uptimeMillis()-lastModelTrace>2000) {
                lastModelTrace=android.os.SystemClock.uptimeMillis();
                Diagnostics.append(c,"AI button proposal "+candidate.rule+" probability="+String.format(java.util.Locale.ROOT,"%.3f",probability));
            }
            return probability;
        }};
        BilibiliVisualMatcher.Hit hit;
        if(HUYA.equals(pkg)) {
            hit=openingWindow && frame.height>frame.width && option(c,"huya_ads")?universal.findLaunching(frame,classifier,HuyaVisualMatcher.SPLASH,cancelled):null;
            if(hit==null)hit=huya.find(frame,option(c,"huya_ads"),option(c,"huya_fish"));
            if(hit==null && !openingWindow && frame.height>frame.width && option(c,"huya_ads"))hit=universal.find(frame,classifier,HuyaVisualMatcher.SPLASH,genericCancelled);
        } else if(TENCENT.equals(pkg)) {
            // During initial loading the splash gets the first frame; popup searches resume after this short window.
            hit=openingWindow && option(c,"tencent_splash")?tencent.opening(frame,classifier,true,cancelled):null;
            if(hit==null)hit=tencent.find(frame,!openingWindow && option(c,"tencent_splash"),!splashPriority && option(c,"tencent_banner"),!splashPriority && option(c,"tencent_video"),classifier);
            if(hit==null && !openingWindow && option(c,"tencent_splash"))hit=universal.find(frame,classifier,TencentVisualMatcher.SPLASH,genericCancelled,splashPriority?1:4);
            if(hit==null && option(c,"tencent_feed"))hit=tencentFeed.find(frame);
        } else if(mobilePackage(pkg))hit=mobile.find(frame);
        else hit=openingWindow?universal.findLaunching(frame,classifier,UniversalSplashMatcher.SKIP,genericCancelled):universal.find(frame,classifier,UniversalSplashMatcher.SKIP,genericCancelled);
        if(hit==null || actionModel==null || TencentFeedVisualMatcher.DIRECT.equals(hit.rule))return hit;
        float probability=Float.isNaN(hit.modelProbability)?actionModel.probability(image,hit):hit.modelProbability;
        probability=Math.max(probability,actionModel.verifiedCrossProbability(frame,hit));
        long now=android.os.SystemClock.uptimeMillis();
        if(now-lastModelTrace>2000) {
            lastModelTrace=now;
            Diagnostics.append(c,"action model "+hit.rule+" probability="+String.format(java.util.Locale.ROOT,"%.3f",probability)+
                    " accepted="+(probability>=AdActionModel.THRESHOLD));
        }
        return probability>=AdActionModel.THRESHOLD?hit.withModel(probability).withSignature(ButtonFeatureMemory.signature(frame,hit)):null;
    }
    synchronized BilibiliVisualMatcher.Hit controlImage(Context c,Bitmap image,java.util.function.BooleanSupplier cancelled) {
        if(!genericPages(c) || controlModel==null)return null;
        int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);
        return controlFrame(c,image,new BilibiliVisualMatcher.Frame(pixels,w,h),cancelled);
    }
    private synchronized BilibiliVisualMatcher.Hit controlFrame(Context c,Bitmap image,BilibiliVisualMatcher.Frame frame,java.util.function.BooleanSupplier cancelled) {
        if(!genericPages(c) || controlModel==null)return null;
        return controlText.find(frame,(f,box,head) -> controlProbability(image,box,head),cancelled);
    }
    private synchronized float controlProbability(Bitmap image,int[] box,int head) {
        return controlModel==null?0:controlModel.probability(image,new android.graphics.Rect(box[0],box[1],box[2],box[3]),head);
    }
    int observeButton(Bitmap image,BilibiliVisualMatcher.Hit prior) {
        if(UiControlPolicy.isControl(prior.rule))return uiText.observe(prior);
        if(actionModel==null || prior.buttonSignature==null || image.getWidth()!=prior.frameWidth || image.getHeight()!=prior.frameHeight)return ClickLearningSession.UNKNOWN;
        int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);
        BilibiliVisualMatcher.Frame f=new BilibiliVisualMatcher.Frame(pixels,w,h);
        float current=actionModel.probability(image,prior);
        float similar=ButtonFeatureMemory.similarity(prior.buttonSignature,ButtonFeatureMemory.signature(f,prior));
        if(current>=.50f || similar>=.72f)return ClickLearningSession.PRESENT;
        if(SkipGlyphVerifier.accepts(f,prior,skipWords,prior.cropScale,.68f) || SkipGlyphVerifier.strokesAccepts(f,prior,skipWords))return ClickLearningSession.PRESENT;
        return current<.25f && similar<.60f?ClickLearningSession.ABSENT:ClickLearningSession.UNKNOWN;
    }
    void remember(String pkg,BilibiliVisualMatcher.Hit hit) { memory.remember(pkg,hit); }
    @Override public synchronized void close() { uiText.close();if(actionModel!=null)actionModel.close();if(controlModel!=null){controlModel.close();controlModel=null;} }
    private static BilibiliVisualMatcher.Template template(Context c,int id,int columns,int rows) {
        Bitmap image=BitmapFactory.decodeResource(c.getResources(),id);
        int w=image.getWidth(),h=image.getHeight(); int[] pixels=new int[w*h];
        image.getPixels(pixels,0,w,0,0,w,h); image.recycle();
        return new BilibiliVisualMatcher.Template(pixels,w,h,columns,rows);
    }
    private static BilibiliVisualMatcher.Template[] controlGlyphs(Context c) {
        Bitmap atlas=BitmapFactory.decodeResource(c.getResources(),R.drawable.control_close_glyphs);
        int w=atlas.getWidth(),h=62,n=atlas.getHeight()/h;
        BilibiliVisualMatcher.Template[] words=new BilibiliVisualMatcher.Template[n];
        for(int i=0;i<n;i++) {
            int[] tile=new int[w*h];atlas.getPixels(tile,0,w,0,i*h,w,h);
            words[i]=ControlTextMatcher.glyph(tile,w,h);
        }
        atlas.recycle();return words;
    }
    private static BilibiliVisualMatcher.Template[] glyphs(Context c) {
        return glyphs(c,R.drawable.generic_skip_glyphs);
    }
    static BilibiliVisualMatcher.Template[] glyphs(Context c,int resource) {
        Bitmap atlas=BitmapFactory.decodeResource(c.getResources(),resource);
        int width=atlas.getWidth(),tileHeight=62,count=atlas.getHeight()/tileHeight;
        BilibiliVisualMatcher.Template[] words=new BilibiliVisualMatcher.Template[count];
        for(int i=0;i<count;i++) {
            int[] pixels=new int[width*tileHeight];atlas.getPixels(pixels,0,width,0,i*tileHeight,width,tileHeight);
            words[i]=resource==R.drawable.generic_ad_glyphs?AdLabelVerifier.glyph(pixels,width,tileHeight):
                    new BilibiliVisualMatcher.Template(pixels,width,tileHeight,32,20);
        }
        atlas.recycle();return words;
    }
}
