package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.util.ArrayList;
import java.util.List;

/** Bundled word-shape features. No package, advertising artwork or screen position is stored. */
final class SkipFeatureBank {
 private final Template[] features;
 SkipFeatureBank(Template[] words) {
  List<Template> cached=new ArrayList<>();
  for(Template word:words)for(float size:new float[]{1f,1.1f,.9f,1.22f,1.3f,.8f,1.4f})cached.add(word.scaled(size));
  features=cached.toArray(new Template[0]);
 }
 Hit verify(Frame fine,Hit candidate) {
  float x=candidate.x*fine.width/(float)fine.originalWidth,y=candidate.y*fine.height/(float)fine.originalHeight;
  for(Template t:features) {
   // A small sample filters unrelated glyphs before the complete two-character test.
   for(float dy:new float[]{0,-.5f,.5f,-1,1,-1.5f,1.5f,-2,2})for(float dx:new float[]{0,-.5f,.5f,-1,1,-1.5f,1.5f,-2,2}) {
    float px=x+dx,py=y+dy;
    for(int mode:new int[]{-1,215,235,190}) {
     if(coarse(fine,t,px,py,mode)<.48f)continue;
     float all=mode==-1?valid(correlate(fine,t,px,py)):valid(AdLabelVerifier.strokes(fine,t,px,py,-1,mode));
     if(all<.82f || all>1.001f)continue;
     float first=mode==-1?valid(SplashPromptVerifier.half(fine,t,px,py,0)):valid(AdLabelVerifier.strokes(fine,t,px,py,0,mode));
     float second=mode==-1?valid(SplashPromptVerifier.half(fine,t,px,py,1)):valid(AdLabelVerifier.strokes(fine,t,px,py,1,mode));
     if(first>=.72f && first<1.001f && second>=.72f && second<1.001f)
      return fine.hit(candidate.rule,px,py,all).withCropScale(candidate.cropScale).withMemory(candidate.memoryMatch);
    }
   }
  }
  return null;
 }
 private static float valid(float value) {return value<=-1f?0:Math.abs(value);}
 private static float coarse(Frame f,Template t,float cx,float cy,int threshold) {
  if(cx<t.width/2+1 || cy<t.height/2+1 || cx+t.width/2>=f.width || cy+t.height/2>=f.height)return 0;
  double a=0,aa=0,b=0,bb=0,ab=0;int n=0;
  for(int row=1;row<t.rows;row+=3)for(int col=1;col<t.columns;col+=3) {
   int px=cx==(int)cx?(int)cx+t.sampleX[col]:(int)(cx-t.width/2+(col+.5f)*t.width/t.columns);
   int py=cy==(int)cy?(int)cy+t.sampleY[row]:(int)(cy-t.height/2+(row+.5f)*t.height/t.rows);
   float v=f.gray[py*f.width+px];
   if(threshold>=0)v=v>=threshold?255:0;
   float e=t.normalized[row*t.columns+col];a+=v;aa+=v*v;b+=e;bb+=e*e;ab+=v*e;n++;
  }
  double av=aa-a*a/n,bv=bb-b*b/n;
  return av<1 || bv<1?0:(float)Math.abs((ab-a*b/n)/Math.sqrt(av*bv));
 }
}
