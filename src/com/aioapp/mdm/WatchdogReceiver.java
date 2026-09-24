package com.aioapp.mdm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Brings MdmService back when its process is gone and nothing else will.
 *
 * A self-update stops the old process and relies on MY_PACKAGE_REPLACED to start the new
 * one. If that one start fails — AMS gave up on it with "start timeout" on a T7 in the field
 * (AT070AA2600030, 2026-09-23) — AMS never retries and the client stays dead until a reboot.
 *
 * Alarms outlive that stop: the install kills the app without the PACKAGE_RESTARTED broadcast
 * a user force-stop sends, and AlarmManagerService ignores PACKAGE_REMOVED while
 * EXTRA_REPLACING. They used to fire into nothing, because the poll alarm's only receiver was
 * registered at runtime and died with the process. Declaring both alarm actions here lets
 * either one start the process again: the poll alarm already pending when the update landed,
 * and the repeating watchdog MdmService arms at startup.
 */
public class WatchdogReceiver extends BroadcastReceiver {
    private static final String TAG = "MdmWatchdog";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (MdmService.isRunning()) return;
        Log.w(TAG, "MdmService not running on " + (intent == null ? null : intent.getAction())
                + " — starting it");
        try {
            context.startForegroundService(new Intent(context, MdmService.class));
        } catch (Exception e) {
            Log.e(TAG, "Failed to start MdmService: " + e.getMessage(), e);
        }
    }
}
