package com.aioapp.mdm;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The staff PIN pad that takes a tablet out of lock mode, with no server connection needed:
 * full screen (A2, picked 20 Oct 2026), the venue's logo and name on top, "Staff only · Enter
 * the venue PIN", dots that fill as digits go in, round 74 dp keys with a coral unlock key
 * next to 0. A right PIN shows "Staff mode on" for a moment, then the tablet leaves lock task
 * and {@link StaffPill} stays up until someone goes back to guest mode.
 *
 * Opened by the firmware's corner + Power gesture (MdmService.onKioskExitRequested). Never
 * opens in lost mode — only the dashboard lifts that (see LostMode).
 *
 * Entry is through a keypad this activity draws, not the system IME: lock task can be run
 * with the keyboard suppressed, a menu board is driven by a remote with no IME at all, and
 * the keys are focusable so a D-pad reaches them. Nothing here depends on a theme, a layout
 * resource, or a font the image may not carry (Poppins falls back to the default face).
 */
public class UnlockActivity extends Activity {
    private static final String TAG = "UnlockActivity";

    private static final int C_BG_TOP = Color.parseColor("#3A2B5E");
    private static final int C_BG = Color.parseColor("#1B1530");
    private static final int C_TEXT = Color.WHITE;
    private static final int C_KICKER = Color.parseColor("#FFB199");
    private static final int C_KEY = Color.argb(26, 255, 255, 255);
    private static final int C_KEY_LINE = Color.argb(36, 255, 255, 255);
    private static final int C_CORAL = Color.parseColor("#F9674E");
    private static final int C_CORAL_OFF = Color.argb(72, 249, 103, 78);
    private static final int C_ERR = Color.parseColor("#FFC1B4");
    private static final int C_OK = Color.parseColor("#5BE38F");
    private static final Typeface FONT = Typeface.create("aio-sans", Typeface.NORMAL);
    private static final Typeface FONT_MEDIUM = Typeface.create("aio-sans-medium", Typeface.NORMAL);

    /** Longest accepted entry: a server-pushed PIN may be up to 8. */
    private static final int MAX_LEN = 8;
    /** Below this there is nothing worth checking, so Unlock stays disabled. */
    private static final int MIN_LEN = 4;
    /** "Staff mode on" stays this long before the tablet leaves lock task. */
    private static final long SUCCESS_MS = 900;

