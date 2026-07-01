package com.companyname.appname

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FileBrowserActivity : AppCompatActivity() {

    private companion object {
        const val PERMISSION_REQUEST_CODE = 100
    }

    private data class MediaItem(
        val id: Long,
        val name: String,
        val subtitle: String,
        val uri: Uri,
        val displayPath: String,
        val dateAddedSeconds: Long
    )

    private var fileType: String = "ALL"
    private val isVideoMode: Boolean get() = fileType == "VIDEO"

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var titleView: TextView
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var previewPlayer: MediaPlayer? = null
    private var currentPreviewUri: String? = null
    private var mediaAdapter: MediaListAdapter? = null
    private lateinit var manageFilesLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)

        fileType = intent.getStringExtra("FILE_TYPE") ?: "AUDIO"

        manageFilesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (hasManageStorage()) startLoadingMedia()
            else showEmpty("يجب منح إذن الوصول لكل الملفات لعرضها")
        }

        listView = findViewById(R.id.listAudioFiles)
        emptyView = findViewById(R.id.tvEmptyFiles)
        titleView = findViewById(R.id.tvBrowserTitle)

        val title = if (isVideoMode) getString(R.string.file_browser_video_title) else getString(R.string.file_browser_title)
        titleView.text = title

        showEmpty("جارٍ التحقق من الصلاحيات...")
        checkAndRequestPermissions()
    }

    private fun hasManageStorage(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }

    private fun showEmpty(message: String) {
        listView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        emptyView.text = message
    }

    private fun showList() {
        emptyView.visibility = View.GONE
        listView.visibility = View.VISIBLE
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasManageStorage()) {
            showEmpty("تحتاج صلاحية الوصول لكل الملفات لعرض المحتوى")
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            manageFilesLauncher.launch(intent)
            return
        }

        val permissions = buildRequiredPermissions()
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            startLoadingMedia()
        } else {
            requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun buildRequiredPermissions(): List<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (isVideoMode) {
                perms.add("android.permission.READ_MEDIA_VIDEO")
            } else {
                perms.add("android.permission.READ_MEDIA_AUDIO")
            }
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) startLoadingMedia()
        else showEmpty("لا توجد صلاحية لقراءة الملفات.\nيرجى منح الإذن من إعدادات التطبيق.")
    }

    private fun startLoadingMedia() {
        startLoadingMediaForType(fileType)
    }

    private fun startLoadingMediaForType(type: String) {
        val msg = if (isVideoMode) "جارٍ جلب ملفات الفيديو..." else "جارٍ جلب جميع الملفات..."
        showEmpty(msg)
        executor.execute {
            val items = try { loadMediaItems() }
            catch (_: Exception) { emptyList() }

            runOnUiThread {
                if (items.isEmpty()) {
                    val emptyMsg = if (isVideoMode) getString(R.string.no_video_files_found) else "لا توجد ملفات متاحة"
                    showEmpty(emptyMsg)
                    return@runOnUiThread
                }
                val foundMsg = "تم العثور على ${items.size} ملف — الأحدث أولاً"
                titleView.text = foundMsg
                mediaAdapter = MediaListAdapter(items)
                listView.adapter = mediaAdapter
                showList()
            }
        }
    }

    private fun loadMediaItems(): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val seenUris = LinkedHashSet<String>()

        val queryTypes = if (isVideoMode) {
            listOf("video")
        } else if (fileType == "AUDIO") {
            listOf("audio")
        } else {
            listOf("audio", "video")
        }

        for (mediaType in queryTypes) {
            val isVid = mediaType == "video"
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (isVid) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                if (isVid) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val selection = "${if (isVid) MediaStore.Video.Media.IS_PENDING else MediaStore.Audio.Media.IS_PENDING} != 1"

            val projection = if (isVid) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.RELATIVE_PATH,
                        MediaStore.Video.Media.DURATION)
                } else {
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.DATA,
                        MediaStore.Video.Media.DURATION)
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATA)
                }
            }

            val sortOrder = if (isVid) "${MediaStore.Video.Media.DATE_ADDED} DESC"
            else "${MediaStore.Audio.Media.DATE_ADDED} DESC"

            contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(
                    if (isVid) MediaStore.Video.Media._ID else MediaStore.Audio.Media._ID
                )
                val nameCol = cursor.getColumnIndexOrThrow(
                    if (isVid) MediaStore.Video.Media.DISPLAY_NAME else MediaStore.Audio.Media.DISPLAY_NAME
                )
                val dateCol = cursor.getColumnIndexOrThrow(
                    if (isVid) MediaStore.Video.Media.DATE_ADDED else MediaStore.Audio.Media.DATE_ADDED
                )
                val sizeCol = cursor.getColumnIndexOrThrow(
                    if (isVid) MediaStore.Video.Media.SIZE else MediaStore.Audio.Media.SIZE
                )
                val mimeCol = cursor.getColumnIndex(
                    if (isVid) MediaStore.Video.Media.MIME_TYPE else MediaStore.Audio.Media.MIME_TYPE
                )
                val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(
                        if (isVid) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Audio.Media.RELATIVE_PATH
                    )
                } else {
                    cursor.getColumnIndex(
                        if (isVid) MediaStore.Video.Media.DATA else MediaStore.Audio.Media.DATA
                    )
                }
                val durCol = if (isVid) cursor.getColumnIndex(MediaStore.Video.Media.DURATION) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() } ?: "media_$id"
                    val dateAddedSec = cursor.getLong(dateCol)
                    val sizeBytes = cursor.getLong(sizeCol)
                    val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                    val displayPath = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else ""
                    val uri = ContentUris.withAppendedId(collection, id)
                    if (!seenUris.add(uri.toString())) continue

                    val durationMs = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    val sub = buildSubtitle(sizeBytes, dateAddedSec, displayPath, mimeType, durationMs)
                    result.add(MediaItem(id, name, sub, uri, displayPath, dateAddedSec))
                }
            }
        }

        result.sortByDescending { it.dateAddedSeconds }
        return result
    }

    private fun buildSubtitle(sizeBytes: Long, dateAddedSec: Long, displayPath: String, mimeType: String, durationMs: Long): String {
        return buildString {
            append(formatSize(sizeBytes)); append(" • ")
            append(formatDate(dateAddedSec * 1000L))
            if (durationMs > 0) {
                append(" • ")
                append(formatDuration(durationMs))
            }
            if (displayPath.isNotBlank()) {
                append(" • ")
                append(displayPath.replace("storage/emulated/0/", "الذاكرة الداخلية/"))
            }
            if (mimeType.isNotBlank()) { append(" • "); append(mimeType) }
        }
    }

    private fun formatDuration(ms: Long): String {
        val sec = ms / 1000
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    private fun formatSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = sizeBytes.toDouble(); var idx = 0
        while (size >= 1024.0 && idx < units.lastIndex) { size /= 1024.0; idx++ }
        return String.format(Locale.US, "%.1f %s", size, units[idx])
    }

    override fun onDestroy() {
        stopPreviewPlayback(notifyUI = false)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun updateButtonIconForUri(targetUri: String) {
        val adapter = mediaAdapter ?: return
        val first = listView.firstVisiblePosition
        val last = listView.lastVisiblePosition
        for (i in first..last) {
            val view = listView.getChildAt(i - first) ?: continue
            val item = adapter.getItem(i)
            if (item.uri.toString() == targetUri) {
                val btn = view.findViewById<ImageButton>(R.id.btnPreview) ?: continue
                applyPreviewIcon(btn, targetUri)
            }
        }
    }

    private fun applyPreviewIcon(btn: ImageButton, uri: String) {
        val isPlaying = currentPreviewUri == uri && previewPlayer?.isPlaying == true
        btn.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        btn.contentDescription = if (isPlaying) "إيقاف المعاينة" else "تشغيل المعاينة"
    }

    private inner class MediaListAdapter(private val items: List<MediaItem>) : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@FileBrowserActivity)
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_audio_file, parent, false)
            val item = getItem(position)
            view.findViewById<TextView>(R.id.tvAudioName).text = item.name
            view.findViewById<TextView>(R.id.tvAudioSubtitle).text = item.subtitle
            val btnPreview = view.findViewById<ImageButton>(R.id.btnPreview)

            applyPreviewIcon(btnPreview, item.uri.toString())

            view.setOnClickListener { openEditorForItem(item) }

            btnPreview.setOnClickListener {
                val prevPlayingUri = currentPreviewUri
                togglePreview(item)
                applyPreviewIcon(btnPreview, item.uri.toString())
                if (prevPlayingUri != null && prevPlayingUri != item.uri.toString()) {
                    updateButtonIconForUri(prevPlayingUri)
                }
            }
            return view
        }
    }

    private fun openEditorForItem(item: MediaItem) {
        stopPreviewPlayback(notifyUI = false)
        val kind = if (isVideoMode || item.uri.toString().contains("video")) MediaKind.VIDEO else MediaKind.AUDIO
        setResult(RESULT_OK, Intent().apply {
            putExtra("AUDIO_URI", item.uri.toString())
            putExtra("MEDIA_KIND", kind.name)
        })
        finish()
    }

    private fun togglePreview(item: MediaItem) {
        val target = item.uri.toString()
        if (currentPreviewUri == target && previewPlayer?.isPlaying == true) {
            stopPreviewPlayback(notifyUI = false)
            return
        }
        stopPreviewPlayback(notifyUI = false)
        val player = MediaPlayer()
        previewPlayer = player
        currentPreviewUri = target
        try {
            player.setDataSource(this, item.uri)
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener {
                val uri = currentPreviewUri
                stopPreviewPlayback(notifyUI = false)
                if (uri != null) updateButtonIconForUri(uri)
            }
            player.setOnErrorListener { _, _, _ ->
                val uri = currentPreviewUri
                stopPreviewPlayback(notifyUI = false)
                if (uri != null) updateButtonIconForUri(uri)
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            stopPreviewPlayback(notifyUI = false)
        }
    }

    private fun stopPreviewPlayback(notifyUI: Boolean) {
        val prevUri = currentPreviewUri
        try { previewPlayer?.stop() } catch (_: Exception) {}
        try { previewPlayer?.release() } catch (_: Exception) {}
        previewPlayer = null
        currentPreviewUri = null
        if (notifyUI && prevUri != null) updateButtonIconForUri(prevUri)
    }
}
