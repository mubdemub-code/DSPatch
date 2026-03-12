package org.lsposed.lspatch.privilege

import android.content.Context
import org.lsposed.lspatch.util.ShizukuApi
import rikka.shizuku.Shizuku

class ShizukuProvider(private val context: Context) : IPrivilegeProvider {

    override fun isAvailable(): Boolean {
        // On réutilise exactement la logique native de LSPatch
        return ShizukuApi.isBinderAvailable && ShizukuApi.isPermissionGranted
    }

    override fun getName(): String = "Shizuku"

    override fun execShellCommand(command: String): String? {
        if (!isAvailable()) return null
        
        return try {
            // Exécution de la commande via le processus privilégié de Shizuku
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
