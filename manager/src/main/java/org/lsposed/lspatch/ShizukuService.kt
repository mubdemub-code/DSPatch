package org.lsposed.lspatch

import java.io.BufferedReader
import java.io.InputStreamReader
import org.lsposed.lspatch.IShizukuService
import kotlin.system.exitProcess

class ShizukuService : IShizukuService.Stub() {

    override fun runShellCommand(cmd: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()

            reader.lines().forEach {
                output.append(it).append("\n")
            }

            process.waitFor()

            output.toString()

        } catch (e: Exception) {
            e.stackTraceToString()
        }
    }

    override fun destroy() {
        exitProcess(0)
    }
}