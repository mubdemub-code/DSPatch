package org.lsposed.lspatch.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.lspatch.IDhizukuService
import java.lang.reflect.Proxy

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

    private const val TAG = "DhizukuApi"

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
            // Dhizuku.init(context) -> boolean (best-effort)
            val mInit = try { clazz.getMethod("init", Context::class.java) } catch (t: Throwable) { null }
            val initOk = if (mInit != null) {
                try {
                    (mInit.invoke(null, context) as? Boolean) ?: false
                } catch (t: Throwable) {
                    Log.e(TAG, "Dhizuku.init invocation failed", t)
                    false
                }
            } else {
                Log.i(TAG, "Dhizuku.init method not found (skipping explicit init)")
                true // if no init method, consider SDK present but rely on probe
            }
            isAvailable = true
            // Dhizuku.isPermissionGranted()
            val mPerm = try { clazz.getMethod("isPermissionGranted") } catch (t: Throwable) { null }
            isPermissionGranted = try { (mPerm?.invoke(null) as? Boolean) ?: false } catch (t: Throwable) {
                Log.e(TAG, "isPermissionGranted invocation failed", t)
                false
            }
            Log.i(TAG, "init: initOk=$initOk isAvailable=$isAvailable isPermissionGranted=$isPermissionGranted")
        } catch (t: Throwable) {
            Log.i(TAG, "Dhizuku SDK not present or reflection failed: ${t.javaClass.simpleName}")
            isAvailable = false
            isPermissionGranted = false
        }
    }

    /**
     * Demande la permission à Dhizuku.
     *
     * Cette version est résiliente :
     *  - essaie plusieurs signatures possibles (no-arg, Activity arg, callback arg)
     *  - logge chaque tentative
     *  - si aucune signature trouvée, appelle callback(false)
     *
     * activity peut être null : si la signature Activity est disponible, la popup risque de ne pas s'afficher
     * correctement sans Activity. Passe l'Activity quand c'est possible.
     */
    fun requestPermission(activity: Activity?, callback: (Boolean) -> Unit) {
        try {
            val clazz = Class.forName("com.rosan.dhizuku.api.Dhizuku")
            val tried = mutableListOf<String>()

            // 1) Try no-arg requestPermission()
            try {
                val m0 = clazz.getMethod("requestPermission")
                m0.invoke(null)
                Log.i(TAG, "requestPermission: invoked no-arg signature")
                // poll state shortly and return result via callback
                Thread {
                    Thread.sleep(500)
                    val granted = probeDhizukuPermission()
                    callback(granted)
                }.start()
                return
            } catch (t: Throwable) {
                tried.add("no-arg")
                Log.d(TAG, "requestPermission no-arg not available: ${t.javaClass.simpleName}")
            }

            // 2) Try requestPermission(Activity)
            try {
                val m1 = clazz.getMethod("requestPermission", Activity::class.java)
                if (activity != null) {
                    m1.invoke(null, activity)
                    Log.i(TAG, "requestPermission: invoked Activity-arg signature")
                    Thread {
                        Thread.sleep(500)
                        val granted = probeDhizukuPermission()
                        callback(granted)
                    }.start()
                    return
                } else {
                    Log.w(TAG, "requestPermission: Activity signature present but activity is null")
                    tried.add("activity-present-activity-null")
                }
            } catch (t: Throwable) {
                tried.add("activity-arg")
                Log.d(TAG, "requestPermission Activity-arg not available: ${t.javaClass.simpleName}")
            }

            // 3) Try requestPermission(callbackInterface) - search for one-arg methods
            try {
                val methods = clazz.methods.filter { it.name == "requestPermission" && it.parameterCount == 1 }
                for (m in methods) {
                    val param = m.parameterTypes[0]
                    if (param == Activity::class.java) continue // already handled
                    // create proxy for this param type
                    try {
                        val callbackClass = param
                        val proxy = Proxy.newProxyInstance(
                            callbackClass.classLoader,
                            arrayOf(callbackClass)
                        ) { _, method, args ->
                            try {
                                // Best-effort: inspect args to detect grant result
                                if (args != null && args.isNotEmpty()) {
                                    for (a in args) {
                                        if (a is Int) {
                                            val granted = a == android.content.pm.PackageManager.PERMISSION_GRANTED
                                            isPermissionGranted = granted
                                            callback(granted)
                                            return@newProxyInstance null
                                        }
                                        if (a is Boolean) {
                                            val granted = a
                                            isPermissionGranted = granted
                                            callback(granted)
                                            return@newProxyInstance null
                                        }
                                    }
                                }
                                // If no recognizable args, poll permission after short wait
                                Thread {
                                    Thread.sleep(500)
                                    val granted = probeDhizukuPermission()
                                    callback(granted)
                                }.start()
                            } catch (ex: Throwable) {
                                Log.e(TAG, "requestPermission proxy handler failed", ex)
                                callback(false)
                            }
                            null
                        }
                        m.invoke(null, proxy)
                        Log.i(TAG, "requestPermission: invoked callback-arg signature param=${param.name}")
                        return
                    } catch (invokeEx: Throwable) {
                        Log.d(TAG, "requestPermission: tried callback param ${param.name} but failed: ${invokeEx.javaClass.simpleName}")
                    }
                }
            } catch (t: Throwable) {
                tried.add("callback-search")
                Log.d(TAG, "requestPermission callback-search failed: ${t.javaClass.simpleName}")
            }

            // nothing matched
            Log.e(TAG, "requestPermission: no matching signature found. tried=$tried")
            callback(false)
        } catch (t: Throwable) {
            Log.e(TAG, "requestPermission reflection failed", t)
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
            Log.w(TAG, "installApk: Dhizuku not available or permission not granted.")
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
                    Log.e(TAG, "installApk onServiceConnected exception", e)
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
            try {
                val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                Log.i(TAG, "installApk: bindService returned $ok")
                ok
            } catch (t: Throwable) {
                Log.e(TAG, "installApk: bindService exception", t)
                false
            }
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
            Log.w(TAG, "installApk: bindService returned false for component ${intent.component}")
            return "ERROR: bindService returned false"
        }

        // wait for response with timeout
        val result = runBlocking {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
        } ?: "ERROR: Dhizuku call timed out after ${timeoutMs}ms"

        Log.i(TAG, "installApk: result=${result.take(200)}")
        return result
    }

    /**
     * Run an arbitrary shell command through the local Dhizuku service.
     * Returns stdout/stderr formatted string or an error message.
     */
    fun runShellCommandWithDhizuku(context: Context, cmd: String, timeoutMs: Long = CALL_TIMEOUT_MS): String {
        if (!probeDhizukuPermission()) {
            Log.w(TAG, "runShellCommandWithDhizuku: Dhizuku not available or permission not granted.")
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
                    Log.e(TAG, "runShellCommandWithDhizuku onServiceConnected exception", e)
                    deferred.complete("EXCEPTION: ${e.stackTraceToString()}")
                    try { context.unbindService(this) } catch (_: Exception) {}
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!deferred.isCompleted) deferred.complete("ERROR: service disconnected")
            }
        }

        val bindResult = if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                Log.i(TAG, "runShellCommandWithDhizuku: bindService returned $ok")
                ok
            } catch (t: Throwable) {
                Log.e(TAG, "runShellCommandWithDhizuku: bindService exception", t)
                false
            }
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
            Log.w(TAG, "runShellCommandWithDhizuku: bindService returned false for component ${intent.component}")
            return "ERROR: bindService returned false"
        }

        val result = runBlocking {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
        } ?: "ERROR: Dhizuku call timed out after ${timeoutMs}ms"

        Log.i(TAG, "runShellCommandWithDhizuku: result=${result.take(200)}")
        return result
    }

    /**
     * Probe whether Dhizuku SDK is present and permission granted.
     * Uses reflection so absence of SDK won't crash.
     */
    private fun probeDhizukuPermission(): Boolean {
        try {
            val clazz = Class.forName("com.rosan.dhizuku.api.Dhizuku")
            val mPerm = try { clazz.getMethod("isPermissionGranted") } catch (t: Throwable) { null }
            val allowed = try { (mPerm?.invoke(null) as? Boolean) ?: false } catch (t: Throwable) {
                Log.e(TAG, "probeDhizukuPermission invoke failed", t)
                false
            }
            isAvailable = true
            isPermissionGranted = allowed
            Log.i(TAG, "probeDhizukuPermission: available=$isAvailable granted=$isPermissionGranted")
            return allowed
        } catch (t: Throwable) {
            Log.i(TAG, "probeDhizukuPermission: Dhizuku SDK absent or reflection failed: ${t.javaClass.simpleName}")
            isAvailable = false
            isPermissionGranted = false
            return false
        }
    }
}