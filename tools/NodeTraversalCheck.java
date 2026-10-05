package com.codex.splashskip;
import java.util.*;

public final class NodeTraversalCheck {
    static final class N {
        final int id;final List<N> children=new ArrayList<>();
        String pkg="test.app",type="View",viewId="",text="",description="";
        int window=9;int[] bounds={10,10,60,40};boolean hidden,enabled=true,clickable;
        N(int id){this.id=id;}N add(N n){children.add(n);return this;}
        @Override public boolean equals(Object other){return other instanceof N && id==((N)other).id;}
        @Override public int hashCode(){return id;}
    }
    static class A implements BoundedNodeWalker.Access<N>{
        final Map<Integer,Integer> parents=new LinkedHashMap<>();final List<Integer> released=new ArrayList<>();
        boolean transientNull;int tries,rejectedId=-1;
        public boolean accepts(N n){return n.id!=rejectedId;}public int children(N n){return n.children.size();}
        public N child(N n,int i){if(transientNull&&tries++==0)return null;return n.children.get(i);}
        public void append(N n,int index,int parent,int depth){parents.put(n.id,parent);}
        public void release(N n){released.add(n.id);}
    }
    static class CheckedA extends A {
        public Object snapshotForAlias(N n){return new Object[]{n.pkg,n.type,n.viewId,n.text,n.description,n.window,
            n.bounds.clone(),n.children.size(),n.hidden,n.enabled,n.clickable};}
        public boolean sameSnapshot(Object first,Object repeated){return Arrays.deepEquals((Object[])first,(Object[])repeated);}
    }
    static void ok(boolean value,String msg){if(!value)throw new AssertionError(msg);System.out.println("PASS "+msg);}
    static void unknownEvidence(BoundedNodeWalker.Stats stats,String message){
        var root=new ControlTree.Node(-1,1,new int[]{0,0,1000,2000},false,"","original-root");
        var close=new ControlTree.Node(0,0,new int[]{800,40,960,100},true,UiControlPolicy.CLOSE_AD,"original-close");
        var original=new ControlTree.Snapshot("test.app",1,1000,1000,2000,List.of(root,close));
        var observer=new NativeTreeObservation("test.app",1,1000,NativeControlPolicy.find(original,false).hit(original));
        ok(!stats.complete() &&
            NativeControlPolicy.find(new ControlTree.Snapshot("test.app",1,1120,1000,2000,List.of(root,close),stats.complete()),false)==null &&
            observer.observe(new ControlTree.Snapshot("test.app",1,1120,1000,2000,List.of(root),stats.complete()),1120)==NativeTreeObservation.UNKNOWN &&
            observer.observe(new ControlTree.Snapshot("test.app",1,1240,1000,2000,List.of(root),stats.complete()),1240)==NativeTreeObservation.UNKNOWN,message);
    }
    static void aliasChecks(){
        N first=new N(101),repeated=new N(101),root=new N(100).add(first).add(repeated);A a=new A();
        var s=BoundedNodeWalker.walk(List.of(root),a,128,48,100,()->0L);
        ok(!s.complete()&&s.duplicates==1&&s.verifiedAliases==0,"adapter without snapshot verification rejects same-parent native identity");
        unknownEvidence(s,"unverified duplicate never authorizes click or disappearance");
        CheckedA checked=new CheckedA();s=BoundedNodeWalker.walk(List.of(root),checked,128,48,100,()->0L);
        ok(s.complete()&&s.nodes==2&&s.duplicates==0&&s.verifiedAliases==1&&s.aliasParents.equals(List.of(0)),
            "same-parent identical frozen snapshots verify one alias and its parent");
        ok(s.uniqueChildren.equals(List.of(1,0))&&checked.released.size()==3,
            "verified alias keeps one unique child while releasing both obtained handles");
        var normalized=List.of(new ControlTree.Node(-1,s.uniqueChildren.get(0),new int[]{0,0,1000,2000},false,"","root"),
            new ControlTree.Node(0,s.uniqueChildren.get(1),new int[]{0,0,1000,2000},false,"","wrapper"));
        ok(new ControlTree.Snapshot("test.app",1,0,1000,2000,normalized).branch(1)==0,
            "verified duplicate edge cannot fabricate a branch in normalized control geometry");

        first=new N(103).add(new N(104));repeated=new N(103).add(new N(104));root=new N(102).add(first).add(repeated);
        checked=new CheckedA();s=BoundedNodeWalker.walk(List.of(root),checked,128,48,100,()->0L);
        ok(s.complete()&&s.nodes==3&&s.verifiedAliases==1&&s.uniqueChildren.equals(List.of(1,1,0)),
            "verified non-leaf alias expands its current descendants once");

        root=new N(110).add(new N(111).add(new N(113))).add(new N(112).add(new N(113)));
        s=BoundedNodeWalker.walk(List.of(root),new CheckedA(),128,48,100,()->0L);
        ok(!s.complete()&&s.duplicates==1&&s.verifiedAliases==0,"identical source under different parents remains an invalid shared node");
        unknownEvidence(s,"cross-parent sharing never authorizes click or disappearance");
        N cycle=new N(120);cycle.add(cycle);
        s=BoundedNodeWalker.walk(List.of(cycle),new CheckedA(),128,48,100,()->0L);
        ok(!s.complete()&&s.duplicates==1&&s.verifiedAliases==0,"verified properties cannot exempt a self-cycle");
        unknownEvidence(s,"ancestor cycle never authorizes click or disappearance");
        N ancestor=new N(122);ancestor.add(new N(123).add(ancestor));
        s=BoundedNodeWalker.walk(List.of(ancestor),new CheckedA(),128,48,100,()->0L);
        ok(!s.complete()&&s.duplicates==1&&s.verifiedAliases==0,"verified properties cannot exempt a descendant-to-ancestor cycle");
        N duplicateRoot=new N(124);
        s=BoundedNodeWalker.walk(List.of(duplicateRoot,new N(124)),new CheckedA(),128,48,100,()->0L);
        ok(!s.complete()&&s.duplicates==1&&s.verifiedAliases==0,"two equal forest roots do not have a validated common parent");

        List<java.util.function.Consumer<N>> changes=List.of(n->n.pkg="other.app",n->n.type="OtherView",n->n.viewId="different",
            n->n.text="different",n->n.description="different",n->n.window=10,n->n.bounds[0]++,n->n.add(new N(133)),
            n->n.hidden=true,n->n.enabled=false,n->n.clickable=true,n->n.text=null);
        for(int i=0;i<changes.size();i++){
            first=new N(131);repeated=new N(131);changes.get(i).accept(repeated);root=new N(130).add(first).add(repeated);
            s=BoundedNodeWalker.walk(List.of(root),new CheckedA(),128,48,100,()->0L);
            if(s.complete() || s.duplicates!=1 || s.verifiedAliases!=0)throw new AssertionError("changed alias property accepted: "+i);
        }
        ok(true,"package class ID text description window bounds children visibility enabled and clickable changes reject aliases");
        unknownEvidence(s,"changed snapshot properties never authorize click or disappearance");

        N mutable=new N(141);root=new N(140).add(mutable).add(mutable);
        checked=new CheckedA(){@Override public void append(N n,int index,int parent,int depth){super.append(n,index,parent,depth);if(n.id==141)n.text="changed after capture";}};
        s=BoundedNodeWalker.walk(List.of(root),checked,128,48,100,()->0L);
        ok(!s.complete()&&s.duplicates==1&&s.verifiedAliases==0,"mutating the same handle cannot rewrite its frozen first snapshot");

        root=new N(150).add(new N(151)).add(new N(151)).add(null);
        s=BoundedNodeWalker.walk(List.of(root),new CheckedA(),128,48,100,()->0L);
        ok(!s.complete()&&s.verifiedAliases==1&&s.missingChildren==1,"one verified alias cannot conceal a separate unavailable child");
        unknownEvidence(s,"verified aliases plus unavailable children remain unknown for click and disappearance");
    }
    public static void main(String[] args){
        aliasChecks();
        N child=new N(2),hidden=new N(1).add(child);hidden.hidden=true;N root=new N(0).add(hidden);A a=new A();
        var s=BoundedNodeWalker.walk(List.of(root),a,128,48,100,()->0L);
        ok(s.complete()&&s.nodes==3&&a.parents.get(2)==1,"invisible container retains visible child and its parent");
        a=new A();a.transientNull=true;s=BoundedNodeWalker.walk(List.of(root),a,128,48,100,()->0L);
        ok(s.complete()&&s.nodes==3,"transient null child retried once");
        N missing=new N(3).add(null);a=new A();s=BoundedNodeWalker.walk(List.of(missing),a,128,48,100,()->0L);
        ok(!s.complete()&&s.missingChildren==1&&s.reasons().contains("unavailable_children"),"persistent null child never called complete");
        N rejectedContainer=new N(12).add(new N(13)),retained=new N(14),mixed=new N(11).add(rejectedContainer).add(retained);
        a=new A();a.rejectedId=12;s=BoundedNodeWalker.walk(List.of(mixed),a,128,48,100,()->0L);
        ok(!s.complete()&&s.rejected==1&&s.reasons().contains("rejected_nodes")&&s.nodes==2&&!a.parents.containsKey(13),
            "rejected container concealing a child makes retained tree incomplete");
        ok(a.released.size()==3&&a.released.contains(12)&&!a.released.contains(13),
            "rejected handle released once without taking ownership of hidden descendants");
        var close=new ControlTree.Node(0,0,new int[]{800,40,960,100},true,UiControlPolicy.CLOSE_AD,"original-close");
        var visibleRoot=new ControlTree.Node(-1,1,new int[]{0,0,1000,2000},false,"","original-root");
        var original=new ControlTree.Snapshot("test.app",1,1000,1000,2000,List.of(visibleRoot,close));
        var originalHit=NativeControlPolicy.find(original,false).hit(original);
        var observer=new NativeTreeObservation("test.app",1,1000,originalHit);
        ok(NativeControlPolicy.find(new ControlTree.Snapshot("test.app",1,1120,1000,2000,List.of(visibleRoot,close),s.complete()),false)==null,
            "retained close cannot authorize action when another provider child was rejected");
        ok(observer.observe(new ControlTree.Snapshot("test.app",1,1120,1000,2000,List.of(visibleRoot),s.complete()),1120)==NativeTreeObservation.UNKNOWN&&
            observer.observe(new ControlTree.Snapshot("test.app",1,1240,1000,2000,List.of(visibleRoot),s.complete()),1240)==NativeTreeObservation.UNKNOWN,
            "two trees missing a rejected subtree cannot verify original control disappeared");
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
