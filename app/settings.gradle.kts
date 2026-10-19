// ============================================================================
// Mirage · Gradle 设置
// ============================================================================
//
// 工程只有两个模块，这是刻意的最小结构：
//
//   :shared      纯 Kotlin Multiplatform 模块 —— 引擎在这里。
//                状态守卫、角色卡解析、Prompt 组装、存储，全部放这。
//                它不依赖任何 Android API，所以能在 JVM 上秒级跑单测，
//                将来要出 iOS 版时也只需要加一个 target、不用重写逻辑。
//
//   :androidApp  只放 UI（Compose）和 Android 平台实现。薄薄一层。
//
// 🔴 底线：任何业务规则都不许写进 :androidApp。
//    一旦规则漏到 UI 层，就必须开模拟器才能测，迭代速度会直接塌掉。
// ============================================================================

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 禁止各模块自己声明仓库，统一在这里管，避免依赖来源失控
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Mirage"

include(":shared")
include(":androidApp")
