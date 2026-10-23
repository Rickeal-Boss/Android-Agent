package com.rickeal.agent.core.data

import com.rickeal.agent.core.model.Conversation

/**
 * 会话全文的进程内读缓存（W80-03 Phase A）。
 *
 * 背景：[ConversationRepository.appendMessage] 此前每追加一条消息要做
 * `writableOrCorrupted`（一次全量读+反序列化）+ 主流程再一次全量读+反序列化，
 * 会话越大每条消息的落盘前开销越高。本缓存把「同一会话反复读全文」收敛成
 * O(1) 内存命中，稳态（save 回填后）appendMessage 只剩一次 `exists` stat、
 * 零次全量读。
 *
 * **缓存一致性边界（W80-03 审查 P1/P2 修法）**：
 * - **唯一写方是 `save()` 写穿回填**（落盘成功才 `put`），[ConversationRepository.load]
 *   **只读缓存或磁盘、绝不回填** —— load 是公开方法、常在 [writeMutex] 锁外调用
 *   （打开会话），若允许它回填，与锁内并发 appendMessage 的 save 交错时会把陈旧
 *   快照写进缓存，下一次 appendMessage 基于陈旧全文落盘 ⇒ 上一条消息无声丢失
 *   （TOCTOU）。读盘结果永不入缓存，此竞态窗口从根上消失。
 * - **不缓存「解析失败」结论**：`JsonFileStore.read` 吞异常返回 null，无法区分
 *   「真损坏」与「瞬时 IO 失败」；缓存住前者语义正确，缓存住后者会把一次性失败
 *   放大成整进程拒写（HEAD 是每次写前重读重判，瞬时失败只损失一次写）。
 *   现在的口径：**瞬时读失败只拒写本次，不熔断**；真损坏文件每次写入前都会重新
 *   探测并拒绝，损坏保护语义不变。
 * - `Conversation` 数据类全字段不可变，[ConversationRepository.appendMessage] 走
 *   `copy()` 生成新对象，不存在缓存对象被原地改坏的问题。
 * - [ConversationRepository.delete] 落盘删除后 `invalidate`，缓存不会复活已删会话；
 *   `rename` / `autoTitle` / `create` 都经 `save` 写穿回填，无需单独失效。
 *
 * 容量：LRU（按最近访问淘汰，`get` 也刷新访问序），默认上限 8 个会话，
 * 上限与初始容量可注入便于单测。全部方法线程安全（`synchronized` 内包
 * `LinkedHashMap(accessOrder = true)`）。
 */
class ConversationCache(
    /** 缓存会话数上限，超限淘汰最久未访问的条目。 */
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    /** 底层 LinkedHashMap 的初始容量（不扩容 rehash 的小优化，可注入便于单测）。 */
    initialCapacity: Int = 16,
) {
    init {
        require(maxEntries > 0) { "maxEntries 必须 > 0，实际 $maxEntries" }
        require(initialCapacity > 0) { "initialCapacity 必须 > 0，实际 $initialCapacity" }
    }

    private val entries = object : LinkedHashMap<String, Conversation>(initialCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Conversation>): Boolean =
            size > maxEntries
    }

    /** 取有效全文。命中刷新访问序；未缓存返回 null。 */
    @Synchronized
    fun get(id: String): Conversation? = entries[id]

    /** 回填有效全文（仅 save 落盘成功后写穿调用）。 */
    @Synchronized
    fun put(id: String, conversation: Conversation) {
        entries[id] = conversation
    }

    /** 失效单条（delete 落盘删除后调用）。 */
    @Synchronized
    fun invalidate(id: String) {
        entries.remove(id)
    }

    /** 清空全部缓存。 */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    private companion object {
        /** 默认上限：会话列表页 + 当前活跃会话，8 个足够覆盖热路径。 */
        const val DEFAULT_MAX_ENTRIES = 8
    }
}
