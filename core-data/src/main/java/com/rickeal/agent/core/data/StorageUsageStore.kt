package com.rickeal.agent.core.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一个存储分桶的**来源**：参与求和的目录 / 文件 + 展示元数据。
 *
 * 由 [AppContainer] 汇总构造 —— 路径的**唯一事实来源**在各 Store 的 `directory` 属性上
 *（[AttachmentStore.directory] / [WallpaperStore.directory] / [ConversationRepository.directory] /
 * [ModelRepository.directory] …），这里只做引用，**不重复拼字面量**。
 */
data class StorageBucketSource(
    val id: String,
    val title: String,
    val description: String,
    /** 参与求和的目录 / 文件；「模型」这类横跨多处的桶会有多个。 */
    val targets: List<File>,
    /**
     * 是否允许「清除」。
     *
     * ⚠️ 用户资产（模型 / 会话 / 附件 / 记忆 / 壁纸 / 设置）恒 `false`：端侧没有云端副本，
     * 一键清除 = 不可恢复的数据销毁（与 WorkBuddy 语义相反，见 [StorageUsageStore] KDoc）。
     */
    val clearable: Boolean,
)

/**
 * 存储用量的一个分桶。
 *
 * **UI 中间态**：不落盘、刻意**不加 `@Serializable`**（规格要求零序列化变更）。
 */
@Immutable
data class StorageBucket(
    val id: String,
    val title: String,
    val description: String,
    /** 主目录绝对路径（详情页展示「数据在哪儿」）；无可用目录时为 null。 */
    val path: String?,
    val bytes: Long,
    val clearable: Boolean,
)

/**
 * 存储空间用量统计（Wave 10 Phase 2b C-2）。
 *
 * 只读求和 + 受限清除，全部走 [Dispatchers.IO]：`walkTopDown()` 是阻塞的文件树遍历，
 * 模型目录动辄几 GB / 上千个文件，绝不能占用主线程。
 *
 * ## 与 WorkBuddy 的关键差异（**不可照抄**）
 *
 * 端侧 Agent **没有云端副本**：WorkBuddy 的「清除」是把云端占用清零，本地随时可重新拉取；
 * 而这里的模型 / 会话 / 附件一旦删除就**永久消失**。所以分桶分两档：
 *  - **可再生成**（[StorageBucket.clearable] = true）：缓存 / 运行日志 / 回合归档 / 执行计划 /
 *    子代理会话 / 诊断日志 / 沙箱 —— 清掉只是丢临时数据，功能下次自建；
 *  - **用户资产**（false）：模型 / 模型索引 / 会话 / 附件 / 记忆 / 壁纸 / 设置 —— 只显示大小，
 *    **不提供一键清**（删除入口留在各自的管理页，那里有单条确认）。
 *
 * 这个划分是**结构性**的（写死在 [AppContainer] 的源列表里），不是 UI 层的按钮开关 ——
 * 加错一处就是「一键删掉用户 4GB 模型」的自毁按钮。
 */
class StorageUsageStore(
    private val sources: List<StorageBucketSource>,
) {

    /** 逐个分桶求和。永远返回与 [sources] **等长、顺序一致**的列表（UI 靠顺序稳定）。 */
    suspend fun usage(): List<StorageBucket> = withContext(Dispatchers.IO) {
        sources.map { source ->
            StorageBucket(
                id = source.id,
                title = source.title,
                description = source.description,
                // 「数据在哪儿」必须如实：多目录桶（如模型 = 内部 models + 外部 models +
                // 外部 Download）曾只显示 targets[0]，用户看着内部路径找文件、实际模型
                // 却下载在外部 —— 全部列出（换行分隔；单目录桶展示不变）。
                path = source.targets
                    .map { it.absolutePath }
                    .distinct()
                    .joinToString("\n")
                    .ifEmpty { null },
                bytes = source.targets.sumOf { sizeOf(it) },
                clearable = source.clearable,
            )
        }
    }

    /**
     * 清除一个**可清除**分桶的内容，返回**是否至少有一个 target 删除成功**
     * （不是"是否执行了删除" —— 后者会让 UI 在全部删除失败时仍提示"已清除"）。
     *
     * - 未知 id / [StorageBucketSource.clearable] 为 false → 直接返回 false（**不删任何东西**）；
     * - 目录：只删**内容**、保留目录本身 —— cacheDir / sandboxDir 等根目录由系统或 App 持有，
     *   删掉根再等它们各自 `mkdirs()` 会有一瞬间的「目录不存在」窗口；
     * - 单文件（如诊断日志）：删文件本身。
     *
     * 每个 target 各自 `runCatching`：一个目录删不掉（个别文件被占用）不该连累其余 target。
     */
    suspend fun clear(id: String): Boolean = withContext(Dispatchers.IO) {
        val source = sources.firstOrNull { it.id == id } ?: return@withContext false
        if (!source.clearable) return@withContext false
        // 返回语义 = **"是否至少有一个 target 真的删掉了"**，不是"是否执行了删除"。
        // 早先无条件 `return true` 会让 UI 在"文件全被占用、一个都没删掉"时仍提示
        // "已清除"，而用户看到数值没有归零 —— 假成功提示。
        var anySucceeded = false
        source.targets.forEach { target ->
            val ok = runCatching {
                if (target.isDirectory) {
                    target.listFiles()?.forEach { it.deleteRecursively() }
                } else if (target.exists()) {
                    target.delete()
                }
            }.isSuccess
            anySucceeded = anySucceeded || ok
        }
        anySucceeded
    }

    private fun sizeOf(target: File): Long {
        if (!target.exists()) return 0L
        if (target.isFile) return target.length()
        return runCatching {
            target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }
}
