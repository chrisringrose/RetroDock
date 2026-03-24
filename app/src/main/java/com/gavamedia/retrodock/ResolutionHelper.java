/*
 * Copyright (c) 2026 Gavamedia (https://gavamedia.com)
 * Licensed under the MIT License. See LICENSE file in the project root.
 */
package com.gavamedia.retrodock;

import android.content.ContentResolver;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;

/**
 * Shared utility class for display resolution control.
 *
 * <p>This class centralises the resolution-switching logic that was previously duplicated
 * in both {@link DisplayMonitorService} (background dock detection) and
 * {@link MainActivity} (manual Test/Reset buttons). Both callers now delegate to this
 * class, ensuring the two code paths stay in sync.
 *
 * <h3>Dual-strategy approach</h3>
 * <p>Two strategies are attempted in order:
 * <ol>
 *   <li><b>{@code wm size}</b> shell command</b> -- works on most devices without
 *       special permissions. This is the preferred path.</li>
 *   <li><b>{@link Settings.Global} {@code display_size_forced}</b> -- fallback that
 *       writes the global setting directly. Requires the
 *       {@code WRITE_SECURE_SETTINGS} permission, which must be granted via ADB:
 *       <pre>adb shell pm grant com.gavamedia.retrodock android.permission.WRITE_SECURE_SETTINGS</pre></li>
 * </ol>
 *
 * <p>The class is deliberately stateless: every method is {@code static} and accepts
 * a {@link ContentResolver} parameter so it can be called from any Android component
 * (Activity, Service, etc.) without coupling to a specific context type.
 *
 * @see DisplayMonitorService#checkAndApply()
 * @see MainActivity
 */
public final class ResolutionHelper {

    private static final String TAG = "RetroDock";

    /** Prevent instantiation -- this is a static utility class. */
    private ResolutionHelper() {
        throw new AssertionError("ResolutionHelper is a static utility class and cannot be instantiated");
    }

    /**
     * Forces the display resolution to the given dimensions.
     *
     * <p>Tries the {@code wm size WxH} shell command first. If that fails (non-zero
     * exit code or exception), falls back to writing
     * {@code Settings.Global.display_size_forced} via the provided
     * {@link ContentResolver}.
     *
     * @param resolver the {@link ContentResolver} used for the Settings.Global fallback;
     *                 typically obtained via {@code getContentResolver()} on an Activity
     *                 or Service.
     * @param width    desired display width in pixels.
     * @param height   desired display height in pixels.
     * @return {@code true} if either strategy succeeded.
     */
    public static boolean setResolution(ContentResolver resolver, int width, int height) {
        // Reject impossible sizes up front instead of feeding them to `wm` or secure settings.
        // This protects both the manual Test button and the automatic dock handler from storing
        // or applying values like 0x0 or negative dimensions.
        if (width <= 0 || height <= 0) {
            Log.e(TAG, "Refusing to set invalid resolution: " + width + "x" + height);
            return false;
        }

        // Strategy 1: `wm size` shell command (preferred -- works without special
        // permissions on most devices).
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"wm", "size", width + "x" + height});
            // Drain stdout and stderr BEFORE waitFor() to prevent deadlock if the
            // process fills the pipe buffer (typically 64KB on Linux/Android).
            try (InputStreamReader r = new InputStreamReader(p.getInputStream())) {
                while (r.read() != -1) { /* drain */ }
            }
            StringBuilder errMsg = new StringBuilder();
            try (BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = err.readLine()) != null) errMsg.append(line).append('\n');
            }
            int exit = p.waitFor();
            if (exit == 0) return true;
            if (errMsg.length() > 0) Log.w(TAG, "wm stderr: " + errMsg.toString().trim());
        } catch (Exception e) {
            Log.w(TAG, "wm command failed: " + e.getMessage());
        }

        // Strategy 2: write Settings.Global directly (needs WRITE_SECURE_SETTINGS,
        // granted via: adb shell pm grant com.gavamedia.retrodock android.permission.WRITE_SECURE_SETTINGS).
        try {
            boolean wrote = Settings.Global.putString(resolver, "display_size_forced",
                    width + "," + height);
            if (!wrote) {
                Log.e(TAG, "Settings.Global fallback returned false while setting "
                        + width + "x" + height);
            }
            return wrote;
        } catch (Exception e) {
            Log.e(TAG, "Settings.Global fallback failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Reads the first line of a file and returns it trimmed, or {@code "unknown"}
     * if the file cannot be read.
     *
     * <p>Used primarily to read sysfs status files under {@code /sys/class/drm/}
     * which are single-line pseudo-files (e.g. containing "connected" or
     * "disconnected").</p>
     *
     * @param path absolute filesystem path to read.
     * @return the trimmed first line, or {@code "unknown"} on any error.
     */
    public static String readFile(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Resets the display resolution to the device default.
     *
     * <p>Tries {@code wm size reset} first, then falls back to clearing
     * {@code Settings.Global.display_size_forced}.
     *
     * @param resolver the {@link ContentResolver} used for the Settings.Global fallback.
     * @return {@code true} if either strategy succeeded.
     */
    public static boolean resetResolution(ContentResolver resolver) {
        // Strategy 1: `wm size reset` shell command.
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"wm", "size", "reset"});
            // Drain stdout/stderr to prevent the process from blocking on a full pipe
            try (InputStreamReader r = new InputStreamReader(p.getInputStream())) {
                while (r.read() != -1) { /* drain */ }
            }
            try (InputStreamReader r = new InputStreamReader(p.getErrorStream())) {
                while (r.read() != -1) { /* drain */ }
            }
            int exit = p.waitFor();
            if (exit == 0) return true;
        } catch (Exception e) {
            Log.w(TAG, "wm reset failed: " + e.getMessage());
        }

        // Strategy 2: clear the Settings.Global key.
        try {
            boolean wrote = Settings.Global.putString(resolver, "display_size_forced", "");
            if (!wrote) {
                Log.e(TAG, "Settings.Global reset fallback returned false");
            }
            return wrote;
        } catch (Exception e) {
            Log.e(TAG, "Settings.Global reset failed: " + e.getMessage());
            return false;
        }
    }
}
