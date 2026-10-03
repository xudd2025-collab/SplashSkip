package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Profile sizes are crop choices around a currently observed word, never click positions. */
final class ProfileTextMatcher {
    static final class Match {
        final int index,x,y;final int[] box;final float probability;
        Match(int index,Hit hit,int[] box,float p){this.index=index;x=hit.x;y=hit.y;this.box=box;probability=p;}
    }
    static Match find(Frame f,Template[] glyphs,int[][] dimensions,int referenceWidth,ControlTextMatcher.Model model) {
        float scale=f.originalWidth/(float)referenceWidth;
        java.util.List<float[]> regions=new java.util.ArrayList<>();regions.add(new float[]{.01f,.01f,.99f,.20f});
        for(int row=0;row<4;row++)for(int col=0;col<3;col++)regions.add(new float[]{Math.max(.01f,col/3f-.06f),Math.max(.01f,row/4f-.02f),Math.min(.99f,(col+1)/3f+.06f),Math.min(.99f,(row+1)/4f+.02f)});
        for(float[] region:regions) {
            for(Hit proposal:AdGlyphProposals.find(f,"profile-skip",region,1))for(float shift:new float[]{0,.66f,-.66f,1.33f,-1.33f}) {
                // A countdown and word can form one edge group. Inspect subwords within that
                // currently visible group; verification locates the real two-character word.
                int x=Math.round(proposal.x+shift*18*proposal.cropScale*f.originalWidth/f.width);
                Hit candidate=new Hit(proposal.rule,x,proposal.y,0,f.originalWidth,f.originalHeight).withCropScale(proposal.cropScale);
                for(int i=0;i<dimensions.length;i++) {
                    int w=Math.round(dimensions[i][0]*scale*candidate.cropScale),h=Math.round(dimensions[i][1]*scale*candidate.cropScale);
                    int[] box={candidate.x-w/2,candidate.y-h/2,candidate.x+(w+1)/2,candidate.y+(h+1)/2};
                    if(box[0]<0 || box[1]<0 || box[2]>f.originalWidth || box[3]>f.originalHeight)continue;
                    float p=model.probability(f,box,0);
                    if(p>=.95f && verified(f,candidate,glyphs))return new Match(i,candidate,box,p);
                }
            }
        }
        return null;
    }
    private static boolean verified(Frame f,Hit candidate,Template[] glyphs) {
        if(SkipGlyphVerifier.accepts(f,candidate,glyphs,candidate.cropScale,.75f))return true;
        Frame fine=new Frame(f.pixels,f.originalWidth,f.originalHeight,1216);
        float x=candidate.x*1216f/f.originalWidth,y=candidate.y*1216f/f.originalWidth;
        for(Template word:glyphs)for(float size:new float[]{1f,.9f,1.1f}) {
            Template t=word.scaled(candidate.cropScale*size*2);
            for(int dy=-4;dy<=4;dy++)for(int dx=-4;dx<=4;dx++)for(int threshold:new int[]{235,245,250}) {
                float px=x+dx*.5f,py=y+dy*.5f;
                if(AdLabelVerifier.strokes(fine,t,px,py,-1,threshold)<.70f)continue;
                if(AdLabelVerifier.strokes(fine,t,px,py,0,threshold)>=.60f && AdLabelVerifier.strokes(fine,t,px,py,1,threshold)>=.60f)return true;
            }
        }
        return false;
    }
}
