package com.agentkosticka.easierspot.privileged

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Runs inside Shizuku's shell-identity UserService process. */
class PrivilegedShellService : IPrivilegedShell.Stub() {
    override fun execute(command: Array<out String>?, timeoutMillis: Long): String {
        val safeCommand = command?.takeIf { it.isNotEmpty() }
            ?: return encode(-1, "", "empty command")
        return try {
            val process = ProcessBuilder(*safeCommand).start()
            val stdout = CompletableFuture.supplyAsync {
                BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            }
            val stderr = CompletableFuture.supplyAsync {
                BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            }
            val completed = process.waitFor(timeoutMillis.coerceIn(250L, 30_000L), TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                stdout.cancel(true)
                stderr.cancel(true)
                encode(-1, "", "command timed out")
            } else {
                encode(process.exitValue(), stdout.get().trim(), stderr.get().trim())
            }
        } catch (error: Throwable) {
            encode(-1, "", error.message ?: error.javaClass.simpleName)
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    private fun encode(code: Int, stdout: String, stderr: String): String = listOf(
        code.toString(),
        Base64.encodeToString(stdout.toByteArray(), Base64.NO_WRAP),
        Base64.encodeToString(stderr.toByteArray(), Base64.NO_WRAP)
    ).joinToString("\n")
}
