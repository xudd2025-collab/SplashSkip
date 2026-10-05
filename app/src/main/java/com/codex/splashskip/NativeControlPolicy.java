package com.codex.splashskip;

import java.util.*;

/** Current native semantics select controls; each executor must revalidate the live target. */
final class NativeControlPolicy {
    /** Source identities, not class names or geometry, preserve a refreshed root's children. */
    static boolean declaredChildrenMatch(List<?> expected,List<?> current,int aliases) {
        if(expected==null || current==null || aliases<0 || current.size()!=expected.size()+aliases ||
                new HashSet<>(expected).size()!=expected.size())return false;
        for(Object child:expected)if(child==null)return false;
        List<Object> seen=new ArrayList<>();int next=0,duplicates=0;
        for(Object child:current) {
            if(child==null)return false;
            if(seen.contains(child)){duplicates++;continue;}
            seen.add(child);
            if(next>=expected.size() || !child.equals(expected.get(next++)))return false;
        }
        return next==expected.size() && duplicates==aliases;
    }
    private static final String MOBILE_PACKAGE="com.greenpoint.android.mc10086.activity";
    private static final String BAIDU_MAP_PACKAGE="com.baidu.BaiduMap";
    private static final String YOUKU_PACKAGE="com.youku.phone";
    private static final String MOBILE_LAYER=JointControlModel.digest("android.widget.LinearLayout/");
    private static final String MOBILE_VIDEO=mobileIdentity("android.widget.FrameLayout","video_layout");
    private static final String MOBILE_LOGO_LAYER=mobileIdentity("android.widget.FrameLayout","logo_layout");
    private static final String MOBILE_IMAGE=mobileIdentity("android.widget.ImageView","img_video");
    private static final String MOBILE_LOGO=mobileIdentity("android.widget.ImageView","logo");
    private static final String MOBILE_HEADER=mobileIdentity("android.widget.LinearLayout","ll_top_right");
    private static final String MOBILE_SKIP=mobileIdentity("android.widget.TextView","video_time_skip");
    private static String mobileIdentity(String type,String id) {
        return JointControlModel.digest(type+"/"+MOBILE_PACKAGE+":id/"+id);
    }
    static final class Candidate {
        final ControlTree.Hint label;
        final int target;
        final boolean currentLabelTouch;
        Candidate(ControlTree.Hint label,int target){this(label,target,false);}
        Candidate(ControlTree.Hint label,int target,boolean currentLabelTouch) {
            this.label=label;this.target=target;this.currentLabelTouch=currentLabelTouch;
        }
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
                if(!opening || !scene(tree,i) && !(UiControlPolicy.SKIP.equals(n.role) &&
                        (compactAdGroup(tree,i) || fullScreenAdSubtree(tree,i))))continue;
            }
            // Duplicate label/description nodes for the same button are harmless;
            // two distinct eligible buttons are ambiguous.
            if(result!=null && (result.target!=target || !result.label.action.equals(n.role)))return null;
            if(result==null || ControlTree.area(n.box)<ControlTree.area(result.label.box))result=new Candidate(hint,target);
        }
        return result;
    }
    static final class ScopedDecision {
        final Candidate candidate;
        final String reason;
        ScopedDecision(Candidate candidate,String reason){this.candidate=candidate;this.reason=reason;}
    }
    static boolean scopedAction(String role){return UiControlPolicy.SKIP.equals(role) || UiControlPolicy.CLOSE_AD.equals(role);}
    /** A dismissed splash does not suppress a later explicitly labelled Close Ad. */
    static boolean blockedByOpeningAction(boolean submitted,String role) {
        return submitted && !UiControlPolicy.CLOSE_AD.equals(role);
    }
    /** Only a previously explicit Skip may vary its independently explicit countdown fields. */
    static int scopePropertyDifference(Object[] first,Object[] current,boolean movingDecoration,String role) {
        if(first==null || current==null || first.length!=current.length)return 0;
        for(int i=0;i<first.length;i++) {
            if(Objects.equals(first[i],current[i]))continue;
            if(movingDecoration && (i==6 || i==7))continue;
            if((i==3 || i==4) && UiControlPolicy.SKIP.equals(role) &&
                    first[i] instanceof String && current[i] instanceof String &&
                    UiControlPolicy.SKIP.equals(UiControlPolicy.action((String)first[i])) &&
                    UiControlPolicy.SKIP.equals(UiControlPolicy.action((String)current[i])) &&
                    (!UiControlPolicy.explicitSkipCountdown((String)first[i]) ||
                        UiControlPolicy.explicitSkipCountdown((String)current[i])) &&
                    (!UiControlPolicy.explicitSkipAd((String)first[i]) || UiControlPolicy.explicitSkipAd((String)current[i])))continue;
            return i;
        }
        return -1;
    }
    static boolean animatedDecoration(ControlTree.Snapshot tree,int index) {
        if(tree==null || !tree.complete || index<=0 || index>=tree.nodes.size() || !completeScopeTopology(tree))return false;
        ControlTree.Node node=tree.nodes.get(index);
        if(!node.role.isEmpty() || node.clickable || node.children!=0)return false;
        // These images now supply native startup-layer evidence, so their geometry
        // cannot use the tolerance reserved for unrelated decoration.
        if(MOBILE_PACKAGE.equals(tree.pkg) && (MOBILE_IMAGE.equals(node.identity) || MOBILE_LOGO.equals(node.identity)))return false;
        // Every image/header below participates in this proof; its geometry stays exact.
        if(baiduStartupSkip(tree)>=0)return false;
        if(youkuDisclosureSkip(tree)>=0)return false;
        for(int i=0;i<tree.nodes.size();i++)if(!tree.nodes.get(i).role.isEmpty())
            for(int at=i,depth=0;at>=0 && depth<=ControlTree.DEPTH;depth++,at=tree.nodes.get(at).parent)
                if(at==index)return false;
        return true;
    }
    /** Only for a separately captured, complete native scope; global partial trees stay unknown. */
    static Candidate findVerifiedAdScope(ControlTree.Snapshot tree) {
        return inspectVerifiedAdScope(tree).candidate;
    }
    /** Fixed reason codes contain no app-provided text or identifiers. */
    static ScopedDecision inspectVerifiedAdScope(ControlTree.Snapshot tree) {
        if(tree==null)return new ScopedDecision(null,"scope-unavailable");
        if(!tree.complete)return new ScopedDecision(null,"scope-incomplete");
        if(tree.nodes.isEmpty())return new ScopedDecision(null,"scope-empty");
        if(!completeScopeTopology(tree))return new ScopedDecision(null,"scope-topology");
        ControlTree.Node root=tree.nodes.get(0);
        if(!root.visible)return new ScopedDecision(null,"scope-hidden");
        if(!coversScreen(tree,root.box))return new ScopedDecision(null,"scope-bounds");
        boolean ad=false;
        for(ControlTree.Node n:tree.nodes) {
            if(!n.visible)continue;
            if(n.role.equals("nav"))return new ScopedDecision(null,"scope-navigation");
            ad|=n.role.equals("ad") && n.box[2]>n.box[0] && n.box[3]>n.box[1] && ControlTree.contains(root.box,n.box);
        }
        int structuralSkip=ad?-1:mobileStartupSkip(tree);
        if(!ad && structuralSkip<0)structuralSkip=baiduStartupSkip(tree);
        int disclosureSkip=ad?-1:youkuDisclosureSkip(tree);
        boolean semanticAd=ad || disclosureSkip>=0;
        if(!semanticAd && structuralSkip<0)return new ScopedDecision(null,"scope-no-ad");
        Candidate result=null;
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node n=tree.nodes.get(i);
            if(!n.visible || !scopedAction(n.role))continue;
            if(!ControlTree.small(n.box,tree.width,tree.height) || !ControlTree.contains(root.box,n.box))
                return new ScopedDecision(null,"scope-skip-bounds");
            int target=target(tree,i);boolean currentLabelTouch=false;
            if(target<0) {
                // Some providers omit clickability from an explicit Skip label.
                // Only this independently complete ad scope can propose its live
                // label bounds; executors must retain the separate touch proof.
                if(!semanticAd || !UiControlPolicy.SKIP.equals(n.role))return new ScopedDecision(null,"scope-no-safe-target");
                target=i;currentLabelTouch=true;
            }
            if(result!=null && (result.target!=target || result.currentLabelTouch!=currentLabelTouch))
                return new ScopedDecision(null,"scope-ambiguous-skip");
            if(result==null || ControlTree.area(n.box)<ControlTree.area(result.label.box))
                result=new Candidate(tree.hint(i,n.role),target,currentLabelTouch);
        }
        if(!semanticAd && result!=null && (result.label.nodeIndex!=structuralSkip || result.target!=structuralSkip || result.currentLabelTouch))
            return new ScopedDecision(null,"scope-no-safe-target");
        if(disclosureSkip>=0 && result!=null && result.label.nodeIndex!=disclosureSkip)
            return new ScopedDecision(null,"scope-no-safe-target");
        return new ScopedDecision(result,result==null?"scope-no-skip":"verified");
    }
    /** Strong live semantics and separate full-screen creative/template branches.
     * The old generic prompt role cannot supply either new frozen semantic flag. */
    private static int youkuDisclosureSkip(ControlTree.Snapshot tree) {
        if(!YOUKU_PACKAGE.equals(tree.pkg) || tree.nodes.isEmpty())return -1;
        int skip=-1,disclosure=-1;ControlTree.Node root=tree.nodes.get(0);
        for(int i=0;i<tree.nodes.size();i++) {
            ControlTree.Node n=tree.nodes.get(i);if(!n.visible)continue;
            if("nav".equals(n.role))return -1;
            if(UiControlPolicy.isControl(n.role)) {
                if(skip>=0 || !UiControlPolicy.SKIP.equals(n.role) || !n.explicitSkipAd || n.children!=0 ||
                        !"android.widget.TextView".equals(n.className) || !ControlTree.small(n.box,tree.width,tree.height) ||
                        !ControlTree.contains(root.box,n.box))return -1;
                skip=i;
            }
            if(n.navigationDisclosure) {
                if(disclosure>=0 || !"prompt".equals(n.role) || n.children!=0 || !"android.widget.TextView".equals(n.className) ||
                        n.box[2]<=n.box[0] || n.box[3]<=n.box[1] || !ControlTree.contains(root.box,n.box))return -1;
                disclosure=i;
            }
        }
        if(skip<0 || disclosure<0 || tree.nodes.get(disclosure).box[1]<tree.nodes.get(skip).box[3])return -1;
        for(int at=tree.nodes.get(skip).parent,d=0;at>=0 && d<=ControlTree.DEPTH;d++,at=tree.nodes.get(at).parent) {
            ControlTree.Node junction=tree.nodes.get(at);
            if(!junction.visible || !junction.role.isEmpty() || !coversScreen(tree,junction.box) ||
                    !ControlTree.contains(root.box,junction.box))continue;
            int template=childBelow(tree,skip,at);
            if(template<0 || template!=childBelow(tree,disclosure,at))continue;
            ControlTree.Node content=tree.nodes.get(template);
            if(!content.visible || !content.role.isEmpty() || !Arrays.equals(content.box,junction.box) ||
                    !ControlTree.contains(content.box,tree.nodes.get(skip).box) ||
                    !ControlTree.contains(content.box,tree.nodes.get(disclosure).box))continue;
            for(int image=0;image<tree.nodes.size();image++) {
                ControlTree.Node picture=tree.nodes.get(image);
                if(!picture.visible || picture.children!=0 || !picture.role.isEmpty() ||
                        !"android.widget.ImageView".equals(picture.className))continue;
                int peer=childBelow(tree,image,at);
                if(peer<0 || peer==template)continue;
                ControlTree.Node branch=tree.nodes.get(peer);
                if(branch.visible && branch.role.isEmpty() && Arrays.equals(branch.box,junction.box) &&
                        Arrays.equals(picture.box,branch.box))return skip;
            }
        }
        return -1;
    }
    private static int childBelow(ControlTree.Snapshot tree,int index,int ancestor) {
        for(int d=0;index>=0 && index<tree.nodes.size() && d<=ControlTree.DEPTH;d++,index=tree.nodes.get(index).parent)
            if(tree.nodes.get(index).parent==ancestor)return index;
        return -1;
    }
    /** Observed complete timed creative layer. No view ID, stored point or partial-page proof. */
    private static int baiduStartupSkip(ControlTree.Snapshot tree) {
        if(!BAIDU_MAP_PACKAGE.equals(tree.pkg) || (tree.nodes.size()!=8 && tree.nodes.size()!=9))return -1;
        int layer=0;
        if(tree.nodes.size()==9) {
            ControlTree.Node wrapper=tree.nodes.get(0);
            if(!wrapper.visible || !wrapper.clickable || !wrapper.role.isEmpty() || wrapper.children!=1 ||
                    !"android.widget.FrameLayout".equals(wrapper.className))return -1;
            layer=1;
            if(tree.nodes.get(layer).parent!=0 || !Arrays.equals(wrapper.box,tree.nodes.get(layer).box))return -1;
        }
        ControlTree.Node root=tree.nodes.get(layer);
        if(!root.visible || root.clickable || !root.role.isEmpty() || root.children!=7 ||
                !"android.widget.RelativeLayout".equals(root.className) || !coversScreen(tree,root.box))return -1;
        int creative=0,image=0,overlay=0,hidden=0,logo=-1,caption=-1,skip=-1;
        for(int i=layer+1;i<tree.nodes.size();i++) {
            ControlTree.Node n=tree.nodes.get(i);
            if(n.parent!=layer || n.children!=0)return -1;
            if(!n.visible) {
                if(n.clickable || !n.role.isEmpty() || n.skipCountdown || !"android.widget.ImageView".equals(n.className))return -1;
                hidden++;continue;
            }
            if(!ControlTree.contains(root.box,n.box) || n.box[2]<=n.box[0] || n.box[3]<=n.box[1])return -1;
            if(UiControlPolicy.SKIP.equals(n.role)) {
                if(skip>=0 || !n.skipCountdown || !n.clickable || !"android.widget.TextView".equals(n.className) ||
                        !ControlTree.small(n.box,tree.width,tree.height) || target(tree,i)!=i)return -1;
                skip=i;continue;
            }
            if(!n.role.isEmpty() || n.skipCountdown)return -1;
            if("android.widget.ImageView".equals(n.className)) {
                if(Arrays.equals(root.box,n.box)) {
                    if(n.clickable)creative++;else image++;
                } else {
                    if(n.clickable || logo>=0 || !ControlTree.small(n.box,tree.width,tree.height))return -1;
                    logo=i;
                }
            } else if("android.view.View".equals(n.className)) {
                if(n.clickable || !Arrays.equals(root.box,n.box))return -1;
                overlay++;
            } else if("android.widget.TextView".equals(n.className)) {
                if(n.clickable || caption>=0 || !ControlTree.small(n.box,tree.width,tree.height))return -1;
                caption=i;
            } else return -1;
        }
        if(creative!=1 || image!=1 || overlay!=1 || hidden!=1 || logo<0 || caption<0 || skip<0)return -1;
        // The current brand, caption and timed Skip form one nonoverlapping row.
        // Their order and absolute screen location are not fixed.
        int[] a=tree.nodes.get(logo).box,b=tree.nodes.get(caption).box,c=tree.nodes.get(skip).box;
        return sameRow(a,b) && sameRow(a,c) && sameRow(b,c)?skip:-1;
    }
    private static boolean sameRow(int[] a,int[] b) {
        int overlap=Math.min(a[3],b[3])-Math.max(a[1],b[1]);
        return overlap>0 && overlap*2>=Math.min(a[3]-a[1],b[3]-b[1]) && (a[2]<=b[0] || b[2]<=a[0]);
    }
    /** Observed Mobile startup video and brand layer; IDs alone are not a scene proof. */
    private static int mobileStartupSkip(ControlTree.Snapshot tree) {
        if(!MOBILE_PACKAGE.equals(tree.pkg))return -1;
        ControlTree.Node root=tree.nodes.get(0);
        if(!MOBILE_LAYER.equals(root.identity) || !root.role.isEmpty() || root.children!=2)return -1;
        int video=mobileChild(tree,0,MOBILE_VIDEO),logo=mobileChild(tree,0,MOBILE_LOGO_LAYER);
        if(video<0 || logo<0)return -1;
        ControlTree.Node content=tree.nodes.get(video),brand=tree.nodes.get(logo);
        if(content.children!=3 || brand.children!=1 || !content.role.isEmpty() || !brand.role.isEmpty() ||
                !coversScreen(tree,content.box))return -1;
        int image=mobileChild(tree,video,MOBILE_IMAGE),header=mobileChild(tree,video,MOBILE_HEADER),
                logoImage=mobileChild(tree,logo,MOBILE_LOGO);
        if(image<0 || header<0 || logoImage<0)return -1;
        ControlTree.Node picture=tree.nodes.get(image),caption=tree.nodes.get(header),mark=tree.nodes.get(logoImage);
        if(picture.children!=0 || mark.children!=0 || caption.children!=1 || picture.clickable || mark.clickable ||
                caption.clickable || !picture.role.isEmpty() || !caption.role.isEmpty() || !mark.role.isEmpty() ||
                !Arrays.equals(picture.box,content.box) || !ControlTree.small(caption.box,tree.width,tree.height))return -1;
        int skip=mobileChild(tree,header,MOBILE_SKIP);if(skip<0)return -1;
        ControlTree.Node control=tree.nodes.get(skip);
        return control.children==0 && control.clickable && UiControlPolicy.SKIP.equals(control.role) &&
                ControlTree.small(control.box,tree.width,tree.height) && target(tree,skip)==skip?skip:-1;
    }
    private static int mobileChild(ControlTree.Snapshot tree,int parent,String identity) {
        int found=-1;
        for(int i=1;i<tree.nodes.size();i++) {
            ControlTree.Node n=tree.nodes.get(i);
            if(n.parent!=parent || !identity.equals(n.identity))continue;
            if(found>=0 || !n.visible || n.box[2]<=n.box[0] || n.box[3]<=n.box[1] ||
                    !ControlTree.contains(tree.nodes.get(parent).box,n.box))return -1;
            found=i;
        }
        return found;
    }
    private static boolean completeScopeTopology(ControlTree.Snapshot tree) {
        int[] children=new int[tree.nodes.size()];
        if(tree.nodes.get(0).parent!=-1)return false;
        for(int i=1;i<tree.nodes.size();i++) {
            int parent=tree.nodes.get(i).parent;
            if(parent<0 || parent>=i)return false;
            children[parent]++;
        }
        for(int i=0;i<children.length;i++)if(tree.nodes.get(i).children!=children[i])return false;
        return true;
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
    private static boolean fullScreenAdSubtree(ControlTree.Snapshot tree,int label) {
        // Provider wrappers can flatten the ad header into a full-screen layer.
        // Keep the explicit Skip/ad proof local to a navigation-free subtree;
        // the home navigation must live in a separate full-screen sibling layer.
        for(int at=tree.nodes.get(label).parent,d=0;at>=0 && at<tree.nodes.size() && d<4;d++,at=tree.nodes.get(at).parent) {
            ControlTree.Node scope=tree.nodes.get(at);
            if(!scope.visible || !coversScreen(tree,scope.box) || !ControlTree.contains(scope.box,tree.nodes.get(label).box))continue;
            boolean cue=false,nav=false;
            for(int i=0;i<tree.nodes.size();i++) {
                ControlTree.Node n=tree.nodes.get(i);
                if(!n.visible || !descendant(tree,i,at))continue;
                nav|=n.role.equals("nav");
                cue|=n.role.equals("ad") && ControlTree.contains(scope.box,n.box) && n.box[2]>n.box[0] && n.box[3]>n.box[1];
            }
            if(cue && !nav && separateHomeLayer(tree,at))return true;
        }
        return false;
    }
    private static boolean coversScreen(ControlTree.Snapshot tree,int[] box) {
        return coversScreen(box,tree.width,tree.height);
    }
    static boolean coversScreen(int[] box,int width,int height) {
        return width>0 && height>0 && box[2]>box[0] && box[3]>box[1] && ControlTree.contains(new int[]{0,0,width,height},box) &&
            ControlTree.area(box)>=((long)width*height*4)/5;
    }
    private static boolean separateHomeLayer(ControlTree.Snapshot tree,int scope) {
        for(int branch=scope,d=0;branch>=0 && branch<tree.nodes.size() && d<=ControlTree.DEPTH;d++,branch=tree.nodes.get(branch).parent) {
            int parent=tree.nodes.get(branch).parent;
            if(parent<0 || parent>=tree.nodes.size())return false;
            ControlTree.Node junction=tree.nodes.get(parent);
            if(junction.visible && junction.role.equals("nav"))return false;
            boolean home=false;
            for(int i=0;i<tree.nodes.size();i++) {
                ControlTree.Node n=tree.nodes.get(i);
                if(!n.visible || !n.role.equals("nav"))continue;
                // Encountering navigation on the ad side invalidates the split,
                // even if another sibling also contains home navigation.
                if(descendant(tree,i,branch))return false;
                for(int peer=i,depth=0;peer>=0 && peer<tree.nodes.size() && depth<=ControlTree.DEPTH;depth++,peer=tree.nodes.get(peer).parent) {
                    ControlTree.Node sibling=tree.nodes.get(peer);
                    if(sibling.parent==parent){
                        home|=peer!=branch && sibling.visible && coversScreen(tree,sibling.box);
                        break;
                    }
                }
            }
            if(home)return true;
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
