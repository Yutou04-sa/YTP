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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private const val DEFAULT_REPO_URL = "https://backup.modules.lsposed.org"
        private const val PREFS_NAME = "repo_cache"
        private const val KEY_MODULES_CACHE = "modules_cache"

        // updateInfo 可能在类初始化时还没准备好，所以仓库地址在每次调用时惰性读取。
        private val RepoURL: String
            get() = lspApp.updateInfo?.repoURL ?: DEFAULT_REPO_URL
        private val MODULE_JSON_URL: String
            get() = "${RepoURL}/modules.json"
        private val MODULE_DETAIL_URL: String
            get() = "${RepoURL}/module/%s.json"
    }

    private val gson = Gson()
    private val prefs = lspApp.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    // 全局缓存
    private var cachedModules: List<XposedModule> = emptyList()
    private var installedModulePackageNames: Map<String, LSPPackageManager.AppInfo> = emptyMap()

    // 筛选/排序会做 binder 调用，串行化以免并发任务互相覆盖状态
    private val filterSortMutex = Mutex()

    // UI 状态
    private val _uiState = MutableStateFlow<RepoUiState>(RepoUiState.Loading)
    val uiState: StateFlow<RepoUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortType = MutableStateFlow(0)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * 从 SharedPreferences 加载缓存数据。返回 true 表示拿到了可用缓存（UI 已显示出来）。
     */
    private suspend fun loadFromCache(): Boolean {
        return try {
            _isRefreshing.value = true
            val cachedJson = withContext(Dispatchers.IO) {
                prefs.getString(KEY_MODULES_CACHE, null)
            }
            if (cachedJson.isNullOrEmpty()) {
                false
            } else {
                val modules = withContext(Dispatchers.IO) {
                    gson.fromJson(cachedJson, Array<XposedModule>::class.java).toList()
                }
                // 恢复解析后的字段
                modules.forEach {
                    it.parsedTime = parseDate(it.latestReleaseTime)
                    it.formattedDate = formatDateForUI(it.latestReleaseTime)
                    it.latestVersionCode = parseVersionCode(it.latestRelease)
                }
                cachedModules = modules
                // 已经在 IO 线程上，直接排序以便缓存立刻可见
                applyFilterAndSort()
                true
            }
        } catch (e: Exception) {
            // 缓存解析失败，忽略，等待网络请求
            false
        } finally {
            _isRefreshing.value = false
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
            // 1. 先拿本机已安装模块，缓存里的"已安装"标记才算得对
            loadInstalledModules()
            // 2. 同步等待缓存读完：读完 cachedModules 才一定可用，才知道网络请求要不要发
            loadFromCache()
            // 3. 有缓存就在后台静默更新，没缓存才显示 Loading 等待网络
            loadModules(forceLoading = cachedModules.isEmpty())
        }
    }
    private fun loadInstalledModules() {
        try {
            installedModulePackageNames = LSPPackageManager.appList
                .filter { it.isXposedModule.isNotEmpty() }.associateBy { it.app.packageName }
        } catch (e: Exception) {
            installedModulePackageNames = emptyMap()
        }
    }

    /**
     * 加载模块列表（优化：缓存优先，网络更快）
     * @param isRefresh 是否下拉刷新
     */
    fun loadModules(isRefresh: Boolean = false) {
        // 下拉刷新时不该把已经显示出来的列表换成 Loading，静默更新同理
        loadModules(forceLoading = !isRefresh, showRefreshingIndicator = isRefresh)
    }

    private fun loadModules(forceLoading: Boolean, showRefreshingIndicator: Boolean = false) {
        // 状态在主线程同步设置，避免协程真正启动前 UI 停留在旧状态
        if (forceLoading) _uiState.value = RepoUiState.Loading
        if (showRefreshingIndicator) _isRefreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                    if (showRefreshingIndicator) loadInstalledModules()

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

            result.onSuccess {
                applyFilterAndSort()
            }.onFailure { error ->
                // 网络失败时如果本地已有缓存内容，就继续展示缓存，不要用 Error 覆盖掉
                if (cachedModules.isEmpty()) {
                    _uiState.value = RepoUiState.Error(error.message ?: "error")
                }
            }

            if (showRefreshingIndicator) _isRefreshing.value = false
        }
    }

    /**
     * 搜索（优化：瞬间响应）
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        refreshFiltered()
    }

    /**
     * 排序（优化：瞬间响应）
     */
    fun setSortType(type: Int) {
        _sortType.value = type
        refreshFiltered()
    }

    /**
     * 在主线程之外重新做筛选/排序：里面每个已安装模块都会走一次 binder 调用（getVersionCode），
     * 放在主线程会卡顿。
     */
    private fun refreshFiltered() {
        viewModelScope.launch(Dispatchers.Default) { applyFilterAndSort() }
    }

    /**
     * 统一筛选排序（超级快）
     * 注意：会做 binder 调用，只应在 Dispatchers.IO/Default 上调用。
     */
    private suspend fun applyFilterAndSort() {
        filterSortMutex.withLock {
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
            val versionCode = runCatching { LSPPackageManager.getVersionCode(it.name) }.getOrDefault(0L)
            it.installed = if (versionCode != 0L) versionCode.toString() else ""
            it.isUpdate = it.latestVersionCode?.let { latestVersionCode ->
                latestVersionCode > versionCode && versionCode != 0L
            } ?: false
        }
        sortedNotInstalled.forEach { it.installed = "" }

        // 5. 更新UI
        _uiState.value = RepoUiState.Success(sortedInstalled + sortedNotInstalled)
        }
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
            // SimpleDateFormat 不是线程安全的，这里每次调用新建，避免 IO/Main 线程共享
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(dateStr)?.time ?: 0L
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