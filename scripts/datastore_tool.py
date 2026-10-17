#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""datastore 字节补丁**唯一受许工具**（W72 C 治理槽）。

    ⛔ **datastore 只经本工具，禁止一次性脚本。**

真机切换 `active_model_id` 的唯一稳健通道 = 改 datastore 的 `preferences_pb` 字节
（应用没有对外暴露「设置激活模型」的编译态 API，UI 点选又要过一整轮 Compose 自动化）。
这条通道本仓已经**同日三犯**，所以本波把它从「交接纪律」升级为「唯一受许工具 + 离线单测」：

| 事故 | 波次 | 现象 | 根因 |
|---|---|---|---|
| ① | **W70 §一.7**（2026-10-17 13:5x） | 设备 datastore 被写坏：`has_seen_intro` 首启标记丢失、引导页复现 | 一次性实验脚本用**切片搜索**（`blob[ki:]` 后取 `m.start()`）拿相对位置当绝对位置用，补丁落进了**别的字段的值区** |
| ② | **W71 §一.6**（同日 14:xx） | 同型再犯：R1 的 UUID 被写进 `has_seen_intro` 值区、首启标记再次丢失 | 恢复脚本踩了 W70 档案里**已经明写**的同一个坑（切片法的 `m.start()` 未 `+ ki`） |
| ③ | **W72（本波）** | 无新事故 —— 本波把纪律变成结构：唯一入口 + 强制快照 + 强制逐字节 diff + 超范围自动回滚 | 前两次的共同点：**纪律写在交接里，脚本每次重写一遍**。写了三遍的纪律等于没写 |

## 为什么「编译态 API 写法」才是正确的（W70 §六.4 定案）

```python
ki = blob.find(b"active_model_id")     # 绝对偏移
m = PATTERN.search(blob, ki)           # ⚠️ pos 参数 = 绝对位置
start, end = m.start(), m.end()        # ⇒ start/end 也是**绝对**位置
```

`re.Pattern.search(string, pos)` 的 `pos` 是**对 `string` 的绝对偏移**，返回的
`match.start()` 同样是相对 `string` 的绝对偏移。所以「先 find 键、再 search(pos=ki)」
拿到的偏移可以直接索引 `blob`，不需要任何加法。

**切片法错在哪**：`blob[ki:]` 造了一个新 bytes 对象，`PATTERN.search(blob[ki:])` 的
`m.start()` 是**相对切片**的偏移（0 起点）。把它直接当绝对位置用，补丁就落在
`blob[0 + m.start()]` = 文件**头部**，而不是键之后 —— 于是键名、长度前缀、以及排在
前面的别的字段（`has_seen_intro` 就在头部区）被覆写成 UUID 文本 ⇒ 首启标记丢失。
切片法要正确必须写 `ki + m.start()`；漏掉那个 `+ ki` 就是 W70/W71 两次事故的全部根因。

本工具**只提供编译态写法**，并在 [find_uuid_span] 里把 `pos=ki` 写成不可绕过的唯一实现
⇒ 想再踩这个坑，得先把本文件改坏（那会当场被 `scripts/tests/test_datastore_tool.py` 判红）。

## 落点说明（偏离申报）

第十六审曾建议把本工具放工作区 `_ci-tools/`（仓外、不入库、不进 CI）。主理人裁断**改放仓内**
`scripts/`：仓外脚本无法进版本控制、无法进 CI 单测、也无法被下一个人 `grep` 到 —— 而本工具
存在的唯一理由就是「下一个人不要重写一遍」。故落 `scripts/datastore_tool.py`，纯函数部分
（定位 / 替换 / 差异报告 / 索引校验）与 CLI 编排分离，`scripts/tests/` 里纯离线单测。

