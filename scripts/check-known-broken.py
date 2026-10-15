#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守卫：ModelPresets.kt 里 knownBrokenOn 非空的预设不得 recommended（W63 刀C，第 34 条）。

为什么需要这条守卫：
  `ModelPreset.knownBrokenOn` 驱动模型卡红字硬提示与加载前确认对话框 —— 它声明
  「该容器在当前运行时已知不可用」。而 `recommended = true` 会进「一键获取模型」
  对话框的首批推荐（recommendedFirst 置顶）。两者同真 = 「一边告诉用户它坏了、
  一边把它排在最前面推荐」的自相矛盾：坏容器必须是「想试的人自己找」，不能由
  产品替用户做决定。

不变量：`knownBrokenOn != null` ⇒ `recommended == false`。

契约（与 scripts/arch-guard.sh 的 check() 一致）：
  - **stdout 非空 = 违规**；干净时**必须零输出**。
  - 永远 exit 0（脚本自身异常时把 traceback 打到 stdout，从而被判红而不是静默通过）。
  - fail-closed：文件缺失 / 文件内不存在 `val knownBrokenOn` 声明（字段被删 =
    守卫面失效）都输出违规行，绝不能静默通过。

用法：python scripts/check-known-broken.py <ModelPresets.kt 路径>
     （在仓库根目录执行；自测网以 cwd=脚手架 运行，路径随 argv 传入）

---------------------------------------------------------------------------
W69 A3：块解析迁移到共享模块 `scripts/preset_blocks.py`（外部审查第十四审 H1）
---------------------------------------------------------------------------
本脚本原先自带一份与 #39（arch-guard.sh 内联 awk）/ #41（check-preset-profile-parity.py）
几乎逐字重复的块解析实现（`SINGLE_LINE` / `MULTI_OPEN` / `OPEN_HEAD` / `NEXT_PAREN` /
`BLOCK_CLOSE` + `strip_comment` / `blank_strlits` / `opens_block` / `split_blocks`）。
W68 三轮修复每次都要**手工同步三处**（`blank_strlits`、括号换行、字段级置空）——
本波把该纪律变结构：删除重复实现，改 `import preset_blocks` 消费同一解析源。
判定逻辑（`knownBrokenOn` 非空 ∧ `recommended=true` ⇒ 红）与契约**逐字保持**。

迁移同时顺带获得模块的两项加固（对应外部审查复审24 P1-① / P2-①）：
  · 块开启判据改为**行内具名位匹配**（非行首锚定）+ 排除前一词为 `class` 的匹配
    ⇒ 修 `val x = ModelPreset(` / `items = listOf(ModelPreset(` 这类**赋值/表达式前缀
    形态**的整块静默逃逸；`data class ModelPreset(` / `ModelPresetMirror(` 仍不命中。
  · 新增**块注释剥离**（`/* … */`，可跨行）⇒ 修「块内 KDoc 散文里的
    `knownBrokenOn = "x"` 被误判违规」的假红面（旧实现只剥行内 `//`）。
  · 字段提取**锚定具名实参位**（在 `blank_strlits` 后的代码视图上定位，值回原行取）
    ⇒ 字符串内容里嵌的同款字样不再骗过判定（等效于原 `blank_strlits` 面，且更精确）。

迁移前既有修补记录（判据语义，均由共享模块承接）：
  W68 A1（复审22 P2-①）：统一开启判据（形态A 换行续写 / 形态B 括号前空格）+ 同缩进
    `),` 收尾 + 去 `KNOWN_BROKEN_SET` 行首锚定。
  W68 A1-R1（严质衡复审 P1）：字符串字面量内容置空后判定，防 note 散文假红。
  W68 A3-R1（严质衡 A2/A3 复审 f6）：括号换行形态（`ModelPreset` 与 `(` 分行）。
"""
import re
import sys
import traceback

import preset_blocks as pb

# 字段声明存在性判据（fail-closed：字段被删 = 守卫面失效）。
DECL = re.compile(r"val knownBrokenOn:\s*String\?")


def main(argv):
    if len(argv) < 2:
        print("check-known-broken.py 缺少 ModelPresets.kt 路径参数（守卫面失效）")
        return
    path = argv[1]
    try:
        lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    except OSError as e:
        print("check-known-broken.py 读不到 %s：%s（守卫面失效，按违规处理）" % (path, e))
        return
    text = "\n".join(lines)
    if not DECL.search(text):
        print("%s 不存在 `val knownBrokenOn: String?` 声明（字段被删 = 第 34 条守卫面失效）" % path)
        return

    # 切块：共享模块（行内具名位开启判据 + 同缩进 `),` 收尾 + 括号换行 + 块注释剥离）。
    blocks = pb.parse_blocks(lines)
    if not blocks:
        print("%s 未找到任何 ModelPreset( 构造块（守卫面失效，按违规处理）" % path)
        return

    for b in blocks:
        f = b["fields"]
        # knownBrokenOn 非空（字段值非 None；显式 null / 缺省都算空）
        kb = f.get("knownBrokenOn")
        if kb is None or kb[0] is None:
            continue
        # recommended == true
        rec = f.get("recommended")
        if rec is None or rec[0] != "true":
            continue
        label = b["label"] if b["label"] is not None else "<未取到 label>"
        print(
            "%s:%d 预设「%s」knownBrokenOn 非空但 recommended=true —— "
            "已知坏容器不得置顶推荐（第 34 条不变量：knownBrokenOn != null ⇒ recommended == false）"
            % (path, b["start_line"], label)
        )


if __name__ == "__main__":
    try:
        main(sys.argv)
    except Exception:
        # 契约要求「stdout 非空 = 违规」⇒ 自身异常也必须打到 stdout，绝不能静默通过
        print("check-known-broken.py 自身执行失败（按违规处理，防静默失效）：")
        traceback.print_exc(file=sys.stdout)
    sys.exit(0)
