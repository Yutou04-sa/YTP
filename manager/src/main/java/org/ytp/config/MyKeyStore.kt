package org.ytp.config

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ytp.lspApp
import org.ytp.share.Constants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.security.KeyStore

// 密钥存储项数据类
data class KeyStoreItem(
    val name: String,
    val path: String, // 本地文件路径
    val password: String,
    val alias: String,
    val aliasPassword: String,
    val isDefault: Boolean = false
) : Serializable

object MyKeyStore {
    const val KEY_STORE_NAME_LSPATCH = "LSPatch"
    val file = File("${_root_ide_package_.org.ytp.lspApp.filesDir}/keystore.bks")
    val tmpFile = File("${_root_ide_package_.org.ytp.lspApp.filesDir}/keystore.bks.tmp")
    private val keyStoreListFile = File("${_root_ide_package_.org.ytp.lspApp.filesDir}/keystores.dat")

    var useDefault by mutableStateOf(!file.exists())
        private set

    // 当前选中的密钥名称
    var currentKeyStoreName by mutableStateOf(
        Configs.keyStoreName
    )
        private set

    // 获取所有密钥列表（包括默认密钥和用户添加的密钥）
    fun getAllKeyStores(): List<KeyStoreItem> {
        val keyStores = mutableListOf<KeyStoreItem>()
        
        // 添加默认密钥
        keyStores.add(
            KeyStoreItem(
                name = org.ytp.share.Constants.KEY_STORE_ALIAS,
                path = "keystore",
                password = org.ytp.share.Constants.KEY_STORE_PASSWORD,
                alias = org.ytp.share.Constants.KEY_STORE_ALIAS,
                aliasPassword = org.ytp.share.Constants.KEY_STORE_ALIAS_PASSWORD,
                isDefault = true
            )
        )
        // 添加默认密钥
        keyStores.add(
            KeyStoreItem(
                name = KEY_STORE_NAME_LSPATCH,
                path = KEY_STORE_NAME_LSPATCH.lowercase(),
                password = org.ytp.share.Constants.KEY_STORE_PASSWORD,
                alias = "key0",
                aliasPassword = org.ytp.share.Constants.KEY_STORE_ALIAS_PASSWORD,
                isDefault = true
            )
        )
        
        // 从持久化存储中读取其他密钥
        val savedKeyStores = loadKeyStoresFromStorage()
        keyStores.addAll(savedKeyStores)
        
        return keyStores
    }

