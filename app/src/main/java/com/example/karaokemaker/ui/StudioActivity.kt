package com.example.karaokemaker.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.karaokemaker.R
import com.example.karaokemaker.audio.AudioUtil
import com.example.karaokemaker.core.Gradients
import com.example.karaokemaker.core.Lyrics
import com.example.karaokemaker.core.StyleOptions
import com.example.karaokemaker.core.StylePresets
import com.example.karaokemaker.core.TimedLine
import com.example.karaokemaker.core.WordTime
import com.example.karaokemaker.data.JobStore
import com.example.karaokemaker.data.Project
import com.example.karaokemaker.data.ProjectStore
import com.example.karaokemaker.download.ModelManager
import com.example.karaokemaker.service.JobService
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Màn STUDIO (khung KaraStudio ảnh 2, thu gọn cho điện thoại dọc):
 *  - Xem trước trực tiếp (nền gradient + chữ 2 dòng tô màu theo tiến độ phát)
 *  - Thanh phát + waveform + nút GẮN NHỊP lớn (= phím Space của bản PC)
 *  - Tab Dòng lời: danh sách câu (số thứ tự, mốc giờ, ±0,1s, sửa, giọng
 *    Nữ/Nam, nhãn "Chưa khớp"), nút tách beat / chép lời / dán lời
 *  - Tab Phong cách: panel chữ (font/cỡ/màu/hiệu ứng), song ca, nền, giọng
 *    dẫn, khung hình — đổi gì thấy nấy ở khung xem trước, tự lưu ngay.
 * Mọi tác vụ dài đều đi qua JobService (FGS) — Studio chỉ hiển thị trạng thái.
 */
class StudioActivity : AppCompatActivity() {

    private lateinit var project: Project

    // st đọc ĐỘNG theo project hiện tại: project có thể bị thay instance khi
    // ProjectStore.reload() chạy — bắt chết object cũ sẽ làm thay đổi phong
    // cách ghi vào hư không. (Getter ở cấp class; Kotlin cấm getter cho val
    // cục bộ trong hàm.)
    private val st: StyleOptions get() = project.style
    private var lines: MutableList<TimedLine> = mutableListOf()

    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var chipSaved: TextView
    private lateinit var preview: KaraokePreviewView
    private lateinit var waveform: WaveformView
    private lateinit var tvTime: TextView
    private lateinit var btnPlay: Button
    private lateinit var swTapMode: Switch
    private lateinit var tvTapInfo: TextView
    private lateinit var rvLines: RecyclerView
    private lateinit var tvLineStats: TextView
    private lateinit var cardJob: LinearLayout
    private lateinit var tvJobLabel: TextView
    private lateinit var pbJob: ProgressBar
    private lateinit var tvJobError: TextView
    private lateinit var btnJobAction: Button

    private val linesAdapter = LinesAdapter()
    private var player: MediaPlayer? = null
    private var playerSource: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tapIndex = 0
    private var currentLineIdx = -1
    private val storeListener: () -> Unit = { runOnUiThread { refreshJobUi(); refreshProjectBits() } }

