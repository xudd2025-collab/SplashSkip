package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.io.*;
import java.util.*;

/** Learning lifecycle, private serialization, stale memories and overlay geometry. */
public final class ButtonLearningCheck {
    static void require(boolean condition,String name) {if(!condition)throw new AssertionError(name);System.out.println("PASS "+name);}
    public static void main(String[] a)throws Exception {
        var im=ScreenshotRuleCheck.resize(ScreenshotRuleCheck.read(a[0]),1216);var f=SceneRuleCheck.frame(im);
        Hit h=new Hit(TencentVisualMatcher.SPLASH,1089,218,1,f.originalWidth,f.originalHeight).withModel(.999f);
        h=h.withSignature(ButtonFeatureMemory.signature(f,h));
        long now=100_000;ButtonFeatureMemory memory=new ButtonFeatureMemory();
        memory.remember("test.app",h.withModel(Float.NaN),now);require(memory.size(now)==0,"unknown model is never learned");
        memory.remember("test.app",h.withModel(.8f),now);require(memory.size(now)==0,"low-confidence click is never learned");
        memory.remember("test.app",h,now);require(memory.size(now)==1,"verified button stored");
        Hit other=new Hit(h.rule,900,218,0,f.originalWidth,f.originalHeight);
        List<Hit> ordered=memory.prioritize("test.app",f,Arrays.asList(other,h),now+1);
        require(ordered.get(0).memoryMatch && ordered.get(0).x==h.x,"recent feature prioritizes a currently detected candidate");
        require(memory.prioritize("test.app",f,Collections.emptyList(),now+1).isEmpty(),"memory cannot invent a coordinate when button is missing");
        require(!memory.prioritize("other.app",f,Arrays.asList(h),now+1).get(0).memoryMatch,"application boundary enforced");
        var moved=new java.awt.image.BufferedImage(im.getWidth(),im.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
        var painter=moved.createGraphics();painter.drawImage(im,0,0,null);
        int[] box=AdActionRegion.box(h,im.getWidth(),im.getHeight());
        painter.drawImage(im.getSubimage(box[0],box[1],box[2]-box[0],box[3]-box[1]),150-(box[2]-box[0])/2,1700-(box[3]-box[1])/2,null);painter.dispose();
        Hit shifted=new Hit(h.rule,150,1700,1,f.originalWidth,f.originalHeight);
        require(memory.prioritize("test.app",SceneRuleCheck.frame(moved),Arrays.asList(shifted),now+1).get(0).memoryMatch,"same feature can move across the screen; historical coordinates do not restrict candidates");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();memory.write(new DataOutputStream(bytes));
        ButtonFeatureMemory restored=new ButtonFeatureMemory();restored.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        require(restored.prioritize("test.app",f,Arrays.asList(h),now+2).get(0).memoryMatch,"features restore across process restart");
        require(restored.size(now+ButtonFeatureMemory.AGE+1)==0,"old features expire");
        require(!TouchTargetGuard.covered(h,new int[]{1000,600,1200,1300}),"middle volume overlay does not cover top skip");
        require(TouchTargetGuard.covered(h,new int[]{1050,180,1150,260}),"overlay over button waits");
        require(TouchTargetGuard.covered(h,new int[]{1025,187,1055,249}),"partially obstructed button waits");
        ClickLearningSession s=new ClickLearningSession("test.app",1,1000,h);
        require(!s.observe(1300,ClickLearningSession.ABSENT,false,true),"submitted gesture alone cannot learn");
        s.complete(1100);
        require(!s.observe(1400,ClickLearningSession.ABSENT,false,true),"one miss cannot learn");
        require(!s.observe(1500,ClickLearningSession.ABSENT,false,true),"near-duplicate frame cannot learn");
        require(s.observe(1800,ClickLearningSession.ABSENT,false,true),"completed gesture plus two fresh disappearance frames verifies learning");
        for(int state:new int[]{ClickLearningSession.UNKNOWN,ClickLearningSession.PRESENT}) {
            s=new ClickLearningSession("test.app",1,1000,h);s.complete(1100);
            require(!s.observe(1400,state,false,true) && !s.observe(1800,state,false,true),"unconfirmed or remaining button cannot learn state="+state);
        }
        s=new ClickLearningSession("test.app",1,1000,h);s.complete(1100);
        require(!s.observe(1400,ClickLearningSession.ABSENT,false,false) && !s.observe(1800,ClickLearningSession.ABSENT,false,false),"occlusion does not become success");
        s=new ClickLearningSession("test.app",1,1000,h);s.complete(1100);
        require(!s.observe(2200,ClickLearningSession.ABSENT,false,true),"late natural expiration is not learned");
        require(s.expired(3300),"unfinished learning expires");
        System.out.println("All button-learning checks passed");
    }
}
