package com.codex.splashskip;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Only the verified update APK directory can be granted to the system installer. */
public final class UpdateApkProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File file(Uri uri) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(uri.getPathSegments().size()!=1 || name==null || !name.matches("SplashSkip-v[0-9]+\\.[0-9]+\\.[0-9]+-[a-f0-9]{8}\\.apk"))throw new FileNotFoundException();
        File directory=new File(getContext().getCacheDir(),"updates"),file=new File(directory,name);
        try { if(!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()) || !file.isFile())throw new FileNotFoundException(); }
        catch(java.io.IOException error){throw new FileNotFoundException();}
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri){return "application/vnd.android.package-archive";}
    @Override public Cursor query(Uri uri,String[] columns,String selection,String[] args,String sort) {
        try {
            File file=file(uri); String[] names=columns==null ? new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:columns;
            MatrixCursor cursor=new MatrixCursor(names); Object[] values=new Object[names.length];
            for(int i=0;i<names.length;i++)values[i]=OpenableColumns.DISPLAY_NAME.equals(names[i])?file.getName():OpenableColumns.SIZE.equals(names[i])?file.length():null;
            cursor.addRow(values);return cursor;
        } catch(FileNotFoundException error){return null;}
    }
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
