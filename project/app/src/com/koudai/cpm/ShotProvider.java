package com.koudai.cpm;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 FileProvider：只服务 cache/shots/ 下的拍照临时文件。
 *
 * 用系统相机拍照时要把输出位置以 content:// 交给相机 App（file:// 在 API 24+
 * 会直接抛 FileUriExposedException），框架层没有现成的 FileProvider（那个在
 * AndroidX 里），所以这里自己实现一个够用的。
 */
public class ShotProvider extends ContentProvider {

    public static final String AUTHORITY = "com.koudai.cpm.shots";

    public static Uri uriFor(File f) {
        return Uri.parse("content://" + AUTHORITY + "/" + f.getName());
    }

    private File fileFor(Uri uri) {
        File dir = new File(getContext().getCacheDir(), "shots");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        String name = uri.getLastPathSegment();
        if (name == null) {
            name = "shot.jpg";
        }
        return new File(dir, name.replace("/", "_"));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        return ParcelFileDescriptor.open(f,
                ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_READ_WRITE
                        | ParcelFileDescriptor.MODE_TRUNCATE);
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
