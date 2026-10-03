package com.codex.splashskip;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Local, bounded records for review and grouped desktop training. No background network or fitting. */
final class JointLearningStore {
    static final int LIMIT=256;static final long AGE=30L*24*60*60*1000;
    private static JointLearningStore instance;
    static synchronized JointLearningStore get(Context c){if(instance==null)instance=new JointLearningStore(c.getApplicationContext());return instance;}
    private final Context context;private final AtomicFile file;
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private final List<JSONObject> records=new ArrayList<>();
    private JointControlModel model;
    private String observedScene="";private final Set<String> observedStructures=new HashSet<>();
    private JointLearningStore(Context c) {
        context=c;file=new AtomicFile(new File(c.getFilesDir(),"joint-controls.json"));
        try(InputStream in=file.openRead()) {
            JSONObject root=new JSONObject(read(in,2*1024*1024));
            if(root.getInt("schema")!=1)throw new IOException("schema");
            JSONArray a=root.getJSONArray("records");if(a.length()>LIMIT)throw new IOException("limit");
            for(int i=0;i<a.length();i++) {JSONObject r=a.getJSONObject(i);if(vector(r)!=null && label(r.optString("label")))records.add(r);}
        }catch(FileNotFoundException empty){}catch(Exception invalid){records.clear();Diagnostics.append(c,"joint records could not be restored");}
        prune();
        try(InputStream in=c.getAssets().open("joint_control.properties")){model=JointControlModel.read(in);}
        catch(FileNotFoundException pending){}catch(Exception pending){Diagnostics.append(c,"joint rank model unavailable; using verified memory");}
    }
    private void prune(){
        long now=System.currentTimeMillis();records.removeIf(r->r.optLong("time")>now || now-r.optLong("time")>AGE);
        while(records.size()>LIMIT)records.remove(records.size()-1);
        int evidence=0,bytes=0;for(JSONObject r:records)if(r.has("tree_nodes")) {
            bytes+=r.optJSONArray("tree_nodes").toString().getBytes(StandardCharsets.UTF_8).length;
            if(++evidence>16 || bytes>512*1024)
                for(String key:new String[]{"tree_nodes","tree_complete","tree_uptime","frame_width","frame_height","target_index"})r.remove(key);
        }
    }
    synchronized String record(String pkg,long epoch,BilibiliVisualMatcher.Hit hit) {
        return record(pkg,epoch,hit,true);
    }
    private synchronized String record(String pkg,long epoch,BilibiliVisualMatcher.Hit hit,boolean attempted) {
        if(!enabled() || !JointControlModel.valid(hit.jointFeatures))return "";
        try {
            JSONObject r=new JSONObject();String id=UUID.randomUUID().toString();
            r.put("id",id).put("time",System.currentTimeMillis()).put("package",pkg).put("action",hit.rule)
                .put("structure",hit.structure==null?"":hit.structure).put("session",epoch).put("label","unknown")
                .put("source",attempted?"automatic":"tree_observation").put("attempted",attempted).put("tree_assisted",hit.treeAssisted).put("schema",1);
            try{r.put("version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName);}catch(Exception ignored){}
            JSONArray f=new JSONArray();for(float v:hit.jointFeatures)f.put((double)v);r.put("features",f);
            records.add(0,r);prune();if(attempted)save();return id;
        }catch(JSONException e){return "";}
    }
    synchronized void observeCandidate(ControlTree.Snapshot tree,int[] pixels,int w,int h) {
        if(!enabled())return;
        String scene=tree.pkg+":"+tree.epoch;
        if(!scene.equals(observedScene)){observedScene=scene;observedStructures.clear();}
        List<ControlTree.Hint> hints=tree.controls(Collections.emptyMap());
        if(hints.size()!=1 || observedStructures.size()>=8)return;
        ControlTree.Hint hint=hints.get(0);if(!observedStructures.add(hint.structure))return;
        BilibiliVisualMatcher.Hit sample=new BilibiliVisualMatcher.Hit(hint.action,(hint.box[0]+hint.box[2])/2,(hint.box[1]+hint.box[3])/2,0,w,h);
        sample.structure=hint.structure;sample.jointFeatures=JointControlModel.features(pixels,w,h,hint.box,hint);
        String id=record(tree.pkg,tree.epoch,sample,false);
        // Bounded diagnostic evidence from this frame, with semantic roles only.
        // Retained under the same local record limit/expiry and never used as saved geometry.
        for(JSONObject r:records)if(id.equals(r.optString("id")))try {
            JSONArray nodes=new JSONArray();
            for(ControlTree.Node n:tree.nodes)nodes.put(new JSONObject().put("parent",n.parent)
                .put("children",n.children).put("box",new JSONArray(n.box)).put("clickable",n.clickable)
                .put("role",n.role).put("identity",n.identity).put("visible",n.visible));
            r.put("tree_nodes",nodes).put("tree_complete",tree.complete).put("tree_uptime",tree.time)
                .put("frame_width",w).put("frame_height",h).put("target_index",hint.nodeIndex);
            save();break;
        }catch(JSONException ignored){}
    }
    synchronized void outcome(String id,String label,String source) {
        if(id==null || id.isEmpty() || !label(label))return;
        for(JSONObject r:records)if(id.equals(r.optString("id"))) {
            if(!r.optBoolean("attempted",true) && !label.equals("unknown"))return;
            // Automatic observations never overwrite an explicit correction.
            if("user".equals(r.optString("source")) && !"user".equals(source))return;
            try{r.put("label",label).put("source",source);save();}catch(JSONException ignored){}return;
        }
    }
    synchronized Map<String,String> learned(String pkg) {
        prune();Map<String,String> result=new HashMap<>();Set<String> blocked=new HashSet<>();
        for(JSONObject r:records)if(pkg.equals(r.optString("package")) && !r.optString("structure").isEmpty()) {
            String key=r.optString("structure");String label=r.optString("label");
            if(label.equals("no_effect") || label.equals("mistouch"))blocked.add(key);
            if(label.equals("success") && UiControlPolicy.isControl(r.optString("action")))result.put(key,r.optString("action"));
        }
        for(String key:blocked)result.remove(key);return result;
    }
    synchronized float rank(String pkg,ControlTree.Hint hint,float[] features) {
        if(!JointControlModel.valid(features))return 0;
        float score=model==null?0:model.score(features);
        if(learned(pkg).containsKey(hint.structure)) {
            float best=0;
            for(JSONObject r:records)if(pkg.equals(r.optString("package")) && hint.structure.equals(r.optString("structure")) && r.optString("label").equals("success")) {
                float[] prior=vector(r);if(prior==null)continue;float error=0;
                for(int i=JointControlModel.SHAPE;i<JointControlModel.SIZE;i++)error+=Math.abs(features[i]-prior[i]);
                best=Math.max(best,1-error/64);
            }
            score+=best;
        }
        return score;
    }
    boolean enabled(){return context.getSharedPreferences("settings",0).getBoolean("joint_learning",true);}
    synchronized String summary() {
        prune();int success=0,failed=0,tree=0,observed=0;
        for(JSONObject r:records){String l=r.optString("label");if(l.equals("success"))success++;if(l.equals("no_effect")||l.equals("mistouch"))failed++;if(!r.optString("structure").isEmpty())tree++;if(!r.optBoolean("attempted",true))observed++;}
        return "本地样本 "+records.size()+" 条 · 含控件结构 "+tree+" 条\n已确认消失/成功 "+success+" · 无效/误触 "+failed+" · 待确认 "+(records.size()-success-failed)+
            "\n其中 "+observed+" 条为未点击候选，仅供诊断\n"+(model==null?"排序模型待收集有效样本后在电脑训练":"已加载通过分组验证的联合排序模型");
    }
    synchronized List<JSONObject> recent(){prune();List<JSONObject> copy=new ArrayList<>();for(JSONObject r:records)try{copy.add(new JSONObject(r.toString()));}catch(JSONException ignored){}return copy;}
    synchronized byte[] exportBytes()throws JSONException {
        prune();JSONObject root=new JSONObject();root.put("schema",1).put("feature_count",JointControlModel.SIZE).put("records",new JSONArray(records));
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }
    synchronized void clear(){records.clear();observedStructures.clear();save();}
    private void save() {
        try {
            final byte[] bytes=exportBytes();writer.execute(()->{
                FileOutputStream out=null;try{out=file.startWrite();out.write(bytes);file.finishWrite(out);}
                catch(Exception e){if(out!=null)file.failWrite(out);Diagnostics.append(context,"joint record save failed");}
            });
        }catch(JSONException ignored){}
    }
    static boolean label(String s){return s.equals("unknown")||s.equals("success")||s.equals("no_effect")||s.equals("mistouch");}
    private static float[] vector(JSONObject r) {
        try {JSONArray a=r.getJSONArray("features");if(a.length()!=JointControlModel.SIZE)return null;
            float[] f=new float[a.length()];for(int i=0;i<f.length;i++)f[i]=(float)a.getDouble(i);return JointControlModel.valid(f)?f:null;
        }catch(JSONException e){return null;}
    }
    private static String read(InputStream in,int limit)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1){if(b.size()+n>limit)throw new IOException("limit");b.write(buf,0,n);}return new String(b.toByteArray(),StandardCharsets.UTF_8);
    }
}
