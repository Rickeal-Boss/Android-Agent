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

W73 新增判据面（G2 通用写路径 + G1 双断言）：
  ⑨ test_set_string_key_rejects_key_outside_whitelist
        白名单外键 ⇒ 拒绝执行（exit 2），且**先于** --snap 检查（拒决不趁晚）
  ⑩ test_string_value_span_and_equal_length_patch_confined_to_value_region
        字符串键值文本区定位（单/多字节 varint 两形态）+ 等长补丁差异只落值文本区
        + 布尔键 fail-closed + 非等长拒绝（UUID 跨度判据不直搬的重设计判据）
  ⑪ test_set_string_key_out_of_span_write_judged_red_with_rollback
        设备读回值文本区**外**有差异 ⇒ 判红（exit 1）且自动回滚被行使（离线 monkeypatch）

W73 P0 结构闸判据面（2026-10-18 事故后新增；修法 = 整文件结构闸 + 夹具带包裹）：
  ⑫ test_validate_accepts_real_device_format_sample
        真机快照实测字节（带外层 `0a <len>` 包裹）⇒ validate 放行；空文件合法
  ⑬ test_validate_rejects_p0_crash_blob
        P0 事故件逐字（43 B 缺包裹混合形态，曾写回真机致崩溃循环）⇒ DatastorePatchError；
        同类畸形（截断 / 键后缺 0x12 / 未知 Value 标签）一并拒
  ⑭ test_apply_refuses_malformed_blob_before_device_io
        `_apply` 写前闸：畸形字节 ⇒ 拒绝且 device_push/copy_back 零调用（离线 monkeypatch）
  ⑮ test_fixture_builders_match_real_device_format
        夹具自洽：make_blob / make_string_blob / 长值条目全部过 validate（混合形态不可能再现）

W73 G1 双断言判据面（解锁后落盘：find_key_offset count + gap≤4 + P4-a）：
  ⑯ test_find_key_offset_rejects_duplicated_key
        断言①：键出现 0 / ≥2 次 ⇒ 拒绝（含读取路径 fail-closed 到 None）
  ⑰ test_find_key_offset_gap_assertion
        断言②：伪键态（count==1、结构合法、gap=6）⇒ 拒绝；真编码 gap=4 与
        紧凑编码 gap=2（莫斯线口径）⇒ 放行

---------------------------------------------------------------------------
变异测试说明（删/改一条判据 ⇒ 哪个用例必红）—— 用于证明本测试网**非 vacuous**：
**已实测执行的变异（W72 本波真跑过，非推测）**：把 `find_uuid_span` 里的
`UUID_PATTERN.search(blob, ki)` 改成切片法 `UUID_PATTERN.search(blob[ki:])`（= W70/W71
的真实根因，漏 `+ ki`）⇒ `Ran 13 tests … FAILED (failures=3)`，红的正是
`test_patch_is_equal_length_and_diff_confined_to_uuid_region`（①，差异全部越界）、
`test_find_uuid_span_uses_absolute_pos_not_slice`（⑥）、
`test_patch_preserves_preceding_bool_field`（补丁污染 `has_seen_intro` —— 与两次真机
事故的现象逐字一致）。⇒ 这张网确实拦得住那个坑，不是形式主义。

**W73 P0 事后加固（2026-10-18，用户报告「同意用户协议后闪退」的事后复盘）**：
⑪ 绿面曾只 mock `device_read` 不 mock `_apply` ⇒ 带设备跑套件会把夹具写进真机
datastore（用户 P0 的直接肇事路径）。修法：⑪ 绿面补 mock `_apply`；索引不可用
测试补 mock `device_read`；模块级 `run_adb` 绊线（见文件中部）让整套测试**可证明
离线**。此后「本文件不碰 adb」从注释承诺变为机械判据。

**W73 G1 双断言已落盘**（解锁通知后：真机设备写已全部完成、终态经 verify 与 927B
全量快照逐字节一致）：`find_key_offset` 双断言（count≠1 + gap≤[UUID_MAX_KEY_GAP]=4，
两断言同 commit 联用）+ P4-a（非 ASCII UUID 归入 DatastorePatchError）。变异自证已
实测：把定位打回 `blob.find(KEY)` 首现形态 ⇒ ⑯/⑰ 必红、还原复绿（输出留 W73 回报；
变异体副本 `_ci-tools/_w73_g2_draft/datastore_tool_MUTANT_firsthit.py`）。gap 口径：
W73 实拉设备快照 key_end→UUID 文本 = 4B（`12 <vlen> 2a <tlen>` 结构字节）；莫斯线
申报的「gap=2B」= 键尾→`0x2a` 串标签距（同族口径，小 2）—— 阈值 4 对两个口径兼容，
真编码恰在界内（零余量，如实申报；形态变化致 gap>4 ⇒ 判红查清，禁放宽）。

