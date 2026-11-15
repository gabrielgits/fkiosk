# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`fkiosk` is an Android-only Flutter plugin for Android Enterprise COSU (Corporate Owned, Single Use) deployments where the host app is the **Device Owner**:

1. **Kiosk Mode** — Lock Task Mode via `DevicePolicyManager`, plus device control (reboot, shutdown, auto-start on boot, hide/uninstall apps, wipe, clear device owner).
2. **Silent updates** — background APK install/uninstall via `PackageInstaller` (from a local path or a URL), remote update-manifest checks, progress reported through a stream.

Package `ao.gabrielvieira.fkiosk`, plugin class `FKioskPlugin`. minSdk 24, compileSdk 34, JVM target 17. Consumed as a git dependency (see `README.md` for the public Dart API and usage examples).

### API-level guards

minSdk 24 is the floor of `DevicePolicyManager.reboot()` and Flutter. Several APIs are newer and must stay behind `Build.VERSION.SDK_INT` checks:

- `setLockTaskFeatures()` (API 28+) — backs `setKioskFeatures` and the `KioskConfig` toggles (`showStatusBar`, `showNotifications`, `enableHomeButton`, `enableOverviewButton`, `enablePowerButton`). Below 28, Lock Task still activates but the toggles are silently ignored (OS falls back to full lockdown).
- `PackageInfo.getLongVersionCode()` (API 28+) — falls back to `versionCode` in `SilentUpdateHandler`.
- `WIPE_SILENTLY` (API 29+) — in `wipeData()`.

Guard any new API-gated call the same way.

## Commands

```bash
flutter pub get
flutter analyze                                   # lint (flutter_lints)
flutter test                                      # all Dart unit tests
flutter test test/kiosk_mode_plugin_test.dart     # single file
flutter test --name "enableKioskMode"             # single test by name

# Example app — manual testing on a real (Device Owner) device
cd example && flutter run
adb shell dpm set-device-owner <pkg>/<AdminReceiver component>   # provision for testing
```

There is no Android/Kotlin unit test suite. Dart tests in `test/` mock the MethodChannels; native code is exercised only via the example app (`example/integration_test/` has a basic integration test) on real hardware.

## Architecture

### Dart ↔ Kotlin wiring

`FKioskPlugin.kt` creates four channels in `onAttachedToEngine`:

| Channel | Type | Kotlin handler |
|---|---|---|
| `ao.gabrielvieira.fkiosk/kiosk_mode` | MethodChannel | `KioskModeHandler` |
| `ao.gabrielvieira.fkiosk/kiosk_mode_events` | EventChannel | `KioskModeHandler` (also a `StreamHandler`) |
| `ao.gabrielvieira.fkiosk/silent_update` | MethodChannel | `SilentUpdateHandler` |
| `ao.gabrielvieira.fkiosk/update_events` | EventChannel | `UpdateEventEmitter` (Kotlin `object`) |

- The **update** handlers only need a `Context` and are bound in `onAttachedToEngine`.
- The **kiosk** handlers are bound later, in `onAttachedToActivity` (plugin is `ActivityAware`), because `startLockTask`/`stopLockTask` need an `Activity`. Before attach, kiosk calls go unanswered; during config changes `setActivity(null)` is called and every method except `isDeviceOwner` returns `NO_ACTIVITY`.
- Channel names are literal strings duplicated in Dart (`lib/src/*_plugin.dart`), Kotlin (`FKioskPlugin.kt`) and the tests. Renaming one means updating all three.

### Admin component resolution

`setupKioskHandler` resolves the admin `ComponentName` from `dpm.activeAdmins` (the first admin belonging to the host app's package) and only falls back to the plugin's own `AdminReceiver`. Host apps commonly register their *own* `DeviceAdminReceiver` as device owner; passing the plugin's stub receiver to `setLockTaskPackages`/`setLockTaskFeatures` would then throw `SecurityException`. Reuse the `adminComponent` held by `KioskModeHandler` instead of calling `AdminReceiver.getComponentName()` directly in new DPM calls.

### Kotlin layout (`android/src/main/kotlin/ao/gabrielvieira/fkiosk/`)

- `FKioskPlugin.kt` — channel registration and lifecycle only.
- `kiosk/` — `KioskModeHandler` (all kiosk + device-control methods), `LockTaskFeatures` (maps `KioskConfig`/`KioskFeature` → `LOCK_TASK_FEATURE_*` bitflags), `KioskActivity` (declared `lockTaskMode="if_whitelisted"`; only used if the plugin launches its own kiosk shell — the host's main activity doesn't need it).
- `update/` — `SilentUpdateHandler`, `PackageInstallerHelper` (session create/write/commit), `UpdateReceiver` (system-instantiated receiver for install/uninstall status), `UpdateEventEmitter`.
- `dpc/` — `AdminReceiver` (stub `DeviceAdminReceiver`), `DevicePolicyHelper`.
- `boot/BootReceiver.kt` — `BOOT_COMPLETED` receiver, **disabled in the manifest** (`android:enabled="false"`); `enableAutoStart`/`disableAutoStart` toggle it with `setComponentEnabledSetting` by its fully-qualified class name (keep that string in sync if it moves).

### Dart layer (`lib/`)

`lib/fkiosk.dart` is the barrel; consumers import only `package:fkiosk/fkiosk.dart`. It exports:

- `KioskModePlugin`, `SilentUpdatePlugin` — thin MethodChannel/EventChannel wrappers; keep logic on the Kotlin side.
- `KioskService` — a facade over both plugins. When adding a plugin method, decide whether it should also be exposed here.
- Models: `KioskConfig` (+ `KioskFeature`), `UpdateStatus` (+ `UpdateInfo`), `DeviceInfo`.

## Conventions

- **Never block the platform thread.** Network/IO work in handlers (`installFromUrl` download, `checkForUpdate` manifest fetch/parse) runs on a background `Thread`, and `result.success/error` is posted back via `Handler(Looper.getMainLooper())`. A crash from parsing the update manifest on the main thread was fixed this way — follow the same pattern for any new slow call.
- Device Owner is a hard precondition for kiosk/DPM calls — fail loudly with a `result.error(...)` rather than silently no-op.
- `UpdateEventEmitter` must stay a Kotlin `object`: `UpdateReceiver` is created by the system and needs a static sink to post status events to.
