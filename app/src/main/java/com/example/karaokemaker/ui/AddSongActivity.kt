package com.example.karaokemaker.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.karaokemaker.R
import com.example.karaokemaker.data.ProjectStore
import com.example.karaokemaker.data.TemplateStore
import com.example.karaokemaker.download.Youtube
import com.example.karaokemaker.service.JobService
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Màn THÊM BÀI (bước 1): dán link YouTube hoặc chọn file trong máy (SAF).
 * Tạo dự án + giao job "pipeline" tự hành cho JobService rồi mở Studio —
 * đúng tinh thần bản PC: người dùng không phải ngồi canh từng chặng.
 */
class AddSongActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var rgSep: RadioGroup
    private lateinit var cbAutoRender: CheckBox

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_song)
        etUrl = findViewById(R.id.etUrl)
        rgSep = findViewById(R.id.rgSep)
        cbAutoRender = findViewById(R.id.cbAutoRender)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnFromLink).setOnClickListener { onLinkSubmit() }
        findViewById<Button>(R.id.btnPickFile).setOnClickListener {
            pickFile.launch(arrayOf("audio/*", "video/*"))
        }
    }

    private fun sepMode(): String =
        if (rgSep.checkedRadioButtonId == R.id.rbAi) "ai" else "fast"

    private fun onLinkSubmit() {
        val url = etUrl.text.toString().trim()
        if (url.isEmpty() || !url.contains("youtu")) {
            toast("Hãy dán link YouTube hợp lệ")
            return
        }
        val btn = findViewById<Button>(R.id.btnFromLink)
        btn.isEnabled = false
        btn.text = "Đang đọc link..."
        // Lấy tiêu đề trước (cũng là kiểm tra link sống) ở thread nền
        thread {
            var title = "Bài từ YouTube"
            var err: String? = null
            try {
                title = Youtube.fetchTitle(this@AddSongActivity, url)
            } catch (e: Exception) {
                err = e.message
            }
            runOnUiThread {
                btn.isEnabled = true
                btn.text = "Tự làm từ link này"
                if (err != null) {
                    toast(err)
                } else {
                    val p = ProjectStore.create(title, "youtube", url)
                    TemplateStore.applyTo(this@AddSongActivity, p)
                    ProjectStore.save(p)
                    val payload = JSONObject()
                        .put("sepMode", sepMode())
                        .put("autoRender", cbAutoRender.isChecked)
                    JobService.enqueue(this@AddSongActivity, p.id, "pipeline", payload.toString())
                    openStudio(p.id)
                }
            }
        }
    }

    private fun onFilePicked(uri: Uri) {
        // Xin quyền đọc bền để lần sau mở lại dự án vẫn đọc được file gốc
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // một số nguồn không cho quyền bền — pipeline sẽ chép file vào
            // thư mục dự án ngay ở chặng nạp bài nên vẫn ổn
        }
        val name = queryDisplayName(uri) ?: "Bài từ file"
        val p = ProjectStore.create(name, "file", uri.toString())
        TemplateStore.applyTo(this, p)
        ProjectStore.save(p)
        val payload = JSONObject()
            .put("sepMode", sepMode())
            .put("autoRender", cbAutoRender.isChecked)
        JobService.enqueue(this, p.id, "pipeline", payload.toString())
        openStudio(p.id)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.substringBeforeLast('.')
                    } else {
                        null
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    private fun openStudio(projectId: String) {
        startActivity(
            Intent(this, StudioActivity::class.java)
                .putExtra(StudioActivity.EXTRA_PROJECT_ID, projectId)
        )
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
