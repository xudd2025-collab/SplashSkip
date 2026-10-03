package com.codex.splashskip;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SkipService extends AccessibilityService {
    private static volatile boolean running,ocrReady;
    private static volatile SkipService desktopService;
    static SkipService desktopInstance(){return running?desktopService:null;}
    private volatile long desktopEventSequence;
    long desktopGeneration(){return desktopEventSequence;}
    static boolean running(){return running;}
    static boolean ocrReady(){return running && ocrReady;}
    private static final long SCAN_WINDOW_MS = 8_000;
    private static final long SCAN_INTERVAL_MS = 180;
    private static final long CLICK_COOLDOWN_MS = 8_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;
    private String currentPackage = "";
    private long activeUntil;
    private long lastScan;
    private long lastClick;
    private long lastScreenshot;
    private long lastProfileEvent;
    private long lastDiagnostic;
    private long lastEventTrace;
    private boolean firstFrame;
    private VisualRuleMatcher.Match verifyMatch;
    private long verifyAfter;
    private VisualRuleMatcher.Match stableCandidate;
    private long stableCandidateAt;
    private VisualRuleMatcher.Match popupStable, popupVerify;
    private long popupStableAt, popupVerifyAfter;
    private int popupAttempts, popupEmptyFrames;
    private String popupPackage = "";
    private long lastSplashClick;
    private long lastPopupClick;
    private long screenshotSerial;
    private boolean screenshotPending;
    private VisualRuleMatcher visualRules;
    private SkipTextModel textModel;
    private SensorGuardController sensorGuard;
    private BilibiliRules biliRules;
    private final ExecutorService biliWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService sceneWorker = Executors.newSingleThreadExecutor();
    private Runnable biliPoll;
    private long biliUntil, lastBiliScan, lastBiliFrame, lastBiliClick, lastBiliAdClick, lastBiliLiveClick;
    private boolean biliFramePending;
    private volatile boolean destroyed;
    private BilibiliVisualMatcher.Hit biliStable, biliVerify;
    private long biliStableAt;
    private InAppSceneRules sceneRules;
    private Runnable scenePoll, foregroundPoll;
    private boolean scenePending;
    private volatile long sceneEpoch;
    private volatile NativeTreeBackoff nativeTreeBackoff=new NativeTreeBackoff();
    private long lastSceneFrame, sceneStableAt;
    private volatile long lastSceneClick;
    private String scenePackage = "";
    private BilibiliVisualMatcher.Hit sceneStable, sceneLastClicked;
    private volatile BilibiliVisualMatcher.Hit sceneVerify;
    private int sceneAttempts;
    private int sceneEmptyFrames;
    private long lastSceneTimingTrace;
    private long sceneUntil;
    private long sceneStarted;
    private long lastSceneContentEvent;
    private volatile ClickLearningSession sceneLearning;
    private volatile long sceneGestureCompletedAt;
    private boolean sceneNodeTried,gesturePending,captureRequested,sceneLaunchDismissed;
    private boolean sceneOpeningActionSent,sceneRecheckPending;
    private long sceneRecheckOrigin;
    private long gestureSerial,lastCaptureRequest;
    private volatile BilibiliVisualMatcher.Hit manualJointHit;
    private volatile String manualJointPackage="";
    private volatile long manualJointEpoch,manualJointFrame;

    private boolean manualCapture() {
        android.content.SharedPreferences p=getSharedPreferences("settings",MODE_PRIVATE);
        boolean enabled=p.getBoolean("capture_click",false);
        if(enabled && System.currentTimeMillis()>p.getLong("capture_until",0)) {
            p.edit().putBoolean("capture_click",false).apply();return false;
        }
        return enabled;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        running=true;ocrReady=false;destroyed=false;desktopService=this;
        if (textModel != null) { textModel.close(); textModel = null; }
        sensorGuard = SensorGuardController.get(this);
        sensorGuard.connect();
        visualRules = new VisualRuleMatcher(this);
        biliRules = new BilibiliRules(this);
        sceneRules = new InAppSceneRules(this);
        sceneWorker.execute(() -> {sceneRules.prepareText();ocrReady=sceneRules.textReady();});
        if (foregroundPoll != null) handler.removeCallbacks(foregroundPoll);
        foregroundPoll = new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                KeyguardManager keyguard = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
                boolean locked=keyguard != null && keyguard.isKeyguardLocked();
                AccessibilityNodeInfo root=locked?null:activeRoot();
                if (locked) { sensorGuard.foreground("");if(!scenePackage.isEmpty())leaveScene(); }
                else if (!foregroundPackage(root).isEmpty()) {
                    String foreground = foregroundPackage(root);
                    sensorGuard.foreground(foreground);
                    if (InAppSceneRules.watches(SkipService.this,foreground)) onSceneEvent(foreground);
                    else if(!foreground.equals(scenePackage) && !scenePackage.isEmpty())leaveScene();
                    AppProfiles.Profile profile = AppProfiles.find(SkipService.this, foreground);
                    if (profile != null && profile.popup != null && visualEnabled()) {
                        if (!foreground.equals(popupPackage)) {
                            popupPackage = foreground; popupStable = null; popupVerify = null;
                            popupAttempts = 0; popupEmptyFrames = 0; lastPopupClick = 0;
                            trace("popup watch started " + foreground);
                        }
                        scanProfileImage(foreground, SystemClock.uptimeMillis());
                    } else {
                        popupPackage = ""; popupStable = null; popupVerify = null; popupAttempts = 0;
                    }
                }
                // Accessibility events start scans immediately; this is only a slower foreground fallback.
                // The scene poll already verifies the foreground; avoid duplicate root queries during it.
                handler.postDelayed(this, 2000);
            }
        };
        handler.post(foregroundPoll);
        try {
            textModel = new SkipTextModel(this);
        } catch (Exception | LinkageError error) {
            Log.e("SplashSkipModel", "Model could not be loaded", error);
        }
        trace("service connected; model=" + (textModel != null));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        desktopEventSequence++;
        String pkg = event.getPackageName().toString();
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                InAppSceneRules.watches(this,pkg) && manualCapture()) {
            AccessibilityNodeInfo source = event.getSource();
            Rect bounds = new Rect();
            String id = "";
            String label = "";
            if (source != null) {
                source.getBoundsInScreen(bounds);
                id = value(source.getViewIdResourceName());
                label = value(source.getText()) + " " + value(source.getContentDescription());
            }
            String detail = "class=" + value(event.getClassName()) +
                    " id=" + id + " label=" + label.trim() + " bounds=" + bounds +
                    " source=" + (source != null);
            Log.i("SplashSkipTap", detail);
            BilibiliVisualMatcher.Hit candidate=manualJointHit;
            boolean paired=candidate!=null && pkg.equals(manualJointPackage) && sceneEpoch==manualJointEpoch &&
                SystemClock.uptimeMillis()-manualJointFrame<=900 && event.getEventTime()>=manualJointFrame &&
                bounds.contains(candidate.x,candidate.y) && bounds.width()<=candidate.frameWidth*.48f && bounds.height()<=Math.min(candidate.frameWidth,candidate.frameHeight)*.15f;
            if(paired) {
                String record=JointLearningStore.get(this).record(pkg,sceneEpoch,candidate);
                JointLearningStore.get(this).outcome(record,"unknown","manual");
                detail+="\n已关联点击前控件与视觉特征；请在特征库中确认结果。";
            } else detail+="\n没有新鲜的点击前特征，本次事件不进入训练集。";
            manualJointHit=null;
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("capture_click", false)
                    .putString("last_tap", detail).apply();
        }
        // These self-drawn apps are already being polled. Re-reading their root for every
        // animation/content event can block delivery of the screenshot and gesture callbacks.
        if(pkg.equals(scenePackage) &&
                (event.getEventType()==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                 event.getEventType()==AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            long changed=SystemClock.uptimeMillis();
            // A new self-drawn ad after an idle period should not wait for the 1.5s poll.
            // Continuous animation events do not extend this burst indefinitely.
            if(changed>sceneUntil && changed-lastSceneContentEvent>1000) {
                sceneUntil=changed+1800;
                wakeScenePoll();
            }
            lastSceneContentEvent=changed;
            return;
        }
        // Accessibility events from background windows must not replace the foreground app
        // and cancel the short screenshot polling window.
        AccessibilityNodeInfo activeRoot = activeRoot();
        boolean rootMatches = pkg.equals(foregroundPackage(activeRoot));
        // Clear the previous launch immediately on a confirmed foreground departure.
        // Waiting for the two-second poll misses quick home/helper -> app relaunches.
        if(SceneFramePolicy.leftScene(scenePackage,pkg,foregroundPackage(activeRoot)))leaveScene();
        KeyguardManager keyguard = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if (sensorGuard != null) {
            if (keyguard != null && keyguard.isKeyguardLocked()) sensorGuard.foreground("");
            else if (activeRoot != null && activeRoot.getPackageName() != null)
                sensorGuard.foreground(activeRoot.getPackageName().toString());
        }
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui") ||
                pkg.startsWith("com.android.settings")) return;
        if (pkg.equals(BilibiliRules.PACKAGE) && rootMatches) {
            if(!pkg.equals(scenePackage)){biliStable=null;biliVerify=null;lastBiliAdClick=0;lastBiliLiveClick=0;}
            onSceneEvent(pkg,event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            onBiliEvent();
            return;
        }
        if (InAppSceneRules.watches(this,pkg) && rootMatches) {
            long eventNow=SystemClock.uptimeMillis();
            boolean newPage=event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getEventType()==AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.getEventType()==AccessibilityEvent.TYPE_VIEW_CLICKED;
            if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                newPage=eventNow-lastSceneContentEvent>1000;lastSceneContentEvent=eventNow;
            }
            onSceneEvent(pkg,newPage);
            // Ordinary splash nodes still work; in-app screenshots have their own lifetime.
            if (!pkg.equals(currentPackage)) { currentPackage = pkg; activeUntil = SystemClock.uptimeMillis() + SCAN_WINDOW_MS; lastClick = 0; }
            scan(pkg);
            if(AppProfiles.find(this,pkg)==null)return;
        }
        if (AppProfiles.find(this, pkg) != null &&
                SystemClock.uptimeMillis() - lastEventTrace > 750) {
            lastEventTrace = SystemClock.uptimeMillis();
            trace("event=" + event.getEventType() + " root=" +
                    (activeRoot == null ? "null" : activeRoot.getPackageName()) +
                    " tracked=" + currentPackage + " remaining=" + (activeUntil - lastEventTrace));
        }
        // A registered app launch event can arrive before its root window is ready.
        // Start polling from that event; the screenshot callback checks the root again.
        if (!rootMatches && AppProfiles.find(this, pkg) == null) return;
        if (pkg.contains("launcher")) {
            currentPackage = "";
            return;
        }
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("enabled", true)) return;
        long now = SystemClock.uptimeMillis();
        boolean newLaunch = !pkg.equals(currentPackage) ||
                (AppProfiles.find(this, pkg) != null && now > activeUntil &&
                (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                 event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                 now - lastProfileEvent > 2_000));
        if (AppProfiles.find(this, pkg) != null) lastProfileEvent = now;
        if (newLaunch) {
            currentPackage = pkg;
            activeUntil = now + (AppProfiles.find(this, pkg) != null ?
                    AppProfiles.find(this, pkg).scanWindowMs : SCAN_WINDOW_MS);
            lastClick = 0;
            lastSplashClick = 0;
            lastPopupClick = 0;
            firstFrame = true;
            verifyMatch = null;
            stableCandidate = null;
            schedulePoll(pkg);
            if (AppProfiles.find(this, pkg) != null) {
                trace("scan window started event=" + event.getEventType());
            }
        }
        if (AppProfiles.find(this, pkg) != null &&
                AppProfiles.enabled(this)) {
            scanProfileImage(pkg, now);
        }
        scan(pkg);
    }

    private void schedulePoll(String pkg) {
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
        pollRunnable = new Runnable() {
            @Override public void run() {
                if (!pkg.equals(currentPackage) || SystemClock.uptimeMillis() > activeUntil) return;
                if (AppProfiles.find(SkipService.this, pkg) != null &&
                        AppProfiles.enabled(SkipService.this)) {
                    scanProfileImage(pkg, SystemClock.uptimeMillis());
                }
                scan(pkg);
                handler.postDelayed(this, 350);
            }
        };
        handler.postDelayed(pollRunnable, 350);
    }

    private boolean sceneForeground(String pkg) {
        KeyguardManager keyguard = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) return false;
        AccessibilityNodeInfo root = activeRoot();
        return pkg.equals(foregroundPackage(root));
    }
    private String foregroundPackage(AccessibilityNodeInfo root) {
        if(root!=null && root.getPackageName()!=null && !"com.android.systemui".contentEquals(root.getPackageName()))return root.getPackageName().toString();
        // Self-drawn windows can temporarily omit their root during launch. Validate against
        // the active application window rather than waiting for the 2-second foreground fallback.
        try {
            AccessibilityWindowInfo chosen=null;
            for(AccessibilityWindowInfo window:getWindows())if(window.getType()==AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (window.isActive() || window.isFocused()) && (chosen==null || window.getLayer()>chosen.getLayer()))chosen=window;
            if(chosen!=null) {
                AccessibilityNodeInfo owner=chosen.getRoot();
                if(owner!=null && owner.getPackageName()!=null)return owner.getPackageName().toString();
            }
        } catch(RuntimeException ignored) { }
        return "";
    }
    private void onSceneEvent(String pkg) { onSceneEvent(pkg,false); }
    private void onSceneEvent(String pkg,boolean newPage) {
        // Both callers already checked this package against the foreground root in this event.
        if (!InAppSceneRules.enabled(this,pkg)) return;
        if (!pkg.equals(scenePackage)) {
            scenePackage=pkg; sceneEpoch++; sceneStable=null; sceneVerify=null; sceneLastClicked=null; sceneAttempts=0; sceneEmptyFrames=0;sceneLearning=null;
            nativeTreeBackoff=new NativeTreeBackoff();
            lastSceneClick=0;sceneStarted=SystemClock.uptimeMillis();sceneUntil=sceneStarted+SCAN_WINDOW_MS;
            sceneNodeTried=false;sceneGestureCompletedAt=0;sceneLaunchDismissed=false;
            sceneRules.beginTextScene();
            sceneOpeningActionSent=false;
            trace("scene watch started "+pkg+" generic="+!InAppSceneRules.supports(pkg));
        }
        else if(newPage && SystemClock.uptimeMillis()>sceneUntil) {
            sceneUntil=SystemClock.uptimeMillis()+2500;
            wakeScenePoll();
        }
        if(!InAppSceneRules.supports(pkg) && !InAppSceneRules.genericPages(this) && SystemClock.uptimeMillis()>sceneUntil)return;
        if(!pkg.equals(currentPackage)) { currentPackage=pkg; activeUntil=SystemClock.uptimeMillis()+SCAN_WINDOW_MS; lastClick=0; }
        if (scenePoll != null || SystemClock.uptimeMillis()>sceneUntil && sceneVerify==null && sceneLearning==null) return;
        scenePoll=new Runnable() {
            @Override public void run() {
                if (destroyed || !sceneForeground(scenePackage) || !InAppSceneRules.enabled(SkipService.this,scenePackage)) {
                    scenePoll=null; scenePackage=""; sceneEpoch++; sceneStable=null; sceneVerify=null;sceneLearning=null; return;
                }
                if(!InAppSceneRules.supports(scenePackage) && !InAppSceneRules.genericPages(SkipService.this) && SystemClock.uptimeMillis()>sceneUntil) {
                    scenePoll=null;sceneEpoch++;sceneStable=null;sceneVerify=null;sceneLearning=null;return;
                }
                if(SystemClock.uptimeMillis()>sceneUntil && sceneVerify==null && sceneLearning==null) {
                    scenePoll=null;trace("scene visual watch idle "+scenePackage);return;
                }
                scanScene(scenePackage); handler.postDelayed(this,sceneInterval(scenePackage));
                if(!InAppSceneRules.supports(scenePackage))scan(scenePackage);
            }
        };
        handler.post(scenePoll);
    }
    private void leaveScene() {
        if(scenePoll!=null)handler.removeCallbacks(scenePoll);
        scenePoll=null;scenePackage="";sceneEpoch++;sceneStable=null;sceneVerify=null;sceneLearning=null;
    }
    private long sceneInterval(String pkg) {
        if(SystemClock.uptimeMillis()>sceneUntil)return 1500;
        return InAppSceneRules.HUYA.equals(pkg) || InAppSceneRules.TENCENT.equals(pkg) || InAppSceneRules.mobilePackage(pkg) || !InAppSceneRules.supports(pkg)?350:700;
    }
    private void wakeScenePoll() {
        if(destroyed)return;
        if(scenePoll==null) {if(!scenePackage.isEmpty() && SystemClock.uptimeMillis()<=sceneUntil)onSceneEvent(scenePackage,false);return;}
        handler.removeCallbacks(scenePoll);
        handler.postDelayed(scenePoll,Math.max(1,lastSceneFrame+sceneInterval(scenePackage)-SystemClock.uptimeMillis()));
    }
    private void scanScene(String pkg) {
        long requested=SystemClock.uptimeMillis(), epoch=sceneEpoch;
        final NativeTreeBackoff treeSchedule=nativeTreeBackoff;
        if(sceneRecheckPending)return;
        if(!SceneFramePolicy.captureAfterAction(requested,gesturePending,sceneVerify!=null,sceneGestureCompletedAt))return;
        if (android.os.Build.VERSION.SDK_INT<30 || scenePending || requested-lastSceneFrame<sceneInterval(pkg)) return;
        if(!reserveScreenshot(requested))return;
        final boolean splashPriority=requested-sceneStarted<1800 && sceneVerify==null && sceneLastClicked==null;
        // Submitting a click is not proof that the splash was dismissed. Keep current-frame
        // launch semantics available for a bounded retry until disappearance is verified.
        final boolean openingWindow=SceneFramePolicy.opening(requested-sceneStarted,sceneLaunchDismissed);
        lastSceneFrame=requested; scenePending=true;
        try { takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                captureRequested=false;
                HardwareBuffer buffer=result.getHardwareBuffer();
                final long captured=SystemClock.uptimeMillis();
                try { sceneWorker.execute(() -> {
                    final long started=SystemClock.uptimeMillis();
                    Bitmap wrapped=null, screen=null; BilibiliVisualMatcher.Hit hit=null;
                    ClickLearningSession learning=sceneLearning;int buttonState=ClickLearningSession.UNKNOWN;
                    BilibiliVisualMatcher.Hit verifying=sceneVerify;int verifyState=ClickLearningSession.UNKNOWN;
                    try {
                        wrapped=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());
                        if (wrapped!=null) screen=wrapped.copy(Bitmap.Config.ARGB_8888,false);
                        boolean processed=screen!=null && SystemClock.uptimeMillis()-requested<760 && epoch==sceneEpoch && !destroyed;
                        if (processed) {
                            ControlTree.Snapshot tree=null;
                            if(treeSchedule.shouldRead(SystemClock.uptimeMillis(),manualCapture())) {
                                AccessibilityNodeInfo root=getRootInActiveWindow();
                                try{tree=AccessibilityControlTree.capture(root,pkg,epoch,screen.getWidth(),screen.getHeight());}
                                finally{if(root!=null)root.recycle();}
                                treeSchedule.observed(tree.nodes.size(),tree.complete,SystemClock.uptimeMillis());
                                if(requested-sceneStarted<SCAN_WINDOW_MS)trace("joint tree nodes="+tree.nodes.size()+" controls="+tree.controls(java.util.Collections.emptyMap()).size()+" complete="+tree.complete+" elapsed="+(SystemClock.uptimeMillis()-tree.time)+"ms frame-age="+(SystemClock.uptimeMillis()-requested)+"ms");
                                if(!tree.current(pkg,sceneEpoch,SystemClock.uptimeMillis(),screen.getWidth(),screen.getHeight()) || SystemClock.uptimeMillis()-requested>350)tree=null;
                            } else if(requested-sceneStarted<SCAN_WINDOW_MS)trace("native tree provider backoff; full frame budget reserved for OCR");
                            if(manualCapture() && tree!=null) {
                                List<ControlTree.Hint> hints=tree.controls(java.util.Collections.emptyMap());
                                if(hints.size()==1) {
                                    ControlTree.Hint hint=hints.get(0);int w=screen.getWidth(),h=screen.getHeight();int[] pixels=new int[w*h];screen.getPixels(pixels,0,w,0,0,w,h);
                                    BilibiliVisualMatcher.Hit sample=new BilibiliVisualMatcher.Hit(hint.action,(hint.box[0]+hint.box[2])/2,(hint.box[1]+hint.box[3])/2,0,w,h).withTextBounds(hint.box[0],hint.box[1],hint.box[2],hint.box[3]);
                                    sample.structure=hint.structure;sample.jointFeatures=JointControlModel.features(pixels,w,h,hint.box,hint);
                                    manualJointPackage=pkg;manualJointEpoch=epoch;manualJointFrame=requested;manualJointHit=sample;
                                }
                            }
                            int recognitionBudget=SceneFramePolicy.recognitionBudget(requested-sceneStarted,tree==null?0:tree.nodes.size());
                            hit=sceneRules.image(SkipService.this,pkg,screen,()->destroyed || epoch!=sceneEpoch,splashPriority,openingWindow,requested+recognitionBudget,tree);
                            if(manualCapture() && hit!=null && hit.jointFeatures!=null) {
                                manualJointPackage=pkg;manualJointEpoch=epoch;manualJointFrame=requested;manualJointHit=hit;
                            }
                        }
                        else sceneRules.clearTextFrame();
                        if(processed && verifying!=null && requested>=lastSceneClick+120)
                            verifyState=sameScene(hit,verifying)?ClickLearningSession.PRESENT:sceneRules.observeButton(screen,verifying);
                        if(screen!=null && learning!=null && learning==sceneLearning && learning.completedAt>0 && requested>=learning.completedAt+120 && !learning.expired(requested))
                            buttonState=hit==null?sceneRules.observeButton(screen,learning.hit):sameScene(hit,learning.hit)?ClickLearningSession.PRESENT:ClickLearningSession.UNKNOWN;
                    } catch (RuntimeException error) { trace("scene error: "+error.getClass().getSimpleName()); }
                    finally { if(screen!=null)screen.recycle(); if(wrapped!=null)wrapped.recycle(); buffer.close(); }
                    final BilibiliVisualMatcher.Hit found=hit;
                    final ClickLearningSession observed=learning;final int observedState=buttonState;
                    final int verification=verifyState;
                    final String recognitionStatus=sceneRules.textStatus();
                    final String recognitionSummary=sceneRules.textSummary();
                    final long finished=SystemClock.uptimeMillis();
                    handler.post(() -> {
                        scenePending=false;
                        if(epoch==sceneEpoch && (found!=null || requested-sceneStarted<SCAN_WINDOW_MS || SystemClock.uptimeMillis()-lastSceneTimingTrace>5000)) {
                            lastSceneTimingTrace=SystemClock.uptimeMillis();
                            trace("scene timing pkg="+pkg+" capture="+(captured-requested)+" queue="+(started-captured)+
                                    " match="+(finished-started)+" callback="+(SystemClock.uptimeMillis()-finished)+"ms hit="+(found==null?"none":found.rule));
                            trace(recognitionStatus);
                        }
                        applyLearning(pkg,epoch,requested,found,observed,observedState);
                        applyScene(pkg,epoch,requested,found,verification,recognitionSummary);
                        wakeScenePoll();
                    });
                }); } catch(RuntimeException error) { buffer.close(); scenePending=false;trace("scene worker unavailable: "+error.getClass().getSimpleName()); }
            }
            @Override public void onFailure(int error) { captureRequested=false;scenePending=false;trace("scene screenshot unavailable: "+error); }
        }); } catch(RuntimeException error) { captureRequested=false;scenePending=false;trace("scene screenshot failed: "+error.getClass().getSimpleName()); }
    }
    private boolean reserveScreenshot(long now) {
        if(captureRequested || now-lastCaptureRequest<350)return false;
        captureRequested=true;lastCaptureRequest=now;return true;
    }
    private boolean sameScene(BilibiliVisualMatcher.Hit a,BilibiliVisualMatcher.Hit b) {
        int tolerance=Math.max(18,getResources().getDisplayMetrics().widthPixels/50);
        return a!=null && b!=null && a.rule.equals(b.rule) && a.frameWidth==b.frameWidth && a.frameHeight==b.frameHeight &&
                Math.abs(a.x-b.x)<tolerance && Math.abs(a.y-b.y)<tolerance;
    }
    private void applyLearning(String pkg,long epoch,long requested,BilibiliVisualMatcher.Hit found,ClickLearningSession session,int state) {
        if(session==null || session!=sceneLearning)return;
        long now=SystemClock.uptimeMillis();
        if(session.expired(now) || epoch!=sceneEpoch || session.epoch!=epoch || !pkg.equals(session.pkg) || !sceneForeground(pkg) || !sceneDisplayMatches(session.hit)) {sceneLearning=null;return;}
        boolean clear=now-requested<=700 && touchTargetClear(pkg,session.hit);
        if(session.observe(requested,state,found!=null,clear)) {
            sceneLearning=null;
            JointLearningStore.get(this).outcome(session.recordId,"success","two_clear_frames");
            sceneWorker.execute(() -> sceneRules.remember(pkg,session.hit));
            trace("button feature learned pkg="+pkg+" rule="+session.hit.rule+" verifiedFrames=2");
        } else if(session.ineffective(requested,state,clear)) {
            JointLearningStore.get(this).outcome(session.recordId,"no_effect","two_present_frames");
            sceneLearning=null;
        }
    }
    private boolean touchTargetClear(String pkg,BilibiliVisualMatcher.Hit hit) {
        return touchTargetClear(pkg,hit,activeRoot());
    }
    private boolean touchTargetClear(String pkg,BilibiliVisualMatcher.Hit hit,AccessibilityNodeInfo active) {
        try {
            List<AccessibilityWindowInfo> windows=getWindows();int targetLayer=Integer.MIN_VALUE,activeId=-1;
            if(active!=null && active.getPackageName()!=null && pkg.contentEquals(active.getPackageName()))activeId=active.getWindowId();
            List<AccessibilityNodeInfo> roots=new ArrayList<>();List<String> owners=new ArrayList<>();
            for(AccessibilityWindowInfo window:windows) {
                AccessibilityNodeInfo root=window.getRoot();String owner=root==null?"":value(root.getPackageName());
                roots.add(root);owners.add(owner);
                if(WindowOwnershipPolicy.target(pkg,owner,window.getId(),activeId))targetLayer=Math.max(targetLayer,window.getLayer());
            }
            for(int index=0;index<windows.size();index++) {
                AccessibilityWindowInfo window=windows.get(index);
                if(window.getLayer()<=targetLayer)continue;
                AccessibilityNodeInfo root=roots.get(index);String owner=owners.get(index);
                if(WindowOwnershipPolicy.target(pkg,owner,window.getId(),activeId))continue;
                Rect bounds=new Rect();window.getBoundsInScreen(bounds);
                long area=(long)bounds.width()*bounds.height(),screenArea=(long)hit.frameWidth*hit.frameHeight;
                if(area<screenArea*.75f) {
                    if(TouchTargetGuard.covered(hit,new int[]{bounds.left,bounds.top,bounds.right,bounds.bottom})){trace("overlay owner="+owner+" window="+window.getId()+" bounds="+bounds);return false;}
                } else if(window.isFocused() || visibleOverlayCovers(root,hit,0,new int[]{0})){trace("overlay owner="+owner+" window="+window.getId()+" focused="+window.isFocused());return false;}
            }
            return true;
        } catch(RuntimeException error) {return false;}
    }
    private boolean visibleOverlayCovers(AccessibilityNodeInfo node,BilibiliVisualMatcher.Hit hit,int depth,int[] visited) {
        if(node==null || depth>15 || ++visited[0]>120)return false;
        if(node.isVisibleToUser() && (node.isClickable() || node.isFocusable() || node.getRangeInfo()!=null || node.getChildCount()==0)) {
            Rect bounds=new Rect();node.getBoundsInScreen(bounds);
            if((long)bounds.width()*bounds.height()<(long)hit.frameWidth*hit.frameHeight*.65f &&
                    TouchTargetGuard.covered(hit,new int[]{bounds.left,bounds.top,bounds.right,bounds.bottom}))return true;
        }
        for(int i=0;i<node.getChildCount();i++)if(visibleOverlayCovers(node.getChild(i),hit,depth+1,visited))return true;
        return false;
    }
    private boolean sceneDisplayMatches(BilibiliVisualMatcher.Hit hit) {
        if(hit==null)return true;
        android.view.Display display=getSystemService(android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null)return false;
        android.graphics.Point size=new android.graphics.Point();display.getRealSize(size);
        return size.x==hit.frameWidth && size.y==hit.frameHeight;
    }
    private void applyScene(String pkg,long epoch,long requested,BilibiliVisualMatcher.Hit hit,int verifyState,String recognitionStatus) {
        applyScene(pkg,epoch,requested,hit,verifyState,recognitionStatus,false);
    }
    private void applyScene(String pkg,long epoch,long requested,BilibiliVisualMatcher.Hit hit,int verifyState,String recognitionStatus,boolean rechecked) {
        long now=SystemClock.uptimeMillis();
        if(manualCapture())return;
        boolean wasVerifying=sceneVerify!=null;
        long maxAge=InAppSceneRules.HUYA.equals(pkg) || InAppSceneRules.TENCENT.equals(pkg) || !InAppSceneRules.supports(pkg)?1200:2500;
        if (destroyed || epoch!=sceneEpoch || !InAppSceneRules.enabled(this,pkg)) return;
        AccessibilityNodeInfo foregroundRoot=(hit!=null || sceneVerify!=null)?activeRoot():null;
        boolean foreground=(hit==null && sceneVerify==null) || pkg.equals(foregroundPackage(foregroundRoot));
        if(!InAppSceneRules.supports(pkg) && !InAppSceneRules.genericPages(this) && now>sceneUntil)return;
        if(hit!=null && UiControlPolicy.isControl(hit.rule))maxAge=850;
        if(rechecked)maxAge=220;
        String rejection=SceneFramePolicy.reject(now-requested,maxAge,epoch==sceneEpoch,foreground,sceneDisplayMatches(hit),requested<lastClick && lastClick>lastSceneClick);
        if(!rejection.isEmpty()) {
            sceneStable=null;trace("scene frame rejected reason="+rejection+" age="+(now-requested));return;
        }
        if (sceneVerify!=null && (requested>lastSceneClick+350 || (hit!=null && !sameScene(hit,sceneVerify)))) {
            boolean remains=sameScene(hit,sceneVerify) || verifyState==ClickLearningSession.PRESENT;
            boolean gone=SceneFramePolicy.verifiedGone(verifyState,sceneGestureCompletedAt>0) && now-requested<=850 &&
                    sceneDisplayMatches(sceneVerify) && touchTargetClear(pkg,sceneVerify,foregroundRoot);
            getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result",
                    remains ? (sceneOpeningActionSent?"已提交一次点击，目标仍存在，本次不再补点":sceneAttempts<2 ? "点击后目标仍存在，等待当前画面重试" : "两次点击后目标仍存在，已停止重试") : gone?"点击完成，已确认原控件消失":"点击已提交，当前画面无法确认结果").apply();
            trace(sceneVerify.rule+" post-tap remains="+remains+" verifiedGone="+gone+" state="+verifyState+" attempts="+sceneAttempts);
            if(gone) {
                sceneLaunchDismissed=true;
                sceneAttempts=0;
                if(AdActionRegion.isSkip(sceneVerify.rule) || UiControlPolicy.isControl(sceneVerify.rule))sceneUntil=Math.min(sceneUntil,now+(sceneLearning==null?350:1100));
            }
            if(remains || gone || now-lastSceneClick>2500)sceneVerify=null;
        }
        if(hit==null) {
            // A missed/expired recognition must not reset the two-click limit for this target.
            sceneStable=null;sceneEmptyFrames++;
            if(!wasVerifying && sceneVerify==null && now-lastSceneTimingTrace<100)getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_visual",recognitionStatus).putString("last_result","当前画面未发现可安全点击的广告控件").apply();
            return;
        }
        sceneUntil=Math.max(sceneUntil,now+1500);
        sceneEmptyFrames=0;
        if(!sceneRules.accepts(this,hit.rule))return;
        boolean openingAction=AdActionRegion.isSkip(hit.rule) || UiControlPolicy.SKIP.equals(hit.rule) || UiControlPolicy.CLOSE.equals(hit.rule);
        if(openingAction && sceneOpeningActionSent)return;
        if(!touchTargetClear(pkg,hit,foregroundRoot)) {sceneStable=null;trace(hit.rule+" blocked by overlay; waiting");getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result","广告控件被浮层遮挡，等待浮层移开").apply();return;}
        now=SystemClock.uptimeMillis();
        if(now-requested>maxAge) {sceneStable=null;trace("scene frame aged during window check");return;}
        String label=UiControlPolicy.SKIP.equals(hit.rule)?"跳过":UiControlPolicy.CLOSE.equals(hit.rule)?"关闭":UiControlPolicy.CLOSE_AD.equals(hit.rule)?"关闭广告":hit.rule;
        getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_visual",hit.nativeOnly?label+" · 原生文字与父控件":label+" 特征="+String.format(Locale.ROOT,"%.2f",hit.score)+(hit.memoryMatch?" · 近期按钮特征命中":"")).apply();
        if(sceneLastClicked!=null && !sameScene(hit,sceneLastClicked)) {sceneAttempts=0;sceneNodeTried=false;}
        long cooldown=sameScene(hit,sceneLastClicked)?650:300;
        if(sceneAttempts>=2 || now-lastSceneClick<cooldown)return;
        boolean fastSemantic=SceneFramePolicy.oneFrame(hit,now-requested);
        boolean fastSkip=AdActionRegion.isSkip(hit.rule) && hit.modelProbability>=.98f && now-requested<=600;
        boolean fastHuya=!AdActionRegion.isSkip(hit.rule) && InAppSceneRules.HUYA.equals(pkg) &&
                (hit.score>=.90f || hit.score>=.82f && hit.modelProbability>=.98f) && now-requested<=600;
        boolean fastText=ControlTextMatcher.CLOSE.equals(hit.rule) && hit.score>=.82f && hit.modelProbability>=.98f && now-requested<=600;
        if(fastSemantic || fastSkip || fastHuya || fastText || SceneFramePolicy.twoFrames(sameScene(hit,sceneStable),now-sceneStableAt)) {
            if(SceneFramePolicy.needsVisualRecheck(hit,rechecked)) {
                if(!sceneRecheckPending){sceneRecheckPending=true;recheckSceneControl(pkg,epoch,requested,hit);}
                return;
            }
            sceneStable=null;
            trace(hit.rule+" confirmation="+(hit.nativeOnly?"native-control-current-tree":fastSemantic?"OCR-control-single-frame":fastSkip?"AI-skip-single-frame":fastHuya?"strong-context":fastText?"verified-control-text":"two-frames")+" age="+(now-requested)+"ms model="+hit.modelProbability);
            trace(hit.rule+" recent-feature="+hit.memoryMatch);
            ClickLearningSession learning=(AdActionRegion.isSkip(hit.rule) && hit.modelProbability>=.95f && hit.buttonSignature!=null || UiControlPolicy.isControl(hit.rule) && hit.jointFeatures!=null)?
                    new ClickLearningSession(pkg,epoch,now,hit):null;
            sceneLearning=learning;
            sceneGestureCompletedAt=0;
            long actionOrigin=rechecked?sceneRecheckOrigin:requested;
            // Re-resolve current semantics and all safe ancestors, including after OCR.
            // Visual-only targets have no native evidence to re-resolve. Probing a
            // slow provider here can consume the entire fresh screenshot deadline.
            boolean nodeAccepted=SceneFramePolicy.tryNative(hit) && !sceneNodeTried && clickCurrentControl(pkg,hit,requested+maxAge,foregroundRoot);
            if(nodeAccepted){sceneNodeTried=true;sceneGestureCompletedAt=SystemClock.uptimeMillis();trace(hit.rule+" capture-to-node-accepted="+(sceneGestureCompletedAt-actionOrigin)+"ms launch-to-node="+(sceneGestureCompletedAt-sceneStarted)+"ms");}
            if(!nodeAccepted && !SceneFramePolicy.allowsGesture(hit)) {
                sceneLearning=null;trace(hit.rule+" native unavailable/rejected; no coordinate fallback");
                getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result","原生控件已变化或未接受点击，等待新画面").apply();return;
            }
            if(!nodeAccepted && SystemClock.uptimeMillis()-requested>maxAge){sceneLearning=null;trace(hit.rule+" frame aged during node lookup; awaiting fresh frame");return;}
            if(nodeAccepted || tapPoint(hit.x,hit.y,hit.rule,actionOrigin,() -> {
                        if(epoch==sceneEpoch){sceneGestureCompletedAt=SystemClock.uptimeMillis();trace(hit.rule+" launch-to-click-complete="+(sceneGestureCompletedAt-sceneStarted)+"ms");if(learning!=null && sceneLearning==learning)learning.complete(sceneGestureCompletedAt);}
                    }, () -> {
                        if(sceneLearning==learning)sceneLearning=null;
                        if(epoch==sceneEpoch){sceneVerify=null;sceneOpeningActionSent=false;sceneAttempts=Math.max(0,sceneAttempts-1);getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result","手势被系统取消，等待新画面后重试").apply();}
                    })) {
                lastSceneClick=SystemClock.uptimeMillis(); sceneAttempts++; sceneVerify=hit; sceneLastClicked=hit;
                if(learning!=null) {
                    learning.recordId=JointLearningStore.get(this).record(pkg,epoch,hit);
                    trace("joint record="+learning.recordId+" tree="+(hit.structure!=null && !hit.structure.isEmpty())+" treeAssisted="+hit.treeAssisted);
                    if(nodeAccepted)learning.complete(sceneGestureCompletedAt);
                }
                if(openingAction)sceneOpeningActionSent=true;
                getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result","已提交点击，等待画面检查").apply();
            } else if(sceneLearning==learning)sceneLearning=null;
        } else { sceneStable=hit; sceneStableAt=now; }
    }
    private void recheckSceneControl(String pkg,long epoch,long originalRequest,BilibiliVisualMatcher.Hit hit) {
        long requested=SystemClock.uptimeMillis();
        if(destroyed || epoch!=sceneEpoch || requested-originalRequest>850 || gesturePending) {sceneRecheckPending=false;return;}
        if(!reserveScreenshot(requested)) {
            handler.postDelayed(()->recheckSceneControl(pkg,epoch,originalRequest,hit),Math.max(15,lastCaptureRequest+350-requested));return;
        }
        try {takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                captureRequested=false;HardwareBuffer buffer=result.getHardwareBuffer();
                try {sceneWorker.execute(()->{
                    Bitmap wrapped=null,screen=null;boolean verified=false;
                    try {
                        wrapped=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());
                        if(wrapped!=null)screen=wrapped.copy(Bitmap.Config.ARGB_8888,false);
                        if(screen!=null && epoch==sceneEpoch && !destroyed)
                            verified=sceneRules.verifyText(screen,hit,()->destroyed || epoch!=sceneEpoch,Math.min(100,200-(SystemClock.uptimeMillis()-requested)));
                    } catch(RuntimeException ignored) {}
                    finally {if(screen!=null)screen.recycle();if(wrapped!=null)wrapped.recycle();buffer.close();}
                    final boolean current=verified;
                    handler.post(()->{
                        sceneRecheckPending=false;
                        if(epoch!=sceneEpoch || destroyed)return;
                        trace(hit.rule+" pre-tap recheck="+current+" age="+(SystemClock.uptimeMillis()-requested)+"ms original-age="+(SystemClock.uptimeMillis()-originalRequest)+"ms");
                        if(current){sceneRecheckOrigin=originalRequest;applyScene(pkg,epoch,requested,hit,ClickLearningSession.UNKNOWN,"点击前已核对当前按钮",true);}
                        else {sceneStable=null;getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result","按钮已变化或核对超时，未点击").apply();}
                        wakeScenePoll();
                    });
                });} catch(RuntimeException error){buffer.close();sceneRecheckPending=false;}
            }
            @Override public void onFailure(int error){captureRequested=false;sceneRecheckPending=false;trace("pre-tap screenshot unavailable: "+error);}
        });}catch(RuntimeException error){captureRequested=false;sceneRecheckPending=false;}
    }
    private boolean clickCurrentControl(String pkg,BilibiliVisualMatcher.Hit hit,long deadline,AccessibilityNodeInfo root) {
        if(gesturePending)return false;
        if(root==null || root.getPackageName()==null || !pkg.contentEquals(root.getPackageName()))return false;
        long epoch=sceneEpoch;
        try {
            if(!root.refresh())return false;
            try(AccessibilityControlTree.Live live=AccessibilityControlTree.captureLive(root,pkg,epoch,hit.frameWidth,hit.frameHeight,
                    Math.min(deadline,SystemClock.uptimeMillis()+200))) {
                ControlTree.Snapshot tree=live.tree;
                NativeControlPolicy.Candidate candidate=NativeControlPolicy.find(tree,SceneFramePolicy.opening(SystemClock.uptimeMillis()-sceneStarted,sceneLaunchDismissed));
                if(!tree.current(pkg,sceneEpoch,SystemClock.uptimeMillis(),hit.frameWidth,hit.frameHeight) || !NativeControlPolicy.matches(candidate,tree,hit))return false;
                AccessibilityNodeInfo label=live.handles.get(candidate.label.nodeIndex),target=live.handles.get(candidate.target);
                if(!label.refresh() || !target.refresh() || !nativeNodeMatches(label,pkg,candidate.label.box) ||
                        !nativeNodeMatches(target,pkg,tree.nodes.get(candidate.target).box) || !target.isClickable())return false;
                if(!hit.rule.equals(UiControlPolicy.action(value(label.getText()))) && !hit.rule.equals(UiControlPolicy.action(value(label.getContentDescription()))))return false;
                // Walk actual current parents: array indexes/resource IDs alone never authorize a click.
                AccessibilityNodeInfo ancestor=AccessibilityNodeInfo.obtain(label);boolean linked=false;
                try {
                    for(int d=0;ancestor!=null && d<5;d++) {
                        if(ancestor.equals(target)){linked=true;break;}
                        AccessibilityNodeInfo next=ancestor.getParent();ancestor.recycle();ancestor=next;
                    }
                } finally {if(ancestor!=null)ancestor.recycle();}
                if(!linked || gesturePending || manualCapture() || epoch!=sceneEpoch || !InAppSceneRules.enabled(this,pkg) || !sceneRules.accepts(this,hit.rule))return false;
                AccessibilityNodeInfo current=activeRoot();
                try {
                    if(current==null || current.getWindowId()!=target.getWindowId() || !pkg.equals(foregroundPackage(current)) ||
                            !sceneDisplayMatches(hit) || !touchTargetClear(pkg,hit,current) || SystemClock.uptimeMillis()>deadline)return false;
                    boolean accepted=target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    trace(hit.rule+" native ACTION_CLICK accepted="+accepted+" label="+candidate.label.nodeIndex+" ancestor="+candidate.target+" source="+(hit.nativeOnly?"native":"visual-rechecked"));
                    if(accepted){lastClick=SystemClock.uptimeMillis();rememberAction(pkg+"  "+hit.rule+" 原生控件");}
                    return accepted;
                } finally {if(current!=null)current.recycle();}
            }
        } catch(RuntimeException error){trace(hit.rule+" current node unavailable: "+error.getClass().getSimpleName());return false;}
    }
    private boolean nativeNodeMatches(AccessibilityNodeInfo node,String pkg,int[] expected) {
        Rect b=new Rect();node.getBoundsInScreen(b);
        return pkg.contentEquals(value(node.getPackageName())) && node.isVisibleToUser() && node.isEnabled() &&
                b.left==expected[0] && b.top==expected[1] && b.right==expected[2] && b.bottom==expected[3];
    }

    private void onBiliEvent() {
        long now = SystemClock.uptimeMillis();
        if (!BilibiliRules.PACKAGE.equals(currentPackage)) {
            if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
            currentPackage = BilibiliRules.PACKAGE;
            activeUntil = now + SCAN_WINDOW_MS;
            lastClick = 0; biliStable = null; biliVerify = null;
        }
        if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("enabled", true)) scan(BilibiliRules.PACKAGE);
        if (!BilibiliRules.ads(this) && !BilibiliRules.live(this) && !InAppSceneRules.genericPages(this)) return;
        biliUntil = now + 4_000;
        scanBili();
        if (biliPoll == null) {
            biliPoll = new Runnable() {
                @Override public void run() {
                    if (destroyed || SystemClock.uptimeMillis() > biliUntil || !biliForeground()) { biliPoll = null; return; }
                    scanBili(); handler.postDelayed(this, 350);
                }
            };
            handler.postDelayed(biliPoll, 350);
        }
    }

    private boolean biliForeground() {
        KeyguardManager keyguard = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) return false;
        AccessibilityNodeInfo root = activeRoot();
        return root != null && root.getPackageName() != null && BilibiliRules.PACKAGE.contentEquals(root.getPackageName());
    }

    private void scanBili() {
        if(manualCapture())return;
        long now = SystemClock.uptimeMillis();
        if (gesturePending || biliRules == null || !biliForeground() || now - lastBiliScan < 250) return;
        lastBiliScan = now;
        boolean ads = BilibiliRules.ads(this), live = BilibiliRules.live(this);
        if (!ads && !live && !InAppSceneRules.genericPages(this)) return;
        if (biliVerify == null) {
            BilibiliRules.NodeHit hit = biliRules.node(activeRoot(), getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels, ads && now - lastBiliAdClick > CLICK_COOLDOWN_MS,
                    live && now - lastBiliLiveClick > CLICK_COOLDOWN_MS);
            if (hit != null) {
                AccessibilityNodeInfo target = hit.node;
                if (!target.isClickable() && target.getParent() != null) {
                    AccessibilityNodeInfo parent = target.getParent(); Rect rect = new Rect(); parent.getBoundsInScreen(rect);
                    if (parent.isClickable() && rect.width() < getResources().getDisplayMetrics().widthPixels * .65f &&
                            rect.height() < getResources().getDisplayMetrics().heightPixels * .12f) target = parent;
                }
                boolean accepted = target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (accepted) rememberAction(BilibiliRules.PACKAGE + "  " + hit.rule + " 控件");
                else accepted = tapPoint(hit.bounds.centerX(), hit.bounds.centerY(), hit.rule);
                if (accepted) {
                    lastBiliClick = now;
                    if (hit.rule.equals(BilibiliVisualMatcher.LIVE)) lastBiliLiveClick = now; else lastBiliAdClick = now;
                    biliVerify = new BilibiliVisualMatcher.Hit(hit.rule, hit.bounds.centerX(), hit.bounds.centerY(), 1);
                    trace(hit.rule + " node accepted=" + accepted);
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_visual", hit.rule + " 控件与场景匹配")
                            .putString("last_result", "已提交点击，等待画面检查").apply();
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT < 30 || biliFramePending || now - lastBiliFrame < 350 ||
                (biliVerify == null && (!ads || now - lastBiliAdClick < CLICK_COOLDOWN_MS) &&
                 (!live || now - lastBiliLiveClick < CLICK_COOLDOWN_MS) && !InAppSceneRules.genericPages(this))) return;
        if(sceneRules!=null && sceneRules.textReady() && BilibiliRules.PACKAGE.equals(scenePackage) && scenePoll!=null && now-sceneStarted<SCAN_WINDOW_MS)return;
        if(!reserveScreenshot(now))return;
        lastBiliFrame = now; biliFramePending = true;
        try { takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                captureRequested=false;
                HardwareBuffer buffer = result.getHardwareBuffer();
                try { biliWorker.execute(() -> {
                    Bitmap wrapped = null, screen = null;
                    BilibiliVisualMatcher.Hit hit = null;
                    try {
                        wrapped = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (wrapped != null) screen = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                        if (screen != null) {
                            hit = biliRules.image(screen, ads, live);
                            if(hit==null && sceneRules!=null)hit=sceneRules.controlImage(SkipService.this,screen,()->destroyed);
                        }
                    } catch (RuntimeException error) { trace("bili frame error: " + error.getClass().getSimpleName()); }
                    finally {
                        if (screen != null) screen.recycle(); if (wrapped != null) wrapped.recycle(); buffer.close();
                    }
                    final BilibiliVisualMatcher.Hit match = hit;
                    handler.post(() -> { biliFramePending = false; applyBiliFrame(match, now); });
                }); } catch (RuntimeException error) { buffer.close(); biliFramePending = false; }
            }
            @Override public void onFailure(int errorCode) { captureRequested=false;biliFramePending = false; trace("bili screenshot unavailable: " + errorCode); }
        }); } catch (RuntimeException error) { captureRequested=false;biliFramePending = false; }
    }

    private void applyBiliFrame(BilibiliVisualMatcher.Hit hit, long requestedAt) {
        long now = SystemClock.uptimeMillis();
        if (destroyed || now - requestedAt > 1_200 || !biliForeground() || !BilibiliRules.PACKAGE.equals(currentPackage) || !sceneDisplayMatches(hit)) {
            biliStable = null; return;
        }
        if (biliVerify != null && requestedAt >= lastBiliClick + 350) {
            boolean remains = hit != null && hit.rule.equals(biliVerify.rule) &&
                    Math.abs(hit.x - biliVerify.x) < 35 && Math.abs(hit.y - biliVerify.y) < 35;
            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_result",
                    remains ? "B站点击后仍识别到原目标" : "B站点击后原位置未识别到目标").apply();
            trace(biliVerify.rule + " post-tap remains=" + remains); biliVerify = null;
        }
        if (hit == null) { biliStable = null; return; }
        boolean live = hit.rule.equals(BilibiliVisualMatcher.LIVE);
        if (now - (live ? lastBiliLiveClick : lastBiliAdClick) < CLICK_COOLDOWN_MS) { biliStable = null; return; }
        if (ControlTextMatcher.CLOSE.equals(hit.rule) ? !InAppSceneRules.genericPages(this) : live ? !BilibiliRules.live(this) : !BilibiliRules.ads(this)) { biliStable = null; return; }
        if(!touchTargetClear(BilibiliRules.PACKAGE,hit)) {biliStable=null;trace(hit.rule+" blocked by overlay; waiting");return;}
        biliUntil=Math.max(biliUntil,now+1500);
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_visual", hit.rule + " 特征=" +
                String.format(Locale.ROOT, "%.2f", hit.score)).apply();
        boolean stable = sameScene(hit,biliStable) && now - biliStableAt < 1_200;
        if (live || stable) {
            biliStable = null;
            if (tapPoint(hit.x, hit.y, hit.rule)) {
                lastBiliClick = now; biliVerify = hit;
                if (live) lastBiliLiveClick = now; else lastBiliAdClick = now;
                getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_result", "已提交点击，等待画面检查").apply();
            }
        } else { biliStable = hit; biliStableAt = now; }
    }

    private void scan(String pkg) {
        if(manualCapture())return;
        if(!getSharedPreferences("settings",MODE_PRIVATE).getBoolean("enabled",true))return;
        if(InAppSceneRules.TENCENT.equals(pkg)) {
            if(!getSharedPreferences("settings",MODE_PRIVATE).getBoolean("tencent_splash",true))return;
            // Enhanced Tencent targets require complete visual context, including normal feed exclusions.
            if(AppProfiles.enabled(this) && android.os.Build.VERSION.SDK_INT>=30)return;
        }
        // Walking Huya's live WebView nodes can stall screenshot callbacks for several seconds.
        if(InAppSceneRules.HUYA.equals(pkg) && AppProfiles.enabled(this) && android.os.Build.VERSION.SDK_INT>=30)return;
        // Registered self-drawn splashes are handled by screenshots. Walking their accessibility
        // tree here can block the main thread long enough to miss the entire countdown.
        if (AppProfiles.find(this, pkg) != null && AppProfiles.find(this, pkg).bypassNodeTree && textModel != null &&
                android.os.Build.VERSION.SDK_INT >= 30 &&
                AppProfiles.enabled(this)) return;
        long now = SystemClock.uptimeMillis();
        if (gesturePending || now > activeUntil || now - lastScan < SCAN_INTERVAL_MS ||
                now - lastClick < CLICK_COOLDOWN_MS) return;
        lastScan = now;
        AccessibilityNodeInfo root = activeRoot();
        if (root == null || root.getPackageName() == null ||
                !pkg.contentEquals(root.getPackageName())) return;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes, 0);
        boolean splashContext = false;
        for (AccessibilityNodeInfo node : nodes) {
            String id = lower(node.getViewIdResourceName());
            if (id.contains("splash") || id.contains("ad_splash")) {
                splashContext = true;
                break;
            }
        }
        boolean strict = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("strict", true);
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        for (AccessibilityNodeInfo node : nodes) {
            int score = score(node, splashContext, strict);
            if (score > bestScore) {
                best = node;
                bestScore = score;
            }
        }
        if (best != null) click(best, pkg, now);
    }

    private void scanProfileImage(String pkg, long now) {
        final AppProfiles.Profile profile = AppProfiles.find(this, pkg);
        if (profile == null || !visualEnabled() || !sceneForeground(pkg)) return;
        if (InAppSceneRules.supports(pkg)) return;
        if(sceneRules!=null && sceneRules.textReady() && pkg.equals(scenePackage) && scenePoll!=null)return;
        final boolean popupOnly = now > activeUntil || !pkg.equals(currentPackage);
        if (popupOnly && profile.popup == null) return;
        // Keep one request in flight. Reissuing while its callback is queued invalidates
        // that callback and can repeatedly discard every frame of a short advertisement.
        if (android.os.Build.VERSION.SDK_INT < 30 || visualRules == null || screenshotPending ||
                now - lastScreenshot < (popupOnly ? 1000 : 500)) return;
        if(!reserveScreenshot(now))return;
        lastScreenshot = now;
        screenshotPending = true;
        long serial = ++screenshotSerial;
        try { takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                captureRequested=false;
                if (serial == screenshotSerial) screenshotPending = false;
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap wrapped = null;
                Bitmap screen = null;
                try {
                    if (serial != screenshotSerial ||
                            SystemClock.uptimeMillis() - now > 2_500) {
                        trace("frame discarded: stale age=" + (SystemClock.uptimeMillis() - now));
                        return;
                    }
                    wrapped = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (wrapped == null) return;
                    screen = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                    boolean textActive = !popupOnly && textModel != null && pkg.equals(currentPackage) &&
                            SystemClock.uptimeMillis() <= activeUntil;
                    if(pkg.equals(scenePackage) && sceneOpeningActionSent)textActive=false;
                    if (screen == null || !visualEnabled() || (!textActive && profile.popup == null)) {
                        trace("frame discarded: state model=" + (textModel != null) +
                                " tracked=" + currentPackage);
                        return;
                    }
                    KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                    if (keyguard != null && keyguard.isKeyguardLocked()) {
                        trace("frame discarded: locked");
                        return;
                    }
                    AccessibilityNodeInfo root = activeRoot();
                    if (root == null || root.getPackageName() == null ||
                            !pkg.contentEquals(root.getPackageName())) {
                        stableCandidate = null;
                        trace("frame discarded: root=" + (root == null ? "null" : root.getPackageName()));
                        return;
                    }
                    long current = SystemClock.uptimeMillis();
                    if (firstFrame) {
                        firstFrame = false;
                        Diagnostics.frame(SkipService.this, screen, "first-frame.png");
                        trace("first frame=" + screen.getWidth() + "x" + screen.getHeight());
                    }
                    if (textActive && verifyMatch != null && current >= verifyAfter) {
                        float remaining = textModel.probability(screen, verifyMatch.textBox);
                        trace("post-tap " + verifyMatch.rule + " same-text-score=" + remaining);
                        Diagnostics.frame(SkipService.this, screen, "after-tap.png");
                        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_result",
                                remaining >= .95f ? "点击后仍检测到跳过文字" : "点击后原位置未检测到跳过文字").apply();
                        verifyMatch = null;
                    }
                    if (applyProfilePopup(visualRules.findPopup(screen, profile), current)) return;
                    if (!textActive) return;
                    if (current - lastSplashClick <= CLICK_COOLDOWN_MS) return;
                    VisualRuleMatcher.Match match = visualRules.findText(screen, textModel, profile);
                    if (match != null) {
                        if (current - lastDiagnostic >= 2_000) {
                            lastDiagnostic = current;
                            trace("scan " + match.rule + " model=" + match.score +
                                    " x=" + match.x + " y=" + match.y +
                                    " elapsed=" + (SystemClock.uptimeMillis() - current) +
                                    " frameAge=" + (current - now));
                        }
                        if (match.score >= 0.5f) {
                            trace(match.rule + " model=" + match.score +
                                    " x=" + match.x + " y=" + match.y);
                            Diagnostics.frame(SkipService.this, screen, "candidate-frame.png");
                            getSharedPreferences("settings", MODE_PRIVATE).edit()
                                    .putString("last_visual", match.rule + " 模型=" +
                                            String.format(Locale.ROOT, "%.2f", match.score))
                                    .apply();
                        }
                        if (match.score >= 0.95f) {
                            int tolerance = Math.max(12, screen.getWidth() / 50);
                            boolean stable = stableCandidate != null &&
                                    match.rule.equals(stableCandidate.rule) &&
                                    Math.abs(match.x - stableCandidate.x) <= tolerance &&
                                    Math.abs(match.y - stableCandidate.y) <= tolerance &&
                                    current - stableCandidateAt >= 250 &&
                                    current - stableCandidateAt <= 1500;
                            if (stable) {
                                stableCandidate = null;
                                if (tapPoint(match.x, match.y, match.rule)) {
                                    lastSplashClick = current;
                                    verifyMatch = match;
                                    verifyAfter = SystemClock.uptimeMillis() + 300;
                                }
                            } else {
                                stableCandidate = match;
                                stableCandidateAt = current;
                                trace("text candidate: awaiting next frame " + match.rule);
                            }
                        } else {
                            stableCandidate = null;
                        }
                    }
                } catch (RuntimeException error) {
                    trace("frame error: " + error);
                    Log.w("SplashSkipVisual", "Screenshot matching failed", error);
                } finally {
                    if (screen != null) screen.recycle();
                    if (wrapped != null) wrapped.recycle();
                    buffer.close();
                }
            }
            @Override public void onFailure(int errorCode) {
                captureRequested=false;
                if (serial == screenshotSerial) screenshotPending = false;
                trace("Screenshot unavailable: " + errorCode);
            }
        }); } catch (RuntimeException error) {
            captureRequested=false;
            screenshotPending = false;
            Log.e("SplashSkipVisual", "takeScreenshot threw", error);
        }
    }

    private boolean visualEnabled() {
        return AppProfiles.enabled(this) && getSharedPreferences("settings", MODE_PRIVATE).getBoolean("enabled", true);
    }
    private boolean samePopup(VisualRuleMatcher.Match a, VisualRuleMatcher.Match b) {
        int tolerance = Math.max(18, getResources().getDisplayMetrics().widthPixels / 50);
        return a != null && b != null && a.rule.equals(b.rule) &&
                Math.abs(a.x - b.x) < tolerance && Math.abs(a.y - b.y) < tolerance;
    }
    private boolean applyProfilePopup(VisualRuleMatcher.Match popup, long now) {
        if (popupVerify != null && now >= popupVerifyAfter) {
            boolean remains = samePopup(popup, popupVerify);
            trace(popupVerify.rule + " post-tap remains=" + remains + " attempts=" + popupAttempts);
            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_result",
                    remains ? "点击后弹窗仍存在" : "点击后原弹窗不再匹配").apply();
            if (!remains) popupAttempts = 0;
            popupVerify = null;
        }
        if (popup == null) {
            popupStable = null;
            if (++popupEmptyFrames >= 2) popupAttempts = 0;
            return false;
        }
        popupEmptyFrames = 0;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_visual",
                popup.rule + " 特征=" + String.format(Locale.ROOT, "%.2f", popup.score)).apply();
        if (popupAttempts >= 2 || now - lastPopupClick < 1500) return true;
        if (samePopup(popup, popupStable) && now - popupStableAt >= 350 && now - popupStableAt < 3000) {
            popupStable = null;
            if (tapPoint(popup.x, popup.y, popup.rule)) {
                lastPopupClick = now; popupAttempts++; popupVerify = popup; popupVerifyAfter = now + 350;
            }
        } else { popupStable = popup; popupStableAt = now; }
        return true;
    }

    private boolean tapPoint(int x, int y, String rule) {
        return tapPoint(x,y,rule,0);
    }
    private boolean tapPoint(int x, int y, String rule,long frameRequested) {
        return tapPoint(x,y,rule,frameRequested,null,null);
    }
    private boolean tapPoint(int x,int y,String rule,long frameRequested,Runnable completedAction,Runnable cancelledAction) {
        if(manualCapture())return false;
        if(gesturePending){trace(rule+" gesture deferred: another gesture pending");return false;}
        gesturePending=true;final long serial=++gestureSerial;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 75)).build();
        boolean submitted;
        try {submitted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription completed) {
                if(serial==gestureSerial)gesturePending=false;
                trace(rule + " gesture completed x=" + x + " y=" + y);
                if(frameRequested>0)trace(rule+" capture-to-click-complete="+(SystemClock.uptimeMillis()-frameRequested)+"ms");
                if(completedAction!=null)completedAction.run();
            }

            @Override public void onCancelled(GestureDescription cancelled) {
                if(serial==gestureSerial)gesturePending=false;
                trace(rule + " gesture cancelled x=" + x + " y=" + y);
                if(cancelledAction!=null)cancelledAction.run();
            }
        }, handler);}catch(RuntimeException error){gesturePending=false;trace(rule+" gesture error: "+error.getClass().getSimpleName());return false;}
        if (submitted) {
            handler.postDelayed(() -> {
                if(gesturePending && serial==gestureSerial){gesturePending=false;trace(rule+" gesture callback timeout");if(cancelledAction!=null)cancelledAction.run();}
            },1200);
            trace(rule + " gesture submitted x=" + x + " y=" + y);
            lastClick = SystemClock.uptimeMillis();
            rememberAction(currentPackage + "  " + rule);
            return true;
        }
        gesturePending=false;trace(rule + " gesture rejected x=" + x + " y=" + y);
        return false;
    }

    private void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> nodes, int depth) {
        if (node == null || depth > 40 || nodes.size() >= 500) return;
        nodes.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), nodes, depth + 1);
    }

    private int score(AccessibilityNodeInfo node, boolean splashContext, boolean strict) {
        String id = lower(node.getViewIdResourceName());
        String label = (value(node.getText()) + " " + value(node.getContentDescription())).trim().toLowerCase(Locale.ROOT);
        boolean idSkip = id.contains("skip") || id.contains("jump_over");
        boolean countdown = id.contains("count_down") || id.contains("countdown");
        boolean textSkip = label.matches("^(跳过|跳过\\s*[0-9０-９]+|skip|skip\\s*[0-9]+)(\\s*[秒s])?$");
        if (!idSkip && !countdown && !textSkip) return 0;
        if (strict && !idSkip && !(countdown && splashContext) &&
                !(textSkip && (splashContext || label.matches(".*[0-9０-９].*")))) return 0;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        if (bounds.isEmpty() || bounds.width() > width * 0.45f ||
                bounds.height() > height * 0.18f || bounds.left < 0 || bounds.top < 0 ||
                bounds.right > width || bounds.bottom > height) return 0;
        if (!node.isClickable()) {
            AccessibilityNodeInfo parent = node.getParent();
            if (parent == null || !parent.isClickable()) return 0;
            Rect parentBounds = new Rect();
            parent.getBoundsInScreen(parentBounds);
            if (parentBounds.width() > width * 0.45f ||
                    parentBounds.height() > height * 0.18f) return 0;
        }
        return (idSkip ? 100 : 0) + (countdown ? 80 : 0) +
                (textSkip ? 60 : 0) + (node.isClickable() ? 10 : 0);
    }

    private void click(AccessibilityNodeInfo node, String pkg, long now) {
        if(manualCapture())return;
        if(gesturePending)return;
        AccessibilityNodeInfo target = node.isClickable() ? node : node.getParent();
        if (target == null) return;
        boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (AppProfiles.find(this, pkg) != null) {
            Rect targetBounds = new Rect();
            target.getBoundsInScreen(targetBounds);
            trace("node click id=" + target.getViewIdResourceName() + " bounds=" + targetBounds +
                    " accepted=" + clicked);
        }
        if (!clicked) {
            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            clicked = tapPoint(bounds.centerX(),bounds.centerY(),"node-skip");
        }
        if (clicked) {
            lastClick = now;
            rememberAction(pkg);
        }
    }

    private void rememberAction(String description) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("last_action", time + "  " + description).apply();
    }

    private void trace(String message) {
        Log.i("SplashSkipVisual", message);
        Diagnostics.append(this, message);
    }

    private AccessibilityNodeInfo activeRoot() {
        // We only need the root package for foreground validation, not prefetched descendants.
        if (android.os.Build.VERSION.SDK_INT >= 33) return getRootInActiveWindow(0);
        return getRootInActiveWindow();
    }

    private String lower(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT);
    }

    private String value(CharSequence input) {
        return input == null ? "" : input.toString();
    }

    @Override public void onDestroy() {
        running=false;ocrReady=false;
        if(desktopService==this)desktopService=null;
        destroyed = true;
        if (scenePoll != null) handler.removeCallbacks(scenePoll);
        if (foregroundPoll != null) handler.removeCallbacks(foregroundPoll);
        if (biliPoll != null) handler.removeCallbacks(biliPoll);
        biliWorker.shutdown();
        if (sceneRules != null) sceneWorker.execute(() -> sceneRules.close());
        sceneWorker.shutdown();
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
        if (textModel != null) textModel.close();
        super.onDestroy();
    }

    @Override public void onInterrupt() { }
}
