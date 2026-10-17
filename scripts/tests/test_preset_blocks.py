#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""`scripts/preset_blocks.py` 模块级 fixture 单测（W69 A2）。

纯 Python、零第三方依赖：既能 `python3 scripts/tests/test_preset_blocks.py`（自带
`__main__` 跑全部 `test_*`），也能 `python3 -m pytest scripts/tests/test_preset_blocks.py`。
运行方式（两种都可）：
    python3 scripts/tests/test_preset_blocks.py
    python3 -m pytest scripts/tests/test_preset_blocks.py -q

钉住的判据面（与 W69 任务 A2 一一对应）：
  ① test_string_content_not_misjudged      字符串内容含 `knownBrokenOn = "x"` 不误判
  ② test_line_comment_not_misjudged        行注释内同款字样不误判
  ③ test_block_comment_not_misjudged       块注释（含跨行 / KDoc 反引号）内同款字样不误判
  ④ test_paren_newline_form                括号换行形态（`ModelPreset` 与 `(` 分行）识别
  ⑤ test_single_line_block                 单行块（`ModelPreset(…),` 一行内自闭合）识别
  ⑥ test_assign_prefix_form                赋值前缀形态 `val x = ModelPreset(` 被识别为块
  ⑦ test_data_class_not_matched            `data class ModelPreset(` 不被识别
  ⑧ test_mirror_not_matched                `ModelPresetMirror(` 不被识别
  ⑨ test_fields_anchored_to_named_arg      字段提取锚定具名位（字符串内容里的假字段不算）
  ⑩ test_clean_fixture_all_blocks          综合夹具：多块 + 干扰项，块数/字段/行号正确
  ⑪ test_applies_to_runtime_literals       `--applies-to-runtime` 消费入口（W71 G5）：
                                               正常提取 = `值<TAB>行号` 行，升序去重
  ⑫ test_applies_string_content_not_misjudged  字符串内容里的 `appliesToRuntime = "9.9.9"`
                                               字样不被误提取（#29 迁移后的假红免疫面）
  ⑬ test_applies_zero_literal_clean        零字面量合法态 = 空输出 exit 0（与「读取失败
                                               exit 1」的 fail-closed 面区分）

---------------------------------------------------------------------------
变异测试说明（删/改一条判据 ⇒ 哪个用例必红）—— 用于证明本测试网**非 vacuous**：
  · 删「前一词 == class」排除（`_prev_word_is_class` 恒 False）⇒ **⑦ 必红**
    （`data class ModelPreset(` 被误识别成块）。
  · 开启判据退回行首锚定（`^[ \\t]*ModelPreset`）⇒ **⑥ 必红**
    （`val x = ModelPreset(` 不再被识别）。
  · 删块注释剥离（`strip_comments` 不处理 `/* … */`）⇒ **③ 必红**
    （KDoc/块注释里的 `knownBrokenOn = "x"` 被提取为字段）。
  · 删行注释剥离 ⇒ **② 必红**。
  · 字段提取不锚定具名位（改回裸 `findall(name)`）⇒ **① 必红**（字符串内容里的
    `knownBrokenOn` 被当字段）、**⑨ 必红**。
  · 删括号换行前瞻（`OPEN_HEAD` / `NEXT_PAREN` 分支）⇒ **④ 必红**。
  · 删单行块分支（括号平衡判定）⇒ **⑤ 必红**。
  · `_OPEN_MATCH` 的负向后顾 `(?<![A-Za-z0-9_])` 放宽 ⇒ **⑧ 可能红**（`ModelPresetMirror(`
    的 `ModelPreset` 尾缀被误命中；由 `[ \\t]*\\(` 要求 `(` 紧跟亦挡住，双重保险）。
---------------------------------------------------------------------------
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import preset_blocks as pb  # noqa: E402


def parse(src):
    """把 Kotlin 片段解析为块列表。"""
    return pb.parse_blocks(src.splitlines())


def fields_of(block):
    return {k: v[0] for k, v in block["fields"].items()}


# ---------------------------------------------------------------------------
# ① 字符串内容含 knownBrokenOn = "x" 不误判（具名位锚定 + blank_strlits）
# ---------------------------------------------------------------------------
def test_string_content_not_misjudged():
    blocks = parse(r'''
object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "prose-ok",
            knownBrokenOn = null,
            note = "历史：knownBrokenOn = \"x\" 与 recommended = true 互斥",
        ),
    )
}
''')
    assert len(blocks) == 1, blocks
    f = fields_of(blocks[0])
    # 真字段 knownBrokenOn = null ⇒ None；字符串内容里的同款字样不得被当字段
    assert f.get("knownBrokenOn") is None, f
    assert "recommended" not in f, f
    # note 真字段值保留原文（含同款字样）
    assert "knownBrokenOn" in (f.get("note") or ""), f
    print("PASS [①] 字符串内容含 knownBrokenOn = \"x\" 不误判")


