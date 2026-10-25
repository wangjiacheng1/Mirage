package com.mirage.android

import android.app.Application
import android.content.pm.ApplicationInfo

/**
 * 应用入口。只负责建立全局唯一的组件容器。
 */
class MirageApp : Application() {

    lateinit var container: MirageContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 用应用信息里的标志位判断是否调试构建。它在任何构建配置下都存在，
        // 不像生成的构建配置类那样可以被关掉
        val debugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        container = MirageContainer(context = this, debugBuild = debugBuild)
    }
}
