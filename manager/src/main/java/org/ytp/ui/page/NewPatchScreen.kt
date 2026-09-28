package org.ytp.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.result.NavResult
import com.ramcosta.composedestinations.result.ResultRecipient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ytp.R
import org.ytp.ui.page.destinations.SelectAppsScreenDestination
import org.ytp.ui.component.CenterTopBar
import org.ytp.ui.component.YtpAppIcon
import org.ytp.ui.component.YtpCard
import org.ytp.ui.component.YtpIconContainer
import org.ytp.ui.component.YtpSectionHeader
import org.ytp.ui.theme.YtpColors
import org.ytp.ui.util.installApks
import org.ytp.ui.util.lastItemIndex
import org.ytp.ui.viewmodel.NewPatchViewModel

private const val TAG = "NewPatchPage"

const val ACTION_STORAGE = 0
const val ACTION_APPLIST = 1
const val ACTION_INTENT_INSTALL = 2

/** Re-patches an app from the original apks that were backed up before it got patched. */
const val ACTION_BACKUP = 3

@OptIn(ExperimentalMaterial3Api::class)
@Destination
@Composable
fun NewPatchScreen(
    navigator: DestinationsNavigator,
    resultRecipient: ResultRecipient<SelectAppsScreenDestination, SelectAppsResult>,
    id: Int,
    data: Uri? = null,
    backupPackage: String? = null
) {
    val viewModel = viewModel<NewPatchViewModel>()
    val snackbarHost =org.ytp.ui.util.LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val errorUnknown = stringResource(R.string.error_unknown)
    val noOriginalBackup = stringResource(R.string.patch_repatch_no_backup)
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { apks ->
        if (apks.isEmpty()) {
            navigator.navigateUp()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            org.ytp.util.LSPPackageManager.getAppInfoFromApks(apks)
                .onSuccess {
                    viewModel.dispatch(NewPatchViewModel.ViewAction.ConfigurePatch(it.first()))
                }
                .onFailure {
                    snackbarHost.showSnackbar(it.message ?: errorUnknown)
                    navigator.navigateUp()
                }
        }
    }

    var showSelectModuleDialog by remember { mutableStateOf(false) }
    val noXposedModules = stringResource(R.string.patch_no_xposed_module)
    val storageModuleLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { apks ->
            if (apks.isEmpty()) {
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                org.ytp.util.LSPPackageManager.getAppInfoFromApks(apks).onSuccess { appInfos ->
                    val modules = appInfos.filter { it.isXposedModule.isNotEmpty() }
                    if (modules.isEmpty()) {
                        snackbarHost.showSnackbar(noXposedModules)
                    } else {
                        viewModel.embeddedModules = modules
                    }
                }.onFailure {
                    snackbarHost.showSnackbar(it.message ?: errorUnknown)
                }
            }
        }

    Log.d(TAG, "PatchState: ${viewModel.patchState}")
    when (viewModel.patchState) {
        NewPatchViewModel.PatchState.INIT -> {
            LaunchedEffect(Unit) {
                org.ytp.util.LSPPackageManager.cleanTmpApkDir()
                when (id) {
                    ACTION_STORAGE -> {
                        storageLauncher.launch(arrayOf("application/vnd.android.package-archive"))
                        viewModel.dispatch(NewPatchViewModel.ViewAction.DoneInit)
                    }

                    ACTION_APPLIST -> {
                        navigator.navigate(SelectAppsScreenDestination(false, null))
                        viewModel.dispatch(NewPatchViewModel.ViewAction.DoneInit)
                    }

                    ACTION_INTENT_INSTALL -> {
                        data?.let { uri ->
                            scope.launch {
                                org.ytp.util.LSPPackageManager.getAppInfoFromApks(listOf(uri)).onSuccess {
                                    viewModel.dispatch(NewPatchViewModel.ViewAction.ConfigurePatch(it.first()))
                                }.onFailure {
                                    snackbarHost.showSnackbar(it.message ?: errorUnknown)
                                    navigator.navigateUp()
                                }
                            }
                        }
                    }

                    ACTION_BACKUP -> {
                        val packageName = backupPackage
                        if (packageName == null) {
                            navigator.navigateUp()
                        } else {
                            scope.launch {
                                val previousName = withContext(Dispatchers.IO) {
                                    org.ytp.util.OriginApkStore.findBackup(packageName)
                                        ?.patchedPackageName
                                        .orEmpty()
                                }
                                org.ytp.util.LSPPackageManager.getAppInfoFromBackup(packageName)
                                    .onSuccess {
                                        viewModel.dispatch(
                                            NewPatchViewModel.ViewAction.ConfigurePatch(it)
                                        )
                                        // Re-patching a renamed app keeps its package name
                                        viewModel.newPackageName = previousName
                                    }
                                    .onFailure {
                                        Log.w(TAG, "No original apks to re-patch $packageName", it)
                                        snackbarHost.showSnackbar(noOriginalBackup)
                                        navigator.navigateUp()
                                    }
                            }
                        }
                    }
                }
            }
        }
        NewPatchViewModel.PatchState.SELECTING -> {
            resultRecipient.onNavResult {
                Log.d(TAG, "onNavResult: $it")
                when (it) {
                    is NavResult.Canceled -> navigator.navigateUp()
                    is NavResult.Value -> {
                        val result = it.value as SelectAppsResult.SingleApp
                        viewModel.dispatch(NewPatchViewModel.ViewAction.ConfigurePatch(result.selected))
                    }
                }
            }
        }
        else -> {
            Scaffold(
                topBar = {
                    when (viewModel.patchState) {
                        NewPatchViewModel.PatchState.CONFIGURING -> Unit
                        NewPatchViewModel.PatchState.PATCHING,
                        NewPatchViewModel.PatchState.FINISHED,
                        NewPatchViewModel.PatchState.ERROR -> CenterAlignedTopAppBar(title = { Text(viewModel.patchApp.app.packageName) })
                        else -> Unit
                    }
                },
                // Keep the window/status-bar area consistent with the patch page.
                containerColor = YtpColors.PageContainer
            ) { innerPadding ->
                if (viewModel.patchState == NewPatchViewModel.PatchState.CONFIGURING) {
                    PatchOptionsBody(
                        modifier = Modifier.padding(innerPadding),
                        onBackClick = { navigator.navigateUp() },
                        onAddEmbed = { showSelectModuleDialog = true }
                    )
                    resultRecipient.onNavResult {
                        if (it is NavResult.Value) {
                            val result = it.value as SelectAppsResult.MultipleApps
                            viewModel.embeddedModules = result.selected
                        }
                    }
                } else {
                    DoPatchBody(Modifier.padding(innerPadding), navigator)
                }
            }

            if (showSelectModuleDialog) {
                EmbedModulesDialog(
                    selectedCount = viewModel.embeddedModules.size,
                    onDismissRequest = { showSelectModuleDialog = false },
                    onSelectStorage = {
                        storageModuleLauncher.launch(arrayOf("application/vnd.android.package-archive"))
                        showSelectModuleDialog = false
                    },
                    onSelectInstalledModules = {
                        navigator.navigate(
                            SelectAppsScreenDestination(
                                true,
                                viewModel.embeddedModules.mapTo(ArrayList()) {
                                    it.app.packageName
                                }
                            )
                        )
                        showSelectModuleDialog = false
                    }
                )
            }
        }
    }
}

