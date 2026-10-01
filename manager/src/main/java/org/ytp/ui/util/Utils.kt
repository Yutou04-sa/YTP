/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.ytp.R
import java.io.File
import java.security.MessageDigest

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
            var session: PackageInstaller.Session? = null
            // Only true once commit() returned: after that the installer owns the session and it
            // must not be abandoned any more.
            var committed = false
            try {
                val opened = installer.openSession(sessionId)
                session = opened
                opened.use { active ->
                    files.forEach { file ->
                        active.openWrite(file.name, 0, file.length()).use { output ->
                            file.inputStream().use { input -> input.copyTo(output) }
                            active.fsync(output)
                        }
                    }
                    val callback = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        callback,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    // The commit itself must survive a cancelled caller, otherwise the session can
                    // be left half-committed and leak.
                    withContext(NonCancellable) {
                        active.commit(pendingIntent.intentSender)
                        committed = true
                    }
                }
                // Silence here does not mean failure: the install may still be running.
                withTimeoutOrNull(INSTALL_TIMEOUT_MS) { result.await() }
                    ?: InstallResult(
                        PackageInstaller.STATUS_PENDING_USER_ACTION,
                        "The installer did not report a result in time",
                    )
            } catch (e: Exception) {
                // Failure or cancellation before the commit: give the session back so the copied
                // apk bytes and the session slot are released instead of leaking.
                if (!committed) {
                    runCatching { withContext(NonCancellable) { session?.abandon() } }
                }
                throw e
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        } catch (e: CancellationException) {
            // 调用方被取消（例如旋转屏幕）时必须继续向上传播，
            // 上面已经 abandon 了未提交的 session。
            throw e
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

/** How long the uninstall of a conflicting package is waited for before giving up. */
private const val UNINSTALL_TIMEOUT_MS = 2 * 60 * 1000L

/** Poll interval while waiting for the uninstalled package to disappear. */
private const val UNINSTALL_POLL_MS = 500L

/** What handing the patched apks to the installer will do to the device. */
enum class InstallPlan {
    /** No app with that package name is installed: a plain install. */
    NEW_INSTALL,

    /** Installed and signed with the same key: the patched apks replace it directly. */
    OVERWRITE,

    /** Installed but signed with another key: the system refuses the update. */
    SIGNER_MISMATCH,
}

/** The installed app a patched apk would replace, as seen by [planInstall]. */
data class InstallTarget(
    val packageName: String,
    val installedVersionCode: Long?,
    val apkVersionCode: Long,
    val plan: InstallPlan,
)

/** Outcome of [installPatched]: the install status plus the plan it was decided from. */
data class InstallOutcome(
    val result: InstallResult,
    val target: InstallTarget?,
    /** True when [target] has a signature conflict and [force] was not requested. */
    val needsForce: Boolean = false,
)

/**
 * Decides what installing [apkFiles] would do to this device: install from scratch, overwrite an
 * app that was signed with the same key, or hit a signature conflict.
 *
 * Returns null when the apks carry no readable package name.
 */
suspend fun planInstall(context: Context, apkFiles: List<File>): InstallTarget? =
    withContext(Dispatchers.IO) {
        val files = apkFiles.filter { it.isFile }
        if (files.isEmpty()) return@withContext null
        val packageManager = context.packageManager
        val archive = files.firstNotNullOfOrNull { file ->
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } ?: return@withContext null
        val packageName = archive.packageName ?: return@withContext null
        val apkSigners = signerDigests(archive)
        val installed = try {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val plan = when {
            installed == null -> InstallPlan.NEW_INSTALL
            // 已安装应用的签名历史（换过签名密钥时会有多个）与本包签名有交集就允许覆盖安装。
            signerDigests(installed).any { it in apkSigners } -> InstallPlan.OVERWRITE
            else -> InstallPlan.SIGNER_MISMATCH
        }
        InstallTarget(packageName, installed?.longVersionCode, archive.longVersionCode, plan)
    }

/**
 * Installs the patched apks after checking what they are about to replace.
 *
 * Nothing installed → plain install. Installed with the same signing key → overwrite. Installed
 * with a different key → the system would reject the update, so the caller gets
 * [InstallOutcome.needsForce] back and only [force] turns that into "uninstall the old app
 * first, then install" (the setting [org.ytp.config.Configs.forceInstall] passes force without
 * asking again).
 */
suspend fun installPatched(
    context: Context,
    apkFiles: List<File>,
    force: Boolean = false,
): InstallOutcome {
    val target = planInstall(context, apkFiles)
    if (target?.plan == InstallPlan.SIGNER_MISMATCH) {
        if (!force) {
            return InstallOutcome(
                InstallResult(
                    PackageInstaller.STATUS_FAILURE,
                    context.getString(R.string.install_error_signer_mismatch, target.packageName),
                ),
                target,
                needsForce = true,
            )
        }
        Log.w(TAG, "Signature mismatch on ${target.packageName}: uninstalling it first")
        if (!uninstallAndWait(context, target.packageName)) {
            return InstallOutcome(
                InstallResult(
                    PackageInstaller.STATUS_FAILURE,
                    context.getString(R.string.install_error_still_installed, target.packageName),
                ),
                target,
            )
        }
    }
    return InstallOutcome(installApks(context, apkFiles), target)
}

/**
 * Opens the system uninstall screen and waits until the package is really gone.
 *
 * The uninstall screen reports nothing back, so the package list is polled instead; returns false
 * when the user kept the app (or when it is still there after [UNINSTALL_TIMEOUT_MS]).
 */
private suspend fun uninstallAndWait(context: Context, packageName: String): Boolean =
    withContext(Dispatchers.IO) {
        if (!isPackageInstalled(context, packageName)) return@withContext true
        uninstallApk(context, packageName)
        withTimeoutOrNull(UNINSTALL_TIMEOUT_MS) {
            while (isPackageInstalled(context, packageName)) delay(UNINSTALL_POLL_MS)
        }
        !isPackageInstalled(context, packageName)
    }

/** True when [packageName] is currently installed. */
private fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
    context.packageManager.getPackageInfo(packageName, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

/**
 * SHA-256 digests (base64) of every certificate that may sign [info], so two packages can be
 * compared without depending on the order the signatures are reported in.
 */
private fun signerDigests(info: PackageInfo): Set<String> {
    val signing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo else null
    val signatures: List<Signature> = if (signing != null) {
        signing.apkContentsSigners.toList() +
            (signing.signingCertificateHistory?.toList() ?: emptyList())
    } else {
        @Suppress("DEPRECATION")
        info.signatures?.toList() ?: emptyList()
    }
    val digest = MessageDigest.getInstance("SHA-256")
    return signatures.mapTo(mutableSetOf()) {
        Base64.encodeToString(digest.digest(it.toByteArray()), Base64.NO_WRAP)
    }
}