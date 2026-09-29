/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.component

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

object SelectionColumnScope {

    @Composable
    fun SelectionItem(
        modifier: Modifier = Modifier,
        selected: Boolean,
        onClick: () -> Unit,
        icon: ImageVector,
        title: String = "",
        desc: String? = null,
        extraContent: (@Composable ColumnScope.() -> Unit)? = null
    ) {
        _root_ide_package_.org.ytp.ui.component.YtpCard(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable { onClick() }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                _root_ide_package_.org.ytp.ui.component.YtpIconContainer(icon)
                Column(modifier = Modifier.weight(1f)) {
                    if (title.isNotBlank()) {
                        Text(text = title, style = MaterialTheme.typography.titleMedium)
                    }
                if (desc != null || extraContent != null) {
                    AnimatedVisibility(
                        visible = selected,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                    ) {
                        Column {
                            if (desc != null) {
                                Text(
                                    text = desc,
                                    modifier = Modifier.padding(top = if (title.isBlank()) 0.dp else 6.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            extraContent?.invoke(this)
                        }
                    }
                }
                }
                Surface(
                    modifier = Modifier.size(20.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ) {}
            }
        }
    }
}


@Composable
fun SelectionColumn(
    modifier: Modifier = Modifier,
    content: @Composable() (SelectionColumnScope.() -> Unit)
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = { SelectionColumnScope.content() }
    )
}
