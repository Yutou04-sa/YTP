/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.page

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Parcelable
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.result.ResultBackNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.ytp.R
import org.ytp.ui.component.AppItem
import org.ytp.ui.component.YtpEmptyState
import org.ytp.ui.component.SearchAppBar
import org.ytp.ui.theme.YtpColors
import org.ytp.ui.viewmodel.SelectAppsViewModel
import org.ytp.util.LSPPackageManager

@Parcelize
sealed class SelectAppsResult : Parcelable {
    data class SingleApp(val selected: LSPPackageManager.AppInfo) : SelectAppsResult()
    data class MultipleApps(val selected: List<LSPPackageManager.AppInfo>) : SelectAppsResult()
}

@OptIn(ExperimentalMaterial3Api::class)
@Destination
@Composable
fun SelectAppsScreen(
    navigator: ResultBackNavigator<SelectAppsResult>,
    multiSelect: Boolean,
    initialSelected: ArrayList<String>?,
) {
    val viewModel = viewModel<SelectAppsViewModel>()
    val context = LocalContext.current
    val installedAppsPermission = "com.android.permission.GET_INSTALLED_APPS"
    var searchPackage by remember { mutableStateOf("") }
    var showPermissionDialog by remember { mutableStateOf(false) }

    val filter: (LSPPackageManager.AppInfo) -> Boolean = { appInfo ->
        val query = searchPackage.lowercase()
        val matches = appInfo.label.lowercase().contains(query) ||
            appInfo.app.packageName.lowercase().contains(query)
        if (multiSelect) matches && appInfo.isXposedModule.isNotEmpty()
        else matches && appInfo.app.flags and ApplicationInfo.FLAG_SYSTEM == 0
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.filterAppList(true, filter)
        else showPermissionDialog = true
    }

    @SuppressLint("WrongConstant")
    fun loadAppsWithPermissionCheck() {
        try {
            context.packageManager.getPermissionInfo(installedAppsPermission, 0)
            if (context.checkSelfPermission(installedAppsPermission) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(installedAppsPermission)
            } else {
                viewModel.filterAppList(false, filter)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            viewModel.filterAppList(false, filter)
        }
    }

    LaunchedEffect(Unit) {
        loadAppsWithPermissionCheck()
        val initialPackages = initialSelected?.toSet().orEmpty()
        viewModel.multiSelected.addAll(
            LSPPackageManager.appList.filter { it.app.packageName in initialPackages }
        )
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            containerColor = YtpColors.Surface,
            titleContentColor = YtpColors.TextPrimary,
            textContentColor = YtpColors.TextSecondary,
            title = { Text(stringResource(R.string.permission_denied)) },
            text = { Text(stringResource(R.string.permission_required)) },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = YtpColors.ThemePrimary),
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:${context.packageName}".let(android.net.Uri::parse)
                            )
                        )
                        showPermissionDialog = false
                    }
                ) { Text(stringResource(R.string.go_to_settings)) }
            },
            dismissButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = YtpColors.ThemePrimary),
                    onClick = { showPermissionDialog = false }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    BackHandler { navigator.navigateBack() }

    Scaffold(
        topBar = {
            SearchAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.screen_select_apps),
                        style = MaterialTheme.typography.titleLarge,
                        color = YtpColors.TextPrimary
                    )
                },
                searchText = searchPackage,
                onSearchTextChange = {
                    searchPackage = it
                    viewModel.filterAppList(false, filter)
                },
                onClearClick = {
                    searchPackage = ""
                    viewModel.filterAppList(false, filter)
                },
                onBackClick = navigator::navigateBack,
                containerColor = YtpColors.PageChrome,
                scrolledContainerColor = YtpColors.PageChrome,
                contentColor = YtpColors.TextPrimary
            )
        },
        floatingActionButton = {
            if (multiSelect) {
                ExtendedFloatingActionButton(
                    onClick = {
                        navigator.navigateBack(SelectAppsResult.MultipleApps(viewModel.multiSelected))
                    },
                    icon = { Icon(Icons.Outlined.Check, contentDescription = null) },
                    text = {
                        Text(stringResource(R.string.selection_done, viewModel.multiSelected.size))
                    },
                    containerColor = YtpColors.ThemePrimary,
                    contentColor = YtpColors.PrimaryActionContent
                )
            }
        },
        containerColor = YtpColors.PageContainer
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = viewModel.isRefreshing,
            onRefresh = { viewModel.filterAppList(true, filter) },
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            if (!viewModel.isRefreshing && viewModel.filteredList.isEmpty()) {
                YtpEmptyState(
                    title = stringResource(R.string.apps_empty),
                    supportingText = if (searchPackage.isBlank()) null else stringResource(R.string.search_empty),
                    icon = Icons.Outlined.SearchOff,
                    modifier = Modifier.fillMaxSize(),
                    iconContainerColor = YtpColors.PrimaryContainer,
                    iconContentColor = YtpColors.IconContent
                )
            } else {
                AppsList(
                    apps = viewModel.filteredList,
                    multiSelect = multiSelect,
                    selected = viewModel.multiSelected,
                    onSingleSelect = { navigator.navigateBack(SelectAppsResult.SingleApp(it)) },
                    onToggle = { app, checked ->
                        if (checked) viewModel.multiSelected.remove(app)
                        else viewModel.multiSelected.add(app)
                    }
                )
            }
        }
    }
}

