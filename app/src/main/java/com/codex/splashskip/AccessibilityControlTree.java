package com.codex.splashskip;

import android.graphics.Rect;
import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.*;
import java.util.function.Consumer;

/** Bounded Android adapter. Text is reduced to semantic roles before leaving the current frame. */
final class AccessibilityControlTree {
    private static volatile Consumer<String> diagnosticLogger;
    static void setDiagnosticLogger(Consumer<String> logger){diagnosticLogger=logger;}
    private static void logScopedDiagnostic(String message){
        Consumer<String> logger=diagnosticLogger;
        if(logger==null)android.util.Log.i("SplashSkipVisual",message);
        else try{logger.accept(message);}catch(RuntimeException unavailable){/* Diagnostics never authorize or prevent cleanup. */}
    }
    static final class Live implements AutoCloseable {
        ControlTree.Snapshot tree;
        final List<AccessibilityNodeInfo> handles=new ArrayList<>();
        final Map<Integer,String> diagnosticLabels=new HashMap<>();
        BoundedNodeWalker.Stats traversal;
        int verifiedAliases;
        final List<Integer> aliasParents=new ArrayList<>();
        boolean prefetchScope;
        boolean uncachedScope;
        public void close(){for(AccessibilityNodeInfo n:handles)n.recycle();handles.clear();}
    }
    static Live captureLive(AccessibilityNodeInfo root,String pkg,long epoch,int w,int h,long deadline) {
        return captureLive(root,pkg,epoch,w,h,deadline,false);
    }
    private static Live captureLive(AccessibilityNodeInfo root,String pkg,long epoch,int w,int h,long deadline,boolean prefetchScope) {
        Live live=new Live();
        live.prefetchScope=prefetchScope;
        try{live.tree=capture(root,pkg,epoch,w,h,deadline,live);return live;}
        catch(RuntimeException error){live.close();throw error;}
    }
    static final class ScopedAd implements AutoCloseable {
        final Live live;
        final NativeControlPolicy.Candidate candidate;
        final AccessibilityNodeInfo scopeRoot,ownerRoot;
        final long verificationStarted;
        private boolean closed;
        ScopedAd(Live live,NativeControlPolicy.Candidate candidate,AccessibilityNodeInfo scopeRoot,AccessibilityNodeInfo ownerRoot,long verificationStarted) {
            this.live=live;this.candidate=candidate;this.scopeRoot=scopeRoot;this.ownerRoot=ownerRoot;
            this.verificationStarted=verificationStarted;
        }
        public synchronized void close(){
            if(closed)return;closed=true;
            try{live.close();}finally{try{scopeRoot.recycle();}finally{ownerRoot.recycle();}}
        }
    }
    private static final int SCOPED_HINT_LIMIT=8,SCOPED_ATTEMPT_LIMIT=5,SCOPED_ANCESTOR_LIMIT=5;
    /** Scheduling/diagnostic evidence only; it is never a proof that can authorize input. */
    static final class DiscoveryEvidence {
        long explicitControlAt;
        boolean hasExplicitControl(){return explicitControlAt>0;}
    }
    private static final class ScopeDiagnostics {
        final String pkg;
        final long started=SystemClock.uptimeMillis();
        int queries,found,hints,skips,attempts,nodes;
        long propertyRefreshMs,propertyParentMs,ownerRefreshMs,ownerParentMs,recaptureMs;
        String verification="individual-refresh";
        boolean complete;
        BoundedNodeWalker.Stats traversal;
        String phase="context";
        String lastReason="discovery-no-skip";
        ScopeDiagnostics(String pkg){this.pkg=pkg;}
        void captured(Live live){nodes=live.tree.nodes.size();complete=live.tree.complete;traversal=live.traversal;}
        void reject(String reason,int node,String semantic){
            lastReason=reason;
            logScopedDiagnostic("native scoped rejection pkg="+pkg+" reason="+reason+" phase="+phase+
                " node="+node+" semantic="+semantic+" attempt="+attempts+" nodes="+nodes+" complete="+complete+
                " traversal="+(traversal==null?"none":traversal.reasons())+timings()+" elapsed="+(SystemClock.uptimeMillis()-started)+"ms");
        }
        String timings(){return " property_refresh="+propertyRefreshMs+" property_parent="+propertyParentMs+
            " owner_refresh="+ownerRefreshMs+" owner_parent="+ownerParentMs+" recapture="+recaptureMs+
            "ms verification="+verification;}
        void failed(){
            logScopedDiagnostic("native scoped discovery pkg="+pkg+" result=none reason="+lastReason+" phase="+phase+
                " queries="+queries+" found="+found+" hints="+hints+" skips="+skips+" attempts="+attempts+
                " nodes="+nodes+" complete="+complete+" elapsed="+(SystemClock.uptimeMillis()-started)+"ms");
        }
    }
    /** Partial globals supply discovery hints only. Every proof comes from a fresh independent walk. */
    static ScopedAd captureScopedAd(Live global,String pkg,long epoch,int w,int h,long deadline) {
        AccessibilityNodeInfo owner=global==null || global.handles.isEmpty()?null:global.handles.get(0);
        return captureScopedAd(global,owner,pkg,epoch,w,h,deadline);
    }
    /** A current owner also permits discovery before a slow or incomplete global traversal. */
    static ScopedAd captureScopedAd(Live global,AccessibilityNodeInfo currentOwner,String pkg,long epoch,int w,int h,long deadline) {
        return captureScopedAd(global,currentOwner,pkg,epoch,w,h,deadline,null);
    }
    static ScopedAd captureScopedAd(Live global,AccessibilityNodeInfo currentOwner,String pkg,long epoch,int w,int h,long deadline,
            DiscoveryEvidence evidence) {
        return captureScopedAd(global,currentOwner,pkg,epoch,w,h,deadline,evidence,null);
    }
    static ScopedAd captureScopedAd(Live global,AccessibilityNodeInfo currentOwner,String pkg,long epoch,int w,int h,long deadline,
            DiscoveryEvidence evidence,AccessibilityService service) {
        ScopeDiagnostics diagnostic=new ScopeDiagnostics(pkg);
        AccessibilityNodeInfo owner=null,uniqueTarget=null;
        List<AccessibilityNodeInfo> tried=new ArrayList<>(),hints=new ArrayList<>(),strictHints=new ArrayList<>();boolean proved=false;
        try {
            if(pkg==null || w<=0 || h<=0){diagnostic.lastReason="discovery-context";return null;}
            if(currentOwner==null){diagnostic.lastReason="discovery-owner-unavailable";return null;}
            if(global!=null && (global.tree==null || !pkg.equals(global.tree.pkg) || epoch!=global.tree.epoch ||
                    w!=global.tree.width || h!=global.tree.height || global.handles.size()!=global.tree.nodes.size())) {
                diagnostic.lastReason="discovery-context";return null;
            }
            if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
            diagnostic.phase="discovery-owner";
            owner=AccessibilityNodeInfo.obtain(currentOwner);
            if(!owner.refresh()){diagnostic.lastReason="discovery-owner-refresh";return null;}
            if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
            int window=owner.getWindowId();
            if(window<0 || !owned(owner,pkg,window)){diagnostic.lastReason="discovery-owner-ownership";return null;}
            // Collect all queries before proving a scope. Another current control
            // in a sibling layer must not be hidden by an early successful walk.
            diagnostic.phase="query";
            for(String query:new String[]{"跳过","skip","关闭广告"}) {
                if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
                List<AccessibilityNodeInfo> queried=null;
                try {
                    diagnostic.queries++;queried=findText(owner,query);
                    if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
                    if(queried==null){diagnostic.lastReason="discovery-query-unavailable";return null;}
                    diagnostic.found+=queried.size();
                    for(AccessibilityNodeInfo hint:queried) {
                        if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
                        if(!addDiscoveryHint(hint,hints,diagnostic))return null;
                    }
                }catch(RuntimeException unavailable){diagnostic.lastReason="discovery-query-provider";return null;}
                finally{if(queried!=null)recyclePath(queried);}
            }
            diagnostic.phase="discovery-global-hints";
            if(global!=null)for(int hint=0;hint<global.tree.nodes.size();hint++) {
                if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
                ControlTree.Node old=global.tree.nodes.get(hint);
                if(old.visible && NativeControlPolicy.scopedAction(old.role) &&
                        !addDiscoveryHint(global.handles.get(hint),hints,diagnostic))return null;
            }
            diagnostic.phase="strict-hints";
            if(!owner.refresh() || !owned(owner,pkg,window)){diagnostic.lastReason="discovery-owner-changed";return null;}
            if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
            // Every explicit visible Skip needs a safe current clickable chain.
            // Different targets or an unverifiable chain make selection unknown.
            for(AccessibilityNodeInfo hint:hints) {
                diagnostic.phase="strict-hints";
                if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
                if(!hint.refresh()){diagnostic.lastReason="discovery-node-refresh";return null;}
                if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
                if(!owned(hint,pkg,window)){diagnostic.lastReason="discovery-node-ownership";return null;}
                if(!hint.isVisibleToUser() || !hint.isEnabled())continue;
                if(!NativeControlPolicy.scopedAction(UiControlPolicy.action(value(hint.getText()))) &&
                        !NativeControlPolicy.scopedAction(UiControlPolicy.action(value(hint.getContentDescription()))))continue;
                diagnostic.skips++;
                if(!ControlTree.small(bounds(hint),w,h)){diagnostic.lastReason="discovery-skip-bounds";return null;}
                AccessibilityNodeInfo target=readCurrentSkipTarget(hint,pkg,w,h,window,deadline,diagnostic);
                if(target==null)return null;
                try {
                    if(uniqueTarget==null){uniqueTarget=target;target=null;}
                    else if(!uniqueTarget.equals(target)){diagnostic.lastReason="discovery-ambiguous-skip";return null;}
                }finally{if(target!=null)target.recycle();}
                strictHints.add(hint);
            }
            if(strictHints.isEmpty()){diagnostic.lastReason="discovery-no-skip";return null;}
            if(evidence!=null)evidence.explicitControlAt=SystemClock.uptimeMillis();
            logScopedDiagnostic("native scoped controls pkg="+pkg+" explicit="+strictHints.size()+
                " queries="+diagnostic.queries+" found="+diagnostic.found+" hints="+diagnostic.hints+
                " elapsed="+(SystemClock.uptimeMillis()-diagnostic.started)+"ms");
            for(AccessibilityNodeInfo hint:strictHints) {
                diagnostic.phase="discovery-ancestors";
                if(diagnostic.attempts>=SCOPED_ATTEMPT_LIMIT)break;
                if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
                ScopedAd proof=readScopedHint(hint,owner,pkg,epoch,w,h,window,deadline,uniqueTarget,tried,diagnostic,service);
                if(proof!=null){proved=true;return proof;}
            }
        }catch(RuntimeException unavailable){diagnostic.lastReason="discovery-provider";}
        finally {
            try{recyclePath(hints);}finally{try{recyclePath(tried);}finally{
                try{if(uniqueTarget!=null)uniqueTarget.recycle();}finally{if(owner!=null)owner.recycle();}
            }}
            if(!proved)diagnostic.failed();
        }
        return null;
    }
    private static boolean addDiscoveryHint(AccessibilityNodeInfo hint,List<AccessibilityNodeInfo> hints,ScopeDiagnostics diagnostic) {
        if(hint==null){diagnostic.lastReason="discovery-hint-unavailable";return false;}
        if(hints.contains(hint))return true;
        if(hints.size()>=SCOPED_HINT_LIMIT){diagnostic.lastReason="discovery-hint-limit";return false;}
        hints.add(AccessibilityNodeInfo.obtain(hint));diagnostic.hints=hints.size();return true;
    }
    private static AccessibilityNodeInfo readCurrentSkipTarget(AccessibilityNodeInfo hint,String pkg,int w,int h,int window,
            long deadline,ScopeDiagnostics diagnostic) {
        List<AccessibilityNodeInfo> path=new ArrayList<>();
        try {
            diagnostic.phase="discovery-target";
            int[] label=bounds(hint);AccessibilityNodeInfo at=AccessibilityNodeInfo.obtain(hint);
            for(int depth=0;at!=null && depth<5;depth++) {
                boolean cycle=path.contains(at);path.add(at);
                if(cycle){diagnostic.lastReason="discovery-target-cycle";return null;}
                if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
                // Depth zero is this scan's refreshed strict hint; ancestors are still refreshed.
                if((depth>0 && !at.refresh()) || !owned(at,pkg,window)){diagnostic.lastReason="discovery-target-refresh";return null;}
                if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
                if(depth==0 && (!Arrays.equals(label,bounds(at)) || !at.isVisibleToUser() || !at.isEnabled() ||
                        !NativeControlPolicy.scopedAction(UiControlPolicy.action(value(at.getText()))) &&
                        !NativeControlPolicy.scopedAction(UiControlPolicy.action(value(at.getContentDescription()))))) {
                    diagnostic.lastReason="discovery-skip-changed";return null;
                }
                if(at.isClickable()) {
                    int[] target=bounds(at);
                    if(!at.isVisibleToUser() || !at.isEnabled() || !ControlTree.safeParent(label,target,w,h)) {
                        String reason=!at.isVisibleToUser()?"discovery-target-hidden":!at.isEnabled()?"discovery-target-disabled":
                            !ControlTree.small(target,w,h)?"discovery-target-size":!ControlTree.contains(target,label)?
                            "discovery-target-containment":"discovery-target-padding";
                        diagnostic.reject(reason,-1,"control");
                        logScopedDiagnostic("native scoped target pkg="+pkg+" depth="+depth+" label="+Arrays.toString(label)+
                            " target="+Arrays.toString(target)+" reason="+reason);
                        return at.isVisibleToUser() && at.isEnabled()?currentSkipLabelHint(path,pkg,w,h,window,deadline):null;
                    }
                    return AccessibilityNodeInfo.obtain(at);
                }
                if(depth==4)break;
                at=fetchParent(at);
            }
            diagnostic.lastReason="discovery-no-safe-target";
            StringBuilder targetPath=new StringBuilder();
            for(AccessibilityNodeInfo part:path) {
                if(targetPath.length()>0)targetPath.append(';');
                targetPath.append(Arrays.toString(bounds(part))).append(" clickable=").append(part.isClickable())
                    .append(" visible=").append(part.isVisibleToUser()).append(" enabled=").append(part.isEnabled());
            }
            logScopedDiagnostic("native scoped target pkg="+pkg+" reason=discovery-no-safe-target path="+targetPath);
            return currentSkipLabelHint(path,pkg,w,h,window,deadline);
        }finally{recyclePath(path);}
    }
    /** This supplies a discovery hint only; complete ad scope proof must authorize any touch. */
    private static AccessibilityNodeInfo currentSkipLabelHint(List<AccessibilityNodeInfo> path,String pkg,int w,int h,
            int window,long deadline) {
        if(path.isEmpty() || SystemClock.uptimeMillis()>deadline)return null;
        AccessibilityNodeInfo label=path.get(0);
        if(!ControlTree.small(bounds(label),w,h) ||
                !UiControlPolicy.SKIP.equals(UiControlPolicy.action(value(label.getText()))) &&
                !UiControlPolicy.SKIP.equals(UiControlPolicy.action(value(label.getContentDescription()))))return null;
        for(AccessibilityNodeInfo part:path)if(!owned(part,pkg,window) || !part.isVisibleToUser() || !part.isEnabled())return null;
        return AccessibilityNodeInfo.obtain(label);
    }
    private static ScopedAd readScopedHint(AccessibilityNodeInfo hint,AccessibilityNodeInfo owner,String pkg,long epoch,
            int w,int h,int window,long deadline,AccessibilityNodeInfo expectedTarget,List<AccessibilityNodeInfo> tried,ScopeDiagnostics diagnostic,
            AccessibilityService service) {
        List<AccessibilityNodeInfo> path=new ArrayList<>();
        try {
            AccessibilityNodeInfo at=AccessibilityNodeInfo.obtain(hint);
            for(int depth=0;at!=null && depth<=SCOPED_ANCESTOR_LIMIT;depth++) {
                diagnostic.phase="discovery-ancestors";
                boolean cycle=path.contains(at);path.add(at);
                if(cycle){diagnostic.lastReason="discovery-parent-cycle";return null;}
                if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
                // Reuse only this scan's strict hint; full scope proof refreshes every node.
                if(depth>0 && !at.refresh()){diagnostic.lastReason="discovery-node-refresh";return null;}
                if(SystemClock.uptimeMillis()>deadline){diagnostic.lastReason="time";return null;}
                if(!owned(at,pkg,window)){diagnostic.lastReason="discovery-node-ownership";return null;}
                if(depth==0) {
                    if(!at.isVisibleToUser() || !at.isEnabled() || !ControlTree.small(bounds(at),w,h)) {
                        diagnostic.lastReason="discovery-skip-unavailable";return null;
                    }
                    if(!NativeControlPolicy.scopedAction(UiControlPolicy.action(value(at.getText()))) &&
                            !NativeControlPolicy.scopedAction(UiControlPolicy.action(value(at.getContentDescription())))) {
                        diagnostic.lastReason="discovery-not-skip";return null;
                    }
                    diagnostic.skips++;diagnostic.lastReason="discovery-no-fullscreen-scope";
                }
                if(depth>0 && NativeControlPolicy.coversScreen(bounds(at),w,h) && !tried.contains(at)) {
                    tried.add(AccessibilityNodeInfo.obtain(at));diagnostic.attempts++;
                    ScopedAd verified=readScopedAd(at,owner,pkg,epoch,w,h,window,deadline,expectedTarget,diagnostic,service);
                    if(verified!=null)return verified;
                    // A larger ancestor cannot rescue an expired property proof.
                    // Return so the next scan starts with a new current Skip query.
                    if("time".equals(diagnostic.lastReason))return null;
                    if(diagnostic.attempts>=SCOPED_ATTEMPT_LIMIT)return null;
                }
                if(depth==SCOPED_ANCESTOR_LIMIT)break;
                diagnostic.phase="discovery-ancestors";
                at=fetchParent(at);
            }
        }catch(RuntimeException stale){diagnostic.lastReason="discovery-hint-provider";}
        finally{recyclePath(path);}
        return null;
    }
    private static ScopedAd readScopedAd(AccessibilityNodeInfo source,AccessibilityNodeInfo ownerSource,String pkg,long epoch,
            int w,int h,int expectedWindow,long deadline,AccessibilityNodeInfo expectedTarget,ScopeDiagnostics diagnostic,
            AccessibilityService service) {
        AccessibilityNodeInfo scope=null,owner=null;Live independent=null;boolean transferred=false;
        try {
            diagnostic.phase="scope-context";
            diagnostic.nodes=0;diagnostic.complete=false;diagnostic.traversal=null;
            if(SystemClock.uptimeMillis()>deadline){diagnostic.reject("time",-1,"none");return null;}
            scope=AccessibilityNodeInfo.obtain(source);owner=AccessibilityNodeInfo.obtain(ownerSource);
            // Discovery supplies identity only. A supported service clears its cache
            // before the independent complete scope, including current parent IDs.
            if(SystemClock.uptimeMillis()>deadline){diagnostic.reject("time",-1,"none");return null;}
            int window=owner.getWindowId();
            if(window<0 || window!=expectedWindow || !owned(scope,pkg,window) || !owned(owner,pkg,window)) {
                diagnostic.reject("scope-ownership",-1,"none");return null;
            }
            if(!scope.isVisibleToUser() || !scope.isEnabled()){diagnostic.reject("scope-hidden",0,"none");return null;}
            if(!NativeControlPolicy.coversScreen(bounds(scope),w,h)){diagnostic.reject("scope-bounds",0,"none");return null;}
            diagnostic.phase="scope-capture";
            boolean freshCache=false;
            long verificationStarted=0;
            diagnostic.verification="individual-refresh";
            if(android.os.Build.VERSION.SDK_INT>=33 && service!=null) {
                synchronized(AccessibilityControlTree.class) {
                    long freshStarted=SystemClock.uptimeMillis();
                    if(freshStarted>deadline){diagnostic.reject("time",-1,"none");return null;}
                    boolean cleared=false;
                    try{cleared=service.clearCache();}catch(RuntimeException unavailable){/* Retain legacy verification. */}
                    if(cleared) {
                        long freshDeadline=Math.min(deadline,freshStarted+250);
                        if(!scope.refresh() || !owned(scope,pkg,window)){diagnostic.reject("scope-refresh",0,"other");return null;}
                        if(SystemClock.uptimeMillis()>freshDeadline){diagnostic.reject("time",0,"other");return null;}
                        independent=captureLive(scope,pkg,epoch,w,h,freshDeadline,true);
                        ControlTree.Snapshot sampled=independent.tree;
                        // Count freshness from BEFORE clear/root refresh, never from
                        // traversal completion. This only shortens the original age.
                        independent.tree=new ControlTree.Snapshot(pkg,epoch,freshStarted,w,h,sampled.nodes,sampled.complete);
                        diagnostic.captured(independent);
                        diagnostic.recaptureMs+=SystemClock.uptimeMillis()-freshStarted;
                        if(!independent.tree.complete){diagnostic.reject(incompleteScopeReason(independent),-1,"none");return null;}
                        if(!verifyFreshParentEdges(independent,pkg,window,freshDeadline,diagnostic))return null;
                        verificationStarted=freshStarted;freshCache=true;
                        independent.uncachedScope=true;
                        diagnostic.verification="uncached-complete-current-scope";
                    }
                }
            }
            if(independent==null)independent=captureLive(scope,pkg,epoch,w,h,Math.min(deadline,SystemClock.uptimeMillis()+160),true);
            diagnostic.captured(independent);
            if(!independent.tree.complete){diagnostic.reject(incompleteScopeReason(independent),-1,"none");return null;}
            diagnostic.phase="scope-policy";
            NativeControlPolicy.ScopedDecision decision=NativeControlPolicy.inspectVerifiedAdScope(independent.tree);
            NativeControlPolicy.Candidate candidate=decision.candidate;
            if(candidate==null){diagnostic.reject(decision.reason,-1,"none");return null;}
            if(expectedTarget==null || candidate.target<0 || candidate.target>=independent.handles.size() ||
                    !independent.handles.get(candidate.target).equals(expectedTarget)) {
                diagnostic.reject("scope-target-changed",candidate.target,"control");return null;
            }
            diagnostic.phase="scope-initial-freshness";
            if(!independent.tree.current(pkg,epoch,SystemClock.uptimeMillis(),w,h)){diagnostic.reject("scope-stale",-1,"none");return null;}
            if(!freshCache)verificationStarted=SystemClock.uptimeMillis();
            long verificationDeadline=Math.min(deadline,verificationStarted+250);
            diagnostic.phase="scope-properties";
            if(!freshCache && !verifyScopeEdges(independent,scope,pkg,window,verificationDeadline,diagnostic))return null;
            diagnostic.phase="scope-final-refresh";
            NodeSnapshot verifiedScope=new NodeSnapshot(independent.handles.get(0));
            if(SystemClock.uptimeMillis()>verificationDeadline){diagnostic.reject("time",-1,"none");return null;}
            if(!scope.refresh()){diagnostic.reject("scope-final-refresh",-1,"none");return null;}
            if(SystemClock.uptimeMillis()>verificationDeadline){diagnostic.reject("time",-1,"none");return null;}
            if(!freshCache && !owner.refresh()){diagnostic.reject("scope-final-refresh",-1,"none");return null;}
            String finalProperty=verifiedScope.changedProperty(new NodeSnapshot(scope));
            if(finalProperty!=null){diagnostic.reject("scope-final-property-"+finalProperty,0,"other");return null;}
            if(freshCache && !verifyRootChildren(independent,scope,pkg,window,verificationDeadline,diagnostic))return null;
            if(!owned(owner,pkg,window) || !owned(scope,pkg,window)){diagnostic.reject("scope-final-ownership",-1,"none");return null;}
            if(!scope.isVisibleToUser() || !scope.isEnabled()){diagnostic.reject("scope-hidden",0,"none");return null;}
            if(!Arrays.equals(bounds(scope),independent.tree.nodes.get(0).box)){diagnostic.reject("scope-bounds-changed",0,"none");return null;}
            diagnostic.phase="scope-owner-link";
            AccessibilityNodeInfo[] reachedOwner=freshCache?new AccessibilityNodeInfo[1]:null;
            String link=linkedToOwnerReason(scope,owner,pkg,window,verificationDeadline,diagnostic,freshCache,reachedOwner);
            if(link!=null){diagnostic.reject(link,-1,"none");return null;}
            if(freshCache){owner.recycle();owner=reachedOwner[0];}
            diagnostic.phase="final-freshness";
            if(SystemClock.uptimeMillis()>verificationDeadline){diagnostic.reject("time",-1,"none");return null;}
            if(!independent.tree.currentVerifiedScope(pkg,epoch,SystemClock.uptimeMillis(),w,h,verificationStarted)){
                diagnostic.reject("scope-stale",-1,"none");return null;
            }
            ScopedAd proof=new ScopedAd(independent,candidate,scope,owner,verificationStarted);
            logScopedDiagnostic("native scoped ad complete=true nodes="+independent.tree.nodes.size()+" window="+window+
                " pkg="+pkg+" queries="+diagnostic.queries+" hints="+diagnostic.hints+" attempts="+diagnostic.attempts+
                diagnostic.timings()+" elapsed="+(SystemClock.uptimeMillis()-diagnostic.started)+"ms");
            transferred=true;
            return proof;
        }catch(RuntimeException unavailable){diagnostic.reject("scope-provider",-1,"none");return null;}
        finally {
            if(!transferred)try{if(independent!=null)independent.close();}
                finally{try{if(scope!=null)scope.recycle();}finally{if(owner!=null)owner.recycle();}}
        }
    }
    private static String incompleteScopeReason(Live live) {
        BoundedNodeWalker.Stats stats=live.traversal;
        if(stats==null)return "scope-incomplete";
        if(stats.timeout)return "scope-capture-time";
        if(stats.duplicates>0)return "scope-cycle-or-shared-node";
        if(stats.missingChildren>0)return "scope-missing-children";
        if(stats.nodeLimit)return "scope-node-limit";
        if(stats.depthLimit)return "scope-depth-limit";
        if(stats.rejected>0)return "scope-node-ownership";
        if(stats.errors>0)return "scope-provider-error";
        return "scope-incomplete";
    }
    private static boolean verifyScopeEdges(Live independent,AccessibilityNodeInfo scope,String pkg,int window,long deadline,
            ScopeDiagnostics diagnostic) {
        diagnostic.verification="individual-refresh";
        if(independent.handles.size()!=independent.tree.nodes.size() || independent.handles.isEmpty() ||
                !independent.handles.get(0).equals(scope)){diagnostic.reject("scope-handles",-1,"none");return false;}
        for(int i=0;i<independent.handles.size();i++) {
            AccessibilityNodeInfo node=independent.handles.get(i);ControlTree.Node captured=independent.tree.nodes.get(i);
            String semantic=UiControlPolicy.isControl(captured.role)?"control":captured.role.equals("ad")?"ad":
                captured.role.equals("nav")?"nav":captured.role.equals("prompt")?"prompt":"other";
            if(SystemClock.uptimeMillis()>deadline){diagnostic.reject("time",i,semantic);return false;}
            if(!owned(node,pkg,window)){diagnostic.reject("scope-node-ownership",i,semantic);return false;}
            NodeSnapshot frozen=new NodeSnapshot(node);
            long refreshStarted=SystemClock.uptimeMillis();
            boolean refreshed=node.refresh();diagnostic.propertyRefreshMs+=SystemClock.uptimeMillis()-refreshStarted;
            if(!refreshed){diagnostic.reject("scope-node-refresh",i,semantic);return false;}
            if(SystemClock.uptimeMillis()>deadline){diagnostic.reject("time",i,semantic);return false;}
            if(!owned(node,pkg,window)){diagnostic.reject("scope-node-ownership",i,semantic);return false;}
            boolean movingDecoration=Boolean.FALSE.equals(frozen.properties[11]) &&
                Integer.valueOf(0).equals(frozen.properties[8]) && NativeControlPolicy.animatedDecoration(independent.tree,i);
            String property=frozen.changedProperty(new NodeSnapshot(node),movingDecoration,captured.role);
            if(property!=null){diagnostic.reject("property-"+property,i,semantic);return false;}
            if(i==0)continue;
            long parentStarted=SystemClock.uptimeMillis();
            AccessibilityNodeInfo parent=fetchParent(node);diagnostic.propertyParentMs+=SystemClock.uptimeMillis()-parentStarted;
            try {
                int expected=captured.parent;
                if(parent==null || expected<0 || expected>=independent.handles.size() || !owned(parent,pkg,window) ||
                        !parent.equals(independent.handles.get(expected))) {
                    diagnostic.reject("scope-parent-changed",i,semantic);return false;
                }
            }finally{if(parent!=null)parent.recycle();}
        }
        if(SystemClock.uptimeMillis()>deadline){diagnostic.reject("time",-1,"none");return false;}
        return true;
    }
    private static boolean verifyFreshParentEdges(Live live,String pkg,int window,long deadline,ScopeDiagnostics diagnostic) {
        if(live.handles.size()!=live.tree.nodes.size()){diagnostic.reject("scope-handles",-1,"none");return false;}
        for(int i=0;i<live.handles.size();i++) {
            if(SystemClock.uptimeMillis()>deadline){diagnostic.reject("time",i,"other");return false;}
            AccessibilityNodeInfo node=live.handles.get(i);
            if(!owned(node,pkg,window)){diagnostic.reject("scope-node-ownership",i,"other");return false;}
            if(i==0)continue;
            long started=SystemClock.uptimeMillis();
            AccessibilityNodeInfo parent=fetchParent(node);diagnostic.propertyParentMs+=SystemClock.uptimeMillis()-started;
            try {
                int expected=live.tree.nodes.get(i).parent;
                if(parent==null || expected<0 || expected>=live.handles.size() || !owned(parent,pkg,window) ||
                        !parent.equals(live.handles.get(expected))) {diagnostic.reject("scope-parent-changed",i,"other");return false;}
            }finally{if(parent!=null)parent.recycle();}
        }
        if(SystemClock.uptimeMillis()>deadline){diagnostic.reject("time",-1,"none");return false;}
        return true;
    }
    /** A refreshed root must still declare the exact direct child identities/order. */
    private static synchronized boolean verifyRootChildren(Live live,AccessibilityNodeInfo root,String pkg,int window,long deadline,
            ScopeDiagnostics diagnostic) {
        List<AccessibilityNodeInfo> expected=new ArrayList<>(),seen=new ArrayList<>();
        for(int i=1;i<live.handles.size();i++)if(live.tree.nodes.get(i).parent==0)expected.add(live.handles.get(i));
        int aliases=0;for(int parent:live.aliasParents)if(parent==0)aliases++;
        if(root.getChildCount()!=expected.size()+aliases){diagnostic.reject("scope-root-children-changed",0,"other");return false;}
        try {
            for(int i=0;i<root.getChildCount();i++) {
                if(SystemClock.uptimeMillis()>deadline){diagnostic.reject("time",0,"other");return false;}
                AccessibilityNodeInfo child=fetchChild(root,i,true);
                if(child==null || !owned(child,pkg,window)){if(child!=null)child.recycle();diagnostic.reject("scope-root-child-missing",0,"other");return false;}
                seen.add(child);
            }
            if(!NativeControlPolicy.declaredChildrenMatch(expected,seen,aliases)) {
                diagnostic.reject("scope-root-child-replaced",0,"other");return false;
            }
            if(SystemClock.uptimeMillis()>deadline){diagnostic.reject("time",0,"other");return false;}
            return true;
        }finally{recyclePath(seen);}
    }
    private static String linkedToOwnerReason(AccessibilityNodeInfo scope,AccessibilityNodeInfo owner,String pkg,int window,long deadline,
            ScopeDiagnostics diagnostic,boolean freshCache,AccessibilityNodeInfo[] reachedOwner) {
        List<AccessibilityNodeInfo> path=new ArrayList<>();
        try {
            AccessibilityNodeInfo at=AccessibilityNodeInfo.obtain(scope);
            for(int depth=0;at!=null && depth<=ControlTree.DEPTH;depth++) {
                boolean cycle=path.contains(at);path.add(at);
                if(cycle)return "scope-parent-cycle";
                if(SystemClock.uptimeMillis()>deadline)return "time";
                // The two endpoints were refreshed immediately above. Intermediate
                // ancestors still require fresh parent IDs, even if their bounds match.
                // Complete recapture cleared every ancestor entry. Serialized reads
                // return that fresh synchronous batch or a new provider response.
                // Legacy paths still refresh each intermediate ancestor.
                if(!freshCache && depth>0 && !at.equals(owner)) {
                    long refreshStarted=SystemClock.uptimeMillis();
                    boolean refreshed=at.refresh();diagnostic.ownerRefreshMs+=SystemClock.uptimeMillis()-refreshStarted;
                    if(!refreshed)return "scope-parent-refresh";
                }
                if(SystemClock.uptimeMillis()>deadline)return "time";
                if(!owned(at,pkg,window))return "scope-parent-ownership";
                if(at.equals(owner)){if(reachedOwner!=null)reachedOwner[0]=AccessibilityNodeInfo.obtain(at);return null;}
                if(depth==ControlTree.DEPTH)return "scope-owner-link";
                long parentStarted=SystemClock.uptimeMillis();
                at=fetchParent(at);diagnostic.ownerParentMs+=SystemClock.uptimeMillis()-parentStarted;
            }
            return "scope-owner-link";
        }finally{recyclePath(path);}
    }
    private static boolean owned(AccessibilityNodeInfo node,String pkg,int window) {
        return node!=null && node.getWindowId()==window && pkg.contentEquals(value(node.getPackageName()));
    }
    private static int[] bounds(AccessibilityNodeInfo node) {
        Rect b=new Rect();node.getBoundsInScreen(b);return new int[]{b.left,b.top,b.right,b.bottom};
    }
    private static void recyclePath(List<AccessibilityNodeInfo> path) {
        Set<AccessibilityNodeInfo> identities=Collections.newSetFromMap(new IdentityHashMap<AccessibilityNodeInfo,Boolean>());
        for(AccessibilityNodeInfo node:path)if(node!=null && identities.add(node))node.recycle();
    }
    static ControlTree.Snapshot capture(AccessibilityNodeInfo root,String pkg,long epoch,int w,int h) {
        return capture(root,pkg,epoch,w,h,SystemClock.uptimeMillis()+200,null);
    }
    private static synchronized ControlTree.Snapshot capture(AccessibilityNodeInfo root,String pkg,long epoch,int w,int h,long deadline,Live live) {
        long now=SystemClock.uptimeMillis();List<ControlTree.Node> nodes=new ArrayList<>();
        if(root==null)return new ControlTree.Snapshot(pkg,epoch,now,w,h,nodes,false);
        BoundedNodeWalker.Stats stats=BoundedNodeWalker.walk(Collections.singletonList(AccessibilityNodeInfo.obtain(root)),
            new BoundedNodeWalker.Access<AccessibilityNodeInfo>() {
                public boolean accepts(AccessibilityNodeInfo n){return pkg.contentEquals(value(n.getPackageName()));}
                public int children(AccessibilityNodeInfo n){return n.getChildCount();}
                public AccessibilityNodeInfo child(AccessibilityNodeInfo n,int i){return fetchChild(n,i,live!=null && live.prefetchScope);}
                public void release(AccessibilityNodeInfo n){n.recycle();}
                public Object snapshotForAlias(AccessibilityNodeInfo n){return new NodeSnapshot(n);}
                public boolean sameSnapshot(Object first,Object repeated){
                    return first instanceof NodeSnapshot && repeated instanceof NodeSnapshot &&
                        Arrays.equals(((NodeSnapshot)first).properties,((NodeSnapshot)repeated).properties);
                }
                public void append(AccessibilityNodeInfo n,int index,int parent,int depth){
                    boolean visible=n.isVisibleToUser(),enabled=n.isEnabled(),clickable=n.isClickable();
                    String text=value(n.getText()),description=value(n.getContentDescription());
                    String role=visible?role(text):"";
                    if(visible&&role.isEmpty())role=role(description);
                    Rect b=new Rect();n.getBoundsInScreen(b);
                    String className=value(n.getClassName()),viewId=value(n.getViewIdResourceName());
                    String identity=JointControlModel.digest(className+"/"+viewId);
                    boolean plain=!n.isEditable() && !n.isPassword();
                    nodes.add(new ControlTree.Node(parent,n.getChildCount(),new int[]{b.left,b.top,b.right,b.bottom},visible&&enabled&&clickable,role,identity,
                        visible&&enabled,className,viewId,UiControlPolicy.explicitSkipCountdown(text)||UiControlPolicy.explicitSkipCountdown(description),enabled,clickable,visible,
                        plain && UiControlPolicy.SKIP.equals(role) && (UiControlPolicy.explicitSkipAd(text)||UiControlPolicy.explicitSkipAd(description)),
                        plain && "prompt".equals(role) && (UiControlPolicy.navigationDisclosure(text)||UiControlPolicy.navigationDisclosure(description))));
                    if(live!=null){
                        live.handles.add(AccessibilityNodeInfo.obtain(n));
                        String label=NativeBoundsPolicy.labelAllowed(text)?text.trim():NativeBoundsPolicy.labelAllowed(description)?description.trim():(text+" "+description).trim();
                        if(plain && label.length()<=96 && (NativeBoundsPolicy.labelAllowed(label) || label.contains("广告") || label.contains("跳过") || label.contains("关闭") || label.contains("翻转") || label.contains("摇") ||
                                "tv.danmaku.bili".equals(pkg) && (label.equals("不感兴趣") || label.equals("查看详情"))))
                            live.diagnosticLabels.put(index,label);
                    }
                }
            },ControlTree.LIMIT,ControlTree.DEPTH,deadline,SystemClock::uptimeMillis);
        if(live!=null){live.verifiedAliases=stats.verifiedAliases;live.aliasParents.addAll(stats.aliasParents);}
        // Only checked duplicate edges are subtracted. Unknown/missing edges
        // retain their declared branch count in an incomplete snapshot.
        int[] aliases=new int[nodes.size()];
        for(int parent:stats.aliasParents)if(parent>=0 && parent<aliases.length)aliases[parent]++;
        boolean complete=stats.complete() && stats.uniqueChildren.size()==nodes.size();
        if(complete)for(int i=0;i<nodes.size();i++)
            if(nodes.get(i).children-aliases[i]!=stats.uniqueChildren.get(i)){stats.errors++;complete=false;break;}
        for(int i=0;i<nodes.size();i++){
            ControlTree.Node node=nodes.get(i);
            int unique=i<stats.uniqueChildren.size()?stats.uniqueChildren.get(i):0;
            int children=complete?unique:Math.max(unique,node.children-aliases[i]);
            if(children!=node.children)nodes.set(i,new ControlTree.Node(node.parent,children,node.box,node.clickable,node.role,node.identity,node.visible,
                node.className,node.viewId,node.skipCountdown,node.enabled,node.declaredClickable,node.onScreen,node.explicitSkipAd,node.navigationDisclosure));
        }
        if(live!=null)live.traversal=stats;
        if(stats.verifiedAliases>0)android.util.Log.i("SplashSkipVisual","native tree verified aliases="+stats.verifiedAliases+
            " parents="+stats.aliasParents+" complete="+complete+" unique-nodes="+nodes.size());
        return new ControlTree.Snapshot(pkg,epoch,now,w,h,nodes,complete&&!nodes.isEmpty());
    }
    /** Frozen properties: refreshing or reusing a handle cannot rewrite the first occurrence. */
    static final class NodeSnapshot {
        private static final String[] PROPERTY_NAMES={"package","class","view-id","text","description","window","screen-bounds",
            "parent-bounds","children","visible","enabled","clickable","focusable","focused","accessibility-focused","selected",
            "checkable","checked","editable","password","scrollable","long-clickable","dismissable","context-clickable",
            "important-for-accessibility","drawing-order","actions","range"};
        final Object[] properties;
        String changedProperty(NodeSnapshot current){return changedProperty(current,false,"");}
        String changedProperty(NodeSnapshot current,boolean movingDecoration){return changedProperty(current,movingDecoration,"");}
        String changedProperty(NodeSnapshot current,boolean movingDecoration,String role){
            int changed=NativeControlPolicy.scopePropertyDifference(properties,current.properties,movingDecoration,role);
            return changed<0?null:PROPERTY_NAMES[changed];
        }
        NodeSnapshot(AccessibilityNodeInfo n){
            Rect screen=new Rect(),parent=new Rect();n.getBoundsInScreen(screen);n.getBoundsInParent(parent);
            List<Object> actions=new ArrayList<>();
            for(AccessibilityNodeInfo.AccessibilityAction action:n.getActionList()){
                actions.add(action.getId());actions.add(nullable(action.getLabel()));
            }
            AccessibilityNodeInfo.RangeInfo range=n.getRangeInfo();
            List<Object> rangeProperties=range==null?null:Arrays.asList(range.getType(),range.getMin(),range.getMax(),range.getCurrent());
            properties=new Object[]{nullable(n.getPackageName()),nullable(n.getClassName()),n.getViewIdResourceName(),
                nullable(n.getText()),nullable(n.getContentDescription()),n.getWindowId(),screen,parent,n.getChildCount(),
                n.isVisibleToUser(),n.isEnabled(),n.isClickable(),n.isFocusable(),n.isFocused(),n.isAccessibilityFocused(),
                n.isSelected(),n.isCheckable(),n.isChecked(),n.isEditable(),n.isPassword(),n.isScrollable(),n.isLongClickable(),
                n.isDismissable(),n.isContextClickable(),n.isImportantForAccessibility(),n.getDrawingOrder(),actions,rangeProperties};
        }
    }
    static AccessibilityNodeInfo fetchChild(AccessibilityNodeInfo n,int index){
        return fetchChild(n,index,false);
    }
    private static synchronized AccessibilityNodeInfo fetchChild(AccessibilityNodeInfo n,int index,boolean scope){
        if(android.os.Build.VERSION.SDK_INT>=33) {
            // Finish prefetch before a later cache invalidation can start another
            // proof. No delayed response from this walk may repopulate old nodes.
            int flags=AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST |
                AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE;
            if(scope)flags|=AccessibilityNodeInfo.FLAG_PREFETCH_ANCESTORS | AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS;
            return n.getChild(index,flags);
        }
        return n.getChild(index);
    }
    static synchronized AccessibilityNodeInfo fetchParent(AccessibilityNodeInfo node){
        return android.os.Build.VERSION.SDK_INT>=33?node.getParent(0):node.getParent();
    }
    static synchronized AccessibilityNodeInfo fetchWindowRoot(AccessibilityWindowInfo window){
        return android.os.Build.VERSION.SDK_INT>=33?window.getRoot(0):window.getRoot();
    }
    static synchronized AccessibilityNodeInfo fetchActiveRoot(AccessibilityService service){
        return android.os.Build.VERSION.SDK_INT>=33?service.getRootInActiveWindow(0):service.getRootInActiveWindow();
    }
    static synchronized AccessibilityNodeInfo fetchEventSource(AccessibilityEvent event){
        return android.os.Build.VERSION.SDK_INT>=33?event.getSource(0):event.getSource();
    }
    private static synchronized List<AccessibilityNodeInfo> findText(AccessibilityNodeInfo owner,String text){
        return owner.findAccessibilityNodeInfosByText(text);
    }
    static String role(String text) {
        return ControlTree.role(text);
    }
    private static String value(CharSequence s){return s==null?"":s.toString();}
    private static String nullable(CharSequence s){return s==null?null:s.toString();}
}
