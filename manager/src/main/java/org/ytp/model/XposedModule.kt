/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class AdditionalAuthor(
    @SerializedName("login")
    val login: String?,

    @SerializedName("name")
    val name: String?
) : Parcelable

@Parcelize
data class XposedModule(
    @SerializedName("name")
    val name: String,
    
    @SerializedName("description")
    val description: String,
    
    @SerializedName("url")
    val url: String,
    
    @SerializedName("homepageUrl")
    val homepageUrl: String?,
    
    @SerializedName("collaborators")
    val collaborators: List<Collaborator>?,
    
    @SerializedName("latestRelease")
    val latestRelease: String?,

    @SerializedName("latestVersionCode")
    var latestVersionCode: Long?,
    
    @SerializedName("latestReleaseTime")
    val latestReleaseTime: String?,
    
    @SerializedName("latestBetaReleaseTime")
    val latestBetaReleaseTime: String?,
    
    @SerializedName("latestSnapshotReleaseTime")
    val latestSnapshotReleaseTime: String?,
    
    @SerializedName("releases")
    var releases: List<Release>,
    
    @SerializedName("readmeHTML")
    var readmeHTML: String?,
    
    @SerializedName("summary")
    val summary: String?,
    
    @SerializedName("scope")
    val scope: List<String>?,
    
    @SerializedName("sourceUrl")
    val sourceUrl: String?,
    
    @SerializedName("hide")
    val hide: Boolean,
    
    @SerializedName("additionalAuthors")
    val additionalAuthors: List<AdditionalAuthor>?,
    
    @SerializedName("updatedAt")
    val updatedAt: String?,
    
    @SerializedName("createdAt")
    val createdAt: String?,
    
    @SerializedName("stargazerCount")
    val stargazerCount: Int,

    @SerializedName("installed")
    var installed: String = "",
    var isUpdate: Boolean = false,

    // 👇 加这一行
     var parsedTime: Long = 0L,

    // 👇 加这一行
    var formattedDate: String = ""
) : Parcelable

@Parcelize
data class Collaborator(
    @SerializedName("login")
    val login: String,
    
    @SerializedName("name")
    val name: String?
) : Parcelable

@Parcelize
data class Release(
    @SerializedName("name")
    val name: String,
    
    @SerializedName("url")
    val url: String,
    
    @SerializedName("isDraft")
    val isDraft: Boolean,
    
    @SerializedName("descriptionHTML")
    val descriptionHTML: String?,
    
    @SerializedName("createdAt")
    val createdAt: String?,
    
    @SerializedName("publishedAt")
    val publishedAt: String?,
    
    @SerializedName("updatedAt")
    val updatedAt: String?,
    
    @SerializedName("tagName")
    val tagName: String,
    
    @SerializedName("isPrerelease")
    val isPrerelease: Boolean,
    
    @SerializedName("releaseAssets")
    val releaseAssets: List<ReleaseAsset>
) : Parcelable

@Parcelize
data class ReleaseAsset(
    @SerializedName("name")
    val name: String,
    
    @SerializedName("contentType")
    val contentType: String,
    
    @SerializedName("downloadUrl")
    val downloadUrl: String,
    
    @SerializedName("downloadCount")
    val downloadCount: Int,
    
    @SerializedName("size")
    val size: Long
) : Parcelable
