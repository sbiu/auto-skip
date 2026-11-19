package com.autoskip.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.graphics.BitmapFactory;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 常驻守护（学李跳跳）：
 *  - 前台服务 + ongoing 常驻通知，进程不会退到 MIUI 的 idle 名单；
 *  - 每 60 秒检查通知是否还在，被 ROM 清掉就重新贴（用户手动滑不掉）；
 *  - 每 30 秒看门狗：无障碍服务被系统关掉 → 通知提示；仍开启但连接丢失超过 60 秒 → 杀进程让系统重绑；
 *  - START_STICKY，被杀后由系统重建。
 */
public class GuardService extends Service {

    static final String TAG = "AutoSkipGuard";
    static final int NOTIFY_ID = 1001;
    static final int PROBLEM_NOTIFY_ID = 1002;
    static final String CHANNEL_ID = "auto_skip_guard_v3";
    /** v1.8 及之前的低优先级通道，升级后删除，避免系统设置里留两条同名通道。 */
    static final String CHANNEL_ID_LEGACY = "auto_skip_guard";
    static final String CHANNEL_ID_V2 = "auto_skip_guard_v2";
    static final String CHANNEL_PROBLEM = "auto_skip_problem";
    static final String ACTION_TOGGLE = "com.autoskip.app.GUARD_TOGGLE";
    static final String ACTION_REFRESH = "com.autoskip.app.GUARD_REFRESH";
    static final String ACTION_BLOCK_LAST = "com.autoskip.app.GUARD_BLOCK_LAST";

    /** 跳过之后「误跳？屏蔽」入口的展示时长。 */
    private static final long HINT_MS = 12_000L;

