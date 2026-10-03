package com.codex.splashskip;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
public final class UpdateDownloadCheck {
    static class Response extends HttpURLConnection {
        final InputStream input;final int status;final String range;
        Response(URL url,InputStream input,int status,String range){super(url);this.input=input;this.status=status;this.range=range;}
        public int getResponseCode(){return status;} public InputStream getInputStream()throws IOException{return input;}
        public String getHeaderField(String name){return "Content-Range".equals(name)?range:null;}
        public void disconnect(){} public boolean usingProxy(){return false;} public void connect(){}
    }
    public static void main(String[] args)throws Exception {
        File dir=Files.createTempDirectory("splashskip-update-check").toFile();
        byte[] data="verified-apk-data".getBytes("UTF-8");File original=new File(dir,"original");Files.write(original.toPath(),data);
        String digest=UpdateDownload.sha256(original),url="https://github.com/xudd2025-collab/SplashSkip/releases/download/v1.0.0/SplashSkip-v1.0.0.apk";
        AtomicInteger calls=new AtomicInteger();
        UpdateDownload retry=new UpdateDownload(u -> {
            if(calls.incrementAndGet()==1)return new Response(u,new InputStream(){int at; public int read()throws IOException{if(at>=5)throw new IOException("disconnected");return data[at++];}
                public int read(byte[] b,int off,int len)throws IOException{if(at>=5)throw new IOException("disconnected");int n=Math.min(len,5-at);System.arraycopy(data,at,b,off,n);at+=n;return n;}},200,null);
            return new Response(u,new ByteArrayInputStream(java.util.Arrays.copyOfRange(data,5,data.length)),206,"bytes 5-"+(data.length-1)+"/"+data.length);
        });
        File partial=new File(dir,"retry");retry.download(url,partial,data.length,digest,()->false,(b,t,a)->{});
        if(calls.get()!=2 || !UpdateDownload.sha256(partial).equals(digest))throw new AssertionError("resume failed");
        System.out.println("PASS interrupted download resumes and SHA-256 agrees");
        UpdateDownload corrupt=new UpdateDownload(u -> new Response(u,new ByteArrayInputStream(new byte[data.length]),200,null));
        try{corrupt.download(url,new File(dir,"bad"),data.length,digest,()->false,(b,t,a)->{});throw new AssertionError("bad digest accepted");}
        catch(SecurityException expected){System.out.println("PASS corrupt APK rejected");}
        if(UpdateDownload.allowed(new URL("http://github.com/a")) || UpdateDownload.allowed(new URL("https://evil.test/a")) || UpdateDownload.allowed(new URL("https://github.com@evil.test/a")))throw new AssertionError("untrusted URL accepted");
        System.out.println("PASS untrusted hosts, cleartext and userinfo rejected");
        String api="https://api.github.com/repos/xudd2025-collab/SplashSkip/releases/assets/123";
        String[] accelerated=UpdateSources.packages(api,url,true),direct=UpdateSources.packages(api,url,false);
        if(accelerated.length!=4 || direct.length!=2 || !direct[0].equals(api) || !direct[1].equals(url))throw new AssertionError("route preference failed");
        for(String source:accelerated)if(!UpdateDownload.allowed(new URL(source)))throw new AssertionError("configured route rejected");
        String[] unsafe={"https://gh-proxy.com/https://github.com/other/project/releases/download/v1.0.0/SplashSkip.apk",
            "https://gh-proxy.com/https://github.com/xudd2025-collab/SplashSkip/releases/download/v1.0.0/../../SplashSkip.apk",
            "https://gh-proxy.com/https://evil.test/SplashSkip.apk",accelerated[0]+"?url=evil",accelerated[0]+"#fragment",
            accelerated[0].replace("gh-proxy.com/","gh-proxy.com:8443/"),accelerated[0].replace("gh-proxy.com/","gh-proxy.com@evil.test/")};
        for(String source:unsafe)if(UpdateDownload.allowed(new URL(source)))throw new AssertionError("untrusted proxy URL accepted: "+source);
        if(UpdateSources.metadata(true).length!=2 || UpdateSources.metadata(false).length!=1)throw new AssertionError("metadata routes failed");
        System.out.println("PASS acceleration can be disabled and proxy routes are restricted to this APK repository");
        AtomicInteger routesTried=new AtomicInteger();
        UpdateDownload lastRoute=new UpdateDownload(u -> {
            routesTried.incrementAndGet();if(!u.toString().equals(url))throw new IOException("route offline");
            return new Response(u,new ByteArrayInputStream(data),200,null);
        });
        File directFile=new File(dir,"direct-fallback");lastRoute.download(accelerated,directFile,data.length,digest,()->false,(b,t,a)->{});
        if(routesTried.get()!=4 || !UpdateDownload.sha256(directFile).equals(digest))throw new AssertionError("last official route never tried");
        System.out.println("PASS both proxies and API can fail while the final official route still succeeds");
        try{retry.download(url,new File(dir,"cancel"),data.length,digest,()->true,(b,t,a)->{});throw new AssertionError("cancel ignored");}
        catch(InterruptedIOException expected){System.out.println("PASS cancellation stops transfer");}
        byte[] large=new byte[4*1024*1024+17];for(int i=0;i<large.length;i++)large[i]=(byte)(i*31+i/97);
        File largeOriginal=new File(dir,"large-original");Files.write(largeOriginal.toPath(),large);String largeDigest=UpdateDownload.sha256(largeOriginal);
        CountDownLatch readers=new CountDownLatch(4);AtomicInteger rangeCalls=new AtomicInteger();
        UpdateDownload ranged=new UpdateDownload(u -> new Response(u,null,206,null) {
            private long[] bounds(){String r=getRequestProperty("Range").substring(6);String[] p=r.split("-");return new long[]{Long.parseLong(p[0]),Long.parseLong(p[1])};}
            public String getHeaderField(String name){long[] b=bounds();return "Content-Range".equals(name)?"bytes "+b[0]+"-"+b[1]+"/"+large.length:null;}
            public InputStream getInputStream()throws IOException {
                long[] b=bounds();rangeCalls.incrementAndGet();readers.countDown();
                try{if(!readers.await(2,TimeUnit.SECONDS))throw new IOException("ranges were not concurrent");}catch(InterruptedException e){throw new IOException(e);}
                return new ByteArrayInputStream(java.util.Arrays.copyOfRange(large,(int)b[0],(int)b[1]+1));
            }
        });
        File parallelFile=new File(dir,"parallel");ranged.download(new String[]{url},parallelFile,large.length,largeDigest,()->false,(b,t,a)->{});
        if(rangeCalls.get()!=4 || !UpdateDownload.sha256(parallelFile).equals(largeDigest))throw new AssertionError("parallel ranges failed");
        System.out.println("PASS four ranges run concurrently and whole-file SHA-256 agrees");
        AtomicInteger attempts=new AtomicInteger();
        UpdateDownload fallback=new UpdateDownload(u -> {
            if(u.getHost().equals("api.github.com"))throw new IOException("API unavailable");
            attempts.incrementAndGet();return new Response(u,new ByteArrayInputStream(large),200,null);
        });
        File fallbackFile=new File(dir,"fallback");fallback.download(new String[]{"https://api.github.com/repos/xudd2025-collab/SplashSkip/releases/assets/123",url},fallbackFile,large.length,largeDigest,()->false,(b,t,a)->{});
        if(!UpdateDownload.sha256(fallbackFile).equals(largeDigest))throw new AssertionError("single transfer fallback failed");
        System.out.println("PASS unavailable API and no-Range server fall back to verified single transfer");
        if(UpdateDownload.allowed(new URL("https://api.github.com/user")) || UpdateDownload.allowed(new URL("https://api.github.com/repos/other/project/releases/assets/123")))throw new AssertionError("unrelated API allowed");
        for(File file:dir.listFiles())file.delete();dir.delete();
    }
}
