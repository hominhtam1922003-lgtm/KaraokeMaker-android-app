package com.example.karaokemaker.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * Tải audio từ YouTube bằng youtubedl-android (:library 0.18.1 — KHÔNG dùng
 * artifact :ffmpeg vì app chỉ tải audio-only 1 luồng, không gộp/không hậu
 * xử lý nên yt-dlp không bao giờ gọi ffmpeg; mọi giải mã do ffmpeg-kit lo).
 *
 * ĐIỂM SỐNG CÒN (đã chốt ở file deps): yt-dlp đóng gói sẵn sẽ bị YouTube từ
 * chối sau một thời gian -> PHẢI tự cập nhật yt-dlp kênh NIGHTLY lúc chạy,
 * trước lượt tải đầu tiên và mỗi khi mở app (chạy nền, lỗi thì báo ở lần tải).
 */
object Youtube {

    class YoutubeException(message: String) : Exception(message)

    @Volatile
    private var initialized = false

    fun interface Progress {
        fun onProgress(pct: Double, label: String)
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        try {
            YoutubeDL.getInstance().init(context.applicationContext)
            initialized = true
        } catch (e: Exception) {
            throw YoutubeException("Không khởi tạo được bộ tải YouTube: ${e.message}")
        }
    }

    /**
     * Cập nhật yt-dlp lên bản nightly mới nhất. Trả về mô tả trạng thái.
     * Ném lỗi nếu FAILED — người gọi quyết định báo user hay thử tải luôn.
     */
    fun updateNightly(context: Context): String {
        init(context)
        val status = YoutubeDL.getInstance()
            .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
        return status?.name ?: "UNKNOWN"
    }

    /** Lấy tiêu đề video (đồng thời kiểm tra link sống). */
    fun fetchTitle(context: Context, url: String): String {
        init(context)
        val req = YoutubeDLRequest(url)
        req.addOption("--get-title")
        req.addOption("--no-playlist")
        val resp = try {
            YoutubeDL.getInstance().execute(req)
        } catch (e: Exception) {
            throw YoutubeException("Không đọc được link YouTube: ${e.message}")
        }
        val title = (resp.out ?: "").lineSequence().firstOrNull()?.trim().orEmpty()
        if (title.isEmpty()) throw YoutubeException("Link YouTube không trả về tiêu đề — kiểm tra lại link")
        return title
    }

    /**
     * Tải audio-only về [outDir] (tên file source.<ext>), trả về file đã tải.
     * Không dùng --extract-audio/không postprocessor (theo chuẩn deps).
     */
    fun downloadAudio(context: Context, url: String, outDir: File, progress: Progress?): File {
        init(context)
        outDir.mkdirs()
        outDir.listFiles()?.filter { it.name.startsWith("source.") }?.forEach { it.delete() }
        val template = File(outDir, "source.%(ext)s").absolutePath
        val req = YoutubeDLRequest(url)
        req.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
        req.addOption("--no-playlist")
        req.addOption("-o", template)
        try {
            YoutubeDL.getInstance().execute(req) { pct, etaSec, _ ->
                progress?.onProgress(
                    pct.toDouble().coerceIn(0.0, 99.0),
                    if (etaSec > 0) "đang tải từ YouTube (còn ~${etaSec}s)" else "đang tải từ YouTube"
                )
            }
        } catch (e: Exception) {
            throw YoutubeException(
                "Tải YouTube thất bại: ${e.message}. " +
                    "YouTube thay đổi thường xuyên — app đã tự cập nhật yt-dlp; nếu vẫn lỗi, thử lại sau ít phút hoặc chọn file có sẵn trong máy."
            )
        }
        val file = outDir.listFiles()?.firstOrNull {
            it.name.startsWith("source.") && it.length() > 0
        } ?: throw YoutubeException("Tải xong nhưng không thấy file nhạc — thử lại")
        progress?.onProgress(100.0, "đã tải xong")
        return file
    }
}
