/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.component

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.ytp.ui.theme.YtpColors

@Composable
fun AppItem(
    modifier: Modifier = Modifier,
    icon: ImageBitmap,
    label: String,
    packageName: String,
    checked: Boolean? = null,
    versionName: String? = null,
    description: String? = null,
    targetApiVersion: String? = null,
    disabled: Boolean = false,
    rightIcon: (@Composable () -> Unit)? = null,
    additionalContent: (@Composable ColumnScope.() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    secondaryTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    versionContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    versionContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    require(checked == null || rightIcon == null) {
        "`checked` and `rightIcon` should not be both set"
    }

    YtpCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PageHorizontalPadding, vertical = 5.dp)
            .alpha(if (disabled) 0.55f else 1f),
        containerColor = containerColor,
        borderColor = borderColor
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                YtpAppIcon(icon, label)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = titleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!versionName.isNullOrBlank()) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = versionContainerColor,
                                contentColor = versionContentColor
                            ) {
                                Text(
                                    text = versionName,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Text(
                        text = packageName,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!targetApiVersion.isNullOrBlank()) {
                        Text(
                            text = "API $targetApiVersion",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryColor
                        )
                    }
                    additionalContent?.invoke(this)
                }
                when {
                    checked != null -> Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(
                            checkedColor = primaryColor,
                            // Material3 当前版本会用 uncheckedColor 绘制未选中框，不能设为透明。
                            uncheckedColor = primaryColor.copy(alpha = 0.12f),
                            checkmarkColor = YtpColors.OnPrimary
                        )
                    )
                    rightIcon != null -> rightIcon()
                }
            }
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = secondaryTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Preview
@Composable
private fun AppItemPreview() {
    _root_ide_package_.org.ytp.ui.theme.LSPTheme {
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(MaterialTheme.colorScheme.primary.toArgb())
        }
        AppItem(
            icon = shape.toBitmap().asImageBitmap(),
            label = "Sample App",
            packageName = "org.ytp.sample",
            versionName = "2.0.0",
            rightIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null) }
        )
    }
}