    private val pickBgImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBg(uri, isVideo = false)
    }
    private val pickBgVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBg(uri, isVideo = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_studio)
        val pid = intent.getStringExtra(EXTRA_PROJECT_ID) ?: ""
        val p = ProjectStore.get(pid)
        if (p == null) {
            toast("Không tìm thấy dự án")
            finish()
            return
        }
        project = p
        lines = ProjectStore.loadLines(pid).toMutableList()

        tvTitle = findViewById(R.id.tvTitle)
        tvSub = findViewById(R.id.tvSub)
        chipSaved = findViewById(R.id.chipSaved)
        preview = findViewById(R.id.preview)
        waveform = findViewById(R.id.waveform)
        tvTime = findViewById(R.id.tvTime)
        btnPlay = findViewById(R.id.btnPlay)
        swTapMode = findViewById(R.id.swTapMode)
        tvTapInfo = findViewById(R.id.tvTapInfo)
        rvLines = findViewById(R.id.rvLines)
        tvLineStats = findViewById(R.id.tvLineStats)
        cardJob = findViewById(R.id.cardJob)
        tvJobLabel = findViewById(R.id.tvJobLabel)
        pbJob = findViewById(R.id.pbJob)
        tvJobError = findViewById(R.id.tvJobError)
        btnJobAction = findViewById(R.id.btnJobCancel)

        rvLines.layoutManager = LinearLayoutManager(this)
        rvLines.adapter = linesAdapter

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnToExport).setOnClickListener {
            startActivity(
                Intent(this, ExportActivity::class.java)
                    .putExtra(ExportActivity.EXTRA_PROJECT_ID, project.id)
            )
        }
        tvTitle.setOnClickListener { renameDialog() }

        btnPlay.setOnClickListener { togglePlay() }
        findViewById<Button>(R.id.btnBack5).setOnClickListener { seekBy(-5.0) }
        findViewById<Button>(R.id.btnFwd5).setOnClickListener { seekBy(5.0) }
        findViewById<Button>(R.id.btnTap).setOnClickListener { onTap() }
        swTapMode.setOnCheckedChangeListener { _, on ->
            if (on) {
                tapIndex = computeTapStartIndex()
                toast("Chế độ gắn nhịp BẬT — nghe nhạc và bấm GẮN NHỊP ở đầu mỗi dòng (đang chờ dòng ${tapIndex + 1})")
            }
            updateTapInfo()
        }
        waveform.onSeek = { sec -> seekTo(sec) }

        findViewById<Button>(R.id.btnTabLines).setOnClickListener { showTab(style = false) }
        findViewById<Button>(R.id.btnTabStyle).setOnClickListener { showTab(style = true) }

        findViewById<Button>(R.id.btnSeparateFast).setOnClickListener {
            enqueueJob("separate", JSONObject().put("mode", "fast"), "Đang tách beat nhanh ở nền")
        }
        findViewById<Button>(R.id.btnSeparateAi).setOnClickListener { confirmSeparateAi() }
        findViewById<Button>(R.id.btnTranscribe).setOnClickListener {
            if (lines.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Chép lời lại bằng AI?")
                    .setMessage("Lời hiện tại (${lines.size} dòng, gồm cả phần bạn đã sửa tay) sẽ bị thay bằng bản chép mới.")
                    .setPositiveButton("Chép lại") { _, _ ->
                        enqueueJob("transcribe", JSONObject().put("force", true), "Đang chép lời ở nền")
                    }
                    .setNegativeButton("Huỷ", null)
                    .show()
            } else {
                enqueueJob("transcribe", JSONObject().put("force", true), "Đang chép lời ở nền")
            }
        }
        findViewById<Button>(R.id.btnPasteLyrics).setOnClickListener { pasteLyricsDialog() }
        btnJobAction.setOnClickListener {
            val job = JobStore.forProject(project.id).firstOrNull {
                it.isActive() || it.status == "error" || it.status == "interrupted"
            } ?: return@setOnClickListener
            if (job.isActive()) {
                JobService.cancel(this, job.id)
            } else {
                JobService.resume(this, job.id)
            }
        }

        setupStylePanel()
        refreshAll()
        JobStore.addListener(storeListener)
    }

    override fun onDestroy() {
        JobStore.removeListener(storeListener)
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        btnPlay.text = "▶"
    }

    // ---------------- làm mới UI ----------------

    private fun refreshAll() {
        ProjectStore.get(project.id)?.let { project = it }
        tvTitle.text = project.title
        tvSub.text = buildString {
            append(
                when (project.sourceType) {
                    "youtube" -> "Link YouTube"
                    "mediastore" -> "File trong máy"
                    else -> "File đã chọn"
                }
            )
            append(
                when (project.beatMode) {
                    "ai" -> " · Beat đã tách (AI)"
                    "fast" -> " · Beat đã tách (nhanh)"
                    else -> " · Chưa tách beat"
                }
            )
            if (project.durationSec > 0) append(" · ").append(fmtTime(project.durationSec))
        }
        lines = ProjectStore.loadLines(project.id).toMutableList()
        linesAdapter.notifyDataSetChanged()
        updateLineStats()
        updatePreview()
        loadPeaks()
        refreshJobUi()
        syncStyleControls()
    }

    private fun refreshProjectBits() {
        val fresh = ProjectStore.get(project.id) ?: return
        val linesChanged = ProjectStore.loadLines(project.id).size != lines.size
        project = fresh
        if (linesChanged) {
            lines = ProjectStore.loadLines(project.id).toMutableList()
            linesAdapter.notifyDataSetChanged()
            updateLineStats()
        }
        tvSub.text = buildString {
            append(
                when (project.beatMode) {
                    "ai" -> "Beat đã tách (AI)"
                    "fast" -> "Beat đã tách (nhanh)"
                    else -> "Chưa tách beat"
                }
            )
            if (project.durationSec > 0) append(" · ").append(fmtTime(project.durationSec))
        }
        updatePreview()
        loadPeaks()
    }

    private fun updateLineStats() {
        val matched = lines.count { it.matched }
        tvLineStats.text = if (lines.isEmpty()) {
            "Chưa có dòng lời nào — bấm Chép lời AI hoặc Dán lời / .lrc"
        } else {
            "Dòng lời · ${lines.size} dòng · $matched đã khớp · ${lines.size - matched} chưa"
        }
        updateTapInfo()
    }

    private fun updateTapInfo() {
        val done = lines.count { it.matched }
        tvTapInfo.text = "Đã gắn $done/${lines.size} câu"
    }

    private fun updatePreview() {
        preview.style = project.style
        preview.ratioLabel = project.style.ratio
        when (project.bg.type) {
            "gradient" -> {
                preview.bgKind = project.bg.value
                preview.bgBitmap = null
            }
            "color" -> {
                preview.bgKind = "color"
                preview.bgColorHex = project.bg.value
                preview.bgBitmap = null
            }
            "image" -> {
                preview.bgKind = "image"
                preview.setBgImageFile(File(project.bg.value))
            }
            else -> preview.bgKind = "demtim"
        }
        preview.lines = lines
        preview.durationSec = project.durationSec
        preview.invalidate()
    }

    private fun loadPeaks() {
        val dir = ProjectStore.dirOf(project.id)
        val peaksFile = File(dir, "peaks.bin")
        val songWav = File(dir, "song.wav")
        thread {
            var peaks = AudioUtil.loadPeaks(peaksFile)
            if (peaks == null && songWav.exists()) {
                peaks = try {
                    AudioUtil.computePeaks(songWav).also {
                        AudioUtil.savePeaks(peaksFile, it)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            val marks = if (project.durationSec > 0) {
                lines.map { it.start / project.durationSec }
            } else {
                emptyList()
            }
            runOnUiThread {
                if (peaks != null) waveform.peaks = peaks
                waveform.durationSec = project.durationSec
                waveform.lineMarks = marks
            }
        }
    }

    private fun refreshJobUi() {
        val job = JobStore.forProject(project.id).firstOrNull {
            it.isActive() || it.status == "error" || it.status == "interrupted"
        }
        if (job == null) {
            cardJob.visibility = View.GONE
            return
        }
        cardJob.visibility = View.VISIBLE
        when {
            job.isActive() -> {
                tvJobLabel.text = "${job.typeLabel()}: ${job.label} — ${job.progress.toInt()}%" +
                    if (job.status == "queued") " (đang chờ)" else ""
                pbJob.progress = job.progress.toInt()
                tvJobError.visibility = View.GONE
                btnJobAction.text = "Huỷ"
            }
            else -> { // error / interrupted
                tvJobLabel.text = "${job.typeLabel()}: ${if (job.status == "error") "bị lỗi" else "bị gián đoạn"}"
                pbJob.progress = job.progress.toInt()
                tvJobError.visibility = View.VISIBLE
                tvJobError.text = job.error ?: ""
                btnJobAction.text = "Chạy tiếp"
            }
        }
    }

    private fun showTab(style: Boolean) {
        findViewById<View>(R.id.layoutLines).visibility = if (style) View.GONE else View.VISIBLE
        findViewById<View>(R.id.panelStyle).visibility = if (style) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnTabLines).setBackgroundResource(
            if (style) R.drawable.bg_btn_dark else R.drawable.bg_btn_selected
        )
        findViewById<Button>(R.id.btnTabStyle).setBackgroundResource(
            if (style) R.drawable.bg_btn_selected else R.drawable.bg_btn_dark
        )
    }

    // ---------------- phát thử + gắn nhịp ----------------

    private fun playerFile(): File? {
        val dir = ProjectStore.dirOf(project.id)
        val song = File(dir, "song.wav")
        if (song.exists()) return song
        val inst = File(dir, "inst.wav")
        return if (inst.exists()) inst else null
    }

    private fun ensurePlayer(): Boolean {
        val f = playerFile()
        if (f == null) {
            toast("Chưa có nhạc — chờ tải/nạp bài xong đã")
            return false
        }
        if (player == null || playerSource != f) {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                setOnCompletionListener { btnPlay.text = "▶" }
            }
            playerSource = f
        }
        return true
    }

    private val tick = object : Runnable {
        override fun run() {
            val mp = player
            if (mp != null) {
                val pos = mp.currentPosition / 1000.0
                tvTime.text = "${fmtTime(pos)} / ${fmtTime(project.durationSec)}"
                waveform.positionSec = pos
                preview.positionSec = pos
                val idx = lines.indexOfLast { pos >= it.start }
                if (idx != currentLineIdx) {
                    currentLineIdx = idx
                    linesAdapter.notifyDataSetChanged()
                    if (idx >= 0) rvLines.smoothScrollToPosition(idx)
                }
            }
            handler.postDelayed(this, 120)
        }
    }

    private fun togglePlay() {
        if (!ensurePlayer()) return
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            btnPlay.text = "▶"
            handler.removeCallbacks(tick)
        } else {
            mp.start()
            btnPlay.text = "⏸"
            handler.post(tick)
        }
    }

    private fun seekTo(sec: Double) {
        if (!ensurePlayer()) return
        player?.seekTo((sec * 1000).toInt())
        val pos = sec.coerceIn(0.0, project.durationSec)
        tvTime.text = "${fmtTime(pos)} / ${fmtTime(project.durationSec)}"
        waveform.positionSec = pos
        preview.positionSec = pos
    }

    private fun seekBy(delta: Double) {
        val mp = player ?: return
        seekTo(mp.currentPosition / 1000.0 + delta)
    }

    private fun computeTapStartIndex(): Int {
        val firstUnmatched = lines.indexOfFirst { !it.matched }
        if (firstUnmatched >= 0) return firstUnmatched
        val pos = (player?.currentPosition ?: 0) / 1000.0
        val next = lines.indexOfFirst { it.start > pos }
        return if (next >= 0) next else 0
    }

    /** Gắn nhịp: gán vị trí phát hiện tại làm giờ bắt đầu của dòng đang chờ,
     *  chốt lại giờ kết thúc toàn bộ danh sách — tương đương phím Space bản PC. */
    private fun onTap() {
        if (!swTapMode.isChecked) {
            toast("Bật “Chế độ gắn nhịp” trước, rồi bấm nút này ở đầu mỗi dòng khi nghe nhạc")
            return
        }
        if (lines.isEmpty()) {
            toast("Chưa có dòng lời để gắn nhịp")
            return
        }
        val mp = player
        if (mp == null || !mp.isPlaying) {
            toast("Hãy bấm ▶ phát nhạc rồi gắn nhịp theo tiếng hát")
            return
        }
        if (tapIndex !in lines.indices) tapIndex = 0
        val pos = mp.currentPosition / 1000.0
        val old = lines[tapIndex]
        lines[tapIndex] = old.copy(start = Math.round(pos * 100) / 100.0, matched = true)
        tapIndex++
        retimeEnds()
        saveLines()
        linesAdapter.notifyDataSetChanged()
        updateLineStats()
        if (tapIndex < lines.size) rvLines.smoothScrollToPosition(tapIndex)
    }

    /** Chốt end = start của câu sau; câu cuối theo thời lượng bài. Giữ words.
     *  Đặt end = start (end "không hợp lệ") để assignEnds đi đúng nhánh
     *  "lấy start câu sau" như luồng căn tay của bản PC. */
    private fun retimeEnds() {
        val sorted = lines.sortedBy { it.start }
        lines = Lyrics.assignEnds(
            sorted.map { it.copy(end = it.start) },
            project.durationSec
        ).toMutableList()
    }

    private fun saveLines() {
        ProjectStore.saveLines(project.id, lines)
        val p = ProjectStore.get(project.id) ?: project
        p.lyricsState = when {
            lines.isEmpty() -> "none"
            lines.all { it.matched } -> "synced"
            else -> "editing"
        }
        ProjectStore.save(p)
        project = p
        flashSaved()
        waveform.lineMarks = if (project.durationSec > 0) {
            lines.map { it.start / project.durationSec }
        } else {
            emptyList()
        }
        updatePreview()
    }

    private fun flashSaved() {
        chipSaved.visibility = View.VISIBLE
        handler.postDelayed({ chipSaved.visibility = View.GONE }, 1200)
    }

    // ---------------- tác vụ ----------------

    private fun enqueueJob(type: String, payload: JSONObject, toastMsg: String) {
        val active = JobStore.forProject(project.id).firstOrNull { it.isActive() }
        if (active != null) {
            toast("Đang có tác vụ chạy (${active.typeLabel()}) — chờ xong hoặc huỷ trước")
            return
        }
        JobService.enqueue(this, project.id, type, payload.toString())
        toast(toastMsg)
        refreshJobUi()
    }

    private fun confirmSeparateAi() {
        if (!ModelManager.mdxReady(this)) {
            AlertDialog.Builder(this)
                .setTitle("Chưa có model tách beat AI")
                .setMessage("Cần tải model MDX (~67 MB) ở Thư viện trước. Tải xong quay lại đây bấm tách AI.")
                .setPositiveButton("Về Thư viện") { _, _ -> finish() }
                .setNegativeButton("Để sau", null)
                .show()
            return
        }
        val etaMin = (project.durationSec * 2 / 60).toInt().coerceAtLeast(1)
        val etaMax = (project.durationSec * 4 / 60).toInt().coerceAtLeast(etaMin + 1)
        AlertDialog.Builder(this)
            .setTitle("Tách beat bằng AI?")
            .setMessage(
                "Tách AI cho beat sạch nhất nhưng rất lâu trên điện thoại: bài này " +
                    "ước tính $etaMin–$etaMax phút, máy sẽ nóng và tốn pin. " +
                    "App chạy nền, bạn cứ để đó làm việc khác.\n\n" +
                    "Tách nhanh (cắt stereo) chỉ mất vài giây."
            )
            .setPositiveButton("Tách AI") { _, _ ->
                enqueueJob("separate", JSONObject().put("mode", "ai"), "Đang tách beat AI ở nền")
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun pasteLyricsDialog() {
        val input = EditText(this).apply {
            hint = "Dán lời bài hát vào đây.\n" +
                "• Có mốc giờ kiểu [0:12.34] (file .lrc) -> app dùng luôn mốc giờ đó.\n" +
                "• Lời trơn từng dòng -> app dùng AI nghe rồi đối chiếu để gắn giờ."
            minLines = 8
            setTextColor(getColor(R.color.text_main))
        }
        AlertDialog.Builder(this)
            .setTitle("Dán lời / .lrc")
            .setView(input)
            .setPositiveButton("Dùng lời này") { _, _ ->
                val text = input.text.toString()
                if (text.isBlank()) return@setPositiveButton
                val lrc = Lyrics.parseLrc(text)
                if (lrc.size >= 2) {
                    val raw = lrc.map { TimedLine(it.start, 0.0, it.text, matched = true) }
                    lines = Lyrics.assignEnds(raw, project.durationSec).toMutableList()
                    saveLines()
                    linesAdapter.notifyDataSetChanged()
                    updateLineStats()
                    toast("Đã nhận ${lines.size} dòng từ .lrc")
                } else {
                    val payload = JSONObject().put("lyrics", text).put("force", true)
                    enqueueJob("transcribe", payload, "Đang đối chiếu lời bằng AI ở nền")
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun renameDialog() {
        val input = EditText(this).apply {
            setText(project.title)
            setTextColor(getColor(R.color.text_main))
        }
        AlertDialog.Builder(this)
            .setTitle("Đổi tên bài")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    project.title = name
                    ProjectStore.save(project)
                    tvTitle.text = name
                    updatePreview()
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ---------------- danh sách dòng lời ----------------

    inner class LinesAdapter : RecyclerView.Adapter<LinesAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_line, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = lines.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(position)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvIndex: TextView = v.findViewById(R.id.tvIndex)
            private val tvLineText: TextView = v.findViewById(R.id.tvLineText)
            private val tvLineTime: TextView = v.findViewById(R.id.tvLineTime)
            private val tvVoice: TextView = v.findViewById(R.id.tvVoice)
            private val tvUnmatched: TextView = v.findViewById(R.id.tvUnmatched)
            private val btnMinus: Button = v.findViewById(R.id.btnMinus)
            private val btnPlus: Button = v.findViewById(R.id.btnPlus)
            private val btnEdit: Button = v.findViewById(R.id.btnEdit)
            private val root: View = v.findViewById(R.id.root)

            fun bind(pos: Int) {
                val ln = lines[pos]
                tvIndex.text = (pos + 1).toString()
                val isCurrent = pos == currentLineIdx && player?.isPlaying == true
                if (isCurrent) {
                    tvIndex.setBackgroundResource(R.drawable.bg_circle_grad)
                } else {
                    tvIndex.setBackgroundResource(R.drawable.bg_btn_dark)
                }
                // Tô màu phần đã hát của dòng đang phát (theo giờ từng từ nếu có)
                tvLineText.text = coloredText(ln, pos)
                tvLineTime.text = "${fmtTime(ln.start)} – ${fmtTime(ln.end)}"
                tvUnmatched.visibility = if (ln.matched) View.GONE else View.VISIBLE
                tvVoice.text = when (ln.voice) {
                    1 -> "Giọng: Nữ"
                    2 -> "Giọng: Nam"
                    else -> "Giọng: tự động"
                }
                tvVoice.setOnClickListener {
                    val next = (ln.voice + 1) % 3
                    lines[pos] = ln.copy(voice = next)
                    saveLines()
                    notifyItemChanged(pos)
                }
                root.setOnClickListener {
                    seekTo(ln.start)
                    currentLineIdx = pos
                    notifyDataSetChanged()
                }
                btnMinus.setOnClickListener { nudge(pos, -0.1) }
                btnPlus.setOnClickListener { nudge(pos, 0.1) }
                btnEdit.setOnClickListener { editLineDialog(pos) }
            }

            private fun coloredText(ln: TimedLine, pos: Int): CharSequence {
                val posNow = (player?.currentPosition ?: 0) / 1000.0
                val active = player?.isPlaying == true && posNow >= ln.start && posNow < ln.end
                if (!active) return ln.text
                val words = Lyrics.splitWords(ln.text)
                if (words.isEmpty() || ln.words.size != words.size) {
                    return SpannableStringBuilder(ln.text).apply {
                        setSpan(
                            ForegroundColorSpan(Color.parseColor(project.style.color)),
                            0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                val sb = SpannableStringBuilder()
                var charPos = 0
                words.forEachIndexed { i, w ->
                    val sung = posNow >= ln.words[i].start
                    val start = sb.length
                    if (i > 0) {
                        sb.append(" ")
                        charPos++
                    }
                    sb.append(w)
                    if (sung) {
                        sb.setSpan(
                            ForegroundColorSpan(Color.parseColor(project.style.color)),
                            start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    charPos += w.length
                }
                return sb
            }

            private fun nudge(pos: Int, delta: Double) {
                val ln = lines[pos]
                fun shift(x: Double) = maxOf(0.0, Math.round((x + delta) * 100) / 100.0)
                lines[pos] = ln.copy(
                    start = shift(ln.start),
                    end = shift(ln.end),
                    words = ln.words.map { WordTime(shift(it.start), shift(it.end)) },
                    matched = true
                )
                saveLines()
                notifyItemChanged(pos)
                updateLineStats()
            }
        }
    }

    private fun editLineDialog(pos: Int) {
        val ln = lines[pos]
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }
        val etText = EditText(this).apply {
            setText(ln.text)
            setTextColor(getColor(R.color.text_main))
        }
        val etStart = EditText(this).apply {
            setText("%.2f".format(ln.start))
            hint = "Bắt đầu (giây)"
            setTextColor(getColor(R.color.text_main))
        }
        val etEnd = EditText(this).apply {
            setText("%.2f".format(ln.end))
            hint = "Kết thúc (giây)"
            setTextColor(getColor(R.color.text_main))
        }
        container.addView(etText)
        container.addView(etStart)
        container.addView(etEnd)
        AlertDialog.Builder(this)
            .setTitle("Sửa dòng ${pos + 1}")
            .setView(container)
            .setPositiveButton("Lưu") { _, _ ->
                val newText = etText.text.toString().trim()
                val newStart = etStart.text.toString().toDoubleOrNull() ?: ln.start
                val newEnd = etEnd.text.toString().toDoubleOrNull() ?: ln.end
                if (newText.isNotEmpty()) {
                    // sửa tay: bỏ giờ từng từ (không còn khớp chữ) nhưng giữ mốc câu
                    lines[pos] = ln.copy(
                        text = newText,
                        start = newStart.coerceAtLeast(0.0),
                        end = maxOf(newEnd, newStart + 0.05),
                        words = if (newText == ln.text) ln.words else emptyList(),
                        matched = true
                    )
                    lines = lines.sortedBy { it.start }.toMutableList()
                    saveLines()
                    linesAdapter.notifyDataSetChanged()
                    updateLineStats()
                }
            }
            .setNeutralButton("Xoá dòng") { _, _ ->
                lines.removeAt(pos)
                retimeEnds()
                saveLines()
                linesAdapter.notifyDataSetChanged()
                updateLineStats()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ---------------- panel phong cách ----------------

    private lateinit var spPreset: Spinner
    private lateinit var spFont: Spinner
    private lateinit var spEffect: Spinner
    private lateinit var spBgType: Spinner
    private var styleSyncing = false

    private fun darkSpinner(spinner: Spinner, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(getColor(R.color.text_main))
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(getColor(R.color.text_main))
                v.setBackgroundColor(getColor(R.color.bg_card_2))
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        if (selected in items.indices) spinner.setSelection(selected)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            // Spinner tự bắn onItemSelected 1 lần khi gắn adapter — bỏ qua
            // lượt đầu kẻo áp preset/chọn sẵn đè mất style user đã lưu.
            private var firstFire = true

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (firstFire) {
                    firstFire = false
                    return
                }
                if (!styleSyncing) onPick(pos)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupStylePanel() {
        spPreset = findViewById(R.id.spPreset)
        spFont = findViewById(R.id.spFont)
        spEffect = findViewById(R.id.spEffect)
        spBgType = findViewById(R.id.spBgType)
        darkSpinner(spPreset, StylePresets.ALL.map { it.name }, 0) { idx ->
            val pr = StylePresets.ALL[idx]
            st.color = pr.color
            st.baseColor = pr.base
            st.font = pr.font
            st.size = pr.size
            saveStyle()
            syncStyleControls()
        }
        darkSpinner(spFont, StylePresets.FONTS, StylePresets.FONTS.indexOf(st.font).coerceAtLeast(0)) { idx ->
            st.font = StylePresets.FONTS[idx]
            saveStyle()
        }
        val effects = listOf("sweep", "pop", "zoom", "type")
        val effectLabels = listOf(
            "Quét mượt — tô dần đều như karaoke xịn",
            "Nhảy từng từ",
            "Phóng to từ đang hát",
            "Gõ từng ký tự"
        )
        darkSpinner(spEffect, effectLabels, effects.indexOf(st.effect).coerceAtLeast(0)) { idx ->
            st.effect = effects[idx]
            saveStyle()
        }
        val bgTypes = listOf("gradient", "color", "image", "video")
        val bgLabels = listOf("Mẫu nền chuyển màu", "Đen đơn giản", "Ảnh của bạn", "Video nền của bạn")
        darkSpinner(spBgType, bgLabels, bgTypes.indexOf(project.bg.type).coerceAtLeast(0)) { idx ->
            val t = bgTypes[idx]
            project.bg.type = t
            if (t == "gradient" && project.bg.value.startsWith("/")) project.bg.value = "demtim"
            if (t == "color" && !project.bg.value.startsWith("#")) project.bg.value = "#0b0e1a"
            if ((t == "image" || t == "video") && !project.bg.value.startsWith("/")) {
                toast("Hãy bấm nút chọn ${if (t == "image") "ảnh" else "video"} nền bên dưới")
            }
            saveStyle()
        }

        val sbSize = findViewById<SeekBar>(R.id.sbSize)
        sbSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || styleSyncing) return
                st.size = 44 + progress
                findViewById<TextView>(R.id.tvSizeVal).text = "CỠ CHỮ: ${st.size}"
                saveStyle()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val sbGuide = findViewById<SeekBar>(R.id.sbGuide)
        sbGuide.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || styleSyncing) return
                st.guidePct = progress
                findViewById<TextView>(R.id.tvGuideVal).text = "GIỌNG DẪN: ${st.guidePct}%"
                saveStyle()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        buildSwatches(findViewById(R.id.llSungColors), SUNG_COLORS) { hex ->
            st.color = hex
            saveStyle()
        }
        buildSwatches(findViewById(R.id.llBaseColors), BASE_COLORS) { hex ->
            st.baseColor = hex
            saveStyle()
        }
        buildSwatches(findViewById(R.id.llDuetColors), DUET_COLORS) { hex ->
            st.duetColor = hex
            saveStyle()
        }
        buildGradientChips()

        findViewById<Switch>(R.id.swTwoLine).setOnCheckedChangeListener { _, v ->
            if (!styleSyncing) {
                st.twoLine = v; saveStyle()
            }
        }
        findViewById<Switch>(R.id.swFade).setOnCheckedChangeListener { _, v ->
            if (!styleSyncing) {
                st.fade = v; saveStyle()
            }
        }
        findViewById<Switch>(R.id.swCountdown).setOnCheckedChangeListener { _, v ->
            if (!styleSyncing) {
                st.countdown = v; saveStyle()
            }
        }
        findViewById<Switch>(R.id.swProgress).setOnCheckedChangeListener { _, v ->
            if (!styleSyncing) {
                st.progressBar = v; saveStyle()
            }
        }
        findViewById<Switch>(R.id.swDuet).setOnCheckedChangeListener { _, v ->
            if (!styleSyncing) {
                st.duet = v
                findViewById<View>(R.id.llDuetRow).visibility = if (v) View.VISIBLE else View.GONE
                saveStyle()
            }
        }
        findViewById<Button>(R.id.btnPickBgImage).setOnClickListener {
            pickBgImage.launch(arrayOf("image/*"))
        }
        findViewById<Button>(R.id.btnPickBgVideo).setOnClickListener {
            pickBgVideo.launch(arrayOf("video/*"))
        }
        findViewById<Button>(R.id.btnR169).setOnClickListener { setRatio("16:9") }
        findViewById<Button>(R.id.btnR916).setOnClickListener { setRatio("9:16") }
        findViewById<Button>(R.id.btnR11).setOnClickListener { setRatio("1:1") }
        syncStyleControls()
    }

    private fun buildSwatches(container: LinearLayout, colors: List<String>, onPick: (String) -> Unit) {
        container.removeAllViews()
        val size = (40 * resources.displayMetrics.density).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()
        for (hex in colors) {
            val v = View(this)
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * resources.displayMetrics.density
                setColor(Color.parseColor(hex))
                setStroke(2, Color.parseColor("#3A3A52"))
            }
            v.background = gd
            v.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(0, 0, margin, 0)
            }
            v.setOnClickListener { onPick(hex) }
            container.addView(v)
        }
    }

    private fun buildGradientChips() {
        val container = findViewById<LinearLayout>(R.id.llGradients)
        container.removeAllViews()
        val w = (72 * resources.displayMetrics.density).toInt()
        val h = (44 * resources.displayMetrics.density).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()
        for ((key, pair) in Gradients.ALL) {
            val cols = pair.second.map { Color.parseColor(it.replace("0x", "#")) }.toIntArray()
            val gd = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, cols
            ).apply { cornerRadius = 10 * resources.displayMetrics.density }
            val v = View(this)
            v.background = gd
            v.layoutParams = LinearLayout.LayoutParams(w, h).apply {
                setMargins(0, 0, margin, 0)
            }
            v.setOnClickListener {
                project.bg.type = "gradient"
                project.bg.value = key
                if (spBgType.selectedItemPosition != 0) spBgType.setSelection(0)
                saveStyle()
                toast("Nền: ${pair.first}")
            }
            container.addView(v)
        }
    }

    private fun importBg(uri: Uri, isVideo: Boolean) {
        val dir = ProjectStore.dirOf(project.id)
        val name = if (isVideo) "bg_video.mp4" else "bg_image.img"
        val dst = File(dir, name)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { input.copyTo(it) }
            }
            project.bg.type = if (isVideo) "video" else "image"
            project.bg.value = dst.absolutePath
            spBgType.setSelection(if (isVideo) 3 else 2)
            saveStyle()
            toast("Đã chọn nền")
        } catch (e: Exception) {
            toast("Không đọc được file nền: ${e.message}")
        }
    }

    private fun setRatio(ratio: String) {
        project.style.ratio = ratio
        saveStyle()
        syncRatioButtons()
    }

    private fun syncRatioButtons() {
        val r = project.style.ratio
        findViewById<Button>(R.id.btnR169).setBackgroundResource(
            if (r == "16:9") R.drawable.bg_btn_selected else R.drawable.bg_btn_dark
        )
        findViewById<Button>(R.id.btnR916).setBackgroundResource(
            if (r == "9:16") R.drawable.bg_btn_selected else R.drawable.bg_btn_dark
        )
        findViewById<Button>(R.id.btnR11).setBackgroundResource(
            if (r == "1:1") R.drawable.bg_btn_selected else R.drawable.bg_btn_dark
        )
    }

    private fun syncStyleControls() {
        if (!::spPreset.isInitialized) return
        styleSyncing = true
        val st = project.style
        spFont.setSelection(StylePresets.FONTS.indexOf(st.font).coerceAtLeast(0))
        spEffect.setSelection(listOf("sweep", "pop", "zoom", "type").indexOf(st.effect).coerceAtLeast(0))
        spBgType.setSelection(listOf("gradient", "color", "image", "video").indexOf(project.bg.type).coerceAtLeast(0))
        val sbSize = findViewById<SeekBar>(R.id.sbSize)
        sbSize.progress = (st.size - 44).coerceIn(0, 66)
        findViewById<TextView>(R.id.tvSizeVal).text = "CỠ CHỮ: ${st.size}"
        val sbGuide = findViewById<SeekBar>(R.id.sbGuide)
        sbGuide.progress = st.guidePct
        findViewById<TextView>(R.id.tvGuideVal).text = "GIỌNG DẪN: ${st.guidePct}%"
        findViewById<Switch>(R.id.swTwoLine).isChecked = st.twoLine
        findViewById<Switch>(R.id.swFade).isChecked = st.fade
        findViewById<Switch>(R.id.swCountdown).isChecked = st.countdown
        findViewById<Switch>(R.id.swProgress).isChecked = st.progressBar
        findViewById<Switch>(R.id.swDuet).isChecked = st.duet
        findViewById<View>(R.id.llDuetRow).visibility = if (st.duet) View.VISIBLE else View.GONE
        syncRatioButtons()
        styleSyncing = false
    }

    private fun saveStyle() {
        ProjectStore.save(project)
        updatePreview()
        flashSaved()
    }

    // ---------------- phụ trợ ----------------

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun fmtTime(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        private val SUNG_COLORS = listOf(
            "#FFD400", "#00E5FF", "#FF5C8A", "#69F0AE", "#FF9100", "#FF2E88", "#B388FF", "#FFFFFF"
        )
        private val BASE_COLORS = listOf("#FFFFFF", "#FFF3E0", "#E1F5FE", "#E8E8FF", "#9E9E9E")
        private val DUET_COLORS = listOf("#00E5FF", "#FF5C8A", "#69F0AE", "#FF9100", "#B388FF")
    }
}
