package com.example.karaokemaker.data

/**
 * ĐỒNG HỒ CANH CHẶNG (v1.1.0) — logic quyết định THUẦN, JobService chỉ
 * việc gọi theo nhịp 30 giây.
 *
 * Bài học Karaoke Maker: lỗi đáng sợ nhất không phải "chết" mà là "treo im
 * lặng" — job hiện "running" mãi mà không ai biết (v1.0.0 user đợi oan
 * ~1 giờ). Từ v1.1.0: job nào đang chạy mà [STALL_LIMIT_MS] không có MỘT
 * báo cáo tiến độ nào (Job.lastProgressAt không nhúc nhích) thì service
 * kết thúc job đó với lỗi rõ ràng để hàng đợi chạy bài kế tiếp.
 *
 * Áp cho MỌI loại job, kể cả job tải model: model tải đều có tiến độ theo
 * byte thật (mỗi ~1 MB một nhịp) nên job khoẻ không bao giờ chạm ngưỡng;
 * job tải đứng yên 10 phút thì cũng là treo thật và nên bị cắt như nhau.
 */
object Watchdog {

    const val STALL_LIMIT_MS: Long = 10 * 60 * 1000L

    /** Job này có đang treo im lặng không, tính tại thời điểm [now]. */
    fun isStalled(job: Job, now: Long, limitMs: Long = STALL_LIMIT_MS): Boolean =
        job.status == "running" &&
            job.lastProgressAt > 0L &&
            now - job.lastProgressAt >= limitMs

    /** Nội dung lỗi khi cắt job treo — nêu đúng chặng đang đứng + cách xử lý. */
    fun stallError(job: Job): String {
        val stage = job.label.takeIf { it.isNotBlank() } ?: "đang xử lý"
        return "Chặng $stage đứng yên quá 10 phút — đã dừng job, bấm Chạy tiếp để thử lại"
    }
}
