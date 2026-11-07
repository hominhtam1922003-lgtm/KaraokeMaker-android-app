package com.example.karaokemaker.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.karaokemaker.R
import com.example.karaokemaker.core.Gradients
import com.example.karaokemaker.data.Job
import com.example.karaokemaker.data.JobBanner
import com.example.karaokemaker.data.JobStore
import com.example.karaokemaker.data.Project
import com.example.karaokemaker.data.ProjectStore
import com.example.karaokemaker.data.TemplateStore
import com.example.karaokemaker.download.ModelManager
import com.example.karaokemaker.service.JobService
import org.json.JSONObject

/**
 * Màn THƯ VIỆN (khung KaraStudio ảnh 1): thẻ dự án với chip trạng thái
 * (Đã xuất / Đang sửa lời / Chưa đồng bộ / Bản nháp) + chip Beat + chip Lời,
 * mục bài hát trong máy, thẻ tải model AI, banner job đang chạy.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rvProjects: RecyclerView
    private lateinit var rvMedia: RecyclerView
    private lateinit var tvEmptyProjects: TextView
    private lateinit var tvProjectCount: TextView
    private lateinit var tvWhisperModel: TextView
    private lateinit var tvMdxModel: TextView
    private lateinit var cardJob: LinearLayout
    private lateinit var tvJobText: TextView
    private lateinit var pbJob: ProgressBar
    private lateinit var cardJobError: LinearLayout
    private lateinit var tvJobErrorTitle: TextView
    private lateinit var tvJobErrorText: TextView

    /** Job lỗi đang hiện trên thẻ lỗi — nút CHẠY TIẾP resume đúng job này. */
    private var failedJobId: String? = null

    private val projectsAdapter = ProjectsAdapter()
    private val mediaAdapter = MediaAdapter()
    private val storeListener: () -> Unit = { runOnUiThread { refreshJobs() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rvProjects = findViewById(R.id.rvProjects)
        rvMedia = findViewById(R.id.rvMedia)
        tvEmptyProjects = findViewById(R.id.tvEmptyProjects)
        tvProjectCount = findViewById(R.id.tvProjectCount)
        tvWhisperModel = findViewById(R.id.tvWhisperModel)
        tvMdxModel = findViewById(R.id.tvMdxModel)
        cardJob = findViewById(R.id.cardJob)
        tvJobText = findViewById(R.id.tvJobText)
        pbJob = findViewById(R.id.pbJob)
        cardJobError = findViewById(R.id.cardJobError)
        tvJobErrorTitle = findViewById(R.id.tvJobErrorTitle)
        tvJobErrorText = findViewById(R.id.tvJobErrorText)

        rvProjects.layoutManager = LinearLayoutManager(this)
        rvProjects.adapter = projectsAdapter
        rvMedia.layoutManager = LinearLayoutManager(this)
        rvMedia.adapter = mediaAdapter

        findViewById<Button>(R.id.btnCreate).setOnClickListener {
            startActivity(Intent(this, AddSongActivity::class.java))
        }
        findViewById<Button>(R.id.btnImport).setOnClickListener {
            startActivity(Intent(this, AddSongActivity::class.java))
        }
        findViewById<Button>(R.id.btnDlWhisper).setOnClickListener {
            if (ModelManager.whisperReady(this)) {
                toast("Model chép lời đã có sẵn trên máy")
            } else {
                JobService.enqueue(this, "", "model", JSONObject().put("kind", "whisper").toString())
                toast("Đang tải model chép lời ở nền (~376 MB)")
            }
        }
        findViewById<Button>(R.id.btnDlMdx).setOnClickListener {
            if (ModelManager.mdxReady(this)) {
                toast("Model tách beat đã có sẵn trên máy")
            } else {
                JobService.enqueue(this, "", "model", JSONObject().put("kind", "mdx").toString())
                toast("Đang tải model tách beat ở nền (~67 MB)")
            }
        }
        findViewById<Button>(R.id.btnScanMedia).setOnClickListener { scanMedia() }
        findViewById<Button>(R.id.btnJobCancel).setOnClickListener {
            JobStore.all().firstOrNull { it.isActive() }?.let { JobService.cancel(this, it.id) }
        }
        findViewById<Button>(R.id.btnJobResume).setOnClickListener {
            failedJobId?.let { id ->
                JobService.resume(this, id)
                toast("Đang chạy tiếp — phần nào xong rồi sẽ không làm lại")
            }
        }
        requestNotificationPermission()
        JobStore.addListener(storeListener)
    }

    override fun onDestroy() {
        JobStore.removeListener(storeListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        ProjectStore.reload()
        refreshProjects()
        refreshModels()
        refreshJobs()
        if (mediaAdapter.itemCount == 0) scanMediaIfPermitted()
    }

    // ---------------- dự án ----------------

    private fun refreshProjects() {
        val list = ProjectStore.all()
        projectsAdapter.submit(list)
        tvEmptyProjects.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        tvProjectCount.text = if (list.isEmpty()) "" else "${list.size} dự án"
    }

    inner class ProjectsAdapter : RecyclerView.Adapter<ProjectsAdapter.VH>() {
        private var items: List<Project> = emptyList()

        fun submit(list: List<Project>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_project, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val previewBox: View = v.findViewById(R.id.previewBox)
            private val tvPreviewTitle: TextView = v.findViewById(R.id.tvPreviewTitle)
            private val tvRatio: TextView = v.findViewById(R.id.tvRatio)
            private val chipStatus: TextView = v.findViewById(R.id.chipStatus)
            private val tvDuration: TextView = v.findViewById(R.id.tvDuration)
            private val chipBeat: TextView = v.findViewById(R.id.chipBeat)
            private val chipLyrics: TextView = v.findViewById(R.id.chipLyrics)
            private val tvJobLabel: TextView = v.findViewById(R.id.tvJobLabel)
            private val btnDelete: Button = v.findViewById(R.id.btnDelete)

            fun bind(p: Project) {
                tvPreviewTitle.text = p.title
                tvRatio.text = p.style.ratio
                tvDuration.text = fmtDuration(p.durationSec)
                // nền thẻ = gradient của dự án (mẫu nền đang chọn)
                val cols = Gradients.colorsOf(p.bg.value)
                    .map { Color.parseColor(it.replace("0x", "#")) }
                    .toIntArray()
                val grad = ShapeDrawable(RectShape())
                grad.paint.shader = LinearGradient(
                    0f, 0f, 600f, 200f, cols, floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                previewBox.background = grad

                setChip(chipStatus, "● " + p.statusLabel(), statusKind(p))
                setChip(
                    chipBeat,
                    when {
                        p.beatReady && p.beatMode == "ai" -> "Đã tách ✓ (AI)"
                        p.beatReady -> "Đã tách ✓"
                        else -> "Chưa tách"
                    },
                    if (p.beatReady) "green" else "gray"
                )
                setChip(
                    chipLyrics,
                    when (p.lyricsState) {
                        "synced" -> "Đã đồng bộ"
                        "editing" -> "Đang sửa"
                        else -> "Chưa có"
                    },
                    when (p.lyricsState) {
                        "synced" -> "green"
                        "editing" -> "orange"
                        else -> "gray"
                    }
                )
                // Dòng tóm tắt phản ánh job MỚI NHẤT của dự án (JobBanner
                // chọn hộ): đang chạy -> tiến độ; lỗi -> "bị lỗi: <error>"
                // đỏ; gián đoạn -> "bị gián đoạn" đỏ; xong/huỷ -> ẩn.
                val lineJob = JobBanner.projectLineJob(JobStore.forProject(p.id))
                when {
                    lineJob == null -> tvJobLabel.visibility = View.GONE
                    lineJob.isActive() -> {
                        tvJobLabel.visibility = View.VISIBLE
                        tvJobLabel.setTextColor(getColor(R.color.warn_orange))
                        tvJobLabel.text = "${lineJob.typeLabel()}: ${lineJob.label} — ${lineJob.progress.toInt()}%"
                    }
                    lineJob.status == "error" -> {
                        tvJobLabel.visibility = View.VISIBLE
                        tvJobLabel.setTextColor(getColor(R.color.error_red))
                        tvJobLabel.text = "${lineJob.typeLabel()} bị lỗi: ${lineJob.error ?: "không rõ nguyên nhân"}"
                    }
                    else -> { // interrupted
                        tvJobLabel.visibility = View.VISIBLE
                        tvJobLabel.setTextColor(getColor(R.color.error_red))
                        tvJobLabel.text = "${lineJob.typeLabel()} bị gián đoạn"
                    }
                }
                itemView.setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, StudioActivity::class.java)
                            .putExtra(StudioActivity.EXTRA_PROJECT_ID, p.id)
                    )
                }
                btnDelete.setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Xoá dự án?")
                        .setMessage("Xoá “${p.title}” cùng toàn bộ file làm việc của dự án? Video đã xuất vào Movies/KaraokeMaker vẫn giữ nguyên.")
                        .setPositiveButton("Xoá") { _, _ ->
                            JobStore.forProject(p.id).filter { it.isActive() }
                                .forEach { JobService.cancel(this@MainActivity, it.id) }
                            ProjectStore.delete(p.id)
                            refreshProjects()
                        }
                        .setNegativeButton("Huỷ", null)
                        .show()
                }
            }

            private fun statusKind(p: Project): String = when {
                p.exportedUri != null -> "green"
                p.lyricsState == "editing" -> "orange"
                p.lyricsState == "none" && p.beatReady -> "orange"
                else -> "gray"
            }

            private fun setChip(tv: TextView, text: String, kind: String) {
                tv.text = text
                when (kind) {
                    "green" -> {
                        tv.setBackgroundResource(R.drawable.bg_chip_green)
                        tv.setTextColor(getColor(R.color.ok_green))
                    }
                    "orange" -> {
                        tv.setBackgroundResource(R.drawable.bg_chip_orange)
                        tv.setTextColor(getColor(R.color.warn_orange))
                    }
                    else -> {
                        tv.setBackgroundResource(R.drawable.bg_chip_gray)
                        tv.setTextColor(getColor(R.color.idle_gray))
                    }
                }
            }
        }
    }

    // ---------------- bài hát trong máy ----------------

    data class MediaSong(val uri: Uri, val title: String, val artist: String, val durationSec: Double)

    inner class MediaAdapter : RecyclerView.Adapter<MediaAdapter.VH>() {
        private var items: List<MediaSong> = emptyList()

        fun submit(list: List<MediaSong>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvTitle: TextView = v.findViewById(R.id.tvMediaTitle)
            private val tvSub: TextView = v.findViewById(R.id.tvMediaSub)
            private val tvDur: TextView = v.findViewById(R.id.tvMediaDuration)

            fun bind(s: MediaSong) {
                tvTitle.text = s.title
                tvSub.text = s.artist
                tvDur.text = fmtDuration(s.durationSec)
                itemView.setOnClickListener {
                    val p = ProjectStore.create(s.title, "mediastore", s.uri.toString())
                    TemplateStore.applyTo(this@MainActivity, p)
                    ProjectStore.save(p)
                    JobService.enqueue(this@MainActivity, p.id, "download")
                    toast("Đã tạo dự án — đang nạp bài ở nền")
                    startActivity(
                        Intent(this@MainActivity, StudioActivity::class.java)
                            .putExtra(StudioActivity.EXTRA_PROJECT_ID, p.id)
                    )
                }
            }
        }
    }

    private fun scanMediaIfPermitted() {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            scanMedia()
        }
    }

    private fun scanMedia() {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), REQ_MEDIA)
            return
        }
        Thread {
            val songs = ArrayList<MediaSong>()
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
            )
            val cursor: Cursor? = contentResolver.query(
                uri, projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )
            cursor?.use { c ->
                val idI = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val tI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val aI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val dI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext() && songs.size < 100) {
                    val dur = c.getLong(dI) / 1000.0
                    if (dur < 30) continue // bỏ nhạc chuông / đoạn ngắn
                    songs.add(
                        MediaSong(
                            Uri.withAppendedPath(uri, c.getLong(idI).toString()),
                            c.getString(tI) ?: "Bài không tên",
                            c.getString(aI) ?: "",
                            dur
                        )
                    )
                }
            }
            runOnUiThread { mediaAdapter.submit(songs) }
        }.start()
    }

    // ---------------- model + job banner ----------------

    private fun refreshModels() {
        tvWhisperModel.text = "Chép lời (Whisper small · ~376 MB): " +
            if (ModelManager.whisperReady(this)) "✔ đã tải" else "chưa tải"
        tvMdxModel.text = "Tách beat AI (MDX · ~67 MB): " +
            if (ModelManager.mdxReady(this)) "✔ đã tải" else "chưa tải"
    }

    private fun refreshJobs() {
        when (val state = JobBanner.pick(JobStore.all())) {
            is JobBanner.State.Active -> {
                val active = state.job
                failedJobId = null
                cardJobError.visibility = View.GONE
                cardJob.visibility = View.VISIBLE
                val title = ProjectStore.get(active.projectId)?.title
                tvJobText.text = buildString {
                    append(active.typeLabel())
                    if (!title.isNullOrBlank()) append(" · ").append(title)
                    append(" — ").append(active.label)
                    if (active.status == "queued") append(" (đang chờ)")
                }
                pbJob.progress = active.progress.toInt()
            }
            is JobBanner.State.Failed -> {
                // v1.0.1: job lỗi PHẢI hiện ngay ở Thư viện kèm nội dung lỗi
                // đầy đủ + nút Chạy tiếp — không để user đợi oan như v1.0.0.
                val failed = state.job
                failedJobId = failed.id
                cardJob.visibility = View.GONE
                cardJobError.visibility = View.VISIBLE
                val title = ProjectStore.get(failed.projectId)?.title
                tvJobErrorTitle.text = buildString {
                    append("⚠ ")
                    append(failed.typeLabel())
                    if (!title.isNullOrBlank()) append(" · ").append(title)
                    append(if (failed.status == "interrupted") " — bị gián đoạn" else " — bị lỗi")
                }
                tvJobErrorText.text = failed.error?.takeIf { it.isNotBlank() }
                    ?: "Đã xảy ra lỗi không rõ nguyên nhân. Bấm Chạy tiếp để thử lại."
                refreshProjects()
                refreshModels()
            }
            JobBanner.State.None -> {
                failedJobId = null
                cardJob.visibility = View.GONE
                cardJobError.visibility = View.GONE
                refreshProjects()
                refreshModels()
            }
        }
    }

    // ---------------- phụ trợ ----------------

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MEDIA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            scanMedia()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun fmtDuration(sec: Double): String {
        if (sec <= 0) return "—"
        val s = sec.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val REQ_MEDIA = 41
        private const val REQ_NOTIF = 42
    }
}
