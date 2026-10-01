/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.page

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ytp.R
import org.ytp.config.Configs
import org.ytp.ui.component.CenterTopBar
import org.ytp.ui.theme.YtpColors
import org.ytp.util.PatchSound
import org.ytp.util.PatchSoundLibrary
import org.ytp.util.PatchSounds

/** 页面上的两个提示音位置：开始修补、修补完成。 */
private enum class SoundSlot { START, SUCCESS }

/**
 * 修补提示音设置页。
 *
 * 三块内容：总开关、两声提示音各选一个音源、以及提示音库（内置素材 + 自定义音频 + 试听/删除）。
 * 内置素材打包在本地构建的 APK 里（见 `manager/sounds`），删除只是把它从列表里移走，可以一键恢复；
 * 自定义音频是用户自己选的文件，可以截取其中一段。
 */
@Destination
@Composable
fun PatchSoundScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    var enabled by rememberSaveable { mutableStateOf(Configs.patchSounds) }
    var random by rememberSaveable { mutableStateOf(Configs.patchSoundsRandom) }
    var sounds by remember { mutableStateOf<List<PatchSound>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var staging by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf<SoundSlot?>(null) }
    var staged by remember { mutableStateOf<PatchSoundLibrary.StagedSound?>(null) }
    var pendingDelete by remember { mutableStateOf<PatchSound?>(null) }
    var previewing by remember { mutableStateOf<String?>(null) }

    // 提示音跟媒体音量走：0 时听不到、不到一半可能听不清，页面上先给一句提示（见 PatchSounds.volumeLevel）
    var volume by remember { mutableStateOf(PatchSounds.volumeLevel()) }

    // 提示音库要列文件、读时长，放 IO 线程；refreshKey 变了（增删/恢复）就重列一遍
    LaunchedEffect(refreshKey) {
        loading = true
        sounds = withContext(Dispatchers.IO) {
            // 顺带清掉自定义目录里没有被任何条目引用的残留（选文件失败、复制到一半被杀会留下）
            PatchSoundLibrary.cleanOrphans()
            PatchSoundLibrary.allSounds()
        }
        loading = false
    }

    // 用户可能在别处（音量键、快捷开关）改了音量，从别处回到本页时重新读一次
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) volume = PatchSounds.volumeLevel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 选完文件马上复制进私有目录并读时长，然后弹截取对话框
    //
    // 这里必须在选文件的回调里直接开协程。以前是先把 uri 存进 state、再用 `LaunchedEffect(uri)` 处理，
    // 而 effect 里又马上把 state 置空——键一变 Compose 就取消这个还停在 withContext 上的协程，
    // stage() 的结果永远没人接，于是截取对话框和失败提示都弹不出来（选完文件像没反应）。
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // SAF 选文件不需要存储权限，任何能播放的音频都行
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // 复制 + 读时长要读整段音频，大文件要几百毫秒到几秒，先给个转圈免得像没反应
            staging = true
            try {
                withContext(Dispatchers.IO) { PatchSoundLibrary.stage(uri) }
                    .onSuccess { staged = it }
                    .onFailure {
                        snackbarHost.showSnackbar(context.getString(R.string.patch_sound_pick_failed))
                    }
            } finally {
                staging = false
            }
        }
    }

    val startSound = sounds.firstOrNull { it.id == Configs.patchStartSound }
    val successSound = sounds.firstOrNull { it.id == Configs.patchSuccessSound }
    val removedCount = PatchSoundLibrary.removedIds().count { it.startsWith("bundled/") }

    // 音量提示的文案：0 是听不到，不到一半是可能听不清
    fun volumeTitle(level: PatchSounds.VolumeLevel): String = context.getString(
        if (level == PatchSounds.VolumeLevel.MUTED) {
            R.string.patch_sound_volume_muted_title
        } else {
            R.string.patch_sound_volume_low_title
        }
    )

    fun volumeDescription(level: PatchSounds.VolumeLevel): String = context.getString(
        if (level == PatchSounds.VolumeLevel.MUTED) {
            R.string.patch_sound_volume_muted
        } else {
            R.string.patch_sound_volume_low
        }
    )

    // 试听：再点同一条就是停；播完了（或放不出来）自动把按钮复位成「试听」
    fun togglePreview(sound: PatchSound) {
        if (previewing == sound.id) {
            PatchSounds.stopPreview()
            previewing = null
            return
        }
        // 音量太小或为 0 时先提示：直接播也是一点声音都没有，看着像试听坏了（见 PatchSounds.volumeLevel）
        val level = PatchSounds.volumeLevel()
        if (level != PatchSounds.VolumeLevel.OK) {
            volume = level
            scope.launch { snackbarHost.showSnackbar(volumeDescription(level)) }
            return
        }
        volume = level
        previewing = sound.id
        PatchSounds.preview(sound) {
            if (previewing == sound.id) previewing = null
        }
    }

    Scaffold(
        containerColor = YtpColors.PageContainer,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            CenterTopBar(
                text = stringResource(R.string.patch_sound_title),
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (volume != PatchSounds.VolumeLevel.OK) {
                    item {
                        SoundCard {
                            SettingLikeRow(
                                icon = Icons.Outlined.VolumeOff,
                                title = volumeTitle(volume),
                                desc = volumeDescription(volume),
                                accent = YtpColors.Warning
                            )
                        }
                    }
                }

                item {
                    SoundCard {
                        SettingLikeRow(
                            icon = Icons.Outlined.VolumeUp,
                            title = stringResource(R.string.settings_patch_sounds),
                            desc = stringResource(R.string.settings_patch_sounds_description),
                            accent = YtpColors.AccentPurple,
                            trailing = {
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = {
                                        enabled = it
                                        Configs.patchSounds = it
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
                }

                item {
                    SoundCard {
                        SettingLikeRow(
                            icon = Icons.Outlined.Shuffle,
                            title = stringResource(R.string.patch_sound_random),
                            desc = stringResource(R.string.patch_sound_random_hint),
                            accent = YtpColors.AccentPurple,
                            trailing = {
                                Switch(
                                    checked = random,
                                    onCheckedChange = {
                                        random = it
                                        Configs.patchSoundsRandom = it
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
                }

                item {
                    SoundSlotCard(
                        title = stringResource(R.string.patch_sound_start_title),
                        sound = startSound,
                        playing = previewing == startSound?.id,
                        onChoose = { choosing = SoundSlot.START },
                        onPreview = { startSound?.let { togglePreview(it) } }
                    )
                }

                item {
                    SoundSlotCard(
                        title = stringResource(R.string.patch_sound_success_title),
                        sound = successSound,
                        playing = previewing == successSound?.id,
                        onChoose = { choosing = SoundSlot.SUCCESS },
                        onPreview = { successSound?.let { togglePreview(it) } }
                    )
                }

                item {
                    SoundCard {
                        SettingLikeRow(
                            icon = Icons.Outlined.Add,
                            title = stringResource(R.string.patch_sound_add_custom),
                            desc = stringResource(R.string.patch_sound_add_custom_hint),
                            accent = YtpColors.Primary,
                            onClick = { pickFile.launch(arrayOf("audio/*")) }
                        )
                        if (removedCount > 0) {
                            SoundDivider()
                            SettingLikeRow(
                                icon = Icons.Outlined.Restore,
                                title = stringResource(R.string.patch_sound_restore_builtin, removedCount),
                                desc = null,
                                accent = YtpColors.Success,
                                onClick = {
                                    PatchSoundLibrary.restoreBuiltIns()
                                    refreshKey++
                                    scope.launch { snackbarHost.showSnackbar(context.getString(R.string.patch_sound_restored)) }
                                }
                            )
                        }
                    }
                }

                items(sounds, key = { it.id }) { sound ->
                    SoundRow(
                        sound = sound,
                        playing = previewing == sound.id,
                        onPreview = { togglePreview(sound) },
                        onDelete = { pendingDelete = sound }
                    )
                }
            }

            if (loading || staging) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = YtpColors.Primary
                )
            }
        }
    }

    // 选音源：把整个库列出来，每条都能先试听
    val choosingSlot = choosing
    if (choosingSlot != null) {
        val currentId = if (choosingSlot == SoundSlot.START) Configs.patchStartSound else Configs.patchSuccessSound
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = {
                Text(
                    stringResource(
                        if (choosingSlot == SoundSlot.START) R.string.patch_sound_start_title
                        else R.string.patch_sound_success_title
                    )
                )
            },
            text = {
                if (sounds.isEmpty()) {
                    Text(stringResource(R.string.patch_sound_empty))
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        sounds.forEach { sound ->
                            PickerRow(
                                sound = sound,
                                selected = sound.id == currentId,
                                playing = previewing == sound.id,
                                onSelect = {
                                    // 选择走 Configs，播放器要按新音源重新 prepare
                                    if (choosingSlot == SoundSlot.START) {
                                        Configs.patchStartSound = sound.id
                                    } else {
                                        Configs.patchSuccessSound = sound.id
                                    }
                                    PatchSounds.stopPreview()
                                    previewing = null
                                    PatchSounds.preload()
                                    choosing = null
                                },
                                onPreview = { togglePreview(sound) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = null }) {
                    Text(stringResource(R.string.patch_sound_cancel))
                }
            }
        )
    }

    // 删除：内置只是移出列表（可恢复），自定义会连文件一起删掉
    val deleting = pendingDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.patch_sound_delete)) },
            text = {
                Text(
                    stringResource(
                        if (deleting.builtIn) R.string.patch_sound_delete_builtin_message
                        else R.string.patch_sound_delete_custom_message
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        PatchSounds.stopPreview()
                        previewing = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (deleting.builtIn) PatchSoundLibrary.hide(deleting.id)
                                else PatchSoundLibrary.deleteCustom(deleting.id)
                            }
                            // 被删的正好是选中的那条时，库已经把选择退回默认了，这里同步给播放器
                            PatchSounds.preload()
                            refreshKey++
                            snackbarHost.showSnackbar(context.getString(R.string.patch_sound_deleted))
                        }
                    }
                ) {
                    Text(stringResource(R.string.patch_sound_delete), color = YtpColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.patch_sound_cancel))
                }
            }
        )
    }

    // 截取刚选的音频
    val trimming = staged
    if (trimming != null) {
        TrimDialog(
            staged = trimming,
            onDismiss = {
                staged = null
                PatchSounds.stopPreview()
                previewing = null
                scope.launch { withContext(Dispatchers.IO) { PatchSoundLibrary.discard(trimming) } }
            },
            onSave = { name, startMs, endMs ->
                staged = null
                PatchSounds.stopPreview()
                previewing = null
                scope.launch {
                    withContext(Dispatchers.IO) { PatchSoundLibrary.commit(trimming, name, startMs, endMs) }
                        .onSuccess {
                            refreshKey++
                            snackbarHost.showSnackbar(context.getString(R.string.patch_sound_added, it.name))
                        }
                        .onFailure {
                            snackbarHost.showSnackbar(context.getString(R.string.patch_sound_save_failed))
                        }
                }
            }
        )
    }
}

