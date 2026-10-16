#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""共享块解析模块：`ModelPresets.kt` 的 `ModelPreset(...)` 构造块**单一解析源**（W69 A1）。

为什么需要它（外部审查第十四审 H1 / 复审24 P1-① / P2-①）：
  同一 `ModelPreset` 块解析语义此前存在**三份独立实现**：
    · #34 `scripts/check-known-broken.py`（266 行 Python）
    · #39 `scripts/arch-guard.sh` 内联 awk（约 :880–1002）
    · #41 `scripts/check-preset-profile-parity.py`（W69 A4 迁移时 319 行；W70 A2 追加方向 C 后 408 行）
  三者的块切分代码（`SINGLE_LINE` / `MULTI_OPEN` / `OPEN_HEAD` / `NEXT_PAREN` /
  `BLOCK_CLOSE` + `strip_comment` / `blank_strlits` / `opens_block` / `split_blocks`）
  几乎逐字重复；W68 三轮修复每次都要**手工同步三处**（`blank_strlits`、括号换行、
  字段级置空）—— 这是本仓仅存的「靠人记得」面。本模块把该纪律**变结构**：
  三守卫改为 `import preset_blocks` 消费同一实现，判据漂移不可能再分叉。

本波同时修掉三处独立实现里残留的逃逸/假红面（与 A2 fixture 单测一一对应）：
  · P1-① 赋值/表达式前缀形态静默逃逸：旧判据要求 `ModelPreset` 在**行首**
    （`^[ \\t]*ModelPreset…`）⇒ `val x = ModelPreset(` / `items = listOf(ModelPreset(`
    整块逃逸。本模块改为**行内具名位匹配** `(?<![A-Za-z0-9_])ModelPreset[ \\t]*\\(`，
    并**额外排除**前一个词为 `class` 的匹配（挡 `data class ModelPreset(` / `class
    ModelPreset(`—— 单靠负向后顾挡不住，因 `class` 与标识符间有空格）。
    `ModelPresetMirror(` 天然不匹配（`ModelPreset` 后紧跟 `Mirror` 而非 `(`）。
  · P2-① 块内块注释 / KDoc 散文假红：旧实现只剥行内 `//`，块注释 `/* … */`（含跨行）
    里的同款字样（如 KDoc 反引号里写 `knownBrokenOn = "x"`）会骗过判定。本模块新增
    **块注释剥离**（`strip_comments`，字符串内不动）。
  · W69-04 无锚定 `match` 污染面：#39 内联 awk 对开启行/单行块上的 `evidenceLevel`
    用**无锚定** `match($0, /evidenceLevel[[:space:]]*=…/)` 提取 ⇒ 理论上可被字符串内容
    污染。本模块的字段提取**天然锚定具名实参位**（在 `blank_strlits` 后的代码视图上
    定位字段名，值再回原行取），消除该面。

API（纯函数，无副作用；输入 = 文件行列表，输出 = 结构化块列表）：
  strip_line_comment(s)                 -> str      剥行内 `//`（字符串/转义感知）
  blank_strlits(s)                      -> str      字符串内容置空、引号保留（转义感知）
  strip_comments(lines)                 -> list[str] 剥块注释（可跨行）+ 行注释（字符串内不动）
  blank_field_literals(s, names)        -> str      具名位字段的字符串字面量内容置空
  opens_block_line(s)                   -> bool     单行判据（不含括号换行形态）
  parse_blocks(lines)                   -> list[dict]

块 dict 结构（每块）：
  {
    "start_line": int,      # 1-based，块起始行号（开启行）
    "end_line":   int,      # 1-based，块收尾行号（含；EOF 未收尾 = 最后一行）
    "indent":     int,      # 开启行的前导空白字符数
    "lines":      list[str],# 块的各行（已 strip_comments：块注释置空 + 行注释截断）
    "label":      str|None, # 便捷字段 = fields["label"] 的字面量值（取不到 = None）
    "fields":     dict,     # 具名实参名 -> (字面量值或 None, 该字段所在行号(1-based), 该字段在行内的起始列(0-based))
  }

