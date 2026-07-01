package com.companyname.appname

import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.companyname.appname.TelegramReporter
import com.companyname.appname.ErrorLog
import android.app.ProgressDialog
import android.graphics.SurfaceTexture
import android.media.MediaScannerConnection
import android.view.Surface
import android.view.TextureView

class AudioEditorActivity : AppCompatActivity() {

    companion object {
        private const val MIN_SELECTION_GAP_MS = 50
    }

    enum class MediaKind { AUDIO, VIDEO }

    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var seekBarStart: SeekBar
    private lateinit var seekBarEnd: SeekBar
    private lateinit var tvStartTime: TextView
    private lateinit var tvEndTime: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var etStartStepCustom: EditText
    private lateinit var etEndStepCustom: EditText
    private var audioUri: Uri? = null
    private var currentAudioPath: String? = null
    private var initialMethodIndex: Int = 0
    private var currentMethodIndex: Int = 0
    private var duration: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var isPreviewing = false
    private var seekForwardMs: Int = 5000
    private var seekRewindMs: Int = 5000
    private var playbackWasCompleted = false
    private var isDestroyedState = false
    private var reviewOffsetMs: Int = 2_000
    private var editedSessionFile: File? = null
    // قائمة الأجزاء المحذوفة افتراضياً (لن تُحذف فعلياً إلا عند الحفظ)
    private val deletedSegments = mutableListOf<Pair<Int, Int>>()
    private var isVideoFile: Boolean = false
    private var isManualSeeking = false
    private var playbackSpeed: Float = 1.0f
    private data class UndoEntry(val filePath: String, val selectionStartMs: Int)
    private val undoHistory = mutableListOf<UndoEntry>()
    private val undoHistoryMaxSize = 10
    private var seekStartStepMs: Int = 100
    private var seekEndStepMs: Int = 100
    private var syncStartBarOnFirstTouch = false
    private var isSyncingStartBar = false
    private val scrubHandler = Handler(Looper.getMainLooper())
    private var pendingScrubStart: Runnable? = null
    private var pendingScrubEnd: Runnable? = null
    private var preciseState: Int = 0
    private var preciseStartMs: Long = -1L
    private var preciseEndMs: Long = -1L
    private var mediaKind: MediaKind = MediaKind.AUDIO
    private val isVideo: Boolean get() = mediaKind == MediaKind.VIDEO
    private lateinit var methodSpinner: Spinner
    private lateinit var layoutSliders: View
    private lateinit var layoutManual: View
    private lateinit var layoutPrecise: View
    private lateinit var btnPreciseToggle: Button
    private var videoSurface: TextureView? = null
    private var videoSurfaceReady = false
    private var videoSurfaceObj: Surface? = null

    private fun buildSeekBarDescription(seekBar: SeekBar, forStartBar: Boolean): String {
        val step = if (forStartBar) seekStartStepMs else seekEndStepMs
        val label = if (forStartBar) "البداية" else "النهاية"
        return "$label ${formatTime(seekBar.progress.toLong())}. مقدار الحركة ${formatStepValue(step)}"
    }

    private fun moveSeekBarByConfiguredStep(forStartBar: Boolean, direction: Int) {
        val targetBar = if (forStartBar) seekBarStart else seekBarEnd
        val step = if (forStartBar) seekStartStepMs else seekEndStepMs
        val rawProgress = targetBar.progress + direction * step
        setSeekBarProgressFromAccessibility(forStartBar, rawProgress)
    }

    private fun setSeekBarProgressFromAccessibility(forStartBar: Boolean, rawProgress: Int) {
        val targetBar = if (forStartBar) seekBarStart else seekBarEnd
        val step = if (forStartBar) seekStartStepMs else seekEndStepMs
        val steppedProgress = if (step > 1) {
            (((rawProgress + step / 2) / step) * step)
        } else {
            rawProgress
        }
        targetBar.progress = if (forStartBar) {
            steppedProgress.coerceIn(0, (seekBarEnd.progress - MIN_SELECTION_GAP_MS).coerceAtLeast(0))
        } else {
            steppedProgress.coerceIn((seekBarStart.progress + MIN_SELECTION_GAP_MS).coerceAtMost(duration), duration)
        }
        targetBar.contentDescription = buildSeekBarDescription(targetBar, forStartBar)
        updateTimeLabels()

        if (::mediaPlayer.isInitialized) {
            val previewPosition = if (forStartBar) {
                seekBarStart.progress
            } else {
                (seekBarEnd.progress - reviewOffsetMs).coerceAtLeast(seekBarStart.progress)
            }
            seekAndPlay(previewPosition)
        }
    }