/** 卡片外框，跟设置页其它卡片保持一致。 */
@Composable
private fun SoundCard(content: @Composable ColumnScope.() -> Unit) {
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
private fun SoundDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 82.dp, end = 16.dp),
        thickness = 1.dp,
        color = YtpColors.Divider
    )
}

/** 左侧图标 + 标题/描述 + 右侧尾部控件，沿用设置页那一行的排版。 */
@Composable
private fun SettingLikeRow(
    icon: ImageVector,
    title: String,
    desc: String?,
    accent: Color,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
                .border(1.dp, YtpColors.IconButtonBorder, RoundedCornerShape(18.dp)),
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!desc.isNullOrBlank()) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = YtpColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (trailing != null) {
            trailing()
        }
    }
}

/** 一声提示音的选择卡片：当前用的是哪个 + 试听 + 换一个。 */
@Composable
private fun SoundSlotCard(
    title: String,
    sound: PatchSound?,
    playing: Boolean,
    onChoose: () -> Unit,
    onPreview: () -> Unit
) {
    SoundCard {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = YtpColors.TextPrimary
            )
            Text(
                text = sound?.name ?: stringResource(R.string.patch_sound_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = YtpColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onPreview, enabled = sound != null) {
                Icon(
                    imageVector = if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(
                        if (playing) R.string.patch_sound_stop else R.string.patch_sound_preview
                    ),
                    tint = YtpColors.iconForeground(YtpColors.Primary)
                )
            }
            Text(
                text = sound?.let { soundDescription(it) }.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = YtpColors.TextSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onChoose) {
                Text(stringResource(R.string.patch_sound_choose), color = YtpColors.ThemePrimary)
            }
        }
    }
}

