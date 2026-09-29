/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.ytp.model.UpdateInfo
import org.ytp.ui.theme.YtpColors
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onDownload: () -> Unit
) {
    // 根据系统语言选择显示中文或英文
    val content = if (java.util.Locale.getDefault().language.lowercase().contains("zh")) {
        updateInfo.zh
    } else {
        updateInfo.en
    }
    
    val scrimInteractionSource = remember { MutableInteractionSource() }
    val contentInteractionSource = remember { MutableInteractionSource() }
    val dismissIfAllowed = {
        if (!updateInfo.force) {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = dismissIfAllowed,
        properties = DialogProperties(
            dismissOnClickOutside = !updateInfo.force,
            dismissOnBackPress = !updateInfo.force,
            usePlatformDefaultWidth = false
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = scrimInteractionSource,
                    indication = null,
                    onClick = dismissIfAllowed
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 420.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .clickable(
                        interactionSource = contentInteractionSource,
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(28.dp),
                color = YtpColors.Surface,
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = YtpColors.iconForeground(YtpColors.Primary)
                        )
                        Text(
                            text = content.title,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold,
                            color = YtpColors.TextPrimary
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = content.msg,
                            style = MaterialTheme.typography.bodyLarge,
                            color = YtpColors.TextPrimary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!updateInfo.force) {
                            TextButton(onClick = onCancel) {
                                Text(content.cancel)
                            }
                        }
                        Button(onClick = onDownload) {
                            Text(content.confirm)
                        }
                    }
                }
            }
        }
    }
}
