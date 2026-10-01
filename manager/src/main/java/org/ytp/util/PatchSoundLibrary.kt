/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.util

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.ytp.R
import org.ytp.config.Configs
import org.ytp.lspApp
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 一条可供修补流程播放的提示音。
 *
 * [startMs] / [endMs] 是自定义截取出来的播放区间，[endMs] 为 0 表示一直播到文件结尾；
 * 内置素材与默认的两声都不截取。
 */
data class PatchSound(
    val id: String,
    val name: String,
    val groupLabel: String,
    val file: File,
    val durationMs: Int,
    val startMs: Int = 0,
    val endMs: Int = 0,
    val builtIn: Boolean = true
) {
    /** 实际播放起点（毫秒）。 */
    val fromMs: Int get() = startMs.coerceAtLeast(0)

    /** 实际播放终点（毫秒），0 表示未知（那就播到结束）。 */
    val toMs: Int get() = if (endMs > 0) endMs else durationMs

    /** 是否被截取过（界面上要标出来）。 */
    val isTrimmed: Boolean
        get() = fromMs > 0 || (toMs > 0 && durationMs > 0 && toMs < durationMs)

    fun exists(): Boolean = file.isFile
}

/**
 * 提示音库。
 *
 * 三类提示音：
 *  - 默认：本仓库自带的两声很短的自造采样（`res/raw/patch_start|success.wav`），首启动时复制到私有目录；
 *  - 内置：从别处提取的音效，打包在 `assets/ytp-sounds/<包>/NN.mp3`（见 `manager/sounds`，不进仓库、
 *    只在本地构建时打进包里），同样会复制到私有目录，方便试听与删除；
 *  - 自定义：用户自己选的音频文件，可以截取其中一段。
 *
 * 内置素材删不掉（在包里），所以「删除」只把 id 记进 [Configs.removedPatchSounds]；清空它就恢复全部。
 */
object PatchSoundLibrary {

    private const val TAG = "PatchSoundLibrary"

    /** assets 里的根目录；本地构建才有内容，仓库/CI 构建下为空。 */
    private const val ASSETS_ROOT = "ytp-sounds"

    private const val PREFS_SYNC_SIGNATURE = "patch_sounds_signature"

    /** assets 目录名 -> 库里的分组名；不写中文，纯展示用。 */
    private val GROUP_LABELS = linkedMapOf(
        "xiujin" to "狗修金",
        "ciallo" to "Ciallo~",
        "omg" to "OMG",
        "gugugaga" to "咕咕嘎嘎"
    )

    private val BUILT_IN_PREFIX = "bundled/"
    private val CUSTOM_PREFIX = "custom/"

    private fun rootDir(): File = File(lspApp.filesDir, "sounds")

    private fun defaultDir(): File = File(rootDir(), "default")

    private fun bundledDir(): File = File(rootDir(), "bundled")

    private fun customDir(): File = File(rootDir(), "custom")

    private fun durationCacheFile(): File = File(rootDir(), "durations.json")

    private val durationCache = mutableMapOf<String, Int>()

    /** 每次启动（或重新同步后）都要重读一遍，避免缓存和磁盘对不上。 */
    @Volatile
    private var cacheLoaded = false

    // ---------------------------------------------------------------- 同步素材

    /**
     * 把资源里的提示音复制到私有目录（首启动或素材变化时）。
     *
     * 用「文件数 + 总字节」当签名：重建 APK 后素材变了会自动重新复制，不依赖版本号。
     */
    @Synchronized
    fun sync() {
        val signature = assetSignature()
        val synced = lspApp.prefs.getString(PREFS_SYNC_SIGNATURE, "")
        // 默认的两声是资源里的，缺了就得重来（用户清过数据、或只是删了文件）
        val missingDefaults = DEFAULT_FILES.any { !File(defaultDir(), it).isFile }
        if (signature == synced && !missingDefaults) return

        runCatching {
            copyRawDefault()
            copyBundledAssets()
            rescanDurations()
            lspApp.prefs.edit().putString(PREFS_SYNC_SIGNATURE, signature).apply()
            Log.i(TAG, "Prompt sounds synced ($signature)")
        }.onFailure { Log.w(TAG, "Could not sync the prompt sounds", it) }
    }

    private val DEFAULT_FILES = listOf("patch_start.wav", "patch_success.wav")

