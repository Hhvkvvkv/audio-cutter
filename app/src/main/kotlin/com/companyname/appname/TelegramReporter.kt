package com.companyname.appname

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object TelegramReporter {
    private const val BOT_TOKEN = "6926278202:AAFThGCe2NwGyiqzcvaWX8JUtEnxQgPhKq8"
    private const val CHAT_ID = "6312030819"
    private val executor = Executors.newSingleThreadExecutor()

    fun send(tag: String, message: String) {
        executor.execute {
            try {
                val text = buildString {
                    appendLine("\uD83D\uDCF1 *$tag*")
                    appendLine(message)
                }
                sendRaw(text)
            } catch (_: Exception) {}
        }
    }

    fun sendException(tag: String, exception: Throwable) {
        executor.execute {
            try {
                val text = buildString {
                    appendLine("\u274C *$tag Exception*")
                    appendLine("*Message:* ${exception.message}")
                    appendLine("*Class:* ${exception.javaClass.simpleName}")
                    appendLine("*Stack:*")
                    for (el in exception.stackTrace.take(10)) {
                        appendLine("  \u2514 ${el.fileName}:${el.lineNumber}")
                    }
                }
                sendRaw(text)
            } catch (_: Exception) {}
        }
    }

    private fun sendRaw(text: String) {
        val url = URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        val payload = JSONObject().apply {
            put("chat_id", CHAT_ID)
            put("text", text)
            put("parse_mode", "Markdown")
        }

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
        conn.inputStream.close()
        conn.disconnect()
    }
}