## 子命令

    snapshot        --out FILE                     读设备 datastore 落盘为快照（打印字节数 + 当前 UUID）
    set-active-model --uuid UUID --snap FILE [...] 等长替换 active_model_id（强制快照 + 强制 diff + 超范围自动回滚）
    restore         --snap FILE [...]              用快照覆盖设备 datastore（逐字节断言，不等即 exit 1）
    verify          --snap FILE [...]              只读比对（打印差异位置）

退出码：`0` 成功 / `1` 判红（补丁越界、还原不等、设备读取失败）/ `2` **拒绝执行**
（缺 `--snap`、UUID 非法、UUID 不在模型索引里、索引取不到 —— 一律 fail-closed，绝不带病写）。

## 不变量（`scripts/tests/test_datastore_tool.py` 钉死）

1. 替换**等长**：写入后字节数必须与快照一致（UUID 恒 36 字符，长度前缀不变）。
2. 差异**只允许落在 UUID 区**：`[uuid_start, uuid_end)` 之外的任何差异字节 ⇒ 判红 + 自动回滚。
   （W71 实测的正常切换 = UUID 区 28 字节差；本工具不硬编码 28，只钉「不得越界」，
   因为差几个字节取决于新旧 UUID 有几个字符相同。）
3. `restore` / `verify` 的判据是**逐字节**，不是「UUID 子串在不在」—— 子串在错误位置
   也会「通过」，那正是 W70/W71 写坏后看起来「成功」的原因。

## 用法示例

    python scripts/datastore_tool.py snapshot --out /tmp/ds_snap.pb
    python scripts/datastore_tool.py set-active-model \\
        --uuid 0e0d75b0-36c0-49ed-b97e-5f4be8103ee0 --snap /tmp/ds_snap.pb \\
        --model-index /tmp/models_index.json
    python scripts/datastore_tool.py verify --snap /tmp/ds_snap.pb
    python scripts/datastore_tool.py restore --snap /tmp/ds_snap.pb
