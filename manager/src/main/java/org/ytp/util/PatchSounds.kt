/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.ytp.config.Configs
import org.ytp.lspApp
import java.io.File

/**
 * 修补流程的提示音播放。
 *
 * 用 [MediaPlayer] 而不是 SoundPool：提示音可以是用户自己选的 mp3，还要能从中间截一段，
 * SoundPool 只适合很短的小文件，也不方便控制播放区间。
 *
 * 两个播放器分别对应「开始修补」与「修补完成」，按 [Configs.patchStartSound] /
 * [Configs.patchSuccessSound] 选中的音源提前 prepare 好（启动时调用 [preload]），
 * 修补开始时才能立刻出声；试听用第三个播放器，避免打断这两个。
 *
 * 打开 [Configs.patchSoundsRandom] 后不再用这两个预准备的播放器，改成每响一次现挑一条、
 * 现建播放器、放完就释放（见 [playRandomSound]），否则要把整个音源库都 prepare 起来。
 */
object PatchSounds {

    private const val TAG = "PatchSounds"

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 走媒体音量（而不是 USAGE_ASSISTANCE_SONIFICATION 的系统音量）。
     *
     * 提示音是用户自己挑的音频，系统音量那一档在关掉「触摸提示音」的设备上是 0，会静音；
     * 媒体档位是用户平时就能听到、也方便自己调的那一档。
     */
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var startPlayer: Player? = null

    private var successPlayer: Player? = null

    /** 随机播放时临时建的播放器（开始 / 成功各一个），放完即释放。 */
    private var randomStartPlayer: Player? = null

    private var randomSuccessPlayer: Player? = null

    private var previewPlayer: Player? = null

    /** 已经按哪一对选择准备好了，选择变了才需要重新 prepare。 */
    private var preparedFor: Pair<String, String>? = null

    /** 上一次随机到的那条，用来尽量避免连着响同一条。 */
    private var lastRandomId: String? = null

    /**
     * 当前媒体音量值不值得提示用户（提示音走媒体档，见 [audioAttributes]）。
     */
    enum class VolumeLevel {

        /** 音量正常。 */
        OK,

        /** 不到一半，提示音可能听不清。 */
        LOW,

        /** 0，完全听不到。 */
        MUTED
    }

    /**
     * 读一下现在的媒体音量。
     *
     * 提示音走媒体档（见 [audioAttributes]），音量为 0 时试听和修补提示音都完全没声音，
     * 不到一半也不容易听清，界面据此提示用户去调音量，免得被当成播放失败或者功能坏了。
     */
    fun volumeLevel(): VolumeLevel = runCatching {
        val audio = lspApp.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return@runCatching VolumeLevel.OK
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        when {
            current <= 0 -> VolumeLevel.MUTED
            max > 0 && current * 2 < max -> VolumeLevel.LOW
            else -> VolumeLevel.OK
        }
    }.getOrDefault(VolumeLevel.OK)

    /** 提前准备好两个播放器（启动时调用）。 */
    @Synchronized
    fun preload() {
        ensurePlayers(force = true)
    }

    /** 修补开始。 */
    @Synchronized
    fun playStart() {
        if (!Configs.patchSounds) return
        if (Configs.patchSoundsRandom) {
            playRandomSound(isStart = true)
            return
        }
        ensurePlayers(force = false)
        startPlayer?.play()
    }

    /** 修补成功（失败时不响）。 */
    @Synchronized
    fun playSuccess() {
        if (!Configs.patchSounds) return
        if (Configs.patchSoundsRandom) {
            playRandomSound(isStart = false)
            return
        }
        ensurePlayers(force = false)
        successPlayer?.play()
    }

    /**
     * 单独试听一条提示音。
     *
     * [onFinished] 在播放结束（或放不出来）时回调，界面据此复位试听按钮。
     */
    @Synchronized
    fun preview(sound: PatchSound, onFinished: (() -> Unit)? = null) {
        stopPreview()
        if (!sound.exists()) {
            Log.w(TAG, "The prompt sound ${sound.id} is missing (${sound.file})")
            onFinished?.invoke()
            return
        }
        // 必须先 prepare：preview 的播放器是现建的，不 prepare 的话 play() 会发现还没有播放器而直接返回，
        // 表现就是点了试听完全没声音。
        val player = Player(sound.file, sound.fromMs, sound.toMs, onFinished)
        previewPlayer = player
        player.prepare()
        player.play()
    }

    @Synchronized
    fun stopPreview() {
        previewPlayer?.release()
        previewPlayer = null
    }

    /** 释放全部播放器（退出前/设置改变后需要时调用）。 */
    @Synchronized
    fun release() {
        startPlayer?.release()
        startPlayer = null
        successPlayer?.release()
        successPlayer = null
        releaseRandomPlayer(isStart = true)
        releaseRandomPlayer(isStart = false)
        stopPreview()
        preparedFor = null
    }

