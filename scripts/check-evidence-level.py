#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守卫：ModelPreset 全 schema 显式声明 evidenceLevel 且 MEASURED 带本仓取证锚点（W66 B3，第 39 条）。

`ModelPreset.evidenceLevel` 声明每条预设结论有多少**仓内可查**的证据支撑（语义见
ModelPresets.kt 的 EvidenceLevel KDoc）。本条把「禁推测预填」（ModelPresets.kt 字段
KDoc 明文）从纪律升级为机械判据。

判据（逐 ModelPreset( 构造块）：
  ① 块内缺 `evidenceLevel = …` 赋值 ⇒ 判红（全 schema：不允许未标注的预设）；
  ② 值不在枚举三态 {MEASURED, UPSTREAM, UNVERIFIED} 内 ⇒ 判红（防拼写/未知等级）；
  ③ 值 == MEASURED 但块的 `backendBasis` / `note` 赋值行内无 `W##` 波次实测锚点 ⇒ 判红
     （MEASURED 声明「本仓实测」，必须能在本条文本里指出是哪一波实测 —— 防「没测过却
     说测过」）。锚点**只认 backendBasis / note 两个字段**：`label` / 注释 / 其它字段里
     的 `W##` **不算**锚点（W66 深审 D2：旧判据扫整块 ⇒ label 里写个 W99 就能骗过）。

契约（与 scripts/arch-guard.sh 的 check() 配合）：
  - 判绿路径**必须零输出**且 exit 0。
  - 违规路径输出违规事实行（stdout 非空）+ exit 1。
  - fail-closed（文件缺失 / `val evidenceLevel` 声明被删 / 未找到任何构造块）⇒ 判红
    （`::error::` 前缀 + exit 1，与迁前 bash 包装一致）。
  - 自身异常把 traceback 打到 stdout + exit 1（防静默失效）。
  - ⚠️ 非零退出**必须伴随非空 stdout**：check() 对「rc=1 但输出空」判 OK（僵尸规则陷阱）。

用法：python scripts/check-evidence-level.py <ModelPresets.kt 路径>

---------------------------------------------------------------------------
W69 A5：内联 awk 迁移到本 Python 检查器（外部审查第十四审 H1）
---------------------------------------------------------------------------
本检查器取代 `scripts/arch-guard.sh` 第 39 条原先的**内联 awk**（约 :880–1002）。awk 与
#34（check-known-broken.py）/ #41（check-preset-profile-parity.py）的块解析几乎逐字重复，
W68 三轮修复每次都要手工同步三处。本波改为 `import preset_blocks` 消费共享模块：
  · 块切分 → `pb.parse_blocks`（行内具名位开启判据 + 同缩进 `),` 收尾 + 括号换行 +
    块注释剥离 + 字段锚定具名位）。
  · `evidenceLevel` 值 / `label` 取模块字段 API；`backendBasis` / `note` 的 `W##` 锚点
    直接查其**字符串字面量内容**（等价于 awk `strlit`，且锚定具名位）。
判据与违规文案**逐字保持**。

顺带裁决 W69-04（原挂账）：awk 对开启行 / 单行块上的 `evidenceLevel` 用**无锚定**
`match($0, /evidenceLevel[[:space:]]*=…/)` 提取 ⇒ 理论上可被字符串内容里的同款字样污染。
模块字段 API **天然锚定具名实参位**（在 `blank_strlits` 后的代码视图上定位，值回原行取），
该污染面消除 —— 无需再挂账。
"""
import re
import sys
import traceback

import preset_blocks as pb

# 字段声明存在性判据（fail-closed：字段被删 = 守卫面失效）。
DECL = re.compile(r"val evidenceLevel")
VALID = ("MEASURED", "UPSTREAM", "UNVERIFIED")
ANCHOR = re.compile(r"W[0-9][0-9]")


def _anchor_ok(fields):
    """MEASURED 锚点判据：backendBasis / note 的**字符串字面量内容**内出现 `W##`。"""
    for name in ("backendBasis", "note"):
        fld = fields.get(name)
        if fld is not None and fld[0] and ANCHOR.search(fld[0]):
            return True
    return False


def main(argv):
    if len(argv) < 2:
        print("::error::check-evidence-level.py 缺少 ModelPresets.kt 路径参数（守卫面失效）")
        return 1
    path = argv[1]
    try:
        lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    except OSError as e:
        print("::error::%s 读不到 %s（守卫面失效，按违规处理）" % (path, e))
        return 1
    if not DECL.search("\n".join(lines)):
        print("::error::%s 缺 val evidenceLevel 声明（字段被删 = 第 39 条守卫面已失效）" % path)
        return 1

    blocks = pb.parse_blocks(lines)
    if not blocks:
        print("::error::%s 未找到任何 ModelPreset( 构造块（守卫面失效，按违规处理）" % path)
        return 1

    violations = 0
    for b in blocks:
        f = b["fields"]
        label = b["label"] if b["label"] is not None else ""
        ev_field = f.get("evidenceLevel")
        if ev_field is None or ev_field[0] is None:
            print(
                "%s:%d 预设「%s」缺 evidenceLevel 赋值（全 schema 要求每条预设显式标注）"
                % (path, b["start_line"], label)
            )
            violations += 1
            continue
        # 值形如 `EvidenceLevel.MEASURED`（去前缀；与 awk `sub(/.*[.]/, "", ev)` 同语义）
        ev = ev_field[0].split('.')[-1]
        if ev not in VALID:
            print(
                "%s:%d 预设「%s」evidenceLevel=%s 非法（须 MEASURED/UPSTREAM/UNVERIFIED）"
                % (path, b["start_line"], label, ev)
            )
            violations += 1
            continue
        if ev == "MEASURED" and not _anchor_ok(f):
            print(
                "%s:%d 预设「%s」evidenceLevel=MEASURED 但 backendBasis/note 内无本仓取证锚点（W## 波次实测）—— 疑推测预填"
                % (path, b["start_line"], label)
            )
            violations += 1
    return 1 if violations else 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception:
        # 自身异常也必须打到 stdout（非空）+ 非零退出，绝不能静默通过
        print("check-evidence-level.py 自身执行失败（按违规处理，防静默失效）：")
        traceback.print_exc(file=sys.stdout)
        sys.exit(1)
