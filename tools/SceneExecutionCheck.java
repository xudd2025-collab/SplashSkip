package com.codex.splashskip;

import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Regression checks for the policy calls used by the live service, not screenshot classification. */
public final class SceneExecutionCheck {
    static void require(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS "+name);}
    public static void main(String[] args) {
        require(SceneFramePolicy.reject(500,850,true,true,true,false).isEmpty(),"fresh foreground frame accepted");
        require(SceneFramePolicy.reject(900,850,true,true,true,false).equals("stale-frame"),"expired OCR point rejected");
        require(SceneFramePolicy.reject(150,850,false,true,true,false).equals("foreground-changed"),"package epoch changed");
        require(SceneFramePolicy.reject(150,850,true,false,true,false).equals("not-foreground"),"background frame rejected");
        require(SceneFramePolicy.reject(150,850,true,true,false,false).equals("display-changed"),"rotation/resolution change rejects old point");
        require(SceneFramePolicy.reject(150,850,true,true,true,true).equals("node-already-clicked"),"node already acted after capture");
        Hit high=new Hit(UiControlPolicy.SKIP,200,300,.99f,720,1560).withModel(.99f);
        require(SceneFramePolicy.oneFrame(high,550),"verified OCR does not wait for a second frame");
        require(!SceneFramePolicy.oneFrame(high.withModel(.97f),550),"uncertain control cannot use fast click");
        require(!SceneFramePolicy.oneFrame(high,851),"old control cannot use fast click");
        require(!SceneFramePolicy.twoFrames(true,80) && SceneFramePolicy.twoFrames(true,130) && !SceneFramePolicy.twoFrames(false,350),"stable confirmation uses current target");
        require(!SceneFramePolicy.verifiedGone(ClickLearningSession.UNKNOWN,true),"recognition timeout is not click success");
        require(!SceneFramePolicy.verifiedGone(ClickLearningSession.ABSENT,false),"cancelled gesture is not click success");
        require(!SceneFramePolicy.verifiedGone(ClickLearningSession.PRESENT,true),"button remains after gesture");
        require(SceneFramePolicy.verifiedGone(ClickLearningSession.ABSENT,true),"completed action and absent control confirm result");
        require(SceneFramePolicy.opening(1500,false),"unconfirmed click retains launch semantics for retry");
        require(!SceneFramePolicy.opening(1500,true),"confirmed dismissal ends launch semantics");
        require(!SceneFramePolicy.opening(8000,false),"failed click cannot extend launch semantics indefinitely");
        require(!SceneFramePolicy.captureAfterAction(1000,true,true,0),"do not capture a retry while the gesture is in flight");
        require(!SceneFramePolicy.captureAfterAction(1150,false,true,1000),"allow the previous action to repaint before verification");
        require(SceneFramePolicy.captureAfterAction(1200,false,true,1000),"verification captures a new frame after completion");
        require(SceneFramePolicy.captureAfterAction(1000,false,false,0),"first action is not delayed by retry guard");
        require(WindowOwnershipPolicy.target("app","",7,7),"rootless active app is not an overlay");
        require(!WindowOwnershipPolicy.target("app","",8,7),"rootless foreign window remains protected");
        require(!WindowOwnershipPolicy.target("app","system",8,-1),"unknown ownership never bypasses overlay protection");
        require(WindowOwnershipPolicy.target("app","app",8,-1),"current package owns its window");
        UiFeatureSearch.setCancellation(()->true);
        int[] pixels=new int[608*800];Frame f=new Frame(pixels,608,800);Template t=new Template(new int[32*20],32,20,16,10);
        require(UiFeatureSearch.find(f,t,0,0,608,800,.5f)==null,"legacy fallback honors frame cancellation");UiFeatureSearch.setCancellation(null);
        require(!UiFeatureSearch.cancelled(),"cancellation scope is cleared for subsequent frames");
        int[] sampled={0};UiFeatureSearch.setCancellation(()->sampled[0]>4000);
        require(VisualComponents.boxes(f,1,1,607,799,(frame,x,y)->{sampled[0]++;return true;},10).isEmpty()
                && sampled[0]<10000,"component search stops mid-frame without returning partial targets");
        UiFeatureSearch.setCancellation(null);
        require(SceneFramePolicy.recognitionBudget(100,1)==240,"initial empty loading tree releases worker early");
        require(SceneFramePolicy.recognitionBudget(600,1)==760,"canvas apps regain full OCR budget after loading window");
        require(SceneFramePolicy.recognitionBudget(100,5)==760,"current populated controls retain full verification budget");
        require(SceneFramePolicy.leftScene("video.app","launcher.app","launcher.app"),"foreground home event resets launch immediately");
        require(SceneFramePolicy.leftScene("video.app","helper.app","helper.app"),"return to helper resets launch immediately");
        require(!SceneFramePolicy.leftScene("video.app","background.app","video.app"),"background event cannot reset click guard");
        require(!SceneFramePolicy.leftScene("video.app","com.android.systemui","com.android.systemui"),"transient system overlay cannot reset click guard");
        require(!SceneFramePolicy.leftScene("video.app","video.app","video.app"),"in-app content change cannot reset click guard");
        require(SceneFramePolicy.idleNativeCloseProbe("com.youku.phone",true,true,false,10000,8000),
                "idle foreground Youku can discover a late pause close without content events");
        require(!SceneFramePolicy.idleNativeCloseProbe("other.app",true,true,false,10000,8000),
                "idle probe is limited to the observed video app");
        require(!SceneFramePolicy.idleNativeCloseProbe("com.youku.phone",false,true,false,10000,8000),
                "disabled ad close cannot start an idle probe");
        require(!SceneFramePolicy.idleNativeCloseProbe("com.youku.phone",true,false,false,10000,8000),
                "active burst cannot schedule a second idle worker");
        require(!SceneFramePolicy.idleNativeCloseProbe("com.youku.phone",true,true,true,10000,8000),
                "pending scan action or verification prevents an idle probe");
        require(!SceneFramePolicy.idleNativeCloseProbe("com.youku.phone",true,true,false,9499,8000) &&
                SceneFramePolicy.idleNativeCloseProbe("com.youku.phone",true,true,false,9500,8000) &&
                !SceneFramePolicy.idleNativeCloseProbe("com.youku.phone",true,true,false,7999,8000),
                "idle scans are bounded by spacing and cannot use a future frame time");
        require(SceneFramePolicy.pageCloseMayReappear(UiControlPolicy.CLOSE_AD,NativeTreeObservation.GONE),
                "confirmed gone page close permits a later pause ad at the same place");
        require(!SceneFramePolicy.pageCloseMayReappear(UiControlPolicy.CLOSE_AD,NativeTreeObservation.PRESENT) &&
                !SceneFramePolicy.pageCloseMayReappear(UiControlPolicy.CLOSE_AD,NativeTreeObservation.UNKNOWN),
                "present or unknown controls retain repeat-click protection");
        require(!SceneFramePolicy.pageCloseMayReappear(UiControlPolicy.SKIP,NativeTreeObservation.GONE) &&
                !SceneFramePolicy.pageCloseMayReappear(UiControlPolicy.CLOSE,NativeTreeObservation.GONE),
                "a later page ad cannot clear splash action protection");
        NativeTreeBackoff backoff=new NativeTreeBackoff();
        backoff.observed(30,false,100);require(!backoff.shouldRead(450,false),"first timeout reserves next frame for OCR");
        require(backoff.shouldRead(1000,false),"first timeout has bounded native retry");
        backoff.observed(40,false,1100);require(!backoff.shouldRead(2000,false),"repeated incomplete tree releases OCR budget");
        require(backoff.shouldRead(1000,true),"manual capture bypasses runtime backoff");
        require(backoff.shouldRead(2900,false),"provider retried after bounded backoff");
        backoff.observed(80,true,2900);require(backoff.shouldRead(2901,false),"complete tree restores normal reads");
        NativeTreeBackoff loading=new NativeTreeBackoff();loading.observed(0,false,100);loading.observed(0,false,300);
        require(loading.shouldRead(301,false),"empty loading window does not delay future native controls");
        NativeTreeBackoff priority=new NativeTreeBackoff();
        require(priority.globalReadBudget(1000,true)==40,"priority native discovery starts with a short hint read");
        require(priority.globalReadBudget(1599,true)==40,"priority hint budget remains short before 600 ms");
        require(priority.globalReadBudget(1600,true)==200,"priority native discovery preserves a full read at the 600 ms boundary");
        require(priority.globalReadBudget(1601,true)==40 && priority.globalReadBudget(2199,true)==40,
                "a full priority read starts a new bounded short-read interval");
        require(priority.globalReadBudget(2200,true)==200,"priority discovery continues periodic full reads when queries never expose a control");
        require(priority.globalReadBudget(2100,true)==40 && priority.globalReadBudget(2699,true)==40 &&
                priority.globalReadBudget(2700,true)==200,"backward time resets the priority anchor without starving a later full read");
        NativeTreeBackoff zeroStart=new NativeTreeBackoff();
        require(zeroStart.globalReadBudget(0,true)==40 && zeroStart.globalReadBudget(599,true)==40 &&
                zeroStart.globalReadBudget(600,true)==200,"a zero uptime origin still records the first priority anchor");
        NativeTreeBackoff delayed=new NativeTreeBackoff();
        require(delayed.globalReadBudget(1000,true)==40 && delayed.globalReadBudget(3400,true)==200 &&
                delayed.globalReadBudget(3401,true)==40 && delayed.globalReadBudget(4000,true)==200,
                "a delayed full read reanchors at its actual time instead of scheduling catch-up full reads");
        NativeTreeBackoff ordinary=new NativeTreeBackoff();
        require(ordinary.globalReadBudget(100,false)==200 && ordinary.globalReadBudget(1000,false)==200 &&
                ordinary.globalReadBudget(2000,true)==40,"ordinary native reads always retain 200 ms and do not start a priority interval");
        require(ordinary.globalReadBudget(2200,false)==200 && ordinary.globalReadBudget(2599,true)==40 &&
                ordinary.globalReadBudget(2600,true)==200,"ordinary reads do not postpone the bounded priority full-read deadline");
        NativeTreeBackoff budgetAfterObservation=new NativeTreeBackoff();
        require(budgetAfterObservation.globalReadBudget(1000,true)==40,"priority budget records its initial scheduling anchor");
        budgetAfterObservation.observed(0,false,1100);budgetAfterObservation.observed(80,true,1500);
        require(budgetAfterObservation.globalReadBudget(1599,true)==40 && budgetAfterObservation.globalReadBudget(1600,true)==200,
                "empty or complete observations cannot reset the periodic global candidate opportunity");
        require(SceneFramePolicy.deferFallback(true,"needs-detail"),"unfinished opening OCR takes fresh pixels before legacy page search");
        require(!SceneFramePolicy.deferFallback(false,"needs-detail"),"in-app page close rules remain available");
        require(!SceneFramePolicy.deferFallback(true,"no-verified-control"),"completed OCR miss still permits legacy fallback");
    }
}