    /** `assets/ytp-sounds` 的「目录数 + 文件数 + 总字节」，用来判断素材有没有变。 */
    private fun assetSignature(): String {
        var files = 0
        var total = 0L
        for (group in listAssetGroups()) {
            for (name in listAssetFiles(group)) {
                files++
                total += assetSize(group, name)
            }
        }
        return "$files-$total"
    }

    private fun listAssetGroups(): List<String> =
        runCatching { lspApp.assets.list(ASSETS_ROOT)?.toList().orEmpty() }.getOrDefault(emptyList())

    private fun listAssetFiles(group: String): List<String> =
        runCatching { lspApp.assets.list("$ASSETS_ROOT/$group")?.toList().orEmpty() }
            .getOrDefault(emptyList())

    private fun assetSize(group: String, name: String): Long =
        runCatching {
            lspApp.assets.open("$ASSETS_ROOT/$group/$name").use { input ->
                var count = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    count += read
                }
                count
            }
        }.getOrDefault(0L)

    private fun copyRawDefault() {
        val dir = defaultDir().apply { mkdirs() }
        val resources = listOf(R.raw.patch_start to "patch_start.wav", R.raw.patch_success to "patch_success.wav")
        resources.forEach { (resId, name) ->
            val target = File(dir, name)
            // 只覆盖「缺失」的那一声：用户改过默认文件的概率极低，没必要每次启动都重写。
            if (target.isFile && target.length() > 0) return@forEach
            runCatching {
                lspApp.resources.openRawResource(resId).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { Log.w(TAG, "Could not copy $name", it) }
        }
    }

    private fun copyBundledAssets() {
        val dir = bundledDir()
        for (group in listAssetGroups()) {
            val target = File(dir, group).apply { mkdirs() }
            for (name in listAssetFiles(group)) {
                val file = File(target, name)
                if (file.isFile && file.length() > 0) continue
                runCatching {
                    lspApp.assets.open("$ASSETS_ROOT/$group/$name").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }.onFailure { Log.w(TAG, "Could not copy $group/$name", it) }
            }
        }
    }

    // ---------------------------------------------------------------- 时长缓存

    /**
     * 读一遍所有提示音的时长并记到缓存里。
     *
     * [MediaMetadataRetriever] 每个文件都要新建一次，96 个素材要一秒多，所以只在同步时扫一遍，
     * 结果落到 `filesDir/sounds/durations.json`。
     */
    private fun rescanDurations() {
        val cache = JSONObject()
        val files = mutableListOf<File>()
        files.addAll(defaultDir().listFiles().orEmpty().filter { it.isFile })
        files.addAll(bundledDir().walkTopDown().filter { it.isFile })
        files.forEach { file ->
            val duration = readDuration(file)
            if (duration > 0) {
                cache.put(file.absolutePath, duration)
                durationCache[file.absolutePath] = duration
            }
        }
        customSounds().forEach { sound ->
            val duration = readDuration(sound.file)
            if (duration > 0) {
                cache.put(sound.file.absolutePath, duration)
                durationCache[sound.file.absolutePath] = duration
            }
        }
        cacheLoaded = true
        runCatching {
            durationCacheFile().parentFile?.mkdirs()
            durationCacheFile().writeText(cache.toString())
        }.onFailure { Log.w(TAG, "Could not store the sound durations", it) }
    }

    private fun loadDurationCache() {
        if (cacheLoaded) return
        cacheLoaded = true
        runCatching {
            val text = durationCacheFile().takeIf { it.isFile }?.readText().orEmpty()
            if (text.isEmpty()) return@runCatching
            val json = JSONObject(text)
            json.keys().forEach { key ->
                val value = json.optInt(key, 0)
                if (value > 0) durationCache[key] = value
            }
        }.onFailure { Log.w(TAG, "Could not read the sound durations", it) }
    }

    private fun readDuration(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
        } catch (t: Throwable) {
            Log.d(TAG, "No duration for ${file.name}: $t")
            0
        } finally {
            // release() 在 API 29 之后被 close() 取代，但 minSdk 28 上只有 release()。
            runCatching { retriever.release() }
        }
    }

    private fun durationOf(file: File): Int {
        loadDurationCache()
        return durationCache[file.absolutePath] ?: readDuration(file).also {
            if (it > 0) {
                durationCache[file.absolutePath] = it
                runCatching {
                    val cache = JSONObject(durationCacheFile().takeIf { f -> f.isFile }?.readText().orEmpty().ifEmpty { "{}" })
                    cache.put(file.absolutePath, it)
                    durationCacheFile().writeText(cache.toString())
                }.onFailure { Log.d(TAG, "Could not cache the duration of ${file.name}", it) }
            }
        }
    }

    // ---------------------------------------------------------------- 列表

    /** 默认的两声（`res/raw`，已复制到私有目录）。 */
    fun defaultSounds(): List<PatchSound> {
        val dir = defaultDir()
        val start = File(dir, "patch_start.wav")
        val success = File(dir, "patch_success.wav")
        return listOf(
            PatchSound(
                id = Configs.DefaultStartSoundId,
                name = lspApp.getString(R.string.patch_sound_default_start),
                groupLabel = lspApp.getString(R.string.patch_sound_group_default),
                file = start,
                durationMs = durationOf(start)
            ),
            PatchSound(
                id = Configs.DefaultSuccessSoundId,
                name = lspApp.getString(R.string.patch_sound_default_success),
                groupLabel = lspApp.getString(R.string.patch_sound_group_default),
                file = success,
                durationMs = durationOf(success)
            )
        )
    }

    /** 内置素材（`assets/ytp-sounds/<分组>/NN.mp3`，本地构建才有）。 */
    fun bundledSounds(): List<PatchSound> {
        val dir = bundledDir()
        if (!dir.isDirectory) return emptyList()
        val result = mutableListOf<PatchSound>()
        GROUP_LABELS.keys.forEach { group ->
            val groupDir = File(dir, group)
            if (!groupDir.isDirectory) return@forEach
            val label = GROUP_LABELS.getValue(group)
            groupDir.listFiles().orEmpty()
                .filter { it.isFile }
                .sortedBy { it.name }
                .forEach { file ->
                    result.add(
                        PatchSound(
                            id = BUILT_IN_PREFIX + group + "/" + file.nameWithoutExtension,
                            name = label + " " + file.nameWithoutExtension,
                            groupLabel = lspApp.getString(R.string.patch_sound_group_builtin) + " · " + label,
                            file = file,
                            durationMs = durationOf(file)
                        )
                    )
                }
        }
        return result
    }

    /** 用户自己添加的提示音。 */
    fun customSounds(): List<PatchSound> {
        val text = Configs.customPatchSounds
        if (text.isBlank()) return emptyList()
        val soundGroup = lspApp.getString(R.string.patch_sound_group_custom)
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val path = item.optString("path")
                if (path.isEmpty()) return@mapNotNull null
                val file = File(path)
                if (!file.isFile) return@mapNotNull null
                val duration = item.optInt("duration", 0).takeIf { it > 0 } ?: durationOf(file)
                PatchSound(
                    id = item.optString("id"),
                    name = item.optString("name").ifBlank { file.nameWithoutExtension },
                    groupLabel = soundGroup,
                    file = file,
                    durationMs = duration,
                    startMs = item.optInt("start", 0),
                    endMs = item.optInt("end", 0),
                    builtIn = false
                )
            }
        }.onFailure { Log.w(TAG, "Could not read the custom prompt sounds", it) }.getOrDefault(emptyList())
    }

    /** 被用户删掉的内置提示音的 id。 */
    fun removedIds(): Set<String> =
        Configs.removedPatchSounds.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** 库里所有还能用的提示音：默认 + 内置（未删除）+ 自定义。 */
    fun allSounds(): List<PatchSound> {
        val removed = removedIds()
        return defaultSounds() + bundledSounds().filterNot { removed.contains(it.id) } + customSounds()
    }

    /** 按 id 找一条提示音，找不到（被删了、文件没了）返回 null。 */
    fun find(id: String): PatchSound? = allSounds().firstOrNull { it.id == id }

    /** 找不到就退回默认那一类，保证修补流程永远有声音可选。 */
    fun findOrDefault(id: String, isStart: Boolean): PatchSound? {
        val sound = find(id)
        if (sound != null) return sound
        val fallback = if (isStart) Configs.DefaultStartSoundId else Configs.DefaultSuccessSoundId
        return allSounds().firstOrNull { it.id == fallback }
            ?: defaultSounds().firstOrNull { it.id == fallback }
    }

    /** 删掉一条内置提示音（只是从列表里移走，素材还在包里）。 */
    fun hide(id: String) {
        if (!id.startsWith(BUILT_IN_PREFIX)) return
        val removed = removedIds() + id
        Configs.removedPatchSounds = removed.joinToString(",")
        // 选中的正好是被删的那条就退回默认，否则修补时会静音
        if (Configs.patchStartSound == id) Configs.patchStartSound = Configs.DefaultStartSoundId
        if (Configs.patchSuccessSound == id) Configs.patchSuccessSound = Configs.DefaultSuccessSoundId
    }

    /** 恢复所有被删掉的内置提示音。 */
    fun restoreBuiltIns() {
        Configs.removedPatchSounds = ""
    }

    /** 永久删除一条自定义提示音（连同文件）。 */
    fun deleteCustom(id: String): Boolean {
        if (!id.startsWith(CUSTOM_PREFIX)) return false
        val array = runCatching { JSONArray(Configs.customPatchSounds) }.getOrDefault(JSONArray())
        val kept = JSONArray()
        var path: String? = null
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("id") == id) {
                path = item.optString("path")
            } else {
                kept.put(item)
            }
        }
        Configs.customPatchSounds = kept.toString()
        path?.let { runCatching { File(it).delete() } }
        if (Configs.patchStartSound == id) Configs.patchStartSound = Configs.DefaultStartSoundId
        if (Configs.patchSuccessSound == id) Configs.patchSuccessSound = Configs.DefaultSuccessSoundId
        return true
    }

    // ---------------------------------------------------------------- 自定义音频

    /** 已经复制进私有目录、等着用户决定截取哪一段的音频。 */
    data class StagedSound(
        val file: File,
        val suggestedName: String,
        val durationMs: Int
    )

    /** 把用户选的文件复制进私有目录，读时长；失败时不留残留。 */
    fun stage(source: Uri): Result<StagedSound> = runCatching {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val extension = extensionOf(source)
        val dir = customDir().apply { mkdirs() }
        val file = File(dir, "$id.$extension")
        val input = lspApp.contentResolver.openInputStream(source)
            ?: throw IOException("Cannot read $source")
        try {
            input.use { from -> file.outputStream().use { to -> from.copyTo(to) } }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        val duration = readDuration(file)
        StagedSound(file, displayNameOf(source)?.substringBeforeLast('.') ?: id, duration)
    }.onFailure { Log.w(TAG, "Could not stage a custom prompt sound", it) }

    /** 保存一条自定义提示音；[endMs] 为 0 或等于时长表示不截断。 */
    fun commit(staged: StagedSound, name: String, startMs: Int, endMs: Int): Result<PatchSound> = runCatching {
        if (!staged.file.isFile) throw IOException("${staged.file} is gone")
        val duration = staged.durationMs.takeIf { it > 0 } ?: readDuration(staged.file)
        val start = startMs.coerceIn(0, duration.coerceAtLeast(0))
        val end = endMs.coerceIn(0, duration.coerceAtLeast(0))
        val id = CUSTOM_PREFIX + staged.file.nameWithoutExtension
        val label = name.ifBlank { staged.suggestedName }
        val item = JSONObject()
            .put("id", id)
            .put("name", label)
            .put("path", staged.file.absolutePath)
            .put("duration", duration)
            .put("start", start)
            // 截到结尾就不记终点，之后换播放器也不会被旧时长限制住
            .put("end", if (end >= duration) 0 else end)
        val array = runCatching { JSONArray(Configs.customPatchSounds) }.getOrDefault(JSONArray())
        array.put(item)
        Configs.customPatchSounds = array.toString()
        if (duration > 0) {
            durationCache[staged.file.absolutePath] = duration
        }
        PatchSound(
            id = id,
            name = label,
            groupLabel = lspApp.getString(R.string.patch_sound_group_custom),
            file = staged.file,
            durationMs = duration,
            startMs = start,
            endMs = if (end >= duration) 0 else end,
            builtIn = false
        )
    }.onFailure { Log.w(TAG, "Could not save a custom prompt sound", it) }

    /** 用户放弃了刚选的音频。 */
    fun discard(staged: StagedSound) {
        runCatching { staged.file.delete() }
    }

    private fun extensionOf(source: Uri): String {
        val extension = displayNameOf(source)?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return if (extension.length in 1..5 && extension.all { it.isLetterOrDigit() }) extension else "mp3"
    }

    private fun displayNameOf(source: Uri): String? = runCatching {
        lspApp.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
}
