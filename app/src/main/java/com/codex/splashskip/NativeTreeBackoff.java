package com.codex.splashskip;

/** Per-scene backoff for expensive providers. Desktop/manual capture is unaffected. */
final class NativeTreeBackoff {
    private int incomplete;
    private long retryAt;
    private boolean priorityBudgetStarted;
    private long priorityBudgetAt;
    boolean shouldRead(long now,boolean manual){return manual || now>=retryAt;}
    /** Short hint reads cannot permanently replace the regular full native attempt. */
    int globalReadBudget(long now,boolean priority) {
        if(!priority)return 200;
        if(!priorityBudgetStarted || now<priorityBudgetAt) {
            priorityBudgetStarted=true;priorityBudgetAt=now;return 40;
        }
        if(now-priorityBudgetAt>=600){priorityBudgetAt=now;return 200;}
        return 40;
    }
    void observed(int nodes,boolean complete,long now) {
        if(complete){incomplete=0;retryAt=0;return;}
        // Empty startup windows may expose their real controls on the very next frame.
        if(nodes==0)return;
        // A timed-out provider must not consume the next frame's context budget
        // as well. Allow one full OCR pass, then retry; repeated failures back off longer.
        retryAt=now+(++incomplete==1?900:1800);
    }
}
