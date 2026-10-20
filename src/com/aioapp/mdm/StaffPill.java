package com.aioapp.mdm;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The staff-mode pill (C1, picked 20 Oct 2026): centred just under the status bar, above
 * every app and the system bars, while the tablet is out of lock mode after a staff PIN, "● Staff mode | Back to guest
 * mode", with a slow coral pulse. A tap on it locks the host app again.
 *
 * A system window the client adds itself: it runs as the system uid, which may add the
 * navigation-bar-panel type, the layer above the status bar (an app overlay sits under it,
 * and the status bar would take the taps). Laid out in code like the other screens here.
 */
final class StaffPill {
    private static final String TAG = "StaffPill";
    private static final int C_BG = Color.argb(240, 28, 24, 40);
    private static final int C_LINE = Color.argb(41, 255, 255, 255);
    private static final int C_CORAL = Color.parseColor("#F9674E");
    private static final int C_BACK = Color.parseColor("#FFB199");

    private final Context ctx;
    private final Runnable onBack;
    private View view;
    private ObjectAnimator pulse;

    StaffPill(Context ctx, Runnable onBack) {
        this.ctx = ctx;
        this.onBack = onBack;
    }

    void show() {
        if (view != null) return;
        View v = build();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = statusBarHeight();
        lp.setFitInsetsTypes(0);
        lp.setTitle("AioStaffPill");
        try {
            ctx.getSystemService(WindowManager.class).addView(v, lp);
            view = v;
            Log.i(TAG, "staff mode: pill up");
        } catch (RuntimeException e) {
            Log.w(TAG, "can't add the staff pill: " + e.getMessage());
        }
    }

    void hide() {
        if (view == null) return;
        if (pulse != null) pulse.cancel();
        try {
            ctx.getSystemService(WindowManager.class).removeView(view);
        } catch (RuntimeException ignored) {
        }
        view = null;
        Log.i(TAG, "staff mode: pill down");
    }

    private View build() {
        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(11), 0, dp(14), 0);
        pill.setMinimumHeight(dp(30));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(C_BG);
        bg.setStroke(dp(1), C_LINE);
        pill.setBackground(bg);
        pill.setElevation(dp(4));
        pill.setContentDescription("Staff mode. Back to guest mode");
        pill.setOnClickListener(x -> onBack.run());

        View dot = new View(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(C_CORAL);
        dot.setBackground(d);
        pill.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f);
        pulse.setDuration(1000);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();

        pill.addView(text("Staff mode", Color.WHITE, "aio-sans-medium"), margins(dp(7), 0));
        View sep = new View(ctx);
        sep.setBackgroundColor(Color.argb(64, 255, 255, 255));
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(dp(1), dp(14));
        sepLp.leftMargin = dp(9);
        sepLp.rightMargin = dp(9);
        pill.addView(sep, sepLp);
        pill.addView(text("Back to guest mode  ›", C_BACK, "aio-sans-medium"), margins(0, 0));
        return pill;
    }

    private TextView text(String s, int color, String family) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static LinearLayout.LayoutParams margins(int left, int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = left;
        lp.rightMargin = right;
        return lp;
    }

    /** The status bar as drawn now (the framework's status_bar_height dimen overstates it). */
    private int statusBarHeight() {
        try {
            return ctx.getSystemService(WindowManager.class).getCurrentWindowMetrics()
                    .getWindowInsets().getInsets(android.view.WindowInsets.Type.statusBars()).top;
        } catch (RuntimeException e) {
            return dp(24);
        }
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
