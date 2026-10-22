package com.aioapp.mdm;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Point;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

import org.json.JSONObject;

/**
 * Remote control input from the dashboard (client 1.16.0; the remote page's R1, 22 Oct
 * 2026: "make remote control like scrcpy, so that keyboard nav and keyboard keys work").
 *
 * Injected the way scrcpy does it, so apps treat the operator's keyboard as a real one:
 * keys carry the source (keyboard), the meta state (Shift/Ctrl/Alt/CapsLock) and the
 * repeat count, which is what lets Tab move focus, Shift+Tab go back and Ctrl+A select.
 *
 * <pre>
 *   touch   {x, y in 0..1, event: down|move|up}
 *   key     {keycode, event: down|up, meta, repeat}
 *   text    {text}       typed through the virtual keyboard's character map; what it
 *                        cannot type (accents, emoji) goes through the clipboard + PASTE
 *   scroll  {x, y, v, h} mouse wheel, in notches (v &gt; 0 scrolls up)
 * </pre>
 *
 * The client reports {@code remote_input: 2} so the dashboard knows it may send all of
 * this; older clients only ever got touch and plain keys.
 */
final class RemoteInput {
    private static final String TAG = "RemoteInput";
    /** What the dashboard reads as extra.remote_input. */
    static final int LEVEL = 2;

    private static long touchDownTime;
    private static KeyCharacterMap charMap;

    private RemoteInput() {}

    static void handle(Context ctx, JSONObject msg) {
        InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
        if (im == null) return;
        switch (msg.optString("action", "touch")) {
            case "touch":
                touch(ctx, im, msg);
                break;
            case "key":
                key(im, msg.optInt("keycode", 0), "down".equals(msg.optString("event", "down")),
                        msg.optInt("meta", 0), Math.max(0, msg.optInt("repeat", 0)));
                break;
            case "text":
                text(ctx, im, msg.optString("text", ""));
                break;
            case "scroll":
                scroll(ctx, im, msg);
                break;
            default:
                Log.d(TAG, "unknown action " + msg.optString("action"));
        }
    }

    private static Point screenSize(Context ctx) {
        Point size = new Point(1080, 1920);
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            Display d = wm.getDefaultDisplay();
            if (d != null) d.getRealSize(size);
        }
        return size;
    }

    private static void touch(Context ctx, InputManager im, JSONObject msg) {
        Point s = screenSize(ctx);
        float x = (float) msg.optDouble("x", 0.5) * s.x;
        float y = (float) msg.optDouble("y", 0.5) * s.y;
        int action;
        switch (msg.optString("event", "down")) {
            case "move": action = MotionEvent.ACTION_MOVE; break;
            case "up":   action = MotionEvent.ACTION_UP; break;
            default:     action = MotionEvent.ACTION_DOWN; break;
        }
        long now = SystemClock.uptimeMillis();
        // One gesture shares its down time; a fresh one per event made long-press and
        // drags look like a stream of separate taps to some apps.
        if (action == MotionEvent.ACTION_DOWN || touchDownTime == 0) touchDownTime = now;
        MotionEvent me = MotionEvent.obtain(touchDownTime, now, action, x, y, 0);
        me.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        im.injectInputEvent(me, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        me.recycle();
        if (action == MotionEvent.ACTION_UP) touchDownTime = 0;
    }

    private static void key(InputManager im, int keycode, boolean down, int meta, int repeat) {
        if (keycode <= 0 || keycode > KeyEvent.getMaxKeyCode()) return;
        long now = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(now, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keycode,
                repeat, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        im.injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    private static void text(Context ctx, InputManager im, String text) {
        if (text.isEmpty()) return;
        if (charMap == null) charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        StringBuilder pending = new StringBuilder(); // characters the map cannot type
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            KeyEvent[] evs = n == 1 ? charMap.getEvents(new char[] {text.charAt(i)}) : null;
            if (evs == null) {
                pending.appendCodePoint(cp);
            } else {
                if (pending.length() > 0) { paste(ctx, im, pending.toString()); pending.setLength(0); }
                for (KeyEvent e : evs) {
                    e.setSource(InputDevice.SOURCE_KEYBOARD);
                    im.injectInputEvent(e, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                }
            }
            i += n;
        }
        if (pending.length() > 0) paste(ctx, im, pending.toString());
    }

    /** Types what the key map cannot (accents, emoji, other scripts) by pasting it. */
    private static void paste(Context ctx, InputManager im, String s) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("AIO MDM remote", s));
        key(im, KeyEvent.KEYCODE_PASTE, true, 0, 0);
        key(im, KeyEvent.KEYCODE_PASTE, false, 0, 0);
    }

    private static void scroll(Context ctx, InputManager im, JSONObject msg) {
        Point s = screenSize(ctx);
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = (float) msg.optDouble("x", 0.5) * s.x;
        coords[0].y = (float) msg.optDouble("y", 0.5) * s.y;
        coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, (float) Math.max(-8, Math.min(8, msg.optDouble("v", 0))));
        coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, (float) Math.max(-8, Math.min(8, msg.optDouble("h", 0))));
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        im.injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        ev.recycle();
    }

    /** The device's clipboard as text, "" when empty or unreadable (clipboard_get). */
    static String clipboardText(Context ctx) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence t = clip.getItemAt(0).coerceToText(ctx);
            return t == null ? "" : t.toString();
        } catch (Exception e) {
            Log.w(TAG, "clipboard read failed: " + e.getMessage());
            return "";
        }
    }
}
