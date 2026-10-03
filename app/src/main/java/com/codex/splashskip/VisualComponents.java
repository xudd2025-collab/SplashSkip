package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.util.ArrayList;
import java.util.List;

/** Connected UI surfaces and glyphs in screen-normalized pixels. No creative templates. */
final class VisualComponents {
    interface Mask {boolean includes(Frame f,int x,int y);}
    static final Mask WHITE=(f,x,y)->grayAt(f,x,y)>=236;
    static final Mask BRIGHT=(f,x,y)->grayAt(f,x,y)>=160;
    static final Mask LIGHT=(f,x,y)->grayAt(f,x,y)>=220;
    static final Mask GRAY=(f,x,y)->grayAt(f,x,y)>=70 && grayAt(f,x,y)<=218;
    static final Mask BLUE=(f,x,y)-> {
        int c=color(f,x,y),r=(c>>16)&255,g=(c>>8)&255,b=c&255;
        return b>r+40 && b>g+25 && b>150;
    };
    static int color(Frame f,int x,int y) {
        int ix=Math.min(f.originalWidth-1,Math.max(0,Math.round(x*f.originalWidth/(float)f.width)));
        int iy=Math.min(f.originalHeight-1,Math.max(0,Math.round(y*f.originalHeight/(float)f.height)));
        return f.pixels[iy*f.originalWidth+ix];
    }
    /** box: left, top, right-exclusive, bottom-exclusive, pixel count. */
    static List<int[]> boxes(Frame f,int left,int top,int right,int bottom,Mask mode,int minCount) {
        left=Math.max(1,left);top=Math.max(1,top);right=Math.min(f.width-1,right);bottom=Math.min(f.height-1,bottom);
        int w=right-left,h=bottom-top;List<int[]> result=new ArrayList<>();if(w<1 || h<1 || UiFeatureSearch.cancelled())return result;
        boolean[] mask=new boolean[w*h];int[] queue=new int[w*h];
        for(int y=0;y<h;y++) {
            if(UiFeatureSearch.cancelled())return new ArrayList<>();
            for(int x=0;x<w;x++)mask[y*w+x]=mode.includes(f,left+x,top+y);
        }
        for(int start=0;start<mask.length;start++) {
            if((start&4095)==0 && UiFeatureSearch.cancelled())return new ArrayList<>();
            if(!mask[start])continue;
            int read=0,count=1;queue[0]=start;mask[start]=false;int minX=start%w,maxX=minX,minY=start/w,maxY=minY;
            while(read<count) {
                if((read&2047)==0 && UiFeatureSearch.cancelled())return new ArrayList<>();
                int p=queue[read++],x=p%w,y=p/w;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
                    if(dx==0 && dy==0 || x+dx<0 || x+dx>=w || y+dy<0 || y+dy>=h)continue;
                    int next=p+dy*w+dx;if(mask[next]) {mask[next]=false;queue[count++]=next;}
                }
            }
            if(count>=minCount && minX>0 && minY>0 && maxX<w-1 && maxY<h-1)
                result.add(new int[]{left+minX,top+minY,left+maxX+1,top+maxY+1,count});
        }
        result.sort((a,b)->Integer.compare(b[4],a[4]));return result;
    }
    static float cross(Frame f,int[] b,boolean light) {
        float best=crossBox(f,b,light);
        // Component bounds quantize differently after resizing. Search only
        // a one-pixel adjustment, still requiring both diagonal strokes.
        for(int left=-1;left<=1;left++)for(int top=-1;top<=1;top++)
            for(int right=-1;right<=1;right++)for(int bottom=-1;bottom<=1;bottom++)
                best=Math.max(best,crossBox(f,new int[]{b[0]+left,b[1]+top,b[2]+right,b[3]+bottom},light));
        return best;
    }
    private static float crossBox(Frame f,int[] b,boolean light) {
        float w=b[2]-b[0],h=b[3]-b[1];if(w<4 || h<4 || w/h<.72f || w/h>1.38f)return -1;
        double sum=0,square=0,dot=0,expected=0,expectedSquare=0;int count=0;
        for(int y=0;y<20;y++)for(int x=0;x<20;x++) {
            float value=grayAt(f,b[0]+(x+.5f)*w/20,b[1]+(y+.5f)*h/20);if(!light)value=255-value;
            float ideal=Math.abs(x-y)<=2 || Math.abs(x+y-19)<=2?255:0;
            sum+=value;square+=value*value;dot+=value*ideal;expected+=ideal;expectedSquare+=ideal*ideal;count++;
        }
        double variance=square-sum*sum/count,ev=expectedSquare-expected*expected/count;
        return variance<1 || ev<1?-1:(float)((dot-sum*expected/count)/Math.sqrt(variance*ev));
    }
}
