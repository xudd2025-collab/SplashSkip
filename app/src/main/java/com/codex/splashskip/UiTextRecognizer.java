package com.codex.splashskip;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Android asset adapter; the inference and decision code is also exercised directly on the PC. */
final class UiTextRecognizer implements AutoCloseable {
    private final Context context;
    private OnnxUiText engine;
    private SkipTextModel closeRanker;
    private TreeCandidateRanker treeRanker;
    private volatile boolean ready;
    private String error="loading";
    private OnnxUiText.Result last;
    private volatile long sceneGeneration;
    private long preparedGeneration;
    private int treeTraceCount;
    private int transitionRetries;
    private boolean preferFreshFrame;
    UiTextRecognizer(Context context){this.context=context.getApplicationContext();}
    synchronized void prepare() {
        if(engine!=null || !error.equals("loading"))return;
        try {
            engine=new OnnxUiText(asset("ui_text_det.onnx"),asset("ui_text_rec.onnx"),new String(asset("ui_text_keys.txt"),StandardCharsets.UTF_8));
            engine.warm();ready=true;error="ready";
            try{closeRanker=new SkipTextModel(context,"close_rank.tflite");}
            catch(Exception | LinkageError optional){Diagnostics.append(context,"close candidate ranking unavailable; OCR verification remains active");}
            try(InputStream in=context.getAssets().open("tree_candidate_rank.properties")){treeRanker=TreeCandidateRanker.read(in);}
            catch(IOException optional){Diagnostics.append(context,"tree identity ranking unavailable; current-frame checks remain active");}
            Diagnostics.append(context,"generic OCR ready; PP-OCRv4; CPU 2 threads; local models; no creative templates");
        } catch(Exception | LinkageError e) {
            error=e.getClass().getSimpleName();if(engine!=null)engine.close();engine=null;
            Diagnostics.append(context,"generic OCR unavailable: "+error);
        }
    }
    private byte[] asset(String name)throws IOException {
        try(InputStream stream=context.getAssets().open(name);ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[16384];int n;while((n=stream.read(buffer))!=-1)bytes.write(buffer,0,n);return bytes.toByteArray();
        }
    }
    boolean ready(){return ready;}
    void newScene(){sceneGeneration++;treeTraceCount=0;transitionRetries=0;preferFreshFrame=false;}
    boolean prefersFreshFrame(){return preferFreshFrame;}
    boolean deferFallback(boolean opening){return last!=null && SceneFramePolicy.deferFallback(opening,last.status);}
    boolean verifyControl(android.graphics.Bitmap screen,Hit hit,BooleanSupplier cancelled,long budget) {
        if(!ready || hit.textBounds==null || screen.getWidth()!=hit.frameWidth || screen.getHeight()!=hit.frameHeight)return false;
        int[] b=hit.textBounds;int w=b[2]-b[0],h=b[3]-b[1];
        if(w<=0 || h<=0 || b[0]<0 || b[1]<0 || b[2]>screen.getWidth() || b[3]>screen.getHeight())return false;
        int[] crop=new int[w*h];screen.getPixels(crop,0,w,b[0],b[1],w,h);
        try{return engine.verifyControl(crop,w,h,hit.rule,cancelled,budget);}
        catch(Exception | LinkageError error){Diagnostics.append(context,"current control recheck failed: "+error.getClass().getSimpleName());return false;}
    }
    void clearFrame(){last=null;error="frame-not-processed";}
    Hit findTree(int[] pixels,int width,int height,boolean opening,ControlTree.Snapshot tree,BooleanSupplier cancelled,long budget) {
        preferFreshFrame=false;
        if(!ready || tree==null)return null;
        JointLearningStore store=JointLearningStore.get(context);
        try {
            OnnxUiText.Result found=TreeVisualLocator.find(engine,pixels,width,height,opening,tree,
                store.enabled()?store.learned(tree.pkg):java.util.Collections.emptyMap(),
                hint -> rankHint(store,tree,pixels,width,height,hint),cancelled,budget);
            if(found!=null){
                transitionRetries=0;
                if(treeTraceCount++<5)Diagnostics.append(context,"tree path=verified complete="+tree.complete+" nodes="+tree.nodes.size());
                last=found;return found.hit;
            }
            String reason=TreeVisualLocator.lastReason();
            // During activity animations the new tree can arrive before its pixels.
            // Reacquire twice before spending a full OCR cycle on the old loading image.
            if(opening && (reason.equals("current-action-visual-mismatch") || reason.equals("tree-visual-deadline")) && transitionRetries++<2) {
                preferFreshFrame=true;last=null;error="waiting-for-current-control";
            }
            if(treeTraceCount<5 && !tree.controls(java.util.Collections.emptyMap()).isEmpty()){
                treeTraceCount++;
                Diagnostics.append(context,"tree path="+TreeVisualLocator.lastReason()+" complete="+tree.complete+" nodes="+tree.nodes.size());
            }
        }catch(Exception | LinkageError e){Diagnostics.append(context,"tree visual check deferred: "+e.getClass().getSimpleName());}
        return null;
    }
    private double rankHint(JointLearningStore store,ControlTree.Snapshot tree,int[] pixels,int w,int h,ControlTree.Hint hint) {
        float[] features=JointControlModel.features(pixels,w,h,hint.box,hint);
        double rank=store.rank(tree.pkg,hint,features);
        if(treeRanker!=null && UiControlPolicy.CLOSE.equals(hint.action))rank+=treeRanker.score(tree.pkg,features);
        if(closeRanker==null || !UiControlPolicy.CLOSE.equals(hint.action))return rank;
        int[] b=hint.box;int cw=b[2]-b[0],ch=b[3]-b[1];android.graphics.Bitmap crop=null;
        try {
            crop=android.graphics.Bitmap.createBitmap(pixels,b[1]*w+b[0],w,cw,ch,android.graphics.Bitmap.Config.ARGB_8888);
            return rank+closeRanker.probability(crop,new android.graphics.Rect(0,0,cw,ch),1);
        }catch(RuntimeException error){return rank;}
        finally{if(crop!=null)crop.recycle();}
    }
    Hit find(int[] pixels,int width,int height,boolean opening,BooleanSupplier cancelled,long budget) {
        last=null;
        if(!ready || cancelled.getAsBoolean())return null;
        try {
            if(preparedGeneration!=sceneGeneration){preparedGeneration=sceneGeneration;engine.resetResolution();}
            last=engine.find(pixels,width,height,opening,cancelled,Math.max(1,budget));return last.hit;
        }
        catch(Exception | LinkageError e) {
            error=e.getClass().getSimpleName();Diagnostics.append(context,"generic OCR run failed: "+e);return null;
        }
    }
    String status() {
        if(last==null)return "OCR="+(ready?error:"unavailable-"+error);
        int controls=0,cues=0;for(UiControlPolicy.Word word:last.words) {
            if(!UiControlPolicy.action(word.text).isEmpty())controls++;
            if(UiControlPolicy.adMark(word)||UiControlPolicy.prompt(word))cues++;
        }
        return "OCR="+last.status+" detail="+last.detailed+" boxes="+last.boxes+" controls="+controls+" adCues="+cues+" detect="+last.detectMs+" recognize="+last.recognizeMs+"ms";
    }
    String summary() {
        if(!ready)return "离线文字识别尚未就绪；旧规则可继续工作";
        if(last==null)return "本帧未进入文字识别，等待新画面";
        String status=last.status.equals("tree-verified")?"控件树与视觉已共同确认":last.status.equals("verified")?"已找到广告控件":last.status.equals("needs-detail")?"快速扫描完成，继续检查细小文字":last.status.equals("deadline")?"识别超时，等待新画面":last.status.equals("cancelled")?"画面已变化，停止本帧识别":"未发现可信广告控件";
        return "离线文字识别："+status+" · "+(last.detectMs+last.recognizeMs)+" ms";
    }
    int observe(Hit prior) {
        if(last==null || last.status.equals("deadline") || last.status.equals("cancelled") || last.status.equals("needs-detail"))return ClickLearningSession.UNKNOWN;
        for(UiControlPolicy.Word word:last.words)if(prior.rule.equals(UiControlPolicy.action(word.text)) &&
                word.confidence>=.90f && Math.abs(word.x()-prior.x)<Math.max(20,word.width()) && Math.abs(word.y()-prior.y)<Math.max(20,word.height()))
            return ClickLearningSession.PRESENT;
        // Partial/capped recognition is not evidence that the old control disappeared.
        if(!last.detailed || last.boxes>64 || last.words.size()<last.boxes || last.words.isEmpty())return ClickLearningSession.UNKNOWN;
        return ClickLearningSession.ABSENT;
    }
    @Override public synchronized void close(){ready=false;if(engine!=null)engine.close();engine=null;if(closeRanker!=null)closeRanker.close();closeRanker=null;}
}
