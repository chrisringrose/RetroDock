/*
 * Copyright (c) 2026 Gavamedia (https://gavamedia.com)
 * Licensed under the MIT License. See LICENSE file in the project root.
 */
package com.gavamedia.retrodock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * Activity that dynamically builds the entire emulator profile configuration UI in code.
 *
 * <p>RetroDock automatically swaps emulator settings files when the device is docked or
 * undocked. This Activity provides the user interface for configuring which emulators
 * participate in profile switching, where their settings files live on disk, and
 * optional "hot-apply" features that push shader/filter changes into running emulators
 * without requiring a restart.</p>
 *
 * <h3>UI Structure (top to bottom)</h3>
 * <ol>
 *   <li><b>Master Toggle</b> &mdash; enables/disables the entire profile-switching feature
 *       globally. When off, the emulator list is dimmed and non-interactive.</li>
 *   <li><b>Emulator Rows</b> &mdash; one section per detected (or custom) emulator:
 *     <ul>
 *       <li>Header row: display name + per-emulator enable/disable toggle</li>
 *       <li>Settings file rows: one row per config file, showing the resolved absolute
 *           path with colour-coded status (green = found, orange = path set but missing,
 *           red = completely unresolvable) and a {@code [...]} browse button</li>
 *       <li>Profile status line: indicates which profile (handheld/docked) is active,
 *           or the initial classification if no swap has happened yet</li>
 *       <li>Hot-apply section (RetroArch, DuckStation, ScummVM, PPSSPP only):
 *           shader/filter configuration that can be applied while the emulator runs</li>
 *     </ul>
 *   </li>
 *   <li><b>"Add Custom Emulator" button</b> &mdash; lets the user manually register an
 *       emulator that RetroDock does not detect automatically.</li>
 * </ol>
 *
 * <h3>SharedPreferences Key Conventions ({@code "retrodock_prefs"})</h3>
 * <table>
 *   <tr><td>{@code profile_switch_enabled}</td>
 *       <td>Master toggle (boolean)</td></tr>
 *   <tr><td>{@code emu_{id}_enabled}</td>
 *       <td>Per-emulator toggle (boolean)</td></tr>
 *   <tr><td>{@code emu_{id}_classified}</td>
 *       <td>Initial classification before first swap: "handheld" or "docked" (String)</td></tr>
 *   <tr><td>{@code emu_{id}_override_{relPath}}</td>
 *       <td>User-overridden absolute path for a specific settings file.
 *           Forward slashes in {@code relPath} are replaced with underscores.</td></tr>
 *   <tr><td>{@code retroarch_hot_apply_enabled}</td>
 *       <td>RetroArch live-shader-swap toggle (boolean). Shader presets are
 *           auto-discovered from the backup config directory.</td></tr>
 *   <tr><td>{@code duckstation_hot_apply_enabled}</td>
 *       <td>DuckStation live-shader-swap toggle (boolean)</td></tr>
 *   <tr><td>{@code scummvm_hot_apply_enabled}</td>
 *       <td>ScummVM live-filter-swap toggle (boolean)</td></tr>
 *   <tr><td>{@code ppsspp_hot_apply_enabled}</td>
 *       <td>PPSSPP live-shader-edit toggle (boolean)</td></tr>
 * </table>
 *
 * <h3>First-Time Enable Flow</h3>
 * <ol>
 *   <li>User toggles an emulator ON.</li>
 *   <li>App checks if any of its settings files can be found on disk.</li>
 *   <li>If no existing {@code .docked}/{@code .handheld} profile backups exist, the user
 *       is asked to classify the current settings as "handheld" or "docked". The
 *       classification is stored in {@code emu_{id}_classified} and used on the first
 *       dock/undock event to know which backup name to save under.</li>
 *   <li>If profile backups from a previous session are detected, the emulator is
 *       silently re-enabled without prompting.</li>
 * </ol>
 */
public class EmulatorSettingsActivity extends Activity {

    // ═══════════════════════════════════════════════════════════════════════
    //  Constants & Fields
    // ═══════════════════════════════════════════════════════════════════════

    /** SharedPreferences file name shared across the app. */
    private static final String PREFS_NAME = "retrodock_prefs";

    /** Handle to the app-wide SharedPreferences store. */
    private SharedPreferences prefs;

    /**
     * Scrollable vertical container that holds every emulator row plus the
     * "Add Custom Emulator" button. Defined in {@code activity_emulator_settings.xml}.
     */
    private LinearLayout emulatorList;

    /**
     * Global on/off switch for the entire profile-switching feature.
     * Persisted as {@code "profile_switch_enabled"} in SharedPreferences.
     */
    private Switch masterToggle;

