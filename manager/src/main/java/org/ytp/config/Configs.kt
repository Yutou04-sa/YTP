/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.config

import org.ytp.ui.util.getValue
import org.ytp.ui.util.setValue

object Configs {

    private const val PREFS_KEYSTORE_NAME= "keystore_name"
    private const val PREFS_KEYSTORE_PASSWORD = "keystore_password"
    private const val PREFS_KEYSTORE_ALIAS = "keystore_alias"
    private const val PREFS_KEYSTORE_ALIAS_PASSWORD = "keystore_alias_password"
    private const val PREFS_STORAGE_DIRECTORY = "storage_directory"
    private const val PREFS_DETAIL_PATCH_LOGS = "detail_patch_logs"
    private const val PREFS_PATCH_SOUNDS = "patch_sounds"
    private const val PREFS_PATCH_SOUNDS_RANDOM = "patch_sounds_random"
    private const val PREFS_PATCH_SOUND_START = "patch_sound_start"
    private const val PREFS_PATCH_SOUND_SUCCESS = "patch_sound_success"
    private const val PREFS_PATCH_SOUNDS_REMOVED = "patch_sounds_removed"
    private const val PREFS_PATCH_SOUNDS_CUSTOM = "patch_sounds_custom"
    private const val PREFS_FORCE_INSTALL = "force_install"
    private const val PREFS_PRIMARY_COLOR = "primary_color"
    private const val PREFS_THEME_VERSION = "theme_version"
    private const val PREFS_BACKGROUND_IMAGE = "background_image"
    private const val PREFS_SURFACE_ALPHA = "surface_alpha"

    /** 初音未来主题的默认主色（初音青）。 */
    const val DefaultPrimaryColor = 0xFF39C5BB

    /** 卡片等前景表面的默认不透明度（仅在设置了自定义背景图时生效）。 */
    const val DefaultSurfaceAlpha = 0.8f

    /** 不透明度滑块的下限，再低文字就没法和底图区分了。 */
    const val MinSurfaceAlpha = 0.2f

    /** 配色方案版本，用来把旧版的蓝色主题升级到初音主题。 */
    private const val THEME_VERSION = 2

    /** 默认提示音的 id：本仓库自带的两个很短的采样（`res/raw/patch_start|success.wav`）。 */
    const val DefaultStartSoundId = "default/start"
    const val DefaultSuccessSoundId = "default/success"

    private const val UPDATE_NEVER = "update_never"

