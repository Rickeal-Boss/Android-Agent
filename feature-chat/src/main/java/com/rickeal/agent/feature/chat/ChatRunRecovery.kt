package com.rickeal.agent.feature.chat

import com.rickeal.agent.core.agent.journal.AgentRunJournal
import com.rickeal.agent.core.model.AgentLogStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// W65 X3 零行为风险迁移：journal 恢复三函数自 ChatRunCoordinator.kt 整段搬移（连 KDoc）。
//
//  1. [offerJournalRunRecovery]（原 `ChatRunCoordinator.maybeOfferRecovery`）——
//     开会话扫 journal，「没跑完就被进程死亡打断」的 run 出恢复卡；
//  2. [dismissJournalRunRecovery]（原 `ChatRunCoordinator.dismissRecovery`）——
//     处置恢复卡（丢弃 / 被新 run 顶替）：markDismissed + 归档其余未 settled journal；
//  3. [archiveOtherUnsettled]（原 `ChatRunCoordinator.archiveOtherUnsettled`）——
//     目录内除 keepRunId 外的未 settled journal 归档（改名不删）。
//
// 为什么是「传参顶层函数 + 类内 facade」而非扩展函数：三函数仅依赖 `container.journalRoot` /
// `scope` / `uiState` 三个类字段，**无深层私有状态**（不碰 runJob / persistState /
// 流式缓冲等 run 编排状态）⇒ 提为显式传参即可零行为迁移。两个外部调用点
// （ChatViewModel `run.maybeOfferRecovery` / `run.dismissRecovery`）经类内 facade 保持
// 原签名与调用形态不变 —— facade 只把类字段喂给顶层函数，无任何逻辑；`onRecover` 内对
// `archiveOtherUnsettled` 的调用点文本不变（新顶层函数同名同签）。

/**
 * 打开会话后扫描 journal：有「没跑完就被进程死亡打断」的 run 就出恢复卡。
 *
 * 这里的 `runCatching` **不需要**挡 `CancellationException`：`findUnsettled` 不是
 * suspend，取消只可能来自外层 `withContext(Dispatchers.IO)` 的调度边界，而那个
 * 异常抛在 `runCatching` **之外**、会正常向上传播（逐处判断，不机械加判据）。
 */
internal suspend fun offerJournalRunRecovery(
    journalRoot: File,
    cid: String,
    uiState: MutableStateFlow<ChatUiState>,
) {
    val dir = File(journalRoot, cid)
    val unsettled = withContext(Dispatchers.IO) {
        runCatching { AgentRunJournal.findUnsettled(dir) }.getOrNull()
    } ?: return
    uiState.update {
        it.copy(recovery = RecoveryOffer(runId = unsettled.runId, messageCount = unsettled.messageCount))
    }
}

/**
 * 把目录里除 [keepRunId] 之外的所有「未 settled」journal 归档。
 *
 * findUnsettled 只返回最近的一个，其余未完成 run 会永远滞留 —— 每次进会话都
 * 再弹一张恢复卡，用户处置完最新的又来一张。归档（改名）而非删除：过程记录
 * 可能还有排查价值；已 settled 的正常 run 与空文件不动。
 *
 * 三条内层 `runCatching` 都只包**非 suspend** 的盘操作（listFiles / readLines /
 * renameTo），不可能抛 `CancellationException`；本函数是 suspend，取消由调用方
 * [dismissJournalRunRecovery] 统一透传。
 */
internal suspend fun archiveOtherUnsettled(runDir: File, keepRunId: String) = withContext(Dispatchers.IO) {
    runCatching {
        val files = runDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return@runCatching
        for (file in files) {
            val name = file.name
            if (name == "$keepRunId.jsonl") continue
            if (name.endsWith(".dismissed.jsonl") || name.endsWith(".jsonl.dismissed")) continue
            val lines = runCatching {
                AgentRunJournal.open(runDir, name.removeSuffix(".jsonl")).readLines()
            }.getOrDefault(emptyList())
            if (lines.isEmpty() || lines.last().kind == AgentRunJournal.KIND_SETTLED) continue
            // renameTo 失败**返回 false 而不抛异常** ⇒ 只包 runCatching 抓不到 ⇒ 归档静默失败
            // （该 run 的恢复卡下次进会话仍会复活）。必须显式判返回值；仍保留 runCatching 兜
            // renameTo 可能抛的 SecurityException（同族正确形态见 SegmentedHistoryStore /
            // AgentRunJournal / FileTools）。
            val archived = runCatching {
                file.renameTo(File(file.parentFile, file.nameWithoutExtension + ".dismissed.jsonl"))
            }.getOrDefault(false)
            if (!archived) {
                AgentLogStore.warn(
                    "恢复卡归档失败：renameTo 失败（返回 false 或抛异常）（file=${file.name}）" +
                        "—— 该 run 的恢复卡下次进会话仍会复活",
                )
            }
        }
    }.getOrDefault(Unit)
}

/**
 * 处置当前恢复卡（丢弃或被新 run 顶替）：归档目标 journal + 其余未完成 run。
 *
 * 「被新 run 顶替」是 Wave3 补的口子：恢复卡挂着时用户直接发了新消息 = 用行动
 * 表示「不恢复」，卡必须归档 —— 不然它留在状态里，下次进会话又弹出来，而且
 * 那份 journal 的 user_input 已经过时（上下文会拼出新 run 之前的状态）。
 *
 * [archiveOtherUnsettled] 是 suspend，所以外层 `runCatching` 会连
 * `CancellationException` 一起吞掉 —— 这里显式透传，保持与 onSend / onRetry
 * 同一约定（取消不是错误，但取消必须**被看见**）。
 */
internal fun dismissJournalRunRecovery(
    scope: CoroutineScope,
    journalRoot: File,
    offer: RecoveryOffer,
    cid: String,
) {
    scope.launch(Dispatchers.IO) {
        runCatching {
            val dir = File(journalRoot, cid)
            AgentRunJournal.open(dir, offer.runId).markDismissed()
            archiveOtherUnsettled(dir, offer.runId)
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            // C6（Wave 50）：其余失败原先**静默吞掉** —— 恢复卡 UI 已清（调用方 VM 在
            // 调本函数前已 `recovery = null`）但 journal 未 markDismissed ⇒ 下次进会话
            // 恢复卡**复活**，用户完全无从得知。补一条诊断日志（与 R-E `熔断救援未落库`
            // 同一出口，本文件既有约定），把「卡没了但还会回来」变成可查事实。
            // ⚠️ **W50 P3-1 提级**：`info` → `warn`。这条不是「值得知道一下」而是
            // **数据不一致已发生**（UI 已清、盘上未归档 ⇒ 下次进会话恢复卡复活），
            // 与 `AgentLogStore` 的 info/warn 分级约定对齐：warn = 本应成功而失败、
            // 需要人介入排查；info = 常规流水。**仅观测**：失败仍不重试、不冒泡
            // （调用方 fire-and-forget），本条提级**不改任何行为**，只改可检索性。
            AgentLogStore.warn(
                "恢复卡归档失败（journal 未 markDismissed）：runId=${offer.runId} " +
                    "cid=$cid —— ${throwable.message ?: throwable::class.simpleName}",
            )
        }
    }
}
