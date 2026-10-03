package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Independent word-shape verification; templates contain only two glyphs, never ad creatives. */
final class SkipGlyphVerifier {
    private static final int[][] OFFSETS=nearestOffsets();
    private static int[][] nearestOffsets() {
        java.util.List<int[]> points=new java.util.ArrayList<>();
        for(int dy=-6;dy<=6;dy++)for(int dx=-6;dx<=6;dx++)points.add(new int[]{dx,dy});
        java.util.Collections.sort(points,(a,b)->Integer.compare(a[0]*a[0]+a[1]*a[1],b[0]*b[0]+b[1]*b[1]));
        return points.toArray(new int[points.size()][]);
    }
    static boolean accepts(Frame f,Hit candidate,Template[] words,float cropScale,float threshold) {
        float x=candidate.x*f.width/(float)f.originalWidth,y=candidate.y*f.height/(float)f.originalHeight;
        float pixelStep=f.width/608f*.5f;
        for(Template word:words)for(float size:new float[]{1f,.9f,1.1f}) {
            Template sized=word.scaled(cropScale*size);
            // Dark lettering on a light control has the same strokes with inverted luminance.
            // Proposals already locate the text center. Try nearby offsets first; keep the
            // same complete search and threshold for controls needing a larger adjustment.
            for(int[] offset:OFFSETS) {
                float value=correlate(f,sized,x+offset[0]*pixelStep,y+offset[1]*pixelStep);
                if(value>-1f && Math.abs(value)>=threshold) {
                    float px=x+offset[0]*pixelStep,py=y+offset[1]*pixelStep;
                    float first=SplashPromptVerifier.half(f,sized,px,py,0),second=SplashPromptVerifier.half(f,sized,px,py,1);
                    if(first>-1f && second>-1f && Math.abs(first)>=.65f && Math.abs(second)>=.65f)return true;
                }
            }
        }
        return false;
    }
    static boolean strokesAccepts(Frame source,Hit hit,Template[] words) {
        Frame f=source.width==1216?source:new Frame(source.pixels,source.originalWidth,source.originalHeight,1216);
        float cx=hit.x*1216f/source.originalWidth,cy=hit.y*1216f/source.originalWidth;
        for(Template word:words)for(float size:new float[]{1f,.9f,1.1f}) {
            Template t=word.scaled(hit.cropScale*size*2);
            float coarse=0;
            for(int dy:new int[]{0,-2,2})for(int dx:new int[]{0,-2,2})coarse=Math.max(coarse,AdLabelVerifier.strokes(f,t,cx+dx,cy+dy));
            if(coarse<.50f)continue;
            for(int[] offset:OFFSETS) {
                float x=cx+offset[0]*.5f,y=cy+offset[1]*.5f;
                float v=AdLabelVerifier.strokes(f,t,x,y);
                if(v<.75f)continue;
                if(AdLabelVerifier.strokes(f,t,x,y,0)>=.70f && AdLabelVerifier.strokes(f,t,x,y,1)>=.70f)return true;
            }
        }
        return false;
    }
}
