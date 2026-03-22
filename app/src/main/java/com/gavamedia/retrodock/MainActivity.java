/*
 * Copyright (c) 2026 Gavamedia (https://gavamedia.com)
 * Licensed under the MIT License. See LICENSE file in the project root.
 */
package com.gavamedia.retrodock;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Main settings screen for the RetroDock application.
 *
 * <p>RetroDock supports all Android gaming handhelds with USB-C / HDMI dock output.
 * It automatically switches the display resolution and emulator settings when the
 * device is docked or undocked. This Activity provides the user interface for all
 * primary configuration:</p>
 *
 * <ul>
 *   <li><b>Device detection</b> -- On first launch, {@link DeviceProfiles#detect()}
 *       identifies the device via {@code Build.MODEL} and pre-fills the docked
 *       resolution with the device's recommended values. The detected device name
 *       is shown in the header. Users can override and later restore via
 *       "Restore suggested resolution".</li>
 *   <li><b>DRM node selection</b> -- choose which display output connector
 *       (e.g. {@code card0-DP-1}) to monitor for dock connection events.</li>
 *   <li><b>Auto-switch on dock</b> -- a single toggle that starts/stops the
 *       background {@link DisplayMonitorService} and reveals the docked resolution
 *       configuration (width/height inputs, Test/Reset buttons).</li>
 *   <li><b>Emulator Profiles</b> -- navigate to per-emulator settings swap
 *       configuration via {@link EmulatorSettingsActivity}.</li>
 * </ul>
 *
 * <h3>Resolution defaults lifecycle</h3>
 * <ol>
 *   <li>First launch: if device is in {@link DeviceProfiles}, auto-fill its
 *       recommended resolution. Otherwise fall back to 1080x1920.</li>
 *   <li>User changes resolution: {@code resolution_user_set} pref is set to
 *       {@code true}, and their values are saved. "Restore suggested" button
 *       appears.</li>
 *   <li>"Restore suggested" pressed: clears {@code resolution_user_set}, removes
 *       saved width/height, restores device defaults.</li>
 * </ol>
 *
 * <p>A live {@link DisplayManager.DisplayListener} keeps the UI in sync whenever
 * a display is added, removed, or changed (e.g. when the user physically docks
 * or undocks the device while this screen is visible).</p>
 */
public class MainActivity extends Activity {

    /** SharedPreferences file name used across the app for persisted settings. */
    private static final String PREFS_NAME = "retrodock_prefs";

    /**
     * The DRM connector node that is recommended for the Retroid Pocket Mini 5.
     * On this device the USB-C DisplayPort output is exposed as {@code card0-DP-1}
     * under {@code /sys/class/drm/}.
     */
    private static final String RECOMMENDED_NODE = "card0-DP-1";

    // -----------------------------------------------------------------------
    //  UI widgets
    // -----------------------------------------------------------------------

    /** Spinner listing all discovered DRM connector nodes. */
    private Spinner drmSpinner;

    /** Text fields for the user-specified docked resolution (width x height). */
    private EditText widthInput;
    private EditText heightInput;

    /** Container for the resolution input fields and test/reset buttons. */
    private View resolutionSection;

    /** Toggle to start/stop the background {@link DisplayMonitorService}. */
    private Switch serviceToggle;

    /** Label showing whether the currently selected DRM node is connected. */
    private TextView nodeStatusText;

    /** Label showing whether the monitor service is running or stopped. */
    private TextView serviceStatusText;

    /** Label showing the current display resolution reported by {@code wm size}. */
    private TextView currentResText;

    /** Label showing the detected device name. */
    private TextView detectedDeviceText;

    /** Button to restore the device-suggested resolution defaults. */
    private Button restoreSuggestedButton;

    /** Applies the user-entered resolution immediately (for preview purposes). */
    private Button testButton;

    /** Resets the resolution back to the device default. */
    private Button resetButton;

    // -----------------------------------------------------------------------
    //  State
    // -----------------------------------------------------------------------

    /** Persisted user preferences (selected node, resolution, service toggle). */
    private SharedPreferences prefs;

    /** Detected device profile (null if the device is not in the known-devices table). */
    private DeviceProfiles.Profile deviceProfile;

    /** Parallel list of raw DRM node names (e.g. "card0-DP-1"). */
    private List<String> nodeNames;

    /** Parallel list of formatted display labels shown in the spinner. */
    private List<String> nodeLabels;

    // -----------------------------------------------------------------------
    //  Display listener
    // -----------------------------------------------------------------------

    /** System service used to register for display-change callbacks. */
    private DisplayManager displayManager;

    /** Callback that refreshes the UI whenever any display event occurs. */
    private DisplayManager.DisplayListener displayListener;

    /** Handler tied to the main looper; display callbacks are dispatched here. */
    private Handler handler;

    // =======================================================================
    //  Lifecycle
    // =======================================================================

    /**
     * Initializes the Activity: inflates the layout, binds views, loads DRM
     * nodes and saved preferences, wires up listeners, and begins monitoring
     * for display changes.
     *
     * @param savedInstanceState standard Android saved-state bundle (unused).
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());
        displayManager = getSystemService(DisplayManager.class);

        // -- Bind views --
        drmSpinner = findViewById(R.id.drm_spinner);
        widthInput = findViewById(R.id.width_input);
        heightInput = findViewById(R.id.height_input);
        resolutionSection = findViewById(R.id.resolution_section);
        serviceToggle = findViewById(R.id.service_toggle);
        nodeStatusText = findViewById(R.id.node_status);
        serviceStatusText = findViewById(R.id.service_status);
        currentResText = findViewById(R.id.current_res);
        testButton = findViewById(R.id.test_button);
        resetButton = findViewById(R.id.reset_button);
        detectedDeviceText = findViewById(R.id.detected_device);
        restoreSuggestedButton = findViewById(R.id.restore_suggested_button);

        // -- Detect the device and show it --
        deviceProfile = DeviceProfiles.detect();
        if (deviceProfile != null) {
            detectedDeviceText.setText("Device: " + deviceProfile.displayName
                    + "  (suggested: " + deviceProfile.dockedWidth + "\u00d7"
                    + deviceProfile.dockedHeight + ")");
        } else {
            detectedDeviceText.setText("Device: " + DeviceProfiles.getRawModel()
                    + "  (not in database)");
        }

        // -- Load DRM nodes --
        loadDrmNodes();

        // -- Load saved preferences --
        loadPreferences();

        // -- Setup listeners --
        setupSpinnerListener();
        setupServiceToggle();
        setupTestResetButtons();
        setupEmulatorProfilesButton();
        setupRestoreSuggestedButton();

        // -- Start display monitoring for live UI updates --
        startDisplayListener();

        // -- Initial UI refresh --
        refreshUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUI();
    }

    @Override
    protected void onDestroy() {
        if (displayManager != null && displayListener != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        super.onDestroy();
    }

    // =======================================================================
    //  DRM Node Discovery
    // =======================================================================

    /**
     * Scans {@code /sys/class/drm/} for connector nodes and populates the
     * spinner. Each node that contains a {@code status} file is included.
     * Nodes are labeled with their connection status (connected/disconnected).
     */
    private void loadDrmNodes() {
        nodeNames = new ArrayList<>();
        nodeLabels = new ArrayList<>();

        File drmDir = new File("/sys/class/drm");
        if (drmDir.exists() && drmDir.isDirectory()) {
            File[] entries = drmDir.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    File statusFile = new File(entry, "status");
                    if (statusFile.exists()) {
                        String name = entry.getName();
                        String status = ResolutionHelper.readFile(statusFile.getAbsolutePath());
                        nodeNames.add(name);
                        nodeLabels.add(name + " (" + status + ")");
                    }
                }
            }
        }

        // Fallback: if no nodes found, add the recommended default so the
        // spinner isn't empty and the user can still configure the app.
        if (nodeNames.isEmpty()) {
            nodeNames.add(RECOMMENDED_NODE);
            nodeLabels.add(RECOMMENDED_NODE + " (default)");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, nodeLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        drmSpinner.setAdapter(adapter);
    }

    // =======================================================================
    //  Preferences
    // =======================================================================

    /**
     * Loads saved preferences and restores UI state. Handles the resolution
     * defaults lifecycle:
     * <ul>
     *   <li>If the user has never set a custom resolution and a device profile
     *       exists, use the device's suggested resolution.</li>
     *   <li>If the user has set a custom resolution, use their saved values.</li>
     *   <li>Otherwise, fall back to 1080x1920.</li>
     * </ul>
     */
    private void loadPreferences() {
        // -- DRM node --
        String savedNode = prefs.getString("drm_node", RECOMMENDED_NODE);
        int nodeIndex = nodeNames.indexOf(savedNode);
        if (nodeIndex >= 0) {
            drmSpinner.setSelection(nodeIndex);
        }

        // -- Resolution --
        boolean userSet = prefs.getBoolean("resolution_user_set", false);
        String defaultW = "1080";
        String defaultH = "1920";
        if (deviceProfile != null) {
            defaultW = String.valueOf(deviceProfile.dockedWidth);
            defaultH = String.valueOf(deviceProfile.dockedHeight);
        }

        if (userSet) {
            widthInput.setText(prefs.getString("width", defaultW));
            heightInput.setText(prefs.getString("height", defaultH));
        } else {
            widthInput.setText(defaultW);
            heightInput.setText(defaultH);
        }

        // -- Service toggle --
        boolean enabled = prefs.getBoolean("service_enabled", false);
        serviceToggle.setChecked(enabled);
        resolutionSection.setVisibility(enabled ? View.VISIBLE : View.GONE);

        // -- Restore suggested button --
        // Show only when user has customized resolution AND a device profile exists
        updateRestoreSuggestedVisibility(userSet);
    }

    /**
     * Saves the current resolution values to SharedPreferences and marks them
     * as user-set so they take priority over device defaults.
     */
    private void saveResolution() {
        String w = widthInput.getText().toString().trim();
        String h = heightInput.getText().toString().trim();
        prefs.edit()
                .putString("width", w)
                .putString("height", h)
                .putString("drm_node", getSelectedNode())
                .putBoolean("resolution_user_set", true)
                .apply();
        updateRestoreSuggestedVisibility(true);
    }

    /**
     * Returns the currently selected DRM node name from the spinner.
     */
    private String getSelectedNode() {
        int pos = drmSpinner.getSelectedItemPosition();
        if (pos >= 0 && pos < nodeNames.size()) {
            return nodeNames.get(pos);
        }
        return RECOMMENDED_NODE;
    }

    /**
     * Shows or hides the "Restore suggested" button based on whether the user
     * has customized resolution and a device profile is available.
     */
    private void updateRestoreSuggestedVisibility(boolean userSet) {
        if (userSet && deviceProfile != null) {
            restoreSuggestedButton.setVisibility(View.VISIBLE);
        } else {
            restoreSuggestedButton.setVisibility(View.GONE);
        }
    }

    // =======================================================================
    //  Listeners
    // =======================================================================

    /** Saves the selected DRM node when the user picks a different one. */
    private void setupSpinnerListener() {
        drmSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String selected = nodeNames.get(pos);
                prefs.edit().putString("drm_node", selected).apply();
                refreshUI();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * Wires the service toggle to start/stop {@link DisplayMonitorService}
     * and show/hide the resolution configuration section.
     */
    private void setupServiceToggle() {
        serviceToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("service_enabled", isChecked).apply();
            resolutionSection.setVisibility(isChecked ? View.VISIBLE : View.GONE);

            if (isChecked) {
                // Save current resolution values before starting the service
                saveResolution();
                startMonitorService();
            } else {
                stopMonitorService();
            }
            refreshUI();
        });
    }

    /**
     * Wires the Test and Reset buttons for previewing resolution changes.
     */
    private void setupTestResetButtons() {
        testButton.setOnClickListener(v -> {
            String w = widthInput.getText().toString().trim();
            String h = heightInput.getText().toString().trim();
            if (w.isEmpty() || h.isEmpty()) {
                Toast.makeText(this, "Enter width and height", Toast.LENGTH_SHORT).show();
                return;
            }
            int parsedW, parsedH;
            try {
                parsedW = Integer.parseInt(w);
                parsedH = Integer.parseInt(h);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Width and height must be numbers", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean ok = ResolutionHelper.setResolution(getContentResolver(), parsedW, parsedH);
            if (ok) {
                Toast.makeText(this, "Resolution set to " + w + "x" + h, Toast.LENGTH_SHORT).show();
                saveResolution();
            } else {
                Toast.makeText(this, "Failed to set resolution", Toast.LENGTH_SHORT).show();
            }
            updateCurrentRes();
        });

        resetButton.setOnClickListener(v -> {
            boolean ok = ResolutionHelper.resetResolution(getContentResolver());
            if (ok) {
                Toast.makeText(this, "Resolution reset to default", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Failed to reset resolution", Toast.LENGTH_SHORT).show();
            }
            updateCurrentRes();
        });
    }

    /** Opens the emulator profiles configuration screen. */
    private void setupEmulatorProfilesButton() {
        findViewById(R.id.emulator_profiles_button).setOnClickListener(v -> {
            startActivity(new Intent(this, EmulatorSettingsActivity.class));
        });
    }

    /**
     * Wires the "Restore suggested resolution" button to clear user overrides
     * and restore the device profile's recommended values.
     */
    private void setupRestoreSuggestedButton() {
        restoreSuggestedButton.setOnClickListener(v -> {
            if (deviceProfile == null) return;

            // Clear the user-set flag and remove saved values
            prefs.edit()
                    .putBoolean("resolution_user_set", false)
                    .remove("width")
                    .remove("height")
                    .apply();

            // Restore device defaults in the UI
            widthInput.setText(String.valueOf(deviceProfile.dockedWidth));
            heightInput.setText(String.valueOf(deviceProfile.dockedHeight));
            updateRestoreSuggestedVisibility(false);

            Toast.makeText(this, "Restored suggested resolution", Toast.LENGTH_SHORT).show();
        });
    }

    // =======================================================================
    //  Service Management
    // =======================================================================

    /**
     * Starts the {@link DisplayMonitorService} foreground service.
     * On Android O+ (API 26+), must use {@code startForegroundService()}.
     */
    private void startMonitorService() {
        Intent intent = new Intent(this, DisplayMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    /** Stops the {@link DisplayMonitorService}. */
    private void stopMonitorService() {
        stopService(new Intent(this, DisplayMonitorService.class));
    }

    // =======================================================================
    //  Display Listener
    // =======================================================================

    /**
     * Registers a {@link DisplayManager.DisplayListener} to refresh the UI
     * whenever a display is added, removed, or changed. This keeps the node
     * status and current resolution labels up-to-date while the activity is
     * visible.
     */
    private void startDisplayListener() {
        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) { refreshUI(); }

            @Override
            public void onDisplayRemoved(int displayId) { refreshUI(); }

            @Override
            public void onDisplayChanged(int displayId) { refreshUI(); }
        };
        displayManager.registerDisplayListener(displayListener, handler);
    }

    // =======================================================================
    //  UI Refresh
    // =======================================================================

    /**
     * Refreshes all dynamic UI elements: node connection status, service
     * state label, and current resolution. Called on creation, on resume,
     * and whenever a display event fires.
     */
    private void refreshUI() {
        // -- Node status --
        String node = getSelectedNode();
        String statusPath = "/sys/class/drm/" + node + "/status";
        String status = ResolutionHelper.readFile(statusPath);
        nodeStatusText.setText("Status: " + status);

        if ("connected".equals(status)) {
            nodeStatusText.setTextColor(0xFF4CAF50); // green
        } else {
            nodeStatusText.setTextColor(0xFF888888); // gray
        }

        // -- Service status --
        boolean enabled = prefs.getBoolean("service_enabled", false);
        if (enabled) {
            serviceStatusText.setText("Service: running");
            serviceStatusText.setTextColor(0xFF4CAF50); // green
        } else {
            serviceStatusText.setText("Service: stopped");
            serviceStatusText.setTextColor(0xFF888888); // gray
        }

        // -- Current resolution --
        updateCurrentRes();
    }

    /**
     * Reads the current display resolution via {@code wm size} and updates
     * the {@link #currentResText} label.
     *
     * <p>Drains both stdout and stderr before calling {@code waitFor()} to
     * prevent deadlock if the process fills the kernel pipe buffer.</p>
     */
    private void updateCurrentRes() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"wm", "size"});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            // Drain stderr to prevent process from blocking
            try (InputStreamReader err = new InputStreamReader(p.getErrorStream())) {
                while (err.read() != -1) { /* drain */ }
            }
            p.waitFor();
            currentResText.setText(sb.toString().trim());
        } catch (Exception e) {
            currentResText.setText("Could not read current resolution");
        }
    }
}
