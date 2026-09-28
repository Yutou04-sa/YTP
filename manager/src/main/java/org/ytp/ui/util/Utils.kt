package org.ytp.ui.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

const val ACTION_INSTALL_RESULT = "org.ytp.manager.INSTALL_RESULT"

val LazyListState.lastVisibleItemIndex
    get() = layoutInfo.visibleItemsInfo.lastOrNull()?.index

val LazyListState.lastItemIndex
    get() = layoutInfo.totalItemsCount.let { if (it == 0) null else it }

val LazyListState.isScrolledToEnd
    get() = lastVisibleItemIndex == lastItemIndex

fun checkIsApkFixedByLSP(context: Context, packageName: String): Boolean {
    return try {
        val app =
            context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        (app.metaData?.containsKey(org.ytp.share.Constants.LOWER_CASE_NAME) != true)
    } catch (_: PackageManager.NameNotFoundException) {
        Log.e("YTP", "Package not found: $packageName")
        false
    } catch (e: Exception) {
        Log.e("YTP", "Unexpected error in checkIsApkFixedByLSP", e)
        false
    }
}

fun installApk(context: Context, apkFile: File) {
    try {
        val apkUri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory("android.intent.category.DEFAULT")
            setDataAndType(apkUri, "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

/**
 * Installs one or several apk files (base apk first, then its splits) through a
 * package installer session, which is the only way to install split apks at once.
 */
fun installApks(context: Context, apkFiles: List<File>): Boolean {
    val files = apkFiles.filter { it.isFile }
    if (files.isEmpty()) return false
    if (files.size == 1) {
        installApk(context, files.first())
        return true
    }
    return try {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        try {
            params.setSize(files.sumOf { it.length() })
        } catch (_: Exception) {
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            files.forEach { file ->
                session.openWrite(file.name, 0, file.length()).use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
            }
            val callback = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pendingIntent.intentSender)
        }
        true
    } catch (e: Exception) {
        Log.e("YTP", "Failed to install ${files.joinToString { it.name }}", e)
        false
    }
}

/** Opens the system uninstall screen for the given package. */
fun uninstallApk(context: Context, packageName: String) {
    try {
        val intent = Intent(Intent.ACTION_DELETE, "package:$packageName".toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("YTP", "Failed to uninstall $packageName", e)
    }
}