`fields` 语义（要求 7：字段提取锚定具名实参位）：
  · 定位在 `blank_strlits(line)` 后的**代码视图**上做，字段名前字符须非 `[A-Za-z0-9_".]`
    且其后为 `=`（非 `==`）⇒ 字符串内容里嵌的同款字样（内容已被置空）不会骗过判定。
  · 值从**原行**取（保留真实文本）：`"` 起 ⇒ 字符串字面量内容（转义感知）；否则 ⇒
    `=` 到行内顶层 `,` / `)` 之间的 token（如 `true` / `EvidenceLevel.MEASURED`）。
  · Kotlin `null` 字面量归一为 Python `None`；值跨行（`=` 后换行）也记 None —— 两者对
    本仓判定等价（`null` 即「无值」，跨行值由 `lines` 扫描兜底）。
  · 同名多次出现 ⇒ first-match-wins（与三守卫既有 `next(...)` 语义一致）。

已知边界（如实申报，与 W68 A1-R1 既有边界同族，不扩大）：
  · `blank_strlits` / `strip_comments` 均为**逐行**引号状态机 ⇒ **跨行 raw string**
    （三引号开在一行、内容续行、闭在后续行）的行级状态会错乱。现役 `ModelPresets.kt`
    全文 0 处三引号；出现该写法前本边界不再扩大。
  · 单行内出现**多个** `ModelPreset(`（如 `listOf(ModelPreset(…), ModelPreset(…))`）
    ⇒ 按首个匹配结算该行（罕见形态，未纳入判定）。
"""
import re

# ---------------------------------------------------------------------------
# 开启 / 收尾判据（行内具名位匹配，非行首锚定 —— 修 P1-① 赋值/表达式前缀逃逸）
# ---------------------------------------------------------------------------
# 行内 `ModelPreset(` 匹配：前字符非标识符字符（防 `FooModelPreset(` 尾缀误命中）。
# `ModelPresetMirror(` 天然不匹配（`ModelPreset` 后紧跟 `Mirror` 而非 `(`）。
_OPEN_MATCH = re.compile(r'(?<![A-Za-z0-9_])ModelPreset[ \t]*\(')
# 前一个词：用于排除 `data class ModelPreset(` / `class ModelPreset(`（跳过空白向左找）。
_PREV_WORD = re.compile(r'([A-Za-z_][A-Za-z0-9_]*)[ \t]*$')
# 括号换行形态（要求 4）：`ModelPreset` 独占一行、`(` 在下一非空行。
OPEN_HEAD = re.compile(r'^[ \t]*ModelPreset[ \t]*$')
NEXT_PAREN = re.compile(r'^[ \t]*\(')
# 块收尾（要求 2）：`<同缩进>),`（可选逗号）。缩进须 == 开启行缩进。
CLOSE_LINE = re.compile(r'^([ \t]*)\)[,]?[ \t]*$')
# 具名实参名（通用提取）：前字符非 `[A-Za-z0-9_".]`，其后 `=`（非 `==`）。
_FIELD = re.compile(r'(?<![A-Za-z0-9_".])([A-Za-z_][A-Za-z0-9_]*)[ \t]*=(?!=)')


def strip_line_comment(s):
    """剥掉行内 `//` 注释（字符串字面量内的 `//` 不动；转义引号不误切）。

    与三守卫既有 `strip_comment` 同语义（W68 A1 ④）：不剥的话，注释里的
    `knownBrokenOn = "…"` 会被具名实参位匹配误判为违规。
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

    与三守卫既有 `blank_strlits` / 第 39 条 awk `strlit` 对偶（W68 A1-R1 ⑤）：具名实参位
    匹配只应在**代码位**生效，字符串内容里的同款字样不得被误判。引号保留 ⇒ 真实具名
    实参的 `= "` 形态仍命中。已知边界：逐行引号状态机，跨行 raw string 状态会错乱。
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


def strip_comments(lines):
    """剥**块注释**（`/* … */`，可跨行）+ **行注释**（`//`）—— 要求 6（修 P2-①）。

    返回与输入等长的行列表：块注释内字符替换为**等长空格**（保留列位，供字段列号计算），
    行注释从 `//` 起截断。**字符串字面量内的 `/*`、`//` 不动**（转义引号不误切）。
    跨行块注释的状态在行间传递（行首进入 / 行内退出）。

    旧实现只剥行内 `//`（三守卫一致），块内 KDoc 散文里的 `knownBrokenOn = "x"`
    （写在反引号里）会被误判违规（复审24 P2-①）。本函数把块注释一并剥离。
    """
    out = []
    in_block = False
    for s in lines:
        res = []
        instr = False
        esc = False
        i = 0
        n = len(s)
        while i < n:
            c = s[i]
            if in_block:
                if c == '*' and i + 1 < n and s[i + 1] == '/':
                    res.append('  ')
                    i += 2
                    in_block = False
                    continue
                res.append(' ')
                i += 1
                continue
            if esc:
                res.append(c)
                esc = False
                i += 1
                continue
            if c == '\\':
                res.append(c)
                esc = True
                i += 1
                continue
            if c == '"':
                instr = not instr
                res.append(c)
                i += 1
                continue
            if not instr and c == '/' and i + 1 < n:
                nxt = s[i + 1]
                if nxt == '/':
                    break  # 行注释：丢弃本行余下内容
                if nxt == '*':
                    res.append('  ')
                    i += 2
                    in_block = True
                    continue
            res.append(c)
            i += 1
        out.append(''.join(res))
    return out


