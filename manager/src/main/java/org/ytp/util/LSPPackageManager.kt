package org.ytp.util

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.appiconloader.AppIconLoader
import org.ytp.config.ConfigManager
import org.ytp.lspApp
import org.ytp.share.Constants
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Collator
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipFile

object LSPPackageManager {

    private const val TAG = "LSPPackageManager"
    private const val SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS"

    const val STATUS_USER_CANCELLED = -2

    @Parcelize
    class AppInfo(val app: ApplicationInfo, val label: String,val isXposedModule: String) : Parcelable {

    }

    fun isXposedModule(app: ApplicationInfo): String {
        var targetApiVersion = ""
        if(app.metaData!=null && (app.metaData.containsKey("xposedminversion") || app.metaData.containsKey("xposedmodule") || app.metaData.containsKey("xposeddescription") || app.metaData.containsKey("xposedscope"))){
            targetApiVersion = "legacy"
        }

        //读取/META-INF/xposed/module.prop中的内容
        try {
            // 使用File对象规范化路径，避免路径遍历攻击
            val sourceDirFile = File(app.sourceDir)
            // 验证文件存在且为普通文件
            if (!sourceDirFile.exists() || !sourceDirFile.isFile) {
                return ""
            }

            // 获取绝对规范路径，防止路径遍历
            val canonicalPath = sourceDirFile.canonicalPath
            // 可以进一步验证路径是否在允许的目录范围内

          ZipFile(canonicalPath).use { srcZFile ->
                val entry = srcZFile.getEntry(Constants.MODULE_PROP_PATH)
                if (entry != null && !entry.isDirectory) {
                    // 读取文件.prop文件内容为Properties
                    val inputStream = srcZFile.getInputStream(entry)
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val properties = Properties()
                    properties.load(inputStream)
                    reader.close()
                    inputStream.close()
                    // 检查是否包含xposedmodule属性
                    if(targetApiVersion.isNotEmpty()){
                        targetApiVersion += " / "
                    }
                    targetApiVersion += properties.getProperty("targetApiVersion") ?: ""
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading zip file: ${app.sourceDir}", e)
            // 发生异常时返回false，表示不是Xposed模块
        }
        return targetApiVersion

    }

    var appList by mutableStateOf(listOf<AppInfo>())
        private set

    @SuppressLint("StaticFieldLeak")
    private val iconLoader = AppIconLoader(
        lspApp.resources.getDimensionPixelSize(android.R.dimen.app_icon_size), false,
        lspApp
    )
    private val appIcon = mutableMapOf<String, ImageBitmap>()

    /** Default icon used when app icon cannot be loaded */
    private var defaultIcon: ImageBitmap? = null
        get() {
            if (field == null) {
                // Load default Android icon
                try {
                    val androidAppInfo = lspApp.packageManager.getApplicationInfo("android", 0)
                    field = iconLoader.loadIcon(androidAppInfo).asImageBitmap()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load default Android icon", e)
                }
            }
            return field
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val fetchDispatcher = Dispatchers.IO.limitedParallelism(4)

    suspend fun fetchAppList() {
        withContext(fetchDispatcher) {
            val pm = lspApp.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            val collection = coroutineScope {
                apps.map { app ->
                    async {
                        // A single app that cannot be read (unreadable icon, removed while
                        // scanning, ...) must not make the whole app list fail.
                        val label = runCatching { pm.getApplicationLabel(app).toString() }
                            .getOrDefault(app.packageName)
                        val moduleType = runCatching { isXposedModule(app) }.getOrDefault("")
                        val icon = runCatching { iconLoader.loadIcon(app).asImageBitmap() }.getOrNull()
                        Triple(AppInfo(app, label, moduleType), app.packageName, icon)
                    }
                }.awaitAll()
            }

            val sorted = collection.map { (appInfo, pkg, icon) ->
                if (icon != null) appIcon[pkg] = icon
                appInfo
            }.sortedWith(compareBy(Collator.getInstance(Locale.getDefault()), AppInfo::label))

            val modules = sorted.filter { it.isXposedModule.isNotEmpty() }
                .associate { it.app.packageName to it.app.sourceDir }
            ConfigManager.updateModules(modules)
            appList = sorted
        }
    }

    /** 1x1 transparent bitmap, used when no icon at all could be loaded. */
    private val emptyIcon: ImageBitmap by lazy { ImageBitmap(1, 1) }

    fun getIcon(appInfo: AppInfo): ImageBitmap {
        return appIcon[appInfo.app.packageName] ?: defaultIcon ?: emptyIcon
    }

    fun getVersionName(packageName: String): String {
        return lspApp.packageManager.getPackageInfo(packageName, 0)?.versionName ?: ""
    }

    fun getVersionCode(packageName: String): Long {
        return lspApp.packageManager.getPackageInfo(packageName, 0)?.longVersionCode ?: 0L
    }

    fun getDescription(appInfo: AppInfo): String? {
        val description = appInfo.app.metaData?.get("xposeddescription")
        if(description != null){
            return description.toString()
        }
        return appInfo.app.loadDescription(lspApp.packageManager) as String?
    }

    suspend fun cleanTmpApkDir() {
        withContext(Dispatchers.IO) {
            lspApp.tmpApkDir.listFiles()?.forEach(File::delete)
        }
    }

    suspend fun cleanExternalTmpApkDir(){
        withContext(Dispatchers.IO) {
            lspApp.externalCacheDir?.listFiles()?.forEach(File::delete)
        }
    }

    suspend fun getAppInfoFromApks(apks: List<Uri>): Result<List<AppInfo>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                var primary: ApplicationInfo? = null
                val splits = mutableListOf<String>()
                val appInfos = apks.mapNotNull { uri ->
                    val src = DocumentFile.fromSingleUri(lspApp, uri)
                        ?: throw IOException("DocumentFile is null")
                    val name = src.name?.takeIf { it.isNotBlank() }
                        ?: throw IOException("Unable to resolve a file name for $uri")
                    val dst = lspApp.tmpApkDir.resolve(name)
                    copyUriToFile(uri, dst)

                    val appInfo = lspApp.packageManager.getPackageArchiveInfo(
                        dst.absolutePath, PackageManager.GET_META_DATA
                    )?.applicationInfo
                    appInfo?.sourceDir = dst.absolutePath
                    if (appInfo == null) {
                        splits.add(dst.absolutePath)
                        return@mapNotNull null
                    }
                    if (primary == null) {
                        primary = appInfo
                    }
                    val label = lspApp.packageManager.getApplicationLabel(appInfo).toString()
                    AppInfo(appInfo, label,isXposedModule(appInfo))
                }
                primary?.splitSourceDirs = splits.toTypedArray()
                if (appInfos.isEmpty()) throw IOException("No apks")
                appInfos
            }.recoverCatching { t ->
                cleanTmpApkDir()
                Log.e(TAG, "Failed to load apks", t)
                throw t
            }
        }
    }

