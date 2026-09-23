package com.aioapp.mdm;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The offline kiosk-exit prompt: a technician enters the static exit PIN (or the rotating
 * TOTP code) to leave kiosk lock mode with no server connection. Opened by five Power
 * presses inside 10s — see MdmService.recordPowerPress, which is the exit path on QCOM
 * images, where the SystemUI long-press-Back arm does not exist. Deliberately unbranded.
 *
 * Entry is through a keypad this activity draws, not the system IME: lock task can be run
 * with the keyboard suppressed, a menu board is driven by a remote with no IME at all, and
 * these buttons are focusable so a D-pad reaches them. Nothing here depends on a theme, a
 * layout resource, or a font the image may not carry — hence the drawn lock mark.
 */
public class UnlockActivity extends Activity {
    private static final String TAG = "UnlockActivity";

    // Palette (self-contained; no theme dependency).
    private static final int C_BG      = Color.parseColor("#FFFFFF");
    private static final int C_TITLE   = Color.parseColor("#0F172A");
    private static final int C_SUB     = Color.parseColor("#64748B");
    private static final int C_FIELD   = Color.parseColor("#F1F5F9");
    private static final int C_KEY     = Color.parseColor("#F8FAFC");
    private static final int C_LINE    = Color.parseColor("#E2E8F0");
    private static final int C_TINT    = Color.parseColor("#E8EEFF");
    private static final int C_ACCENT  = Color.parseColor("#2563EB");
    private static final int C_ERR     = Color.parseColor("#DC2626");

    /** Longest accepted entry: TOTP is 6, a server-pushed PIN may be up to 8. */
    private static final int MAX_LEN = 8;
    /** Below this there is nothing worth checking, so Unlock stays disabled. */
    private static final int MIN_LEN = 4;

