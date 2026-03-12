package org.lsposed.lspatch.privilege

interface IPrivilegeProvider {
    fun isAvailable(): Boolean
    fun getName(): String
    fun execShellCommand(command: String): String?
}
