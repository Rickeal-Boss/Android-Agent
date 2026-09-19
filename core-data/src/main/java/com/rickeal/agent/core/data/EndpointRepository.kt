package com.rickeal.agent.core.data

import android.content.Context
import com.rickeal.agent.core.model.RemoteEndpoint
import com.rickeal.agent.core.model.RemotePresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** 别名：与任务书里的命名对齐（真正实现的类名沿用架构文档 §6.3 的 EndpointRepository）。 */
typealias RemoteEndpointsRepository = EndpointRepository

/**
 * 远程端点 CRUD（OpenAI 兼容）。JSON 持久化，首次启动写入预置端点。
 */
class EndpointRepository(context: Context) {
    private val store = JsonFileStore(File(context.filesDir, "endpoints"))
    private val _endpoints = MutableStateFlow<List<RemoteEndpoint>>(RemotePresets.all())
    val endpoints: StateFlow<List<RemoteEndpoint>> = _endpoints.asStateFlow()

    /**
     * 串行化所有「读 `_endpoints` → 改 → 写 endpoints.json」的操作。
     *
     * 无锁时两个并发写会互相覆盖（用户刚填的 API Key 从列表里消失且无任何报错）。
     * `Mutex` **不可重入**：只给 [refresh] / [upsert] / [remove] 这三个**互不调用**的入口加锁；
     * [delete]（[remove] 的别名）与 [upsertValidated] 都只是转发，自己绝不加锁，否则自锁。
     */
    private val writeMutex = Mutex()

    /** 供 UI collect 的只读流（与 [endpoints] 同源，只是把 StateFlow 收敛成 Flow）。 */
    fun observeEndpoints(): Flow<List<RemoteEndpoint>> = endpoints

    suspend fun refresh() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val list = store.read("endpoints.json", ListSerializer(RemoteEndpoint.serializer()))
            _endpoints.value = if (list.isNullOrEmpty()) RemotePresets.all() else list
        }
    }

    suspend fun upsert(endpoint: RemoteEndpoint) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val next = _endpoints.value.filter { it.id != endpoint.id } + endpoint
            store.write("endpoints.json", next, ListSerializer(RemoteEndpoint.serializer()))
            _endpoints.value = next
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val next = _endpoints.value.filter { it.id != id }
            store.write("endpoints.json", next, ListSerializer(RemoteEndpoint.serializer()))
            _endpoints.value = next
        }
    }

    /** [remove] 的语义别名 —— UI 侧更习惯叫 delete。 */
    suspend fun delete(id: String) = remove(id)

    suspend fun find(id: String?): RemoteEndpoint? =
        if (id == null) null else _endpoints.value.firstOrNull { it.id == id }

    /** 保存前做一次轻量校验，避免把空 baseUrl 存进去。 */
    suspend fun upsertValidated(endpoint: RemoteEndpoint): Boolean {
        if (endpoint.baseUrl.isBlank()) return false
        upsert(endpoint)
        return true
    }
}
