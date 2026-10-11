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
"""
import re
import sys
import traceback

# 每个 ModelPreset( 构造块从「trim 后恰为 ModelPreset(」的行开始，到下一个同款行
# （或文件尾）结束。data class ModelPreset( 声明行 trim 后是 "data class ModelPreset("，
# 不命中 —— 与真实文件、脚手架 fixture 同款。
BLOCK_START = re.compile(r"^ModelPreset\($")
# 非空赋值形态：knownBrokenOn = "…"（null 显式写法 `knownBrokenOn = null` 与缺省都算空）。
KNOWN_BROKEN_SET = re.compile(r"^\s*knownBrokenOn\s*=\s*\"")
RECOMMENDED_TRUE = re.compile(r"^\s*recommended\s*=\s*true\b")
LABEL = re.compile(r"^\s*label\s*=\s*\"([^\"]*)\"")
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

    # 切块：从每个「trim 后恰为 ModelPreset(」的行到下一个同款行。
    starts = [i for i, raw in enumerate(lines) if BLOCK_START.match(raw.strip())]
    blocks = []
    for k, s in enumerate(starts):
        e = starts[k + 1] if k + 1 < len(starts) else len(lines)
        blocks.append((s, lines[s:e]))
    if not blocks:
        print("%s 未找到任何 ModelPreset( 构造块（守卫面失效，按违规处理）" % path)
        return

    for s, block in blocks:
        broken = any(KNOWN_BROKEN_SET.match(raw) for raw in block)
        if not broken:
            continue
        recommended = any(RECOMMENDED_TRUE.match(raw) for raw in block)
        if not recommended:
            continue
        label = next((m.group(1) for m in (LABEL.match(raw) for raw in block) if m), "<未取到 label>")
        line_no = s + 1
        print(
            "%s:%d 预设「%s」knownBrokenOn 非空但 recommended=true —— "
            "已知坏容器不得置顶推荐（第 34 条不变量：knownBrokenOn != null ⇒ recommended == false）"
            % (path, line_no, label)
        )


if __name__ == "__main__":
    try:
        main(sys.argv)
    except Exception:
        # 契约要求「stdout 非空 = 违规」⇒ 自身异常也必须打到 stdout，绝不能静默通过
        print("check-known-broken.py 自身执行失败（按违规处理，防静默失效）：")
        traceback.print_exc(file=sys.stdout)
    sys.exit(0)
