package com.codex.splashskip;

/**
 * Post-click evidence from current accessibility trees, independent of Android.
 * GONE means that the old target is no longer exposed by the tree; it does not
 * establish that a self-drawn advertisement has disappeared from the screen.
 */
final class NativeTreeObservation {
    static final int UNKNOWN=0,PRESENT=1,GONE=2;
    final String pkg;
    final long epoch,acceptedAt;
    private final String rule,structure;
    private final int width,height;
    private long firstAbsent=-1,lastObserved=-1;
    private int lastState=UNKNOWN;
    private long lastStateAt=-1;
    private long firstCompletePresence=-1,lastCompletePresence=-1;
    private volatile boolean touchSubmitted;

    NativeTreeObservation(String pkg,long epoch,long acceptedAt,BilibiliVisualMatcher.Hit hit) {
        this.pkg=pkg;this.epoch=epoch;this.acceptedAt=acceptedAt;
        rule=hit==null?"":hit.rule;structure=hit==null?null:hit.structure;
        width=hit==null?0:hit.frameWidth;height=hit==null?0:hit.frameHeight;
    }
    boolean expired(long now) {return now<acceptedAt || now-acceptedAt>2200;}
    // A bounded observation may finish just before the main-thread callback sees
    // its timeout. Keep fresh presence available for that timeout diagnostic;
    // cached state never supplies a new disappearance observation.
    int lastFreshState(long now) {
        return lastStateAt>=0 && now>=lastStateAt && now-lastStateAt<=250?lastState:UNKNOWN;
    }
    boolean canTryCurrentTouch(long now) {
        return !touchSubmitted && !expired(now) && lastFreshState(now)==PRESENT &&
            firstCompletePresence>=0 && lastCompletePresence-firstCompletePresence>=120;
    }
    void touchSubmitted(){touchSubmitted=true;}
    int observe(ControlTree.Snapshot tree,long now) {
        if(expired(now) || !UiControlPolicy.isControl(rule) || width<=0 || height<=0 || tree==null ||
                !tree.current(pkg,epoch,now,width,height) || tree.time<acceptedAt+120)return unknown();
        // Re-reading the same snapshot cannot supply a second observation.
        if(tree.time<=lastObserved)return UNKNOWN;
        lastObserved=tree.time;
        lastState=UNKNOWN;lastStateAt=tree.time;
        boolean present=false,originalPresent=false,hidden=false;
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node node=tree.nodes.get(i);
            boolean original=structure!=null && !structure.isEmpty() && structure.equals(tree.structure(i));
            // Any visible same-role control prevents a disappearance claim,
            // even when its identity differs from the clicked control.
            if(node.visible && rule.equals(node.role)) {
                present=true;originalPresent|=original;
            }
            if(original && (!node.visible || node.role.isEmpty()))hidden=true;
        }
        if(present) {
            firstAbsent=-1;lastState=PRESENT;
            // A new touch needs two complete samples of the original chain.
            // Other controls and partial trees interrupt this separate proof.
            if(tree.complete && originalPresent){
                if(firstCompletePresence<0)firstCompletePresence=tree.time;
                lastCompletePresence=tree.time;
            }else{firstCompletePresence=-1;lastCompletePresence=-1;}
            return PRESENT;
        }
        firstCompletePresence=-1;lastCompletePresence=-1;
        // A rejected click candidate is not disappearance evidence. Missing
        // children, an empty provider, or a concealed old chain remain unknown.
        if(!tree.complete || tree.nodes.isEmpty() || hidden)return unknown();
        if(firstAbsent<0) {firstAbsent=tree.time;return UNKNOWN;}
        return tree.time-firstAbsent>=120?GONE:UNKNOWN;
    }
    private int unknown() {firstAbsent=-1;firstCompletePresence=-1;lastCompletePresence=-1;return UNKNOWN;}
}
