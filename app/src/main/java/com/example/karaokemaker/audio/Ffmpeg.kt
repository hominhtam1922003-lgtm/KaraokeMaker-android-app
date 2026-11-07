package com.example.karaokemaker.audio

import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.example.karaokemaker.core.BgSpec
import com.example.karaokemaker.core.Gradients
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bọc FFmpegKit (fork maintained 8.1.9 — API giữ nguyên arthenica, bản full
 * có libass). Các lệnh port từ karaoke/media.py của bản PC, đổi 2 điểm theo
 * ràng buộc Android đã chốt:
 *  - encode H.264 bằng h264_mediacodec + pix_fmt nv12 + bitrate (bản full
 *    LGPL không có libx264 như bản PC);
 *  - đốt ASS bằng filter `subtitles` (đã soi AAR 8.1.9 xác nhận filter này +
 *    libass hiện diện). Đường dẫn ASS là đường dẫn tuyệt đối trong filesDir
 *    của app — không chứa ':' ',' hay nháy đơn (khác ổ đĩa Windows) nên an
 *    toàn khi nhúng vào cú pháp filter.
 *
 * Mọi hàm chạy ĐỒNG BỘ (blocking) — chỉ gọi từ thread nền của JobService.
 * executeAsync + latch riêng cho từng lần gọi để biến thành đồng bộ.
 * Tiến độ lấy từ Statistics callback (thời gian đã xử lý / tổng thời lượng).
 */
object Ffmpeg {

    class FfmpegException(message: String) : Exception(message)

    fun interface Progress {
        fun onProgress(pct: Double, label: String)
    }

