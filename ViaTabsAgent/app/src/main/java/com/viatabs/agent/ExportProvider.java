package com.viatabs.agent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public class ExportProvider extends ContentProvider {
    static final String METHOD_WRITE_FILE = "writeFile";
    static final String METHOD_APPEND_LOG = "appendLog";
    static final String METHOD_READ_LOG = "readLog";
    static final String METHOD_CLEAR_LOG = "clearLog";
    static final String METHOD_GET_EXPORT_ENABLED = "getExportEnabled";
    static final String METHOD_SET_EXPORT_ENABLED = "setExportEnabled";
    static final String METHOD_GET_SETTINGS = "getSettings";
    static final String METHOD_SET_SAVE_TO_VIA_ENABLED = "setSaveToViaEnabled";
    static final String METHOD_SET_EXPORT_FILE_ENABLED = "setExportFileEnabled";
    static final String EXTRA_FILE_NAME = "fileName";
    static final String EXTRA_PAYLOAD = "payload";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_PATH = "path";
    static final String EXTRA_LOG = "log";
    static final String EXTRA_ENABLED = "enabled";
    static final String EXTRA_PANEL_ENABLED = "panelEnabled";
    static final String EXTRA_SAVE_TO_VIA_ENABLED = "saveToViaEnabled";
    static final String EXTRA_EXPORT_FILE_ENABLED = "exportFileEnabled";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        try {
            if (METHOD_WRITE_FILE.equals(method)) {
                String fileName = extras == null ? null : extras.getString(EXTRA_FILE_NAME);
                String payload = extras == null ? null : extras.getString(EXTRA_PAYLOAD);
                if (fileName == null || payload == null) {
                    throw new IllegalArgumentException("missing fileName or payload");
                }
                String path = AgentStore.writeDownloadFile(getContext(), fileName, payload);
                AgentStore.appendLog(getContext(), "导出成功: " + path);
                result.putString(EXTRA_PATH, path);
            } else if (METHOD_APPEND_LOG.equals(method)) {
                String message = extras == null ? arg : extras.getString(EXTRA_MESSAGE, arg);
                AgentStore.appendLog(getContext(), message);
            } else if (METHOD_READ_LOG.equals(method)) {
                result.putString(EXTRA_LOG, AgentStore.readLog(getContext()));
            } else if (METHOD_CLEAR_LOG.equals(method)) {
                AgentStore.clearLog(getContext());
            } else if (METHOD_GET_EXPORT_ENABLED.equals(method)) {
                result.putBoolean(EXTRA_ENABLED, AgentStore.isExportEnabled(getContext()));
            } else if (METHOD_SET_EXPORT_ENABLED.equals(method)) {
                boolean enabled = extras == null || extras.getBoolean(EXTRA_ENABLED, true);
                AgentStore.setExportEnabled(getContext(), enabled);
                AgentStore.appendLog(getContext(), "导出开关: " + (enabled ? "开启" : "关闭"));
                result.putBoolean(EXTRA_ENABLED, enabled);
            } else if (METHOD_GET_SETTINGS.equals(method)) {
                result.putBoolean(EXTRA_PANEL_ENABLED, AgentStore.isExportEnabled(getContext()));
                result.putBoolean(EXTRA_SAVE_TO_VIA_ENABLED, AgentStore.isSaveToViaEnabled(getContext()));
                result.putBoolean(EXTRA_EXPORT_FILE_ENABLED, AgentStore.isExportFileEnabled(getContext()));
            } else if (METHOD_SET_SAVE_TO_VIA_ENABLED.equals(method)) {
                boolean enabled = extras == null || extras.getBoolean(EXTRA_SAVE_TO_VIA_ENABLED, true);
                AgentStore.setSaveToViaEnabled(getContext(), enabled);
                AgentStore.appendLog(getContext(), "保存到 Via 书签: " + (enabled ? "开启" : "关闭"));
                result.putBoolean(EXTRA_SAVE_TO_VIA_ENABLED, enabled);
            } else if (METHOD_SET_EXPORT_FILE_ENABLED.equals(method)) {
                boolean enabled = extras == null || extras.getBoolean(EXTRA_EXPORT_FILE_ENABLED, true);
                AgentStore.setExportFileEnabled(getContext(), enabled);
                AgentStore.appendLog(getContext(), "导出文件: " + (enabled ? "开启" : "关闭"));
                result.putBoolean(EXTRA_EXPORT_FILE_ENABLED, enabled);
            }
        } catch (Throwable t) {
            AgentStore.appendLog(getContext(), method + " 失败: " + t);
            result.putString("error", String.valueOf(t));
        }
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
