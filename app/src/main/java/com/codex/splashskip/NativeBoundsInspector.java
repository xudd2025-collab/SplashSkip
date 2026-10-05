package com.codex.splashskip;

import java.util.*;

/** Geometry and relationships for a saved diagnostic only; never authorizes an input. */
final class NativeBoundsInspector {
    static final int ALL=0, CLICKABLE=1, CONTROLS=2;
    final ControlTree.Snapshot tree;
    NativeBoundsInspector(ControlTree.Snapshot tree){this.tree=tree;}
    static boolean displayedComplete(boolean scopeOnly,boolean fullComplete,boolean scopeComplete){return scopeOnly?scopeComplete:fullComplete;}
    static List<String> scopeReasons(boolean sourceComplete,boolean archiveTruncated) {
        List<String> codes=new ArrayList<>();codes.add("scope_only");
        if(!sourceComplete)codes.add("provider_incomplete");
        if(archiveTruncated)codes.add("archive_payload_limit");
        return codes;
    }
    boolean shown(int i,int filter) {
        if(i<0 || i>=tree.nodes.size())return false;
        ControlTree.Node n=tree.nodes.get(i);
        boolean visible=n.onScreen==null?n.visible:n.onScreen;
        return visible && ControlTree.area(n.box)>0 && n.box[2]>n.box[0] && n.box[3]>n.box[1] &&
            (filter==ALL || filter==CLICKABLE && n.clickable || filter==CONTROLS && UiControlPolicy.isControl(n.role));
    }
    List<Integer> hits(float x,float y,int filter) {
        List<Integer> found=new ArrayList<>();
        for(int i=0;i<tree.nodes.size();i++) {
            int[] b=tree.nodes.get(i).box;
            if(shown(i,filter) && x>=b[0] && x<=b[2] && y>=b[1] && y<=b[3])found.add(i);
        }
        found.sort(Comparator.comparingInt((Integer i)->priority(tree.nodes.get(i)))
            .thenComparingLong(i->ControlTree.area(tree.nodes.get(i).box)).thenComparingInt(i->i));
        return found;
    }
    private static int priority(ControlTree.Node n){return UiControlPolicy.isControl(n.role)?0:n.clickable?1:2;}
    List<Integer> path(int index) {
        List<Integer> path=new ArrayList<>();
        for(int at=index;at>=0 && at<tree.nodes.size() && path.size()<=ControlTree.DEPTH;at=tree.nodes.get(at).parent) {
            if(path.contains(at))break;
            path.add(at);
        }
        return path;
    }
    int nearestClickable(int index) {
        for(int i:path(index))if(tree.nodes.get(i).clickable)return i;
        return -1;
    }
    int compactTarget(int index) {
        if(index<0 || index>=tree.nodes.size())return -1;
        List<Integer> chain=path(index);int[] b=tree.nodes.get(index).box;
        for(int d=0;d<Math.min(5,chain.size());d++) {
            int at=chain.get(d);ControlTree.Node n=tree.nodes.get(at);
            if(n.clickable)return n.visible && ControlTree.safeParent(b,n.box,tree.width,tree.height)?at:-1;
        }
        return -1;
    }
    List<Integer> children(int index) {
        List<Integer> found=new ArrayList<>();
        for(int i=0;i<tree.nodes.size();i++)if(tree.nodes.get(i).parent==index)found.add(i);
        return found;
    }
    static String result(String code) {
        switch(code) {
            case "tree-incomplete":return "控件树未读全，未通过自动点击校验";
            case "no-safe-native-control":return "未找到符合自动关闭条件的控件";
            case "diagnostic-only-no-action":return "仅记录边框，自动点击已关闭";
            case "scope-no-ad":return "未确认独立广告场景";
            case "scope-no-skip":return "广告区域内未找到明确跳过或关闭按钮";
            case "scope-navigation":return "区域包含普通页面导航，无法确认是独立广告";
            case "scope-incomplete":case "scope-capture-time":return "广告区域读取不完整或超时";
            case "scope-no-safe-target":return "找到了动作标签，未确认可操作目标";
            case "scope-ambiguous-skip":return "多个动作目标，无法确定该点哪一个";
            case "verified":return "已通过独立广告区域校验";
            case "action-accepted":case "node-accepted":return "系统已接受点击请求，实际关闭结果另行确认";
            case "native-click-accepted":return "系统已接受原生点击请求，实际关闭结果另行确认";
            case "native-click-rejected-or-changed":return "原生点击被拒绝或控件已变化";
            case "native-touch-queued":return "触摸请求已排队，尚未记录提交结果";
            case "native-touch-submitted":return "已提交触摸请求，实际关闭结果另行确认";
            case "native-touch-rejected":return "系统未接受触摸请求";
            case "native-touch-changed":case "native-touch-control-changed":case "native-touch-control-properties-changed":return "目标控件或属性已变化，停止触摸";
            case "native-touch-label-without-ad-proof":return "未通过独立广告场景校验，停止触摸";
            case "native-touch-no-current-presence":return "未确认按钮当前仍在页面上";
            case "native-touch-owner-changed":return "前台应用已变化，停止触摸";
            case "native-touch-scope-changed":return "广告区域已变化，停止触摸";
            case "native-touch-parent-changed":return "控件父级已变化，停止触摸";
            case "native-touch-covered":return "按钮被其他窗口遮挡，停止触摸";
            case "native-touch-expired-before-occlusion":case "native-touch-expired-after-occlusion":return "当前页面校验已过期，停止触摸";
            case "native-touch-worker-stopped":return "触摸执行队列已停止";
            case "tree-target-gone":return "后续扫描中目标已消失";
            default:return code.isEmpty()?"未记录执行结果":"已记录执行状态，详见原始代码";
        }
    }
    static String reason(String code) {
        switch(code) {
            case "time_budget":return "读取超时";
            case "node_limit":return "达到节点数量上限";
            case "depth_limit":return "达到层级上限";
            case "provider_incomplete":return "应用未提供完整控件";
            case "archive_payload_limit":return "记录容量限制，部分节点未保存";
            case "scope_only":return "仅保存广告区域";
            case "missing_children":return "部分子节点读取失败";
            default:return code;
        }
    }
}
