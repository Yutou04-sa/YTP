package org.ytp

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.ytp.model.UpdateInfo
import org.ytp.util.LSPPackageManager
import org.ytp.config.Configs
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File

lateinit var lspApp: Application

open class Application : android.app.Application() {

    lateinit var prefs: SharedPreferences
    lateinit var tmpApkDir: File
    var updateInfo: UpdateInfo? = null
    var targetApkFiles: ArrayList<File>? = null
    val globalScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.addHiddenApiExemptions("")
        lspApp = this
        filesDir.mkdir()
        tmpApkDir = cacheDir.resolve("apk").also { it.mkdir() }
        prefs = lspApp.getSharedPreferences("settings", MODE_PRIVATE)
        Configs.migrateTheme()
        globalScope.launch { LSPPackageManager.fetchAppList() }
    }
}
