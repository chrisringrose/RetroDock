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
 * BootReceiver — Auto-starts the RetroDock monitor service after boot or app update.
 *
 * <h3>Problem</h3>
 * <p>The DisplayMonitorService (foreground service that detects dock/undock events) is
 * killed in two situations where the user expects it to keep running:</p>
 * <ol>
 *   <li><b>Device reboot:</b> All services are stopped and must be explicitly restarted.</li>
 *   <li><b>App update:</b> Installing a new APK (via {@code adb install} or Play Store)
 *       kills the app's process, including any running foreground service. Unlike a
 *       system kill (where START_STICKY would trigger a restart), an app update does NOT
 *       cause Android to restart the service — it's gone until someone starts it again.</li>
 * </ol>
 *
 * <h3>Solution</h3>
 * <p>This receiver listens for two broadcasts:</p>
 * <ul>
 *   <li>{@code ACTION_BOOT_COMPLETED} — fires after device boot</li>
 *   <li>{@code ACTION_MY_PACKAGE_REPLACED} — fires after this app's APK is updated</li>
 * </ul>
 * <p>In both cases, if the user previously enabled the service toggle (stored as
 * {@code "service_enabled"} in SharedPreferences), we restart the service automatically.
 * This means the user never has to manually re-toggle the service after updating the app
 * or rebooting their device.</p>
 *
 * <h3>Requirements</h3>
 * <ul>
 *   <li>{@code RECEIVE_BOOT_COMPLETED} permission in AndroidManifest.xml</li>
 *   <li>Receiver declared with {@code android:exported="true"} and intent filters for
 *       both BOOT_COMPLETED and MY_PACKAGE_REPLACED in the manifest</li>
 * </ul>
 *
 * <h3>Related files</h3>
 * <ul>
 *   <li>{@link DisplayMonitorService} — The service this receiver starts.</li>
 *   <li>{@link MainActivity} — Where the user enables/disables the service toggle.</li>
 * </ul>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "RetroDock";
    private static final String PREFS_NAME = "retrodock_prefs";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean("service_enabled", false)) {
                Log.i(TAG, "Received " + action + " — restarting RetroDock monitor service");
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
