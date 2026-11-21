package com.autoskip.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 纯原生 UI 工具集：零第三方依赖，兼容 minSdk 24 / Java 8 / AAPT1。
 * 负责主题色解析、卡片与水波纹绘制、现代胶囊开关、底部面板、时间格式化及异步应用图标缓存。
 */
final class UiKit {

    private static final LruCache<String, Drawable> ICON_CACHE = new LruCache<>(120);
    private static final LruCache<String, String> LABEL_CACHE = new LruCache<>(200);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UiKit() {
    }

    static int dp(Context context, float value) {
        return (int) (context.getResources().getDisplayMetrics().density * value + 0.5f);
    }

    static int themeColor(Context context, int attr, int fallback) {
        try {
            TypedValue tv = new TypedValue();
            if (context.getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.resourceId != 0) {
                    return context.getColor(tv.resourceId);
                }
                return tv.data;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    static boolean isDarkTheme(Context context) {
        int bg = themeColor(context, R.attr.appBg, 0xFFF4F6FA);
        double lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0;
        return lum < 0.5;
    }

    /** 统一状态栏与导航栏底色及前景色（安全兼容 API 24-36）。 */
    @SuppressWarnings("deprecation")
    static void applySystemBars(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            int bg = themeColor(activity, R.attr.appBg, 0xFFF4F6FA);
            boolean dark = isDarkTheme(activity);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(bg);
            window.setNavigationBarColor(bg);
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (dark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {
        }
    }

    static void haptic(View view) {
        if (view != null) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            } catch (Throwable ignored) {
            }
        }
    }

    static void clipRounded(View view, final float radiusPx) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });
        view.setClipToOutline(true);
    }

    static GradientDrawable roundedRect(int fillColor, float radiusPx, int strokePx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        d.setColor(fillColor);
        if (strokePx > 0) {
            d.setStroke(strokePx, strokeColor);
        }
        return d;
    }

    static GradientDrawable topRoundedRect(int fillColor, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadii(new float[]{radiusPx, radiusPx, radiusPx, radiusPx, 0, 0, 0, 0});
        d.setColor(fillColor);
        return d;
    }

    static GradientDrawable circle(int fillColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fillColor);
        return d;
    }

    static GradientDrawable gradientCard(int startColor, int endColor, float radiusPx,
                                         int strokePx, int strokeColor) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{startColor, endColor});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) {
            d.setStroke(strokePx, strokeColor);
        }
        return d;
    }

    static Drawable rippleRounded(int fillColor, int rippleColor, float radiusPx,
                                  int strokePx, int strokeColor) {
        GradientDrawable content = roundedRect(fillColor, radiusPx, strokePx, strokeColor);
        GradientDrawable mask = roundedRect(0xFFFFFFFF, radiusPx, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    static Drawable rippleRow(int rippleColor, float radiusPx) {
        GradientDrawable mask = roundedRect(0xFFFFFFFF, radiusPx, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask);
    }

    static int subtleRipple(Context context) {
        return isDarkTheme(context) ? 0x22FFFFFF : 0x160F172A;
    }

    /** 现代圆角胶囊开关，替代原生默认 Material 1 样式。 */
    static void styleSwitch(Context context, Switch sw, int onTrackColor,
                            int offTrackColor, int thumbColor) {
        int trackW = dp(context, 48);
        int trackH = dp(context, 28);
        int inset = dp(context, 3);
        int radius = trackH / 2;

        GradientDrawable onTrack = new GradientDrawable();
        onTrack.setShape(GradientDrawable.RECTANGLE);
        onTrack.setCornerRadius(radius);
        onTrack.setColor(onTrackColor);
        onTrack.setSize(trackW, trackH);

        GradientDrawable offTrack = new GradientDrawable();
        offTrack.setShape(GradientDrawable.RECTANGLE);
        offTrack.setCornerRadius(radius);
        offTrack.setColor(offTrackColor);
        offTrack.setSize(trackW, trackH);

        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, onTrack);
        track.addState(new int[]{}, offTrack);

        GradientDrawable thumbCircle = new GradientDrawable();
        thumbCircle.setShape(GradientDrawable.OVAL);
        thumbCircle.setColor(thumbColor);
        thumbCircle.setSize(trackH - inset * 2, trackH - inset * 2);

        LayerDrawable thumb = new LayerDrawable(new Drawable[]{thumbCircle});
        thumb.setLayerInset(0, inset, inset, inset, inset);

        sw.setTrackDrawable(track);
        sw.setThumbDrawable(thumb);
        sw.setSwitchMinWidth(trackW);
        sw.setShowText(false);
        sw.setSplitTrack(false);
    }

    /** 统一样式的底部圆角弹窗（顶部拖拽条 + 圆角底板 + 滑入动画）。 */
    static Dialog createBottomSheet(Activity activity, View contentView) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        int sheetBg = themeColor(activity, R.attr.cardBg, 0xFFFFFFFF);
        wrapper.setBackground(topRoundedRect(sheetBg, dp(activity, 24)));
        wrapper.setPadding(0, dp(activity, 10), 0, dp(activity, 20));

        // 顶部居中拖拽条
        View handle = new View(activity);
        int handleColor = isDarkTheme(activity) ? 0x38FFFFFF : 0x260F172A;
        handle.setBackground(roundedRect(handleColor, dp(activity, 2.5f), 0, 0));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                dp(activity, 38), dp(activity, 4.5f));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(activity, 10);
        wrapper.addView(handle, hlp);

        wrapper.addView(contentView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(wrapper);
        dialog.setCanceledOnTouchOutside(true);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(isDarkTheme(activity) ? 0.62f : 0.42f);
            window.setWindowAnimations(R.style.BottomSheetAnim);
        }
        return dialog;
    }

    static String formatSavedTime(int skipCount) {
        if (skipCount <= 0) {
            return "0 秒";
        }
        long sec = skipCount * 3L;
        if (sec < 60) {
            return sec + " 秒";
        }
        if (sec < 3600) {
            double min = sec / 60.0;
            return formatOneDecimal(min) + " 分钟";
        }
        double hour = sec / 3600.0;
        return formatOneDecimal(hour) + " 小时";
    }

    private static String formatOneDecimal(double val) {
        String s = String.format(Locale.ROOT, "%.1f", val);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    static String formatCompactCount(int n) {
        if (n < 1000) {
            return String.valueOf(n);
        }
        if (n < 10000) {
            return formatOneDecimal(n / 1000.0) + "k";
        }
        return formatOneDecimal(n / 10000.0) + "万";
    }

    static String formatRelativeTime(long wallMs) {
        if (wallMs <= 0) {
            return "";
        }
        long now = System.currentTimeMillis();
        long diff = now - wallMs;
        if (diff >= 0 && diff < 60_000L) {
            return "刚刚";
        }
        if (diff >= 0 && diff < 3_600_000L) {
            return (diff / 60_000L) + " 分钟前";
        }
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(wallMs);
        Calendar today = Calendar.getInstance();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        if (target.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && target.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
            return "今天 " + hm.format(new Date(wallMs));
        }
        today.add(Calendar.DAY_OF_YEAR, -1);
        if (target.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && target.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
            return "昨天 " + hm.format(new Date(wallMs));
        }
        SimpleDateFormat mdhm = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        return mdhm.format(new Date(wallMs));
    }

    static String getAppLabel(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "-";
        }
        String cached = LABEL_CACHE.get(pkg);
        if (cached != null) {
            return cached;
        }
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            String label = pm.getApplicationLabel(ai).toString();
            if (label != null && !label.isEmpty()) {
                LABEL_CACHE.put(pkg, label);
                return label;
            }
        } catch (Throwable ignored) {
        }
        return pkg;
    }

    /** 异步加载并缓存应用图标，带 Tag 校验避免列表复用闪烁。 */
    static void loadAppIcon(final Context context, final String pkg, final ImageView target) {
        target.setTag(pkg);
        if (pkg == null || pkg.isEmpty()) {
            target.setImageDrawable(null);
            return;
        }
        Drawable cached = ICON_CACHE.get(pkg);
        if (cached != null) {
            target.setImageDrawable(cached);
            return;
        }
        // 占位淡底，避免加载前跳动
        target.setImageDrawable(roundedRect(
                themeColor(context, R.attr.surfaceSubtle, 0xFFEEF2F8), dp(context, 10), 0, 0));
        final Context appCtx = context.getApplicationContext();
        IO.execute(new Runnable() {
            @Override
            public void run() {
                Drawable loaded = null;
                try {
                    PackageManager pm = appCtx.getPackageManager();
                    loaded = pm.getApplicationIcon(pkg);
                } catch (Throwable ignored) {
                }
                if (loaded != null) {
                    ICON_CACHE.put(pkg, loaded);
                }
                final Drawable result = loaded;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (pkg.equals(target.getTag()) && result != null) {
                            target.setImageDrawable(result);
                        }
                    }
                });
            }
        });
    }

    static void putCachedIcon(String pkg, Drawable icon) {
        if (pkg != null && icon != null) {
            ICON_CACHE.put(pkg, icon);
        }
    }

    static void putCachedLabel(String pkg, String label) {
        if (pkg != null && label != null && !label.isEmpty()) {
            LABEL_CACHE.put(pkg, label);
        }
    }
}
