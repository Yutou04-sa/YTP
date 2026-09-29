package org.ytp.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.ytp.database.entity.Module

@Dao
interface ModuleDao {

    @Query("SELECT * FROM module WHERE pkgName = :pkgName")
    suspend fun getModule(pkgName: String): Module

    @Query("SELECT * FROM module")
    suspend fun getAll(): List<Module>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(module: Module)

    @Query("UPDATE module SET apkPath = :apkPath WHERE pkgName = :pkgName")
    suspend fun updateApkPath(pkgName: String, apkPath: String)

    @Delete
    suspend fun delete(module: Module)
}
