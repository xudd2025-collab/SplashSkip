package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Only the two ad-label glyphs; branding and the creative body are not compared. */
final class AdLabelVerifier {
    static float[] region(Frame source,float[] region,Template[] words,java.util.function.BooleanSupplier cancelled) {
        Frame f=source.width==608?source:new Frame(source.pixels,source.originalWidth,source.originalHeight,608);
        Frame fine=source.width==1216?source:new Frame(source.pixels,source.originalWidth,source.originalHeight,1216);
        for(Hit proposal:AdGlyphProposals.find(f,"ad-label",region,1,true)) {
            if(cancelled.getAsBoolean())return null;
            float x=proposal.x*1216f/f.originalWidth,y=proposal.y*1216f/f.originalWidth;
            for(Template word:words)for(float size:new float[]{1f,.9f,1.1f,.8f,1.2f}) {
                Template sized=word.scaled(proposal.cropScale*size*2);
                if(!possible(fine,sized,x,y))continue;
                for(int dy=-3;dy<=3;dy++)for(int dx=-4;dx<=4;dx++)for(int threshold:new int[]{215,190,235}) {
                    float px=x+dx,py=y+dy,value=strokes(fine,sized,px,py,-1,threshold);
                    if(value<=-1f || Math.abs(value)<.70f)continue;
                    float first=strokes(fine,sized,px,py,0,threshold),second=strokes(fine,sized,px,py,1,threshold);
                    if(first>-1f && second>-1f && Math.abs(first)>=.60f && Math.abs(second)>=.60f)
                        return new float[]{px*source.width/1216f,py*source.width/1216f,Math.abs(value)};
                }
            }
        }
        return null;
    }
    static Template glyph(int[] pixels,int width,int height) {
        int left=width,top=height,right=0,bottom=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)if((pixels[y*width+x]&255)>127) {
            left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
        }
        left=Math.max(0,left-2);right=Math.min(width-1,right+2);top=Math.max(0,top-2);bottom=Math.min(height-1,bottom+2);
        int w=right-left+1,h=bottom-top+1;int[] cropped=new int[w*h];
        for(int y=0;y<h;y++)System.arraycopy(pixels,(top+y)*width+left,cropped,y*w,w);
        return new Template(cropped,w,h,32,20);
    }
    static float[] header(Frame source,float skipY,Template[] words,java.util.function.BooleanSupplier cancelled) {return header(source,skipY,words,cancelled,null);}
    static float[] header(Frame source,float skipY,Template[] words,java.util.function.BooleanSupplier cancelled,ControlTextMatcher.Model labelModel) {
        if(words.length==0)return null;
        Frame f=source.width==608?source:new Frame(source.pixels,source.originalWidth,source.originalHeight,608);
        Frame fine=source.width==1216?source:new Frame(source.pixels,source.originalWidth,source.originalHeight,1216);
        float centerY=skipY/source.height;
        java.util.List<float[]> regions=new java.util.ArrayList<>();
        regions.add(new float[]{.01f,Math.max(.01f,centerY-.06f),.99f,Math.min(.99f,centerY+.06f)});
        // Tile the rest of the screen so a candidate limit in a busy region
        // cannot hide a label elsewhere. The first band is only a priority.
        for(int row=0;row<4;row++)for(int col=0;col<3;col++)
            regions.add(new float[]{Math.max(.01f,col/3f-.06f),Math.max(.01f,row/4f-.02f),Math.min(.99f,(col+1)/3f+.06f),Math.min(.99f,(row+1)/4f+.02f)});
        for(float[] region:regions) {
        for(Hit proposal:AdGlyphProposals.find(f,"ad-label",region,1,true)) {
            if(cancelled.getAsBoolean())return null;
            float x=proposal.x*1216f/f.originalWidth,y=proposal.y*1216f/f.originalWidth;
            if(labelModel!=null) {
                Template t=words[0].scaled(proposal.cropScale*2);
                int[] box=ControlTextMatcher.box(fine,x,y,t.width,t.height);
                if(box==null || labelModel.probability(fine,box,1)<.10f)continue;
            }
            for(Template word:words)for(float size:new float[]{1f,.9f,1.1f,.8f,1.2f}) {
                Template sized=word.scaled(proposal.cropScale*size*2);
                if(!possible(fine,sized,x,y))continue;
                for(int dy=-3;dy<=3;dy++)for(int dx=-4;dx<=4;dx++) {
                    float px=x+dx,py=y+dy;
                    for(int threshold:new int[]{215,190,235}) {
                    float value=strokes(fine,sized,px,py,-1,threshold);
                    float score=Math.abs(value);
                    if(value<=-1f || score<.70f)continue;
                    float first=strokes(fine,sized,px,py,0,threshold),second=strokes(fine,sized,px,py,1,threshold);
                    if(first>-1f && second>-1f && Math.abs(first)>=.60f && Math.abs(second)>=.60f)
                        return new float[]{px*source.width/1216f,py*source.width/1216f,score};
                    }
                }
            }
        }
        }
        return null;
    }
    private static boolean possible(Frame f,Template t,float x,float y) {
        for(int dy=-2;dy<=2;dy+=2)for(int dx=-3;dx<=3;dx+=3) {
            if(Math.abs(correlate(f,t,x+dx,y+dy))>=.36f)return true;
        }
        return false;
    }
    static float strokes(Frame f,Template t,float cx,float cy) {
        return strokes(f,t,cx,cy,-1);
    }
    static float strokes(Frame f,Template t,float cx,float cy,int half) {
        return strokes(f,t,cx,cy,half,215);
    }
    static float strokes(Frame f,Template t,float cx,float cy,int half,int threshold) {
        float left=cx-t.width/2f,top=cy-t.height/2f;
        if(left<0 || top<0 || left+t.width>=f.width || top+t.height>=f.height)return -1;
        double sum=0,square=0,dot=0,expected=0,expectedSquare=0;int count=0;
        for(int y=0,i=0;y<t.rows;y++)for(int x=0;x<t.columns;x++,i++) {
            int px=(int)(left+(x+.5f)*t.width/t.columns),py=(int)(top+(y+.5f)*t.height/t.rows);
            if(half==0 && x>=t.columns/2 || half==1 && x<t.columns/2)continue;
            float value=f.gray[py*f.width+px]>=threshold?255:0;
            sum+=value;square+=value*value;dot+=value*t.normalized[i];
            expected+=t.normalized[i];expectedSquare+=t.normalized[i]*t.normalized[i];count++;
        }
        double variance=square-sum*sum/count,expectedVariance=expectedSquare-expected*expected/count;
        return variance<1 || expectedVariance<1?-1:(float)((dot-sum*expected/count)/Math.sqrt(variance*expectedVariance));
    }
}
