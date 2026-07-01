package com.companyname.appname

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.companyname.appname.TelegramReporter
import com.companyname.appname.ErrorLog
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorderActivity : AppCompatActivity() {

    private enum class AudioSource(val labelRes: Int) {
        MIC(R.string.recorder_source_mic),
        SYSTEM(R.string.recorder_source_system),
        BOTH(R.string.recorder_source_both)
    }

    private var selectedSource = AudioSource.MIC
    private var isRecording = false
    private var isPaused = false
    private var recordingStartTime = 0L
    private var pausedDuration = 0L
    private var pauseStartTime = 0L

    private var micAudioRecord: AudioRecord? = null
    private var systemAudioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private var recordingThread: Thread? = null
    private var outputFile: File? = null
    private var dataSize = 0L

    private val sampleRate = 44100
    private val channels: Short = 1
    private val bitsPerSample = 16

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    private lateinit var btnSelectSource: Button
    private lateinit var btnStartRecording: Button
    private lateinit var layoutRecording: LinearLayout
    private lateinit var tvRecorderStatus: TextView
    private lateinit var tvRecordingTime: TextView
    private lateinit var recordingProgressBar: ProgressBar
    private lateinit var btnPauseResume: Button
    private lateinit var btnStopSave: Button
    private lateinit var btnCancelRecording: Button
    private lateinit var tvRecorderMessage: TextView

    private var projectionReceiver: BroadcastReceiver? = null

    companion object {
        private const val PERMISSION_RECORD_AUDIO_CODE = 200
        private const val PERMISSION_NOTIFICATION_CODE = 202
        private const val MEDIA_PROJECTION_REQUEST_CODE = 201
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_recorder)

        btnSelectSource = findViewById(R.id.btnSelectSource)
        btnStartRecording = findViewById(R.id.btnStartRecording)
        layoutRecording = findViewById(R.id.layoutRecording)
        tvRecorderStatus = findViewById(R.id.tvRecorderStatus)
        tvRecordingTime = findViewById(R.id.tvRecordingTime)
        recordingProgressBar = findViewById(R.id.recordingProgressBar)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        btnStopSave = findViewById(R.id.btnStopSave)
        btnCancelRecording = findViewById(R.id.btnCancelRecording)
        tvRecorderMessage = findViewById(R.id.tvRecorderMessage)

        btnSelectSource.setOnClickListener { showSourceDialog() }
        btnStartRecording.setOnClickListener { requestStartRecording() }
        btnPauseResume.setOnClickListener { togglePauseResume() }
        btnStopSave.setOnClickListener { stopAndSave() }
        btnCancelRecording.setOnClickListener { cancelRecording() }
    }

    override fun onDestroy() {
        projectionReceiver?.let { unregisterReceiver(it) }
        cleanupRecording()
        super.onDestroy()
    }

    // ===== Source selection =====

    private fun showSourceDialog() {
        val items = arrayOf(
            getString(R.string.recorder_source_mic),
            getString(R.string.recorder_source_system),
            getString(R.string.recorder_source_both)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.recorder_source_label))
            .setItems(items) { _, which ->
                selectedSource = when (which) {
                    1 -> AudioSource.SYSTEM
                    2 -> AudioSource.BOTH
                    else -> AudioSource.MIC
                }
                btnSelectSource.text = getString(selectedSource.labelRes)
            }
            .show()
    }

    // ===== Start recording =====

    private fun requestStartRecording() {
        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermission()
            return
        }
        if (selectedSource == AudioSource.MIC) {
            startRecordingImmediate()
            return
        }
        // SYSTEM / BOTH need notification permission for foreground service (API 33+)
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            requestNotificationPermission()
            return
        }
        doStartMediaProjection()
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_NOTIFICATION_CODE)
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRecordAudioPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_RECORD_AUDIO_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_RECORD_AUDIO_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestStartRecording()
            } else {
                Toast.makeText(this, "يجب منح إذن الميكروفون للتسجيل", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == PERMISSION_NOTIFICATION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                doStartMediaProjection()
            } else {
                Toast.makeText(this, "يجب منح إذن الإشعارات لتسجيل صوت النظام", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun doStartMediaProjection() {
        if (Build.VERSION.SDK_INT >= 34) {
            val svcIntent = Intent(this, MediaProjectionForegroundService::class.java)
            ContextCompat.startForegroundService(this, svcIntent)
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (Build.VERSION.SDK_INT < 34) {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mpm.getMediaProjection(resultCode, data)
                    startRecordingImmediate()
                    return
                }
                val svcIntent = Intent(this, MediaProjectionForegroundService::class.java).apply {
                    putExtra(MediaProjectionForegroundService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(MediaProjectionForegroundService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(this, svcIntent)
                registerProjectionReceiver()
            } else {
                Toast.makeText(this, "يجب منح إذن تسجيل الشاشة لالتقاط صوت النظام", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun registerProjectionReceiver() {
        projectionReceiver?.let { unregisterReceiver(it) }
        projectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    MediaProjectionForegroundService.ACTION_READY -> {
                        mediaProjection = MediaProjectionForegroundService.mediaProjection
                        projectionReceiver?.let { unregisterReceiver(it) }
                        projectionReceiver = null
                        startRecordingImmediate()
                    }
                    MediaProjectionForegroundService.ACTION_FAILED -> {
                        projectionReceiver?.let { unregisterReceiver(it) }
                        projectionReceiver = null
                        Toast.makeText(this@AudioRecorderActivity,
                            "تسجيل صوت النظام غير مدعوم على هذا الجهاز", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(MediaProjectionForegroundService.ACTION_READY)
            addAction(MediaProjectionForegroundService.ACTION_FAILED)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            registerReceiver(projectionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(projectionReceiver, filter)
        }
    }

    // ===== Recording engine =====

    private fun startRecordingImmediate() {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)

        // Create MIC AudioRecord if needed
        if (selectedSource == AudioSource.MIC || selectedSource == AudioSource.BOTH) {
            micAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            if (micAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(this, "فشل تهيئة تسجيل الميكروفون", Toast.LENGTH_SHORT).show()
                micAudioRecord?.release()
                micAudioRecord = null
                return
            }
            micAudioRecord?.startRecording()
            if (micAudioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Toast.makeText(this, "فشل بدء تسجيل الميكروفون", Toast.LENGTH_SHORT).show()
                micAudioRecord?.release()
                micAudioRecord = null
                return
            }
        }

        // Create SYSTEM AudioRecord if needed
        if (selectedSource == AudioSource.SYSTEM || selectedSource == AudioSource.BOTH) {
            val proj = mediaProjection
            if (proj == null && Build.VERSION.SDK_INT >= 29) {
                Toast.makeText(this, "لم يتم منح إذن تسجيل صوت النظام", Toast.LENGTH_SHORT).show()
                return
            }
            if (Build.VERSION.SDK_INT >= 29) {
                systemAudioRecord = createSystemAudioRecord(proj!!, bufferSize)
                if (systemAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Toast.makeText(this, "فشل تهيئة تسجيل صوت النظام", Toast.LENGTH_SHORT).show()
                    systemAudioRecord?.release()
                    systemAudioRecord = null
                    return
                }
                systemAudioRecord?.startRecording()
                if (systemAudioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Toast.makeText(this, "فشل بدء تسجيل صوت النظام", Toast.LENGTH_SHORT).show()
                    systemAudioRecord?.release()
                    systemAudioRecord = null
                    return
                }
            } else {
                Toast.makeText(this, "تسجيل صوت النظام يتطلب Android 10+", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Create output file in cache
        val tmpFile = File(cacheDir, "recording_${System.currentTimeMillis()}.pcm")
        outputFile = tmpFile

        isRecording = true
        isPaused = false
        recordingStartTime = System.currentTimeMillis()
        pausedDuration = 0L

        // Start recording thread
        recordingThread = Thread {
            writeRecordingData(tmpFile, bufferSize)
        }
        recordingThread?.start()

        // Update UI
        btnStartRecording.visibility = View.GONE
        layoutRecording.visibility = View.VISIBLE
        tvRecorderStatus.text = getString(R.string.recorder_recording)
        recordingProgressBar.visibility = View.VISIBLE
        btnPauseResume.text = getString(R.string.recorder_pause)
        tvRecorderMessage.text = ""
        startTimer()
    }


    private fun createSystemAudioRecord(projection: MediaProjection, bufferSize: Int): AudioRecord? {
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        return AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize * 2)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()
    }

    private fun writeRecordingData(file: File, bufferSize: Int) {
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(file, "rw")
            writeWavHeader(raf)
            dataSize = 0

            val micBuffer = ShortArray(bufferSize / 2)
            val sysBuffer = ShortArray(bufferSize / 2)

            while (isRecording) {
                if (isPaused) {
                    Thread.sleep(100)
                    continue
                }

                when (selectedSource) {
                    AudioSource.MIC -> {
                        val mic = micAudioRecord ?: break
                        val read = mic.read(micBuffer, 0, micBuffer.size)
                        if (read > 0) {
                            val bytes = shortArrayToByteArray(micBuffer, read)
                            raf.write(bytes)
                            dataSize += bytes.size
                        }
                    }
                    AudioSource.SYSTEM -> {
                        val sys = systemAudioRecord ?: break
                        val read = sys.read(sysBuffer, 0, sysBuffer.size)
                        if (read > 0) {
                            val bytes = shortArrayToByteArray(sysBuffer, read)
                            raf.write(bytes)
                            dataSize += bytes.size
                        }
                    }
                    AudioSource.BOTH -> {
                        val mic = micAudioRecord
                        val sys = systemAudioRecord
                        if (mic == null || sys == null) break

                        val micRead = mic.read(micBuffer, 0, micBuffer.size)
                        val sysRead = sys.read(sysBuffer, 0, sysBuffer.size)
                        val samples = minOf(micRead, sysRead).coerceAtLeast(0)
                        if (samples > 0) {
                            val mixed = ShortArray(samples)
                            for (i in 0 until samples) {
                                val sum = micBuffer[i].toInt() + sysBuffer[i].toInt()
                                mixed[i] = (sum / 2).coerceIn(-32768, 32767).toShort()
                            }
                            val bytes = shortArrayToByteArray(mixed, samples)
                            raf.write(bytes)
                            dataSize += bytes.size
                        }
                    }
                }
            }

            // Update WAV header with actual data size
            val totalSize = 36 + dataSize
            raf.seek(4)
            raf.write(intToBytes(totalSize.toInt()))
            raf.seek(40)
            raf.write(intToBytes(dataSize.toInt()))
        } catch (e: Exception) {
            ErrorLog.logException(this, "RECORDER", e)
            TelegramReporter.sendException("RECORDER", e)
        } finally {
            try { raf?.close() } catch (_: Exception) {}
        }
    }

    private fun writeWavHeader(raf: RandomAccessFile) {
        // RIFF header
        raf.write("RIFF".toByteArray())
        raf.write(intToBytes(0)) // file size - placeholder
        raf.write("WAVE".toByteArray())
        // fmt chunk
        raf.write("fmt ".toByteArray())
        raf.write(intToBytes(16)) // subchunk size (PCM)
        raf.write(shortToBytes(1)) // audio format (PCM)
        raf.write(shortToBytes(channels))
        raf.write(intToBytes(sampleRate))
        raf.write(intToBytes(sampleRate * channels * bitsPerSample / 8)) // byte rate
        raf.write(shortToBytes((channels * bitsPerSample / 8).toShort())) // block align
        raf.write(shortToBytes(bitsPerSample.toShort()))
        // data chunk
        raf.write("data".toByteArray())
        raf.write(intToBytes(0)) // data size - placeholder
    }

    private fun shortArrayToByteArray(shorts: ShortArray, count: Int): ByteArray {
        val result = ByteArray(count * 2)
        val buf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        buf.put(shorts, 0, count)
        return result
    }

    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToBytes(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }

    // ===== Timer =====

    private fun startTimer() {
        timerRunnable = object : Runnable {
            override fun run() {
                if (isRecording && !isPaused) {
                    val elapsed = System.currentTimeMillis() - recordingStartTime - pausedDuration
                    val secs = (elapsed / 1000).toInt()
                    val mins = secs / 60
                    val remainingSecs = secs % 60
                    tvRecordingTime.text = String.format("%02d:%02d", mins, remainingSecs)
                    handler.postDelayed(this, 200)
                }
            }
        }
        timerRunnable?.let { handler.postDelayed(it, 200) }
    }

    private fun stopTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
    }

    // ===== Pause / Resume =====

    private fun togglePauseResume() {
        if (isPaused) {
            resumeRecording()
        } else {
            pauseRecording()
        }
    }

    private fun pauseRecording() {
        isPaused = true
        pauseStartTime = System.currentTimeMillis()
        tvRecorderStatus.text = getString(R.string.recorder_paused)
        recordingProgressBar.visibility = View.GONE
        btnPauseResume.text = getString(R.string.recorder_resume)
    }

    private fun resumeRecording() {
        isPaused = false
        pausedDuration += System.currentTimeMillis() - pauseStartTime
        tvRecorderStatus.text = getString(R.string.recorder_recording)
        recordingProgressBar.visibility = View.VISIBLE
        btnPauseResume.text = getString(R.string.recorder_pause)
    }

    // ===== Stop & Save =====

    private fun stopAndSave() {
        if (!isRecording) return
        stopRecording()
        tvRecorderMessage.text = "جارٍ حفظ التسجيل..."
        executor.execute {
            try {
                val tmpFile = outputFile
                if (tmpFile != null && tmpFile.exists() && dataSize > 0) {
                    saveToPublicFolder(tmpFile)
                } else {
                    handler.post {
                        tvRecorderMessage.text = "فشل الحفظ: الملف فارغ"
                        resetUI()
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    tvRecorderMessage.text = "فشل الحفظ: ${e.message}"
                    resetUI()
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        stopTimer()
        recordingThread?.join(2000)
        recordingThread = null
        releaseAudioRecorders()
    }

    private fun releaseAudioRecorders() {
        try {
            if (micAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                micAudioRecord?.stop()
            }
            micAudioRecord?.release()
        } catch (_: Exception) {}
        micAudioRecord = null
        try {
            if (systemAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                systemAudioRecord?.stop()
            }
            systemAudioRecord?.release()
        } catch (_: Exception) {}
        systemAudioRecord = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                stopService(Intent(this, MediaProjectionForegroundService::class.java))
            } catch (_: Exception) {}
        }
    }

    private fun cleanupRecording() {
        isRecording = false
        stopTimer()
        releaseAudioRecorders()
        outputFile?.delete()
        outputFile = null
        stopService(Intent(this, MediaProjectionForegroundService::class.java))
    }

    private fun saveToPublicFolder(tmpFile: File) {
        val fileName = "recording_${System.currentTimeMillis()}"
        val mimeType = "audio/wav"
        val format = "wav"

        if (Build.VERSION.SDK_INT >= 29) {
            val relativePath = "${getString(R.string.output_root_folder)}/recordings"
            val uri = StoragePaths.saveToMediaStore(this, tmpFile, fileName, format, mimeType, relativePath)
            handler.post {
                if (uri != null) {
                    tvRecorderMessage.text = "✓ تم حفظ التسجيل بنجاح"
                    Toast.makeText(this, "تم حفظ التسجيل", Toast.LENGTH_LONG).show()
                } else {
                    tvRecorderMessage.text = "فشل الحفظ عبر MediaStore"
                }
                resetUI()
            }
        } else {
            val dir = StoragePaths.toolDir(this, "recordings")
            dir.mkdirs()
            val outFile = File(dir, "$fileName.$format")
            FileInputStream(tmpFile).use { input ->
                java.io.FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            handler.post {
                tvRecorderMessage.text = "✓ تم حفظ التسجيل: ${outFile.name}"
                resetUI()
            }
        }
    }

    // ===== Cancel =====

    private fun cancelRecording() {
        if (isRecording) {
            stopRecording()
            outputFile?.delete()
            outputFile = null
            tvRecorderMessage.text = "تم إلغاء التسجيل"
            resetUI()
        }
    }

    private fun resetUI() {
        btnStartRecording.visibility = View.VISIBLE
        layoutRecording.visibility = View.GONE
        recordingProgressBar.visibility = View.GONE
        tvRecordingTime.text = "00:00"
    }
}
