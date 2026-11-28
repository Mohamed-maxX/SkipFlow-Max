package com.max.skipflow.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.max.skipflow.BuildConfig
import com.max.skipflow.IShizukuTapService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuStatus(val arabicSummary: String) {
    NOT_INSTALLED("تطبيق Shizuku غير مثبت على الجهاز"),
    SERVICE_NOT_RUNNING("Shizuku مثبت لكن الخدمة غير مشغّلة"),
    PERMISSION_REQUIRED("يتطلب منح صلاحية Shizuku لتشغيل وضع التيربو"),
    READY("Shizuku متصل ومصرّح وجاهز للعمل")
}

/**
 * Safe bridge to Shizuku's [ShizukuTapUserService]. Checks installation, binder availability,
 * and runtime permission safely; returns `false` on any failure so callers seamlessly fall back
 * to standard `AccessibilityService.dispatchGesture()`.
 */
class ShizukuTapBridge(context: Context) {

    private val appContext = context.applicationContext

    private val _status = MutableStateFlow(ShizukuStatus.NOT_INSTALLED)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    @Volatile
    private var tapService: IShizukuTapService? = null

    @Volatile
    private var isBinding: Boolean = false

    @Volatile
    private var listenersRegistered: Boolean = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShizukuTapUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_tap")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            isBinding = false
            tapService = if (binder != null && binder.pingBinder()) {
                IShizukuTapService.Stub.asInterface(binder)
            } else {
                null
            }
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBinding = false
            tapService = null
            refreshStatus()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        tapService = null
        isBinding = false
        refreshStatus()
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            refreshStatus()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindUserServiceIfReady()
            }
        }
    }

    fun registerListeners() {
        if (listenersRegistered) return
        listenersRegistered = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }
        refreshStatus()
    }

    fun unregisterListeners() {
        if (!listenersRegistered) return
        listenersRegistered = false
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
        unbind()
    }

    fun refreshStatus(): ShizukuStatus {
        val current = queryStatus()
        _status.value = current
        if (current == ShizukuStatus.READY) {
            bindUserServiceIfReady()
        } else {
            tapService = null
        }
        return current
    }

    fun requestPermissionIfNeeded(): Boolean {
        val current = refreshStatus()
        if (current == ShizukuStatus.PERMISSION_REQUIRED) {
            return runCatching {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                true
            }.getOrDefault(false)
        }
        return current == ShizukuStatus.READY
    }

    fun tryTap(x: Int, y: Int): Boolean {
        if (refreshStatus() != ShizukuStatus.READY) return false
        val svc = tapService
        if (svc == null || !runCatching { svc.asBinder().pingBinder() }.getOrDefault(false)) {
            tapService = null
            bindUserServiceIfReady()
            return false
        }
        return runCatching { svc.executeInputTap(x, y) }.getOrElse {
            tapService = null
            false
        }
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
        tapService = null
        isBinding = false
    }

    private fun bindUserServiceIfReady() {
        if (tapService != null || isBinding) return
        if (queryStatus() != ShizukuStatus.READY) return
        isBinding = true
        runCatching {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        }.onFailure {
            isBinding = false
        }
    }

    private fun queryStatus(): ShizukuStatus {
        if (!isShizukuPackageInstalled()) {
            return ShizukuStatus.NOT_INSTALLED
        }
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            return ShizukuStatus.SERVICE_NOT_RUNNING
        }
        val preV11 = runCatching { Shizuku.isPreV11() }.getOrDefault(true)
        if (preV11) {
            return ShizukuStatus.SERVICE_NOT_RUNNING
        }
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
        return if (granted) ShizukuStatus.READY else ShizukuStatus.PERMISSION_REQUIRED
    }

    private fun isShizukuPackageInstalled(): Boolean {
        return runCatching {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 3091
    }
}
