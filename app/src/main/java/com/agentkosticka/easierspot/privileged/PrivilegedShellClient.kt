package com.agentkosticka.easierspot.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Base64
import com.agentkosticka.easierspot.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

data class PrivilegedCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

/** Reusable client for the shell-identity UserService; callers must invoke execute off main. */
object PrivilegedShellClient {
    private val lock = Any()
    @Volatile private var service: IPrivilegedShell? = null
    @Volatile private var connectionLatch = CountDownLatch(1)
    @Volatile private var binding = false
    private lateinit var appContext: Context

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IPrivilegedShell.Stub.asInterface(binder)
            binding = false
            connectionLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) = invalidate()

        override fun onBindingDied(name: ComponentName?) = invalidate()

        override fun onNullBinding(name: ComponentName?) = invalidate()
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun execute(command: Array<String>, timeoutMillis: Long = 10_000L): PrivilegedCommandResult {
        if (!::appContext.isInitialized || !ShizukuStateMonitor.isReady()) {
            return PrivilegedCommandResult(-1, "", "Shizuku is unavailable")
        }
        val remote = service ?: bindAndAwait() ?: return PrivilegedCommandResult(
            -1,
            "",
            "Shizuku privileged service did not connect"
        )
        return runCatching {
            decode(remote.execute(command, timeoutMillis))
        }.getOrElse {
            invalidate()
            PrivilegedCommandResult(-1, "", it.message ?: "privileged service failed")
        }
    }

    private fun bindAndAwait(): IPrivilegedShell? {
        synchronized(lock) {
            service?.let { return it }
            if (!binding) {
                binding = true
                connectionLatch = CountDownLatch(1)
                runCatching {
                    Shizuku.bindUserService(userServiceArgs(), connection)
                }.onFailure {
                    binding = false
                    connectionLatch.countDown()
                }
            }
        }
        connectionLatch.await(3, TimeUnit.SECONDS)
        return service
    }

    internal fun invalidate() {
        service = null
        binding = false
        connectionLatch.countDown()
    }

    private fun userServiceArgs() = Shizuku.UserServiceArgs(
        ComponentName(appContext, PrivilegedShellService::class.java)
    )
        .daemon(false)
        .processNameSuffix("privileged")
        .tag("easierspot-privileged")
        .version(BuildConfig.VERSION_CODE)
        .debuggable(BuildConfig.DEBUG)

    private fun decode(encoded: String): PrivilegedCommandResult {
        val parts = encoded.split('\n', limit = 3)
        if (parts.size != 3) return PrivilegedCommandResult(-1, "", "invalid privileged response")
        fun decodeText(value: String): String = String(Base64.decode(value, Base64.NO_WRAP))
        return PrivilegedCommandResult(parts[0].toIntOrNull() ?: -1, decodeText(parts[1]), decodeText(parts[2]))
    }
}
