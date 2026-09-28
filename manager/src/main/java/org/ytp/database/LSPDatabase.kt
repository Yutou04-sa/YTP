package org.ytp.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [_root_ide_package_.org.ytp.database.entity.Module::class, _root_ide_package_.org.ytp.database.entity.Scope::class], version = 1)
abstract class LSPDatabase : RoomDatabase() {
    abstract fun moduleDao(): org.ytp.database.dao.ModuleDao
    abstract fun scopeDao(): org.ytp.database.dao.ScopeDao
}