    // 从存储中加载密钥列表
    private fun loadKeyStoresFromStorage(): List<KeyStoreItem> {
        if (!keyStoreListFile.exists()) {
            return emptyList()
        }
        
        try {
            val fileInputStream = FileInputStream(keyStoreListFile)
            val objectInputStream = ObjectInputStream(fileInputStream)
            @Suppress("UNCHECKED_CAST")
            val keyStores = objectInputStream.readObject() as? List<KeyStoreItem>
            objectInputStream.close()
            fileInputStream.close()
            return keyStores ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    // 保存密钥列表到存储
    private suspend fun saveKeyStoresToStorage(keyStores: List<KeyStoreItem>) {
        withContext(Dispatchers.IO) {
            try {
                val fileOutputStream = FileOutputStream(keyStoreListFile)
                val objectOutputStream = ObjectOutputStream(fileOutputStream)
                objectOutputStream.writeObject(keyStores)
                objectOutputStream.close()
                fileOutputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 添加新密钥 - 复制到唯一命名的文件中
    suspend fun addKeyStore(password: String, alias: String, aliasPassword: String) {
        withContext(Dispatchers.IO) {
            // 首先验证密钥库是否有效
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            try {
                tmpFile.inputStream().use { input ->
                    keyStore.load(input, password.toCharArray())
                }
                keyStore.getKey(alias, aliasPassword.toCharArray())
            } catch (e: Exception) {
                throw e
            }
            
            // 创建唯一命名的密钥文件
            val uniqueKeyStoreFile = File("${_root_ide_package_.org.ytp.lspApp.filesDir}/keystore_${alias.hashCode()}.bks")
            
            // 将临时文件复制到唯一命名的文件
            tmpFile.copyTo(uniqueKeyStoreFile, overwrite = true)
            tmpFile.copyTo(file, overwrite = true)
            
            // 更新配置
            Configs.keyStorePassword = password
            Configs.keyStoreAlias = alias
            Configs.keyStoreAliasPassword = aliasPassword
            Configs.keyStoreName = alias
            useDefault = false
            currentKeyStoreName = alias
            
            // 保存到密钥列表
            val existingKeyStores = loadKeyStoresFromStorage().toMutableList()
            // 检查是否已存在同名密钥，如果存在则替换
            val existingIndex = existingKeyStores.indexOfFirst { it.name == alias }
            if (existingIndex != -1) {
                existingKeyStores[existingIndex] = KeyStoreItem(
                    name = alias,
                    path = uniqueKeyStoreFile.absolutePath,
                    password = password,
                    alias = alias,
                    aliasPassword = aliasPassword,
                    isDefault = false
                )
            } else {
                existingKeyStores.add(
                    KeyStoreItem(
                        name = alias,
                        path = uniqueKeyStoreFile.absolutePath,
                        password = password,
                        alias = alias,
                        aliasPassword = aliasPassword,
                        isDefault = false
                    )
                )
            }
            
            saveKeyStoresToStorage(existingKeyStores)
        }
    }

    suspend fun reset() {
        withContext(Dispatchers.IO) {
            selectKeyStore(Constants.KEY_STORE_ALIAS)
        }
    }
    
    // 删除密钥
    suspend fun deleteKeyStore(name: String) {
        withContext(Dispatchers.IO) {
            // 不能删除默认密钥
            if (name == org.ytp.share.Constants.KEY_STORE_ALIAS) {
                return@withContext
            }
            
            // 从存储中移除密钥
            val existingKeyStores = loadKeyStoresFromStorage().toMutableList()
            val keyStoreToRemove = existingKeyStores.find { it.name == name }
            if (keyStoreToRemove != null) {
                // 删除对应的文件
                val keyStoreFile = File(keyStoreToRemove.path)
                if (keyStoreFile.exists() && !keyStoreToRemove.isDefault) {
                    keyStoreFile.delete()
                }
                
                existingKeyStores.remove(keyStoreToRemove)
                saveKeyStoresToStorage(existingKeyStores)
                
                // 如果删除的是当前选中的密钥，则切换到默认密钥
                if (currentKeyStoreName == name) {
                    reset()
                }
            }
        }
    }
    
    // 选择密钥
    suspend fun selectKeyStoreDialog(name: String) {
        withContext(Dispatchers.IO) {
            selectKeyStore(name)
        }
    }

    // 选择密钥
    fun selectKeyStore(name: String) {
        val allKeyStores = getAllKeyStores()
        val keyStoreItem = allKeyStores.find { it.name == name }

        if (keyStoreItem != null) {
            if(keyStoreItem.isDefault){
                lspApp.assets.open(keyStoreItem.path).use { key ->
                    file.outputStream().use {
                        it.write(key.readBytes())
                    }
                }
            }
            else{
                val keyStoreFile = File(keyStoreItem.path)
                if (!keyStoreFile.exists()) {
                    return
                }
                // 复制选定的密钥文件到主文件位置，以便系统使用
                keyStoreFile.copyTo(file, overwrite = true)
            }
            Configs.keyStorePassword = keyStoreItem.password
            Configs.keyStoreAlias = keyStoreItem.alias
            Configs.keyStoreAliasPassword = keyStoreItem.aliasPassword
            useDefault = keyStoreItem.isDefault
            if(keyStoreItem.isDefault){
                if(keyStoreItem.name == org.ytp.share.Constants.KEY_STORE_ALIAS){
                    Configs.keyStoreName = org.ytp.share.Constants.KEY_STORE_ALIAS
                }
                else{
                    Configs.keyStoreName = KEY_STORE_NAME_LSPATCH
                }
                currentKeyStoreName = Configs.keyStoreName
            }else{
                Configs.keyStoreName = keyStoreItem.alias
                currentKeyStoreName = keyStoreItem.alias
            }
        }
    }
}