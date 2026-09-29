package org.ytp.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Ballot
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ytp.R
import org.ytp.config.Configs
import org.ytp.config.MyKeyStore
import org.ytp.ui.component.AnywhereDropdown
import org.ytp.ui.page.destinations.AboutScreenDestination
import org.ytp.ui.theme.YtpColors
import org.ytp.ui.util.LocalSnackbarHost
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "SettingsScreen"

private val hueGradientColors = listOf(
    Color.Red,
    Color.Yellow,
    Color.Green,
    Color.Cyan,
    Color.Blue,
    Color.Magenta,
    Color.Red
)

@Destination
@Composable
fun SettingsScreen(navigator: DestinationsNavigator) {
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
            SettingsHero()

            SettingsSectionHeader(
                title = stringResource(R.string.settings_group_theme),
                trailing = stringResource(R.string.settings_group_theme_hint),
                accent = YtpColors.Primary
            )

            SettingsCard {
                ColorPalette()
                SettingsDividerLine()
                BackgroundReplace()
                if (Configs.backgroundImage.isNotEmpty()) {
                    SettingsDividerLine()
                    SurfaceOpacity()
                }
            }

            SettingsSectionHeader(
                title = stringResource(R.string.settings_group_other),
                trailing = stringResource(R.string.settings_group_other_hint),
                accent = YtpColors.AccentPurple
            )

            SettingsCard {
                KeyStore()
                SettingsDividerLine()
                StorageDirectory()
                SettingsDividerLine()
                DetailPatchLogs()
                SettingsDividerLine()
                About(navigator)
            }
        }
    }
}

