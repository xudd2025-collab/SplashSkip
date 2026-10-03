package com.codex.splashskip;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** HTTPS download with bounded redirects, retries, range recovery and SHA-256 verification. */
final class UpdateDownload {
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    interface Progress { void update(long bytes,long total,int attempt); }
    private final Connections connections;
    UpdateDownload() { this(url -> (HttpURLConnection)url.openConnection()); }
    UpdateDownload(Connections connections) { this.connections=connections; }
    static boolean allowed(URL url) {
        String host=url.getHost().toLowerCase(Locale.ROOT);
        return url.getProtocol().equals("https") && url.getUserInfo()==null && (url.getPort()==-1 || url.getPort()==443) &&
                (UpdateSources.proxy(url) || host.equals("github.com") || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com") ||
                 (host.equals("api.github.com") && url.getPath().matches("/repos/xudd2025-collab/SplashSkip/releases/assets/[1-9][0-9]*")));
    }
    File download(String[] sources,File partial,long size,String digest,BooleanSupplier cancelled,Progress progress) throws Exception {
        if(sources.length==0)throw new IOException("没有官方安装包地址");
        if(size>=4L*1024*1024 && partial.length()==0) {
            try {return new ParallelUpdateDownload(connections).download(sources,partial,size,digest,cancelled,progress);}
            catch(ParallelUpdateDownload.NoRanges ignored) { }
        }
        return sequential(sources,partial,size,digest,cancelled,progress);
    }
    File download(String source,File partial,long size,String digest,BooleanSupplier cancelled,Progress progress) throws Exception {
        return sequential(new String[]{source},partial,size,digest,cancelled,progress);
    }
    private File sequential(String[] sources,File partial,long size,String digest,BooleanSupplier cancelled,Progress progress) throws Exception {
        if(size<=0 || size>100*1024*1024 || !digest.matches("[a-fA-F0-9]{64}")) throw new IOException("缺少有效的安装包校验信息");
        IOException failure=null;
        int attempts=Math.max(3,sources.length);
        for(int attempt=1;attempt<=attempts;attempt++) {
            if(cancelled.getAsBoolean())throw new InterruptedIOException("下载已取消");
            if(partial.length()==size && sha256(partial).equalsIgnoreCase(digest))return partial;
            if(partial.length()>=size) { try(RandomAccessFile f=new RandomAccessFile(partial,"rw")){f.setLength(0);} }
            long offset=partial.length(); HttpURLConnection connection=null;
            try {
                URL url=new URL(sources[(attempt-1)%sources.length]);
                for(int redirects=0;;redirects++) {
                    if(!allowed(url))throw new IOException("下载地址不属于固定更新线路");
                    connection=connections.open(url); connection.setConnectTimeout(5000); connection.setReadTimeout(8000);
                    connection.setInstanceFollowRedirects(false); connection.setRequestProperty("User-Agent","SplashSkip");
                    connection.setRequestProperty("Accept-Encoding","identity");
                    if(url.getHost().equalsIgnoreCase("api.github.com")) {
                        connection.setRequestProperty("Accept","application/octet-stream");
                        connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
                    }
                    if(offset>0)connection.setRequestProperty("Range","bytes="+offset+"-");
                    int status=connection.getResponseCode();
                    if(status==301 || status==302 || status==303 || status==307 || status==308) {
                        if(redirects>=5)throw new IOException("下载重定向过多");
                        String next=connection.getHeaderField("Location");
                        if(next==null)throw new IOException("缺少下载跳转地址");
                        URL target=new URL(url,next); connection.disconnect(); connection=null; url=target; continue;
                    }
                    if(status!=200 && status!=206)throw new IOException("下载服务器返回 HTTP "+status);
                    if(status==206) {
                        String range=connection.getHeaderField("Content-Range");
                        if(range==null || !range.matches("bytes "+offset+"-[0-9]+/"+size))throw new IOException("续传响应无效");
                    } else offset=0;
                    break;
                }
                progress.update(offset,size,attempt);
                try(InputStream input=connection.getInputStream(); OutputStream output=new FileOutputStream(partial,offset>0)) {
                    byte[] buffer=new byte[131072]; int count; long total=offset,lastProgress=0;
                    while((count=input.read(buffer))!=-1) {
                        if(cancelled.getAsBoolean())throw new InterruptedIOException("下载已取消");
                        total+=count; if(total>size)throw new IOException("安装包大小超出发布信息");
                        output.write(buffer,0,count);
                        if(System.currentTimeMillis()-lastProgress>150) { progress.update(total,size,attempt); lastProgress=System.currentTimeMillis(); }
                    }
                    if(total!=size)throw new EOFException("连接中断，安装包未下载完整");
                }
                if(!sha256(partial).equalsIgnoreCase(digest)) {
                    try(RandomAccessFile f=new RandomAccessFile(partial,"rw")){f.setLength(0);}
                    throw new SecurityException("安装包校验失败，请重新下载");
                }
                progress.update(size,size,attempt); return partial;
            } catch(InterruptedIOException interrupted) {
                if(cancelled.getAsBoolean())throw interrupted;
                failure=interrupted;
            } catch(IOException error) { failure=error; }
            finally { if(connection!=null)connection.disconnect(); }
            if(attempt<attempts && sources.length==1)Thread.sleep(attempt*700L);
        }
        throw failure==null ? new IOException("下载失败") : failure;
    }
    static String sha256(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)) { byte[] buffer=new byte[32768]; int n; while((n=input.read(buffer))!=-1)digest.update(buffer,0,n); }
        StringBuilder result=new StringBuilder(); for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));
        return result.toString();
    }
}
