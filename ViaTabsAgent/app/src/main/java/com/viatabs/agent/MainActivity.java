package com.viatabs.agent;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("ViaTabsAgent");
        title.setTextSize(22f);
        title.setGravity(Gravity.LEFT);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(this);
        info.setText("LSPosed 模块已安装后，请在 LSPosed 中启用并勾选 mark.via / mark.via.gp。\n"
                + "保存文件目录：/storage/emulated/0/Download/ViaTabsAgent/\n"
                + "保存文件名：saved-bookmarks-时间戳.json\n"
                + "日志文件目标路径：/storage/emulated/0/Download/ViaTabsAgent/agent-log.txt");
        info.setTextSize(14f);
        info.setPadding(0, 18, 0, 18);
        root.addView(info, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button refresh = new Button(this);
        refresh.setText("刷新日志");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshLog();
            }
        });
        buttons.addView(refresh, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button clear = new Button(this);
        clear.setText("清空日志");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AgentStore.clearLog(MainActivity.this);
                refreshLog();
                Toast.makeText(MainActivity.this, "日志已清空", Toast.LENGTH_SHORT).show();
            }
        });
        buttons.addView(clear, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        logView = new TextView(this);
        logView.setTextSize(12f);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, 18, 0, 18);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        AgentStore.appendLog(this, "打开模块界面");
        refreshLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLog();
    }

    private void refreshLog() {
        String log = AgentStore.readLog(this);
        if (log.length() == 0) {
            log = "暂无日志。\n\n"
                    + "操作顺序：\n"
                    + "1. 在 LSPosed 启用 ViaTabsAgent 并勾选 Via。\n"
                    + "2. 强制停止 Via 后重新打开。\n"
                    + "3. 在 Via 内点击“标签”按钮保存。\n"
                    + "4. 回到这里点“刷新日志”。";
        }
        logView.setText(log);
    }
}