@Composable
private fun SettingsHero() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.screen_settings),
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = YtpColors.TextPrimary
        )
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    trailing: String,
    accent: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.68f), accent)
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
private fun SettingsCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(26.dp),
                ambientColor = YtpColors.Shadow.copy(alpha = 0.025f),
                spotColor = YtpColors.Primary.copy(alpha = 0.05f)
            ),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = YtpColors.Surface),
        border = BorderStroke(1.dp, YtpColors.Border)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsDividerLine() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 82.dp, end = 16.dp),
        thickness = 1.dp,
        color = YtpColors.Divider
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    desc: String? = null,
    accent: Color,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            YtpColors.iconBackground(accent, 0.12f),
                            YtpColors.iconBackground(accent, 0.06f)
                        )
                    )
                )
                .border(
                    1.dp,
                    YtpColors.IconButtonBorder,
                    RoundedCornerShape(18.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(25.dp),
                tint = YtpColors.iconForeground(accent)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = YtpColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!desc.isNullOrBlank()) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = YtpColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(YtpColors.iconBackground(accent, 0.06f))
                    .border(1.dp, YtpColors.IconButtonBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = YtpColors.iconForeground(accent).copy(alpha = 0.85f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyStore() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    AnywhereDropdown(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        onClick = { expanded = true },
        surface = {
            SettingRow(
                icon = Icons.Outlined.Ballot,
                title = stringResource(R.string.settings_keystore),
                desc = MyKeyStore.currentKeyStoreName,
                accent = YtpColors.Primary,
                onClick = null,
                trailing = {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                YtpColors.iconBackground(YtpColors.Primary, 0.06f)
                            )
                            .border(1.dp, YtpColors.IconButtonBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = YtpColors.iconForeground(YtpColors.Primary)
                                .copy(alpha = 0.85f)
                        )
                    }
                }
            )
        }
    ) {
        val allKeyStores = MyKeyStore.getAllKeyStores()

        allKeyStores.filter { it.isDefault }.forEach { keyStore ->
            DropdownMenuItem(
                text = {
                    Text(
                        "${keyStore.name} " +
                                "(${stringResource(R.string.settings_keystore_default)})"
                    )
                },
                onClick = {
                    scope.launch {
                        try {
                            MyKeyStore.selectKeyStoreDialog(keyStore.name)
                            expanded = false
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                e.message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )
        }

        allKeyStores.filter { !it.isDefault }.forEach { keyStore ->
            DropdownMenuItem(
                text = { Text(keyStore.name) },
                onClick = {
                    scope.launch {
                        try {
                            MyKeyStore.selectKeyStore(keyStore.name)
                            expanded = false
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to select key store ${keyStore.name}", e)
                        }
                    }
                },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                MyKeyStore.deleteKeyStore(keyStore.name)
                                expanded = false
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(
                                R.string.settings_delete
                            ),
                            tint = YtpColors.iconForeground(YtpColors.Primary)
                        )
                    }
                }
            )
        }

        DropdownMenuItem(
            text = {
                Text(stringResource(R.string.settings_keystore_custom))
            },
            onClick = {
                expanded = false
                showDialog = true
            },
            trailingIcon = {
                IconButton(
                    onClick = {
                        expanded = false
                        showDialog = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(
                            R.string.settings_keystore_custom
                        ),
                        tint = YtpColors.iconForeground(YtpColors.Primary)
                    )
                }
            }
        )
    }

    if (showDialog) {
        KeyStoreDialog(
            onClose = {
                expanded = false
                showDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyStoreDialog(
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var wrongKeystore by rememberSaveable { mutableStateOf(false) }
    var wrongPassword by rememberSaveable { mutableStateOf(false) }
    var wrongAliasName by rememberSaveable { mutableStateOf(false) }
    var wrongAliasPassword by rememberSaveable { mutableStateOf(false) }

    var path by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var alias by rememberSaveable { mutableStateOf("") }
    var aliasPassword by rememberSaveable { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        context.contentResolver.openInputStream(uri).use { input ->
            MyKeyStore.tmpFile.outputStream().use { output ->
                input?.copyTo(output)
            }
        }
        path = uri.path ?: ""
    }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(
                content = {
                    Text(stringResource(android.R.string.ok))
                },
                onClick = {
                    wrongKeystore = false
                    wrongPassword = false
                    wrongAliasName = false
                    wrongAliasPassword = false

                    if (path.isEmpty()) {
                        wrongKeystore = true
                        return@TextButton
                    }

                    val keyStore = KeyStore.getInstance(
                        KeyStore.getDefaultType()
                    )

                    try {
                        MyKeyStore.tmpFile.inputStream().use { input ->
                            keyStore.load(
                                input,
                                password.toCharArray()
                            )
                        }
                    } catch (e: IOException) {
                        wrongKeystore = true
                        if (e.message == "KeyStore integrity check failed.") {
                            wrongPassword = true
                        }
                        return@TextButton
                    }

                    if (!keyStore.containsAlias(alias)) {
                        wrongAliasName = true
                        return@TextButton
                    }

                    try {
                        keyStore.getKey(
                            alias,
                            aliasPassword.toCharArray()
                        )
                    } catch (e: GeneralSecurityException) {
                        wrongAliasPassword = true
                        return@TextButton
                    }

                    scope.launch {
                        try {
                            MyKeyStore.addKeyStore(
                                password,
                                alias,
                                aliasPassword
                            )
                            onClose()
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                e.message,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
        },
        dismissButton = {
            TextButton(
                onClick = onClose
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        title = {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(
                    R.string.settings_keystore_dialog_title
                ),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val interactionSource = remember {
                    MutableInteractionSource()
                }

                LaunchedEffect(interactionSource) {
                    interactionSource.interactions.collect { interaction ->
                        if (interaction is PressInteraction.Release) {
                            launcher.launch("*/*")
                        }
                    }
                }

                val wrongText = when {
                    wrongAliasPassword -> stringResource(
                        R.string.settings_keystore_wrong_alias_password
                    )

                    wrongAliasName -> stringResource(
                        R.string.settings_keystore_wrong_alias
                    )

                    wrongPassword -> stringResource(
                        R.string.settings_keystore_wrong_password
                    )

                    wrongKeystore -> stringResource(
                        R.string.settings_keystore_wrong_keystore
                    )

                    else -> null
                }

                Text(
                    text = wrongText ?: stringResource(
                        R.string.settings_keystore_desc
                    ),
                    color = if (wrongText != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        YtpColors.TextSecondary
                    }
                )

                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    readOnly = true,
                    label = {
                        Text(
                            stringResource(
                                R.string.settings_keystore_file
                            )
                        )
                    },
                    placeholder = {
                        Text(
                            stringResource(
                                R.string.settings_keystore_file
                            )
                        )
                    },
                    singleLine = true,
                    isError = wrongKeystore,
                    interactionSource = interactionSource,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = {
                        Text(
                            stringResource(
                                R.string.settings_keystore_password
                            )
                        )
                    },
                    singleLine = true,
                    isError = wrongPassword,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = {
                        Text(
                            stringResource(
                                R.string.settings_keystore_alias
                            )
                        )
                    },
                    singleLine = true,
                    isError = wrongAliasName,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = aliasPassword,
                    onValueChange = { aliasPassword = it },
                    label = {
                        Text(
                            stringResource(
                                R.string.settings_keystore_alias_password
                            )
                        )
                    },
                    singleLine = true,
                    isError = wrongAliasPassword,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = YtpColors.Surface
    )
}

@Composable
private fun DetailPatchLogs() {
    var checked by rememberSaveable {
        mutableStateOf(Configs.detailPatchLogs)
    }

    SettingRow(
        icon = Icons.Outlined.BugReport,
        title = stringResource(R.string.settings_detail_patch_logs),
        desc = stringResource(R.string.settings_detail_patch_logs_description),
        accent = YtpColors.AccentPurple,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    Configs.detailPatchLogs = it
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = YtpColors.OnPrimary,
                    checkedTrackColor = YtpColors.ThemePrimary,
                    uncheckedThumbColor = YtpColors.OnPrimary,
                    uncheckedTrackColor = YtpColors.ControlTrackOff,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    )
}

@Composable
private fun ColorPalette() {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    SettingRow(
        icon = Icons.Outlined.Palette,
        title = stringResource(R.string.settings_color_palette),
        desc = stringResource(
            R.string.settings_color_palette_value,
            YtpColors.Primary.toHex()
        ),
        accent = YtpColors.Primary,
        onClick = { showDialog = true }
    )

    if (showDialog) {
        ColorPaletteDialog(
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun BackgroundReplace() {
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val errorText = stringResource(R.string.settings_background_error)
    var showDialog by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            try {
                // 复制可能很大，放到 IO 线程；Configs.applyBackgroundImage 写的是 Compose
                // 状态，所以回到主线程后再调用。
                val target = withContext(Dispatchers.IO) {
                    val file = File(
                        context.filesDir,
                        "page_background_${System.currentTimeMillis()}.img"
                    )

                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IOException("No data")

                    file
                }

                Configs.applyBackgroundImage(target.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Error when reading background image", e)
                snackbarHost.showSnackbar(errorText)
            }
        }
    }

    SettingRow(
        icon = Icons.Outlined.Image,
        title = stringResource(R.string.settings_background),
        desc = if (Configs.backgroundImage.isEmpty()) {
            stringResource(R.string.settings_background_default)
        } else {
            stringResource(R.string.settings_background_custom)
        },
        accent = YtpColors.Primary,
        onClick = { showDialog = true }
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.settings_background)) },
            text = {
                Text(stringResource(R.string.settings_background_dialog_text))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        picker.launch("image/*")
                    }
                ) {
                    Text(stringResource(R.string.settings_background_pick))
                }
            },
            dismissButton = {
                if (Configs.backgroundImage.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            showDialog = false
                            Configs.clearBackgroundImage()
                        }
                    ) {
                        Text(stringResource(R.string.settings_background_reset))
                    }
                }
            }
        )
    }
}

/** 背景图存在时的「控件透明度」滑块：控制卡片表面的不透明度。 */
@Composable
private fun SurfaceOpacity() {
    val rowShape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(rowShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                YtpColors.iconBackground(YtpColors.Primary, 0.12f),
                                YtpColors.iconBackground(YtpColors.Primary, 0.06f)
                            )
                        )
                    )
                    .border(1.dp, YtpColors.IconButtonBorder, rowShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Opacity,
                    contentDescription = null,
                    modifier = Modifier.size(25.dp),
                    tint = YtpColors.iconForeground(YtpColors.Primary)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_surface_alpha),
                    style = MaterialTheme.typography.bodyLarge,
                    color = YtpColors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.settings_surface_alpha_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = YtpColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = "${(Configs.surfaceAlpha * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = YtpColors.Primary
            )
        }

        Slider(
            value = Configs.surfaceAlpha,
            onValueChange = { Configs.surfaceAlpha = it },
            valueRange = Configs.MinSurfaceAlpha..1f,
            modifier = Modifier.padding(start = 64.dp, end = 4.dp)
        )
    }
}

