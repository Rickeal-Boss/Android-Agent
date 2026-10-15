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

用法：python scripts/check-preset-profile-parity.py <ModelPresets.kt> <ModelSamplingProfiles.kt>

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
    if len(argv) < 3:
        print("check-preset-profile-parity.py 缺少 ModelPresets.kt / ModelSamplingProfiles.kt 路径参数（守卫面失效）")
        return
    preset_path, profile_path = argv[1], argv[2]
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


if __name__ == "__main__":
    try:
        main(sys.argv)
    except Exception:
        print("check-preset-profile-parity.py 自身执行失败（按违规处理，防静默失效）：")
        traceback.print_exc(file=sys.stdout)
    sys.exit(0)
