/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.ytp.ui.theme.YtpColors

@Composable
fun LoadingDialog() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier.size(88.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = YtpColors.Surface,
            tonalElevation = 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
            }
        }
    }
}

@Preview
@Composable
private fun LoadingDialogPreview() {
    _root_ide_package_.org.ytp.ui.theme.LSPTheme { LoadingDialog() }
}
