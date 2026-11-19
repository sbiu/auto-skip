package com.autoskip.testad;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

public class GestureAdActivity extends Activity {
    static final String TAG = "TestAd";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ad_gesture);
        TextView skip = findViewById(R.id.skip);
        skip.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    Log.i(TAG, "GESTURE_AD_CLICKED");
                    finish();
                }
                return true;
            }
        });
    }
}
