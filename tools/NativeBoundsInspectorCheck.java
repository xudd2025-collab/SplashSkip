package com.codex.splashskip;

import java.util.*;

/** Saved diagnostics must distinguish leaf labels, click parents and incomplete data. */
public final class NativeBoundsInspectorCheck {
    private static int checks;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    private static ControlTree.Node node(int parent,int[] bounds,boolean clickable,String role){return new ControlTree.Node(parent,0,bounds,clickable,role,"",true);}
    private static NativeBoundsInspector model(ControlTree.Node... nodes){return new NativeBoundsInspector(new ControlTree.Snapshot("test",1,1,1000,2000,Arrays.asList(nodes),false));}
    public static void main(String[] args) {
        NativeBoundsInspector m=model(node(-1,new int[]{0,0,1000,2000},true,""),
            node(0,new int[]{750,20,950,100},true,""),node(1,new int[]{775,40,925,80},false,UiControlPolicy.SKIP),
            node(1,new int[]{775,40,925,80},false,""));
        check(m.hits(800,50,0).equals(Arrays.asList(2,1,0,3)),"semantic controls then click targets before plain labels");
        check(m.hits(800,50,1).equals(Arrays.asList(1,0)),"clickable filter exposes real clickable parent");
        check(m.hits(800,50,2).equals(Arrays.asList(2)),"control filter retains unclickable skip label");
        check(m.nearestClickable(2)==1 && m.compactTarget(2)==1,"leaf can use small clickable parent");
        check(m.path(2).equals(Arrays.asList(2,1,0)),"saved parent chain remains navigable");
        check(m.children(1).equals(Arrays.asList(2,3)),"overlapping siblings can be selected separately");
        check(m.compactTarget(0)==-1,"large clickable container not a small action target");
        check(m.hits(-2,50,0).isEmpty(),"outside screen hits none");
        check(m.nearestClickable(-1)==-1 && m.compactTarget(99)==-1,"invalid indices handled");
        NativeBoundsInspector partial=model(node(8,new int[]{10,20,30,40},false,UiControlPolicy.SKIP));
        check(partial.path(0).equals(Arrays.asList(0)) && partial.nearestClickable(0)==-1,"missing ancestor stays unknown");
        NativeBoundsInspector cycle=model(node(1,new int[]{0,0,100,100},false,""),node(0,new int[]{0,0,100,100},false,""));
        check(cycle.path(0).equals(Arrays.asList(0,1)),"malformed cycle cannot loop");
        ControlTree.Node disabled=new ControlTree.Node(-1,0,new int[]{10,10,30,30},false,"","",false,"","",false,false,true,true);
        NativeBoundsInspector disabledModel=model(disabled);
        check(disabledModel.shown(0,0) && !disabledModel.shown(0,1),"visible disabled node shown without implying effective clickability");
        check(m.tree.nodes.get(0).enabled==null && m.tree.nodes.get(0).declaredClickable==null,"old metadata stays unknown");
        check(!model(node(-1,new int[]{0,0,0,5},true,"")).shown(0,0),"empty bounds not selectable");
        check(NativeBoundsInspector.result("tree-incomplete").contains("未读全"),"incomplete scan explained in Chinese");
        check(NativeBoundsInspector.result("future-result").contains("原始代码"),"unknown execution not invented");
        check(!NativeBoundsInspector.displayedComplete(false,false,true),"complete ad scope cannot make partial global tree complete");
        check(NativeBoundsInspector.displayedComplete(true,false,true),"complete scope-only archive correctly described");
        check(NativeBoundsInspector.scopeReasons(true,true).equals(Arrays.asList("scope_only","archive_payload_limit")),"archive truncation not confused with provider failure");
        check(NativeBoundsInspector.result("native-touch-queued").contains("排队") && !NativeBoundsInspector.result("native-touch-queued").contains("已提交"),"queued request not reported as executed");
        System.out.println("Native bounds inspector checks: "+checks);
    }
}
