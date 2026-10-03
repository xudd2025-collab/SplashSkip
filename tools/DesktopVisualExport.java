package com.codex.splashskip;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Pure desktop OCR export. No connection to ADB and no action executor. */
public final class DesktopVisualExport {
    static String quote(String s){StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){
        if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);
    }return b.append('"').toString();}
    public static void main(String[] args)throws Exception {
        Path assets=Paths.get(args[0]);
        try(OnnxUiText engine=new OnnxUiText(Files.readAllBytes(assets.resolve("ui_text_det.onnx")),Files.readAllBytes(assets.resolve("ui_text_rec.onnx")),Files.readString(assets.resolve("ui_text_keys.txt")))){
            engine.warm();
            for(int i=1;i<args.length;i+=2){
                var im=ImageIO.read(Paths.get(args[i]).toFile());int w=im.getWidth(),h=im.getHeight();
                var result=engine.inspect(im.getRGB(0,0,w,h,null,0,w),w,h,6000);
                StringBuilder out=new StringBuilder("{\"status\":").append(quote(result.status)).append(",\"boxes\":").append(result.boxes).append(",\"recognized\":").append(result.words.size()).append(",\"words\":[");
                int n=0;for(var word:result.words){
                    if(word.confidence<.85f)continue;if(n++>0)out.append(',');
                    out.append("{\"text\":").append(quote(word.text)).append(",\"confidence\":").append(word.confidence)
                        .append(",\"bounds\":").append(Arrays.toString(new int[]{word.left,word.top,word.right,word.bottom}))
                        .append(",\"role\":").append(quote(UiControlPolicy.action(word.text))).append('}');
                }
                Files.writeString(Paths.get(args[i+1]),out.append("]}").toString());
            }
        }
    }
}
