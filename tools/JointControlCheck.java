package com.codex.splashskip;
import java.util.*;
import java.io.*;
import static com.codex.splashskip.BilibiliVisualMatcher.*;

public final class JointControlCheck {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    static ControlTree.Snapshot tree(int dx,int dy,int scale,boolean huge) {
        List<ControlTree.Node> n=new ArrayList<>();
        n.add(new ControlTree.Node(-1,1,new int[]{0,0,1000*scale,2000*scale},huge,"","root"));
        n.add(new ControlTree.Node(0,1,new int[]{(90+dx)*scale,(90+dy)*scale,(210+dx)*scale,(150+dy)*scale},!huge,"","parent"));
        n.add(new ControlTree.Node(1,0,new int[]{(100+dx)*scale,(100+dy)*scale,(200+dx)*scale,(140+dy)*scale},false,UiControlPolicy.SKIP,"child"));
        return new ControlTree.Snapshot("test.app",8,100,1000*scale,2000*scale,n);
    }
    public static void main(String[] args)throws Exception {
        ControlTree.Snapshot a=tree(0,0,1,false),b=tree(400,800,2,false);
        ControlTree.Hint x=a.controls(Collections.emptyMap()).get(0),y=b.controls(Collections.emptyMap()).get(0);
        check(x.structure.equals(y.structure),"identity does not depend on coordinates or resolution");
        check(Arrays.equals(x.shape,y.shape),"relative parent/child features survive movement and scale");
        check(x.shape[2]==1 && x.shape[3]==.25f,"nearest clickable parent discovered");
        check(tree(0,0,1,true).controls(Collections.emptyMap()).get(0).shape[2]==0,"whole-ad clickable ancestor rejected");
        check(y.box[0]==1000 && y.box[1]==1800,"candidate uses current position");
        check(a.current("test.app",8,200,1000,2000),"fresh matching snapshot accepted");
        check(!a.current("other.app",8,200,1000,2000),"different owner rejected");
        check(!a.current("test.app",9,200,1000,2000),"old epoch rejected");
        check(!a.current("test.app",8,351,1000,2000),"expired tree rejected");
        check(!a.current("test.app",8,200,2000,1000),"rotated snapshot rejected");
        check(!ControlTree.safeParent(new int[]{100,100,200,140},new int[]{190,100,300,140},1000,2000),"unrelated parent rejected");
        Hit hit=new Hit(UiControlPolicy.SKIP,150,120,.99f,1000,2000).withTextBounds(110,105,190,135);
        hit.structure=x.structure;hit.jointFeatures=new float[JointControlModel.SIZE];hit.treeAssisted=true;
        Hit copy=hit.withModel(.99f).withMemory(true).withSignature(new float[640]);
        check(Arrays.equals(copy.textBounds,hit.textBounds) && copy.treeAssisted && copy.structure.equals(hit.structure),"metadata survives hit transformations");
        check(a.match(hit)!=null && a.match(hit).structure.equals(x.structure),"visual box joins its current tree node");
        check(a.match(new Hit(UiControlPolicy.SKIP,800,120,.99f,1000,2000).withTextBounds(760,105,840,135))==null,"unrelated image box does not join old tree location");
        int[] pixels=new int[1000*2000];Arrays.fill(pixels,0xffeeeeee);
        float[] features=JointControlModel.features(pixels,1000,2000,hit.textBounds,x);
        check(JointControlModel.valid(features),"bounded visual and structural feature vector");
        check(JointControlModel.features(pixels,1000,2000,new int[]{-1,0,3,3},x)==null,"out of frame visual crop rejected");
        ClickLearningSession session=new ClickLearningSession("test.app",8,100,hit);session.complete(200);
        check(!session.observe(400,ClickLearningSession.UNKNOWN,false,true),"unknown never labels success");
        check(!session.observe(450,ClickLearningSession.ABSENT,false,false),"overlay never labels success");
        check(!session.observe(500,ClickLearningSession.ABSENT,false,true) && session.observe(700,ClickLearningSession.ABSENT,false,true),"success requires two clear new frames");
        check(!session.ineffective(650,ClickLearningSession.PRESENT,true) && session.ineffective(850,ClickLearningSession.PRESENT,true),"no effect requires two later positive observations");
        boolean refused=false;try{JointControlModel.read(new ByteArrayInputStream("schema=1\nfeatures=76\nvalidated=false\n".getBytes("UTF-8")));}catch(IOException expected){refused=true;}
        check(refused,"unvalidated trained weights rejected");
        check(UiControlPolicy.prompt(new UiControlPolicy.Word("摇一摇手机",1,1,0,0,100,30)),"complete shake-phone prompt recognized as interaction context");
        check(!UiControlPolicy.prompt(new UiControlPolicy.Word("摇一摇手机领取金币",1,1,0,0,100,30)),"promotional sentence is not a complete interaction cue");
        for(String disclosure:new String[]{"跳转至详情页面或第三方应用","点击跳转至详情页或第三方应用›","点击跳转详情页面或第三方应用"})
            check(UiControlPolicy.prompt(new UiControlPolicy.Word(disclosure,1,1,0,0,500,30)),"complete destination disclosure accepted: "+disclosure);
        for(String unrelated:new String[]{"第三方应用","跳转至设置","跳转至详情页面","不跳转至详情页面或第三方应用","跳转至详情页面或第三方应用领取金币"})
            check(!UiControlPolicy.prompt(new UiControlPolicy.Word(unrelated,1,1,0,0,500,30)),"partial or unrelated disclosure rejected: "+unrelated);
        UiControlPolicy.Word disclosure=new UiControlPolicy.Word("跳转至详情页面或第三方应用",1,1,100,1500,900,1540);
        UiControlPolicy.Word close=new UiControlPolicy.Word("关闭",1,1,450,500,550,540);
        int[] launchPixels=new int[1000*2000];Arrays.fill(launchPixels,0xffeeeeee);
        for(int row=475;row<=565;row++)Arrays.fill(launchPixels,row*1000+380,row*1000+621,0xff222222);
        check(UiControlPolicy.find(launchPixels,1000,2000,Arrays.asList(close,disclosure),true)!=null,"current bounded close and full destination disclosure accepted during launch");
        check(UiControlPolicy.find(launchPixels,1000,2000,Arrays.asList(close,disclosure),false)==null,"destination disclosure does not authorize ordinary-page close");
        check(UiControlPolicy.find(pixels,1000,2000,Arrays.asList(close,disclosure),true)==null,"destination disclosure does not bypass current button boundary");
        check(UiControlPolicy.find(launchPixels,1000,2000,Arrays.asList(close),true)==null,"close alone remains unauthorized");
        check(!UiControlPolicy.prompt(new UiControlPolicy.Word(disclosure.text,.95f,1,100,1500,900,1540)) &&
                !UiControlPolicy.prompt(new UiControlPolicy.Word(disclosure.text,1,.89f,100,1500,900,1540)),"destination disclosure retains OCR confidence thresholds");
        List<ControlTree.Node> overlay=new ArrayList<>();
        overlay.add(new ControlTree.Node(-1,2,new int[]{0,0,1000,2000},false,"","root"));
        overlay.add(new ControlTree.Node(0,2,new int[]{0,0,1000,2000},false,"","ad-branch"));
        overlay.add(new ControlTree.Node(1,1,new int[]{830,55,990,155},true,"","button-parent"));
        overlay.add(new ControlTree.Node(2,0,new int[]{880,80,970,130},false,UiControlPolicy.CLOSE,"label"));
        overlay.add(new ControlTree.Node(1,0,new int[]{300,1700,700,1740},false,"prompt","disclosure"));
        overlay.add(new ControlTree.Node(0,0,new int[]{20,1900,180,1980},true,"nav","home-behind-ad"));
        ControlTree.Snapshot partialOverlay=new ControlTree.Snapshot("test.app",8,100,1000,2000,overlay,false);
        List<ControlTree.Hint> overlayHints=partialOverlay.controls(Collections.emptyMap());
        check(partialOverlay.fullScreenBranch(3) && !partialOverlay.sameBranch(3,5),"current full-screen branch separates underlying home navigation");
        check(TreeVisualLocator.closeGate(partialOverlay,overlayHints,true).isEmpty(),"incomplete tree permits only current full-screen ad branch with explicit close and cue");
        check(!TreeVisualLocator.closeGate(partialOverlay,overlayHints,false).isEmpty(),"old ad branch cannot authorize ordinary page close");
        List<ControlTree.Node> visibleNav=new ArrayList<>(overlay);
        visibleNav.set(5,new ControlTree.Node(1,0,new int[]{20,1900,180,1980},true,"nav","visible-nav"));
        ControlTree.Snapshot navScene=new ControlTree.Snapshot("test.app",8,100,1000,2000,visibleNav,false);
        check(!TreeVisualLocator.closeGate(navScene,navScene.controls(Collections.emptyMap()),true).isEmpty(),"navigation in active ad branch blocks close");
        List<ControlTree.Node> noCue=new ArrayList<>(overlay);noCue.remove(4);
        ControlTree.Snapshot ordinary=new ControlTree.Snapshot("test.app",8,100,1000,2000,noCue,false);
        check(!TreeVisualLocator.closeGate(ordinary,ordinary.controls(Collections.emptyMap()),true).isEmpty(),"ordinary full-screen close without cue rejected");
        List<ControlTree.Node> card=new ArrayList<>(overlay);
        card.set(1,new ControlTree.Node(0,2,new int[]{0,0,1000,500},false,"","page-card"));
        ControlTree.Snapshot incompleteCard=new ControlTree.Snapshot("test.app",8,100,1000,2000,card,false);
        check(!TreeVisualLocator.closeGate(incompleteCard,incompleteCard.controls(Collections.emptyMap()),true).isEmpty(),"partial ordinary page card rejected");
        float[] weights=new float[76];weights[0]=2;JointControlModel model=new JointControlModel(weights);
        check(model.score(features)>.8f,"valid model supplies a ranking score only");
        System.out.println("Joint checks: "+checks);
    }
}
