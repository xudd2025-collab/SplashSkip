package com.codex.splashskip;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.DashPathEffect;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.*;
import java.util.function.IntConsumer;

/** Interactive saved geometry; its touches only select diagnostic nodes. */
final class NativeBoundsView extends View {
    static final int CLICK_COLOR=Color.rgb(20,137,85),CONTROL_COLOR=Color.rgb(109,73,208),
        CUE_COLOR=Color.rgb(190,111,18),SELECT_COLOR=Color.rgb(221,54,85),PARENT_COLOR=Color.rgb(28,113,205);
    private final NativeBoundsInspector model;
    private final IntConsumer selected;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector gestures;
    private int selection=-1,filter;
    private float scale,left,top,zoom=1,panX,panY,lastX,lastY,downX,downY;
    private boolean moved,multitouch;
    private List<Integer> overlaps=Collections.emptyList();
    NativeBoundsView(Context context,NativeBoundsInspector model,IntConsumer selected) {
        super(context);this.model=model;this.selected=selected;
        setBackgroundColor(Color.rgb(247,247,252));setFocusable(true);setClickable(true);
        gestures=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float old=zoom;zoom=Math.max(1,Math.min(8,zoom*detector.getScaleFactor()));
                float ratio=zoom/old,cx=getWidth()/2f,cy=getHeight()/2f;
                panX=(panX+cx-detector.getFocusX())*ratio+detector.getFocusX()-cx;
                panY=(panY+cy-detector.getFocusY())*ratio+detector.getFocusY()-cy;
                invalidate();return true;
            }
        });describe();
    }
    void setFilter(int value){filter=value;selection=-1;overlaps=Collections.emptyList();invalidate();describe();selected.accept(-1);}
    int filter(){return filter;}
    int selection(){return selection;}
    void selectIndex(int index,boolean focus) {
        selectIndex(index,focus,false);
    }
    private void selectIndex(int index,boolean focus,boolean preserveOverlaps) {
        if(index<0 || index>=model.tree.nodes.size())return;
        if(!preserveOverlaps)overlaps=Collections.emptyList();
        selection=index;if(focus)focusSelection();invalidate();describe();selected.accept(index);
    }
    void nextOverlap() {
        if(overlaps.size()<2)return;
        selectIndex(overlaps.get((overlaps.indexOf(selection)+1)%overlaps.size()),false,true);
    }
    int overlapCount(){return overlaps.size();}
    void reset(){zoom=1;panX=panY=0;invalidate();}
    void focusSelection() {
        if(selection<0)return;
        int[] b=model.tree.nodes.get(selection).box;
        float base=baseScale();
        zoom=Math.max(1,Math.min(8,Math.min(getWidth()*.65f/Math.max(1,b[2]-b[0]),getHeight()*.55f/Math.max(1,b[3]-b[1]))/base));
        panX=(model.tree.width/2f-(b[0]+b[2])/2f)*base*zoom;
        panY=(model.tree.height/2f-(b[1]+b[3])/2f)*base*zoom;invalidate();
    }
    private float baseScale(){float inset=dp(8);return Math.max(.001f,Math.min(Math.max(1,getWidth()-2*inset)/model.tree.width,Math.max(1,getHeight()-2*inset)/model.tree.height));}
    private void describe() {
        setContentDescription("保存的控件边框图，绿色可点击，紫色关闭或跳过，双指缩放，"+
            (selection<0?"未选择控件":"已选择控件 "+selection));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);scale=baseScale()*zoom;
        left=(getWidth()-model.tree.width*scale)/2+panX;top=(getHeight()-model.tree.height*scale)/2+panY;
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setPathEffect(null);
        canvas.drawRect(left,top,left+model.tree.width*scale,top+model.tree.height*scale,paint);
        for(int pass=0;pass<3;pass++)for(int i=0;i<model.tree.nodes.size();i++) {
            ControlTree.Node n=model.tree.nodes.get(i);
            int category=UiControlPolicy.isControl(n.role)?2:n.clickable?1:0;
            if(!model.shown(i,filter) || category!=pass || i==selection)continue;
            int color=category==2?CONTROL_COLOR:category==1?CLICK_COLOR:"ad".equals(n.role)||"prompt".equals(n.role)?CUE_COLOR:Color.argb(90,90,112,140);
            outline(canvas,n.box,color,category>0?2:1,false);
            if(category>0)badge(canvas,n.box,i,color);
        }
        if(selection>=0) {
            int parent=model.nearestClickable(selection);
            if(parent>=0 && parent!=selection)outline(canvas,model.tree.nodes.get(parent).box,PARENT_COLOR,2,true);
            int[] b=model.tree.nodes.get(selection).box;
            outline(canvas,b,SELECT_COLOR,3,false);badge(canvas,b,selection,SELECT_COLOR);
        }
    }
    private void outline(Canvas canvas,int[] b,int color,float width,boolean dash) {
        paint.setStyle(Paint.Style.STROKE);paint.setColor(color);paint.setStrokeWidth(dp(width));
        paint.setPathEffect(dash?new DashPathEffect(new float[]{dp(5),dp(3)},0):null);
        canvas.drawRect(left+b[0]*scale,top+b[1]*scale,left+b[2]*scale,top+b[3]*scale,paint);paint.setPathEffect(null);
    }
    private void badge(Canvas canvas,int[] b,int index,int color) {
        float x=left+b[0]*scale,y=top+b[1]*scale;
        if(x>getWidth() || y>getHeight() || left+b[2]*scale<0 || top+b[3]*scale<0)return;
        String label="#"+index;paint.setTextSize(dp(10));float w=paint.measureText(label)+dp(6);
        x=Math.max(0,Math.min(getWidth()-w,x));y=Math.max(dp(14),Math.min(getHeight(),y));
        paint.setStyle(Paint.Style.FILL);paint.setColor(color);canvas.drawRect(x,y-dp(14),x+w,y,paint);
        paint.setColor(Color.WHITE);canvas.drawText(label,x+dp(3),y-dp(3),paint);
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        gestures.onTouchEvent(e);
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);downX=lastX=e.getX();downY=lastY=e.getY();moved=multitouch=false;return true;
        }
        if(e.getPointerCount()>1)multitouch=true;
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE) {
            if(Math.hypot(e.getX()-downX,e.getY()-downY)>dp(6))moved=true;
            if(!multitouch && zoom>1 && !gestures.isInProgress()){panX+=e.getX()-lastX;panY+=e.getY()-lastY;invalidate();}
            lastX=e.getX();lastY=e.getY();return true;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_UP) {
            if(!moved && !multitouch) {
                overlaps=model.hits((e.getX()-left)/scale,(e.getY()-top)/scale,filter);
                if(!overlaps.isEmpty())selectIndex(overlaps.get(0),false,true);performClick();
            }
            getParent().requestDisallowInterceptTouchEvent(false);return true;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)getParent().requestDisallowInterceptTouchEvent(false);
        return true;
    }
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    @Override public boolean performClick(){super.performClick();return true;}
}
