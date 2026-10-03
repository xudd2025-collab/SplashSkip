package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.image.BufferedImage;
import java.util.List;

/** External screenshot diagnostics; all scores use the shipped classifier weights. */
public final class SkipLatencyCheck {
 public static void main(String[] args)throws Exception {
  UniversalSplashMatcher matcher=SceneRuleCheck.universal(args[0]);
  AdButtonModelCheck model=new AdButtonModelCheck(args[1]);
  Template[] glyphs=SceneRuleCheck.glyphs(args[0]);
  SkipFeatureBank bank=new SkipFeatureBank(SceneRuleCheck.glyphs(args[0],"skip_feature_glyphs"));
  for(int i=2;i<args.length;i++) {
   BufferedImage im=ScreenshotRuleCheck.read(args[i]);int width=Integer.getInteger("test.width",0);if(width>0)im=ScreenshotRuleCheck.resize(im,width);Frame f=SceneRuleCheck.frame(im);
   long start=System.nanoTime();List<Hit> candidates=AdGlyphProposals.find(f,UniversalSplashMatcher.SKIP,new float[]{.01f,.02f,.99f,.18f},1);
   System.out.println("SOURCE "+args[i]+" dimensions="+im.getWidth()+"x"+im.getHeight()+" proposalMs="+(System.nanoTime()-start)/1000000+" count="+candidates.size());
   for(Hit h:candidates) {
    float p=model.probability(f,h);if(p<.1f && h.x<990)continue;
    start=System.nanoTime();boolean glyph=SkipGlyphVerifier.accepts(f,h,glyphs,h.cropScale,.68f);
    System.out.printf("candidate %d,%d scale=%.3f p=%.6f glyph=%s glyphMs=%d stroke=%.6f%n",h.x,h.y,h.cropScale,p,glyph,(System.nanoTime()-start)/1000000,model.strokeProbability(f,h));
   }
   start=System.nanoTime();Hit hit=matcher.findLaunching(f,model,UniversalSplashMatcher.SKIP,()->false);
   System.out.printf("RESULT %s time=%dms %s%n",args[i],(System.nanoTime()-start)/1000000,hit==null?"MISS":hit.x+","+hit.y+" p="+hit.modelProbability);
   if(hit!=null){start=System.nanoTime();Hit b=bank.verify(f,hit);System.out.println("BANK "+(b==null?"MISS":b.x+","+b.y+" score="+b.score)+" time="+(System.nanoTime()-start)/1000000+"ms");}
   hit=SceneRuleCheck.tencent(args[0]).opening(f,model);System.out.println("TENCENT "+(hit==null?"MISS":hit.x+","+hit.y+" p="+hit.modelProbability));
  }
 }
}
