package com.codex.splashskip;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Real pixels, synthetic tree proposals. Live parent structure is tested separately on device. */
public final class TreeOverlayCheck {
    static int checks;
    static void check(boolean v,String label){if(!v)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static ControlTree.Snapshot proposal(java.util.List<UiControlPolicy.Word> words,int w,int h,int dx,int dy,boolean cue,boolean safe,boolean nav) {
        java.util.List<ControlTree.Node> nodes=new ArrayList<>();
        nodes.add(new ControlTree.Node(-1,2,new int[]{0,0,w,h},false,"","root"));
        nodes.add(new ControlTree.Node(0,3,new int[]{0,0,w,h},true,"","overlay"));
        for(var word:words) {
            String role=UiControlPolicy.action(word.text);
            if(!role.equals(UiControlPolicy.CLOSE))role=UiControlPolicy.prompt(word)?"prompt":"";
            if(role.isEmpty() || role.equals("prompt")&&!cue)continue;
            int[] b={word.left+dx,word.top+dy,word.right+dx,word.bottom+dy};
            int parent=1;
            if(role.equals(UiControlPolicy.CLOSE)) {
                parent=nodes.size();int pad=word.height()/3;
                int[] pb={Math.max(1,b[0]-pad),Math.max(1,b[1]-pad),Math.min(w-1,b[2]+pad),Math.min(h-1,b[3]+pad)};
                nodes.add(new ControlTree.Node(1,1,pb,safe,"","small-parent"));
            }
            nodes.add(new ControlTree.Node(parent,0,b,false,role,"label-"+nodes.size()));
        }
        nodes.add(new ControlTree.Node(nav?1:0,0,new int[]{10,h-50,100,h-10},true,"nav","home"));
        return new ControlTree.Snapshot("test.overlay",1,0,w,h,nodes,false);
    }
    static OnnxUiText.Result find(OnnxUiText e,BufferedImage im,ControlTree.Snapshot t,boolean opening)throws Exception {
        int w=im.getWidth(),h=im.getHeight();return TreeVisualLocator.find(e,im.getRGB(0,0,w,h,null,0,w),w,h,opening,t,Collections.emptyMap(),hint->1,()->false,240);
    }
    public static void main(String[] args)throws Exception {
        Path a=Paths.get(args[0]);
        try(OnnxUiText e=new OnnxUiText(Files.readAllBytes(a.resolve("ui_text_det.onnx")),Files.readAllBytes(a.resolve("ui_text_rec.onnx")),Files.readString(a.resolve("ui_text_keys.txt")))) {
            e.warm();
            for(int index=1;index<args.length;index++) {
                BufferedImage original=ImageIO.read(Paths.get(args[index]).toFile());
                for(int width:new int[]{720,1216,1440}) {
                    int height=Math.round(original.getHeight()*width/(float)original.getWidth());
                    BufferedImage im=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=im.createGraphics();g.drawImage(original,0,0,width,height,null);g.dispose();
                    e.resetResolution();var full=e.find(im.getRGB(0,0,width,height,null,0,width),width,height,true,()->false,6000);
                    // Read prompt separately if the full OCR stopped on the ad badge first.
                    check(full.hit!=null,"full OCR baseline "+index+" width="+width);
                    // Explicit annotation is fixture data only; production reads this box from
                    // the current tree. Never derive the fixture cue from advertiser content.
                    boolean pink=args[index].contains("025943") || args[index].contains("080615");
                    int[] pb=pink?new int[]{140,1883,1075,2086}:new int[]{332,2175,886,2226};
                    int l=Math.round(pb[0]*width/1216f),top=Math.round(pb[1]*height/2640f),r=Math.round(pb[2]*width/1216f),bottom=Math.round(pb[3]*height/2640f);
                    int[] cuePixels=im.getRGB(l,top,r-l,bottom-top,null,0,r-l);
                    var pr=pink?e.readCueRegion(cuePixels,r-l,bottom-top,()->false,500):e.readRegion(cuePixels,r-l,bottom-top,()->false,500);
                    check(pr!=null && UiControlPolicy.prompt(pr),"current fixture disclosure recognized");
                    java.util.List<UiControlPolicy.Word> words=new ArrayList<>();
                    for(var word:full.words)if(UiControlPolicy.isControl(UiControlPolicy.action(word.text)))words.add(word);
                    words.add(new UiControlPolicy.Word(pr.text,pr.confidence,pr.weakest,l,top,r,bottom));
                    var t=proposal(words,width,height,0,0,true,true,false);
                    var hit=find(e,im,t,true);
                    check(hit!=null && hit.hit.treeAssisted,"current translucent close and current tree "+index+" width="+width+" reason="+TreeVisualLocator.lastReason());
                    check(find(e,im,proposal(words,width,height,0,0,false,true,false),true)==null,"missing context rejected");
                    check(find(e,im,proposal(words,width,height,0,0,true,false,false),true)==null,"full-ad ancestor rejected");
                    check(find(e,im,proposal(words,width,height,0,0,true,true,true),true)==null,"same-branch navigation rejected");
                    check(find(e,im,t,false)==null,"ordinary page close rejected");
                    int dx=-width/12,dy=height/16;
                    BufferedImage moved=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);g=moved.createGraphics();g.setColor(Color.GRAY);g.fillRect(0,0,width,height);
                    for(var word:words)if(UiControlPolicy.isControl(UiControlPolicy.action(word.text)) || UiControlPolicy.prompt(word)) {
                        int p=Math.min(16,word.height()/3);int left=Math.max(0,word.left-p),upper=Math.max(0,word.top-p),right=Math.min(width,word.right+p),b=Math.min(height,word.bottom+p);
                        // Keep each current control/cue in bounds, independently of the creative body.
                        if(left+dx>=0 && b+dy<height)g.drawImage(im.getSubimage(left,upper,right-left,b-upper),left+dx,upper+dy,null);
                    }
                    g.dispose();
                    var movedHit=find(e,moved,proposal(words,width,height,dx,dy,true,true,false),true);
                    check(movedHit!=null,"moved control with replaced creative accepted");
                    check(find(e,moved,t,true)==null,"stale tree coordinates on replaced scene rejected");
                }
            }
        }
        System.out.println("Overlay checks="+checks);
    }
}
