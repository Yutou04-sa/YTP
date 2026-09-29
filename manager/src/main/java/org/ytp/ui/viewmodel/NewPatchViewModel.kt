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
import org.ytp.Patcher
import org.ytp.patch.util.Logger
import org.ytp.share.YTPConfig
import org.ytp.share.PatchConfig
import org.ytp.util.LSPPackageManager

class NewPatchViewModel : ViewModel() {

    companion object {
        private const val TAG = "NewPatchViewModel"

        private const val LOG_PROCESS = "LOG_PROCESS"
    }

    enum class PatchState {
        INIT, SELECTING, CONFIGURING, PATCHING, FINISHED, ERROR
    }

    enum class InstallMethod {
        SYSTEM, SHIZUKU
    }

    sealed class ViewAction {
        object DoneInit : ViewAction()
        data class ConfigurePatch(val app: LSPPackageManager.AppInfo) : ViewAction()
        object SubmitPatch : ViewAction()
        object LaunchPatch : ViewAction()
    }

    var patchState by mutableStateOf(PatchState.INIT)
        private set

    var packageName by mutableStateOf("")

    /** 修补时写进 manifest 的新包名，留空表示不改。 */
    var newPackageName by mutableStateOf("")

    /** 修补时写进 manifest 的应用名，留空表示不改。 */
    var appLabel by mutableStateOf("")

    var embeddedModules by mutableStateOf<List<LSPPackageManager.AppInfo>>(emptyList())
        public set

    lateinit var patchApp: LSPPackageManager.AppInfo
        private set
    lateinit var patchOptions: Patcher.Options
        private set
    val logs = mutableStateListOf<Pair<Int, String>>()
    private val logger = object : Logger() {
        override fun d(msg: String) {
            if (verbose) {
                Log.d(TAG, msg)
                if (msg.startsWith(LOG_PROCESS)) {
                    val newMsg = msg.replace(LOG_PROCESS,"")
                    val index = logs.indexOfLast { it.second.startsWith(newMsg.substringBefore(":")) }
                    if (index != -1) {
                        logs[index] = Log.DEBUG to newMsg+"\n"
                    }
                    else{
                        logs += Log.DEBUG to newMsg+"\n"
                    }
                    return
                }
                logs += Log.DEBUG to msg+"\n"
            }
        }

        override fun i(msg: String) {
            Log.i(TAG, msg)
            logs += Log.INFO to msg+"\n"
        }

        override fun e(msg: String) {
            Log.e(TAG, msg)
            logs += Log.ERROR to msg+"\n"
        }
    }

    fun dispatch(action: ViewAction) {
        viewModelScope.launch {
            when (action) {
                is ViewAction.DoneInit -> doneInit()
                is ViewAction.ConfigurePatch -> configurePatch(action.app)
                is ViewAction.SubmitPatch -> submitPatch()
                is ViewAction.LaunchPatch -> launchPatch()
            }
        }
    }

    private fun doneInit() {
        patchState = PatchState.SELECTING
    }

    private fun configurePatch(app: LSPPackageManager.AppInfo) {
        Log.d(TAG, "Configuring patch for ${app.app.packageName}")
        patchApp = app
        patchState = PatchState.CONFIGURING
        packageName = app.app.packageName
        newPackageName = ""
        appLabel = ""
    }

    private fun submitPatch() {
        Log.d(TAG, "Submit Patch")
        val config = PatchConfig(null, packageName, newPackageName.trim(), appLabel.trim())
        patchOptions = Patcher.Options(
            config = config,
            apkPaths = listOf(patchApp.app.sourceDir) + (patchApp.app.splitSourceDirs ?: emptyArray()),
            embeddedModules = embeddedModules.flatMap { listOf(it.app.sourceDir) + (it.app.splitSourceDirs ?: emptyArray()) }
        )
        patchState = PatchState.PATCHING
    }


    private suspend fun launchPatch() {
        logger.i("Launch Patch VersionCode:"+ YTPConfig.instance.VERSION_CODE)
        patchState = try {
            Patcher.patch(logger, patchOptions)
            PatchState.FINISHED
        } catch (t: Throwable) {
            logger.e(t.message.orEmpty())
            logger.e(t.stackTraceToString())
            PatchState.ERROR
        } finally {
            LSPPackageManager.cleanTmpApkDir()
        }
    }
}