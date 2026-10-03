package com.codex.splashskip;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Activity-independent update task; permission settings and rotation keep the downloaded APK. */
final class ApkUpdater {
    interface Listener { void changed(); }
    private static ApkUpdater instance;
    static synchronized ApkUpdater get(Context c){if(instance==null)instance=new ApkUpdater(c.getApplicationContext());return instance;}
    private final Context context;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile boolean busy,cancelled;
    private volatile String message="";
    private volatile String channel="";
    private volatile int percent;
    private File ready;
    private UpdateChecker.Result result;
    private ApkUpdater(Context c){context=c;}
    void listen(Listener l){listener=l;}
    boolean busy(){return busy;}
    boolean ready(){return ready!=null && ready.isFile();}
    String message(){return message;}
    int percent(){return percent;}
    void cancel(){cancelled=true;}
    Uri uri(){return new Uri.Builder().scheme("content").authority(context.getPackageName()+".updates").appendPath(ready.getName()).build();}
    void start(UpdateChecker.Result update) {
        if(busy)return; result=update; ready=null;busy=true;cancelled=false;percent=0;channel="";message="正在选择更新线路…";changed();
        final boolean accelerate=context.getSharedPreferences("settings",Context.MODE_PRIVATE).getBoolean("update_acceleration",true);
        worker.execute(() -> {
            try {
                if(!update.sha256.matches("[a-f0-9]{64}") || update.apkUrl.isEmpty())throw new IllegalArgumentException("此版本缺少可验证的安装包");
                File dir=new File(context.getCacheDir(),"updates"); if(!dir.isDirectory() && !dir.mkdirs())throw new java.io.IOException("无法创建下载目录");
                String version=update.version.replaceFirst("^[vV]","");
                if(!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IllegalArgumentException("版本格式不正确");
                File partial=new File(dir,version+"-"+update.sha256.substring(0,8)+".part");
                final long started=System.currentTimeMillis();final long[] last={started,-1,0,0};
                new UpdateDownload(url -> {
                    channel=UpdateSources.label(url);message="正在连接 "+channel+"…";changed();
                    return (java.net.HttpURLConnection)url.openConnection();
                }).download(update.sources(accelerate),partial,update.size,update.sha256,() -> cancelled,(bytes,total,attempt) -> {
                    synchronized(last) {
                        long now=System.currentTimeMillis();
                        if(last[1]<0){last[1]=bytes;last[0]=now;}
                        if(now-last[0]>=500){last[2]=Math.max(0,(bytes-last[1])*1000/Math.max(1,now-last[0]));last[0]=now;last[1]=bytes;}
                        percent=Math.max(percent,(int)(bytes*100/total));
                        String speed=last[2]>=1024*1024?String.format(java.util.Locale.ROOT,"%.1f MB/s",last[2]/(1024f*1024)):String.format(java.util.Locale.ROOT,"%.0f KB/s",last[2]/1024f);
                        message=channel+" · "+(attempt>1?"重试 "+attempt+" · ":"")+"下载中 "+percent+"% · "+speed;
                        if(now-last[3]>=150 || bytes==total){last[3]=now;changed();}
                    }
                });
                if(cancelled)throw new java.io.InterruptedIOException("下载已取消");
                validate(context,partial,version);
                File file=new File(dir,"SplashSkip-v"+version+"-"+update.sha256.substring(0,8)+".apk");
                if(file.exists() && !file.delete())throw new java.io.IOException("无法替换缓存安装包");
                if(!partial.renameTo(file))throw new java.io.IOException("无法保存安装包");
                ready=file;percent=100;message="下载完成，大小、SHA-256、包名和签名已验证";
            } catch(Exception error) {
                message=cancelled?"下载已取消，可重新下载":
                        error instanceof SecurityException || error instanceof IllegalArgumentException ? error.getMessage():
                        "下载未完成，已保留续传数据。请检查网络，或开启“更新加速”后点重试。";
                Diagnostics.append(context,"update download "+error.getClass().getSimpleName());
            } finally {busy=false;changed();}
        });
    }
    void retry(){if(result!=null)start(result);}
    private void changed(){handler.post(() -> {if(listener!=null)listener.changed();});}
    @SuppressWarnings("deprecation") static void validate(Context c,File file,String version)throws Exception {
        PackageManager pm=c.getPackageManager(); int flags=android.os.Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags),current=pm.getPackageInfo(c.getPackageName(),flags);
        if(archive==null || !c.getPackageName().equals(archive.packageName) || !version.equals(archive.versionName) || archive.versionCode<current.versionCode)
            throw new SecurityException("安装包的应用、版本不匹配或版本过旧，已停止安装");
        Signature[] old=android.os.Build.VERSION.SDK_INT>=28?current.signingInfo.getApkContentsSigners():current.signatures;
        Signature[] next=android.os.Build.VERSION.SDK_INT>=28?archive.signingInfo.getApkContentsSigners():archive.signatures;
        if(old==null || next==null || old.length!=next.length)throw new SecurityException("安装包签名不一致，已停止安装");
        for(Signature signature:old) {boolean found=false;for(Signature candidate:next)if(Arrays.equals(signature.toByteArray(),candidate.toByteArray()))found=true;
            if(!found)throw new SecurityException("安装包签名不一致，已停止安装");}
    }
}