    private final StringBuilder entry = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Dots dots;
    private TextView status;
    private View unlockBtn;
    private Runnable lockoutTick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(""); // suppress the app label in the window title
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (LostMode.isOn(this) || !KioskExit.isEnabled(this)) {
            Log.w(TAG, "offline exit not enabled/provisioned; dismissing");
            finish();
            return;
        }
        setContentView(buildView());
        hideSystemBars();
        refreshLockout();
    }

    /**
     * Full screen over the system bars (lock task keeps the nav bar's Back): Cancel and the
     * serial sat under it. Whatever bar still shows pads the content instead (see root()).
     */
    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.hide(WindowInsets.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    protected void onDestroy() {
        if (lockoutTick != null) ui.removeCallbacks(lockoutTick);
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private View root(LinearLayout column) {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {C_BG_TOP, C_BG}));
        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        final int top = column.getPaddingTop(), bottom = column.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            column.setPadding(column.getPaddingLeft(), top + bars.top,
                    column.getPaddingRight(), bottom + bars.bottom);
            return insets;
        });
        return root;
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(28), dp(24), dp(16));
        col.addView(venueRow());
        return col;
    }

    private View buildView() {
        LinearLayout col = column();

        TextView kicker = label("STAFF ONLY", C_KICKER, 12, FONT_MEDIUM);
        kicker.setLetterSpacing(0.12f);
        kicker.setCompoundDrawablePadding(dp(6));
        col.addView(kicker, topMargin(dp(28)));
        col.addView(label("Enter the venue PIN", C_TEXT, 27, FONT_MEDIUM), topMargin(dp(6)));

        dots = new Dots(this);
        col.addView(dots, sized(LinearLayout.LayoutParams.MATCH_PARENT, dp(16), dp(20)));

        status = label("", C_ERR, 13, FONT);
        LinearLayout.LayoutParams sLp = topMargin(dp(10));
        status.setMinHeight(dp(18));
        col.addView(status, sLp);

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        col.addView(buildKeypad());

        TextView cancel = label("Cancel", C_TEXT, 16, FONT_MEDIUM);
        cancel.setPadding(dp(20), dp(8), dp(20), dp(8));
        cancel.setFocusable(true);
        cancel.setClickable(true);
        cancel.setOnClickListener(v -> finish());
        col.addView(cancel, topMargin(dp(16)));

        String serial = SystemPropertiesProxy.get("ro.serialno", "");
        if (!serial.isEmpty()) {
            TextView s = label(serial, C_TEXT, 10, FONT);
            s.setAlpha(0.45f);
            s.setLetterSpacing(0.04f);
            col.addView(s, topMargin(dp(4)));
        }
        updateEntry();
        return root(col);
    }

    /** The venue's logo (or the AIO tile) and its name: whose PIN this wants. */
    private View venueRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GuestInfo g = GuestInfo.load(this);
        Bitmap logo = g.hasRestaurant() ? VenueIcon.icon(this) : null;

        FrameLayout tile = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(9));
        if (logo != null) {
            bg.setColor(Color.WHITE);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(logo);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(4), dp(4), dp(4), dp(4));
            tile.addView(iv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            bg.setOrientation(GradientDrawable.Orientation.TL_BR);
            bg.setColors(new int[] {C_CORAL, Color.parseColor("#7A80F6")});
            TextView mark = label("AIO", C_TEXT, 10, FONT_MEDIUM);
            tile.addView(mark, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        tile.setBackground(bg);
        tile.setClipToOutline(true);
        row.addView(tile, new LinearLayout.LayoutParams(dp(34), dp(34)));

        TextView name = label(g.hasRestaurant() ? g.restaurantName : "AIO", C_TEXT, 13, FONT_MEDIUM);
        name.setAlpha(0.9f);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nLp.leftMargin = dp(10);
        row.addView(name, nLp);
        return row;
    }

    /** 3x4 grid of round keys: 1-9, then backspace / 0 / unlock. */
    private View buildKeypad() {
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"del", "0", "ok"}};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final String k = rows[r][c];
                View key;
                if ("del".equals(k)) {
                    TextView t = label("⌫", C_TEXT, 26, FONT);
                    t.setContentDescription("Delete");
                    key = t;
                    key.setOnClickListener(v -> {
                        if (entry.length() > 0) entry.setLength(entry.length() - 1);
                        updateEntry();
                    });
                    key.setOnLongClickListener(v -> {
                        entry.setLength(0);
                        updateEntry();
                        return true;
                    });
                } else if ("ok".equals(k)) {
                    key = new UnlockKey(this);
                    key.setContentDescription("Unlock");
                    key.setOnClickListener(v -> attempt());
                    unlockBtn = key;
                } else {
                    TextView t = label(k, C_TEXT, 30, FONT);
                    t.setBackground(round(C_KEY, C_KEY_LINE));
                    key = t;
                    key.setOnClickListener(v -> {
                        if (entry.length() < MAX_LEN) entry.append(k);
                        status.setText("");
                        updateEntry();
                    });
                }
                key.setClickable(true);
                key.setFocusable(true); // so a remote / D-pad can drive the keypad
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(74), dp(74));
                if (c > 0) lp.leftMargin = dp(32);
                line.addView(key, lp);
            }
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (r > 0) lineLp.topMargin = dp(12);
            lineLp.gravity = Gravity.CENTER_HORIZONTAL;
            pad.addView(line, lineLp);
        }
        return pad;
    }

    private GradientDrawable round(int fill, int line) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (line != 0) d.setStroke(dp(1), line);
        return d;
    }

    private TextView label(String text, int color, float sp, Typeface face) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout.LayoutParams topMargin(int top) {
        return sized(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, top);
    }

    private LinearLayout.LayoutParams sized(int w, int h, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = top;
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    /** Redraws the dots and enables Unlock once the entry is long enough. */
    private void updateEntry() {
        dots.setCount(entry.length());
        boolean ready = entry.length() >= MIN_LEN && !KioskExit.isLockedOut(this);
        unlockBtn.setEnabled(ready);
        unlockBtn.invalidate();
    }

    /** While locked out, count the remaining seconds down in place rather than once. */
    private void refreshLockout() {
        if (lockoutTick != null) ui.removeCallbacks(lockoutTick);
        lockoutTick = new Runnable() {
            @Override public void run() {
                if (KioskExit.isLockedOut(UnlockActivity.this)) {
                    long s = KioskExit.lockoutRemainingMs(UnlockActivity.this) / 1000 + 1;
                    status.setText(String.format(java.util.Locale.ROOT,
                            "Too many tries. Try again in %d:%02d", s / 60, s % 60));
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
            dots.shake();
            if (KioskExit.isLockedOut(this)) {
                refreshLockout();
            } else {
                status.setText("Wrong PIN. Try again.");
            }
            return;
        }
        showStaffModeOn();
        ui.postDelayed(this::exitLockMode, SUCCESS_MS);
    }

    /** A green tick and "Staff mode on" while the tablet gets ready to leave lock task. */
    private void showStaffModeOn() {
        LinearLayout col = column();
        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView tick = label("✓", C_OK, 46, FONT_MEDIUM);
        tick.setBackground(round(Color.argb(46, 91, 227, 143), 0));
        col.addView(tick, sized(dp(96), dp(96), 0));
        col.addView(label("Staff mode on", C_TEXT, 27, FONT_MEDIUM), topMargin(dp(20)));
        TextView sub = label("Guest mode is off until you switch it back.", C_TEXT, 15, FONT);
        sub.setAlpha(0.8f);
        col.addView(sub, topMargin(dp(8)));
        View bottom = new View(this);
        col.addView(bottom, new LinearLayout.LayoutParams(1, 0, 1.3f));
        setContentView(root(col));
    }

    private void exitLockMode() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, MdmAdminReceiver.class);
            KioskManager.suspendLocally(this, dpm, admin);
        } catch (Exception e) {
            Log.e(TAG, "suspendLocally error: " + e.getMessage());
        }
        // Tell the service to report the exit now (and put the staff pill up). Without this
        // the suspension only rode the next scheduled check-in, so the dashboard showed the
        // device as locked long after the technician had unlocked it in front of the customer.
        try {
            Intent report = new Intent(this, MdmService.class);
            report.setAction(MdmService.ACTION_KIOSK_EXITED);
            startService(report);
        } catch (Exception e) {
            Log.e(TAG, "exit report error: " + e.getMessage());
        }
        finish();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float dpf(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** One outlined dot per digit (at least 4), filled for each digit entered. */
    private final class Dots extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int count;

        Dots(Context ctx) { super(ctx); }

        void setCount(int n) {
            count = n;
            invalidate();
        }

        void shake() {
            animate().cancel();
            setTranslationX(0);
            float d = dpf(10);
            animate().translationX(d).setDuration(50).withEndAction(() ->
                    animate().translationX(-d).setDuration(80).withEndAction(() ->
                            animate().translationX(d / 2).setDuration(70).withEndAction(() ->
                                    animate().translationX(0).setDuration(60).start()).start()).start()).start();
        }

        @Override protected void onDraw(Canvas c) {
            int n = Math.max(MIN_LEN, count);
            float r = dpf(7.5f), gap = dpf(16);
            float total = n * 2 * r + (n - 1) * gap;
            float x = (getWidth() - total) / 2f + r, y = getHeight() / 2f;
            for (int i = 0; i < n; i++) {
                if (i < count) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(C_TEXT);
                } else {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dpf(1.5f));
                    paint.setColor(Color.argb(140, 255, 255, 255));
                }
                c.drawCircle(x, y, i < count ? r : r - dpf(0.75f), paint);
                x += 2 * r + gap;
            }
        }
    }

    /** The coral round key with an open padlock; pale and inert until 4 digits are in. */
    private final class UnlockKey extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        UnlockKey(Context ctx) { super(ctx); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            boolean on = isEnabled();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? C_CORAL : C_CORAL_OFF);
            c.drawCircle(cx, cy, Math.min(w, h) / 2f, paint);

            int ink = on ? Color.WHITE : Color.argb(115, 255, 255, 255);
            float bw = dpf(18), bh = dpf(13), bt = cy - dpf(1);
            // Shackle, swung open to the left.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpf(2.2f));
            paint.setColor(ink);
            float sw = dpf(11);
            float sl = cx - bw / 2f - dpf(3);
            c.drawArc(new RectF(sl, bt - sw, sl + sw, bt), 180f, 180f, false, paint);
            c.drawLine(sl + sw, bt - sw / 2f, sl + sw, bt, paint);
            // Body with a keyhole.
            paint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(new RectF(cx - bw / 2f, bt, cx + bw / 2f, bt + bh), dpf(3), dpf(3), paint);
            paint.setColor(on ? C_CORAL : C_BG);
            c.drawCircle(cx, bt + bh * 0.45f, dpf(2), paint);
        }
    }
}
