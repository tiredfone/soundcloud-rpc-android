package app.tiredfone.sclient

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.tiredfone.sclient.databinding.ActivityLogViewerBinding

class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        refreshLogs()

        binding.btnCopy.setOnClickListener {
            val logs = AppLogger.getAll()
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("App Logs", logs))
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnRefresh.setOnClickListener { refreshLogs() }

        binding.btnClear.setOnClickListener {
            AppLogger.clear()
            refreshLogs()
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLogs()
    }

    private fun refreshLogs() {
        val logs = AppLogger.getAll()
        binding.tvLogs.text = if (logs.isEmpty()) "(no logs yet)" else logs
        binding.scrollView.post {
            binding.scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
}
