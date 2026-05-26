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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
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
    static final String KEY_TAB_EXPORT_ENABLED = "tab_export_enabled";
    static final String KEY_BOOKMARK_IMPORT_ENABLED = "bookmark_import_enabled";
    static final String KEY_DOMAIN_GROUP_ENABLED = "domain_group_enabled";
    static final String KEY_LAST_SAVE_RESULT = "last_save_result";
    static final String KEY_PANEL_COLOR = "panel_color";
    static final String PANEL_COLOR_BLUE = "blue";
    static final String PANEL_COLOR_GREEN = "green";
    static final String PANEL_COLOR_PURPLE = "purple";
    static final String PANEL_COLOR_DARK = "dark";
    static final String PANEL_COLOR_WHITE = "white";
    static final String PANEL_COLOR_TRANSPARENT = "transparent";
    static final String PANEL_COLOR_ROSE = "rose";
    static final String PANEL_COLOR_ORANGE = "orange";
    static final String PANEL_COLOR_CYAN = "cyan";
    static final String KEY_PANEL_ALPHA = "panel_alpha";
    static final String KEY_PANEL_SIZE = "panel_size";
    static final String KEY_PANEL_X_PREFIX = "panel_x_";
    static final String KEY_PANEL_Y_PREFIX = "panel_y_";
    static final String KEY_EXPORT_NOTE_PREFIX = "export_note_";

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
                || isExportDataName(fileName);
    }

    private static boolean isExportDataName(String fileName) {
        return isManagedExportDataName(fileName) || isTestExportName(fileName);
    }

    private static boolean isManagedExportDataName(String fileName) {
        return fileName != null && fileName.startsWith("via-")
                && !fileName.startsWith("via-test-")
                && (fileName.endsWith(".json") || fileName.endsWith(".html"));
    }

    private static boolean isTestExportName(String fileName) {
        return fileName != null && fileName.startsWith("via-test-") && fileName.endsWith(".json");
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

    static boolean isTabExportEnabled(Context context) {
        return true;
    }

    static void setTabExportEnabled(Context context, boolean enabled) {
        // JSON export is now a fixed baseline behavior.
    }

    static boolean isBookmarkImportEnabled(Context context) {
        if (context == null) {
            return true;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_BOOKMARK_IMPORT_ENABLED, true);
    }

    static void setBookmarkImportEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BOOKMARK_IMPORT_ENABLED, enabled)
                .apply();
    }

    static boolean isDomainGroupEnabled(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_DOMAIN_GROUP_ENABLED, false);
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

    static String getLastSaveResult(Context context) {
        if (context == null) {
            return "";
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LAST_SAVE_RESULT, "");
    }

    static void setLastSaveResult(Context context, String result) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_SAVE_RESULT, result == null ? "" : result)
                .apply();
    }

    static String getPanelColor(Context context) {
        if (context == null) {
            return PANEL_COLOR_BLUE;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PANEL_COLOR, PANEL_COLOR_BLUE);
    }

    static void setPanelColor(Context context, String color) {
        if (context == null) {
            return;
        }
        if (!PANEL_COLOR_GREEN.equals(color)
                && !PANEL_COLOR_PURPLE.equals(color)
                && !PANEL_COLOR_DARK.equals(color)
                && !PANEL_COLOR_WHITE.equals(color)
                && !PANEL_COLOR_TRANSPARENT.equals(color)
                && !PANEL_COLOR_ROSE.equals(color)
                && !PANEL_COLOR_ORANGE.equals(color)
                && !PANEL_COLOR_CYAN.equals(color)) {
            color = PANEL_COLOR_BLUE;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PANEL_COLOR, color)
                .apply();
    }

    static int getPanelAlpha(Context context) {
        if (context == null) {
            return 92;
        }
        return Math.max(20, Math.min(100,
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PANEL_ALPHA, 92)));
    }

    static void setPanelAlpha(Context context, int alpha) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_PANEL_ALPHA, Math.max(20, Math.min(100, alpha)))
                .apply();
    }

    static int getPanelSize(Context context) {
        if (context == null) {
            return 40;
        }
        return Math.max(28, Math.min(56,
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PANEL_SIZE, 40)));
    }

    static void setPanelSize(Context context, int size) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_PANEL_SIZE, Math.max(28, Math.min(56, size)))
                .apply();
    }

    static float getPanelX(Context context, String packageName) {
        if (context == null || packageName == null) {
            return Float.NaN;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_PANEL_X_PREFIX + packageName, Float.NaN);
    }

    static float getPanelY(Context context, String packageName) {
        if (context == null || packageName == null) {
            return Float.NaN;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_PANEL_Y_PREFIX + packageName, Float.NaN);
    }

    static void setPanelPosition(Context context, String packageName, float x, float y) {
        if (context == null || packageName == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_PANEL_X_PREFIX + packageName, x)
                .putFloat(KEY_PANEL_Y_PREFIX + packageName, y)
                .apply();
    }

    static List<ExportedFile> listExportedFiles(Context context) {
        List<ExportedFile> result = new ArrayList<ExportedFile>();
        if (context == null) {
            return result;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            listMediaStoreExports(context, result);
        }
        listLegacyExports(result);
        Collections.sort(result, new Comparator<ExportedFile>() {
            @Override
            public int compare(ExportedFile left, ExportedFile right) {
                if (left.lastModified == right.lastModified) {
                    return left.name.compareTo(right.name);
                }
                return left.lastModified > right.lastModified ? -1 : 1;
            }
        });
        return result;
    }

    static boolean deleteExportedFile(Context context, String fileName) {
        if (context == null || !isExportDataName(fileName)) {
            return false;
        }
        boolean deleted = false;
        if (Build.VERSION.SDK_INT >= 29) {
            deleted = deleteMediaStoreExport(context, fileName);
        }
        File legacy = new File(new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_DIR), fileName);
        if (legacy.exists()) {
            deleted = legacy.delete() || deleted;
        }
        return deleted;
    }

    static List<ExportGroup> listExportGroups(Context context) {
        List<ExportedFile> files = listExportedFiles(context);
        List<ExportGroup> groups = new ArrayList<ExportGroup>();
        for (ExportedFile file : files) {
            String base = exportBaseName(file.name);
            if (base == null) {
                continue;
            }
            ExportGroup group = findGroup(groups, base);
            if (group == null) {
                group = new ExportGroup(base);
                groups.add(group);
            }
            if (file.name.endsWith(".json")) {
                group.jsonName = file.name;
                group.jsonPath = file.path;
            } else if (file.name.endsWith(".html")) {
                group.htmlName = file.name;
                group.htmlPath = file.path;
            }
            group.size += file.size;
            group.lastModified = Math.max(group.lastModified, file.lastModified);
        }
        for (ExportGroup group : groups) {
            group.note = getExportNote(context, group.baseName);
            readGroupMetadata(context, group);
        }
        Collections.sort(groups, new Comparator<ExportGroup>() {
            @Override
            public int compare(ExportGroup left, ExportGroup right) {
                if (left.lastModified == right.lastModified) {
                    return left.baseName.compareTo(right.baseName);
                }
                return left.lastModified > right.lastModified ? -1 : 1;
            }
        });
        return groups;
    }

    static boolean deleteExportGroup(Context context, String baseName) {
        if (baseName == null) {
            return false;
        }
        boolean deleted = false;
        deleted = deleteExportedFile(context, baseName + ".json") || deleted;
        deleted = deleteExportedFile(context, baseName + ".html") || deleted;
        setExportNote(context, baseName, "");
        return deleted;
    }

    static String getExportNote(Context context, String baseName) {
        if (context == null || baseName == null) {
            return "";
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_EXPORT_NOTE_PREFIX + baseName, "");
    }

    static void setExportNote(Context context, String baseName, String note) {
        if (context == null || baseName == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_EXPORT_NOTE_PREFIX + baseName, note == null ? "" : note.trim())
                .apply();
    }

    static String readExportText(Context context, String fileName) {
        if (context == null || !isExportDataName(fileName)) {
            return "";
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_DIR + "/";
                ContentResolver resolver = context.getContentResolver();
                Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri uri = findMediaStoreExport(resolver, collection, fileName, relativePath);
                if (uri != null) {
                    java.io.InputStream input = resolver.openInputStream(uri);
                    if (input != null) {
                        try {
                            return readStream(input);
                        } finally {
                            input.close();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            File legacy = new File(new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_DIR), fileName);
            return legacy.exists() ? readFile(legacy) : "";
        } catch (Throwable ignored) {
            return "";
        }
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

    private static void listMediaStoreExports(Context context, List<ExportedFile> result) {
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_DIR + "/";
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Cursor cursor = null;
        try {
            cursor = resolver.query(collection,
                    new String[]{
                            MediaStore.Downloads.DISPLAY_NAME,
                            MediaStore.Downloads.SIZE,
                            MediaStore.Downloads.DATE_MODIFIED
                    },
                    MediaStore.Downloads.RELATIVE_PATH + "=?",
                    new String[]{relativePath},
                    null);
            while (cursor != null && cursor.moveToNext()) {
                String name = cursor.getString(0);
                if (!isManagedExportDataName(name) || hasExport(result, name)) {
                    continue;
                }
                long size = cursor.isNull(1) ? 0L : cursor.getLong(1);
                long modified = cursor.isNull(2) ? 0L : cursor.getLong(2) * 1000L;
                result.add(new ExportedFile(name,
                        "/storage/emulated/0/Download/" + DOWNLOAD_DIR + "/" + name,
                        size,
                        modified));
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static void listLegacyExports(List<ExportedFile> result) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_DIR);
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && isManagedExportDataName(name) && !hasExport(result, name)) {
                result.add(new ExportedFile(name, file.getAbsolutePath(), file.length(), file.lastModified()));
            }
        }
    }

    private static boolean hasExport(List<ExportedFile> result, String name) {
        for (ExportedFile file : result) {
            if (file.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String exportBaseName(String fileName) {
        if (!isManagedExportDataName(fileName)) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static ExportGroup findGroup(List<ExportGroup> groups, String baseName) {
        for (ExportGroup group : groups) {
            if (group.baseName.equals(baseName)) {
                return group;
            }
        }
        return null;
    }

    private static void readGroupMetadata(Context context, ExportGroup group) {
        if (context == null || group == null || group.jsonName == null) {
            return;
        }
        try {
            String payload = readExportText(context, group.jsonName);
            if (payload == null || payload.trim().length() == 0) {
                return;
            }
            JSONObject root = new JSONObject(payload);
            group.packageName = root.optString("packageName", "");
            group.folder = root.optString("folder", "");
            group.captured = root.optInt("captured", 0);
            group.bookmarkable = root.optInt("bookmarkable", 0);
            JSONArray tabs = root.optJSONArray("tabs");
            if (tabs != null && group.captured == 0) {
                group.captured = tabs.length();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readStream(java.io.InputStream input) throws Exception {
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

    private static boolean deleteMediaStoreExport(Context context, String fileName) {
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_DIR + "/";
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = findMediaStoreExport(resolver, collection, fileName, relativePath);
        return uri != null && resolver.delete(uri, null, null) > 0;
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

    static final class ExportedFile {
        final String name;
        final String path;
        final long size;
        final long lastModified;

        ExportedFile(String name, String path, long size, long lastModified) {
            this.name = name;
            this.path = path;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    static final class ExportGroup {
        final String baseName;
        String htmlName;
        String htmlPath;
        String jsonName;
        String jsonPath;
        String packageName = "";
        String folder = "";
        String note = "";
        long size;
        long lastModified;
        int captured;
        int bookmarkable;

        ExportGroup(String baseName) {
            this.baseName = baseName;
        }
    }
}