# ---------------------------------------------------------------------------
# ② 行注释内同款字样不误判（strip_line_comment）
# ---------------------------------------------------------------------------
def test_line_comment_not_misjudged():
    blocks = parse('''
object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "line-comment-ok",
            knownBrokenOn = null,
            recommended = true, // knownBrokenOn = "x" 曾置顶推荐（注释里的不算）
        ),
    )
}
''')
    assert len(blocks) == 1, blocks
    f = fields_of(blocks[0])
    assert f.get("knownBrokenOn") is None, f
    assert f.get("recommended") == "true", f
    print("PASS [②] 行注释内同款字样不误判")


# ---------------------------------------------------------------------------
# ③ 块注释（含跨行 / KDoc 反引号）内同款字样不误判（strip_comments 新增面）
# ---------------------------------------------------------------------------
def test_block_comment_not_misjudged():
    blocks = parse('''
object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "block-comment-ok",
            /* 历史沿革：
               knownBrokenOn = "x" 与 recommended = true 互斥，
               下面这行是 KDoc 风格反引号提及：`knownBrokenOn = "x"`
            */
            knownBrokenOn = null,
            recommended = true,
        ),
    )
}
''')
    assert len(blocks) == 1, blocks
    f = fields_of(blocks[0])
    assert f.get("knownBrokenOn") is None, f
    assert f.get("recommended") == "true", f
    print("PASS [③] 块注释（含跨行）内同款字样不误判")


# ---------------------------------------------------------------------------
# ④ 括号换行形态（`ModelPreset` 独占一行、`(` 在下一非空行）识别
# ---------------------------------------------------------------------------
def test_paren_newline_form():
    blocks = parse('''
object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset
        (
            label = "paren-newline",
            evidenceLevel = EvidenceLevel.UPSTREAM,
        ),
    )
}
''')
    assert len(blocks) == 1, blocks
    assert blocks[0]["label"] == "paren-newline", blocks
    assert fields_of(blocks[0]).get("evidenceLevel") == "EvidenceLevel.UPSTREAM", blocks
    print("PASS [④] 括号换行形态识别")


# ---------------------------------------------------------------------------
# ⑤ 单行块（`ModelPreset(…),` 一行内自闭合）识别
# ---------------------------------------------------------------------------
def test_single_line_block():
    blocks = parse('''
object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(label = "single-line", recommended = false),
    )
}
''')
    assert len(blocks) == 1, blocks
    assert blocks[0]["label"] == "single-line", blocks
    assert blocks[0]["start_line"] == blocks[0]["end_line"], blocks
    assert fields_of(blocks[0]).get("recommended") == "false", blocks
    print("PASS [⑤] 单行块识别")


# ---------------------------------------------------------------------------
# ⑥ 赋值前缀形态 `val x = ModelPreset(` 被识别为块（修 P1-① 逃逸）
# ---------------------------------------------------------------------------
def test_assign_prefix_form():
    blocks = parse('''
object ModelPresets {
    val x = ModelPreset(
        label = "assign-prefix",
        recommended = true,
    )
}
''')
    assert len(blocks) == 1, blocks
    assert blocks[0]["label"] == "assign-prefix", blocks
    print("PASS [⑥] 赋值前缀形态 val x = ModelPreset( 被识别为块")


# ---------------------------------------------------------------------------
# ⑦ `data class ModelPreset(` 不被识别（前一词 == class 排除）
# ---------------------------------------------------------------------------
def test_data_class_not_matched():
    assert parse('''
data class ModelPreset(
    val label: String,
    val knownBrokenOn: String? = null,
)
''') == [], "data class ModelPreset( 被误识别为块"
    # `class ModelPreset(` 同款
    assert parse('''
class ModelPreset(
    val label: String,
)
''') == [], "class ModelPreset( 被误识别为块"
    print("PASS [⑦] data class ModelPreset( 不被识别")


# ---------------------------------------------------------------------------
# ⑧ `ModelPresetMirror(` 不被识别（尾缀非 `(`）
# ---------------------------------------------------------------------------
def test_mirror_not_matched():
    assert parse('''
object M {
    val mirrors = listOf(
        ModelPresetMirror("a", "b"),
        ModelPresetMirror("c", "d"),
    )
}
''') == [], "ModelPresetMirror( 被误识别为块"
    print("PASS [⑧] ModelPresetMirror( 不被识别")


