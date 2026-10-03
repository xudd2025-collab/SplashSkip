package com.codex.splashskip;

/** Timing rules used by the live service; kept independent of Android for execution-path regression checks. */
final class SceneFramePolicy {
    static boolean deferFallback(boolean opening,String textStatus) {
        // The coarse pass requested fresh pixels for detail. Searching the same
        // loading image with page rules delays the next frame without resolving it.
        return opening && "needs-detail".equals(textStatus);
    }
    static int recognitionBudget(long sceneAge,int treeNodes) {
        // The first empty loading window must not occupy a full OCR cycle while
        // the real ad appears. This short budget ends after the initial 600 ms.
        return sceneAge>=0 && sceneAge<600 && treeNodes<=1?240:760;
    }
    static String reject(long age,long maxAge,boolean epoch,boolean foreground,boolean display,boolean nodeClosed) {
        if(!epoch)return "foreground-changed";
        if(!foreground)return "not-foreground";
        if(!display)return "display-changed";
        if(nodeClosed)return "node-already-clicked";
        return age<0 || age>maxAge?"stale-frame":"";
    }
    static boolean oneFrame(BilibiliVisualMatcher.Hit hit,long age) {
        return hit!=null && UiControlPolicy.isControl(hit.rule) && (hit.nativeOnly || hit.modelProbability>=.985f) && age>=0 && age<=850;
    }
    static boolean needsVisualRecheck(BilibiliVisualMatcher.Hit hit,boolean rechecked) {
        return UiControlPolicy.isControl(hit.rule) && !hit.nativeOnly && !rechecked;
    }
    static boolean allowsGesture(BilibiliVisualMatcher.Hit hit){return !hit.nativeOnly;}
    static boolean tryNative(BilibiliVisualMatcher.Hit hit) {
        return UiControlPolicy.isControl(hit.rule) && (hit.nativeOnly || hit.treeAssisted);
    }
    static boolean leftScene(String tracked,String eventPackage,String foreground) {
        return !tracked.isEmpty() && !tracked.equals(foreground) && eventPackage.equals(foreground)
            && !foreground.isEmpty() && !foreground.equals("com.android.systemui");
    }
    static boolean twoFrames(boolean same,long elapsed) {return same && elapsed>=120 && elapsed<1800;}
    static boolean verifiedGone(int state,boolean gestureCompleted) {return gestureCompleted && state==ClickLearningSession.ABSENT;}
    static boolean opening(long elapsed,boolean confirmedGone) {return elapsed>=0 && elapsed<8000 && !confirmedGone;}
    static boolean captureAfterAction(long now,boolean pending,boolean verifying,long completed) {
        return !pending && (!verifying || completed==0 || now>=completed+200);
    }
}