/** 库里的一条提示音。 */
@Composable
private fun SoundRow(
    sound: PatchSound,
    playing: Boolean,
    onPreview: () -> Unit,
    onDelete: () -> Unit
) {
    SoundCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                YtpColors.iconBackground(YtpColors.AccentPurple, 0.12f),
                                YtpColors.iconBackground(YtpColors.AccentPurple, 0.06f)
                            )
                        )
                    )
                    .border(1.dp, YtpColors.IconButtonBorder, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = YtpColors.iconForeground(YtpColors.AccentPurple)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = sound.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = YtpColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = soundDescription(sound),
                    style = MaterialTheme.typography.labelMedium,
                    color = YtpColors.TextSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onPreview) {
                Icon(
                    imageVector = if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(
                        if (playing) R.string.patch_sound_stop else R.string.patch_sound_preview
                    ),
                    tint = YtpColors.iconForeground(YtpColors.Primary)
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.patch_sound_delete),
                    tint = YtpColors.iconForeground(YtpColors.Error)
                )
            }
        }
    }
}

/** 选音源弹窗里的一行：点一下选中，旁边的按钮单独试听。 */
@Composable
private fun PickerRow(
    sound: PatchSound,
    selected: Boolean,
    playing: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (selected) YtpColors.ThemePrimary
                    else YtpColors.iconBackground(YtpColors.Primary, 0.06f)
                )
                .border(1.dp, YtpColors.IconButtonBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = YtpColors.OnPrimary
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = sound.name,
                style = MaterialTheme.typography.bodyLarge,
                color = YtpColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = soundDescription(sound),
                style = MaterialTheme.typography.labelMedium,
                color = YtpColors.TextSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onPreview) {
            Icon(
                imageVector = if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                contentDescription = stringResource(
                    if (playing) R.string.patch_sound_stop else R.string.patch_sound_preview
                ),
                tint = YtpColors.iconForeground(YtpColors.Primary)
            )
        }
    }
}