其余变异（未逐一执行，按代码路径推导）：
  · 把 `DiffReport.ok` 的 `out_of_span` 条件删掉 ⇒ **④ 必红**。
  · 把 `cmd_set_active_model` 的 `if not args.snap` 守卫删掉 ⇒ **② 必红**
    （会走到 `read_bytes(None)` 抛 TypeError 而不是返回 2）。
  · 把索引对账（`validate_uuid_in_index`）删掉 ⇒ **③ 必红**。
  · 把 `is_identical` 改成「UUID 子串在不在」⇒ **⑤ 必红**（差一字节但子串仍在）。
  · 把 `UUID_FULLMATCH` 校验删掉 ⇒ **⑦ 必红**（35 字符 UUID 会被静默接受）。
  · 把白名单检查（`KNOWN_SETTINGS_KEYS`）删掉 ⇒ **⑨ 必红**（白名单外键被放行）。
  · 把 `patch_string_value` 的等长断言删掉 ⇒ **⑩ 必红**（非等长被静默接受）。
  · 把 `cmd_set_string_key` 的 `out_of_span` 判红 + 回滚分支删掉 ⇒ **⑪ 必红**
    （越界写入被判绿 = W70 形态在通用路径复发）。
  · 把 `_apply` 里的 `validate_preferences_blob` 调用删掉 ⇒ **⑭ 必红**
    （畸形字节直通设备 = W73 P0 事故通道重开）。
  · 把 `validate_preferences_blob` 的 `entry_end > total` 边界检查删掉 ⇒ **⑬ 必红**
    （截断文件被放行）；把 `blob[pos] != 0x0A` 顶层检查删掉 ⇒ **⑬ 必红**（缺包裹直通）。
  · 把 `find_key_offset` 的 count 断言删掉 ⇒ **⑯ 必红**（重复键被放行；已实测：变异体
    `_ci-tools/_w73_g2_draft/datastore_tool_MUTANT_firsthit.py` ⇒ ⑯/⑰ 双红）。
  · 把 `find_key_offset` 的 gap 断言删掉 ⇒ **⑰ 必红**（伪键态被放行；同上变异体）。
  · 把 `patch_active_model_id` 的 `encode("ascii")` try 归回裸调用 ⇒ 非 ASCII UUID
    裸 traceback（P4-a 复发；行为面无单测钉子，靠本列表申报）。
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
# 🔴 W73 P0 事后加固：全测试套件 adb 绊线（离线契约机械化）
#
# 事故：W73 G2 的测试 ⑪ 绿面只 monkeypatch 了 `device_read`、**没** patch `_apply`
# ⇒ 带真机跑 `python scripts/tests/test_datastore_tool.py` 时，绿面的
# `set-string-key` 经**真实** `_apply` 把测试夹具（`make_string_blob` 打成
# `LIGHT!` 的 43B/45B 字节）push+cp 进了真机 datastore ⇒ 真机 DataStore
# CorruptionException 崩溃循环（用户现象：同意用户协议后 App 闪退）。
# `device_read`/`device_push`/`device_copy_back` 全部经 `run_adb` 收口 ⇒ 在此
# 炸掉任何未被 mock 的真实 adb 调用：本套件从此**可证明**离线（CI 席/开发席
# 带设备跑测试也不再能碰设备一根字节）。
# ---------------------------------------------------------------------------
def _tripwire_run_adb(adb, args):  # pragma: no cover - 永不应被调用
    raise AssertionError(
        "测试套件禁止真实 adb 调用（W73 P0：⑪ 绿面曾只 mock device_read 不 mock "
        "_apply，把夹具写进真机 datastore 致用户 App 崩溃循环）。设备层必须整体 "
        "monkeypatch（_apply / device_read / device_push / device_copy_back / "
        "_cleanup_remote）；如确需走 run_adb 的语义，请在测试内 mock device_read。"
    )