    private static final boolean DEBUG = false;
    private static final long CHECK_INTERVAL_MS = 30_000L;
    private static final long NOTIFY_RECHECK_MS = 60_000L;
    private static final long DISCONNECTED_ALARM_MS = 60_000L;
    private static final long REBOOT_COOLDOWN_MS = 10 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private NotificationManager nm;
    private long disconnectedSince;
    private int probeFailures;
    private PowerManager powerManager;
    private long lastRebootAt;
    private boolean screenReceiverRegistered;
    private boolean problemShown;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            check();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver screenOnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 亮屏时立刻检查一次：很多“过一阵就不跳”是息屏/待机后被系统解绑
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    check();
                }
            }, 1500);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        createChannels();
        lastRebootAt = getSharedPreferences("auto_skip", MODE_PRIVATE)
                .getLong("guard_last_reboot", 0);
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenOnReceiver, filter);
            screenReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            SkipState.set(this, !SkipState.isEnabled());
            applyA11yState();
            startForeground(NOTIFY_ID, buildNotification());
            return START_STICKY;
        }
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            // 只刷新通知内容（计数/连接状态/按钮文案），不重置看门狗
            startForeground(NOTIFY_ID, buildNotification());
            return START_STICKY;
        }
        if (intent != null && ACTION_BLOCK_LAST.equals(intent.getAction())) {
            String pkg = SkipService.lastSkipPkg;
            String cls = SkipService.lastSkipClass;
            if (pkg != null) {
                SkipRules.addScene(this, pkg, cls);
                SkipService.lastSkipAt = 0;
                Log.i(TAG, "blocked scene " + pkg + "/" + cls);
            }
            startForeground(NOTIFY_ID, buildNotification());
            return START_STICKY;
        }
        startForeground(NOTIFY_ID, buildNotification());
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 从最近任务划掉也继续守护
        if (SkipState.isGuardEnabled(this)) {
            startForeground(NOTIFY_ID, buildNotification());
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenOnReceiver);
            } catch (Throwable ignored) {
            }
            screenReceiverRegistered = false;
        }
        super.onDestroy();
    }

    // ---- 看门狗 ----

    private void check() {
        boolean a11yEnabled = isA11yEnabledInSettings();
        if (!a11yEnabled) {
            disconnectedSince = 0;
            showProblemNotification();
            return;
        }
        clearProblemNotification();
        boolean interactive = powerManager == null || powerManager.isInteractive();
        if (DEBUG) {
            long ago = SkipService.lastEventAt == 0 ? -1
                    : (SystemClock.elapsedRealtime() - SkipService.lastEventAt) / 1000;
            Log.i(TAG, "tick a11y=" + a11yEnabled + " connected=" + SkipService.isConnected()
                    + " probeOk=" + SkipService.probeAlive() + " failures=" + probeFailures
                    + " lastEventSecAgo=" + ago + " screenOn=" + interactive);
        }
        if (SkipService.isConnected()) {
            disconnectedSince = 0;
            // 假死检测：系统显示已绑定、但 a11y 客户端拿不到任何窗口根节点
            if (interactive) {
                if (SkipService.probeAlive()) {
                    probeFailures = 0;
                } else {
                    probeFailures++;
                    Log.i(TAG, "probe failed " + probeFailures + " times");
                    if (probeFailures >= 3) {
                        restartProcessToRebind(SystemClock.elapsedRealtime());
                    }
                }
            }
        } else {
            long now = SystemClock.elapsedRealtime();
            if (disconnectedSince == 0) {
                disconnectedSince = now;
            } else if (now - disconnectedSince > DISCONNECTED_ALARM_MS) {
                restartProcessToRebind(now);
            }
        }
        ensureForegroundNotification();
    }

    /** 无障碍在系统设置里仍开启、但服务连接丢了：杀掉自己让系统重新 bind。 */
    private void restartProcessToRebind(long now) {
        if (now - lastRebootAt < REBOOT_COOLDOWN_MS) {
            return;
        }
        lastRebootAt = now;
        probeFailures = 0;
        try {
            getSharedPreferences("auto_skip", MODE_PRIVATE).edit()
                    .putLong("guard_last_reboot", now)
                    .putLong("guard_last_reboot_wall", System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "a11y connected=false for 60s, restarting process to force rebind");
        Process.killProcess(Process.myPid());
    }

    private boolean isA11yEnabledInSettings() {
        try {
            String enabled = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) {
                return false;
            }
            String me = getPackageName() + "/" + SkipService.class.getName();
            String shortMe = getPackageName() + "/.SkipService";
            return enabled.contains(me) || enabled.contains(shortMe);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 有些 ROM（含 MIUI 的“清除全部”）会把 ongoing 通知清掉；每 60 秒检查一次，
     * 不在了就重新 startForeground 贴回来，做到“删不掉”。
     */
    private void ensureForegroundNotification() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotifyCheck < NOTIFY_RECHECK_MS) {
            return;
        }
        lastNotifyCheck = now;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                StatusBarNotification[] active = nm.getActiveNotifications();
                if (active != null) {
                    for (StatusBarNotification sbn : active) {
                        if (sbn.getId() == NOTIFY_ID) {
                            return;
                        }
                    }
                }
                startForeground(NOTIFY_ID, buildNotification());
            }
        } catch (Throwable ignored) {
        }
    }

    private long lastNotifyCheck;

    private void showProblemNotification() {
        if (problemShown) {
            return;
        }
        problemShown = true;
        try {
            Intent open = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CHANNEL_PROBLEM)
                    .setSmallIcon(R.mipmap.ic_launcher_monochrome)
                    .setLargeIcon(largeIcon())
                    .setContentTitle("无障碍服务被系统关闭")
                    .setContentText("点这里重新开启，张跳跳 才能继续跳过广告")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build();
            nm.notify(PROBLEM_NOTIFY_ID, n);
        } catch (Throwable ignored) {
        }
    }

    private void clearProblemNotification() {
        if (!problemShown) {
            return;
        }
        problemShown = false;
        try {
            nm.cancel(PROBLEM_NOTIFY_ID);
        } catch (Throwable ignored) {
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        // 通道重要性创建后不可修改：用 IMPORTANCE_DEFAULT 新建 v2 通道，
        // 让通知脱离 HyperOS 的“更多通知”收纳（LOW/MIN 会被折进去）。
        NotificationChannel guard = new NotificationChannel(
                CHANNEL_ID, "常驻守护", NotificationManager.IMPORTANCE_HIGH);
        guard.setDescription("保持无障碍服务在线，防止被系统冻结");
        guard.setShowBadge(false);
        guard.enableVibration(false);
        guard.setSound(null, null);
        guard.enableLights(false);
        guard.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(guard);
        try {
            nm.deleteNotificationChannel(CHANNEL_ID_LEGACY);
            nm.deleteNotificationChannel(CHANNEL_ID_V2);
        } catch (Throwable ignored) {
        }

        NotificationChannel problem = new NotificationChannel(
                CHANNEL_PROBLEM, "服务异常提醒", NotificationManager.IMPORTANCE_DEFAULT);
        problem.setDescription("无障碍服务被关闭时提醒");
        nm.createNotificationChannel(problem);
    }

    private final Runnable hideHint = new Runnable() {
        @Override
        public void run() {
            startForeground(NOTIFY_ID, buildNotification());
        }
    };

    private void scheduleHintHide() {
        handler.removeCallbacks(hideHint);
        long remain = HINT_MS - (SystemClock.elapsedRealtime() - SkipService.lastSkipAt);
        if (remain > 0) {
            handler.postDelayed(hideHint, remain + 100);
        }
    }

    private Bitmap largeIcon() {
        try {
            return BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取应用名用于通知副标；取不到返回 null。 */
    private String appLabel(String pkg) {
        if (pkg == null) {
            return null;
        }
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 副标里的应用名：空则退回包名末段，过长截断，保证单行不撑高卡片。 */
    private String shortLabel(String label, String pkg) {
        String s = label == null ? "" : label.trim();
        if (s.isEmpty() && pkg != null) {
            int dot = pkg.lastIndexOf('.');
            s = dot >= 0 && dot + 1 < pkg.length() ? pkg.substring(dot + 1) : pkg;
        }
        if (s.length() > 8) {
            s = s.substring(0, 8) + "…";
        }
        return s;
    }

    private Notification buildNotification() {
        int count = 0;
        try {
            SharedPreferences sp = getSharedPreferences("auto_skip", MODE_PRIVATE);
            count = sp.getInt("skip_count", 0);
        } catch (Throwable ignored) {
        }
        boolean connected = SkipService.isConnected();
        boolean enabled = SkipState.isEnabled();
        boolean showBlock = SkipService.lastSkipPkg != null
                && SystemClock.elapsedRealtime() - SkipService.lastSkipAt < HINT_MS;

        // 收起状态下一行标题 + 一行副标即可看全，不需要点“展开”
        String title;
        String text;
        if (enabled) {
            title = getString(R.string.notif_title_run, count);
            text = connected ? getString(R.string.notif_sub_connected)
                    : getString(R.string.notif_sub_waiting);
        } else {
            title = getString(R.string.notif_title_paused);
            text = connected ? getString(R.string.notif_sub_paused_fmt, count)
                    : getString(R.string.notif_sub_waiting);
        }
        if (showBlock) {
            text = getString(R.string.notif_sub_just_skipped,
                    shortLabel(appLabel(SkipService.lastSkipPkg), SkipService.lastSkipPkg));
        }

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent toggle = new Intent(this, GuardService.class).setAction(ACTION_TOGGLE);
        PendingIntent togglePi = PendingIntent.getService(this, 1, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent block = new Intent(this, GuardService.class).setAction(ACTION_BLOCK_LAST);
        PendingIntent blockPi = PendingIntent.getService(this, 2, block,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (showBlock) {
            scheduleHintHide();
        }

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setColor(getResources().getColor(R.color.accent))
                .setStyle(new Notification.BigTextStyle().bigText(text));

        // 不用自定义 RemoteViews：HyperOS 会把带自定义布局的前台服务通知收进“更多通知”，
        // 并且自定义布局比收起行高更高，会多出一个“展开”箭头。
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this,
                        enabled ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play),
                getString(enabled ? R.string.action_pause : R.string.action_resume),
                togglePi).build());
        if (showBlock) {
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.notif_block), blockPi).build());
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            b.setChannelId(CHANNEL_ID);
        }
        Notification n = b.build();
        n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        return n;
    }

    private void applyA11yState() {
        // 通过广播让 SkipService 立刻应用开关（服务在跑时无需重开无障碍）
        try {
            sendBroadcast(new Intent(SkipService.ACTION_STATE_CHANGED).setPackage(getPackageName()));
        } catch (Throwable ignored) {
        }
    }

    /** 跳过一次/连接状态变化时调用：立刻刷新通知栏里的计数与文案（事件驱动，不轮询）。 */
    static void refresh(Context context) {
        try {
            Intent i = new Intent(context, GuardService.class).setAction(ACTION_REFRESH);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    static void start(Context context) {
        try {
            Intent i = new Intent(context, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    static void stop(Context context) {
        try {
            context.stopService(new Intent(context, GuardService.class));
        } catch (Throwable ignored) {
        }
    }
}