    var keyStorePassword by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getString(
            PREFS_KEYSTORE_PASSWORD,
            org.ytp.share.Constants.KEY_STORE_PASSWORD
        )!!
    ) {
        org.ytp.lspApp.prefs.edit().putString(PREFS_KEYSTORE_PASSWORD, it)
            .apply()
    }

    var keyStoreAlias by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getString(
            PREFS_KEYSTORE_ALIAS,
            org.ytp.share.Constants.KEY_STORE_ALIAS
        )!!
    ) {
        org.ytp.lspApp.prefs.edit().putString(PREFS_KEYSTORE_ALIAS, it).apply()
    }

    var keyStoreAliasPassword by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getString(PREFS_KEYSTORE_ALIAS_PASSWORD, org.ytp.share.Constants.KEY_STORE_ALIAS_PASSWORD)!!
    ) {
        org.ytp.lspApp.prefs.edit().putString(PREFS_KEYSTORE_ALIAS_PASSWORD, it)
            .apply()
    }

    var storageDirectory by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getString(
            PREFS_STORAGE_DIRECTORY,
            null
        )
    ) {
        org.ytp.lspApp.prefs.edit().putString(PREFS_STORAGE_DIRECTORY, it)
            .apply()
    }

    var detailPatchLogs by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getBoolean(
            PREFS_DETAIL_PATCH_LOGS,
            true
        )
    ) {
        org.ytp.lspApp.prefs.edit().putBoolean(PREFS_DETAIL_PATCH_LOGS, it)
            .apply()
    }

    /**
     * 修补提示音开关（开始修补 / 修补完成各一声）。
     *
     * 默认开启，设置页里可以关掉。
     */
    var patchSounds by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getBoolean(
            PREFS_PATCH_SOUNDS,
            true
        )
    ) {
        org.ytp.lspApp.prefs.edit().putBoolean(PREFS_PATCH_SOUNDS, it)
            .apply()
    }

    /**
     * 随机播放修补提示音。
     *
     * 打开后每次修补都从全部可用提示音里随机挑，[patchStartSound] / [patchSuccessSound] 只在关闭随机时生效。
     */
    var patchSoundsRandom by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getBoolean(
            PREFS_PATCH_SOUNDS_RANDOM,
            false
        )
    ) {
        org.ytp.lspApp.prefs.edit().putBoolean(PREFS_PATCH_SOUNDS_RANDOM, it)
            .apply()
    }

    /** 修补开始提示音的 id（见 `org.ytp.util.PatchSoundLibrary`），默认是内置的那一声。 */
    var patchStartSound by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getString(PREFS_PATCH_SOUND_START, DefaultStartSoundId)!!
    ) {
        org.ytp.lspApp.prefs.edit().putString(PREFS_PATCH_SOUND_START, it).apply()
    }

    /** 修补成功提示音的 id。 */
    var patchSuccessSound by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getString(PREFS_PATCH_SOUND_SUCCESS, DefaultSuccessSoundId)!!
    ) {
        org.ytp.lspApp.prefs.edit().putString(PREFS_PATCH_SOUND_SUCCESS, it).apply()
    }

    /**
     * 被用户删掉的内置提示音的 id，用逗号分隔。
     *
     * 内置素材打在包里，删不掉文件本身，所以只在这里记一笔；清空它就等于恢复全部内置提示音。
     */
    var removedPatchSounds by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getString(PREFS_PATCH_SOUNDS_REMOVED, "")!!
    ) {
        org.ytp.lspApp.prefs.edit().putString(PREFS_PATCH_SOUNDS_REMOVED, it).apply()
    }

    /** 用户自己添加的提示音，JSON 数组，见 `org.ytp.util.PatchSoundLibrary`。 */
    var customPatchSounds by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getString(PREFS_PATCH_SOUNDS_CUSTOM, "[]")!!
    ) {
        org.ytp.lspApp.prefs.edit().putString(PREFS_PATCH_SOUNDS_CUSTOM, it).apply()
    }

    /**
     * 强制安装：安装补丁产物时如果设备上已装同名应用且签名不一致，先卸载它再安装，不再逐次询问。
     *
     * 默认关闭，此时安装前会弹一次「签名不一致」的确认框，由用户当场决定。
     */
    var forceInstall by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getBoolean(PREFS_FORCE_INSTALL, false)
    ) {
        org.ytp.lspApp.prefs.edit().putBoolean(PREFS_FORCE_INSTALL, it).apply()
    }

    var primaryColor by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getLong(PREFS_PRIMARY_COLOR, DefaultPrimaryColor)
    ) {
        org.ytp.lspApp.prefs.edit().putLong(PREFS_PRIMARY_COLOR, it).apply()
    }

    var keyStoreName by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getString(
            PREFS_KEYSTORE_NAME,
            org.ytp.share.Constants.KEY_STORE_ALIAS
        )!!
    ) {
        org.ytp.lspApp.prefs.edit().putString(PREFS_KEYSTORE_NAME, it)
            .apply()
    }

    /**
     * 自定义页面背景图在应用私有目录里的绝对路径，空字符串表示使用默认纯色背景。
     *
     * 每次替换都会换一个新文件名，这样路径本身的变化就能触发界面刷新。
     */
    var backgroundImage by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getString(PREFS_BACKGROUND_IMAGE, "")!!
    ) {
        org.ytp.lspApp.prefs.edit().putString(PREFS_BACKGROUND_IMAGE, it).apply()
    }

    /** 应用一张新的背景图，并删掉上一张留下的文件。 */
    fun applyBackgroundImage(path: String) {
        val previous = backgroundImage
        backgroundImage = path
        if (previous.isNotEmpty() && previous != path) {
            runCatching { java.io.File(previous).delete() }
        }
    }

    /** 恢复默认背景，并删掉自定义背景图文件。 */
    fun clearBackgroundImage() {
        val previous = backgroundImage
        backgroundImage = ""
        if (previous.isNotEmpty()) {
            runCatching { java.io.File(previous).delete() }
        }
    }

    /**
     * 卡片等前景表面的不透明度，只有设置了自定义背景图时才会被用上。
     *
     * 1.0 = 卡片完全不透明（底图只在卡片之间露出来）；调小后卡片半透明、底图会透出来。
     * 取值下限见 [MinSurfaceAlpha]。
     */
    var surfaceAlpha by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getFloat(PREFS_SURFACE_ALPHA, DefaultSurfaceAlpha)
    ) {
        org.ytp.lspApp.prefs.edit().putFloat(PREFS_SURFACE_ALPHA, it).apply()
    }

    var updateNever by org.ytp.ui.util.delegateStateOf(
        org.ytp.lspApp.prefs.getInt(
            UPDATE_NEVER,
            0
        )!!
    ) {
        org.ytp.lspApp.prefs.edit().putInt(UPDATE_NEVER, it)
            .apply()
    }

    /**
     * 旧版本升级到初音主题时统一重置一次主色。
     *
     * 只对从未迁移过的安装生效，之后调色盘里的选择都会被保留。
     * 需要在读取 [primaryColor] 之前调用。
     */
    fun migrateTheme() {
        val prefs = org.ytp.lspApp.prefs
        if (prefs.getInt(PREFS_THEME_VERSION, 0) >= THEME_VERSION) return
        prefs.edit()
            .putLong(PREFS_PRIMARY_COLOR, DefaultPrimaryColor)
            .putInt(PREFS_THEME_VERSION, THEME_VERSION)
            .apply()
    }
}