dst.run_adb = _tripwire_run_adb

# ---------------------------------------------------------------------------
# 夹具：合成一个最小 PreferencesProto 字节串
# ---------------------------------------------------------------------------
# 真实形态（`preferences_pb` 的 map<string, Value> 条目，实测快照
# `0a 14 0a 0e has_seen_intro 12 02 08 01` 佐证 —— W73 P0 事故后逐字节核对）：
#   0x0a <elen>  0x0a <klen> "<key>"  0x12 <vlen> <Value 子消息>
#   ^^^^^^^^^^  外层 map 条目包裹，**不可省**（W73 P0：省了它真机直接 CorruptionException）
# 字符串值 = Value 里的 field 5（wire type 2）⇒ 子消息 = 0x2a <tlen> "<text>"
#
# 🔴 W73 P0 事故备忘（2026-10-18）：本文件曾经的 `_string_entry`/`_bool_entry`
# 缺外层 `0a <elen>` 包裹，而 `_str_entry` 有 ⇒ `make_string_blob` 产出**混合形态**
# （43 B：首条无包裹 + 次条有包裹 + `LIGHT!`），该字节流被当快照写回真机 ⇒ 真机
# DataStore 崩溃循环（用户现象：同意用户协议后闪退）。修法 = 全部夹具统一带包裹
# + 工具侧 `validate_preferences_blob` 整文件结构闸（见 datastore_tool.py）。
OLD_UUID = "0e0d75b0-36c0-49ed-b97e-5f4be8103ee0"   # 设备真原值（W71 §六.3 留痕）
#: 与 [OLD_UUID] **恰好差 28 个字节**（= W71 实测的正常切换形态；8 个字符相同）。
NEW_UUID = "f1dba342-bb68-5d95-cd86-39bd5cea3ee0"


def _varint(n: int) -> bytes:
    """protobuf varint 编码（长度 >127 走多字节 —— `inference_config` 同款）。"""
    out = bytearray()
    while True:
        bits = n & 0x7F
        n >>= 7
        if n:
            out.append(bits | 0x80)
        else:
            out.append(bits)
            return bytes(out)


def _string_entry(key: bytes, text: bytes) -> bytes:
    """字符串 map 条目（**带外层包裹**，与设备快照实测同款式）。"""
    value = b"\x2a" + _varint(len(text)) + text
    entry_inner = b"\x0a" + _varint(len(key)) + key + b"\x12" + _varint(len(value)) + value
    return b"\x0a" + _varint(len(entry_inner)) + entry_inner


def _bool_entry(key: bytes, flag: bool) -> bytes:
    """布尔 map 条目（**带外层包裹** —— W73 P0 前此函数漏了包裹）。"""
    value = b"\x08" + (b"\x01" if flag else b"\x00")
    entry_inner = b"\x0a" + _varint(len(key)) + key + b"\x12" + _varint(len(value)) + value
    return b"\x0a" + _varint(len(entry_inner)) + entry_inner


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
            # 🔴 W73 P0：load_index 取本地文件失败会回落**真实** device_read（经 run_adb）
            # ⇒ 必须 mock 掉，否则「不可达设备」语义被真机状态顶替（且违背套件离线契约）。
            def fake_read(adb, pkg, rel):
                raise dst.DatastorePatchError("模拟无设备/索引读不到")

            old_read = dst.device_read
            dst.device_read = fake_read
            try:
                code, out = self._run([
                    "set-active-model", "--uuid", NEW_UUID, "--snap", snap,
                    "--model-index", os.path.join(tmp, "missing.json"),
                ])
            finally:
                dst.device_read = old_read
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


# ---------------------------------------------------------------------------
# ⑨ / ⑩ / ⑪ W73 G2（N-1）：set-string-key 通用写路径
# ---------------------------------------------------------------------------
def _str_entry(key: bytes, text: bytes) -> bytes:
    """字符串 map 条目（与设备快证实测同款式：0a <elen> 0a <klen> key 12 <vlen> 2a <tlen> text）。"""
    value = b"\x2a" + bytes([len(text)]) + text
    entry_inner = b"\x0a" + bytes([len(key)]) + key + b"\x12" + bytes([len(value)]) + value
    return b"\x0a" + bytes([len(entry_inner)]) + entry_inner


