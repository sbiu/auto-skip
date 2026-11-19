package com.autoskip.testad;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 模拟“小程序容器里的假开屏”：类名故意包含 AppBrand（微信小程序容器的特征），
 * 页面上有一个“跳过 5”按钮。张跳跳应因内置场景规则而**不跳过**它。
 */
public class AppBrandFakeActivity extends Activity {

    static final String TAG = "TestAd";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1565C0);

        TextView title = new TextView(this);
        title.setText("小程序页面（不是开屏广告）");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER;
        root.addView(title, tlp);

        TextView skip = new TextView(this);
        skip.setText("跳过 5");
        skip.setTextColor(0xFFFFFFFF);
        skip.setTextSize(17);
        skip.setPadding(50, 18, 50, 18);
        skip.setBackgroundColor(0x66000000);
        skip.setClickable(true);
        skip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "APPBRAND_FAKE_CLICKED");
            }
        });
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM | Gravity.END;
        slp.bottomMargin = 160;
        slp.rightMargin = 60;
        root.addView(skip, slp);

        setContentView(root);
    }
}
