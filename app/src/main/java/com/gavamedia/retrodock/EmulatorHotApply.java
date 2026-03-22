/*
 * Copyright (c) 2026 Gavamedia (https://gavamedia.com)
 * Licensed under the MIT License. See LICENSE file in the project root.
 */
package com.gavamedia.retrodock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hot-applies emulator-specific settings (primarily shaders and scalers) to running emulators
 * when the device is docked or undocked, without requiring the user to restart the emulator.
 *
 * <h2>How it works</h2>
 * <p>When RetroDock detects a dock/undock event and an emulator is currently running, this class
 * is invoked to push the user's preferred "docked" or "handheld" settings into that emulator.
 * Each emulator has a different mechanism for accepting runtime changes, so each one has its
 * own hot-apply strategy. All hot-apply features are opt-in and disabled by default.</p>
 *
 * <h2>The 2-second delay</h2>
 * <p>Every hot-apply operation is scheduled with a {@value #HOT_APPLY_DELAY_MS}ms delay after
 * the dock/undock event. This delay is critical because when a device is docked or undocked,
 * the display resolution and configuration change, which causes emulators to reinitialize their
 * video rendering pipeline (recreate surfaces, reset viewports, reload graphics state). If we
 * send shader or scaler commands during this reinitialization window, the commands are either
 * lost (the emulator hasn't finished rebuilding its renderer) or cause visual glitches. The
 * 2-second delay allows the resolution change to fully propagate and the emulator's video
 * pipeline to stabilize before we apply the new settings.</p>
 *
 * <h2>Supported emulators and their support level</h2>
 * <table>
 *   <tr><th>Emulator</th><th>Method</th><th>Support Level</th></tr>
 *   <tr>
 *     <td><b>RetroArch</b></td>
 *     <td>UDP network command to localhost:55355</td>
 *     <td>Best supported -- instant shader swap mid-game</td>
 *   </tr>
 *   <tr>
 *     <td><b>DuckStation</b></td>
 *     <td>Direct INI edit + optional keyevent reload</td>
 *     <td>Experimental -- keyevent requires foreground focus</td>
 *   </tr>
 *   <tr>
 *     <td><b>ScummVM</b></td>
 *     <td>Direct INI edit + Ctrl+Alt+S key combo</td>
 *     <td>Experimental -- modifier key combos are unreliable on Android</td>
 *   </tr>
 *   <tr>
 *     <td><b>PPSSPP</b></td>
 *     <td>Direct INI edit only (no runtime reload)</td>
 *     <td>Partial -- changes take effect on next game load</td>
 *   </tr>
 * </table>
 *
 * <h2>User preferences</h2>
 * <p>All configuration is stored in the "{@value #PREFS_NAME}" SharedPreferences. Each emulator
 * has an enable toggle (e.g. {@code retroarch_hot_apply_enabled}). RetroArch shader presets
 * are auto-discovered from the backup config directory; other emulators use per-mode settings
 * stored in SharedPreferences.</p>
 *
 * @see ProfileSwitcher#isAnyPackageRunning(Context, String[])
 */
public class EmulatorHotApply {

    /** Log tag shared with other RetroDock components. */
    private static final String TAG = "RetroDock";

    /** SharedPreferences file name used for all RetroDock settings. */
    private static final String PREFS_NAME = "retrodock_prefs";

    /**
     * Delay in milliseconds before executing any hot-apply action after a dock/undock event.
     *
     * <p>This delay exists because dock/undock triggers a display resolution change, which
     * causes emulators to reinitialize their video rendering pipeline (surface recreation,
     * viewport reset, graphics state reload). Sending commands during this transient period
     * results in lost commands or visual artifacts. Two seconds is sufficient for all tested
     * emulators to complete their video pipeline reinitialization on typical Android hardware.</p>
     */
    private static final long HOT_APPLY_DELAY_MS = 2000;

    /** Shader preset file extensions in priority order (modern Vulkan first). */
    private static final String[] SHADER_EXTENSIONS = {".slangp", ".glslp", ".cgp"};

    /** RetroArch root paths — must match EmulatorConfig's database entry. */
    private static final String[] RETROARCH_ROOTS = {
            "/storage/emulated/0/RetroArch",
            "/storage/emulated/0/Android/data/com.retroarch/files",
            "/storage/emulated/0/Android/data/com.retroarch.aarch64/files"
    };

    /**
     * Lock object for INI file editing operations.
     *
     * <p><b>Issue #8 fix -- INI editing thread safety:</b> Both {@link #modifyIniSection} and
     * {@link #modifyIniKey} perform read-modify-write cycles on INI files. Without synchronization,
     * concurrent hot-apply operations targeting the same file (e.g., two rapid dock/undock events)
     * could interleave their reads and writes, causing one edit to silently overwrite the other.
     * This lock ensures that all INI file modifications are serialized, preventing data loss from
     * concurrent read-modify-write cycles on the same file.</p>
     */
    private static final Object INI_LOCK = new Object();

    /**
     * Tracks pending delayed hot-apply runnables by emulator ID.
     *
     * <p><b>Issue #6 fix -- Race condition in hot-apply delay vs exit watcher:</b> When a dock
     * event fires, we schedule a delayed hot-apply via {@link Handler#postDelayed}. If the user
     * exits the emulator before the delay expires, the exit watcher in ProfileSwitcher fires but
     * the pending hot-apply is still queued. Without cancellation, the hot-apply would execute
     * after the emulator has exited, which is wasteful and could cause errors (e.g., writing to
     * an INI file that the emulator is no longer using). This map allows us to cancel any pending
     * hot-apply for a given emulator when the exit watcher fires.</p>
     *
     * <p>Keys are emulator IDs (e.g., "retroarch", "duckstation"). Values are the Runnable
     * callbacks posted to the Handler, which can be removed via
     * {@link Handler#removeCallbacks(Runnable)}.</p>
     */
    private static final Map<String, Runnable> pendingHotApply = new HashMap<>();

    /**
     * Handler used for scheduling delayed hot-apply operations.
     *
     * <p>A single shared Handler instance is used so that {@link #cancelPending(String)} can
     * remove callbacks from the same Handler that posted them.</p>
     */
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    // =====================================================================
    // RetroArch
    // =====================================================================
    //
    // Communication method: UDP packet to localhost:55355
    //
    // RetroArch includes a built-in network command interface that listens for
    // plain-text commands over UDP. This is the most reliable hot-apply method
    // because it is an officially supported, documented API that works regardless
    // of window focus or foreground state.
    //
    // What can be changed:
    //   - Shader preset: SET_SHADER <path> loads a .glslp/.slangp preset instantly
    //   - Shader disable: SET_SHADER (no argument) disables shaders
    //
    // Prerequisites:
    //   - User must enable "Network Commands" in RetroArch settings
    //     (Settings > Network > Network Commands = ON)
    //   - The command interface port must remain at the default 55355
    //
    // Limitations:
    //   - Only shader changes are supported (no resolution/core options)
    //   - If Network Commands is disabled, the UDP packet is silently dropped
    //   - The shader path must be an absolute path accessible to RetroArch
    // =====================================================================

    /**
     * UDP port for RetroArch's built-in network command interface.
     *
     * <p>This is RetroArch's default command port. When "Network Commands" is enabled
     * in RetroArch settings, it listens on this port for plain-text UDP commands on
     * localhost. The protocol is simple: send a UTF-8 string terminated by a newline.</p>
     */
    private static final int RETROARCH_CMD_PORT = 55355;

    /**
     * Hot-applies shader settings to a running RetroArch instance via auto-discovery.
     *
     * <p>Sends a UDP command to RetroArch's network command interface to instantly swap
     * the active shader preset. This is the best-supported hot-apply path because RetroArch's
     * UDP command server is an officially documented feature that works regardless of whether
     * RetroArch is in the foreground.</p>
     *
     * <h3>Shader auto-discovery</h3>
     * <p>Instead of requiring the user to manually configure shader paths, this method
     * automatically discovers the correct global shader preset from the backup config
     * directory. RetroArch stores shader auto-load presets inside its {@code config/}
     * directory, and RetroDock's profile swap system creates backup variants:</p>
     * <ul>
     *   <li>{@code config.docked/global.slangp} — the docked global shader preset</li>
     *   <li>{@code config.handheld/global.slangp} — the handheld global shader preset</li>
     * </ul>
     *
     * <p>When docking, this method reads the shader from {@code config.docked/}; when
     * undocking, from {@code config.handheld/}. Files are checked in extension priority
     * order: {@code .slangp}, {@code .glslp}, {@code .cgp}.</p>
     *
     * <h3>Why only the global preset?</h3>
     * <p>Per-core and per-game shader presets (e.g. {@code config/nestopia/nestopia.slangp})
     * are handled automatically by the full {@code config/} directory swap when RetroArch
     * exits. Hot-apply is a best-effort temporary measure while the emulator is running —
     * the global preset provides an immediate visual change for the current display mode.</p>
     *
     * <h3>Behavior</h3>
     * <ul>
     *   <li>If a global shader preset is found in the backup config dir, sends
     *       {@code SET_SHADER <path>} to load it.</li>
     *   <li>If no global preset exists, sends {@code SET_SHADER} with no argument to
     *       disable shaders (the safe default).</li>
     * </ul>
     *
     * <p>The command is sent after a {@value #HOT_APPLY_DELAY_MS}ms delay to allow the
     * resolution change to settle (see class-level documentation for rationale).</p>
     *
     * @param ctx    application context, used to access SharedPreferences and check running packages
     * @param docked {@code true} if the device was just docked, {@code false} if just undocked
     */
    public static void hotApplyRetroArch(Context ctx, boolean docked) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("retroarch_hot_apply_enabled", false)) return;

        scheduleDelayed(ctx, "retroarch", docked, () -> {
            // RetroArch ships in multiple APK variants; check all known package names
            String[] pkgs = {"com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32"};
            if (!ProfileSwitcher.isAnyPackageRunning(ctx, pkgs)) {
                Log.i(TAG, "RetroArch no longer running, skipping hot-apply");
                return;
            }

            // Auto-discover the global shader preset from the backup config directory.
            // When switching to docked, the backup we want is "config.docked/" (the docked
            // shader presets that will be swapped in after RetroArch exits). Per-core and
            // per-game shader presets are handled automatically by the full config/ directory
            // swap on exit — hot-apply only needs the global preset for an immediate change.
            String configDir = ProfileSwitcher.findSettingsFile(RETROARCH_ROOTS, "config");
            if (configDir == null) {
                Log.w(TAG, "RetroArch config dir not found, skipping shader hot-apply");
                return;
            }

            String shaderPath = findGlobalShaderPreset(configDir, docked);
            if (shaderPath != null) {
                sendUdpCommand("SET_SHADER " + shaderPath);
                Log.i(TAG, "RetroArch hot-apply: SET_SHADER " + shaderPath);
            } else {
                // No global shader preset in the backup dir — disable shaders.
                // SET_SHADER with no argument is idempotent and safe.
                sendUdpCommand("SET_SHADER");
                Log.i(TAG, "RetroArch hot-apply: no shader preset found in backup, disabling shaders");
            }
        });
    }

    /**
     * Looks for a global shader preset file in the backup config directory for the
     * target dock mode.
     *
     * <p>When switching to docked mode, the docked shader presets live in
     * {@code config.docked/}. When switching to handheld, they live in
     * {@code config.handheld/}. This method checks for {@code global.slangp},
     * {@code global.glslp}, and {@code global.cgp} in that order.</p>
     *
     * @param configDirPath absolute path to the active config directory (e.g.
     *                      {@code /storage/emulated/0/RetroArch/config})
     * @param docked        {@code true} if switching to docked mode
     * @return absolute path to the shader preset file, or {@code null} if none found
     */
    private static String findGlobalShaderPreset(String configDirPath, boolean docked) {
        String backupDir = configDirPath + (docked ? ".docked" : ".handheld");
        File dir = new File(backupDir);
        if (!dir.isDirectory()) {
            Log.i(TAG, "Backup config dir does not exist: " + backupDir);
            return null;
        }
        for (String ext : SHADER_EXTENSIONS) {
            File preset = new File(dir, "global" + ext);
            if (preset.isFile()) {
                return preset.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * Sends a plain-text UDP command to RetroArch's network command interface.
     *
     * <p>The command is sent as a UTF-8 encoded string with a trailing newline character
     * to {@code 127.0.0.1} on port {@value #RETROARCH_CMD_PORT}. The socket has a 1-second
     * timeout to avoid blocking indefinitely if RetroArch is not listening.</p>
     *
     * <p>This runs on a dedicated background thread to avoid blocking the caller, since
     * network I/O (even localhost UDP) is not permitted on Android's main thread.</p>
     *
     * <p>Known RetroArch UDP commands used by RetroDock:</p>
     * <ul>
     *   <li>{@code SET_SHADER <path>} -- loads a shader preset from the given absolute path</li>
     *   <li>{@code SET_SHADER} (no argument) -- disables the current shader</li>
     * </ul>
     *
     * @param command the command string to send (without trailing newline; one is appended)
     */
    private static void sendUdpCommand(String command) {
        new Thread(() -> {
            // FIX: DatagramSocket resource leak
            //
            // CONCERN: The previous implementation created the DatagramSocket and called
            // socket.close() manually after socket.send(). If send() threw an exception
            // (e.g., network error, SecurityException, or any RuntimeException), the close()
            // call was skipped entirely. Each leaked DatagramSocket holds an underlying
            // native file descriptor (a UDP socket in the OS kernel). On Android, each
            // process is limited to ~1024 file descriptors. In a pathological scenario —
            // say, the user rapidly docks/undocks dozens of times while RetroArch's network
            // command port is unreachable — enough sockets could leak to exhaust the
            // process's file descriptor limit, causing unrelated operations (file reads,
            // database access, UI rendering) to fail with "Too many open files" errors.
            //
            // FIX: Wrap the socket in a try-with-resources block. Java's AutoCloseable
            // contract guarantees that the socket is closed when the block exits, whether
            // normally or via an exception. This is the standard idiom for any resource
            // that implements Closeable/AutoCloseable.
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(1000);
                byte[] data = (command + "
").getBytes("UTF-8");
                InetAddress addr = InetAddress.getByName("127.0.0.1");
                DatagramPacket packet = new DatagramPacket(data, data.length, addr, RETROARCH_CMD_PORT);
                socket.send(packet);
                Log.i(TAG, "Sent RetroArch UDP command: " + command);
            } catch (Exception e) {
                Log.w(TAG, "Failed to send RetroArch UDP: " + e.getMessage());
            }
        }, "RetroArchCmd").start();
    }

    // =====================================================================
    // DuckStation
    // =====================================================================
    //
    // Communication method: Direct INI file edit + optional Android keyevent
    //
    // DuckStation stores its post-processing shader chain in the [PostProcessing]
    // section of settings.ini. We rewrite that section with the desired shader
    // chain, then optionally send a keyevent that corresponds to DuckStation's
    // "Reload Post-Processing Shaders" hotkey to force an immediate reload.
    //
    // What can be changed:
    //   - Post-processing shader chain (ordered list of shader names)
    //   - Can enable/disable post-processing entirely
    //
    // Prerequisites:
    //   - settings.ini must be writable by RetroDock (scoped storage may block this)
    //   - For runtime reload: user must configure a "Reload Shaders" hotkey in
    //     DuckStation and provide the corresponding Android keycode in RetroDock
    //
    // Limitations:
    //   - EXPERIMENTAL: The keyevent approach requires DuckStation to be in the
    //     foreground and actively processing input events. If another app has focus,
    //     the keyevent is delivered to that app instead.
    //   - Without the reload keyevent, the INI edit only takes effect on next
    //     game load or DuckStation restart.
    //   - On Android 11+, scoped storage restrictions may prevent direct file access
    //     to DuckStation's data directory.
    // =====================================================================

    /**
     * Hot-applies shader settings to a running DuckStation instance.
     *
     * <p>This method modifies the {@code [PostProcessing]} section of DuckStation's
     * {@code settings.ini} to set the desired shader chain for the current dock state.
     * After editing the INI file, it optionally sends an Android keyevent to trigger
     * DuckStation's "Reload Post-Processing Shaders" hotkey for immediate effect.</p>
     *
     * <p><b>Experimental:</b> The keyevent-based reload requires DuckStation to be in the
     * foreground. If another app has focus, the keyevent is lost. Without the keyevent,
     * the INI changes take effect on the next game load or DuckStation restart.</p>
     *
     * <p>The user configures:</p>
     * <ul>
     *   <li>Handheld shader chain -- comma-separated shader names, or empty for none</li>
     *   <li>Docked shader chain -- comma-separated shader names, or empty for none</li>
     *   <li>Reload keycode -- the Android keycode corresponding to DuckStation's
     *       "Reload Post-Processing Shaders" hotkey (0 = no reload attempt)</li>
     * </ul>
     *
     * @param ctx    application context, used to access SharedPreferences and check running packages
     * @param docked {@code true} if the device was just docked, {@code false} if just undocked
     */
    public static void hotApplyDuckStation(Context ctx, boolean docked) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("duckstation_hot_apply_enabled", false)) return;

        scheduleDelayed(ctx, "duckstation", docked, () -> {
            String[] pkgs = {"com.github.stenzek.duckstation"};
            if (!ProfileSwitcher.isAnyPackageRunning(ctx, pkgs)) {
                Log.i(TAG, "DuckStation no longer running, skipping hot-apply");
                return;
            }

            // Locate DuckStation's settings.ini on the filesystem
            String settingsPath = findDuckStationSettings(ctx);
            if (settingsPath == null) {
                Log.w(TAG, "DuckStation settings.ini not found");
                return;
            }

            // Get the user's desired shader chain for the current dock state
            String chainKey = docked ? "duckstation_shaders_docked" : "duckstation_shaders_handheld";
            String shaderChain = prefs.getString(chainKey, "");

            // Rewrite the entire [PostProcessing] section with the new shader chain
            boolean modified = modifyIniSection(settingsPath, "PostProcessing", buildDuckStationShaderEntries(shaderChain));
            if (modified) {
                Log.i(TAG, "DuckStation hot-apply: updated PostProcessing in settings.ini");

                // Attempt to trigger an immediate reload via Android keyevent.
                // The user must have configured a "Reload Post-Processing Shaders" hotkey
                // in DuckStation and provided the matching Android keycode in RetroDock settings.
                int reloadKeycode = prefs.getInt("duckstation_reload_keycode", 0);
                if (reloadKeycode > 0) {
                    sendKeyEvent(reloadKeycode);
                    Log.i(TAG, "DuckStation hot-apply: sent reload keyevent " + reloadKeycode);
                } else {
                    Log.i(TAG, "DuckStation hot-apply: no reload keycode configured, shader will apply on next restart");
                }
            }
        });
    }

    /**
     * Locates DuckStation's {@code settings.ini} file on the filesystem.
     *
     * <p>Checks the following locations in order:</p>
     * <ol>
     *   <li>User-configured override path from SharedPreferences
     *       (key: {@code emu_duckstation_override_settings.ini})</li>
     *   <li>{@code /storage/emulated/0/Android/data/com.github.stenzek.duckstation/files/settings.ini}
     *       -- DuckStation's default scoped storage location</li>
     *   <li>{@code /storage/emulated/0/duckstation/settings.ini}
     *       -- legacy/alternative storage location</li>
     * </ol>
     *
     * @param ctx application context for accessing SharedPreferences
     * @return absolute path to settings.ini if found, or {@code null} if not found
     */
    private static String findDuckStationSettings(Context ctx) {
        String[] paths = {
                "/storage/emulated/0/Android/data/com.github.stenzek.duckstation/files/settings.ini",
                "/storage/emulated/0/duckstation/settings.ini"
        };

        // Check user override first
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String override = prefs.getString("emu_duckstation_override_settings.ini", "");
        if (!override.isEmpty() && new File(override).exists()) return override;

        for (String p : paths) {
            if (new File(p).exists()) return p;
        }
        return null;
    }

    /**
     * Builds the INI key-value entries for DuckStation's {@code [PostProcessing]} section.
     *
     * <p>DuckStation's post-processing configuration uses a numbered stage system:</p>
     * <pre>
     * [PostProcessing]
     * Enabled = true
     * StageCount = 2
     * Stage1 = CRT-Geom
     * Stage2 = FXAA
     * </pre>
     *
     * <p>When the shader chain is empty, post-processing is disabled:</p>
     * <pre>
     * [PostProcessing]
     * StageCount = 0
     * Enabled = false
     * </pre>
     *
     * @param shaderChain comma-separated shader names (e.g. "CRT-Geom,FXAA"), or empty/null
     *                    to disable post-processing entirely
     * @return list of INI key-value lines to write under the [PostProcessing] section header
     */
    private static List<String> buildDuckStationShaderEntries(String shaderChain) {
        List<String> entries = new ArrayList<>();
        if (shaderChain == null || shaderChain.trim().isEmpty()) {
            entries.add("StageCount = 0");
            entries.add("Enabled = false");
        } else {
            String[] shaders = shaderChain.split(",");
            entries.add("Enabled = true");
            entries.add("StageCount = " + shaders.length);
            for (int i = 0; i < shaders.length; i++) {
                entries.add("Stage" + (i + 1) + " = " + shaders[i].trim());
            }
        }
        return entries;
    }

    // =====================================================================
    // ScummVM
    // =====================================================================
    //
    // Communication method: Direct INI file edit + Ctrl+Alt+S key combo via shell
    //
    // ScummVM stores graphics settings in scummvm.ini under the [scummvm] section.
    // We edit the relevant keys (gfx_mode, scaler, filtering, aspect_ratio), then
    // send the Ctrl+Alt+S key combination to trigger ScummVM's "cycle scaler" hotkey,
    // which forces it to re-read its scaler configuration.
    //
    // What can be changed:
    //   - Graphics scaler/mode (e.g. "normal", "hq2x", "2xsai")
    //   - Bilinear filtering (on/off)
    //   - Aspect ratio correction (on/off)
    //
    // Prerequisites:
    //   - scummvm.ini must be writable by RetroDock
    //   - ScummVM must be in the foreground for key combos to be received
    //
    // Limitations:
    //   - EXPERIMENTAL: Android's `input keyevent --longpress` for modifier key combos
    //     (Ctrl+Alt+S) is unreliable. The timing of simultaneous modifier keys is not
    //     guaranteed, so the combo may not register correctly in ScummVM. This is a
    //     fundamental limitation of Android's input injection for multi-modifier shortcuts.
    //   - The INI edit alone does not take effect until ScummVM is restarted or the
    //     scaler is cycled via hotkey.
    //
    // ScummVM hotkey reference:
    //   Ctrl+Alt+1 through Ctrl+Alt+8 : select specific scaler
    //   Ctrl+Alt+a : toggle aspect ratio correction
    //   Ctrl+Alt+f : toggle bilinear filtering
    //   Ctrl+Alt+s : cycle scaling modes
    // =====================================================================

    /**
     * Hot-applies graphics scaler and filtering settings to a running ScummVM instance.
     *
     * <p>Modifies {@code gfx_mode}, {@code scaler}, {@code filtering}, and {@code aspect_ratio}
     * keys in the {@code [scummvm]} section of ScummVM's INI file. After editing, sends the
     * {@code Ctrl+Alt+S} key combination to trigger ScummVM's built-in "cycle scalers" hotkey,
     * which forces it to re-read its scaler configuration at runtime.</p>
     *
     * <p><b>Experimental:</b> The Ctrl+Alt+S key combo is sent via Android's {@code input keyevent
     * --longpress} shell command with simultaneous modifier keys (CTRL_LEFT=113, ALT_LEFT=57,
     * S=47). This approach is unreliable on Android because the OS does not guarantee the timing
     * of simultaneous key presses, so ScummVM may not recognize the modifier combination. The
     * INI file edit is always applied regardless; the key combo is a best-effort attempt at
     * forcing an immediate visual update.</p>
     *
     * @param ctx    application context, used to access SharedPreferences and check running packages
     * @param docked {@code true} if the device was just docked, {@code false} if just undocked
     */
    public static void hotApplyScummVM(Context ctx, boolean docked) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("scummvm_hot_apply_enabled", false)) return;

        scheduleDelayed(ctx, "scummvm", docked, () -> {
            String[] pkgs = {"org.scummvm.scummvm"};
            if (!ProfileSwitcher.isAnyPackageRunning(ctx, pkgs)) {
                Log.i(TAG, "ScummVM no longer running, skipping hot-apply");
                return;
            }

            String settingsPath = findScummVMSettings(ctx);
            if (settingsPath == null) {
                Log.w(TAG, "ScummVM scummvm.ini not found");
                return;
            }

            // Read user's preferred scaler/filter settings for the current dock state
            String scalerKey = docked ? "scummvm_scaler_docked" : "scummvm_scaler_handheld";
            String scaler = prefs.getString(scalerKey, "");
            String filterKey = docked ? "scummvm_filtering_docked" : "scummvm_filtering_handheld";
            boolean filtering = prefs.getBoolean(filterKey, false);
            String aspectKey = docked ? "scummvm_aspect_docked" : "scummvm_aspect_handheld";
            boolean aspect = prefs.getBoolean(aspectKey, true);

            // Update the [scummvm] section in scummvm.ini with the new graphics settings.
            // Both gfx_mode and scaler are set for compatibility with different ScummVM versions.
            if (!scaler.isEmpty()) {
                modifyIniKey(settingsPath, "scummvm", "gfx_mode", scaler);
                modifyIniKey(settingsPath, "scummvm", "scaler", scaler);
                Log.i(TAG, "ScummVM hot-apply: set scaler to " + scaler);
            }
            modifyIniKey(settingsPath, "scummvm", "filtering", filtering ? "true" : "false");
            modifyIniKey(settingsPath, "scummvm", "aspect_ratio", aspect ? "true" : "false");

            // Issue #9 fix: Attempt to trigger ScummVM's scaler cycle hotkey: Ctrl+Alt+S
            // Android keycodes: KEYCODE_CTRL_LEFT=113, KEYCODE_ALT_LEFT=57, KEYCODE_S=47
            //
            // WARNING: Simultaneous modifier key combos via `input keyevent` are fundamentally
            // unreliable on Android. The `--longpress` flag causes sequential key-down events
            // with timing that is NOT guaranteed to overlap, so ScummVM may see individual key
            // presses instead of a chord. We use `sh -c` to invoke the command through a proper
            // shell, which is slightly more reliable than splitting on spaces (the previous
            // approach passed individual tokens to Runtime.exec, which bypasses the shell and
            // can behave differently). This is still a best-effort mechanism; the INI file edit
            // is the primary means of applying the change.
            sendKeyCombo("input keyevent --longpress 113 57 47"); // CTRL_LEFT, ALT_LEFT, S
            Log.i(TAG, "ScummVM hot-apply: sent Ctrl+Alt+S key combo (best-effort, may not register)");
        });
    }

    /**
     * Locates ScummVM's {@code scummvm.ini} configuration file on the filesystem.
     *
     * <p>Checks the following locations in order:</p>
     * <ol>
     *   <li>User-configured override path from SharedPreferences
     *       (key: {@code emu_scummvm_override_scummvm.ini})</li>
     *   <li>{@code /storage/emulated/0/ScummVM/scummvm.ini}
     *       -- ScummVM's common shared storage location</li>
     *   <li>{@code /storage/emulated/0/Android/data/org.scummvm.scummvm/files/scummvm.ini}
     *       -- ScummVM's scoped storage location</li>
     * </ol>
     *
     * @param ctx application context for accessing SharedPreferences
     * @return absolute path to scummvm.ini if found, or {@code null} if not found
     */
    private static String findScummVMSettings(Context ctx) {
        String[] paths = {
                "/storage/emulated/0/ScummVM/scummvm.ini",
                "/storage/emulated/0/Android/data/org.scummvm.scummvm/files/scummvm.ini"
        };

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String override = prefs.getString("emu_scummvm_override_scummvm.ini", "");
        if (!override.isEmpty() && new File(override).exists()) return override;

        for (String p : paths) {
            if (new File(p).exists()) return p;
        }
        return null;
    }

    // =====================================================================
    // PPSSPP (partial support)
    // =====================================================================
    //
    // Communication method: Direct INI file edit only (no runtime reload)
    //
    // PPSSPP stores its post-processing shader setting in the [Graphics] section
    // of ppsspp.ini under the key "PostProcessingShader". We edit this value
    // directly, but PPSSPP has no known mechanism to reload the shader at runtime.
    //
    // What can be changed:
    //   - Post-processing shader name (a single shader, not a chain)
    //   - Set to "Off" to disable post-processing
    //
    // Prerequisites:
    //   - ppsspp.ini must be writable by RetroDock
    //
    // Limitations:
    //   - PARTIAL SUPPORT: There is no runtime reload mechanism. Although PPSSPP has
    //     a WebSocket debug API, it does not expose settings endpoints for shader
    //     changes. The INI edit only takes effect on the next game load or PPSSPP
    //     restart. This means the user will not see a visual change mid-game.
    //   - Only a single shader name is supported (not a chain like DuckStation)
    // =====================================================================

    /**
     * Hot-applies post-processing shader settings to a running PPSSPP instance.
     *
     * <p>Modifies the {@code PostProcessingShader} key in the {@code [Graphics]} section
     * of PPSSPP's {@code ppsspp.ini}. Sets it to the user's preferred shader name for the
     * current dock state, or {@code "Off"} if no shader is configured.</p>
     *
     * <p><b>Partial support only:</b> PPSSPP has no runtime shader reload mechanism.
     * Although PPSSPP exposes a WebSocket debug API, it does not include endpoints for
     * reloading graphics settings. The INI edit is persisted, but the change will only
     * take effect the next time a game is loaded or PPSSPP is restarted. The user will
     * not see an immediate visual change mid-game.</p>
     *
     * @param ctx    application context, used to access SharedPreferences and check running packages
     * @param docked {@code true} if the device was just docked, {@code false} if just undocked
     */
    public static void hotApplyPPSSPP(Context ctx, boolean docked) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("ppsspp_hot_apply_enabled", false)) return;

        scheduleDelayed(ctx, "ppsspp", docked, () -> {
            // PPSSPP ships in both free and Gold variants
            String[] pkgs = {"org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"};
            if (!ProfileSwitcher.isAnyPackageRunning(ctx, pkgs)) {
                Log.i(TAG, "PPSSPP no longer running, skipping hot-apply");
                return;
            }

            String settingsPath = findPPSSPPSettings(ctx);
            if (settingsPath == null) {
                Log.w(TAG, "PPSSPP ppsspp.ini not found");
                return;
            }

            String shaderKey = docked ? "ppsspp_shader_docked" : "ppsspp_shader_handheld";
            String shader = prefs.getString(shaderKey, "");

            if (!shader.isEmpty()) {
                modifyIniKey(settingsPath, "Graphics", "PostProcessingShader", shader);
                Log.i(TAG, "PPSSPP hot-apply: set PostProcessingShader to " + shader);
            } else {
                modifyIniKey(settingsPath, "Graphics", "PostProcessingShader", "Off");
                Log.i(TAG, "PPSSPP hot-apply: disabled PostProcessingShader");
            }
            // No reliable way to force PPSSPP to reload mid-game.
            // Change takes effect on next game load or restart.
        });
    }

    /**
     * Locates PPSSPP's {@code ppsspp.ini} configuration file on the filesystem.
     *
     * <p>Checks the following locations in order:</p>
     * <ol>
     *   <li>User-configured override path from SharedPreferences
     *       (key: {@code emu_ppsspp_override_SYSTEM_ppsspp.ini})</li>
     *   <li>{@code /storage/emulated/0/PSP/SYSTEM/ppsspp.ini}
     *       -- PPSSPP's default shared storage location</li>
     *   <li>{@code /storage/emulated/0/Android/data/org.ppsspp.ppsspp/files/SYSTEM/ppsspp.ini}
     *       -- PPSSPP free version scoped storage</li>
     *   <li>{@code /storage/emulated/0/Android/data/org.ppsspp.ppssppgold/files/SYSTEM/ppsspp.ini}
     *       -- PPSSPP Gold scoped storage</li>
     * </ol>
     *
     * @param ctx application context for accessing SharedPreferences
     * @return absolute path to ppsspp.ini if found, or {@code null} if not found
     */
    private static String findPPSSPPSettings(Context ctx) {
        String[] paths = {
                "/storage/emulated/0/PSP/SYSTEM/ppsspp.ini",
                "/storage/emulated/0/Android/data/org.ppsspp.ppsspp/files/SYSTEM/ppsspp.ini",
                "/storage/emulated/0/Android/data/org.ppsspp.ppssppgold/files/SYSTEM/ppsspp.ini"
        };

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String override = prefs.getString("emu_ppsspp_override_SYSTEM_ppsspp.ini", "");
        if (!override.isEmpty() && new File(override).exists()) return override;

        for (String p : paths) {
            if (new File(p).exists()) return p;
        }
        return null;
    }

    // =====================================================================
    // Common utilities
    // =====================================================================

    /**
     * Schedules a hot-apply action to run after the standard delay period.
     *
     * <p>This is the central scheduling mechanism for all hot-apply operations. It posts
     * a delayed message to the main thread's Handler, which then spawns a background thread
     * to execute the actual hot-apply logic. The two-stage approach (main thread delay, then
     * background thread execution) is used because:</p>
     * <ol>
     *   <li>The {@link Handler#postDelayed} API requires a Looper, and the main looper is
     *       always available and reliable for scheduling.</li>
     *   <li>The actual hot-apply work (file I/O, network I/O) must run off the main thread
     *       to avoid ANR violations.</li>
     * </ol>
     *
     * <p>The delay of {@value #HOT_APPLY_DELAY_MS}ms allows the emulator's video pipeline
     * to finish reinitializing after the dock/undock resolution change. See the class-level
     * documentation for a detailed explanation.</p>
     *
     * <p><b>Issue #6 fix -- Cancellation mechanism:</b> Before scheduling a new delayed
     * hot-apply, any previously pending hot-apply for the same emulator is cancelled. This
     * prevents stale hot-apply operations from executing after the emulator has exited or
     * after a rapid sequence of dock/undock events. The pending runnable is tracked in
     * {@link #pendingHotApply} so that {@link #cancelPending(String)} can remove it from
     * the Handler's message queue.</p>
     *
     * @param ctx    application context (unused in the current implementation but passed for
     *               future extensibility and consistency with other methods)
     * @param emuId  emulator identifier used to track and cancel pending hot-apply operations
     * @param docked the current dock state, logged for diagnostic purposes
     * @param action the hot-apply logic to execute after the delay, on a background thread
     */
    private static void scheduleDelayed(Context ctx, String emuId, boolean docked, Runnable action) {
        Log.i(TAG, "Scheduling hot-apply in " + HOT_APPLY_DELAY_MS + "ms (emuId=" + emuId + ", docked=" + docked + ")");

        // Issue #6: Cancel any previously pending hot-apply for this emulator before scheduling
        // a new one. This handles two scenarios:
        // 1. Rapid dock/undock: If the user docks and immediately undocks, we cancel the first
        //    hot-apply so only the final dock state is applied.
        // 2. Emulator exit: ProfileSwitcher can call cancelPending() when the exit watcher fires,
        //    preventing a stale hot-apply from running after the emulator has quit.
        cancelPending(emuId);

        Runnable delayedRunnable = () -> {
            // Remove ourselves from the pending map now that we are executing
            synchronized (pendingHotApply) {
                pendingHotApply.remove(emuId);
            }
            new Thread(action, "HotApply-" + emuId).start();
        };

        synchronized (pendingHotApply) {
            pendingHotApply.put(emuId, delayedRunnable);
        }
        sHandler.postDelayed(delayedRunnable, HOT_APPLY_DELAY_MS);
    }

    /**
     * Cancels any pending delayed hot-apply operation for the given emulator.
     *
     * <p><b>Issue #6 fix:</b> This method is intended to be called by ProfileSwitcher when
     * its exit watcher detects that an emulator has quit. Without cancellation, the pending
     * hot-apply (which was scheduled with a 2-second delay) would still fire after the emulator
     * has exited, wasting resources and potentially writing to INI files that are no longer
     * in use by a running process.</p>
     *
     * <p>It is safe to call this method even if no hot-apply is pending for the given emulator;
     * it will simply do nothing in that case.</p>
     *
     * @param emuId the emulator identifier (e.g., "retroarch", "duckstation") whose pending
     *              hot-apply should be cancelled
     */
    public static void cancelPending(String emuId) {
        Runnable pending;
        synchronized (pendingHotApply) {
            pending = pendingHotApply.remove(emuId);
        }
        if (pending != null) {
            sHandler.removeCallbacks(pending);
            Log.i(TAG, "Cancelled pending hot-apply for " + emuId);
        }
    }

    /**
     * Replaces an entire INI section with new entries, preserving all other sections.
     *
     * <p>This method performs a full rewrite of the target section in an INI-format configuration
     * file. It reads the entire file into memory, replaces all lines between the target section
     * header and the next section header (or end-of-file) with the provided entries, then writes
     * the file back. If the target section does not exist, it is appended to the end of the file.</p>
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Read all lines from the file into a list.</li>
     *   <li>Iterate through lines. When the target section header is found (case-insensitive
     *       match), write the header followed by the new entries.</li>
     *   <li>Skip all original lines within the section until the next section header or EOF.</li>
     *   <li>All lines outside the target section are preserved unchanged.</li>
     *   <li>If the section was never found, append a blank line, the section header, and the
     *       new entries at the end of the file.</li>
     * </ol>
     *
     * <p><b>Issue #10 fix -- Blank line preservation:</b> When replacing a section's content,
     * any leading blank lines that appear immediately before the next section header are
     * preserved. Many INI files use blank lines as visual separators between sections, and
     * stripping them would cause the file to become increasingly compressed on each edit,
     * making it harder to read when manually inspected.</p>
     *
     * <p><b>Issue #8 fix -- Thread safety:</b> This method is synchronized on {@link #INI_LOCK}
     * to prevent concurrent read-modify-write cycles from corrupting INI files.</p>
     *
     * @param filePath   absolute path to the INI file to modify
     * @param section    the section name without brackets (e.g. "PostProcessing")
     * @param newEntries the key-value lines to write under the section header
     *                   (e.g. ["Enabled = true", "StageCount = 1", "Stage1 = CRT-Geom"])
     * @return {@code true} if the file was successfully modified, {@code false} if the file
     *         does not exist or an I/O error occurred
     */
    static boolean modifyIniSection(String filePath, String section, List<String> newEntries) {
        // Issue #8: Synchronize on INI_LOCK to prevent concurrent read-modify-write cycles.
        // Multiple hot-apply threads could target the same INI file (e.g., if two dock events
        // fire in rapid succession), and without locking, one thread's read could see stale
        // data that the other thread is about to overwrite.
        synchronized (INI_LOCK) {
            try {
                File file = new File(filePath);
                if (!file.exists()) return false;

                List<String> lines = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                }

                List<String> output = new ArrayList<>();
                boolean inSection = false;
                boolean sectionFound = false;
                String sectionHeader = "[" + section + "]";

                // Issue #10: Collect trailing blank lines within the section so they can be
                // preserved before the next section header. Without this, each edit would strip
                // the blank line separators that many INI files use between sections.
                List<String> trailingBlanks = new ArrayList<>();

                for (String l : lines) {
                    String trimmed = l.trim();
                    if (trimmed.equalsIgnoreCase(sectionHeader)) {
                        // Found the target section -- write header and new entries
                        inSection = true;
                        sectionFound = true;
                        trailingBlanks.clear();
                        output.add(l);
                        // Add new entries immediately after the section header
                        for (String entry : newEntries) {
                            output.add(entry);
                        }
                        continue;
                    }
                    if (inSection) {
                        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                            // We are leaving the target section and entering a new one.
                            // Issue #10: Re-insert any trailing blank lines that appeared before
                            // this next section header to preserve visual separation between sections.
                            for (String blank : trailingBlanks) {
                                output.add(blank);
                            }
                            trailingBlanks.clear();
                            inSection = false;
                            output.add(l);
                        } else if (trimmed.isEmpty()) {
                            // Issue #10: Buffer blank lines -- they might be separators before the
                            // next section. If more non-blank content follows within this section,
                            // these blanks are discarded (they were part of the old content being
                            // replaced). If a section header follows, they are preserved.
                            trailingBlanks.add(l);
                        } else {
                            // Non-blank content within the section being replaced -- discard it,
                            // and discard any blank lines that preceded it (they were interspersed
                            // in the old section content, not separators before the next section).
                            trailingBlanks.clear();
                        }
                        continue;
                    }
                    output.add(l);
                }

                // If the section didn't exist in the file, append it at the end
                if (!sectionFound) {
                    output.add("");
                    output.add(sectionHeader);
                    for (String entry : newEntries) {
                        output.add(entry);
                    }
                }

                // Write to a temp file in the same directory and replace the original only after
                // the full new contents are durable on disk. The previous implementation opened
                // the real INI directly with FileWriter, which truncates the file up front. If
                // RetroDock or the emulator died mid-write, the user could be left with a partial
                // or empty config. Temp-file replacement keeps the original untouched until the
                // new version is complete.
                writeLinesAtomically(file, output);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to modify INI section [" + section + "] in " + filePath + ": " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Modifies a single key-value pair within a specific section of an INI file.
     *
     * <p>Unlike {@link #modifyIniSection}, which replaces an entire section, this method
     * surgically updates (or inserts) a single key within a section while preserving all
     * other keys in that section. This is used when only one setting needs to change
     * (e.g. setting {@code filtering = true} in ScummVM's config without disturbing other
     * graphics settings).</p>
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Read all lines from the file into a list.</li>
     *   <li>Iterate through lines, tracking which section we are currently inside.</li>
     *   <li>When inside the target section and a line starting with the target key is found,
     *       replace it with the new value.</li>
     *   <li>If we leave the section (hit the next section header) without finding the key,
     *       insert the new key-value pair just before the next section header.</li>
     *   <li>If we reach EOF while still in the target section without finding the key,
     *       append the key-value pair at the end.</li>
     *   <li>If the target section does not exist at all, append the section header and the
     *       key-value pair at the end of the file.</li>
     * </ol>
     *
     * <p><b>Issue #12 fix -- Robust key matching:</b> The previous implementation checked if a
     * trimmed line started with {@code key + " "} or {@code key + "="}. This was fragile because
     * it could produce false positives for keys that share a prefix (e.g., searching for "Stage"
     * would match "StageCount"). The fix splits each line on the first {@code =} character and
     * compares the left-hand side (trimmed) against the target key. This correctly handles all
     * common INI formatting variants: {@code key=value}, {@code key = value}, {@code key =value},
     * and {@code key= value}. It also avoids false prefix matches because the entire left-hand
     * side must equal the key, not merely start with it.</p>
     *
     * <p><b>Issue #8 fix -- Thread safety:</b> This method is synchronized on {@link #INI_LOCK}
     * to prevent concurrent read-modify-write cycles from corrupting INI files.</p>
     *
     * @param filePath absolute path to the INI file to modify
     * @param section  the section name without brackets (e.g. "Graphics", "scummvm")
     * @param key      the INI key to set (e.g. "PostProcessingShader", "filtering")
     * @param value    the value to assign to the key (e.g. "FXAA", "true")
     * @return {@code true} if the file was successfully modified, {@code false} if the file
     *         does not exist or an I/O error occurred
     */
    static boolean modifyIniKey(String filePath, String section, String key, String value) {
        // Issue #8: Synchronize on INI_LOCK to prevent concurrent read-modify-write cycles.
        // This is especially important for ScummVM hot-apply, which calls modifyIniKey multiple
        // times in sequence (gfx_mode, scaler, filtering, aspect_ratio) -- without locking,
        // another thread could interleave and corrupt the file.
        synchronized (INI_LOCK) {
            try {
                File file = new File(filePath);
                if (!file.exists()) return false;

                List<String> lines = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                }

                List<String> output = new ArrayList<>();
                boolean inSection = false;
                boolean keyFound = false;
                boolean sectionFound = false;
                String sectionHeader = "[" + section + "]";

                for (String l : lines) {
                    String trimmed = l.trim();
                    if (trimmed.equalsIgnoreCase(sectionHeader)) {
                        inSection = true;
                        sectionFound = true;
                        output.add(l);
                        continue;
                    }
                    if (inSection) {
                        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                            // We are leaving the target section and entering a new one.
                            // If the key was never found, insert it before the new section header.
                            if (!keyFound) {
                                output.add(key + " = " + value);
                                keyFound = true;
                            }
                            inSection = false;
                            output.add(l);
                            continue;
                        }
                        // Issue #12 fix: Robust key matching by splitting on the first '=' and
                        // comparing the trimmed left-hand side. The old approach used startsWith(),
                        // which could false-match keys sharing a prefix (e.g., "Stage" matching
                        // "StageCount"). Splitting on '=' and trimming ensures we match the exact
                        // key name regardless of whitespace around the '=' sign.
                        if (!keyFound && trimmed.contains("=")) {
                            int eqIndex = trimmed.indexOf('=');
                            String lineKey = trimmed.substring(0, eqIndex).trim();
                            if (lineKey.equals(key)) {
                                // Replace the existing key-value pair
                                output.add(key + " = " + value);
                                keyFound = true;
                                continue;
                            }
                        }
                    }
                    output.add(l);
                }

                // If we reached EOF while still in the target section and the key wasn't found,
                // append the key-value pair at the end of the file (still within the section)
                if (inSection && !keyFound) {
                    output.add(key + " = " + value);
                    keyFound = true;
                }

                // If the section was never found at all, append the entire section with the key
                if (!sectionFound) {
                    output.add("");
                    output.add(sectionHeader);
                    output.add(key + " = " + value);
                }

                // Same safety guarantee as modifyIniSection(): never truncate the live config
                // until the replacement bytes already exist in a temp file beside it.
                writeLinesAtomically(file, output);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to modify INI key " + key + " in [" + section + "] of " + filePath + ": " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Writes a complete new file body to a temp file and then replaces the original in one step.
     *
     * <h3>CONCERN: INI file corruption during hot-apply</h3>
     * <p>Hot-apply modifies emulator INI files (DuckStation's settings.ini, ScummVM's
     * scummvm.ini, PPSSPP's ppsspp.ini) <b>while the emulator is running</b>. The
     * previous implementation opened the target file directly with {@link java.io.FileWriter},
     * which truncates the file to zero bytes immediately upon opening. This created a
     * dangerous window:</p>
     * <ol>
     *   <li>FileWriter opens settings.ini → file is now 0 bytes (truncated)</li>
     *   <li>RetroDock starts writing new content line by line...</li>
     *   <li><b>CRASH</b> — RetroDock is killed, or the device loses power, or an
     *       IOException occurs mid-write</li>
     *   <li>Result: settings.ini is now a partial file (or empty). The emulator's
     *       entire configuration is corrupted or lost.</li>
     * </ol>
     * <p>This is especially dangerous because the emulator may still be reading from
     * this same file. Even without a crash, a race between the emulator reading and
     * RetroDock truncating could cause the emulator to see a partial or empty config.</p>
     *
     * <h3>How this fix protects config files</h3>
     * <p>Instead of truncating the live config file, we use a write-to-temp-then-replace
     * strategy that guarantees the original file is never modified until the complete
     * replacement is safely on disk:</p>
     * <ol>
     *   <li><b>Write to a sibling temp file:</b> A new file is created next to the
     *       original (e.g. {@code settings.ini.retrodock-write.12345.tmp}). The original
     *       file is completely untouched at this point.</li>
     *   <li><b>Flush + fsync:</b> After writing all lines, we call {@code writer.flush()}
     *       to push data from Java's buffers to the OS, then {@code fos.getFD().sync()}
     *       to force the OS to write the data to the physical storage device. Without
     *       fsync, a power loss could leave the temp file with buffered-but-unwritten
     *       data, and the subsequent replace would swap in a corrupt file.</li>
     *   <li><b>Atomic replace:</b> We use {@link Files#move} with
     *       {@code ATOMIC_MOVE + REPLACE_EXISTING} to swap the temp file into the
     *       original's path. On filesystems that support it, this is a single
     *       {@code rename(2)} syscall — the original file is replaced in one atomic
     *       operation with no intermediate state where the file is missing or partial.</li>
     *   <li><b>Non-atomic fallback:</b> Some Android FUSE combinations don't support
     *       ATOMIC_MOVE. In that case, we fall back to {@code REPLACE_EXISTING} alone,
     *       which is still safer than direct truncation because the original file exists
     *       until the move completes.</li>
     * </ol>
     *
     * <h3>Failure handling</h3>
     * <ul>
     *   <li><b>Write failure:</b> If any IOException occurs while writing the temp file,
     *       the temp is deleted and the original is untouched. The emulator continues
     *       running with its current config.</li>
     *   <li><b>Replace failure:</b> If the Files.move fails (both atomic and non-atomic),
     *       the temp is deleted and the original is untouched. The hot-apply is logged as
     *       failed but no data is lost.</li>
     * </ul>
     *
     * @param file   the original INI file to replace
     * @param output the complete list of lines that should constitute the new file contents
     * @throws IOException if the temp file cannot be written or the replacement fails
     */
    private static void writeLinesAtomically(File file, List<String> output) throws IOException {
        // Step 1: Create a uniquely-named temp file next to the original.
        // The nanoTime() suffix prevents collisions if two hot-apply operations target
        // the same INI file in rapid succession (shouldn't happen due to INI_LOCK, but
        // defensive naming costs nothing).
        File temp = new File(file.getAbsolutePath() + ".retrodock-write." + System.nanoTime() + ".tmp");

        // Step 2: Write the complete replacement content to the temp file.
        // The original INI file is completely untouched during this phase.
        // try-with-resources guarantees the streams are closed even on exception.
        try (FileOutputStream fos = new FileOutputStream(temp);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fos))) {
            for (int i = 0; i < output.size(); i++) {
                writer.write(output.get(i));
                writer.newLine();
            }
            // flush() pushes data from Java's BufferedWriter to the OS kernel buffers.
            // getFD().sync() (fsync) forces the kernel to write those buffers to the
            // physical storage device. Together they guarantee the temp file is fully
            // durable before we attempt to replace the original.
            writer.flush();
            fos.getFD().sync();
        } catch (IOException writeFailure) {
            // Write failed — clean up the partial temp file and propagate the error.
            // The original INI file was never touched.
            temp.delete();
            throw writeFailure;
        }

        // Step 3: Replace the original file with the fully-written temp file.
        try {
            try {
                // First attempt: atomic move. On supported filesystems, this is a single
                // rename(2) syscall — the original file is replaced instantaneously with no
                // intermediate state where it's missing or partial.
                Files.move(temp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                // Fallback: Some Android/FUSE combinations do not support ATOMIC_MOVE even
                // within the same directory. A non-atomic REPLACE_EXISTING is still far safer
                // than the old direct-truncation approach because the original file remains
                // intact until this replace operation completes.
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException replaceFailure) {
            // Both move strategies failed — clean up the temp file and propagate the error.
            // The original INI file was never modified.
            temp.delete();
            throw replaceFailure;
        }
    }

    /**
     * Sends a single Android keyevent to the system input.
     *
     * <p>Executes {@code input keyevent <keycode>} via the Android shell. This simulates
     * a physical key press and is used to trigger hotkeys in emulators (e.g. DuckStation's
     * "Reload Post-Processing Shaders" key binding).</p>
     *
     * <p><b>Limitation:</b> The keyevent is delivered to whichever application currently has
     * input focus. If the target emulator is not in the foreground, the keyevent will be
     * consumed by a different application and have no effect on the emulator.</p>
     *
     * @param keycode the Android keycode to send (e.g. {@code KeyEvent.KEYCODE_F5} = 134)
     * @see <a href="https://developer.android.com/reference/android/view/KeyEvent">Android KeyEvent</a>
     */
    private static void sendKeyEvent(int keycode) {
        try {
            Runtime.getRuntime().exec(new String[]{"input", "keyevent", String.valueOf(keycode)});
        } catch (Exception e) {
            Log.w(TAG, "Failed to send keyevent " + keycode + ": " + e.getMessage());
        }
    }

    /**
     * Sends a key combination via an Android shell command.
     *
     * <p><b>Issue #9 fix:</b> The previous implementation split the command string on spaces
     * and passed the tokens directly to {@link Runtime#exec(String[])}. This bypasses the
     * shell entirely, which means the command is executed as a raw process invocation. While
     * this works for simple cases, it can behave differently from shell execution for commands
     * that rely on shell features. The fix wraps the command in {@code sh -c "..."} so it is
     * executed through a proper shell, which is how {@code input keyevent} is intended to be
     * invoked.</p>
     *
     * <p><b>KNOWN LIMITATION:</b> Simultaneous modifier key combos (like Ctrl+Alt+S) via
     * Android's {@code input keyevent --longpress} are fundamentally unreliable. The OS
     * sends sequential key-down events whose timing is not guaranteed to overlap, so the
     * target application may see individual key presses instead of a chord. There is no
     * reliable way to inject true simultaneous key combos on Android without root access
     * or a custom input method. The {@code input keycombination} command exists on some
     * Android versions (API 31+) but is not universally available. This method is a
     * best-effort approach; the INI file edit is the primary mechanism for applying changes.</p>
     *
     * @param command the full shell command string to execute (e.g. "input keyevent --longpress 113 57 47")
     */
    private static void sendKeyCombo(String command) {
        try {
            // Issue #9: Use sh -c to execute through a proper shell rather than splitting on
            // spaces and passing tokens directly to Runtime.exec(). The direct token approach
            // bypasses the shell, which can cause subtle behavioral differences for commands
            // like `input keyevent --longpress` that are designed to be invoked as shell commands.
            Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
        } catch (Exception e) {
            Log.w(TAG, "Failed to send key combo: " + e.getMessage());
        }
    }

    // =====================================================================
    // Dispatcher
    // =====================================================================

    /**
     * Main entry point for hot-applying emulator settings after a dock/undock event.
     *
     * <p>Called by {@link ProfileSwitcher} when the dock state changes and an emulator is
     * detected as running. Routes to the appropriate emulator-specific hot-apply method
     * based on the emulator identifier string.</p>
     *
     * <p>Valid emulator IDs and their corresponding methods:</p>
     * <ul>
     *   <li>{@code "retroarch"} -- {@link #hotApplyRetroArch(Context, boolean)}</li>
     *   <li>{@code "duckstation"} -- {@link #hotApplyDuckStation(Context, boolean)}</li>
     *   <li>{@code "scummvm"} -- {@link #hotApplyScummVM(Context, boolean)}</li>
     *   <li>{@code "ppsspp"} -- {@link #hotApplyPPSSPP(Context, boolean)}</li>
     * </ul>
     *
     * <p>Unrecognized emulator IDs are silently logged at DEBUG level and ignored.</p>
     *
     * @param ctx    application context, passed through to emulator-specific methods
     * @param emuId  emulator identifier string (e.g. "retroarch", "duckstation")
     * @param docked {@code true} if the device was just docked, {@code false} if just undocked
     */
    public static void hotApply(Context ctx, String emuId, boolean docked) {
        switch (emuId) {
            case "retroarch":
                hotApplyRetroArch(ctx, docked);
                break;
            case "duckstation":
                hotApplyDuckStation(ctx, docked);
                break;
            case "scummvm":
                hotApplyScummVM(ctx, docked);
                break;
            case "ppsspp":
                hotApplyPPSSPP(ctx, docked);
                break;
            default:
                Log.d(TAG, "No hot-apply available for: " + emuId);
                break;
        }
    }
}
