package com.rickeal.agent.core.model

/**
 * 进程内诊断日志的**环形缓冲**（这个项目的「黑匣子」）。
 *
 * ## 为什么需要它
 * 本项目的验证通道只有云端编译：编译只能证明「能编译」，证明不了「行为对」。
 * 而真机上出问题时（工具结果被丢弃、半截回答落库、引擎损坏后不恢复……）
 * 这些都是**静默缺陷**——不崩不报错，只是结果错，用户不会去 adb 抓 logcat。
 * 把关键决策点留在进程内，至少能在出事后回看「刚才发生了什么」。
 *
 * ## 设计取舍（逐条对应约束）
 *  - **绝不无限增长**：容量固定为 [DEFAULT_CAPACITY] 条，写满后丢弃最旧的一条。
 *    这里**没有**「按需扩容」的开关，端侧内存不允许。
 *  - **不持久化到会话 JSON**：日志不进会话文件、不落盘，杀进程即清空。
 *    代价是「重启 App 后现场丢失」——这是刻意的，换来的是会话文件不被撑大。
 *  - **进程级单例**（object）：写入方（引擎 / Agent 循环）与读取方（设置页）横跨多个模块，
 *    走单例可以不引入任何 DI 装配、也不需要新增依赖（本模块只有 kotlinx-serialization，
 *    连协程都没有，所以这里**没有** Flow/监听器，UI 用 [recent] 取快照）。
 *  - **线程安全**：Agent 循环跑在 Default/IO 线程，UI 在主线程读，全部经同一把锁。
 */
object AgentLogStore {

    /** 环形缓冲容量上限。超出后丢弃最旧的一条。 */
    const val DEFAULT_CAPACITY: Int = 200

    /** 单条消息的保留上限：防止把整段模型输出/工具输出塞进日志导致内存膨胀。 */
    private const val MAX_MESSAGE_CHARS: Int = 400

    private val lock = Any()

    // 用 kotlin.collections.ArrayDeque：它的「带初始容量」主构造器是 internal（跨模块不可见），
    // 所以这里只能用无参构造器。容量上限由 DEFAULT_CAPACITY 在写入处强制。
    private val buffer = ArrayDeque<AgentLog>()

