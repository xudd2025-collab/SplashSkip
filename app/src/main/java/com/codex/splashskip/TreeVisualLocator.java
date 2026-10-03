package com.codex.splashskip;
import java.util.*;
import java.util.function.*;
import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Shared desktop/phone verification. Tree/memory hints must be re-read from this image. */
final class TreeVisualLocator {
    private static final ThreadLocal<String> reason=ThreadLocal.withInitial(()->"not-run");
    static String lastReason(){return reason.get();}
    private static OnnxUiText.Result defer(String why){reason.set(why);return null;}
    static String closeGate(ControlTree.Snapshot tree,List<ControlTree.Hint> hints,boolean opening) {
        int closeIndex=-1;
        for(ControlTree.Hint h:hints)if(UiControlPolicy.CLOSE.equals(h.action)) {
            closeIndex=h.nodeIndex;
            if(!opening || h.shape[10]!=1 || h.shape[2]!=1)return "unsafe-close-parent-or-scene";
        }
        if(closeIndex<0)return "";
        boolean fullOverlay=tree.fullScreenBranch(closeIndex);
        if(!tree.complete && !fullOverlay)return "partial-nonfullscreen-tree";
        boolean cue=false;
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node n=tree.nodes.get(i);
            if(n.role.equals("nav") && (!fullOverlay || tree.sameBranch(closeIndex,i)))return "visible-navigation-branch";
            if((n.role.equals("ad") || n.role.equals("prompt")) && tree.sameBranch(closeIndex,i))cue=true;
        }
        return cue?"":"no-same-branch-ad-cue";
    }
    static OnnxUiText.Result find(OnnxUiText engine,int[] pixels,int width,int height,boolean opening,
            ControlTree.Snapshot tree,Map<String,String> learned,ToDoubleFunction<ControlTree.Hint> rank,
            BooleanSupplier cancelled,long budget)throws Exception {
        reason.set("starting");
        if(tree==null || cancelled.getAsBoolean() || budget<20)return defer("no-current-tree-or-budget");
        List<ControlTree.Hint> hints=tree.controls(learned);
        if(hints.isEmpty() || hints.size()>2)return defer("ambiguous-control-count");
        boolean close=false;int closeIndex=-1;
        for(ControlTree.Hint h:hints)if(UiControlPolicy.CLOSE.equals(h.action)){close=true;closeIndex=h.nodeIndex;}
        String gate=closeGate(tree,hints,opening);if(!gate.isEmpty())return defer(gate);
        long start=System.nanoTime(),deadline=start+Math.min(close?240:180,budget)*1_000_000L;
        // A single current control has nothing to rank. Avoid optional model/bitmap
        // work consuming the local OCR budget on the common splash path.
        if(hints.size()>1) {
            Map<ControlTree.Hint,Double> scores=new HashMap<>();for(ControlTree.Hint h:hints)scores.put(h,rank.applyAsDouble(h));
            hints.sort((a,b)->Double.compare(scores.get(b),scores.get(a)));
        }
        List<UiControlPolicy.Word> words=new ArrayList<>();
        for(ControlTree.Hint h:hints) {
            UiControlPolicy.Word w=readBox(engine,pixels,width,height,h.box,cancelled,deadline,false);
            if(w==null || !h.action.equals(UiControlPolicy.action(w.text)))return defer("current-action-visual-mismatch");
            words.add(w);
        }
        List<Integer> cues=new ArrayList<>();
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node node=tree.nodes.get(i);
            if(!close && node.role.equals("nav"))words.add(new UiControlPolicy.Word("首页",1,1,0,0,1,1));
            if((node.role.equals("ad") || node.role.equals("prompt")) &&
                    (!close || tree.sameBranch(closeIndex,i)))cues.add(i);
        }
        // The splash may coexist with an underlying home tree. Inspect only cues in
        // the active full-screen branch, then verify their text in this screenshot.
        if(close && cues.isEmpty())return defer("no-same-branch-ad-cue");
        cues.sort((a,b)->Boolean.compare(tree.nodes.get(b).role.equals("prompt"),tree.nodes.get(a).role.equals("prompt")));
        int checked=0,confirmed=0;
        for(int i:cues) {
            if(checked++>=3 || (close && confirmed>0))break;
            ControlTree.Node node=tree.nodes.get(i);
            UiControlPolicy.Word w=readBox(engine,pixels,width,height,node.box,cancelled,deadline,true);
            if(w!=null && (UiControlPolicy.adMark(w) || UiControlPolicy.prompt(w))) {
                words.add(w);confirmed++;
            }
        }
        if(close && confirmed==0)return defer("current-ad-cue-visual-mismatch "+engine.cueStatus());
        if(cancelled.getAsBoolean() || System.nanoTime()>deadline)return defer("tree-visual-deadline");
        List<int[]> verifiedParents=new ArrayList<>();
        if(close)for(ControlTree.Hint h:hints)if(UiControlPolicy.CLOSE.equals(h.action)) {
            int[] parent=tree.safeClickableParent(h.nodeIndex);
            if(parent!=null)verifiedParents.add(parent);
        }
        Hit hit=UiControlPolicy.find(pixels,width,height,words,opening,verifiedParents);
        if(hit==null) {
            UiControlPolicy.Word action=words.get(0);
            return defer("visual-policy-rejected confidence="+action.confidence+" weakest="+action.weakest);
        }
        hit.treeAssisted=true;
        OnnxUiText.Result result=new OnnxUiText.Result(hit,words,"tree-verified",words.size(),0,(System.nanoTime()-start)/1_000_000);
        result.detailed=false;reason.set("tree-verified");return result;
    }
    private static UiControlPolicy.Word readBox(OnnxUiText engine,int[] pixels,int w,int h,int[] b,BooleanSupplier cancelled,long deadline,boolean cue)throws Exception {
        if(b[0]<0 || b[1]<0 || b[2]>w || b[3]>h || b[2]<=b[0] || b[3]<=b[1] ||
            b[2]-b[0]>w*(cue?1f:.85f) || b[3]-b[1]>Math.min(w,h)*(cue?.28f:.15f))return null;
        int cw=b[2]-b[0],ch=b[3]-b[1];int[] crop=new int[cw*ch];
        for(int y=0;y<ch;y++)System.arraycopy(pixels,(b[1]+y)*w+b[0],crop,y*cw,cw);
        boolean padded=cue && (ch>Math.min(w,h)*.12f || cw>ch*14);
        UiControlPolicy.Word read=padded?engine.readCueRegion(crop,cw,ch,cancelled,Math.min(140,(deadline-System.nanoTime())/1_000_000)):
            engine.readRegion(crop,cw,ch,cancelled,Math.min(90,(deadline-System.nanoTime())/1_000_000));
        return read==null?null:new UiControlPolicy.Word(read.text,read.confidence,read.weakest,b[0]+read.left,b[1]+read.top,b[0]+read.right,b[1]+read.bottom);
    }
}
