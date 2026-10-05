package com.codex.splashskip.capture;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.os.HandlerThread;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;

/** Shell screenshot stream only; accessibility nodes come from the existing authorized service. */
public final class DeviceScreens {
    private static final DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)));
    static void packet(JSONObject meta,byte[] image)throws Exception {
        byte[] b=meta.toString().getBytes("UTF-8");out.writeInt(0x53534350);out.writeInt(b.length);out.write(b);out.writeInt(image.length);out.write(image);out.flush();
    }
    public static void main(String[] args)throws Exception {
        int seconds=Integer.parseInt(args[0]);if(seconds<1||seconds>300)throw new IllegalArgumentException();
        int shortSide=args.length>1?Integer.parseInt(args[1]):1600;
        if(shortSide<400||shortSide>2048)throw new IllegalArgumentException("image short side");
        HandlerThread h=new HandlerThread("desktop-screen");h.start();UiAutomation a=null;
        try {
            Class<?> c=Class.forName("android.app.IUiAutomationConnection");Object conn=Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
            Constructor<UiAutomation> ctor=UiAutomation.class.getDeclaredConstructor(android.os.Looper.class,c);ctor.setAccessible(true);a=ctor.newInstance(h.getLooper(),conn);
            int flag=UiAutomation.class.getField("FLAG_DONT_USE_ACCESSIBILITY").getInt(null)
                |UiAutomation.class.getField("FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES").getInt(null);
            UiAutomation.class.getDeclaredMethod("connect",int.class).invoke(a,flag);
            packet(new JSONObject().put("kind","ready").put("pid",android.os.Process.myPid()),new byte[0]);
            long until=SystemClock.uptimeMillis()+seconds*1000L;
            while(SystemClock.uptimeMillis()<until) {
                long began=SystemClock.uptimeMillis(),wall=System.currentTimeMillis();Bitmap original=a.takeScreenshot(),small=null;
                if(original==null){SystemClock.sleep(200);continue;}
                try {
                    int w=original.getWidth(),height=original.getHeight();float scale=Math.min(1,shortSide/(float)Math.min(w,height));
                    small=Bitmap.createScaledBitmap(original,Math.round(w*scale),Math.round(height*scale),true);
                    ByteArrayOutputStream jpeg=new ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,96,jpeg);
                    packet(new JSONObject().put("kind","screen").put("screenshot_uptime",began).put("wall_time",wall).put("width",w).put("height",height)
                        .put("image_width",small.getWidth()).put("image_height",small.getHeight()),jpeg.toByteArray());
                }finally{if(small!=null && small!=original)small.recycle();original.recycle();}
                long wait=200-(SystemClock.uptimeMillis()-began);if(wait>0)SystemClock.sleep(wait);
            }
            packet(new JSONObject().put("kind","done"),new byte[0]);
        }catch(Throwable t){packet(new JSONObject().put("kind","error").put("error",t.toString()),new byte[0]);}
        finally{if(a!=null)try{UiAutomation.class.getDeclaredMethod("disconnect").invoke(a);}catch(Exception ignored){}h.quitSafely();}
    }
}
