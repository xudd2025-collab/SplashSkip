package com.codex.splashskip;

/** Compatibility input needs new positive evidence; unknown results supply no touch. */
public final class NativeTouchObservationCheck {
    static int checks;
    static void ok(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static ControlTree.Snapshot present(long time,boolean complete){return NativeControlCheck.observed(NativeControlCheck.fixture(),time,complete);}
    static ControlTree.Snapshot differentStructure(long time,int changedIndex){
        java.util.List<ControlTree.Node> nodes=NativeControlCheck.fixture();ControlTree.Node old=nodes.get(changedIndex);
        nodes.set(changedIndex,new ControlTree.Node(old.parent,old.children,old.box,old.clickable,old.role,old.identity+"-different",old.visible));
        return NativeControlCheck.observed(nodes,time,true);
    }
    static NativeTreeObservation observation(){
        ControlTree.Snapshot tree=present(100,true);
        return new NativeTreeObservation("test.app",9,1000,NativeControlPolicy.find(tree,true).hit(tree));
    }
    public static void main(String[] args){
        ControlTree.Snapshot scope=present(100,true);
        ok(!scope.current("test.app",9,400,1216,2640),"ordinary captured trees retain their original expiry");
        ok(scope.currentVerifiedScope("test.app",9,449,1216,2640,200),
            "complete refreshed scope proof uses the start of property verification without changing capture time");
        ok(!scope.currentVerifiedScope("test.app",9,451,1216,2640,200),"verified scope proof still expires after 250 ms");
        ok(!scope.currentVerifiedScope("test.app",9,250,1216,2640,99) &&
                !scope.currentVerifiedScope("test.app",9,250,1216,2640,251),"old or future verification timestamps cannot authorize input");
        ok(!scope.currentVerifiedScope("other.app",9,400,1216,2640,200) &&
                !scope.currentVerifiedScope("test.app",10,400,1216,2640,200) &&
                !scope.currentVerifiedScope("test.app",9,400,1217,2640,200) &&
                !present(100,false).currentVerifiedScope("test.app",9,400,1216,2640,200),
            "scope proof cannot bypass package epoch display or completeness guards");
        NativeTreeObservation session=observation();
        ok(session.observe(present(1120,true),1120)==NativeTreeObservation.PRESENT && !session.canTryCurrentTouch(1120),
            "one post-click complete presence does not authorize compatibility touch");
        ok(session.observe(present(1240,true),1240)==NativeTreeObservation.PRESENT && session.canTryCurrentTouch(1240),
            "two fresh complete presences of the original structure allow one revalidated touch");
        ok(!session.canTryCurrentTouch(1491),"stale positive presence cannot authorize touch");
        session.touchSubmitted();
        ok(!session.canTryCurrentTouch(1240),"accepted compatibility touch cannot be repeated");

        session=observation();ControlTree.Snapshot same=present(1120,true);
        session.observe(same,1120);session.observe(same,1240);
        ok(!session.canTryCurrentTouch(1240),"one snapshot reused does not become two presence samples");

        session=observation();session.observe(present(1120,true),1120);session.observe(present(1240,false),1240);
        ok(!session.canTryCurrentTouch(1240),"partial-tree presence cannot authorize touch");
        session.observe(present(1360,true),1360);
        ok(!session.canTryCurrentTouch(1360),"complete presence after partial tree begins a new proof");
        session.observe(present(1480,true),1480);
        ok(session.canTryCurrentTouch(1480),"two new complete presences restore bounded compatibility eligibility");

        session=observation();session.observe(present(1120,true),1120);
        session.observe(NativeControlCheck.observed(NativeControlCheck.goneTree(),1240,true),1240);
        session.observe(present(1360,true),1360);
        ok(!session.canTryCurrentTouch(1360),"an absent target interrupts positive compatibility proof");

        session=observation();session.observe(present(1120,true),1120);session.observe(present(1240,true),1240);
        ControlTree.Snapshot next=new ControlTree.Snapshot("test.app",10,1360,1216,2640,NativeControlCheck.fixture(),true);
        session.observe(next,1360);
        ok(!session.canTryCurrentTouch(1360),"new scene generation invalidates compatibility eligibility");
        ok(!session.canTryCurrentTouch(3201),"expired observation never authorizes compatibility touch");
        session=observation();
        ok(session.observe(differentStructure(1120,6),1120)==NativeTreeObservation.PRESENT && !session.canTryCurrentTouch(1120),
            "a different same-rule control stays PRESENT without supplying original-target touch evidence");
        ok(session.observe(present(1240,true),1240)==NativeTreeObservation.PRESENT && !session.canTryCurrentTouch(1240),
            "another control followed by one original-target sample cannot authorize touch");
        ok(session.observe(present(1360,true),1360)==NativeTreeObservation.PRESENT && session.canTryCurrentTouch(1360),
            "two consecutive original-target samples restore eligibility after another control");
        ok(session.observe(differentStructure(1480,4),1480)==NativeTreeObservation.PRESENT && !session.canTryCurrentTouch(1480),
            "a changed clickable ancestor interrupts an eligible original-target presence sequence");
        ok(session.observe(present(1600,true),1600)==NativeTreeObservation.PRESENT && !session.canTryCurrentTouch(1600),
            "one original-target sample after an ancestor change cannot reuse the old sequence");
        ok(session.observe(present(1720,true),1720)==NativeTreeObservation.PRESENT && session.canTryCurrentTouch(1720),
            "a second original-target sample after the change establishes new touch evidence");

        ControlTree.Snapshot noIdentityTree=present(100,true);
        BilibiliVisualMatcher.Hit noIdentity=NativeControlPolicy.find(noIdentityTree,true).hit(noIdentityTree);noIdentity.structure=null;
        session=new NativeTreeObservation("test.app",9,1000,noIdentity);
        ok(session.observe(present(1120,true),1120)==NativeTreeObservation.PRESENT &&
                session.observe(present(1240,true),1240)==NativeTreeObservation.PRESENT && !session.canTryCurrentTouch(1240),
            "missing original structure preserves conservative PRESENT without authorizing touch");
        System.out.println("Native touch observation checks: "+checks);
    }
}
