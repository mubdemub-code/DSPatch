package org.lsposed.lspatch.privilege

import android.content.Context
import rikka.shizuku.Shizuku
import org.lsposed.lspatch.util.ShizukuApi

class ShizukuProvider(private val context: Context) : IPrivilegeProvider {

    override fun isAvailable(): Boolean {
        return ShizukuApi.isBinderAvailable && ShizukuApi.isPermissionGranted
    }

    override fun getName(): String = "Shizuku"

    override fun execShellCommand(command: String): String? {
        if (!isAvailable()) return null
        return try {
            // Correction : On passe par "sh -c" pour exécuter la commande texte
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}
