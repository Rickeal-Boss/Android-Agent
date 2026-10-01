# Wave 43 交接 —— 本地构建解禁 + 真机验收首轮（adb 通道打通 · 热熔断重设计 · 采样口径纠偏 · 预设实测校正）

- 分支：`harness-improve`
- CI 终态 SHA：`774fd28`
- 日期：2026-10-01（晚）→ 2026-10-02（凌晨）
- 前序：[`handoff-20261001-wave42-policy-collapse-split-guards.md`](handoff-20261001-wave42-policy-collapse-split-guards.md)

---

## 一、CI 终态（SHA `774fd28`，双绿）

| 工作流 | Run | 结论 | 耗时 |
|---|---|---|---|
| Build（build.yml） | `36890764609` | ✅ success | Unit tests 1m32s / Lint 3m14s / Assemble Debug 3m49s |
| Release（release.yml） | `36890764484` | ✅ success | 16:16:37 → 16:24:03（约 7.5 min） |

产物：`liquidagent-release-apk` **70.23 MB** / `liquidagent-debug` **39.35 MB** / `mapping` **4.35 MB**（与 Wave 41/42 持平）。
本地验证（推送前）：`arch-guard` **18 项全 OK**、`arch-guard-selftest` **PASS=30**、`compileDebugKotlin` 全仓绿、`:core-data:testDebugUnitTest` 26/26 绿、本地 `assembleDebug` BUILD SUCCESSFUL（3m10s）。

## 二、交付明细（7 commit）

| Commit | 内容 |
|---|---|
| `5800bf4` | **K0 文档同步**（v12 复评 P0）：撤销 README / `docs/02-ci.md` 的「CI 是唯一验证通道」表述（J2 解禁后已失真） |
| `8bccb06` | **热熔断计量重设计**：主判据改**电池温度 ≥ 44.9℃**（sticky broadcast 实时读）；SEVERE 由 Abort 降级为**轮间冷却 10s**（首轮豁免）；CRITICAL+ 保留 Abort；`batteryTempTenths` 未接线 = 旧行为逐字节保留。测试 18 → 26 |
| `eb55330` | **能力位编辑入口**：接通死代码 `onEditCapabilities`（ModelCard「编辑能力位」chip + `ModelCapabilitiesDialog`） |
| `3e08b8f` | **thought 通道输出解析声明**：`THOUGHT_CHANNEL_DEFS` 下发全部 5 处 `ConversationConfig` 构造点 |
| `6f865d1` | **采样口径纠偏**：工具 run 不再死用 `temp 0.4 / topK 20`，改**以模型档案为下界** |
| `3b0eac3` | **预设按真机实测校正**：Gemma-4 GPU 变体标退化 & 摘推荐；CPU 变体转推荐；E4B GPU 标未验证 |
| `774fd28` | **挂账台账 +2**：模型加载后小样本自检（立项）、Gemma-4 GPU 变体退化（上游错配） |

## 三、真机验收首轮结果（OPPO PDRM00 / Android 13 / 骁龙 8s Gen3）

**通道**：USB adb（无线三次配对成功但主端口恒 RST，环境阻断）→ debug 包 `com.rickeal.agent.debug` 装机 → `logcat -s LiquidAgentDiag:V` + journal（`run-as` 读 `/files/journal/<cid>/run_*.jsonl`）。

| 模型 | 结论 |
|---|---|
| MiniCPM-V-4-int8 | ❌ `Unsupported model type`：容器声明 `LlmModelType=13 (MiniCpmV4)`，该值 2026-09-16 才进 litert-lm main，**0.17.1 不含** |
| MiniCPM5-2B_int4 | ✅ 会话 `role=on`、工具调用正常；temp 0.5（档案感知后 0.4→0.5）回归通过 |
| Qwen2.5-1.5B | ✅ 正常结束 |
| **Gemma-4 E2B · CPU**（2.41GB） | ✅ **通过**：多轮文本协议工具调用（`current_time`/`ask_actor`/`memory_write`）解析正常，2 轮 + 1 轮两次正常收尾 |
| **Gemma-4 E2B · GPU**（2.0GB） | ❌ **输出退化**：temp 0.4 → n-gram 死锁；temp 1.0/64 → 采样出 `<unused1556>/<unused4347>` 等**保留未训练 token**（logits 分布退化）；容器 section 仅 `tf_lite_artisan_text_decoder`，CPU 后端 engine init `NOT_FOUND` |

