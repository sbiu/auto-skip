package com.autoskip.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 开屏广告跳过服务（v1.3，前台会话模型）。
 *
 * 行为契约：
 *  - 监听窗口状态变化 / 窗口内容变化 / 窗口列表变化；
 *  - 只在“应用进入前台”后的 15 秒会话内工作，其余时间回到事件回调直接 return；
 *  - 一次会话最多成功跳过 1 次（避免广告关闭后的界面切换重复计数/误点）。
 *
 * 为什么是“会话”而不是 v1.2 的 arm/deadline：
 *  - 杀进程重开、从后台/最近任务回到前台、热启动广告，都不一定产生新的
 *    TYPE_WINDOW_STATE_CHANGED；v1.2 依赖 lastEventPackage 的门槛会让这些情况下
 *    永远不开窗口（热启动不跳），而“刚跳过 8 秒内不再开窗口”又会让快速重开
 *    一次跳一次不跳。会话模型只认两个信号：包名变化、或距上一条应用事件超过 1.2 秒。
 */
public class SkipService extends AccessibilityService {

    private static final String TAG = "AutoSkip";
    private static final String PREFS = "auto_skip";
    private static final String KEY_COUNT = "skip_count";
    private static final String KEY_LAST = "last_package";

    static final String ACTION_STATE_CHANGED = "com.autoskip.app.STATE_CHANGED";

    /** 进入前台后最多工作 15 秒。 */
    private static final long SESSION_WINDOW_MS = 15_000L;
    /** 距上一条“应用事件”超过这个时间，视为一次新的前台会话（切出过/回后台/热启动）。 */
    private static final long SESSION_GAP_MS = 1_200L;
    private static final long MIN_SCAN_INTERVAL_MS = 120L;
    private static final long MIN_SETTLE_MS = 120L;
    private static final int MAX_SCANS = 100;
    private static final int MAX_GESTURE_ATTEMPTS = 4;
    private static final long GESTURE_RETRY_MS = 400L;
    /** 只用于 ACTION_CLICK 后的立刻防抖，不再阻塞重新开会话。 */
    private static final long CLICK_COOLDOWN_MS = 1_500L;
    private static final boolean DEBUG = false;

    /** 最近一次成功跳过的信息（GuardService 的「误跳？屏蔽」用）。 */
    static volatile String lastSkipPkg;
    static volatile String lastSkipClass;
    static volatile String lastSkipLabel;
    static volatile long lastSkipAt;

    /** 无障碍服务当前是否已连接（GuardService 看门狗用）。 */
    private static volatile boolean connected;
    /** 最近一条无障碍事件的时间（uptimeMillis），用于诊断。 */
    static volatile long lastEventAt;

    private static volatile SkipService instance;

    static boolean isConnected() {
        return connected && instance != null;
    }