@Composable
private fun ColorPaletteDialog(
    onDismiss: () -> Unit
) {
    val currentColor = YtpColors.Primary
    val currentHsv = remember(currentColor) { currentColor.toHsv() }
    var hexValue by remember(currentColor) {
        mutableStateOf(currentColor.toHex())
    }
    var hue by remember(currentColor) {
        mutableStateOf(currentHsv[0])
    }
    var saturation by remember(currentColor) {
        mutableStateOf(currentHsv[1])
    }
    var value by remember(currentColor) {
        mutableStateOf(currentHsv[2])
    }
    var alpha by remember(currentColor) {
        mutableStateOf(currentColor.alpha)
    }
    var hasValidInput by remember { mutableStateOf(true) }
    val selectedColor = hsvToColor(hue, saturation, value, alpha)

    fun selectColor(color: Color) {
        val hsv = color.toHsv()
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        alpha = color.alpha
        hexValue = color.toHex()
        hasValidInput = true
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(28.dp),
            color = YtpColors.Surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_color_palette_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = YtpColors.TextPrimary
                )

                Text(
                    text = stringResource(R.string.settings_color_palette_preview),
                    style = MaterialTheme.typography.labelLarge,
                    color = YtpColors.TextSecondary
                )

                HsvColorField(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onValueChange = { newSaturation, newValue ->
                        saturation = newSaturation
                        value = newValue
                        hexValue = hsvToColor(
                            hue,
                            newSaturation,
                            newValue,
                            alpha
                        ).toHex()
                        hasValidInput = true
                    }
                )

                HueSlider(
                    hue = hue,
                    onHueChange = { newHue ->
                        hue = newHue
                        hexValue = hsvToColor(
                            newHue,
                            saturation,
                            value,
                            alpha
                        ).toHex()
                        hasValidInput = true
                    }
                )

                AlphaSlider(
                    color = selectedColor,
                    alpha = alpha,
                    onAlphaChange = { newAlpha ->
                        alpha = newAlpha
                        hexValue = hsvToColor(
                            hue,
                            saturation,
                            value,
                            newAlpha
                        ).toHex()
                        hasValidInput = true
                    }
                )

                OutlinedTextField(
                    value = hexValue,
                    onValueChange = { value ->
                        hexValue = value
                        parseYtpColor(value)?.let { color ->
                            selectColor(color)
                            hasValidInput = true
                        } ?: run {
                            hasValidInput = false
                        }
                    },
                    label = {
                        Text(stringResource(R.string.settings_color_palette_hex))
                    },
                    singleLine = true,
                    isError = !hasValidInput,
                    supportingText = if (!hasValidInput) {
                        {
                            Text(stringResource(R.string.settings_color_palette_invalid))
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    YtpColors.PrimaryPresets.chunked(4).forEach { presetRow ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            presetRow.forEach { color ->
                                PaletteSwatch(
                                    color = color,
                                    selected = selectedColor == color,
                                    onClick = { selectColor(color) },
                                    swatchSize = 38.dp
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            selectColor(YtpColors.DefaultPrimary)
                        }
                    ) {
                        Text(stringResource(R.string.settings_color_palette_reset))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Button(
                        enabled = hasValidInput,
                        onClick = {
                            Configs.primaryColor =
                                selectedColor.toArgb().toLong() and 0xFFFFFFFFL
                            onDismiss()
                        }
                    ) {
                        Text(stringResource(R.string.settings_color_palette_apply))
                    }
                }
            }
        }
    }
}

@Composable
private fun HsvColorField(
    hue: Float,
    saturation: Float,
    value: Float,
    onValueChange: (Float, Float) -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val hueColor = hsvToColor(hue, 1f, 1f, 1f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(shape)
            .pointerInput(hue) {
                detectDragGestures(
                    onDragStart = { position ->
                        onValueChange(
                            (position.x / size.width).coerceIn(0f, 1f),
                            (1f - position.y / size.height).coerceIn(0f, 1f)
                        )
                    },
                    onDrag = { change, _ ->
                        onValueChange(
                            (change.position.x / size.width).coerceIn(0f, 1f),
                            (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        )
                    }
                )
            }
    ) {
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color.White, hueColor)
            )
        )
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black)
            )
        )

        val indicatorCenter = Offset(
            x = size.width * saturation,
            y = size.height * (1f - value)
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.55f),
            radius = 10.dp.toPx(),
            center = indicatorCenter,
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = Color.White,
            radius = 8.dp.toPx(),
            center = indicatorCenter,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
