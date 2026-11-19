package com.autoskip.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 不跳过规则：按应用 / 按场景（包名 + Activity）排除，外加内置的小程序容器识别。
 * 数据都存在 SharedPreferences，进程内有一份缓存，规则变化时通过广播让服务重新加载。
 */
final class SkipRules {

    static final String PREFS = "auto_skip";
    static final String ACTION_RULES_CHANGED = "com.autoskip.app.RULES_CHANGED";

    private static final String KEY_PACKAGES = "rules_excluded_packages";
    private static final String KEY_SCENES = "rules_excluded_scenes";
    private static final String KEY_RECENT = "rules_recent_skips";
    private static final int MAX_RECENT = 20;

    /**
     * 内置场景：这些 Activity 里出现“跳过/关闭”按钮大概率不是开屏广告，而是
     * 小程序/内嵌页自己的控件，一律不跳。
     * 微信小程序：com.tencent.mm.plugin.appbrand.ui.AppBrandUI*
     * 支付宝小程序/H5 容器：com.alipay.mobile.nebulacore.* / Nebula*
     */
    private static final String[] BUILTIN_SCENE_PATTERNS = {
            "AppBrand", "appbrand", "MiniProgram", "miniprogram", "Nebula", "nebula"
    };

    private static volatile Set<String> packages = null;
    private static volatile Set<String> scenes = null;

    private SkipRules() {
    }

    static boolean isExcluded(Context context, String pkg, String cls) {
        if (pkg == null || pkg.isEmpty()) {
            return true;
        }
        Set<String> pkgs = getPackages(context);
        if (pkgs.contains(pkg)) {
            return true;
        }
        if (cls == null || cls.isEmpty()) {
            return false;
        }
        for (String pattern : BUILTIN_SCENE_PATTERNS) {
            if (cls.contains(pattern)) {
                return true;
            }
        }
        return getScenes(context).contains(sceneKey(pkg, cls));
    }

    static String sceneKey(String pkg, String cls) {
        return pkg + "/" + (cls == null ? "-" : cls);
    }

    static String simpleClass(String cls) {
        if (cls == null) {
            return "-";
        }
        int idx = cls.lastIndexOf('.');
        return idx >= 0 ? cls.substring(idx + 1) : cls;
    }

    // ---- 包级名单 ----

    static Set<String> getPackages(Context context) {
        Set<String> cached = packages;
        if (cached == null) {
            synchronized (SkipRules.class) {
                if (packages == null) {
                    packages = new HashSet<>(prefs(context)
                            .getStringSet(KEY_PACKAGES, new HashSet<String>()));
                }
                cached = packages;
            }
        }
        return cached;
    }

    static void addPackage(Context context, String pkg) {
        Set<String> set = new HashSet<>(getPackages(context));
        set.add(pkg);
        prefs(context).edit().putStringSet(KEY_PACKAGES, set).apply();
        packages = set;
        notifyChanged(context);
    }

    static void removePackage(Context context, String pkg) {
        Set<String> set = new HashSet<>(getPackages(context));
        set.remove(pkg);
        prefs(context).edit().putStringSet(KEY_PACKAGES, set).apply();
        packages = set;
        notifyChanged(context);
    }

    // ---- 场景级名单 ----

    static Set<String> getScenes(Context context) {
        Set<String> cached = scenes;
        if (cached == null) {
            synchronized (SkipRules.class) {
                if (scenes == null) {
                    scenes = new HashSet<>(prefs(context)
                            .getStringSet(KEY_SCENES, new HashSet<String>()));
                }
                cached = scenes;
            }
        }
        return cached;
    }

    static void addScene(Context context, String pkg, String cls) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        Set<String> set = new HashSet<>(getScenes(context));
        set.add(sceneKey(pkg, cls));
        prefs(context).edit().putStringSet(KEY_SCENES, set).apply();
        scenes = set;
        notifyChanged(context);
    }

    static void removeScene(Context context, String key) {
        Set<String> set = new HashSet<>(getScenes(context));
        set.remove(key);
        prefs(context).edit().putStringSet(KEY_SCENES, set).apply();
        scenes = set;
        notifyChanged(context);
    }

    static void load(Context context) {
        packages = new HashSet<>(prefs(context).getStringSet(KEY_PACKAGES, new HashSet<String>()));
        scenes = new HashSet<>(prefs(context).getStringSet(KEY_SCENES, new HashSet<String>()));
    }

    // ---- 最近跳过 ----

    static final class Recent {
        long ts;
        String pkg;
        String cls;
        String label;
    }

    static void recordSkip(Context context, String pkg, String cls, String label) {
        try {
            long ts = System.currentTimeMillis();
            String line = ts + "|" + pkg + "|" + (cls == null ? "-" : cls) + "|"
                    + (label == null ? "-" : label.replace("\n", " "));
            List<String> lines = new ArrayList<>();
            lines.add(line);
            for (String old : prefs(context).getString(KEY_RECENT, "").split("\n")) {
                if (!old.isEmpty() && lines.size() < MAX_RECENT) {
                    lines.add(old);
                }
            }
            prefs(context).edit().putString(KEY_RECENT, join(lines)).apply();
        } catch (Throwable ignored) {
        }
    }

    static List<Recent> getRecent(Context context) {
        List<Recent> out = new ArrayList<>();
        try {
            String raw = prefs(context).getString(KEY_RECENT, "");
            if (raw == null || raw.isEmpty()) {
                return out;
            }
            for (String line : raw.split("\n")) {
                String[] parts = line.split("\\|", 4);
                if (parts.length < 4) {
                    continue;
                }
                Recent r = new Recent();
                r.ts = Long.parseLong(parts[0]);
                r.pkg = parts[1];
                r.cls = parts[2];
                r.label = parts[3];
                out.add(r);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 清空最近跳过记录（调试用）。 */
    static void clearRecent(Context context) {
        prefs(context).edit().remove(KEY_RECENT).apply();
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void notifyChanged(Context context) {
        try {
            context.sendBroadcast(new android.content.Intent(ACTION_RULES_CHANGED)
                    .setPackage(context.getPackageName()));
        } catch (Throwable ignored) {
        }
    }
}