@Composable
private fun EmbedModulesDialog(
    selectedCount: Int,
    onDismissRequest: () -> Unit,
    onSelectStorage: () -> Unit,
    onSelectInstalledModules: () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = YtpColors.Surface,
            shadowElevation = 18.dp,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .then(
                                if (YtpColors.IsMonochromeTheme) {
                                    Modifier.background(YtpColors.IconButtonSurface)
                                } else {
                                    Modifier.background(
                                        Brush.linearGradient(
                                            listOf(
                                                YtpColors.PrimaryLight,
                                                YtpColors.Primary
                                            )
                                        )
                                    )
                                }
                            )
                            .border(
                                1.dp,
                                YtpColors.IconButtonBorder,
                                RoundedCornerShape(18.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = if (YtpColors.IsMonochromeTheme) {
                                YtpColors.IconContent
                            } else {
                                YtpColors.OnPrimary
                            }
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.patch_embed_modules),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = YtpColors.TextPrimary
                        )

                        Text(
                            text = stringResource(
                                R.string.patch_embed_modules_dialog_desc
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = YtpColors.TextSecondary
                        )
                    }
                }

                if (selectedCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = YtpColors.PrimaryContainer,
                        contentColor = YtpColors.iconForeground(YtpColors.Primary)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.patch_embed_modules_selected,
                                selectedCount
                            ),
                            modifier = Modifier.padding(
                                horizontal = 12.dp,
                                vertical = 6.dp
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EmbedModuleSourceOption(
                        icon = Icons.Outlined.FolderOpen,
                        title = stringResource(
                            R.string.patch_embed_modules_from_storage
                        ),
                        supportingText = stringResource(
                            R.string.patch_embed_modules_from_storage_desc
                        ),
                        accent = YtpColors.Primary,
                        startColor = YtpColors.PrimarySurface,
                        endColor = YtpColors.PrimarySurfaceHighlight,
                        onClick = onSelectStorage
                    )

                    EmbedModuleSourceOption(
                        icon = Icons.Outlined.Apps,
                        title = stringResource(
                            R.string.patch_embed_modules_from_apps
                        ),
                        supportingText = stringResource(
                            R.string.patch_embed_modules_from_apps_desc
                        ),
                        accent = YtpColors.Success,
                        startColor = YtpColors.PrimarySurface,
                        endColor = YtpColors.PrimarySurfaceHighlight,
                        onClick = onSelectInstalledModules
                    )
                }

                TextButton(
                    modifier = Modifier.align(Alignment.End),
                    onClick = onDismissRequest
                ) {
                    Text(
                        text = stringResource(android.R.string.cancel),
                        color = YtpColors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun EmbedModuleSourceOption(
    icon: ImageVector,
    title: String,
    supportingText: String,
    accent: Color,
    startColor: Color,
    endColor: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        border = BorderStroke(
            1.dp,
            accent.copy(alpha = 0.10f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(startColor, endColor)
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(YtpColors.iconBackground(accent, 0.10f))
                        .border(
                            1.dp,
                            YtpColors.IconButtonBorder,
                            RoundedCornerShape(15.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(23.dp),
                        tint = YtpColors.iconForeground(accent)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = YtpColors.TextPrimary
                    )

                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = YtpColors.TextSecondary
                    )
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(YtpColors.iconBackground(accent, 0.08f))
                        .border(1.dp, YtpColors.IconButtonBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = YtpColors.iconForeground(accent)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmbedModuleSourceOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    supportingText: String,
    emphasized: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (emphasized) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val iconColor = YtpColors.iconForeground(YtpColors.Primary)

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = MaterialTheme.shapes.medium,
                color = iconColor.copy(alpha = 0.09f),
                contentColor = iconColor
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(11.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = iconColor.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun PatchOptionsBody(
    modifier: Modifier,
    onBackClick: () -> Unit,
    onAddEmbed: () -> Unit
) {
    val viewModel = viewModel<NewPatchViewModel>()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(YtpColors.PageChrome)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 118.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PatchHero(onBackClick = onBackClick)

            TargetAppCard()

            PatchIdentityCard()

            PatchNotice()

            if (viewModel.embeddedModules.isNotEmpty()) {
                    PatchSectionHeader(
                        title = stringResource(
                            R.string.patch_embed_modules_count,
                            viewModel.embeddedModules.size
                        ),
                        trailing = stringResource(
                            R.string.patch_selected_count,
                            viewModel.embeddedModules.size
                        )
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    viewModel.embeddedModules.forEachIndexed { index, module ->
                        EmbeddedModuleCard(
                            label = module.label,
                            packageName = module.app.packageName,
                            icon = {
                                YtpAppIcon(
                                    bitmap = org.ytp.util.LSPPackageManager.getIcon(module),
                                    contentDescription = module.label,
                                    modifier = Modifier.size(52.dp)
                                )
                            },
                            onDelete = {
                                viewModel.embeddedModules = viewModel.embeddedModules
                                    .toMutableList()
                                    .apply { removeAt(index) }
                            }
                        )
                    }
                }
            }
        }

        PatchBottomActions(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            onAddEmbed = onAddEmbed,
            onStartPatch = {
                viewModel.dispatch(NewPatchViewModel.ViewAction.SubmitPatch)
            }
        )
    }
}

@Composable
private fun PatchHero(
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

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = stringResource(R.string.screen_new_patch),
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = YtpColors.TextPrimary
        )
    }
}
@Composable
private fun TargetAppCard() {
    val viewModel = viewModel<NewPatchViewModel>()

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
        colors = CardDefaults.cardColors(containerColor = YtpColors.Surface),
        border = BorderStroke(1.dp, YtpColors.Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            YtpAppIcon(
                bitmap = org.ytp.util.LSPPackageManager.getIcon(viewModel.patchApp),
                contentDescription = viewModel.patchApp.label,
                modifier = Modifier.size(62.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = viewModel.patchApp.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = YtpColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = viewModel.patchApp.app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = YtpColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

        }
    }
}

@Composable
private fun PatchIdentityCard() {
    val viewModel = viewModel<NewPatchViewModel>()
    val shape = RoundedCornerShape(26.dp)
    val newPackageName = viewModel.newPackageName
    val packageNameValid = newPackageName.isEmpty() || isValidPackageName(newPackageName)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = YtpColors.Shadow.copy(alpha = 0.025f),
                spotColor = YtpColors.Primary.copy(alpha = 0.06f)
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = YtpColors.Surface),
        border = BorderStroke(1.dp, YtpColors.Border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.patch_identity_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = YtpColors.TextPrimary
            )

            Text(
                text = stringResource(R.string.patch_identity_desc),
                style = MaterialTheme.typography.bodySmall,
                color = YtpColors.TextSecondary
            )

            OutlinedTextField(
                value = newPackageName,
                onValueChange = { viewModel.newPackageName = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = !packageNameValid,
                label = { Text(stringResource(R.string.patch_new_package_name)) },
                placeholder = {
                    Text(
                        text = viewModel.patchApp.app.packageName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingText = {
                    Text(
                        text = if (packageNameValid) {
                            stringResource(R.string.patch_identity_hint)
                        } else {
                            stringResource(R.string.patch_identity_invalid)
                        }
                    )
                }
            )

            OutlinedTextField(
                value = viewModel.appLabel,
                onValueChange = { viewModel.appLabel = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.patch_new_app_label)) },
                placeholder = {
                    Text(
                        text = viewModel.patchApp.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingText = {
                    Text(stringResource(R.string.patch_identity_hint))
                }
            )
        }
    }
}

/** 包名至少要有一个点，且只允许字母、数字、下划线和点。 */
private fun isValidPackageName(value: String): Boolean {
    if (!value.contains('.')) return false
    if (value.startsWith('.') || value.endsWith('.')) return false
    return value.all { it.isLetterOrDigit() || it == '_' || it == '.' } &&
            value.split('.').all { it.isNotEmpty() }
}

@Composable
private fun PatchNotice() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(22.dp),
                ambientColor = YtpColors.Shadow.copy(alpha = 0.02f),
                spotColor = YtpColors.Primary.copy(alpha = 0.06f)
            ),
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        border = BorderStroke(
            1.dp,
            YtpColors.Primary.copy(alpha = 0.10f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            YtpColors.PrimarySurface,
                            YtpColors.PrimarySurfaceHighlight,
                            YtpColors.PrimarySurface
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            YtpColors.iconBackground(YtpColors.AccentPurple, 0.10f)
                        )
                        .border(1.dp, YtpColors.IconButtonBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        tint = YtpColors.iconForeground(YtpColors.AccentPurple)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = stringResource(R.string.patch_integrated_desc),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = YtpColors.TextPrimary
                    )

                    Text(
                        text = stringResource(R.string.patch_integrated_signature_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = YtpColors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun PatchSectionHeader(
    title: String,
    trailing: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.verticalGradient(
                        listOf(YtpColors.PrimaryAccent, YtpColors.Primary)
                    )
                )
        )

        Spacer(Modifier.width(9.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = YtpColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = trailing,
            style = MaterialTheme.typography.labelMedium,
            color = YtpColors.TextSecondary
        )
    }
}

@Composable
private fun EmbeddedModuleCard(
    label: String,
    packageName: String,
    icon: @Composable () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = YtpColors.Shadow.copy(alpha = 0.02f),
                spotColor = YtpColors.Primary.copy(alpha = 0.045f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = YtpColors.Surface),
        border = BorderStroke(1.dp, YtpColors.Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                YtpColors.Primary.copy(alpha = 0.08f),
                                YtpColors.PrimaryAccent.copy(alpha = 0.05f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = YtpColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = YtpColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(YtpColors.iconBackground(YtpColors.Error, 0.07f))
                    .border(1.dp, YtpColors.IconButtonBorder, CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = YtpColors.iconForeground(YtpColors.Error)
                )
            }
        }
    }
}

@Composable
private fun PatchBottomActions(
    modifier: Modifier,
    onAddEmbed: () -> Unit,
    onStartPatch: () -> Unit
) {
    Surface(
        modifier = modifier,
        color = YtpColors.Surface.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp)
                    .clickable(onClick = onAddEmbed),
                shape = RoundedCornerShape(22.dp),
                color = YtpColors.PrimaryContainer,
                contentColor = YtpColors.iconForeground(YtpColors.Primary),
                border = BorderStroke(1.dp, YtpColors.IconButtonBorder)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = stringResource(R.string.patch_embed_modules),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(onClick = onStartPatch),
                shape = RoundedCornerShape(22.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, YtpColors.PrimaryActionBorder)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (YtpColors.IsMonochromeTheme) {
                                Modifier.background(YtpColors.PrimaryActionSurface)
                            } else {
                                Modifier.background(
                                    Brush.linearGradient(
                                        listOf(
                                            YtpColors.PrimaryLight,
                                            YtpColors.Primary,
                                            YtpColors.PrimaryDeep
                                        )
                                    )
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = YtpColors.PrimaryActionContent
                        )

                        Spacer(Modifier.width(8.dp))

                        Text(
                            text = stringResource(R.string.patch_start),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = YtpColors.PrimaryActionContent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DoPatchBody(modifier: Modifier, navigator: DestinationsNavigator) {
    val viewModel = viewModel<NewPatchViewModel>()
    val snackbarHost =org.ytp.ui.util.LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    var showInstallDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (viewModel.logs.isEmpty()) {
            viewModel.dispatch(NewPatchViewModel.ViewAction.LaunchPatch)
        }
    }

    val scrollState = rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(YtpColors.PageChrome)
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        YtpCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            containerColor = YtpColors.Surface,
            borderColor = YtpColors.Divider
        ) {
            ProvideTextStyle(
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            ) {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(YtpColors.PageChrome),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(viewModel.logs) { entry ->
                        Text(
                            text = entry.second,
                            color = when (entry.first) {
                                Log.DEBUG -> YtpColors.TextSecondary
                                Log.ERROR -> YtpColors.Error
                                else -> YtpColors.TextPrimary
                            }
                        )
                    }
                }
            }
        }

        LaunchedEffect(scrollState.lastItemIndex) {
            scrollState.lastItemIndex?.let { scrollState.animateScrollToItem(it) }
        }

        when (viewModel.patchState) {
            NewPatchViewModel.PatchState.PATCHING -> {
                BackHandler {}
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = YtpColors.PrimaryContainer,
                    contentColor = YtpColors.iconForeground(YtpColors.Primary),
                    border = BorderStroke(1.dp, YtpColors.IconButtonBorder)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Terminal,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.patch_status_patching),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = YtpColors.ThemePrimary,
                            trackColor = YtpColors.ThemePrimary.copy(alpha = 0.14f)
                        )
                    }
                }
            }
            NewPatchViewModel.PatchState.FINISHED -> {
                val installSuccessfully = stringResource(R.string.patch_install_successfully)
                val installFailed = stringResource(R.string.patch_install_failed)
                val copyError = stringResource(R.string.copy_error)
                val onFinish: (Int, String?) -> Unit = { status, message ->
                    scope.launch {
                        if (status == PackageInstaller.STATUS_SUCCESS) {
                            snackbarHost.showSnackbar(installSuccessfully)
                            navigator.navigateUp()
                        } else if (status !=org.ytp.util.LSPPackageManager.STATUS_USER_CANCELLED) {
                            val result = snackbarHost.showSnackbar(installFailed, copyError)
                            if (result == SnackbarResult.ActionPerformed) {
                                val cm =org.ytp.lspApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText(org.ytp.share.Constants.UPPER_CASE_NAME, message))
                            }
                        }
                        showInstallDialog = false
                    }
                }

                if (showInstallDialog) {
                    InstallDialog2(viewModel.patchApp, onFinish)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = { navigator.navigateUp() },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = YtpColors.PrimaryContainer,
                            contentColor = YtpColors.iconForeground(YtpColors.Primary)
                        ),
                        border = BorderStroke(1.dp, YtpColors.IconButtonBorder),
                        content = { Text(stringResource(R.string.patch_return)) }
                    )
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { showInstallDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = YtpColors.ThemePrimary,
                            contentColor = YtpColors.PrimaryActionContent
                        ),
                        content = { Text(stringResource(R.string.install)) }
                    )
                }
            }
            NewPatchViewModel.PatchState.ERROR -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = { navigator.navigateUp() },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = YtpColors.PrimaryContainer,
                            contentColor = YtpColors.iconForeground(YtpColors.Primary)
                        ),
                        border = BorderStroke(1.dp, YtpColors.IconButtonBorder),
                        content = { Text(stringResource(R.string.patch_return)) }
                    )
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val cm =org.ytp.lspApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText(org.ytp.share.Constants.UPPER_CASE_NAME, viewModel.logs.joinToString(separator = "\n") { it.second }))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = YtpColors.ThemePrimary,
                            contentColor = YtpColors.PrimaryActionContent
                        ),
                        content = { Text(stringResource(R.string.copy_error)) }
                    )
                }
            }
            else -> Unit
        }
    }
}


@Composable
private fun InstallDialog2(patchApp: org.ytp.util.LSPPackageManager.AppInfo, onFinish: (Int, String?) -> Unit) {

    fun doInstall() {
        Log.i(TAG, "Installing app with system installer: ${patchApp.app.packageName}")
        val apkFiles =org.ytp.lspApp.targetApkFiles
        if (apkFiles.isNullOrEmpty()){
            onFinish(PackageInstaller.STATUS_FAILURE, "No target APK files found for installation")
            return
        }
        // install every apk of the patched app, otherwise apps with split apks stay incomplete
        installApks(_root_ide_package_.org.ytp.lspApp, apkFiles)
    }

    LaunchedEffect(patchApp.app.packageName) {
        Log.d(TAG, "State changed to install, starting installation via system.")
        doInstall()
        // Since system installer is an Intent, it's fire-and-forget. We can dismiss our UI.
        onFinish(_root_ide_package_.org.ytp.util.LSPPackageManager.STATUS_USER_CANCELLED, "Handed over to system installer")
    }
}
