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
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

    /** Maximum number of times a hot-apply rewrite will re-read and retry after detecting
     * that the emulator changed the config file underneath us. */
    private static final int HOT_APPLY_MAX_RETRIES = 3;

    /**
     * Lock object for INI file editing operations.
     *
     * <p><b>Issue #8 fix -- INI editing thread safety:</b> Both {@link #modifyIniSection} and
     * {@link #modifyIniKey} perform read-modify-write cycles on INI files. Without synchronization,
     * concurrent hot-apply operations targeting the same file (e.g., two rapid dock/undock events)
     * could interleave their reads and writes, causing one edit to silently overwrite the other.
     * This lock ensures that all INI file modifications are serialized, preventing data loss from
     * concurrent read-modify-write cycles on the same file.</p>
     *
     * <h3>IMPORTANT LIMITATION (Audit Fix #6): This lock does NOT prevent the running
     * emulator from writing to the same file simultaneously</h3>
     *
     * <p>Hot-apply edits INI files <b>while the emulator is running and may also be writing
     * to those files</b>. This creates an unavoidable read-modify-write race condition:</p>
     * <ol>
     *   <li>RetroDock reads the entire INI file into memory</li>
     *   <li>The emulator writes to the same INI file (e.g., saving a setting change)</li>
     *   <li>RetroDock writes its modified version back — overwriting the emulator's change</li>
     * </ol>
     *
     * <p><b>Why this cannot be fully eliminated:</b> There is no cross-process file locking API
     * available on Android that both RetroDock and arbitrary emulators would honor. The
     * emulators are third-party applications with no knowledge of RetroDock's lock.</p>
     *
     * <p><b>What we do now instead:</b> each edit captures a content hash of the original file,
     * writes the replacement to a temp file, then re-checks the live file just before the atomic
     * replace. If the emulator changed the file in the meantime, RetroDock aborts and retries from
     * the new on-disk version instead of blindly overwriting it with a stale snapshot. This does
     * not create a perfect lock, but it turns silent lost updates into an explicit retry/abort
     * path, which is much safer for user configs.</p>
     *
     * <p><b>Mitigations in place:</b></p>
     * <ul>
     *   <li>{@link #writeLinesAtomically} minimizes the window by using temp-file-then-move
     *       instead of direct truncation</li>
     *   <li>{@link #rewriteTextConfig} detects stale snapshots and retries from the emulator's
     *       newest on-disk version instead of overwriting blindly</li>
     *   <li>The {@link #HOT_APPLY_DELAY_MS} delay gives the emulator time to finish its own
     *       post-dock writes before RetroDock touches the file</li>
     *   <li>The INI_LOCK serializes RetroDock's own threads so at least our writes don't
     *       conflict with each other</li>
     * </ul>
     */
    private static final Object INI_LOCK = new Object();

    /**
     * Immutable snapshot of a text config file at one instant in time.
     *
     * <p>Hot-apply needs two views of the original file:
     * <ul>
     *   <li>The parsed text lines so we can surgically edit keys/sections.</li>
     *   <li>A cryptographic digest of the raw bytes so we can detect whether the emulator wrote
     *       a newer version before our atomic replace.</li>
     * </ul>
     * Keeping those together prevents the "read one version, compare against another" bug class.</p>
     */
    private static final class ConfigFileSnapshot {
        final List<String> lines;
        final byte[] sha256;
        final long size;
        final long lastModified;

        ConfigFileSnapshot(List<String> lines, byte[] sha256, long size, long lastModified) {
            this.lines = lines;
            this.sha256 = sha256;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    /**
     * Signals that the emulator changed the config file after RetroDock read it but before
     * RetroDock could safely replace it.
     *
     * <p>This is not treated as a generic I/O error because the safest response is to retry from
     * the emulator's newest version, not to log a permanent failure immediately.</p>
     */
    private static final class StaleConfigSnapshotException extends IOException {
        StaleConfigSnapshotException(String message) {
            super(message);
        }
    }

    /** Functional interface used by the optimistic-concurrency rewrite helper. */
    private interface ConfigTransformer {
        List<String> transform(List<String> lines) throws IOException;
    }

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
     * Resolves the exact settings path RetroDock should hot-apply against for a built-in emulator.
     *
     * <p><b>Why this matters:</b> the swap engine and the hot-apply engine must agree on the same
     * physical file. If hot-apply edits one config while the swap engine manages another, the user
     * ends up with seemingly random persistence behavior: the running emulator sees one file, while
     * the next dock/undock swap manipulates a different one. We therefore delegate path resolution
     * to {@link ProfileSwitcher#findSettingsFile(SharedPreferences, EmulatorConfig, String)} so
     * hot-apply uses the same override-aware, cache-aware resolver as the swap engine.</p>
     */
    private static String findManagedSettingsPath(Context ctx, String emuId, String relPath) {
        EmulatorConfig emu = findKnownEmulator(emuId);
        if (emu == null) {
            Log.w(TAG, "No built-in emulator definition found for hot-apply id: " + emuId);
            return null;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return ProfileSwitcher.findSettingsFile(prefs, emu, relPath);
    }

    private static EmulatorConfig findKnownEmulator(String emuId) {
        for (EmulatorConfig emu : EmulatorConfig.getKnownDatabase()) {
            if (emu.id.equals(emuId)) {
                return emu;
            }
        }
        return null;
    }

    /**
     * Prepares RetroDock's trusted rollback snapshot before any live hot-apply.
     *
     * <p>Hot-apply is convenient, but once RetroDock changes a running emulator's live state the
     * currently mounted config file is no longer a trustworthy "latest saved profile". The
     * emulator may keep writing temporary session edits into that mounted file until it exits.
     * To prevent those temporary edits from being mislabeled as the new handheld or docked
     * profile, ProfileSwitcher snapshots the mounted profile immediately before the first live
     * change of the session. If that snapshot cannot be created safely, we refuse the live edit.</p>
     */
    private static boolean prepareTrackedHotApply(Context ctx, String emuId, String label) {
        if (ProfileSwitcher.ensureHotSwapSessionPrepared(ctx, emuId)) {
            return true;
        }

        Log.w(TAG, label + " hot-apply skipped because RetroDock could not prepare a trusted "
                + "pre-hot-swap rollback snapshot");
        return false;
    }

    /**
     * Finalizes session tracking after one live hot-apply attempt.
     *
     * <p>A successful live operation marks the session untrusted, meaning the mounted config must
     * be restored from the trusted snapshot on emulator exit before any real docked/handheld swap
     * is allowed to save. A failed operation does the opposite: if no prior hot-apply already
     * marked the session untrusted, the prepared snapshot is discarded so abandoned attempts do
     * not cause later clean exits to throw away valid user edits.</p>
     */
    private static void finishTrackedHotApply(Context ctx, String emuId, boolean applied, String label) {
        if (applied) {
            ProfileSwitcher.markHotSwapSessionApplied(ctx, emuId);
            return;
        }

        ProfileSwitcher.discardPreparedHotSwapSessionIfUnused(ctx, emuId);
        Log.w(TAG, label + " hot-apply did not complete, so the prepared trusted-session "
                + "snapshot was discarded");
    }

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
            String configDir = findManagedSettingsPath(ctx, "retroarch", "config");
            if (configDir == null) {
                Log.w(TAG, "RetroArch config dir not found, skipping shader hot-apply");
                return;
            }

            if (!prepareTrackedHotApply(ctx, "retroarch", "RetroArch")) {
                return;
            }

            String shaderPath = findGlobalShaderPreset(configDir, docked);
            boolean applied;
            if (shaderPath != null) {
                applied = sendUdpCommand("SET_SHADER " + shaderPath);
                if (applied) {
                    Log.i(TAG, "RetroArch hot-apply: SET_SHADER " + shaderPath);
                }
            } else {
                // No global shader preset in the backup dir — disable shaders.
                // SET_SHADER with no argument is idempotent and safe.
                applied = sendUdpCommand("SET_SHADER");
                if (applied) {
                    Log.i(TAG, "RetroArch hot-apply: no shader preset found in backup, disabling shaders");
                }
            }
            finishTrackedHotApply(ctx, "retroarch", applied, "RetroArch");
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
     * <p>This method runs synchronously on the background worker thread created by
     * {@link #scheduleDelayed}. Keeping the send synchronous lets the caller know whether the
     * live command really left the process, which in turn determines whether the current emulator
     * session must be marked "untrusted" and later restored from its trusted snapshot.</p>
     *
     * <p>Known RetroArch UDP commands used by RetroDock:</p>
     * <ul>
     *   <li>{@code SET_SHADER <path>} -- loads a shader preset from the given absolute path</li>
     *   <li>{@code SET_SHADER} (no argument) -- disables the current shader</li>
     * </ul>
     *
     * @param command the command string to send (without trailing newline; one is appended)
     * @return {@code true} if the UDP packet was sent successfully, {@code false} otherwise
     */
    private static boolean sendUdpCommand(String command) {
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
            byte[] data = (command + "\n").getBytes("UTF-8");
            InetAddress addr = InetAddress.getByName("127.0.0.1");
            DatagramPacket packet = new DatagramPacket(data, data.length, addr, RETROARCH_CMD_PORT);
            socket.send(packet);
            Log.i(TAG, "Sent RetroArch UDP command: " + command);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to send RetroArch UDP: " + e.getMessage());
            return false;
        }
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

            if (!prepareTrackedHotApply(ctx, "duckstation", "DuckStation")) {
                return;
            }

            // Rewrite the entire [PostProcessing] section with the new shader chain
            boolean modified = modifyIniSection(settingsPath, "PostProcessing", buildDuckStationShaderEntries(shaderChain));
            if (modified) {
                finishTrackedHotApply(ctx, "duckstation", true, "DuckStation");
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
            } else {
                finishTrackedHotApply(ctx, "duckstation", false, "DuckStation");
            }
        });
    }

    /**
     * Locates DuckStation's {@code settings.ini} file on the filesystem.
     *
     * <p>This method intentionally delegates to RetroDock's main path resolver instead of
     * hardcoding a search order. That keeps DuckStation hot-apply aligned with the swap engine's
     * override-aware, cache-aware root selection so both features operate on the same file.</p>
     *
     * @param ctx application context for accessing SharedPreferences
     * @return absolute path to settings.ini if found, or {@code null} if not found
     */
    private static String findDuckStationSettings(Context ctx) {
        return findManagedSettingsPath(ctx, "duckstation", "settings.ini");
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

            if (!prepareTrackedHotApply(ctx, "scummvm", "ScummVM")) {
                return;
            }

            // Audit Fix #13: Update all ScummVM graphics keys in one atomic rewrite.
            //
            // CONCERN: The previous code issued up to four separate full-file rewrites
            // (gfx_mode, scaler, filtering, aspect_ratio). If rewrite #3 failed, the file was
            // still syntactically valid but only half updated for the new dock state.
            //
            // FIX: Build one key map and apply it with modifyIniKeys(), so the ScummVM config
            // transitions as a single logical unit.
            Map<String, String> updates = new LinkedHashMap<>();
            if (!scaler.isEmpty()) {
                // Both gfx_mode and scaler are set for compatibility with different ScummVM versions.
                updates.put("gfx_mode", scaler);
                updates.put("scaler", scaler);
            }
            updates.put("filtering", filtering ? "true" : "false");
            updates.put("aspect_ratio", aspect ? "true" : "false");

            if (!modifyIniKeys(settingsPath, "scummvm", updates)) {
                finishTrackedHotApply(ctx, "scummvm", false, "ScummVM");
                Log.w(TAG, "ScummVM hot-apply: failed to update scummvm.ini safely");
                return;
            }
            finishTrackedHotApply(ctx, "scummvm", true, "ScummVM");
            if (!scaler.isEmpty()) {
                Log.i(TAG, "ScummVM hot-apply: set scaler to " + scaler);
            }

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
     * <p>Uses the same managed resolver as the swap engine so a per-entry override or previously
     * chosen root applies equally to hot-apply and full profile swaps.</p>
     *
     * @param ctx application context for accessing SharedPreferences
     * @return absolute path to scummvm.ini if found, or {@code null} if not found
     */
    private static String findScummVMSettings(Context ctx) {
        return findManagedSettingsPath(ctx, "scummvm", "scummvm.ini");
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

            if (!prepareTrackedHotApply(ctx, "ppsspp", "PPSSPP")) {
                return;
            }

            boolean modified;
            if (!shader.isEmpty()) {
                modified = modifyIniKey(settingsPath, "Graphics", "PostProcessingShader", shader);
                if (modified) {
                    Log.i(TAG, "PPSSPP hot-apply: set PostProcessingShader to " + shader);
                }
            } else {
                modified = modifyIniKey(settingsPath, "Graphics", "PostProcessingShader", "Off");
                if (modified) {
                    Log.i(TAG, "PPSSPP hot-apply: disabled PostProcessingShader");
                }
            }

            if (modified) {
                finishTrackedHotApply(ctx, "ppsspp", true, "PPSSPP");
            } else {
                finishTrackedHotApply(ctx, "ppsspp", false, "PPSSPP");
            }
            // No reliable way to force PPSSPP to reload mid-game.
            // Change takes effect on next game load or restart.
        });
    }

    /**
     * Locates PPSSPP's {@code ppsspp.ini} configuration file on the filesystem.
     *
     * <p>Uses the same override-aware resolver as the swap engine rather than a private path
     * guesser. That prevents hot-apply from editing a stale legacy config while the real swap
     * engine manages a different active root.</p>
     *
     * @param ctx application context for accessing SharedPreferences
     * @return absolute path to ppsspp.ini if found, or {@code null} if not found
     */
    private static String findPPSSPPSettings(Context ctx) {
        return findManagedSettingsPath(ctx, "ppsspp", "SYSTEM/ppsspp.ini");
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
     * Optimistic-concurrency wrapper around all text-config rewrites.
     *
     * <p>The dangerous race in hot-apply is not "RetroDock has two threads" -- the
     * {@link #INI_LOCK} already handles that. The real problem is RetroDock versus the running
     * emulator. This helper reads a snapshot of the live file, lets the caller transform the
     * parsed lines, writes the replacement to a temp file, and then verifies the original file is
     * still byte-for-byte identical to the snapshot before replacing it. If the emulator changed
     * the file in the meantime, the replace is aborted and retried from the new version.</p>
     *
     * <p>This does not create a perfect inter-process lock -- a tiny race window still exists
     * between the final comparison and the atomic move -- but it eliminates the overwhelmingly
     * common "read stale snapshot, blindly overwrite newer config" failure mode.</p>
     */
    private static boolean rewriteTextConfig(File file, String logLabel, ConfigTransformer transformer) {
        synchronized (INI_LOCK) {
            for (int attempt = 1; attempt <= HOT_APPLY_MAX_RETRIES; attempt++) {
                try {
                    ConfigFileSnapshot snapshot = readConfigSnapshot(file);
                    List<String> output = transformer.transform(snapshot.lines);
                    writeLinesAtomically(file, output, snapshot);
                    return true;
                } catch (StaleConfigSnapshotException stale) {
                    if (attempt == HOT_APPLY_MAX_RETRIES) {
                        Log.w(TAG, logLabel + " aborted because the emulator kept rewriting the "
                                + "config file underneath RetroDock: " + stale.getMessage());
                        return false;
                    }
                    Log.w(TAG, logLabel + " detected concurrent emulator write; retrying ("
                            + attempt + "/" + HOT_APPLY_MAX_RETRIES + "): " + stale.getMessage());
                } catch (Exception e) {
                    Log.e(TAG, logLabel + " failed: " + e.getMessage());
                    return false;
                }
            }
        }
        return false;
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
        File file = new File(filePath);
        if (!file.exists()) return false;

        return rewriteTextConfig(file, "Failed to modify INI section [" + section + "] in " + filePath,
                lines -> {
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
                            // Audit Fix #8: Handle duplicate sections in malformed INI files.
                            //
                            // CONCERN: If a section appears twice (e.g., two [PostProcessing]
                            // headers), the old code would add newEntries under BOTH headers,
                            // duplicating the settings. On subsequent edits, each pass would
                            // double the entries again, causing the file to grow unboundedly.
                            //
                            // FIX: Only add entries under the FIRST occurrence of the section.
                            // Subsequent duplicate section headers are still detected (inSection
                            // is set to true so their old content is stripped), but newEntries
                            // are NOT added again. This effectively merges duplicates into one.
                            inSection = true;
                            trailingBlanks.clear();
                            if (!sectionFound) {
                                // First occurrence: write header and new entries
                                sectionFound = true;
                                output.add(l);
                                output.addAll(newEntries);
                            } else {
                                // Duplicate occurrence: strip header and contents (skip output.add)
                                Log.w(TAG, "Duplicate section [" + section + "] found in " + filePath
                                        + " — stripping duplicate to prevent entry duplication");
                            }
                            continue;
                        }
                        if (inSection) {
                            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                                // We are leaving the target section and entering a new one.
                                // Issue #10: Re-insert any trailing blank lines that appeared before
                                // this next section header to preserve visual separation between sections.
                                output.addAll(trailingBlanks);
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
                        output.addAll(newEntries);
                    }
                    return output;
                });
    }

    /**
     * Atomically updates one or more key-value pairs inside a single INI section.
     *
     * <p>This is the multi-key companion to {@link #modifyIniKey}. It exists because some
     * emulators, especially ScummVM, need several keys changed together to represent one logical
     * graphics mode transition. Writing those keys one by one left the file valid but only
     * partially updated if the third or fourth write failed. By applying the entire key set in
     * one optimistic-concurrency rewrite, the config is either updated completely or not at all.</p>
     */
    static boolean modifyIniKeys(String filePath, String section, Map<String, String> updates) {
        File file = new File(filePath);
        if (!file.exists()) return false;
        if (updates == null || updates.isEmpty()) return true;

        return rewriteTextConfig(file, "Failed to modify INI keys in [" + section + "] of " + filePath,
                lines -> {
                    List<String> output = new ArrayList<>();
                    boolean inSection = false;
                    boolean sectionFound = false;
                    String sectionHeader = "[" + section + "]";

                    // Track which keys we have already written so duplicate stale copies in the
                    // original file can be stripped instead of surviving beside the new values.
                    Map<String, Boolean> writtenKeys = new LinkedHashMap<>();
                    for (String key : updates.keySet()) {
                        writtenKeys.put(key, false);
                    }

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
                                for (Map.Entry<String, String> update : updates.entrySet()) {
                                    if (!writtenKeys.get(update.getKey())) {
                                        output.add(update.getKey() + " = " + update.getValue());
                                        writtenKeys.put(update.getKey(), true);
                                    }
                                }
                                inSection = false;
                                output.add(l);
                                continue;
                            }

                            if (trimmed.contains("=")) {
                                int eqIndex = trimmed.indexOf('=');
                                String lineKey = trimmed.substring(0, eqIndex).trim();
                                if (updates.containsKey(lineKey)) {
                                    // Audit Fix #12: Collapse duplicate key definitions down to the
                                    // one authoritative value from 'updates'. The first match emits
                                    // the replacement; later duplicates are stripped.
                                    if (!writtenKeys.get(lineKey)) {
                                        output.add(lineKey + " = " + updates.get(lineKey));
                                        writtenKeys.put(lineKey, true);
                                    }
                                    continue;
                                }
                            }
                        }
                        output.add(l);
                    }

                    if (inSection) {
                        for (Map.Entry<String, String> update : updates.entrySet()) {
                            if (!writtenKeys.get(update.getKey())) {
                                output.add(update.getKey() + " = " + update.getValue());
                                writtenKeys.put(update.getKey(), true);
                            }
                        }
                    }

                    if (!sectionFound) {
                        output.add("");
                        output.add(sectionHeader);
                        for (Map.Entry<String, String> update : updates.entrySet()) {
                            output.add(update.getKey() + " = " + update.getValue());
                        }
                    }

                    return output;
                });
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
        Map<String, String> singleUpdate = new LinkedHashMap<>();
        singleUpdate.put(key, value);
        return modifyIniKeys(filePath, section, singleUpdate);
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
     * <h3>Stale-snapshot protection</h3>
     * <p>Before replacing the original, we compare its current raw-byte hash against the
     * {@code expectedOriginal} snapshot that the caller read. If the emulator saved a newer
     * version in the meantime, we abort instead of clobbering it with RetroDock's stale view.</p>
     *
     * @param file             the original INI/config file to replace
     * @param output           the complete list of lines that should constitute the new file contents
     * @param expectedOriginal snapshot of the original file that was used to compute {@code output}
     * @throws IOException if the temp file cannot be written or the replacement fails
     */
    private static void writeLinesAtomically(File file, List<String> output,
                                             ConfigFileSnapshot expectedOriginal) throws IOException {
        // Step 1: Create a uniquely-named temp file next to the original.
        // The nanoTime() suffix prevents collisions if two hot-apply operations target
        // the same INI file in rapid succession (shouldn't happen due to INI_LOCK, but
        // defensive naming costs nothing).
        File temp = new File(file.getAbsolutePath() + ".retrodock-write." + System.nanoTime() + ".tmp");

        // Audit Fix #9: Capture the original file's permissions before we replace it.
        //
        // CONCERN: Files.move(REPLACE_EXISTING) may not preserve file metadata (permissions,
        // SELinux context) on all Android/FUSE combinations. If the replacement file has
        // different permissions, the emulator may not be able to read its own config file,
        // effectively corrupting the user's settings by making them inaccessible.
        //
        // FIX: Record whether the original was readable/writable/executable before the
        // move, then restore those permissions on the replacement. This handles the common
        // case. SELinux labels are managed by the OS and cannot be set from Java, but
        // standard POSIX permissions cover the majority of real-world access issues.
        boolean origReadable = file.canRead();
        boolean origWritable = file.canWrite();
        boolean origExecutable = file.canExecute();
        long origSize = file.length();

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
            // Audit Fix #14: Refuse to overwrite a config file that changed since we read it.
            //
            // CONCERN: Temp-file replacement solves truncation, but by itself it does not solve
            // stale snapshots. The emulator could have written a newer config version after
            // RetroDock read the file but before RetroDock moved the temp into place.
            //
            // FIX: Compare the live file against the snapshot used to derive 'output'. If the
            // hashes differ, the caller must re-read and retry from the latest on-disk content.
            if (!matchesSnapshot(file, expectedOriginal)) {
                temp.delete();
                throw new StaleConfigSnapshotException("live file changed after read (path="
                        + file.getAbsolutePath() + ", expectedSize=" + expectedOriginal.size
                        + ", currentSize=" + file.length() + ")");
            }
            try {
                // First attempt: atomic move. On supported filesystems, this is a single
                // rename(2) syscall — the original file is replaced instantaneously with no
                // intermediate state where it's missing or partial.
                Files.move(temp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                // Fallback: Some Android/FUSE combinations do not support ATOMIC_MOVE even
                // within the same directory.
                //
                // Audit Fix #5: CONCERN — A non-atomic REPLACE_EXISTING could be interrupted
                // by a power loss or process kill mid-move, leaving both the temp and original
                // in a corrupt state. This is a fundamental limitation: without kernel-level
                // atomic rename support, no userspace workaround can guarantee atomicity.
                //
                // MITIGATION: After the non-atomic move, we verify the replacement file's
                // size is reasonable (non-zero and not drastically smaller than what we wrote).
                // A zero-byte or truncated file is a strong signal of corruption. In that case,
                // we log a critical error but cannot recover — the damage is done at the
                // filesystem level. This at least makes the failure visible instead of silent.
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);

                // Post-move integrity check: verify the file wasn't truncated by the move
                long newSize = file.length();
                if (newSize == 0 && !output.isEmpty()) {
                    Log.e(TAG, "CRITICAL: Non-atomic file replacement produced a 0-byte file! "
                            + "The config file may be corrupted: " + file.getAbsolutePath()
                            + " (original was " + origSize + " bytes). "
                            + "This can happen if the device lost power during a FUSE move. "
                            + "The emulator may need to regenerate its config.");
                }
            }
        } catch (IOException replaceFailure) {
            // Both move strategies failed — clean up the temp file and propagate the error.
            // The original INI file was never modified.
            temp.delete();
            throw replaceFailure;
        }

        // Audit Fix #9: Restore the original file's permissions on the replacement.
        // This is a best-effort operation — if it fails, the file contents are still
        // correct, just potentially with different access permissions.
        file.setReadable(origReadable);
        file.setWritable(origWritable);
        if (origExecutable) {
            file.setExecutable(true);
        }
    }

    private static ConfigFileSnapshot readConfigSnapshot(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new StringReader(new String(bytes, Charset.defaultCharset())))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return new ConfigFileSnapshot(lines, sha256(bytes), bytes.length, file.lastModified());
    }

    private static boolean matchesSnapshot(File file, ConfigFileSnapshot snapshot) throws IOException {
        if (!file.exists()) {
            return false;
        }
        byte[] currentBytes = Files.readAllBytes(file.toPath());
        if (currentBytes.length != snapshot.size) {
            return false;
        }
        return MessageDigest.isEqual(snapshot.sha256, sha256(currentBytes));
    }

    private static byte[] sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    // =====================================================================
    // Flat Config File Editing (no sections, e.g. retroarch.cfg)
    // =====================================================================

    /**
     * Atomically updates one or more key-value pairs in a flat (sectionless) config file.
     *
     * <p>This exists for cases like RetroArch bootstrap settings where two related keys must be
     * kept in sync. Writing them together avoids half-updated flat configs in the same way
     * {@link #modifyIniKeys} avoids half-updated section-based INI files.</p>
     */
    static boolean modifyFlatKeys(String filePath, Map<String, String> updates) {
        File file = new File(filePath);
        if (!file.exists()) return false;
        if (updates == null || updates.isEmpty()) return true;

        return rewriteTextConfig(file, "Failed to modify flat config keys in " + filePath,
                lines -> {
                    List<String> output = new ArrayList<>();
                    Map<String, Boolean> writtenKeys = new LinkedHashMap<>();
                    for (String key : updates.keySet()) {
                        writtenKeys.put(key, false);
                    }

                    for (String l : lines) {
                        String trimmed = l.trim();
                        if (trimmed.contains("=")) {
                            int eqIndex = trimmed.indexOf('=');
                            String lineKey = trimmed.substring(0, eqIndex).trim();
                            if (updates.containsKey(lineKey)) {
                                if (!writtenKeys.get(lineKey)) {
                                    output.add(lineKey + " = \"" + updates.get(lineKey) + "\"");
                                    writtenKeys.put(lineKey, true);
                                }
                                continue;
                            }
                        }
                        output.add(l);
                    }

                    for (Map.Entry<String, String> update : updates.entrySet()) {
                        if (!writtenKeys.get(update.getKey())) {
                            output.add(update.getKey() + " = \"" + update.getValue() + "\"");
                            writtenKeys.put(update.getKey(), true);
                        }
                    }
                    return output;
                });
    }

    /**
     * Modifies a single key-value pair in a flat (sectionless) config file.
     *
     * <p>Unlike {@link #modifyIniKey}, which operates within a specific {@code [Section]},
     * this method handles config files like RetroArch's {@code retroarch.cfg} that use
     * bare {@code key = "value"} pairs with no section headers.</p>
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Read all lines from the file.</li>
     *   <li>For each line, split on the first {@code =} and compare the trimmed left-hand
     *       side against the target key.</li>
     *   <li>If found, replace the line with {@code key = "value"}.</li>
     *   <li>If not found after scanning the entire file, append the key-value pair at the end.</li>
     * </ol>
     *
     * <p>Uses {@link #writeLinesAtomically} for the same crash-safe write guarantees as
     * the section-based INI methods.</p>
     *
     * <p><b>Thread safety:</b> Synchronized on {@link #INI_LOCK} to prevent concurrent
     * read-modify-write corruption.</p>
     *
     * @param filePath absolute path to the config file
     * @param key      the config key to set (e.g. "network_cmd_enable")
     * @param value    the value to assign, will be quoted (e.g. "true" becomes {@code "true"})
     * @return {@code true} if the file was successfully modified
     */
    static boolean modifyFlatKey(String filePath, String key, String value) {
        Map<String, String> singleUpdate = new LinkedHashMap<>();
        singleUpdate.put(key, value);
        return modifyFlatKeys(filePath, singleUpdate);
    }

    /**
     * Enables or disables RetroArch's built-in network command interface by modifying
     * {@code network_cmd_enable} and {@code network_cmd_port} in {@code retroarch.cfg}.
     *
     * <h3>Why this exists</h3>
     * <p>RetroArch's UDP command interface (used for live shader hot-apply via
     * {@code SET_SHADER}) requires {@code network_cmd_enable = "true"} in the config file.
     * Users frequently report that enabling this setting via RetroArch's UI doesn't persist
     * across restarts. By writing it directly to the config file when the user enables
     * the RetroDock live shader swap toggle, we ensure the setting is always present.</p>
     *
     * <h3>When this is called</h3>
     * <ul>
     *   <li>User enables "Live Shader Swap" toggle in EmulatorSettingsActivity:
     *       sets {@code network_cmd_enable = "true"} and {@code network_cmd_port = "55355"}</li>
     *   <li>User disables "Live Shader Swap" toggle:
     *       sets {@code network_cmd_enable = "false"} (leaves port unchanged)</li>
     * </ul>
     *
     * @param ctx    application context used to resolve the exact managed retroarch.cfg path
     * @param enable {@code true} to enable network commands, {@code false} to disable
     * @return {@code true} if the config file was successfully modified
     */
    public static boolean setRetroArchNetworkCommands(Context ctx, boolean enable) {
        // Resolve retroarch.cfg through the same override-aware path resolver used by the swap
        // engine. This keeps the persistent hot-apply bootstrap edit aligned with the file
        // RetroDock will later swap and recover.
        String cfgPath = findManagedSettingsPath(ctx, "retroarch", "retroarch.cfg");
        if (cfgPath == null) {
            Log.w(TAG, "retroarch.cfg not found, cannot set network_cmd_enable");
            return false;
        }

        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("network_cmd_enable", enable ? "true" : "false");
        if (enable) {
            // Also ensure the port is set to the expected value. Both keys are written in one
            // atomic rewrite so RetroArch never sees a half-updated command configuration.
            updates.put("network_cmd_port", "55355");
        }
        boolean ok = modifyFlatKeys(cfgPath, updates);
        Log.i(TAG, "RetroArch network_cmd_enable set to " + enable + " in " + cfgPath);
        return ok;
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
