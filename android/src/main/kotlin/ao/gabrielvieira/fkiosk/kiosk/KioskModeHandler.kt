package ao.gabrielvieira.fkiosk.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class KioskModeHandler(
    private var activity: Activity?,
    private val dpm: DevicePolicyManager,
    private val adminComponent: ComponentName
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private var eventSink: EventChannel.EventSink? = null

    fun setActivity(activity: Activity?) {
        this.activity = activity
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val currentActivity = activity
        if (currentActivity == null && call.method != "isDeviceOwner") {
            result.error("NO_ACTIVITY", "Activity not available", null)
            return
        }

        when (call.method) {
            "isDeviceOwner" -> {
                val pkg = activity?.packageName
                    ?: return result.success(false)
                result.success(dpm.isDeviceOwnerApp(pkg))
            }

            "isInKioskMode" -> {
                val activityManager = currentActivity!!.getSystemService(
                    Context.ACTIVITY_SERVICE
                ) as ActivityManager
                result.success(
                    activityManager.lockTaskModeState
                        != ActivityManager.LOCK_TASK_MODE_NONE
                )
            }

            "enableKioskMode" -> {
                try {
                    @Suppress("UNCHECKED_CAST")
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
                @Suppress("UNCHECKED_CAST")
                val features = call.arguments as? List<Int> ?: emptyList()
                setFeatures(features)
                result.success(null)
            }

            "rebootDevice" -> {
                try {
                    rebootDevice()
                    result.success(null)
                } catch (e: Exception) {
                    result.error("REBOOT_ERROR", e.message, null)
                }
            }

            "shutdownDevice" -> {
                try {
                    shutdownDevice()
                    result.success(null)
                } catch (e: Exception) {
                    result.error("SHUTDOWN_ERROR", e.message, null)
                }
            }

            "enableAutoStart" -> {
                setAutoStart(currentActivity!!, true)
                result.success(null)
            }

            "disableAutoStart" -> {
                setAutoStart(currentActivity!!, false)
                result.success(null)
            }

            "uninstallApp" -> {
                val packageName = call.argument<String>("packageName") ?: run {
                    result.error("INVALID_ARGS", "packageName required", null)
                    return
                }
                uninstallApp(packageName, result)
            }

            "setAppHidden" -> {
                val packageName = call.argument<String>("packageName") ?: run {
                    result.error("INVALID_ARGS", "packageName required", null)
                    return
                }
                val hidden = call.argument<Boolean>("hidden") ?: true
                try {
                    if (!dpm.isDeviceOwnerApp(currentActivity!!.packageName)) {
                        result.error("NOT_DEVICE_OWNER", "App is not Device Owner", null)
                        return
                    }
                    val success = dpm.setApplicationHidden(adminComponent, packageName, hidden)
                    result.success(success)
                } catch (e: Exception) {
                    result.error("APP_HIDDEN_ERROR", e.message, null)
                }
            }

            "wipeData" -> {
                try {
                    wipeData()
                    result.success(null)
                } catch (e: Exception) {
                    result.error("WIPE_ERROR", e.message, null)
                }
            }

            "clearDeviceOwner" -> {
                try {
                    if (!dpm.isDeviceOwnerApp(currentActivity!!.packageName)) {
                        result.error("NOT_DEVICE_OWNER", "App is not Device Owner", null)
                        return
                    }
                    dpm.clearDeviceOwnerApp(currentActivity.packageName)
                    result.success(null)
                } catch (e: Exception) {
                    result.error("CLEAR_OWNER_ERROR", e.message, null)
                }
            }

            else -> result.notImplemented()
        }
    }

    private fun setAutoStart(context: Context, enabled: Boolean) {
        val component = ComponentName(
            context.packageName,
            "ao.gabrielvieira.fkiosk.boot.BootReceiver"
        )
        context.packageManager.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun enableKiosk(config: Map<String, Any>?) {
        val currentActivity = activity
            ?: throw IllegalStateException("Activity not available")

        if (!dpm.isDeviceOwnerApp(currentActivity.packageName)) {
            throw IllegalStateException("App is not Device Owner")
        }

        val packages = mutableListOf(currentActivity.packageName)
        @Suppress("UNCHECKED_CAST")
        val additional = config?.get("allowedPackages") as? List<String>
        if (additional != null) packages.addAll(additional)

        dpm.setLockTaskPackages(adminComponent, packages.toTypedArray())
        // setLockTaskFeatures() was added in API 28 (Android 9). Below that,
        // Lock Task Mode still works but is all-or-nothing (no customization
        // API exists), so the fine-grained toggles are silently ignored.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(adminComponent, LockTaskFeatures.fromConfig(config))
        }

        currentActivity.startLockTask()
        eventSink?.success(true)
    }

    private fun disableKiosk() {
        activity?.stopLockTask()
        // Re-enable ADB and unknown-source installs when kiosk is turned off
        // so the device owner can update or debug the device again.
        if (dpm.isDeviceOwnerApp(activity?.packageName ?: "")) {
            runCatching {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            }
            runCatching {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            }
        }
        eventSink?.success(false)
    }

    private fun rebootDevice() {
        if (!dpm.isDeviceOwnerApp(activity!!.packageName)) {
            throw IllegalStateException("App is not Device Owner")
        }
        dpm.reboot(adminComponent)
    }

    private fun shutdownDevice() {
        if (!dpm.isDeviceOwnerApp(activity!!.packageName)) {
            throw IllegalStateException("App is not Device Owner")
        }
        val commandExitCode = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/reboot", "-p"))
            val exitCode = process.waitFor()
            // Best-effort cleanup; avoid leaking file descriptors.
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            exitCode
        }.getOrNull()

        if (commandExitCode == 0) return

        val fallbackError = runCatching {
            val intent = Intent("com.android.internal.intent.action.REQUEST_SHUTDOWN").apply {
                putExtra("android.intent.extra.KEY_CONFIRM", false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activity!!.startActivity(intent)
        }.exceptionOrNull()

        val cmdMsg = when (commandExitCode) {
            null -> "exec(/system/bin/reboot -p) failed"
            else -> "exec(/system/bin/reboot -p) exited with code=$commandExitCode"
        }
        val fbMsg = fallbackError?.let { "fallback intent failed: ${it::class.java.simpleName}: ${it.message}" }
            ?: "fallback intent started but device did not shutdown (likely blocked by system permissions)"

        throw IllegalStateException("$cmdMsg; $fbMsg")
    }

    private fun uninstallApp(packageName: String, result: MethodChannel.Result) {
        val ctx = activity ?: run {
            result.error("NO_ACTIVITY", "Activity not available", null)
            return
        }
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) {
            result.error("NOT_DEVICE_OWNER", "App is not Device Owner", null)
            return
        }
        val intent = Intent("ao.gabrielvieira.fkiosk.UNINSTALL_COMPLETE").apply {
            setPackage(ctx.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            ctx, packageName.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            ctx.packageManager.packageInstaller.uninstall(packageName, pendingIntent.intentSender)
            result.success(null)
        } catch (e: Exception) {
            result.error("UNINSTALL_ERROR", e.message, null)
        }
    }

    private fun wipeData() {
        if (!dpm.isDeviceOwnerApp(activity!!.packageName)) {
            throw IllegalStateException("App is not Device Owner")
        }
        // WIPE_SILENTLY was added in API 29 (Android 10).
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DevicePolicyManager.WIPE_SILENTLY
        } else {
            0
        }
        @Suppress("DEPRECATION")
        dpm.wipeData(flags)
    }

    private fun setFeatures(featureValues: List<Int>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(adminComponent, LockTaskFeatures.combinedFlags(featureValues))
        }
    }

    // EventChannel.StreamHandler
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }
}
