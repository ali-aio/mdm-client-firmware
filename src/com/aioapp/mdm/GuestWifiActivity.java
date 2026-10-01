package com.aioapp.mdm;

import android.app.Activity;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * The guest Wi-Fi card a guest opens from the home screen (the Wi-Fi widget, or the Wi-Fi
 * chip in the launcher's glance header): the network name, its password, and a QR code
 * their phone's camera joins from. Exported so the launcher can open it; it shows only what
 * the venue hands every guest anyway.
 *
 * Follows changes while open, so a password changed on the dashboard is not left on screen.
 */
public class GuestWifiActivity extends Activity {
    private static final String TAG = "GuestWifiActivity";

    private ContentObserver observer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.guest_wifi_activity);
        findViewById(R.id.guest_wifi_done).setOnClickListener(v -> finish());
        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                bind();
            }
        };
        getContentResolver().registerContentObserver(StatusProvider.ROOT_URI, true, observer);
        bind();
    }

    @Override
    protected void onDestroy() {
        if (observer != null) getContentResolver().unregisterContentObserver(observer);
        super.onDestroy();
    }

    private void bind() {
        GuestInfo g = GuestInfo.load(this);
        TextView ssid = findViewById(R.id.guest_wifi_ssid);
        TextView pass = findViewById(R.id.guest_wifi_password);
        ImageView qr = findViewById(R.id.guest_wifi_qr);
        View details = findViewById(R.id.guest_wifi_details);
        TextView hint = findViewById(R.id.guest_wifi_hint);

        if (!g.hasWifi()) {
            details.setVisibility(View.GONE);
            qr.setVisibility(View.GONE);
            hint.setText(R.string.guest_wifi_ask_staff_long);
            return;
        }
        details.setVisibility(View.VISIBLE);
        ssid.setText(g.wifiSsid);
        if (GuestInfo.SEC_NOPASS.equals(g.wifiSecurity)) {
            pass.setText(R.string.guest_wifi_open_network);
        } else {
            pass.setText(g.wifiPassword);
        }
        Bitmap bmp = qrBitmap(g.wifiQrPayload(), getResources().getDimensionPixelSize(R.dimen.guest_qr_size));
        if (bmp == null) {
            qr.setVisibility(View.GONE);
            hint.setText(R.string.guest_wifi_join_by_name);
            return;
        }
        // Scaled without filtering, so the modules stay sharp at any size.
        BitmapDrawable d = new BitmapDrawable(getResources(), bmp);
        d.setFilterBitmap(false);
        qr.setImageDrawable(d);
        qr.setVisibility(View.VISIBLE);
        hint.setText(R.string.guest_wifi_scan_hint);
    }

    /**
     * Renders a QR code as black modules on white, each module an integer number of pixels
     * (the largest that fits targetPx). Null when the payload can't be encoded.
     */
    static Bitmap qrBitmap(String payload, int targetPx) {
        if (payload == null || payload.isEmpty()) return null;
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        // ISO-8859-1 by default; a name or password outside ASCII goes as UTF-8 (with the
        // ECI marker scanners use to read it as such).
        if (!isAscii(payload)) hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        try {
            BitMatrix m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = m.getWidth();
            int h = m.getHeight();
            int scale = Math.max(1, targetPx / Math.max(w, h));
            int pw = w * scale;
            int ph = h * scale;
            int[] px = new int[pw * ph];
            for (int y = 0; y < ph; y++) {
                int row = y * pw;
                int my = y / scale;
                for (int x = 0; x < pw; x++) {
                    px[row + x] = m.get(x / scale, my) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888);
        } catch (WriterException | IllegalArgumentException e) {
            Log.w(TAG, "could not draw the Wi-Fi QR code: " + e.getMessage());
            return null;
        }
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7e) return false;
        }
        return true;
    }
}
