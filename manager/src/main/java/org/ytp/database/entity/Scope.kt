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
import androidx.room.ForeignKey

@Entity(
    primaryKeys = ["appPkgName", "modulePkgName"],
    foreignKeys = [ForeignKey(entity = Module::class, parentColumns = ["pkgName"], childColumns = ["modulePkgName"], onDelete = ForeignKey.CASCADE)]
)
data class Scope(
    val appPkgName: String,
    val modulePkgName: String
)