    // ═══════════════════════════════════════════════════════════════════════
    //  Activity Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Initialises the activity, wires the master toggle, requests storage permissions
     * if needed, and builds the full emulator list UI.
     *
     * @param savedInstanceState standard Android saved-state bundle (unused)
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emulator_settings);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        masterToggle = findViewById(R.id.master_toggle);
        emulatorList = findViewById(R.id.emulator_list);

        // ── MASTER TOGGLE ──────────────────────────────────────────────
        // Reads and writes the "profile_switch_enabled" preference.
        // When toggled off, all emulator rows are visually dimmed and disabled.
        masterToggle.setChecked(prefs.getBoolean("profile_switch_enabled", false));
        masterToggle.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("profile_switch_enabled", checked).apply();
            updateListEnabled(checked);
        });

        checkStoragePermission();
        buildEmulatorRows();
        updateListEnabled(masterToggle.isChecked());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Storage Permission
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * On Android 11+ (API 30, MANAGE_EXTERNAL_STORAGE), checks whether the app has
     * "All Files Access". If not, shows a Toast and launches the system settings page
     * so the user can grant it. This is required because emulator config files live
     * in shared storage (e.g. {@code /storage/emulated/0/RetroArch/}).
     */
    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this,
                        "Storage access needed to swap emulator config folders",
                        Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  EMULATOR LIST: Top-level Builder
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Clears and rebuilds the entire emulator list from scratch.
     *
     * <p>Queries {@link EmulatorConfig#getInstalled(android.content.Context)} for every
     * known + custom emulator, creates a row for each, and appends the
     * "Add Custom Emulator" button at the bottom. Called once during {@code onCreate}
     * and again whenever a custom emulator is added (so the list refreshes).</p>
     */
    private void buildEmulatorRows() {
        emulatorList.removeAllViews();

        List<EmulatorConfig> emulators = EmulatorConfig.getInstalled(this);

        // If no emulators are detected, show a helpful hint instead of an empty screen.
        if (emulators.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No known emulators detected. Tap \"Add Custom Emulator\" below to add one manually.");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setTextColor(0xFF888888);
            empty.setPadding(0, 0, 0, dp(16));
            emulatorList.addView(empty);
        }

        // Build one UI section per installed emulator.
        for (EmulatorConfig emu : emulators) {
            addEmulatorRow(emu);
        }

        // ── "ADD CUSTOM EMULATOR" BUTTON ───────────────────────────────
        // Sits at the bottom of the list, always visible.
        Button addCustomBtn = new Button(this);
        addCustomBtn.setText("+ Add Custom Emulator");
        addCustomBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        addCustomBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        btnParams.topMargin = dp(8);
        addCustomBtn.setLayoutParams(btnParams);
        addCustomBtn.setOnClickListener(v -> showAddCustomDialog());
        emulatorList.addView(addCustomBtn);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  EMULATOR ROW: Per-Emulator Section
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Builds the complete UI block for a single emulator and appends it to
     * {@link #emulatorList}.
     *
     * <p>The block contains (in order):
     * <ol>
     *   <li>Header row &mdash; emulator display name (left) + per-emulator toggle (right).
     *       The toggle writes to {@code "emu_{id}_enabled"}.</li>
     *   <li>Settings file rows &mdash; one per entry in {@link EmulatorConfig#settingsFiles},
     *       each showing the resolved path and a browse button.</li>
     *   <li>Profile status label &mdash; shows which profile is active.</li>
     *   <li>Hot-apply section (conditional) &mdash; shader/filter controls for emulators
     *       that support live config changes.</li>
     *   <li>Divider line &mdash; thin horizontal rule separating this emulator from the next.</li>
     * </ol>
     *
     * @param emu the emulator configuration to render
     */
    private void addEmulatorRow(EmulatorConfig emu) {
        // ── Outer container (vertical stack for this emulator) ─────────
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, 0, 0, dp(16));
        container.setTag(emu.id); // Tag for programmatic lookup if needed

        // ── Header row: emulator name + per-emulator enable toggle ─────
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        // Emulator display name (takes all available horizontal space via weight=1)
        TextView nameText = new TextView(this);
        nameText.setText(emu.displayName);
        nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        nameText.setTextColor(0xFFE0E0E0);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameText.setLayoutParams(nameParams);

        // ── Profile status label (declared early) ──────────────────────
        // Created before the toggle so the toggle's change listener can update it
        // immediately when the user enables or classifies the emulator.
        TextView statusText = new TextView(this);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusText.setPadding(0, dp(2), 0, 0);

        // ── Per-emulator enable/disable toggle ─────────────────────────
        // Reads from / writes to "emu_{id}_enabled".
        // When toggled ON, triggers the first-time-enable flow (see onEmulatorEnabled).
        // When toggled OFF, simply writes false to the preference.
        Switch toggle = new Switch(this);
        toggle.setChecked(prefs.getBoolean("emu_" + emu.id + "_enabled", false));
        toggle.setOnCheckedChangeListener((v, checked) -> {
            if (checked) {
                onEmulatorEnabled(emu, toggle, statusText);
            } else {
                prefs.edit().putBoolean("emu_" + emu.id + "_enabled", false).apply();
            }
        });

        headerRow.addView(nameText);
        headerRow.addView(toggle);

        // ── Settings file rows ─────────────────────────────────────────
        // One row per settings file (e.g. "retroarch.cfg", "config/retroarch-core-options.cfg").
        // Each row shows the resolved absolute path with colour-coded existence status
        // and a [...] browse button to override the auto-detected path.
        LinearLayout settingsContainer = new LinearLayout(this);
        settingsContainer.setOrientation(LinearLayout.VERTICAL);
        settingsContainer.setPadding(0, dp(4), 0, 0);

        if (emu.settingsFiles != null && emu.settingsFiles.length > 0) {
            for (String relPath : emu.settingsFiles) {
                addSettingsFileRow(settingsContainer, emu, relPath, statusText);
            }
        }

        // Compute and display the current profile status (handheld / docked / unclassified).
        updateProfileStatus(statusText, emu);

        container.addView(headerRow);
        container.addView(settingsContainer);
        container.addView(statusText);

        // ── Hot-apply section (emulator-specific) ──────────────────────
        // Only certain emulators support pushing shader/filter changes into
        // a running process. Each gets its own builder method.
        switch (emu.id) {
            case "retroarch":
                container.addView(buildRetroArchHotApplySection());
                break;
            case "duckstation":
                container.addView(buildDuckStationHotApplySection());
                break;
            case "scummvm":
                container.addView(buildScummVMHotApplySection());
                break;
            case "ppsspp":
                container.addView(buildPPSSPPHotApplySection());
                break;
        }

        // ── Divider ────────────────────────────────────────────────────
        // Thin horizontal line to visually separate emulator sections.
        View divider = new View(this);
        divider.setBackgroundColor(0xFF333355);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divParams.topMargin = dp(12);
        divider.setLayoutParams(divParams);
        container.addView(divider);

        emulatorList.addView(container);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SETTINGS FILE ROW: Path Display + Browse Button
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Adds a single settings-file row to the given parent layout.
     *
     * <p>Each row displays the resolved absolute path of a settings file (or folder)
     * with colour-coded status:
     * <ul>
     *   <li><b>Green</b> ({@code 0xFF4CAF50}) &mdash; file/directory exists on disk</li>
     *   <li><b>Orange</b> ({@code 0xFFFF9800}) &mdash; a path was resolved (or overridden)
     *       but the file does not currently exist</li>
     *   <li><b>Red</b> ({@code 0xFFFF5252}) &mdash; the file could not be resolved at all
     *       across any of the emulator's default search paths</li>
     * </ul>
     *
     * <p>The {@code [...]} browse button opens a dialog where the user can type or paste
     * an absolute path. The override is stored under the preference key
     * {@code "emu_{id}_override_{sanitisedRelPath}"}, where forward slashes in
     * {@code relPath} are replaced with underscores to keep the key flat.</p>
     *
     * @param parent     the LinearLayout to append this row to
     * @param emu        the emulator this file belongs to
     * @param relPath    relative path of the settings file (e.g. "retroarch.cfg")
     * @param statusText the profile-status TextView to refresh after a path change
     */
    private void addSettingsFileRow(LinearLayout parent, EmulatorConfig emu,
                                    String relPath, TextView statusText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));

        // ── Path display label ─────────────────────────────────────────
        // Shows the resolved absolute path (or the raw relPath if unresolvable).
        // Uses monospace-style rendering for readability.
        TextView pathText = new TextView(this);
        pathText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pathText.setFontFeatureSettings("monospace");
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pathText.setLayoutParams(pathParams);

        // Build the override preference key: "emu_{id}_override_{relPath}"
        // Slashes in relPath are replaced with underscores to keep the key flat.
        String overrideKey = "emu_" + emu.id + "_override_" + relPath.replace("/", "_");
        // Use the same preference-aware resolver as the swap engine so the path displayed in the
        // UI is the path RetroDock will actually operate on. The old UI duplicated its own
        // override logic here while the swap engine continued to use defaultPaths only, which
        // could make the screen show one file and the background service rename another.
        String resolved = ProfileSwitcher.findSettingsFileForDisplay(prefs, emu, relPath);

        // Colour-code the path based on file existence:
        //   Green  = exists on disk
        //   Orange = resolved but missing (e.g. user override to a path that doesn't exist yet)
        //   Red    = could not be resolved at all
        if (resolved != null && new File(resolved).exists()) {
            File f = new File(resolved);
            pathText.setText(resolved + (f.isDirectory() ? " (dir)" : ""));
            pathText.setTextColor(0xFF4CAF50); // Green: found
        } else if (resolved != null) {
            pathText.setText(resolved + " (not found)");
            pathText.setTextColor(0xFFFF9800); // Orange: resolved but missing
        } else {
            pathText.setText(relPath + " (not found)");
            pathText.setTextColor(0xFFFF5252); // Red: completely unresolvable
        }

        // ── Browse/override button [...] ───────────────────────────────
        // Opens an AlertDialog with an EditText pre-filled with the current resolved
        // path. The user can type a new absolute path, save it (stored as an override),
        // or reset to auto-detection.
        Button browseBtn = new Button(this);
        browseBtn.setText("...");
        browseBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        browseBtn.setTextColor(0xFFFFFFFF);
        browseBtn.setMinimumWidth(0);
        browseBtn.setMinWidth(0);
        LinearLayout.LayoutParams browseParams = new LinearLayout.LayoutParams(dp(40), dp(30));
        browseParams.setMarginStart(dp(4));
        browseBtn.setLayoutParams(browseParams);
        browseBtn.setPadding(0, 0, 0, 0);

        browseBtn.setOnClickListener(v -> {
            // Build the edit dialog for manual path entry.
            EditText input = new EditText(this);
            input.setTextColor(0xFFE0E0E0);
            input.setHintTextColor(0xFF555555);
            input.setBackgroundColor(0xFF2D2D44);
            input.setPadding(dp(12), dp(8), dp(12), dp(8));
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

            // Pre-fill with the currently configured path. If the user already supplied an
            // override, show that exact value; otherwise show the auto-detected path. This keeps
            // the dialog aligned with the actual path the swap engine will use.
            String currentResolved = ProfileSwitcher.findSettingsFileForDisplay(prefs, emu, relPath);
            input.setText(currentResolved != null ? currentResolved : "");
            input.setHint("Full path to " + relPath);

            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle("Path for " + relPath)
                    .setMessage("Enter the full path to this settings file/folder:")
                    .setView(input)
                    .setPositiveButton("Save", (dialog, which) -> {
                        // Store the user's override under "emu_{id}_override_{relPath}".
                        String newPath = input.getText().toString().trim();

                        // Basic path validation to reject obviously invalid entries.
                        // - Empty paths are silently ignored (treated as "no override").
                        // - Paths containing ".." are rejected to prevent path-traversal
                        //   attacks where a malicious path could escape the expected
                        //   config directory (e.g. "../../system/etc/hosts").
                        if (newPath.isEmpty()) {
                            Toast.makeText(this, "Path is empty — override not saved", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!newPath.startsWith("/")) {
                            Toast.makeText(this, "Invalid path: use an absolute path", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (newPath.contains("..")) {
                            Toast.makeText(this, "Invalid path: \"..\" is not allowed (path traversal)", Toast.LENGTH_LONG).show();
                            return;
                        }

                        prefs.edit().putString(overrideKey, newPath).apply();
                        // Update the display colour based on whether the new path exists.
                        if (!newPath.isEmpty() && new File(newPath).exists()) {
                            File f = new File(newPath);
                            pathText.setText(newPath + (f.isDirectory() ? " (dir)" : ""));
                            pathText.setTextColor(0xFF4CAF50); // Green
                        } else if (!newPath.isEmpty()) {
                            pathText.setText(newPath + " (not found)");
                            pathText.setTextColor(0xFFFF9800); // Orange
                        }
                        // Refresh profile status since path availability may have changed.
                        updateProfileStatus(statusText, emu);
                    })
                    .setNeutralButton("Reset", (dialog, which) -> {
                        // Remove the override so auto-detection takes over again.
                        prefs.edit().remove(overrideKey).apply();
                        String auto = ProfileSwitcher.findSettingsFileForDisplay(prefs, emu, relPath);
                        if (auto != null && new File(auto).exists()) {
                            File f = new File(auto);
                            pathText.setText(auto + (f.isDirectory() ? " (dir)" : ""));
                            pathText.setTextColor(0xFF4CAF50); // Green
                        } else {
                            pathText.setText(relPath + " (not found)");
                            pathText.setTextColor(0xFFFF5252); // Red
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        row.addView(pathText);
        row.addView(browseBtn);
        parent.addView(row);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  FIRST-TIME ENABLE FLOW
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handles the first-time (or re-) enable of an emulator toggle.
     *
     * <p>The flow has three branches:
     * <ol>
     *   <li><b>No settings files found at all</b> &mdash; the emulator is enabled anyway
     *       (so the preference is persisted), but a Toast tells the user to run the
     *       emulator at least once so its config files are created.</li>
     *   <li><b>Existing {@code .docked} and/or {@code .handheld} profile backups found</b>
     *       &mdash; the emulator was previously configured with RetroDock. It is silently
     *       re-enabled without prompting. A Toast summarises what was found.</li>
     *   <li><b>Settings files exist but no profile backups</b> &mdash; this is a
     *       first-time setup. An AlertDialog asks the user to classify the current
     *       settings as "handheld" or "docked". The choice is stored in
     *       {@code "emu_{id}_classified"} so that on the first dock/undock event,
     *       ProfileSwitcher knows which backup name to save the current files under.</li>
     * </ol>
     *
     * @param emu        the emulator being enabled
     * @param toggle     the Switch widget, so it can be unchecked if the user cancels
     * @param statusText the profile-status label to refresh after classification
     */
    private void onEmulatorEnabled(EmulatorConfig emu, Switch toggle, TextView statusText) {
        // Custom emulators must provide a package name so RetroDock can prove the emulator is
        // closed before touching its config files. Without that identity, automatic swapping is
        // unsafe because a live emulator can overwrite the files we just renamed when it exits.
        if (!ProfileSwitcher.hasProcessDetectionIdentity(emu)) {
            Toast.makeText(this,
                    emu.displayName + ": package name is required for safe profile switching.",
                    Toast.LENGTH_LONG).show();
            toggle.setChecked(false);
            return;
        }

        // Scan all root paths to determine what exists on disk.
        boolean anySettingsFound = false;
        boolean hasDocked = false;
        boolean hasHandheld = false;

        String[] roots = emu.defaultPaths;
        if (emu.settingsFiles != null && roots != null) {
            for (String relPath : emu.settingsFiles) {
                String resolved = ProfileSwitcher.findSettingsFile(prefs, emu, relPath);
                if (resolved != null) {
                    anySettingsFound = true;
                    // Check for existing profile backups alongside the settings file.
                    // ProfileSwitcher stores backups as "{original}.docked" / "{original}.handheld".
                    if (new File(resolved + ".docked").exists()) hasDocked = true;
                    if (new File(resolved + ".handheld").exists()) hasHandheld = true;
                }
            }
        }

        // ── Branch 1: no settings files found ──────────────────────────
        // The emulator has probably never been run. Enable it (so the pref is saved)
        // but warn the user.
        if (!anySettingsFound) {
            prefs.edit().putBoolean("emu_" + emu.id + "_enabled", true).apply();
            Toast.makeText(this, emu.displayName + ": no settings files found yet. " +
                    "Run the emulator first, then re-enable.", Toast.LENGTH_LONG).show();
            return;
        }

        // ── Branch 2: previous profile backups found ───────────────────
        // The user had RetroDock set up before. Re-enable seamlessly.
        if (hasDocked || hasHandheld) {
            prefs.edit().putBoolean("emu_" + emu.id + "_enabled", true).apply();
            String msg;
            if (hasDocked && hasHandheld) {
                msg = "Found both docked and handheld profiles from before.";
            } else if (hasDocked) {
                // .docked backup exists => the live file is the handheld version
                msg = "Found existing profiles. Currently using handheld settings.";
            } else {
                // .handheld backup exists => the live file is the docked version
                msg = "Found existing profiles. Currently using docked settings.";
            }
            Toast.makeText(this, emu.displayName + ": " + msg, Toast.LENGTH_LONG).show();
            updateProfileStatus(statusText, emu);
            return;
        }

        // ── Branch 3: first-time classification dialog ─────────────────
        // Settings files exist but no .docked/.handheld backups.
        // Ask the user whether the current settings are for handheld or docked play.
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle(emu.displayName + " — Classify Settings")
                .setMessage("No docked/handheld profiles found. Your current " + emu.displayName
                        + " settings will be used as a starting point.

"
                        + "Are your current settings configured for handheld or docked play?")
                .setPositiveButton("Handheld", (dialog, which) -> {
                    // Store "emu_{id}_enabled" = true and "emu_{id}_classified" = "handheld".
                    // On the first dock event, ProfileSwitcher will save the current
                    // settings as .handheld and let the user configure docked settings.
                    prefs.edit()
                            .putBoolean("emu_" + emu.id + "_enabled", true)
                            .putString("emu_" + emu.id + "_classified", "handheld")
                            .apply();
                    Toast.makeText(this,
                            "Current settings marked as handheld. On first dock, they'll be saved and you can configure docked settings.",
                            Toast.LENGTH_LONG).show();
                    updateProfileStatus(statusText, emu);
                })
                .setNegativeButton("Docked", (dialog, which) -> {
                    // Store "emu_{id}_enabled" = true and "emu_{id}_classified" = "docked".
                    // On the first undock event, ProfileSwitcher will save the current
                    // settings as .docked and let the user configure handheld settings.
                    prefs.edit()
                            .putBoolean("emu_" + emu.id + "_enabled", true)
                            .putString("emu_" + emu.id + "_classified", "docked")
                            .apply();
                    Toast.makeText(this,
                            "Current settings marked as docked. On first undock, they'll be saved and you can configure handheld settings.",
                            Toast.LENGTH_LONG).show();
                    updateProfileStatus(statusText, emu);
                })
                .setNeutralButton("Cancel", (dialog, which) -> {
                    // User backed out; revert the toggle to OFF.
                    toggle.setChecked(false);
                })
                .setCancelable(false)
                .show();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ADD CUSTOM EMULATOR Dialog
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Shows a dialog that lets the user register a custom emulator that RetroDock
     * does not auto-detect.
     *
     * <p>The dialog collects four fields:
     * <ul>
     *   <li><b>Name</b> (required) &mdash; human-readable display name</li>
     *   <li><b>Package name</b> (required) &mdash; Android package for process detection</li>
     *   <li><b>Config folder path</b> &mdash; absolute path to the emulator's config directory</li>
     *   <li><b>Settings file/folder name</b> &mdash; relative name inside the config folder</li>
     * </ul>
     *
     * <p>On "Add", delegates to {@link EmulatorConfig#addCustomEntry} to persist the
     * entry, then rebuilds the full emulator list so the new entry appears immediately.</p>
     */
    private void showAddCustomDialog() {
        // Build the form layout programmatically (vertical stack of label + input pairs).
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), dp(8));

        // ── Name field ─────────────────────────────────────────────────
        TextView nameLabel = new TextView(this);
        nameLabel.setText("Name");
        nameLabel.setTextColor(0xFFBBBBBB);
        nameLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        form.addView(nameLabel);

        EditText nameInput = new EditText(this);
        nameInput.setHint("e.g. MyEmulator");
        nameInput.setTextColor(0xFFE0E0E0);
        nameInput.setHintTextColor(0xFF555555);
        nameInput.setBackgroundColor(0xFF2D2D44);
        nameInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        form.addView(nameInput);

        // ── Package name field (required) ──────────────────────────────
        TextView pkgLabel = new TextView(this);
        pkgLabel.setText("Package name (required, for safe process detection)");
        pkgLabel.setTextColor(0xFFBBBBBB);
        pkgLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pkgLabel.setPadding(0, dp(12), 0, 0);
        form.addView(pkgLabel);

        EditText pkgInput = new EditText(this);
        pkgInput.setHint("e.g. com.example.emulator");
        pkgInput.setTextColor(0xFFE0E0E0);
        pkgInput.setHintTextColor(0xFF555555);
        pkgInput.setBackgroundColor(0xFF2D2D44);
        pkgInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        form.addView(pkgInput);

        // ── Config folder path field ───────────────────────────────────
        TextView pathLabel = new TextView(this);
        pathLabel.setText("Config folder path");
        pathLabel.setTextColor(0xFFBBBBBB);
        pathLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pathLabel.setPadding(0, dp(12), 0, 0);
        form.addView(pathLabel);

        EditText pathInput = new EditText(this);
        pathInput.setHint("/storage/emulated/0/...");
        pathInput.setTextColor(0xFFE0E0E0);
        pathInput.setHintTextColor(0xFF555555);
        pathInput.setBackgroundColor(0xFF2D2D44);
        pathInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        form.addView(pathInput);

        // ── Settings file/folder name field ────────────────────────────
        TextView settingsLabel = new TextView(this);
        settingsLabel.setText("Settings file/folder name (relative to config path)");
        settingsLabel.setTextColor(0xFFBBBBBB);
        settingsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        settingsLabel.setPadding(0, dp(12), 0, 0);
        form.addView(settingsLabel);

        EditText settingsInput = new EditText(this);
        settingsInput.setHint("e.g. settings.ini or Config");
        settingsInput.setTextColor(0xFFE0E0E0);
        settingsInput.setHintTextColor(0xFF555555);
        settingsInput.setBackgroundColor(0xFF2D2D44);
        settingsInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        form.addView(settingsInput);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("Add Custom Emulator")
                .setView(form)
                .setPositiveButton("Add", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String pkg = pkgInput.getText().toString().trim();
                    if (pkg.isEmpty()) {
                        Toast.makeText(this,
                                "Package name is required so RetroDock can avoid swapping a live emulator.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    String path = pathInput.getText().toString().trim();
                    String settings = settingsInput.getText().toString().trim();
                    if (!path.isEmpty() && !path.startsWith("/")) {
                        Toast.makeText(this, "Config folder path must be absolute", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (path.contains("..") || settings.contains("..")) {
                        Toast.makeText(this, "Parent-directory segments (..) are not allowed", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (settings.startsWith("/")) {
                        Toast.makeText(this, "Settings file/folder name must be relative to the config path", Toast.LENGTH_LONG).show();
                        return;
                    }
                    EmulatorConfig.addCustomEntry(this, name, pkg, path, settings);

                    // Rebuild the entire list so the new entry appears.
                    buildEmulatorRows();
                    updateListEnabled(masterToggle.isChecked());
                    Toast.makeText(this, name + " added", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HOT-APPLY SECTION: RetroArch
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Builds the RetroArch-specific "Live Shader Swap" hot-apply section.
     *
     * <p>RetroArch supports receiving shader commands over UDP (network commands),
     * allowing RetroDock to push shader preset changes into a running RetroArch
     * instance without restarting it.</p>
     *
     * <p>Shader presets are auto-discovered from the backup config directory
     * ({@code config.docked/} or {@code config.handheld/}), so no manual path
     * configuration is needed.</p>
     *
     * <p>UI elements:
     * <ul>
     *   <li>Sub-header and info text explaining the feature and prerequisite
     *       (RetroArch &gt; Settings &gt; Network &gt; Network Commands = ON)</li>
     *   <li>Enable toggle &mdash; persisted as {@code "retroarch_hot_apply_enabled"}</li>
     * </ul>
     *
     * @return the fully constructed LinearLayout for this section
     */
    private LinearLayout buildRetroArchHotApplySection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(8), 0, 0);

        // ── Sub-header ─────────────────────────────────────────────────
        TextView header = new TextView(this);
        header.setText("Live Shader Swap (no restart needed)");
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTextColor(0xFF9090C0); // Muted purple to distinguish from main labels
        header.setPadding(0, dp(4), 0, dp(2));
        section.addView(header);

        // ── Info text ──────────────────────────────────────────────────
        TextView info = new TextView(this);
        info.setText("Sends shader commands to RetroArch via UDP while running.
" +
                "Auto-detects the global shader preset from your docked/handheld config directory.
" +
                "Requires: RetroArch > Settings > Network > Network Commands = ON");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        info.setTextColor(0xFF666688);
        info.setPadding(0, 0, 0, dp(4));
        section.addView(info);

        // ── Enable toggle ──────────────────────────────────────────────
        // Preference key: "retroarch_hot_apply_enabled"
        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView toggleLabel = new TextView(this);
        toggleLabel.setText("Enable live shader swap");
        toggleLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        toggleLabel.setTextColor(0xFFCCCCCC);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        toggleLabel.setLayoutParams(labelParams);

        Switch hotToggle = new Switch(this);
        hotToggle.setChecked(prefs.getBoolean("retroarch_hot_apply_enabled", false));
        hotToggle.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("retroarch_hot_apply_enabled", checked).apply();
        });

        toggleRow.addView(toggleLabel);
        toggleRow.addView(hotToggle);
        section.addView(toggleRow);

        return section;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HOT-APPLY SECTION: DuckStation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Builds the DuckStation-specific "Live Shader Swap" hot-apply section.
     *
     * <p>DuckStation stores post-processing shader chain names in its
     * {@code settings.ini}. RetroDock can edit the {@code PostProcessing} value while
     * DuckStation is running (experimental &mdash; takes effect on next frame or
     * game load depending on the version).</p>
     *
     * <p>UI elements:
     * <ul>
     *   <li>Enable toggle &mdash; {@code "duckstation_hot_apply_enabled"}</li>
     *   <li>Handheld shader chain &mdash; {@code "duckstation_shaders_handheld"}</li>
     *   <li>Docked shader chain &mdash; {@code "duckstation_shaders_docked"}</li>
     * </ul>
     *
     * @return the fully constructed LinearLayout for this section
     */
    private LinearLayout buildDuckStationHotApplySection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(8), 0, 0);

        // ── Sub-header ─────────────────────────────────────────────────
        TextView header = new TextView(this);
        header.setText("Live Shader Swap (experimental)");
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTextColor(0xFF9090C0);
        header.setPadding(0, dp(4), 0, dp(2));
        section.addView(header);

        // ── Info text ──────────────────────────────────────────────────
        TextView info = new TextView(this);
        info.setText("Edits PostProcessing in settings.ini while running.
" +
                "Shader names are comma-separated (e.g. CRT-Royale,Scanlines).
" +
                "Leave empty to disable shaders for that mode.");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        info.setTextColor(0xFF666688);
        info.setPadding(0, 0, 0, dp(4));
        section.addView(info);

        // ── Enable toggle ──────────────────────────────────────────────
        // Preference key: "duckstation_hot_apply_enabled"
        section.addView(buildHotApplyToggle("Enable live shader swap",
                "duckstation_hot_apply_enabled"));

        // ── Shader chain text settings ─────────────────────────────────
        // Comma-separated shader names for each mode.
        section.addView(buildTextSettingRow("Handheld shaders:",
                "duckstation_shaders_handheld",
                "e.g. CRT-Royale (comma-separated)"));

        section.addView(buildTextSettingRow("Docked shaders:",
                "duckstation_shaders_docked",
                "e.g. (empty for no shaders)"));

        return section;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HOT-APPLY SECTION: ScummVM
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Builds the ScummVM-specific "Live Filter Swap" hot-apply section.
     *
     * <p>ScummVM stores its scaler/filter preference in {@code scummvm.ini}. RetroDock
     * modifies the scaler name and optionally the bilinear filtering flag, then sends
     * a {@code Ctrl+Alt+S} keystroke to trigger ScummVM to reload its filter settings
     * (experimental).</p>
     *
     * <p>UI elements:
     * <ul>
     *   <li>Enable toggle &mdash; {@code "scummvm_hot_apply_enabled"}</li>
     *   <li>Handheld scaler name &mdash; {@code "scummvm_scaler_handheld"}</li>
     *   <li>Docked scaler name &mdash; {@code "scummvm_scaler_docked"}</li>
     *   <li>Handheld bilinear filtering toggle &mdash; {@code "scummvm_filtering_handheld"}</li>
     *   <li>Docked bilinear filtering toggle &mdash; {@code "scummvm_filtering_docked"}</li>
     * </ul>
     *
     * @return the fully constructed LinearLayout for this section
     */
    private LinearLayout buildScummVMHotApplySection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(8), 0, 0);

        // ── Sub-header ─────────────────────────────────────────────────
        TextView header = new TextView(this);
        header.setText("Live Filter Swap (experimental)");
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTextColor(0xFF9090C0);
        header.setPadding(0, dp(4), 0, dp(2));
        section.addView(header);

        // ── Info text ──────────────────────────────────────────────────
        TextView info = new TextView(this);
        info.setText("Modifies scaler/filter in scummvm.ini and sends Ctrl+Alt+S to cycle.
" +
                "Scalers: normal, hq2x, hq3x, 2xsai, super2xsai, supereagle, advmame2x, advmame3x, tv2x, dotmatrix");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        info.setTextColor(0xFF666688);
        info.setPadding(0, 0, 0, dp(4));
        section.addView(info);

        // ── Enable toggle ──────────────────────────────────────────────
        // Preference key: "scummvm_hot_apply_enabled"
        section.addView(buildHotApplyToggle("Enable live filter swap",
                "scummvm_hot_apply_enabled"));

        // ── Scaler name text settings ──────────────────────────────────
        section.addView(buildTextSettingRow("Handheld scaler:",
                "scummvm_scaler_handheld", "e.g. hq2x"));

        section.addView(buildTextSettingRow("Docked scaler:",
                "scummvm_scaler_docked", "e.g. normal"));

        // ── Bilinear filtering boolean toggles ─────────────────────────
        section.addView(buildBoolSettingRow("Handheld bilinear filtering",
                "scummvm_filtering_handheld"));
        section.addView(buildBoolSettingRow("Docked bilinear filtering",
                "scummvm_filtering_docked"));

        return section;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HOT-APPLY SECTION: PPSSPP
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Builds the PPSSPP-specific "Live Shader Swap" hot-apply section.
     *
     * <p>PPSSPP stores its post-processing shader name in {@code ppsspp.ini} under
     * the {@code PostProcessingShader} key. RetroDock edits this value on dock/undock.
     * Unlike RetroArch, the change only takes effect on the next game load, not
     * mid-game.</p>
     *
     * <p>UI elements:
     * <ul>
     *   <li>Enable toggle &mdash; {@code "ppsspp_hot_apply_enabled"}</li>
     *   <li>Handheld shader name &mdash; {@code "ppsspp_shader_handheld"}</li>
     *   <li>Docked shader name &mdash; {@code "ppsspp_shader_docked"}</li>
     * </ul>
     *
     * @return the fully constructed LinearLayout for this section
     */
    private LinearLayout buildPPSSPPHotApplySection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(8), 0, 0);

        // ── Sub-header ─────────────────────────────────────────────────
        TextView header = new TextView(this);
        header.setText("Live Shader Swap (partial)");
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTextColor(0xFF9090C0);
        header.setPadding(0, dp(4), 0, dp(2));
        section.addView(header);

        // ── Info text ──────────────────────────────────────────────────
        TextView info = new TextView(this);
        info.setText("Edits PostProcessingShader in ppsspp.ini.
" +
                "Takes effect on next game load (not mid-game).
" +
                "Shader name must match PPSSPP's internal name (e.g. CRT-Color).");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        info.setTextColor(0xFF666688);
        info.setPadding(0, 0, 0, dp(4));
        section.addView(info);

        // ── Enable toggle ──────────────────────────────────────────────
        // Preference key: "ppsspp_hot_apply_enabled"
        section.addView(buildHotApplyToggle("Enable shader edit on dock/undock",
                "ppsspp_hot_apply_enabled"));

        // ── Shader name text settings ──────────────────────────────────
        section.addView(buildTextSettingRow("Handheld shader:",
                "ppsspp_shader_handheld", "e.g. CRT-Color"));

        section.addView(buildTextSettingRow("Docked shader:",
                "ppsspp_shader_docked", "e.g. Off"));

        return section;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SHARED HOT-APPLY UI HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Builds a reusable toggle row for hot-apply enable/disable switches.
     *
     * <p>Creates a horizontal row with a label on the left and a {@link Switch} on the
     * right. The switch reads its initial state from {@code prefKey} and writes back
     * on every change.</p>
     *
     * @param label   the human-readable label (e.g. "Enable live shader swap")
     * @param prefKey the SharedPreferences key for this boolean toggle
     * @return a horizontal LinearLayout containing the label and switch
     */
    private LinearLayout buildHotApplyToggle(String label, String prefKey) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));

        // Label (weighted to fill available space)
        TextView toggleLabel = new TextView(this);
        toggleLabel.setText(label);
        toggleLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        toggleLabel.setTextColor(0xFFCCCCCC);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        toggleLabel.setLayoutParams(labelParams);

        // Toggle switch (reads/writes prefKey)
        Switch toggle = new Switch(this);
        toggle.setChecked(prefs.getBoolean(prefKey, false));
        toggle.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean(prefKey, checked).apply();
        });

        row.addView(toggleLabel);
        row.addView(toggle);
        return row;
    }

    /**
     * Builds a reusable text-setting row with a label, current-value display, and a
     * {@code [...]} edit button that opens an input dialog.
     *
     * <p>Used for shader names, scaler names, and similar string preferences in the
     * hot-apply sections. The current value is shown in green if set, or grey
     * "(not set)" if empty.</p>
     *
     * @param label   the label text shown above the value (e.g. "Handheld shaders:")
     * @param prefKey the SharedPreferences key for this string value
     * @param hint    placeholder text for the edit dialog input
     * @return the constructed row layout
     */
    private LinearLayout buildTextSettingRow(String label, String prefKey, String hint) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(2), 0, 0);

        // Label above the value display
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        labelView.setTextColor(0xFF999999);
        container.addView(labelView);

        // Horizontal row: value display (weighted) + edit button (fixed)
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        String currentValue = prefs.getString(prefKey, "");

        // Value display with colour coding:
        //   Green = value is set
        //   Grey  = not set
        TextView display = new TextView(this);
        display.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams displayParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        display.setLayoutParams(displayParams);
        if (!currentValue.isEmpty()) {
            display.setText(currentValue);
            display.setTextColor(0xFF4CAF50); // Green: configured
        } else {
            display.setText("(not set)");
            display.setTextColor(0xFF888888); // Grey: unconfigured
        }

        // ── Edit button [...] ──────────────────────────────────────────
        Button editBtn = new Button(this);
        editBtn.setText("...");
        editBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        editBtn.setTextColor(0xFFFFFFFF);
        editBtn.setMinimumWidth(0);
        editBtn.setMinWidth(0);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(dp(40), dp(30));
        btnParams.setMarginStart(dp(4));
        editBtn.setLayoutParams(btnParams);
        editBtn.setPadding(0, 0, 0, 0);

        editBtn.setOnClickListener(v -> {
            EditText input = new EditText(this);
            input.setTextColor(0xFFE0E0E0);
            input.setHintTextColor(0xFF555555);
            input.setBackgroundColor(0xFF2D2D44);
            input.setPadding(dp(12), dp(8), dp(12), dp(8));
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            input.setText(prefs.getString(prefKey, ""));
            input.setHint(hint);

            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle(label)
                    .setView(input)
                    .setPositiveButton("Save", (dialog, which) -> {
                        String val = input.getText().toString().trim();
                        prefs.edit().putString(prefKey, val).apply();
                        if (!val.isEmpty()) {
                            display.setText(val);
                            display.setTextColor(0xFF4CAF50); // Green
                        } else {
                            display.setText("(not set)");
                            display.setTextColor(0xFF888888); // Grey
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        row.addView(display);
        row.addView(editBtn);
        container.addView(row);
        return container;
    }

    /**
     * Builds a reusable boolean-setting row with a label and a {@link Switch}.
     *
     * <p>Used for simple on/off preferences in hot-apply sections (e.g. ScummVM
     * bilinear filtering). Smaller text than {@link #buildHotApplyToggle} to
     * visually nest under the parent hot-apply section.</p>
     *
     * @param label   the human-readable label for this setting
     * @param prefKey the SharedPreferences key for this boolean value
     * @return the constructed row layout
     */
    private LinearLayout buildBoolSettingRow(String label, String prefKey) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));

        // Label (weighted, smaller text to indicate sub-setting)
        TextView toggleLabel = new TextView(this);
        toggleLabel.setText(label);
        toggleLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        toggleLabel.setTextColor(0xFF999999);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        toggleLabel.setLayoutParams(labelParams);

        // Toggle switch (reads/writes prefKey)
        Switch toggle = new Switch(this);
        toggle.setChecked(prefs.getBoolean(prefKey, false));
        toggle.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean(prefKey, checked).apply();
        });

        row.addView(toggleLabel);
        row.addView(toggle);
        return row;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PROFILE STATUS: Per-Emulator State Display
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Updates the profile-status label for a single emulator based on what backup
     * files exist on disk.
     *
     * <p>The logic determines the current state by scanning for {@code .docked} and
     * {@code .handheld} sidecar files next to each resolved settings file:
     * <ul>
     *   <li>Both {@code .docked} and {@code .handheld} exist &mdash; unexpected state,
     *       shown in orange as a warning</li>
     *   <li>Only {@code .docked} exists &mdash; the live file is the handheld version
     *       (the docked version is stored in the backup), shown in green</li>
     *   <li>Only {@code .handheld} exists &mdash; the live file is the docked version,
     *       shown in green</li>
     *   <li>Neither exists &mdash; falls back to the {@code "emu_{id}_classified"}
     *       preference to show the initial classification, or a generic "no profiles
     *       yet" message if unclassified. Shown in grey.</li>
     * </ul>
     *
     * @param statusText the TextView to update
     * @param emu        the emulator whose profile state to check
     */
    private void updateProfileStatus(TextView statusText, EmulatorConfig emu) {
        // Scan all root paths for .docked / .handheld sidecar backup files.
        boolean hasDocked = false;
        boolean hasHandheld = false;

        String[] roots = emu.defaultPaths;
        String[] settings = emu.settingsFiles;
        if (settings != null && roots != null) {
            for (String relPath : settings) {
                String resolved = ProfileSwitcher.findSettingsFile(prefs, emu, relPath);
                if (resolved != null) {
                    if (new File(resolved + ".docked").exists()) hasDocked = true;
                    if (new File(resolved + ".handheld").exists()) hasHandheld = true;
                }
            }
        }

        // Display the status with appropriate colour coding.
        if (hasDocked && hasHandheld) {
            // Both backups existing simultaneously is unexpected (should not happen
            // under normal operation). Warn the user.
            statusText.setText("Both docked and handheld profiles exist (unexpected)");
            statusText.setTextColor(0xFFFF9800); // Orange: warning
        } else if (hasDocked) {
            // .docked backup => live file = handheld settings
            statusText.setText("Currently using: handheld settings");
            statusText.setTextColor(0xFF4CAF50); // Green: normal operation
        } else if (hasHandheld) {
            // .handheld backup => live file = docked settings
            statusText.setText("Currently using: docked settings");
            statusText.setTextColor(0xFF4CAF50); // Green: normal operation
        } else {
            // No backups yet. Check the initial classification preference.
            String classified = prefs.getString("emu_" + emu.id + "_classified", "");
            if ("handheld".equals(classified)) {
                statusText.setText("Marked as handheld — docked settings created on first dock");
                statusText.setTextColor(0xFF888888); // Grey: awaiting first swap
            } else if ("docked".equals(classified)) {
                statusText.setText("Marked as docked — handheld settings created on first undock");
                statusText.setTextColor(0xFF888888); // Grey: awaiting first swap
            } else {
                statusText.setText("No profiles yet — will be created on first dock/undock");
                statusText.setTextColor(0xFF888888); // Grey: completely unconfigured
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MASTER TOGGLE: Enable/Disable All Rows
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Visually enables or disables the entire emulator list based on the master toggle.
     *
     * <p>When disabled, all child views are dimmed to 40% opacity and have their
     * {@code enabled} state set to {@code false}, preventing interaction. This gives
     * the user a clear visual indication that profile switching is globally off.</p>
     *
     * @param enabled {@code true} to enable (full opacity), {@code false} to dim and disable
     */
    private void updateListEnabled(boolean enabled) {
        float alpha = enabled ? 1.0f : 0.4f;
        for (int i = 0; i < emulatorList.getChildCount(); i++) {
            View child = emulatorList.getChildAt(i);
            child.setAlpha(alpha);
            setViewEnabled(child, enabled);
        }
    }

    /**
     * Recursively sets the {@code enabled} state on a view and all of its children
     * (if it is a {@link LinearLayout}).
     *
     * <p>This ensures that nested switches, buttons, and text views inside emulator
     * rows are all disabled when the master toggle is off.</p>
     *
     * @param view    the view to update
     * @param enabled the enabled state to apply
     */
    private void setViewEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof LinearLayout) {
            LinearLayout layout = (LinearLayout) view;
            for (int i = 0; i < layout.getChildCount(); i++) {
                setViewEnabled(layout.getChildAt(i), enabled);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  UTILITY: Density-Independent Pixel Conversion
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Converts a density-independent pixel (dp) value to actual pixels for the
     * current display density.
     *
     * <p>Used throughout this Activity for all programmatic layout dimensions
     * (padding, margins, fixed widths/heights) to ensure consistent sizing across
     * different screen densities.</p>
     *
     * @param value the dp value to convert
     * @return the equivalent pixel value, rounded to the nearest integer
     */
    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
