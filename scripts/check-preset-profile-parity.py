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

判据（W68 A3，主理人预裁量⑦）：
  - 方向A（硬）：每个 ModelPreset( 构造块提取到的 `.litertlm` 文件名（url / mirrors
    具名实参位，块解析与第 34 条同款）必须命中 `forFileName` 的某个 `when` 臂 ⇒ 缺 ⇒
    红，**除非**该预设 `note` 含显式豁免理由（字面量「档案豁免」—— 机械可判，防下一
    条新预设静默漏档）。
    无任何 `.litertlm` 文件名的块（无 URL 的 fixture/桩）跳过：无可检查项。
  - 方向B（硬）：`forFileName` 的每个 `when` **臂**至少有一个文件名命中某预设 ⇒ 全臂
    无对应预设 ⇒ 红（孤儿档案）。臂内其余文件名视为**同族别名**（如
    `MiniCPM5-2B_int4/int8` 同臂两档，Wave 20 起有意登记；int4 有预设即锚定该臂）。
"""
import re
import sys
import traceback

# ---- ModelPresets.kt 块解析（与 check-known-broken.py 第 34 条同款，W68 A1 双修后判据）----
SINGLE_LINE = re.compile(r'^[ \t]*ModelPreset[ \t]*\(.*\)[ \t]*,?[ \t]*$')
MULTI_OPEN = re.compile(r'^[ \t]*ModelPreset[ \t]*\(')
BLOCK_CLOSE = re.compile(r'^([ \t]+)\)[,]?[ \t]*$')
# 块内具名实参位（前字符非 [A-Za-z0-9_".]，与第 39 条 field_lit 同语义）
LABEL = re.compile(r'(?<![A-Za-z0-9_".])label[ \t]*=[ \t]*"([^"]*)"')
NOTE = re.compile(r'(?<![A-Za-z0-9_".])note[ \t]*=[ \t]*"([^"]*)"')
# 块内任意位置的 .litertlm 文件名（url 主源 + domesticMirrors 两路镜像均覆盖）
LITERTLM_NAME = re.compile(r'([A-Za-z0-9._-]+\.litertlm)')

# ---- ModelSamplingProfiles.kt 档案臂解析 ----
WHEN_OPEN = re.compile(r'=\s*when\s*\(\s*fileName\s*\)\s*\{')
ARM_NAME = re.compile(r'"([A-Za-z0-9._-]+\.litertlm)"')

# 方向A 显式豁免令牌（写进预设 note 才放行，机械可判）
EXEMPT_TOKEN = "档案豁免"


def strip_comment(s):
    """剥行内 // 注释（字符串内不动）—— 与第 34/39 条同语义。"""
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


def split_preset_blocks(lines):
    """ModelPreset 构造块切分（第 34 条同款：统一开启判据 + 同缩进 `),` 收尾）。"""
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
            if MULTI_OPEN.match(s):
                inblk = True
                start = i
                ind = ind_now
                buf = [s]
                if SINGLE_LINE.match(s):
                    settle()
                    inblk = False
                continue
            continue
        if SINGLE_LINE.match(s) or MULTI_OPEN.match(s):
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
        s = strip_comment(raw)
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

    blocks = split_preset_blocks(preset_lines)
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
    for s, block in blocks:
        label = next((m.group(1) for m in (LABEL.search(raw) for raw in block) if m), "<未取到 label>")
        note = next((m.group(1) for m in (NOTE.search(raw) for raw in block) if m), "")
        names = []
        for raw in block:
            names.extend(LITERTLM_NAME.findall(raw))
        if not names:
            continue  # 无 URL 的块（fixture/桩）无可检查项
        preset_names.update(names)
        missing = [n for n in names if n not in profile_names]
        if missing and EXEMPT_TOKEN not in note:
            print(
                "%s:%d 预设「%s」文件名 %s 无 ModelSamplingProfiles 档案分支 —— "
                "新预设漏档 = 采样/上下文钳制静默缺席（第 41 条方向A；确需豁免请在 note 写明「%s」+ 理由）"
                % (preset_path, s + 1, label, "、".join(missing), EXEMPT_TOKEN)
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
