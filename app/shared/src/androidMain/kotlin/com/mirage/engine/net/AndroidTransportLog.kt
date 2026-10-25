package com.mirage.engine.net

import android.util.Log

/**
 * 把传输层的诊断输出接到系统日志上。
 *
 * 一次只写一行，配合格式化那边的分段逻辑 —— 系统日志对单条消息有长度上限，
 * 超长内容会被悄悄截断，而被截掉的往往正是要看的那部分。
 *
 * 用信息级而不是调试级，是因为调试级在很多设备和构建配置下会被直接过滤掉，
 * 排查时还得先去改过滤条件，很容易误以为是日志没打出来。
 *
 * @param tag 日志标签。保持短而固定，便于在日志里按标签过滤。
 */
fun androidTransportLog(tag: String = DEFAULT_LOG_TAG): TransportLog =
    TransportLog { line -> Log.i(tag, line) }

/** 默认标签。 */
const val DEFAULT_LOG_TAG: String = "MirageNet"
