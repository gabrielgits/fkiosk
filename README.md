# fkiosk

A Flutter plugin for **Android Enterprise** kiosk deployments. Exposes Lock Task Mode and silent APK install/update for COSU (Corporate Owned, Single Use) devices where the app is the Device Owner.

> Android-only. Requires Android 11+ (API 30) and Device Owner privileges.

## Features

- **Kiosk Mode** — Enter/exit Lock Task Mode via `DevicePolicyManager`, configure which system UI features stay available (status bar, notifications, home, overview, global actions, keyguard), and listen for state changes.
- **Silent Updates** — Install and uninstall APKs in the background using `PackageInstaller`. Install from a local path or directly from a URL, and observe progress through a stream.
- **Device Control** — Reboot, shut down, and toggle auto-start on boot.

## Requirements

- Flutter `>=3.10.0`, Dart `>=3.0.0 <4.0.0`
- Android 11+ (API 30+)
- App provisioned as **Device Owner** (zero-touch, QR provisioning, or `adb shell dpm set-device-owner`)

## Installation

```yaml
dependencies:
  fkiosk:
    git:
      url: https://github.com/gabrielvieira/fkiosk.git
```

Then:

```dart
import 'package:fkiosk/fkiosk.dart';
```

## Kiosk Mode

```dart
final kiosk = KioskModePlugin();

if (!await kiosk.isDeviceOwner()) return;

await kiosk.enableKioskMode(
  config: const KioskConfig(
    showStatusBar: true,
    showNotifications: false,
    enableHomeButton: false,
    allowedPackages: ['com.example.companion'],
  ),
);

kiosk.onKioskModeChanged.listen((inKiosk) {
  debugPrint('kiosk = $inKiosk');
});

// Adjust system UI features at runtime
await kiosk.setKioskFeatures({
  KioskFeature.systemInfo,
  KioskFeature.keyguard,
});

final locked = await kiosk.isInKioskMode();

await kiosk.disableKioskMode();
```

### `KioskConfig`

| Option | Default | Description |
|---|---|---|
| `showStatusBar` | `false` | Time, battery, connectivity |
| `showNotifications` | `false` | System notifications |
| `enableHomeButton` | `false` | Home button |
| `enableOverviewButton` | `false` | Recent apps |
| `enablePowerButton` | `false` | Power menu |
| `allowedPackages` | `[]` | Additional whitelisted packages |

### `KioskFeature`

`systemInfo`, `notifications`, `home`, `overview`, `globalActions`, `keyguard` — map 1:1 to `DevicePolicyManager.LOCK_TASK_FEATURE_*` flags.

## Silent Updates

```dart
final updater = SilentUpdatePlugin();

if (!await updater.canSilentInstall()) return;

updater.onUpdateStatus.listen((status) {
  debugPrint('${status.state.name} ${(status.progress * 100).toInt()}%');
});

// From URL (download + install in one step)
final sessionId = await updater.installFromUrl(
  'https://example.com/app-release.apk',
  headers: {'Authorization': 'Bearer $token'},
);

// From a local file
await updater.installApk('/sdcard/Download/app.apk');

// Check a remote manifest for an available update
final info = await updater.checkForUpdate(url: 'https://example.com/latest.json');

// Current app version
final version = await updater.getVersionInfo();

await updater.uninstallPackage('com.example.legacy');
```

## Device Control

```dart
await kiosk.rebootDevice();
await kiosk.shutdownDevice();
await kiosk.enableAutoStart();   // start app on BOOT_COMPLETED
await kiosk.disableAutoStart();
```

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for platform channel layout, DPC setup, and provisioning flows.

## License

See [LICENSE](LICENSE).
