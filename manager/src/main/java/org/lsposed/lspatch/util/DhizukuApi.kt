package org.lsposed.lspatch.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import org.lsposed.lspatch.IDhizukuService

/**
 * DhizukuApi - thin wrapper to:
 *  - probe Dhizuku SDK availability/permission (via reflection if SDK absent)
 *  - provide a synchronous installApk(context, apkPath) helper that binds to the local
 *    IDhizukuService (the AIDL service implemented in the manager module) and calls installApk(...)
 *
 * Notes:
 *  - This is intentionally separate from ShizukuApi to keep logic identical to the original app.
 *  - The binding target is the local DhizukuService (org.lsposed.lspatch.DhizukuService).
 *  - Timeouts are enforced to avoid hangs.
 */
object DhizukuApi {

    var isAvailable = false
    var isPermissionGranted by mutableStateOf(false)

    private const val BIND_TIMEOUT_MS = 30_000L
    private const val CALL_TIMEOUT_MS = 30_000L

    /**
     * Try to initialize Dhizuku SDK (if present). Uses reflection so that module doesn't
     * crash if dependency is missing. Sets isAvailable/isPermissionGranted flags.
     */
    fun init(context: Context) {
        try {
            val clazz = Class.forName("com.rosan.dhizuku.api.Dhizuku")
            // Dhizuku.init(context) -> boolean
            val mInit = clazz.getMethod("init", Context::class.java)
            val initOk = (mInit.invoke(null, context) as? Boolean) ?: false
            isAvailable = true
            // Dhizuku.isPermissionGranted()
            val mPerm = try { clazz.getMethod("isPermissionGranted") } catch (t: Throwable) { null }
            isPermissionGranted = (mPerm?.invoke(null) as? Boolean) ?: false
        } catch (t: Throwable) {
            // SDK absent or reflection failed
            isAvailable = false
            isPermissionGranted = false
        }
    }

    /**
     * Demande la permission à Dhizuku.
     * Appelé depuis l'interface (SettingsScreen).
     */
    fun requestPermission(activity: android.app.Activity, callback: (Boolean) -> Unit) {
        try {
            val clazz = Class.forName("com.rosan.dhizuku.api.Dhizuku")
            val mRequest = clazz.getMethod(
                "requestPermission",
                com.rosan.dhizuku.api.DhizukuRequestPermissionCallback::class.java
            )

            // Création du callback via le SDK (réflexion)
            val proxy = com.rosan.dhizuku.api.DhizukuRequestPermissionCallback { _, resultCode ->
                val granted = resultCode == android.content.pm.PackageManager.PERMISSION_GRANTED
                isPermissionGranted = granted
                callback(granted)
            }

            mRequest.invoke(null, proxy)
        } catch (t: Throwable) {
            t.printStackTrace()
            callback(false)
        }
    }

