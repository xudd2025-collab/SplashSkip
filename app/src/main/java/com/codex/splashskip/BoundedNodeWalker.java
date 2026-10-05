package com.codex.splashskip;
import java.util.*;
import java.util.function.LongSupplier;

/** Breadth-first native traversal. Invisible containers must not prune descendants. */
final class BoundedNodeWalker<T> {
    interface Access<T> {
        boolean accepts(T node);
        int children(T node);
        T child(T node,int index);
        void append(T node,int index,int parent,int depth);
        void release(T node);
        // Adapters must opt in with frozen properties from this traversal.
        // Equality of native IDs alone never verifies a repeated edge.
        default Object snapshotForAlias(T node){return null;}
        default boolean sameSnapshot(Object first,Object repeated){return false;}
    }
    static final class Stats {
        int nodes,missingChildren,errors,duplicates,rejected,verifiedAliases;
        final List<Integer> parents=new ArrayList<>(),uniqueChildren=new ArrayList<>(),aliasParents=new ArrayList<>();
        final Map<String,Integer> duplicateKinds=new LinkedHashMap<>();
        final List<String> duplicateEdges=new ArrayList<>();
        boolean timeout,nodeLimit,depthLimit;
        boolean complete(){return !timeout&&!nodeLimit&&!depthLimit&&missingChildren==0&&errors==0&&duplicates==0&&rejected==0;}
        List<String> reasons(){
            List<String> r=new ArrayList<>();if(timeout)r.add("time_budget");if(nodeLimit)r.add("node_limit");
            if(depthLimit)r.add("depth_limit");if(missingChildren>0)r.add("unavailable_children");
            if(errors>0)r.add("provider_error");if(duplicates>0)r.add("duplicate_or_cycle");
            if(rejected>0)r.add("rejected_nodes");return r;
        }
    }
    private static final class Item<T>{final T node;final int parent,depth;Item(T n,int p,int d){node=n;parent=p;depth=d;}}
    private static final class Seen {final int index,parent;final Object snapshot;Seen(int i,int p,Object s){index=i;parent=p;snapshot=s;}}
    private static boolean ancestor(int index,int parent,List<Integer> parents){
        for(int steps=0;parent>=0 && parent<parents.size() && steps<=parents.size();steps++,parent=parents.get(parent))
            if(parent==index)return true;
        return false;
    }
    static <T> Stats walk(List<T> roots,Access<T> access,int maxNodes,int maxDepth,long deadline,LongSupplier clock){
        Stats s=new Stats();ArrayDeque<Item<T>> queue=new ArrayDeque<>();Map<T,Seen> visited=new HashMap<>();List<T> opened=new ArrayList<>();
        // Ownership of all roots and child handles transfers to this walker.
        for(T root:roots)if(root!=null)queue.add(new Item<>(root,-1,0));
        try {
            while(!queue.isEmpty()){
                if(clock.getAsLong()>deadline){s.timeout=true;break;}
                if(s.nodes>=maxNodes){s.nodeLimit=true;break;}
                Item<T> item=queue.remove();T node=item.node;opened.add(node);
                try {
                    // A known child rejected by ownership/adapter filtering can
                    // conceal a whole subtree. Its absence is not a complete
                    // observation of the original provider tree.
                    if(!access.accepts(node)){s.rejected++;continue;}
                    Seen first=visited.get(node);
                    if(first!=null){
                        boolean alias=false,cycle=ancestor(first.index,item.parent,s.parents);
                        if(item.parent>=0 && first.parent==item.parent && !cycle && first.snapshot!=null){
                            Object repeated=access.snapshotForAlias(node);
                            alias=repeated!=null && access.sameSnapshot(first.snapshot,repeated);
                        }
                        boolean expired=clock.getAsLong()>deadline;
                        if(expired){s.timeout=true;alias=false;}
                        if(alias){s.verifiedAliases++;s.aliasParents.add(item.parent);}
                        else {
                            s.duplicates++;
                            String kind=expired?"unverified":cycle?"ancestor_cycle":
                                item.parent>=0 && first.parent>=0 && first.parent!=item.parent?"cross_parent":
                                item.parent<0 || first.parent<0 || first.snapshot==null?"unverified":"snapshot_mismatch";
                            s.duplicateKinds.put(kind,s.duplicateKinds.getOrDefault(kind,0)+1);
                            if(s.duplicateEdges.size()<16)s.duplicateEdges.add("original="+first.index+",original-parent="+first.parent+
                                ",repeated-parent="+item.parent+",reason="+kind);
                        }
                        continue;
                    }
                    int count=access.children(node),index=s.nodes;
                    Object snapshot=access.snapshotForAlias(node);
                    access.append(node,index,item.parent,item.depth);s.nodes++;
                    visited.put(node,new Seen(index,item.parent,snapshot));
                    s.parents.add(item.parent);s.uniqueChildren.add(0);
                    if(item.parent>=0)s.uniqueChildren.set(item.parent,s.uniqueChildren.get(item.parent)+1);
                    if(item.depth>=maxDepth && count>0){s.depthLimit=true;continue;}
                    for(int i=0;i<count;i++){
                        if(clock.getAsLong()>deadline){s.timeout=true;break;}
                        if(s.nodes+queue.size()>=maxNodes){s.nodeLimit=true;break;}
                        T child=null;
                        try {child=access.child(node,i);if(child==null)child=access.child(node,i);}
                        catch(RuntimeException failure){s.errors++;}
                        if(child==null){s.missingChildren++;continue;}
                        queue.add(new Item<>(child,index,item.depth+1));
                    }
                }catch(RuntimeException failure){s.errors++;}
            }
        }finally{
            // Android <=32 recycles node objects; keep their identity stable until
            // duplicate/cycle detection has finished for the entire snapshot.
            for(T node:opened)access.release(node);
            while(!queue.isEmpty())access.release(queue.remove().node);
        }
        return s;
    }
}
