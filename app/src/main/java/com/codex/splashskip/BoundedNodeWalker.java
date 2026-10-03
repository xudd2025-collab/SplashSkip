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
    }
    static final class Stats {
        int nodes,missingChildren,errors,duplicates;
        boolean timeout,nodeLimit,depthLimit;
        boolean complete(){return !timeout&&!nodeLimit&&!depthLimit&&missingChildren==0&&errors==0&&duplicates==0;}
        List<String> reasons(){
            List<String> r=new ArrayList<>();if(timeout)r.add("time_budget");if(nodeLimit)r.add("node_limit");
            if(depthLimit)r.add("depth_limit");if(missingChildren>0)r.add("unavailable_children");
            if(errors>0)r.add("provider_error");if(duplicates>0)r.add("duplicate_or_cycle");return r;
        }
    }
    private static final class Item<T>{final T node;final int parent,depth;Item(T n,int p,int d){node=n;parent=p;depth=d;}}
    static <T> Stats walk(List<T> roots,Access<T> access,int maxNodes,int maxDepth,long deadline,LongSupplier clock){
        Stats s=new Stats();ArrayDeque<Item<T>> queue=new ArrayDeque<>();Set<T> visited=new HashSet<>();List<T> opened=new ArrayList<>();
        // Ownership of all roots and child handles transfers to this walker.
        for(T root:roots)if(root!=null)queue.add(new Item<>(root,-1,0));
        try {
            while(!queue.isEmpty()){
                if(clock.getAsLong()>deadline){s.timeout=true;break;}
                if(s.nodes>=maxNodes){s.nodeLimit=true;break;}
                Item<T> item=queue.remove();T node=item.node;opened.add(node);
                try {
                    if(!access.accepts(node))continue;
                    if(!visited.add(node)){s.duplicates++;continue;}
                    int count=access.children(node),index=s.nodes;
                    access.append(node,index,item.parent,item.depth);s.nodes++;
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
