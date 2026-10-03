package com.codex.splashskip;

/** Learned button scoring, independent of Android for production-scene regression checks. */
interface AdButtonClassifier {
    float probability(BilibiliVisualMatcher.Frame frame,BilibiliVisualMatcher.Hit candidate);
    default float strokeProbability(BilibiliVisualMatcher.Frame frame,BilibiliVisualMatcher.Hit candidate) { return 0; }
    default java.util.List<BilibiliVisualMatcher.Hit> prioritize(BilibiliVisualMatcher.Frame frame,java.util.List<BilibiliVisualMatcher.Hit> candidates) { return candidates; }
}
