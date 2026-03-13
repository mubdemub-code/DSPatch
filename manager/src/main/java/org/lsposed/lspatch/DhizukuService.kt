package org.lsposed.lspatch

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * DhizukuService — Expose IDhizukuService AIDL for privileged command execution.
 *
 * Notes:
 * - This implementation uses reflection to check Dhizuku availability (no hard dependency).
 * - Protect the service in AndroidManifest (permission/signature) or keep exported=false and
 *   bind from the same package whenever possible.
 * - For best results, add the official Dhizuku SDK and replace reflection checks with direct calls.
 */
class DhizukuService : Service() {

    private val executor = Executors.newCachedThreadPool()
    private val COMMAND_TIMEOUT_SECONDS = 90L // adjust as needed
    private val OUTPUT_READ_TIMEOUT_SECONDS = 2L

    private val binder = object : IDhizukuService.Stub() {
        @Throws(RemoteException::class)
        override fun runShellCommand(cmd: String): String {
            // protect caller
            if (!isCallerAllowed()) return "ERROR: caller not allowed (uid=${Binder.getCallingUid()})"
            return runWithDhizukuGuard(cmd)
        }

        @Throws(RemoteException::class)
        override fun installApk(apkPath: String): String {
            if (!isCallerAllowed()) return "ERROR: caller not allowed (uid=${Binder.getCallingUid()})"
            val safePath = apkPath.replace("\"", "\\\"")
            val cmd = "pm install -r \"$safePath\""
            return runWithDhizukuGuard(cmd)
        }

        @Throws(RemoteException::class)
        override fun destroy() {
            // Stop only the service instance; do not kill the app process.
            try {
                this@DhizukuService.stopSelf()
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = binder

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    /**
     * Check if the caller is allowed to use this service.
     * - allow same-app calls
     * - allow Dhizuku package uid (common case)
     * - allow system uid
     *
     * You should harden this check for production (signature permission, custom permission, whitelist).
     */
    private fun isCallerAllowed(): Boolean {
        val callerUid = Binder.getCallingUid()
        // allow same process / same app
        if (callerUid == Process.myUid()) return true
        // allow system
        if (callerUid == android.os.Process.SYSTEM_UID) return true

        val pkgs = try {
            packageManager.getPackagesForUid(callerUid)
        } catch (e: Throwable) {
            null
        } ?: return false

        for (p in pkgs) {
            // allow if caller package is the app itself
            if (p == packageName) return true
            // common Dhizuku package name(s) — permit binding from Dhizuku host if required
            if (p.startsWith("com.rosan.dhizuku") || p.startsWith("com.topjohnwu")) return true
        }
        return false
    }

    /**
     * Guard: first verify Dhizuku permission/availability (via reflection), then execute.
     * If Dhizuku is not available, return a clear error message.
     */
    private fun runWithDhizukuGuard(cmd: String): String {
        val dhizukuAvailable = try {
            // reflection to avoid hard dependency; checks for com.rosan.dhizuku.api.Dhizuku.isPermissionGranted()
            val clazz = Class.forName("com.rosan.dhizuku.api.Dhizuku")
            val method = clazz.getMethod("isPermissionGranted")
            val allowed = method.invoke(null) as? Boolean ?: false
            allowed
        } catch (t: Throwable) {
            false
        }

        if (!dhizukuAvailable) {
            return "ERROR: Dhizuku not available or permission not granted. Ensure Dhizuku is installed and this app has Dhizuku permission (Device Owner / granted)."
        }

        return runCommandSafely(cmd)
    }

    /**
     * Execute command with robust handling:
     * - run via "sh -c" to support pipes/redirects
     * - read stdout & stderr concurrently to avoid deadlocks
     * - enforce a process timeout and small read timeouts for streams
     * - return formatted string with exit code, stdout and stderr
     */
    private fun runCommandSafely(cmd: String): String {
        val pb = ProcessBuilder(listOf("sh", "-c", cmd))
        pb.redirectErrorStream(false) // capture separately
        return try {
            val proc = pb.start()

            val outFuture = executor.submit<String> { readStream(proc.inputStream) }
            val errFuture = executor.submit<String> { readStream(proc.errorStream) }

            val finished = proc.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                try { proc.destroyForcibly() } catch (_: Throwable) {}
                return "ERROR: command timed out after ${COMMAND_TIMEOUT_SECONDS}s"
            }

            val out = try { outFuture.get(OUTPUT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS) } catch (_: Exception) { "" }
            val err = try { errFuture.get(OUTPUT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS) } catch (_: Exception) { "" }
            val exitCode = try { proc.exitValue() } catch (_: Throwable) { -1 }

            buildString {
                append("EXIT_CODE:").append(exitCode).append("\n")
                append("STDOUT:\n").append(out).append("\n")
                append("STDERR:\n").append(err).append("\n")
            }
        } catch (e: Throwable) {
            "EXCEPTION: ${e.stackTraceToString()}"
        }
    }

    private fun readStream(input: InputStream): String {
        return try {
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Throwable) {
            ""
        }
    }
}