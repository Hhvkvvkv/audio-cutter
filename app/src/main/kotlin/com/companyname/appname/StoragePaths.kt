package com.companyname.appname

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

object StoragePaths {

    private fun rootOutputDir(): File {
        return Environment.getExternalStorageDirectory()
    }

    fun appRoot(context: Context): File {
        return File(rootOutputDir(), context.getString(R.string.output_root_folder))
    }

    fun toolDir(context: Context, toolFolder: String): File {
        return File(appRoot(context), toolFolder)
    }

    fun videoRoot(context: Context): File {
        return appRoot(context)
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim('.', ' ')
        return if (cleaned.isBlank()) "audio_${System.currentTimeMillis()}" else cleaned
    }

    fun saveToMediaStore(
        context: Context,
        sourceFile: File,
        fileName: String,
        format: String,
        mimeType: String,
        relativePath: String
    ): Uri? {
        val collection = if (mimeType.startsWith("video/"))
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.$format")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.SIZE, sourceFile.length())
        }

        val uri = context.contentResolver.insert(collection, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { output ->
            FileInputStream(sourceFile).use { input ->
                input.copyTo(output)
            }
        } ?: run {
            context.contentResolver.delete(uri, null, null)
            return null
        }
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        return uri
    }
}
