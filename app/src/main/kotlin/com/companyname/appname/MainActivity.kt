package com.companyname.appname

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 1001
    private lateinit var audioBrowserLauncher: ActivityResultLauncher<Intent>
    private lateinit var manageFilesPermissionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioBrowserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uriText = result.data?.getStringExtra("AUDIO_URI")
                val audioPath = result.data?.getStringExtra("AUDIO_PATH")
                val uri = if (!uriText.isNullOrBlank()) Uri.parse(uriText) else null
                if (uri != null || !audioPath.isNullOrBlank()) {
                    val intent = Intent(this, CutMethodActivity::class.java)
                    if (uri != null) {
                        intent.putExtra("AUDIO_URI", uri)
                    }
                    intent.putExtra("AUDIO_PATH", audioPath)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
            }
        }

        manageFilesPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (hasBroadFileAccess() || checkStoragePermission()) {
                openAudioPicker()
            } else {
                Toast.makeText(this, "يجب منح إذن الوصول لكل الملفات لعرض كل الصوتيات", Toast.LENGTH_LONG).show()
            }
        }

        val btnImport = findViewById<Button>(R.id.btnImport)

        btnImport.setOnClickListener {
            if (hasBroadFileAccess() || checkStoragePermission()) {
                openAudioPicker()
            } else {
                requestBestAvailablePermission()
            }
        }
    }

    private fun hasBroadFileAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, "android.permission.READ_MEDIA_AUDIO") == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBestAvailablePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestManageAllFilesPermission()
        } else {
            requestStoragePermission()
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.READ_MEDIA_AUDIO"), PERMISSION_REQUEST_CODE)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
        }
    }

    private fun requestManageAllFilesPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        manageFilesPermissionLauncher.launch(intent)
    }

    private fun openAudioPicker() {
        audioBrowserLauncher.launch(Intent(this, FileBrowserActivity::class.java).apply {
            putExtra("FILE_TYPE", "AUDIO")
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openAudioPicker()
            } else {
                Toast.makeText(this, "عذراً، يجب منح إذن الوصول للملفات لاستخدام التطبيق", Toast.LENGTH_LONG).show()
            }
        }
    }
}
