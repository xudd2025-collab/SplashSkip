package com.codex.splashskip;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Native policy regressions plus replay of actual captures exported as tab-separated fields. */
public final class NativeControlCheck {
    static int checks;
    static void ok(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static ControlTree.Node node(int p,int count,int[] b,boolean click,String role,String id){return new ControlTree.Node(p,count,b,click,role,id);}
    static List<ControlTree.Node> fixture() {
        List<ControlTree.Node> n=new ArrayList<>();int[] full={0,0,1216,2640},button={866,70,1146,196};
        n.add(node(-1,1,full,false,"","decor"));
        n.add(node(0,2,full,false,"","junction"));
        n.add(node(1,2,full,false,"","ad-branch"));
        n.add(node(1,1,full,false,"","home-branch"));
        n.add(node(2,1,button,true,"","unused_res_a"));
        n.add(node(4,1,button,false,"","wrapper"));
        n.add(node(5,0,new int[]{963,100,1061,166},false,UiControlPolicy.CLOSE,"label"));
        n.add(node(2,0,new int[]{335,2174,881,2223},false,"prompt","disclosure"));
        n.add(node(3,0,new int[]{70,2524,202,2602},true,"nav","home-tab"));
        return n;
    }
    static ControlTree.Snapshot tree(List<ControlTree.Node> n){return new ControlTree.Snapshot("test.app",9,100,1216,2640,n);}
    static String decode(String s){return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception {
        var n=fixture();var t=tree(n);var c=NativeControlPolicy.find(t,true);
        ok(c!=null && c.label.nodeIndex==6 && c.target==4,"nonclickable parent resolves clickable grandparent");
        var hit=c.hit(t);
        ok(hit.nativeOnly && Float.isNaN(hit.modelProbability),"native proof is not fabricated model confidence");
        ok(SceneFramePolicy.oneFrame(hit,150) && !SceneFramePolicy.needsVisualRecheck(hit,false),"native action bypasses OCR gate");
        ok(!SceneFramePolicy.allowsGesture(hit),"native refusal cannot become blind coordinate tap");
        ok(hit.withMemory(true).withModel(.99f).nativeOnly,"hit copies preserve native-only guard");
        ok(NativeControlPolicy.matches(c,t,hit),"same current native chain matches");
        ok(NativeControlPolicy.find(t,false)==null,"ordinary close outside launch is protected");
        ok(!t.current("other.app",9,110,1216,2640) && !t.current("test.app",10,110,1216,2640)
            && !t.current("test.app",9,351,1216,2640),"package generation and age must match");
        ok(NativeControlPolicy.find(new ControlTree.Snapshot("test.app",9,100,1216,2640,n,false),true)==null,"partial native tree cannot authorize click");
        n=fixture();n.set(7,node(3,0,n.get(7).box,false,"prompt","disclosure"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"unrelated ad cue in other branch is insufficient");
        n=fixture();n.set(8,node(2,0,n.get(8).box,true,"nav","home-tab"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"navigation in same branch blocks plain close");
        n=fixture();n.set(4,node(2,1,new int[]{0,0,1216,2640},true,"","ad-container"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"giant clickable ad container rejected");
        n=fixture();n.set(6,new ControlTree.Node(5,0,n.get(6).box,false,UiControlPolicy.CLOSE,"label",false));
        ok(NativeControlPolicy.find(tree(n),true)==null,"invisible or disabled label cannot authorize");
        n=fixture();n.set(4,new ControlTree.Node(2,1,n.get(4).box,true,"","ancestor",false));
        ok(NativeControlPolicy.find(tree(n),true)==null,"invisible or disabled ancestor cannot authorize");
        n=fixture();n.add(node(2,0,new int[]{100,70,220,135},true,UiControlPolicy.CLOSE,"second-close"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"two eligible close buttons deferred");
        n=fixture();n.set(6,node(5,0,n.get(6).box,false,"","search-field"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"same index and bounds without close text rejected");
        n=fixture();n.set(6,node(5,0,n.get(6).box,false,UiControlPolicy.CLOSE,"different-control"));
        var changed=tree(n);
        ok(!NativeControlPolicy.matches(NativeControlPolicy.find(changed,true),changed,hit),"reused generic ID cannot replace a changed chain");
        for(double scale:new double[]{.5,1,1.5}) {
            n=fixture();for(int i:new int[]{4,5,6}) {
                var a=n.get(i);int[] b=a.box.clone();for(int j=0;j<4;j++)b[j]-=(j%2==0?500:-400);
                n.set(i,node(a.parent,a.children,b,a.clickable,a.role,a.identity));
            }
            List<ControlTree.Node> scaled=new ArrayList<>();for(var a:n){int[] b=a.box.clone();for(int j=0;j<4;j++)b[j]=(int)Math.round(b[j]*scale);scaled.add(node(a.parent,a.children,b,a.clickable,a.role,a.identity));}
            var moved=new ControlTree.Snapshot("test.app",9,100,(int)(1216*scale),(int)(2640*scale),scaled);
            var proposed=NativeControlPolicy.find(moved,true);
            ok(proposed!=null && proposed.target==4,"moved control at resolution scale "+scale);
            ok(!NativeControlPolicy.matches(proposed,moved,hit),"old geometry cannot execute after movement "+scale);
        }
        var visual=new BilibiliVisualMatcher.Hit(UiControlPolicy.CLOSE,hit.x,hit.y,.99f,1216,2640).withModel(.99f);
        ok(!SceneFramePolicy.tryNative(visual) && SceneFramePolicy.tryNative(hit),"visual-only target does not waste recheck budget on missing native nodes");
        visual.treeAssisted=true;
        ok(SceneFramePolicy.tryNative(visual),"visual verification with native evidence keeps ancestor path");
        ok(SceneFramePolicy.needsVisualRecheck(visual,false) && !SceneFramePolicy.needsVisualRecheck(visual,true)
            && NativeControlPolicy.matches(c,t,visual),"visually rechecked path can still use native ancestor");
        n=new ArrayList<>();int[] full={0,0,1216,2640},controlRow={0,119,1216,294};
        n.add(node(-1,1,full,false,"","decor"));n.add(node(0,2,full,false,"","shared-home-ad"));
        n.add(node(1,2,controlRow,false,"","control-row"));
        n.add(node(2,0,new int[]{1002,161,1177,273},true,UiControlPolicy.SKIP,"skip"));
        n.add(node(2,0,new int[]{460,193,950,239},false,"ad","ad-label"));
        n.add(node(1,0,new int[]{70,2524,202,2602},true,"nav","home-tab"));
        var rowTree=tree(n);var rowHit=NativeControlPolicy.find(rowTree,true);
        ok(rowHit!=null && rowHit.target==3,"skip and ad share compact group despite home in outer branch");
        n.set(4,node(1,0,new int[]{460,193,950,239},false,"ad","unrelated-ad-label"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"nearby ad label with different parent is insufficient");
        n.set(4,node(2,0,new int[]{460,193,950,239},false,"ad","ad-label"));
        n.add(node(2,0,new int[]{20,150,160,240},true,"nav","local-navigation"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"navigation inside compact group remains protected");
        for(String arg:args) {
            List<String> rows=Files.readAllLines(Paths.get(arg));String[] header=rows.remove(0).split("\t");n=new ArrayList<>();
            for(String row:rows){String[] p=row.split("\t",-1);boolean visible=Boolean.parseBoolean(p[3]);
                String role=visible?ControlTree.role(decode(p[8])):"";if(visible && role.isEmpty())role=ControlTree.role(decode(p[9]));
                n.add(new ControlTree.Node(Integer.parseInt(p[0]),Integer.parseInt(p[1]),new int[]{Integer.parseInt(p[4]),Integer.parseInt(p[5]),Integer.parseInt(p[6]),Integer.parseInt(p[7])},Boolean.parseBoolean(p[2]),role,decode(p[10]),visible));}
            var real=new ControlTree.Snapshot("captured.app",9,100,Integer.parseInt(header[0]),Integer.parseInt(header[1]),n,Boolean.parseBoolean(header[2]));
            var got=NativeControlPolicy.find(real,true);int expected=Integer.parseInt(header[3]);
            ok(expected<0?got==null:got!=null && got.target==expected,"real capture "+Paths.get(arg).getFileName()+" target="+(got==null?-1:got.target));
        }
        System.out.println("Native checks passed: "+checks);
    }
}
