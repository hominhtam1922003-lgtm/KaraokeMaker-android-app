package com.example.karaokemaker.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.karaokemaker.R
import com.example.karaokemaker.data.BatchPlan
import com.example.karaokemaker.data.ProjectStore
import com.example.karaokemaker.data.TemplateStore
import com.example.karaokemaker.download.Youtube
import com.example.karaokemaker.service.JobService
import com.example.karaokemaker.service.Pipeline
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Màn THÊM BÀI (bước 1): dán link YouTube hoặc chọn file trong máy (SAF).
 * Tạo dự án + giao job "pipeline" tự hành cho JobService rồi mở Studio —
 * đúng tinh thần bản PC: người dùng không phải ngồi canh từng chặng.
 *
 * HÀNG LOẠT (v1.1.0): dán nhiều link (mỗi dòng 1 link) hoặc chọn nhiều
 * file -> tạo 1 ĐỢT = N dự án + N job pipeline chung batchId, hàng đợi
 * tuần tự của JobService tự chạy lần lượt; tiến độ cả đợt xem ở Thư viện.
 * Luồng đợt KHÔNG fetchTitle ở UI (chặn giao diện + chậm): dự án mang
 * tiêu đề tạm, Pipeline.ensureSongWav tự điền tiêu đề thật từ metadata
 * sau khi tải xong bài.
 */
class AddSongActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var etBatchLinks: EditText
    private lateinit var rgSep: RadioGroup
    private lateinit var cbAutoRender: CheckBox
    private lateinit var cbCleanup: CheckBox

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onBatchFilesPicked(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_song)
        etUrl = findViewById(R.id.etUrl)
        etBatchLinks = findViewById(R.id.etBatchLinks)
        rgSep = findViewById(R.id.rgSep)
        cbAutoRender = findViewById(R.id.cbAutoRender)
        cbCleanup = findViewById(R.id.cbCleanup)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnFromLink).setOnClickListener { onLinkSubmit() }
        findViewById<Button>(R.id.btnPickFile).setOnClickListener {
            pickFile.launch(arrayOf("audio/*", "video/*"))
        }
        findViewById<Button>(R.id.btnBatchLinks).setOnClickListener { onBatchLinksSubmit() }
        findViewById<Button>(R.id.btnPickFiles).setOnClickListener {
            pickFiles.launch(arrayOf("audio/*", "video/*"))
        }
    }

    private fun sepMode(): String =
        if (rgSep.checkedRadioButtonId == R.id.rbAi) "ai" else "fast"

    // ---------------- luồng đơn (giữ nguyên từ v1) ----------------

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
            var title = Pipeline.YOUTUBE_PLACEHOLDER_TITLE
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
        takeReadPermission(uri)
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

    // ---------------- luồng hàng loạt (v1.1.0) ----------------

    private fun onBatchLinksSubmit() {
        val links = BatchPlan.parseLinks(etBatchLinks.text.toString())
        if (links.isEmpty()) {
            toast("Chưa thấy link YouTube nào trong ô — mỗi dòng dán 1 link")
            return
        }
        BatchPlan.countError(links.size)?.let { toast(it); return }
        if (!checkStorage(links.size)) return
        val items = links.map { BatchItem("youtube", it, Pipeline.YOUTUBE_PLACEHOLDER_TITLE) }
        createBatch(items)
    }

    private fun onBatchFilesPicked(uris: List<Uri>) {
        BatchPlan.countError(uris.size)?.let { toast(it); return }
        if (!checkStorage(uris.size)) return
        val items = uris.map { uri ->
            takeReadPermission(uri)
            BatchItem("file", uri.toString(), queryDisplayName(uri) ?: "Bài từ file")
        }
        createBatch(items)
    }

    private data class BatchItem(val sourceType: String, val sourceRef: String, val title: String)

    /**
     * Tạo cả đợt: N dự án (kế thừa mẫu phong cách như dự án thường) + N job
     * pipeline dùng chung 1 batchId; payload từng job tự chứa cấu hình của
     * đợt (cách tách / tự xuất / dọn trung gian) nên chết app mở lại vẫn
     * chạy tiếp đúng ý user. Xong thì quay về Thư viện xem tiến độ đợt.
     */
    private fun createBatch(items: List<BatchItem>) {
        val batchId = UUID.randomUUID().toString().take(12)
        for (item in items) {
            val p = ProjectStore.create(item.title, item.sourceType, item.sourceRef)
            TemplateStore.applyTo(this, p)
            ProjectStore.save(p)
            val payload = JSONObject()
                .put("sepMode", sepMode())
                .put("autoRender", cbAutoRender.isChecked)
                .put("cleanup", cbCleanup.isChecked)
            JobService.enqueue(this, p.id, "pipeline", payload.toString(), batchId)
        }
        toast("Đã tạo đợt ${items.size} bài — app làm lần lượt ở nền, xem tiến độ ở Thư viện")
        finish()
    }

    /**
     * Pre-flight dung lượng: ước [BatchPlan.BYTES_PER_ITEM]/bài; thiếu thì
     * báo rõ còn thiếu khoảng bao nhiêu và KHÔNG tạo đợt (tránh chết giữa
     * chừng vì đầy máy như các bản trước).
     */
    private fun checkStorage(count: Int): Boolean {
        val available = try {
            StatFs(filesDir.absolutePath).availableBytes
        } catch (e: Exception) {
            return true // không đọc được số thì không chặn — pipeline tự báo lỗi nếu đầy thật
        }
        val shortage = BatchPlan.shortageBytes(available, count)
        if (shortage > 0) {
            toast(
                "Không đủ bộ nhớ cho đợt $count bài: còn thiếu khoảng " +
                    "${BatchPlan.fmtSize(shortage)} (mỗi bài cần ~${BatchPlan.fmtSize(BatchPlan.BYTES_PER_ITEM)}). " +
                    "Xoá bớt dữ liệu trong máy rồi thử lại."
            )
            return false
        }
        return true
    }

    // ---------------- phụ trợ ----------------

    private fun takeReadPermission(uri: Uri) {
        // Xin quyền đọc bền để lần sau mở lại dự án vẫn đọc được file gốc
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // một số nguồn không cho quyền bền — pipeline sẽ chép file vào
            // thư mục dự án ngay ở chặng nạp bài nên vẫn ổn
        }
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
