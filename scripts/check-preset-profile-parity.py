#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守卫：ModelPresets 预设 ↔ ModelSamplingProfiles 采样档案**双向一致**（W68 A3，第 41 条）。

为什么需要这条守卫（复检 Q-2 反向）：
  `ModelSamplingProfiles.forFileName` 按文件名钳制采样参数与上下文长度，是运行时安全
  围栏。两条漂移方向都会静默失效：
   · 方向A（预设漏档）：新预设忘了登记档案 ⇒ contextLength 可拉 32k 超 KV 预算、
     temperature 漏掉防贪心下限（E4B 单文件补档前正是此态，W68 A2 修复检）；
   · 方向B（孤儿档案）：档案表登记了预设表里不存在的文件名 ⇒ 死条目/漂移信号，
     后人无法从预设侧看出它还对不对。

契约（与 scripts/arch-guard.sh 的 check() / check-known-broken.py 一致）：
  - **stdout 非空 = 违规**；干净时**必须零输出**。
  - 永远 exit 0（自身异常打到 stdout ⇒ 判红而不是静默通过）。
  - fail-closed：两文件任一缺失 / `when (fileName) {` 块提取不到（提取正则或函数签名漂移）
    ⇒ 判红。找到 when 块但零档案臂 = 合法空注册表（方向B 空过、方向A 仍硬判）。

用法：python scripts/check-preset-profile-parity.py <ModelPresets.kt> <ModelSamplingProfiles.kt> <docs/12-litertlm-0171-anchors.md>
  （第三参数 = docs/12 锚清单；W70 A2 起**必需** —— 方向 C 的对账载体。缺失即红。）

