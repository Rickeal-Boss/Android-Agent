package com.rickeal.agent.core.model

/**
 * 代理对安全截断（Wave 35 D1）—— 全仓「按字符数砍一刀」的**唯一**口径。
 *
 * ## 为什么不能直接 `String.take(n)`
 *
 * Kotlin 的 [String.take] 按 **UTF-16 code unit** 计数，而 emoji、部分 CJK 扩展汉字、
 * 音乐符号等都是**代理对**（一个 Unicode 码点占两个 code unit）。当 `n` 恰好落在代理对
 * 的**中间**时，`take(n)` 会留下一个孤立的高位代理 —— 它在落库（memory.json / journal）、
 * 回灌提示词、上屏渲染三个环节都会变成 U+FFFD（问号方块）。用户看到的事实是
 * 「工具输出末尾有个乱码方块」，且无从判断是内容本身坏了还是显示坏了。
 *
 * 工具输出截断是全仓**最高频的用户可见截断路径**（每轮几乎都有），所以这不是
 * 「理论正确性问题」而是日常可见的显示缺陷。
 *
 * ## 与 `take` 的差异（只有两处，其余逐字保持）
 *
 *  - **计数口径不变**：仍按 UTF-16 code unit 计数，**刻意不改**成按码点计数 ——
 *    改成码点会让各处既有预算（4000 / 1200 / 80 …）在 emoji 密集内容上整体缩水近半，
 *    那是未被评估的行为变化，不属于本波机械保守范围；
 *  - ① 尾部落在**孤立高位代理**上（低位被切走）→ 回退一格；
 *  - ② 尾部是**孤立低位代理**（输入本身已是病态串）→ 同样回退一格。
 *    两条合起来保证返回值**恒为合法 UTF-16**，调用方不必再自行判一次；
 *  - `max <= 0` → 空串；`length <= max` → 原样返回（零分配，与 `take` 一致）。
 *
 * 为什么「丢半个码点」比「留半个码点」好：丢掉的是一个**字形**（少显示一个 emoji，
 * 读者不会察觉缺了），留下的是**一个 U+FFFD**（读者读成「输出损坏 / 工具坏了」）。
 * 两个方向的代价不对称，取丢的那一侧。
 */
fun String.truncateSafe(max: Int): String {
    if (max <= 0) return ""
    if (length <= max) return this
    // 此处 max >= 1 且 length > max ⇒ head 必非空，last() 安全。
    val head = take(max)
    // ① 高位代理结尾：它必然是半个代理对（配对的低位在下标 max 处被切走）。
    if (head.last().isHighSurrogate()) return head.dropLast(1)
    // ② 低位代理结尾且前一位不是高位：孤立低位（输入本身病态），同样是半个码点。
    //    max == 1 时没有前一位可比，直接认定为孤立。
    if (head.last().isLowSurrogate() && (max < 2 || !this[max - 2].isHighSurrogate())) {
        return head.dropLast(1)
    }
    return head
}
