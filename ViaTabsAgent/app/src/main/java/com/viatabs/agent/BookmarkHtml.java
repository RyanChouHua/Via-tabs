package com.viatabs.agent;

import java.util.List;
import java.util.Map;

final class BookmarkHtml {
    interface TitleProvider {
        String titleFor(TabRecord tab);
    }

    private BookmarkHtml() {
    }

    static String toNetscapeBookmarksHtml(BookmarkBatch batch, List<TabRecord> tabs,
                                          TitleProvider titleProvider) {
        String safeFolder = batch.folderName == null || batch.folderName.trim().length() == 0
                ? "ViaTabsAgent"
                : batch.folderName.trim();
        long addDate = batch.addDate;
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n");
        html.append("<!-- This is an automatically generated file.\n");
        html.append("     It will be read and overwritten.\n");
        html.append("     DO NOT EDIT! -->\n");
        html.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n");
        html.append("<TITLE>Bookmarks</TITLE>\n");
        html.append("<H1>Bookmarks</H1>\n");
        html.append("<DL><p>\n");
        html.append("  <DT><H3 ADD_DATE=\"").append(addDate).append("\">")
                .append(escape(safeFolder)).append("</H3>\n");
        html.append("  <DL><p>\n");
        if (batch.groupByDomain) {
            for (Map.Entry<String, List<TabRecord>> entry : batch.domainGroups.entrySet()) {
                html.append("    <DT><H3 ADD_DATE=\"").append(addDate).append("\">")
                        .append(escape(entry.getKey())).append("</H3>\n");
                html.append("    <DL><p>\n");
                appendBookmarkLinks(html, entry.getValue(), addDate, "      ", titleProvider);
                html.append("    </DL><p>\n");
            }
        } else {
            appendBookmarkLinks(html, tabs, addDate, "    ", titleProvider);
        }
        html.append("  </DL><p>\n");
        html.append("</DL><p>\n");
        return html.toString();
    }

    static String unescape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static void appendBookmarkLinks(StringBuilder html, List<TabRecord> tabs, long addDate,
                                            String indent, TitleProvider titleProvider) {
        if (tabs == null) {
            return;
        }
        for (TabRecord tab : tabs) {
            if (tab == null || !TabUrls.isBookmarkable(tab.url)) {
                continue;
            }
            html.append(indent).append("<DT><A HREF=\"").append(escape(tab.url))
                    .append("\" ADD_DATE=\"").append(addDate).append("\">")
                    .append(escape(titleProvider == null ? "" : titleProvider.titleFor(tab))).append("</A>\n");
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '&') {
                out.append("&amp;");
            } else if (ch == '<') {
                out.append("&lt;");
            } else if (ch == '>') {
                out.append("&gt;");
            } else if (ch == '"') {
                out.append("&quot;");
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
