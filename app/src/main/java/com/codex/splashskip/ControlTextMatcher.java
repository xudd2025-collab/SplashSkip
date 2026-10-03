package com.codex.splashskip;

import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.util.ArrayList;
import java.util.List;

/** Shared control semantics, without package names, creative templates or saved coordinates. */
final class ControlTextMatcher {
    static final String CLOSE="universal-ad-text-close";
    interface Model { float probability(Frame f,int[] box,int head); }
    private final Template[] words;
    ControlTextMatcher(Template[] words) {this.words=words;}
    static Template glyph(int[] pixels,int width,int height) {
        int l=width,r=0,t=height,b=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)if((pixels[y*width+x]&255)>127){l=Math.min(l,x);r=Math.max(r,x);t=Math.min(t,y);b=Math.max(b,y);}
        l=Math.max(0,l-1);r=Math.min(width-1,r+1);t=Math.max(0,t-1);b=Math.min(height-1,b+1);
        int w=r-l+1,h=b-t+1;int[] crop=new int[w*h];
        for(int y=0;y<h;y++)System.arraycopy(pixels,(t+y)*width+l,crop,y*w,w);
        return new Template(crop,w,h,64,20);
    }
    Hit find(Frame source,Model model,java.util.function.BooleanSupplier cancelled) {
        if(model==null)return null;
        int width=source.originalWidth>source.originalHeight?Math.round(608f*source.originalWidth/source.originalHeight):608;
        Frame f=source.width==width?source:new Frame(source.pixels,source.originalWidth,source.originalHeight,width);
        for(int polarity=0;polarity<2;polarity++) {
            for(float[] proposal:proposals(f,polarity)) {
                if(cancelled.getAsBoolean())return null;
                float x=proposal[0],y=proposal[1],height=proposal[2];
                for(Template word:words)for(float ratio:new float[]{1.1f,1f,1.2f,.9f}) {
                    Template t=word.scaled(height/word.height*ratio);
                    float cx=x+t.width/2;
                    if(Math.abs(correlate(f,t,cx,y))<.25f)continue;
                    Template expected=polarity==0?t:t.inverted();
                    float[] hit=refine(f,expected,cx,y,3);
                    if(hit[2]<.53f)continue;
                    float weakest=1;
                    for(int letter=0;letter<4;letter++)weakest=Math.min(weakest,UiFeatureSearch.part(f,expected,hit[0],hit[1],letter*16,0,(letter+1)*16,20));
                    if(weakest<.32f || !button(f,t,hit[0],hit[1]) || followingCharacter(f,t,hit[0],hit[1],polarity))continue;
                    int[] box=box(f,hit[0],hit[1],t.width,t.height);
                    if(box==null)continue;
                    float p=model.probability(f,box,0);
                    float required=hit[2]>=.60f && weakest>=.45f?.90f:.98f;
                    if(p>=required)return f.hit(CLOSE,hit[0],hit[1],hit[2]).withModel(p);
                }
            }
        }
        return null;
    }
    private boolean followingCharacter(Frame f,Template t,float x,float y,int polarity) {
        float character=t.width/4,edge=x+t.width/2;int strokes=0,total=0;
        for(int py=(int)(y-t.height*.40f);py<y+t.height*.05f;py++)for(int px=(int)(edge+character*.20f);px<edge+character*.85f;px++) {
            float v=grayAt(f,px,py);if(polarity==0?v>=215:v<=65)strokes++;total++;
        }
        // A continuing Chinese word such as “关闭广告功能” is a different action.
        // A comma followed by an explanatory clause, as in the iQiyi control, is allowed.
        return total>0 && strokes>total*.17f;
    }
    static int[] box(Frame f,float x,float y,float w,float h) {
        float scale=f.originalWidth/(float)f.width;
        int l=Math.round((x-w/2-1)*scale),r=Math.round((x+w/2+1)*scale);
        int t=Math.round((y-h/2-1)*scale),b=Math.round((y+h/2+1)*scale);
        return l>=0 && t>=0 && r<=f.originalWidth && b<=f.originalHeight && r>l && b>t?new int[]{l,t,r,b}:null;
    }
    private boolean button(Frame f,Template t,float x,float y) {
        // Flat padding above/below the entire word, bounded by an actual control edge.
        // This rejects the same phrase in a paragraph or settings description.
        float above=y-t.height*.80f,below=y+t.height*.80f;
        float[] a=new float[3],b=new float[3];
        for(int i=0;i<3;i++){float px=x+(i-1)*t.width*.35f;a[i]=grayAt(f,px,above);b[i]=grayAt(f,px,below);}
        for(int i=1;i<3;i++)if(Math.abs(a[i]-a[0])>24 || Math.abs(b[i]-b[0])>24)return false;
        if(Math.abs(a[0]-b[0])>26)return false;
        float bg=(a[0]+b[0])/2;
        for(float distance:new float[]{1.1f,1.4f,1.7f,2f,2.4f}) {
            float top=y-t.height*distance,bottom=y+t.height*distance;
            if(top<1 || bottom>=f.height-1)continue;
            if(Math.abs(grayAt(f,x,top)-bg)>28 && Math.abs(grayAt(f,x,bottom)-bg)>28)return true;
        }
        return false;
    }
    static List<float[]> proposals(Frame f,int mode) {
        int w=f.width,h=f.height;boolean[] mask=new boolean[w*h];int[] queue=new int[w*h];
        for(int y=2;y<h-2;y++)for(int x=2;x<w-2;x++) {
            float c=f.gray[y*w+x];if(mode==0?c<215:c>65)continue;
            // Require an edge; large flat bright regions are not glyphs.
            float edge=Math.max(Math.abs(c-f.gray[y*w+x-2]),Math.abs(c-f.gray[y*w+x+2]));
            edge=Math.max(edge,Math.max(Math.abs(c-f.gray[(y-2)*w+x]),Math.abs(c-f.gray[(y+2)*w+x])));
            if(edge<32)continue;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)mask[(y+dy)*w+x+dx]=true;
        }
        List<int[]> boxes=new ArrayList<>();int[] directions={-1,1,-w,w};
        for(int start=0;start<mask.length;start++)if(mask[start]) {
            int count=1,read=0;queue[0]=start;mask[start]=false;
            int l=start%w,r=l,t=start/w,b=t;
            while(read<count){int p=queue[read++],x=p%w,y=p/w;l=Math.min(l,x);r=Math.max(r,x);t=Math.min(t,y);b=Math.max(b,y);
                for(int d:directions){int n=p+d;if(n<0 || n>=mask.length || d==-1 && x==0 || d==1 && x==w-1 || !mask[n])continue;mask[n]=false;queue[count++]=n;}}
            int bh=b-t-1,bw=r-l-1;
            if(bh>=7 && bh<=38 && bw>=4 && bw<=400 && count>=25 && l>0 && r<w-1 && t>0 && b<h-1)boxes.add(new int[]{l+1,t+1,r,b});
        }
        List<float[]> result=new ArrayList<>();
        for(int[] a:boxes) {
            float ah=a[3]-a[1];
            // Propose from each currently visible glyph/line start, including a long label prefix.
            result.add(new float[]{a[0],(a[1]+a[3])/2f,ah});
            if(a[2]-a[0]>ah*5)for(float px=a[0]+ah;px<a[2]-ah*3;px+=ah)result.add(new float[]{px,(a[1]+a[3])/2f,ah});
        }
        return result;
    }
}
