/*
 * Copyright (c) 2026 Gavamedia (https://gavamedia.com)
 * Licensed under the MIT License. See LICENSE file in the project root.
 */
package com.gavamedia.retrodock;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ProfileSwitcher manages automatic swapping of emulator settings files when the
 * device transitions between docked and handheld modes.
 *
 * <h2>Overview</h2>
 * <p>
 * Each supported emulator has one or more settings entries (files or directories, e.g.
 * {@code retroarch.cfg}, {@code config/}). For every entry, up to three versions can exist
 * on disk at any time:
 * </p>
 * <pre>
 *   retroarch.cfg            -- the ACTIVE copy (what the emulator actually reads)
 *   retroarch.cfg.docked     -- backup of the docked variant
 *   retroarch.cfg.handheld   -- backup of the handheld variant
 * </pre>
 * <p>
 * <b>Invariant:</b> only ONE backup should exist at a time -- the opposite of whichever
 * variant is currently active. For example, when the device is docked the active file
 * contains docked settings and only {@code .handheld} backup exists.
 * </p>
 *
 * <h2>Swap Flow (high level)</h2>
 * <pre>
 *   DisplayMonitorService detects dock state change
 *           |
 *           v
 *   swapProfiles(ctx, docked, statusPath)
 *           |
 *           +-- recoverFromPartialSwap()  (crash recovery -- clean up orphaned .swaptmp files)
 *           |
 *           +-- for each enabled emulator:
 *           |       |
 *           |       +-- emulator IS running?
 *           |       |       yes -> showRestartNotification()
 *           |       |              watchForExit()   (polls until process exits)
 *           |       |              hotApply()        (live shader/filter changes)
 *           |       |
 *           |       +-- emulator NOT running?
 *           |               swapSettings()
 *           |                   |
 *           |                   +-- for each settings entry:
 *           |                           findSettingsFile()  (resolve across root paths)
 *           |                           swapSingleEntry()   (atomic 3-step rename)
 *           v
 *         done
 * </pre>
 *
 * <h2>Atomic 3-Step Rename ({@link #swapSingleEntry})</h2>
 * <p>
 * When both an active file and a target backup exist, the swap is performed as three
 * sequential renames to avoid any window where data is lost:
 * </p>
 * <pre>
 *   Example: transitioning from handheld -> docked
 *
 *   BEFORE:
 *     retroarch.cfg            (contains handheld settings -- currently active)
 *     retroarch.cfg.docked     (backup of docked settings)
 *
 *   Step 1: retroarch.cfg  ->  retroarch.cfg.swaptmp.handheld     (park current aside)
 *   Step 2: retroarch.cfg.docked  ->  retroarch.cfg      (restore docked as active)
 *   Step 3: retroarch.cfg.swaptmp.handheld  ->  retroarch.cfg.handheld  (save old as handheld backup)
 *
 *   AFTER:
 *     retroarch.cfg            (contains docked settings -- now active)
 *     retroarch.cfg.handheld   (backup of handheld settings)
 * </pre>
 * <p>
 * If step 2 fails, step 1 is rolled back (temp renamed back to active). Step 3 failure
 * is logged but treated as non-fatal since the active file is already correct.
 * </p>
 *
 * <h2>Crash Recovery ({@link #recoverFromPartialSwap})</h2>
 * <p>
 * If the app crashes or is killed between steps of the 3-step rename, orphaned
 * {@code .swaptmp} files may remain on disk. The recovery method is called at the start
 * of every {@link #swapProfiles} invocation to detect and resolve these orphans before
 * any new swap is attempted.
 * </p>
 *
 * <h2>First-Time Swap (Classification)</h2>
 * <p>
 * When the user first enables an emulator, they classify their current settings as either
 * "docked" or "handheld". On the first dock/undock event after classification:
 * </p>
 * <ul>
 *   <li>There is no backup yet -- only the active file exists.</li>
 *   <li>If the user classified as "handheld" and we are now docking, the active file is
 *       renamed to {@code .handheld}. The emulator will then have no active config, which
 *       causes it to regenerate defaults (the docked defaults).</li>
 *   <li>The {@code classified} preference is now retained as a bootstrap hint, while each
 *       individual settings entry records whether it has already consumed that hint. This lets
 *       late-appearing config files still be initialized correctly without reusing the hint for
 *       entries that were already seeded.</li>
 * </ul>
 *
 * <h2>Exit Watcher</h2>
 * <p>
 * When a dock event occurs while an emulator is running, we cannot safely swap its files
 * (the emulator may overwrite them on exit). Instead, a polling watcher is started:
 * </p>
 * <ul>
 *   <li>Polls every {@value #PROCESS_CHECK_INTERVAL_MS}ms checking if the emulator process
 *       has exited.</li>
 *   <li>On exit, the watcher <b>re-reads the DRM connector status</b> to determine the
 *       <i>current</i> dock state -- NOT the state from when the swap was originally requested.</li>
 *   <li>This correctly handles rapid state changes, e.g.: user docks -> emulator is running ->
 *       user undocks -> emulator exits. The watcher sees "undocked" and does NOT mistakenly
 *       apply docked settings.</li>
 *   <li>If no swap is needed (the active profile already matches the current state), the
 *       watcher simply dismisses its notification.</li>
 * </ul>
 */
public class ProfileSwitcher {

    // ==================================================================================
    // Constants
    // ==================================================================================

    private static final String TAG = "RetroDock";
    private static final String PREFS_NAME = "retrodock_prefs";
    private static final String CHANNEL_ID = "retrodock_profile";
    private static final int NOTIFY_ID_BASE = 2000;

    /** Interval between process-alive checks in the exit watcher. */
    private static final long PROCESS_CHECK_INTERVAL_MS = 3000;

    /**
     * Swap-temp naming scheme used by the 3-step rename flow.
     *
     * <p><b>Problem in the old design:</b> every interrupted swap left behind the same generic
     * {@code .swaptmp} file name. If RetroDock later found both the active file and that temp
     * file, it could no longer tell whether the temp should become {@code .docked} or
     * {@code .handheld}. The previous recovery code therefore deleted the temp in that state,
     * which could permanently destroy the only remaining copy of the opposite profile.</p>
     *
     * <p><b>Fix:</b> temp files are now self-describing. We encode which backup slot the temp
     * should eventually become directly into the filename ({@code .swaptmp.docked} or
     * {@code .swaptmp.handheld}). Recovery can therefore finish step 3 safely without guessing.
     * The legacy generic {@code .swaptmp} name is still recognized for backward compatibility,
     * but it is quarantined instead of deleted when the correct destination cannot be proven.</p>
     */
    private static final String SWAPTMP_DOCKED_SUFFIX = ".swaptmp.docked";
    private static final String SWAPTMP_HANDHELD_SUFFIX = ".swaptmp.handheld";
    private static final String LEGACY_SWAPTMP_SUFFIX = ".swaptmp";
    private static final String QUARANTINE_SUFFIX = ".retrodock-orphan";

    /**
     * SharedPreferences key prefixes for per-entry path resolution and first-swap seeding.
     *
     * <p><b>Override key:</b> user-selected absolute path for one settings entry. The UI has
     * always let the user choose these paths, but the old swap engine ignored them and still
     * operated on whichever auto-detected root it found first. That created a serious integrity
     * risk: the UI could show one config path while the rename engine touched a different one.</p>
     *
     * <p><b>Resolved-path cache:</b> when several candidate roots all contain the same settings
     * entry (for example a legacy shared-storage folder and a scoped-storage folder), repeatedly
     * choosing "the first one" is brittle. RetroDock now scores candidates and caches the winner
     * so the same physical path is used consistently across status display, swap detection, and
     * actual file moves until it disappears.</p>
     *
     * <p><b>Seeded key:</b> tracks whether a settings entry has already completed its one-time
     * "initial classification" bootstrap. The old code cleared the emulator-wide classification
     * as soon as any one entry swapped successfully, which could leave later-created config files
     * without the original handheld/docked hint they still needed. Seeding is now tracked per
     * entry so late-appearing files can still be initialized correctly without reusing the hint
     * for entries that were already bootstrapped.</p>
     */
    private static final String OVERRIDE_KEY_PREFIX = "emu_%s_override_%s";
    private static final String RESOLVED_PATH_KEY_PREFIX = "emu_%s_resolved_%s";
    private static final String SEEDED_KEY_PREFIX = "emu_%s_seeded_%s";

    // ==================================================================================
    // Exit Watcher State
    // ==================================================================================

    /**
     * Tracks active exit-watcher runnables keyed by emulator ID.
     * Prevents duplicate watchers for the same emulator if multiple dock events
     * fire in quick succession. All accesses must be synchronized on this object.
     */
    private static final Map<String, Runnable> activeWatchers = new HashMap<>();

    /** Shared handler for scheduling periodic exit-watcher checks on the main looper. */
    private static Handler watchHandler;

    // ==================================================================================
    // Public Entry Point
    // ==================================================================================

    /**
     * Main entry point called by {@code DisplayMonitorService} when the dock state changes.
     * <p>
     * Iterates over all installed and user-enabled emulators. For each one, either
     * performs an immediate settings swap (if the emulator is not running) or defers the
     * swap by starting an exit watcher (if it is running).
     * </p>
     *
     * @param ctx        application context, used for SharedPreferences, notifications,
     *                   and process queries
     * @param docked     {@code true} if the device has just been docked,
     *                   {@code false} if undocked
     * @param statusPath absolute path to the DRM connector status file, passed to the
     *                   exit watcher so it can re-read the current state later
     */
    public static void swapProfiles(Context ctx, boolean docked, String statusPath) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Global kill switch -- bail if the user has turned off profile switching entirely
        if (!prefs.getBoolean("profile_switch_enabled", false)) {
            return;
        }

        List<EmulatorConfig> emulators = EmulatorConfig.getInstalled(ctx);

        for (int i = 0; i < emulators.size(); i++) {
            EmulatorConfig emu = emulators.get(i);

            // Per-emulator enable toggle
            if (!prefs.getBoolean("emu_" + emu.id + "_enabled", false)) {
                continue;
            }

            // Custom emulators previously allowed an empty package name, but that means RetroDock
            // has no safe way to tell whether the emulator is still running. Since renaming config
            // files under a live emulator is exactly the failure mode we are trying to avoid, the
            // safe behavior is to skip automatic swapping until the user supplies a package name.
            if (!hasProcessDetectionIdentity(emu)) {
                Log.w(TAG, "Skipping " + emu.displayName + " because no package name is configured. " +
                        "Without process detection RetroDock cannot safely rename its settings files.");
                continue;
            }

            // FIX Issue #18: Use stable notification IDs based on emulator ID hash instead of
            // list position. Position-based IDs (NOTIFY_ID_BASE + i) are fragile because the
            // emulator list order can change when apps are installed/uninstalled, causing
            // notifications to overwrite the wrong emulator's notification or fail to dismiss.
            // Bitmask (& 0x7FFFFFFF) ensures a non-negative value; Math.abs() would fail for
            // Integer.MIN_VALUE, which returns Integer.MIN_VALUE (still negative).
            int notifyId = NOTIFY_ID_BASE + (emu.id.hashCode() & 0x7FFFFFFF);

            // --- Running emulator path: defer swap, notify user, hot-apply shaders ---
            boolean running = isAnyPackageRunning(ctx, emu.packageNames);
            if (running) {
                Log.i(TAG, emu.displayName + " is running, deferring swap until exit");
                showRestartNotification(ctx, emu.displayName, docked, notifyId);
                watchForExit(ctx, emu, statusPath, notifyId);

                // Hot-apply safe settings (shaders/filters) while emulator is running
                EmulatorHotApply.hotApply(ctx, emu.id, docked);
                continue;
            }

            // --- Idle emulator path: swap settings files immediately ---
            // Recover this emulator's interrupted temp files only after we have proved it is not
            // running. Recovering while the emulator is still alive would itself be a file move
            // against a live config tree, which defeats the entire "wait until exit" safety rule.
            recoverFromPartialSwap(prefs, emu);
            String classified = prefs.getString("emu_" + emu.id + "_classified", "");
            boolean ok = swapSettings(prefs, emu, docked, classified);
            if (ok) {
                Log.i(TAG, "Swapped settings for " + emu.displayName + " (docked=" + docked + ")");
            } else {
                Log.w(TAG, "Failed to swap settings for " + emu.displayName);
            }
        }
    }

    // ==================================================================================
    // Crash Recovery (Issue #2 & #3)
    // ==================================================================================

    /**
     * Scans all enabled emulators for orphaned {@code .swaptmp} files left behind by
     * interrupted swaps, and recovers them to a consistent state.
     * <p>
     * This method MUST be called before any new swap is attempted (at the start of
     * {@link #swapProfiles}) to ensure the filesystem is in a clean state. Without this
     * recovery, a crash between step 1 and step 2 of the 3-step rename would leave the
     * active file missing and the user's settings stranded in a {@code .swaptmp} file
     * that nothing ever reads.
     * </p>
     *
     * <h3>Recovery logic per settings entry:</h3>
     * <ul>
     *   <li><b>Named temp exists, active file missing:</b> crash happened after step 1 but
     *       before step 2. Recovery rolls back temp -> active.</li>
     *   <li><b>Named temp exists, active file present, destination backup missing:</b> crash
     *       happened after step 2 but before step 3. Recovery completes temp -> backup.</li>
     *   <li><b>Legacy generic .swaptmp exists:</b> restore it to active only when the active
     *       file is missing; otherwise quarantine it because its destination profile is
     *       ambiguous.</li>
     * </ul>
     *
     * @param prefs shared preferences for checking per-emulator enable state
     * @param emu   emulator configuration whose settings entries should be recovered
     */
    private static void recoverFromPartialSwap(SharedPreferences prefs, EmulatorConfig emu) {
        // Only recover for emulators the user has enabled -- we should not touch files
        // for emulators the user never configured.
        if (!prefs.getBoolean("emu_" + emu.id + "_enabled", false)) {
            return;
        }

        if (emu.settingsFiles == null || emu.settingsFiles.length == 0) {
            return;
        }

        for (String relPath : emu.settingsFiles) {
            String resolved = findSettingsFileIncludingSwaptmp(prefs, emu, relPath);
            if (resolved == null) {
                continue;
            }

            File activeFile = new File(resolved);
            File dockedBackup = new File(resolved + ".docked");
            File handheldBackup = new File(resolved + ".handheld");

            // Self-describing temp files are the new, fully recoverable format. If the
            // active file is missing, the swap crashed before step 2 and we roll back to
            // the original active file. If the active file exists, the swap crashed after
            // step 2 and the temp should be finalized into its intended backup slot.
            recoverNamedTemp(activeFile, dockedBackup, new File(resolved + SWAPTMP_DOCKED_SUFFIX), "docked");
            recoverNamedTemp(activeFile, handheldBackup, new File(resolved + SWAPTMP_HANDHELD_SUFFIX), "handheld");

            // Legacy generic .swaptmp files are ambiguous once an active file exists: we can
            // prove they contain "the other profile", but we cannot prove whether that means
            // docked or handheld. The old code deleted them in this state, which was data
            // loss. We now preserve them by moving them to a quarantined filename so future
            // swaps do not collide with them and manual recovery remains possible.
            recoverLegacyTemp(activeFile, new File(resolved + LEGACY_SWAPTMP_SUFFIX));
        }
    }

    /**
     * Extended version of {@link #findSettingsFile} that also considers the presence
     * of {@code .swaptmp} files when resolving paths. This is needed during crash recovery
     * because the active file may not exist (it was renamed to .swaptmp), so the standard
     * finder would miss it.
     *
     * @param prefs   preferences used for override-aware resolution
     * @param emu     emulator definition whose settings entry is being resolved
     * @param relPath relative path of the settings entry within a root directory
     * @return the full absolute path (without any suffix), or {@code null} if not found
     */
    private static String findSettingsFileIncludingSwaptmp(SharedPreferences prefs, EmulatorConfig emu, String relPath) {
        return resolveSettingsFile(prefs, emu, relPath, true, false);
    }

    /**
     * Finalizes recovery for a self-describing temp file.
     *
     * <p>There are only two valid interrupted states for these temps:
     * <ol>
     *   <li><b>Active missing:</b> crash happened after step 1 but before step 2. We roll back
     *       the swap by restoring temp -> active.</li>
     *   <li><b>Active present, destination backup missing:</b> crash happened after step 2 but
     *       before step 3. We complete the swap by moving temp -> its intended backup slot.</li>
     * </ol>
     * If both active and backup already exist, we preserve the temp by quarantining it rather
     * than deleting it. That state should be rare, but preserving unexpected data is safer than
     * assuming it is disposable.</p>
     */
    private static void recoverNamedTemp(File activeFile, File destinationBackup, File tempFile,
                                         String profileLabel) {
        if (!tempFile.exists()) {
            return;
        }

        if (!activeFile.exists()) {
            if (moveWithFallback(tempFile, activeFile)) {
                Log.w(TAG, "Crash recovery: restored " + profileLabel + " temp back to active: "
                        + activeFile.getAbsolutePath());
            } else {
                Log.e(TAG, "Crash recovery: FAILED to restore temp back to active: "
                        + activeFile.getAbsolutePath());
            }
            return;
        }

        if (!destinationBackup.exists()) {
            if (moveWithFallback(tempFile, destinationBackup)) {
                Log.w(TAG, "Crash recovery: completed deferred backup " + destinationBackup.getAbsolutePath());
            } else {
                Log.e(TAG, "Crash recovery: FAILED to complete deferred backup "
                        + destinationBackup.getAbsolutePath());
            }
            return;
        }

        quarantineTempFile(tempFile, "named temp already had both active and backup present");
    }

    /**
     * Handles a legacy generic {@code .swaptmp} file from versions before temp files encoded
     * their destination profile.
     *
     * <p>If the active file is missing, this legacy temp can still be safely restored to active.
     * If the active file already exists, however, we cannot prove whether the legacy temp should
     * become {@code .docked} or {@code .handheld}. The old implementation deleted it in that
     * state, which was a direct data-loss bug. We now quarantine it so the data survives and
     * future swaps do not collide with the stale filename.</p>
     */
    private static void recoverLegacyTemp(File activeFile, File legacyTemp) {
        if (!legacyTemp.exists()) {
            return;
        }

        if (!activeFile.exists()) {
            if (moveWithFallback(legacyTemp, activeFile)) {
                Log.w(TAG, "Crash recovery: restored legacy .swaptmp back to active: "
                        + activeFile.getAbsolutePath());
            } else {
                Log.e(TAG, "Crash recovery: FAILED to restore legacy .swaptmp back to active: "
                        + activeFile.getAbsolutePath());
            }
            return;
        }

        quarantineTempFile(legacyTemp, "legacy temp is ambiguous once active exists");
    }

    /**
     * Moves an unexpected temp file out of the live swap namespace without discarding it.
     *
     * <p>Quarantining is the safest fallback whenever RetroDock cannot prove where a temp file
     * belongs. The data remains on disk for manual inspection, but future swaps can proceed
     * because the reserved {@code .swaptmp*} filename is no longer blocked.</p>
     */
    private static void quarantineTempFile(File tempFile, String reason) {
        File quarantine = new File(tempFile.getAbsolutePath() + QUARANTINE_SUFFIX);
        if (quarantine.exists()) {
            quarantine = new File(tempFile.getAbsolutePath() + QUARANTINE_SUFFIX + "." + System.currentTimeMillis());
        }

        if (moveWithFallback(tempFile, quarantine)) {
            Log.w(TAG, "Crash recovery: quarantined temp file (" + reason + "): " + quarantine.getAbsolutePath());
        } else {
            Log.e(TAG, "Crash recovery: FAILED to quarantine temp file (" + reason + "): "
                    + tempFile.getAbsolutePath());
        }
    }

    // ==================================================================================
    // Exit Watcher
    // ==================================================================================

    /**
     * Starts a polling watcher that waits for an emulator to exit, then performs the
     * profile swap based on the dock state at the moment of exit (not the moment of request).
     * <p>
     * If a watcher already exists for the same emulator, it is cancelled first to avoid
     * duplicate polling. The watcher also self-cancels if the user disables profile switching
     * or the specific emulator while it is running.
     * </p>
     *
     * @param ctx        application context
     * @param emu        the emulator configuration to watch
     * @param statusPath path to the DRM status file for re-reading dock state on exit
     * @param notifyId   notification ID used for updating/dismissing the pending notification
     */
    private static void watchForExit(Context ctx, EmulatorConfig emu,
                                     String statusPath, int notifyId) {
        // Cancel any existing watcher for this emulator to prevent double-polling
        cancelWatcher(emu.id);

        if (watchHandler == null) {
            watchHandler = new Handler(Looper.getMainLooper());
        }

        Runnable checker = new Runnable() {
            @Override
            public void run() {
                // Re-check preferences each tick -- the user may have disabled the feature
                // while the watcher was running
                SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                if (!prefs.getBoolean("profile_switch_enabled", false) ||
                        !prefs.getBoolean("emu_" + emu.id + "_enabled", false)) {
                    Log.i(TAG, "Watcher cancelled (feature disabled): " + emu.displayName);
                    synchronized (activeWatchers) { activeWatchers.remove(emu.id); }
                    return;
                }

                if (isAnyPackageRunning(ctx, emu.packageNames)) {
                    // Still running -- schedule the next check
                    watchHandler.postDelayed(this, PROCESS_CHECK_INTERVAL_MS);
                    return;
                }

                // ---- Emulator has exited ----
                Log.i(TAG, emu.displayName + " has exited, re-checking dock state");

                // Cancel any pending hot-apply for this emulator (Issue #6 fix).
                // The hot-apply delay may not have fired yet, and sending commands
                // to a dead process is harmless but confusing in logs.
                EmulatorHotApply.cancelPending(emu.id);

                // Finish any interrupted swap for this emulator now that we know the process has
                // exited. This keeps recovery inside the same "only touch closed emulators" rule
                // as normal swaps, and it ensures a leftover temp from a previous failure does
                // not confuse the next doesNeedSwap()/swapSettings() decision.
                recoverFromPartialSwap(prefs, emu);

                // IMPORTANT: Re-read the DRM status file NOW, not using the docked boolean
                // from when swapProfiles() was originally called. This handles the scenario:
                //   1. User docks (docked=true passed to swapProfiles)
                //   2. Emulator is running, watcher starts
                //   3. User undocks while emulator still running
                //   4. User closes emulator
                //   5. Watcher fires -- DRM status now says "disconnected"
                //   6. We correctly apply handheld settings, NOT docked
                String currentStatus = ResolutionHelper.readFile(statusPath);
                if (!"connected".equals(currentStatus) && !"disconnected".equals(currentStatus)) {
                    Log.w(TAG, "Watcher could not read DRM status for " + emu.displayName
                            + " (" + currentStatus + "); retrying instead of guessing");
                    synchronized (activeWatchers) { activeWatchers.put(emu.id, this); }
                    watchHandler.postDelayed(this, PROCESS_CHECK_INTERVAL_MS);
                    return;
                }

                synchronized (activeWatchers) { activeWatchers.remove(emu.id); }
                boolean currentlyDocked = "connected".equals(currentStatus);

                // Check if the currently active profile already matches the dock state.
                // This can happen if the user toggled dock state multiple times while the
                // emulator was running and ended up back where they started.
                boolean needsSwap = doesNeedSwap(prefs, emu, currentlyDocked);

                if (needsSwap) {
                    String classified = prefs.getString("emu_" + emu.id + "_classified", "");
                    boolean ok = swapSettings(prefs, emu, currentlyDocked, classified);
                    if (ok) {
                        Log.i(TAG, "Deferred swap completed for " + emu.displayName +
                                " (docked=" + currentlyDocked + ")");
                        // Update notification to confirm swap happened
                        showSwapCompleteNotification(ctx, emu.displayName, currentlyDocked, notifyId);
                    } else {
                        Log.w(TAG, "Deferred swap failed for " + emu.displayName);
                    }
                } else {
                    Log.i(TAG, "No swap needed for " + emu.displayName +
                            " — already on correct profile for docked=" + currentlyDocked);
                    // Dismiss the warning notification since no action was required
                    dismissNotification(ctx, notifyId);
                }
            }
        };

        synchronized (activeWatchers) { activeWatchers.put(emu.id, checker); }
        watchHandler.postDelayed(checker, PROCESS_CHECK_INTERVAL_MS);
        Log.i(TAG, "Started exit watcher for " + emu.displayName);
    }

    /**
     * Cancels and removes an active exit watcher for the given emulator, if one exists.
     * <p>
     * FIX Issue #17: When the last watcher is removed, the shared {@link #watchHandler}
     * is nulled out to release the reference. Without this cleanup, the Handler (and its
     * reference to the main Looper's MessageQueue) would be retained indefinitely even
     * when no watchers are active, preventing garbage collection of any objects it
     * transitively references.
     * </p>
     *
     * @param emuId the emulator identifier whose watcher should be cancelled
     */
    private static void cancelWatcher(String emuId) {
        Runnable existing;
        boolean empty;
        synchronized (activeWatchers) {
            existing = activeWatchers.remove(emuId);
            empty = activeWatchers.isEmpty();
        }
        if (existing != null && watchHandler != null) {
            watchHandler.removeCallbacks(existing);
            Log.i(TAG, "Cancelled existing watcher for: " + emuId);
        }

        // FIX Issue #17: Null out the handler when no watchers remain. The Handler holds
        // a reference to the main Looper and its MessageQueue; keeping it alive indefinitely
        // is a minor leak. It will be lazily re-created in watchForExit() if needed again.
        if (empty) {
            watchHandler = null;
        }
    }

    // ==================================================================================
    // Swap Logic
    // ==================================================================================

    /**
     * Determines whether a swap is needed by checking if a backup file exists for the
     * profile that should currently be active.
     * <p>
     * The logic: if we are docked and a {@code .docked} backup exists, that means the
     * docked settings are sitting in the backup instead of being active -- so a swap is
     * needed. Conversely for handheld.
     * </p>
     *
     * @param prefs  preferences used for override-aware path resolution
     * @param emu    emulator definition whose settings entries are being checked
     * @param docked the current dock state to check against
     * @return {@code true} if at least one settings entry needs to be swapped
     */
    private static boolean doesNeedSwap(SharedPreferences prefs, EmulatorConfig emu, boolean docked) {
        if (emu.settingsFiles == null || emu.settingsFiles.length == 0) return false;

        for (String relPath : emu.settingsFiles) {
            String resolved = findSettingsFile(prefs, emu, relPath);
            if (resolved == null) continue;

            File handheldBackup = new File(resolved + ".handheld");
            File dockedBackup = new File(resolved + ".docked");

            // If docked and the docked backup exists, it means docked settings are NOT
            // currently active -- they need to be swapped in
            if (docked && dockedBackup.exists()) return true;
            // If undocked and the handheld backup exists, handheld settings are NOT
            // currently active -- they need to be swapped in
            if (!docked && handheldBackup.exists()) return true;
        }
        return false;
    }

    /**
     * Preference-aware resolver used by the real swap engine and other logic that must act on
     * exactly the same path the user sees in the UI.
     *
     * <p><b>Why this overload exists:</b> RetroDock supports per-entry path overrides because
     * emulator config layouts differ wildly between Android versions, package variants, and
     * migration states. The old implementation stored those overrides in the UI but ignored them
     * when the rename engine actually ran. This method fixes that by applying the override first
     * and, crucially, refusing to fall back to a different auto-detected path when the override is
     * configured but missing. For integrity-sensitive file moves, "no path" is safer than "the
     * wrong path".</p>
     */
    static String findSettingsFile(SharedPreferences prefs, EmulatorConfig emu, String relPath) {
        return resolveSettingsFile(prefs, emu, relPath, false, false);
    }

    /**
     * Display-oriented resolver used by the settings UI.
     *
     * <p>The UI needs slightly different behavior from the swap engine: when a user has typed an
     * override path that does not exist yet, the row should still display that explicit path
     * instead of silently snapping back to auto-detection. The swap engine, however, must not
     * touch a fallback path in that situation. The two callers therefore intentionally use two
     * different resolution modes.</p>
     */
    static String findSettingsFileForDisplay(SharedPreferences prefs, EmulatorConfig emu, String relPath) {
        return resolveSettingsFile(prefs, emu, relPath, false, true);
    }

    /**
     * Root-only resolver used by code that does not have emulator preferences available
     * (for example some hot-apply helpers).
     *
     * <p><b>Problem in the old design:</b> the first matching root simply won. If both a legacy
     * shared-storage path and a scoped-storage path existed, RetroDock could bounce between them
     * or consistently pick the stale one. We now score every candidate root and choose the best
     * one using two signals:
     * <ul>
     *   <li>Managed state first -- paths with RetroDock sidecars/backups are strongly preferred.</li>
     *   <li>Freshness second -- if several unmanaged candidates exist, the most recently modified
     *       one is a better guess than arbitrary root order.</li>
     * </ul>
     * This is still heuristic, but it is far safer and more stable than "whichever root happened
     * to appear first".</p>
     */
    static String findSettingsFile(String[] rootPaths, String relPath) {
        if (rootPaths == null || rootPaths.length == 0) {
            return null;
        }
        return selectBestResolvedPath(rootPaths, relPath, null, false);
    }

    /**
     * Shared implementation behind the preference-aware resolvers.
     *
     * @param prefs                     preferences used for override lookup and path caching
     * @param emu                       emulator being resolved
     * @param relPath                   relative settings entry path
     * @param includeSwaptmp            whether interrupted-swap temp files should count as state
     * @param displayOverrideWhenMissing if {@code true}, a missing override path is still returned
     *                                  verbatim for UI display; if {@code false}, operations
     *                                  refuse to fall back and return {@code null} instead
     */
    private static String resolveSettingsFile(SharedPreferences prefs, EmulatorConfig emu, String relPath,
                                              boolean includeSwaptmp, boolean displayOverrideWhenMissing) {
        String override = getOverridePath(prefs, emu, relPath);
        if (!override.isEmpty()) {
            if (pathHasManagedState(override, includeSwaptmp)) {
                cacheResolvedPath(prefs, emu, relPath, override);
                return override;
            }
            if (displayOverrideWhenMissing) {
                return override;
            }
            Log.w(TAG, "Override configured for " + emu.displayName + " / " + relPath
                    + " but the file and all RetroDock sidecars are missing. Refusing to fall "
                    + "back to auto-detection for safety.");
            return null;
        }

        String cached = prefs.getString(buildResolvedPathKey(emu.id, relPath), "");
        String resolved = selectBestResolvedPath(emu.defaultPaths, relPath, cached, includeSwaptmp);
        if (resolved != null) {
            cacheResolvedPath(prefs, emu, relPath, resolved);
        }
        return resolved;
    }

    /**
     * Chooses the best candidate path for one settings entry across all configured root folders.
     *
     * <p>Preference order:
     * <ol>
     *   <li>The previously cached path, if it still contains the entry or one of RetroDock's
     *       sidecars. This keeps subsequent operations stable.</li>
     *   <li>The highest-scoring root based on managed-state markers and freshness.</li>
     * </ol>
     * The scoring deliberately prefers paths that already contain RetroDock's backup/temp files,
     * because those paths are known to be the ones RetroDock has been managing.</p>
     */
    private static String selectBestResolvedPath(String[] rootPaths, String relPath, String cachedPath,
                                                 boolean includeSwaptmp) {
        if (rootPaths == null || rootPaths.length == 0) {
            return null;
        }
        if (cachedPath != null && !cachedPath.isEmpty() && pathHasManagedState(cachedPath, includeSwaptmp)) {
            return cachedPath;
        }

        String bestPath = null;
        int bestScore = Integer.MIN_VALUE;
        long bestFreshness = Long.MIN_VALUE;

        for (String root : rootPaths) {
            String candidate = new File(root, relPath).getAbsolutePath();
            int score = scoreResolvedPath(candidate, includeSwaptmp);
            if (score < 0) {
                continue;
            }

            long freshness = getResolvedPathFreshness(candidate, includeSwaptmp);
            if (score > bestScore || (score == bestScore && freshness > bestFreshness)) {
                bestPath = candidate;
                bestScore = score;
                bestFreshness = freshness;
            }
        }

        return bestPath;
    }

    /**
     * Returns {@code true} if the entry path contains any state RetroDock can operate on:
     * the live file/dir, either profile backup, or optionally an interrupted-swap temp file.
     */
    private static boolean pathHasManagedState(String resolvedPath, boolean includeSwaptmp) {
        if (resolvedPath == null || resolvedPath.isEmpty()) {
            return false;
        }

        File active = new File(resolvedPath);
        if (active.exists()) return true;
        if (new File(resolvedPath + ".docked").exists()) return true;
        if (new File(resolvedPath + ".handheld").exists()) return true;
        if (!includeSwaptmp) return false;

        return new File(resolvedPath + SWAPTMP_DOCKED_SUFFIX).exists()
                || new File(resolvedPath + SWAPTMP_HANDHELD_SUFFIX).exists()
                || new File(resolvedPath + LEGACY_SWAPTMP_SUFFIX).exists();
    }

    /**
     * Scores one candidate path for root selection.
     *
     * <p>Higher scores mean "more likely to be the path RetroDock should manage". Managed
     * sidecars/backups dominate the score because they prove prior ownership. Active files still
     * contribute so fresh installs with no sidecars can be resolved.</p>
     */
    private static int scoreResolvedPath(String resolvedPath, boolean includeSwaptmp) {
        if (resolvedPath == null || resolvedPath.isEmpty()) {
            return -1;
        }

        boolean active = new File(resolvedPath).exists();
        boolean docked = new File(resolvedPath + ".docked").exists();
        boolean handheld = new File(resolvedPath + ".handheld").exists();
        boolean tempDocked = includeSwaptmp && new File(resolvedPath + SWAPTMP_DOCKED_SUFFIX).exists();
        boolean tempHandheld = includeSwaptmp && new File(resolvedPath + SWAPTMP_HANDHELD_SUFFIX).exists();
        boolean legacyTemp = includeSwaptmp && new File(resolvedPath + LEGACY_SWAPTMP_SUFFIX).exists();

        if (!(active || docked || handheld || tempDocked || tempHandheld || legacyTemp)) {
            return -1;
        }

        int score = 0;
        if (active) score += 50;
        if (docked || handheld) score += 200;
        if (tempDocked || tempHandheld) score += 180;
        if (legacyTemp) score += 120;
        return score;
    }

    /**
     * Returns the newest timestamp among the live entry and all RetroDock sidecars.
     *
     * <p>This is only a tie-breaker after score comparison. It helps choose the most recently
     * touched config tree when several unmanaged candidates all exist.</p>
     */
    private static long getResolvedPathFreshness(String resolvedPath, boolean includeSwaptmp) {
        long freshness = 0L;
        freshness = Math.max(freshness, new File(resolvedPath).lastModified());
        freshness = Math.max(freshness, new File(resolvedPath + ".docked").lastModified());
        freshness = Math.max(freshness, new File(resolvedPath + ".handheld").lastModified());
        if (includeSwaptmp) {
            freshness = Math.max(freshness, new File(resolvedPath + SWAPTMP_DOCKED_SUFFIX).lastModified());
            freshness = Math.max(freshness, new File(resolvedPath + SWAPTMP_HANDHELD_SUFFIX).lastModified());
            freshness = Math.max(freshness, new File(resolvedPath + LEGACY_SWAPTMP_SUFFIX).lastModified());
        }
        return freshness;
    }

    private static String getOverridePath(SharedPreferences prefs, EmulatorConfig emu, String relPath) {
        return prefs.getString(buildOverrideKey(emu.id, relPath), "");
    }

    private static void cacheResolvedPath(SharedPreferences prefs, EmulatorConfig emu, String relPath, String path) {
        String key = buildResolvedPathKey(emu.id, relPath);
        if (path.equals(prefs.getString(key, ""))) {
            return;
        }
        prefs.edit().putString(key, path).apply();
    }

    private static boolean isEntrySeeded(SharedPreferences prefs, EmulatorConfig emu, String relPath) {
        return prefs.getBoolean(buildSeededKey(emu.id, relPath), false);
    }

    private static void markEntrySeeded(SharedPreferences prefs, EmulatorConfig emu, String relPath) {
        String key = buildSeededKey(emu.id, relPath);
        if (!prefs.getBoolean(key, false)) {
            prefs.edit().putBoolean(key, true).apply();
        }
    }

    private static String buildOverrideKey(String emuId, String relPath) {
        return String.format(OVERRIDE_KEY_PREFIX, emuId, sanitizeRelPath(relPath));
    }

    private static String buildResolvedPathKey(String emuId, String relPath) {
        return String.format(RESOLVED_PATH_KEY_PREFIX, emuId, sanitizeRelPath(relPath));
    }

    private static String buildSeededKey(String emuId, String relPath) {
        return String.format(SEEDED_KEY_PREFIX, emuId, sanitizeRelPath(relPath));
    }

    private static String sanitizeRelPath(String relPath) {
        return relPath.replace("/", "_");
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  FILE STATE MACHINE
    // ──────────────────────────────────────────────────────────────────────────
    //
    //  At any point, each settings entry (e.g. retroarch.cfg) is in one of
    //  these states:
    //
    //  STATE 1 – Fresh install (no swap yet):
    //    retroarch.cfg          ← active (unclassified)
    //
    //  STATE 2 – After first dock (classified as "handheld"):
    //    retroarch.cfg          ← (missing — emulator regenerates defaults for docked)
    //    retroarch.cfg.handheld ← backup of the handheld settings
    //
    //  STATE 3 – Normal docked state (after ≥1 swap cycle):
    //    retroarch.cfg          ← contains docked settings (active)
    //    retroarch.cfg.handheld ← backup of handheld settings
    //
    //  STATE 4 – Normal handheld state:
    //    retroarch.cfg          ← contains handheld settings (active)
    //    retroarch.cfg.docked   ← backup of docked settings
    //
    //  STATE 5 – Crash recovery (partial swap):
    //    retroarch.cfg.swaptmp.handheld / .swaptmp.docked  ← orphaned temp from interrupted swap
    //    (handled by recoverFromPartialSwap on next run)
    //
    //  INVARIANT: At most ONE backup (.docked OR .handheld) should exist at a
    //  time. The backup always contains the INACTIVE profile's settings.
    //
    //  The 3-step swap (State 3 → State 4, or vice versa):
    //    1. active       → .swaptmp.<profile>     (park aside)
    //    2. .{target}    → active       (restore target profile)
    //    3. .swaptmp.<profile> → .{opposite}  (save old as backup)
    //
    //  Steps 1-2 are protected by rollback. Step 3 failure is non-fatal.
    //  All renames use moveWithFallback() for FUSE filesystem compatibility.
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Swaps settings files for a single emulator by iterating over all of its settings
     * entries and resolving each one independently across the provided root paths.
     * <p>
     * Each entry (e.g. {@code retroarch.cfg}, {@code config/}) is looked up via
     * {@link #findSettingsFile} and then passed to {@link #swapSingleEntry} for the
     * atomic rename operation.
     * </p>
     *
     * @param prefs      preferences used for override-aware path resolution and per-entry seeding
     * @param emu        emulator definition whose entries should be swapped
     * @param docked     {@code true} to swap TO docked settings, {@code false} for handheld
     * @param classified the user's original classification hint ("docked" or "handheld"), or
     *                   empty if the emulator has never been classified
     * @return {@code true} if at least one settings entry was successfully swapped
     */
    static boolean swapSettings(SharedPreferences prefs, EmulatorConfig emu, boolean docked, String classified) {
        if (emu.settingsFiles == null || emu.settingsFiles.length == 0) {
            Log.w(TAG, "No settings files defined");
            return false;
        }

        boolean anySwapped = false;

        for (String relPath : emu.settingsFiles) {
            String resolved = findSettingsFile(prefs, emu, relPath);
            if (resolved == null) {
                Log.i(TAG, "Settings file not found in any root, skipping: " + relPath);
                continue;
            }

            File current = new File(resolved);
            File dockedFile = new File(resolved + ".docked");
            File handheldFile = new File(resolved + ".handheld");

            // Once we see either backup variant on disk, that entry has already completed its
            // first-time bootstrap. We persist that knowledge so a later missing-backup anomaly
            // does not cause the old global classification hint to be reused incorrectly.
            if (dockedFile.exists() || handheldFile.exists()) {
                markEntrySeeded(prefs, emu, relPath);
            }

            boolean ok;
            boolean allowClassification = !classified.isEmpty() && !isEntrySeeded(prefs, emu, relPath);
            if (docked) {
                // Transitioning TO docked: restore .docked backup as active, save current as .handheld
                ok = swapSingleEntry(current, dockedFile, handheldFile,
                        allowClassification ? classified : "", "handheld", "docked");
            } else {
                // Transitioning TO handheld: restore .handheld backup as active, save current as .docked
                ok = swapSingleEntry(current, handheldFile, dockedFile,
                        allowClassification ? classified : "", "docked", "handheld");
            }
            if (ok) {
                anySwapped = true;
                markEntrySeeded(prefs, emu, relPath);
            }
        }

        return anySwapped;
    }

    /**
     * Performs the atomic swap for a single settings entry (file or directory).
     * <p>
     * There are three possible scenarios:
     * </p>
     *
     * <h3>Scenario 1: Normal swap (targetBackup exists)</h3>
     * <pre>
     *   Step 1: current      -> current.swaptmp.<profile>    (park active aside into temp)
     *   Step 2: targetBackup -> current             (restore target as the new active)
     *   Step 3: swaptmp.<profile> -> saveBackup     (save old active as the other backup)
     * </pre>
     * <p>If step 2 fails, step 1 is rolled back. Step 3 failure is non-fatal.</p>
     *
     * <h3>Scenario 2: Target backup exists but no active file</h3>
     * <p>Simply renames the backup into the active position. This can happen after a
     * first-time classification where the active file was moved to a backup.</p>
     *
     * <h3>Scenario 3: First-time classification swap (no backup exists yet)</h3>
     * <p>If {@code classified} matches {@code classifyMatch}, the current active file is
     * renamed to the saveBackup position. For example, if the user classified their
     * settings as "handheld" and we are now docking, the current file becomes
     * {@code .handheld}.</p>
     *
     * @param current        the active settings file/directory
     * @param targetBackup   the backup to restore as active (e.g. {@code .docked} when docking)
     * @param saveBackup     where to save the current active as backup (e.g. {@code .handheld}
     *                       when docking)
     * @param classified     the user's one-time classification string, or empty
     * @param classifyMatch  the classification value that triggers a first-time swap
     *                       (e.g. "handheld" when docking)
     * @param classifyLabel  label for logging the first-time swap direction (unused in logic,
     *                       included for symmetry)
     * @return {@code true} if the swap (or first-time rename) succeeded
     */
    private static boolean swapSingleEntry(File current, File targetBackup, File saveBackup,
                                           String classified, String classifyMatch, String classifyLabel) {
        // --- Scenario 1 & 2: A target backup exists and can be restored ---
        if (targetBackup.exists()) {
            if (current.exists()) {
                // Scenario 1: Both active and backup exist -- do the 3-step atomic swap
                // The temp file name now encodes which backup slot it should ultimately become.
                // That lets crash recovery finish step 3 safely without guessing whether the temp
                // represents docked or handheld settings.
                File temp = new File(current.getAbsolutePath()
                        + ("docked".equals(classifyMatch) ? SWAPTMP_DOCKED_SUFFIX : SWAPTMP_HANDHELD_SUFFIX));

                // Step 1: Park current file aside into a temp location.
                // Uses moveWithFallback() which tries renameTo() first, then falls back
                // to copy+delete on FUSE filesystems where rename can fail.
                if (!moveWithFallback(current, temp)) {
                    Log.e(TAG, "Step 1 failed: could not move current to temp: " + current);
                    return false;
                }
                // Step 2: Move target backup into the active position
                if (!moveWithFallback(targetBackup, current)) {
                    Log.e(TAG, "Step 2 failed: could not restore target, rolling back: " + targetBackup);
                    // Rollback step 1 -- put the original back
                    moveWithFallback(temp, current);
                    return false;
                }
                // Step 3: Rename temp (old active) to the opposite backup slot.
                //
                // IMPORTANT: if this step fails we now LEAVE the self-describing temp file in
                // place instead of deleting it. The active file is already correct, and the temp
                // still contains the opposite profile. Keeping it allows recoverFromPartialSwap()
                // to finish the move on the next run instead of turning a transient filesystem
                // problem into permanent profile loss.
                if (!moveWithFallback(temp, saveBackup)) {
                    Log.e(TAG, "Step 3 failed (non-fatal): could not save backup: " + temp
                            + " — leaving self-describing temp in place for crash recovery");
                }
            } else {
                // Scenario 2: No active file exists, just restore the backup directly
                if (!moveWithFallback(targetBackup, current)) {
                    Log.e(TAG, "Failed to restore target: " + targetBackup);
                    return false;
                }
            }
            return true;
        }

        // --- Scenario 3: First-time swap via classification ---
        // No backup exists yet. If the user classified their current settings and the
        // classification matches what we expect to save, rename the active file to the
        // backup position. Example: classified="handheld", classifyMatch="handheld",
        // we are docking -> rename current to .handheld so there is no active file
        // (emulator will regenerate defaults for docked mode).
        if (classifyMatch.equals(classified) && current.exists()) {
            Log.i(TAG, "First swap: renaming " + current.getName() + " to ." + classifyMatch);
            if (!moveWithFallback(current, saveBackup)) {
                Log.e(TAG, "Failed to move for first swap: " + current);
                return false;
            }
            return true;
        }

        // --- No swap possible: no backup exists and no classification applies ---
        Log.i(TAG, "No backup exists yet for: " + current.getAbsolutePath());
        return false;
    }

    // ==================================================================================
    // FUSE-Safe File Move
    // ==================================================================================

    /**
     * Moves a file or directory from {@code src} to {@code dst}, falling back to a
     * recursive copy + delete if {@link File#renameTo} fails.
     *
     * <h3>Why this is needed</h3>
     * <p>On Android 11+ with scoped storage, many paths are backed by FUSE (Filesystem in
     * Userspace). FUSE translates {@code rename(2)} into copy+delete internally, and this
     * translation can fail for various reasons (cross-mount moves, permission issues,
     * partial FUSE support). When {@code renameTo()} fails, we fall back to a manual
     * byte-level copy followed by a recursive delete of the source.</p>
     *
     * <h3>CONCERN: Config file integrity during moves</h3>
     * <p>This method is the foundation of every settings swap in RetroDock. If it fails
     * or behaves incorrectly, the user's emulator config files could be corrupted, lost,
     * or left in an inconsistent state. The previous implementation used bare
     * {@link File#renameTo} with no fallback — on FUSE filesystems, this silently failed
     * and the swap was quietly skipped, leaving the user with the wrong profile active
     * and no indication that anything went wrong.</p>
     *
     * <h3>Safety guarantees (how the fix protects config files)</h3>
     * <ol>
     *   <li><b>Destination is fully written before source is deleted:</b>
     *       {@link #copyRecursive} completes the entire copy (all files, all bytes)
     *       before {@link #deleteRecursive} touches the source. There is no window
     *       where data exists in neither location.</li>
     *   <li><b>Copy failure leaves source intact:</b> If {@code copyRecursive()} throws
     *       an {@link IOException} at any point (disk full, permission denied, I/O error),
     *       the exception bypasses the {@code deleteRecursive(src)} call. The source
     *       file/directory remains completely untouched.</li>
     *   <li><b>Partial destination is cleaned up:</b> On copy failure, the catch block
     *       calls {@code deleteRecursive(dst)} to remove any partially-written files,
     *       preventing corrupt half-copies from confusing future swap attempts or
     *       emulator launches.</li>
     *   <li><b>Timestamps are preserved:</b> {@code copyRecursive()} calls
     *       {@link File#setLastModified} on every copied entry, so emulators that use
     *       modification times for cache invalidation or reload detection see the
     *       same timestamps as the original files.</li>
     * </ol>
     *
     * @param src the source file or directory to move
     * @param dst the destination path (must not already exist)
     * @return {@code true} if the move succeeded via either rename or copy+delete
     */
    private static boolean moveWithFallback(File src, File dst) {
        // Fast path: atomic rename (works on most local filesystems)
        if (src.renameTo(dst)) {
            return true;
        }

        // Slow path: copy + delete (needed on FUSE-mounted storage)
        Log.w(TAG, "renameTo failed for " + src.getName() + ", falling back to copy+delete");
        try {
            copyRecursive(src, dst);
            deleteRecursive(src);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Copy fallback failed: " + src + " -> " + dst + ": " + e.getMessage());
            // Clean up partial destination to avoid leaving corrupt state
            deleteRecursive(dst);
            return false;
        }
    }

    /**
     * Recursively copies a file or directory tree from {@code src} to {@code dst},
     * preserving last-modified timestamps on every entry.
     *
     * <p>For files, copies byte-by-byte with an 8KB buffer using try-with-resources
     * to guarantee both streams are closed even on I/O errors. For directories,
     * creates the destination directory and recursively copies each child entry.</p>
     *
     * <h3>FIX: Timestamp preservation</h3>
     * <p><b>Concern:</b> When {@link #moveWithFallback} falls back to copy+delete
     * (because {@code renameTo()} failed on a FUSE filesystem), the copied files
     * receive the current wall-clock time as their last-modified timestamp. This is
     * a problem because several emulators use file modification timestamps for cache
     * invalidation, config reload detection, or freshness checks. For example:</p>
     * <ul>
     *   <li>RetroArch's config directory may use timestamps to detect changed overrides</li>
     *   <li>DuckStation checks INI modification times before deciding to re-parse</li>
     *   <li>ScummVM can re-read its config when it detects a timestamp change</li>
     * </ul>
     * <p>If a profile swap silently bumps every file's timestamp to "now", the
     * emulator may unnecessarily reload configs on next launch, or worse, it may
     * treat unchanged settings as new and overwrite them on exit — destroying the
     * backup profile that was just swapped in.</p>
     *
     * <p><b>Fix:</b> After copying each entry (file or directory), we call
     * {@link File#setLastModified} to restore the original source timestamp. This
     * makes the copy+delete fallback behave identically to an atomic rename from
     * the emulator's perspective — the file contents AND metadata are preserved.</p>
     *
     * <h3>Failure safety</h3>
     * <p>If this method throws {@link IOException} partway through a directory copy,
     * the caller ({@link #moveWithFallback}) catches the exception, deletes the
     * partial destination via {@link #deleteRecursive}, and returns {@code false}.
     * The source is never deleted on copy failure, so no data is lost.</p>
     *
     * @throws IOException if any file cannot be read or written
     */
    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.mkdirs() && !dst.isDirectory()) {
                throw new IOException("Failed to create directory: " + dst);
            }
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursive(child, new File(dst, child.getName()));
                }
            }
        } else {
            // Copy file contents with buffered streams.
            // try-with-resources guarantees both streams close even if write() throws,
            // preventing file descriptor leaks during the FUSE fallback path.
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
        }

        // FIX: Restore the original last-modified timestamp on the destination.
        //
        // Without this, every file touched by a FUSE-fallback move would get a fresh
        // timestamp of "now", even though its contents are identical to the source.
        // Emulators that check modification times (RetroArch, DuckStation, ScummVM)
        // could misinterpret the timestamp jump as "config was edited externally" and
        // trigger unwanted behavior (config reloads, cache invalidation, or overwrite
        // on exit). Preserving the original timestamp makes the copy+delete path
        // indistinguishable from an atomic rename from the emulator's perspective.
        //
        // The guard (lastModified > 0) skips the call if the source has no timestamp
        // metadata, which can happen on some virtual filesystems.
        long lastModified = src.lastModified();
        if (lastModified > 0) {
            dst.setLastModified(lastModified);
        }
    }

    /**
     * Recursively deletes a file or directory tree. Used as cleanup after a
     * successful copy-based move, or to clean up a partial copy on failure.
     *
     * <p>Silently ignores files that don't exist or can't be deleted.</p>
     */
    private static void deleteRecursive(File target) {
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        target.delete();
    }

    // ==================================================================================
    // Process Detection
    // ==================================================================================

    /**
     * Checks whether any of the given package names correspond to a currently running
     * process on the device.
     * <p>
     * Used to determine if an emulator is active before attempting a file swap (swapping
     * files while the emulator is running risks the emulator overwriting the new settings
     * on exit).
     * </p>
     * <p>
     * FIX Issue #7: Uses a dual-strategy approach because
     * {@link ActivityManager#getRunningAppProcesses()} has been deprecated and increasingly
     * restricted since Android L. On Android 5.1+ it only returns the caller's own processes
     * and a few system ones, making it unreliable for detecting third-party emulator apps.
     * </p>
     * <p>
     * <b>Primary strategy:</b> {@link UsageStatsManager#queryUsageStats} checks whether
     * any target package had foreground activity in the last 5 seconds. This is accurate on
     * Android 5+ but requires the {@code PACKAGE_USAGE_STATS} permission, which the user
     * must grant via Settings > Apps > Special access > Usage access. If not granted, the
     * query returns an empty list and we fall through to the fallback.
     * </p>
     * <p>
     * <b>Fallback strategy:</b> The original {@link ActivityManager#getRunningAppProcesses()}
     * approach. While deprecated and limited, it still works for some device/OS combinations
     * (especially manufacturer-customized ROMs) and provides a safety net when UsageStats
     * permission has not been granted. Together, the two strategies maximize detection
     * coverage across the fragmented Android ecosystem.
     * </p>
     *
     * @param ctx          application context for accessing system services
     * @param packageNames array of package names to check (an emulator may have multiple,
     *                     e.g. free and paid variants)
     * @return {@code true} if at least one of the package names is found to be recently
     *         active or currently running
     */
    public static boolean isAnyPackageRunning(Context ctx, String[] packageNames) {
        if (packageNames == null || packageNames.length == 0) {
            return false;
        }

        // UsageStats is now treated as a positive signal only. The previous implementation
        // trusted a negative UsageStats result and skipped all further checks, but "not used
        // in the last 5 seconds" is not the same as "process is not running". That could let
        // RetroDock rename config files under a still-live emulator. For integrity-sensitive
        // swaps, absence of evidence must not be treated as evidence of safety.
        if (wasAnyPackageRecentlyUsed(ctx, packageNames)) {
            return true;
        }

        // Shell-based detection is surprisingly valuable on Android: toybox/busybox process
        // tools often see more than ActivityManager on modern builds, and unlike UsageStats
        // they check for actual live processes rather than recent foreground activity.
        if (isAnyPackageRunningViaShell(packageNames)) {
            return true;
        }

        // Final fallback for devices/ROMs where shell process tools are unavailable.
        return isAnyPackageRunningViaActivityManager(ctx, packageNames);
    }

    /**
     * Returns {@code true} when the emulator definition contains at least one package name that
     * can be used for safe process detection.
     */
    static boolean hasProcessDetectionIdentity(EmulatorConfig emu) {
        return emu.packageNames != null && emu.packageNames.length > 0;
    }

    /**
     * Positive-evidence UsageStats check.
     *
     * <p>We still use UsageStats because it is reliable when it says "yes", but we no longer use
     * it to prove "no". A backgrounded emulator can still be alive even if it was not the most
     * recently used foreground app.</p>
     */
    private static boolean wasAnyPackageRecentlyUsed(Context ctx, String[] packageNames) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 5000, now);
        if (stats == null || stats.isEmpty()) {
            return false;
        }

        for (UsageStats stat : stats) {
            for (String pkg : packageNames) {
                if (pkg.equals(stat.getPackageName()) && stat.getLastTimeUsed() >= now - 5000) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Process detection via Android shell tools.
     *
     * <p>We try {@code pidof} first because it is cheap and precise. If that is unavailable, we
     * fall back to parsing {@code ps -A}. Both are best-effort, but either one is a better safety
     * signal than trusting a negative UsageStats result.</p>
     */
    private static boolean isAnyPackageRunningViaShell(String[] packageNames) {
        for (String pkg : packageNames) {
            if (isPackageRunningViaPidof(pkg)) {
                return true;
            }
        }
        return isAnyPackageRunningViaPs(packageNames);
    }

    private static boolean isPackageRunningViaPidof(String pkg) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"pidof", pkg});
            String stdout = drainStream(process.getInputStream()).trim();
            drainStream(process.getErrorStream());
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                process.destroy();
                return false;
            }
            return process.exitValue() == 0 && !stdout.isEmpty();
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static boolean isAnyPackageRunningViaPs(String[] packageNames) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "ps -A"});
            String stdout = drainStream(process.getInputStream());
            drainStream(process.getErrorStream());
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                process.destroy();
                return false;
            }

            String[] lines = stdout.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("USER")) {
                    continue;
                }

                String[] columns = trimmed.split("\\s+");
                String processName = columns[columns.length - 1];
                for (String pkg : packageNames) {
                    if (processName.equals(pkg) || processName.startsWith(pkg + ":")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return false;
    }

    private static boolean isAnyPackageRunningViaActivityManager(Context ctx, String[] packageNames) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;

        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) return false;

        for (ActivityManager.RunningAppProcessInfo proc : processes) {
            for (String pkg : packageNames) {
                if (proc.processName.equals(pkg) || proc.processName.startsWith(pkg + ":")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String drainStream(InputStream stream) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    // ==================================================================================
    // Config Path Discovery
    // ==================================================================================

    /**
     * Scans the emulator's known root paths to find the best configuration directory.
     * <p>
     * Uses a two-pass strategy:
     * <ol>
     *   <li><b>Prefer paths with actual settings files.</b> If a root path contains one of
     *       the emulator's declared settings entries, it is the most likely correct location.</li>
     *   <li><b>Fall back to any existing root path.</b> The directory exists even if no
     *       settings files are present yet (e.g., freshly installed emulator).</li>
     * </ol>
     * </p>
     *
     * @param emu the emulator configuration containing {@code defaultPaths} and
     *            {@code settingsFiles}
     * @return the best-matching root path, or an empty string if none of the paths exist
     */
    public static String scanForConfigPath(EmulatorConfig emu) {
        // First pass: prefer paths where actual settings files exist
        if (emu.settingsFiles != null && emu.settingsFiles.length > 0) {
            for (String path : emu.defaultPaths) {
                File root = new File(path);
                if (!root.exists()) continue;
                for (String settingsFile : emu.settingsFiles) {
                    if (new File(root, settingsFile).exists()) {
                        return path;
                    }
                }
            }
        }

        // Second pass: fall back to any existing root path
        for (String path : emu.defaultPaths) {
            if (new File(path).exists()) {
                return path;
            }
        }
        return "";
    }

    // ==================================================================================
    // Notifications
    // ==================================================================================

    /**
     * Shows a persistent notification warning the user that an emulator is running and
     * its settings will be swapped once it exits.
     *
     * @param ctx      application context
     * @param emuName  the emulator's display name for the notification text
     * @param docked   whether the pending swap is to docked or handheld mode
     * @param notifyId unique notification ID (allows per-emulator notifications)
     */
    private static void showRestartNotification(Context ctx, String emuName, boolean docked, int notifyId) {
        ensureNotificationChannel(ctx);

        String message;
        if (docked) {
            message = emuName + " is running. Settings will be swapped to docked when it exits.";
        } else {
            message = emuName + " is running. Settings will be swapped to handheld when it exits.";
        }

        Notification notification = buildNotification(ctx, "RetroDock — " + emuName, message,
                android.R.drawable.ic_dialog_alert);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(notifyId, notification);
        }
    }

    /**
     * Replaces the pending-swap notification with a confirmation that the swap completed
     * successfully after the emulator exited.
     *
     * @param ctx      application context
     * @param emuName  the emulator's display name
     * @param docked   the dock state that was applied
     * @param notifyId the same notification ID used by the pending notification (overwrites it)
     */
    private static void showSwapCompleteNotification(Context ctx, String emuName, boolean docked, int notifyId) {
        ensureNotificationChannel(ctx);

        String profile = docked ? "docked" : "handheld";
        String message = emuName + " settings swapped to " + profile + " profile.";

        Notification notification = buildNotification(ctx, "RetroDock — " + emuName, message,
                android.R.drawable.ic_dialog_info);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(notifyId, notification);
        }
    }

    /**
     * Dismisses a notification by its ID. Used when the exit watcher determines no swap
     * was needed (the active profile already matches the current dock state).
     *
     * @param ctx      application context
     * @param notifyId the notification ID to dismiss
     */
    private static void dismissNotification(Context ctx, int notifyId) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(notifyId);
        }
    }

    /**
     * Constructs a {@link Notification} with the given title, message, and icon.
     * Supports both pre-O and O+ notification APIs.
     *
     * @param ctx     application context
     * @param title   notification title text
     * @param message notification body text (also used for expanded big-text style)
     * @param icon    resource ID for the small notification icon
     * @return the built {@link Notification} object
     */
    private static Notification buildNotification(Context ctx, String title, String message, int icon) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(ctx);
        }

        return builder
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(icon)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build();
    }

    /**
     * Creates the notification channel for profile switch alerts on Android O+.
     * Safe to call multiple times -- the system ignores duplicate channel creation.
     *
     * @param ctx application context
     */
    private static void ensureNotificationChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Profile Switch Alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Alerts when emulator settings need a restart to take effect");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}
