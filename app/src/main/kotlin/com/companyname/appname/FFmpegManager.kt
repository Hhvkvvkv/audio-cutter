package com.companyname.appname

import android.content.Context
import android.util.Log

/**
 * FFmpegManager - معطل، التطبيق يستخدم Android Media APIs فقط
 */
object FFmpegManager {

    private const val TAG = "FFmpegManager"

    fun initialize(context: Context) {
        Log.d(TAG, "FFmpeg غير متوفر - التطبيق يستخدم Android Media APIs")
    }

    fun isInitialized(): Boolean = false

    fun trimMedia(inputPath: String, outputPath: String, startMs: Long, endMs: Long, context: Context? = null): Result<String> =
        Result.failure(Exception("FFmpeg غير متوفر"))

    fun getLastError(): String? = "FFmpeg غير مدعوم على هذا الجهاز"
    fun reset() {}
}
