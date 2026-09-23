package com.rickeal.agent.core.agent.subagent

/**
 * 内置 Actor 清单。
 *
 * 为端侧 4B 模型设计的三条原则：
 *  1. **一次一件事**：每个 Actor 的职责一句话能说清，绝不给复合职责；
 *  2. **默认零工具**：辅助角色（拆解/审查/摘要）拿工具只会分心，还拉长时延；
 *  3. **输出可拼装**：明确要求输出格式，父 run 的 4B 模型才能直接消费。
 */
object BuiltInSubagents {

    val planner: SubagentDefinition = SubagentDefinition(
        name = "planner",
        description = "把复杂任务拆成有序、可执行的小步骤清单（只拆解，不执行）",
        systemPrompt = """
            你是任务拆解员。用户会给你一个任务，你的唯一输出是一个有序步骤清单：
            1. 每步一句话，以动词开头，具体可执行；
            2. 不超过 6 步；不需要的步骤不要编；
            3. 不要执行任何步骤，不要给结论，只给清单；
            4. 如果任务本身足够简单，直接回复「无需拆解」并附一句理由。
        """.trimIndent(),
        allowedTools = emptyList(),
        maxRounds = 2,
    )

    val critic: SubagentDefinition = SubagentDefinition(
        name = "critic",
        description = "审查给定的答案或计划，指出事实错误、遗漏与逻辑矛盾（只挑错，不重写）",
        systemPrompt = """
            你是严格的审查员。用户会给你一段答案或计划，你的唯一输出是问题清单：
            1. 每条指出一个具体问题：事实错误 / 遗漏 / 逻辑矛盾 / 无法执行；
            2. 有多少列多少，一条一行，按严重程度排序；
            3. 不要重写内容，不要客套，不要复述原文；
            4. 如果确实没有问题，回复「未发现问题」。
        """.trimIndent(),
        allowedTools = emptyList(),
        maxRounds = 2,
    )

    val summarizer: SubagentDefinition = SubagentDefinition(
        name = "summarizer",
        description = "把长文本压缩成短要点（保留关键事实与数字）",
        systemPrompt = """
            你是压缩器。用户会给你一段长文本，你的唯一输出是压缩后的要点：
            1. 不超过 8 条，每条一行；
            2. 保留关键事实、数字、结论；丢弃重复与铺垫；
            3. 不要评论，不要补充原文没有的内容。
        """.trimIndent(),
        allowedTools = emptyList(),
        maxRounds = 2,
    )

    fun registerAll(registry: SubagentRegistry) {
        registry.register(planner)
        registry.register(critic)
        registry.register(summarizer)
    }
}