def _read_string(s, i):
    """从 `s[i] == '"'` 起读字符串字面量**内容**（不含引号，转义感知）。

    转义序列（`\\"` / `\\\\`）原样保留在返回值里（便于 `in` / 正则子串判定）；未闭合时
    返回剩余全部内容（fail-soft，不抛异常）。
    """
    out = []
    j = i + 1
    n = len(s)
    while j < n:
        c = s[j]
        if c == '\\':
            if j + 1 < n:
                out.append(s[j:j + 2])
                j += 2
                continue
            out.append(c)
            j += 1
            continue
        if c == '"':
            break
        out.append(c)
        j += 1
    return ''.join(out)


def _field_rhs(code_line, orig_line, match_end):
    """在代码视图行上，从 `=` 之后取字段值；值回**原行**取（保留真实文本）。

    返回 `(value, value_col)`；`value` 为 None 表示 `=` 后本行无值（值在下一行，未取）。
    """
    n = len(code_line)
    j = match_end
    while j < n and code_line[j] in ' \t':
        j += 1
    if j >= n:
        return (None, j)
    if code_line[j] == '"':
        return (_read_string(orig_line, j), j)
    # 非字符串：取到本行顶层 `,` / `)` 之前（token，如 true / EvidenceLevel.X）
    k = j
    while k < n and code_line[k] not in ',)':
        k += 1
    token = orig_line[j:k].strip()
    # Kotlin `null` 字面量归一为 None（「字面量值或 None」；与「值不在本行」对判定等价）
    return (None if token == "null" else token, j)


def extract_fields(clean_lines, start_idx, end_idx):
    """提取块内具名实参（要求 7：锚定具名实参位）。

    `clean_lines` = `strip_comments` 后的全文行；`start_idx..end_idx` = 块的 0-based 行区间
    （含端点）。返回 `{name: (value, line_no, col)}`（line_no 1-based，col 0-based 字段名起始列）。
    """
    fields = {}
    for idx in range(start_idx, end_idx + 1):
        raw = clean_lines[idx]
        code = blank_strlits(raw)
        for m in _FIELD.finditer(code):
            name = m.group(1)
            if name in fields:
                continue  # first-match-wins
            value, _ = _field_rhs(code, raw, m.end())
            fields[name] = (value, idx + 1, m.start())
    return fields


def blank_field_literals(s, field_names):
    """抹掉**具名位字段**的字符串字面量内容（引号保留），其余原样 —— 要求 7 的对偶工具。

    与 #41 既有 `blank_prose_literals` 同族（W68 A3-R2：字段级而非行级）：只有
    `field_names` 里列出的散文字段（note / backendBasis / …）的字面量内容被置空，
    url / mirrors / domesticMirrors / `ModelPresetMirror(...)` 等真实文件名字面量全部保留。
    定位在 `blank_strlits` 后的代码视图上做（防字符串内容里的假字段），值区间回原串抹除。
    """
    names = set(field_names)
    out = list(s)
    code = blank_strlits(s)
    for m in _FIELD.finditer(code):
        if m.group(1) not in names:
            continue
        i = m.end()
        while i < len(s) and s[i] in ' \t':
            i += 1
        if i >= len(s) or s[i] != '"':
            continue
        j = i + 1
        while j < len(s):
            if s[j] == '\\':
                j += 2
                continue
            if s[j] == '"':
                break
            j += 1
        for k in range(i, min(j + 1, len(s))):
            if out[k] != '"':
                out[k] = ' '
    return ''.join(out)