/** 刚选的音频：起个名字，再截出要用的那一段。 */
@Composable
private fun TrimDialog(
    staged: PatchSoundLibrary.StagedSound,
    onDismiss: () -> Unit,
    onSave: (name: String, startMs: Int, endMs: Int) -> Unit
) {
    val duration = staged.durationMs
    var name by remember { mutableStateOf(staged.suggestedName) }
    var range by remember { mutableStateOf(0f..duration.toFloat()) }
    var playing by remember { mutableStateOf(false) }
    // 截取对话框里也有试听，同样先看音量（见 PatchSounds.volumeLevel）
    var volume by remember { mutableStateOf(PatchSounds.volumeLevel()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.patch_sound_add_custom)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.patch_sound_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (duration > 0) {
                    Text(
                        text = stringResource(
                            R.string.patch_sound_clip,
                            formatTime(range.start.toInt()),
                            formatTime(range.endInclusive.toInt())
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = YtpColors.TextSecondary
                    )
                    RangeSlider(
                        value = range,
                        onValueChange = { range = it },
                        valueRange = 0f..duration.toFloat()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                if (playing) {
                                    PatchSounds.stopPreview()
                                    playing = false
                                } else if (PatchSounds.volumeLevel() != PatchSounds.VolumeLevel.OK) {
                                    // 音量太小就别播了，给一句提示（对话框里没有 snackbar）
                                    volume = PatchSounds.volumeLevel()
                                } else {
                                    volume = PatchSounds.VolumeLevel.OK
                                    // 用当前的截取区间试听，选好再保存
                                    playing = true
                                    PatchSounds.preview(
                                        PatchSound(
                                            id = "preview",
                                            name = name,
                                            groupLabel = "",
                                            file = staged.file,
                                            durationMs = duration,
                                            startMs = range.start.toInt(),
                                            endMs = range.endInclusive.toInt(),
                                            builtIn = false
                                        )
                                    ) { playing = false }
                                }
                            }
                        ) {
                            Text(
                                text = stringResource(
                                    if (playing) R.string.patch_sound_stop else R.string.patch_sound_preview
                                ),
                                color = YtpColors.ThemePrimary
                            )
                        }
                        Text(
                            text = formatTime(duration),
                            style = MaterialTheme.typography.labelMedium,
                            color = YtpColors.TextSoft
                        )
                    }
                    if (volume != PatchSounds.VolumeLevel.OK) {
                        Text(
                            text = stringResource(
                                if (volume == PatchSounds.VolumeLevel.MUTED) {
                                    R.string.patch_sound_volume_muted
                                } else {
                                    R.string.patch_sound_volume_low
                                }
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = YtpColors.Warning
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.patch_sound_clip_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = YtpColors.TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, range.start.toInt(), range.endInclusive.toInt()) }) {
                Text(stringResource(R.string.patch_sound_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.patch_sound_cancel))
            }
        }
    )
}

/** 「分组 · 时长」，截取过的把区间也写出来。 */
@Composable
private fun soundDescription(sound: PatchSound): String {
    val duration = if (sound.durationMs > 0) formatTime(sound.durationMs) else "--"
    val clip = if (sound.isTrimmed) {
        " · " + stringResource(R.string.patch_sound_clip, formatTime(sound.fromMs), formatTime(sound.toMs))
    } else {
        ""
    }
    return sound.groupLabel + " · " + duration + clip
}

/** 毫秒 -> 「0.9s」（不足一分钟）或「1:21」。 */
private fun formatTime(ms: Int): String {
    val safe = ms.coerceAtLeast(0)
    if (safe < 60_000) return "%.1fs".format(safe / 1000.0)
    val seconds = safe / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
