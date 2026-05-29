package com.viatabs.agent;

final class ManagedBackup {
    final long id;
    final String name;
    final long createdAt;
    final String sourcePackages;
    final int sourceCount;
    final int inserted;
    final int updated;
    final int skipped;
    final String note;
    final boolean deleted;
    final int totalTabs;
    final int activeTabs;
    final int deletedTabs;

    ManagedBackup(long id, String name, long createdAt, String sourcePackages, int sourceCount,
                  int inserted, int updated, int skipped, String note, boolean deleted,
                  int totalTabs, int activeTabs, int deletedTabs) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.sourcePackages = sourcePackages;
        this.sourceCount = sourceCount;
        this.inserted = inserted;
        this.updated = updated;
        this.skipped = skipped;
        this.note = note;
        this.deleted = deleted;
        this.totalTabs = totalTabs;
        this.activeTabs = activeTabs;
        this.deletedTabs = deletedTabs;
    }
}
