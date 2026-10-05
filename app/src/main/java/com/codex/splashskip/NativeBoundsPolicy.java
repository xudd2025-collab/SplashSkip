package com.codex.splashskip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Pure Java limits and sampling decisions. Used only by the archive writer. */
final class NativeBoundsPolicy {
    static final long RETENTION_MS=7L*24*60*60*1000;
    static final int MAX_FRAMES=64, MAX_TOTAL_BYTES=1536*1024, MAX_FRAME_BYTES=192*1024;
    static final long EARLY_WINDOW_MS=8000, EARLY_INTERVAL_MS=500, LATE_INTERVAL_MS=2000;
    static final int MAX_EARLY_FRAMES=12;
    private static final int MAX_GENERATIONS=128;
    enum Action { IGNORE, APPEND, MERGE }
    static final class Decision {
        final Action action;
        final boolean first;
        Decision(Action action,boolean first){this.action=action;this.first=first;}
    }
    private static final class State {
        long first, sampled, appended;
        int earlyFrames, importance;
        String signature, reason;
    }
    private final LinkedHashMap<String,State> states=new LinkedHashMap<>(16,.75f,true);

    Decision decide(String pkg,long epoch,long uptime,String signature,String reason) {
        return decide(pkg,epoch,uptime,signature,reason,0);
    }
    Decision decide(String pkg,long epoch,long uptime,String signature,String reason,int importance) {
        if(pkg==null || pkg.isEmpty() || uptime<0)return new Decision(Action.IGNORE,false);
        importance=Math.max(0,Math.min(2,importance));
        String key=pkg+'\u0000'+epoch;
        State state=states.get(key);
        if(state==null) {
            state=new State();state.first=state.sampled=state.appended=uptime;
            state.earlyFrames=1;state.importance=importance;state.signature=signature;state.reason=reason;states.put(key,state);
            if(states.size()>MAX_GENERATIONS)states.remove(states.keySet().iterator().next());
            return new Decision(Action.APPEND,true);
        }
        boolean upgrade=importance>state.importance;
        if(uptime<state.sampled || !upgrade && uptime-state.sampled<EARLY_INTERVAL_MS)
            return new Decision(Action.IGNORE,false);
        boolean early=uptime-state.first<EARLY_WINDOW_MS;
        boolean same=equal(state.signature,signature) && equal(state.reason,reason);
        if(!upgrade && same && uptime-state.appended<=LATE_INTERVAL_MS) {
            state.sampled=uptime;return new Decision(Action.MERGE,false);
        }
        if(early) {
            if(state.earlyFrames>=MAX_EARLY_FRAMES)return new Decision(Action.IGNORE,false);
            state.earlyFrames++;
        } else if(!upgrade && (same || uptime-state.appended<LATE_INTERVAL_MS)) {
            return new Decision(Action.IGNORE,false);
        }
        state.sampled=state.appended=uptime;state.signature=signature;state.reason=reason;
        state.importance=Math.max(state.importance,importance);
        return new Decision(Action.APPEND,false);
    }
    void reset(){states.clear();}
    int generations(){return states.size();}
    private static boolean equal(Object a,Object b){return a==null?b==null:a.equals(b);}

    static final class Budget {
        final long wallTime;
        final int bytes;
        Budget(long wallTime,int bytes){this.wallTime=wallTime;this.bytes=bytes;}
    }
    /** Input and returned indices are oldest to newest. Envelope includes empty frames array. */
    static List<Integer> retained(List<Budget> frames,long now,int envelopeBytes) {
        List<Integer> kept=new ArrayList<>();long bytes=Math.max(0,envelopeBytes);
        for(int i=0;i<frames.size();i++) {
            Budget f=frames.get(i);
            if(f==null || f.bytes<0 || f.bytes>MAX_FRAME_BYTES || expired(f.wallTime,now))continue;
            kept.add(i);bytes+=f.bytes;
        }
        bytes+=Math.max(0,kept.size()-1);
        while(!kept.isEmpty() && (kept.size()>MAX_FRAMES || bytes>MAX_TOTAL_BYTES)) {
            int first=kept.remove(0);bytes-=frames.get(first).bytes;
            if(!kept.isEmpty())bytes--;
        }
        return kept;
    }
    static boolean expired(long wallTime,long now) {
        return wallTime<0 || now>=wallTime && now-wallTime>RETENTION_MS;
    }
    static boolean fullTreeComplete(boolean sourceComplete,boolean scopeOnly,int sourceCount,int keptCount) {
        return sourceComplete && !scopeOnly && sourceCount>0 && sourceCount==keptCount;
    }
    /** Only short explicit controls, ad badges and navigation disclosures may be saved as text. */
    static boolean labelAllowed(String text) {
        if(text==null || text.length()>96)return false;
        StringBuilder normalized=new StringBuilder();
        for(char c:text.toLowerCase(Locale.ROOT).toCharArray()) {
            if(Character.isWhitespace(c))continue;
            if(c>='０' && c<='９')c=(char)(c-'０'+'0');
            normalized.append(c);
        }
        String s=normalized.toString();
        if(s.equals("跳过广告") || s.matches("(?:[0-9]{1,2}(?:秒|s)?[|·:]?)?(?:跳过|skip)(?:[|·:]?[0-9]{1,2}(?:秒|s)?)?"))return true;
        if(s.equals("关闭") || s.equals("关闭广告") || s.equals("关闭广告，放大暂停画面") ||
                s.equals("关闭广告,放大暂停画面") || s.equals("广告反馈"))return true;
        if(s.matches("(?:互动|预加载)?广告(?:[|·丨]?(?:有奖|已wifi预加载|wifi预加载|已预加载))?"))return true;
        if(s.equals("翻转手机") || s.equals("摇动手机") || s.equals("摇一摇") || s.equals("摇一摇手机") ||
                s.equals("扭动手机"))return true;
        return s.matches("(?:点击|互动)?跳转(?:至)?详情页(?:面)?或第三方应用[>›]?") ||
                s.matches("(?:点击)?下载或(?:跳转(?:至)?|打开)第三方应用[>›]?");
    }
}
