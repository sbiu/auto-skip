package com.autoskip.app;

import android.content.Context;

/**
 * 全局开关状态。MainActivity 负责修改，SkipService 负责应用。
 * 关闭时服务会把 eventTypes 置 0，系统不再派发任何无障碍事件，做到“关闭即零开销”。
 */
final class SkipState {

    static final String PREFS = "auto_skip";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_GUARD = "guard_enabled";

    private static volatile boolean enabled = true;

    /** 最近一次扫描结果，仅用于界面诊断（不落盘、不上传）。 */
    static volatile String lastScan = "-";

    private SkipState() {
    }

    static boolean isEnabled() {
        return enabled;
    }

    static boolean isGuardEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_GUARD, true);
    }

    static void load(Context context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    static void set(Context context, boolean value) {
        enabled = value;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, value).apply();
    }

    static void setGuardEnabled(Context context, boolean value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_GUARD, value).apply();
    }
}
