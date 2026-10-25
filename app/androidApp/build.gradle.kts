// ============================================================================
// :androidApp —— 只有 UI 和平台实现
// ============================================================================
// 🔴 不要往这里写业务规则。任何「状态怎么算」「Prompt 怎么拼」的逻辑
//    都属于 :shared。见 settings.gradle.kts 的说明。
// ============================================================================

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.mirage.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mirage.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    buildTypes {
        debug {
            // 便于和 release 并存安装，调试时不用来回卸载
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 签名配置留空：签名文件绝不进仓库（见 .gitignore 的 *.jks / *.keystore）
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":shared"))

    // 界面层直接使用了协程与状态流类型（状态持有者里的作用域、StateFlow），
    // 而引擎模块是以不传递的方式依赖它们的，所以这里必须自己声明一份
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)
}
