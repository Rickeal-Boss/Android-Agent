package com.rickeal.agent.core.engine

import java.util.concurrent.ConcurrentHashMap

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
 * 把「实例当前韧性状态」与「store 快照」按**单调合并**语义合成新状态
 *（W56 读回语义的纯函数化，W57 外提）。
 *
 * 单调不变式：
 *  - [EngineResilienceState.nativeToolsRejected] 取 **OR**（一旦证伪**永不回退**）；
 *  - [EngineResilienceState.templateRebuildCount] 取 **max**（计数**只增不减**）。
 *
 * ⇒ 无论 store 快照与实例字段如何错位（并发写 / 旧快照 / cid 切换），读回**永远不会把
 * 实例状态拉退**。这正是 `LiteRtLmEngine.adoptResilienceFromStore` 的承重语义，也是
 * `LiteRtLmEngine.generateStream` 软熔断闸门「max(实例字段, store)」的同一口径。
 *
 * 外提动机（W57）：`LiteRtLmEngine` 是 native 重类，JVM 里**不能**实例化 ⇒ 合并语义
 * 原本零 JVM 覆盖（W56 审查 P2）。外提为**纯函数**后，OR/max 方向、单调性、幂等性
 * 可直接 JVM 断言（见 `EngineResilienceMergeTest`）。
 *
 * 契约：**纯函数** —— 不读写 store、无副作用；只做 data class 运算。
 *
 * ⚠️ 边界（如实申报）：本函数只钉**合并语义**，钉不住引擎**调用点存在性**
 *（调用点是否仍被 `adoptResilienceFromStore` / 熔断闸门行使属引擎方法体，纯函数测试
 * 覆盖不到；二者互补）。
 */
internal fun mergeResilienceState(
    instance: EngineResilienceState,
    snapshot: EngineResilienceState,
): EngineResilienceState = EngineResilienceState(
    nativeToolsRejected = instance.nativeToolsRejected || snapshot.nativeToolsRejected,
    templateRebuildCount = maxOf(instance.templateRebuildCount, snapshot.templateRebuildCount),
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
 * ⚠️ **「cid 键隔离」与「单调合并」的张力（W57 补）**：store 快照按 **cid 键天然隔离**
 *（换会话 = 新键，互不污染）；但引擎**实例字段**的读回是**单调合并**（[mergeResilienceState]：
 * 证伪 OR 不回退、计数 max 只增），且实例字段**只在 `releaseInternal` 复位、换 cid 不复位**
 * ⇒ **同一引擎实例先后服务多个 cid 时，前一个 cid 的证伪置位会被单调合并带进后一个 cid**
 *（后一会话直接走文本协议）。当前「换模型 / 换会话」以**新建实例**为主路径，故该残留可接受；
 * 若将来支持**同实例热切会话/模型**，须把键扩成 `(cid, model)` 复合键**或在换 cid 时复位
 * 实例字段**（另立改动，勿顺手做）。
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

    private val states = ConcurrentHashMap<String, EngineResilienceState>()

    override fun read(cid: String): EngineResilienceState = states[cid] ?: EngineResilienceState()

    override fun write(cid: String, state: EngineResilienceState) {
        states[cid] = state
    }
}
