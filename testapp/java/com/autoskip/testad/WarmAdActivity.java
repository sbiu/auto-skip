package com.autoskip.testad;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 复现“热启动开屏广告”：广告不是新 Activity，而是回到前台 800ms 后
 * 在同一个窗口里浮出来（只有内容变化事件，没有窗口状态变化）。
 *
 * 用法：
 *   am start -n com.autoskip.testad/.WarmAdActivity   # 冷启动
 *   input keyevent HOME ; sleep 2
 *   am start -n com.autoskip.testad/.WarmAdActivity   # 热启动（应同样被跳过）
 * 命中后 logcat 会出现 TestAd: WARM_AD_SKIPPED。
 */
public class WarmAdActivity extends Activity {

    static final String TAG = "TestAd";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private FrameLayout adOverlay;
    private TextView skipView;
    private int countdown = 5;
    /** 广告延迟出现（毫秒）：默认 800ms；传 20000 可模拟“前台停留很久后才弹开屏广告”。 */
    private int adDelayMs = 800;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        TextView content = new TextView(this);
        content.setText("内容页（不是广告）");
        content.setTextSize(22);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.topMargin = 220;
        lp.leftMargin = 60;
        root.addView(content, lp);
        setContentView(root);
        adDelayMs = getIntent().getIntExtra("delay", 800);
    }

    @Override
    protected void onResume() {
        super.onResume();
        countdown = 5;
        handler.postDelayed(showAd, adDelayMs);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
        hideAd();
    }

    private final Runnable showAd = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || adOverlay != null) {
                return;
            }
            FrameLayout ad = new FrameLayout(WarmAdActivity.this);
            ad.setBackgroundColor(0xFFB71C1C);

            TextView label = new TextView(WarmAdActivity.this);
            label.setText("广告");
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(18);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.gravity = Gravity.TOP | Gravity.END;
            llp.topMargin = 260;
            llp.rightMargin = 40;
            ad.addView(label, llp);

            TextView skip = new TextView(WarmAdActivity.this);
            skip.setText("跳过 " + countdown);
            skip.setTextColor(0xFFFFFFFF);
            skip.setTextSize(17);
            skip.setPadding(50, 18, 50, 18);
            skip.setBackgroundColor(0x66000000);
            skip.setClickable(true);
            skip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.i(TAG, "WARM_AD_SKIPPED");
                    hideAd();
                }
            });
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.gravity = Gravity.BOTTOM | Gravity.END;
            slp.bottomMargin = 160;
            slp.rightMargin = 60;
            ad.addView(skip, slp);

            root.addView(ad, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            adOverlay = ad;
            skipView = skip;
            tick();
        }
    };

    private void tick() {
        if (skipView == null) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (skipView == null) {
                    return;
                }
                countdown--;
                if (countdown <= 0) {
                    hideAd();
                    return;
                }
                skipView.setText("跳过 " + countdown);
                tick();
            }
        }, 1000);
    }

    private void hideAd() {
        handler.removeCallbacksAndMessages(null);
        if (adOverlay != null && adOverlay.getParent() == root) {
            root.removeView(adOverlay);
        }
        adOverlay = null;
        skipView = null;
    }
}
