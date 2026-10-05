package com.codex.splashskip;

import java.util.Map;

/** A current Bilibili pause-ad sheet. Frozen labels supply semantics, never saved points. */
final class BilibiliNativePolicy {
    static final String PACKAGE="tv.danmaku.bili";
    static final String PAUSE_CLOSE="关闭暂停页", MENU="不感兴趣";
    private static final String IMAGE_VIEW=JointControlModel.digest("android.widget.ImageView/");
    static final class Candidate {
        final int labelIndex,targetIndex,panelIndex,adIndex,menuIndex;
        final String adLabel;
        final int imageIndex;
        Candidate(int label,int target,int panel,int ad,int menu) {
            this(label,target,panel,ad,menu,"广告",-1);
        }
        Candidate(int label,int target,int panel,int ad,int menu,String adLabel,int image) {
            labelIndex=label;targetIndex=target;panelIndex=panel;adIndex=ad;menuIndex=menu;
            this.adLabel=adLabel;imageIndex=image;
        }
    }
    static Candidate find(ControlTree.Snapshot tree,Map<Integer,String> labels) {
        if(tree==null || labels==null || !PACKAGE.equals(tree.pkg) || tree.height<=tree.width || !completeTopology(tree))return null;
        Candidate selected=null;
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node label=tree.nodes.get(i);
            if(!label.visible || !PAUSE_CLOSE.equals(label(labels,i)))continue;
            Candidate current=inspect(tree,labels,i);
            // Another visible Pause Close with an unknown context also makes selection ambiguous.
            if(current==null)return null;
            if(selected!=null && (selected.targetIndex!=current.targetIndex || selected.panelIndex!=current.panelIndex ||
                    selected.adIndex!=current.adIndex || selected.menuIndex!=current.menuIndex ||
                    !selected.adLabel.equals(current.adLabel) || selected.imageIndex!=current.imageIndex))return null;
            if(selected==null || ControlTree.area(label.box)<ControlTree.area(tree.nodes.get(selected.labelIndex).box))selected=current;
        }
        return selected;
    }
    private static Candidate inspect(ControlTree.Snapshot tree,Map<Integer,String> labels,int labelIndex) {
        ControlTree.Node close=tree.nodes.get(labelIndex);
        int targetIndex=close.parent;if(targetIndex<0)return null;
        ControlTree.Node target=tree.nodes.get(targetIndex);
        int headerIndex=target.parent;if(headerIndex<0)return null;
        ControlTree.Node header=tree.nodes.get(headerIndex);
        int panelIndex=header.parent;if(panelIndex<0)return null;
        ControlTree.Node panel=tree.nodes.get(panelIndex);
        if(!usable(close,tree) || close.children!=0 || !smallLabel(close.box,tree) ||
                !usable(target,tree) || !target.clickable || !ControlTree.safeParent(close.box,target.box,tree.width,tree.height) ||
                !usable(header,tree) || header.clickable || !usable(panel,tree) || panel.clickable ||
                !panelBox(panel.box,tree) || !ControlTree.contains(panel.box,header.box) ||
                !ControlTree.contains(header.box,target.box))return null;
        int panelWidth=panel.box[2]-panel.box[0],panelHeight=panel.box[3]-panel.box[1];
        if(header.box[2]-header.box[0]<panelWidth*.8f || header.box[3]-header.box[1]>tree.width*.24f ||
                header.box[1]-panel.box[1]>panelHeight*.1f ||
                centerX(close.box)<panel.box[2]-panelWidth*.18f)return null;
        for(int i=0;i<tree.nodes.size();i++)
            if(tree.nodes.get(i).visible && "nav".equals(tree.nodes.get(i).role) && branchUnder(tree,i,panelIndex)>=0)return null;
        int menuIndex=-1;
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node menu=tree.nodes.get(i);
            if(!menu.visible || !MENU.equals(label(labels,i)) || menu.parent<0)continue;
            ControlTree.Node menuTarget=tree.nodes.get(menu.parent);
            if(menuTarget.parent!=headerIndex)continue;
            if(!usable(menu,tree) || menu.children!=0 || !smallLabel(menu.box,tree) ||
                    menu.parent==targetIndex || !usable(menuTarget,tree) || !menuTarget.clickable ||
                    !ControlTree.safeParent(menu.box,menuTarget.box,tree.width,tree.height) ||
                    !ControlTree.contains(header.box,menuTarget.box) || menu.box[2]>close.box[0] ||
                    centerX(close.box)-centerX(menu.box)>panelWidth*.3f ||
                    Math.abs(centerY(close.box)-centerY(menu.box))>Math.max(close.box[3]-close.box[1],menu.box[3]-menu.box[1]))return null;
            if(menuIndex>=0)return null;
            menuIndex=i;
        }
        if(menuIndex<0)return null;
        int adIndex=-1;
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node ad=tree.nodes.get(i);
            if(!ad.visible || !"ad".equals(ad.role) || !"广告".equals(label(labels,i)))continue;
            int bodyIndex=branchUnder(tree,i,panelIndex);
            if(bodyIndex<0 || bodyIndex==headerIndex)continue;
            ControlTree.Node body=tree.nodes.get(bodyIndex);
            if(!usable(ad,tree) || ad.children!=0 || !usable(body,tree) ||
                    body.box[2]-body.box[0]<panelWidth*.8f || body.box[3]-body.box[1]<panelHeight*.45f ||
                    !ControlTree.contains(panel.box,body.box) || ad.box[1]<header.box[3] ||
                    !containedPath(tree,i,panelIndex))continue;
            if(adIndex>=0)return null;
            adIndex=i;
        }
        if(adIndex>=0)return new Candidate(labelIndex,targetIndex,panelIndex,adIndex,menuIndex);
        return promotion(tree,labels,labelIndex,targetIndex,headerIndex,panelIndex,menuIndex);
    }
    /** Observed image-only body plus a separate six-child promotion strip in this same panel. */
    private static Candidate promotion(ControlTree.Snapshot tree,Map<Integer,String> labels,int closeIndex,int targetIndex,
            int headerIndex,int panelIndex,int menuIndex) {
        ControlTree.Node panel=tree.nodes.get(panelIndex);
        int panelWidth=panel.box[2]-panel.box[0],panelHeight=panel.box[3]-panel.box[1];
        int imageIndex=-1,bodyIndex=-1;
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node image=tree.nodes.get(i);
            if(!IMAGE_VIEW.equals(image.identity) || !usable(image,tree) || image.clickable || image.children!=0 ||
                    !"".equals(image.role) || !label(labels,i).isEmpty() || image.parent<0)continue;
            ControlTree.Node media=tree.nodes.get(image.parent);
            if(media.parent<0)continue;
            ControlTree.Node body=tree.nodes.get(media.parent);
            if(body.parent!=panelIndex || media.parent==headerIndex || !usable(media,tree) || media.clickable || media.children!=1 ||
                    !usable(body,tree) || body.clickable || body.children!=1 ||
                    body.box[2]-body.box[0]<panelWidth*.8f || body.box[3]-body.box[1]<panelHeight*.45f ||
                    image.box[2]-image.box[0]<panelWidth*.75f || image.box[3]-image.box[1]<panelHeight*.4f ||
                    !containedPath(tree,i,panelIndex))continue;
            if(imageIndex>=0)return null;
            imageIndex=i;bodyIndex=media.parent;
        }
        if(imageIndex<0)return null;
        int ctaIndex=-1;
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node text=tree.nodes.get(i);
            if(!text.visible || !"查看详情".equals(label(labels,i)) || text.parent<0)continue;
            ControlTree.Node cta=tree.nodes.get(text.parent);
            if(cta.parent<0)continue;
            ControlTree.Node strip=tree.nodes.get(cta.parent);
            if(strip.parent<0)continue;
            ControlTree.Node footer=tree.nodes.get(strip.parent);
            if(footer.parent!=panelIndex || strip.parent==headerIndex || strip.parent==bodyIndex)continue;
            if(!usable(text,tree) || text.children!=0 || !usable(cta,tree) || !cta.clickable || cta.children!=2 ||
                    !ControlTree.safeParent(text.box,cta.box,tree.width,tree.height) ||
                    !usable(strip,tree) || !strip.clickable || strip.children!=6 || !usable(footer,tree) ||
                    footer.clickable || footer.children!=1 || !containedPath(tree,i,panelIndex) ||
                    strip.box[2]-strip.box[0]<panelWidth*.75f || strip.box[3]-strip.box[1]>tree.width*.30f ||
                    strip.box[1]<panel.box[1]+panelHeight*.72f || strip.box[3]<panel.box[3]-panelHeight*.15f ||
                    centerX(text.box)<strip.box[0]+(strip.box[2]-strip.box[0])*.60f ||
                    tree.nodes.get(imageIndex).box[3]>strip.box[1])continue;
            if(ctaIndex>=0)return null;
            ctaIndex=i;
        }
        return ctaIndex<0?null:new Candidate(closeIndex,targetIndex,panelIndex,ctaIndex,menuIndex,"查看详情",imageIndex);
    }
    private static boolean panelBox(int[] b,ControlTree.Snapshot tree) {
        return b[2]-b[0]>=tree.width*.8f && b[3]-b[1]>=tree.height*.35f &&
            b[3]-b[1]<=tree.height*.90f && b[1]>=tree.height*.10f && b[3]>=tree.height*.88f;
    }
    private static boolean smallLabel(int[] b,ControlTree.Snapshot tree) {
        return ControlTree.small(b,tree.width,tree.height) && b[2]-b[0]<=tree.width*.16f && b[3]-b[1]<=tree.width*.12f;
    }
    private static boolean usable(ControlTree.Node node,ControlTree.Snapshot tree) {
        int[] b=node.box;
        // The Android adapter combines visibility and enabled state in Node.visible.
        return node.visible && b!=null && b.length==4 && b[0]>=0 && b[1]>=0 && b[2]<=tree.width && b[3]<=tree.height && b[2]>b[0] && b[3]>b[1];
    }
    private static int branchUnder(ControlTree.Snapshot tree,int index,int ancestor) {
        for(int at=index,depth=0;at>=0 && depth<=ControlTree.DEPTH;depth++) {
            int parent=tree.nodes.get(at).parent;
            if(parent==ancestor)return at;
            at=parent;
        }
        return -1;
    }
    private static boolean containedPath(ControlTree.Snapshot tree,int index,int ancestor) {
        int[] label=tree.nodes.get(index).box;
        for(int at=index,depth=0;at>=0 && depth<=ControlTree.DEPTH;depth++) {
            ControlTree.Node node=tree.nodes.get(at);
            if(!usable(node,tree) || !ControlTree.contains(node.box,label))return false;
            if(at==ancestor)return true;
            at=node.parent;
        }
        return false;
    }
    private static boolean completeTopology(ControlTree.Snapshot tree) {
        if(!tree.complete || tree.width<=0 || tree.height<=0 || tree.nodes.isEmpty() || tree.nodes.size()>ControlTree.LIMIT)return false;
        int[] children=new int[tree.nodes.size()],depths=new int[tree.nodes.size()];
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node node=tree.nodes.get(i);
            if(node==null || node.children<0)return false;
            if(i==0){if(node.parent!=-1)return false;}
            else {
                if(node.parent<0 || node.parent>=i)return false;
                children[node.parent]++;depths[i]=depths[node.parent]+1;
                if(depths[i]>ControlTree.DEPTH)return false;
            }
        }
        for(int i=0;i<children.length;i++)if(children[i]!=tree.nodes.get(i).children)return false;
        return true;
    }
    private static String label(Map<Integer,String> labels,int index) {
        String value=labels.get(index);return value==null?"":value.trim();
    }
    private static int centerX(int[] b){return b[0]+(b[2]-b[0])/2;}
    private static int centerY(int[] b){return b[1]+(b[3]-b[1])/2;}
}
