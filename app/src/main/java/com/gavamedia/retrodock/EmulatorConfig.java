/*
 * Copyright (c) 2026 Gavamedia (https://gavamedia.com)
 * Licensed under the MIT License. See LICENSE file in the project root.
 */
package com.gavamedia.retrodock;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Database of known Android emulators and their configuration layouts, plus support
 * for user-defined custom entries.
 *
 * <p>RetroDock swaps emulator settings when a dock/controller is connected or disconnected.
 * To do this it needs to know three things about each emulator:
 * <ol>
 *   <li>How to detect it is installed (Android package names)</li>
 *   <li>Where its configuration lives on the filesystem (root folder paths)</li>
 *   <li>Which files or directories inside those roots contain the settings to swap
 *       (as opposed to saves, ROMs, shaders, etc.)</li>
 * </ol>
 *
 * <p>The built-in database ({@link #getKnownDatabase()}) ships 27 emulators covering
 * Nintendo, Sony, Sega, multi-system, and other platforms. Users can supplement it
 * with custom entries that are persisted in {@link SharedPreferences}.
 *
 * <h3>How settings file resolution works</h3>
 * <p>{@link #settingsFiles} contains <b>relative</b> paths. Each one is resolved
 * independently against every entry in {@link #defaultPaths}. For example, if
 * {@code defaultPaths} is {@code ["/storage/emulated/0/RetroArch",
 * "/storage/emulated/0/Android/data/com.retroarch/files"]} and {@code settingsFiles}
 * is {@code ["retroarch.cfg", "config"]}, then RetroDock will look for:
 * <ul>
 *   <li>{@code /storage/emulated/0/RetroArch/retroarch.cfg}</li>
 *   <li>{@code /storage/emulated/0/RetroArch/config/}</li>
 *   <li>{@code /storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg}</li>
 *   <li>{@code /storage/emulated/0/Android/data/com.retroarch/files/config/}</li>
 * </ul>
 * Each settings file may be found in a different root, and each one that exists on
 * the device is independently backed up and swapped.
 */
public class EmulatorConfig {

    /**
     * Unique identifier for this emulator, used as a key in SharedPreferences and
     * internally to distinguish entries. For built-in emulators this is a short
     * human-readable slug (e.g. "retroarch", "dolphin"). For custom emulators it
     * follows the pattern "custom_N" where N is the index.
     */
    public final String id;

    /**
     * Human-readable name shown in the UI (e.g. "RetroArch", "Dolphin (GameCube/Wii)").
     */
    public final String displayName;

    /**
     * One or more Android application package names used to detect whether this
     * emulator is installed. Checked via {@link PackageManager#getPackageInfo}.
     * Many emulators ship multiple variants (free/pro, stable/canary, arm64/arm32),
     * so this array may contain several entries. Only one needs to match for the
     * emulator to be considered installed.
     */
    public final String[] packageNames;

    /**
     * Absolute filesystem paths where this emulator may store its configuration.
     * Typically includes both the legacy shared-storage location
     * (e.g. {@code /storage/emulated/0/RetroArch}) and the scoped-storage location
     * (e.g. {@code /storage/emulated/0/Android/data/com.retroarch/files}).
     * Each path is checked at runtime; only those that exist on the device are used.
     */
    public final String[] defaultPaths;

    /**
     * Relative paths within the emulator root folders that contain swappable settings.
     *
     * <p>These are paths to individual files (e.g. {@code "retroarch.cfg"},
     * {@code "settings.ini"}) or directories (e.g. {@code "config"},
     * {@code "gamesettings"}) that hold configuration data. They deliberately exclude
     * saves, ROMs, shaders, BIOS files, and other data that should not change when
     * switching profiles.
     *
     * <p>Each relative path is resolved independently against every entry in
     * {@link #defaultPaths}. A single settings file may therefore be found in one
     * root while another settings file lives in a different root.
     */
    public final String[] settingsFiles;

    /**
     * {@code true} if this entry was added by the user rather than coming from the
     * built-in database. Custom entries are persisted via SharedPreferences and
     * always included in the installed list regardless of PackageManager detection.
     */
    public final boolean isCustom;

    /**
     * Constructs a built-in (non-custom) emulator configuration.
     *
     * @param id            unique slug identifying this emulator
     * @param displayName   human-readable name for the UI
     * @param packageNames  Android package name(s) to detect installation
     * @param defaultPaths  absolute root folder(s) where config may live
     * @param settingsFiles relative paths within roots to settings files/dirs
     */
    public EmulatorConfig(String id, String displayName, String[] packageNames,
                          String[] defaultPaths, String[] settingsFiles) {
        this(id, displayName, packageNames, defaultPaths, settingsFiles, false);
    }

    /**
     * Constructs an emulator configuration.
     *
     * @param id            unique slug identifying this emulator
     * @param displayName   human-readable name for the UI
     * @param packageNames  Android package name(s) to detect installation
     * @param defaultPaths  absolute root folder(s) where config may live
     * @param settingsFiles relative paths within roots to settings files/dirs
     * @param isCustom      {@code true} if this is a user-defined entry
     */
    public EmulatorConfig(String id, String displayName, String[] packageNames,
                          String[] defaultPaths, String[] settingsFiles, boolean isCustom) {
        this.id = id;
        this.displayName = displayName;
        this.packageNames = packageNames;
        this.defaultPaths = defaultPaths;
        this.settingsFiles = settingsFiles;
        this.isCustom = isCustom;
    }

    /**
     * Returns only emulators that are currently installed on the device,
     * plus any user-added custom entries.
     *
     * <p>Detection works by iterating every emulator in {@link #getKnownDatabase()}
     * and attempting {@link PackageManager#getPackageInfo} for each of its
     * {@link #packageNames}. If any one package is found, the emulator is considered
     * installed and added to the result list (only once, even if multiple variants
     * are present). Custom entries from {@link #getCustomEntries(Context)} are
     * unconditionally appended.
     *
     * @param ctx Android context, used for PackageManager and SharedPreferences access
     * @return list of emulators installed on the device plus all custom entries
     */
    public static List<EmulatorConfig> getInstalled(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<EmulatorConfig> installed = new ArrayList<>();

        for (EmulatorConfig emu : getKnownDatabase()) {
            for (String pkg : emu.packageNames) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    installed.add(emu);
                    break;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }

        // Add user-defined custom emulators (always included regardless of detection)
        installed.addAll(getCustomEntries(ctx));

        return installed;
    }

    /**
     * Returns the full built-in database of 27 known emulators and their
     * package names, config root paths, and settings file locations.
     *
     * <p>Emulators are grouped by platform:
     * <ul>
     *   <li><b>Multi-system</b> -- RetroArch, Lemuroid</li>
     *   <li><b>Nintendo</b> -- Dolphin, Citra, Lime3DS, PabloMK7 Citra, Yuzu,
     *       Ryujinx, Suyu, Citron, Pizza Boy, Mupen64Plus FZ, DraStic, melonDS,
     *       Snes9x EX+, mGBA</li>
     *   <li><b>Sony</b> -- DuckStation, NetherSx2, PPSSPP, Vita3K</li>
     *   <li><b>Sega</b> -- Redream, Flycast, Yaba Sanshiro</li>
     *   <li><b>Other</b> -- ScummVM, Magic DOSBox, MAME4droid</li>
     * </ul>
     *
     * <p>This list does not include custom user-defined entries; see
     * {@link #getCustomEntries(Context)} for those.
     *
     * @return all 27 known emulator configurations
     */
    public static List<EmulatorConfig> getKnownDatabase() {
        List<EmulatorConfig> list = new ArrayList<>();

        // =====================================================================
        // Multi-system emulators
        // =====================================================================

        // RetroArch -- the most popular multi-system frontend.
        // Packages: stable, aarch64 (64-bit ARM), and ra32 (32-bit ARM).
        // retroarch.cfg  = master config file (video, audio, input, paths, etc.)
        // config/        = per-core and per-game override directory tree
        list.add(new EmulatorConfig("retroarch", "RetroArch",
                new String[]{"com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32"},
                new String[]{"/storage/emulated/0/RetroArch",
                        "/storage/emulated/0/Android/data/com.retroarch/files",
                        "/storage/emulated/0/Android/data/com.retroarch.aarch64/files"},
                new String[]{"retroarch.cfg", "config"}));

        // Lemuroid -- simple multi-system emulator built on libretro cores.
        //
        // NOTE: Lemuroid only has a scoped storage path. Config file accessibility
        // depends on Android version and device. If config/ doesn't exist at this
        // path, RetroDock silently skips this emulator.
        list.add(new EmulatorConfig("lemuroid", "Lemuroid",
                new String[]{"com.swordfish.lemuroid"},
                new String[]{"/storage/emulated/0/Android/data/com.swordfish.lemuroid/files"},
                new String[]{"config"}));

        // =====================================================================
        // Nintendo emulators
        // =====================================================================

        // Dolphin -- GameCube and Wii emulator.
        // Config/ directory contains Dolphin.ini, GFX.ini, WiimoteNew.ini,
        // GCPadNew.ini, etc. -- all the core and graphics settings.
        // Packages: standard, handheld-optimized variant.
        // Config/ directory contains Dolphin.ini, GFX.ini, WiimoteNew.ini,
        // GCPadNew.ini, etc. -- all the core and graphics settings.
        list.add(new EmulatorConfig("dolphin", "Dolphin (GameCube/Wii)",
                new String[]{"org.dolphinemu.dolphinemu", "org.dolphinemu.handheld"},
                new String[]{"/storage/emulated/0/dolphin-emu",
                        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files",
                        "/storage/emulated/0/Android/data/org.dolphinemu.handheld/files"},
                new String[]{"Config"}));

        // Citra -- Nintendo 3DS emulator (original project, now discontinued).
        // Packages: stable and canary builds.
        // config/ = sdl2-config.ini and similar configuration files
        list.add(new EmulatorConfig("citra", "Citra (3DS)",
                new String[]{"org.citra.citra_emu", "org.citra.citra_emu.canary"},
                new String[]{"/storage/emulated/0/citra-emu",
                        "/storage/emulated/0/Android/data/org.citra.citra_emu/files/citra-emu"},
                new String[]{"config"}));

        // Lime3DS -- community continuation of Citra for 3DS emulation.
        // Packages: stable and canary builds.
        // config/ = configuration files following the Citra layout
        list.add(new EmulatorConfig("lime3ds", "Lime3DS (3DS)",
                new String[]{"io.github.lime3ds.android", "io.github.lime3ds.android.canary"},
                new String[]{"/storage/emulated/0/lime3ds",
                        "/storage/emulated/0/Android/data/io.github.lime3ds.android/files"},
                new String[]{"config"}));

        // PabloMK7 Citra -- another community fork of Citra for 3DS emulation.
        // config/ = configuration files following the Citra layout
        list.add(new EmulatorConfig("pablomk7_citra", "PabloMK7 Citra (3DS)",
                new String[]{"org.PabloMK7.citra"},
                new String[]{"/storage/emulated/0/citra-emu",
                        "/storage/emulated/0/Android/data/org.PabloMK7.citra/files"},
                new String[]{"config"}));

        // Yuzu -- Nintendo Switch emulator (discontinued).
        // Packages: stable and early-access (EA) builds.
        // config/ = per-module INI files (system, renderer, controls, etc.)
        list.add(new EmulatorConfig("yuzu", "Yuzu (Switch)",
                new String[]{"org.yuzu.yuzu_emu", "org.yuzu.yuzu_emu.ea"},
                new String[]{"/storage/emulated/0/yuzu",
                        "/storage/emulated/0/Android/data/org.yuzu.yuzu_emu/files"},
                new String[]{"config"}));

        // Ryujinx -- Nintendo Switch emulator.
        // Config.json = single monolithic JSON configuration file
        list.add(new EmulatorConfig("ryujinx", "Ryujinx (Switch)",
                new String[]{"org.ryujinx.Ryujinx"},
                new String[]{"/storage/emulated/0/Ryujinx"},
                new String[]{"Config.json"}));

        // Suyu -- community fork of Yuzu for Switch emulation.
        // config/ = per-module INI files matching the Yuzu layout
        list.add(new EmulatorConfig("suyu", "Suyu (Switch)",
                new String[]{"dev.suyu.suyu_emu"},
                new String[]{"/storage/emulated/0/suyu",
                        "/storage/emulated/0/Android/data/dev.suyu.suyu_emu/files"},
                new String[]{"config"}));

        // Citron -- another community fork of Yuzu for Switch emulation.
        // config/ = per-module INI files matching the Yuzu layout
        list.add(new EmulatorConfig("citron", "Citron (Switch)",
                new String[]{"org.citron.citron_emu"},
                new String[]{"/storage/emulated/0/citron",
                        "/storage/emulated/0/Android/data/org.citron.citron_emu/files"},
                new String[]{"config"}));

        // Pizza Boy -- Game Boy Advance and Game Boy Color emulator.
        // Packages: GBA free, GBA pro, GBC free, GBC pro.
        //
        // NOTE: Pizza Boy on Android likely stores its main settings in SharedPreferences
        // (inaccessible without root), not as a settings.json in scoped storage. The
        // scoped storage path is included speculatively — if settings.json doesn't exist
        // there, RetroDock will simply skip this emulator during swaps (no harm done).
        // settings.json = single JSON file containing all emulator preferences (if present)
        list.add(new EmulatorConfig("pizza", "Pizza Boy (GBA/GBC)",
                new String[]{"it.dbtecno.pizzaboygba", "it.dbtecno.pizzaboygbapro",
                        "it.dbtecno.pizzaboygbc", "it.dbtecno.pizzaboygbcpro"},
                new String[]{"/storage/emulated/0/Android/data/it.dbtecno.pizzaboygba/files",
                        "/storage/emulated/0/Android/data/it.dbtecno.pizzaboygbapro/files"},
                new String[]{"settings.json"}));

        // Mupen64Plus FZ -- Nintendo 64 emulator by Francisco Zurita.
        // Packages: free and pro versions.
        //
        // NOTE: Confirmed via testing that the scoped storage files/ directory is EMPTY.
        // Mupen64Plus FZ stores all settings (including profiles and mupen64plus.cfg) in
        // Android's internal app storage (/data/data/), inaccessible without root.
        // Config paths are included speculatively — if nothing exists, RetroDock silently
        // skips this emulator during swaps.
        list.add(new EmulatorConfig("mupen64", "Mupen64Plus FZ (N64)",
                new String[]{"org.mupen64plusae.v3.fzurita", "org.mupen64plusae.v3.fzurita.pro"},
                new String[]{"/storage/emulated/0/Android/data/org.mupen64plusae.v3.fzurita/files",
                        "/storage/emulated/0/Android/data/org.mupen64plusae.v3.fzurita.pro/files"},
                new String[]{"profiles", "mupen64plus.cfg"}));

        // DraStic -- Nintendo DS emulator.
        // config/ = drastic.cfg and controller layout files
        list.add(new EmulatorConfig("drastic", "DraStic (DS)",
                new String[]{"com.dsemu.drastic"},
                new String[]{"/storage/emulated/0/DraStic",
                        "/storage/emulated/0/Android/data/com.dsemu.drastic/files"},
                new String[]{"config"}));

        // melonDS -- open-source Nintendo DS emulator.
        // melonDS.ini = single INI configuration file
        list.add(new EmulatorConfig("melonds", "melonDS (DS)",
                new String[]{"me.magnum.melonds"},
                new String[]{"/storage/emulated/0/melonDS",
                        "/storage/emulated/0/Android/data/me.magnum.melonds/files"},
                new String[]{"melonDS.ini"}));

        // Snes9x EX+ -- Super Nintendo emulator (EX+ Android port).
        //
        // NOTE: Snes9x EX+ only has a scoped storage path. Config file accessibility
        // depends on Android version and device. If snes9x.conf doesn't exist at this
        // path, RetroDock silently skips this emulator.
        list.add(new EmulatorConfig("snes9x", "Snes9x EX+ (SNES)",
                new String[]{"com.explusalpha.Snes9xPlus"},
                new String[]{"/storage/emulated/0/Android/data/com.explusalpha.Snes9xPlus/files"},
                new String[]{"snes9x.conf"}));

        // mGBA -- Game Boy Advance emulator.
        //
        // NOTE: mGBA only has a scoped storage path. Config file accessibility
        // depends on Android version and device. If config.ini doesn't exist at this
        // path, RetroDock silently skips this emulator.
        list.add(new EmulatorConfig("mgba", "mGBA (GBA)",
                new String[]{"io.mgba.mgba"},
                new String[]{"/storage/emulated/0/Android/data/io.mgba.mgba/files"},
                new String[]{"config.ini"}));

        // =====================================================================
        // Sony emulators
        // =====================================================================

        // DuckStation -- PlayStation 1 emulator.
        //
        // NOTE: DuckStation on Android stores its MAIN settings in Android SharedPreferences
        // (an internal key-value store at /data/data/.../shared_prefs/), NOT in a settings.ini
        // file. This is inaccessible without root. The desktop version uses settings.ini, but
        // the Android version never has.
        //
        // However, PER-GAME settings ARE stored as real .ini files in gamesettings/ (one file
        // per game serial, e.g. "SLUS-01222.ini"). These are accessible and swappable, allowing
        // users to have different per-game shader/upscaling/filtering settings for docked vs
        // handheld mode.
        //
        // RetroDock can only swap gamesettings/ for DuckStation — not the global app config.
        list.add(new EmulatorConfig("duckstation", "DuckStation (PS1)",
                new String[]{"com.github.stenzek.duckstation"},
                new String[]{"/storage/emulated/0/Android/data/com.github.stenzek.duckstation/files",
                        "/storage/emulated/0/duckstation"},
                new String[]{"gamesettings"}));

        // NetherSx2 -- PlayStation 2 emulator (AetherSX2 continuation / PCSX2 fork).
        //
        // NOTE: Like DuckStation, NetherSx2 on Android stores its MAIN settings
        // (GS.ini, PCSX2.ini, etc.) internally via SharedPreferences or in the app's
        // private /data/data/ directory — NOT as accessible INI files. The inis/
        // directory does not exist at the scoped storage path. Confirmed via logcat:
        // EmuFolders::Settings points to the scoped storage root, but no inis/ folder
        // is created there.
        //
        // However, PER-GAME settings ARE stored as real .ini files in gamesettings/
        // (one file per game CRC hash, e.g. "BEB4577E.ini"). These are accessible and
        // swappable, allowing different per-game graphics/upscaling settings per mode.
        //
        // RetroDock can only swap gamesettings/ for NetherSx2 — not the global config.
        list.add(new EmulatorConfig("nethersx2", "NetherSx2 (PS2)",
                new String[]{"xyz.aethersx2.android"},
                new String[]{"/storage/emulated/0/Android/data/xyz.aethersx2.android/files",
                        "/storage/emulated/0/AetherSX2"},
                new String[]{"gamesettings"}));

        // PPSSPP -- PlayStation Portable emulator.
        // Packages: free and gold (paid) versions.
        // SYSTEM/ppsspp.ini = main config file located in the SYSTEM subdirectory
        list.add(new EmulatorConfig("ppsspp", "PPSSPP (PSP)",
                new String[]{"org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"},
                new String[]{"/storage/emulated/0/PSP",
                        "/storage/emulated/0/Android/data/org.ppsspp.ppsspp/files",
                        "/storage/emulated/0/Android/data/org.ppsspp.ppssppgold/files"},
                new String[]{"SYSTEM/ppsspp.ini"}));

        // Vita3K -- PlayStation Vita emulator.
        // config.yml = YAML configuration file
        list.add(new EmulatorConfig("vita3k", "Vita3K (PS Vita)",
                new String[]{"org.vita3k.emulator"},
                new String[]{"/storage/emulated/0/vita3k",
                        "/storage/emulated/0/Android/data/org.vita3k.emulator/files"},
                new String[]{"config.yml"}));

        // =====================================================================
        // Sega emulators
        // =====================================================================

        // Redream -- Sega Dreamcast emulator.
        // redream.cfg = single configuration file
        list.add(new EmulatorConfig("redream", "Redream (Dreamcast)",
                new String[]{"io.recompiled.redream"},
                new String[]{"/storage/emulated/0/redream",
                        "/storage/emulated/0/Android/data/io.recompiled.redream/files"},
                new String[]{"redream.cfg"}));

        // Flycast -- open-source Sega Dreamcast emulator.
        // emu.cfg = single configuration file
        list.add(new EmulatorConfig("flycast", "Flycast (Dreamcast)",
                new String[]{"com.flycast.emulator"},
                new String[]{"/storage/emulated/0/flycast",
                        "/storage/emulated/0/Android/data/com.flycast.emulator/files"},
                new String[]{"emu.cfg"}));

        // Yaba Sanshiro -- Sega Saturn emulator.
        // Packages: free (urern) and pro versions.
        // yabause.ini = single INI configuration file (named after upstream Yabause)
        list.add(new EmulatorConfig("yaba_sanshiro", "Yaba Sanshiro (Saturn)",
                new String[]{"org.uoyabause.urern", "org.devmiyax.yabasanshioro2.pro"},
                new String[]{"/storage/emulated/0/yaba_sanshiro",
                        "/storage/emulated/0/Android/data/org.uoyabause.urern/files"},
                new String[]{"yabause.ini"}));

        // =====================================================================
        // Other emulators / engines
        // =====================================================================

        // ScummVM -- classic adventure game engine (LucasArts, Sierra, etc.).
        // scummvm.ini = master configuration with game entries and global settings
        list.add(new EmulatorConfig("scummvm", "ScummVM",
                new String[]{"org.scummvm.scummvm"},
                new String[]{"/storage/emulated/0/ScummVM",
                        "/storage/emulated/0/Android/data/org.scummvm.scummvm/files"},
                new String[]{"scummvm.ini"}));

        // Magic DOSBox -- DOS emulator for Android.
        // Packages: paid and free versions.
        // config/ = directory containing DOSBox configuration profiles
        list.add(new EmulatorConfig("dosbox", "Magic DOSBox",
                new String[]{"bruenor.magicbox", "bruenor.magicbox.free"},
                new String[]{"/storage/emulated/0/MagicDosbox",
                        "/storage/emulated/0/Android/data/bruenor.magicbox/files"},
                new String[]{"config"}));

        // MAME4droid -- Arcade machine emulator for Android.
        // Packages: 2024 and legacy versions.
        //
        // NOTE: MAME4droid only has scoped storage paths. Config file accessibility
        // depends on Android version and device. If mame4droid.cfg doesn't exist at
        // these paths, RetroDock silently skips this emulator.
        list.add(new EmulatorConfig("mame4droid", "MAME4droid (Arcade)",
                new String[]{"com.seleuco.mame4droid2024", "com.seleuco.mame4droid"},
                new String[]{"/storage/emulated/0/Android/data/com.seleuco.mame4droid2024/files",
                        "/storage/emulated/0/Android/data/com.seleuco.mame4droid/files"},
                new String[]{"mame4droid.cfg"}));

        return list;
    }

    // =========================================================================
    // Custom emulator persistence
    //
    // Users can add emulators not in the built-in database. Custom entries are
    // stored in SharedPreferences as an indexed array:
    //
    //   custom_emu_count          -> total number of custom entries
    //   custom_emu_{i}_name       -> display name
    //   custom_emu_{i}_pkg        -> Android package name (may be empty)
    //   custom_emu_{i}_path       -> absolute config root path (may be empty)
    //   custom_emu_{i}_settings   -> relative settings file/dir path (may be empty)
    //
    // When an entry is removed, all entries above it are shifted down to keep
    // indices contiguous (0..count-1). The associated enable/path preferences
    // (emu_{id}_enabled, emu_{id}_path) are also cleaned up.
    // =========================================================================

    /** SharedPreferences file name used for all RetroDock app preferences. */
    private static final String PREFS_NAME = "retrodock_prefs";

    /** SharedPreferences key storing the number of custom emulator entries. */
    private static final String CUSTOM_COUNT_KEY = "custom_emu_count";

    /**
     * Loads all user-defined custom emulator entries from SharedPreferences.
     *
     * <p>Custom entries are always included in the installed list (they bypass
     * PackageManager detection) since the user explicitly added them.
     *
     * @param ctx Android context for SharedPreferences access
     * @return list of custom emulator configurations; empty if none have been added
     */
    public static List<EmulatorConfig> getCustomEntries(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int count = prefs.getInt(CUSTOM_COUNT_KEY, 0);
        List<EmulatorConfig> customs = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String name = prefs.getString("custom_emu_" + i + "_name", "");
            String pkg = prefs.getString("custom_emu_" + i + "_pkg", "");
            String path = prefs.getString("custom_emu_" + i + "_path", "");
            String settings = prefs.getString("custom_emu_" + i + "_settings", "");
            if (!name.isEmpty()) {
                String id = "custom_" + i;
                customs.add(new EmulatorConfig(id, name,
                        pkg.isEmpty() ? new String[]{} : new String[]{pkg},
                        path.isEmpty() ? new String[]{} : new String[]{path},
                        settings.isEmpty() ? new String[]{} : new String[]{settings},
                        true));
            }
        }
        return customs;
    }

    /**
     * Persists a new custom emulator entry to SharedPreferences.
     *
     * <p>The entry is appended at the end of the custom list. Its id will be
     * {@code "custom_N"} where N is its index in the list.
     *
     * @param ctx          Android context for SharedPreferences access
     * @param name         display name for the emulator (shown in UI)
     * @param packageName  Android package name used for process detection. New UI flows require
     *                     this so RetroDock can avoid renaming a live emulator's config files.
     * @param configPath   absolute path to the emulator's config root folder (can be empty)
     * @param settingsFile relative path to the settings file/dir within the root (can be empty)
     */
    public static void addCustomEntry(Context ctx, String name, String packageName,
                                      String configPath, String settingsFile) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int count = prefs.getInt(CUSTOM_COUNT_KEY, 0);

        prefs.edit()
                .putString("custom_emu_" + count + "_name", name)
                .putString("custom_emu_" + count + "_pkg", packageName)
                .putString("custom_emu_" + count + "_path", configPath)
                .putString("custom_emu_" + count + "_settings", settingsFile)
                .putInt(CUSTOM_COUNT_KEY, count + 1)
                .apply();
    }

    /**
     * Removes a custom emulator entry at the given index and shifts all subsequent
     * entries down to fill the gap, keeping indices contiguous (0..count-1).
     *
     * <p>Also cleans up the associated per-emulator preferences
     * ({@code emu_{id}_enabled} and {@code emu_{id}_path}) for the removed entry.
     *
     * @param ctx   Android context for SharedPreferences access
     * @param index zero-based index of the custom entry to remove; no-op if out of range
     */
    public static void removeCustomEntry(Context ctx, int index) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int count = prefs.getInt(CUSTOM_COUNT_KEY, 0);
        if (index < 0 || index >= count) return;

        // Take one immutable snapshot of the current preference map before we start mutating.
        // This is critical for safe ID migration:
        //
        //   custom_0 removed
        //   custom_1 shifts down and becomes custom_0
        //
        // All of RetroDock's per-emulator state is keyed by the synthetic ID (for example
        // emu_custom_1_enabled, emu_custom_1_classified, emu_custom_1_override_..., and the
        // hot-session bookkeeping keys). If we only shift the custom_emu_{i}_* metadata rows and
        // delete the removed emulator's keys, every surviving shifted emulator silently loses its
        // state because the old keys remain under the now-unused higher index.
        //
        // The safe fix is:
        //   1. snapshot the old pref map,
        //   2. clear the entire emu_custom_{index..count-1}_* namespace,
        //   3. copy each old emu_custom_{i+1}_* prefix down to emu_custom_{i}_* from the snapshot.
        //
        // Using the snapshot avoids a common migration bug where a key that has already been moved
        // is then read again under its new name and shifted a second time.
        Map<String, ?> allPrefs = prefs.getAll();
        SharedPreferences.Editor editor = prefs.edit();
        // Shift entries down to fill the gap left by the removed entry
        for (int i = index; i < count - 1; i++) {
            editor.putString("custom_emu_" + i + "_name", prefs.getString("custom_emu_" + (i + 1) + "_name", ""));
            editor.putString("custom_emu_" + i + "_pkg", prefs.getString("custom_emu_" + (i + 1) + "_pkg", ""));
            editor.putString("custom_emu_" + i + "_path", prefs.getString("custom_emu_" + (i + 1) + "_path", ""));
        }
        // Shift settings field too (separate loop preserved from original implementation)
        for (int i = index; i < count - 1; i++) {
            editor.putString("custom_emu_" + i + "_settings", prefs.getString("custom_emu_" + (i + 1) + "_settings", ""));
        }
        // Remove the now-duplicated last entry
        editor.remove("custom_emu_" + (count - 1) + "_name");
        editor.remove("custom_emu_" + (count - 1) + "_pkg");
        editor.remove("custom_emu_" + (count - 1) + "_path");
        editor.remove("custom_emu_" + (count - 1) + "_settings");
        editor.putInt(CUSTOM_COUNT_KEY, count - 1);

        // Clear the entire affected emu_custom_* namespace first. This removes the deleted
        // emulator's state and also clears the destination prefixes for shifted entries so no
        // stale keys survive beside the migrated ones.
        for (int i = index; i < count; i++) {
            removePrefsWithPrefix(editor, allPrefs, "emu_custom_" + i + "_");
        }

        // Recreate the shifted emulator IDs from the snapshot. This migrates every key under the
        // prefix, not just a hand-picked subset, so future per-emulator features automatically
        // ride along with the same migration logic.
        for (int i = index + 1; i < count; i++) {
            copyPrefsWithPrefix(editor, allPrefs,
                    "emu_custom_" + i + "_",
                    "emu_custom_" + (i - 1) + "_");
        }

        editor.apply();
    }

    /**
     * Removes every preference key that starts with the given prefix.
     *
     * <p>SharedPreferences has no native "remove by prefix" API, so custom-emulator ID
     * migration must enumerate the snapshot map and explicitly remove each matching key.</p>
     */
    private static void removePrefsWithPrefix(SharedPreferences.Editor editor,
                                              Map<String, ?> allPrefs,
                                              String prefix) {
        for (String key : allPrefs.keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
    }

    /**
     * Copies every preference under one per-emulator prefix to another prefix.
     *
     * <p>This is the heart of the custom-ID migration fix. Rather than hard-coding a short list
     * of known keys, we migrate the entire prefix so enable flags, classification, path overrides,
     * cached resolutions, hot-session markers, and future emulator-scoped prefs all stay attached
     * to the correct custom entry when indices shift.</p>
     */
    private static void copyPrefsWithPrefix(SharedPreferences.Editor editor,
                                            Map<String, ?> allPrefs,
                                            String fromPrefix,
                                            String toPrefix) {
        for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(fromPrefix)) {
                continue;
            }
            String migratedKey = toPrefix + key.substring(fromPrefix.length());
            putPreferenceValue(editor, migratedKey, entry.getValue());
        }
    }

    /**
     * Writes one snapshot value back into SharedPreferences using the correct typed setter.
     *
     * <p>Custom-emulator migration needs to preserve the original value type. Using only
     * {@code toString()} here would corrupt booleans such as enabled/classified flags and break
     * later reads. The app currently uses mostly String and boolean values, but the helper also
     * preserves the other standard SharedPreferences scalar types for forward compatibility.</p>
     */
    @SuppressWarnings("unchecked")
    private static void putPreferenceValue(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof Set) {
            editor.putStringSet(key, new HashSet<>((Set<String>) value));
        } else if (value == null) {
            editor.remove(key);
        } else {
            // Defensive fallback: unknown value types are not expected from SharedPreferences,
            // but logging the anomaly is safer than silently discarding data during a migration.
            throw new IllegalArgumentException("Unsupported SharedPreferences value type for key "
                    + key + ": " + value.getClass().getName());
        }
    }
}
