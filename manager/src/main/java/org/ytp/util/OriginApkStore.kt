/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.util

import android.content.pm.PackageInfo
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.ytp.R
import org.ytp.lspApp
import org.ytp.share.Constants
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * Keeps a private copy of the original (not patched) APK files so they can be restored later.
 *
 * Layout: filesDir/origin/<packageName>/{base.apk, <split>.apk, meta.properties}
 */
object OriginApkStore {

    private const val TAG = "OriginApkStore"
    private const val ROOT_DIR = "origin"
    private const val META_FILE = "meta.properties"
    private const val TMP_SUFFIX = ".tmp"
    private const val OLD_SUFFIX = ".old"

    private const val KEY_LABEL = "label"
    private const val KEY_VERSION_NAME = "versionName"
    private const val KEY_VERSION_CODE = "versionCode"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_BASE_APK = "baseApk"

    /**
     * Package name the patched build was renamed to. Stored next to the original package name
     * so a renamed patch can still be matched with its origin backup.
     */
    private const val KEY_PATCHED_PACKAGE = "patchedPackageName"

    /**
     * Which tool's patched apk the backup was recovered from (see [recoverFromEmbedded]).
     * Empty for a backup that was taken directly from the installed app before patching.
     */
    private const val KEY_RECOVERED_FROM = "recoveredFrom"

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    /** 本项目改名成 YTP 之前（HkPatch/HKP）打进去的标记。 */
    private const val HKP_PATCH_ASSET_PATH = "assets/hkp/config.json"

    /** 上游 LSPatch 打进去的标记。 */
    private const val LSPATCH_PATCH_ASSET_PATH = "assets/lspatch/config.json"

    /** NPatch 打进去的标记。0.7.2 及更早的 NPatch 用的是 LSPatch 那套路径，只能算作 [PatchSource.LSPATCH]。 */
    private const val NPATCH_PATCH_ASSET_PATH = "assets/npatch/config.json"

    /**
     * 备份时中转用的目录、以及「旧备份先挪走」用的目录，都必须每次运行唯一：
     * 固定名字（`<pkg>.tmp` / `<pkg>.old`）在两个备份/回滚重叠时会互相踩掉。
     * 名字仍然以 [TMP_SUFFIX]/[OLD_SUFFIX] 结尾，[listBackups] 会照旧跳过它们。
     */
    private val workDirSeq = AtomicInteger()

    private fun workDir(root: File, packageName: String, suffix: String): File =
        File(root, "$packageName-${System.currentTimeMillis()}-${workDirSeq.incrementAndGet()}$suffix")

    /** 匹配本包自己遗留的中转目录，不会误伤真正的备份目录（目录名就是包名本身）。 */
    private fun isStaleWorkDir(name: String, packageName: String): Boolean = Regex(
        "^" + Regex.escape(packageName) + "-\\d+-\\d+(" +
            Regex.escape(TMP_SUFFIX) + "|" + Regex.escape(OLD_SUFFIX) + ")$"
    ).matches(name)

    /**
     * 清掉上次备份/回滚中断后留下的中转目录（改用唯一名字后不再自动被覆盖）。
     * [keep] 是本次要用的两个名字，绝不动它们。
     */
    private fun cleanStaleWorkDirs(root: File, packageName: String, keep: Set<String>) {
        root.listFiles()?.forEach { file ->
            if (file.name in keep || !isStaleWorkDir(file.name, packageName)) return@forEach
            if (!file.deleteRecursively()) {
                Log.w(TAG, "Unable to delete the stale work directory ${file.path}")
            }
        }
    }

    class Backup(
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val timestamp: Long,
        val dir: File,
        val apkFiles: List<File>,
        val baseApk: File,
        /** Empty unless the patch was renamed to another package name. */
        val patchedPackageName: String = "",
        /** Which tool's patch the apk was recovered from, empty for a fresh backup. */
        val recoveredFrom: String = "",
    ) {
        val size: Long get() = apkFiles.sumOf { if (it.isFile) it.length() else 0L }
    }

    fun rootDir(): File = File(lspApp.filesDir, ROOT_DIR)

