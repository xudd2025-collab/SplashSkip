package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** New external backgrounds, moving controls and missing-control/semantics regressions. */
public final class SkipFeatureCheck {
 static void positive(String name,BufferedImage im,UniversalSplashMatcher u,AdButtonModelCheck m,float x,float y) {
  long start=System.nanoTime();Hit h=u.findLaunching(SceneRuleCheck.frame(im),m,UniversalSplashMatcher.SKIP,()->false);
  if(h==null || Math.abs(h.x-x)>12 || Math.abs(h.y-y)>12 || h.modelProbability<.98f)throw new AssertionError(name+" "+(h==null?"MISS":h.x+","+h.y+" p="+h.modelProbability));
  System.out.printf("PASS %s tap=%d,%d p=%.6f match=%dms%n",name,h.x,h.y,h.modelProbability,(System.nanoTime()-start)/1000000);
 }
 static void none(String name,BufferedImage im,UniversalSplashMatcher u,AdButtonModelCheck m) {
  if(u.findLaunching(SceneRuleCheck.frame(im),m,UniversalSplashMatcher.SKIP,()->false)!=null)throw new AssertionError(name);
  System.out.println("PASS reject "+name);
 }
 public static void main(String[] a)throws Exception {
  UniversalSplashMatcher u=SceneRuleCheck.universal(a[0]);AdButtonModelCheck m=new AdButtonModelCheck(a[1]);
  for(int i=2;i<a.length;i++) {
   BufferedImage source=ScreenshotRuleCheck.read(a[i]);int x=i==a.length-1?1089:1075,y=i==a.length-1?218:233;
   for(int width:new int[]{720,1080,1216,1440}) {
    float scale=width/1216f;positive("source="+(i-2)+" width="+width,ScreenshotRuleCheck.resize(source,width),u,m,x*scale,y*scale);
   }
   // Keep the muted speaker X and all ad cues; an absent skip must never target that X.
   none("source="+(i-2)+" missing skip",ScreenshotRuleCheck.erase(source,x-85,y-55,170,110,new Color(63,65,67)),u,m);
  }
  BufferedImage source=ScreenshotRuleCheck.read(a[3]);
  for(int[] point:new int[][]{{170,420},{830,1850}}) {
   BufferedImage moved=ScreenshotRuleCheck.erase(source,990,178,170,110,new Color(63,65,67));Graphics2D g=moved.createGraphics();
   g.drawImage(source.getSubimage(1017,202,116,62),point[0]-58,point[1]-31,null);g.dispose();
   positive("relocated control "+point[0]+","+point[1],moved,u,m,point[0],point[1]);
  }
  BufferedImage noCue=new BufferedImage(1216,2640,BufferedImage.TYPE_INT_RGB);Graphics2D g=noCue.createGraphics();g.setColor(new Color(63,65,67));g.fillRect(0,0,1216,2640);g.drawImage(source.getSubimage(1017,202,116,62),1017,202,null);g.dispose();
  none("skip alone without advertisement semantics",noCue,u,m);
  for(int side=0;side<2;side++)none("one skip character missing "+side,ScreenshotRuleCheck.erase(source,side==0?1017:1075,202,58,62,new Color(30,30,30)),u,m);
  BufferedImage replacement=ScreenshotRuleCheck.erase(source,0,320,1216,1530,new Color(29,92,135));positive("creative body replaced",replacement,u,m,1075,233);
  Frame f=SceneRuleCheck.frame(source);if(u.findLaunching(f,m,UniversalSplashMatcher.SKIP,()->true)!=null)throw new AssertionError("cancelled frame");
  System.out.println("PASS cancelled/stale recognition stops");
 }
}
