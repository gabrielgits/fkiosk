# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`fkiosk` is a Flutter plugin (Android-only) exposing two features for Android Enterprise COSU deployments:

1. **Kiosk Mode** — Lock Task Mode via `DevicePolicyManager`, requires Device Owner.
2. **Silent APK Updates** — Background install via `PackageInstaller`, progress reported through streams.

Min SDK is Android 11 (API 30). Package is `ao.gabrielvieira.fkiosk`, plugin class `FKioskPlugin`.

See `ARCHITECTURE.md` for the full Android Enterprise design doc (platform channels, DPC setup, provisioning flows, API reference).

## Commands

```bash
flutter pub get                        # install deps
flutter analyze                        # lint (flutter_lints)
flutter test                           # run all Dart unit tests
flutter test test/kiosk_mode_plugin_test.dart   # single test file
flutter test --name "enableKioskMode"  # single test by name

# Example app (for manual testing on a device)
cd example && flutter run
```

There is no Android unit test suite — native Kotlin code is exercised only through the Flutter test mocks and the example app on real hardware.

## Architecture

### Dart ↔ Kotlin wiring

`FKioskPlugin.kt` registers four channels on attach:

- `ao.gabrielvieira.fkiosk/kiosk_mode` (MethodChannel) → `KioskModeHandler`
- `ao.gabrielvieira.fkiosk/kiosk_mode_events` (EventChannel) → `KioskModeHandler` (also implements `StreamHandler`)
- `ao.gabrielvieira.fkiosk/silent_update` (MethodChannel) → `SilentUpdateHandler`
- `ao.gabrielvieira.fkiosk/update_events` (EventChannel) → `UpdateEventEmitter` (singleton)

The kiosk handler is **ActivityAware** — it is only wired up inside `onAttachedToActivity` because `startLockTask`/`stopLockTask` need an `Activity`. The update handler only needs `Context` and is wired in `onAttachedToEngine`. When working on kiosk features, remember the handler can briefly have a null activity during config changes (`setActivity(null)` is called in the detached callbacks).

### Kotlin package layout (`android/src/main/kotlin/ao/gabrielvieira/fkiosk/`)

- `FKioskPlugin.kt` — channel registration and lifecycle only; no business logic.
- `kiosk/` — `KioskModeHandler` (MethodChannel + StreamHandler), `KioskActivity` (declared with `android:lockTaskMode="if_whitelisted"`), `LockTaskFeatures` (bitflag helpers mapping `KioskConfig` → `DevicePolicyManager.LOCK_TASK_FEATURE_*`).
- `update/` — `SilentUpdateHandler` (MethodChannel), `PackageInstallerHelper` (session create/write/commit), `UpdateReceiver` (BroadcastReceiver for `ACTION_INSTALL_STATUS` / `ACTION_UNINSTALL_STATUS`), `UpdateEventEmitter` (singleton `StreamHandler` — receiver posts status events through it).
- `dpc/` — `AdminReceiver` (extends `DeviceAdminReceiver`, declared in manifest with `BIND_DEVICE_ADMIN`) and `DevicePolicyHelper`. `AdminReceiver.getComponentName(context)` is the canonical way to get the admin `ComponentName`; use it rather than constructing one by hand.
- `boot/BootReceiver.kt` — `BOOT_COMPLETED` receiver, **disabled by default** in the manifest (`android:enabled="false"`); enable programmatically if the consuming app wants auto-start.

### Dart layer (`lib/src/`)

`fkiosk.dart` is a barrel that re-exports the two plugin classes and the three models (`KioskConfig`, `UpdateStatus`, `DeviceInfo`). Consumers should import `package:fkiosk/fkiosk.dart` only. The two plugin classes (`KioskModePlugin`, `SilentUpdatePlugin`) are thin MethodChannel wrappers — keep business logic on the Kotlin side.

### Channel naming

All channel names are literal strings duplicated in Dart and Kotlin. If you rename a channel, grep for the exact string (e.g. `ao.gabrielvieira.fkiosk/silent_update`) and update both sides plus any tests that mock it.

## Conventions

- Device Owner is a hard precondition for every kiosk call — handlers should check `isDeviceOwner()` and fail loudly rather than silently no-op.
- `UpdateEventEmitter` is a Kotlin `object` (singleton) because `UpdateReceiver` is instantiated by the system and needs a static sink to post events to. Don't convert it to a class.
- The kiosk `KioskActivity` uses `lockTaskMode="if_whitelisted"` — the consuming app's main activity does not need this; it's only used when the plugin launches its own kiosk shell.
