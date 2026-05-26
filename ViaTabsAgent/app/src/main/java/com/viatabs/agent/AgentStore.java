package com.viatabs.agent;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class AgentStore {
    static final String AUTHORITY = "com.viatabs.agent.export";
    static final Uri EXPORT_URI = Uri.parse("content://" + AUTHORITY + "/export");
    static final Uri LOG_URI = Uri.parse("content://" + AUTHORITY + "/log");
    static final String DOWNLOAD_DIR = "ViaTabsAgent";
    static final String BOOKMARKS_FILE = "saved-bookmarks.json";
    static final String LOG_FILE = "agent-log.txt";
    static final String PREFS = "via_tabs_agent";
    static final String KEY_EXPORT_ENABLED = "export_enabled";

    private static final int MAX_LOG_BYTES = 64 * 1024;

    private AgentStore() {
    }

    static String writeDownloadFile(Context context, String fileName, String payload) throws Exception {
        if (!isSupportedExportName(fileName)) {
            throw new IllegalArgumentException("unsupported file: " + fileName);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return writeDownloadFileMediaStore(context, fileName, payload);
        }
        return writeDownloadFileLegacy(fileName, payload);
    }

    private static boolean isSupportedExportName(String fileName) {
        return BOOKMARKS_FILE.equals(fileName)
                || LOG_FILE.equals(fileName)
                || (fileName != null && fileName.startsWith("书签-")
                && (fileName.endsWith(".json") || fileName.endsWith(".html")));
    }

    static boolean isExportEnabled(Context context) {
        if (context == null) {
            return true;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_EXPORT_ENABLED, true);
    }

    static void setExportEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_EXPORT_ENABLED, enabled)
                .apply();
    }

    static void appendLog(Context context, String message) {
        if (context == null || message == null) {
            return;
        }
        try {
            String entry = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "  " + message + "\n";
            File log = privateLogFile(context);
            String previous = log.exists() ? readFile(log) : "";
            String next = trimLog(previous + entry);
            writeFile(log, next);
            try {
                writeDownloadFile(context, LOG_FILE, next);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    static String readLog(Context context) {
        if (context == null) {
            return "";
        }
        try {
            File log = privateLogFile(context);
            return log.exists() ? readFile(log) : "";
        } catch (Throwable t) {
            return "读取日志失败: " + t + "\n";
        }
    }

    static void clearLog(Context context) {
        if (context == null) {
            return;
        }
        try {
            writeFile(privateLogFile(context), "");
            try {
                writeDownloadFile(context, LOG_FILE, "");
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static String writeDownloadFileMediaStore(Context context, String fileName, String payload) throws Exception {
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_DIR + "/";
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = findMediaStoreExport(resolver, collection, fileName, relativePath);
        if (uri == null) {
            deleteLegacyDuplicateFiles(fileName, true);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(fileName));
            values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            uri = resolver.insert(collection, values);
        }
        if (uri == null) {
            throw new IllegalStateException("cannot create Download file");
        }
        OutputStream stream = resolver.openOutputStream(uri, "wt");
        if (stream == null) {
            throw new IllegalStateException("cannot open Download file");
        }
        try {
            stream.write(payload.getBytes(StandardCharsets.UTF_8));
        } finally {
            stream.close();
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        deleteMediaStoreDuplicateExports(resolver, collection, fileName, relativePath, uri);
        deleteLegacyDuplicateFiles(fileName, false);
        return "/storage/emulated/0/Download/" + DOWNLOAD_DIR + "/" + fileName;
    }

    private static String mimeTypeFor(String fileName) {
        if (fileName != null && fileName.endsWith(".json")) {
            return "application/json";
        }
        if (fileName != null && fileName.endsWith(".html")) {
            return "text/html";
        }
        return "text/plain";
    }

    private static Uri findMediaStoreExport(ContentResolver resolver, Uri collection,
                                            String fileName, String relativePath) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(collection, new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?",
                    new String[]{fileName, relativePath}, null);
            if (cursor != null && cursor.moveToFirst()) {
                return Uri.withAppendedPath(collection, String.valueOf(cursor.getLong(0)));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private static void deleteMediaStoreDuplicateExports(ContentResolver resolver, Uri collection,
                                                         String fileName, String relativePath, Uri keep) {
        Cursor cursor = null;
        try {
            String duplicatePrefix = fileName.substring(0, fileName.lastIndexOf('.')) + " (";
            String duplicateSuffix = fileName.substring(fileName.lastIndexOf('.'));
            cursor = resolver.query(collection, new String[]{MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME},
                    MediaStore.Downloads.RELATIVE_PATH + "=?",
                    new String[]{relativePath}, null);
            while (cursor != null && cursor.moveToNext()) {
                long id = cursor.getLong(0);
                Uri item = Uri.withAppendedPath(collection, String.valueOf(id));
                String name = cursor.getString(1);
                if (!item.equals(keep) && name != null
                        && name.startsWith(duplicatePrefix) && name.endsWith(duplicateSuffix)) {
                    resolver.delete(item, null, null);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static String writeDownloadFileLegacy(String fileName, String payload) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        deleteLegacyDuplicateFiles(fileName, true);
        File out = new File(dir, fileName);
        writeFile(out, payload);
        return out.getAbsolutePath();
    }

    private static void deleteLegacyDuplicateFiles(String fileName, boolean includeExact) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_DIR);
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        int dot = fileName.lastIndexOf('.');
        String duplicatePrefix = dot > 0 ? fileName.substring(0, dot) + " (" : fileName + " (";
        String duplicateSuffix = dot > 0 ? fileName.substring(dot) : "";
        for (File file : files) {
            String name = file.getName();
            if ((includeExact && fileName.equals(name))
                    || (name.startsWith(duplicatePrefix) && name.endsWith(duplicateSuffix))) {
                file.delete();
            }
        }
    }

    private static File privateLogFile(Context context) {
        return new File(context.getFilesDir(), LOG_FILE);
    }

    private static String readFile(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private static void writeFile(File file, String payload) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create " + parent);
        }
        FileOutputStream output = new FileOutputStream(file, false);
        try {
            output.write(payload.getBytes(StandardCharsets.UTF_8));
        } finally {
            output.close();
        }
    }

    private static String trimLog(String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (data.length <= MAX_LOG_BYTES) {
            return value;
        }
        int start = data.length - MAX_LOG_BYTES;
        return new String(data, start, MAX_LOG_BYTES, StandardCharsets.UTF_8);
    }
}
