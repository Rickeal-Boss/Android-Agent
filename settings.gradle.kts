// =============================================================================
// LiquidAgent — 模块声明
//
// 约定（见 docs/01-architecture.md §1.1）：
//   * 全部使用扁平命名，project path 与目录名字面一致（:core-model <-> core-model/）
//   * 仓库里没有 jitpack：任何 JitPack 依赖都会在 FAIL_ON_PROJECT_REPOS 下直接失败
// =============================================================================

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 杜绝模块级 repositories {} —— 所有仓库只能在这里声明
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LiquidAgent"

// ---- 9 个模块，按依赖拓扑排序（下层在前） ----
include(":core-model")
include(":core-engine")
include(":core-agent")
include(":core-data")
include(":core-design")
include(":feature-chat")
include(":feature-models")
include(":feature-settings")
include(":app")