    /**
     * Every asset path that marks an apk as patched: the current one, the branding this fork
     * used before it was renamed, and the ones of upstream LSPatch and NPatch. The patch list of
     * the manager shows all of them (see [patchSourceOf]), the backup only has to avoid storing
     * an already patched build as if it were an original one.
     */
    private val ANY_PATCH_ASSET_PATHS = listOf(
        Constants.CONFIG_ASSET_PATH,
        HKP_PATCH_ASSET_PATH,
        LSPATCH_PATCH_ASSET_PATH,
        NPATCH_PATCH_ASSET_PATH,
    )

    /** Which tool patched an apk. Only [YTP] patches are managed by this manager. */
    enum class PatchSource { YTP, HKP, LSPATCH, NPATCH, UNKNOWN }

    /**
     * The tool that patched the given APK, or null when it carries no known patch marker.
     *
     * 读不出 zip 时返回 [PatchSource.UNKNOWN]（同 [isPatchedApk]）：宁可让用户看到一条来源
     * 未知的条目，也不能把一个可能已打补丁的包装成原包。
     */
    fun patchSourceOf(file: File): PatchSource? = try {
        ZipFile(file).use { zip ->
            when {
                zip.getEntry(Constants.CONFIG_ASSET_PATH) != null -> PatchSource.YTP
                zip.getEntry(HKP_PATCH_ASSET_PATH) != null -> PatchSource.HKP
                // NPatch 0.7.2 及更早版本写的就是 LSPatch 那套路径，无法与 LSPatch 区分；
                // 两者都能靠内嵌的 origin.apk 还原原包，所以这里合并成一个来源没问题。
                zip.getEntry(LSPATCH_PATCH_ASSET_PATH) != null -> PatchSource.LSPATCH
                zip.getEntry(NPATCH_PATCH_ASSET_PATH) != null -> PatchSource.NPATCH
                else -> null
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to inspect ${file.path}; assuming it is already patched", t)
        PatchSource.UNKNOWN
    }

    /**
     * True when the given APK was patched by this manager — only YTP patches are accepted.
     *
     * 读不出 zip 时按「可能已经打过补丁」处理并返回 true：false 会让 [backup] 把补丁包
     * 当成原包存下来，覆盖掉真正的原包备份，之后再打补丁就没有还原点了。
     */
    fun isPatchedApk(file: File): Boolean = try {
        ZipFile(file).use { zip -> zip.getEntry(Constants.CONFIG_ASSET_PATH) != null }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to inspect ${file.path}; assuming it is already patched", t)
        true
    }

    /**
     * True when the given APK carries a patch of this manager or of a framework it forked from.
     * 同 [isPatchedApk]：读不出来时保守地当作「可能已打补丁」，宁可跳过备份也不能覆盖原包。
     */
    private fun carriesAnyPatch(file: File): Boolean = try {
        ZipFile(file).use { zip -> ANY_PATCH_ASSET_PATHS.any { zip.getEntry(it) != null } }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to inspect ${file.path}; assuming it is already patched", t)
        true
    }

    /**
     * Copies [sourceApks] (base first, then splits) into the private backup directory.
     * Nothing is written when the source is already a patched APK or when the very same
     * version has been backed up before.
     *
     * [skipPatchCheck] is only for [recoverFromEmbedded]: the apks that come out of an LSPatch /
     * NPatch bundle are originals by construction, but the installed apk they are read from
     * obviously carries a patch marker.
     */
    fun backup(
        packageName: String,
        sourceApks: List<String>,
        patchedPackageName: String = "",
        skipPatchCheck: Boolean = false,
        recoveredFrom: String = "",
    ): Backup? {
        if (packageName.isBlank()) return null
        val sources = sourceApks.map(::File).filter { it.isFile }
        if (sources.isEmpty()) {
            Log.w(TAG, "No readable source apk for $packageName")
            return null
        }
        val base = sources.first()
        if (!skipPatchCheck && carriesAnyPatch(base)) {
            Log.w(TAG, "$packageName is already patched, backup skipped")
            return null
        }
        val versionCode = versionCodeOf(packageName, base)
        readBackup(packageName)?.let { existing ->
            if (existing.versionCode == versionCode) {
                if (existing.patchedPackageName != patchedPackageName) {
                    storePatchedPackageName(existing.dir, patchedPackageName)
                }
                Log.i(TAG, "Origin backup of $packageName ($versionCode) already exists")
                return readBackup(packageName) ?: existing
            }
        }
        val root = rootDir()
        if (!root.isDirectory && !root.mkdirs()) {
            Log.e(TAG, "Unable to create ${root.path}")
            return null
        }
        val target = File(root, packageName)
        val tmp = workDir(root, packageName, TMP_SUFFIX)
        val old = workDir(root, packageName, OLD_SUFFIX)
        return try {
            cleanStaleWorkDirs(root, packageName, setOf(tmp.name, old.name))
            if (!tmp.mkdirs()) throw IOException("Unable to create ${tmp.path}")
            sources.forEachIndexed { index, src ->
                val name = if (index == 0) "base.apk" else src.name
                src.copyTo(File(tmp, name), overwrite = true)
            }
            val meta = Properties().apply {
                setProperty(KEY_LABEL, labelOf(packageName, base))
                setProperty(KEY_VERSION_NAME, versionNameOf(packageName, base))
                setProperty(KEY_VERSION_CODE, versionCode.toString())
                setProperty(KEY_TIMESTAMP, System.currentTimeMillis().toString())
                setProperty(KEY_BASE_APK, "base.apk")
                if (patchedPackageName.isNotEmpty()) {
                    setProperty(KEY_PATCHED_PACKAGE, patchedPackageName)
                }
                if (recoveredFrom.isNotEmpty()) {
                    setProperty(KEY_RECOVERED_FROM, recoveredFrom)
                }
            }
            File(tmp, META_FILE).outputStream().use { meta.store(it, null) }
            // Move the previous backup aside instead of deleting it: a failure between the two
            // renames can then be rolled back and never leaves the user without a copy.
            if (target.exists() && !target.renameTo(old)) {
                throw IOException("Unable to move ${target.path} aside")
            }
            if (!tmp.renameTo(target)) {
                // 回滚失败会让用户同时失去旧备份和新备份，必须显式抛错而不是只打日志。
                if (old.exists() && !old.renameTo(target)) {
                    throw IOException(
                        "Unable to move ${tmp.path} to ${target.path} and unable to restore " +
                            "the previous backup from ${old.path}"
                    )
                }
                throw IOException("Unable to move ${tmp.path} to ${target.path}")
            }
            if (old.exists() && !old.deleteRecursively()) {
                // 新备份已经就位，删除失败只留下一个可被下次清理的中转目录。
                Log.w(TAG, "Unable to delete the previous backup of $packageName at ${old.path}")
            }
            Log.i(TAG, "Origin backup stored for $packageName ($versionCode)")
            readBackup(packageName)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to back up $packageName", t)
            tmp.deleteRecursively()
            // 旧备份已经挪走而新备份没就位时，尽力把它放回原位，避免用户两手空空。
            if (!target.exists() && old.exists() && !old.renameTo(target)) {
                Log.e(TAG, "Unable to restore the previous backup of $packageName from ${old.path}")
            }
            // A real failure must not look like "nothing to back up": the caller reports it to the user.
            throw if (t is IOException) t else IOException("Failed to back up $packageName", t)
        }
    }

    /**
     * Rebuilds the original apk of [packageName] from the embedded copy an LSPatch / NPatch patch
     * keeps inside the patched apk and stores it as a normal backup, so the app can be re-patched
     * from the untouched original and exported again.
     *
     * A YTP patch — and one of the branding this fork used before — rewrites the app's dex in
     * place and keeps no copy, so for those this always fails and the caller tells the user.
     */
    fun recoverFromEmbedded(packageName: String): Result<Backup> = try {
        val appInfo = installedInfo(packageName)?.applicationInfo
            ?: throw IOException(lspApp.getString(R.string.origin_apk_not_installed, packageName))
        val sources = (listOf(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty().toList())
            .filterNotNull()
            .map(::File)
            .filter { it.isFile }
        if (sources.isEmpty()) {
            throw IOException(lspApp.getString(R.string.origin_apk_none_found, packageName))
        }
        val from = patchSourceOf(sources.first())
        val temp = File(lspApp.cacheDir, "origin-recovery/$packageName")
        temp.deleteRecursively()
        try {
            val recovered = OriginApkRecovery.recover(sources, temp)
            // The recovered base apk is what the developer shipped, so its package name is the
            // original one — different from the installed one when the patch was renamed.
            val originalPackage = archiveInfo(recovered.first())?.packageName ?: packageName
            val backup = backup(
                packageName = originalPackage,
                sourceApks = recovered.map { it.absolutePath },
                patchedPackageName = if (originalPackage != packageName) packageName else "",
                skipPatchCheck = true,
                recoveredFrom = from?.name.orEmpty().lowercase(),
            ) ?: throw IOException(lspApp.getString(R.string.origin_apk_store_failed, packageName))
            Log.i(TAG, "Recovered the original apk of $packageName (from ${from ?: PatchSource.UNKNOWN})")
            Result.success(backup)
        } finally {
            temp.deleteRecursively()
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to recover the original apk of $packageName", t)
        Result.failure(t)
    }

    /**
     * Stores an apk the user picked by hand as the original of [patchedPackageName].
     *
     * A YTP patch — and one of the branding this fork used before — rewrites the app's dex in place
     * and keeps no copy of the original, so when the backup is missing as well there is nothing to
     * extract. What is left is the untouched apk the user installed the app from, which is what
     * this registers: the picked file has to be an apk that carries no patch marker, and it is
     * stored under its own (original) package name, with [patchedPackageName] recorded when the
     * patch was installed under a different name.
     */
    fun registerOriginal(patchedPackageName: String, source: File): Result<Backup> = try {
        if (!source.isFile) throw IOException(lspApp.getString(R.string.origin_apk_not_a_file, source.name))
        val picked = archiveInfo(source)
            ?: throw IOException(lspApp.getString(R.string.origin_apk_not_an_apk, source.name))
        patchSourceOf(source)?.let {
            throw IllegalArgumentException(
                lspApp.getString(R.string.origin_apk_already_patched, source.name, it.name)
            )
        }
        val originalPackage = picked.packageName?.takeIf { it.isNotBlank() } ?: patchedPackageName
        val temp = File(lspApp.cacheDir, "manual-origin/$patchedPackageName")
        temp.deleteRecursively()
        try {
            if (!temp.mkdirs() && !temp.isDirectory) {
                throw IOException(lspApp.getString(R.string.origin_apk_create_failed, temp.path))
            }
            val staged = File(temp, "base.apk")
            source.copyTo(staged, overwrite = true)
            val backup = backup(
                packageName = originalPackage,
                sourceApks = listOf(staged.absolutePath),
                patchedPackageName = if (originalPackage != patchedPackageName) patchedPackageName else "",
                // 上面已经确认这个文件本身不是补丁包了。
                skipPatchCheck = true,
                recoveredFrom = "manual",
            ) ?: throw IOException(lspApp.getString(R.string.origin_apk_store_failed, patchedPackageName))
            Log.i(TAG, "Registered the picked original apk of $patchedPackageName ($originalPackage)")
            Result.success(backup)
        } finally {
            temp.deleteRecursively()
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to register the original apk of $patchedPackageName", t)
        Result.failure(t)
    }

    fun listBackups(): List<Backup> {
        val root = rootDir()
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && !it.name.endsWith(TMP_SUFFIX) && !it.name.endsWith(OLD_SUFFIX) }
            ?.mapNotNull { readBackup(it.name) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    fun delete(backup: Backup): Boolean = try {
        backup.dir.deleteRecursively()
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to delete ${backup.dir.path}", t)
        false
    }

    /**
     * Copies every apk of [backup] into a new folder below the granted SAF tree [treeUri].
     * Returns the folder name the files were written to.
     */
    fun exportTo(backup: Backup, treeUri: Uri): Result<String> = try {
        val resolver = lspApp.contentResolver
        val root = DocumentFile.fromTreeUri(lspApp, treeUri) ?: throw IOException("DocumentFile is null")
        val folderName = exportFolderName(backup)
        val target = root.findFile(folderName)?.takeIf { it.isDirectory }
            ?: root.createDirectory(folderName)
            ?: throw IOException("Unable to create $folderName")
        backup.apkFiles.forEach { file ->
            val doc = target.findFile(file.name)?.takeIf { it.isFile }
                ?: target.createFile(APK_MIME_TYPE, file.name)
                ?: throw IOException("Unable to create ${file.name}")
            resolver.openOutputStream(doc.uri, "wt")?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Unable to open ${doc.uri}")
        }
        Log.i(TAG, "Exported ${backup.packageName} to $folderName")
        Result.success(folderName)
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to export ${backup.packageName}", t)
        Result.failure(t)
    }

    private fun exportFolderName(backup: Backup): String {
        val label = backup.label.ifBlank { backup.packageName }
        val version = backup.versionName.ifBlank { backup.versionCode.toString() }
        return sanitizeFileName("${label}_$version")
    }

    private fun sanitizeFileName(raw: String): String = raw
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifBlank { "backup" }
        .take(64)

    fun isInstalled(packageName: String): Boolean =
        try {
            lspApp.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (t: Throwable) {
            false
        }

    /** True when [packageName] is installed and its APK carries the patch assets. */
    fun isPatchedInstalled(packageName: String): Boolean = try {
        val info = lspApp.packageManager.getPackageInfo(packageName, 0)
        info.applicationInfo?.let { isPatchedApk(File(it.sourceDir)) } ?: false
    } catch (t: Throwable) {
        false
    }

    private fun readBackup(packageName: String): Backup? {
        val dir = File(rootDir(), packageName)
        val metaFile = File(dir, META_FILE)
        if (!dir.isDirectory || !metaFile.isFile) return null
        val meta = Properties().apply {
            try {
                metaFile.inputStream().use { load(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to read ${metaFile.path}", t)
            }
        }
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: return null
        if (files.isEmpty()) return null
        val base = File(dir, meta.getProperty(KEY_BASE_APK) ?: "").takeIf { it.isFile } ?: files.first()
        return Backup(
            packageName = packageName,
            label = meta.getProperty(KEY_LABEL) ?: packageName,
            versionName = meta.getProperty(KEY_VERSION_NAME).orEmpty(),
            versionCode = meta.getProperty(KEY_VERSION_CODE)?.toLongOrNull() ?: 0L,
            timestamp = meta.getProperty(KEY_TIMESTAMP)?.toLongOrNull() ?: dir.lastModified(),
            dir = dir,
            apkFiles = listOf(base) + files.filter { it != base },
            baseApk = base,
            patchedPackageName = meta.getProperty(KEY_PATCHED_PACKAGE).orEmpty(),
            recoveredFrom = meta.getProperty(KEY_RECOVERED_FROM).orEmpty(),
        )
    }

    /** Writes (or clears, for an empty value) the renamed package name of an existing backup. */
    private fun storePatchedPackageName(dir: File, patchedPackageName: String) {
        val metaFile = File(dir, META_FILE)
        if (!metaFile.isFile) return
        val meta = Properties().apply {
            try {
                metaFile.inputStream().use { load(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to read ${metaFile.path}", t)
            }
        }
        if (patchedPackageName.isEmpty()) {
            meta.remove(KEY_PATCHED_PACKAGE)
        } else {
            meta.setProperty(KEY_PATCHED_PACKAGE, patchedPackageName)
        }
        try {
            metaFile.outputStream().use { meta.store(it, null) }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to update ${metaFile.path}", t)
        }
    }

    /** Finds a backup by its original or by its renamed package name. */
    fun findBackup(packageName: String): Backup? =
        listBackups().firstOrNull {
            it.packageName == packageName || it.patchedPackageName == packageName
        }

    private fun archiveInfo(file: File): PackageInfo? = try {
        @Suppress("DEPRECATION")
        lspApp.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to read ${file.path}", t)
        null
    }

    private fun installedInfo(packageName: String): PackageInfo? = try {
        @Suppress("DEPRECATION")
        lspApp.packageManager.getPackageInfo(packageName, 0)
    } catch (t: Throwable) {
        null
    }

    private fun versionCodeOf(packageName: String, base: File): Long =
        installedInfo(packageName)?.longVersionCode ?: archiveInfo(base)?.longVersionCode ?: 0L

    private fun versionNameOf(packageName: String, base: File): String =
        installedInfo(packageName)?.versionName ?: archiveInfo(base)?.versionName.orEmpty()

    private fun labelOf(packageName: String, base: File): String {
        val pm = lspApp.packageManager
        val info = installedInfo(packageName) ?: archiveInfo(base)
        val appInfo = info?.applicationInfo
        if (appInfo != null) {
            return try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (t: Throwable) {
                packageName
            }
        }
        return packageName
    }
}