def _long_str_entry(key: bytes, text: bytes) -> bytes:
    """长字符串条目（vlen/tlen 走**多字节 varint**，钉 `_read_varint` —— 实测
    `inference_config` 就是 `12 d9 03 2a d6 03` 形态；按 1 字节长度硬编码会算错）。"""
    def _varint(n: int) -> bytes:
        out = bytearray()
        while True:
            bits = n & 0x7F
            n >>= 7
            if n:
                out.append(bits | 0x80)
            else:
                out.append(bits)
                return bytes(out)

    value = b"\x2a" + _varint(len(text)) + text
    entry_inner = b"\x0a" + bytes([len(key)]) + key + b"\x12" + _varint(len(value)) + value
    return b"\x0a" + _varint(len(entry_inner)) + entry_inner


def make_string_blob(text: bytes = b"SYSTEM") -> bytes:
    """夹具：bool 前置 + `dark_mode` 字符串条目（键名取自 SettingsRepository 白名单）。"""
    return _bool_entry(b"has_seen_intro", True) + _str_entry(b"dark_mode", text)


class StringKeyTests(unittest.TestCase):
    """⑨ 白名单拒绝 / ⑩ 值文本区定位+等长 diff / ⑪ 越界回滚。"""

    def _run(self, argv):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = dst.main(argv)
        return code, buf.getvalue()

    def test_set_string_key_rejects_key_outside_whitelist(self):
        """⑨ 白名单外 ⇒ exit 2，且**先于** --snap 检查（连快照都不要求）。"""
        for bad_key in ("not_a_settings_key", "active_model_id", "javax.net", "proto"):
            code, out = self._run(["set-string-key", bad_key, "v"])
            self.assertEqual(code, dst.EXIT_REFUSED,
                            "白名单外键 %r 未返回 exit 2：%s" % (bad_key, out))
            self.assertIn("白名单", out)
        # active_model_id 有专用命令：拒绝文案要点名这件事（防有人拿通用路径绕 UUID 护栏）
        code, out = self._run(["set-string-key", "active_model_id", "x"])
        self.assertIn("set-active-model", out)
        # 白名单内的已知布尔键走到定位期才拒（fail-closed，非白名单问题）——
        # 证明白名单拦的是「键存不存在」，定位拦的是「值区形态对不对」。
        with tempfile.TemporaryDirectory() as tmp:
            snap = _write_temp(tmp, "snap.pb", make_string_blob())
            code, out = self._run(["set-string-key", "has_seen_intro", "true",
                                   "--snap", snap])
            self.assertEqual(code, dst.EXIT_REFUSED, "布尔键被通用路径放行：%s" % out)
            self.assertIn("不是字符串载荷", out)

    def test_string_value_span_and_equal_length_patch_confined_to_value_region(self):
        """⑩ 值文本区定位（单/多字节 varint）+ 等长补丁差异只落值文本区。"""
        # --- 单字节 varint 形态 ---
        blob = make_string_blob(b"SYSTEM")
        start, end = dst.find_string_value_span(blob, b"dark_mode")
        self.assertEqual(blob[start:end], b"SYSTEM")
        patched, span = dst.patch_string_value(blob, b"dark_mode", "LIGHT!")
        self.assertEqual(span, (start, end))
        self.assertEqual(len(patched), len(blob), "等长替换被破坏")
        # 差异只允许落在值文本区内（UUID 跨度判据不直搬：这里是 text 段）
        report = dst.classify_diff(blob, patched, span)
        self.assertTrue(report.ok, "等长补丁被判红：%s" % report.describe())
        self.assertEqual(report.out_of_span, ())
        self.assertEqual(report.count, 6)
        for pos in report.positions:
            self.assertGreaterEqual(pos, span[0])
            self.assertLess(pos, span[1])
        # 条目标签 / 长度前缀 / 前置 bool 条目逐字节不变
        head = _bool_entry(b"has_seen_intro", True)
        self.assertEqual(patched[:len(head)], blob[:len(head)])
        self.assertEqual(patched[end:], blob[end:])
        self.assertEqual(patched[:start], blob[:start])

        # --- 多字节 varint 形态（长 JSON 值，实测 inference_config 同款） ---
        long_text = ('{"sampling":{"temperature":0.7},"pad":"%s"}' % ("x" * 400)).encode()
        blob2 = make_string_blob(b"SYSTEM") + _long_str_entry(b"inference_config", long_text)
        start2, end2 = dst.find_string_value_span(blob2, b"inference_config")
        self.assertEqual(blob2[start2:end2], long_text)
        # 钉「多字节 varint 确实被行使」：tlen > 127 ⇒ 长度前缀是 2 字节 varint，
        # text_start 前第 2 字节必带 varint 延续位（0x80）—— 按 1 字节长度硬编码
        # 会在这里算错区（「差一字节」族坑，实测 inference_config 同款）。
        self.assertGreater(len(long_text), 127, "夹具未逼出多字节 varint（本形态 vacuous）")
        self.assertTrue(blob2[start2 - 2] & 0x80, "tlen varint 不是多字节形态")
        new_long = ('{"sampling":{"temperature":0.9},"pad":"%s"}' % ("x" * 400)).encode()
        patched2, span2 = dst.patch_string_value(blob2, b"inference_config", new_long.decode())
        self.assertEqual(len(patched2), len(blob2))
        report2 = dst.classify_diff(blob2, patched2, span2)
        self.assertTrue(report2.ok, "长值补丁被判红：%s" % report2.describe()[:80])
        self.assertEqual(report2.out_of_span, ())
        # 长度前缀区（varint 字节）不动 ⇒ 只改文本
        self.assertEqual(patched2[:start2], blob2[:start2])
        self.assertEqual(patched2[end2:], blob2[end2:])

        # --- 布尔键 fail-closed（值区非字符串载荷） ---
        with self.assertRaises(dst.DatastorePatchError, msg="布尔键被当字符串定位"):
            dst.find_string_value_span(blob, b"has_seen_intro")

        # --- 非等长拒绝（长度前缀会动） ---
        with self.assertRaises(dst.DatastorePatchError, msg="非等长替换被接受"):
            dst.patch_string_value(blob, b"dark_mode", "LIGHT")

        # --- 伪键位拒绝：键名前不是 `0a <键长>` 条目头（同名文本蹭在值区里） ---
        sneaky = _str_entry(b"note", b"see dark_mode here")
        self.assertEqual(sneaky.count(b"dark_mode"), 1, "夹具应保证键名唯一（红的是条目头断言）")
        with self.assertRaises(dst.DatastorePatchError, msg="值区里的同名文本被当成键位"):
            dst.find_string_value_span(sneaky, b"dark_mode")

    def test_set_string_key_out_of_span_write_judged_red_with_rollback(self):
        """⑪ 设备读回出现值文本区**外**差异 ⇒ 判红（exit 1）+ 自动回滚被行使。

        离线 monkeypatch 设备层：`_apply` 只记账不碰 adb；`device_read` 第一次读回
        「补丁写错位置」的字节（W70 形态：改到了别的条目），回滚读回快照。
        """
        snap_blob = make_string_blob(b"SYSTEM")
        apply_calls = []
        reads = []

        def fake_apply(adb, pkg, local_path):
            apply_calls.append(local_path)

        def fake_read(adb, pkg, rel):
            if not reads:
                # 写后读回：差异落在**别的条目**（前置 bool 条目头）⇒ 越界
                tampered = bytearray(snap_blob)
                tampered[2] ^= 0xFF
                reads.append(1)
                return bytes(tampered)
            # 回滚后读回：与快照逐字节一致
            reads.append(2)
            return snap_blob

        old_apply, old_read = dst._apply, dst.device_read
        dst._apply, dst.device_read = fake_apply, fake_read
        try:
            with tempfile.TemporaryDirectory() as tmp:
                snap = _write_temp(tmp, "snap.pb", snap_blob)
                code, out = self._run(["set-string-key", "dark_mode", "LIGHT!",
                                       "--snap", snap])
        finally:
            dst._apply, dst.device_read = old_apply, old_read

        self.assertEqual(code, dst.EXIT_RED, "越界写入未判红：%s" % out)
        self.assertIn("之外", out)
        self.assertEqual(len(apply_calls), 2, "回滚未行使（_apply 应被调 2 次：写+回滚）")
        self.assertIn("已自动回滚", out)

        # 对照绿面：设备读回 = 补丁本体（差异全在值文本区）⇒ exit 0
        # 🔴 W73 P0：绿面也必须 patch `_apply`（只 patch device_read 会让**真实**
        # `_apply` 把夹具推真设备 —— G2 当波即因此把设备 datastore 写坏、用户 App
        # 崩溃循环）。此后绿面与红面同规格：设备层全 mock。
        snap_blob2 = make_string_blob(b"SYSTEM")
        reads2 = []
        applies2 = []

        def fake_read_ok(adb, pkg, rel):
            if not reads2:
                reads2.append(1)
                head = _bool_entry(b"has_seen_intro", True)
                return head + _str_entry(b"dark_mode", b"LIGHT!")
            return snap_blob2

        def fake_apply_ok(adb, pkg, local_path):
            applies2.append(local_path)

        old_read2, old_apply2 = dst.device_read, dst._apply
        dst.device_read = fake_read_ok
        dst._apply = fake_apply_ok
        try:
            with tempfile.TemporaryDirectory() as tmp:
                snap2 = _write_temp(tmp, "snap.pb", snap_blob2)
                code2, out2 = self._run(["set-string-key", "dark_mode", "LIGHT!",
                                         "--snap", snap2])
        finally:
            dst.device_read, dst._apply = old_read2, old_apply2
        self.assertEqual(code2, dst.EXIT_OK, "绿面被判红：%s" % out2)
        self.assertIn("补丁完成", out2)
        self.assertEqual(len(applies2), 1, "绿面未走一次 _apply")


