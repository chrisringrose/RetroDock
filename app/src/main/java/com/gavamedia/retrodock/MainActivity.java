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

        // Detect the device and show it
        deviceProfile = DeviceProfiles.detect();
        if (deviceProfile != null) {
            detectedDeviceText.setText("Device: " + deviceProfile.displayName
                    + "  (suggested: " + deviceProfile.dockedWidth + "
