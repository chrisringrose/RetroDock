# RetroDock

**Automatically switch display resolution and emulator settings when you dock your Android gaming handheld.**

RetroDock runs as a lightweight background service that detects when your device connects to a dock, TV, or external display. It instantly switches your screen resolution and swaps your emulator configurations — shaders, filters, scaling, controls — so everything looks right, whether you're playing handheld or on the big screen.

## Why you'd want this

Some Android gaming handhelds have a problem: their docked display output often defaults to the wrong resolution, producing a stretched or letterboxed image on your TV. RetroDock fixes this by forcing the correct widescreen resolution the moment you dock.

But resolution is just one issue; when you move from a 4-inch handheld screen to a 55-inch TV, you probably want different settings, like:

- **Shaders**: Some shaders look great on your TV, but not so great on the tiny screen. Now you can use one when handheld, and another one when docked.
- **Scaling and filters**: Bilinear filtering smooths pixels nicely on a big display but wastes detail on a small one.
- **Controls**: You might want different button mappings for a docked controller vs handheld buttons.
- **Per-game overrides**: Maybe you want a specific CRT shader for SNES games on the TV, but a clean integer-scale shader for GBA games.

RetroDock handles all of this automatically. Dock your device, and it loads your "docked" settings. Undock, and it switches back to "handheld." No menus, no manual swapping.

### The CRT TV use case

If you connect your handheld to an actual CRT television via a RetroTINK or similar adapter, RetroDock makes this seamless. Set your docked resolution to match your CRT's native output, configure CRT-appropriate shaders, and RetroDock transforms your pocket device into the ultimate retro console — then switches right back to handheld mode when you unplug.

## Features

- **Automatic dock detection** via DRM connector status (event-driven, no polling)
- **Resolution switching** to your preferred docked output (e.g. 1080p, 4K, or CRT-appropriate)
- **Device auto-detection** for 30+ known handhelds with suggested docked resolutions
- **Emulator settings swap** — maintains separate docked/handheld versions of config files and directories
- **Live shader hot-apply** for RetroArch (via UDP command interface, no restart needed)
- **Per-emulator control** — enable/disable swapping for each emulator independently
- **Crash-safe swaps** with atomic 3-step rename, rollback on failure, and automatic recovery from interrupted swaps
- **27 built-in emulators** with known config paths, plus support for custom entries
- **Starts on boot** (optional) so it's always ready

### Supported emulators (built-in)

| Platform | Emulators |
|---|---|
| Multi-system | RetroArch, Lemuroid |
| Nintendo 64 | Mupen64Plus FZ |
| GameCube/Wii | Dolphin |
| 3DS | Citra, Lime3DS, PabloMK7 Citra |
| DS | DraStic, melonDS |
| Switch | Yuzu, Ryujinx, Suyu, Citron |
| GBA/GBC | Pizza Boy, mGBA |
| SNES | Snes9x EX+ |
| PlayStation 1 | DuckStation |
| PlayStation 2 | NetherSx2 |
| PSP | PPSSPP |
| PS Vita | Vita3K |
| Dreamcast | Redream, Flycast |
| Saturn | Yaba Sanshiro |
| Point-and-click | ScummVM |
| DOS | Magic DOSBox |
| Arcade | MAME4droid |

You can also add custom emulator entries for anything not in this list.

### Supported devices (auto-detected)

Retroid Pocket 5, Mini, Mini V2, Flip 2, 4 Pro, 4, 3+, 3, Flip, 2S, 2+ | AYN Odin 2, Odin 2 Mini, Odin 2 Portal, Odin, Odin Lite | AYANEO Pocket S, S2 Pro, EVO, DS, DMG, Micro, Air, FIT, Air | Anbernic RG556, RG Cube, RG405M, RG405V, RG406V, RG406H, RG552 | GPD XP Plus

Unrecognized devices still work — you just set the docked resolution manually.

## How it works

### Dock detection

RetroDock registers an Android `DisplayManager.DisplayListener` that fires on display hotplug events. When triggered, it reads the DRM connector status file (e.g. `/sys/class/drm/card0-DP-1/status`) to determine if the device is `connected` (docked) or `disconnected` (handheld). This is event-driven — no background polling.

### Resolution switching

