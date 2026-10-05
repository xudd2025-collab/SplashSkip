package com.codex.splashskip;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Policy negatives and optional complete native captures exported as frozen TSV rows. */
public final class BilibiliNativeCheck {
    private static int checked;
    private static final class Frame {
        final ControlTree.Snapshot tree;final Map<Integer,String> labels;
        Frame(ControlTree.Snapshot tree,Map<Integer,String> labels){this.tree=tree;this.labels=new HashMap<>(labels);}
    }
    private static void ok(boolean passed,String name){if(!passed)throw new AssertionError(name);checked++;System.out.println("PASS "+name);}
    private static ControlTree.Node node(int parent,int[] box,boolean clickable,String role) {
        return new ControlTree.Node(parent,0,box,clickable,role,"current-control",true);
    }
    private static Frame frame(List<ControlTree.Node> nodes,Map<Integer,String> labels,int width,int height,String pkg,boolean complete) {
        int[] children=new int[nodes.size()];for(ControlTree.Node node:nodes)if(node.parent>=0 && node.parent<nodes.size())children[node.parent]++;
        List<ControlTree.Node> counted=new ArrayList<>();
        for(int i=0;i<nodes.size();i++){ControlTree.Node n=nodes.get(i);counted.add(new ControlTree.Node(n.parent,children[i],n.box,n.clickable,n.role,n.identity,n.visible));}
        return new Frame(new ControlTree.Snapshot(pkg,3,100,width,height,counted,complete),labels);
    }
    private static Frame sample() {
        List<ControlTree.Node> n=new ArrayList<>();
        n.add(node(-1,new int[]{0,0,1200,2600},false,""));
        n.add(node(0,new int[]{0,0,1200,2600},false,""));
        n.add(node(0,new int[]{0,800,1200,2540},false,""));
        n.add(node(2,new int[]{0,800,1200,1040},false,""));
        n.add(node(2,new int[]{0,800,1200,2540},false,""));
        n.add(node(3,new int[]{1030,840,1200,1010},true,""));
        n.add(node(3,new int[]{880,840,1030,1010},true,""));
        n.add(node(4,new int[]{0,1800,160,1990},false,""));
        n.add(node(5,new int[]{1080,885,1160,965},false,""));
        n.add(node(6,new int[]{922,885,1002,965},false,""));
        n.add(node(7,new int[]{50,1880,108,1918},false,"ad"));
        n.add(node(1,new int[]{250,940,315,980},false,"ad"));
        Map<Integer,String> labels=new HashMap<>();labels.put(8,BilibiliNativePolicy.PAUSE_CLOSE);labels.put(9,BilibiliNativePolicy.MENU);labels.put(10,"广告");labels.put(11,"广告");
        return frame(n,labels,1200,2600,BilibiliNativePolicy.PACKAGE,true);
    }
    private static BilibiliNativePolicy.Candidate find(Frame f){return BilibiliNativePolicy.find(f.tree,f.labels);}
    private static boolean candidate(Frame f,int label,int target,int panel,int ad,int menu) {
        BilibiliNativePolicy.Candidate c=find(f);return c!=null && c.labelIndex==label && c.targetIndex==target && c.panelIndex==panel && c.adIndex==ad && c.menuIndex==menu;
    }
    private static Frame label(Frame f,int index,String value) {
        Map<Integer,String> labels=new HashMap<>(f.labels);if(value==null)labels.remove(index);else labels.put(index,value);return new Frame(f.tree,labels);
    }
    private static Frame replace(Frame f,int index,int parent,int[] box,boolean clickable,String role,boolean visible) {
        List<ControlTree.Node> nodes=new ArrayList<>(f.tree.nodes);ControlTree.Node old=nodes.get(index);
        nodes.set(index,new ControlTree.Node(parent,old.children,box,clickable,role,old.identity,visible));
        return frame(nodes,f.labels,f.tree.width,f.tree.height,f.tree.pkg,f.tree.complete);
    }
    private static Frame flags(Frame f,String pkg,int width,int height,boolean complete) {
        return new Frame(new ControlTree.Snapshot(pkg,3,100,width,height,f.tree.nodes,complete),f.labels);
    }
    private static Frame scaled(Frame f,int numerator,int denominator) {
        List<ControlTree.Node> nodes=new ArrayList<>();for(ControlTree.Node n:f.tree.nodes){int[] b=n.box.clone();for(int i=0;i<4;i++)b[i]=b[i]*numerator/denominator;nodes.add(new ControlTree.Node(n.parent,n.children,b,n.clickable,n.role,n.identity,n.visible));}
        return frame(nodes,f.labels,f.tree.width*numerator/denominator,f.tree.height*numerator/denominator,f.tree.pkg,true);
    }
    public static void main(String[] args) throws Exception {
        Frame f=sample();ok(candidate(f,8,5,2,10,9),"local pause-ad sheet chooses Close parent, never menu");
        ok(candidate(scaled(f,9,10),8,5,2,10,9),"proportions support another portrait resolution");
        Frame moved=f;for(int i=2;i<=10;i++){ControlTree.Node n=moved.tree.nodes.get(i);int[] b=n.box.clone();b[1]+=60;b[3]+=60;moved=replace(moved,i,n.parent,b,n.clickable,n.role,n.visible);}ok(candidate(moved,8,5,2,10,9),"current panel can move vertically");
        ok(candidate(label(label(f,3,"another advertiser"),9," 不感兴趣 "),8,5,2,10,9),"advertiser and trimmed menu do not change proof");
        ok(find(flags(f,"another.app",1200,2600,true))==null,"foreign package rejected");
        ok(find(flags(f,f.tree.pkg,2600,1200,true))==null,"landscape rejected");
        ok(find(flags(f,f.tree.pkg,1200,2600,false))==null,"incomplete tree rejected");
        ok(find(label(f,8,"关闭"))==null,"ordinary Close is not Pause Close");
        ok(find(label(f,8,"关闭弹幕"))==null,"danmaku control is not Pause Close");
        ok(find(label(f,8,"关闭暂停页面"))==null,"inexact description rejected");
        ok(find(label(f,10,null))==null,"missing local ad cannot borrow background ad");
        ok(find(label(f,10,"推荐"))==null,"non-ad label rejected");
        ok(find(label(f,9,null))==null,"missing menu rejected");
        ok(find(label(f,9,"更多"))==null,"unidentified menu rejected");
        ok(find(replace(f,10,1,f.tree.nodes.get(10).box,false,"ad",true))==null,"ad in another branch rejected");
        ok(find(replace(f,10,3,f.tree.nodes.get(10).box,false,"ad",true))==null,"header ad cannot substitute for body evidence");
        ok(find(replace(f,9,1,f.tree.nodes.get(9).box,false,"",true))==null,"menu in another branch rejected");
        ok(find(replace(f,5,3,f.tree.nodes.get(2).box,true,"",true))==null,"large clickable parent rejected");
        ok(find(replace(f,5,3,f.tree.nodes.get(5).box,false,"",true))==null,"nonclickable parent rejected");
        ok(find(replace(f,8,5,f.tree.nodes.get(8).box,false,"",false))==null,"hidden or disabled close rejected");
        ok(find(replace(f,5,3,f.tree.nodes.get(5).box,true,"",false))==null,"hidden or disabled target rejected");
        ok(find(replace(f,3,2,f.tree.nodes.get(3).box,false,"",false))==null,"hidden or disabled header rejected");
        ok(find(replace(f,2,0,f.tree.nodes.get(2).box,false,"",false))==null,"hidden or disabled panel rejected");
        ok(find(replace(f,10,7,f.tree.nodes.get(10).box,false,"ad",false))==null,"hidden or disabled ad rejected");
        ok(find(replace(f,9,6,f.tree.nodes.get(9).box,false,"",false))==null,"hidden or disabled menu rejected");
        ok(find(replace(f,2,0,new int[]{0,0,1200,2600},false,"",true))==null,"fullscreen container rejected");
        ok(find(replace(f,2,0,new int[]{700,800,1200,2540},false,"",true))==null,"narrow page fragment rejected");
        ok(find(replace(f,10,7,new int[]{50,2650,108,2688},false,"ad",true))==null,"out-of-frame ad rejected");
        ok(find(replace(f,4,2,f.tree.nodes.get(4).box,false,"nav",true))==null,"navigation inside panel rejected");
        List<ControlTree.Node> doubled=new ArrayList<>(f.tree.nodes);doubled.add(node(3,new int[]{1030,830,1200,1000},true,""));doubled.add(node(12,new int[]{1080,875,1160,955},false,""));Map<Integer,String> doubledLabels=new HashMap<>(f.labels);doubledLabels.put(13,BilibiliNativePolicy.PAUSE_CLOSE);
        ok(find(frame(doubled,doubledLabels,1200,2600,f.tree.pkg,true))==null,"two distinct current Close targets rejected");
        List<ControlTree.Node> aliases=new ArrayList<>(f.tree.nodes);aliases.add(node(5,f.tree.nodes.get(8).box,false,""));Map<Integer,String> aliasLabels=new HashMap<>(f.labels);aliasLabels.put(12,BilibiliNativePolicy.PAUSE_CLOSE);
        ok(candidate(frame(aliases,aliasLabels,1200,2600,f.tree.pkg,true),8,5,2,10,9),"duplicate label for same target is harmless");
        List<ControlTree.Node> broken=new ArrayList<>(f.tree.nodes);ControlTree.Node root=broken.get(0);broken.set(0,new ControlTree.Node(-1,root.children+1,root.box,false,"",root.identity,true));
        ok(find(new Frame(new ControlTree.Snapshot(f.tree.pkg,3,100,1200,2600,broken,true),f.labels))==null,"declared child count mismatch rejected");
        ok(find(replace(f,6,9,f.tree.nodes.get(6).box,true,"",true))==null,"cycle or reversed parent order rejected");
        ok(BilibiliNativePolicy.find(null,f.labels)==null,"unavailable tree rejected");ok(BilibiliNativePolicy.find(f.tree,null)==null,"unavailable frozen labels rejected");
        for(String path:args)replay(path);
        System.out.println("Bilibili native checks: "+checked+" passed");
    }
    private static String decode(String value){return new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8);}
    private static void replay(String path) throws Exception {
        List<String> rows=Files.readAllLines(Paths.get(path),StandardCharsets.UTF_8);String[] header=rows.remove(0).split("\t",-1);
        if(header.length!=9)throw new IllegalArgumentException("Replay header needs package, width, height, complete and five expected indices");
        List<ControlTree.Node> nodes=new ArrayList<>();Map<Integer,String> labels=new HashMap<>();
        for(String row:rows){
            String[] p=row.split("\t",-1);if(p.length!=12)throw new IllegalArgumentException("Invalid raw-native replay row");int index=nodes.size();
            boolean visible=Boolean.parseBoolean(p[3]);String role=visible?ControlTree.role(decode(p[8])):"";
            if(visible && role.isEmpty())role=ControlTree.role(decode(p[9]));
            nodes.add(new ControlTree.Node(Integer.parseInt(p[0]),Integer.parseInt(p[1]),new int[]{Integer.parseInt(p[4]),Integer.parseInt(p[5]),Integer.parseInt(p[6]),Integer.parseInt(p[7])},Boolean.parseBoolean(p[2]),role,decode(p[10]),visible));
            String label=decode(p[11]);if(!label.isEmpty())labels.put(index,label);
        }
        Frame actual=new Frame(new ControlTree.Snapshot(header[0],3,100,Integer.parseInt(header[1]),Integer.parseInt(header[2]),nodes,Boolean.parseBoolean(header[3])),labels);
        int[] expected=new int[5];for(int i=0;i<5;i++)expected[i]=Integer.parseInt(header[i+4]);
        ok(candidate(actual,expected[0],expected[1],expected[2],expected[3],expected[4]),"actual "+nodes.size()+"-node capture "+Paths.get(path).getFileName()+" indices="+Arrays.toString(expected));
    }
}
