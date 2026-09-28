package org.ytp.ui.component

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import org.ytp.R
import org.ytp.config.Configs
import org.ytp.ui.page.ACTION_APPLIST
import org.ytp.ui.page.ACTION_STORAGE
import org.ytp.ui.page.destinations.NewPatchScreenDestination
import org.ytp.ui.theme.YtpColors
import org.ytp.ui.util.LocalSnackbarHost
import java.io.IOException

private const val TAG = "AppFab"

/**
 * Floating "+" button in the bottom-end corner of the home screen. Choosing an apk happens in two
 * steps:
 * first the user picks where the apk comes from (storage or the list of installed apps),
 * then - only when it is actually missing - a writable storage directory is requested,
 * because patched apks are written there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFab(navigator: DestinationsNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    var showChooser by remember { mutableStateOf(false) }
    var shouldSelectDirectory by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<Int?>(null) }

    val errorText = stringResource(R.string.patch_select_dir_error)

    fun openPatch(action: Int) {
        val uri = Configs.storageDirectory?.toUri()
        if (uri == null) {
            pendingAction = action
            shouldSelectDirectory = true
            return
        }
        runCatching {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            if (DocumentFile.fromTreeUri(context, uri)?.exists() == false) {
                throw IOException("Storage directory was deleted")
            }
        }.onSuccess {
            navigator.navigate(NewPatchScreenDestination(id = action))
        }.onFailure {
            Log.w(TAG, "Failed to take persistable permission for saved uri", it)
            Configs.storageDirectory = null
            pendingAction = action
            shouldSelectDirectory = true
        }
    }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            try {
                if (it.resultCode == Activity.RESULT_CANCELED) {
                    pendingAction = null
                    return@rememberLauncherForActivityResult
                }
                val uri = it.data?.data ?: throw IOException("No data")
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                Configs.storageDirectory = uri.toString()
                Log.i(TAG, "Storage directory: ${uri.path}")
                val action = pendingAction
                pendingAction = null
                if (action != null) {
                    navigator.navigate(NewPatchScreenDestination(id = action))
                } else {
                    showChooser = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error when requesting saving directory", e)
                scope.launch { snackbarHost.showSnackbar(errorText) }
            }
        }

    if (shouldSelectDirectory) {
        AlertDialog(
            onDismissRequest = {
                shouldSelectDirectory = false
                pendingAction = null
            },
            confirmButton = {
                TextButton(
                    content = { Text(stringResource(android.R.string.ok)) },
                    onClick = {
                        launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                        shouldSelectDirectory = false
                    }
                )
            },
            dismissButton = {
                TextButton(
                    content = { Text(stringResource(android.R.string.cancel)) },
                    onClick = {
                        shouldSelectDirectory = false
                        pendingAction = null
                    }
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.patch_select_dir_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = { Text(stringResource(R.string.patch_select_dir_text)) }
        )
    }

    if (showChooser) {
        AlertDialog(
            onDismissRequest = { showChooser = false },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    content = { Text(stringResource(android.R.string.cancel)) },
                    onClick = { showChooser = false }
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.screen_new_patch),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showChooser = false
                            openPatch(ACTION_APPLIST)
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = YtpColors.PrimaryContainer,
                            contentColor = YtpColors.iconForeground(YtpColors.Primary)
                        )
                    ) {
                        Icon(Icons.Outlined.Apps, contentDescription = null)
                        Text(
                            modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                            text = stringResource(R.string.patch_from_applist),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showChooser = false
                            openPatch(ACTION_STORAGE)
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = YtpColors.PrimaryContainer,
                            contentColor = YtpColors.iconForeground(YtpColors.Primary)
                        )
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Text(
                            modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                            text = stringResource(R.string.patch_from_storage),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        )
    }

    FloatingActionButton(
        onClick = { showChooser = true },
        modifier = modifier,
        containerColor = YtpColors.Primary,
        contentColor = YtpColors.OnPrimary
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = stringResource(R.string.add),
            modifier = Modifier.size(28.dp)
        )
    }
}
