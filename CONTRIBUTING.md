# 参与贡献

感谢你对 LiquidAgent 感兴趣。本项目处于开荒阶段，欢迎任何形式的参与：提 issue、改文档、写功能、优化 CI。

## 环境要求

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | **21**（推荐 Temurin） | Kotlin 2.3.0 / Gradle 9.7.1 要求 |
| Android SDK | compileSdk **36** | AGP 会自动下载缺失组件 |
| Gradle | 用仓库自带的 wrapper，**不要**用系统 gradle | `./gradlew` |

## 本地构建

```bash
# 1. 确认 JDK 21
java -version        # 应包含 21

# 2. 编译 debug 包
./gradlew :app:assembleDebug

# 3. 产物位置
#    app/build/outputs/apk/debug/app-debug.apk
```

> 没有本地 JDK / SDK 也能参与：直接开 PR，GitHub Actions 的 **Build** 工作流会给出生死结论。
> CI 设计参见 [`docs/02-ci.md`](docs/02-ci.md)。

## 工程约定（硬性）

这几条来自 [`docs/00-recon-brief.md`](docs/00-recon-brief.md)，违反会导致 PR 被拒：

1. **不引入注解处理器**：禁止 KSP / Kapt / Room / Hilt / Dagger / Koin。
   持久化用 `DataStore Preferences` + `kotlinx.serialization`；DI 用纯 Kotlin 手写容器。
2. **不擅自升级版本矩阵**（Gradle 9.7.1 / AGP 9.3.2 / Kotlin 2.3.0 / Compose BOM 2026.02.00 / litertlm 0.11.0）。
   确需变更请在 PR 中说明理由并附 CI 验证结果。
3. **每个模块都必须能被 `:app:assembleDebug` 编译通过**。宁可写法朴素，不要「可能更好但没把握」。
4. **不要提交模型文件**：`*.litertlm` / `*.task` / `*.gguf` / `*.bin` 已在 `.gitignore` 中忽略。
5. **不要提交密钥**：keystore、`local.properties`、`signing.properties` 均在 `.gitignore` 中。

## 提交规范

采用 [Conventional Commits](https://www.conventionalcommits.org/)：

```
<type>(<scope>): <subject>

<body>
<footer>
```

`type` 取值：

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `refactor` | 重构（不改变行为） |
| `perf` | 性能优化 |
| `docs` | 文档 |
| `ci` | CI / 构建脚本 |
| `chore` | 依赖、杂项 |
| `test` | 测试 |

示例：

```
feat(chat): 支持思考模式通道输出

读取 message.channels["thought"] 并渲染为折叠区块。

Closes #42
```

规则：
- subject 用中文或英文均可，但同一条提交内保持一致；不超过 72 字符；结尾不加句号。
- 一个 PR 做一件事，不要混入无关的格式调整。

## PR 流程

1. Fork 或从 `main` 切分支：`feat/xxx`、`fix/xxx`、`ci/xxx`。
2. 提交前确认 `./gradlew :app:assembleDebug` 通过（或等 CI 结果）。
3. 填好 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md)。
4. **Build 工作流必须全绿**才会被合并。

## 报告问题

请使用 [Issue 模板](.github/ISSUE_TEMPLATE)：Bug 走 `bug_report.yml`，新想法走 `feature_request.yml`。
提交前先搜索是否已存在。

## 行为准则

请保持友善、就事论事。技术分歧请给出复现步骤或代码依据，不要人身攻击。

## 许可证

贡献即表示你同意你的代码以本项目采用的 [Apache License 2.0](LICENSE) 发布。
