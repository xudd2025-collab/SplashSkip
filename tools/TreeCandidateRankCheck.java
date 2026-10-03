package com.codex.splashskip;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class TreeCandidateRankCheck {
    public static void main(String[] args)throws Exception {
        byte[] payload=Files.readAllBytes(Paths.get(args[0]));
        TreeCandidateRanker m=TreeCandidateRanker.read(new ByteArrayInputStream(payload));
        float[] f=new float[76];Arrays.fill(f,.3f);f[0]=1;
        float score=m.score("com.qiyi.video",f);
        if(score<=0 || score>=1)throw new AssertionError("valid bounded score");
        f[10]=1;f[11]=1;
        if(m.score("com.qiyi.video",f)!=score)throw new AssertionError("semantic label leakage");
        if(f[10]!=1 || f[11]!=1)throw new AssertionError("mutated caller features");
        if(m.score("another.app",f)!=0 || m.score("com.qiyi.video",null)!=0)throw new AssertionError("scope and validity guard");
        String wrong=new String(payload,java.nio.charset.StandardCharsets.US_ASCII).replace("control_candidate","click_outcome");
        boolean rejected=false;try{TreeCandidateRanker.read(new ByteArrayInputStream(wrong.getBytes("US-ASCII")));}catch(IOException expected){rejected=true;}
        if(!rejected)throw new AssertionError("objective confusion");
        f[0]=Float.NaN;if(m.score("com.qiyi.video",f)!=0)throw new AssertionError("invalid value");
        System.out.println("PASS candidate identity scope, masking, immutability, objective, validity");
    }
}
