/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class SelectAppsViewModel : ViewModel() {

    companion object {
        private const val TAG = "SelectAppViewModel"
    }

    init {
        Log.d(TAG, "SelectAppsViewModel ${toString().substringAfterLast('@')} construct")
    }

    var isRefreshing by mutableStateOf(false)
        private set

    var filteredList by mutableStateOf(listOf<org.ytp.util.LSPPackageManager.AppInfo>())
        private set

    val multiSelected = mutableStateListOf<org.ytp.util.LSPPackageManager.AppInfo>()

    fun filterAppList(refresh: Boolean, filter: (org.ytp.util.LSPPackageManager.AppInfo) -> Boolean, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            if (_root_ide_package_.org.ytp.util.LSPPackageManager.appList.isEmpty() || refresh) {
                isRefreshing = true
                _root_ide_package_.org.ytp.util.LSPPackageManager.fetchAppList()
                isRefreshing = false
            }
            filteredList = _root_ide_package_.org.ytp.util.LSPPackageManager.appList.filter(filter)
            Log.d(TAG, "Filtered ${filteredList.size} apps")
            onComplete?.invoke()
        }
    }
}