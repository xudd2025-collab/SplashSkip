package com.codex.splashskip;

import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Package-independent splash detection. A real skip glyph, ad label and learned identity are required. */
final class UniversalSplashMatcher {
    static final String SKIP="universal-splash-skip";
    static final float THRESHOLD=.95f;
    private final Template[] skips, marks;
    private ControlTextMatcher.Model labelModel;
    UniversalSplashMatcher withLabelModel(ControlTextMatcher.Model model){labelModel=model;return this;}
    private Template[] glyphs=new Template[0];
    private Template[] adGlyphs=new Template[0];
    private Template[] prompts=new Template[0];
    private Template[] textMarks=new Template[0];
    UniversalSplashMatcher withTextMarks(Template[] words) {textMarks=words;return this;}
    private SkipFeatureBank featureBank;
    UniversalSplashMatcher withFeatures(Template[] words) {featureBank=new SkipFeatureBank(words);return this;}
    UniversalSplashMatcher(Template[] skips,Template[] marks) { this.skips=skips;this.marks=marks; }
    UniversalSplashMatcher withGlyphs(Template[] words) { glyphs=words;return this; }
    UniversalSplashMatcher withAdGlyphs(Template[] words) { adGlyphs=words;return this; }
    UniversalSplashMatcher withPrompts(Template[] words) { prompts=words;return this; }
    Hit findLaunching(Frame f,AdButtonClassifier classifier,String rule,java.util.function.BooleanSupplier cancelled) {
        return find(f,classifier,rule,cancelled,1,true);
    }
    Hit find(Frame f,AdButtonClassifier classifier,String rule) {
        return find(f,classifier,rule,()->false);
    }
    Hit find(Frame f,AdButtonClassifier classifier,String rule,java.util.function.BooleanSupplier cancelled) {
        return find(f,classifier,rule,cancelled,4);
    }
    Hit find(Frame f,AdButtonClassifier classifier,String rule,java.util.function.BooleanSupplier cancelled,int maxRegions) {
        return find(f,classifier,rule,cancelled,maxRegions,false);
    }
    private Hit find(Frame f,AdButtonClassifier classifier,String rule,java.util.function.BooleanSupplier cancelled,int maxRegions,boolean launchContext) {
        if(classifier==null || f.height<f.width*.35f || f.height>f.width*2.8f)return null;
        float cropScale=1f;
        if(f.originalWidth>f.originalHeight) {
            cropScale=f.originalHeight/(float)f.originalWidth;
            f=new Frame(f.pixels,f.originalWidth,f.originalHeight,Math.round(608/cropScale));
        }
        // The header is a priority, not an allowed-coordinate list. Then
        // search every screen tile for actual skip text and independent ad cues.
        java.util.List<float[]> regions=new java.util.ArrayList<>();
        regions.add(new float[]{.01f,.02f,.99f,.18f});
        for(int row=0;row<4;row++)for(int col=0;col<3;col++)
            regions.add(new float[]{Math.max(.01f,col/3f-.06f),Math.max(.01f,row/4f-.02f),Math.min(.99f,(col+1)/3f+.06f),Math.min(.99f,(row+1)/4f+.02f)});
        float[] mark=null;
        boolean searchedMark=false;
        Frame strokesFrame=null;
        java.util.List<java.util.List<Hit>> groups=new java.util.ArrayList<>();
        for(float[] region:regions) {
            if(cancelled.getAsBoolean())return null;
            java.util.List<Hit> candidates=contrast(f,region)?classifier.prioritize(f,AdGlyphProposals.find(f,rule,region,cropScale)):java.util.Collections.emptyList();
            groups.add(candidates);
            if(featureBank!=null) {
                for(Hit candidate:candidates) {
                    if(cancelled.getAsBoolean())return null;
                    float raw=classifier.probability(f,candidate),p=raw;
                    if(p<.98f)p=Math.max(p,classifier.strokeProbability(f,candidate));
                    if(p<.70f)continue;
                    Hit verified=featureBank.verify(f,candidate);
                    if(verified==null)continue;
                    // Shape refinement can move a point by a pixel; classify that final crop too.
                    p=classifier.probability(f,verified);
                    if(p<.98f) {
                        Hit centered=AdGlyphProposals.refineModel(f,verified,classifier,p);
                        // This is only a crop adjustment inside the independently verified word.
                        if(centered.modelProbability>p){verified=centered;p=centered.modelProbability;}
                    }
                    if(p<.98f) {
                        Hit centered=AdGlyphProposals.refineModel(f,candidate,classifier,raw);
                        if(centered.modelProbability>p) {
                            verified=new Hit(verified.rule,centered.x,centered.y,verified.score,verified.frameWidth,verified.frameHeight)
                                    .withCropScale(centered.cropScale).withMemory(verified.memoryMatch);
                            p=centered.modelProbability;
                        }
                    }
                    if(p<.98f)p=Math.max(p,classifier.strokeProbability(f,verified));
                    if(p<.98f)continue;
                    if(launchContext && SplashPromptVerifier.find(f,prompts,cancelled)!=null)return verified.withModel(p);
                    if(!searchedMark){mark=findMark(f,verified.y*f.width/(float)f.originalWidth,cancelled);searchedMark=true;}
                    if(mark!=null)return verified.withModel(p);
                }
            }
        }
        // Finish cheap feature searches across the screen before expensive font refinement.
        for(java.util.List<Hit> candidates:groups) {
            for(Hit proposal:candidates) {
                if(cancelled.getAsBoolean())return null;
                float probability=classifier.probability(f,proposal);
                float glyphScale=proposal.cropScale/cropScale;
                boolean inspectStrokes=probability<.85f;
                if(inspectStrokes && strokesFrame==null)strokesFrame=new Frame(f.pixels,f.originalWidth,f.originalHeight,1216);
                boolean strokeVerified=inspectStrokes && SkipGlyphVerifier.strokesAccepts(strokesFrame,proposal,glyphs);
                if(strokeVerified)
                    probability=Math.max(probability,classifier.strokeProbability(f,proposal));
                if(strokeVerified && probability<.98f)continue;
                if(probability>=.7f && probability<.98f && SkipGlyphVerifier.accepts(f,proposal,glyphs,glyphScale,.82f)) {
                    proposal=AdGlyphProposals.refineModel(f,proposal,classifier,probability);probability=proposal.modelProbability;
                }
                if(probability<.85f)continue;
                if(probability<.98f) {
                    if(!SkipGlyphVerifier.accepts(f,proposal,glyphs,glyphScale,.82f))continue;
                } else if(!strokeVerified && !SkipGlyphVerifier.accepts(f,proposal,skips,glyphScale,.55f) &&
                        !SkipGlyphVerifier.accepts(f,proposal,glyphs,glyphScale,.68f))continue;
                if(launchContext && probability>=.98f) {
                    float[] prompt=SplashPromptVerifier.find(f,prompts,cancelled);
                    if(prompt!=null)return proposal.withModel(probability);
                }
                if(!searchedMark) { mark=findMark(f,proposal.y*f.width/(float)f.originalWidth,cancelled);searchedMark=true; }
                if(mark==null)return null;
                return f.hit(rule,proposal.x*f.width/(float)f.originalWidth,proposal.y*f.height/(float)f.originalHeight,mark[2])
                        .withCropScale(proposal.cropScale).withModel(probability).withMemory(proposal.memoryMatch);
            }
        }
        for(float[] region:regions) {
            if(cancelled.getAsBoolean())return null;
            if(!contrast(f,region))continue;
            for(float scale:new float[]{1f,.8f,1.2f})for(Template source:skips) {
            Template t=source.scaled(scale);
            for(int y=(int)(f.height*region[1]);y<f.height*region[3];y+=3) {
                if(cancelled.getAsBoolean())return null;
                for(int x=(int)(f.width*region[0]);x<f.width*region[2];x+=3) {
                    if(correlate(f,t,x,y)<.35f)continue;
                    float[] button=refine(f,t,x,y,2);
                    if(button[2]<.75f)continue;
                    if(!searchedMark) { mark=findMark(f,button[1],cancelled);searchedMark=true; }
                    if(mark==null)return null;
                    Hit candidate=f.hit(rule,button[0],button[1],Math.min(button[2],mark[2])).withCropScale(scale*cropScale);
                    float probability=classifier.probability(f,candidate);
                    // A textured background can lower correlation despite an actual skip glyph.
                    // Those candidates require stricter learned identity than clear template matches.
                    float required=button[2]>=.88f?THRESHOLD:.98f;
                    if(probability>=required)return candidate.withModel(probability);
                }
            }
            }
        }
        return null;
    }
    private boolean contrast(Frame f,float[] region) {
        float min=255,max=0;
        for(int y=(int)(f.height*region[1]);y<f.height*region[3];y+=2)
            for(int x=(int)(f.width*region[0]);x<f.width*region[2];x+=2) {
                float value=grayAt(f,x,y);min=Math.min(min,value);max=Math.max(max,value);
                if(max-min>=24)return true;
            }
        return false;
    }
    private float[] findMark(Frame f,float skipY,java.util.function.BooleanSupplier cancelled) {
        for(Template word:textMarks) {
            if(cancelled.getAsBoolean())return null;
            float[] mark=UiFeatureSearch.first(f,word,0,0,f.width,f.height,.80f,
                    p -> UiFeatureSearch.part(f,word,p[0],p[1],0,0,word.columns/3,word.rows)>=.70f &&
                            SplashPromptVerifier.half(f,word,p[0],p[1],0)>=.70f && SplashPromptVerifier.half(f,word,p[0],p[1],1)>=.70f);
            if(mark!=null)return mark;
        }
        float[] generic=AdLabelVerifier.header(f,skipY,adGlyphs,cancelled,labelModel);
        if(generic!=null)return generic;
        for(Template source:marks)for(float scale:new float[]{1f,1.1f,.9f,.8f,1.2f}) {
            if(cancelled.getAsBoolean())return null;
            float[] mark=UiFeatureSearch.find(f,source.scaled(scale),0,0,f.width,f.height,.84f);
            if(mark!=null)return mark;
        }
        return null;
    }
}
