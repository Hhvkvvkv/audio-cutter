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

class NoiseReductionActivity : AppCompatActivity() {

    private var audioUri: Uri? = null
    private var currentLevel = 0
    private var mediaKind = MediaKind.AUDIO
    private val isVideo: Boolean get() = mediaKind == MediaKind.VIDEO

    private var resultTempFile: File? = null
    private var resultPlayer: MediaPlayer? = null
    private var isResultPlaying = false

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private val thresholds = floatArrayOf(0.005f, 0.012f, 0.025f, 0.040f)
    private val reductionFactors = floatArrayOf(0.55f, 0.30f, 0.12f, 0.04f)

    private lateinit var tvFileName: TextView
    private lateinit var seekBarLevel: SeekBar
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var btnApply: Button
    private lateinit var layoutResult: LinearLayout
    private lateinit var btnPlayPause: Button
    private lateinit var btnUndo: Button
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_noise_reduction)

        audioUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("AUDIO_URI", Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("AUDIO_URI") as? Uri
        }
        mediaKind = try { MediaKind.valueOf(intent.getStringExtra("MEDIA_KIND") ?: "AUDIO") } catch (_: Exception) { MediaKind.AUDIO }

        if (audioUri == null) {
            Toast.makeText(this, "فشل تحميل الملف", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        tvFileName = findViewById(R.id.tvNoiseFileName)
        seekBarLevel = findViewById(R.id.seekBarNoiseLevel)
        progressBar = findViewById(R.id.noiseProgressBar)
        tvStatus = findViewById(R.id.tvNoiseStatus)
        btnApply = findViewById(R.id.btnNoiseApply)
        layoutResult = findViewById(R.id.layoutNoiseResultButtons)
        btnPlayPause = findViewById(R.id.btnNoisePlayPause)
        btnUndo = findViewById(R.id.btnNoiseUndo)
        btnSave = findViewById(R.id.btnNoiseSave)

        tvFileName.text = audioUri?.lastPathSegment ?: if (isVideo) "ملف فيديو" else "ملف صوتي"

        seekBarLevel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentLevel = progress
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        seekBarLevel.progress = 0

        btnApply.setOnClickListener { startApply() }
        btnPlayPause.setOnClickListener { toggleResultPlayback() }
        btnUndo.setOnClickListener { undoOperation() }
        btnSave.setOnClickListener { saveResult() }
    }

    private fun startApply() {
        val srcUri = audioUri ?: return
        setUiBusy(true, "جارٍ تطبيق إزالة الضوضاء...")
        executor.execute {
            try {
                if (isVideo && hasVideoTrack()) {
                    val tmp = File(cacheDir, "noise_reduction_video_${System.currentTimeMillis()}.mp4")
                    applyNoiseReductionToVideo(srcUri, tmp.absolutePath)
                    handler.post {
                        resultTempFile = tmp
                        setUiBusy(false, "✓ اكتملت إزالة الضوضاء من الفيديو")
                        showResultButtons()
                    }
                } else {
                    val tmp = File(cacheDir, "noise_reduction_${System.currentTimeMillis()}.m4a")
                    applyNoiseReductionToAudio(srcUri, tmp.absolutePath)
                    handler.post {
                        resultTempFile = tmp
                        setUiBusy(false, "✓ اكتملت إزالة الضوضاء")
                        showResultButtons()
                    }
                }
            } catch (e: Exception) {
                ErrorLog.logException(this, "NOISE_REDUCTION", e); TelegramReporter.sendException("NOISE_REDUCTION", e)
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
        } catch (_: Exception) { false } finally { extractor.release() }
    }

    // ===== Audio noise reduction =====

    private fun applyNoiseReductionToAudio(uri: Uri, outputPath: String) {
        val threshold = thresholds[currentLevel]
        val reductionFactor = reductionFactors[currentLevel]

        val extractor = MediaExtractor()
        extractor.setDataSource(this, uri, null)
        val (trackIdx, srcFormat) = selectAudioTrack(extractor)
            ?: run { extractor.release(); throw IllegalStateException("لا يوجد مسار صوتي") }
        extractor.selectTrack(trackIdx)

        val srcMime = srcFormat.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val decoder = MediaCodec.createDecoderByType(srcMime)
        decoder.configure(srcFormat, null, null, 0)
        decoder.start()

        val encFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val frameDurationUs = 50_000L
        val bytesPerFrame = ((sampleRate * frameDurationUs) / 1_000_000L).toInt() * channels * 2

        var muxer: MediaMuxer? = null
        var muxTrack = -1
        var muxStarted = false
        var writtenPcmBytes = 0L
        var encodedSamplesWritten = false

        try {
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var decInDone = false
            var decOutDone = false
            var encInDone = false
            var encOutDone = false

            fun drainEncoder(timeoutUs: Long): Boolean {
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(encInfo, timeoutUs)
                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxStarted) throw IllegalStateException("تغير تنسيق المشفر أكثر من مرة")
                            muxTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxStarted = true
                        }
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                        idx >= 0 -> {
                            val isEos = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (encInfo.size > 0 && encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                if (!muxStarted || muxTrack < 0) throw IllegalStateException("لم يبدأ muxer")
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

            var pcmFrame = ByteArray(0)

            while (!encOutDone) {
                if (!decInDone) {
                    val inIdx = decoder.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        val st = extractor.sampleTime
                        if (st < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decInDone = true
                        } else {
                            val buf = decoder.getInputBuffer(inIdx)!!
                            val sz = extractor.readSampleData(buf, 0)
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

                if (!decOutDone && !encInDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 0)
                    if (outIdx >= 0) {
                        val isDecEOS = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (decInfo.size > 0) {
                            val outBuf = decoder.getOutputBuffer(outIdx)!!
                            outBuf.position(decInfo.offset)
                            outBuf.limit(decInfo.offset + decInfo.size)
                            val pcm = ByteArray(decInfo.size)
                            outBuf.get(pcm)
                            pcmFrame += pcm

                            while (pcmFrame.size >= bytesPerFrame) {
                                val frame = pcmFrame.copyOfRange(0, bytesPerFrame)
                                pcmFrame = pcmFrame.copyOfRange(bytesPerFrame, pcmFrame.size)
                                val processed = applyNoiseGate(frame, threshold, reductionFactor)
                                var offset = 0
                                while (offset < processed.size) {
                                    val ei = encoder.dequeueInputBuffer(5_000)
                                    if (ei >= 0) {
                                        val encIn = encoder.getInputBuffer(ei)!!
                                        val canW = minOf(processed.size - offset, encIn.capacity())
                                        encIn.clear(); encIn.put(processed, offset, canW)
                                        val adjPts = (writtenPcmBytes / (channels * 2L)) * 1_000_000L / sampleRate
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
                            if (pcmFrame.size >= bytesPerFrame / 2) {
                                val processed = applyNoiseGate(pcmFrame, threshold, reductionFactor)
                                var offset = 0
                                while (offset < processed.size) {
                                    val ei = encoder.dequeueInputBuffer(5_000)
                                    if (ei >= 0) {
                                        val encIn = encoder.getInputBuffer(ei)!!
                                        val canW = minOf(processed.size - offset, encIn.capacity())
                                        encIn.clear(); encIn.put(processed, offset, canW)
                                        val adjPts = (writtenPcmBytes / (channels * 2L)) * 1_000_000L / sampleRate
                                        encoder.queueInputBuffer(ei, 0, canW, adjPts, 0)
                                        writtenPcmBytes += canW
                                        offset += canW
                                    }
                                    drainEncoder(0)
                                }
                            }
                            pcmFrame = ByteArray(0)
                            val ei = encoder.dequeueInputBuffer(5_000)
                            if (ei >= 0) {
                                encoder.queueInputBuffer(ei, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                encInDone = true
                            }
                        }
                    }
                }

                encOutDone = drainEncoder(5_000)
            }

            if (!encodedSamplesWritten) throw IllegalStateException("لم تتم كتابة أي عينات صوتية")
        } finally {
            try { decoder.stop(); decoder.release() } catch (_: Exception) {}
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            try { if (muxStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun applyNoiseGate(pcm: ByteArray, threshold: Float, reductionFactor: Float): ByteArray {
        if (pcm.size < 4) return pcm
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shorts = ShortArray(buf.remaining())
        buf.get(shorts)

        var sumSq = 0.0
        for (s in shorts) {
            val norm = s / 32768.0
            sumSq += norm * norm
        }
        val rms = sqrt(sumSq / shorts.size).toFloat()
        val gain = if (rms < threshold) reductionFactor else 1.0f

        val result = ByteArray(pcm.size)
        val outBuf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (s in shorts) {
            val sample = (s * gain).toInt().coerceIn(-32768, 32767)
            outBuf.put(sample.toShort())
        }
        return result
    }

    // ===== Video noise reduction =====

    private fun applyNoiseReductionToVideo(uri: Uri, outputPath: String) {
        val processedAudio = File(cacheDir, "temp_noise_audio_${System.nanoTime()}.m4a")
        try {
            applyNoiseReductionToAudio(uri, processedAudio.absolutePath)
            remuxVideoWithNewAudio(uri, processedAudio.absolutePath, outputPath)
        } finally {
            processedAudio.delete()
        }
    }

    private fun remuxVideoWithNewAudio(videoUri: Uri, processedAudioPath: String, outputPath: String) {
        val inputPath = getFilePathFromUri(videoUri)

        val videoTrackFormats = mutableListOf<Pair<Int, MediaFormat>>()
        var tempExtractor = MediaExtractor()
        try {
            tempExtractor.setDataSource(inputPath)
            for (i in 0 until tempExtractor.trackCount) {
                val fmt = tempExtractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrackFormats.add(Pair(i, fmt))
                }
            }
        } finally { tempExtractor.release() }

        val audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(processedAudioPath)
        val (audioTrackIdx, audioFormat) = selectAudioTrack(audioExtractor)
            ?: run { audioExtractor.release(); throw IllegalStateException("لا يوجد مسار صوتي في الصوت المعالج") }
        audioExtractor.selectTrack(audioTrackIdx)

        File(outputPath).parentFile?.mkdirs()
        File(outputPath).delete()

        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var anyWritten = false

        try {
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val muxerVideoTracks = mutableListOf<Int>()
            for ((_, fmt) in videoTrackFormats) {
                muxerVideoTracks.add(muxer.addTrack(fmt))
            }
            val muxerAudioTrack = muxer.addTrack(audioFormat)

            muxer.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            for ((origIdx, _) in videoTrackFormats) {
                var ve = MediaExtractor()
                try {
                    ve.setDataSource(inputPath)
                    ve.selectTrack(origIdx)

                    while (true) {
                        val sampleTime = ve.sampleTime
                        if (sampleTime < 0) break
                        buffer.clear()
                        val size = ve.readSampleData(buffer, 0)
                        if (size < 0) break
                        bufferInfo.offset = 0
                        bufferInfo.size = size
                        bufferInfo.flags = if (ve.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        bufferInfo.presentationTimeUs = sampleTime
                        val trackIdx = videoTrackFormats.indexOfFirst { it.first == origIdx }
                        muxer.writeSampleData(muxerVideoTracks[trackIdx], buffer, bufferInfo)
                        anyWritten = true
                        if (!ve.advance()) break
                    }
                } finally { ve.release() }
            }

            while (true) {
                val sampleTime = audioExtractor.sampleTime
                if (sampleTime < 0) break
                buffer.clear()
                val size = audioExtractor.readSampleData(buffer, 0)
                if (size < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = size
                bufferInfo.flags = if (audioExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                bufferInfo.presentationTimeUs = sampleTime
                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                anyWritten = true
                if (!audioExtractor.advance()) break
            }

            if (!anyWritten) throw IllegalStateException("لم تتم كتابة أي عينات")
        } finally {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            audioExtractor.release()
        }
    }

    private fun getFilePathFromUri(uri: Uri): String {
        if (uri.scheme == "file") return uri.path ?: throw IllegalStateException("مسار غير صالح")
        val tempFile = File(cacheDir, "temp_video_noise_${System.nanoTime()}.mp4")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("تعذر فتح ملف الفيديو")
        return tempFile.absolutePath
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return Pair(i, fmt)
        }
        return null
    }

    // ===== Save =====

    private fun saveResult() {
        val tmp = resultTempFile ?: return
        showSaveDialog(tmp)
    }

    private fun showSaveDialog(tmp: File) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_save_audio, null)
        val etName = dialogView.findViewById<EditText>(R.id.etFileName)
        val spFormat = dialogView.findViewById<Spinner>(R.id.spFormat)

        if (isVideo) {
            spFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("mp4"))
            spFormat.isEnabled = false
        } else {
            spFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("m4a", "mp4"))
        }
        etName.setText("no_noise_${System.currentTimeMillis()}")

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
                ErrorLog.logException(this, "NOISE_REDUCTION", e); TelegramReporter.sendException("NOISE_REDUCTION", e)
                handler.post { setUiBusy(false, "فشل الحفظ: ${e.message}") }
            }
        }
    }


    private fun saveViaMediaStore(tmp: File, fileName: String, format: String) {
        val mimeType = if (isVideo) "video/mp4" else "audio/mp4"
        val relativePath = if (isVideo) {
            "${getString(R.string.output_root_folder)}/${getString(R.string.folder_video_noise_reduction)}"
        } else {
            "${getString(R.string.output_root_folder)}/noise_reduction"
        }
        val uri = StoragePaths.saveToMediaStore(this, tmp, fileName, format, mimeType, relativePath)
        if (uri == null) throw IllegalStateException("فشل حفظ الملف عبر MediaStore")
    }

    private fun saveViaDirectFile(tmp: File, fileName: String, format: String) {
        val dir = if (isVideo) {
            val d = File(StoragePaths.videoRoot(this), getString(R.string.folder_video_noise_reduction))
            d.mkdirs(); d
        } else {
            StoragePaths.toolDir(this, "noise_reduction").also { it.mkdirs() }
        }
        val outFile = File(dir, "$fileName.$format")
        FileInputStream(tmp).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        if (!outFile.exists() || outFile.length() <= 0L) {
            outFile.delete()
            throw IllegalStateException("لم يتم إنشاء ملف صالح")
        }
    }

    // ===== Playback =====

    private fun toggleResultPlayback() {
        if (isResultPlaying) pauseResult() else playResult()
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
        try { resultPlayer?.stop() } catch (_: Exception) {}
        try { resultPlayer?.release() } catch (_: Exception) {}
        resultPlayer = null
        isResultPlaying = false
    }

    private fun undoOperation() {
        stopAndReleasePlayer()
        resultTempFile?.delete()
        resultTempFile = null
        hideResultButtons()
        tvStatus.text = ""
        Toast.makeText(this, "تم التراجع عن عملية إزالة الضوضاء", Toast.LENGTH_SHORT).show()
    }

    // ===== UI =====

    private fun showResultButtons() {
        btnApply.visibility = View.GONE
        seekBarLevel.isEnabled = false
        layoutResult.visibility = View.VISIBLE
        btnPlayPause.text = "تشغيل النتيجة"
    }

    private fun hideResultButtons() {
        layoutResult.visibility = View.GONE
        btnApply.visibility = View.VISIBLE
        seekBarLevel.isEnabled = true
        btnPlayPause.text = "تشغيل النتيجة"
    }

    private fun setUiBusy(busy: Boolean, status: String) {
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        tvStatus.text = status
        btnApply.isEnabled = !busy
        seekBarLevel.isEnabled = !busy
        btnPlayPause.isEnabled = !busy
        btnUndo.isEnabled = !busy
        btnSave.isEnabled = !busy
    }

    override fun onDestroy() {
        stopAndReleasePlayer()
        resultTempFile?.delete()
        executor.shutdownNow()
        super.onDestroy()
    }
}
