package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Independent four-character interaction text; backgrounds cannot replace either half. */
final class SplashPromptVerifier {
    static float[] find(Frame f,Template[] words,java.util.function.BooleanSupplier cancelled) {
        // Read word-shaped groups first. Texture elsewhere need not be compared to every pixel.
        for(float[] band:new float[][]{{.01f,.65f,.99f,.99f},{.01f,.01f,.99f,.66f}}) {
            if(cancelled.getAsBoolean())return null;
            for(Hit group:AdGlyphProposals.find(f,"interaction-word",band,1,true)) {
                float x=group.x*f.width/(float)f.originalWidth,y=group.y*f.height/(float)f.originalHeight;
                for(Template word:words) {
                    if(cancelled.getAsBoolean())return null;
                    if(correlate(f,word,x,y)<.35f)continue;
                    float[] candidate=refine(f,word,x,y,4);
                    if(candidate[2]>=.75f && half(f,word,candidate[0],candidate[1],0)>=.55f && half(f,word,candidate[0],candidate[1],1)>=.55f)return candidate;
                }
            }
        }
        for(Template word:words) {
            if(cancelled.getAsBoolean())return null;
            // Lower-page interaction text gets priority, with complete fallback elsewhere.
            // Stop at the first independently verified word instead of scanning for every match.
            for(float[] area:new float[][]{{0,f.height*.65f,f.width,f.height},{0,0,f.width,f.height*.65f+word.height}}) {
                if(cancelled.getAsBoolean())return null;
                float[] candidate=UiFeatureSearch.first(f,word,area[0],area[1],area[2],area[3],.75f,
                        p -> half(f,word,p[0],p[1],0)>=.55f && half(f,word,p[0],p[1],1)>=.55f);
                if(candidate!=null)return candidate;
            }
        }
        return null;
    }
    static float half(Frame f,Template t,float x,float y,int half) {
        double sum=0,square=0,dot=0,expected=0,expectedSquare=0;int count=0;
        for(int row=0,i=0;row<t.rows;row++)for(int col=0;col<t.columns;col++,i++) {
            if(half==0 && col>=t.columns/2 || half==1 && col<t.columns/2)continue;
            float value=grayAt(f,Math.round(x)+t.sampleX[col],Math.round(y)+t.sampleY[row]);
            sum+=value;square+=value*value;dot+=value*t.normalized[i];expected+=t.normalized[i];expectedSquare+=t.normalized[i]*t.normalized[i];count++;
        }
        double v=square-sum*sum/count,e=expectedSquare-expected*expected/count;
        return v<1 || e<1?-1:(float)((dot-sum*expected/count)/Math.sqrt(v*e));
    }
}
