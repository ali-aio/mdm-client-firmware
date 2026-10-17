package com.aioapp.mdm;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The lost-mode screen (see {@link LostMode}): what the device is, who to call, its serial.
 * Nothing on it can be tapped — touches are swallowed and Back does nothing; lock task keeps
 * home, recents, notifications and the status bar away. The five-Power-press exit prompt does
 * not open while lost; only the dashboard lifts it.
 *
 * Laid out in code like UnlockActivity, with no theme, layout or font the image may lack.
 * The text block is centred and width-capped, so it reads the same on the T7 in landscape
 * and on a portrait kiosk panel. The screen is allowed to sleep: a lost T7 is on battery,
 * and keeping the panel lit would run it flat long before anyone finds it.
 */
public class LostActivity extends Activity {
    private static final int C_BG = Color.parseColor("#0F172A");
    private static final int C_TITLE = Color.parseColor("#F8FAFC");
    private static final int C_BODY = Color.parseColor("#CBD5E1");
    private static final int C_DIM = Color.parseColor("#64748B");

    private static final String DEFAULT_MESSAGE =
            "This device belongs to the restaurant. Please hand it to a member of staff.";

    private final BroadcastReceiver lifted = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (LostMode.ACTION_CHANGED.equals(i.getAction())) setContentView(buildView());
            else finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("");
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        IntentFilter f = new IntentFilter(LostMode.ACTION_LIFTED);
        f.addAction(LostMode.ACTION_CHANGED);
        registerReceiver(lifted, f, Context.RECEIVER_NOT_EXPORTED);
        setContentView(buildView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!LostMode.isOn(this)) finish(); // lifted while we were in the background
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(lifted); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public void onBackPressed() { /* nothing to go back to */ }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) { return true; }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // D-pad and remote keys go nowhere; Power is handled by the system, not here.
        return true;
    }

    private View buildView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(32), dp(32), dp(32), dp(32));

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        int width = Math.min(dp(560), getResources().getDisplayMetrics().widthPixels - dp(64));
        block.setLayoutParams(new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT));

        block.addView(text("This device is lost", C_TITLE, 30, Typeface.DEFAULT_BOLD, 0));
        String msg = LostMode.message(this);
        block.addView(text(msg.isEmpty() ? DEFAULT_MESSAGE : msg, C_BODY, 18, Typeface.DEFAULT, dp(16)));
        String phone = LostMode.phone(this);
        if (!phone.isEmpty()) {
            block.addView(text(phone, C_TITLE, 26, Typeface.DEFAULT_BOLD, dp(24)));
        }
        String serial = LostMode.serial(this);
        if (!serial.isEmpty()) {
            block.addView(text("Serial " + serial, C_DIM, 12, Typeface.MONOSPACE, dp(40)));
        }
        root.addView(block);
        return root;
    }

    private TextView text(String s, int color, int sp, Typeface tf, int topMargin) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
