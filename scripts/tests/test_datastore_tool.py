#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""`scripts/datastore_tool.py` 的**纯离线**单测（W72 C 治理槽）。

    ⛔ 本文件**不碰 adb、不碰真机、不碰设备**：全部夹具都是内存里合成的
    `preferences_pb` 字节串。这也是把「定位 / 替换 / 差异报告 / 索引校验」抽成
    可 import 纯函数的直接理由 —— 会写坏设备的那部分逻辑必须能在 CI 里被判红。

运行方式（两种都可）：
    python scripts/tests/test_datastore_tool.py
    python -m unittest discover -s scripts/tests -p "test_datastore_tool.py" -v

钉住的判据面（与 W72 单 2 一一对应）：
  ① test_patch_is_equal_length_and_diff_confined_to_uuid_region
        等长替换正确 + 差异**只**落在 UUID 区（本仓实测形态 = 28 字节差）
  ② test_set_active_model_without_snapshot_refused
        无 `--snap` ⇒ 拒绝执行（exit 2），且**在任何设备 I/O 之前**返回
  ③ test_set_active_model_rejects_uuid_absent_from_index
        UUID 不在模型索引 ⇒ 拒绝执行（exit 2）并列出全部可用 UUID
  ④ test_diff_outside_uuid_region_judged_red
        差异越出 UUID 区 ⇒ 判红（W70/W71 的损坏形态）
  ⑤ test_restore_identical_bytes_pass
        快照字节 = 还原字节 ⇒ 通过；差一字节 ⇒ 不通过
  ⑥ test_find_uuid_span_uses_absolute_pos_not_slice
        **根因钉子**：`PATTERN.search(blob, ki)` 的偏移是绝对的；切片法
        `PATTERN.search(blob[ki:])` 的 `m.start()` 是相对的（W70/W71 两次事故根因）
  ⑦ test_patch_rejects_malformed_uuid        非法 UUID 文本 ⇒ 拒绝（等长前提）
  ⑧ test_index_uuids_accepts_both_shapes      索引两种形态都能对账

---------------------------------------------------------------------------
变异测试说明（删/改一条判据 ⇒ 哪个用例必红）—— 用于证明本测试网**非 vacuous**：
**已实测执行的变异（W72 本波真跑过，非推测）**：把 `find_uuid_span` 里的
`UUID_PATTERN.search(blob, ki)` 改成切片法 `UUID_PATTERN.search(blob[ki:])`（= W70/W71
的真实根因，漏 `+ ki`）⇒ `Ran 13 tests … FAILED (failures=3)`，红的正是
`test_patch_is_equal_length_and_diff_confined_to_uuid_region`（①，差异全部越界）、
`test_find_uuid_span_uses_absolute_pos_not_slice`（⑥）、
`test_patch_preserves_preceding_bool_field`（补丁污染 `has_seen_intro` —— 与两次真机
事故的现象逐字一致）。⇒ 这张网确实拦得住那个坑，不是形式主义。

其余变异（未逐一执行，按代码路径推导）：
  · 把 `DiffReport.ok` 的 `out_of_span` 条件删掉 ⇒ **④ 必红**。
  · 把 `cmd_set_active_model` 的 `if not args.snap` 守卫删掉 ⇒ **② 必红**
    （会走到 `read_bytes(None)` 抛 TypeError 而不是返回 2）。
  · 把索引对账（`validate_uuid_in_index`）删掉 ⇒ **③ 必红**。
  · 把 `is_identical` 改成「UUID 子串在不在」⇒ **⑤ 必红**（差一字节但子串仍在）。
  · 把 `UUID_FULLMATCH` 校验删掉 ⇒ **⑦ 必红**（35 字符 UUID 会被静默接受）。
