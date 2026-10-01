/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.page

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ytp.R
import org.ytp.config.Configs
import org.ytp.ui.component.CenterTopBar
import org.ytp.ui.component.YtpCard
import org.ytp.ui.component.YtpEmptyState
import org.ytp.ui.theme.YtpColors
import org.ytp.ui.util.LocalSnackbarHost
import org.ytp.ui.util.InstallOutcome
import org.ytp.ui.util.InstallPlan
import org.ytp.ui.util.InstallTarget
import org.ytp.ui.util.installPatched
import org.ytp.ui.util.planInstall
import org.ytp.util.OriginApkStore
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private data class RestoreItem(
    val backup: OriginApkStore.Backup,
    /** 补丁实际安装在系统里的包名：改过包名的补丁用它，否则就是备份的原包名。 */
    val installedName: String,
    val installed: Boolean,
    val patched: Boolean
)

/**
 * Lists the original apks that were backed up before patching, so the patched
 * version can be replaced by the untouched one again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination
@Composable
fun RestoreScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<RestoreItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<OriginApkStore.Backup?>(null) }
    var pendingExtract by remember { mutableStateOf<OriginApkStore.Backup?>(null) }
    // 卸载/安装都交给系统界面去做（ACTION_DELETE / 安装器），拿不到结果回调，所以这里记一笔
    // "刚发起过外部动作"，回到前台后连查几次，列表就不会等到下一次自动刷新才更新。
    var pendingExternalAction by remember { mutableStateOf(false) }
    var settleTicks by remember { mutableIntStateOf(0) }
    // 每次扫描都会对每个备份做 PackageManager 查询，刚扫完就别再扫（旋转/快速前后台切换）。
    val refreshedAt = remember { AtomicLong(0L) }

    LaunchedEffect(settleTicks) {
        if (settleTicks == 0) return@LaunchedEffect
        repeat(SETTLE_REFRESH_COUNT) {
            refreshKey++
            delay(SETTLE_REFRESH_INTERVAL_MS)
        }
    }

    LaunchedEffect(refreshKey) {
        if (items.isEmpty()) loading = true
        items = withContext(Dispatchers.IO) {
            OriginApkStore.listBackups().map { backup ->
                // Renamed patches are installed under their new package name.
                val installedName = backup.patchedPackageName.ifEmpty { backup.packageName }
                RestoreItem(
                    backup = backup,
                    installedName = installedName,
                    installed = OriginApkStore.isInstalled(installedName),
                    patched = OriginApkStore.isPatchedInstalled(installedName)
                )
            }
        }
        loading = false
        refreshedAt.set(SystemClock.elapsedRealtime())
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    if (pendingExternalAction) {
                        // 刚从系统卸载界面/安装器回来：立刻查，并在几秒内settle 几次。
                        pendingExternalAction = false
                        settleTicks++
                    } else if (isRestoreRefreshDue(refreshedAt)) {
                        refreshKey++
                    }
                }

                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Backups and installs happen outside this screen, so keep the list in sync while it is visible.
    LaunchedEffect(resumed) {
        while (resumed) {
            delay(AUTO_REFRESH_INTERVAL_MS)
            // 上一次扫描还没结束（或者刚刚结束）就跳过这一轮，避免扫描任务堆积。
            if (isRestoreRefreshDue(refreshedAt)) refreshKey++
        }
    }

    // 卸载是交给系统界面做的，靠它的回执（EXTRA_RETURN_RESULT）或回前台时的补查来更新列表。
    val uninstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        settleTicks++
    }

    val extractLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val backup = pendingExtract
        pendingExtract = null
        val treeUri = result.data?.data
        if (backup == null || result.resultCode != Activity.RESULT_OK || treeUri == null) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val exported = withContext(Dispatchers.IO) { OriginApkStore.exportTo(backup, treeUri) }
            snackbarHost.showSnackbar(
                exported.fold(
                    onSuccess = { folder -> context.getString(R.string.restore_extract_done, folder) },
                    onFailure = { context.getString(R.string.restore_extract_failed) }
                )
            )
        }
    }

    // 安装前的签名检查：设备上装了同名应用但签名不同时不能直接覆盖安装，先把冲突摆出来，
    // 由用户决定要不要强制安装（先卸载已安装的版本再装原始包）。
    var installConflict by remember { mutableStateOf<InstallTarget?>(null) }
    var conflictFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    // 已交给系统安装器（或已经装好）都算交接成功，其它状态才算安装失败。
    suspend fun reportInstall(outcome: InstallOutcome) {
        val handedOver = outcome.result.status == PackageInstaller.STATUS_SUCCESS ||
            outcome.result.status == PackageInstaller.STATUS_PENDING_USER_ACTION
        snackbarHost.showSnackbar(
            context.getString(
                if (handedOver) R.string.restore_handed_over else R.string.restore_install_failed
            )
        )
    }

    suspend fun installBackup(files: List<File>, force: Boolean) {
        val target = planInstall(context, files)
        val forced = force || Configs.forceInstall
        if (target?.plan == InstallPlan.SIGNER_MISMATCH && !forced) {
            installConflict = target
            conflictFiles = files
            return
        }
        reportInstall(installPatched(context, files, forced))
    }

    Scaffold(
        containerColor = YtpColors.PageContainer,
        topBar = {
            CenterTopBar(
                text = stringResource(R.string.restore_original_apk),
                onBackClick = navigator::popBackStack,
                containerColor = YtpColors.PageChrome,
                scrolledContainerColor = YtpColors.PageChrome,
                contentColor = YtpColors.TextPrimary
            )
        }
    ) { innerPadding ->
        if (!loading && items.isEmpty()) {
            YtpEmptyState(
                title = stringResource(R.string.restore_empty_title),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                icon = Icons.Outlined.Inventory2,
                supportingText = stringResource(R.string.restore_empty_description)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items, key = { it.backup.packageName }) { item ->
                    RestoreCard(
                        item = item,
                        onUninstall = {
                            // 系统卸载界面能回结果（EXTRA_RETURN_RESULT：卸载成功是 RESULT_FIRST_USER），
                            // 拿不到结果的设备也还有 ON_RESUME 的 settle 补查兜底。
                            pendingExternalAction = true
                            uninstallLauncher.launch(
                                Intent(
                                    Intent.ACTION_DELETE,
                                    "package:${item.installedName}".toUri()
                                ).putExtra(Intent.EXTRA_RETURN_RESULT, true)
                            )
                        },
                        onInstall = { files ->
                            // 装之前先看设备上的情况：没装过直接装，装过且签名一致直接覆盖，
                            // 签名不一致时弹框问要不要强制安装（installBackup 里处理）。
                            pendingExternalAction = true
                            scope.launch { installBackup(files, force = false) }
                        },
                        onDelete = { pendingDelete = item.backup },
                        onExtract = {
                            pendingExtract = item.backup
                            extractLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                        }
                    )
                }
            }
        }
    }

    val deleting = pendingDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.restore_delete_backup)) },
            text = { Text(stringResource(R.string.restore_delete_message, deleting.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            val deleted = withContext(Dispatchers.IO) { OriginApkStore.delete(deleting) }
                            if (!deleted) {
                                snackbarHost.showSnackbar(context.getString(R.string.restore_delete_failed))
                            }
                            refreshKey++
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_delete), color = YtpColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.restore_cancel))
                }
            }
        )
    }

    // 签名冲突：装原始包会覆盖掉已安装的（可能是打过补丁的）应用，所以要用户点头。
    val conflict = installConflict
    if (conflict != null) {
        val files = conflictFiles
        AlertDialog(
            onDismissRequest = {
                installConflict = null
                conflictFiles = emptyList()
            },
            title = { Text(stringResource(R.string.patch_install_conflict_title)) },
            text = {
                Text(stringResource(R.string.patch_install_conflict_message, conflict.packageName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        installConflict = null
                        conflictFiles = emptyList()
                        pendingExternalAction = true
                        scope.launch { installBackup(files, force = true) }
                    }
                ) {
                    Text(stringResource(R.string.patch_install_force))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        installConflict = null
                        conflictFiles = emptyList()
                    }
                ) {
                    Text(stringResource(R.string.restore_cancel))
                }
            }
        )
    }
}

@Composable
private fun RestoreCard(
    item: RestoreItem,
    onUninstall: () -> Unit,
    onInstall: (List<File>) -> Unit,
    onDelete: () -> Unit,
    onExtract: () -> Unit
) {
    val backup = item.backup
    YtpCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = YtpColors.Surface,
        borderColor = YtpColors.Border
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = backup.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = YtpColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = backup.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = YtpColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                RestoreStateChip(item)
            }

            val version = if (backup.versionName.isBlank()) {
                backup.versionCode.toString()
            } else {
                "${backup.versionName} (${backup.versionCode})"
            }
            Text(
                text = stringResource(
                    R.string.restore_backup_info,
                    version,
                    formatSize(backup.size),
                    formatTime(backup.timestamp)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = YtpColors.TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (item.installed) {
                            onUninstall()
                        } else {
                            onInstall(backup.apkFiles)
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (item.installed) R.string.uninstall else R.string.restore_install_original
                        )
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onExtract) {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(stringResource(R.string.restore_extract))
                    }
                    TextButton(onClick = onDelete) {
                        Text(
                            text = stringResource(R.string.settings_delete),
                            color = YtpColors.Error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RestoreStateChip(item: RestoreItem) {
    val stateRes = when {
        !item.installed -> R.string.restore_state_missing
        item.patched -> R.string.restore_state_patched
        else -> R.string.restore_state_original
    }
    val accent = when {
        !item.installed -> YtpColors.TextSecondary
        item.patched -> YtpColors.Warning
        else -> YtpColors.Success
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = YtpColors.iconBackground(accent, 0.14f),
        border = BorderStroke(1.dp, YtpColors.IconButtonBorder)
    ) {
        Text(
            text = stringResource(stateRes),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = YtpColors.iconForeground(accent)
        )
    }
}

// 备份列表的自动刷新间隔。每次刷新都要遍历备份并对每项做 PackageManager 查询，
// 2 秒一次太频繁，10 秒既能保持同步又不会持续占用磁盘/主线程。
private const val AUTO_REFRESH_INTERVAL_MS = 10_000L

/** 距离上次扫描完成至少要过这么久才允许再扫一次。 */
private const val MIN_RESTORE_REFRESH_INTERVAL_MS = 5_000L

// 外部动作（卸载/安装）回到前台后的补查：系统界面不回调结果，连查几次直到状态稳定。
private const val SETTLE_REFRESH_COUNT = 5
private const val SETTLE_REFRESH_INTERVAL_MS = 700L

private fun isRestoreRefreshDue(refreshedAt: AtomicLong): Boolean =
    SystemClock.elapsedRealtime() - refreshedAt.get() > MIN_RESTORE_REFRESH_INTERVAL_MS

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
