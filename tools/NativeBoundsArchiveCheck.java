package com.codex.splashskip;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Run with only NativeBoundsPolicy.java; no Android runtime or phone is required. */
public final class NativeBoundsArchiveCheck {
    private static int checks;
    private static void ok(boolean value,String message) {
        if(!value)throw new AssertionError(message);
        checks++;
    }
    private static NativeBoundsPolicy.Action sample(NativeBoundsPolicy p,String pkg,long epoch,long time,String shape,String reason) {
        return p.decide(pkg,epoch,time,shape,reason).action;
    }
    private static void sampling() {
        NativeBoundsPolicy p=new NativeBoundsPolicy();
        ok(p.decide("app",1,100,"a","read").first,"generation first frame retained");
        ok(sample(p,"app",1,599,"b","read")==NativeBoundsPolicy.Action.IGNORE,"499ms throttled");
        ok(sample(p,"app",1,600,"a","read")==NativeBoundsPolicy.Action.MERGE,"500ms duplicate merges");
        ok(sample(p,"app",1,1100,"a","changed")==NativeBoundsPolicy.Action.APPEND,"reason change retained");
        ok(sample(p,"app",1,2100,"b","changed")==NativeBoundsPolicy.Action.APPEND,"new layout retained");
        ok(p.decide("app",2,2101,"b","changed").first,"same package returning with new epoch retains first");
        ok(p.decide("other",1,2102,"b","changed").first,"another package has its own first");
        ok(sample(p,"app",1,1600,"c","read")==NativeBoundsPolicy.Action.IGNORE,"late older sample ignored");
        p=new NativeBoundsPolicy();
        int early=0;
        for(int i=0;i<16;i++)if(sample(p,"app",1,i*500,"layout"+i,"read")==NativeBoundsPolicy.Action.APPEND)early++;
        ok(early==12,"at most 12 new startup frames");
        ok(sample(p,"app",1,8000,"late-new","read")==NativeBoundsPolicy.Action.APPEND,"8 seconds starts late new-layout policy");
        ok(sample(p,"app",1,8500,"late-other","read")==NativeBoundsPolicy.Action.IGNORE,"late new layout under 2 seconds throttled");
        ok(sample(p,"app",1,10000,"late-other","read")==NativeBoundsPolicy.Action.APPEND,"late new layout at 2 seconds retained");
        ok(sample(p,"app",1,12000,"late-other","read")==NativeBoundsPolicy.Action.MERGE,"short late duplicate merges");
        ok(sample(p,"app",1,12500,"late-other","read")==NativeBoundsPolicy.Action.IGNORE,"unchanged late layout stops adding frames");
        p.reset();ok(p.decide("app",1,13000,"late-other","read").first,"clear resets generation sampling");
        for(int i=0;i<200;i++)p.decide("app",i,14000+i,"a","read");
        ok(p.generations()==128,"generation bookkeeping bounded");
        ok(sample(p,"",1,0,"a","read")==NativeBoundsPolicy.Action.IGNORE,"empty owner ignored");
        ok(sample(p,"app",1,-1,"a","read")==NativeBoundsPolicy.Action.IGNORE,"negative uptime ignored");
    }
    private static void budgets() {
        long now=NativeBoundsPolicy.RETENTION_MS+100;
        List<NativeBoundsPolicy.Budget> frames=new ArrayList<>();
        frames.add(new NativeBoundsPolicy.Budget(99,10));
        frames.add(new NativeBoundsPolicy.Budget(100,10));
        frames.add(new NativeBoundsPolicy.Budget(now,10));
        List<Integer> kept=NativeBoundsPolicy.retained(frames,now,20);
        ok(kept.size()==2 && kept.get(0)==1,"older than 7 days removed; exact boundary retained");
        ok(!NativeBoundsPolicy.expired(now+1000,now),"wall clock rollback does not expire future records");
        frames.clear();
        for(int i=0;i<80;i++)frames.add(new NativeBoundsPolicy.Budget(now,100));
        kept=NativeBoundsPolicy.retained(frames,now,30);
        ok(kept.size()==64 && kept.get(0)==16,"64-frame limit evicts oldest frames");
        frames.clear();
        frames.add(new NativeBoundsPolicy.Budget(now,NativeBoundsPolicy.MAX_FRAME_BYTES));
        frames.add(new NativeBoundsPolicy.Budget(now,NativeBoundsPolicy.MAX_FRAME_BYTES+1));
        kept=NativeBoundsPolicy.retained(frames,now,2);
        ok(kept.size()==1 && kept.get(0)==0,"single-frame limit checks inclusive byte boundary");
        frames.clear();
        for(int i=0;i<9;i++)frames.add(new NativeBoundsPolicy.Budget(now,NativeBoundsPolicy.MAX_FRAME_BYTES));
        kept=NativeBoundsPolicy.retained(frames,now,500);
        long total=500+Math.max(0,kept.size()-1);
        for(int i:kept)total+=frames.get(i).bytes;
        ok(kept.size()==7 && total<=NativeBoundsPolicy.MAX_TOTAL_BYTES,"total limit includes envelope and commas");
        frames.clear();
        frames.add(new NativeBoundsPolicy.Budget(now,NativeBoundsPolicy.MAX_TOTAL_BYTES-2));
        ok(NativeBoundsPolicy.retained(frames,now,2).isEmpty(),"total-sized single frame cannot bypass per-frame limit");
        String chinese="关闭广告";
        ok(chinese.getBytes(StandardCharsets.UTF_8).length>chinese.length(),"UTF8 byte count differs from character count");
        frames.clear();frames.add(new NativeBoundsPolicy.Budget(-1,12));
        ok(NativeBoundsPolicy.retained(frames,now,2).isEmpty(),"invalid retention timestamp rejected");
    }
    private static void importantFrames() {
        NativeBoundsPolicy p=new NativeBoundsPolicy();
        ok(p.decide("app",1,0,"loading","read",0).action==NativeBoundsPolicy.Action.APPEND,"loading first frame retained");
        ok(p.decide("app",1,200,"ad","read",1).action==NativeBoundsPolicy.Action.APPEND,"first ad frame bypasses 500ms interval");
        ok(p.decide("app",1,300,"ad","read",1).action==NativeBoundsPolicy.Action.IGNORE,"repeated ad frame still throttled");
        ok(p.decide("app",1,350,"close","read",2).action==NativeBoundsPolicy.Action.APPEND,"first explicit control can bypass interval once");
        ok(p.decide("app",1,400,"another-close","read",2).action==NativeBoundsPolicy.Action.IGNORE,"control layout changes do not repeatedly bypass interval");
        ok(p.decide("app",1,850,"another-close","read",2).action==NativeBoundsPolicy.Action.APPEND,"500ms after upgraded frame permits new structure");
        p=new NativeBoundsPolicy();
        ok(p.decide("app",1,0,"close","read",2).first,"first frame may start at highest importance");
        ok(p.decide("app",1,200,"another-close","read",2).action==NativeBoundsPolicy.Action.IGNORE,"initial highest level uses regular throttling afterward");
        p=new NativeBoundsPolicy();
        for(int i=0;i<12;i++)p.decide("app",1,i*500,"layout"+i,"read",0);
        ok(p.decide("app",1,5700,"close","read",2).action==NativeBoundsPolicy.Action.IGNORE,"importance upgrade cannot exceed 12 startup frames");
        ok(p.decide("app",2,5701,"close","read",2).first,"new epoch keeps its own first important frame");
    }
    private static void completenessAndLabels() {
        ok(NativeBoundsPolicy.fullTreeComplete(true,false,27,27),"complete unchanged global tree stays complete");
        ok(!NativeBoundsPolicy.fullTreeComplete(false,false,27,27),"provider truncated tree stays incomplete");
        ok(!NativeBoundsPolicy.fullTreeComplete(true,true,27,27),"complete ad scope never claims complete global tree");
        ok(!NativeBoundsPolicy.fullTreeComplete(true,false,27,26),"archive node truncation clears complete flag");
        ok(!NativeBoundsPolicy.fullTreeComplete(true,false,0,0),"empty tree is not complete");
        for(String s:new String[]{"跳过广告","跳过 ５ 秒","Skip 5s","关闭广告，放大暂停画面","广告","互动广告","点击下载或打开第三方应用","互动跳转详情页面或第三方应用"})
            ok(NativeBoundsPolicy.labelAllowed(s),"explicit short diagnostic label retained: "+s);
        for(String s:new String[]{"广告：输入手机号领取奖励","这是一篇广告正文，介绍商品和用户账号","如何关闭广告","关闭广告，开通会员","my password",null})
            ok(!NativeBoundsPolicy.labelAllowed(s),"unrelated or free-form text excluded");
        StringBuilder huge=new StringBuilder("广告");for(int i=0;i<100;i++)huge.append('字');
        ok(!NativeBoundsPolicy.labelAllowed(huge.toString()),"long ad text excluded");
    }
    public static void main(String[] args) {
        sampling();importantFrames();budgets();completenessAndLabels();
        System.out.println("PASS NativeBoundsArchiveCheck checks="+checks);
    }
}
