package com.codex.splashskip;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A learned ranking score never authorizes a click or supplies geometry. */
final class JointControlModel {
    static final int SHAPE=12,SIZE=76;
    private final float[] weights;
    JointControlModel(float[] w) {
        if(w.length!=SIZE)throw new IllegalArgumentException("feature count");
        for(float v:w)if(!Float.isFinite(v) || Math.abs(v)>100)throw new IllegalArgumentException("weight");
        weights=w.clone();
    }
    static JointControlModel read(InputStream stream)throws IOException {
        Properties p=new Properties();p.load(stream);
        if(!"1".equals(p.getProperty("schema")) || !"76".equals(p.getProperty("features")) ||
            !"true".equals(p.getProperty("validated")))throw new IOException("Unvalidated joint model");
        try {
            String[] raw=p.getProperty("weights","").split(",");float[] w=new float[raw.length];
            for(int i=0;i<w.length;i++)w[i]=Float.parseFloat(raw[i]);return new JointControlModel(w);
        }catch(RuntimeException e){throw new IOException("Invalid joint model",e);}
    }
    float score(float[] features) {
        if(!valid(features))return 0;float v=0;for(int i=0;i<SIZE;i++)v+=features[i]*weights[i];
        return (float)(1/(1+Math.exp(-Math.max(-30,Math.min(30,v)))));
    }
    static boolean valid(float[] f) {if(f==null || f.length!=SIZE)return false;for(float v:f)if(!Float.isFinite(v) || v<0 || v>1)return false;return true;}
    static float[] features(int[] pixels,int w,int h,int[] box,ControlTree.Hint hint) {
        if(!ControlTree.small(box,w,h))return null;
        float[] f=new float[SIZE];if(hint!=null)System.arraycopy(hint.shape,0,f,0,SHAPE);f[0]=1;
        int lo=255,hi=0;int[] gray=new int[64];
        for(int y=0,i=0;y<8;y++)for(int x=0;x<8;x++,i++) {
            int px=Math.min(box[2]-1,box[0]+(int)((x+.5f)*(box[2]-box[0])/8));
            int py=Math.min(box[3]-1,box[1]+(int)((y+.5f)*(box[3]-box[1])/8));
            int c=pixels[py*w+px];gray[i]=(((c>>16)&255)*299+((c>>8)&255)*587+(c&255)*114)/1000;
            lo=Math.min(lo,gray[i]);hi=Math.max(hi,gray[i]);
        }
        for(int i=0;i<64;i++)f[SHAPE+i]=(gray[i]-lo)/(float)Math.max(1,hi-lo);
        return f;
    }
    static String digest(String value) {
        try {byte[] b=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex="0123456789abcdef".toCharArray();StringBuilder s=new StringBuilder();for(byte v:b){s.append(hex[(v&255)>>>4]);s.append(hex[v&15]);}return s.toString();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
