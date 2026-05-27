import 'kiosk_mode_plugin.dart';
import 'models/kiosk_config.dart';
import 'models/update_status.dart';
import 'silent_update_plugin.dart';

/// Unified facade over [KioskModePlugin] and [SilentUpdatePlugin].
///
/// Provides a single entry point for all kiosk and silent-update operations
/// on Android Enterprise Device Owner deployments.
class KioskService {
  final KioskModePlugin _kioskMode = KioskModePlugin();
  final SilentUpdatePlugin _silentUpdate = SilentUpdatePlugin();

  Future<bool> isDeviceOwner() => _kioskMode.isDeviceOwner();
  Future<bool> isInKioskMode() => _kioskMode.isInKioskMode();

  Future<void> enableKioskMode({KioskConfig? config}) =>
      _kioskMode.enableKioskMode(config: config);
  Future<void> disableKioskMode() => _kioskMode.disableKioskMode();

  Future<void> enableAutoStart() => _kioskMode.enableAutoStart();
  Future<void> disableAutoStart() => _kioskMode.disableAutoStart();

  Future<void> rebootDevice() => _kioskMode.rebootDevice();
  Future<void> shutdownDevice() => _kioskMode.shutdownDevice();

  Future<void> uninstallApp(String packageName) =>
      _kioskMode.uninstallApp(packageName);
  Future<bool> setAppHidden(String packageName, {bool hidden = true}) =>
      _kioskMode.setAppHidden(packageName, hidden: hidden);
  Future<void> wipeData() => _kioskMode.wipeData();
  Future<void> clearDeviceOwner() => _kioskMode.clearDeviceOwner();

  Future<bool> canSilentInstall() => _silentUpdate.canSilentInstall();
  Future<int> installFromUrl(String url) => _silentUpdate.installFromUrl(url);
  Future<Map<String, String>> getVersionInfo() => _silentUpdate.getVersionInfo();
  Future<UpdateInfo?> checkForUpdate(String url) =>
      _silentUpdate.checkForUpdate(url: url);
  Stream<UpdateStatus> get onUpdateStatus => _silentUpdate.onUpdateStatus;
}
