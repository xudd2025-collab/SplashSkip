package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Learns verified button features only. Current candidate geometry always supplies the tap. */
final class ButtonFeatureMemory {
    static final int SIZE=640,LIMIT=32;
    static final long AGE=14L*24*60*60*1000;
    static final class Entry {
        String pkg;int category;boolean landscape;float x,y;long verifiedAt;float[] signature;
    }
    private final List<Entry> entries=new ArrayList<>();
    static float[] signature(Frame f,Hit hit) {
        int[] b=AdActionRegion.box(hit,f.originalWidth,f.originalHeight);if(b==null)return null;
        float[] values=new float[SIZE];float mean=0;
        for(int y=0,i=0;y<20;y++)for(int x=0;x<32;x++,i++) {
            int px=Math.min(b[2]-1,b[0]+(int)((x+.5f)*(b[2]-b[0])/32));
            int py=Math.min(b[3]-1,b[1]+(int)((y+.5f)*(b[3]-b[1])/20));
            int c=f.pixels[py*f.originalWidth+px];
            values[i]=((c>>16)&255)*.299f+((c>>8)&255)*.587f+(c&255)*.114f;mean+=values[i];
        }
        mean/=SIZE;double variance=0;
        for(float v:values)variance+=(v-mean)*(v-mean);
        float scale=(float)Math.sqrt(variance/SIZE);if(scale<8)return null;
        for(int i=0;i<SIZE;i++)values[i]=(values[i]-mean)/scale;
        return values;
    }
    static float similarity(float[] a,float[] b) {
        if(a==null || b==null || a.length!=SIZE || b.length!=SIZE)return 0;
        double dot=0,aa=0,bb=0;
        for(int i=0;i<SIZE;i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}
        return aa<1 || bb<1?0:(float)(dot/Math.sqrt(aa*bb));
    }
    private void prune(long now) { entries.removeIf(e -> now<e.verifiedAt || now-e.verifiedAt>AGE); }
    synchronized List<Hit> prioritize(String pkg,Frame f,List<Hit> candidates,long now) {
        prune(now);if(entries.isEmpty())return candidates;
        List<Hit> result=new ArrayList<>();
        for(Hit candidate:candidates) {
            float[] current=signature(f,candidate);boolean match=false;
            for(Entry e:entries)if(e.pkg.equals(pkg) && e.category==AdActionRegion.classIndex(candidate.rule) &&
                    e.landscape==(f.originalWidth>f.originalHeight) &&
                    similarity(current,e.signature)>=.86f) {match=true;break;}
            result.add(candidate.withMemory(match));
        }
        result.sort((a,b) -> Boolean.compare(b.memoryMatch,a.memoryMatch));return result;
    }
    synchronized void remember(String pkg,Hit hit,long now) {
        if(hit.buttonSignature==null || hit.buttonSignature.length!=SIZE || !Float.isFinite(hit.modelProbability) || hit.modelProbability<.95f || !AdActionRegion.isSkip(hit.rule) ||
                pkg==null || !pkg.matches("[a-zA-Z0-9_.]{1,200}") || hit.frameWidth<=0 || hit.frameHeight<=0)return;
        for(float v:hit.buttonSignature)if(!Float.isFinite(v))return;
        prune(now);entries.removeIf(e -> e.pkg.equals(pkg) && e.landscape==(hit.frameWidth>hit.frameHeight) && similarity(e.signature,hit.buttonSignature)>.94f);
        Entry e=new Entry();e.pkg=pkg;e.category=AdActionRegion.classIndex(hit.rule);e.landscape=hit.frameWidth>hit.frameHeight;
        e.x=hit.x/(float)hit.frameWidth;e.y=hit.y/(float)hit.frameHeight;e.verifiedAt=now;e.signature=hit.buttonSignature.clone();entries.add(0,e);
        while(entries.size()>LIMIT)entries.remove(entries.size()-1);
    }
    synchronized int size(long now) {prune(now);return entries.size();}
    synchronized void clear() {entries.clear();}
    synchronized void write(DataOutputStream out)throws IOException {
        out.writeInt(1);out.writeInt(entries.size());
        for(Entry e:entries) {out.writeUTF(e.pkg);out.writeInt(e.category);out.writeBoolean(e.landscape);out.writeFloat(e.x);out.writeFloat(e.y);out.writeLong(e.verifiedAt);for(float v:e.signature)out.writeFloat(v);}
    }
    synchronized void read(DataInputStream in)throws IOException {
        if(in.readInt()!=1)throw new IOException("Unknown feature memory version");
        int count=in.readInt();if(count<0 || count>LIMIT)throw new IOException("Invalid feature count");
        List<Entry> restored=new ArrayList<>();
        for(int i=0;i<count;i++) {
            Entry e=new Entry();e.pkg=in.readUTF();e.category=in.readInt();e.landscape=in.readBoolean();e.x=in.readFloat();e.y=in.readFloat();e.verifiedAt=in.readLong();e.signature=new float[SIZE];
            if(!e.pkg.matches("[a-zA-Z0-9_.]{1,200}") || e.category<0 || e.category>2 || !(e.x>=0 && e.x<=1 && e.y>=0 && e.y<=1))throw new IOException("Invalid feature metadata");
            for(int j=0;j<SIZE;j++){e.signature[j]=in.readFloat();if(!Float.isFinite(e.signature[j]))throw new IOException("Invalid feature value");}
            restored.add(e);
        }
        entries.clear();entries.addAll(restored);
    }
}
