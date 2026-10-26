package com.rickeal.agent.core.agent.tools

/**
 * `file_list` 的 path 归一化（W84-06a）：省略 / 纯空白 ⇒ `"."`（沙箱根目录）。
 *
 * 为什么不能只靠 [stringArg] 的 `default` 参数：它的默认值是**空串**，而
 * [SandboxedFileTool.resolveSafe] 对空路径是 **fail-closed 拒绝**（「路径为空」）——
 * 两者语义正好相反。spec 已把 path 声明为 `required = false` 且描述写「默认根目录」，
 * 所以「省略」必须由本函数落成 `"."`，否则这条被承诺的用法永远失败。
 *
 * 为什么用 `"."` 而不是在 resolveSafe 里加「空路径特例」：`File(base, ".").canonicalFile`
 * 就是沙箱根本身，走 resolveSafe 既有的 `target.path == basePath` 分支天然通过 ——
 * 给安全护栏开「空路径放行」的口子会削弱它对所有工具的 fail-closed 语义。
 *
 * 独立成 `internal` 顶层函数以便 JVM 单测：`FileListTool` 需要 [ToolContext]（含 android
 * `Context`），纯 JVM 测试无法构造（本模块无 Robolectric），故把可测的那半边外提。
 *
 * W84-20：自 [FileTools] 拆出（该行数表 #25 家族第 15 员已触顶 600，纯函数先外提减压）。
 * 保持同包 `internal` 顶层，原调用点与 `FileListPathArgTest` 无需改动。
 */
internal fun fileListPathArg(rawPath: String): String = rawPath.trim().ifEmpty { "." }
