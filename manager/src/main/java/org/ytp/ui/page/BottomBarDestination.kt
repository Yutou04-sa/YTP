/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.page

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.ramcosta.composedestinations.spec.DirectionDestinationSpec
import org.ytp.R
import org.ytp.ui.page.destinations.HomeScreenDestination
import org.ytp.ui.page.destinations.RepoScreenDestination
import org.ytp.ui.page.destinations.SettingsScreenDestination

enum class BottomBarDestination(
    val direction: DirectionDestinationSpec,
    @param:StringRes val label: Int,
    val iconSelected: ImageVector,
    val iconNotSelected: ImageVector,
    val showInNavbar: Boolean = true
) {
    Home(HomeScreenDestination, R.string.app_name, Icons.Filled.Home, Icons.Outlined.Home),
    Repo(RepoScreenDestination, R.string.screen_repo, Icons.Filled.GetApp, Icons.Outlined.GetApp),
    Settings(SettingsScreenDestination, R.string.screen_settings, Icons.Filled.Settings, Icons.Outlined.Settings);
    
    companion object {
        fun navbarItems(): List<BottomBarDestination> = entries.filter { it.showInNavbar }
    }
}
