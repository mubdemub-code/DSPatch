package org.lsposed.lspatch.privilege

/**
 * C'est le contrat : tout moteur de privilège doit respecter ces règles.
 */
interface IPrivilegeProvider {
    fun isAvailable(): Boolean
    fun getName(): String
    fun execShellCommand(command: String): String?
}
