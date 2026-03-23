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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *           +-- for each enabled emulator:
 *           |       |
 *           |       +-- emulator IS running?
 *           |       |       yes -> showRestartNotification()
 *           |       |              watchForExit()   (polls until process exits)
 *           |       |              hotApply()        (live shader/filter changes)
 *           |       |
 *           |       +-- emulator NOT running?
 *           |               recoverFromPartialSwap()  (only after proving emulator is idle)
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
 * of each idle-emulator swap path <b>after</b> RetroDock has proved the emulator is no longer
 * running. This ordering matters: moving files for crash recovery is itself a real file move, so
 * recovery must obey the same "never touch configs while the emulator is live" rule as normal
 * swaps.
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
 *
 * <h2>Untrusted Hot-Swap Sessions</h2>
 * <p>
 * Live hot-apply features (RetroArch UDP shaders, DuckStation/ScummVM/PPSSPP config edits)
 * intentionally change emulator behavior while the emulator is still running. That is useful
 * for instant dock/handheld feedback, but it creates a profile-purity problem: the file mounted
 * on disk is still whichever profile was active when the session began. If RetroDock simply
 * saved that live file on exit, temporary docked tweaks made during a handheld-launched session
 * would be mislabeled as the new handheld profile, and vice versa.
 * </p>
 * <p>
 * To prevent that cross-contamination, RetroDock now treats the first successful hot-apply in a
 * running session as the moment the mounted config becomes <b>untrusted</b>. Right before that
 * first live change, RetroDock snapshots the currently mounted profile into a private sidecar
 * copy. On emulator exit, if any hot-apply happened during the session, RetroDock restores that
 * trusted sidecar back to the live path <b>before</b> deciding whether a normal dock/handheld
 * file swap is still required.
 * </p>
 * <p>
 * This gives RetroDock a simple, defensible rule:
 * </p>
 * <ul>
 *   <li><b>No hot-apply used:</b> preserve current exit-time behavior and save the live config.</li>
 *   <li><b>Any hot-apply used:</b> discard the session's untrusted mounted-file edits and return
 *       to the last trusted mounted profile before applying the final device mode.</li>
 * </ul>
 * <p>
 * Important limitation: because RetroDock does not have a guaranteed emulator-launch hook, the
 * strongest trust point it can prove is "the mounted profile immediately before the first
 * hot-apply of this session", not necessarily "the exact bytes from process launch". That is
 * still enough to prevent hot-swapped dock/handheld contamination, which is the integrity risk
 * this feature is designed to eliminate.
 * </p>
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
    private static final String FILE_COPY_STAGING_SUFFIX = ".retrodock-copytmp";
    private static final String HOT_SESSION_TRUSTED_DOCKED_SUFFIX = ".retrodock-sessiontrusted.docked";
    private static final String HOT_SESSION_TRUSTED_HANDHELD_SUFFIX = ".retrodock-sessiontrusted.handheld";
    private static final String HOT_SESSION_ABSENT_DOCKED_SUFFIX = ".retrodock-sessionabsent.docked";
    private static final String HOT_SESSION_ABSENT_HANDHELD_SUFFIX = ".retrodock-sessionabsent.handheld";
    private static final String HOT_SESSION_DIRTY_SUFFIX = ".retrodock-sessiondirty";
    private static final String MODE_DOCKED = "docked";
    private static final String MODE_HANDHELD = "handheld";

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
    private static final String HOT_SESSION_PREPARED_KEY_PREFIX = "emu_%s_hot_session_prepared";
    private static final String HOT_SESSION_APPLIED_KEY_PREFIX = "emu_%s_hot_session_applied";
    private static final String HOT_SESSION_MODE_KEY_PREFIX = "emu_%s_hot_session_mode";

    /**
     * Result type for a single settings-entry swap attempt.
     *
     * <p>The previous boolean return value conflated three very different outcomes:
     * <ul>
     *   <li><b>SWAPPED</b> -- bytes really moved on disk and the entry changed state</li>
     *   <li><b>NO_OP</b> -- nothing needed to happen for this entry right now</li>
     *   <li><b>FAILED</b> -- a filesystem operation failed mid-flow</li>
     * </ul>
     * For multi-entry emulators, treating "no-op" and "failure" the same made it impossible
     * to perform a proper all-or-nothing rollback. The transactional swap code below needs
     * to know exactly which entries actually changed so it can undo only those entries if a
     * later one fails.</p>
     */
    private enum SwapResult {
        SWAPPED,
        NO_OP,
        FAILED
    }

    /**
     * Result type for exit-time untrusted-session handling.
     *
     * <p>This is separate from {@link SwapResult} because "restore trusted snapshot" is not
     * the same operation as "swap docked/handheld profiles", yet callers still need to know
     * whether the hot-session path consumed the event or whether the normal swap logic should
     * continue afterward.</p>
     */
    private enum HotSwapSessionFinalizationResult {
        NO_SESSION,
        RESTORED_ONLY,
        RESTORED_AND_SWAPPED,
        FAILED
    }

    /**
     * In-memory view of one emulator's hot-session tracking state as persisted in
     * SharedPreferences.
     *
     * <p>{@code prepared} means RetroDock already created private trusted sidecars for the
     * current mounted profile. {@code applied} means at least one live hot-apply action really
     * happened and the mounted file must therefore be treated as untrusted on exit. Keeping both
     * flags lets RetroDock clean up aborted hot-apply attempts without discarding legitimate
     * sessions that already used live mode switching successfully.</p>
     */
    private static final class HotSwapSessionState {
        final boolean prepared;
        final boolean applied;
        final String mountedMode;

        HotSwapSessionState(boolean prepared, boolean applied, String mountedMode) {
            this.prepared = prepared;
            this.applied = applied;
            this.mountedMode = mountedMode;
        }
    }

    /**
     * Immutable plan for one settings entry inside a multi-entry emulator swap transaction.
     *
     * <p>We resolve all file paths up front and keep them stable for the duration of the
     * transaction. Re-resolving mid-swap would be dangerous because auto-detection could
     * pick a different root if files appear/disappear while the transaction is in flight.</p>
     */
    private static final class SettingsEntryPlan {
        final String relPath;
        final File current;
        final File dockedBackup;
        final File handheldBackup;
        final boolean allowClassification;
        final boolean hadBackupBeforeSwap;

        SettingsEntryPlan(String relPath, File current, File dockedBackup, File handheldBackup,
                          boolean allowClassification, boolean hadBackupBeforeSwap) {
            this.relPath = relPath;
            this.current = current;
            this.dockedBackup = dockedBackup;
            this.handheldBackup = handheldBackup;
            this.allowClassification = allowClassification;
            this.hadBackupBeforeSwap = hadBackupBeforeSwap;
        }
    }

    /**
     * Snapshot plan for one settings entry before the first hot-apply of a session.
     *
     * <p>Each entry needs one of two trust records:
     * <ul>
     *   <li>A real sidecar copy of the mounted file/directory if it existed on disk.</li>
     *   <li>An "absent marker" if the trusted state for that entry was intentionally missing
     *       (for example after a first-time bootstrap move where the emulator later regenerated
     *       defaults only in memory).</li>
     * </ul>
     * Recording absence explicitly is important because "restore trusted state" sometimes means
     * "delete the generated current entry and leave the path absent", not just "copy bytes back".
     * </p>
     */
    private static final class HotSwapSnapshotPlan {
        final String relPath;
        final File current;
        final File trustedSnapshot;
        final File absentMarker;
        final boolean activeExists;

        HotSwapSnapshotPlan(String relPath, File current, File trustedSnapshot,
                            File absentMarker, boolean activeExists) {
            this.relPath = relPath;
            this.current = current;
            this.trustedSnapshot = trustedSnapshot;
            this.absentMarker = absentMarker;
            this.activeExists = activeExists;
        }
    }

    /**
     * Restore transaction plan for one settings entry in an untrusted hot-session cleanup.
     *
     * <p>During restore we temporarily park the contaminated live file in {@code dirtyParking}
     * and copy the trusted snapshot back into place. We keep the dirty copy until the entire
     * emulator restore transaction succeeds so a later-entry failure can still roll the earlier
     * entries back to their pre-restore state.</p>
     */
    private static final class HotSwapRestorePlan {
        final HotSwapSnapshotPlan snapshotPlan;
        final File dirtyParking;
        boolean parkedDirty;
        boolean restoredTrustedCopy;

        HotSwapRestorePlan(HotSwapSnapshotPlan snapshotPlan, File dirtyParking) {
            this.snapshotPlan = snapshotPlan;
            this.dirtyParking = dirtyParking;
        }
    }

    // ==================================================================================
    // Per-Emulator Swap Serialization (Audit Fix #1/#2/#9)
    // ==================================================================================

    /**
     * Per-emulator locks that serialize swap operations for the same emulator.
     *
     * <h3>CONCERN: Concurrent swap calls can corrupt config files</h3>
     * <p>Without serialization, rapid dock/undock events (or a dock event firing while an
     * exit-watcher swap is in progress) can cause two threads to enter
     * {@link #swapSingleEntry} for the same emulator simultaneously. The dangerous sequence:</p>
     * <ol>
     *   <li>Thread A: step 1 moves {@code retroarch.cfg} to {@code .swaptmp.docked}</li>
     *   <li>Thread B: step 1 tries to move {@code retroarch.cfg} — but it's GONE
     *       (thread A already moved it). Thread B returns false, logs an error.</li>
     *   <li>Meanwhile, thread A continues to step 2 and 3. The swap "succeeds" on thread A,
     *       but thread B's failure is silent and may leave the user confused.</li>
     * </ol>
     * <p>In the <b>worst case</b>, both threads succeed at step 1 for different settings
     * entries, creating a mixed state where some entries are in docked mode and others in
     * handheld mode. This violates the all-or-nothing swap invariant.</p>
     *
     * <h3>How this fix protects config files</h3>
     * <p>Each emulator gets its own lock object (keyed by emulator ID). Before any swap
     * or recovery operation, the calling thread acquires the lock for that emulator.
     * This ensures that:</p>
     * <ul>
     *   <li>Only one thread can swap a given emulator's files at a time</li>
     *   <li>Recovery ({@link #recoverFromPartialSwap}) cannot race with an active swap</li>
     *   <li>The exit watcher's deferred swap cannot overlap with a new dock event's swap</li>
     *   <li>Different emulators can still swap concurrently (no global bottleneck)</li>
     * </ul>
     *
     * <p>We use {@link ConcurrentHashMap} for the lock registry itself so that
     * {@link #getEmulatorLock} can be called from any thread without external sync.</p>
     */
    private static final ConcurrentHashMap<String, Object> emulatorSwapLocks = new ConcurrentHashMap<>();

    /**
     * Returns (or creates) the lock object for a specific emulator.
     * Thread-safe via {@link ConcurrentHashMap#computeIfAbsent}.
     */
    private static Object getEmulatorLock(String emulatorId) {
        return emulatorSwapLocks.computeIfAbsent(emulatorId, k -> new Object());
    }

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
            // Acquire the per-emulator lock to prevent concurrent swaps for the same emulator.
            // This protects against rapid dock/undock events or an exit-watcher swap overlapping
            // with a new dock event. See the emulatorSwapLocks documentation for the full
            // rationale and failure scenarios this prevents.
            synchronized (getEmulatorLock(emu.id)) {
                // Recover this emulator's interrupted temp files only after we have proved it is
                // not running. Recovering while the emulator is still alive would itself be a file
                // move against a live config tree, which defeats the entire "wait until exit"
                // safety rule. Recovery is inside the lock to prevent races with concurrent swaps.
                recoverFromPartialSwap(prefs, emu);

                // If a previous running session used any live hot-apply feature, the mounted
                // profile on disk is no longer trustworthy as the "latest saved version" for that
                // mode. Before we do any normal dock/handheld swap logic, restore the trusted
                // snapshot we captured immediately before the first hot-apply of that session.
                //
                // This is the key fix for cross-profile contamination:
                //   1. Launch handheld profile
                //   2. Dock while emulator is running
                //   3. Hot-apply TV-side settings for convenience
                //   4. User tweaks more settings before exit
                //   5. Emulator exits
                //
                // Without this restore step, those step-4 edits would still be sitting in the
                // handheld-mounted live file, and the next deferred swap would mislabel them as
                // the new handheld truth. Finalizing the hot session first discards the
                // untrusted mounted file and returns to the last trusted pre-hot-apply state.
                HotSwapSessionFinalizationResult hotSessionResult =
                        finalizeHotSwapSessionIfNeeded(ctx, prefs, emu, docked);
                if (hotSessionResult == HotSwapSessionFinalizationResult.FAILED) {
                    Log.w(TAG, "Skipping normal swap for " + emu.displayName
                            + " because trusted-session finalization failed");
                    continue;
                }
                if (hotSessionResult == HotSwapSessionFinalizationResult.RESTORED_ONLY) {
                    Log.i(TAG, "Restored trusted mounted profile for " + emu.displayName
                            + "; no additional dock/handheld swap was needed");
                    continue;
                }
                if (hotSessionResult == HotSwapSessionFinalizationResult.RESTORED_AND_SWAPPED) {
                    Log.i(TAG, "Restored trusted mounted profile and completed deferred mode swap for "
                            + emu.displayName);
                    continue;
                }

                // Audit Fix #7: Read the classification INSIDE the lock so it cannot change
                // between the read and the swap. The classification dialog in
                // EmulatorSettingsActivity writes to SharedPreferences from the UI thread;
                // by reading it here under the lock, we ensure the swap uses a consistent
                // value even if the user changes the classification at the exact same moment.
                String classified = prefs.getString("emu_" + emu.id + "_classified", "");
                boolean ok = swapSettings(prefs, emu, docked, classified);
                if (ok) {
                    Log.i(TAG, "Swapped settings for " + emu.displayName + " (docked=" + docked + ")");
                } else {
                    Log.w(TAG, "Failed to swap settings for " + emu.displayName);
                }
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
        File quarantine = buildQuarantineTarget(tempFile);

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

                // Acquire the per-emulator swap lock before touching any files.
                // This prevents the exit watcher's deferred swap from overlapping with
                // a concurrent dock event that also calls swapProfiles() for the same
                // emulator. Without this lock, two threads could move the same config
                // file simultaneously, causing silent data loss.
                synchronized (getEmulatorLock(emu.id)) {
                    // Recovery must be inside the lock (same reason as in swapProfiles)
                    recoverFromPartialSwap(prefs, emu);

                    // A running session that used hot-apply is finalized before any ordinary
                    // exit-time swap decision. That ensures the mounted profile on disk is put
                    // back to the last trusted pre-hot-apply state before we ask "do we still
                    // need to swap to the final docked/handheld mode?".
                    HotSwapSessionFinalizationResult hotSessionResult =
                            finalizeHotSwapSessionIfNeeded(ctx, prefs, emu, currentlyDocked);
                    if (hotSessionResult == HotSwapSessionFinalizationResult.FAILED) {
                        Log.w(TAG, "Deferred finalization failed for " + emu.displayName);
                        return;
                    }
                    if (hotSessionResult == HotSwapSessionFinalizationResult.RESTORED_AND_SWAPPED) {
                        Log.i(TAG, "Deferred exit handling restored trusted state and swapped "
                                + emu.displayName + " to docked=" + currentlyDocked);
                        showSwapCompleteNotification(ctx, emu.displayName, currentlyDocked, notifyId);
                        return;
                    }
                    if (hotSessionResult == HotSwapSessionFinalizationResult.RESTORED_ONLY) {
                        Log.i(TAG, "Deferred exit handling restored trusted state for "
                                + emu.displayName + " with no additional mode swap required");
                        dismissNotification(ctx, notifyId);
                        return;
                    }

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
                } // end synchronized (getEmulatorLock)
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

    private static String buildHotSessionPreparedKey(String emuId) {
        return String.format(HOT_SESSION_PREPARED_KEY_PREFIX, emuId);
    }

    private static String buildHotSessionAppliedKey(String emuId) {
        return String.format(HOT_SESSION_APPLIED_KEY_PREFIX, emuId);
    }

    private static String buildHotSessionModeKey(String emuId) {
        return String.format(HOT_SESSION_MODE_KEY_PREFIX, emuId);
    }

    private static String sanitizeRelPath(String relPath) {
        return relPath.replace("/", "_");
    }

    private static HotSwapSessionState readHotSwapSessionState(SharedPreferences prefs, String emuId) {
        return new HotSwapSessionState(
                prefs.getBoolean(buildHotSessionPreparedKey(emuId), false),
                prefs.getBoolean(buildHotSessionAppliedKey(emuId), false),
                prefs.getString(buildHotSessionModeKey(emuId), "")
        );
    }

    private static void writeHotSwapSessionState(SharedPreferences prefs, String emuId,
                                                 boolean prepared, boolean applied, String mountedMode) {
        prefs.edit()
                .putBoolean(buildHotSessionPreparedKey(emuId), prepared)
                .putBoolean(buildHotSessionAppliedKey(emuId), applied)
                .putString(buildHotSessionModeKey(emuId), mountedMode)
                .apply();
    }

    private static void clearHotSwapSessionState(SharedPreferences prefs, String emuId) {
        prefs.edit()
                .remove(buildHotSessionPreparedKey(emuId))
                .remove(buildHotSessionAppliedKey(emuId))
                .remove(buildHotSessionModeKey(emuId))
                .apply();
    }

    private static EmulatorConfig findEmulatorById(Context ctx, String emuId) {
        for (EmulatorConfig emu : EmulatorConfig.getInstalled(ctx)) {
            if (emu.id.equals(emuId)) {
                return emu;
            }
        }
        for (EmulatorConfig emu : EmulatorConfig.getKnownDatabase()) {
            if (emu.id.equals(emuId)) {
                return emu;
            }
        }
        return null;
    }

    private static File buildHotSessionTrustedSnapshot(File basePath, String mountedMode) {
        return new File(basePath.getAbsolutePath()
                + (MODE_DOCKED.equals(mountedMode)
                ? HOT_SESSION_TRUSTED_DOCKED_SUFFIX
                : HOT_SESSION_TRUSTED_HANDHELD_SUFFIX));
    }

    private static File buildHotSessionAbsentMarker(File basePath, String mountedMode) {
        return new File(basePath.getAbsolutePath()
                + (MODE_DOCKED.equals(mountedMode)
                ? HOT_SESSION_ABSENT_DOCKED_SUFFIX
                : HOT_SESSION_ABSENT_HANDHELD_SUFFIX));
    }

    private static File buildHotSessionDirtyParking(File basePath) {
        return new File(basePath.getAbsolutePath() + HOT_SESSION_DIRTY_SUFFIX + "." + System.nanoTime());
    }

    private static String resolveManagedPathForArtifacts(SharedPreferences prefs, EmulatorConfig emu, String relPath) {
        String override = getOverridePath(prefs, emu, relPath);
        if (!override.isEmpty()) {
            return override;
        }
        String cached = prefs.getString(buildResolvedPathKey(emu.id, relPath), "");
        if (!cached.isEmpty()) {
            return cached;
        }
        return null;
    }

    /**
     * Infers which profile is physically mounted on disk right now for a running emulator.
     *
     * <p>This is the critical reference point for hot-session protection. When a user docks or
     * undocks while the emulator is still running, RetroDock does <b>not</b> swap the config
     * files immediately. The "mounted mode" therefore remains whichever profile was already live
     * on disk before the hot-apply happened. That mounted profile is exactly what can become
     * contaminated by temporary live shader/filter edits, so it is the profile we must snapshot
     * and later restore if the session becomes untrusted.</p>
     *
     * <p>The inference rules mirror the UI status logic:
     * <ul>
     *   <li>{@code .docked} exists => the live file is handheld</li>
     *   <li>{@code .handheld} exists => the live file is docked</li>
     *   <li>No backups yet => fall back to the user's initial classification if available</li>
     * </ul>
     * If entries disagree or both backup sidecars exist simultaneously, the state is treated as
     * unknown and hot-apply is refused. Failing closed is safer than snapshotting the wrong mode.
     * </p>
     */
    private static String inferMountedMode(SharedPreferences prefs, EmulatorConfig emu) {
        String inferred = "";

        if (emu.settingsFiles == null || emu.settingsFiles.length == 0) {
            return "";
        }

        for (String relPath : emu.settingsFiles) {
            String resolved = findSettingsFile(prefs, emu, relPath);
            if (resolved == null) {
                if (isMissingManagedEntry(prefs, emu, relPath)) {
                    Log.w(TAG, "Cannot infer mounted mode for " + emu.displayName
                            + " because a previously managed entry disappeared: " + relPath);
                    return "";
                }
                continue;
            }

            File current = new File(resolved);
            boolean hasDockedBackup = new File(resolved + ".docked").exists();
            boolean hasHandheldBackup = new File(resolved + ".handheld").exists();
            String entryMode = "";

            if (hasDockedBackup && hasHandheldBackup) {
                Log.w(TAG, "Cannot infer mounted mode for " + emu.displayName
                        + " because both backup slots exist for " + relPath);
                return "";
            }
            if (hasDockedBackup) {
                entryMode = MODE_HANDHELD;
            } else if (hasHandheldBackup) {
                entryMode = MODE_DOCKED;
            } else if (current.exists()) {
                // No backups yet means this entry has never completed a real mode swap. The only
                // trustworthy hint is the user's original classification from the first-enable
                // dialog. If even that is unavailable, RetroDock refuses to guess.
                entryMode = prefs.getString("emu_" + emu.id + "_classified", "");
            }

            if (entryMode.isEmpty()) {
                continue;
            }

            if (inferred.isEmpty()) {
                inferred = entryMode;
            } else if (!inferred.equals(entryMode)) {
                Log.w(TAG, "Cannot infer mounted mode for " + emu.displayName
                        + " because settings entries disagree (" + inferred + " vs " + entryMode + ")");
                return "";
            }
        }

        return inferred;
    }

    /**
     * Ensures RetroDock has a trusted pre-hot-apply snapshot for the emulator's currently
     * mounted profile.
     *
     * <p>This method is called immediately before a live hot-apply action is attempted. The first
     * time it runs for a session, it snapshots every resolvable managed settings entry into a
     * private sidecar file or directory. Later hot-apply operations in the same session reuse the
     * existing snapshot. The snapshot is deliberately taken <i>before</i> the live edit so that
     * RetroDock can restore a known-good mounted profile on exit instead of promoting a temporary
     * hot-swapped file into the user's saved handheld/docked slots.</p>
     *
     * <p>If snapshot creation fails, the caller must skip the hot-apply. Allowing a live change
     * without a trusted rollback point would recreate the exact contamination problem this feature
     * is meant to solve.</p>
     */
    static boolean ensureHotSwapSessionPrepared(Context ctx, String emuId) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        EmulatorConfig emu = findEmulatorById(ctx, emuId);
        if (emu == null) {
            Log.w(TAG, "Cannot prepare hot-swap session for unknown emulator id: " + emuId);
            return false;
        }

        synchronized (getEmulatorLock(emu.id)) {
            HotSwapSessionState existing = readHotSwapSessionState(prefs, emu.id);
            if (existing.prepared) {
                return true;
            }

            String mountedMode = inferMountedMode(prefs, emu);
            if (mountedMode.isEmpty()) {
                Log.w(TAG, "Refusing hot-apply for " + emu.displayName
                        + " because RetroDock could not prove which profile is mounted");
                return false;
            }

            if (!createHotSwapTrustedSnapshots(prefs, emu, mountedMode)) {
                Log.w(TAG, "Refusing hot-apply for " + emu.displayName
                        + " because trusted-session snapshots could not be created");
                return false;
            }

            writeHotSwapSessionState(prefs, emu.id, true, false, mountedMode);
            Log.i(TAG, "Prepared untrusted hot-swap session for " + emu.displayName
                    + " (mountedMode=" + mountedMode + ")");
            return true;
        }
    }

    /**
     * Marks that at least one live hot-apply really happened during the current emulator session.
     *
     * <p>Prepared snapshots alone do not make a session untrusted. RetroDock may prepare a
     * rollback point and then discover that the actual hot-apply failed or the emulator exited
     * before the command could be delivered. The session becomes untrusted only after a live
     * setting change succeeded.</p>
     */
    static void markHotSwapSessionApplied(Context ctx, String emuId) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        synchronized (getEmulatorLock(emuId)) {
            HotSwapSessionState state = readHotSwapSessionState(prefs, emuId);
            if (!state.prepared || state.applied) {
                return;
            }
            writeHotSwapSessionState(prefs, emuId, true, true, state.mountedMode);
            Log.i(TAG, "Marked hot-swap session as untrusted for " + emuId);
        }
    }

    /**
     * Cleans up a prepared-but-unused hot-swap session.
     *
     * <p>This handles aborted hot-apply attempts. Example: RetroDock snapshots the mounted
     * profile, then the actual file rewrite fails because the emulator changed the config under
     * us. In that case no live hot-swap really happened, so the session must not be treated as
     * untrusted on exit. We delete the private sidecars and clear the session flags, but only if
     * {@code applied == false}. Once any real hot-apply succeeded, the rollback point must stay
     * intact until exit-time reconciliation.</p>
     */
    static void discardPreparedHotSwapSessionIfUnused(Context ctx, String emuId) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        EmulatorConfig emu = findEmulatorById(ctx, emuId);
        if (emu == null) {
            clearHotSwapSessionState(prefs, emuId);
            return;
        }

        synchronized (getEmulatorLock(emu.id)) {
            HotSwapSessionState state = readHotSwapSessionState(prefs, emu.id);
            if (!state.prepared || state.applied) {
                return;
            }
            cleanupHotSwapSessionArtifacts(prefs, emu, state.mountedMode);
            clearHotSwapSessionState(prefs, emu.id);
            Log.i(TAG, "Discarded unused prepared hot-swap session for " + emu.displayName);
        }
    }

    /**
     * Reconciles and clears an untrusted hot-swap session if one exists.
     *
     * <p>This is the exit-time heart of the feature. If any live hot-apply happened during the
     * session, RetroDock first restores the last trusted mounted profile sidecar back to the live
     * path, thereby discarding any session-time contamination of that mounted file. Only after
     * that restore succeeds does RetroDock decide whether a normal docked/handheld swap is still
     * required to match the device's final state.</p>
     *
     * <p>If the session was merely prepared but never applied, the sidecars are just cleaned up
     * and the caller continues with normal swap logic.</p>
     */
    private static HotSwapSessionFinalizationResult finalizeHotSwapSessionIfNeeded(
            Context ctx, SharedPreferences prefs, EmulatorConfig emu, boolean finalDocked) {
        HotSwapSessionState state = readHotSwapSessionState(prefs, emu.id);
        if (!state.prepared) {
            return HotSwapSessionFinalizationResult.NO_SESSION;
        }

        if (!state.applied) {
            cleanupHotSwapSessionArtifacts(prefs, emu, state.mountedMode);
            clearHotSwapSessionState(prefs, emu.id);
            return HotSwapSessionFinalizationResult.NO_SESSION;
        }

        if (state.mountedMode.isEmpty()) {
            Log.e(TAG, "Hot-swap session for " + emu.displayName
                    + " is marked applied but has no mounted-mode metadata");
            return HotSwapSessionFinalizationResult.FAILED;
        }

        if (!restoreTrustedHotSwapSnapshots(prefs, emu, state.mountedMode)) {
            Log.e(TAG, "Failed to restore trusted mounted profile for " + emu.displayName
                    + "; preserving hot-session markers for later retry");
            return HotSwapSessionFinalizationResult.FAILED;
        }

        boolean mountedDocked = MODE_DOCKED.equals(state.mountedMode);
        boolean didModeSwap = false;
        if (finalDocked != mountedDocked) {
            String classified = prefs.getString("emu_" + emu.id + "_classified", "");
            if (!swapSettings(prefs, emu, finalDocked, classified)) {
                Log.e(TAG, "Trusted-session restore succeeded but final mode swap still failed for "
                        + emu.displayName + " (finalDocked=" + finalDocked + ")");
                cleanupHotSwapSessionArtifacts(prefs, emu, state.mountedMode);
                clearHotSwapSessionState(prefs, emu.id);
                return HotSwapSessionFinalizationResult.FAILED;
            }
            didModeSwap = true;
        }

        cleanupHotSwapSessionArtifacts(prefs, emu, state.mountedMode);
        clearHotSwapSessionState(prefs, emu.id);
        return didModeSwap
                ? HotSwapSessionFinalizationResult.RESTORED_AND_SWAPPED
                : HotSwapSessionFinalizationResult.RESTORED_ONLY;
    }

    /**
     * Creates the private "trusted mounted profile" sidecars for one emulator session.
     *
     * <p><b>Problem this solves:</b> once a live hot-apply succeeds, the file currently mounted
     * at the active config path can no longer be trusted as the user's true handheld/docked
     * profile. The emulator may later save temporary runtime changes into that same mounted file.
     * If RetroDock simply saved that exit-time file into `.handheld` or `.docked`, it would be
     * mislabeled profile drift, not a real user-confirmed profile save.</p>
     *
     * <p><b>Fix:</b> before the first live edit of the session, snapshot the currently mounted
     * active path into a private sidecar. If the trusted state for that entry is "path absent",
     * record that fact explicitly with an empty marker file instead of inventing bytes. This lets
     * exit-time reconciliation restore either a real file/directory or a trusted absence.</p>
     *
     * <p><b>Failure policy:</b> fail closed. If any entry cannot be snapshotted safely, the
     * entire hot-apply must be refused. Live edits without a rollback point would recreate the
     * contamination bug this feature is meant to eliminate.</p>
     */
    private static boolean createHotSwapTrustedSnapshots(SharedPreferences prefs, EmulatorConfig emu,
                                                         String mountedMode) {
        List<HotSwapSnapshotPlan> plans = buildHotSwapSnapshotPlans(prefs, emu, mountedMode);
        if (plans.isEmpty()) {
            Log.w(TAG, "No managed settings entries could be snapshotted for " + emu.displayName);
            return false;
        }

        for (HotSwapSnapshotPlan plan : plans) {
            if (!deleteIfExists(plan.trustedSnapshot) || !deleteIfExists(plan.absentMarker)) {
                cleanupHotSwapSessionArtifacts(prefs, emu, mountedMode);
                return false;
            }

            boolean ok;
            if (plan.activeExists) {
                ok = copyPathForSnapshot(plan.current, plan.trustedSnapshot);
            } else {
                ok = createMarkerFile(plan.absentMarker);
            }

            if (!ok) {
                Log.e(TAG, "Failed to create trusted hot-session snapshot for "
                        + plan.current.getAbsolutePath());
                cleanupHotSwapSessionArtifacts(prefs, emu, mountedMode);
                return false;
            }
        }

        return true;
    }

    /**
     * Restores the trusted mounted-profile sidecars back to the active config paths.
     *
     * <p><b>Why this is a transaction:</b> many emulators have more than one managed settings
     * entry. Restoring entry 1 and then failing on entry 2 would leave the emulator half restored
     * and half contaminated. To avoid that, we park each current live entry in a temporary
     * `.retrodock-sessiondirty.*` sidecar, copy the trusted snapshot back into place, and keep
     * the parked dirty copy until the whole emulator restore succeeds. If any later entry fails,
     * we can roll the earlier entries back to the exact exit-time state we found.</p>
     *
     * <p><b>Why copy instead of move the trusted snapshot:</b> the trusted sidecar is our only
     * rollback anchor while the restore transaction is still in flight. Keeping it intact until
     * the entire restore succeeds prevents one partial restore failure from also destroying the
     * last known-good snapshot.</p>
     */
    private static boolean restoreTrustedHotSwapSnapshots(SharedPreferences prefs, EmulatorConfig emu,
                                                          String mountedMode) {
        List<HotSwapSnapshotPlan> snapshotPlans = buildHotSwapSnapshotPlans(prefs, emu, mountedMode);
        List<HotSwapRestorePlan> restorePlans = new ArrayList<>();

        for (HotSwapSnapshotPlan snapshotPlan : snapshotPlans) {
            boolean hasTrustedSnapshot = snapshotPlan.trustedSnapshot.exists();
            boolean hasAbsentMarker = snapshotPlan.absentMarker.exists();
            if (!hasTrustedSnapshot && !hasAbsentMarker) {
                continue;
            }
            if (hasTrustedSnapshot && hasAbsentMarker) {
                Log.e(TAG, "Hot-session restore found both trusted snapshot and absent marker for "
                        + snapshotPlan.current.getAbsolutePath());
                return false;
            }
            restorePlans.add(new HotSwapRestorePlan(snapshotPlan,
                    buildHotSessionDirtyParking(snapshotPlan.current)));
        }

        if (restorePlans.isEmpty()) {
            Log.e(TAG, "Hot-session restore requested for " + emu.displayName
                    + " but no trusted sidecars were present");
            return false;
        }

        List<HotSwapRestorePlan> appliedPlans = new ArrayList<>();
        for (HotSwapRestorePlan restorePlan : restorePlans) {
            appliedPlans.add(restorePlan);

            if (restorePlan.snapshotPlan.current.exists()) {
                if (!moveWithFallback(restorePlan.snapshotPlan.current, restorePlan.dirtyParking)) {
                    Log.e(TAG, "Failed to park untrusted live entry before restore: "
                            + restorePlan.snapshotPlan.current.getAbsolutePath());
                    rollbackRestoredHotSwapEntries(appliedPlans);
                    return false;
                }
                restorePlan.parkedDirty = true;
            }

            if (restorePlan.snapshotPlan.trustedSnapshot.exists()) {
                if (!copyPathForSnapshot(restorePlan.snapshotPlan.trustedSnapshot,
                        restorePlan.snapshotPlan.current)) {
                    Log.e(TAG, "Failed to copy trusted snapshot back into place: "
                            + restorePlan.snapshotPlan.current.getAbsolutePath());
                    rollbackRestoredHotSwapEntries(appliedPlans);
                    return false;
                }
                restorePlan.restoredTrustedCopy = true;
            } else if (!restorePlan.snapshotPlan.absentMarker.exists()) {
                Log.e(TAG, "Trusted restore metadata vanished mid-restore for "
                        + restorePlan.snapshotPlan.current.getAbsolutePath());
                rollbackRestoredHotSwapEntries(appliedPlans);
                return false;
            }
        }

        for (HotSwapRestorePlan restorePlan : restorePlans) {
            if (restorePlan.parkedDirty && !deleteIfExists(restorePlan.dirtyParking)) {
                Log.w(TAG, "Trusted restore succeeded but cleanup of parked dirty entry failed: "
                        + restorePlan.dirtyParking.getAbsolutePath());
            }
        }
        return true;
    }

    /**
     * Rolls back a partially completed trusted-restore transaction.
     *
     * <p>This method is the inverse of {@link #restoreTrustedHotSwapSnapshots}. For every entry
     * that already changed, remove any restored trusted copy and move the parked dirty live copy
     * back into place. Reverse-order rollback mirrors normal transaction unwinding and minimizes
     * the risk of colliding with paths we just recreated.</p>
     */
    private static boolean rollbackRestoredHotSwapEntries(List<HotSwapRestorePlan> appliedPlans) {
        boolean allRolledBack = true;
        for (int i = appliedPlans.size() - 1; i >= 0; i--) {
            HotSwapRestorePlan plan = appliedPlans.get(i);

            if (plan.restoredTrustedCopy && plan.snapshotPlan.current.exists()
                    && !deleteRecursive(plan.snapshotPlan.current)) {
                Log.e(TAG, "Failed to delete partially restored trusted copy during rollback: "
                        + plan.snapshotPlan.current.getAbsolutePath());
                allRolledBack = false;
            }

            if (plan.parkedDirty) {
                if (plan.snapshotPlan.current.exists()
                        && !deleteRecursive(plan.snapshotPlan.current)) {
                    Log.e(TAG, "Failed to clear restore target before moving dirty copy back: "
                            + plan.snapshotPlan.current.getAbsolutePath());
                    allRolledBack = false;
                    continue;
                }
                if (!moveWithFallback(plan.dirtyParking, plan.snapshotPlan.current)) {
                    Log.e(TAG, "Failed to move parked dirty entry back during rollback: "
                            + plan.dirtyParking.getAbsolutePath());
                    allRolledBack = false;
                }
            }
        }
        return allRolledBack;
    }

    /**
     * Deletes all private hot-session sidecars for one emulator.
     *
     * <p>Cleanup deliberately removes both docked and handheld trusted/absent markers rather than
     * trying to infer which one "should" exist. These sidecars are purely internal bookkeeping
     * owned by RetroDock; if stale leftovers from an older crash survive, the safest cleanup is to
     * remove the entire private namespace and let the next hot session rebuild it from scratch.</p>
     */
    private static boolean cleanupHotSwapSessionArtifacts(SharedPreferences prefs, EmulatorConfig emu,
                                                          String mountedMode) {
        boolean allDeleted = true;
        if (emu.settingsFiles == null || emu.settingsFiles.length == 0) {
            return true;
        }

        for (String relPath : emu.settingsFiles) {
            String resolved = resolveManagedPathForArtifacts(prefs, emu, relPath);
            if (resolved == null) {
                resolved = findSettingsFile(prefs, emu, relPath);
            }
            if (resolved == null) {
                continue;
            }

            File basePath = new File(resolved);
            allDeleted &= deleteIfExists(buildHotSessionTrustedSnapshot(basePath, MODE_DOCKED));
            allDeleted &= deleteIfExists(buildHotSessionTrustedSnapshot(basePath, MODE_HANDHELD));
            allDeleted &= deleteIfExists(buildHotSessionAbsentMarker(basePath, MODE_DOCKED));
            allDeleted &= deleteIfExists(buildHotSessionAbsentMarker(basePath, MODE_HANDHELD));
            allDeleted &= cleanupHotSwapDirtyParkings(basePath);
        }

        return allDeleted;
    }

    /**
     * Resolves the per-entry file plans used for snapshot creation and trusted restore.
     *
     * <p>The important property is path stability. Once a running session is marked untrusted, we
     * must keep using the same physical base path that the session was prepared against. That is
     * why this builder prefers the current managed resolver but falls back to the cached/override
     * path RetroDock already stored for that entry.</p>
     */
    private static List<HotSwapSnapshotPlan> buildHotSwapSnapshotPlans(SharedPreferences prefs,
                                                                       EmulatorConfig emu,
                                                                       String mountedMode) {
        List<HotSwapSnapshotPlan> plans = new ArrayList<>();
        if (emu.settingsFiles == null || emu.settingsFiles.length == 0) {
            return plans;
        }

        for (String relPath : emu.settingsFiles) {
            // Prefer RetroDock's explicit override/cached path before re-running auto-detection.
            // The hot-session feature must restore the exact same physical path it snapshotted at
            // prepare time. If a second matching root appears later (for example a stale legacy
            // config beside the real scoped-storage config), a fresh auto-detect pass could pick
            // the wrong tree and restore the trusted snapshot into the wrong location.
            String resolved = resolveManagedPathForArtifacts(prefs, emu, relPath);
            if (resolved != null) {
                File resolvedBase = new File(resolved);
                if (!pathHasManagedState(resolved, true) && !pathHasHotSwapArtifacts(resolvedBase)) {
                    resolved = null;
                }
            }
            if (resolved == null) {
                resolved = findSettingsFile(prefs, emu, relPath);
            }
            if (resolved == null) {
                continue;
            }

            File current = new File(resolved);
            plans.add(new HotSwapSnapshotPlan(
                    relPath,
                    current,
                    buildHotSessionTrustedSnapshot(current, mountedMode),
                    buildHotSessionAbsentMarker(current, mountedMode),
                    current.exists()
            ));
        }

        return plans;
    }

    private static boolean cleanupHotSwapDirtyParkings(File basePath) {
        File parent = basePath.getParentFile();
        if (parent == null || !parent.exists()) {
            return true;
        }

        File[] children = parent.listFiles();
        if (children == null) {
            Log.w(TAG, "Unable to enumerate hot-session dirty sidecars in "
                    + parent.getAbsolutePath());
            return false;
        }

        boolean allDeleted = true;
        String dirtyPrefix = basePath.getName() + HOT_SESSION_DIRTY_SUFFIX;
        for (File child : children) {
            if (!child.getName().startsWith(dirtyPrefix)) {
                continue;
            }
            if (!deleteIfExists(child)) {
                allDeleted = false;
            }
        }
        return allDeleted;
    }

    private static boolean pathHasHotSwapArtifacts(File basePath) {
        return buildHotSessionTrustedSnapshot(basePath, MODE_DOCKED).exists()
                || buildHotSessionTrustedSnapshot(basePath, MODE_HANDHELD).exists()
                || buildHotSessionAbsentMarker(basePath, MODE_DOCKED).exists()
                || buildHotSessionAbsentMarker(basePath, MODE_HANDHELD).exists()
                || hasHotSwapDirtyParking(basePath);
    }

    private static boolean hasHotSwapDirtyParking(File basePath) {
        File parent = basePath.getParentFile();
        if (parent == null || !parent.exists()) {
            return false;
        }

        File[] children = parent.listFiles();
        if (children == null) {
            return false;
        }

        String dirtyPrefix = basePath.getName() + HOT_SESSION_DIRTY_SUFFIX;
        for (File child : children) {
            if (child.getName().startsWith(dirtyPrefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean deleteIfExists(File path) {
        return path == null || !path.exists() || deleteRecursive(path);
    }

    /**
     * Creates an empty marker file and forces it to stable storage.
     *
     * <p>An absent marker is how RetroDock remembers "the trusted state for this managed entry
     * was that no active file/directory existed". Writing a real marker is safer than keeping
     * that fact only in memory because the app may be killed between hot-apply and exit-time
     * reconciliation.</p>
     */
    private static boolean createMarkerFile(File marker) {
        File parent = marker.getParentFile();
        if (parent == null || !parent.exists()) {
            Log.e(TAG, "Cannot create marker because parent directory is missing: "
                    + marker.getAbsolutePath());
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.getFD().sync();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to create marker file " + marker.getAbsolutePath() + ": "
                    + e.getMessage());
            marker.delete();
            return false;
        }
    }

    /**
     * Copies either a file or directory tree into a private trusted sidecar.
     *
     * <p>This helper is intentionally separate from {@link #moveWithFallback}. The hot-session
     * feature is taking a snapshot, not trying to emulate a rename, so copy semantics are
     * acceptable here as long as the destination is private and we verify the source did not
     * change during the snapshot. The destination must never already exist; snapshot sidecars are
     * treated as immutable once created.</p>
     */
    private static boolean copyPathForSnapshot(File src, File dst) {
        if (!src.exists()) {
            Log.e(TAG, "Snapshot source does not exist: " + src.getAbsolutePath());
            return false;
        }
        if (dst.exists()) {
            Log.e(TAG, "Snapshot destination already exists: " + dst.getAbsolutePath());
            return false;
        }

        try {
            if (src.isDirectory()) {
                return copyDirectorySnapshot(src, dst);
            }
            copyFileDurably(src, dst);
            applyBasicPermissions(src, dst);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to snapshot " + src.getAbsolutePath() + " -> "
                    + dst.getAbsolutePath() + ": " + e.getMessage());
            deleteIfExists(dst);
            return false;
        }
    }

    /**
     * Copies an entire directory tree into a trusted sidecar with before/after verification.
     *
     * <p><b>Why directory snapshots need extra validation:</b> unlike a regular file copy, a
     * directory snapshot spans many child files and subdirectories. A running emulator could save
     * a new override or rewrite one file halfway through that copy. If RetroDock blindly accepted
     * the result, the "trusted" sidecar would already be internally inconsistent.</p>
     *
     * <p><b>Fix:</b> record a full tree manifest (relative paths, file sizes, timestamps, and
     * content hashes) before the copy, copy the tree into the sidecar, then capture manifests for
     * both the source and the copy again. The snapshot is accepted only if:</p>
     * <ol>
     *   <li>The source manifest is identical before and after the copy, proving the source did
     *       not mutate while we were snapshotting it.</li>
     *   <li>The copied tree manifest exactly matches that stable source manifest.</li>
     * </ol>
     *
     * <p>This is more expensive than a blind recursive copy, but it runs only on the first
     * successful hot-apply of a session and it dramatically raises confidence that the trusted
     * restore point really is a coherent config tree.</p>
     */
    private static boolean copyDirectorySnapshot(File src, File dst) throws IOException {
        if (!src.isDirectory()) {
            throw new IOException("copyDirectorySnapshot requires a directory source: " + src);
        }
        if (dst.exists()) {
            throw new IOException("Snapshot destination already exists: " + dst);
        }

        List<String> sourceBefore = describeDirectoryTree(src);
        if (sourceBefore == null) {
            throw new IOException("Could not describe source directory before snapshot: " + src);
        }

        if (!copyDirectorySnapshotRecursive(src, dst)) {
            deleteIfExists(dst);
            throw new IOException("Recursive directory snapshot copy failed: " + src);
        }

        List<String> sourceAfter = describeDirectoryTree(src);
        List<String> copyAfter = describeDirectoryTree(dst);
        if (sourceAfter == null || copyAfter == null) {
            deleteIfExists(dst);
            throw new IOException("Could not verify directory snapshot manifests for: " + src);
        }
        if (!sourceBefore.equals(sourceAfter)) {
            deleteIfExists(dst);
            throw new IOException("Source directory changed while snapshotting: " + src);
        }
        if (!sourceBefore.equals(copyAfter)) {
            deleteIfExists(dst);
            throw new IOException("Snapshot directory does not match source manifest: " + src);
        }
        return true;
    }

    private static boolean copyDirectorySnapshotRecursive(File src, File dst) throws IOException {
        if (!src.isDirectory()) {
            throw new IOException("Expected directory source: " + src);
        }
        if (dst.exists()) {
            throw new IOException("Destination already exists: " + dst);
        }
        if (!dst.mkdir()) {
            throw new IOException("Failed to create snapshot directory: " + dst);
        }

        File[] children = src.listFiles();
        if (children == null) {
            deleteIfExists(dst);
            throw new IOException("Failed to enumerate directory children for snapshot: " + src);
        }

        List<File> sortedChildren = new ArrayList<>();
        Collections.addAll(sortedChildren, children);
        Collections.sort(sortedChildren, (left, right) -> left.getName().compareTo(right.getName()));

        for (File child : sortedChildren) {
            File childDst = new File(dst, child.getName());
            if (child.isDirectory()) {
                if (!copyDirectorySnapshotRecursive(child, childDst)) {
                    deleteIfExists(dst);
                    return false;
                }
            } else if (child.isFile()) {
                copyFileDurably(child, childDst);
                applyBasicPermissions(child, childDst);
            } else {
                deleteIfExists(dst);
                throw new IOException("Unsupported non-regular path in config tree: "
                        + child.getAbsolutePath());
            }
        }

        applyBasicPermissions(src, dst);
        if (src.lastModified() > 0) {
            dst.setLastModified(src.lastModified());
        }
        return true;
    }

    /**
     * Builds a stable manifest of a directory tree for snapshot verification.
     *
     * <p>Each manifest entry includes the relative path plus enough metadata to catch the kinds
     * of silent drift that matter for config integrity: file-vs-directory shape, file length,
     * modification time, and a SHA-256 digest for file contents. The resulting list is sorted so
     * two logically identical trees compare equal regardless of filesystem enumeration order.</p>
     */
    private static List<String> describeDirectoryTree(File root) {
        if (!root.exists() || !root.isDirectory()) {
            return null;
        }

        List<String> entries = new ArrayList<>();
        if (!describeDirectoryTreeRecursive(root, root, entries)) {
            return null;
        }
        Collections.sort(entries);
        return entries;
    }

    private static boolean describeDirectoryTreeRecursive(File root, File current, List<String> entries) {
        String relativePath;
        if (root.equals(current)) {
            relativePath = ".";
        } else {
            String absoluteRoot = root.getAbsolutePath();
            String absoluteCurrent = current.getAbsolutePath();
            relativePath = absoluteCurrent.substring(absoluteRoot.length() + 1)
                    .replace(File.separatorChar, '/');
        }

        if (current.isDirectory()) {
            entries.add("D|" + relativePath + "|" + current.lastModified());
            File[] children = current.listFiles();
            if (children == null) {
                Log.w(TAG, "Failed to enumerate directory while describing snapshot tree: "
                        + current.getAbsolutePath());
                return false;
            }

            List<File> sortedChildren = new ArrayList<>();
            Collections.addAll(sortedChildren, children);
            Collections.sort(sortedChildren, (left, right) -> left.getName().compareTo(right.getName()));
            for (File child : sortedChildren) {
                if (!describeDirectoryTreeRecursive(root, child, entries)) {
                    return false;
                }
            }
            return true;
        }

        if (!current.isFile()) {
            Log.w(TAG, "Unsupported non-regular path in snapshot tree: " + current.getAbsolutePath());
            return false;
        }

        try {
            entries.add("F|" + relativePath + "|" + current.length() + "|"
                    + current.lastModified() + "|" + sha256File(current));
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Failed to hash file while describing snapshot tree: "
                    + current.getAbsolutePath() + " (" + e.getMessage() + ")");
            return false;
        }
    }

    private static String sha256File(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static void applyBasicPermissions(File src, File dst) {
        dst.setReadable(src.canRead(), false);
        dst.setWritable(src.canWrite(), false);
        dst.setExecutable(src.canExecute(), false);
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
     * <p><b>Audit Fix #10:</b> the method now behaves like an emulator-level transaction.
     * If any one entry fails after an earlier entry already swapped, RetroDock recovers any
     * stranded temp files from the failing entry and then rolls the earlier entries back to
     * their original state. This prevents multi-entry emulators from ending up half docked
     * and half handheld.
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

        // Audit Fix #10: Treat multi-entry emulator swaps as one transaction.
        //
        // CONCERN: Several emulators have more than one settings entry (for example a main
        // config file plus an overrides directory). The old code swapped each entry
        // independently and immediately committed the result. If entry 1 succeeded and entry 2
        // later failed, the emulator was left in a mixed on-disk state where part of its config
        // tree was docked and part was handheld.
        //
        // FIX: We build a stable per-entry plan first, execute the entries sequentially, and if
        // any one entry fails we attempt to roll back every earlier entry that already changed.
        // This gives RetroDock "all or nothing" behavior at the emulator level instead of just
        // the individual-file level.
        List<SettingsEntryPlan> plannedEntries = new ArrayList<>();
        for (String relPath : emu.settingsFiles) {
            String resolved = findSettingsFile(prefs, emu, relPath);
            if (resolved == null) {
                if (isMissingManagedEntry(prefs, emu, relPath)) {
                    Log.e(TAG, "Aborting swap for " + emu.displayName + " because a previously "
                            + "managed settings entry disappeared: " + relPath);
                    return false;
                }
                Log.i(TAG, "Settings file not found in any root, skipping: " + relPath);
                continue;
            }

            File current = new File(resolved);
            File dockedFile = new File(resolved + ".docked");
            File handheldFile = new File(resolved + ".handheld");
            boolean hadBackupBeforeSwap = dockedFile.exists() || handheldFile.exists();

            plannedEntries.add(new SettingsEntryPlan(
                    relPath,
                    current,
                    dockedFile,
                    handheldFile,
                    !classified.isEmpty() && !isEntrySeeded(prefs, emu, relPath),
                    hadBackupBeforeSwap
            ));
        }

        boolean anySwapped = false;
        List<SettingsEntryPlan> appliedEntries = new ArrayList<>();
        for (SettingsEntryPlan entry : plannedEntries) {
            SwapResult result;
            if (docked) {
                // Transitioning TO docked: restore .docked backup as active, save current as .handheld
                result = swapSingleEntry(entry.current, entry.dockedBackup, entry.handheldBackup,
                        entry.allowClassification ? classified : "", "handheld", "docked");
            } else {
                // Transitioning TO handheld: restore .handheld backup as active, save current as .docked
                result = swapSingleEntry(entry.current, entry.handheldBackup, entry.dockedBackup,
                        entry.allowClassification ? classified : "", "docked", "handheld");
            }

            if (result == SwapResult.SWAPPED) {
                anySwapped = true;
                appliedEntries.add(entry);
                continue;
            }
            if (result == SwapResult.NO_OP) {
                continue;
            }

            // Stabilize any stranded temp files from the failing entry first, then try to undo
            // every earlier entry that already swapped. Running recovery before and after the
            // rollback keeps named temps from being mistaken for completed entries.
            recoverFromPartialSwap(prefs, emu);
            if (!rollbackAppliedEntries(appliedEntries, docked)) {
                Log.e(TAG, "CRITICAL: rollback failed after a multi-entry swap error for "
                        + emu.displayName + ". Emulator may need manual verification.");
            }
            recoverFromPartialSwap(prefs, emu);
            return false;
        }

        if (!anySwapped) {
            return false;
        }

        // Commit the bootstrap/seeded markers only after the full emulator transaction has
        // succeeded. Marking an entry as seeded before the transaction commits would make a
        // later rollback look like a completed first-time swap, which would suppress the
        // classification hint for that entry the next time it appears.
        for (SettingsEntryPlan entry : plannedEntries) {
            if (entry.hadBackupBeforeSwap || appliedEntries.contains(entry)) {
                markEntrySeeded(prefs, emu, entry.relPath);
            }
        }
        return true;
    }

    /**
     * Returns {@code true} when an entry looks like RetroDock had previously been managing it,
     * but the operational resolver can no longer find a safe path for it.
     *
     * <p>This is treated as a hard failure for multi-entry swaps. Silently skipping a missing
     * previously-managed entry would let the rest of the emulator swap continue, producing the
     * same mixed-profile state this transaction logic is trying to prevent.</p>
     */
    private static boolean isMissingManagedEntry(SharedPreferences prefs, EmulatorConfig emu, String relPath) {
        if (!getOverridePath(prefs, emu, relPath).isEmpty()) {
            return true;
        }
        return !prefs.getString(buildResolvedPathKey(emu.id, relPath), "").isEmpty();
    }

    /**
     * Rolls back previously-swapped entries in reverse order after a later entry fails.
     *
     * <p>We reverse the original dock direction so every entry is restored to the exact state it
     * had before the transaction started. Reverse-order rollback is the safest choice because it
     * mirrors normal transaction unwinding: the most recently changed entry is undone first.</p>
     */
    private static boolean rollbackAppliedEntries(List<SettingsEntryPlan> appliedEntries, boolean docked) {
        boolean allRolledBack = true;
        for (int i = appliedEntries.size() - 1; i >= 0; i--) {
            SettingsEntryPlan entry = appliedEntries.get(i);
            SwapResult rollback;
            if (docked) {
                rollback = swapSingleEntry(entry.current, entry.handheldBackup, entry.dockedBackup,
                        "", "docked", "handheld");
            } else {
                rollback = swapSingleEntry(entry.current, entry.dockedBackup, entry.handheldBackup,
                        "", "handheld", "docked");
            }

            if (rollback != SwapResult.SWAPPED) {
                allRolledBack = false;
                Log.e(TAG, "Rollback failed for " + entry.current.getAbsolutePath()
                        + " (result=" + rollback + ")");
            }
        }
        return allRolledBack;
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
     * @return {@link SwapResult#SWAPPED} if bytes were moved, {@link SwapResult#NO_OP} if the
     *         entry did not need any action right now, or {@link SwapResult#FAILED} if a
     *         filesystem operation failed
     */
    private static SwapResult swapSingleEntry(File current, File targetBackup, File saveBackup,
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
                    return SwapResult.FAILED;
                }
                // Step 2: Move target backup into the active position.
                //
                // CONCERN (Audit Fix #2): If step 2 fails AND the rollback (temp → current)
                // also fails, the user's active config file is stranded in the .swaptmp file
                // with no active file visible to the emulator. This is a total data loss
                // scenario from the emulator's perspective — it would see no config and
                // regenerate defaults, destroying the user's settings.
                //
                // FIX: Retry the rollback up to 3 times with a short delay. Transient FUSE
                // errors (permission races, inode lock contention) often succeed on retry.
                // If all retries fail, leave the temp file in place (with its self-describing
                // name) so that recoverFromPartialSwap() can restore it on the next run.
                // The temp file is the ONLY remaining copy of the user's active settings,
                // so we must NEVER delete it in this failure path.
                if (!moveWithFallback(targetBackup, current)) {
                    Log.e(TAG, "Step 2 failed: could not restore target, rolling back: " + targetBackup);
                    // Retry rollback with small delays to handle transient FUSE errors
                    boolean rolledBack = false;
                    for (int retry = 0; retry < 3; retry++) {
                        if (moveWithFallback(temp, current)) {
                            rolledBack = true;
                            break;
                        }
                        Log.w(TAG, "Rollback retry " + (retry + 1) + "/3 failed for: " + temp);
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    }
                    if (!rolledBack) {
                        // CRITICAL: Rollback completely failed. The active file is stranded
                        // in the temp location. DO NOT delete the temp — it's the only copy.
                        // recoverFromPartialSwap() will restore it on the next service run.
                        Log.e(TAG, "CRITICAL: Rollback failed after 3 retries. User's active "
                                + "config is stranded at: " + temp.getAbsolutePath()
                                + " — recovery will restore it on next run");
                    }
                    return SwapResult.FAILED;
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
                    return SwapResult.FAILED;
                }
            }
            return SwapResult.SWAPPED;
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
                return SwapResult.FAILED;
            }
            return SwapResult.SWAPPED;
        }

        // --- No swap possible: no backup exists and no classification applies ---
        Log.i(TAG, "No backup exists yet for: " + current.getAbsolutePath());
        return SwapResult.NO_OP;
    }

    // ==================================================================================
    // FUSE-Safe File Move
    // ==================================================================================

    /**
     * Moves a file or directory from {@code src} to {@code dst}, falling back to a
     * crash-safe file copy + delete only when a direct move is unavailable.
     *
     * <h3>Why this is needed</h3>
     * <p>On Android 11+ with scoped storage, many paths are backed by FUSE (Filesystem in
     * Userspace). A same-directory rename normally remains the safest operation because it keeps
     * the config tree intact and avoids exposing any partially-written destination. Unfortunately,
     * Java's classic {@link File#renameTo} can fail on some Android/FUSE combinations even when
     * a kernel-level move would still be possible. We therefore try multiple direct move APIs
     * first ({@link Files#move} with and without {@code ATOMIC_MOVE}, then {@code renameTo()})
     * before considering any copy-based fallback.</p>
     *
     * <h3>CONCERN: Config file integrity during moves</h3>
     * <p>This helper is the foundation of every profile swap. The old copy+delete fallback
     * treated files and directories the same, which turned out to be too risky for directory
     * trees. If a directory copy succeeded but the source delete only partially succeeded, the
     * next swap step could copy the opposite profile into a destination directory that still
     * contained leftover files from the original profile. That created a merged, mixed-profile
     * config tree -- valid enough to exist on disk, but semantically corrupted.</p>
     *
     * <h3>Safety guarantees (how the fix protects config files)</h3>
     * <ol>
     *   <li><b>Destination must be absent:</b> We now refuse to move onto an existing path.
     *       This prevents directory merges and surfaces state drift immediately.</li>
     *   <li><b>Directories never use copy+delete fallback:</b> If every direct move API fails
     *       for a directory, the swap fails closed instead of attempting a risky emulated move.</li>
     *   <li><b>Files use a durable copy fallback:</b> For regular files only, we copy bytes to a
     *       staging file, {@code fsync()} them, move the staged copy into place, and only then
     *       delete the source. This preserves data even on FUSE-backed storage.</li>
     *   <li><b>Source mutation during fallback is detected:</b> If the source file changes while
     *       we are copying it, the fallback aborts rather than claiming success for a stale copy.</li>
     * </ol>
     *
     * @param src the source file or directory to move
     * @param dst the destination path (must not already exist)
     * @return {@code true} if the move succeeded via either rename or copy+delete
     */
    private static boolean moveWithFallback(File src, File dst) {
        if (!src.exists()) {
            Log.e(TAG, "moveWithFallback: source does not exist: " + src.getAbsolutePath());
            return false;
        }
        if (dst.exists()) {
            Log.e(TAG, "moveWithFallback: refusing to overwrite existing destination: "
                    + dst.getAbsolutePath());
            return false;
        }

        // Fast path: direct move. We try the modern NIO move APIs first because they can succeed
        // on some Android/FUSE combinations where the legacy File.renameTo() reports failure.
        if (moveDirectly(src, dst)) {
            return true;
        }

        // Audit Fix #11: Directory copy+delete fallback was removed on purpose.
        //
        // CONCERN: Recursive copy+delete cannot provide the same integrity guarantees as a real
        // rename for config directories. If even one source child fails to delete, the next swap
        // step can merge profiles together. Because the user's saved config tree matters more than
        // "always swap no matter what", the safe behavior is to fail closed here.
        if (src.isDirectory()) {
            Log.e(TAG, "Direct directory move failed and copy+delete fallback is disabled for "
                    + "safety: " + src.getAbsolutePath());
            return false;
        }

        // Slow path for FILES ONLY: durable copy to a staging file, then expose the destination.
        Log.w(TAG, "Direct file move failed for " + src.getName()
                + ", falling back to staged copy+delete");
        File stagedCopy = new File(dst.getAbsolutePath() + FILE_COPY_STAGING_SUFFIX + "." + System.nanoTime());
        try {
            copyFileDurably(src, stagedCopy);
            if (!moveDirectly(stagedCopy, dst)) {
                Log.e(TAG, "moveWithFallback: failed to expose staged copy at destination: "
                        + dst.getAbsolutePath());
                stagedCopy.delete();
                return false;
            }

            // Only after the destination is fully durable and visible do we delete the source.
            // If source deletion fails for a regular file, the source copy is still intact, so we
            // can safely remove the destination and report failure instead of pretending the move
            // completed. That keeps the caller from building a transaction on top of a duplicate.
            if (!src.delete()) {
                Log.e(TAG, "moveWithFallback: copied file but could not delete source: "
                        + src.getAbsolutePath());
                if (dst.exists() && !dst.delete()) {
                    File quarantine = buildQuarantineTarget(dst);
                    if (moveDirectly(dst, quarantine)) {
                        Log.e(TAG, "moveWithFallback: moved duplicate destination out of the live "
                                + "namespace after source delete failure: " + quarantine.getAbsolutePath());
                    } else {
                        Log.e(TAG, "moveWithFallback: FAILED to clean up duplicate destination after "
                                + "source delete failure: " + dst.getAbsolutePath());
                    }
                }
                return false;
            }

            return true;
        } catch (IOException e) {
            Log.e(TAG, "Copy fallback failed: " + src + " -> " + dst + ": " + e.getMessage());
            stagedCopy.delete();
            return false;
        }
    }

    /**
     * Tries every direct move API available to us, from strongest semantics to weakest.
     *
     * <p>{@code ATOMIC_MOVE} is ideal when supported because the source and destination switch in
     * one kernel operation. Plain {@code Files.move()} is still preferable to a manual copy
     * because the filesystem, not RetroDock, owns the move semantics. {@code renameTo()} is kept
     * as a final compatibility fallback because some Android builds wire it up differently.</p>
     */
    private static boolean moveDirectly(File src, File dst) {
        try {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception ignored) {
        }
        try {
            Files.move(src.toPath(), dst.toPath());
            return true;
        } catch (Exception ignored) {
        }
        return src.renameTo(dst);
    }

    /**
     * Copies one regular file to a staging location and forces the bytes to stable storage.
     *
     * <p>This method intentionally refuses directory input. Directory-tree copies were the
     * source of the mixed-profile corruption risk described above, so only regular files are
     * eligible for manual fallback now.</p>
     *
     * <h3>How this protects emulator configs</h3>
     * <ul>
     *   <li><b>Durability before source deletion:</b> the destination file descriptor is synced
     *       before the caller is allowed to remove the source.</li>
     *   <li><b>Source-change detection:</b> the source size and timestamp are sampled before the
     *       copy and rechecked after it completes. If the source changed mid-copy, we abort
     *       instead of publishing a stale or torn duplicate.</li>
     *   <li><b>Metadata preservation:</b> the original last-modified timestamp is restored on the
     *       staging copy so file-based emulators do not misinterpret the fallback as a fresh edit.</li>
     * </ul>
     */
    private static void copyFileDurably(File src, File dst) throws IOException {
        if (!src.isFile()) {
            throw new IOException("copyFileDurably only supports regular files: " + src);
        }
        if (dst.exists()) {
            throw new IOException("Destination already exists: " + dst);
        }

        long originalSize = src.length();
        long originalLastModified = src.lastModified();
        boolean originalReadable = src.canRead();
        boolean originalWritable = src.canWrite();
        boolean originalExecutable = src.canExecute();

        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.getFD().sync();
        } catch (IOException copyFailure) {
            dst.delete();
            throw copyFailure;
        }

        if (src.length() != originalSize || src.lastModified() != originalLastModified) {
            dst.delete();
            throw new IOException("Source changed while file fallback copy was in progress: " + src);
        }
        if (dst.length() != originalSize) {
            dst.delete();
            throw new IOException("Copied file length mismatch for: " + src);
        }

        if (originalLastModified > 0) {
            dst.setLastModified(originalLastModified);
        }
        dst.setReadable(originalReadable, false);
        dst.setWritable(originalWritable, false);
        dst.setExecutable(originalExecutable, false);
    }

    private static File buildQuarantineTarget(File originalPath) {
        File quarantine = new File(originalPath.getAbsolutePath() + QUARANTINE_SUFFIX);
        if (quarantine.exists()) {
            quarantine = new File(originalPath.getAbsolutePath() + QUARANTINE_SUFFIX
                    + "." + System.currentTimeMillis());
        }
        return quarantine;
    }

    /**
     * Recursively deletes a file or directory tree, returning whether the deletion
     * was fully successful.
     *
     * <h3>CONCERN (Audit Fix #4): Silent deletion failures leave orphaned files</h3>
     * <p>The previous implementation called {@code target.delete()} and silently ignored
     * failures. If a child file could not be deleted (locked by another process, permission
     * denied on FUSE), the recursion continued silently, the parent directory delete failed
     * (because it still had children), and the caller believed the delete succeeded.</p>
     *
     * <p>This created a dangerous inconsistency in {@link #moveWithFallback}: the method
     * returned {@code true} (signaling a successful move), but the source still partially
     * existed. On the next swap, both source and destination would exist simultaneously,
     * violating the invariant that a config should only exist in one location.</p>
     *
     * <h3>How this fix protects config files</h3>
     * <p>The method now returns {@code false} if ANY file or directory in the tree could
     * not be deleted. Callers can use this signal to avoid claiming a move succeeded when
     * the source was only partially removed. Undeletable files are logged individually to
     * aid debugging.</p>
     *
     * @param target the file or directory to delete
     * @return {@code true} if the entire tree was successfully deleted (or didn't exist),
     *         {@code false} if any file or directory could not be deleted
     */
    private static boolean deleteRecursive(File target) {
        if (!target.exists()) return true;

        boolean allDeleted = true;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) {
                        allDeleted = false;
                        // Continue trying to delete other children — partial cleanup is
                        // better than no cleanup. But we track the overall result.
                    }
                }
            }
        }
        if (!target.delete()) {
            Log.w(TAG, "deleteRecursive: could not delete: " + target.getAbsolutePath());
            allDeleted = false;
        }
        return allDeleted;
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
