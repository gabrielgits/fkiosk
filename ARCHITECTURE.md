# Flutter Kiosk Mode & Silent Update - Technical Architecture

## Table of Contents

1. [Overview](#overview)
2. [System Architecture](#system-architecture)
3. [Android Enterprise SDK Concepts](#android-enterprise-sdk-concepts)
4. [Flutter Plugin Architecture](#flutter-plugin-architecture)
5. [Plugin 1: Kiosk Mode Plugin](#plugin-1-kiosk-mode-plugin)
6. [Plugin 2: Silent Update Plugin](#plugin-2-silent-update-plugin)
7. [Device Provisioning](#device-provisioning)
8. [Remote Management Backend](#remote-management-backend)
9. [Security Considerations](#security-considerations)
10. [Deployment Flow](#deployment-flow)
11. [API Reference](#api-reference)
12. [Appendix](#appendix)

---

## Overview

This document describes the technical architecture for two Flutter plugins that leverage the **Android Enterprise SDK** to:

1. **Lock a Flutter app in Kiosk Mode** (COSU - Corporate Owned Single Use) using the `DevicePolicyManager` Lock Task Mode API.
2. **Silently update the app remotely** without user interaction, using Managed Google Play or a custom Device Policy Controller (DPC).

### Goals

- Full-screen, single-app kiosk experience with no user escape routes.
- Remote, silent APK/AAB updates triggered from a backend server.
- Zero-touch provisioning for new devices.
- Centralized device fleet management.

### Target Devices

- **Minimum: Android 7.0 (API 24)** — the floor of `DevicePolicyManager.reboot()`
  and of Flutter itself.
- Below Android 9 (API 28), Lock Task Mode still activates via
  `setLockTaskPackages()` + `startLockTask()`, but it is all-or-nothing:
  `setLockTaskFeatures()` doesn't exist yet, so the fine-grained toggles
  (status bar, notifications, home, overview, power button) are silently
  ignored and the OS falls back to full lockdown — the safest default.
- Android 9+ (API 28+) unlocks the full customizable kiosk security model:
  - All `setLockTaskFeatures()` customization APIs available.
  - `PackageInfo.getLongVersionCode()` available (used with a fallback below
    API 28).
- Android 10+ (API 29+) adds `WIPE_SILENTLY` to `wipeData()`.
- Android 11+ additionally brings scoped storage enforcement and package
  visibility restrictions; they harden the device but the plugin doesn't
  depend on them.

---

## System Architecture

```
+------------------------------------------------------------------+
|                        CLOUD / BACKEND                           |
|                                                                  |
|  +--------------------+    +-------------------------------+     |
|  | Management Console |    | Android Management API (AMAPI)|     |
|  | (Admin Dashboard)  |--->| or Custom EMM Server          |     |
|  +--------------------+    +-------------------------------+     |
|                                    |                             |
|                                    | Policy Push / APK Deploy    |
|                                    v                             |
+------------------------------------------------------------------+
                                     |
                          FCM / HTTPS Polling
                                     |
+------------------------------------------------------------------+
|                       ANDROID DEVICE                             |
|                                                                  |
|  +------------------------------------------------------------+ |
|  |                   Device Policy Controller (DPC)            | |
|  |  - DeviceAdminReceiver                                      | |
|  |  - DevicePolicyManager APIs                                 | |
|  |  - PackageInstaller (silent install)                        | |
|  +------------------------------------------------------------+ |
|           |                              |                       |
|           v                              v                       |
|  +---------------------+    +-------------------------+         |
|  | Kiosk Mode Plugin   |    | Silent Update Plugin    |         |
|  | (MethodChannel)     |    | (MethodChannel)         |         |
|  +---------------------+    +-------------------------+         |
|           |                              |                       |
|           v                              v                       |
|  +------------------------------------------------------------+ |
|  |                  Flutter Application                        | |
|  |  - Dart UI Layer                                            | |
|  |  - Plugin Interface (Dart API)                              | |
|  +------------------------------------------------------------+ |
|                                                                  |
+------------------------------------------------------------------+
```

---

## Android Enterprise SDK Concepts

### Device Owner vs Profile Owner

| Feature | Device Owner | Profile Owner |
|---|---|---|
| **Scope** | Full device control | Work profile only |
| **Kiosk Mode** | Supported (Lock Task) | Not supported |
| **Silent Install** | Supported | Limited |
| **Provisioning** | Factory reset required | Can be added to existing device |
| **Use Case** | Dedicated kiosk devices | BYOD / dual-use |

**For kiosk use cases, Device Owner mode is required.**

### Key Android APIs

| API | Class | Purpose |
|---|---|---|
| Lock Task Mode | `DevicePolicyManager.setLockTaskPackages()` | Whitelist apps for kiosk mode |
| Lock Task Features | `DevicePolicyManager.setLockTaskFeatures()` | Control UI elements in kiosk |
| Package Installer | `PackageInstaller` | Silent APK installation |
| Managed Config | `RestrictionsManager` | Push config from EMM to app |
| App Update Policy | AMAPI `autoUpdateMode` | Control update timing/priority |

### Lock Task Feature Flags (Android 9+)

```kotlin
// Available flags for setLockTaskFeatures()
DevicePolicyManager.LOCK_TASK_FEATURE_NONE                 // 0 - Full lockdown
DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO          // 1 - Status bar info
DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS        // 2 - Notifications (requires SYSTEM_INFO)
DevicePolicyManager.LOCK_TASK_FEATURE_HOME                 // 4 - Home button
DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW             // 8 - Overview/Recents
DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS       // 16 - Power button dialog
DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD             // 32 - Lock screen
DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK // 64 - Block new activities
```

---

## Flutter Plugin Architecture

### Communication Pattern

Flutter plugins use **Platform Channels** to bridge Dart code with native Android (Kotlin/Java) code.

```
+-------------------+          +-------------------+
|   Dart (Flutter)   |          |  Kotlin (Android)  |
|                   |          |                   |
|  MethodChannel    |  <---->  |  MethodChannel    |
|  'ao.gabrielvieira.fkiosk/     |  Binary  |  Handler          |
|   kiosk_mode'     |  Codec   |                   |
+-------------------+          +-------------------+
```

### Plugin Project Structure

```
fkiosk/
├── lib/
│   ├── fkiosk.dart                          # Public API barrel file
│   ├── src/
│   │   ├── kiosk_mode_plugin.dart           # Dart API for kiosk mode
│   │   ├── silent_update_plugin.dart        # Dart API for silent updates
│   │   └── models/
│   │       ├── kiosk_config.dart            # Kiosk configuration model
│   │       ├── update_status.dart           # Update status model
│   │       └── device_info.dart             # Device provisioning info
├── android/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/fkiosk/
│   │   │   ├── FKioskPlugin.kt             # Plugin registration
│   │   │   ├── kiosk/
│   │   │   │   ├── KioskModeHandler.kt     # Kiosk MethodChannel handler
│   │   │   │   ├── KioskActivity.kt        # Lock Task Activity
│   │   │   │   └── LockTaskFeatures.kt     # Feature flag helpers
│   │   │   ├── update/
│   │   │   │   ├── SilentUpdateHandler.kt  # Update MethodChannel handler
│   │   │   │   ├── PackageInstallerHelper.kt
│   │   │   │   └── UpdateReceiver.kt       # BroadcastReceiver for install status
│   │   │   └── dpc/
│   │   │       ├── AdminReceiver.kt        # DeviceAdminReceiver
│   │   │       └── DevicePolicyHelper.kt   # DPM wrapper utilities
│   │   └── res/
│   │       └── xml/
│   │           └── device_admin_receiver.xml
├── example/                                 # Example Flutter app
├── test/                                    # Dart unit tests
├── pubspec.yaml
└── ARCHITECTURE.md                          # This file
```

---

## Plugin 1: Kiosk Mode Plugin

### Dart API

```dart
/// Controls Android Lock Task (Kiosk) Mode.
class KioskModePlugin {
  static const _channel = MethodChannel('ao.gabrielvieira.fkiosk/kiosk_mode');

  /// Check if the app is currently the Device Owner.
  Future<bool> isDeviceOwner() async {
    return await _channel.invokeMethod<bool>('isDeviceOwner') ?? false;
  }

  /// Check if the device is currently in Lock Task (kiosk) mode.
  Future<bool> isInKioskMode() async {
    return await _channel.invokeMethod<bool>('isInKioskMode') ?? false;
  }

  /// Enter kiosk mode with optional configuration.
  ///
  /// Requires Device Owner privileges. Whitelists the app package
  /// for Lock Task Mode and starts the lock task.
  Future<void> enableKioskMode({KioskConfig? config}) async {
    await _channel.invokeMethod('enableKioskMode', config?.toMap());
  }

  /// Exit kiosk mode.
  ///
  /// Can only be called programmatically or via admin action.
  Future<void> disableKioskMode() async {
    await _channel.invokeMethod('disableKioskMode');
  }

  /// Configure which system UI features are available in kiosk mode.
  /// Android 9+ only.
  Future<void> setKioskFeatures(Set<KioskFeature> features) async {
    await _channel.invokeMethod(
      'setKioskFeatures',
      features.map((f) => f.value).toList(),
    );
  }

  /// Stream of kiosk mode state changes.
  Stream<bool> get onKioskModeChanged {
    return const EventChannel('ao.gabrielvieira.fkiosk/kiosk_mode_events')
        .receiveBroadcastStream()
        .map((event) => event as bool);
  }
}
```

```dart
/// Configuration for kiosk mode behavior.
class KioskConfig {
  /// Allow status bar info (time, battery, connectivity).
  final bool showStatusBar;

  /// Allow system notifications to appear.
  final bool showNotifications;

  /// Allow the home button.
  final bool enableHomeButton;

  /// Allow the overview/recents button.
  final bool enableOverviewButton;

  /// Allow the power button global actions dialog.
  final bool enablePowerButton;

  /// Additional packages allowed to run alongside the kiosk app.
  final List<String> allowedPackages;

  const KioskConfig({
    this.showStatusBar = false,
    this.showNotifications = false,
    this.enableHomeButton = false,
    this.enableOverviewButton = false,
    this.enablePowerButton = false,
    this.allowedPackages = const [],
  });

  Map<String, dynamic> toMap() => {
    'showStatusBar': showStatusBar,
    'showNotifications': showNotifications,
    'enableHomeButton': enableHomeButton,
    'enableOverviewButton': enableOverviewButton,
    'enablePowerButton': enablePowerButton,
    'allowedPackages': allowedPackages,
  };
}

/// System UI features available in kiosk mode (Android 9+).
enum KioskFeature {
  systemInfo(1),
  notifications(2),
  home(4),
  overview(8),
  globalActions(16),
  keyguard(32);

  final int value;
  const KioskFeature(this.value);
}
```

### Android Native Implementation (Kotlin)

#### DeviceAdminReceiver

```kotlin
// AdminReceiver.kt
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device admin enabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        // Called when Device Owner provisioning completes
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as DevicePolicyManager
        val componentName = getComponentName(context)

        // Enable the profile
        manager.setProfileName(componentName, "Kiosk Device")
        manager.setProfileEnabled(componentName)
    }

    companion object {
        private const val TAG = "FKioskAdminReceiver"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, AdminReceiver::class.java)
        }
    }
}
```

#### Kiosk Mode Handler

```kotlin
// KioskModeHandler.kt
class KioskModeHandler(
    private val activity: Activity,
    private val dpm: DevicePolicyManager,
    private val adminComponent: ComponentName
) : MethodChannel.MethodCallHandler {

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isDeviceOwner" -> {
                result.success(dpm.isDeviceOwnerApp(activity.packageName))
            }

            "isInKioskMode" -> {
                val activityManager = activity.getSystemService(
                    Context.ACTIVITY_SERVICE
                ) as ActivityManager
                result.success(
                    activityManager.lockTaskModeState
                        != ActivityManager.LOCK_TASK_MODE_NONE
                )
            }

            "enableKioskMode" -> {
                try {
                    enableKiosk(call.arguments as? Map<String, Any>)
                    result.success(null)
                } catch (e: Exception) {
                    result.error("KIOSK_ERROR", e.message, null)
                }
            }

            "disableKioskMode" -> {
                try {
                    disableKiosk()
                    result.success(null)
                } catch (e: Exception) {
                    result.error("KIOSK_ERROR", e.message, null)
                }
            }

            "setKioskFeatures" -> {
                val features = call.arguments as? List<Int> ?: emptyList()
                setFeatures(features)
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun enableKiosk(config: Map<String, Any>?) {
        if (!dpm.isDeviceOwnerApp(activity.packageName)) {
            throw IllegalStateException("App is not Device Owner")
        }

        // Collect packages to whitelist
        val packages = mutableListOf(activity.packageName)
        val additional = config?.get("allowedPackages") as? List<String>
        if (additional != null) packages.addAll(additional)

        // Whitelist packages for Lock Task Mode
        dpm.setLockTaskPackages(adminComponent, packages.toTypedArray())

        // Configure Lock Task features (available since API 28, guarded below that)
        var flags = DevicePolicyManager.LOCK_TASK_FEATURE_NONE
        if (config?.get("showStatusBar") == true)
            flags = flags or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
        if (config?.get("showNotifications") == true)
            flags = flags or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
        if (config?.get("enableHomeButton") == true)
            flags = flags or DevicePolicyManager.LOCK_TASK_FEATURE_HOME
        if (config?.get("enableOverviewButton") == true)
            flags = flags or DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW
        if (config?.get("enablePowerButton") == true)
            flags = flags or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS

        dpm.setLockTaskFeatures(adminComponent, flags)

        // Start Lock Task Mode
        activity.startLockTask()
    }

    private fun disableKiosk() {
        activity.stopLockTask()
    }

    private fun setFeatures(featureValues: List<Int>) {
        var flags = 0
        for (value in featureValues) flags = flags or value
        dpm.setLockTaskFeatures(adminComponent, flags)
    }
}
```

#### AndroidManifest.xml Declarations

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="ao.gabrielvieira.fkiosk">

    <application>
        <!-- Device Admin Receiver -->
        <receiver
            android:name=".dpc.AdminReceiver"
            android:permission="android.permission.BIND_DEVICE_ADMIN"
            android:exported="true">
            <meta-data
                android:name="android.app.device_admin"
                android:resource="@xml/device_admin_receiver" />
            <intent-filter>
                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
                <action android:name="android.app.action.PROFILE_PROVISIONING_COMPLETE" />
            </intent-filter>
        </receiver>

        <!-- Main Activity with Lock Task support -->
        <activity
            android:name=".kiosk.KioskActivity"
            android:lockTaskMode="if_whitelisted"
            android:launchMode="singleTask"
            android:exported="true">
        </activity>
    </application>
</manifest>
```

```xml
<!-- res/xml/device_admin_receiver.xml -->
<device-admin>
    <uses-policies>
        <limit-password />
        <watch-login />
        <reset-password />
        <force-lock />
        <wipe-data />
        <expire-password />
        <encrypted-storage />
        <disable-camera />
        <disable-keyguard-features />
    </uses-policies>
</device-admin>
```

### Kiosk Mode State Machine

```
                    +-----------------+
                    |   NOT_PROVISIONED|
                    |  (No Device Owner)|
                    +--------+--------+
                             |
                   Device Owner Provisioning
                   (QR code / NFC / ADB)
                             |
                             v
                    +--------+--------+
                    |   PROVISIONED    |
                    |  (Device Owner)  |
                    +--------+--------+
                             |
                   enableKioskMode()
                             |
                             v
                    +--------+--------+
              +---->|   KIOSK_ACTIVE   |<----+
              |     | (Lock Task Mode) |     |
              |     +--------+--------+     |
              |              |              |
         App crash/      disableKioskMode() |
         reboot auto-       |              Remote
         restart             v              re-enable
              |     +--------+--------+     |
              +-----|  KIOSK_PAUSED    |-----+
                    | (Unlocked temp)  |
                    +-----------------+
```

---

## Plugin 2: Silent Update Plugin

### Dart API

```dart
/// Manages silent (background) app updates on Device Owner managed devices.
class SilentUpdatePlugin {
  static const _channel = MethodChannel('ao.gabrielvieira.fkiosk/silent_update');
  static const _eventChannel = EventChannel('ao.gabrielvieira.fkiosk/update_events');

  /// Check if silent install is available (requires Device Owner).
  Future<bool> canSilentInstall() async {
    return await _channel.invokeMethod<bool>('canSilentInstall') ?? false;
  }

  /// Install an APK silently from a local file path.
  ///
  /// The APK must already be downloaded to the device.
  /// Returns a session ID to track installation progress.
  Future<int> installApk(String apkPath) async {
    return await _channel.invokeMethod<int>('installApk', {
      'apkPath': apkPath,
    }) ?? -1;
  }

  /// Download and install an APK from a URL silently.
  ///
  /// Handles download + installation in one step.
  Future<int> installFromUrl(String url, {Map<String, String>? headers}) async {
    return await _channel.invokeMethod<int>('installFromUrl', {
      'url': url,
      'headers': headers,
    }) ?? -1;
  }

  /// Uninstall a package silently.
  Future<bool> uninstallPackage(String packageName) async {
    return await _channel.invokeMethod<bool>(
      'uninstallPackage', {'packageName': packageName}
    ) ?? false;
  }

  /// Get current app version info.
  Future<Map<String, String>> getVersionInfo() async {
    final result = await _channel.invokeMethod<Map>('getVersionInfo');
    return result?.cast<String, String>() ?? {};
  }

  /// Check for updates from the configured update server.
  Future<UpdateInfo?> checkForUpdate() async {
    final result = await _channel.invokeMethod<Map>('checkForUpdate');
    if (result == null) return null;
    return UpdateInfo.fromMap(result.cast<String, dynamic>());
  }

  /// Stream of update/installation progress events.
  Stream<UpdateStatus> get onUpdateStatus {
    return _eventChannel
        .receiveBroadcastStream()
        .map((event) => UpdateStatus.fromMap(
            Map<String, dynamic>.from(event as Map)));
  }
}

/// Information about an available update.
class UpdateInfo {
  final String version;
  final String downloadUrl;
  final int fileSize;
  final String checksum;
  final bool mandatory;

  const UpdateInfo({
    required this.version,
    required this.downloadUrl,
    required this.fileSize,
    required this.checksum,
    this.mandatory = false,
  });

  factory UpdateInfo.fromMap(Map<String, dynamic> map) => UpdateInfo(
    version: map['version'] as String,
    downloadUrl: map['downloadUrl'] as String,
    fileSize: map['fileSize'] as int,
    checksum: map['checksum'] as String,
    mandatory: map['mandatory'] as bool? ?? false,
  );
}

/// Status of an ongoing update operation.
class UpdateStatus {
  final UpdateState state;
  final int sessionId;
  final double progress;   // 0.0 - 1.0
  final String? error;

  const UpdateStatus({
    required this.state,
    required this.sessionId,
    this.progress = 0.0,
    this.error,
  });

  factory UpdateStatus.fromMap(Map<String, dynamic> map) => UpdateStatus(
    state: UpdateState.values.byName(map['state'] as String),
    sessionId: map['sessionId'] as int,
    progress: (map['progress'] as num?)?.toDouble() ?? 0.0,
    error: map['error'] as String?,
  );
}

enum UpdateState {
  downloading,
  installing,
  success,
  failed,
}
```

### Android Native Implementation (Kotlin)

#### Silent Install via PackageInstaller

```kotlin
// PackageInstallerHelper.kt
class PackageInstallerHelper(private val context: Context) {

    fun installApk(apkFile: File): Int {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(context.packageName)

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        // Write APK to session
        session.openWrite("app.apk", 0, apkFile.length()).use { output ->
            apkFile.inputStream().use { input ->
                input.copyTo(output)
            }
            session.fsync(output)
        }

        // Create a status receiver intent
        val intent = Intent(context, UpdateReceiver::class.java).apply {
            action = ACTION_INSTALL_STATUS
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, sessionId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // Commit the session (triggers silent install for Device Owner)
        session.commit(pendingIntent.intentSender)

        return sessionId
    }

    fun uninstallPackage(packageName: String): Boolean {
        return try {
            val intent = Intent(context, UpdateReceiver::class.java).apply {
                action = ACTION_UNINSTALL_STATUS
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            context.packageManager.packageInstaller.uninstall(
                packageName, pendingIntent.intentSender
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS =
            "ao.gabrielvieira.fkiosk.ACTION_INSTALL_STATUS"
        const val ACTION_UNINSTALL_STATUS =
            "ao.gabrielvieira.fkiosk.ACTION_UNINSTALL_STATUS"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
```

#### Install Status BroadcastReceiver

```kotlin
// UpdateReceiver.kt
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val sessionId = intent.getIntExtra(
            PackageInstallerHelper.EXTRA_SESSION_ID, -1
        )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Should NOT happen for Device Owner - indicates
                // the app is not Device Owner or not whitelisted
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                notifyFlutter(context, sessionId, "success", 1.0, null)
            }
            else -> {
                val message = intent.getStringExtra(
                    PackageInstaller.EXTRA_STATUS_MESSAGE
                )
                notifyFlutter(context, sessionId, "failed", 0.0, message)
            }
        }
    }

    private fun notifyFlutter(
        context: Context,
        sessionId: Int,
        state: String,
        progress: Double,
        error: String?
    ) {
        // Send event to Flutter via EventChannel sink
        // (implementation via a singleton event emitter)
        UpdateEventEmitter.send(mapOf(
            "sessionId" to sessionId,
            "state" to state,
            "progress" to progress,
            "error" to error,
        ))
    }
}
```

### Silent Update Sequence Diagram

```
Backend Server          Device (DPC)           PackageInstaller        Flutter App
     |                      |                        |                      |
     |-- Push notification ->|                        |                      |
     |   (FCM / polling)    |                        |                      |
     |                      |-- Download APK -------->|                      |
     |                      |   (HTTPS + checksum)   |                      |
     |                      |                        |                      |
     |                      |-- createSession() ---->|                      |
     |                      |-- openWrite() -------->|                      |
     |                      |-- commit() ----------->|                      |
     |                      |                        |                      |
     |                      |                        |-- (silent install) -->|
     |                      |                        |                      |
     |                      |<-- STATUS_SUCCESS -----|                      |
     |                      |                        |                      |
     |                      |--- EventChannel -------|----> onUpdateStatus  |
     |                      |                        |      (success)       |
     |                      |                        |                      |
     |                      |-- Restart app -------->|----> App relaunches  |
     |                      |   (in kiosk mode)      |      with new version|
```

### Update Strategies

| Strategy | Mechanism | Pros | Cons |
|---|---|---|---|
| **Managed Google Play** | AMAPI `autoUpdateMode: AUTO_UPDATE_HIGH_PRIORITY` | Zero custom infra, Google handles delivery | Requires Play Store, slower rollout |
| **Custom DPC + PackageInstaller** | Direct APK install via `PackageInstaller` | Full control, instant updates, works offline | Must build update server, manage signing |
| **Hybrid** | Play Store for base + DPC for hotfixes | Best of both | More complexity |

#### AMAPI Auto-Update Policy Options

```json
{
  "applications": [
    {
      "packageName": "com.example.kioskapp",
      "installType": "KIOSK",
      "autoUpdateMode": "AUTO_UPDATE_HIGH_PRIORITY",
      "defaultPermissionPolicy": "GRANT"
    }
  ]
}
```

| Mode | Behavior |
|---|---|
| `AUTO_UPDATE_DEFAULT` | Updates when on Wi-Fi, charging, idle, app not in foreground |
| `AUTO_UPDATE_POSTPONED` | Delays updates up to 90 days |
| `AUTO_UPDATE_HIGH_PRIORITY` | Updates as soon as available, may force restart |

---

## Device Provisioning

### Provisioning Methods

| Method | Description | Best For |
|---|---|---|
| **QR Code** | Scan QR at factory reset setup wizard | Medium-scale deployments |
| **NFC Bump** | NFC tag with provisioning data | Bulk provisioning stations |
| **Zero-Touch** | Pre-configured via reseller portal | Large-scale fleet |
| **ADB** | `adb shell dpm set-device-owner` | Development / testing |

### QR Code Provisioning Payload

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
    "ao.gabrielvieira.fkiosk/.dpc.AdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":
    "https://your-server.com/dpc.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM":
    "base64-encoded-sha256-checksum",
  "android.app.extra.PROVISIONING_WIFI_SSID": "KioskNetwork",
  "android.app.extra.PROVISIONING_WIFI_PASSWORD": "password",
  "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE": "WPA",
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true,
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": false
}
```

### ADB Provisioning (Development)

```bash
# 1. Install the app
adb install -r app-release.apk

# 2. Set as Device Owner (device must have no accounts)
adb shell dpm set-device-owner ao.gabrielvieira.fkiosk/.dpc.AdminReceiver

# 3. Verify
adb shell dumpsys device_policy | grep "Device Owner"
```

---

## Remote Management Backend

### Architecture Options

#### Option A: Android Management API (AMAPI) - Recommended

Google's hosted EMM solution. No server infrastructure to manage.

```
Admin Console (Web)
       |
       v
Google AMAPI  ───>  Enterprise ───>  Policy ───>  Device
  (REST API)         Object          Object        Enrollment
```

**Key endpoints:**

```
POST   /v1/enterprises                              # Create enterprise
POST   /v1/enterprises/{id}/policies                 # Create/update policy
POST   /v1/enterprises/{id}/enrollmentTokens         # Generate enrollment token
GET    /v1/enterprises/{id}/devices                  # List devices
PATCH  /v1/enterprises/{id}/devices/{deviceId}       # Apply policy to device
```

**Kiosk policy example via AMAPI:**

```json
{
  "name": "enterprises/LC0xxx/policies/kiosk-policy",
  "applications": [
    {
      "packageName": "ao.gabrielvieira.fkiosk.app",
      "installType": "KIOSK",
      "autoUpdateMode": "AUTO_UPDATE_HIGH_PRIORITY",
      "defaultPermissionPolicy": "GRANT"
    }
  ],
  "kioskCustomization": {
    "powerButtonActions": "POWER_BUTTON_AVAILABLE",
    "statusBar": "NOTIFICATIONS_AND_SYSTEM_INFO_DISABLED",
    "systemNavigation": "NAVIGATION_DISABLED",
    "systemErrorWarnings": "ERROR_AND_WARNINGS_MUTED",
    "deviceSettings": "SETTINGS_ACCESS_BLOCKED"
  },
  "statusBarDisabled": true,
  "keyguardDisabled": true,
  "persistentPreferredActivities": [
    {
      "receiverActivity": "ao.gabrielvieira.fkiosk.app/.MainActivity",
      "actions": ["android.intent.action.MAIN"],
      "categories": ["android.intent.category.HOME", "android.intent.category.DEFAULT"]
    }
  ]
}
```

#### Option B: Custom Backend

For full control or air-gapped environments.

```
+-------------------+     +------------------+     +------------------+
|  Admin Dashboard   |     |  API Server      |     |  APK Storage     |
|  (React/Flutter)   |---->|  (Node/Go/etc)   |---->|  (S3/GCS/local)  |
+-------------------+     +------------------+     +------------------+
                                   |
                              FCM / HTTPS
                                   |
                                   v
                          +------------------+
                          |  Device Fleet     |
                          |  (DPC + App)      |
                          +------------------+
```

**Custom update server API:**

```
GET  /api/v1/updates/check?pkg={packageName}&version={currentVersion}
  -> { "available": true, "version": "2.1.0", "url": "...", "checksum": "...", "mandatory": true }

GET  /api/v1/updates/download/{version}
  -> APK binary stream

POST /api/v1/devices/register
  -> Register device with fleet

POST /api/v1/devices/{id}/status
  -> Report device status (version, battery, connectivity)
```

---

## Security Considerations

### APK Integrity

- **Always verify APK checksum** (SHA-256) before installing.
- Sign APKs with a private key stored in a hardware security module (HSM) or secure vault.
- Use APK Signature Scheme v2+ for tamper detection.

### Communication Security

- All device-to-server communication over **TLS 1.3**.
- Use **certificate pinning** in the DPC to prevent MITM attacks.
- Authenticate devices using per-device tokens or mutual TLS.

### Device Security Policies

```kotlin
// Recommended security policies for kiosk devices
fun applySecurityPolicies(dpm: DevicePolicyManager, admin: ComponentName) {
    // Disable USB debugging
    dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)

    // Disable factory reset
    dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)

    // Disable safe boot
    dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)

    // Disable adding new users
    dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER)

    // Disable installing apps from unknown sources
    dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)

    // Disable USB file transfer
    dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)

    // Set the app as the default launcher
    dpm.addPersistentPreferredActivity(
        admin,
        IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        },
        ComponentName(context.packageName, "${context.packageName}.MainActivity")
    )
}
```

### Kiosk Escape Prevention

| Vector | Mitigation |
|---|---|
| Power button long-press | `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` = disabled |
| Status bar pull-down | `statusBarDisabled = true` |
| Navigation buttons | `LOCK_TASK_FEATURE_HOME` / `OVERVIEW` = disabled |
| USB keyboard shortcuts | Disable `DISALLOW_DEBUGGING_FEATURES` |
| Factory reset | `DISALLOW_FACTORY_RESET` |
| Safe mode boot | `DISALLOW_SAFE_BOOT` |
| App crash exits kiosk | Auto-restart via `persistentPreferredActivities` + lock task `if_whitelisted` |

---

## Deployment Flow

### End-to-End Deployment

```
1. BUILD & SIGN
   Flutter build -> APK/AAB signed with release key

2. UPLOAD
   Push to Managed Google Play (private track)
   OR upload to custom update server

3. PROVISION DEVICES
   Factory reset -> QR code scan -> DPC installs -> Device Owner set

4. APPLY POLICY
   AMAPI policy pushed (or DPC self-configures)
   -> App installs as KIOSK type
   -> Lock Task Mode activated
   -> Security restrictions applied

5. OPERATE
   Device runs in kiosk mode
   Periodic health check-ins to backend
   Remote config changes via Managed Configurations

6. UPDATE
   New version uploaded to Play / update server
   -> AUTO_UPDATE_HIGH_PRIORITY triggers install
   -> OR DPC downloads + PackageInstaller silent install
   -> App restarts in kiosk mode automatically
```

### CI/CD Integration

```yaml
# Example GitHub Actions workflow
name: Build & Deploy Kiosk App

on:
  push:
    tags: ['v*']

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: subosito/flutter-action@v2
        with:
          flutter-version: '3.x'

      - run: flutter build apk --release

      - name: Sign APK
        run: |
          jarsigner -keystore ${{ secrets.KEYSTORE_PATH }} \
            -storepass ${{ secrets.KEYSTORE_PASSWORD }} \
            build/app/outputs/flutter-apk/app-release.apk \
            ${{ secrets.KEY_ALIAS }}

      - name: Upload to Update Server
        run: |
          curl -X POST https://your-server.com/api/v1/updates/upload \
            -H "Authorization: Bearer ${{ secrets.API_TOKEN }}" \
            -F "apk=@build/app/outputs/flutter-apk/app-release.apk" \
            -F "version=${{ github.ref_name }}"
```

---

## API Reference

### Kiosk Mode Plugin - Method Channel: `ao.gabrielvieira.fkiosk/kiosk_mode`

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `isDeviceOwner` | none | `bool` | Check if app is Device Owner |
| `isInKioskMode` | none | `bool` | Check if Lock Task is active |
| `enableKioskMode` | `Map<String, dynamic>?` (KioskConfig) | `void` | Start Lock Task Mode |
| `disableKioskMode` | none | `void` | Stop Lock Task Mode |
| `setKioskFeatures` | `List<int>` (feature flags) | `void` | Configure kiosk UI features |

### Silent Update Plugin - Method Channel: `ao.gabrielvieira.fkiosk/silent_update`

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `canSilentInstall` | none | `bool` | Check Device Owner status |
| `installApk` | `{apkPath: String}` | `int` (sessionId) | Install APK from local path |
| `installFromUrl` | `{url: String, headers?: Map}` | `int` (sessionId) | Download and install APK |
| `uninstallPackage` | `{packageName: String}` | `bool` | Silent uninstall |
| `getVersionInfo` | none | `Map<String, String>` | Current version details |
| `checkForUpdate` | none | `Map?` (UpdateInfo) | Query update server |

### Event Channels

| Channel | Event Type | Description |
|---|---|---|
| `ao.gabrielvieira.fkiosk/kiosk_mode_events` | `bool` | Kiosk mode state changes |
| `ao.gabrielvieira.fkiosk/update_events` | `UpdateStatus` map | Install progress & status |

---

## Appendix

### Minimum Android Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<!-- WRITE_EXTERNAL_STORAGE not needed: APKs are downloaded to the app cache dir -->
```

### Useful ADB Commands for Development

```bash
# Set Device Owner
adb shell dpm set-device-owner ao.gabrielvieira.fkiosk/.dpc.AdminReceiver

# Remove Device Owner (requires app cooperation)
adb shell dpm remove-active-admin ao.gabrielvieira.fkiosk/.dpc.AdminReceiver

# Check current Device Owner
adb shell dumpsys device_policy

# Force stop kiosk app (for debugging only)
adb shell am force-stop ao.gabrielvieira.fkiosk

# List Lock Task packages
adb shell dumpsys activity | grep "mLockTaskPackages"

# Check Lock Task state
adb shell dumpsys activity | grep "mLockTaskMode"
```

### Dependencies

```yaml
# pubspec.yaml
name: fkiosk
description: Flutter plugins for Android Enterprise kiosk mode and silent updates.

environment:
  sdk: '>=3.0.0 <4.0.0'
  flutter: '>=3.10.0'

dependencies:
  flutter:
    sdk: flutter

dev_dependencies:
  flutter_test:
    sdk: flutter
  flutter_lints: ^5.0.0

flutter:
  plugin:
    platforms:
      android:
        package: ao.gabrielvieira.fkiosk
        pluginClass: FKioskPlugin
```

```kotlin
// android/build.gradle.kts
android {
    compileSdk = 34
    defaultConfig {
        minSdk = 24  // Android 7.0+; fine-grained kiosk feature toggles need API 28+
        targetSdk = 34
    }
}
```

### References

- [Android Lock Task Mode](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode)
- [Android Management API](https://developers.google.com/android/management)
- [Dedicated Device Policies](https://developers.google.com/android/management/policies/dedicated-devices)
- [Flutter Platform Channels](https://docs.flutter.dev/platform-integration/platform-channels)
- [PackageInstaller API](https://developer.android.com/reference/android/content/pm/PackageInstaller)
- [Build a DPC](https://developer.android.com/develop/enterprise/work/dpc/build-dpc)
