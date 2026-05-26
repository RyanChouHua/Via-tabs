package com.viatabs.agent;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView logView;
    private TextView statusView;
    private CheckBox exportSwitch;
    private CheckBox tabExportSwitch;
    private CheckBox bookmarkImportSwitch;

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
        info.setText("LSPosed 模块安装后，请在 LSPosed 中启用并勾选 mark.via / mark.via.gp。\n"
                + "Via 内只保留导出书签按钮，导出目录：/storage/emulated/0/Download/ViaTabsAgent/\n"
                + "书签文件名：via-日期-数量.html / via-日期-数量.json\n"
                + "日志文件：/storage/emulated/0/Download/ViaTabsAgent/agent-log.txt");
        info.setTextSize(14f);
        info.setPadding(0, 18, 0, 18);
        root.addView(info, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextSize(14f);
        statusView.setPadding(0, 0, 0, 12);
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        exportSwitch = new CheckBox(this);
        exportSwitch.setText("启用 Via 内导出书签按钮");
        exportSwitch.setChecked(AgentStore.isExportEnabled(this));
        exportSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean enabled = exportSwitch.isChecked();
                AgentStore.setExportEnabled(MainActivity.this, enabled);
                AgentStore.appendLog(MainActivity.this, "导出开关: " + (enabled ? "开启" : "关闭"));
                refreshStatus();
                refreshLog();
                Toast.makeText(MainActivity.this, enabled ? "已启用导出" : "已关闭导出", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(exportSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        tabExportSwitch = new CheckBox(this);
        tabExportSwitch.setText("标签导出功能");
        tabExportSwitch.setChecked(AgentStore.isTabExportEnabled(this));
        tabExportSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean enabled = tabExportSwitch.isChecked();
                AgentStore.setTabExportEnabled(MainActivity.this, enabled);
                AgentStore.appendLog(MainActivity.this, "标签导出功能: " + (enabled ? "开启" : "关闭"));
                refreshStatus();
                refreshLog();
                Toast.makeText(MainActivity.this, enabled ? "已启用标签导出" : "已关闭标签导出", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(tabExportSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        bookmarkImportSwitch = new CheckBox(this);
        bookmarkImportSwitch.setText("标签导入到书签");
        bookmarkImportSwitch.setChecked(AgentStore.isBookmarkImportEnabled(this));
        bookmarkImportSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean enabled = bookmarkImportSwitch.isChecked();
                AgentStore.setBookmarkImportEnabled(MainActivity.this, enabled);
                AgentStore.appendLog(MainActivity.this, "标签导入到书签: " + (enabled ? "开启" : "关闭"));
                refreshStatus();
                refreshLog();
                Toast.makeText(MainActivity.this, enabled ? "已启用导入书签" : "已关闭导入书签", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(bookmarkImportSwitch, new LinearLayout.LayoutParams(
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

        Button testExport = new Button(this);
        testExport.setText("测试导出");
        testExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTestExport();
            }
        });
        root.addView(testExport, new LinearLayout.LayoutParams(
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
        refreshStatus();
        refreshLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshLog();
    }

    private void refreshStatus() {
        boolean enabled = AgentStore.isExportEnabled(this);
        boolean tabExport = AgentStore.isTabExportEnabled(this);
        boolean bookmarkImport = AgentStore.isBookmarkImportEnabled(this);
        StringBuilder status = new StringBuilder();
        status.append("模块版本: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        status.append("mark.via: ").append(isPackageInstalled("mark.via") ? "已安装" : "未安装").append("\n");
        status.append("mark.via.gp: ").append(isPackageInstalled("mark.via.gp") ? "已安装" : "未安装").append("\n");
        status.append("按钮开关: ").append(enabled ? "开启" : "关闭").append("\n");
        status.append("标签导出功能: ").append(tabExport ? "开启" : "关闭").append("\n");
        status.append("标签导入到书签: ").append(bookmarkImport ? "开启" : "关闭").append("\n");
        status.append("注入状态: 查看日志中是否出现 module attached in mark.via / mark.via.gp");
        statusView.setText(status.toString());
        if (exportSwitch != null) {
            exportSwitch.setChecked(enabled);
        }
        if (tabExportSwitch != null) {
            tabExportSwitch.setChecked(tabExport);
        }
        if (bookmarkImportSwitch != null) {
            bookmarkImportSwitch.setChecked(bookmarkImport);
        }
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private void runTestExport() {
        try {
            String fileName = "via-test-" + System.currentTimeMillis() + "-1.json";
            String payload = "{\n"
                    + "  \"source\": \"module-test-export\",\n"
                    + "  \"message\": \"如果这个文件存在，说明模块 App 写入 Download 正常\"\n"
                    + "}\n";
            String path = AgentStore.writeDownloadFile(this, fileName, payload);
            AgentStore.appendLog(this, "测试导出成功: " + path);
            refreshLog();
            Toast.makeText(this, "测试导出成功", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            AgentStore.appendLog(this, "测试导出失败: " + t);
            refreshLog();
            Toast.makeText(this, "测试导出失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshLog() {
        String log = AgentStore.readLog(this);
        if (log.length() == 0) {
            log = "暂无日志。\n\n"
                    + "操作顺序:\n"
                    + "1. 在 LSPosed 启用 ViaTabsAgent，并勾选 Via。\n"
                    + "2. 强制停止 Via 后重新打开。\n"
                    + "3. 在 Via 内点击“书签”按钮导出。\n"
                    + "4. 回到这里点击“刷新日志”。";
        } else if (!log.contains("module attached in")
                && !log.contains("loaded in mark.via")
                && !log.contains("loaded in mark.via.gp")) {
            log = "未检测到 Via 注入日志。\n"
                    + "如果保存后仍只看到“打开模块界面”，请在 LSPosed 中确认已启用模块，"
                    + "作用域勾选 mark.via / mark.via.gp，并强制停止 Via 后重新打开。\n\n"
                    + log;
        }
        logView.setText(log);
    }
}
