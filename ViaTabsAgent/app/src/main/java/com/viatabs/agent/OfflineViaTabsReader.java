package com.viatabs.agent;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class OfflineViaTabsReader {
    private static final int VIA_TAB_FLAG_RESTORE_REQUIRED = 0x2;
    private static final int VIA_TAB_FLAG_RESTORE_EXCLUDE = 0x1;

    private OfflineViaTabsReader() {
    }

    static ParseResult parsePrepared(Context context, String packageName) throws Exception {
        validatePackage(context, packageName);
        File prepared = preparedViaDatabase(context, packageName);
        if (prepared == null) {
            throw new IllegalStateException("no prepared sh db for " + packageName
                    + ". expected: " + preparedDbLocations(context, packageName));
        }
        List<TabRecord> tabs = readTabs(prepared);
        AgentStore.appendLog(context, "prepared db parsed: package=" + packageName
                + " tabs=" + tabs.size() + " db=" + prepared.getAbsolutePath());
        return new ParseResult(packageName, prepared, tabs);
    }

    static boolean hasPreparedDatabase(Context context, String packageName) {
        if (context == null || packageName == null) {
            return false;
        }
        if (!"mark.via".equals(packageName) && !"mark.via.gp".equals(packageName)) {
            return false;
        }
        return preparedViaDatabase(context, packageName) != null;
    }

    static List<String> preparedPackages(Context context) {
        ArrayList<String> packages = new ArrayList<String>();
        if (hasPreparedDatabase(context, "mark.via")) {
            packages.add("mark.via");
        }
        if (hasPreparedDatabase(context, "mark.via.gp")) {
            packages.add("mark.via.gp");
        }
        return packages;
    }

    private static void validatePackage(Context context, String packageName) {
        if (context == null) {
            throw new IllegalArgumentException("context is null");
        }
        if (!"mark.via".equals(packageName) && !"mark.via.gp".equals(packageName)) {
            throw new IllegalArgumentException("unsupported Via package: " + packageName);
        }
    }

    private static File preparedViaDatabase(Context context, String packageName) {
        if (context == null || packageName == null) {
            return null;
        }
        String name = packageName.replace('.', '_') + "-via.db";
        ArrayList<File> candidates = new ArrayList<File>();
        File files = context.getFilesDir();
        if (files != null) {
            candidates.add(new File(new File(files, "offline-via-tabs"), name));
        }
        try {
            File external = context.getExternalFilesDir("offline-via-tabs");
            if (external != null) {
                candidates.add(new File(external, name));
            }
        } catch (Throwable ignored) {
        }
        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile() && candidate.canRead()) {
                return candidate;
            }
        }
        return null;
    }

    private static String preparedDbLocations(Context context, String packageName) {
        String name = packageName == null ? "<package>-via.db"
                : packageName.replace('.', '_') + "-via.db";
        StringBuilder out = new StringBuilder();
        File files = context == null ? null : context.getFilesDir();
        if (files != null) {
            out.append(new File(new File(files, "offline-via-tabs"), name).getAbsolutePath());
        } else {
            out.append("/data/user/0/com.viatabs.agent/files/offline-via-tabs/").append(name);
        }
        try {
            File external = context == null ? null : context.getExternalFilesDir("offline-via-tabs");
            if (external != null) {
                out.append(" or ").append(new File(external, name).getAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
        return out.toString();
    }

    private static List<TabRecord> readTabs(File dbFile) throws Exception {
        ArrayList<TabRecord> records = new ArrayList<TabRecord>();
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            if (!hasTable(db, "tabs")) {
                return records;
            }
            cursor = db.rawQuery("SELECT _id, url, title, flags, last_visited_at FROM tabs "
                    + "WHERE (flags & " + VIA_TAB_FLAG_RESTORE_REQUIRED + ") = "
                    + VIA_TAB_FLAG_RESTORE_REQUIRED
                    + " AND (flags & " + VIA_TAB_FLAG_RESTORE_EXCLUDE + ") = 0 "
                    + "ORDER BY last_visited_at ASC", null);
            while (cursor.moveToNext()) {
                String url = cursor.getString(1);
                if (!TabUrls.isMeaningful(url)) {
                    continue;
                }
                String title = cursor.getString(2);
                if (title == null || title.trim().length() == 0 || TabUrls.isMeaningful(title)) {
                    title = TabUrls.titleFromUrl(url);
                }
                records.add(new TabRecord(records.size(), title.trim(), url.trim()));
            }
            return records;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            if (db != null) {
                db.close();
            }
        }
    }

    private static boolean hasTable(SQLiteDatabase db, String table) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    new String[]{table});
            return cursor.moveToFirst();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    static String displayTitle(TabRecord tab) {
        if (tab == null) {
            return "";
        }
        String title = tab.title == null ? "" : tab.title.trim();
        if (title.length() == 0 || "null".equalsIgnoreCase(title) || TabUrls.isMeaningful(title)) {
            return TabUrls.titleFromUrl(tab.url);
        }
        return title;
    }

    static final class ParseResult {
        final String packageName;
        final File database;
        final List<TabRecord> tabs;

        ParseResult(String packageName, File database, List<TabRecord> tabs) {
            this.packageName = packageName;
            this.database = database;
            this.tabs = tabs;
        }
    }
}
