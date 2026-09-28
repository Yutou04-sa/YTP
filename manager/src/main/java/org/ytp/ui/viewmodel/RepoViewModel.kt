package org.ytp.ui.viewmodel

import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ytp.lspApp
import org.ytp.model.XposedModule
import org.ytp.util.LSPPackageManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class RepoViewModel : ViewModel() {
    companion object {
        private const val TAG = "RepoViewModel"
        private var RepoURL = lspApp.updateInfo?.repoURL ?: "https://backup.modules.lsposed.org"
        private val MODULE_JSON_URL = "${RepoURL}/modules.json"
        private val MODULE_DETAIL_URL = "${RepoURL}/module/%s.json"
        private const val PREFS_NAME = "repo_cache"
        private const val KEY_MODULES_CACHE = "modules_cache"
    }

    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private val prefs = lspApp.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    // 全局缓存
    private var cachedModules: List<XposedModule> = emptyList()
    private var installedModulePackageNames: Map<String, LSPPackageManager.AppInfo> = emptyMap()

    // UI 状态
    private val _uiState = MutableStateFlow<RepoUiState>(RepoUiState.Loading)
    val uiState: StateFlow<RepoUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortType = MutableStateFlow(0)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * 从 SharedPreferences 加载缓存数据
     */
    private fun loadFromCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isRefreshing.value = true
                val cachedJson = prefs.getString(KEY_MODULES_CACHE, null)
                if (!cachedJson.isNullOrEmpty()) {
                    val modules = gson.fromJson(cachedJson, Array<XposedModule>::class.java).toList()
                    // 恢复解析后的字段
                    modules.forEach {
                        it.parsedTime = parseDate(it.latestReleaseTime)
                        it.formattedDate = formatDateForUI(it.latestReleaseTime)
                        it.latestVersionCode = parseVersionCode(it.latestRelease)
                    }
                    cachedModules = modules
                    applyFilterAndSort()
                }
            } catch (e: Exception) {
                // 缓存解析失败，忽略，等待网络请求
            }finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * 保存缓存到 SharedPreferences
     */
    private fun saveToCache(modules: List<XposedModule>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = gson.toJson(modules)
                prefs.edit { putString(KEY_MODULES_CACHE, json) }
            } catch (e: Exception) {
                // 保存失败，忽略
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadInstalledModules()
        }
        // 优先从缓存加载，然后后台静默更新
        loadFromCache()
        loadModules(cachedModules.isEmpty())
    }
    private fun loadInstalledModules() {
        installedModulePackageNames = LSPPackageManager.appList
            .filter { it.isXposedModule.isNotEmpty() }.associateBy { it.app.packageName }
    }

    /**
     * 加载模块列表（优化：缓存优先，网络更快）
     * @param isRefresh 是否下拉刷新
     */
    fun loadModules(isRefresh: Boolean = false) {
        viewModelScope.launch {
            // 非静默更新且非刷新时才显示 Loading
            if (!isRefresh) _uiState.value = RepoUiState.Loading
            if (isRefresh) _isRefreshing.value = true

            val result = withContext(Dispatchers.IO) {
                try {
                    if (isRefresh) loadInstalledModules()

                    val url = URL(MODULE_JSON_URL)
                    val connection = url.openConnection() as HttpURLConnection

                    // 网络优化
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    //connection.setRequestProperty("Accept-Encoding", "gzip") // 启用GZIP，速度翻倍

                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
                        val response = reader.use { it.readText() }
                        reader.close()
                        connection.disconnect()

                        val modules = gson.fromJson(response, Array<XposedModule>::class.java).toList()

                        // 预解析日期，只解析一次！排序性能暴涨
                        modules.forEach {
                            it.parsedTime = parseDate(it.latestReleaseTime)
                            it.formattedDate = formatDateForUI(it.latestReleaseTime) // 👈 加这行
                            it.latestVersionCode = parseVersionCode(it.latestRelease)
                        }

                        cachedModules = modules
                        // 保存到本地缓存
                        saveToCache(modules)
                        Result.success(modules)
                    } else {
                        Result.failure(Exception("Network error $responseCode"))
                    }
                } catch (e: Exception) {
                    Result.failure(Exception("Loading failed"))
                }
            }

            result.onSuccess {
                applyFilterAndSort()
            }.onFailure { error ->
                _uiState.value = RepoUiState.Error(error.message ?: "error")
            }

            if (isRefresh) _isRefreshing.value = false
        }
    }

    /**
     * 搜索（优化：瞬间响应）
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilterAndSort()
    }

    /**
     * 排序（优化：瞬间响应）
     */
    fun setSortType(type: Int) {
        _sortType.value = type
        applyFilterAndSort()
    }

    /**
     * 统一筛选排序（超级快）
     */
    private fun applyFilterAndSort() {
        val query = _searchQuery.value.lowercase()
        val sortType = _sortType.value

        // 1. 分区：已安装 / 未安装
        val (installed, notInstalled) = cachedModules.partition {
            installedModulePackageNames.contains(it.name)
        }

        // 2. 搜索过滤
        val filter: (XposedModule) -> Boolean = { module ->
            query.isEmpty() ||
                    module.name.lowercase().contains(query) ||
                    module.description.lowercase().contains(query)
        }

        val filteredInstalled = installed.filter(filter)
        val filteredNotInstalled = notInstalled.filter(filter)

        // 3. 排序（使用预解析时间，超快）
        val sortedInstalled = sortModules(filteredInstalled, sortType)
        val sortedNotInstalled = sortModules(filteredNotInstalled, sortType)

        // 4. 标记已安装
        sortedInstalled.forEach {
            val versionCode = LSPPackageManager.getVersionCode(it.name)
            it.installed = if (versionCode != 0L) versionCode.toString() else ""
            it.isUpdate = it.latestVersionCode?.let { latestVersionCode ->
                latestVersionCode > versionCode && versionCode != 0L
            } ?: false
        }
        sortedNotInstalled.forEach { it.installed = "" }

        // 5. 更新UI
        _uiState.value = RepoUiState.Success(sortedInstalled + sortedNotInstalled)
    }

    /**
     * 超快排序（使用预解析时间）
     */
    private fun sortModules(
        list: List<XposedModule>,
        type: Int
    ): List<XposedModule> {
        return when (type) {
            0 -> list.sortedByDescending { it.parsedTime }
            1 -> list.sortedBy { it.parsedTime }
            2 -> list.sortedBy { it.description.lowercase() }
            3 -> list.sortedByDescending { it.description.lowercase() }
            else -> list
        }
    }

    /**
     * 日期解析
     */
    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            dateFormat.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    private fun formatDateForUI(dateStr: String?): String {
        if (dateStr.isNullOrBlank()) return ""
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            val date = inputFormat.parse(dateStr)
            date?.let { outputFormat.format(it) } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 加载模块详情（获取readmeHTML）
     */
    fun loadModuleDetail(moduleName: String, callback: (XposedModule?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL(MODULE_DETAIL_URL.format(moduleName))
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
                    val response = reader.use { it.readText() }
                    reader.close()
                    connection.disconnect()

                    // 只解析readmeHTML字段
                    val detailModule = gson.fromJson(response, XposedModule::class.java)
                    callback(detailModule)
                } else {
                    callback(null)
                }
            } catch (e: Exception) {
                callback(null)
            }
        }
    }

    private fun parseVersionCode(versionCodeStr: String?): Long {
        if (versionCodeStr.isNullOrBlank()) return 0L
        return try {
            versionCodeStr.split("-").firstOrNull()?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    sealed class RepoUiState {
        object Loading : RepoUiState()
        data class Success(val modules: List<XposedModule>) : RepoUiState()
        data class Error(val message: String) : RepoUiState()
    }
}