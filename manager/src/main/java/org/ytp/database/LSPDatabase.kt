/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [_root_ide_package_.org.ytp.database.entity.Module::class, _root_ide_package_.org.ytp.database.entity.Scope::class], version = 1)
abstract class LSPDatabase : RoomDatabase() {
    abstract fun moduleDao(): org.ytp.database.dao.ModuleDao
    abstract fun scopeDao(): org.ytp.database.dao.ScopeDao
}
