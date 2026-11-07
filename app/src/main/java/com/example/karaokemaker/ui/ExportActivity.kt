package com.example.karaokemaker.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.karaokemaker.R
import com.example.karaokemaker.data.Job
import com.example.karaokemaker.data.JobStore
import com.example.karaokemaker.data.Project
import com.example.karaokemaker.data.ProjectStore
import com.example.karaokemaker.data.TemplateStore
import com.example.karaokemaker.service.JobService

/**
 * Màn XUẤT (khung KaraStudio ảnh 4): xem trước + thẻ thông tin bài, thẻ
 * preset định dạng (YouTube 16:9 / Shorts 9:16 / Facebook 1:1 / chất lượng
 * 720p–1080p), nút thêm vào hàng đợi, và HÀNG ĐỢI XUẤT chạy lần lượt —
 * xong bài nào mở xem/chia sẻ ngay bài đó.
 * (Preset 4K trong ảnh mẫu không làm: điện thoại encode 4K bằng MediaCodec
 * không ổn định — thay bằng thẻ chất lượng 720p/1080p.)
 */
class ExportActivity : AppCompatActivity() {

    private lateinit var project: Project
    private lateinit var preview: KaraokePreviewView
    private val queueAdapter = QueueAdapter()
    private val storeListener: () -> Unit = { runOnUiThread { refreshQueue(); refreshInfo() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)
        val pid = intent.getStringExtra(EXTRA_PROJECT_ID) ?: ""
        val p = ProjectStore.get(pid)
        if (p == null) {
            toast("Không tìm thấy dự án")
            finish()
            return
        }
        project = p
        preview = findViewById(R.id.previewExport)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSaveTemplate).setOnClickListener {
            TemplateStore.save(this, project.style, project.bg)
            toast("Đã lưu cấu hình này làm mẫu cho dự án mới")
        }
        findViewById<View>(R.id.preset169).setOnClickListener { setRatio("16:9") }
        findViewById<View>(R.id.preset916).setOnClickListener { setRatio("9:16") }
        findViewById<View>(R.id.preset11).setOnClickListener { setRatio("1:1") }
        findViewById<View>(R.id.presetQuality).setOnClickListener {
            project.style.quality = if (project.style.quality == "1080p") "720p" else "1080p"
            ProjectStore.save(project)
            refreshInfo()
            toast("Chất lượng xuất: ${project.style.quality}")
        }
        findViewById<Button>(R.id.btnRenderNow).setOnClickListener { enqueueRender() }

        val rv = findViewById<RecyclerView>(R.id.rvQueue)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = queueAdapter

