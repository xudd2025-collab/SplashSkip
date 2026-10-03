package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.util.ArrayList;
import java.util.List;

/** Finds actual UI glyphs in a region. Regions express scene relationships, never tap coordinates. */
final class UiFeatureSearch {
    private static final ThreadLocal<java.util.function.BooleanSupplier> cancellation=new ThreadLocal<>();
    static void setCancellation(java.util.function.BooleanSupplier test) {if(test==null)cancellation.remove();else cancellation.set(test);}
    static boolean cancelled(){java.util.function.BooleanSupplier test=cancellation.get();return test!=null && test.getAsBoolean();}
    static List<float[]> all(Frame f,Template t,float threshold) {
        return all(f,t,t.width/2+1,t.height/2+1,f.width-t.width/2-1,f.height-t.height/2-1,threshold,12);
    }
    static float[] find(Frame f,Template t,float left,float top,float right,float bottom,float threshold) {
        List<float[]> hits=all(f,t,left,top,right,bottom,threshold,12);
        return hits.isEmpty()?null:hits.get(0);
    }
    static List<float[]> all(Frame f,Template t,float left,float top,float right,float bottom,float threshold,int limit) {
        return all(f,t,left,top,right,bottom,threshold,limit,null);
    }
    static float[] first(Frame f,Template t,float left,float top,float right,float bottom,float threshold,java.util.function.Predicate<float[]> accepts) {
        List<float[]> hits=all(f,t,left,top,right,bottom,threshold,1,accepts);
        return hits.isEmpty()?null:hits.get(0);
    }
    private static List<float[]> all(Frame f,Template t,float left,float top,float right,float bottom,float threshold,int limit,java.util.function.Predicate<float[]> accepts) {
        List<float[]> hits=new ArrayList<>();
        if(cancelled())return hits;
        left=Math.max(t.width/2+1,left);top=Math.max(t.height/2+1,top);
        right=Math.min(f.width-t.width/2-1,right);bottom=Math.min(f.height-t.height/2-1,bottom);
        // Thin glyphs need a dense proposal grid. A small independent sample
        // rejects unrelated pixels before the full template and refinement.
        int cols=Math.min(12,t.columns),rows=Math.min(8,t.rows),n=cols*rows;
        float[] expected=new float[n];int[] ox=new int[n],oy=new int[n];double sum=0,sq=0;
        for(int y=0,i=0;y<rows;y++)for(int x=0;x<cols;x++,i++) {
            int tx=Math.min(t.columns-1,(int)((x+.5f)*t.columns/cols)),ty=Math.min(t.rows-1,(int)((y+.5f)*t.rows/rows));
            expected[i]=t.normalized[ty*t.columns+tx];sum+=expected[i];sq+=expected[i]*expected[i];
            ox[i]=t.sampleX[tx];oy[i]=t.sampleY[ty];
        }
        double mean=sum/n,variance=sq-sum*sum/n;
        if(variance<1)return hits;
        for(int i=0;i<n;i++)expected[i]-=mean;
        for(int y=(int)top;y<bottom;y+=2)for(int x=(int)left;x<right;x+=2) {
            if(x==(int)left && cancelled())return new ArrayList<>();
            double actual=0,actualSquare=0,dot=0;
            for(int i=0;i<n;i++){float v=f.gray[(y+oy[i])*f.width+x+ox[i]];actual+=v;actualSquare+=v*v;dot+=v*expected[i];}
            double av=actualSquare-actual*actual/n;
            if(av<1 || dot/Math.sqrt(av*variance)<.38)continue;
            if(correlate(f,t,x,y)<.50f)continue;
            float[] hit=refine(f,t,x,y,4);if(hit[2]<threshold)continue;
            hit=refine(f,t,hit[0],hit[1],4);
            if(accepts!=null && !accepts.test(hit))continue;
            boolean duplicate=false;for(float[] prior:hits)if(Math.abs(prior[0]-hit[0])<t.width*.4f && Math.abs(prior[1]-hit[1])<t.height*.4f){if(hit[2]>prior[2])System.arraycopy(hit,0,prior,0,3);duplicate=true;break;}
            if(!duplicate) {hits.add(hit);if(hits.size()>=limit){hits.sort((a,b)->Float.compare(b[2],a[2]));return hits;}}
        }
        hits.sort((a,b)->Float.compare(b[2],a[2]));
        return hits;
    }
    static float part(Frame f,Template t,float cx,float cy,int left,int top,int right,int bottom) {
        double sum=0,square=0,dot=0,expected=0,expectedSquare=0;int count=0;
        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++) {
            int px=(int)(cx-t.width/2+(x+.5f)*t.width/t.columns),py=(int)(cy-t.height/2+(y+.5f)*t.height/t.rows);
            if(px<0 || py<0 || px>=f.width || py>=f.height)return -1;
            float a=f.gray[py*f.width+px],b=t.normalized[y*t.columns+x];
            sum+=a;square+=a*a;dot+=a*b;expected+=b;expectedSquare+=b*b;count++;
        }
        double va=square-sum*sum/count,vb=expectedSquare-expected*expected/count;
        return va<1 || vb<1?-1:(float)((dot-sum*expected/count)/Math.sqrt(va*vb));
    }
}
