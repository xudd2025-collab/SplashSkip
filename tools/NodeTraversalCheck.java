package com.codex.splashskip;
import java.util.*;

public final class NodeTraversalCheck {
    static final class N {final int id;final List<N> children=new ArrayList<>();boolean hidden;N(int id){this.id=id;}N add(N n){children.add(n);return this;}}
    static final class A implements BoundedNodeWalker.Access<N>{
        final Map<Integer,Integer> parents=new LinkedHashMap<>();final List<Integer> released=new ArrayList<>();
        boolean transientNull;int tries;
        public boolean accepts(N n){return true;}public int children(N n){return n.children.size();}
        public N child(N n,int i){if(transientNull&&tries++==0)return null;return n.children.get(i);}
        public void append(N n,int index,int parent,int depth){parents.put(n.id,parent);}
        public void release(N n){released.add(n.id);}
    }
    static void ok(boolean value,String msg){if(!value)throw new AssertionError(msg);System.out.println("PASS "+msg);}
    public static void main(String[] args){
        N child=new N(2),hidden=new N(1).add(child);hidden.hidden=true;N root=new N(0).add(hidden);A a=new A();
        var s=BoundedNodeWalker.walk(List.of(root),a,128,48,100,()->0L);
        ok(s.complete()&&s.nodes==3&&a.parents.get(2)==1,"invisible container retains visible child and its parent");
        a=new A();a.transientNull=true;s=BoundedNodeWalker.walk(List.of(root),a,128,48,100,()->0L);
        ok(s.complete()&&s.nodes==3,"transient null child retried once");
        N missing=new N(3).add(null);a=new A();s=BoundedNodeWalker.walk(List.of(missing),a,128,48,100,()->0L);
        ok(!s.complete()&&s.missingChildren==1&&s.reasons().contains("unavailable_children"),"persistent null child never called complete");
        a=new A();s=BoundedNodeWalker.walk(List.of(root,new N(9)),a,128,48,100,()->0L);
        ok(s.complete()&&a.parents.get(9)==-1&&s.nodes==4,"independent roots keep separate parent chains");
        N chain=new N(0),at=chain;for(int i=1;i<=35;i++){N next=new N(i);at.add(next);at=next;}
        a=new A();s=BoundedNodeWalker.walk(List.of(chain),a,128,48,100,()->0L);
        ok(s.complete()&&s.nodes==36,"deep children beyond old depth limit retained");
        a=new A();s=BoundedNodeWalker.walk(List.of(chain),a,128,8,100,()->0L);
        ok(!s.complete()&&s.depthLimit,"depth limit explicitly reported");
        a=new A();s=BoundedNodeWalker.walk(List.of(root),a,2,48,100,()->0L);
        ok(!s.complete()&&s.nodeLimit&&s.nodes==2,"node cap explicitly reported");
        a=new A();s=BoundedNodeWalker.walk(List.of(root),a,128,48,0,()->1L);
        ok(!s.complete()&&s.timeout&&a.released.size()==1,"deadline releases pending native handles");
        N cycle=new N(77);cycle.add(cycle);a=new A();s=BoundedNodeWalker.walk(List.of(cycle),a,128,48,100,()->0L);
        ok(!s.complete()&&s.nodes==1&&s.duplicates==1,"cycles bounded and reported");
        var invisible=new ControlTree.Node(-1,0,new int[]{10,10,60,40},true,UiControlPolicy.CLOSE,"hidden",false);
        var tree=new ControlTree.Snapshot("test.app",1,0,1000,2000,List.of(invisible));
        ok(tree.controls(Collections.emptyMap()).isEmpty(),"retained invisible node cannot become click candidate");
        List<ControlTree.Node> n=new ArrayList<>();
        n.add(new ControlTree.Node(-1,1,new int[]{0,0,1000,2000},false,"","decor"));
        n.add(new ControlTree.Node(0,1,new int[]{0,0,1000,2000},false,"","wrapper"));
        n.add(new ControlTree.Node(1,2,new int[]{0,0,1000,2000},false,"","junction"));
        n.add(new ControlTree.Node(2,2,new int[]{0,0,1000,2000},false,"","ad"));
        n.add(new ControlTree.Node(2,1,new int[]{0,0,1000,2000},false,"","home"));
        n.add(new ControlTree.Node(3,1,new int[]{800,40,960,100},true,"","close-parent"));
        n.add(new ControlTree.Node(5,0,new int[]{830,50,930,90},false,UiControlPolicy.CLOSE,"close"));
        n.add(new ControlTree.Node(3,0,new int[]{100,1700,900,1740},false,"prompt","disclosure"));
        n.add(new ControlTree.Node(4,0,new int[]{50,1900,150,1940},true,"nav","home-tab"));
        tree=new ControlTree.Snapshot("test.app",1,0,1000,2000,n);
        ok(tree.branch(6)==3&&!tree.sameBranch(6,8),"unary decor wrappers preserve ad versus home separation");
        ok(TreeVisualLocator.closeGate(tree,tree.controls(Collections.emptyMap()),true).isEmpty(),"wrapped fullscreen ad passes current branch gate");
        n.set(8,new ControlTree.Node(3,0,new int[]{50,1900,150,1940},true,"nav","same-page-tab"));
        tree=new ControlTree.Snapshot("test.app",1,0,1000,2000,n);
        ok(!TreeVisualLocator.closeGate(tree,tree.controls(Collections.emptyMap()),true).isEmpty(),"wrapped ordinary page navigation still blocks close");
    }
}
