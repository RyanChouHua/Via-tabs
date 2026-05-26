package com.viatabs.agent;

import android.app.Application;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "ViaTabsAgent";
    private static final String VIA_CN = "mark.via";
    private static final String VIA_GP = "mark.via.gp";
    private static final String MODULE_PACKAGE = "com.viatabs.agent";
    private static volatile boolean installed;
    private static volatile boolean receiverRegistered;
    private static volatile Context appContext;
    private static volatile Object lastTabManager;
    private static volatile Handler mainHandler;
    private static volatile boolean restoringAgentSession;
    private static volatile boolean dynamicScanStarted;
    private static volatile Method tabListMethod;
    private static volatile Method openUrlMethod;
    private static volatile Method switchTabMethod;
    private static volatile Method sessionBundleMethod;
    private static final WeakHashMap<Activity, View> PANEL_BUTTONS = new WeakHashMap<Activity, View>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    private static final ThreadLocal<Boolean> IN_MANAGER_SNAPSHOT = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> IN_WEBVIEW_CAPTURE = new ThreadLocal<Boolean>();
    private static final Map<String, String> LAST_PAYLOAD = new HashMap<String, String>();
    private static final Map<String, Long> LAST_WRITE_AT = new HashMap<String, Long>();
    private static final Map<String, Long> LAST_FILE_LOG_AT = new HashMap<String, Long>();
    private static final Map<String, String> WEBVIEW_TITLES = new HashMap<String, String>();
    private static final long MIN_WRITE_INTERVAL_MS = 1000L;
    private static final long SNAPSHOT_LOG_INTERVAL_MS = 10000L;
    private static final String ACTION_OPEN_TEST_TABS = "com.viatabs.agent.OPEN_TEST_TABS";
    private static final String ACTION_DUMP_TABS = "com.viatabs.agent.DUMP_TABS";
    private static final String ACTION_SWITCH_TAB = "com.viatabs.agent.SWITCH_TAB";
    private static final String ACTION_SET_RESTORE_ALWAYS = "com.viatabs.agent.SET_RESTORE_ALWAYS";
    private static final String ACTION_CLOSE_VIA = "com.viatabs.agent.CLOSE_VIA";
    private static final String ACTION_RESTORE_AGENT_SESSION = "com.viatabs.agent.RESTORE_AGENT_SESSION";
    private static final String ACTION_SAVE_TABS_TO_BOOKMARKS = "com.viatabs.agent.SAVE_TABS_TO_BOOKMARKS";
    static final String ACTION_IMPORT_EXPORT_GROUPS = "com.viatabs.agent.IMPORT_EXPORT_GROUPS";
    static final String EXTRA_JSON_NAMES = "jsonNames";
    static final String EXTRA_HTML_NAMES = "htmlNames";
    private static final int PANEL_STATE_DEFAULT = 0;
    private static final int PANEL_STATE_READING = 1;
    private static final int PANEL_STATE_CONFIRM = 2;
    private static final int PANEL_STATE_SAVING = 3;
    private static final int PANEL_STATE_DONE = 4;
    private static final int PANEL_STATE_FAILED = 5;

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
                startDynamicTabManagerScan(classLoader);
                installWebViewFallback(classLoader);
                installViaPanelHook();
                registerDebugReceiver();
                log("module attached in " + appContext.getPackageName());
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
            cacheLegacyTabMethods(managerClass);
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

    private static void cacheLegacyTabMethods(Class<?> managerClass) {
        try {
            tabListMethod = managerClass.getDeclaredMethod("C");
            tabListMethod.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            sessionBundleMethod = managerClass.getDeclaredMethod("d");
            sessionBundleMethod.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            openUrlMethod = managerClass.getDeclaredMethod("K", String.class, int.class);
            openUrlMethod.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            switchTabMethod = managerClass.getDeclaredMethod("V", int.class);
            switchTabMethod.setAccessible(true);
        } catch (Throwable ignored) {
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
        filter.addAction(ACTION_IMPORT_EXPORT_GROUPS);

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
                    if (!isExportEnabled()) {
                        log("save broadcast ignored: export disabled");
                        return;
                    }
                    String folder = intent.getStringExtra("folder");
                    saveCurrentTabsToBookmarks(folder == null ? "书签" : folder);
                } else if (ACTION_IMPORT_EXPORT_GROUPS.equals(action)) {
                    importExportGroups(intent);
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
        final Method opener = openUrlMethod;
        if (manager == null || opener == null || mainHandler == null) {
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
                        opener.invoke(manager, url, 0);
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
        final Method switcher = switchTabMethod;
        if (manager == null || switcher == null || mainHandler == null) {
            log("switchTab skipped: tab manager not ready");
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    switcher.invoke(manager, Math.max(0, index));
                    log("switched tab to " + index);
                    snapshotFromLastTabManager("broadcast.switchTab");
                } catch (Throwable t) {
                    log("switch tab failed: " + t);
                }
            }
        });
    }

    private static void maybeInstallPanelButton(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        String packageName = activity.getPackageName();
        if (!VIA_CN.equals(packageName) && !VIA_GP.equals(packageName)) {
            return;
        }
        if (!isExportEnabled()) {
            removePanelButton(activity);
            log("panel button hidden: export disabled");
            return;
        }
        if (PANEL_BUTTONS.containsKey(activity)) {
            View existing = PANEL_BUTTONS.get(activity);
            if (existing instanceof TextView) {
                applyPanelButtonStyle((TextView) existing, PANEL_STATE_DEFAULT);
            }
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        ExportSettings settings = readExportSettings();
        final TextView button = new TextView(activity);
        applyPanelButtonStyle(button, PANEL_STATE_DEFAULT);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 2), dp(activity, 2));
        if (Build.VERSION.SDK_INT >= 21) {
            button.setElevation(dp(activity, 6));
        }
        button.setContentDescription("保存当前标签页到书签");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(activity, settings.panelSize), dp(activity, settings.panelSize));
        params.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        params.rightMargin = dp(activity, 10);
        try {
            content.addView(button, params);
            PANEL_BUTTONS.put(activity, button);
            restorePanelPosition(button, activity);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    handlePanelButtonClick(button, activity);
                }
            });
            button.setOnTouchListener(new View.OnTouchListener() {
                private float downRawX;
                private float downRawY;
                private float startX;
                private float startY;
                private boolean dragging;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (!button.isEnabled()) {
                        return false;
                    }
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downRawX = event.getRawX();
                            downRawY = event.getRawY();
                            startX = button.getX();
                            startY = button.getY();
                            dragging = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - downRawX;
                            float dy = event.getRawY() - downRawY;
                            if (!dragging && Math.hypot(dx, dy) > dp(activity, 6)) {
                                dragging = true;
                            }
                            if (dragging) {
                                movePanelButton(button, startX + dx, startY + dy);
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (dragging) {
                                savePanelPosition(button, activity);
                            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                                button.performClick();
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });
            log("installed draggable bookmark count button");
        } catch (Throwable t) {
            log("install panel button failed: " + t);
        }
    }

    private static void handlePanelButtonClick(TextView button, Activity activity) {
        if (!isExportEnabled()) {
            toast(activity, "导出已关闭");
            log("panel export ignored: export disabled");
            return;
        }
        log("panel export clicked");
        previewCurrentTabsToBookmarks(button, activity);
    }

    private static void restorePanelPosition(final TextView button, final Activity activity) {
        if (button == null || activity == null) {
            return;
        }
        button.post(new Runnable() {
            @Override
            public void run() {
                try {
                    float savedX = AgentStore.getPanelX(activity, activity.getPackageName());
                    float savedY = AgentStore.getPanelY(activity, activity.getPackageName());
                    if (Float.isNaN(savedX) || Float.isNaN(savedY)) {
                        View parent = button.getParent() instanceof View ? (View) button.getParent() : null;
                        if (parent != null) {
                            savedX = parent.getWidth() - button.getWidth() - dp(activity, 10);
                            savedY = (parent.getHeight() - button.getHeight()) * 0.58f;
                        }
                    }
                    if (!Float.isNaN(savedX) && !Float.isNaN(savedY)) {
                        movePanelButton(button, savedX, savedY);
                    }
                } catch (Throwable t) {
                    log("restore panel position failed: " + t);
                }
            }
        });
    }

    private static void movePanelButton(TextView button, float x, float y) {
        View parent = button.getParent() instanceof View ? (View) button.getParent() : null;
        if (parent == null) {
            button.setX(x);
            button.setY(y);
            return;
        }
        float maxX = Math.max(0, parent.getWidth() - button.getWidth());
        float maxY = Math.max(0, parent.getHeight() - button.getHeight());
        button.setX(Math.max(0, Math.min(x, maxX)));
        button.setY(Math.max(0, Math.min(y, maxY)));
    }

    private static void savePanelPosition(TextView button, Activity activity) {
        try {
            AgentStore.setPanelPosition(activity, activity.getPackageName(), button.getX(), button.getY());
            log("saved panel position: package=" + activity.getPackageName()
                    + " x=" + Math.round(button.getX()) + " y=" + Math.round(button.getY()));
        } catch (Throwable t) {
            log("save panel position failed: " + t);
        }
    }

    private static void removePanelButton(Activity activity) {
        try {
            View button = PANEL_BUTTONS.remove(activity);
            if (button == null) {
                return;
            }
            ViewGroup parent = button.getParent() instanceof ViewGroup ? (ViewGroup) button.getParent() : null;
            if (parent != null) {
                parent.removeView(button);
            }
        } catch (Throwable t) {
            log("remove panel button failed: " + t);
        }
    }

    private static boolean isExportEnabled() {
        return readExportSettings().panelEnabled;
    }

    private static ExportSettings readExportSettings() {
        if (appContext == null) {
            return new ExportSettings(true, true, true, false,
                    AgentStore.PANEL_COLOR_BLUE, 92, 40);
        }
        try {
            Bundle result = appContext.getContentResolver().call(
                    AgentStore.EXPORT_URI,
                    ExportProvider.METHOD_GET_SETTINGS,
                    null,
                    null);
            if (result == null) {
                return new ExportSettings(true, true, true, false,
                        AgentStore.PANEL_COLOR_BLUE, 92, 40);
            }
            return new ExportSettings(
                    result.getBoolean(ExportProvider.EXTRA_PANEL_ENABLED, true),
                    true,
                    result.getBoolean(ExportProvider.EXTRA_BOOKMARK_IMPORT_ENABLED, true),
                    result.getBoolean(ExportProvider.EXTRA_DOMAIN_GROUP_ENABLED, false),
                    result.getString(ExportProvider.EXTRA_PANEL_COLOR, AgentStore.PANEL_COLOR_BLUE),
                    result.getInt(ExportProvider.EXTRA_PANEL_ALPHA, 92),
                    result.getInt(ExportProvider.EXTRA_PANEL_SIZE, 40));
        } catch (Throwable t) {
            log("read export settings failed, default enabled: " + t);
            return new ExportSettings(true, true, true, false,
                    AgentStore.PANEL_COLOR_BLUE, 92, 40);
        }
    }

    private static final class ExportSettings {
        final boolean panelEnabled;
        final boolean tabExportEnabled;
        final boolean bookmarkImportEnabled;
        final boolean domainGroupEnabled;
        final String panelColor;
        final int panelAlpha;
        final int panelSize;

        ExportSettings(boolean panelEnabled, boolean tabExportEnabled, boolean bookmarkImportEnabled,
                       boolean domainGroupEnabled, String panelColor, int panelAlpha, int panelSize) {
            this.panelEnabled = panelEnabled;
            this.tabExportEnabled = true;
            this.bookmarkImportEnabled = bookmarkImportEnabled;
            this.domainGroupEnabled = domainGroupEnabled;
            this.panelColor = panelColor == null ? AgentStore.PANEL_COLOR_BLUE : panelColor;
            this.panelAlpha = Math.max(20, Math.min(100, panelAlpha));
            this.panelSize = Math.max(28, Math.min(56, panelSize));
        }
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void applyPanelButtonStyle(TextView button, int state) {
        if (button == null) {
            return;
        }
        Context context = button.getContext();
        ExportSettings settings = readExportSettings();
        int[] palette = panelPalette(settings);
        int fill;
        int stroke;
        switch (state) {
            case PANEL_STATE_READING:
                fill = Color.rgb(71, 85, 105);
                stroke = Color.rgb(100, 116, 139);
                break;
            case PANEL_STATE_CONFIRM:
                fill = palette[0];
                stroke = palette[1];
                break;
            case PANEL_STATE_SAVING:
                fill = palette[2];
                stroke = palette[1];
                break;
            case PANEL_STATE_DONE:
                fill = Color.rgb(5, 150, 105);
                stroke = Color.rgb(110, 231, 183);
                break;
            case PANEL_STATE_FAILED:
                fill = Color.rgb(220, 38, 38);
                stroke = Color.rgb(252, 165, 165);
                break;
            case PANEL_STATE_DEFAULT:
            default:
                fill = palette[0];
                stroke = palette[1];
                break;
        }
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(applyAlpha(fill, settings.panelAlpha));
        background.setStroke(dp(context, 2), stroke);
        button.setBackground(background);
        button.setText("");
        button.setTextSize(1f);
        button.setTextColor(Color.TRANSPARENT);
        button.setLineSpacing(0f, 0.88f);
    }

    private static int[] panelPalette(ExportSettings settings) {
        String color = settings == null ? AgentStore.PANEL_COLOR_BLUE : settings.panelColor;
        if (AgentStore.PANEL_COLOR_WHITE.equals(color)) {
            return new int[]{Color.WHITE, Color.rgb(203, 213, 225), Color.rgb(241, 245, 249), Color.rgb(15, 23, 42)};
        }
        if (AgentStore.PANEL_COLOR_TRANSPARENT.equals(color)) {
            return new int[]{Color.WHITE, Color.rgb(148, 163, 184), Color.rgb(226, 232, 240), Color.rgb(15, 23, 42)};
        }
        if (AgentStore.PANEL_COLOR_GREEN.equals(color)) {
            return new int[]{Color.rgb(5, 150, 105), Color.rgb(110, 231, 183), Color.rgb(4, 120, 87), Color.WHITE};
        }
        if (AgentStore.PANEL_COLOR_PURPLE.equals(color)) {
            return new int[]{Color.rgb(124, 58, 237), Color.rgb(196, 181, 253), Color.rgb(91, 33, 182), Color.WHITE};
        }
        if (AgentStore.PANEL_COLOR_DARK.equals(color)) {
            return new int[]{Color.rgb(30, 41, 59), Color.rgb(148, 163, 184), Color.rgb(15, 23, 42), Color.WHITE};
        }
        if (AgentStore.PANEL_COLOR_ROSE.equals(color)) {
            return new int[]{Color.rgb(225, 29, 72), Color.rgb(253, 164, 175), Color.rgb(190, 18, 60), Color.WHITE};
        }
        if (AgentStore.PANEL_COLOR_ORANGE.equals(color)) {
            return new int[]{Color.rgb(234, 88, 12), Color.rgb(253, 186, 116), Color.rgb(194, 65, 12), Color.WHITE};
        }
        if (AgentStore.PANEL_COLOR_CYAN.equals(color)) {
            return new int[]{Color.rgb(8, 145, 178), Color.rgb(103, 232, 249), Color.rgb(14, 116, 144), Color.WHITE};
        }
        return new int[]{Color.rgb(37, 99, 235), Color.rgb(191, 219, 254), Color.rgb(30, 64, 175), Color.WHITE};
    }

    private static int applyAlpha(int color, int alphaPercent) {
        int alpha = Math.max(20, Math.min(100, alphaPercent)) * 255 / 100;
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
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
                    if (param.method instanceof Method) {
                        tabListMethod = (Method) param.method;
                        tabListMethod.setAccessible(true);
                    }
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
                    if (param.method instanceof Method) {
                        sessionBundleMethod = (Method) param.method;
                        sessionBundleMethod.setAccessible(true);
                    }
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
                    if (param.method instanceof Method) {
                        Method method = (Method) param.method;
                        method.setAccessible(true);
                        if ("K".equals(method.getName())) {
                            openUrlMethod = method;
                        } else if ("V".equals(method.getName())) {
                            switchTabMethod = method;
                        } else if ("U".equals(method.getName())) {
                            sessionBundleMethod = method;
                        }
                    }
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

    private static void startDynamicTabManagerScan(final ClassLoader classLoader) {
        if (dynamicScanStarted || appContext == null) {
            return;
        }
        dynamicScanStarted = true;
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                int scanned = 0;
                int hooked = 0;
                DexFile dex = null;
                try {
                    dex = new DexFile(appContext.getPackageCodePath());
                    Enumeration<String> entries = dex.entries();
                    while (entries.hasMoreElements() && hooked < 3) {
                        String name = entries.nextElement();
                        if (!shouldScanViaClass(name)) {
                            continue;
                        }
                        scanned++;
                        Class<?> clazz;
                        try {
                            clazz = Class.forName(name, false, classLoader);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        TabManagerCandidate candidate = findTabManagerCandidate(clazz);
                        if (candidate == null) {
                            continue;
                        }
                        if (hookDynamicTabManager(candidate)) {
                            hooked++;
                        }
                    }
                    log("dynamic tab manager scan finished: scanned=" + scanned + " hooked=" + hooked);
                } catch (Throwable t) {
                    log("dynamic tab manager scan failed: " + t);
                } finally {
                    if (dex != null) {
                        try {
                            dex.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        });
    }

    private static boolean shouldScanViaClass(String name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        return !name.startsWith("android.")
                && !name.startsWith("androidx.")
                && !name.startsWith("kotlin.")
                && !name.startsWith("kotlinx.")
                && !name.startsWith("okhttp3.")
                && !name.startsWith("okio.")
                && !name.startsWith("org.")
                && !name.startsWith("com.google.")
                && !name.startsWith("com.viatabs.");
    }

    private static TabManagerCandidate findTabManagerCandidate(Class<?> clazz) {
        try {
            if (clazz == null || clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
                return null;
            }
            Method list = null;
            Method opener = null;
            Method switcher = null;
            Method bundle = null;
            Method[] methods = clazz.getDeclaredMethods();
            for (Method method : methods) {
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                Class<?> returns = method.getReturnType();
                if (params.length == 0 && List.class.isAssignableFrom(returns)) {
                    list = method;
                } else if (params.length == 0 && Bundle.class.isAssignableFrom(returns)) {
                    bundle = method;
                } else if (params.length == 2 && params[0] == String.class && params[1] == int.class) {
                    opener = method;
                } else if (params.length == 1 && params[0] == int.class) {
                    switcher = method;
                }
            }
            if (list != null && (opener != null || bundle != null || switcher != null)) {
                list.setAccessible(true);
                if (opener != null) {
                    opener.setAccessible(true);
                }
                if (switcher != null) {
                    switcher.setAccessible(true);
                }
                if (bundle != null) {
                    bundle.setAccessible(true);
                }
                return new TabManagerCandidate(clazz, list, opener, switcher, bundle);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean hookDynamicTabManager(final TabManagerCandidate candidate) {
        try {
            XposedBridge.hookAllConstructors(candidate.clazz, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    rememberTabManager(candidate, param.thisObject, "dynamic.constructor");
                }
            });
            XposedBridge.hookMethod(candidate.listMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    rememberTabManager(candidate, param.thisObject, "dynamic." + candidate.listMethod.getName());
                    Object result = param.getResult();
                    if (result instanceof List) {
                        writeTabsSnapshot("tabManager.dynamic." + candidate.listMethod.getName(), (List<?>) result, null);
                    }
                }
            });
            if (candidate.bundleMethod != null) {
                XposedBridge.hookMethod(candidate.bundleMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        rememberTabManager(candidate, param.thisObject, "dynamic." + candidate.bundleMethod.getName());
                        Object result = param.getResult();
                        if (result instanceof Bundle) {
                            writeBundleSnapshot("tabManager.dynamic." + candidate.bundleMethod.getName(), (Bundle) result);
                        }
                    }
                });
            }
            if (candidate.openMethod != null) {
                XposedBridge.hookMethod(candidate.openMethod, dynamicMutationHook(candidate, candidate.openMethod));
            }
            if (candidate.switchMethod != null) {
                XposedBridge.hookMethod(candidate.switchMethod, dynamicMutationHook(candidate, candidate.switchMethod));
            }
            log("hooked dynamic tab manager: " + candidate.clazz.getName()
                    + " list=" + candidate.listMethod.getName()
                    + " open=" + (candidate.openMethod == null ? "null" : candidate.openMethod.getName())
                    + " switch=" + (candidate.switchMethod == null ? "null" : candidate.switchMethod.getName())
                    + " bundle=" + (candidate.bundleMethod == null ? "null" : candidate.bundleMethod.getName()));
            return true;
        } catch (Throwable t) {
            log("hook dynamic tab manager failed: " + candidate.clazz.getName() + " error=" + t);
            return false;
        }
    }

    private static XC_MethodHook dynamicMutationHook(final TabManagerCandidate candidate, final Method method) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                rememberTabManager(candidate, param.thisObject, "dynamic." + method.getName());
                snapshotFromLastTabManager("dynamic." + method.getName());
            }
        };
    }

    private static void rememberTabManager(TabManagerCandidate candidate, Object manager, String reason) {
        if (candidate == null || manager == null) {
            return;
        }
        lastTabManager = manager;
        tabListMethod = candidate.listMethod;
        if (candidate.openMethod != null) {
            openUrlMethod = candidate.openMethod;
        }
        if (candidate.switchMethod != null) {
            switchTabMethod = candidate.switchMethod;
        }
        if (candidate.bundleMethod != null) {
            sessionBundleMethod = candidate.bundleMethod;
        }
        log("captured tab manager: " + candidate.clazz.getName() + " reason=" + reason);
    }

    private static Object callTabListMethod(Object manager) throws Exception {
        Method method = tabListMethod;
        if (method != null && method.getDeclaringClass().isInstance(manager)) {
            method.setAccessible(true);
            return method.invoke(manager);
        }
        Method[] methods = manager.getClass().getDeclaredMethods();
        for (Method candidate : methods) {
            if (Modifier.isStatic(candidate.getModifiers())
                    || candidate.getParameterTypes().length != 0
                    || !List.class.isAssignableFrom(candidate.getReturnType())) {
                continue;
            }
            candidate.setAccessible(true);
            Object result = candidate.invoke(manager);
            if (looksLikeTabList(result)) {
                tabListMethod = candidate;
                return result;
            }
        }
        return null;
    }

    private static void callOpenUrlMethod(Object manager, String url) throws Exception {
        Method method = openUrlMethod;
        if (method != null && method.getDeclaringClass().isInstance(manager)) {
            method.setAccessible(true);
            method.invoke(manager, url, 0);
            return;
        }
        Method[] methods = manager.getClass().getDeclaredMethods();
        for (Method candidate : methods) {
            Class<?>[] params = candidate.getParameterTypes();
            if (Modifier.isStatic(candidate.getModifiers())
                    || params.length != 2
                    || params[0] != String.class
                    || params[1] != int.class) {
                continue;
            }
            candidate.setAccessible(true);
            openUrlMethod = candidate;
            candidate.invoke(manager, url, 0);
            return;
        }
        throw new IllegalStateException("open tab method not ready");
    }

    private static boolean looksLikeTabList(Object value) {
        if (!(value instanceof List)) {
            return false;
        }
        List<?> list = (List<?>) value;
        if (list.isEmpty()) {
            return true;
        }
        int checked = Math.min(list.size(), 5);
        for (int i = 0; i < checked; i++) {
            Object item = list.get(i);
            if (item == null) {
                continue;
            }
            if (isMeaningfulUrl(String.valueOf(item)) || tabRecordFromValue(item, i) != null) {
                return true;
            }
        }
        return false;
    }

    private static void installWebViewFallback(ClassLoader classLoader) {
        try {
            XposedBridge.hookAllMethods(WebView.class, "getUrl", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(IN_WEBVIEW_CAPTURE.get())) {
                        return;
                    }
                    Object result = param.getResult();
                    if (result instanceof String) {
                        WebView webView = param.thisObject instanceof WebView ? (WebView) param.thisObject : null;
                        String title = safeWebViewTitle(webView);
                        rememberWebView((String) result, title);
                        writeSingleUrlSnapshot("androidWebView.getUrl", (String) result, title);
                    }
                }
            });
            XposedBridge.hookAllMethods(WebView.class, "getTitle", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(IN_WEBVIEW_CAPTURE.get())) {
                        return;
                    }
                    Object result = param.getResult();
                    if (result instanceof String && param.thisObject instanceof WebView) {
                        WebView webView = (WebView) param.thisObject;
                        rememberWebView(safeWebViewUrl(webView), (String) result);
                    }
                }
            });
            XposedBridge.hookAllMethods(WebView.class, "loadUrl", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(IN_WEBVIEW_CAPTURE.get())) {
                        return;
                    }
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof String) {
                        WebView webView = param.thisObject instanceof WebView ? (WebView) param.thisObject : null;
                        String url = (String) param.args[0];
                        String title = safeWebViewTitle(webView);
                        rememberWebView(url, title);
                        writeSingleUrlSnapshot("androidWebView.loadUrl", url, title);
                    }
                }
            });
            log("hooked android.webkit.WebView fallback");
        } catch (Throwable t) {
            log("failed to hook android.webkit.WebView fallback: " + t);
        }
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
                        writeSingleUrlSnapshot("viaWebView.getUrl", (String) result, null);
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
                root.put("tabs", toTabRecordArray(tabRecordsFromList((List<?>) list)));
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
            List<TabRecord> records = tabRecordsFromList(urls);
            root.put("tabs", toTabRecordArray(records));
            boolean wrote = writeJson(root);
            if (wrote && isTabManagerSource(source)) {
                persistAgentSession(source, tabRecordUrls(records));
            }
        } catch (Throwable t) {
            log("write tabs snapshot failed: " + t);
        }
    }

    private static void writeSingleUrlSnapshot(String source, String url) {
        writeSingleUrlSnapshot(source, url, null);
    }

    private static void writeSingleUrlSnapshot(String source, String url, String title) {
        try {
            if (!isMeaningfulUrl(url)) {
                return;
            }
            JSONObject root = baseSnapshot(source);
            JSONArray tabs = new JSONArray();
            JSONObject tab = new JSONObject();
            tab.put("url", url);
            if (title != null && title.trim().length() > 0) {
                tab.put("title", title.trim());
            }
            tabs.put(tab);
            root.put("tabs", tabs);
            writeJson(root);
        } catch (Throwable t) {
            log("write single url snapshot failed: " + t);
        }
    }

    private static void writeTabRecordsSnapshot(String source, List<TabRecord> records) {
        try {
            if (records == null || records.isEmpty()) {
                return;
            }
            JSONObject root = baseSnapshot(source);
            root.put("tabs", toTabRecordArray(records));
            writeJson(root);
        } catch (Throwable t) {
            log("write tab records snapshot failed: " + t);
        }
    }

    private static List<TabRecord> collectWebViewTabRecords(Activity activity) {
        ArrayList<TabRecord> records = new ArrayList<TabRecord>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        try {
            View root = activity == null ? null : activity.findViewById(android.R.id.content);
            collectWebViewsFromView(root, records, seen);
            synchronized (WEBVIEW_TITLES) {
                for (Map.Entry<String, String> entry : WEBVIEW_TITLES.entrySet()) {
                    String url = entry.getKey();
                    if (!isMeaningfulUrl(url) || !seen.add(url)) {
                        continue;
                    }
                    String title = entry.getValue();
                    if (title == null || title.trim().length() == 0) {
                        title = titleFromUrl(url);
                    }
                    records.add(new TabRecord(records.size(), title, url));
                }
            }
        } catch (Throwable t) {
            log("collect WebView tabs failed: " + t);
        }
        log("collected visible WebView tabs: " + records.size());
        return records;
    }

    private static void collectWebViewsFromView(View view, List<TabRecord> records, Set<String> seen) {
        if (view == null) {
            return;
        }
        if (view instanceof WebView) {
            WebView webView = (WebView) view;
            String url = safeWebViewUrl(webView);
            String title = safeWebViewTitle(webView);
            rememberWebView(url, title);
            if (isMeaningfulUrl(url) && seen.add(url)) {
                if (title == null || title.trim().length() == 0) {
                    title = titleFromUrl(url);
                }
                records.add(new TabRecord(records.size(), title, url));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectWebViewsFromView(group.getChildAt(i), records, seen);
            }
        }
    }

    private static String safeWebViewUrl(WebView webView) {
        try {
            if (webView == null) {
                return null;
            }
            IN_WEBVIEW_CAPTURE.set(Boolean.TRUE);
            return webView.getUrl();
        } catch (Throwable ignored) {
            return null;
        } finally {
            IN_WEBVIEW_CAPTURE.remove();
        }
    }

    private static String safeWebViewTitle(WebView webView) {
        try {
            if (webView == null) {
                return null;
            }
            IN_WEBVIEW_CAPTURE.set(Boolean.TRUE);
            return webView.getTitle();
        } catch (Throwable ignored) {
            return null;
        } finally {
            IN_WEBVIEW_CAPTURE.remove();
        }
    }

    private static void rememberWebView(String url, String title) {
        if (!isMeaningfulUrl(url)) {
            return;
        }
        String safeTitle = title == null || title.trim().length() == 0 ? titleFromUrl(url) : title.trim();
        synchronized (WEBVIEW_TITLES) {
            WEBVIEW_TITLES.put(url.trim(), safeTitle);
        }
    }

    private static boolean isMeaningfulUrl(String url) {
        if (url == null) {
            return false;
        }
        String safe = url.trim();
        if (safe.length() == 0 || "null".equalsIgnoreCase(safe)) {
            return false;
        }
        return safe.startsWith("http://") || safe.startsWith("https://")
                || safe.startsWith("file://") || safe.startsWith("content://");
    }

    private static void snapshotFromLastTabManager(String reason) {
        Object manager = lastTabManager;
        if (manager == null || Boolean.TRUE.equals(IN_MANAGER_SNAPSHOT.get())) {
            return;
        }
        try {
            IN_MANAGER_SNAPSHOT.set(Boolean.TRUE);
            Object urls = callTabListMethod(manager);
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

    private static void previewCurrentTabsToBookmarks(final TextView button, final Activity activity) {
        setPanelButtonState(button, "读取中", false);
        final ExportSettings settings = readExportSettings();
        if (!settings.tabExportEnabled && !settings.bookmarkImportEnabled) {
            log("panel preview skipped: tabExport=false bookmarkImport=false");
            toast(activity, "请先在模块中开启标签导出或标签导入到书签");
            resetPanelButtonLater(button, 0L);
            return;
        }
        final Object manager = lastTabManager;
        final Handler handler = mainHandler;
        final List<TabRecord> visibleWebViews = collectWebViewTabRecords(activity);
        if (visibleWebViews.size() > 0) {
            writeTabRecordsSnapshot("webView.activity.preview", visibleWebViews);
        }
        if (manager == null || handler == null) {
            if (visibleWebViews.size() > 0) {
                showSavePreviewDialog(button, activity, visibleWebViews, "webview.activity", settings);
                return;
            }
            loadCachedPreview(button, activity, "managerNotReady", settings);
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    List<TabRecord> tabs = snapshotTabRecords(manager);
                    if (tabs.size() == 0) {
                        loadCachedPreview(button, activity, "emptyLiveSnapshot", settings);
                        return;
                    }
                    showSavePreviewDialog(button, activity, tabs, "live", settings);
                } catch (Throwable t) {
                    log("preview live snapshot failed, using cache: " + t);
                    loadCachedPreview(button, activity, "liveSnapshotFailed", settings);
                }
            }
        });
    }

    private static void loadCachedPreview(final TextView button, final Activity activity,
                                          final String reason, final ExportSettings settings) {
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<TabRecord> tabs = readFallbackTabRecords();
                    if (tabs.size() == 0) {
                        log("panel preview cache empty: reason=" + reason);
                        toast(activity, "标签管理器未就绪，且没有读取到缓存标签");
                        resetPanelButtonLater(button, 0L);
                        return;
                    }
                    log("panel preview cache snapshot: reason=" + reason + " tabs=" + tabs.size());
                    Handler handler = mainHandler;
                    if (handler != null) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                showSavePreviewDialog(button, activity, tabs, "cache." + reason, settings);
                            }
                        });
                    }
                } catch (Throwable t) {
                    log("panel preview cache failed: " + t);
                    toast(activity, "读取标签失败：" + shortError(t));
                    setPanelButtonState(button, "失败", false);
                    resetPanelButtonLater(button, 2000L);
                }
            }
        });
    }

    private static void showSavePreviewDialog(final TextView button, final Activity activity,
                                              final List<TabRecord> tabs, final String source,
                                              final ExportSettings settings) {
        if (activity == null || activity.isFinishing()) {
            resetPanelButtonLater(button, 0L);
            return;
        }
        final BookmarkBatch batch = createBookmarkBatch(tabs, settings.domainGroupEnabled);
        log("panel preview ready: source=" + source + " captured=" + tabs.size()
                + " bookmarkable=" + batch.bookmarkCount + " folder=" + batch.folderName);
        String message = "当前捕获标签: " + tabs.size()
                + "\n可保存标签: " + batch.bookmarkCount
                + "\n文件夹: " + batch.folderName
                + "\nJSON 保存: 默认"
                + "\n导入书签: " + enabledText(settings.bookmarkImportEnabled)
                + "\n按域名整理: " + enabledText(settings.domainGroupEnabled)
                + "\n导出目录: Download/ViaTabsAgent";
        setPanelButtonState(button, "确认", false);
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("保存当前标签页？")
                    .setMessage(message)
                    .setPositiveButton("确认保存", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            log("panel preview confirmed: folder=" + batch.folderName
                                    + " bookmarkable=" + batch.bookmarkCount);
                            setPanelButtonState(button, "保存中", false);
                            saveAndExportTabs(batch, tabs, activity, source, button);
                        }
                    })
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            log("panel preview canceled");
                            resetPanelButtonLater(button, 0L);
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            log("panel preview canceled");
                            resetPanelButtonLater(button, 0L);
                        }
                    })
                    .show();
        } catch (Throwable t) {
            log("show save preview dialog failed: " + t);
            toast(activity, "显示确认窗口失败：" + shortError(t));
            resetPanelButtonLater(button, 0L);
        }
    }

    private static String enabledText(boolean enabled) {
        return enabled ? "开启" : "关闭";
    }

    private static void setPanelButtonState(final TextView button, final String text, final boolean enabled) {
        Handler handler = mainHandler;
        if (button == null || handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                applyPanelButtonStyle(button, panelStateForText(text));
                button.setEnabled(enabled);
            }
        });
    }

    private static void resetPanelButtonLater(final TextView button, long delayMs) {
        Handler handler = mainHandler;
        if (button == null || handler == null) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyPanelButtonStyle(button, PANEL_STATE_DEFAULT);
                button.setEnabled(true);
            }
        }, delayMs);
    }

    private static int panelStateForText(String text) {
        if ("读取中".equals(text)) {
            return PANEL_STATE_READING;
        }
        if ("确认".equals(text)) {
            return PANEL_STATE_CONFIRM;
        }
        if ("保存中".equals(text)) {
            return PANEL_STATE_SAVING;
        }
        if ("完成".equals(text)) {
            return PANEL_STATE_DONE;
        }
        if ("失败".equals(text)) {
            return PANEL_STATE_FAILED;
        }
        return PANEL_STATE_DEFAULT;
    }

    private static void saveCurrentTabsToBookmarks(final String folderName, final Context uiContext) {
        log("saveCurrentTabsToBookmarks requested: folder=" + folderName);
        final Object manager = lastTabManager;
        final Handler handler = mainHandler;
        final List<TabRecord> visibleWebViews = uiContext instanceof Activity
                ? collectWebViewTabRecords((Activity) uiContext)
                : new ArrayList<TabRecord>();
        if (visibleWebViews.size() > 0) {
            writeTabRecordsSnapshot("webView.activity.save", visibleWebViews);
        }
        if (manager == null || handler == null) {
            if (visibleWebViews.size() > 0) {
                log("saveCurrentTabsToBookmarks using visible WebView snapshot: tabs=" + visibleWebViews.size());
                saveAndExportTabs(folderName, visibleWebViews, uiContext, "webview.activity");
                return;
            }
            log("saveCurrentTabsToBookmarks fallback: tab manager not ready and no visible WebView");
            exportCachedTabsToBookmarks(folderName, uiContext, "managerNotReady");
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<TabRecord> tabs = snapshotTabRecords(manager);
                    if (tabs.size() == 0) {
                        log("saveCurrentTabsToBookmarks fallback: live tab list empty");
                        exportCachedTabsToBookmarks(folderName, uiContext, "emptyLiveSnapshot");
                        return;
                    }
                    log("saveCurrentTabsToBookmarks snapshot: tabs=" + tabs.size());
                    saveAndExportTabs(folderName, tabs, uiContext, "live");
                } catch (Throwable t) {
                    log("saveCurrentTabsToBookmarks live snapshot failed, using cache: " + t);
                    exportCachedTabsToBookmarks(folderName, uiContext, "liveSnapshotFailed");
                }
            }
        });
    }

    private static void exportCachedTabsToBookmarks(final String folderName, final Context uiContext, final String reason) {
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<TabRecord> tabs = readFallbackTabRecords();
                    if (tabs.size() == 0) {
                        log("saveCurrentTabsToBookmarks cache empty: reason=" + reason);
                        toast(uiContext, "标签管理器未就绪，且没有读取到缓存标签");
                        return;
                    }
                    log("saveCurrentTabsToBookmarks cache snapshot: reason=" + reason + " tabs=" + tabs.size());
                    saveAndExportTabsNow(folderName, tabs, uiContext, "cache." + reason);
                } catch (Throwable t) {
                    log("saveCurrentTabsToBookmarks cache export failed: " + t);
                    toast(uiContext, "缓存导出失败：" + shortError(t));
                }
            }
        });
    }

    private static void saveAndExportTabs(final String folderName, final List<TabRecord> tabs,
                                          final Context uiContext, final String source) {
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                saveAndExportTabsNow(folderName, tabs, uiContext, source);
            }
        });
    }

    private static void importExportGroups(final Intent intent) {
        if (intent == null) {
            return;
        }
        final ArrayList<String> jsonNames = intent.getStringArrayListExtra(EXTRA_JSON_NAMES);
        final ArrayList<String> htmlNames = intent.getStringArrayListExtra(EXTRA_HTML_NAMES);
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                int inserted = 0;
                int skipped = 0;
                int groups = 0;
                String error = null;
                try {
                    int max = Math.max(jsonNames == null ? 0 : jsonNames.size(),
                            htmlNames == null ? 0 : htmlNames.size());
                    for (int i = 0; i < max; i++) {
                        String jsonName = jsonNames != null && i < jsonNames.size() ? jsonNames.get(i) : null;
                        String htmlName = htmlNames != null && i < htmlNames.size() ? htmlNames.get(i) : null;
                        List<TabRecord> tabs = readExportedTabs(jsonName, htmlName);
                        if (tabs.isEmpty()) {
                            log("import export group skipped: json=" + jsonName + " html=" + htmlName);
                            continue;
                        }
                        BookmarkBatch batch = createImportBatch(jsonName, htmlName, tabs);
                        BookmarkSaveResult result = saveTabsToViaBookmarks(batch, tabs);
                        inserted += result.inserted;
                        skipped += result.skipped;
                        groups++;
                        log("imported export group: folder=" + batch.folderName
                                + " tabs=" + tabs.size() + " inserted=" + result.inserted
                                + " skipped=" + result.skipped);
                    }
                } catch (Throwable t) {
                    error = shortError(t);
                    log("import export groups failed: " + t);
                }
                recordImportResult(groups, inserted, skipped, error);
                toast(appContext, error == null
                        ? "已导入 " + inserted + " 个书签到 " + (appContext == null ? "Via" : appContext.getPackageName())
                        : "导入失败：" + error);
            }
        });
    }

    private static List<TabRecord> readExportedTabs(String jsonName, String htmlName) {
        List<TabRecord> tabs = readExportedJsonTabs(jsonName);
        if (!tabs.isEmpty()) {
            return tabs;
        }
        return readExportedHtmlTabs(htmlName);
    }

    private static List<TabRecord> readExportedJsonTabs(String fileName) {
        ArrayList<TabRecord> tabs = new ArrayList<TabRecord>();
        if (fileName == null || fileName.length() == 0) {
            return tabs;
        }
        try {
            String payload = readExportFileFromModule(fileName);
            if (payload == null || payload.trim().length() == 0) {
                return tabs;
            }
            JSONArray array = new JSONObject(payload).optJSONArray("tabs");
            if (array == null) {
                return tabs;
            }
            LinkedHashSet<String> seen = new LinkedHashSet<String>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String url = item.optString("url", "").trim();
                if (!isBookmarkableUrl(url) || !seen.add(url)) {
                    continue;
                }
                String title = item.optString("title", "").trim();
                tabs.add(new TabRecord(tabs.size(), title, url));
            }
        } catch (Throwable t) {
            log("read exported json tabs failed: " + fileName + " " + t);
        }
        return tabs;
    }

    private static List<TabRecord> readExportedHtmlTabs(String fileName) {
        ArrayList<TabRecord> tabs = new ArrayList<TabRecord>();
        if (fileName == null || fileName.length() == 0) {
            return tabs;
        }
        try {
            String html = readExportFileFromModule(fileName);
            if (html == null || html.length() == 0) {
                return tabs;
            }
            LinkedHashSet<String> seen = new LinkedHashSet<String>();
            int offset = 0;
            while (offset >= 0 && offset < html.length()) {
                int hrefAt = html.indexOf("HREF=\"", offset);
                if (hrefAt < 0) {
                    hrefAt = html.indexOf("href=\"", offset);
                }
                if (hrefAt < 0) {
                    break;
                }
                int urlStart = hrefAt + 6;
                int urlEnd = html.indexOf('"', urlStart);
                int titleStart = html.indexOf('>', urlEnd);
                int titleEnd = html.indexOf("</A>", titleStart);
                if (titleEnd < 0) {
                    titleEnd = html.indexOf("</a>", titleStart);
                }
                offset = urlEnd > 0 ? urlEnd + 1 : hrefAt + 1;
                if (urlEnd <= urlStart || titleStart < 0 || titleEnd < 0) {
                    continue;
                }
                String url = unescapeBookmarkHtml(html.substring(urlStart, urlEnd)).trim();
                if (!isBookmarkableUrl(url) || !seen.add(url)) {
                    continue;
                }
                String title = unescapeBookmarkHtml(html.substring(titleStart + 1, titleEnd)).trim();
                tabs.add(new TabRecord(tabs.size(), title, url));
            }
        } catch (Throwable t) {
            log("read exported html tabs failed: " + fileName + " " + t);
        }
        return tabs;
    }

    private static String readExportFileFromModule(String fileName) {
        if (appContext == null || fileName == null || fileName.length() == 0) {
            return "";
        }
        try {
            Bundle extras = new Bundle();
            extras.putString(ExportProvider.EXTRA_FILE_NAME, fileName);
            Bundle result = appContext.getContentResolver().call(
                    AgentStore.EXPORT_URI,
                    ExportProvider.METHOD_READ_EXPORT_FILE,
                    null,
                    extras);
            return result == null ? "" : result.getString(ExportProvider.EXTRA_PAYLOAD, "");
        } catch (Throwable t) {
            log("read export file from module failed: " + fileName + " " + t);
            return "";
        }
    }

    private static BookmarkBatch createImportBatch(String jsonName, String htmlName, List<TabRecord> tabs) {
        long now = System.currentTimeMillis();
        String baseName = jsonName != null && jsonName.endsWith(".json")
                ? jsonName.substring(0, jsonName.length() - 5)
                : htmlName != null && htmlName.endsWith(".html")
                ? htmlName.substring(0, htmlName.length() - 5)
                : "via-import-" + now;
        String folderName = "导入-" + baseName;
        int bookmarkCount = countBookmarkableTabs(tabs);
        ExportSettings settings = readExportSettings();
        return new BookmarkBatch(folderName, baseName, now / 1000L, bookmarkCount,
                settings.domainGroupEnabled, groupTabsByDomain(tabs));
    }

    private static void recordImportResult(int groups, int inserted, int skipped, String error) {
        if (appContext == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            root.put("timestamp", System.currentTimeMillis());
            root.put("source", "localExport.import");
            root.put("targetPackage", appContext.getPackageName());
            root.put("folder", "本地导出数据导入");
            root.put("captured", groups);
            root.put("bookmarkable", inserted + skipped);
            root.put("inserted", inserted);
            root.put("skipped", skipped);
            root.put("domainGroup", readExportSettings().domainGroupEnabled);
            root.put("error", error == null ? JSONObject.NULL : error);
            Bundle extras = new Bundle();
            extras.putString(ExportProvider.EXTRA_RESULT, root.toString());
            appContext.getContentResolver().call(
                    AgentStore.EXPORT_URI,
                    ExportProvider.METHOD_SET_LAST_RESULT,
                    null,
                    extras);
        } catch (Throwable t) {
            log("record import result failed: " + t);
        }
    }

    private static void saveAndExportTabs(final BookmarkBatch batch, final List<TabRecord> tabs,
                                          final Context uiContext, final String source,
                                          final TextView button) {
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                saveAndExportTabsNow(batch, tabs, uiContext, source, button);
            }
        });
    }

    private static void saveAndExportTabsNow(String folderName, List<TabRecord> tabs,
                                             Context uiContext, String source) {
        ExportSettings settings = readExportSettings();
        if (!settings.tabExportEnabled && !settings.bookmarkImportEnabled) {
            log("saved tabs skipped: tabExport=false bookmarkImport=false");
            toast(uiContext, "请先在模块中开启标签导出或标签导入到书签");
            return;
        }
        BookmarkBatch batch = createBookmarkBatch(tabs, settings.domainGroupEnabled);
        saveAndExportTabsNow(batch, tabs, uiContext, source, null);
    }

    private static void saveAndExportTabsNow(BookmarkBatch batch, List<TabRecord> tabs,
                                             Context uiContext, String source, TextView button) {
        ExportSettings settings = readExportSettings();
        if (!settings.tabExportEnabled && !settings.bookmarkImportEnabled) {
            log("saved tabs skipped: tabExport=false bookmarkImport=false");
            toast(uiContext, "请先在模块中开启标签导出或标签导入到书签");
            recordLastSaveResult(batch, source, tabs, new BookmarkSaveResult(), settings, null,
                    "tabExport=false bookmarkImport=false");
            setPanelButtonState(button, "失败", false);
            resetPanelButtonLater(button, 2000L);
            return;
        }

        BookmarkSaveResult result = new BookmarkSaveResult();
        if (settings.bookmarkImportEnabled) {
            try {
                result = saveTabsToViaBookmarks(batch, tabs);
            } catch (Throwable t) {
                result.error = shortError(t);
                log("save tabs to Via bookmarks failed, export will continue: " + t);
            }
        }

        String exportPath = settings.tabExportEnabled
                ? writeBookmarkSaveSnapshot(batch, tabs, result, source)
                : null;
        result.jsonExportPath = exportPath;
        String resultError = result.error;
        if (settings.tabExportEnabled && (exportPath == null || exportPath.length() == 0)) {
            resultError = resultError == null ? "export failed" : resultError + "; export failed";
        }

        log("saved tabs export: source=" + source + " folder=" + batch.folderName + " tabs=" + tabs.size()
                + " tabExport=" + settings.tabExportEnabled
                + " bookmarkImport=" + settings.bookmarkImportEnabled
                + " domainGroup=" + settings.domainGroupEnabled
                + " inserted=" + result.inserted + " skipped=" + result.skipped + " export=" + exportPath
                + (resultError == null ? "" : " error=" + resultError));
        recordLastSaveResult(batch, source, tabs, result, settings, exportPath, resultError);

        if (settings.bookmarkImportEnabled && !settings.tabExportEnabled) {
            toast(uiContext, "已导入 " + result.inserted + " 个书签到 Via");
            setPanelButtonState(button, resultError == null ? "完成" : "失败", false);
        } else if (!settings.bookmarkImportEnabled && settings.tabExportEnabled) {
            if (exportPath == null || exportPath.length() == 0) {
                toast(uiContext, "导出失败：未写入 Download/ViaTabsAgent");
                setPanelButtonState(button, "失败", false);
            } else if (source != null && source.startsWith("cache.")) {
                toast(uiContext, "已从缓存导出 " + tabs.size() + " 个标签到 Download/ViaTabsAgent");
                setPanelButtonState(button, "完成", false);
            } else {
                toast(uiContext, "已导出 " + tabs.size() + " 个标签到 Download/ViaTabsAgent");
                setPanelButtonState(button, "完成", false);
            }
        } else if (exportPath == null || exportPath.length() == 0) {
            toast(uiContext, "导出失败：未写入 Download/ViaTabsAgent");
            setPanelButtonState(button, "失败", false);
        } else if (source != null && source.startsWith("cache.")) {
            toast(uiContext, "已导入 " + result.inserted + " 个书签，并从缓存导出到 Download/ViaTabsAgent");
            setPanelButtonState(button, resultError == null ? "完成" : "失败", false);
        } else {
            toast(uiContext, "已导入 " + result.inserted + " 个书签，已导出 " + tabs.size() + " 个标签");
            setPanelButtonState(button, resultError == null ? "完成" : "失败", false);
        }
        resetPanelButtonLater(button, 2000L);
    }

    private static void recordLastSaveResult(BookmarkBatch batch, String source, List<TabRecord> tabs,
                                             BookmarkSaveResult result, ExportSettings settings,
                                             String exportPath, String error) {
        if (appContext == null || batch == null || result == null || settings == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            root.put("timestamp", System.currentTimeMillis());
            root.put("source", source == null ? JSONObject.NULL : source);
            root.put("folder", batch.folderName);
            root.put("captured", tabs == null ? 0 : tabs.size());
            root.put("bookmarkable", batch.bookmarkCount);
            root.put("inserted", result.inserted);
            root.put("skipped", result.skipped);
            root.put("domainGroup", settings.domainGroupEnabled);
            root.put("tabExport", settings.tabExportEnabled);
            root.put("bookmarkImport", settings.bookmarkImportEnabled);
            root.put("htmlExportPath", result.htmlExportPath == null ? JSONObject.NULL : result.htmlExportPath);
            root.put("jsonExportPath", exportPath == null ? JSONObject.NULL : exportPath);
            root.put("error", error == null ? JSONObject.NULL : error);
            Bundle extras = new Bundle();
            extras.putString(ExportProvider.EXTRA_RESULT, root.toString());
            appContext.getContentResolver().call(
                    AgentStore.EXPORT_URI,
                    ExportProvider.METHOD_SET_LAST_RESULT,
                    null,
                    extras);
        } catch (Throwable t) {
            log("record last save result failed: " + t);
        }
    }

    private static List<TabRecord> snapshotTabRecords(Object manager) {
        ArrayList<TabRecord> records = new ArrayList<TabRecord>();
        Object urls = null;
        try {
            urls = callTabListMethod(manager);
        } catch (Throwable t) {
            log("snapshot tab records failed to read list: " + t);
        }
        List<?> rawList = urls instanceof List ? (List<?>) urls : new ArrayList<Object>();
        Object rawTabs = null;
        try {
            rawTabs = XposedHelpers.getObjectField(manager, "c");
        } catch (Throwable ignored) {
        }
        List<?> tabs = rawTabs instanceof List ? (List<?>) rawTabs : null;
        for (int i = 0; i < rawList.size(); i++) {
            Object value = rawList.get(i);
            TabRecord record = tabRecordFromValue(value, i);
            if (record == null && tabs != null && i < tabs.size()) {
                record = tabRecordFromValue(tabs.get(i), i);
            } else if (record != null && (record.title == null || record.title.trim().length() == 0)
                    && tabs != null && i < tabs.size()) {
                String title = titleFromViaTab(tabs.get(i));
                if (title != null && title.trim().length() > 0) {
                    record = new TabRecord(record.index, title.trim(), record.url);
                }
            }
            if (record != null && isMeaningfulUrl(record.url)) {
                records.add(new TabRecord(records.size(), record.title, record.url));
            }
        }
        log("snapshot tab records parsed: raw=" + rawList.size() + " tabs=" + records.size());
        return records;
    }

    private static List<TabRecord> tabRecordsFromList(List<?> values) {
        ArrayList<TabRecord> records = new ArrayList<TabRecord>();
        if (values == null) {
            return records;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < values.size(); i++) {
            TabRecord record = tabRecordFromValue(values.get(i), i);
            if (record == null || !isMeaningfulUrl(record.url) || !seen.add(record.url)) {
                continue;
            }
            records.add(new TabRecord(records.size(), record.title, record.url));
        }
        return records;
    }

    private static List<String> tabRecordUrls(List<TabRecord> records) {
        ArrayList<String> urls = new ArrayList<String>();
        if (records == null) {
            return urls;
        }
        for (TabRecord record : records) {
            if (record != null && isMeaningfulUrl(record.url)) {
                urls.add(record.url);
            }
        }
        return urls;
    }

    private static TabRecord tabRecordFromValue(Object value, int index) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            String url = ((String) value).trim();
            return isMeaningfulUrl(url) ? new TabRecord(index, titleFromUrl(url), url) : null;
        }
        if (value instanceof Bundle) {
            return tabRecordFromBundle((Bundle) value, index);
        }
        String direct = String.valueOf(value).trim();
        if (isMeaningfulUrl(direct)) {
            return new TabRecord(index, titleFromUrl(direct), direct);
        }
        String url = firstMeaningfulStringFromObject(value, true);
        if (!isMeaningfulUrl(url)) {
            return null;
        }
        String title = titleFromViaTab(value);
        if (title == null || title.trim().length() == 0 || isMeaningfulUrl(title)) {
            title = firstMeaningfulStringFromObject(value, false);
        }
        if (title == null || title.trim().length() == 0 || isMeaningfulUrl(title)) {
            title = titleFromUrl(url);
        }
        return new TabRecord(index, title.trim(), url.trim());
    }

    private static TabRecord tabRecordFromBundle(Bundle bundle, int index) {
        String url = firstString(bundle, "url", "URL", "u", "link", "href");
        if (!isMeaningfulUrl(url)) {
            for (String key : bundle.keySet()) {
                Object value = bundle.get(key);
                if (value instanceof String && isMeaningfulUrl((String) value)) {
                    url = (String) value;
                    break;
                }
            }
        }
        if (!isMeaningfulUrl(url)) {
            return null;
        }
        String title = firstString(bundle, "title", "TITLE", "name", "label");
        if (title == null || title.trim().length() == 0 || isMeaningfulUrl(title)) {
            title = titleFromUrl(url);
        }
        return new TabRecord(index, title.trim(), url.trim());
    }

    private static String firstString(Bundle bundle, String... keys) {
        for (String key : keys) {
            try {
                String value = bundle.getString(key);
                if (value != null && value.trim().length() > 0) {
                    return value.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String firstMeaningfulStringFromObject(Object value, boolean wantUrl) {
        String fromFields = firstMeaningfulStringFromFields(value, wantUrl);
        if (fromFields != null) {
            return fromFields;
        }
        return firstMeaningfulStringFromMethods(value, wantUrl);
    }

    private static String firstMeaningfulStringFromFields(Object value, boolean wantUrl) {
        Class<?> clazz = value.getClass();
        while (clazz != null && clazz != Object.class) {
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(value);
                    String found = stringFromPossibleTabValue(fieldValue, wantUrl);
                    if (found != null) {
                        return found;
                    }
                } catch (Throwable ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static String firstMeaningfulStringFromMethods(Object value, boolean wantUrl) {
        Method[] methods = value.getClass().getDeclaredMethods();
        for (Method method : methods) {
            if (Modifier.isStatic(method.getModifiers())
                    || method.getParameterTypes().length != 0
                    || method.getReturnType() == Void.TYPE
                    || (!String.class.isAssignableFrom(method.getReturnType())
                    && !Bundle.class.isAssignableFrom(method.getReturnType())
                    && !WebView.class.isAssignableFrom(method.getReturnType()))) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object result = method.invoke(value);
                String found = stringFromPossibleTabValue(result, wantUrl);
                if (found != null) {
                    return found;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String stringFromPossibleTabValue(Object value, boolean wantUrl) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            String safe = ((String) value).trim();
            boolean url = isMeaningfulUrl(safe);
            if ((wantUrl && url) || (!wantUrl && !url && safe.length() > 0 && safe.length() < 200)) {
                return safe;
            }
        } else if (value instanceof Bundle) {
            TabRecord record = tabRecordFromBundle((Bundle) value, 0);
            if (record != null) {
                return wantUrl ? record.url : record.title;
            }
        } else if (value instanceof WebView) {
            return wantUrl ? safeWebViewUrl((WebView) value) : safeWebViewTitle((WebView) value);
        }
        return null;
    }

    private static List<TabRecord> readFallbackTabRecords() {
        ArrayList<TabRecord> records = new ArrayList<TabRecord>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        try {
            File dir = agentDir();
            addRecordsFromSnapshotFile(records, new File(dir, "tabs.json"), seen);
            addRecordsFromSnapshotFile(records, new File(dir, "agent-session.json"), seen);
            addRecordsFromSnapshotFile(records, new File(dir, "webview-fallback.json"), seen);
        } catch (Throwable t) {
            log("read fallback tab records failed: " + t);
        }
        return records;
    }

    private static void addRecordsFromSnapshotFile(List<TabRecord> out, File file, Set<String> seen) {
        try {
            if (file == null || !file.exists() || file.length() <= 0 || file.length() > 1024 * 1024) {
                return;
            }
            JSONObject root = readJsonFile(file);
            JSONArray tabs = root.optJSONArray("tabs");
            if (tabs == null) {
                return;
            }
            int before = out.size();
            for (int i = 0; i < tabs.length(); i++) {
                JSONObject tab = tabs.optJSONObject(i);
                if (tab == null) {
                    continue;
                }
                String url = tab.optString("url", "").trim();
                if (url.length() == 0 || "null".equals(url)) {
                    continue;
                }
                if (!seen.add(url)) {
                    continue;
                }
                String title = tab.optString("title", "").trim();
                if (title.length() == 0 || "null".equals(title)) {
                    title = titleFromUrl(url);
                }
                out.add(new TabRecord(out.size(), title, url));
            }
            log("read fallback snapshot: file=" + file.getName() + " added=" + (out.size() - before));
        } catch (Throwable t) {
            log("read fallback snapshot failed: file=" + (file == null ? "null" : file.getName()) + " error=" + t);
        }
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

    private static BookmarkBatch createBookmarkBatch(List<TabRecord> tabs, boolean groupByDomain) {
        long now = System.currentTimeMillis();
        int bookmarkCount = countBookmarkableTabs(tabs);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now));
        String folderName = "书签-" + stamp + "-" + bookmarkCount;
        String fileBaseName = "via-" + stamp + "-" + bookmarkCount;
        return new BookmarkBatch(folderName, fileBaseName, now / 1000L, bookmarkCount,
                groupByDomain, groupTabsByDomain(tabs));
    }

    private static LinkedHashMap<String, List<TabRecord>> groupTabsByDomain(List<TabRecord> tabs) {
        LinkedHashMap<String, List<TabRecord>> groups = new LinkedHashMap<String, List<TabRecord>>();
        if (tabs == null) {
            return groups;
        }
        for (TabRecord tab : tabs) {
            if (tab == null || !isBookmarkableUrl(tab.url)) {
                continue;
            }
            String groupName = domainGroupName(tab.url);
            List<TabRecord> group = groups.get(groupName);
            if (group == null) {
                group = new ArrayList<TabRecord>();
                groups.put(groupName, group);
            }
            group.add(tab);
        }
        return groups;
    }

    private static BookmarkSaveResult saveTabsToViaBookmarks(BookmarkBatch batch, List<TabRecord> tabs) throws Exception {
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
            String folderId = ensureBookmarkFolder(db, batch.folderName, "");
            long now = System.currentTimeMillis() / 1000L;
            if (batch.groupByDomain) {
                for (TabRecord tab : tabs) {
                    if (tab == null || !isBookmarkableUrl(tab.url)) {
                        result.skipped++;
                    }
                }
                int folderOrdering = maxOrdering(db, "bookmark_folders", "parent_folder_id", folderId) + 1;
                for (Map.Entry<String, List<TabRecord>> entry : batch.domainGroups.entrySet()) {
                    String childFolderId = ensureBookmarkFolder(db, entry.getKey(), folderId, folderOrdering++);
                    int ordering = maxOrdering(db, "bookmark_items", "folder_id", childFolderId) + 1;
                    for (TabRecord tab : entry.getValue()) {
                        ordering = insertBookmarkIfNeeded(db, childFolderId, tab, ordering, now, result);
                    }
                }
            } else {
                int ordering = maxOrdering(db, "bookmark_items", "folder_id", folderId) + 1;
                for (TabRecord tab : tabs) {
                    ordering = insertBookmarkIfNeeded(db, folderId, tab, ordering, now, result);
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

    private static int insertBookmarkIfNeeded(SQLiteDatabase db, String folderId, TabRecord tab,
                                              int ordering, long now, BookmarkSaveResult result) {
        if (tab == null || !isBookmarkableUrl(tab.url)) {
            result.skipped++;
            return ordering;
        }
        if (bookmarkExists(db, folderId, tab.url)) {
            result.skipped++;
            return ordering;
        }
        ContentValues values = new ContentValues();
        values.put("_id", UUID.randomUUID().toString());
        values.put("url", tab.url);
        values.put("title", displayTitleForBookmark(tab));
        values.put("folder_id", folderId);
        values.put("ordering", ordering);
        values.put("created_at", now);
        values.put("last_updated_at", now);
        long row = db.insert("bookmark_items", null, values);
        if (row >= 0) {
            result.inserted++;
            return ordering + 1;
        }
        result.skipped++;
        return ordering;
    }

    private static String displayTitleForBookmark(TabRecord tab) {
        if (tab == null) {
            return "";
        }
        String title = tab.title == null ? "" : tab.title.trim();
        if (title.length() == 0 || ("主页".equals(title) && !isHomepageUrl(tab.url))) {
            return titleFromUrl(tab.url);
        }
        return title;
    }

    private static boolean isHomepageUrl(String url) {
        if (url == null) {
            return false;
        }
        String normalized = url.trim().toLowerCase(Locale.US);
        return normalized.startsWith("file://") && normalized.contains("homepage");
    }

    private static boolean isBookmarkableUrl(String url) {
        if (url == null) {
            return false;
        }
        String normalized = url.trim().toLowerCase();
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private static String domainGroupName(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null || host.trim().length() == 0) {
                return "other";
            }
            String[] parts = normalizeHostParts(host);
            for (int i = effectiveDomainEnd(parts); i >= 0; i--) {
                String part = parts[i] == null ? "" : parts[i].trim();
                if (part.length() == 0 || isHostPrefixLabel(part) || isPublicSuffixLabel(part)) {
                    continue;
                }
                return part;
            }
        } catch (Throwable ignored) {
        }
        return "other";
    }

    private static String[] normalizeHostParts(String host) {
        if (host == null) {
            return new String[0];
        }
        String normalized = host.toLowerCase(Locale.US).trim();
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.split("\\.");
    }

    private static int effectiveDomainEnd(String[] parts) {
        if (parts == null || parts.length == 0) {
            return -1;
        }
        int end = parts.length - 1;
        if (end >= 1 && isTwoPartPublicSuffix(parts[end - 1], parts[end])) {
            return end - 2;
        }
        while (end >= 0 && isPublicSuffixLabel(parts[end])) {
            end--;
        }
        return end;
    }

    private static boolean isHostPrefixLabel(String label) {
        return "www".equals(label)
                || "m".equals(label)
                || "mobile".equals(label)
                || "wap".equals(label)
                || "touch".equals(label);
    }

    private static boolean isTwoPartPublicSuffix(String left, String right) {
        String suffix = left + "." + right;
        return "com.cn".equals(suffix)
                || "net.cn".equals(suffix)
                || "org.cn".equals(suffix)
                || "gov.cn".equals(suffix)
                || "com.hk".equals(suffix)
                || "com.tw".equals(suffix)
                || "co.uk".equals(suffix)
                || "org.uk".equals(suffix)
                || "ac.uk".equals(suffix)
                || "co.jp".equals(suffix)
                || "com.au".equals(suffix);
    }

    private static boolean isPublicSuffixLabel(String label) {
        return "com".equals(label)
                || "cn".equals(label)
                || "net".equals(label)
                || "org".equals(label)
                || "edu".equals(label)
                || "gov".equals(label)
                || "mil".equals(label)
                || "io".equals(label)
                || "ai".equals(label)
                || "ws".equals(label)
                || "xyz".equals(label)
                || "top".equals(label)
                || "cc".equals(label)
                || "me".equals(label)
                || "app".equals(label)
                || "dev".equals(label)
                || "info".equals(label)
                || "biz".equals(label)
                || "co".equals(label)
                || "uk".equals(label)
                || "jp".equals(label)
                || "tv".equals(label);
    }

    private static void ensureBookmarkTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmark_items(_id TEXT PRIMARY KEY,url TEXT,title TEXT,folder_id TEXT,ordering INTEGER,last_updated_at INTEGER DEFAULT 0,created_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmark_folders(_id TEXT PRIMARY KEY,title TEXT,parent_folder_id TEXT,ordering INTEGER,created_at INTEGER DEFAULT 0,last_updated_at INTEGER DEFAULT 0)");
    }

    private static String ensureBookmarkFolder(SQLiteDatabase db, String folderName, String parentId) {
        return ensureBookmarkFolder(db, folderName, parentId, maxOrdering(db, "bookmark_folders", "parent_folder_id", parentId) + 1);
    }

    private static String ensureBookmarkFolder(SQLiteDatabase db, String folderName, String parentId, int ordering) {
        String safeName = folderName == null || folderName.trim().length() == 0 ? "ViaTabsAgent" : folderName.trim();
        String safeParentId = parentId == null ? "" : parentId;
        String existing = findBookmarkFolder(db, safeName, safeParentId);
        if (existing != null) {
            return existing;
        }
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000L;
        ContentValues values = new ContentValues();
        values.put("_id", id);
        values.put("title", safeName);
        values.put("parent_folder_id", safeParentId);
        values.put("ordering", ordering);
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

    private static String writeBookmarkSaveSnapshot(BookmarkBatch batch, List<TabRecord> tabs,
                                                    BookmarkSaveResult result, String source) {
        try {
            JSONObject root = baseSnapshot("bookmarks.saveTabs");
            root.put("captureSource", source == null ? JSONObject.NULL : source);
            root.put("folder", batch.folderName);
            root.put("folderId", result.folderId == null ? JSONObject.NULL : result.folderId);
            root.put("domainGroup", batch.groupByDomain);
            root.put("captured", tabs == null ? 0 : tabs.size());
            root.put("bookmarkable", batch.bookmarkCount);
            root.put("inserted", result.inserted);
            root.put("skipped", result.skipped);
            root.put("bookmarkError", result.error == null ? JSONObject.NULL : result.error);
            root.put("tabs", toTabRecordArray(tabs));
            String htmlFileName = batch.fileBaseName + ".html";
            String htmlPath = writeJsonToDownloadsNow(htmlFileName, toNetscapeBookmarksHtml(batch, tabs));
            result.htmlExportPath = htmlPath;
            root.put("htmlExportPath", htmlPath == null ? JSONObject.NULL : "Download/ViaTabsAgent/" + htmlFileName);
            String fileName = batch.fileBaseName + ".json";
            root.put("exportPath", "Download/ViaTabsAgent/" + fileName);
            String jsonPath = writeJsonToDownloadsNow(fileName, root.toString(2));
            result.jsonExportPath = jsonPath;
            if (htmlPath != null) {
                log("wrote bookmark import html: " + htmlPath);
            }
            return jsonPath;
        } catch (Throwable t) {
            log("write bookmark save snapshot failed: " + t);
            return null;
        }
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
            tab.put("title", displayTitleForBookmark(record));
            tab.put("url", record.url);
            tabs.put(tab);
        }
        return tabs;
    }

    private static String toNetscapeBookmarksHtml(BookmarkBatch batch, List<TabRecord> tabs) {
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
                .append(escapeBookmarkHtml(safeFolder)).append("</H3>\n");
        html.append("  <DL><p>\n");
        if (batch.groupByDomain) {
            for (Map.Entry<String, List<TabRecord>> entry : batch.domainGroups.entrySet()) {
                html.append("    <DT><H3 ADD_DATE=\"").append(addDate).append("\">")
                        .append(escapeBookmarkHtml(entry.getKey())).append("</H3>\n");
                html.append("    <DL><p>\n");
                appendBookmarkLinks(html, entry.getValue(), addDate, "      ");
                html.append("    </DL><p>\n");
            }
        } else {
            appendBookmarkLinks(html, tabs, addDate, "    ");
        }
        html.append("  </DL><p>\n");
        html.append("</DL><p>\n");
        return html.toString();
    }

    private static void appendBookmarkLinks(StringBuilder html, List<TabRecord> tabs, long addDate, String indent) {
        if (tabs == null) {
            return;
        }
        for (TabRecord tab : tabs) {
            if (tab == null || !isBookmarkableUrl(tab.url)) {
                continue;
            }
            html.append(indent).append("<DT><A HREF=\"").append(escapeBookmarkHtml(tab.url))
                    .append("\" ADD_DATE=\"").append(addDate).append("\">")
                    .append(escapeBookmarkHtml(displayTitleForBookmark(tab))).append("</A>\n");
        }
    }

    private static String escapeBookmarkHtml(String value) {
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

    private static String unescapeBookmarkHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
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
                    Object rawCurrent = callTabListMethod(manager);
                    List<String> current = rawCurrent instanceof List ? normalizeUrls((List<?>) rawCurrent) : new ArrayList<String>();
                    Set<String> currentSet = new LinkedHashSet<String>(current);
                    int opened = 0;
                    for (String url : saved) {
                        if (currentSet.contains(url)) {
                            continue;
                        }
                        callOpenUrlMethod(manager, url);
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
            logSnapshotWrite(out);
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

    private static String writeJsonToDownloadsNow(String fileName, String payload) {
        if (appContext == null) {
            return null;
        }
        String path = writeJsonToDownloadsViaProvider(fileName, payload);
        if (path != null) {
            return path;
        }
        path = writeJsonToDownloadsViaBroadcast(fileName, payload);
        if (path != null) {
            return path;
        }
        try {
            return AgentStore.writeDownloadFile(appContext, fileName, payload);
        } catch (Throwable t) {
            log("write Download fallback failed: " + t);
            return null;
        }
    }

    private static String writeJsonToDownloadsViaProvider(String fileName, String payload) {
        try {
            Bundle extras = new Bundle();
            extras.putString(ExportProvider.EXTRA_FILE_NAME, fileName);
            extras.putString(ExportProvider.EXTRA_PAYLOAD, payload);
            Bundle result = appContext.getContentResolver().call(AgentStore.EXPORT_URI,
                    ExportProvider.METHOD_WRITE_FILE, null, extras);
            if (result == null) {
                log("module export provider returned null");
                return null;
            }
            String error = result.getString("error");
            if (error != null && error.length() > 0) {
                log("module export provider failed: " + error);
                return null;
            }
            String path = result.getString(ExportProvider.EXTRA_PATH);
            if (path != null && path.length() > 0) {
                log("wrote " + path + " via module provider");
                return path;
            }
        } catch (Throwable t) {
            Log.i(TAG, "module export provider unavailable: " + t);
            XposedBridge.log(TAG + ": module export provider unavailable: " + t);
        }
        return null;
    }

    private static String writeJsonToDownloadsViaBroadcast(String fileName, String payload) {
        try {
            Intent intent = new Intent(ExportReceiver.ACTION_WRITE_FILE);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, "com.viatabs.agent.ExportReceiver"));
            intent.putExtra(ExportProvider.EXTRA_FILE_NAME, fileName);
            intent.putExtra(ExportProvider.EXTRA_PAYLOAD, payload);
            appContext.sendBroadcast(intent);
            String path = "/storage/emulated/0/Download/ViaTabsAgent/" + fileName;
            Log.i(TAG, "sent export broadcast: " + path);
            XposedBridge.log(TAG + ": sent export broadcast: " + path);
            return path;
        } catch (Throwable t) {
            Log.i(TAG, "module export broadcast unavailable: " + t);
            XposedBridge.log(TAG + ": module export broadcast unavailable: " + t);
            return null;
        }
    }

    private static boolean isTabManagerSource(String source) {
        return source != null && source.startsWith("tabManager.");
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
        appendModuleLog(message);
    }

    private static void appendModuleLog(String message) {
        if (appContext == null || message == null) {
            return;
        }
        try {
            Bundle extras = new Bundle();
            extras.putString(ExportProvider.EXTRA_MESSAGE, message);
            appContext.getContentResolver().call(AgentStore.LOG_URI,
                    ExportProvider.METHOD_APPEND_LOG, null, extras);
        } catch (Throwable providerError) {
            try {
                Intent intent = new Intent(ExportReceiver.ACTION_APPEND_LOG);
                intent.setComponent(new ComponentName(MODULE_PACKAGE, "com.viatabs.agent.ExportReceiver"));
                intent.putExtra(ExportProvider.EXTRA_MESSAGE, message);
                appContext.sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
        }
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

    private static final class TabManagerCandidate {
        final Class<?> clazz;
        final Method listMethod;
        final Method openMethod;
        final Method switchMethod;
        final Method bundleMethod;

        TabManagerCandidate(Class<?> clazz, Method listMethod, Method openMethod,
                            Method switchMethod, Method bundleMethod) {
            this.clazz = clazz;
            this.listMethod = listMethod;
            this.openMethod = openMethod;
            this.switchMethod = switchMethod;
            this.bundleMethod = bundleMethod;
        }
    }

    private static final class BookmarkSaveResult {
        String folderId;
        String htmlExportPath;
        String jsonExportPath;
        int inserted;
        int skipped;
        String error;
    }

    private static final class BookmarkBatch {
        final String folderName;
        final String fileBaseName;
        final long addDate;
        final int bookmarkCount;
        final boolean groupByDomain;
        final LinkedHashMap<String, List<TabRecord>> domainGroups;

        BookmarkBatch(String folderName, String fileBaseName, long addDate, int bookmarkCount,
                      boolean groupByDomain, LinkedHashMap<String, List<TabRecord>> domainGroups) {
            this.folderName = folderName;
            this.fileBaseName = fileBaseName;
            this.addDate = addDate;
            this.bookmarkCount = bookmarkCount;
            this.groupByDomain = groupByDomain;
            this.domainGroups = domainGroups;
        }
    }

    private static void logSnapshotWrite(File out) {
        if (out == null) {
            return;
        }
        String path = out.getAbsolutePath();
        long now = System.currentTimeMillis();
        synchronized (LAST_FILE_LOG_AT) {
            Long previous = LAST_FILE_LOG_AT.get(path);
            if (previous != null && now - previous.longValue() < SNAPSHOT_LOG_INTERVAL_MS) {
                return;
            }
            LAST_FILE_LOG_AT.put(path, now);
        }
        log("wrote " + path);
    }
}
