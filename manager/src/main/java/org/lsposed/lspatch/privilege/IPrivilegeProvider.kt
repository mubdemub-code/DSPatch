package org.lsposed.lspatch.privilege

import android.content.IntentSender
import android.content.pm.PackageInstaller

interface IPrivilegeProvider {
    fun getName(): String
    fun isAvailable(): Boolean
    fun createPackageInstallerSession(params: PackageInstaller.SessionParams): PackageInstaller.Session
    fun isPackageInstalledWithoutPatch(packageName: String): Boolean
    fun uninstallPackage(packageName: String, intentSender: IntentSender)
    suspend fun performDexOptMode(packageName: String): Boolean
    suspend fun executeShellCommand(command: String): String?
}