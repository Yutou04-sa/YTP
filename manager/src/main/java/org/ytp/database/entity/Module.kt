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
