package com.codex.splashskip;
/** A submitted gesture or a scene-matcher miss cannot by itself become a learned success. */
final class ClickLearningSession {
    static final int UNKNOWN=0,PRESENT=1,ABSENT=2;
    final String pkg;final long epoch,submitted;final BilibiliVisualMatcher.Hit hit;
    volatile long completedAt;private int absent;private long firstAbsent;
    String recordId="";private int present;private long firstPresent;
    ClickLearningSession(String pkg,long epoch,long submitted,BilibiliVisualMatcher.Hit hit) {
        this.pkg=pkg;this.epoch=epoch;this.submitted=submitted;this.hit=hit;
    }
    void complete(long now) { completedAt=now; }
    boolean expired(long now) {return now-submitted>2200;}
    boolean ineffective(long requested,int state,boolean clear) {
        if(completedAt<=0 || requested<completedAt+400 || requested>completedAt+1700 || !clear || state!=PRESENT){present=0;return false;}
        if(present==0){present=1;firstPresent=requested;return false;}
        return requested-firstPresent>=180;
    }
    boolean observe(long requested,int state,boolean matchingScene,boolean clearTarget) {
        if(completedAt<=0 || requested<completedAt+120 || requested>completedAt+1700 || !clearTarget) {absent=0;return false;}
        if(state!=ABSENT || matchingScene) {absent=0;return false;}
        if(absent==0) {if(requested-completedAt>900)return false;firstAbsent=requested;absent=1;return false;}
        if(requested-firstAbsent<180)return false;
        absent++;return absent>=2;
    }
}
