package com.codex.splashskip;

/** Candidate crop sizes in the same 1216-pixel reference used by scene matching. */
final class AdActionRegion {
    static boolean isSkip(String rule) { return TencentVisualMatcher.SPLASH.equals(rule) || HuyaVisualMatcher.SPLASH.equals(rule) || UniversalSplashMatcher.SKIP.equals(rule); }
    static int classIndex(String rule) { return isSkip(rule)?0:HuyaVisualMatcher.GAME_AD.equals(rule)?1:2; }
    static int[] box(BilibiliVisualMatcher.Hit hit,int width,int height) {
        float halfW,halfH;
        if(isSkip(hit.rule)) { halfW=58*hit.cropScale;halfH=31*hit.cropScale; }
        else if(TencentVisualMatcher.BANNER.equals(hit.rule)) { halfW=48;halfH=49; }
        else if(TencentVisualMatcher.VIDEO.equals(hit.rule)) { halfW=18;halfH=21; }
        else if(HuyaVisualMatcher.GAME_AD.equals(hit.rule)) { halfW=34;halfH=18; }
        else if(HuyaVisualMatcher.FISH.equals(hit.rule)) { halfW=12;halfH=12; }
        else if(HuyaVisualMatcher.AD.equals(hit.rule) || HuyaVisualMatcher.PORTRAIT_AD.equals(hit.rule) || HuyaVisualMatcher.REWARD.equals(hit.rule)) { halfW=18*hit.cropScale;halfH=18*hit.cropScale; }
        else if(ChinaMobileVisualMatcher.CLOSE.equals(hit.rule)) { halfW=36*hit.cropScale;halfH=36*hit.cropScale; }
        else if(TencentFeedVisualMatcher.FEED.equals(hit.rule)) {halfW=18*hit.cropScale;halfH=18*hit.cropScale;}
        else return null;
        float scale=width/1216f;
        int left=Math.round(hit.x-halfW*scale),top=Math.round(hit.y-halfH*scale);
        int right=Math.round(hit.x+halfW*scale),bottom=Math.round(hit.y+halfH*scale);
        return left>=0 && top>=0 && right<=width && bottom<=height && right>left && bottom>top?
                new int[]{left,top,right,bottom}:null;
    }
}