    /**
     * Loads the original apks of [packageName] from the private backup directory into the
     * temporary apk directory and builds an [AppInfo] for them.
     *
     * Used to patch an already patched app again: the backup holds the untouched apks, and
     * because they land in the cache directory the patch step consumes the copies.
     */
    suspend fun getAppInfoFromBackup(packageName: String): Result<AppInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val backup = OriginApkStore.findBackup(packageName)
                    ?: throw IOException("No original apk backup for $packageName")
                stageBackupApks(backup)
            }.recoverCatching { t ->
                cleanTmpApkDir()
                Log.e(TAG, "Failed to load the original apks of $packageName", t)
                throw t
            }
        }

    private fun stageBackupApks(backup: OriginApkStore.Backup): AppInfo {
        val ordered = listOf(backup.baseApk) + backup.apkFiles.filter { it != backup.baseApk }
        var primary: ApplicationInfo? = null
        val splits = mutableListOf<String>()
        ordered.forEach { file ->
            if (!file.isFile) return@forEach
            val dst = lspApp.tmpApkDir.resolve(file.name)
            file.copyTo(dst, overwrite = true)
            val appInfo = lspApp.packageManager.getPackageArchiveInfo(
                dst.absolutePath, PackageManager.GET_META_DATA
            )?.applicationInfo
            if (appInfo == null) {
                splits.add(dst.absolutePath)
            } else {
                appInfo.sourceDir = dst.absolutePath
                if (primary == null) {
                    primary = appInfo
                }
            }
        }
        val base = primary
            ?: throw IOException("No readable apk in the backup of ${backup.packageName}")
        base.splitSourceDirs = splits.toTypedArray()
        return AppInfo(
            base,
            lspApp.packageManager.getApplicationLabel(base).toString(),
            isXposedModule(base)
        )
    }

    fun getLaunchIntentForPackage(packageName: String): Intent? {
        val intentToResolve = Intent(Intent.ACTION_MAIN)
        intentToResolve.addCategory(Intent.CATEGORY_INFO)
        intentToResolve.setPackage(packageName)
        var ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)

        if (ris.size <= 0) {
            intentToResolve.removeCategory(Intent.CATEGORY_INFO)
            intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER)
            intentToResolve.setPackage(packageName)
            ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)
        }

        if (ris.size <= 0) return null

        return Intent(intentToResolve)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setClassName(
                ris[0].activityInfo.packageName,
                ris[0].activityInfo.name
            )
    }

    fun getSettingsIntent(packageName: String): Intent? {
        val intentToResolve = Intent(Intent.ACTION_MAIN)
        intentToResolve.addCategory(SETTINGS_CATEGORY)
        intentToResolve.setPackage(packageName)
        val ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)

        if (ris.size <= 0) return getLaunchIntentForPackage(packageName)

        return Intent(intentToResolve)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setClassName(
                ris[0].activityInfo.packageName,
                ris[0].activityInfo.name
            )
    }

    /**
     * Copies the apk behind [uri] into [dst].
     *
     * 兜底路径必须重新打开输入流：原来复用的是已经被 `use { }` 关掉的流，所以兜底永远不会成功。
     * 现在每条流都只被关闭一次，正常路径的行为保持不变。
     */
    private fun copyUriToFile(uri: Uri, dst: File) {
        val input = lspApp.contentResolver.openInputStream(uri)
            ?: throw IOException("InputStream is null")

        try {
            input.use { inputStream ->
                dst.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct file copy failed, trying alternative approach", e)
            // 失败可能留下半截文件，删掉才能让下面的兜底真正执行。
            dst.delete()
        }

        if (!dst.exists()) {
            val fallbackInput = lspApp.contentResolver.openInputStream(uri)
                ?: throw IOException("InputStream is null")
            copyFile(fallbackInput, dst)
        }
    }

    fun copyFile(input: InputStream, dst: File) {
        dst.createNewFile()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        input.use { inputStream ->
            dst.outputStream().use { outputStream ->
                var bytes = inputStream.read(buffer)
                while (bytes >= 0) {
                    outputStream.write(buffer, 0, bytes)
                    bytes = inputStream.read(buffer)
                }
            }
        }
    }
}
