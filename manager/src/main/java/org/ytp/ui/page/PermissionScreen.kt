/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.page

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.ytp.R
import org.ytp.ui.theme.YtpColors

private const val TAG = "PermissionScreen"

/**
 * 部分 ROM（尤其是国产 ROM）并不声明这个权限，取不到就说明"应用列表"本身不需要额外授权。
 */
private const val PERMISSION_GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS"

private enum class PermissionState {
    GRANTED, MISSING, UNSUPPORTED
}

private class PermissionEntry(
    val icon: ImageVector,
    val accent: Color,
    val title: String,
    val description: String,
    val state: PermissionState,
    val onGrant: () -> Unit
)

/**
 * 权限与访问：把修补流程真正会用到、又必须由用户手动确认的几项集中列出来。
 *
 * 每一项都按"当前是否已授权"实时判断，点右侧按钮会跳到对应的系统页面；
 * 从系统设置返回时（ON_RESUME）会重新判断一次。
 */
@Destination
@Composable
fun PermissionScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current

    // 每次回到本页都自增一次，用来重新读取系统里的授权状态。
    var refreshKey by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val appsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshKey++
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshKey++
    }

    val entries = remember(refreshKey, context) {
        buildPermissionEntries(context, appsPermissionLauncher, mediaPermissionLauncher)
    }
    // 系统不支持的项目（例如没有声明该权限的 ROM）不参与计数。
    val requiredEntries = entries.filter { it.state != PermissionState.UNSUPPORTED }
    val grantedCount = requiredEntries.count { it.state == PermissionState.GRANTED }

    Scaffold(
        containerColor = YtpColors.PageContainer
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PermissionHero(
                onBackClick = navigator::popBackStack
            )

            PermissionSummaryCard(
                granted = grantedCount,
                total = requiredEntries.size
            )

            entries.forEach { entry ->
                PermissionCard(entry)
            }
        }
    }
}

/**
 * 当前系统上真正需要用户处理的权限项。
 *
 * - Android 13 起 `READ_EXTERNAL_STORAGE` 已不再适用，只有 Android 12L 及以下才需要存储权限；
 * - `MANAGE_EXTERNAL_STORAGE` 是 Android 11 起才有的"所有文件访问"，更低版本没有这一项。
 */
private fun buildPermissionEntries(
    context: Context,
    appsPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
    mediaPermissionLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>
): List<PermissionEntry> {
    val packageManager = context.packageManager
    val entries = mutableListOf<PermissionEntry>()

    val appsPermissionSupported = runCatching {
        packageManager.getPermissionInfo(PERMISSION_GET_INSTALLED_APPS, 0)
    }.isSuccess
    val appsPermissionGranted = isGranted(context, PERMISSION_GET_INSTALLED_APPS)
    entries += PermissionEntry(
        icon = Icons.Outlined.PhoneAndroid,
        accent = YtpColors.Primary,
        title = context.getString(R.string.permission_apps_title),
        description = context.getString(R.string.permission_apps_description),
        state = when {
            !appsPermissionSupported -> PermissionState.UNSUPPORTED
            appsPermissionGranted -> PermissionState.GRANTED
            else -> PermissionState.MISSING
        },
        onGrant = { appsPermissionLauncher.launch(PERMISSION_GET_INSTALLED_APPS) }
    )

    val installGranted = packageManager.canRequestPackageInstalls()
    entries += PermissionEntry(
        icon = Icons.Outlined.Download,
        accent = YtpColors.AccentPurple,
        title = context.getString(R.string.permission_install_title),
        description = context.getString(R.string.permission_install_description),
        state = if (installGranted) PermissionState.GRANTED else PermissionState.MISSING,
        onGrant = {
            openSettings(
                context,
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", context.packageName, null)
                ),
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            )
        }
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val allFilesGranted = Environment.isExternalStorageManager()
        entries += PermissionEntry(
            icon = Icons.Outlined.Folder,
            accent = YtpColors.Success,
            title = context.getString(R.string.permission_storage_title),
            description = context.getString(R.string.permission_storage_description),
            state = if (allFilesGranted) PermissionState.GRANTED else PermissionState.MISSING,
            onGrant = {
                openSettings(
                    context,
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", context.packageName, null)
                    ),
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
            }
        )
    }

    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        val mediaPermissions = buildList {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        entries += PermissionEntry(
            icon = Icons.Outlined.Lock,
            accent = YtpColors.Warning,
            title = context.getString(R.string.permission_media_title),
            description = context.getString(R.string.permission_media_description),
            state = if (mediaPermissions.all { isGranted(context, it) }) {
                PermissionState.GRANTED
            } else {
                PermissionState.MISSING
            },
            onGrant = { mediaPermissionLauncher.launch(mediaPermissions.toTypedArray()) }
        )
    }

    return entries
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * 依次尝试几个系统页面，用第一个能打开的；都打不开时退到应用详情页，至少让用户有路可走。
 */
private fun openSettings(context: Context, vararg intents: Intent) {
    val fallback = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    val candidates: List<Intent> = intents.toList() + fallback
    for (intent in candidates) {
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (opened) return
    }
    Log.w(TAG, "No settings page could be opened for ${context.packageName}")
}

@Composable
private fun PermissionHero(
    onBackClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clickable(onClick = onBackClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                modifier = Modifier.size(22.dp),
                tint = YtpColors.TextPrimary
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = stringResource(R.string.permission_title),
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = YtpColors.TextPrimary
        )
    }
}

@Composable
private fun PermissionSummaryCard(
    granted: Int,
    total: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(26.dp),
                ambientColor = YtpColors.Shadow.copy(alpha = 0.025f),
                spotColor = YtpColors.Primary.copy(alpha = 0.06f)
            ),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = YtpColors.Surface
        ),
        border = BorderStroke(1.dp, YtpColors.Border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp)
        ) {
            Text(
                text = stringResource(R.string.permission_summary, granted, total),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (granted == total) YtpColors.Success else YtpColors.TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.permission_summary_hint),
                fontSize = 12.sp,
                color = YtpColors.TextSecondary
            )
        }
    }
}

@Composable
private fun PermissionCard(entry: PermissionEntry) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(26.dp),
                ambientColor = YtpColors.Shadow.copy(alpha = 0.025f),
                spotColor = entry.accent.copy(alpha = 0.06f)
            ),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = YtpColors.Surface
        ),
        border = BorderStroke(1.dp, YtpColors.Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(YtpColors.iconBackground(entry.accent, 0.10f))
                    .border(1.dp, YtpColors.IconButtonBorder, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                    tint = YtpColors.iconForeground(entry.accent)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = YtpColors.TextPrimary
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = entry.description,
                    fontSize = 12.sp,
                    color = YtpColors.TextSecondary
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            PermissionAction(entry)
        }
    }
}

@Composable
private fun PermissionAction(entry: PermissionEntry) {
    when (entry.state) {
        PermissionState.GRANTED -> StatusChip(
            text = stringResource(R.string.permission_granted),
            color = YtpColors.Success
        )

        PermissionState.UNSUPPORTED -> StatusChip(
            text = stringResource(R.string.permission_unsupported),
            color = YtpColors.TextSoft
        )

        PermissionState.MISSING -> Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(YtpColors.iconBackground(entry.accent, 0.14f))
                .border(1.dp, YtpColors.IconButtonBorder, RoundedCornerShape(50))
                .clickable(onClick = entry.onGrant)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.permission_grant),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = YtpColors.iconForeground(entry.accent)
            )
        }
    }
}

@Composable
private fun StatusChip(
    text: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color
        )

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
