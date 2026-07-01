package com.companyname.appname

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ErrorLog {

    private const val MAX_LOG_LINES = 5000
    private const val LOG_FILE = "error_log.txt"

    private var logFile: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs")
        dir.mkdirs()
        logFile = File(dir, LOG_FILE)
    }

    fun log(context: Context, tag: String, message: String) {
        val file = logFile ?: File(File(context.filesDir, "logs"), LOG_FILE)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$timestamp] [$tag] $message"
        try {
            file.appendText("$line\n")
            trimLines(file)
        } catch (_: Exception) {}
    }

    fun logException(context: Context, tag: String, throwable: Throwable) {
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        log(context, tag, sw.toString())
    }

    fun readLog(context: Context): String {
        val file = logFile ?: File(File(context.filesDir, "logs"), LOG_FILE)
        return try {
            val sb = StringBuilder()
            BufferedReader(FileReader(file)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append('\n')
                }
            }
            sb.toString()
        } catch (_: Exception) {
            "لا توجد أخطاء مسجلة بعد."
        }
    }

    fun clear(context: Context) {
        logFile?.delete()
        logFile = null
        init(context)
    }

    private fun trimLines(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size > MAX_LOG_LINES) {
                file.writeText(lines.drop(lines.size - MAX_LOG_LINES).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }
}
