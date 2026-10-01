/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.ytp.R
import org.ytp.ui.theme.YtpColors

/**
 * 读取「已安装应用列表」在部分 ROM（MIUI / HyperOS 之类）上还要单独授权这一条。
 * 没有它 `PackageManager` 只能看到自己，「模块」和「应用」两个页面就会是空的。
 * 系统根本不认识这条权限时（`getPermissionInfo` 抛 NameNotFoundException）无需请求。
 */
private const val APP_LIST_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

/** 当前是否已经可以列出已安装应用：系统不认识这条权限，或者已经授权。 */
fun Context.hasAppListPermission(): Boolean =
    runCatching { packageManager.getPermissionInfo(APP_LIST_PERMISSION, 0) }.isFailure ||
        checkSelfPermission(APP_LIST_PERMISSION) == PackageManager.PERMISSION_GRANTED

/**
 * 「模块 / 应用」入口的权限闸门：点入口时先确认应用列表权限，已授权直接放行；
 * 未授权先弹系统的权限请求，被拒绝则提示去设置里手动打开。
 *
 * [content] 会收到一个 `request(onGranted)`，用它包住真正要执行的跳转。
 */
@Composable
fun AppListPermissionGate(
    content: @Composable (request: (onGranted: () -> Unit) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showDeniedDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pending
        pending = null
        if (granted) {
            action?.invoke()
        } else {
            showDeniedDialog = true
        }
    }

    fun request(onGranted: () -> Unit) {
        if (context.hasAppListPermission()) {
            onGranted()
        } else {
            pending = onGranted
            permissionLauncher.launch(APP_LIST_PERMISSION)
        }
    }

    if (showDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showDeniedDialog = false },
            containerColor = YtpColors.Surface,
            titleContentColor = YtpColors.TextPrimary,
            textContentColor = YtpColors.TextSecondary,
            title = { Text(stringResource(R.string.permission_denied)) },
            text = { Text(stringResource(R.string.permission_required)) },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = YtpColors.ThemePrimary),
                    onClick = {
                        // 先关掉对话框再跳设置：从设置回来时用户再点一次入口即可（那时已经授权）。
                        showDeniedDialog = false
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                ) { Text(stringResource(R.string.go_to_settings)) }
            },
            dismissButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = YtpColors.ThemePrimary),
                    onClick = { showDeniedDialog = false }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    content { onGranted -> request(onGranted) }
}
