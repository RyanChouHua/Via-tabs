package com.viatabs.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ExportReceiver extends BroadcastReceiver {
    static final String ACTION_WRITE_FILE = "com.viatabs.agent.WRITE_FILE";
    static final String ACTION_APPEND_LOG = "com.viatabs.agent.APPEND_LOG";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) {
            return;
        }
        try {
            if (ACTION_WRITE_FILE.equals(intent.getAction())) {
                String fileName = intent.getStringExtra(ExportProvider.EXTRA_FILE_NAME);
                String payload = intent.getStringExtra(ExportProvider.EXTRA_PAYLOAD);
                if (fileName == null || payload == null) {
                    throw new IllegalArgumentException("missing fileName or payload");
                }
                String path = AgentStore.writeDownloadFile(context, fileName, payload);
                AgentStore.appendLog(context, "广播导出成功: " + path);
            } else if (ACTION_APPEND_LOG.equals(intent.getAction())) {
                AgentStore.appendLog(context, intent.getStringExtra(ExportProvider.EXTRA_MESSAGE));
            }
        } catch (Throwable t) {
            AgentStore.appendLog(context, "广播处理失败: " + t);
        }
    }
}
