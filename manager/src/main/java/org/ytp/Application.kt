package org.ytp

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ytp.model.UpdateInfo
import org.ytp.util.LSPPackageManager
import org.ytp.config.Configs
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File

private const val TAG = "Application"

lateinit var lspApp: Application

open class Application : android.app.Application() {

    lateinit var prefs: SharedPreferences
    lateinit var tmpApkDir: File
    var updateInfo: UpdateInfo? = null
    var targetApkFiles: ArrayList<File>? = null

    /**
     * 后台任务的兜底。
     *
     * 原来只有 `Dispatchers.Default`：launch 里未捕获的异常会一路冒到默认处理器，
     * 直接把整个进程打崩。加上 [SupervisorJob]（一个子任务失败不影响其他任务）和
     * [CoroutineExceptionHandler]（记录而不是崩溃）后，后台失败最多丢一次刷新。
     */
    val globalScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Background task failed", t)
        }
    )

    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.addHiddenApiExemptions("")
        lspApp = this
        filesDir.mkdir()
        tmpApkDir = cacheDir.resolve("apk").also { it.mkdir() }
        prefs = lspApp.getSharedPreferences("settings", MODE_PRIVATE)
        Configs.migrateTheme()
        globalScope.launch {
            try {
                LSPPackageManager.fetchAppList()
            } catch (t: Throwable) {
                // 这里是最后一道防线：异常就地消化并记日志，不再冒泡去杀进程。
                Log.e(TAG, "Failed to fetch the app list", t)
            }
        }
    }
}
