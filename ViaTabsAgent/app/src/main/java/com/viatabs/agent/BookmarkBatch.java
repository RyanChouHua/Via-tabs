package com.viatabs.agent;

import java.util.LinkedHashMap;
import java.util.List;

final class BookmarkBatch {
    final String folderName;
    final String fileBaseName;
    final long addDate;
    final int bookmarkCount;
    final boolean groupByDomain;
    final LinkedHashMap<String, List<TabRecord>> domainGroups;

    BookmarkBatch(String folderName, String fileBaseName, long addDate, int bookmarkCount,
                  boolean groupByDomain, LinkedHashMap<String, List<TabRecord>> domainGroups) {
        this.folderName = folderName;
        this.fileBaseName = fileBaseName;
        this.addDate = addDate;
        this.bookmarkCount = bookmarkCount;
        this.groupByDomain = groupByDomain;
        this.domainGroups = domainGroups;
    }
}
