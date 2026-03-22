/*
 * Copyright (c) 2026 Gavamedia (https://gavamedia.com)
 * Licensed under the MIT License. See LICENSE file in the project root.
 */
package com.gavamedia.retrodock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * BootReceiver — Auto-starts the RetroDock monitor service on device boot.
 *
 * WHAT IT DOES:
 *   Listens for the BOOT_COMPLETED broadcast. If the user previously enabled the
 *   auto-switch service toggle in MainActivity, this receiver starts
 *   DisplayMonitorService as a foreground service so docking detection resumes
 *   automatically after every reboot.
 *
 * HOW IT WORKS:
 *   1. Android sends ACTION_BOOT_COMPLETED after the device finishes booting.
 *   2. We check SharedPreferences for "service_enabled" (set by the service toggle
 *      in MainActivity).
 *   3. If true, we start DisplayMonitorService. On Android O+ (API 26+), we must use
 *      startForegroundService() instead of startService().
 *
 * REQUIREMENTS:
 *   - RECEIVE_BOOT_COMPLETED permission in AndroidManifest.xml
 *   - Receiver must be declared with android:exported="true" and the BOOT_COMPLETED
 *     intent filter in the manifest.
 *
 * RELATED FILES:
 *   - DisplayMonitorService.java — The service this receiver starts.
 *   - MainActivity.java — Where the user enables/disables the service toggle.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "RetroDock";
    private static final String PREFS_NAME = "retrodock_prefs";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean("service_enabled", false)) {
                Log.i(TAG, "Boot completed — starting RetroDock monitor service");
                Intent serviceIntent = new Intent(context, DisplayMonitorService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