# ---------------------------------------------------------------------------
# ⑨ 字段提取锚定具名位（字符串内容里的假字段不算；真字段值/行号/列号正确）
# ---------------------------------------------------------------------------
def test_fields_anchored_to_named_arg():
    blocks = parse(r'''
object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "anchor-check",
            url = "https://x/y.litertlm",
            note = "字段假象：evidenceLevel = MEASURED 与 url = \"z\" 都在引号里",
            evidenceLevel = EvidenceLevel.UNVERIFIED,
        ),
    )
}
''')
    assert len(blocks) == 1, blocks
    b = blocks[0]
    f = fields_of(b)
    assert f.get("evidenceLevel") == "EvidenceLevel.UNVERIFIED", f
    assert f.get("url") == "https://x/y.litertlm", f
    assert f.get("label") == "anchor-check", f
    # 真值所在行号/列号（1-based 行、0-based 列）
    val, line_no, col = b["fields"]["label"]
    assert val == "anchor-check" and line_no == 5 and col == 12, b["fields"]["label"]
    print("PASS [⑨] 字段提取锚定具名位（假字段不算 + 值/行号/列号正确）")


# ---------------------------------------------------------------------------
# ⑩ 综合夹具：多块 + 干扰项（data class / Mirror / 字符串假象），块数/顺序正确
# ---------------------------------------------------------------------------
def test_clean_fixture_all_blocks():
    blocks = parse(r'''
package com.rickeal.agent.core.model

data class ModelPreset(
    val label: String,
    val url: String = "",
    val recommended: Boolean = false,
    val knownBrokenOn: String? = null,
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
    val note: String = "",
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "clean-recommended",
            url = "https://fixture.invalid/fixture-preset.litertlm",
            recommended = true,
            evidenceLevel = EvidenceLevel.MEASURED,
            note = "W60 实测（fixture 锚点）",
        ),
        ModelPreset(
            label = "broken-not-recommended",
            recommended = false,
            knownBrokenOn = "0.17.1 假签名（fixture）",
            evidenceLevel = EvidenceLevel.UPSTREAM,
        ),
        ModelPreset
        (
            label = "paren-newline-ok",
            evidenceLevel = EvidenceLevel.UPSTREAM,
        ),
    )

    private val recommendedOrder = listOf("clean-recommended")
    private val mirrors = listOf(ModelPresetMirror("a", "b"))
}
''')
    labels = [b["label"] for b in blocks]
    assert labels == ["clean-recommended", "broken-not-recommended", "paren-newline-ok"], labels
    assert blocks[0]["start_line"] == 15, blocks[0]["start_line"]
    print("PASS [⑩] 综合夹具：3 块（data class / Mirror 均未混入），顺序正确")


# ---------------------------------------------------------------------------
# ⑪ W71 G5（#29 消费面）：applies_to_runtime_literals 正常提取（块内具名位 + 行号）
# ---------------------------------------------------------------------------
def test_applies_to_runtime_literals_extracted():
    lines = r'''
object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "a",
            appliesToRuntime = "0.17.1",
        ),
        ModelPreset(
            label = "b",
            appliesToRuntime = "0.17.1",
        ),
    )
}
'''.splitlines()
    lits = pb.applies_to_runtime_literals(lines)
    assert lits == [("0.17.1", 6), ("0.17.1", 10)], lits
    print("PASS [⑪] appliesToRuntime 字面量提取（值 + 行号，块内具名位）")


# ---------------------------------------------------------------------------
# ⑫ W71 G5（#29 消费面）：字符串内容里的同款字样不假红（迁移修掉旧 grep 假红面）
#     旧 bash grep 不锚定块 + 注释排除只认行首 ⇒ note 字符串里的
#     `appliesToRuntime = "9.9.9"` 会被误提取 ⇒ 假红。本消费双重锚定 ⇒ 免疫。
# ---------------------------------------------------------------------------
def test_applies_to_runtime_string_pollution_not_false_red():
    lines = r'''
object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "prose-ok",
            note = "历史：appliesToRuntime = \"9.9.9\" 曾漂移（字符串内容，不算字面量）",
            appliesToRuntime = "0.17.1",
        ),
    )
}
'''.splitlines()
    lits = pb.applies_to_runtime_literals(lines)
    assert lits == [("0.17.1", 7)], lits
    print("PASS [⑫] 字符串内容里的 appliesToRuntime = \"9.9.9\" 不假红（只取块内具名位真值）")


# ---------------------------------------------------------------------------
# ⑬ W71 G5（#29 消费面）：零字面量合法态（字段全 null / 无赋值 ⇒ 空列表）
# ---------------------------------------------------------------------------
def test_applies_to_runtime_zero_literal_legal():
    lines = r'''
object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "null-ok",
            appliesToRuntime = null,
        ),
        ModelPreset(
            label = "absent-ok",
        ),
    )
}
'''.splitlines()
    lits = pb.applies_to_runtime_literals(lines)
    assert lits == [], lits
    print("PASS [⑬] 零字面量合法态（null / 无赋值 ⇒ 空列表，非 fail-closed）")


def main():
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failed = 0
    for t in tests:
        try:
            t()
        except AssertionError as e:
            failed += 1
            print("FAIL [%s] %s" % (t.__name__, e))
    print("----")
    print("preset_blocks 模块单测：PASS=%d FAIL=%d" % (len(tests) - failed, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
