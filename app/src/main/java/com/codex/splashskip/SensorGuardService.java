package com.codex.splashskip;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Shares the same timed target lock as local ADB so backend changes cannot end a new guard early. */
public final class SensorGuardService extends ISensorGuard.Stub {
    private final LocalSensorBackend backend=new LocalSensorBackend(this::shell);
    public SensorGuardService() { }
    private String shell(String command,int timeout)throws Exception {
        Process process=new ProcessBuilder("sh","-c",command).redirectErrorStream(true).start();
        if(!process.waitFor(timeout,TimeUnit.MILLISECONDS)) {process.destroyForcibly();throw new java.io.IOException("Shell timeout");}
        StringBuilder output=new StringBuilder();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;while((line=reader.readLine())!=null && output.length()<4096) {if(output.length()>0)output.append('\n');output.append(line);}
        }
        return output.toString().trim();
    }
    @Override public synchronized String protect(String pkg,int user) {
        try{return backend.protect(pkg,user);}catch(Exception error){return "error:"+error.getClass().getSimpleName();}
    }
    @Override public synchronized String getState(String pkg,int user) {
        try{return backend.getState(pkg,user);}catch(Exception error){return "unknown";}
    }
    @Override public synchronized void restoreAll() {try{backend.restoreAll();}catch(Exception ignored){}}
    @Override public void destroy(){restoreAll();System.exit(0);}
}