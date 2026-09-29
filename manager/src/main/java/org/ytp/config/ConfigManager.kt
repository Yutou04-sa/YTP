package org.ytp.config

import android.content.pm.PackageManager
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.ytp.util.LSPPackageManager
import org.ytp.util.ModuleLoader
import java.io.File

object ConfigManager {

    private const val TAG = "ConfigManager"

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    private val db: org.ytp.database.LSPDatabase = Room.databaseBuilder(
        _root_ide_package_.org.ytp.lspApp, _root_ide_package_.org.ytp.database.LSPDatabase::class.java, "modules_config.db"
    ).build()

    private val moduleDao = db.moduleDao()
    private val scopeDao = db.scopeDao()

    private val loadedModules = mutableMapOf<org.ytp.database.entity.Module, org.lsposed.lspd.models.Module>()

    suspend fun updateModules(newModules: Map<String, String>) =
        withContext(dispatcher) {
            for (module in moduleDao.getAll()) {
                val apkPath = newModules[module.pkgName]
                if (apkPath == null) {
                    // 这次扫描没看到它：可能是真卸载了，也可能只是扫描时读 apk 失败
                    // （例如应用正在升级）。Module 删除会级联删掉 Scope，等于永久丢掉
                    // 用户的作用域配置，所以只有确认包真的不在了才删；包还在就保留并告警。
                    if (LSPPackageManager.isPackageInstalled(module.pkgName)) {
                        Log.w(
                            TAG,
                            "Module ${module.pkgName} is still installed but was not detected " +
                                "in this scan; keeping its configuration"
                        )
                    } else {
                        moduleDao.delete(module)
                        loadedModules.remove(module)
                    }
                } else if (module.apkPath != apkPath) {
                    loadedModules.remove(module)
                    // apkPath 变化时必须落库（原先只改内存副本，重启后又变回旧路径）
                    moduleDao.updateApkPath(module.pkgName, apkPath)
                }
            }
            for ((pkgName, apkPath) in newModules) {
                moduleDao.insert(
                    _root_ide_package_.org.ytp.database.entity.Module(
                        pkgName,
                        apkPath
                    )
                )
            }
        }

    suspend fun activateModule(pkgName: String, module: org.ytp.database.entity.Module) =
        withContext(dispatcher) {
            scopeDao.insert(
                _root_ide_package_.org.ytp.database.entity.Scope(
                    appPkgName = pkgName,
                    modulePkgName = module.pkgName
                )
            )
        }

    suspend fun deactivateModule(pkgName: String, module: org.ytp.database.entity.Module) =
        withContext(dispatcher) {
            scopeDao.delete(
                _root_ide_package_.org.ytp.database.entity.Scope(
                    appPkgName = pkgName,
                    modulePkgName = module.pkgName
                )
            )
        }

    suspend fun getModulesForApp(pkgName: String): List<org.ytp.database.entity.Module> =
        withContext(dispatcher) {
            return@withContext scopeDao.getModulesForApp(pkgName)
        }

    suspend fun getModuleFilesForApp(pkgName: String): List<org.lsposed.lspd.models.Module> =
        withContext(dispatcher) {
            val modules = scopeDao.getModulesForApp(pkgName)
            return@withContext modules.mapNotNull { module ->
                var apkPath = module.apkPath
                if (!File(apkPath).exists()) {
                    loadedModules.remove(module)
                    try {
                        apkPath = _root_ide_package_.org.ytp.lspApp.packageManager.getApplicationInfo(module.pkgName, 0).sourceDir
                    } catch (e: PackageManager.NameNotFoundException) {
                        moduleDao.delete(moduleDao.getModule(module.pkgName))
                        Log.w(TAG, "Module may be uninstalled: ${module.pkgName}")
                        return@mapNotNull null
                    }
                    // 路径变化同步落库，并用新实例作为缓存 key，避免旧 key 残留在 loadedModules
                    moduleDao.updateApkPath(module.pkgName, apkPath)
                    Log.i(TAG, "Module apk path updated: ${module.pkgName}")
                }
                val key = module.copy(apkPath = apkPath)
                loadedModules.getOrPut(key) {
                    org.lsposed.lspd.models.Module().apply {
                        packageName = key.pkgName
                        apkPath = key.apkPath
                        file = ModuleLoader.loadModule(key.apkPath)
                    }
                }
            }
        }
}
