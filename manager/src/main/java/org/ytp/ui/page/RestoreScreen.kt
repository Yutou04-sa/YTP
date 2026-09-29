package org.ytp.ui.page

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ytp.R
import org.ytp.ui.component.CenterTopBar
import org.ytp.ui.component.YtpCard
import org.ytp.ui.component.YtpEmptyState
import org.ytp.ui.theme.YtpColors
import org.ytp.ui.util.LocalSnackbarHost
import org.ytp.ui.util.installApks
import org.ytp.ui.util.uninstallApk
import org.ytp.util.OriginApkStore
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class RestoreItem(
    val backup: OriginApkStore.Backup,
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

    LaunchedEffect(refreshKey) {
        if (items.isEmpty()) loading = true
        items = withContext(Dispatchers.IO) {
            OriginApkStore.listBackups().map { backup ->
                // Renamed patches are installed under their new package name.
                val installedName = backup.patchedPackageName.ifEmpty { backup.packageName }
                RestoreItem(
                    backup = backup,
                    installed = OriginApkStore.isInstalled(installedName),
                    patched = OriginApkStore.isPatchedInstalled(installedName)
                )
            }
        }
        loading = false
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    refreshKey++
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
            refreshKey++
        }
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
                        onUninstall = { uninstallApk(context, item.backup.packageName) },
                        onInstall = { files ->
                            // installApks 现在是 suspend 且返回真实安装状态：交给安装器（含等待用户确认）
                            // 与安装成功都算"已交给系统安装器"，其它状态才报安装失败。
                            scope.launch {
                                val result = installApks(context, files)
                                val handedOver = result.status == PackageInstaller.STATUS_SUCCESS ||
                                    result.status == PackageInstaller.STATUS_PENDING_USER_ACTION
                                snackbarHost.showSnackbar(
                                    context.getString(
                                        if (handedOver) {
                                            R.string.restore_handed_over
                                        } else {
                                            R.string.restore_install_failed
                                        }
                                    )
                                )
                            }
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

private const val AUTO_REFRESH_INTERVAL_MS = 2_000L

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
