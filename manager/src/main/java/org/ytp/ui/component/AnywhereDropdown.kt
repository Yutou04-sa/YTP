/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnywhereDropdown(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    surface: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val indication = LocalIndication.current
    val interactionSource = remember { MutableInteractionSource() }
    val state by interactionSource.interactions.collectAsState(null)
    var offset by remember { mutableStateOf(Offset.Zero) }
    val dpOffset = with(LocalDensity.current) {
        DpOffset(offset.x.toDp(), offset.y.toDp())
    }

    LaunchedEffect(state) {
        if (state is PressInteraction.Press) {
            val i = state as PressInteraction.Press
            offset = i.pressPosition
        }
        if (state is PressInteraction.Release) {
            val i = state as PressInteraction.Release
            offset = i.press.pressPosition
        }
    }

    Box(
        modifier = modifier
            .combinedClickable(
                interactionSource = interactionSource,
                indication = indication,
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        surface()
        Box {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = onDismissRequest,
                offset = dpOffset,
                content = content
            )
        }
    }
}