---------------------------------------------------------------------------
"""
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import datastore_tool as dst  # noqa: E402

# ---------------------------------------------------------------------------
# 夹具：合成一个最小 PreferencesProto 字节串
# ---------------------------------------------------------------------------
# 真实形态（`preferences_pb` 的 map<string, Value> 条目）：
#   0x0a <len> "<key>"   0x12 <len> <Value 子消息>
# 字符串值 = Value 里的 field 5（wire type 2）⇒ 子消息 = 0x2a <len> "<text>"
OLD_UUID = "0e0d75b0-36c0-49ed-b97e-5f4be8103ee0"   # 设备真原值（W71 §六.3 留痕）
#: 与 [OLD_UUID] **恰好差 28 个字节**（= W71 实测的正常切换形态；8 个字符相同）。
NEW_UUID = "f1dba342-bb68-5d95-cd86-39bd5cea3ee0"


def _string_entry(key: bytes, text: bytes) -> bytes:
    value = b"\x2a" + bytes([len(text)]) + text
    return b"\x0a" + bytes([len(key)]) + key + b"\x12" + bytes([len(value)]) + value


def _bool_entry(key: bytes, flag: bool) -> bytes:
    value = b"\x08" + (b"\x01" if flag else b"\x00")
    return b"\x0a" + bytes([len(key)]) + key + b"\x12" + bytes([len(value)]) + value


def make_blob(uuid_text=OLD_UUID) -> bytes:
    """最小可判夹具：`has_seen_intro`（布尔，排在**前**面）+ `active_model_id`（字符串）。

    刻意把布尔字段排在前面 —— W70/W71 两次写坏毁的正是它（切片法把 UUID 覆写到
    文件头部，`has_seen_intro` 首启标记丢失 ⇒ 引导页复现）。
    """
    return _bool_entry(b"has_seen_intro", True) + _string_entry(
        dst.KEY, uuid_text.encode("ascii")
    )


def _write_temp(path_dir, name, data):
    path = os.path.join(path_dir, name)
    with open(path, "wb") as handle:
        handle.write(data)
    return path


# ---------------------------------------------------------------------------
# ① 等长替换 + 差异只落 UUID 区
# ---------------------------------------------------------------------------
class PatchTests(unittest.TestCase):
    def test_patch_is_equal_length_and_diff_confined_to_uuid_region(self):
        blob = make_blob()
        span = dst.find_uuid_span(blob)
        patched, patched_span = dst.patch_active_model_id(blob, NEW_UUID)

        # 等长：长度前缀不变的前提
        self.assertEqual(len(patched), len(blob), "等长替换被破坏")
        self.assertEqual(span, patched_span, "UUID 区定位在补丁前后应一致")
        # 值确实换成了新 UUID
        self.assertEqual(dst.read_active_model_id(patched), NEW_UUID)

        # 差异只允许落在 UUID 区，且本夹具形态 = 28 字节差（W71 实测形态）
        report = dst.classify_diff(blob, patched, span)
        self.assertTrue(report.ok, "合法补丁被判红：%s" % report.describe())
        self.assertEqual(report.out_of_span, (), "差异越出 UUID 区：%s" % report.describe())
        self.assertEqual(report.count, 28, "夹具与 W71 实测形态不符：%s" % report.describe())
        self.assertFalse(report.length_changed)
        # 差异位置必须逐个落在 [start, end) 内
        for pos in report.positions:
            self.assertGreaterEqual(pos, span[0])
            self.assertLess(pos, span[1])

    def test_patch_preserves_preceding_bool_field(self):
        """补丁不得动到排在前面 `has_seen_intro`（W70/W71 的损坏面）。"""
        blob = make_blob()
        patched, _ = dst.patch_active_model_id(blob, NEW_UUID)
        intro = _bool_entry(b"has_seen_intro", True)
        self.assertEqual(
            patched[:len(intro)], blob[:len(intro)],
            "补丁污染了前一个字段（首启标记丢失形态）",
        )

    def test_patch_rejects_malformed_uuid(self):
        blob = make_blob()
        for bad in ("not-a-uuid", OLD_UUID[:-1], OLD_UUID + "0", ""):
            with self.assertRaises(dst.DatastorePatchError, msg="非法 UUID 被接受：%r" % bad):
                dst.patch_active_model_id(blob, bad)

    def test_find_uuid_span_uses_absolute_pos_not_slice(self):
        """⑥ 根因钉子：pos 参数 = 绝对位置；切片法的 `m.start()` 是相对位置。

        W70/W71 两次事故的全部根因就是把这个相对值当绝对值用（少加 `ki`）——
        补丁于是落到 `blob[0 + relative]`（文件头部），覆写 `has_seen_intro`。
        """
        blob = make_blob()
        ki = blob.find(dst.KEY)
        self.assertGreater(ki, 0, "夹具应让键不在文件开头（否则绝对/相对同值，钉不住）")

        relative = dst.UUID_PATTERN.search(blob[ki:]).start()
        absolute = dst.find_uuid_span(blob)[0]

        self.assertEqual(absolute, ki + relative, "编译态 pos 语义被改坏")
        self.assertNotEqual(absolute, relative, "绝对/相对位置相等 ⇒ 本用例 vacuous")

        # 反面演示：拿相对位置当绝对值会写进前面的字段区（不真的写，只算位置）
        wrong_start = relative
        self.assertLess(wrong_start, ki, "相对位置应落在键之前（= 头部字段区）")
        self.assertNotEqual(wrong_start, absolute)


# ---------------------------------------------------------------------------
# ② / ③ CLI 拒绝路径（离线：两条都在任何设备 I/O 之前返回）
# ---------------------------------------------------------------------------
class RefusalTests(unittest.TestCase):
    def _run(self, argv):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = dst.main(argv)
        return code, buf.getvalue()

    def test_set_active_model_without_snapshot_refused(self):
        code, out = self._run(["set-active-model", "--uuid", NEW_UUID])
        self.assertEqual(code, dst.EXIT_REFUSED, "无 --snap 未返回 exit 2：%s" % out)
        self.assertIn("必须带 --snap", out)

    def test_set_active_model_rejects_uuid_absent_from_index(self):
        with tempfile.TemporaryDirectory() as tmp:
            snap = _write_temp(tmp, "snap.pb", make_blob())
            index = os.path.join(tmp, "models.json")
            other = "851b9e2d-0f24-4616-a437-56509b79e574"
            with open(index, "w", encoding="utf-8") as handle:
                json.dump([{"id": other, "fileName": "MiniCPM5-2B_int4.litertlm"}], handle)
            code, out = self._run([
                "set-active-model", "--uuid", NEW_UUID, "--snap", snap,
                "--model-index", index,
            ])
        self.assertEqual(code, dst.EXIT_REFUSED, "索引未命中未返回 exit 2：%s" % out)
        self.assertIn("不在模型索引里", out)
        self.assertIn(other, out, "拒绝时必须列出全部可用 UUID")

    def test_set_active_model_refuses_when_index_unavailable(self):
        with tempfile.TemporaryDirectory() as tmp:
            snap = _write_temp(tmp, "snap.pb", make_blob())
            code, out = self._run([
                "set-active-model", "--uuid", NEW_UUID, "--snap", snap,
                "--model-index", os.path.join(tmp, "missing.json"),
            ])
        self.assertEqual(code, dst.EXIT_REFUSED, "索引取不到应 fail-closed：%s" % out)
        self.assertIn("取不到模型索引", out)


# ---------------------------------------------------------------------------
# ④ 差异越界 ⇒ 判红
# ---------------------------------------------------------------------------
class DiffVerdictTests(unittest.TestCase):
    def test_diff_outside_uuid_region_judged_red(self):
        blob = make_blob()
        span = dst.find_uuid_span(blob)
        # 模拟 W70/W71 的损坏形态：头部布尔字段的值区被改
        tampered = bytearray(blob)
        tampered[0] = 0xFF
        report = dst.classify_diff(blob, bytes(tampered), span)
        self.assertFalse(report.ok, "越界差异被判绿：%s" % report.describe())
        self.assertEqual(report.out_of_span, (0,))
        self.assertIn("UUID 区外", report.describe())

    def test_length_change_judged_red(self):
        blob = make_blob()
        span = dst.find_uuid_span(blob)
        report = dst.classify_diff(blob, blob + b"\x00", span)
        self.assertTrue(report.length_changed)
        self.assertFalse(report.ok, "长度变化被判绿")
        self.assertIn("长度已变", report.describe())

    def test_too_many_diff_bytes_judged_red(self):
        """差异字节数 > UUID 长度 ⇒ 不可能来自一次合法替换。"""
        blob = b"\x00" * 8 + make_blob()
        span = (8 + dst.find_uuid_span(make_blob())[0], 8 + dst.find_uuid_span(make_blob())[1])
        report = dst.DiffReport(
            positions=tuple(range(dst.UUID_LEN + 1)), span=span, length_changed=False,
        )
        self.assertFalse(report.ok)


# ---------------------------------------------------------------------------
# ⑤ 逐字节相等判据（restore / verify）
# ---------------------------------------------------------------------------
class IdentityTests(unittest.TestCase):
    def test_restore_identical_bytes_pass(self):
        snap = make_blob()
        # 「还原字节 = 快照字节」⇒ 通过
        self.assertTrue(dst.is_identical(snap, snap))
        report = dst.classify_diff(snap, snap, dst.find_uuid_span(snap))
        self.assertEqual(report.count, 0)
        self.assertTrue(report.ok)

        # 差一字节（且 UUID 子串仍在！）⇒ 不通过
        broken = bytearray(snap)
        broken[0] ^= 0xFF
        self.assertFalse(dst.is_identical(snap, bytes(broken)),
                         "「UUID 子串还在」不能当还原成功的判据")
        self.assertIn(OLD_UUID, bytes(broken).decode("latin-1"))


# ---------------------------------------------------------------------------
# ⑧ 索引解析两种形态
# ---------------------------------------------------------------------------
class IndexTests(unittest.TestCase):
    def test_index_uuids_accepts_both_shapes(self):
        flat = [{"id": "a"}, {"id": "b"}, {"nope": 1}, "junk"]
        self.assertEqual(dst.index_uuids(flat), ["a", "b"])
        wrapped = {"models": [{"id": "a"}, {"id": "b"}]}
        self.assertEqual(dst.index_uuids(wrapped), ["a", "b"])
        self.assertEqual(dst.index_uuids(None), [])

    def test_validate_uuid_in_index_reports_available(self):
        hit, available = dst.validate_uuid_in_index("b", [{"id": "a"}, {"id": "b"}])
        self.assertTrue(hit)
        self.assertEqual(available, ["a", "b"])
        hit, available = dst.validate_uuid_in_index("z", [{"id": "a"}])
        self.assertFalse(hit)
        self.assertEqual(available, ["a"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
