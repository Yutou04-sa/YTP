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
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ytp.R
import org.ytp.config.Configs
import org.ytp.ui.component.CenterTopBar
import org.ytp.ui.component.PageHorizontalPadding
import org.ytp.ui.component.PageVerticalSpacing
import org.ytp.ui.component.YtpAppIcon
import org.ytp.ui.component.YtpCard
import org.ytp.ui.component.YtpEmptyState
import org.ytp.ui.page.destinations.NewPatchScreenDestination
import org.ytp.ui.page.destinations.RestoreScreenDestination
import org.ytp.ui.theme.YtpColors
import org.ytp.ui.util.LocalSnackbarHost
import org.ytp.util.LSPPackageManager
import org.ytp.util.OriginApkRecovery
import org.ytp.util.OriginApkStore
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "PatchedAppsScreen"

/**
 * 刚扫完不到这个时间就不要再扫一遍：fetchAppList() 会遍历所有已安装应用并解析 apk，
 * 旋转屏幕、返回本页这类连续的 ON_RESUME 没必要重复扫描。
 */
private const val MIN_REFRESH_INTERVAL_MS = 5_000L

private fun isRefreshDue(lastRefreshAt: AtomicLong): Boolean =
    SystemClock.elapsedRealtime() - lastRefreshAt.get() > MIN_REFRESH_INTERVAL_MS

private data class PatchedApp(
    val app: LSPPackageManager.AppInfo,
    val versionName: String,
    val apkSize: Long,
    /** The original apks that were backed up before patching, null when there is none. */
    val backup: OriginApkStore.Backup?,
    /** Who patched the apk: only [OriginApkStore.PatchSource.YTP] builds can be re-patched. */
    val patchedBy: OriginApkStore.PatchSource,
    /**
     * Whether the patched apk still carries the untouched original apk of the app. Only LSPatch
     * and NPatch keep such a copy; a YTP patch rewrites the app's dex in place and loses it.
     */
    val originRecoverable: Boolean
)

/** 条目上那枚标记：YTP 自己的补丁沿用原来的「已修补」，其它来源标出是谁打的。 */
private fun patchSourceLabel(source: OriginApkStore.PatchSource): Int = when (source) {
    OriginApkStore.PatchSource.YTP -> R.string.patched_apps_state
    OriginApkStore.PatchSource.HKP -> R.string.patched_apps_source_hkp
    OriginApkStore.PatchSource.LSPATCH -> R.string.patched_apps_source_lspatch
    OriginApkStore.PatchSource.NPATCH -> R.string.patched_apps_source_npatch
    OriginApkStore.PatchSource.UNKNOWN -> R.string.patched_apps_source_unknown
}

/** 外来的补丁（HKP/LSPatch/NPatch）不是本管理器管理的，用中性色而不是「已修补」的绿色。 */
private fun patchSourceAccent(source: OriginApkStore.PatchSource): Color = when (source) {
    OriginApkStore.PatchSource.YTP -> YtpColors.Success
    else -> YtpColors.AccentPurple
}

