package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.nio.*;
import java.nio.file.*;
public final class ProfileTextCheck {
    public static void main(String[] a)throws Exception {
        BufferedImage atlas=ScreenshotRuleCheck.read(a[0]+"/generic_skip_glyphs.png");var glyphs=new Template[atlas.getHeight()/62];for(int i=0;i<glyphs.length;i++)glyphs[i]=AdLabelVerifier.glyph(atlas.getRGB(0,i*62,116,62,null,0,116),116,62);int[][] sizes={{110,60},{85,45},{115,62}};
        ByteBuffer bb=ByteBuffer.wrap(Files.readAllBytes(Paths.get(a[1]))).order(ByteOrder.LITTLE_ENDIAN);float[] weights=new float[bb.remaining()/4];bb.asFloatBuffer().get(weights);
        ControlTextMatcher.Model model=(f,box,head)->{
            BufferedImage original=new BufferedImage(f.originalWidth,f.originalHeight,BufferedImage.TYPE_INT_RGB);original.setRGB(0,0,f.originalWidth,f.originalHeight,f.pixels,0,f.originalWidth);
            BufferedImage crop=original.getSubimage(box[0],box[1],box[2]-box[0],box[3]-box[1]);BufferedImage small=new BufferedImage(32,20,BufferedImage.TYPE_INT_RGB);var g=small.createGraphics();g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(crop,0,0,32,20,null);g.dispose();
            float[] x=new float[640];float mean=0;for(int i=0;i<640;i++){int c=small.getRGB(i%32,i/32);x[i]=((c>>16)&255)*.299f+((c>>8)&255)*.587f+(c&255)*.114f;mean+=x[i];}mean/=640;float var=0;for(float v:x)var+=(v-mean)*(v-mean);float scale=(float)Math.sqrt(var/640)+20;for(int i=0;i<640;i++)x[i]=Math.max(-3,Math.min(3,(x[i]-mean)/scale));
            float p=weights[10240+16+16];for(int i=0;i<16;i++){float h=weights[10240+i];for(int j=0;j<640;j++)h+=weights[i*640+j]*x[j];p+=weights[10240+16+i]*Math.max(0,h);}return (float)(1/(1+Math.exp(-p)));
        };
        for(int i=2;i<a.length-1;i++) {
            BufferedImage original=ScreenshotRuleCheck.read(a[i]);var hit=ProfileTextMatcher.find(SceneRuleCheck.frame(original),glyphs,sizes,1216,model);
            if(hit==null)throw new AssertionError("profile missed "+a[i]);System.out.printf("PASS profile %s at=%d,%d model=%.3f%n",Paths.get(a[i]).getFileName(),hit.x,hit.y,hit.probability);
            BufferedImage moved=MovingControlsCheck.shifted(original,-300,370);var relocated=ProfileTextMatcher.find(SceneRuleCheck.frame(moved),glyphs,sizes,1216,model);
            if(relocated==null || Math.abs(relocated.x-hit.x+300)>18 || Math.abs(relocated.y-hit.y-370)>18)throw new AssertionError("profile relocation "+a[i]);
            System.out.printf("PASS relocated profile at=%d,%d%n",relocated.x,relocated.y);
        }
        var negative=ScreenshotRuleCheck.read(a[a.length-1]);if(ProfileTextMatcher.find(SceneRuleCheck.frame(negative),glyphs,sizes,1216,model)!=null)throw new AssertionError("normal map accepted");System.out.println("PASS profile rejects normal map");
    }
}
