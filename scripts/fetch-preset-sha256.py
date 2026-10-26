#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""fetch-preset-sha256.py — 内置模型预设的 sha256 锚点**复核工具**（W86-02）

## 为什么需要它

`ModelPresets.all` 的 `sha256` 字段是「下载完整性校验」的唯一机械依据。它一旦填错，
**正常下载会被判成篡改**（用户下完 2~4 GB 拿不到模型）；留空则校验面静默失效（vacuous）。
所以这个字段不能手抄、不能猜，必须有可重跑的来源与判据。

## 锚点来源

ModelScope 镜像仓的 `repo/files` API（`/api/v1/models/litert-community/<repo>/repo/files`）
返回每个文件的 `Sha256` + `Size`。本仓沙箱无法直连 HuggingFace（502），故以 ModelScope
为权威侧；**跨源同一性**用体积代理判据：`ModelScope.Size == 预设 sizeBytes`（后者取自 HF
API，2026-09 记录）。体积逐字节相等 + 仓库/文件名相同 ⇒ 视为同一份字节。

## 用法

    python scripts/fetch-preset-sha256.py            # 复核（默认）：逐条打印判据，异常即 exit 1
    python scripts/fetch-preset-sha256.py --emit     # 额外打印可直接粘进 ModelPresets.kt 的行

## 判据（三种退出状态）

  OK        体积一致 + 预设锚点与 API 摘要一致（或预设为空但本次未回填）
  MISMATCH  体积不一致 ⇒ **不得回填**（跨源同一性不成立）
  STALE     预设锚点非空但与 API 摘要不同 ⇒ 锚点过期/填错

⚠️ 体积不一致的条目**必须保持 `sha256 = null`**：宁可跳过校验（fail-open，有 ERROR 留痕），
   也不能让主源下载的正常文件被镜像侧的摘要误拒。

## 已知例外必须显式登记（`KNOWN_UNANCHORED`）

「永远 exit 1 的检查等于没人跑」—— 与「永远 exit 0 的软守卫」是同一个病。
故体积不一致的条目进 `KNOWN_UNANCHORED`（带理由），只有**新的**不一致 / 已回填锚点变 STALE
才判红。新增例外必须同时写进 `docs/15-backlog.md`，否则例外会变成永久豁免。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PRESETS = os.path.join(
    REPO_ROOT, "core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt"
)
API = "https://www.modelscope.cn/api/v1/models/litert-community/{repo}/repo/files?Revision=master"

# 已知「刻意不回填」的条目：label -> 理由。任何**新的**体积不一致都不在此列 ⇒ 判红。
KNOWN_UNANCHORED = {
    "Qwen2.5 1.5B · q8": (
        "镜像体积 1597922856 != 预设 1597931520（差 8664B）⇒ 不是同一份字节；"
        "HF 侧摘要本仓取不到，故保持 fail-open（见 ModelPresets.sha256 KDoc / docs/15 W86-02）"
    ),
}

# 每条预设是 `        ModelPreset(` 起、到下一个同类构造器（或列表收尾）止的一个块。
# 逐块解析（而不是用一条跨字段正则）：`sha256` 在 `ModelPresets.all` 里排在 `sizeBytes` 之后，
# 单条「url→sizeBytes」区间抓不到它 —— 这正是本脚本首版把 13 条已回填锚点全读成「未回填」的原因。
BLOCK_SPLIT = "\n        ModelPreset("
LABEL_RE = re.compile(r'label = "([^"]+)"')
URL_RE = re.compile(r'url = "\$BASE/([^/]+)/resolve/main/([^"]+)"')
SIZE_RE = re.compile(r"sizeBytes = (\d+),")
SHA_RE = re.compile(r'sha256 = "([0-9a-fA-F]{64})"')


def fetch(repo: str) -> dict | None:
    """取镜像仓文件清单；网络失败返回 None（调用方判 API_FAIL，不静默跳过）。"""
    try:
        out = subprocess.run(
            ["curl", "-s", "--ssl-no-revoke", "-m", "30", API.format(repo=repo)],
            capture_output=True, text=True, encoding="utf-8",
        )
    except Exception:
        return None
    try:
        return json.loads(out.stdout)
    except Exception:
        return None


