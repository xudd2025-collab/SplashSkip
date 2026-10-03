package com.codex.splashskip;
import android.content.Context;
import android.util.AtomicFile;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/** Private, bounded, local-only feature store. No full screenshot or ad creative is saved. */
final class RecentButtonMemory {
    private static RecentButtonMemory instance;
    static synchronized RecentButtonMemory get(Context c) {if(instance==null)instance=new RecentButtonMemory(c.getApplicationContext());return instance;}
    final ButtonFeatureMemory bank=new ButtonFeatureMemory();
    private final Context context;private final AtomicFile file;
    private RecentButtonMemory(Context c) {
        context=c;file=new AtomicFile(new File(c.getFilesDir(),"learned-buttons.bin"));
        try(DataInputStream in=new DataInputStream(file.openRead())){bank.read(in);}
        catch(java.io.FileNotFoundException empty) { }
        catch(Exception invalid) {bank.clear();Diagnostics.append(c,"button feature memory reset: "+invalid.getClass().getSimpleName());}
        updateCount();
    }
    synchronized void remember(String pkg,BilibiliVisualMatcher.Hit hit) {
        bank.remember(pkg,hit,System.currentTimeMillis());save();
    }
    synchronized void clear() {bank.clear();save();}
    private void save() {
        FileOutputStream stream=null;
        try {stream=file.startWrite();DataOutputStream out=new DataOutputStream(stream);bank.write(out);out.flush();file.finishWrite(stream);updateCount();}
        catch(Exception error){if(stream!=null)file.failWrite(stream);Diagnostics.append(context,"button feature memory save failed");}
    }
    private void updateCount(){context.getSharedPreferences("settings",0).edit().putInt("learned_button_count",bank.size(System.currentTimeMillis())).apply();}
}
