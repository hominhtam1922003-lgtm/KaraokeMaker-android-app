package com.example.karaokemaker.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.karaokemaker.audio.AudioUtil
import com.example.karaokemaker.audio.Ffmpeg
import com.example.karaokemaker.audio.Mdx
import com.example.karaokemaker.audio.Whisper
import com.example.karaokemaker.core.Align
import com.example.karaokemaker.core.Lyrics
import com.example.karaokemaker.core.TranscribeLines
import com.example.karaokemaker.data.Job
import com.example.karaokemaker.data.JobStore
import com.example.karaokemaker.data.Project
import com.example.karaokemaker.data.ProjectStore
import com.example.karaokemaker.download.ModelManager
import com.example.karaokemaker.download.Youtube
import org.json.JSONObject
import java.io.File

/**
 * Các chặng xử lý của một job — chạy trên thread nền của JobService.
 * Mọi chặng đều IDEMPOTENT theo file đầu ra: file đích đã có (và hợp lệ) thì
 * bỏ qua. Nhờ vậy job bị gián đoạn (HyperOS giết app) chạy tiếp không làm
 * lại từ đầu. Tiến độ của từng chặng được ánh xạ vào khoảng % của job.
 */
object Pipeline {

    fun interface Reporter {
        fun report(pctInJob: Double, label: String)
    }

    class Cancelled : Exception("Người dùng đã huỷ")

    private fun checkCancelled(job: Job) {
        if (JobStore.get(job.id)?.status == "canceled") throw Cancelled()
    }

    fun runJob(context: Context, job: Job) {
        val rep = Reporter { pct, label -> JobStore.progress(job.id, pct, label) }
        when (job.type) {
            "model" -> runModelDownload(context, job, rep)
            "download" -> runDownload(context, job, rep)
            "separate" -> runSeparate(context, job, rep)
            "transcribe" -> runTranscribe(context, job, rep)
            "render" -> runRender(context, job, rep)
            "pipeline" -> runPipeline(context, job, rep)
            else -> throw IllegalArgumentException("Loại job lạ: ${job.type}")
        }
    }

    // ---------------- chặng: tải model ----------------

    private fun runModelDownload(context: Context, job: Job, rep: Reporter) {
        val kind = job.payloadJson().optString("kind", "whisper")
        val specs = if (kind == "mdx") listOf(ModelManager.MDX) else ModelManager.WHISPER_FILES
        rep.report(1.0, "đang tải model AI")
        ModelManager.download(context, specs, { pct, name ->
            rep.report(pct, if (name.isEmpty()) "đang tải model AI" else "đang tải $name")
        }) { JobStore.get(job.id)?.status == "canceled" }
        checkCancelled(job)
    }

    // ---------------- chặng: tải bài / nạp nguồn ----------------

    private fun projectOrThrow(job: Job): Project =
        ProjectStore.get(job.projectId)
            ?: throw IllegalStateException("Dự án không còn tồn tại")

