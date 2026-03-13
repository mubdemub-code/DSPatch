package org.lsposed.lspatch

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteException

/**
 * DhizukuService – Expose IDhizukuService AIDL pour exécuter des commandes avec les privilèges Dhizuku.
 */
class DhizukuService : Service() {

    private val binder = object : IDhizukuService.Stub() {
        @Throws(RemoteException::class)
        override fun runShellCommand(cmd: String): String {
            if (!isCallerAllowed()) return "ERROR: caller not allowed (uid=${Binder.getCallingUid()})"
            return executeWithDhizuku(cmd)
        }

        @Throws(RemoteException::class)
        override fun installApk(apkPath: String): String {
            if (!isCallerAllowed()) return "ERROR: caller not allowed (uid=${Binder.getCallingUid()})"
            val cmd = "pm install -r \"$apkPath\""
            return executeWithDhizuku(cmd)
        }

        @Throws(RemoteException::class)
        override fun destroy() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = binder

    private fun isCallerAllowed(): Boolean {
        val callerUid = Binder.getCallingUid()
        if (callerUid == Process.myUid()) return true
        if (callerUid == android.os.Process.SYSTEM_UID) return true

        val pkgs = try { packageManager.getPackagesForUid(callerUid) } catch (e: Throwable) { null } ?: return false
        return pkgs.any { it == packageName || it.startsWith("com.rosan.dhizuku") || it.startsWith("com.topjohnwu") }
    }

    private fun executeWithDhizuku(cmd: String): String {
        return try {
            val clazz = Class.forName("com.rosan.dhizuku.api.Dhizuku")
            val isPermGranted = clazz.getMethod("isPermissionGranted").invoke(null) as? Boolean ?: false
            if (!isPermGranted) {
                return "ERROR: Dhizuku permission not granted"
            }

            // Utiliser execCommand si disponible (méthode générique)
            val execMethod = try {
                clazz.getMethod("execCommand", String::class.java)
            } catch (e: NoSuchMethodException) {
                null
            }

            if (execMethod != null) {
                val execResult = execMethod.invoke(null, cmd)
                val exitCode = execResult.javaClass.getMethod("getExitCode").invoke(execResult) as? Int ?: -1
                val stdout = execResult.javaClass.getMethod("getStdout").invoke(execResult) as? String ?: ""
                val stderr = execResult.javaClass.getMethod("getStderr").invoke(execResult) as? String ?: ""
                buildString {
                    append("EXIT_CODE:").append(exitCode).append("\n")
                    append("STDOUT:\n").append(stdout).append("\n")
                    append("STDERR:\n").append(stderr).append("\n")
                }
            } else {
                // Fallback : essayer d'autres méthodes comme installPackage
                val installMethod = try {
                    clazz.getMethod("installPackage", String::class.java)
                } catch (e: NoSuchMethodException) {
                    null
                }
                if (installMethod != null && cmd.startsWith("pm install")) {
                    val result = installMethod.invoke(null, cmd.substringAfter("pm install -r ").trim('"'))
                    // Supposons que installPackage retourne un Boolean ou un String
                    if (result == true || result == "success") {
                        "EXIT_CODE:0\nSTDOUT:Install success\nSTDERR:\n"
                    } else {
                        "EXIT_CODE:1\nSTDOUT:\nSTDERR:Install failed\n"
                    }
                } else {
                    "ERROR: Dhizuku API does not support command execution"
                }
            }
        } catch (t: Throwable) {
            "EXCEPTION: ${t.stackTraceToString()}"
        }
    }
}