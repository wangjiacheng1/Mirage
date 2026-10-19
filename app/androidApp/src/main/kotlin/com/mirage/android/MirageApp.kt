package com.mirage.android

import android.app.Application

/**
 * Application 入口。
 *
 * 现在是空的。将来这里做两件事：
 *  1. 启动 Koin 依赖注入容器；
 *  2. 初始化崩溃日志的本地落盘（**只落本地，不上报** —— 我们没有服务端，
 *     也承诺不采集用户数据，见 docs/07）。
 */
class MirageApp : Application()
