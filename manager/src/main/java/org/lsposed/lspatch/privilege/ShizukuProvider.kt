// manager/src/main/java/org/lsposed/lspatch/privilege/ShizukuProvider.kt
package org.lsposed.lspatch.privilege

import android.content.IntentSender
import android.content.pm.PackageInstaller
import org.lsposed.lspatch.util.ShizukuApi

class ShizukuProvider : IPrivilegeProvider {
    
    override fun getName(): String = "Shizuku"

    override fun isAvailable(): Boolean = ShizukuApi.isBinderAvailable && ShizukuApi.isPermissionGranted

    override fun createPackageInstallerSession(
        params: PackageInstaller.SessionParams
    ): PackageInstaller.Session {
        return ShizukuApi.createPackageInstallerSession(params)
    }

    override fun isPackageInstalledWithoutPatch(packageName: String): Boolean {
        return ShizukuApi.isPackageInstalledWithoutPatch(packageName)
    }

    override fun uninstallPackage(packageName: String, intentSender: IntentSender) {
        ShizukuApi.uninstallPackage(packageName, intentSender)
    }

    override suspend fun performDexOptMode(packageName: String): Boolean {
        return ShizukuApi.performDexOptMode(packageName)
    }

    override suspend fun executeShellCommand(command: String): String? {
        return null // Implémenté plus tard si nécessaire
    }
}