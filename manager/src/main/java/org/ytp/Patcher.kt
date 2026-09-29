package org.ytp

import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ytp.config.Configs
import org.ytp.config.MyKeyStore
import org.ytp.patch.YTPPatch
import org.ytp.patch.util.Logger
import org.ytp.share.Constants
import org.ytp.share.PatchConfig
import org.ytp.util.OriginApkStore
import java.io.File
import java.io.IOException

object Patcher {

    class Options(
        private val config: PatchConfig,
        private val apkPaths: List<String>,
        private val embeddedModules: List<String>?,
    ) {
        val packageName: String get() = config.packageName
        val newPackageName: String get() = config.newPackageName
        val sourceApkPaths: List<String> get() = apkPaths

        fun toStringArray(): Array<String> {
            return buildList {
                add("-o"); add(lspApp.tmpApkDir.absolutePath)
                add("-p"); add(config.packageName)
                if (config.newPackageName.isNotEmpty()) {
                    add("-n"); add(config.newPackageName)
                }
                if (config.appLabel.isNotEmpty()) {
                    add("-l"); add(config.appLabel)
                }
                if (Configs.detailPatchLogs) add("-v")
                embeddedModules?.forEach {
                    add("-m"); add(it)
                }

                //v98开始在||后面未来会删除，为了兼容之前的配置文件
                if(!MyKeyStore.file.exists() || (Configs.keyStoreName==Constants.KEY_STORE_ALIAS && Configs.keyStoreAlias!=Constants.KEY_STORE_ALIAS)){
                    MyKeyStore.selectKeyStore(Constants.KEY_STORE_ALIAS)
                }
                addAll(arrayOf("-k", MyKeyStore.file.path, Configs.keyStorePassword, Configs.keyStoreAlias, Configs.keyStoreAliasPassword,Configs.keyStoreName))
                addAll(apkPaths)
            }.toTypedArray()
        }
    }

    /**
     * patch() 全程共享 `externalCacheDir`（会被 deleteRecursively）、`tmpApkDir`、
     * `lspApp.targetApkFiles`（就地 clear）和固定后缀的临时产物：连点两次或并行执行会
     * 毁掉原包备份、产物串包且不报错，所以整个打补丁过程必须串行。
     */
    private val patchMutex = Mutex()

    suspend fun patch(logger: Logger, options: Options) {
        return withContext(Dispatchers.IO) {
            patchMutex.withLock {
                // Keep a private copy of the original apk before it gets patched
                try {
                    OriginApkStore.backup(options.packageName, options.sourceApkPaths, options.newPackageName)
                } catch (t: Throwable) {
                    // Never silently continue without the safety net: tell the user the original
                    // apk cannot be restored automatically.
                    logger.e("警告：原始 APK 备份失败（${t.message}），补丁完成后将无法自动还原原包")
                }
                YTPPatch(logger, *options.toStringArray()).doCommandLine()
                val uri = Configs.storageDirectory?.toUri()
                    ?: throw IOException("Uri is null")
                val root = DocumentFile.fromTreeUri(lspApp, uri)
                    ?: throw IOException("DocumentFile is null")
                val externalCacheDir = lspApp.externalCacheDir
                externalCacheDir?.deleteRecursively()
                // Falls back to the internal cache when external storage is unavailable, so the
                // output files never end up with a null parent (i.e. a relative path).
                val outputDir = externalCacheDir ?: lspApp.cacheDir
                lspApp.targetApkFiles?.clear()
                val apkFileList = arrayListOf<File>()
                lspApp.tmpApkDir.walk()
                    .filter { it.isFile && it.name.endsWith(Constants.PATCH_FILE_SUFFIX) }
                    .forEach { tempApkFile ->
                        val cachedApkFile = File(outputDir, tempApkFile.name)
                        if (tempApkFile.renameTo(cachedApkFile).not()) {
                            tempApkFile.copyTo(cachedApkFile, overwrite = true)
                            tempApkFile.delete()
                        }
                        apkFileList.add(cachedApkFile)

                        val finalFile = root.createFile("application/vnd.android.package-archive", cachedApkFile.name)
                            ?: throw IOException("Failed to write the file： ${cachedApkFile.name}")
                        lspApp.contentResolver.openOutputStream(finalFile.uri)?.use { output ->
                            cachedApkFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        } ?: throw IOException("Unable to open an output stream:  ${finalFile.uri}")
                    }
                lspApp.targetApkFiles = apkFileList
                lspApp.cacheDir.list()?.forEach {
                    if(it.startsWith("tempdir_")){
                        File(lspApp.cacheDir, it).deleteRecursively()
                    }
                }
                logger.i("Patched files are saved to \n${root.uri.lastPathSegment}")
            }
        }
    }
}