    /** 给 GuardService 探活：a11y 客户端假死时 getRootInActiveWindow() 会一直拿不到根节点。 */
    static boolean probeAlive() {
        SkipService s = instance;
        if (s == null) {
            return false;
        }
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            return root != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final int CLICK_NONE = 0;
    private static final int CLICK_GESTURE = 1;
    private static final int CLICK_ACTION = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ---- 前台会话状态 ----
    private String foregroundPackage;
    private String foregroundClass;
    private String lastHitLabel;
    private long sessionStartedAt;
    private long lastAppEventAt;
    private long lastScanAt;
    private long lastActionClickAt;
    private int scanCount;
    private int sessionIndex;
    private int gestureAttempts;
    private boolean recorded;
    private boolean clickedThisSession;
    private boolean snapshotSaved;
    private boolean receiverRegistered;

    private int screenWidth;
    private int screenHeight;
    private String homePackage;
    private PowerManager powerManager;

    private final Runnable delayedScan = new Runnable() {
        @Override
        public void run() {
            scanInternal(SystemClock.uptimeMillis());
        }
    };

    /** 主界面的开关通过广播通知服务即时启停，避免轮询。 */
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SkipRules.ACTION_RULES_CHANGED.equals(intent.getAction())) {
                SkipRules.load(SkipService.this);
                return;
            }
            SkipState.load(SkipService.this);
            applyEnabled();
            if (!SkipState.isEnabled()) {
                endSession();
            }
        }
    };

    /**
     * 服务刚连上时的自愈：系统可能在上一次会话里解绑过服务，
     * 此时开屏广告已经显示、错过了会话开始的那个事件。这里对当前前台窗口补一次会话。
     */
    private final Runnable healOnConnect = new Runnable() {
        @Override
        public void run() {
            if (!SkipState.isEnabled()) {
                return;
            }
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) {
                    return;
                }
                CharSequence p = root.getPackageName();
                if (p == null) {
                    return;
                }
                String pkg = p.toString();
                if (pkg.isEmpty() || isIgnoredPackage(pkg)) {
                    return;
                }
                startSession(pkg, SystemClock.uptimeMillis());
                if (DEBUG) {
                    Log.i(TAG, "heal session " + pkg);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    /** 诊断计数：帮助在真机上确认事件是否到达（上限 500ms 写一次）。 */
    private long lastDebugAt;

    private void debug(String what) {
        try {
            long now = SystemClock.uptimeMillis();
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt("dbg_events", sp.getInt("dbg_events", 0) + 1);
            if (now - lastDebugAt > 500) {
                lastDebugAt = now;
                editor.putString("dbg_last", what);
            }
            editor.apply();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        updateScreenSize();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(home, 0);
            if (ri != null && ri.activityInfo != null) {
                homePackage = ri.activityInfo.packageName;
            }
        } catch (Throwable ignored) {
            homePackage = null;
        }
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("home_pkg", homePackage == null ? "-" : homePackage).apply();
        } catch (Throwable ignored) {
        }
        SkipState.load(this);
        SkipRules.load(this);
        registerStateReceiver();
        applyEnabled();
        handler.postDelayed(healOnConnect, 500);
        connected = true;
        instance = this;
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong("last_connect", System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
        }
        if (SkipState.isGuardEnabled(this)) {
            GuardService.start(this);
            GuardService.refresh(this);
        }
        Log.i(TAG, "service connected, home=" + homePackage + " enabled=" + SkipState.isEnabled());
    }

    private void registerStateReceiver() {
        if (receiverRegistered) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(ACTION_STATE_CHANGED);
            filter.addAction(SkipRules.ACTION_RULES_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stateReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Throwable ignored) {
            receiverRegistered = false;
        }
    }

    /** 开关关闭时把 eventTypes 置 0：系统不再派发事件，后台开销为零。 */
    private void applyEnabled() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                return;
            }
            info.eventTypes = SkipState.isEnabled()
                    ? (AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED)
                    : 0;
            // 开屏广告的“跳过”经常被标记为 not-important，默认过滤掉后节点树里根本看不到；
            // 关掉开关时不改 flags（事件已为 0，系统不会派发，零开销）。
            if (SkipState.isEnabled()) {
                info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                // 没有这个 flag，getWindows() 只会返回空列表，看不见浮层里的开屏广告
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            }
            setServiceInfo(info);
            if (SkipState.isGuardEnabled(this)) {
                GuardService.refresh(this);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScreenSize();
    }

    private void updateScreenSize() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !SkipState.isEnabled()) {
            return;
        }
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) {
            return;
        }
        String pkg = pkgCs.toString();
        if (pkg.isEmpty()) {
            return;
        }
        lastEventAt = SystemClock.elapsedRealtime();
        debug(pkg + "/" + event.getEventType());
        if (DEBUG && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.i(TAG, "state " + pkg + " cls=" + event.getClassName());
        }
        if (isIgnoredPackage(pkg)) {
            return;
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String cls = event.getClassName() == null ? null : event.getClassName().toString();
            // 只记 Activity 类名：窗口事件里还会掺 View/FrameLayout 之类，会把真实页面覆盖掉
            if (looksLikeActivity(cls)) {
                foregroundClass = cls;
            }
        }
        long now = SystemClock.uptimeMillis();
        boolean newSession = !pkg.equals(foregroundPackage)
                || now - lastAppEventAt > SESSION_GAP_MS;
        if (newSession) {
            startSession(pkg, now);
        }
        lastAppEventAt = now;

        if (SkipRules.isExcluded(this, pkg, foregroundClass)) {
            if (DEBUG) {
                Log.i(TAG, "excluded scene " + pkg + "/" + SkipRules.simpleClass(foregroundClass));
            }
            return;
        }
        if (clickedThisSession) {
            return;
        }
        if (now - sessionStartedAt > SESSION_WINDOW_MS) {
            return;
        }
        if (scanCount >= MAX_SCANS) {
            return;
        }
        if (lastScanAt != 0 && now - lastScanAt < MIN_SCAN_INTERVAL_MS) {
            return;
        }
        if (lastActionClickAt != 0 && now - lastActionClickAt < CLICK_COOLDOWN_MS) {
            return;
        }
        if (powerManager != null && !powerManager.isInteractive()) {
            return;
        }
        scanInternal(now);
    }

    private boolean looksLikeActivity(String cls) {
        if (cls == null || cls.isEmpty()) {
            return false;
        }
        return !cls.startsWith("android.")
                && !cls.startsWith("androidx.")
                && !cls.startsWith("com.android.internal.");
    }

    private void startSession(String pkg, long now) {
        foregroundPackage = pkg;
        sessionStartedAt = now;
        sessionIndex++;
        scanCount = 0;
        gestureAttempts = 0;
        recorded = false;
        clickedThisSession = false;
        snapshotSaved = false;
        lastScanAt = 0;
        handler.removeCallbacks(delayedScan);
        // 开屏广告常常在首帧之后才 inflate，补两次延迟扫描覆盖这种情况
        handler.postDelayed(delayedScan, MIN_SETTLE_MS + 60);
        handler.postDelayed(delayedScan, 900);
        if (DEBUG) {
            Log.i(TAG, "session#" + sessionIndex + " " + pkg);
        }
    }

    private void endSession() {
        foregroundPackage = null;
        clickedThisSession = true;
        handler.removeCallbacks(delayedScan);
    }

    private boolean isIgnoredPackage(String pkg) {
        if (pkg.equals(getPackageName())) {
            return true;
        }
        if (pkg.equals("android")
                || pkg.equals("com.android.systemui")
                || pkg.equals("miui.systemui.plugin")
                || pkg.equals("com.miui.home")
                || pkg.equals("com.miui.securitycenter")
                || pkg.equals("com.miui.aod")
                || pkg.equals("com.miui.personalassistant")
                || pkg.equals("com.android.quicksearchbox")
                || pkg.equals("com.google.android.googlequicksearchbox")) {
            return true;
        }
        return homePackage != null && homePackage.equals(pkg);
    }

    private void scanInternal(long now) {
        if (foregroundPackage == null || clickedThisSession) {
            return;
        }
        if (SkipRules.isExcluded(this, foregroundPackage, foregroundClass)) {
            return;
        }
        if (now - sessionStartedAt > SESSION_WINDOW_MS) {
            return;
        }
        if (now < sessionStartedAt + MIN_SETTLE_MS) {
            return;
        }
        if (scanCount >= MAX_SCANS) {
            return;
        }
        if (lastScanAt != 0 && now - lastScanAt < MIN_SCAN_INTERVAL_MS) {
            return;
        }
        if (powerManager != null && !powerManager.isInteractive()) {
            return;
        }

        lastScanAt = now;
        scanCount++;

        List<AccessibilityNodeInfo> roots = collectRoots(foregroundPackage);
        if (roots.isEmpty()) {
            if (DEBUG) {
                Log.i(TAG, "scan " + foregroundPackage + " no window");
            }
            SkipState.lastScan = "旧窗口（等待 " + foregroundPackage + "）";
            return;
        }

        AccessibilityNodeInfo target = null;
        for (AccessibilityNodeInfo root : roots) {
            AccessibilityNodeInfo found = SkipMatcher.findSkipNode(root, screenWidth, screenHeight);
            if (found != null) {
                target = found;
                break;
            }
        }
        if (DEBUG) {
            if (target != null) {
                Rect rb = new Rect();
                target.getBoundsInScreen(rb);
                Log.i(TAG, "HIT pkg=" + foregroundPackage + " text=" + target.getText()
                        + " desc=" + target.getContentDescription()
                        + " id=" + target.getViewIdResourceName()
                        + " click=" + target.isClickable()
                        + " bounds=" + rb.toShortString()
                        + " scan=" + scanCount + " roots=" + roots.size());
            } else {
                Log.i(TAG, "MISS pkg=" + foregroundPackage + " scan=" + scanCount + " roots=" + roots.size());
            }
        }
        if (target == null) {
            SkipState.lastScan = foregroundPackage + " 会话#" + sessionIndex + " 第" + scanCount
                    + "次扫描：未命中（" + roots.size() + " 个窗口）";
            saveSnapshot(roots);
            if (DEBUG) {
                dumpTree(roots.get(0));
            }
            // 手势点击后广告消失，说明已经跳过；补记一次
            if (gestureAttempts > 0) {
                recordOnce(foregroundPackage);
                clickedThisSession = true;
                handler.removeCallbacks(delayedScan);
            }
            return;
        }
        lastHitLabel = target.getText() == null
                ? (target.getContentDescription() == null ? "?" : target.getContentDescription().toString())
                : target.getText().toString();
        SkipState.lastScan = foregroundPackage + " 会话#" + sessionIndex + " 第" + scanCount
                + "次扫描：命中 " + lastHitLabel;

        String skipped = foregroundPackage;
        int result = click(target);
        if (result == CLICK_NONE) {
            return;
        }
        if (result == CLICK_ACTION) {
            lastActionClickAt = now;
            recordOnce(skipped);
            clickedThisSession = true;
            handler.removeCallbacks(delayedScan);
            return;
        }
        // 手势是异步的：完成后回查一次，广告还在就再点，消失则结束
        gestureAttempts++;
        if (gestureAttempts >= MAX_GESTURE_ATTEMPTS) {
            recordOnce(skipped);
            clickedThisSession = true;
            handler.removeCallbacks(delayedScan);
        }
    }

    /**
     * 收集当前前台包名下的所有窗口根节点。
     * 很多开屏广告（微博、部分广告 SDK）不是新 Activity，而是浮在主界面之上的独立窗口；
     * 只读 getRootInActiveWindow() 会一直拿到下面的主界面，永远看不到“跳过”。
     */
    private List<AccessibilityNodeInfo> collectRoots(String pkg) {
        List<AccessibilityNodeInfo> roots = new ArrayList<>(4);
        try {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null) {
                roots.add(active);
            }
        } catch (Throwable ignored) {
        }
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) {
                        continue;
                    }
                    AccessibilityNodeInfo root = null;
                    try {
                        root = window.getRoot();
                    } catch (Throwable ignored) {
                    }
                    if (root == null || containsSameRoot(roots, root)) {
                        continue;
                    }
                    roots.add(root);
                }
            }
        } catch (Throwable ignored) {
        }
        List<AccessibilityNodeInfo> mine = new ArrayList<>(roots.size());
        for (AccessibilityNodeInfo root : roots) {
            CharSequence rootPkg = root.getPackageName();
            if (pkg != null && rootPkg != null && pkg.equals(rootPkg.toString())) {
                mine.add(root);
            }
        }
        return mine;
    }

    private boolean containsSameRoot(List<AccessibilityNodeInfo> roots, AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo existing : roots) {
            if (existing.equals(node)) {
                return true;
            }
        }
        return false;
    }

    private int click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int depth = 0; depth < 4 && cur != null; depth++) {
            if (cur.isClickable() && cur.isEnabled()) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    if (DEBUG) {
                        Log.i(TAG, "click-node depth=" + depth + " cls=" + cur.getClassName());
                    }
                    return CLICK_ACTION;
                }
                break;
            }
            AccessibilityNodeInfo parent = cur.getParent();
            if (parent == null) {
                break;
            }
            cur = parent;
        }
        if (DEBUG) {
            Log.i(TAG, "click-node failed, fallback to gesture");
        }
        return gestureTap(node) ? CLICK_GESTURE : CLICK_NONE;
    }

    /** 节点不可点击（很多开屏广告用非 clickable 的 TextView）时的兜底：坐标点击。 */
    private boolean gestureTap(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 60);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        try {
            return dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    if (DEBUG) {
                        Log.i(TAG, "gesture completed");
                    }
                    if (foregroundPackage == null) {
                        return;
                    }
                    if (gestureAttempts >= MAX_GESTURE_ATTEMPTS) {
                        recordOnce(foregroundPackage);
                        clickedThisSession = true;
                    } else {
                        // 回查一次：广告还在就再点，消失则结束
                        handler.postDelayed(delayedScan, GESTURE_RETRY_MS);
                    }
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    if (DEBUG) {
                        Log.i(TAG, "gesture cancelled");
                    }
                    if (foregroundPackage != null) {
                        handler.postDelayed(delayedScan, 250);
                    }
                }
            }, null);
        } catch (Throwable t) {
            if (DEBUG) {
                Log.i(TAG, "gesture failed " + t);
            }
            return false;
        }
    }

    /** 仅调试：把当前窗口前 40 个节点的结构打到 logcat，用于分析未命中的广告。 */
    private void dumpTree(AccessibilityNodeInfo root) {
        try {
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            queue.add(root);
            int n = 0;
            while (!queue.isEmpty() && n < 40) {
                AccessibilityNodeInfo node = queue.poll();
                n++;
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                Log.i(TAG, "  #" + n + " " + node.getClassName()
                        + " text=" + node.getText()
                        + " desc=" + node.getContentDescription()
                        + " id=" + node.getViewIdResourceName()
                        + " click=" + node.isClickable()
                        + " vis=" + node.isVisibleToUser()
                        + " " + r.toShortString());
                int children = node.getChildCount();
                for (int i = 0; i < children; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 未命中时把当前窗口前 40 个节点的结构存进 SharedPreferences，主界面诊断区可查看。
     * 广告模板再变化时，直接看这份快照就能定位新的“跳过/关闭”节点（仅本地，不上传）。
     */
    private void saveSnapshot(List<AccessibilityNodeInfo> roots) {
        if (snapshotSaved) {
            return;
        }
        snapshotSaved = true;
        try {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (AccessibilityNodeInfo root : roots) {
                if (root == null) {
                    continue;
                }
                sb.append("== window ").append(root.getPackageName()).append(" ==\n");
                ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
                queue.add(root);
                while (!queue.isEmpty() && n < 40) {
                    AccessibilityNodeInfo node = queue.poll();
                    n++;
                    Rect r = new Rect();
                    node.getBoundsInScreen(r);
                    sb.append(node.getClassName()).append('|')
                            .append(node.getText() == null ? "" : node.getText()).append('|')
                            .append(node.getContentDescription() == null ? "" : node.getContentDescription()).append('|')
                            .append(node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName()).append('|')
                            .append(r.left).append(',').append(r.top).append(',')
                            .append(r.right).append(',').append(r.bottom).append('\n');
                    int children = node.getChildCount();
                    for (int i = 0; i < children; i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) {
                            queue.add(child);
                        }
                    }
                }
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("dbg_tree", sb.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private void recordOnce(String pkg) {
        if (!recorded && pkg != null) {
            recorded = true;
            recordSkip(pkg);
        }
    }

    private void recordSkip(String pkg) {
        try {
            lastSkipPkg = pkg;
            lastSkipClass = foregroundClass;
            lastSkipLabel = lastHitLabel;
            lastSkipAt = SystemClock.elapsedRealtime();
            SkipRules.recordSkip(this, pkg, foregroundClass, lastHitLabel);
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            int count = sp.getInt(KEY_COUNT, 0) + 1;
            sp.edit().putInt(KEY_COUNT, count).putString(KEY_LAST, pkg).apply();
            Log.i(TAG, "skipped " + pkg + " count=" + count);
            // 通知栏的“已跳过 N 次”即时刷新（事件驱动，不轮询）
            if (SkipState.isGuardEnabled(this)) {
                GuardService.refresh(this);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onInterrupt() {
        // no-op
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        if (instance == this) {
            instance = null;
        }
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong("last_unbind", System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "service unbound");
        if (SkipState.isGuardEnabled(this)) {
            GuardService.refresh(this);
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        if (instance == this) {
            instance = null;
        }
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }
}
