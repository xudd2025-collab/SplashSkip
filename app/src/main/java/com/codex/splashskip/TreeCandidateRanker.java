package com.codex.splashskip;

import java.io.*;
import java.util.*;

/** Candidate identity ranking, separate from learned click outcomes. Never proposes coordinates. */
final class TreeCandidateRanker {
    private final JointControlModel model;
    private final Set<String> scope;
    private TreeCandidateRanker(JointControlModel model,Set<String> scope){this.model=model;this.scope=scope;}
    static TreeCandidateRanker read(InputStream stream)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buf=new byte[1024];int n;
        while((n=stream.read(buf))!=-1){bytes.write(buf,0,n);if(bytes.size()>16384)throw new IOException("Oversize ranker");}
        Properties p=new Properties();p.load(new ByteArrayInputStream(bytes.toByteArray()));
        if(!"control_candidate".equals(p.getProperty("objective")) || !"10,11".equals(p.getProperty("masked")))
            throw new IOException("Wrong ranking objective");
        Set<String> scope=new HashSet<>();
        for(String pkg:p.getProperty("scope","").split(",")){
            if(!pkg.matches("[A-Za-z][\\w]*(?:\\.[\\w]+)+"))throw new IOException("Invalid ranking scope");
            scope.add(pkg);
        }
        return new TreeCandidateRanker(JointControlModel.read(new ByteArrayInputStream(bytes.toByteArray())),scope);
    }
    float score(String pkg,float[] features){
        if(!scope.contains(pkg) || !JointControlModel.valid(features))return 0;
        float[] f=features.clone();f[10]=f[11]=0;
        return model.score(f);
    }
}
