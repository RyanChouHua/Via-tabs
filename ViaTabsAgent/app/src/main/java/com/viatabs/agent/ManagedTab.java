package com.viatabs.agent;

final class ManagedTab {
    final long id;
    final long backupId;
    final String sourcePackage;
    final String title;
    final String url;
    final String urlKey;
    final String domain;
    final String note;
    final boolean deleted;
    final long sourceDbModified;
    final long importTime;
    final long lastSeenTime;

    ManagedTab(long id, long backupId, String sourcePackage, String title, String url, String urlKey,
               String domain, String note, boolean deleted, long sourceDbModified,
               long importTime, long lastSeenTime) {
        this.id = id;
        this.backupId = backupId;
        this.sourcePackage = sourcePackage;
        this.title = title;
        this.url = url;
        this.urlKey = urlKey;
        this.domain = domain;
        this.note = note;
        this.deleted = deleted;
        this.sourceDbModified = sourceDbModified;
        this.importTime = importTime;
        this.lastSeenTime = lastSeenTime;
    }
}
