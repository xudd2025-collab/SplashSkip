package com.codex.splashskip;

import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Local diagnostics only. It never reads Android nodes, captures pixels, or authorizes input. */
final class NativeBoundsArchive {
    private static final int SCHEMA=1, QUEUE_CAPACITY=32;
    private static volatile NativeBoundsArchive instance;
    private final Context context;
    private final String session=UUID.randomUUID().toString();
    private final AtomicLong dropped=new AtomicLong();
    private final ThreadPoolExecutor writer;
    private volatile Thread writerThread;
    // These fields are confined to the writer, including initialization and JSON serialization.
    private AtomicFile disk;
    private final List<Entry> frames=new ArrayList<>();
    private final NativeBoundsPolicy policy=new NativeBoundsPolicy();
    private String lastError="";
    private boolean dirty;

    static NativeBoundsArchive get(Context context) {
        NativeBoundsArchive result=instance;
        if(result==null)synchronized(NativeBoundsArchive.class) {
            result=instance;
            if(result==null)instance=result=new NativeBoundsArchive(context);
        }
        return result;
    }
    static void record(Context context,SkipService.NativeDiagnostic frame){get(context).record(frame);}
    private NativeBoundsArchive(Context context) {
        Context app=context.getApplicationContext();this.context=app==null?context:app;
        writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),r->{
            Thread thread=new Thread(r,"native-bounds-archive");thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);
            writerThread=thread;return thread;
        },new ThreadPoolExecutor.AbortPolicy());
        // getFilesDir(), directory creation and all file reads happen on this thread.
        writer.execute(this::restore);
    }
    /** Input must be the existing frozen Snapshot and copied NativeDiagnostic labels. No waiting. */
    void record(SkipService.NativeDiagnostic frame) {
        if(frame==null)return;
        long wall=System.currentTimeMillis(),uptime=SystemClock.uptimeMillis();
        try{writer.execute(()->recordOnWriter(frame,wall,uptime));}
        catch(RejectedExecutionException busy){dropped.incrementAndGet();}
    }
    /** Background callers only. Summaries are newest first and cannot mutate archive entries. */
    List<JSONObject> recent() {
        return call(()->{
            maintain();List<JSONObject> result=new ArrayList<>();
            for(int i=frames.size()-1;i>=0;i--) {
                JSONObject source=frames.get(i).json,summary=new JSONObject();
                for(String key:new String[]{"schema","id","package","epoch","width","height","captured_wall_time",
                        "captured_uptime","last_seen_wall_time","last_seen_uptime","opening","result","window","tree_complete",
                        "source_tree_complete","scope_complete","scope_only","node_count","source_node_count","scope_node_count",
                        "archive_truncated","repeat_count","first_frame","truncation_reasons"})
                    if(source.has(key))summary.put(key,source.get(key));
                result.add(new JSONObject(summary.toString()));
            }
            return result;
        });
    }
    /** Background callers only. A missing UUID returns null. */
    JSONObject frame(String id) {
        return call(()->{
            maintain();if(id==null)return null;
            for(Entry entry:frames)if(id.equals(entry.json.optString("id")))return new JSONObject(entry.json.toString());
            return null;
        });
    }
    /** Background callers only. Returned bytes are a complete bounded UTF8 JSON archive. */
    byte[] exportBytes(){return call(()->{
        maintain();int before=frames.size();byte[] bytes=archiveBytes();
        if(frames.size()!=before && !persist())throw new IllegalStateException("Archive write failed: "+lastError);
        return bytes;
    });}
    /** Background callers only. Queued earlier records finish first; sampling state is reset. */
    void clear(){call(()->{
        frames.clear();policy.reset();dropped.set(0);lastError="";dirty=true;
        if(!persist())throw new IllegalStateException("Archive clear could not be saved: "+lastError);
        return null;
    });}

    private static final class Entry {
        JSONObject json;
        int bytes;
        Entry(JSONObject json){this.json=json;refresh();}
        void refresh(){bytes=json.toString().getBytes(StandardCharsets.UTF_8).length;}
    }
    private void recordOnWriter(SkipService.NativeDiagnostic frame,long enqueuedWall,long enqueuedUptime) {
        try {
            // A setting changed after enqueue also suppresses the queued record.
            if(!context.getSharedPreferences("settings",0).getBoolean("native_bounds_auto",true))return;
            ControlTree.Snapshot source=frame.tree==null?frame.adScope:frame.tree;
            if(source==null || source.pkg==null || source.pkg.isEmpty() || source.time<0)return;
            long wall=Math.max(0,enqueuedWall-Math.max(0,enqueuedUptime-source.time));
            String signature=signature(frame);
            int importance=Math.max(importance(frame.tree),importance(frame.adScope));
            NativeBoundsPolicy.Decision decision=policy.decide(source.pkg,source.epoch,source.time,signature,frame.reason,importance);
            boolean changed=prune(System.currentTimeMillis());
            if(decision.action==NativeBoundsPolicy.Action.IGNORE){if(changed || dirty)persist();return;}
            if(decision.action==NativeBoundsPolicy.Action.MERGE) {
                for(int i=frames.size()-1;i>=0;i--) {
                    Entry entry=frames.get(i);JSONObject saved=entry.json;
                    if(session.equals(saved.optString("capture_session")) && source.pkg.equals(saved.optString("package")) &&
                            source.epoch==saved.optLong("epoch",Long.MIN_VALUE) && signature.equals(saved.optString("structure")) &&
                            text(frame.reason).equals(saved.optString("result"))) {
                        saved.put("repeat_count",saved.optLong("repeat_count",1)+1);
                        saved.put("last_seen_wall_time",wall);saved.put("last_seen_uptime",source.time);
                        entry.refresh();dirty=true;prune(System.currentTimeMillis());persist();return;
                    }
                }
                // The matching entry may have been evicted by retention or the byte limit.
            }
            JSONObject saved=boundedFrame(frame,source,wall,signature,decision.first);
            if(saved==null){dropped.incrementAndGet();if(changed || dirty)persist();return;}
            frames.add(new Entry(saved));dirty=true;prune(System.currentTimeMillis());persist();
        }catch(Exception error){failure("record",error);}
    }
    private JSONObject boundedFrame(SkipService.NativeDiagnostic frame,ControlTree.Snapshot source,long wall,
            String signature,boolean first)throws Exception {
        boolean scopeOnly=frame.tree==null;
        int mainCount=source.nodes.size(),scopeCount=frame.adScope==null?0:frame.adScope.nodes.size();
        int mainKept=mainCount,scopeKept=scopeCount;
        String id=UUID.randomUUID().toString();
        while(true) {
            JSONObject out=new JSONObject();
            boolean truncated=mainKept<mainCount || scopeKept<scopeCount;
            out.put("schema",SCHEMA).put("id",id).put("package",source.pkg).put("epoch",source.epoch)
                .put("capture_session",session).put("captured_wall_time",wall).put("captured_uptime",source.time)
                .put("last_seen_wall_time",wall).put("last_seen_uptime",source.time)
                .put("width",source.width).put("height",source.height).put("opening",frame.opening)
                .put("result",text(frame.reason)).put("window",frame.window).put("scope_only",scopeOnly)
                .put("source_tree_complete",frame.tree!=null && frame.tree.complete)
                .put("source_scope_complete",frame.adScope!=null && frame.adScope.complete)
                .put("tree_complete",NativeBoundsPolicy.fullTreeComplete(source.complete,scopeOnly,mainCount,mainKept))
                .put("scope_complete",frame.adScope!=null && frame.adScope.complete && scopeCount>0 && scopeKept==scopeCount)
                .put("node_count",mainKept).put("source_node_count",mainCount).put("scope_node_count",scopeKept)
                .put("archive_truncated",truncated).put("first_frame",first).put("repeat_count",1).put("structure",signature)
                .put("child_count_kind","snapshot_normalized");
            List<String> reasons=new ArrayList<>(frame.traversalReasons);
            if(!source.complete && reasons.isEmpty())reasons.add("provider_incomplete");
            if(scopeOnly)reasons.add("scope_only");
            if(truncated)reasons.add("archive_payload_limit");
            out.put("truncation_reasons",new JSONArray(reasons));
            JSONObject traversal=new JSONObject().put("scope",scopeOnly?"scope":"global")
                .put("verified_aliases",frame.verifiedAliases).put("alias_parents",new JSONArray(frame.aliasParents))
                .put("duplicates",frame.duplicates).put("errors",frame.errors).put("missing_children",frame.missingChildren)
                .put("duplicate_edges",new JSONArray(frame.duplicateEdges)).put("duplicate_kinds",new JSONObject(frame.duplicateKinds));
            out.put("traversal",traversal).put("tree_nodes",nodes(source,mainKept,frame.labels));
            if(frame.adScope!=null) {
                out.put("ad_scope",new JSONObject().put("width",frame.adScope.width).put("height",frame.adScope.height)
                    .put("captured_uptime",frame.adScope.time).put("source_tree_complete",frame.adScope.complete)
                    .put("tree_complete",frame.adScope.complete && scopeCount>0 && scopeKept==scopeCount)
                    .put("source_node_count",scopeCount).put("node_count",scopeKept)
                    .put("archive_truncated",scopeKept<scopeCount)
                    .put("tree_nodes",nodes(frame.adScope,scopeKept,scopeOnly?frame.labels:null)));
            }
            // Reserve room for larger repeat counts and later timestamps when merging a frame.
            if(out.toString().getBytes(StandardCharsets.UTF_8).length<=NativeBoundsPolicy.MAX_FRAME_BYTES-128)return out;
            if(mainKept==0 && scopeKept==0)return null;
            if(scopeOnly){mainKept=mainKept*3/4;scopeKept=mainKept;}
            else {mainKept=mainKept*3/4;scopeKept=scopeKept*3/4;}
        }
    }
    private static JSONArray nodes(ControlTree.Snapshot tree,int count,Map<Integer,String> labels)throws Exception {
        JSONArray out=new JSONArray();
        for(int i=0;i<count;i++) {
            ControlTree.Node n=tree.nodes.get(i);String label=labels==null?"":labels.get(i);
            if(!NativeBoundsPolicy.labelAllowed(label))label="";
            out.put(new JSONObject().put("index",i).put("parent",n.parent).put("child_count",n.children)
                .put("bounds",new JSONArray(n.box)).put("role",text(n.role)).put("identity",text(n.identity))
                .put("class",text(n.className)).put("id",text(n.viewId)).put("visible",n.visible).put("clickable",n.clickable)
                .put("label",label).put("skip_countdown",n.skipCountdown)
                .put("enabled",n.enabled==null?JSONObject.NULL:n.enabled)
                .put("declared_clickable",n.declaredClickable==null?JSONObject.NULL:n.declaredClickable)
                .put("on_screen",n.onScreen==null?JSONObject.NULL:n.onScreen)
                .put("explicit_skip_ad",n.explicitSkipAd).put("navigation_disclosure",n.navigationDisclosure));
        }
        return out;
    }
    private static int importance(ControlTree.Snapshot tree) {
        int level=0;if(tree==null)return level;
        for(ControlTree.Node node:tree.nodes)if(node.visible) {
            if(UiControlPolicy.isControl(node.role))return 2;
            if("ad".equals(node.role) || "prompt".equals(node.role))level=1;
        }
        return level;
    }
    private String signature(SkipService.NativeDiagnostic frame)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        digestTree(digest,frame.tree,frame.tree==null?null:frame.labels);
        digestTree(digest,frame.adScope,frame.tree==null?frame.labels:null);
        add(digest,frame.opening);add(digest,frame.window);add(digest,frame.traversalReasons.toString());
        byte[] bytes=digest.digest();StringBuilder result=new StringBuilder();
        for(byte b:bytes){int value=b&255;result.append(Character.forDigit(value>>>4,16)).append(Character.forDigit(value&15,16));}
        return result.toString();
    }
    private static void digestTree(MessageDigest digest,ControlTree.Snapshot tree,Map<Integer,String> labels) {
        if(tree==null){add(digest,"no-tree");return;}
        add(digest,tree.width);add(digest,tree.height);add(digest,tree.complete);add(digest,tree.nodes.size());
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node n=tree.nodes.get(i);add(digest,n.parent);add(digest,n.children);
            for(int bound:n.box)add(digest,bound);
            add(digest,n.role);add(digest,n.identity);add(digest,n.className);add(digest,n.viewId);
            add(digest,n.visible);add(digest,n.clickable);add(digest,n.skipCountdown);
            add(digest,n.enabled);add(digest,n.declaredClickable);add(digest,n.onScreen);add(digest,n.explicitSkipAd);add(digest,n.navigationDisclosure);
            String label=labels==null?null:labels.get(i);add(digest,NativeBoundsPolicy.labelAllowed(label)?label:"");
        }
    }
    private static void add(MessageDigest digest,Object value) {
        String s=String.valueOf(value);byte[] bytes=s.getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length+":").getBytes(StandardCharsets.UTF_8));digest.update(bytes);
    }
    private static String text(String value){return value==null?"":value;}

    private JSONObject envelope(long now)throws Exception {
        return new JSONObject().put("schema",SCHEMA).put("updated_wall_time",now).put("capture_session",session)
            .put("retention_days",7).put("max_frames",NativeBoundsPolicy.MAX_FRAMES)
            .put("max_frame_bytes",NativeBoundsPolicy.MAX_FRAME_BYTES).put("max_total_bytes",NativeBoundsPolicy.MAX_TOTAL_BYTES)
            .put("queue_capacity",QUEUE_CAPACITY).put("dropped_records",dropped.get()).put("last_error",lastError)
            .put("frames",new JSONArray());
    }
    private boolean prune(long now)throws Exception {
        List<NativeBoundsPolicy.Budget> budgets=new ArrayList<>();
        for(Entry entry:frames)budgets.add(new NativeBoundsPolicy.Budget(entry.json.optLong("captured_wall_time",-1),entry.bytes));
        int overhead=envelope(now).toString().getBytes(StandardCharsets.UTF_8).length;
        List<Integer> kept=NativeBoundsPolicy.retained(budgets,now,overhead);
        if(kept.size()==frames.size())return false;
        List<Entry> remaining=new ArrayList<>();for(int index:kept)remaining.add(frames.get(index));
        frames.clear();frames.addAll(remaining);dirty=true;return true;
    }
    private void maintain()throws Exception {
        prune(System.currentTimeMillis());
        if(dirty && !persist())throw new IllegalStateException("Archive changes could not be saved: "+lastError);
    }
    private byte[] archiveBytes()throws Exception {
        long now=System.currentTimeMillis();prune(now);
        while(true) {
            JSONObject root=envelope(now);JSONArray array=new JSONArray();for(Entry entry:frames)array.put(entry.json);
            root.put("frames",array);byte[] bytes=root.toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length<=NativeBoundsPolicy.MAX_TOTAL_BYTES)return bytes;
            if(frames.isEmpty())throw new IllegalStateException("Archive envelope exceeds byte limit");
            frames.remove(0);dirty=true;
        }
    }
    private void restore() {
        try {
            disk=new AtomicFile(new File(new File(context.getFilesDir(),"diagnostics"),"native-bounds.json"));
            byte[] bytes;
            try(FileInputStream input=disk.openRead();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1) {
                    if(output.size()+count>NativeBoundsPolicy.MAX_TOTAL_BYTES)throw new IllegalStateException("Stored archive too large");
                    output.write(buffer,0,count);
                }
                bytes=output.toByteArray();
            }
            JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            if(root.optInt("schema")!=SCHEMA)throw new IllegalStateException("Unsupported archive schema");
            JSONArray saved=root.optJSONArray("frames");
            if(saved!=null)for(int i=0;i<saved.length();i++) {
                JSONObject frame=saved.optJSONObject(i);if(frame==null || frame.optInt("schema")!=SCHEMA)continue;
                try{if(!UUID.fromString(frame.optString("id")).toString().equals(frame.optString("id")))continue;}
                catch(IllegalArgumentException invalid){continue;}
                Entry entry=new Entry(frame);if(entry.bytes<=NativeBoundsPolicy.MAX_FRAME_BYTES)frames.add(entry);
            }
            if(saved!=null && saved.length()!=frames.size())dirty=true;
            prune(System.currentTimeMillis());if(dirty)persist();
            // Sampling deliberately starts afresh after process restart, preserving the first new generation frame.
        }catch(FileNotFoundException missing){/* No archive exists yet. */}
        catch(Exception error){frames.clear();failure("restore",error);}
    }
    private boolean persist() {
        FileOutputStream stream=null;
        try {
            if(disk==null)disk=new AtomicFile(new File(new File(context.getFilesDir(),"diagnostics"),"native-bounds.json"));
            File parent=disk.getBaseFile().getParentFile();
            if(!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())throw new IllegalStateException("Cannot create diagnostics directory");
            lastError="";byte[] bytes=archiveBytes();stream=disk.startWrite();stream.write(bytes);disk.finishWrite(stream);stream=null;
            dirty=false;return true;
        }catch(Exception error){
            if(stream!=null)try{disk.failWrite(stream);}catch(Exception rollback){Log.w("SplashSkipBounds","write rollback: "+rollback.getClass().getSimpleName());}
            dirty=true;failure("write",error);return false;
        }
    }
    private void failure(String operation,Exception error) {
        lastError=operation+": "+error.getClass().getSimpleName();Log.w("SplashSkipBounds",lastError);
    }
    private <T> T call(Callable<T> operation) {
        if(Thread.currentThread()==writerThread)try{return operation.call();}
            catch(Exception error){throw new IllegalStateException("Archive operation failed",error);}
        Future<T> future;
        try{future=writer.submit(operation);}catch(RejectedExecutionException busy){throw new IllegalStateException("Archive queue is busy",busy);}
        try{return future.get(10,TimeUnit.SECONDS);}
        catch(InterruptedException interrupted){cancelQueued(future);Thread.currentThread().interrupt();throw new IllegalStateException("Archive operation interrupted",interrupted);}
        catch(TimeoutException timeout){cancelQueued(future);throw new IllegalStateException("Archive operation timed out",timeout);}
        catch(ExecutionException error){throw new IllegalStateException("Archive operation failed",error.getCause());}
    }
    private void cancelQueued(Future<?> future) {
        // Do not interrupt an AtomicFile transaction already running on the writer.
        future.cancel(false);if(future instanceof Runnable)writer.remove((Runnable)future);
    }
}
