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
    private volatile long screenshotRequestCount;
    long desktopScreenshotRequests(){return screenshotRequestCount;}
    static final class NativeDiagnostic {
        final ControlTree.Snapshot tree;
        final ControlTree.Snapshot adScope;
        final String reason;
        final int window;
        final boolean opening;
        final java.util.Map<Integer,String> labels;
        final int verifiedAliases;
        final List<Integer> aliasParents;
        final List<String> traversalReasons,duplicateEdges;
        final java.util.Map<String,Integer> duplicateKinds;
        final int duplicates,errors,missingChildren;
        NativeDiagnostic(ControlTree.Snapshot tree,ControlTree.Snapshot adScope,String reason,int window,boolean opening,java.util.Map<Integer,String> labels,
                BoundedNodeWalker.Stats traversal) {
            this.tree=tree;this.adScope=adScope;this.reason=reason;this.window=window;this.opening=opening;
            if(traversal==null)traversal=new BoundedNodeWalker.Stats();
            this.labels=java.util.Collections.unmodifiableMap(new java.util.HashMap<>(labels));
            this.verifiedAliases=traversal.verifiedAliases;this.aliasParents=java.util.Collections.unmodifiableList(new ArrayList<>(traversal.aliasParents));
            this.traversalReasons=java.util.Collections.unmodifiableList(traversal.reasons());
            this.duplicateEdges=java.util.Collections.unmodifiableList(new ArrayList<>(traversal.duplicateEdges));
            this.duplicateKinds=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(traversal.duplicateKinds));
            this.duplicates=traversal.duplicates;this.errors=traversal.errors;this.missingChildren=traversal.missingChildren;
        }
    }
    private volatile NativeDiagnostic nativeDiagnostic;
    private void publishNativeDiagnostic(NativeDiagnostic frame) {
        nativeDiagnostic=frame;
        NativeBoundsArchive.record(this,frame);
    }
    private volatile String boundsPackage="";
    private volatile long boundsEpoch=-1;
    private long boundsStarted,lastBoundsRead;
    private boolean boundsReadPending;
    private Runnable boundsPoll;
    private boolean boundsEnabled() {return getSharedPreferences("settings",MODE_PRIVATE).getBoolean("native_bounds_auto",true);}
    private void onBoundsForeground(String pkg) {
        if(!pkg.equals(boundsPackage)) {
            boundsPackage=pkg;boundsEpoch--;boundsStarted=SystemClock.uptimeMillis();lastBoundsRead=0;
            if(boundsPoll!=null)handler.removeCallbacks(boundsPoll);
            boundsPoll=null;
        }
        if(!boundsEnabled() || !InAppSceneRules.watches(this,pkg) || controlsEnabled(pkg) || boundsPoll!=null ||
                SystemClock.uptimeMillis()-boundsStarted>SCAN_WINDOW_MS)return;
        boundsPoll=new Runnable(){public void run(){
            if(destroyed || !boundsEnabled() || !pkg.equals(boundsPackage) || controlsEnabled(pkg) ||
                    SystemClock.uptimeMillis()-boundsStarted>SCAN_WINDOW_MS){boundsPoll=null;return;}
            readBoundsOnly(pkg);handler.postDelayed(this,650);
        }};
        handler.post(boundsPoll);
    }
    /** Limited launch sampling when automatic clicking is disabled. Never proposes an action. */
    private void readBoundsOnly(String pkg) {
        long now=SystemClock.uptimeMillis(),epoch=boundsEpoch;
        if(boundsReadPending || scenePending || gesturePending || now-lastBoundsRead<650)return;
        android.view.Display display=getSystemService(android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null)return;
        android.graphics.Point size=new android.graphics.Point();display.getRealSize(size);
        boundsReadPending=true;lastBoundsRead=now;
        try{controlWorker.execute(()->{
            AccessibilityNodeInfo root=null;
            try {
                KeyguardManager keyguard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
                if(destroyed || epoch!=boundsEpoch || !pkg.equals(boundsPackage) || !boundsEnabled() || controlsEnabled(pkg) ||
                        keyguard!=null && keyguard.isKeyguardLocked() || SystemClock.uptimeMillis()-now>350)return;
                root=activeRoot();
                if(root==null || !pkg.contentEquals(value(root.getPackageName())))return;
                try(AccessibilityControlTree.Live live=AccessibilityControlTree.captureLive(root,pkg,epoch,size.x,size.y,SystemClock.uptimeMillis()+120)) {
                    if(epoch==boundsEpoch && pkg.equals(boundsPackage) && !destroyed && boundsEnabled())
                        NativeBoundsArchive.record(this,new NativeDiagnostic(live.tree,null,"diagnostic-only-no-action",root.getWindowId(),true,live.diagnosticLabels,live.traversal));
                }
            }catch(RuntimeException ignored){}finally{if(root!=null)root.recycle();handler.post(()->boundsReadPending=false);}
        });}catch(RuntimeException ignored){boundsReadPending=false;}
    }
    NativeDiagnostic desktopNativeFrame(String pkg) {
        NativeDiagnostic frame=nativeDiagnostic;
        ControlTree.Snapshot source=frame==null?null:frame.tree==null?frame.adScope:frame.tree;
        return source!=null && pkg.equals(scenePackage) && pkg.equals(source.pkg) && source.epoch==sceneEpoch?frame:null;
    }
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
    private volatile long lastClick;
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
    private final ExecutorService controlWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService foregroundWorker = Executors.newSingleThreadExecutor();
    private boolean foregroundPending;
    private String nextForegroundPackage = "";
    private int nextForegroundType;
    private long foregroundRevision;
    private final java.util.Set<String> nativeAcceptedTargets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> nativeTouchTargets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private String nativeObservedTargetKey = "";
    private volatile int sceneWindowId = -1;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener recognitionSettings;
    private volatile NativeTreeObservation nativeObservation;
    private volatile long nativeAcceptedAt;
    private Runnable biliPoll;
    private long biliUntil, lastBiliScan, lastBiliFrame, lastBiliClick, lastBiliAdClick, lastBiliLiveClick;
    private boolean biliFramePending;
    private boolean biliControlPending;
    private volatile BiliPauseObservation biliPauseObservation;
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
    private volatile String scenePackage = "";
    private BilibiliVisualMatcher.Hit sceneStable, sceneLastClicked;
    private volatile BilibiliVisualMatcher.Hit sceneVerify;
    private int sceneAttempts;
    private int sceneEmptyFrames;
    private long lastSceneTimingTrace;
    private long sceneUntil;
    private volatile long sceneStarted;
    private long lastSceneContentEvent;
    private volatile long nativeFirstControlAt;
    private volatile boolean nativeHintPending;
    private volatile ClickLearningSession sceneLearning;
    private volatile long sceneGestureCompletedAt;
    private boolean sceneNodeTried,captureRequested;
    private volatile boolean gesturePending,sceneLaunchDismissed,sceneOpeningActionSent;
    private boolean sceneRecheckPending;
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
        AccessibilityControlTree.setDiagnosticLogger(this::trace);
        if (textModel != null) { textModel.close(); textModel = null; }
        sensorGuard = SensorGuardController.get(this);
        sensorGuard.connect();
        visualRules = new VisualRuleMatcher(this);
        biliRules = new BilibiliRules(this);
        sceneRules = new InAppSceneRules(this);
        if(RecognitionMode.visuals(this))prepareVisualText();
        recognitionSettings=(settings,key)->{
            if(!"visual_supplement".equals(key) && !"ai_enhanced".equals(key) && !"enabled".equals(key))return;
            handler.post(()->{
                if(destroyed)return;
                sceneEpoch++;screenshotSerial++;
                sceneStable=null;sceneVerify=null;sceneLearning=null;nativeObservation=null;
                biliStable=null;biliVerify=null;popupStable=null;popupVerify=null;
                sceneRecheckPending=false;sceneOpeningActionSent=false;sceneLaunchDismissed=false;
                nativeTreeBackoff=new NativeTreeBackoff();lastSceneFrame=0;nativeFirstControlAt=0;nativeHintPending=false;nativeAcceptedTargets.clear();nativeTouchTargets.clear();
                if(RecognitionMode.visuals(this))prepareVisualText();
                trace("recognition mode="+(RecognitionMode.visuals(this)?"controls+visual":"controls-only"));
                if(!scenePackage.isEmpty()){sceneUntil=Math.max(sceneUntil,SystemClock.uptimeMillis()+2000);wakeScenePoll();}
            });
        };
        getSharedPreferences("settings",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(recognitionSettings);
        if (foregroundPoll != null) handler.removeCallbacks(foregroundPoll);
        foregroundPoll = new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                requestForeground("",0);
                handler.postDelayed(this, 2000);
            }
        };
        handler.post(foregroundPoll);
        trace("service connected; model=" + (textModel != null));
        trace("recognition mode="+(RecognitionMode.visuals(this)?"controls+visual":"controls-only"));
    }

    private void prepareVisualText() {
        sceneWorker.execute(()->{
            if(destroyed || !RecognitionMode.visuals(this))return;
            if(textModel==null)try{textModel=new SkipTextModel(this);}
            catch(Exception | LinkageError error){trace("legacy text model unavailable: "+error.getClass().getSimpleName());}
            sceneRules.prepareText();ocrReady=sceneRules.textReady();
        });
    }

    private boolean controlsEnabled(String pkg) {
        return getSharedPreferences("settings",MODE_PRIVATE).getBoolean("enabled",true) && InAppSceneRules.watches(this,pkg);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        desktopEventSequence++;
        String pkg = event.getPackageName().toString();
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                InAppSceneRules.watches(this,pkg) && manualCapture()) {
            AccessibilityNodeInfo source = AccessibilityControlTree.fetchEventSource(event);
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
            if(paired && candidate.jointFeatures!=null) {
                String record=JointLearningStore.get(this).record(pkg,sceneEpoch,candidate);
                JointLearningStore.get(this).outcome(record,"unknown","manual");
                detail+="\n已关联点击前控件与视觉特征；请在特征库中确认结果。";
            } else if(paired)detail+="\n已关联当前父子控件；本次仅记录控件点击诊断，未采集视觉训练特征。";
            else detail+="\n没有新鲜的点击前特征，本次事件不进入训练集。";
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
            if(BilibiliRules.PACKAGE.equals(pkg)) {
                // Native ad sheets also appear after the opening scan has gone idle.
                // Restart only this throttled poll; it never requires a screenshot.
                biliUntil=changed+4000;
                scheduleBiliPoll();
                scanBili();
            }
            if(fastNativeOpening(pkg,changed) && !scenePending)wakeScenePoll();
            return;
        }
        requestForeground(pkg,event.getEventType());
    }

    private void requestForeground(String pkg,int eventType) {
        if(destroyed || pkg.isEmpty() && foregroundPending)return;
        nextForegroundPackage=pkg;nextForegroundType=eventType;
        final long revision=++foregroundRevision;
        if(foregroundPending)return;
        foregroundPending=true;
        try {foregroundWorker.execute(()->{
            String found="";boolean locked=false;int windowId=-1;long started=SystemClock.uptimeMillis();
            AccessibilityNodeInfo root=null;
            try {
                KeyguardManager keyguard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
                locked=keyguard!=null && keyguard.isKeyguardLocked();
                if(!locked){root=activeRoot();found=foregroundPackage(root);if(root!=null && found.contentEquals(value(root.getPackageName())))windowId=root.getWindowId();}
            } catch(RuntimeException ignored) { }
            finally{if(root!=null)root.recycle();}
            final String foreground=found;final boolean wasLocked=locked;
            final int currentWindow=windowId;
            final long elapsed=SystemClock.uptimeMillis()-started;
            handler.post(()->{
                foregroundPending=false;
                if(destroyed)return;
                if(elapsed>80)trace("foreground read pkg="+foreground+" elapsed="+elapsed+"ms");
                KeyguardManager keyguard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
                if(wasLocked || keyguard!=null && keyguard.isKeyguardLocked()) {
                    sensorGuard.foreground("");if(!scenePackage.isEmpty())leaveScene();
                    boundsPackage="";boundsEpoch--;
                } else if(!foreground.isEmpty()) {
                    sensorGuard.foreground(foreground);
                    if(!scenePackage.isEmpty() && (!foreground.equals(scenePackage) ||
                            currentWindow>=0 && sceneWindowId>=0 && currentWindow!=sceneWindowId))leaveScene();
                    if(InAppSceneRules.watches(SkipService.this,foreground))onSceneEvent(foreground);
                    if(pkg.isEmpty())applyForegroundPoll(foreground);
                    else handleForegroundEvent(pkg,eventType,foreground);
                    if(foreground.equals(scenePackage) && currentWindow>=0)sceneWindowId=currentWindow;
                    onBoundsForeground(foreground);
                }
                if(revision!=foregroundRevision)requestForeground(nextForegroundPackage,nextForegroundType);
            });
        });}catch(RuntimeException error){foregroundPending=false;}
    }
    private void applyForegroundPoll(String foreground) {
        if (InAppSceneRules.watches(this,foreground)) onSceneEvent(foreground);
        else if(!foreground.equals(scenePackage) && !scenePackage.isEmpty())leaveScene();
        long probeNow=SystemClock.uptimeMillis();
        if(SceneFramePolicy.idleNativeCloseProbe(foreground,controlsEnabled(foreground) && InAppSceneRules.genericPages(this),
                foreground.equals(scenePackage) && scenePoll==null && probeNow>sceneUntil,
                destroyed || manualCapture() || scenePending || sceneRecheckPending || gesturePending || nativeObservation!=null ||
                    sceneVerify!=null || sceneLearning!=null,probeNow,lastSceneFrame))
            scanScene(foreground,true);
        if(BilibiliRules.PACKAGE.equals(foreground)) {
            biliUntil=SystemClock.uptimeMillis()+4000;
            scheduleBiliPoll();
            scanBili();
        }
        AppProfiles.Profile profile = AppProfiles.find(this, foreground);
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
    private void handleForegroundEvent(String pkg,int eventType,String foreground) {
        // Accessibility events from background windows must not replace the foreground app
        // and cancel the short screenshot polling window.
        boolean rootMatches = pkg.equals(foreground);
        // Clear the previous launch immediately on a confirmed foreground departure.
        // Waiting for the two-second poll misses quick home/helper -> app relaunches.
        if(SceneFramePolicy.leftScene(scenePackage,pkg,foreground))leaveScene();
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui") ||
                pkg.startsWith("com.android.settings")) return;
        if (pkg.equals(BilibiliRules.PACKAGE) && rootMatches) {
            if(!pkg.equals(scenePackage)){biliStable=null;biliVerify=null;lastBiliAdClick=0;lastBiliLiveClick=0;}
            onSceneEvent(pkg,eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            onBiliEvent();
            return;
        }
        if (InAppSceneRules.watches(this,pkg) && rootMatches) {
            long eventNow=SystemClock.uptimeMillis();
            boolean newPage=eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || eventType==AccessibilityEvent.TYPE_WINDOWS_CHANGED || eventType==AccessibilityEvent.TYPE_VIEW_CLICKED;
            if(eventType==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
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
            trace("event=" + eventType + " root=" +
                    foreground +
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
                (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                 eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
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
                trace("scan window started event=" + eventType);
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
        try{return pkg.equals(foregroundPackage(root));}
        finally{if(root!=null)root.recycle();}
    }
    private String foregroundPackage(AccessibilityNodeInfo root) {
        if(root!=null && root.getPackageName()!=null && !"com.android.systemui".contentEquals(root.getPackageName()))return root.getPackageName().toString();
        // Self-drawn windows can temporarily omit their root during launch. Validate against
        // the active application window rather than waiting for the 2-second foreground fallback.
        try {
            String foreground="";int layer=Integer.MIN_VALUE;
            for(AccessibilityWindowInfo window:getWindows()) {
                AccessibilityNodeInfo owner=null;
                try {
                    if(window.getType()==AccessibilityWindowInfo.TYPE_APPLICATION && (window.isActive() || window.isFocused()) && window.getLayer()>layer) {
                        owner=AccessibilityControlTree.fetchWindowRoot(window);
                        if(owner!=null && owner.getPackageName()!=null){foreground=owner.getPackageName().toString();layer=window.getLayer();}
                    }
                }finally{if(owner!=null)owner.recycle();window.recycle();}
            }
            return foreground;
        } catch(RuntimeException ignored) { }
        return "";
    }
    private void onSceneEvent(String pkg) { onSceneEvent(pkg,false); }
    private void onSceneEvent(String pkg,boolean newPage) {
        // Both callers already checked this package against the foreground root in this event.
        if (!controlsEnabled(pkg)) return;
        if (!pkg.equals(scenePackage)) {
            scenePackage=pkg; sceneEpoch++; sceneStable=null; sceneVerify=null; sceneLastClicked=null; sceneAttempts=0; sceneEmptyFrames=0;sceneLearning=null;
            nativeTreeBackoff=new NativeTreeBackoff();
            nativeObservation=null;nativeAcceptedTargets.clear();nativeTouchTargets.clear();
            lastSceneClick=0;nativeFirstControlAt=0;nativeHintPending=false;sceneStarted=SystemClock.uptimeMillis();sceneUntil=sceneStarted+SCAN_WINDOW_MS;
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
                if (destroyed || !controlsEnabled(scenePackage)) {
                    scenePoll=null; scenePackage=""; sceneEpoch++; sceneStable=null; sceneVerify=null;sceneLearning=null;nativeObservation=null; return;
                }
                if(!InAppSceneRules.supports(scenePackage) && !InAppSceneRules.genericPages(SkipService.this) && SystemClock.uptimeMillis()>sceneUntil) {
                    scenePoll=null;sceneEpoch++;sceneStable=null;sceneVerify=null;sceneLearning=null;return;
                }
                if(SystemClock.uptimeMillis()>sceneUntil && sceneVerify==null && sceneLearning==null && nativeObservation==null) {
                    scenePoll=null;trace("scene watch idle "+scenePackage);return;
                }
                scanScene(scenePackage); handler.postDelayed(this,sceneInterval(scenePackage));
                if(!InAppSceneRules.supports(scenePackage))scan(scenePackage);
            }
        };
        handler.post(scenePoll);
    }
    private void leaveScene() {
        if(scenePoll!=null)handler.removeCallbacks(scenePoll);
        scenePoll=null;scenePackage="";sceneEpoch++;sceneStable=null;sceneVerify=null;sceneLearning=null;nativeObservation=null;nativeDiagnostic=null;nativeAcceptedTargets.clear();nativeTouchTargets.clear();nativeObservedTargetKey="";sceneWindowId=-1;nativeFirstControlAt=0;nativeHintPending=false;
    }
    private boolean fastNativeOpening(String pkg,long now) {
        return InAppSceneRules.HUYA.equals(pkg) && pkg.equals(scenePackage) && !RecognitionMode.visuals(this) &&
            SceneFramePolicy.opening(now-sceneStarted,sceneLaunchDismissed) && nativeObservation==null &&
            !sceneOpeningActionSent && !manualCapture();
    }
    private long sceneInterval(String pkg) {
        if(SystemClock.uptimeMillis()>sceneUntil)return 1500;
        if(fastNativeOpening(pkg,SystemClock.uptimeMillis()))return 100;
        if(!RecognitionMode.visuals(this))return 200;
        if(SceneFramePolicy.opening(SystemClock.uptimeMillis()-sceneStarted,sceneLaunchDismissed))return 350;
        return InAppSceneRules.HUYA.equals(pkg) || InAppSceneRules.TENCENT.equals(pkg) || InAppSceneRules.mobilePackage(pkg) || !InAppSceneRules.supports(pkg)?350:700;
    }
    private void wakeScenePoll() {
        if(destroyed)return;
        if(scenePoll==null) {if(!scenePackage.isEmpty() && SystemClock.uptimeMillis()<=sceneUntil)onSceneEvent(scenePackage,false);return;}
        handler.removeCallbacks(scenePoll);
        handler.postDelayed(scenePoll,Math.max(1,lastSceneFrame+sceneInterval(scenePackage)-SystemClock.uptimeMillis()));
    }
    private void scanScene(String pkg) {
        scanScene(pkg,false);
    }
    private void scanScene(String pkg,boolean idleCloseOnly) {
        long requested=SystemClock.uptimeMillis(), epoch=sceneEpoch;
        final long launchStarted=sceneStarted;
        if(destroyed || scenePending || sceneRecheckPending || !controlsEnabled(pkg) || !pkg.equals(scenePackage) ||
                requested-lastSceneFrame<sceneInterval(pkg) || gesturePending || requested<nativeAcceptedAt+120)return;
        if(!SceneFramePolicy.captureAfterAction(requested,gesturePending,sceneVerify!=null,sceneGestureCompletedAt))return;
        android.view.Display display=getSystemService(android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null)return;
        android.graphics.Point size=new android.graphics.Point();display.getRealSize(size);
        lastSceneFrame=requested;scenePending=true;
        final boolean opening=!idleCloseOnly && SceneFramePolicy.opening(requested-sceneStarted,sceneLaunchDismissed);
        final boolean priorityNative=!idleCloseOnly && fastNativeOpening(pkg,requested);
        final NativeTreeBackoff schedule=nativeTreeBackoff;
        final NativeTreeObservation observing=nativeObservation;
        try {controlWorker.execute(()->{
            long started=SystemClock.uptimeMillis();
            NativeScanResult result=new NativeScanResult();result.observation=observing;
            AccessibilityNodeInfo root=null;
            try {
                if(destroyed || epoch!=sceneEpoch || !controlsEnabled(pkg)){result.reason="scene-changed";}
                else if(!idleCloseOnly && RecognitionMode.visuals(SkipService.this) && !schedule.shouldRead(started,manualCapture())){result.reason="provider-backoff";}
                else if(started-requested>350){result.reason="worker-queue-expired";}
                else {
                    root=activeRoot();
                    if(root==null || !pkg.contentEquals(value(root.getPackageName())))result.reason="foreground-root-unavailable";
                    else if(priorityNative) {
                        // Query the current Skip before a potentially expensive
                        // background tree, including the first scan of a new scene.
                        result.globalBudget=0;
                        AccessibilityControlTree.DiscoveryEvidence evidence=new AccessibilityControlTree.DiscoveryEvidence();
                        AccessibilityControlTree.ScopedAd focused=AccessibilityControlTree.captureScopedAd(null,root,pkg,epoch,size.x,size.y,
                                Math.min(requested+850,SystemClock.uptimeMillis()+700),evidence,SkipService.this);
                        recordNativeDiscovery(pkg,epoch,evidence);
                        result.readFinished=SystemClock.uptimeMillis();
                        if(focused!=null) {
                            result.scopedTree=focused.live.tree;
                            evaluateNativeAction(pkg,epoch,requested,launchStarted,opening,root,null,result,focused);
                            if(epoch==sceneEpoch && pkg.equals(scenePackage))
                                publishNativeDiagnostic(new NativeDiagnostic(null,result.scopedTree,result.reason,root.getWindowId(),opening,
                                        focused.live.diagnosticLabels,focused.live.traversal));
                        }else if(evidence.hasExplicitControl()) {
                            result.reason="native-scope-retry-pending";
                            if(epoch==sceneEpoch && pkg.equals(scenePackage))nativeDiagnostic=null;
                        }else {
                            int budget=schedule.globalReadBudget(SystemClock.uptimeMillis(),true);
                            try(AccessibilityControlTree.Live live=AccessibilityControlTree.captureLive(root,pkg,epoch,size.x,size.y,
                                    Math.min(requested+850,SystemClock.uptimeMillis()+budget))) {
                            result.globalBudget=budget;result.tree=live.tree;result.verifiedAliases=live.verifiedAliases;
                            result.readFinished=SystemClock.uptimeMillis();
                            noteNativeControl(pkg,epoch,live.tree,"current-global-read");
                            AccessibilityControlTree.ScopedAd fromTree=null;
                            // Some providers expose nodes before their text index.
                            if(NativeControlPolicy.find(live.tree,opening)==null &&
                                    live.tree.nodes.stream().anyMatch(n->n.visible && NativeControlPolicy.scopedAction(n.role))) {
                                fromTree=AccessibilityControlTree.captureScopedAd(live,root,pkg,epoch,size.x,size.y,
                                    Math.min(requested+850,SystemClock.uptimeMillis()+700),evidence,SkipService.this);
                                recordNativeDiscovery(pkg,epoch,evidence);
                                result.readFinished=SystemClock.uptimeMillis();
                                if(fromTree!=null)result.scopedTree=fromTree.live.tree;
                            }
                            evaluateNativeAction(pkg,epoch,requested,launchStarted,opening,root,live,result,fromTree);
                            schedule.observed(live.tree.nodes.size(),live.tree.complete,result.readFinished);
                            if(epoch==sceneEpoch && pkg.equals(scenePackage))
                                publishNativeDiagnostic(new NativeDiagnostic(result.tree,result.scopedTree,result.reason,root.getWindowId(),opening,
                                        live.diagnosticLabels,live.traversal));
                            }
                        }
                    } else {
                        AccessibilityControlTree.ScopedAd focused=null;
                        if(opening && scopedNativeApp(pkg) && observing==null &&
                                !sceneOpeningActionSent && !manualCapture())
                            focused=AccessibilityControlTree.captureScopedAd(null,root,pkg,epoch,size.x,size.y,
                                    Math.min(requested+850,SystemClock.uptimeMillis()+700),null,SkipService.this);
                        if(focused!=null) {
                            result.scopedTree=focused.live.tree;result.readFinished=SystemClock.uptimeMillis();
                            evaluateNativeAction(pkg,epoch,requested,launchStarted,opening,root,null,result,focused);
                            if(epoch==sceneEpoch && pkg.equals(scenePackage))
                                publishNativeDiagnostic(new NativeDiagnostic(null,result.scopedTree,result.reason,root.getWindowId(),opening,
                                        focused.live.diagnosticLabels,focused.live.traversal));
                        } else try(AccessibilityControlTree.Live live=AccessibilityControlTree.captureLive(root,pkg,epoch,size.x,size.y,
                                Math.min(requested+850,SystemClock.uptimeMillis()+200))) {
                            result.tree=live.tree;result.readFinished=SystemClock.uptimeMillis();
                            result.verifiedAliases=live.verifiedAliases;
                            schedule.observed(live.tree.nodes.size(),live.tree.complete,result.readFinished);
                            if(observing!=null)result.verification=observing.observe(live.tree,result.readFinished);
                            evaluateNativeAction(pkg,epoch,requested,launchStarted,opening,root,live,result);
                            if(epoch==sceneEpoch && pkg.equals(scenePackage))
                                publishNativeDiagnostic(new NativeDiagnostic(result.tree,result.scopedTree,result.reason,root.getWindowId(),opening,live.diagnosticLabels,
                                        live.traversal));
                        }
                    }
                }
            } catch(RuntimeException error){result.reason="native-provider-"+error.getClass().getSimpleName();}
            finally{if(root!=null)root.recycle();}
            long finished=SystemClock.uptimeMillis();
            handler.post(()->{
                scenePending=false;
                if(destroyed || epoch!=sceneEpoch || !pkg.equals(scenePackage))return;
                boolean log=result.hit!=null || requested-sceneStarted<SCAN_WINDOW_MS || SystemClock.uptimeMillis()-lastSceneTimingTrace>5000;
                if(log) {
                    lastSceneTimingTrace=SystemClock.uptimeMillis();
                    int controls=result.tree==null?0:result.tree.controls(java.util.Collections.emptyMap()).size();
                    trace("native timing pkg="+pkg+" queue="+(started-requested)+" read="+((result.readFinished>0?result.readFinished:finished)-started)+
                            " action="+(result.readFinished>0?finished-result.readFinished:0)+" callback="+(SystemClock.uptimeMillis()-finished)+
                            "ms nodes="+(result.tree==null?0:result.tree.nodes.size())+" complete="+(result.tree!=null && result.tree.complete)+
                            " controls="+controls+" aliases="+result.verifiedAliases+
                            " global_budget="+result.globalBudget+
                            " scope_nodes="+(result.scopedTree==null?0:result.scopedTree.nodes.size())+
                            " scope_complete="+(result.scopedTree!=null && result.scopedTree.complete)+" result="+result.reason);
                    getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_control",
                            nativeSummary(result,controls)).apply();
                }
                if(manualCapture() && result.hit!=null && result.tree!=null && result.tree.current(pkg,epoch,SystemClock.uptimeMillis(),size.x,size.y)) {
                    manualJointPackage=pkg;manualJointEpoch=epoch;manualJointFrame=requested;manualJointHit=result.hit;
                }
                applyNativeResult(pkg,epoch,requested,result);
                // A currently exposed native button never authorizes a coordinate fallback.
                if(!idleCloseOnly && result.hit==null && nativeObservation==null && RecognitionMode.visuals(SkipService.this) &&
                        InAppSceneRules.enabled(SkipService.this,pkg) && (!sceneOpeningActionSent || sceneVerify!=null))
                    scanVisualScene(pkg,requested,epoch,result.tree);
                wakeScenePoll();
            });
        });}catch(RuntimeException error){scenePending=false;trace("native worker unavailable: "+error.getClass().getSimpleName());}
    }
    private static final class NativeScanResult {
        ControlTree.Snapshot tree;
        BilibiliVisualMatcher.Hit hit;
        NativeTreeObservation observation;
        int verification=NativeTreeObservation.UNKNOWN;
        int verifiedAliases;
        int globalBudget=200;
        boolean attempted,accepted;
        boolean touch;
        ControlTree.Snapshot scopedTree;
        long readFinished,acceptedAt;
        String reason="unknown";
        String targetKey="";
    }
    /** Timing evidence only. A control in a partial tree never grants permission to click. */
    private void noteNativeControl(String pkg,long epoch,ControlTree.Snapshot tree,String source) {
        if(!InAppSceneRules.HUYA.equals(pkg) || nativeFirstControlAt!=0 || epoch!=sceneEpoch || !pkg.equals(scenePackage))return;
        for(ControlTree.Node node:tree.nodes)if(node.visible && NativeControlPolicy.scopedAction(node.role)) {
            noteNativeControlAt(pkg,epoch,SystemClock.uptimeMillis(),source,tree.complete);
            return;
        }
    }
    private void noteNativeControlAt(String pkg,long epoch,long observed,String source,boolean complete) {
        if(!InAppSceneRules.HUYA.equals(pkg) || epoch!=sceneEpoch || !pkg.equals(scenePackage) ||
                observed<sceneStarted || nativeFirstControlAt>0 && nativeFirstControlAt<=observed)return;
        nativeFirstControlAt=observed;
        trace("native first-control pkg="+pkg+" scene-to-control-observed="+(observed-sceneStarted)+
                "ms source="+source+" complete="+complete);
    }
    private void recordNativeDiscovery(String pkg,long epoch,AccessibilityControlTree.DiscoveryEvidence evidence) {
        if(epoch!=sceneEpoch || !pkg.equals(scenePackage))return;
        nativeHintPending=evidence.hasExplicitControl();
        if(evidence.hasExplicitControl())
            noteNativeControlAt(pkg,epoch,evidence.explicitControlAt,"current-explicit-query-or-tree",false);
    }
    private String nativeSummary(NativeScanResult result,int controls) {
        if(result.touch)return result.accepted?"当前父子控件已核验 · 系统已提交控件触摸":"当前控件触摸待执行或已变化";
        if(result.accepted)return "父子控件已定位 · 系统已接受控件点击";
        if(result.hit!=null)return "已读到关闭控件 · "+("click-already-submitted".equals(result.reason)?"等待节点变化":result.attempted?"控件未接受点击或已变化":"当前条件不允许点击");
        if(result.tree!=null && !result.tree.complete)return "父子控件读取未完成，等待重新读取";
        if(result.tree==null)return "暂时无法读取当前应用控件（"+result.reason+"）";
        return controls==0?"当前应用未暴露跳过或关闭控件":"已读取控件，但广告语境或可点击父控件不足";
    }
    private void applyNativeResult(String pkg,long epoch,long requested,NativeScanResult result) {
        long now=SystemClock.uptimeMillis();
        if(result.observation!=null && result.observation==nativeObservation) {
            if(result.verification==NativeTreeObservation.GONE) {
                trace("native post-tap pkg="+pkg+" result=tree-target-gone evidence=two-current-complete-trees elapsed="+(now-result.observation.acceptedAt)+"ms");
                getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result","原控件已从节点树消失；未进行画面验证").apply();
                if(SceneFramePolicy.pageCloseMayReappear(sceneLastClicked==null?"":sceneLastClicked.rule,result.verification)) {
                    sceneLastClicked=null;sceneAttempts=0;
                }
                nativeObservation=null;sceneLaunchDismissed=true;sceneUntil=Math.min(sceneUntil,now+350);
                nativeAcceptedTargets.remove(nativeObservedTargetKey);nativeObservedTargetKey="";
            } else if(result.observation.expired(now)) {
                boolean present=result.observation.lastFreshState(now)==NativeTreeObservation.PRESENT;
                trace("native post-tap pkg="+pkg+" result="+(present?"target-still-present":"unknown"));
                getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result",present?
                        "系统接受了点击，但原控件仍存在；已停止重复点击":"已提交控件点击，节点信息无法确认结果").apply();
                nativeObservation=null;
            }
        }
        if(result.touch)return; // The main-thread touch callbacks own submission and verification.
        if(!result.accepted) {
            if(result.attempted)getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result","原生控件未接受点击或已变化，等待新控件").apply();
            return;
        }
        nativeAcceptedAt=result.acceptedAt;lastSceneClick=result.acceptedAt;lastClick=result.acceptedAt;
        nativeAcceptedTargets.add(result.targetKey);nativeObservedTargetKey=result.targetKey;
        sceneLastClicked=result.hit;sceneAttempts++;
        boolean openingAction=UiControlPolicy.SKIP.equals(result.hit.rule) || UiControlPolicy.CLOSE.equals(result.hit.rule);
        if(openingAction)sceneOpeningActionSent=true;
        nativeObservation=new NativeTreeObservation(pkg,epoch,result.acceptedAt,result.hit);
        sceneVerify=null;sceneLearning=null;sceneUntil=Math.max(sceneUntil,result.acceptedAt+2400);
        rememberAction(pkg+"  "+result.hit.rule+" 父子控件");
        getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result","系统已接受控件点击，等待父子节点复核").apply();
    }
    private String nativeTargetKey(ControlTree.Snapshot tree,int target,int windowId) {
        return windowId+"|"+tree.structure(target);
    }
    private boolean scopedNativeApp(String pkg) {
        return NetdiskRules.PACKAGE.equals(pkg) || InAppSceneRules.HUYA.equals(pkg) ||
                InAppSceneRules.TENCENT.equals(pkg) || "com.baidu.BaiduMap".equals(pkg) || "com.youku.phone".equals(pkg) || InAppSceneRules.mobilePackage(pkg);
    }
    private void evaluateNativeAction(String pkg,long epoch,long requested,long launchStarted,boolean opening,
            AccessibilityNodeInfo owner,AccessibilityControlTree.Live global,NativeScanResult result) {
        evaluateNativeAction(pkg,epoch,requested,launchStarted,opening,owner,global,result,null);
    }
    private void evaluateNativeAction(String pkg,long epoch,long requested,long launchStarted,boolean opening,
            AccessibilityNodeInfo owner,AccessibilityControlTree.Live global,NativeScanResult result,AccessibilityControlTree.ScopedAd preset) {
        AccessibilityControlTree.ScopedAd scope=preset;
        try {
            AccessibilityControlTree.Live action=scope==null?global:scope.live;
            NativeControlPolicy.Candidate candidate=scope==null?NativeControlPolicy.find(global.tree,opening):scope.candidate;
            // The current owner was already queried before the global read. A failed
            // discovery waits for the next fresh scan rather than repeating the same
            // text queries after a large tree has consumed this scan's deadline.
            long now=SystemClock.uptimeMillis();
            boolean current=scope==null?action.tree.current(pkg,sceneEpoch,now,action.tree.width,action.tree.height):
                action.tree.currentVerifiedScope(pkg,sceneEpoch,now,action.tree.width,action.tree.height,scope.verificationStarted);
            if(!current)result.reason="tree-expired";
            else if(!action.tree.complete)result.reason="tree-incomplete";
            else if(candidate==null)result.reason="no-safe-native-control";
            else if(!sceneRules.nativeAllowed(this,pkg,candidate.label.action))result.reason="rule-disabled";
            else {
                result.hit=candidate.hit(action.tree);
                result.targetKey=nativeTargetKey(action.tree,candidate.target,owner.getWindowId());
                boolean retry=InAppSceneRules.TENCENT.equals(pkg) && result.observation!=null &&
                    result.verification==NativeTreeObservation.PRESENT && result.observation.canTryCurrentTouch(now) &&
                    result.targetKey.equals(nativeObservedTargetKey) && !nativeTouchTargets.contains(result.targetKey);
                boolean firstTouch=InAppSceneRules.HUYA.equals(pkg) || scope!=null;
                if(manualCapture())result.reason="manual-capture";
                else if(retry)queueNativeTouch(pkg,epoch,requested,action,candidate,scope==null?null:scope.scopeRoot,
                        scope==null?owner:scope.ownerRoot,result,true,scope==null?action.tree.time:scope.verificationStarted);
                else if(result.observation!=null || NativeControlPolicy.blockedByOpeningAction(sceneOpeningActionSent,candidate.label.action) ||
                        nativeAcceptedTargets.contains(result.targetKey))
                    result.reason="click-already-submitted";
                else if(sameScene(result.hit,sceneLastClicked) && (sceneAttempts>=2 || now-lastSceneClick<650))result.reason="target-cooldown";
                else if(firstTouch)queueNativeTouch(pkg,epoch,requested,action,candidate,scope==null?null:scope.scopeRoot,
                        scope==null?owner:scope.ownerRoot,result,false,scope==null?action.tree.time:scope.verificationStarted);
                else {
                    result.attempted=true;
                    result.accepted=performNativeControl(pkg,epoch,action,candidate,result.hit,Math.min(requested+650,action.tree.time+250));
                    result.acceptedAt=SystemClock.uptimeMillis();
                    result.reason=result.accepted?"native-click-accepted":"native-click-rejected-or-changed";
                    if(result.accepted)trace(result.hit.rule+" request-to-node-accepted="+(result.acceptedAt-requested)+
                            "ms launch-to-node="+(result.acceptedAt-launchStarted)+"ms source=controls-first");
                }
            }
        }finally{if(scope!=null)scope.close();}
    }
    private boolean nativeChain(AccessibilityNodeInfo start,AccessibilityNodeInfo ancestor,int limit,String pkg,int window) {
        return nativeChain(start,ancestor,limit,pkg,window,Long.MAX_VALUE);
    }
    private boolean nativeChain(AccessibilityNodeInfo start,AccessibilityNodeInfo ancestor,int limit,String pkg,int window,long deadline) {
        List<AccessibilityNodeInfo> held=new ArrayList<>();AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain(start);
        try {
            for(int d=0;node!=null && d<=limit;d++) {
                if(SystemClock.uptimeMillis()>deadline){node.recycle();node=null;return false;}
                for(AccessibilityNodeInfo previous:held)if(previous.equals(node)){node.recycle();node=null;return false;}
                held.add(node);
                if(node.getWindowId()!=window || !pkg.contentEquals(value(node.getPackageName())))return false;
                if(node.equals(ancestor))return true;
                node=AccessibilityControlTree.fetchParent(node);
            }
            if(node!=null){node.recycle();node=null;}
            return false;
        }finally{for(AccessibilityNodeInfo handle:held)handle.recycle();}
    }
    /** A fresh native proof supplies this touch. It is never an old-point fallback. */
    private void queueNativeTouch(String pkg,long epoch,long requested,AccessibilityControlTree.Live live,
            NativeControlPolicy.Candidate candidate,AccessibilityNodeInfo scopeRoot,AccessibilityNodeInfo ownerRoot,
            NativeScanResult result,boolean retry,long verificationStarted) {
        ControlTree.Snapshot tree=live.tree;
        final boolean uncachedScope=scopeRoot!=null && live.uncachedScope;
        AccessibilityNodeInfo label=AccessibilityNodeInfo.obtain(live.handles.get(candidate.label.nodeIndex));
        // A clickable Skip is both label and target. Refresh its one live handle
        // once, so the two checks cannot compare different provider responses.
        AccessibilityNodeInfo target=label.equals(live.handles.get(candidate.target))?label:
                AccessibilityNodeInfo.obtain(live.handles.get(candidate.target));
        AccessibilityNodeInfo scope=scopeRoot==null?null:AccessibilityNodeInfo.obtain(scopeRoot);
        AccessibilityNodeInfo owner=AccessibilityNodeInfo.obtain(ownerRoot);
        AccessibilityControlTree.NodeSnapshot labelProperties=new AccessibilityControlTree.NodeSnapshot(label);
        AccessibilityControlTree.NodeSnapshot targetProperties=new AccessibilityControlTree.NodeSnapshot(target);
        AccessibilityControlTree.NodeSnapshot scopeProperties=scope==null?null:new AccessibilityControlTree.NodeSnapshot(scope);
        AccessibilityControlTree.NodeSnapshot ownerProperties=new AccessibilityControlTree.NodeSnapshot(owner);
        long deadline=Math.min(requested+900,verificationStarted+250);
        final long touchSceneStarted=sceneStarted;
        final long touchControlObservedAt=nativeFirstControlAt;
        result.touch=true;result.attempted=true;result.reason="native-touch-queued";
        Runnable action=()->{
            AccessibilityNodeInfo current=null;
            try {
                long now=SystemClock.uptimeMillis();
                boolean currentProof=scope==null?tree.current(pkg,epoch,now,result.hit.frameWidth,result.hit.frameHeight):
                    tree.currentVerifiedScope(pkg,epoch,now,result.hit.frameWidth,result.hit.frameHeight,verificationStarted);
                if(destroyed || epoch!=sceneEpoch || !pkg.equals(scenePackage) || gesturePending || manualCapture() ||
                    !controlsEnabled(pkg) || !sceneRules.nativeAllowed(this,pkg,result.hit.rule) || now>deadline ||
                    !tree.complete || !currentProof ||
                    nativeTouchTargets.contains(result.targetKey)) {result.reason="native-touch-changed";return;}
                if(candidate.currentLabelTouch && (scope==null || candidate.target!=candidate.label.nodeIndex ||
                        !UiControlPolicy.SKIP.equals(result.hit.rule) || !ControlTree.small(candidate.label.box,tree.width,tree.height))) {
                    result.reason="native-touch-label-without-ad-proof";return;
                }
                if(retry && (result.observation!=nativeObservation || !result.observation.canTryCurrentTouch(now) ||
                    !result.targetKey.equals(nativeObservedTargetKey))) {result.reason="native-touch-no-current-presence";return;}
                if(!retry && (nativeObservation!=null || NativeControlPolicy.blockedByOpeningAction(sceneOpeningActionSent,result.hit.rule) ||
                        nativeAcceptedTargets.contains(result.targetKey))) {
                    result.reason="click-already-submitted";return;
                }
                if(!label.refresh() || target!=label && !target.refresh() || !nativeNodeMatches(label,pkg,candidate.label.box) ||
                    !nativeNodeMatches(target,pkg,tree.nodes.get(candidate.target).box) || !target.isClickable() && !candidate.currentLabelTouch ||
                    !ControlTree.safeParent(candidate.label.box,tree.nodes.get(candidate.target).box,tree.width,tree.height) ||
                    !result.hit.rule.equals(UiControlPolicy.action(value(label.getText()))) &&
                    !result.hit.rule.equals(UiControlPolicy.action(value(label.getContentDescription())))) {
                    result.reason="native-touch-control-changed";return;
                }
                if(labelProperties.changedProperty(new AccessibilityControlTree.NodeSnapshot(label),false,candidate.label.action)!=null ||
                    targetProperties.changedProperty(new AccessibilityControlTree.NodeSnapshot(target),false,tree.nodes.get(candidate.target).role)!=null) {
                    result.reason="native-touch-control-properties-changed";return;
                }
                current=activeRoot();
                KeyguardManager keyguard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
                int window=target.getWindowId();
                if(current==null || !current.equals(owner) || !uncachedScope && !current.refresh() ||
                    ownerProperties.changedProperty(new AccessibilityControlTree.NodeSnapshot(current))!=null ||
                    window!=current.getWindowId() || label.getWindowId()!=window ||
                    !pkg.contentEquals(value(current.getPackageName())) || keyguard!=null && keyguard.isKeyguardLocked() ||
                    !nativeChain(label,target,4,pkg,window,deadline) || !sceneDisplayMatches(result.hit)) {
                    result.reason="native-touch-owner-changed";return;
                }
                if(scope!=null) {
                    if(!scope.refresh() || !nativeNodeMatches(scope,pkg,tree.nodes.get(0).box) ||
                        scopeProperties.changedProperty(new AccessibilityControlTree.NodeSnapshot(scope))!=null ||
                        !nativeChain(target,scope,ControlTree.DEPTH,pkg,window,deadline) ||
                        !nativeChain(scope,current,ControlTree.DEPTH,pkg,window,deadline)) {result.reason="native-touch-scope-changed";return;}
                }else if(!nativeChain(target,current,ControlTree.DEPTH,pkg,window,deadline)) {result.reason="native-touch-parent-changed";return;}
                Rect bounds=new Rect();target.getBoundsInScreen(bounds);
                int x=bounds.centerX(),y=bounds.centerY();
                BilibiliVisualMatcher.Hit touch=new BilibiliVisualMatcher.Hit(result.hit.rule,x,y,1,tree.width,tree.height)
                        .withTextBounds(bounds.left,bounds.top,bounds.right,bounds.bottom);
                touch.nativeOnly=true;touch.treeAssisted=true;
                if(SystemClock.uptimeMillis()>deadline || epoch!=sceneEpoch){result.reason="native-touch-expired-before-occlusion";return;}
                if(!touchTargetClear(pkg,touch,current)){result.reason="native-touch-covered";return;}
                if(SystemClock.uptimeMillis()>deadline || epoch!=sceneEpoch){result.reason="native-touch-expired-after-occlusion";return;}
                boolean accepted=tapPoint(x,y,result.hit.rule+" current-native",0,()->{
                    long completed=SystemClock.uptimeMillis();
                    trace("native TOUCH completed=true pkg="+pkg+" request-to-touch-complete="+(completed-requested)+
                            "ms scene-to-touch-complete="+(completed-touchSceneStarted)+"ms"+
                            (touchControlObservedAt>0?" observed-control-to-touch-complete="+(completed-touchControlObservedAt)+"ms":""));
                    if(epoch!=sceneEpoch || !pkg.equals(scenePackage) || destroyed)return;
                    nativeAcceptedAt=lastClick=sceneGestureCompletedAt=completed;
                    nativeObservation=new NativeTreeObservation(pkg,epoch,completed,result.hit);
                    nativeObservation.touchSubmitted();nativeObservedTargetKey=result.targetKey;
                    sceneUntil=Math.max(sceneUntil,completed+2400);
                    getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result","当前控件触摸已完成，等待节点复核").apply();
                    wakeScenePoll();
                },()->{
                    trace("native TOUCH completed=false pkg="+pkg);
                    if(epoch==sceneEpoch && pkg.equals(scenePackage)) {
                        nativeObservation=null;
                        getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result","当前控件触摸未完成，结果未知").apply();
                        wakeScenePoll();
                    }
                });
                result.accepted=accepted;result.acceptedAt=SystemClock.uptimeMillis();
                result.reason=accepted?"native-touch-submitted":"native-touch-rejected";
                trace("native TOUCH submitted="+accepted+" pkg="+pkg+" request-to-touch-submitted="+
                    (result.acceptedAt-requested)+"ms scene-to-touch-submitted="+(result.acceptedAt-touchSceneStarted)+
                    "ms label="+candidate.label.nodeIndex+" ancestor="+candidate.target+
                    " point="+(candidate.currentLabelTouch?"verified-skip-label":"verified-ancestor-center")+
                    " proof="+(scope==null?"complete-current-tree":"independent-complete-ad-scope"));
                if(accepted) {
                    nativeAcceptedAt=lastClick=lastSceneClick=result.acceptedAt;
                    nativeAcceptedTargets.add(result.targetKey);nativeTouchTargets.add(result.targetKey);
                    if(result.observation!=null)result.observation.touchSubmitted();
                    sceneLastClicked=result.hit;sceneAttempts++;
                    if(UiControlPolicy.SKIP.equals(result.hit.rule) || UiControlPolicy.CLOSE.equals(result.hit.rule))sceneOpeningActionSent=true;
                    sceneUntil=Math.max(sceneUntil,result.acceptedAt+2600);
                    getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result","已提交当前控件触摸，等待输入完成与节点复核").apply();
                }
            }catch(RuntimeException error){result.reason="native-touch-provider-"+error.getClass().getSimpleName();}
            finally{if(current!=null)current.recycle();label.recycle();if(target!=label)target.recycle();owner.recycle();if(scope!=null)scope.recycle();}
        };
        if(!handler.post(action)){label.recycle();if(target!=label)target.recycle();owner.recycle();if(scope!=null)scope.recycle();result.reason="native-touch-worker-stopped";}
    }
    private boolean performNativeControl(String pkg,long epoch,AccessibilityControlTree.Live live,NativeControlPolicy.Candidate candidate,
            BilibiliVisualMatcher.Hit hit,long deadline) {
        ControlTree.Snapshot tree=live.tree;
        if(candidate.currentLabelTouch)return false;
        if(!tree.current(pkg,sceneEpoch,SystemClock.uptimeMillis(),hit.frameWidth,hit.frameHeight))return false;
        AccessibilityNodeInfo label=live.handles.get(candidate.label.nodeIndex),target=live.handles.get(candidate.target);
        if(!label.refresh() || !target.refresh() || !nativeNodeMatches(label,pkg,candidate.label.box) ||
                !nativeNodeMatches(target,pkg,tree.nodes.get(candidate.target).box) || !target.isClickable())return false;
        if(!hit.rule.equals(UiControlPolicy.action(value(label.getText()))) && !hit.rule.equals(UiControlPolicy.action(value(label.getContentDescription()))))return false;
        AccessibilityNodeInfo ancestor=AccessibilityNodeInfo.obtain(label);boolean linked=false;
        try {
            for(int d=0;ancestor!=null && d<5;d++) {
                if(ancestor.equals(target)){linked=true;break;}
                AccessibilityNodeInfo next=AccessibilityControlTree.fetchParent(ancestor);ancestor.recycle();ancestor=next;
            }
        } finally{if(ancestor!=null)ancestor.recycle();}
        if(!linked || destroyed || gesturePending || manualCapture() || epoch!=sceneEpoch || !controlsEnabled(pkg) ||
                !sceneRules.nativeAllowed(this,pkg,hit.rule))return false;
        AccessibilityNodeInfo current=activeRoot();
        try {
            KeyguardManager keyguard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
            if(keyguard!=null && keyguard.isKeyguardLocked())return false;
            if(current==null || current.getWindowId()!=target.getWindowId() || !pkg.contentEquals(value(current.getPackageName())) ||
                    !sceneDisplayMatches(hit) || !touchTargetClear(pkg,hit,current) || SystemClock.uptimeMillis()>deadline || epoch!=sceneEpoch)return false;
            boolean accepted=target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if(accepted){nativeAcceptedAt=lastClick=SystemClock.uptimeMillis();nativeAcceptedTargets.add(nativeTargetKey(tree,candidate.target,target.getWindowId()));}
            trace(hit.rule+" native ACTION_CLICK accepted="+accepted+" pkg="+pkg+" label="+candidate.label.nodeIndex+" ancestor="+candidate.target+" source=controls-first");
            return accepted;
        } finally{if(current!=null)current.recycle();}
    }
    private void scanVisualScene(String pkg,long requested,long epoch,ControlTree.Snapshot nativeTree) {
        if(!RecognitionMode.visuals(this) || destroyed || epoch!=sceneEpoch)return;
        if(sceneRecheckPending)return;
        if(!SceneFramePolicy.captureAfterAction(requested,gesturePending,sceneVerify!=null,sceneGestureCompletedAt))return;
        if (android.os.Build.VERSION.SDK_INT<30 || scenePending || SystemClock.uptimeMillis()-requested>650) return;
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
                        boolean processed=RecognitionMode.visuals(SkipService.this) && screen!=null && SystemClock.uptimeMillis()-requested<760 && epoch==sceneEpoch && !destroyed;
                        if (processed) {
                            // Reuse only this request's fresh native evidence. A second tree
                            // walk after capture would consume the same frame's OCR budget.
                            ControlTree.Snapshot tree=nativeTree;
                            if(tree!=null && !tree.current(pkg,sceneEpoch,SystemClock.uptimeMillis(),screen.getWidth(),screen.getHeight()))tree=null;
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
        if(!RecognitionMode.visuals(this))return false;
        if(captureRequested || now-lastCaptureRequest<350)return false;
        captureRequested=true;lastCaptureRequest=now;screenshotRequestCount++;trace("screen capture requested; mode=controls+visual");return true;
    }
    private boolean sameScene(BilibiliVisualMatcher.Hit a,BilibiliVisualMatcher.Hit b) {
        int tolerance=Math.max(18,getResources().getDisplayMetrics().widthPixels/50);
        return a!=null && b!=null && a.rule.equals(b.rule) && a.frameWidth==b.frameWidth && a.frameHeight==b.frameHeight &&
                Math.abs(a.x-b.x)<tolerance && Math.abs(a.y-b.y)<tolerance;
    }
    private void applyLearning(String pkg,long epoch,long requested,BilibiliVisualMatcher.Hit found,ClickLearningSession session,int state) {
        if(!RecognitionMode.visuals(this))return;
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
        AccessibilityNodeInfo root=activeRoot();
        try{return touchTargetClear(pkg,hit,root);}
        finally{if(root!=null)root.recycle();}
    }
    private boolean touchTargetClear(String pkg,BilibiliVisualMatcher.Hit hit,AccessibilityNodeInfo active) {
        List<AccessibilityWindowInfo> windows=null;
        List<AccessibilityNodeInfo> roots=new ArrayList<>();
        try {
            windows=getWindows();int targetLayer=Integer.MIN_VALUE,activeId=-1;
            if(active!=null && active.getPackageName()!=null && pkg.contentEquals(active.getPackageName()))activeId=active.getWindowId();
            List<String> owners=new ArrayList<>();
            for(AccessibilityWindowInfo window:windows) {
                AccessibilityNodeInfo root=AccessibilityControlTree.fetchWindowRoot(window);String owner=root==null?"":value(root.getPackageName());
                roots.add(root);owners.add(owner);
                if(WindowOwnershipPolicy.target(pkg,owner,window.getId(),activeId))targetLayer=Math.max(targetLayer,window.getLayer());
            }
            if(targetLayer==Integer.MIN_VALUE)return false;
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
        finally {
            for(AccessibilityNodeInfo root:roots)if(root!=null)root.recycle();
            if(windows!=null)for(AccessibilityWindowInfo window:windows)window.recycle();
        }
    }
    private boolean visibleOverlayCovers(AccessibilityNodeInfo node,BilibiliVisualMatcher.Hit hit,int depth,int[] visited) {
        if(node==null || depth>15 || ++visited[0]>120)return true;
        if(node.isVisibleToUser() && (node.isClickable() || node.isFocusable() || node.getRangeInfo()!=null || node.getChildCount()==0)) {
            Rect bounds=new Rect();node.getBoundsInScreen(bounds);
            if((long)bounds.width()*bounds.height()<(long)hit.frameWidth*hit.frameHeight*.65f &&
                    TouchTargetGuard.covered(hit,new int[]{bounds.left,bounds.top,bounds.right,bounds.bottom}))return true;
        }
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=AccessibilityControlTree.fetchChild(node,i);
            try{if(visibleOverlayCovers(child,hit,depth+1,visited))return true;}
            finally{if(child!=null)child.recycle();}
        }
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
        if(!RecognitionMode.visuals(this))return;
        if(hit!=null && hit.nativeOnly) {lastSceneFrame=0;scanScene(pkg);return;}
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
        if(!RecognitionMode.visuals(this) || destroyed || epoch!=sceneEpoch || requested-originalRequest>850 || gesturePending) {sceneRecheckPending=false;return;}
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
                        if(RecognitionMode.visuals(SkipService.this) && screen!=null && epoch==sceneEpoch && !destroyed)
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
                        AccessibilityNodeInfo next=AccessibilityControlTree.fetchParent(ancestor);ancestor.recycle();ancestor=next;
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
        scheduleBiliPoll();
    }

    private void scheduleBiliPoll() {
        if (biliPoll == null) {
            biliPoll = new Runnable() {
                @Override public void run() {
                    if (destroyed || SystemClock.uptimeMillis() > biliUntil || !BilibiliRules.PACKAGE.equals(scenePackage)) { biliPoll = null; return; }
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
        try{return root != null && root.getPackageName() != null && BilibiliRules.PACKAGE.contentEquals(root.getPackageName());}
        finally{if(root!=null)root.recycle();}
    }

    private void scanBili() {
        if(manualCapture())return;
        long now = SystemClock.uptimeMillis();
        if (gesturePending || biliRules == null || !BilibiliRules.PACKAGE.equals(scenePackage) || now - lastBiliScan < 250) return;
        lastBiliScan = now;
        boolean ads = BilibiliRules.ads(this), live = BilibiliRules.live(this);
        if (!ads && !live && !InAppSceneRules.genericPages(this)) return;
        if(biliVerify==null)scanBiliControls(now,ads,live);
        if (!RecognitionMode.visuals(this) || android.os.Build.VERSION.SDK_INT < 30 || biliFramePending || now - lastBiliFrame < 350 ||
                (biliVerify == null && (!ads || now - lastBiliAdClick < CLICK_COOLDOWN_MS) &&
                 (!live || now - lastBiliLiveClick < CLICK_COOLDOWN_MS) && !InAppSceneRules.genericPages(this))) return;
        if(sceneRules!=null && sceneRules.textReady() && BilibiliRules.PACKAGE.equals(scenePackage) && scenePoll!=null && now-sceneStarted<SCAN_WINDOW_MS)return;
        if(!reserveScreenshot(now))return;
        lastBiliFrame = now; biliFramePending = true;
        final long biliEpoch=sceneEpoch;
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
                    handler.post(() -> { biliFramePending = false;if(biliEpoch==sceneEpoch)applyBiliFrame(match, now); });
                }); } catch (RuntimeException error) { buffer.close(); biliFramePending = false; }
            }
            @Override public void onFailure(int errorCode) { captureRequested=false;biliFramePending = false; trace("bili screenshot unavailable: " + errorCode); }
        }); } catch (RuntimeException error) { captureRequested=false;biliFramePending = false; }
    }

    private void scanBiliControls(long requested,boolean ads,boolean liveEnabled) {
        if(biliControlPending || !getSharedPreferences("settings",MODE_PRIVATE).getBoolean("enabled",true))return;
        biliControlPending=true;final long epoch=sceneEpoch;
        android.view.Display display=getSystemService(android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null){biliControlPending=false;return;}
        android.graphics.Point size=new android.graphics.Point();display.getRealSize(size);
        try {controlWorker.execute(()->{
            AccessibilityNodeInfo root=null;String acceptedRule="";Rect acceptedBounds=null;
            try {
                long started=SystemClock.uptimeMillis();
                if(destroyed || epoch!=sceneEpoch || started-requested>350 || manualCapture())return;
                root=activeRoot();
                if(root==null || !BilibiliRules.PACKAGE.contentEquals(value(root.getPackageName())))return;
                try(AccessibilityControlTree.Live current=AccessibilityControlTree.captureLive(root,BilibiliRules.PACKAGE,epoch,size.x,size.y,started+200)) {
                    if(!current.tree.complete || !current.tree.current(BilibiliRules.PACKAGE,sceneEpoch,SystemClock.uptimeMillis(),size.x,size.y))return;
                    observeBiliPause(current,epoch,root.getWindowId());
                    BilibiliRules.NodeHit hit=biliRules.node(current,size.x,size.y,
                        ads,
                        liveEnabled && started-lastBiliLiveClick>CLICK_COOLDOWN_MS);
                    // This proven sheet is rearmed only after two complete trees
                    // show its old close control gone. A different, newly opened
                    // sheet need not inherit the generic eight-second cooldown.
                    if(hit!=null && hit.pause==null && BilibiliVisualMatcher.AD.equals(hit.rule) &&
                            started-lastBiliAdClick<=CLICK_COOLDOWN_MS)return;
                    BiliPauseProof pauseProof=hit!=null && hit.pause!=null?new BiliPauseProof(current,hit.pause):null;
                    if(hit==null || !hit.node.refresh() || !nativeNodeMatches(hit.node,BilibiliRules.PACKAGE,
                        new int[]{hit.bounds.left,hit.bounds.top,hit.bounds.right,hit.bounds.bottom}))return;
                    BilibiliRules.NodeHit refreshed=biliRules.node(current,size.x,size.y,ads,liveEnabled);
                    if(refreshed==null || !hit.rule.equals(refreshed.rule) || !hit.node.equals(refreshed.node))return;
                    AccessibilityNodeInfo target=AccessibilityNodeInfo.obtain(hit.node);
                    try {
                        for(int depth=0;target!=null && !target.isClickable() && depth<4;depth++) {
                            AccessibilityNodeInfo parent=AccessibilityControlTree.fetchParent(target);target.recycle();target=parent;
                        }
                        if(target==null || !target.refresh() || !target.isClickable() || !target.isVisibleToUser() || !target.isEnabled())return;
                        int targetIndex=current.handles.indexOf(target);if(targetIndex<0)return;
                        if(hit.pause!=null && targetIndex!=hit.pause.targetIndex)return;
                        String targetKey=nativeTargetKey(current.tree,targetIndex,target.getWindowId());
                        if(nativeObservation!=null || nativeAcceptedTargets.contains(targetKey) || SystemClock.uptimeMillis()<nativeAcceptedAt+120)return;
                        Rect bounds=new Rect();target.getBoundsInScreen(bounds);
                        if(!ControlTree.safeParent(new int[]{hit.bounds.left,hit.bounds.top,hit.bounds.right,hit.bounds.bottom},
                            new int[]{bounds.left,bounds.top,bounds.right,bounds.bottom},size.x,size.y))return;
                        BilibiliVisualMatcher.Hit candidate=new BilibiliVisualMatcher.Hit(hit.rule,bounds.centerX(),bounds.centerY(),1,size.x,size.y)
                            .withTextBounds(bounds.left,bounds.top,bounds.right,bounds.bottom);
                        AccessibilityNodeInfo foreground=activeRoot();
                        try {
                            KeyguardManager keyguard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
                            if(keyguard!=null && keyguard.isKeyguardLocked())return;
                            boolean allowed=BilibiliVisualMatcher.LIVE.equals(hit.rule)?BilibiliRules.live(SkipService.this):BilibiliRules.ads(SkipService.this);
                            if(destroyed || epoch!=sceneEpoch || gesturePending || manualCapture() || !allowed ||
                                !getSharedPreferences("settings",MODE_PRIVATE).getBoolean("enabled",true) ||
                                foreground==null || foreground.getWindowId()!=target.getWindowId() ||
                                !BilibiliRules.PACKAGE.contentEquals(value(foreground.getPackageName())) ||
                                !sceneDisplayMatches(candidate) || !touchTargetClear(BilibiliRules.PACKAGE,candidate,foreground) ||
                                !current.tree.current(BilibiliRules.PACKAGE,sceneEpoch,SystemClock.uptimeMillis(),size.x,size.y))return;
                            if(pauseProof!=null && !validateBiliPause(current,pauseProof,foreground.getWindowId(),requested+850))return;
                            if(pauseProof!=null && (!foreground.refresh() || !foreground.equals(root) || foreground.getWindowId()!=target.getWindowId() ||
                                !BilibiliRules.PACKAGE.contentEquals(value(foreground.getPackageName())) ||
                                !nativeChain(current.handles.get(pauseProof.candidate.panelIndex),foreground,ControlTree.DEPTH,
                                    BilibiliRules.PACKAGE,foreground.getWindowId()) ||
                                keyguard!=null && keyguard.isKeyguardLocked() || !touchTargetClear(BilibiliRules.PACKAGE,candidate,foreground)))return;
                            if(destroyed || epoch!=sceneEpoch || gesturePending || manualCapture() ||
                                !BilibiliRules.ads(SkipService.this) && BilibiliVisualMatcher.AD.equals(hit.rule) ||
                                !BilibiliRules.live(SkipService.this) && BilibiliVisualMatcher.LIVE.equals(hit.rule) ||
                                !current.tree.current(BilibiliRules.PACKAGE,sceneEpoch,SystemClock.uptimeMillis(),size.x,size.y))return;
                            boolean accepted=target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            trace(hit.rule+" native ACTION_CLICK accepted="+accepted+" pkg="+BilibiliRules.PACKAGE+
                                " source="+(pauseProof==null?"guarded-bili-controls":"guarded-bili-pause-controls")+
                                " label="+current.handles.indexOf(hit.node)+" ancestor="+targetIndex+
                                " request-to-node-accepted="+(SystemClock.uptimeMillis()-requested)+"ms");
                            if(accepted){
                                nativeAcceptedAt=lastClick=SystemClock.uptimeMillis();nativeAcceptedTargets.add(targetKey);acceptedRule=hit.rule;acceptedBounds=new Rect(hit.bounds);
                                if(pauseProof!=null)biliPauseObservation=new BiliPauseObservation(epoch,nativeAcceptedAt,targetKey,target.getWindowId());
                            }
                        }finally{if(foreground!=null)foreground.recycle();}
                    }finally{if(target!=null)target.recycle();}
                }
            }catch(RuntimeException error){trace("bili native provider unavailable: "+error.getClass().getSimpleName());}
            finally {
                if(root!=null)root.recycle();
                final String rule=acceptedRule;final Rect bounds=acceptedBounds;
                handler.post(()->{
                    biliControlPending=false;
                    if(destroyed || epoch!=sceneEpoch || rule.isEmpty())return;
                    long now=SystemClock.uptimeMillis();lastBiliClick=now;
                    if(BilibiliVisualMatcher.LIVE.equals(rule))lastBiliLiveClick=now;else lastBiliAdClick=now;
                    rememberAction(BilibiliRules.PACKAGE+"  "+rule+" 控件");
                    if(RecognitionMode.visuals(SkipService.this))biliVerify=new BilibiliVisualMatcher.Hit(rule,bounds.centerX(),bounds.centerY(),1,size.x,size.y);
                    getSharedPreferences("settings",MODE_PRIVATE).edit().putString("last_result",
                        RecognitionMode.visuals(SkipService.this)?"系统已接受控件点击，等待画面检查":"系统已接受控件点击；未进行画面验证").apply();
                });
            }
        });}catch(RuntimeException error){biliControlPending=false;}
    }

    private static final class BiliPauseObservation {
        final long epoch,acceptedAt;
        final String targetKey;
        final int window;
        int absentFrames;
        long lastAbsentFrame;
        boolean unknownReported;
        BiliPauseObservation(long epoch,long acceptedAt,String targetKey,int window) {
            this.epoch=epoch;this.acceptedAt=acceptedAt;this.targetKey=targetKey;this.window=window;
        }
    }
    private void observeBiliPause(AccessibilityControlTree.Live current,long epoch,int window) {
        BiliPauseObservation observation=biliPauseObservation;
        if(observation==null)return;
        if(observation.epoch!=epoch || observation.window!=window){biliPauseObservation=null;return;}
        if(!current.tree.complete || current.tree.time<=observation.acceptedAt+100 ||
                !current.tree.current(BilibiliRules.PACKAGE,sceneEpoch,SystemClock.uptimeMillis(),current.tree.width,current.tree.height))return;
        boolean present=false;
        for(java.util.Map.Entry<Integer,String> entry:current.diagnosticLabels.entrySet()) {
            if("关闭暂停页".equals(entry.getValue()) && current.tree.nodes.get(entry.getKey()).visible){present=true;break;}
        }
        if(present){observation.absentFrames=0;observation.lastAbsentFrame=0;}
        else if(current.tree.time-observation.lastAbsentFrame>=100) {
            observation.absentFrames++;observation.lastAbsentFrame=current.tree.time;
            if(observation.absentFrames>=2) {
                trace("bili-ad-close native post-tap pkg="+BilibiliRules.PACKAGE+" result=tree-target-gone proof=two-complete-current-trees");
                nativeAcceptedTargets.remove(observation.targetKey);biliPauseObservation=null;
                handler.post(()->{
                    if(!destroyed && epoch==sceneEpoch)getSharedPreferences("settings",MODE_PRIVATE).edit()
                        .putString("last_result","系统已接受点击；两次当前控件树确认原广告关闭控件消失").apply();
                });
                return;
            }
        }
        if(!observation.unknownReported && SystemClock.uptimeMillis()-observation.acceptedAt>=2400) {
            observation.unknownReported=true;
            trace("bili-ad-close native post-tap pkg="+BilibiliRules.PACKAGE+" result="+(present?"target-still-present":"unknown"));
        }
    }

    private static final class BiliPauseProof {
        final BilibiliNativePolicy.Candidate candidate;
        final java.util.Map<Integer,AccessibilityControlTree.NodeSnapshot> properties=new java.util.LinkedHashMap<>();
        BiliPauseProof(AccessibilityControlTree.Live current,BilibiliNativePolicy.Candidate candidate) {
            this.candidate=candidate;
            for(int start:new int[]{candidate.labelIndex,candidate.adIndex,candidate.menuIndex,candidate.imageIndex}) {
                for(int at=start,depth=0;at>=0 && depth<8;depth++,at=current.tree.nodes.get(at).parent) {
                    if(!properties.containsKey(at))properties.put(at,new AccessibilityControlTree.NodeSnapshot(current.handles.get(at)));
                    if(at==candidate.panelIndex)break;
                }
            }
        }
    }
    private boolean validateBiliPause(AccessibilityControlTree.Live current,BiliPauseProof proof,int window,long deadline) {
        for(java.util.Map.Entry<Integer,AccessibilityControlTree.NodeSnapshot> entry:proof.properties.entrySet()) {
            if(SystemClock.uptimeMillis()>deadline)return false;
            int index=entry.getKey();AccessibilityNodeInfo node=current.handles.get(index);
            if(!node.refresh() || node.getWindowId()!=window ||
                    !nativeNodeMatches(node,BilibiliRules.PACKAGE,current.tree.nodes.get(index).box))return false;
            String difference=entry.getValue().changedProperty(new AccessibilityControlTree.NodeSnapshot(node));
            if(difference!=null){trace("bili pause rejected phase=properties reason="+difference);return false;}
            if(index!=proof.candidate.panelIndex) {
                int parentIndex=current.tree.nodes.get(index).parent;
                AccessibilityNodeInfo parent=AccessibilityControlTree.fetchParent(node);
                try{if(parent==null || parentIndex<0 || !parent.equals(current.handles.get(parentIndex)))return false;}
                finally{if(parent!=null)parent.recycle();}
            }
        }
        AccessibilityNodeInfo label=current.handles.get(proof.candidate.labelIndex),
            mark=current.handles.get(proof.candidate.adIndex),menu=current.handles.get(proof.candidate.menuIndex);
        return SystemClock.uptimeMillis()<=deadline &&
            ("关闭暂停页".equals(value(label.getText()).trim()) || "关闭暂停页".equals(value(label.getContentDescription()).trim())) &&
            (proof.candidate.adLabel.equals(value(mark.getText()).trim()) || proof.candidate.adLabel.equals(value(mark.getContentDescription()).trim())) &&
            ("不感兴趣".equals(value(menu.getText()).trim()) || "不感兴趣".equals(value(menu.getContentDescription()).trim()));
    }

    private void applyBiliFrame(BilibiliVisualMatcher.Hit hit, long requestedAt) {
        if(!RecognitionMode.visuals(this))return;
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
        if(!controlsEnabled(pkg))return;
        if(!pkg.equals(scenePackage))onSceneEvent(pkg);
        scanScene(pkg);
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
        return RecognitionMode.visuals(this) && AppProfiles.enabled(this) && getSharedPreferences("settings", MODE_PRIVATE).getBoolean("enabled", true);
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
        final java.util.concurrent.atomic.AtomicBoolean callbackDone=new java.util.concurrent.atomic.AtomicBoolean();
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 75)).build();
        boolean submitted;
        try {submitted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription completed) {
                if(!callbackDone.compareAndSet(false,true))return;
                if(serial==gestureSerial)gesturePending=false;
                trace(rule + " gesture completed x=" + x + " y=" + y);
                if(frameRequested>0)trace(rule+" capture-to-click-complete="+(SystemClock.uptimeMillis()-frameRequested)+"ms");
                if(completedAction!=null)completedAction.run();
            }

            @Override public void onCancelled(GestureDescription cancelled) {
                if(!callbackDone.compareAndSet(false,true))return;
                if(serial==gestureSerial)gesturePending=false;
                trace(rule + " gesture cancelled x=" + x + " y=" + y);
                if(cancelledAction!=null)cancelledAction.run();
            }
        }, handler);}catch(RuntimeException error){gesturePending=false;trace(rule+" gesture error: "+error.getClass().getSimpleName());return false;}
        if (submitted) {
            handler.postDelayed(() -> {
                if(gesturePending && serial==gestureSerial && callbackDone.compareAndSet(false,true)){gesturePending=false;trace(rule+" gesture callback timeout");if(cancelledAction!=null)cancelledAction.run();}
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
        for (int i = 0; i < node.getChildCount(); i++) collect(AccessibilityControlTree.fetchChild(node,i), nodes, depth + 1);
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
            AccessibilityNodeInfo parent = AccessibilityControlTree.fetchParent(node);
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
        AccessibilityNodeInfo target = node.isClickable() ? node : AccessibilityControlTree.fetchParent(node);
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
        return AccessibilityControlTree.fetchActiveRoot(this);
    }

    private String lower(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT);
    }

    private String value(CharSequence input) {
        return input == null ? "" : input.toString();
    }

    @Override public void onDestroy() {
        running=false;ocrReady=false;
        if(desktopService==this){desktopService=null;AccessibilityControlTree.setDiagnosticLogger(null);}
        destroyed = true;
        if(recognitionSettings!=null)getSharedPreferences("settings",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(recognitionSettings);
        if (scenePoll != null) handler.removeCallbacks(scenePoll);
        if (foregroundPoll != null) handler.removeCallbacks(foregroundPoll);
        if (boundsPoll != null) handler.removeCallbacks(boundsPoll);
        if (biliPoll != null) handler.removeCallbacks(biliPoll);
        biliWorker.shutdown();
        controlWorker.shutdown();
        foregroundWorker.shutdown();
        if (sceneRules != null) sceneWorker.execute(() -> sceneRules.close());
        sceneWorker.shutdown();
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
        if (textModel != null) textModel.close();
        super.onDestroy();
    }

    @Override public void onInterrupt() { }
}
