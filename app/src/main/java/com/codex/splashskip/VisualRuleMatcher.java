package com.codex.splashskip;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Profiles retain dimensions and glyphs. Every tap is located in the current image. */
final class VisualRuleMatcher {
    private final Context context;
    private final Map<String,Template> templates=new HashMap<>();
    private Template[] skipGlyphs;
    static final class Match {
        final String rule;final float score;final int x,y;final Rect textBox;
        Match(String rule,float score,int x,int y,Rect textBox) {this.rule=rule;this.score=score;this.x=x;this.y=y;this.textBox=textBox;}
    }
    VisualRuleMatcher(Context c) {context=c;}
    Match findText(Bitmap screen,SkipTextModel model,AppProfiles.Profile profile) {
        if(profile.layouts.length==0)return null;
        int[][] sizes=new int[profile.layouts.length][2];
        for(int i=0;i<sizes.length;i++){sizes[i][0]=profile.layouts[i].cropWidth;sizes[i][1]=profile.layouts[i].cropHeight;}
        ProfileTextMatcher.Match hit=ProfileTextMatcher.find(frame(screen),glyphs(),sizes,profile.referenceWidth,
                (f,box,head) -> model.probability(screen,new Rect(box[0],box[1],box[2],box[3])));
        return hit==null?null:new Match(profile.layouts[hit.index].name,hit.probability,hit.x,hit.y,new Rect(hit.box[0],hit.box[1],hit.box[2],hit.box[3]));
    }
    Match findPopup(Bitmap screen,AppProfiles.Profile profile) {
        if(profile.popup==null)return null;
        try {
            JSONObject rule=profile.popup;Frame f=frame(screen);
            Template cross=template(rule.getString("crossTemplate")),ring=template(rule.getString("ringTemplate"));
            if(cross==null || ring==null)return null;
            for(float scale:new float[]{1f,.8f,1.2f})for(float[] candidate:UiFeatureSearch.all(f,cross.scaled(scale),(float)rule.getDouble("crossThreshold"))) {
                float score=correlate(f,ring.scaled(scale),candidate[0],candidate[1]);
                if(score<rule.getDouble("ringThreshold"))continue;
                Hit target=f.hit(rule.getString("name"),candidate[0],candidate[1],Math.min(candidate[2],score));
                return new Match(target.rule,target.score,target.x,target.y,null);
            }
        } catch(Exception error) {Diagnostics.append(context,"profile feature error: "+error.getClass().getSimpleName());}
        return null;
    }
    private Frame frame(Bitmap image) {
        int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);
        return new Frame(pixels,w,h);
    }
    private Template template(String name) {
        if(!templates.containsKey(name)) {
            int id=context.getResources().getIdentifier(name,"drawable",context.getPackageName());
            BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;
            Bitmap image=id==0?null:BitmapFactory.decodeResource(context.getResources(),id,options);
            if(image==null)templates.put(name,null);
            else {
                int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);image.recycle();
                templates.put(name,new Template(pixels,w,h,32,32));
            }
        }
        return templates.get(name);
    }
    private Template[] glyphs() {
        if(skipGlyphs!=null)return skipGlyphs;
        Bitmap atlas=BitmapFactory.decodeResource(context.getResources(),R.drawable.generic_skip_glyphs);
        int w=atlas.getWidth(),h=62,n=atlas.getHeight()/h;skipGlyphs=new Template[n];
        for(int i=0;i<n;i++){int[] pixels=new int[w*h];atlas.getPixels(pixels,0,w,0,i*h,w,h);skipGlyphs[i]=AdLabelVerifier.glyph(pixels,w,h);}
        atlas.recycle();return skipGlyphs;
    }
}