On dock: forces the configured resolution via `wm size WxH`.
On undock: resets to device default via `wm size reset`.
Falls back to `Settings.Global.display_size_forced` if the `wm` command fails.

### Emulator settings swap

This is the core feature. For each enabled emulator, RetroDock maintains two backup copies of your settings:

```
retroarch.cfg              <-- active (whatever mode you're currently in)
retroarch.cfg.docked       <-- backup of your docked settings
retroarch.cfg.handheld     <-- backup of your handheld settings
```

When you dock, RetroDock performs an atomic 3-step swap:

1. `retroarch.cfg` -> `retroarch.cfg.swaptmp` (park current aside)
2. `retroarch.cfg.docked` -> `retroarch.cfg` (restore docked settings)
3. `retroarch.cfg.swaptmp` -> `retroarch.cfg.handheld` (save old as handheld backup)

If step 2 fails, step 1 is rolled back — your original file is restored. Step 3 failure is non-fatal (the active file is already correct). If the app crashes mid-swap, it automatically recovers orphaned `.swaptmp` files on next launch.

This works for both individual files (like `retroarch.cfg`) and entire directories (like RetroArch's `config/` folder, which contains per-core and per-game shader overrides).

### Running emulator safety

If an emulator is currently running when you dock/undock, RetroDock does **not** swap its files immediately — that could corrupt the config if the emulator is writing to it. Instead, it:

1. Hot-applies what it can (e.g. sends a shader change to RetroArch via UDP)
2. Watches for the emulator process to exit
3. Swaps the files once the emulator has closed

## Installation

### Prerequisites

- Android device with USB-C display output (or HDMI)
- ADB access to your device (for granting elevated permissions)
- Android Studio or `adb` command-line tools

### Build and install

```bash
# Clone the repository
git clone https://github.com/nicoepp/RetroDock.git
cd RetroDock

# Build the debug APK
./gradlew assembleDebug

# Install on your device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Grant permissions

RetroDock needs `WRITE_SECURE_SETTINGS` to change the display resolution. This permission can only be granted via ADB (it's not available to normal apps through the Play Store permission flow):

```bash
adb shell pm grant com.gavamedia.retrodock android.permission.WRITE_SECURE_SETTINGS
```

This permission allows RetroDock to:
- Run `wm size` commands to change display resolution
- Write to `Settings.Global.display_size_forced` as a fallback

**If you only want emulator settings swapping** (no resolution changes), you can skip this permission grant. The emulator profile swap feature works without any special permissions — it only reads and renames files in the emulator's own data directories.

### First launch

1. Open RetroDock. It will detect your device and suggest a docked resolution.
2. Toggle **"Auto-switch on dock"** to start the background monitoring service.
3. Go to **Emulator Profiles** to enable swapping for your installed emulators.
4. For each emulator, you'll be asked to classify your current settings as "docked" or "handheld" on the first swap. After that, it's fully automatic.

## Configuration

### DRM node

The default DRM connector node is `card0-DP-1` (DisplayPort output, used by most USB-C docks). If your device uses a different connector, you can change it in the app. Common values:

- `card0-DP-1` — USB-C DisplayPort (most common)
- `card0-HDMI-A-1` — HDMI output
- `card1-DP-1` — Secondary display controller

To find your device's connector, check `/sys/class/drm/` while docked.

### RetroArch shader hot-apply

For live shader switching without restarting RetroArch, enable **Network Commands** in RetroArch:

Settings > Network > Network Commands > **ON** (port 55355)

RetroDock will automatically discover your global shader preset from the swapped `config/` directory and apply it via UDP.

## Upgrading from DockRes

If you previously used **DockRes** (the earlier version of this app), the package name has changed from `com.gavamedia.dockres` to `com.gavamedia.retrodock`. To upgrade:

```bash
# Stop and uninstall the old app
adb shell am force-stop com.gavamedia.dockres
adb uninstall com.gavamedia.dockres

# Install RetroDock
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.gavamedia.retrodock android.permission.WRITE_SECURE_SETTINGS
```

Or simply uninstall DockRes from Settings > Apps before installing RetroDock.

## License

MIT License. Copyright (c) 2026 [Gavamedia](https://gavamedia.com). See [LICENSE](LICENSE) for details.
