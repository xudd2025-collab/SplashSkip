package com.codex.splashskip;

/** Local UI feature matching, independent of Android so screenshot regressions can run on a PC. */
final class BilibiliVisualMatcher {
    static final String AD = "bili-ad-close", LIVE = "bili-live-cancel";
    static final class Hit {
        final String rule;
        final int x, y, frameWidth, frameHeight;
        final float score, modelProbability, cropScale;
        final float[] buttonSignature;
        final boolean memoryMatch;
        int[] textBounds;
        String structure;
        float[] jointFeatures;
        boolean treeAssisted, nativeOnly;
        Hit(String rule, int x, int y, float score) { this(rule,x,y,score,0,0); }
        Hit(String rule,int x,int y,float score,int frameWidth,int frameHeight) {
            this(rule,x,y,score,frameWidth,frameHeight,Float.NaN,1f,null,false);
        }
        private Hit(String rule,int x,int y,float score,int frameWidth,int frameHeight,float modelProbability,float cropScale,float[] signature,boolean remembered) {
            this.rule=rule;this.x=x;this.y=y;this.score=score;this.frameWidth=frameWidth;this.frameHeight=frameHeight;
            this.modelProbability=modelProbability;this.cropScale=cropScale;
            buttonSignature=signature;memoryMatch=remembered;
        }
        private Hit metadata(Hit copy){copy.textBounds=textBounds==null?null:textBounds.clone();copy.structure=structure;copy.jointFeatures=jointFeatures==null?null:jointFeatures.clone();copy.treeAssisted=treeAssisted;copy.nativeOnly=nativeOnly;return copy;}
        Hit withModel(float probability) { return metadata(new Hit(rule,x,y,score,frameWidth,frameHeight,probability,cropScale,buttonSignature,memoryMatch)); }
        Hit withCropScale(float scale) { return metadata(new Hit(rule,x,y,score,frameWidth,frameHeight,modelProbability,scale,buttonSignature,memoryMatch)); }
        Hit withSignature(float[] signature) { return metadata(new Hit(rule,x,y,score,frameWidth,frameHeight,modelProbability,cropScale,signature,memoryMatch)); }
        Hit withMemory(boolean remembered) { return metadata(new Hit(rule,x,y,score,frameWidth,frameHeight,modelProbability,cropScale,buttonSignature,remembered)); }
        Hit withTextBounds(int l,int t,int r,int b){textBounds=new int[]{l,t,r,b};return this;}
    }
    static final class Frame {
        final int width, height, originalWidth, originalHeight;
        final int[] pixels;
        final float[] gray;
        Frame(int[] pixels, int w, int h) {
            this(pixels,w,h,608);
        }
        Frame(int[] pixels, int w, int h, int processingWidth) {
            this.pixels=pixels; width=processingWidth;
            originalWidth = w; originalHeight = h;
            height = Math.round(h * width / (float) w);
            gray = new float[width * height];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int c = pixels[Math.min(h - 1, Math.round(y * h / (float) height)) * w +
                        Math.min(w - 1, Math.round(x * w / (float) width))];
                gray[y * width + x] = luminance(c);
            }
        }
        Hit hit(String rule, float x, float y, float score) {
            return new Hit(rule, Math.round(x * originalWidth / width), Math.round(y * originalHeight / height), score,originalWidth,originalHeight);
        }
    }
    static final class Template {
        final float width, height;
        final int columns, rows;
        final float[] normalized;
        final int[] sampleX, sampleY;
        final double norm;
        private Template(Template source,float scale) {this(source,scale,false);}
        private Template(Template source,float scale,boolean inverse) {
            width=source.width*scale;height=source.height*scale;columns=source.columns;rows=source.rows;
            normalized=inverse?source.normalized.clone():source.normalized;norm=source.norm;
            if(inverse)for(int i=0;i<normalized.length;i++)normalized[i]=-normalized[i];
            sampleX=offsets(width,columns);sampleY=offsets(height,rows);
        }
        Template scaled(float factor) { return new Template(this,factor); }
        Template inverted() {return new Template(this,1f,true);}
        Template(int[] pixels, int w, int h, int columns, int rows) {
            width = w / 2f; height = h / 2f; this.columns = columns; this.rows = rows;
            normalized = new float[columns * rows];
            float mean = 0;
            for (int y = 0; y < rows; y++) for (int x = 0; x < columns; x++) {
                float value = luminance(pixels[Math.min(h - 1, (int)((y + .5f) * h / rows)) * w +
                        Math.min(w - 1, (int)((x + .5f) * w / columns))]);
                normalized[y * columns + x] = value; mean += value;
            }
            mean /= normalized.length;
            double squared = 0;
            for (int i = 0; i < normalized.length; i++) { normalized[i] -= mean; squared += normalized[i] * normalized[i]; }
            norm = Math.sqrt(squared);
            sampleX=offsets(width,columns);sampleY=offsets(height,rows);
        }
        private static int[] offsets(float size,int count) {
            int[] values=new int[count];
            for(int i=0;i<count;i++)values[i]=(int)Math.floor(-size/2f+(i+.5f)*size/count);
            return values;
        }
    }
    private final Template cross, menu, handle, adLabel, live, cancel;
    private Template[] adGlyphs=new Template[0];
    BilibiliVisualMatcher withAdGlyphs(Template[] words) {adGlyphs=words;return this;}
    BilibiliVisualMatcher(Template cross, Template menu, Template handle, Template adLabel, Template live, Template cancel) {
        this.cross = cross; this.menu = menu; this.handle = handle; this.adLabel = adLabel;
        this.live = live; this.cancel = cancel;
    }
    Hit find(Frame frame, boolean ads, boolean liveCancel) {
        if (frame.height < frame.width * 1.65f || frame.height > frame.width * 2.65f) return null;
        Hit hit = liveCancel ? findLive(frame) : null;
        return hit != null ? hit : ads ? findAd(frame) : null;
    }
    private Hit findAd(Frame f) {
        for(float scale:new float[]{1f,.8f,1.2f}) {
            Template closeWord=cross.scaled(scale);
            for(float[] close:UiFeatureSearch.all(f,closeWord,.82f)) {
                if(grayAt(f,close[0]-24*scale,close[1]+46*scale)<225)continue;
                float[] neighbour=UiFeatureSearch.find(f,menu.scaled(scale),close[0]-150*scale,close[1]-20*scale,close[0]-25*scale,close[1]+20*scale,.76f);
                if(neighbour==null)continue;
                float[] drag=findHandle(f,close[1],scale);
                if(drag==null)continue;
                // The label can move to any part of this sheet, independently
                // of the advertiser and the close/menu/handle positions.
                float labelTop=Math.max(0,drag[1]-20*scale);
                Template label=adLabel.scaled(scale);
                float[] ad=UiFeatureSearch.first(f,label,0,labelTop,f.width,f.height,.75f,
                        mark -> UiFeatureSearch.part(f,label,mark[0],mark[1],0,0,label.columns/2,label.rows)>=.60f &&
                        UiFeatureSearch.part(f,label,mark[0],mark[1],label.columns/2,0,label.columns,label.rows)>=.60f);
                if(ad==null)ad=AdLabelVerifier.region(f,new float[]{.01f,labelTop/f.height,.99f,.99f},adGlyphs,()->false);
                if(ad==null)continue;
                return f.hit(AD,close[0],close[1],Math.min(close[2],ad[2])).withCropScale(scale);
            }
        }
        return null;
    }
    private float[] findHandle(Frame f,float closeY,float scale) {
        for(int[] b:VisualComponents.boxes(f,1,(int)(closeY-100*scale),f.width-1,(int)(closeY-15*scale),VisualComponents.GRAY,30)) {
            float w=b[2]-b[0],h=b[3]-b[1],x=(b[0]+b[2]-1)/2f,y=(b[1]+b[3]-1)/2f;
            if(w<f.width*.035f || w>f.width*.18f || w/h<5 || w/h>25 || b[4]<w*h*.65f)continue;
            if(grayAt(f,x,y)<160 || grayAt(f,x,y)>230 || grayAt(f,x,b[1]-4)<200 ||
                    grayAt(f,x,b[3]+4)<235 || grayAt(f,b[0]-4,y)<235 || grayAt(f,b[2]+4,y)<235)continue;
            float[] candidate=refine(f,handle.scaled(scale),x,y,4);
            if(candidate[2]>=.70f)return candidate;
        }
        return null;
    }
    private Hit findLive(Frame f) {
        for(float[] title:UiFeatureSearch.all(f,live,.83f)) {
            float[] stop=UiFeatureSearch.find(f,cancel,title[0]-f.width*.30f,title[1]+live.height,title[0]+f.width*.30f,Math.min(f.height,title[1]+f.height*.18f),.82f);
            if(stop==null || grayAt(f,stop[0]-cancel.width,stop[1])>100 || grayAt(f,stop[0]+cancel.width,stop[1])>100)continue;
            return f.hit(LIVE,stop[0],stop[1],Math.min(title[2],stop[2]));
        }
        return null;
    }
    static float[] refine(Frame f, Template t, float x, float y, int range) {
        float[] best = {x, y, -1};
        for (int dy = -range * 2; dy <= range * 2; dy++) for (int dx = -range * 2; dx <= range * 2; dx++) {
            float px = x + dx / 2f, py = y + dy / 2f;
            float value = correlate(f, t, px, py);
            if (value > best[2]) { best[0] = px; best[1] = py; best[2] = value; }
        }
        return best;
    }
    static float correlate(Frame f, Template t, float cx, float cy) {
        float left = cx - t.width / 2f, top = cy - t.height / 2f;
        if (left < 0 || top < 0 || left + t.width >= f.width || top + t.height >= f.height) return -1;
        double sum = 0, square = 0, dot = 0;
        if(cx==(int)cx && cy==(int)cy) {
            int ix=(int)cx,iy=(int)cy;
            for(int y=0,i=0;y<t.rows;y++) {
                int row=(iy+t.sampleY[y])*f.width;
                for(int x=0;x<t.columns;x++,i++) {
                    float value=f.gray[row+ix+t.sampleX[x]];
                    sum+=value;square+=value*value;dot+=value*t.normalized[i];
                }
            }
            double variance=square-sum*sum/t.normalized.length;
            return variance<1 || t.norm<1?-1:(float)(dot/(Math.sqrt(variance)*t.norm));
        }
        for (int y = 0, i = 0; y < t.rows; y++) for (int x = 0; x < t.columns; x++, i++) {
            int px = (int)(left + (x + .5f) * t.width / t.columns);
            int py = (int)(top + (y + .5f) * t.height / t.rows);
            float value = f.gray[py * f.width + px];
            sum += value; square += value * value; dot += value * t.normalized[i];
        }
        double variance = square - sum * sum / t.normalized.length;
        return variance < 1 || t.norm < 1 ? -1 : (float)(dot / (Math.sqrt(variance) * t.norm));
    }
    static float grayAt(Frame f, float x, float y) {
        int ix = Math.round(x), iy = Math.round(y);
        return ix < 0 || iy < 0 || ix >= f.width || iy >= f.height ? 0 : f.gray[iy * f.width + ix];
    }
    private static float luminance(int color) {
        return ((color >> 16) & 255) * .299f + ((color >> 8) & 255) * .587f + (color & 255) * .114f;
    }
}
