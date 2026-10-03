package com.codex.splashskip;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

/** Learned button identity is checked only after the full enabled ad scene matches. */
final class AdActionModel implements AutoCloseable {
    static final float THRESHOLD=.85f;
    private final SkipTextModel model;
    private SkipTextModel generalSkip;
    AdActionModel(Context context)throws Exception {
        model=new SkipTextModel(context,"ad_action.tflite");
        try {
            check(context,R.drawable.ad_model_check_skip,0);
            check(context,R.drawable.ad_model_check_close,1);
            check(context,R.drawable.ad_model_check_cross,2);
        } catch(RuntimeException error) { model.close();throw error; }
        try {
            generalSkip=new SkipTextModel(context,"general_skip.tflite");
            Bitmap tile=BitmapFactory.decodeResource(context.getResources(),R.drawable.ad_model_check_skip);
            try {
                float p=generalSkip.probability(tile,new Rect(0,0,tile.getWidth(),tile.getHeight()),0);
                Diagnostics.append(context,"general skip model runtime check probability="+String.format(java.util.Locale.ROOT,"%.3f",p));
            } finally { tile.recycle(); }
        }
        catch(Exception | LinkageError error) {
            if(generalSkip!=null)generalSkip.close();generalSkip=null;
            Diagnostics.append(context,"general skip model unavailable; visual skip scoring paused, ordinary accessibility remains available");
        }
    }
    private void check(Context c,int resource,int index) {
        Bitmap tile=BitmapFactory.decodeResource(c.getResources(),resource);
        try {
            float probability=model.probability(tile,new Rect(0,0,tile.getWidth(),tile.getHeight()),index);
            Diagnostics.append(c,"action model runtime check class="+index+" probability="+String.format(java.util.Locale.ROOT,"%.3f",probability));
        } finally { tile.recycle(); }
    }
    float probability(Bitmap image,BilibiliVisualMatcher.Hit hit) {
        if(AdActionRegion.isSkip(hit.rule) && generalSkip==null)return 0;
        int[] box=AdActionRegion.box(hit,image.getWidth(),image.getHeight());
        if(box==null)return 0;
        Rect crop=new Rect(box[0],box[1],box[2],box[3]);
        return AdActionRegion.isSkip(hit.rule) && generalSkip!=null?generalSkip.probability(image,crop,0):model.probability(image,crop,AdActionRegion.classIndex(hit.rule));
    }
    @Override public void close() { model.close();if(generalSkip!=null)generalSkip.close(); }
    /** Only after the modal, circle, X and both navigation labels have matched. */
    float verifiedCrossProbability(BilibiliVisualMatcher.Frame frame,BilibiliVisualMatcher.Hit hit) {
        if(!ChinaMobileVisualMatcher.CLOSE.equals(hit.rule) && !TencentFeedVisualMatcher.FEED.equals(hit.rule) && !HuyaVisualMatcher.PORTRAIT_AD.equals(hit.rule) && !(HuyaVisualMatcher.AD.equals(hit.rule) && Math.abs(hit.cropScale-1f)>.05f))return 0;
        int[] box=AdActionRegion.box(hit,frame.originalWidth,frame.originalHeight);if(box==null)return 0;
        int w=box[2]-box[0],h=box[3]-box[1];float best=0;
        for(int threshold:new int[]{215,190,235}) {
            Bitmap tile=Bitmap.createBitmap(ButtonStrokeInput.crop(frame,box,threshold),w,h,Bitmap.Config.ARGB_8888);
            try { best=Math.max(best,model.probability(tile,new Rect(0,0,w,h),2)); }
            finally {tile.recycle();}
            if(best>=.98f)break;
        }
        return best;
    }
    float strokeProbability(BilibiliVisualMatcher.Frame frame,BilibiliVisualMatcher.Hit hit) {
        if(generalSkip==null)return 0;
        float best=strokeCrop(frame,hit);
        if(best<.98f && Math.abs(hit.cropScale-1f)>.05f)best=Math.max(best,strokeCrop(frame,hit.withCropScale(1f)));
        return best;
    }
    private float strokeCrop(BilibiliVisualMatcher.Frame frame,BilibiliVisualMatcher.Hit hit) {
        int[] box=AdActionRegion.box(hit,frame.originalWidth,frame.originalHeight);if(box==null)return 0;
        int w=box[2]-box[0],h=box[3]-box[1];float best=0;
        for(int threshold:new int[]{215,190,235}) {
            int[] strokes=ButtonStrokeInput.crop(frame,box,threshold);
            for(int polarity=0;polarity<2;polarity++) {
                Bitmap tile=Bitmap.createBitmap(polarity==0?strokes:ButtonStrokeInput.inverted(strokes),w,h,Bitmap.Config.ARGB_8888);
                try {
                    Rect rect=new Rect(0,0,w,h);float p=generalSkip.probability(tile,rect,0);
                    best=Math.max(best,p);
                } finally {tile.recycle();}
                if(best>=.98f)break;
            }
            if(best>=.98f)break;
        }
        return best;
    }
}