def parse_presets() -> list[dict]:
    src = open(PRESETS, encoding="utf-8").read()
    # 只取 `val all: List<ModelPreset> = listOf(` 之后的部分，避免把 data class 定义当成预设。
    start = src.find("val all: List<ModelPreset>")
    if start < 0:
        sys.exit("::error:: 未找到 ModelPresets.all 定义（结构漂移？禁 fail-open）")
    blocks = src[start:].split(BLOCK_SPLIT)[1:]
    rows = []
    for block in blocks:
        label_m, url_m, size_m = LABEL_RE.search(block), URL_RE.search(block), SIZE_RE.search(block)
        if not (label_m and url_m and size_m):
            continue
        sha_m = SHA_RE.search(block)
        rows.append({
            "label": label_m.group(1),
            "repo": url_m.group(1),
            "file": url_m.group(2),
            "sizeBytes": int(size_m.group(1)),
            "sha256": sha_m.group(1).lower() if sha_m else None,
        })
    if not rows:
        sys.exit("::error:: 未从 ModelPresets.kt 解析出任何预设（正则漂移？禁 fail-open）")
    return rows


def main() -> int:
    emit = "--emit" in sys.argv
    rows = parse_presets()
    print(f"解析预设 {len(rows)} 条（{os.path.relpath(PRESETS, REPO_ROOT)}）\n")
    header = f"{'判据':<10} {'预设':<26} {'体积差':>9}  摘要"
    print(header)
    print("-" * len(header))

    bad = 0
    emit_lines = []
    for r in rows:
        data = fetch(r["repo"])
        if not data or data.get("Code") != 200:
            print(f"{'API_FAIL':<10} {r['label']:<26} {'-':>9}  （仓库 {r['repo']} 取不到）")
            bad += 1
            continue
        hit = next(
            (f for f in data["Data"]["Files"]
             if f.get("Path") == r["file"] or f.get("Name") == r["file"]),
            None,
        )
        if not hit:
            print(f"{'NOFILE':<10} {r['label']:<26} {'-':>9}  仓库里没有 {r['file']}")
            bad += 1
            continue

        api_sha = (hit.get("Sha256") or "").lower()
        delta = (hit.get("Size") or 0) - r["sizeBytes"]

        if delta != 0:
            if r["label"] in KNOWN_UNANCHORED:
                print(f"{'KNOWN':<10} {r['label']:<26} {delta:>+9}  已知例外（保持 null）：{KNOWN_UNANCHORED[r['label']]}")
            else:
                # 跨源同一性不成立 ⇒ 必须留空，且这是需要人看的**新**异常
                print(f"{'MISMATCH':<10} {r['label']:<26} {delta:>+9}  不得回填（体积不等 ⇒ 不是同一份字节）")
                bad += 1
            continue

        if r["sha256"] and r["sha256"] != api_sha:
            print(f"{'STALE':<10} {r['label']:<26} {delta:>+9}  预设={r['sha256'][:16]}… API={api_sha[:16]}…")
            bad += 1
            continue

        state = "OK" if r["sha256"] else "OK(未回填)"
        print(f"{state:<10} {r['label']:<26} {delta:>+9}  {api_sha[:16]}…")
        if not r["sha256"]:
            emit_lines.append((r["label"], api_sha))

    if emit and emit_lines:
        print("\n# ---- 可粘进 ModelPresets.kt 的行（体积判据已过）----")
        for label, sha in emit_lines:
            print(f'# {label}\nsha256 = "{sha}",')

    ok = sum(1 for r in rows) - bad
    print(f"\n汇总：{ok}/{len(rows)} 条判据通过，{bad} 条异常。")
    if bad:
        print("::error:: 存在体积不一致 / 锚点过期 / 取数失败 —— 见上表（禁静默放行）")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
