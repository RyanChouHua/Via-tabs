package com.viatabs.agent;

final class TabRecord {
    final int index;
    final String title;
    final String url;

    TabRecord(int index, String title, String url) {
        this.index = index;
        this.title = title;
        this.url = url;
    }
}
