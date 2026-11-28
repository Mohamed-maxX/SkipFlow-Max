package com.max.skipflow.shizuku

import com.max.skipflow.IShizukuTapService
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Privileged AIDL UserService hosted inside Shizuku's shell/root process
 * to execute hardware-level `input tap X Y` events for anti-accessibility games.
 */
class ShizukuTapUserService : IShizukuTapService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun executeInputTap(x: Int, y: Int): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("input", "tap", x.coerceAtLeast(0).toString(), y.coerceAtLeast(0).toString())
            )
            val completed = process.waitFor(1_500L, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: Throwable) {
            false
        }
    }
}
