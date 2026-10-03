package com.codex.splashskip;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
/** Export production parent/child and local visual features from redacted diagnostic TSV. */
public class CapturedTreeFeatures {
    public static void main(String[] args)throws Exception {
        var im=ImageIO.read(Paths.get(args[1]).toFile());int w=im.getWidth(),h=im.getHeight();
        int tw=Integer.parseInt(args[3]),th=Integer.parseInt(args[4]);
        java.util.List<ControlTree.Node> nodes=new ArrayList<>();
        for(String line:Files.readAllLines(Paths.get(args[0]))) {
            String[] p=line.split("\t",-1);int[] b=new int[4];
            for(int k=0;k<4;k++)b[k]=Integer.parseInt(p[k+5]);
            nodes.add(new ControlTree.Node(Integer.parseInt(p[0]),Integer.parseInt(p[1]),b,Boolean.parseBoolean(p[2]),p[3],p[4]));
        }
        var t=new ControlTree.Snapshot("captured.test",1,0,tw,th,nodes,false);
        int[] pixels=im.getRGB(0,0,w,h,null,0,w);StringBuilder out=new StringBuilder("[");
        for(int i=0;i<nodes.size();i++) {
            var n=nodes.get(i);if(!ControlTree.small(n.box,tw,th))continue;
            int[] b={Math.round(n.box[0]*w/(float)tw),Math.round(n.box[1]*h/(float)th),Math.round(n.box[2]*w/(float)tw),Math.round(n.box[3]*h/(float)th)};
            var hint=t.hint(i,UiControlPolicy.CLOSE);float[] f=JointControlModel.features(pixels,w,h,b,hint);
            if(!JointControlModel.valid(f))continue;
            if(out.length()>1)out.append(',');
            out.append("{\"index\":").append(i).append(",\"features\":").append(Arrays.toString(f)).append('}');
        }
        Files.writeString(Paths.get(args[2]),out.append(']').toString());
    }
}
