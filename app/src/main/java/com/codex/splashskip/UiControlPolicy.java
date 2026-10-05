package com.codex.splashskip;

import java.util.List;
import java.util.Locale;
import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Decisions use recognized control text and the present scene, never an advertiser or a stored point. */
final class UiControlPolicy {
    static final String SKIP="ui-text-skip", CLOSE="ui-text-close", CLOSE_AD="ui-text-close-ad";
    static final class Word {
        final String text;
        final float confidence, weakest;
        final int left,top,right,bottom;
        Word(String text,float confidence,float weakest,int l,int t,int r,int b) {
            this.text=text;this.confidence=confidence;this.weakest=weakest;left=l;top=t;right=r;bottom=b;
        }
        int width(){return right-left;} int height(){return bottom-top;}
        int x(){return (left+right)/2;} int y(){return (top+bottom)/2;}
    }
    static String normalized(String text) {
        StringBuilder b=new StringBuilder();
        for(char c:text.toLowerCase(Locale.ROOT).toCharArray()) {
            if(Character.isWhitespace(c))continue;
            if(c>='０' && c<='９')c=(char)(c-'０'+'0');
            b.append(c);
        }
        return b.toString();
    }
    static String action(String text) {
        String s=normalized(text);
        if(s.equals("跳过广告") || s.matches("(?:[0-9]{1,2}(?:秒|s)?[|·:]?)?(?:跳过|skip)(?:[|·:]?[0-9]{1,2}(?:秒|s)?)?"))return SKIP;
        if(s.equals("关闭广告") || s.equals("关闭广告，放大暂停画面") ||
                s.equals("关闭广告,放大暂停画面"))return CLOSE_AD;
        if(s.equals("关闭"))return CLOSE;
        return "";
    }
    static boolean isControl(String rule) {return SKIP.equals(rule)||CLOSE.equals(rule)||CLOSE_AD.equals(rule);}
    static boolean explicitSkipAd(String text) {
        return text!=null && normalized(text).equals("跳过广告");
    }
    /** A complete disclosure field, never a gesture hint or free-form marketing text. */
    static boolean navigationDisclosure(String text) {
        if(text==null)return false;
        String s=normalized(text);
        return s.matches("(?:点击|互动)?跳转(?:至)?详情页(?:面)?或第三方应用[>›]?") ||
                s.matches("(?:点击)?下载或(?:跳转(?:至)?|打开)第三方应用[>›]?");
    }
    /** One field must independently contain explicit Skip and one countdown. */
    static boolean explicitSkipCountdown(String text) {
        if(text==null)return false;
        String s=normalized(text);
        return s.matches("(?:[0-9]{1,2}(?:秒|s)?[|·:]?(?:跳过|skip)|(?:跳过|skip)[|·:]?[0-9]{1,2}(?:秒|s)?)");
    }
    static boolean adMark(Word word) {
        if(word.confidence<.96f || word.weakest<.90f)return false;
        String s=normalized(word.text);
        return s.matches("(?:互动|预加载)?广告(?:[|·丨]?(?:有奖|已wifi预加载|wifi预加载|已预加载))?") || s.equals("广告反馈");
    }
    static boolean prompt(Word word) {
        if(word.confidence<.96f || word.weakest<.90f)return false;
        String s=normalized(word.text);
        return s.equals("翻转手机") || s.equals("摇动手机") || s.equals("摇一摇") || s.equals("摇一摇手机") || s.equals("扭动手机") ||
                navigationDisclosure(word.text);
    }
    static Hit find(int[] pixels,int width,int height,List<Word> words,boolean opening) {
        return find(pixels,width,height,words,opening,java.util.Collections.emptyList());
    }
    /** Only TreeVisualLocator supplies parents after current label + same-branch cue OCR.
     * A small live clickable container can establish a translucent button whose edges
     * are invisible. Ordinary screenshot recognition still requires its visual boundary. */
    static Hit find(int[] pixels,int width,int height,List<Word> words,boolean opening,List<int[]> verifiedParents) {
        boolean ad=false,interactive=false;int navigation=0;
        for(Word word:words) {
            ad|=adMark(word);interactive|=prompt(word);
            String s=normalized(word.text);
            if(word.confidence>.96f && (s.equals("首页") || s.equals("我的") || s.equals("会员专区") || s.equals("发现")))navigation++;
        }
        Hit selected=null;
        for(Word word:words) {
            String rule=action(word.text);
            if(rule.isEmpty() || word.confidence<.985f || word.weakest<.96f)continue;
            if(word.width()>width*.48f || word.height()>Math.min(width,height)*.12f ||
                    word.left<1 || word.top<1 || word.right>=width || word.bottom>=height)continue;
            boolean boundary=buttonBoundary(pixels,width,height,word);
            if(CLOSE_AD.equals(rule)) { if(!boundary)continue; }
            else {
                if(!ad && !(opening && interactive))continue;
                // A standalone close needs a launch scene and a real button, not an unrelated page control.
                boolean adControlRow=false;
                for(Word mark:words)if(adMark(mark) && Math.abs(mark.y()-word.y())<=Math.max(mark.height(),word.height())*1.5f &&
                        Math.min(mark.right,word.right)<=Math.max(mark.left,word.left))adControlRow=true;
                // Some translucent capsules have an indistinguishable lower edge. A separately
                // recognized ad badge on the same control row supplies that missing scene evidence.
                boolean liveParent=false;
                if(CLOSE.equals(rule))for(int[] parent:verifiedParents)
                    liveParent|=ControlTree.safeParent(new int[]{word.left,word.top,word.right,word.bottom},parent,width,height);
                if(CLOSE.equals(rule) && (!opening || !boundary && !adControlRow && !liveParent || navigation>=2 && !interactive))continue;
                if(SKIP.equals(rule) && !opening && (!interactive || !boundary))continue;
            }
            Hit hit=new Hit(rule,word.x(),word.y(),word.confidence,width,height).withModel(word.confidence).withTextBounds(word.left,word.top,word.right,word.bottom);
            // Ambiguous scenes are deferred instead of choosing an arbitrary close control.
            if(selected!=null && (Math.abs(selected.x-hit.x)>word.width()/2 || Math.abs(selected.y-hit.y)>word.height()/2))return null;
            selected=hit;
        }
        return selected;
    }
    static boolean buttonBoundary(int[] pixels,int width,int height,Word word) {
        float h=word.height(),x=word.x(),y=word.y();
        // Sample the padding around this detected word, then find both edges of its current container.
        for(float padding:new float[]{.55f,.65f,.8f}) {
            float above=y-h*padding,below=y+h*padding;
            if(above<1 || below>=height-1)continue;
            float bg=gray(pixels,width,height,x,above);boolean flat=true;
            for(float dx:new float[]{-.35f,0,.35f}) {
                if(Math.abs(gray(pixels,width,height,x+word.width()*dx,above)-bg)>24 ||
                        Math.abs(gray(pixels,width,height,x+word.width()*dx,below)-bg)>24)flat=false;
            }
            if(!flat)continue;
            for(float edge:new float[]{1f,1.3f,1.7f,2.2f,2.8f}) {
                if(y-h*edge<1 || y+h*edge>=height-1)continue;
                if(Math.abs(gray(pixels,width,height,x,y-h*edge)-bg)>14 &&
                        Math.abs(gray(pixels,width,height,x,y+h*edge)-bg)>14)return true;
            }
        }
        // Translucent controls can contain a vertical background gradient. Use their
        // clear lower padding and the two side edges instead of requiring equal top/bottom colors.
        for(float padding:new float[]{.55f,.7f,.9f}) {
            float row=y+h*padding;if(row>=height-1)continue;
            float bg=gray(pixels,width,height,x,row);boolean flat=true;
            for(float dx:new float[]{-.45f,-.2f,.2f,.45f})if(Math.abs(gray(pixels,width,height,x+word.width()*dx,row)-bg)>16)flat=false;
            if(!flat)continue;
            for(float distance:new float[]{.8f,1.1f,1.6f,2.3f}) {
                float left=x-word.width()*distance,right=x+word.width()*distance;
                if(left<1 || right>=width-1)continue;
                if(Math.abs(gray(pixels,width,height,left,row)-bg)>12 && Math.abs(gray(pixels,width,height,right,row)-bg)>12)return true;
            }
        }
        return false;
    }
    private static float gray(int[] pixels,int w,int h,float x,float y) {
        int c=pixels[Math.max(0,Math.min(h-1,Math.round(y)))*w+Math.max(0,Math.min(w-1,Math.round(x)))];
        return ((c>>16)&255)*.299f+((c>>8)&255)*.587f+(c&255)*.114f;
    }
}
