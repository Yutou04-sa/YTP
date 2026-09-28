package org.ytp.ui.page

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ytp.R
import org.ytp.ui.component.CenterTopBar
import org.ytp.ui.component.PageHorizontalPadding
import org.ytp.ui.component.PageVerticalSpacing
import org.ytp.ui.component.YtpAppIcon
import org.ytp.ui.component.YtpCard
import org.ytp.ui.component.YtpEmptyState
import org.ytp.ui.theme.YtpColors
import org.ytp.ui.util.LocalSnackbarHost
import org.ytp.ui.util.uninstallApk
import org.ytp.util.LSPPackageManager
import java.io.File
import java.io.IOException

private const val TAG = "ModuleManager"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

private data class ModuleItem(
    val app: LSPPackageManager.AppInfo,
    val versionName: String,
    val description: String?
)

/**
 * Lists the modules (Xposed / LSPatch) installed on the device and offers the operations that can
 * be performed from the manager: opening the module, its app info page, exporting and uninstalling
 * it. Enabling a module for a patched app is still handled inside the patched app itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination
@Composable
fun ModuleManagerScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<ModuleItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var pendingExport by remember { mutableStateOf<ModuleItem?>(null) }
    var pendingUninstall by remember { mutableStateOf<ModuleItem?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val item = pendingExport
        pendingExport = null
        val treeUri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (item == null || treeUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching { exportModuleApk(context, File(item.app.app.sourceDir), treeUri) }
                    .fold(
                        onSuccess = { name -> context.getString(R.string.module_export_success, name) },
                        onFailure = { throwable ->
                            Log.w(TAG, "Failed to export ${item.app.app.packageName}", throwable)
                            context.getString(R.string.module_export_failed)
                        }
                    )
            }
            snackbarHost.showSnackbar(message)
        }
    }

    // Coming back to this screen (e.g. after the system uninstall dialog or a module update)
    // should always show fresh data.
    var initialised by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (initialised) refreshKey++ else initialised = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshKey) {
        if (items.isEmpty()) loading = true
        items = withContext(Dispatchers.IO) {
            runCatching { LSPPackageManager.fetchAppList() }
                .onFailure { Log.w(TAG, "Failed to refresh the app list", it) }
            LSPPackageManager.appList
                .filter { it.isXposedModule.isNotEmpty() }
                .map { info ->
                    ModuleItem(
                        app = info,
                        versionName = LSPPackageManager.getVersionName(info.app.packageName),
                        description = LSPPackageManager.getDescription(info)
                    )
                }
        }
        loading = false
    }

    pendingUninstall?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            containerColor = YtpColors.Surface,
            title = { Text(stringResource(R.string.module_uninstall_title, item.app.label)) },
            text = { Text(stringResource(R.string.module_uninstall_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUninstall = null
                        uninstallApk(context, item.app.app.packageName)
                    }
                ) {
                    Text(
                        text = stringResource(R.string.module_action_uninstall),
                        color = YtpColors.Error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.restore_cancel))
                }
            }
        )
    }

    Scaffold(
        containerColor = YtpColors.PageContainer,
        topBar = {
            CenterTopBar(
                text = stringResource(R.string.module_manager_title),
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
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = YtpColors.Primary
                )

                items.isEmpty() -> YtpEmptyState(
                    title = stringResource(R.string.module_manager_empty_title),
                    modifier = Modifier.align(Alignment.Center),
                    icon = Icons.Outlined.Extension,
                    supportingText = stringResource(R.string.module_manager_empty_description)
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.module_manager_count, items.size),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                color = YtpColors.TextSecondary
                            )
                        }
                    }
                    items(items, key = { it.app.app.packageName }) { item ->
                        ModuleCard(
                            item = item,
                            onOpen = {
                                LSPPackageManager
                                    .getLaunchIntentForPackage(item.app.app.packageName)
                                    ?.let { intent -> runCatching { context.startActivity(intent) } }
                            },
                            onAppInfo = {
                                LSPPackageManager
                                    .getSettingsIntent(item.app.app.packageName)
                                    ?.let { intent -> runCatching { context.startActivity(intent) } }
                            },
                            onExport = { pendingExport = item },
                            onUninstall = { pendingUninstall = item }
                        )
                    }
                }
            }
        }
    }
}

/** Copies the apk of a module into the directory the user picked. */
private fun exportModuleApk(context: Context, apkFile: File, treeUri: Uri): String {
    val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IOException("DocumentFile is null")
    val name = apkFile.name.ifBlank { "module.apk" }
    val target = root.findFile(name)?.takeIf { it.isFile }
        ?: root.createFile(APK_MIME_TYPE, name)
        ?: throw IOException("Cannot create $name")
    val output = context.contentResolver.openOutputStream(target.uri)
        ?: throw IOException("OutputStream is null")
    apkFile.inputStream().use { input ->
        output.use { stream -> input.copyTo(stream) }
    }
    return name
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleCard(
    item: ModuleItem,
    onOpen: () -> Unit,
    onAppInfo: () -> Unit,
    onExport: () -> Unit,
    onUninstall: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val canOpen = remember(item.app.app.packageName) {
        LSPPackageManager.getLaunchIntentForPackage(item.app.app.packageName) != null
    }

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
                    InfoChip(text = moduleTypeLabel(item.app.isXposedModule))
                    if (item.versionName.isNotBlank()) InfoChip(text = item.versionName)
                }
                if (!item.description.isNullOrBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = YtpColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
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
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.module_action_open)) },
                        leadingIcon = {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                        },
                        enabled = canOpen,
                        onClick = {
                            menuExpanded = false
                            onOpen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.module_action_app_info)) },
                        leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onAppInfo()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.module_action_export)) },
                        leadingIcon = {
                            Icon(Icons.Outlined.FileDownload, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onExport()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.module_action_uninstall),
                                color = YtpColors.Error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = YtpColors.Error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onUninstall()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun moduleTypeLabel(type: String): String =
    if (type == "legacy") {
        stringResource(R.string.module_manager_type_legacy)
    } else {
        stringResource(R.string.module_manager_type_api, type)
    }

@Composable
fun InfoChip(
    text: String,
    accent: Color = YtpColors.Primary
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = YtpColors.iconBackground(accent, 0.12f),
        contentColor = YtpColors.iconForeground(accent),
        border = BorderStroke(1.dp, YtpColors.IconButtonBorder)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}