    private fun runSync(
        args: Array<String>,
        durationSec: Double = 0.0,
        progress: Progress? = null,
        label: String = "",
        timeoutSec: Long = 3600
    ): FFmpegSession {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<FFmpegSession>(1)
        FFmpegKit.executeWithArgumentsAsync(
            args,
            { completed ->
                holder[0] = completed
                latch.countDown()
            },
            null,
            { stats ->
                if (progress != null && durationSec > 0 && stats != null) {
                    val t = stats.time / 1000.0 // Statistics.time tính bằng mili-giây
                    val pct = (t / durationSec * 100.0).coerceIn(0.0, 99.0)
                    progress.onProgress(pct, label)
                }
            }
        )
        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
            FFmpegKit.cancel()
            throw FfmpegException("ffmpeg quá thời gian (${timeoutSec}s) ở bước $label — file đầu vào có thể bị hỏng")
        }
        val done = holder[0] ?: throw FfmpegException("ffmpeg kết thúc bất thường ở bước $label")
        val rc = done.returnCode
        if (ReturnCode.isCancel(rc)) {
            throw FfmpegException("Đã huỷ ở bước $label")
        }
        if (!ReturnCode.isSuccess(rc)) {
            val tail = (done.allLogsAsString ?: "").takeLast(600)
            throw FfmpegException("ffmpeg lỗi (mã $rc) ở bước $label: $tail")
        }
        progress?.onProgress(100.0, label)
        return done
    }

    /** Huỷ phiên ffmpeg đang chạy (nút Huỷ). */
    fun cancelCurrent() {
        try {
            FFmpegKit.cancel()
        } catch (e: Exception) {
            // không có phiên nào đang chạy — bỏ qua
        }
    }

    /** Độ dài file media (giây) bằng MediaMetadataRetriever; lỗi -> 0.0. */
    fun probeDuration(path: String): Double {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            ms / 1000.0
        } catch (e: Exception) {
            0.0
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
    }

    /** Trích audio về WAV stereo 44.1kHz s16 (định dạng làm việc chung). */
    fun extractAudio(src: String, dstWav: File) {
        runSync(
            arrayOf(
                "-y", "-v", "error", "-i", src,
                "-vn", "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le",
                dstWav.absolutePath
            ), label = "trích âm thanh"
        )
    }

    /** Giải mã về WAV mono 16kHz s16 — định dạng engine chép lời ăn trực tiếp. */
    fun decode16kMono(src: String, dstWav: File) {
        runSync(
            arrayOf(
                "-y", "-v", "error", "-i", src,
                "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                dstWav.absolutePath
            ), label = "giải mã cho AI nghe lời"
        )
    }

    /**
     * Tách beat NHANH: hiệu hai kênh (L−R) triệt giọng ở giữa — nguyên lệnh
     * của media.center_cut() bản PC.
     */
    fun centerCut(inWav: File, outWav: File, durationSec: Double, progress: Progress?) {
        runSync(
            arrayOf(
                "-y", "-v", "error", "-i", inWav.absolutePath,
                "-af", "pan=stereo|c0=0.5*c0-0.5*c1|c1=0.5*c1-0.5*c0,volume=2.0,alimiter=limit=0.95",
                "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le",
                outWav.absolutePath
            ), durationSec, progress, "tách beat nhanh"
        )
    }

    /** Trộn beat với một phần giọng gốc (guidePct 0..40%) làm giọng dẫn. */
    fun mixWithGuide(instWav: File, origWav: File, guidePct: Int, outWav: File) {
        val g = guidePct.coerceIn(0, 40) / 100.0
        if (g <= 0.001) {
            instWav.copyTo(outWav, overwrite = true)
            return
        }
        runSync(
            arrayOf(
                "-y", "-v", "error",
                "-i", instWav.absolutePath, "-i", origWav.absolutePath,
                "-filter_complex",
                "[0:a]volume=1.0[a0];[1:a]volume=${"%.3f".format(Locale.US, g)}[a1];" +
                    "[a0][a1]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.95[aout]",
                "-map", "[aout]", "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le",
                outWav.absolutePath
            ), label = "trộn giọng dẫn"
        )
    }

    /** Tạo ảnh nền gradient TĨNH theo mẫu (3 điểm màu + vignette + nhiễu hạt). */
    fun makeGradientBg(kind: String, outPng: File, w: Int, h: Int) {
        val colors = Gradients.colorsOf(kind)
        val src = StringBuilder("gradients=s=${w}x${h}:")
        colors.forEachIndexed { i, c -> if (i > 0) src.append(':'); src.append("c$i=$c") }
        src.append(":nb_colors=${colors.size}:x0=0:y0=0:x1=$w:y1=$h")
        runSync(
            arrayOf(
                "-y", "-v", "error", "-f", "lavfi", "-i", src.toString(),
                "-vf", "vignette=angle=PI/5,noise=alls=5:allf=t",
                "-frames:v", "1", outPng.absolutePath
            ), label = "tạo nền"
        )
        if (!outPng.exists()) throw FfmpegException("Không tạo được nền gradient")
    }

    /**
     * Xuất MP4: nền (gradient tĩnh / màu / ảnh / video lặp) + đốt ASS +
     * thanh tiến trình tuỳ chọn + nhạc beat. Port của media.render_video(),
     * đổi libx264 -> h264_mediacodec theo chuẩn deps.
     * [bgImageFile]: ảnh nền đã chuẩn bị sẵn trong thư mục dự án (cho type
     * gradient/image). Với type video, bg.value là đường dẫn video nền.
     */
    fun renderVideo(
        bg: BgSpec,
        bgImageFile: File?,
        audioWav: File,
        assFile: File,
        outMp4: File,
        durationSec: Double,
        w: Int,
        h: Int,
        videoBitrate: String,
        progressBar: Boolean,
        progress: Progress?
    ) {
        val args = ArrayList<String>()
        args += listOf("-y", "-v", "error")
        val vfilter = StringBuilder()
        when (bg.type) {
            "video" -> {
                args += listOf("-stream_loop", "-1", "-i", bg.value)
                vfilter.append("[0:v]scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,fps=30,setsar=1[bg];")
            }
            "image", "gradient" -> {
                val img = bgImageFile ?: File(bg.value)
                args += listOf("-loop", "1", "-framerate", "30", "-i", img.absolutePath)
                vfilter.append("[0:v]scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,setsar=1[bg];")
            }
            else -> { // color
                val color = bg.value.ifBlank { "#0b0e1a" }.replace("#", "0x")
                args += listOf("-f", "lavfi", "-i", "color=c=$color:s=${w}x${h}:r=30")
                vfilter.append("[0:v]setsar=1[bg];")
            }
        }
        val assPath = assFile.absolutePath
        val barInput: List<String>
        if (progressBar && durationSec > 0) {
            // Thanh tiến trình: dải vàng phủ bằng overlay với x trượt theo thời
            // gian (drawbox/crop không có biến t theo khung — bài học bản PC).
            vfilter.append("[bg]subtitles='$assPath'[vsub];")
            vfilter.append(
                "[vsub][2:v]overlay=x='(t/${"%.3f".format(Locale.US, durationSec)}-1)*overlay_w':y=0[vout]"
            )
            barInput = listOf("-f", "lavfi", "-i", "color=c=0xFFD400:s=${w}x6:r=30")
        } else {
            vfilter.append("[bg]subtitles='$assPath'[vout]")
            barInput = emptyList()
        }
        args += listOf("-i", audioWav.absolutePath)
        args += barInput
        args += listOf(
            "-filter_complex", vfilter.toString(),
            "-map", "[vout]", "-map", "1:a",
            "-c:v", "h264_mediacodec", "-b:v", videoBitrate,
            "-pix_fmt", "nv12", "-r", "30",
            "-c:a", "aac", "-b:a", "192k",
            "-t", "%.3f".format(Locale.US, durationSec),
            "-movflags", "+faststart",
            outMp4.absolutePath
        )
        runSync(
            args.toTypedArray(), durationSec, progress, "xuất video",
            timeoutSec = maxOf(600L, (durationSec * 8).toLong())
        )
        if (!outMp4.exists() || outMp4.length() == 0L) {
            throw FfmpegException("Xuất video xong nhưng không thấy file MP4")
        }
    }
}
