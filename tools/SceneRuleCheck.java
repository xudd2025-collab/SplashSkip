package com.codex.splashskip;

import java.awt.Color;
import java.awt.image.BufferedImage;

/** Exercises the shipped matchers against external positive and negative examples. */
public final class SceneRuleCheck {
    static BilibiliVisualMatcher.Template[] glyphs(String folder)throws Exception {
        return glyphs(folder,"generic_skip_glyphs");
    }
    static BilibiliVisualMatcher.Template[] glyphs(String folder,String name)throws Exception {
        BufferedImage atlas=ScreenshotRuleCheck.read(folder+"/"+name+".png");
        BilibiliVisualMatcher.Template[] words=new BilibiliVisualMatcher.Template[atlas.getHeight()/62];
        for(int i=0;i<words.length;i++) {
            BufferedImage tile=atlas.getSubimage(0,i*62,116,62);
            int[] pixels=tile.getRGB(0,0,116,62,null,0,116);
            words[i]=name.equals("generic_ad_glyphs")?AdLabelVerifier.glyph(pixels,116,62):new BilibiliVisualMatcher.Template(pixels,116,62,32,20);
        }
        return words;
    }
    static UniversalSplashMatcher universal(String folder)throws Exception {
        return new UniversalSplashMatcher(new BilibiliVisualMatcher.Template[]{
                ScreenshotRuleCheck.template(folder,"universal_skip_text",32,20),ScreenshotRuleCheck.template(folder,"tencent_skip_text",32,20),
                ScreenshotRuleCheck.template(folder,"tencent_skip_bold",32,20)},new BilibiliVisualMatcher.Template[]{
                ScreenshotRuleCheck.template(folder,"universal_ad_label",32,16),ScreenshotRuleCheck.template(folder,"tencent_ad_label",32,16),
                ScreenshotRuleCheck.template(folder,"tencent_interactive_ad",48,16),ScreenshotRuleCheck.template(folder,"tencent_interactive_right",48,16)}).withGlyphs(glyphs(folder)).withAdGlyphs(glyphs(folder,"generic_ad_glyphs"))
                .withPrompts(new BilibiliVisualMatcher.Template[]{ScreenshotRuleCheck.template(folder,"tencent_flip_label",80,24),ScreenshotRuleCheck.template(folder,"tencent_flip_bold",80,24)}).withFeatures(glyphs(folder,"skip_feature_glyphs"));
    }
    static HuyaVisualMatcher huya(String folder)throws Exception {
        return new HuyaVisualMatcher(ScreenshotRuleCheck.template(folder,"huya_ad_cross",16,16),
                ScreenshotRuleCheck.template(folder,"huya_download_label",48,16),ScreenshotRuleCheck.template(folder,"huya_close_text",32,16),
                ScreenshotRuleCheck.template(folder,"huya_download_game",48,16),ScreenshotRuleCheck.template(folder,"huya_promo_header",48,16),
                ScreenshotRuleCheck.template(folder,"huya_fish_cross",8,8),ScreenshotRuleCheck.template(folder,"huya_fish_header",64,12),
                ScreenshotRuleCheck.template(folder,"huya_game_install",48,16),ScreenshotRuleCheck.template(folder,"huya_reward_claim",48,16),
                ScreenshotRuleCheck.template(folder,"huya_reward_title",64,16),ScreenshotRuleCheck.template(folder,"huya_play_label",48,16))
                .withActionWords(new BilibiliVisualMatcher.Template[]{ScreenshotRuleCheck.template(folder,"download_bold_text",64,20),ScreenshotRuleCheck.template(folder,"play_bold_text",64,20)});
    }
    static TencentVisualMatcher tencent(String folder)throws Exception {
        return new TencentVisualMatcher(ScreenshotRuleCheck.template(folder,"tencent_skip_text",48,24),
                ScreenshotRuleCheck.template(folder,"tencent_interactive_ad",64,16),ScreenshotRuleCheck.template(folder,"tencent_ad_label",32,20),
                ScreenshotRuleCheck.template(folder,"tencent_flip_label",80,24),ScreenshotRuleCheck.template(folder,"tencent_banner_cross",32,32),
                ScreenshotRuleCheck.template(folder,"tencent_reserve_label",64,24),ScreenshotRuleCheck.template(folder,"tencent_video_cross",12,14),
                ScreenshotRuleCheck.template(folder,"tencent_video_ad",40,20),ScreenshotRuleCheck.template(folder,"tencent_video_replay",32,32),
                ScreenshotRuleCheck.template(folder,"tencent_skip_bold",48,24),ScreenshotRuleCheck.template(folder,"tencent_interactive_right",64,24),
                ScreenshotRuleCheck.template(folder,"tencent_flip_bold",80,24))
                .withPreloadedLabel(ScreenshotRuleCheck.template(folder,"tencent_preloaded_label",96,20)).withGlyphs(glyphs(folder)).withAdGlyphs(glyphs(folder,"generic_ad_glyphs")).withFeatures(glyphs(folder,"skip_feature_glyphs"));
    }
    static BilibiliVisualMatcher.Frame frame(BufferedImage im) {
        return new BilibiliVisualMatcher.Frame(im.getRGB(0,0,im.getWidth(),im.getHeight(),null,0,im.getWidth()),im.getWidth(),im.getHeight());
    }
    interface Find { BilibiliVisualMatcher.Hit run(BufferedImage im); }
    static ChinaMobileVisualMatcher mobile(String folder)throws Exception {
        return new ChinaMobileVisualMatcher(ScreenshotRuleCheck.template(folder,"mobile_prev_text",48,20),ScreenshotRuleCheck.template(folder,"mobile_next_text",48,20));
    }
    static void positive(String name,BufferedImage original,Find find,int[] widths,String rule,float x,float y) {
        for(int width:widths) {
            long started=System.nanoTime();
            BilibiliVisualMatcher.Hit hit=find.run(ScreenshotRuleCheck.resize(original,width));
            float scale=width/1216f;
            if(hit==null || !rule.equals(hit.rule) || Math.abs(hit.x-x*scale)>12*scale || Math.abs(hit.y-y*scale)>12*scale)
                throw new AssertionError(name+" width="+width+" "+(hit==null?"none":hit.rule+" "+hit.x+","+hit.y));
            System.out.printf("PASS %s width=%d tap=%d,%d score=%.3f elapsed=%dms%n",name,width,hit.x,hit.y,hit.score,(System.nanoTime()-started)/1000000);
        }
    }
    static void none(String name,BufferedImage im,Find find) {
        BilibiliVisualMatcher.Hit hit=find.run(im);
        if(hit!=null)throw new AssertionError(name+" falsely matched "+hit.rule+" "+hit.x+","+hit.y);
        System.out.println("PASS reject "+name);
    }
    public static void main(String[] args)throws Exception {
        HuyaVisualMatcher h=huya(args[0]);TencentVisualMatcher t=tencent(args[0]);
        Find hf=im->h.find(frame(im),true,true),tf=im->t.find(frame(im),true,true,true);
        BufferedImage game=ScreenshotRuleCheck.read(args[1]),fish=ScreenshotRuleCheck.read(args[2]),white=ScreenshotRuleCheck.read(args[3]);
        BufferedImage splash1=ScreenshotRuleCheck.read(args[4]),splash2=ScreenshotRuleCheck.read(args[5]),banner=ScreenshotRuleCheck.read(args[6]),video=ScreenshotRuleCheck.read(args[7]),feed=ScreenshotRuleCheck.read(args[8]);
        int[] landscape={1216,1920,2640},portrait={720,1080,1260,1440};
        positive("huya game promotion",game,hf,landscape,HuyaVisualMatcher.GAME_AD,1120.5f,101.5f);
        positive("huya fish panel",fish,hf,landscape,HuyaVisualMatcher.FISH,1097.5f,344.5f);
        positive("huya white promotion",white,hf,landscape,HuyaVisualMatcher.AD,1138,192);
        positive("tencent interactive splash",splash1,tf,portrait,TencentVisualMatcher.SPLASH,1091.5f,195.5f);
        positive("tencent labelled splash",splash2,tf,portrait,TencentVisualMatcher.SPLASH,1091.5f,195.5f);
        positive("tencent reservation",banner,tf,portrait,TencentVisualMatcher.BANNER,1119,1970);
        positive("tencent account-page ad",video,tf,portrait,TencentVisualMatcher.VIDEO,1181.5f,157.5f);
        for(int width:portrait)none("normal drama recommendation width="+width,ScreenshotRuleCheck.resize(feed,width),tf);
        game=ScreenshotRuleCheck.resize(game,1216);fish=ScreenshotRuleCheck.resize(fish,1216);
        splash1=ScreenshotRuleCheck.resize(splash1,1216);splash2=ScreenshotRuleCheck.resize(splash2,1216);
        banner=ScreenshotRuleCheck.resize(banner,1216);video=ScreenshotRuleCheck.resize(video,1216);
        none("game promotion without close",ScreenshotRuleCheck.erase(game,1096,86,52,32,Color.DARK_GRAY),hf);
        none("game promotion without download",ScreenshotRuleCheck.erase(game,920,416,105,35,Color.BLUE),hf);
        none("game promotion without header",ScreenshotRuleCheck.erase(game,828,87,98,29,Color.DARK_GRAY),hf);
        none("fish panel with disappeared cross",ScreenshotRuleCheck.erase(fish,1090,337,16,17,Color.GRAY),hf);
        none("fish cross without panel title",ScreenshotRuleCheck.erase(fish,970,333,107,27,Color.CYAN),hf);
        none("splash without skip",ScreenshotRuleCheck.erase(splash1,1036,164,116,62,Color.GRAY),tf);
        none("splash without advertising label",ScreenshotRuleCheck.erase(splash1,585,174,147,49,Color.GREEN),tf);
        none("generic splash without ad label",ScreenshotRuleCheck.erase(splash2,30,162,91,54,Color.BLUE),tf);
        none("splash without interactive prompt",ScreenshotRuleCheck.erase(splash1,473,2284,274,80,Color.GREEN),tf);
        none("reservation without circular cross",ScreenshotRuleCheck.erase(banner,1071,1920,97,99,Color.WHITE),tf);
        none("cross without reservation button",ScreenshotRuleCheck.erase(banner,716,2415,210,74,Color.PINK),tf);
        none("video without advertising label",ScreenshotRuleCheck.erase(video,1070,130,95,62,Color.DARK_GRAY),tf);
        none("video without cross",ScreenshotRuleCheck.erase(video,1166,136,35,43,Color.DARK_GRAY),tf);
        none("video without playback control",ScreenshotRuleCheck.erase(video,32,662,96,85,Color.GRAY),tf);
        none("disabled huya",game,im->h.find(frame(im),false,false));
        none("disabled fish",fish,im->h.find(frame(im),true,false));
        none("disabled tencent splash",splash1,im->t.find(frame(im),false,true,true));
        none("disabled tencent reservation",banner,im->t.find(frame(im),true,false,true));
        none("disabled tencent video",video,im->t.find(frame(im),true,true,false));
        for(BufferedImage im:new BufferedImage[]{game,fish,white})none("landscape rejected by tencent",im,tf);
        for(BufferedImage im:new BufferedImage[]{splash1,splash2,banner,video,feed})none("portrait rejected by huya",im,hf);
        if(args.length>=11) {
            BufferedImage install=ScreenshotRuleCheck.read(args[9]),reward=ScreenshotRuleCheck.read(args[10]);
            positive("huya immediate download",install,hf,landscape,HuyaVisualMatcher.GAME_AD,1120.5f,101.5f);
            positive("huya grain reward",reward,hf,landscape,HuyaVisualMatcher.REWARD,1138,216);
            install=ScreenshotRuleCheck.resize(install,1216);reward=ScreenshotRuleCheck.resize(reward,1216);
            none("immediate download without close",ScreenshotRuleCheck.erase(install,1096,86,52,32,Color.DARK_GRAY),hf);
            none("immediate download without install label",ScreenshotRuleCheck.erase(install,925,415,110,38,Color.BLUE),hf);
            none("reward without cross",ScreenshotRuleCheck.erase(reward,1120,199,38,37,Color.WHITE),hf);
            none("reward without claim",ScreenshotRuleCheck.erase(reward,1014,397,105,45,Color.YELLOW),hf);
            none("reward without title",ScreenshotRuleCheck.erase(reward,988,324,160,45,Color.WHITE),hf);
            none("reward disabled",reward,im->h.find(frame(im),false,true));
        }
        if(args.length>=12) {
            BufferedImage black=ScreenshotRuleCheck.read(args[11]);
            positive("tencent bold black splash",black,tf,portrait,TencentVisualMatcher.SPLASH,1084.5f,217.5f);
            black=ScreenshotRuleCheck.resize(black,1216);
            none("bold splash without skip",ScreenshotRuleCheck.erase(black,1010,174,148,87,Color.DARK_GRAY),tf);
            none("bold splash without advertising label",ScreenshotRuleCheck.erase(black,812,180,174,76,Color.BLACK),tf);
            none("bold splash without interactive prompt",ScreenshotRuleCheck.erase(black,468,2198,282,102,Color.BLACK),tf);
            none("bold splash disabled",black,im->t.find(frame(im),false,true,true));
        }
        if(args.length>=13) {
            BufferedImage wifi=ScreenshotRuleCheck.read(args[12]);
            positive("tencent Wi-Fi-prefetched splash",wifi,tf,portrait,TencentVisualMatcher.SPLASH,1084.5f,217.5f);
            wifi=ScreenshotRuleCheck.resize(wifi,1216);
            none("prefetched splash without skip",ScreenshotRuleCheck.erase(wifi,1010,174,148,87,Color.DARK_GRAY),tf);
            none("prefetched splash without advertising label",ScreenshotRuleCheck.erase(wifi,553,185,154,58,Color.GRAY),tf);
            none("prefetched splash without flip prompt",ScreenshotRuleCheck.erase(wifi,468,2198,282,102,Color.BLACK),tf);
        }
        if(args.length>=14) {
            BufferedImage play=ScreenshotRuleCheck.read(args[13]);
            positive("huya play promotion",play,hf,landscape,HuyaVisualMatcher.AD,1138,216);
            play=ScreenshotRuleCheck.resize(play,1216);
            none("play card without cross",ScreenshotRuleCheck.erase(play,1120,198,38,38,Color.WHITE),hf);
            none("play card without action text",ScreenshotRuleCheck.erase(play,1015,397,105,43,Color.BLUE),hf);
            none("play card without blue action context",ScreenshotRuleCheck.erase(play,1025,402,89,34,Color.WHITE),hf);
            none("play card disabled",play,im->h.find(frame(im),false,true));
        }
        for(int i=14;i<args.length;i++) {
            BufferedImage enlarged=ScreenshotRuleCheck.read(args[i]);
            positive("tencent adaptive ad label "+i,enlarged,tf,portrait,TencentVisualMatcher.SPLASH,1091,218);
            enlarged=ScreenshotRuleCheck.resize(enlarged,1216);
            none("adaptive splash without ad label "+i,ScreenshotRuleCheck.erase(enlarged,792,188,163,62,Color.DARK_GRAY),tf);
            none("adaptive splash without skip "+i,ScreenshotRuleCheck.erase(enlarged,1010,174,160,87,Color.DARK_GRAY),tf);
            none("adaptive splash without flip "+i,ScreenshotRuleCheck.erase(enlarged,468,2198,282,102,Color.DARK_GRAY),tf);
        }
    }
}