    /** Bảo đảm dự án đã có song.wav (WAV làm việc) + duration. */
    fun ensureSongWav(context: Context, job: Job, rep: Reporter, lo: Double, hi: Double) {
        val p = projectOrThrow(job)
        val dir = ProjectStore.dirOf(p.id)
        val songWav = File(dir, "song.wav")
        if (songWav.exists() && songWav.length() > 0) {
            if (p.durationSec <= 0) {
                p.durationSec = Ffmpeg.probeDuration(songWav.absolutePath)
                ProjectStore.save(p)
            }
            return
        }
        val srcFile: File = when (p.sourceType) {
            "youtube" -> {
                rep.report(lo, "đang cập nhật yt-dlp")
                try {
                    Youtube.updateNightly(context)
                } catch (e: Exception) {
                    // Cập nhật lỗi không chặn tải — yt-dlp cũ vẫn có thể chạy;
                    // nếu tải cũng lỗi, lỗi tải sẽ hiện rõ cho người dùng.
                }
                checkCancelled(job)
                Youtube.downloadAudio(context, p.sourceRef, dir) { pct, label ->
                    rep.report(lo + (hi - lo) * 0.7 * pct / 100.0, label)
                }
            }
            "file", "mediastore" -> {
                rep.report(lo, "đang đọc file")
                val uri = Uri.parse(p.sourceRef)
                val ext = guessExt(context, uri)
                val dst = File(dir, "source.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("Không mở được file đã chọn")
                dst
            }
            else -> throw IllegalStateException("Nguồn bài không rõ: ${p.sourceType}")
        }
        checkCancelled(job)
        rep.report(lo + (hi - lo) * 0.85, "đang trích âm thanh")
        Ffmpeg.extractAudio(srcFile.absolutePath, songWav)
        p.durationSec = Ffmpeg.probeDuration(songWav.absolutePath)
        if (p.durationSec <= 0) throw IllegalStateException("File không đọc được tiếng — thử file/link khác")
        ProjectStore.save(p)
        // peaks cho waveform ở Studio (tính 1 lần, cache lại)
        val peaksFile = File(dir, "peaks.bin")
        if (!peaksFile.exists()) {
            try {
                AudioUtil.savePeaks(peaksFile, AudioUtil.computePeaks(songWav))
            } catch (e: Exception) {
                // không có waveform vẫn dùng được app — bỏ qua
            }
        }
        rep.report(hi, "đã nạp bài")
    }

    private fun runDownload(context: Context, job: Job, rep: Reporter) {
        ensureSongWav(context, job, rep, 0.0, 100.0)
    }

    private fun guessExt(context: Context, uri: Uri): String {
        val mime = context.contentResolver.getType(uri) ?: ""
        return when {
            mime.contains("mpeg") -> "mp3"
            mime.contains("mp4") -> "mp4"
            mime.contains("wav") -> "wav"
            mime.contains("webm") -> "webm"
            mime.contains("ogg") -> "ogg"
            mime.contains("flac") -> "flac"
            mime.contains("x-matroska") -> "mkv"
            else -> "bin"
        }
    }

    // ---------------- chặng: tách beat ----------------

    fun ensureBeat(context: Context, job: Job, mode: String, rep: Reporter, lo: Double, hi: Double) {
        val p = projectOrThrow(job)
        val dir = ProjectStore.dirOf(p.id)
        val songWav = File(dir, "song.wav")
        val instWav = File(dir, "inst.wav")
        if (p.beatReady && instWav.exists() && instWav.length() > 0 && p.beatMode == mode) {
            rep.report(hi, "beat đã sẵn sàng")
            return
        }
        ensureSongWav(context, job, rep, lo, lo + (hi - lo) * 0.15)
        val spanLo = lo + (hi - lo) * 0.15
        when (mode) {
            "fast" -> {
                Ffmpeg.centerCut(songWav, instWav, p.durationSec) { pct, label ->
                    rep.report(spanLo + (hi - spanLo) * pct / 100.0, label)
                }
                // chế độ nhanh không có stem giọng riêng
                File(dir, "vocal.wav").delete()
            }
            "ai" -> {
                if (!ModelManager.mdxReady(context)) {
                    throw IllegalStateException(
                        "Chưa có model AI tách beat — hãy tải model MDX trước (nút Tải model AI ở Thư viện)"
                    )
                }
                val vocal = File(dir, "vocal.wav")
                Mdx.separate(
                    songWav, instWav, vocal,
                    ModelManager.fileOf(context, ModelManager.MDX_NAME),
                    { pct, label -> rep.report(spanLo + (hi - spanLo) * pct / 100.0, label) }
                ) { JobStore.get(job.id)?.status == "canceled" }
            }
            else -> throw IllegalArgumentException("Chế độ tách beat lạ: $mode")
        }
        checkCancelled(job)
        p.beatMode = mode
        p.beatReady = true
        ProjectStore.save(p)
        rep.report(hi, "đã tách beat xong")
    }

    private fun runSeparate(context: Context, job: Job, rep: Reporter) {
        val mode = job.payloadJson().optString("mode", "fast")
        ensureBeat(context, job, mode, rep, 0.0, 100.0)
    }

    // ---------------- chặng: chép lời ----------------

    /**
     * Chép lời bằng AI. Nếu payload có "lyrics" (lời user dán) thì đối chiếu
     * để lấy giờ (lời chuẩn của user thắng); không có thì AI tự chép thành câu.
     * Nguồn nghe: stem giọng nếu có (vocal.wav), không thì bản phối song.wav.
     */
    fun ensureLyrics(context: Context, job: Job, rep: Reporter, lo: Double, hi: Double) {
        val p = projectOrThrow(job)
        val payloadLyrics = job.payloadJson().optString("lyrics", "")
        val force = job.payloadJson().optBoolean("force", false)
        // Có lời dán mới trong payload hoặc job ép làm lại (force) -> luôn chạy.
        // Không có -> lời đã làm xong rồi thì bỏ qua (chạy tiếp sau gián đoạn).
        if (!force && payloadLyrics.isBlank() &&
            (p.lyricsState == "synced" || p.lyricsState == "editing")
        ) {
            val existing = ProjectStore.loadLines(p.id)
            if (existing.isNotEmpty()) {
                rep.report(hi, "lời đã sẵn sàng")
                return
            }
        }
        ensureSongWav(context, job, rep, lo, lo + (hi - lo) * 0.1)
        if (!ModelManager.whisperReady(context)) {
            throw IllegalStateException(
                "Chưa có model AI nghe lời — hãy tải model Whisper trước (nút Tải model AI ở Thư viện)"
            )
        }
        val dir = ProjectStore.dirOf(p.id)
        val vocal = File(dir, "vocal.wav")
        val hearSrc = if (vocal.exists() && vocal.length() > 0) vocal else File(dir, "song.wav")
        val wav16 = File(dir, "hear16k.wav")
        rep.report(lo + (hi - lo) * 0.15, "đang chuẩn bị âm thanh cho AI nghe")
        Ffmpeg.decode16kMono(hearSrc.absolutePath, wav16)
        checkCancelled(job)
        val spanLo = lo + (hi - lo) * 0.2
        val spanHi = lo + (hi - lo) * 0.9
        val segments = Whisper.transcribe(wav16, ModelManager.modelsDir(context), { pct, label ->
            rep.report(spanLo + (spanHi - spanLo) * pct / 100.0, label)
        }) { JobStore.get(job.id)?.status == "canceled" }
        checkCancelled(job)

        val plainTexts = if (payloadLyrics.isNotBlank()) Lyrics.parsePlain(payloadLyrics) else emptyList()
        val timed = if (plainTexts.isNotEmpty()) {
            val heard = segments.flatMap { it.words }
            if (heard.isEmpty()) throw IllegalStateException("AI không nghe được từ nào để đối chiếu lời")
            val full = Align.alignFull(heard, plainTexts, p.durationSec)
            val raw = plainTexts.mapIndexed { i, t ->
                com.example.karaokemaker.core.TimedLine(
                    start = full.starts[i],
                    end = 0.0, // assignEnds sẽ chốt
                    text = t,
                    words = full.words.getOrElse(i) { emptyList() },
                    matched = full.lineMatched.getOrElse(i) { false }
                )
            }
            Lyrics.assignEnds(raw, p.durationSec)
        } else {
            val rawLines = TranscribeLines.segmentsToLines(segments)
            if (rawLines.isEmpty()) {
                throw IllegalStateException("AI chép xong nhưng không tạo được câu lời nào — thử dán lời rồi căn bằng AI")
            }
            Lyrics.assignEnds(rawLines, p.durationSec)
        }
        ProjectStore.saveLines(p.id, timed)
        val allMatched = timed.all { it.matched }
        p.lyricsState = if (allMatched) "synced" else "editing"
        ProjectStore.save(p)
        rep.report(hi, "đã chép lời xong (${timed.size} câu)")
    }

    private fun runTranscribe(context: Context, job: Job, rep: Reporter) {
        ensureLyrics(context, job, rep, 0.0, 100.0)
    }

    // ---------------- chặng: xuất video ----------------

    fun ensureRender(context: Context, job: Job, rep: Reporter, lo: Double, hi: Double) {
        val p = projectOrThrow(job)
        val lines = ProjectStore.loadLines(p.id)
        if (lines.isEmpty()) throw IllegalStateException("Chưa có lời — hãy chép lời/căn nhịp trong Studio trước khi xuất")
        if (!p.beatReady) throw IllegalStateException("Chưa có beat — hãy tách beat trong Studio trước khi xuất")
        ensureSongWav(context, job, rep, lo, lo)
        val dir = ProjectStore.dirOf(p.id)
        val instWav = File(dir, "inst.wav")
        if (!instWav.exists() || instWav.length() == 0L) {
            throw IllegalStateException("Không thấy file beat — hãy tách beat lại trong Studio")
        }
        val songWav = File(dir, "song.wav")
        val style = p.style
        val (w, h) = style.renderSize()

        // ASS
        rep.report(lo + (hi - lo) * 0.03, "đang dựng chữ karaoke")
        val assFile = File(dir, "karaoke.ass")
        val assText = Lyrics.buildAss(lines, style, title = p.title, playX = w, playY = h)
        assFile.writeText(assText, Charsets.UTF_8)

        // Nền
        var bgImage: File? = null
        when (p.bg.type) {
            "gradient" -> {
                bgImage = File(dir, "bg_${p.bg.value}_${w}x${h}.png")
                if (!bgImage.exists()) Ffmpeg.makeGradientBg(p.bg.value, bgImage, w, h)
            }
            "image" -> {
                bgImage = File(p.bg.value)
                if (!bgImage.exists()) throw IllegalStateException("Không thấy ảnh nền đã chọn — chọn lại trong Studio")
            }
            "video" -> {
                if (!File(p.bg.value).exists()) throw IllegalStateException("Không thấy video nền đã chọn — chọn lại trong Studio")
            }
        }

        // Nhạc: beat + giọng dẫn nếu có
        var audio = instWav
        if (style.guidePct > 0) {
            val mixed = File(dir, "mixed.wav")
            Ffmpeg.mixWithGuide(instWav, songWav, style.guidePct, mixed)
            audio = mixed
        }

        val outMp4 = File(dir, "out.mp4")
        val spanLo = lo + (hi - lo) * 0.08
        Ffmpeg.renderVideo(
            bg = p.bg, bgImageFile = bgImage, audioWav = audio, assFile = assFile,
            outMp4 = outMp4, durationSec = p.durationSec, w = w, h = h,
            videoBitrate = style.videoBitrate(), progressBar = style.progressBar
        ) { pct, label -> rep.report(spanLo + (hi - spanLo) * pct / 100.0, label) }
        checkCancelled(job)

        // Lưu vào Movies/KaraokeMaker qua MediaStore + ghi nhận vào dự án
        rep.report(hi - 1, "đang lưu vào thư viện máy")
        val savedName = saveToMovies(context, outMp4, p.title)
        p.exportedUri = savedName.first
        p.exportedName = savedName.second
        ProjectStore.save(p)
        rep.report(hi, "đã xuất video xong")
    }

    private fun runRender(context: Context, job: Job, rep: Reporter) {
        ensureRender(context, job, rep, 0.0, 100.0)
    }

    /**
     * Lưu MP4 thành phẩm vào Movies/KaraokeMaker. API 29+ dùng MediaStore
     * (không cần quyền); API 26–28 ghi thẳng thư mục công khai (đã khai
     * WRITE_EXTERNAL_STORAGE maxSdk 28). Trả về (uriString, displayName).
     */
    fun saveToMovies(context: Context, mp4: File, title: String): Pair<String, String> {
        val safe = title.replace(Regex("""[<>:"/\\|?*]"""), "").trim().take(80)
            .ifEmpty { "karaoke" }
        val displayName = "$safe — Karaoke.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/KaraokeMaker")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Không tạo được bản ghi video trong thư viện máy")
            resolver.openOutputStream(uri)?.use { out ->
                mp4.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Không ghi được video vào thư viện máy")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri.toString() to displayName
        }
        @Suppress("DEPRECATION")
        val moviesDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MOVIES
            ), "KaraokeMaker"
        )
        moviesDir.mkdirs()
        val dst = File(moviesDir, displayName)
        mp4.copyTo(dst, overwrite = true)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DATA, dst.absolutePath)
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        )
        return (uri?.toString() ?: dst.absolutePath) to displayName
    }

    // ---------------- trọn gói ----------------

    /**
     * Tự hành: nạp bài -> tách beat (theo payload sepMode, mặc định fast) ->
     * chép lời AI -> xuất video (nếu payload autoRender=true).
     * Các chặng đã xong (file đã có) tự bỏ qua — chạy tiếp an toàn.
     */
    private fun runPipeline(context: Context, job: Job, rep: Reporter) {
        val payload: JSONObject = job.payloadJson()
        val sepMode = payload.optString("sepMode", "fast")
        val autoRender = payload.optBoolean("autoRender", true)
        ensureSongWav(context, job, rep, 0.0, 18.0)
        checkCancelled(job)
        ensureBeat(context, job, sepMode, rep, 18.0, 48.0)
        checkCancelled(job)
        ensureLyrics(context, job, rep, 48.0, 78.0)
        checkCancelled(job)
        if (autoRender) {
            ensureRender(context, job, rep, 78.0, 100.0)
        } else {
            rep.report(100.0, "đã xong phần lời & beat — mở Studio để chỉnh rồi xuất")
        }
    }
}
