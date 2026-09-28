package org.ytp.util

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ytp.model.UpdateInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    
    /**
     * 更新检查地址。本 fork 不提供自己的 update2 服务，故留空 => 启动时不会发起任何网络请求、
     * 也不会弹出更新对话框（等价于“停用更新检查入口”）。
     * 想恢复更新提示：把下面改成你自己的 update2 地址即可（格式见 update-msg 模块）。
     */
    private const val UPDATE_URL = ""
    
    /**
     * 检测更新
     * @return UpdateInfo 如果有更新，否则 null
     */
    suspend fun checkUpdate(): UpdateInfo? {
        if (UPDATE_URL.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(UPDATE_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
                    var response = reader.use { it.readText() }
                    //responses是StandardCharsets.UTF_16字节数组[1,2,118]这种，需要转换成字符串
                    val toByteArray = Gson().fromJson(response, ByteArray::class.java)
                        response = String(toByteArray, Charsets.UTF_16)
                    Gson().fromJson(response, UpdateInfo::class.java)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