    private fun showSeekStepDialog(forStartBar: Boolean) {
        val currentStep = if (forStartBar) seekStartStepMs else seekEndStepMs
        val barLabel = if (forStartBar) "شريط البداية" else "شريط النهاية"

        val editText = EditText(this).apply {
            hint = "${getString(R.string.seek_step_hint)} ${currentStep}ms"
            setText("${currentStep}ms")
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("${getString(R.string.seek_step_dialog_title)} — $barLabel")
            .setView(editText)
            .setPositiveButton("تطبيق") { _, _ ->
                val parsed = parseStepInput(editText.text.toString(), duration)
                if (parsed == null || parsed <= 0) {
                    Toast.makeText(this, R.string.seek_step_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    if (forStartBar) seekStartStepMs = parsed else seekEndStepMs = parsed
                    applySeekStepIncrements()
                    if (forStartBar && ::etStartStepCustom.isInitialized) {
                        etStartStepCustom.setText(formatStepValue(parsed))
                    } else if (!forStartBar && ::etEndStepCustom.isInitialized) {
                        etEndStepCustom.setText(formatStepValue(parsed))
                    }
                    Toast.makeText(
                        this,
                        "${getString(R.string.seek_step_updated)} ${formatStepValue(parsed)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun parseStepInput(s: String, totalDurationMs: Int): Int? {
        val t = s.trim().lowercase().replace(" ", "")
        if (t.isEmpty()) return null
        return try {
            val rawValueMs = when {
                t.endsWith("%") -> {
                    val percentage = t.removeSuffix("%").toDouble()
                    if (percentage <= 0.0 || totalDurationMs <= 0) return null
                    (totalDurationMs * (percentage / 100.0))
                }
                t.endsWith("ms") -> t.removeSuffix("ms").toDouble()
                t.endsWith("s")  -> t.removeSuffix("s").toDouble() * 1000.0
                t.endsWith("m")  -> t.removeSuffix("m").toDouble() * 60_000.0
                else             -> t.toDouble()
            }
            val rounded = rawValueMs.toInt()
            if (rounded <= 0) null else rounded
        } catch (_: Exception) {
            null
        }
    }

    private fun formatStepValue(stepMs: Int): String {
        return when {
            stepMs % 1000 == 0 -> "${stepMs / 1000}s"
            else -> "${stepMs}ms"
        }
    }

    private fun applyManualSelection(showToast: Boolean = true): Boolean {
        val etStart = findViewById<EditText>(R.id.etManualStart)
        val etEnd = findViewById<EditText>(R.id.etManualEnd)
        val startMs = parseManualTime(etStart.text.toString())
        val endMs = parseManualTime(etEnd.text.toString())

        if (startMs == null || endMs == null) {
            Toast.makeText(this, R.string.manual_invalid, Toast.LENGTH_LONG).show()
            return false
        }
        if (endMs <= startMs) {
            Toast.makeText(this, "النهاية يجب أن تكون بعد البداية", Toast.LENGTH_SHORT).show()
            return false
        }

        val safeStart = startMs.coerceIn(0L, duration.toLong())
        val safeEnd = endMs.coerceIn(0L, duration.toLong())
        if (safeEnd <= safeStart) {
            Toast.makeText(this, "القيم خارج مدة الملف أو غير صالحة", Toast.LENGTH_SHORT).show()
            return false
        }
        seekBarStart.progress = safeStart.toInt()
        seekBarEnd.progress = safeEnd.toInt()
        updateTimeLabels()
        if (showToast) {
            Toast.makeText(
                this,
                "تم تطبيق التحديد: ${formatTime(safeStart)} → ${formatTime(safeEnd)}",
                Toast.LENGTH_SHORT
            ).show()
        }
        return true
    }

    private fun parseManualTime(input: String): Long? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val parts = raw.split('.')
        if (parts.any { it.isEmpty() }) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        if (nums.any { it < 0 }) return null

        return when (nums.size) {
            1 -> nums[0] * 1000L
            2 -> nums[0] * 60_000L + nums[1] * 1000L
            3 -> nums[0] * 3_600_000L + nums[1] * 60_000L + nums[2] * 1000L
            4 -> nums[0] * 3_600_000L + nums[1] * 60_000L + nums[2] * 1000L + nums[3].toLong()
            else -> null
        }
    }

    private fun playPreciseSelection() {
        if (preciseStartMs < 0 || preciseEndMs <= preciseStartMs) {
            Toast.makeText(this, R.string.precise_no_selection, Toast.LENGTH_SHORT).show()
            return
        }
        if (!::mediaPlayer.isInitialized) return
        seekBarStart.progress = preciseStartMs.toInt()
        seekBarEnd.progress = preciseEndMs.toInt()
        updateTimeLabels()

        val startPos = (preciseStartMs).toInt().coerceIn(0, duration)

        // التوجيه الدقيق (SEEK_CLOSEST) لضمان التشغيل من النقطة المحددة بالضبط
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mediaPlayer.seekTo(startPos.toLong(), MediaPlayer.SEEK_CLOSEST)
        } else {
            mediaPlayer.seekTo(startPos)
        }

        mediaPlayer.start()
        btnPlayPause.setImageResource(R.drawable.ic_pause)
        isPreviewing = false
        playbackWasCompleted = false
        checkPosition()
    }

    private fun seekAndPlay(milli: Int) {
        if (!::mediaPlayer.isInitialized) return
        val pos = milli.coerceAtLeast(0)

        // التوجيه الدقيق (SEEK_CLOSEST) لضمان الانتقال للنقطة المحددة بالضبط
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mediaPlayer.seekTo(pos.toLong(), MediaPlayer.SEEK_CLOSEST)
        } else {
            mediaPlayer.seekTo(pos)
        }

        if (isVideo) {
            pausePlayback()
        } else if (!mediaPlayer.isPlaying) {
            mediaPlayer.start()
            btnPlayPause.setImageResource(R.drawable.ic_pause)
            isPreviewing = false
            playbackWasCompleted = false
            checkPosition()
        }
    }

    private fun resumeSegmentPlayback() {
        if (seekBarEnd.progress <= seekBarStart.progress) {
            Toast.makeText(this, "النهاية يجب أن تكون بعد البداية", Toast.LENGTH_SHORT).show()
            return
        }
        val currentPosition = mediaPlayer.currentPosition
        val shouldRestartFromSelectionStart =
            playbackWasCompleted ||
            currentPosition < seekBarStart.progress ||
            currentPosition >= seekBarEnd.progress

        if (shouldRestartFromSelectionStart) {
            mediaPlayer.seekTo(seekBarStart.progress)
        }

        mediaPlayer.start()
        btnPlayPause.setImageResource(R.drawable.ic_pause)
        isPreviewing = false
        playbackWasCompleted = false
        checkPosition()
    }

    private fun pausePlayback() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
        }
        playbackWasCompleted = false
        btnPlayPause.setImageResource(R.drawable.ic_play)
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * حساب الموضع الذي يجب التخطي إليه بناءً على الأجزاء المحذوفة
     * @return موضع جديد إذا كان في جزء محذوف، أو نفس الموضع إذا لم يكن
     */
    private fun getSkipPosition(currentPos: Int): Int {
        var pos = currentPos
        var maxIter = 20
        while (maxIter-- > 0) {
            var skipped = false
            for ((start, end) in deletedSegments) {
                if (pos in start until end) {
                    pos = end
                    skipped = true
                    break
                }
            }
            if (!skipped) break
        }
        return pos
    }

    private fun undoLastOperation() {
        if (deletedSegments.isNotEmpty()) {
            // التراجع عن آخر عملية حذف افتراضي
            val lastDeleted = deletedSegments.removeLast()
            // استعادة الـ seek bars للنطاق الكامل
            seekBarStart.progress = 0
            seekBarEnd.progress = duration
            updateTimeLabels()
            Toast.makeText(this, "تم التراجع عن حذف الجزء (${lastDeleted.first}-${lastDeleted.second})", Toast.LENGTH_LONG).show()
        } else if (undoHistory.isNotEmpty()) {
            // الرجوع للخلف في سجل التعديلات
            val entry = undoHistory.removeLast()
            if (!File(entry.filePath).exists()) {
                Toast.makeText(this, "ملف النسخة الاحتياطية غير موجود", Toast.LENGTH_SHORT).show()
                return
            }
            editedSessionFile?.takeIf { it.absolutePath != entry.filePath }?.let {
                if (it.absolutePath.startsWith(cacheDir.absolutePath)) it.delete()
            }
            currentAudioPath = entry.filePath
            editedSessionFile = File(entry.filePath)
            initMediaPlayer()
            resetSelectionToFullRange((entry.selectionStartMs - 2000).coerceAtLeast(0))
            Toast.makeText(this, "تم التراجع عن آخر تعديل", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "لا توجد عمليات سابقة للتراجع عنها", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPosition() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isDestroyedState || !::mediaPlayer.isInitialized) return
                if (mediaPlayer.isPlaying) {
                    val curPos = mediaPlayer.currentPosition
                    
                    // تخطي الأجزاء المحذوفة افتراضياً أثناء المعاينة (فقط إذا لم يكن seek يدوي)
                    if (!isManualSeeking) {
                        val skipTo = getSkipPosition(curPos)
                        if (skipTo > curPos && skipTo < seekBarEnd.progress) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            mediaPlayer.seekTo(skipTo.toLong(), MediaPlayer.SEEK_CLOSEST)
                        } else {
                            mediaPlayer.seekTo(skipTo)
                        }
                        handler.postDelayed(this, 100)
                        return
                    }
                    
                    }
                    
                    if (curPos >= seekBarEnd.progress) {
                        mediaPlayer.pause()
                        playbackWasCompleted = true
                        btnPlayPause.setImageResource(R.drawable.ic_play)
                        handler.removeCallbacksAndMessages(null)
                    } else {
                        handler.postDelayed(this, 100)
                    }
                }
            }
        }, 100)
    }

    private fun initMediaPlayer() {
        try {
            if (::mediaPlayer.isInitialized) {
                mediaPlayer.release()
            }

            if (currentAudioPath == null && audioUri != null) {
                currentAudioPath = getFilePathFromUri(audioUri!!)
            }
            val audioPath = currentAudioPath
            if (audioPath.isNullOrBlank()) {
                throw IllegalStateException("تعذر قراءة الملف الصوتي")
            }

            mediaPlayer = MediaPlayer()
            if (isVideo && videoSurfaceObj != null) {
                mediaPlayer.setSurface(videoSurfaceObj)
            }
            mediaPlayer.setDataSource(audioPath)
            mediaPlayer.prepare()

            duration = mediaPlayer.duration

            seekBarStart.max = duration
            seekBarEnd.max = duration
            applySeekStepIncrements()
            seekBarStart.progress = 0
            seekBarEnd.progress = duration

            preciseState = 0
            preciseStartMs = -1L
            preciseEndMs = -1L
            if (::btnPreciseToggle.isInitialized) {
                btnPreciseToggle.text = getString(R.string.precise_start)
            }

            updateTimeLabels()
        } catch (e: Exception) {
            ErrorLog.logException(this, "AUDIO_EDITOR", e)
            TelegramReporter.sendException("AUDIO_EDITOR", e)
            Toast.makeText(this, "خطأ في تشغيل الملف: ${e.message}، جرّاح الرجوع للنسخة السابقة", Toast.LENGTH_LONG).show()
            editedSessionFile?.let { prev ->
                if (prev.exists() && prev.length() > 0) {
                    currentAudioPath = prev.absolutePath
                    try { initMediaPlayer() } catch (_: Exception) { finish() }
                } else { finish() }
            } ?: finish()
        }
    }

    private fun getFilePathFromUri(uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.path ?: throw IllegalStateException("تعذر قراءة مسار الملف الصوتي")
        }
        val tempFile = File(cacheDir, "temp_input_audio")
        contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: throw IllegalStateException("تعذر فتح الملف الصوتي")
        return tempFile.absolutePath
    }

    private fun updateTimeLabels() {
        tvStartTime.text = formatTime(seekBarStart.progress.toLong())
        tvEndTime.text = formatTime(seekBarEnd.progress.toLong())
    }

    private fun formatTime(millis: Long): String {
        return String.format("%02d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(millis),
            TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
        )
    }

    private fun showSaveDialog(saveWholeFile: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_save_audio, null)
        val etName = dialogView.findViewById<EditText>(R.id.etFileName)
        val spFormat = dialogView.findViewById<Spinner>(R.id.spFormat)
        val tvFormatLabel = dialogView.findViewById<TextView>(R.id.tvFormatLabel)

        if (isVideo) {
            AlertDialog.Builder(this)
                .setTitle("حفظ الفيديو")
                .setView(dialogView)
                .setPositiveButton("حفظ") { _, _ ->
                    val fileName = etName.text.toString()
                    saveAudio(fileName, "mp4", saveWholeFile)
                }
                .setNegativeButton("إلغاء", null)
                .show()
            spFormat.visibility = View.GONE
            tvFormatLabel.visibility = View.GONE
        } else {
            val nativeExt = currentAudioPath?.let { pickOutputExtension(it) } ?: "m4a"
            val formats = linkedSetOf(nativeExt, "mp3", "m4a", "ogg", "wav").toTypedArray()
            spFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formats)

            AlertDialog.Builder(this)
                .setTitle(R.string.save_dialog_title)
                .setView(dialogView)
                .setPositiveButton("حفظ") { _, _ ->
                    val fileName = etName.text.toString()
                    val format = spFormat.selectedItem.toString()
                    saveAudio(fileName, format, saveWholeFile)
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    private fun deleteSelectedSegment() {
        val srcPath = currentAudioPath ?: return
        val selectionRange = getCurrentSelectionRangeMs() ?: return
        
        val startSec = selectionRange.first
        val endSec = selectionRange.second
        
        // إضافة الجزء المحدد إلى قائمة الأجزاء المحذوفة افتراضياً
        // (لم يتم حذفها فعلياً - فقط تمييزها للحذف أثناء الحفظ النهائي)
        deletedSegments.add(startSec to endSec)
        
        // حفظ في سجل التراجع لعملية التراجع
        undoHistory.add(UndoEntry(srcPath, startSec))
        if (undoHistory.size > undoHistoryMaxSize) {
            val removed = undoHistory.removeFirst()
        }
        
        Toast.makeText(this, "تم تمييز الجزء للحذف (سيتم تطبيقه عند الحفظ)", Toast.LENGTH_LONG).show()
        // الرجوع إلى بداية الجزء المحذوف ناقص reviewOffsetMs (2 ثانية افتراضياً)
        resetSelectionToFullRange((startSec - reviewOffsetMs).coerceAtLeast(0))
    }

    /**
     * بناء قائمة النطاقات المراد الاحتفاظ بها بعد استبعاد الأجزاء المحذوفة
     */
    private fun buildKeepRanges(
        fullRange: Pair<Int, Int>,
        deleted: List<Pair<Int, Int>>
    ): List<Pair<Long, Long>> {
        val (start, end) = fullRange
        // دمج الأجزاء المحذوفة المتداخلة
        val sortedDeleted = deleted
            .filter { it.first < it.second }
            .sortedBy { it.first }
        
        val merged = mutableListOf<Pair<Int, Int>>()
        for (seg in sortedDeleted) {
            val safeSeg = seg.first.coerceIn(start, end) to seg.second.coerceIn(start, end)
            if (safeSeg.first >= safeSeg.second) continue
            if (merged.isEmpty()) {
                merged.add(safeSeg)
            } else {
                val last = merged.last()
                if (safeSeg.first <= last.second) {
                    merged[merged.size - 1] = last.first to maxOf(last.second, safeSeg.second)
                } else {
                    merged.add(safeSeg)
                }
            }
        }
        
        // بناء النطاقات المحتفظ بها
        val keep = mutableListOf<Pair<Long, Long>>()
        var currentStart = start.toLong()
        for (seg in merged) {
            if (seg.first > currentStart) {
                keep.add(currentStart * 1000L to seg.first.toLong() * 1000L)
            }
            currentStart = seg.second.toLong()
        }
        if (currentStart < end) {
            keep.add(currentStart * 1000L to end.toLong() * 1000L)
        }
        
        return keep
    }

    private fun saveAudio(name: String, format: String, saveWholeFile: Boolean) {
        val srcPath = currentAudioPath ?: return
        if (format.lowercase() == "ogg" && !isSourceOgg(srcPath)) {
            Toast.makeText(this, R.string.ogg_only_when_source_ogg, Toast.LENGTH_LONG).show()
            return
        }
        if (format.lowercase() == "wav" && !isSourceWav(srcPath)) {
            Toast.makeText(this, R.string.wav_only_when_source_wav, Toast.LENGTH_LONG).show()
            return
        }
        if (isSourceWav(srcPath) && format.lowercase() != "wav") {
            Toast.makeText(this, R.string.wav_source_saves_as_wav, Toast.LENGTH_LONG).show()
            return
        }
        val progressDialog = ProgressDialog(this)
        progressDialog.setMessage("جاري حفظ الملف...")
        progressDialog.show()

        val finalName = StoragePaths.sanitizeFileName(name)
        val displayName = "$finalName.$format"

        val selectionRange = if (saveWholeFile) {
            0 to duration
        } else {
            getCurrentSelectionRangeMs() ?: return
        }
        val startUs = selectionRange.first * 1000L
        val endUs = selectionRange.second * 1000L
        val reviewPointMs = selectionRange.first

        // بناء قائمة النطاقات المراد الاحتفاظ بها (باستثناء الأجزاء المحذوفة افتراضياً)
        val keepRanges = buildKeepRanges(selectionRange, deletedSegments)
        
        if (keepRanges.isEmpty()) {
            runOnUiThread { Toast.makeText(this, "لا توجد مقاطع للاحتفاظ بها بعد الحذف", Toast.LENGTH_LONG).show() }
            return
        }

        executor.execute {
            val tempPath = File(cacheDir, "trim_temp_${System.currentTimeMillis()}.$format").absolutePath
            val result: Result<String> = try {
                trimAudio(srcPath, tempPath, keepRanges)
                val destination = saveToInternalToolFolder(tempPath, displayName)
                File(tempPath).delete()
                Result.success(destination)
            } catch (e: Exception) {
                ErrorLog.logException(this, "AUDIO_EDITOR", e)
                TelegramReporter.sendException("AUDIO_EDITOR", e)
                File(tempPath).delete()
                Result.failure(e)
            }
            runOnUiThread {
                progressDialog.dismiss()
            }
            runOnUiThread {
                progressDialog.dismiss()
                result.fold(
                    onSuccess = {
                        // مسح قائمة الأجزاء المحذوفة افتراضياً بعد الحفظ الفعلي
                        deletedSegments.clear()
                        if (saveWholeFile) {
                            seekForReview(reviewPointMs)
                        } else {
                            resetSelectionToFullRange(reviewPointMs)
                        }
                        Toast.makeText(this, "تم الحفظ في: $it", Toast.LENGTH_LONG).show()
                    },
                    onFailure = {
                        Toast.makeText(
                            this,
                            "فشل الحفظ: ${it.message ?: it.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_editor)

        audioUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("AUDIO_URI", Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("AUDIO_URI") as? Uri
        }
        currentAudioPath = intent.getStringExtra("AUDIO_PATH")
        initialMethodIndex = intent.getIntExtra("CUT_METHOD_INDEX", 0).coerceIn(0, 2)
        mediaKind = try { MediaKind.valueOf(intent.getStringExtra("MEDIA_KIND") ?: "AUDIO") } catch (_: Exception) { MediaKind.AUDIO }

        if (audioUri == null && currentAudioPath.isNullOrBlank()) {
            Toast.makeText(this, "فشل تحميل ملف الصوت", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupUI()
        switchMethod(initialMethodIndex)
        initMediaPlayer()
    }

    private fun setupUI() {
        methodSpinner = findViewById(R.id.methodSpinner)
        val methods = arrayOf(
            getString(R.string.method_1),
            getString(R.string.method_2),
            getString(R.string.method_3)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, methods)
        methodSpinner.adapter = adapter
        methodSpinner.visibility = View.GONE
        findViewById<TextView>(R.id.tvMethodTitle).visibility = View.GONE

        seekBarStart = findViewById(R.id.seekBarStart)
        seekBarEnd = findViewById(R.id.seekBarEnd)
        etStartStepCustom = findViewById(R.id.etStartStepCustom)
        etEndStepCustom = findViewById(R.id.etEndStepCustom)
        tvStartTime = findViewById(R.id.tvStartTime)
        tvEndTime = findViewById(R.id.tvEndTime)
        btnPlayPause = findViewById(R.id.btnPlayPause)

        layoutSliders = findViewById(R.id.layoutSliders)
        layoutManual = findViewById(R.id.layoutManual)
        layoutPrecise = findViewById(R.id.layoutPrecise)
        btnPreciseToggle = findViewById(R.id.btnPreciseToggle)

        videoSurface = findViewById(R.id.videoSurface)
        videoSurface?.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                videoSurfaceReady = true
                videoSurfaceObj = Surface(surfaceTexture)
                if (::mediaPlayer.isInitialized && isVideo) {
                    mediaPlayer.setSurface(videoSurfaceObj)
                }
            }
            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                videoSurfaceReady = false
                return true
            }
            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
        }

        // تبديل واجهة الطريقة حسب اختيار القائمة المنسدلة
        methodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                switchMethod(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        methodSpinner.setSelection(initialMethodIndex, false)

        // أزرار التشغيل المشتركة
        btnPlayPause.setOnClickListener {
            if (::mediaPlayer.isInitialized) {
                if (mediaPlayer.isPlaying) {
                    pausePlayback()
                } else {
                    resumeSegmentPlayback()
                }
            }
        }

        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            if (::mediaPlayer.isInitialized) {
                isManualSeeking = true
                val current = mediaPlayer.currentPosition
                val targetPos = (current + seekForwardMs).coerceAtMost(duration)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mediaPlayer.seekTo(targetPos.toLong(), MediaPlayer.SEEK_CLOSEST)
                } else {
                    mediaPlayer.seekTo(targetPos)
                }
                handler.postDelayed({ isManualSeeking = false }, 500)
            }
        }

        findViewById<ImageButton>(R.id.btnRewind).setOnClickListener {
            if (::mediaPlayer.isInitialized) {
                isManualSeeking = true
                val current = mediaPlayer.currentPosition
                val targetPos = (current - seekRewindMs).coerceAtLeast(0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mediaPlayer.seekTo(targetPos.toLong(), MediaPlayer.SEEK_CLOSEST)
                } else {
                    mediaPlayer.seekTo(targetPos)
                }
                handler.postDelayed({ isManualSeeking = false }, 500)
            }
        }
        findViewById<ImageButton>(R.id.btnForward).setOnLongClickListener {
            showJumpStepDialog(forForward = true)
            true
        }
        findViewById<ImageButton>(R.id.btnRewind).setOnLongClickListener {
            showJumpStepDialog(forForward = false)
            true
        }

        findViewById<Button>(R.id.btnSaveSegment).setOnClickListener { showSaveDialog(saveWholeFile = false) }
        findViewById<Button>(R.id.btnDeleteSegment).setOnClickListener { deleteSelectedSegment() }
        // ضغطة مطولة على زر التشغيل: تغيير سرعة التشغيل
        findViewById<ImageButton>(R.id.btnPlayPause).setOnLongClickListener {
            showPlaybackSpeedDialog()
            true
        }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { undoLastOperation() }
        findViewById<Button>(R.id.btnFinalSave).setOnClickListener { showSaveDialog(saveWholeFile = true) }

        // ===== الطريقة 1: أشرطة التمرير =====
        seekBarStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (isSyncingStartBar) return
                if (progress >= seekBarEnd.progress) {
                    seekBarStart.progress = (seekBarEnd.progress - MIN_SELECTION_GAP_MS).coerceAtLeast(0)
                }
                updateTimeLabels()
                if (fromUser && ::mediaPlayer.isInitialized) {
                    pendingScrubStart?.let { scrubHandler.removeCallbacks(it) }
                    pendingScrubStart = Runnable {
                        seekAndPlay(seekBarStart.progress)
                    }.also { scrubHandler.postDelayed(it, 300) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                pendingScrubStart?.let { scrubHandler.removeCallbacks(it) }
                snapToStep(seekBarStart, seekStartStepMs)
                if (::mediaPlayer.isInitialized) seekAndPlay(seekBarStart.progress)
            }
        })

        seekBarEnd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress <= seekBarStart.progress) {
                    seekBarEnd.progress = (seekBarStart.progress + MIN_SELECTION_GAP_MS).coerceAtMost(duration)
                }
                updateTimeLabels()
                if (fromUser && ::mediaPlayer.isInitialized) {
                    pendingScrubEnd?.let { scrubHandler.removeCallbacks(it) }
                    pendingScrubEnd = Runnable {
                        val pos = (progress - reviewOffsetMs).coerceAtLeast(seekBarStart.progress)
                        seekAndPlay(pos)
                    }.also { scrubHandler.postDelayed(it, 300) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                pendingScrubEnd?.let { scrubHandler.removeCallbacks(it) }
                snapToStep(seekBarEnd, seekEndStepMs)
                if (::mediaPlayer.isInitialized) {
                    val pos = (seekBarEnd.progress - reviewOffsetMs).coerceAtLeast(seekBarStart.progress)
                    seekAndPlay(pos)
                }
            }
        })

        seekBarStart.setOnLongClickListener {
            showSeekStepDialog(true)
            true
        }
        seekBarEnd.setOnLongClickListener {
            showSeekStepDialog(false)
            true
        }

        // ===== الطريقة 3: التحديد الدقيق =====
        btnPreciseToggle.setOnClickListener {
            if (!::mediaPlayer.isInitialized) {
                Toast.makeText(this, "الملف الصوتي غير جاهز", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            preciseState = (preciseState + 1) % 3
            when (preciseState) {
                0 -> {
                    preciseStartMs = -1L
                    preciseEndMs = -1L
                    btnPreciseToggle.setText(R.string.precise_start)
                    Toast.makeText(this, "تم إعادة ضبط التحديد", Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    preciseStartMs = mediaPlayer.currentPosition.toLong()
                    btnPreciseToggle.setText(R.string.precise_end)
                    Toast.makeText(this, "تم تسجيل البداية عند ${formatTime(preciseStartMs)}", Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    preciseEndMs = mediaPlayer.currentPosition.toLong()
                    if (preciseEndMs <= preciseStartMs) {
                        Toast.makeText(this, "النهاية يجب أن تكون بعد البداية", Toast.LENGTH_SHORT).show()
                        preciseState = 1
                        return@setOnClickListener
                    }
                    btnPreciseToggle.setText(R.string.precise_end_confirmed)
                    seekBarStart.progress = preciseStartMs.toInt()
                    seekBarEnd.progress = preciseEndMs.toInt()
                    updateTimeLabels()
                    Toast.makeText(this, "تم التحديد: ${formatTime(preciseStartMs)} ← ${formatTime(preciseEndMs)}", Toast.LENGTH_LONG).show()
                }
            }
        }
        btnPreciseToggle.setOnLongClickListener {
            showReviewOffsetDialog()
            true
        }
        findViewById<Button>(R.id.btnPreciseListen).setOnClickListener {
            playPreciseSelection()
        }
    }

    /**
     * إظهار/إخفاء واجهة الطريقة المختارة من القائمة.
     */
    private fun switchMethod(position: Int) {
        currentMethodIndex = position.coerceIn(0, 2)
        layoutSliders.visibility = if (position == 0) View.VISIBLE else View.GONE
        layoutPrecise.visibility = if (position == 1) View.VISIBLE else View.GONE
        layoutManual.visibility = if (position == 2) View.VISIBLE else View.GONE
    }

    private fun bindStepInput(editText: EditText, forStartBar: Boolean) {
        var isFormatting = false
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormatting) return
                val parsed = parseStepInput(s.toString(), duration)
                if (parsed != null && parsed > 0) {
                    updateSeekStep(parsed, forStartBar)
                }
            }
        })
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                isFormatting = true
                applyStepFromInput(editText, forStartBar)
                isFormatting = false
            }
        }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                isFormatting = true
                applyStepFromInput(editText, forStartBar)
                isFormatting = false
                true
            } else {
                false
            }
        }
    }

    private fun applyStepFromInput(editText: EditText, forStartBar: Boolean) {
        val parsed = parseStepInput(editText.text.toString(), duration)
        if (parsed == null || parsed <= 0) {
            Toast.makeText(this, R.string.seek_step_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        updateSeekStep(parsed, forStartBar)
        editText.setText(formatStepValue(parsed))
        editText.setSelection(editText.text.length)
    }

    private fun updateSeekStep(stepMs: Int, forStartBar: Boolean) {
        if (forStartBar) seekStartStepMs = stepMs else seekEndStepMs = stepMs
        applySeekStepIncrements()
        val targetBar = if (forStartBar) seekBarStart else seekBarEnd
        targetBar.contentDescription = buildSeekBarDescription(targetBar, forStartBar)
    }

    private fun showReviewOffsetDialog() {
        val editText = EditText(this).apply {
            hint = "قيمة الرجوع للخلف (ms)"
            setText("${reviewOffsetMs}ms")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("تعديل وقت الرجوع للخلف بعد القص")
            .setMessage("عدد الميلي ثانية للرجوع للخلف بعد قص المقطع وحذف جزء منه")
            .setView(editText)
            .setPositiveButton("تطبيق") { _, _ ->
                val parsed = parseStepInput(editText.text.toString(), duration)
                if (parsed == null || parsed <= 0) {
                    Toast.makeText(this, "قيمة غير صالحة", Toast.LENGTH_SHORT).show()
                } else {
                    reviewOffsetMs = parsed
                    Toast.makeText(this, "تم تحديث وقت الرجوع للخلف إلى ${formatStepValue(reviewOffsetMs)}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showPlaybackSpeedDialog() {
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)
        val labels = speeds.map { "${it}x" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("سرعة التشغيل")
            .setSingleChoiceItems(labels, speeds.indexOf(playbackSpeed).coerceAtLeast(0)) { dialog, which ->
                val newSpeed = speeds[which]
                playbackSpeed = newSpeed
                if (::mediaPlayer.isInitialized) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(newSpeed)
                    }
                }
                Toast.makeText(this, "سرعة التشغيل: ${newSpeed}x", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showJumpStepDialog(forForward: Boolean) {
        val currentVal = if (forForward) seekForwardMs else seekRewindMs
        val label = if (forForward) "التقديم" else "التأخير"
        val editText = EditText(this).apply {
            hint = "قيمة زر $label (ms)"
            setText("${currentVal}ms")
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("تخصيص زر $label")
            .setView(editText)
            .setPositiveButton("تطبيق") { _, _ ->
                val parsed = parseStepInput(editText.text.toString(), duration)
                if (parsed == null || parsed <= 0) {
                    Toast.makeText(this, R.string.seek_step_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    if (forForward) seekForwardMs = parsed else seekRewindMs = parsed
                    Toast.makeText(
                        this,
                        "تم تعيين ${label}: ${formatStepValue(parsed)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    /**
     * يحاذي قيمة الشريط لأقرب مضاعف من مقدار الحركة المحدد (snap-to-step).
     */
    private fun snapToStep(seekBar: SeekBar, stepMs: Int) {
        if (stepMs <= 1) return
        val current = seekBar.progress
        val rounded = (((current + stepMs / 2) / stepMs) * stepMs).coerceIn(0, seekBar.max)
        if (rounded != current) seekBar.progress = rounded
    }

    private fun applySeekStepIncrements() {
        snapToStep(seekBarStart, seekStartStepMs)
        snapToStep(seekBarEnd, seekEndStepMs)
    }

    private fun seekForReview(editPointMs: Int) {
        if (!::mediaPlayer.isInitialized) return
        val reviewPosition = (editPointMs - reviewOffsetMs).coerceAtLeast(0)
        mediaPlayer.seekTo(reviewPosition)
        playbackWasCompleted = false
        btnPlayPause.setImageResource(R.drawable.ic_play)
    }

    private fun getCurrentSelectionRangeMs(): Pair<Int, Int>? {
        if (!::seekBarStart.isInitialized || !::seekBarEnd.isInitialized) return null

        when (currentMethodIndex) {
            1 -> {
                if (preciseStartMs < 0 || preciseEndMs <= preciseStartMs) {
                    Toast.makeText(this, R.string.precise_no_selection, Toast.LENGTH_SHORT).show()
                    return null
                }
                seekBarStart.progress = preciseStartMs.toInt().coerceIn(0, duration)
                seekBarEnd.progress = preciseEndMs.toInt().coerceIn(0, duration)
                updateTimeLabels()
            }
            2 -> {
                if (!applyManualSelection(showToast = false)) return null
            }
        }

        val startMs = seekBarStart.progress
        val endMs = seekBarEnd.progress
        if (endMs <= startMs) {
            Toast.makeText(this, "النهاية يجب أن تكون بعد البداية", Toast.LENGTH_SHORT).show()
            return null
        }
        return startMs to endMs
    }

    private fun resetSelectionToFullRange(reviewPointMs: Int? = null) {
        if (!::seekBarStart.isInitialized || !::seekBarEnd.isInitialized) return
        handler.removeCallbacksAndMessages(null)
        scrubHandler.removeCallbacksAndMessages(null)
        seekBarStart.progress = 0
        seekBarEnd.progress = duration
        preciseState = 0
        preciseStartMs = -1L
        preciseEndMs = -1L
        if (::btnPreciseToggle.isInitialized) {
            btnPreciseToggle.text = getString(R.string.precise_start)
        }
        playbackWasCompleted = false
        btnPlayPause.setImageResource(R.drawable.ic_play)
        updateTimeLabels()
        if (::mediaPlayer.isInitialized) {
            try {
                if (mediaPlayer.isPlaying) mediaPlayer.pause()
                val reviewPosition = reviewPointMs
                    ?.let { (it - reviewOffsetMs).coerceAtLeast(0) }
                    ?: 0
                handler.post {
                    try {
                        mediaPlayer.setOnSeekCompleteListener(null)
                        mediaPlayer.seekTo(reviewPosition)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun saveToInternalToolFolder(srcPath: String, displayName: String): String {
        val outDir = if (isVideo) {
            val dir = File(StoragePaths.videoRoot(this), cutToolFolder())
            dir.mkdirs(); dir
        } else {
            buildToolOutputDir(cutToolFolder())
        }
        val outFile = File(outDir, displayName)
        // استخدام FileChannel لنقل البيانات بشكل مباشر وسريع (Zero-Copy)
        FileInputStream(srcPath).channel.use { srcChannel ->
            FileOutputStream(outFile).channel.use { destChannel ->
                destChannel.transferFrom(srcChannel, 0, srcChannel.size())
            }
        }
        MediaScannerConnection.scanFile(this, arrayOf(outFile.absolutePath), null, null)
        return outFile.absolutePath
    }

    private fun safeSetExtractorDataSource(extractor: MediaExtractor, path: String) {
        val uri = Uri.parse(path)
        if (uri.scheme?.lowercase() == "content") {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            }
        } else {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            }
        }
    }

    /**
     * يختار امتداد الملف الناتج بناءً على تنسيق المصدر.
     * - MP3 → mp3 (المعيار الأكثر توافقاً)
     * - WAV/PCM → wav
     * - AAC/M4A → m4a (عبر MediaMuxer في حاوية MP4)
     * - غير ذلك → m4a كمحاولة افتراضية
     */
    private fun pickOutputExtension(srcPath: String): String {
        if (isVideo) return "mp4"
        val extractor = MediaExtractor()
        return try {
            safeSetExtractorDataSource(extractor, srcPath)
            var mime: String? = null
            for (i in 0 until extractor.trackCount) {
                val m = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (m != null && m.startsWith("audio/")) {
                    mime = m
                    break
                }
            }
            when (mime) {
                "audio/mpeg" -> "mp3"
                "audio/ogg" -> "ogg"
                "application/ogg" -> "ogg"
                "audio/vorbis" -> "ogg"
                "audio/wav" -> "wav"
                "audio/x-wav" -> "wav"
                "audio/raw" -> "wav"
                else -> "m4a"
            }
        } catch (_: Exception) {
            "m4a"
        } finally {
            extractor.release()
        }
    }

    private fun isSourceOgg(srcPath: String): Boolean {
        val mime = getSourceAudioMime(srcPath) ?: return false
        return mime.contains("ogg", ignoreCase = true) || mime.contains("vorbis", ignoreCase = true)
    }

    private fun isSourceWav(srcPath: String): Boolean {
        val mime = getSourceAudioMime(srcPath)
        if (mime != null && (mime.contains("wav", ignoreCase = true) || mime == "audio/raw")) {
            return true
        }
        return srcPath.endsWith(".wav", ignoreCase = true) || srcPath.endsWith(".wave", ignoreCase = true)
    }

    private fun getSourceAudioMime(srcPath: String): String? {
        val extractor = MediaExtractor()
        return try {
            safeSetExtractorDataSource(extractor, srcPath)
            for (i in 0 until extractor.trackCount) {
                val m = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (m != null && m.startsWith("audio/")) {
                    return m
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }


    private fun hasVideoTrack(extractor: MediaExtractor): Boolean {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return true
        }
        return false
    }


    /**
     * قص باستخدام MediaMuxer مع جميع المسارات (للصوت والفيديو معاً)
     */
    private fun useMuxerForAllTracks(
        extractor: MediaExtractor,
        outPath: String,
        ranges: List<Pair<Long, Long>>,
        srcPath: String? = null
    ) {
        val srcFile = srcPath ?: outPath

        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            // 1. مستخرج الفيديو (من Keyframe)
            val videoExt = MediaExtractor()
            safeSetExtractorDataSource(videoExt, srcFile)

            // 2. مستخرج الصوت (من أقرب نقطة)
            val audioExt = MediaExtractor()
            safeSetExtractorDataSource(audioExt, srcFile)

            val outType = when {
                srcFile.lowercase().endsWith(".webm") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                srcFile.lowercase().endsWith(".3gp") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
                else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            muxer = MediaMuxer(outPath, outType)

            val videoTrackMap = mutableMapOf<Int, Int>()
            val audioTrackMap = mutableMapOf<Int, Int>()
            var absoluteMaxInputSize = 1024 * 1024

            // تحديد المسارات وإعدادها
            for (i in 0 until videoExt.trackCount) {
                val tf = videoExt.getTrackFormat(i)
                val mime = tf.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/") && !mime.startsWith("video/")) continue
                try {
                    val muxIdx = muxer.addTrack(tf)
                    if (mime.startsWith("video/")) {
                        videoTrackMap[i] = muxIdx
                        videoExt.selectTrack(i)
                    } else {
                        audioTrackMap[i] = muxIdx
                        audioExt.selectTrack(i)
                    }
                    if (tf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        absoluteMaxInputSize = maxOf(absoluteMaxInputSize, tf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                } catch (_: Exception) {}
            }

            if (videoTrackMap.isEmpty() && audioTrackMap.isEmpty()) {
                throw IllegalStateException("لا يوجد مسار صوتي أو فيديو مدعوم")
            }

            muxer.start()
            muxerStarted = true

            val videoBuf = ByteBuffer.allocateDirect(absoluteMaxInputSize)
            val audioBuf = ByteBuffer.allocateDirect(absoluteMaxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var timeOffset = 0L

            // 3. معالجة كل نطاق بمستخرجين منفصلين
            for (ri in ranges.indices) {
                val (rs, re) = ranges[ri]
                if (rs >= re) continue

                var maxPtsThisRange = 0L

                // حساب SyncAnchor للفيديو + Audio offset
                var videoSyncAnchor = rs
                if (videoTrackMap.isNotEmpty()) {
                    val firstVideoExt = videoTrackMap.keys.first()
                    videoExt.selectTrack(firstVideoExt)
                    videoExt.seekTo(rs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    videoSyncAnchor = maxOf(0L, videoExt.sampleTime.coerceAtMost(rs))
                }

                // ----- مسار الفيديو: من Keyframe -----
                if (videoTrackMap.isNotEmpty()) {
                    for ((extIdx, muxIdx) in videoTrackMap) {
                        videoExt.selectTrack(extIdx)
                        videoExt.seekTo(videoSyncAnchor, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        var firstWrittenPts: Long? = null

                        while (true) {
                            val st = videoExt.sampleTime
                            if (st < 0 || st > re) break
                            if (st < rs) { videoExt.advance(); continue }
                            if (firstWrittenPts == null) firstWrittenPts = st

                            videoBuf.clear()
                            val size = videoExt.readSampleData(videoBuf, 0)
                            if (size < 0) break

                            bufferInfo.offset = 0
                            bufferInfo.size = size
                            bufferInfo.flags = videoExt.sampleFlags
                            val newPts = (st - firstWrittenPts!!) + timeOffset
                            bufferInfo.presentationTimeUs = newPts
                            if (newPts > maxPtsThisRange) maxPtsThisRange = newPts

                            videoBuf.position(0)
                            muxer.writeSampleData(muxIdx, videoBuf, bufferInfo)
                            if (!videoExt.advance()) break
                        }
                    }
                }

                // ----- مسار الصوت: من أقرب نقطة مباشرة (بدون تخطي) -----
                if (audioTrackMap.isNotEmpty()) {
                    for ((extIdx, muxIdx) in audioTrackMap) {
                        audioExt.selectTrack(extIdx)
                        audioExt.seekTo(rs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        var foundStart = false

                        while (true) {
                            val st = audioExt.sampleTime
                            if (st < 0 || st > re) break
                            if (!foundStart) {
                                if (st < rs) { audioExt.advance(); continue }
                                foundStart = true
                            }

                            audioBuf.clear()
                            val size = audioExt.readSampleData(audioBuf, 0)
                            if (size < 0) break

                            bufferInfo.offset = 0
                            bufferInfo.size = size
                            bufferInfo.flags = audioExt.sampleFlags
                            val relativePts = maxOf(0L, st - rs)
                            val newPts = relativePts + timeOffset
                            bufferInfo.presentationTimeUs = newPts
                            if (newPts > maxPtsThisRange) maxPtsThisRange = newPts

                            audioBuf.position(0)
                            muxer.writeSampleData(muxIdx, audioBuf, bufferInfo)
                            if (!audioExt.advance()) break
                        }
                    }
                }

                if (maxPtsThisRange <= 0) {
                    throw IllegalStateException("لم يتم كتابة أي عينات صالحة للنطاق $ri")
                }
                timeOffset += maxPtsThisRange + 1000L
            }

            videoExt.release()
            audioExt.release()

        } finally {
            if (muxerStarted) muxer?.stop()
            try { muxer?.release() } catch (_: Exception) {}
        }
    }


    private fun hasVideoTrack(srcPath: String): Boolean {
        val extractor = MediaExtractor()
        return try {
            safeSetExtractorDataSource(extractor, srcPath)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) return true
            }
            false
        } catch (_: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    

    private data class WavFormatInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val bytesPerSample: Int,
        val blockAlign: Int,
        val byteRate: Int
    )

    private fun getWavFormatInfo(format: MediaFormat): WavFormatInfo {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        val bitsPerSample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }
        val bytesPerSample = bitsPerSample / 8
        val blockAlign = channels * bytesPerSample
        val byteRate = sampleRate * blockAlign
        return WavFormatInfo(sampleRate, channels, bitsPerSample, bytesPerSample, blockAlign, byteRate)
    }

    private fun copyWavWithCorrectHeader(
        extractor: MediaExtractor,
        format: MediaFormat,
        outPath: String,
        ranges: List<Pair<Long, Long>>,
        srcPath: String
    ) {
        extractor.release()
        
        val wavInfo = getWavFormatInfo(format)
        
        try {
            val raf = java.io.RandomAccessFile(outPath, "rw")
            var actualDataSize = 0L
            val readBuf = ByteBuffer.allocate(64 * 1024)
            val tempByteArray = ByteArray(64 * 1024)

            try {
                // هيدر مؤقت
                raf.write(ByteArray(44))

                val newExt = MediaExtractor()
                safeSetExtractorDataSource(newExt, srcPath)
                var pcmTrack = -1
                for (i in 0 until newExt.trackCount) {
                    val mime = newExt.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) { pcmTrack = i; break }
                }
                if (pcmTrack < 0) throw IllegalStateException("لا يوجد مسار صوتي")
                newExt.selectTrack(pcmTrack)

                for ((rangeStart, rangeEnd) in ranges) {
                    if (rangeStart >= rangeEnd) continue
                    newExt.seekTo(rangeStart, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    var foundStart = false
                    while (true) {
                        val sampleTime = newExt.sampleTime
                        if (sampleTime < 0) break
                        if (!foundStart) {
                            if (sampleTime < rangeStart) { if (!newExt.advance()) break; continue }
                            foundStart = true
                        }
                        if (sampleTime >= rangeEnd) break
                        readBuf.clear()
                        val sampleSize = newExt.readSampleData(readBuf, 0)
                        if (sampleSize < 0) break
                        readBuf.position(0)
                        readBuf.get(tempByteArray, 0, sampleSize)
                        raf.write(tempByteArray, 0, sampleSize)
                        actualDataSize += sampleSize
                        if (!newExt.advance()) break
                    }
                }
                newExt.release()

                if (actualDataSize == 0L) throw IllegalStateException("لم يتم نسخ أي بيانات WAV")

                // هيدر RIFF/WAV صحيح
                val h = ByteArray(44)
                var p = 0
                h[p++] = 'R'.code.toByte(); h[p++] = 'I'.code.toByte(); h[p++] = 'F'.code.toByte(); h[p++] = 'F'.code.toByte()
                writeInt32S(h, p, (actualDataSize + 36).toInt()); p += 4
                h[p++] = 'W'.code.toByte(); h[p++] = 'A'.code.toByte(); h[p++] = 'V'.code.toByte(); h[p++] = 'E'.code.toByte()
                h[p++] = 'f'.code.toByte(); h[p++] = 'm'.code.toByte(); h[p++] = 't'.code.toByte(); h[p++] = ' '.code.toByte()
                writeInt32S(h, p, 16); p += 4
                writeInt16S(h, p, 1); p += 2
                writeInt16S(h, p, wavInfo.channels); p += 2
                writeInt32S(h, p, wavInfo.sampleRate); p += 4
                writeInt32S(h, p, wavInfo.byteRate); p += 4
                writeInt16S(h, p, wavInfo.blockAlign); p += 2
                writeInt16S(h, p, wavInfo.bitsPerSample); p += 2
                h[p++] = 'd'.code.toByte(); h[p++] = 'a'.code.toByte(); h[p++] = 't'.code.toByte(); h[p++] = 'a'.code.toByte()
                writeInt32S(h, p, actualDataSize.toInt()); p += 4

                raf.seek(0)
                raf.write(h)
            } finally {
                raf.close()
            }
        } catch (e: Exception) {
            throw IllegalStateException("فشل نسخ WAV: ${e.message}", e)
        }
    }

    private fun writeInt16S(buffer: ByteArray, pos: Int, value: Int) {
        buffer[pos] = (value and 0xFF).toByte()
        buffer[pos + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeInt32S(buffer: ByteArray, pos: Int, value: Int) {
        buffer[pos] = (value and 0xFF).toByte()
        buffer[pos + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[pos + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[pos + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun trimAudio(srcPath: String, outPath: String, ranges: List<Pair<Long, Long>>) {
        File(outPath).parentFile?.mkdirs()
        File(outPath).delete()


val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var outTrackIndex = -1
        
        try {
            safeSetExtractorDataSource(extractor, srcPath)
            
            // التحقق من وجود فيديو - إذا كان موجوداً نستخدم الطريقة المتعددة المسارات
            if (hasVideoTrack(extractor) || isVideo) {
                useMuxerForAllTracks(extractor, outPath, ranges, srcPath)
                return
            }
            
            // اختيار المسار الصوتي الأول
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) {
                throw IllegalStateException("لم يتم العثور على مسار صوتي")
            }
            extractor.selectTrack(audioTrackIndex)

            val mimeCheck = format.getString(MediaFormat.KEY_MIME) ?: ""
            
            // معالجة خاصة لملفات WAV/PCM: نحتاج إلى هيدر RIFF صحيح
            if (mimeCheck.contains("raw") || mimeCheck.contains("wav") || srcPath.lowercase().endsWith(".wav")) {
                copyWavWithCorrectHeader(extractor, format, outPath, ranges, srcPath)
                return
            }

            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4"
            val outFormat = when {
                mime.contains("mp4") || mime.contains("aac") || mime.contains("m4a") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                mime.contains("webm") || mime.contains("opus") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                mime.contains("3gp") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
                else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            muxer = MediaMuxer(outPath, outFormat)
            outTrackIndex = try {
                muxer.addTrack(format)
            } catch (e: Exception) {
                throw IllegalStateException("ترميز الصوت غير مدعوم", e)
            }
            muxer.start()
            muxerStarted = true

            val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else {
                1024 * 1024
            }
            val buffer = ByteBuffer.allocate(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var timeOffset = 0L
            var lastWrittenPts = 0L

            for ((rangeStart, rangeEnd) in ranges) {
                if (rangeStart >= rangeEnd) continue

                extractor.seekTo(rangeStart, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    val t = extractor.sampleTime
                    if (t < 0 || t >= rangeStart) break
                    if (!extractor.advance()) break
                }

                val firstSampleTime = extractor.sampleTime
                if (firstSampleTime < 0) continue

                while (true) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0 || sampleTime >= rangeEnd) break

                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.flags = extractor.sampleFlags
                    bufferInfo.presentationTimeUs = (sampleTime - firstSampleTime) + timeOffset

                    muxer.writeSampleData(outTrackIndex, buffer, bufferInfo)
                    lastWrittenPts = bufferInfo.presentationTimeUs

                    if (!extractor.advance()) break
                }
                timeOffset = lastWrittenPts + 1000
            }
        } finally {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
    private fun cutToolFolder(): String {
        val prefix = if (isVideo) "video" else "audio"
        return when (currentMethodIndex) {
            0 -> "${prefix}_ribbon"
            1 -> "${prefix}_selection"
            else -> "${prefix}_value"
        }
    }

    private fun buildToolOutputDir(toolFolder: String): File {
        val dir = StoragePaths.toolDir(this, toolFolder)
        dir.mkdirs()
        return dir
    }

    private fun buildToolOutputPath(toolFolder: String, baseName: String, extension: String): String {
        return File(buildToolOutputDir(toolFolder), "$baseName.$extension").absolutePath
    }

    override fun onDestroy() {
        isDestroyedState = true
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
        if (::mediaPlayer.isInitialized) {
            try {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.stop()
                }
            } catch (_: Exception) {
            }
            try {
                mediaPlayer.release()
            } catch (_: Exception) {
            }
        }
        executor.shutdownNow()
        editedSessionFile?.delete()
    }
}
