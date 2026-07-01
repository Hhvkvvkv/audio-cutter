package com.companyname.appname

import android.content.Context
import android.util.Log
import com.github.pao11.libffmpeg.FFmpeg
import com.github.pao11.libffmpeg.LoadBinaryResponseHandler
import com.github.pao11.libffmpeg.ExecuteBinaryResponseHandler
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FFmpegManager - قص الفيديو والصوت بسرعة واحترافية
 * Pipeline: Fast Copy → Fast Re-encode → (Activity تتولى Legacy Fallback)
 * 
 * ملاحظة: التهيئة غير blocking - تبدأ تحميل FFmpeg في الخلفية
 * وتستخدمه فقط لما يكون جاهزاً، وإلا يتم اللجوء للطريقة القديمة تلقائياً
 */
object FFmpegManager {

    private const val TAG = "FFMPEG_MANAGER"
    @Volatile
    private var initialized = false
    private var initAttempted = false
    private var lastError: String? = null

    /**
     * بدء تهيئة FFmpeg في الخلفية - غير blocking
     * تلتقط أي أخطاء (بما فيها ProGuard stripping) بهدوء
     */
    fun initialize(context: Context) {
        if (initAttempted) return
        initAttempted = true
        
        try {
            Log.d(TAG, "Attempting FFmpeg initialization...")
            val ffmpeg = FFmpeg.getInstance(context)
            ffmpeg.loadBinary(object : LoadBinaryResponseHandler() {
                override fun onSuccess() {
                    initialized = true
                    Log.d(TAG, "FFmpeg loaded successfully ✅")
                }
                override fun onFailure() {
                    Log.e(TAG, "FFmpeg load failed ❌ - will use legacy method")
                    lastError = "فشل تحميل مكتبة FFmpeg على هذا الجهاز"
                }
            })
        } catch (e: NoClassDefFoundError) {
            // ProGuard stripped FFmpeg classes in release build
            Log.w(TAG, "FFmpeg classes not found (ProGuard?): ${e.message}")
            lastError = "مكتبة FFmpeg غير موجودة (ProGuard)"
        } catch (e: Exception) {
            Log.e(TAG, "FFmpeg init exception: ${e.message}")
            lastError = e.message
        }
    }

    fun isInitialized(): Boolean = initialized
    fun getLastError(): String? = lastError
    fun reset() { lastError = null }

    /**
     * قص الفيديو/الصوت بخطوتين:
     * 1. Fast Copy (نسخ مباشر بدون ترميز - أجزاء من الثانية)
     * 2. Fast Re-encode (إعادة ترميز سريع - مضمون 100%)
     * 
     * @return Result.success إذا نجح FFmpeg، Result.failure للـ Activity تستخدم Legacy
     */
    fun trimMedia(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long,
        context: Context? = null
    ): Result<String> {
        if (!initialized) {
            return Result.failure(Exception("FFmpeg ليس جاهزاً بعد"))
        }

        val ctx = context ?: AudioCutterApp.instance
            ?: return Result.failure(Exception("سياق التطبيق غير متوفر"))

        // استخدام Locale.US لمنع مشكلة الأرقام العربية (٣٫٥ بدل 3.5)
        val startSec = String.format(Locale.US, "%.3f", startMs / 1000.0)
        val durationSec = String.format(Locale.US, "%.3f", (endMs - startMs) / 1000.0)

        // حذف ملف المخرجات القديم لمنع تجمد FFmpeg بانتظار تأكيد الاستبدال
        File(outputPath).delete()

        // ===== الخطوة 1: Fast Copy (نسخ مباشر = أسرع طريقة) =====
        Log.d(TAG, "===== محاولة 1: Fast Copy =====")
        val cmdCopy = arrayOf(
            "-y",
            "-ss", startSec,
            "-accurate_seek",
            "-i", inputPath,
            "-t", durationSec,
            "-c", "copy",
            "-avoid_negative_ts", "make_zero",
            "-map", "0:v",
            "-map", "0:a?",
            outputPath
        )

        val copyResult = runCommand(ctx, cmdCopy, outputPath)
        if (copyResult.isSuccess) {
            Log.d(TAG, "====== Fast Copy نجح! ======")
            return copyResult
        }

        Log.w(TAG, "Fast Copy فشل: ${copyResult.exceptionOrNull()?.message}")

        // ===== الخطوة 2: Fast Re-encode (بريست ultrafast = سريع جداً) =====
        Log.d(TAG, "===== محاولة 2: Fast Re-encode =====")
        File(outputPath).delete()

        val videoExts = setOf("mp4", "3gp", "webm", "mkv", "mov", "avi", "flv", "wmv", "m4v")
        val ext = inputPath.substringAfterLast('.', "").lowercase(Locale.US)
        val isVideo = ext in videoExts

        val cmdReencode: Array<String> = if (isVideo) {
            arrayOf(
                "-y",
                "-ss", startSec,
                "-i", inputPath,
                "-t", durationSec,
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", "23",
                "-c:a", "aac",
                "-b:a", "128k",
                "-avoid_negative_ts", "make_zero",
                "-map", "0:v",
                "-map", "0:a?",
                outputPath
            )
        } else {
            arrayOf(
                "-y",
                "-ss", startSec,
                "-i", inputPath,
                "-t", durationSec,
                "-c:a", "aac",
                "-b:a", "192k",
                "-map", "0:a?",
                outputPath
            )
        }

        val reencodeResult = runCommand(ctx, cmdReencode, outputPath)
        if (reencodeResult.isSuccess) {
            Log.d(TAG, "====== Fast Re-encode نجح! ======")
            return reencodeResult
        }

        Log.w(TAG, "Fast Re-encode فشل: ${reencodeResult.exceptionOrNull()?.message}")
        lastError = reencodeResult.exceptionOrNull()?.message
        return reencodeResult
    }

    /**
     * تنفيذ أمر FFmpeg وانتظار النتيجة (هذا فقط أثناء القص، مش أثناء التهيئة)
     */
    private fun runCommand(context: Context, cmd: Array<String>, outputPath: String): Result<String> {
        val latch = CountDownLatch(1)
        var isSuccess = false
        var errorMsg = "خطأ غير معروف"

        Log.d(TAG, "FFmpeg: ${cmd.joinToString(" ")}")

        try {
            FFmpeg.getInstance(context).execute(cmd, object : ExecuteBinaryResponseHandler() {
                override fun onSuccess(message: String?) {
                    isSuccess = true
                    latch.countDown()
                }
                override fun onFailure(message: String?) {
                    errorMsg = message ?: "فشل تنفيذ الأمر"
                    latch.countDown()
                }
                override fun onProgress(message: String?) {
                    Log.v(TAG, "Progress: $message")
                }
            })

            val finished = latch.await(5, TimeUnit.MINUTES)
            if (!finished) {
                return Result.failure(Exception("انتهت مهلة الانتظار (5 دقائق)"))
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }

        return if (isSuccess && File(outputPath).exists() && File(outputPath).length() > 0) {
            Result.success(outputPath)
        } else {
            Result.failure(Exception(errorMsg))
        }
    }
}
