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
W68 A1 修补（复审22 P2-①；双修复逃逸形态，selftest case61d 钉触发面）
---------------------------------------------------------------------------
旧实现的三个逃逸/错位面（脚手架实测，现役守卫全绿或错位红）：
  ① 块开启判据 = trim 后**恰为** `ModelPreset(` ⇒ 换行续写形态
     （`ModelPreset(label = "x",` 后续写）与括号前空格形态（`ModelPreset (…`）
     都不命中：前者整条不是块起点、其行被吞进**前一块**（中部注入 ⇒ 红挂在
     清白预设名下，归因错位）；若它在首个块起点**之前**（头部注入）则完全静默。
  ② 无块收尾判据（块 = 起点到下一起点）⇒ 与① 叠加放大错位面。
  ③ `KNOWN_BROKEN_SET` 锚定行首 ⇒ 单行构造里任何位置的 `knownBrokenOn = "…"`
     永不命中（单行块本来也整块不识别，双逃逸）。
修补（判据与 arch-guard.sh 第 39 条统一，实现各自独立）：
  ① 统一开启判据：行含 `ModelPreset` + 可选空白 + `(` 且该行**未自闭合**
     （不是以 `)` 可选逗号收尾）⇒ 开多行块；自闭合 ⇒ 单行块。
     `data class ModelPreset(` / `ModelPresetMirror(` 不命中（行首锚定，
     前置字面挡住）。
  ② 补块收尾判据：起点行缩进 == 收尾行缩进的 `),` 行收块；未收尾遇新块起点
     ⇒ 先按上一块结算（第 39 条同缩进 `),` 同款语义）。
W68 A1-R1 修补（严质衡复审 P1：去行首锚定的对偶面——字符串内容假红）：
  ③ 的具名实参位匹配把 `knownBrokenOn` / `recommended` / `label` 的匹配面从行首
     扩大到「行首/逗号/空白/构造括号后均可」——但**前字符负向类只排引号与标识符**，
     字符串字面量内容中**空格后**的散文（如 note 记录历史原因「该 preset 曾
     recommended = true」）畅通无阻 ⇒ 合法预设被假红（raw string 内的
     `knownBrokenOn = "x" 与 recommended = true 互斥` 同型）。
  ⑤ W68 A1-R1：broken/recommended 判定改在 **blank_strlits 后的行**上进行——
     字符串字面量内容替换为等长空格（保留引号本身 ⇒ 真实具名实参的 `= "` 仍命中），
     与第 39 条 awk `strlit` 对偶；LABEL 仍取原行（保留 label 文本，first-match-wins
     下真 label 行在前）。
  已知边界（如实申报）：blank_strlits / strip_comment 均为**逐行**引号状态机，
  **跨行 raw string**（三引号开在一行、内容续行、闭在后续行）的行级状态会错乱
  （现役 ModelPresets.kt 全文 0 处三引号；出现该写法前本条边界不再扩大）。
  ④ 块识别前剥行内 `//` 注释（字符串字面量内的 `//` 不动）——与第 39 条
     awk `strip_comment` 同语义；否则注释里的 `knownBrokenOn = "…"` 会被误判。
