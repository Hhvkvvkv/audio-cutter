package com.companyname.appname

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ErrorLogActivity : AppCompatActivity() {

    private lateinit var tvLogContent: TextView
    private lateinit var tvLogStats: TextView
    private lateinit var btnClear: Button
    private lateinit var btnRefresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error_log)

        tvLogContent = findViewById(R.id.tvLogContent)
        tvLogStats = findViewById(R.id.tvLogStats)
        btnClear = findViewById(R.id.btnClearLog)
        btnRefresh = findViewById(R.id.btnRefreshLog)

        loadLog()

        btnRefresh.setOnClickListener { loadLog() }

        btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("تأكيد المسح")
                .setMessage("هل تريد مسح سجل الأخطاء بالكامل؟")
                .setPositiveButton("نعم") { _, _ ->
                    ErrorLog.clear(this)
                    loadLog()
                    Toast.makeText(this, "تم مسح السجل", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("لا", null)
                .show()
        }
    }

    private fun loadLog() {
        val logText = ErrorLog.readLog(this)
        tvLogContent.text = logText.ifBlank { "لا توجد أخطاء مسجلة بعد." }

        val lineCount = logText.count { it == '\n' }
        tvLogStats.text = "إجمالي الأسطر: $lineCount"
    }
}
