/*
 * Copyright (c) 2026 Gavamedia (https://gavamedia.com)
 * Licensed under the MIT License. See LICENSE file in the project root.
 */
package com.gavamedia.retrodock;

import android.os.Build;

/**
 * Known Android gaming handhelds and their recommended docked output resolutions.
 *
 * <p>When the app launches for the first time, it checks {@link Build#MODEL} against
 * this table and pre-fills the docked resolution fields with the device's recommended
 * values. Users can override at any time; see {@link MainActivity} for the
 * "resolution_user_set" preference that tracks whether defaults have been customised.</p>
 *
 * <p>The resolutions listed here are the <em>maximum reliable</em> external display
 * output each device supports over USB-C / HDMI. Devices with Snapdragon 8 Gen 2 or
 * Qualcomm G3x Gen 2 can output 4K (3840x2160). Mid-range chipsets (Dimensity,
 * Unisoc T820, etc.) top out at 1080p. Lower-end devices cap at 720p or 480p.</p>
 */
public final class DeviceProfiles {

    /** Prevent instantiation. */
    private DeviceProfiles() {}

    // ────────────────────────────────────────────────────────────────────────
    //  Data class
    // ────────────────────────────────────────────────────────────────────────

    /** Immutable record of a known device and its recommended docked resolution. */
    public static final class Profile {
        /** Human-readable device name shown in the UI. */
        public final String displayName;
        /** Recommended docked output width in pixels. */
        public final int dockedWidth;
        /** Recommended docked output height in pixels. */
        public final int dockedHeight;

        Profile(String displayName, int dockedWidth, int dockedHeight) {
            this.displayName = displayName;
            this.dockedWidth = dockedWidth;
            this.dockedHeight = dockedHeight;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Lookup table — keyed by Build.MODEL (case-insensitive match)
    // ────────────────────────────────────────────────────────────────────────

    private static final String[][] KNOWN_DEVICES = {
        // ── Retroid ──────────────────────────────────────────────────────
        // Manufacturer varies across generations: "Retroid", "Moorechip",
        // "retroidpocket" — so we match on MODEL only.
        {"Retroid Pocket 5",                "Retroid Pocket 5",          "1080", "1920"},
        {"Retroid Pocket Mini",             "Retroid Pocket Mini",       "1080", "1920"},
        {"Retroid Pocket Mini V2",          "Retroid Pocket Mini V2",    "1080", "1920"},
        {"Retroid Pocket Flip2 (D1100)",    "Retroid Pocket Flip 2",    "1080", "1920"},
        {"Retroid Pocket 4 Pro",            "Retroid Pocket 4 Pro",     "1080", "1920"},
        {"Retroid Pocket 4",                "Retroid Pocket 4",         "1080", "1920"},
        {"Retroid Pocket 3 Plus",           "Retroid Pocket 3+",        "1080", "1920"},
        {"Retroid Pocket 3",                "Retroid Pocket 3",         "720",  "1280"},
        {"Retroid Pocket 3.5 Flip",         "Retroid Pocket Flip",      "720",  "1280"},
        {"Retroid Pocket 2S",               "Retroid Pocket 2S",        "640",  "480"},
        {"Retroid Pocket 2+",               "Retroid Pocket 2+",        "640",  "480"},
        {"Retroid Pocket 2Plus",            "Retroid Pocket 2+",        "640",  "480"},

        // ── AYN Odin ────────────────────────────────────────────────────
        // Snapdragon 8 Gen 2 models can output 4K via HDMI 2.0 / USB-C DP.
        {"Odin 2",                          "AYN Odin 2",               "3840", "2160"},
        {"Odin2 Mini",                      "AYN Odin 2 Mini",          "3840", "2160"},
        {"Odin2 Portal",                    "AYN Odin 2 Portal",        "3840", "2160"},
        {"Odin",                            "AYN Odin",                 "1080", "1920"},
        {"Odin_M0",                         "AYN Odin Lite",            "1080", "1920"},

        // ── AYANEO ──────────────────────────────────────────────────────
        // G3x Gen 2 models support 4K output; Dimensity 1200 caps at 1080p.
        {"AYANEO Pocket S",                 "AYANEO Pocket S",          "3840", "2160"},
        {"AYANEO Pocket S2 Pro",            "AYANEO Pocket S2 Pro",     "3840", "2160"},
        {"AYANEO Pocket EVO",               "AYANEO Pocket EVO",        "3840", "2160"},
        {"AYANEO Pocket DS",                "AYANEO Pocket DS",         "3840", "2160"},
        {"AYANEO Pocket DMG",               "AYANEO Pocket DMG",        "1080", "1920"},
        {"AYANEO Pocket Micro",             "AYANEO Pocket Micro",      "1080", "1920"},
        {"AYANEO Pocket Air",               "AYANEO Pocket Air",        "1080", "1920"},
        {"AYANEO Pocket FIT",               "AYANEO Pocket FIT",        "1080", "1920"},
        {"AYANEO AIR",                      "AYANEO Air",               "1080", "1920"},

        // ── Anbernic ────────────────────────────────────────────────────
        {"RG556",                           "Anbernic RG556",           "1080", "1920"},
        {"RG Cube",                         "Anbernic RG Cube",         "1080", "1920"},
        {"RG405M",                          "Anbernic RG405M",          "1080", "1920"},
        {"RG405V",                          "Anbernic RG405V",          "1080", "1920"},
        {"RG406V",                          "Anbernic RG406V",          "1080", "1920"},
        {"RG 406H",                         "Anbernic RG406H",          "1080", "1920"},
        {"RG552",                           "Anbernic RG552",           "1080", "1920"},

        // ── GPD ─────────────────────────────────────────────────────────
        {"GPD XP Plus",                     "GPD XP Plus",              "1080", "1920"},
    };

    // ────────────────────────────────────────────────────────────────────────
    //  Public API
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Looks up the current device's {@link Build#MODEL} in the known-devices
     * table and returns the matching {@link Profile}, or {@code null} if the
     * device is not recognised.
     *
     * <p>The comparison is case-insensitive and also checks for a
     * {@code contains} match to handle manufacturer-prefixed MODEL strings
     * (e.g. some ROMs report "Retroid Pocket 4 Pro (Performance mode)").</p>
     */
    public static Profile detect() {
        String model = Build.MODEL;
        if (model == null || model.isEmpty()) return null;

        // First pass: exact match (case-insensitive)
        for (String[] entry : KNOWN_DEVICES) {
            if (model.equalsIgnoreCase(entry[0])) {
                return new Profile(entry[1], Integer.parseInt(entry[2]), Integer.parseInt(entry[3]));
            }
        }

        // Second pass: contains match for ROM variants that append suffixes
        String modelLower = model.toLowerCase();
        for (String[] entry : KNOWN_DEVICES) {
            if (modelLower.contains(entry[0].toLowerCase())) {
                return new Profile(entry[1], Integer.parseInt(entry[2]), Integer.parseInt(entry[3]));
            }
        }

        return null;
    }

    /**
     * Returns the raw {@link Build#MODEL} string for display purposes.
     */
    public static String getRawModel() {
        return Build.MODEL != null ? Build.MODEL : "Unknown";
    }
}
