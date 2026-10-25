// ============================================================================
// :shared —— Mirage 引擎
// ============================================================================
//
// 这是整个项目**最重要**的模块，护城河都在这里：
//   engine/state     G1~G8 状态守卫、结算回执        ← M1 当前进度
//   engine/card      SillyTavern 角色卡解析 / 导出
//   engine/prompt    上下文组装、分卷压缩
//   engine/llm       多厂商 API 适配、SSE 流式解析
//   data             Room 存储（消息树 + 状态事件流）
//
// 刻意约束：**不引入任何 Android 依赖**。
// 好处是 `./gradlew :shared:jvmTest` 几秒就能跑完全部引擎单测，不用开模拟器。
// 这个速度直接决定我们迭代 Prompt 和守卫规则的效率。
// ============================================================================

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // ------------------------------------------------------------------------
    // 工具链：把「用哪个 JDK **编译**」和「用哪个 JDK **运行 Gradle**」解耦
    // ------------------------------------------------------------------------
    // 🔴 补的是一个真实缺陷，不是洁癖。
    //
    // 下面的 androidTarget 显式写了 jvmTarget = 17，android{} 也写了 17，
    // 唯独 jvm() 这个 target 没有任何约束 —— 它会跟着「当前跑 Gradle 的那个
    // JDK」走。于是同一份代码：
    //     命令行（JAVA_HOME 未设，PATH 里是 JDK 17）→ 编译成字节码 17
    //     Android Studio（Gradle JDK 若是内置 JBR 21）→ 编译成字节码 21
    // 产物不一致 → 构建缓存反复失效，更糟的是会出现「命令行测试全绿、
    // IDE 里却报错」这种最难查的问题。2026-10-19 我们就卡在这类分歧上。
    //
    // jvmToolchain(17) 之后，不管谁用哪个 JDK 启动 Gradle，编译用的永远是 17。
    // 本机 ~/Library/Java/JavaVirtualMachines/ms-17.0.15 会被 Gradle 自动发现。
    // 换机器时若没装 JDK 17，Gradle 会给出明确的「找不到 17 工具链」提示，
    // 而不是默默换一个版本编译 —— 这正是我们要的行为。
    jvmToolchain(17)

    // --- JVM target：单测跑在这上面，是我们的主要开发回路 ---
    jvm()

    // --- Android target：打进 APK 的那份 ---
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
    }

    // --- iOS target：暂不开启 ---
    // 决策见 docs/09 Q11：是否做 iOS 还没定。但 KMP 的价值就在于
    // 「将来想做的时候，这里加三行就行，引擎代码一行不用改」。
    // 现在就开启只会白增编译时间和 CI 复杂度。
    //
    // iosX64(); iosArm64(); iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.ktor.client.logging)
                implementation(libs.koin.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }

        val androidMain by getting {
            dependencies {
                // Android 上 Ktor 用 OkHttp 引擎（HTTP/2、连接池成熟）
                implementation(libs.ktor.client.okhttp)
                // 凭据加密直接基于系统密钥库实现，不引入官方那个仍是预览版、
                // 且已被标记为不再推荐使用的加密存储组件
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }
    }
}

android {
    namespace = "com.mirage.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 26 // Android 8.0。理由见下方注释
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// minSdk 26 的理由：
// 1. API Key 加密要用 Android Keystore 的 AES/GCM，26 以下支持不完整；
// 2. 26 以下的 WebView / 字体 / 通知 API 差异会显著增加兼容代码量；
// 3. 我们的目标用户是「愿意自己配 API Key 的玩家」，设备普遍不会太老。
// 如果后续发现覆盖率有问题，这是个可以重新讨论的数字（记入 docs/09）。