private fun HueSlider(
    hue: Float,
    onHueChange: (Float) -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(50))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { position ->
                        onHueChange(
                            (position.x / size.width * 360f).coerceIn(0f, 360f)
                        )
                    },
                    onDrag = { change, _ ->
                        onHueChange(
                            (change.position.x / size.width * 360f).coerceIn(0f, 360f)
                        )
                    }
                )
            }
    ) {
        drawRect(brush = Brush.horizontalGradient(hueGradientColors))
        val indicatorX = size.width * (hue / 360f).coerceIn(0f, 1f)
        drawLine(
            color = Color.Black.copy(alpha = 0.55f),
            start = Offset(indicatorX, 1.dp.toPx()),
            end = Offset(indicatorX, size.height - 1.dp.toPx()),
            strokeWidth = 6.dp.toPx()
        )
        drawLine(
            color = Color.White,
            start = Offset(indicatorX, 1.dp.toPx()),
            end = Offset(indicatorX, size.height - 1.dp.toPx()),
            strokeWidth = 3.dp.toPx()
        )
    }
}

@Composable
private fun AlphaSlider(
    color: Color,
    alpha: Float,
    onAlphaChange: (Float) -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(50))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { position ->
                        onAlphaChange(
                            (position.x / size.width).coerceIn(0f, 1f)
                        )
                    },
                    onDrag = { change, _ ->
                        onAlphaChange(
                            (change.position.x / size.width).coerceIn(0f, 1f)
                        )
                    }
                )
            }
    ) {
        val checkerSize = 8.dp.toPx()
        var top = 0f
        while (top < size.height) {
            var left = 0f
            while (left < size.width) {
                drawRect(
                    color = if (((left / checkerSize).toInt() +
                            (top / checkerSize).toInt()) % 2 == 0
                    ) {
                        Color.White
                    } else {
                        Color(0xFFD1D7E0)
                    },
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(checkerSize, checkerSize)
                )
                left += checkerSize
            }
            top += checkerSize
        }
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, color.copy(alpha = 1f))
            )
        )

        val indicatorX = size.width * alpha.coerceIn(0f, 1f)
        drawLine(
            color = Color.Black.copy(alpha = 0.55f),
            start = Offset(indicatorX, 1.dp.toPx()),
            end = Offset(indicatorX, size.height - 1.dp.toPx()),
            strokeWidth = 6.dp.toPx()
        )
        drawLine(
            color = Color.White,
            start = Offset(indicatorX, 1.dp.toPx()),
            end = Offset(indicatorX, size.height - 1.dp.toPx()),
            strokeWidth = 3.dp.toPx()
        )
    }
}