        refreshInfo()
        refreshQueue()
        JobStore.addListener(storeListener)
    }

    override fun onDestroy() {
        JobStore.removeListener(storeListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        ProjectStore.get(project.id)?.let { project = it }
        refreshInfo()
        refreshQueue()
    }

    private fun setRatio(ratio: String) {
        project.style.ratio = ratio
        ProjectStore.save(project)
        refreshInfo()
    }

    private fun refreshInfo() {
        val lines = ProjectStore.loadLines(project.id)
        findViewById<TextView>(R.id.tvExportSub).text = "· ${project.title}"
        findViewById<TextView>(R.id.tvInfoDuration).text = fmtTime(project.durationSec)
        findViewById<TextView>(R.id.tvInfoBeat).text = when (project.beatMode) {
            "ai" -> "Beat đã tách (AI)"
            "fast" -> "Beat đã tách (nhanh)"
            else -> "Chưa tách beat"
        }
        val matched = lines.count { it.matched }
        findViewById<TextView>(R.id.tvInfoLines).text =
            if (lines.isEmpty()) "Chưa có lời" else "${lines.size} dòng · $matched khớp"
        findViewById<TextView>(R.id.tvFileName).text =
            "${project.title} — Karaoke.mp4"
        val (w169, h169) = dimsFor("16:9")
        val (w916, h916) = dimsFor("9:16")
        val (w11, h11) = dimsFor("1:1")
        findViewById<TextView>(R.id.tvPreset169Sub).text = "$w169 × $h169 · ngang"
        findViewById<TextView>(R.id.tvPreset916Sub).text = "$w916 × $h916 · dọc"
        findViewById<TextView>(R.id.tvPreset11Sub).text = "$w11 × $h11 · vuông"
        findViewById<TextView>(R.id.tvQualityTitle).text =
            "Chất lượng · ${project.style.quality}"
        findViewById<View>(R.id.preset169).setBackgroundResource(
            if (project.style.ratio == "16:9") R.drawable.bg_btn_selected else R.drawable.bg_card
        )
        findViewById<View>(R.id.preset916).setBackgroundResource(
            if (project.style.ratio == "9:16") R.drawable.bg_btn_selected else R.drawable.bg_card
        )
        findViewById<View>(R.id.preset11).setBackgroundResource(
            if (project.style.ratio == "1:1") R.drawable.bg_btn_selected else R.drawable.bg_card
        )
        // Xem trước tĩnh: đặt vị trí ở giữa câu đầu cho thấy chữ đã hát một phần
        preview.style = project.style
        preview.ratioLabel = project.style.ratio
        when (project.bg.type) {
            "gradient" -> preview.bgKind = project.bg.value
            "color" -> {
                preview.bgKind = "color"
                preview.bgColorHex = project.bg.value
            }
            "image" -> preview.setBgImageFile(java.io.File(project.bg.value))
            else -> preview.bgKind = "demtim"
        }
        preview.lines = lines
        preview.durationSec = project.durationSec
        preview.positionSec = lines.firstOrNull()?.let { it.start + (it.end - it.start) * 0.45 } ?: 0.0
        preview.invalidate()
    }

    private fun dimsFor(ratio: String): Pair<Int, Int> {
        val q = project.style.quality
        return when (ratio) {
            "9:16" -> if (q == "1080p") (1080 to 1920) else (720 to 1280)
            "1:1" -> if (q == "1080p") (1080 to 1080) else (720 to 720)
            else -> if (q == "1080p") (1920 to 1080) else (1280 to 720)
        }
    }

    private fun enqueueRender() {
        val lines = ProjectStore.loadLines(project.id)
        if (lines.isEmpty()) {
            toast("Chưa có lời — vào Studio chép lời/căn nhịp trước")
            return
        }
        if (!project.beatReady) {
            toast("Chưa có beat — vào Studio tách beat trước")
            return
        }
        val active = JobStore.forProject(project.id).firstOrNull {
            it.isActive() && (it.type == "render" || it.type == "pipeline")
        }
        if (active != null) {
            toast("Bài này đang ở trong hàng đợi xuất rồi")
            return
        }
        JobService.enqueue(this, project.id, "render")
        toast("Đã thêm vào hàng đợi xuất — chạy ở nền, xong sẽ có thông báo")
        refreshQueue()
    }

    private fun refreshQueue() {
        val jobs = JobStore.all().filter { it.type == "render" || it.type == "pipeline" }
        queueAdapter.submit(jobs)
    }

    inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.VH>() {
        private var items: List<Job> = emptyList()

        fun submit(list: List<Job>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_queue, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvTitle: TextView = v.findViewById(R.id.tvQTitle)
            private val tvPct: TextView = v.findViewById(R.id.tvQPct)
            private val tvSub: TextView = v.findViewById(R.id.tvQSub)
            private val pb: ProgressBar = v.findViewById(R.id.pbQ)
            private val btnOpen: Button = v.findViewById(R.id.btnQOpen)
            private val btnShare: Button = v.findViewById(R.id.btnQShare)
            private val btnResume: Button = v.findViewById(R.id.btnQResume)
            private val btnCancel: Button = v.findViewById(R.id.btnQCancel)

            fun bind(job: Job) {
                val proj = ProjectStore.get(job.projectId)
                tvTitle.text = proj?.title ?: job.typeLabel()
                pb.progress = job.progress.toInt()
                when (job.status) {
                    "running" -> {
                        tvPct.text = "${job.progress.toInt()}%"
                        tvPct.setTextColor(getColor(R.color.accent_pink))
                        tvSub.text = "Đang ${job.typeLabel().lowercase()} · ${job.label}"
                    }
                    "queued" -> {
                        tvPct.text = "Chờ"
                        tvPct.setTextColor(getColor(R.color.text_sub))
                        tvSub.text = "Đang chờ tới lượt"
                    }
                    "done" -> {
                        tvPct.text = "✔ Xong"
                        tvPct.setTextColor(getColor(R.color.ok_green))
                        tvSub.text = "Đã lưu · Movies/KaraokeMaker" +
                            (proj?.exportedName?.let { " · $it" } ?: "")
                    }
                    "error" -> {
                        tvPct.text = "Lỗi"
                        tvPct.setTextColor(getColor(R.color.error_red))
                        tvSub.text = job.error ?: "Đã xảy ra lỗi"
                    }
                    "interrupted" -> {
                        tvPct.text = "Gián đoạn"
                        tvPct.setTextColor(getColor(R.color.warn_orange))
                        tvSub.text = "Bị gián đoạn — bấm Chạy tiếp"
                    }
                    "canceled" -> {
                        tvPct.text = "Đã huỷ"
                        tvPct.setTextColor(getColor(R.color.text_sub))
                        tvSub.text = "Người dùng đã huỷ"
                    }
                    else -> tvSub.text = job.status
                }
                val exported = proj?.exportedUri
                btnOpen.visibility =
                    if (job.status == "done" && exported != null) View.VISIBLE else View.GONE
                btnShare.visibility = btnOpen.visibility
                btnResume.visibility =
                    if (job.status == "error" || job.status == "interrupted") View.VISIBLE else View.GONE
                btnCancel.visibility = if (job.isActive()) View.VISIBLE else View.GONE

                btnOpen.setOnClickListener {
                    if (exported != null) openVideo(exported)
                }
                btnShare.setOnClickListener {
                    if (exported != null) shareVideo(exported, proj?.title ?: "Karaoke")
                }
                btnResume.setOnClickListener { JobService.resume(this@ExportActivity, job.id) }
                btnCancel.setOnClickListener { JobService.cancel(this@ExportActivity, job.id) }
            }
        }
    }

    private fun openVideo(uriStr: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uriStr), "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            toast("Không mở được video: ${e.message}")
        }
    }

    private fun shareVideo(uriStr: String, title: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, Uri.parse(uriStr))
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Chia sẻ video karaoke"))
        } catch (e: Exception) {
            toast("Không chia sẻ được: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun fmtTime(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
    }
}
