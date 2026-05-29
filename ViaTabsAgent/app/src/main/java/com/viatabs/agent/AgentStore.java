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
    static final String DOWNLOAD_DIR = "ViaTabsAgent";
    static final String LOG_FILE = "agent-log.txt";
    static final String PREPARE_ALL_SCRIPT_FILE = "prepare-via-all-db.sh";

    private static final String PREFS = "via_tabs_agent";
    private static final String KEY_DOMAIN_GROUP_ENABLED = "domain_group_enabled";
    private static final int MAX_LOG_BYTES = 64 * 1024;

    private AgentStore() {
    }

    static boolean isDomainGroupEnabled(Context context) {
        if (context == null) {
            return false;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DOMAIN_GROUP_ENABLED, false);
    }

    static void setDomainGroupEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DOMAIN_GROUP_ENABLED, enabled)
                .apply();
    }

    static String writeDownloadFile(Context context, String fileName, String payload) throws Exception {
        return writeDownloadFileDetailed(context, fileName, payload).primaryPath();
    }

    static WriteResult writeDownloadFileDetailed(Context context, String fileName, String payload)
            throws Exception {
        if (!isSupportedExportName(fileName)) {
            throw new IllegalArgumentException("unsupported file: " + fileName);
        }
        WriteResult result = new WriteResult(fileName);
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                result.publicPath = writeDownloadFileMediaStore(context, fileName, payload);
            } catch (Throwable t) {
                result.publicError = String.valueOf(t);
            }
        } else {
            try {
                result.publicPath = writeDownloadFileLegacy(fileName, payload);
            } catch (Throwable t) {
                result.publicError = String.valueOf(t);
            }
        }
        try {
            result.appExternalPath = writeAppExternalFile(context, fileName, payload);
        } catch (Throwable t) {
            result.appExternalError = String.valueOf(t);
        }
        if (!result.success()) {
            throw new IllegalStateException(result.failureSummary());
        }
        return result;
    }

    private static boolean isSupportedExportName(String fileName) {
        return LOG_FILE.equals(fileName)
                || PREPARE_ALL_SCRIPT_FILE.equals(fileName)
                || isManagedExportDataName(fileName);
    }

    private static boolean isManagedExportDataName(String fileName) {
        return fileName != null
                && fileName.startsWith("via-")
                && (fileName.endsWith(".json") || fileName.endsWith(".html"));
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
            return "read log failed: " + t + "\n";
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
        deleteMediaStoreExports(resolver, collection, fileName, relativePath);
        Uri uri = null;
        if (uri == null) {
            deleteLegacyDuplicateFile(fileName);
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
        return "/storage/emulated/0/Download/" + DOWNLOAD_DIR + "/" + fileName;
    }

    private static String writeDownloadFileLegacy(String fileName, String payload) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        File file = new File(dir, fileName);
        writeFile(file, payload);
        return file.getAbsolutePath();
    }

    private static String writeAppExternalFile(Context context, String fileName, String payload)
            throws Exception {
        File base = context.getExternalFilesDir(DOWNLOAD_DIR);
        if (base == null) {
            throw new IllegalStateException("external files dir unavailable");
        }
        File file = new File(base, fileName);
        writeFile(file, payload);
        return file.getAbsolutePath();
    }

    private static String mimeTypeFor(String fileName) {
        if (fileName != null && fileName.endsWith(".json")) {
            return "application/json";
        }
        if (fileName != null && fileName.endsWith(".html")) {
            return "text/html";
        }
        if (fileName != null && fileName.endsWith(".sh")) {
            return "text/x-shellscript";
        }
        return "text/plain";
    }

    private static void deleteMediaStoreExports(ContentResolver resolver, Uri collection,
                                                String fileName, String relativePath) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(collection, new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                            + MediaStore.Downloads.RELATIVE_PATH + "=?",
                    new String[]{fileName, relativePath}, null);
            while (cursor != null && cursor.moveToNext()) {
                Uri item = Uri.withAppendedPath(collection, String.valueOf(cursor.getLong(0)));
                resolver.delete(item, null, null);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static void deleteLegacyDuplicateFile(String fileName) {
        try {
            File legacy = new File(new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_DIR), fileName);
            if (legacy.exists()) {
                legacy.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static File privateLogFile(Context context) {
        return new File(context.getFilesDir(), LOG_FILE);
    }

    private static void writeFile(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create " + parent);
        }
        FileOutputStream output = new FileOutputStream(file, false);
        try {
            output.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        } finally {
            output.close();
        }
    }

    private static String readFile(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            return readStream(input);
        } finally {
            input.close();
        }
    }

    private static String readStream(FileInputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String trimLog(String text) {
        if (text == null || text.length() <= MAX_LOG_BYTES) {
            return text == null ? "" : text;
        }
        return text.substring(text.length() - MAX_LOG_BYTES);
    }

    static final class WriteResult {
        final String fileName;
        String publicPath;
        String appExternalPath;
        String publicError;
        String appExternalError;

        WriteResult(String fileName) {
            this.fileName = fileName;
        }

        boolean success() {
            return publicPath != null || appExternalPath != null;
        }

        String primaryPath() {
            return publicPath != null ? publicPath : appExternalPath;
        }

        String summary() {
            StringBuilder out = new StringBuilder();
            out.append("file=").append(fileName);
            if (publicPath != null) {
                out.append(" public=").append(publicPath);
            }
            if (appExternalPath != null) {
                out.append(" appExternal=").append(appExternalPath);
            }
            if (publicError != null) {
                out.append(" publicError=").append(publicError);
            }
            if (appExternalError != null) {
                out.append(" appExternalError=").append(appExternalError);
            }
            return out.toString();
        }

        String displayText() {
            StringBuilder out = new StringBuilder();
            if (publicPath != null) {
                out.append("公共下载目录:\n").append(publicPath).append("\n\n");
            }
            if (appExternalPath != null) {
                out.append("应用外部目录:\n").append(appExternalPath).append("\n\n");
            }
            if (publicError != null) {
                out.append("公共下载目录错误:\n").append(publicError).append("\n\n");
            }
            if (appExternalError != null) {
                out.append("应用外部目录错误:\n").append(appExternalError).append("\n\n");
            }
            return out.toString().trim();
        }

        String failureSummary() {
            return "write failed: " + summary();
        }
    }
}
