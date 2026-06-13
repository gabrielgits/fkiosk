package ao.gabrielvieira.fkiosk.update

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageInfo
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class SilentUpdateHandler(
    private val context: Context,
    private val dpm: DevicePolicyManager
) : MethodChannel.MethodCallHandler {

    private val client = OkHttpClient()
    private val installerHelper = PackageInstallerHelper(context)

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "canSilentInstall" -> {
                result.success(dpm.isDeviceOwnerApp(context.packageName))
            }

            "installApk" -> {
                val apkPath = call.argument<String>("apkPath")
                if (apkPath == null) {
                    result.error("INVALID_ARG", "apkPath is required", null)
                    return
                }
                try {
                    val sessionId = installerHelper.installApk(File(apkPath))
                    UpdateEventEmitter.send(
                        mapOf(
                            "sessionId" to sessionId,
                            "state" to "installing",
                            "progress" to 0.0,
                            "error" to null,
                        )
                    )
                    result.success(sessionId)
                } catch (e: Exception) {
                    result.error("INSTALL_ERROR", e.message, null)
                }
            }

            "installFromUrl" -> {
                val url = call.argument<String>("url")
                if (url == null) {
                    result.error("INVALID_ARG", "url is required", null)
                    return
                }
                @Suppress("UNCHECKED_CAST")
                val headers = call.argument<Map<String, String>>("headers")
                Thread {
                    try {
                        val apkFile = downloadApk(url, headers)
                        val sessionId = installerHelper.installApk(apkFile)
                        UpdateEventEmitter.send(
                            mapOf(
                                "sessionId" to sessionId,
                                "state" to "installing",
                                "progress" to 0.5,
                                "error" to null,
                            )
                        )
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.success(sessionId)
                        }
                    } catch (e: Exception) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.error("INSTALL_ERROR", e.message, null)
                        }
                    }
                }.start()
            }

            "uninstallPackage" -> {
                val packageName = call.argument<String>("packageName")
                if (packageName == null) {
                    result.error("INVALID_ARG", "packageName is required", null)
                    return
                }
                result.success(installerHelper.uninstallPackage(packageName))
            }

            "getVersionInfo" -> {
                try {
                    val packageInfo: PackageInfo = context.packageManager
                        .getPackageInfo(context.packageName, 0)
                    result.success(
                        mapOf(
                            "packageName" to context.packageName,
                            "versionName" to (packageInfo.versionName ?: ""),
                            "versionCode" to packageInfo.longVersionCode.toString(),
                        )
                    )
                } catch (e: Exception) {
                    result.error("VERSION_ERROR", e.message, null)
                }
            }

            "checkForUpdate" -> {
                val url = call.argument<String>("url")
                if (url == null) {
                    result.success(null)
                    return
                }
                Thread {
                    try {
                        val request = Request.Builder().url(url).build()
                        val response = client.newCall(request).execute()
                        val body = response.body?.string()
                        // Parse off the main thread so a malformed/empty manifest
                        // surfaces as an error instead of crashing the app's main looper.
                        val map = if (body.isNullOrBlank()) null else parseJsonToMap(body)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.success(map)
                        }
                    } catch (e: Exception) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.error("UPDATE_CHECK_ERROR", e.message, null)
                        }
                    }
                }.start()
            }

            else -> result.notImplemented()
        }
    }

    private fun downloadApk(url: String, headers: Map<String, String>?): File {
        val requestBuilder = Request.Builder().url(url)
        headers?.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        UpdateEventEmitter.send(
            mapOf(
                "sessionId" to -1,
                "state" to "downloading",
                "progress" to 0.0,
                "error" to null,
            )
        )

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            throw Exception("Download failed with HTTP ${response.code}")
        }

        val contentLength = response.body?.contentLength() ?: -1L
        val apkFile = File(context.cacheDir, "update.apk")
        val buffer = ByteArray(8 * 1024)
        var bytesRead = 0L

        response.body?.byteStream()?.use { input ->
            apkFile.outputStream().use { output ->
                var bytes = input.read(buffer)
                while (bytes >= 0) {
                    output.write(buffer, 0, bytes)
                    bytesRead += bytes
                    if (contentLength > 0) {
                        UpdateEventEmitter.send(
                            mapOf(
                                "sessionId" to -1,
                                "state" to "downloading",
                                "progress" to bytesRead.toDouble() / contentLength.toDouble(),
                                "error" to null,
                            )
                        )
                    }
                    bytes = input.read(buffer)
                }
            }
        } ?: throw Exception("Empty response body")

        UpdateEventEmitter.send(
            mapOf(
                "sessionId" to -1,
                "state" to "downloading",
                "progress" to 1.0,
                "error" to null,
            )
        )

        return apkFile
    }

    private fun parseJsonToMap(json: String): Map<String, Any?> {
        val obj = org.json.JSONObject(json)
        return obj.keys().asSequence().associateWith { key ->
            when (val value = obj.get(key)) {
                org.json.JSONObject.NULL -> null
                is org.json.JSONObject -> value.toString()
                is org.json.JSONArray -> value.toString()
                else -> value
            }
        }
    }
}
