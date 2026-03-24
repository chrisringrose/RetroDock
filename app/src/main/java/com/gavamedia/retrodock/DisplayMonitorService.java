/*
 * Copyright (c) 2026 Gavamedia (https://gavamedia.com)
 * Licensed under the MIT License. See LICENSE file in the project root.
 */
package com.gavamedia.retrodock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.content.pm.ServiceInfo;
import android.util.Log;


/**
 * DisplayMonitorService — Core foreground service that detects docking/undocking
 * and automatically switches resolution and emulator profiles.
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * FEATURE 1: DISPLAY DOCK DETECTION (event-driven)
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Uses Android's DisplayManager.DisplayListener to react to display hotplug
 * events (add/remove/change). When an event fires, we read the DRM connector
 * status file (e.g. /sys/class/drm/card0-DP-1/status) to determine if the
 * device is "connected" (docked) or "disconnected" (handheld).
 *
 * The DRM node is user-configurable in MainActivity (defaults to "card0-DP-1",
 * which is the DisplayPort output on the Retroid Pocket Mini 5 dock).
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * FEATURE 2: AUTOMATIC RESOLUTION SWITCHING
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * On dock:   Forces a specific resolution via `wm size WxH` shell command.
 *            Falls back to Settings.Global.putString("display_size_forced", ...)
 *            if the wm command fails (requires WRITE_SECURE_SETTINGS).
 *
 * On undock: Resets resolution to device default via `wm size reset`.
 *
 * The target docked resolution is configured by the user in MainActivity
 * (stored as "width" and "height" in SharedPreferences).
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * FEATURE 3: EMULATOR PROFILE SWITCHING (delegated)
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * After every dock state change, calls ProfileSwitcher.swapProfiles() which:
 *   - Swaps emulator settings files between docked/handheld variants
 *   - Hot-applies shader changes to running emulators (via EmulatorHotApply)
 *   - Watches for running emulators to exit and applies deferred swaps
 *
 * See ProfileSwitcher.java for the full emulator profile system.
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * SERVICE LIFECYCLE
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * - Started by: MainActivity toggle, or BootReceiver after device reboot.
 * - Runs as: Foreground service with a persistent notification showing dock state.
 * - Returns: START_STICKY so Android restarts us if killed.
 * - On destroy: Unregisters the display listener and resets resolution to default.
 *
 * RELATED FILES:
 *   - BootReceiver.java        — Starts this service on boot if enabled.
 *   - MainActivity.java        — UI for configuring DRM node and resolution.
 *   - ProfileSwitcher.java     — Emulator settings swap logic.
 *   - EmulatorHotApply.java    — Live shader/filter hot-apply to running emulators.
 */
public class DisplayMonitorService extends Service {

    private static final String TAG = "RetroDock";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "retrodock_monitor";
    private static final String PREFS_NAME = "retrodock_prefs";

    /**
     * In-process runtime flag used by MainActivity for service health checks.
     *
     * <p>Because RetroDock's Activity and Service run in the same process, a simple static flag
     * is a safer signal than the deprecated ActivityManager service inspection APIs. The flag is
     * cleared on {@link #onDestroy()} and naturally resets to {@code false} if the process dies.</p>
     */
    private static volatile boolean sRunningInProcess;

    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    private Handler handler;

    /** Tracks last-seen status to avoid redundant processing on duplicate events. */
    private String lastStatus = "";


    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        handler = new Handler(Looper.getMainLooper());
        displayManager = getSystemService(DisplayManager.class);
        sRunningInProcess = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Build a notification that lets the user tap to open the main settings.
        Intent notifyIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notifyIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Notification notification = buildNotification("Monitoring display connection...", pendingIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        startMonitoring();

        // START_STICKY: Android will restart this service if it's killed by the system.
        return START_STICKY;
    }

    /**
     * Registers a DisplayManager listener for hotplug events and does an initial check.
     *
     * This is event-driven, not polling. The DisplayManager calls our listener whenever
     * Android detects a display being added, removed, or changed. We then read the DRM
     * sysfs node to determine the actual connection state.
     */
    private void startMonitoring() {
        // Service re-starts are legal: startService()/startForegroundService() on an already
        // running service simply route back through onStartCommand(). The previous code blindly
        // registered a new DisplayListener every time, overwriting the field and leaking the
        // older registrations. That multiplied callbacks over time and only the most recent
        // listener was ever unregistered.
        //
        // Fix: make monitoring idempotent. If we are already registered, just refresh the current
        // state instead of adding another listener.
        if (displayListener != null) {
            Log.i(TAG, "startMonitoring called while already registered; refreshing state only");
            checkAndApply();
            return;
        }

        // Read initial config for logging; checkAndApply() will re-read prefs each time
        // so settings changes take effect immediately without restarting the service.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String node = prefs.getString("drm_node", "card0-DP-1");
        Log.i(TAG, "Monitoring started (event-driven): /sys/class/drm/" + node + "/status");

        // Check immediately on start (in case we were docked before the service launched).
        checkAndApply();

        // Register for display hotplug events — no polling needed.
        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                Log.i(TAG, "Display added: " + displayId);
                checkAndApply();
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                Log.i(TAG, "Display removed: " + displayId);
                checkAndApply();
            }

