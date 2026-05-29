package com.viatabs.agent;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

final class LocalTabStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "viatabs-local.db";
    private static final int DB_VERSION = 4;
    private static final String BACKUPS = "backups";
    private static final String TABS = "tabs";

    LocalTabStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createSchema(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            migrateV1ToV2(db);
            oldVersion = 2;
        }
        if (oldVersion < 3) {
            migrateV2ToV3(db);
            oldVersion = 3;
        }
        if (oldVersion < 4) {
            migrateV3ToV4(db);
            oldVersion = 4;
        }
        if (oldVersion >= newVersion) {
            return;
        }
        db.execSQL("DROP TABLE IF EXISTS " + TABS);
        db.execSQL("DROP TABLE IF EXISTS " + BACKUPS);
        createSchema(db);
    }

    private void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + BACKUPS + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "source_packages TEXT NOT NULL DEFAULT '',"
                + "source_count INTEGER NOT NULL DEFAULT 0,"
                + "inserted INTEGER NOT NULL DEFAULT 0,"
                + "updated INTEGER NOT NULL DEFAULT 0,"
                + "skipped INTEGER NOT NULL DEFAULT 0,"
                + "total_tabs INTEGER NOT NULL DEFAULT 0,"
                + "active_tabs INTEGER NOT NULL DEFAULT 0,"
                + "deleted_tabs INTEGER NOT NULL DEFAULT 0,"
                + "note TEXT NOT NULL DEFAULT '',"
                + "deleted INTEGER NOT NULL DEFAULT 0"
                + ")");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABS + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "backup_id INTEGER NOT NULL,"
                + "source_package TEXT NOT NULL,"
                + "url TEXT NOT NULL,"
                + "url_key TEXT NOT NULL,"
                + "title TEXT NOT NULL,"
                + "domain TEXT NOT NULL,"
                + "note TEXT NOT NULL DEFAULT '',"
                + "deleted INTEGER NOT NULL DEFAULT 0,"
                + "source_db_modified INTEGER NOT NULL DEFAULT 0,"
                + "import_time INTEGER NOT NULL DEFAULT 0,"
                + "last_seen_time INTEGER NOT NULL DEFAULT 0,"
                + "UNIQUE(backup_id, source_package, url_key)"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS backups_deleted_idx ON " + BACKUPS + "(deleted)");
        db.execSQL("CREATE INDEX IF NOT EXISTS backups_created_idx ON " + BACKUPS + "(created_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS tabs_backup_idx ON " + TABS + "(backup_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS tabs_deleted_idx ON " + TABS + "(deleted)");
        db.execSQL("CREATE INDEX IF NOT EXISTS tabs_source_idx ON " + TABS + "(backup_id, source_package)");
        db.execSQL("CREATE INDEX IF NOT EXISTS tabs_domain_idx ON " + TABS + "(backup_id, domain)");
        db.execSQL("CREATE INDEX IF NOT EXISTS tabs_seen_idx ON " + TABS + "(last_seen_time)");
    }

    private void migrateV1ToV2(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            boolean hasOldTabs = hasTable(db, TABS);
            if (hasOldTabs) {
                db.execSQL("ALTER TABLE " + TABS + " RENAME TO tabs_old");
            }
            createSchema(db);
            if (hasOldTabs && hasTable(db, "tabs_old")) {
                long now = System.currentTimeMillis();
                long backupId = insertBackup(db, "legacy-" + stamp(now), now,
                        "legacy", 0, 0, 0, 0, "Migrated from 0.5.0 local tabs");
                db.execSQL("INSERT OR IGNORE INTO " + TABS + " ("
                        + "backup_id, source_package, url, url_key, title, domain, note, deleted, "
                        + "source_db_modified, import_time, last_seen_time"
                        + ") SELECT ?, source_package, url, url_key, title, domain, note, deleted, "
                        + "source_db_modified, import_time, last_seen_time FROM tabs_old",
                        new Object[]{backupId});
                updateBackupSummary(db, backupId, sourcePackagesForBackup(db, backupId),
                        countSourcePackages(db, backupId), countTabs(db, backupId), 0, 0);
                updateBackupCounts(db, backupId);
                db.execSQL("DROP TABLE IF EXISTS tabs_old");
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void migrateV2ToV3(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            addColumnIfMissing(db, BACKUPS, "total_tabs", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db, BACKUPS, "active_tabs", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db, BACKUPS, "deleted_tabs", "INTEGER NOT NULL DEFAULT 0");
            splitMixedBackups(db);
            refreshAllBackupCaches(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void migrateV3ToV4(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            addColumnIfMissing(db, BACKUPS, "total_tabs", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db, BACKUPS, "active_tabs", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db, BACKUPS, "deleted_tabs", "INTEGER NOT NULL DEFAULT 0");
            splitMixedBackups(db);
            refreshAllBackupCaches(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    long createBackup(String name, List<String> packages) {
        long now = System.currentTimeMillis();
        return insertBackup(getWritableDatabase(), clean(name), now, joinPackages(packages),
                packages == null ? 0 : packages.size(), 0, 0, 0, "");
    }

    void updateBackupSummary(long backupId, List<ImportResult> results) {
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        LinkedHashSet<String> packages = new LinkedHashSet<String>();
        if (results != null) {
            for (ImportResult result : results) {
                if (result == null) {
                    continue;
                }
                inserted += result.inserted;
                updated += result.updated;
                skipped += result.skipped;
                if (result.sourcePackage != null && result.sourcePackage.length() > 0) {
                    packages.add(result.sourcePackage);
                }
            }
        }
        SQLiteDatabase db = getWritableDatabase();
        updateBackupSummary(db, backupId, joinPackages(new ArrayList<String>(packages)),
                packages.size(), inserted, updated, skipped);
        updateBackupCounts(db, backupId);
    }

    ImportResult importTabs(long backupId, String sourcePackage, List<TabRecord> tabs,
                            long sourceDbModified) {
        ImportResult result = new ImportResult(backupId, sourcePackage);
        if (backupId <= 0 || sourcePackage == null || tabs == null) {
            return result;
        }
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (TabRecord tab : tabs) {
                result.seen++;
                String url = tab == null ? "" : clean(tab.url);
                String key = TabUrls.key(url);
                if (!TabUrls.isMeaningful(url) || key.length() == 0) {
                    result.skipped++;
                    continue;
                }
                String title = clean(tab.title);
                if (title.length() == 0 || "null".equalsIgnoreCase(title)
                        || TabUrls.isMeaningful(title)) {
                    title = TabUrls.titleFromUrl(url);
                }
                long existingId = existingId(db, backupId, sourcePackage, key);
                ContentValues values = new ContentValues();
                values.put("backup_id", backupId);
                values.put("source_package", sourcePackage);
                values.put("url", url);
                values.put("url_key", key);
                values.put("title", title);
                values.put("domain", TabUrls.domainGroupName(url));
                values.put("deleted", 0);
                values.put("source_db_modified", sourceDbModified);
                values.put("last_seen_time", now);
                if (existingId > 0) {
                    db.update(TABS, values, "_id=?", new String[]{String.valueOf(existingId)});
                    result.updated++;
                    result.restored++;
                } else {
                    values.put("note", "");
                    values.put("import_time", now);
                    if (db.insert(TABS, null, values) >= 0) {
                        result.inserted++;
                    } else {
                        result.skipped++;
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return result;
    }

    long addManualTab(long backupId, String title, String url, String note) {
        if (backupId <= 0) {
            backupId = createBackup("manual-" + stamp(System.currentTimeMillis()), null);
        }
        String cleanUrl = clean(url);
        String key = TabUrls.key(cleanUrl);
        if (!TabUrls.isBookmarkable(cleanUrl) || key.length() == 0) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }
        String cleanTitle = clean(title);
        if (cleanTitle.length() == 0 || TabUrls.isMeaningful(cleanTitle)) {
            cleanTitle = TabUrls.titleFromUrl(cleanUrl);
        }
        long now = System.currentTimeMillis();
        String sourcePackage = "manual";
        SQLiteDatabase db = getWritableDatabase();
        long existingId = existingId(db, backupId, sourcePackage, key);
        ContentValues values = new ContentValues();
        values.put("backup_id", backupId);
        values.put("source_package", sourcePackage);
        values.put("url", cleanUrl);
        values.put("url_key", key);
        values.put("title", cleanTitle);
        values.put("domain", TabUrls.domainGroupName(cleanUrl));
        values.put("note", clean(note));
        values.put("deleted", 0);
        values.put("source_db_modified", 0);
        values.put("last_seen_time", now);
        if (existingId > 0) {
            db.update(TABS, values, "_id=?", new String[]{String.valueOf(existingId)});
            updateBackupCounts(db, backupId);
            return existingId;
        }
        values.put("import_time", now);
        long id = db.insertOrThrow(TABS, null, values);
        updateBackupSources(db, backupId);
        updateBackupCounts(db, backupId);
        return id;
    }

    List<ManagedBackup> listBackups(boolean includeDeleted) {
        return listBackups(includeDeleted, 0, 0);
    }

    List<ManagedBackup> listBackups(boolean includeDeleted, int limit, int offset) {
        ArrayList<ManagedBackup> out = new ArrayList<ManagedBackup>();
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query(BACKUPS, backupColumns(),
                    includeDeleted ? null : "deleted=0", null, null, null,
                    "created_at DESC, _id DESC", limitClause(limit, offset));
            while (cursor.moveToNext()) {
                out.add(backupFromCursor(cursor));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return out;
    }

    int countBackups(boolean includeDeleted) {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + BACKUPS
                            + (includeDeleted ? "" : " WHERE deleted=0"),
                    null);
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    ManagedBackup latestBackup() {
        List<ManagedBackup> backups = listBackups(false, 1, 0);
        return backups.isEmpty() ? null : backups.get(0);
    }

    ManagedBackup findBackup(long backupId) {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query(BACKUPS, backupColumns(), "_id=?",
                    new String[]{String.valueOf(backupId)}, null, null, null, "1");
            return cursor.moveToFirst() ? backupFromCursor(cursor) : null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    int setBackupDeleted(long backupId, boolean deleted) {
        ContentValues values = new ContentValues();
        values.put("deleted", deleted ? 1 : 0);
        return getWritableDatabase().update(BACKUPS, values, "_id=?",
                new String[]{String.valueOf(backupId)});
    }

    int updateBackupNote(long backupId, String note) {
        ContentValues values = new ContentValues();
        values.put("note", clean(note));
        return getWritableDatabase().update(BACKUPS, values, "_id=?",
                new String[]{String.valueOf(backupId)});
    }

    List<String> listSources(long backupId, boolean includeDeleted) {
        ArrayList<String> sources = new ArrayList<String>();
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery(
                    "SELECT DISTINCT source_package FROM " + TABS
                            + " WHERE backup_id=?"
                            + (includeDeleted ? "" : " AND deleted=0")
                            + " ORDER BY source_package ASC",
                    new String[]{String.valueOf(backupId)});
            while (cursor.moveToNext()) {
                sources.add(cursor.getString(0));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return sources;
    }

    ManagedTab findById(long id) {
        List<String> args = new ArrayList<String>();
        args.add(String.valueOf(id));
        List<ManagedTab> tabs = query("_id=?", args, "_id DESC");
        return tabs.isEmpty() ? null : tabs.get(0);
    }

    List<ManagedTab> listTabs(long backupId, String sourceFilter, String domainFilter,
                              String query, boolean includeDeleted) {
        return listTabs(backupId, sourceFilter, domainFilter, query, includeDeleted, 0, 0);
    }

    List<ManagedTab> listTabs(long backupId, String sourceFilter, String domainFilter,
                              String query, boolean includeDeleted, int limit, int offset) {
        ArrayList<String> args = new ArrayList<String>();
        String where = buildTabWhere(backupId, sourceFilter, domainFilter, query, includeDeleted, args);
        return query(where, args, "deleted ASC, domain ASC, last_seen_time DESC, _id DESC",
                limitClause(limit, offset));
    }

    int countTabs(long backupId, String sourceFilter, String domainFilter,
                  String query, boolean includeDeleted) {
        ArrayList<String> args = new ArrayList<String>();
        String where = buildTabWhere(backupId, sourceFilter, domainFilter, query, includeDeleted, args);
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABS
                            + " WHERE " + where,
                    args.toArray(new String[args.size()]));
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    List<String> listDomains(long backupId, boolean includeDeleted) {
        ArrayList<String> domains = new ArrayList<String>();
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery(
                    "SELECT DISTINCT domain FROM " + TABS
                            + " WHERE backup_id=?"
                            + (includeDeleted ? "" : " AND deleted=0")
                            + " ORDER BY domain ASC",
                    new String[]{String.valueOf(backupId)});
            while (cursor.moveToNext()) {
                domains.add(cursor.getString(0));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return domains;
    }

    Stats stats() {
        Stats stats = new Stats();
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery(
                    "SELECT SUM(total_tabs), SUM(active_tabs), SUM(deleted_tabs) FROM " + BACKUPS,
                    null);
            if (cursor.moveToFirst()) {
                stats.total = intOrZero(cursor, 0);
                stats.active = intOrZero(cursor, 1);
                stats.deleted = intOrZero(cursor, 2);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        Cursor backupCursor = null;
        try {
            backupCursor = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*), SUM(CASE WHEN deleted=0 THEN 1 ELSE 0 END), "
                            + "SUM(CASE WHEN deleted=1 THEN 1 ELSE 0 END) FROM " + BACKUPS, null);
            if (backupCursor.moveToFirst()) {
                stats.backups = backupCursor.getInt(0);
                stats.activeBackups = backupCursor.getInt(1);
                stats.deletedBackups = backupCursor.getInt(2);
            }
        } finally {
            if (backupCursor != null) {
                backupCursor.close();
            }
        }
        return stats;
    }

    int setDeleted(List<Long> ids, boolean deleted) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int changed = 0;
        SQLiteDatabase db = getWritableDatabase();
        LinkedHashSet<Long> backupIds = backupIdsForTabs(db, ids);
        ContentValues values = new ContentValues();
        values.put("deleted", deleted ? 1 : 0);
        for (Long id : ids) {
            if (id != null) {
                changed += db.update(TABS, values, "_id=?", new String[]{String.valueOf(id)});
            }
        }
        for (Long backupId : backupIds) {
            if (backupId != null) {
                updateBackupCounts(db, backupId);
            }
        }
        return changed;
    }

    int updateText(long id, String title, String note) {
        ContentValues values = new ContentValues();
        values.put("title", clean(title));
        values.put("note", clean(note));
        return getWritableDatabase().update(TABS, values, "_id=?", new String[]{String.valueOf(id)});
    }

    int purgeDeleted(long backupId) {
        SQLiteDatabase db = getWritableDatabase();
        int changed = db.delete(TABS, "backup_id=? AND deleted=1",
                new String[]{String.valueOf(backupId)});
        updateBackupCounts(db, backupId);
        return changed;
    }

    int purgeDeletedBackups() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int tabs = db.delete(TABS, "backup_id IN (SELECT _id FROM " + BACKUPS
                    + " WHERE deleted=1)", null);
            int backups = db.delete(BACKUPS, "deleted=1", null);
            db.setTransactionSuccessful();
            return tabs + backups;
        } finally {
            db.endTransaction();
        }
    }

    private List<ManagedTab> query(String where, List<String> args, String orderBy) {
        return query(where, args, orderBy, null);
    }

    private List<ManagedTab> query(String where, List<String> args, String orderBy, String limit) {
        ArrayList<ManagedTab> out = new ArrayList<ManagedTab>();
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query(TABS, null, where,
                    args.toArray(new String[args.size()]), null, null, orderBy, limit);
            while (cursor.moveToNext()) {
                out.add(fromCursor(cursor));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return out;
    }

    private String buildTabWhere(long backupId, String sourceFilter, String domainFilter,
                                 String query, boolean includeDeleted, List<String> args) {
        args.add(String.valueOf(backupId));
        StringBuilder where = new StringBuilder("backup_id=?");
        if (!includeDeleted) {
            where.append(" AND deleted=0");
        }
        if (sourceFilter != null && sourceFilter.length() > 0) {
            where.append(" AND source_package=?");
            args.add(sourceFilter);
        }
        if (domainFilter != null && domainFilter.length() > 0) {
            where.append(" AND domain=?");
            args.add(domainFilter);
        }
        String cleanQuery = query == null ? "" : query.trim();
        if (cleanQuery.length() > 0) {
            where.append(" AND (title LIKE ? OR url LIKE ? OR note LIKE ?)");
            String like = "%" + cleanQuery + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return where.toString();
    }

    private ManagedTab fromCursor(Cursor cursor) {
        return new ManagedTab(
                cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("backup_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("source_package")),
                cursor.getString(cursor.getColumnIndexOrThrow("title")),
                cursor.getString(cursor.getColumnIndexOrThrow("url")),
                cursor.getString(cursor.getColumnIndexOrThrow("url_key")),
                cursor.getString(cursor.getColumnIndexOrThrow("domain")),
                cursor.getString(cursor.getColumnIndexOrThrow("note")),
                cursor.getInt(cursor.getColumnIndexOrThrow("deleted")) != 0,
                cursor.getLong(cursor.getColumnIndexOrThrow("source_db_modified")),
                cursor.getLong(cursor.getColumnIndexOrThrow("import_time")),
                cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_time")));
    }

    private ManagedBackup backupFromCursor(Cursor cursor) {
        return new ManagedBackup(
                cursor.getLong(0),
                cursor.getString(1),
                cursor.getLong(2),
                cursor.getString(3),
                cursor.getInt(4),
                cursor.getInt(5),
                cursor.getInt(6),
                cursor.getInt(7),
                cursor.getString(8),
                cursor.getInt(9) != 0,
                cursor.getInt(10),
                cursor.getInt(11),
                cursor.getInt(12));
    }

    private long existingId(SQLiteDatabase db, long backupId, String sourcePackage, String urlKey) {
        Cursor cursor = null;
        try {
            cursor = db.query(TABS, new String[]{"_id"},
                    "backup_id=? AND source_package=? AND url_key=?",
                    new String[]{String.valueOf(backupId), sourcePackage, urlKey},
                    null, null, null, "1");
            return cursor.moveToFirst() ? cursor.getLong(0) : -1L;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private long insertBackup(SQLiteDatabase db, String name, long createdAt, String packages,
                              int sourceCount, int inserted, int updated, int skipped, String note) {
        ContentValues values = new ContentValues();
        String cleanName = name == null || name.trim().length() == 0
                ? "backup-" + stamp(createdAt)
                : name.trim();
        values.put("name", cleanName);
        values.put("created_at", createdAt);
        values.put("source_packages", clean(packages));
        values.put("source_count", sourceCount);
        values.put("inserted", inserted);
        values.put("updated", updated);
        values.put("skipped", skipped);
        values.put("note", clean(note));
        values.put("deleted", 0);
        return db.insertOrThrow(BACKUPS, null, values);
    }

    private void updateBackupSummary(SQLiteDatabase db, long backupId, String packages, int sourceCount,
                                     int inserted, int updated, int skipped) {
        ContentValues values = new ContentValues();
        values.put("source_packages", clean(packages));
        values.put("source_count", sourceCount);
        values.put("inserted", inserted);
        values.put("updated", updated);
        values.put("skipped", skipped);
        db.update(BACKUPS, values, "_id=?", new String[]{String.valueOf(backupId)});
    }

    private void updateBackupSources(SQLiteDatabase db, long backupId) {
        ContentValues values = new ContentValues();
        values.put("source_packages", sourcePackagesForBackup(db, backupId));
        values.put("source_count", countSourcePackages(db, backupId));
        db.update(BACKUPS, values, "_id=?", new String[]{String.valueOf(backupId)});
    }

    private int countTabs(SQLiteDatabase db, long backupId) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABS + " WHERE backup_id=?",
                    new String[]{String.valueOf(backupId)});
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private int countSourcePackages(SQLiteDatabase db, long backupId) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(DISTINCT source_package) FROM " + TABS
                            + " WHERE backup_id=?",
                    new String[]{String.valueOf(backupId)});
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private String sourcePackagesForBackup(SQLiteDatabase db, long backupId) {
        Cursor cursor = null;
        LinkedHashSet<String> packages = new LinkedHashSet<String>();
        try {
            cursor = db.rawQuery("SELECT DISTINCT source_package FROM " + TABS
                            + " WHERE backup_id=? ORDER BY source_package ASC",
                    new String[]{String.valueOf(backupId)});
            while (cursor.moveToNext()) {
                packages.add(cursor.getString(0));
            }
            return joinPackages(new ArrayList<String>(packages));
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private ArrayList<String> sourcePackageListForBackup(SQLiteDatabase db, long backupId) {
        ArrayList<String> packages = new ArrayList<String>();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT DISTINCT source_package FROM " + TABS
                            + " WHERE backup_id=? ORDER BY source_package ASC",
                    new String[]{String.valueOf(backupId)});
            while (cursor.moveToNext()) {
                packages.add(cursor.getString(0));
            }
            return packages;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void addColumnIfMissing(SQLiteDatabase db, String table, String column, String definition) {
        if (!hasColumn(db, table, column)) {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(1))) {
                    return true;
                }
            }
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void splitMixedBackups(SQLiteDatabase db) {
        ArrayList<ManagedBackup> backups = new ArrayList<ManagedBackup>();
        Cursor cursor = null;
        try {
            cursor = db.query(BACKUPS, backupColumns(), null, null, null, null,
                    "created_at ASC, _id ASC");
            while (cursor.moveToNext()) {
                backups.add(backupFromCursor(cursor));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        for (ManagedBackup backup : backups) {
            ArrayList<String> packages = sourcePackageListForBackup(db, backup.id);
            if (packages.size() <= 1) {
                continue;
            }
            String firstPackage = packages.get(0);
            renameBackupForPackage(db, backup, firstPackage);
            for (int i = 1; i < packages.size(); i++) {
                String packageName = packages.get(i);
                long newBackupId = insertBackup(db, backupNameForPackage(backup.name, packageName),
                        backup.createdAt + i, packageName, 1, countTabsForSource(db, backup.id, packageName),
                        0, 0, splitNote(backup.note, backup.name));
                ContentValues move = new ContentValues();
                move.put("backup_id", newBackupId);
                db.update(TABS, move, "backup_id=? AND source_package=?",
                        new String[]{String.valueOf(backup.id), packageName});
                updateBackupCounts(db, newBackupId);
            }
            updateBackupSources(db, backup.id);
            updateBackupSummary(db, backup.id, firstPackage, 1,
                    countTabsForSource(db, backup.id, firstPackage), 0, 0);
            updateBackupCounts(db, backup.id);
        }
    }

    private void renameBackupForPackage(SQLiteDatabase db, ManagedBackup backup, String packageName) {
        ContentValues values = new ContentValues();
        values.put("name", backupNameForPackage(backup.name, packageName));
        db.update(BACKUPS, values, "_id=?", new String[]{String.valueOf(backup.id)});
    }

    private void refreshAllBackupCaches(SQLiteDatabase db) {
        Cursor cursor = null;
        try {
            cursor = db.query(BACKUPS, new String[]{"_id"}, null, null, null, null, null);
            while (cursor.moveToNext()) {
                long backupId = cursor.getLong(0);
                updateBackupSources(db, backupId);
                updateBackupCounts(db, backupId);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void updateBackupCounts(SQLiteDatabase db, long backupId) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*), "
                            + "SUM(CASE WHEN deleted=0 THEN 1 ELSE 0 END), "
                            + "SUM(CASE WHEN deleted=1 THEN 1 ELSE 0 END) "
                            + "FROM " + TABS + " WHERE backup_id=?",
                    new String[]{String.valueOf(backupId)});
            ContentValues values = new ContentValues();
            if (cursor.moveToFirst()) {
                values.put("total_tabs", intOrZero(cursor, 0));
                values.put("active_tabs", intOrZero(cursor, 1));
                values.put("deleted_tabs", intOrZero(cursor, 2));
            } else {
                values.put("total_tabs", 0);
                values.put("active_tabs", 0);
                values.put("deleted_tabs", 0);
            }
            db.update(BACKUPS, values, "_id=?", new String[]{String.valueOf(backupId)});
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private LinkedHashSet<Long> backupIdsForTabs(SQLiteDatabase db, List<Long> ids) {
        LinkedHashSet<Long> backupIds = new LinkedHashSet<Long>();
        if (ids == null) {
            return backupIds;
        }
        Cursor cursor = null;
        try {
            for (Long id : ids) {
                if (id == null) {
                    continue;
                }
                if (cursor != null) {
                    cursor.close();
                    cursor = null;
                }
                cursor = db.query(TABS, new String[]{"backup_id"}, "_id=?",
                        new String[]{String.valueOf(id)}, null, null, null, "1");
                if (cursor.moveToFirst()) {
                    backupIds.add(cursor.getLong(0));
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return backupIds;
    }

    private int countTabsForSource(SQLiteDatabase db, long backupId, String sourcePackage) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABS
                            + " WHERE backup_id=? AND source_package=?",
                    new String[]{String.valueOf(backupId), sourcePackage});
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private boolean hasTable(SQLiteDatabase db, String table) {
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

    private static String[] backupColumns() {
        return new String[]{
                "_id", "name", "created_at", "source_packages", "source_count",
                "inserted", "updated", "skipped", "note", "deleted",
                "total_tabs", "active_tabs", "deleted_tabs"
        };
    }

    private static String backupNameForPackage(String baseName, String packageName) {
        String suffix = safePackageName(packageName);
        String base = baseName == null || baseName.trim().length() == 0
                ? "backup"
                : baseName.trim();
        return base.endsWith("-" + suffix) ? base : base + "-" + suffix;
    }

    private static String safePackageName(String packageName) {
        return packageName == null ? "unknown" : packageName.replace('.', '_');
    }

    private static String splitNote(String note, String originalName) {
        String prefix = "Split from " + (originalName == null ? "mixed backup" : originalName);
        String cleanNote = clean(note);
        return cleanNote.length() == 0 ? prefix : prefix + "\n" + cleanNote;
    }

    private static int intOrZero(Cursor cursor, int index) {
        return cursor == null || cursor.isNull(index) ? 0 : cursor.getInt(index);
    }

    private static String limitClause(int limit, int offset) {
        if (limit <= 0) {
            return null;
        }
        int safeOffset = Math.max(0, offset);
        return safeOffset + "," + limit;
    }

    private static String joinPackages(List<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String item : packages) {
            if (item == null || item.length() == 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(item);
        }
        return out.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stamp(long time) {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(time));
    }

    static final class ImportResult {
        final long backupId;
        final String sourcePackage;
        int seen;
        int inserted;
        int updated;
        int restored;
        int skipped;

        ImportResult(long backupId, String sourcePackage) {
            this.backupId = backupId;
            this.sourcePackage = sourcePackage;
        }

        String summary() {
            return sourcePackage + ": seen=" + seen
                    + " inserted=" + inserted
                    + " updated=" + updated
                    + " skipped=" + skipped;
        }
    }

    static final class Stats {
        int total;
        int active;
        int deleted;
        int backups;
        int activeBackups;
        int deletedBackups;

        String summary() {
            return String.format(Locale.US,
                    "backups=%d activeBackups=%d total=%d active=%d deleted=%d",
                    backups, activeBackups, total, active, deleted);
        }
    }
}
