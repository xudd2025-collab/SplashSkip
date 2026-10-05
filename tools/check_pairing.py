"""Check actual pairing session/NSD sources with deterministic Android callback stubs."""
from pathlib import Path
import argparse, subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--java-home', required=True, type=Path)
args = parser.parse_args()
out = root / '.build/local-adb-check'
out.mkdir(parents=True, exist_ok=True)
stubs = {
    'android/content/Context.java': '''package android.content;
public class Context { public static final String NSD_SERVICE="servicediscovery";
 private final Object manager; public Context(Object value){manager=value;}
 public Object getSystemService(String name){return manager;} }''',
    'android/os/Build.java': '''package android.os;
public class Build { public static class VERSION_CODES { public static final int JELLY_BEAN=16; } }''',
    'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }',
    'android/os/Handler.java': '''package android.os;
import java.util.*;
public class Handler { public static final List<Runnable> pending=new ArrayList<>(); public Handler(Looper l){}
 public boolean postDelayed(Runnable r,long d){pending.add(r);return true;}
 public static void runNext(){pending.remove(0).run();} }''',
    'android/net/nsd/NsdServiceInfo.java': '''package android.net.nsd;
import java.net.InetAddress;
public class NsdServiceInfo { private final String name; private final InetAddress host; private final int port;
 public NsdServiceInfo(String n,InetAddress h,int p){name=n;host=h;port=p;}
 public String getServiceName(){return name;} public InetAddress getHost(){return host;} public int getPort(){return port;} }''',
    'android/net/nsd/NsdManager.java': '''package android.net.nsd;
import java.util.*;
public class NsdManager { public static final int PROTOCOL_DNS_SD=1, FAILURE_ALREADY_ACTIVE=3; public DiscoveryListener discovery;
 public final List<ResolveListener> resolves=new ArrayList<>(); public int stopCalls;
 public void discoverServices(String type,int protocol,DiscoveryListener listener){discovery=listener;}
 public void stopServiceDiscovery(DiscoveryListener listener){++stopCalls;}
 public void resolveService(NsdServiceInfo info,ResolveListener listener){resolves.add(listener);}
 public interface DiscoveryListener { void onDiscoveryStarted(String t); void onStartDiscoveryFailed(String t,int c);
 void onDiscoveryStopped(String t); void onStopDiscoveryFailed(String t,int c);
 void onServiceFound(NsdServiceInfo i); void onServiceLost(NsdServiceInfo i); }
 public interface ResolveListener { void onResolveFailed(NsdServiceInfo i,int c); void onServiceResolved(NsdServiceInfo i); } }''',
    'io/github/muntashirakon/adb/android/AndroidUtils.java': '''package io.github.muntashirakon.adb.android;
import android.content.Context; import java.net.*;
public class AndroidUtils { public static InetAddress getHostIpAddress(Context c) {
 try{return InetAddress.getByName("127.0.0.1");}catch(Exception e){throw new AssertionError(e);} } }''',
}
for name in ('NonNull', 'Nullable'):
    stubs['androidx/annotation/' + name + '.java'] = 'package androidx.annotation; public @interface ' + name + ' {}'
stubs['androidx/annotation/RequiresApi.java'] = 'package androidx.annotation; public @interface RequiresApi { int value(); }'
stubs['androidx/annotation/StringDef.java'] = 'package androidx.annotation; public @interface StringDef { String[] value(); }'
files = []
for name, content in stubs.items():
    path = out / 'stubs' / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')
    files.append(path)
files.extend([
    root / 'third_party/local-adb/src/io/github/muntashirakon/adb/android/AdbMdns.java',
    root / 'app/src/main/java/com/codex/splashskip/PairingSession.java',
    root / 'tools/PairingSessionCheck.java',
    root / 'tools/local-adb-check/MdnsLifecycleCheck.java',
])
classes = out / 'classes'
classes.mkdir(exist_ok=True)
subprocess.run([str(args.java_home / 'bin/javac.exe'), '-encoding', 'UTF-8', '-d', str(classes),
                *map(str, files)], check=True)
for name in ('PairingSessionCheck', 'MdnsLifecycleCheck'):
    subprocess.run([str(args.java_home / 'bin/java.exe'), '-cp', str(classes), 'com.codex.splashskip.' + name], check=True)
