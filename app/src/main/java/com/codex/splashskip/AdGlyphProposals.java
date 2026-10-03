package com.codex.splashskip;

import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.util.ArrayList;
import java.util.List;

/** Proposes text-sized edge groups. The learned classifier, not these edges, identifies skip text. */
final class AdGlyphProposals {
    static Hit refineModel(Frame f,Hit proposal,AdButtonClassifier classifier,float probability) {
        Hit best=proposal.withModel(probability);
        int step=Math.max(1,Math.round(f.originalWidth/1216f));
        for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++) {
            if(dx==0 && dy==0)continue;
            Hit candidate=new Hit(proposal.rule,proposal.x+dx*step,proposal.y+dy*step,proposal.score,f.originalWidth,f.originalHeight)
                    .withCropScale(proposal.cropScale).withMemory(proposal.memoryMatch);
            float p=classifier.probability(f,candidate);
            if(p>best.modelProbability)best=candidate.withModel(p);
            if(p>=.985f)return best;
        }
        return best;
    }
    static List<Hit> find(Frame f,String rule,float[] region,float cropScale) {
        return find(f,rule,region,cropScale,false);
    }
    static List<Hit> find(Frame f,String rule,float[] region,float cropScale,boolean subwords) {
        int left=Math.max(2,(int)(f.width*region[0])),top=Math.max(2,(int)(f.height*region[1]));
        int right=Math.min(f.width-2,(int)(f.width*region[2])),bottom=Math.min(f.height-2,(int)(f.height*region[3]));
        int w=right-left,h=bottom-top;List<Hit> hits=new ArrayList<>();
        if(w<20 || h<8)return hits;
        // Isolate bright/dark strokes before edge grouping. A translucent button over
        // textured content otherwise joins its letters to the background edges.
        for(int mode=0;mode<3;mode++) {
        List<int[]> boxes=new ArrayList<>();
        boolean[] mask=new boolean[w*h];int[] queue=new int[w*h],neighbors={-1,1,-w,w};
        for(int y=1;y<h-1;y++)for(int x=2;x<w-2;x++) {
            int p=(top+y)*f.width+left+x;float c=f.gray[p];
            float edge=Math.max(Math.abs(c-f.gray[p-1]),Math.abs(c-f.gray[p+1]));
            edge=Math.max(edge,Math.max(Math.abs(c-f.gray[p-f.width]),Math.abs(c-f.gray[p+f.width])));
            if(mode==0?c<215:mode==1?c>55:edge<18)continue;
            for(int dy=-1;dy<=1;dy++)for(int dx=-2;dx<=2;dx++)mask[(y+dy)*w+x+dx]=true;
        }
        for(int start=0;start<mask.length;start++)if(mask[start]) {
            int count=1,read=0;queue[0]=start;mask[start]=false;
            int minX=start%w,maxX=minX,minY=start/w,maxY=minY;
            while(read<count) {
                int p=queue[read++],x=p%w,y=p/w;
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                for(int d:neighbors) {
                    int next=p+d;
                    if(next<0 || next>=mask.length || (d==-1 && x==0) || (d==1 && x==w-1) || !mask[next])continue;
                    mask[next]=false;queue[count++]=next;
                }
            }
            int textW=maxX-minX-3,textH=maxY-minY-1;
            if(subwords && textH>=4 && textH<=40 && textW>textH*3.4f && textW<400 &&
                    minX>0 && minY>0 && maxX<w-1 && maxY<h-1 && count>=45) {
                float halfWord=textH*1.1f,scale=Math.max(.3f,Math.min(1.6f,textH/16f));
                float end=maxX-halfWord,startCenter=minX+halfWord;
                for(float center=end;center>=startCenter;center=Math.max(startCenter,center-Math.max(2,textH/4f))) {
                    hits.add(f.hit(rule,left+center,top+(minY+maxY)/2f,0).withCropScale(scale*cropScale));
                    if(hits.size()>=96)return hits;
                    if(center==startCenter)break;
                }
            }
            if(textW>=5 && textW<=100 && textH>=(subwords?4:8) && textH<=40 && count>=25 &&
                    minX>0 && minY>0 && maxX<w-1 && maxY<h-1)boxes.add(new int[]{minX,minY,maxX,maxY});
            if(textW<(subwords?10:20) || textW>100 || textH<(subwords?4:8) || textH>40 || textW<textH*1.4f || textW>textH*3.8f ||
                    minX==0 || minY==0 || maxX==w-1 || maxY==h-1 || count<45)continue;
            float scale=subwords?Math.max(.3f,Math.min(1.6f,textW/36f)):Math.max(.6f,Math.min(1.6f,textH/18f));
            Hit hit=f.hit(rule,left+(minX+maxX)/2f,top+(minY+maxY)/2f,0);
            // Keep the usual crop first; alternate crop sizes handle different text sizes.
            hits.add(hit.withCropScale((subwords?scale:1)*cropScale));
            if(Math.abs(scale-1)>.12f)hits.add(hit.withCropScale(scale*cropScale));
            if(hits.size()>=(subwords?96:24))return hits;
        }
        // Some thin fonts leave the two characters disconnected after dilation.
        for(int[] a:boxes)for(int[] b:boxes) {
            if(b[0]<=a[2])continue;
            int ah=a[3]-a[1],bh=b[3]-b[1],gap=b[0]-a[2];
            if(gap>Math.max(ah,bh)*.9f || Math.min(ah,bh)<Math.max(ah,bh)*.65f ||
                    Math.min(a[3],b[3])-Math.max(a[1],b[1])<Math.min(ah,bh)*.6f)continue;
            int minY=Math.min(a[1],b[1]),maxY=Math.max(a[3],b[3]);
            int textW=b[2]-a[0]-3,textH=maxY-minY-1;
            if(textW<(subwords?10:20) || textW>100 || textW<textH*1.4f || textW>textH*3.8f)continue;
            Hit hit=f.hit(rule,left+(a[0]+b[2])/2f,top+(minY+maxY)/2f,0);
            hits.add(hit.withCropScale(cropScale));
            float scale=Math.max(.6f,Math.min(1.6f,textH/18f));
            if(Math.abs(scale-1)>.12f)hits.add(hit.withCropScale(scale*cropScale));
            if(hits.size()>=(subwords?96:24))return hits;
        }
        }
        return hits;
    }
}
