package com.tiredfone.soundcloudrpc

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {
    private const val MAX_LINES = 600
    private val lines = ArrayDeque<String>(MAX_LINES + 1)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(level: String, tag: String, message: String) {
        val entry = "${fmt.format(Date())} $level/$tag: $message"
        lines.addLast(entry)
        if (lines.size > MAX_LINES) lines.removeFirst()
        when (level) {
            "D" -> Log.d(tag, message)
            "I" -> Log.i(tag, message)
            "W" -> Log.w(tag, message)
            "E" -> Log.e(tag, message)
        }
    }

    fun d(tag: String, msg: String) = log("D", tag, msg)
    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String) = log("E", tag, msg)

    @Synchronized
    fun getAll(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