    private fun ensurePlayers(force: Boolean) {
        // 随机模式不用这两个预准备的播放器，留着只是白占一个 MediaPlayer
        if (Configs.patchSoundsRandom) {
            startPlayer?.release()
            startPlayer = null
            successPlayer?.release()
            successPlayer = null
            preparedFor = null
            return
        }
        val selection = Configs.patchStartSound to Configs.patchSuccessSound
        if (!force && preparedFor == selection && startPlayer != null && successPlayer != null) return
        startPlayer?.release()
        successPlayer?.release()
        startPlayer = newPlayer(PatchSoundLibrary.findOrDefault(selection.first, true))
        successPlayer = newPlayer(PatchSoundLibrary.findOrDefault(selection.second, false))
        preparedFor = selection
    }

    /**
     * 随机响一声。
     *
     * 每响一次都现挑一条、现建播放器，放完（或出错）就释放：音源库可能有上百条，不可能都提前 prepare，
     * 而 MediaPlayer 也不该越堆越多。
     */
    private fun playRandomSound(isStart: Boolean) {
        releaseRandomPlayer(isStart)
        val sound = pickRandomSound() ?: run {
            Log.w(TAG, "No prompt sound available to play at random")
            return
        }
        val player = Player(sound.file, sound.fromMs, sound.toMs) {
            // 回调是在 MediaPlayer 自己的监听里触发的，挪到下一轮消息再释放，别在监听里把自己拆了
            handler.post { releaseRandomPlayer(isStart) }
        }
        if (isStart) {
            randomStartPlayer = player
        } else {
            randomSuccessPlayer = player
        }
        player.prepare()
        player.play()
    }

    /** 从库里随机挑一条（文件确实还在的），尽量不跟上次重复。 */
    private fun pickRandomSound(): PatchSound? {
        val pool = PatchSoundLibrary.allSounds().filter { it.exists() }
        if (pool.isEmpty()) return null
        val candidates = if (pool.size > 1) pool.filterNot { it.id == lastRandomId } else pool
        val picked = candidates.random()
        lastRandomId = picked.id
        return picked
    }

    private fun releaseRandomPlayer(isStart: Boolean) {
        if (isStart) {
            randomStartPlayer?.release()
            randomStartPlayer = null
        } else {
            randomSuccessPlayer?.release()
            randomSuccessPlayer = null
        }
    }

    private fun newPlayer(sound: PatchSound?): Player? {
        if (sound == null || !sound.exists()) {
            Log.w(TAG, "No prompt sound to prepare (${sound?.id})")
            return null
        }
        return Player(sound.file, sound.fromMs, sound.toMs).also { it.prepare() }
    }

    /**
     * 单个提示音的播放器。
     *
     * [fromMs] / [toMs] 是从原音频里截出来的播放区间；[toMs] 为 0 表示播到文件结束。
     */
    private class Player(
        private val file: File,
        private val fromMs: Int,
        private val toMs: Int,
        private val onFinished: (() -> Unit)? = null
    ) {

        private var player: MediaPlayer? = null

        private var prepared = false

        /** prepare 是异步的，还没就绪就被要求播放时先记下来。 */
        private var pending = false

        private var stopTask: Runnable? = null

        fun prepare() {
            val created = MediaPlayer()
            player = created
            runCatching {
                created.setAudioAttributes(audioAttributes)
                created.setDataSource(file.absolutePath)
                created.setOnPreparedListener {
                    prepared = true
                    if (pending) {
                        pending = false
                        play()
                    }
                }
                created.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error $what/$extra on ${file.name}")
                    onFinished?.invoke()
                    true
                }
                created.setOnCompletionListener { onFinished?.invoke() }
                created.prepareAsync()
            }.onFailure { t ->
                Log.w(TAG, "Could not prepare ${file.name}", t)
                release()
                onFinished?.invoke()
            }
        }

        fun play() {
            val created = player ?: return
            if (!prepared) {
                pending = true
                return
            }
            runCatching {
                stopTask?.let { handler.removeCallbacks(it) }
                created.seekTo(fromMs)
                created.start()
                // 截取过的音频要自己掐掉尾巴；toMs 为 0 表示时长未知，那就让它自然播完
                if (toMs > fromMs) {
                    val task = Runnable {
                        runCatching { created.pause() }
                        onFinished?.invoke()
                    }
                    stopTask = task
                    handler.postDelayed(task, (toMs - fromMs).toLong())
                }
            }.onFailure { Log.w(TAG, "Could not play ${file.name}", it) }
        }

        fun release() {
            stopTask?.let { handler.removeCallbacks(it) }
            stopTask = null
            runCatching { player?.release() }
            player = null
            prepared = false
            pending = false
        }
    }
}