# ---------------------------------------------------------------------------
# ⑫-⑮ W73 P0 结构闸回归（整文件 validate + _apply 写前闸 + 夹具自洽）
# ---------------------------------------------------------------------------
class StructureValidationTests(unittest.TestCase):
    """⑫ 真机格式样本放行｜⑬ P0 崩溃字节拒杀｜⑭ `_apply` 写前闸｜⑮ 夹具自洽。

    背景（W73 P0 事故，2026-10-18）：缺外层 `0a <len>` 包裹的畸形夹具被当快照
    写回真机 ⇒ 真机 DataStore CorruptionException 崩溃循环（用户现象：同意用户
    协议后 App 闪退）。修法 = 工具侧 `validate_preferences_blob` 整文件结构闸
    （所有设备写路径经 `_apply` 统一先过）+ 夹具统一带包裹。本类 = 回归钉子。
    """

    #: 真机快照前两条（P0 事故当日 15:07 全量快照 `ds_pre.pb` 头 45 字节逐字）：
    #: entry1 = has_seen_intro=true（`0a 14` 包裹），entry2 = is_tos_accepted=true（`0a 15` 包裹）。
    REAL_SAMPLE = bytes.fromhex(
        "0a140a0e6861735f7365656e5f696e74726f12020801"
        "0a150a0f69735f746f735f616363657074656412020801"
    )

    #: P0 事故件逐字（43 B，曾把真机 datastore 写成这个 ⇒ 崩溃循环）：
    #: 旧 `make_string_blob(b"LIGHT!")` 产出 = 无包裹 bool 条目 + 有包裹 string 条目的混合形态。
    P0_CRASH_BLOB = bytes.fromhex(
        "0a0e6861735f7365656e5f696e74726f12020801"
        "0a150a096461726b5f6d6f646512082a064c4947485421"
    )

    def test_validate_accepts_real_device_format_sample(self):
        """⑫ 真机实测格式（带外层包裹）⇒ 放行；工具夹具与空文件同样放行。"""
        dst.validate_preferences_blob(self.REAL_SAMPLE)  # 不抛即通过
        dst.validate_preferences_blob(make_blob())
        dst.validate_preferences_blob(make_string_blob())
        dst.validate_preferences_blob(make_string_blob(b"LIGHT!"))
        dst.validate_preferences_blob(b"")  # 空文件合法（DataStore 读成 emptyPreferences）

    def test_validate_rejects_p0_crash_blob(self):
        """⑬ P0 事故字节（缺外层包裹）⇒ DatastorePatchError —— 回归钉子。"""
        with self.assertRaises(dst.DatastorePatchError, msg="P0 崩溃字节被结构闸放行"):
            dst.validate_preferences_blob(self.P0_CRASH_BLOB)
        # 错误信息必须点破形态（缺 0x0a 条目标签），不能是泛泛的 parse 错
        try:
            dst.validate_preferences_blob(self.P0_CRASH_BLOB)
        except dst.DatastorePatchError as exc:
            self.assertIn("0x0a", str(exc), "错误信息未点出条目标签：%s" % exc)
        # 同类畸形形态一并拒：截断条目 / 键后缺 0x12 / 值载荷未知标签
        with self.assertRaises(dst.DatastorePatchError, msg="截断条目被放行"):
            dst.validate_preferences_blob(self.REAL_SAMPLE[:20])
        bad_value_tag = bytearray(self.REAL_SAMPLE)
        bad_value_tag[18] = 0x0A  # entry1 键后的 0x12 值标签改掉
        with self.assertRaises(dst.DatastorePatchError, msg="键后缺 0x12 被放行"):
            dst.validate_preferences_blob(bytes(bad_value_tag))
        unknown_tag = bytearray(self.REAL_SAMPLE)
        unknown_tag[20] = 0x99  # 值载荷首字节（bool 0x08）改成未知标签
        with self.assertRaises(dst.DatastorePatchError, msg="未知 Value 标签被放行"):
            dst.validate_preferences_blob(bytes(unknown_tag))

    def test_apply_refuses_malformed_blob_before_device_io(self):
        """⑭ `_apply` 对畸形字节一个字节都不推设备（P0 直接闸口，离线 monkeypatch）。"""
        pushed, copied, cleaned = [], [], []
        old_push, old_copy, old_clean = dst.device_push, dst.device_copy_back, dst._cleanup_remote
        dst.device_push = lambda adb, pkg, local: pushed.append(local)
        dst.device_copy_back = lambda adb, pkg, remote, rel: copied.append(remote)
        dst._cleanup_remote = lambda adb, remote: cleaned.append(remote)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                bad = _write_temp(tmp, "malformed.pb", self.P0_CRASH_BLOB)
                with self.assertRaises(dst.DatastorePatchError, msg="畸形字节过了 _apply 闸"):
                    dst._apply("adb", "pkg", bad)
                # 绿面：真格式放行且设备 IO 被行使
                ok = _write_temp(tmp, "ok.pb", make_string_blob())
                dst._apply("adb", "pkg", ok)
        finally:
            dst.device_push, dst.device_copy_back, dst._cleanup_remote = old_push, old_copy, old_clean
        self.assertEqual(pushed, [dst._remote_name(ok)], "畸形字节被推送 / 绿面未推送：%r" % pushed)
        self.assertEqual(len(copied), 1)
        self.assertEqual(len(cleaned), 1)

    def test_fixture_builders_match_real_device_format(self):
        """⑮ 夹具自洽：所有 builder 产出必须是带外层包裹的真格式（混合形态不再可能）。"""
        dst.validate_preferences_blob(make_blob())
        dst.validate_preferences_blob(make_string_blob(b"LIGHT!"))
        blob = make_string_blob(b"SYSTEM") + _long_str_entry(b"inference_config", b"x" * 400)
        dst.validate_preferences_blob(blob)
        # 旧事故形态自证：无包裹 bool + 有包裹 string（曾静默通过所有既有值区护栏）
        legacy_mixed = b"\x0a\x0ehas_seen_intro\x12\x02\x08\x01" + _str_entry(
            b"dark_mode", b"LIGHT!"
        )
        self.assertEqual(legacy_mixed, self.P0_CRASH_BLOB, "事故形态复刻与真机字节不符")
        with self.assertRaises(dst.DatastorePatchError):
            dst.validate_preferences_blob(legacy_mixed)


