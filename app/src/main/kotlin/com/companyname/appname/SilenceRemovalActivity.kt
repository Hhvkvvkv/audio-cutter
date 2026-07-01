package com.companyname.appname

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.companyname.appname.TelegramReporter
import com.companyname.appname.ErrorLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.sqrt

class SilenceRemovalActivity : AppCompatActivity() {

    private data class PcmWindow(val startUs: Long, val isSilent: Boolean)
    private data class TimeRange(val startUs: Long, val endUs: Long)

    private var audioUri: Uri? = null
    private var currentLevel: Int = 0
    private var mediaKind: MediaKind = MediaKind.AUDIO
    private val isVideo: Boolean get() = mediaKind == MediaKind.VIDEO

    // ملف النتيجة المؤقت (بعد الضغط على تطبيق)
    private var resultTempFile: File? = null
    private var resultPlayer: MediaPlayer? = null
    private var isResultPlaying = false

    private val executor = Executors.newSingleThreadExecutor()
    private val handler  = Handler(Looper.getMainLooper())

    private lateinit var tvFileName: TextView
    private lateinit var seekBarLevel: SeekBar
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var btnApply: Button
    private lateinit var layoutResult: LinearLayout
    private lateinit var btnPlayPause: Button
    private lateinit var btnUndo: Button
    private lateinit var btnSave: Button

    private val thresholds   = floatArrayOf(0.007f, 0.016f, 0.032f, 0.055f)
    private val minSilenceMs = intArrayOf(600, 450, 300, 180)
    private val paddingMs    = intArrayOf(120, 90, 60, 40)