"""
import re
import sys
import traceback

# W68 A1：统一开启判据（与 arch-guard.sh 第 39 条同语义，各自实现）——
#   单行块：行首可选空白 + `ModelPreset` + 可选空白 + `(` … 以 `)`（可选逗号）收尾；
#   多行块开启：含 `ModelPreset` + 可选空白 + `(` 且**未自闭合**。
# 旧判据 `^ModelPreset\($`（trim 后严式）只认「(` 独占一行」⇒ 形态A/B 整块逃逸。
SINGLE_LINE = re.compile(r'^[ \t]*ModelPreset[ \t]*\(.*\)[ \t]*,?[ \t]*$')
MULTI_OPEN = re.compile(r'^[ \t]*ModelPreset[ \t]*\(')
# W68 A3-R1：括号换行形态（`ModelPreset` 独占一行、`(` 在下一非空行）——与 #39/#41 同步
#   （严质衡 A2/A3 复审 f6：旧判据要求 `(` 同行 ⇒ 该形态整块静默逃逸）。
OPEN_HEAD = re.compile(r'^[ \t]*ModelPreset[ \t]*$')
NEXT_PAREN = re.compile(r'^[ \t]*\(')
# 块收尾：同缩进的 `),` 行（缩进 == 起点行缩进，第 39 条同款机制）。
# W68 A1-R1 声明（严质衡复审 P2：两处与第 39 条 awk 的良性分歧，刻意保留）：
#   ① 收尾逗号本实现可选（`\)[,]?`）vs 第 39 条必需（`\)[,]`）——无逗号收尾时
#      两守卫都靠「遇新块起点 / EOF」正确结算，实测等价；
#   ② 缩进字符类 `[ \t]` vs 第 39 条 `[[:space:]]`——差别仅在 CRLF 的 `\r`
#      （本实现不吞 `\r`）；现役 `.kt` 由 .gitattributes 钉 LF，无现实命中。
BLOCK_CLOSE = re.compile(r'^([ \t]+)\)[,]?[ \t]*$')
# 非空赋值形态：knownBrokenOn = "…"（null 显式写法 `knownBrokenOn = null` 与缺省都算空）。
# W68 A1 ③：去行首锚定，改块内具名实参位匹配（前字符非 `[A-Za-z0-9_".]`）——
#   单行构造 / 续写形态里任意位置的具名实参都能命中。
# W68 A1-R1 ⑤：**匹配前先经 blank_strlits**（字符串字面量内容置空）——否则
#   note 里的散文（`…曾 recommended = true…`）会骗过具名实参位匹配造成假红
#   （严质衡复审 P1 实测两形态）。blank_strlits 后引号本身保留 ⇒ 真实 `= "` 仍命中。
KNOWN_BROKEN_SET = re.compile(r'(?<![A-Za-z0-9_".])knownBrokenOn[ \t]*=[ \t]*"')
RECOMMENDED_TRUE = re.compile(r'(?<![A-Za-z0-9_".])recommended[ \t]*=[ \t]*true\b')
LABEL = re.compile(r'(?<![A-Za-z0-9_".])label[ \t]*=[ \t]*"([^"]*)"')
DECL = re.compile(r"val knownBrokenOn:\s*String\?")


def strip_comment(s):
    """剥掉行内 // 注释（字符串字面量内的 // 不动；转义引号不误切）。

    与 arch-guard.sh 第 39 条 awk strip_comment 同语义（W68 A1 ④）：
    不剥的话，注释里的 `knownBrokenOn = "…"` 会被具名实参位匹配误判为违规。
    """
    instr = False
    esc = False
    for i, c in enumerate(s):
        if esc:
            esc = False
            continue
        if c == '\\':
            esc = True
            continue
        if c == '"':
            instr = not instr
            continue
        if not instr and c == '/' and i + 1 < len(s) and s[i + 1] == '/':
            return s[:i]
    return s


def blank_strlits(s):
    """把字符串字面量**内容**替换为等长空格（引号本身保留）。

    W68 A1-R1 ⑤（严质衡复审 P1）：与第 39 条 awk `strlit` 对偶——具名实参位匹配
    （KNOWN_BROKEN_SET / RECOMMENDED_TRUE）只应在**代码位**生效，字符串内容里的
    同款字样（note 记录历史原因「曾 recommended = true」/ raw string 内
    `knownBrokenOn = "x" 与 recommended = true 互斥`）不得被误判为违规。
    引号保留 ⇒ 真实具名实参的 `knownBrokenOn = "` 仍能命中（`= "` 形态需要引号）。
    LABEL 仍取原行（保留 label 文本）。
    已知边界：逐行引号状态机，跨行 raw string 的行级状态会错乱（现役文件 0 处三引号）。
    """
    res = []
    instr = False
    esc = False
    for ch in s:
        if esc:
            res.append(' ' if instr else ch)
            esc = False
            continue
        if ch == '\\':
            res.append(ch)
            esc = True
            continue
        if ch == '"':
            instr = not instr
            res.append(ch)
            continue
        res.append(' ' if instr else ch)
    return ''.join(res)


def opens_block(lines, i):
    """行 i 是否开启 ModelPreset 构造块（W68 A3-R1：含括号换行形态）。

    形态A/B：`ModelPreset(` 同行（可选空白）；括号换行：`ModelPreset` 独占一行且
    下一**非空**行以 `(` 开头（严质衡 A2/A3 复审 f6 实测的静默逃逸面）。
    """
    s = strip_comment(lines[i])
    if MULTI_OPEN.match(s):
        return True
    if OPEN_HEAD.match(s):
        for j in range(i + 1, len(lines)):
            nxt = strip_comment(lines[j])
            if not nxt.strip():
                continue
            return bool(NEXT_PAREN.match(nxt))
    return False


def split_blocks(lines):
    """按统一开启判据切块；块 = 起点行 → 同缩进 `),` 收尾行（含）。

    W68 A1 ①②：形态A（换行续写）/ 形态B（括号前空格）都能识别；未收尾遇新
    块起点先按上一块结算（第 39 条同款）；EOF 时未收尾也结算（fail-closed，
    绝不把半块行静默丢给下一块或虚空）。
    W68 A3-R1：补括号换行形态（`ModelPreset` 与 `(` 分行）。
    返回 [(start_index, [stripped_lines...]), ...]；start_index 从 0 起。
    """
    blocks = []
    inblk = False
    start = -1
    ind = 0
    buf = []

    def settle():
        blocks.append((start, buf))

    for i, raw in enumerate(lines):
        s = strip_comment(raw)
        ind_now = len(s) - len(s.lstrip(' \t'))
        if not inblk:
            if opens_block(lines, i):
                inblk = True
                start = i
                ind = ind_now
                buf = [s]
                if SINGLE_LINE.match(s):
                    # 自闭合 ⇒ 单行块，立即结算
                    settle()
                    inblk = False
                continue
            # 非块起点行（块间空白 / 注释 / 其它代码）不属任何块
            continue
        # inblk：块内
        if SINGLE_LINE.match(s) or opens_block(lines, i):
            # 未收尾遇新块起点 ⇒ 先按上一块结算，再开新块
            settle()
            start = i
            ind = ind_now
            buf = [s]
            if SINGLE_LINE.match(s):
                settle()
                inblk = False
            continue
        m = BLOCK_CLOSE.match(s)
        if m and len(m.group(1)) == ind:
            buf.append(s)
            settle()
            inblk = False
            continue
        buf.append(s)
    if inblk:
        settle()
    return blocks


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

    # 切块：统一开启判据 + 同缩进 `),` 收尾（W68 A1）。
    blocks = split_blocks(lines)
    if not blocks:
        print("%s 未找到任何 ModelPreset( 构造块（守卫面失效，按违规处理）" % path)
        return

    for s, block in blocks:
        # W68 A1-R1 ⑤：违规判定在 blank_strlits（字符串内容置空）后的行上进行，
        # 防 note 散文 / raw string 内的同款字样被误判为违规（严质衡复审 P1）。
        broken = any(KNOWN_BROKEN_SET.search(blank_strlits(raw)) for raw in block)
        if not broken:
            continue
        recommended = any(RECOMMENDED_TRUE.search(blank_strlits(raw)) for raw in block)
        if not recommended:
            continue
        # LABEL 仍取原行（保留 label 文本；真 label 行在 first-match-wins 下在前）
        label = next((m.group(1) for m in (LABEL.search(raw) for raw in block) if m), "<未取到 label>")
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
