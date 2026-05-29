package com.viatabs.agent;

import android.os.Bundle;
import android.os.Parcelable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

final class SnapshotJson {
    private SnapshotJson() {
    }

    static JSONObject base(String source, String packageName) throws Exception {
        JSONObject root = new JSONObject();
        root.put("source", source);
        root.put("packageName", packageName == null ? JSONObject.NULL : packageName);
        root.put("timestamp", System.currentTimeMillis());
        return root;
    }

    static JSONArray tabRecords(List<TabRecord> records, BookmarkHtml.TitleProvider titleProvider)
            throws Exception {
        JSONArray tabs = new JSONArray();
        for (TabRecord record : records) {
            JSONObject tab = new JSONObject();
            tab.put("index", record.index);
            tab.put("title", titleProvider == null ? "" : titleProvider.titleFor(record));
            tab.put("url", record.url);
            tabs.put(tab);
        }
        return tabs;
    }

    static JSONArray bundleUrls(Parcelable[] bundles) throws Exception {
        JSONArray tabs = new JSONArray();
        for (int i = 0; i < bundles.length; i++) {
            JSONObject tab = new JSONObject();
            tab.put("index", i);
            if (bundles[i] instanceof Bundle) {
                tab.put("url", ((Bundle) bundles[i]).getString("url", null));
            } else {
                tab.put("url", JSONObject.NULL);
            }
            tabs.put(tab);
        }
        return tabs;
    }

    static JSONArray objectBundleUrls(Object[] bundles) throws Exception {
        JSONArray tabs = new JSONArray();
        for (int i = 0; i < bundles.length; i++) {
            JSONObject tab = new JSONObject();
            tab.put("index", i);
            if (bundles[i] instanceof Bundle) {
                tab.put("url", ((Bundle) bundles[i]).getString("url", null));
            } else {
                tab.put("url", JSONObject.NULL);
            }
            tabs.put(tab);
        }
        return tabs;
    }

    static JSONArray stringUrls(List<String> urls) throws Exception {
        JSONArray tabs = new JSONArray();
        for (int i = 0; i < urls.size(); i++) {
            JSONObject tab = new JSONObject();
            tab.put("index", i);
            tab.put("url", urls.get(i));
            tabs.put(tab);
        }
        return tabs;
    }
}
