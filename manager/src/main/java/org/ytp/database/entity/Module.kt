/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Module(
    @PrimaryKey val pkgName: String,
    // apkPath 参与 equals/hashCode，而 Module 被当作 Map 的 key 使用，
    // 因此必须是不可变字段：路径变化时改用 copy() 产出新实例并同步落库。
    val apkPath: String
)
