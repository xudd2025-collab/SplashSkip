package com.codex.splashskip;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.util.*;

/** Replay the production fresh-frame OCR sequence on an explicitly supplied skip screenshot. */
public final class SuppliedOpeningCheck {
    public static void main(String[] a)throws Exception {
        Path assets=Paths.get(a[0]);var im=ImageIO.read(Paths.get(a[1]).toFile());int w=im.getWidth(),h=im.getHeight();
        int[] pixels=im.getRGB(0,0,w,h,null,0,w);
        try(OnnxUiText engine=new OnnxUiText(Files.readAllBytes(assets.resolve("ui_text_det.onnx")),Files.readAllBytes(assets.resolve("ui_text_rec.onnx")),Files.readString(assets.resolve("ui_text_keys.txt")))) {
            engine.warm();BilibiliVisualMatcher.Hit hit=null;
            for(int i=0;i<4 && hit==null;i++) {
                var r=engine.find(pixels,w,h,true,()->false,700);hit=r.hit;
                System.out.println("frame="+i+" status="+r.status+" detailed="+r.detailed+" det="+r.detectMs+" rec="+r.recognizeMs);
                for(var word:r.words)System.out.println(word.text+" confidence="+word.confidence+" weakest="+word.weakest);
            }
            if(hit==null || !UiControlPolicy.SKIP.equals(hit.rule))throw new AssertionError("Supplied skip not verified");
            System.out.println("PASS supplied countdown skip at "+hit.x+","+hit.y);
            // With the actual button removed, the same advertiser and disclosure
            // must not authorize a point from the previous frame.
            int[] b=hit.textBounds;int pad=12;
            for(int y=Math.max(0,b[1]-pad);y<Math.min(h,b[3]+pad);y++)
                Arrays.fill(pixels,y*w+Math.max(0,b[0]-pad),y*w+Math.min(w,b[2]+pad),0xff999999);
            engine.resetResolution();
            for(int i=0;i<3;i++)if(engine.find(pixels,w,h,true,()->false,700).hit!=null)throw new AssertionError("Removed skip reused");
            System.out.println("PASS current button required after creative remains");
        }
    }
}
