package com.viatabs.agent;

import android.app.Application;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "ViaTabsAgent";
    private static final String VIA_CN = "mark.via";
    private static final String VIA_GP = "mark.via.gp";
    private static volatile boolean installed;
    private static volatile boolean receiverRegistered;
    private static volatile Context appContext;
    private static volatile Object lastTabManager;
    private static volatile Handler mainHandler;
    private static volatile boolean restoringAgentSession;
    private static final WeakHashMap<Activity, View> PANEL_BUTTONS = new WeakHashMap<Activity, View>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    private static final ThreadLocal<Boolean> IN_MANAGER_SNAPSHOT = new ThreadLocal<Boolean>();
    private static final Map<String, String> LAST_PAYLOAD = new HashMap<String, String>();
    private static final Map<String, Long> LAST_WRITE_AT = new HashMap<String, Long>();
    private static final long MIN_WRITE_INTERVAL_MS = 1000L;
    private static final String ACTION_OPEN_TEST_TABS = "com.viatabs.agent.OPEN_TEST_TABS";
    private static final String ACTION_DUMP_TABS = "com.viatabs.agent.DUMP_TABS";
    private static final String ACTION_SWITCH_TAB = "com.viatabs.agent.SWITCH_TAB";
    private static final String ACTION_SET_RESTORE_ALWAYS = "com.viatabs.agent.SET_RESTORE_ALWAYS";
    private static final String ACTION_CLOSE_VIA = "com.viatabs.agent.CLOSE_VIA";
    private static final String ACTION_RESTORE_AGENT_SESSION = "com.viatabs.agent.RESTORE_AGENT_SESSION";
    private static final String ACTION_SAVE_TABS_TO_BOOKMARKS = "com.viatabs.agent.SAVE_TABS_TO_BOOKMARKS";
    private static final String ACTION_GROUP_TABS = "com.viatabs.agent.GROUP_TABS";
    private static final String ACTION_RESTORE_TAB_GROUP = "com.viatabs.agent.RESTORE_TAB_GROUP";
    private static final String ACTION_ARCHIVE_TAB_GROUP = "com.viatabs.agent.ARCHIVE_TAB_GROUP";
    private static final String ACTION_DELETE_TAB_GROUP = "com.viatabs.agent.DELETE_TAB_GROUP";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!VIA_CN.equals(lpparam.packageName) && !VIA_GP.equals(lpparam.packageName)) {
            return;
        }

        log("loaded in " + lpparam.packageName);
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (installed) {
                    return;
                }
                installed = true;
                appContext = (Context) param.args[0];
                mainHandler = new Handler(Looper.getMainLooper());
                ClassLoader classLoader = appContext.getClassLoader();
                captureTabManagerConstructors(classLoader);
                installVia700Hooks(classLoader);
                installWebViewFallback(classLoader);
                installViaPanelHook();
                registerDebugReceiver();
            }
        });
    }

    private static void installViaPanelHook() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Activity) {
                        maybeInstallPanelButton((Activity) param.thisObject);
                    }
                }
            });
            log("hooked Activity.onResume for panel");
        } catch (Throwable t) {
            log("failed to hook Activity.onResume for panel: " + t);
        }
    }

    private static void captureTabManagerConstructors(ClassLoader classLoader) {
        try {
            Class<?> managerClass = XposedHelpers.findClass("e.h.a.e.c", classLoader);
            XposedBridge.hookAllConstructors(managerClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    lastTabManager = param.thisObject;
                    snapshotFromLastTabManager("constructor");
                    scheduleAgentSessionRestore("constructor", 2500L);
                }
            });
            log("hooked e.h.a.e.c constructors");
        } catch (Throwable t) {
            log("failed to hook e.h.a.e.c constructors: " + t);
        }
    }

    private static void registerDebugReceiver() {
        if (receiverRegistered || appContext == null) {
            return;
        }
        receiverRegistered = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_OPEN_TEST_TABS);
        filter.addAction(ACTION_DUMP_TABS);
        filter.addAction(ACTION_SWITCH_TAB);
        filter.addAction(ACTION_SET_RESTORE_ALWAYS);
        filter.addAction(ACTION_CLOSE_VIA);
        filter.addAction(ACTION_RESTORE_AGENT_SESSION);
        filter.addAction(ACTION_SAVE_TABS_TO_BOOKMARKS);
        filter.addAction(ACTION_GROUP_TABS);
        filter.addAction(ACTION_RESTORE_TAB_GROUP);
        filter.addAction(ACTION_ARCHIVE_TAB_GROUP);
        filter.addAction(ACTION_DELETE_TAB_GROUP);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                String action = intent.getAction();
                if (ACTION_OPEN_TEST_TABS.equals(action)) {
                    int count = intent.getIntExtra("count", 10);
                    String prefix = intent.getStringExtra("prefix");
                    openTestTabs(count, prefix == null ? "https://example.com/?via_tab=" : prefix);
                } else if (ACTION_DUMP_TABS.equals(action)) {
                    snapshotFromLastTabManager("broadcast.dump");
                } else if (ACTION_SWITCH_TAB.equals(action)) {
                    switchTab(intent.getIntExtra("index", 0));
                } else if (ACTION_SET_RESTORE_ALWAYS.equals(action)) {
                    setRestoreAlways();
                } else if (ACTION_CLOSE_VIA.equals(action)) {
                    android.os.Process.killProcess(android.os.Process.myPid());
                } else if (ACTION_RESTORE_AGENT_SESSION.equals(action)) {
                    restoreAgentSession("broadcast.restoreAgentSession");
                } else if (ACTION_SAVE_TABS_TO_BOOKMARKS.equals(action)) {
                    String folder = intent.getStringExtra("folder");
                    saveCurrentTabsToBookmarks(folder == null ? "ViaTabsAgent" : folder);
                } else if (ACTION_GROUP_TABS.equals(action)) {
                    String groupId = intent.getStringExtra("groupId");
                    String group = intent.getStringExtra("group");
                    boolean saveBookmarks = intent.getBooleanExtra("bookmarks", true);
                    String color = intent.getStringExtra("color");
                    boolean archived = intent.getBooleanExtra("archived", false);
                    boolean closeTabs = intent.getBooleanExtra("close", false);
                    groupCurrentTabs(groupId, group, color, saveBookmarks, archived, closeTabs);
                } else if (ACTION_RESTORE_TAB_GROUP.equals(action)) {
                    restoreTabGroup(intent.getStringExtra("groupId"), intent.getStringExtra("group"),
                            intent.getBooleanExtra("dedupe", true),
                            intent.getIntExtra("index", -1),
                            intent.getStringExtra("url"));
                } else if (ACTION_ARCHIVE_TAB_GROUP.equals(action)) {
                    updateTabGroupArchive(intent.getStringExtra("groupId"), intent.getStringExtra("group"),
                            intent.getBooleanExtra("archived", true));
                } else if (ACTION_DELETE_TAB_GROUP.equals(action)) {
                    deleteTabGroup(intent.getStringExtra("groupId"), intent.getStringExtra("group"));
                }
            }
        };

        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(receiver, filter);
        }
        log("registered debug receiver");
    }

    private static void setRestoreAlways() {
        try {
            Class<?> settingsAccessor = XposedHelpers.findClass("k.a.d0.s", appContext.getClassLoader());
            Object settings = XposedHelpers.callStaticMethod(settingsAccessor, "j");
            XposedHelpers.callMethod(settings, "b2", 1);
            Object value = XposedHelpers.callMethod(settings, "O");
            log("restoreclosedtabs set to " + value + " (1=always restore)");
        } catch (Throwable t) {
            log("set restore always failed: " + t);
            try {
                appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .edit()
                        .putInt("restoreclosedtabs", 1)
                        .apply();
                log("restoreclosedtabs fallback wrote to settings.xml");
            } catch (Throwable fallback) {
                log("restoreclosedtabs fallback failed: " + fallback);
            }
        }
    }

    private static void openTestTabs(final int count, final String prefix) {
        final Object manager = lastTabManager;
        if (manager == null || mainHandler == null) {
            log("openTestTabs skipped: tab manager not ready");
            return;
        }
        int safeCount = Math.max(1, Math.min(count, 50));
        for (int i = 1; i <= safeCount; i++) {
            final int index = i;
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        String url = prefix + index;
                        XposedHelpers.callMethod(manager, "K", url, 0);
                        log("opened test tab " + index + ": " + url);
                        snapshotFromLastTabManager("broadcast.openTestTabs");
                    } catch (Throwable t) {
                        log("open test tab failed: " + t);
                    }
                }
            }, i * 250L);
        }
    }

    private static void switchTab(final int index) {
        final Object manager = lastTabManager;
        if (manager == null || mainHandler == null) {
            log("switchTab skipped: tab manager not ready");
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    XposedHelpers.callMethod(manager, "V", Math.max(0, index));
                    log("switched tab to " + index);
                    snapshotFromLastTabManager("broadcast.switchTab");
                } catch (Throwable t) {
                    log("switch tab failed: " + t);
                }
            }
        });
    }

    private static void maybeInstallPanelButton(final Activity activity) {
        if (activity == null || activity.isFinishing() || PANEL_BUTTONS.containsKey(activity)) {
            return;
        }
        String packageName = activity.getPackageName();
        if (!VIA_CN.equals(packageName) && !VIA_GP.equals(packageName)) {
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        final TextView button = new TextView(activity);
        button.setText("标签");
        button.setTextColor(0xffffffff);
        button.setTextSize(12f);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(0xcc1f6feb);
        button.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(activity, 58), dp(activity, 42));
        params.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        params.rightMargin = dp(activity, 8);
        try {
            content.addView(button, params);
            PANEL_BUTTONS.put(activity, button);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showOperationPanel(activity);
                }
            });
            log("installed operation panel button");
        } catch (Throwable t) {
            log("install panel button failed: " + t);
        }
    }

    private static void showOperationPanel(final Activity activity) {
        final String[] actions = new String[]{
                "保存当前标签到书签",
                "创建标签分组",
                "恢复最近分组",
                "归档最近分组",
                "删除最近分组",
                "刷新标签快照"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Via 标签")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            promptSaveBookmarks(activity);
                        } else if (which == 1) {
                            promptCreateGroup(activity);
                        } else if (which == 2) {
                            restoreLatestGroupFromPanel(activity);
                        } else if (which == 3) {
                            archiveLatestGroupFromPanel(activity);
                        } else if (which == 4) {
                            deleteLatestGroupFromPanel(activity);
                        } else if (which == 5) {
                            snapshotFromLastTabManager("panel.dump");
                            toast(activity, "已刷新标签快照");
                        }
                    }
                })
                .show();
    }

    private static void promptSaveBookmarks(final Activity activity) {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(defaultGroupName());
        input.setHint("书签文件夹名称");
        new AlertDialog.Builder(activity)
                .setTitle("保存当前标签")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        saveCurrentTabsToBookmarks(textOrDefault(input, "ViaTabsAgent"), activity);
                        toast(activity, "正在保存到书签");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void promptCreateGroup(final Activity activity) {
        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        layout.setPadding(pad, pad / 2, pad, 0);

        final EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setText(defaultGroupName());
        name.setHint("分组名称");
        layout.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText color = new EditText(activity);
        color.setSingleLine(true);
        color.setText("绿色");
        color.setHint("颜色：蓝色、绿色、紫色...");
        layout.addView(color, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final CheckBox bookmarks = new CheckBox(activity);
        bookmarks.setText("同时保存到 Via 书签");
        bookmarks.setChecked(true);
        layout.addView(bookmarks, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final CheckBox archived = new CheckBox(activity);
        archived.setText("创建后归档该分组");
        archived.setChecked(false);
        layout.addView(archived, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(activity)
                .setTitle("创建标签分组")
                .setView(layout)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        groupCurrentTabs(null, textOrDefault(name, defaultGroupName()),
                                textOrDefault(color, "blue"), bookmarks.isChecked(), archived.isChecked(), false,
                                activity);
                        toast(activity, "正在创建分组");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void restoreLatestGroupFromPanel(Activity activity) {
        try {
            JSONObject latest = latestTabGroup(false);
            if (latest == null) {
                toast(activity, "没有可恢复的分组");
                return;
            }
            restoreTabGroup(latest.optString("groupId"), null, true, -1, null, activity);
            toast(activity, "正在恢复：" + latest.optString("group", "分组"));
        } catch (Throwable t) {
            log("panel restore latest failed: " + t);
            toast(activity, "恢复失败");
        }
    }

    private static void archiveLatestGroupFromPanel(Activity activity) {
        try {
            JSONObject latest = latestTabGroup(false);
            if (latest == null) {
                toast(activity, "没有可归档的分组");
                return;
            }
            updateTabGroupArchive(latest.optString("groupId"), null, true, activity);
            toast(activity, "正在归档：" + latest.optString("group", "分组"));
        } catch (Throwable t) {
            log("panel archive latest failed: " + t);
            toast(activity, "归档失败");
        }
    }

    private static void deleteLatestGroupFromPanel(Activity activity) {
        try {
            JSONObject latest = latestTabGroup(true);
            if (latest == null) {
                toast(activity, "没有可删除的分组");
                return;
            }
            deleteTabGroup(latest.optString("groupId"), null, activity);
            toast(activity, "正在删除：" + latest.optString("group", "分组"));
        } catch (Throwable t) {
            log("panel delete latest failed: " + t);
            toast(activity, "删除失败");
        }
    }

    private static JSONObject latestTabGroup(boolean includeArchived) throws Exception {
        JSONArray groups = readTabGroups(new File(agentDir(), "tab-groups.json"));
        for (int i = groups.length() - 1; i >= 0; i--) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) {
                continue;
            }
            if (includeArchived || !group.optBoolean("archived", false)) {
                return group;
            }
        }
        return null;
    }

    private static String defaultGroupName() {
        return "Via 标签 " + (System.currentTimeMillis() / 1000L);
    }

    private static String textOrDefault(EditText input, String fallback) {
        if (input == null || input.getText() == null) {
            return fallback;
        }
        String value = input.getText().toString().trim();
        return value.length() == 0 ? fallback : value;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void toast(final Context context, final String message) {
        if (context == null || mainHandler == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String shortError(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String message = t.getMessage();
        if (message == null || message.trim().length() == 0) {
            message = t.getClass().getSimpleName();
        }
        message = message.trim();
        return message.length() > 48 ? message.substring(0, 48) : message;
    }

    private static void installVia700Hooks(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("e.h.a.e.c", classLoader, "C", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    lastTabManager = param.thisObject;
                    if (Boolean.TRUE.equals(IN_MANAGER_SNAPSHOT.get())) {
                        return;
                    }
                    Object result = param.getResult();
                    if (result instanceof List) {
                        writeTabsSnapshot("tabManager.C", (List<?>) result, null);
                    }
                }
            });
            log("hooked e.h.a.e.c.C()");
        } catch (Throwable t) {
            log("failed to hook e.h.a.e.c.C(): " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("e.h.a.e.c", classLoader, "d", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    lastTabManager = param.thisObject;
                    Object result = param.getResult();
                    if (result instanceof Bundle) {
                        expandBundleToFullTabList(param.thisObject, (Bundle) result);
                        writeBundleSnapshot("tabManager.d", (Bundle) result);
                    }
                }
            });
            log("hooked e.h.a.e.c.d()");
        } catch (Throwable t) {
            log("failed to hook e.h.a.e.c.d(): " + t);
        }

        hookMutation(classLoader, "K", String.class, int.class);
        hookMutation(classLoader, "V", int.class);
        hookMutation(classLoader, "U", Bundle.class);
    }

    private static void hookMutation(ClassLoader classLoader, String methodName, Object... signature) {
        try {
            Object[] args = new Object[signature.length + 1];
            System.arraycopy(signature, 0, args, 0, signature.length);
            args[signature.length] = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    lastTabManager = param.thisObject;
                    snapshotFromLastTabManager(param.method.getName());
                    if ("U".equals(param.method.getName())) {
                        scheduleAgentSessionRestore("after.U", 1200L);
                    }
                }
            };
            XposedHelpers.findAndHookMethod("e.h.a.e.c", classLoader, methodName, args);
            log("hooked e.h.a.e.c." + methodName + "()");
        } catch (Throwable t) {
            log("failed to hook e.h.a.e.c." + methodName + "(): " + t);
        }
    }

    private static void installWebViewFallback(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("e.h.a.g.a", classLoader, "getUrl", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(IN_MANAGER_SNAPSHOT.get())) {
                        return;
                    }
                    Object result = param.getResult();
                    if (result instanceof String) {
                        snapshotFromLastTabManager("viaWebView.getUrl");
                        writeSingleUrlSnapshot("viaWebView.getUrl", (String) result);
                    }
                }
            });
            log("hooked e.h.a.g.a.getUrl()");
        } catch (Throwable t) {
            log("failed to hook e.h.a.g.a.getUrl(): " + t);
        }
    }

    private static void writeBundleSnapshot(String source, Bundle bundle) {
        try {
            JSONObject root = baseSnapshot(source);
            root.put("current", bundle.getInt("CUR", -1));
            root.put("title", bundle.getString("TITLE"));
            root.put("url", bundle.getString("URL"));
            root.put("keys", new JSONArray(bundle.keySet()));

            Object list = bundle.get("LIST");
            if (list instanceof List) {
                root.put("tabs", toUrlArray((List<?>) list));
            } else if (list instanceof Parcelable[]) {
                root.put("tabs", toBundleUrlArray((Parcelable[]) list));
            } else if (list instanceof Object[]) {
                root.put("tabs", toObjectBundleUrlArray((Object[]) list));
            }
            writeJson(root);
        } catch (Throwable t) {
            log("write bundle snapshot failed: " + t);
        }
    }

    private static void writeTabsSnapshot(String source, List<?> urls, Integer current) {
        try {
            JSONObject root = baseSnapshot(source);
            if (current != null) {
                root.put("current", current.intValue());
            }
            root.put("tabs", toUrlArray(urls));
            boolean wrote = writeJson(root);
            if (wrote && isTabManagerSource(source)) {
                persistAgentSession(source, urls);
            }
        } catch (Throwable t) {
            log("write tabs snapshot failed: " + t);
        }
    }

    private static void writeSingleUrlSnapshot(String source, String url) {
        try {
            JSONObject root = baseSnapshot(source);
            JSONArray tabs = new JSONArray();
            JSONObject tab = new JSONObject();
            tab.put("url", url);
            tabs.put(tab);
            root.put("tabs", tabs);
            writeJson(root);
        } catch (Throwable t) {
            log("write single url snapshot failed: " + t);
        }
    }

    private static void snapshotFromLastTabManager(String reason) {
        Object manager = lastTabManager;
        if (manager == null || Boolean.TRUE.equals(IN_MANAGER_SNAPSHOT.get())) {
            return;
        }
        try {
            IN_MANAGER_SNAPSHOT.set(Boolean.TRUE);
            Object urls = XposedHelpers.callMethod(manager, "C");
            if (urls instanceof List) {
                writeTabsSnapshot("tabManager.cachedAfter." + reason, (List<?>) urls, null);
            }
        } catch (Throwable t) {
            log("cached tab manager snapshot failed: " + t);
        } finally {
            IN_MANAGER_SNAPSHOT.remove();
        }
    }

    private static void saveCurrentTabsToBookmarks(final String folderName) {
        saveCurrentTabsToBookmarks(folderName, null);
    }

    private static void saveCurrentTabsToBookmarks(final String folderName, final Context uiContext) {
        final Object manager = lastTabManager;
        if (manager == null || mainHandler == null) {
            log("saveCurrentTabsToBookmarks skipped: tab manager not ready");
            toast(uiContext, "Via 标签管理器未就绪，请先打开一个网页或稍后重试");
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<TabRecord> tabs = snapshotTabRecords(manager);
                    if (tabs.size() == 0) {
                        toast(uiContext, "没有读取到可保存的标签");
                        return;
                    }
                    WRITER.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                BookmarkSaveResult result = saveTabsToViaBookmarks(folderName, tabs);
                                writeBookmarkSaveSnapshot(folderName, tabs, result);
                                log("saved tabs to bookmarks: folder=" + folderName + " tabs=" + tabs.size()
                                        + " inserted=" + result.inserted + " skipped=" + result.skipped);
                                toast(uiContext, "已保存 " + result.inserted + " 个标签，跳过 " + result.skipped + " 个");
                            } catch (Throwable t) {
                                log("saveCurrentTabsToBookmarks background failed: " + t);
                                toast(uiContext, "保存失败：" + shortError(t));
                            }
                        }
                    });
                } catch (Throwable t) {
                    log("saveCurrentTabsToBookmarks failed: " + t);
                    toast(uiContext, "保存失败：" + shortError(t));
                }
            }
        });
    }

    private static void groupCurrentTabs(final String groupId, final String groupName, final String color,
                                         final boolean saveBookmarks, final boolean archived,
                                         final boolean closeTabs) {
        groupCurrentTabs(groupId, groupName, color, saveBookmarks, archived, closeTabs, null);
    }

    private static void groupCurrentTabs(final String groupId, final String groupName, final String color,
                                         final boolean saveBookmarks, final boolean archived,
                                         final boolean closeTabs, final Context uiContext) {
        final Object manager = lastTabManager;
        if (manager == null || mainHandler == null) {
            log("groupCurrentTabs skipped: tab manager not ready");
            toast(uiContext, "Via 标签管理器未就绪，请先打开一个网页或稍后重试");
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                final String safeGroup = groupName == null || groupName.trim().length() == 0
                        ? "ViaTabs " + System.currentTimeMillis()
                        : groupName.trim();
                try {
                    final List<TabRecord> tabs = snapshotTabRecords(manager);
                    if (tabs.size() == 0) {
                        toast(uiContext, "没有读取到可分组的标签");
                        return;
                    }
                    WRITER.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                BookmarkSaveResult result = saveBookmarks ? saveTabsToViaBookmarks(safeGroup, tabs) : new BookmarkSaveResult();
                                writeTabGroupSnapshot(groupId, safeGroup, color, archived, closeTabs, tabs, result);
                                log("grouped tabs: group=" + safeGroup + " tabs=" + tabs.size()
                                        + " bookmarks=" + saveBookmarks + " inserted=" + result.inserted
                                        + " archived=" + archived + " closeRequested=" + closeTabs);
                                toast(uiContext, "已创建分组：" + safeGroup + "（" + tabs.size() + " 个标签）");
                            } catch (Throwable t) {
                                log("groupCurrentTabs background failed: " + t);
                                toast(uiContext, "创建分组失败：" + shortError(t));
                            }
                        }
                    });
                } catch (Throwable t) {
                    log("groupCurrentTabs failed: " + t);
                    toast(uiContext, "创建分组失败：" + shortError(t));
                }
            }
        });
    }

    private static List<TabRecord> snapshotTabRecords(Object manager) {
        ArrayList<TabRecord> records = new ArrayList<TabRecord>();
        Object urls = XposedHelpers.callMethod(manager, "C");
        List<String> cleanUrls = urls instanceof List ? normalizeUrls((List<?>) urls) : new ArrayList<String>();
        Object rawTabs = null;
        try {
            rawTabs = XposedHelpers.getObjectField(manager, "c");
        } catch (Throwable ignored) {
        }
        List<?> tabs = rawTabs instanceof List ? (List<?>) rawTabs : null;
        for (int i = 0; i < cleanUrls.size(); i++) {
            String url = cleanUrls.get(i);
            String title = null;
            if (tabs != null && i < tabs.size()) {
                title = titleFromViaTab(tabs.get(i));
            }
            if (title == null || title.trim().length() == 0) {
                title = titleFromUrl(url);
            }
            records.add(new TabRecord(i, title, url));
        }
        return records;
    }

    private static String titleFromViaTab(Object tab) {
        try {
            Object webView = XposedHelpers.callMethod(tab, "d");
            if (webView != null) {
                Object title = XposedHelpers.callMethod(webView, "getTitle");
                if (title instanceof String && ((String) title).trim().length() > 0) {
                    return ((String) title).trim();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Object state = XposedHelpers.callMethod(tab, "f");
            if (state instanceof Bundle) {
                String title = ((Bundle) state).getString("title");
                if (title != null && title.trim().length() > 0) {
                    return title.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String titleFromUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null && host.length() > 0) {
                return host;
            }
        } catch (Throwable ignored) {
        }
        return url;
    }

    private static BookmarkSaveResult saveTabsToViaBookmarks(String folderName, List<TabRecord> tabs) throws Exception {
        BookmarkSaveResult result = new BookmarkSaveResult();
        if (appContext == null || tabs.isEmpty()) {
            return result;
        }
        SQLiteDatabase db = null;
        boolean transactionStarted = false;
        try {
            db = appContext.openOrCreateDatabase("via", Context.MODE_PRIVATE, null);
            ensureBookmarkTables(db);
            db.beginTransaction();
            transactionStarted = true;
            String folderId = ensureBookmarkFolder(db, folderName);
            int ordering = maxOrdering(db, "bookmark_items", "folder_id", folderId) + 1;
            long now = System.currentTimeMillis() / 1000L;
            for (TabRecord tab : tabs) {
                if (!isBookmarkableUrl(tab.url)) {
                    result.skipped++;
                    continue;
                }
                if (bookmarkExists(db, folderId, tab.url)) {
                    result.skipped++;
                    continue;
                }
                ContentValues values = new ContentValues();
                values.put("_id", UUID.randomUUID().toString());
                values.put("url", tab.url);
                values.put("title", tab.title);
                values.put("folder_id", folderId);
                values.put("ordering", ordering++);
                values.put("created_at", now);
                values.put("last_updated_at", now);
                long row = db.insert("bookmark_items", null, values);
                if (row >= 0) {
                    result.inserted++;
                } else {
                    result.skipped++;
                }
            }
            db.setTransactionSuccessful();
            result.folderId = folderId;
        } finally {
            if (db != null) {
                try {
                    if (transactionStarted) {
                        db.endTransaction();
                    }
                } finally {
                    db.close();
                }
            }
        }
        return result;
    }

    private static boolean isBookmarkableUrl(String url) {
        if (url == null) {
            return false;
        }
        String normalized = url.trim().toLowerCase();
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private static void ensureBookmarkTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmark_items(_id TEXT PRIMARY KEY,url TEXT,title TEXT,folder_id TEXT,ordering INTEGER,last_updated_at INTEGER DEFAULT 0,created_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmark_folders(_id TEXT PRIMARY KEY,title TEXT,parent_folder_id TEXT,ordering INTEGER,created_at INTEGER DEFAULT 0,last_updated_at INTEGER DEFAULT 0)");
    }

    private static String ensureBookmarkFolder(SQLiteDatabase db, String folderName) {
        String safeName = folderName == null || folderName.trim().length() == 0 ? "ViaTabsAgent" : folderName.trim();
        String existing = findBookmarkFolder(db, safeName, "");
        if (existing != null) {
            return existing;
        }
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000L;
        ContentValues values = new ContentValues();
        values.put("_id", id);
        values.put("title", safeName);
        values.put("parent_folder_id", "");
        values.put("ordering", maxOrdering(db, "bookmark_folders", "parent_folder_id", "") + 1);
        values.put("created_at", now);
        values.put("last_updated_at", now);
        db.insert("bookmark_folders", null, values);
        return id;
    }

    private static String findBookmarkFolder(SQLiteDatabase db, String title, String parentId) {
        Cursor cursor = null;
        try {
            cursor = db.query("bookmark_folders", new String[]{"_id"}, "title = ? AND parent_folder_id = ?",
                    new String[]{title, parentId}, null, null, null, "1");
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private static boolean bookmarkExists(SQLiteDatabase db, String folderId, String url) {
        Cursor cursor = null;
        try {
            cursor = db.query("bookmark_items", new String[]{"_id"}, "folder_id = ? AND url = ?",
                    new String[]{folderId, url}, null, null, null, "1");
            return cursor.moveToFirst();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static int maxOrdering(SQLiteDatabase db, String table, String parentColumn, String parentValue) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT MAX(ordering) FROM " + table + " WHERE " + parentColumn + " = ?",
                    new String[]{parentValue});
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getInt(0);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return -1;
    }

    private static void writeBookmarkSaveSnapshot(String folderName, List<TabRecord> tabs, BookmarkSaveResult result) {
        try {
            JSONObject root = baseSnapshot("bookmarks.saveTabs");
            root.put("folder", folderName);
            root.put("folderId", result.folderId == null ? JSONObject.NULL : result.folderId);
            root.put("inserted", result.inserted);
            root.put("skipped", result.skipped);
            root.put("tabs", toTabRecordArray(tabs));
            writeJsonToFileNow(new File(agentDir(), "saved-bookmarks.json"), root.toString(2));
        } catch (Throwable t) {
            log("write bookmark save snapshot failed: " + t);
        }
    }

    private static void writeTabGroupSnapshot(String requestedGroupId, String groupName, String color,
                                              boolean archived, boolean closeTabs,
                                              List<TabRecord> tabs, BookmarkSaveResult result) {
        try {
            String groupId = requestedGroupId == null || requestedGroupId.trim().length() == 0
                    ? UUID.randomUUID().toString()
                    : requestedGroupId.trim();
            JSONObject group = baseSnapshot("tabs.group");
            group.put("groupId", groupId);
            group.put("group", groupName);
            group.put("color", normalizeGroupColor(color));
            group.put("archived", archived);
            group.put("closeRequested", closeTabs);
            group.put("folderId", result.folderId == null ? JSONObject.NULL : result.folderId);
            group.put("inserted", result.inserted);
            group.put("skipped", result.skipped);
            group.put("tabCount", tabs.size());
            group.put("bookmarkableCount", countBookmarkableTabs(tabs));
            group.put("tabs", toTabRecordArray(tabs));

            File file = new File(agentDir(), "tab-groups.json");
            JSONArray groups = readTabGroups(file);
            groups.put(group);
            JSONObject root = baseSnapshot("tabs.groups");
            root.put("groups", groups);
            writeJsonToFileNow(file, root.toString(2));
        } catch (Throwable t) {
            log("write tab group snapshot failed: " + t);
        }
    }

    private static void restoreTabGroup(final String groupId, final String groupName, final boolean dedupe,
                                        final int targetIndex, final String targetUrl) {
        restoreTabGroup(groupId, groupName, dedupe, targetIndex, targetUrl, null);
    }

    private static void restoreTabGroup(final String groupId, final String groupName, final boolean dedupe,
                                        final int targetIndex, final String targetUrl, final Context uiContext) {
        final Object manager = lastTabManager;
        if (manager == null || mainHandler == null) {
            log("restoreTabGroup skipped: tab manager not ready");
            toast(uiContext, "Via 标签管理器未就绪，请先打开一个网页或稍后重试");
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject group = findTabGroup(groupId, groupName);
                    if (group == null) {
                        log("restoreTabGroup skipped: group not found");
                        toast(uiContext, "没有找到可恢复的分组");
                        return;
                    }
                    JSONArray tabs = group.optJSONArray("tabs");
                    if (tabs == null || tabs.length() == 0) {
                        log("restoreTabGroup skipped: group has no tabs");
                        toast(uiContext, "该分组没有可恢复的标签");
                        return;
                    }
                    Object rawCurrent = XposedHelpers.callMethod(manager, "C");
                    Set<String> current = new LinkedHashSet<String>(rawCurrent instanceof List
                            ? normalizeUrls((List<?>) rawCurrent)
                            : new ArrayList<String>());
                    int opened = 0;
                    for (int i = 0; i < tabs.length(); i++) {
                        JSONObject tab = tabs.optJSONObject(i);
                        if (tab == null) {
                            continue;
                        }
                        String url = tab.optString("url", "").trim();
                        if (!matchesRestoreTarget(tab, url, targetIndex, targetUrl)) {
                            continue;
                        }
                        if (!isBookmarkableUrl(url)) {
                            continue;
                        }
                        if (dedupe && current.contains(url)) {
                            continue;
                        }
                        XposedHelpers.callMethod(manager, "K", url, 0);
                        current.add(url);
                        opened++;
                    }
                    snapshotFromLastTabManager("tabGroup.restore");
                    log("restored tab group: groupId=" + group.optString("groupId") + " group="
                            + group.optString("group") + " opened=" + opened + " dedupe=" + dedupe
                            + " index=" + targetIndex);
                    toast(uiContext, opened > 0 ? "已恢复 " + opened + " 个标签" : "没有需要恢复的新标签");
                } catch (Throwable t) {
                    log("restoreTabGroup failed: " + t);
                    toast(uiContext, "恢复失败：" + shortError(t));
                }
            }
        });
    }

    private static void updateTabGroupArchive(final String groupId, final String groupName, final boolean archived) {
        updateTabGroupArchive(groupId, groupName, archived, null);
    }

    private static void updateTabGroupArchive(final String groupId, final String groupName, final boolean archived,
                                              final Context uiContext) {
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File file = new File(agentDir(), "tab-groups.json");
                    JSONArray groups = readTabGroups(file);
                    boolean changed = false;
                    for (int i = 0; i < groups.length(); i++) {
                        JSONObject group = groups.optJSONObject(i);
                        if (matchesGroup(group, groupId, groupName)) {
                            group.put("archived", archived);
                            group.put("updatedAt", System.currentTimeMillis());
                            changed = true;
                        }
                    }
                    if (changed) {
                        writeTabGroups(file, groups);
                    }
                    log("archive tab group: changed=" + changed + " archived=" + archived);
                    toast(uiContext, changed ? (archived ? "已归档分组" : "已取消归档分组") : "没有找到可归档的分组");
                } catch (Throwable t) {
                    log("archive tab group failed: " + t);
                    toast(uiContext, "归档失败：" + shortError(t));
                }
            }
        });
    }

    private static void deleteTabGroup(final String groupId, final String groupName) {
        deleteTabGroup(groupId, groupName, null);
    }

    private static void deleteTabGroup(final String groupId, final String groupName, final Context uiContext) {
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File file = new File(agentDir(), "tab-groups.json");
                    JSONArray groups = readTabGroups(file);
                    JSONArray kept = new JSONArray();
                    boolean deleted = false;
                    for (int i = 0; i < groups.length(); i++) {
                        JSONObject group = groups.optJSONObject(i);
                        if (matchesGroup(group, groupId, groupName)) {
                            deleted = true;
                            continue;
                        }
                        kept.put(groups.get(i));
                    }
                    if (deleted) {
                        writeTabGroups(file, kept);
                    }
                    log("delete tab group: deleted=" + deleted);
                    toast(uiContext, deleted ? "已删除分组" : "没有找到可删除的分组");
                } catch (Throwable t) {
                    log("delete tab group failed: " + t);
                    toast(uiContext, "删除失败：" + shortError(t));
                }
            }
        });
    }

    private static JSONObject findTabGroup(String groupId, String groupName) throws Exception {
        JSONArray groups = readTabGroups(new File(agentDir(), "tab-groups.json"));
        for (int i = groups.length() - 1; i >= 0; i--) {
            JSONObject group = groups.optJSONObject(i);
            if (matchesGroup(group, groupId, groupName)) {
                return group;
            }
        }
        return null;
    }

    private static boolean matchesGroup(JSONObject group, String groupId, String groupName) {
        if (group == null) {
            return false;
        }
        String safeId = groupId == null ? "" : groupId.trim();
        if (safeId.length() > 0 && safeId.equals(group.optString("groupId", ""))) {
            return true;
        }
        String safeName = groupName == null ? "" : groupName.trim();
        return safeName.length() > 0 && safeName.equals(group.optString("group", ""));
    }

    private static boolean matchesRestoreTarget(JSONObject tab, String url, int targetIndex, String targetUrl) {
        String safeUrl = targetUrl == null ? "" : targetUrl.trim();
        if (safeUrl.length() > 0) {
            return safeUrl.equals(url);
        }
        return targetIndex < 0 || tab.optInt("index", -1) == targetIndex;
    }

    private static JSONArray readTabGroups(File file) throws Exception {
        if (!file.exists() || file.length() <= 0 || file.length() > 1024 * 1024) {
            return new JSONArray();
        }
        JSONObject existing = readJsonFile(file);
        JSONArray oldGroups = existing.optJSONArray("groups");
        return oldGroups == null ? new JSONArray() : oldGroups;
    }

    private static void writeTabGroups(File file, JSONArray groups) throws Exception {
        JSONObject root = baseSnapshot("tabs.groups");
        root.put("groups", groups);
        writeJsonToFileNow(file, root.toString(2));
    }

    private static int countBookmarkableTabs(List<TabRecord> tabs) {
        int count = 0;
        for (TabRecord tab : tabs) {
            if (isBookmarkableUrl(tab.url)) {
                count++;
            }
        }
        return count;
    }

    private static String normalizeGroupColor(String color) {
        if (color == null || color.trim().length() == 0) {
            return "blue";
        }
        String safe = color.trim().toLowerCase();
        if ("红色".equals(safe)) {
            return "red";
        } else if ("橙色".equals(safe)) {
            return "orange";
        } else if ("黄色".equals(safe)) {
            return "yellow";
        } else if ("绿色".equals(safe)) {
            return "green";
        } else if ("蓝色".equals(safe)) {
            return "blue";
        } else if ("紫色".equals(safe)) {
            return "purple";
        } else if ("粉色".equals(safe)) {
            return "pink";
        } else if ("灰色".equals(safe)) {
            return "gray";
        }
        if ("red".equals(safe) || "orange".equals(safe) || "yellow".equals(safe)
                || "green".equals(safe) || "blue".equals(safe) || "purple".equals(safe)
                || "pink".equals(safe) || "gray".equals(safe)) {
            return safe;
        }
        return "blue";
    }

    private static JSONObject readJsonFile(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        FileInputStream stream = new FileInputStream(file);
        try {
            int offset = 0;
            while (offset < data.length) {
                int read = stream.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        } finally {
            stream.close();
        }
        return new JSONObject(new String(data, StandardCharsets.UTF_8));
    }

    private static JSONArray toTabRecordArray(List<TabRecord> records) throws Exception {
        JSONArray tabs = new JSONArray();
        for (TabRecord record : records) {
            JSONObject tab = new JSONObject();
            tab.put("index", record.index);
            tab.put("title", record.title);
            tab.put("url", record.url);
            tabs.put(tab);
        }
        return tabs;
    }

    private static JSONObject baseSnapshot(String source) throws Exception {
        JSONObject root = new JSONObject();
        root.put("source", source);
        root.put("packageName", appContext == null ? JSONObject.NULL : appContext.getPackageName());
        root.put("timestamp", System.currentTimeMillis());
        return root;
    }

    private static JSONArray toUrlArray(List<?> urls) throws Exception {
        JSONArray tabs = new JSONArray();
        for (int i = 0; i < urls.size(); i++) {
            JSONObject tab = new JSONObject();
            Object value = urls.get(i);
            tab.put("index", i);
            tab.put("url", value == null ? JSONObject.NULL : String.valueOf(value));
            tabs.put(tab);
        }
        return tabs;
    }

    private static JSONArray toBundleUrlArray(Parcelable[] bundles) throws Exception {
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

    private static JSONArray toObjectBundleUrlArray(Object[] bundles) throws Exception {
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

    private static void expandBundleToFullTabList(Object manager, Bundle bundle) {
        try {
            Object rawTabs = XposedHelpers.getObjectField(manager, "c");
            if (!(rawTabs instanceof List)) {
                return;
            }
            List<?> tabs = (List<?>) rawTabs;
            if (tabs.isEmpty()) {
                return;
            }
            Parcelable[] existing = bundle.getParcelableArray("LIST");
            if (existing != null && existing.length >= tabs.size()) {
                return;
            }
            Parcelable[] fullStates = new Parcelable[tabs.size()];
            for (int i = 0; i < tabs.size(); i++) {
                Object tab = tabs.get(i);
                Object state = XposedHelpers.callMethod(tab, "f");
                if (state instanceof Bundle) {
                    fullStates[i] = (Bundle) state;
                }
            }
            int current = XposedHelpers.getIntField(manager, "e");
            bundle.putParcelableArray("LIST", fullStates);
            bundle.putInt("CUR", Math.max(0, Math.min(current, tabs.size() - 1)));
            log("expanded Via session bundle to full tab list: " + tabs.size());
        } catch (Throwable t) {
            log("expand session bundle failed: " + t);
        }
    }

    private static void persistAgentSession(String source, List<?> urls) {
        if (appContext == null || restoringAgentSession) {
            return;
        }
        try {
            List<String> cleanUrls = normalizeUrls(urls);
            if (cleanUrls.isEmpty()) {
                return;
            }
            List<String> existing = readAgentSessionUrls();
            if (!existing.isEmpty() && cleanUrls.size() < existing.size()) {
                log("kept larger agent session: current=" + cleanUrls.size() + " saved=" + existing.size());
                return;
            }
            JSONObject root = baseSnapshot("agentSession." + source);
            root.put("tabs", toStringUrlArray(cleanUrls));
            writeJsonToFile(new File(agentDir(), "agent-session.json"), root.toString(2));
        } catch (Throwable t) {
            log("persist agent session failed: " + t);
        }
    }

    private static void scheduleAgentSessionRestore(final String reason, long delayMs) {
        if (mainHandler == null) {
            return;
        }
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                restoreAgentSession(reason);
            }
        }, delayMs);
    }

    private static void restoreAgentSession(String reason) {
        final Object manager = lastTabManager;
        if (manager == null || mainHandler == null) {
            log("restoreAgentSession skipped: tab manager not ready");
            return;
        }
        final List<String> saved = readAgentSessionUrls();
        if (saved.isEmpty()) {
            log("restoreAgentSession skipped: no saved agent session");
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    restoringAgentSession = true;
                    Object rawCurrent = XposedHelpers.callMethod(manager, "C");
                    List<String> current = rawCurrent instanceof List ? normalizeUrls((List<?>) rawCurrent) : new ArrayList<String>();
                    Set<String> currentSet = new LinkedHashSet<String>(current);
                    int opened = 0;
                    for (String url : saved) {
                        if (currentSet.contains(url)) {
                            continue;
                        }
                        XposedHelpers.callMethod(manager, "K", url, 0);
                        currentSet.add(url);
                        opened++;
                    }
                    log("restoreAgentSession " + reason + ": saved=" + saved.size() + " current=" + current.size() + " opened=" + opened);
                } catch (Throwable t) {
                    log("restoreAgentSession failed: " + t);
                } finally {
                    restoringAgentSession = false;
                    snapshotFromLastTabManager("agentSession.restore");
                }
            }
        });
    }

    private static List<String> normalizeUrls(List<?> urls) {
        ArrayList<String> cleanUrls = new ArrayList<String>();
        for (Object value : urls) {
            if (value == null) {
                continue;
            }
            String url = String.valueOf(value).trim();
            if (url.length() == 0 || "null".equals(url)) {
                continue;
            }
            cleanUrls.add(url);
        }
        return cleanUrls;
    }

    private static JSONArray toStringUrlArray(List<String> urls) throws Exception {
        JSONArray tabs = new JSONArray();
        for (int i = 0; i < urls.size(); i++) {
            JSONObject tab = new JSONObject();
            tab.put("index", i);
            tab.put("url", urls.get(i));
            tabs.put(tab);
        }
        return tabs;
    }

    private static List<String> readAgentSessionUrls() {
        ArrayList<String> urls = new ArrayList<String>();
        try {
            File session = new File(agentDir(), "agent-session.json");
            if (!session.exists() || session.length() <= 0 || session.length() > 1024 * 1024) {
                return urls;
            }
            byte[] data = new byte[(int) session.length()];
            FileInputStream stream = new FileInputStream(session);
            try {
                int offset = 0;
                while (offset < data.length) {
                    int read = stream.read(data, offset, data.length - offset);
                    if (read < 0) {
                        break;
                    }
                    offset += read;
                }
            } finally {
                stream.close();
            }
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            JSONArray tabs = root.optJSONArray("tabs");
            if (tabs == null) {
                return urls;
            }
            for (int i = 0; i < tabs.length(); i++) {
                JSONObject tab = tabs.optJSONObject(i);
                if (tab == null) {
                    continue;
                }
                String url = tab.optString("url", "").trim();
                if (url.length() > 0 && !"null".equals(url)) {
                    urls.add(url);
                }
            }
        } catch (Throwable t) {
            log("read agent session failed: " + t);
        }
        return urls;
    }

    private static boolean writeJson(JSONObject root) throws Exception {
        if (appContext == null) {
            return false;
        }
        File dir = agentDir();
        final File out = new File(dir, isTabManagerSource(root.optString("source")) ? "tabs.json" : "webview-fallback.json");
        final String payload = root.toString(2);
        JSONObject signatureRoot = new JSONObject(root.toString());
        signatureRoot.remove("timestamp");
        final String signature = signatureRoot.toString();
        final String key = out.getAbsolutePath();
        long now = System.currentTimeMillis();
        synchronized (LAST_PAYLOAD) {
            String previous = LAST_PAYLOAD.get(key);
            Long previousAt = LAST_WRITE_AT.get(key);
            if (signature.equals(previous) && previousAt != null && now - previousAt.longValue() < MIN_WRITE_INTERVAL_MS) {
                return false;
            }
            LAST_PAYLOAD.put(key, signature);
            LAST_WRITE_AT.put(key, now);
        }
        writeJsonToFile(out, payload);
        return true;
    }

    private static File agentDir() {
        File dir = new File(appContext.getExternalFilesDir(null), "ViaTabsAgent");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        return dir;
    }

    private static void writeJsonToFile(final File out, final String payload) {
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                writeJsonToFileNow(out, payload);
            }
        });
    }

    private static void writeJsonToFileNow(File out, String payload) {
        FileOutputStream stream = null;
        try {
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            stream = new FileOutputStream(out, false);
            stream.write(data);
            log("wrote " + out.getAbsolutePath());
        } catch (Throwable t) {
            log("write file failed: " + t);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean isTabManagerSource(String source) {
        return source != null && source.startsWith("tabManager.");
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }

    private static final class TabRecord {
        final int index;
        final String title;
        final String url;

        TabRecord(int index, String title, String url) {
            this.index = index;
            this.title = title;
            this.url = url;
        }
    }

    private static final class BookmarkSaveResult {
        String folderId;
        int inserted;
        int skipped;
    }
}
