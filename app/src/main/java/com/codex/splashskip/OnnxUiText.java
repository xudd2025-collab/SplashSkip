package com.codex.splashskip;

import ai.onnxruntime.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Bundled PP-OCRv4 detector + CTC recognizer. Shared unchanged by Android and desktop verification. */
final class OnnxUiText implements AutoCloseable {
    static final class Result {
        final Hit hit; final List<UiControlPolicy.Word> words; final String status;
        final int boxes; final long detectMs,recognizeMs;
        boolean detailed;
        Result(Hit hit,List<UiControlPolicy.Word> words,String status,int boxes,long detect,long recognize) {
            this.hit=hit;this.words=words;this.status=status;this.boxes=boxes;detectMs=detect;recognizeMs=recognize;
        }
    }
    private final OrtEnvironment env=OrtEnvironment.getEnvironment();
    private final OrtSession detector,recognizer;
    private final String detectorInput,recognizerInput;
    private final String[] alphabet;
    private boolean detailNext;
    private int fastMisses;
    private int contextMisses;
    private String cueStatus="none";
    synchronized String cueStatus(){return cueStatus;}
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"ui-text-deadline");t.setDaemon(true);return t;
    });
    OnnxUiText(byte[] det,byte[] rec,String dictionary)throws OrtException {
        OrtSession d=null,r=null;
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            d=env.createSession(det,options);r=env.createSession(rec,options);
            String[] glyphs=dictionary.replace("\r","").split("\n");alphabet=new String[glyphs.length+2];alphabet[0]="";
            System.arraycopy(glyphs,0,alphabet,1,glyphs.length);alphabet[alphabet.length-1]=" ";
        } catch(OrtException | RuntimeException error) {
            if(d!=null)d.close();if(r!=null)r.close();watchdog.shutdown();throw error;
        }
        detector=d;recognizer=r;detectorInput=d.getInputNames().iterator().next();recognizerInput=r.getInputNames().iterator().next();
    }
    synchronized void warm()throws OrtException {
        try(OnnxTensor d=OnnxTensor.createTensor(env,FloatBuffer.wrap(new float[3*32*32]),new long[]{1,3,32,32});
            OrtSession.Result dr=detector.run(Collections.singletonMap(detectorInput,d));
            OnnxTensor r=OnnxTensor.createTensor(env,FloatBuffer.wrap(new float[3*48*192]),new long[]{1,3,48,192});
            OrtSession.Result rr=recognizer.run(Collections.singletonMap(recognizerInput,r))) { }
    }
    synchronized void resetResolution(){detailNext=false;fastMisses=0;contextMisses=0;}
    synchronized boolean verifyControl(int[] pixels,int w,int h,String expected,BooleanSupplier cancelled,long budgetMs)throws OrtException {
        UiControlPolicy.Word word=readRegion(pixels,w,h,cancelled,budgetMs);
        return word!=null && word.confidence>=.985f && word.weakest>=.96f && expected.equals(UiControlPolicy.action(word.text));
    }
    synchronized UiControlPolicy.Word readRegion(int[] pixels,int w,int h,BooleanSupplier cancelled,long budgetMs)throws OrtException {
        if(cancelled.getAsBoolean() || budgetMs<=0 || w<=0 || h<=0)return null;
        long deadline=System.nanoTime()+budgetMs*1_000_000L;
        int rw=Math.max(64,Math.min(320,32*(int)Math.ceil(1.5*w/h))),rh=48;
        try(OrtSession.RunOptions options=new OrtSession.RunOptions()) {
            ScheduledFuture<?> timer=watchdog.scheduleWithFixedDelay(()->{
                if(System.nanoTime()>deadline || cancelled.getAsBoolean())try{options.setTerminate(true);}catch(Exception ignored){}
            },10,10,TimeUnit.MILLISECONDS);
            try(OnnxTensor tensor=OnnxTensor.createTensor(env,rgb(pixels,w,h,0,0,w,h,Math.min(rw,(int)Math.ceil(rh*w/(float)h)),rh,rw),new long[]{1,3,rh,rw});
                OrtSession.Result result=recognizer.run(Collections.singletonMap(recognizerInput,tensor),options)) {
                OnnxTensor output=(OnnxTensor)result.get(0);long[] shape=output.getInfo().getShape();
                int steps=(int)shape[1],classes=(int)shape[2];if(classes!=alphabet.length)return null;
                FloatBuffer buf=output.getFloatBuffer();float[] values=new float[buf.remaining()];buf.get(values);
                int previous=0,letters=0;float sum=0,min=1;StringBuilder word=new StringBuilder();
                for(int step=0;step<steps;step++) {
                    int start=step*classes,best=0;float confidence=values[start];
                    for(int c=1;c<classes;c++)if(values[start+c]>confidence){best=c;confidence=values[start+c];}
                    if(best>0 && best!=previous){word.append(alphabet[best]);sum+=confidence;min=Math.min(min,confidence);letters++;}
                    previous=best;
                }
                if(cancelled.getAsBoolean() || System.nanoTime()>deadline || letters==0)return null;
                return new UiControlPolicy.Word(word.toString(),sum/letters,min,0,0,w,h);
            } catch(OrtException error) {
                if(cancelled.getAsBoolean() || System.nanoTime()>deadline)return null;
                throw error;
            } finally {timer.cancel(false);}
        }
    }
    /** Accessibility often exposes an entire padded CTA instead of its text child.
     * Locate text within that current container, then recognize the actual glyph crop. */
    synchronized UiControlPolicy.Word readCueRegion(int[] pixels,int w,int h,BooleanSupplier cancelled,long budgetMs)throws OrtException {
        cueStatus="container-start";
        if(cancelled.getAsBoolean() || budgetMs<10 || w<=0 || h<=0)return null;
        long deadline=System.nanoTime()+budgetMs*1_000_000L;
        float scale=Math.min(1,Math.min(640f/w,160f/h));
        int dw=Math.max(32,Math.round(w*scale/32)*32),dh=Math.max(32,Math.round(h*scale/32)*32);
        List<int[]> regions;
        try(OrtSession.RunOptions options=new OrtSession.RunOptions()) {
            ScheduledFuture<?> timer=watchdog.scheduleWithFixedDelay(()->{
                if(System.nanoTime()>deadline || cancelled.getAsBoolean())try{options.setTerminate(true);}catch(Exception ignored){}
            },10,10,TimeUnit.MILLISECONDS);
            try(OnnxTensor tensor=OnnxTensor.createTensor(env,rgb(pixels,w,h,0,0,w,h,dw,dh,dw),new long[]{1,3,dh,dw});
                OrtSession.Result result=detector.run(Collections.singletonMap(detectorInput,tensor),options)) {
                OnnxTensor output=(OnnxTensor)result.get(0);long[] shape=output.getInfo().getShape();
                regions=boxes(output.getFloatBuffer(),(int)shape[3],(int)shape[2],w,h,.85f);
            }catch(OrtException error){if(cancelled.getAsBoolean() || System.nanoTime()>deadline)return null;throw error;}
            finally{timer.cancel(false);}
        }
        cueStatus="container-boxes="+regions.size();
        if(regions.size()>4)return null;
        for(int[] b:regions) {
            int cw=b[2]-b[0],ch=b[3]-b[1];int[] crop=new int[cw*ch];
            for(int y=0;y<ch;y++)System.arraycopy(pixels,(b[1]+y)*w+b[0],crop,y*cw,cw);
            UiControlPolicy.Word word=readRegion(crop,cw,ch,cancelled,(deadline-System.nanoTime())/1_000_000);
            cueStatus+=" crop="+cw+"x"+ch+" confidence="+(word==null?"unread":word.confidence+"/"+word.weakest);
            if(word!=null && (UiControlPolicy.prompt(word) || UiControlPolicy.adMark(word)))
                return new UiControlPolicy.Word(word.text,word.confidence,word.weakest,b[0],b[1],b[2],b[3]);
        }
        return null;
    }
    synchronized Result find(int[] pixels,int width,int height,boolean opening,BooleanSupplier cancelled,long budgetMs)throws OrtException {
        long began=System.nanoTime();boolean detail=detailNext;detailNext=false;
        Result first=findAtScale(pixels,width,height,opening,cancelled,budgetMs,detail?640:384);
        first.detailed=detail;
        if(detail || first.hit!=null && !UiControlPolicy.CLOSE.equals(first.hit.rule) || cancelled.getAsBoolean()){fastMisses=0;contextMisses=0;return first;}
        boolean action=false;
        for(UiControlPolicy.Word word:first.words)if(!UiControlPolicy.action(word.text).isEmpty()
                && word.confidence>=.985f && word.weakest>=.96f){action=true;break;}
        // Contention during app launch is not evidence of tiny text. Retrying a larger
        // detector after a timeout compounds that delay. Re-acquire a cheap current frame.
        if(first.status.equals("deadline")) {
            // A clearly read action plus missing context is different from an empty
            // detector timeout. Retry detail on a NEW frame instead of looping on
            // the same coarse text boxes for the whole countdown.
            // Native reads can leave too little time to read coarse context. In
            // that case try the cheap pass on fresh pixels with a full budget;
            // immediately increasing detector size would spend that budget again.
            if(action)detailNext=++contextMisses>=2 && budgetMs>=600;
            return first;
        }
        boolean clue=false;
        for(UiControlPolicy.Word word:first.words)clue|=!UiControlPolicy.action(word.text).isEmpty() || UiControlPolicy.adMark(word) || UiControlPolicy.prompt(word);
        // Splash controls can precede their animated disclosure. One fresh coarse
        // pass is cheaper than immediately enlarging a frame that lacks that text.
        if(action){detailNext=++contextMisses>=2;fastMisses=0;}
        else {contextMisses=0;detailNext=clue || ++fastMisses>=2;}
        long remaining=budgetMs-(System.nanoTime()-began)/1_000_000;
        // A fast pass never proves absence. Difficult/small text gets a detail pass,
        // using a fresh screenshot on the phone when this frame has insufficient time.
        if(!detailNext || remaining<450)return new Result(null,first.words,"needs-detail",first.boxes,first.detectMs,first.recognizeMs);
        detailNext=false;
        Result second=findAtScale(pixels,width,height,opening,cancelled,remaining,640);
        Result combined=new Result(second.hit,second.words,second.status,second.boxes,first.detectMs+first.recognizeMs+second.detectMs,second.recognizeMs);
        combined.detailed=true;return combined;
    }
    private Result findAtScale(int[] pixels,int width,int height,boolean opening,BooleanSupplier cancelled,long budgetMs,int shortSide)throws OrtException {
        return findAtScale(pixels,width,height,opening,cancelled,budgetMs,shortSide,false);
    }
    /** Offline desktop inspection only: collect text without authorizing a device action. */
    synchronized Result inspect(int[] pixels,int width,int height,long budgetMs)throws OrtException {
        return findAtScale(pixels,width,height,false,()->false,budgetMs,640,true);
    }
    private Result findAtScale(int[] pixels,int width,int height,boolean opening,BooleanSupplier cancelled,long budgetMs,int shortSide,boolean inspect)throws OrtException {
        long started=System.nanoTime(),deadline=started+budgetMs*1_000_000L;
        List<UiControlPolicy.Word> words=new ArrayList<>();long detected=started;int count=0;boolean detectionDone=false;
        if(cancelled.getAsBoolean())return new Result(null,words,"cancelled",0,0,0);
        try(OrtSession.RunOptions options=new OrtSession.RunOptions()) {
            ScheduledFuture<?> timer=watchdog.scheduleWithFixedDelay(() -> {
                if(System.nanoTime()>deadline || cancelled.getAsBoolean())try {options.setTerminate(true);}catch(OrtException | RuntimeException ignored){}
            },25,25,TimeUnit.MILLISECONDS);
            try {
                int small=Math.min(width,height),dw=Math.max(32,Math.round(width*(shortSide/(float)small)/32)*32),dh=Math.max(32,Math.round(height*(shortSide/(float)small)/32)*32);
                FloatBuffer input=rgb(pixels,width,height,0,0,width,height,dw,dh,dw);
                List<int[]> boxes;
                try(OnnxTensor tensor=OnnxTensor.createTensor(env,input,new long[]{1,3,dh,dw});
                    OrtSession.Result result=detector.run(Collections.singletonMap(detectorInput,tensor),options)) {
                    OnnxTensor output=(OnnxTensor)result.get(0);long[] shape=output.getInfo().getShape();
                    boxes=boxes(output.getFloatBuffer(),(int)shape[3],(int)shape[2],width,height);
                }
                detected=System.nanoTime();detectionDone=true;count=boxes.size();
                // Actual detected text boxes everywhere on screen; button padding determines priority, not position.
                Map<int[],Float> priorities=new IdentityHashMap<>();
                for(int[] box:boxes)priorities.put(box,priority(pixels,width,height,box));
                boxes.sort((a,b) -> Float.compare(priorities.get(b),priorities.get(a)));
                for(int offset=0;offset<boxes.size() && offset<64;) {
                    if(cancelled.getAsBoolean() || System.nanoTime()>deadline)return result(null,words,"deadline",count,started,detected);
                    // Short controls should not wait for a large padded batch of creative text.
                    // After an action is read, check its current control row before unrelated text.
                    UiControlPolicy.Word control=null;
                    for(UiControlPolicy.Word word:words)if(!UiControlPolicy.action(word.text).isEmpty() && word.confidence>=.985f && word.weakest>=.96f){control=word;break;}
                    if(control!=null) {
                        final UiControlPolicy.Word anchor=control;
                        boxes.subList(offset,boxes.size()).sort((a,b)->Float.compare(contextPriority(b,anchor),contextPriority(a,anchor)));
                    }
                    int n=Math.min(offset<16?1:4,Math.min(64,boxes.size())-offset),rw=64,rh=48;
                    for(int i=0;i<n;i++) {
                        int[] box=boxes.get(offset+i);
                        rw=Math.max(rw,Math.min(640,32*(int)Math.ceil(1.5*(box[2]-box[0])/(box[3]-box[1]))));
                    }
                    int plane=rw*rh;
                    FloatBuffer batch=ByteBuffer.allocateDirect(n*plane*3*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    for(int i=0;i<n;i++) {
                        int[] box=boxes.get(offset+i);int resized=Math.min(rw,(int)Math.ceil(rh*(box[2]-box[0])/(float)(box[3]-box[1])));
                        batch.put(rgb(pixels,width,height,box[0],box[1],box[2],box[3],resized,rh,rw));
                    }
                    batch.flip();
                    try(OnnxTensor tensor=OnnxTensor.createTensor(env,batch,new long[]{n,3,rh,rw});
                        OrtSession.Result result=recognizer.run(Collections.singletonMap(recognizerInput,tensor),options)) {
                        OnnxTensor output=(OnnxTensor)result.get(0);long[] shape=output.getInfo().getShape();
                        FloatBuffer valuesBuffer=output.getFloatBuffer();
                        float[] values=new float[valuesBuffer.remaining()];valuesBuffer.get(values);
                        int steps=(int)shape[1],classes=(int)shape[2];
                        if(classes!=alphabet.length)throw new IllegalArgumentException("OCR dictionary shape mismatch: "+classes+" / "+alphabet.length);
                        for(int i=0;i<n;i++) {
                            int last=0,letters=0;float sum=0,min=1;StringBuilder text=new StringBuilder();
                            for(int step=0;step<steps;step++) {
                                int start=(i*steps+step)*classes,best=0;float confidence=values[start];
                                for(int c=1;c<classes;c++){float v=values[start+c];if(v>confidence){best=c;confidence=v;}}
                                if(best>0 && best!=last) {text.append(alphabet[best]);sum+=confidence;min=Math.min(min,confidence);letters++;}
                                last=best;
                            }
                            int[] box=boxes.get(offset+i);
                            if(letters>0)words.add(new UiControlPolicy.Word(text.toString(),sum/letters,min,box[0],box[1],box[2],box[3]));
                        }
                    }
                    Hit hit=UiControlPolicy.find(pixels,width,height,words,opening);
                    if(!inspect && hit!=null && !UiControlPolicy.CLOSE.equals(hit.rule) && !cancelled.getAsBoolean() && System.nanoTime()<=deadline)return result(hit,words,"verified",count,started,detected);
                    offset+=n;
                }
                Hit hit=cancelled.getAsBoolean() || System.nanoTime()>deadline?null:UiControlPolicy.find(pixels,width,height,words,opening);
                if(hit!=null && UiControlPolicy.CLOSE.equals(hit.rule) && boxes.size()>64)hit=null;
                return result(inspect?null:hit,words,inspect?"inspection":hit==null?"no-verified-control":"verified",count,started,detected);
            } catch(OrtException error) {
                if(!detectionDone)detected=System.nanoTime();
                if(System.nanoTime()>deadline || cancelled.getAsBoolean())return result(null,words,cancelled.getAsBoolean()?"cancelled":"deadline",count,started,detected);
                throw error;
            } finally {timer.cancel(false);}
        }
    }
    private static Result result(Hit hit,List<UiControlPolicy.Word> words,String status,int count,long start,long detect) {
        return new Result(hit,words,status,count,(detect-start)/1_000_000,(System.nanoTime()-detect)/1_000_000);
    }
    private static float priority(int[] pixels,int w,int h,int[] box) {
        UiControlPolicy.Word word=new UiControlPolicy.Word("",1,1,box[0],box[1],box[2],box[3]);
        float ratio=word.width()/(float)word.height();
        return 4-Math.abs(ratio-1.75f)+(UiControlPolicy.buttonBoundary(pixels,w,h,word)?.25f:0);
    }
    private static float contextPriority(int[] box,UiControlPolicy.Word anchor) {
        float height=box[3]-box[1],ratio=(box[2]-box[0])/height;
        float rowDistance=Math.abs((box[1]+box[3])/2f-anchor.y())/Math.max(height,anchor.height());
        // Compact disclosures (ad badge / motion prompt) can be anywhere. Read
        // these before long product copy; rank only, all semantic gates remain.
        float compact=ratio>=.7f && ratio<=5.5f?6:ratio<=22?2:0;
        float similarSize=height<=anchor.height()*1.8f?1:0;
        return (rowDistance<1.5f?10:0)+compact+similarSize-Math.min(3,rowDistance*.01f);
    }
    private static List<int[]> boxes(FloatBuffer output,int w,int h,int originalW,int originalH) {
        return boxes(output,w,h,originalW,originalH,.09f);
    }
    private static List<int[]> boxes(FloatBuffer output,int w,int h,int originalW,int originalH,float maxHeight) {
        float[] probabilities=new float[w*h];output.get(probabilities);
        boolean[] mask=new boolean[w*h];int[] queue=new int[w*h];List<int[]> boxes=new ArrayList<>();
        for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++)if(probabilities[y*w+x]>.3f) {
            mask[y*w+x]=true;mask[y*w+x+1]=true;mask[(y+1)*w+x]=true;mask[(y+1)*w+x+1]=true;
        }
        for(int start=0;start<mask.length;start++)if(mask[start]) {
            int n=1,read=0,l=start%w,r=l,t=start/w,b=t;float total=0;queue[0]=start;mask[start]=false;
            while(read<n) {
                int p=queue[read++],x=p%w,y=p/w;l=Math.min(l,x);r=Math.max(r,x);t=Math.min(t,y);b=Math.max(b,y);total+=probabilities[p];
                for(int i=0;i<4;i++) {
                    int d=i==0?-1:i==1?1:i==2?-w:w;
                    int next=p+d;if(next<0 || next>=mask.length || d==-1 && x==0 || d==1 && x==w-1 || !mask[next])continue;
                    mask[next]=false;queue[n++]=next;
                }
            }
            int bw=r-l+1,bh=b-t+1;
            if(bh<3 || bh>Math.min(w,h)*maxHeight || bw<bh*.7f || bw>bh*(maxHeight>.5f?60:26) || total/n<.45f)continue;
            float pad=bw*bh*1.6f/(2*(bw+bh));
            int left=Math.max(0,Math.round((l-pad)*originalW/w)),right=Math.min(originalW,Math.round((r+pad)*originalW/w));
            int top=Math.max(0,Math.round((t-pad)*originalH/h)),bottom=Math.min(originalH,Math.round((b+pad)*originalH/h));
            if(right>left && bottom>top)boxes.add(new int[]{left,top,right,bottom});
        }
        return boxes;
    }
    private static FloatBuffer rgb(int[] pixels,int w,int h,int l,int t,int r,int b,int dw,int dh,int paddedW) {
        int plane=paddedW*dh;float[] input=new float[plane*3];
        for(int y=0;y<dh;y++) {
            float sy=t+(y+.5f)*(b-t)/dh-.5f;int y0=Math.max(t,Math.min(b-1,(int)Math.floor(sy))),y1=Math.min(b-1,y0+1);float fy=Math.max(0,sy-y0);
            for(int x=0;x<dw;x++) {
                float sx=l+(x+.5f)*(r-l)/dw-.5f;int x0=Math.max(l,Math.min(r-1,(int)Math.floor(sx))),x1=Math.min(r-1,x0+1);float fx=Math.max(0,sx-x0);
                int c00=pixels[y0*w+x0],c10=pixels[y0*w+x1],c01=pixels[y1*w+x0],c11=pixels[y1*w+x1];
                for(int channel=0;channel<3;channel++) {
                    int shift=channel*8;float a=((c00>>shift)&255)*(1-fx)+((c10>>shift)&255)*fx;
                    float c=((c01>>shift)&255)*(1-fx)+((c11>>shift)&255)*fx;
                    input[channel*plane+y*paddedW+x]=(a*(1-fy)+c*fy)/127.5f-1;
                }
            }
        }
        return FloatBuffer.wrap(input);
    }
    @Override public synchronized void close() {
        watchdog.shutdown();try{detector.close();}catch(OrtException ignored){}try{recognizer.close();}catch(OrtException ignored){}
    }
}