    private val levelNames = arrayOf("عادي", "متوسط", "مرتفع", "مرتفع جداً")
    private val levelDescs = arrayOf(
        "يزيل فترات الصمت الطويلة فقط (600ms فأكثر)",
        "يزيل فترات الصمت المتوسطة (450ms فأكثر)",
        "يزيل فترات الصمت القصيرة (300ms فأكثر)",
        "يزيل كل فترات الصمت (180ms فأكثر)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_silence_removal)

        audioUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("AUDIO_URI", Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("AUDIO_URI") as? Uri
        }
        mediaKind = try { MediaKind.valueOf(intent.getStringExtra("MEDIA_KIND") ?: "AUDIO") } catch (_: Exception) { MediaKind.AUDIO }

        if (audioUri == null) {
            Toast.makeText(this, "فشل تحميل الملف الصوتي", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        tvFileName    = findViewById(R.id.tvSilenceFileName)
        seekBarLevel  = findViewById(R.id.seekBarLevel)
        progressBar   = findViewById(R.id.silenceProgressBar)
        tvStatus      = findViewById(R.id.tvSilenceStatus)
        btnApply      = findViewById(R.id.btnSilenceApply)
        layoutResult  = findViewById(R.id.layoutResultButtons)
        btnPlayPause  = findViewById(R.id.btnSilencePlayPause)
        btnUndo       = findViewById(R.id.btnSilenceUndo)
        btnSave       = findViewById(R.id.btnSilenceSave)

        tvFileName.text = audioUri?.lastPathSegment ?: if (isVideo) "ملف فيديو" else "ملف صوتي"

        // شريط التمرير للمستوى
        seekBarLevel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentLevel = progress
                seekBarLevel.contentDescription =
                    "مستوى إزالة الصمت: ${levelNames[progress]}. ${levelDescs[progress]}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        seekBarLevel.progress = 0
        seekBarLevel.contentDescription = "مستوى إزالة الصمت: ${levelNames[0]}. ${levelDescs[0]}"

        btnApply.setOnClickListener { startApply() }

        // أزرار النتيجة
        btnPlayPause.setOnClickListener { toggleResultPlayback() }
        btnUndo.setOnClickListener { undoOperation() }
        btnSave.setOnClickListener { saveResult() }
    }

    // ===== تطبيق إزالة الصمت على الملف كاملاً =====

    private fun startApply() {
        val srcUri = audioUri ?: return
        setUiBusy(true, "جارٍ تحليل الملف وإزالة الصمت...")
        executor.execute {
            try {
                if (isVideo && hasVideoTrack()) {
                    val tmp = File(cacheDir, "silence_video_result_${System.currentTimeMillis()}.mp4")
                    removeSilenceFromVideo(srcUri, tmp.absolutePath)
                    if (!tmp.exists() || tmp.length() <= 0L) {
                        throw IllegalStateException("خرج إزالة الصمت من الفيديو فارغ")
                    }
                    handler.post {
                        resultTempFile = tmp
                        setUiBusy(false, "✓ اكتملت إزالة الصمت من الفيديو")
                        showResultButtons()
                    }
                } else {
                    val tmp = File(cacheDir, "silence_result_${System.currentTimeMillis()}.m4a")
                    performSilenceRemoval(srcUri, tmp.absolutePath, Long.MAX_VALUE)
                    if (!tmp.exists() || tmp.length() <= 0L) {
                        throw IllegalStateException("خرج إزالة الصمت فارغ")
                    }
                    handler.post {
                        resultTempFile = tmp
                        setUiBusy(false, "✓ اكتملت عملية إزالة الصمت")
                        showResultButtons()
                    }
                }
            } catch (e: Exception) {
                ErrorLog.logException(this, "SILENCE_REMOVAL", e); TelegramReporter.sendException("SILENCE_REMOVAL", e)
                handler.post { setUiBusy(false, "فشل التطبيق: ${e.message}") }
            }
        }
    }

    private fun hasVideoTrack(): Boolean {
        val uri = audioUri ?: return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
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

    // ===== تشغيل / إيقاف النتيجة =====

    private fun toggleResultPlayback() {
        if (isResultPlaying) {
            pauseResult()
        } else {
            playResult()
        }
    }

    private fun playResult() {
        val file = resultTempFile ?: return
        if (resultPlayer == null) {
            val player = MediaPlayer()
            resultPlayer = player
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener { onResultPlaybackComplete() }
            player.setOnErrorListener { _, _, _ -> onResultPlaybackComplete(); true }
            player.prepare()
        }
        resultPlayer?.start()
        isResultPlaying = true
        btnPlayPause.text = "إيقاف مؤقت"
    }

    private fun pauseResult() {
        try { resultPlayer?.pause() } catch (_: Exception) {}
        isResultPlaying = false
        btnPlayPause.text = "تشغيل النتيجة"
    }

    private fun onResultPlaybackComplete() {
        isResultPlaying = false
        btnPlayPause.text = "تشغيل النتيجة"
        try { resultPlayer?.seekTo(0) } catch (_: Exception) {}
    }

    private fun stopAndReleasePlayer() {
        try { resultPlayer?.stop()    } catch (_: Exception) {}
        try { resultPlayer?.release() } catch (_: Exception) {}
        resultPlayer    = null
        isResultPlaying = false
    }

    // ===== تراجع عن العملية =====

    private fun undoOperation() {
        stopAndReleasePlayer()
        resultTempFile?.delete()
        resultTempFile = null
        hideResultButtons()
        tvStatus.text = ""
        Toast.makeText(this, "تم التراجع عن عملية إزالة الصمت", Toast.LENGTH_SHORT).show()
    }

    // ===== حفظ النتيجة =====

    private fun saveResult() {
        val tmp = resultTempFile ?: return
        showSaveDialog(tmp)
    }

    private fun showSaveDialog(tmp: File) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_save_audio, null)
        val etName = dialogView.findViewById<EditText>(R.id.etFileName)
        val spFormat = dialogView.findViewById<Spinner>(R.id.spFormat)

        if (isVideo) {
            val formats = arrayOf("mp4")
            spFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formats)
            spFormat.isEnabled = false
        } else {
            val formats = arrayOf("m4a", "mp4")
            spFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formats)
        }
        etName.setText("no_silence_${System.currentTimeMillis()}")

