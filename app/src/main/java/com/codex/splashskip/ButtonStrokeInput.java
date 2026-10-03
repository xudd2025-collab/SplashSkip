package com.codex.splashskip;
/** Removes textured background before scoring a separately verified skip glyph. */
final class ButtonStrokeInput {
    static int[] inverted(int[] pixels) {
        int[] values=pixels.clone();for(int i=0;i<values.length;i++)values[i]^=0x00ffffff;return values;
    }
    static int[] crop(BilibiliVisualMatcher.Frame f,int[] box,int threshold) {
        int w=box[2]-box[0],h=box[3]-box[1];int[] pixels=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int c=f.pixels[(box[1]+y)*f.originalWidth+box[0]+x];
            float gray=((c>>16)&255)*.299f+((c>>8)&255)*.587f+(c&255)*.114f;
            pixels[y*w+x]=gray>=threshold?0xffffffff:0xff000000;
        }
        return pixels;
    }
}