# ---------------------------------------------------------------------------
# ⑯ / ⑰ W73 G1（复审27-莫斯线 P3-A 勘误块 A）：find_key_offset 双断言
# ---------------------------------------------------------------------------
class KeyOffsetAssertionTests(unittest.TestCase):
    """⑯ count≠1 拒绝｜⑰ gap>4 拒绝（两断言同 commit 联用；真编码 gap=4 放行）。

    设备隔离（W73 P0 纪律）：两类都是**纯函数层**（find_key_offset /
    find_uuid_span 不经设备层），仍按套件纪律整体 mock 五个设备函数，并在例末
    断言「零设备调用」—— 把「不碰 adb」从注释承诺变成机械判据（P0 肇事路径 =
    只 mock device_read 不 mock _apply；本类连 device_read 都不经过）。
    """

    def setUp(self):
        self.calls = []
        self._old = {name: getattr(dst, name) for name in
                     ("_apply", "device_read", "device_push", "device_copy_back",
                      "_cleanup_remote")}
        for name in self._old:
            setattr(dst, name, lambda *a, **k: self.calls.append(name))

    def tearDown(self):
        for name, fn in self._old.items():
            setattr(dst, name, fn)
        self.assertEqual(self.calls, [],
                         "G1 两例走了设备层（%r）—— 纯函数断言不碰设备" % (self.calls,))

    def test_find_key_offset_rejects_duplicated_key(self):
        """⑯ 断言①：键出现 0 / ≥2 次都拒绝（首现哪一次都是猜）。"""
        # 0 次：夹具里没这个键（白名单字符串键，无 active_model_id）
        with self.assertRaises(dst.DatastorePatchError, msg="缺键被放行"):
            dst.find_key_offset(make_string_blob())
        # ≥2 次：键名撞结构（夹具与主树现行款式一致：全条目带外层包裹）
        dup = make_blob() + _string_entry(dst.KEY, NEW_UUID.encode("ascii"))
        self.assertEqual(dup.count(dst.KEY), 2, "夹具未造出重复键")
        with self.assertRaises(dst.DatastorePatchError, msg="重复键被放行"):
            dst.find_key_offset(dup)
        with self.assertRaises(dst.DatastorePatchError, msg="重复键被 find_uuid_span 放行"):
            dst.find_uuid_span(dup)
        # 读取路径不炸但读不到（fail-closed 到 None，而不是首现值）
        self.assertIsNone(dst.read_active_model_id(dup))

    def test_find_key_offset_gap_assertion(self):
        """⑰ 断言②：gap>4 拒绝（伪键态）；真编码 gap=4 / 紧凑编码 gap=2 放行。"""
        uuid_text = NEW_UUID.encode("ascii")
        # 伪键 fixture：键名唯一、**结构合法**（带外层包裹、过 validate_preferences_blob
        # —— P0 结构闸拦不住它：合法文件里键名出现处并非其值区），但键尾之后 6 字节
        # 才见 UUID（值区不紧凑）。「仅伪键」态只有 gap 断言能兜底。
        junk = b"\x3f\x3f"
        payload = b"\x2a" + bytes([len(junk) + len(uuid_text)]) + junk + uuid_text
        entry_inner = (b"\x0a" + bytes([len(dst.KEY)]) + dst.KEY
                       + b"\x12" + bytes([len(payload)]) + payload)
        fake = (_bool_entry(b"has_seen_intro", True)
                + b"\x0a" + bytes([len(entry_inner)]) + entry_inner)
        self.assertEqual(fake.count(dst.KEY), 1, "伪键夹具应键名唯一（否则红的是断言①）")
        dst.validate_preferences_blob(fake)  # 结构合法 ⇒ 只有 gap 断言能拦
        with self.assertRaises(dst.DatastorePatchError, msg="伪键态（gap=6）被放行"):
            dst.find_key_offset(fake)
        with self.assertRaises(dst.DatastorePatchError, msg="伪键态被 find_uuid_span 放行"):
            dst.find_uuid_span(fake)

        # 真编码 fixture：gap=4（W73 实拉设备快照同款式 `12 <vlen> 2a <tlen>`）⇒ 放行
        real_shape = _bool_entry(b"has_seen_intro", True) + _string_entry(dst.KEY, uuid_text)
        start, end = dst.find_uuid_span(real_shape)
        self.assertEqual(real_shape[start:end], uuid_text)
        self.assertEqual(start - (real_shape.find(dst.KEY) + len(dst.KEY)), 4,
                         "真编码 gap 漂离实测值 4（形态变了必须查清，禁默默放宽）")

        # 紧凑编码 fixture：gap=2（键尾后 2 字节即 UUID 文本 —— 莫斯线申报口径）⇒ 放行
        entry_inner2 = (b"\x0a" + bytes([len(dst.KEY)]) + dst.KEY
                        + b"\x12" + bytes([len(uuid_text)]) + uuid_text)
        compact = (_bool_entry(b"has_seen_intro", True)
                   + b"\x0a" + bytes([len(entry_inner2)]) + entry_inner2)
        start2, end2 = dst.find_uuid_span(compact)
        self.assertEqual(compact[start2:end2], uuid_text)
        self.assertEqual(start2 - (compact.find(dst.KEY) + len(dst.KEY)), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
