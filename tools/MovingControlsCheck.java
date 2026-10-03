package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.Color;
import java.awt.image.BufferedImage;

/** Relocate controls and labels independently; a recommendation X is a mandatory negative. */
public final class MovingControlsCheck {
    static BufferedImage shifted(BufferedImage source,int dx,int dy) {
        BufferedImage result=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_RGB);
        var g=result.createGraphics();g.setColor(new Color(120,120,120));g.fillRect(0,0,result.getWidth(),result.getHeight());g.drawImage(source,dx,dy,null);g.dispose();return result;
    }
    static BufferedImage move(BufferedImage source,int x,int y,int w,int h,int dx,int dy,Color fill) {
        var result=ScreenshotRuleCheck.erase(source,x,y,w,h,fill);var g=result.createGraphics();
        g.drawImage(source,x+dx,y+dy,x+dx+w,y+dy+h,x,y,x+w,y+h,null);g.dispose();return result;
    }
    static void yes(String name,BufferedImage source,SceneRuleCheck.Find find,String rule,int x,int y,boolean modelRequired,AdButtonModelCheck model) {
        for(int width:new int[]{480,720,1216,2160}) {
            var im=ScreenshotRuleCheck.resize(source,width);long started=System.nanoTime();Hit hit=find.run(im);float scale=width/1216f;
            if(hit==null || !hit.rule.equals(rule) || Math.abs(hit.x/scale-x)>15 || Math.abs(hit.y/scale-y)>15)
                throw new AssertionError(name+" width="+width+" "+(hit==null?"none":hit.rule+" "+hit.x+","+hit.y));
            if(modelRequired && model.probability(SceneRuleCheck.frame(im),hit)<.85f)throw new AssertionError(name+" production model gate width="+width);
            System.out.printf("PASS %s width=%d tap=%d,%d scene=%.3f elapsed=%dms%n",name,width,hit.x,hit.y,hit.score,(System.nanoTime()-started)/1000000);
        }
    }
    static BilibiliVisualMatcher bili(String folder)throws Exception {
        return new BilibiliVisualMatcher(ScreenshotRuleCheck.template(folder,"bili_ad_cross",32,32),ScreenshotRuleCheck.template(folder,"bili_ad_menu",32,32),ScreenshotRuleCheck.template(folder,"bili_sheet_handle",48,8),ScreenshotRuleCheck.template(folder,"bili_ad_label",32,20),ScreenshotRuleCheck.template(folder,"bili_auto_live",80,16),ScreenshotRuleCheck.template(folder,"bili_live_cancel",40,20)).withAdGlyphs(SceneRuleCheck.glyphs(folder,"generic_ad_glyphs"));
    }
    static TencentFeedVisualMatcher feed(String folder)throws Exception {
        return new TencentFeedVisualMatcher(ScreenshotRuleCheck.template(folder,"feed_download_text",64,20),ScreenshotRuleCheck.template(folder,"feed_ad_text",32,20),ScreenshotRuleCheck.template(folder,"feed_reason_title",112,20),ScreenshotRuleCheck.template(folder,"feed_direct_close",64,20)).withAdGlyphs(SceneRuleCheck.glyphs(folder,"generic_ad_glyphs"));
    }
    public static void main(String[] a)throws Exception {
        var model=new AdButtonModelCheck(a[1]);var mobile=SceneRuleCheck.mobile(a[0]);var huya=SceneRuleCheck.huya(a[0]);var bili=bili(a[0]);var feed=feed(a[0]);var tencent=SceneRuleCheck.tencent(a[0]);var universal=SceneRuleCheck.universal(a[0]);
        SceneRuleCheck.Find mf=im->mobile.find(SceneRuleCheck.frame(im)),hf=im->huya.find(SceneRuleCheck.frame(im),true,true),bf=im->bili.find(SceneRuleCheck.frame(im),true,false),ff=im->feed.find(SceneRuleCheck.frame(im));
        var m=ScreenshotRuleCheck.resize(ScreenshotRuleCheck.read(a[2]),1216);var h=ScreenshotRuleCheck.resize(ScreenshotRuleCheck.read(a[3]),1216);var b=ScreenshotRuleCheck.resize(ScreenshotRuleCheck.read(a[4]),1216);var f=ScreenshotRuleCheck.resize(ScreenshotRuleCheck.read(a[5]),1216);var dialog=ScreenshotRuleCheck.resize(ScreenshotRuleCheck.read(a[6]),1216);var negative=ScreenshotRuleCheck.resize(ScreenshotRuleCheck.read(a[7]),1216);var black=ScreenshotRuleCheck.resize(ScreenshotRuleCheck.read(a[8]),1216);
        yes("mobile moved overlay",shifted(m,-90,160),mf,ChinaMobileVisualMatcher.CLOSE,988,663,true,model);
        yes("huya portrait left and higher",shifted(h,-480,-380),hf,HuyaVisualMatcher.PORTRAIT_AD,660,1400,true,model);
        yes("bili pause sheet",b,bf,BilibiliVisualMatcher.AD,1133,950,false,model);
        var moved=shifted(b,0,180);
        moved=move(moved,1085,1084,96,94,-250,0,Color.WHITE);
        moved=move(moved,941,1084,96,94,-210,0,Color.WHITE);
        moved=move(moved,550,1026,116,28,-160,-20,Color.WHITE);
        moved=move(moved,40,2080,78,44,530,-260,new Color(241,242,244));
        yes("bili independently moved X menu handle and label",moved,bf,BilibiliVisualMatcher.AD,883,1130,false,model);
        yes("tencent own feed-ad X",f,ff,TencentFeedVisualMatcher.FEED,1098,1710,true,model);
        yes("tencent direct-close reason dialog",dialog,ff,TencentFeedVisualMatcher.DIRECT,240,1349,false,model);
        SceneRuleCheck.none("recommendation X plus separate unclosable ad",negative,ff);
        SceneRuleCheck.none("recommendation X rejected by other tencent rules",negative,im->tencent.find(SceneRuleCheck.frame(im),true,true,true,model));
        SceneRuleCheck.none("feed ad X absent with recommendation X intact",ScreenshotRuleCheck.erase(f,1070,1679,56,61,new Color(245,245,245)),ff);
        SceneRuleCheck.none("feed ad label absent",ScreenshotRuleCheck.erase(f,1036,950,105,64,Color.ORANGE),ff);
        SceneRuleCheck.none("feed download action absent",ScreenshotRuleCheck.erase(f,811,1670,212,84,Color.WHITE),ff);
        SceneRuleCheck.none("direct-close choice absent",ScreenshotRuleCheck.erase(dialog,124,1292,262,118,new Color(248,248,248)),ff);
        var replaced=ScreenshotRuleCheck.erase(b,42,1050,1033,581,new Color(90,150,110));
        yes("bili new creative",replaced,bf,BilibiliVisualMatcher.AD,1133,950,false,model);
        for(int[] box:new int[][]{{1085,904,96,94},{941,904,96,94},{550,846,116,28},{40,1900,78,44}})
            SceneRuleCheck.none("bili required control absent "+box[0],ScreenshotRuleCheck.erase(b,box[0],box[1],box[2],box[3],Color.WHITE),bf);
        var skipMoved=move(black,1010,174,148,87,-680,300,Color.BLACK);
        skipMoved=move(skipMoved,812,180,174,76,-625,500,Color.BLACK);
        if(a.length>9)universal.withLabelModel(new ControlTextCheck(a[9]));
        SceneRuleCheck.Find uf=im->universal.find(SceneRuleCheck.frame(im),model,UniversalSplashMatcher.SKIP,()->false);
        yes("generic skip and ad label move independently",skipMoved,uf,UniversalSplashMatcher.SKIP,405,518,true,model);
        SceneRuleCheck.none("generic moved skip without ad label",ScreenshotRuleCheck.erase(skipMoved,187,680,174,76,Color.BLACK),uf);
    }
}
