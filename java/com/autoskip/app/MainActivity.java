package com.autoskip.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int THEME_AUTO = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_DARK = 2;
    private static final String PREFS = "auto_skip";
    private static final String KEY_THEME = "ui_theme_mode";

    private TextView statusView;
    private TextView counterView;
    private TextView debugView;
    private Switch skipSwitch;
    private Switch guardSwitch;
    private TextView hintGuard;
    private boolean bindingSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SkipState.load(this);

        statusView = findViewById(R.id.status);
        counterView = findViewById(R.id.counter);
        debugView = findViewById(R.id.debug);
        skipSwitch = findViewById(R.id.switch_skip);
        guardSwitch = findViewById(R.id.switch_guard);
        hintGuard = findViewById(R.id.hint_guard);

        skipSwitch.setChecked(SkipState.isEnabled());
        skipSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingSwitch) {
                    return;
                }
                SkipState.set(MainActivity.this, isChecked);
                sendBroadcast(new Intent(SkipService.ACTION_STATE_CHANGED)
                        .setPackage(getPackageName()));
                refresh();
            }
        });

        guardSwitch.setChecked(SkipState.isGuardEnabled(this));
        guardSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingSwitch) {
                    return;
                }
                SkipState.setGuardEnabled(MainActivity.this, isChecked);
                if (isChecked) {
                    GuardService.start(MainActivity.this);
                } else {
                    GuardService.stop(MainActivity.this);
                }
                updateGuardHint();
            }
        });

        setupThemeBar();

        Button open = findViewById(R.id.btn_open);
        open.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable ignored) {
                }
            }
        });

        Button battery = findViewById(R.id.btn_battery);
        battery.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                requestBatteryExemption();
            }
        });

        Button addApp = findViewById(R.id.btn_add_app);
        addApp.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                showAddAppDialog();
            }
        });

        Button autostart = findViewById(R.id.btn_autostart);
        autostart.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                openAutostartSettings();
            }
        });

        requestNotificationPermission();
        if (SkipState.isGuardEnabled(this)) {
            GuardService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SkipState.load(this);
        refresh();
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

    private void setupThemeBar() {
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int mode = THEME_AUTO;
                if (v.getId() == R.id.btn_theme_light) {
                    mode = THEME_LIGHT;
                } else if (v.getId() == R.id.btn_theme_dark) {
                    mode = THEME_DARK;
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt(KEY_THEME, mode).apply();
                recreate();
            }
        };
        findViewById(R.id.btn_theme_light).setOnClickListener(listener);
        findViewById(R.id.btn_theme_dark).setOnClickListener(listener);
        findViewById(R.id.btn_theme_auto).setOnClickListener(listener);
    }

    private void updateThemeBar() {
        int mode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_THEME, THEME_AUTO);
        int selectedBg = themeColor(R.attr.barSelected, 0xFFDFF3F0);
        int normalText = themeColor(R.attr.barText, 0xFF444444);
        int selectedText = themeColor(R.attr.barTextSelected, 0xFF00695C);

        LinearLayout bar = findViewById(R.id.theme_bar);
        bar.setBackgroundColor(Color.TRANSPARENT);

        styleThemeButton(findViewById(R.id.btn_theme_light), mode == THEME_LIGHT,
                selectedBg, selectedText, normalText);
        styleThemeButton(findViewById(R.id.btn_theme_dark), mode == THEME_DARK,
                selectedBg, selectedText, normalText);
        styleThemeButton(findViewById(R.id.btn_theme_auto), mode == THEME_AUTO,
                selectedBg, selectedText, normalText);
    }

    private void styleThemeButton(Button button, boolean selected,
                                  int selectedBg, int selectedText, int normalText) {
        button.setBackgroundColor(selected ? selectedBg : Color.TRANSPARENT);
        button.setTextColor(selected ? selectedText : normalText);
        button.setSelected(selected);
    }

    private int themeColor(int attr, int fallback) {
        try {
            TypedValue tv = new TypedValue();
            if (getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.resourceId != 0) {
                    return getColor(tv.resourceId);
                }
                return tv.data;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 「更多通知」兜底说明只在常驻通知开启时显示。 */
    private void updateGuardHint() {
        if (hintGuard != null) {
            hintGuard.setVisibility(SkipState.isGuardEnabled(this) ? View.VISIBLE : View.GONE);
        }
    }

    private void refresh() {
        boolean serviceEnabled = isServiceEnabled();
        boolean skipEnabled = SkipState.isEnabled();

        if (!serviceEnabled) {
            statusView.setText(R.string.status_off);
        } else if (!skipEnabled) {
            statusView.setText(R.string.status_paused);
        } else {
            statusView.setText(R.string.status_on);
        }

        bindingSwitch = true;
        skipSwitch.setChecked(skipEnabled);
        guardSwitch.setChecked(SkipState.isGuardEnabled(this));
        bindingSwitch = false;
        updateGuardHint();

        SharedPreferences sp = getSharedPreferences("auto_skip", MODE_PRIVATE);
        int count = sp.getInt("skip_count", 0);
        String last = sp.getString("last_package", null);
        if (count <= 0 || last == null) {
            counterView.setText(R.string.counter_zero);
        } else {
            counterView.setText(getString(R.string.counter_format, count, last));
        }

        int events = sp.getInt("dbg_events", 0);
        String lastEvent = sp.getString("dbg_last", "-");
        long lastConnect = sp.getLong("last_connect", 0);
        long lastUnbind = sp.getLong("last_unbind", 0);

        StringBuilder debugText = new StringBuilder();
        debugText.append("诊断：事件 ").append(events).append(" 次 · 最近 ").append(lastEvent);
        debugText.append("\n服务连接：")
                .append(SkipService.isConnected() ? "已连接" : "未连接")
                .append(lastConnect > 0 ? "（最近连接 " + formatTime(lastConnect) + "）" : "")
                .append(lastUnbind > 0 ? "（最近断开 " + formatTime(lastUnbind) + "）" : "");
        debugText.append("\n最近扫描：").append(SkipState.lastScan);
        String tree = sp.getString("dbg_tree", "");
        if (tree != null && !tree.isEmpty()) {
            int max = 500;
            String shown = tree.length() > max ? tree.substring(0, max) + "…" : tree;
            debugText.append("\n未命中快照：\n").append(shown);
        }
        debugView.setText(debugText.toString());
        updateThemeBar();
        refreshRulesUi();
    }

    // ---- 不跳过名单 / 最近跳过 ----

    private void refreshRulesUi() {
        try {
            LinearLayout excluded = findViewById(R.id.excluded_list);
            excluded.removeAllViews();
            Set<String> pkgs = SkipRules.getPackages(this);
            Set<String> scenes = SkipRules.getScenes(this);
            if (pkgs.isEmpty() && scenes.isEmpty()) {
                excluded.addView(emptyText(R.string.empty_excluded));
            }
            List<String> sortedPkgs = new ArrayList<>(pkgs);
            Collections.sort(sortedPkgs);
            for (final String pkg : sortedPkgs) {
                excluded.addView(ruleRow(appLabel(pkg) + "\n" + pkg, getString(R.string.btn_remove),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                SkipRules.removePackage(MainActivity.this, pkg);
                                refreshRulesUi();
                            }
                        }));
            }
            List<String> sortedScenes = new ArrayList<>(scenes);
            Collections.sort(sortedScenes);
            for (final String key : sortedScenes) {
                int idx = key.indexOf('/');
                String pkg = idx > 0 ? key.substring(0, idx) : key;
                String cls = idx > 0 ? key.substring(idx + 1) : "-";
                excluded.addView(ruleRow(appLabel(pkg) + " · " + SkipRules.simpleClass(cls) + "\n" + key,
                        getString(R.string.btn_remove), new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                SkipRules.removeScene(MainActivity.this, key);
                                refreshRulesUi();
                            }
                        }));
            }

            LinearLayout recent = findViewById(R.id.recent_list);
            recent.removeAllViews();
            List<SkipRules.Recent> recents = SkipRules.getRecent(this);
            if (recents.isEmpty()) {
                recent.addView(emptyText(R.string.empty_recent));
                return;
            }
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
            int shown = 0;
            for (final SkipRules.Recent r : recents) {
                if (shown++ >= 10) {
                    break;
                }
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(0, 10, 0, 10);

                TextView text = new TextView(this);
                text.setText(fmt.format(new Date(r.ts)) + "  " + appLabel(r.pkg)
                        + "\n命中：" + (r.label == null || r.label.isEmpty() ? "?" : r.label));
                text.setTextSize(13);
                box.addView(text);

                LinearLayout buttons = new LinearLayout(this);
                buttons.setOrientation(LinearLayout.HORIZONTAL);
                buttons.setPadding(0, 6, 0, 0);

                Button scene = smallButton(getString(R.string.btn_block_scene));
                scene.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        SkipRules.addScene(MainActivity.this, r.pkg, r.cls);
                        refreshRulesUi();
                    }
                });
                Button app = smallButton(getString(R.string.btn_block_app));
                app.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        SkipRules.addPackage(MainActivity.this, r.pkg);
                        refreshRulesUi();
                    }
                });
                buttons.addView(scene);
                buttons.addView(app);
                box.addView(buttons);
                recent.addView(box);
            }
        } catch (Throwable ignored) {
        }
    }

    private TextView emptyText(int resId) {
        TextView t = new TextView(this);
        t.setText(resId);
        t.setTextSize(12);
        t.setTextColor(themeColor(R.attr.textHint, 0xFF888888));
        t.setPadding(0, 6, 0, 6);
        return t;
    }

    private LinearLayout ruleRow(String text, String buttonText, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 8, 0, 8);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        Button btn = new Button(this);
        btn.setText(buttonText);
        btn.setTextSize(12);
        btn.setPadding(16, 0, 16, 0);
        btn.setOnClickListener(listener);
        row.addView(btn);
        return row;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setPadding(12, 0, 12, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 8, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private void showAddAppDialog() {
        try {
            final PackageManager pm = getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(i, 0);
            final List<ResolveInfo> items = new ArrayList<>();
            final Set<String> excluded = SkipRules.getPackages(this);
            for (ResolveInfo ri : infos) {
                if (ri == null || ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || excluded.contains(pkg)) {
                    continue;
                }
                items.add(ri);
            }
            Collections.sort(items, new Comparator<ResolveInfo>() {
                @Override
                public int compare(ResolveInfo a, ResolveInfo b) {
                    String la = pm.getApplicationLabel(a.activityInfo.applicationInfo).toString();
                    String lb = pm.getApplicationLabel(b.activityInfo.applicationInfo).toString();
                    return la.compareToIgnoreCase(lb);
                }
            });
            final AppListAdapter adapter = new AppListAdapter(items);
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_pick_app)
                    .setAdapter(adapter, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            ResolveInfo ri = items.get(which);
                            SkipRules.addPackage(MainActivity.this, ri.activityInfo.packageName);
                            refreshRulesUi();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.show();
            try {
                Window window = dialog.getWindow();
                if (window != null) {
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(themeColor(R.attr.appBg, 0xFFFFFFFF));
                    bg.setCornerRadius(dp(16));
                    window.setBackgroundDrawable(bg);
                    window.setDimAmount(0.45f);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 应用选择列表：图标 + 应用名 + 包名，每行底部一条分割线。 */
    private class AppListAdapter extends android.widget.BaseAdapter {
        private final List<ResolveInfo> items;
        private final PackageManager pm;
        private final int nameColor;
        private final int pkgColor;

        AppListAdapter(List<ResolveInfo> items) {
            this.items = items;
            this.pm = getPackageManager();
            this.nameColor = themeColor(android.R.attr.textColorPrimary, 0xFF212121);
            this.pkgColor = themeColor(R.attr.textHint, 0xFF888888);
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));

            ResolveInfo ri = items.get(position);
            ImageView icon = new ImageView(MainActivity.this);
            try {
                icon.setImageDrawable(ri.activityInfo.loadIcon(pm));
            } catch (Throwable ignored) {
            }
            row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(14), 0, 0, 0);

            TextView name = new TextView(MainActivity.this);
            name.setText(pm.getApplicationLabel(ri.activityInfo.applicationInfo));
            name.setTextSize(16);
            name.setTextColor(nameColor);
            name.setMaxLines(1);

            TextView pkg = new TextView(MainActivity.this);
            pkg.setText(ri.activityInfo.packageName);
            pkg.setTextSize(12);
            pkg.setTextColor(pkgColor);
            pkg.setMaxLines(1);

            texts.addView(name);
            texts.addView(pkg);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(row);

            View divider = new View(MainActivity.this);
            divider.setBackgroundColor(0x33888888);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
            dlp.leftMargin = dp(70);
            dlp.rightMargin = dp(16);
            root.addView(divider, dlp);
            return root;
        }
    }

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private String formatTime(long millis) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss");
        return fmt.format(new java.util.Date(millis));
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
}