@Composable
private fun PaletteSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    swatchSize: androidx.compose.ui.unit.Dp = 42.dp
) {
    Box(
        modifier = Modifier
            .size(swatchSize)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) YtpColors.TextPrimary else YtpColors.Border,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun parseYtpColor(value: String): Color? {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 6 || normalized.any { it !in "0123456789abcdefABCDEF" }) {
        return null
    }

    return runCatching {
        Color(android.graphics.Color.parseColor("#$normalized"))
    }.getOrNull()
}

private fun Color.toHsv(): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    return hsv
}

private fun hsvToColor(
    hue: Float,
    saturation: Float,
    value: Float,
    alpha: Float
): Color = Color(
    android.graphics.Color.HSVToColor(
        (alpha * 255f).roundToInt().coerceIn(0, 255),
        floatArrayOf(hue, saturation, value)
    )
)

private fun Color.toHex(): String =
    "#%06X".format(Locale.US, toArgb() and 0xFFFFFF)

@SuppressLint("WrongConstant")
@Composable
private fun StorageDirectory() {
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val errorText = stringResource(R.string.patch_select_dir_error)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        try {
            if (it.resultCode == Activity.RESULT_CANCELED) {
                return@rememberLauncherForActivityResult
            }

            val uri = it.data?.data
                ?: throw IOException("No data")

            val takeFlags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            context.contentResolver.takePersistableUriPermission(
                uri,
                takeFlags
            )

            Configs.storageDirectory = uri.toString()
            Log.i(TAG, "Storage directory: ${uri.path}")
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Error when requesting saving directory",
                e
            )
            scope.launch {
                snackbarHost.showSnackbar(errorText)
            }
        }
    }

    val description = Configs.storageDirectory?.let { uri ->
        try {
            val decodedUri = URLDecoder.decode(uri, "UTF-8")
            decodedUri.substringAfterLast(':')
        } catch (e: Exception) {
            uri.substringAfterLast(':')
        }
    } ?: stringResource(R.string.patch_select_dir_text)

    SettingRow(
        icon = Icons.Outlined.Folder,
        title = stringResource(
            R.string.settings_storage_directory
        ),
        desc = description,
        accent = YtpColors.Primary,
        onClick = {
            launcher.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            )
        }
    )
}

@Composable
private fun About(
    navigator: DestinationsNavigator
) {
    SettingRow(
        icon = Icons.Outlined.Info,
        title = stringResource(R.string.settings_about),
        desc = stringResource(R.string.settings_about_description),
        accent = YtpColors.AccentPurple,
        onClick = {
            navigator.navigate(AboutScreenDestination)
        }
    )
}
