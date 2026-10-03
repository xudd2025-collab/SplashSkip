package com.codex.splashskip;
final class TouchTargetGuard {
    static boolean covered(BilibiliVisualMatcher.Hit hit,int[] overlay) {
        int[] button=AdActionRegion.box(hit,hit.frameWidth,hit.frameHeight);
        if(button==null) {int radius=Math.max(8,hit.frameWidth/100);button=new int[]{hit.x-radius,hit.y-radius,hit.x+radius,hit.y+radius};}
        if(overlay[0]<=hit.x && hit.x<overlay[2] && overlay[1]<=hit.y && hit.y<overlay[3])return true;
        long overlap=(long)Math.max(0,Math.min(button[2],overlay[2])-Math.max(button[0],overlay[0]))*
                Math.max(0,Math.min(button[3],overlay[3])-Math.max(button[1],overlay[1]));
        return overlap*10L>(long)(button[2]-button[0])*(button[3]-button[1]);
    }
}