/** 多选时每个应用额外要显示的版本名/描述，由后台线程填充。 */
private data class AppExtra(val version: String, val description: String?)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppsList(
    apps: List<LSPPackageManager.AppInfo>,
    multiSelect: Boolean,
    selected: List<LSPPackageManager.AppInfo>,
    onSingleSelect: (LSPPackageManager.AppInfo) -> Unit,
    onToggle: (LSPPackageManager.AppInfo, Boolean) -> Unit
) {
    // getVersionName/getDescription 都是 PackageManager IPC + 读 metaData，不能放在 composition
    // 里对每个可见项调用（滚动时会掉帧）。这里在后台线程算一次，结果放进快照状态。
    val extras = remember { mutableStateMapOf<String, AppExtra>() }
    LaunchedEffect(apps, multiSelect) {
        if (!multiSelect) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            apps.forEach { appInfo ->
                val packageName = appInfo.app.packageName
                if (!extras.containsKey(packageName)) {
                    val version = runCatching { LSPPackageManager.getVersionName(packageName) }
                        .getOrNull() ?: ""
                    val description = runCatching { LSPPackageManager.getDescription(appInfo) }
                        .getOrNull()
                    extras[packageName] = AppExtra(version, description)
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = if (multiSelect) 96.dp else 20.dp)
    ) {
        items(items = apps, key = { it.app.packageName }) { appInfo ->
            val checked = appInfo in selected
            val disabled = multiSelect && appInfo.isXposedModule == "100"
            val extra = if (multiSelect) extras[appInfo.app.packageName] else null
            AppItem(
                modifier = Modifier
                    .animateItem(spring(stiffness = Spring.StiffnessLow))
                    .clickable(enabled = !disabled) {
                        if (multiSelect) onToggle(appInfo, checked) else onSingleSelect(appInfo)
                    },
                icon = LSPPackageManager.getIcon(appInfo),
                label = appInfo.label,
                packageName = appInfo.app.packageName,
                versionName = extra?.version,
                description = extra?.description,
                targetApiVersion = if (multiSelect) appInfo.isXposedModule else null,
                disabled = disabled,
                checked = if (multiSelect) checked else null,
                containerColor = YtpColors.Surface,
                borderColor = YtpColors.Border,
                titleColor = YtpColors.TextPrimary,
                secondaryTextColor = YtpColors.TextSecondary,
                primaryColor = YtpColors.ThemePrimary,
                versionContainerColor = YtpColors.PrimaryContainer,
                versionContentColor = YtpColors.iconForeground(YtpColors.Primary)
            )
        }
    }
}
