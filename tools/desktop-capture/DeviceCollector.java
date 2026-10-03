package com.codex.splashskip.capture;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;

/** Read-only, temporary ADB shell bridge. Controlled and stored by the desktop. */
public final class DeviceCollector {
    private static volatile long eventSequence;
    private static final int MAX_NODES=384;
    private static final DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)));
    private static String value(CharSequence s){return s==null?"":s.toString();}
    private static void packet(JSONObject meta,byte[] image)throws Exception {
        byte[] json=meta.toString().getBytes("UTF-8");out.writeInt(0x53534350);out.writeInt(json.length);out.write(json);
        out.writeInt(image.length);out.write(image);out.flush();
    }
    private static void walk(AccessibilityNodeInfo node,int parent,int depth,JSONArray nodes,long deadline,boolean[] complete)throws Exception {
        if(node==null)return;
        if(depth>24 || nodes.length()>=MAX_NODES || SystemClock.uptimeMillis()>deadline){complete[0]=false;return;}
        Rect b=new Rect();node.getBoundsInScreen(b);int index=nodes.length(),children=node.getChildCount();
        nodes.put(new JSONObject().put("index",index).put("parent",parent).put("child_count",children)
            .put("bounds",new JSONArray(new int[]{b.left,b.top,b.right,b.bottom}))
            .put("class",value(node.getClassName())).put("id",value(node.getViewIdResourceName()))
            .put("text",value(node.getText())).put("description",value(node.getContentDescription()))
            .put("package",value(node.getPackageName())).put("clickable",node.isClickable())
            .put("enabled",node.isEnabled()).put("visible",node.isVisibleToUser()));
        for(int i=0;i<children;i++) {
            if(SystemClock.uptimeMillis()>deadline || nodes.length()>=MAX_NODES){complete[0]=false;break;}
            AccessibilityNodeInfo child=node.getChild(i);try{walk(child,index,depth+1,nodes,deadline,complete);}finally{if(child!=null)child.recycle();}
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("package duration-seconds interval-ms");
        String pkg=args[0];int seconds=Integer.parseInt(args[1]),interval=Integer.parseInt(args[2]);
        if(seconds<1 || seconds>300 || interval<120 || interval>5000)throw new IllegalArgumentException("capture bounds");
        HandlerThread handler=new HandlerThread("desktop-capture-events");handler.start();UiAutomation automation=null;
        try {
            packet(new JSONObject().put("kind","initializing").put("pid",android.os.Process.myPid()),new byte[0]);
            Class<?> connectionType=Class.forName("android.app.IUiAutomationConnection");
            Object connection=Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
            Constructor<UiAutomation> constructor=UiAutomation.class.getDeclaredConstructor(android.os.Looper.class,connectionType);
            constructor.setAccessible(true);automation=constructor.newInstance(handler.getLooper(),connection);
            // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES. Keep the assistant's service active.
            int preserveServices=UiAutomation.class.getField("FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES").getInt(null);
            UiAutomation.class.getDeclaredMethod("connect",int.class).invoke(automation,preserveServices);
            packet(new JSONObject().put("kind","connected"),new byte[0]);
            AccessibilityServiceInfo info=automation.getServiceInfo();
            info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS|AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);automation.setOnAccessibilityEventListener(event->eventSequence++);
            packet(new JSONObject().put("kind","ready").put("package",pkg).put("pid",android.os.Process.myPid()).put("protocol",1).put("non_suppressing",true),new byte[0]);
            long until=SystemClock.uptimeMillis()+seconds*1000L;int frame=0;
            while(SystemClock.uptimeMillis()<until) {
                long began=SystemClock.uptimeMillis(),events=eventSequence;AccessibilityNodeInfo root=automation.getRootInActiveWindow();Bitmap image=null;
                try {
                    if(root==null || !pkg.equals(value(root.getPackageName()))) {
                        packet(new JSONObject().put("kind","waiting").put("uptime",began),new byte[0]);
                    } else {
                        image=automation.takeScreenshot();long imageTime=SystemClock.uptimeMillis();
                        if(image==null){packet(new JSONObject().put("kind","screenshot-unavailable"),new byte[0]);continue;}
                        // Refresh after the screenshot; never claim that separate APIs are atomic.
                        root.refresh();JSONArray nodes=new JSONArray();boolean[] complete={true};
                        walk(root,-1,0,nodes,SystemClock.uptimeMillis()+100,complete);
                        long treeTime=SystemClock.uptimeMillis();AccessibilityNodeInfo after=automation.getRootInActiveWindow();
                        boolean owner=after!=null && pkg.equals(value(after.getPackageName()));if(after!=null)after.recycle();
                        if(!owner){packet(new JSONObject().put("kind","owner-changed"),new byte[0]);continue;}
                        ByteArrayOutputStream png=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,png);
                        packet(new JSONObject().put("kind","frame").put("frame",frame++).put("package",pkg)
                            .put("width",image.getWidth()).put("height",image.getHeight()).put("wall_time",System.currentTimeMillis())
                            .put("started_uptime",began).put("screenshot_uptime",imageTime).put("tree_uptime",treeTime)
                            .put("event_sequence_start",events).put("event_sequence_end",eventSequence)
                            .put("tree_complete",complete[0]).put("tree_nodes",nodes),png.toByteArray());
                    }
                }finally{if(root!=null)root.recycle();if(image!=null)image.recycle();}
                long wait=interval-(SystemClock.uptimeMillis()-began);if(wait>0)SystemClock.sleep(wait);
            }
            packet(new JSONObject().put("kind","done"),new byte[0]);
        }catch(Throwable error) {
            packet(new JSONObject().put("kind","error").put("error",error.toString()),new byte[0]);
        }finally {
            if(automation!=null)try{UiAutomation.class.getDeclaredMethod("disconnect").invoke(automation);}catch(Exception ignored){}
            handler.quitSafely();out.flush();
        }
    }
}
