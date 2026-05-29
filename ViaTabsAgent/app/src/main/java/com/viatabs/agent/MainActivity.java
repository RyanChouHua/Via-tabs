package com.viatabs.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int COLOR_BG = Color.rgb(246, 248, 251);
    private static final int COLOR_PANEL = Color.WHITE;
    private static final int COLOR_BORDER = Color.rgb(226, 232, 240);
    private static final int COLOR_TEXT = Color.rgb(17, 24, 39);
    private static final int COLOR_MUTED = Color.rgb(100, 116, 139);
    private static final int COLOR_PRIMARY = Color.rgb(37, 99, 235);
    private static final int COLOR_PRIMARY_DARK = Color.rgb(30, 64, 175);
    private static final int COLOR_DANGER = Color.rgb(220, 38, 38);
    private static final String SOURCE_ALL = "";
    private static final String DOMAIN_ALL = "";
    private static final int BACKUP_PAGE_SIZE = 30;
    private static final int TAG_PAGE_SIZE = 60;

    private TextView statusView;
    private CheckBox domainGroupSwitch;
    private LocalTabStore tabStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tabStore = new LocalTabStore(this);
        setContentView(buildContent());
        AgentStore.appendLog(this, "app opened");
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        if (tabStore != null) {
            tabStore.close();
        }
        super.onDestroy();
    }

    private View buildContent() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(false);
        page.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(18));
        page.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = textView(24f, COLOR_TEXT, true);
        title.setText("ViaTabsAgent");
        root.addView(title, matchWrap());

        TextView subtitle = textView(13f, COLOR_MUTED, false);
        subtitle.setText("终端脚本提取 Via 数据库，应用本地解析和管理标签");
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle, matchWrap());

        statusView = textView(14f, COLOR_TEXT, true);
        statusView.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusView.setBackground(panelBackground(8, COLOR_PANEL, COLOR_BORDER));
        root.addView(statusView, margin(matchWrap(), 0, 0, 0, dp(12)));

        root.addView(settingsPanel(), margin(matchWrap(), 0, 0, 0, dp(12)));
        root.addView(actionPanel(), margin(matchWrap(), 0, 0, 0, dp(12)));
        root.addView(helpPanel(), margin(matchWrap(), 0, 0, 0, dp(12)));
        return page;
    }

    private LinearLayout settingsPanel() {
        LinearLayout panel = panel();
        addSectionTitle(panel, "设置");
        domainGroupSwitch = switchView("导出书签文件时按域名整理");
        domainGroupSwitch.setChecked(AgentStore.isDomainGroupEnabled(this));
        domainGroupSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean enabled = domainGroupSwitch.isChecked();
                AgentStore.setDomainGroupEnabled(MainActivity.this, enabled);
                AgentStore.appendLog(MainActivity.this, "domain grouping " + (enabled ? "enabled" : "disabled"));
                refreshStatus();
            }
        });
        panel.addView(domainGroupSwitch, matchWrap());
        return panel;
    }

    private LinearLayout actionPanel() {
        LinearLayout panel = panel();
        addSectionTitle(panel, "操作");
        panel.addView(actionButton("保存脚本", COLOR_PRIMARY_DARK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePrepareScript();
            }
        }), matchWrap());
        Button parseButton = actionButton("解析数据库", COLOR_PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                parsePreparedDatabases();
            }
        });
        parseButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showParseTargetDialog();
                return true;
            }
        });
        panel.addView(parseButton, margin(matchWrap(), 0, dp(8), 0, 0));
        panel.addView(actionButton("备份管理", COLOR_PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBackupsDialog();
            }
        }), margin(matchWrap(), 0, dp(8), 0, 0));
        panel.addView(actionButton("日志", COLOR_PRIMARY_DARK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogDialog();
            }
        }), margin(matchWrap(), 0, dp(8), 0, 0));
        return panel;
    }

    private LinearLayout helpPanel() {
        LinearLayout panel = panel();
        addSectionTitle(panel, "流程");
        TextView text = textView(13f, COLOR_MUTED, false);
        text.setText("1. 打开本应用一次。\n"
                + "2. 点保存脚本，在手机终端中用 root 执行 prepare-via-all-db.sh。\n"
                + "3. 提取完成后回到应用，点解析数据库。\n"
                + "4. 在备份管理里按国内版/GP 分开管理，导出书签生成 Via 可导入文件。");
        panel.addView(text, matchWrap());
        return panel;
    }

    private void refreshStatus() {
        if (statusView == null) {
            return;
        }
        LocalTabStore.Stats stats = tabStore.stats();
        statusView.setText("ViaTabsAgent " + BuildConfig.VERSION_NAME + "  #" + BuildConfig.VERSION_CODE + "\n"
                + "国内版: " + installedText("mark.via")
                + "   GP版: " + installedText("mark.via.gp") + "\n"
                + "已提取数据库: 国内版 " + preparedText("mark.via")
                + " / GP版 " + preparedText("mark.via.gp") + "\n"
                + "解析范围: " + parseTargetLabel(AgentStore.parseTarget(this)) + "（长按解析数据库设置）\n"
                + "书签文件夹: " + AgentStore.bookmarkFolderPrefix(this) + "-日期-数量\n"
                + "备份: 可用 " + stats.activeBackups + " / 已删 " + stats.deletedBackups
                + " / 总数 " + stats.backups + "\n"
                + "标签: 可用 " + stats.active + " / 已删 " + stats.deleted
                + " / 总数 " + stats.total);
    }

    private void savePrepareScript() {
        try {
            String scriptName = AgentStore.PREPARE_ALL_SCRIPT_FILE;
            AgentStore.WriteResult result = AgentStore.writeDownloadFileDetailed(this,
                    scriptName, readAssetText(scriptName));
            AgentStore.appendLog(this, "prepare script exported: " + result.summary());
            showTextDialog("prepare-via-all-db.sh",
                    "已保存:\n" + result.displayText() + "\n\n"
                            + "在手机终端执行:\n"
                            + "su\n"
                            + "sh /storage/emulated/0/Download/ViaTabsAgent/prepare-via-all-db.sh\n\n"
                            + "完成后回到这里点击解析数据库。",
                    true);
            Toast.makeText(this, "脚本已保存", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            AgentStore.appendLog(this, "prepare script export failed: " + t);
            Toast.makeText(this, "保存脚本失败: " + shortError(t), Toast.LENGTH_LONG).show();
        }
    }

    private String readAssetText(String name) throws Exception {
        InputStream input = getAssets().open(name);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), "UTF-8");
        } finally {
            input.close();
        }
    }

    private void showParseTargetDialog() {
        final String[] values = new String[]{
                AgentStore.PARSE_TARGET_ALL,
                AgentStore.PARSE_TARGET_MARK_VIA,
                AgentStore.PARSE_TARGET_MARK_VIA_GP
        };
        String[] labels = new String[values.length];
        int checked = 0;
        String current = AgentStore.parseTarget(this);
        for (int i = 0; i < values.length; i++) {
            labels[i] = parseTargetLabel(values[i]);
            if (values[i].equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("解析范围")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String target = values[which];
                        AgentStore.setParseTarget(MainActivity.this, target);
                        AgentStore.appendLog(MainActivity.this, "parse target set: " + target);
                        Toast.makeText(MainActivity.this,
                                "解析范围: " + parseTargetLabel(target), Toast.LENGTH_SHORT).show();
                        refreshStatus();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void parsePreparedDatabases() {
        final Context appContext = getApplicationContext();
        final String parseTarget = AgentStore.parseTarget(this);
        Toast.makeText(this, "正在解析数据库", Toast.LENGTH_SHORT).show();
        AgentStore.appendLog(this, "parse prepared db requested: target=" + parseTarget);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<LocalTabStore.ImportResult> results =
                        new ArrayList<LocalTabStore.ImportResult>();
                final ArrayList<String> errors = new ArrayList<String>();
                final ArrayList<String> backupSummaries = new ArrayList<String>();
                try {
                    List<String> packages = preparedPackagesForTarget(appContext, parseTarget);
                    if (packages.isEmpty()) {
                        throw new IllegalStateException("当前解析范围没有已提取数据库: "
                                + parseTargetLabel(parseTarget) + "。请先运行 prepare-via-all-db.sh");
                    }
                    LocalTabStore store = new LocalTabStore(appContext);
                    try {
                        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                                .format(new java.util.Date());
                        for (String packageName : packages) {
                            OfflineViaTabsReader.ParseResult parsed =
                                    OfflineViaTabsReader.parsePrepared(appContext, packageName);
                            ArrayList<String> onePackage = new ArrayList<String>();
                            onePackage.add(packageName);
                            String backupName = "备份-" + stamp + "-" + packageDisplayName(packageName);
                            long backupId = store.createBackup(backupName, onePackage);
                            AgentStore.appendLog(appContext, "backup created: id=" + backupId
                                    + " name=" + backupName + " package=" + packageName);
                            LocalTabStore.ImportResult result = store.importTabs(backupId, packageName,
                                    parsed.tabs, parsed.database.lastModified());
                            ArrayList<LocalTabStore.ImportResult> oneResult =
                                    new ArrayList<LocalTabStore.ImportResult>();
                            oneResult.add(result);
                            store.updateBackupSummary(backupId, oneResult);
                            results.add(result);
                            backupSummaries.add(backupName + " (#" + backupId + "): "
                                    + importResultText(result));
                            AgentStore.appendLog(appContext, "import " + result.summary());
                        }
                        AgentStore.appendLog(appContext, "backups summarized: " + importSummary(results));
                    } finally {
                        store.close();
                    }
                } catch (Throwable t) {
                    errors.add(String.valueOf(t));
                    AgentStore.appendLog(appContext, "parse prepared db failed: " + t);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                        if (!errors.isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    "解析失败: " + shortError(errors.get(0)),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        showTextDialog("解析数据库", "备份:\n" + joinLines(backupSummaries)
                                + "\n\n" + importSummary(results), false);
                        Toast.makeText(MainActivity.this, "解析完成", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "ViaTabsParseDb").start();
    }

    private String importSummary(List<LocalTabStore.ImportResult> results) {
        StringBuilder out = new StringBuilder();
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        for (LocalTabStore.ImportResult result : results) {
            out.append(importResultText(result)).append("\n");
            inserted += result.inserted;
            updated += result.updated;
            skipped += result.skipped;
        }
        out.append("\n新增=").append(inserted)
                .append(" 更新=").append(updated)
                .append(" 跳过=").append(skipped);
        return out.toString();
    }

    private void showBackupsDialog() {
        final BackupsState state = new BackupsState();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));

        state.summary = textView(13f, COLOR_MUTED, false);
        root.addView(state.summary, matchWrap());

        LinearLayout actionRow = horizontalRow();
        state.deletedToggleButton = smallButton("显示已删除", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.includeDeleted = !state.includeDeleted;
                populateBackups(state);
            }
        });
        actionRow.addView(state.deletedToggleButton, weightParam(1f, dp(6)));
        actionRow.addView(smallButton("永久清理", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                purgeDeletedBackups(state);
            }
        }), weightParam(1f, 0));
        root.addView(actionRow, margin(matchWrap(), 0, dp(8), 0, dp(8)));

        state.list = new LinearLayout(this);
        state.list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(state.list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(460)));

        state.dialog = new AlertDialog.Builder(this)
                .setTitle("备份")
                .setView(root)
                .setPositiveButton("关闭", null)
                .create();
        state.dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                populateBackups(state);
            }
        });
        state.dialog.show();
    }

    private void populateBackups(final BackupsState state) {
        if (state.list == null) {
            return;
        }
        state.list.removeAllViews();
        state.visibleBackups.clear();
        state.totalBackups = tabStore.countBackups(state.includeDeleted);
        updateBackupsSummary(state);
        if (state.totalBackups == 0) {
            TextView empty = textView(14f, COLOR_MUTED, false);
            empty.setText("暂无备份。先运行脚本并点击解析数据库。");
            state.list.addView(empty, matchWrap());
            return;
        }
        appendBackupsPage(state);
    }

    private void appendBackupsPage(final BackupsState state) {
        if (state.list == null) {
            return;
        }
        if (state.loadMoreButton != null) {
            state.list.removeView(state.loadMoreButton);
            state.loadMoreButton = null;
        }
        List<ManagedBackup> next = tabStore.listBackups(state.includeDeleted,
                BACKUP_PAGE_SIZE, state.visibleBackups.size());
        state.visibleBackups.addAll(next);
        updateBackupsSummary(state);
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (final ManagedBackup backup : next) {
            LinearLayout item = listItemPanel();

            TextView title = textView(15f, backup.deleted ? COLOR_MUTED : COLOR_TEXT, true);
            title.setText(backup.name + (backup.deleted ? "  已删除" : ""));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            item.addView(title, matchWrap());

            TextView meta = textView(12f, COLOR_MUTED, false);
            meta.setText(sourceLabel(backup.sourcePackages)
                    + " · " + format.format(new java.util.Date(backup.createdAt))
                    + " · 可用 " + backup.activeTabs
                    + " / 已删 " + backup.deletedTabs
                    + " / 共 " + backup.totalTabs
                    + " · 新增 " + backup.inserted
                    + " / 更新 " + backup.updated
                    + " / 跳过 " + backup.skipped);
            item.addView(meta, matchWrap());
            if (backup.note != null && backup.note.trim().length() > 0) {
                TextView note = textView(12f, COLOR_MUTED, false);
                note.setText("备注: " + backup.note.trim());
                note.setSingleLine(true);
                note.setEllipsize(TextUtils.TruncateAt.END);
                item.addView(note, matchWrap());
            }

            LinearLayout actions = horizontalRow();
            actions.addView(listButton("进入", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTagsDialog(backup);
                }
            }), weightParam(1f, dp(6)));
            actions.addView(listButton("导出", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exportBackupHtml(backup);
                }
            }), weightParam(1f, dp(6)));
            actions.addView(listButton("备注", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editBackupNote(state, backup);
                }
            }), weightParam(1f, dp(6)));

            Button delete = listButton(backup.deleted ? "恢复备份" : "删除备份", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int changed = tabStore.setBackupDeleted(backup.id, !backup.deleted);
                    AgentStore.appendLog(MainActivity.this,
                            (backup.deleted ? "restore backup " : "delete backup ")
                                    + backup.id + " changed=" + changed);
                    refreshStatus();
                    populateBackups(state);
                }
            });
            delete.setTextColor(backup.deleted ? COLOR_TEXT : COLOR_DANGER);
            actions.addView(delete, weightParam(1f, 0));
            item.addView(actions, margin(matchWrap(), 0, dp(5), 0, 0));

            state.list.addView(item, margin(matchWrap(), 0, 0, 0, dp(6)));
        }
        if (state.visibleBackups.size() < state.totalBackups) {
            state.loadMoreButton = smallButton("加载更多", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    appendBackupsPage(state);
                }
            });
            state.list.addView(state.loadMoreButton, margin(matchWrap(), 0, 0, 0, dp(8)));
        }
    }

    private void updateBackupsSummary(BackupsState state) {
        if (state.summary == null) {
            return;
        }
        state.summary.setText("显示 " + state.visibleBackups.size() + " / "
                + state.totalBackups + " 个备份，已删除备份: "
                + (state.includeDeleted ? "显示" : "隐藏"));
        if (state.deletedToggleButton != null) {
            state.deletedToggleButton.setText(state.includeDeleted ? "隐藏已删除" : "显示已删除");
        }
    }

    private void editBackupNote(final BackupsState state, final ManagedBackup backup) {
        final EditText note = new EditText(this);
        note.setSingleLine(false);
        note.setMinLines(3);
        note.setText(backup.note);
        new AlertDialog.Builder(this)
                .setTitle("备份备注")
                .setView(note)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int changed = tabStore.updateBackupNote(backup.id, note.getText().toString());
                        AgentStore.appendLog(MainActivity.this,
                                "edit backup note " + backup.id + " changed=" + changed);
                        populateBackups(state);
                    }
                })
                .show();
    }

    private void purgeDeletedBackups(final BackupsState state) {
        new AlertDialog.Builder(this)
                .setTitle("清理已删除备份？")
                .setMessage("会永久删除本地库中标记为已删除的备份和其中标签，不影响 Via。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int count = tabStore.purgeDeletedBackups();
                        AgentStore.appendLog(MainActivity.this, "purge deleted backups count=" + count);
                        refreshStatus();
                        populateBackups(state);
                    }
                })
                .show();
    }

    private void showTagsDialog(ManagedBackup backup) {
        final TagsState state = new TagsState();
        state.backupId = backup.id;
        state.backupName = backup.name;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));

        state.summary = textView(12f, COLOR_MUTED, false);
        root.addView(state.summary, matchWrap());

        LinearLayout filterRow = horizontalRow();
        state.queryInput = new EditText(this);
        state.queryInput.setSingleLine(true);
        state.queryInput.setHint("搜索标题、网址、备注");
        state.queryInput.setTextSize(12f);
        state.queryInput.setMinHeight(0);
        state.queryInput.setMinimumHeight(0);
        state.queryInput.setPadding(dp(6), 0, dp(6), 0);
        state.queryInput.setIncludeFontPadding(false);
        filterRow.addView(state.queryInput, new LinearLayout.LayoutParams(0, dp(32), 1f));
        filterRow.addView(smallButton("搜索", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.query = state.queryInput.getText().toString();
                populateTags(state);
            }
        }), new LinearLayout.LayoutParams(dp(64), dp(32)));
        root.addView(filterRow, margin(matchWrap(), 0, dp(5), 0, 0));

        LinearLayout optionRow = horizontalRow();
        optionRow.addView(smallButton("新增", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addManualTab(state);
            }
        }), weightParam(1f, dp(6)));
        optionRow.addView(smallButton("域名", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDomainFilter(state);
            }
        }), weightParam(1f, dp(6)));
        state.deletedToggleButton = smallButton("显示已删除", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.includeDeleted = !state.includeDeleted;
                populateTags(state);
            }
        });
        optionRow.addView(state.deletedToggleButton, weightParam(1f, 0));
        root.addView(optionRow, margin(matchWrap(), 0, dp(6), 0, 0));

        LinearLayout actionRow = horizontalRow();
        actionRow.addView(smallButton("选已加载", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.selected.clear();
                for (ManagedTab tab : state.visibleTabs) {
                    state.selected.add(tab.id);
                }
                updateTagsSummary(state);
            }
        }), weightParam(1f, dp(6)));
        actionRow.addView(smallButton("清空", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.selected.clear();
                updateTagsSummary(state);
            }
        }), weightParam(1f, dp(6)));
        actionRow.addView(smallButton("导出选中", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportSelectedHtml(state);
            }
        }), weightParam(1f, 0));
        root.addView(actionRow, margin(matchWrap(), 0, dp(6), 0, 0));

        LinearLayout deleteRow = horizontalRow();
        Button delete = smallButton("删除选中", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSelectedDeleted(state, true);
            }
        });
        delete.setTextColor(COLOR_DANGER);
        deleteRow.addView(delete, weightParam(1f, dp(6)));
        deleteRow.addView(smallButton("恢复选中", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSelectedDeleted(state, false);
            }
        }), weightParam(1f, dp(6)));
        deleteRow.addView(smallButton("永久清理", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                purgeDeleted(state);
            }
        }), weightParam(1f, 0));
        root.addView(deleteRow, margin(matchWrap(), 0, dp(6), 0, dp(6)));

        state.list = new LinearLayout(this);
        state.list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(state.list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(440)));

        state.dialog = new AlertDialog.Builder(this)
                .setTitle("标签: " + backup.name)
                .setView(root)
                .setPositiveButton("关闭", null)
                .create();
        state.dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                populateTags(state);
            }
        });
        state.dialog.show();
    }

    private void populateTags(final TagsState state) {
        if (state.list == null) {
            return;
        }
        state.list.removeAllViews();
        state.visibleTabs.clear();
        state.totalTabs = tabStore.countTabs(state.backupId, SOURCE_ALL,
                state.domainFilter, state.query, state.includeDeleted);
        updateTagsSummary(state);
        if (state.totalTabs == 0) {
            TextView empty = textView(14f, COLOR_MUTED, false);
            empty.setText("暂无标签。先运行脚本并点击解析数据库。");
            state.list.addView(empty, matchWrap());
            return;
        }
        appendTagsPage(state);
    }

    private void appendTagsPage(final TagsState state) {
        if (state.list == null) {
            return;
        }
        if (state.loadMoreButton != null) {
            state.list.removeView(state.loadMoreButton);
            state.loadMoreButton = null;
        }
        List<ManagedTab> next = tabStore.listTabs(state.backupId, SOURCE_ALL,
                state.domainFilter, state.query, state.includeDeleted,
                TAG_PAGE_SIZE, state.visibleTabs.size());
        state.visibleTabs.addAll(next);
        updateTagsSummary(state);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (final ManagedTab tab : next) {
            LinearLayout item = listItemPanel();

            CheckBox selected = compactCheckBox(trimMiddle(tab.title, 80)
                    + (tab.deleted ? "  已删除" : ""));
            selected.setChecked(state.selected.contains(tab.id));
            selected.setTextColor(tab.deleted ? COLOR_MUTED : COLOR_TEXT);
            selected.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (((CheckBox) v).isChecked()) {
                        state.selected.add(tab.id);
                    } else {
                        state.selected.remove(tab.id);
                    }
                    updateTagsSummary(state);
                }
            });
            item.addView(selected, matchWrap());

            TextView meta = textView(12f, COLOR_MUTED, false);
            meta.setText(safeText(tab.domain, "无域名")
                    + " · " + format.format(new java.util.Date(tab.lastSeenTime)));
            item.addView(meta, matchWrap());

            TextView url = textView(12f, COLOR_MUTED, false);
            url.setText(tab.url);
            url.setSingleLine(true);
            url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            item.addView(url, matchWrap());

            if (tab.note != null && tab.note.trim().length() > 0) {
                TextView note = textView(12f, COLOR_MUTED, false);
                note.setText("备注: " + tab.note.trim());
                note.setSingleLine(true);
                note.setEllipsize(TextUtils.TruncateAt.END);
                item.addView(note, matchWrap());
            }

            LinearLayout actions = horizontalRow();
            actions.addView(listButton("编辑", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editTab(state, tab);
                }
            }), weightParam(1f, dp(6)));
            actions.addView(listButton(tab.deleted ? "恢复" : "删除", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ArrayList<Long> ids = new ArrayList<Long>();
                    ids.add(tab.id);
                    int changed = tabStore.setDeleted(ids, !tab.deleted);
                    AgentStore.appendLog(MainActivity.this,
                            (tab.deleted ? "restore tab " : "delete tab ") + tab.id + " changed=" + changed);
                    refreshStatus();
                    populateTags(state);
                }
            }), weightParam(1f, 0));
            item.addView(actions, margin(matchWrap(), 0, dp(5), 0, 0));
            state.list.addView(item, margin(matchWrap(), 0, 0, 0, dp(6)));
        }
        if (state.visibleTabs.size() < state.totalTabs) {
            state.loadMoreButton = smallButton("加载更多", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    appendTagsPage(state);
                }
            });
            state.list.addView(state.loadMoreButton, margin(matchWrap(), 0, 0, 0, dp(8)));
        }
    }

    private void updateTagsSummary(TagsState state) {
        if (state.summary == null) {
            return;
        }
        state.summary.setText("显示 " + state.visibleTabs.size() + " / "
                + state.totalTabs + " 条，已选 " + state.selected.size()
                + "  备份: " + trimMiddle(state.backupName, 36)
                + "\n域名: " + labelOrAll(state.domainFilter)
                + "  已删除标签: " + (state.includeDeleted ? "显示" : "隐藏"));
        if (state.deletedToggleButton != null) {
            state.deletedToggleButton.setText(state.includeDeleted ? "隐藏已删除" : "显示已删除");
        }
    }

    private void editTab(final TagsState state, final ManagedTab tab) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final EditText title = new EditText(this);
        title.setSingleLine(true);
        title.setText(tab.title);
        final EditText note = new EditText(this);
        note.setSingleLine(false);
        note.setMinLines(3);
        note.setText(tab.note);
        root.addView(label("标题"), matchWrap());
        root.addView(title, matchWrap());
        root.addView(label("备注"), matchWrap());
        root.addView(note, matchWrap());
        new AlertDialog.Builder(this)
                .setTitle("编辑标签")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int changed = tabStore.updateText(tab.id, title.getText().toString(),
                                note.getText().toString());
                        AgentStore.appendLog(MainActivity.this, "edit tab " + tab.id + " changed=" + changed);
                        populateTags(state);
                    }
                })
                .show();
    }

    private void addManualTab(final TagsState state) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("https://example.com/");
        final EditText title = new EditText(this);
        title.setSingleLine(true);
        title.setHint("标题");
        final EditText note = new EditText(this);
        note.setSingleLine(false);
        note.setMinLines(3);
        root.addView(label("网址"), matchWrap());
        root.addView(url, matchWrap());
        root.addView(label("标题"), matchWrap());
        root.addView(title, matchWrap());
        root.addView(label("备注"), matchWrap());
        root.addView(note, matchWrap());
        new AlertDialog.Builder(this)
                .setTitle("新增标签")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            long id = tabStore.addManualTab(state.backupId,
                                    title.getText().toString(), url.getText().toString(),
                                    note.getText().toString());
                            AgentStore.appendLog(MainActivity.this, "manual tab saved backup="
                                    + state.backupId + " id=" + id);
                            refreshStatus();
                            populateTags(state);
                        } catch (Throwable t) {
                            AgentStore.appendLog(MainActivity.this, "manual tab save failed: " + t);
                            Toast.makeText(MainActivity.this,
                                    "新增失败: " + shortError(t), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .show();
    }

    private void showDomainFilter(final TagsState state) {
        List<String> domains = tabStore.listDomains(state.backupId, state.includeDeleted);
        final ArrayList<String> values = new ArrayList<String>();
        values.add(DOMAIN_ALL);
        values.addAll(domains);
        String[] labels = new String[values.size()];
        int checked = 0;
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            labels[i] = value.length() == 0 ? "全部" : value;
            if (value.equals(state.domainFilter)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("域名筛选")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        state.domainFilter = values.get(which);
                        populateTags(state);
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void setSelectedDeleted(TagsState state, boolean deleted) {
        if (state.selected.isEmpty()) {
            Toast.makeText(this, "请先选择标签", Toast.LENGTH_SHORT).show();
            return;
        }
        int changed = tabStore.setDeleted(new ArrayList<Long>(state.selected), deleted);
        AgentStore.appendLog(this, (deleted ? "delete selected tabs " : "restore selected tabs ")
                + "count=" + changed);
        state.selected.clear();
        refreshStatus();
        populateTags(state);
    }

    private void purgeDeleted(final TagsState state) {
        new AlertDialog.Builder(this)
                .setTitle("清理已删除标签？")
                .setMessage("会永久删除本地库中标记为已删除的标签，不影响 Via。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int count = tabStore.purgeDeleted(state.backupId);
                        AgentStore.appendLog(MainActivity.this, "purge deleted tabs backup="
                                + state.backupId + " count=" + count);
                        state.selected.clear();
                        refreshStatus();
                        populateTags(state);
                    }
                })
                .show();
    }

    private void exportBackupHtml(ManagedBackup backup) {
        if (backup == null) {
            Toast.makeText(this, "没有可导出的备份", Toast.LENGTH_SHORT).show();
            return;
        }
        showExportFolderDialog(tabStore.listTabs(backup.id, SOURCE_ALL, DOMAIN_ALL, "", false),
                backup.name);
    }

    private void exportSelectedHtml(TagsState state) {
        if (state.selected.isEmpty()) {
            Toast.makeText(this, "请先选择标签", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<ManagedTab> selected = new ArrayList<ManagedTab>();
        for (ManagedTab tab : state.visibleTabs) {
            if (state.selected.contains(tab.id) && !tab.deleted) {
                selected.add(tab);
            }
        }
        showExportFolderDialog(selected, state.backupName);
    }

    private void showExportFolderDialog(final List<ManagedTab> managedTabs, final String sourceName) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(AgentStore.bookmarkFolderPrefix(this));
        input.setSelectAllOnFocus(true);
        input.setHint(AgentStore.DEFAULT_BOOKMARK_FOLDER_PREFIX);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(6), dp(12), 0);
        root.addView(label("书签文件夹名前缀"), matchWrap());
        root.addView(input, matchWrap());
        TextView example = textView(12f, COLOR_MUTED, false);
        example.setText("例如: 动漫收藏 -> 动漫收藏-日期-数量");
        root.addView(example, margin(matchWrap(), 0, dp(6), 0, 0));

        new AlertDialog.Builder(this)
                .setTitle("导出书签")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("导出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String prefix = safeText(input.getText().toString(),
                                AgentStore.DEFAULT_BOOKMARK_FOLDER_PREFIX);
                        AgentStore.setBookmarkFolderPrefix(MainActivity.this, prefix);
                        exportTabs(managedTabs, sourceName, prefix);
                    }
                })
                .show();
    }

    private void exportTabs(List<ManagedTab> managedTabs, String sourceName, String folderPrefix) {
        try {
            List<TabRecord> records = toTabRecords(managedTabs);
            if (records.isEmpty()) {
                Toast.makeText(this, "没有可导出的标签", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean groupByDomain = AgentStore.isDomainGroupEnabled(this);
            BookmarkBatch batch = BookmarkBatches.create(records, groupByDomain, folderPrefix);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new java.util.Date());
            String htmlName = "via-bookmarks-" + stamp + "-" + batch.bookmarkCount + ".html";
            String jsonName = "via-bookmarks-" + stamp + "-" + batch.bookmarkCount + ".json";
            String html = BookmarkHtml.toNetscapeBookmarksHtml(batch, records,
                    new BookmarkHtml.TitleProvider() {
                        @Override
                        public String titleFor(TabRecord tab) {
                            return OfflineViaTabsReader.displayTitle(tab);
                        }
            });
            AgentStore.WriteResult htmlResult = AgentStore.writeDownloadFileDetailed(this, htmlName, html);
            AgentStore.WriteResult jsonResult = AgentStore.writeDownloadFileDetailed(this, jsonName,
                    exportJson(records, groupByDomain, sourceName, batch.folderName));
            AgentStore.appendLog(this, "export bookmarks html: tabs=" + records.size()
                    + " source=" + safeText(sourceName, "local")
                    + " folder=" + batch.folderName
                    + " html=" + htmlResult.summary() + " json=" + jsonResult.summary());
            showTextDialog("导出书签",
                    "来源:\n" + safeText(sourceName, "本地") + "\n\n"
                            + "书签文件夹:\n" + batch.folderName + "\n\n"
                            + "书签文件:\n" + htmlResult.displayText() + "\n\n"
                            + "备份数据:\n" + jsonResult.displayText() + "\n\n"
                            + "在 Via 书签导入中选择这个书签文件。",
                    false);
            refreshStatus();
        } catch (Throwable t) {
            AgentStore.appendLog(this, "export html failed: " + t);
            Toast.makeText(this, "导出失败: " + shortError(t), Toast.LENGTH_LONG).show();
        }
    }

    private List<TabRecord> toTabRecords(List<ManagedTab> managedTabs) {
        ArrayList<TabRecord> out = new ArrayList<TabRecord>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        if (managedTabs == null) {
            return out;
        }
        for (ManagedTab tab : managedTabs) {
            if (tab == null || tab.deleted || !TabUrls.isBookmarkable(tab.url)) {
                continue;
            }
            String key = TabUrls.key(tab.url);
            if (key.length() == 0 || !seen.add(key)) {
                continue;
            }
            out.add(new TabRecord(out.size(), tab.title, tab.url));
        }
        return out;
    }

    private String exportJson(List<TabRecord> records, boolean groupByDomain,
                              String sourceName, String bookmarkFolder) throws Exception {
        JSONObject root = SnapshotJson.base("bookmarks.localExport", safeText(sourceName, "local"));
        root.put("sourceName", safeText(sourceName, "local"));
        root.put("bookmarkFolder", safeText(bookmarkFolder, AgentStore.DEFAULT_BOOKMARK_FOLDER_PREFIX));
        root.put("domainGroup", groupByDomain);
        root.put("captured", records.size());
        root.put("bookmarkable", BookmarkBatches.countBookmarkable(records));
        root.put("tabs", SnapshotJson.tabRecords(records, new BookmarkHtml.TitleProvider() {
            @Override
            public String titleFor(TabRecord tab) {
                return OfflineViaTabsReader.displayTitle(tab);
            }
        }));
        return root.toString(2);
    }

    private void showLogDialog() {
        showTextDialog("日志", buildLogText(), true);
    }

    private String buildLogText() {
        String log = AgentStore.readLog(this);
        return log.length() == 0 ? "暂无日志。" : log;
    }

    private void showTextDialog(final String title, final String text, final boolean canClear) {
        final TextView content = detailText(true);
        content.setText(text == null ? "" : text);
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(dp(12), dp(8), dp(12), dp(8));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyText(title, content.getText().toString());
                    }
                })
                .setNegativeButton(canClear ? "清空" : null, null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(final DialogInterface dialogInterface) {
                final AlertDialog alert = (AlertDialog) dialogInterface;
                Button clear = alert.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (clear != null) {
                    clear.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if ("日志".equals(title)) {
                                AgentStore.clearLog(MainActivity.this);
                                content.setText(buildLogText());
                                Toast.makeText(MainActivity.this, "日志已清空", Toast.LENGTH_SHORT).show();
                            } else {
                                content.setText("");
                            }
                        }
                    });
                }
            }
        });
        dialog.show();
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private String installedText(String packageName) {
        return isPackageInstalled(packageName) ? "已安装" : "未安装";
    }

    private String preparedText(String packageName) {
        return OfflineViaTabsReader.hasPreparedDatabase(this, packageName) ? "已准备" : "缺失";
    }

    private List<String> preparedPackagesForTarget(Context context, String target) {
        ArrayList<String> packages = new ArrayList<String>();
        if (AgentStore.PARSE_TARGET_MARK_VIA.equals(target)) {
            if (OfflineViaTabsReader.hasPreparedDatabase(context, AgentStore.PARSE_TARGET_MARK_VIA)) {
                packages.add(AgentStore.PARSE_TARGET_MARK_VIA);
            }
            return packages;
        }
        if (AgentStore.PARSE_TARGET_MARK_VIA_GP.equals(target)) {
            if (OfflineViaTabsReader.hasPreparedDatabase(context, AgentStore.PARSE_TARGET_MARK_VIA_GP)) {
                packages.add(AgentStore.PARSE_TARGET_MARK_VIA_GP);
            }
            return packages;
        }
        return OfflineViaTabsReader.preparedPackages(context);
    }

    private String parseTargetLabel(String target) {
        if (AgentStore.PARSE_TARGET_MARK_VIA.equals(target)) {
            return "国内版";
        }
        if (AgentStore.PARSE_TARGET_MARK_VIA_GP.equals(target)) {
            return "GP版";
        }
        return "全部";
    }

    private String labelOrAll(String value) {
        return value == null || value.length() == 0 ? "全部" : value;
    }

    private String packageDisplayName(String packageName) {
        if ("mark.via".equals(packageName)) {
            return "国内版";
        }
        if ("mark.via.gp".equals(packageName)) {
            return "GP版";
        }
        return safeText(packageName, "未知来源");
    }

    private String sourceLabel(String source) {
        if (source == null || source.trim().length() == 0) {
            return "全部";
        }
        String[] parts = source.split(",");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String clean = part == null ? "" : part.trim();
            if (clean.length() == 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append(" / ");
            }
            out.append(packageDisplayName(clean));
        }
        return out.length() == 0 ? "全部" : out.toString();
    }

    private String importResultText(LocalTabStore.ImportResult result) {
        if (result == null) {
            return "无结果";
        }
        return packageDisplayName(result.sourcePackage)
                + ": 读取=" + result.seen
                + " 新增=" + result.inserted
                + " 更新=" + result.updated
                + " 跳过=" + result.skipped;
    }

    private String trimMiddle(String value, int max) {
        String text = safeText(value, "");
        if (text.length() <= max || max < 8) {
            return text;
        }
        int keep = (max - 1) / 2;
        int right = max - 1 - keep;
        return text.substring(0, keep) + "…" + text.substring(text.length() - right);
    }

    private String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.length() == 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append("\n");
            }
            out.append(line);
        }
        return out.toString();
    }

    private String safeText(String value, String fallback) {
        return value == null || value.trim().length() == 0 ? fallback : value.trim();
    }

    private String shortError(Object error) {
        if (error == null) {
            return "unknown";
        }
        String message = error instanceof Throwable
                ? ((Throwable) error).getMessage()
                : String.valueOf(error);
        if (message == null || message.trim().length() == 0) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 140 ? message.substring(0, 140) : message;
    }

    private LinearLayout panel() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(12), dp(14), dp(12));
        layout.setBackground(panelBackground(8, COLOR_PANEL, COLOR_BORDER));
        return layout;
    }

    private LinearLayout listItemPanel() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(8), dp(6), dp(8), dp(6));
        layout.setBackground(panelBackground(6, COLOR_PANEL, COLOR_BORDER));
        return layout;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void addSectionTitle(LinearLayout parent, String title) {
        TextView view = textView(15f, COLOR_TEXT, true);
        view.setText(title);
        view.setPadding(0, 0, 0, dp(8));
        parent.addView(view, matchWrap());
    }

    private TextView label(String text) {
        TextView view = textView(12f, COLOR_MUTED, true);
        view.setText(text);
        view.setPadding(0, dp(8), 0, 0);
        return view;
    }

    private TextView textView(float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.08f);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private TextView detailText(boolean monospace) {
        TextView view = textView(13f, COLOR_TEXT, false);
        if (monospace) {
            view.setTypeface(Typeface.MONOSPACE);
        }
        view.setTextIsSelectable(true);
        return view;
    }

    private CheckBox switchView(String text) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextSize(14f);
        box.setTextColor(COLOR_TEXT);
        box.setPadding(0, dp(4), 0, dp(4));
        return box;
    }

    private CheckBox compactCheckBox(String text) {
        CheckBox box = switchView(text);
        box.setTextSize(13f);
        box.setPadding(0, 0, 0, 0);
        box.setSingleLine(true);
        box.setEllipsize(TextUtils.TruncateAt.END);
        return box;
    }

    private Button actionButton(String text, int color, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setBackground(panelBackground(8, color, color));
        button.setOnClickListener(listener);
        return button;
    }

    private Button smallButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12f);
        button.setAllCaps(false);
        button.setTextColor(COLOR_TEXT);
        button.setBackground(panelBackground(6, Color.rgb(248, 250, 252), COLOR_BORDER));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(6), dp(2), dp(6), dp(2));
        button.setIncludeFontPadding(false);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(listener);
        return button;
    }

    private Button listButton(String text, View.OnClickListener listener) {
        Button button = smallButton(text, listener);
        button.setTextSize(11f);
        button.setPadding(dp(4), dp(1), dp(4), dp(1));
        return button;
    }

    private GradientDrawable panelBackground(int radiusDp, int color, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        bg.setStroke(dp(1), strokeColor);
        return bg;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams margin(LinearLayout.LayoutParams params,
                                             int left, int top, int right, int bottom) {
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private LinearLayout.LayoutParams weightParam(float weight, int rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(0, 0, rightMargin, 0);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void copyText(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text == null ? "" : text));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private static final class TagsState {
        AlertDialog dialog;
        LinearLayout list;
        TextView summary;
        EditText queryInput;
        long backupId;
        String backupName = "";
        List<ManagedTab> visibleTabs = new ArrayList<ManagedTab>();
        Set<Long> selected = new HashSet<Long>();
        String domainFilter = DOMAIN_ALL;
        String query = "";
        boolean includeDeleted;
        int totalTabs;
        Button loadMoreButton;
        Button deletedToggleButton;
    }

    private static final class BackupsState {
        AlertDialog dialog;
        LinearLayout list;
        TextView summary;
        List<ManagedBackup> visibleBackups = new ArrayList<ManagedBackup>();
        boolean includeDeleted;
        int totalBackups;
        Button loadMoreButton;
        Button deletedToggleButton;
    }
}