        AlertDialog.Builder(this)
            .setTitle("حفظ النتيجة")
            .setView(dialogView)
            .setPositiveButton("حفظ") { _, _ ->
                val fileName = StoragePaths.sanitizeFileName(etName.text.toString())
                val format = spFormat.selectedItem.toString()
                doSaveResult(tmp, fileName, format)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun doSaveResult(tmp: File, fileName: String, format: String) {
        pauseResult()
        setUiBusy(true, "جارٍ حفظ الملف...")
        executor.execute {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    saveViaMediaStore(tmp, fileName, format)
                } else {
                    saveViaDirectFile(tmp, fileName, format)
                }
                handler.post {
                    setUiBusy(false, "✓ تم الحفظ بنجاح")
                    Toast.makeText(this, "تم الحفظ:\n$fileName.$format", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                ErrorLog.logException(this, "SILENCE_REMOVAL", e); TelegramReporter.sendException("SILENCE_REMOVAL", e)
                handler.post { setUiBusy(false, "فشل الحفظ: ${e.message}") }
            }
        }
    }


    private fun saveViaMediaStore(tmp: File, fileName: String, format: String) {
        val mimeType = if (isVideo) "video/mp4" else "audio/mp4"
        val relativePath = if (isVideo) {
            "${getString(R.string.output_root_folder)}/${getString(R.string.folder_video_silence_removal)}"
        } else {
            "${getString(R.string.output_root_folder)}/silence_removal"
        }
        val uri = StoragePaths.saveToMediaStore(this, tmp, fileName, format, mimeType, relativePath)
        if (uri == null) throw IllegalStateException("فشل حفظ الملف عبر MediaStore")
    }

    private fun saveViaDirectFile(tmp: File, fileName: String, format: String) {
        val outPath = createOutputPath(fileName, format)
        val outFile = File(outPath)
        outFile.parentFile?.mkdirs()
        FileInputStream(tmp).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        if (!outFile.exists() || outFile.length() <= 0L) {
            outFile.delete()
            throw IllegalStateException("لم يتم إنشاء ملف صالح")
        }
    }

    private fun createOutputPath(fileName: String, format: String): String {
        val dir = if (isVideo) {
            val d = File(StoragePaths.videoRoot(this), getString(R.string.folder_video_silence_removal))
            d.mkdirs(); d
        } else {
            StoragePaths.toolDir(this, "silence_removal").also { it.mkdirs() }
        }
        return File(dir, "$fileName.$format").absolutePath
    }

    // ===== إزالة الصمت من الفيديو (بدون إعادة ترميز) =====

    private fun getDurationUs(uri: Uri): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            var maxUs = 0L
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    val d = fmt.getLong(MediaFormat.KEY_DURATION)
                    if (d > maxUs) maxUs = d
                }
            }
            if (maxUs <= 0L) Long.MAX_VALUE else maxUs
        } catch (_: Exception) {
            Long.MAX_VALUE
        } finally {
            extractor.release()
        }
    }

    private fun removeSilenceFromVideo(uri: Uri, outputPath: String) {
        val threshold = thresholds[currentLevel]
        val minSilUs  = minSilenceMs[currentLevel] * 1000L
        val padUs     = paddingMs[currentLevel] * 1000L
        val totalUs = getDurationUs(uri)
        val silentRanges = detectSilentRanges(uri, threshold, minSilUs, padUs, totalUs)
        val keepRanges = invertRanges(silentRanges, totalUs)
        if (keepRanges.isEmpty()) {
            copyFile(uri, outputPath)
            return
        }
        trimMultiTrackVideo(uri, outputPath, keepRanges)
    }

    private fun invertRanges(silent: List<TimeRange>, totalDurationUs: Long): List<Pair<Long, Long>> {
        if (silent.isEmpty()) return listOf(0L to totalDurationUs)
        val result = mutableListOf<Pair<Long, Long>>()
        var cursor = 0L
        for (range in silent) {
            if (range.startUs > cursor) {
                result.add(cursor to range.startUs)
            }
            cursor = range.endUs
        }
        if (cursor < totalDurationUs) {
            result.add(cursor to totalDurationUs)
        }
        return result
    }

    private fun copyFile(uri: Uri, outputPath: String) {
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outputPath).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun trimMultiTrackVideo(uri: Uri, outputPath: String, rangesUs: List<Pair<Long, Long>>) {
        val inputPath = getFilePathFromUri(uri)

        val trackFormats = mutableListOf<MediaFormat>()
        var tempExtractor = MediaExtractor()
        try {
            tempExtractor.setDataSource(inputPath)
            for (i in 0 until tempExtractor.trackCount) {
                trackFormats.add(tempExtractor.getTrackFormat(i))
            }
        } finally {
            tempExtractor.release()
        }

        File(outputPath).parentFile?.mkdirs()
        File(outputPath).delete()

        var muxer: MediaMuxer? = null
        var muxerStarted = false
        val muxerTracks = mutableListOf<Int>()

        try {
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            for (fmt in trackFormats) {
                muxerTracks.add(muxer.addTrack(fmt))
            }
            muxer.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var anySampleWritten = false

            for (t in 0 until trackFormats.size) {
                var trackExtractor = MediaExtractor()
                try {
                    trackExtractor.setDataSource(inputPath)
                    trackExtractor.selectTrack(t)

                    var trackTimeOffset = 0L

                    for ((rangeStart, rangeEnd) in rangesUs) {
                        if (rangeStart >= rangeEnd) continue

                        trackExtractor.seekTo(rangeStart, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                        while (true) {
                            val st = trackExtractor.sampleTime
                            if (st < 0) break
                            if (st >= rangeStart) break
                            if (!trackExtractor.advance()) break
                        }

                        val firstSampleTime = trackExtractor.sampleTime
                        if (firstSampleTime < 0) continue

                        var lastWrittenPts = 0L

                        while (true) {
                            val sampleTime = trackExtractor.sampleTime
                            if (sampleTime < 0) break
                            if (sampleTime >= rangeEnd) break

                            buffer.clear()
                            val sampleSize = trackExtractor.readSampleData(buffer, 0)
                            if (sampleSize < 0) break

                            bufferInfo.offset = 0
                            bufferInfo.size = sampleSize
                            val rawFlags = trackExtractor.sampleFlags
                            bufferInfo.flags = if (rawFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            bufferInfo.presentationTimeUs = (sampleTime - firstSampleTime) + trackTimeOffset

                            muxer.writeSampleData(muxerTracks[t], buffer, bufferInfo)
                            lastWrittenPts = bufferInfo.presentationTimeUs
                            anySampleWritten = true

                            if (!trackExtractor.advance()) break
                        }

                        trackTimeOffset = lastWrittenPts + 1000
                    }
                } finally {
                    trackExtractor.release()
                }
            }

            if (!anySampleWritten) {
                throw IllegalStateException("لم تتم كتابة أي عينات - تحقق من النطاق المحدد")
            }
        } finally {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun getFilePathFromUri(uri: Uri): String {
        if (uri.scheme == "file") return uri.path ?: throw IllegalStateException("مسار غير صالح")
        val tempFile = File(cacheDir, "temp_video_input_${System.nanoTime()}.mp4")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("تعذر فتح ملف الفيديو")
        return tempFile.absolutePath
    }

    // ===== خوارزمية إزالة الصمت =====

    private fun performSilenceRemoval(uri: Uri, outputPath: String, limitUs: Long) {
        val threshold = thresholds[currentLevel]
        val minSilUs  = minSilenceMs[currentLevel] * 1000L
        val padUs     = paddingMs[currentLevel] * 1000L
        val silentRanges = detectSilentRanges(uri, threshold, minSilUs, padUs, limitUs)
        transcodeWithoutSilence(uri, outputPath, silentRanges, limitUs)
    }

    // ---- المرحلة 1: تحليل الصمت ----

    private fun detectSilentRanges(
        uri: Uri, threshold: Float,
        minSilenceUs: Long, padUs: Long, limitUs: Long
    ): List<TimeRange> {

        val extractor = MediaExtractor()
        extractor.setDataSource(this, uri, null)
        val (trackIdx, format) = selectAudioTrack(extractor)
            ?: run { extractor.release(); return emptyList() }
        extractor.selectTrack(trackIdx)

        val mime        = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate  = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels    = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val windowBytes = (sampleRate * 0.05).toInt() * channels * 2

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val windows       = mutableListOf<PcmWindow>()
        var pcmAccum      = ByteArray(0)
        var windowStartUs = 0L
        var firstPcmUs    = -1L
        val bufInfo       = MediaCodec.BufferInfo()
        var inputDone     = false

        outer@ while (true) {
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val st = extractor.sampleTime
                    if (st < 0 || st > limitUs) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val sz  = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, st, 0)
                            extractor.advance()
                        }
                    }
                }
            }
            val outIdx = decoder.dequeueOutputBuffer(bufInfo, 5_000)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) { if (inputDone) break@outer; continue }
            if (outIdx >= 0) {
                val isEOS = (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                if (bufInfo.size > 0) {
                    if (firstPcmUs < 0) { firstPcmUs = bufInfo.presentationTimeUs; windowStartUs = firstPcmUs }
                    val outBuf = decoder.getOutputBuffer(outIdx)!!
                    val bytes  = ByteArray(bufInfo.size); outBuf.get(bytes)
                    pcmAccum += bytes
                    while (pcmAccum.size >= windowBytes) {
                        val chunk = pcmAccum.copyOfRange(0, windowBytes)
                        pcmAccum  = pcmAccum.copyOfRange(windowBytes, pcmAccum.size)
                        windows.add(PcmWindow(windowStartUs, calcRms(chunk) < threshold))
                        windowStartUs += 50_000L
                    }
                }
                decoder.releaseOutputBuffer(outIdx, false)
                if (isEOS) break@outer
            }
        }
        if (pcmAccum.isNotEmpty()) windows.add(PcmWindow(windowStartUs, calcRms(pcmAccum) < threshold))
        decoder.stop(); decoder.release(); extractor.release()
        return buildSilentRanges(windows, minSilenceUs, padUs)
    }

    private fun calcRms(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sumSq = 0.0; val n = buf.remaining()
        repeat(n) { val s = buf.get() / 32768.0; sumSq += s * s }
        return sqrt(sumSq / n).toFloat()
    }

    private fun buildSilentRanges(windows: List<PcmWindow>, minSilenceUs: Long, padUs: Long): List<TimeRange> {
        val result = mutableListOf<TimeRange>()
        var i = 0
        while (i < windows.size) {
            if (windows[i].isSilent) {
                val silStart = windows[i].startUs
                var j = i; while (j < windows.size && windows[j].isSilent) j++
                val silEnd = if (j < windows.size) windows[j].startUs else windows.last().startUs + 50_000L
                if (silEnd - silStart >= minSilenceUs) {
                    val trimStart = (silStart + padUs).coerceAtMost(silEnd)
                    val trimEnd   = (silEnd   - padUs).coerceAtLeast(silStart)
                    if (trimEnd > trimStart) result.add(TimeRange(trimStart, trimEnd))
                }
                i = j
            } else i++
        }
        return result
    }

    // ---- المرحلة 2: فك التشفير → حذف الصمت → إعادة الترميز AAC ----

    private fun transcodeWithoutSilence(
        uri: Uri, outputPath: String,
        silentRanges: List<TimeRange>, limitUs: Long
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(this, uri, null)
        val (trackIdx, srcFormat) = selectAudioTrack(extractor)
            ?: run { extractor.release(); throw IllegalStateException("لا يوجد مسار صوتي") }
        extractor.selectTrack(trackIdx)

        val srcMime    = srcFormat.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels   = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val decoder = MediaCodec.createDecoderByType(srcMime)
        decoder.configure(srcFormat, null, null, 0)
        decoder.start()

        val encoderMime = "audio/mp4a-latm"
        val encFormat = MediaFormat.createAudioFormat(encoderMime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val encoder = MediaCodec.createEncoderByType(encoderMime)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        var muxer: MediaMuxer? = null
        var muxTrack   = -1
        var muxStarted = false
        var writtenPcmBytes = 0L
        var encodedSamplesWritten = false
        val bytesPerFrame = (channels * 2).coerceAtLeast(2)

        try {
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val decInfo    = MediaCodec.BufferInfo()
            val encInfo    = MediaCodec.BufferInfo()
            var decInDone  = false
            var decOutDone = false
            var encInDone  = false
            var encOutDone = false

            fun drainEncoder(timeoutUs: Long): Boolean {
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(encInfo, timeoutUs)
                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxStarted) {
                                throw IllegalStateException("تغير تنسيق المشفر أكثر من مرة")
                            }
                            muxTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxStarted = true
                        }
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                        idx >= 0 -> {
                            val isEos = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (encInfo.size > 0 &&
                                encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                if (!muxStarted || muxTrack < 0) {
                                    throw IllegalStateException("لم يبدأ muxer قبل كتابة العينات")
                                }
                                val outBuf = encoder.getOutputBuffer(idx)!!
                                outBuf.position(encInfo.offset)
                                outBuf.limit(encInfo.offset + encInfo.size)
                                muxer.writeSampleData(muxTrack, outBuf, encInfo)
                                encodedSamplesWritten = true
                            }
                            encoder.releaseOutputBuffer(idx, false)
                            if (isEos) return true
                        }
                    }
                }
            }

            while (!encOutDone) {
                // 1. إرسال بيانات مضغوطة للمفكك
                if (!decInDone) {
                    val inIdx = decoder.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        val st = extractor.sampleTime
                        if (st < 0 || st > limitUs) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decInDone = true
                        } else {
                            val buf = decoder.getInputBuffer(inIdx)!!
                            val sz  = extractor.readSampleData(buf, 0)
                            if (sz < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                decInDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, sz, st, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 2. PCM من المفكك → تخطي الصمت → إرسال للمشفر
                if (!decOutDone && !encInDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 0)
                    if (outIdx >= 0) {
                        val isDecEOS = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (decInfo.size > 0) {
                            val ptsUs    = decInfo.presentationTimeUs
                            val silRange = silentRanges.firstOrNull { ptsUs >= it.startUs && ptsUs < it.endUs }
                            if (silRange == null) {
                                val outBuf = decoder.getOutputBuffer(outIdx)!!
                                outBuf.position(decInfo.offset)
                                outBuf.limit(decInfo.offset + decInfo.size)
                                val pcm    = ByteArray(decInfo.size)
                                outBuf.get(pcm)
                                var offset = 0
                                while (offset < pcm.size) {
                                    val ei = encoder.dequeueInputBuffer(5_000)
                                    if (ei >= 0) {
                                        val encIn = encoder.getInputBuffer(ei)!!
                                        val canW  = minOf(pcm.size - offset, encIn.capacity())
                                        encIn.clear(); encIn.put(pcm, offset, canW)
                                        val adjPts = (writtenPcmBytes / bytesPerFrame) * 1_000_000L / sampleRate
                                        encoder.queueInputBuffer(ei, 0, canW, adjPts, 0)
                                        writtenPcmBytes += canW
                                        offset += canW
                                    }
                                    drainEncoder(0)
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (isDecEOS) {
                            decOutDone = true
                            val ei = encoder.dequeueInputBuffer(5_000)
                            if (ei >= 0) {
                                encoder.queueInputBuffer(ei, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                encInDone = true
                            }
                        }
                    }
                }

                // 3. استنزاف المشفر
                encOutDone = drainEncoder(5_000)
            }

            if (!encodedSamplesWritten) {
                throw IllegalStateException("لم تتم كتابة أي عينات صوتية")
            }
        } finally {
            try { decoder.stop(); decoder.release() } catch (_: Exception) {}
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            try { if (muxStarted) muxer?.stop()    } catch (_: Exception) {}
            try { muxer?.release()                 } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return Pair(i, fmt)
        }
        return null
    }

    // ===== واجهة المستخدم =====

    private fun showResultButtons() {
        btnApply.visibility     = View.GONE
        seekBarLevel.isEnabled  = false
        layoutResult.visibility = View.VISIBLE
        btnPlayPause.text       = "تشغيل النتيجة"
    }

    private fun hideResultButtons() {
        layoutResult.visibility = View.GONE
        btnApply.visibility     = View.VISIBLE
        seekBarLevel.isEnabled  = true
        btnPlayPause.text       = "تشغيل النتيجة"
    }

    private fun setUiBusy(busy: Boolean, status: String) {
        progressBar.visibility  = if (busy) View.VISIBLE else View.GONE
        tvStatus.text           = status
        btnApply.isEnabled      = !busy
        seekBarLevel.isEnabled  = !busy
        btnPlayPause.isEnabled  = !busy
        btnUndo.isEnabled       = !busy
        btnSave.isEnabled       = !busy
    }

    override fun onDestroy() {
        stopAndReleasePlayer()
        resultTempFile?.delete()
        executor.shutdownNow()
        super.onDestroy()
    }
}
