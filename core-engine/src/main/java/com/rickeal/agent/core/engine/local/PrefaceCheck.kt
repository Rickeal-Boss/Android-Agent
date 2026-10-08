package com.rickeal.agent.core.engine.local

import java.util.Locale

/**
 * preface 校验用的**窗口长度**（Wave 33）：systemText 归一化后取前 64 个字符做子串匹配。
 *
 * 取 64 的理由：系统提示词开头是固定的角色/行为约束段，64 个归一化字符足以区分
 * 「渲染进去了」与「渲染丢了」，又不至于要求整段提示词逐字出现在 preface 里
 * （native 模板可能在提示词前后插入自己的标记）。
 */
private const val PREFACE_CHECK_WINDOW = 64

/**
 * [prefaceContainsSystem] 用的归一化（Wave 33）：lowercase(Locale.ROOT) 后仅保留
 * 字母与数字字符。
 *
 * 纯函数（文件级 internal，可被单测直接调）。与 Wave 24 判定⑨回显指纹的归一化
 * 同一口径 —— 模板插入的换行/标点/空白噪声必须被吃掉，否则校验会因格式差异误报。
 */
internal fun normalizeForPrefaceCheck(s: String): String =
    s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

/**
 * preface（systemInstruction + initialMessages 在 native chat template 下的实际渲染
 * 结果）是否包含系统提示词正文（Wave 33）。
 *
 * 判定语义：取 [systemText] 归一化后的**前 [PREFACE_CHECK_WINDOW] 个字符**作为窗口，
 * 判断它是否为 [preface] 归一化串的子串。窗口语义两侧必须同读：
 *  - systemText 归一化后不足 64 字符 → 窗口退化为**全串**匹配；
 *  - 负例的正确形态是「preface 只含有窗口前缀的一部分」（如仅前 40 个归一化字符
 *    被渲染进来）—— 此时窗口（64 字符）不可能是 preface 的子串，判 false；
 *  - systemText 归一化为空（无系统提示词）→ 无事可校验，恒 true。
 *
 * 纯函数（文件级 internal，可被单测直接调）。返回 false = 「角色通道第三态」
 * （createConversation 成功但模板渲染丢失 system），调用方据此做中档回退。
 */
internal fun prefaceContainsSystem(preface: String, systemText: String): Boolean {
    val normalizedSystem = normalizeForPrefaceCheck(systemText)
    if (normalizedSystem.isEmpty()) return true
    val window = normalizedSystem.take(PREFACE_CHECK_WINDOW)
    return normalizeForPrefaceCheck(preface).contains(window)
}
