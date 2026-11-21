package com.autoskip.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 可搜索异步底部应用选择器：
 *  - 后台加载可启动应用，不阻塞主线程；
 *  - 实时按应用名 / 包名筛选；
 *  - 支持连续点击多选切换不跳过状态，实时持久化并回调主界面刷新。
 */
final class AppPickerSheet {

    interface OnChangedListener {
        void onRulesChanged();
    }

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final class AppItem {
        final String pkg;
        final String label;
        final String lowerLabel;
        final String lowerPkg;

        AppItem(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
            this.lowerLabel = label.toLowerCase(Locale.ROOT);
            this.lowerPkg = pkg.toLowerCase(Locale.ROOT);
        }
    }

    private AppPickerSheet() {
    }

    static void show(final Activity activity, final OnChangedListener listener) {
        final int textPrimary = UiKit.themeColor(activity, R.attr.textPrimary, 0xFF0F172A);
        final int textSecondary = UiKit.themeColor(activity, R.attr.textSecondary, 0xFF475569);
        final int textHint = UiKit.themeColor(activity, R.attr.textHint, 0xFF94A3B8);
        final int subtleBg = UiKit.themeColor(activity, R.attr.surfaceSubtle, 0xFFEEF2F8);
        final int accent = UiKit.themeColor(activity, R.attr.accentColor, 0xFF1C6BFF);
        final int accentSoft = UiKit.themeColor(activity, R.attr.accentSoft, 0xFFEBF2FF);
        final int divider = UiKit.themeColor(activity, R.attr.dividerColor, 0xFFEDF1F7);
        final int ripple = UiKit.subtleRipple(activity);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        // 标题行：标题 + 数量 + 完成按钮
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(UiKit.dp(activity, 20), UiKit.dp(activity, 2),
                UiKit.dp(activity, 16), UiKit.dp(activity, 12));

        TextView title = new TextView(activity);
        title.setText(R.string.picker_title);
        title.setTextSize(17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(textPrimary);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView doneBtn = new TextView(activity);
        doneBtn.setText(R.string.action_done);
        doneBtn.setTextSize(13);
        doneBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        doneBtn.setTextColor(accent);
        doneBtn.setBackground(UiKit.rippleRounded(accentSoft, ripple, UiKit.dp(activity, 16), 0, 0));
        doneBtn.setPadding(UiKit.dp(activity, 14), UiKit.dp(activity, 6),
                UiKit.dp(activity, 14), UiKit.dp(activity, 6));
        header.addView(doneBtn);
        root.addView(header);

        // 搜索框容器
        LinearLayout searchBox = new LinearLayout(activity);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(UiKit.roundedRect(subtleBg, UiKit.dp(activity, 14), 0, 0));
        searchBox.setPadding(UiKit.dp(activity, 12), 0, UiKit.dp(activity, 8), 0);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(activity, 44));
        sblp.leftMargin = UiKit.dp(activity, 16);
        sblp.rightMargin = UiKit.dp(activity, 16);
        sblp.bottomMargin = UiKit.dp(activity, 10);

        ImageView searchIcon = new ImageView(activity);
        searchIcon.setImageResource(R.drawable.ic_search);
        searchIcon.setColorFilter(textHint);
        searchBox.addView(searchIcon, new LinearLayout.LayoutParams(
                UiKit.dp(activity, 18), UiKit.dp(activity, 18)));

        final EditText searchInput = new EditText(activity);
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setHint(R.string.picker_search_hint);
        searchInput.setHintTextColor(textHint);
        searchInput.setTextColor(textPrimary);
        searchInput.setTextSize(14);
        searchInput.setSingleLine(true);
        searchInput.setPadding(UiKit.dp(activity, 10), 0, UiKit.dp(activity, 8), 0);
        searchBox.addView(searchInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        final ImageView clearBtn = new ImageView(activity);
        clearBtn.setImageResource(R.drawable.ic_close);
        clearBtn.setColorFilter(textHint);
        clearBtn.setPadding(UiKit.dp(activity, 6), UiKit.dp(activity, 6),
                UiKit.dp(activity, 6), UiKit.dp(activity, 6));
        clearBtn.setVisibility(View.GONE);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchInput.setText("");
            }
        });
        searchBox.addView(clearBtn, new LinearLayout.LayoutParams(
                UiKit.dp(activity, 30), UiKit.dp(activity, 30)));
        root.addView(searchBox, sblp);

        // 内容区：固定舒适高度（约 56% 屏高），承载加载态 / 列表 / 空搜索态
        int listHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.56f);
        FrameLayout body = new FrameLayout(activity);
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listHeight));

        final ListView listView = new ListView(activity);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setSelector(android.R.color.transparent);
        listView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        listView.setVisibility(View.GONE);
        body.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 加载中指示
        final LinearLayout loadingBox = new LinearLayout(activity);
        loadingBox.setOrientation(LinearLayout.VERTICAL);
        loadingBox.setGravity(Gravity.CENTER);
        ProgressBar pb = new ProgressBar(activity);
        loadingBox.addView(pb, new LinearLayout.LayoutParams(
                UiKit.dp(activity, 28), UiKit.dp(activity, 28)));
        TextView loadingText = new TextView(activity);
        loadingText.setText(R.string.picker_loading);
        loadingText.setTextColor(textHint);
        loadingText.setTextSize(13);
        loadingText.setPadding(0, UiKit.dp(activity, 10), 0, 0);
        loadingBox.addView(loadingText);
        body.addView(loadingBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 空搜索提示
        final TextView emptyView = new TextView(activity);
        emptyView.setText(R.string.picker_empty);
        emptyView.setTextColor(textHint);
        emptyView.setTextSize(13);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        body.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final Dialog dialog = UiKit.createBottomSheet(activity, root);
        // 避免弹出软键盘时顶掉布局体验
        Window win = dialog.getWindow();
        if (win != null) {
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        doneBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        final List<AppItem> allApps = new ArrayList<>();
        final List<AppItem> filtered = new ArrayList<>();
        final Set<String> excludedSet = new HashSet<>(SkipRules.getPackages(activity));

        final PickerAdapter adapter = new PickerAdapter(
                activity, filtered, excludedSet,
                textPrimary, textSecondary, textHint, accent, subtleBg, divider, ripple,
                new PickerAdapter.OnToggleListener() {
                    @Override
                    public void onToggle(AppItem item) {
                        if (excludedSet.contains(item.pkg)) {
                            excludedSet.remove(item.pkg);
                            SkipRules.removePackage(activity, item.pkg);
                        } else {
                            excludedSet.add(item.pkg);
                            SkipRules.addPackage(activity, item.pkg);
                        }
                        if (listener != null) {
                            listener.onRulesChanged();
                        }
                    }
                });
        listView.setAdapter(adapter);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                clearBtn.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                applyFilter(allApps, filtered, q, adapter, listView, emptyView, loadingBox);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        dialog.show();

        // 后台读取所有可启动应用，保持弹窗顺滑展开
        final String selfPkg = activity.getPackageName();
        LOADER.execute(new Runnable() {
            @Override
            public void run() {
                final List<AppItem> loaded = new ArrayList<>();
                try {
                    PackageManager pm = activity.getPackageManager();
                    Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                    List<ResolveInfo> infos = pm.queryIntentActivities(intent, 0);
                    Set<String> seen = new HashSet<>();
                    for (ResolveInfo ri : infos) {
                        if (ri == null || ri.activityInfo == null) {
                            continue;
                        }
                        String pkg = ri.activityInfo.packageName;
                        if (pkg == null || pkg.equals(selfPkg) || !seen.add(pkg)) {
                            continue;
                        }
                        CharSequence cs = pm.getApplicationLabel(ri.activityInfo.applicationInfo);
                        String label = cs == null ? pkg : cs.toString();
                        UiKit.putCachedLabel(pkg, label);
                        loaded.add(new AppItem(pkg, label));
                    }
                    // 已勾选的应用置顶，其余按名称排序，方便管理
                    Collections.sort(loaded, new Comparator<AppItem>() {
                        @Override
                        public int compare(AppItem a, AppItem b) {
                            boolean ea = excludedSet.contains(a.pkg);
                            boolean eb = excludedSet.contains(b.pkg);
                            if (ea != eb) {
                                return ea ? -1 : 1;
                            }
                            return a.label.compareToIgnoreCase(b.label);
                        }
                    });
                } catch (Throwable ignored) {
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!dialog.isShowing()) {
                            return;
                        }
                        allApps.clear();
                        allApps.addAll(loaded);
                        loadingBox.setVisibility(View.GONE);
                        String q = searchInput.getText() == null
                                ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
                        applyFilter(allApps, filtered, q, adapter, listView, emptyView, loadingBox);
                    }
                });
            }
        });
    }

    private static void applyFilter(List<AppItem> all, List<AppItem> filtered, String query,
                                    PickerAdapter adapter, ListView listView,
                                    TextView emptyView, View loadingBox) {
        if (loadingBox.getVisibility() == View.VISIBLE) {
            return;
        }
        filtered.clear();
        if (query.isEmpty()) {
            filtered.addAll(all);
        } else {
            for (AppItem item : all) {
                if (item.lowerLabel.contains(query) || item.lowerPkg.contains(query)) {
                    filtered.add(item);
                }
            }
        }
        adapter.notifyDataSetChanged();
        boolean empty = filtered.isEmpty();
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private static final class PickerAdapter extends BaseAdapter {

        interface OnToggleListener {
            void onToggle(AppItem item);
        }

        private final Activity activity;
        private final List<AppItem> items;
        private final Set<String> excluded;
        private final int textPrimary;
        private final int textSecondary;
        private final int textHint;
        private final int accent;
        private final int subtleBg;
        private final int dividerColor;
        private final int ripple;
        private final OnToggleListener listener;

        PickerAdapter(Activity activity, List<AppItem> items, Set<String> excluded,
                      int textPrimary, int textSecondary, int textHint,
                      int accent, int subtleBg, int dividerColor, int ripple,
                      OnToggleListener listener) {
            this.activity = activity;
            this.items = items;
            this.excluded = excluded;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.textHint = textHint;
            this.accent = accent;
            this.subtleBg = subtleBg;
            this.dividerColor = dividerColor;
            this.ripple = ripple;
            this.listener = listener;
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

        private static final class Holder {
            ImageView icon;
            TextView name;
            TextView pkg;
            FrameLayout checkCircle;
            ImageView checkIcon;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                LinearLayout wrap = new LinearLayout(activity);
                wrap.setOrientation(LinearLayout.VERTICAL);

                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(UiKit.rippleRow(ripple, 0));
                row.setPadding(UiKit.dp(activity, 18), UiKit.dp(activity, 10),
                        UiKit.dp(activity, 18), UiKit.dp(activity, 10));

                ImageView icon = new ImageView(activity);
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                UiKit.clipRounded(icon, UiKit.dp(activity, 10));
                row.addView(icon, new LinearLayout.LayoutParams(
                        UiKit.dp(activity, 40), UiKit.dp(activity, 40)));

                LinearLayout texts = new LinearLayout(activity);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(UiKit.dp(activity, 12), 0, UiKit.dp(activity, 10), 0);

                TextView name = new TextView(activity);
                name.setTextSize(15);
                name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                name.setTextColor(textPrimary);
                name.setMaxLines(1);
                name.setEllipsize(TextUtils.TruncateAt.END);

                TextView pkg = new TextView(activity);
                pkg.setTextSize(11.5f);
                pkg.setTextColor(textHint);
                pkg.setMaxLines(1);
                pkg.setEllipsize(TextUtils.TruncateAt.END);

                texts.addView(name);
                texts.addView(pkg);
                row.addView(texts, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                FrameLayout checkCircle = new FrameLayout(activity);
                ImageView checkIcon = new ImageView(activity);
                checkIcon.setImageResource(R.drawable.ic_check);
                checkIcon.setColorFilter(Color.WHITE);
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                        UiKit.dp(activity, 14), UiKit.dp(activity, 14));
                clp.gravity = Gravity.CENTER;
                checkCircle.addView(checkIcon, clp);
                row.addView(checkCircle, new LinearLayout.LayoutParams(
                        UiKit.dp(activity, 24), UiKit.dp(activity, 24)));

                wrap.addView(row);

                View div = new View(activity);
                div.setBackgroundColor(dividerColor);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                dlp.leftMargin = UiKit.dp(activity, 70);
                dlp.rightMargin = UiKit.dp(activity, 18);
                wrap.addView(div, dlp);

                h = new Holder();
                h.icon = icon;
                h.name = name;
                h.pkg = pkg;
                h.checkCircle = checkCircle;
                h.checkIcon = checkIcon;
                wrap.setTag(h);
                convertView = wrap;
            } else {
                h = (Holder) convertView.getTag();
            }

            final AppItem item = items.get(position);
            h.name.setText(item.label);
            h.pkg.setText(item.pkg);
            UiKit.loadAppIcon(activity, item.pkg, h.icon);

            boolean checked = excluded.contains(item.pkg);
            if (checked) {
                h.checkCircle.setBackground(UiKit.circle(accent));
                h.checkIcon.setVisibility(View.VISIBLE);
            } else {
                h.checkCircle.setBackground(UiKit.roundedRect(
                        subtleBg, UiKit.dp(activity, 12),
                        UiKit.dp(activity, 1.2f), textSecondary & 0x44FFFFFF));
                h.checkIcon.setVisibility(View.INVISIBLE);
            }

            View row = ((LinearLayout) convertView).getChildAt(0);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.haptic(v);
                    if (listener != null) {
                        listener.onToggle(item);
                    }
                    notifyDataSetChanged();
                }
            });
            return convertView;
        }
    }
}