    /**
     * 兜底脱敏：即便将来有人写错调用点，也不让凭据落进日志。三类模式：
     *
     *  1. `Authorization: Bearer xxx` —— 请求头形态（整体替换，不保留原值）。
     *  2. `sk-xxxxxxxx` —— OpenAI 风格的裸 key（常见于异常消息里）。
     *  3. `?key=xxx` / `&api_key=xxx` / `token: xxx` —— **query 参数形态**。
     *     这类最容易漏：本仓库的 `RemoteEndpoint.name` 在为空时会回退成 `baseUrl`
     *     （见 SettingsViewModel.onSaveEndpoint），而 baseUrl 完全可能带 `?key=` 查询串；
     *     引擎文案 `"${remote.name} 需要填写 API Key"` 又会把这个 name 带进异常消息，
     *     于是 `t.message` 被日志记录时就成了一条凭据泄露路径。
     *     第 3 类**只替换参数值、保留参数名与分隔符**，脱敏后仍能看出「是哪个参数」。
     *
     * 大小写不敏感（`Bearer` / `bearer`、`API_KEY` / `api_key` 都覆盖）。
     * 这只是**最后一道保险**，不是主要手段 —— 正确做法是调用点根本不传入密钥、
     * 请求头或带凭据的 URL（见各处埋点的注释）；也**不应该**为了日志安全让业务侧少说话
     * （例如删掉 `${remote.name}` 那句引擎文案），防护应该在出口。
     */
    private val secretPattern = Regex(
        "Bearer\\s+\\S+" +
            "|sk-[A-Za-z0-9_-]{8,}" +
            "|(\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|token" +
            "|secret|password|passwd|pwd|credential|key)\\b[\"']?\\s*[=:]\\s*[\"']?)([^\\s\"',;&}\\]]+)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 可选的进程外出口（见 [AgentLogSink]）。由上层在启动时注入；`null` 表示纯内存模式。
     *
     * `@Volatile`：`setSink` 在启动线程调用，`record` 可能在任意线程读。
     */
    @Volatile
    private var sink: AgentLogSink? = null

    /**
     * 安装 / 卸载日志出口。传 `null` 即卸载（测试与「纯内存模式」用）。
     *
     * 只影响「是否额外转发」，**不改变**缓冲区的容量与语义：无论有没有出口，
     * 日志都会照常进内存缓冲。
     */
    fun setSink(sink: AgentLogSink?) {
        this.sink = sink
    }

    fun info(message: String) = record(AgentLogLevel.INFO, message)

    fun warn(message: String) = record(AgentLogLevel.WARN, message)

    fun error(message: String) = record(AgentLogLevel.ERROR, message)

    fun record(level: AgentLogLevel, message: String) {
        val entry = AgentLog(
            level = level,
            message = sanitize(message),
            atMillis = System.currentTimeMillis(),
        )
        synchronized(lock) {
            if (buffer.size >= DEFAULT_CAPACITY) dropOneFor(entry)
            buffer.addLast(entry)
        }
        // 出口回调放在**锁外**：实现方要做文件 IO，绝不能持锁执行。
        // 先取到本地引用再调用，避免调用途中 sink 被替换造成的可见性问题。
        val target = sink ?: return
        // 兜底 try/catch：record() 会在 AgentRunner 的 catch 块里被调用，
        // 出口一旦抛异常就会把原始错误顶掉、甚至让工具执行路径崩掉。
        // 日志设施绝不能反过来影响主流程。
        runCatching { target.onLog(level, entry.message, entry.atMillis) }
    }

    /**
     * 缓冲满时腾一个位置给 [entry]，**优先丢 INFO**（调用方必须持有 [lock]）。
     *
     * 为什么需要它：本地推理每条工具调用、每次上下文压缩都会写 INFO，200 条缓冲很快
     * 被 INFO 填满；若一律丢最旧的一条，那么**崩溃前刚发生的 WARN/ERROR 会被 INFO 挤掉** ——
     * 恰恰是「用户来诊断页看日志」时最该看到的那几条没了。本类的存在意义就是保住它们。
     *
     * 因此：WARN/ERROR 进来时先找最旧的一条 INFO 丢掉；只有当缓冲里全是 WARN/ERROR
     * （那说明确实一直在报错）才退化成丢最旧一条。INFO 进来时照旧丢最旧一条 ——
     * INFO 之间互相淘汰，不会占用 WARN/ERROR 的位置。
     *
     * 用「整表重建」而不是 `iterator.remove()`：kotlin 的 `ArrayDeque` 迭代器是否支持
     * `remove()` 没有保证，而这里是出错路径，绝不能再抛异常；缓冲只有 200 条，重建代价可忽略。
     */
    private fun dropOneFor(entry: AgentLog) {
        if (entry.level == AgentLogLevel.INFO) {
            buffer.removeFirst()
            return
        }
        var victim = -1
        for (i in buffer.indices) {
            if (buffer[i].level == AgentLogLevel.INFO) {
                victim = i
                break
            }
        }
        if (victim < 0) {
            buffer.removeFirst()
            return
        }
        val kept = ArrayList<AgentLog>(buffer.size - 1)
        for (i in buffer.indices) {
            if (i != victim) kept.add(buffer[i])
        }
        buffer.clear()
        buffer.addAll(kept)
    }

    /**
     * 按时间**正序**（最旧 → 最新）返回最近 [limit] 条。
     *
     * @param limit <= 0 时返回空列表；大于当前条数时返回全部。
     */
    fun recent(limit: Int = DEFAULT_CAPACITY): List<AgentLog> {
        if (limit <= 0) return emptyList()
        // 用表达式求值而不是在锁内 return：块体函数 + inline 块内非局部 return 的
        // 「一定返回」判定容易踩坑，写成有值表达式最稳。
        return synchronized(lock) {
            if (buffer.isEmpty()) {
                emptyList()
            } else {
                buffer.drop((buffer.size - limit).coerceAtLeast(0))
            }
        }
    }

    /** 当前已保留的条数（上限 [DEFAULT_CAPACITY]）。 */
    fun size(): Int = synchronized(lock) { buffer.size }

    /** 清空缓冲（供诊断页/测试使用）。 */
    fun clear() = synchronized(lock) { buffer.clear() }

    /**
     * 给**要显示给用户看**的文本做脱敏 —— 与进日志的是同一套规则（复用下面的 [sanitize]）。
     *
     * 为什么必须有它：上屏的文本和进日志的文本**风险等级不同**。日志留在设备上的私有目录里，
     * 而上屏的文案会进截图、进录屏、进用户发给我们的 bug report —— 相当于主动外发。
     * 真实的泄露路径已经存在：远程引擎的异常文案是 `"${remote.name} 需要填写 API Key"`，
     * 而 `RemoteEndpoint.name` 为空时会回退成 `baseUrl`，用户自建反代的 baseUrl 完全可能
     * 带 `?key=sk-xxxx`；这条异常消息被直接上屏，凭据就跟着截图走了。
     *
     * 覆盖三类：`Authorization: Bearer xxx`、`sk-xxxxxxxx`、以及
     * `?key=` / `&api_key=` / `token: xxx` 这类 query 参数形态（**只替换值、保留参数名**，
     * 脱敏后仍看得出是哪个参数漏了）。
     *
     * 注意：它会顺带按 [MAX_MESSAGE_CHARS]（400 字符）截断并补 "…"。对错误对话框是合适的
     * （异常消息本来就不该整段糊在屏幕上），但如果将来要展示**完整**文本，别用这个入口。
     *
     * 这个方法是**纯函数**：不写缓冲、不触发 sink，可以在任意线程调用。
     */
    fun sanitizeUserFacing(raw: String): String = sanitize(raw)

    /** 脱敏 + 截断。两条都是为了「日志本身不能变成新的内存/安全风险」。 */
    private fun sanitize(raw: String): String {
        val redacted = secretPattern.replace(raw) { match ->
            // group 1 只有 query 参数形态参与匹配：它保存了「参数名 + 分隔符 + 可能的引号」，
            // 把它原样保留、只把值换成 ***，脱敏后依然能看出是哪个参数漏了。
            val prefix = match.groupValues[1]
            when {
                prefix.isNotEmpty() -> prefix + "***"
                match.value.startsWith("sk-", ignoreCase = true) -> "sk-***"
                else -> "Bearer ***"
            }
        }
        return if (redacted.length > MAX_MESSAGE_CHARS) {
            redacted.take(MAX_MESSAGE_CHARS) + "…"
        } else {
            redacted
        }
    }
}