"""
import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from typing import List, Optional, Sequence, Tuple

# ---------------------------------------------------------------------------
# 常量（设备侧口径）
# ---------------------------------------------------------------------------
#: datastore 文件（相对 app `filesDir`）。改这里等于改全部子命令的目标。
DATASTORE_REL = "files/datastore/liquid_agent_settings.preferences_pb"
#: 模型索引（相对 `filesDir`），`ModelRepository.indexDirectory` 落盘位置。
INDEX_REL = "files/model_index/models.json"
#: 设备侧中转目录（`adb push` 目的地；`run-as` 无法直接写宿主机）。
REMOTE_TMP = "/data/local/tmp"
#: 默认包名 = debug 变体（本仓真机取证一律装 debug）。
DEFAULT_PKG = "com.rickeal.agent.debug"
#: 默认 adb = 本工作区 SDK（有线 adb 铁律；调用方可覆盖）。
DEFAULT_ADB = "D:/WBfil/2026-09-18-18-27-37/_j2env/sdk/platform-tools/adb.exe"
#: 目标键（PreferencesProto 的 map key 明文，可直接在字节流里 find）。
KEY = b"active_model_id"
#: UUID 文本长度（含 4 个连字符）。等长替换的前提。
UUID_LEN = 36

#: UUID 模式。`pos` 参数语义见模块头注 —— 这是本文件唯一允许的定位方式。
UUID_PATTERN = re.compile(
    rb"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
)
UUID_FULLMATCH = re.compile(
    rb"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)

EXIT_OK = 0
EXIT_RED = 1
EXIT_REFUSED = 2


class DatastorePatchError(Exception):
    """纯函数层的判红（定位失败 / 非法 UUID / 等长替换失败）。"""


# ---------------------------------------------------------------------------
# 纯函数层（无 I/O、无 adb、无文件系统 ⇒ 可离线单测）
# ---------------------------------------------------------------------------
def find_key_offset(blob: bytes, key: bytes = KEY) -> int:
    """`key` 在 `blob` 里的**绝对**偏移；找不到抛 [DatastorePatchError]。"""
    offset = blob.find(key)
    if offset < 0:
        raise DatastorePatchError(
            "快照里找不到键 %r（快照字节数 %d）—— 多半是拉错了文件或拉成了空文件"
            % (key.decode("ascii", "replace"), len(blob))
        )
    return offset


def find_uuid_span(blob: bytes, key: bytes = KEY) -> Tuple[int, int]:
    """定位 `active_model_id` 的 UUID 值区，返回**绝对** `[start, end)`。

    ⚠️ 这里就是 W70/W71 两次事故的唯一正确写法：`PATTERN.search(blob, ki)` 的 `pos`
    是**绝对位置**，`m.start()` 因此也是绝对位置，可直接索引 `blob`。
    **禁止**改写成 `PATTERN.search(blob[ki:])`（相对位置，必须 `+ ki` 才对）。
    """
    ki = find_key_offset(blob, key)
    match = UUID_PATTERN.search(blob, ki)
    if match is None:
        raise DatastorePatchError(
            "键 %r（绝对偏移 %d）之后找不到 UUID 形态的值 —— 该键可能不是字符串字段，"
            "或 datastore 结构与预期不符；拒绝继续（不带病写）"
            % (key.decode("ascii", "replace"), ki)
        )
    start, end = match.start(), match.end()
    if end - start != UUID_LEN:
        raise DatastorePatchError(
            "UUID 值区长度 %d ≠ %d —— 等长替换前提不成立" % (end - start, UUID_LEN)
        )
    return start, end


def read_active_model_id(blob: bytes, key: bytes = KEY) -> Optional[str]:
    """读快照里的 `active_model_id`；无该键 / 值区非 UUID ⇒ `None`（读取路径不判红）。"""
    try:
        start, end = find_uuid_span(blob, key)
    except DatastorePatchError:
        return None
    return blob[start:end].decode("ascii")


def patch_active_model_id(blob: bytes, new_uuid: str) -> Tuple[bytes, Tuple[int, int]]:
    """把 `active_model_id` 的值**等长**替换为 `new_uuid`。

    返回 `(patched_blob, (start, end))`。三条硬断言（任一不成立抛 [DatastorePatchError]）：
      ① `new_uuid` 必须是 36 字符 UUID 文本（长度前缀不变的前提）；
      ② 原值区长度必须 = [UUID_LEN]；
      ③ 替换后总字节数必须与入参**逐字节等长**（不等长会让 PreferencesProto 的长度
         前缀与后续字段全部错位 —— 这是比写错值更彻底的损坏）。
    """
    new_bytes = new_uuid.encode("ascii", "strict")
    if UUID_FULLMATCH.match(new_bytes) is None:
        raise DatastorePatchError("非法 UUID 文本：%r（须 8-4-4-4-12 十六进制）" % new_uuid)
    start, end = find_uuid_span(blob)
    patched = blob[:start] + new_bytes + blob[end:]
    if len(patched) != len(blob):
        raise DatastorePatchError(
            "等长替换失败：%d → %d 字节（必须相等，否则长度前缀与后续字段全部错位）"
            % (len(blob), len(patched))
        )
    return patched, (start, end)


def diff_positions(old: bytes, new: bytes) -> List[int]:
    """逐字节差异位置（升序）。长度不等时只比到短的一侧，长度差由调用方判红。"""
    return [i for i in range(min(len(old), len(new))) if old[i] != new[i]]


@dataclass(frozen=True)
class DiffReport:
    """一次逐字节比对的结论（纯数据，可直接 print / 单测断言）。"""

    positions: Tuple[int, ...]
    span: Optional[Tuple[int, int]]
    length_changed: bool

    @property
    def count(self) -> int:
        return len(self.positions)

    @property
    def out_of_span(self) -> Tuple[int, ...]:
        """落在 UUID 值区之外的差异位置（`span is None` ⇒ 全部视为越界）。"""
        if self.span is None:
            return self.positions
        start, end = self.span
        return tuple(p for p in self.positions if not (start <= p < end))

    @property
    def ok(self) -> bool:
        """补丁判据：等长 ∧ 无越界 ∧ 差异字节数 ≤ UUID 长度。"""
        return not self.length_changed and not self.out_of_span and self.count <= UUID_LEN

    def describe(self) -> str:
        head = "差异字节 %d 处" % self.count
        if self.length_changed:
            head += "（⚠️ 长度已变 —— 结构性损坏）"
        if self.count:
            shown = ", ".join(str(p) for p in self.positions[:48])
            if self.count > 48:
                shown += " …"
            head += "，位置：%s" % shown
        if self.out_of_span:
            head += "；⛔ UUID 区外 %d 处（位置：%s）" % (
                len(self.out_of_span),
                ", ".join(str(p) for p in self.out_of_span[:24]),
            )
        return head


def classify_diff(old: bytes, new: bytes, span: Optional[Tuple[int, int]]) -> DiffReport:
    """`set-active-model` 的判据：差异只允许落在 UUID 值区内。"""
    return DiffReport(
        positions=tuple(diff_positions(old, new)),
        span=span,
        length_changed=len(old) != len(new),
    )


def is_identical(a: bytes, b: bytes) -> bool:
    """`restore` / `verify` 的判据：**逐字节**相等（不是「UUID 子串在不在」）。"""
    return a == b


def index_uuids(index_obj) -> List[str]:
    """从模型索引（`models.json` 解析结果）里取全部 `id`。

    接受两种形态：`[{...}, {...}]`（本仓真实形态）与 `{"models": [...]}`（防御）。
    非 dict 元素 / 缺 `id` 的条目跳过 —— 索引是**对账**用的，读不全不该让工具崩。
    """
    if isinstance(index_obj, dict):
        index_obj = index_obj.get("models") or []
    if not isinstance(index_obj, list):
        return []
    out: List[str] = []
    for item in index_obj:
        if isinstance(item, dict):
            value = item.get("id")
            if isinstance(value, str) and value:
                out.append(value)
    return out


def validate_uuid_in_index(uuid: str, index_obj) -> Tuple[bool, List[str]]:
    """UUID 对账（W70「UUID 错一位」教训）：返回 `(是否命中, 全部可用 UUID)`。

    为什么要对账：W70 驱动脚本把 Phi-4 的 UUID 写成 `…4c95…`（实际 `…4d95…`），
    等长替换「成功」、diff「只有 UUID 区在变」，但设备上根本没有这个模型 ⇒ 表现为
    「未选中模型」态，被误读成模型行为问题，排查烧掉一整轮。
    """
    available = index_uuids(index_obj)
    return (uuid in available), available


# ---------------------------------------------------------------------------
# I/O 层（adb 编排；全部经 subprocess，`MSYS_NO_PATHCONV=1` 防 Git Bash 改写路径）
# ---------------------------------------------------------------------------
def subprocess_env() -> dict:
    """Git Bash 会把 `/data/local/tmp/x` 改写成 `D:/…` —— 必须显式关掉。"""
    env = dict(os.environ)
    env["MSYS_NO_PATHCONV"] = "1"
    return env


def run_adb(adb: str, args: Sequence[str]) -> subprocess.CompletedProcess:
    return subprocess.run(
        [adb] + list(args),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=subprocess_env(),
        check=False,
    )


def device_read(adb: str, pkg: str, rel: str) -> bytes:
    """`adb exec-out run-as <pkg> cat <rel>` —— 拿原始字节（不走 shell 编码转换）。"""
    proc = run_adb(adb, ["exec-out", "run-as", pkg, "cat", rel])
    if proc.returncode != 0 or not proc.stdout:
        raise DatastorePatchError(
            "读取设备 %s 失败（rc=%d，stdout %d 字节）：%s"
            % (rel, proc.returncode, len(proc.stdout or b""),
               (proc.stderr or b"").decode("utf-8", "replace").strip() or "（无 stderr）")
        )
    return proc.stdout


def device_push(adb: str, local_path: str, remote_path: str) -> None:
    proc = run_adb(adb, ["push", local_path, remote_path])
    if proc.returncode != 0:
        raise DatastorePatchError(
            "adb push %s → %s 失败（rc=%d）：%s"
            % (local_path, remote_path, proc.returncode,
               (proc.stderr or b"").decode("utf-8", "replace").strip())
        )


def device_copy_back(adb: str, pkg: str, remote_path: str, rel: str) -> None:
    """`adb shell run-as <pkg> cp <remote> <rel>` —— run-as 是唯一能写 app 私有目录的通道。"""
    proc = run_adb(adb, ["shell", "run-as", pkg, "cp", remote_path, rel])
    if proc.returncode != 0:
        raise DatastorePatchError(
            "run-as cp %s → %s 失败（rc=%d）：%s"
            % (remote_path, rel, proc.returncode,
               (proc.stderr or b"").decode("utf-8", "replace").strip())
        )


def write_bytes(path: str, blob: bytes) -> None:
    with open(path, "wb") as handle:
        handle.write(blob)


def read_bytes(path: str) -> bytes:
    with open(path, "rb") as handle:
        return handle.read()


def _remote_name(local_path: str) -> str:
    return "%s/%s" % (REMOTE_TMP, os.path.basename(local_path) or "datastore_patch.pb")


def _apply(adb: str, pkg: str, local_path: str) -> None:
    """push + run-as cp 回原位（快照、补丁、回滚三条路径共用同一段编排）。"""
    remote = _remote_name(local_path)
    device_push(adb, local_path, remote)
    device_copy_back(adb, pkg, remote, DATASTORE_REL)


def load_index(adb: str, pkg: str, model_index: Optional[str]) -> Optional[object]:
    """取模型索引：优先 `--model-index` 本地文件，否则从设备拉。取不到返回 None。"""
    if model_index:
        try:
            with open(model_index, "r", encoding="utf-8") as handle:
                return json.load(handle)
        except (OSError, ValueError):
            return None
    try:
        raw = device_read(adb, pkg, INDEX_REL)
    except DatastorePatchError:
        return None
    try:
        return json.loads(raw.decode("utf-8"))
    except ValueError:
        return None


# ---------------------------------------------------------------------------
# 子命令
# ---------------------------------------------------------------------------
def cmd_snapshot(args) -> int:
    blob = device_read(args.adb, args.pkg, DATASTORE_REL)
    write_bytes(args.out, blob)
    active = read_active_model_id(blob)
    print("快照已落盘：%s" % args.out)
    print("字节数：%d" % len(blob))
    print("active_model_id：%s" % (active if active else "（未找到 / 非 UUID 值区）"))
    return EXIT_OK


def cmd_set_active_model(args) -> int:
    # ① 强制快照（无快照 = 无法回滚 = 拒绝执行）
    if not args.snap:
        print("⛔ 拒绝执行：set-active-model 必须带 --snap <快照文件>。")
        print("   理由：没有快照就没有回滚锚点 —— W70/W71 两次写坏都是靠事前快照救回来的。")
        print("   先跑：python scripts/datastore_tool.py snapshot --out <file>")
        return EXIT_REFUSED
    try:
        snap = read_bytes(args.snap)
    except OSError as exc:
        print("⛔ 拒绝执行：快照不可读（%s）" % exc)
        return EXIT_REFUSED

    # ② 定位 + 等长替换（纯函数，先算完再碰设备 —— 算不出来就一个字节都不写）
    try:
        patched, span = patch_active_model_id(snap, args.uuid)
    except DatastorePatchError as exc:
        print("⛔ 拒绝执行：%s" % exc)
        return EXIT_REFUSED

    # ③ UUID 对账（W70「错一位」教训）—— 必须在任何设备写入之前
    index = load_index(args.adb, args.pkg, args.model_index)
    if index is None:
        print("⛔ 拒绝执行：取不到模型索引，无法对账 UUID。")
        print("   用 --model-index <models.json> 指定本地索引（设备侧默认路径：%s）。" % INDEX_REL)
        print("   理由：UUID 错一位也会「等长替换成功」，只是设备上没这个模型 —— fail-closed。")
        return EXIT_REFUSED
    hit, available = validate_uuid_in_index(args.uuid, index)
    if not hit:
        print("⛔ 拒绝执行：UUID %s 不在模型索引里（共 %d 个可用）。" % (args.uuid, len(available)))
        for value in available:
            print("   - %s" % value)
        return EXIT_REFUSED

    # ④ 写设备：先写补丁字节，再读回逐字节核验
    old_uuid = snap[span[0]:span[1]].decode("ascii", "replace")
    print("快照：%s（%d 字节，UUID 区 [%d, %d)）" % (args.snap, len(snap), span[0], span[1]))
    print("UUID：%s → %s" % (old_uuid, args.uuid))
    patched_path = args.snap + ".patched"
    write_bytes(patched_path, patched)
    try:
        _apply(args.adb, args.pkg, patched_path)
        device_now = device_read(args.adb, args.pkg, DATASTORE_REL)
    except DatastorePatchError as exc:
        print("⛔ 写入/读回失败：%s" % exc)
        _rollback(args)
        return EXIT_RED

    # ⑤ 逐字节 diff（判据 = 差异只落在 UUID 区；越界即判红 + 自动回滚）
    report = classify_diff(snap, device_now, span)
    print("diff（设备现状 vs 快照）：%s" % report.describe())
    if report.length_changed:
        print("⛔ 判红：设备文件长度变了（%d → %d）—— 结构性损坏。" % (len(snap), len(device_now)))
        _rollback(args)
        return EXIT_RED
    if report.out_of_span:
        print("⛔ 判红：有差异落在 UUID 区之外 —— 补丁写错了位置（W70/W71 同型）。")
        _rollback(args)
        return EXIT_RED
    if report.count > UUID_LEN:
        print("⛔ 判红：差异 %d 字节 > UUID 长度 %d —— 不可能来自一次合法替换。" % (report.count, UUID_LEN))
        _rollback(args)
        return EXIT_RED
    if report.count == 0:
        print("⚠️ 设备 UUID 已是目标值（零差异）—— 无操作完成，快照保留在 %s。" % args.snap)
    print("✅ 补丁完成：UUID 区 %d 字节差，全部落在 [%d, %d) 内。" % (report.count, span[0], span[1]))
    print("   快照保留在 %s（需要复位时跑 restore --snap %s）。" % (args.snap, args.snap))
    return EXIT_OK


def _rollback(args) -> None:
    """把快照推回设备并逐字节复核（回滚本身失败必须说出来，不能静默）。"""
    try:
        _apply(args.adb, args.pkg, args.snap)
        back = device_read(args.adb, args.pkg, DATASTORE_REL)
    except DatastorePatchError as exc:
        print("⛔⛔ 回滚失败：%s" % exc)
        print("   设备 datastore 可能处于不一致状态 —— 手动执行：")
        print("   %s push %s %s/ && %s shell run-as %s cp %s/%s %s"
              % (args.adb, args.snap, REMOTE_TMP, args.adb, args.pkg,
                 REMOTE_TMP, os.path.basename(args.snap), DATASTORE_REL))
        return
    if is_identical(read_bytes(args.snap), back):
        print("↩️ 已自动回滚到快照，逐字节核验一致。")
    else:
        print("⛔⛔ 回滚后逐字节仍不一致 —— 立即停手，人工介入（勿再写）。")


def cmd_restore(args) -> int:
    try:
        snap = read_bytes(args.snap)
    except OSError as exc:
        print("⛔ 拒绝执行：快照不可读（%s）" % exc)
        return EXIT_REFUSED
    try:
        _apply(args.adb, args.pkg, args.snap)
        device_now = device_read(args.adb, args.pkg, DATASTORE_REL)
    except DatastorePatchError as exc:
        print("⛔ 还原失败：%s" % exc)
        return EXIT_RED
    if not is_identical(snap, device_now):
        report = classify_diff(snap, device_now, None)
        print("⛔ 判红：还原后与快照**逐字节不等**（%s）" % report.describe())
        return EXIT_RED
    print("✅ 还原完成：%d 字节逐字节一致（active_model_id=%s）。"
          % (len(snap), read_active_model_id(device_now) or "（未找到）"))
    return EXIT_OK


def cmd_verify(args) -> int:
    try:
        snap = read_bytes(args.snap)
    except OSError as exc:
        print("⛔ 拒绝执行：快照不可读（%s）" % exc)
        return EXIT_REFUSED
    try:
        device_now = device_read(args.adb, args.pkg, DATASTORE_REL)
    except DatastorePatchError as exc:
        print("⛔ 读取失败：%s" % exc)
        return EXIT_RED
    try:
        span = find_uuid_span(snap)
    except DatastorePatchError:
        span = None
    report = classify_diff(snap, device_now, span)
    print("设备现状 vs 快照：%s" % report.describe())
    print("UUID：快照 %s / 设备 %s"
          % (read_active_model_id(snap) or "（未找到）",
             read_active_model_id(device_now) or "（未找到）"))
    if is_identical(snap, device_now):
        print("✅ 逐字节一致。")
        return EXIT_OK
    print("⚠️ 与快照存在差异（只读比对，未做任何写入）。")
    return EXIT_RED


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="datastore_tool.py",
        description="datastore 字节补丁唯一受许工具（W72 治理槽）—— 禁止一次性脚本。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--pkg", default=DEFAULT_PKG, help="包名（默认 %(default)s）")
    common.add_argument("--adb", default=DEFAULT_ADB, help="adb 路径（默认 %(default)s）")

    sub = parser.add_subparsers(dest="command", required=True)

    p_snap = sub.add_parser("snapshot", parents=[common], help="读设备 datastore 落盘为快照")
    p_snap.add_argument("--out", required=True, help="快照输出路径")
    p_snap.set_defaults(func=cmd_snapshot)

    p_set = sub.add_parser(
        "set-active-model", parents=[common],
        help="等长替换 active_model_id（强制快照 + 强制 diff + 超范围自动回滚）",
    )
    p_set.add_argument("--uuid", required=True, help="目标模型 UUID（36 字符）")
    # ⚠️ 刻意**不**设为 required：缺 --snap 要走到本工具自己的「拒绝执行」文案 + exit 2，
    #    而不是 argparse 的通用 usage 报错（判据要能被人和脚本同时读懂）。
    p_set.add_argument("--snap", default=None, help="事前快照（必填，缺则 exit 2 拒绝执行）")
    p_set.add_argument("--model-index", default=None,
                       help="本地 models.json（缺省从设备 %s 拉）" % INDEX_REL)
    p_set.set_defaults(func=cmd_set_active_model)

    p_restore = sub.add_parser("restore", parents=[common], help="用快照覆盖设备 datastore")
    p_restore.add_argument("--snap", required=True, help="快照文件")
    p_restore.set_defaults(func=cmd_restore)

    p_verify = sub.add_parser("verify", parents=[common], help="只读比对设备现状与快照")
    p_verify.add_argument("--snap", required=True, help="快照文件")
    p_verify.set_defaults(func=cmd_verify)

    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except DatastorePatchError as exc:
        print("⛔ %s" % exc)
        return EXIT_RED


if __name__ == "__main__":
    sys.exit(main())
