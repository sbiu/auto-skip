package com.autoskip.testad;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

public class WeiboStyleAdActivity extends Activity {
    static final String TAG = "TestAd";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ad_weibo_style);
        View container = findViewById(R.id.skip_container);
        container.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    Log.i(TAG, "WEIBO_STYLE_CLICKED");
                    finish();
                }
                return true;
            }
        });
    }
}
