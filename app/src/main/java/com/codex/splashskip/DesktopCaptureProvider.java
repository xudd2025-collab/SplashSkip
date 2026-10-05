package com.codex.splashskip;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;

/** ADB-only read bridge for the desktop inspector. No click or settings endpoints. */
public final class DesktopCaptureProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private void requireShell() {
        if(Binder.getCallingUid()!=2000)throw new SecurityException("Desktop capture requires an authorized ADB shell");
        getContext().enforceCallingPermission("android.permission.DUMP","ADB diagnostic permission required");
    }
    @Override public Bundle call(String method,String pkg,Bundle extras) {
        requireShell();
        if(pkg==null || !pkg.matches("[A-Za-z][\\w]*(?:\\.[\\w]+)+"))throw new IllegalArgumentException("Package required");
        Bundle reply=new Bundle();
        try {
            JSONObject result;
            if("status".equals(method)) {
                if(!getContext().getPackageName().equals(pkg))throw new IllegalArgumentException("Status only for this assistant");
                android.content.SharedPreferences settings=getContext().getSharedPreferences("settings",android.content.Context.MODE_PRIVATE);
                SkipService service=SkipService.desktopInstance();
                result=new JSONObject().put("kind","status")
                    .put("enabled",settings.getBoolean("enabled",true))
                    .put("ai_enhanced",AppProfiles.enabled(getContext()))
                    .put("visual_supplement",RecognitionMode.visuals(getContext()))
                    .put("capture_click",settings.getBoolean("capture_click",false) && System.currentTimeMillis()<=settings.getLong("capture_until",0))
                    .put("service_running",service!=null)
                    .put("ocr_ready",SkipService.ocrReady())
                    .put("native_bounds_auto",settings.getBoolean("native_bounds_auto",true))
                    .put("bounds_records",NativeBoundsArchive.get(getContext()).recent().size())
                    .put("screenshot_requests",service==null?-1:service.desktopScreenshotRequests());
            } else if("native_frame".equals(method)) {
                SkipService service=SkipService.desktopInstance();
                SkipService.NativeDiagnostic frame=service==null?null:service.desktopNativeFrame(pkg);
                if(frame==null)result=new JSONObject().put("kind","waiting");
                else {
                    JSONArray nodes=new JSONArray();ControlTree.Snapshot tree=frame.tree==null?frame.adScope:frame.tree;
                    for(int i=0;frame.tree!=null && i<tree.nodes.size();i++) {
                        ControlTree.Node node=tree.nodes.get(i);
                        nodes.put(new JSONObject().put("index",i).put("parent",node.parent).put("child_count",node.children)
                            .put("bounds",new JSONArray(node.box)).put("role",node.role).put("identity",node.identity).put("class",node.className).put("id",node.viewId)
                            .put("clickable",node.clickable).put("visible",node.visible).put("label",frame.labels.get(i)));
                    }
                    result=new JSONObject().put("kind","native-frame").put("package",tree.pkg).put("width",tree.width).put("height",tree.height)
                        .put("global_read",frame.tree!=null)
                        .put("tree_uptime",frame.tree==null?JSONObject.NULL:tree.time)
                        .put("frame_age",frame.tree==null?JSONObject.NULL:SystemClock.uptimeMillis()-tree.time)
                        .put("tree_complete",frame.tree!=null && tree.complete)
                        .put("opening",frame.opening).put("window",frame.window).put("result",frame.reason).put("tree_nodes",nodes)
                        .put("verified_aliases",frame.verifiedAliases).put("alias_parents",new JSONArray(frame.aliasParents))
                        .put("truncation_reasons",new JSONArray(frame.traversalReasons)).put("duplicates",frame.duplicates)
                        .put("node_errors",frame.errors).put("missing_children",frame.missingChildren)
                        .put("duplicate_kinds",new JSONObject(frame.duplicateKinds)).put("duplicate_edges",new JSONArray(frame.duplicateEdges));
                    if(frame.adScope!=null) {
                        JSONArray scopeNodes=new JSONArray();ControlTree.Snapshot scope=frame.adScope;
                        for(int i=0;i<scope.nodes.size();i++) {
                            ControlTree.Node node=scope.nodes.get(i);
                            scopeNodes.put(new JSONObject().put("index",i).put("parent",node.parent).put("child_count",node.children)
                                .put("bounds",new JSONArray(node.box)).put("role",node.role).put("identity",node.identity).put("class",node.className).put("id",node.viewId)
                                .put("clickable",node.clickable).put("visible",node.visible));
                        }
                        result.put("ad_scope",new JSONObject().put("independent",true).put("complete",scope.complete)
                            .put("tree_uptime",scope.time).put("frame_age",SystemClock.uptimeMillis()-scope.time)
                            .put("tree_nodes",scopeNodes));
                    }
                }
            } else if("bounds_records".equals(method)) {
                List<JSONObject> all=new ArrayList<>();
                for(JSONObject summary:NativeBoundsArchive.get(getContext()).recent())if(pkg.equals(summary.optString("package")))all.add(summary);
                int offset=extras==null?0:extras.getInt("offset",0);
                if(offset<0 || offset>64)throw new IllegalArgumentException("Invalid bounds offset");
                JSONArray frames=new JSONArray();int next=offset,bytes=0;
                for(;next<all.size()&&frames.length()<6;next++) {
                    JSONObject frame=NativeBoundsArchive.get(getContext()).frame(all.get(next).optString("id"));
                    if(frame==null)continue;
                    int size=frame.toString().getBytes(StandardCharsets.UTF_8).length;
                    if(bytes+size>256*1024&&frames.length()>0)break;
                    frames.put(frame);bytes+=size;
                }
                result=new JSONObject().put("schema",1).put("kind","bounds-records").put("frames",frames)
                    .put("next_offset",next<all.size()?next:-1).put("total",all.size());
            } else if("records".equals(method)) {
                List<JSONObject> all=new ArrayList<>();
                for(JSONObject r:JointLearningStore.get(getContext()).recent())if(pkg.equals(r.optString("package")))all.add(r);
                int offset=extras==null?0:extras.getInt("offset",0);
                if(offset<0 || offset>256)throw new IllegalArgumentException("Invalid records offset");
                JSONArray records=new JSONArray();int next=offset,bytes=0;
                for(;next<all.size()&&records.length()<20;next++){
                    JSONObject r=all.get(next);int size=r.toString().getBytes(StandardCharsets.UTF_8).length;
                    if(size>150*1024){r.remove("tree_nodes");r.put("tree_evidence_omitted","single_record_budget");size=r.toString().getBytes(StandardCharsets.UTF_8).length;}
                    if(bytes+size>150*1024&&records.length()>0)break;
                    records.put(r);bytes+=size;
                }
                result=new JSONObject().put("schema",1).put("feature_count",JointControlModel.SIZE).put("records",records)
                    .put("next_offset",next<all.size()?next:-1).put("total",all.size());
            } else if("tree".equals(method)||"tree_deep".equals(method))result=tree(pkg,"tree_deep".equals(method));
            else throw new IllegalArgumentException("Unknown read method");
            byte[] data=result.toString().getBytes(StandardCharsets.UTF_8);
            if(data.length>2*1024*1024)throw new IllegalStateException("Diagnostic response too large");
            // Compress repetitive native tree fields before the Base64/UTF-16 Bundle
            // expansion, rather than silently dropping deep parent-child branches.
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(GZIPOutputStream zip=new GZIPOutputStream(bytes)){zip.write(data);}
            byte[] compressed=bytes.toByteArray();
            if(compressed.length>150*1024)throw new IllegalStateException("Compressed diagnostic response too large");
            reply.putString("encoding","gzip");reply.putInt("protocol",2);
            reply.putString("data",Base64.encodeToString(compressed,Base64.NO_WRAP));
        }catch(Exception error){reply.putString("error",error.getClass().getSimpleName()+": "+error.getMessage());}
        return reply;
    }
    private JSONObject tree(String pkg,boolean deep)throws Exception {
        SkipService service=SkipService.desktopInstance();
        if(service==null)return new JSONObject().put("kind","service-unavailable");
        long start=SystemClock.uptimeMillis(),generation=service.desktopGeneration();
        Point size=new Point();android.hardware.display.DisplayManager dm=service.getSystemService(android.hardware.display.DisplayManager.class);
        Display display=dm.getDisplay(Display.DEFAULT_DISPLAY);if(display==null)throw new IllegalStateException("Display unavailable");display.getRealSize(size);
        List<AccessibilityNodeInfo> roots=new ArrayList<>();Set<Integer> windows=new HashSet<>();JSONArray windowInfo=new JSONArray();
        List<AccessibilityWindowInfo> all=new ArrayList<>(service.getWindows());
        all.sort((a,b)->Integer.compare(b.getLayer(),a.getLayer()));
        int unavailableRoots=0;
        for(AccessibilityWindowInfo window:all){
            AccessibilityNodeInfo root=null;
            try {
                root=AccessibilityControlTree.fetchWindowRoot(window);
                if(root==null){unavailableRoots++;continue;}
                if(pkg.equals(text(root.getPackageName())) && windows.add(window.getId())){
                    roots.add(root);root=null;
                    windowInfo.put(new JSONObject().put("id",window.getId()).put("layer",window.getLayer()).put("active",window.isActive()).put("focused",window.isFocused()));
                }
            }finally{if(root!=null)root.recycle();window.recycle();}
        }
        AccessibilityNodeInfo active=AccessibilityControlTree.fetchActiveRoot(service);
        boolean activeOwner=active!=null&&pkg.equals(text(active.getPackageName()));
        if(activeOwner&&windows.add(active.getWindowId())){roots.add(active);active=null;}
        if(active!=null)active.recycle();
        if(roots.isEmpty())return new JSONObject().put("kind","waiting").put("unavailable_window_roots",unavailableRoots);
        JSONArray nodes=new JSONArray();
        int budget=deep?1500:300;
        BoundedNodeWalker.Stats stats=BoundedNodeWalker.walk(roots,new BoundedNodeWalker.Access<AccessibilityNodeInfo>(){
            public boolean accepts(AccessibilityNodeInfo n){return pkg.equals(text(n.getPackageName()));}
            public int children(AccessibilityNodeInfo n){return n.getChildCount();}
            public AccessibilityNodeInfo child(AccessibilityNodeInfo n,int i){return AccessibilityControlTree.fetchChild(n,i);}
            public void release(AccessibilityNodeInfo n){n.recycle();}
            public void append(AccessibilityNodeInfo n,int index,int parent,int depth){
                try{
                    Rect b=new Rect();n.getBoundsInScreen(b);
                    nodes.put(new JSONObject().put("index",index).put("parent",parent).put("child_count",n.getChildCount())
                        .put("bounds",new JSONArray(new int[]{b.left,b.top,b.right,b.bottom})).put("source","native").put("depth",depth).put("window_id",n.getWindowId())
                        .put("class",text(n.getClassName())).put("id",text(n.getViewIdResourceName())).put("text",limited(n.getText())).put("description",limited(n.getContentDescription()))
                        .put("package",pkg).put("clickable",n.isClickable()).put("enabled",n.isEnabled()).put("visible",n.isVisibleToUser()));
                }catch(JSONException e){throw new IllegalStateException(e);}
            }
        },deep?1536:512,deep?48:32,SystemClock.uptimeMillis()+budget,SystemClock::uptimeMillis);
        List<String> reasons=stats.reasons();
        while(nodes.toString().getBytes(StandardCharsets.UTF_8).length>1024*1024){nodes.remove(nodes.length()-1);if(!reasons.contains("payload_limit"))reasons.add("payload_limit");}
        int[] captured=new int[nodes.length()];
        for(int i=0;i<nodes.length();i++){int p=nodes.getJSONObject(i).getInt("parent");if(p>=0)captured[p]++;}
        for(int i=0;i<nodes.length();i++)nodes.getJSONObject(i).put("captured_children",captured[i]);
        AccessibilityNodeInfo check=AccessibilityControlTree.fetchActiveRoot(service);boolean owner=check!=null&&pkg.equals(text(check.getPackageName()));if(check!=null)check.recycle();
        return new JSONObject().put("kind",activeOwner&&!owner?"owner-changed":"frame").put("package",pkg)
            .put("width",size.x).put("height",size.y).put("started_uptime",start).put("tree_uptime",SystemClock.uptimeMillis())
            .put("wall_time",System.currentTimeMillis()).put("tree_complete",reasons.isEmpty()).put("tree_nodes",nodes)
            .put("capture_mode",deep?"deep":"normal").put("truncation_reasons",new JSONArray(reasons)).put("missing_children",stats.missingChildren)
            .put("node_errors",stats.errors).put("target_active",owner).put("windows",windowInfo).put("unavailable_window_roots",unavailableRoots)
            .put("event_sequence_start",generation).put("event_sequence_end",service.desktopGeneration());
    }
    private static String limited(CharSequence s){String v=text(s);return v.length()>512?v.substring(0,512):v;}
    private static String text(CharSequence s){return s==null?"":s.toString();}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
