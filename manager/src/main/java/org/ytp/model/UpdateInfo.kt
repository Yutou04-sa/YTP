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
