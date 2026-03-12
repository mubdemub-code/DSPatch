// manager/src/main/java/org/lsposed/lspatch/privilege/IPrivilegeProvider.kt
package org.lsposed.lspatch.privilege

import android.content.IntentSender
import android.content.pm.PackageInstaller

interface IPrivilegeProvider {
    /**
     * Nom du fournisseur (Shizuku, Dhizuku, etc.)
     */
    fun getName(): String

    /**
     * Vérifie si le service est disponible et les permissions accordées
     */
    fun isAvailable(): Boolean

    /**
     * Crée une session d'installation d'APK
     */
    fun createPackageInstallerSession(
        params: PackageInstaller.SessionParams
    ): PackageInstaller.Session

    /**
     * Vérifie si une app est installée SANS le patch
     */
    fun isPackageInstalledWithoutPatch(packageName: String): Boolean

    /**
     * Désinstalle un package
     */
    fun uninstallPackage(packageName: String, intentSender: IntentSender)

    /**
     * Optimise un package (DEX compilation)
     */
    suspend fun performDexOptMode(packageName: String): Boolean

    /**
     * Exécute une commande shell
     */
    suspend fun executeShellCommand(command: String): String?
}