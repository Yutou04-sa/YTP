package org.ytp.util

import android.content.pm.PackageInfo
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.ytp.lspApp
import org.ytp.share.Constants
import java.io.File
import java.io.IOException
import java.util.Properties
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

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

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
    ) {
        val size: Long get() = apkFiles.sumOf { if (it.isFile) it.length() else 0L }
    }

    fun rootDir(): File = File(lspApp.filesDir, ROOT_DIR)

    /**
     * Every asset path that marks an apk as patched: the current one, the branding this fork
     * used before it was renamed, and the one of upstream LSPatch. Only used to avoid backing
     * up an already patched build as if it were an original one.
     */
    private val ANY_PATCH_ASSET_PATHS = listOf(
        Constants.CONFIG_ASSET_PATH,
        "assets/hkp/config.json",
        "assets/lspatch/config.json",
    )

    /** True when the given APK was patched by this manager — only YTP patches are accepted. */
    fun isPatchedApk(file: File): Boolean = try {
        ZipFile(file).use { zip -> zip.getEntry(Constants.CONFIG_ASSET_PATH) != null }
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to inspect ${file.path}", t)
        false
    }

    /** True when the given APK carries a patch of this manager or of a framework it forked from. */
    private fun carriesAnyPatch(file: File): Boolean = try {
        ZipFile(file).use { zip -> ANY_PATCH_ASSET_PATHS.any { zip.getEntry(it) != null } }
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to inspect ${file.path}", t)
        false
    }

    /**
     * Copies [sourceApks] (base first, then splits) into the private backup directory.
     * Nothing is written when the source is already a patched APK or when the very same
     * version has been backed up before.
     */
    fun backup(
        packageName: String,
        sourceApks: List<String>,
        patchedPackageName: String = "",
    ): Backup? {
        if (packageName.isBlank()) return null
        val sources = sourceApks.map(::File).filter { it.isFile }
        if (sources.isEmpty()) {
            Log.w(TAG, "No readable source apk for $packageName")
            return null
        }
        val base = sources.first()
        if (carriesAnyPatch(base)) {
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
        val tmp = File(root, packageName + TMP_SUFFIX)
        return try {
            tmp.deleteRecursively()
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
            }
            File(tmp, META_FILE).outputStream().use { meta.store(it, null) }
            target.deleteRecursively()
            if (!tmp.renameTo(target)) throw IOException("Unable to move ${tmp.path} to ${target.path}")
            Log.i(TAG, "Origin backup stored for $packageName ($versionCode)")
            readBackup(packageName)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to back up $packageName", t)
            tmp.deleteRecursively()
            null
        }
    }

    fun listBackups(): List<Backup> {
        val root = rootDir()
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && !it.name.endsWith(TMP_SUFFIX) }
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
