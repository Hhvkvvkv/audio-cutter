package com.companyname.appname

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class CutMethodActivity : AppCompatActivity() {

    private var selectedMethodIndex: Int = -1
    private var currentTool: String = "cut"

    private lateinit var fileBrowserLauncher: ActivityResultLauncher<Intent>
    private lateinit var audioRecorderLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cut_method)

        fileBrowserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uriText = result.data?.getStringExtra("AUDIO_URI")
                val mediaKindStr = result.data?.getStringExtra("MEDIA_KIND")
                val mediaKind = try { MediaKind.valueOf(mediaKindStr ?: "AUDIO") } catch (_: Exception) { MediaKind.AUDIO }
                val uri = if (!uriText.isNullOrBlank()) Uri.parse(uriText) else null

                if (uri != null) {
                    when (currentTool) {
                        "silence" -> {
                            val intent = Intent(this, SilenceRemovalActivity::class.java)
                            intent.putExtra("AUDIO_URI", uri)
                            intent.putExtra("MEDIA_KIND", mediaKind.name)
                            startActivity(intent)
                        }
                        "noise" -> {
                            val intent = Intent(this, NoiseReductionActivity::class.java)
                            intent.putExtra("AUDIO_URI", uri)
                            intent.putExtra("MEDIA_KIND", mediaKind.name)
                            startActivity(intent)
                        }
                        "video_cut" -> {
                            val intent = Intent(this, AudioEditorActivity::class.java)
                            intent.putExtra("AUDIO_URI", uri)
                            intent.putExtra("CUT_METHOD_INDEX", 1)
                            intent.putExtra("MEDIA_KIND", MediaKind.VIDEO.name)
                            startActivity(intent)
                        }
                        else -> { // "cut"
                            val intent = Intent(this, AudioEditorActivity::class.java)
                            intent.putExtra("AUDIO_URI", uri)
                            intent.putExtra("CUT_METHOD_INDEX", selectedMethodIndex)
                            intent.putExtra("MEDIA_KIND", mediaKind.name)
                            startActivity(intent)
                        }
                    }
                } else {
                    Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
            }
        }

        audioRecorderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // AudioRecorderActivity returns nothing special, just continue
        }

        // أدوات القص
        findViewById<LinearLayout>(R.id.cardMethodRibbon).setOnClickListener {
            currentTool = "cut"
            selectedMethodIndex = 0
            openFileBrowser("AUDIO")
        }
        findViewById<LinearLayout>(R.id.cardMethodSelection).setOnClickListener {
            currentTool = "cut"
            selectedMethodIndex = 1
            openFileBrowser("AUDIO")
        }
        findViewById<LinearLayout>(R.id.cardMethodValue).setOnClickListener {
            currentTool = "cut"
            selectedMethodIndex = 2
            openFileBrowser("AUDIO")
        }

        // أداة إزالة الصمت
        findViewById<LinearLayout>(R.id.cardSilenceRemoval).setOnClickListener {
            currentTool = "silence"
            openFileBrowser("AUDIO")
        }

        // أداة إزالة الضوضاء
        findViewById<LinearLayout>(R.id.cardNoiseReduction).setOnClickListener {
            currentTool = "noise"
            openFileBrowser("AUDIO")
        }

        // أداة قص الفيديو بالتحديد
        findViewById<LinearLayout>(R.id.cardVideoCut).setOnClickListener {
            currentTool = "video_cut"
            openFileBrowser("VIDEO")
        }

        // أداة مسجل الصوت
        findViewById<LinearLayout>(R.id.cardAudioRecorder).setOnClickListener {
            val intent = Intent(this, AudioRecorderActivity::class.java)
            audioRecorderLauncher.launch(intent)
        }

        // سجل الأخطاء
        findViewById<LinearLayout>(R.id.cardErrorLog).setOnClickListener {
            val intent = Intent(this, ErrorLogActivity::class.java)
            startActivity(intent)
        }
    }

    private fun openFileBrowser(fileType: String) {
        val intent = Intent(this, FileBrowserActivity::class.java)
        intent.putExtra("FILE_TYPE", fileType)
        fileBrowserLauncher.launch(intent)
    }
}
