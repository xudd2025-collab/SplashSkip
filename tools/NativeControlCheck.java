package com.codex.splashskip;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Native policy regressions plus replay of actual captures exported as tab-separated fields. */
public final class NativeControlCheck {
    static int checks;
    static void ok(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static void pauseAdChecks() {
        for(String label:new String[]{"关闭广告，放大暂停画面","关闭广告,放大暂停画面"}) {
            ok(UiControlPolicy.CLOSE_AD.equals(UiControlPolicy.action(label)),"explicit pause ad close sentence: "+label);
            int[] full={0,0,2640,1216};
            List<ControlTree.Node> nodes=new ArrayList<>();
            nodes.add(node(-1,2,full,false,"","root"));
            nodes.add(node(0,2,new int[]{1755,35,2441,140},true,"","close-capsule"));
            nodes.add(node(0,0,new int[]{20,600,100,680},true,"nav","page-control"));
            nodes.add(node(1,0,new int[]{1797,54,2336,120},false,ControlTree.role(label),"label"));
            nodes.add(node(1,0,new int[]{2350,63,2399,112},false,"","close-image"));
            var tree=new ControlTree.Snapshot("com.qiyi.video",9,100,2640,1216,nodes,true);
            var candidate=NativeControlPolicy.find(tree,false);
            ok(candidate!=null && candidate.label.nodeIndex==3 && candidate.target==1 && !candidate.currentLabelTouch,
                    "pause page selects current close capsule outside launch window");
            ok(!NativeControlPolicy.blockedByOpeningAction(true,candidate.label.action),
                    "after splash skip a later explicit pause close remains eligible");
            ok(NativeControlPolicy.find(new ControlTree.Snapshot(tree.pkg,9,100,2640,1216,nodes,false),false)==null,
                    "partial pause page cannot authorize close");
            nodes.set(1,node(0,2,full,true,"","giant-ad-container"));
            ok(NativeControlPolicy.find(new ControlTree.Snapshot(tree.pkg,9,100,2640,1216,nodes,true),false)==null,
                    "pause text does not authorize a giant ad container");
        }
        for(String text:new String[]{"继续播放","打开应用","关闭广告，打开应用","关闭广告，开通会员",
                "如何关闭广告，放大暂停画面","关闭广告，放大暂停画面说明","关闭广告后继续播放"})
            ok(UiControlPolicy.action(text).isEmpty(),"other pause-page text is not a close action: "+text);
        ok(NativeControlPolicy.blockedByOpeningAction(true,UiControlPolicy.SKIP) &&
                NativeControlPolicy.blockedByOpeningAction(true,UiControlPolicy.CLOSE),"opening actions remain suppressed after splash submission");
        ok(NativeControlPolicy.blockedByOpeningAction(true,""),"unknown role cannot bypass prior splash suppression");
        ok(!NativeControlPolicy.blockedByOpeningAction(false,UiControlPolicy.SKIP),"initial splash action still eligible");
    }
    static ControlTree.Node node(int p,int count,int[] b,boolean click,String role,String id){return new ControlTree.Node(p,count,b,click,role,id);}
    static void youkuSkipAdChecks() {
        for(String label:new String[]{"跳过广告","跳过 广告","\n跳过广告\n"})
            ok(UiControlPolicy.SKIP.equals(UiControlPolicy.action(label)),"exact Skip Ad phrase recognized: "+label.trim());
        for(String text:new String[]{"如何跳过广告","点击跳过广告领取奖励","跳过广告，打开应用","跳过广告开通会员","广告跳过"})
            ok(UiControlPolicy.action(text).isEmpty(),"Skip Ad instructions and promotion are not actions: "+text);
        var n=scopedAdFixture();var old=n.get(1);
        n.set(1,node(0,0,old.box,false,ControlTree.role("跳过广告"),old.identity));
        var t=new ControlTree.Snapshot("com.youku.phone",9,100,1216,2640,n,true);
        var c=NativeControlPolicy.findVerifiedAdScope(t);
        ok(c!=null && c.target==1 && c.currentLabelTouch && UiControlPolicy.SKIP.equals(c.label.action),
                "Skip Ad without clickability requires complete independent ad scope");
        ok(NativeControlPolicy.find(t,true)==null,"ordinary global tree cannot authorize unclickable Skip Ad touch");
        ok(NativeControlPolicy.findVerifiedAdScope(new ControlTree.Snapshot(t.pkg,9,100,1216,2640,n,false))==null,
                "incomplete ad scope does not authorize Skip Ad touch");
        n.set(2,node(0,0,n.get(2).box,false,"","missing-ad"));
        ok(NativeControlPolicy.findVerifiedAdScope(new ControlTree.Snapshot(t.pkg,9,100,1216,2640,n,true))==null,
                "Skip Ad phrase alone cannot supply its own ad badge");
        n=scopedAdFixture();n.set(1,node(0,0,old.box,false,ControlTree.role("跳过广告"),old.identity));
        n.set(0,node(-1,3,n.get(0).box,false,"","scope"));
        n.add(node(0,0,new int[]{80,2450,180,2530},true,"nav","home"));
        ok(NativeControlPolicy.findVerifiedAdScope(new ControlTree.Snapshot(t.pkg,9,100,1216,2640,n,true))==null,
                "home navigation still blocks Skip Ad label touch");
    }
    static final String YOUKU_PACKAGE="com.youku.phone";
    static ControlTree.Node disclosureNode(int parent,int children,int[] box,boolean click,String role,boolean visible,
            String type,boolean skipAd,boolean navigation) {
        return new ControlTree.Node(parent,children,box,click,role,JointControlModel.digest(type+"/"),visible,type,"",false,
            visible,click,visible,skipAd,navigation);
    }
    /** Geometry/topology from the new 23-node capture, rebased at its node 5.
     * The two strong flags model required fresh semantics, not an inference from the old prompt role. */
    static List<ControlTree.Node> youkuDisclosureFixture() {
        int[] full={0,0,1216,2640};
        return new ArrayList<>(List.of(
            disclosureNode(-1,2,full,false,"",true,"android.widget.FrameLayout",false,false),
            disclosureNode(0,1,full,false,"",true,"android.widget.FrameLayout",false,false),
            disclosureNode(0,1,full,false,"",true,"android.widget.FrameLayout",false,false),
            disclosureNode(1,0,full,false,"",true,"android.widget.ImageView",false,false),
            disclosureNode(2,3,full,false,"",true,"android.widget.FrameLayout",false,false),
            disclosureNode(4,1,new int[]{778,0,1216,263},false,"",true,"android.widget.FrameLayout",false,false),
            disclosureNode(4,2,new int[]{0,2403,1216,2640},true,"",true,"android.widget.FrameLayout",false,false),
            disclosureNode(4,1,new int[]{16,1996,1199,2416},false,"",true,"android.widget.FrameLayout",false,false),
            disclosureNode(5,2,new int[]{873,123,1163,211},false,"",true,"android.widget.LinearLayout",false,false),
            disclosureNode(6,0,new int[]{0,2403,1216,2640},false,"",true,"android.widget.ImageView",false,false),
            disclosureNode(6,0,new int[]{786,2556,835,2584},false,"",true,"android.widget.ImageView",false,false),
            disclosureNode(7,1,new int[]{121,2101,1094,2311},false,"",true,"android.widget.FrameLayout",false,false),
            disclosureNode(8,0,new int[]{915,123,939,211},false,"",true,"android.widget.TextView",false,false),
            disclosureNode(8,0,new int[]{953,123,1121,211},false,UiControlPolicy.SKIP,true,"android.widget.TextView",true,false),
            disclosureNode(11,3,new int[]{121,2101,1094,2311},false,"",true,"android.widget.RelativeLayout",false,false),
            disclosureNode(14,0,new int[]{121,2101,1094,2311},false,"",true,"android.widget.ImageView",false,false),
            disclosureNode(14,0,new int[]{205,2173,933,2239},false,"prompt",true,"android.widget.TextView",false,true),
            disclosureNode(14,0,new int[]{954,2178,1010,2234},false,"",true,"android.widget.ImageView",false,false)));
    }
    static ControlTree.Snapshot youkuTree(List<ControlTree.Node> nodes,boolean complete) {
        return new ControlTree.Snapshot(YOUKU_PACKAGE,9,100,1216,2640,nodes,complete);
    }
    static List<ControlTree.Node> disclosureSubtree(List<ControlTree.Node> nodes,int root) {
        Map<Integer,Integer> map=new HashMap<>();List<ControlTree.Node> subset=new ArrayList<>();
        for(int i=root;i<nodes.size();i++) {
            ControlTree.Node n=nodes.get(i);if(i!=root && !map.containsKey(n.parent))continue;
            map.put(i,subset.size());subset.add(disclosureNode(i==root?-1:map.get(n.parent),n.children,n.box,n.clickable,
                n.role,n.visible,n.className,n.explicitSkipAd,n.navigationDisclosure));
        }
        return subset;
    }
    static void disclosureSemanticsChecks() {
        for(String text:new String[]{"跳过广告","跳过 广告","\n跳过广告\n"})
            ok(UiControlPolicy.explicitSkipAd(text),"independent explicit Skip Ad field: "+text.trim());
        for(String text:new String[]{"跳过","Skip 3s","广告","如何跳过广告","跳过广告开通会员","跳过广告，打开应用",null})
            ok(!UiControlPolicy.explicitSkipAd(text),"ordinary Skip or marketing cannot become explicit Skip Ad: "+text);
        for(String text:new String[]{"跳转详情页或第三方应用","点击跳转至详情页面或第三方应用›",
                "互动跳转详情页面或第三方应用","点击下载或打开第三方应用","下载或跳转至第三方应用>"}) {
            ok(UiControlPolicy.navigationDisclosure(text),"complete third-party navigation disclosure: "+text);
            ok("prompt".equals(ControlTree.role(text)),"strong navigation semantics retain generic prompt role: "+text);
        }
        for(String text:new String[]{"摇一摇","翻转手机","扭动手机","查看详情","打开应用","点击领取奖励",
                "如何点击下载或打开第三方应用","点击下载或打开第三方应用领取奖励","跳转详情页","第三方应用",null})
            ok(!UiControlPolicy.navigationDisclosure(text),"gesture, generic action or body cannot become navigation disclosure: "+text);
    }
    static void youkuDisclosureChecks() {
        var n=youkuDisclosureFixture();var t=youkuTree(n,true);var c=NativeControlPolicy.findVerifiedAdScope(t);
        ok(c!=null && c.label.nodeIndex==13 && c.target==13 && c.currentLabelTouch,
                "complete separate creative and strongly disclosed template prove the live unclickable Skip Ad");
        ok(c.hit(t).nativeOnly && !SceneFramePolicy.allowsGesture(c.hit(t)),
                "Youku new semantic proof cannot authorize generic saved-point fallback");
        ok(NativeControlPolicy.find(t,true)==null && NativeControlPolicy.find(t,false)==null,
                "Youku global path cannot authorize unclickable label touch");
        ok(NativeControlPolicy.findVerifiedAdScope(youkuTree(n,false))==null,
                "Youku creative and strong text never promote partial scope completeness");
        ok(NativeControlPolicy.findVerifiedAdScope(new ControlTree.Snapshot("other.app",9,100,1216,2640,n,true))==null,
                "Youku no-badge proof cannot authorize another package");
        for(int at:new int[]{2,4}) {
            var subset=disclosureSubtree(n,at);
            ok(NativeControlPolicy.findVerifiedAdScope(youkuTree(subset,true))==null,
                    "complete Youku template without independent creative remains rejected: "+subset.size()+" nodes");
        }
        n=youkuDisclosureFixture();var old=n.get(16);
        n.set(16,disclosureNode(old.parent,0,old.box,false,"prompt",true,old.className,false,false));
        ok(NativeControlPolicy.findVerifiedAdScope(youkuTree(n,true))==null,
                "old capture generic prompt cannot be retroactively inferred as strong disclosure");
        ok(!UiControlPolicy.navigationDisclosure("摇一摇") && NativeControlPolicy.findVerifiedAdScope(youkuTree(n,true))==null,
                "shake-only prompt cannot supply a missing independent ad badge");
        n=youkuDisclosureFixture();old=n.get(13);
        n.set(13,disclosureNode(old.parent,0,old.box,false,UiControlPolicy.SKIP,true,old.className,false,false));
        ok(NativeControlPolicy.findVerifiedAdScope(youkuTree(n,true))==null,
                "plain Skip with third-party disclosure cannot borrow explicit Skip Ad proof");
        for(int at:new int[]{1,2,3,13,16}) {
            n=youkuDisclosureFixture();old=n.get(at);
            n.set(at,disclosureNode(old.parent,old.children,old.box,old.clickable,old.role,false,old.className,old.explicitSkipAd,old.navigationDisclosure));
            ok(NativeControlPolicy.findVerifiedAdScope(youkuTree(n,true))==null,"hidden or disabled Youku scene evidence stays rejected: "+at);
        }
        n=youkuDisclosureFixture();old=n.get(3);
        n.set(3,disclosureNode(1,0,new int[]{0,0,1216,1800},false,"",true,old.className,false,false));
        ok(NativeControlPolicy.findVerifiedAdScope(youkuTree(n,true))==null,"small creative image cannot prove current full-screen Youku scene");
        n=youkuDisclosureFixture();List<ControlTree.Node> merged=new ArrayList<>();
        // Move the creative leaf inside the actual control template, after its
        // parent, and retain accurate declared child counts for the whole tree.
        for(int i=0;i<n.size();i++)if(i!=3) {
            old=n.get(i);int parent=old.parent>3?old.parent-1:old.parent;
            int children=i==1?0:i==4?old.children+1:old.children;
            merged.add(disclosureNode(parent,children,old.box,old.clickable,old.role,old.visible,
                    old.className,old.explicitSkipAd,old.navigationDisclosure));
        }
        old=n.get(3);merged.add(disclosureNode(3,0,old.box,false,"",true,old.className,false,false));
        ok(NativeControlPolicy.findVerifiedAdScope(youkuTree(merged,true))==null,
                "creative merged into control template is not an independent full-screen sibling branch");
        n=youkuDisclosureFixture();old=n.get(0);n.set(0,disclosureNode(-1,3,old.box,false,"",true,old.className,false,false));
        ok(NativeControlPolicy.findVerifiedAdScope(youkuTree(n,true))==null,"declared missing scope edge cannot be hidden by full-screen creative");
        for(String role:new String[]{"nav",UiControlPolicy.CLOSE,UiControlPolicy.CLOSE_AD,UiControlPolicy.SKIP}) {
            n=youkuDisclosureFixture();old=n.get(10);
            n.set(10,disclosureNode(old.parent,0,old.box,true,role,true,"android.widget.TextView",UiControlPolicy.SKIP.equals(role),false));
            ok(NativeControlPolicy.findVerifiedAdScope(youkuTree(n,true))==null,"another current action or navigation blocks no-badge Youku proof: "+role);
        }
        n=youkuDisclosureFixture();old=n.get(16);
        n.set(16,disclosureNode(old.parent,0,new int[]{205,100,933,166},false,"prompt",true,old.className,false,true));
        ok(NativeControlPolicy.findVerifiedAdScope(youkuTree(n,true))==null,"disclosure overlapping control row cannot prove separate lower scene evidence");
        n=youkuDisclosureFixture();old=n.get(16);
        n.set(16,disclosureNode(old.parent,0,old.box,false,"prompt",true,"android.widget.ImageView",false,true));
        ok(NativeControlPolicy.findVerifiedAdScope(youkuTree(n,true))==null,"strong disclosure needs its actual current text node");
        n=youkuDisclosureFixture();List<ControlTree.Node> wrapped=new ArrayList<>();
        wrapped.add(disclosureNode(-1,1,n.get(0).box,false,"",true,"android.widget.FrameLayout",false,false));
        for(int i=0;i<n.size();i++) {
            old=n.get(i);wrapped.add(disclosureNode(i==0?0:old.parent+1,old.children,old.box,old.clickable,old.role,
                old.visible,old.className,old.explicitSkipAd,old.navigationDisclosure));
        }
        c=NativeControlPolicy.findVerifiedAdScope(youkuTree(wrapped,true));
        ok(c!=null && c.target==14,"complete additional outer wrapper preserves current Youku branch proof without fixed total count");
        n=youkuDisclosureFixture();old=n.get(4);n.set(4,disclosureNode(old.parent,4,old.box,false,"",true,old.className,false,false));
        n.add(disclosureNode(4,0,new int[]{100,400,200,500},false,"",true,"android.widget.ImageView",false,false));
        c=NativeControlPolicy.findVerifiedAdScope(youkuTree(n,true));
        ok(c!=null && c.target==13,"complete extra ordinary template decoration does not require an 18-node total");
        n=youkuDisclosureFixture();t=youkuTree(n,true);var initial=NativeControlPolicy.findVerifiedAdScope(t).hit(t);
        for(int at:new int[]{5,8,12,13}) {
            old=n.get(at);int[] box=old.box.clone();box[0]-=600;box[2]-=600;
            n.set(at,disclosureNode(old.parent,old.children,box,old.clickable,old.role,true,old.className,old.explicitSkipAd,old.navigationDisclosure));
        }
        t=youkuTree(n,true);c=NativeControlPolicy.findVerifiedAdScope(t);
        ok(c!=null && c.label.box[0]==353 && !NativeControlPolicy.matches(c,t,initial),
                "Youku label touch follows a moved live control chain and cannot reuse its saved point");
        for(double scale:new double[]{.5,1.5}) {
            List<ControlTree.Node> scaled=new ArrayList<>();
            for(var item:youkuDisclosureFixture()) {
                int[] box=item.box.clone();for(int i=0;i<4;i++)box[i]=(int)Math.round(box[i]*scale);
                scaled.add(disclosureNode(item.parent,item.children,box,item.clickable,item.role,item.visible,item.className,item.explicitSkipAd,item.navigationDisclosure));
            }
            c=NativeControlPolicy.findVerifiedAdScope(new ControlTree.Snapshot(YOUKU_PACKAGE,9,100,(int)(1216*scale),(int)(2640*scale),scaled,true));
            ok(c!=null && c.target==13,"current Youku semantic proof follows display scale "+scale);
        }
        n=youkuDisclosureFixture();
        for(int i=0;i<n.size();i++) {
            old=n.get(i);n.set(i,new ControlTree.Node(old.parent,old.children,old.box,old.clickable,old.role,"unrelated-identity-"+i,
                old.visible,old.className,"unrelated-id-"+i,false,old.enabled,old.declaredClickable,old.onScreen,old.explicitSkipAd,old.navigationDisclosure));
        }
        c=NativeControlPolicy.findVerifiedAdScope(youkuTree(n,true));
        ok(c!=null && c.target==13,"Youku view IDs and identity hashes are not authorization anchors");
        t=youkuTree(youkuDisclosureFixture(),true);
        ok(!NativeControlPolicy.animatedDecoration(t,3) && !NativeControlPolicy.animatedDecoration(t,15),
                "Youku proof image geometry remains exact during independent scope refresh");
        Object[] first=new Object[28];Arrays.fill(first,"fixed");first[3]="跳过广告";var current=first.clone();current[3]="跳过";
        ok(NativeControlPolicy.scopePropertyDifference(first,current,false,UiControlPolicy.SKIP)==3,
                "Skip Ad changing to plain Skip loses required live advertising semantics");
        current=first.clone();current[3]="跳过 广告";
        ok(NativeControlPolicy.scopePropertyDifference(first,current,false,UiControlPolicy.SKIP)<0,
                "independently explicit Skip Ad whitespace variation retains live advertising semantics");
    }
    static List<ControlTree.Node> fixture() {
        List<ControlTree.Node> n=new ArrayList<>();int[] full={0,0,1216,2640},button={866,70,1146,196};
        n.add(node(-1,1,full,false,"","decor"));
        n.add(node(0,2,full,false,"","junction"));
        n.add(node(1,2,full,false,"","ad-branch"));
        n.add(node(1,1,full,false,"","home-branch"));
        n.add(node(2,1,button,true,"","unused_res_a"));
        n.add(node(4,1,button,false,"","wrapper"));
        n.add(node(5,0,new int[]{963,100,1061,166},false,UiControlPolicy.CLOSE,"label"));
        n.add(node(2,0,new int[]{335,2174,881,2223},false,"prompt","disclosure"));
        n.add(node(3,0,new int[]{70,2524,202,2602},true,"nav","home-tab"));
        return n;
    }
    static ControlTree.Snapshot tree(List<ControlTree.Node> n){return new ControlTree.Snapshot("test.app",9,100,1216,2640,n);}
    static ControlTree.Snapshot observed(List<ControlTree.Node> n,long time,boolean complete) {
        return new ControlTree.Snapshot("test.app",9,time,1216,2640,n,complete);
    }
    static List<ControlTree.Node> goneTree() {
        List<ControlTree.Node> nodes=new ArrayList<>();
        nodes.add(node(-1,1,new int[]{0,0,1216,2640},false,"","home-root"));
        nodes.add(node(0,0,new int[]{70,2524,202,2602},true,"nav","home-tab"));
        return nodes;
    }
    static NativeTreeObservation observation(BilibiliVisualMatcher.Hit hit) {
        return new NativeTreeObservation("test.app",9,1000,hit);
    }
    static void observationChecks(BilibiliVisualMatcher.Hit hit) {
        var gone=goneTree();var session=observation(hit);
        ok(session.observe(observed(gone,1119,true),1119)==NativeTreeObservation.UNKNOWN,
                "tree before post-click settling time cannot verify disappearance");
        ok(session.observe(observed(gone,1120,true),1120)==NativeTreeObservation.UNKNOWN,
                "one complete absent tree is insufficient");
        ok(session.observe(observed(gone,1239,true),1239)==NativeTreeObservation.UNKNOWN,
                "absent samples closer than 120 ms cannot verify disappearance");
        ok(session.observe(observed(gone,1240,true),1240)==NativeTreeObservation.GONE,
                "two complete fresh absent trees verify only tree disappearance");

        session=observation(hit);var once=observed(gone,1120,true);
        ok(session.observe(once,1120)==NativeTreeObservation.UNKNOWN &&
                session.observe(once,1240)==NativeTreeObservation.UNKNOWN,
                "reusing one tree does not become a second disappearance sample");
        ok(session.observe(observed(gone,1240,true),1240)==NativeTreeObservation.GONE,
                "a new tree can finish disappearance after a repeated sample");

        session=observation(hit);
        ok(session.observe(observed(gone,1120,false),1120)==NativeTreeObservation.UNKNOWN &&
                session.observe(observed(gone,1240,false),1240)==NativeTreeObservation.UNKNOWN,
                "incomplete trees cannot verify disappearance");
        ok(session.observe(observed(Collections.emptyList(),1360,true),1360)==NativeTreeObservation.UNKNOWN &&
                session.observe(observed(Collections.emptyList(),1480,true),1480)==NativeTreeObservation.UNKNOWN,
                "empty provider is unknown even when traversal reports complete");
        ok(session.observe(observed(gone,1600,true),1600)==NativeTreeObservation.UNKNOWN &&
                session.observe(observed(gone,1720,true),1720)==NativeTreeObservation.GONE,
                "clear samples after empty provider still need two observations");
        session=observation(hit);
        ok(session.observe(observed(gone,1120,true),1120)==NativeTreeObservation.UNKNOWN &&
                session.observe(observed(gone,1240,false),1240)==NativeTreeObservation.UNKNOWN &&
                session.observe(observed(gone,1360,true),1360)==NativeTreeObservation.UNKNOWN &&
                session.observe(observed(gone,1480,true),1480)==NativeTreeObservation.GONE,
                "incomplete tree interrupts an absence sequence instead of supplying confirmation");

        session=observation(hit);
        ok(session.observe(observed(gone,1120,true),1371)==NativeTreeObservation.UNKNOWN &&
                session.observe(observed(gone,1500,true),1499)==NativeTreeObservation.UNKNOWN,
                "stale and future trees cannot verify disappearance");
        ok(session.observe(new ControlTree.Snapshot("other.app",9,1500,1216,2640,gone,true),1500)==NativeTreeObservation.UNKNOWN &&
                session.observe(new ControlTree.Snapshot("test.app",10,1620,1216,2640,gone,true),1620)==NativeTreeObservation.UNKNOWN &&
                session.observe(new ControlTree.Snapshot("test.app",9,1740,2640,1216,gone,true),1740)==NativeTreeObservation.UNKNOWN,
                "package epoch and display changes invalidate native observation");

        session=observation(hit);var hidden=fixture();var old=hidden.get(6);
        hidden.set(6,new ControlTree.Node(old.parent,old.children,old.box,old.clickable,"",old.identity,true));
        ok(session.observe(observed(hidden,1120,true),1120)==NativeTreeObservation.UNKNOWN &&
                session.observe(observed(hidden,1240,true),1240)==NativeTreeObservation.UNKNOWN,
                "same current chain without semantics is unknown rather than gone");
        hidden.set(6,new ControlTree.Node(old.parent,old.children,old.box,old.clickable,old.role,old.identity,false));
        ok(session.observe(observed(hidden,1360,true),1360)==NativeTreeObservation.UNKNOWN,
                "same invisible chain is unknown rather than gone");
        session=observation(hit);
        ok(session.observe(observed(gone,1120,true),1120)==NativeTreeObservation.UNKNOWN &&
                session.observe(observed(hidden,1240,true),1240)==NativeTreeObservation.UNKNOWN &&
                session.observe(observed(gone,1360,true),1360)==NativeTreeObservation.UNKNOWN &&
                session.observe(observed(gone,1480,true),1480)==NativeTreeObservation.GONE,
                "concealed old chain interrupts disappearance evidence");

        session=observation(hit);var moved=fixture();var label=moved.remove(6);
        moved.add(node(5,0,new int[]{210,500,310,566},false,label.role,label.identity));
        ok(session.observe(observed(moved,1120,true),1120)==NativeTreeObservation.PRESENT,
                "visible same-role target remains present after moving and renumbering");
        var unrelated=goneTree();unrelated.add(node(0,0,new int[]{20,200,110,250},true,hit.rule,"different-close"));
        ok(session.observe(observed(unrelated,1240,true),1240)==NativeTreeObservation.PRESENT,
                "any current visible same-role control prevents disappearance");
        ok(NativeControlPolicy.find(observed(unrelated,1360,true),true)==null &&
                session.observe(observed(unrelated,1360,true),1360)==NativeTreeObservation.PRESENT,
                "click-policy rejection cannot be mistaken for target disappearance");
        ok(session.observe(observed(unrelated,1480,false),1480)==NativeTreeObservation.PRESENT,
                "a visible same-role control is presence evidence even in a partial tree");
        ok(session.observe(observed(gone,1600,true),1600)==NativeTreeObservation.UNKNOWN &&
                session.observe(observed(gone,1720,true),1720)==NativeTreeObservation.GONE,
                "presence resets absence and requires two later clear trees");

        session=observation(hit);
        ok(!session.expired(3200) && session.expired(3201) && session.expired(999),
                "native observation lasts at most 2200 ms after accepted click");
        ok(session.observe(observed(gone,3201,true),3201)==NativeTreeObservation.UNKNOWN,
                "expired observation cannot verify disappearance");

        session=observation(hit);
        ok(session.observe(observed(fixture(),3080,true),3080)==NativeTreeObservation.PRESENT &&
                session.observe(observed(fixture(),3201,true),3201)==NativeTreeObservation.UNKNOWN &&
                session.lastFreshState(3201)==NativeTreeObservation.PRESENT,
                "timeout log retains last fresh presence without extending observation");
        ok(session.lastFreshState(3330)==NativeTreeObservation.PRESENT &&
                session.lastFreshState(3331)==NativeTreeObservation.UNKNOWN &&
                session.lastFreshState(3079)==NativeTreeObservation.UNKNOWN,
                "timeout presence follows snapshot freshness and rejects future evidence");

        session=observation(hit);
        session.observe(observed(fixture(),3080,true),3080);
        ok(session.observe(observed(gone,3160,true),3160)==NativeTreeObservation.UNKNOWN &&
                session.lastFreshState(3201)==NativeTreeObservation.UNKNOWN,
                "one later absent tree invalidates cached presence for timeout log");

        session=observation(hit);
        session.observe(observed(fixture(),3080,true),3080);
        ok(session.observe(observed(hidden,3160,true),3160)==NativeTreeObservation.UNKNOWN &&
                session.lastFreshState(3201)==NativeTreeObservation.UNKNOWN,
                "later concealed chain invalidates cached presence for timeout log");

        session=observation(hit);
        session.observe(observed(fixture(),3080,true),3080);
        ok(session.observe(observed(gone,3160,false),3160)==NativeTreeObservation.UNKNOWN &&
                session.lastFreshState(3201)==NativeTreeObservation.UNKNOWN,
                "later incomplete tree cannot retain earlier presence for timeout log");

        session=observation(hit);
        session.observe(observed(fixture(),3080,true),3080);
        ok(session.observe(observed(Collections.emptyList(),3160,true),3160)==NativeTreeObservation.UNKNOWN &&
                session.lastFreshState(3201)==NativeTreeObservation.UNKNOWN,
                "later empty provider cannot retain earlier presence for timeout log");
    }
    static List<ControlTree.Node> fullScreenSkipFixture() {
        List<ControlTree.Node> n=new ArrayList<>();int[] full={0,0,1216,2640};
        n.add(node(-1,2,full,false,"","decor"));
        n.add(node(0,0,full,false,"","empty-decor-layer"));
        n.add(node(0,1,full,false,"","shared-home-ad"));
        n.add(node(2,2,full,false,"","layer-junction"));
        n.add(node(3,1,full,false,"","ad-layer"));
        n.add(node(3,1,full,false,"","home-layer"));
        n.add(node(4,2,full,false,"","ad-content"));
        n.add(node(6,2,full,false,"","flattened-control-group"));
        n.add(node(6,0,new int[]{177,2113,961,2188},false,"prompt","ad-prompt"));
        n.add(node(7,0,new int[]{999,161,1174,273},true,UiControlPolicy.SKIP,"skip"));
        n.add(node(7,0,new int[]{42,182,119,229},false,"ad","ad-label"));
        n.add(node(5,0,new int[]{70,2524,202,2602},true,"nav","home-tab"));
        return n;
    }
    static void fullScreenSkipChecks() {
        var n=fullScreenSkipFixture();var t=tree(n);var selected=NativeControlPolicy.find(t,true);
        ok(selected!=null && selected.target==9,"explicit skip and ad in full-screen subtree separated from home layer");
        ok(selected.hit(t).nativeOnly && !SceneFramePolicy.allowsGesture(selected.hit(t)),
                "full-screen subtree proof authorizes only native ACTION_CLICK");
        ok(NativeControlPolicy.find(t,false)==null,"full-screen skip proof remains opening-only");
        ok(NativeControlPolicy.find(observed(n,100,false),true)==null,"incomplete full-screen subtree cannot authorize skip");

        n=fullScreenSkipFixture();n.set(9,node(7,0,n.get(9).box,true,UiControlPolicy.CLOSE,"plain-close"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"new full-screen subtree proof does not broaden plain close");
        n.set(9,node(7,0,n.get(9).box,true,UiControlPolicy.CLOSE_AD,"explicit-close-ad"));
        selected=NativeControlPolicy.find(tree(n),false);
        ok(selected!=null && selected.target==9,"explicit close-ad retains existing nonopening semantics");

        n=fullScreenSkipFixture();n.set(10,node(7,0,n.get(10).box,false,"prompt","prompt-without-ad"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"prompts without explicit ad do not prove full-screen skip");
        n.set(10,new ControlTree.Node(7,0,n.get(10).box,false,"ad","hidden-ad",false));
        ok(NativeControlPolicy.find(tree(n),true)==null,"invisible ad cannot prove full-screen skip");

        n=fullScreenSkipFixture();n.set(7,node(6,1,n.get(7).box,false,"","control-group"));
        n.set(5,node(3,2,n.get(5).box,false,"","home-layer"));
        n.set(10,node(5,0,n.get(10).box,false,"ad","home-feed-ad"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"ordinary home mixed ad does not authorize skip in another subtree");

        n=fullScreenSkipFixture();n.set(7,node(6,3,n.get(7).box,false,"","control-group"));
        n.add(node(7,0,new int[]{300,500,450,570},true,"nav","inside-ad-navigation"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"visible navigation inside ad subtree remains protected");
        n=fullScreenSkipFixture();n.set(6,node(4,3,n.get(6).box,false,"","ad-content"));
        n.add(node(6,0,new int[]{300,500,450,570},true,"nav","small-peer-navigation"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"small navigation sibling is not an independent full-screen home layer");

        n=fullScreenSkipFixture();n.set(11,node(5,0,n.get(11).box,true,"","no-home-tab"));
        n.set(3,node(2,2,n.get(3).box,false,"nav","ancestor-navigation"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"navigation on common ancestor is not a separate home sibling");
        n=fullScreenSkipFixture();n.set(3,node(2,2,n.get(3).box,false,"nav","ancestor-navigation"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"navigation on common ancestor blocks skip even with full-screen home sibling");

        n=fullScreenSkipFixture();n.set(5,node(3,1,new int[]{0,2363,1216,2640},false,"","bottom-bar"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"navigation bar alone does not prove independent home layer");
        for(int[] box:new int[][]{new int[]{0,0,1216,1848},new int[]{-10,0,1206,2640}}) {
            n=fullScreenSkipFixture();
            for(int at:new int[]{4,6,7}){var old=n.get(at);n.set(at,node(old.parent,old.children,box,old.clickable,old.role,old.identity));}
            ok(NativeControlPolicy.find(tree(n),true)==null,"noncovering or offscreen ad subtree remains protected "+Arrays.toString(box));
        }
        n=fullScreenSkipFixture();n.set(10,node(7,0,new int[]{-42,182,77,229},false,"ad","outside-scope-ad"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"ad cue outside current scope bounds cannot prove skip");

        n=fullScreenSkipFixture();n.set(9,node(7,0,n.get(9).box,false,UiControlPolicy.SKIP,"nonclickable-skip"));
        n.set(7,node(6,2,n.get(7).box,true,"","giant-clickable-ad"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"full-screen semantics cannot authorize giant clickable ancestor");
        n=fullScreenSkipFixture();n.set(7,node(6,3,n.get(7).box,false,"","control-group"));
        n.add(node(7,0,new int[]{700,170,850,260},true,UiControlPolicy.SKIP,"second-skip"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"two full-screen skip targets remain ambiguous");
    }
    static List<ControlTree.Node> scopedAdFixture() {
        return new ArrayList<>(List.of(node(-1,2,new int[]{0,0,1216,2640},false,"","fresh-ad-scope"),
            node(0,0,new int[]{929,98,1153,196},true,UiControlPolicy.SKIP,"skip"),
            node(0,0,new int[]{119,2462,189,2509},false,"ad","explicit-ad")));
    }
    static void scopedAdChecks() {
        var n=scopedAdFixture();var scoped=tree(n);var candidate=NativeControlPolicy.findVerifiedAdScope(scoped);
        ok(candidate!=null && candidate.target==1,"independently complete full-screen ad scope resolves unique native skip");
        var global=observed(fullScreenSkipFixture(),100,false);
        ok(NativeControlPolicy.find(global,true)==null && NativeControlPolicy.findVerifiedAdScope(global)==null && !global.complete,
                "independent scope proof never promotes partial global tree completeness");
        ok(NativeControlPolicy.findVerifiedAdScope(observed(n,100,false))==null,"partial independently read scope remains unknown");
        n=scopedAdFixture();n.set(0,node(-1,3,n.get(0).box,false,"","missing-child-scope"));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,"declared unavailable scope child cannot be hidden by complete flag");
        n=scopedAdFixture();n.set(1,node(1,0,n.get(1).box,true,UiControlPolicy.SKIP,"cycle"));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,"scope containing self-cycle is rejected");
        n=scopedAdFixture();n.set(0,node(1,2,n.get(0).box,false,"","ancestor-cycle"));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,"scope root connected back to descendant is rejected");
        n=scopedAdFixture();n.set(2,node(-1,0,n.get(2).box,false,"ad","separate-root"));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,"unrelated forest root cannot supply scope ad cue");
        n=scopedAdFixture();n.set(0,node(-1,3,n.get(0).box,false,"","scope"));
        n.add(node(0,0,new int[]{70,2524,202,2602},true,"nav","home-tab"));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,"visible home navigation inside independent scope blocks skip");
        n=scopedAdFixture();n.set(2,node(0,0,n.get(2).box,false,"prompt","no-explicit-ad"));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,"prompt without explicit ad cannot prove scoped skip");
        n.set(2,new ControlTree.Node(0,0,n.get(2).box,false,"ad","hidden-ad",false));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,"hidden ad cue cannot prove scoped skip");
        for(int[] box:new int[][]{new int[]{0,0,1216,1800},new int[]{-10,0,1206,2640}}) {
            n=scopedAdFixture();n.set(0,node(-1,2,box,false,"","unsafe-scope"));
            ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,"nonfull or offscreen independent scope rejected "+Arrays.toString(box));
        }
        n=scopedAdFixture();n.set(0,node(-1,3,n.get(0).box,false,"","scope"));
        n.add(node(0,0,new int[]{600,100,800,196},true,UiControlPolicy.SKIP,"second-skip"));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,"two distinct independently proven scoped skip targets remain ambiguous");
        n=scopedAdFixture();n.set(0,node(-1,2,n.get(0).box,true,"","giant-clickable-scope"));
        n.set(1,node(0,0,n.get(1).box,false,UiControlPolicy.SKIP,"nonclickable-label"));
        candidate=NativeControlPolicy.findVerifiedAdScope(tree(n));
        ok(candidate!=null && candidate.target==1 && candidate.currentLabelTouch,
                "independent explicit-ad scope uses the current Skip label instead of its giant clickable parent");
        n=scopedAdFixture();n.set(1,node(0,0,n.get(1).box,true,UiControlPolicy.CLOSE_AD,"close-ad"));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))!=null,"complete ad scope supports explicit close-ad action");
        n.set(1,node(0,0,n.get(1).box,true,UiControlPolicy.CLOSE,"ordinary-close"));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,"scoped discovery does not accept ordinary close without explicit action");
        n=scopedAdFixture();n.set(0,node(-1,3,n.get(0).box,false,"","scope"));
        n.add(node(0,0,new int[]{200,400,800,1400},false,"","animated-content"));
        ok(NativeControlPolicy.animatedDecoration(tree(n),3),"unrelated nonclickable leaf may animate within a complete ad scope");
        ok(!NativeControlPolicy.animatedDecoration(tree(n),0) && !NativeControlPolicy.animatedDecoration(tree(n),1) &&
                !NativeControlPolicy.animatedDecoration(tree(n),2),"root action and ad cue geometry must remain fixed");
        n.set(3,node(0,0,n.get(3).box,true,"","clickable-content"));
        ok(!NativeControlPolicy.animatedDecoration(tree(n),3),"clickable ad content cannot bypass geometry validation");
        n.set(3,node(0,0,n.get(3).box,false,"nav","navigation"));
        ok(!NativeControlPolicy.animatedDecoration(tree(n),3),"navigation geometry remains protected");
        ok(!NativeControlPolicy.animatedDecoration(observed(n,100,false),3),"incomplete scopes cannot tolerate geometry changes");
        n=scopedAdFixture();n.set(1,new ControlTree.Node(0,0,n.get(1).box,true,UiControlPolicy.SKIP,"hidden-skip",false));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,"hidden or disabled scoped skip cannot authorize action");
        candidate=NativeControlPolicy.findVerifiedAdScope(scoped);
        ok(candidate.hit(scoped).nativeOnly && !SceneFramePolicy.allowsGesture(candidate.hit(scoped)),
                "scoped proof does not unlock the generic native coordinate fallback");
    }
    static void scopedLabelTouchChecks() {
        var n=scopedAdFixture();var old=n.get(1);
        n.set(1,node(0,0,old.box,false,old.role,old.identity));
        var t=tree(n);var c=NativeControlPolicy.findVerifiedAdScope(t);
        ok(c!=null && c.currentLabelTouch && c.target==1 && c.label.nodeIndex==1,
                "complete explicit-ad scope can propose its unique unclickable live Skip label");
        ok(c.hit(t).nativeOnly && !SceneFramePolicy.allowsGesture(c.hit(t)),
                "scope label-touch proof cannot enable the generic gesture fallback");
        ok(NativeControlPolicy.find(t,true)==null && NativeControlPolicy.find(t,false)==null,
                "global native selection never proposes label-touch even for explicit ads");
        ok(NativeControlPolicy.findVerifiedAdScope(observed(n,100,false))==null,
                "incomplete explicit-ad scope cannot propose label-touch");
        n.set(2,node(0,0,n.get(2).box,false,"prompt","not-ad"));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,
                "scope label-touch needs an explicit ad cue rather than a prompt");
        n=scopedAdFixture();old=n.get(1);n.set(1,node(0,0,old.box,false,UiControlPolicy.CLOSE_AD,old.identity));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,
                "explicit Close-ad cannot borrow the Skip label-touch fallback");
        n.set(1,node(0,0,old.box,false,UiControlPolicy.CLOSE,old.identity));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,
                "ordinary Close cannot borrow the Skip label-touch fallback");
        n=scopedAdFixture();old=n.get(1);n.set(1,node(0,0,old.box,false,"",old.identity));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,
                "unlabeled or unknown native nodes cannot propose scope label-touch");
        n=scopedAdFixture();old=n.get(1);n.set(1,node(0,0,old.box,false,old.role,old.identity));
        n.set(0,node(-1,3,n.get(0).box,false,"",n.get(0).identity));
        n.add(node(0,0,new int[]{70,2524,202,2602},true,"nav","home-tab"));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,
                "scope label-touch cannot bypass current visible home navigation");
        n=scopedAdFixture();old=n.get(1);n.set(1,node(0,0,old.box,false,old.role,old.identity));
        n.set(0,node(-1,3,n.get(0).box,false,"",n.get(0).identity));
        n.add(node(0,0,new int[]{30,2240,230,2330},false,UiControlPolicy.SKIP,"second-label"));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,
                "two independent current Skip labels cannot supply a unique touch target");
        n=scopedAdFixture();old=n.get(1);n.set(1,new ControlTree.Node(0,0,old.box,false,old.role,old.identity,false));
        ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,
                "hidden or disabled Skip label cannot supply touch evidence");
        for(int[] box:new int[][]{new int[]{0,0,1216,300},new int[]{-5,98,219,196}}) {
            n=scopedAdFixture();old=n.get(1);n.set(1,node(0,0,box,false,old.role,old.identity));
            ok(NativeControlPolicy.findVerifiedAdScope(tree(n))==null,
                    "oversized or offscreen Skip label cannot propose touch "+Arrays.toString(box));
        }
        n=new ArrayList<>(List.of(
            node(-1,2,new int[]{0,0,1216,2640},false,"","scope"),
            node(0,1,new int[]{866,70,1146,196},true,"","unsafe-parent"),
            node(0,0,new int[]{119,2462,189,2509},false,"ad","ad-cue"),
            node(1,0,new int[]{963,100,1008,126},false,UiControlPolicy.SKIP,"label")));
        c=NativeControlPolicy.findVerifiedAdScope(tree(n));
        ok(c!=null && c.currentLabelTouch && c.target==3 && NativeControlPolicy.target(tree(n),3)<0,
                "scope touch remains on the live label when a small clickable parent is disproportionately large");
        n=scopedAdFixture();old=n.get(1);n.set(1,node(0,0,new int[]{30,2240,230,2330},false,old.role,old.identity));
        t=tree(n);c=NativeControlPolicy.findVerifiedAdScope(t);
        ok(c!=null && c.currentLabelTouch && c.label.box[0]==30 && c.label.box[1]==2240 && c.target==1,
                "scope label-touch follows the current lower-left Skip bounds without saved coordinates");
        ok(!NativeControlPolicy.matches(c,t,new BilibiliVisualMatcher.Hit(UiControlPolicy.SKIP,1041,147,1,1216,2640)
                .withTextBounds(929,98,1153,196)),
                "previous Skip geometry cannot replace current scope label-touch bounds");
        c=NativeControlPolicy.findVerifiedAdScope(tree(scopedAdFixture()));
        ok(c!=null && !c.currentLabelTouch && c.target==1,
                "an existing safe clickable Skip retains native click selection");
        n=mobileStartupFixture();old=n.get(7);n.set(7,node(4,0,old.box,false,old.role,old.identity));
        ok(NativeControlPolicy.findVerifiedAdScope(mobileTree(n,true))==null,
                "Mobile structural startup proof without explicit ad cannot borrow label-touch");
    }
    static final String MOBILE_PACKAGE="com.greenpoint.android.mc10086.activity";
    static String mobileId(String type,String id) {
        return JointControlModel.digest(type+"/"+(id.isEmpty()?"":MOBILE_PACKAGE+":id/"+id));
    }
    /** Nine current nodes observed in both complete and partially captured Mobile startup frames. */
    static List<ControlTree.Node> mobileStartupFixture() {
        return new ArrayList<>(List.of(
            node(-1,2,new int[]{0,0,1216,2577},true,"",mobileId("android.widget.LinearLayout","")),
            node(0,3,new int[]{0,0,1216,2152},true,"",mobileId("android.widget.FrameLayout","video_layout")),
            node(0,1,new int[]{0,2152,1216,2577},false,"",mobileId("android.widget.FrameLayout","logo_layout")),
            node(1,0,new int[]{0,0,1216,2152},false,"",mobileId("android.widget.ImageView","img_video")),
            node(1,1,new int[]{917,114,1168,203},false,"",mobileId("android.widget.LinearLayout","ll_top_right")),
            node(1,1,new int[]{263,1882,952,2025},true,"",mobileId("android.widget.FrameLayout","turn_to_third_page")),
            node(2,0,new int[]{0,2230,1216,2499},false,"",mobileId("android.widget.ImageView","logo")),
            node(4,0,new int[]{965,114,1168,203},true,ControlTree.role("2 跳过"),mobileId("android.widget.TextView","video_time_skip")),
            node(5,0,new int[]{385,1919,830,1987},false,"",mobileId("android.widget.TextView","tv_turn_to_third_page"))));
    }
    static ControlTree.Snapshot mobileTree(List<ControlTree.Node> n,boolean complete) {
        return new ControlTree.Snapshot(MOBILE_PACKAGE,9,100,1216,2640,n,complete);
    }
    static void mobileStartupChecks() {
        var n=mobileStartupFixture();var t=mobileTree(n,true);
        var c=NativeControlPolicy.findVerifiedAdScope(t);
        ok(c!=null && !c.currentLabelTouch && c.label.nodeIndex==7 && c.target==7 && UiControlPolicy.SKIP.equals(c.label.action),
                "observed complete Mobile startup video and logo layer proves its current native Skip");
        ok(NativeControlPolicy.find(t,true)==null && NativeControlPolicy.find(t,false)==null,
                "Mobile proof is available only to the independent scope path");
        ok(NativeControlPolicy.findVerifiedAdScope(new ControlTree.Snapshot("other.app",9,100,1216,2640,n,true))==null,
                "Mobile class and view IDs cannot authorize another package");
        ok(NativeControlPolicy.findVerifiedAdScope(mobileTree(n,false))==null,
                "Mobile startup IDs never promote an incomplete capture");
        ok(NativeControlPolicy.findVerifiedAdScope(mobileTree(goneTree(),true))==null,
                "ordinary Mobile home navigation cannot borrow startup proof");
        for(int at:new int[]{0,1,2,3,4,6,7}) {
            n=mobileStartupFixture();var old=n.get(at);
            n.set(at,node(old.parent,old.children,old.box,old.clickable,old.role,"different-current-class-or-id"));
            ok(NativeControlPolicy.findVerifiedAdScope(mobileTree(n,true))==null,
                    "Mobile startup requires every observed class and ID anchor "+at);
        }
        n=mobileStartupFixture();var old=n.get(7);
        n.set(4,node(1,0,n.get(4).box,false,"",n.get(4).identity));
        n.set(5,node(1,2,n.get(5).box,true,"",n.get(5).identity));
        n.set(7,node(5,0,old.box,true,old.role,old.identity));
        ok(NativeControlPolicy.findVerifiedAdScope(mobileTree(n,true))==null,
                "Mobile IDs in a different complete parent chain remain protected");
        for(String role:new String[]{UiControlPolicy.CLOSE,UiControlPolicy.CLOSE_AD,""}) {
            n=mobileStartupFixture();old=n.get(7);n.set(7,node(4,0,old.box,true,role,old.identity));
            ok(NativeControlPolicy.findVerifiedAdScope(mobileTree(n,true))==null,
                    "Mobile startup exception accepts explicit Skip only: "+role);
        }
        for(int at:new int[]{3,6,7}) {
            n=mobileStartupFixture();old=n.get(at);
            n.set(at,new ControlTree.Node(old.parent,old.children,old.box,old.clickable,old.role,old.identity,false));
            ok(NativeControlPolicy.findVerifiedAdScope(mobileTree(n,true))==null,
                    "hidden or disabled Mobile proof node cannot authorize "+at);
        }
        n=mobileStartupFixture();old=n.get(7);n.set(7,node(4,0,old.box,false,old.role,old.identity));
        ok(NativeControlPolicy.findVerifiedAdScope(mobileTree(n,true))==null,
                "Mobile startup cannot substitute its giant clickable video for an unclickable Skip");
        n=mobileStartupFixture();old=n.get(5);n.set(5,node(1,2,old.box,old.clickable,old.role,old.identity));
        n.add(node(5,0,new int[]{650,1900,800,1980},true,UiControlPolicy.SKIP,"second-skip"));
        ok(NativeControlPolicy.findVerifiedAdScope(mobileTree(n,true))==null,
                "another current Skip target remains ambiguous in a Mobile startup layer");
        n=mobileStartupFixture();old=n.get(8);n.set(8,node(old.parent,0,old.box,false,"nav",old.identity));
        ok(NativeControlPolicy.findVerifiedAdScope(mobileTree(n,true))==null,
                "Mobile startup evidence cannot bypass visible navigation");
        n=mobileStartupFixture();old=n.get(0);n.set(0,node(-1,3,old.box,true,"",old.identity));
        ok(NativeControlPolicy.findVerifiedAdScope(mobileTree(n,true))==null,
                "Mobile startup proof cannot hide an unavailable declared child");
        n=mobileStartupFixture();old=n.get(0);n.set(0,node(-1,2,new int[]{0,0,1216,1800},true,"",old.identity));
        ok(NativeControlPolicy.findVerifiedAdScope(mobileTree(n,true))==null,
                "Mobile startup IDs do not prove a nonfull screen layer");
        n=mobileStartupFixture();old=n.get(3);n.set(3,node(1,0,new int[]{0,0,1216,1800},false,"",old.identity));
        ok(NativeControlPolicy.findVerifiedAdScope(mobileTree(n,true))==null,
                "Mobile startup requires its current video image to fill the video container");
        n=mobileStartupFixture();n.set(4,node(1,1,new int[]{600,500,851,589},false,"",n.get(4).identity));
        n.set(7,node(4,0,new int[]{648,500,851,589},true,UiControlPolicy.SKIP,n.get(7).identity));
        c=NativeControlPolicy.findVerifiedAdScope(mobileTree(n,true));
        ok(c!=null && c.target==7 && c.label.box[0]==648,
                "Mobile startup uses the moved live Skip bounds without a saved screen position");
        for(double scale:new double[]{.5,1.5}) {
            List<ControlTree.Node> scaled=new ArrayList<>();
            for(var item:mobileStartupFixture()) {
                int[] b=item.box.clone();for(int i=0;i<4;i++)b[i]=(int)Math.round(b[i]*scale);
                scaled.add(node(item.parent,item.children,b,item.clickable,item.role,item.identity));
            }
            c=NativeControlPolicy.findVerifiedAdScope(new ControlTree.Snapshot(MOBILE_PACKAGE,9,100,
                    (int)(1216*scale),(int)(2640*scale),scaled,true));
            ok(c!=null && c.target==7,"Mobile startup structure survives current display scale "+scale);
        }
        t=mobileTree(mobileStartupFixture(),true);
        ok(!NativeControlPolicy.animatedDecoration(t,3) && !NativeControlPolicy.animatedDecoration(t,6) &&
                NativeControlPolicy.animatedDecoration(t,8),
                "Mobile video and logo proof geometry stays exact while unrelated decoration may animate");
    }
    static final String BAIDU_MAP_PACKAGE="com.baidu.BaiduMap";
    static ControlTree.Node baiduNode(int parent,int children,int[] box,boolean clickable,String role,
            boolean visible,String type,boolean countdown) {
        return new ControlTree.Node(parent,children,box,clickable,role,JointControlModel.digest(type+"/"),visible,type,"",countdown);
    }
    /** Seven actual leaf children were present under the captured ad RelativeLayout.
     * This fixture models its separately complete scope; the original page stays partial. */
    static List<ControlTree.Node> baiduStartupFixture() {
        int[] full={0,0,1216,2577};
        return new ArrayList<>(List.of(
            baiduNode(-1,7,full,false,"",true,"android.widget.RelativeLayout",false),
            baiduNode(0,0,full,true,"",true,"android.widget.ImageView",false),
            baiduNode(0,0,full,false,"",true,"android.view.View",false),
            baiduNode(0,0,full,false,"",true,"android.widget.ImageView",false),
            baiduNode(0,0,new int[]{53,70,291,175},false,"",true,"android.widget.ImageView",false),
            baiduNode(0,0,new int[]{509,84,859,161},false,"",true,"android.widget.TextView",false),
            baiduNode(0,0,new int[]{911,70,1163,175},true,ControlTree.role("跳过 01"),true,"android.widget.TextView",
                UiControlPolicy.explicitSkipCountdown("跳过 01")),
            baiduNode(0,0,new int[]{608,2475,608,2475},false,"",false,"android.widget.ImageView",false)));
    }
    static ControlTree.Snapshot baiduTree(List<ControlTree.Node> nodes,boolean complete) {
        return new ControlTree.Snapshot(BAIDU_MAP_PACKAGE,9,100,1216,2640,nodes,complete);
    }
    static ControlTree.Node baiduChange(ControlTree.Node n,int[] box,boolean clickable,String role,boolean visible,
            String type,boolean countdown) {
        return baiduNode(n.parent,n.children,box,clickable,role,visible,type,countdown);
    }
    static void explicitCountdownChecks() {
        for(String text:new String[]{"跳过 01","０１ 跳过","Skip 5s","5 s | SKIP","跳过:0秒","跳过 99","2秒·跳过"})
            ok(UiControlPolicy.explicitSkipCountdown(text),"one explicit Skip countdown field: "+text);
        for(String text:new String[]{"01","1秒","跳过","Skip","广告跳过01","跳过教程01","跳过 100","2跳过3","关闭 01",null})
            ok(!UiControlPolicy.explicitSkipCountdown(text),"unrelated or missing countdown field: "+text);
        ok(!UiControlPolicy.explicitSkipCountdown("跳过") && !UiControlPolicy.explicitSkipCountdown("01"),
                "separate Skip and numeric fields cannot fabricate a countdown");
    }
    static void baiduStartupChecks() {
        var n=baiduStartupFixture();var t=baiduTree(n,true);var c=NativeControlPolicy.findVerifiedAdScope(t);
        ok(c!=null && c.label.nodeIndex==6 && c.target==6 && !c.currentLabelTouch,
                "complete Baidu timed creative layer proves its directly clickable current Skip");
        ok(c.hit(t).nativeOnly && !SceneFramePolicy.allowsGesture(c.hit(t)),
                "Baidu scope proof authorizes native click without coordinate fallback");
        ok(NativeControlPolicy.find(t,true)==null && NativeControlPolicy.find(t,false)==null,
                "Baidu structural proof remains exclusive to separately captured scopes");
        ok(NativeControlPolicy.findVerifiedAdScope(baiduTree(n,false))==null && NativeControlPolicy.find(baiduTree(n,false),true)==null,
                "Baidu complete-looking subtree never promotes partial page completeness");
        ok(NativeControlPolicy.findVerifiedAdScope(new ControlTree.Snapshot("other.app",9,100,1216,2640,n,true))==null,
                "Baidu scene proof cannot authorize another package");
        List<ControlTree.Node> wrapped=new ArrayList<>();
        wrapped.add(baiduNode(-1,1,n.get(0).box,true,"",true,"android.widget.FrameLayout",false));
        for(int i=0;i<n.size();i++) {
            var old=n.get(i);
            wrapped.add(baiduNode(i==0?0:old.parent+1,old.children,old.box,old.clickable,old.role,old.visible,old.className,old.skipCountdown));
        }
        c=NativeControlPolicy.findVerifiedAdScope(baiduTree(wrapped,true));
        ok(c!=null && c.target==7,"observed unary full-screen wrapper preserves complete Baidu scope proof");
        n=baiduStartupFixture();var old=n.get(6);
        n.set(6,baiduChange(old,old.box,true,old.role,true,old.className,false));
        ok(NativeControlPolicy.findVerifiedAdScope(baiduTree(n,true))==null,
                "generic Skip without an independently explicit countdown cannot prove Baidu creative scene");
        for(String role:new String[]{"",UiControlPolicy.CLOSE,UiControlPolicy.CLOSE_AD}) {
            n=baiduStartupFixture();old=n.get(6);n.set(6,baiduChange(old,old.box,true,role,true,old.className,true));
            ok(NativeControlPolicy.findVerifiedAdScope(baiduTree(n,true))==null,"Baidu countdown exception accepts explicit Skip only: "+role);
        }
        n=baiduStartupFixture();old=n.get(6);n.set(6,baiduChange(old,old.box,false,old.role,true,old.className,true));
        ok(NativeControlPolicy.findVerifiedAdScope(baiduTree(n,true))==null,
                "Baidu scene cannot substitute giant clickable creative for an unclickable Skip");
        for(int at:new int[]{0,1,3,4,5,6}) {
            n=baiduStartupFixture();old=n.get(at);n.set(at,baiduChange(old,old.box,old.clickable,old.role,false,old.className,old.skipCountdown));
            ok(NativeControlPolicy.findVerifiedAdScope(baiduTree(n,true))==null,"Baidu proof requires every essential current layer visible: "+at);
        }
        n=baiduStartupFixture();old=n.get(0);n.set(0,baiduNode(-1,8,old.box,false,"",true,old.className,false));
        ok(NativeControlPolicy.findVerifiedAdScope(baiduTree(n,true))==null,"missing declared Baidu scope child stays incomplete");
        n=baiduStartupFixture();old=n.get(5);n.set(5,baiduChange(old,old.box,true,"nav",true,old.className,false));
        ok(NativeControlPolicy.findVerifiedAdScope(baiduTree(n,true))==null,"current navigation inside Baidu scope remains protected");
        n=baiduStartupFixture();old=n.get(5);n.set(5,baiduChange(old,old.box,true,UiControlPolicy.SKIP,true,old.className,true));
        ok(NativeControlPolicy.findVerifiedAdScope(baiduTree(n,true))==null,"two current countdown Skip controls remain ambiguous");
        n=baiduStartupFixture();old=n.get(1);n.set(1,baiduChange(old,new int[]{0,0,1216,1800},true,"",true,old.className,false));
        ok(NativeControlPolicy.findVerifiedAdScope(baiduTree(n,true))==null,"Baidu creative image must fill its current complete layer");
        n=baiduStartupFixture();old=n.get(1);n.set(1,baiduChange(old,old.box,false,"",true,old.className,false));
        ok(NativeControlPolicy.findVerifiedAdScope(baiduTree(n,true))==null,"Baidu home or introduction image without creative interaction is insufficient");
        n=baiduStartupFixture();old=n.get(4);n.set(4,baiduChange(old,new int[]{53,500,291,605},false,"",true,old.className,false));
        ok(NativeControlPolicy.findVerifiedAdScope(baiduTree(n,true))==null,"unrelated brand image outside current timed control row cannot prove scene");
        n=baiduStartupFixture();old=n.get(6);n.set(6,baiduChange(old,old.box,true,old.role,true,"android.widget.Button",true));
        ok(NativeControlPolicy.findVerifiedAdScope(baiduTree(n,true))==null,"changed current class graph cannot borrow Baidu scene proof");
        n=baiduStartupFixture();
        for(int i=0;i<n.size();i++) {
            old=n.get(i);n.set(i,new ControlTree.Node(old.parent,old.children,old.box,old.clickable,old.role,
                "unrelated-identity-"+i,old.visible,old.className,"unrelated-current-id-"+i,old.skipCountdown));
        }
        c=NativeControlPolicy.findVerifiedAdScope(baiduTree(n,true));
        ok(c!=null && c.target==6,"view IDs and identity hashes do not supply Baidu scene evidence");
        n=baiduStartupFixture();t=baiduTree(n,true);var original=NativeControlPolicy.findVerifiedAdScope(t).hit(t);
        for(int at:new int[]{4,5,6}) {
            old=n.get(at);int[] box=old.box.clone();box[1]+=600;box[3]+=600;
            n.set(at,baiduChange(old,box,old.clickable,old.role,true,old.className,old.skipCountdown));
        }
        t=baiduTree(n,true);c=NativeControlPolicy.findVerifiedAdScope(t);
        ok(c!=null && c.label.box[1]==670 && !NativeControlPolicy.matches(c,t,original),
                "Baidu proof follows moved current header row and cannot execute saved coordinates");
        for(double scale:new double[]{.5,1.5}) {
            List<ControlTree.Node> scaled=new ArrayList<>();
            for(var item:baiduStartupFixture()) {
                int[] box=item.box.clone();for(int i=0;i<4;i++)box[i]=(int)Math.round(box[i]*scale);
                scaled.add(baiduNode(item.parent,item.children,box,item.clickable,item.role,item.visible,item.className,item.skipCountdown));
            }
            c=NativeControlPolicy.findVerifiedAdScope(new ControlTree.Snapshot(BAIDU_MAP_PACKAGE,9,100,
                (int)(1216*scale),(int)(2640*scale),scaled,true));
            ok(c!=null && c.target==6,"Baidu current scene proof follows display scale "+scale);
        }
        t=baiduTree(baiduStartupFixture(),true);
        ok(!NativeControlPolicy.animatedDecoration(t,3) && !NativeControlPolicy.animatedDecoration(t,4) &&
                !NativeControlPolicy.animatedDecoration(t,5),"Baidu proof images and caption cannot bypass exact live geometry checks");
    }
    static void scopedCountdownChecks() {
        Object[] first=new Object[28];Arrays.fill(first,"fixed");first[3]="跳过 3秒";first[4]="Skip 3 s";
        Object[] current=first.clone();current[3]="跳过 2秒";current[4]="Skip 2 s";
        ok(NativeControlPolicy.scopePropertyDifference(first,current,false,UiControlPolicy.SKIP)<0,
            "explicit Skip text and description countdowns may change in a verified scope");
        current=first.clone();current[3]="关闭广告";
        ok(NativeControlPolicy.scopePropertyDifference(first,current,false,UiControlPolicy.SKIP)==3,
            "Skip changing to another control cannot use countdown tolerance");
        current=first.clone();current[3]=null;Object[] unknown=first.clone();unknown[3]=null;
        ok(NativeControlPolicy.scopePropertyDifference(first,current,false,UiControlPolicy.SKIP)==3 &&
                NativeControlPolicy.scopePropertyDifference(unknown,first,false,UiControlPolicy.SKIP)==3,
            "null and previously unknown text fields remain exactly compared");
        current=first.clone();current[4]="";
        ok(NativeControlPolicy.scopePropertyDifference(first,current,false,UiControlPolicy.SKIP)==4,
            "description losing explicit Skip semantics remains a rejection");
        current=first.clone();current[3]="跳过";
        ok(NativeControlPolicy.scopePropertyDifference(first,current,false,UiControlPolicy.SKIP)==3,
            "current text losing countdown cannot retain earlier timed scene proof");
        current=first.clone();current[4]="Skip";
        ok(NativeControlPolicy.scopePropertyDifference(first,current,false,UiControlPolicy.SKIP)==4,
            "current description losing countdown cannot retain earlier timed scene proof");
        current=first.clone();current[3]="跳过 2秒";current[4]="Skip 2 s";current[11]=Boolean.TRUE;
        ok(NativeControlPolicy.scopePropertyDifference(first,current,false,UiControlPolicy.SKIP)==11,
            "countdown changes cannot hide a later clickable-property change");
        current=first.clone();current[3]="跳过 2秒";current[6]="moved-control-bounds";
        ok(NativeControlPolicy.scopePropertyDifference(first,current,false,UiControlPolicy.SKIP)==6,
            "Skip geometry remains exact even when its countdown changes");
        current=first.clone();current[3]="跳过 2秒";
        ok(NativeControlPolicy.scopePropertyDifference(first,current,false,UiControlPolicy.CLOSE_AD)==3 &&
                NativeControlPolicy.scopePropertyDifference(first,current,false,"")==3,
            "close-ad and unrelated nodes cannot borrow Skip countdown tolerance");
        NativeControlPolicy.scopePropertyDifference(first,current,false,UiControlPolicy.SKIP);
        ok(!Arrays.equals(first,current) && "跳过 3秒".equals(first[3]),
            "scope countdown tolerance never rewrites frozen properties used by exact alias checks");
    }
    static String decode(String s){return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception {
        pauseAdChecks();
        var direct=List.of("node:11","node:12");
        ok(NativeControlPolicy.declaredChildrenMatch(direct,direct,0),"fresh root retains current child source identities");
        ok(!NativeControlPolicy.declaredChildrenMatch(direct,List.of("node:11","node:99"),0),
            "same child count cannot hide a replaced node with the same class and bounds");
        ok(!NativeControlPolicy.declaredChildrenMatch(direct,List.of("node:12","node:11"),0),"reordered root children invalidate the capture");
        ok(NativeControlPolicy.declaredChildrenMatch(direct,List.of("node:11","node:11","node:12"),1),"only a verified alias preserves current root children");
        ok(!NativeControlPolicy.declaredChildrenMatch(direct,List.of("node:11","node:11","node:12"),0),"unknown duplicate child cannot borrow alias proof");
        ok(!NativeControlPolicy.declaredChildrenMatch(direct,Arrays.asList("node:11",null),0),"missing current root child remains a rejection");
        ok(!NativeControlPolicy.declaredChildrenMatch(direct,List.of("node:11"),0),"removed current child cannot promote an incomplete scope");
        var freshScope=new ControlTree.Snapshot("test.app",9,100,1216,2640,fixture(),true);
        ok(freshScope.currentVerifiedScope("test.app",9,350,1216,2640,100) &&
            !freshScope.currentVerifiedScope("test.app",9,351,1216,2640,100),"fresh scope includes traversal and input in its original 250ms age");
        var n=fixture();var t=tree(n);var c=NativeControlPolicy.find(t,true);
        ok(c!=null && c.label.nodeIndex==6 && c.target==4,"nonclickable parent resolves clickable grandparent");
        var hit=c.hit(t);
        ok(hit.nativeOnly && Float.isNaN(hit.modelProbability),"native proof is not fabricated model confidence");
        ok(SceneFramePolicy.oneFrame(hit,150) && !SceneFramePolicy.needsVisualRecheck(hit,false),"native action bypasses OCR gate");
        ok(!SceneFramePolicy.allowsGesture(hit),"native refusal cannot become blind coordinate tap");
        ok(hit.withMemory(true).withModel(.99f).nativeOnly,"hit copies preserve native-only guard");
        ok(NativeControlPolicy.matches(c,t,hit),"same current native chain matches");
        observationChecks(hit);
        ok(NativeControlPolicy.find(t,false)==null,"ordinary close outside launch is protected");
        ok(!t.current("other.app",9,110,1216,2640) && !t.current("test.app",10,110,1216,2640)
            && !t.current("test.app",9,351,1216,2640),"package generation and age must match");
        ok(NativeControlPolicy.find(new ControlTree.Snapshot("test.app",9,100,1216,2640,n,false),true)==null,"partial native tree cannot authorize click");
        n=fixture();n.set(7,node(3,0,n.get(7).box,false,"prompt","disclosure"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"unrelated ad cue in other branch is insufficient");
        n=fixture();n.set(8,node(2,0,n.get(8).box,true,"nav","home-tab"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"navigation in same branch blocks plain close");
        n=fixture();n.set(4,node(2,1,new int[]{0,0,1216,2640},true,"","ad-container"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"giant clickable ad container rejected");
        n=fixture();n.set(6,new ControlTree.Node(5,0,n.get(6).box,false,UiControlPolicy.CLOSE,"label",false));
        ok(NativeControlPolicy.find(tree(n),true)==null,"invisible or disabled label cannot authorize");
        n=fixture();n.set(4,new ControlTree.Node(2,1,n.get(4).box,true,"","ancestor",false));
        ok(NativeControlPolicy.find(tree(n),true)==null,"invisible or disabled ancestor cannot authorize");
        n=fixture();n.add(node(2,0,new int[]{100,70,220,135},true,UiControlPolicy.CLOSE,"second-close"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"two eligible close buttons deferred");
        n=fixture();n.set(6,node(5,0,n.get(6).box,false,"","search-field"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"same index and bounds without close text rejected");
        n=fixture();n.set(6,node(5,0,n.get(6).box,false,UiControlPolicy.CLOSE,"different-control"));
        var changed=tree(n);
        ok(!NativeControlPolicy.matches(NativeControlPolicy.find(changed,true),changed,hit),"reused generic ID cannot replace a changed chain");
        for(double scale:new double[]{.5,1,1.5}) {
            n=fixture();for(int i:new int[]{4,5,6}) {
                var a=n.get(i);int[] b=a.box.clone();for(int j=0;j<4;j++)b[j]-=(j%2==0?500:-400);
                n.set(i,node(a.parent,a.children,b,a.clickable,a.role,a.identity));
            }
            List<ControlTree.Node> scaled=new ArrayList<>();for(var a:n){int[] b=a.box.clone();for(int j=0;j<4;j++)b[j]=(int)Math.round(b[j]*scale);scaled.add(node(a.parent,a.children,b,a.clickable,a.role,a.identity));}
            var moved=new ControlTree.Snapshot("test.app",9,100,(int)(1216*scale),(int)(2640*scale),scaled);
            var proposed=NativeControlPolicy.find(moved,true);
            ok(proposed!=null && proposed.target==4,"moved control at resolution scale "+scale);
            ok(!NativeControlPolicy.matches(proposed,moved,hit),"old geometry cannot execute after movement "+scale);
        }
        var visual=new BilibiliVisualMatcher.Hit(UiControlPolicy.CLOSE,hit.x,hit.y,.99f,1216,2640).withModel(.99f);
        ok(!SceneFramePolicy.tryNative(visual) && SceneFramePolicy.tryNative(hit),"visual-only target does not waste recheck budget on missing native nodes");
        visual.treeAssisted=true;
        ok(SceneFramePolicy.tryNative(visual),"visual verification with native evidence keeps ancestor path");
        ok(SceneFramePolicy.needsVisualRecheck(visual,false) && !SceneFramePolicy.needsVisualRecheck(visual,true)
            && NativeControlPolicy.matches(c,t,visual),"visually rechecked path can still use native ancestor");
        n=new ArrayList<>();int[] full={0,0,1216,2640},controlRow={0,119,1216,294};
        n.add(node(-1,1,full,false,"","decor"));n.add(node(0,2,full,false,"","shared-home-ad"));
        n.add(node(1,2,controlRow,false,"","control-row"));
        n.add(node(2,0,new int[]{1002,161,1177,273},true,UiControlPolicy.SKIP,"skip"));
        n.add(node(2,0,new int[]{460,193,950,239},false,"ad","ad-label"));
        n.add(node(1,0,new int[]{70,2524,202,2602},true,"nav","home-tab"));
        var rowTree=tree(n);var rowHit=NativeControlPolicy.find(rowTree,true);
        ok(rowHit!=null && rowHit.target==3,"skip and ad share compact group despite home in outer branch");
        n.set(4,node(1,0,new int[]{460,193,950,239},false,"ad","unrelated-ad-label"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"nearby ad label with different parent is insufficient");
        n.set(4,node(2,0,new int[]{460,193,950,239},false,"ad","ad-label"));
        n.add(node(2,0,new int[]{20,150,160,240},true,"nav","local-navigation"));
        ok(NativeControlPolicy.find(tree(n),true)==null,"navigation inside compact group remains protected");
        fullScreenSkipChecks();
        scopedAdChecks();
        scopedLabelTouchChecks();
        youkuSkipAdChecks();
        disclosureSemanticsChecks();
        youkuDisclosureChecks();
        mobileStartupChecks();
        explicitCountdownChecks();
        baiduStartupChecks();
        scopedCountdownChecks();
        // Frozen native-frame roles are replayed directly. Other TSV captures
        // retain text-derived roles and the legacy numeric fourth header field.
        // When that field is absent/non-numeric, pass the expected target after
        // the filename, for example: capture.tsv 32 home.tsv -1.
        for(int replay=0;replay<args.length;replay++) {
            String arg=args[replay];
            List<String> rows=Files.readAllLines(Paths.get(arg));String[] header=rows.remove(0).split("\t");n=new ArrayList<>();
            boolean frozen=header.length>3 && (header[3].equals("native_frame") || header[3].equals("ad_scope_frozen"));
            boolean scoped=header.length>3 && (header[3].equals("ad_scope") || header[3].equals("ad_scope_frozen"));
            for(String row:rows){String[] p=row.split("\t",-1);boolean visible=Boolean.parseBoolean(p[3]);
                String role=visible?(frozen?decode(p[8]):ControlTree.role(decode(p[8]))):"";
                if(visible && role.isEmpty() && !frozen)role=ControlTree.role(decode(p[9]));
                n.add(new ControlTree.Node(Integer.parseInt(p[0]),Integer.parseInt(p[1]),new int[]{Integer.parseInt(p[4]),Integer.parseInt(p[5]),Integer.parseInt(p[6]),Integer.parseInt(p[7])},
                    Boolean.parseBoolean(p[2]),role,decode(p[10]),visible,p.length>11?decode(p[11]):"",p.length>12?decode(p[12]):"",
                    p.length>13 && Boolean.parseBoolean(p[13]),null,null,null,p.length>14 && Boolean.parseBoolean(p[14]),
                    p.length>15 && Boolean.parseBoolean(p[15])));}
            var real=new ControlTree.Snapshot(header.length>4?header[4]:"captured.app",9,100,Integer.parseInt(header[0]),Integer.parseInt(header[1]),n,Boolean.parseBoolean(header[2]));
            int expected;
            if(header.length>3 && !frozen && !scoped)expected=Integer.parseInt(header[3]);
            else if(replay+1<args.length)expected=Integer.parseInt(args[++replay]);
            else throw new IllegalArgumentException("Expected target required after captured TSV filename: "+arg);
            var got=scoped?NativeControlPolicy.findVerifiedAdScope(real):NativeControlPolicy.find(real,true);
            ok(expected<0?got==null:got!=null && got.target==expected,"real capture "+Paths.get(arg).getFileName()+" target="+(got==null?-1:got.target));
        }
        System.out.println("Native checks passed: "+checks);
    }
}
