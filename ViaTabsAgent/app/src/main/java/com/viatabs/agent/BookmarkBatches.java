package com.viatabs.agent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class BookmarkBatches {
    private static final String DEFAULT_FOLDER_PREFIX = "\u4e66\u7b7e";
    private static final String DEFAULT_FILE_PREFIX = "via";
    private static final String DEFAULT_FOLDER_NAME = "ViaTabsAgent";

    private BookmarkBatches() {
    }

    static BookmarkBatch create(List<TabRecord> tabs, boolean groupByDomain) {
        return create(tabs, groupByDomain, DEFAULT_FOLDER_PREFIX);
    }

    static BookmarkBatch create(List<TabRecord> tabs, boolean groupByDomain, String folderPrefix) {
        long now = System.currentTimeMillis();
        int bookmarkCount = countBookmarkable(tabs);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now));
        return create(cleanName(folderPrefix, DEFAULT_FOLDER_PREFIX) + "-" + stamp + "-" + bookmarkCount,
                DEFAULT_FILE_PREFIX + "-" + stamp + "-" + bookmarkCount,
                now / 1000L,
                tabs,
                groupByDomain);
    }

    static BookmarkBatch create(String folderName, String fileBaseName, long addDate,
                                List<TabRecord> tabs, boolean groupByDomain) {
        return new BookmarkBatch(
                cleanName(folderName, DEFAULT_FOLDER_NAME),
                cleanName(fileBaseName, DEFAULT_FILE_PREFIX),
                addDate,
                countBookmarkable(tabs),
                groupByDomain,
                groupByDomain(tabs));
    }

    static LinkedHashMap<String, List<TabRecord>> groupByDomain(List<TabRecord> tabs) {
        LinkedHashMap<String, List<TabRecord>> groups = new LinkedHashMap<String, List<TabRecord>>();
        if (tabs == null) {
            return groups;
        }
        for (TabRecord tab : tabs) {
            if (tab == null || !TabUrls.isBookmarkable(tab.url)) {
                continue;
            }
            String groupName = TabUrls.domainGroupName(tab.url);
            List<TabRecord> group = groups.get(groupName);
            if (group == null) {
                group = new ArrayList<TabRecord>();
                groups.put(groupName, group);
            }
            group.add(tab);
        }
        return groups;
    }

    static int countBookmarkable(List<TabRecord> tabs) {
        int count = 0;
        if (tabs == null) {
            return count;
        }
        for (TabRecord tab : tabs) {
            if (tab != null && TabUrls.isBookmarkable(tab.url)) {
                count++;
            }
        }
        return count;
    }

    private static String cleanName(String value, String fallback) {
        String clean = value == null ? "" : value.trim();
        return clean.length() == 0 ? fallback : clean;
    }
}
