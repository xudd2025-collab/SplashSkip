package com.codex.splashskip;
import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;

/** Actual screenshots are held out of training; move controls and replace all creative content. */
public final class ControlTextCheck implements ControlTextMatcher.Model {
    final float[] weights;
    ControlTextCheck(String path)throws Exception {ByteBuffer b=ByteBuffer.wrap(Files.readAllBytes(Paths.get(path))).order(ByteOrder.LITTLE_ENDIAN);weights=new float[b.remaining()/4];b.asFloatBuffer().get(weights);}
    public float probability(Frame f,int[] box,int head) {
        BufferedImage crop=new BufferedImage(box[2]-box[0],box[3]-box[1],BufferedImage.TYPE_INT_RGB);crop.setRGB(0,0,crop.getWidth(),crop.getHeight(),f.pixels,box[1]*f.originalWidth+box[0],f.originalWidth);
        BufferedImage small=new BufferedImage(32,20,BufferedImage.TYPE_INT_RGB);Graphics2D g=small.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(crop,0,0,32,20,null);g.dispose();
        float[] input=new float[640];float mean=0;for(int i=0;i<640;i++){int c=small.getRGB(i%32,i/32);input[i]=((c>>16)&255)*.299f+((c>>8)&255)*.587f+(c&255)*.114f;mean+=input[i];}mean/=640;float var=0;for(float v:input)var+=(v-mean)*(v-mean);float scale=(float)Math.sqrt(var/640)+20;for(int i=0;i<640;i++)input[i]=Math.max(-3,Math.min(3,(input[i]-mean)/scale));
        float[] hidden=new float[32];for(int i=0;i<32;i++){float v=weights[20480+i];for(int j=0;j<640;j++)v+=weights[i*640+j]*input[j];hidden[i]=Math.max(0,v);}
        float result=weights[20512+64+head];for(int i=0;i<32;i++)result+=weights[20512+head*32+i]*hidden[i];return (float)(1/(1+Math.exp(-result)));
    }
    static ControlTextMatcher matcher(String folder)throws Exception {
        BufferedImage atlas=ScreenshotRuleCheck.read(folder+"/control_close_glyphs.png");int w=atlas.getWidth(),h=62;Template[] ts=new Template[atlas.getHeight()/h];
        for(int i=0;i<ts.length;i++)ts[i]=ControlTextMatcher.glyph(atlas.getRGB(0,i*h,w,h,null,0,w),w,h);return new ControlTextMatcher(ts);
    }
    static void yes(String name,BufferedImage source,ControlTextMatcher matcher,ControlTextCheck model,float nx,float ny) {
        for(int w:new int[]{640,960,1280,2048,2880}) {
            BufferedImage im=ScreenshotRuleCheck.resize(source,w);long started=System.nanoTime();Hit hit=matcher.find(SceneRuleCheck.frame(im),model,()->false);
            if(hit==null || Math.abs(hit.x/(float)w-nx)>.014f || Math.abs(hit.y/(float)im.getHeight()-ny)>.024f)throw new AssertionError(name+" width="+w+" hit="+(hit==null?"none":hit.x+","+hit.y+" p="+hit.modelProbability));
            System.out.printf("PASS %s width=%d tap=%d,%d glyph=%.3f model=%.3f elapsed=%dms%n",name,w,hit.x,hit.y,hit.score,hit.modelProbability,(System.nanoTime()-started)/1000000);
        }
    }
    static BufferedImage buttonOnly(BufferedImage source,int[] box,int x,int y) {
        float scale=source.getWidth()/2048f;box=box.clone();for(int k=0;k<4;k++)box[k]=Math.round(box[k]*scale);x=Math.round(x*scale);y=Math.round(y*scale);
        BufferedImage im=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=im.createGraphics();g.setColor(new Color(60,120,160));g.fillRect(0,0,im.getWidth(),im.getHeight());g.drawImage(source,x,y,x+box[2]-box[0],y+box[3]-box[1],box[0],box[1],box[2],box[3],null);g.dispose();return im;
    }
    static BufferedImage erase(BufferedImage im,int x,int y,int w,int h,Color fill) {
        float s=im.getWidth()/2048f;return ScreenshotRuleCheck.erase(im,Math.round(x*s),Math.round(y*s),Math.round(w*s),Math.round(h*s),fill);
    }
    public static void main(String[] args)throws Exception {
        var matcher=matcher(args[0]);var model=new ControlTextCheck(args[1]);var y=ScreenshotRuleCheck.read(args[2]);var i=ScreenshotRuleCheck.read(args[3]);
        yes("shared close-ad Youku",y,matcher,model,1695/2048f,100/943f);yes("shared close-ad iQiyi prefix",i,matcher,model,1468/2048f,68/943f);
        int[] yb={1594,63,1798,137},ib={1358,24,1898,112};
        yes("new creative and moved Youku control",buttonOnly(y,yb,580,360),matcher,model,681/2048f,397/943f);
        yes("new creative and moved iQiyi control",buttonOnly(i,ib,680,410),matcher,model,790/2048f,454/943f);
        SceneRuleCheck.Find find=im->matcher.find(SceneRuleCheck.frame(im),model,()->false);
        SceneRuleCheck.none("Youku missing close label; mute X remains",erase(y,1594,63,204,74,new Color(0,128,60)),find);
        SceneRuleCheck.none("iQiyi missing close button; mute X remains",erase(i,1358,24,540,88,new Color(249,229,205)),find);
        for(String word:new String[]{"关闭网页","广告设置","继续播放","直接关闭","取消","广告","×","关闭广告功能"}) {
            BufferedImage im=new BufferedImage(1216,900,BufferedImage.TYPE_INT_RGB);Graphics2D g=im.createGraphics();g.setColor(Color.GRAY);g.fillRect(0,0,1216,900);g.setColor(Color.BLACK);g.fillRoundRect(350,380,500,90,70,70);g.setColor(Color.WHITE);g.setFont(new Font("Microsoft YaHei",Font.BOLD,40));g.drawString(word,410,440);g.dispose();SceneRuleCheck.none("normal or unrelated control "+word,im,find);
        }
        var flat=erase(ScreenshotRuleCheck.resize(y,2048),1594,63,204,74,new Color(0,128,60));Graphics2D g=flat.createGraphics();g.setColor(Color.WHITE);g.setFont(new Font("Microsoft YaHei",Font.BOLD,32));g.drawString("关闭广告",1630,112);g.dispose();SceneRuleCheck.none("phrase without control boundary",flat,find);
        SceneRuleCheck.none("normal drama recommendation",ScreenshotRuleCheck.read(args[4]),find);
    }
}
