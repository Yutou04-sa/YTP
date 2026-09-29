package org.ytp.ui.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

const val ACTION_INSTALL_RESULT = "org.ytp.manager.INSTALL_RESULT"

private const val TAG = "YTP"

/** Give up waiting for the installer result after this long and report "still running". */
private const val INSTALL_TIMEOUT_MS = 5 * 60 * 1000L

/** Outcome of an install attempt: the [PackageInstaller] status plus its status message. */
data class InstallResult(val status: Int, val message: String? = null)

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

/** Hands a single apk to the system installer. Returns false when no installer accepted it. */
fun installApk(context: Context, apkFile: File): Boolean {
    return try {
        val apkUri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory("android.intent.category.DEFAULT")
            setDataAndType(apkUri, "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.e(TAG, "No installer accepted ${apkFile.name}", e)
        false
    }
}

/**
 * Installs one or several apk files (base apk first, then its splits) through a
 * package installer session, which is the only way to install split apks at once.
 *
 * The copy always happens on [Dispatchers.IO] — moving hundreds of megabytes on the main thread
 * freezes the UI — and the install result is awaited, so the caller can report the real status
 * instead of assuming the hand-off succeeded.
 */
suspend fun installApks(context: Context, apkFiles: List<File>): InstallResult =
    withContext(Dispatchers.IO) {
        val files = apkFiles.filter { it.isFile }
        if (files.isEmpty()) {
            return@withContext InstallResult(PackageInstaller.STATUS_FAILURE, "No apk file to install")
        }
        if (files.size == 1) {
            // The system installer shows its own progress screen: nothing to wait for.
            return@withContext if (installApk(context, files.first())) {
                InstallResult(
                    PackageInstaller.STATUS_PENDING_USER_ACTION,
                    "Handed over to system installer",
                )
            } else {
                InstallResult(
                    PackageInstaller.STATUS_FAILURE,
                    "No installer accepted ${files.first().name}",
                )
            }
        }
        try {
            val installer = context.packageManager.packageInstaller
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            try {
                params.setSize(files.sumOf { it.length() })
            } catch (_: Exception) {
            }
            val sessionId = installer.createSession(params)
            val result = CompletableDeferred<InstallResult>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent == null) return
                    val status = intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE,
                    )
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        // The installer wants the user to confirm: show its dialog and keep waiting.
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                            ?.let { confirm ->
                                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                try {
                                    context.startActivity(confirm)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to show the installer confirmation", e)
                                }
                            }
                        return
                    }
                    result.complete(
                        InstallResult(
                            status,
                            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                        )
                    )
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_INSTALL_RESULT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            try {
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
                // Silence here does not mean failure: the install may still be running.
                withTimeoutOrNull(INSTALL_TIMEOUT_MS) { result.await() }
                    ?: InstallResult(
                        PackageInstaller.STATUS_PENDING_USER_ACTION,
                        "The installer did not report a result in time",
                    )
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install ${files.joinToString { it.name }}", e)
            InstallResult(PackageInstaller.STATUS_FAILURE, e.message)
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