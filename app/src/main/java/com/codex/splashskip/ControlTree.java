package com.codex.splashskip;

import java.util.*;

/** Ephemeral geometry from the current accessibility tree. No saved coordinates are proposals. */
final class ControlTree {
    static final int LIMIT=512, DEPTH=40;
    static String role(String text) {
        String action=UiControlPolicy.action(text);if(!action.isEmpty())return action;
        UiControlPolicy.Word word=new UiControlPolicy.Word(text,1,1,0,0,1,1);
        if(UiControlPolicy.adMark(word))return "ad";if(UiControlPolicy.prompt(word))return "prompt";
        String s=UiControlPolicy.normalized(text);if(s.equals("首页")||s.equals("我的")||s.equals("会员专区")||s.equals("发现"))return "nav";
        return "";
    }
    static final class Node {
        final int parent,children; final int[] box; final boolean clickable;
        final String role,identity,className,viewId;final boolean visible,skipCountdown,explicitSkipAd,navigationDisclosure;
        // Nullable properties keep older diagnostic snapshots honest about missing information.
        final Boolean enabled,declaredClickable,onScreen;
        Node(int parent,int children,int[] box,boolean clickable,String role,String identity) {
            this(parent,children,box,clickable,role,identity,true);
        }
        Node(int parent,int children,int[] box,boolean clickable,String role,String identity,boolean visible) {
            this(parent,children,box,clickable,role,identity,visible,"","");
        }
        Node(int parent,int children,int[] box,boolean clickable,String role,String identity,boolean visible,String className,String viewId) {
            this(parent,children,box,clickable,role,identity,visible,className,viewId,false);
        }
        Node(int parent,int children,int[] box,boolean clickable,String role,String identity,boolean visible,String className,String viewId,boolean skipCountdown) {
            this(parent,children,box,clickable,role,identity,visible,className,viewId,skipCountdown,null,null,null);
        }
        Node(int parent,int children,int[] box,boolean clickable,String role,String identity,boolean visible,String className,String viewId,
                boolean skipCountdown,Boolean enabled,Boolean declaredClickable,Boolean onScreen) {
            this(parent,children,box,clickable,role,identity,visible,className,viewId,skipCountdown,enabled,declaredClickable,onScreen,false,false);
        }
        Node(int parent,int children,int[] box,boolean clickable,String role,String identity,boolean visible,String className,String viewId,
                boolean skipCountdown,Boolean enabled,Boolean declaredClickable,Boolean onScreen,boolean explicitSkipAd,boolean navigationDisclosure) {
            this.parent=parent;this.children=children;this.box=box.clone();this.clickable=clickable;
            this.role=role;this.identity=identity;this.visible=visible;
            this.className=className==null?"":className;this.viewId=viewId==null?"":viewId;
            this.skipCountdown=skipCountdown;this.enabled=enabled;this.declaredClickable=declaredClickable;this.onScreen=onScreen;
            this.explicitSkipAd=explicitSkipAd;this.navigationDisclosure=navigationDisclosure;
        }
    }
    static final class Hint {
        final int[] box; final String action,structure; final float[] shape;final int nodeIndex;
        Hint(int nodeIndex,int[] box,String action,String structure,float[] shape) {
            this.nodeIndex=nodeIndex;this.box=box.clone();this.action=action;this.structure=structure;this.shape=shape;
        }
    }
    static final class Snapshot {
        final String pkg;final long epoch,time;final int width,height;
        final List<Node> nodes;
        final boolean complete;
        Snapshot(String pkg,long epoch,long time,int w,int h,List<Node> nodes) {
            this(pkg,epoch,time,w,h,nodes,true);
        }
        Snapshot(String pkg,long epoch,long time,int w,int h,List<Node> nodes,boolean complete) {
            this.pkg=pkg;this.epoch=epoch;this.time=time;width=w;height=h;
            this.complete=complete;
            this.nodes=Collections.unmodifiableList(new ArrayList<>(nodes));
        }
        boolean current(String owner,long generation,long now,int w,int h) {
            return pkg.equals(owner) && epoch==generation && now>=time && now-time<=250 && width==w && height==h;
        }
        /** After an uncached complete current scope/edges, or independent per-node refresh. */
        boolean currentVerifiedScope(String owner,long generation,long now,int w,int h,long verificationStarted) {
            return complete && verificationStarted>=time && pkg.equals(owner) && epoch==generation &&
                now>=verificationStarted && now-verificationStarted<=250 && width==w && height==h;
        }
        List<Hint> controls(Map<String,String> learned) {
            List<Hint> result=new ArrayList<>();
            for(int i=0;i<nodes.size() && result.size()<8;i++) {
                Node n=nodes.get(i);if(!n.visible || !small(n.box,width,height))continue;
                String key=structure(i),action=UiControlPolicy.isControl(n.role)?n.role:learned.get(key);
                if(action==null || action.isEmpty())continue;
                result.add(hint(i,action));
            }
            return result;
        }
        Hint match(BilibiliVisualMatcher.Hit hit) {
            if(hit.textBounds==null)return null;
            int best=-1;long area=Long.MAX_VALUE;
            for(int i=0;i<nodes.size();i++) {
                Node n=nodes.get(i);
                if(!n.visible || !small(n.box,width,height) || !contains(n.box,hit.textBounds))continue;
                if(!n.role.isEmpty() && !n.role.equals(hit.rule))continue;
                long a=area(n.box);if(a<area){best=i;area=a;}
            }
            return best<0?null:hint(best,hit.rule);
        }
        String structure(int index) {
            StringBuilder b=new StringBuilder();
            for(int d=0;index>=0 && index<nodes.size() && d<5;d++) {
                Node n=nodes.get(index);b.append(n.identity).append(':').append(n.clickable?'1':'0').append('/');index=n.parent;
            }
            return JointControlModel.digest(b.toString());
        }
        Hint hint(int i,String action) {
            Node n=nodes.get(i);int ancestor=-1,distance=0,p=i;
            for(int d=0;p>=0 && p<nodes.size() && d<5;d++) {
                Node a=nodes.get(p);
                if(a.clickable && safeParent(n.box,a.box,width,height)){ancestor=p;distance=d;break;}
                p=a.parent;
            }
            float[] shape=new float[JointControlModel.SHAPE];
            shape[0]=1;shape[1]=n.clickable?1:0;shape[2]=ancestor>=0?1:0;shape[3]=distance/4f;
            shape[4]=Math.min(8,n.children)/8f;
            shape[5]=Math.min(8,(n.box[2]-n.box[0])/(float)Math.max(1,n.box[3]-n.box[1]))/8f;
            shape[6]=(n.box[2]-n.box[0])/(float)width;shape[7]=(n.box[3]-n.box[1])/(float)height;
            if(ancestor>=0){Node a=nodes.get(ancestor);shape[8]=(float)area(n.box)/area(a.box);shape[9]=Math.min(8,a.children)/8f;}
            shape[10]=UiControlPolicy.isControl(n.role)?1:0;shape[11]=UiControlPolicy.SKIP.equals(action)?1:0;
            return new Hint(i,n.box,action,structure(i),shape);
        }
        int[] safeClickableParent(int index) {
            if(index<0 || index>=nodes.size())return null;
            int[] child=nodes.get(index).box;
            for(int at=index,depth=0;at>=0 && at<nodes.size() && depth<5;depth++,at=nodes.get(at).parent) {
                Node n=nodes.get(at);
                if(n.clickable && safeParent(child,n.box,width,height))return n.box.clone();
            }
            return null;
        }
        int branch(int i) {
            if(i<0 || i>=nodes.size())return -1;
            List<Integer> path=new ArrayList<>();
            for(int depth=0;i>=0&&i<nodes.size()&&depth<=DEPTH;depth++,i=nodes.get(i).parent)path.add(i);
            // Unimportant views add unary decor/layout wrappers above the real
            // window branches. Skip those wrappers, not the ad/home separation.
            for(int p=path.size()-1;p>0;p--)if(nodes.get(path.get(p)).children>1)return path.get(p-1);
            return path.get(path.size()-1);
        }
        boolean sameBranch(int i,int j) {return branch(i)>=0 && branch(i)==branch(j);}
        boolean fullScreenBranch(int i) {
            int branch=branch(i);
            return branch>=0 && area(nodes.get(branch).box)>=((long)width*height*4)/5;
        }
    }
    static long area(int[] b){return (long)(b[2]-b[0])*(b[3]-b[1]);}
    static boolean contains(int[] outer,int[] inner){return outer[0]<=inner[0] && outer[1]<=inner[1] && outer[2]>=inner[2] && outer[3]>=inner[3];}
    static boolean small(int[] b,int w,int h) {
        return b!=null && b.length==4 && w>0 && h>0 && b[0]>=0 && b[1]>=0 && b[2]<=w && b[3]<=h &&
            b[2]>b[0] && b[3]>b[1] && b[2]-b[0]<=w*.48f && b[3]-b[1]<=Math.min(w,h)*.15f;
    }
    static boolean safeParent(int[] child,int[] parent,int w,int h) {
        return small(parent,w,h) && contains(parent,child) && area(parent)<=area(child)*6;
    }
}
