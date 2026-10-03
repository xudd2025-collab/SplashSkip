package com.codex.splashskip;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;

/** Bounded Android adapter. Text is reduced to semantic roles before leaving the current frame. */
final class AccessibilityControlTree {
    static final class Live implements AutoCloseable {
        ControlTree.Snapshot tree;
        final List<AccessibilityNodeInfo> handles=new ArrayList<>();
        public void close(){for(AccessibilityNodeInfo n:handles)n.recycle();handles.clear();}
    }
    static Live captureLive(AccessibilityNodeInfo root,String pkg,long epoch,int w,int h,long deadline) {
        Live live=new Live();
        try{live.tree=capture(root,pkg,epoch,w,h,deadline,live.handles);return live;}
        catch(RuntimeException error){live.close();throw error;}
    }
    static ControlTree.Snapshot capture(AccessibilityNodeInfo root,String pkg,long epoch,int w,int h) {
        return capture(root,pkg,epoch,w,h,SystemClock.uptimeMillis()+200,null);
    }
    private static ControlTree.Snapshot capture(AccessibilityNodeInfo root,String pkg,long epoch,int w,int h,long deadline,List<AccessibilityNodeInfo> handles) {
        long now=SystemClock.uptimeMillis();List<ControlTree.Node> nodes=new ArrayList<>();
        if(root==null)return new ControlTree.Snapshot(pkg,epoch,now,w,h,nodes,false);
        BoundedNodeWalker.Stats stats=BoundedNodeWalker.walk(Collections.singletonList(AccessibilityNodeInfo.obtain(root)),
            new BoundedNodeWalker.Access<AccessibilityNodeInfo>() {
                public boolean accepts(AccessibilityNodeInfo n){return pkg.contentEquals(value(n.getPackageName()));}
                public int children(AccessibilityNodeInfo n){return n.getChildCount();}
                public AccessibilityNodeInfo child(AccessibilityNodeInfo n,int i){return fetchChild(n,i);}
                public void release(AccessibilityNodeInfo n){n.recycle();}
                public void append(AccessibilityNodeInfo n,int index,int parent,int depth){
                    boolean visible=n.isVisibleToUser();String role=visible?role(value(n.getText())):"";
                    if(visible&&role.isEmpty())role=role(value(n.getContentDescription()));
                    Rect b=new Rect();n.getBoundsInScreen(b);
                    String identity=JointControlModel.digest(value(n.getClassName())+"/"+value(n.getViewIdResourceName()));
                    nodes.add(new ControlTree.Node(parent,n.getChildCount(),new int[]{b.left,b.top,b.right,b.bottom},visible&&n.isEnabled()&&n.isClickable(),role,identity,visible&&n.isEnabled()));
                    if(handles!=null)handles.add(AccessibilityNodeInfo.obtain(n));
                }
            },ControlTree.LIMIT,ControlTree.DEPTH,deadline,SystemClock::uptimeMillis);
        return new ControlTree.Snapshot(pkg,epoch,now,w,h,nodes,stats.complete()&&!nodes.isEmpty());
    }
    static AccessibilityNodeInfo fetchChild(AccessibilityNodeInfo n,int index){
        if(android.os.Build.VERSION.SDK_INT>=33)return n.getChild(index,AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST);
        return n.getChild(index);
    }
    static String role(String text) {
        return ControlTree.role(text);
    }
    private static String value(CharSequence s){return s==null?"":s.toString();}
}
