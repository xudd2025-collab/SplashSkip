package com.codex.splashskip;

import java.nio.file.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;

/** Runs the same detector, preprocessing, recognizer and policy shipped on Android. */
public final class UiTextCheck {
    static OnnxUiText.Result run(OnnxUiText engine,BufferedImage im,boolean opening)throws Exception {
        int w=im.getWidth(),h=im.getHeight();long start=System.nanoTime();
        OnnxUiText.Result result=engine.find(im.getRGB(0,0,w,h,null,0,w),w,h,opening,()->false,4000);
        System.out.println("OCR status="+result.status+" detected="+result.boxes+" det="+result.detectMs+" rec="+result.recognizeMs+" total="+(System.nanoTime()-start)/1000000+"ms");
        for(UiControlPolicy.Word word:result.words)System.out.printf("  %s %.5f/%.5f %d,%d,%d,%d action=%s button=%s%n",word.text,word.confidence,word.weakest,word.left,word.top,word.right,word.bottom,UiControlPolicy.action(word.text),UiControlPolicy.buttonBoundary(im.getRGB(0,0,w,h,null,0,w),w,h,word));
        return result;
    }
    static void yes(String name,OnnxUiText engine,BufferedImage im,String expected,int x,int y)throws Exception {
        OnnxUiText.Result result=run(engine,im,true);var hit=result.hit;
        if(hit==null || !expected.equals(hit.rule) || Math.abs(hit.x-x)>24 || Math.abs(hit.y-y)>24)throw new AssertionError(name+" "+(hit==null?"MISS":hit.rule+" "+hit.x+","+hit.y));
        System.out.println("PASS "+name+" "+hit.rule+" tap="+hit.x+","+hit.y);
    }
    static void no(String name,OnnxUiText engine,BufferedImage im)throws Exception {
        if(run(engine,im,true).hit!=null)throw new AssertionError(name);System.out.println("PASS reject "+name);
    }
    static BufferedImage synthetic(String control,String cue,int x,int y,boolean navigation) {
        BufferedImage im=new BufferedImage(960,1800,BufferedImage.TYPE_INT_RGB);Graphics2D g=im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(48,110,152));g.fillRect(0,0,960,1800);g.setColor(Color.BLACK);g.fillRoundRect(x-90,y-45,180,90,60,60);
        g.setColor(Color.WHITE);g.setFont(new Font("Microsoft YaHei",Font.PLAIN,34));g.drawString(control,x-g.getFontMetrics().stringWidth(control)/2,y+12);
        g.setFont(new Font("Microsoft YaHei",Font.PLAIN,30));g.drawString(cue,70,1530);
        if(navigation){g.drawString("首页",70,1680);g.drawString("我的",750,1680);}g.dispose();return im;
    }
    public static void main(String[] a)throws Exception {
        try(OnnxUiText engine=new OnnxUiText(Files.readAllBytes(Paths.get(a[0],"ui_text_det.onnx")),Files.readAllBytes(Paths.get(a[0],"ui_text_rec.onnx")),new String(Files.readAllBytes(Paths.get(a[0],"ui_text_keys.txt")),java.nio.charset.StandardCharsets.UTF_8))) {
            engine.warm();System.out.println("PASS production model warm-up");
            for(int i=1;i<a.length;i++) {
                BufferedImage source=ScreenshotRuleCheck.read(a[i]);
                if(i==4){no("normal Youku home",engine,source);continue;}
                int x=i==1?1012:i==2?1075:1089,y=i==1?136:i==2?233:218;
                yes("held-out source "+i,engine,source,i==1?UiControlPolicy.CLOSE:UiControlPolicy.SKIP,x,y);
                for(int width:new int[]{720,1440}) {
                    float scale=width/(float)source.getWidth();yes("source "+i+" width="+width,engine,ScreenshotRuleCheck.resize(source,width),i==1?UiControlPolicy.CLOSE:UiControlPolicy.SKIP,Math.round(x*scale),Math.round(y*scale));
                }
                no("source "+i+" action removed",engine,ScreenshotRuleCheck.erase(source,x-100,y-60,200,120,new Color(48,110,152)));
            }
            for(String control:new String[]{"跳过","关闭","关闭广告"})for(int[] p:new int[][]{{190,550},{710,1250}})
                yes("new creative/moved "+control,engine,synthetic(control,"广告",p[0],p[1],false),UiControlPolicy.action(control),p[0],p[1]);
            for(String control:new String[]{"继续","广告设置","跳转","×","关闭网页","关闭广告功能"})no("unrelated "+control,engine,synthetic(control,"广告",300,500,false));
            no("ordinary close without advertisement",engine,synthetic("关闭","消息提醒",300,500,false));
            no("skip control without advertisement context",engine,synthetic("跳过","欢迎使用",300,500,false));
            yes("destination disclosure supplies ad context",engine,synthetic("跳过","点击跳转详情页面或第三方应用",500,800,false),UiControlPolicy.SKIP,500,800);
            no("ordinary navigation text is not an ad disclosure",engine,synthetic("跳过","跳转设置",500,800,false));
            no("partial third party text is not an ad disclosure",engine,synthetic("跳过","第三方应用",500,800,false));
            no("normal page with an unrelated ad",engine,synthetic("关闭","广告",300,500,true));
            BufferedImage im=synthetic("跳过","广告",300,500,false);int[] pixels=im.getRGB(0,0,960,1800,null,0,960);
            if(!engine.find(pixels,960,1800,true,()->true,1000).status.equals("cancelled"))throw new AssertionError("cancellation");
            OnnxUiText.Result shortRun=engine.find(pixels,960,1800,true,()->false,1);
            if(shortRun.hit!=null || !shortRun.status.equals("deadline"))throw new AssertionError("expired frame accepted");
            yes("recognizer recovers after interrupted run",engine,im,UiControlPolicy.SKIP,300,500);
        }
    }
}
