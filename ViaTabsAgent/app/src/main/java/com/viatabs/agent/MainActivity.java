package com.viatabs.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private static final int COLOR_LOG_BG = Color.rgb(15, 23, 42);
    private static final String FILTER_ALL = "all";
    private static final String FILTER_NOTE = "note";
    private static final String FILTER_NONE = "none";

    private TextView statusView;
    private CheckBox exportSwitch;
    private CheckBox bookmarkImportSwitch;
    private CheckBox domainGroupSwitch;
    private TextView panelStyleSummary;
    private TextView panelStyleButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        subtitle.setText("Via 标签导出与书签导入模块");
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle, matchWrap());

        statusView = textView(14f, COLOR_TEXT, true);
        statusView.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusView.setBackground(panelBackground(8, COLOR_PANEL, COLOR_BORDER));
        root.addView(statusView, margin(matchWrap(), 0, 0, 0, dp(12)));

        LinearLayout switchPanel = panel();
        addSectionTitle(switchPanel, "功能开关");
        exportSwitch = switchView("启用 Via 内悬浮按钮");
        bookmarkImportSwitch = switchView("标签导入到书签");
        domainGroupSwitch = switchView("按域名整理书签");
        switchPanel.addView(exportSwitch, matchWrap());
        switchPanel.addView(bookmarkImportSwitch, matchWrap());
        switchPanel.addView(domainGroupSwitch, matchWrap());
        addPanelColorChooser(switchPanel);
        root.addView(switchPanel, margin(matchWrap(), 0, 0, 0, dp(12)));

        bindSwitches();

        LinearLayout actionPanel = panel();
        addSectionTitle(actionPanel, "操作");
        Button testExport = actionButton("测试导出", COLOR_PRIMARY_DARK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTestExport();
            }
        });
        actionPanel.addView(testExport, matchWrap());
        Button manager = actionButton("本地导出数据管理", COLOR_PRIMARY_DARK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showExportManagerDialog();
            }
        });
        actionPanel.addView(manager, margin(matchWrap(), 0, dp(8), 0, 0));
        root.addView(actionPanel, margin(matchWrap(), 0, 0, 0, dp(12)));

        LinearLayout detailPanel = panel();
        addSectionTitle(detailPanel, "查看与管理");
        addButtonGrid(detailPanel,
                detailButton("信息", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showTextDialog("信息", buildInfoText(), false);
                    }
                }),
                detailButton("说明", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showTextDialog("说明", buildHelpText(), false);
                    }
                }));
        addButtonGrid(detailPanel,
                detailButton("结果", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String result = formatLastResult(AgentStore.getLastSaveResult(MainActivity.this));
                        showTextDialog("结果", result.length() == 0 ? "暂无最近一次保存结果。" : result, true);
                    }
                }),
                detailButton("日志", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showTextDialog("日志", buildLogText(), true);
                    }
                }));
        root.addView(detailPanel, margin(matchWrap(), 0, 0, 0, dp(12)));

        setContentView(page);
        AgentStore.appendLog(this, "打开模块界面");
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void bindSwitches() {
        exportSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean enabled = exportSwitch.isChecked();
                AgentStore.setExportEnabled(MainActivity.this, enabled);
                AgentStore.appendLog(MainActivity.this, "导出开关: " + (enabled ? "开启" : "关闭"));
                refreshStatus();
            }
        });
        bookmarkImportSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean enabled = bookmarkImportSwitch.isChecked();
                AgentStore.setBookmarkImportEnabled(MainActivity.this, enabled);
                AgentStore.appendLog(MainActivity.this, "标签导入到书签: " + (enabled ? "开启" : "关闭"));
                refreshStatus();
            }
        });
        domainGroupSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean enabled = domainGroupSwitch.isChecked();
                AgentStore.setDomainGroupEnabled(MainActivity.this, enabled);
                AgentStore.appendLog(MainActivity.this, "按域名整理: " + (enabled ? "开启" : "关闭"));
                refreshStatus();
            }
        });
    }

    private void refreshStatus() {
        boolean enabled = AgentStore.isExportEnabled(this);
        boolean bookmarkImport = AgentStore.isBookmarkImportEnabled(this);
        boolean domainGroup = AgentStore.isDomainGroupEnabled(this);
        exportSwitch.setChecked(enabled);
        bookmarkImportSwitch.setChecked(bookmarkImport);
        domainGroupSwitch.setChecked(domainGroup);
        if (panelStyleSummary != null) {
            panelStyleSummary.setText(panelStyleSummaryText());
        }
        if (panelStyleButton != null) {
            panelStyleButton.setBackground(stylePreviewBackground(AgentStore.getPanelColor(this), true));
            panelStyleButton.setText("✔");
            panelStyleButton.setTextColor(previewTextColor(AgentStore.getPanelColor(this)));
        }
        statusView.setText(buildStatusSummary(enabled, bookmarkImport, domainGroup));
    }

    private String buildStatusSummary(boolean enabled, boolean bookmarkImport, boolean domainGroup) {
        StringBuilder status = new StringBuilder();
        status.append("ViaTabsAgent ").append(BuildConfig.VERSION_NAME)
                .append("  #").append(BuildConfig.VERSION_CODE).append("\n");
        status.append("国内版 ").append(isPackageInstalled("mark.via") ? "已安装" : "未安装")
                .append("   GP版 ").append(isPackageInstalled("mark.via.gp") ? "已安装" : "未安装")
                .append("\n");
        status.append("悬浮按钮 ").append(enabled ? "开启" : "关闭")
                .append("   导入 ").append(bookmarkImport ? "开启" : "关闭")
                .append("   整理 ").append(domainGroup ? "开启" : "关闭")
                .append("\n");
        status.append("圆点 ")
                .append(colorLabel(AgentStore.getPanelColor(this)))
                .append(" / ").append(AgentStore.getPanelSize(this)).append("dp")
                .append(" / ").append(AgentStore.getPanelAlpha(this)).append("%");
        return status.toString();
    }

    private String buildInfoText() {
        return "模块版本: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                + "mark.via: " + (isPackageInstalled("mark.via") ? "已安装" : "未安装") + "\n"
                + "mark.via.gp: " + (isPackageInstalled("mark.via.gp") ? "已安装" : "未安装") + "\n"
                + "Via 内按钮: " + (AgentStore.isExportEnabled(this) ? "开启" : "关闭") + "\n"
                + "JSON 标签文件: 默认保存\n"
                + "标签导入到书签: " + (AgentStore.isBookmarkImportEnabled(this) ? "开启" : "关闭") + "\n"
                + "按域名整理: " + (AgentStore.isDomainGroupEnabled(this) ? "开启" : "关闭") + "\n"
                + "悬浮按钮配色: " + colorLabel(AgentStore.getPanelColor(this)) + "\n"
                + "悬浮按钮大小: " + AgentStore.getPanelSize(this) + "dp\n"
                + "透明度: " + AgentStore.getPanelAlpha(this) + "%";
    }

    private String buildHelpText() {
        return "使用说明:\n"
                + "1. 在 LSPosed 中启用 ViaTabsAgent，并勾选 mark.via / mark.via.gp。\n"
                + "2. 强制停止 Via 后重新打开。\n"
                + "3. Via 内悬浮按钮为纯色圆点，点击后读取当前标签并弹出确认。\n"
                + "4. 长按拖动悬浮按钮，位置会按 mark.via / mark.via.gp 分开保存。\n"
                + "5. 本地导出数据管理会把同名 HTML/JSON 合并成一组，可备注、筛选、批量备注、批量删除或导入。\n\n"
                + "导出目录:\n/storage/emulated/0/Download/ViaTabsAgent/";
    }

    private String formatLastResult(String payload) {
        if (payload == null || payload.trim().length() == 0) {
            return "";
        }
        try {
            JSONObject root = new JSONObject(payload);
            StringBuilder result = new StringBuilder();
            result.append("时间: ").append(root.optString("time", "-")).append("\n");
            result.append("来源: ").append(root.optString("source", "-")).append("\n");
            result.append("目标: ").append(root.optString("targetPackage", "-")).append("\n");
            result.append("文件夹: ").append(root.optString("folder", "-")).append("\n");
            result.append("捕获/可保存: ").append(root.optInt("captured", 0))
                    .append("/").append(root.optInt("bookmarkable", 0)).append("\n");
            result.append("导入/跳过: ").append(root.optInt("inserted", 0))
                    .append("/").append(root.optInt("skipped", 0)).append("\n");
            result.append("按域名整理: ").append(root.optBoolean("domainGroup", false) ? "开启" : "关闭").append("\n");
            String html = root.optString("htmlExportPath", "");
            String json = root.optString("jsonExportPath", "");
            if (html.length() > 0 && !"null".equals(html)) {
                result.append("HTML: ").append(html).append("\n");
            }
            if (json.length() > 0 && !"null".equals(json)) {
                result.append("JSON: ").append(json).append("\n");
            }
            String error = root.optString("error", "");
            if (error.length() > 0 && !"null".equals(error)) {
                result.append("错误: ").append(error).append("\n");
            }
            return result.toString().trim();
        } catch (Throwable t) {
            return "读取最近结果失败: " + t;
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
            boolean deleted = AgentStore.deleteExportedFile(this, fileName);
            AgentStore.appendLog(this, "测试导出成功: " + path
                    + "，测试文件已清理: " + (deleted ? "是" : "否"));
            Toast.makeText(this, "测试导出成功", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            AgentStore.appendLog(this, "测试导出失败: " + t);
            Toast.makeText(this, "测试导出失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String buildLogText() {
        String log = AgentStore.readLog(this);
        if (log.length() == 0) {
            return "暂无日志。\n\n请确认 LSPosed 已启用模块，并强制停止 Via 后重开。";
        }
        return log;
    }

    private void addPanelColorChooser(LinearLayout parent) {
        LinearLayout row = horizontalRow();
        row.setPadding(0, dp(10), 0, 0);
        panelStyleSummary = textView(13f, COLOR_MUTED, false);
        panelStyleSummary.setText(panelStyleSummaryText());
        row.addView(panelStyleSummary, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        panelStyleButton = stylePreviewButton(AgentStore.getPanelColor(this), true);
        panelStyleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPanelStyleDialog();
            }
        });
        row.addView(panelStyleButton, new LinearLayout.LayoutParams(dp(32), dp(32)));
        parent.addView(row, matchWrap());
    }

    private String panelStyleSummaryText() {
        return "悬浮按钮样式: " + colorLabel(AgentStore.getPanelColor(this))
                + " / " + AgentStore.getPanelSize(this) + "dp"
                + " / " + AgentStore.getPanelAlpha(this) + "%";
    }

    private void showPanelStyleDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(4), dp(8), 0);

        final String[] selectedColor = new String[]{AgentStore.getPanelColor(this)};
        final ArrayList<TextView> swatches = new ArrayList<TextView>();
        LinearLayout row1 = horizontalRow();
        LinearLayout row2 = horizontalRow();
        LinearLayout row3 = horizontalRow();
        addStyleSwatch(row1, swatches, selectedColor, AgentStore.PANEL_COLOR_BLUE, "蓝");
        addStyleSwatch(row1, swatches, selectedColor, AgentStore.PANEL_COLOR_GREEN, "绿");
        addStyleSwatch(row1, swatches, selectedColor, AgentStore.PANEL_COLOR_PURPLE, "紫");
        addStyleSwatch(row2, swatches, selectedColor, AgentStore.PANEL_COLOR_DARK, "深");
        addStyleSwatch(row2, swatches, selectedColor, AgentStore.PANEL_COLOR_WHITE, "白");
        addStyleSwatch(row2, swatches, selectedColor, AgentStore.PANEL_COLOR_TRANSPARENT, "透");
        addStyleSwatch(row3, swatches, selectedColor, AgentStore.PANEL_COLOR_ROSE, "玫");
        addStyleSwatch(row3, swatches, selectedColor, AgentStore.PANEL_COLOR_ORANGE, "橙");
        addStyleSwatch(row3, swatches, selectedColor, AgentStore.PANEL_COLOR_CYAN, "青");
        root.addView(row1, matchWrap());
        root.addView(row2, margin(matchWrap(), 0, dp(8), 0, 0));
        root.addView(row3, margin(matchWrap(), 0, dp(8), 0, dp(8)));

        final TextView alphaText = textView(13f, COLOR_MUTED, false);
        alphaText.setText("透明度: " + AgentStore.getPanelAlpha(this) + "%");
        root.addView(alphaText, margin(matchWrap(), 0, dp(8), 0, 0));

        final SeekBar alpha = new SeekBar(this);
        alpha.setMax(80);
        alpha.setProgress(AgentStore.getPanelAlpha(this) - 20);
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                alphaText.setText("透明度: " + (progress + 20) + "%");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(alpha, matchWrap());

        final TextView sizeText = textView(13f, COLOR_MUTED, false);
        sizeText.setText("大小: " + AgentStore.getPanelSize(this) + "dp");
        root.addView(sizeText, margin(matchWrap(), 0, dp(8), 0, 0));

        final SeekBar size = new SeekBar(this);
        size.setMax(28);
        size.setProgress(AgentStore.getPanelSize(this) - 28);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sizeText.setText("大小: " + (progress + 28) + "dp");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(size, matchWrap());
        refreshStyleSwatches(swatches, selectedColor[0]);

        new AlertDialog.Builder(this)
                .setTitle("悬浮按钮样式")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String color = selectedColor[0];
                        AgentStore.setPanelColor(MainActivity.this, color);
                        AgentStore.setPanelAlpha(MainActivity.this, alpha.getProgress() + 20);
                        AgentStore.setPanelSize(MainActivity.this, size.getProgress() + 28);
                        AgentStore.appendLog(MainActivity.this, "悬浮按钮样式: color=" + color
                                + " size=" + AgentStore.getPanelSize(MainActivity.this)
                                + " alpha=" + AgentStore.getPanelAlpha(MainActivity.this));
                        refreshStatus();
                        Toast.makeText(MainActivity.this, "悬浮按钮样式已保存，重开 Via 后生效", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void addStyleSwatch(LinearLayout row, final List<TextView> swatches,
                                final String[] selectedColor, final String color, String label) {
        final TextView swatch = stylePreviewButton(color, false);
        swatch.setTag(color);
        swatch.setContentDescription(label);
        swatch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedColor[0] = color;
                refreshStyleSwatches(swatches, selectedColor[0]);
            }
        });
        swatches.add(swatch);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(30), dp(30));
        params.setMargins(0, 0, dp(8), 0);
        row.addView(swatch, params);
    }

    private void refreshStyleSwatches(List<TextView> swatches, String selectedColor) {
        for (TextView swatch : swatches) {
            String color = swatch.getTag() instanceof String ? (String) swatch.getTag() : AgentStore.PANEL_COLOR_BLUE;
            boolean selected = color.equals(selectedColor);
            swatch.setText(selected ? "✔" : "");
            swatch.setBackground(stylePreviewBackground(color, selected));
        }
        if (panelStyleButton != null) {
            panelStyleButton.setBackground(stylePreviewBackground(AgentStore.getPanelColor(this), true));
            panelStyleButton.setText("✔");
        }
    }

    private TextView stylePreviewButton(String color, boolean selected) {
        TextView view = textView(13f, previewTextColor(color), true);
        view.setGravity(Gravity.CENTER);
        view.setText(selected ? "✔" : "");
        view.setBackground(stylePreviewBackground(color, selected));
        return view;
    }

    private GradientDrawable stylePreviewBackground(String color, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(previewColor(color));
        drawable.setStroke(dp(selected ? 3 : 1), selected ? COLOR_TEXT : COLOR_BORDER);
        return drawable;
    }

    private int previewColor(String color) {
        if (AgentStore.PANEL_COLOR_GREEN.equals(color)) return Color.rgb(5, 150, 105);
        if (AgentStore.PANEL_COLOR_PURPLE.equals(color)) return Color.rgb(124, 58, 237);
        if (AgentStore.PANEL_COLOR_DARK.equals(color)) return Color.rgb(30, 41, 59);
        if (AgentStore.PANEL_COLOR_WHITE.equals(color)) return Color.WHITE;
        if (AgentStore.PANEL_COLOR_TRANSPARENT.equals(color)) return Color.rgb(241, 245, 249);
        if (AgentStore.PANEL_COLOR_ROSE.equals(color)) return Color.rgb(225, 29, 72);
        if (AgentStore.PANEL_COLOR_ORANGE.equals(color)) return Color.rgb(234, 88, 12);
        if (AgentStore.PANEL_COLOR_CYAN.equals(color)) return Color.rgb(8, 145, 178);
        return Color.rgb(37, 99, 235);
    }

    private int previewTextColor(String color) {
        if (AgentStore.PANEL_COLOR_WHITE.equals(color) || AgentStore.PANEL_COLOR_TRANSPARENT.equals(color)) {
            return COLOR_TEXT;
        }
        return Color.WHITE;
    }

    private String colorLabel(String color) {
        if (AgentStore.PANEL_COLOR_GREEN.equals(color)) return "绿色";
        if (AgentStore.PANEL_COLOR_PURPLE.equals(color)) return "紫色";
        if (AgentStore.PANEL_COLOR_DARK.equals(color)) return "深色";
        if (AgentStore.PANEL_COLOR_WHITE.equals(color)) return "白色";
        if (AgentStore.PANEL_COLOR_TRANSPARENT.equals(color)) return "透明";
        if (AgentStore.PANEL_COLOR_ROSE.equals(color)) return "玫红";
        if (AgentStore.PANEL_COLOR_ORANGE.equals(color)) return "橙色";
        if (AgentStore.PANEL_COLOR_CYAN.equals(color)) return "青色";
        return "蓝色";
    }

    private void showTextDialog(final String title, String text, final boolean canClear) {
        final TextView content = detailText("日志".equals(title));
        content.setText(text == null ? "" : text);
        content.setPadding(dp(14), dp(12), dp(14), dp(12));
        if ("日志".equals(title)) {
            content.setTextColor(Color.rgb(226, 232, 240));
            content.setBackgroundColor(COLOR_LOG_BG);
        } else {
            content.setTextColor(COLOR_TEXT);
            content.setBackgroundColor(Color.WHITE);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton("清除", null)
                .setNeutralButton("复制", null)
                .setPositiveButton("确定", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(final DialogInterface dialogInterface) {
                final AlertDialog shown = (AlertDialog) dialogInterface;
                shown.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copyText(title, content.getText().toString());
                    }
                });
                shown.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if ("日志".equals(title)) {
                            AgentStore.clearLog(MainActivity.this);
                            content.setText(buildLogText());
                            Toast.makeText(MainActivity.this, "日志已清空", Toast.LENGTH_SHORT).show();
                        } else if ("结果".equals(title)) {
                            AgentStore.setLastSaveResult(MainActivity.this, "");
                            content.setText("暂无最近一次保存结果。");
                            refreshStatus();
                            Toast.makeText(MainActivity.this, "最近结果已清除", Toast.LENGTH_SHORT).show();
                        } else if (canClear) {
                            content.setText("");
                        } else {
                            content.setText("");
                            Toast.makeText(MainActivity.this, "已清空当前显示内容", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
        dialog.show();
    }

    private void showExportManagerDialog() {
        final ManagerState state = new ManagerState();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));

        state.summary = textView(13f, COLOR_MUTED, false);
        root.addView(state.summary, matchWrap());

        state.filterButton = smallButton("筛选: 所有", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNoteFilterDialog(state);
            }
        });
        root.addView(state.filterButton, margin(matchWrap(), 0, dp(8), 0, 0));

        LinearLayout selectRow = horizontalRow();
        selectRow.addView(smallButton("全选", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.selected.clear();
                for (AgentStore.ExportGroup group : state.visibleGroups) {
                    state.selected.add(group.baseName);
                }
                populateExportGroups(state);
            }
        }), weightParam(1f, dp(6)));
        selectRow.addView(smallButton("反选", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Set<String> next = new HashSet<String>();
                for (AgentStore.ExportGroup group : state.visibleGroups) {
                    if (!state.selected.contains(group.baseName)) {
                        next.add(group.baseName);
                    }
                }
                state.selected.clear();
                state.selected.addAll(next);
                populateExportGroups(state);
            }
        }), weightParam(1f, dp(6)));
        selectRow.addView(smallButton("清空选择", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.selected.clear();
                populateExportGroups(state);
            }
        }), weightParam(1f, 0));
        root.addView(selectRow, margin(matchWrap(), 0, dp(8), 0, 0));

        state.sortButton = smallButton("排序: 时间倒序", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.asc = !state.asc;
                populateExportGroups(state);
            }
        });
        root.addView(state.sortButton, margin(matchWrap(), 0, dp(6), 0, 0));

        Button bulk = actionButton("批量操作", COLOR_PRIMARY_DARK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBulkActionDialog(state);
            }
        });
        root.addView(bulk, margin(matchWrap(), 0, dp(6), 0, dp(6)));

        state.list = new LinearLayout(this);
        state.list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(state.list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420)));

        state.dialog = new AlertDialog.Builder(this)
                .setTitle("本地导出标签数据")
                .setView(root)
                .setPositiveButton("关闭", null)
                .create();
        state.dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                populateExportGroups(state);
            }
        });
        state.dialog.show();
    }

    private void populateExportGroups(final ManagerState state) {
        state.list.removeAllViews();
        List<AgentStore.ExportGroup> groups = AgentStore.listExportGroups(this);
        normalizeNoteFilter(state, groups);
        if (state.asc) {
            Collections.sort(groups, new Comparator<AgentStore.ExportGroup>() {
                @Override
                public int compare(AgentStore.ExportGroup left, AgentStore.ExportGroup right) {
                    if (left.lastModified == right.lastModified) {
                        return left.baseName.compareTo(right.baseName);
                    }
                    return left.lastModified < right.lastModified ? -1 : 1;
                }
            });
        }
        state.visibleGroups.clear();
        for (AgentStore.ExportGroup group : groups) {
            String note = group.note == null ? "" : group.note;
            if (!matchesNoteFilter(state, note)) {
                continue;
            }
            state.visibleGroups.add(group);
        }
        updateManagerSummary(state);
        if (state.sortButton != null) {
            state.sortButton.setText("排序: " + (state.asc ? "时间正序" : "时间倒序"));
        }
        if (state.visibleGroups.isEmpty()) {
            TextView empty = textView(14f, COLOR_MUTED, false);
            empty.setText("暂无 via-*.html / via-*.json 导出数据。");
            state.list.addView(empty, matchWrap());
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (final AgentStore.ExportGroup group : state.visibleGroups) {
            LinearLayout item = panel();
            item.setPadding(dp(10), dp(8), dp(10), dp(8));

            CheckBox selected = switchView(group.baseName);
            selected.setChecked(state.selected.contains(group.baseName));
            selected.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (((CheckBox) v).isChecked()) {
                        state.selected.add(group.baseName);
                    } else {
                        state.selected.remove(group.baseName);
                    }
                    updateManagerSummary(state);
                }
            });
            item.addView(selected, matchWrap());

            TextView meta = textView(12f, COLOR_MUTED, false);
            meta.setText("来源: " + safeText(group.packageName, "未知来源")
                    + "  ·  " + format.format(new java.util.Date(group.lastModified))
                    + "  ·  " + formatSize(group.size)
                    + "\n标签: " + group.captured + " / 可保存: " + group.bookmarkable
                    + "\nHTML: " + safeText(group.htmlName, "缺失")
                    + "\nJSON: " + safeText(group.jsonName, "缺失")
                    + "\n备注: " + safeText(group.note, "无"));
            item.addView(meta, matchWrap());

            LinearLayout actions = horizontalRow();
            actions.addView(smallButton("备注", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editNote(state, group);
                }
            }), weightParam(1f, dp(6)));
            Button delete = smallButton("删除", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDeleteGroup(state, group.baseName);
                }
            });
            delete.setTextColor(COLOR_DANGER);
            actions.addView(delete, weightParam(1f, 0));
            item.addView(actions, margin(matchWrap(), 0, dp(6), 0, 0));

            state.list.addView(item, margin(matchWrap(), 0, 0, 0, dp(8)));
        }
    }

    private void showNoteFilterDialog(final ManagerState state) {
        final List<AgentStore.ExportGroup> groups = AgentStore.listExportGroups(this);
        final ArrayList<NoteFilterOption> options = buildNoteFilterOptions(groups);
        int checked = 0;
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            NoteFilterOption option = options.get(i);
            labels[i] = option.label;
            if (option.matches(state.filterMode, state.filterNote)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择筛选条件")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        NoteFilterOption option = options.get(which);
                        state.filterMode = option.mode;
                        state.filterNote = option.note;
                        populateExportGroups(state);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private ArrayList<NoteFilterOption> buildNoteFilterOptions(List<AgentStore.ExportGroup> groups) {
        ArrayList<NoteFilterOption> options = new ArrayList<NoteFilterOption>();
        options.add(new NoteFilterOption(FILTER_ALL, "", "所有"));
        LinkedHashSet<String> notes = new LinkedHashSet<String>();
        for (AgentStore.ExportGroup group : groups) {
            String note = group.note == null ? "" : group.note.trim();
            if (note.length() > 0) {
                notes.add(note);
            }
        }
        for (String note : notes) {
            options.add(new NoteFilterOption(FILTER_NOTE, note, note));
        }
        options.add(new NoteFilterOption(FILTER_NONE, "", "未备注"));
        return options;
    }

    private void normalizeNoteFilter(ManagerState state, List<AgentStore.ExportGroup> groups) {
        if (!FILTER_NOTE.equals(state.filterMode)) {
            return;
        }
        String target = state.filterNote == null ? "" : state.filterNote.trim();
        if (target.length() == 0) {
            state.filterMode = FILTER_ALL;
            state.filterNote = "";
            return;
        }
        for (AgentStore.ExportGroup group : groups) {
            String note = group.note == null ? "" : group.note.trim();
            if (target.equals(note)) {
                return;
            }
        }
        state.filterMode = FILTER_ALL;
        state.filterNote = "";
    }

    private boolean matchesNoteFilter(ManagerState state, String note) {
        String value = note == null ? "" : note.trim();
        if (FILTER_NONE.equals(state.filterMode)) {
            return value.length() == 0;
        }
        if (FILTER_NOTE.equals(state.filterMode)) {
            String target = state.filterNote == null ? "" : state.filterNote.trim();
            return target.length() > 0 && target.equals(value);
        }
        if (FILTER_ALL.equals(state.filterMode)) {
            return true;
        }
        return true;
    }

    private String noteFilterLabel(ManagerState state) {
        if (FILTER_NONE.equals(state.filterMode)) {
            return "未备注";
        }
        if (FILTER_NOTE.equals(state.filterMode)) {
            return state.filterNote == null || state.filterNote.trim().length() == 0
                    ? "所有"
                    : state.filterNote.trim();
        }
        return "所有";
    }

    private void updateManagerSummary(ManagerState state) {
        if (state.summary == null) {
            return;
        }
        state.summary.setText("共 " + state.visibleGroups.size() + " 组，已选择 " + state.selected.size()
                + " 组，筛选: " + noteFilterLabel(state)
                + "，排序: " + (state.asc ? "时间正序" : "时间倒序"));
        if (state.filterButton != null) {
            state.filterButton.setText("筛选: " + noteFilterLabel(state));
        }
    }

    private void editNote(final ManagerState state, final AgentStore.ExportGroup group) {
        final EditText input = new EditText(this);
        input.setText(group.note == null ? "" : group.note);
        input.setSingleLine(false);
        new AlertDialog.Builder(this)
                .setTitle("编辑备注")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        AgentStore.setExportNote(MainActivity.this, group.baseName, input.getText().toString());
                        populateExportGroups(state);
                    }
                })
                .show();
    }

    private void confirmDeleteGroup(final ManagerState state, final String baseName) {
        new AlertDialog.Builder(this)
                .setTitle("删除导出数据？")
                .setMessage(baseName + "\n将同时删除对应 HTML/JSON 文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        AgentStore.deleteExportGroup(MainActivity.this, baseName);
                        state.selected.remove(baseName);
                        populateExportGroups(state);
                    }
                })
                .show();
    }

    private void showBulkActionDialog(final ManagerState state) {
        if (state.selected.isEmpty()) {
            Toast.makeText(this, "请先选择数据组", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] actions = new String[]{
                "导入到 mark.via",
                "导入到 mark.via.gp",
                "批量备注",
                "删除所选"
        };
        new AlertDialog.Builder(this)
                .setTitle("批量操作（已选 " + state.selected.size() + " 组）")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            importSelectedGroups(state, "mark.via");
                        } else if (which == 1) {
                            importSelectedGroups(state, "mark.via.gp");
                        } else if (which == 2) {
                            editSelectedNotes(state);
                        } else if (which == 3) {
                            confirmDeleteSelected(state);
                        }
                    }
                })
                .show();
    }

    private void editSelectedNotes(final ManagerState state) {
        if (state.selected.isEmpty()) {
            Toast.makeText(this, "请先选择数据组", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setHint("留空可清除所选备注");
        new AlertDialog.Builder(this)
                .setTitle("批量备注（已选 " + state.selected.size() + " 组）")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String note = input.getText().toString();
                        for (String baseName : new ArrayList<String>(state.selected)) {
                            AgentStore.setExportNote(MainActivity.this, baseName, note);
                        }
                        populateExportGroups(state);
                    }
                })
                .show();
    }

    private void confirmDeleteSelected(final ManagerState state) {
        if (state.selected.isEmpty()) {
            Toast.makeText(this, "请先选择数据组", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("批量删除？")
                .setMessage("将删除 " + state.selected.size() + " 组导出数据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        for (String baseName : new ArrayList<String>(state.selected)) {
                            AgentStore.deleteExportGroup(MainActivity.this, baseName);
                        }
                        state.selected.clear();
                        populateExportGroups(state);
                    }
                })
                .show();
    }

    private void importSelectedGroups(ManagerState state, String targetPackage) {
        if (state.selected.isEmpty()) {
            Toast.makeText(this, "请先选择数据组", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> jsonNames = new ArrayList<String>();
        ArrayList<String> htmlNames = new ArrayList<String>();
        for (AgentStore.ExportGroup group : state.visibleGroups) {
            if (!state.selected.contains(group.baseName)) {
                continue;
            }
            jsonNames.add(group.jsonName == null ? "" : group.jsonName);
            htmlNames.add(group.htmlName == null ? "" : group.htmlName);
        }
        Intent intent = new Intent(Hook.ACTION_IMPORT_EXPORT_GROUPS);
        intent.setPackage(targetPackage);
        intent.putStringArrayListExtra(Hook.EXTRA_JSON_NAMES, jsonNames);
        intent.putStringArrayListExtra(Hook.EXTRA_HTML_NAMES, htmlNames);
        sendBroadcast(intent);
        AgentStore.appendLog(this, "发送本地导出数据导入请求: target=" + targetPackage
                + " groups=" + jsonNames.size());
        Toast.makeText(this, "已发送导入请求，请确认目标 Via 已打开并已被 LSPosed 注入", Toast.LENGTH_LONG).show();
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private String safeText(String value, String fallback) {
        return value == null || value.trim().length() == 0 ? fallback : value.trim();
    }

    private String formatSize(long size) {
        if (size < 1024L) return size + " B";
        if (size < 1024L * 1024L) return (size / 1024L) + " KB";
        return (size / 1024L / 1024L) + " MB";
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        panel.setBackground(panelBackground(8, COLOR_PANEL, COLOR_BORDER));
        return panel;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams weightParam(float weight, int rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(0, 0, rightMargin, 0);
        return params;
    }

    private void addSectionTitle(LinearLayout parent, String title) {
        TextView text = textView(16f, COLOR_TEXT, true);
        text.setText(title);
        text.setPadding(0, 0, 0, dp(8));
        parent.addView(text, matchWrap());
    }

    private TextView textView(float size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setLineSpacing(0f, 1.12f);
        if (bold) {
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return text;
    }

    private TextView detailText(boolean monospace) {
        TextView text = textView(13f, COLOR_TEXT, false);
        text.setTextIsSelectable(true);
        if (monospace) {
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextSize(12f);
        }
        return text;
    }

    private CheckBox switchView(String text) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextSize(14f);
        box.setTextColor(COLOR_TEXT);
        box.setPadding(0, dp(2), 0, dp(2));
        return box;
    }

    private Button actionButton(String text, int color, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setBackground(panelBackground(8, color, color));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setOnClickListener(listener);
        return button;
    }

    private Button smallButton(String text) {
        return smallButton(text, null);
    }

    private Button smallButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12f);
        button.setTextColor(COLOR_PRIMARY);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), dp(5), dp(10), dp(5));
        button.setBackground(panelBackground(8, Color.rgb(239, 246, 255), Color.rgb(191, 219, 254)));
        if (listener != null) {
            button.setOnClickListener(listener);
        }
        return button;
    }

    private Button detailButton(String title, View.OnClickListener listener) {
        return smallButton(title, listener);
    }

    private void addButtonGrid(LinearLayout parent, Button left, Button right) {
        LinearLayout row = horizontalRow();
        row.addView(left, weightParam(1f, dp(8)));
        row.addView(right, weightParam(1f, 0));
        parent.addView(row, margin(matchWrap(), 0, 0, 0, dp(8)));
    }

    private GradientDrawable panelBackground(int radiusDp, int color, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams margin(LinearLayout.LayoutParams params, int left, int top, int right, int bottom) {
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void copyText(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text == null ? "" : text));
        Toast.makeText(this, label + "已复制", Toast.LENGTH_SHORT).show();
    }

    private static final class ManagerState {
        boolean asc;
        String filterMode = FILTER_ALL;
        String filterNote = "";
        LinearLayout list;
        TextView summary;
        Button filterButton;
        Button sortButton;
        AlertDialog dialog;
        final Set<String> selected = new HashSet<String>();
        final List<AgentStore.ExportGroup> visibleGroups = new ArrayList<AgentStore.ExportGroup>();
    }

    private static final class NoteFilterOption {
        final String mode;
        final String note;
        final String label;

        NoteFilterOption(String mode, String note, String label) {
            this.mode = mode;
            this.note = note == null ? "" : note;
            this.label = label == null ? "" : label;
        }

        boolean matches(String currentMode, String currentNote) {
            if (!mode.equals(currentMode)) {
                return false;
            }
            if (!FILTER_NOTE.equals(mode)) {
                return true;
            }
            String left = note == null ? "" : note.trim();
            String right = currentNote == null ? "" : currentNote.trim();
            return left.equals(right);
        }
    }
}
