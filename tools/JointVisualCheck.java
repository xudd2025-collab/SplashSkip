package com.codex.splashskip;
import java.util.*;
import java.nio.file.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/** Synthetic accessibility trees verify the actual visual gate; these are NOT training records. */
public final class JointVisualCheck {
    static void require(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS "+label);}
    static ControlTree.Snapshot tree(OnnxUiText.Result found,int w,int h,int dx,int dy,boolean stripLabel) {
        java.util.List<ControlTree.Node> nodes=new ArrayList<>();
        nodes.add(new ControlTree.Node(-1,1,new int[]{0,0,w,h},false,"","root"));
        nodes.add(new ControlTree.Node(0,found.words.size(),new int[]{0,0,w,h},false,"","current-full-screen"));
        for(UiControlPolicy.Word word:found.words) {
            String role=UiControlPolicy.action(word.text);if(role.isEmpty())role=UiControlPolicy.adMark(word)?"ad":UiControlPolicy.prompt(word)?"prompt":"";
            if(role.isEmpty())continue;
            if(stripLabel && UiControlPolicy.isControl(role))role="";
            nodes.add(new ControlTree.Node(1,0,new int[]{word.left+dx,word.top+dy,word.right+dx,word.bottom+dy},true,role,"node-"+nodes.size()));
        }
        return new ControlTree.Snapshot("synthetic.test",1,0,w,h,nodes);
    }
    public static void main(String[] args)throws Exception {
        Path assets=Paths.get(args[0]);
        try(OnnxUiText engine=new OnnxUiText(Files.readAllBytes(assets.resolve("ui_text_det.onnx")),Files.readAllBytes(assets.resolve("ui_text_rec.onnx")),Files.readString(assets.resolve("ui_text_keys.txt")))) {
            engine.warm();BufferedImage im=UiTextCheck.synthetic("跳过","广告",260,320,false);
            int w=im.getWidth(),h=im.getHeight();int[] pixels=im.getRGB(0,0,w,h,null,0,w);
            var initial=engine.find(pixels,w,h,true,()->false,4000);require(initial.hit!=null,"baseline visual control");
            ControlTree.Snapshot a=tree(initial,w,h,0,0,false);
            var result=TreeVisualLocator.find(engine,pixels,w,h,true,a,Collections.emptyMap(),hint->0,()->false,180);
            require(result!=null && result.hit.treeAssisted && !result.detailed,"tree proposal visually verified, cannot prove absence");
            BufferedImage shifted=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=shifted.createGraphics();g.drawImage(im,130,250,null);g.dispose();
            var moved=TreeVisualLocator.find(engine,shifted.getRGB(0,0,w,h,null,0,w),w,h,true,tree(initial,w,h,130,250,false),Collections.emptyMap(),hint->0,()->false,180);
            require(moved!=null && moved.hit.x==result.hit.x+130 && moved.hit.y==result.hit.y+250,"moving both current tree and button moves the target");
            require(TreeVisualLocator.find(engine,shifted.getRGB(0,0,w,h,null,0,w),w,h,true,a,Collections.emptyMap(),hint->1,()->false,180)==null,"old tree coordinates on changed image rejected even with maximum rank");
            require(TreeVisualLocator.find(engine,pixels,w,h,true,a,Collections.emptyMap(),hint->1,()->true,180)==null,"cancelled tree scan rejected");
            ControlTree.Snapshot unlabeled=tree(initial,w,h,0,0,true);Map<String,String> learned=new HashMap<>();
            for(int i=2;i<unlabeled.nodes.size();i++)if(unlabeled.nodes.get(i).role.isEmpty())learned.put(unlabeled.structure(i),UiControlPolicy.SKIP);
            require(TreeVisualLocator.find(engine,pixels,w,h,true,unlabeled,learned,hint->1,()->false,180)!=null,"remembered structure proposes a current unlabeled node then OCR verifies it");
            int[] blank=new int[w*h];Arrays.fill(blank,0xffeeeeee);
            require(TreeVisualLocator.find(engine,blank,w,h,true,unlabeled,learned,hint->1,()->false,180)==null,"learned structure alone cannot authorize a click");
            BufferedImage close=UiTextCheck.synthetic("关闭","广告",260,320,false);int[] cp=close.getRGB(0,0,w,h,null,0,w);
            engine.resetResolution();var closeOcr=engine.find(cp,w,h,true,()->false,4000);require(closeOcr.hit!=null,"baseline close with ad context");
            ControlTree.Snapshot ct=tree(closeOcr,w,h,0,0,false);
            require(TreeVisualLocator.find(engine,cp,w,h,true,ct,Collections.emptyMap(),hint->0,()->false,180)!=null,"complete tree and clickable ancestry plus visual ad context allow close");
            ControlTree.Snapshot partial=new ControlTree.Snapshot(ct.pkg,ct.epoch,ct.time,w,h,ct.nodes,false);
            require(TreeVisualLocator.find(engine,cp,w,h,true,partial,Collections.emptyMap(),hint->0,()->false,180)!=null,"partial full-screen ad branch still needs live visual cue");
            java.util.List<ControlTree.Node> nav=new ArrayList<>(ct.nodes);nav.add(new ControlTree.Node(1,0,new int[]{5,5,10,10},true,"nav","nav"));
            require(TreeVisualLocator.find(engine,cp,w,h,true,new ControlTree.Snapshot(ct.pkg,1,0,w,h,nav),Collections.emptyMap(),hint->1,()->false,180)==null,"page navigation blocks standalone close fast path");
            java.util.List<ControlTree.Node> dialog=new ArrayList<>();for(ControlTree.Node node:ct.nodes)if(!node.role.equals("ad"))dialog.add(node);
            require(TreeVisualLocator.find(engine,cp,w,h,true,new ControlTree.Snapshot(ct.pkg,1,0,w,h,dialog),Collections.emptyMap(),hint->1,()->false,180)==null,"ordinary close without verified context rejected");
        }
    }
}