def _prev_word_is_class(s, pos):
    """匹配位置前的**前一个词**（跳过空白向左找）是否为 `class` —— 要求 1 的额外排除。

    `data class ModelPreset(` / `class ModelPreset(` 的 `ModelPreset` 前一词 = `class`
    ⇒ 不算块开启（负向后顾 `(?<![A-Za-z0-9_])` 挡不住 —— `class` 与标识符间有空格）。
    """
    m = _PREV_WORD.search(s[:pos])
    return bool(m) and m.group(1) == 'class'


def _paren_balanced_from(s, pos):
    """从 `s[pos] == '('` 起扫描括号平衡（`s` 已 `blank_strlits` ⇒ 字符串内 `()` 已置空）。

    返回 `(balanced, close_idx)`；`balanced` = 该 `(` 在本行内闭合。
    """
    depth = 0
    n = len(s)
    i = pos
    while i < n:
        c = s[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return True, i
        i += 1
    return False, None


def opens_block_line(s):
    """行 `s`（**已 strip_comments**）是否开启一个 `ModelPreset` 构造块（不含括号换行形态）。

    行内具名位匹配 `ModelPreset(`（非行首锚定），并排除前一词为 `class` 的匹配。
    `ModelPresetMirror(` 天然不匹配。**不含**括号换行形态（`ModelPreset` 与 `(` 分行）
    —— 那由 `parse_blocks` 的前瞻处理（要求 4）。
    """
    code = blank_strlits(s)
    m = _OPEN_MATCH.search(code)
    if not m:
        return False
    return not _prev_word_is_class(code, m.start())


def parse_blocks(lines):
    """把 `ModelPresets.kt` 行列表解析为结构化块列表（本模块主入口）。

    块界（要求 2）：开启行（行内 `ModelPreset(` 或括号换行形态）→ **同缩进** `),`（可选
    逗号）收尾行（含）。未收尾遇新块起点 ⇒ 先结算上一块；EOF 未收尾也结算（fail-closed，
    绝不把半块行静默丢给下一块或虚空）。

    单行块（要求 3）：`ModelPreset(…),` 一行内自闭合（括号在本行内平衡）⇒ 整行成一块。
    括号换行（要求 4）：`ModelPreset` 独占一行、`(` 在下一非空行 ⇒ 该两行起成一块。

    返回 `list[dict]`，结构见模块 docstring。
    """
    clean = strip_comments(lines)
    code_lines = [blank_strlits(s) for s in clean]
    blocks = []

    def emit(start, end, ind):
        fields = extract_fields(clean, start, end)
        label = fields.get("label", (None, 0, 0))[0]
        blocks.append({
            "start_line": start + 1,
            "end_line": end + 1,
            "indent": ind,
            "lines": clean[start:end + 1],
            "label": label,
            "fields": fields,
        })

    inblk = False
    start = -1
    ind = 0
    i = 0
    n = len(lines)
    while i < n:
        s = clean[i]
        code = code_lines[i]
        if not inblk:
            m = _OPEN_MATCH.search(code)
            if m and not _prev_word_is_class(code, m.start()):
                ind = len(s) - len(s.lstrip(' \t'))
                balanced, _ = _paren_balanced_from(code, m.start())
                if balanced:
                    emit(i, i, ind)  # 单行块：一行内自闭合
                else:
                    inblk = True
                    start = i
                i += 1
                continue
            if OPEN_HEAD.match(s):
                # 括号换行形态：前瞻下一**非空**行是否以 `(` 开头
                j = i + 1
                while j < n and not clean[j].strip():
                    j += 1
                if j < n and NEXT_PAREN.match(clean[j]):
                    ind = len(s) - len(s.lstrip(' \t'))
                    inblk = True
                    start = i
                    i += 1
                    continue
            i += 1
            continue
        # ---- 块内 ----
        m = _OPEN_MATCH.search(code)
        if m and not _prev_word_is_class(code, m.start()):
            # 未收尾遇新块起点 ⇒ 先结算上一块，再开新块
            emit(start, i - 1, ind)
            ind = len(s) - len(s.lstrip(' \t'))
            balanced, _ = _paren_balanced_from(code, m.start())
            if balanced:
                emit(i, i, ind)
                inblk = False
            else:
                start = i
            i += 1
            continue
        cm = CLOSE_LINE.match(code)
        if cm and len(cm.group(1)) == ind:
            emit(start, i, ind)
            inblk = False
            i += 1
            continue
        i += 1
    if inblk:
        emit(start, n - 1, ind)  # EOF 未收尾也结算（fail-closed）
    return blocks