    /**
     * Synchronous helper to install an APK via the local Dhizuku service (AIDL).
     * Binds to the service, calls installApk(apkPath) and returns the response string.
     *
     * This method blocks the calling thread up to BIND_TIMEOUT_MS. Call from background thread.
     */
    fun installApk(context: Context, apkPath: String, timeoutMs: Long = CALL_TIMEOUT_MS): String {
        // Prefer SDK permission check if possible
        if (!probeDhizukuPermission()) {
            return "ERROR: Dhizuku not available or permission not granted."
        }

        // bind to local DhizukuService (in this package)
        val intent = Intent().apply {
            component = ComponentName(context.packageName, "org.lsposed.lspatch.DhizukuService")
        }

        val deferred = CompletableDeferred<String>()

        val handler = Handler(Looper.getMainLooper())

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    val aidl = IDhizukuService.Stub.asInterface(service)
                    // call on background thread to avoid binder-thread blocking UI thread
                    Thread {
                        try {
                            val resp = aidl.installApk(apkPath)
                            deferred.complete(resp ?: "NULL_RESPONSE")
                        } catch (e: Throwable) {
                            deferred.complete("EXCEPTION: ${e.stackTraceToString()}")
                        } finally {
                            try { context.unbindService(this) } catch (_: Exception) {}
                        }
                    }.start()
                } catch (e: Throwable) {
                    deferred.complete("EXCEPTION: ${e.stackTraceToString()}")
                    try { context.unbindService(this) } catch (_: Exception) {}
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!deferred.isCompleted) deferred.complete("ERROR: service disconnected")
            }
        }

        // bind (must run on main looper) — we post to main if not on it
        val bindResult = if (Looper.myLooper() == Looper.getMainLooper()) {
            try { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) } catch (t: Throwable) { false }
        } else {
            // post to main looper and wait briefly for bind() result
            val bindDeferred = CompletableDeferred<Boolean>()
            handler.post {
                try {
                    val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                    bindDeferred.complete(ok)
                } catch (t: Throwable) {
                    bindDeferred.complete(false)
                }
            }
            runBlocking {
                withTimeoutOrNull(BIND_TIMEOUT_MS) { bindDeferred.await() } ?: false
            }
        }

        if (!bindResult) {
            return "ERROR: bindService returned false"
        }

        // wait for response with timeout
        val result = runBlocking {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
        } ?: "ERROR: Dhizuku call timed out after ${timeoutMs}ms"

        return result
    }

    /**
     * Run an arbitrary shell command through the local Dhizuku service.
     * Returns stdout/stderr formatted string or an error message.
     */
    fun runShellCommandWithDhizuku(context: Context, cmd: String, timeoutMs: Long = CALL_TIMEOUT_MS): String {
        if (!probeDhizukuPermission()) {
            return "ERROR: Dhizuku not available or permission not granted."
        }

        val intent = Intent().apply {
            component = ComponentName(context.packageName, "org.lsposed.lspatch.DhizukuService")
        }

        val deferred = CompletableDeferred<String>()
        val handler = Handler(Looper.getMainLooper())

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    val aidl = IDhizukuService.Stub.asInterface(service)
                    Thread {
                        try {
                            val resp = aidl.runShellCommand(cmd)
                            deferred.complete(resp ?: "NULL_RESPONSE")
                        } catch (e: Throwable) {
                            deferred.complete("EXCEPTION: ${e.stackTraceToString()}")
                        } finally {
                            try { context.unbindService(this) } catch (_: Exception) {}
                        }
                    }.start()
                } catch (e: Throwable) {
                    deferred.complete("EXCEPTION: ${e.stackTraceToString()}")
                    try { context.unbindService(this) } catch (_: Exception) {}
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!deferred.isCompleted) deferred.complete("ERROR: service disconnected")
            }
        }

        val bindResult = if (Looper.myLooper() == Looper.getMainLooper()) {
            try { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) } catch (t: Throwable) { false }
        } else {
            val bindDeferred = CompletableDeferred<Boolean>()
            handler.post {
                try {
                    val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                    bindDeferred.complete(ok)
                } catch (t: Throwable) {
                    bindDeferred.complete(false)
                }
            }
            runBlocking {
                withTimeoutOrNull(BIND_TIMEOUT_MS) { bindDeferred.await() } ?: false
            }
        }

        if (!bindResult) {
            return "ERROR: bindService returned false"
        }

        val result = runBlocking {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
        } ?: "ERROR: Dhizuku call timed out after ${timeoutMs}ms"

        return result
    }

    /**
     * Probe whether Dhizuku SDK is present and permission granted.
     * Uses reflection so absence of SDK won't crash.
     */
    private fun probeDhizukuPermission(): Boolean {
        try {
            val clazz = Class.forName("com.rosan.dhizuku.api.Dhizuku")
            val mPerm = clazz.getMethod("isPermissionGranted")
            val allowed = mPerm.invoke(null) as? Boolean ?: false
            isAvailable = true
            isPermissionGranted = allowed
            return allowed
        } catch (t: Throwable) {
            isAvailable = false
            isPermissionGranted = false
            return false
        }
    }
}