/**
 * Overview of the installed apps whose apk carries a patch marker: YTP patches plus the ones of
 * the branding this fork used before it was renamed and of upstream LSPatch and NPatch, each
 * labeled with its source.
 *
 * Apps with a YTP backup offer to re-patch and to extract the original apk; the ones patched by
 * LSPatch or NPatch carry the original apk inside the patched apk, so they offer to recover it
 * first (after that the same two actions become available). A HKP patch is the same framework as
 * YTP under its previous name, so it can be re-patched in place. The complete backup list stays
 * reachable through [RestoreScreenDestination].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination
@Composable
fun PatchedAppsScreen(navigator: DestinationsNavigator) {
    var items by remember { mutableStateOf<List<PatchedApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var pendingExtract by remember { mutableStateOf<OriginApkStore.Backup?>(null) }
    var pendingRepatch by remember { mutableStateOf<String?>(null) }
    var recovering by remember { mutableStateOf(false) }
    // 没有备份、补丁包里也没有原包时（YTP/HKP 都是就地改写宿主 dex），只能让用户自己指一份
    // 未修补的原包；这里记下要登记到哪个已安装的补丁包名下。
    var assignTarget by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

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

    // 手动指定原包：用户从文件管理器里挑一份没打过补丁的原始 apk，登记成这个应用的备份，
    // 之后就能和自动备份一样「重新修补 / 导出原包 / 装回原版」了。
    val assignLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val packageName = assignTarget
        assignTarget = null
        if (packageName == null || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val assigned = withContext(Dispatchers.IO) {
                runCatching {
                    val staged = File(context.cacheDir, "picked-original.apk")
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException(
                                context.getString(R.string.origin_apk_open_failed)
                            )
                        input.use { source ->
                            staged.outputStream().use { target -> source.copyTo(target) }
                        }
                        OriginApkStore.registerOriginal(packageName, staged).getOrThrow()
                    } finally {
                        staged.delete()
                    }
                }
            }
            snackbarHost.showSnackbar(
                assigned.fold(
                    onSuccess = { context.getString(R.string.patched_apps_assign_done, it.label) },
                    onFailure = {
                        context.getString(R.string.patched_apps_assign_failed, it.message.orEmpty())
                    }
                )
            )
            // 登记成功后这一条就有备份了，重新扫一遍让 chip 和菜单跟着变。
            if (assigned.isSuccess) refreshKey++
        }
    }

    // Re-patching needs the storage directory (the patched apks are written there), so ask for
    // it first when the user has not granted one yet.
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val packageName = pendingRepatch
        pendingRepatch = null
        val treeUri = result.data?.data
        if (packageName == null || result.resultCode != Activity.RESULT_OK || treeUri == null) {
            return@rememberLauncherForActivityResult
        }
        // Persisting the grant can fail (revoked/expired URI). Only remember the directory when
        // it actually succeeded, otherwise patching would fail much later with no explanation.
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.isSuccess
        if (!persisted) {
            scope.launch { snackbarHost.showSnackbar(context.getString(R.string.patched_apps_storage_permission_failed)) }
            return@rememberLauncherForActivityResult
        }
        Configs.storageDirectory = treeUri.toString()
        navigator.navigate(
            NewPatchScreenDestination(id = ACTION_BACKUP, backupPackage = packageName)
        )
    }

    // Returning to this screen (after patching, uninstalling or installing something) always
    // shows fresh data, so the list refreshes itself instead of offering a refresh button.
    // fetchAppList() walks every installed app and parses its apk, so a resume that comes right
    // after a refresh (e.g. rotation, or a dialog round-trip) does not need another scan.
    var initialised by remember { mutableStateOf(false) }
    val lastRefreshAt = remember { AtomicLong(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val firstResume = !initialised
                initialised = true
                if (!firstResume && isRefreshDue(lastRefreshAt)) refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshKey) {
        if (items.isEmpty()) loading = true
        items = withContext(Dispatchers.IO) {
            // Always refresh: an app that got patched (or installed) after the list was cached
            // has to show up here, and a failing refresh must not keep the screen spinning.
            runCatching { LSPPackageManager.fetchAppList() }
                .onFailure { Log.w(TAG, "Failed to refresh the app list", it) }
            // A renamed patch is installed under its new package name, so the backup has to be
            // reachable through both the original and the renamed name.
            val backups = buildMap {
                runCatching { OriginApkStore.listBackups() }
                    .onFailure { Log.w(TAG, "Failed to read the backup list", it) }
                    .getOrDefault(emptyList())
                    .forEach { backup ->
                        put(backup.packageName, backup)
                        if (backup.patchedPackageName.isNotEmpty()) {
                            put(backup.patchedPackageName, backup)
                        }
                    }
            }
            LSPPackageManager.appList.mapNotNull { info ->
                val source = File(info.app.sourceDir)
                // 只认 YTP 自己的补丁的话，被 HKP/LSPatch/NPatch 修补过的应用会凭空消失，
                // 所以这里接受任何一种已知标记，再由卡片标出是谁打的。
                val patchedBy = OriginApkStore.patchSourceOf(source) ?: return@mapNotNull null
                // 分裂包的每个 apk 各自带一份内嵌原包，缺一个都还原不出完整的原包。
                val sources = buildList {
                    add(source)
                    info.app.splitSourceDirs?.forEach { add(File(it)) }
                }
                PatchedApp(
                    app = info,
                    versionName = LSPPackageManager.getVersionName(info.app.packageName),
                    apkSize = source.length(),
                    backup = backups[info.app.packageName],
                    patchedBy = patchedBy,
                    originRecoverable = patchedBy != OriginApkStore.PatchSource.YTP &&
                        patchedBy != OriginApkStore.PatchSource.HKP &&
                        OriginApkRecovery.canRecover(sources)
                )
            }
        }
        loading = false
        lastRefreshAt.set(SystemClock.elapsedRealtime())
    }

    Scaffold(
        containerColor = YtpColors.PageContainer,
        topBar = {
            CenterTopBar(
                text = stringResource(R.string.patched_apps_title),
                onBackClick = navigator::popBackStack,
                containerColor = YtpColors.PageChrome,
                scrolledContainerColor = YtpColors.PageChrome,
                contentColor = YtpColors.TextPrimary
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 「原包」入口常显：加载中、列表为空、有内容时都能进入备份/还原页
                RestoreOriginalEntry(
                    onClick = { navigator.navigate(RestoreScreenDestination) },
                    modifier = Modifier.padding(
                        start = PageHorizontalPadding,
                        end = PageHorizontalPadding,
                        top = PageVerticalSpacing
                    )
                )

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        // 加载中的转圈画在整页正中（见下方覆盖层）：这个 Box 只占「原包」入口
                        // 以下的区域，转圈放在这里会显得偏下。
                        loading -> Unit

                        items.isEmpty() -> YtpEmptyState(
                            title = stringResource(R.string.patched_apps_empty_title),
                            modifier = Modifier.align(Alignment.Center),
                            icon = Icons.Outlined.VerifiedUser,
                            supportingText = stringResource(R.string.patched_apps_empty_description)
                        )

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                horizontal = PageHorizontalPadding,
                                vertical = PageVerticalSpacing
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                Text(
                                    text = stringResource(R.string.patched_apps_count, items.size),
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = YtpColors.TextSecondary
                                )
                            }
                            items(items, key = { it.app.app.packageName }) { item ->
                                PatchedAppCard(
                                    item = item,
                                    onRepatch = {
                                        val packageName = item.app.app.packageName
                                        if (Configs.storageDirectory == null) {
                                            pendingRepatch = packageName
                                            storageLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                                        } else {
                                            navigator.navigate(
                                                NewPatchScreenDestination(
                                                    id = ACTION_BACKUP,
                                                    backupPackage = packageName
                                                )
                                            )
                                        }
                                    },
                                    onExtract = {
                                        val backup = item.backup ?: return@PatchedAppCard
                                        pendingExtract = backup
                                        extractLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                                    },
                                    onRecover = {
                                        val packageName = item.app.app.packageName
                                        recovering = true
                                        scope.launch {
                                            val recovered = withContext(Dispatchers.IO) {
                                                OriginApkStore.recoverFromEmbedded(packageName)
                                            }
                                            recovering = false
                                            snackbarHost.showSnackbar(
                                                recovered.fold(
                                                    onSuccess = {
                                                        context.getString(
                                                            R.string.patched_apps_recover_done,
                                                            it.label
                                                        )
                                                    },
                                                    onFailure = {
                                                        context.getString(R.string.patched_apps_recover_failed)
                                                    }
                                                )
                                            )
                                            // 还原成功后这一条就有备份了，重新扫一遍让菜单跟着变。
                                            if (recovered.isSuccess) refreshKey++
                                        }
                                    },
                                    onAssign = {
                                        assignTarget = item.app.app.packageName
                                        assignLauncher.launch(
                                            arrayOf(
                                                "application/vnd.android.package-archive",
                                                "application/octet-stream",
                                                "application/zip"
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (loading || recovering) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = YtpColors.Primary
                )
            }
        }
    }
}

/** 「原包」入口：整行卡片，任何列表状态下都可见，点击进入原包备份/还原页。 */
@Composable
private fun RestoreOriginalEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    val iconShape = RoundedCornerShape(14.dp)
    val accent = YtpColors.Primary

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = YtpColors.Surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(iconShape)
                    .background(accent.copy(alpha = 0.14f))
                    .border(1.dp, YtpColors.IconButtonBorder, iconShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    tint = accent
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.restore_original_apk),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = YtpColors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.patched_apps_restore_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = YtpColors.TextSecondary
                )
            }

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = YtpColors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatchedAppCard(
    item: PatchedApp,
    onRepatch: () -> Unit,
    onExtract: () -> Unit,
    onRecover: () -> Unit,
    onAssign: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    YtpCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = YtpColors.Surface,
        borderColor = YtpColors.Border
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            YtpAppIcon(
                bitmap = LSPPackageManager.getIcon(item.app),
                contentDescription = null
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.app.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = YtpColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.app.app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = YtpColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    InfoChip(
                        text = stringResource(patchSourceLabel(item.patchedBy)),
                        accent = patchSourceAccent(item.patchedBy)
                    )
                    if (item.versionName.isNotBlank()) {
                        InfoChip(text = item.versionName)
                    }
                    InfoChip(text = formatApkSize(item.apkSize))
                    if (item.backup == null) {
                        if (item.originRecoverable) {
                            // 没有自己的备份，但补丁包里带着原包，能从那儿还原出来。
                            InfoChip(
                                text = stringResource(R.string.patched_apps_origin_embedded),
                                accent = YtpColors.AccentPurple
                            )
                        } else {
                            InfoChip(
                                text = stringResource(R.string.patched_apps_no_backup),
                                accent = YtpColors.Warning
                            )
                        }
                    }
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.module_action_more),
                        tint = YtpColors.TextSecondary
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (item.backup == null && item.originRecoverable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.patched_apps_recover_original)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Restore, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onRecover()
                            }
                        )
                    }
                    if (item.backup == null) {
                        // YTP/HKP 的补丁是就地改写宿主 dex 的，包里没有原包，只能让用户指一份。
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.patched_apps_assign_original)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.FileOpen, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onAssign()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.patched_apps_repatch)) },
                        leadingIcon = {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                        },
                        // HKP 是本项目改名前的同一个框架，它的补丁包能就地换成 YTP 的补丁；
                        // LSPatch/NPatch 的包内自带原包，重打时会先自动提取成备份再打；其它情况
                        // 必须有原包备份才能重打。
                        enabled = item.backup != null ||
                            item.originRecoverable ||
                            item.patchedBy == OriginApkStore.PatchSource.HKP,
                        onClick = {
                            menuExpanded = false
                            onRepatch()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.patched_apps_extract_original)) },
                        leadingIcon = {
                            Icon(Icons.Outlined.FileDownload, contentDescription = null)
                        },
                        enabled = item.backup != null,
                        onClick = {
                            menuExpanded = false
                            onExtract()
                        }
                    )
                }
            }
        }
    }
}

private fun formatApkSize(bytes: Long): String {
    if (bytes <= 0L) return "-"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}