判据（W68 A3，主理人预裁量⑦；W68 A3-R1 硬化 = 严质衡 A2/A3 复审 3 重要面）：
  - 方向A（硬）：每个 ModelPreset( 构造块提取到的 `.litertlm` 文件名（**只取 url /
    mirrors / domesticMirrors 具名实参位所在行**，W68 A3-R1 修：note/backendBasis 散文里
    提到的文件名不再计入）必须命中 `forFileName` 的某个 `when` 臂 ⇒ 缺 ⇒ 红，**除非**
    该预设 `note` 同时含豁免令牌「档案豁免」**与** `W##` 波次锚点（W68 A3-R1 修：裸子串
    匹配会让否定句/解释性提及也获得豁免）。
    无任何 `.litertlm` 文件名的块（无 URL 的 fixture/桩）跳过：无可检查项。
  - 方向B（硬）：`forFileName` 的每个 `when` **臂**至少有一个文件名命中某预设 ⇒ 全臂
    无对应预设 ⇒ 红（孤儿档案）。臂内其余文件名视为**同族别名**（如
    `MiniCPM5-2B_int4/int8` 同臂两档，Wave 20 起有意登记；int4 有预设即锚定该臂）。
  - 块开启判据（W69 A4，与 #34/#39 同款，共享模块）：`ModelPreset(` 行内具名位匹配
    **或** `ModelPreset` 独占一行且下一非空行以 `(` 开头（括号换行形态）。

---------------------------------------------------------------------------
W70 A2：方向 C —— docs/12 锚对账（W69-11 收窄落地；计划 §四.A A2）
---------------------------------------------------------------------------
方向 C 校「代码 ↔ docs/12 文本声明」，与方向 A/B（代码内部一致性）正交；与 #29
（appliesToRuntime == toml 版本）互补不重叠：#29 校「代码↔toml」，方向 C-1 校
「代码↔docs/12」。两条任一漂移都会被拦（bump 时改 toml 忘改 docs/12 即 C-1 红）。

  - 方向C-1（硬，**非 vacuous**）：`ModelPresets.kt` 全部 `appliesToRuntime` 字面量
    （`appliesToRuntime = "X"` 形态，注释行排除）的**取值集合 + 条数**必须 == docs/12
    **锚 #14 行**（锚清单表 `#` 列 = 14 的行）**依据列**的声明：依据列反引号内的
    版本字面量集合 + 条数声明（「三条」/「第 3 处」两种写法互校，不一致即判声明歧义）。
    现役声明 = {`"0.17.1"`} × 3；代码实数漂移（改值 / 增减条数）⇒ 红，红行含两侧
    实数与代码侧出现行号。fail-closed：docs/12 缺失 / 锚清单表头（含「定案波次」列）
    提取不到 / 锚 #14 行缺失 / 依据列提不出版本取值或提不出条数 ⇒ 红。
  - 方向C-2（硬，**现役 0 命中 = ⛔vacuous 面，如实申报**）：note 含「档案豁免」时，
    其 `W##` 波次锚点必须存在于 docs/12 锚清单**定案波次集合**（锚清单表定案波次列
    全部 W## 的并集）；不在（或无 W## 锚点）⇒ 红。**0 命中 ≠ 通过** —— 现役 0 处
    「档案豁免」令牌 ⇒ 零真实命中面；触发能力由 selftest case73 注入「档案豁免 +
    W99」假锚钉住。fail-closed：定案波次集合为空（锚清单表存在但一列波次都提不出）
    ⇒ 红（豁免锚对账的载体蒸发）。


---------------------------------------------------------------------------
W69 A4：块解析迁移到共享模块 `scripts/preset_blocks.py`（外部审查第十四审 H1）
---------------------------------------------------------------------------
本脚本原先自带一份与 #34 / #39 几乎逐字重复的块解析实现（`SINGLE_LINE` / `MULTI_OPEN` /
`OPEN_HEAD` / `NEXT_PAREN` / `BLOCK_CLOSE` + `strip_comment` / `blank_strlits` /
`opens_block` / `split_preset_blocks`）以及 `blank_prose_literals`。W68 三轮修复每次
都要**手工同步三处**；本波删除重复实现，改 `import preset_blocks` 消费同一解析源：
  · 块切分 → `pb.parse_blocks`（行内具名位开启判据 + 同缩进 `),` 收尾 + 括号换行 +
    块注释剥离 + 字段锚定具名位）。
  · `blank_prose_literals` → `pb.blank_field_literals`（字段级散文置空，语义等价）。
  · `label` / `note` 取自模块字段 API（`fields`），行号取 `start_line`。
方向A / 方向B 判据与文案**逐字保持**。`parse_profile_arms`（ModelSamplingProfiles 侧
的 `when (fileName)` 臂解析）为 #41 独有、无重复，保留（内部 `strip_comment` 改调模块
`strip_line_comment`，同语义）。
"""
import re
import sys
import traceback

import preset_blocks as pb

# 文件名提取（块内，散文字段置空后扫全块）。
LITERTLM_NAME = re.compile(r'([A-Za-z0-9._-]+\.litertlm)')
# W68 A3-R2：文件名提取改「字段级散文置空」——先抹掉散文字段（note / backendBasis /
#   knownBrokenOn / evidenceLevel / label / sizeText / ramText / memBasis）的字面量内容，
#   再扫全块取 `.litertlm`。比 A3-R1 的「行级限定 url/mirrors」更稳：
#   ① 仍根治 f2/f8（note/backendBasis 散文里的文件名不计入）；
#   ② 不丢镜像/续行形态（bespoke `ModelPresetMirror(...)`、多行 `domesticMirrors(`、
#      `url =` 换行续写）的文件名 —— 这些行没有 url/mirrors 关键字，行级限定会漏。
PROSE_FIELDS = ("note", "backendBasis", "knownBrokenOn", "evidenceLevel", "label",
                "sizeText", "ramText", "memBasis")

# ---- ModelSamplingProfiles.kt 档案臂解析 ----
WHEN_OPEN = re.compile(r'=\s*when\s*\(\s*fileName\s*\)\s*\{')
ARM_NAME = re.compile(r'"([A-Za-z0-9._-]+\.litertlm)"')

# 方向A 显式豁免令牌（写进预设 note 才放行，机械可判）
# W68 A3-R1：令牌须与 `W##` 波次锚点同现（防否定句 / 解释性提及「档案豁免」四字即豁免）
EXEMPT_TOKEN = "档案豁免"
ANCHOR = re.compile(r'W[0-9][0-9]')

# ---- 方向C（W70 A2）：docs/12 锚清单解析 ----
# 依据列反引号内的版本字面量（`"0.17.1"` 形态；反引号包裹 + 引号内 semver）
BACKTICK = re.compile(r'`([^`]*)`')
DOC_VERSION_LIT = re.compile(r'"(\d+\.\d+(?:\.\d+)?[^"]*)"')
# 条数声明的两种写法：「三条」/「第 3 处」——互校，不一致即声明歧义（fail-closed）
COUNT_CN = re.compile(r'([一二两三四五六七八九十]+)条')
COUNT_DIGIT = re.compile(r'第\s*(\d+)\s*处')
# 定案波次列里的波次锚点（W55 / W68 等；波次内序号 W69-05 不算定案波次）
WAVE = re.compile(r'W[0-9][0-9]')

_CN_DIGITS = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5,
              "六": 6, "七": 7, "八": 8, "九": 9}


def cn_to_int(s):
    """中文数字 → int（支持 1–99 的「十 / X十 / X十Y」写法；无法解析返回 None）。"""
    if not s:
        return None
    if s == "十":
        return 10
    if "十" in s:
        left, _, right = s.partition("十")
        tens = _CN_DIGITS.get(left, 10) if left else 1
        ones = _CN_DIGITS.get(right, 0) if right else 0
        if tens is None or ones is None or (left and left not in _CN_DIGITS) \
                or (right and right not in _CN_DIGITS):
            return None
        return tens * 10 + ones
    if len(s) == 1 and s in _CN_DIGITS:
        return _CN_DIGITS[s]
    return None


def split_row(line):
    """markdown 表格行 → 单元格列表（首尾空段来自行首行尾 `|`）；非表格行返回 None。"""
    s = line.strip()
    if not s.startswith("|") or not s.rstrip().endswith("|"):
        return None
    return [c.strip() for c in s.strip("|").split("|")]


def parse_docs12(lines):
    """解析 docs/12 锚清单：返回 (anchor14_row, decision_waves)。

    anchor14_row = 锚 #14 行的单元格列表（`#` 列 == 14）；decision_waves = 锚清单表
    定案波次列全部 W## 的集合。表头按列名（含「定案波次」「依据」）定位，不写死列序。
    返回 (None, set()) = 表头或 #14 行提取失败 ⇒ 调用方 fail-closed 判红。
    """
    col_id = col_wave = col_basis = None
    anchor14 = None
    waves = set()
    in_table = False
    for raw in lines:
        cells = split_row(raw)
        if cells is None:
            if in_table:
                break  # 锚清单表到此结束（markdown 表内无空行）
            continue
        if all(re.fullmatch(r':?-{3,}:?', c) for c in cells):
            continue  # 分隔行
        if col_id is None:
            # 表头行：按列名定位（`#` / 含「定案波次」/ 含「依据」）
            for i, c in enumerate(cells):
                if re.fullmatch(r'#', c):
                    col_id = i
                elif "定案波次" in c:
                    col_wave = i
                elif "依据" in c:
                    col_basis = i
            if col_id is not None and col_wave is not None and col_basis is not None:
                in_table = True
            continue
        in_table = True
        if cells[col_id] == "14":
            anchor14 = cells
        if col_wave < len(cells):
            waves.update(WAVE.findall(cells[col_wave]))
    return anchor14, waves


def declared_runtime(basis_cell):
    """方向C-1：从锚 #14 依据列解析声明 ⇒ (版本取值集合, 条数)。

    版本 = 依据列反引号 token 内的 semver 字面量；条数 = 「三条」/「第 3 处」两种写法
    互校。返回 (None, None) = 声明歧义或提不出 ⇒ 调用方 fail-closed 判红。
    """
    versions = set()
    for tok in BACKTICK.findall(basis_cell):
        m = DOC_VERSION_LIT.fullmatch(tok)
        if m:
            versions.add(m.group(1))
    counts = set()
    m = COUNT_CN.search(basis_cell)
    if m:
        n = cn_to_int(m.group(1))
        if n is not None:
            counts.add(n)
    m = COUNT_DIGIT.search(basis_cell)
    if m:
        counts.add(int(m.group(1)))
    if not versions or len(counts) != 1:
        return None, None
    return versions, counts.pop()


def code_applies_to_runtime(lines):
    """方向C-1：ModelPresets.kt 全部 `appliesToRuntime = "X"` 字面量（注释行排除）。

    返回 [(行号, 取值), ...]；行首注释 / KDoc 行（`*` / `//` 开头）不计。
    """
    out = []
    for i, raw in enumerate(lines, 1):
        if re.match(r'^\s*([*/]|//)', raw):
            continue
        for m in re.finditer(r'appliesToRuntime[ \t]*=[ \t]*"([^"]+)"', raw):
            out.append((i, m.group(1)))
    return out


def exempted(note):
    """方向A 豁免判据：note 同时含令牌「档案豁免」与 `W##` 波次锚点（W68 A3-R1）。

    已知边界（如实申报）：不解析否定语义 —— 若一句话里同时出现「档案豁免」与 `W##`
    且是否定句，仍会放行；该组合属刻意构巧，现役 note 零命中。
    """
    return EXEMPT_TOKEN in note and ANCHOR.search(note) is not None


def parse_profile_arms(lines):
    """提取 forFileName 的 when 臂：返回 [[文件名, ...], ...]（臂 = 两次 `->` 之间的名单）。

    返回 None = `when (fileName) {` 都找不到（提取正则 / 函数签名漂移）⇒ 调用方判红
    （fail-closed）。返回**空列表** = 找到 when 块但一个档案臂都没有（合法空注册表）⇒
    方向B  vacuously 通过、方向A 仍硬判（任何带 URL 的预设都匹配不到空名字集 ⇒ 红）——
    该分工令「提取漂移」不可能静默：块漂移 ⇒ None ⇒ 红；块在但名单丢 ⇒ 方向A 全红。
    """
    in_when = False
    arms = []
    current = []
    for raw in lines:
        s = pb.strip_line_comment(raw)
        if not in_when:
            if WHEN_OPEN.search(s):
                in_when = True
            continue
        # when 体结束（收尾 `}` 顶格）：其余代码不再属档案区
        if re.match(r'^\}', s):
            break
        # 先收本行名单（单名臂的 `"x.litertlm" -> …` 同行形态必须计入，W68 A3 实测教训），
        # 再按 `->` 收臂。
        for name in ARM_NAME.findall(s):
            current.append(name)
        if '->' in s:
            if current:
                arms.append(current)
            current = []
            continue
    if current:
        arms.append(current)
    if not in_when:
        return None
    return arms


def main(argv):
    if len(argv) < 4:
        print("check-preset-profile-parity.py 缺少路径参数（需 ModelPresets.kt / ModelSamplingProfiles.kt / docs/12-litertlm-0171-anchors.md；守卫面失效）")
        return
    preset_path, profile_path, docs12_path = argv[1], argv[2], argv[3]
    try:
        preset_lines = open(preset_path, encoding="utf-8", errors="replace").read().splitlines()
    except OSError as e:
        print("check-preset-profile-parity.py 读不到 %s：%s（守卫面失效，按违规处理）" % (preset_path, e))
        return
    try:
        profile_lines = open(profile_path, encoding="utf-8", errors="replace").read().splitlines()
    except OSError as e:
        print("check-preset-profile-parity.py 读不到 %s：%s（守卫面失效，按违规处理）" % (profile_path, e))
        return
    try:
        docs12_lines = open(docs12_path, encoding="utf-8", errors="replace").read().splitlines()
    except OSError as e:
        print("check-preset-profile-parity.py 读不到 %s：%s（第 41 条方向 C 载体缺失，守卫面失效，按违规处理）" % (docs12_path, e))
        return

    blocks = pb.parse_blocks(preset_lines)
    if not blocks:
        print("%s 未找到任何 ModelPreset( 构造块（第 41 条守卫面失效，按违规处理）" % preset_path)
        return
    arms = parse_profile_arms(profile_lines)
    if arms is None:
        print(
            "%s 未提取到 forFileName 的 when 档案臂（`when (fileName) {` 缺失或提取正则漂移 "
            "= 第 41 条守卫面失效，按违规处理）" % profile_path
        )
        return

    # 档案侧全集 + 臂结构
    profile_names = {n for arm in arms for n in arm}

    # ---- 方向A：预设文件名必须命中档案（note 显式豁免除外）----
    preset_names = set()
    for b in blocks:
        label = b["label"] if b["label"] is not None else "<未取到 label>"
        note = b["fields"].get("note", (None,))[0] or ""
        names = []
        for raw in b["lines"]:
            # W68 A3-R2：字段级散文置空后扫全块（散文里的文件名不计入；url / mirrors /
            # domesticMirrors / ModelPresetMirror 等真实文件名字面量全部保留）
            names.extend(LITERTLM_NAME.findall(pb.blank_field_literals(raw, PROSE_FIELDS)))
        if not names:
            continue  # 无 URL 的块（fixture/桩）无可检查项
        preset_names.update(names)
        missing = [n for n in names if n not in profile_names]
        if missing and not exempted(note):
            print(
                "%s:%d 预设「%s」文件名 %s 无 ModelSamplingProfiles 档案分支 —— "
                "新预设漏档 = 采样/上下文钳制静默缺席（第 41 条方向A；确需豁免请在 note 写明「%s」+ 理由 + W## 波次锚点）"
                % (preset_path, b["start_line"], label, "、".join(missing), EXEMPT_TOKEN)
            )

    # ---- 方向B：每个档案臂至少一个文件名命中预设（全臂孤儿 ⇒ 红；臂内其余为同族别名）----
    for arm in arms:
        if not any(n in preset_names for n in arm):
            print(
                "%s 采样档案臂 %s 无任何对应预设 —— 孤儿档案（第 41 条方向B：预设表已无此文件名，档案应同步清理或补预设）"
                % (profile_path, "、".join(arm))
            )

    # ---- 方向C（W70 A2）：docs/12 锚对账（方向 A/B 逐字不变，本段纯追加）----
    anchor14, decision_waves = parse_docs12(docs12_lines)
    if anchor14 is None:
        print(
            "%s 未提取到锚 #14 行或锚清单表头（含「定案波次」「依据」列）（表头列名或 #14 行形态漂移 "
            "= 第 41 条方向 C 守卫面失效，按违规处理）" % docs12_path
        )
    elif not decision_waves:
        print(
            "%s 锚清单定案波次集合为空（豁免锚对账的载体蒸发 = 第 41 条方向 C 守卫面失效，按违规处理）" % docs12_path
        )
    else:
        # 依据列定位：锚 #14 行中含条数声明（「三条」/「第 3 处」）的单元格 —— 依据列是
        # 锚清单表里唯一含版本声明 + 条数声明的列（比写死列序稳健：列序漂移仍可定位）。
        col_basis = None
        for c in anchor14:
            if COUNT_CN.search(c) or COUNT_DIGIT.search(c):
                col_basis = c
                break
        if col_basis is None:
            print(
                "%s 锚 #14 行依据列提不出条数声明（「三条」/「第 3 处」形态漂移 = 第 41 条方向 C-1 "
                "守卫面失效，按违规处理）" % docs12_path
            )
        else:
            declared_versions, declared_count = declared_runtime(col_basis)
            if declared_versions is None:
                print(
                    "%s 锚 #14 行依据列声明歧义（提不出版本取值集合，或「三条」/「第 3 处」两种条数"
                    "写法互校不一致 = 第 41 条方向 C-1 守卫面失效，按违规处理）" % docs12_path
                )
            else:
                lits = code_applies_to_runtime(preset_lines)
                code_versions = {v for _, v in lits}
                code_count = len(lits)
                if code_versions != declared_versions or code_count != declared_count:
                    where = "、".join(":%d" % ln for ln, _ in lits) or "（零字面量）"
                    print(
                        "%s 的 appliesToRuntime 字面量（取值 %s × %d 处，行 %s）与 %s 锚 #14 依据列"
                        "声明（取值 %s × %d）不一致（第 41 条方向C-1）—— bump 时必须同步更新"
                        "docs/12 锚 #14 声明与该字段（与 #29 的代码↔toml 判据互补：漂移任一侧都会被拦）"
                        % (preset_path,
                           "、".join('"%s"' % v for v in sorted(code_versions)) or "（空）",
                           code_count, where, docs12_path,
                           "、".join('"%s"' % v for v in sorted(declared_versions)),
                           declared_count)
                    )

    # 方向C-2：豁免锚点必须存在于 docs/12 定案波次集合（现役 0 命中 = vacuous 面，如实申报；
    # 触发能力由 selftest case73 注入「档案豁免 + W99」假锚钉住 —— 0 命中 ≠ 通过）。
    if anchor14 is not None and decision_waves:
        for b in blocks:
            note = b["fields"].get("note", (None,))[0] or ""
            if EXEMPT_TOKEN not in note:
                continue
            anchors = ANCHOR.findall(note)
            label = b["label"] if b["label"] is not None else "<未取到 label>"
            if not anchors:
                print(
                    "%s:%d 预设「%s」note 含「档案豁免」但无 W## 波次锚点（第 41 条方向C-2：豁免"
                    "必须有波次背书；方向A 的豁免判据同要求令牌 + 锚点同现）"
                    % (preset_path, b["start_line"], label)
                )
                continue
            for w in anchors:
                if w not in decision_waves:
                    print(
                        "%s:%d 预设「%s」note 的「档案豁免」锚点 %s 不在 docs/12 锚清单定案波次集合"
                        " {%s}（第 41 条方向C-2：豁免令牌的波次锚点须有 docs/12 定案波次背书；"
                        "现役 0 命中 ≠ 通过，本判据触发面由 selftest case73 假锚夹具钉住）"
                        % (preset_path, b["start_line"], label, w,
                           "、".join(sorted(decision_waves)))
                    )


if __name__ == "__main__":
    try:
        main(sys.argv)
    except Exception:
        print("check-preset-profile-parity.py 自身执行失败（按违规处理，防静默失效）：")
        traceback.print_exc(file=sys.stdout)
    sys.exit(0)