**Wave 43 热策略真机实证**：`SEVERE → 热降档 maxTokens 1024→512`，run **未被掐死**（旧行为是 Abort）✅

## 四、三条根因与误判复盘（重要）

1. **热熔断误判**：第一反应是「ROM 热档失真」。实测 `dumpsys thermalservice` 证明 **SoC 结温 83℃**（外壳/电池被外置散热压住，芯片内部来不及传导）→ ROM 正确。但用户裁决成立：SoC 阈值各厂商调教不同、跨设备不可比 ⇒ 改用电池温度。
2. **Gemma 输出退化误判两轮**：① 先以为是 thought 通道未声明（修了，无效——模型连通道名都生成不准，吐 `<|channel>तरह`）；② 再以为是 `enable_thinking` 未注入（打开思考能力位，仍复现）。**真因是采样口径被压垮**：对照 `google-ai-edge/gallery` 的 `data/Consts.kt`（`DEFAULT_TEMPERATURE=1.0 / TOPK=64 / TOPP=0.95`）发现它**根本不覆盖模型官方采样**。
3. **判据沉淀**：端侧模型「坏容器」判据 = 采样出**保留未训练 token**（`<unusedNNNN>`）或多语种碎片。出现即说明 logits 分布退化，**换温度/topK 救不回来**，应当怀疑容器↔运行时错配而不是继续调参。

## 五、挂账（下波）

| 条目 | 状态 |
|---|---|
| **模型加载后小样本自检（健康度门禁）** | 🆕 **立项**（README 台账）。候选判据：保留 token / n-gram 重复率 / 空输出；重启前提：阈值裁定、触发时机、好坏容器各验不误报、不拖慢加载 |
| **Gemma-4 GPU 特化变体输出退化** | 🆕 入账。三条重启路径：bump runtime / 改用上游单文件双后端容器 / 下架 |
| **错误驱动降级（AUDIO_ENCODER NOT_FOUND 自动去 audio 重建）** | 立账未做。范本：`embedding_engine_impl.cc:903` 的「先查 section 再建」（与硬依赖路径 `litert_lm_lib.cc:872` 并存，上游实现不一致） |
| **预设改用上游单文件双后端容器** | 候选。`litert-lm` README 实证 `gemma-4-E4B-it.litertlm --backend=gpu` 单文件跑 GPU；Gallery 的 3n E2B 2.6GB / E4B 3.7GB 同为单文件多模态 ⇒ 可得「一个文件多模态 + CPU/GPU 自由切」 |
| N1 / P2-2 / termsVersion / G4 / 0 tags / 接缝排序 | 维持（压真机或用户决策） |

## 六、真机验收入口（下波从此处接续）

```bash
ADB="D:/WBfil/2026-09-18-18-27-37/_j2env/sdk/platform-tools/adb.exe"
MSYS_NO_PATHCONV=1 "$ADB" -s 13309cc8 logcat -v time LiquidAgentDiag:V *:S   # 诊断通道（缓冲区已扩 16M）
MSYS_NO_PATHCONV=1 "$ADB" -s 13309cc8 shell "run-as com.rickeal.agent.debug ls -t files/journal/"   # 逐 run journal
```
- 验收清单：`docs/10-device-acceptance.md` §11（本轮已回收：§11.1 引擎/回显部分、热熔断、工具调用；**剩余 ~30 条未回收**）
- 本地构建：`bash _ci-tools/localbuild.sh [gradle task]`（增量 15s；踩坑见 `_ci-tools/LOCALBUILD.md`）

## 七、团队与流程

本波为**主理人直做 + 真机配合**（未组建专家团）：用户手动操作手机、主理人读日志逐条验收。
J2 本地编译基建（`_j2env/`，全落 D 盘）本波首次投入实战，把验证闭环从「CI 20 分钟」压缩到「本地 1 分钟」——**这是本波所有快速试错的前提**。
