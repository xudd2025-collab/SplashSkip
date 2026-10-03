package com.codex.splashskip;

import java.util.*;

/** Current native semantics authorize ACTION_CLICK only, never a coordinate fallback. */
final class NativeControlPolicy {
    static final class Candidate {
        final ControlTree.Hint label;
        final int target;
        Candidate(ControlTree.Hint label,int target){this.label=label;this.target=target;}
        BilibiliVisualMatcher.Hit hit(ControlTree.Snapshot tree) {
            int[] b=label.box;
            BilibiliVisualMatcher.Hit hit=new BilibiliVisualMatcher.Hit(label.action,(b[0]+b[2])/2,(b[1]+b[3])/2,1,tree.width,tree.height)
                .withTextBounds(b[0],b[1],b[2],b[3]);
            hit.structure=label.structure;hit.treeAssisted=true;hit.nativeOnly=true;
            return hit;
        }
    }
    static Candidate find(ControlTree.Snapshot tree,boolean opening) {
        if(tree==null || !tree.complete)return null;
        Candidate result=null;
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node n=tree.nodes.get(i);
            if(!n.visible || !UiControlPolicy.isControl(n.role) || !ControlTree.small(n.box,tree.width,tree.height))continue;
            int target=target(tree,i);if(target<0)continue;
            ControlTree.Hint hint=tree.hint(i,n.role);
            if(!UiControlPolicy.CLOSE_AD.equals(n.role)) {
                if(!opening || !scene(tree,i) && !(UiControlPolicy.SKIP.equals(n.role) && compactAdGroup(tree,i)))continue;
            }
            // Duplicate label/description nodes for the same button are harmless;
            // two distinct eligible buttons are ambiguous.
            if(result!=null && (result.target!=target || !result.label.action.equals(n.role)))return null;
            if(result==null || ControlTree.area(n.box)<ControlTree.area(result.label.box))result=new Candidate(hint,target);
        }
        return result;
    }
    private static boolean scene(ControlTree.Snapshot tree,int label) {
        boolean full=tree.fullScreenBranch(label),cue=false;
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node n=tree.nodes.get(i);if(!n.visible)continue;
            boolean same=tree.sameBranch(label,i);
            if(n.role.equals("nav") && (!full || same))return false;
            if(same && (n.role.equals("ad") || n.role.equals("prompt")))cue=true;
        }
        return cue;
    }
    private static boolean compactAdGroup(ControlTree.Snapshot tree,int label) {
        // Some apps keep the home tree under the same outer branch as their splash.
        // An explicit Skip and ad label sharing a small current control group supply
        // local semantics without assuming which side of the screen they occupy.
        for(int at=tree.nodes.get(label).parent,d=0;at>=0 && at<tree.nodes.size() && d<4;d++,at=tree.nodes.get(at).parent) {
            int[] box=tree.nodes.get(at).box;
            if(!ControlTree.contains(new int[]{0,0,tree.width,tree.height},box) || ControlTree.area(box)<=0 ||
                    ControlTree.area(box)>(long)tree.width*tree.height*.12f || box[3]-box[1]>Math.min(tree.width,tree.height)*.25f)continue;
            boolean cue=false,nav=false;
            for(int i=0;i<tree.nodes.size();i++) {
                ControlTree.Node n=tree.nodes.get(i);
                if(!n.visible || !ControlTree.contains(box,n.box) || !descendant(tree,i,at))continue;
                cue|=n.role.equals("ad");nav|=n.role.equals("nav");
            }
            if(cue && !nav)return true;
        }
        return false;
    }
    private static boolean descendant(ControlTree.Snapshot tree,int i,int ancestor) {
        for(int d=0;i>=0 && i<tree.nodes.size() && d<=ControlTree.DEPTH;d++,i=tree.nodes.get(i).parent)
            if(i==ancestor)return true;
        return false;
    }
    static int target(ControlTree.Snapshot tree,int label) {
        int[] child=tree.nodes.get(label).box;
        for(int at=label,depth=0;at>=0 && at<tree.nodes.size() && depth<5;depth++,at=tree.nodes.get(at).parent) {
            ControlTree.Node n=tree.nodes.get(at);
            if(n.clickable)return n.visible && ControlTree.safeParent(child,n.box,tree.width,tree.height)?at:-1;
        }
        return -1;
    }
    static boolean matches(Candidate c,ControlTree.Snapshot tree,BilibiliVisualMatcher.Hit expected) {
        if(c==null || expected==null || !c.label.action.equals(expected.rule) || tree.width!=expected.frameWidth || tree.height!=expected.frameHeight)return false;
        int[] b=c.label.box;
        if(expected.nativeOnly)return Objects.equals(c.label.structure,expected.structure) && Arrays.equals(b,expected.textBounds);
        return expected.x>=b[0] && expected.x<b[2] && expected.y>=b[1] && expected.y<b[3];
    }
}