            @Override
            public void onDisplayChanged(int displayId) {
                Log.i(TAG, "Display changed: " + displayId);
                checkAndApply();
            }
        };

        displayManager.registerDisplayListener(displayListener, handler);
    }

    /**
     * Reads the DRM connector status and applies resolution + profile changes if the
     * dock state has changed since last check.
     *
     * <p><b>Thread safety:</b> This method is only ever called from the main thread
     * (DisplayListener callbacks are dispatched to our main-looper Handler), so
     * {@code lastStatus} access is inherently single-threaded.</p>
     *
     * <p><b>Settings re-read:</b> Prefs are re-read on every call so the user can
     * change DRM node, resolution, etc. in the UI without restarting the service.</p>
     *
     * FLOW:
     *   1. Read /sys/class/drm/<node>/status ("connected" or "disconnected")
     *   2. If unchanged from lastStatus, do nothing (dedup for noisy display events)
     *   3. If docked:  force the user-configured resolution
     *   4. If undocked: reset to device default
     *   5. Delegate to ProfileSwitcher for emulator settings swap + hot-apply
     */
    private void checkAndApply() {
        // Re-read prefs on every event so settings changes take effect immediately
        // without needing to restart the service.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String node = prefs.getString("drm_node", "card0-DP-1");
        // Sanitize node name to prevent path traversal (e.g. "../../etc/passwd")
        if (node.contains("..") || node.contains("/")) {
            Log.e(TAG, "Invalid DRM node name (contains path separators): " + node);
            return;
        }
        String width = prefs.getString("width", "1080");
        String height = prefs.getString("height", "1920");
        String statusPath = "/sys/class/drm/" + node + "/status";

        String status = ResolutionHelper.readFile(statusPath);

        // IMPORTANT: only act on explicit, trusted connector states.
        //
        // The old code treated every non-"connected" value as "undocked". That meant a bad DRM
        // node, a transient sysfs read failure, or any other unexpected read result could trigger
        // a real handheld swap across every enabled emulator. Since settings integrity is more
        // important than being aggressive about state changes, unknown states are now ignored.
        if (!"connected".equals(status) && !"disconnected".equals(status)) {
            Log.w(TAG, "Ignoring unreadable/unknown DRM status from " + statusPath + ": " + status);
            return;
        }

        // Only act on actual state transitions; DisplayManager can fire multiple events
        // for a single dock/undock action.
        if (!status.equals(lastStatus)) {
            Log.i(TAG, "Display status changed: " + lastStatus + " -> " + status);

            boolean docked = "connected".equals(status);

            if (docked) {
                int w;
                int h;
                try {
                    w = Integer.parseInt(width);
                    h = Integer.parseInt(height);
                } catch (NumberFormatException e) {
                    // Old behavior bug:
                    //   a bad saved width/height aborted the entire dock event and also marked the
                    //   status as already handled. That meant emulator profile swaps were skipped
                    //   and would not retry until the user physically undocked and docked again.
                    //
                    // Fix:
                    //   resolution switching and profile switching are now decoupled. Invalid
                    //   resolution prefs skip only the resolution step; RetroDock still processes
                    //   emulator profile changes for the dock transition.
                    Log.e(TAG, "Invalid resolution values (width=\"" + width + "\", height=\""
                            + height + "\") — skipping resolution change but still processing "
                            + "profile swaps");
                    updateNotification("Docked — invalid saved resolution");
                    w = -1;
                    h = -1;
                }
                if (w > 0 && h > 0) {
                    boolean ok = ResolutionHelper.setResolution(getContentResolver(), w, h);
                    Log.i(TAG, "Set resolution " + width + "x" + height + ": "
                            + (ok ? "success" : "failed"));
                    updateNotification("Docked — " + width + "x" + height);
                } else {
                    Log.w(TAG, "Docked profile handling continued without a resolution change "
                            + "because the saved resolution was invalid");
                }
            } else {
                boolean ok = ResolutionHelper.resetResolution(getContentResolver());
                Log.i(TAG, "Reset resolution: " + (ok ? "success" : "failed"));
                updateNotification("Undocked — default resolution");
            }

            // Swap emulator config files and hot-apply shaders (if feature is enabled).
            ProfileSwitcher.swapProfiles(this, docked, statusPath);

            lastStatus = status;
        }
    }

    // ── Resolution control ─────────────────────────────────────────────────────
    // Delegated to ResolutionHelper — a shared static utility that contains the
    // dual-strategy logic (wm command + Settings.Global fallback). See also
    // MainActivity which uses the same helper for its Test/Reset buttons.

    // ── Helpers ────────────────────────────────────────────────────────────────

    // ── Notification management ────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "RetroDock Monitor",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when RetroDock is monitoring for display connection");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text, PendingIntent pendingIntent) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("RetroDock")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    /** Updates the persistent foreground notification text (e.g. "Docked — 1080x1920"). */
    private void updateNotification(String text) {
        Intent notifyIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notifyIntent,
                PendingIntent.FLAG_IMMUTABLE);
        Notification notification = buildNotification(text, pendingIntent);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void onDestroy() {
        // Clean up: stop listening for display events.
        if (displayManager != null && displayListener != null) {
            displayManager.unregisterDisplayListener(displayListener);
            displayListener = null;
        }
        sRunningInProcess = false;
        // Only reset resolution if the user intentionally disabled the service.
        // When the system kills and restarts a START_STICKY service, resetting here
        // would cause a visible resolution flicker before the service restarts and
        // re-applies the docked resolution.
        boolean serviceEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean("service_enabled", false);
        if (!serviceEnabled) {
            ResolutionHelper.resetResolution(getContentResolver());
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service.
    }

    /** Returns whether the monitor service is currently alive in this app process. */
    static boolean isRunningInProcess() {
        return sRunningInProcess;
    }
}
