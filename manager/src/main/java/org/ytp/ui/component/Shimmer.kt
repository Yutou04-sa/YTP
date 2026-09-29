/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.ytp.ui.theme.YtpColors

private val ShimmerColorShades
    @Composable get() = listOf(
        if (YtpColors.IsMonochromeTheme) YtpColors.ShimmerBase
        else MaterialTheme.colorScheme.secondaryContainer.copy(0.9f),
        if (YtpColors.IsMonochromeTheme) YtpColors.ShimmerHighlight
        else MaterialTheme.colorScheme.secondaryContainer.copy(0.2f),
        if (YtpColors.IsMonochromeTheme) YtpColors.ShimmerBase
        else MaterialTheme.colorScheme.secondaryContainer.copy(0.9f)
    )

class ShimmerScope(val brush: Brush)

@Composable
fun ShimmerAnimation(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ShimmerScope.() -> Unit
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            RepeatMode.Restart
        ),
        label = "shimmer-offset"
    )

    val brush = Brush.linearGradient(
        colors = if (enabled) ShimmerColorShades else List(3) { ShimmerColorShades[0] },
        start = Offset(10f, 10f),
        end = Offset(translateAnim, translateAnim)
    )

    Box(modifier = modifier) {
        content(ShimmerScope(brush))
    }
}

@Preview
@Composable
private fun ShimmerPreview() {
    ShimmerAnimation {
        Column(modifier = Modifier.padding(16.dp)) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .size(250.dp)
                    .background(brush = brush)
            )
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .padding(vertical = 8.dp)
                    .background(brush = brush)
            )
        }
    }
}
