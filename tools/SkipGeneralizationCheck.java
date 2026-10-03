package com.codex.splashskip;

import static com.codex.splashskip.BilibiliVisualMatcher.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/** Untrained creatives and font families; no generated image is added to training. */
public final class SkipGeneralizationCheck {
    static BufferedImage variant(BufferedImage original,Font font,String word,int style) {
        BufferedImage im=ScreenshotRuleCheck.resize(original,1216);
        Graphics2D g=im.createGraphics();
        Color bg=style==0?new Color(32,43,56):style==1?new Color(183,223,174):new Color(240,217,193);
        g.setColor(bg);g.fillRect(0,330,1216,im.getHeight()-330);
        // Entirely replace the advertisement body, including its animation prompt.
        g.setColor(style==0?Color.CYAN:Color.MAGENTA);g.fillOval(240,600,720,1100);
        g.setColor(bg);g.fillRoundRect(1010,174,148,88,12,12);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);g.setColor(style==2?Color.BLACK:Color.WHITE);
        FontMetrics fm=g.getFontMetrics();
        g.drawString(word,1086-fm.stringWidth(word)/2,218+(fm.getAscent()-fm.getDescent())/2);g.dispose();return im;
    }
    public static void main(String[] a)throws Exception {
        TencentVisualMatcher matcher=SceneRuleCheck.tencent(a[0]);AdButtonModelCheck model=new AdButtonModelCheck(a[1]);
        BufferedImage source=ScreenshotRuleCheck.read(a[2]);int passed=0,missed=0;
        boolean evaluate=a.length>3 && "--report-misses".equals(a[3]);
        for(String file:new String[]{"Deng.ttf","STXIHEI.TTF"}) {
            Font face=Font.createFont(Font.TRUETYPE_FONT,new File("C:/Windows/Fonts/"+file));
            for(int style=0;style<3;style++) {
                BufferedImage creative=variant(source,face.deriveFont(36f),"跳过",style);
                for(int width:new int[]{720,1080,1216,1440}) {
                    BufferedImage im=ScreenshotRuleCheck.resize(creative,width);Hit hit=matcher.opening(SceneRuleCheck.frame(im),model);
                    float scale=width/1216f;
                    if(hit==null) {
                        if(evaluate) {
                            System.out.println("MISS font="+file+" style="+style+" width="+width);missed++;continue;
                        }
                        Frame frame=SceneRuleCheck.frame(im);
                        Hit expected=new Hit(TencentVisualMatcher.SPLASH,Math.round(1086*scale),Math.round(218*scale),1,width,im.getHeight());
                        System.out.println("expected-center probability="+model.probability(frame,expected)+" glyph86="+SkipGlyphVerifier.accepts(frame,expected,SceneRuleCheck.glyphs(a[0]),1,.86f));
                        for(Hit proposal:AdGlyphProposals.find(frame,TencentVisualMatcher.SPLASH,new float[]{.84f,.045f,.97f,.11f},1)) {
                            float p=model.probability(frame,proposal);
                            if(p<.8f)continue;
                            System.out.println("proposal="+proposal.x+","+proposal.y+" crop="+proposal.cropScale+" probability="+p);
                            for(String name:new String[]{"tencent_skip_text","tencent_skip_bold"}) {
                                Template text=ScreenshotRuleCheck.template(a[0],name,48,24).scaled(proposal.cropScale);
                                System.out.println(name+" evidence="+refine(frame,text,proposal.x*608f/width,proposal.y*608f/width,2)[2]);
                            }
                        }
                    }
                    if(hit==null || Math.abs(hit.x-1086*scale)>18*scale || Math.abs(hit.y-218*scale)>18*scale)
                        throw new AssertionError("untrained font="+file+" style="+style+" width="+width);
                    System.out.println("PASS unseen creative font="+file+" style="+style+" width="+width+" model="+hit.modelProbability);passed++;
                }
                for(String word:new String[]{"播放","关闭","购买","取消"}) {
                    BufferedImage negative=variant(source,face.deriveFont(36f),word,style);
                    Hit wrong=matcher.opening(SceneRuleCheck.frame(negative),model);
                    if(wrong!=null) {
                        Frame f=SceneRuleCheck.frame(negative);
                        for(String name:new String[]{"tencent_skip_text","tencent_skip_bold"}) {
                            Template text=ScreenshotRuleCheck.template(a[0],name,48,24);
                            System.out.println("false wordU="+Integer.toHexString(word.codePointAt(0))+" model="+wrong.modelProbability+" "+name+" corr="+refine(f,text,wrong.x/2f,wrong.y/2f,2)[2]);
                        }
                        throw new AssertionError("false skip U="+Integer.toHexString(word.codePointAt(0))+" font="+file);
                    }
                }
            }
        }
        System.out.println((missed==0?"PASS":"EVALUATION")+" model-held-out font variants recognized="+passed+" missed="+missed+"; all non-skip words rejected");
    }
}
