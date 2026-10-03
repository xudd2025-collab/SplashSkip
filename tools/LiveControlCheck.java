package com.codex.splashskip;
import java.nio.file.*;
import java.awt.image.BufferedImage;
public final class LiveControlCheck {
 static int[] pixels(BufferedImage image,int[] b){return image.getRGB(b[0],b[1],b[2]-b[0],b[3]-b[1],null,0,b[2]-b[0]);}
 public static void main(String[] args)throws Exception {
  try(OnnxUiText engine=new OnnxUiText(Files.readAllBytes(Paths.get(args[0],"ui_text_det.onnx")),Files.readAllBytes(Paths.get(args[0],"ui_text_rec.onnx")),new String(Files.readAllBytes(Paths.get(args[0],"ui_text_keys.txt")),"UTF-8"))) {
   BufferedImage home=ScreenshotRuleCheck.read(args[1]),article=ScreenshotRuleCheck.read(args[2]);
   for(int i=3;i<args.length;i++) {
    BufferedImage im=ScreenshotRuleCheck.read(args[i]);engine.resetResolution();
    var result=engine.find(im.getRGB(0,0,im.getWidth(),im.getHeight(),null,0,im.getWidth()),im.getWidth(),im.getHeight(),true,()->false,4000);
    if(result.hit==null || !UiControlPolicy.SKIP.equals(result.hit.rule))throw new AssertionError("skip absent "+args[i]);
    int[] b=result.hit.textBounds;int w=b[2]-b[0],h=b[3]-b[1];
    if(!engine.verifyControl(pixels(im,b),w,h,result.hit.rule,()->false,1000))throw new AssertionError("current control rejected");
    for(BufferedImage changed:new BufferedImage[]{home,article}) {
     BufferedImage scaled=ScreenshotRuleCheck.resize(changed,im.getWidth());
     if(engine.verifyControl(pixels(scaled,b),w,h,result.hit.rule,()->false,1000))throw new AssertionError("old coordinate accepted on changed page");
    }
    if(engine.verifyControl(pixels(im,b),w,h,result.hit.rule,()->false,0))throw new AssertionError("expired recheck accepted");
    System.out.println("PASS current skip + reject changed home/article + reject expired recheck "+Paths.get(args[i]).getFileName());
   }
   for(BufferedImage negative:new BufferedImage[]{home,article}) {
    engine.resetResolution();
    if(engine.find(negative.getRGB(0,0,negative.getWidth(),negative.getHeight(),null,0,negative.getWidth()),negative.getWidth(),negative.getHeight(),true,()->false,4000).hit!=null)throw new AssertionError("normal page clicked");
    System.out.println("PASS no action on normal page");
   }
  }
 }
}
