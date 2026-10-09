package com.rickeal.agent.core.engine

/**
 * 跨引擎实例存活的会话韧性状态快照（W56）。
 *
 * 只承载「跨实例**必须**存活」的两个字段：
 *  - [nativeToolsRejected]：原生工具通道已被证伪。若随 evict 清零，每次引擎重建
 *    （AgentRunner 首败即 evict+新建）都会重走一遍必炸路径（W55 审查 P2#1）。
 *  - [templateRebuildCount]：模板渲染失败累计重建计数，软熔断的数据面。
 *
 * ⚠️ 刻意**不**承载 `conversationDirty` / `awaitingNativeToolResponse`：二者是
 * 「当前 native conversation」的实例生命周期语义，跨实例持久反而引入 stale 置位
 *（「置位载体活太久也是坑」—— W55 坑 5 的反向应用，W56 方案 §2.2 明文裁定）。
 */
data class EngineResilienceState(
    val nativeToolsRejected: Boolean = false,
    val templateRebuildCount: Int = 0,
)

/**
 * 进程级会话韧性 store（W56，治 W55 审查 P2#1「自愈置位随旧实例清零」）。
 *
 * 生命周期：**进程级** —— 键值随进程存活，跨引擎实例（含 `DefaultEngineFactory.evict`
 * 后的重建）存活；**不做磁盘持久化**（进程死亡即清零：native 进程重启后重新探测 /
 * 重新给机会是合理语义，无需跨启动恢复）。
 *
 * 键：会话 `cid`（conversation id）。换会话即新键，天然隔离，互不污染。
 *
 * ⚠️ 已知语义边界（如实申报）：键只含 cid、不含模型身份 —— 同一会话中途换模型，
 * 旧模型的证伪置位仍会读回。当前 app 侧换模型走「新建会话」为主路径，该残留可接受；
 * 若将来支持「同会话热切换模型」，需把键扩成 `(cid, model)` 复合键（另立改动，勿顺手做）。
 *
 * 线程安全：实现必须可在引擎 IO 线程与调用方线程并发读写。
 *
 * 内存面：每会话一个快照对象（几十字节级），进程生命周期内会话数量级有限，
 * 不做淘汰（如实申报：长驻进程 + 海量会话才有淘汰需求，当前无此场景）。
 */
interface EngineResilienceStore {
    /** 读回 [cid] 的韧性状态；从未写入过时返回默认值（未证伪 / 计数 0）。 */
    fun read(cid: String): EngineResilienceState

    /** 整体覆盖写入 [cid] 的韧性状态（快照语义，无部分写）。 */
    fun write(cid: String, state: EngineResilienceState)
}

/**
 * [EngineResilienceStore] 的进程级内存实现（W56）。
 *
 * [ConcurrentHashMap] 保证单键读写原子；快照整体替换（write 覆盖整个
 * [EngineResilienceState]），不存在「两个字段分别写」的撕裂窗口。
 * 由 AppContainer 构造并注入 `DefaultEngineFactory`（手写 DI，简报 §6 禁 Hilt/Koin）。
 */
class ProcessEngineResilienceStore : EngineResilienceStore {

    private val states = java.util.concurrent.ConcurrentHashMap<String, EngineResilienceState>()

    override fun read(cid: String): EngineResilienceState = states[cid] ?: EngineResilienceState()

    override fun write(cid: String, state: EngineResilienceState) {
        states[cid] = state
    }
}
