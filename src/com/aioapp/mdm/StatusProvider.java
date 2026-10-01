package com.aioapp.mdm;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code content://com.aioapp.status} — the guest info ({@link GuestInfo}) for the AIO
 * launcher's glance header and the lock screen's welcome line. Read-only, and readable by
 * anyone: everything in it is what the tablet shows any guest anyway (the Wi-Fi password is
 * deliberately not in it — only {@link GuestWifiActivity} shows that, on a tap).
 *
 * <ul>
 * <li>{@code /glance}: chips — {@code icon} (a name the launcher maps to a glyph),
 *     {@code text}, {@code intent} (an {@code intent:} URI or null) and {@code priority}
 *     (higher first; the launcher asks for {@code "priority DESC"}, and ascending is honoured
 *     too). One row each for the restaurant, the table and the guest Wi-Fi, when set. With
 *     nothing set the cursor is empty, not null: the launcher shows its own chips (device
 *     name, network, battery) for an empty answer exactly as for a missing provider, and an
 *     empty cursor is not logged as a failure.</li>
 * <li>{@code /welcome}: exactly one row — {@code restaurant_name}, {@code table_label},
 *     {@code wifi_ssid}, each "" when unset.</li>
 * </ul>
 *
 * Exported and directBootAware: the launcher and SystemUI query it from the start of a boot,
 * and {@link GuestInfo} keeps its data in device-protected storage for that reason.
 * Changes are announced on {@link #ROOT_URI}, which reaches observers of both paths.
 */
public class StatusProvider extends ContentProvider {
    static final String AUTHORITY = "com.aioapp.status";
    static final Uri ROOT_URI = Uri.parse("content://" + AUTHORITY);

    static final String[] GLANCE_COLUMNS = {"icon", "text", "intent", "priority"};
    static final String[] WELCOME_COLUMNS = {"restaurant_name", "table_label", "wifi_ssid"};

    static final int PRIORITY_RESTAURANT = 30;
    static final int PRIORITY_TABLE = 20;
    static final int PRIORITY_WIFI = 10;

    private static final int GLANCE = 1;
    private static final int WELCOME = 2;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        MATCHER.addURI(AUTHORITY, "glance", GLANCE);
        MATCHER.addURI(AUTHORITY, "welcome", WELCOME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        switch (MATCHER.match(uri)) {
            case GLANCE:
                return glance(GuestInfo.load(getContext()), sortOrder);
            case WELCOME:
                return welcome(GuestInfo.load(getContext()));
            default:
                return null;
        }
    }

    private static final class Chip {
        final String icon;
        final String text;
        final String intent;
        final int priority;

        Chip(String icon, String text, String intent, int priority) {
            this.icon = icon;
            this.text = text;
            this.intent = intent;
            this.priority = priority;
        }
    }

    private Cursor glance(GuestInfo g, String sortOrder) {
        List<Chip> chips = new ArrayList<>(3);
        if (g.hasRestaurant()) {
            chips.add(new Chip("storefront", g.restaurantName, null, PRIORITY_RESTAURANT));
        }
        if (g.hasTable()) {
            chips.add(new Chip("table", g.tableLabel, null, PRIORITY_TABLE));
        }
        if (g.hasWifi()) {
            Intent open = new Intent(Intent.ACTION_VIEW)
                    .setComponent(new ComponentName(getContext(), GuestWifiActivity.class));
            chips.add(new Chip("wifi", getContext().getString(R.string.guest_glance_wifi, g.wifiSsid),
                    open.toUri(Intent.URI_INTENT_SCHEME), PRIORITY_WIFI));
        }
        // Built in descending priority; anything that asks for ascending gets it reversed.
        // Other sort orders (or a column we don't have) are answered in the default order
        // rather than failing the query.
        if (sortOrder != null && sortOrder.trim().toLowerCase(java.util.Locale.ROOT)
                .matches("priority(\\s+asc)?")) {
            Collections.reverse(chips);
        }
        MatrixCursor c = new MatrixCursor(GLANCE_COLUMNS, chips.size());
        for (Chip chip : chips) {
            c.addRow(new Object[] {chip.icon, chip.text, chip.intent, chip.priority});
        }
        return c;
    }

    private static Cursor welcome(GuestInfo g) {
        MatrixCursor c = new MatrixCursor(WELCOME_COLUMNS, 1);
        c.addRow(new Object[] {g.restaurantName, g.tableLabel, g.wifiSsid});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        switch (MATCHER.match(uri)) {
            case GLANCE:
                return "vnd.android.cursor.dir/vnd.com.aioapp.status.glance";
            case WELCOME:
                return "vnd.android.cursor.item/vnd.com.aioapp.status.welcome";
            default:
                return null;
        }
    }

    // Read-only: nothing outside the MDM client writes guest info.

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
