package com.codex.splashskip;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
/** Replay redacted, captured tree proposals against saved images without any device clicks. */
public class CapturedTreeCheck {
    public static void main(String[] args)throws Exception {
        Path a=Paths.get(args[0]);
        try(OnnxUiText e=new OnnxUiText(Files.readAllBytes(a.resolve("ui_text_det.onnx")),Files.readAllBytes(a.resolve("ui_text_rec.onnx")),Files.readString(a.resolve("ui_text_keys.txt")))) {
            e.warm();
            for(int i=1;i<args.length;i+=2) {
                var im=ImageIO.read(Paths.get(args[i+1]).toFile());int w=im.getWidth(),h=im.getHeight();
                java.util.List<ControlTree.Node> nodes=new ArrayList<>();
                for(String line:Files.readAllLines(Paths.get(args[i]))) {
                    String[] p=line.split("\t",-1);int[] b=new int[4];for(int k=0;k<4;k++)b[k]=Integer.parseInt(p[k+5]);
                    nodes.add(new ControlTree.Node(Integer.parseInt(p[0]),Integer.parseInt(p[1]),b,Boolean.parseBoolean(p[2]),p[3],p[4]));
                }
                var t=new ControlTree.Snapshot("captured.test",1,0,w,h,nodes,false);
                int[] px=im.getRGB(0,0,w,h,null,0,w);long began=System.nanoTime();
                var r=TreeVisualLocator.find(e,px,w,h,true,t,Collections.emptyMap(),hint->0,()->false,240);
                System.out.println(args[i+1]+" result="+TreeVisualLocator.lastReason()+" time="+(System.nanoTime()-began)/1_000_000+"ms");
                if(r==null)throw new AssertionError("Captured structure + visible cue rejected");
                System.out.println("PASS current control at "+r.hit.x+","+r.hit.y);
            }
        }
    }
}
