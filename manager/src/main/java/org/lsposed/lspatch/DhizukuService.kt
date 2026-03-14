package org.lsposed.lspatch

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * DhizukuService — Expose IDhizukuService AIDL for privileged command execution.
 *
 * Corrections / améliorations :
 *  - logs détaillés
 *  - vérification d'appelant plus claire + logs
 *  - tentative d'utilisation de l'API Dhizuku via reflection si disponible
 *  - fallback sur ProcessBuilder (identique à l'implémentation précédente) si la SDK
 *    ne fournit pas de méthode d'exécution shell accessible par reflection
 *
 * IMPORTANT:
 *  - Pour la sécurité, protège ce service dans AndroidManifest.xml (permission/signature)
 *    ou garde android:exported="false" si tu ne veux binder qu'en local.
 */
class DhizukuService : Service() {

    companion object {
        private const val TAG = "DhizukuService"
    }

    private val executor = Executors.newCachedThreadPool()
    private val COMMAND_TIMEOUT_SECONDS = 90L // ajustable
    private val OUTPUT_READ_TIMEOUT_SECONDS = 2L

    private val binder = object : IDhizukuService.Stub() {
        @Throws(RemoteException::class)
        override fun runShellCommand(cmd: String): String {
            Log.i(TAG, "AIDL runShellCommand requested: ${cmd.take(200)}")
            if (!isCallerAllowed()) {
                val msg = "ERROR: caller not allowed (uid=${Binder.getCallingUid()})"
                Log.w(TAG, msg)
                return msg
            }
            return try {
                // Do not execute on binder thread
                runWithDhizukuGuard(cmd)
            } catch (t: Throwable) {
                Log.e(TAG, "runShellCommand exception", t)
                "EXCEPTION: ${t.stackTraceToString()}"
            }
        }

        @Throws(RemoteException::class)
        override fun installApk(apkPath: String): String {
            Log.i(TAG, "AIDL installApk requested: ${apkPath}")
            if (!isCallerAllowed()) {
                val msg = "ERROR: caller not allowed (uid=${Binder.getCallingUid()})"
                Log.w(TAG, msg)
                return msg
            }
            // sanitize path a bit for logs
            val safePathForLog = apkPath.replace("\n", "\\n").take(300)
            val cmd = "pm install -r \"${apkPath.replace("\"", "\\\"")}\""
            Log.i(TAG, "installApk -> running command: $cmd")
            return try {
                runWithDhizukuGuard(cmd)
            } catch (t: Throwable) {
                Log.e(TAG, "installApk exception", t)
                "EXCEPTION: ${t.stackTraceToString()}"
            }
        }

        @Throws(RemoteException::class)
        override fun destroy() {
            Log.i(TAG, "AIDL destroy requested — stopping service instance")
            try {
                this@DhizukuService.stopSelf()
            } catch (t: Throwable) {
                Log.e(TAG, "destroy stopSelf exception", t)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "onBind() called, intent=$intent")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            executor.shutdownNow()
        } catch (t: Throwable) {
            Log.e(TAG, "executor.shutdownNow failed", t)
        }
        Log.i(TAG, "DhizukuService destroyed")
    }

    /**
     * Check if the caller is allowed to use this service.
     * - allow same-app calls
     * - allow Dhizuku package uid (common case)
     * - allow system uid
     *
     * NOTE: for production, replace/augment with permission/signature checks.
     */
    private fun isCallerAllowed(): Boolean {
        val callerUid = Binder.getCallingUid()
        try {
            // allow same process / same app
            if (callerUid == Process.myUid()) {
                Log.d(TAG, "isCallerAllowed: caller is same UID (self). allowed")
                return true
            }
            // allow system
            if (callerUid == android.os.Process.SYSTEM_UID) {
                Log.d(TAG, "isCallerAllowed: caller is system UID. allowed")
                return true
            }

            val pkgs = packageManager.getPackagesForUid(callerUid) ?: run {
                Log.w(TAG, "isCallerAllowed: no packages for uid $callerUid")
                return false
            }

            Log.d(TAG, "isCallerAllowed: packages for uid $callerUid = ${pkgs.joinToString()}")
            for (p in pkgs) {
                if (p == packageName) {
                    Log.d(TAG, "isCallerAllowed: caller package == self ($p). allowed")
                    return true
                }
                // permit common Dhizuku / su-host packages for debugging; tighten in production
                if (p.startsWith("com.rosan.dhizuku") || p.startsWith("com.topjohnwu") || p.startsWith("rikka.shizuku")) {
                    Log.d(TAG, "isCallerAllowed: caller package $p looks like privileged host. allowed")
                    return true
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "isCallerAllowed exception", t)
        }
        Log.w(TAG, "isCallerAllowed: caller uid $callerUid not allowed")
        return false
    }

    /**
     * Guard: tries to use Dhizuku SDK via reflection to execute the command (preferable),
     * otherwise falls back to running the command locally with ProcessBuilder.
     *
     * Important: calling Dhizuku methods (via reflection) may execute the command with
     * higher privileges if the SDK provides such an API. If not available, we fallback
     * to the previous behavior (ProcessBuilder) and return its result — but note that
     * fallback may lack privileges to perform certain operations (eg: pm install).
     */
    private fun runWithDhizukuGuard(cmd: String): String {
        // First probe Dhizuku presence & permission (best-effort via reflection)
        val dhizukuAllowed = try {
            val clazz = Class.forName("com.rosan.dhizuku.api.Dhizuku")
            val mPerm = try { clazz.getMethod("isPermissionGranted") } catch (_: Throwable) { null }
            val allowed = try { (mPerm?.invoke(null) as? Boolean) ?: false } catch (_: Throwable) { false }
            Log.i(TAG, "runWithDhizukuGuard: Dhizuku present, permission=$allowed")
            allowed
        } catch (t: Throwable) {
            Log.i(TAG, "runWithDhizukuGuard: Dhizuku SDK absent or reflection failed: ${t.javaClass.simpleName}")
            false
        }

        if (!dhizukuAllowed) {
            val msg = "ERROR: Dhizuku not available or permission not granted. Ensure Dhizuku is installed and this app has Dhizuku permission."
            Log.w(TAG, msg)
            return msg
        }

        // Try to use Dhizuku SDK via reflection to run shell if such API exists.
        try {
            val clazz = Class.forName("com.rosan.dhizuku.api.Dhizuku")
            // Try possible method names commonly used by such SDKs
            val tryNames = listOf("runShellCommand", "runCommand", "execShell", "exec", "runShell")
            for (name in tryNames) {
                try {
                    val method = clazz.getMethod(name, String::class.java)
                    Log.i(TAG, "runWithDhizukuGuard: calling Dhizuku.$name via reflection")
                    val res = method.invoke(null, cmd) as? String
                    if (res != null) {
                        Log.i(TAG, "runWithDhizukuGuard: Dhizuku.$name returned ${res.take(200)}")
                        return res
                    }
                } catch (e: NoSuchMethodException) {
                    // try next
                } catch (e: Throwable) {
                    Log.e(TAG, "runWithDhizukuGuard: invoking Dhizuku.$name failed", e)
                    // do not return, try next strategy or fallback
                }
            }
            Log.i(TAG, "runWithDhizukuGuard: no usable Dhizuku run-method found via reflection; falling back")
        } catch (t: Throwable) {
            Log.i(TAG, "runWithDhizukuGuard: Dhizuku reflection path failed: ${t.javaClass.simpleName}")
        }

        // Fallback: run command locally in this process (same logic as before)
        Log.i(TAG, "runWithDhizukuGuard: falling back to local ProcessBuilder execution")
        return runCommandSafely(cmd)
    }

    /**
     * Execute command with robust handling:
     * - run via "sh -c" to support pipes/redirects
     * - read stdout & stderr concurrently to avoid deadlocks
     * - enforce a process timeout and small read timeouts for streams
     * - return formatted string with exit code, stdout and stderr
     *
     * This method executes the command in the app process; it may not have privileged rights.
     */
    private fun runCommandSafely(cmd: String): String {
        Log.i(TAG, "runCommandSafely: executing command (may be unprivileged): ${cmd.take(200)}")
        val pb = ProcessBuilder(listOf("sh", "-c", cmd))
        pb.redirectErrorStream(false)
        return try {
            val proc = pb.start()

            val outFuture = executor.submit<String> { readStream(proc.inputStream) }
            val errFuture = executor.submit<String> { readStream(proc.errorStream) }

            val finished = proc.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                try { proc.destroyForcibly() } catch (_: Throwable) {}
                val msg = "ERROR: command timed out after ${COMMAND_TIMEOUT_SECONDS}s"
                Log.w(TAG, msg)
                return msg
            }

            val out = try { outFuture.get(OUTPUT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS) } catch (e: Exception) {
                Log.d(TAG, "runCommandSafely: stdout read timeout/exception: ${e.javaClass.simpleName}")
                ""
            }
            val err = try { errFuture.get(OUTPUT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS) } catch (e: Exception) {
                Log.d(TAG, "runCommandSafely: stderr read timeout/exception: ${e.javaClass.simpleName}")
                ""
            }
            val exitCode = try { proc.exitValue() } catch (_: Throwable) { -1 }

            val result = buildString {
                append("EXIT_CODE:").append(exitCode).append("\n")
                append("STDOUT:\n").append(out).append("\n")
                append("STDERR:\n").append(err).append("\n")
            }
            Log.i(TAG, "runCommandSafely: finished exit=$exitCode")
            result
        } catch (e: Throwable) {
            Log.e(TAG, "runCommandSafely: exception running command", e)
            "EXCEPTION: ${e.stackTraceToString()}"
        }
    }

    private fun readStream(input: InputStream): String {
        return try {
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Throwable) {
            Log.d(TAG, "readStream exception: ${e.javaClass.simpleName}")
            ""
        }
    }
}