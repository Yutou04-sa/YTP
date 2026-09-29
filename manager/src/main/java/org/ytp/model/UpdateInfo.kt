/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.model

import com.google.gson.annotations.SerializedName

data class UpdateInfo(
    @SerializedName("zh") val zh: UpdateContent,
    @SerializedName("en") val en: UpdateContent,
    @SerializedName("version") var version: Int,
    @SerializedName("force") var force: Boolean,
    @SerializedName("repoURL") val repoURL: String,
)

data class UpdateContent(
    @SerializedName("title") val title: String,
    @SerializedName("msg") val msg: String,
    @SerializedName("download") val download: String,
    @SerializedName("history") val history: String,
    @SerializedName("confirm") val confirm: String,
    @SerializedName("cancel") val cancel: String,
)
