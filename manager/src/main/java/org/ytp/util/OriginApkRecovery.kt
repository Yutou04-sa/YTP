/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.util

import android.util.Log
import org.ytp.R
import org.ytp.lspApp
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Pulls the untouched original apk back out of a patch made by LSPatch or NPatch.
 *
 * Both keep the complete input file — including its signing block, so the developer's own
 * signature survives — as one stored entry inside the patched apk: every other entry of the
 * outer apk is just an offset-relinked view of the original bytes, so this entry is all that is
 * needed to rebuild the original apk. YTP (and the HKP branding before it) rewrites the app's
 * dex in place instead and keeps no copy, which is why only those two formats can be recovered;
 * see [OriginApkStore.patchSourceOf].
 */
object OriginApkRecovery {

    private const val TAG = "OriginApkRecovery"

    /**
     * Newer NPatch builds and LSPatch write `origin.apk`; older NPatch builds wrote
     * `origin_apk.bin`. NPatch reads exactly these names back in its own manager, so the order
     * matches the one it uses.
     */
    private val EMBEDDED_ORIGIN_PATHS = listOf(
        "assets/lspatch/origin.apk",
        "assets/npatch/origin.apk",
        "assets/npatch/origin_apk.bin",
    )

    /** The entry holding the original apk of [apk], or null when it carries none. */
    fun embeddedOriginPath(apk: File): String? = try {
        ZipFile(apk).use { zip -> EMBEDDED_ORIGIN_PATHS.firstOrNull { zip.getEntry(it) != null } }
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to inspect ${apk.path}", t)
        null
    }

    /**
     * True when every one of [apks] carries an original apk. NPatch patches of split apps are the
     * one known case where this is false: its patcher embeds the original only for the base apk.
     */
    fun canRecover(apks: List<File>): Boolean =
        apks.isNotEmpty() && apks.all { embeddedOriginPath(it) != null }

    /**
     * Copies the embedded original apk of every apk in [sourceApks] (base first) into [destDir],
     * naming them after the patch apks they came from (`base.apk`, `split_*.apk`).
     */
    fun recover(sourceApks: List<File>, destDir: File): List<File> {
        if (sourceApks.isEmpty()) {
            throw IOException(lspApp.getString(R.string.origin_apk_none_found, destDir.name))
        }
        if (!destDir.isDirectory && !destDir.mkdirs()) {
            throw IOException(lspApp.getString(R.string.origin_apk_create_failed, destDir.path))
        }
        return sourceApks.map { apk ->
            val entryPath = embeddedOriginPath(apk)
                ?: throw IOException(lspApp.getString(R.string.origin_apk_no_embedded, apk.name))
            val target = File(destDir, apk.name)
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(entryPath)
                    ?: throw IOException(lspApp.getString(R.string.origin_apk_no_embedded, apk.name))
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (target.length() <= 0L) {
                target.delete()
                throw IOException(lspApp.getString(R.string.origin_apk_embedded_empty, apk.name))
            }
            Log.i(TAG, "Recovered ${target.name} (${target.length()} B) from ${apk.name} via $entryPath")
            target
        }
    }
}
