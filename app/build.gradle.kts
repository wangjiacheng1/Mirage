// ============================================================================
// 根 build 脚本
// ============================================================================
// 只声明插件（apply false），具体由各子模块按需启用。
// 这样做的好处：插件版本在这里统一对齐，子模块里只写 alias(...) 不写版本号。
// ============================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    // 存储用。ksp 负责跑 Room 的代码生成，room 插件负责把表结构导出成文件
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
