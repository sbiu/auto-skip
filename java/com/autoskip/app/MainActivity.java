package com.autoskip.app;

import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int THEME_AUTO = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_DARK = 2;
    private static final String PREFS = "auto_skip";
    private static final String KEY_THEME = "ui_theme_mode";

    private static final int TAB_RECENT = 0;
    private static final int TAB_EXCLUDED = 1;
    private static final int RECENT_PREVIEW_COUNT = 5;
    private static final long LIVE_REFRESH_MS = 2_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // 布局视图
    private LinearLayout mainContainer;
    private LinearLayout btnThemePill;
    private ImageView ivThemeIcon;
    private TextView tvThemeLabel;

    // Hero 状态主卡
    private LinearLayout heroCard;
    private LinearLayout heroBadge;
    private View heroBadgeDot;
    private TextView heroBadgeText;
    private TextView heroTitle;
    private TextView heroSubtitle;
    private Switch skipSwitch;
    private TextView btnHeroCta;
    private LinearLayout heroMetrics;
    private TextView metricCount;
    private TextView metricSaved;
    private TextView metricLastApp;

    // 运行守护卡片
    private LinearLayout cardGuard;
    private LinearLayout rowGuardToggle;
    private FrameLayout iconBgGuard;
    private ImageView ivGuardIcon;
    private TextView tvGuardSub;
    private Switch guardSwitch;
    private LinearLayout rowEnvHeader;
    private View envDot;
    private TextView tvEnvSummary;
    private TextView tvEnvToggle;
    private ImageView ivEnvChevron;
    private LinearLayout envDetails;

    // 分段标签卡片
    private LinearLayout cardActivity;
    private LinearLayout segmentTrack;
    private TextView tabRecent;
    private TextView tabExcluded;
    private LinearLayout listSubHeader;
    private TextView tvListHint;
    private TextView btnClearRecent;
    private LinearLayout btnAddApp;
    private ImageView ivAddPlus;
    private TextView tvAddAppLabel;
    private LinearLayout recentContainer;
    private LinearLayout excludedContainer;

    // 底部工具卡
    private LinearLayout cardDiag;
    private FrameLayout iconBgDiag;
    private ImageView ivDiagIcon;
    private TextView tvDiagBrief;
    private LinearLayout cardInfo;
    private FrameLayout iconBgInfo;
    private ImageView ivInfoIcon;

    // 状态字段
    private boolean bindingSwitch;
    private int currentTab = TAB_RECENT;
    private boolean recentExpanded;
    private Boolean userEnvExpanded;
    private String lastEnvSignature = "";
    private String lastListSignature = "";

    private final Runnable liveTick = new Runnable() {
        @Override
        public void run() {
            refresh(false);
            handler.postDelayed(this, LIVE_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        UiKit.applySystemBars(this);

        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt("ui_tab", TAB_RECENT);
            recentExpanded = savedInstanceState.getBoolean("ui_recent_exp", false);
            int envState = savedInstanceState.getInt("ui_env_exp", -1);
            userEnvExpanded = envState == -1 ? null : (envState == 1);
        }

        SkipState.load(this);

        bindViews();
        styleStaticSurfaces();
        bindEvents();

        requestNotificationPermission();
        if (SkipState.isGuardEnabled(this)) {
            GuardService.start(this);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("ui_tab", currentTab);
        outState.putBoolean("ui_recent_exp", recentExpanded);
        outState.putInt("ui_env_exp", userEnvExpanded == null ? -1 : (userEnvExpanded ? 1 : 0));
    }

    @Override
    protected void onResume() {
        super.onResume();
        SkipState.load(this);
        refresh(true);
        handler.removeCallbacks(liveTick);
        handler.postDelayed(liveTick, LIVE_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(liveTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void bindViews() {
        mainContainer = findViewById(R.id.main_container);
        ImageView brandIcon = findViewById(R.id.brand_icon);
        UiKit.clipRounded(brandIcon, UiKit.dp(this, 10));

        btnThemePill = findViewById(R.id.btn_theme_pill);
        ivThemeIcon = findViewById(R.id.iv_theme_icon);
        tvThemeLabel = findViewById(R.id.tv_theme_label);

        heroCard = findViewById(R.id.hero_card);
        heroBadge = findViewById(R.id.hero_badge);
        heroBadgeDot = findViewById(R.id.hero_badge_dot);
        heroBadgeText = findViewById(R.id.hero_badge_text);
        heroTitle = findViewById(R.id.hero_title);
        heroSubtitle = findViewById(R.id.hero_subtitle);
        skipSwitch = findViewById(R.id.switch_skip);
        btnHeroCta = findViewById(R.id.btn_hero_cta);
        heroMetrics = findViewById(R.id.hero_metrics);
        metricCount = findViewById(R.id.metric_count);
        metricSaved = findViewById(R.id.metric_saved);
        metricLastApp = findViewById(R.id.metric_last_app);

        cardGuard = findViewById(R.id.card_guard);
        rowGuardToggle = findViewById(R.id.row_guard_toggle);
        iconBgGuard = findViewById(R.id.icon_bg_guard);
        ivGuardIcon = findViewById(R.id.iv_guard_icon);
        tvGuardSub = findViewById(R.id.tv_guard_sub);
        guardSwitch = findViewById(R.id.switch_guard);
        rowEnvHeader = findViewById(R.id.row_env_header);
        envDot = findViewById(R.id.env_dot);
        tvEnvSummary = findViewById(R.id.tv_env_summary);
        tvEnvToggle = findViewById(R.id.tv_env_toggle);
        ivEnvChevron = findViewById(R.id.iv_env_chevron);
        envDetails = findViewById(R.id.env_details);

        cardActivity = findViewById(R.id.card_activity);
        segmentTrack = findViewById(R.id.segment_track);
        tabRecent = findViewById(R.id.tab_recent);
        tabExcluded = findViewById(R.id.tab_excluded);
        listSubHeader = findViewById(R.id.list_sub_header);
        tvListHint = findViewById(R.id.tv_list_hint);
        btnClearRecent = findViewById(R.id.btn_clear_recent);
        btnAddApp = findViewById(R.id.btn_add_app);
        ivAddPlus = findViewById(R.id.iv_add_plus);
        tvAddAppLabel = findViewById(R.id.tv_add_app_label);
        recentContainer = findViewById(R.id.recent_container);
        excludedContainer = findViewById(R.id.excluded_container);

        cardDiag = findViewById(R.id.card_diag);
        iconBgDiag = findViewById(R.id.icon_bg_diag);
        ivDiagIcon = findViewById(R.id.iv_diag_icon);
        tvDiagBrief = findViewById(R.id.tv_diag_brief);
        cardInfo = findViewById(R.id.card_info);
        iconBgInfo = findViewById(R.id.icon_bg_info);
        ivInfoIcon = findViewById(R.id.iv_info_icon);
    }

    private void styleStaticSurfaces() {
        int cardBg = UiKit.themeColor(this, R.attr.cardBg, 0xFFFFFFFF);
        int cardStroke = UiKit.themeColor(this, R.attr.cardStroke, 0xFFE6EAF2);
        int subtleBg = UiKit.themeColor(this, R.attr.surfaceSubtle, 0xFFEEF2F8);
        int accent = UiKit.themeColor(this, R.attr.accentColor, 0xFF1C6BFF);
        int accentSoft = UiKit.themeColor(this, R.attr.accentSoft, 0xFFEBF2FF);
        int accentText = UiKit.themeColor(this, R.attr.accentText, 0xFF1557D6);
        int danger = UiKit.themeColor(this, R.attr.dangerColor, 0xFFE11D48);
        int dangerSoft = UiKit.themeColor(this, R.attr.dangerSoft, 0xFFFFEBEF);
        int okColor = UiKit.themeColor(this, R.attr.okColor, 0xFF0D9F6E);
        int okSoft = UiKit.themeColor(this, R.attr.okSoft, 0xFFE6F7F0);
        int textSecondary = UiKit.themeColor(this, R.attr.textSecondary, 0xFF475569);
        int textHint = UiKit.themeColor(this, R.attr.textHint, 0xFF94A3B8);
        int switchOff = UiKit.themeColor(this, R.attr.switchTrackOff, 0xFFCBD5E1);
        int ripple = UiKit.subtleRipple(this);

        // 顶栏主题胶囊
        btnThemePill.setBackground(UiKit.rippleRounded(
                cardBg, ripple, UiKit.dp(this, 18), UiKit.dp(this, 1), cardStroke));
        ivThemeIcon.setColorFilter(textSecondary);

        // Hero 内部微玻璃元素
        heroBadge.setBackground(UiKit.roundedRect(0x28FFFFFF, UiKit.dp(this, 12), 0, 0));
        heroMetrics.setBackground(UiKit.roundedRect(0x1AFFFFFF, UiKit.dp(this, 14), 0, 0));
        UiKit.styleSwitch(this, skipSwitch, 0x66FFFFFF, 0x2EFFFFFF, Color.WHITE);

        // 守护卡片与开关
        cardGuard.setBackground(UiKit.roundedRect(
                cardBg, UiKit.dp(this, 20), UiKit.dp(this, 1), cardStroke));
        rowGuardToggle.setBackground(UiKit.rippleRow(ripple, UiKit.dp(this, 16)));
        iconBgGuard.setBackground(UiKit.roundedRect(accentSoft, UiKit.dp(this, 11), 0, 0));
        ivGuardIcon.setColorFilter(accent);
        UiKit.styleSwitch(this, guardSwitch, accent, switchOff, Color.WHITE);
        rowEnvHeader.setBackground(UiKit.rippleRow(ripple, UiKit.dp(this, 14)));
        ivEnvChevron.setColorFilter(textHint);

        // 分段标签卡片
        cardActivity.setBackground(UiKit.roundedRect(
                cardBg, UiKit.dp(this, 20), UiKit.dp(this, 1), cardStroke));
        segmentTrack.setBackground(UiKit.roundedRect(subtleBg, UiKit.dp(this, 13), 0, 0));

        btnClearRecent.setTextColor(danger);
        btnClearRecent.setBackground(UiKit.rippleRounded(
                dangerSoft, ripple, UiKit.dp(this, 14), 0, 0));

        btnAddApp.setBackground(UiKit.rippleRounded(
                accentSoft, ripple, UiKit.dp(this, 15), 0, 0));
        ivAddPlus.setColorFilter(accentText);
        tvAddAppLabel.setTextColor(accentText);

        // 底部双工具卡
        cardDiag.setBackground(UiKit.rippleRounded(
                cardBg, ripple, UiKit.dp(this, 18), UiKit.dp(this, 1), cardStroke));
        iconBgDiag.setBackground(UiKit.roundedRect(accentSoft, UiKit.dp(this, 10), 0, 0));
        ivDiagIcon.setColorFilter(accent);

        cardInfo.setBackground(UiKit.rippleRounded(
                cardBg, ripple, UiKit.dp(this, 18), UiKit.dp(this, 1), cardStroke));
        iconBgInfo.setBackground(UiKit.roundedRect(okSoft, UiKit.dp(this, 10), 0, 0));
        ivInfoIcon.setColorFilter(okColor);

        updateThemeButton();
    }

    private void bindEvents() {
        btnThemePill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.haptic(v);
                showThemeSheet();
            }
        });

        skipSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingSwitch) {
                    return;
                }
                UiKit.haptic(buttonView);
                SkipState.set(MainActivity.this, isChecked);
                sendBroadcast(new Intent(SkipService.ACTION_STATE_CHANGED)
                        .setPackage(getPackageName()));
                animateContainer();
                refresh(true);
            }
        });

        btnHeroCta.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.haptic(v);
                openAccessibilitySettings();
            }
        });

        guardSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingSwitch) {
                    return;
                }
                UiKit.haptic(buttonView);
                SkipState.setGuardEnabled(MainActivity.this, isChecked);
                if (isChecked) {
                    GuardService.start(MainActivity.this);
                } else {
                    GuardService.stop(MainActivity.this);
                }
                refresh(true);
            }
        });

        rowGuardToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (SkipState.isGuardEnabled(MainActivity.this) && !areNotificationsEnabledCompat()) {
                    openNotificationSettings();
                } else {
                    guardSwitch.setChecked(!guardSwitch.isChecked());
                }
            }
        });

        rowEnvHeader.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.haptic(v);
                boolean currentlyVisible = envDetails.getVisibility() == View.VISIBLE;
                userEnvExpanded = !currentlyVisible;
                animateContainer();
                refresh(true);
            }
        });

        tabRecent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentTab != TAB_RECENT) {
                    UiKit.haptic(v);
                    currentTab = TAB_RECENT;
                    animateCard(cardActivity);
                    refresh(true);
                }
            }
        });

        tabExcluded.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentTab != TAB_EXCLUDED) {
                    UiKit.haptic(v);
                    currentTab = TAB_EXCLUDED;
                    animateCard(cardActivity);
                    refresh(true);
                }
            }
        });

        btnClearRecent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.haptic(v);
                confirmClearRecent();
            }
        });

        btnAddApp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.haptic(v);
                AppPickerSheet.show(MainActivity.this, new AppPickerSheet.OnChangedListener() {
                    @Override
                    public void onRulesChanged() {
                        refresh(true);
                    }
                });
            }
        });

        cardDiag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.haptic(v);
                showDiagnosticsSheet();
            }
        });

        cardInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.haptic(v);
                showInfoSheet();
            }
        });
    }

    private void animateContainer() {
        try {
            AutoTransition t = new AutoTransition();
            t.setDuration(200);
            TransitionManager.beginDelayedTransition(mainContainer, t);
        } catch (Throwable ignored) {
        }
    }

    private void animateCard(ViewGroup target) {
        try {
            AutoTransition t = new AutoTransition();
            t.setDuration(180);
            TransitionManager.beginDelayedTransition(target, t);
        } catch (Throwable ignored) {
        }
    }

    // ---- 主界面数据刷新 ----

    private void refresh(boolean forceRebuildLists) {
        boolean serviceEnabled = isServiceEnabled();
        boolean connected = SkipService.isConnected();
        boolean skipEnabled = SkipState.isEnabled();
        boolean guardEnabled = SkipState.isGuardEnabled(this);
        boolean batteryOk = isIgnoringBatteryOptimizations();
        boolean notifOk = areNotificationsEnabledCompat();

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        int count = sp.getInt("skip_count", 0);
        String lastPkg = sp.getString("last_package", null);
        int events = sp.getInt("dbg_events", 0);

        updateHeroCard(serviceEnabled, connected, skipEnabled, count, lastPkg);
        updateGuardAndEnvCard(serviceEnabled, connected, guardEnabled, batteryOk, notifOk, forceRebuildLists);
        updateActivityCard(forceRebuildLists);

        String diagStatus = connected ? getString(R.string.pill_connected)
                : (serviceEnabled ? getString(R.string.pill_waiting) : getString(R.string.pill_off));
        tvDiagBrief.setText(UiKit.formatCompactCount(events) + " 事件 · " + diagStatus);
    }

    private void updateHeroCard(boolean serviceEnabled, boolean connected,
                                boolean skipEnabled, int count, String lastPkg) {
        int start;
        int end;
        int dotColor;
        int ctaColor;

        if (!serviceEnabled) {
            start = UiKit.themeColor(this, R.attr.heroAlertStart, 0xFFE11D48);
            end = UiKit.themeColor(this, R.attr.heroAlertEnd, 0xFFBE123C);
            dotColor = 0xFFFFFFFF;
            ctaColor = 0xFFBE123C;
            heroBadgeText.setText(R.string.hero_badge_off);
            heroTitle.setText(R.string.hero_title_off);
            heroSubtitle.setText(R.string.hero_sub_off);
            skipSwitch.setVisibility(View.GONE);
            btnHeroCta.setVisibility(View.VISIBLE);
            btnHeroCta.setText(R.string.hero_cta_enable);
        } else if (!connected) {
            start = UiKit.themeColor(this, R.attr.heroWarnStart, 0xFFD97706);
            end = UiKit.themeColor(this, R.attr.heroWarnEnd, 0xFFB45309);
            dotColor = 0xFFFEF08A;
            ctaColor = 0xFFB45309;
            heroBadgeText.setText(R.string.hero_badge_waiting);
            heroTitle.setText(R.string.hero_title_waiting);
            heroSubtitle.setText(R.string.hero_sub_waiting);
            skipSwitch.setVisibility(View.GONE);
            btnHeroCta.setVisibility(View.VISIBLE);
            btnHeroCta.setText(R.string.hero_cta_check);
        } else if (!skipEnabled) {
            start = UiKit.themeColor(this, R.attr.heroPausedStart, 0xFF475569);
            end = UiKit.themeColor(this, R.attr.heroPausedEnd, 0xFF334155);
            dotColor = 0xFFFBBF24;
            ctaColor = 0xFF334155;
            heroBadgeText.setText(R.string.hero_badge_paused);
            heroTitle.setText(R.string.hero_title_paused);
            heroSubtitle.setText(R.string.hero_sub_paused);
            skipSwitch.setVisibility(View.VISIBLE);
            btnHeroCta.setVisibility(View.GONE);
        } else {
            start = UiKit.themeColor(this, R.attr.heroStart, 0xFF1C6BFF);
            end = UiKit.themeColor(this, R.attr.heroEnd, 0xFF114CC9);
            dotColor = 0xFF34D399;
            ctaColor = 0xFF1557D6;
            heroBadgeText.setText(R.string.hero_badge_active);
            heroTitle.setText(R.string.hero_title_active);
            heroSubtitle.setText(R.string.hero_sub_active);
            skipSwitch.setVisibility(View.VISIBLE);
            btnHeroCta.setVisibility(View.GONE);
        }

        heroCard.setBackground(UiKit.gradientCard(
                start, end, UiKit.dp(this, 22), UiKit.dp(this, 1), 0x24FFFFFF));
        heroBadgeDot.setBackground(UiKit.circle(dotColor));
        if (btnHeroCta.getVisibility() == View.VISIBLE) {
            btnHeroCta.setTextColor(ctaColor);
            btnHeroCta.setBackground(UiKit.rippleRounded(
                    0xFFFFFFFF, 0x24000000, UiKit.dp(this, 18), 0, 0));
        }

        bindingSwitch = true;
        skipSwitch.setChecked(skipEnabled);
        bindingSwitch = false;

        metricCount.setText(String.valueOf(count));
        metricSaved.setText(UiKit.formatSavedTime(count));
        if (count <= 0 || lastPkg == null || lastPkg.isEmpty()) {
            metricLastApp.setText(R.string.metric_none);
        } else {
            metricLastApp.setText(UiKit.getAppLabel(this, lastPkg));
        }
    }

    private void updateGuardAndEnvCard(boolean serviceEnabled, boolean connected,
                                       boolean guardEnabled, boolean batteryOk,
                                       boolean notifOk, boolean forceRebuild) {
        bindingSwitch = true;
        guardSwitch.setChecked(guardEnabled);
        bindingSwitch = false;

        int textSecondary = UiKit.themeColor(this, R.attr.textSecondary, 0xFF475569);
        int textHint = UiKit.themeColor(this, R.attr.textHint, 0xFF94A3B8);
        int okColor = UiKit.themeColor(this, R.attr.okColor, 0xFF0D9F6E);
        int warnColor = UiKit.themeColor(this, R.attr.warnColor, 0xFFD97706);

        if (!guardEnabled) {
            tvGuardSub.setText(R.string.guard_sub_off);
            tvGuardSub.setTextColor(textHint);
        } else if (!notifOk) {
            tvGuardSub.setText(R.string.guard_sub_no_perm);
            tvGuardSub.setTextColor(warnColor);
        } else {
            tvGuardSub.setText(R.string.guard_sub_on);
            tvGuardSub.setTextColor(textSecondary);
        }

        boolean a11yOk = serviceEnabled && connected;
        int issues = (!a11yOk ? 1 : 0) + (!batteryOk ? 1 : 0)
                + (guardEnabled && !notifOk ? 1 : 0);

        boolean expanded = userEnvExpanded != null ? userEnvExpanded : (issues > 0);
        if (issues == 0) {
            envDot.setBackground(UiKit.circle(okColor));
            tvEnvSummary.setText(R.string.env_ready);
            tvEnvSummary.setTextColor(okColor);
        } else {
            envDot.setBackground(UiKit.circle(warnColor));
            tvEnvSummary.setText(getString(R.string.env_issues, issues));
            tvEnvSummary.setTextColor(warnColor);
        }

        tvEnvToggle.setText(expanded ? R.string.env_collapse : R.string.env_expand);
        ivEnvChevron.setRotation(expanded ? 180f : 0f);
        envDetails.setVisibility(expanded ? View.VISIBLE : View.GONE);

        String sig = serviceEnabled + "|" + connected + "|" + guardEnabled + "|"
                + batteryOk + "|" + notifOk + "|" + expanded;
        if (!expanded || (!forceRebuild && sig.equals(lastEnvSignature))) {
            return;
        }
        lastEnvSignature = sig;
        rebuildEnvDetails(serviceEnabled, connected, guardEnabled, batteryOk, notifOk);
    }

    private void rebuildEnvDetails(boolean serviceEnabled, boolean connected,
                                   boolean guardEnabled, boolean batteryOk, boolean notifOk) {
        envDetails.removeAllViews();

        int okColor = UiKit.themeColor(this, R.attr.okColor, 0xFF0D9F6E);
        int okSoft = UiKit.themeColor(this, R.attr.okSoft, 0xFFE6F7F0);
        int warnColor = UiKit.themeColor(this, R.attr.warnColor, 0xFFD97706);
        int warnSoft = UiKit.themeColor(this, R.attr.warnSoft, 0xFFFEF3E2);
        int dangerColor = UiKit.themeColor(this, R.attr.dangerColor, 0xFFE11D48);
        int dangerSoft = UiKit.themeColor(this, R.attr.dangerSoft, 0xFFFFEBEF);
        int accent = UiKit.themeColor(this, R.attr.accentColor, 0xFF1C6BFF);
        int accentSoft = UiKit.themeColor(this, R.attr.accentSoft, 0xFFEBF2FF);
        int subtleBg = UiKit.themeColor(this, R.attr.surfaceSubtle, 0xFFEEF2F8);
        int textSecondary = UiKit.themeColor(this, R.attr.textSecondary, 0xFF475569);

        // 1. 无障碍服务
        String a11yPill = connected ? getString(R.string.pill_connected)
                : (serviceEnabled ? getString(R.string.pill_waiting) : getString(R.string.pill_off));
        int a11yPillFg = connected ? okColor : (serviceEnabled ? warnColor : dangerColor);
        int a11yPillBg = connected ? okSoft : (serviceEnabled ? warnSoft : dangerSoft);
        envDetails.addView(buildEnvRow(
                R.drawable.ic_accessibility, accent, accentSoft,
                getString(R.string.item_a11y_title),
                getString(R.string.item_a11y_sub),
                a11yPill, a11yPillFg, a11yPillBg,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openAccessibilitySettings();
                    }
                }));

        // 2. 省电策略
        envDetails.addView(buildEnvRow(
                R.drawable.ic_battery,
                batteryOk ? okColor : warnColor,
                batteryOk ? okSoft : warnSoft,
                getString(R.string.item_battery_title),
                getString(R.string.item_battery_sub),
                getString(batteryOk ? R.string.pill_ok : R.string.pill_todo),
                batteryOk ? okColor : warnColor,
                batteryOk ? okSoft : warnSoft,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestBatteryExemption();
                    }
                }));

        // 3. 后台自启动
        envDetails.addView(buildEnvRow(
                R.drawable.ic_autostart, accent, accentSoft,
                getString(R.string.item_autostart_title),
                getString(isXiaomiDevice()
                        ? R.string.item_autostart_sub_miui : R.string.item_autostart_sub_generic),
                getString(R.string.pill_check), textSecondary, subtleBg,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openAutostartSettings();
                    }
                }));

        // 4. 通知显示设置（仅常驻守护开启时展示，便于在 HyperOS 提升通知重要性）
        if (guardEnabled) {
            envDetails.addView(buildEnvRow(
                    R.drawable.ic_bell,
                    notifOk ? okColor : warnColor,
                    notifOk ? okSoft : warnSoft,
                    getString(R.string.item_notif_title),
                    getString(R.string.item_notif_sub),
                    getString(notifOk ? R.string.pill_allowed : R.string.pill_denied),
                    notifOk ? okColor : warnColor,
                    notifOk ? okSoft : warnSoft,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openNotificationSettings();
                        }
                    }));
        }
    }

    private View buildEnvRow(int iconRes, int iconTint, int iconBg,
                             String titleText, String subText,
                             String pillText, int pillFg, int pillBg,
                             View.OnClickListener listener) {
        int textPrimary = UiKit.themeColor(this, R.attr.textPrimary, 0xFF0F172A);
        int textHint = UiKit.themeColor(this, R.attr.textHint, 0xFF94A3B8);
        int divider = UiKit.themeColor(this, R.attr.dividerColor, 0xFFEDF1F7);
        int ripple = UiKit.subtleRipple(this);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        View div = new View(this);
        div.setBackgroundColor(divider);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dlp.leftMargin = UiKit.dp(this, 62);
        dlp.rightMargin = UiKit.dp(this, 16);
        wrap.addView(div, dlp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiKit.rippleRow(ripple, UiKit.dp(this, 12)));
        row.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 11),
                UiKit.dp(this, 12), UiKit.dp(this, 11));
        row.setOnClickListener(listener);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(UiKit.roundedRect(iconBg, UiKit.dp(this, 10), 0, 0));
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setColorFilter(iconTint);
        FrameLayout.LayoutParams ivlp = new FrameLayout.LayoutParams(
                UiKit.dp(this, 17), UiKit.dp(this, 17));
        ivlp.gravity = Gravity.CENTER;
        iconBox.addView(iv, ivlp);
        row.addView(iconBox, new LinearLayout.LayoutParams(
                UiKit.dp(this, 34), UiKit.dp(this, 34)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(UiKit.dp(this, 12), 0, UiKit.dp(this, 8), 0);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(titleText);
        tvTitle.setTextSize(14);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvTitle.setTextColor(textPrimary);

        TextView tvSub = new TextView(this);
        tvSub.setText(subText);
        tvSub.setTextSize(11.5f);
        tvSub.setTextColor(textHint);
        tvSub.setPadding(0, UiKit.dp(this, 1.5f), 0, 0);

        texts.addView(tvTitle);
        texts.addView(tvSub);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView pill = new TextView(this);
        pill.setText(pillText);
        pill.setTextSize(11);
        pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        pill.setTextColor(pillFg);
        pill.setBackground(UiKit.roundedRect(pillBg, UiKit.dp(this, 11), 0, 0));
        pill.setPadding(UiKit.dp(this, 9), UiKit.dp(this, 4),
                UiKit.dp(this, 9), UiKit.dp(this, 4));
        row.addView(pill);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(textHint);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 16), UiKit.dp(this, 16));
        clp.leftMargin = UiKit.dp(this, 4);
        row.addView(chev, clp);

        wrap.addView(row);
        return wrap;
    }

    // ---- 分段卡片：最近跳过 & 不跳过名单 ----

    private void updateActivityCard(boolean forceRebuild) {
        List<SkipRules.Recent> recents = SkipRules.getRecent(this);
        Set<String> pkgs = SkipRules.getPackages(this);
        Set<String> scenes = SkipRules.getScenes(this);
        int excludedTotal = pkgs.size() + scenes.size();

        // 更新分段标签文案与高亮样式
        String recentLabel = recents.isEmpty()
                ? getString(R.string.tab_recent)
                : getString(R.string.tab_recent) + "  " + recents.size();
        String excludedLabel = excludedTotal == 0
                ? getString(R.string.tab_excluded)
                : getString(R.string.tab_excluded) + "  " + excludedTotal;
        tabRecent.setText(recentLabel);
        tabExcluded.setText(excludedLabel);

        int cardBg = UiKit.themeColor(this, R.attr.cardBg, 0xFFFFFFFF);
        int cardStroke = UiKit.themeColor(this, R.attr.cardStroke, 0xFFE6EAF2);
        int textPrimary = UiKit.themeColor(this, R.attr.textPrimary, 0xFF0F172A);
        int textSecondary = UiKit.themeColor(this, R.attr.textSecondary, 0xFF475569);

        styleSegmentTab(tabRecent, currentTab == TAB_RECENT, cardBg, cardStroke, textPrimary, textSecondary);
        styleSegmentTab(tabExcluded, currentTab == TAB_EXCLUDED, cardBg, cardStroke, textPrimary, textSecondary);

        if (currentTab == TAB_RECENT) {
            listSubHeader.setVisibility(recents.isEmpty() ? View.GONE : View.VISIBLE);
            tvListHint.setText(R.string.recent_hint);
            btnClearRecent.setVisibility(recents.isEmpty() ? View.GONE : View.VISIBLE);
            btnAddApp.setVisibility(View.GONE);
            recentContainer.setVisibility(View.VISIBLE);
            excludedContainer.setVisibility(View.GONE);
        } else {
            listSubHeader.setVisibility(View.VISIBLE);
            tvListHint.setText(R.string.excluded_hint);
            btnClearRecent.setVisibility(View.GONE);
            btnAddApp.setVisibility(View.VISIBLE);
            recentContainer.setVisibility(View.GONE);
            excludedContainer.setVisibility(View.VISIBLE);
        }

        StringBuilder sig = new StringBuilder();
        sig.append(currentTab).append('|').append(recentExpanded).append('|')
                .append(recents.size()).append('|').append(pkgs.hashCode()).append('|')
                .append(scenes.hashCode());
        if (!recents.isEmpty()) {
            sig.append('|').append(recents.get(0).ts);
        }
        String newSig = sig.toString();
        if (!forceRebuild && newSig.equals(lastListSignature)) {
            return;
        }
        lastListSignature = newSig;

        if (currentTab == TAB_RECENT) {
            renderRecentList(recents);
        } else {
            renderExcludedList(pkgs, scenes);
        }
    }

    private void styleSegmentTab(TextView tab, boolean selected, int cardBg, int cardStroke,
                                 int textPrimary, int textSecondary) {
        if (selected) {
            tab.setBackground(UiKit.roundedRect(
                    cardBg, UiKit.dp(this, 10), UiKit.dp(this, 0.75f), cardStroke));
            tab.setTextColor(textPrimary);
            tab.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tab.setElevation(UiKit.dp(this, 1));
        } else {
            tab.setBackgroundColor(Color.TRANSPARENT);
            tab.setTextColor(textSecondary);
            tab.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            tab.setElevation(0);
        }
    }

    private void renderRecentList(final List<SkipRules.Recent> recents) {
        recentContainer.removeAllViews();
        if (recents.isEmpty()) {
            recentContainer.addView(buildEmptyState(
                    R.drawable.ic_shield_on,
                    getString(R.string.empty_recent_title),
                    getString(R.string.empty_recent_desc)));
            return;
        }

        int limit = recentExpanded ? recents.size() : Math.min(RECENT_PREVIEW_COUNT, recents.size());
        int textPrimary = UiKit.themeColor(this, R.attr.textPrimary, 0xFF0F172A);
        int textSecondary = UiKit.themeColor(this, R.attr.textSecondary, 0xFF475569);
        int textHint = UiKit.themeColor(this, R.attr.textHint, 0xFF94A3B8);
        int accentText = UiKit.themeColor(this, R.attr.accentText, 0xFF1557D6);
        int accentSoft = UiKit.themeColor(this, R.attr.accentSoft, 0xFFEBF2FF);
        int okColor = UiKit.themeColor(this, R.attr.okColor, 0xFF0D9F6E);
        int okSoft = UiKit.themeColor(this, R.attr.okSoft, 0xFFE6F7F0);
        int subtleBg = UiKit.themeColor(this, R.attr.surfaceSubtle, 0xFFEEF2F8);
        int divider = UiKit.themeColor(this, R.attr.dividerColor, 0xFFEDF1F7);
        int ripple = UiKit.subtleRipple(this);

        for (int i = 0; i < limit; i++) {
            final SkipRules.Recent r = recents.get(i);
            final boolean alreadyExcluded = SkipRules.isExcluded(this, r.pkg, r.cls);

            if (i > 0) {
                recentContainer.addView(buildInsetDivider(divider, 66));
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(UiKit.rippleRow(ripple, UiKit.dp(this, 12)));
            row.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 11),
                    UiKit.dp(this, 14), UiKit.dp(this, 11));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.haptic(v);
                    showShieldSheet(r);
                }
            });

            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            UiKit.clipRounded(icon, UiKit.dp(this, 10));
            UiKit.loadAppIcon(this, r.pkg, icon);
            row.addView(icon, new LinearLayout.LayoutParams(
                    UiKit.dp(this, 38), UiKit.dp(this, 38)));

            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(UiKit.dp(this, 12), 0, UiKit.dp(this, 12), 0);

            // 第一行：应用名 + 相对时间
            LinearLayout topLine = new LinearLayout(this);
            topLine.setOrientation(LinearLayout.HORIZONTAL);
            topLine.setGravity(Gravity.CENTER_VERTICAL);

            TextView nameView = new TextView(this);
            nameView.setText(UiKit.getAppLabel(this, r.pkg));
            nameView.setTextSize(14.5f);
            nameView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            nameView.setTextColor(textPrimary);
            nameView.setMaxLines(1);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            topLine.addView(nameView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView timeView = new TextView(this);
            timeView.setText(UiKit.formatRelativeTime(r.ts));
            timeView.setTextSize(11);
            timeView.setTextColor(textHint);
            timeView.setPadding(UiKit.dp(this, 6), 0, 0, 0);
            topLine.addView(timeView);
            mid.addView(topLine);

            // 第二行：命中文案胶囊 + Activity 简名
            LinearLayout subLine = new LinearLayout(this);
            subLine.setOrientation(LinearLayout.HORIZONTAL);
            subLine.setGravity(Gravity.CENTER_VERTICAL);
            subLine.setPadding(0, UiKit.dp(this, 4), 0, 0);

            String hitText = (r.label == null || r.label.isEmpty() || "-".equals(r.label))
                    ? "跳过" : r.label;
            TextView hitPill = new TextView(this);
            hitPill.setText("命中「" + hitText + "」");
            hitPill.setTextSize(10.5f);
            hitPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            hitPill.setTextColor(accentText);
            hitPill.setBackground(UiKit.roundedRect(accentSoft, UiKit.dp(this, 6), 0, 0));
            hitPill.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 2),
                    UiKit.dp(this, 6), UiKit.dp(this, 2));
            hitPill.setMaxLines(1);
            hitPill.setEllipsize(TextUtils.TruncateAt.END);
            subLine.addView(hitPill);

            String simpleCls = SkipRules.simpleClass(r.cls);
            if (!"-".equals(simpleCls) && !simpleCls.isEmpty()) {
                TextView clsView = new TextView(this);
                clsView.setText(simpleCls);
                clsView.setTextSize(11);
                clsView.setTextColor(textHint);
                clsView.setMaxLines(1);
                clsView.setEllipsize(TextUtils.TruncateAt.END);
                clsView.setPadding(UiKit.dp(this, 6), 0, 0, 0);
                subLine.addView(clsView, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            mid.addView(subLine);

            row.addView(mid, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 右侧操作胶囊
            TextView actionPill = new TextView(this);
            if (alreadyExcluded) {
                actionPill.setText(R.string.btn_shielded);
                actionPill.setTextColor(okColor);
                actionPill.setBackground(UiKit.roundedRect(okSoft, UiKit.dp(this, 13), 0, 0));
            } else {
                actionPill.setText(R.string.btn_shield);
                actionPill.setTextColor(textSecondary);
                actionPill.setBackground(UiKit.roundedRect(subtleBg, UiKit.dp(this, 13), 0, 0));
            }
            actionPill.setTextSize(11.5f);
            actionPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            actionPill.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 5),
                    UiKit.dp(this, 10), UiKit.dp(this, 5));
            row.addView(actionPill);

            recentContainer.addView(row);
        }

        // 超过预览数量时显示平滑展开/收起按钮
        if (recents.size() > RECENT_PREVIEW_COUNT) {
            recentContainer.addView(buildInsetDivider(divider, 16));
            TextView more = new TextView(this);
            more.setText(recentExpanded
                    ? getString(R.string.btn_show_less)
                    : getString(R.string.btn_show_more, recents.size() - RECENT_PREVIEW_COUNT));
            more.setTextSize(12.5f);
            more.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            more.setTextColor(accentText);
            more.setGravity(Gravity.CENTER);
            more.setBackground(UiKit.rippleRow(ripple, UiKit.dp(this, 12)));
            more.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 6));
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.haptic(v);
                    recentExpanded = !recentExpanded;
                    animateCard(cardActivity);
                    refresh(true);
                }
            });
            recentContainer.addView(more);
        }
    }

    private void renderExcludedList(Set<String> pkgs, Set<String> scenes) {
        excludedContainer.removeAllViews();
        if (pkgs.isEmpty() && scenes.isEmpty()) {
            excludedContainer.addView(buildEmptyState(
                    R.drawable.ic_block,
                    getString(R.string.empty_excluded_title),
                    getString(R.string.empty_excluded_desc)));
            return;
        }

        int divider = UiKit.themeColor(this, R.attr.dividerColor, 0xFFEDF1F7);
        int accentText = UiKit.themeColor(this, R.attr.accentText, 0xFF1557D6);
        int accentSoft = UiKit.themeColor(this, R.attr.accentSoft, 0xFFEBF2FF);
        int warnColor = UiKit.themeColor(this, R.attr.warnColor, 0xFFD97706);
        int warnSoft = UiKit.themeColor(this, R.attr.warnSoft, 0xFFFEF3E2);

        List<String> sortedPkgs = new ArrayList<>(pkgs);
        Collections.sort(sortedPkgs, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return UiKit.getAppLabel(MainActivity.this, a)
                        .compareToIgnoreCase(UiKit.getAppLabel(MainActivity.this, b));
            }
        });

        List<String> sortedScenes = new ArrayList<>(scenes);
        Collections.sort(sortedScenes);

        int index = 0;
        for (final String pkg : sortedPkgs) {
            if (index++ > 0) {
                excludedContainer.addView(buildInsetDivider(divider, 66));
            }
            excludedContainer.addView(buildExcludedRow(
                    pkg, UiKit.getAppLabel(this, pkg), pkg,
                    getString(R.string.tag_app_rule), accentText, accentSoft,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            UiKit.haptic(v);
                            SkipRules.removePackage(MainActivity.this, pkg);
                            animateCard(cardActivity);
                            refresh(true);
                        }
                    }));
        }

        for (final String key : sortedScenes) {
            if (index++ > 0) {
                excludedContainer.addView(buildInsetDivider(divider, 66));
            }
            int slash = key.indexOf('/');
            String pkg = slash > 0 ? key.substring(0, slash) : key;
            String cls = slash > 0 ? key.substring(slash + 1) : "-";
            excludedContainer.addView(buildExcludedRow(
                    pkg, UiKit.getAppLabel(this, pkg), SkipRules.simpleClass(cls),
                    getString(R.string.tag_scene_rule), warnColor, warnSoft,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            UiKit.haptic(v);
                            SkipRules.removeScene(MainActivity.this, key);
                            animateCard(cardActivity);
                            refresh(true);
                        }
                    }));
        }
    }

    private View buildExcludedRow(String pkg, String titleText, String subText,
                                  String tagText, int tagFg, int tagBg,
                                  View.OnClickListener onRemove) {
        int textPrimary = UiKit.themeColor(this, R.attr.textPrimary, 0xFF0F172A);
        int textHint = UiKit.themeColor(this, R.attr.textHint, 0xFF94A3B8);
        int danger = UiKit.themeColor(this, R.attr.dangerColor, 0xFFE11D48);
        int dangerSoft = UiKit.themeColor(this, R.attr.dangerSoft, 0xFFFFEBEF);
        int ripple = UiKit.subtleRipple(this);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 11),
                UiKit.dp(this, 14), UiKit.dp(this, 11));

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        UiKit.clipRounded(icon, UiKit.dp(this, 10));
        UiKit.loadAppIcon(this, pkg, icon);
        row.addView(icon, new LinearLayout.LayoutParams(
                UiKit.dp(this, 38), UiKit.dp(this, 38)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(UiKit.dp(this, 12), 0, UiKit.dp(this, 8), 0);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView nameView = new TextView(this);
        nameView.setText(titleText);
        nameView.setTextSize(14.5f);
        nameView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nameView.setTextColor(textPrimary);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        int maxNamePx = Math.max(UiKit.dp(this, 110),
                getResources().getDisplayMetrics().widthPixels - UiKit.dp(this, 236));
        nameView.setMaxWidth(maxNamePx);
        titleRow.addView(nameView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tag = new TextView(this);
        tag.setText(tagText);
        tag.setTextSize(10);
        tag.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tag.setTextColor(tagFg);
        tag.setBackground(UiKit.roundedRect(tagBg, UiKit.dp(this, 6), 0, 0));
        tag.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 1.5f),
                UiKit.dp(this, 6), UiKit.dp(this, 1.5f));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = UiKit.dp(this, 6);
        titleRow.addView(tag, tlp);
        mid.addView(titleRow);

        TextView subView = new TextView(this);
        subView.setText(subText);
        subView.setTextSize(11.5f);
        subView.setTextColor(textHint);
        subView.setMaxLines(1);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        subView.setPadding(0, UiKit.dp(this, 2.5f), 0, 0);
        mid.addView(subView);

        row.addView(mid, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView removeBtn = new TextView(this);
        removeBtn.setText(R.string.btn_remove);
        removeBtn.setTextSize(11.5f);
        removeBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        removeBtn.setTextColor(danger);
        removeBtn.setBackground(UiKit.rippleRounded(
                dangerSoft, ripple, UiKit.dp(this, 13), 0, 0));
        removeBtn.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 5.5f),
                UiKit.dp(this, 11), UiKit.dp(this, 5.5f));
        removeBtn.setOnClickListener(onRemove);
        row.addView(removeBtn);

        return row;
    }

    private View buildEmptyState(int iconRes, String title, String desc) {
        int subtleBg = UiKit.themeColor(this, R.attr.surfaceSubtle, 0xFFEEF2F8);
        int textSecondary = UiKit.themeColor(this, R.attr.textSecondary, 0xFF475569);
        int textHint = UiKit.themeColor(this, R.attr.textHint, 0xFF94A3B8);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 28),
                UiKit.dp(this, 24), UiKit.dp(this, 24));

        FrameLayout circle = new FrameLayout(this);
        circle.setBackground(UiKit.circle(subtleBg));
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setColorFilter(textHint);
        FrameLayout.LayoutParams ivlp = new FrameLayout.LayoutParams(
                UiKit.dp(this, 22), UiKit.dp(this, 22));
        ivlp.gravity = Gravity.CENTER;
        circle.addView(iv, ivlp);
        box.addView(circle, new LinearLayout.LayoutParams(
                UiKit.dp(this, 48), UiKit.dp(this, 48)));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(14);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(textSecondary);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, UiKit.dp(this, 12), 0, 0);
        box.addView(tvTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvDesc = new TextView(this);
        tvDesc.setText(desc);
        tvDesc.setTextSize(12);
        tvDesc.setTextColor(textHint);
        tvDesc.setGravity(Gravity.CENTER);
        tvDesc.setPadding(0, UiKit.dp(this, 4), 0, 0);
        box.addView(tvDesc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private View buildInsetDivider(int color, int leftMarginDp) {
        View div = new View(this);
        div.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = UiKit.dp(this, leftMarginDp);
        lp.rightMargin = UiKit.dp(this, 16);
        div.setLayoutParams(lp);
        return div;
    }

    // ---- 底部交互面板 ----

    private void showShieldSheet(final SkipRules.Recent r) {
        int textPrimary = UiKit.themeColor(this, R.attr.textPrimary, 0xFF0F172A);
        int textSecondary = UiKit.themeColor(this, R.attr.textSecondary, 0xFF475569);
        int textHint = UiKit.themeColor(this, R.attr.textHint, 0xFF94A3B8);
        int subtleBg = UiKit.themeColor(this, R.attr.surfaceSubtle, 0xFFEEF2F8);
        int warnColor = UiKit.themeColor(this, R.attr.warnColor, 0xFFD97706);
        int warnSoft = UiKit.themeColor(this, R.attr.warnSoft, 0xFFFEF3E2);
        int accent = UiKit.themeColor(this, R.attr.accentColor, 0xFF1C6BFF);
        int accentSoft = UiKit.themeColor(this, R.attr.accentSoft, 0xFFEBF2FF);
        int ripple = UiKit.subtleRipple(this);

        final String appName = UiKit.getAppLabel(this, r.pkg);
        final String simpleCls = SkipRules.simpleClass(r.cls);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 4),
                UiKit.dp(this, 18), UiKit.dp(this, 4));

        // 顶部上下文摘要（图标 + 应用名 + 命中文案）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(UiKit.dp(this, 2), 0, UiKit.dp(this, 2), UiKit.dp(this, 16));

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        UiKit.clipRounded(icon, UiKit.dp(this, 11));
        UiKit.loadAppIcon(this, r.pkg, icon);
        head.addView(icon, new LinearLayout.LayoutParams(
                UiKit.dp(this, 42), UiKit.dp(this, 42)));

        LinearLayout headTexts = new LinearLayout(this);
        headTexts.setOrientation(LinearLayout.VERTICAL);
        headTexts.setPadding(UiKit.dp(this, 12), 0, 0, 0);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(R.string.shield_sheet_title);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(textPrimary);

        TextView tvMeta = new TextView(this);
        tvMeta.setText(appName + " · 命中「"
                + (r.label == null || r.label.isEmpty() ? "?" : r.label) + "」");
        tvMeta.setTextSize(12);
        tvMeta.setTextColor(textHint);
        tvMeta.setPadding(0, UiKit.dp(this, 2), 0, 0);

        headTexts.addView(tvTitle);
        headTexts.addView(tvMeta);
        head.addView(headTexts);
        content.addView(head);

        final Dialog dialog = UiKit.createBottomSheet(this, content);

        // 选项 1：仅屏蔽此页面
        content.addView(buildSheetOption(
                R.drawable.ic_shield_pause, warnColor, warnSoft,
                getString(R.string.shield_scene_title),
                getString(R.string.shield_scene_desc, simpleCls),
                subtleBg, textPrimary, textSecondary, ripple,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        UiKit.haptic(v);
                        SkipRules.addScene(MainActivity.this, r.pkg, r.cls);
                        dialog.dismiss();
                        refresh(true);
                        Toast.makeText(MainActivity.this,
                                "已屏蔽页面：" + simpleCls, Toast.LENGTH_SHORT).show();
                    }
                }));

        // 选项 2：屏蔽整个应用
        View appOpt = buildSheetOption(
                R.drawable.ic_block, accent, accentSoft,
                getString(R.string.shield_app_title),
                getString(R.string.shield_app_desc, appName),
                subtleBg, textPrimary, textSecondary, ripple,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        UiKit.haptic(v);
                        SkipRules.addPackage(MainActivity.this, r.pkg);
                        dialog.dismiss();
                        refresh(true);
                        Toast.makeText(MainActivity.this,
                                "已屏蔽应用：" + appName, Toast.LENGTH_SHORT).show();
                    }
                });
        LinearLayout.LayoutParams olp = (LinearLayout.LayoutParams) appOpt.getLayoutParams();
        olp.topMargin = UiKit.dp(this, 10);
        content.addView(appOpt);

        // 取消按钮
        TextView cancel = new TextView(this);
        cancel.setText(R.string.action_cancel);
        cancel.setTextSize(14);
        cancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cancel.setTextColor(textSecondary);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(UiKit.rippleRow(ripple, UiKit.dp(this, 14)));
        cancel.setPadding(0, UiKit.dp(this, 14), 0, UiKit.dp(this, 8));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiKit.dp(this, 8);
        content.addView(cancel, clp);

        dialog.show();
    }

    private View buildSheetOption(int iconRes, int iconTint, int iconBg,
                                  String title, String desc,
                                  int bgColor, int textPrimary, int textSecondary, int ripple,
                                  View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(UiKit.rippleRounded(bgColor, ripple, UiKit.dp(this, 16), 0, 0));
        box.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 14),
                UiKit.dp(this, 14), UiKit.dp(this, 14));
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setOnClickListener(onClick);

        FrameLayout icWrap = new FrameLayout(this);
        icWrap.setBackground(UiKit.roundedRect(iconBg, UiKit.dp(this, 11), 0, 0));
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setColorFilter(iconTint);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                UiKit.dp(this, 19), UiKit.dp(this, 19));
        ilp.gravity = Gravity.CENTER;
        icWrap.addView(iv, ilp);
        box.addView(icWrap, new LinearLayout.LayoutParams(
                UiKit.dp(this, 38), UiKit.dp(this, 38)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(UiKit.dp(this, 12), 0, 0, 0);

        TextView tvT = new TextView(this);
        tvT.setText(title);
        tvT.setTextSize(14.5f);
        tvT.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvT.setTextColor(textPrimary);

        TextView tvD = new TextView(this);
        tvD.setText(desc);
        tvD.setTextSize(12);
        tvD.setTextColor(textSecondary);
        tvD.setPadding(0, UiKit.dp(this, 2), 0, 0);

        texts.addView(tvT);
        texts.addView(tvD);
        box.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return box;
    }

    private void showThemeSheet() {
        final int currentMode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_THEME, THEME_AUTO);
        int textPrimary = UiKit.themeColor(this, R.attr.textPrimary, 0xFF0F172A);
        int textSecondary = UiKit.themeColor(this, R.attr.textSecondary, 0xFF475569);
        int subtleBg = UiKit.themeColor(this, R.attr.surfaceSubtle, 0xFFEEF2F8);
        int accent = UiKit.themeColor(this, R.attr.accentColor, 0xFF1C6BFF);
        int accentSoft = UiKit.themeColor(this, R.attr.accentSoft, 0xFFEBF2FF);
        int ripple = UiKit.subtleRipple(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 4),
                UiKit.dp(this, 18), UiKit.dp(this, 8));

        TextView title = new TextView(this);
        title.setText(R.string.theme_sheet_title);
        title.setTextSize(17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(textPrimary);
        title.setPadding(UiKit.dp(this, 4), 0, 0, UiKit.dp(this, 14));
        content.addView(title);

        final Dialog dialog = UiKit.createBottomSheet(this, content);

        int[] modes = {THEME_AUTO, THEME_LIGHT, THEME_DARK};
        int[] icons = {R.drawable.ic_theme_auto, R.drawable.ic_theme_light, R.drawable.ic_theme_dark};
        int[] titles = {R.string.theme_auto_full, R.string.theme_light_full, R.string.theme_dark_full};
        int[] descs = {R.string.theme_auto_desc, R.string.theme_light_desc, R.string.theme_dark_desc};

        for (int i = 0; i < modes.length; i++) {
            final int mode = modes[i];
            boolean selected = (mode == currentMode);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(UiKit.rippleRounded(
                    selected ? accentSoft : subtleBg, ripple, UiKit.dp(this, 16),
                    selected ? UiKit.dp(this, 1.2f) : 0, selected ? accent : 0));
            row.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 13),
                    UiKit.dp(this, 14), UiKit.dp(this, 13));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                rlp.topMargin = UiKit.dp(this, 10);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.haptic(v);
                    dialog.dismiss();
                    if (mode != currentMode) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putInt(KEY_THEME, mode).apply();
                        recreate();
                    }
                }
            });

            ImageView iv = new ImageView(this);
            iv.setImageResource(icons[i]);
            iv.setColorFilter(selected ? accent : textSecondary);
            row.addView(iv, new LinearLayout.LayoutParams(
                    UiKit.dp(this, 22), UiKit.dp(this, 22)));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 8), 0);

            TextView tvT = new TextView(this);
            tvT.setText(titles[i]);
            tvT.setTextSize(15);
            tvT.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tvT.setTextColor(selected ? accent : textPrimary);

            TextView tvD = new TextView(this);
            tvD.setText(descs[i]);
            tvD.setTextSize(12);
            tvD.setTextColor(textSecondary);
            tvD.setPadding(0, UiKit.dp(this, 1.5f), 0, 0);

            texts.addView(tvT);
            texts.addView(tvD);
            row.addView(texts, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (selected) {
                FrameLayout check = new FrameLayout(this);
                check.setBackground(UiKit.circle(accent));
                ImageView ck = new ImageView(this);
                ck.setImageResource(R.drawable.ic_check);
                ck.setColorFilter(Color.WHITE);
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                        UiKit.dp(this, 13), UiKit.dp(this, 13));
                clp.gravity = Gravity.CENTER;
                check.addView(ck, clp);
                row.addView(check, new LinearLayout.LayoutParams(
                        UiKit.dp(this, 22), UiKit.dp(this, 22)));
            }
            content.addView(row, rlp);
        }
        dialog.show();
    }

    private void showDiagnosticsSheet() {
        int textPrimary = UiKit.themeColor(this, R.attr.textPrimary, 0xFF0F172A);
        int textSecondary = UiKit.themeColor(this, R.attr.textSecondary, 0xFF475569);
        int textHint = UiKit.themeColor(this, R.attr.textHint, 0xFF94A3B8);
        int subtleBg = UiKit.themeColor(this, R.attr.surfaceSubtle, 0xFFEEF2F8);
        int divider = UiKit.themeColor(this, R.attr.dividerColor, 0xFFEDF1F7);
        int accent = UiKit.themeColor(this, R.attr.accentColor, 0xFF1C6BFF);
        int okColor = UiKit.themeColor(this, R.attr.okColor, 0xFF0D9F6E);
        int dangerColor = UiKit.themeColor(this, R.attr.dangerColor, 0xFFE11D48);
        int ripple = UiKit.subtleRipple(this);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        int events = sp.getInt("dbg_events", 0);
        String lastEvent = sp.getString("dbg_last", "-");
        long lastConnect = sp.getLong("last_connect", 0);
        long lastUnbind = sp.getLong("last_unbind", 0);
        String homePkg = sp.getString("home_pkg", "-");
        final String tree = sp.getString("dbg_tree", "");
        boolean connected = SkipService.isConnected();
        boolean batteryOk = isIgnoringBatteryOptimizations();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 4),
                UiKit.dp(this, 18), UiKit.dp(this, 8));

        TextView title = new TextView(this);
        title.setText(R.string.diag_title);
        title.setTextSize(17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(textPrimary);
        title.setPadding(UiKit.dp(this, 2), 0, 0, UiKit.dp(this, 12));
        content.addView(title);

        // 键值明细卡片
        LinearLayout kvCard = new LinearLayout(this);
        kvCard.setOrientation(LinearLayout.VERTICAL);
        kvCard.setBackground(UiKit.roundedRect(subtleBg, UiKit.dp(this, 16), 0, 0));
        kvCard.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 6),
                UiKit.dp(this, 14), UiKit.dp(this, 6));

        addDiagRow(kvCard, "服务连接", connected ? "已连接" : "未连接",
                connected ? okColor : dangerColor, textSecondary, false, divider);
        addDiagRow(kvCard, "省电策略", batteryOk ? "已无限制" : "受系统限制",
                batteryOk ? okColor : textPrimary, textSecondary, true, divider);
        addDiagRow(kvCard, "累计事件", events + " 次", textPrimary, textSecondary, true, divider);
        addDiagRow(kvCard, "最近事件", lastEvent, textPrimary, textSecondary, true, divider);
        addDiagRow(kvCard, "最近扫描", SkipState.lastScan, textPrimary, textSecondary, true, divider);
        addDiagRow(kvCard, "最近连接", lastConnect > 0 ? formatTime(lastConnect) : "-",
                textPrimary, textSecondary, true, divider);
        if (lastUnbind > 0) {
            addDiagRow(kvCard, "最近断开", formatTime(lastUnbind),
                    textPrimary, textSecondary, true, divider);
        }
        addDiagRow(kvCard, "主桌面包名", homePkg, textPrimary, textSecondary, true, divider);
        content.addView(kvCard);

        // 未命中窗口节点快照区
        TextView snapLabel = new TextView(this);
        snapLabel.setText("未命中窗口快照（用于适配新广告模板）");
        snapLabel.setTextSize(12);
        snapLabel.setTextColor(textHint);
        snapLabel.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 14), 0, UiKit.dp(this, 6));
        content.addView(snapLabel);

        ScrollView snapScroll = new ScrollView(this);
        snapScroll.setBackground(UiKit.roundedRect(subtleBg, UiKit.dp(this, 14), 0, 0));
        snapScroll.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10),
                UiKit.dp(this, 12), UiKit.dp(this, 10));
        TextView snapText = new TextView(this);
        snapText.setTypeface(Typeface.MONOSPACE);
        snapText.setTextSize(10.5f);
        snapText.setTextColor(textSecondary);
        snapText.setText(tree != null && !tree.isEmpty()
                ? tree : getString(R.string.diag_no_snapshot));
        snapScroll.addView(snapText);
        int snapH = UiKit.dp(this, tree != null && !tree.isEmpty() ? 140 : 52);
        content.addView(snapScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, snapH));

        final Dialog dialog = UiKit.createBottomSheet(this, content);

        if (tree != null && !tree.isEmpty()) {
            LinearLayout copyBtn = new LinearLayout(this);
            copyBtn.setOrientation(LinearLayout.HORIZONTAL);
            copyBtn.setGravity(Gravity.CENTER);
            copyBtn.setBackground(UiKit.rippleRounded(accent, 0x33FFFFFF, UiKit.dp(this, 14), 0, 0));
            copyBtn.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 12));

            ImageView copyIc = new ImageView(this);
            copyIc.setImageResource(R.drawable.ic_copy);
            copyIc.setColorFilter(Color.WHITE);
            copyBtn.addView(copyIc, new LinearLayout.LayoutParams(
                    UiKit.dp(this, 16), UiKit.dp(this, 16)));

            TextView copyTv = new TextView(this);
            copyTv.setText(R.string.diag_copy_snapshot);
            copyTv.setTextSize(14);
            copyTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            copyTv.setTextColor(Color.WHITE);
            copyTv.setPadding(UiKit.dp(this, 7), 0, 0, 0);
            copyBtn.addView(copyTv);

            copyBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.haptic(v);
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("autoskip_snapshot", tree));
                            Toast.makeText(MainActivity.this,
                                    R.string.diag_copied, Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable ignored) {
                    }
                    dialog.dismiss();
                }
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = UiKit.dp(this, 12);
            content.addView(copyBtn, blp);
        } else {
            TextView close = new TextView(this);
            close.setText(R.string.action_done);
            close.setTextSize(14);
            close.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            close.setTextColor(accent);
            close.setGravity(Gravity.CENTER);
            close.setBackground(UiKit.rippleRow(ripple, UiKit.dp(this, 12)));
            close.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 4));
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = UiKit.dp(this, 8);
            content.addView(close, clp);
        }

        dialog.show();
    }

    private void addDiagRow(LinearLayout parent, String key, String val,
                            int valColor, int keyColor, boolean withDivider, int divColor) {
        if (withDivider) {
            View div = new View(this);
            div.setBackgroundColor(divColor);
            parent.addView(div, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 9), 0, UiKit.dp(this, 9));

        TextView k = new TextView(this);
        k.setText(key);
        k.setTextSize(12.5f);
        k.setTextColor(keyColor);
        row.addView(k, new LinearLayout.LayoutParams(
                UiKit.dp(this, 86), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView v = new TextView(this);
        v.setText(val == null ? "-" : val);
        v.setTextSize(12.5f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setTextColor(valColor);
        v.setGravity(Gravity.END);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(v, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
    }

    private void showInfoSheet() {
        int textPrimary = UiKit.themeColor(this, R.attr.textPrimary, 0xFF0F172A);
        int textSecondary = UiKit.themeColor(this, R.attr.textSecondary, 0xFF475569);
        int subtleBg = UiKit.themeColor(this, R.attr.surfaceSubtle, 0xFFEEF2F8);
        int accent = UiKit.themeColor(this, R.attr.accentColor, 0xFF1C6BFF);
        int accentSoft = UiKit.themeColor(this, R.attr.accentSoft, 0xFFEBF2FF);
        int okColor = UiKit.themeColor(this, R.attr.okColor, 0xFF0D9F6E);
        int okSoft = UiKit.themeColor(this, R.attr.okSoft, 0xFFE6F7F0);
        int warnColor = UiKit.themeColor(this, R.attr.warnColor, 0xFFD97706);
        int warnSoft = UiKit.themeColor(this, R.attr.warnSoft, 0xFFFEF3E2);
        int ripple = UiKit.subtleRipple(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 4),
                UiKit.dp(this, 18), UiKit.dp(this, 8));

        TextView title = new TextView(this);
        title.setText(R.string.info_title);
        title.setTextSize(17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(textPrimary);
        title.setPadding(UiKit.dp(this, 2), 0, 0, UiKit.dp(this, 14));
        content.addView(title);

        content.addView(buildInfoCard(R.drawable.ic_shield_on, accent, accentSoft,
                "前台会话精准识别",
                "仅在应用进入前台后的 15 秒窗口内扫描当前及浮层控件，识别“跳过/关闭广告”并点击，命中或超时后立即停扫。",
                subtleBg, textPrimary, textSecondary, false));
        content.addView(buildInfoCard(R.drawable.ic_battery, warnColor, warnSoft,
                "轻量省电，暂停即零开销",
                "只订阅 3 类窗口事件、100ms 合并与点击防抖，息屏自动休眠；暂停开关后系统不再派发事件。",
                subtleBg, textPrimary, textSecondary, true));
        content.addView(buildInfoCard(R.drawable.ic_lock, okColor, okSoft,
                "纯本地运行，零联网权限",
                "安装包不申请 INTERNET 网络权限，内置屏蔽微信小程序等常见场景，所有计数和规则仅保存在本机。",
                subtleBg, textPrimary, textSecondary, true));

        final Dialog dialog = UiKit.createBottomSheet(this, content);

        TextView done = new TextView(this);
        done.setText(R.string.action_done);
        done.setTextSize(14);
        done.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        done.setTextColor(accent);
        done.setGravity(Gravity.CENTER);
        done.setBackground(UiKit.rippleRow(ripple, UiKit.dp(this, 12)));
        done.setPadding(0, UiKit.dp(this, 14), 0, UiKit.dp(this, 4));
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = UiKit.dp(this, 6);
        content.addView(done, dlp);

        dialog.show();
    }

    private View buildInfoCard(int iconRes, int iconTint, int iconBg,
                               String heading, String body,
                               int bgColor, int textPrimary, int textSecondary, boolean topMargin) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.TOP);
        card.setBackground(UiKit.roundedRect(bgColor, UiKit.dp(this, 16), 0, 0));
        card.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 14),
                UiKit.dp(this, 14), UiKit.dp(this, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (topMargin) {
            lp.topMargin = UiKit.dp(this, 10);
        }
        card.setLayoutParams(lp);

        FrameLayout icWrap = new FrameLayout(this);
        icWrap.setBackground(UiKit.roundedRect(iconBg, UiKit.dp(this, 11), 0, 0));
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setColorFilter(iconTint);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                UiKit.dp(this, 18), UiKit.dp(this, 18));
        ilp.gravity = Gravity.CENTER;
        icWrap.addView(iv, ilp);
        card.addView(icWrap, new LinearLayout.LayoutParams(
                UiKit.dp(this, 36), UiKit.dp(this, 36)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(UiKit.dp(this, 12), 0, 0, 0);

        TextView tvH = new TextView(this);
        tvH.setText(heading);
        tvH.setTextSize(14);
        tvH.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvH.setTextColor(textPrimary);

        TextView tvB = new TextView(this);
        tvB.setText(body);
        tvB.setTextSize(12);
        tvB.setLineSpacing(UiKit.dp(this, 2), 1f);
        tvB.setTextColor(textSecondary);
        tvB.setPadding(0, UiKit.dp(this, 3), 0, 0);

        texts.addView(tvH);
        texts.addView(tvB);
        card.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return card;
    }

    private void confirmClearRecent() {
        int textPrimary = UiKit.themeColor(this, R.attr.textPrimary, 0xFF0F172A);
        int textSecondary = UiKit.themeColor(this, R.attr.textSecondary, 0xFF475569);
        int subtleBg = UiKit.themeColor(this, R.attr.surfaceSubtle, 0xFFEEF2F8);
        int danger = UiKit.themeColor(this, R.attr.dangerColor, 0xFFE11D48);
        int dangerSoft = UiKit.themeColor(this, R.attr.dangerSoft, 0xFFFFEBEF);
        int ripple = UiKit.subtleRipple(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 8),
                UiKit.dp(this, 20), UiKit.dp(this, 8));

        FrameLayout icWrap = new FrameLayout(this);
        icWrap.setBackground(UiKit.circle(dangerSoft));
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_trash);
        iv.setColorFilter(danger);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                UiKit.dp(this, 22), UiKit.dp(this, 22));
        ilp.gravity = Gravity.CENTER;
        icWrap.addView(iv, ilp);
        content.addView(icWrap, new LinearLayout.LayoutParams(
                UiKit.dp(this, 48), UiKit.dp(this, 48)));

        TextView title = new TextView(this);
        title.setText(R.string.clear_recent_title);
        title.setTextSize(17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(textPrimary);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, UiKit.dp(this, 14), 0, 0);
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView msg = new TextView(this);
        msg.setText(R.string.clear_recent_msg);
        msg.setTextSize(13);
        msg.setTextColor(textSecondary);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 6),
                UiKit.dp(this, 8), UiKit.dp(this, 18));
        content.addView(msg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Dialog dialog = UiKit.createBottomSheet(this, content);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        TextView cancel = new TextView(this);
        cancel.setText(R.string.action_cancel);
        cancel.setTextSize(14);
        cancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        cancel.setTextColor(textSecondary);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(UiKit.rippleRounded(subtleBg, ripple, UiKit.dp(this, 14), 0, 0));
        cancel.setPadding(0, UiKit.dp(this, 13), 0, UiKit.dp(this, 13));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = UiKit.dp(this, 6);
        btns.addView(cancel, clp);

        TextView ok = new TextView(this);
        ok.setText(R.string.btn_clear);
        ok.setTextSize(14);
        ok.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        ok.setTextColor(Color.WHITE);
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(UiKit.rippleRounded(danger, 0x33FFFFFF, UiKit.dp(this, 14), 0, 0));
        ok.setPadding(0, UiKit.dp(this, 13), 0, UiKit.dp(this, 13));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.haptic(v);
                dialog.dismiss();
                SkipRules.clearRecent(MainActivity.this);
                recentExpanded = false;
                animateCard(cardActivity);
                refresh(true);
            }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        olp.leftMargin = UiKit.dp(this, 6);
        btns.addView(ok, olp);

        content.addView(btns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.show();
    }

    // ---- 主题与系统设置跳转（完整沿用原有权限逻辑） ----

    private void applyTheme() {
        int mode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_THEME, THEME_AUTO);
        if (mode == THEME_LIGHT) {
            setTheme(R.style.AppThemeLight);
        } else if (mode == THEME_DARK) {
            setTheme(R.style.AppThemeDark);
        } else {
            setTheme(R.style.AppTheme);
        }
    }

    private void updateThemeButton() {
        int mode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_THEME, THEME_AUTO);
        if (mode == THEME_LIGHT) {
            ivThemeIcon.setImageResource(R.drawable.ic_theme_light);
            tvThemeLabel.setText(R.string.theme_light);
        } else if (mode == THEME_DARK) {
            ivThemeIcon.setImageResource(R.drawable.ic_theme_dark);
            tvThemeLabel.setText(R.string.theme_dark);
        } else {
            ivThemeIcon.setImageResource(R.drawable.ic_theme_auto);
            tvThemeLabel.setText(R.string.theme_auto);
        }
    }

    private void requestNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
            }
        } catch (Throwable ignored) {
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    private void requestBatteryExemption() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    private void openAutostartSettings() {
        try {
            Intent i = new Intent("miui.intent.action.OP_AUTO_START");
            i.addCategory(Intent.CATEGORY_DEFAULT);
            i.setPackage("com.miui.securitycenter");
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent();
            i.setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity");
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {
        }
    }

    private void openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {
        }
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.isEmpty()) {
            return false;
        }
        return enabled.contains(getPackageName() + "/" + SkipService.class.getName())
                || enabled.contains(getPackageName() + "/.SkipService");
    }

    private boolean isIgnoringBatteryOptimizations() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean areNotificationsEnabledCompat() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && !nm.areNotificationsEnabled()) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= 33) {
                return checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isXiaomiDevice() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        String b = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(Locale.ROOT);
        return m.contains("xiaomi") || b.contains("xiaomi") || b.contains("redmi") || b.contains("poco");
    }

    private String formatTime(long millis) {
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        return fmt.format(new Date(millis));
    }
}
