// manager/src/main/java/org/lsposed/lspatch/privilege/DhizukuProvider.kt
package org.lsposed.lspatch.privilege

import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageInstaller
import com.rosan.dhizuku.api.Dhizuku
import org.lsposed.lspatch.util.DhizukuApi

class DhizukuProvider(private val context: Context) : IPrivilegeProvider {
    
    override fun getName(): String = "Dhizuku"

    override fun isAvailable(): Boolean {
        return try {
            Dhizuku.init(context) && Dhizuku.isPermissionGranted()
        } catch (e: Exception) {
            false
        }
    }

    override fun createPackageInstallerSession(
        params: PackageInstaller.SessionParams
    ): PackageInstaller.Session {
        return DhizukuApi.createPackageInstallerSession(params)
    }

    override fun isPackageInstalledWithoutPatch(packageName: String): Boolean {
        return DhizukuApi.isPackageInstalledWithoutPatch(packageName)
    }

    override fun uninstallPackage(packageName: String, intentSender: IntentSender) {
        DhizukuApi.uninstallPackage(packageName, intentSender)
    }

    override suspend fun performDexOptMode(packageName: String): Boolean {
        return DhizukuApi.performDexOptMode(packageName)
    }

    override suspend fun executeShellCommand(command: String): String? {
        return DhizukuApi.executeShellCommand(command)
    }
}