    private final StringBuilder entry = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView dots;
    private TextView status;
    private TextView unlockBtn;
    private Runnable lockoutTick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(""); // suppress the app label in the window title
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0.55f);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        if (!KioskExit.isEnabled(this)) {
            Log.w(TAG, "offline exit not enabled/provisioned; dismissing");
            finish();
            return;
        }
        setContentView(buildView());
        refreshLockout();
    }

    @Override
    protected void onDestroy() {
        if (lockoutTick != null) ui.removeCallbacks(lockoutTick);
        super.onDestroy();
    }

    private View buildView() {
        // Outer transparent wrapper centres the card.
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(20), dp(20), dp(20), dp(20));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(24), dp(24), dp(18));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(C_BG);
        cardBg.setCornerRadius(dp(24));
        card.setBackground(cardBg);
        card.setElevation(dp(8));
        card.setLayoutParams(new LinearLayout.LayoutParams(dp(300),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LockMark mark = new LockMark(this);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        markLp.gravity = Gravity.CENTER_HORIZONTAL;
        mark.setLayoutParams(markLp);
        card.addView(mark);

        card.addView(label("Exit kiosk mode", C_TITLE, 20, Typeface.DEFAULT_BOLD, dp(12), 0));
        card.addView(label("Enter the exit PIN to unlock this device.",
                C_SUB, 13, Typeface.DEFAULT, dp(4), dp(16)));

        // Entry display: one dot per digit, so the code is never on screen to be read off.
        dots = new TextView(this);
        dots.setGravity(Gravity.CENTER);
        dots.setTextSize(20);
        dots.setLetterSpacing(0.45f);
        dots.setTextColor(C_TITLE);
        dots.setPadding(dp(14), dp(13), dp(14), dp(13));
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(C_FIELD);
        fieldBg.setCornerRadius(dp(12));
        dots.setBackground(fieldBg);
        card.addView(dots);

        status = new TextView(this);
        status.setTextColor(C_ERR);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, 0);
        card.addView(status);

        card.addView(buildKeypad());

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(12), 0, 0);

        TextView cancel = pill("Cancel", Color.TRANSPARENT, C_SUB);
        cancel.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        cLp.rightMargin = dp(8);
        cancel.setLayoutParams(cLp);
        btnRow.addView(cancel);

        unlockBtn = pill("Unlock", C_ACCENT, Color.WHITE);
        unlockBtn.setOnClickListener(v -> attempt());
        unlockBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1.3f));
        btnRow.addView(unlockBtn);
        card.addView(btnRow);

        // Which device this is: a technician standing over a wall of kiosks needs it, and
        // it is the same serial the dashboard shows.
        String serial = SystemPropertiesProxy.get("ro.serialno", "");
        if (!serial.isEmpty()) {
            card.addView(label(serial, C_SUB, 10.5f, Typeface.DEFAULT, dp(12), 0));
        }

        outer.addView(card);
        updateEntry();
        return outer;
    }

    /** 3×4 grid: 1-9, then clear / 0 / backspace. */
    private View buildKeypad() {
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setPadding(0, dp(12), 0, 0);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"C", "0", "⌫"}};
        for (String[] row : rows) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : row) {
                final String k = key;
                TextView b = pill(k, C_KEY, C_TITLE);
                b.setTextSize(18);
                if ("C".equals(k)) {
                    b.setTextColor(C_SUB);
                    b.setOnClickListener(v -> { entry.setLength(0); status.setText(""); updateEntry(); });
                } else if ("⌫".equals(k)) {
                    b.setTextColor(C_SUB);
                    b.setOnClickListener(v -> {
                        if (entry.length() > 0) entry.setLength(entry.length() - 1);
                        updateEntry();
                    });
                } else {
                    b.setOnClickListener(v -> {
                        if (entry.length() < MAX_LEN) entry.append(k);
                        status.setText("");
                        updateEntry();
                    });
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                b.setLayoutParams(lp);
                r.addView(b);
            }
            pad.addView(r);
        }
        return pad;
    }

    private TextView label(String text, int color, float size, Typeface face, int top, int bottom) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(face);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, top, 0, bottom);
        return t;
    }

    private TextView pill(String text, int bg, int fg) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);            // so a remote / D-pad can drive the keypad
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(12));
        if (bg == Color.TRANSPARENT || bg == C_KEY) d.setStroke(dp(1), C_LINE);
        b.setBackground(d);
        return b;
    }

    /** Redraws the dot display and enables Unlock once the entry is long enough. */
    private void updateEntry() {
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < entry.length(); i++) shown.append('●');
        dots.setText(shown.length() == 0 ? "—" : shown.toString());
        boolean ready = entry.length() >= MIN_LEN && !KioskExit.isLockedOut(this);
        unlockBtn.setAlpha(ready ? 1f : 0.45f);
        unlockBtn.setEnabled(ready);
    }

    /** While locked out, count the remaining seconds down in place rather than once. */
    private void refreshLockout() {
        if (lockoutTick != null) ui.removeCallbacks(lockoutTick);
        lockoutTick = new Runnable() {
            @Override public void run() {
                if (KioskExit.isLockedOut(UnlockActivity.this)) {
                    long s = KioskExit.lockoutRemainingMs(UnlockActivity.this) / 1000 + 1;
                    status.setText("Too many attempts — wait " + s + "s.");
                    updateEntry();
                    ui.postDelayed(this, 1000);
                } else if (status.getText().toString().startsWith("Too many")) {
                    status.setText("");
                    updateEntry();
                }
            }
        };
        ui.post(lockoutTick);
    }

    private void attempt() {
        if (KioskExit.isLockedOut(this)) {
            refreshLockout();
            return;
        }
        String code = entry.toString();
        entry.setLength(0);
        if (!KioskExit.verify(this, code)) {
            updateEntry();
            if (KioskExit.isLockedOut(this)) {
                refreshLockout();
            } else {
                status.setText("Incorrect PIN. Try again.");
            }
            return;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, MdmAdminReceiver.class);
            KioskManager.suspendLocally(this, dpm, admin);
        } catch (Exception e) {
            Log.e(TAG, "suspendLocally error: " + e.getMessage());
        }
        // Tell the service to report the exit now. Without this the suspension only rode
        // the next scheduled check-in, so the dashboard showed the device as locked long
        // after the technician had unlocked it in front of the customer.
        try {
            Intent report = new Intent(this, MdmService.class);
            report.setAction(MdmService.ACTION_KIOSK_EXITED);
            startService(report);
        } catch (Exception e) {
            Log.e(TAG, "exit report error: " + e.getMessage());
        }
        Toast.makeText(this, "Kiosk mode exited", Toast.LENGTH_LONG).show();
        finish();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float dpf(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** A padlock: tinted disc, shackle arc, body with a keyhole. No font involved. */
    private final class LockMark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LockMark(Context ctx) { super(ctx); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(C_TINT);
            c.drawCircle(cx, h / 2f, Math.min(w, h) / 2f, paint);

            paint.setColor(C_ACCENT);
            // Shackle: an open arc sitting on the body.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpf(2f));
            float sw = dpf(11f), top = h * 0.28f;
            c.drawArc(new RectF(cx - sw / 2f, top, cx + sw / 2f, top + sw), 180f, 180f, false, paint);

            // Body.
            paint.setStyle(Paint.Style.FILL);
            float bw = dpf(18f), bh = dpf(13f), bt = top + sw / 2f + dpf(1f);
            c.drawRoundRect(new RectF(cx - bw / 2f, bt, cx + bw / 2f, bt + bh),
                    dpf(3f), dpf(3f), paint);

            // Keyhole.
            paint.setColor(C_TINT);
            c.drawCircle(cx, bt + bh * 0.42f, dpf(2f), paint);
        }
    }
}
