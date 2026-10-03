package com.codex.splashskip;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Exports only production-located button crops to an external local check directory. */
public final class ActionModelCheck {
    public static void main(String[] args)throws Exception {
        HuyaVisualMatcher h=SceneRuleCheck.huya(args[0]);TencentVisualMatcher t=SceneRuleCheck.tencent(args[0]);
        File folder=new File(args[1]);if(!folder.isDirectory() && !folder.mkdirs())throw new IllegalArgumentException("output directory");
        for(int i=2;i<args.length;i++) {
            BufferedImage original=ScreenshotRuleCheck.read(args[i]);
            boolean landscape=original.getWidth()>original.getHeight();
            int[] widths=landscape?new int[]{1216,1920,2640}:new int[]{720,1080,1260,1440};
            for(int width:widths) {
                BufferedImage im=ScreenshotRuleCheck.resize(original,width);
                BilibiliVisualMatcher.Hit hit=landscape?h.find(SceneRuleCheck.frame(im),true,true):t.find(SceneRuleCheck.frame(im),true,true,true);
                if(hit==null)throw new AssertionError("no candidate "+i+" width="+width);
                int[] box=AdActionRegion.box(hit,im.getWidth(),im.getHeight());
                if(box==null)throw new AssertionError("invalid crop "+hit.rule);
                ImageIO.write(im.getSubimage(box[0],box[1],box[2]-box[0],box[3]-box[1]),"png",new File(folder,AdActionRegion.classIndex(hit.rule)+"_"+(i-2)+"-"+width+".png"));
            }
        }
        System.out.println("Exported production candidate crops to "+folder);
    }
}
