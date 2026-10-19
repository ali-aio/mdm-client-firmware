package com.aioapp.mdm;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The lost-mode screen (see {@link LostMode}): whose device this is and what to do with it.
 * Nothing on it can be tapped — touches are swallowed and Back does nothing; lock task keeps
 * home, recents, notifications and the status bar away. The five-Power-press exit prompt does
 * not open while lost; only the dashboard lifts it.
 *
 * L1 (picked 19 Oct 2026, client 1.13.4): calm and branded. A dark radial background, the AIO
 * mark top-left, a lock in a soft coral square, the title, the venue (name and icon, see
 * {@link VenueIcon}), the request, the phone in its own box only when there is one, and a
 * quiet footer with the serial and how staff get it back.
 *
 * Laid out in code like UnlockActivity, with no theme, layout or font the image may lack.
 * The block is centred and width-capped, so it reads the same on the T7 in landscape and on
 * a portrait kiosk panel. The screen is allowed to sleep: a lost T7 is on battery, and
 * keeping the panel lit would run it flat long before anyone finds it.
 */
public class LostActivity extends Activity {
    private static final int C_BG_TOP = Color.parseColor("#1D2A4A");
    private static final int C_BG = Color.parseColor("#0E1424");
    private static final int C_TITLE = Color.parseColor("#FFFFFF");
    private static final int C_BODY = Color.parseColor("#CFD6E4");
    private static final int C_DIM = Color.parseColor("#6F7B93");
    private static final int C_MARK = Color.parseColor("#C9D2E6");
    private static final int C_CORAL = Color.parseColor("#F9674E");
    private static final int C_CORAL_SOFT = Color.argb(36, 249, 103, 78);
    private static final int C_CHIP = Color.argb(20, 255, 255, 255);
    private static final int C_BOX = Color.argb(18, 255, 255, 255);

    private static final String DEFAULT_MESSAGE = "Please hand it to a member of staff.";

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
        boolean t7 = MdmProduct.KEY_T7.equals(MdmProduct.detect().key());
        String venue = VenueIcon.venue(this);
        String phone = LostMode.phone(this);

        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        bg.setColors(new int[]{C_BG_TOP, C_BG});
        bg.setGradientCenter(0.5f, 0f);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        bg.setGradientRadius(Math.max(dm.widthPixels, dm.heightPixels) * 0.9f);
        root.setBackground(bg);

        // The AIO mark, top-left.
        SpannableString mark = new SpannableString(t7 ? "AIO · Tableside" : "AIO");
        mark.setSpan(new ForegroundColorSpan(C_CORAL), 0, 3, 0);
        TextView mk = text("", C_MARK, 14, Typeface.DEFAULT_BOLD, 0);
        mk.setText(mark);
        mk.setGravity(Gravity.START);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        mlp.setMargins(dp(24), dp(20), dp(24), 0);
        root.addView(mk, mlp);

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        int width = Math.min(dp(520), dm.widthPixels - dp(64));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        blp.bottomMargin = dp(24); // leave the footer its room
        block.setLayoutParams(blp);

        // The lock, in a soft coral square.
        FrameLayout lockBox = new FrameLayout(this);
        lockBox.setBackground(rounded(C_CORAL_SOFT, dp(18)));
        lockBox.addView(new LockGlyph(this, C_CORAL), new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        block.addView(lockBox, new LinearLayout.LayoutParams(dp(60), dp(60)));

        block.addView(text(t7 ? "This tablet is lost" : "This device is lost", C_TITLE, 28, Typeface.DEFAULT_BOLD, dp(14)));

        if (!venue.isEmpty()) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(rounded(C_CHIP, dp(99)));
            Bitmap icon = VenueIcon.icon(this);
            int padL = dp(14);
            if (icon != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(icon);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackground(rounded(Color.WHITE, dp(7)));
                iv.setPadding(dp(2), dp(2), dp(2), dp(2));
                chip.addView(iv, new LinearLayout.LayoutParams(dp(26), dp(26)));
                padL = dp(5);
            }
            TextView vn = text(venue, C_TITLE, 15, Typeface.DEFAULT_BOLD, 0);
            vn.setSingleLine(true);
            vn.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            vlp.leftMargin = icon != null ? dp(8) : 0;
            chip.addView(vn, vlp);
            chip.setPadding(padL, dp(5), dp(14), dp(5));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(12);
            block.addView(chip, clp);
        }

        block.addView(text(request(LostMode.message(this), venue), C_BODY, 17, Typeface.DEFAULT, dp(16)));

        if (!phone.isEmpty()) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setBackground(rounded(C_BOX, dp(16)));
            box.setPadding(dp(22), dp(10), dp(22), dp(12));
            box.addView(text("or call AIO", Color.parseColor("#9AA6BF"), 12, Typeface.DEFAULT, 0));
            box.addView(text(phone, C_TITLE, 26, Typeface.DEFAULT_BOLD, dp(2)));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.topMargin = dp(16);
            block.addView(box, plp);
        }
        root.addView(block);

        // Footer: the serial, and how staff get it back.
        String serial = LostMode.serial(this);
        TextView foot = text((serial.isEmpty() ? "" : "Serial " + serial + "\n")
                + "Staff: it unlocks from the dashboard once it is back.", C_DIM, 12, Typeface.DEFAULT, 0);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        flp.setMargins(dp(24), 0, dp(24), dp(18));
        root.addView(foot, flp);
        return root;
    }

    /**
     * The request, without what the screen already says: "It belongs to <venue>." when the
     * venue is drawn above it (servers keep that sentence for clients before 1.13.4), and a
     * trailing "or call:" — the number has its own box, and with no number it led nowhere.
     */
    static String request(String msg, String venue) {
        String m = msg == null ? "" : msg.trim();
        if (!venue.isEmpty()) {
            for (String lead : new String[]{"It belongs to " + venue + ".", "This device belongs to " + venue + "."}) {
                if (m.startsWith(lead)) m = m.substring(lead.length()).trim();
            }
        }
        if (m.endsWith("or call:")) {
            m = m.substring(0, m.length() - "or call:".length()).trim();
            if (m.endsWith(",")) m = m.substring(0, m.length() - 1).trim();
            if (!m.isEmpty() && !m.endsWith(".")) m += ".";
        }
        return m.isEmpty() ? DEFAULT_MESSAGE : m;
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** A padlock drawn on a canvas: no drawable resource to go missing from the image. */
    private static final class LockGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LockGlyph(Context c, int color) {
            super(c);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), u = Math.min(w, h) / 24f;
            paint.setStrokeWidth(2f * u);
            // Body: x 5..19, y 11..21, corner 2; shackle: an arc over x 8..16 from y 11 up to 3.
            canvas.drawRoundRect(new RectF(5 * u, 11 * u, 19 * u, 21 * u), 2 * u, 2 * u, paint);
            Path p = new Path();
            p.moveTo(8 * u, 11 * u);
            p.lineTo(8 * u, 7 * u);
            p.arcTo(new RectF(8 * u, 3 * u, 16 * u, 11 * u), 180, 180);
            p.lineTo(16 * u, 11 * u);
            canvas.drawPath(p, paint);
        }
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
