package com.codex.splashskip;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Four bounded ranges, reusable local segments, and a final whole-file digest check. */
final class ParallelUpdateDownload {
    static final class NoRanges extends IOException { }
    private final UpdateDownload.Connections connections;
    ParallelUpdateDownload(UpdateDownload.Connections c){connections=c;}
    private HttpURLConnection open(String source,long start,long end)throws IOException {
        URL url=new URL(source);HttpURLConnection c=null;
        try {
            for(int redirects=0;;redirects++) {
                if(!UpdateDownload.allowed(url))throw new IOException("下载地址不属于固定更新线路");
                c=connections.open(url);c.setConnectTimeout(5000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent","SplashSkip");c.setRequestProperty("Accept-Encoding","identity");
                c.setRequestProperty("Range","bytes="+start+"-"+end);
                if(url.getHost().equalsIgnoreCase("api.github.com")) {
                    c.setRequestProperty("Accept","application/octet-stream");c.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
                }
                int status=c.getResponseCode();
                if(status==301 || status==302 || status==303 || status==307 || status==308) {
                    String location=c.getHeaderField("Location");
                    if(redirects>=5 || location==null)throw new IOException("下载跳转无效");
                    URL next=new URL(url,location);c.disconnect();c=null;url=next;continue;
                }
                return c;
            }
        } catch(IOException | RuntimeException e){if(c!=null)c.disconnect();throw e;}
    }
    File download(String[] sources,File output,long size,String digest,BooleanSupplier cancelled,UpdateDownload.Progress progress)throws Exception {
        if(size<=0 || size>100L*1024*1024 || !digest.matches("[a-fA-F0-9]{64}"))throw new IOException("安装包校验信息无效");
        String direct=null;
        for(String source:sources) {
            if(cancelled.getAsBoolean())throw new InterruptedIOException("下载已取消");
            HttpURLConnection probe=null;
            try {
                probe=open(source,0,0);
                if(probe.getResponseCode()==206 && ("bytes 0-0/"+size).equals(probe.getHeaderField("Content-Range"))) {
                    direct=probe.getURL().toString();break;
                }
            } catch(IOException ignored) { }
            finally {if(probe!=null)probe.disconnect();}
        }
        if(direct==null)throw new NoRanges();
        final String resolved=direct;
        Set<String> unique=new LinkedHashSet<>();unique.add(resolved);Collections.addAll(unique,sources);
        final String[] retries=unique.toArray(new String[0]);
        File[] parts=new File[4];long[] starts=new long[4],ends=new long[4];AtomicLong done=new AtomicLong();
        for(int i=0;i<4;i++) {
            starts[i]=size*i/4;ends[i]=size*(i+1)/4-1;parts[i]=new File(output.getPath()+".segment"+i);
            if(parts[i].length()>ends[i]-starts[i]+1)try(RandomAccessFile f=new RandomAccessFile(parts[i],"rw")){f.setLength(0);}
            done.addAndGet(parts[i].length());
        }
        progress.update(done.get(),size,1);
        AtomicBoolean failed=new AtomicBoolean();ExecutorService pool=Executors.newFixedThreadPool(4);
        Set<HttpURLConnection> active=ConcurrentHashMap.newKeySet();List<Future<?>> jobs=new ArrayList<>();
        try {
            for(int i=0;i<4;i++) {
                final int index=i;
                jobs.add(pool.submit(() -> {
                    IOException failure=null;
                    for(int attempt=1;attempt<=Math.max(3,retries.length);attempt++) {
                        if(cancelled.getAsBoolean() || failed.get())throw new InterruptedIOException("下载已取消");
                        long saved=parts[index].length(),expected=ends[index]-starts[index]+1;
                        if(saved==expected)return null;
                        HttpURLConnection c=null;
                        try {
                            String source=retries[(attempt-1)%retries.length];
                            c=open(source,starts[index]+saved,ends[index]);active.add(c);
                            if(c.getResponseCode()!=206 || !("bytes "+(starts[index]+saved)+"-"+ends[index]+"/"+size).equals(c.getHeaderField("Content-Range")))
                                throw new IOException("分段下载响应无效");
                            try(InputStream input=c.getInputStream();OutputStream file=new FileOutputStream(parts[index],true)) {
                                byte[] buffer=new byte[131072];int n;long at=saved,lastProgress=0;
                                while((n=input.read(buffer))!=-1) {
                                    if(cancelled.getAsBoolean() || failed.get())throw new InterruptedIOException("下载已取消");
                                    if(at+n>expected)throw new IOException("分段大小超出发布信息");
                                    file.write(buffer,0,n);at+=n;long total=done.addAndGet(n);
                                    if(System.currentTimeMillis()-lastProgress>150) {progress.update(total,size,attempt);lastProgress=System.currentTimeMillis();}
                                }
                                if(at!=expected)throw new EOFException("分段连接中断");
                            }
                            return null;
                        } catch(IOException e) {failure=e;if(cancelled.getAsBoolean() || failed.get())throw e;}
                        finally {if(c!=null){active.remove(c);c.disconnect();}}
                    }
                    throw failure;
                }));
            }
            for(Future<?> job:jobs) {
                try {job.get();}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IOException(cause);}
            }
            if(cancelled.getAsBoolean())throw new InterruptedIOException("下载已取消");
            try(OutputStream file=new FileOutputStream(output)) {
                byte[] buffer=new byte[131072];
                for(File part:parts)try(InputStream input=new FileInputStream(part)){int n;while((n=input.read(buffer))!=-1){if(cancelled.getAsBoolean())throw new InterruptedIOException("下载已取消");file.write(buffer,0,n);}}
            }
            if(output.length()!=size || !UpdateDownload.sha256(output).equalsIgnoreCase(digest)) {
                output.delete();for(File part:parts)part.delete();throw new SecurityException("安装包校验失败，请重新下载");
            }
            for(File part:parts)part.delete();progress.update(size,size,1);return output;
        } finally {
            failed.set(true);for(HttpURLConnection c:active)c.disconnect();for(Future<?> job:jobs)job.cancel(true);pool.shutdownNow();
            // Avoid concurrent writers when the user starts another retry.
            if(!pool.awaitTermination(12,TimeUnit.SECONDS))throw new IOException("下载线程尚未结束，请稍后重试");
        }
    }
}
