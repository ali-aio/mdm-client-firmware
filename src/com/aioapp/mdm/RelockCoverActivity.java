package com.aioapp.mdm;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.window.SplashScreen;

/**
 * "Just a moment" over the screen while a locked tablet's host app is put back. When lock
 * task drops (the app crashed or was killed) the device is unlocked for the second or two
 * before MdmService re-locks it, and the guest would otherwise see the launcher. This covers
 * that gap: the venue's logo (the AIO mark until there is one) in a coral spinner, on the dark
 * AIO background, and nothing to touch.
 *
 * It goes away by itself: ACTION_DONE when the device is locked again, or after
 * {@link #MAX_MS} whatever happens, so a host app that never comes back can't leave the
 * tablet stuck under it. Laid out in code like LostActivity.
 */
public class RelockCoverActivity extends Activity {
    static final String ACTION_DONE = "com.aioapp.mdm.action.RELOCK_COVER_DONE";
    private static final long MAX_MS = 8_000L;

    private static final int C_BG_TOP = Color.parseColor("#3A2B5E");
    private static final int C_BG = Color.parseColor("#1B1530");
    private static final int C_TITLE = Color.parseColor("#FFFFFF");
    private static final int C_SUB = Color.parseColor("#C9C5D8");
    private static final int C_CORAL = Color.parseColor("#F9674E");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::finish;

    private final BroadcastReceiver done = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            finish();
        }
    };

    /** Puts the cover up. Safe to call again while it is showing (singleInstance). */
    static void show(Context ctx) {
        try {
            // No transition and a plain-colour splash: the system's icon splash would show
            // first otherwise.
            ActivityOptions o = ActivityOptions.makeCustomAnimation(ctx, 0, 0);
            o.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR);
            ctx.startActivity(new Intent(ctx, RelockCoverActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    o.toBundle());
        } catch (Exception ignored) {
        }
    }

    /** Takes the cover down, if it is up. */
    static void hide(Context ctx) {
        try {
            ctx.sendBroadcast(new Intent(ACTION_DONE).setPackage(ctx.getPackageName()));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        registerReceiver(done, new IntentFilter(ACTION_DONE), Context.RECEIVER_NOT_EXPORTED);
        setContentView(buildView());
        ui.postDelayed(timeout, MAX_MS);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        ui.removeCallbacks(timeout);
        ui.postDelayed(timeout, MAX_MS);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(timeout);
        try { unregisterReceiver(done); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public void onBackPressed() { /* nothing behind it for a guest */ }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) { return true; }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        return e.getKeyCode() != KeyEvent.KEYCODE_POWER || super.dispatchKeyEvent(e);
    }

    private View buildView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {C_BG_TOP, C_BG}));

        // Logo tile inside a coral ring that spins.
        FrameLayout ring = new FrameLayout(this);
        ProgressBar spin = new ProgressBar(this);
        spin.setIndeterminate(true);
        spin.setIndeterminateTintList(ColorStateList.valueOf(C_CORAL));
        ring.addView(spin, new FrameLayout.LayoutParams(dp(132), dp(132), Gravity.CENTER));

        FrameLayout tile = new FrameLayout(this);
        GradientDrawable tileBg = new GradientDrawable();
        tileBg.setCornerRadius(dp(17));
        Bitmap logo = VenueIcon.icon(this);
        if (logo != null && GuestInfo.load(this).hasRestaurant()) {
            tileBg.setColor(Color.WHITE);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(logo);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(8), dp(8), dp(8), dp(8));
            tile.addView(iv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            tileBg.setColors(new int[] {C_CORAL, Color.parseColor("#7A80F6")});
            tileBg.setOrientation(GradientDrawable.Orientation.TL_BR);
            TextView mark = new TextView(this);
            mark.setText("AIO");
            mark.setTextColor(Color.WHITE);
            mark.setTextSize(15);
            mark.setTypeface(Typeface.create("aio-sans-medium", Typeface.NORMAL));
            mark.setLetterSpacing(0.08f);
            mark.setGravity(Gravity.CENTER);
            tile.addView(mark, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        tile.setBackground(tileBg);
        tile.setClipToOutline(true);
        ring.addView(tile, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        root.addView(ring, new LinearLayout.LayoutParams(dp(132), dp(132)));

        root.addView(label("Just a moment", C_TITLE, 26, "aio-sans-medium", dp(28)));
        root.addView(label("Getting the menu ready", C_SUB, 15, "aio-sans", dp(6)));
        return root;
    }

    private TextView label(String text, int color, float sp, String family, int top) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(24), top, dp(24), 0);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
