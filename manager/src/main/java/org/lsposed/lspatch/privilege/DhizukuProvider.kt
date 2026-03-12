package org.lsposed.lspatch.privilege

import android.content.Context
import com.rosan.dhizuku.api.Dhizuku

class DhizukuProvider(private val context: Context) : IPrivilegeProvider {

    override fun isAvailable(): Boolean {
        return try {
            if (!Dhizuku.isInit()) {
                Dhizuku.init(context)
            }
            Dhizuku.isPermissionGranted()
        } catch (e: Exception) {
            false
        }
    }

    override fun getName(): String = "Dhizuku"

    override fun execShellCommand(command: String): String? {
        return null
    }
}
